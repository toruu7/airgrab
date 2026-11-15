package dev.toruu.airgrab.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.toruu.airgrab.MainActivity

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
            AirGrabDiscoveryService.start(this)
            qsTile?.apply {
                state = Tile.STATE_ACTIVE
                label = "AirGrab: Listening"
                updateTile()
            }
        }
    }
}
