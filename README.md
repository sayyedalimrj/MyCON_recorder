# MyCON Recorder

Professional Android field capture for the MyCON R4 reconstruction/thesis pipeline.

## v0.7 capture package

Every finished session is self-contained:

- `arcore_recording.mp4` — ARCore high-resolution CPU recording stream selected by the scientific camera profile
- `pose.csv` — synchronized metric ARCore camera centres, rotations, intrinsics, exposure/ISO/frame timing
- `imu.csv` — accelerometer, gyroscope, rotation-vector stream
- `qr_events.jsonl` — surveyed MYCON controls, QR corners, intrinsics, solved marker pose and reprojection QA
- `session.json` — capture/camera/quality manifest
- `r4_camera.json` — camera/intrinsics bridge metadata
- `r4_controls.json` — best solved observation for each metric control
- `r4_compatibility.json` — exact MyCON R4 compatibility contract
- `integrity_sha256.json` — hashes for scientific sidecars
- `R4_IMPORT_README.txt`
- `tools/mycon_r4_bridge.py` — bundled offline bridge

The exported file remains `*_MYCON.zip`.

## MyCON R4 integration

The Recorder **does not replace** Stage 1–7, COLMAP, independent pose validation, or bundle adjustment.

Use the MP4 as the normal MyCON input.

After Stage 2:

```bash
python tools/mycon_r4_bridge.py SESSION_MYCON.zip \
  --stage2-report STAGE2_REPORT_OR_KEYFRAME_DIR \
  --output bridge
```

Then set:

`POSE_VALIDATOR_JSON_INPUT = '.../bridge/pose_validator.json'`

After a COLMAP text model exists:

```bash
python tools/mycon_r4_bridge.py SESSION_MYCON.zip \
  --stage2-report STAGE2_REPORT_OR_KEYFRAME_DIR \
  --colmap-text-model SOURCE_TXT_OR_IMAGES_TXT \
  --output bridge
```

When `stage8_anchors.json` reports `READY`, set:

`ANCHORS_JSON_INPUT = '.../bridge/stage8_anchors.json'`

Current MyCON Stage-8 policy needs at least four fit controls. With seven or more solved controls the bridge reserves three independent controls as holdout.

See [MyCON R4 bridge contract](docs/MYCON_R4_BRIDGE.md).

## COLMAP policy

- one unchanged Recorder session = one physical camera/intrinsic group;
- no digital zoom;
- sequential temporal matching;
- keep MyCON R4's camera-model hypothesis search;
- ARCore positions are optional priors/independent validation evidence, never final geometry;
- surveyed QR controls provide project/metric evidence;
- bundle adjustment remains authoritative.

The bridge also exports `colmap_pose_priors.json` for controlled pose-prior experiments. It is never injected into the default pipeline silently.

## Capture UI

The capture screen is intentionally minimal:

- compact tracking / project / video HUD;
- centered camera-style record control;
- dedicated QR and 3D actions;
- history and secondary tools moved out of the viewfinder;
- Material bottom sheets for tools and camera details;
- warning overlay appears only when action is needed;
- adaptive max-width panels for landscape/tablet layouts;
- 48dp+ touch targets and accessibility descriptions.

## 3D control model

A QR-attached model is not continuously re-positioned from noisy QR detections.

The app first collects a stable multi-frame PnP consensus and then creates an ARCore Session Anchor. Rendering follows that Anchor afterwards.

## Scientific camera profiles

Default: **Scientific HQ • 30 FPS**.

Alternative: **Motion • 60 FPS**, with fallback to HQ30 when unsupported.

The app records the actual camera ID, CPU/recorded resolution, GPU preview size, FPS range, exposure, ISO and rolling-shutter metadata.

## Validation

```bash
python tools/mycon_session_check.py SESSION_MYCON.zip
python tools/mycon_r4_bridge.py --self-test
python tools/qr_pose_math_check.py
```

## Compatibility

- Android min SDK: 24
- ARCore-capable physical device required
- marker schema: `mycon://anchor/v1`
- capture session schema remains `MYCON_CAPTURE_SESSION / format_version=1`
- R4 bridge schema is versioned independently
