import SwiftUI
import RealityKit

struct CaptureView: View {
    @ObservedObject var controller: CaptureController

    var body: some View {
        ZStack {
            ARViewContainer(controller: controller)
                .ignoresSafeArea()

            LinearGradient(colors: [.black.opacity(0.75), .clear, .black.opacity(0.78)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .allowsHitTesting(false)

            VStack(spacing: 12) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("MYCON • iOS / ARKit").font(.caption2.bold()).foregroundStyle(.cyan)
                        Text(controller.trackingText).font(.headline)
                        Text(controller.qrText).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Circle()
                        .fill(controller.isRecording ? .red : .green)
                        .frame(width: 10,height: 10)
                }
                .padding(14)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))

                TextField("Project hint", text: $controller.projectHint)
                    .textInputAutocapitalization(.never)
                    .padding(12)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))

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

                    Button {
                        controller.isRecording ? controller.stopRecording() : controller.startRecording()
                    } label: {
                        ZStack {
                            Circle().stroke(.white,lineWidth: 5).frame(width: 82,height: 82)
                            if controller.isRecording {
                                RoundedRectangle(cornerRadius: 8).fill(.red).frame(width: 38,height: 38)
                            } else {
                                Circle().fill(.red).frame(width: 62,height: 62)
                            }
                        }
                    }
                    .accessibilityLabel(controller.isRecording ? "Stop recording" : "Start recording")

                    Image(systemName: "cube.transparent")
                        .font(.title2)
                        .frame(width: 52,height: 52)
                        .background(.ultraThinMaterial, in: Circle())
                }
                .padding(.bottom, 12)
            }
            .padding()
        }
    }
}

private struct ARViewContainer: UIViewRepresentable {
    let controller: CaptureController

    func makeUIView(context: Context) -> ARView {
        let view = ARView(frame: .zero, cameraMode: .ar, automaticallyConfigureSession: false)
        Task { @MainActor in controller.attach(view) }
        return view
    }

    func updateUIView(_ uiView: ARView, context: Context) {}
}
