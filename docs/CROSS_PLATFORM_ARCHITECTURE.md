# MyCON 0.9 Cross-Platform Architecture

## Principle

Android and iOS are the authoritative scientific acquisition layers. The Web app is a companion surface and fallback recorder.

```text
Android / ARCore ─┐
                  ├─ MYCON_CAPTURE_SESSION v1 ─ R4 Bridge ─ MyCON Stages
iOS / ARKit ──────┘

Web PWA ─ QR / QA / 3D / measurements / fallback documentation capture
```

## Stable external contract

The native apps preserve:

- `MYCON_CAPTURE_SESSION`
- `format_version = 1`
- `mycon://anchor/v1`
- `arcore_recording.mp4` legacy dataset filename
- pose convention fields already consumed by the R4 bridge
- survey controls as the project/metric reference
- phone tracking as prior / validation evidence, never final geometry

## Sensor layers

### Android

ARCore supplies world tracking, camera intrinsics and Recording & Playback. MyCON adds external CSV/JSON sidecars, raw Depth and confidence images, GNSS, IMU, QR/PnP controls and integrity metadata.

### iOS

ARKit supplies world tracking and intrinsics. MyCON records synchronized RGB video, pose, CoreMotion, GNSS, scene depth and optional scene reconstruction. RoomPlan is an independent architectural scan mode and does not alter the scientific R4 pipeline.

### Web

Browser APIs provide camera, DeviceMotion and Geolocation but not an equivalent metric ARKit/ARCore 6DoF contract. Therefore web fallback capture is isolated under a different session schema and cannot pass the scientific pose gate.

## Capture quality guidance

The native apps prioritize:

1. tracking state
2. image texture / feature density
3. motion rate and blur risk
4. lighting
5. project/control availability
6. depth availability where supported
7. multi-angle and overlapping capture

The UI should warn only when the operator can act on the warning.

## Depth policy

Depth is auxiliary geometry evidence.

- Android: raw uint16 depth in millimetres + uint8 confidence
- iOS: float32 scene depth in metres + ARKit confidence
- repeated/stale depth should not be treated as a new independent observation
- Depth never silently replaces image-based reconstruction or bundle adjustment

## Compatibility policy

The Python bridge remains the single adapter between a native capture package and the R4 notebook. Platform-specific acquisition details should be normalized in sidecars, not fork the downstream stages.

## Web deployment

The repository contains a Pages workflow. GitHub Pages must be enabled once with **Source = GitHub Actions**. After that, pushes to `main` under `web/**` deploy automatically.
