package ir.mycon.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Session
import org.json.JSONObject
import java.util.EnumSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan

enum class CaptureProfile(
    val key: String,
    val displayName: String
) {
    SCIENTIFIC_HQ30(
        "scientific_hq30",
        "Scientific HQ • 30 FPS"
    ),
    MOTION_60(
        "motion_60",
        "Motion • 60 FPS"
    ),
    ARCORE_AUTO(
        "arcore_auto",
        "ARCore Auto"
    );

    companion object {
        fun fromKey(key: String): CaptureProfile =
            entries.firstOrNull { it.key == key }
                ?: SCIENTIFIC_HQ30
    }
}

data class CameraSelection(
    val requestedProfile: String,
    val appliedProfile: String,
    val fallbackUsed: Boolean,
    val cameraId: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val textureWidth: Int,
    val textureHeight: Int,
    val fpsMin: Int,
    val fpsMax: Int,
    val facing: String,
    val depthUsage: String,
    val stereoUsage: String,
    val focalLengthMm: Double?,
    val horizontalFovDeg: Double?,
    val logicalMultiCamera: Boolean,
    val highResolutionCpuStream: Boolean,
    val note: String
) {
    val megapixels: Double
        get() =
            imageWidth.toDouble() *
                imageHeight.toDouble() /
                1_000_000.0

    val displaySummary: String
        get() {
            val fps =
                if (fpsMin == fpsMax) {
                    "$fpsMax"
                } else {
                    "$fpsMin-$fpsMax"
                }
            val fov =
                horizontalFovDeg?.let {
                    " • FOV ${String.format("%.0f", it)}°"
                } ?: ""
            return "${imageWidth}×${imageHeight} • ${fps}fps$fov"
        }

    fun toJson(): JSONObject =
        JSONObject()
            .put(
                "requested_profile",
                requestedProfile
            )
            .put(
                "applied_profile",
                appliedProfile
            )
            .put(
                "fallback_used",
                fallbackUsed
            )
            .put("camera_id", cameraId)
            .put(
                "cpu_image_width",
                imageWidth
            )
            .put(
                "cpu_image_height",
                imageHeight
            )
            .put(
                "gpu_texture_width",
                textureWidth
            )
            .put(
                "gpu_texture_height",
                textureHeight
            )
            .put("fps_min", fpsMin)
            .put("fps_max", fpsMax)
            .put("facing", facing)
            .put(
                "depth_sensor_usage",
                depthUsage
            )
            .put(
                "stereo_camera_usage",
                stereoUsage
            )
            .put(
                "focal_length_mm",
                focalLengthMm
                    ?: JSONObject.NULL
            )
            .put(
                "horizontal_fov_deg",
                horizontalFovDeg
                    ?: JSONObject.NULL
            )
            .put(
                "logical_multi_camera",
                logicalMultiCamera
            )
            .put(
                "high_resolution_cpu_stream",
                highResolutionCpuStream
            )
            .put("note", note)
}

object ScientificCameraSelector {

    private data class Candidate(
        val config: CameraConfig,
        val fovDeg: Double?,
        val focalLengthMm: Double?,
        val logicalMultiCamera: Boolean,
        val originalIndex: Int
    ) {
        val imagePixels: Long
            get() =
                config.imageSize.width.toLong() *
                    config.imageSize.height.toLong()

        val texturePixels: Long
            get() =
                config.textureSize.width.toLong() *
                    config.textureSize.height.toLong()

        val lensTier: Int
            get() =
                when {
                    fovDeg == null -> 2
                    fovDeg in 52.0..90.0 -> 3
                    else -> 1
                }

        val fovDistance: Double
            get() =
                fovDeg?.let {
                    abs(it - 70.0)
                } ?: 999.0
    }

