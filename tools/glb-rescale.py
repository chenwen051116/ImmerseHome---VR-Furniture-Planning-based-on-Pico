#!/usr/bin/env python3
"""Bake real-world scale into GLB files (glTF binary).

Two modes:

  measure  — print the world-space AABB (scene-graph transforms applied)
  bake     — multiply the ROOT node transforms so the model lands at an
             intended real-world size, per axis. Vertices are untouched;
             only the JSON chunk changes.

Usage:
  python tools/glb-rescale.py measure file.glb [...]
  python tools/glb-rescale.py bake file.glb WIDTH DEPTH HEIGHT

WIDTH/DEPTH/HEIGHT are the intended size in meters along the model's
world X / Z / Y axes respectively. The per-axis factors are applied to
every scene-root node (translation / scale / matrix), which is exact for
the flat, axis-aligned hierarchies typical of furniture exports.
"""

import json
import math
import struct
import sys

COMP_FLOAT = 5126
CTYPE_SIZE = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


# ---------- GLB container ----------

def read_glb(path):
    with open(path, "rb") as f:
        magic, version, _length = struct.unpack("<III", f.read(12))
        if magic != 0x46546C67:
            raise ValueError(f"{path}: not a GLB")
        json_chunk = bin_chunk = None
        while True:
            head = f.read(8)
            if len(head) < 8:
                break
            clen, ctype = struct.unpack("<II", head)
            data = f.read(clen)
            if ctype == 0x4E4F534A:
                json_chunk = data
            elif ctype == 0x004E4942:
                bin_chunk = data
        return json.loads(json_chunk), bin_chunk or b""


def write_glb(path, doc, bin_chunk):
    json_chunk = json.dumps(doc, separators=(",", ":")).encode("utf-8")
    json_chunk += b" " * (-len(json_chunk) % 4)
    bin_chunk = bytes(bin_chunk) + b"\x00" * (-len(bin_chunk) % 4)
    total = 12 + 8 + len(json_chunk) + 8 + len(bin_chunk)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(json_chunk), 0x4E4F534A))
        f.write(json_chunk)
        f.write(struct.pack("<II", len(bin_chunk), 0x004E4942))
        f.write(bin_chunk)


# ---------- math helpers (column-major 4x4 as flat list of 16) ----------

def mat_identity():
    return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]


def mat_mul(a, b):
    out = [0.0] * 16
    for c in range(4):
        for r in range(4):
            out[c * 4 + r] = sum(a[k * 4 + r] * b[c * 4 + k] for k in range(4))
    return out


def mat_from_trs(t, r, s):
    x, y, z, w = r
    m = [
        1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
        2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
        2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
        t[0], t[1], t[2], 1,
    ]
    for i in range(3):
        m[0 * 4 + i] *= s[0]
        m[1 * 4 + i] *= s[1]
        m[2 * 4 + i] *= s[2]
    return m


def node_local_matrix(node):
    if "matrix" in node:
        return [float(v) for v in node["matrix"]]
    t = node.get("translation", [0, 0, 0])
    r = node.get("rotation", [0, 0, 0, 1])
    s = node.get("scale", [1, 1, 1])
    return mat_from_trs(t, r, s)


def transform_point(m, p):
    return (
        m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
        m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
        m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14],
    )


# ---------- measuring ----------

def accessor_bounds(doc, index):
    acc = doc["accessors"][index]
    if "min" in acc and "max" in acc:
        return [float(v) for v in acc["min"]], [float(v) for v in acc["max"]]
    return None


def real_accessor_bounds(doc, bin_chunk, index):
    """Local-space min/max computed from the actual vertex data (ignores authored metadata)."""
    acc = doc["accessors"][index]
    if acc.get("componentType") != COMP_FLOAT or acc.get("type") != "VEC3":
        return accessor_bounds(doc, index)
    view = doc.get("bufferViews", [])[acc["bufferView"]]
    stride = view.get("byteStride", 12)
    base = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    mn = [math.inf] * 3
    mx = [-math.inf] * 3
    for i in range(acc["count"]):
        x, y, z = struct.unpack_from("<fff", bin_chunk, base + i * stride)
        for k, v in enumerate((x, y, z)):
            mn[k] = min(mn[k], v)
            mx[k] = max(mx[k], v)
    return mn, mx


def position_accessors(doc):
    ids = set()
    for mesh in doc.get("meshes", []):
        for prim in mesh.get("primitives", []):
            if "POSITION" in prim.get("attributes", {}):
                ids.add(prim["attributes"]["POSITION"])
    return ids


