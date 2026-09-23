package app.pwhs.blockads.service

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.NumberFormat
import java.util.Locale

class NotificationHelper(
    private val context: Context,
    private val appPrefs: AppPreferences
) {
    companion object {
        const val MILESTONE_CHANNEL_ID = "blockads_milestone_channel"
        private const val MILESTONE_NOTIFICATION_ID = 3001
        val MILESTONES = longArrayOf(1_000, 10_000, 50_000, 100_000, 1_000_000)

        /** Highest milestone reached by [blocked] and newer than [lastSeen]; null when [enabled] is off. */
        fun unseenMilestone(blocked: Long, lastSeen: Long, enabled: Boolean): Long? {
            if (!enabled) return null
            val reached = MILESTONES.filter { it <= blocked }.maxOrNull() ?: return null
            return reached.takeIf { it > lastSeen }
        }

        /** Milestone the service should notify for; null while the app is open, since the Home sheet covers that case. */
        fun milestoneToNotify(blocked: Long, announced: Long, appInForeground: Boolean): Long? =
            if (appInForeground) null else unseenMilestone(blocked, announced, enabled = true)
    }

    private val milestoneMutex = Mutex()

    // Shares seen state with the Home sheet so each milestone is announced once, by whichever surface
    // gets there first. [blockedCount] is queried only while a milestone is still pending.
    suspend fun checkAndNotifyMilestone(blockedCount: suspend () -> Long) {
        milestoneMutex.withLock {
            if (!appPrefs.milestoneNotificationsEnabled.first()) return
            val announced = maxOf(appPrefs.lastMilestoneBlocked.first(), appPrefs.lastSeenMilestoneDialog.first())
            if (MILESTONES.none { it > announced }) return
            val milestone = milestoneToNotify(blockedCount(), announced, isAppInForeground()) ?: return
            appPrefs.setLastMilestoneBlocked(milestone)
            appPrefs.setLastSeenMilestoneDialog(milestone)
            showMilestoneNotification(milestone)
        }
    }

    private fun isAppInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun showMilestoneNotification(milestone: Long) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MILESTONE_CHANNEL_ID,
                context.getString(R.string.milestone_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.milestone_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedCount = NumberFormat.getNumberInstance(Locale.getDefault()).format(milestone)
        val notification = NotificationCompat.Builder(context, MILESTONE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.milestone_title))
            .setContentText(context.getString(R.string.milestone_text, formattedCount))
            .setSmallIcon(R.drawable.ic_shield_on)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(MILESTONE_NOTIFICATION_ID, notification)
    }
}
