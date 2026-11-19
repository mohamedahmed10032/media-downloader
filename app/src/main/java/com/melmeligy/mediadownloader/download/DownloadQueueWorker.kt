package com.melmeligy.mediadownloader.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * WorkManager-driven foreground worker that drains the download queue via [DownloadManager].
 *
 * Protected against Android 14/15 `ForegroundServiceStartNotAllowedException`: if background
 * foreground-service promotion is blocked by OS restrictions, the worker degrades gracefully
 * and continues draining the queue instead of failing or entering an infinite retry loop.
 */
@HiltWorker
class DownloadQueueWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val manager: DownloadManager
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = manager.currentForegroundInfo()

    override suspend fun doWork(): Result = try {
        trySetForeground(getForegroundInfo())
        manager.runQueue { info -> trySetForeground(info) }
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Transient infrastructure failure: let WorkManager retry with backoff.
        Result.retry()
    }

    private suspend fun trySetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Android 14/15 ForegroundServiceStartNotAllowedException or SecurityException:
            // continue downloading in WorkManager without failing or retrying indefinitely.
        }
    }
}
