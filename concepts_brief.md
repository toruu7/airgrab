# AirGrab Spatial Aura UI/UX Architecture & Technical Implementation Brief

* **Authors:** EGM Architecture Swarm & muhtadeetaron
* **Status:** Final Synthesis & Implementation Deliverable
* **Design Standards:** Apple Fluid Interfaces (`/apple-design`), Emil Kowalski Design Engineering (`/emil-design-eng`), Compose Graphics Performance (`/impeccable`, `/transitions-dev`)

---

## 1. Radical Simplification & Design Philosophy

### The Zero-Diagnostic Principle
1. **Elimination of Diagnostic Slop:** No confidence percentage badges (`85%`), no raw gesture state readouts (`"FIST (grab)"`), and no imperative instruction strings (`"Hold for 300ms"`).
2. **Spatial Intuition:** The physical state of the staged item and the edge perimeter lighting (The Aura) communicate readiness naturally.
3. **Pure OLED Dark Canvas:** The canvas stays void (`#050608`). UI elements only appear when active or staged.

---

## 2. Visual Architecture & Materials

### Color Tokens
* **Void Base:** `#050608`
* **Translucent Glass Layer:** `Color(0x0FFFFFFF)` (6% white fill), framed by `Color(0x1AFFFFFF)` (10% white border, `1dp`)
* **Projection Aura (Sender / Fist):** Electric Cyan (`#00F0FF`)
* **Reception Aura (Receiver / Palm):** Aurora Emerald (`#00FFA3`)
* **Text Primary:** `#F5F5F7`
* **Text Secondary:** `#8E8E93`

### Spatial Aura Bezel Rendering (Zero-Recomposition Engine)
Rather than full-screen radial blurs or expensive `RenderEffect.createBlurEffect()` (which breaks on `< Android 12` and lags on Mali GPUs), the Aura is drawn as a bezel vignette using 4 edge gradient strips via `drawWithContent`:
* **Edge Inset:** Outer 48dp perimeter fade-in.
* **Draw-Phase Modulated:** Brush alpha and intensity bind directly to `gestureConfidence.value` inside the draw pass, skipping recomposition entirely.

---

## 3. Interactive Physics & Fluid Interruption

### The Grab Compression Curve
* **Resting Staged File:** Centered high-gloss glass card (`scale = 1.0f`).
* **Fist Detected:** Card smoothly compresses into a pill capsule (`scale = 0.82f`, `dampingRatio = 0.82f`, `stiffness = Spring.StiffnessMediumLow`).
* **Instant Interruption:** If fist is opened before transfer is initiated, the capsule rebounds instantly with natural momentum back to `scale = 1.0f`. No snapping or layout jumps.
* **Haptic Signature:** `HapticFeedbackConstants.GESTURE_START` on initial fist compression; `HapticFeedbackConstants.CONFIRM` when the peer accepts the drop.

### The Catch Expansion Curve
* **Incoming Discovery:** Receiver wakes into void canvas with a subtle emerald ripple around front camera cut-out.
* **Palm Detected:** Emerald Aura brightens. Capsule expands with `Spring.DampingRatioMediumBouncy` into full media item.
* **Completion:** Smooth crossfade to media actions (`"Open in Gallery"` / `"Done"`).

---

## 4. Codebase Architecture & Technical Deliverables

### Deliverable 1: `AirGrabSurface.kt` (Unified Surface Composable)
* Replaces `HomeScreen.kt`, `SenderScreen.kt`, and `ReceiverScreen.kt`.
* Single root surface hosting:
  * Top camera status dot (10dp dot indicating BLE mesh status).
  * Center: Physics-bound staged card or incoming catch card.
  * Floating Bottom Pill: Suspended glass action pill (`"Stage File"` / `"Paste Link"`), disappearing automatically when a payload is active.

### Deliverable 2: `SpatialAuraModifier.kt` (`drawWithContent`)
* High-performance edge vignette rendered completely in the draw phase.

### Deliverable 3: Shared Pipeline & Deduplication
1. **Deprecate Legacy Screens:** Cleanly remove old segmented screens.
2. **Refactor `MainActivity.kt`:** Serve `AirGrabSurface` directly.
3. **CameraX Safety:** Ensure `ProcessCameraProvider.unbindAll()` is called cleanly in `onPause()` to guarantee immediate front camera release for lockscreen wakes.
