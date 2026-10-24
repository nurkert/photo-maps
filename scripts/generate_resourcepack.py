#!/usr/bin/env python3
"""Generates the PhotoMaps resource packs from the pixel art below.

Writes the Java pack (src/main/resourcepack/java), the Bedrock pack (src/main/resourcepack/bedrock), the Geyser item
mapping (src/main/resourcepack/geyser) and the pixel art of the web interface (src/main/resources/web/).
Commit the generated files; the Maven build zips them. Needs no third-party modules.

usage: python3 scripts/generate_resourcepack.py
"""

import json
import math
import random
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKS = ROOT / "src/main/resourcepack"
NAMESPACE = "photomaps"

# The photo code: a card with the three finder squares of a QR code and a tiny photo in the fourth corner.
QR_CODE = [
    ".oooooooooooooo.",
    "owwwwwwwwwwwwwwo",
    "owkkkkkwwkkkkkwo",
    "owkwwwkwkkwwwkwo",
    "owkwkwkkwkwkwkwo",
    "owkwwwkwkkwwwkwo",
    "owkkkkkwwkkkkkwo",
    "owwwkwkkwwkwkwwo",
    "owkwwkwwkwkkwwwo",
    "owkkkkkwkbbbyywo",
    "owkwwwkkwbbbyywo",
    "owkwkwkwkbgbbbwo",
    "owkwwwkwwgggbgwo",
    "owkkkkkwkgggggwo",
    "osssssssssssssso",
    ".oooooooooooooo.",
]

COLORS = {
    ".": (0, 0, 0, 0),
    "o": (43, 45, 58, 255),
    "w": (242, 241, 234, 255),
    "s": (207, 205, 196, 255),
    "k": (27, 28, 36, 255),
    "b": (99, 183, 240, 255),
    "y": (255, 211, 77, 255),
    "g": (79, 174, 74, 255),
}

# item id -> (display name, pixel art); the plugin sets the item model photomaps:<id> on paper
ITEMS = {"qr_code": ("Photo Code", QR_CODE)}

PACK_FORMAT = 46  # Minecraft 1.21.4, the first version with item model definitions
MAX_FORMAT = 99


def png(pixels, scale=1):
    """Encodes rows of RGBA tuples as a PNG, every pixel scaled to a scale x scale block."""
    height, width = len(pixels) * scale, len(pixels[0]) * scale
    raw = bytearray()
    for row in pixels:
        line = bytearray(b"\x00")
        for pixel in row:
            line += bytes(pixel) * scale
        raw += bytes(line) * scale

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


def pixels(art):
    assert len(art) == 16 and all(len(row) == 16 for row in art), "pixel art must be 16x16"
    return [[COLORS[c] for c in row] for row in art]


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, (dict, list)):
        content = json.dumps(content, indent=2) + "\n"
    path.write_bytes(content if isinstance(content, bytes) else content.encode())
    print("wrote", path.relative_to(ROOT))


