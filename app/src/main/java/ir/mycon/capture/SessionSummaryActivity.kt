package ir.mycon.capture

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.util.Locale

class SessionSummaryActivity : AppCompatActivity() {
    private lateinit var repository:
        SessionRepository
    private var sessionDir:
        File? = null

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        AppSettings(this)
            .applyTheme()
        super.onCreate(
            savedInstanceState
        )
        Ui.edgeToEdge(this)
        repository =
            SessionRepository(this)
        sessionDir =
            intent.getStringExtra(
                EXTRA_SESSION_PATH
            )?.let {
                File(it)
            }
        buildUi()
    }

    private fun buildUi() {
        val scroll =
            ScrollView(this)

        val outer =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                gravity =
                    Gravity.TOP or
                        Gravity.CENTER_HORIZONTAL
                setPadding(
                    Ui.dp(
                        this@SessionSummaryActivity,
                        16
                    ),
                    Ui.dp(
                        this@SessionSummaryActivity,
                        16
                    ),
                    Ui.dp(
                        this@SessionSummaryActivity,
                        16
                    ),
                    Ui.dp(
                        this@SessionSummaryActivity,
                        24
                    )
                )
            }
        scroll.addView(outer)
        Ui.insetWholeRoot(scroll)

        val maxWidth =
            if (
                resources.configuration
                    .screenWidthDp >=
                600
            ) {
                Ui.dp(
                    this,
                    560
                )
            } else {
                LinearLayout
                    .LayoutParams
                    .MATCH_PARENT
            }

        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        outer.addView(
            root,
            LinearLayout.LayoutParams(
                maxWidth,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT
            )
        )

        val info =
            sessionDir?.let {
                repository.readInfo(
                    it
                )
            }

        root.addView(
            Ui.title(
                this,
                "برداشت ذخیره شد",
                24f
            )
        )
        root.addView(
            Ui.label(
                this,
                info?.startedUtc
                    ?: "Session data unavailable",
                11.5f
            )
        )
        Ui.addSpacer(
            root,
            14
        )

        if (info == null) {
            root.addView(
                Ui.title(
                    this,
                    "اطلاعات Session قابل خواندن نیست",
                    17f
                )
            )
            setContentView(scroll)
            return
        }

        val compatibility =
            runCatching {
                JSONObject(
                    File(
                        info.dir,
                        "r4_compatibility.json"
                    ).readText()
                )
            }.getOrNull()

        val stage8 =
            compatibility
                ?.optJSONObject(
                    "stage8_metric_alignment"
                )

        val uniqueControls =
            stage8
                ?.optInt(
                    "captured_unique_controls",
                    info.anchors.size
                )
                ?: info.anchors.size

        val trackingGood =
            info.trackingRatio >=
                0.90

        val realityReady =
            info.frameCount > 0L &&
                trackingGood

        val hero =
            Ui.card(this)
        val heroBox =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(
                        this@SessionSummaryActivity,
                        16
                    ),
                    Ui.dp(
                        this@SessionSummaryActivity,
                        14
                    ),
                    Ui.dp(
                        this@SessionSummaryActivity,
                        16
                    ),
                    Ui.dp(
                        this@SessionSummaryActivity,
                        14
                    )
                )
            }

        heroBox.addView(
            Ui.title(
                this,
                if (realityReady) {
                    "✓ Capture سالم"
                } else {
                    "⚠ Capture نیاز به بررسی دارد"
                },
                18f
            )
        )

        heroBox.addView(
            Ui.label(
                this,
                when {
                    !trackingGood ->
                        "Tracking کمتر از 90٪ است؛ قبل از Dense باید Sparse/Stage 4 سخت‌گیرانه بررسی شود."
                    uniqueControls == 0 ->
                        "Reality 1–7 قابل اجراست، اما مرجع متریک پروژه هنوز Control ندارد."
                    uniqueControls < 4 ->
                        "Pose/Reality آماده است؛ برای Stage 8 فعلی MyCON حداقل 4 Control متریک لازم داریم."
                    else ->
                        "بسته برای Reality 1–7 و ساخت Bridge مرحله‌های 4 و 8 آماده است."
                },
                12f
            )
        )
        hero.addView(
            heroBox
        )
        root.addView(hero)

        Ui.addSpacer(
            root,
            10
        )

        val pipeline =
            Ui.card(this)
        val pipelineBox =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(
                        this@SessionSummaryActivity,
                        14
                    ),
                    Ui.dp(
                        this@SessionSummaryActivity,
                        12
                    ),
                    Ui.dp(
                        this@SessionSummaryActivity,
                        14
                    ),
                    Ui.dp(
                        this@SessionSummaryActivity,
                        12
                    )
                )
            }

        pipelineBox.addView(
            Ui.title(
                this,
                "MyCON R4 readiness",
                16f
            )
        )
        Ui.addSpacer(
            pipelineBox,
            7
        )

        readinessRow(
            pipelineBox,
            "Reality 1–7",
            if (realityReady) {
                "READY"
            } else {
                "REVIEW"
            },
            if (realityReady) {
                Ui.GREEN
            } else {
                Ui.AMBER
            }
        )
        readinessRow(
            pipelineBox,
            "Stage 4 Pose Validator",
            "بعد از Stage 2",
            Ui.BLUE
        )
        readinessRow(
            pipelineBox,
            "Stage 8 Metric",
            "$uniqueControls / 4 Control",
            if (
                uniqueControls >= 4
            ) {
                Ui.GREEN
            } else {
                Ui.AMBER
            }
        )
        readinessRow(
            pipelineBox,
            "Independent holdout",
            if (
                uniqueControls >= 7
            ) {
                "3 Control آماده"
            } else {
                "پیشنهاد: 7+ Control"
            },
            if (
                uniqueControls >= 7
            ) {
                Ui.GREEN
            } else {
                Ui.MUTED
            }
        )

        pipeline.addView(
            pipelineBox
        )
        root.addView(
            pipeline
        )

        Ui.addSpacer(
            root,
            10
        )

        val metrics =
            Ui.card(this)
        val metricsBox =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(
                        this@SessionSummaryActivity,
                        14
                    ),
                    Ui.dp(
                        this@SessionSummaryActivity,
                        10
                    ),
                    Ui.dp(
                        this@SessionSummaryActivity,
                        14
                    ),
                    Ui.dp(
                        this@SessionSummaryActivity,
                        10
                    )
                )
            }

        metric(
            metricsBox,
            "Project",
            info.project
        )
        metric(
            metricsBox,
            "Tracking",
            SessionRepository.percent(
                info.trackingRatio
            )
        )
        metric(
            metricsBox,
            "Controls",
            uniqueControls.toString()
        )
        metric(
            metricsBox,
            "Video",
            if (
                info.videoWidth > 0 &&
                info.videoHeight > 0
            ) {
                "${info.videoWidth}×${info.videoHeight}"
            } else {
                "legacy"
            }
        )
        metric(
            metricsBox,
            "FPS",
            if (
                info.observedFps > 0.0
            ) {
                String.format(
                    Locale.US,
                    "%.1f",
                    info.observedFps
                )
            } else {
                "—"
            }
        )
        metric(
            metricsBox,
            "Exposure",
            if (
                info.averageExposureMs >
                0.0
            ) {
                String.format(
                    Locale.US,
                    "%.1f ms",
                    info.averageExposureMs
                )
            } else {
                "—"
            }
        )
        metric(
            metricsBox,
            "Package video",
            SessionRepository
                .humanBytes(
                    info.videoBytes
                )
        )

        metrics.addView(
            metricsBox
        )
        root.addView(metrics)

        Ui.addSpacer(
            root,
            16
        )

        root.addView(
            Ui.primaryButton(
                this,
                "اشتراک بسته MyCON R4"
            ).apply {
                setOnClickListener {
                    repository.share(
                        this@SessionSummaryActivity,
                        info.dir
                    )
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams
                    .MATCH_PARENT,
                Ui.dp(
                    this,
                    54
                )
            )
        )

        Ui.addSpacer(
            root,
            8
        )

        val secondary =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        val history =
            Ui.button(
                this,
                "برداشت‌ها",
                Ui.SURFACE_2
            ).apply {
                setOnClickListener {
                    startActivity(
                        Intent(
                            this@SessionSummaryActivity,
                            SessionManagerActivity::class.java
                        )
                    )
                }
            }

        val back =
            Ui.button(
                this,
                "دوربین",
                Ui.SURFACE_2
            ).apply {
                setOnClickListener {
                    finish()
                }
            }

        secondary.addView(
            history,
            LinearLayout.LayoutParams(
                0,
                Ui.dp(
                    this,
                    48
                ),
                1f
            ).apply {
                marginEnd =
                    Ui.dp(
                        this@SessionSummaryActivity,
                        6
                    )
            }
        )
        secondary.addView(
            back,
            LinearLayout.LayoutParams(
                0,
                Ui.dp(
                    this,
                    48
                ),
                1f
            )
        )
        root.addView(
            secondary
        )

        setContentView(scroll)
    }

    private fun readinessRow(
        parent: LinearLayout,
        label: String,
        value: String,
        color: Int
    ) {
        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL
                setPadding(
                    0,
                    Ui.dp(
                        this@SessionSummaryActivity,
                        4
                    ),
                    0,
                    Ui.dp(
                        this@SessionSummaryActivity,
                        4
                    )
                )
            }
        row.addView(
            Ui.label(
                this,
                label,
                12f
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
                1f
            )
        )
        row.addView(
            Ui.pill(
                this,
                value,
                color
            )
        )
        parent.addView(row)
    }

    private fun metric(
        parent: LinearLayout,
        label: String,
        value: String
    ) {
        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                setPadding(
                    0,
                    Ui.dp(
                        this@SessionSummaryActivity,
                        4
                    ),
                    0,
                    Ui.dp(
                        this@SessionSummaryActivity,
                        4
                    )
                )
            }
        row.addView(
            Ui.label(
                this,
                label,
                12f
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
                1f
            )
        )
        row.addView(
            Ui.title(
                this,
                value,
                13.5f
            )
        )
        parent.addView(row)
    }

    companion object {
        const val EXTRA_SESSION_PATH =
            "session_path"
    }
}
