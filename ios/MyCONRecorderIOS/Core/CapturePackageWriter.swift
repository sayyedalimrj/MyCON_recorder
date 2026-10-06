import Foundation
import ARKit
import CoreLocation
import CryptoKit
import ZIPFoundation

final class CapturePackageWriter {
    let sessionDir: URL
    let videoURL: URL
    private let poseHandle: FileHandle
    private let imuHandle: FileHandle
    private let qrHandle: FileHandle
    private let lock = NSLock()

    private var frameCount: Int64 = 0
    private var trackingCount: Int64 = 0
    private var validQRCount: Int64 = 0
    private var firstFrameNs: Int64 = 0
    private var lastFrameNs: Int64 = 0
    private var lastFrameTimestamp: TimeInterval?
    private var projects = Set<String>()
    private var anchors = Set<String>()
    private var bestControls: [String: [String: Any]] = [:]
    private let startedUTC = ISO8601DateFormatter().string(from: Date())

    init() throws {
        let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MyCON Sessions", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let stamp = Int(Date().timeIntervalSince1970)
        sessionDir = root.appendingPathComponent("MYCON_IOS_\(stamp)", isDirectory: true)
        try FileManager.default.createDirectory(at: sessionDir, withIntermediateDirectories: true)

        videoURL = sessionDir.appendingPathComponent("arcore_recording.mp4")
        let pose = sessionDir.appendingPathComponent("pose.csv")
        let imu = sessionDir.appendingPathComponent("imu.csv")
        let qr = sessionDir.appendingPathComponent("qr_events.jsonl")
        FileManager.default.createFile(atPath: pose.path, contents: Data())
        FileManager.default.createFile(atPath: imu.path, contents: Data())
        FileManager.default.createFile(atPath: qr.path, contents: Data())
        poseHandle = try FileHandle(forWritingTo: pose)
        imuHandle = try FileHandle(forWritingTo: imu)
        qrHandle = try FileHandle(forWritingTo: qr)
        write(poseHandle, "frame_index,timestamp_ns,tx_m,ty_m,tz_m,qx,qy,qz,qw,tracking_state,fx_px,fy_px,cx_px,cy_px,image_width_px,image_height_px,exposure_time_ns,iso,frame_duration_ns,rolling_shutter_skew_ns,gps_lat,gps_lon,gps_alt_m,gps_accuracy_m,gps_bearing_deg,gps_speed_mps,gps_time_ms\n")
        write(imuHandle, "timestamp_ns,sensor,v0,v1,v2,v3,accuracy\n")
    }

    func recordFrame(_ frame: ARFrame, location: CLLocation?) {
        lock.lock(); defer { lock.unlock() }
        frameCount += 1
        let tsNs = Int64(frame.timestamp * 1_000_000_000)
        if firstFrameNs == 0 { firstFrameNs = tsNs }
        lastFrameNs = tsNs

        let transform = frame.camera.transform
        let t = transform.columns.3
        let q = simd_quatf(transform)
        let K = frame.camera.intrinsics
        let res = frame.camera.imageResolution

        let tracking: String
        switch frame.camera.trackingState {
        case .normal:
            tracking = "TRACKING"; trackingCount += 1
        case .notAvailable:
            tracking = "NOT_AVAILABLE"
        case .limited(let reason):
            tracking = "LIMITED_\(String(describing: reason).uppercased())"
        }

        var frameDurationNs = ""
        if let previous = lastFrameTimestamp {
            frameDurationNs = String(Int64((frame.timestamp - previous) * 1_000_000_000))
        }
        lastFrameTimestamp = frame.timestamp

        let parts: [String] = [
            String(frameCount), String(tsNs),
            String(t.x), String(t.y), String(t.z),
            String(q.imag.x), String(q.imag.y), String(q.imag.z), String(q.real),
            tracking,
            String(K.columns.0.x), String(K.columns.1.y), String(K.columns.2.x), String(K.columns.2.y),
            String(Int(res.width)), String(Int(res.height)),
            "", "", frameDurationNs, "",
            location.map { String($0.coordinate.latitude) } ?? "",
            location.map { String($0.coordinate.longitude) } ?? "",
            location.map { String($0.altitude) } ?? "",
            location.map { String($0.horizontalAccuracy) } ?? "",
            location.map { String($0.course) } ?? "",
            location.map { String($0.speed) } ?? "",
            location.map { String(Int64($0.timestamp.timeIntervalSince1970 * 1000)) } ?? ""
        ]
        write(poseHandle, parts.joined(separator: ",") + "\n")
        if frameCount % 30 == 0 { try? poseHandle.synchronize() }
    }

