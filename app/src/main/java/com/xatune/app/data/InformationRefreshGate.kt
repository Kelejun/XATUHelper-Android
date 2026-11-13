package com.xatune.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object InformationRefreshGate {
    private val mutex = Mutex()
    suspend fun waitForSlot() = mutex.withLock { delay(3000) }
}
