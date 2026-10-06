import Foundation
import ARKit
import CoreVideo

final class DepthRecorder {
    private let depthDir: URL
    private let indexHandle: FileHandle
    private let lock = NSLock()
    private var lastSampleTimestamp: TimeInterval = -1
    private(set) var sampleCount: Int = 0
    let supported: Bool

    init(sessionDir: URL) throws {
        depthDir = sessionDir.appendingPathComponent("depth", isDirectory: true)
        try FileManager.default.createDirectory(at: depthDir, withIntermediateDirectories: true)
        let index = depthDir.appendingPathComponent("depth_index.jsonl")
        FileManager.default.createFile(atPath: index.path, contents: Data())
        indexHandle = try FileHandle(forWritingTo: index)
        supported = ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)
    }

    func record(_ frame: ARFrame) {
        guard supported else { return }
        lock.lock(); defer { lock.unlock() }
        guard frame.timestamp - lastSampleTimestamp >= 0.20 else { return }
        guard let depth = frame.sceneDepth ?? frame.smoothedSceneDepth else { return }

        lastSampleTimestamp = frame.timestamp
        sampleCount += 1
        let stem = String(format: "depth_%06d", sampleCount)
        let depthURL = depthDir.appendingPathComponent(stem + ".f32")
        let confURL = depthDir.appendingPathComponent(stem + ".u8")

        guard let depthInfo = writePixelBuffer(
            depth.depthMap,
            to: depthURL,
            bytesPerPixel: 4
        ) else { return }

        var confidenceInfo: [String: Any]? = nil
        if let confidence = depth.confidenceMap,
           let info = writePixelBuffer(confidence, to: confURL, bytesPerPixel: 1) {
            confidenceInfo = info
        }

        let K = frame.camera.intrinsics
        let T = frame.camera.transform
        let q = simd_quatf(T)
        let t = T.columns.3

        var row: [String: Any] = [
            "timestamp_ns": Int64(frame.timestamp * 1_000_000_000),
            "depth_file": "depth/" + depthURL.lastPathComponent,
            "width": depthInfo["width"]!,
            "height": depthInfo["height"]!,
            "pixel_format": depthInfo["pixel_format"]!,
            "units": "metres_float32",
            "intrinsics_fx_fy_cx_cy": [
                K.columns.0.x, K.columns.1.y, K.columns.2.x, K.columns.2.y
            ],
            "camera_pose_arcore_tx_ty_tz_qx_qy_qz_qw": [
                t.x, t.y, t.z, q.imag.x, q.imag.y, q.imag.z, q.real
            ]
        ]
        if confidenceInfo != nil {
            row["confidence_file"] = "depth/" + confURL.lastPathComponent
            row["confidence_encoding"] = "ARConfidenceLevel_uint8"
        }

        if let data = try? JSONSerialization.data(withJSONObject: row),
           let line = String(data: data, encoding: .utf8),
           let bytes = (line + "\n").data(using: .utf8) {
            try? indexHandle.write(contentsOf: bytes)
        }
    }

    func close() {
        lock.lock(); defer { lock.unlock() }
        try? indexHandle.synchronize()
        try? indexHandle.close()
    }

    private func writePixelBuffer(
        _ buffer: CVPixelBuffer,
        to url: URL,
        bytesPerPixel: Int
    ) -> [String: Any]? {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }

        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let rowBytes = CVPixelBufferGetBytesPerRow(buffer)
        let packedRow = width * bytesPerPixel
        var data = Data(capacity: packedRow * height)

        for y in 0..<height {
            let row = base.advanced(by: y * rowBytes)
            data.append(row.assumingMemoryBound(to: UInt8.self), count: packedRow)
        }
        do {
            try data.write(to: url, options: .atomic)
            return [
                "width": width,
                "height": height,
                "pixel_format": CVPixelBufferGetPixelFormatType(buffer)
            ]
        } catch {
            return nil
        }
    }
}
