package app.drivegallery

import android.content.Context
import android.content.pm.ServiceInfo
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Scans, uploads and (optionally) cleans up. Runs on a schedule and whenever new media appears. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            Notifier.ID_PROGRESS,
            Notifier.progressNotification(applicationContext, 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        if (!app.prefs.authorized || !app.scanner.hasFullAccess()) return Result.success()

        var foreground = false
        return try {
            app.engine.run { done, total ->
                if (!foreground && total > 0) {
                    foreground = true
                    try {
                        setForeground(getForegroundInfo())
                    } catch (e: Exception) {
                        // not allowed to start a foreground service right now; keep going without it
                    }
                }
                if (total > 0) Notifier.showProgress(app, done, total)
            }
            Notifier.clearProgress(app)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthNeeded) {
            Notifier.clearProgress(app)
            Notifier.authNeeded(app)
            Result.success()
        } catch (e: Exception) {
            Notifier.clearProgress(app)
            if (app.engine.isQuota(e)) {
                Notifier.quotaFull(app)
                Result.success()
            } else {
                Result.retry()
            }
        }
    }
}

/** Fires when MediaStore changes (a new photo was taken), starts a sync, then arms itself again. */
class MediaTriggerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Scheduler.syncSoon(applicationContext)
        Scheduler.armTrigger(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

object Scheduler {
    private const val PERIODIC = "periodic_sync"
    private const val TRIGGER = "media_trigger"
    private const val SYNC = "sync_now"

    private fun constraints(ctx: Context): Constraints.Builder {
        val prefs = Prefs(ctx)
        return Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(prefs.chargingOnly)
    }

    /** Called at app start and whenever a setting that affects scheduling changes. */
    fun ensureScheduled(ctx: Context, replace: Boolean = false) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints(ctx).build()).build()
        )
        armTrigger(ctx, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    /**
     * [policy] KEEP at app start (do not stack duplicates), REPLACE after a settings change,
     * APPEND_OR_REPLACE from the trigger worker itself so it re-arms right after it finishes.
     */
    fun armTrigger(ctx: Context, policy: ExistingWorkPolicy) {
        val c = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            TRIGGER, policy, OneTimeWorkRequestBuilder<MediaTriggerWorker>().setConstraints(c).build()
        )
    }

    /** Normal-priority sync, skipped if one is already queued. */
    fun syncSoon(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            SYNC, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(ctx).build()).build()
        )
    }

    /** User pressed "sync now": run as soon as possible. */
    fun syncNow(ctx: Context) {
        val builder = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(ctx).build())
        // Expedited work only allows network constraints, so skip it when "charging only" is on.
        if (!Prefs(ctx).chargingOnly) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(ctx).enqueueUniqueWork(SYNC, ExistingWorkPolicy.REPLACE, builder.build())
    }

    fun cancelAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(TRIGGER)
        wm.cancelUniqueWork(SYNC)
    }
}
