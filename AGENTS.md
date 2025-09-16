# AGENTS: Wear OS Gasket Check

This document organizes tasks, milestones, and ownership for building a Wear OS app that estimates watch gasket integrity using a barometer-based press/hold test. It is not a certified water resistance test and must be presented as guidance only.

## Goals

- Deliver an on-device guided test that computes a seal score (0–100) from barometer data during a short press-and-hold and release, with motion gating for robustness.
- Support Samsung Galaxy Watch 6 and Google Pixel Watch 4 first. Degrade gracefully if sensors are missing or noisy.
- Store local history, show trends, and provide clear safety guidance.

## Scope (MVP)

- Sensor capture: barometer + accelerometer (optional gyro) using Kotlin coroutines/Flow.
- Processing: filtering, press/release detection, features (ΔPmax, decay τ), simple scoring + confidence.
- UI: Wear Compose wizard (Baseline → Press → Release → Result), haptics, retry prompts.
- Persistence: local DataStore for last results and per-device calibration.
- Capability checks and disclaimers. No network, no accounts.

## Non‑Goals (MVP)

- Waterproof certification or guarantees; in-water testing; cloud sync.
- Complications, notifications, or background monitoring (post‑MVP).

---

## Project Structure (proposed)

- `app`: Wear OS app entry, navigation, permissions, theming.
- `core:sensors`: Sensor adapters and Flows for pressure and motion.
- `core:signal`: Filtering, feature extraction, scoring, synthetic generators, unit tests.
- `feature:testflow`: State machine + Compose screens for the guided test.
- `data:store`: DataStore schemas for results, calibration, and app prefs.

Each module should be small, testable, and free of device‑specific UI logic.

---

## Milestones & Acceptance

1) Environment & Skeleton
- Android Studio + SDKs installed; Wear AVD runs; watches connect over ADB.
- Multi‑module project builds; a hello screen shows on emulator and device.

2) Sensors & Gating
- Streams for pressure (25–50 Hz) and accel (50–100 Hz) with backpressure.
- Motion gating metric (RMS accel) computed; ring buffer in place.
- Unit tests pass on synthetic noisy streams.

3) Signal Processing
- Median + low/high‑pass filters; press/release detection implemented.
- Feature extraction for ΔPmax, dP/dt, decay τ with R² goodness.
- Scoring maps to 0–100 with confidence; tests cover clean/leaky/noisy traces.

4) Guided UI Flow
- Baseline, Press, Release, and Result screens wired to state machine.
- Haptics and clear prompts; error/retry paths for motion/noise.
- Accessibility: large text support; haptics‑only cues validated.

5) Persistence & History
- Results stored with timestamp, ΔP, τ, confidence; history list and trend.
- Per‑device calibration stored and applied.

6) Device Integration
- Capability checks; device model detection; water lock guidance for Samsung.
- Threshold defaults reasonable on Galaxy Watch 6 and Pixel Watch 4.

7) QA & Polishing
- On‑device sanity across motion/press variants; performance acceptable.
- Copy reviewed; disclaimers prominent; app resilient to lifecycle changes.

---

## Task Breakdown

### 0. Environment & Project Setup
- Install Android Studio, JDK 17+, Wear OS SDKs, Kotlin.
- Create Wear OS project (Compose, minSdk 30+, target SDK current).
- Add modules: `core:sensors`, `core:signal`, `feature:testflow`, `data:store`.
- Configure Gradle: Kotlin DSL, strict warnings, release/debug build types.
- Set up Wear AVD (Round/Chin, API 34+) and enable ADB over Wi‑Fi on target watches.
- Acceptance: `./gradlew assembleDebug` succeeds; app launches on AVD.

### 1. Capability & Safety
- Check `FEATURE_SENSOR_BAROMETER`; gracefully exit if missing.
- Keep screen awake during test; disable touch except needed actions.
- Disclaimers: light press only; dry environment; not a certified test.
- Samsung water lock: detect and guide user to enable/disable as needed.
- Acceptance: Capability screen renders with correct paths and copy.

### 2. Sensor Layer (`core:sensors`)
- Implement `PressureStream` and `MotionStream` using `SensorManager` + coroutines.
- Subscribe at FASTEST; downsample to target rates with timestamp alignment.
- Provide motion metrics (RMS accel) and basic noise indicators.
- Ring buffer (e.g., 5–10 s) for baseline and event windows.
- Unit tests using synthetic producers; benchmark allocations.

