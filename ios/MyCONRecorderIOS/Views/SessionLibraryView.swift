import SwiftUI

struct SessionLibraryView: View {
    @ObservedObject var controller: CaptureController
    @State private var files: [URL] = []

    var body: some View {
        NavigationStack {
            List {
                if let latest = controller.latestPackageURL {
                    Section("Latest") {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(latest.lastPathComponent).font(.headline)
                            ShareLink(item: latest) {
                                Label("Share / Save MYCON ZIP", systemImage: "square.and.arrow.up")
                            }
                        }
                    }
                }
                Section("Saved packages") {
                    if files.isEmpty {
                        Text("هنوز بسته‌ای ساخته نشده.").foregroundStyle(.secondary)
                    }
                    ForEach(files, id: \.self) { url in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(url.lastPathComponent).font(.subheadline.bold())
                                Text(fileSize(url)).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }
                        }
                    }
                }
                Section("Contract") {
                    Text("خروجی iOS همان MYCON_CAPTURE_SESSION / v1 و همان mycon://anchor/v1 را نگه می‌دارد. نام arcore_recording.mp4 عمداً برای سازگاری Stageهای فعلی حفظ شده است.")
                        .font(.caption)
                }
            }
            .navigationTitle("Sessions")
            .onAppear(perform: refresh)
            .refreshable { refresh() }
        }
    }

    private func refresh() {
        let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MyCON Sessions", isDirectory: true)
        files = ((try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.fileSizeKey], options: [.skipsHiddenFiles])) ?? [])
            .filter { $0.pathExtension.lowercased() == "zip" }
            .sorted { $0.lastPathComponent > $1.lastPathComponent }
    }

    private func fileSize(_ url: URL) -> String {
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        return ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}
