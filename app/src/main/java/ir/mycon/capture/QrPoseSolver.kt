package ir.mycon.capture

import com.google.ar.core.Pose
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

data class QrPoseEstimate(
    val worldPose: Pose,
    val reprojectionErrorPx: Double,
    val distanceM: Double
)

object QrPoseSolver {

    fun solve(
        payload: AnchorPayload,
        detection: QrFrameScanner.Detection
    ): QrPoseEstimate? {
        val sizeM = payload.sizeMm / 1000.0
        if (sizeM <= 0.0) return null

        val ordered = orderCorners(detection.corners)
        if (ordered.size != 4) return null

        val fx = detection.intrinsics4.getOrNull(0)?.toDouble() ?: return null
        val fy = detection.intrinsics4.getOrNull(1)?.toDouble() ?: return null
        val cx = detection.intrinsics4.getOrNull(2)?.toDouble() ?: return null
        val cy = detection.intrinsics4.getOrNull(3)?.toDouble() ?: return null
        if (fx <= 0.0 || fy <= 0.0) return null

        val h = sizeM / 2.0
        val objectPoints =
            arrayOf(
                doubleArrayOf(-h, -h),
                doubleArrayOf(h, -h),
                doubleArrayOf(h, h),
                doubleArrayOf(-h, h)
            )

        val normalized =
            ordered.map { p ->
                doubleArrayOf(
                    (p.first - cx) / fx,
                    (p.second - cy) / fy
                )
            }

        val homography =
            solveHomography(
                objectPoints,
                normalized.toTypedArray()
            ) ?: return null

        val c1 =
            doubleArrayOf(
                homography[0],
                homography[3],
                homography[6]
            )
        val c2 =
            doubleArrayOf(
                homography[1],
                homography[4],
                homography[7]
            )
        val c3 =
            doubleArrayOf(
                homography[2],
                homography[5],
                1.0
            )

        val n1 = norm(c1)
        val n2 = norm(c2)
        if (n1 < 1e-9 || n2 < 1e-9) return null

        var lambda = 2.0 / (n1 + n2)
        var r1 = scale(c1, lambda)
        var r2 = scale(c2, lambda)
        var t = scale(c3, lambda)

        if (t[2] < 0.0) {
            lambda = -lambda
            r1 = scale(c1, lambda)
            r2 = scale(c2, lambda)
            t = scale(c3, lambda)
        }

        r1 = normalize(r1) ?: return null
        r2 =
            normalize(
                subtract(
                    r2,
                    scale(r1, dot(r1, r2))
                )
            ) ?: return null
        val r3 = normalize(cross(r1, r2)) ?: return null

        val rCv =
            arrayOf(
                doubleArrayOf(r1[0], r2[0], r3[0]),
                doubleArrayOf(r1[1], r2[1], r3[1]),
                doubleArrayOf(r1[2], r2[2], r3[2])
            )

        val reprojection =
            reprojectionError(
                objectPoints,
                ordered,
                rCv,
                t,
                fx,
                fy,
                cx,
                cy
            )

        if (!reprojection.isFinite()) return null

        val distance = norm(t)
        if (
            distance < 0.08 ||
            distance > 12.0 ||
            reprojection > MAX_REPROJECTION_ERROR_PX
        ) {
            return null
        }

        // CV camera coordinates: +X right, +Y down, +Z forward.
        // ARCore camera coordinates: +X right, +Y up, -Z forward.
        // Model local coordinates use +X right, +Y up, +Z outward
        // toward the viewer from the QR plane.
        val c =
            arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0),
                doubleArrayOf(0.0, -1.0, 0.0),
                doubleArrayOf(0.0, 0.0, -1.0)
            )
        val m = c

        val rAr =
            multiply3(
                multiply3(c, rCv),
                m
            )

        val tAr =
            floatArrayOf(
                t[0].toFloat(),
                (-t[1]).toFloat(),
                (-t[2]).toFloat()
            )
        val qAr =
            quaternionFromRotation(rAr)

        val markerInCamera =
            Pose(tAr, qAr)

        val cp = detection.cameraPose7
        if (cp.size < 7) return null
        val cameraInWorld =
            Pose(
                floatArrayOf(cp[0], cp[1], cp[2]),
                floatArrayOf(cp[3], cp[4], cp[5], cp[6])
            )

        val markerInWorld =
            cameraInWorld.compose(markerInCamera)

        return QrPoseEstimate(
            worldPose = markerInWorld,
            reprojectionErrorPx = reprojection,
            distanceM = distance
        )
    }

    private fun orderCorners(
        corners: List<Pair<Int, Int>>
    ): List<Pair<Double, Double>> {
        if (corners.size != 4) return emptyList()

        val pts =
            corners.map {
                it.first.toDouble() to it.second.toDouble()
            }

        val tl = pts.minByOrNull { it.first + it.second } ?: return emptyList()
        val br = pts.maxByOrNull { it.first + it.second } ?: return emptyList()
        val tr = pts.maxByOrNull { it.first - it.second } ?: return emptyList()
        val bl = pts.minByOrNull { it.first - it.second } ?: return emptyList()

        val unique = listOf(tl, tr, br, bl).distinct()
        if (unique.size != 4) {
            val cx = pts.map { it.first }.average()
            val cy = pts.map { it.second }.average()
            val sorted =
                pts.sortedBy {
                    atan2(
                        it.second - cy,
                        it.first - cx
                    )
                }
            val start =
                sorted.indices.minByOrNull {
                    sorted[it].first + sorted[it].second
                } ?: 0
            val rotated =
                (0 until 4).map {
                    sorted[(start + it) % 4]
                }
            return if (
                polygonSignedArea(rotated) > 0.0
            ) {
                rotated
            } else {
                listOf(
                    rotated[0],
                    rotated[3],
                    rotated[2],
                    rotated[1]
                )
            }
        }

        return listOf(tl, tr, br, bl)
    }

    private fun polygonSignedArea(
        pts: List<Pair<Double, Double>>
    ): Double {
        var area = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            area += a.first * b.second - b.first * a.second
        }
        return area / 2.0
    }

    private fun solveHomography(
        src: Array<DoubleArray>,
        dst: Array<DoubleArray>
    ): DoubleArray? {
        val a = Array(8) { DoubleArray(9) }

        for (i in 0 until 4) {
            val x = src[i][0]
            val y = src[i][1]
            val u = dst[i][0]
            val v = dst[i][1]

            val r0 = i * 2
            val r1 = r0 + 1

            a[r0][0] = x
            a[r0][1] = y
            a[r0][2] = 1.0
            a[r0][6] = -u * x
            a[r0][7] = -u * y
            a[r0][8] = u

            a[r1][3] = x
            a[r1][4] = y
            a[r1][5] = 1.0
            a[r1][6] = -v * x
            a[r1][7] = -v * y
            a[r1][8] = v
        }

        for (col in 0 until 8) {
            var pivot = col
            var best = abs(a[col][col])
            for (row in col + 1 until 8) {
                val value = abs(a[row][col])
                if (value > best) {
                    best = value
                    pivot = row
                }
            }
            if (best < 1e-12) return null

            if (pivot != col) {
                val tmp = a[col]
                a[col] = a[pivot]
                a[pivot] = tmp
            }

            val div = a[col][col]
            for (j in col until 9) {
                a[col][j] /= div
            }

            for (row in 0 until 8) {
                if (row == col) continue
                val factor = a[row][col]
                if (abs(factor) < 1e-15) continue
                for (j in col until 9) {
                    a[row][j] -= factor * a[col][j]
                }
            }
        }

        return DoubleArray(8) { a[it][8] }
    }

    private fun reprojectionError(
        objectPoints: Array<DoubleArray>,
        imagePoints: List<Pair<Double, Double>>,
        r: Array<DoubleArray>,
        t: DoubleArray,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double
    ): Double {
        var sum = 0.0
        for (i in objectPoints.indices) {
            val x = objectPoints[i][0]
            val y = objectPoints[i][1]

            val xc =
                r[0][0] * x +
                    r[0][1] * y +
                    t[0]
            val yc =
                r[1][0] * x +
                    r[1][1] * y +
                    t[1]
            val zc =
                r[2][0] * x +
                    r[2][1] * y +
                    t[2]

            if (zc <= 1e-6) return Double.POSITIVE_INFINITY

            val u = fx * xc / zc + cx
            val v = fy * yc / zc + cy

            val du = u - imagePoints[i].first
            val dv = v - imagePoints[i].second
            sum += du * du + dv * dv
        }
        return sqrt(sum / objectPoints.size)
    }

    private fun quaternionFromRotation(
        r: Array<DoubleArray>
    ): FloatArray {
        val trace = r[0][0] + r[1][1] + r[2][2]
        val x: Double
        val y: Double
        val z: Double
        val w: Double

        if (trace > 0.0) {
            val s = sqrt(trace + 1.0) * 2.0
            w = 0.25 * s
            x = (r[2][1] - r[1][2]) / s
            y = (r[0][2] - r[2][0]) / s
            z = (r[1][0] - r[0][1]) / s
        } else if (
            r[0][0] > r[1][1] &&
            r[0][0] > r[2][2]
        ) {
            val s =
                sqrt(
                    max(
                        0.0,
                        1.0 +
                            r[0][0] -
                            r[1][1] -
                            r[2][2]
                    )
                ) * 2.0
            w = (r[2][1] - r[1][2]) / s
            x = 0.25 * s
            y = (r[0][1] + r[1][0]) / s
            z = (r[0][2] + r[2][0]) / s
        } else if (r[1][1] > r[2][2]) {
            val s =
                sqrt(
                    max(
                        0.0,
                        1.0 +
                            r[1][1] -
                            r[0][0] -
                            r[2][2]
                    )
                ) * 2.0
            w = (r[0][2] - r[2][0]) / s
            x = (r[0][1] + r[1][0]) / s
            y = 0.25 * s
            z = (r[1][2] + r[2][1]) / s
        } else {
            val s =
                sqrt(
                    max(
                        0.0,
                        1.0 +
                            r[2][2] -
                            r[0][0] -
                            r[1][1]
                    )
                ) * 2.0
            w = (r[1][0] - r[0][1]) / s
            x = (r[0][2] + r[2][0]) / s
            y = (r[1][2] + r[2][1]) / s
            z = 0.25 * s
        }

        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 1e-12) {
            return floatArrayOf(0f, 0f, 0f, 1f)
        }

        return floatArrayOf(
            (x / n).toFloat(),
            (y / n).toFloat(),
            (z / n).toFloat(),
            (w / n).toFloat()
        )
    }

    private fun multiply3(
        a: Array<DoubleArray>,
        b: Array<DoubleArray>
    ): Array<DoubleArray> {
        val out = Array(3) { DoubleArray(3) }
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var value = 0.0
                for (k in 0 until 3) {
                    value += a[i][k] * b[k][j]
                }
                out[i][j] = value
            }
        }
        return out
    }

    private fun norm(a: DoubleArray): Double =
        sqrt(dot(a, a))

    private fun dot(
        a: DoubleArray,
        b: DoubleArray
    ): Double =
        a[0] * b[0] +
            a[1] * b[1] +
            a[2] * b[2]

    private fun cross(
        a: DoubleArray,
        b: DoubleArray
    ): DoubleArray =
        doubleArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )

    private fun scale(
        a: DoubleArray,
        value: Double
    ): DoubleArray =
        doubleArrayOf(
            a[0] * value,
            a[1] * value,
            a[2] * value
        )

    private fun subtract(
        a: DoubleArray,
        b: DoubleArray
    ): DoubleArray =
        doubleArrayOf(
            a[0] - b[0],
            a[1] - b[1],
            a[2] - b[2]
        )

    private fun normalize(
        a: DoubleArray
    ): DoubleArray? {
        val n = norm(a)
        if (n < 1e-12) return null
        return scale(a, 1.0 / n)
    }

    private const val MAX_REPROJECTION_ERROR_PX = 10.0
}
