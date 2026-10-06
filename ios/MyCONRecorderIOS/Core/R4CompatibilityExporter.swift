import Foundation
import CryptoKit

enum R4CompatibilityExporter {
    static func write(sessionDir: URL) throws {
        let sessionURL = sessionDir.appendingPathComponent("session.json")
        let poseURL = sessionDir.appendingPathComponent("pose.csv")
        let qrURL = sessionDir.appendingPathComponent("qr_events.jsonl")

        let manifest = try jsonObject(sessionURL)

        var firstPose: [String]? = nil
        if let poseText = try? String(contentsOf: poseURL, encoding: .utf8) {
            let lines = poseText.split(whereSeparator: \.isNewline)
            if lines.count > 1 {
                firstPose = String(lines[1])
                    .split(separator: ",", omittingEmptySubsequences: false)
                    .map { String($0) }
            }
        }

        var camera: [String: Any] = [
            "schema": "mycon.r4.camera.v1",
            "camera_selection": manifest["camera_selection"] ?? NSNull(),
            "capture_controls": manifest["capture_controls"] ?? NSNull()
        ]
        if let p = firstPose, p.count >= 16 {
            var intrinsics: [String: Any] = [:]
            intrinsics["fx"] = Double(p[10]) ?? NSNull()
            intrinsics["fy"] = Double(p[11]) ?? NSNull()
            intrinsics["cx"] = Double(p[12]) ?? NSNull()
            intrinsics["cy"] = Double(p[13]) ?? NSNull()
            intrinsics["width"] = Int(p[14]) ?? NSNull()
            intrinsics["height"] = Int(p[15]) ?? NSNull()
            camera["first_frame_intrinsics"] = intrinsics
        }
        try writeJSON(camera, to: sessionDir.appendingPathComponent("r4_camera.json"))

        var best: [String: [String: Any]] = [:]
        if let text = try? String(contentsOf: qrURL, encoding: .utf8) {
            for line in text.split(whereSeparator: \.isNewline) {
                guard
                    let data = String(line).data(using: .utf8),
                    let row = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                    (row["valid_mycon_anchor"] as? Bool) == true,
                    let project = row["project"] as? String, !project.isEmpty,
                    let anchor = row["anchor"] as? String, !anchor.isEmpty,
                    let pose = row["marker_pose_arcore_tx_ty_tz_qx_qy_qz_qw"] as? [Any], pose.count >= 7
                else { continue }
                let key = project + "/" + anchor
                let err = number(row["marker_reprojection_error_px"]) ?? .infinity
                let old = best[key].flatMap { number($0["marker_reprojection_error_px"]) } ?? .infinity
                if best[key] == nil || err < old { best[key] = row }
            }
        }

        var controls: [[String: Any]] = []
        for key in best.keys.sorted() {
            guard let row = best[key] else { continue }
            let markerPose = row["marker_pose_arcore_tx_ty_tz_qx_qy_qz_qw"] as? [Any]
            let reproj = number(row["marker_reprojection_error_px"])
            controls.append([
                "id": key,
                "project": row["project"] ?? "",
                "anchor": row["anchor"] ?? "",
                "crs": row["crs"] ?? "",
                "target_project_xyz_m": row["anchor_xyz_m"] ?? NSNull(),
                "source_arcore_xyz_m": markerPose.map { Array($0.prefix(3)) } ?? NSNull(),
                "marker_pose_arcore": markerPose ?? NSNull(),
                "reprojection_error_px": reproj?.isFinite == true ? reproj! : NSNull(),
                "timestamp_ns": row["timestamp_ns"] ?? 0
            ])
        }

        try writeJSON([
            "schema": "mycon.r4.capture_controls.v1",
            "selection": "best_reprojection_observation_per_control",
            "controls": controls
        ], to: sessionDir.appendingPathComponent("r4_controls.json"))

        let controlCount = controls.count
        let video = (manifest["video_dataset"] as? String) ?? "arcore_recording.mp4"
        let compatibility: [String: Any] = [
            "schema": "mycon.r4.capture_compatibility.v1",
            "app_version": "1.0.0-ios",
            "pipeline_family": "MyCon R4 v4.8.x",
            "preserve_scientific_order": true,
            "files": [
                "video": video,
                "pose_trace": "pose.csv",
                "imu": "imu.csv",
                "qr_events": "qr_events.jsonl",
                "camera": "r4_camera.json",
                "controls": "r4_controls.json",
                "bridge_tool": "tools/mycon_r4_bridge.py"
            ],
            "stage2_contract": [
                "keyframe_name_pattern": "frame_%08d.jpg",
                "source": "source_video_frame_number",
                "do_not_rename": true
            ],
            "stage4_pose_validator": [
                "supported": true,
                "generated_after_stage2": "pose_validator.json",
                "minimum_common_frames": 6,
                "policy": "VALIDATE_ONLY_NEVER_AVERAGE"
            ],
            "stage8_metric_alignment": [
                "supported": true,
                "generated_after_colmap": "stage8_anchors.json",
                "captured_unique_controls": controlCount,
                "minimum_fit_controls": 4,
                "recommended_holdout_controls": 3,
                "capture_ready": controlCount >= 4
            ],
            "colmap": [
                "matching": "SEQUENTIAL",
                "single_camera_group_per_session": true,
                "share_intrinsics_within_session": true,
                "camera_model_policy": "DO_NOT_FORCE; preserve MyCon R4 model-hypothesis selection",
                "pose_priors": "OPTIONAL_POSITION_PRIORS_ONLY",
                "pose_priors_default_sigma_m": 0.10
            ],
            "notes": [
                "ARKit pose is a metric session-local prior, never survey ground truth.",
                "Surveyed MYCON QR coordinates are the metric/project reference.",
                "Bundle adjustment and MyCon Stage-4 validation remain authoritative."
            ]
        ]
        try writeJSON(compatibility, to: sessionDir.appendingPathComponent("r4_compatibility.json"))

        let toolDir = sessionDir.appendingPathComponent("tools", isDirectory: true)
        try FileManager.default.createDirectory(at: toolDir, withIntermediateDirectories: true)
        if let bundled = Bundle.main.url(forResource: "mycon_r4_bridge", withExtension: "py") {
            let target = toolDir.appendingPathComponent("mycon_r4_bridge.py")
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.copyItem(at: bundled, to: target)
        }

        let readme = """
        MYCON RECORDER iOS -> MYCON R4

        1) Use arcore_recording.mp4 as the normal MyCon video input.
        2) Run Stage 1/2 normally. Do NOT change MyCon scientific stage order.
        3) After Stage 2, run:
           python tools/mycon_r4_bridge.py <SESSION_DIR_OR_ZIP> --stage2-report <STAGE2_REPORT_OR_KEYFRAME_DIR> --output <BRIDGE_DIR>
        4) Set POSE_VALIDATOR_JSON_INPUT to <BRIDGE_DIR>/pose_validator.json for Stage 4 validation.
        5) After sparse/refined COLMAP exists, run the bridge again with --colmap-text-model <SOURCE_TXT_OR_IMAGES_TXT>.
        6) If stage8_anchors.json reports READY, set ANCHORS_JSON_INPUT to that file for Stage 8.
        7) Optional colmap_pose_priors.json is experimental only.

        ARKit replaces ARCore only in acquisition. The external R4 contract and stage order remain unchanged.
        Stage 8 requires at least 4 calibration controls; 7+ controls allow 3 holdouts.
        """
        try readme.write(
            to: sessionDir.appendingPathComponent("R4_IMPORT_README.txt"),
            atomically: true,
            encoding: .utf8
        )

        try writeIntegrity(sessionDir: sessionDir)
    }

