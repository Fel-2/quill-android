package fel.quill.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import fel.quill.android.data.MarkdownParser
import fel.quill.android.data.QuillGraph
import fel.quill.android.data.SecureStore
import fel.quill.android.model.Todo

object ReminderScheduler {
    private const val ALARM_REQUEST = 2000
    private const val WORK_NAME = "quill-reminder-sync"
    private const val EXTRA_PATH = "path"
    private const val EXTRA_TEXT = "text"

    fun schedule(context: Context) {
        val request = androidx.work.PeriodicWorkRequestBuilder<ReminderWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .build()
        androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        reschedule(context)
    }

    fun reschedule(context: Context) {
        val pending = nextAlarm(context)
        val manager = context.getSystemService<AlarmManager>() ?: return
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .putExtra(EXTRA_PATH, pending?.path)
            .putExtra(EXTRA_TEXT, pending?.text)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val alarmIntent = PendingIntent.getBroadcast(context, ALARM_REQUEST, intent, flags)
        if (pending == null) {
            manager.cancel(alarmIntent)
            return
        }
        val triggerAt = pending.triggerAt
        val canExact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent)
            }
        }
    }

    private fun nextAlarm(context: Context): Pending? {
        val config = SecureStore(context).loadBridge() ?: return null
        val library = runCatching { QuillGraph.repository(context).loadLocal() }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        return library.todos
            .asSequence()
            .filter { !it.done && it.due != null && it.time != null }
            .mapNotNull { todo -> triggerFor(todo)?.let { Pending(it, todo.path, todo.text) } }
            .filter { it.triggerAt > now }
            .minByOrNull { it.triggerAt }
    }

    private fun triggerFor(todo: Todo): Long? {
        val due = todo.due ?: return null
        val time = MarkdownParser.normalizeTime(todo.time) ?: return null
        return runCatching {
            val date = java.time.LocalDate.parse(due)
            val (hour, minute) = time.split(":").let { it[0].toInt() to it[1].toInt() }
            date.atTime(hour, minute).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    data class Pending(val triggerAt: Long, val path: String, val text: String)
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        ReminderWorker.createChannel(context)
        val ok = android.os.Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (ok) {
            val pending = PendingIntent.getActivity(
                context,
                10,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = androidx.core.app.NotificationCompat.Builder(context, ReminderWorker.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_quill)
                .setContentTitle("Quill")
                .setContentText(text)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .build()
            androidx.core.app.NotificationManagerCompat.from(context).notify(42, notification)
        }
        ReminderScheduler.reschedule(context)
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ReminderScheduler.schedule(context)
        }
    }
}
