# MyCON notebook integration contract

A `*_MYCON.zip` file is an acquisition package, not a plain video.

## Preserve the scientific pipeline

This recorder must **not** renumber or reorder the thesis stages. It improves acquisition and provides priors/controls.

Recommended flow remains:

`Stage 1 → 2 → 3 → 4 → 4.5 → 5 → 6 → 7 → 8 → 9 → 10 → 11`

Pose recovery becomes a fallback rather than the default path.

## Stage 1 importer

1. Open the ZIP and validate `session.json`.
2. Use `arcore_recording.mp4` as the video/dataset source.
3. Load `pose.csv` as metric local camera-pose priors.
4. Load `imu.csv` as auxiliary high-rate motion evidence.
5. Load valid `qr_events.jsonl` observations.
6. For each control observation, solve marker pose from:
   - four QR corners;
   - `size_mm`;
   - `fx, fy, cx, cy`.
7. Combine the solved marker pose with its known project pose.
8. Robustly estimate the ARCore-local → project transform using multiple controls.
9. Reject controls with high reprojection error or inconsistent project IDs.
10. Feed transformed ARCore poses to COLMAP/BA as **priors**, not immutable truth.

## Quality gates

- Ignore pose rows where `tracking != TRACKING` for hard constraints.
- Treat GNSS as auxiliary evidence; do not use phone GNSS as survey-grade truth.
- Require one valid control for an absolute frame; prefer 3+ spatially separated controls.
- Verify `size_mm` against the physical print.
- Preserve nanosecond timestamps.
- Validate frame counts/timing before associating video frames with pose rows.
- Keep image matching and bundle adjustment. The phone trajectory should reduce ambiguity, not bypass reconstruction QA.

## Capture package v1

Expected files:

```text
arcore_recording.mp4
pose.csv
imu.csv
qr_events.jsonl
session.json
```

`session.json.format` must be `MYCON_CAPTURE_SESSION` and `format_version` must currently be `1`.
