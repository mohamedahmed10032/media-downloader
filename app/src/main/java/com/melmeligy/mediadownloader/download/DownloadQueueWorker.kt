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
 * Running inside WorkManager gives us guaranteed execution, network constraints and
 * survival across process death; calling [setForeground] promotes it to a foreground
 * service with the ongoing progress notification.
 */
@HiltWorker
class DownloadQueueWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val manager: DownloadManager
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = manager.currentForegroundInfo()

    override suspend fun doWork(): Result = try {
        setForeground(getForegroundInfo())
        manager.runQueue { info -> runCatching { setForeground(info) } }
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Transient infrastructure failure: let WorkManager retry with backoff.
        Result.retry()
    }
}
