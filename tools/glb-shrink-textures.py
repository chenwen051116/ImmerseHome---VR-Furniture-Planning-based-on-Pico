#!/usr/bin/env python3
"""Downscale textures embedded in GLB files so heavy models stop killing the emulator.

Every embedded image larger than MAX_DIM pixels on its long side is resized to MAX_DIM
(alpha images stay PNG, others become JPEG q85) and the binary chunk is rebuilt with
recomputed bufferView offsets. Vertices/accessors are untouched (accessor byteOffsets
are relative to their bufferViews, which keep their indices).

Usage:
  python tools/glb-shrink-textures.py file.glb [...] [--max 1024]
"""

import io
import struct
import sys

from PIL import Image

import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("gr", str(Path(__file__).with_name("glb-rescale.py")))
gr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gr)

Image.MAX_IMAGE_PIXELS = None  # trusted local assets


def shrink_file(path, max_dim):
    doc, bin_chunk = gr.read_glb(path)
    views = doc.get("bufferViews", [])
    images = doc.get("images", [])
    view_to_image = {}
    for index, image in enumerate(images):
        if "bufferView" in image:
            view_to_image[image["bufferView"]] = image

    rebuilt = []
    cursor = 0
    changed = 0
    saved_bytes = 0
    for view_index, view in enumerate(views):
        start = view.get("byteOffset", 0)
        data = bin_chunk[start : start + view.get("byteLength", 0)]
        image = view_to_image.get(view_index)
        if image is not None and data:
            try:
                with Image.open(io.BytesIO(data)) as im:
                    im.load()
                    width, height = im.size
                    if max(width, height) > max_dim:
                        ratio = max_dim / max(width, height)
                        new_size = (
                            max(1, round(width * ratio)),
                            max(1, round(height * ratio)),
                        )
                        resized = im.resize(new_size, Image.LANCZOS)
                        has_alpha = im.mode in ("RGBA", "LA", "PA") or (
                            im.mode == "P" and "transparency" in im.info
                        )
                        out = io.BytesIO()
                        if has_alpha:
                            resized.convert("RGBA").save(out, "PNG", optimize=True)
                            image["mimeType"] = "image/png"
                        else:
                            resized.convert("RGB").save(out, "JPEG", quality=85)
                            image["mimeType"] = "image/jpeg"
                        saved_bytes += len(data) - out.tell()
                        print(
                            f"  image[{view_index}] {width}x{height} -> "
                            f"{new_size[0]}x{new_size[1]} ({len(data)//1024}KB -> "
                            f"{out.tell()//1024}KB)"
                        )
                        data = out.getvalue()
                        changed += 1
            except Exception as error:
                print(f"  image[{view_index}]: skipped ({error})")

        padding = -len(data) % 4
        view["byteOffset"] = cursor
        view["byteLength"] = len(data)
        rebuilt.append(data + b"\x00" * padding)
        cursor += len(data) + padding

    if changed:
        doc["buffers"][0]["byteLength"] = cursor
        gr.write_glb(path, doc, b"".join(rebuilt))
        print(f"{path}: {changed} texture(s) shrunk, saved {saved_bytes//1024}KB")
    else:
        print(f"{path}: nothing to shrink")
    return changed


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = argv[1:]
    max_dim = 1024
    if "--max" in args:
        index = args.index("--max")
        max_dim = int(args[index + 1])
        del args[index : index + 2]
    if not args:
        print(__doc__)
        return 1
    total = 0
    for path in args:
        total += shrink_file(path, max_dim)
    print(f"done: {total} texture(s) shrunk across {len(args)} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