    private static func writeIntegrity(sessionDir: URL) throws {
        let fm = FileManager.default
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey]
        let en = fm.enumerator(
            at: sessionDir,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles]
        )
        var rows: [[String: Any]] = []
        while let url = en?.nextObject() as? URL {
            guard url.lastPathComponent != "integrity_sha256.json" else { continue }
            let values = try? url.resourceValues(forKeys: Set(keys))
            guard values?.isRegularFile == true else { continue }
            let relative = url.path.replacingOccurrences(of: sessionDir.path + "/", with: "")
            let ext = url.pathExtension.lowercased()
            let deferredBinary = ["mp4", "d16", "u8", "f32"].contains(ext)
            rows.append([
                "path": relative,
                "size_bytes": values?.fileSize ?? 0,
                "sha256": deferredBinary ? NSNull() : sha256(url),
                "hash_policy": deferredBinary ? "DEFERRED_LARGE_BINARY" : "SHA256"
            ])
        }
        rows.sort { ($0["path"] as? String ?? "") < ($1["path"] as? String ?? "") }
        try writeJSON([
            "schema": "mycon.capture.integrity.v1",
            "files": rows
        ], to: sessionDir.appendingPathComponent("integrity_sha256.json"))
    }

    private static func jsonObject(_ url: URL) throws -> [String: Any] {
        let data = try Data(contentsOf: url)
        return try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
    }

    private static func writeJSON(_ value: Any, to url: URL) throws {
        let data = try JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: url, options: .atomic)
    }

    private static func number(_ value: Any?) -> Double? {
        if let n = value as? NSNumber { return n.doubleValue }
        if let d = value as? Double { return d }
        if let f = value as? Float { return Double(f) }
        return nil
    }

    private static func sha256(_ url: URL) -> String {
        guard let data = try? Data(contentsOf: url) else { return "" }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
