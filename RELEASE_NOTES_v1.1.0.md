# MyCON 1.1.0

Sensor-focused release for iPhone and Web.

## iPhone / iPad

- ARKit LiDAR raw scene depth capture.
- Smoothed scene depth captured alongside raw depth.
- LiDAR depth confidence maps.
- Scene-reconstruction mesh export to binary PLY.
- ARKit sparse feature-point snapshot export.
- Gravity, user acceleration, calibrated magnetic field, barometer and heading evidence.
- GNSS and existing IMU streams preserved.
- RoomPlan remains available for structural room scanning and USDZ export.
- Compact Sensor Center in the Capture UI.
- Web-to-app handoff through `mycon://capture`.

## Web / PWA

- Automatic sensor capability probe.
- Camera + DeviceMotion + GNSS fallback capture.
- WebXR Depth Sensing capture on browsers/devices that actually support `immersive-ar` + `depth-sensing`.
- WebXR depth packages preserve depth buffers, scale-to-metres, projection and view transforms.
- iPhone Safari does not pretend to expose raw LiDAR; the UI offers a direct handoff to the native iOS app instead.
- Cleaner Capture UI with one compact Sensors panel.

## Scientific boundary

- Native ARKit / ARCore remain the scientific acquisition paths.
- WebXR depth is experimental auxiliary evidence and is marked `not_for_r4_pose_validation=true`.
- LiDAR depth / scene mesh are auxiliary geometry evidence and do not replace image-based bundle adjustment.
- `MYCON_CAPTURE_SESSION v1`, QR controls and R4 compatibility remain unchanged.

## Versions

- Android: 1.1.0 / build 12
- iOS: 1.1.0 / build 12
- Web/PWA: 1.1
