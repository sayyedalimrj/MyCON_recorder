package ir.mycon.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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

    private lateinit var glView: GLSurfaceView
    private lateinit var statusView: TextView
    private lateinit var recordButton: Button
    private lateinit var exportButton: Button

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

    private val locationTracker by lazy { LocationTracker(this) }
    private val pendingTrackData = ConcurrentLinkedQueue<ByteArray>()

    private var latestAnchorLabel = "—"
    private var activeProject: String? = null
    private val sessionAnchors = linkedSetOf<String>()
    private val rejectedProjects = mutableSetOf<String>()

    private var lastUiUpdateNs = 0L

    private val qrScanner =
        QrFrameScanner { detection ->
            onQrDetection(detection)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        statusView =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0x99000000.toInt())
                textSize = 14f
                setPadding(24, 20, 24, 20)
                text = "در حال آماده‌سازی ARCore…"
            }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        val bottomBar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(8, 8, 8, 20)
                setBackgroundColor(0x88000000.toInt())
            }

        recordButton =
            Button(this).apply {
                text = "● شروع برداشت"
                setOnClickListener { toggleRecording() }
            }

        val qrButton =
            Button(this).apply {
                text = "QR پروژه"
                setOnClickListener {
                    startActivity(
                        Intent(
                            this@MainActivity,
                            QrGeneratorActivity::class.java
                        )
                    )
                }
            }

        exportButton =
            Button(this).apply {
                text = "خروجی ZIP"
                isEnabled = false
                setOnClickListener { exportLastSession() }
            }

        bottomBar.addView(recordButton)
        bottomBar.addView(qrButton)
        bottomBar.addView(exportButton)

        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        setContentView(root)
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
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

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

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationTracker.start()
        }
    }

    override fun onResume() {
        super.onResume()
        resumeArIfPossible()
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
                                updateMode =
                                    Config.UpdateMode.LATEST_CAMERA_IMAGE
                            }
                        session.configure(config)
                    }
            }

            if (!arResumed) {
                arSession?.resume()
                glView.onResume()
                arResumed = true
            }

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                locationTracker.start()
            }
        } catch (e: Exception) {
            toast("ARCore: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun onPause() {
        if (recording) {
            stopRecording()
        }

        locationTracker.stop()

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
        try {
            arSession?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onSurfaceCreated(
        unused: GL10?,
        config: EGLConfig?
    ) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()

        try {
            arSession?.setCameraTextureName(background.textureId)
        } catch (_: Exception) {
        }
    }

    override fun onSurfaceChanged(
        unused: GL10?,
        width: Int,
        height: Int
    ) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES20.glClear(
            GLES20.GL_COLOR_BUFFER_BIT or
                GLES20.GL_DEPTH_BUFFER_BIT
        )

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
            // Transient camera/GL errors are ignored here; lifecycle errors
            // are surfaced by resume/start/stop paths instead of spamming UI.
        }
    }

    private fun onArFrame(frame: Frame) {
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
                    // Sidecar JSONL is authoritative if MP4 custom-track
                    // insertion fails on a device/frame.
                    break
                }
            }
        }

        qrScanner.maybeScan(frame)

        if (
            frame.timestamp - lastUiUpdateNs < 250_000_000L &&
            lastUiUpdateNs != 0L
        ) {
            return
        }
        lastUiUpdateNs = frame.timestamp

        val tracking = frame.camera.trackingState
        latestTrackingState = tracking
        val project = activeProject ?: "NO CONTROL"
        val rec = if (recording) "● REC" else "READY"

        runOnUiThread {
            statusView.text =
                "$rec  |  Tracking: $tracking\n" +
                    "Project: $project  |  Anchor: $latestAnchorLabel" +
                    if (recording) {
                        "  |  Controls: ${sessionAnchors.size}"
                    } else {
                        ""
                    }
        }
    }

    private fun onQrDetection(
        detection: QrFrameScanner.Detection
    ) {
        val payload = AnchorPayload.parse(detection.raw) ?: return

        val currentProject = activeProject
        if (
            recording &&
            currentProject != null &&
            payload.project != currentProject
        ) {
            if (rejectedProjects.add(payload.project)) {
                toast(
                    "QR پروژه ${payload.project} نادیده گرفته شد؛ " +
                        "این برداشت برای پروژه $currentProject است."
                )
            }
            return
        }

        if (activeProject == null) {
            activeProject = payload.project
        }

        latestAnchorLabel =
            "${payload.project}/${payload.anchor}"

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

    private fun toggleRecording() {
        if (recording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val session =
            arSession ?: return toast("ARCore هنوز آماده نیست.")

        if (
            latestTrackingState != TrackingState.TRACKING
        ) {
            toast(
                "Tracking هنوز پایدار نیست. چند لحظه دوربین را آرام حرکت بده."
            )
            return
        }

        val base = getExternalFilesDir(null) ?: filesDir
        val dir =
            File(
                base,
                "sessions/${sessionFolderName()}"
            ).apply {
                mkdirs()
            }
        val mp4 = File(dir, "arcore_recording.mp4")

        pendingTrackData.clear()
        sessionAnchors.clear()
        rejectedProjects.clear()

        try {
            val qrTrack =
                Track(session)
                    .setId(QR_TRACK_UUID)
                    .setMimeType(
                        "application/vnd.mycon.anchor+json"
                    )

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

            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )

            runOnUiThread {
                recordButton.text = "■ پایان برداشت"
                exportButton.isEnabled = false
            }

            if (activeProject == null) {
                toast(
                    "برداشت شروع شد؛ هنوز Control QR ندیده‌ای. " +
                        "همین اول یک QR پروژه را کامل داخل کادر بگیر."
                )
            } else {
                toast(
                    "برداشت شروع شد؛ QR کنترل را در ابتدای مسیر دوباره اسکن کن."
                )
            }
        } catch (e: Exception) {
            try {
                dir.deleteRecursively()
            } catch (_: Exception) {
            }
            toast(
                "شروع ضبط ناموفق: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false

        try {
            arSession?.stopRecording()
        } catch (e: Exception) {
            toast(
                "هشدار پایان ضبط: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        telemetry?.close(
            projectHint = activeProject,
            mp4Name = "arcore_recording.mp4"
        )
        telemetry = null
        pendingTrackData.clear()

        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        runOnUiThread {
            recordButton.text = "● شروع برداشت"
            exportButton.isEnabled =
                currentSessionDir?.exists() == true
        }

        toast(
            "برداشت ذخیره شد. Controls ثبت‌شده: ${sessionAnchors.size}"
        )
    }

    private fun exportLastSession() {
        val dir = currentSessionDir ?: return

        try {
            val zip =
                File(
                    dir.parentFile,
                    "${dir.name}_MYCON.zip"
                )

            SessionPackager.zip(dir, zip)

            val uri =
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.files",
                    zip
                )

            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    },
                    "ارسال بسته برداشت"
                )
            )
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
            UUID.fromString(
                "34b0af27-8c4c-46cf-b55b-ec02d11601a1"
            )
    }
}
