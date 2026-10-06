import SwiftUI
import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit

struct MarkerBuilderView: View {
    @State private var project = "MYCON_PROJECT"
    @State private var anchor = "A001"
    @State private var crs = "LOCAL:P001"
    @State private var x = "0"
    @State private var y = "0"
    @State private var z = "0"
    @State private var azimuth = "0"
    @State private var sizeMm = "160"
    @State private var floor = ""
    @State private var mount = "VERTICAL"
    @State private var modelId = ""
    @State private var modelSize = "1"
    @State private var shareURL: URL?

    private var payload: AnchorPayload {
        AnchorPayload(
            project: project, anchor: anchor, crs: crs,
            x: Double(x) ?? 0, y: Double(y) ?? 0, z: Double(z) ?? 0,
            mount: mount, azimuthDeg: Double(azimuth) ?? 0,
            sizeMm: max(1, Double(sizeMm) ?? 160), floor: floor,
            modelId: modelId, modelSizeM: Double(modelSize)
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Project control") {
                    TextField("Project", text: $project)
                    TextField("Anchor", text: $anchor)
                    TextField("CRS", text: $crs)
                    TextField("Floor / zone", text: $floor)
                }
                Section("Coordinates • metres") {
                    TextField("X", text: $x).keyboardType(.numbersAndPunctuation)
                    TextField("Y", text: $y).keyboardType(.numbersAndPunctuation)
                    TextField("Z", text: $z).keyboardType(.numbersAndPunctuation)
                    Picker("Mount", selection: $mount) {
                        Text("VERTICAL").tag("VERTICAL")
                        Text("HORIZONTAL").tag("HORIZONTAL")
                    }
                    TextField("Azimuth °", text: $azimuth).keyboardType(.numbersAndPunctuation)
                    TextField("QR symbol size mm", text: $sizeMm).keyboardType(.numbersAndPunctuation)
                }
                Section("Optional 3D model") {
                    TextField("Model ID", text: $modelId)
                    TextField("Model size m", text: $modelSize).keyboardType(.numbersAndPunctuation)
                }
                Section("MYCON QR") {
                    if let image = qrImage(payload.qrString()) {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .padding(10)
                            .background(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 18))
                    }
                    Text(payload.qrString())
                        .font(.caption2.monospaced())
                        .textSelection(.enabled)
                    Button("ساخت PDF چاپ 100%") {
                        shareURL = makePDF(payload: payload)
                    }
                    if let shareURL {
                        ShareLink(item: shareURL) {
                            Label("Share PDF", systemImage: "square.and.arrow.up")
                        }
                    }
                }
            }
            .navigationTitle("QR پروژه")
        }
    }

    private func qrImage(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: .init(scaleX: 12, y: 12)) else { return nil }
        return UIImage(ciImage: out)
    }

    private func makePDF(payload: AnchorPayload) -> URL? {
        guard let qr = qrImage(payload.qrString()) else { return nil }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(payload.project)_\(payload.anchor)_MYCON.pdf")
        let page = CGRect(x: 0, y: 0, width: 595.28, height: 841.89)
        let mmToPt: CGFloat = 72 / 25.4
        let qrSide = CGFloat(payload.sizeMm) * mmToPt
        let quiet = qrSide * 4 / 29
        let full = qrSide + quiet * 2
        let renderer = UIGraphicsPDFRenderer(bounds: page)
        do {
            try renderer.writePDF(to: url) { ctx in
                ctx.beginPage()
                UIColor.white.setFill(); ctx.cgContext.fill(page)
                let origin = CGPoint(x: (page.width - full)/2, y: 140)
                qr.draw(in: CGRect(x: origin.x + quiet, y: origin.y + quiet, width: qrSide, height: qrSide))
                UIColor.black.setFill()
                ("MYCON • \(payload.project) / \(payload.anchor)" as NSString).draw(at: CGPoint(x: 54, y: 60), withAttributes: [.font:UIFont.boldSystemFont(ofSize: 18), .foregroundColor:UIColor.black])
                ("Print at 100% / Actual Size • QR symbol = \(AnchorPayload.fmt(payload.sizeMm)) mm" as NSString).draw(at: CGPoint(x: 54, y: 92), withAttributes: [.font:UIFont.systemFont(ofSize: 11), .foregroundColor:UIColor.black])
                let barY = origin.y + full + 80
                let barW = 100 * mmToPt
                ctx.cgContext.setLineWidth(3);ctx.cgContext.move(to: CGPoint(x:(page.width-barW)/2,y:barY));ctx.cgContext.addLine(to: CGPoint(x:(page.width+barW)/2,y:barY));ctx.cgContext.strokePath()
                ("100 mm verification bar" as NSString).draw(at: CGPoint(x:(page.width-barW)/2,y:barY+9), withAttributes:[.font:UIFont.systemFont(ofSize:10),.foregroundColor:UIColor.black])
            }
            return url
        } catch {
            return nil
        }
    }
}
