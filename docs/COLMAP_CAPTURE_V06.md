# COLMAP-ready capture — v0.6

MyCON Recorder v0.6 is tuned for video-to-SfM capture rather than for cinematic video.

## Capture invariants

A session keeps one ARCore camera configuration and does not use digital zoom. The session manifest records the actual camera ID, resolution, FPS range, focus mode, torch state, EIS state and measured exposure/FPS statistics.

COLMAP treats images from one physical camera with the same lens/zoom as one camera and recommends sharing intrinsics across those images. MyCON therefore keeps one camera configuration for the whole session and writes per-frame intrinsics for audit/initialization.

## Recommended defaults

- **Scientific HQ 30 FPS**
- **Auto focus** for video and mixed working distances
- **EIS OFF**
- **Torch OFF** unless the scene is genuinely too dark
- no digital zoom
- full-resolution CPU/recorded stream
- high visual overlap and slow, smooth camera translation

For a scene where all geometry stays comfortably beyond the fixed-focus distance, the Advanced image settings allow **Fixed focus**. This can reduce focus breathing, but it must not be used when nearby objects become soft.

## Exposure / blur QA

The app reads per-frame exposure time, ISO, frame duration and rolling-shutter skew from ARCore image metadata. It combines exposure with measured gyroscope angular velocity and focal length in pixels to estimate rotational image smear.

This is used only as a field QA warning:
- around >2 px: caution;
- around >4 px: strong warning.

The values are engineering thresholds for MyCON capture QA, not COLMAP hard limits.

## Model stability

QR PnP is now only an initialization source for attached AR models.

For a model QR, MyCON collects multiple good PnP observations and requires:
- at least 8 accepted observations;
- reprojection error <= 5 px;
- translation spread <= 35 mm;
- rotation spread <= 6 degrees.

Only after that consensus is reached does the app create a **Session Anchor**. Rendering uses the ARCore Anchor pose from then on, rather than replacing the model pose every time the QR decoder fires.

This is important because ARCore world-space numerical poses can adjust as the map improves; ARCore Anchors are designed to keep attached virtual content stable through those world updates.

The scale correction is frozen while the model is visible. Additional control QR observations can refine the scale for the next activation without making an already visible model visibly grow/shrink.

## COLMAP processing hints

The session manifest contains:

```json
{
  "capture_controls": {
    "focus_mode": "auto",
    "torch_enabled": false,
    "eis_mode": "OFF",
    "digital_zoom": 1.0,
    "single_camera_config_locked": true
  },
  "sfm_hints": {
    "matching": "SEQUENTIAL",
    "share_intrinsics_within_session": true,
    "use_full_resolution": true,
    "pose_prior_policy": "PRIOR_NOT_GROUND_TRUTH"
  }
}
```

For the notebook:
1. extract/select sharp keyframes rather than feeding every redundant video frame;
2. preserve frame timestamps;
3. use sequential matching for the temporal sequence, with loop detection / wider matching where needed;
4. group images from one unchanged MyCON session as one camera/intrinsic group;
5. initialize from recorded intrinsics/poses but allow bundle adjustment to refine them;
6. do not treat phone GNSS or ARCore poses as survey truth;
7. align/scale to surveyed MYCON controls.

## Why manual AE/AWB lock is not enabled in v0.6

Android Camera2 exposes AE lock and deeper manual sensor controls. In an ARCore app, controlling them robustly requires moving to the SharedCamera/Camera2 path on supported devices. That increases device-specific complexity and stream/performance pressure.

v0.6 therefore keeps the main capture path device-safe: exposure/ISO are measured and warned on, while camera config, lens/zoom and EIS behavior remain deterministic.

A SharedCamera manual-exposure profile should be added only after testing the current scientific profile on the target phones.
