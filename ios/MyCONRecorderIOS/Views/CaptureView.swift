import SwiftUI
import RealityKit

struct CaptureView: View {
    @ObservedObject var controller: CaptureController
    @State private var showSensors = false

    var body: some View {
        ZStack {
            ARViewContainer(controller: controller)
                .ignoresSafeArea()

            LinearGradient(
                colors: [
                    .black.opacity(0.62),
                    .clear,
                    .black.opacity(0.78)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(spacing: 10) {
                topBar

                HStack(spacing: 7) {
                    metricPill(
                        controller.depthText,
                        icon: controller.lidarAvailable ? "viewfinder" : "camera"
                    )
                    metricPill(controller.mappingText, icon: "map")
                    Button {
                        showSensors = true
                    } label: {
                        Label(controller.sensorSummaryText, systemImage: "waveform.path.ecg")
                            .lineLimit(1)
                            .minimumScaleFactor(0.72)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(.ultraThinMaterial, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
                .font(.caption2.bold())

                if controller.qualityScore < 78 || controller.isRecording {
                    Text(controller.guidanceText)
                        .font(.caption.weight(.semibold))
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 9)
                        .background(
                            guidanceColor.opacity(0.20),
                            in: RoundedRectangle(cornerRadius: 13)
                        )
                }

                if !controller.warningText.isEmpty {
                    Text(controller.warningText)
                        .font(.caption.weight(.semibold))
                        .lineLimit(2)
                        .frame(maxWidth: .infinity)
                        .padding(9)
                        .background(
                            .orange.opacity(0.22),
                            in: RoundedRectangle(cornerRadius: 12)
                        )
                }

                Spacer()

                captureDock
            }
            .padding(.horizontal, 14)
            .padding(.top, 6)
            .padding(.bottom, 10)
        }
        .sheet(isPresented: $showSensors) {
            SensorStatusSheet(controller: controller)
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
        }
    }

    private var topBar: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("MYCON")
                    .font(.caption.bold())
                    .foregroundStyle(.cyan)
                Text(controller.trackingText)
                    .font(.headline)
                    .lineLimit(1)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 1) {
                Text("\(controller.qualityScore)")
                    .font(.title3.monospacedDigit().bold())
                Text("QUALITY")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 11)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var captureDock: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                TextField("Project", text: $controller.projectHint)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.horizontal, 12)
                    .frame(height: 42)
                    .background(
                        .thinMaterial,
                        in: RoundedRectangle(cornerRadius: 13)
                    )

                if controller.isRecording {
                    Text(String(format: "%.1f m", controller.pathLengthM))
                        .font(.caption.monospacedDigit().bold())
                        .padding(.horizontal, 10)
                        .frame(height: 42)
                        .background(.thinMaterial, in: Capsule())
                }
            }

            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(controller.qrText)
                        .font(.caption.bold())
                        .lineLimit(1)
                    Text(controller.lidarAvailable ? "LiDAR + ARKit" : "ARKit")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Button {
                    controller.isRecording
                        ? controller.stopRecording()
                        : controller.startRecording()
                } label: {
                    ZStack {
                        Circle()
                            .fill(.ultraThinMaterial)
                            .frame(width: 74, height: 74)
                        Circle()
                            .stroke(.white.opacity(0.92), lineWidth: 3)
                            .frame(width: 62, height: 62)

                        if controller.isRecording {
                            RoundedRectangle(cornerRadius: 7)
                                .fill(.red)
                                .frame(width: 28, height: 28)
                        } else {
                            Circle()
                                .fill(.red)
                                .frame(width: 48, height: 48)
                        }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(
                    controller.isRecording ? "Stop recording" : "Start recording"
                )
            }
        }
        .padding(12)
        .background(
            .ultraThinMaterial,
            in: RoundedRectangle(cornerRadius: 22)
        )
    }

    private var guidanceColor: Color {
        if controller.qualityScore >= 75 { return .green }
        if controller.qualityScore >= 45 { return .orange }
        return .red
    }

    private func metricPill(_ text: String, icon: String) -> some View {
        Label(text, systemImage: icon)
            .lineLimit(1)
            .minimumScaleFactor(0.72)
            .padding(.horizontal, 9)
            .padding(.vertical, 7)
            .background(.ultraThinMaterial, in: Capsule())
    }
}

private struct SensorStatusSheet: View {
    @ObservedObject var controller: CaptureController

    var body: some View {
        NavigationStack {
            List {
                sensorRow("LiDAR depth", controller.lidarAvailable, "viewfinder")
                sensorRow("Smooth depth", controller.smoothedDepthAvailable, "square.stack.3d.up")
                sensorRow("Scene mesh", controller.meshAvailable, "cube")
                sensorRow("Motion / IMU", controller.motionAvailable, "waveform.path.ecg")
                sensorRow("Barometer", controller.barometerAvailable, "gauge")
                sensorRow("GNSS", controller.gnssAvailable, "location")

                Section {
                    Text("سنسورهای موجود هنگام Capture خودکار ثبت می‌شوند.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Sensors")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func sensorRow(
        _ title: String,
        _ available: Bool,
        _ icon: String
    ) -> some View {
        HStack {
            Label(title, systemImage: icon)
            Spacer()
            Image(systemName: available ? "checkmark.circle.fill" : "minus.circle")
                .foregroundStyle(available ? .green : .secondary)
        }
    }
}

private struct ARViewContainer: UIViewRepresentable {
    let controller: CaptureController

    func makeUIView(context: Context) -> ARView {
        let view = ARView(
            frame: .zero,
            cameraMode: .ar,
            automaticallyConfigureSession: false
        )
        Task { @MainActor in controller.attach(view) }
        return view
    }

    func updateUIView(_ uiView: ARView, context: Context) {}
}
