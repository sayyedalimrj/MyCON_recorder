import Foundation
import ARKit
import CoreVideo

final class DepthRecorder {
    private let depthDir: URL
    private let indexHandle: FileHandle
    private let lock = NSLock()
    private var lastSampleTimestamp: TimeInterval = -1

    private(set) var sampleCount: Int = 0
    private(set) var rawSampleCount: Int = 0
    private(set) var smoothedSampleCount: Int = 0

    let supported: Bool
    let smoothedSupported: Bool
    let sampleInterval: TimeInterval = 0.10

    init(sessionDir: URL) throws {
        depthDir = sessionDir.appendingPathComponent("depth", isDirectory: true)
        try FileManager.default.createDirectory(at: depthDir, withIntermediateDirectories: true)
        let index = depthDir.appendingPathComponent("depth_index.jsonl")
        FileManager.default.createFile(atPath: index.path, contents: Data())
        indexHandle = try FileHandle(forWritingTo: index)

        supported = ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)
        smoothedSupported = ARWorldTrackingConfiguration.supportsFrameSemantics(.smoothedSceneDepth)
    }

    func record(_ frame: ARFrame) {
        guard supported || smoothedSupported else { return }

        lock.lock()
        defer { lock.unlock() }

        guard frame.timestamp - lastSampleTimestamp >= sampleInterval else { return }

        let rawDepth = frame.sceneDepth
        let smoothDepth = frame.smoothedSceneDepth
        guard rawDepth != nil || smoothDepth != nil else { return }

        lastSampleTimestamp = frame.timestamp
        sampleCount += 1
        let stem = String(format: "depth_%06d", sampleCount)

        var row: [String: Any] = [
            "timestamp_ns": Int64(frame.timestamp * 1_000_000_000)
        ]

        if let rawDepth {
            let rawURL = depthDir.appendingPathComponent(stem + ".raw.f32")
            let confURL = depthDir.appendingPathComponent(stem + ".confidence.u8")

            if let rawInfo = writePixelBuffer(
                rawDepth.depthMap,
                to: rawURL,
                bytesPerPixel: 4
            ) {
                rawSampleCount += 1
                row["raw_depth_file"] = "depth/" + rawURL.lastPathComponent
                row["raw_width"] = rawInfo["width"]!
                row["raw_height"] = rawInfo["height"]!
                row["raw_pixel_format"] = rawInfo["pixel_format"]!
                row["raw_units"] = "metres_float32"
            }

            if let confidence = rawDepth.confidenceMap,
               let confInfo = writePixelBuffer(
                    confidence,
                    to: confURL,
                    bytesPerPixel: 1
               ) {
                row["confidence_file"] = "depth/" + confURL.lastPathComponent
                row["confidence_width"] = confInfo["width"]!
                row["confidence_height"] = confInfo["height"]!
                row["confidence_encoding"] = "ARConfidenceLevel_uint8"
            }
        }

        if let smoothDepth {
            let smoothURL = depthDir.appendingPathComponent(stem + ".smooth.f32")
            if let smoothInfo = writePixelBuffer(
                smoothDepth.depthMap,
                to: smoothURL,
                bytesPerPixel: 4
            ) {
                smoothedSampleCount += 1
                row["smoothed_depth_file"] = "depth/" + smoothURL.lastPathComponent
                row["smoothed_width"] = smoothInfo["width"]!
                row["smoothed_height"] = smoothInfo["height"]!
                row["smoothed_pixel_format"] = smoothInfo["pixel_format"]!
                row["smoothed_units"] = "metres_float32"
            }
        }

        let K = frame.camera.intrinsics
        let T = frame.camera.transform
        let q = simd_quatf(T)
        let t = T.columns.3
        let res = frame.camera.imageResolution

        row["camera_intrinsics_fx_fy_cx_cy"] = [
            K.columns.0.x, K.columns.1.y, K.columns.2.x, K.columns.2.y
        ]
        row["camera_image_wh"] = [Int(res.width), Int(res.height)]
        row["camera_pose_arcore_tx_ty_tz_qx_qy_qz_qw"] = [
            t.x, t.y, t.z,
            q.imag.x, q.imag.y, q.imag.z, q.real
        ]

        if let data = try? JSONSerialization.data(withJSONObject: row),
           let line = String(data: data, encoding: .utf8),
           let bytes = (line + "\n").data(using: .utf8) {
            try? indexHandle.write(contentsOf: bytes)
        }
    }

    func close() {
        lock.lock()
        defer { lock.unlock() }
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
            data.append(
                row.assumingMemoryBound(to: UInt8.self),
                count: packedRow
            )
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
