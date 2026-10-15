#!/usr/bin/env python3
"""Non-destructive emergency MyCON backup (PC + Android USB debugging).

Does not install, uninstall, modify or delete phone app files.
Writes backup into a NEW local directory and verifies the ZIP CRC.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
import time
import zipfile

PACKAGE = "ir.mycon.capture"
REMOTE = f"/sdcard/Android/data/{PACKAGE}/files/sessions"


def adb(*args, **kwargs):
    return subprocess.run(["adb", *args], **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="MYCON_EMERGENCY_BACKUP", help="New local backup folder")
    parser.add_argument("--serial", help="ADB device serial (optional)")
    args = parser.parse_args()
    target = pathlib.Path(args.output).absolute()
    if target.exists() and any(target.iterdir()):
        parser.error("Backup folder must be empty; refusing to overwrite existing files.")
    target.mkdir(parents=True, exist_ok=True)
    prefix = ["-s", args.serial] if args.serial else []
    try:
        devices = adb(*prefix, "get-state", text=True, capture_output=True)
    except FileNotFoundError:
        parser.error("adb not installed. Install official Android platform-tools first.")
    if devices.returncode or devices.stdout.strip() != "device":
        parser.error("No authorized Android device. Enable USB debugging and approve your PC.")

    print("Reading MyCON's app-specific sessions without modifying the phone…")
    result = adb(*prefix, "pull", REMOTE, str(target / "sessions"))
    raw = target / "sessions"
    files = [p for p in raw.rglob("*") if p.is_file()] if raw.exists() else []
    if result.returncode != 0 or not files:
        # run-as may work for debug builds; use a raw tar file instead of
        # riskily unpacking archives from device stdout.
        print("Direct adb pull unavailable. Trying debug app run-as streaming…")
        path = target / "MYCON_sessions_raw.tar"
        with path.open("wb") as output:
            proc = adb(*prefix, "exec-out", "run-as", PACKAGE, "tar", "-cf", "-",
                       "-C", f"/sdcard/Android/data/{PACKAGE}/files", "sessions",
                       stdout=output, stderr=subprocess.PIPE)
        if proc.returncode != 0 or path.stat().st_size < 1024:
            path.unlink(missing_ok=True)
            print("Backup blocked by device permissions. DO NOT uninstall the app.")
            print("ADB error:", proc.stderr.decode("utf-8", "replace")[-600:])
            return 2
        print(f"Saved raw TAR: {path} ({path.stat().st_size:,} bytes)")
        print("Preserve this TAR unchanged; inspect locally before any app update.")
        return 0

    total = sum(p.stat().st_size for p in files)
    if total == 0:
        print("Nothing copied. DO NOT uninstall MyCON.")
        return 2
    zip_path = target / f"MYCON_BACKUP_{int(time.time())}.zip"
    digest = {}
    with zipfile.ZipFile(zip_path, "w", allowZip64=True) as z:
        for file in sorted(files):
            rel = file.relative_to(raw).as_posix()
            sha = hashlib.sha256()
            with file.open("rb") as stream:
                for part in iter(lambda: stream.read(1024 * 1024), b""):
                    sha.update(part)
            digest[rel] = {"bytes": file.stat().st_size, "sha256": sha.hexdigest()}
            # Already compressed MP4 benefits little from DEFLATE.
            z.write(file, rel, compress_type=zipfile.ZIP_STORED)
    with zipfile.ZipFile(zip_path) as z:
        failed = z.testzip()
        if failed:
            raise RuntimeError(f"CRC failure in {failed}; do not trust backup")
        if set(z.namelist()) != set(digest):
            raise RuntimeError("Backup file list mismatch")
    (target / "MANIFEST_SHA256.json").write_text(
        json.dumps(digest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"Copied {len(files)} files, {total:,} bytes. ZIP CRC verified.")
    print(f"Backup: {zip_path}")
    print("Only after checking this copy should you consider any device changes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
