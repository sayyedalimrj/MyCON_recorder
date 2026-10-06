# Scientific capture profile — v0.5

MyCON Recorder v0.5 changes the camera pipeline because the default ARCore recording path can otherwise produce only the 640×480 CPU tracking stream in the MP4.

## Default profile: Scientific HQ • 30 FPS

The app now enumerates ARCore-supported rear camera configurations at runtime and chooses a configuration using these priorities:

1. rear-facing world camera;
2. standard/wide field of view when ARCore exposes multiple camera IDs;
3. highest available **CPU image** resolution, because this is the stream ARCore Recording stores as the primary high-resolution MP4 video stream;
4. 30 FPS target for the default scientific profile;
5. autofocus enabled;
6. ARCore EIS remains OFF.

The selected camera ID, CPU image resolution, GPU texture resolution, target FPS range, focal length/FOV estimate, depth/stereo usage and fallback state are written into `session.json`.

## Why 30 FPS is the scientific default

For SfM, resolution, sharpness, texture and geometric baseline matter more than keeping every frame of a high-FPS video. COLMAP itself recommends high overlap but also recommends down-sampling video frame rate because redundant frames are not necessarily helpful.

ARCore 60 FPS:
- is not supported by every device;
- uses more power and memory;
- can fall below its target in poor light because exposure time grows;
- may only be available with a lower-resolution CPU stream on some devices.

Therefore v0.5 uses quality-first HQ30 by default and exposes a separate Motion 60 profile for fast camera movement.

## Motion • 60 FPS

When selected, the app requests ARCore 60 FPS rear camera configs and again chooses the highest-resolution standard/wide candidate.

If 60 FPS is unavailable, it automatically falls back to Scientific HQ30 and records that fallback in the session manifest.

## ARCore Auto

This leaves ARCore's default camera configuration unchanged. It is useful only for compatibility testing. If the selected CPU stream is still VGA, the app warns before recording and requires explicit confirmation.

## Live sharpness risk

Per-frame ARCore image metadata is sampled for:
- sensor exposure time;
- ISO;
- frame duration;
- rolling-shutter skew.

The app combines exposure time, camera focal length in pixels and measured gyro angular velocity to estimate rotational motion blur in pixels:

`blur_px ≈ fx × angular_velocity_rad_s × exposure_s`

A live warning is shown at approximately:
- >2 px: blur risk;
- >4 px: high blur risk.

This is a capture QA estimate, not a deblurring algorithm.

## Recorded QA

The capture package now records:
- target camera profile;
- actual selected camera ID;
- actual CPU/recording stream resolution;
- GPU preview resolution;
- requested FPS range;
- observed effective FPS computed from frame timestamps;
- average/max exposure;
- average ISO;
- frame-level exposure/ISO/frame-duration/rolling-shutter metadata in `pose.csv`.

## Multi-camera limitation

ARCore camera configurations expose a Camera2 camera ID. On devices where several ARCore-compatible rear camera IDs are exposed, MyCON prefers the standard/wide camera rather than ultra-wide or telephoto.

On many multi-camera phones ARCore exposes only one logical rear camera. In that case the app cannot safely force an arbitrary physical ultra-wide/tele sensor without moving to the SharedCamera/Camera2 path. That path is intentionally not the default because additional streams increase performance load, device compatibility becomes less predictable, and ARCore cannot use its hardware depth sensor while SharedCamera is active.

The current design therefore chooses the highest-quality ARCore-supported scientific capture path first, and reports exactly what the phone actually supplied.
