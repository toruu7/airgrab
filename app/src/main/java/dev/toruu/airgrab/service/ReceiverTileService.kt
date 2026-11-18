package dev.toruu.airgrab.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.toruu.airgrab.MainActivity
import dev.toruu.airgrab.ui.components.PermissionHelper

class ReceiverTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val running = AirGrabDiscoveryService.isServiceRunning
        qsTile?.apply {
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (running) "AirGrab: Listening" else "AirGrab: Off"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val isRunning = AirGrabDiscoveryService.isServiceRunning
        if (isRunning) {
            AirGrabDiscoveryService.stop(this)
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                label = "AirGrab: Off"
                updateTile()
            }
        } else {
            if (!PermissionHelper.hasAllPermissions(this)) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
                return
            }

            AirGrabDiscoveryService.start(this)
            qsTile?.apply {
                state = Tile.STATE_ACTIVE
                label = "AirGrab: Listening"
                updateTile()
            }
        }
    }
}
