# MyCON Recorder

Android field-capture app for the MyCON reconstruction/thesis pipeline.

The goal is simple: record the information that reconstruction normally has to infer later.

## Recorded during capture

- ARCore MP4 dataset: `arcore_recording.mp4`
- metric local 6DoF camera trajectory + intrinsics: `pose.csv`
- accelerometer, gyroscope, rotation vector: `imu.csv`
- surveyed MYCON QR-control observations: `qr_events.jsonl`
- versioned session metadata/QA summary: `session.json`
- QR events are also written into a custom timed ARCore recording track when supported by the current frame/device.

The exported field package is `*_MYCON.zip`.

## Why the QR controls exist

ARCore relative motion is metric, but its world coordinate frame is local to each session. Phone GNSS is useful context but is not survey-grade, especially indoors.

MYCON QR controls provide the bridge to project coordinates. A marker contains:

- project + anchor ID;
- CRS;
- surveyed XYZ of the marker centre;
- mount/orientation;
- exact QR-symbol physical side length.

During capture the app stores the four detected QR corners, camera intrinsics and ARCore camera pose at that timestamp. Offline processing can therefore run square-marker PnP and robustly align the ARCore-local trajectory into the project frame.

## Field workflow

1. Open **QR پروژه**.
2. Enter project/anchor, CRS, surveyed XYZ, mount, azimuth and physical QR-symbol size.
3. Generate the PDF.
4. Print at **100% / Actual Size**.
5. Measure the PDF's 100 mm verification bar.
6. Install the marker flat at the surveyed control.
7. Start capture only when ARCore Tracking is `TRACKING`.
8. Show a marker at the beginning, after difficult/feature-poor transitions, and near the end.
9. Stop capture and export the ZIP.

The app rejects control markers from a different project while recording.

## Build APK

Open the **Actions** tab and run **Build Android APK**, or push to `main`. The workflow builds a debug APK and publishes it as a workflow artifact.

No Android Studio or administrator access is required on the field PC when using GitHub Actions.

## Compatibility

- Android min SDK: 24
- ARCore-capable physical Android device required
- Session schema: `MYCON_CAPTURE_SESSION / format_version=1`
- Marker schema: `mycon://anchor/v1`

## Documentation

- [QR control standard](docs/QR_STANDARD.md)
- [Notebook integration contract](docs/NOTEBOOK_INTEGRATION.md)

## Session validation

```bash
python tools/mycon_session_check.py path/to/session_MYCON.zip
```

The recorder provides priors and control observations. COLMAP/image matching/bundle adjustment remain part of the scientific reconstruction pipeline; the app does not silently replace them.
