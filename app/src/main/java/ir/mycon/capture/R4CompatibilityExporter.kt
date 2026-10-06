package ir.mycon.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object R4CompatibilityExporter {

    fun write(
        context: Context,
        sessionDir: File
    ) {
        val sessionFile =
            File(
                sessionDir,
                "session.json"
            )
        if (!sessionFile.isFile) {
            return
        }

        val manifest =
            JSONObject(
                sessionFile.readText()
            )

        val poseFile =
            File(
                sessionDir,
                "pose.csv"
            )
        val qrFile =
            File(
                sessionDir,
                "qr_events.jsonl"
            )

        val firstPose =
            poseFile
                .takeIf {
                    it.isFile
                }
                ?.useLines { lines ->
                    lines.drop(1)
                        .firstOrNull {
                            it.isNotBlank()
                        }
                }
                ?.split(",")

        val cameraJson =
            JSONObject()
                .put(
                    "schema",
                    "mycon.r4.camera.v1"
                )
                .put(
                    "camera_selection",
                    manifest.optJSONObject(
                        "camera_selection"
                    ) ?: JSONObject.NULL
                )
                .put(
                    "capture_controls",
                    manifest.optJSONObject(
                        "capture_controls"
                    ) ?: JSONObject.NULL
                )

        if (
            firstPose != null &&
            firstPose.size >= 16
        ) {
            cameraJson
                .put(
                    "first_frame_intrinsics",
                    JSONObject()
                        .put(
                            "fx",
                            firstPose[10]
                                .toDoubleOrNull()
                                ?: JSONObject.NULL
                        )
                        .put(
                            "fy",
                            firstPose[11]
                                .toDoubleOrNull()
                                ?: JSONObject.NULL
                        )
                        .put(
                            "cx",
                            firstPose[12]
                                .toDoubleOrNull()
                                ?: JSONObject.NULL
                        )
                        .put(
                            "cy",
                            firstPose[13]
                                .toDoubleOrNull()
                                ?: JSONObject.NULL
                        )
                        .put(
                            "width",
                            firstPose[14]
                                .toIntOrNull()
                                ?: JSONObject.NULL
                        )
                        .put(
                            "height",
                            firstPose[15]
                                .toIntOrNull()
                                ?: JSONObject.NULL
                        )
                )
        }

        File(
            sessionDir,
            "r4_camera.json"
        ).writeText(
            cameraJson.toString(2)
        )

        val controls =
            linkedMapOf<
                String,
                JSONObject
                >()

        if (qrFile.isFile) {
            qrFile.forEachLine { line ->
                if (
                    line.isBlank()
                ) {
                    return@forEachLine
                }

                val row =
                    runCatching {
                        JSONObject(line)
                    }.getOrNull()
                        ?: return@forEachLine

                if (
                    !row.optBoolean(
                        "valid_mycon_anchor",
                        false
                    )
                ) {
                    return@forEachLine
                }

                val project =
                    row.optString(
                        "project"
                    )
                val anchor =
                    row.optString(
                        "anchor"
                    )

                if (
                    project.isBlank() ||
                    anchor.isBlank()
                ) {
                    return@forEachLine
                }

                val markerPose =
                    row.optJSONArray(
                        "marker_pose_arcore_tx_ty_tz_qx_qy_qz_qw"
                    )

                if (
                    markerPose == null ||
                    markerPose.length() < 7
                ) {
                    return@forEachLine
                }

                val key =
                    "$project/$anchor"

                val error =
                    row.optDouble(
                        "marker_reprojection_error_px",
                        Double.POSITIVE_INFINITY
                    )

                val previous =
                    controls[key]

                val previousError =
                    previous?.optDouble(
                        "marker_reprojection_error_px",
                        Double.POSITIVE_INFINITY
                    )
                        ?: Double.POSITIVE_INFINITY

                if (
                    previous == null ||
                    error <
                    previousError
                ) {
                    controls[key] =
                        row
                }
            }
        }

        val controlRows =
            JSONArray()

        controls
            .toSortedMap()
            .forEach {
                    (key, row) ->
                val reprojectionError =
                    row.optDouble(
                        "marker_reprojection_error_px",
                        Double.POSITIVE_INFINITY
                    )

                controlRows.put(
                    JSONObject()
                        .put(
                            "id",
                            key
                        )
                        .put(
                            "project",
                            row.optString(
                                "project"
                            )
                        )
                        .put(
                            "anchor",
                            row.optString(
                                "anchor"
                            )
                        )
                        .put(
                            "crs",
                            row.optString(
                                "crs"
                            )
                        )
                        .put(
                            "target_project_xyz_m",
                            row.optJSONArray(
                                "anchor_xyz_m"
                            ) ?: JSONObject.NULL
                        )
                        .put(
                            "source_arcore_xyz_m",
                            row.optJSONArray(
                                "marker_pose_arcore_tx_ty_tz_qx_qy_qz_qw"
                            )?.let {
                                JSONArray()
                                    .put(
                                        it.optDouble(
                                            0
                                        )
                                    )
                                    .put(
                                        it.optDouble(
                                            1
                                        )
                                    )
                                    .put(
                                        it.optDouble(
                                            2
                                        )
                                    )
                            } ?: JSONObject.NULL
                        )
                        .put(
                            "marker_pose_arcore",
                            row.optJSONArray(
                                "marker_pose_arcore_tx_ty_tz_qx_qy_qz_qw"
                            ) ?: JSONObject.NULL
                        )
                        .put(
                            "reprojection_error_px",
                            if (
                                reprojectionError.isFinite()
                            ) {
                                reprojectionError
                            } else {
                                JSONObject.NULL
                            }
                        )
                        .put(
                            "timestamp_ns",
                            row.optLong(
                                "timestamp_ns"
                            )
                        )
                )
            }

        File(
            sessionDir,
            "r4_controls.json"
        ).writeText(
            JSONObject()
                .put(
                    "schema",
                    "mycon.r4.capture_controls.v1"
                )
                .put(
                    "selection",
                    "best_reprojection_observation_per_control"
                )
                .put(
                    "controls",
                    controlRows
                )
                .toString(2)
        )

        val controlCount =
            controls.size

        val compatibility =
            JSONObject()
                .put(
                    "schema",
                    "mycon.r4.capture_compatibility.v1"
                )
                .put(
                    "app_version",
                    "0.9.0"
                )
                .put(
                    "pipeline_family",
                    "MyCon R4 v4.8.x"
                )
                .put(
                    "preserve_scientific_order",
                    true
                )
                .put(
                    "files",
                    JSONObject()
                        .put(
                            "video",
                            manifest.optString(
                                "video_dataset",
                                "arcore_recording.mp4"
                            )
                        )
                        .put(
                            "pose_trace",
                            "pose.csv"
                        )
                        .put(
                            "imu",
                            "imu.csv"
                        )
                        .put(
                            "qr_events",
                            "qr_events.jsonl"
                        )
                        .put(
                            "camera",
                            "r4_camera.json"
                        )
                        .put(
                            "controls",
                            "r4_controls.json"
                        )
                        .put(
                            "bridge_tool",
                            "tools/mycon_r4_bridge.py"
                        )
                )
                .put(
                    "stage2_contract",
                    JSONObject()
                        .put(
                            "keyframe_name_pattern",
                            "frame_%08d.jpg"
                        )
                        .put(
                            "source",
                            "source_video_frame_number"
                        )
                        .put(
                            "do_not_rename",
                            true
                        )
                )
                .put(
                    "stage4_pose_validator",
                    JSONObject()
                        .put(
                            "supported",
                            true
                        )
                        .put(
                            "generated_after_stage2",
                            "pose_validator.json"
                        )
                        .put(
                            "minimum_common_frames",
                            6
                        )
                        .put(
                            "policy",
                            "VALIDATE_ONLY_NEVER_AVERAGE"
                        )
                )
                .put(
                    "stage8_metric_alignment",
                    JSONObject()
                        .put(
                            "supported",
                            true
                        )
                        .put(
                            "generated_after_colmap",
                            "stage8_anchors.json"
                        )
                        .put(
                            "captured_unique_controls",
                            controlCount
                        )
                        .put(
                            "minimum_fit_controls",
                            4
                        )
                        .put(
                            "recommended_holdout_controls",
                            3
                        )
                        .put(
                            "capture_ready",
                            controlCount >= 4
                        )
                )
                .put(
                    "colmap",
                    JSONObject()
                        .put(
                            "matching",
                            "SEQUENTIAL"
                        )
                        .put(
                            "single_camera_group_per_session",
                            true
                        )
                        .put(
                            "share_intrinsics_within_session",
                            true
                        )
                        .put(
                            "camera_model_policy",
                            "DO_NOT_FORCE; preserve MyCon R4 model-hypothesis selection"
                        )
                        .put(
                            "pose_priors",
                            "OPTIONAL_POSITION_PRIORS_ONLY"
                        )
                        .put(
                            "pose_priors_default_sigma_m",
                            0.10
                        )
                )
                .put(
                    "notes",
                    JSONArray()
                        .put(
                            "ARCore pose is a metric session-local prior, never survey ground truth."
                        )
                        .put(
                            "Surveyed MYCON QR coordinates are the metric/project reference."
                        )
                        .put(
                            "Bundle adjustment and MyCon Stage-4 validation remain authoritative."
                        )
                )

        File(
            sessionDir,
            "r4_compatibility.json"
        ).writeText(
            compatibility.toString(2)
        )

        val toolDir =
            File(
                sessionDir,
                "tools"
            ).apply {
                mkdirs()
            }

        runCatching {
            context.assets.open(
                "mycon_r4_bridge.py"
            ).use { input ->
                File(
                    toolDir,
                    "mycon_r4_bridge.py"
                )
                    .outputStream()
                    .use {
                        output ->
                        input.copyTo(
                            output
                        )
                    }
            }
        }

        File(
            sessionDir,
            "R4_IMPORT_README.txt"
        ).writeText(
            """
MYCON RECORDER -> MYCON R4

1) Use arcore_recording.mp4 as the normal MyCon video input.
2) Run Stage 1/2 normally. Do NOT change MyCon scientific stage order.
3) After Stage 2, run:
   python tools/mycon_r4_bridge.py <SESSION_DIR_OR_ZIP> --stage2-report <STAGE2_REPORT_OR_KEYFRAME_DIR> --output <BRIDGE_DIR>
4) Set POSE_VALIDATOR_JSON_INPUT to <BRIDGE_DIR>/pose_validator.json for Stage 4 validation.
5) After sparse/refined COLMAP exists, run the bridge again with --colmap-text-model <SOURCE_TXT_OR_IMAGES_TXT>.
6) If stage8_anchors.json reports READY, set ANCHORS_JSON_INPUT to that file for Stage 8.
7) Optional colmap_pose_priors.json is generated for experiments only. The default MyCon Stage-3 mapper is NOT silently replaced.

Stage 8 requires at least 4 calibration controls for the current MyCon metric gate.
7+ controls allow the bridge to reserve 3 independent holdout controls.
            """.trimIndent()
        )

        writeIntegrityManifest(
            sessionDir
        )
    }

    private fun writeIntegrityManifest(
        sessionDir: File
    ) {
        val rows =
            JSONArray()

        sessionDir.walkTopDown()
            .filter {
                it.isFile &&
                    it.name !=
                    "integrity_sha256.json"
            }
            .sortedBy {
                it.relativeTo(
                    sessionDir
                ).path
            }
            .forEach {
                    file ->
                val deferredBinary =
                    file.extension
                        .lowercase() in
                        setOf(
                            "mp4",
                            "d16",
                            "u8",
                            "f32"
                        )

                rows.put(
                    JSONObject()
                        .put(
                            "path",
                            file.relativeTo(
                                sessionDir
                            )
                                .path
                                .replace(
                                    '\\',
                                    '/'
                                )
                        )
                        .put(
                            "size_bytes",
                            file.length()
                        )
                        .put(
                            "sha256",
                            if (deferredBinary) {
                                JSONObject.NULL
                            } else {
                                sha256(
                                    file
                                )
                            }
                        )
                        .put(
                            "hash_policy",
                            if (deferredBinary) {
                                "DEFERRED_LARGE_BINARY"
                            } else {
                                "SHA256"
                            }
                        )
                )
            }

        File(
            sessionDir,
            "integrity_sha256.json"
        ).writeText(
            JSONObject()
                .put(
                    "schema",
                    "mycon.capture.integrity.v1"
                )
                .put(
                    "files",
                    rows
                )
                .toString(2)
        )
    }

    private fun sha256(
        file: File
    ): String {
        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )

        FileInputStream(file).use {
                input ->
            val buffer =
                ByteArray(
                    1024 * 1024
                )
            while (true) {
                val n =
                    input.read(
                        buffer
                    )
                if (n <= 0) {
                    break
                }
                digest.update(
                    buffer,
                    0,
                    n
                )
            }
        }

        return digest
            .digest()
            .joinToString("") {
                "%02x".format(it)
            }
    }
}
