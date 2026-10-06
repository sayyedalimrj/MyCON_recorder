#!/usr/bin/env python3
"""MyCON Recorder -> MyCon R4 / COLMAP bridge.

Outputs:
  pose_validator.json       exact optional Stage-4 validator consumed by MyCon R4
  colmap_pose_priors.json   optional position-prior interchange, not auto-injected
  frame_sync.csv            Stage-2 keyframe <-> ARCore timestamp mapping
  controls_capture.json     best surveyed QR observations
  stage8_anchors.json       generated only when a COLMAP text model is supplied
  bridge_report.json        auditable readiness/QA report

This tool intentionally does NOT replace MyCon Stage 3, matching, mapping, Stage 4
validation, or bundle adjustment.
"""
from __future__ import annotations

import argparse
import csv
import itertools
import json
import math
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

FRAME_RE = re.compile(r"frame_(\d{8})\.(?:jpg|jpeg|png)$", re.I)


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def open_session(src):
    src = Path(src)
    if src.is_dir():
        return src, None
    if not zipfile.is_zipfile(src):
        raise ValueError(f"not a MyCON session directory/ZIP: {src}")
    tmp = tempfile.TemporaryDirectory(prefix="mycon_r4_")
    with zipfile.ZipFile(src) as zf:
        zf.extractall(tmp.name)
    root = Path(tmp.name)
    if not (root / "session.json").is_file():
        found = list(root.rglob("session.json"))
        if len(found) != 1:
            tmp.cleanup()
            raise FileNotFoundError("session.json not uniquely found")
        root = found[0].parent
    return root, tmp


