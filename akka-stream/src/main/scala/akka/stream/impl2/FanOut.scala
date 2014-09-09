/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.stream.MaterializerSettings
import akka.stream.impl.{ BatchingInputBuffer, Pump, SimpleOutputs, SubReceive, TransferState, _ }
import org.reactivestreams.{ Subscription, Subscriber, Publisher }

import scala.collection.immutable

/**
 * INTERNAL API
 */
private[akka] object FanOut {

  case class SubstreamRequestMore(id: Int, demand: Long)
  case class SubstreamCancel(id: Int)
  case class SubstreamSubscribePending(id: Int)

  class SubstreamSubscription(val parent: ActorRef, val id: Int) extends Subscription {
    override def request(elements: Long): Unit =
      if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
      else parent ! SubstreamRequestMore(id, elements)
    override def cancel(): Unit = parent ! SubstreamCancel(id)
    override def toString = "SubstreamSubscription" + System.identityHashCode(this)
  }

  class FanoutOutputs(val id: Int, _impl: ActorRef, _pump: Pump) extends SimpleOutputs(_impl, _pump) {
    override def createSubscription(): Subscription = new SubstreamSubscription(self, id)
  }

  case class ExposedPublishers(publishers: immutable.Seq[ActorPublisher[Any]])

  class OutputBunch(outputCount: Int, impl: ActorRef, pump: Pump) {
    private var bunchCancelled = false

    private val outputs = Array.tabulate(outputCount)(new FanoutOutputs(_, impl, pump))

    private val marked = Array.ofDim[Boolean](outputCount)
    private var markCount = 0
    private val pending = Array.ofDim[Boolean](outputCount)
    private var markedPending = 0
    private val cancelled = Array.ofDim[Boolean](outputCount)
    private var markedCancelled = 0

    private var unmarkCancelled = true

    private var preferredId = 0

    def complete(): Unit =
      if (!bunchCancelled) {
        bunchCancelled = true
        outputs foreach (_.complete())
      }

    def cancel(e: Throwable): Unit =
      if (!bunchCancelled) {
        bunchCancelled = true
        outputs foreach (_.cancel(e))
      }

    def markOutput(output: Int): Unit = {
      if (!marked(output)) {
        if (cancelled(output)) markedCancelled += 1
        if (pending(output)) markedPending += 1
        marked(output) = true
        markCount += 1
      }
    }

    def unmarkOutput(output: Int): Unit = {
      if (marked(output)) {
        if (cancelled(output)) markedCancelled -= 1
        if (pending(output)) markedPending -= 1
        marked(output) = false
        markCount -= 1
      }
    }

    def unmarkCancelledOutputs(enabled: Boolean): Unit = unmarkCancelled = enabled

    private def idToEnqueue(): Int = {
      var id = preferredId
      while (!(marked(id) && pending(id))) {
        id += 1
        if (id == outputCount) id = 0
        assert(id != preferredId, "Tried to enqueue without waiting for any demand")
      }
      id
    }

    def enqueue(id: Int, elem: Any): Unit = {
      val output = outputs(id)
      output.enqueueOutputElement(elem)
      if (!output.demandAvailable) {
        markedPending -= 1
        pending(id) = false
      }
    }

    def enqueueMarked(elem: Any): Unit = {
      var id = 0
      while (id < outputCount) {
        if (marked(id)) enqueue(id, elem)
        id += 1
      }
    }

    def enqueueAndYield(elem: Any): Unit = {
      val id = idToEnqueue()
      preferredId = (id + 1) % outputCount
      enqueue(id, elem)
    }

    def enqueueAndPrefer(elem: Any, preferred: Int): Unit = {
      val id = idToEnqueue()
      preferredId = preferred
      enqueue(id, elem)
    }

    val AllOfMarkedOutputs = new TransferState {
      override def isCompleted: Boolean = markedCancelled > 0
      override def isReady: Boolean = markedPending == markCount
    }

    val AnyOfMarkedOutputs = new TransferState {
      override def isCompleted: Boolean = markedCancelled == markCount
      override def isReady: Boolean = markedPending > 0
    }

    // FIXME: Eliminate re-wraps
    def subreceive: SubReceive = new SubReceive({
      case ExposedPublishers(publishers) ⇒
        publishers.zip(outputs) foreach {
          case (pub, output) ⇒
            output.subreceive(ExposedPublisher(pub))
        }

      case SubstreamRequestMore(id, demand) ⇒
        if (marked(id) && !pending(id)) markedPending += 1
        pending(id) = true
        outputs(id).subreceive(RequestMore(null, demand))
      case SubstreamCancel(id) ⇒
        if (unmarkCancelled) {
          if (marked(id)) markCount -= 1
          marked(id) = false
        }
        if (marked(id) && !cancelled(id)) markedCancelled += 1
        cancelled(id) = true
        outputs(id).subreceive(Cancel(null))
      case SubstreamSubscribePending(id) ⇒ outputs(id).subreceive(SubscribePending)
    })

  }

}

abstract class FanOut(val settings: MaterializerSettings, val outputPorts: Int) extends Actor with ActorLogging with Pump {
  import akka.stream.impl2.FanOut._

  protected val outputBunch = new OutputBunch(outputPorts, self, this)
  protected val primaryInputs: Inputs = new BatchingInputBuffer(settings.maxInputBufferSize, this) {
    override def onError(e: Throwable): Unit = fail(e)
  }

  override def pumpFinished(): Unit = {
    primaryInputs.cancel()
    outputBunch.complete()
    context.stop(self)
  }

  override def pumpFailed(e: Throwable): Unit = fail(e)

  protected def fail(e: Throwable): Unit = {
    log.error(e, "failure during processing") // FIXME: escalate to supervisor instead
    primaryInputs.cancel()
    outputBunch.cancel(e)
    context.stop(self)
  }

  override def postStop(): Unit = {
    primaryInputs.cancel()
    outputBunch.cancel(new IllegalStateException("Processor actor terminated abruptly"))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    throw new IllegalStateException("This actor cannot be restarted")
  }

  def receive = primaryInputs.subreceive orElse outputBunch.subreceive

}

class Broadcast(_settings: MaterializerSettings, _outputPorts: Int) extends FanOut(_settings, _outputPorts) {
  (0 until outputPorts) foreach outputBunch.markOutput

  nextPhase(TransferPhase(primaryInputs.NeedsInput && outputBunch.AllOfMarkedOutputs) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    outputBunch.enqueueMarked(elem)
  })
}

class Balance(_settings: MaterializerSettings, _outputPorts: Int) extends FanOut(_settings, _outputPorts) {
  (0 until outputPorts) foreach outputBunch.markOutput

  nextPhase(TransferPhase(primaryInputs.NeedsInput && outputBunch.AnyOfMarkedOutputs) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    outputBunch.enqueueAndYield(elem)
  })
}

class Unzip(_settings: MaterializerSettings, _outputPorts: Int) extends FanOut(_settings, _outputPorts) {
  (0 until outputPorts) foreach outputBunch.markOutput

  nextPhase(TransferPhase(primaryInputs.NeedsInput && outputBunch.AllOfMarkedOutputs) { () ⇒
    val (elem0, elem1) = primaryInputs.dequeueInputElement().asInstanceOf[(Any, Any)]
    outputBunch.enqueue(0, elem0)
    outputBunch.enqueue(1, elem1)
  })
}
