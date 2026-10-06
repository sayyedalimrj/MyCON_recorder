import SwiftUI

@main
struct MyCONRecorderIOSApp: App {
    @StateObject private var controller = CaptureController()

    var body: some Scene {
        WindowGroup {
            TabView {
                CaptureView(controller: controller)
                    .tabItem { Label("Capture", systemImage: "camera.viewfinder") }

                MarkerBuilderView()
                    .tabItem { Label("QR", systemImage: "qrcode.viewfinder") }

                RoomPlanScanView()
                    .tabItem { Label("Room", systemImage: "square.3.layers.3d") }

                SessionLibraryView(controller: controller)
                    .tabItem { Label("Sessions", systemImage: "archivebox") }
            }
            .preferredColorScheme(.dark)
        }
    }
}
