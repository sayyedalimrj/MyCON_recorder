package ir.mycon.capture

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.util.ArrayDeque
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ModelLockStatus(
    val samples: Int,
    val locked: Boolean,
    val positionSpreadM: Double,
    val rotationSpreadDeg: Double,
    val reprojectionErrorPx: Double
)

class StableModelAnchorController(
    private val requiredSamples: Int = 8,
    private val maxSamples: Int = 14,
    private val maxPositionSpreadM: Double = 0.035,
    private val maxRotationSpreadDeg: Double = 6.0,
    private val maxReprojectionErrorPx: Double = 5.0
) {
    private data class Observation(
        val pose: Pose,
        val reprojectionErrorPx: Double
    )

    private val observations =
        ArrayDeque<Observation>()

    private var currentKey: String? = null
    private var anchor: Anchor? = null
    private var lastStatus =
        ModelLockStatus(
            samples = 0,
            locked = false,
            positionSpreadM = Double.POSITIVE_INFINITY,
            rotationSpreadDeg = Double.POSITIVE_INFINITY,
            reprojectionErrorPx = Double.POSITIVE_INFINITY
        )

    @Synchronized
    fun reset() {
        anchor?.detach()
        anchor = null
        currentKey = null
        observations.clear()
        lastStatus =
            ModelLockStatus(
                samples = 0,
                locked = false,
                positionSpreadM = Double.POSITIVE_INFINITY,
                rotationSpreadDeg = Double.POSITIVE_INFINITY,
                reprojectionErrorPx = Double.POSITIVE_INFINITY
            )
    }

    @Synchronized
    fun status(): ModelLockStatus =
        lastStatus

    @Synchronized
    fun anchorPoseOrNull(): Pose? {
        val a = anchor ?: return null
        return if (
            a.trackingState ==
            TrackingState.TRACKING
        ) {
            a.pose
        } else {
            null
        }
    }

    @Synchronized
    fun hasLockedAnchor(): Boolean =
        anchor?.trackingState ==
            TrackingState.TRACKING

    @Synchronized
    fun feed(
        session: Session,
        payload: AnchorPayload,
        estimate: QrPoseEstimate
    ): ModelLockStatus {
        val key =
            "${payload.project}/${payload.anchor}"

        if (
            currentKey != null &&
            currentKey != key
        ) {
            reset()
        }
        currentKey = key

        if (hasLockedAnchor()) {
            return lastStatus.copy(
                locked = true
            )
        }

        if (
            estimate.reprojectionErrorPx >
            maxReprojectionErrorPx
        ) {
            lastStatus =
                lastStatus.copy(
                    reprojectionErrorPx =
                        estimate.reprojectionErrorPx
                )
            return lastStatus
        }

        observations.addLast(
            Observation(
                pose = estimate.worldPose,
                reprojectionErrorPx =
                    estimate.reprojectionErrorPx
            )
        )
        while (
            observations.size >
            maxSamples
        ) {
            observations.removeFirst()
        }

        val poses =
            observations.map {
                it.pose
            }

        val average =
            averagePose(poses)
                ?: return lastStatus

        val positionSpread =
            poses.maxOfOrNull {
                distance(
                    it.translation,
                    average.translation
                )
            } ?: Double.POSITIVE_INFINITY

        val rotationSpread =
            poses.maxOfOrNull {
                quaternionAngleDeg(
                    it.rotationQuaternion,
                    average.rotationQuaternion
                )
            } ?: Double.POSITIVE_INFINITY

        val avgReprojection =
            observations
                .map {
                    it.reprojectionErrorPx
                }
                .average()

        val stable =
            observations.size >=
                requiredSamples &&
                positionSpread <=
                    maxPositionSpreadM &&
                rotationSpread <=
                    maxRotationSpreadDeg

        if (stable) {
            anchor?.detach()
            anchor =
                session.createAnchor(
                    average
                )
        }

        lastStatus =
            ModelLockStatus(
                samples =
                    observations.size,
                locked =
                    stable &&
                        anchor != null,
                positionSpreadM =
                    positionSpread,
                rotationSpreadDeg =
                    rotationSpread,
                reprojectionErrorPx =
                    avgReprojection
            )

        return lastStatus
    }

    private fun averagePose(
        poses: List<Pose>
    ): Pose? {
        if (poses.isEmpty()) {
            return null
        }

        val tx =
            poses.map {
                it.tx().toDouble()
            }.average()
                .toFloat()
        val ty =
            poses.map {
                it.ty().toDouble()
            }.average()
                .toFloat()
        val tz =
            poses.map {
                it.tz().toDouble()
            }.average()
                .toFloat()

        val reference =
            poses.first()
                .rotationQuaternion

        var x = 0.0
        var y = 0.0
        var z = 0.0
        var w = 0.0

        poses.forEach {
            val q =
                it.rotationQuaternion
                    .copyOf()

            val dot =
                q[0] * reference[0] +
                    q[1] * reference[1] +
                    q[2] * reference[2] +
                    q[3] * reference[3]

            if (dot < 0f) {
                for (
                    i in
                    q.indices
                ) {
                    q[i] = -q[i]
                }
            }

            x += q[0]
            y += q[1]
            z += q[2]
            w += q[3]
        }

        val norm =
            sqrt(
                x * x +
                    y * y +
                    z * z +
                    w * w
            )

        if (norm < 1e-12) {
            return null
        }

        return Pose(
            floatArrayOf(
                tx,
                ty,
                tz
            ),
            floatArrayOf(
                (x / norm)
                    .toFloat(),
                (y / norm)
                    .toFloat(),
                (z / norm)
                    .toFloat(),
                (w / norm)
                    .toFloat()
            )
        )
    }

    private fun distance(
        a: FloatArray,
        b: FloatArray
    ): Double {
        val dx =
            a[0] - b[0]
        val dy =
            a[1] - b[1]
        val dz =
            a[2] - b[2]
        return sqrt(
            (
                dx * dx +
                    dy * dy +
                    dz * dz
                ).toDouble()
        )
    }

    private fun quaternionAngleDeg(
        a: FloatArray,
        b: FloatArray
    ): Double {
        var dot =
            (
                a[0] * b[0] +
                    a[1] * b[1] +
                    a[2] * b[2] +
                    a[3] * b[3]
                ).toDouble()

        dot =
            min(
                1.0,
                max(
                    -1.0,
                    kotlin.math.abs(
                        dot
                    )
                )
            )

        return 2.0 *
            acos(dot) *
            180.0 /
            Math.PI
    }
}
