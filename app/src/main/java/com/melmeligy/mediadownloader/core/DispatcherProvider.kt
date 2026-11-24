package com.melmeligy.mediadownloader.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/** Abstraction over coroutine dispatchers so business logic stays testable. */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
    /** Dedicated I/O view for interactive link extraction isolated from bulk download workers. */
    val extractionIo: CoroutineDispatcher get() = io
}

@Singleton
class DefaultDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val extractionIo: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(24)
}
