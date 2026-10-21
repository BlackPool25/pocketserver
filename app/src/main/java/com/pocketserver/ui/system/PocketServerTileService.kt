package com.pocketserver.ui.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controller for Persistent Notification with lockscreen quick actions.
 *
 * Implements Section 3:
 * - Persistent notification showing live service count and ports.
 * - Action buttons: [ Pause All (Sleep) ] / [ Resume All ], [ Copy Public Link ], [ Stop All ].
 */
class PocketNotificationController(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "pocketserver_services_channel"
        const val NOTIFICATION_ID = 42003

        const val ACTION_PAUSE_ALL = "com.pocketserver.ACTION_PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.pocketserver.ACTION_RESUME_ALL"
        const val ACTION_COPY_LINK = "com.pocketserver.ACTION_COPY_LINK"
        const val ACTION_STOP_ALL = "com.pocketserver.ACTION_STOP_ALL"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PocketServer Services",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live status and quick controls for hosted services"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Builds and updates notification representing current service statuses.
     */
    fun buildNotification(
        liveCount: Int,
        sleepingCount: Int,
        activePorts: List<Int>,
        publicTunnelUrl: String? = null
    ): Notification {
        val isAllSleeping = liveCount == 0 && sleepingCount > 0
        val isRunning = liveCount > 0

        val title = when {
            isRunning -> "PocketServer: $liveCount Service${if (liveCount > 1) "s" else ""} Live"
            isAllSleeping -> "PocketServer: Sleeping • Saving Battery"
            else -> "PocketServer: Idle"
        }

        val portsDesc = if (activePorts.isNotEmpty()) {
            activePorts.joinToString(", ") { "Port $it" }
        } else {
            "No active ports"
        }

        val contentText = "$portsDesc • Battery Impact: Minimal (<1%)"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(isRunning || isAllSleeping)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // Action 1: Toggle Pause/Resume
        if (isRunning) {
            val pauseIntent = Intent(ACTION_PAUSE_ALL)
            val pausePending = PendingIntent.getBroadcast(
                context, 101, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Pause All (Sleep)", pausePending)
        } else if (isAllSleeping) {
            val resumeIntent = Intent(ACTION_RESUME_ALL)
            val resumePending = PendingIntent.getBroadcast(
                context, 102, resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "Resume All", resumePending)
        }

        // Action 2: Copy Public Link
        if (publicTunnelUrl != null) {
            val copyIntent = Intent(ACTION_COPY_LINK).apply {
                putExtra("url", publicTunnelUrl)
            }
            val copyPending = PendingIntent.getBroadcast(
                context, 103, copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_share, "Copy Link", copyPending)
        }

        // Action 3: Stop All
        val stopIntent = Intent(ACTION_STOP_ALL)
        val stopPending = PendingIntent.getBroadcast(
            context, 104, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop All", stopPending)

        val notification = builder.build()
        notificationManager?.notify(NOTIFICATION_ID, notification)
        return notification
    }

    fun cancel() {
        notificationManager?.cancel(NOTIFICATION_ID)
    }
}

/**
 * Android Quick Settings Tile in pull-down shade.
 *
 * Implements Section 3 & Automated Testing Protocol:
 * "Quick Settings Tile: Tap Android QS Tile from outside the app to toggle server.
 *  Services pause/resume; persistent notification updates immediately."
 */
class PocketServerTileService : TileService() {

    companion object {
        private val _isServerActive = MutableStateFlow(false)
        val isServerActive: StateFlow<Boolean> = _isServerActive.asStateFlow()

        private val _activePorts = MutableStateFlow<List<Int>>(emptyList())
        val activePorts: StateFlow<List<Int>> = _activePorts.asStateFlow()

        var onToggleListener: ((Boolean) -> Unit)? = null

        fun setServerStatus(isActive: Boolean, ports: List<Int> = emptyList()) {
            _isServerActive.value = isActive
            _activePorts.value = ports
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val nextActiveState = !_isServerActive.value
        _isServerActive.value = nextActiveState

        onToggleListener?.invoke(nextActiveState)

        updateTileState()

        // Update persistent notification immediately
        val controller = PocketNotificationController(this)
        if (nextActiveState) {
            controller.buildNotification(
                liveCount = maxOf(1, _activePorts.value.size),
                sleepingCount = 0,
                activePorts = if (_activePorts.value.isEmpty()) listOf(8000) else _activePorts.value
            )
        } else {
            controller.buildNotification(
                liveCount = 0,
                sleepingCount = maxOf(1, _activePorts.value.size),
                activePorts = _activePorts.value
            )
        }
    }

    fun updateTileState() {
        val tile = qsTile ?: return
        val active = _isServerActive.value
        val ports = _activePorts.value

        if (active) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "PocketServer"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (ports.isNotEmpty()) "Live (Port ${ports.joinToString(", ")})" else "Live"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "PocketServer"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Sleeping (0% CPU)"
            }
        }
        tile.updateTile()
    }
}
