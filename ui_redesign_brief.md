# AirGrab UI/UX Architectural Redesign Brief

* **Project:** AirGrab (`dev.toruu.airgrab`)
* **Lenses Applied:** `/apple-design`, `/emil-design-eng`, `/impeccable`, `/design-taste-frontend`, `/egm-brainstorm`
* **Status:** Draft / Conceptual RFC

---

## 1. Critique of Current Frontend: The "AI-Generated Slop" Audit

The existing AirGrab interface exhibits the classic failure modes of default LLM generation:

| Current Flaw | Mechanism of Failure | Design Violation |
| :--- | :--- | :--- |
| **Bifurcated Screen Architecture** | Forcing the user to manually select "Sender Mode" vs "Receiver Mode" via cards on a Home screen. | Violates direct manipulation. A touchless transfer tool should be a single continuous spatial canvas where bringing a file makes you a sender, and approaching another phone makes you a receiver. |
| **Instruction Manual Copy** | Giant text blocks: *"Show Open Palm towards phone for 250ms to drop & accept payload"*, *"Hold closed fist towards phone for 300ms to grab"*. | Treats the user like an operator running a terminal diagnostics suite. Interfaces must communicate state through physics, layout transformation, and haptics, not instructional paragraphs. |
| **Material 3 Tinted Card Soup** | Rounded cards inside rounded cards inside rounded scaffold, tinted with generic purple/lavender (`#D0BCFF` / `#4F378B`). | Synthetic, low-contrast template aesthetic with zero visual hierarchy or brand identity. |
| **Radar Pulse & Stock Spinners** | Repetitive infinite pulse animations (`Icons.Default.Sensors`) and default `CircularProgressIndicator` sitting in empty screen real estate. | Decorative filler motion with zero physics or velocity awareness. |
| **Debug Badges Plastered on UI** | Percentage confidence chips (`50%`, `85%`) and "Waiting for Hand..." status pills. | Exposes internal detector plumbing rather than delivering an integrated product experience. |

---

## 2. Core Design Engineering Principles

Synthesized from Apple’s *Designing Fluid Interfaces* (WWDC) and Emil Kowalski’s design-engineering standards:

1. **Unified Ambient Canvas (Zero Mode Switching):**
   - The app has no "Sender" or "Receiver" navigation tabs.
   - If a file is shared into the app or staged from disk, it rests on the canvas ready to be grabbed.
   - If the canvas is empty, the device is automatically in ambient receive posture, ready to catch.
2. **Physical Spring Behavior (Interruptible & Velocity-Aware):**
   - Replace linear transitions with critically damped springs (`damping = 1.0`, `response = 0.35s`).
   - Grab motion must feel physical: closed fist causes the staged payload to physically compress into a high-density, glowing capsule under spring tension. Opening the hand cancels and snaps back to rest.
3. **Hardware-Anchored Spatial Lighting (Aura over Icons):**
   - No camera viewfinders and no status cards.
   - When front camera detects hand presence in the spatial frustum, the screen perimeter responds with a subtle chromatic refraction vignette (the "Aura"). Tightening into a fist pulls the vignette toward the center.
4. **Camera Punch-Hole Integration:**
   - Visual indicators should anchor physically around the front camera sensor (where the user's hand is aimed), anchoring interaction to physical reality rather than the screen center.
5. **Restrained, High-Contrast Palette:**
   - Deep OLED Void (`#070709`) base.
   - Dynamic frosted glass layers with subtle specular edge highlights (`rgba(255, 255, 255, 0.08)` border, `1dp`).
   - Single luminous accent (Electrum Cyan `#00F2FE` or Pure Mercury `#F5F5F7`), eliminating muddy purple tints.

---

## 3. Three Concrete Design Directions

### Direction A: "Spatial Aura" (Apple NameDrop / VisionOS Metaphor)
* **Visual Identity:** Pure black OLED canvas with liquid glass sheets, subtle specular rim lighting, and an organic peripheral glow that reacts dynamically to hand distance and gesture tension.
* **Key Interaction Flow:**
  - **Staged File:** Appears as an elevated glass card with an authentic thumbnail, crisp typographic title, and file size in subtle muted typography.
  - **Hand Approaching:** The top perimeter of the screen begins breathing with a soft, diffused cyan/emerald aura, indicating the front camera sees the hand.
  - **The Grab (Closed Fist):** The file card spring-compresses down into an intense, compact energy capsule (`scale: 0.88`, `damping: 0.85`), accompanied by a heavy haptic tick (`CONFIRM`). The status text reads only: `"Holding • Ready to Project"`.
  - **The Drop (Receiver):** Display wakes with a concentric liquid ripple. When the receiver opens their palm, the incoming capsule expands fluidly from the top camera cut-out down into the viewport and unpacks with a soft tactile settle.
* **Strengths:** Maximum emotional polish, feels modern and hardware-integrated, zero technical clutter.

---

### Direction B: "Tactile Monolith" (Teenage Engineering / Dieter Rams Minimalist)
* **Visual Identity:** Industrial, stark, high-precision slate (`#111215`). Heavy mono/grotesque typography, hairline dividers (`0.5dp`), razor-sharp geometric radii (`8dp`–`12dp`), and mechanical precision meters.
* **Key Interaction Flow:**
  - **Camera Detection:** Instead of vague cards, an ultra-crisp physical aperture ring or 5-dot LED ladder around the camera indicates tracking status.
  - **The Grab:** High-contrast inverse transition (background flashes dark slate to crisp off-white for 100ms), file details collapse into a compact mechanical badge with a live countdown bar (`4.0s`).
  - **Data Transfer:** Minimalist high-density progress telemetry: live transfer rate (`48.2 MB/s`), elapsed time, and total bytes rendered with monospace clarity.
* **Strengths:** Eliminates all "AI bubbly card" tropes, deeply unique, conveys engineering robustness and raw speed.

---

### Direction C: "Dynamic Morphing Island" (Fluid Micro-Capsule Architecture)
* **Visual Identity:** The entire app lifecycle anchors around a single morphing morphic surface at the top of the display that transforms across states.
* **Key Interaction Flow:**
  - **Idle:** Compact pill (`height: 44dp`, `width: 140dp`) resting quietly below the status bar: `"AirGrab • Ready"`.
  - **Payload Picked:** Pill smoothly expands downward with spring physics into a media card showing the image preview and metadata.
  - **Grabbed:** The card contracts into a pulsating high-priority beam with a radial ripple projecting outward.
  - **Receiving:** On the other phone, the pill expands dynamically to reveal incoming sender name and file details, with a progress arc rendered directly inside the pill border.
* **Strengths:** Compact, unobtrusive, natural fit for both full-screen app and overlay/lockscreen wake modes.

---

## 4. Immediate Architectural Decisions for the Implementation

1. **Combine Screens into a Single Reactive Component:**
   - Merge `HomeScreen`, `SenderScreen`, and `ReceiverScreen` into a unified `AirGrabSurface.kt` driven strictly by machine state.
2. **Eliminate Instructional Clutter:**
   - Remove every explanatory sentence from the main UI. Rely entirely on haptics, spring animation, and minimal state labels (`"Holding"`, `"Incoming"`, `"Delivered"`).
3. **Motion Foundations:**
   - Introduce standard spring motion tokens:
     - `SnappySpring`: `spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)`
     - `PhysicalSettle`: `spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessLow)`
     - `PressFeedback`: `spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessHigh)`
