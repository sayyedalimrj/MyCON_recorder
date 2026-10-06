package ir.mycon.capture

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SessionSummaryActivity : AppCompatActivity() {
    private lateinit var repository: SessionRepository
    private var sessionDir: java.io.File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppSettings(this).applyTheme()
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        repository = SessionRepository(this)
        sessionDir = intent.getStringExtra(EXTRA_SESSION_PATH)?.let { java.io.File(it) }
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SessionSummaryActivity, 18), Ui.dp(this@SessionSummaryActivity, 18), Ui.dp(this@SessionSummaryActivity, 18), Ui.dp(this@SessionSummaryActivity, 18))
        }
        scroll.addView(root)
        Ui.insetWholeRoot(scroll)

        val info = sessionDir?.let { repository.readInfo(it) }
        root.addView(Ui.title(this, "خلاصه برداشت", 26f))
        root.addView(Ui.label(this, info?.startedUtc ?: "Session data unavailable", 12f))
        Ui.addSpacer(root, 16)

        if (info == null) {
            root.addView(Ui.title(this, "اطلاعات Session قابل خواندن نیست", 18f))
            setContentView(scroll)
            return
        }

        val ready = info.trackingRatio >= 0.90 && info.qrEvents > 0
        val verdictCard = Ui.card(this)
        val verdict = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SessionSummaryActivity, 18), Ui.dp(this@SessionSummaryActivity, 16), Ui.dp(this@SessionSummaryActivity, 18), Ui.dp(this@SessionSummaryActivity, 16))
        }
        verdict.addView(Ui.title(this, if (ready) "✓ برداشت قابل استفاده است" else "⚠ برداشت نیاز به توجه دارد", 20f))
        verdict.addView(Ui.label(this,
            when {
                info.qrEvents == 0L -> "هیچ Control QR معتبر داخل این Session ثبت نشده؛ برای مختصات پروژه برداشت مجدد توصیه می‌شود."
                info.trackingRatio < 0.90 -> "Tracking کمتر از 90٪ بوده؛ فایل قابل بررسی است اما Pose QA باید سخت‌گیرانه باشد."
                info.anchors.size < 3 -> "حداقل یک Control داریم. برای Zoneهای بزرگ بهتر است سه Anchor فضاییِ جدا داشته باشیم."
                else -> "Tracking و Controlها برای ورود به Pipeline مناسب‌اند."
            },
            13f
        ))
        verdictCard.addView(verdict)
        root.addView(verdictCard)

        Ui.addSpacer(root, 14)
        val metrics = Ui.card(this)
        val mc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SessionSummaryActivity, 18), Ui.dp(this@SessionSummaryActivity, 16), Ui.dp(this@SessionSummaryActivity, 18), Ui.dp(this@SessionSummaryActivity, 16))
        }
        fun metric(label: String, value: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Ui.dp(this@SessionSummaryActivity, 6), 0, Ui.dp(this@SessionSummaryActivity, 6))
            }
            row.addView(Ui.label(this, label, 14f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.title(this, value, 15f))
            mc.addView(row)
        }
        metric("Project", info.project)
        metric("Frame", info.frameCount.toString())
        metric("Tracking", SessionRepository.percent(info.trackingRatio))
        metric("QR events", info.qrEvents.toString())
        metric("Unique anchors", info.anchors.size.toString())
        metric(
            "Video stream",
            if (info.videoWidth > 0 && info.videoHeight > 0) {
                "${info.videoWidth}×${info.videoHeight} • target ≤${info.targetFpsMax}fps"
            } else {
                "legacy / unknown"
            }
        )
        metric(
            "Observed FPS",
            if (info.observedFps > 0.0) {
                String.format(java.util.Locale.US, "%.1f fps", info.observedFps)
            } else {
                "—"
            }
        )
        metric(
            "Avg exposure",
            if (info.averageExposureMs > 0.0) {
                String.format(java.util.Locale.US, "%.1f ms", info.averageExposureMs)
            } else {
                "—"
            }
        )
        metric("Camera ID", info.cameraId)
        metric("Video size", SessionRepository.humanBytes(info.videoBytes))
        metrics.addView(mc)
        root.addView(metrics)

        if (info.anchors.isNotEmpty()) {
            Ui.addSpacer(root, 14)
            root.addView(Ui.title(this, "Anchorهای دیده‌شده", 18f))
            info.anchors.forEach { root.addView(Ui.label(this, "• $it", 14f)) }
        }

        Ui.addSpacer(root, 20)
        val share = Ui.primaryButton(this, "اشتراک بسته ZIP").apply {
            setOnClickListener { repository.share(this@SessionSummaryActivity, info.dir) }
        }
        root.addView(share, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 60)))

        Ui.addSpacer(root, 10)
        val history = Ui.button(this, "مدیریت همه برداشت‌ها", Ui.SURFACE_2).apply {
            setOnClickListener { startActivity(Intent(this@SessionSummaryActivity, SessionManagerActivity::class.java)) }
        }
        root.addView(history, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)))

        Ui.addSpacer(root, 10)
        val back = Ui.button(this, "بازگشت به دوربین", Ui.SURFACE_2).apply {
            setOnClickListener { finish() }
        }
        root.addView(back, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)))

        setContentView(scroll)
    }

    companion object {
        const val EXTRA_SESSION_PATH = "session_path"
    }
}