### 3. Signal Processing (`core:signal`)
- Filters: 3–5 sample median; 1–2 s low/high‑pass pair to remove drift.
- Event detection: baseline stabilization; press start/hold; release detection with dP/dt thresholds and motion gating.
- Feature extraction: ΔPmax, dP/dt peak, decay τ via exponential fit; compute R².
- Scoring: combine features → 0–100; confidence from motion/noise/R²; map to 3 labels: Likely OK, Inconclusive, Likely Compromised.
- Synthetic trace generator for clean/leaky/noisy cases; unit tests.

### 4. Test Flow UI (`feature:testflow`)
- State machine: Idle → Baseline → Press → Release → Analyze → Result → Done.
- Compose screens with large cues, progress ring, haptics at phase transitions.
- Real‑time indicators: stability (baseline), press strength, motion alerts.
- Retry flow when motion too high or press too short; helpful tips.
- Previews and localized strings; dark/light; round screen safe insets.

### 5. Persistence (`data:store`)
- Schemas for `Result { time, deltaP, tau, score, confidence }` and `Calibration { thresholds }` using DataStore + Kotlinx serialization.
- Repository for saving/fetching last N results; trend summary (min/avg/max).
- Export/clear data options (local only).

### 6. Device‑Specific Integration
- Model detection via `Build.MANUFACTURER/MODEL` and feature flags.
- Galaxy Watch 6: note expected ΔP ranges; ensure sensor units/timebase are correct; water lock guidance.
- Pixel Watch 4: verify sensor rates and latency; adjust defaults if needed.
- Threshold table (override map) by model; safe fallbacks when unknown.

### 7. Calibration
- Optional onboarding calibration (2–3 valid traces) to personalize thresholds.
- UI to re‑run calibration and reset values to defaults.
- Store per‑device calibration; annotate results with calibration version.

### 8. QA & Validation
- Unit tests (processing); instrumentation smoke tests for sensor hookup and flow transitions.
- On‑device manual matrix: table‑top vs wrist; light/firm press; brief/long hold; with/without water lock (Samsung).
- Performance profiling: CPU < 15% during test; memory stable; no jank.
- Lifecycle: handle app backgrounding mid‑test; recover or cancel safely.

### 9. Release Readiness (optional)
- App ID, versioning, signing configs; about screen with disclaimers and links.
- Play Console artifacts; internal testing; store listing with clear safety copy.

---

## Deliverables per Milestone
- M1: Project skeleton in repo; README with run instructions.
- M2: `core:sensors` with tests and sample viewer screen.
- M3: `core:signal` with unit tests and reference plots (text summary acceptable on‑device).
- M4: End‑to‑end guided test producing a score on device.
- M5: History screen with trend and detail view.
- M6: Device model overrides + water lock guidance.

---

## Technical Decisions
- Language/tooling: Kotlin, Coroutines/Flow, Compose for Wear OS, DataStore, Kotlinx serialization, JUnit.
- Sampling: pressure FASTEST → downsample 25–50 Hz; accel 50–100 Hz.
- Decay fitting: exponential decay P(t) ≈ P∞ + A·e^(−t/τ) via linear fit on ln(P−P∞); compute τ and R² over 1–2 s post‑release window.
- Scoring defaults (to be calibrated): low ΔP threshold ≈ 0.15 hPa, high τ ≈ 0.8 s; map to score with soft ramps.
- No network permissions; sensors are normal‑level, no runtime permission prompts.

---

## Risks & Mitigations
- Small ΔP on some models → enhance SNR with filtering, multi‑press averaging (post‑MVP), per‑device calibration.
- User variability → strong guidance, haptics, clear retry logic.
- Motion artifacts → strict gating; cancel and re‑cue when motion > threshold.
- False reassurance → prominent disclaimers; “Inconclusive” when confidence low.

---

## Quickstart (for new contributors)
1. Install Android Studio Hedgehog+, JDK 17, Wear OS SDKs.
2. Create a Wear AVD (Round, API 34+). Enable ADB over Wi‑Fi on your watch (Developer options → Wireless debugging).
3. Build: `./gradlew assembleDebug`. Run on AVD or paired watch.
4. Modules to explore first: `core:sensors` (streams), `core:signal` (algorithms), `feature:testflow` (UI flow).

---

## Open Questions
- Do we require gyro for better motion gating, or is accel sufficient for MVP?
- Should we prompt to enable Samsung water lock automatically or only provide guidance?
- What default thresholds feel right on Galaxy Watch 6 vs Pixel Watch 4? (Calibrate in M6.)

