# MyCON Recorder → MyCon R4 / COLMAP bridge

Version: v0.7

This contract was written against the current MyCon R4 v4.8.x notebook family and preserves its scientific stage order.

## What the Recorder package is

A Recorder ZIP is **not** a replacement COLMAP workspace. It is the acquisition/evidence package that feeds the normal MyCon R4 video pipeline and adds synchronized priors/controls.

Required capture files:

- `arcore_recording.mp4`
- `pose.csv`
- `imu.csv`
- `qr_events.jsonl`
- `session.json`
- `r4_camera.json`
- `r4_controls.json`
- `r4_compatibility.json`
- `tools/mycon_r4_bridge.py`

## Stage 1–3

Use `arcore_recording.mp4` as the normal video input.

Do not bypass:
- Stage-1 video validation;
- Stage-2 adaptive keyframe selection/masks;
- Stage-3 learned/COLMAP frontend and camera-model hypothesis search.

The Recorder keeps a single ARCore camera configuration through a capture session, disables digital zoom and EIS, and records per-frame intrinsics.

The current MyCon Stage-2 image naming convention is:

`frame_%08d.jpg`

The bridge preserves that exact name because the Stage-4 validator and COLMAP model are joined by image name.

## Stage 4 — independent pose validator

After Stage 2:

```bash
python tools/mycon_r4_bridge.py SESSION_DIR_OR_ZIP \
  --stage2-report STAGE2_REPORT_OR_KEYFRAME_DIRECTORY \
  --output BRIDGE_OUT
```

Set the notebook:

`POSE_VALIDATOR_JSON_INPUT = '.../BRIDGE_OUT/pose_validator.json'`

The bridge:
1. maps each `frame_XXXXXXXX.jpg` source-video frame number to the closest ARCore camera timestamp;
2. outputs camera centre in the ARCore session-local metric frame;
3. converts ARCore camera-axis convention to COLMAP world-to-camera quaternion convention;
4. marks the validator as ready when at least six keyframes are mapped.

This validator is intentionally **validation-only**. It does not average ARCore and COLMAP poses and does not replace bundle adjustment.

If the notebook is processing a clip cut from the original Recorder MP4, pass the original-video start offset:

`--video-offset-s 18.0`

## Optional COLMAP position-prior interchange

The same bridge writes:

`colmap_pose_priors.json`

It contains:
- image name;
- ARCore-local metric position;
- Cartesian coordinate-system declaration;
- conservative position covariance.

The default MyCon Stage-3 path remains unchanged. This file exists for controlled experiments with COLMAP/PyCOLMAP pose-prior workflows; it is never injected silently.

## Stage 8 — metric project alignment

After a COLMAP sparse/refined model is available as text, rerun:

```bash
python tools/mycon_r4_bridge.py SESSION_DIR_OR_ZIP \
  --stage2-report STAGE2_REPORT_OR_KEYFRAME_DIRECTORY \
  --colmap-text-model SOURCE_TXT_OR_IMAGES_TXT \
  --output BRIDGE_OUT
```

The bridge then:

1. reads COLMAP camera centres from `images.txt`;
2. matches them to ARCore camera centres by exact Stage-2 image name;
3. robustly estimates ARCore-local → COLMAP-source Sim(3);
4. transforms the best QR marker centres into COLMAP source coordinates;
5. pairs them with the surveyed/project XYZ encoded in those QR controls;
6. writes `stage8_anchors.json`.

For the current MyCon Stage-8 metric gate:

- fewer than 4 usable controls → `INSUFFICIENT_CONTROLS`;
- 4–6 controls → fit anchors available, no independent holdout;
- 7+ controls → the bridge reserves 3 controls as holdout.

Set:

`ANCHORS_JSON_INPUT = '.../BRIDGE_OUT/stage8_anchors.json'`

The bridge never invents metric scale when surveyed controls are insufficient.

## COLMAP compatibility choices

### Camera grouping

All keyframes from one unchanged Recorder session should be treated as one physical camera/intrinsic group.

Do not mix captures after:
- camera/lens change;
- digital zoom change;
- resolution/config change.

### Matching

The capture is temporal video; sequential matching is the primary compatibility mode.

The Recorder does not force one COLMAP camera model. MyCon R4's own hypothesis selection remains authoritative.

### Coordinate conventions

Recorder `pose.csv` stores ARCore camera-in-world pose:

- translation: camera centre in ARCore world;
- quaternion: `qx,qy,qz,qw`.

COLMAP `images.txt` uses world-to-camera pose:

- quaternion: `qw,qx,qy,qz`;
- camera coordinates: +X right, +Y down, +Z forward.

The bundled bridge performs this conversion explicitly.

## Package integrity

`integrity_sha256.json` hashes the small scientific sidecars.

The large MP4 is marked `DEFERRED_LARGE_MEDIA` so stopping a field capture does not block the UI while hashing hundreds of megabytes. Hash the MP4 during offline ingestion if full media-chain integrity is required.

## Scientific policy

- Phone GNSS is auxiliary evidence, not survey truth.
- ARCore is a metric local trajectory prior, not final geometry.
- QR controls are surveyed/project references.
- COLMAP/SfM and bundle adjustment remain active.
- MyCon Stage 4 remains a scientific gate.
- Stage 8 refuses metric registration when control evidence is insufficient.
