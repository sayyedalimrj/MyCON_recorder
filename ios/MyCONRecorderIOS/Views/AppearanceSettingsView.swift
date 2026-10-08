import SwiftUI

struct AppearanceSettingsView: View {
    @AppStorage("mycon.appearance") private var appearance = "system"

    var body: some View {
        NavigationStack {
            Form {
                Section("Appearance") {
                    Picker("Theme", selection: $appearance) {
                        Label("System", systemImage: "circle.lefthalf.filled")
                            .tag("system")
                        Label("Light", systemImage: "sun.max.fill")
                            .tag("light")
                        Label("Dark", systemImage: "moon.fill")
                            .tag("dark")
                    }
                    .pickerStyle(.segmented)

                    Text("System follows the iPhone appearance automatically.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("MyCON") {
                    LabeledContent("Version", value: "1.1.0")
                    LabeledContent("Capture contract", value: "v1")
                    Text("Native Capture = scientific path.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
        }
    }
}
