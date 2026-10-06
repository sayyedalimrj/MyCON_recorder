# AR model test kit — v0.4

MyCON Recorder v0.4 can attach a built-in metric 3D model to a MYCON QR control.

## Test model

- model id: `test_cube_1m`
- true side length: **1.000 m**
- QR A001 is the model origin/control.
- the cube is drawn with its back face on the QR plane and extends outward along the marker normal.

The model is procedural OpenGL geometry bundled in the app, so the test does not depend on downloading a GLB/OBJ file.

## Pose solve

The app does not place the cube by screen-space guessing. For every accepted model QR observation it uses:

1. four detected QR corners in raw image pixels;
2. camera `fx, fy, cx, cy`;
3. the physical QR `size_mm`;
4. a planar square homography/PnP decomposition;
5. ARCore's camera world pose.

The observation is rejected if the PnP reprojection RMS exceeds 10 px or the solved marker distance is outside the accepted working range.

Repeated observations are averaged to reduce placement jitter.

## Three-marker scale check

For the supplied TEST01 kit, print and mount the marker centres as:

- A001: `(0.000, 0.000, 0.000) m` — model origin, Test Cube 1m attached
- A002: `(0.500, 0.000, 0.000) m`
- A003: `(0.000, 0.500, 0.000) m`

All markers use a **160 mm QR symbol**.

Mount the centres of A002 and A003 exactly 500 mm from A001. A002 is 500 mm to the project +X direction; A003 is 500 mm to +Y.

When 2+ controls have been observed, the app compares known project distances with observed AR distances and computes a median scale correction. With 3 controls, three pair distances contribute.

A correction outside **0.80–1.20** is considered implausible and the UI shows a red calibration warning rather than silently stretching the model.

## Field test order

1. Print markers at **100% / Actual Size**.
2. Verify QR symbol size and the 100 mm print bar.
3. Start the app and wait for Tracking ✓.
4. Scan A001 until the model status becomes `3D READY`.
5. Tap **مدل AR**. The 1 m cube should stay attached to A001.
6. Scan A002 and A003 without restarting the ARCore session.
7. Watch the model status: it shows the number of controls and scale factor.
8. Walk around the cube and visually verify its 1 m size against a tape/known object.
9. Record screenshots and a short capture for QA.

The multi-marker correction is an additional scale QA/refinement. ARCore and the physical QR size already provide metric information; the extra controls are intended to catch bad print scaling, bad control coordinates, and systematic placement error.
