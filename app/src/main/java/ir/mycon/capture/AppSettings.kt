package ir.mycon.capture

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class AppSettings(context: Context) {
    private val prefs =
        context.getSharedPreferences("mycon_settings", Context.MODE_PRIVATE)

    var soundFeedback: Boolean
        get() = prefs.getBoolean("sound_feedback", true)
        set(value) = prefs.edit().putBoolean("sound_feedback", value).apply()

    var hapticFeedback: Boolean
        get() = prefs.getBoolean("haptic_feedback", true)
        set(value) = prefs.edit().putBoolean("haptic_feedback", value).apply()

    var gpsLogging: Boolean
        get() = prefs.getBoolean("gps_logging", true)
        set(value) = prefs.edit().putBoolean("gps_logging", value).apply()

    var autoQrScan: Boolean
        get() = prefs.getBoolean("auto_qr_scan", true)
        set(value) = prefs.edit().putBoolean("auto_qr_scan", value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    var depthLogging: Boolean
        get() = prefs.getBoolean("depth_logging", true)
        set(value) = prefs.edit().putBoolean("depth_logging", value).apply()

    var qualityWarnings: Boolean
        get() = prefs.getBoolean("quality_warnings", true)
        set(value) = prefs.edit().putBoolean("quality_warnings", value).apply()

    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) = prefs.edit().putString("theme", value).apply()

    var captureProfile: String
        get() =
            prefs.getString(
                "capture_profile",
                CaptureProfile.SCIENTIFIC_HQ30.key
            ) ?: CaptureProfile.SCIENTIFIC_HQ30.key
        set(value) =
            prefs.edit()
                .putString(
                    "capture_profile",
                    value
                )
                .apply()

    var focusMode: String
        get() =
            prefs.getString(
                "focus_mode",
                "auto"
            ) ?: "auto"
        set(value) =
            prefs.edit()
                .putString(
                    "focus_mode",
                    value
                )
                .apply()

    var torchEnabled: Boolean
        get() =
            prefs.getBoolean(
                "torch_enabled",
                false
            )
        set(value) =
            prefs.edit()
                .putBoolean(
                    "torch_enabled",
                    value
                )
                .apply()

    var lastProject: String
        get() = prefs.getString("last_project", "") ?: ""
        set(value) = prefs.edit().putString("last_project", value).apply()

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
