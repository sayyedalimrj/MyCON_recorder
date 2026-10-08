import Foundation
import ARKit
import Metal
import simd

enum SceneMeshRecorder {
    static func writeSnapshot(
        frame: ARFrame,
        sessionDir: URL
    ) -> [String: Any] {
        let meshAnchors = frame.anchors.compactMap { $0 as? ARMeshAnchor }
        let spatialDir = sessionDir.appendingPathComponent("spatial", isDirectory: true)

        do {
            try FileManager.default.createDirectory(
                at: spatialDir,
                withIntermediateDirectories: true
            )
        } catch {
            return [
                "supported": false,
                "error": error.localizedDescription
            ]
        }

        var summary: [String: Any] = [
            "mesh_anchor_count": meshAnchors.count,
            "mesh_supported": ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh)
        ]

        if !meshAnchors.isEmpty {
            do {
                let meshURL = spatialDir.appendingPathComponent("scene_mesh.ply")
                let meshStats = try writeMeshPLY(
                    anchors: meshAnchors,
                    to: meshURL
                )
                summary["mesh_file"] = "spatial/scene_mesh.ply"
                summary["mesh_vertex_count"] = meshStats.vertices
                summary["mesh_face_count"] = meshStats.faces
                summary["classification_counts"] = meshStats.classifications
            } catch {
                summary["mesh_error"] = error.localizedDescription
            }
        }

        if let cloud = frame.rawFeaturePoints {
            do {
                let pointsURL = spatialDir.appendingPathComponent("feature_points.ply")
                try writePointCloudPLY(
                    points: cloud.points,
                    identifiers: cloud.identifiers,
                    to: pointsURL
                )
                summary["feature_points_file"] = "spatial/feature_points.ply"
                summary["feature_point_count"] = cloud.points.count
            } catch {
                summary["feature_points_error"] = error.localizedDescription
            }
        }

        let summaryURL = spatialDir.appendingPathComponent("spatial_summary.json")
        if let data = try? JSONSerialization.data(
            withJSONObject: summary,
            options: [.prettyPrinted, .sortedKeys]
        ) {
            try? data.write(to: summaryURL, options: .atomic)
        }

        return summary
    }

    private struct MeshStats {
        let vertices: Int
        let faces: Int
        let classifications: [String: Int]
    }

    private static func writeMeshPLY(
        anchors: [ARMeshAnchor],
        to url: URL
    ) throws -> MeshStats {
        let vertexCount = anchors.reduce(0) { $0 + $1.geometry.vertices.count }
        let faceCount = anchors.reduce(0) { $0 + $1.geometry.faces.count }

        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }

        let header = """
        ply
        format binary_little_endian 1.0
        comment MyCON ARKit LiDAR scene mesh
        element vertex \(vertexCount)
        property float x
        property float y
        property float z
        element face \(faceCount)
        property list uchar uint vertex_indices
        end_header
        """
        try handle.write(contentsOf: Data((header + "\n").utf8))

        var vertexChunk = Data()
        vertexChunk.reserveCapacity(1_048_576)

        for anchor in anchors {
            let source = anchor.geometry.vertices
            let base = source.buffer.contents()

            for i in 0..<source.count {
                let ptr = base.advanced(by: source.offset + i * source.stride)
                let local = ptr.assumingMemoryBound(to: SIMD3<Float>.self).pointee
                let world4 = anchor.transform * SIMD4<Float>(local.x, local.y, local.z, 1)
                appendFloat(world4.x, to: &vertexChunk)
                appendFloat(world4.y, to: &vertexChunk)
                appendFloat(world4.z, to: &vertexChunk)

                if vertexChunk.count >= 1_048_576 {
                    try handle.write(contentsOf: vertexChunk)
                    vertexChunk.removeAll(keepingCapacity: true)
                }
            }
        }

        if !vertexChunk.isEmpty {
            try handle.write(contentsOf: vertexChunk)
        }

        var faceChunk = Data()
        faceChunk.reserveCapacity(1_048_576)
        var vertexOffset: UInt32 = 0
        var classifications: [String: Int] = [:]

        for anchor in anchors {
            let geometry = anchor.geometry
            let faces = geometry.faces
            let base = faces.buffer.contents()

            for faceIndex in 0..<faces.count {
                faceChunk.append(3)

                for corner in 0..<3 {
                    let rawIndex = faceIndex * faces.indexCountPerPrimitive + corner
                    let byteOffset = rawIndex * faces.bytesPerIndex
                    let index: UInt32

                    if faces.bytesPerIndex == 2 {
                        index = UInt32(
                            base.advanced(by: byteOffset)
                                .assumingMemoryBound(to: UInt16.self)
                                .pointee
                        )
                    } else {
                        index = base.advanced(by: byteOffset)
                            .assumingMemoryBound(to: UInt32.self)
                            .pointee
                    }

                    appendUInt32(index + vertexOffset, to: &faceChunk)
                }

                let label = classificationName(
                    geometry.classificationOf(faceWithIndex: faceIndex)
                )
                classifications[label, default: 0] += 1

                if faceChunk.count >= 1_048_576 {
                    try handle.write(contentsOf: faceChunk)
                    faceChunk.removeAll(keepingCapacity: true)
                }
            }

            vertexOffset += UInt32(geometry.vertices.count)
        }

        if !faceChunk.isEmpty {
            try handle.write(contentsOf: faceChunk)
        }

        return MeshStats(
            vertices: vertexCount,
            faces: faceCount,
            classifications: classifications
        )
    }

    private static func writePointCloudPLY(
        points: [SIMD3<Float>],
        identifiers: [UInt64],
        to url: URL
    ) throws {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }

        let header = """
        ply
        format binary_little_endian 1.0
        comment MyCON ARKit raw feature points
        element vertex \(points.count)
        property float x
        property float y
        property float z
        property uint id_low
        property uint id_high
        end_header
        """
        try handle.write(contentsOf: Data((header + "\n").utf8))

        var chunk = Data()
        chunk.reserveCapacity(1_048_576)

        for (i, p) in points.enumerated() {
            appendFloat(p.x, to: &chunk)
            appendFloat(p.y, to: &chunk)
            appendFloat(p.z, to: &chunk)

            let id = i < identifiers.count ? identifiers[i] : UInt64(i)
            appendUInt32(UInt32(truncatingIfNeeded: id), to: &chunk)
            appendUInt32(UInt32(truncatingIfNeeded: id >> 32), to: &chunk)

            if chunk.count >= 1_048_576 {
                try handle.write(contentsOf: chunk)
                chunk.removeAll(keepingCapacity: true)
            }
        }

        if !chunk.isEmpty {
            try handle.write(contentsOf: chunk)
        }
    }

    private static func classificationName(
        _ value: ARMeshClassification
    ) -> String {
        switch value {
        case .none: return "none"
        case .wall: return "wall"
        case .floor: return "floor"
        case .ceiling: return "ceiling"
        case .table: return "table"
        case .seat: return "seat"
        case .window: return "window"
        case .door: return "door"
        @unknown default: return "unknown"
        }
    }

    private static func appendFloat(
        _ value: Float,
        to data: inout Data
    ) {
        var bits = value.bitPattern.littleEndian
        withUnsafeBytes(of: &bits) {
            data.append(contentsOf: $0)
        }
    }

    private static func appendUInt32(
        _ value: UInt32,
        to data: inout Data
    ) {
        var v = value.littleEndian
        withUnsafeBytes(of: &v) {
            data.append(contentsOf: $0)
        }
    }
}
