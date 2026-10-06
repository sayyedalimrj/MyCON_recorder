package ir.mycon.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.ImageMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

class TelemetryRecorder(
    context: Context,
    val sessionDir: File,
    private val locationTracker: LocationTracker,
    private val cameraSelection: CameraSelection?,
    private val focusMode: String,
    private val torchEnabled: Boolean
) : SensorEventListener {

    private val poseWriter =
        BufferedWriter(
            FileWriter(
                File(
                    sessionDir,
                    "pose.csv"
                )
            )
        )
    private val imuWriter =
        BufferedWriter(
            FileWriter(
                File(
                    sessionDir,
                    "imu.csv"
                )
            )
        )
    private val qrWriter =
        BufferedWriter(
            FileWriter(
                File(
                    sessionDir,
                    "qr_events.jsonl"
                )
            )
        )

    private val sensorManager =
        context.getSystemService(
            Context.SENSOR_SERVICE
        ) as SensorManager

    private val frameCounter =
        AtomicLong(0)
    private val trackingCounter =
        AtomicLong(0)
    private val qrCounter =
        AtomicLong(0)

    private val anchors =
        Collections.synchronizedSet(
            mutableSetOf<String>()
        )
    private val projects =
        Collections.synchronizedSet(
            mutableSetOf<String>()
        )

    private val startedUtc =
        isoNow()
    private val startedElapsedNs =
        SystemClock.elapsedRealtimeNanos()

    private var firstFrameTimestampNs =
        0L
    private var lastFrameTimestampNs =
        0L

    private var exposureSamples =
        0L
    private var exposureSumNs =
        0.0
    private var exposureMaxNs =
        0L

    private var isoSamples =
        0L
    private var isoSum =
        0.0

    @Volatile
    private var closed = false

    init {
        poseWriter.write(
            "frame,timestamp_ns,tx_m,ty_m,tz_m,qx,qy,qz,qw,tracking," +
                "fx,fy,cx,cy,image_w,image_h," +
                "exposure_ns,iso,frame_duration_ns,rolling_shutter_skew_ns," +
                "gps_lat,gps_lon,gps_alt_m,gps_accuracy_m,gps_bearing_deg,gps_speed_mps,gps_time_ms\n"
        )
        imuWriter.write(
            "timestamp_ns,sensor,v0,v1,v2,v3,accuracy\n"
        )

        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ROTATION_VECTOR
        ).forEach { type ->
            sensorManager
                .getDefaultSensor(type)
                ?.let { sensor ->
                    sensorManager
                        .registerListener(
                            this,
                            sensor,
                            SensorManager
                                .SENSOR_DELAY_GAME
                        )
                }
        }
    }

    @Synchronized
    fun recordFrame(
        frame: Frame
    ) {
        if (closed) return

        val camera = frame.camera
        val pose = camera.pose
        val translation =
            pose.translation
        val quaternion =
            pose.rotationQuaternion

        val intrinsics:
            CameraIntrinsics =
            camera.imageIntrinsics
        val focal =
            intrinsics.focalLength
        val principal =
            intrinsics.principalPoint
        val dims =
            intrinsics.imageDimensions
        val location =
            locationTracker.latest()

        val exposureNs =
            metadataLong(
                frame,
                ImageMetadata
                    .SENSOR_EXPOSURE_TIME
            )
        val frameDurationNs =
            metadataLong(
                frame,
                ImageMetadata
                    .SENSOR_FRAME_DURATION
            )
        val rollingSkewNs =
            metadataLong(
                frame,
                ImageMetadata
                    .SENSOR_ROLLING_SHUTTER_SKEW
            )
        val iso =
            metadataInt(
                frame,
                ImageMetadata
                    .SENSOR_SENSITIVITY
            )

        if (
            exposureNs != null &&
            exposureNs > 0L
        ) {
            exposureSamples++
            exposureSumNs +=
                exposureNs.toDouble()
            if (
                exposureNs >
                exposureMaxNs
            ) {
                exposureMaxNs =
                    exposureNs
            }
        }

        if (
            iso != null &&
            iso > 0
        ) {
            isoSamples++
            isoSum +=
                iso.toDouble()
        }

        val index =
            frameCounter.incrementAndGet()

        if (
            frame.timestamp > 0L
        ) {
            if (
                firstFrameTimestampNs ==
                0L
            ) {
                firstFrameTimestampNs =
                    frame.timestamp
            }
            lastFrameTimestampNs =
                frame.timestamp
        }

        if (
            camera.trackingState.name ==
            "TRACKING"
        ) {
            trackingCounter
                .incrementAndGet()
        }

        val row =
            listOf(
                index,
                frame.timestamp,
                translation[0],
                translation[1],
                translation[2],
                quaternion[0],
                quaternion[1],
                quaternion[2],
                quaternion[3],
                camera.trackingState.name,
                focal[0],
                focal[1],
                principal[0],
                principal[1],
                dims[0],
                dims[1],
                exposureNs ?: "",
                iso ?: "",
                frameDurationNs ?: "",
                rollingSkewNs ?: "",
                location?.lat ?: "",
                location?.lon ?: "",
                location?.alt ?: "",
                location?.accuracyM ?: "",
                location?.bearingDeg ?: "",
                location?.speedMps ?: "",
                location?.timeMs ?: ""
            ).joinToString(",")

        poseWriter.write(row)
        poseWriter.newLine()

        if (
            index % 30L == 0L
        ) {
            poseWriter.flush()
        }
    }

    @Synchronized
    fun recordQrEvent(
        frameTimestampNs: Long,
        raw: String,
        corners: List<Pair<Int, Int>>,
        cameraPose: FloatArray,
        intrinsics: FloatArray,
        imageDims: IntArray,
        markerEstimate: QrPoseEstimate? = null
    ): JSONObject {
        val parsed =
            AnchorPayload.parse(raw)

        val json =
            JSONObject()
                .put(
                    "timestamp_ns",
                    frameTimestampNs
                )
                .put("raw", raw)
                .put(
                    "valid_mycon_anchor",
                    parsed != null
                )
                .put(
                    "corners_px",
                    JSONArray(
                        corners.map {
                            JSONArray(
                                listOf(
                                    it.first,
                                    it.second
                                )
                            )
                        }
                    )
                )
                .put(
                    "camera_pose_arcore_tx_ty_tz_qx_qy_qz_qw",
                    JSONArray(
                        cameraPose.toList()
                    )
                )
                .put(
                    "intrinsics_fx_fy_cx_cy",
                    JSONArray(
                        intrinsics.toList()
                    )
                )
                .put(
                    "image_wh",
                    JSONArray(
                        imageDims.toList()
                    )
                )

        if (markerEstimate != null) {
            val p =
                markerEstimate.worldPose
            val t =
                p.translation
            val q =
                p.rotationQuaternion

            json
                .put(
                    "marker_pose_arcore_tx_ty_tz_qx_qy_qz_qw",
                    JSONArray(
                        listOf(
                            t[0],
                            t[1],
                            t[2],
                            q[0],
                            q[1],
                            q[2],
                            q[3]
                        )
                    )
                )
                .put(
                    "marker_reprojection_error_px",
                    markerEstimate
                        .reprojectionErrorPx
                )
                .put(
                    "marker_distance_m",
                    markerEstimate
                        .distanceM
                )
        }

        if (parsed != null) {
            projects += parsed.project
            anchors +=
                "${parsed.project}/${parsed.anchor}"
            qrCounter.incrementAndGet()

            json.put(
                "project",
                parsed.project
            )
                .put(
                    "anchor",
                    parsed.anchor
                )
                .put(
                    "crs",
                    parsed.crs
                )
                .put(
                    "anchor_xyz_m",
                    JSONArray(
                        listOf(
                            parsed.x,
                            parsed.y,
                            parsed.z
                        )
                    )
                )
                .put(
                    "mount",
                    parsed.mount
                )
                .put(
                    "azimuth_deg",
                    parsed.azimuthDeg
                )
                .put(
                    "size_mm",
                    parsed.sizeMm
                )
                .put(
                    "floor",
                    parsed.floor
                )
                .put(
                    "model_id",
                    if (
                        parsed.modelId
                            .isBlank()
                    ) {
                        JSONObject.NULL
                    } else {
                        parsed.modelId
                    }
                )
                .put(
                    "model_size_m",
                    parsed.modelSizeM
                        ?: JSONObject.NULL
                )
        }

        qrWriter.write(
            json.toString()
        )
        qrWriter.newLine()
        qrWriter.flush()
        return json
    }

    override fun onSensorChanged(
        event: SensorEvent
    ) {
        if (closed) return

        val name =
            when (
                event.sensor.type
            ) {
                Sensor.TYPE_ACCELEROMETER ->
                    "ACCEL"
                Sensor.TYPE_GYROSCOPE ->
                    "GYRO"
                Sensor.TYPE_ROTATION_VECTOR ->
                    "ROT_VEC"
                else -> return
            }

        synchronized(this) {
            if (closed) return

            val v =
                event.values
            val v0 =
                v.getOrNull(0)
                    ?: 0f
            val v1 =
                v.getOrNull(1)
                    ?: 0f
            val v2 =
                v.getOrNull(2)
                    ?: 0f
            val v3 =
                v.getOrNull(3)
                    ?.toString()
                    ?: ""

            imuWriter.write(
                "${event.timestamp},$name,$v0,$v1,$v2,$v3,${event.accuracy}\n"
            )
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) = Unit

    @Synchronized
    fun close(
        projectHint: String?,
        mp4Name: String
    ) {
        if (closed) return
        closed = true

        sensorManager
            .unregisterListener(this)

        poseWriter.flush()
        poseWriter.close()
        imuWriter.flush()
        imuWriter.close()
        qrWriter.flush()
        qrWriter.close()

        val frames =
            frameCounter.get()
        val tracked =
            trackingCounter.get()

        val elapsedFrameNs =
            lastFrameTimestampNs -
                firstFrameTimestampNs

        val observedFps =
            if (
                frames > 1L &&
                elapsedFrameNs > 0L
            ) {
                (
                    (frames - 1L)
                        .toDouble() *
                        1_000_000_000.0
                    ) /
                    elapsedFrameNs
                        .toDouble()
            } else {
                0.0
            }

        val avgExposureMs =
            if (
                exposureSamples > 0L
            ) {
                exposureSumNs /
                    exposureSamples /
                    1_000_000.0
            } else {
                0.0
            }

        val avgIso =
            if (isoSamples > 0L) {
                isoSum /
                    isoSamples
            } else {
                0.0
            }

        val manifest =
            JSONObject()
                .put(
                    "format",
                    "MYCON_CAPTURE_SESSION"
                )
                .put(
                    "format_version",
                    1
                )
                .put(
                    "app_version",
                    "0.7.0"
                )
                .put(
                    "started_utc",
                    startedUtc
                )
                .put(
                    "ended_utc",
                    isoNow()
                )
                .put(
                    "started_elapsed_realtime_ns",
                    startedElapsedNs
                )
                .put(
                    "project_hint",
                    projectHint
                        ?: JSONObject.NULL
                )
                .put(
                    "projects_seen",
                    JSONArray(
                        projects
                            .toList()
                            .sorted()
                    )
                )
                .put(
                    "anchors_seen",
                    JSONArray(
                        anchors
                            .toList()
                            .sorted()
                    )
                )
                .put(
                    "video_dataset",
                    mp4Name
                )
                .put(
                    "pose_file",
                    "pose.csv"
                )
                .put(
                    "imu_file",
                    "imu.csv"
                )
                .put(
                    "qr_events_file",
                    "qr_events.jsonl"
                )
                .put(
                    "frame_count",
                    frames
                )
                .put(
                    "first_frame_timestamp_ns",
                    firstFrameTimestampNs
                )
                .put(
                    "last_frame_timestamp_ns",
                    lastFrameTimestampNs
                )
                .put(
                    "tracking_frame_count",
                    tracked
                )
                .put(
                    "tracking_ratio",
                    if (frames > 0L) {
                        tracked.toDouble() /
                            frames.toDouble()
                    } else {
                        0.0
                    }
                )
                .put(
                    "observed_frame_rate_fps",
                    observedFps
                )
                .put(
                    "average_exposure_ms",
                    avgExposureMs
                )
                .put(
                    "max_exposure_ms",
                    exposureMaxNs /
                        1_000_000.0
                )
                .put(
                    "average_iso",
                    avgIso
                )
                .put(
                    "valid_qr_event_count",
                    qrCounter.get()
                )
                .put(
                    "camera_selection",
                    cameraSelection
                        ?.toJson()
                        ?: JSONObject.NULL
                )
                .put(
                    "capture_controls",
                    JSONObject()
                        .put(
                            "focus_mode",
                            focusMode
                        )
                        .put(
                            "torch_enabled",
                            torchEnabled
                        )
                        .put(
                            "eis_mode",
                            "OFF"
                        )
                        .put(
                            "digital_zoom",
                            1.0
                        )
                        .put(
                            "single_camera_config_locked",
                            true
                        )
                )
                .put(
                    "sfm_hints",
                    JSONObject()
                        .put(
                            "matching",
                            "SEQUENTIAL"
                        )
                        .put(
                            "share_intrinsics_within_session",
                            true
                        )
                        .put(
                            "use_full_resolution",
                            true
                        )
                        .put(
                            "pose_prior_policy",
                            "PRIOR_NOT_GROUND_TRUTH"
                        )
                )
                .put(
                    "notes",
                    "ARCore world pose is metric but session-local. Surveyed MYCON QR control markers align it to the project coordinate system. Camera selection metadata documents the actual scientific capture profile."
                )

        File(
            sessionDir,
            "session.json"
        )
            .writeText(
                manifest.toString(2)
            )
    }

    private fun metadataLong(
        frame: Frame,
        key: Int
    ): Long? =
        runCatching {
            frame.imageMetadata
                .getLong(key)
        }.getOrNull()

    private fun metadataInt(
        frame: Frame,
        key: Int
    ): Int? =
        runCatching {
            frame.imageMetadata
                .getInt(key)
        }.getOrNull()

    companion object {
        private fun isoNow(): String {
            val format =
                SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    Locale.US
                )
            format.timeZone =
                TimeZone.getTimeZone(
                    "UTC"
                )
            return format.format(
                Date()
            )
        }
    }
}
