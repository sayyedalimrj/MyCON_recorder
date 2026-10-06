#!/usr/bin/env python3
"""Deterministic math checks for the MyCON v0.4 QR/AR contract.

Standard-library only. Verifies the exact TEST01 QR payload signatures,
planar square homography/PnP decomposition, reprojection geometry, and
multi-marker scale correction assumptions used by the Android app.
"""

from __future__ import annotations

import hashlib
import math
from urllib.parse import urlencode


def fmt(v: float) -> str:
    s = f"{v:.4f}".rstrip("0").rstrip(".")
    return s or "0"


def make_payload(anchor: str, x: float, y: float, z: float, model=False) -> str:
    project = "TEST01"
    crs = "LOCAL:TEST01"
    mount = "VERTICAL"
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
            "azimuth_deg=0",
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
        ("azimuth_deg", "0"),
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


def solve_linear(a, b):
    n = len(b)
    aug = [list(a[i]) + [float(b[i])] for i in range(n)]
    for col in range(n):
        pivot = max(range(col, n), key=lambda row: abs(aug[row][col]))
        if abs(aug[pivot][col]) < 1e-12:
            raise ValueError("singular")
        aug[col], aug[pivot] = aug[pivot], aug[col]
        div = aug[col][col]
        aug[col] = [v / div for v in aug[col]]
        for row in range(n):
            if row == col:
                continue
            factor = aug[row][col]
            if abs(factor) < 1e-15:
                continue
            for j in range(col, n + 1):
                aug[row][j] -= factor * aug[col][j]
    return [aug[i][n] for i in range(n)]


def solve_homography(src, dst):
    a = [[0.0] * 8 for _ in range(8)]
    b = [0.0] * 8
    for i, ((x, y), (u, v)) in enumerate(zip(src, dst)):
        r0 = 2 * i
        r1 = r0 + 1
        a[r0] = [x, y, 1, 0, 0, 0, -u * x, -u * y]
        b[r0] = u
        a[r1] = [0, 0, 0, x, y, 1, -v * x, -v * y]
        b[r1] = v
    return solve_linear(a, b)


def norm(v):
    return math.sqrt(sum(x * x for x in v))


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def cross(a, b):
    return [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]


def scale(v, s):
    return [x * s for x in v]


def normalize(v):
    n = norm(v)
    return [x / n for x in v]


def decompose_square(corners_px, fx, fy, cx, cy, size_m):
    hsize = size_m / 2
    obj = [
        (-hsize, -hsize),
        (hsize, -hsize),
        (hsize, hsize),
        (-hsize, hsize),
    ]
    dst = [((u - cx) / fx, (v - cy) / fy) for u, v in corners_px]
    h = solve_homography(obj, dst)

    c1 = [h[0], h[3], h[6]]
    c2 = [h[1], h[4], h[7]]
    c3 = [h[2], h[5], 1.0]

    lam = 2.0 / (norm(c1) + norm(c2))
    r1 = scale(c1, lam)
    r2 = scale(c2, lam)
    t = scale(c3, lam)

    if t[2] < 0:
        r1 = scale(r1, -1)
        r2 = scale(r2, -1)
        t = scale(t, -1)

    r1 = normalize(r1)
    proj = scale(r1, dot(r1, r2))
    r2 = normalize([r2[i] - proj[i] for i in range(3)])
    r3 = normalize(cross(r1, r2))

    # Row-major rotation from column vectors.
    r = [
        [r1[0], r2[0], r3[0]],
        [r1[1], r2[1], r3[1]],
        [r1[2], r2[2], r3[2]],
    ]
    return r, t


def mat_vec(r, p):
    return [sum(r[i][j] * p[j] for j in range(3)) for i in range(3)]


def project_square(r, t, fx, fy, cx, cy, size_m):
    h = size_m / 2
    pts = [(-h, -h, 0), (h, -h, 0), (h, h, 0), (-h, h, 0)]
    out = []
    for p in pts:
        rp = mat_vec(r, p)
        c = [rp[i] + t[i] for i in range(3)]
        out.append((fx * c[0] / c[2] + cx, fy * c[1] / c[2] + cy))
    return out


def matrix_error(a, b):
    return math.sqrt(
        sum((a[i][j] - b[i][j]) ** 2 for i in range(3) for j in range(3))
    )


def vector_error(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def main() -> int:
    assert make_payload("A001", 0, 0, 0, model=True) == EXPECTED["A001"]
    assert make_payload("A002", 0.5, 0, 0) == EXPECTED["A002"]
    assert make_payload("A003", 0, 0.5, 0) == EXPECTED["A003"]

    fx, fy, cx, cy = 1100.0, 1080.0, 640.0, 480.0
    size = 0.180

    r_true = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]
    t_true = [0.08, -0.03, 2.0]
    corners = project_square(r_true, t_true, fx, fy, cx, cy, size)
    r_est, t_est = decompose_square(corners, fx, fy, cx, cy, size)
    assert vector_error(t_est, t_true) < 1e-7
    assert matrix_error(r_est, r_true) < 1e-7

    theta = math.radians(31.0)
    r_true = [
        [math.cos(theta), -math.sin(theta), 0.0],
        [math.sin(theta), math.cos(theta), 0.0],
        [0.0, 0.0, 1.0],
    ]
    t_true = [-0.12, 0.07, 1.4]
    corners = project_square(r_true, t_true, fx, fy, cx, cy, size)
    r_est, t_est = decompose_square(corners, fx, fy, cx, cy, size)
    assert vector_error(t_est, t_true) < 1e-7
    assert matrix_error(r_est, r_true) < 1e-7

    known = [0.5, 0.5 * math.sqrt(2)]
    observed = [d / 1.04 for d in known]
    ratios = sorted(k / o for k, o in zip(known, observed))
    scale_factor = ratios[len(ratios) // 2]
    assert abs(scale_factor - 1.04) < 1e-12

    print("MYCON v0.4 QR/PnP math QA: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
