# Wear OS Gasket Check (MVP)

A Wear OS app that guides the user through a light press-and-hold test and estimates gasket integrity from barometer data. Not a certified water resistance test.

- Target devices: Samsung Galaxy Watch 6, Google Pixel Watch 4
- Modules: `app`, `core:sensors`, `core:signal`, `feature:testflow`, `data:store`
- Planning: see `AGENTS.md`

## Build & Run

Prereqs:
- Android Studio Hedgehog+ and JDK 17+
- Wear OS SDKs and an emulator (API 34+)

Steps:
- Build: `./gradlew assembleDebug`
- Run on emulator or a paired watch (enable Wireless debugging in Developer Options)

## Module Overview
- `app`: Application entry, theme, ties feature modules together.
- `core:sensors`: Interfaces for pressure and motion streams (SensorManager impl TBD).
- `core:signal`: Signal processing (filters, event detection, features, scoring TBD).
- `feature:testflow`: Compose UI for the guided test (stubbed entry screen in place).
- `data:store`: Models for results + calibration (DataStore repos TBD).

## Status
- Multi-module skeleton in place; app launches to a stub test screen.
- Next: implement SensorManager-backed streams and basic capability checks.

## Safety
- Dry environment only. Light pressure. This is guidance, not a guarantee of water resistance.

## Contributing
- Start with `AGENTS.md` milestones and tasks.
- Keep modules small and testable; prefer coroutines + Flow.

