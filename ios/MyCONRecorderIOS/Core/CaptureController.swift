import Foundation
import ARKit
import RealityKit
import Vision
import CoreMotion
import CoreLocation
import UIKit

@MainActor
final class CaptureController: NSObject, ObservableObject {
    @Published var isRecording = false
    @Published var trackingText = "شروع AR…"
    @Published var projectHint = ""
    @Published var qrText = "QR: —"
    @Published var latestPackageURL: URL?
    @Published var warningText = ""

    private weak var arView: ARView?
    private var package: CapturePackageWriter?
    private var video: VideoRecorder?
    private let motion = CMMotionManager()
    private let location = CLLocationManager()
    private var latestLocation: CLLocation?
    private var frameIndex = 0
    private var qrBusy = false
    private let qrQueue = DispatchQueue(label: "ir.mycon.ios.qr", qos: .userInitiated)
    private var modelObservations: [String: [MarkerSolveResult]] = [:]
    private var renderedModels = Set<String>()

    override init() {
        super.init()
        location.delegate = self
        location.desiredAccuracy = kCLLocationAccuracyBest
        location.requestWhenInUseAuthorization()
        location.startUpdatingLocation()
    }

    func attach(_ view: ARView) {
        if arView === view { return }
        arView = view
        view.automaticallyConfigureSession = false
        view.session.delegate = self

        guard ARWorldTrackingConfiguration.isSupported else {
            trackingText = "ARKit روی این دستگاه پشتیبانی نمی‌شود"
            return
        }
        let c = ARWorldTrackingConfiguration()
        c.worldAlignment = .gravity
        c.planeDetection = [.horizontal, .vertical]
        c.environmentTexturing = .automatic
        view.session.run(c, options: [.resetTracking, .removeExistingAnchors])
    }

    func startRecording() {
        guard !isRecording else { return }
        do {
            let p = try CapturePackageWriter()
            package = p
            video = VideoRecorder(outputURL: p.videoURL)
            startMotion()
            isRecording = true
            warningText = ""
        } catch {
            warningText = "شروع ضبط ناموفق: \(error.localizedDescription)"
        }
    }

    func stopRecording() {
        guard isRecording else { return }
        isRecording = false
        stopMotion()
        let package = self.package
        self.package = nil
        let hint = projectHint.trimmingCharacters(in: .whitespacesAndNewlines)
        let video = self.video
        self.video = nil
        video?.stop { [weak self] in
            guard let self, let package else { return }
            do {
                let url = try package.finalize(projectHint: hint.isEmpty ? nil : hint)
                Task { @MainActor in
                    self.latestPackageURL = url
                    self.warningText = "بسته MyCON آماده شد"
                }
            } catch {
                Task { @MainActor in
                    self.warningText = "بستن بسته ناموفق: \(error.localizedDescription)"
                }
            }
        }
    }

    private func startMotion() {
        if motion.isAccelerometerAvailable {
            motion.accelerometerUpdateInterval = 1.0 / 60.0
            motion.startAccelerometerUpdates(to: .init()) { [weak self] d, _ in
                guard let d else { return }
                self?.package?.recordIMU(timestamp: d.timestamp, sensor: "ACCEL", values: [d.acceleration.x,d.acceleration.y,d.acceleration.z])
            }
        }
        if motion.isGyroAvailable {
            motion.gyroUpdateInterval = 1.0 / 60.0
            motion.startGyroUpdates(to: .init()) { [weak self] d, _ in
                guard let d else { return }
                self?.package?.recordIMU(timestamp: d.timestamp, sensor: "GYRO", values: [d.rotationRate.x,d.rotationRate.y,d.rotationRate.z])
            }
        }
        if motion.isDeviceMotionAvailable {
            motion.deviceMotionUpdateInterval = 1.0 / 60.0
            motion.startDeviceMotionUpdates(to: .init()) { [weak self] d, _ in
                guard let d else { return }
                let q = d.attitude.quaternion
                self?.package?.recordIMU(timestamp: d.timestamp, sensor: "ROT_VEC", values: [q.x,q.y,q.z,q.w])
            }
        }
    }

    private func stopMotion() {
        motion.stopAccelerometerUpdates()
        motion.stopGyroUpdates()
        motion.stopDeviceMotionUpdates()
    }

