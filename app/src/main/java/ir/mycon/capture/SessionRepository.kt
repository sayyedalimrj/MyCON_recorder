package ir.mycon.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.util.Locale

data class SessionInfo(
    val dir: File,
    val name: String,
    val project: String,
    val startedUtc: String,
    val frameCount: Long,
    val trackingFrames: Long,
    val trackingRatio: Double,
    val qrEvents: Long,
    val anchors: List<String>,
    val videoBytes: Long,
    val videoWidth: Int,
    val videoHeight: Int,
    val targetFpsMax: Int,
    val observedFps: Double,
    val averageExposureMs: Double,
    val cameraId: String
)

class SessionRepository(private val context: Context) {
    val sessionsDir: File
        get() = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "sessions"
        ).apply { mkdirs() }

    fun listSessions(): List<SessionInfo> =
        sessionsDir.listFiles()
            ?.filter { it.isDirectory && File(it, "session.json").exists() }
            ?.sortedByDescending { it.name }
            ?.mapNotNull { readInfo(it) }
            ?: emptyList()

    fun latest(): SessionInfo? = listSessions().firstOrNull()

    fun readInfo(dir: File): SessionInfo? = try {
        val manifest = JSONObject(File(dir, "session.json").readText())
        val anchorsJson = manifest.optJSONArray("anchors_seen")
        val anchors = mutableListOf<String>()
        if (anchorsJson != null) {
            for (i in 0 until anchorsJson.length()) {
                anchors += anchorsJson.optString(i)
            }
        }
        val mp4Name = manifest.optString("video_dataset", "arcore_recording.mp4")
        val video = File(dir, mp4Name)
        val camera =
            manifest.optJSONObject("camera_selection")
        SessionInfo(
            dir = dir,
            name = dir.name,
            project = manifest.optString("project_hint", "—").ifBlank { "—" },
            startedUtc = manifest.optString("started_utc", ""),
            frameCount = manifest.optLong("frame_count", 0),
            trackingFrames = manifest.optLong("tracking_frame_count", 0),
            trackingRatio = manifest.optDouble("tracking_ratio", 0.0),
            qrEvents = manifest.optLong("valid_qr_event_count", 0),
            anchors = anchors,
            videoBytes = if (video.exists()) video.length() else 0,
            videoWidth = camera?.optInt("cpu_image_width", 0) ?: 0,
            videoHeight = camera?.optInt("cpu_image_height", 0) ?: 0,
            targetFpsMax = camera?.optInt("fps_max", 0) ?: 0,
            observedFps = manifest.optDouble("observed_frame_rate_fps", 0.0),
            averageExposureMs = manifest.optDouble("average_exposure_ms", 0.0),
            cameraId = camera?.optString("camera_id", "—") ?: "—"
        )
    } catch (_: Exception) {
        null
    }

    fun ensureZip(dir: File): File {
        val out = File(sessionsDir, "${dir.name}_MYCON.zip")
        if (!out.exists() || out.lastModified() < dir.lastModified()) {
            SessionPackager.zip(dir, out)
        }
        return out
    }

    fun share(activity: Activity, dir: File) {
        val zip = ensureZip(dir)
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.files",
            zip
        )
        activity.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "ارسال بسته برداشت"
            )
        )
    }

    fun delete(dir: File): Boolean {
        File(sessionsDir, "${dir.name}_MYCON.zip").delete()
        return dir.deleteRecursively()
    }

    companion object {
        fun humanBytes(bytes: Long): String {
            if (bytes <= 0) return "0 MB"
            val mb = bytes / 1024.0 / 1024.0
            return if (mb < 1024) {
                String.format(Locale.US, "%.1f MB", mb)
            } else {
                String.format(Locale.US, "%.2f GB", mb / 1024.0)
            }
        }

        fun percent(value: Double): String =
            NumberFormat.getPercentInstance(Locale.US).apply {
                maximumFractionDigits = 1
            }.format(value.coerceIn(0.0, 1.0))
    }
}
