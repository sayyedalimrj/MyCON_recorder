import SwiftUI
import RoomPlan
import UIKit

@MainActor
final class RoomPlanScanner: NSObject, ObservableObject, RoomCaptureViewDelegate, RoomCaptureSessionDelegate {
    @Published var status = "آماده"
    @Published var resultURL: URL?
    @Published var isRunning = false
    weak var captureView: RoomCaptureView?

    override init() {
        super.init()
    }

    required init?(coder: NSCoder) {
        super.init()
    }

    func encode(with coder: NSCoder) {}

    func start(on view: RoomCaptureView) {
        guard RoomCaptureSession.isSupported else {
            status = "این دستگاه LiDAR / RoomPlan ندارد"
            return
        }
        captureView = view
        view.delegate = self
        view.captureSession.delegate = self
        var config = RoomCaptureSession.Configuration()
        config.isCoachingEnabled = true
        view.captureSession.run(configuration: config)
        isRunning = true
        status = "اسکن فعال"
    }

    func stop() {
        guard isRunning else { return }
        captureView?.captureSession.stop()
        isRunning = false
        status = "در حال پردازش…"
    }

    func captureView(shouldPresent roomDataForProcessing: CapturedRoomData, error: Error?) -> Bool {
        if let error { status = "RoomPlan: \(error.localizedDescription)" }
        return true
    }

    func captureView(didPresent processedResult: CapturedRoom, error: Error?) {
        if let error {
            status = "RoomPlan: \(error.localizedDescription)"
            return
        }
        do {
            let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("MyCON Rooms", isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let url = root.appendingPathComponent("MYCON_ROOM_\(Int(Date().timeIntervalSince1970)).usdz")
            try processedResult.export(to: url)
            resultURL = url
            status = "اسکن آماده شد"
        } catch {
            status = "Export ناموفق: \(error.localizedDescription)"
        }
    }

    func captureSession(_ session: RoomCaptureSession, didProvide instruction: RoomCaptureSession.Instruction) {
        let hint = String(describing: instruction).lowercased()
        if hint.contains("slow") {
            status = "کمی آهسته‌تر"
        } else if hint.contains("light") {
            status = "نور بیشتر"
        } else if hint.contains("close") {
            status = "کمی نزدیک‌تر"
        } else if hint.contains("away") {
            status = "کمی دورتر"
        } else {
            status = "اسکن فعال"
        }
    }
}

struct RoomPlanScanView: View {
    @StateObject private var scanner = RoomPlanScanner()

    var body: some View {
        NavigationStack {
            Group {
                if RoomCaptureSession.isSupported {
                    ZStack(alignment: .bottom) {
                        RoomCaptureRepresentable(scanner: scanner)
                            .ignoresSafeArea(edges: .bottom)

                        VStack(spacing: 10) {
                            Text(scanner.status)
                                .font(.caption.bold())
                                .frame(maxWidth: .infinity)
                                .padding(10)
                                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))

                            HStack {
                                if scanner.isRunning {
                                    Button(role: .destructive) {
                                        scanner.stop()
                                    } label: {
                                        Label("پایان اسکن", systemImage: "stop.fill")
                                    }
                                    .buttonStyle(.borderedProminent)
                                }
                                if let result = scanner.resultURL {
                                    ShareLink(item: result) {
                                        Label("USDZ", systemImage: "square.and.arrow.up")
                                    }
                                    .buttonStyle(.borderedProminent)
                                }
                            }
                        }
                        .padding()
                    }
                } else {
                    ContentUnavailableView(
                        "RoomPlan نیاز به LiDAR دارد",
                        systemImage: "viewfinder",
                        description: Text("Capture علمی MyCON بدون LiDAR همچنان در تب Capture کار می‌کند.")
                    )
                }
            }
            .navigationTitle("Room")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct RoomCaptureRepresentable: UIViewRepresentable {
    let scanner: RoomPlanScanner

    func makeUIView(context: Context) -> RoomCaptureView {
        let view = RoomCaptureView(frame: .zero)
        Task { @MainActor in scanner.start(on: view) }
        return view
    }

    func updateUIView(_ uiView: RoomCaptureView, context: Context) {}

    static func dismantleUIView(_ uiView: RoomCaptureView, coordinator: ()) {
        uiView.captureSession.stop()
    }
}
