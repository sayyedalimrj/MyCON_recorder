package ir.mycon.capture

import android.media.Image
import com.google.ar.core.Frame
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.nio.ByteOrder
import java.util.Locale

class DepthRecorder(
    private val sessionDir: File,
    private val enabled: Boolean
) {
    private val depthDir =
        File(sessionDir, "depth").apply {
            if (enabled) mkdirs()
        }

    private val indexFile =
        File(depthDir, "depth_index.jsonl")

    private val writer: BufferedWriter? =
        if (enabled) {
            BufferedWriter(FileWriter(indexFile, false))
        } else {
            null
        }

    private var lastDepthTimestampNs = Long.MIN_VALUE
    private var sampleCount = 0
    private var closed = false

    @Synchronized
    fun recordFrame(frame: Frame) {
        if (!enabled || closed) return

        try {
            frame.acquireRawDepthImage16Bits().use { depth ->
                if (depth.timestamp == lastDepthTimestampNs) {
                    return
                }

                frame.acquireRawDepthConfidenceImage().use { confidence ->
                    lastDepthTimestampNs = depth.timestamp
                    sampleCount++

                    val stem =
                        String.format(
                            Locale.US,
                            "depth_%06d",
                            sampleCount
                        )

                    val depthFile =
                        File(depthDir, "${stem}.d16")
                    val confidenceFile =
                        File(depthDir, "${stem}.u8")

                    writePacked(
                        image = depth,
                        output = depthFile,
                        bytesPerPixel = 2
                    )
                    writePacked(
                        image = confidence,
                        output = confidenceFile,
                        bytesPerPixel = 1
                    )

                    val camera = frame.camera
                    val pose = camera.pose
                    val t = pose.translation
                    val q = pose.rotationQuaternion
                    val intrinsics =
                        camera.imageIntrinsics
                    val focal =
                        intrinsics.focalLength
                    val principal =
                        intrinsics.principalPoint

                    val row =
                        JSONObject()
                            .put(
                                "frame_timestamp_ns",
                                frame.timestamp
                            )
                            .put(
                                "depth_timestamp_ns",
                                depth.timestamp
                            )
                            .put(
                                "depth_file",
                                "depth/${depthFile.name}"
                            )
                            .put(
                                "confidence_file",
                                "depth/${confidenceFile.name}"
                            )
                            .put(
                                "width",
                                depth.width
                            )
                            .put(
                                "height",
                                depth.height
                            )
                            .put(
                                "depth_encoding",
                                "uint16_little_endian_mm"
                            )
                            .put(
                                "confidence_encoding",
                                "uint8_0_255"
                            )
                            .put(
                                "intrinsics_fx_fy_cx_cy",
                                JSONArray(
                                    listOf(
                                        focal[0],
                                        focal[1],
                                        principal[0],
                                        principal[1]
                                    )
                                )
                            )
                            .put(
                                "camera_pose_arcore_tx_ty_tz_qx_qy_qz_qw",
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

                    writer?.write(row.toString())
                    writer?.newLine()
                    writer?.flush()
                }
            }
        } catch (_: Exception) {
            // Depth may be unavailable while tracking, motion or lighting settles.
        }
    }

    @Synchronized
    fun closeAndAttachToSession() {
        if (closed) return
        closed = true

        runCatching {
            writer?.flush()
            writer?.close()
        }

        val summary =
            JSONObject()
                .put(
                    "schema",
                    "mycon.capture.depth.v1"
                )
                .put(
                    "enabled",
                    enabled
                )
                .put(
                    "sample_count",
                    sampleCount
                )
                .put(
                    "index",
                    if (enabled) {
                        "depth/depth_index.jsonl"
                    } else {
                        JSONObject.NULL
                    }
                )
                .put(
                    "depth_encoding",
                    if (enabled) {
                        "uint16_little_endian_mm"
                    } else {
                        JSONObject.NULL
                    }
                )
                .put(
                    "confidence_encoding",
                    if (enabled) {
                        "uint8_0_255"
                    } else {
                        JSONObject.NULL
                    }
                )

        File(
            sessionDir,
            "depth_summary.json"
        ).writeText(
            summary.toString(2)
        )

        val sessionFile =
            File(sessionDir, "session.json")

        if (sessionFile.isFile) {
            runCatching {
                val session =
                    JSONObject(
                        sessionFile.readText()
                    )
                session.put(
                    "depth_capture",
                    summary
                )
                sessionFile.writeText(
                    session.toString(2)
                )
            }
        }
    }

    private fun writePacked(
        image: Image,
        output: File,
        bytesPerPixel: Int
    ) {
        val plane =
            image.planes[0]
        val source =
            plane.buffer.duplicate()
                .order(
                    ByteOrder.LITTLE_ENDIAN
                )
        val rowStride =
            plane.rowStride
        val pixelStride =
            plane.pixelStride

        val packed =
            ByteArray(
                image.width *
                    image.height *
                    bytesPerPixel
            )

        var out = 0
        for (y in 0 until image.height) {
            val rowStart =
                y * rowStride
            for (x in 0 until image.width) {
                val pos =
                    rowStart +
                        x * pixelStride

                for (
                    b in
                    0 until bytesPerPixel
                ) {
                    packed[out++] =
                        source.get(
                            pos + b
                        )
                }
            }
        }

        output.writeBytes(
            packed
        )
    }
}