    private func scanQR(frame: ARFrame) {
        guard !qrBusy else { return }
        qrBusy = true
        let buffer = frame.capturedImage
        let K = frame.camera.intrinsics
        let cameraTransform = frame.camera.transform
        let resolution = frame.camera.imageResolution

        qrQueue.async { [weak self] in
            let request = VNDetectBarcodesRequest()
            let handler = VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .up, options: [:])
            try? handler.perform([request])
            let observations = (request.results ?? []).compactMap { $0 as? VNBarcodeObservation }
            guard let obs = observations.first(where: { ($0.payloadStringValue ?? "").hasPrefix("mycon://anchor/v1") }),
                  let raw = obs.payloadStringValue else {
                Task { @MainActor in self?.qrBusy = false }
                return
            }

            let w = Float(resolution.width), h = Float(resolution.height)
            func px(_ p: CGPoint) -> SIMD2<Float> { SIMD2(Float(p.x) * w, (1 - Float(p.y)) * h) }
            let corners = [px(obs.topLeft), px(obs.topRight), px(obs.bottomRight), px(obs.bottomLeft)]
            let parsed = AnchorPayload.parse(raw)
            let solve = parsed.flatMap {
                PlanarMarkerSolver.solve(
                    cornersPx: corners,
                    markerSizeM: Float($0.sizeMm / 1000.0),
                    intrinsics: K,
                    worldFromARCamera: cameraTransform
                )
            }

            self?.package?.recordQR(raw: raw, parsed: parsed, cornersPx: corners, frame: frame, solve: solve)

            Task { @MainActor in
                guard let self else { return }
                self.qrBusy = false
                if let parsed {
                    self.qrText = "QR: \(parsed.project) / \(parsed.anchor)"
                    if self.projectHint.isEmpty { self.projectHint = parsed.project }
                    if let solve, solve.reprojectionErrorPx <= 5 {
                        self.acceptModelObservation(parsed: parsed, solve: solve)
                    }
                } else {
                    self.qrText = "QR نامعتبر"
                }
            }
        }
    }

    private func acceptModelObservation(parsed: AnchorPayload, solve: MarkerSolveResult) {
        guard parsed.hasModel, let arView else { return }
        let key = "\(parsed.project)/\(parsed.anchor)/\(parsed.modelId)"
        guard !renderedModels.contains(key) else { return }
        var list = modelObservations[key] ?? []
        list.append(solve)
        if list.count > 12 { list.removeFirst(list.count - 12) }
        modelObservations[key] = list
        guard list.count >= 8 else { return }

        let positions = list.map { SIMD3<Float>($0.worldFromMarker.columns.3.x,$0.worldFromMarker.columns.3.y,$0.worldFromMarker.columns.3.z) }
        let mean = positions.reduce(SIMD3<Float>(repeating: 0), +) / Float(positions.count)
        let maxSpread = positions.map { simd_length($0 - mean) }.max() ?? .infinity
        let q0 = simd_quatf(list[0].worldFromMarker)
        let maxAngle = list.map {
            let q = simd_quatf($0.worldFromMarker)
            let d = min(1, abs(simd_dot(q0.vector, q.vector)))
            return 2 * acos(d) * 180 / .pi
        }.max() ?? .infinity

        guard maxSpread <= 0.035, maxAngle <= 6 else { return }
        var stable = list.last!.worldFromMarker
        stable.columns.3 = SIMD4(mean.x, mean.y, mean.z, 1)

        let anchor = AnchorEntity(world: stable)
        let size = Float(parsed.modelSizeM ?? 1)
        let material = SimpleMaterial(color: UIColor.systemCyan.withAlphaComponent(0.75), isMetallic: false)
        let model = ModelEntity(mesh: .generateBox(size: size), materials: [material])
        anchor.addChild(model)
        arView.scene.addAnchor(anchor)
        renderedModels.insert(key)
    }
}

extension CaptureController: ARSessionDelegate {
    nonisolated func session(_ session: ARSession, didUpdate frame: ARFrame) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            switch frame.camera.trackingState {
            case .normal: self.trackingText = "TRACKING"
            case .notAvailable: self.trackingText = "NOT AVAILABLE"
            case .limited(let reason): self.trackingText = "LIMITED • \(reason)"
            }
            if self.isRecording {
                self.package?.recordFrame(frame, location: self.latestLocation)
                self.video?.append(pixelBuffer: frame.capturedImage, timestamp: frame.timestamp)
            }
            self.frameIndex += 1
            if self.frameIndex % 12 == 0 { self.scanQR(frame: frame) }
        }
    }
}

extension CaptureController: CLLocationManagerDelegate {
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        Task { @MainActor [weak self] in self?.latestLocation = last }
    }
}
