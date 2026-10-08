import SwiftUI

@main
struct MyCONRecorderIOSApp: App {
    @StateObject private var controller = CaptureController()
    @AppStorage("mycon.appearance") private var appearance = "system"
    @State private var selectedTab = 0

    private var preferredScheme: ColorScheme? {
        switch appearance {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    var body: some Scene {
        WindowGroup {
            TabView(selection: $selectedTab) {
                CaptureView(controller: controller)
                    .tabItem { Label("Capture", systemImage: "camera.viewfinder") }
                    .tag(0)

                MarkerBuilderView()
                    .tabItem { Label("QR", systemImage: "qrcode.viewfinder") }
                    .tag(1)

                RoomPlanScanView()
                    .tabItem { Label("Room", systemImage: "square.3.layers.3d") }
                    .tag(2)

                SessionLibraryView(controller: controller)
                    .tabItem { Label("Sessions", systemImage: "archivebox") }
                    .tag(3)

                AppearanceSettingsView()
                    .tabItem { Label("Settings", systemImage: "gearshape") }
                    .tag(4)
            }
            .preferredColorScheme(preferredScheme)
            .onOpenURL { url in
                guard url.scheme?.lowercased() == "mycon" else { return }
                switch url.host?.lowercased() {
                case "capture": selectedTab = 0
                case "qr": selectedTab = 1
                case "room": selectedTab = 2
                case "sessions": selectedTab = 3
                default: selectedTab = 0
                }
            }
        }
    }
}