def world_aabb(doc, bin_chunk=None):
    """AABB over all POSITION accessors with node transforms applied. When [bin_chunk] is
    given, bounds come from the real vertex data instead of authored accessor metadata."""
    lo = [math.inf] * 3
    hi = [-math.inf] * 3
    nodes = doc.get("nodes", [])
    scenes = doc.get("scenes", [])
    scene = scenes[doc.get("scene", 0)] if scenes else {"nodes": list(range(len(nodes)))}

    def walk(idx, parent_m):
        node = nodes[idx]
        m = mat_mul(parent_m, node_local_matrix(node))
        if "mesh" in node:
            mesh = doc["meshes"][node["mesh"]]
            for prim in mesh.get("primitives", []):
                pos_id = prim.get("attributes", {}).get("POSITION")
                if pos_id is None:
                    continue
                bounds = (
                    real_accessor_bounds(doc, bin_chunk, pos_id)
                    if bin_chunk is not None
                    else accessor_bounds(doc, pos_id)
                )
                if not bounds:
                    continue
                amin, amax = bounds
                for corner in range(8):
                    p = [
                        amin[0] if corner & 1 else amax[0],
                        amin[1] if corner & 2 else amax[1],
                        amin[2] if corner & 4 else amax[2],
                    ]
                    wp = transform_point(m, p)
                    for k in range(3):
                        lo[k] = min(lo[k], wp[k])
                        hi[k] = max(hi[k], wp[k])
        for child in node.get("children", []):
            walk(child, m)

    for root in scene.get("nodes", []):
        walk(root, mat_identity())
    return lo, hi


def scene_roots(doc):
    scenes = doc.get("scenes", [])
    if not scenes:
        return list(range(len(doc.get("nodes", []))))
    return scenes[doc.get("scene", 0)].get("nodes", [])


# ---------- commands ----------

def iter_position_accessors(doc):
    seen = set()
    for mesh in doc.get("meshes", []):
        for prim in mesh.get("primitives", []):
            idx = prim.get("attributes", {}).get("POSITION")
            if idx is not None:
                seen.add(idx)
    return seen


def scale_vertices(doc, bin_chunk, factors):
    """Scale every POSITION accessor's vertex data in place; recompute min/max."""
    buf = bytearray(bin_chunk)
    views = doc.get("bufferViews", [])
    for idx in iter_position_accessors(doc):
        acc = doc["accessors"][idx]
        if acc.get("componentType") != COMP_FLOAT or acc.get("type") != "VEC3":
            print(f"  skip accessor {idx}: componentType={acc.get('componentType')} type={acc.get('type')}")
            continue
        if "sparse" in acc:
            raise ValueError(f"sparse accessor {idx} not supported")
        view = views[acc["bufferView"]]
        stride = view.get("byteStride", 12)
        base = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
        mn = [math.inf] * 3
        mx = [-math.inf] * 3
        for i in range(acc["count"]):
            off = base + i * stride
            x, y, z = struct.unpack_from("<fff", buf, off)
            x *= factors[0]
            y *= factors[1]
            z *= factors[2]
            struct.pack_into("<fff", buf, off, x, y, z)
            for k, v in enumerate((x, y, z)):
                mn[k] = min(mn[k], v)
                mx[k] = max(mx[k], v)
        acc["min"] = mn
        acc["max"] = mx
    return bytes(buf)


def scale_node_translations(doc, factors):
    """Per-axis scale of every NON-scene-root node's translation (matrix nodes: elements 12-14).

    Scene roots are deliberately untouched: the SDK folds the glTF scene root into the loaded
    entity's own TransformComponent, so any transform baked there is invisible to
    getVisualBounds(relativeTo = entity) and gets overwritten by placement code.
    """
    roots = set(scene_roots(doc))
    for idx, node in enumerate(doc.get("nodes", [])):
        if idx in roots:
            continue
        if "translation" in node:
            node["translation"] = [node["translation"][k] * factors[k] for k in range(3)]
        if "matrix" in node:
            m = [float(v) for v in node["matrix"]]
            m[12] *= factors[0]
            m[13] *= factors[1]
            m[14] *= factors[2]
            node["matrix"] = m


def cmd_measure(paths):
    for path in paths:
        doc, bin_chunk = read_glb(path)
        lo, hi = world_aabb(doc, bin_chunk)
        size = [hi[k] - lo[k] for k in range(3)]
        print(
            f"{path}: W{size[0]:.3f} x H{size[1]:.3f} x D{size[2]:.3f} "
            f"(y {lo[1]:.3f}..{hi[1]:.3f})"
        )


