package ir.mycon.capture

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SettingsActivity, 18), Ui.dp(this@SettingsActivity, 18), Ui.dp(this@SettingsActivity, 18), Ui.dp(this@SettingsActivity, 18))
        }
        scroll.addView(root)
        Ui.insetWholeRoot(scroll)

        root.addView(Ui.title(this, "تنظیمات MyCON Recorder", 24f))
        root.addView(Ui.label(this, "تنظیمات فقط روی رفتار برداشت اثر می‌گذارند؛ ساختار بسته خروجی ثابت می‌ماند.", 13f))
        Ui.addSpacer(root, 18)

        fun toggle(title: String, subtitle: String, initial: Boolean, onChange: (Boolean) -> Unit) {
            val card = Ui.card(this)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 12), Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 12))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val textBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            textBox.addView(Ui.title(this, title, 16f))
            textBox.addView(Ui.label(this, subtitle, 12f))
            row.addView(textBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val sw = SwitchMaterial(this).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            }
            row.addView(sw)
            content.addView(row)
            card.addView(content)
            root.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(this@SettingsActivity, 10)
            })
        }

        toggle("بازخورد لرزشی", "هنگام شناسایی QR معتبر", settings.hapticFeedback) { settings.hapticFeedback = it }
        toggle("بازخورد صوتی", "صدای کوتاه تأیید برای Anchor معتبر", settings.soundFeedback) { settings.soundFeedback = it }
        toggle("ثبت GNSS", "مختصات GPS فقط داده کمکی است، نه Survey Ground Truth", settings.gpsLogging) { settings.gpsLogging = it }
        toggle("اسکن خودکار QR", "در حین Preview و ضبط به‌صورت پیوسته", settings.autoQrScan) { settings.autoQrScan = it }
        toggle("روشن ماندن صفحه", "در طول برداشت صفحه خاموش نشود", settings.keepScreenOn) { settings.keepScreenOn = it }
        toggle("هشدار کیفیت زنده", "هشدار حرکت سریع، نور کم، GPS ضعیف و نبود Control", settings.qualityWarnings) { settings.qualityWarnings = it }

        Ui.addSpacer(root, 8)
        root.addView(Ui.title(this, "ظاهر", 18f))
        Ui.addSpacer(root, 10)

        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        fun themeButton(text: String, key: String): MaterialButton =
            Ui.button(this, text).apply {
                id = android.view.View.generateViewId()
                tag = key
            }

        val system = themeButton("سیستم", "system")
        val dark = themeButton("تیره", "dark")
        val light = themeButton("روشن", "light")
        group.addView(system, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        group.addView(dark, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        group.addView(light, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        val selected = when (settings.theme) {
            "dark" -> dark.id
            "light" -> light.id
            else -> system.id
        }
        group.check(selected)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val key = listOf(system, dark, light).firstOrNull { it.id == checkedId }?.tag as? String ?: return@addOnButtonCheckedListener
            if (key != settings.theme) {
                settings.theme = key
                settings.applyTheme()
            }
        }
        root.addView(group)

        Ui.addSpacer(root, 24)
        root.addView(Ui.label(this, "نسخه رابط کاربری: 0.3.0 • Capture schema: v1", 12f))

        setContentView(scroll)
    }
}
