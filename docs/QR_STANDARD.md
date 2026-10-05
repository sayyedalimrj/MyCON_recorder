# MYCON Control Marker Standard v1

MYCON control markers tie the ARCore-local metric camera trajectory to a known project coordinate frame.

## URI payload

`mycon://anchor/v1?...&sig=<checksum>`

Required fields:

- `project`: project identifier.
- `anchor`: unique control-marker identifier.
- `crs`: `EPSG:<code>` or `LOCAL:<project-id>`.
- `x`, `y`, `z`: surveyed coordinates of the **centre of the QR symbol**, metres.
- `mount`: `VERTICAL` or `HORIZONTAL`.
- `azimuth_deg`: orientation reference in degrees.
- `size_mm`: physical side length of the **QR symbol itself**, excluding the external quiet zone.
- `floor`: optional floor/zone label.
- `sig`: first 12 hexadecimal characters of SHA-256 over the canonical payload.

The checksum detects accidental edits; it is **not** a cryptographic identity/authentication signature.

## Physical print geometry

The app generates the QR symbol with zero library margin, then prints a separate four-module white quiet zone around it.

This distinction matters for PnP: `size_mm` is the physical distance between the outer boundaries of the QR **symbol**, not the paper, not the quiet zone, and not the whole PDF image.

Each generated PDF also contains an independent **100 mm verification bar**. After printing at **100% / Actual Size**, measure that bar. Reject scaled prints.

## Installation

1. Print at **100% / Actual Size**. Never use Fit-to-page.
2. Measure the 100 mm verification bar.
3. Measure the QR symbol side and verify it matches `size_mm`.
4. Survey/measure the centre of the QR symbol for X/Y/Z.
5. Keep the sheet flat.
6. For `VERTICAL`, keep the printed top edge level and record the outward-normal azimuth.
7. For `HORIZONTAL`, record the printed top-edge azimuth.
8. Prefer at least three spatially separated controls per work zone when practical.
9. Show a control near the beginning, after feature-poor/long transitions, and near the end of a capture.

## Offline solve

Every valid QR event stores:

- four detected image corners in raw camera-image pixels;
- exact physical `size_mm`;
- camera `fx, fy, cx, cy`;
- ARCore camera pose at the same frame timestamp;
- known marker project coordinates/orientation.

The notebook should solve square-marker PnP, reject high-reprojection-error observations, then robustly estimate the transform from ARCore-local world coordinates to the project frame.
