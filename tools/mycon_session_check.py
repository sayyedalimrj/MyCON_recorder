#!/usr/bin/env python3
"""Validate/report a MyCON Recorder session directory or ZIP.

No third-party packages are required.
"""

from __future__ import annotations

import csv
import io
import json
import sys
import zipfile
from pathlib import Path


BASE_REQUIRED = {
    "session.json",
    "pose.csv",
    "imu.csv",
    "qr_events.jsonl",
    "arcore_recording.mp4",
}

R4_REQUIRED_V07 = {
    "r4_camera.json",
    "r4_controls.json",
    "r4_compatibility.json",
    "R4_IMPORT_README.txt",
    "tools/mycon_r4_bridge.py",
    "integrity_sha256.json",
}


def read_from_zip(path: Path, name: str) -> bytes:
    with zipfile.ZipFile(path) as zf:
        candidates = [
            n
            for n in zf.namelist()
            if n.rstrip("/").endswith(name)
        ]
        if not candidates:
            raise FileNotFoundError(name)
        if len(candidates) > 1:
            exact = [
                n
                for n in candidates
                if n == name
            ]
            if len(exact) == 1:
                return zf.read(exact[0])
            raise RuntimeError(
                f"ambiguous {name}: {candidates}"
            )
        return zf.read(candidates[0])


def read_bytes(src: Path, name: str) -> bytes:
    if src.is_dir():
        return (src / name).read_bytes()
    return read_from_zip(src, name)


def exists(src: Path, name: str) -> bool:
    try:
        read_bytes(src, name)
        return True
    except FileNotFoundError:
        return False


def version_tuple(value: str) -> tuple[int, ...]:
    out = []
    for part in str(value or "").split("."):
        digits = "".join(
            ch
            for ch in part
            if ch.isdigit()
        )
        if not digits:
            break
        out.append(int(digits))
    return tuple(out)


def main(raw: str) -> int:
    src = Path(raw)
    if not src.exists():
        raise SystemExit(
            f"not found: {src}"
        )

    manifest = json.loads(
        read_bytes(
            src,
            "session.json",
        ).decode("utf-8")
    )

    app_version = str(
        manifest.get(
            "app_version",
            "0.0.0",
        )
    )

    required = set(BASE_REQUIRED)
    if version_tuple(app_version) >= (0, 7, 0):
        required |= R4_REQUIRED_V07

    missing = sorted(
        name
        for name in required
        if not exists(src, name)
    )

    poses_text = read_bytes(
        src,
        "pose.csv",
    ).decode("utf-8")
    poses = list(
        csv.DictReader(
            io.StringIO(
                poses_text
            )
        )
    )

    qr_lines = (
        read_bytes(
            src,
            "qr_events.jsonl",
        )
        .decode("utf-8")
        .splitlines()
    )
    qr_events = [
        json.loads(line)
        for line in qr_lines
        if line.strip()
    ]
    valid = [
        x
        for x in qr_events
        if x.get(
            "valid_mycon_anchor"
        )
    ]

    tracking = sum(
        1
        for row in poses
        if row.get(
            "tracking"
        ) == "TRACKING"
    )

    anchors = sorted(
        {
            f"{x.get('project')}/{x.get('anchor')}"
            for x in valid
            if x.get("project")
            and x.get("anchor")
        }
    )

    projects = sorted(
        {
            x.get("project")
            for x in valid
            if x.get("project")
        }
    )

    solved_controls = sorted(
        {
            f"{x.get('project')}/{x.get('anchor')}"
            for x in valid
            if x.get(
                "marker_pose_arcore_tx_ty_tz_qx_qy_qz_qw"
            )
            and x.get("project")
            and x.get("anchor")
        }
    )

    r4 = None
    if exists(
        src,
        "r4_compatibility.json",
    ):
        r4 = json.loads(
            read_bytes(
                src,
                "r4_compatibility.json",
            ).decode("utf-8")
        )

    tracking_ratio = (
        tracking /
        max(
            1,
            len(poses),
        )
    )

    report = {
        "ok":
            not missing
            and manifest.get(
                "format"
            ) ==
            "MYCON_CAPTURE_SESSION"
            and manifest.get(
                "format_version"
            ) == 1,
        "missing_files":
            missing,
        "format":
            manifest.get("format"),
        "format_version":
            manifest.get(
                "format_version"
            ),
        "app_version":
            app_version,
        "frames":
            len(poses),
        "tracking_frames":
            tracking,
        "tracking_ratio":
            tracking_ratio,
        "qr_events":
            len(qr_events),
        "valid_control_events":
            len(valid),
        "unique_controls":
            len(anchors),
        "solved_metric_controls":
            len(solved_controls),
        "projects":
            projects,
        "anchors":
            anchors,
        "single_project":
            len(projects) <= 1,
        "mp4_exists":
            exists(
                src,
                manifest.get(
                    "video_dataset",
                    "arcore_recording.mp4",
                ),
            ),
        "r4_sidecars":
            r4 is not None,
        "r4_stage8_capture_ready":
            len(
                solved_controls
            ) >= 4,
        "r4_holdout_capture_ready":
            len(
                solved_controls
            ) >= 7,
    }

    print(
        json.dumps(
            report,
            ensure_ascii=False,
            indent=2,
        )
    )
    return (
        0
        if report["ok"]
        else 2
    )


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(
            "usage: mycon_session_check.py SESSION_DIR_OR_ZIP"
        )
    raise SystemExit(
        main(
            sys.argv[1]
        )
    )
