import Foundation
import simd

struct MarkerSolveResult {
    let worldFromMarker: simd_float4x4
    let reprojectionErrorPx: Float
    let distanceM: Float
}

enum PlanarMarkerSolver {
    static func solve(
        cornersPx: [SIMD2<Float>],
        markerSizeM: Float,
        intrinsics K: simd_float3x3,
        worldFromARCamera: simd_float4x4
    ) -> MarkerSolveResult? {
        guard cornersPx.count == 4, markerSizeM > 0 else { return nil }

        let fx = K.columns.0.x
        let fy = K.columns.1.y
        let cx = K.columns.2.x
        let cy = K.columns.2.y
        guard fx > 0, fy > 0 else { return nil }

        let h = markerSizeM / 2
        let object: [SIMD2<Float>] = [
            SIMD2(-h, -h), SIMD2(h, -h), SIMD2(h, h), SIMD2(-h, h)
        ]
        let normalized = cornersPx.map {
            SIMD2<Float>(($0.x - cx) / fx, ($0.y - cy) / fy)
        }

        var a = Array(repeating: Array(repeating: Float(0), count: 8), count: 8)
        var b = Array(repeating: Float(0), count: 8)
        for i in 0..<4 {
            let X = object[i].x, Y = object[i].y
            let x = normalized[i].x, y = normalized[i].y
            let r = i * 2
            a[r] = [X, Y, 1, 0, 0, 0, -x * X, -x * Y]
            b[r] = x
            a[r + 1] = [0, 0, 0, X, Y, 1, -y * X, -y * Y]
            b[r + 1] = y
        }
        guard let s = gaussianSolve(a, b) else { return nil }

        let c1 = SIMD3<Float>(s[0], s[3], s[6])
        let c2 = SIMD3<Float>(s[1], s[4], s[7])
        let c3 = SIMD3<Float>(s[2], s[5], 1)
        let n1 = simd_length(c1), n2 = simd_length(c2)
        guard n1 > 1e-6, n2 > 1e-6 else { return nil }
        let scale = 2 / (n1 + n2)

        var r1 = simd_normalize(c1 * scale)
        var r2 = c2 * scale
        r2 = simd_normalize(r2 - simd_dot(r2, r1) * r1)
        var r3 = simd_normalize(simd_cross(r1, r2))
        r2 = simd_normalize(simd_cross(r3, r1))
        if simd_dot(simd_cross(r1, r2), r3) < 0 {
            r3 *= -1
        }
        let t = c3 * scale
        guard t.z > 0 else { return nil }

        let cvFromMarker = simd_float4x4(columns: (
            SIMD4(r1.x, r1.y, r1.z, 0),
            SIMD4(r2.x, r2.y, r2.z, 0),
            SIMD4(r3.x, r3.y, r3.z, 0),
            SIMD4(t.x, t.y, t.z, 1)
        ))

        var cvToAR = matrix_identity_float4x4
        cvToAR.columns.1.y = -1
        cvToAR.columns.2.z = -1
        let worldFromMarker = simd_mul(worldFromARCamera, simd_mul(cvToAR, cvFromMarker))

        var sum: Float = 0
        for i in 0..<4 {
            let p = simd_mul(cvFromMarker, SIMD4(object[i].x, object[i].y, 0, 1))
            guard p.z > 1e-6 else { return nil }
            let u = fx * p.x / p.z + cx
            let v = fy * p.y / p.z + cy
            sum += simd_length(SIMD2(u, v) - cornersPx[i])
        }
        return MarkerSolveResult(
            worldFromMarker: worldFromMarker,
            reprojectionErrorPx: sum / 4,
            distanceM: simd_length(t)
        )
    }

    private static func gaussianSolve(_ aa: [[Float]], _ bb: [Float]) -> [Float]? {
        var a = aa
        var b = bb
        let n = b.count
        for col in 0..<n {
            var pivot = col
            for row in col..<n where abs(a[row][col]) > abs(a[pivot][col]) {
                pivot = row
            }
            guard abs(a[pivot][col]) > 1e-8 else { return nil }
            if pivot != col {
                a.swapAt(pivot, col)
                b.swapAt(pivot, col)
            }
            let d = a[col][col]
            for j in col..<n { a[col][j] /= d }
            b[col] /= d
            for row in 0..<n where row != col {
                let f = a[row][col]
                if abs(f) < 1e-12 { continue }
                for j in col..<n { a[row][j] -= f * a[col][j] }
                b[row] -= f * b[col]
            }
        }
        return b
    }
}
