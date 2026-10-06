package ir.mycon.capture

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SessionManagerActivity : AppCompatActivity() {
    private lateinit var repository: SessionRepository
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        AppSettings(this).applyTheme()
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        repository = SessionRepository(this)

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SessionManagerActivity, 18), Ui.dp(this@SessionManagerActivity, 18), Ui.dp(this@SessionManagerActivity, 18), Ui.dp(this@SessionManagerActivity, 18))
        }
        scroll.addView(root)
        Ui.insetWholeRoot(scroll)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(Ui.title(this, "برداشت‌ها", 26f))
        root.addView(Ui.label(this, "Sessionها داخل فضای اختصاصی اپ نگهداری می‌شوند. ZIP را هر زمان لازم بود بساز و Share کن.", 13f))
        Ui.addSpacer(root, 16)

        val sessions = repository.listSessions()
        if (sessions.isEmpty()) {
            root.addView(Ui.label(this, "هنوز برداشتی ذخیره نشده.", 15f))
            return
        }

        sessions.forEach { info ->
            val card = Ui.card(this)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@SessionManagerActivity, 16), Ui.dp(this@SessionManagerActivity, 14), Ui.dp(this@SessionManagerActivity, 16), Ui.dp(this@SessionManagerActivity, 14))
            }
            box.addView(Ui.title(this, info.project, 18f))
            box.addView(Ui.label(this, info.startedUtc.ifBlank { info.name }, 12f))
            Ui.addSpacer(box, 8)
            val videoLabel =
                if (
                    info.videoWidth > 0 &&
                    info.videoHeight > 0
                ) {
                    "${info.videoWidth}×${info.videoHeight} • ${String.format(java.util.Locale.US, "%.1f", info.observedFps)}fps"
                } else {
                    "legacy"
                }
            box.addView(
                Ui.label(
                    this,
                    "Tracking ${SessionRepository.percent(info.trackingRatio)}  •  QR ${info.qrEvents}  •  Anchor ${info.anchors.size}  •  $videoLabel  •  ${SessionRepository.humanBytes(info.videoBytes)}",
                    13f
                )
            )
            Ui.addSpacer(box, 12)

            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val summary = Ui.button(this, "خلاصه", Ui.BLUE).apply {
                setOnClickListener {
                    startActivity(Intent(this@SessionManagerActivity, SessionSummaryActivity::class.java).putExtra(SessionSummaryActivity.EXTRA_SESSION_PATH, info.dir.absolutePath))
                }
            }
            val share = Ui.button(this, "ZIP", Ui.GREEN).apply {
                setOnClickListener { repository.share(this@SessionManagerActivity, info.dir) }
            }
            val delete = Ui.button(this, "حذف", Ui.RED).apply {
                setOnClickListener {
                    AlertDialog.Builder(this@SessionManagerActivity)
                        .setTitle("حذف برداشت؟")
                        .setMessage("این Session و ZIP ساخته‌شده از آن حذف می‌شوند.")
                        .setNegativeButton("لغو", null)
                        .setPositiveButton("حذف") { _, _ ->
                            repository.delete(info.dir)
                            render()
                        }
                        .show()
                }
            }
            val p = LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f).apply { marginEnd = Ui.dp(this@SessionManagerActivity, 6) }
            actions.addView(summary, p)
            actions.addView(share, p)
            actions.addView(delete, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
            box.addView(actions)
            card.addView(box)
            root.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(this@SessionManagerActivity, 12)
            })
        }
    }
}
