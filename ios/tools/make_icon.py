#!/usr/bin/env python3
"""Draws the iPhone app icon (1024x1024, opaque): Murmur's radio-wave mark in mint on the night sky.

    python3 ios/tools/make_icon.py ios/Murmur/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png
"""
import math
import struct
import sys
import zlib

SIZE = 1024
NIGHT = (0x0A, 0x0F, 0x1E)
NIGHT_GLOW = (0x17, 0x20, 0x42)
MINT = (0x5E, 0xEA, 0xD4)


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def coverage(distance, half_width):
    """Anti-aliased band: 1 inside, fading over ~1.5 px at the edge."""
    return max(0.0, min(1.0, half_width - distance + 0.75))


def pixel(x, y):
    cx = cy = SIZE / 2
    dx, dy = x + 0.5 - cx, y + 0.5 - cy
    r = math.hypot(dx, dy)
    # A soft glow in the middle of the night sky.
    color = mix(NIGHT_GLOW, NIGHT, min(1.0, r / (SIZE * 0.62)))
    # Centre dot.
    color = mix(color, MINT, coverage(r - 74, 0))
    # Three waves on each side, fading outwards, within ±48° of the horizontal.
    angle = math.degrees(math.atan2(abs(dy), abs(dx)))
    if angle < 48:
        edge = min(1.0, (48 - angle) / 4)
        for radius, strength in ((190, 1.0), (300, 0.78), (410, 0.55)):
            a = coverage(abs(r - radius), 24) * strength * edge
            if a > 0:
                color = mix(color, MINT, a)
    return color


def write_png(path):
    rows = bytearray()
    for y in range(SIZE):
        rows.append(0)
        for x in range(SIZE):
            rows.extend(pixel(x, y))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(rows), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


if __name__ == "__main__":
    write_png(sys.argv[1] if len(sys.argv) > 1 else "AppIcon.png")