def java_pack():
    base = PACKS / "java"
    write(base / "pack.mcmeta", {
        "pack": {
            "description": "PhotoMaps: photo code",
            "pack_format": PACK_FORMAT,
            "supported_formats": {"min_inclusive": PACK_FORMAT, "max_inclusive": MAX_FORMAT},
            "min_format": PACK_FORMAT,
            "max_format": MAX_FORMAT,
        }
    })
    write(base / "pack.png", png(pixels(QR_CODE), 8))
    assets = base / "assets" / NAMESPACE
    for item, (_, art) in ITEMS.items():
        write(assets / "items" / f"{item}.json",
              {"model": {"type": "minecraft:model", "model": f"{NAMESPACE}:item/{item}"}})
        write(assets / "models/item" / f"{item}.json",
              {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NAMESPACE}:item/{item}"}})
        write(assets / "textures/item" / f"{item}.png", png(pixels(art)))


def bedrock_pack():
    base = PACKS / "bedrock"
    write(base / "manifest.json", {
        "format_version": 2,
        "header": {
            "name": "PhotoMaps",
            "description": "Photo code item of PhotoMaps (Geyser)",
            "uuid": "5d0c7a4e-91b2-4c3f-8e6a-2f7b19d4c0a8",
            "version": [1, 0, 0],
            "min_engine_version": [1, 21, 0],
        },
        "modules": [{"type": "resources", "uuid": "b8e2f0d1-3a6c-4b95-a7d4-6c1e9f2b5a37", "version": [1, 0, 0]}],
    })
    write(base / "pack_icon.png", png(pixels(QR_CODE), 8))
    write(base / "textures/item_texture.json", {
        "resource_pack_name": "PhotoMaps",
        "texture_name": "atlas.items",
        "texture_data": {f"{NAMESPACE}_{item}": {"textures": [f"textures/items/{item}"]} for item in ITEMS},
    })
    for item, (_, art) in ITEMS.items():
        write(base / "textures/items" / f"{item}.png", png(pixels(art)))


def geyser_mappings():
    write(PACKS / "geyser/photomaps_mappings.json", {
        "format_version": 2,
        "items": {
            "minecraft:paper": [
                {
                    "type": "definition",
                    "model": f"{NAMESPACE}:{item}",
                    "bedrock_identifier": f"{NAMESPACE}:{item}",
                    "display_name": name,
                    "bedrock_options": {
                        "icon": f"{NAMESPACE}_{item}",
                        "display_handheld": False,
                        "creative_category": "items",
                    },
                }
                for item, (name, _) in ITEMS.items()
            ]
        },
    })


# --------------------------------------------------------------------------------------------- web interface art
# Item-like 16x16 sprites for the buttons of the upload page, drawn from shapes so they are easy to change.

def canvas():
    return [[(0, 0, 0, 0)] * 16 for _ in range(16)]


def rect(img, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            img[y][x] = color


def outline(img, color):
    """Adds a 1 pixel outline around everything drawn so far, like vanilla item sprites."""
    filled = {(x, y) for y in range(16) for x in range(16) if img[y][x][3]}
    for y in range(16):
        for x in range(16):
            if (x, y) not in filled and any((x + dx, y + dy) in filled for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                img[y][x] = color


def disc(img, cx, cy, r, color):
    for y in range(16):
        for x in range(16):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                img[y][x] = color


INK = (30, 30, 34, 255)


def camera_sprite():
    img = canvas()
    rect(img, 4, 3, 7, 4, (88, 88, 96, 255))  # viewfinder bump
    rect(img, 1, 5, 14, 13, (70, 70, 78, 255))  # body
    rect(img, 1, 5, 14, 5, (112, 112, 122, 255))  # lit top edge
    rect(img, 1, 13, 14, 13, (46, 46, 52, 255))  # shaded bottom edge
    disc(img, 7.5, 9, 3.3, (24, 24, 28, 255))  # lens ring
    disc(img, 7.5, 9, 2.3, (58, 110, 165, 255))  # glass
    img[8][6] = img[7][7] = (190, 225, 255, 255)  # reflection
    rect(img, 12, 6, 13, 6, (255, 211, 77, 255))  # flash
    outline(img, INK)
    return img


def selfie_sprite():
    img = canvas()
    rect(img, 4, 3, 11, 10, (224, 172, 128, 255))  # face
    rect(img, 4, 2, 11, 4, (92, 60, 36, 255))  # hair
    rect(img, 4, 5, 4, 6, (92, 60, 36, 255))
    rect(img, 11, 5, 11, 6, (92, 60, 36, 255))
    img[6][6] = img[6][9] = (40, 40, 60, 255)  # eyes
    rect(img, 6, 9, 9, 9, (170, 96, 80, 255))  # smile
    img[8][5] = img[8][10] = (170, 96, 80, 255)
    rect(img, 3, 11, 12, 14, (79, 174, 74, 255))  # shirt
    rect(img, 6, 11, 9, 11, (224, 172, 128, 255))  # neck
    outline(img, INK)
    return img


def gallery_sprite():
    img = canvas()
    rect(img, 1, 2, 14, 13, (126, 84, 46, 255))  # wooden frame
    rect(img, 2, 3, 13, 12, (99, 183, 240, 255))  # sky
    disc(img, 10.5, 5.5, 1.6, (255, 211, 77, 255))  # sun
    for x in range(2, 14):  # hills
        top = 9 + round(1.4 * math.sin(x / 1.6))
        rect(img, x, top, x, 12, (79, 174, 74, 255))
    rect(img, 1, 2, 14, 2, (158, 112, 66, 255))
    rect(img, 1, 13, 14, 13, (92, 60, 32, 255))
    outline(img, INK)
    return img


def map_sprite():
    img = canvas()
    rect(img, 1, 1, 14, 14, (226, 212, 170, 255))  # paper
    rect(img, 3, 3, 12, 12, (205, 190, 146, 255))
    rect(img, 3, 3, 7, 7, (99, 183, 240, 255))  # a lake
    rect(img, 8, 6, 12, 12, (79, 174, 74, 255))  # a forest
    rect(img, 4, 9, 7, 12, (190, 160, 100, 255))  # sand
    img[5][10] = img[4][11] = (190, 60, 50, 255)  # the marker
    outline(img, (120, 92, 56, 255))
    return img


def ink_sprite():
    """An ink pouch for the crafting recipe on the website."""
    img = canvas()
    disc(img, 7.5, 9.5, 4.6, (38, 38, 52, 255))
    rect(img, 6, 3, 9, 5, (38, 38, 52, 255))  # neck
    rect(img, 5, 2, 10, 2, (70, 70, 90, 255))  # knot
    disc(img, 6, 8, 1.2, (84, 84, 110, 255))  # shine
    rect(img, 4, 12, 11, 12, (24, 24, 34, 255))
    outline(img, (12, 12, 16, 255))
    return img


def empty_map_sprite():
    img = canvas()
    rect(img, 1, 1, 14, 14, (226, 212, 170, 255))
    rect(img, 3, 3, 12, 12, (214, 198, 154, 255))
    outline(img, (120, 92, 56, 255))
    return img


def background_tile():
    """A dark earthy tile for the page background, random but always the same."""
    rng = random.Random(7)
    shades = [(43, 33, 25), (52, 39, 29), (59, 44, 32), (36, 28, 21), (66, 50, 36)]
    img = []
    for y in range(16):
        row = []
        for x in range(16):
            r, g, b = rng.choices(shades, weights=[5, 6, 3, 3, 1])[0]
            row.append((r, g, b, 255))
        img.append(row)
    return img


def web_art():
    web = ROOT / "src/main/resources/web"
    write(web / "icon.png", png(pixels(QR_CODE), 8))
    for name, sprite in (("camera", camera_sprite), ("selfie", selfie_sprite), ("gallery", gallery_sprite),
                         ("map", map_sprite), ("ink", ink_sprite), ("empty_map", empty_map_sprite)):
        write(web / f"{name}.png", png(sprite()))
    write(web / "background.png", png(background_tile()))


if __name__ == "__main__":
    java_pack()
    bedrock_pack()
    geyser_mappings()
    web_art()
