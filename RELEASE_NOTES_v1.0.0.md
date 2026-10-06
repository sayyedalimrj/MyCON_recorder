# MyCON 1.0.0

First stable cross-platform release of the MyCON field capture suite.

## Included

- Android / ARCore scientific capture
- iOS / ARKit scientific capture
- Web / PWA companion
- MYCON_CAPTURE_SESSION v1 and mycon://anchor/v1 compatibility
- R4 bridge, Stage 4 pose validation support and Stage 8 metric controls
- IMU + GNSS + QR/PnP
- Android Raw Depth + confidence
- iOS Scene Depth / LiDAR + RoomPlan
- Web QR tools, local QA, 3D/AR viewer and measurements
- System / Light / Dark appearance modes
- Built-in GitHub-hosted 1 m calibration cube model
- Offline PWA caching

## Release artifacts

- Android APK: installable debug-signed v1.0.0 build
- iOS physical-device app: unsigned build archive
- iOS Simulator app
- Web/PWA source package

## iOS signing

The public GitHub Release includes an unsigned iPhone device build. A directly installable IPA / TestFlight build still requires Apple Developer signing credentials. The repository includes the Build Signed iOS / TestFlight workflow for this final signing step.
