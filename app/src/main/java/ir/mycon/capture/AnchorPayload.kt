package ir.mycon.capture

import android.net.Uri
import java.security.MessageDigest
import java.util.Locale

data class AnchorPayload(
    val project: String,
    val anchor: String,
    val crs: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val mount: String,
    val azimuthDeg: Double,
    val sizeMm: Double,
    val floor: String = ""
) {
    fun canonical(): String = listOf(
        "v=1",
        "project=$project",
        "anchor=$anchor",
        "crs=$crs",
        "x=${fmt(x)}",
        "y=${fmt(y)}",
        "z=${fmt(z)}",
        "mount=${mount.uppercase(Locale.US)}",
        "azimuth_deg=${fmt(azimuthDeg)}",
        "size_mm=${fmt(sizeMm)}",
        "floor=$floor"
    ).joinToString("&")

    fun checksum(): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(canonical().toByteArray(Charsets.UTF_8))
        return bytes.take(6).joinToString("") { "%02x".format(it) }
    }

    fun toQrString(): String =
        Uri.Builder()
            .scheme("mycon")
            .authority("anchor")
            .appendPath("v1")
            .appendQueryParameter("project", project)
            .appendQueryParameter("anchor", anchor)
            .appendQueryParameter("crs", crs)
            .appendQueryParameter("x", fmt(x))
            .appendQueryParameter("y", fmt(y))
            .appendQueryParameter("z", fmt(z))
            .appendQueryParameter("mount", mount.uppercase(Locale.US))
            .appendQueryParameter("azimuth_deg", fmt(azimuthDeg))
            .appendQueryParameter("size_mm", fmt(sizeMm))
            .appendQueryParameter("floor", floor)
            .appendQueryParameter("sig", checksum())
            .build()
            .toString()

    companion object {
        private fun fmt(v: Double): String =
            String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')

        fun parse(raw: String): AnchorPayload? = try {
            val u = Uri.parse(raw)
            if (u.scheme != "mycon" || u.host != "anchor" || u.path != "/v1") return null

            val payload = AnchorPayload(
                project = u.getQueryParameter("project")?.trim().orEmpty(),
                anchor = u.getQueryParameter("anchor")?.trim().orEmpty(),
                crs = u.getQueryParameter("crs")?.trim().orEmpty(),
                x = u.getQueryParameter("x")?.toDouble() ?: return null,
                y = u.getQueryParameter("y")?.toDouble() ?: return null,
                z = u.getQueryParameter("z")?.toDouble() ?: return null,
                mount = u.getQueryParameter("mount")?.uppercase(Locale.US) ?: return null,
                azimuthDeg = u.getQueryParameter("azimuth_deg")?.toDouble() ?: return null,
                sizeMm = u.getQueryParameter("size_mm")?.toDouble() ?: return null,
                floor = u.getQueryParameter("floor") ?: ""
            )

            if (payload.project.isBlank() || payload.anchor.isBlank() || payload.crs.isBlank()) return null
            if (payload.mount !in setOf("VERTICAL", "HORIZONTAL")) return null
            if (!payload.sizeMm.isFinite() || payload.sizeMm <= 0.0) return null
            if (!payload.x.isFinite() || !payload.y.isFinite() || !payload.z.isFinite()) return null

            val sig = u.getQueryParameter("sig") ?: return null
            if (sig.equals(payload.checksum(), ignoreCase = true)) payload else null
        } catch (_: Exception) {
            null
        }
    }
}
