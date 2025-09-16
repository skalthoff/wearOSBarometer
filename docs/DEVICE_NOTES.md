# Device Notes

Initial assumptions and notes to calibrate and tune for target devices.

## Samsung Galaxy Watch 6
- Barometer present; expect responsive pressure changes under light press.
- Water lock: consider prompting user to enable during test to reduce acoustic venting.
- Thermal drift manageable for short tests (< 10 s).

## Google Pixel Watch 4
- Barometer present; verify effective sampling rates and event latency.
- Expect smaller ΔP on some units; soft thresholds and per-device calibration recommended.

## General
- Keep press/hold 2–3 s; release and collect 1–2 s decay window.
- Motion gating is critical; stationary surface preferred.
- Calibrate low ΔP and high τ thresholds per model.