    func recordIMU(timestamp: TimeInterval, sensor: String, values: [Double], accuracy: Int = 3) {
        lock.lock(); defer { lock.unlock() }
        var v = values.map(String.init)
        while v.count < 4 { v.append("") }
        write(imuHandle, "\(Int64(timestamp * 1_000_000_000)),\(sensor),\(v[0]),\(v[1]),\(v[2]),\(v[3]),\(accuracy)\n")
    }

    func recordQR(
        raw: String,
        parsed: AnchorPayload?,
        cornersPx: [SIMD2<Float>],
        frame: ARFrame,
        solve: MarkerSolveResult?
    ) {
        lock.lock(); defer { lock.unlock() }
        let transform = frame.camera.transform
        let t = transform.columns.3
        let q = simd_quatf(transform)
        let K = frame.camera.intrinsics
        let res = frame.camera.imageResolution
        var obj: [String: Any] = [
            "timestamp_ns": Int64(frame.timestamp * 1_000_000_000),
            "raw": raw,
            "valid_mycon_anchor": parsed != nil,
            "corners_px": cornersPx.map { [$0.x, $0.y] },
            "camera_pose_arcore_tx_ty_tz_qx_qy_qz_qw": [t.x,t.y,t.z,q.imag.x,q.imag.y,q.imag.z,q.real],
            "intrinsics_fx_fy_cx_cy": [K.columns.0.x,K.columns.1.y,K.columns.2.x,K.columns.2.y],
            "image_wh": [Int(res.width),Int(res.height)]
        ]

        if let solve {
            let mt = solve.worldFromMarker.columns.3
            let mq = simd_quatf(solve.worldFromMarker)
            obj["marker_pose_arcore_tx_ty_tz_qx_qy_qz_qw"] = [mt.x,mt.y,mt.z,mq.imag.x,mq.imag.y,mq.imag.z,mq.real]
            obj["marker_reprojection_error_px"] = solve.reprojectionErrorPx
            obj["marker_distance_m"] = solve.distanceM
        }

        if let p = parsed {
            validQRCount += 1
            projects.insert(p.project)
            anchors.insert("\(p.project)/\(p.anchor)")
            obj["project"] = p.project
            obj["anchor"] = p.anchor
            obj["crs"] = p.crs
            obj["anchor_xyz_m"] = [p.x,p.y,p.z]
            obj["mount"] = p.mount
            obj["azimuth_deg"] = p.azimuthDeg
            obj["size_mm"] = p.sizeMm
            obj["floor"] = p.floor
            obj["model_id"] = p.modelId.isEmpty ? NSNull() : p.modelId
            obj["model_size_m"] = p.modelSizeM ?? NSNull()

            if let solve, solve.reprojectionErrorPx <= 5 {
                let key = "\(p.project)/\(p.anchor)"
                let candidate: [String: Any] = [
                    "project": p.project, "anchor": p.anchor, "crs": p.crs,
                    "project_xyz_m": [p.x,p.y,p.z],
                    "size_mm": p.sizeMm,
                    "marker_pose_local_tx_ty_tz_qx_qy_qz_qw": obj["marker_pose_arcore_tx_ty_tz_qx_qy_qz_qw"]!,
                    "reprojection_error_px": solve.reprojectionErrorPx,
                    "timestamp_ns": Int64(frame.timestamp * 1_000_000_000)
                ]
                if let old = bestControls[key],
                   let oldError = old["reprojection_error_px"] as? Float,
                   oldError <= solve.reprojectionErrorPx {
                } else {
                    bestControls[key] = candidate
                }
            }
        }

        if let data = try? JSONSerialization.data(withJSONObject: obj),
           let line = String(data: data, encoding: .utf8) {
            write(qrHandle, line + "\n")
            try? qrHandle.synchronize()
        }
    }

