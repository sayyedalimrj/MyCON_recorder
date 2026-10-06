# MyCON Recorder Suite 1.0

MyCON is now a cross-platform field-capture suite for the MyCON R4 reconstruction/thesis pipeline:

- **Android / ARCore** — primary scientific capture on Android.
- **iOS / ARKit** — primary scientific capture on iPhone/iPad.
- **Web / PWA** — project controls, QA, 3D/AR viewing, measurements and a clearly-labeled fallback capture mode.

The scientific contract remains stable: native Android and iOS emit `MYCON_CAPTURE_SESSION / format_version=1` and use the same `mycon://anchor/v1` survey-control schema.

## Scientific capture contract

A finished native session is self-contained:

- `arcore_recording.mp4` — legacy filename intentionally retained on both platforms for existing R4 compatibility
- `pose.csv` — metric session-local camera centres, rotations and intrinsics
- `imu.csv` — accelerometer / gyroscope / rotation-vector stream
- `qr_events.jsonl` — surveyed controls, QR corners, solved marker pose and reprojection QA
- `session.json` — capture/camera/quality manifest
- `depth/...` and `depth_summary.json` when supported/enabled
- `r4_camera.json`
- `r4_controls.json`
- `r4_compatibility.json`
- `integrity_sha256.json`
- `R4_IMPORT_README.txt`
- `tools/mycon_r4_bridge.py`

Large video/depth binaries use deferred hashing; scientific sidecars are SHA-256 hashed immediately.

## Android 1.0

- ARCore Recording & Playback MP4 dataset
- scientific camera selection and real camera metadata
- metric ARCore pose + intrinsics
- IMU + GNSS
- live blur / lighting / tracking warnings
- MYCON QR controls with multi-frame stable model anchoring
- **Raw Depth + confidence recording** on Depth-capable devices
- depth timestamps preserved so repeated reprojected depth can be rejected
- local ZIP export and R4 bridge sidecars

Default capture profile remains **Scientific HQ • 30 FPS** with a Motion • 60 FPS alternative where supported.

## iOS 1.0

- SwiftUI + ARKit + RealityKit
- ARKit world tracking and per-frame intrinsics
- AVAssetWriter camera recording
- CoreMotion IMU + CoreLocation GNSS
- Vision QR detection
- native planar marker solve and stable multi-frame 3D anchoring
- live quality score using tracking, feature-point density, motion and lighting
- path-length / mapping-status / depth HUD
- **LiDAR Scene Depth** recording with confidence
- **ARKit scene reconstruction** when supported
- **RoomPlan** room scan mode with USDZ export
- exact R4 sidecars and bundled `mycon_r4_bridge.py`

## Web / PWA 1.0

- installable PWA for iPhone, Android and desktop
- MYCON QR/control builder with Android/iOS checksum parity
- exact-size print sheet and 100 mm verification bar
- local `*_MYCON.zip` inspection — no server upload required
- QA score for capture completeness, tracking, FPS, controls and depth
- QA history + JSON report export
- GLB/GLTF/USDZ local viewer
- WebXR / Scene Viewer / Quick Look AR handoff through `<model-viewer>`
- automatic model bounding-box dimensions
- point-to-point 3D measurement on model surfaces
- browser fallback video + IMU + GNSS capture

### Web capture safety boundary

Browser fallback sessions are deliberately written as:

`MYCON_WEB_FALLBACK_CAPTURE`

with:

- `scientific_6dof = false`
- `metric_pose_available = false`
- `not_for_r4_pose_validation = true`

They are documentation/fallback captures and must not be used as ARKit/ARCore Stage-4 pose evidence.

## MyCON R4 integration

Recorder data supplements the existing R4 pipeline; it does **not** silently replace Stage 1–7, COLMAP, bundle adjustment or independent pose validation.

After Stage 2:

```bash
python tools/mycon_r4_bridge.py SESSION_MYCON.zip \
  --stage2-report STAGE2_REPORT_OR_KEYFRAME_DIR \
  --output bridge
```

Use:

`POSE_VALIDATOR_JSON_INPUT = '.../bridge/pose_validator.json'`

After a COLMAP text model exists:

```bash
python tools/mycon_r4_bridge.py SESSION_MYCON.zip \
  --stage2-report STAGE2_REPORT_OR_KEYFRAME_DIR \
  --colmap-text-model SOURCE_TXT_OR_IMAGES_TXT \
  --output bridge
```

When `stage8_anchors.json` reports `READY`:

`ANCHORS_JSON_INPUT = '.../bridge/stage8_anchors.json'`

Stage 8 requires at least four fit controls. With seven or more solved controls, the bridge reserves three independent holdouts.

## Builders

GitHub Actions includes:

- `Build Android APK`
- `Build iOS` — simulator + unsigned physical-device compile
- `Build Signed iOS / TestFlight` — Ad Hoc or App Store Connect export; optional TestFlight upload when Apple secrets are configured
- `Check MyCON Web`
- `Deploy MyCON Web` — GitHub Pages deployment after Pages is enabled for the repository

## Validation

```bash
python tools/mycon_session_check.py SESSION_MYCON.zip
python tools/mycon_r4_bridge.py --self-test
python tools/qr_pose_math_check.py
```

CI compiles Android and iOS and smoke-tests the web app on feature branches and pull requests.

## Design references

The suite adopts useful workflow ideas seen in modern capture tools — real-time capture guidance, raw/depth export, room scanning, open 3D viewing and measurements — while keeping MyCON's scientific capture files local, explicit and reproducible.

See `docs/CROSS_PLATFORM_ARCHITECTURE.md` and `THIRD_PARTY_NOTICES.md`.


## MyCON 1.0 additions

- System / Light / Dark appearance on Web and native apps.
- Built-in GitHub-hosted 3D model catalog in MyCON Web.
- 1 m calibration cube included for immediate Viewer / AR / measurement testing.
- Version-aligned Android, iOS and capture manifests at 1.0.0.
- GitHub Release workflow publishes Android, iOS build artifacts and Web package for v1.0.0.
