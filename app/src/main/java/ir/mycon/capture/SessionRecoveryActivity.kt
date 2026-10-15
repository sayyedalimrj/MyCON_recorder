package ir.mycon.capture

import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Rescue workspace: copies old session bytes out through Android's Storage Access Framework.
 * It never changes, migrates or deletes anything in the source session.
 */
class SessionRecoveryActivity : AppCompatActivity() {
    private lateinit var repository: SessionRepository
    private var sessionDir: File? = null
    private var chosenFile: File? = null
    private var busy = false
    private lateinit var root: LinearLayout
    private lateinit var status: TextView

    private val zipPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) saveZip(uri)
        }
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val file = chosenFile
            if (uri != null && file != null) saveFile(uri, file)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppSettings(this).applyTheme()
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        repository = SessionRepository(this)
        val candidate = intent.getStringExtra(EXTRA_SESSION_PATH)?.let(::File)
        sessionDir = candidate?.takeIf { repository.isSessionFolder(it) }
        chosenFile = savedInstanceState?.getString("chosen_file")?.let(::File)

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SessionRecoveryActivity, 18),
                Ui.dp(this@SessionRecoveryActivity, 18),
                Ui.dp(this@SessionRecoveryActivity, 18),
                Ui.dp(this@SessionRecoveryActivity, 32))
        }
        scroll.addView(root)
        Ui.insetWholeRoot(scroll)
        setContentView(scroll)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        chosenFile?.let { outState.putString("chosen_file", it.absolutePath) }
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        root.removeAllViews()
        root.addView(Ui.title(this, "بازیابی برداشت", 25f))
        root.addView(Ui.label(this, "فایل اصلی دست‌نخورده می‌ماند.", 13f))
        Ui.addSpacer(root, 12)
        val dir = sessionDir
        if (dir == null || !repository.isSessionFolder(dir)) {
            root.addView(Ui.label(this, "پوشه برداشت پیدا نشد.", 15f))
            return
        }
        val files = runCatching { SessionPackager.sourceFiles(dir) }.getOrElse {
            root.addView(Ui.label(this, "خواندن فایل‌ها ناموفق بود: ${it.message}", 13f))
            return
        }
        val size = files.sumOf { it.size }
        root.addView(Ui.title(this, "${files.size} فایل • ${SessionRepository.humanBytes(size)}", 18f))
        val hasManifest = File(dir, "session.json").isFile
        root.addView(Ui.label(this,
            if (hasManifest) "برداشت پیدا شد" else "ضبط ناتمام: فایل‌ها قابل بررسی و خروجی هستند", 12f))
        Ui.addSpacer(root, 12)

        status = Ui.label(this, "برای خروجی، Downloads یا حافظه دلخواه را انتخاب کن.", 13f)
        root.addView(Ui.primaryButton(this, "ذخیره ZIP جدید").apply {
            setOnClickListener {
                if (busy) return@setOnClickListener
                zipPicker.launch("${dir.name}_MYCON_RECOVERY.zip")
            }
        }, LinearLayout.LayoutParams(-1, Ui.dp(this, 52)))
        Ui.addSpacer(root, 8)
        root.addView(status)
        Ui.addSpacer(root, 20)

        root.addView(Ui.title(this, "فایل‌های موجود", 17f))
        files.forEach { source ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(Ui.label(this,
                "${source.path}  •  ${SessionRepository.humanBytes(source.size)}", 12.5f))
            Ui.addSpacer(row, 4)
            row.addView(Ui.button(this, "ذخیره این فایل", Ui.SURFACE_2).apply {
                setOnClickListener {
                    if (busy) return@setOnClickListener
                    chosenFile = source.file
                    filePicker.launch(source.file.name)
                }
            }, LinearLayout.LayoutParams(-1, Ui.dp(this, 43)))
            Ui.addSpacer(row, 12)
            root.addView(row)
        }
    }

    private fun progress(label: String) = runOnUiThread { status.text = label }

    private fun saveZip(uri: Uri) {
        val dir = sessionDir ?: return
        if (busy) return
        busy = true
        progress("در حال ساخت ZIP جدید…")
        Thread {
            try {
                val files = SessionPackager.sourceFiles(dir)
                val expected = contentResolver.openOutputStream(uri, "w")?.use {
                    SessionPackager.zipToStream(dir, it)
                } ?: error("Storage destination unavailable")
                progress("در حال بررسی ZIP ذخیره‌شده…")
                val input = contentResolver.openInputStream(uri)
                    ?: error("Cannot re-open the exported ZIP")
                input.use { SessionPackager.verifyZip(it, files, expected) }
                progress("✓ ZIP کامل بررسی شد • ${expected.count} فایل")
            } catch (e: Exception) {
                progress("⚠ خروجی تأیید نشد: ${e.message} • دوباره امتحان کن؛ اصل برداشت سالم مانده.")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun saveFile(uri: Uri, original: File) {
        val dir = sessionDir ?: return
        if (!repository.isSessionFolder(dir) ||
            runCatching { !original.canonicalPath.startsWith(dir.canonicalPath + File.separator) }.getOrDefault(true)) {
            progress("مسیر فایل معتبر نیست")
            return
        }
        if (busy) return
        busy = true
        progress("در حال کپی فایل اصلی…")
        Thread {
            try {
                val originalSize = original.length()
                val originalModified = original.lastModified()
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(128 * 1024)
                var count = 0L
                val target = contentResolver.openOutputStream(uri, "w")
                    ?: error("Cannot open destination")
                target.use { output ->
                    BufferedOutputStream(output, 128 * 1024).use { buffered ->
                        BufferedInputStream(original.inputStream(), 128 * 1024).use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n == -1) break
                                buffered.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                count += n
                            }
                        }
                    }
                }
                require(count == originalSize && original.lastModified() == originalModified) {
                    "Source changed while copying"
                }
                progress("در حال تأیید فایل ذخیره‌شده…")
                val check = MessageDigest.getInstance("SHA-256")
                val input = contentResolver.openInputStream(uri) ?: error("Cannot verify copy")
                var exportedSize = 0L
                input.use { stream ->
                    while (true) {
                        val n = stream.read(buffer)
                        if (n == -1) break
                        check.update(buffer, 0, n)
                        exportedSize += n
                    }
                }
                require(exportedSize == count &&
                    digest.digest().contentEquals(check.digest())) { "Copied file differs from original" }
                progress("✓ فایل به‌طور کامل ذخیره و تأیید شد")
            } catch (e: Exception) {
                progress("⚠ کپی تأیید نشد: ${e.message} • فایل اصلی تغییر نکرده.")
            } finally {
                busy = false
            }
        }.start()
    }

    companion object {
        const val EXTRA_SESSION_PATH = "session_path"
    }
}