def flatten_world(doc, bin_chunk):
    """Bake every node's world transform into its POSITION data, then reset all non-root
    node transforms to identity. After this, world space == mesh space, so a subsequent
    per-axis vertex scale is exact even for rotated/skewed hierarchies. (Furniture only:
    no skins/animations; NORMAL vectors are left as-is.)"""
    nodes = doc.get("nodes", [])
    buf = bytearray(bin_chunk)
    views = doc.get("bufferViews", [])
    acc_matrices = {}

    def walk(idx, parent_m):
        node = nodes[idx]
        m = mat_mul(parent_m, node_local_matrix(node))
        if "mesh" in node:
            mesh = doc["meshes"][node["mesh"]]
            for prim in mesh.get("primitives", []):
                pos = prim.get("attributes", {}).get("POSITION")
                if pos is not None:
                    acc_matrices.setdefault(pos, set()).add(tuple(round(v, 6) for v in m))
        for child in node.get("children", []):
            walk(child, m)

    for root in scene_roots(doc):
        walk(root, mat_identity())

    for idx, mats in acc_matrices.items():
        if len(mats) > 1:
            print(f"  warning: accessor {idx} shared by {len(mats)} transforms; using the first")
        m = list(next(iter(mats)))
        acc = doc["accessors"][idx]
        if acc.get("componentType") != COMP_FLOAT or acc.get("type") != "VEC3":
            print(f"  skip accessor {idx}: not float VEC3")
            continue
        if "sparse" in acc:
            raise ValueError(f"sparse accessor {idx} not supported")
        view = views[acc["bufferView"]]
        stride = view.get("byteStride", 12)
        base = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
        mn = [math.inf] * 3
        mx = [-math.inf] * 3
        for i in range(acc["count"]):
            off = base + i * stride
            x, y, z = struct.unpack_from("<fff", buf, off)
            wx, wy, wz = transform_point(m, (x, y, z))
            struct.pack_into("<fff", buf, off, wx, wy, wz)
            for k, v in enumerate((wx, wy, wz)):
                mn[k] = min(mn[k], v)
                mx[k] = max(mx[k], v)
        acc["min"] = mn
        acc["max"] = mx

    # Vertices are now in world space; EVERY node transform must become identity —
    # including scene roots: many exports keep their matrices on the roots themselves.
    for node in nodes:
        for key in ("translation", "rotation", "scale", "matrix"):
            node.pop(key, None)
    return bytes(buf)


def cmd_bake(path, target_w, target_d, target_h):
    doc, bin_chunk = read_glb(path)

    # 1) flatten the hierarchy into the vertices (exact for any transforms; also rewrites
    #    accessor min/max from the REAL data — some exports ship swapped/wrong metadata),
    # 2) measure the TRUE world extent from the flattened file,
    # 3) per-axis scale to the intended size.
    bin_chunk = flatten_world(doc, bin_chunk)
    lo, hi = world_aabb(doc)
    size = [hi[k] - lo[k] for k in range(3)]
    if any(s <= 0 for s in size):
        raise ValueError(f"{path}: degenerate bounds {size}")
    target = [target_w, target_h, target_d]  # x, y, z
    factors = [target[k] / size[k] for k in range(3)]
    uniform = max(factors) / min(factors) <= 1.05

    bin_chunk = scale_vertices(doc, bin_chunk, factors)
    write_glb(path, doc, bin_chunk)

    doc2, _bin2 = read_glb(path)
    lo2, hi2 = world_aabb(doc2)
    out = [hi2[k] - lo2[k] for k in range(3)]
    kind = "uniform" if uniform else "per-axis"
    print(
        f"{path}: ({size[0]:.3f},{size[1]:.3f},{size[2]:.3f}) -> "
        f"({out[0]:.3f},{out[1]:.3f},{out[2]:.3f}) "
        f"factors=({factors[0]:.4g},{factors[1]:.4g},{factors[2]:.4g}) [{kind}]"
    )


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if len(argv) < 3:
        print(__doc__)
        return 1
    mode = argv[1]
    if mode == "measure":
        cmd_measure(argv[2:])
        return 0
    if mode == "bake" and len(argv) == 6:
        cmd_bake(argv[2], float(argv[3]), float(argv[4]), float(argv[5]))
        return 0
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
