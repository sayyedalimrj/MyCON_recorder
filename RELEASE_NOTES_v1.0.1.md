# MyCON 1.0.1

Patch release for the built-in 3D calibration model and model validation.

## Fixed

- Rebuilt the built-in 1 m calibration cube from scratch.
- Correct geometry: 6 faces, 24 face vertices, 24 normals, 36 indices / 12 triangles.
- Exact model bounds remain 1.000 × 1.000 × 1.000 m.
- Replaced the old model URL so browsers cannot reuse the malformed cached asset.
- Bumped the PWA cache so iPhone/Android/Desktop receive the corrected model.
- Added strict CI validation for embedded glTF buffer sizes, accessors, normals, indices and 1 m bounds.

## Platforms

- Android version 1.0.1 / build 11
- iOS version 1.0.1 / build 11
- Web/PWA 1.0.1

Scientific MYCON_CAPTURE_SESSION v1 and R4 compatibility remain unchanged.
