# AirGrab Asynchronous Wake Pipeline

* **Author:** muhtadeetaron
* **Date:** 2026-11-15

## Overview

AirGrab currently requires the receiver device to either remain active in the foreground application (`ReceiverScreen`) or have the user manually toggle a Quick Settings tile (`ReceiverTileService`) before a transfer can begin. This creates substantial friction for spontaneous, touchless sharing.

The Asynchronous Wake Pipeline introduces a low-power background discovery service that listens for Nearby BLE Grab beacons while the screen is off or the device is running other applications. When a nearby sender executes a `Closed_Fist` Grab gesture, the receiver detects the broadcast within 500 milliseconds, fires a high-priority Heads-Up Notification with a full-screen intent (`USE_FULL_SCREEN_INTENT`), and turns on the screen over the lock screen. Only upon foreground display does CameraX and MediaPipe bind and elevate to 30 fps to capture the first-come, first-served `Open_Palm` drop gesture. If no gesture is detected within a 4-second window or another peer claims the drop, the camera unbinds immediately, the activity finishes, and the receiver returns to deep sleep.

## Analysis

### 1. UX & Ergonomics (`egm-design`)
* **Screen-Off vs. Screen-On Behavior:** Unconditionally forcing a full-screen takeover while a user is actively reading or messaging causes severe disruption. The wake pipeline must bifurcate:
  * *Screen-Off / Keyguard Locked:* Trigger a full-screen activity over the lockscreen (`setShowWhenLocked(true)`, `setTurnScreenOn(true)`) with ambient radial pulse, soft haptic feedback, and sender device name.
  * *Screen-On / Interactive:* Display a non-blocking Heads-Up Notification (HUN) banner with a 1-tap "Catch" button rather than taking over the active foreground application.
* **Ergonomic Reaction Time Window:** Cold CameraX pipeline startup requires 400–600ms, and human reaction to an unexpected screen wake averages 1.5–2.0 seconds. A rigid 5-second TTL from initial Grab leaves insufficient time for palm presentation. The BLE Grab beacon TTL should be lengthened to 7 seconds, with an explicit 4-second circular countdown arc displayed on the receiver screen once the camera feed becomes active.
* **Camera Privacy & Silhouette Framing:** A raw front camera feed appearing on wake triggers privacy concerns. The UI must render a stylized silhouette or frosted glass preview with hand landmark skeletons, demonstrating that video is processed strictly on-device for geometry classification and is not stored or broadcast.

### 2. Android OS & Runtime Restrictions (`egm-codebase-details`)
* **Android 14+ Full-Screen Intent Policies:** On Android 14 (API 34) and Android 15, `USE_FULL_SCREEN_INTENT` is restricted and can be revoked by default for non-dialer/non-alarm apps. `PermissionGate.kt` must check `NotificationManager.canUseFullScreenIntent()` and redirect to `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` if ungranted.
* **Foreground Service Type:** To sustain background BLE discovery without being killed by the OEM battery manager or Doze mode, the service must declare `foregroundServiceType="connectedDevice"` under Android 14+ requirements.
* **Strict Camera Lifecycle Invariant:** Android cuts off camera HAL binder buffers when an app lacks an active, resumed window. CameraX must never be initialized or referenced from the background service; it must only bind within `ReceiverWakeActivity.onResume()` and explicitly unbind in `onPause()`.

### 3. Systems, Reliability & Battery Economics (`egm-zoom-out`)
* **Coexistence Controller Contention (BLE vs. Wi-Fi Direct):** Concurrent continuous BLE scanning and Wi-Fi Direct group negotiation on shared 2.4 GHz antennas produces packet drops and handshake timeouts. The discovery service must pause active BLE scanning immediately once a valid Grab beacon is received, dedicating the radio interface to high-throughput Wi-Fi Direct payload transfer.
* **Denial-of-Service & Thermal Throttle Protection:** Malicious or looping Grab broadcasts could wake the display and spin CameraX + MediaPipe continuously. The receiver must enforce rate limiting (maximum 3 wakes per 60 seconds from unauthenticated peers) and an aggressive 4-second timeout to return to low-power deep sleep.

### 4. Protocol & State Machine Extensions (`egm-structural`)
* **Control Protocol Separation:** Introduce an explicit lightweight `GrabBeacon` broadcast message for initial wake signaling, reserving the full `GrabReady` and `AcceptDrop` protocol exchange for the active Wi-Fi Direct session.
* **Receiver State Machine:** Add `ReceiverState.Waking(beacon)` between `Listening` and `Armed`. If another peer claims the drop during the wake window, the state transitions directly to `Listening` with a brief toast notification rather than crashing or hanging.

## Goals vs. Non-Goals

* **In-Scope Goals:**
  * Implement `AirGrabDiscoveryService` (`connectedDevice` foreground service) for low-duty BLE discovery.
  * Implement `ReceiverWakeActivity` using `setShowWhenLocked(true)`, `setTurnScreenOn(true)`, and full-screen notification triggers.
  * Dynamically bind and unbind CameraX + MediaPipe on demand, ensuring 0% CPU/GPU camera usage during idle background states.
  * Add radio arbitration: pause BLE discovery during active Wi-Fi Direct file transmission.
  * Add anti-DoS wake throttling (maximum 3 screen wakes per 60 seconds).
  * Verify end-to-end performance and latency across two physical Android test devices.

* **Out-of-Scope Non-Goals:**
  * Multi-party concurrent file broadcasting (remains single-receiver first-come, first-served).
  * Cloud-assisted push notifications (remains strictly local offline BLE/Wi-Fi Direct).
  * Silent background camera analysis while the screen is off (prohibited by Android OS security architecture).

## Alternatives Weighed

* **Continuous Background Camera Analysis:** Rejected due to Android OS platform boundaries. Android 14+ revokes camera HAL access to background services and kills apps attempting to read camera buffers with the screen turned off.
* **Cloud Push Notification (FCM) Wake:** Rejected to preserve AirGrab's core offline, peer-to-peer operational guarantee.
* **Always-On Hotspot / Wi-Fi Direct Group:** Rejected because maintaining an active Wi-Fi Direct group owner continuously drains 15–25% battery per hour, whereas BLE discovery consumes under 1.5% battery per hour.
* **Simultaneous Multi-Receiver Mesh Streaming:** Rejected by author to maintain maximum throughput and prevent Wi-Fi Direct radio packet congestion.

## Key Decisions & Staging for Next Steps

1. **Wake Split:** Screen-off triggers full-screen keyguard activity; screen-on triggers a non-intrusive Heads-Up banner.
2. **Dynamic TTL:** 7-second BLE broadcast TTL combined with a 4-second active camera countdown on the receiver.
3. **Radio Switching:** Suspend BLE scanning during active Wi-Fi Direct payload streaming to eliminate antenna contention.
4. **Physical Verification Milestone:** Build and deploy debug APKs to both physical test devices to benchmark wake-to-drop latency and battery idle draw via `adb shell dumpsys battery`.
