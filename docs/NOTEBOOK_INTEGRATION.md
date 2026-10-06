# MyCON notebook integration contract

A `*_MYCON.zip` file is a scientific acquisition/evidence package, not a replacement notebook or a pre-built COLMAP reconstruction.

## Preserve the actual MyCon R4 order

Recorder integration must not silently reorder or bypass the verified pipeline.

Reality/reconstruction remains:

`Stage 1 → 2 → 3 → 4 → 5 → 6 → 7`

The notebook may contain internal/intermediate phase labels, but Recorder integration does not renumber them.

Thesis continuation follows the current notebook policy:

`8 → 9 → 11 → 10 → [CASE_STUDY if selected] → M16`

Stage 8 still refuses to invent metric scale/registration.

## Stage 1

Use:

`arcore_recording.mp4`

as the normal MyCon source video.

All existing video preflight, native-FPS validation, adaptive keyframe selection, semantic masking and checkpoint/resume behavior remain active.

## Stage 2

The current MyCon R4 keyframe contract names selected source-video frames:

`frame_%08d.jpg`

Do not rename them before the bridge runs.

## Stage 3

Keep MyCon R4's COLMAP/learned frontend and camera-model hypothesis policy.

The Recorder does not force a single camera model and does not replace:
- feature extraction;
- sequential/temporal matching;
- geometric verification;
- mapping;
- bundle adjustment.

One unchanged Recorder session should be grouped as one physical camera/intrinsic group.

## Stage 4 — exact Recorder bridge

After Stage 2, run the bridge bundled in the Recorder ZIP:

```bash
python tools/mycon_r4_bridge.py SESSION_MYCON.zip \
  --stage2-report STAGE2_REPORT_OR_KEYFRAME_DIRECTORY \
  --output BRIDGE_OUT
```

Then set:

`POSE_VALIDATOR_JSON_INPUT = '.../BRIDGE_OUT/pose_validator.json'`

The bridge names poses with the exact Stage-2 image names and emits:
- `center`;
- `world_to_camera_q`;
- source video frame number;
- ARCore timestamp;
- timestamp association error.

Policy is `VALIDATE_ONLY_NEVER_AVERAGE`.

ARCore remains independent validation/prior evidence and does not overwrite COLMAP geometry.

## Optional COLMAP pose priors

The bridge also writes:

`colmap_pose_priors.json`

These are conservative Cartesian position priors. They are for explicit experiments with COLMAP/PyCOLMAP pose-prior workflows only.

The default MyCon Stage-3 path does **not** consume them automatically.

## Stage 8 — metric anchors

After a COLMAP text model is available, rerun:

```bash
python tools/mycon_r4_bridge.py SESSION_MYCON.zip \
  --stage2-report STAGE2_REPORT_OR_KEYFRAME_DIRECTORY \
  --colmap-text-model SOURCE_TXT_OR_IMAGES_TXT \
  --output BRIDGE_OUT
```

The bridge robustly aligns the ARCore camera trajectory to the COLMAP camera trajectory and transforms solved QR marker centres into the COLMAP source frame.

It then pairs:

- `source`: marker centres in COLMAP reconstruction coordinates;
- `target`: surveyed/project XYZ from MYCON QR payloads.

Output:

`stage8_anchors.json`

Then set:

`ANCHORS_JSON_INPUT = '.../BRIDGE_OUT/stage8_anchors.json'`

Current capture policy:
- <4 solved controls: refuse Stage-8-ready status;
- 4–6: calibration controls only;
- 7+: reserve 3 controls as independent holdout.

## If the notebook uses a clipped validation segment

The Stage-2 frame number belongs to the clip, while Recorder timestamps belong to the original MP4.

Pass the original-video clip start:

`--video-offset-s <seconds>`

Example:

`--video-offset-s 18.0`

## Capture package v0.7

Expected files:

```text
arcore_recording.mp4
pose.csv
imu.csv
qr_events.jsonl
session.json
r4_camera.json
r4_controls.json
r4_compatibility.json
integrity_sha256.json
R4_IMPORT_README.txt
tools/mycon_r4_bridge.py
```

The session schema deliberately remains:

- `format = MYCON_CAPTURE_SESSION`
- `format_version = 1`

The bridge contract is independently versioned so older notebook/session readers are not broken.

## Scientific invariants

- Ignore non-`TRACKING` rows as hard pose evidence.
- Preserve nanosecond timestamps.
- GNSS is auxiliary evidence only.
- ARCore is a metric local prior/validator, not final geometry.
- Surveyed QR coordinates are metric/project evidence.
- Keep feature matching and bundle adjustment.
- Never invent Stage-8 metric scale when controls are insufficient.
- Keep refusal/uncertainty states as first-class outputs.
