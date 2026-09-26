package fel.quill.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fel.quill.android.data.QuillGraph
import fel.quill.android.data.SecureStore
import java.util.concurrent.TimeUnit

class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = SecureStore(applicationContext)
        val config = store.loadBridge() ?: return Result.success()
        val repository = QuillGraph.repository(applicationContext)
        val library = QuillGraph.withSyncLock {
            runCatching { repository.sync(config) }.getOrElse { repository.loadLocal() }
        }
        val counts = library.counts
        if (counts.dueToday == 0 && counts.overdue == 0) return Result.success()
        val signature = "${java.time.LocalDate.now()}|$counts.dueToday|$counts.overdue"
        val preferences = applicationContext.getSharedPreferences("quill_reminders", Context.MODE_PRIVATE)
        if (preferences.getString("signature", null) == signature) return Result.success()
        createChannel(applicationContext)
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            applicationContext,
            10,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = buildList {
            if (counts.dueToday > 0) add("${counts.dueToday} due today")
            if (counts.overdue > 0) add("${counts.overdue} overdue")
        }.joinToString(" · ")
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quill)
            .setContentTitle("Quill")
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        if (android.os.Build.VERSION.SDK_INT < 33 || applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }
        preferences.edit().putString("signature", signature).apply()
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "quill-reminders"
        private const val NOTIFICATION_ID = 41

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Due reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
