# MyCON Recorder for iOS

Native iPhone capture companion for the existing MyCON R4 / COLMAP pipeline.

## Architecture

- SwiftUI UI
- ARKit metric 6DoF camera tracking and per-frame intrinsics
- AVAssetWriter video recording
- CoreMotion IMU
- CoreLocation auxiliary GNSS
- Vision QR detection
- native planar square-marker pose solve
- RealityKit stable model preview after multi-frame consensus
- ZIPFoundation packaging

## Compatibility

The iOS app intentionally writes the same external capture contract used by Android:

- `MYCON_CAPTURE_SESSION / format_version=1`
- `arcore_recording.mp4` (legacy filename retained for R4 compatibility)
- `pose.csv`
- `imu.csv`
- `qr_events.jsonl`
- `session.json`
- `r4_camera.json`
- `r4_controls.json`
- `r4_compatibility.json`
- `integrity_sha256.json`

QR payload remains `mycon://anchor/v1`.

ARKit replaces ARCore only in the acquisition layer. Phone poses remain priors/validation evidence, not final geometry.

## Local generation

Install XcodeGen, then:

```bash
cd ios
xcodegen generate
open MyCONRecorderIOS.xcodeproj
```

The unsigned simulator build is verified by GitHub Actions. A separate signed-device workflow can produce an IPA after Apple signing credentials are added as GitHub Secrets.
