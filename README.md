# MyCON Recorder

Android field-capture app for the MyCON reconstruction/thesis pipeline.

This repository records synchronized camera/ARCore trajectory, IMU, GNSS assistance, camera intrinsics, and surveyed MYCON QR control markers so pose recovery becomes a fallback rather than the normal reconstruction path.

## Capture package

Each field session exports a versioned package containing:

- `arcore_recording.mp4`
- `pose.csv`
- `imu.csv`
- `qr_events.jsonl`
- `session.json`

MYCON QR markers use a versioned `mycon://anchor/v1` payload and include project/anchor IDs, CRS, surveyed XYZ, installation orientation, and physical QR size.

## Build

Use **Actions → Build Android APK → Run workflow** or open the project in Android Studio.

> Test on an ARCore-supported physical Android device. GNSS is auxiliary; surveyed QR controls are the bridge from the ARCore-local trajectory into the project coordinate system.

See `docs/QR_STANDARD.md` and `docs/NOTEBOOK_INTEGRATION.md` for the data contract.
