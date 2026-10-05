package ir.mycon.capture

import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

class QrFrameScanner(
    private val onDetection: (Detection) -> Unit
) {
    data class Detection(
        val timestampNs: Long,
        val raw: String,
        val corners: List<Pair<Int, Int>>,
        val cameraPose7: FloatArray,
        val intrinsics4: FloatArray,
        val imageDims: IntArray
    )

    private val options =
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

    private val scanner: BarcodeScanner =
        BarcodeScanning.getClient(options)

    private val busy = AtomicBoolean(false)
    private var frameNo = 0L
    private val lastAcceptedNs = mutableMapOf<String, Long>()

    fun maybeScan(frame: Frame) {
        frameNo++

        if (frameNo % 6L != 0L) return
        if (busy.get()) return
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        val image = try {
            frame.acquireCameraImage()
        } catch (_: Exception) {
            return
        }

        busy.set(true)

        val pose = frame.camera.pose
        val t = pose.translation
        val q = pose.rotationQuaternion
        val pose7 = floatArrayOf(
            t[0], t[1], t[2],
            q[0], q[1], q[2], q[3]
        )

        val intrinsics = frame.camera.imageIntrinsics
        val focal = intrinsics.focalLength
        val principal = intrinsics.principalPoint
        val dims = intrinsics.imageDimensions
        val intrinsics4 = floatArrayOf(
            focal[0],
            focal[1],
            principal[0],
            principal[1]
        )
        val imageDims = intArrayOf(dims[0], dims[1])
        val timestampNs = frame.timestamp

        // Rotation is intentionally 0 so ML Kit corner coordinates stay in the
        // raw camera-image coordinate system used by ARCore imageIntrinsics.
        val input = InputImage.fromMediaImage(image, 0)

        scanner.process(input)
            .addOnSuccessListener { codes ->
                for (barcode in codes) {
                    val raw = barcode.rawValue ?: continue
                    if (AnchorPayload.parse(raw) == null) continue

                    val points =
                        barcode.cornerPoints?.map { it.x to it.y } ?: continue
                    if (points.size != 4) continue

                    val previous = lastAcceptedNs[raw]
                    if (
                        previous != null &&
                        timestampNs > previous &&
                        timestampNs - previous < 750_000_000L
                    ) {
                        continue
                    }
                    lastAcceptedNs[raw] = timestampNs

                    onDetection(
                        Detection(
                            timestampNs = timestampNs,
                            raw = raw,
                            corners = points,
                            cameraPose7 = pose7,
                            intrinsics4 = intrinsics4,
                            imageDims = imageDims
                        )
                    )
                }
            }
            .addOnCompleteListener {
                image.close()
                busy.set(false)
            }
    }

    fun close() {
        scanner.close()
    }
}
