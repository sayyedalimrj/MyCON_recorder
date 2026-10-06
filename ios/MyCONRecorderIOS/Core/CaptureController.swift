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
    @Published var qualityScore = 0
    @Published var guidanceText = "دوربین را آرام حرکت بده تا Tracking پایدار شود."
    @Published var mappingText = "MAP —"
    @Published var depthText = "RGB"
    @Published var featurePointCount = 0
    @Published var pathLengthM: Double = 0

    private weak var arView: ARView?
    private var package: CapturePackageWriter?
    private var video: VideoRecorder?
    private let motion = CMMotionManager()
    private let location = CLLocationManager()
    private var latestLocation: CLLocation?
    private var latestAngularSpeedRadS: Double = 0
    private var frameIndex = 0
    private var qrBusy = false
    private let qrQueue = DispatchQueue(label: "ir.mycon.ios.qr", qos: .userInitiated)
    private var modelObservations: [String: [MarkerSolveResult]] = [:]
    private var renderedModels = Set<String>()
    private var lastPathPosition: SIMD3<Float>?

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

        if ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) {
            c.frameSemantics.insert(.sceneDepth)
        }
        if ARWorldTrackingConfiguration.supportsSceneReconstruction(.meshWithClassification) {
            c.sceneReconstruction = .meshWithClassification
        } else if ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) {
            c.sceneReconstruction = .mesh
        }

        view.session.run(c, options: [.resetTracking, .removeExistingAnchors])
    }

    func startRecording() {
        guard !isRecording else { return }
        do {
            let p = try CapturePackageWriter()
            package = p
            video = VideoRecorder(outputURL: p.videoURL)
            startMotion()
            pathLengthM = 0
            lastPathPosition = nil
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
        lastPathPosition = nil

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
                self?.package?.recordIMU(
                    timestamp: d.timestamp,
                    sensor: "ACCEL",
                    values: [d.acceleration.x,d.acceleration.y,d.acceleration.z]
                )
            }
        }

        if motion.isGyroAvailable {
            motion.gyroUpdateInterval = 1.0 / 60.0
            motion.startGyroUpdates(to: .init()) { [weak self] d, _ in
                guard let d else { return }
                let speed = sqrt(
                    d.rotationRate.x * d.rotationRate.x +
                    d.rotationRate.y * d.rotationRate.y +
                    d.rotationRate.z * d.rotationRate.z
                )
                self?.package?.recordIMU(
                    timestamp: d.timestamp,
                    sensor: "GYRO",
                    values: [d.rotationRate.x,d.rotationRate.y,d.rotationRate.z]
                )
                Task { @MainActor [weak self] in
                    self?.latestAngularSpeedRadS = speed
                }
            }
        }

        if motion.isDeviceMotionAvailable {
            motion.deviceMotionUpdateInterval = 1.0 / 60.0
            motion.startDeviceMotionUpdates(to: .init()) { [weak self] d, _ in
                guard let d else { return }
                let q = d.attitude.quaternion
                self?.package?.recordIMU(
                    timestamp: d.timestamp,
                    sensor: "ROT_VEC",
                    values: [q.x,q.y,q.z,q.w]
                )
            }
        }
    }

    private func stopMotion() {
        motion.stopAccelerometerUpdates()
        motion.stopGyroUpdates()
        motion.stopDeviceMotionUpdates()
        latestAngularSpeedRadS = 0
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
            request.symbologies = [.qr]
            let handler = VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .up, options: [:])
            try? handler.perform([request])

            let observations = request.results ?? []
            guard let obs = observations.first(where: {
                ($0.payloadStringValue ?? "").hasPrefix("mycon://anchor/v1")
            }), let raw = obs.payloadStringValue else {
                Task { @MainActor in self?.qrBusy = false }
                return
            }

            let w = Float(resolution.width)
            let h = Float(resolution.height)
            func px(_ p: CGPoint) -> SIMD2<Float> {
                SIMD2(Float(p.x) * w, (1 - Float(p.y)) * h)
            }
            let corners = [
                px(obs.topLeft), px(obs.topRight),
                px(obs.bottomRight), px(obs.bottomLeft)
            ]
            let parsed = AnchorPayload.parse(raw)
            let solve = parsed.flatMap {
                PlanarMarkerSolver.solve(
                    cornersPx: corners,
                    markerSizeM: Float($0.sizeMm / 1000.0),
                    intrinsics: K,
                    worldFromARCamera: cameraTransform
                )
            }

            self?.package?.recordQR(
                raw: raw,
                parsed: parsed,
                cornersPx: corners,
                frame: frame,
                solve: solve
            )

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

        let positions = list.map {
            SIMD3<Float>(
                $0.worldFromMarker.columns.3.x,
                $0.worldFromMarker.columns.3.y,
                $0.worldFromMarker.columns.3.z
            )
        }
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
        let material = SimpleMaterial(
            color: UIColor.systemCyan.withAlphaComponent(0.75),
            isMetallic: false
        )
        let model = ModelEntity(
            mesh: .generateBox(size: size),
            materials: [material]
        )
        anchor.addChild(model)
        arView.scene.addAnchor(anchor)
        renderedModels.insert(key)
    }

    private func updateLiveQuality(_ frame: ARFrame) {
        featurePointCount = frame.rawFeaturePoints?.points.count ?? 0
        depthText = frame.sceneDepth != nil || frame.smoothedSceneDepth != nil ? "DEPTH" : "RGB"

        switch frame.worldMappingStatus {
        case .mapped: mappingText = "MAP • MAPPED"
        case .extending: mappingText = "MAP • EXTENDING"
        case .limited: mappingText = "MAP • LIMITED"
        case .notAvailable: mappingText = "MAP • N/A"
        @unknown default: mappingText = "MAP • ?"
        }

        let light = frame.lightEstimate?.ambientIntensity ?? 1000
        var score = 0

        if case .normal = frame.camera.trackingState { score += 35 }

        if featurePointCount >= 250 { score += 25 }
        else if featurePointCount >= 120 { score += 18 }
        else if featurePointCount >= 60 { score += 10 }

        if latestAngularSpeedRadS < 0.8 { score += 20 }
        else if latestAngularSpeedRadS < 1.5 { score += 12 }
        else if latestAngularSpeedRadS < 2.5 { score += 5 }

        if light >= 300 { score += 20 }
        else if light >= 120 { score += 12 }
        else if light >= 60 { score += 5 }

        qualityScore = min(100, score)

        switch frame.camera.trackingState {
        case .notAvailable:
            guidanceText = "Tracking در دسترس نیست؛ دوربین را به سطح با جزئیات برگردان."
        case .limited:
            guidanceText = "Tracking محدود است؛ آهسته حرکت کن و بخش قبلی را دوباره داخل کادر بیاور."
        case .normal:
            if latestAngularSpeedRadS > 2.0 {
                guidanceText = "حرکت خیلی سریع است؛ سرعت چرخش گوشی را کم کن."
            } else if featurePointCount < 70 {
                guidanceText = "ویژگی بصری کم است؛ زاویه را عوض کن یا سطح با بافت/جزئیات بیشتری بگیر."
            } else if light < 100 {
                guidanceText = "نور کم است؛ حرکت را کندتر کن و در صورت امکان نور را بیشتر کن."
            } else if isRecording && qrText == "QR: —" {
                guidanceText = "برداشت خوب است؛ برای مرجع متریک یک Control QR هم ثبت کن."
            } else {
                guidanceText = "کیفیت خوب است؛ حرکت پیوسته، هم‌پوشانی و چند زاویه را حفظ کن."
            }
        }

        if isRecording {
            let p4 = frame.camera.transform.columns.3
            let p = SIMD3<Float>(p4.x, p4.y, p4.z)
            if let previous = lastPathPosition {
                let d = simd_length(p - previous)
                if d > 0.003 && d < 0.50 {
                    pathLengthM += Double(d)
                }
            }
            lastPathPosition = p
        }
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

            self.updateLiveQuality(frame)

            if self.isRecording {
                self.package?.recordFrame(frame, location: self.latestLocation)
                self.package?.recordDepth(frame)
                self.video?.append(
                    pixelBuffer: frame.capturedImage,
                    timestamp: frame.timestamp
                )
            }

            self.frameIndex += 1
            if self.frameIndex % 12 == 0 {
                self.scanQR(frame: frame)
            }
        }
    }
}

extension CaptureController: CLLocationManagerDelegate {
    nonisolated func locationManager(
        _ manager: CLLocationManager,
        didUpdateLocations locations: [CLLocation]
    ) {
        guard let last = locations.last else { return }
        Task { @MainActor [weak self] in
            self?.latestLocation = last
        }
    }
}
