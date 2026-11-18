package com.melmeligy.mediadownloader.util

import com.melmeligy.mediadownloader.core.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** DispatcherProvider that routes every dispatcher to a single test dispatcher. */
class TestDispatcherProvider(
    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()
) : DispatcherProvider {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}

