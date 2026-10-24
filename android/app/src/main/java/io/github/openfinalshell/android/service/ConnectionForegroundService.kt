package io.github.openfinalshell.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import io.github.openfinalshell.android.MainActivity
import io.github.openfinalshell.android.MainViewModel
import io.github.openfinalshell.android.OpenFinalShellApplication
import io.github.openfinalshell.android.R

/**
 * Keeps the process in the foreground while an SSH session, transfer, or forward is open.
 *
 * Leaving the app, rotating, or swiping the task away does not close those sessions. A partial
 * wake lock lets the SSH heartbeat run while the screen is off. A Wi-Fi lock keeps the radio from
 * sleeping on Wi-Fi. Force-stop and a low-memory kill still drop the sockets; a sticky restart
 * only tells the user to reopen the app.
 */
class ConnectionForegroundService : Service() {
    private var stopRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_connections),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val restartedBySystem = intent == null
        if (restartedBySystem && liveWorkbench()?.hasBackgroundWork() == true) {
            // The process survived and still owns sockets. Refresh the notification from the model
            // instead of treating this as a cold start.
            liveWorkbench()?.ensureBackgroundKeepAlive()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP || stopRequested) {
            stopRequested = true
            clearCounts()
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_UPDATE || intent?.action == ACTION_START) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_SESSIONS, intent.getIntExtra(EXTRA_SESSIONS, 0))
                .putInt(KEY_TRANSFERS, intent.getIntExtra(EXTRA_TRANSFERS, 0))
                .putInt(KEY_FORWARDS, intent.getIntExtra(EXTRA_FORWARDS, 0))
                .apply()
        }
        val sessions = count(KEY_SESSIONS)
        val transfers = count(KEY_TRANSFERS)
        val forwards = count(KEY_FORWARDS)
        val connecting = intent?.action == ACTION_START || intent?.getBooleanExtra(EXTRA_CONNECTING, false) == true
        if (!SessionKeepAlivePolicy.keepRunningAfterStart(restartedBySystem, connecting, sessions, transfers, forwards)) {
            // Process death cannot restore TCP. An empty update should not leave a permanent notification.
            if (restartedBySystem) {
                notifySessionsEnded()
            }
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply()
        startInForeground(notification(holding = true, connecting = connecting))
        acquireLocks()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val sessions = count(KEY_SESSIONS)
        val transfers = count(KEY_TRANSFERS)
        val forwards = count(KEY_FORWARDS)
        if (!SessionKeepAlivePolicy.shouldHold(sessions, transfers, forwards)) {
            super.onTaskRemoved(rootIntent)
            return
        }
        // Swiping the app away destroys the activity. Promote the service again so the process,
        // and the sessions owned by the application-scoped model, stay up.
        val restart = Intent(applicationContext, ConnectionForegroundService::class.java)
            .setAction(ACTION_UPDATE)
            .putExtra(EXTRA_SESSIONS, sessions)
            .putExtra(EXTRA_TRANSFERS, transfers)
            .putExtra(EXTRA_FORWARDS, forwards)
        runCatching { ContextCompat.startForegroundService(applicationContext, restart) }
            .onFailure { Log.w(TAG, "Unable to keep the session service after the task was removed", it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        if (stopRequested) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_ACTIVE).apply()
        }
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openfinalshell:ssh").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock?.isHeld != true) {
            val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "openfinalshell:ssh").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    private fun count(key: String): Int = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(key, 0)

    private fun clearCounts() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .remove(KEY_ACTIVE)
            .remove(KEY_SESSIONS)
            .remove(KEY_TRANSFERS)
            .remove(KEY_FORWARDS)
            .apply()
    }

    private fun liveWorkbench(): MainViewModel? {
        val app = application as? OpenFinalShellApplication ?: return null
        return ViewModelProvider(
            app.workbenchStore,
            ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        )[MainViewModel::class.java]
    }

    private fun openIntent(): PendingIntent {
        val launch = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notification(holding: Boolean, connecting: Boolean = false): Notification {
        val sessions = count(KEY_SESSIONS)
        val transfers = count(KEY_TRANSFERS)
        val forwards = count(KEY_FORWARDS)
        val summary = if (holding && (connecting || sessions > 0 || transfers > 0 || forwards > 0)) {
            getString(R.string.notification_summary, sessions, transfers, forwards)
        } else {
            getString(R.string.notification_reopen_to_restore)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(summary)
            .setContentIntent(openIntent())
            .setOngoing(holding)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifySessionsEnded() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_reopen_to_restore))
                .setContentIntent(openIntent())
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        const val ACTION_STOP = "io.github.openfinalshell.android.action.STOP"
        const val ACTION_START = "io.github.openfinalshell.android.action.START"
        const val ACTION_UPDATE = "io.github.openfinalshell.android.action.UPDATE"
        const val EXTRA_SESSIONS = "sessions"
        const val EXTRA_TRANSFERS = "transfers"
        const val EXTRA_FORWARDS = "forwards"
        const val EXTRA_CONNECTING = "connecting"
        private const val TAG = "ConnectionForeground"
        private const val CHANNEL_ID = "connections"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "background_service"
        private const val KEY_ACTIVE = "active"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_TRANSFERS = "transfers"
        private const val KEY_FORWARDS = "forwards"

        fun begin(context: Context) {
            val intent = Intent(context, ConnectionForegroundService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Unable to start the session service", it) }
        }

        fun update(context: Context, sessions: Int, transfers: Int, forwards: Int = 0, connecting: Boolean = false) {
            val intent = Intent(context, ConnectionForegroundService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_SESSIONS, sessions)
                .putExtra(EXTRA_TRANSFERS, transfers)
                .putExtra(EXTRA_FORWARDS, forwards)
                .putExtra(EXTRA_CONNECTING, connecting)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Unable to update the session service", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ConnectionForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Unable to stop the session service", it) }
        }
    }
}
