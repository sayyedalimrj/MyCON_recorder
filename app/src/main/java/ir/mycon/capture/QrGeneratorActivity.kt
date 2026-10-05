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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class QrGeneratorActivity : AppCompatActivity() {
    private val fields = linkedMapOf<String, EditText>()
    private lateinit var preview: ImageView
    private lateinit var payloadView: TextView

    private var lastPayload: AnchorPayload? = null
    private var lastModules: BitMatrix? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }

        column.addView(
            TextView(this).apply {
                text = "MYCON Control Marker v1"
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 18)
            }
        )

        fun add(
            label: String,
            key: String,
            defaultValue: String,
            numeric: Boolean = false
        ) {
            column.addView(TextView(this).apply { text = label })
            val edit = EditText(this).apply {
                setText(defaultValue)
                setSingleLine(true)
                if (numeric) {
                    inputType =
                        InputType.TYPE_CLASS_NUMBER or
                            InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            InputType.TYPE_NUMBER_FLAG_SIGNED
                }
            }
            fields[key] = edit
            column.addView(edit)
        }

        add("شناسه پروژه", "project", "P001")
        add("شناسه Anchor", "anchor", "A001")
        add("CRS، مثلاً EPSG:32639 یا LOCAL:P001", "crs", "LOCAL:P001")
        add("X متر", "x", "0.000", true)
        add("Y متر", "y", "0.000", true)
        add("Z متر", "z", "0.000", true)
        add("نوع نصب: VERTICAL یا HORIZONTAL", "mount", "VERTICAL")
        add("Azimuth درجه", "azimuth", "0.0", true)
        add("اندازه واقعی خود سمبل QR، میلی‌متر", "size", "180", true)
        add("طبقه / زون", "floor", "GF")

        column.addView(
            Button(this).apply {
                text = "ساخت QR استاندارد"
                setOnClickListener { generate() }
            }
        )

        preview = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = 600
        }
        column.addView(preview)

        payloadView = TextView(this).apply {
            setTextIsSelectable(true)
            setPadding(4, 16, 4, 16)
        }
        column.addView(payloadView)

        column.addView(
            TextView(this).apply {
                text =
                    "size_mm فقط اندازه خود سمبل QR است. " +
                        "Quiet Zone چهار ماژولی بیرون آن چاپ می‌شود و جزو اندازه نیست."
            }
        )

        column.addView(
            Button(this).apply {
                text = "ساخت PDF چاپ 100%"
                setOnClickListener { createPdfAndShare() }
            }
        )

        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }

    private fun readPayload(): AnchorPayload {
        val payload = AnchorPayload(
            project = fields.getValue("project").text.toString().trim(),
            anchor = fields.getValue("anchor").text.toString().trim(),
            crs = fields.getValue("crs").text.toString().trim(),
            x = fields.getValue("x").text.toString().toDouble(),
            y = fields.getValue("y").text.toString().toDouble(),
            z = fields.getValue("z").text.toString().toDouble(),
            mount = fields.getValue("mount").text.toString().trim(),
            azimuthDeg =
                fields.getValue("azimuth").text.toString().toDouble(),
            sizeMm = fields.getValue("size").text.toString().toDouble(),
            floor = fields.getValue("floor").text.toString().trim()
        )

        require(payload.project.isNotBlank()) { "Project خالی است" }
        require(payload.anchor.isNotBlank()) { "Anchor خالی است" }
        require(payload.crs.isNotBlank()) { "CRS خالی است" }
        require(payload.sizeMm > 0.0) { "اندازه QR باید مثبت باشد" }
        require(
            payload.mount.uppercase() in setOf("VERTICAL", "HORIZONTAL")
        ) { "نوع نصب باید VERTICAL یا HORIZONTAL باشد" }

        return payload
    }

    private fun makeModules(raw: String): BitMatrix {
        val hints = mapOf(EncodeHintType.MARGIN to 0)
        // 1x1 asks ZXing for the minimum native module matrix.
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

            lastPayload = payload
            lastModules = modules
            preview.setImageBitmap(bitmap)
            payloadView.text =
                payload.toQrString() +
                    "\n\nModules: ${modules.width} × ${modules.height}"
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

        if (totalPt > 520f) {
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
        }

        // Four-module white quiet zone exists around this symbol.
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

        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 13f
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText(
            "MYCON CONTROL MARKER v1",
            pageWidthPt / 2f,
            55f,
            textPaint
        )
        canvas.drawText(
            "${payload.project} / ${payload.anchor}   ${payload.crs}",
            pageWidthPt / 2f,
            78f,
            textPaint
        )
        canvas.drawText(
            "XYZ center: ${payload.x}, ${payload.y}, ${payload.z} m",
            pageWidthPt / 2f,
            top + totalPt + 32f,
            textPaint
        )
        canvas.drawText(
            "Mount: ${payload.mount.uppercase()}   Azimuth: ${payload.azimuthDeg}°",
            pageWidthPt / 2f,
            top + totalPt + 53f,
            textPaint
        )
        canvas.drawText(
            "QR SYMBOL: ${payload.sizeMm} mm — print at 100% / Actual Size",
            pageWidthPt / 2f,
            top + totalPt + 74f,
            textPaint
        )

        // Independent 100 mm verification bar.
        val verifyBarPt = (100.0 * 72.0 / 25.4).toFloat()
        val verifyY = pageHeightPt - 80f
        val verifyLeft = (pageWidthPt - verifyBarPt) / 2f
        canvas.drawLine(
            verifyLeft,
            verifyY,
            verifyLeft + verifyBarPt,
            verifyY,
            black
        )
        canvas.drawLine(
            verifyLeft,
            verifyY - 8f,
            verifyLeft,
            verifyY + 8f,
            black
        )
        canvas.drawLine(
            verifyLeft + verifyBarPt,
            verifyY - 8f,
            verifyLeft + verifyBarPt,
            verifyY + 8f,
            black
        )
        canvas.drawText(
            "VERIFY AFTER PRINT: this bar must measure exactly 100 mm",
            pageWidthPt / 2f,
            verifyY - 14f,
            textPaint
        )

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