def qxyzw_to_rot(q):
    import numpy as np
    x, y, z, w = map(float, q)
    n = math.sqrt(x*x + y*y + z*z + w*w)
    if n <= 1e-12:
        return np.eye(3)
    x, y, z, w = x/n, y/n, z/n, w/n
    return np.array([
        [1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
        [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
        [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)],
    ], dtype=float)


def qwxyz_to_rot(q):
    w, x, y, z = map(float, q)
    return qxyzw_to_rot((x, y, z, w))


def rot_to_qwxyz(R):
    import numpy as np
    R = np.asarray(R, dtype=float)
    tr = float(np.trace(R))
    if tr > 0:
        s = math.sqrt(tr + 1.0) * 2.0
        w = 0.25*s
        x = (R[2,1]-R[1,2])/s
        y = (R[0,2]-R[2,0])/s
        z = (R[1,0]-R[0,1])/s
    elif R[0,0] > R[1,1] and R[0,0] > R[2,2]:
        s = math.sqrt(max(1e-15,1+R[0,0]-R[1,1]-R[2,2]))*2
        w = (R[2,1]-R[1,2])/s
        x = 0.25*s
        y = (R[0,1]+R[1,0])/s
        z = (R[0,2]+R[2,0])/s
    elif R[1,1] > R[2,2]:
        s = math.sqrt(max(1e-15,1+R[1,1]-R[0,0]-R[2,2]))*2
        w = (R[0,2]-R[2,0])/s
        x = (R[0,1]+R[1,0])/s
        y = 0.25*s
        z = (R[1,2]+R[2,1])/s
    else:
        s = math.sqrt(max(1e-15,1+R[2,2]-R[0,0]-R[1,1]))*2
        w = (R[1,0]-R[0,1])/s
        x = (R[0,2]+R[2,0])/s
        y = (R[1,2]+R[2,1])/s
        z = 0.25*s
    n = math.sqrt(w*w+x*x+y*y+z*z)
    return [w/n,x/n,y/n,z/n]


def arcore_to_colmap_world_to_camera(qxyzw):
    # ARCore camera: +X right, +Y up, viewing direction -Z.
    # COLMAP camera: +X right, +Y down, +Z forward.
    import numpy as np
    R_world_from_ar_cam = qxyzw_to_rot(qxyzw)
    R_ar_cam_from_world = R_world_from_ar_cam.T
    convention = np.diag([1.0,-1.0,-1.0])
    return rot_to_qwxyz(convention @ R_ar_cam_from_world)


def load_poses(root):
    rows = []
    with (root/"pose.csv").open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            try:
                rows.append({
                    "frame": int(row["frame"]),
                    "timestamp_ns": int(row["timestamp_ns"]),
                    "center": [float(row["tx_m"]),float(row["ty_m"]),float(row["tz_m"])],
                    "qxyzw": [float(row["qx"]),float(row["qy"]),float(row["qz"]),float(row["qw"])],
                    "tracking": row.get("tracking",""),
                })
            except Exception:
                pass
    rows.sort(key=lambda x:x["timestamp_ns"])
    if not rows:
        raise ValueError("pose.csv contains no usable rows")
    return rows


def load_keyframes(path):
    if path is None:
        return []
    path = Path(path)
    found = {}
    if path.is_dir():
        for p in path.iterdir():
            m = FRAME_RE.match(p.name)
            if m:
                found[p.name] = int(m.group(1))
        return sorted(found.items(), key=lambda x:x[1])
    data = load_json(path)

    def add(name, frame=None):
        if not name:
            return
        name = Path(str(name)).name
        m = FRAME_RE.match(name)
        if frame is None and m:
            frame = int(m.group(1))
        if frame is not None:
            found[name] = int(frame)

    def walk(x):
        if isinstance(x, dict):
            if any(k in x for k in ("image","name","file")):
                add(
                    x.get("image") or x.get("name") or x.get("file"),
                    x.get("frame") or x.get("frame_no") or x.get("source_frame"),
                )
            for value in x.values():
                walk(value)
        elif isinstance(x, list):
            for value in x:
                walk(value)
        elif isinstance(x, str):
            add(x)
    walk(data)
    return sorted(found.items(), key=lambda x:x[1])


def probe_video_pts(video_path):
    """Return video-frame PTS seconds using ffprobe, or None if unavailable."""
    video_path = Path(video_path)
    ffprobe = shutil.which("ffprobe")
    if not ffprobe or not video_path.is_file():
        return None
    try:
        proc = subprocess.run(
            [
                ffprobe,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries",
                "frame=best_effort_timestamp_time",
                "-of", "csv=p=0",
                str(video_path),
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=120,
        )
        pts = []
        for raw in proc.stdout.splitlines():
            raw = raw.strip().split(",")[0]
            if not raw or raw == "N/A":
                continue
            try:
                pts.append(float(raw))
            except ValueError:
                pass
        return pts if len(pts) >= 2 else None
    except Exception:
        return None


def nearest_pose(poses, timestamp_ns):
    track = [p for p in poses if p["tracking"] == "TRACKING"] or poses
    lo, hi = 0, len(track)
    while lo < hi:
        mid = (lo+hi)//2
        if track[mid]["timestamp_ns"] < timestamp_ns:
            lo = mid+1
        else:
            hi = mid
    cand = [track[i] for i in (lo-1,lo) if 0 <= i < len(track)]
    return min(cand, key=lambda p:abs(p["timestamp_ns"]-timestamp_ns))


def parse_colmap_images(model):
    import numpy as np
    path = Path(model)
    if path.is_dir():
        path = path/"images.txt"
    if not path.is_file():
        raise FileNotFoundError(f"COLMAP images.txt not found: {path}")
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    result = {}
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        i += 1
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 10:
            continue
        try:
            q = list(map(float,parts[1:5]))
            t = np.array(list(map(float,parts[5:8])),dtype=float)
            name = " ".join(parts[9:])
            R = qwxyz_to_rot(q)
            result[Path(name).name] = {
                "name": name,
                "image_id": int(parts[0]),
                "camera_id": int(parts[8]),
                "center": (-R.T @ t).tolist(),
                "world_to_camera_q": q,
            }
        except Exception:
            pass
        # COLMAP images.txt normally has a second POINTS2D line.
        if i < len(lines) and not lines[i].lstrip().startswith("#"):
            i += 1
    return result


def umeyama(src, dst):
    import numpy as np
    X = np.asarray(src,float)
    Y = np.asarray(dst,float)
    mx, my = X.mean(0), Y.mean(0)
    Xc, Yc = X-mx, Y-my
    cov = Yc.T @ Xc / len(X)
    U,D,Vt = np.linalg.svd(cov)
    S = np.eye(3)
    if np.linalg.det(U@Vt) < 0:
        S[-1,-1] = -1
    R = U@S@Vt
    var = float((Xc*Xc).sum()/len(X))
    scale = float((D*np.diag(S)).sum()/var)
    t = my - scale*(R@mx)
    return scale,R,t


def robust_sim3(src, dst, threshold=0.10):
    import numpy as np
    X = np.asarray(src,float)
    Y = np.asarray(dst,float)
    if len(X) < 3:
        raise ValueError("need >=3 common frames for trajectory Sim3")
    combos = list(itertools.combinations(range(len(X)),3))
    if len(combos) > 300:
        step = max(1,len(combos)//300)
        combos = combos[::step][:300]
    best = None
    for ids in combos:
        try:
            s,R,t = umeyama(X[list(ids)],Y[list(ids)])
        except Exception:
            continue
        pred = (s*(R@X.T)).T+t
        err = np.linalg.norm(pred-Y,axis=1)
        mask = err <= threshold
        score = (
            int(mask.sum()),
            -float(np.median(err[mask])) if mask.any() else -1e9,
        )
        if best is None or score > best[0]:
            best = (score,mask,s,R,t,err)
    if best is None:
        raise ValueError("trajectory Sim3 failed")
    mask = best[1]
    if int(mask.sum()) >= 3:
        s,R,t = umeyama(X[mask],Y[mask])
        pred = (s*(R@X.T)).T+t
        err = np.linalg.norm(pred-Y,axis=1)
        mask = err <= threshold
    else:
        _,_,s,R,t,err = best
    rmse = (
        float(math.sqrt(float((err[mask]**2).mean())))
        if mask.any() else float("inf")
    )
    return s,R,t,mask,err,rmse


def best_controls(root):
    path = root/"qr_events.jsonl"
    if not path.is_file():
        return []
    best = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip():
            continue
        try:
            row = json.loads(raw)
            pose = row.get("marker_pose_arcore_tx_ty_tz_qx_qy_qz_qw")
            xyz = row.get("anchor_xyz_m")
            if (
                not row.get("valid_mycon_anchor")
                or not pose or len(pose) < 7
                or not xyz or len(xyz) < 3
            ):
                continue
            key = f"{row['project']}/{row['anchor']}"
            item = {
                "id": key,
                "project": row["project"],
                "anchor": row["anchor"],
                "crs": row.get("crs"),
                "source_arcore": [float(v) for v in pose[:3]],
                "target_project": [float(v) for v in xyz[:3]],
                "reprojection_error_px": float(
                    row.get("marker_reprojection_error_px",999.0)
                ),
                "timestamp_ns": int(row.get("timestamp_ns",0)),
            }
            if (
                key not in best
                or item["reprojection_error_px"]
                < best[key]["reprojection_error_px"]
            ):
                best[key] = item
        except Exception:
            pass
    return [best[k] for k in sorted(best)]


def bridge(
    session,
    stage2_report,
    colmap_model,
    output,
    fps_override=None,
    video_offset_s=0.0,
    prior_sigma_m=0.10,
    stage_video=None,
):
    import numpy as np
    root,tmp = open_session(Path(session))
    try:
        manifest = load_json(root/"session.json")
        poses = load_poses(root)
        camera = manifest.get("camera_selection") or {}
        fps = float(
            fps_override
            or manifest.get("observed_frame_rate_fps")
            or camera.get("fps_max")
            or 30.0
        )
        if not math.isfinite(fps) or fps <= 0:
            fps = 30.0

        positive = [p["timestamp_ns"] for p in poses if p["timestamp_ns"] > 0]
        first_ts = (
            int(manifest.get("first_frame_timestamp_ns") or 0)
            or min(positive)
        )

        output = Path(output)
        output.mkdir(parents=True,exist_ok=True)
        keyframes = load_keyframes(stage2_report)

        # Prefer real decoded video timestamps over frame_no/fps when possible.
        # If Stage 1 uses a clipped segment, pass --stage-video and optionally
        # --video-offset-s so the clip PTS can be mapped back to the Recorder MP4.
        pts_source = None
        video_pts = None
        if stage_video is not None:
            video_pts = probe_video_pts(stage_video)
            if video_pts:
                pts_source = "STAGE_VIDEO_FFPROBE"
        elif abs(float(video_offset_s)) < 1e-12:
            recorder_video = root / manifest.get(
                "video_dataset",
                "arcore_recording.mp4",
            )
            video_pts = probe_video_pts(recorder_video)
            if video_pts:
                pts_source = "RECORDER_MP4_FFPROBE"

        sync_method = (
            pts_source
            if video_pts
            else "FRAME_NUMBER_DIVIDED_BY_FPS"
        )

        validator_poses = {}
        sync = []
        for name,frame_no in keyframes:
            if (
                video_pts is not None
                and 0 <= frame_no < len(video_pts)
            ):
                relative_s = (
                    float(video_offset_s)
                    + float(video_pts[frame_no])
                )
            else:
                relative_s = (
                    float(video_offset_s)
                    + frame_no / fps
                )
            wanted = first_ts + int(
                relative_s * 1e9
            )
            p = nearest_pose(poses,wanted)
            dt_ms = abs(p["timestamp_ns"]-wanted)/1e6
            validator_poses[name] = {
                "center": p["center"],
                "world_to_camera_q":
                    arcore_to_colmap_world_to_camera(p["qxyzw"]),
                "timestamp_ns": p["timestamp_ns"],
                "source_video_frame": frame_no,
                "time_error_ms": dt_ms,
                "tracking": p["tracking"],
            }
            sync.append({
                "image":name,
                "frame":frame_no,
                "pose_frame":p["frame"],
                "timestamp_ns":p["timestamp_ns"],
                "time_error_ms":dt_ms,
            })

        pose_validator = {
            "schema":"mycon.r4.pose_validator.arcore.v1",
            "status":
                "COMPLETE"
                if len(validator_poses) >= 6
                else "INSUFFICIENT_COMMON_FRAMES",
            "engine":"ARCore MyCON Recorder",
            "policy":"VALIDATE_ONLY_NEVER_AVERAGE",
            "coordinate_frame":"ARCORE_SESSION_LOCAL_METRIC",
            "poses":validator_poses,
            "source":{
                "fps_used":fps,
                "video_offset_s":float(video_offset_s),
                "sync_method":sync_method,
                "stage_video":
                    str(stage_video)
                    if stage_video is not None
                    else None,
            },
        }
        (output/"pose_validator.json").write_text(
            json.dumps(pose_validator,indent=2),
            encoding="utf-8",
        )

        covariance = prior_sigma_m**2
        pose_priors = {
            "schema":"mycon.colmap.pose_priors.v1",
            "coordinate_system":"CARTESIAN_ARCORE_LOCAL_METRIC",
            "default_position_sigma_m":prior_sigma_m,
            "policy":
                "OPTIONAL_ONLY; DO_NOT_REPLACE_DEFAULT_MYCON_STAGE3",
            "poses":{
                name:{
                    "position":row["center"],
                    "position_covariance":[
                        [covariance,0,0],
                        [0,covariance,0],
                        [0,0,covariance],
                    ],
                    "timestamp_ns":row["timestamp_ns"],
                }
                for name,row in validator_poses.items()
            },
        }
        (output/"colmap_pose_priors.json").write_text(
            json.dumps(pose_priors,indent=2),
            encoding="utf-8",
        )

        with (output/"frame_sync.csv").open(
            "w",
            newline="",
            encoding="utf-8",
        ) as f:
            writer = csv.DictWriter(
                f,
                fieldnames=[
                    "image",
                    "frame",
                    "pose_frame",
                    "timestamp_ns",
                    "time_error_ms",
                ],
            )
            writer.writeheader()
            writer.writerows(sync)

        controls = best_controls(root)
        (output/"controls_capture.json").write_text(
            json.dumps(
                {
                    "schema":
                        "mycon.controls.capture.v1",
                    "controls":controls,
                },
                indent=2,
            ),
            encoding="utf-8",
        )

        sim3_report = None
        stage8 = None
        if colmap_model and validator_poses:
            colmap = parse_colmap_images(colmap_model)
            common = [
                name
                for name in validator_poses
                if name in colmap
            ]
            if len(common) >= 3:
                src = [
                    validator_poses[n]["center"]
                    for n in common
                ]
                dst = [
                    colmap[n]["center"]
                    for n in common
                ]
                s,R,t,mask,err,rmse = robust_sim3(
                    src,
                    dst,
                    0.10,
                )
                sim3_report = {
                    "schema":
                        "mycon.arcore_to_colmap.sim3.v1",
                    "scale":s,
                    "rotation":R.tolist(),
                    "translation":t.tolist(),
                    "common_frames":len(common),
                    "inlier_frames":int(mask.sum()),
                    "rmse_m":rmse,
                    "max_error_m":
                        float(max(err))
                        if len(err) else None,
                    "threshold_m":0.10,
                }
                (output/"arcore_to_colmap_sim3.json").write_text(
                    json.dumps(
                        sim3_report,
                        indent=2,
                    ),
                    encoding="utf-8",
                )

                if controls:
                    source = np.asarray(
                        [
                            c["source_arcore"]
                            for c in controls
                        ],
                        dtype=float,
                    )
                    source_colmap = (
                        s*(R@source.T)
                    ).T+t
                    target = [
                        c["target_project"]
                        for c in controls
                    ]
                    ids = [
                        c["id"]
                        for c in controls
                    ]

                    # Current MyCon Stage-8 metric gate needs >=4 fit anchors.
                    # Reserve 3 independent holdouts only when >=7 exist.
                    if len(controls) >= 7:
                        fit_n = len(controls) - 3
                    else:
                        fit_n = len(controls)

                    stage8 = {
                        "schema":
                            "mycon.r4.stage8_anchors.bridge.v1",
                        "status":
                            "READY"
                            if fit_n >= 4
                            else "INSUFFICIENT_CONTROLS",
                        "source":
                            source_colmap[
                                :fit_n
                            ].tolist(),
                        "target":
                            target[:fit_n],
                        "control_ids":
                            ids[:fit_n],
                        "crs":
                            controls[0].get(
                                "crs"
                            ),
                        "provenance":
                            "Surveyed QR centers transformed from ARCore-local to COLMAP source coordinates by robust camera-trajectory Sim3.",
                    }
                    if len(controls)-fit_n >= 3:
                        stage8[
                            "holdout_source"
                        ] = source_colmap[
                            fit_n:
                        ].tolist()
                        stage8[
                            "holdout_target"
                        ] = target[
                            fit_n:
                        ]
                        stage8[
                            "holdout_control_ids"
                        ] = ids[
                            fit_n:
                        ]

                    (output/"stage8_anchors.json").write_text(
                        json.dumps(
                            stage8,
                            indent=2,
                        ),
                        encoding="utf-8",
                    )

        readiness = {
            "stage1_video":
                (root/manifest.get(
                    "video_dataset",
                    "arcore_recording.mp4",
                )).is_file(),
            "stage4_pose_validator_ready":
                len(validator_poses) >= 6,
            "stage4_common_keyframes":
                len(validator_poses),
            "stage8_metric_anchor_ready":
                bool(
                    stage8
                    and stage8.get(
                        "status"
                    ) == "READY"
                ),
            "unique_metric_controls":
                len(controls),
            "optional_colmap_position_priors":
                bool(validator_poses),
        }

        report = {
            "schema":"mycon.r4.bridge_report.v1",
            "fps_used":fps,
            "video_offset_s":
                float(video_offset_s),
            "sync_method":
                sync_method,
            "video_pts_count":
                len(video_pts)
                if video_pts is not None
                else 0,
            "keyframes_requested":
                len(keyframes),
            "keyframes_mapped":
                len(validator_poses),
            "mean_sync_error_ms":
                (
                    sum(
                        x["time_error_ms"]
                        for x in sync
                    )/len(sync)
                    if sync else None
                ),
            "max_sync_error_ms":
                max(
                    (
                        x["time_error_ms"]
                        for x in sync
                    ),
                    default=None,
                ),
            "readiness":readiness,
            "sim3":sim3_report,
            "outputs":{
                "pose_validator":
                    "pose_validator.json",
                "pose_priors":
                    "colmap_pose_priors.json",
                "frame_sync":
                    "frame_sync.csv",
                "controls":
                    "controls_capture.json",
                "stage8_anchors":
                    "stage8_anchors.json"
                    if stage8 else None,
            },
        }
        (output/"bridge_report.json").write_text(
            json.dumps(report,indent=2),
            encoding="utf-8",
        )
        return report
    finally:
        if tmp:
            tmp.cleanup()


def self_test():
    tmp = Path(
        tempfile.mkdtemp(
            prefix="mycon_bridge_test_"
        )
    )
    try:
        session = tmp/"session"
        session.mkdir()
        fps = 30.0
        first = 1_000_000_000
        (session/"session.json").write_text(
            json.dumps({
                "format":
                    "MYCON_CAPTURE_SESSION",
                "format_version":1,
                "observed_frame_rate_fps":
                    fps,
                "first_frame_timestamp_ns":
                    first,
                "video_dataset":
                    "arcore_recording.mp4",
            }),
            encoding="utf-8",
        )
        (session/"arcore_recording.mp4").write_bytes(
            b"test"
        )
        with (session/"pose.csv").open(
            "w",
            newline="",
            encoding="utf-8",
        ) as f:
            writer = csv.writer(f)
            writer.writerow([
                "frame",
                "timestamp_ns",
                "tx_m",
                "ty_m",
                "tz_m",
                "qx",
                "qy",
                "qz",
                "qw",
                "tracking",
            ])
            for i in range(40):
                writer.writerow([
                    i+1,
                    first+int(i/fps*1e9),
                    i*0.02,
                    0,
                    0,
                    0,
                    0,
                    0,
                    1,
                    "TRACKING",
                ])
        (session/"qr_events.jsonl").write_text(
            "",
            encoding="utf-8",
        )
        stage2 = tmp/"stage2.json"
        stage2.write_text(
            json.dumps({
                "frames":[
                    {
                        "image":
                            f"frame_{i:08d}.jpg",
                        "frame":i,
                    }
                    for i in range(
                        0,
                        30,
                        3
                    )
                ]
            }),
            encoding="utf-8",
        )

        result = bridge(
            session,
            stage2,
            None,
            tmp/"out",
        )
        assert result[
            "keyframes_mapped"
        ] == 10
        validator = load_json(
            tmp/"out"/
            "pose_validator.json"
        )
        assert len(
            validator["poses"]
        ) == 10
        print(
            json.dumps({
                "ok":True,
                "mapped":10,
            })
        )
        return 0
    finally:
        shutil.rmtree(
            tmp,
            ignore_errors=True,
        )


def main(argv=None):
    parser = argparse.ArgumentParser(
        description=__doc__
    )
    parser.add_argument(
        "session",
        nargs="?",
        type=Path,
    )
    parser.add_argument(
        "--stage2-report",
        type=Path,
        help=
            "MyCon Stage-2 report JSON or keyframe directory",
    )
    parser.add_argument(
        "--colmap-text-model",
        type=Path,
        help=
            "COLMAP text model dir or images.txt",
    )
    parser.add_argument(
        "--stage-video",
        type=Path,
        help=
            "Exact Stage-1/2 video or clip for ffprobe frame PTS synchronization",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(
            "mycon_r4_bridge"
        ),
    )
    parser.add_argument(
        "--fps",
        type=float,
        default=None,
    )
    parser.add_argument(
        "--video-offset-s",
        type=float,
        default=0.0,
        help=
            "Stage-1 clip start time within original recorder video",
    )
    parser.add_argument(
        "--prior-sigma-m",
        type=float,
        default=0.10,
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
    )
    args = parser.parse_args(
        argv
    )

    if args.self_test:
        return self_test()

    if args.session is None:
        parser.error(
            "session is required"
        )

    result = bridge(
        args.session,
        args.stage2_report,
        args.colmap_text_model,
        args.output,
        args.fps,
        args.video_offset_s,
        args.prior_sigma_m,
        args.stage_video,
    )
    print(
        json.dumps(
            result,
            ensure_ascii=False,
            indent=2,
        )
    )
    return (
        0
        if result["readiness"][
            "stage1_video"
        ]
        else 2
    )


if __name__ == "__main__":
    raise SystemExit(main())
