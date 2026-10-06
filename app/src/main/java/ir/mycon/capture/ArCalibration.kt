package ir.mycon.capture

import com.google.ar.core.Pose
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt

data class ControlObservation(
    val payload: AnchorPayload,
    val worldPosition: FloatArray,
    val reprojectionErrorPx: Double
)

class MultiAnchorScaleCalibrator {
    private val observations =
        linkedMapOf<String, ControlObservation>()
    private val positionSamples =
        linkedMapOf<String, ArrayDeque<FloatArray>>()

    @Synchronized
    fun update(
        payload: AnchorPayload,
        estimate: QrPoseEstimate
    ) {
        val key =
            "${payload.project}/${payload.anchor}"
        val queue =
            positionSamples.getOrPut(key) {
                ArrayDeque()
            }
        queue.addLast(
            estimate.worldPose.translation.copyOf()
        )
        while (queue.size > 5) {
            queue.removeFirst()
        }

        val avg =
            floatArrayOf(
                queue.map { it[0].toDouble() }
                    .average()
                    .toFloat(),
                queue.map { it[1].toDouble() }
                    .average()
                    .toFloat(),
                queue.map { it[2].toDouble() }
                    .average()
                    .toFloat()
            )

        observations[key] =
            ControlObservation(
                payload = payload,
                worldPosition = avg,
                reprojectionErrorPx =
                    estimate.reprojectionErrorPx
            )
    }

    @Synchronized
    fun count(): Int =
        observations.size

    @Synchronized
    fun scaleCorrection(): Double {
        val values =
            observations.values.toList()
        if (values.size < 2) return 1.0

        val ratios = mutableListOf<Double>()
        for (i in values.indices) {
            for (j in i + 1 until values.size) {
                val a = values[i]
                val b = values[j]

                if (
                    a.payload.project !=
                    b.payload.project
                ) {
                    continue
                }

                val known =
                    distance(
                        doubleArrayOf(
                            a.payload.x,
                            a.payload.y,
                            a.payload.z
                        ),
                        doubleArrayOf(
                            b.payload.x,
                            b.payload.y,
                            b.payload.z
                        )
                    )

                val observed =
                    distance(
                        doubleArrayOf(
                            a.worldPosition[0].toDouble(),
                            a.worldPosition[1].toDouble(),
                            a.worldPosition[2].toDouble()
                        ),
                        doubleArrayOf(
                            b.worldPosition[0].toDouble(),
                            b.worldPosition[1].toDouble(),
                            b.worldPosition[2].toDouble()
                        )
                    )

                if (
                    known >= 0.10 &&
                    observed >= 0.10
                ) {
                    ratios += known / observed
                }
            }
        }

        if (ratios.isEmpty()) return 1.0
        ratios.sort()
        val median =
            if (ratios.size % 2 == 1) {
                ratios[ratios.size / 2]
            } else {
                (
                    ratios[ratios.size / 2 - 1] +
                        ratios[ratios.size / 2]
                    ) / 2.0
            }

        // Huge corrections mean the QR geometry/control layout is wrong.
        // Do not silently distort the model. Fall back to the metric scale
        // from ARCore + physical QR size and surface the red QA warning.
        return if (
            median in
            MIN_ALLOWED_SCALE_CORRECTION..
                MAX_ALLOWED_SCALE_CORRECTION
        ) {
            median
        } else {
            1.0
        }
    }

    @Synchronized
    fun rawScaleCorrectionOrNull(): Double? {
        val values = observations.values.toList()
        if (values.size < 2) return null

        val ratios = mutableListOf<Double>()
        for (i in values.indices) {
            for (j in i + 1 until values.size) {
                val a = values[i]
                val b = values[j]
                if (a.payload.project != b.payload.project) continue

                val known =
                    distance(
                        doubleArrayOf(a.payload.x, a.payload.y, a.payload.z),
                        doubleArrayOf(b.payload.x, b.payload.y, b.payload.z)
                    )
                val observed =
                    distance(
                        doubleArrayOf(
                            a.worldPosition[0].toDouble(),
                            a.worldPosition[1].toDouble(),
                            a.worldPosition[2].toDouble()
                        ),
                        doubleArrayOf(
                            b.worldPosition[0].toDouble(),
                            b.worldPosition[1].toDouble(),
                            b.worldPosition[2].toDouble()
                        )
                    )
                if (known >= 0.10 && observed >= 0.10) {
                    ratios += known / observed
                }
            }
        }
        if (ratios.isEmpty()) return null
        ratios.sort()
        return ratios[ratios.size / 2]
    }

    @Synchronized
    fun isScalePlausible(): Boolean {
        val raw = rawScaleCorrectionOrNull()
            ?: return true
        return raw in
            MIN_ALLOWED_SCALE_CORRECTION..
                MAX_ALLOWED_SCALE_CORRECTION
    }

    private fun distance(
        a: DoubleArray,
        b: DoubleArray
    ): Double {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(
            dx * dx +
                dy * dy +
                dz * dz
        )
    }

    companion object {
        const val MIN_ALLOWED_SCALE_CORRECTION =
            0.80
        const val MAX_ALLOWED_SCALE_CORRECTION =
            1.20
    }
}

class PoseAccumulator(
    private val maxSamples: Int = 5
) {
    private val samples =
        ArrayDeque<Pose>()

    @Synchronized
    fun add(pose: Pose) {
        samples.addLast(pose)
        while (samples.size > maxSamples) {
            samples.removeFirst()
        }
    }

    @Synchronized
    fun count(): Int = samples.size

    @Synchronized
    fun average(): Pose? {
        if (samples.isEmpty()) return null

        val list = samples.toList()
        val tx =
            list.map { it.tx().toDouble() }
                .average()
                .toFloat()
        val ty =
            list.map { it.ty().toDouble() }
                .average()
                .toFloat()
        val tz =
            list.map { it.tz().toDouble() }
                .average()
                .toFloat()

        val ref = list.first().rotationQuaternion
        var qx = 0.0
        var qy = 0.0
        var qz = 0.0
        var qw = 0.0

        list.forEach { pose ->
            val q =
                pose.rotationQuaternion
                    .copyOf()
            val dot =
                q[0] * ref[0] +
                    q[1] * ref[1] +
                    q[2] * ref[2] +
                    q[3] * ref[3]
            if (dot < 0f) {
                for (i in q.indices) {
                    q[i] = -q[i]
                }
            }
            qx += q[0]
            qy += q[1]
            qz += q[2]
            qw += q[3]
        }

        val norm =
            sqrt(
                qx * qx +
                    qy * qy +
                    qz * qz +
                    qw * qw
            )
        if (norm < 1e-12) return null

        return Pose(
            floatArrayOf(tx, ty, tz),
            floatArrayOf(
                (qx / norm).toFloat(),
                (qy / norm).toFloat(),
                (qz / norm).toFloat(),
                (qw / norm).toFloat()
            )
        )
    }
}
