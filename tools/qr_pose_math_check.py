#!/usr/bin/env python3
"""Deterministic math checks for the v0.4 QR/AR model contract.

This does not emulate ARCore. It verifies the exact test QR signatures,
planar square homography decomposition, reprojection, and pairwise metric
scale correction used by the Android implementation.
"""

from __future__ import annotations

import hashlib
import math
from urllib.parse import urlencode

import numpy as np


def fmt(v: float) -> str:
    s = f"{v:.4f}".rstrip("0").rstrip(".")
    return s or "0"


def payload(anchor: str, x: float, y: float, z: float, model=False) -> str:
    project = "TEST01"
    crs = "LOCAL:TEST01"
    mount = "VERTICAL"
    azimuth = 0.0
    size_mm = 180.0
    floor = "TEST"

    canonical = "&".join(
        [
            "v=1",
            f"project={project}",
            f"anchor={anchor}",
            f"crs={crs}",
            f"x={fmt(x)}",
            f"y={fmt(y)}",
            f"z={fmt(z)}",
            f"mount={mount}",
            f"azimuth_deg={fmt(azimuth)}",
            f"size_mm={fmt(size_mm)}",
            f"floor={floor}",
        ]
    )

    params = [
        ("project", project),
        ("anchor", anchor),
        ("crs", crs),
        ("x", fmt(x)),
        ("y", fmt(y)),
        ("z", fmt(z)),
        ("mount", mount),
        ("azimuth_deg", fmt(azimuth)),
        ("size_mm", fmt(size_mm)),
        ("floor", floor),
    ]

    if model:
        canonical += "&model_id=test_cube_1m&model_size_m=1"
        params += [("model_id", "test_cube_1m"), ("model_size_m", "1")]

    sig = hashlib.sha256(canonical.encode()).digest()[:6].hex()
    params.append(("sig", sig))
    return "mycon://anchor/v1?" + urlencode(params)


EXPECTED = {
    "A001": "mycon://anchor/v1?project=TEST01&anchor=A001&crs=LOCAL%3ATEST01&x=0&y=0&z=0&mount=VERTICAL&azimuth_deg=0&size_mm=180&floor=TEST&model_id=test_cube_1m&model_size_m=1&sig=8ace1e1e9fb0",
    "A002": "mycon://anchor/v1?project=TEST01&anchor=A002&crs=LOCAL%3ATEST01&x=0.5&y=0&z=0&mount=VERTICAL&azimuth_deg=0&size_mm=180&floor=TEST&sig=313b34417c7f",
    "A003": "mycon://anchor/v1?project=TEST01&anchor=A003&crs=LOCAL%3ATEST01&x=0&y=0.5&z=0&mount=VERTICAL&azimuth_deg=0&size_mm=180&floor=TEST&sig=af8a0a53c55f",
}


def solve_homography(src, dst):
    a = np.zeros((8, 8), dtype=float)
    b = np.zeros(8, dtype=float)

    for i, ((x, y), (u, v)) in enumerate(zip(src, dst)):
        r0 = 2 * i
        r1 = r0 + 1

        a[r0] = [x, y, 1, 0, 0, 0, -u * x, -u * y]
        b[r0] = u

        a[r1] = [0, 0, 0, x, y, 1, -v * x, -v * y]
        b[r1] = v

    return np.linalg.solve(a, b)


def decompose_square(corners_px, fx, fy, cx, cy, size_m):
    half = size_m / 2
    obj = np.array(
        [
            [-half, -half],
            [half, -half],
            [half, half],
            [-half, half],
        ],
        dtype=float,
    )

    norm = np.array(
        [[(u - cx) / fx, (v - cy) / fy] for u, v in corners_px],
        dtype=float,
    )

    h = solve_homography(obj, norm)

    c1 = np.array([h[0], h[3], h[6]], dtype=float)
    c2 = np.array([h[1], h[4], h[7]], dtype=float)
    c3 = np.array([h[2], h[5], 1.0], dtype=float)

    lam = 2.0 / (np.linalg.norm(c1) + np.linalg.norm(c2))
    r1 = lam * c1
    r2 = lam * c2
    t = lam * c3

    if t[2] < 0:
        r1 *= -1
        r2 *= -1
        t *= -1

    r1 /= np.linalg.norm(r1)
    r2 = r2 - np.dot(r2, r1) * r1
    r2 /= np.linalg.norm(r2)
    r3 = np.cross(r1, r2)
    r3 /= np.linalg.norm(r3)

    r = np.column_stack([r1, r2, r3])
    return r, t


def project_square(r, t, fx, fy, cx, cy, size_m):
    half = size_m / 2
    pts = [
        (-half, -half, 0.0),
        (half, -half, 0.0),
        (half, half, 0.0),
        (-half, half, 0.0),
    ]
    out = []
    for p in pts:
        c = r @ np.asarray(p) + t
        out.append((fx * c[0] / c[2] + cx, fy * c[1] / c[2] + cy))
    return out


def main() -> int:
    assert payload("A001", 0, 0, 0, model=True) == EXPECTED["A001"]
    assert payload("A002", 0.5, 0, 0) == EXPECTED["A002"]
    assert payload("A003", 0, 0.5, 0) == EXPECTED["A003"]

    fx = 1100.0
    fy = 1080.0
    cx = 640.0
    cy = 480.0
    size = 0.180

    # Test 1: front-facing marker at 2 m.
    r_true = np.eye(3)
    t_true = np.array([0.08, -0.03, 2.0])
    corners = project_square(r_true, t_true, fx, fy, cx, cy, size)
    r_est, t_est = decompose_square(corners, fx, fy, cx, cy, size)

    assert np.linalg.norm(t_est - t_true) < 1e-7, (t_est, t_true)
    assert np.linalg.norm(r_est - r_true) < 1e-7, (r_est, r_true)

    # Test 2: in-plane marker rotation.
    theta = math.radians(31.0)
    r_true = np.array(
        [
            [math.cos(theta), -math.sin(theta), 0],
            [math.sin(theta), math.cos(theta), 0],
            [0, 0, 1],
        ]
    )
    t_true = np.array([-0.12, 0.07, 1.4])
    corners = project_square(r_true, t_true, fx, fy, cx, cy, size)
    r_est, t_est = decompose_square(corners, fx, fy, cx, cy, size)

    assert np.linalg.norm(t_est - t_true) < 1e-7
    assert np.linalg.norm(r_est - r_true) < 1e-7

    # Test 3: multi-marker scale correction.
    known = [0.0, 0.5, 0.5 * math.sqrt(2)]
    observed = [d / 1.04 for d in known]
    ratios = [k / o for k, o in zip(known[1:], observed[1:])]
    scale = sorted(ratios)[len(ratios) // 2]
    assert abs(scale - 1.04) < 1e-12

    print("MYCON v0.4 QR/PnP math QA: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