    fun apply(
        context: Context,
        session: Session,
        requestedProfileKey: String
    ): CameraSelection {
        val requested =
            CaptureProfile.fromKey(
                requestedProfileKey
            )

        if (
            requested ==
            CaptureProfile.ARCORE_AUTO
        ) {
            val current =
                session.cameraConfig
            return snapshot(
                context,
                current,
                requested,
                requested,
                fallback = false,
                note =
                    "ARCore default camera configuration."
            )
        }

        val target =
            if (
                requested ==
                CaptureProfile.MOTION_60
            ) {
                CameraConfig.TargetFps
                    .TARGET_FPS_60
            } else {
                CameraConfig.TargetFps
                    .TARGET_FPS_30
            }

        var applied = requested
        var fallback = false

        var configs =
            configsFor(
                session,
                target
            )

        if (
            configs.isEmpty() &&
            requested ==
            CaptureProfile.MOTION_60
        ) {
            configs =
                configsFor(
                    session,
                    CameraConfig.TargetFps
                        .TARGET_FPS_30
                )
            applied =
                CaptureProfile
                    .SCIENTIFIC_HQ30
            fallback = true
        }

        if (configs.isEmpty()) {
            val current =
                session.cameraConfig
            return snapshot(
                context,
                current,
                requested,
                CaptureProfile
                    .ARCORE_AUTO,
                fallback = true,
                note =
                    "No filtered rear camera config was available; using ARCore default."
            )
        }

        val cameraManager =
            context.getSystemService(
                Context.CAMERA_SERVICE
            ) as CameraManager

        val candidates =
            configs.mapIndexed {
                    index,
                    config ->
                val characteristics =
                    runCatching {
                        cameraManager
                            .getCameraCharacteristics(
                                config.cameraId
                            )
                    }.getOrNull()

                val optics =
                    characteristics
                        ?.let {
                            estimateOptics(it)
                        }

                Candidate(
                    config = config,
                    fovDeg =
                        optics?.first,
                    focalLengthMm =
                        optics?.second,
                    logicalMultiCamera =
                        characteristics
                            ?.let {
                                isLogicalMultiCamera(it)
                            } ?: false,
                    originalIndex = index
                )
            }

        val highRes =
            candidates.filter {
                it.imagePixels >
                    640L * 480L
            }

        val pool =
            if (highRes.isNotEmpty()) {
                highRes
            } else {
                candidates
            }

        val chosen =
            pool.sortedWith(
                compareByDescending<Candidate> {
                    it.lensTier
                }
                    .thenByDescending {
                        it.imagePixels
                    }
                    .thenBy {
                        it.fovDistance
                    }
                    .thenByDescending {
                        it.texturePixels
                    }
                    .thenBy {
                        it.originalIndex
                    }
            ).first()

        session.cameraConfig =
            chosen.config

        val note =
            buildString {
                append(
                    if (
                        chosen.lensTier == 3
                    ) {
                        "Rear standard/wide camera preferred for SfM; "
                    } else {
                        "ARCore-compatible rear camera selected; "
                    }
                )
                append(
                    "highest available CPU image resolution in the requested FPS class."
                )
                if (
                    chosen.imagePixels <=
                    640L * 480L
                ) {
                    append(
                        " Device exposes only VGA CPU image for this profile."
                    )
                }
            }

        return snapshot(
            context = context,
            config = chosen.config,
            requested = requested,
            applied = applied,
            fallback = fallback,
            note = note
        )
    }

    private fun configsFor(
        session: Session,
        targetFps:
            CameraConfig.TargetFps
    ): List<CameraConfig> {
        val filter =
            CameraConfigFilter(session).apply {
                facingDirection =
                    CameraConfig
                        .FacingDirection
                        .BACK
                this.targetFps =
                    EnumSet.of(
                        targetFps
                    )
            }
        return session
            .getSupportedCameraConfigs(
                filter
            )
    }

    private fun snapshot(
        context: Context,
        config: CameraConfig,
        requested: CaptureProfile,
        applied: CaptureProfile,
        fallback: Boolean,
        note: String
    ): CameraSelection {
        val manager =
            context.getSystemService(
                Context.CAMERA_SERVICE
            ) as CameraManager

        val characteristics =
            runCatching {
                manager
                    .getCameraCharacteristics(
                        config.cameraId
                    )
            }.getOrNull()

        val optics =
            characteristics
                ?.let {
                    estimateOptics(it)
                }

        return CameraSelection(
            requestedProfile =
                requested.key,
            appliedProfile =
                applied.key,
            fallbackUsed = fallback,
            cameraId =
                config.cameraId,
            imageWidth =
                config.imageSize.width,
            imageHeight =
                config.imageSize.height,
            textureWidth =
                config.textureSize.width,
            textureHeight =
                config.textureSize.height,
            fpsMin =
                config.fpsRange.lower,
            fpsMax =
                config.fpsRange.upper,
            facing =
                config.facingDirection.name,
            depthUsage =
                config.depthSensorUsage.name,
            stereoUsage =
                config.stereoCameraUsage.name,
            focalLengthMm =
                optics?.second,
            horizontalFovDeg =
                optics?.first,
            logicalMultiCamera =
                characteristics
                    ?.let {
                        isLogicalMultiCamera(
                            it
                        )
                    } ?: false,
            highResolutionCpuStream =
                config.imageSize.width *
                    config.imageSize.height >
                    640 * 480,
            note = note
        )
    }

    private fun estimateOptics(
        characteristics:
            CameraCharacteristics
    ): Pair<Double, Double>? {
        val sensor =
            characteristics.get(
                CameraCharacteristics
                    .SENSOR_INFO_PHYSICAL_SIZE
            ) ?: return null

        val focalLengths =
            characteristics.get(
                CameraCharacteristics
                    .LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            ) ?: return null

        if (
            focalLengths.isEmpty() ||
            sensor.width <= 0f
        ) {
            return null
        }

        val pairs =
            focalLengths.mapNotNull {
                focal ->
                if (focal <= 0f) {
                    null
                } else {
                    val fov =
                        2.0 *
                            atan(
                                sensor.width.toDouble() /
                                    (
                                        2.0 *
                                            focal.toDouble()
                                        )
                            ) *
                            180.0 /
                            PI
                    fov to
                        focal.toDouble()
                }
            }

        return pairs.minByOrNull {
            abs(it.first - 70.0)
        }
    }

    private fun isLogicalMultiCamera(
        characteristics:
            CameraCharacteristics
    ): Boolean {
        if (Build.VERSION.SDK_INT < 28) {
            return false
        }
        val caps =
            characteristics.get(
                CameraCharacteristics
                    .REQUEST_AVAILABLE_CAPABILITIES
            ) ?: return false
        return caps.contains(
            CameraCharacteristics
                .REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
        )
    }
}
