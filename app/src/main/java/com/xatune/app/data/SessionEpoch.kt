package com.xatune.app.data

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

class SessionEpoch {
    private val generation = AtomicLong()
    fun capture(): Long = generation.get()
    fun invalidate(): Long = generation.incrementAndGet()
    fun check(version: Long) {
        if (version != generation.get()) throw CancellationException("账号会话已变化")
    }
}
