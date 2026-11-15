# AirGrab: Gesture-Based Cross-Device File Transfer for Android

Touchless **Grab & Release** file-sharing app built in **Kotlin + Jetpack Compose**, inspired by Huawei’s Air Gesture transfer. Runs on any modern Android device (including the base Samsung Galaxy S24) with instant, PIN-free open-room pairing.

- **Application ID:** `dev.toruu.airgrab`
- **Peer Display Name:** `android.os.Build.MODEL`
- **Multi-Receiver Policy:** First-come, first-served (`sessionId` locks to first `AcceptDrop`)
- **Folder Handling:** Auto-zipped into temporary archive with Zip Slip directory traversal protection and extracted into `Downloads/AirGrab/<folderName>/`

---

## Architecture Overview

```text
[Sender: CameraX + MediaPipe] --(Closed_Fist >= 300ms)--> [Stage Payload + Broadcast GRAB_READY]
                                                                    |
                                                      Nearby Connections (P2P_STAR)
                                                                    |
[Receiver: Radial Glow + Save] <--(Open_Palm >= 250ms)-- [Receiver: CameraX + MediaPipe]
```

1. **Sender Workflow:**
   - Share via Samsung Share Sheet (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) from Gallery, My Files, or Chrome, or pick in-app.
   - Front camera analyzes gestures with MediaPipe (`gesture_recognizer.task`).
   - Holding `Closed_Fist` continuous for $\ge 300\text{ ms}$ triggers **Grab**, blurs the UI preview, triggers haptic feedback, and broadcasts `GrabReady` (5s TTL).
   - Once the first receiver accepts, session is claimed and payload is transmitted over Wi-Fi Direct.

2. **Receiver Workflow:**
   - Launched in-app or via Quick Settings `TileService` (`ReceiverTileService`).
   - Receiving `GrabReady` elevates camera pipeline from 12 fps to 30 fps and displays the incoming item banner.
   - Holding `Open_Palm` continuous for $\ge 250\text{ ms}$ triggers **Drop**, activating fullscreen `RadialGlowOverlay` and streaming the file.
   - Photos/videos are saved to `Pictures/AirGrab` via `MediaStore` (instantly appearing in Samsung Gallery). Documents go to `Downloads/AirGrab`. Folders are unzipped safely. Links/text are copied to `ClipboardManager`.

---

## Project Structure

```text
app/src/main/
├── assets/
│   └── gesture_recognizer.task
├── AndroidManifest.xml
└── java/dev/toruu/airgrab/
    ├── AirGrabApp.kt
    ├── MainActivity.kt                      # Handles launcher + ACTION_SEND / ACTION_SEND_MULTIPLE
    ├── service/
    │   └── ReceiverTileService.kt           # Quick Settings tile to launch Receiver Mode
    ├── data/
    │   ├── vision/
    │   │   ├── GestureDetector.kt           # Interface
    │   │   ├── MediaPipeGestureDetector.kt  # CameraX ImageAnalysis + GestureRecognizer
    │   │   └── FrameRateController.kt       # Adaptive 12fps idle -> 30fps active throttling
    │   ├── nearby/
    │   │   ├── NearbyTransport.kt           # Interface
    │   │   ├── NearbyConnectionsManager.kt  # P2P_STAR advertising/discovery + auto-accept
    │   │   └── ControlProtocol.kt           # @Serializable control messages (GRAB_READY, etc.)
    │   └── payload/
    │       ├── PayloadModels.kt             # TransferPayload sealed hierarchy
    │       ├── PayloadResolver.kt           # Intent/SAF/Folder->Zip/Clipboard resolution
    │       └── PayloadSaver.kt              # MediaStore, Downloads, auto-unzip & ClipboardManager
    ├── domain/
    │   ├── GrabDropStateMachine.kt          # First-come first-served lock + timeouts
    │   └── GestureHysteresis.kt             # 300ms grab / 250ms drop debounce logic
    └── ui/
        ├── AirGrabViewModel.kt
        ├── screens/
        │   ├── HomeScreen.kt                # Mode switcher (Send / Receive) + room status
        │   ├── SenderScreen.kt              # Staged preview + blur-on-grab + camera PIP
        │   └── ReceiverScreen.kt            # Waiting pulse + radial glow drop animation
        └── components/
            ├── RadialGlowOverlay.kt         # Compose Canvas shader/gradient drop animation
            └── PermissionGate.kt            # Runtime permission requester (Android 13–16)
```

---

## Build Requirements

- **Android SDK:** Compile SDK 35, Min SDK 26
- **JDK:** Java 17
- **Gradle:** 8.10+
- **Model Asset:** `app/src/main/assets/gesture_recognizer.task` (Pre-packaged MediaPipe float16 model)