    func finalize(projectHint: String?) throws -> URL {
        lock.lock()
        try? poseHandle.synchronize(); try? imuHandle.synchronize(); try? qrHandle.synchronize()
        try? poseHandle.close(); try? imuHandle.close(); try? qrHandle.close()

        let elapsed = max(0, lastFrameNs - firstFrameNs)
        let fps = frameCount > 1 && elapsed > 0 ? Double(frameCount - 1) * 1_000_000_000 / Double(elapsed) : 0
        let trackingRatio = frameCount > 0 ? Double(trackingCount) / Double(frameCount) : 0

        let manifest: [String: Any] = [
            "format": "MYCON_CAPTURE_SESSION",
            "format_version": 1,
            "app_version": "0.8.0-ios",
            "platform": "ios",
            "tracking_provider": "ARKit",
            "started_utc": startedUTC,
            "ended_utc": ISO8601DateFormatter().string(from: Date()),
            "project_hint": projectHint ?? NSNull(),
            "projects_seen": projects.sorted(),
            "anchors_seen": anchors.sorted(),
            "video_dataset": "arcore_recording.mp4",
            "pose_file": "pose.csv",
            "imu_file": "imu.csv",
            "qr_events_file": "qr_events.jsonl",
            "frame_count": frameCount,
            "first_frame_timestamp_ns": firstFrameNs,
            "last_frame_timestamp_ns": lastFrameNs,
            "tracking_frame_count": trackingCount,
            "tracking_ratio": trackingRatio,
            "observed_frame_rate_fps": fps,
            "valid_qr_event_count": validQRCount,
            "capture_controls": [
                "focus_mode": "ARKIT_MANAGED",
                "torch_enabled": false,
                "eis_mode": "OFF_BY_MYCON_POLICY",
                "digital_zoom": 1.0,
                "single_camera_config_locked": true
            ],
            "sfm_hints": [
                "matching": "SEQUENTIAL",
                "share_intrinsics_within_session": true,
                "use_full_resolution": true,
                "pose_prior_policy": "PRIOR_NOT_GROUND_TRUTH"
            ],
            "notes": "ARKit camera pose is metric but session-local. Surveyed MYCON QR controls align it to the project coordinate system. The legacy video filename is preserved for MyCON R4 compatibility."
        ]
        try writeJSON(manifest, name: "session.json")

        let camera: [String: Any] = [
            "schema": "MYCON_R4_CAMERA",
            "version": 1,
            "platform": "ios",
            "provider": "ARKit",
            "pose_convention": "camera_in_world_tx_ty_tz_qx_qy_qz_qw",
            "intrinsics_source": "ARFrame.camera.intrinsics",
            "video_file": "arcore_recording.mp4",
            "pose_file": "pose.csv"
        ]
        try writeJSON(camera, name: "r4_camera.json")

        let controls: [String: Any] = [
            "schema": "MYCON_R4_CONTROLS",
            "version": 1,
            "controls": Array(bestControls.values)
        ]
        try writeJSON(controls, name: "r4_controls.json")

        let compat: [String: Any] = [
            "schema": "MYCON_R4_COMPATIBILITY",
            "version": 1,
            "capture_format": "MYCON_CAPTURE_SESSION",
            "capture_format_version": 1,
            "stage2_image_pattern": "frame_%08d.jpg",
            "video_file": "arcore_recording.mp4",
            "pose_file": "pose.csv",
            "qr_events_file": "qr_events.jsonl",
            "platform_note": "ARKit replaces ARCore only at acquisition. Downstream Stage 1-8 contract remains unchanged."
        ]
        try writeJSON(compat, name: "r4_compatibility.json")

        let readme = """
        MyCON Recorder iOS package
        ==========================
        Use arcore_recording.mp4 as the normal MyCON R4 video input.
        The filename is intentionally preserved for compatibility.
        pose.csv contains ARKit session-local metric camera poses.
        QR controls use the same mycon://anchor/v1 schema as Android.
        ARKit poses are priors/validation evidence, not final geometry.
        """
        try readme.write(to: sessionDir.appendingPathComponent("R4_IMPORT_README.txt"), atomically: true, encoding: .utf8)

        let integrityNames = ["pose.csv","imu.csv","qr_events.jsonl","session.json","r4_camera.json","r4_controls.json","r4_compatibility.json"]
        var hashes: [String: Any] = [:]
        for name in integrityNames {
            let url = sessionDir.appendingPathComponent(name)
            if let data = try? Data(contentsOf: url) {
                hashes[name] = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            }
        }
        hashes["arcore_recording.mp4"] = "DEFERRED_LARGE_MEDIA"
        try writeJSON(hashes, name: "integrity_sha256.json")
        lock.unlock()

        let zipURL = sessionDir.deletingLastPathComponent()
            .appendingPathComponent(sessionDir.lastPathComponent + "_MYCON.zip")
        try? FileManager.default.removeItem(at: zipURL)
        try FileManager.default.zipItem(at: sessionDir, to: zipURL, shouldKeepParent: false, compressionMethod: .deflate)
        return zipURL
    }

    private func writeJSON(_ object: Any, name: String) throws {
        let data = try JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: sessionDir.appendingPathComponent(name), options: .atomic)
    }

    private func write(_ handle: FileHandle, _ string: String) {
        if let data = string.data(using: .utf8) { try? handle.write(contentsOf: data) }
    }
}
