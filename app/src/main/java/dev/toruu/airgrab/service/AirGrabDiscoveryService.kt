package dev.toruu.airgrab.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.toruu.airgrab.AirGrabApp
import dev.toruu.airgrab.MainActivity
import dev.toruu.airgrab.R
import dev.toruu.airgrab.data.nearby.ControlMessage
import dev.toruu.airgrab.data.nearby.NearbyConnectionsManager
import dev.toruu.airgrab.domain.GrabDropStateMachine
import dev.toruu.airgrab.ui.ReceiverWakeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AirGrabDiscoveryService : Service() {

    companion object {
        private const val TAG = "AirGrab_WakeService"
        const val ACTION_START = "dev.toruu.airgrab.action.START_DISCOVERY_SERVICE"
        const val ACTION_STOP = "dev.toruu.airgrab.action.STOP_DISCOVERY_SERVICE"

        const val CHANNEL_SERVICE_ID = "airgrab_service_channel"
        const val CHANNEL_WAKE_ID = "airgrab_wake_channel"
        const val SERVICE_NOTIFICATION_ID = 1001
        const val WAKE_NOTIFICATION_ID = 1002

        private const val MAX_WAKES_PER_MINUTE = 3
        private const val RATE_LIMIT_WINDOW_MS = 60_000L

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, AirGrabDiscoveryService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AirGrabDiscoveryService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nearbyTransport: NearbyConnectionsManager? = null
    private val wakeTimestamps = mutableListOf<Long>()

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannels()

        // Share the single process-wide transport (Nearby keeps one client per app).
        nearbyTransport = AirGrabApp.from(this).transport

        startServiceForeground()
        listenForGrabSignals()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // Advertising is already started (idempotently) in startServiceForeground().
                Log.d(TAG, "Ambient discovery already hosting the open room")
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "AirGrab Background Discovery",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps low-power BLE discovery active for spontaneous file drops"
                setShowBadge(false)
            }

            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val wakeChannel = NotificationChannel(
                CHANNEL_WAKE_ID,
                "AirGrab Incoming Drop Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Wakes display and notifies when a peer initiates a Grab"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(
                    ringtoneUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(wakeChannel)
        }
    }

    private fun startServiceForeground() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("AirGrab Ambient Discovery")
            .setContentText("Listening for touchless Grab & Release transfers...")
            .setSmallIcon(R.drawable.ic_airgrab_tile)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }

        nearbyTransport?.startAutoRoom(Build.MODEL)
        Log.d(TAG, "AirGrabDiscoveryService started in foreground (symmetric advertise + discover)")
    }

    private fun listenForGrabSignals() {
        val transport = nearbyTransport ?: return
        serviceScope.launch {
            transport.incomingControl.collect { (endpointId, message) ->
                if (message is ControlMessage.GrabReady) {
                    handleIncomingGrab(endpointId, message)
                }
            }
        }
    }

    @Synchronized
    private fun isRateLimited(): Boolean {
        val now = System.currentTimeMillis()
        wakeTimestamps.removeAll { now - it > RATE_LIMIT_WINDOW_MS }
        return if (wakeTimestamps.size >= MAX_WAKES_PER_MINUTE) {
            Log.w(TAG, "Wake rate limit exceeded (${wakeTimestamps.size} in past 60s), suppressing wake.")
            true
        } else {
            wakeTimestamps.add(now)
            false
        }
    }

    private fun handleIncomingGrab(endpointId: String, grabReady: ControlMessage.GrabReady) {
        if (isRateLimited()) return

        if (AirGrabApp.from(this).isAppForeground) {
            // The activity is on screen and arms itself from the shared transport; launching a
            // second drop UI here would race with it for the same payload.
            Log.d(TAG, "App is foreground, in-app receiver handles this grab")
            return
        }

        Log.d(TAG, "Grab signal detected from ${grabReady.senderName} (${grabReady.fileName}). Waking screen...")

        // Robust wake lock to turn on screen across modern Samsung/Honor/Android devices
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "airgrab:wake_alert"
        )
        // Keep the screen on for the whole drop window (12s) plus launch overhead.
        wakeLock.acquire(13_000L)

        val wakeIntent = Intent(this, ReceiverWakeActivity::class.java).apply {
            putExtra(ReceiverWakeActivity.EXTRA_SESSION_ID, grabReady.sessionId)
            // Pass the endpoint the GrabReady actually arrived from - that is the only id this
            // device can use to send AcceptDrop back. grabReady.senderEndpointId belongs to the
            // sender's own view of its peers and is not routable here.
            putExtra(ReceiverWakeActivity.EXTRA_SENDER_ENDPOINT_ID, endpointId)
            putExtra(ReceiverWakeActivity.EXTRA_SENDER_NAME, grabReady.senderName)
            putExtra(ReceiverWakeActivity.EXTRA_FILE_NAME, grabReady.fileName)
            putExtra(ReceiverWakeActivity.EXTRA_MIME_TYPE, grabReady.mimeType)
            putExtra(ReceiverWakeActivity.EXTRA_SIZE_BYTES, grabReady.sizeBytes)
            // Local deadline: the sender's absolute timestamp comes from a different clock.
            putExtra(
                ReceiverWakeActivity.EXTRA_EXPIRES_AT,
                System.currentTimeMillis() + GrabDropStateMachine.GRAB_READY_TTL_MS
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        // Direct Activity Launch (permitted when SYSTEM_ALERT_WINDOW is granted or keyguard locked)
        try {
            startActivity(wakeIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct startActivity deferred to FullScreenIntent: ${e.message}")
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            grabReady.sessionId.hashCode(),
            wakeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_WAKE_ID)
            .setSmallIcon(R.drawable.ic_airgrab_tile)
            .setContentTitle("AirGrab: Incoming Drop from ${grabReady.senderName}")
            .setContentText("Open palm to drop & catch: ${grabReady.fileName}")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(WAKE_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceScope.cancel()
        // The transport is shared with the activity; only close the room when ambient mode is
        // explicitly turned off and the UI is not using it.
        if (!AirGrabApp.from(this).isAppForeground) {
            nearbyTransport?.stopRoom()
        }
        Log.d(TAG, "AirGrabDiscoveryService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
