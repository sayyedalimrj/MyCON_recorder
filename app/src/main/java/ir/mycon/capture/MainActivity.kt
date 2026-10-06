package ir.mycon.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.RecordingConfig
import com.google.ar.core.Session
import com.google.ar.core.Track
import com.google.ar.core.TrackingState
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var settings: AppSettings
    private lateinit var repository: SessionRepository
    private lateinit var qualityMonitor: CaptureQualityMonitor

    private lateinit var glView: GLSurfaceView
    private lateinit var topCard: MaterialCardView
    private lateinit var bottomDock: MaterialCardView
    private lateinit var guideCard: MaterialCardView
    private lateinit var guideTitle: TextView
    private lateinit var guideDetail: TextView
    private lateinit var recordingStatus: TextView
    private lateinit var trackingPill: TextView
    private lateinit var projectPill: TextView
    private lateinit var gpsPill: TextView
    private lateinit var anchorPill: TextView
    private lateinit var recordButton: MaterialButton
    private lateinit var exportButton: MaterialButton

    private val background = BackgroundRenderer()
    private var arSession: Session? = null
    private var installRequested = false
    private var arResumed = false
    private var viewportWidth = 1
    private var viewportHeight = 1

    @Volatile
    private var recording = false

    @Volatile
    private var latestTrackingState: TrackingState = TrackingState.PAUSED

    private var telemetry: TelemetryRecorder? = null
    private var currentSessionDir: File? = null
    private var recordingStartedElapsedMs = 0L
    private var lastUiUpdateElapsedMs = 0L
    private var lastQrSeenElapsedMs = 0L
    private var lastFeedbackAnchor = ""

    private val locationTracker by lazy { LocationTracker(this) }
    private val pendingTrackData = ConcurrentLinkedQueue<ByteArray>()

    private var latestAnchorLabel = "—"
    private var activeProject: String? = null
    private val sessionAnchors = linkedSetOf<String>()
    private val rejectedProjects = mutableSetOf<String>()

    private var tone: ToneGenerator? = null

    private val qrScanner =
        QrFrameScanner { detection ->
            onQrDetection(detection)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        settings.applyTheme()
        super.onCreate(savedInstanceState)

        repository = SessionRepository(this)
        qualityMonitor = CaptureQualityMonitor(this)
        activeProject = settings.lastProject.takeIf { it.isNotBlank() }

        Ui.edgeToEdge(this)
        buildUi()
        requestNeededPermissions()
    }

    private fun buildUi() {
        val root = FrameLayout(this)

        glView =
            GLSurfaceView(this).apply {
                setEGLContextClientVersion(2)
                preserveEGLContextOnPause = true
                setRenderer(this@MainActivity)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        root.addView(
            glView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        topCard = Ui.card(this, alphaSurface = true)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Ui.title(this, "MyCON Recorder", 19f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        recordingStatus = Ui.pill(this, "READY", Ui.BLUE)
        header.addView(recordingStatus)
        top.addView(header)

        Ui.addSpacer(top, 9)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        trackingPill = Ui.pill(this, "Tracking …", Ui.AMBER)
        projectPill = Ui.pill(this, activeProject?.let { "Project $it" } ?: "Project —", if (activeProject == null) Ui.AMBER else Ui.BLUE).apply {
            setOnClickListener { showProjectDialog() }
        }
        gpsPill = Ui.pill(this, "GPS …", Ui.AMBER)

        val chipLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = Ui.dp(this@MainActivity, 6)
        }
        row1.addView(trackingPill, chipLp)
        row1.addView(projectPill, chipLp)
        row1.addView(gpsPill, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(row1)

        Ui.addSpacer(top, 7)

        anchorPill = Ui.pill(this, "Anchor —", Ui.PURPLE)
        top.addView(anchorPill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        topCard.addView(top)

        root.addView(
            topCard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                leftMargin = Ui.dp(this@MainActivity, 8)
                rightMargin = Ui.dp(this@MainActivity, 8)
                topMargin = Ui.dp(this@MainActivity, 8)
            }
        )

        guideCard = Ui.card(this, alphaSurface = true).apply {
            setCardBackgroundColor(ColorUtils.setAlphaComponent(Ui.SURFACE_SOLID, 220))
        }
        val guideBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 14))
        }
        guideTitle = Ui.title(this, "در حال آماده‌سازی…", 16f).apply { gravity = Gravity.CENTER }
        guideDetail = Ui.label(this, "ARCore را آماده می‌کنیم.", 12f).apply { gravity = Gravity.CENTER }
        guideBox.addView(guideTitle)
        guideBox.addView(guideDetail)
        guideCard.addView(guideBox)
        root.addView(
            guideCard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = Ui.dp(this@MainActivity, 26)
                rightMargin = Ui.dp(this@MainActivity, 26)
            }
        )

        bottomDock = Ui.card(this, alphaSurface = true)
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 10))
        }

        recordButton = Ui.primaryButton(this, "● شروع برداشت").apply {
            setOnClickListener { toggleRecording() }
        }
        dock.addView(recordButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 60)))

        Ui.addSpacer(dock, 8)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qrButton = Ui.button(this, "QR", Ui.SURFACE_2).apply {
            setOnClickListener {
                startActivity(Intent(this@MainActivity, QrGeneratorActivity::class.java))
            }
        }
        val sessionsButton = Ui.button(this, "برداشت‌ها", Ui.SURFACE_2).apply {
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SessionManagerActivity::class.java))
            }
        }
        exportButton = Ui.button(this, "ZIP", Ui.SURFACE_2).apply {
            setOnClickListener { shareLatestSession() }
        }
        val settingsButton = Ui.button(this, "تنظیمات", Ui.SURFACE_2).apply {
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        val actionLp = LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f).apply {
            marginEnd = Ui.dp(this@MainActivity, 5)
        }
        actions.addView(qrButton, actionLp)
        actions.addView(sessionsButton, actionLp)
        actions.addView(exportButton, actionLp)
        actions.addView(settingsButton, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        dock.addView(actions)
        bottomDock.addView(dock)

        root.addView(
            bottomDock,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = Ui.dp(this@MainActivity, 8)
                rightMargin = Ui.dp(this@MainActivity, 8)
                bottomMargin = Ui.dp(this@MainActivity, 8)
            }
        )

        Ui.insetOverlayPanels(root, topCard, bottomDock)
        setContentView(root)
        refreshExportAvailability()
    }

    private fun refreshExportAvailability() {
        exportButton.isEnabled =
            currentSessionDir?.let { File(it, "session.json").exists() } == true ||
                repository.latest() != null
        exportButton.alpha = if (exportButton.isEnabled) 1f else 0.45f
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.CAMERA
        }

        if (
            settings.gpsLogging &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                needed.toTypedArray(),
                PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            resumeArIfPossible()
        } else {
            toast("بدون مجوز دوربین امکان برداشت وجود ندارد.")
        }

        startLocationIfAllowed()
    }

    override fun onResume() {
        super.onResume()
        settings = AppSettings(this)
        qualityMonitor.start()
        resumeArIfPossible()
        refreshExportAvailability()
    }

    private fun startLocationIfAllowed() {
        if (
            settings.gpsLogging &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationTracker.start()
        } else {
            locationTracker.stop()
        }
    }

    private fun resumeArIfPossible() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            if (arSession == null) {
                when (
                    ArCoreApk.getInstance()
                        .requestInstall(this, !installRequested)
                ) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }

                arSession =
                    Session(this).also { session ->
                        val config =
                            Config(session).apply {
                                focusMode = Config.FocusMode.AUTO
                                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            }
                        session.configure(config)
                    }
            }

            if (!arResumed) {
                arSession?.resume()
                glView.onResume()
                arResumed = true
            }

            startLocationIfAllowed()
        } catch (e: Exception) {
            setGuide("ARCore آماده نشد", e.message ?: e.javaClass.simpleName, Ui.RED)
            toast("ARCore: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun onPause() {
        if (recording) {
            stopRecording(openSummary = false)
        }

        locationTracker.stop()
        qualityMonitor.stop()

        if (arResumed) {
            try {
                glView.onPause()
                arSession?.pause()
            } catch (_: Exception) {
            }
            arResumed = false
        }

        super.onPause()
    }

    override fun onDestroy() {
        qrScanner.close()
        tone?.release()
        tone = null
        try {
            arSession?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        try {
            arSession?.setCameraTextureName(background.textureId)
        } catch (_: Exception) {
        }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = arSession ?: return
        if (!arResumed) return

        try {
            if (background.textureId >= 0) {
                session.setCameraTextureName(background.textureId)
            }

            @Suppress("DEPRECATION")
            val displayRotation = windowManager.defaultDisplay.rotation

            session.setDisplayGeometry(
                displayRotation,
                viewportWidth,
                viewportHeight
            )

            val frame = session.update()

            if (frame.timestamp != 0L) {
                background.draw(frame)
            }

            onArFrame(frame)
        } catch (_: Exception) {
        }
    }

    private fun onArFrame(frame: Frame) {
        latestTrackingState = frame.camera.trackingState

        if (recording) {
            telemetry?.recordFrame(frame)

            while (true) {
                val bytes = pendingTrackData.poll() ?: break
                try {
                    frame.recordTrackData(
                        QR_TRACK_UUID,
                        ByteBuffer.wrap(bytes)
                    )
                } catch (_: Exception) {
                    break
                }
            }
        }

        if (settings.autoQrScan) {
            qrScanner.maybeScan(frame)
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdateElapsedMs < 250L) return
        lastUiUpdateElapsedMs = now

        updateHud(now)
    }

    private fun updateHud(now: Long) {
        val tracking = latestTrackingState
        val location = if (settings.gpsLogging) locationTracker.latest() else null

        runOnUiThread {
            Ui.setPill(
                trackingPill,
                when (tracking) {
                    TrackingState.TRACKING -> "Tracking ✓"
                    TrackingState.PAUSED -> "Tracking PAUSED"
                    else -> "Tracking $tracking"
                },
                if (tracking == TrackingState.TRACKING) Ui.GREEN else Ui.RED
            )

            Ui.setPill(
                projectPill,
                activeProject?.let { "Project $it" } ?: "Project —",
                if (activeProject == null) Ui.AMBER else Ui.BLUE
            )

            val gpsText: String
            val gpsColor: Int
            when {
                !settings.gpsLogging -> {
                    gpsText = "GPS OFF"
                    gpsColor = Ui.MUTED
                }
                location == null -> {
                    gpsText = "GPS …"
                    gpsColor = Ui.AMBER
                }
                location.accuracyM <= 10f -> {
                    gpsText = "GPS ±${location.accuracyM.toInt()}m"
                    gpsColor = Ui.GREEN
                }
                location.accuracyM <= 25f -> {
                    gpsText = "GPS ±${location.accuracyM.toInt()}m"
                    gpsColor = Ui.AMBER
                }
                else -> {
                    gpsText = "GPS ±${location.accuracyM.toInt()}m"
                    gpsColor = Ui.RED
                }
            }
            Ui.setPill(gpsPill, gpsText, gpsColor)

            val anchorText =
                if (latestAnchorLabel == "—") {
                    if (recording) "Control 0" else "Anchor —"
                } else {
                    "Anchor ${latestAnchorLabel.substringAfterLast('/')} • ${if (recording) sessionAnchors.size else "seen"}"
                }
            Ui.setPill(
                anchorPill,
                anchorText,
                if (latestAnchorLabel == "—") Ui.AMBER else Ui.PURPLE
            )

            if (recording) {
                val elapsed = (now - recordingStartedElapsedMs).coerceAtLeast(0L)
                val min = elapsed / 60000L
                val sec = (elapsed / 1000L) % 60L
                Ui.setPill(
                    recordingStatus,
                    String.format(Locale.US, "● REC %02d:%02d", min, sec),
                    Ui.RED
                )
            } else {
                Ui.setPill(recordingStatus, "READY", Ui.BLUE)
            }

            updateGuide(location, now)
        }
    }

    private fun updateGuide(location: LocationSample?, now: Long) {
        if (latestTrackingState != TrackingState.TRACKING) {
            setGuide(
                "Tracking آماده نیست",
                "گوشی را آرام به چپ و راست حرکت بده و یک سطح با بافت کافی داخل کادر نگه دار.",
                Ui.RED
            )
            return
        }

        if (activeProject.isNullOrBlank()) {
            setGuide(
                "پروژه را تعیین کن",
                "روی Project بالا بزن یا یک MYCON QR معتبر جلوی دوربین بگیر.",
                Ui.AMBER
            )
            return
        }

        if (recording && sessionAnchors.isEmpty()) {
            setGuide(
                "Control QR ثبت نشده",
                "برای اتصال مسیر ARCore به مختصات پروژه، یک QR کنترل همین پروژه را کامل داخل کادر بگیر.",
                Ui.AMBER
            )
            return
        }

        if (settings.qualityWarnings) {
            if (qualityMonitor.angularSpeedDegPerSec > 120f) {
                setGuide(
                    "حرکت خیلی سریع است",
                    "سرعت چرخش را کم کن؛ حرکت یکنواخت Matching و Pose را پایدارتر می‌کند.",
                    Ui.AMBER
                )
                return
            }

            val lux = qualityMonitor.lightLux
            if (lux != null && lux < 30f) {
                setGuide(
                    "نور محیط کم است",
                    "اگر ممکن است نور را بیشتر کن و از حرکت سریع دوربین پرهیز کن.",
                    Ui.AMBER
                )
                return
            }

            if (
                settings.gpsLogging &&
                location != null &&
                location.accuracyM > 40f
            ) {
                setGuide(
                    "GNSS کم‌دقت است",
                    "مشکلی برای برداشت داخل ساختمان نیست؛ QR کنترل مرجع اصلی مختصات خواهد بود.",
                    Ui.AMBER
                )
                return
            }
        }

        if (recording) {
            val ageSec =
                if (lastQrSeenElapsedMs > 0L) (now - lastQrSeenElapsedMs) / 1000L else Long.MAX_VALUE
            if (ageSec > 180L) {
                setGuide(
                    "برداشت در حال انجام",
                    "سه دقیقه از آخرین Control گذشته؛ اگر Zone عوض شده یک Anchor دیگر اسکن کن.",
                    Ui.BLUE
                )
            } else {
                setGuide(
                    "کیفیت برداشت خوب است",
                    "حرکت آرام و پیوسته را ادامه بده و سطوح را با هم‌پوشانی مناسب پوشش بده.",
                    Ui.GREEN
                )
            }
        } else {
            setGuide(
                "آماده برداشت",
                "Project و Tracking آماده‌اند. بهتر است QR کنترل در ابتدای مسیر داخل کادر باشد.",
                Ui.GREEN
            )
        }
    }

    private fun setGuide(title: String, detail: String, color: Int) {
        guideTitle.text = title
        guideDetail.text = detail
        guideCard.strokeColor = ColorUtils.setAlphaComponent(color, 180)
        guideCard.setCardBackgroundColor(ColorUtils.setAlphaComponent(Ui.SURFACE_SOLID, 225))
    }

    private fun onQrDetection(detection: QrFrameScanner.Detection) {
        val payload = AnchorPayload.parse(detection.raw) ?: return

        val currentProject = activeProject
        if (
            recording &&
            currentProject != null &&
            payload.project != currentProject
        ) {
            if (rejectedProjects.add(payload.project)) {
                toast(
                    "QR پروژه ${payload.project} رد شد؛ Session برای $currentProject قفل است."
                )
            }
            return
        }

        if (!recording && payload.project != currentProject) {
            activeProject = payload.project
            settings.lastProject = payload.project
        } else if (activeProject == null) {
            activeProject = payload.project
            settings.lastProject = payload.project
        }

        latestAnchorLabel = "${payload.project}/${payload.anchor}"
        lastQrSeenElapsedMs = SystemClock.elapsedRealtime()
        performQrFeedback(latestAnchorLabel)

        if (recording) {
            sessionAnchors += latestAnchorLabel

            val event =
                telemetry?.recordQrEvent(
                    frameTimestampNs = detection.timestampNs,
                    raw = detection.raw,
                    corners = detection.corners,
                    cameraPose = detection.cameraPose7,
                    intrinsics = detection.intrinsics4,
                    imageDims = detection.imageDims
                )

            if (event != null) {
                pendingTrackData.offer(
                    event.toString().toByteArray(Charsets.UTF_8)
                )
            }
        }
    }

    private fun performQrFeedback(anchor: String) {
        val now = SystemClock.elapsedRealtime()
        if (anchor == lastFeedbackAnchor && now - lastQrSeenElapsedMs < 2500L) {
            return
        }
        lastFeedbackAnchor = anchor

        if (settings.hapticFeedback) {
            try {
                val vibrator: Vibrator =
                    if (Build.VERSION.SDK_INT >= 31) {
                        (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                            .defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        (getSystemService(VIBRATOR_SERVICE) as Vibrator)
                    }
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            45L,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(45L)
                }
            } catch (_: Exception) {
            }
        }

        if (settings.soundFeedback) {
            try {
                if (tone == null) {
                    tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
                }
                tone?.startTone(ToneGenerator.TONE_PROP_ACK, 90)
            } catch (_: Exception) {
            }
        }
    }

    private fun showProjectDialog() {
        if (recording) {
            toast("Project در حین ضبط قفل است.")
            return
        }

        val input = EditText(this).apply {
            hint = "مثلاً P001"
            setSingleLine(true)
            setText(activeProject ?: settings.lastProject)
            setSelection(text.length)
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 10))
        }

        AlertDialog.Builder(this)
            .setTitle("Project")
            .setMessage("شناسه پروژه را ثبت کن؛ MYCON QRهای یک Session باید همین Project ID را داشته باشند.")
            .setView(input)
            .setNegativeButton("لغو", null)
            .setNeutralButton("پاک کردن") { _, _ ->
                activeProject = null
                settings.lastProject = ""
                latestAnchorLabel = "—"
            }
            .setPositiveButton("ثبت") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    activeProject = value
                    settings.lastProject = value
                    latestAnchorLabel = "—"
                }
            }
            .show()
    }

    private fun toggleRecording() {
        if (recording) {
            stopRecording(openSummary = true)
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val session =
            arSession ?: return toast("ARCore هنوز آماده نیست.")

        if (activeProject.isNullOrBlank()) {
            showProjectDialog()
            toast("قبل از ضبط Project را تعیین کن.")
            return
        }

        if (latestTrackingState != TrackingState.TRACKING) {
            toast("Tracking هنوز پایدار نیست؛ چند لحظه دوربین را آرام حرکت بده.")
            return
        }

        val dir =
            File(
                repository.sessionsDir,
                sessionFolderName()
            ).apply { mkdirs() }

        val mp4 = File(dir, "arcore_recording.mp4")

        pendingTrackData.clear()
        sessionAnchors.clear()
        rejectedProjects.clear()
        latestAnchorLabel = "—"
        lastQrSeenElapsedMs = 0L

        try {
            val qrTrack =
                Track(session)
                    .setId(QR_TRACK_UUID)
                    .setMimeType("application/vnd.mycon.anchor+json")

            val config =
                RecordingConfig(session)
                    .setMp4DatasetUri(Uri.fromFile(mp4))
                    .setAutoStopOnPause(false)
                    .addTrack(qrTrack)

            session.startRecording(config)

            currentSessionDir = dir
            telemetry =
                TelemetryRecorder(
                    context = this,
                    sessionDir = dir,
                    locationTracker = locationTracker
                )
            recording = true
            recordingStartedElapsedMs = SystemClock.elapsedRealtime()

            if (settings.keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            runOnUiThread {
                recordButton.text = "■ پایان برداشت"
                recordButton.backgroundTintList = ColorStateList.valueOf(Ui.RED)
                exportButton.isEnabled = false
                exportButton.alpha = 0.45f
            }

            toast("برداشت شروع شد؛ یک Control QR همین پروژه را در ابتدای مسیر اسکن کن.")
        } catch (e: Exception) {
            try {
                dir.deleteRecursively()
            } catch (_: Exception) {
            }
            toast("شروع ضبط ناموفق: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopRecording(openSummary: Boolean) {
        if (!recording) return
        recording = false

        try {
            arSession?.stopRecording()
        } catch (e: Exception) {
            toast("هشدار پایان ضبط: ${e.message ?: e.javaClass.simpleName}")
        }

        telemetry?.close(
            projectHint = activeProject,
            mp4Name = "arcore_recording.mp4"
        )
        telemetry = null
        pendingTrackData.clear()

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        runOnUiThread {
            recordButton.text = "● شروع برداشت"
            recordButton.backgroundTintList = ColorStateList.valueOf(Ui.GREEN)
            refreshExportAvailability()
        }

        val dir = currentSessionDir
        toast("برداشت ذخیره شد • Anchorهای یکتا: ${sessionAnchors.size}")

        if (openSummary && dir != null && File(dir, "session.json").exists()) {
            startActivity(
                Intent(this, SessionSummaryActivity::class.java)
                    .putExtra(SessionSummaryActivity.EXTRA_SESSION_PATH, dir.absolutePath)
            )
        }
    }

    private fun shareLatestSession() {
        val dir =
            currentSessionDir?.takeIf { File(it, "session.json").exists() }
                ?: repository.latest()?.dir

        if (dir == null) {
            toast("هنوز Session قابل خروجی وجود ندارد.")
            return
        }

        try {
            repository.share(this, dir)
        } catch (e: Exception) {
            toast("ZIP: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun sessionFolderName(): String {
        val format =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US
            )
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 100

        val QR_TRACK_UUID: UUID =
            UUID.fromString("34b0af27-8c4c-46cf-b55b-ec02d11601a1")
    }
}
