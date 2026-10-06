package ir.mycon.capture

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        settings = AppSettings(this)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)

        val scroll = ScrollView(this)
        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(
                        this@SettingsActivity,
                        16
                    ),
                    Ui.dp(
                        this@SettingsActivity,
                        16
                    ),
                    Ui.dp(
                        this@SettingsActivity,
                        16
                    ),
                    Ui.dp(
                        this@SettingsActivity,
                        16
                    )
                )
            }
        scroll.addView(root)
        Ui.insetWholeRoot(scroll)

        root.addView(
            Ui.title(
                this,
                "تنظیمات",
                22f
            )
        )
        root.addView(
            Ui.label(
                this,
                "پیش‌فرض‌ها برای ورودی هندسی/SfM تنظیم شده‌اند.",
                12f
            )
        )
        Ui.addSpacer(
            root,
            14
        )

        root.addView(
            sectionTitle(
                "کیفیت ضبط"
            )
        )
        root.addView(
            Ui.label(
                this,
                "HQ30 انتخاب پیشنهادی است؛ 60 برای حرکت سریع‌تر.",
                11.5f
            )
        )
        Ui.addSpacer(
            root,
            7
        )

        val profileGroup =
            MaterialButtonToggleGroup(this).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }

        fun profileButton(
            text: String,
            profile:
                CaptureProfile
        ): MaterialButton =
            Ui.button(
                this,
                text,
                Ui.SURFACE_2
            ).apply {
                id =
                    View.generateViewId()
                tag = profile.key
                textSize = 12f
            }

        val hq =
            profileButton(
                "HQ 30",
                CaptureProfile
                    .SCIENTIFIC_HQ30
            )
        val motion =
            profileButton(
                "60 FPS",
                CaptureProfile
                    .MOTION_60
            )
        val auto =
            profileButton(
                "Auto",
                CaptureProfile
                    .ARCORE_AUTO
            )

        val profileLp =
            LinearLayout.LayoutParams(
                0,
                Ui.dp(
                    this,
                    44
                ),
                1f
            )
        profileGroup.addView(
            hq,
            profileLp
        )
        profileGroup.addView(
            motion,
            profileLp
        )
        profileGroup.addView(
            auto,
            profileLp
        )

        profileGroup.check(
            when (
                CaptureProfile.fromKey(
                    settings.captureProfile
                )
            ) {
                CaptureProfile
                    .SCIENTIFIC_HQ30 ->
                    hq.id
                CaptureProfile
                    .MOTION_60 ->
                    motion.id
                CaptureProfile
                    .ARCORE_AUTO ->
                    auto.id
            }
        )

        profileGroup
            .addOnButtonCheckedListener {
                    _,
                    id,
                    checked ->
                if (!checked) {
                    return@addOnButtonCheckedListener
                }
                val value =
                    listOf(
                        hq,
                        motion,
                        auto
                    )
                        .first {
                            it.id == id
                        }
                        .tag as String
                settings.captureProfile =
                    value
            }

        root.addView(profileGroup)

        Ui.addSpacer(
            root,
            16
        )

        root.addView(
            sectionTitle(
                "تصویر"
            )
        )

        val imageCard =
            Ui.card(this)
        val imageBox =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(
                        this@SettingsActivity,
                        12
                    ),
                    Ui.dp(
                        this@SettingsActivity,
                        10
                    ),
                    Ui.dp(
                        this@SettingsActivity,
                        12
                    ),
                    Ui.dp(
                        this@SettingsActivity,
                        10
                    )
                )
            }

        imageBox.addView(
            Ui.label(
                this,
                "Focus",
                11.5f
            )
        )
        Ui.addSpacer(
            imageBox,
            5
        )

        val focusGroup =
            MaterialButtonToggleGroup(this).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }
        val autoFocus =
            Ui.button(
                this,
                "Auto",
                Ui.SURFACE_2
            ).apply {
                id =
                    View.generateViewId()
                textSize = 12f
            }
        val fixedFocus =
            Ui.button(
                this,
                "Fixed",
                Ui.SURFACE_2
            ).apply {
                id =
                    View.generateViewId()
                textSize = 12f
            }

        focusGroup.addView(
            autoFocus,
            LinearLayout.LayoutParams(
                0,
                Ui.dp(
                    this,
                    42
                ),
                1f
            )
        )
        focusGroup.addView(
            fixedFocus,
            LinearLayout.LayoutParams(
                0,
                Ui.dp(
                    this,
                    42
                ),
                1f
            )
        )
        focusGroup.check(
            if (
                settings.focusMode ==
                "fixed"
            ) {
                fixedFocus.id
            } else {
                autoFocus.id
            }
        )
        focusGroup
            .addOnButtonCheckedListener {
                    _,
                    id,
                    checked ->
                if (!checked) {
                    return@addOnButtonCheckedListener
                }
                settings.focusMode =
                    if (
                        id ==
                        fixedFocus.id
                    ) {
                        "fixed"
                    } else {
                        "auto"
                    }
            }
        imageBox.addView(
            focusGroup
        )

        Ui.addSpacer(
            imageBox,
            8
        )
        imageBox.addView(
            compactSwitch(
                "چراغ کمکی",
                "فقط برای محیط کم‌نور؛ پیش‌فرض خاموش",
                settings.torchEnabled
            ) {
                settings.torchEnabled =
                    it
            }
        )
        imageBox.addView(
            Ui.label(
                this,
                "EIS: خاموش  •  Digital zoom: ندارد  •  Exposure/ISO: پایش خودکار",
                11f
            )
        )

        imageCard.addView(
            imageBox
        )
        root.addView(
            imageCard
        )

        Ui.addSpacer(
            root,
            14
        )

        root.addView(
            sectionTitle(
                "برداشت"
            )
        )
        val captureCard =
            Ui.card(this)
        val captureBox =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(
                        this@SettingsActivity,
                        12
                    ),
                    Ui.dp(
                        this@SettingsActivity,
                        8
                    ),
                    Ui.dp(
                        this@SettingsActivity,
                        12
                    ),
                    Ui.dp(
                        this@SettingsActivity,
                        8
                    )
                )
            }
        captureBox.addView(
            compactSwitch(
                "QA زنده",
                "Blur، نور، Tracking و Control",
                settings.qualityWarnings
            ) {
                settings.qualityWarnings =
                    it
            }
        )
        captureBox.addView(
            compactSwitch(
                "GNSS",
                "داده کمکی؛ QR مرجع اصلی است",
                settings.gpsLogging
            ) {
                settings.gpsLogging =
                    it
            }
        )
        captureBox.addView(
            compactSwitch(
                "Depth / عمق",
                "Raw Depth + confidence در گوشی‌های پشتیبانی‌شده؛ داده کمکی reconstruction",
                settings.depthLogging
            ) {
                settings.depthLogging =
                    it
            }
        )
        captureCard.addView(
            captureBox
        )
        root.addView(
            captureCard
        )

        Ui.addSpacer(
            root,
            12
        )

        val advanced =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                visibility =
                    View.GONE
            }

        val advancedCard =
            Ui.card(this)
        val advancedBox =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(
                        this@SettingsActivity,
                        12
                    ),
                    Ui.dp(
                        this@SettingsActivity,
                        8
                    ),
                    Ui.dp(
                        this@SettingsActivity,
                        12
                    ),
                    Ui.dp(
                        this@SettingsActivity,
                        8
                    )
                )
            }

        advancedBox.addView(
            compactSwitch(
                "Auto QR",
                "اسکن پیوسته Marker",
                settings.autoQrScan
            ) {
                settings.autoQrScan =
                    it
            }
        )
        advancedBox.addView(
            compactSwitch(
                "روشن ماندن صفحه",
                "در زمان ضبط",
                settings.keepScreenOn
            ) {
                settings.keepScreenOn =
                    it
            }
        )
        advancedBox.addView(
            compactSwitch(
                "ویبره QR",
                "بازخورد Anchor معتبر",
                settings.hapticFeedback
            ) {
                settings.hapticFeedback =
                    it
            }
        )
        advancedBox.addView(
            compactSwitch(
                "صدای QR",
                "بازخورد کوتاه",
                settings.soundFeedback
            ) {
                settings.soundFeedback =
                    it
            }
        )

        Ui.addSpacer(
            advancedBox,
            8
        )
        advancedBox.addView(
            Ui.label(
                this,
                "Theme",
                11.5f
            )
        )
        Ui.addSpacer(
            advancedBox,
            5
        )

        val themeGroup =
            MaterialButtonToggleGroup(
                this
            ).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }

        fun themeButton(
            label: String,
            key: String
        ): MaterialButton =
            Ui.button(
                this,
                label,
                Ui.SURFACE_2
            ).apply {
                id =
                    View.generateViewId()
                tag = key
                textSize = 12f
            }

        val system =
            themeButton(
                "System",
                "system"
            )
        val dark =
            themeButton(
                "Dark",
                "dark"
            )
        val light =
            themeButton(
                "Light",
                "light"
            )

        val themeLp =
            LinearLayout.LayoutParams(
                0,
                Ui.dp(
                    this,
                    42
                ),
                1f
            )
        themeGroup.addView(
            system,
            themeLp
        )
        themeGroup.addView(
            dark,
            themeLp
        )
        themeGroup.addView(
            light,
            themeLp
        )
        themeGroup.check(
            when (settings.theme) {
                "dark" -> dark.id
                "light" -> light.id
                else -> system.id
            }
        )
        themeGroup
            .addOnButtonCheckedListener {
                    _,
                    id,
                    checked ->
                if (!checked) {
                    return@addOnButtonCheckedListener
                }
                val key =
                    listOf(
                        system,
                        dark,
                        light
                    )
                        .first {
                            it.id == id
                        }
                        .tag as String
                if (
                    key !=
                    settings.theme
                ) {
                    settings.theme = key
                    settings.applyTheme()
                }
            }
        advancedBox.addView(
            themeGroup
        )
        advancedCard.addView(
            advancedBox
        )
        advanced.addView(
            advancedCard
        )
        root.addView(
            advanced
        )

        val advancedButton =
            Ui.button(
                this,
                "تنظیمات بیشتر",
                Ui.SURFACE_2
            ).apply {
                setOnClickListener {
                    val show =
                        advanced.visibility !=
                            View.VISIBLE
                    advanced.visibility =
                        if (show) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                    text =
                        if (show) {
                            "بستن تنظیمات بیشتر"
                        } else {
                            "تنظیمات بیشتر"
                        }
                }
            }
        root.addView(
            advancedButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Ui.dp(
                    this,
                    44
                )
            )
        )

        Ui.addSpacer(
            root,
            12
        )
        root.addView(
            Ui.label(
                this,
                "v0.7 • MyCON R4 Bridge",
                11f
            )
        )

        setContentView(scroll)
    }

    private fun sectionTitle(
        text: String
    ) =
        Ui.title(
            this,
            text,
            16f
        )

    private fun compactSwitch(
        title: String,
        subtitle: String,
        initial: Boolean,
        onChange:
            (Boolean) -> Unit
    ): View {
        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    android.view.Gravity
                        .CENTER_VERTICAL
                setPadding(
                    0,
                    Ui.dp(
                        this@SettingsActivity,
                        3
                    ),
                    0,
                    Ui.dp(
                        this@SettingsActivity,
                        3
                    )
                )
            }

        val texts =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }
        texts.addView(
            Ui.title(
                this,
                title,
                14f
            )
        )
        texts.addView(
            Ui.label(
                this,
                subtitle,
                10.5f
            )
        )

        row.addView(
            texts,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        row.addView(
            SwitchMaterial(this).apply {
                isChecked = initial
                setOnCheckedChangeListener {
                        _,
                        value ->
                    onChange(value)
                }
            }
        )
        return row
    }
}
