#!/usr/bin/env python3
import base64
import json
import pathlib
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
CATALOG = ROOT / "web" / "models" / "catalog.json"

COMPONENT_SIZE = {
    5120: 1,  # BYTE
    5121: 1,  # UNSIGNED_BYTE
    5122: 2,  # SHORT
    5123: 2,  # UNSIGNED_SHORT
    5125: 4,  # UNSIGNED_INT
    5126: 4,  # FLOAT
}
TYPE_WIDTH = {
    "SCALAR": 1,
    "VEC2": 2,
    "VEC3": 3,
    "VEC4": 4,
    "MAT2": 4,
    "MAT3": 9,
    "MAT4": 16,
}

def fail(message):
    raise SystemExit("MODEL VALIDATION FAILED: " + message)

def decode_buffer(buffer_def):
    uri = buffer_def.get("uri", "")
    prefix = "data:application/octet-stream;base64,"
    if not uri.startswith(prefix):
        fail("only embedded base64 buffers are supported by this validator")
    try:
        return base64.b64decode(uri[len(prefix):], validate=True)
    except Exception as exc:
        fail(f"invalid base64 buffer: {exc}")

def validate_gltf(path):
    doc = json.loads(path.read_text(encoding="utf-8"))
    if doc.get("asset", {}).get("version") != "2.0":
        fail(f"{path.name}: glTF 2.0 required")

    buffers = doc.get("buffers", [])
    if len(buffers) != 1:
        fail(f"{path.name}: exactly one embedded buffer expected")

    raw = decode_buffer(buffers[0])
    declared = int(buffers[0].get("byteLength", -1))
    if declared != len(raw):
        fail(f"{path.name}: declared buffer length {declared}, actual {len(raw)}")

    views = doc.get("bufferViews", [])
    for i, view in enumerate(views):
        start = int(view.get("byteOffset", 0))
        length = int(view.get("byteLength", 0))
        if start < 0 or length < 0 or start + length > len(raw):
            fail(f"{path.name}: bufferView {i} exceeds buffer")

    accessors = doc.get("accessors", [])
    for i, acc in enumerate(accessors):
        view_index = acc.get("bufferView")
        if view_index is None or not (0 <= view_index < len(views)):
            fail(f"{path.name}: accessor {i} has invalid bufferView")
        component = acc.get("componentType")
        kind = acc.get("type")
        if component not in COMPONENT_SIZE or kind not in TYPE_WIDTH:
            fail(f"{path.name}: accessor {i} uses unsupported component/type")
        count = int(acc.get("count", 0))
        element_size = COMPONENT_SIZE[component] * TYPE_WIDTH[kind]
        offset = int(acc.get("byteOffset", 0))
        if offset + count * element_size > int(views[view_index].get("byteLength", 0)):
            fail(f"{path.name}: accessor {i} exceeds its bufferView")

    meshes = doc.get("meshes", [])
    if not meshes:
        fail(f"{path.name}: mesh missing")
    prim = meshes[0].get("primitives", [{}])[0]
    attrs = prim.get("attributes", {})
    if "POSITION" not in attrs or "NORMAL" not in attrs:
        fail(f"{path.name}: POSITION and NORMAL are required")
    if "indices" not in prim:
        fail(f"{path.name}: indexed triangles required")

    pos = accessors[attrs["POSITION"]]
    normal = accessors[attrs["NORMAL"]]
    idx = accessors[prim["indices"]]

    if pos.get("count") != 24 or normal.get("count") != 24:
        fail(f"{path.name}: calibration cube must use 24 face vertices/normals")
    if idx.get("count") != 36:
        fail(f"{path.name}: calibration cube must use exactly 36 indices / 12 triangles")
    if pos.get("min") != [-0.5, -0.5, -0.5] or pos.get("max") != [0.5, 0.5, 0.5]:
        fail(f"{path.name}: calibration cube bounds must be exactly 1 metre")

    idx_view = views[idx["bufferView"]]
    start = int(idx_view.get("byteOffset", 0)) + int(idx.get("byteOffset", 0))
    data = raw[start:start + 36 * 2]
    values = struct.unpack("<36H", data)
    if max(values) >= 24:
        fail(f"{path.name}: index references a missing vertex")

    print(f"OK {path.name}: {len(raw)} bytes, 24 vertices, 12 triangles, 1.000 m cube")

def main():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    models = catalog.get("models", [])
    if not models:
        fail("catalog contains no models")
    for model in models:
        rel = model.get("file", "")
        if not rel.startswith("./models/"):
            fail(f"catalog model path is invalid: {rel}")
        path = ROOT / "web" / rel[2:]
        if not path.exists():
            fail(f"catalog model file missing: {path}")
        validate_gltf(path)

if __name__ == "__main__":
    main()
