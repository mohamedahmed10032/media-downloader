package com.melmeligy.mediadownloader.util

import com.melmeligy.mediadownloader.core.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher

/** DispatcherProvider that routes every dispatcher to a single test dispatcher. */
class TestDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}
