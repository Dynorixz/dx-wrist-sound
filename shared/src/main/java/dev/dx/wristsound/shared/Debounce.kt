package dev.dx.wristsound.shared

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(FlowPreview::class)
fun Flow<WristState>.confirmedWristState(delayMillis: Long, offDelayMillis: Long = delayMillis): Flow<WristState> =
    distinctUntilChanged().debounce {
        when (it) {
            WristState.UNKNOWN -> 0
            WristState.OFF_WRIST -> offDelayMillis
            WristState.ON_WRIST -> delayMillis
        }
    }.distinctUntilChanged()
