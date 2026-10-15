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
    val cameraId: String,
    val totalBytes: Long = 0,
    val fileCount: Int = 0,
    val hasManifest: Boolean = false
)

class SessionRepository(private val context: Context) {
    // Preserve the exact legacy location. Do not rename or migrate it.
    val sessionsDir: File
        get() = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "sessions"
        ).apply { mkdirs() }

    private fun roots(): List<File> =
        listOf(sessionsDir, File(context.filesDir, "sessions"))
            .distinctBy { it.absolutePath }
            .filter { it.isDirectory }

    fun isSessionFolder(dir: File): Boolean {
        val candidate = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        return roots().any { root ->
            candidate.parentFile == runCatching { root.canonicalFile }.getOrNull()
        }
    }

    fun listSessions(): List<SessionInfo> =
        roots().flatMap { root ->
            root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        }.distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { readInfo(it) }

    fun latest(): SessionInfo? = listSessions().firstOrNull()

    /** Also exposes unfinished recordings without session.json, never modifies them. */
    fun readInfo(dir: File): SessionInfo? {
        if (!isSessionFolder(dir)) return null
        val sources = runCatching { SessionPackager.sourceFiles(dir) }.getOrNull()
            ?: return null
        if (sources.isEmpty()) return null
        val manifest = runCatching {
            JSONObject(File(dir, "session.json").readText())
        }.getOrNull()
        val anchorsJson = manifest?.optJSONArray("anchors_seen")
        val anchors = mutableListOf<String>()
        if (anchorsJson != null) {
            for (i in 0 until anchorsJson.length()) anchors += anchorsJson.optString(i)
        }
        val name = manifest?.optString("video_dataset", "arcore_recording.mp4")
            ?.takeIf { !it.contains('/') && !it.contains('\\') }
            ?: "arcore_recording.mp4"
        val video = File(dir, name).takeIf { it.isFile }
            ?: sources.firstOrNull { it.path.endsWith(".mp4", ignoreCase = true) }?.file
        val camera = manifest?.optJSONObject("camera_selection")
        return SessionInfo(
            dir = dir,
            name = dir.name,
            project = manifest?.optString("project_hint", "برداشت بازیابی")?.ifBlank { "برداشت بازیابی" }
                ?: "برداشت بازیابی",
            startedUtc = manifest?.optString("started_utc", "") ?: "",
            frameCount = manifest?.optLong("frame_count", 0) ?: 0,
            trackingFrames = manifest?.optLong("tracking_frame_count", 0) ?: 0,
            trackingRatio = manifest?.optDouble("tracking_ratio", 0.0) ?: 0.0,
            qrEvents = manifest?.optLong("valid_qr_event_count", 0) ?: 0,
            anchors = anchors,
            videoBytes = video?.length() ?: 0,
            videoWidth = camera?.optInt("cpu_image_width", 0) ?: 0,
            videoHeight = camera?.optInt("cpu_image_height", 0) ?: 0,
            targetFpsMax = camera?.optInt("fps_max", 0) ?: 0,
            observedFps = manifest?.optDouble("observed_frame_rate_fps", 0.0) ?: 0.0,
            averageExposureMs = manifest?.optDouble("average_exposure_ms", 0.0) ?: 0.0,
            cameraId = camera?.optString("camera_id", "—") ?: "—",
            totalBytes = sources.sumOf { it.size },
            fileCount = sources.size,
            hasManifest = manifest?.optString("format") == "MYCON_CAPTURE_SESSION"
        )
    }

    /** Never reuse an old ZIP; avoid accidental deletion of a prior export. */
    fun ensureZip(dir: File): File {
        require(isSessionFolder(dir)) { "Unknown recording folder" }
        val out = File(sessionsDir, "${dir.name}_MYCON_recovery_${System.currentTimeMillis()}.zip")
        return SessionPackager.zip(dir, out)
    }

    /** Legacy share uses a background thread for large sessions. */
    fun share(activity: Activity, dir: File) {
        Thread {
            runCatching { ensureZip(dir) }.onSuccess { zip ->
                activity.runOnUiThread {
                    val uri = FileProvider.getUriForFile(
                        activity, "${activity.packageName}.files", zip
                    )
                    activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "ارسال نسخه بازیابی"))
                }
            }.onFailure { e ->
                activity.runOnUiThread {
                    android.widget.Toast.makeText(activity,
                        "ZIP ساخته نشد: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    fun delete(dir: File): Boolean {
        // Destructive cleanup is deliberately disabled in the recovery release.
        return false
    }

    companion object {
        fun humanBytes(bytes: Long): String {
            if (bytes <= 0) return "0 MB"
            val mb = bytes / 1048576.0
            return if (mb < 1024) String.format(Locale.US, "%.1f MB", mb)
            else String.format(Locale.US, "%.2f GB", mb / 1024.0)
        }
        fun percent(value: Double): String =
            NumberFormat.getPercentInstance(Locale.US).apply {
                maximumFractionDigits = 1
            }.format(value.coerceIn(0.0, 1.0))
    }
}
