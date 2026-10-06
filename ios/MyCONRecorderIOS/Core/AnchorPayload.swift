import Foundation
import CryptoKit

struct AnchorPayload: Codable, Hashable {
    var project: String
    var anchor: String
    var crs: String
    var x: Double
    var y: Double
    var z: Double
    var mount: String
    var azimuthDeg: Double
    var sizeMm: Double
    var floor: String = ""
    var modelId: String = ""
    var modelSizeM: Double? = nil

    static let testModelId = "test_cube_1m"

    var canonicalLegacy: String {
        [
            "v=1",
            "project=\(project)",
            "anchor=\(anchor)",
            "crs=\(crs)",
            "x=\(Self.fmt(x))",
            "y=\(Self.fmt(y))",
            "z=\(Self.fmt(z))",
            "mount=\(mount.uppercased())",
            "azimuth_deg=\(Self.fmt(azimuthDeg))",
            "size_mm=\(Self.fmt(sizeMm))",
            "floor=\(floor)"
        ].joined(separator: "&")
    }

    var canonical: String {
        guard !modelId.isEmpty else { return canonicalLegacy }
        return canonicalLegacy + "&model_id=\(modelId)&model_size_m=\(Self.fmt(modelSizeM ?? 1.0))"
    }

    var checksum: String {
        Self.digest(canonical)
    }

    var hasModel: Bool {
        !modelId.isEmpty && (modelSizeM ?? 0) > 0
    }

    func qrString() -> String {
        var c = URLComponents()
        c.scheme = "mycon"
        c.host = "anchor"
        c.path = "/v1"
        var items: [URLQueryItem] = [
            .init(name: "project", value: project),
            .init(name: "anchor", value: anchor),
            .init(name: "crs", value: crs),
            .init(name: "x", value: Self.fmt(x)),
            .init(name: "y", value: Self.fmt(y)),
            .init(name: "z", value: Self.fmt(z)),
            .init(name: "mount", value: mount.uppercased()),
            .init(name: "azimuth_deg", value: Self.fmt(azimuthDeg)),
            .init(name: "size_mm", value: Self.fmt(sizeMm)),
            .init(name: "floor", value: floor)
        ]
        if !modelId.isEmpty {
            items.append(.init(name: "model_id", value: modelId))
            items.append(.init(name: "model_size_m", value: Self.fmt(modelSizeM ?? 1.0)))
        }
        items.append(.init(name: "sig", value: checksum))
        c.queryItems = items
        return c.string ?? ""
    }

    static func parse(_ raw: String) -> AnchorPayload? {
        guard let c = URLComponents(string: raw),
              c.scheme == "mycon", c.host == "anchor", c.path == "/v1"
        else { return nil }
        let q = Dictionary(uniqueKeysWithValues: (c.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        guard
            let project = q["project"], !project.isEmpty,
            let anchor = q["anchor"], !anchor.isEmpty,
            let crs = q["crs"], !crs.isEmpty,
            let x = Double(q["x"] ?? ""),
            let y = Double(q["y"] ?? ""),
            let z = Double(q["z"] ?? ""),
            let az = Double(q["azimuth_deg"] ?? ""),
            let size = Double(q["size_mm"] ?? ""), size > 0,
            let sig = q["sig"]
        else { return nil }

        let p = AnchorPayload(
            project: project,
            anchor: anchor,
            crs: crs,
            x: x, y: y, z: z,
            mount: (q["mount"] ?? "").uppercased(),
            azimuthDeg: az,
            sizeMm: size,
            floor: q["floor"] ?? "",
            modelId: q["model_id"] ?? "",
            modelSizeM: Double(q["model_size_m"] ?? "")
        )
        let ok = sig.lowercased() == p.checksum || sig.lowercased() == digest(p.canonicalLegacy)
        return ok ? p : nil
    }

    static func fmt(_ v: Double) -> String {
        var s = String(format: "%.4f", locale: Locale(identifier: "en_US_POSIX"), v)
        while s.contains(".") && s.last == "0" { s.removeLast() }
        if s.last == "." { s.removeLast() }
        return s
    }

    private static func digest(_ s: String) -> String {
        let d = SHA256.hash(data: Data(s.utf8))
        return d.prefix(6).map { String(format: "%02x", $0) }.joined()
    }
}
