package dev.toruu.airgrab

import android.app.Application
import android.content.Context
import dev.toruu.airgrab.data.nearby.NearbyConnectionsManager
import java.util.UUID

class AirGrabApp : Application() {

    /**
     * One Nearby transport for the whole process.
     *
     * Nearby caches a single client per application context, so the ViewModel, the ambient
     * discovery service and the wake activity must all use the same manager or their
     * advertising / payload callbacks silently overwrite each other.
     */
    val transport: NearbyConnectionsManager by lazy { NearbyConnectionsManager(this, deviceId) }

    /** Stable per-install identity used to resolve first-come-first-served claims. */
    val deviceId: String by lazy {
        val prefs = getSharedPreferences("airgrab_identity", MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { generated ->
            prefs.edit().putString("device_id", generated).apply()
        }
    }

    /**
     * Session id currently owned by the in-app receiver screen (if any). The wake activity
     * checks this so both surfaces never consume the same payload.
     */
    @Volatile
    var activeReceiverSessionId: String? = null

    /**
     * True while the main UI is visible. The ambient service uses this to avoid launching the
     * wake activity on top of the screen that is already handling the incoming drop.
     */
    @Volatile
    var isAppForeground: Boolean = false

    companion object {
        fun from(context: Context): AirGrabApp =
            context.applicationContext as AirGrabApp
    }
}
