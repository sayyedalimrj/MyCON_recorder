package ir.mycon.capture

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileOutputStream

class QrGeneratorActivity : AppCompatActivity() {
    private val fields = linkedMapOf<String, TextInputEditText>()
    private lateinit var preview: ImageView
    private lateinit var payloadView: TextView
    private lateinit var settings: AppSettings
    private lateinit var modelGroup: MaterialButtonToggleGroup
    private var testCubeButtonId: Int = android.view.View.NO_ID
    private var mountMode = "VERTICAL"
    private var modelId = ""

    private var lastPayload: AnchorPayload? = null
    private var lastModules: BitMatrix? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
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
                        this@QrGeneratorActivity,
                        16
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        16
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        16
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        24
                    )
                )
            }

        scroll.addView(outer)
        Ui.insetWholeRoot(scroll)

        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        val maxWidth =
            if (
                resources.configuration
                    .screenWidthDp >=
                600
            ) {
                Ui.dp(
                    this,
                    600
                )
            } else {
                LinearLayout
                    .LayoutParams
                    .MATCH_PARENT
            }

        outer.addView(
            root,
            LinearLayout.LayoutParams(
                maxWidth,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT
            )
        )

        root.addView(
            Ui.title(
                this,
                "Control Marker",
                23f
            )
        )
        root.addView(
            Ui.label(
                this,
                "مرکز QR = مختصات Survey. چاپ همیشه Actual Size.",
                12f
            )
        )
        Ui.addSpacer(
            root,
            14
        )

        val presetCard =
            Ui.card(this)
        val presetBox =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        10
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        10
                    )
                )
            }
        presetBox.addView(
            Ui.title(
                this,
                "TEST01",
                15f
            )
        )
        presetBox.addView(
            Ui.label(
                this,
                "A001 مدل 1m • A002/A003 کنترل Scale",
                10.5f
            )
        )
        Ui.addSpacer(
            presetBox,
            7
        )

        val presets =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        listOf(
            "A001",
            "A002",
            "A003"
        ).forEachIndexed {
                index,
                anchor ->
            presets.addView(
                Ui.button(
                    this,
                    anchor,
                    if (
                        anchor ==
                        "A001"
                    ) {
                        Ui.PURPLE
                    } else {
                        Ui.SURFACE_2
                    }
                ).apply {
                    textSize = 12f
                    setOnClickListener {
                        loadTestPreset(
                            anchor
                        )
                    }
                },
                LinearLayout.LayoutParams(
                    0,
                    Ui.dp(
                        this,
                        42
                    ),
                    1f
                ).apply {
                    if (
                        index < 2
                    ) {
                        marginEnd =
                            Ui.dp(
                                this@QrGeneratorActivity,
                                5
                            )
                    }
                }
            )
        }

        presetBox.addView(presets)
        presetCard.addView(presetBox)
        root.addView(presetCard)

        Ui.addSpacer(
            root,
            10
        )

        fun makeField(
            key: String,
            hint: String,
            defaultValue: String,
            numeric: Boolean = false
        ): TextInputLayout {
            val layout =
                TextInputLayout(this).apply {
                    this.hint = hint
                    boxBackgroundMode =
                        TextInputLayout
                            .BOX_BACKGROUND_OUTLINE
                    boxStrokeColor =
                        Ui.BLUE
                    defaultHintTextColor =
                        android.content.res
                            .ColorStateList
                            .valueOf(
                                Ui.MUTED
                            )
                    val radius =
                        Ui.dp(
                            this@QrGeneratorActivity,
                            12
                        ).toFloat()
                    setBoxCornerRadii(
                        radius,
                        radius,
                        radius,
                        radius
                    )
                }

            val edit =
                TextInputEditText(
                    layout.context
                ).apply {
                    setText(
                        defaultValue
                    )
                    setTextColor(
                        Ui.TEXT
                    )
                    setHintTextColor(
                        Ui.MUTED
                    )
                    setSingleLine(true)
                    textSize = 14f
                    if (numeric) {
                        inputType =
                            InputType
                                .TYPE_CLASS_NUMBER or
                                InputType
                                    .TYPE_NUMBER_FLAG_DECIMAL or
                                InputType
                                    .TYPE_NUMBER_FLAG_SIGNED
                    }
                }

            fields[key] = edit
            layout.addView(edit)
            return layout
        }

        val essentialCard =
            Ui.card(this)
        val essential =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    )
                )
            }

        essential.addView(
            Ui.title(
                this,
                "مشخصات Control",
                15f
            )
        )
        Ui.addSpacer(
            essential,
            8
        )

        val idRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }
        idRow.addView(
            makeField(
                "project",
                "Project",
                settings.lastProject
                    .ifBlank {
                        "P001"
                    }
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
                1f
            ).apply {
                marginEnd =
                    Ui.dp(
                        this@QrGeneratorActivity,
                        6
                    )
            }
        )
        idRow.addView(
            makeField(
                "anchor",
                "Anchor",
                "A001"
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
                1f
            )
        )
        essential.addView(idRow)

        Ui.addSpacer(
            essential,
            8
        )

        val xyzRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        listOf(
            Triple(
                "x",
                "X (m)",
                "0.000"
            ),
            Triple(
                "y",
                "Y (m)",
                "0.000"
            ),
            Triple(
                "z",
                "Z (m)",
                "0.000"
            )
        ).forEachIndexed {
                index,
                item ->
            xyzRow.addView(
                makeField(
                    item.first,
                    item.second,
                    item.third,
                    true
                ),
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams
                        .WRAP_CONTENT,
                    1f
                ).apply {
                    if (
                        index < 2
                    ) {
                        marginEnd =
                            Ui.dp(
                                this@QrGeneratorActivity,
                                5
                            )
                    }
                }
            )
        }
        essential.addView(xyzRow)

        Ui.addSpacer(
            essential,
            8
        )

        essential.addView(
            makeField(
                "size",
                "QR symbol size (mm)",
                "160",
                true
            )
        )

        essentialCard.addView(
            essential
        )
        root.addView(
            essentialCard
        )

        Ui.addSpacer(
            root,
            8
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
                        this@QrGeneratorActivity,
                        12
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    )
                )
            }

        advancedBox.addView(
            makeField(
                "crs",
                "CRS",
                "LOCAL:P001"
            )
        )
        Ui.addSpacer(
            advancedBox,
            7
        )

        val azFloorRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }
        azFloorRow.addView(
            makeField(
                "azimuth",
                "Azimuth °",
                "0.0",
                true
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
                1f
            ).apply {
                marginEnd =
                    Ui.dp(
                        this@QrGeneratorActivity,
                        6
                    )
            }
        )
        azFloorRow.addView(
            makeField(
                "floor",
                "Floor / Zone",
                "GF"
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
                1f
            )
        )
        advancedBox.addView(
            azFloorRow
        )

        Ui.addSpacer(
            advancedBox,
            10
        )
        advancedBox.addView(
            Ui.label(
                this,
                "Mount",
                11f
            )
        )

        val mountGroup =
            MaterialButtonToggleGroup(this).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }
        val vertical =
            Ui.button(
                this,
                "Vertical",
                Ui.BLUE
            ).apply {
                id =
                    View.generateViewId()
                textSize = 12f
            }
        val horizontal =
            Ui.button(
                this,
                "Horizontal",
                Ui.SURFACE_2
            ).apply {
                id =
                    View.generateViewId()
                textSize = 12f
            }
        mountGroup.addView(
            vertical,
            LinearLayout.LayoutParams(
                0,
                Ui.dp(
                    this,
                    42
                ),
                1f
            )
        )
        mountGroup.addView(
            horizontal,
            LinearLayout.LayoutParams(
                0,
                Ui.dp(
                    this,
                    42
                ),
                1f
            )
        )
        mountGroup.check(
            vertical.id
        )
        mountGroup
            .addOnButtonCheckedListener {
                    _,
                    checkedId,
                    checked ->
                if (!checked) {
                    return@addOnButtonCheckedListener
                }
                val isHorizontal =
                    checkedId ==
                        horizontal.id
                mountMode =
                    if (isHorizontal) {
                        "HORIZONTAL"
                    } else {
                        "VERTICAL"
                    }
            }
        advancedBox.addView(
            mountGroup
        )

        Ui.addSpacer(
            advancedBox,
            10
        )
        advancedBox.addView(
            Ui.label(
                this,
                "Attached model",
                11f
            )
        )

        modelGroup =
            MaterialButtonToggleGroup(this).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }

        val noModel =
            Ui.button(
                this,
                "None",
                Ui.SURFACE_2
            ).apply {
                id =
                    View.generateViewId()
                textSize = 12f
            }
        val testCube =
            Ui.button(
                this,
                "Cube 1m",
                Ui.BLUE
            ).apply {
                id =
                    View.generateViewId()
                textSize = 12f
            }

        testCubeButtonId =
            testCube.id

        modelGroup.addView(
            noModel,
            LinearLayout.LayoutParams(
                0,
                Ui.dp(
                    this,
                    42
                ),
                1f
            )
        )
        modelGroup.addView(
            testCube,
            LinearLayout.LayoutParams(
                0,
                Ui.dp(
                    this,
                    42
                ),
                1f
            )
        )
        modelGroup.check(
            noModel.id
        )
        modelGroup
            .addOnButtonCheckedListener {
                    _,
                    checkedId,
                    checked ->
                if (!checked) {
                    return@addOnButtonCheckedListener
                }
                modelId =
                    if (
                        checkedId ==
                        testCube.id
                    ) {
                        AnchorPayload
                            .TEST_MODEL_ID
                    } else {
                        ""
                    }
            }
        advancedBox.addView(
            modelGroup
        )

        advancedCard.addView(
            advancedBox
        )
        advanced.addView(
            advancedCard
        )
        root.addView(advanced)

        val advancedToggle =
            Ui.button(
                this,
                "جزئیات Marker",
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
                            "بستن جزئیات"
                        } else {
                            "جزئیات Marker"
                        }
                }
            }
        root.addView(
            advancedToggle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams
                    .MATCH_PARENT,
                Ui.dp(
                    this,
                    42
                )
            )
        )

        Ui.addSpacer(
            root,
            10
        )

        val actions =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        val generate =
            Ui.primaryButton(
                this,
                "ساخت Marker"
            )
                .apply {
                    textSize = 14f
                    setOnClickListener {
                        generate()
                    }
                }

        val pdf =
            Ui.button(
                this,
                "PDF / Share",
                Ui.SURFACE_2
            ).apply {
                setOnClickListener {
                    createPdfAndShare()
                }
            }

        actions.addView(
            generate,
            LinearLayout.LayoutParams(
                0,
                Ui.dp(
                    this,
                    50
                ),
                1.3f
            ).apply {
                marginEnd =
                    Ui.dp(
                        this@QrGeneratorActivity,
                        6
                    )
            }
        )
        actions.addView(
            pdf,
            LinearLayout.LayoutParams(
                0,
                Ui.dp(
                    this,
                    50
                ),
                1f
            )
        )
        root.addView(actions)

        Ui.addSpacer(
            root,
            10
        )

        val previewCard =
            Ui.card(this)
        val previewBox =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                gravity =
                    Gravity.CENTER
                setPadding(
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        12
                    )
                )
            }

        preview =
            ImageView(this).apply {
                adjustViewBounds = true
                minimumHeight =
                    Ui.dp(
                        this@QrGeneratorActivity,
                        220
                    )
                setBackgroundColor(
                    Color.WHITE
                )
                setPadding(
                    Ui.dp(
                        this@QrGeneratorActivity,
                        8
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        8
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        8
                    ),
                    Ui.dp(
                        this@QrGeneratorActivity,
                        8
                    )
                )
            }

        payloadView =
            Ui.label(
                this,
                "Marker هنوز ساخته نشده.",
                10.5f
            ).apply {
                setTextIsSelectable(true)
                maxLines = 5
                ellipsize =
                    android.text.TextUtils
                        .TruncateAt.END
            }

        previewBox.addView(
            preview,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams
                    .MATCH_PARENT,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT
            )
        )
        Ui.addSpacer(
            previewBox,
            7
        )
        previewBox.addView(
            payloadView
        )
        previewCard.addView(
            previewBox
        )
        root.addView(
            previewCard
        )

        Ui.addSpacer(
            root,
            8
        )

        root.addView(
            Ui.label(
                this,
                "چاپ: 100% / Actual Size • خط 100 mm را بعد از چاپ اندازه بگیر.",
                11f
            )
        )

        setContentView(scroll)
    }

    private fun loadTestPreset(anchor: String) {
        fields["project"]?.setText("TEST01")
        fields["anchor"]?.setText(anchor)
        fields["crs"]?.setText("LOCAL:TEST01")

        when (anchor) {
            "A002" -> {
                fields["x"]?.setText("0.500")
                fields["y"]?.setText("0.000")
                fields["z"]?.setText("0.000")
            }
            "A003" -> {
                fields["x"]?.setText("0.000")
                fields["y"]?.setText("0.500")
                fields["z"]?.setText("0.000")
            }
            else -> {
                fields["x"]?.setText("0.000")
                fields["y"]?.setText("0.000")
                fields["z"]?.setText("0.000")
            }
        }

        fields["azimuth"]?.setText("0.0")
        fields["size"]?.setText("160")
        fields["floor"]?.setText("TEST")

        if (
            testCubeButtonId !=
            android.view.View.NO_ID
        ) {
            if (anchor == "A001") {
                modelGroup.check(
                    testCubeButtonId
                )
            } else {
                val firstId =
                    modelGroup
                        .getChildAt(0)
                        ?.id
                        ?: android.view.View.NO_ID
                if (
                    firstId !=
                    android.view.View.NO_ID
                ) {
                    modelGroup.check(firstId)
                }
            }
        }

        Toast.makeText(
            this,
            if (anchor == "A001") {
                "A001 آماده شد؛ Test Cube 1m به این QR متصل است."
            } else {
                "$anchor آماده شد؛ این Marker فقط برای Scale calibration است."
            },
            Toast.LENGTH_LONG
        ).show()
    }

    private fun field(key: String): String =
        fields.getValue(key).text?.toString()?.trim().orEmpty()

    private fun readPayload(): AnchorPayload {
        val payload = AnchorPayload(
            project = field("project"),
            anchor = field("anchor"),
            crs = field("crs"),
            x = field("x").toDouble(),
            y = field("y").toDouble(),
            z = field("z").toDouble(),
            mount = mountMode,
            azimuthDeg = field("azimuth").toDouble(),
            sizeMm = field("size").toDouble(),
            floor = field("floor"),
            modelId = modelId,
            modelSizeM =
                if (modelId.isBlank()) {
                    null
                } else {
                    1.0
                }
        )

        require(payload.project.isNotBlank()) { "Project خالی است" }
        require(payload.anchor.isNotBlank()) { "Anchor خالی است" }
        require(payload.crs.isNotBlank()) { "CRS خالی است" }
        require(payload.x.isFinite() && payload.y.isFinite() && payload.z.isFinite()) { "XYZ معتبر نیست" }
        require(payload.azimuthDeg.isFinite()) { "Azimuth معتبر نیست" }
        require(payload.sizeMm in 50.0..500.0) { "اندازه QR باید بین 50 تا 500 میلی‌متر باشد" }

        return payload
    }

    private fun makeModules(raw: String): BitMatrix {
        val hints =
            mapOf(
                EncodeHintType.MARGIN to 0,
                EncodeHintType.ERROR_CORRECTION to
                    ErrorCorrectionLevel.M
            )
        return QRCodeWriter().encode(
            raw,
            BarcodeFormat.QR_CODE,
            1,
            1,
            hints
        )
    }

    private fun generate() {
        try {
            val payload = readPayload()
            val modules = makeModules(payload.toQrString())
            val quietModules = 4
            val scale = 12
            val fullModules = modules.width + quietModules * 2
            val bitmapSize = fullModules * scale

            val bitmap =
                Bitmap.createBitmap(
                    bitmapSize,
                    bitmapSize,
                    Bitmap.Config.ARGB_8888
                )
            bitmap.eraseColor(Color.WHITE)

            for (y in 0 until modules.height) {
                for (x in 0 until modules.width) {
                    if (!modules[x, y]) continue
                    val left = (x + quietModules) * scale
                    val top = (y + quietModules) * scale
                    for (py in top until top + scale) {
                        for (px in left until left + scale) {
                            bitmap.setPixel(px, py, Color.BLACK)
                        }
                    }
                }
            }

            settings.lastProject = payload.project
            lastPayload = payload
            lastModules = modules
            preview.setImageBitmap(bitmap)
            payloadView.text =
                "${payload.project} / ${payload.anchor} • ${payload.crs}\n" +
                    "XYZ: ${payload.x}, ${payload.y}, ${payload.z} m • ${payload.sizeMm} mm\n" +
                    (
                        if (payload.hasModel()) {
                            "MODEL: ${payload.modelId} • ${payload.modelSizeM} m\n"
                        } else {
                            ""
                        }
                    ) +
                    payload.toQrString()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "ورودی نامعتبر: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun createPdfAndShare() {
        if (lastPayload == null || lastModules == null) {
            generate()
        }

        val payload = lastPayload ?: return
        val modules = lastModules ?: return

        val outputDir =
            File(getExternalFilesDir(null), "markers").apply { mkdirs() }
        val output =
            File(outputDir, "${payload.project}_${payload.anchor}.pdf")

        val document = PdfDocument()
        val pageWidthPt = 595
        val pageHeightPt = 842
        val page =
            document.startPage(
                PdfDocument.PageInfo.Builder(
                    pageWidthPt,
                    pageHeightPt,
                    1
                ).create()
            )
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val symbolPt = (payload.sizeMm * 72.0 / 25.4).toFloat()
        val modulePt = symbolPt / modules.width.toFloat()
        val quietPt = 4f * modulePt
        val totalPt = symbolPt + quietPt * 2f

        if (totalPt > pageWidthPt - 10f) {
            Toast.makeText(
                this,
                "QR همراه Quiet Zone برای A4 بزرگ است؛ size_mm را کم کن.",
                Toast.LENGTH_LONG
            ).show()
            document.finishPage(page)
            document.close()
            return
        }

        val left = (pageWidthPt - totalPt) / 2f
        val top = 120f
        val symbolLeft = left + quietPt
        val symbolTop = top + quietPt

        val black = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            strokeWidth = 1.5f
        }

        for (y in 0 until modules.height) {
            for (x in 0 until modules.width) {
                if (!modules[x, y]) continue
                val l = symbolLeft + x * modulePt
                val t = symbolTop + y * modulePt
                canvas.drawRect(
                    RectF(
                        l,
                        t,
                        l + modulePt + 0.02f,
                        t + modulePt + 0.02f
                    ),
                    black
                )
            }
        }

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 16f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 12f
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText("MYCON CONTROL MARKER v1", pageWidthPt / 2f, 52f, titlePaint)
        canvas.drawText("${payload.project} / ${payload.anchor}   ${payload.crs}", pageWidthPt / 2f, 78f, textPaint)
        canvas.drawText("XYZ center: ${payload.x}, ${payload.y}, ${payload.z} m", pageWidthPt / 2f, top + totalPt + 30f, textPaint)
        canvas.drawText("Mount: ${payload.mount.uppercase()}   Azimuth: ${payload.azimuthDeg}°", pageWidthPt / 2f, top + totalPt + 50f, textPaint)
        canvas.drawText("QR SYMBOL: ${payload.sizeMm} mm — PRINT 100% / ACTUAL SIZE", pageWidthPt / 2f, top + totalPt + 70f, textPaint)

        val verifyBarPt = (100.0 * 72.0 / 25.4).toFloat()
        val verifyY = pageHeightPt - 24f
        val verifyLeft = (pageWidthPt - verifyBarPt) / 2f
        canvas.drawLine(verifyLeft, verifyY, verifyLeft + verifyBarPt, verifyY, black)
        canvas.drawLine(verifyLeft, verifyY - 8f, verifyLeft, verifyY + 8f, black)
        canvas.drawLine(verifyLeft + verifyBarPt, verifyY - 8f, verifyLeft + verifyBarPt, verifyY + 8f, black)
        canvas.drawText("VERIFY AFTER PRINT: this bar must measure exactly 100 mm", pageWidthPt / 2f, verifyY - 15f, textPaint)

        document.finishPage(page)
        FileOutputStream(output).use { document.writeTo(it) }
        document.close()

        val uri =
            FileProvider.getUriForFile(
                this,
                "${packageName}.files",
                output
            )

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "ارسال / چاپ Marker"
            )
        )
    }
}
