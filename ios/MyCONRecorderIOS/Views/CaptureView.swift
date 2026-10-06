import SwiftUI
import RealityKit

struct CaptureView: View {
    @ObservedObject var controller: CaptureController

    var body: some View {
        ZStack {
            ARViewContainer(controller: controller)
                .ignoresSafeArea()

            LinearGradient(
                colors: [.black.opacity(0.76), .clear, .black.opacity(0.82)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(spacing: 10) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("MYCON • iOS / ARKit")
                            .font(.caption2.bold())
                            .foregroundStyle(.cyan)
                        Text(controller.trackingText)
                            .font(.headline)
                        Text(controller.qrText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 4) {
                        Text("\(controller.qualityScore)")
                            .font(.title2.monospacedDigit().bold())
                        Text("QUALITY")
                            .font(.caption2.bold())
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(14)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))

                HStack(spacing: 8) {
                    metricPill(controller.mappingText, icon: "map")
                    metricPill(
                        controller.depthText,
                        icon: controller.depthText == "DEPTH" ? "sensor.tag.radiowaves.forward" : "camera"
                    )
                    metricPill(
                        "PTS \(controller.featurePointCount)",
                        icon: "circle.grid.cross"
                    )
                    if controller.isRecording {
                        metricPill(
                            String(format: "%.1fm", controller.pathLengthM),
                            icon: "point.topleft.down.curvedto.point.bottomright.up"
                        )
                    }
                }
                .font(.caption2.bold())

                TextField("Project hint", text: $controller.projectHint)
                    .textInputAutocapitalization(.never)
                    .padding(11)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))

                Text(controller.guidanceText)
                    .font(.caption.bold())
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(guidanceColor.opacity(0.23), in: RoundedRectangle(cornerRadius: 13))

                if !controller.warningText.isEmpty {
                    Text(controller.warningText)
                        .font(.caption.bold())
                        .padding(10)
                        .frame(maxWidth: .infinity)
                        .background(.orange.opacity(0.22), in: RoundedRectangle(cornerRadius: 12))
                }

                Spacer()

                HStack(spacing: 28) {
                    Image(systemName: "qrcode.viewfinder")
                        .font(.title2)
                        .frame(width: 52,height: 52)
                        .background(.ultraThinMaterial, in: Circle())
                        .accessibilityLabel("MYCON QR auto scan")

                    Button {
                        controller.isRecording
                            ? controller.stopRecording()
                            : controller.startRecording()
                    } label: {
                        ZStack {
                            Circle()
                                .stroke(.white,lineWidth: 5)
                                .frame(width: 82,height: 82)
                            if controller.isRecording {
                                RoundedRectangle(cornerRadius: 8)
                                    .fill(.red)
                                    .frame(width: 38,height: 38)
                            } else {
                                Circle()
                                    .fill(.red)
                                    .frame(width: 62,height: 62)
                            }
                        }
                    }
                    .accessibilityLabel(
                        controller.isRecording ? "Stop recording" : "Start recording"
                    )

                    Image(systemName: "cube.transparent")
                        .font(.title2)
                        .frame(width: 52,height: 52)
                        .background(.ultraThinMaterial, in: Circle())
                        .accessibilityLabel("Stable QR attached model")
                }
                .padding(.bottom, 12)
            }
            .padding()
        }
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
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(.ultraThinMaterial, in: Capsule())
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
