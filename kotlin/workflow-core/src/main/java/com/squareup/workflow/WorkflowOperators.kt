/*
 * Copyright 2018 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.workflow

import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.Dispatchers.Unconfined
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.channels.toChannel
import kotlinx.coroutines.experimental.launch
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Defines the [CoroutineContext] used by [Workflow] operators below.
 *
 * See this module's README for an explanation of why Unconfined is used.
 */
private val operatorScope = CoroutineScope(Unconfined)

/**
 * [Transforms][https://stackoverflow.com/questions/15457015/explain-contramap]
 * the receiver to accept events of type [E2] instead of [E1].
 */
fun <S : Any, E2 : Any, E1 : Any, O : Any> Workflow<S, E1, O>.adaptEvents(transform: (E2) -> E1):
    Workflow<S, E2, O> = object : Workflow<S, E2, O>, Deferred<O> by this {
  override fun openSubscriptionToState(): ReceiveChannel<S> =
    this@adaptEvents.openSubscriptionToState()

  override fun sendEvent(event: E2) = this@adaptEvents.sendEvent(transform(event))
}

/**
 * Transforms the receiver to emit states of type [S2] instead of [S1].
 */
fun <S1 : Any, S2 : Any, E : Any, O : Any> Workflow<S1, E, O>.mapState(
  transform: suspend (S1) -> S2
): Workflow<S2, E, O> = object : Workflow<S2, E, O>,
    Deferred<O> by this,
    WorkflowInput<E> by this {
  override fun openSubscriptionToState(): ReceiveChannel<S2> =
    operatorScope.produce {
      val source = this@mapState.openSubscriptionToState()
      source.consumeEach {
        send(transform(it))
      }
    }
}

/**
 * Like [mapState], transforms the receiving workflow with
 * [state][Workflow.openSubscriptionToState] of type [S1] to one with states of [S2]. Unlike that
 * method, each [S1] update is transformed into a stream of [S2] updates -- useful when an [S1]
 * state might wrap an underlying workflow whose own screens need to be shown.
 */
fun <S1 : Any, S2 : Any, E : Any, O : Any> Workflow<S1, E, O>.switchMapState(
  transform: suspend CoroutineScope.(S1) -> ReceiveChannel<S2>
): Workflow<S2, E, O> = object : Workflow<S2, E, O>,
    Deferred<O> by this,
    WorkflowInput<E> by this {
  override fun openSubscriptionToState(): ReceiveChannel<S2> =
    operatorScope.produce(capacity = CONFLATED) {
      val upstreamChannel = this@switchMapState.openSubscriptionToState()
      val downstreamChannel = channel
      var transformerJob: Job? = null

      upstreamChannel.consumeEach { upstreamState ->
        // Stop emitting states from the previous transformed channel before processing the new
        // item. This behavior is what differentiates switchMap from other flatMap variants.
        transformerJob?.cancel()
        // Start a new coroutine to forward all items downstream. While this is running, we'll go
        // back to waiting for the next upstream state. If the upstream channel is closed, we'll
        // leave the consumeEach loop but the produce coroutine will wait for this child coroutine
        // to complete (structured concurrency) before closing the downstream channel.
        transformerJob = launch {
          transform(upstreamState).toChannel(downstreamChannel)
        }
      }
    }
}

/**
 * Transforms the receiver to emit a result of type [O2] instead of [O1].
 */
fun <S : Any, E : Any, O1 : Any, O2 : Any> Workflow<S, E, O1>.mapResult(
  transform: suspend (O1) -> O2
): Workflow<S, E, O2> {
  // We can't just make the downstream a child of the upstream workflow to propagate cancellation,
  // since the downstream's call to `await` would never return (parent waits for all its children
  // to complete).
  val transformedResult = operatorScope.async {
    transform(this@mapResult.await())
  }

  // Propagate cancellation upstream.
  transformedResult.invokeOnCompletion { cause ->
    if (cause != null) {
      this@mapResult.cancel(cause)
    }
  }

  return object : Workflow<S, E, O2>,
      Deferred<O2> by transformedResult,
      WorkflowInput<E> by this {
    override fun openSubscriptionToState(): ReceiveChannel<S> =
      this@mapResult.openSubscriptionToState()
  }
}
