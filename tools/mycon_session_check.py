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


REQUIRED = {
    "session.json",
    "pose.csv",
    "imu.csv",
    "qr_events.jsonl",
    "arcore_recording.mp4",
}


def read_from_zip(path: Path, name: str) -> bytes:
    with zipfile.ZipFile(path) as zf:
        candidates = [n for n in zf.namelist() if n.rstrip("/").endswith(name)]
        if not candidates:
            raise FileNotFoundError(name)
        if len(candidates) > 1:
            exact = [n for n in candidates if n == name]
            if len(exact) == 1:
                return zf.read(exact[0])
            raise RuntimeError(f"ambiguous {name}: {candidates}")
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


def main(raw: str) -> int:
    src = Path(raw)
    if not src.exists():
        raise SystemExit(f"not found: {src}")

    missing = sorted(name for name in REQUIRED if not exists(src, name))

    manifest = json.loads(read_bytes(src, "session.json").decode("utf-8"))
    poses_text = read_bytes(src, "pose.csv").decode("utf-8")
    poses = list(csv.DictReader(io.StringIO(poses_text)))

    qr_lines = (
        read_bytes(src, "qr_events.jsonl")
        .decode("utf-8")
        .splitlines()
    )
    qr_events = [json.loads(line) for line in qr_lines if line.strip()]
    valid = [x for x in qr_events if x.get("valid_mycon_anchor")]

    tracking = sum(
        1 for row in poses if row.get("tracking") == "TRACKING"
    )
    anchors = sorted(
        {
            f"{x.get('project')}/{x.get('anchor')}"
            for x in valid
            if x.get("project") and x.get("anchor")
        }
    )
    projects = sorted(
        {
            x.get("project")
            for x in valid
            if x.get("project")
        }
    )

    report = {
        "ok": not missing
        and manifest.get("format") == "MYCON_CAPTURE_SESSION"
        and manifest.get("format_version") == 1,
        "missing_files": missing,
        "format": manifest.get("format"),
        "format_version": manifest.get("format_version"),
        "frames": len(poses),
        "tracking_frames": tracking,
        "tracking_ratio": tracking / max(1, len(poses)),
        "qr_events": len(qr_events),
        "valid_control_events": len(valid),
        "projects": projects,
        "anchors": anchors,
        "single_project": len(projects) <= 1,
        "mp4_exists": exists(
            src,
            manifest.get("video_dataset", "arcore_recording.mp4"),
        ),
    }

    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ok"] else 2


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(
            "usage: mycon_session_check.py SESSION_DIR_OR_ZIP"
        )
    raise SystemExit(main(sys.argv[1]))
