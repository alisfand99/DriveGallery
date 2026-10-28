package app.drivegallery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifier {
    private const val CH_SYNC = "sync"
    private const val CH_ALERT = "alerts"
    const val ID_PROGRESS = 1
    private const val ID_AUTH = 2
    private const val ID_CONSENT = 3
    private const val ID_QUOTA = 4

    const val EXTRA_ACTION = "action"
    const val ACTION_DELETE_UPLOADED = "delete_uploaded"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun createChannels(ctx: Context) {
        nm(ctx).createNotificationChannel(
            NotificationChannel(CH_SYNC, "پیشرفت آپلود", NotificationManager.IMPORTANCE_LOW)
        )
        nm(ctx).createNotificationChannel(
            NotificationChannel(CH_ALERT, "هشدارها", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(ctx: Context, action: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) i.putExtra(EXTRA_ACTION, action)
        return PendingIntent.getActivity(
            ctx, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun progressNotification(ctx: Context, done: Int, total: Int): Notification =
        NotificationCompat.Builder(ctx, CH_SYNC)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("در حال آپلود در Google Drive")
            .setContentText(if (total > 0) "$done از $total" else "آماده‌سازی")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .build()

    fun showProgress(ctx: Context, done: Int, total: Int) {
        nm(ctx).notify(ID_PROGRESS, progressNotification(ctx, done, total))
    }

    fun clearProgress(ctx: Context) = nm(ctx).cancel(ID_PROGRESS)

    private fun alert(ctx: Context, id: Int, title: String, text: String, pi: PendingIntent) {
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm(ctx).notify(id, n)
    }

    fun authNeeded(ctx: Context) = alert(
        ctx, ID_AUTH, "ورود به Google لازم است",
        "اجازه دسترسی به Drive منقضی شده. برنامه را باز کن و دوباره وارد شو تا آپلود ادامه پیدا کند.",
        openApp(ctx)
    )

    fun consentNeeded(ctx: Context, count: Int) = alert(
        ctx, ID_CONSENT, "$count فایل آپلودشده آماده حذف از گوشی است",
        "اندروید برای حذف بی‌صدا به دسترسی «مدیریت رسانه» نیاز دارد. اینجا بزن تا با یک تأیید همه پاک شوند.",
        openApp(ctx, ACTION_DELETE_UPLOADED)
    )

    fun quotaFull(ctx: Context) = alert(
        ctx, ID_QUOTA, "فضای Google Drive پر شده",
        "آپلود متوقف شد. فایل‌های گوشی دست‌نخورده مانده‌اند.",
        openApp(ctx)
    )
}
