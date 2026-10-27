package dev.dx.wristsound.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive

/** A transient sensor failure must not clear the user's persistent enabled setting. */
fun <T> Flow<T>.recoveringSubscription(
    retryMillis: Long = 30_000,
    onInterrupted: suspend (Throwable?) -> Unit,
): Flow<T> = flow {
    require(retryMillis > 0)
    while (currentCoroutineContext().isActive) {
        // retryWhen preserves flow exception transparency: downstream errors are never swallowed.
        emitAll(this@recoveringSubscription.retryWhen { error, _ ->
            if (error is CancellationException || error !is Exception) throw error
            onInterrupted(error)
            delay(retryMillis)
            true
        })
        onInterrupted(null)
        delay(retryMillis)
    }
}
