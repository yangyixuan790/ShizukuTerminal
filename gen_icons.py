#!/usr/bin/env python3
"""Generate simple solid-color PNG icons without PIL."""
import zlib
import struct
import os
import sys

def make_png(width, height, rgb_color, path):
    r, g, b = rgb_color
    # PNG signature
    signature = b'\x89PNG\r\n\x1a\n'

    def chunk(chunk_type, data):
        chunk_len = struct.pack('>I', len(data))
        chunk_data = chunk_type + data
        crc = struct.pack('>I', zlib.crc32(chunk_data) & 0xffffffff)
        return chunk_len + chunk_data + crc

    # IHDR chunk
    ihdr_data = struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)
    ihdr = chunk(b'IHDR', ihdr_data)

    # IDAT chunk: raw image data with filter byte 0 at start of each scanline
    raw_data = b''
    for y in range(height):
        raw_data += b'\x00'  # filter: None
        raw_data += bytes([r, g, b]) * width
    compressed = zlib.compress(raw_data, 9)
    idat = chunk(b'IDAT', compressed)

    # IEND chunk
    iend = chunk(b'IEND', b'')

    with open(path, 'wb') as f:
        f.write(signature + ihdr + idat + iend)

# Sizes for each mipmap density (launcher icons)
sizes = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}

base = sys.argv[1] if len(sys.argv) > 1 else '.'
dark = (0x1A, 0x1A, 0x1A)  # terminal bg
green = (0x4C, 0xAF, 0x50)  # accent green

for density, size in sizes.items():
    d = os.path.join(base, f'app/src/main/res/mipmap-{density}')
    os.makedirs(d, exist_ok=True)
    # Simple two-tone: dark bg, smaller green center area
    path = os.path.join(d, 'ic_launcher.png')
    path_round = os.path.join(d, 'ic_launcher_round.png')

    # Generate image pixel by pixel in memory
    import io
    r, g, b = dark
    gr, gg, gb = green
    raw = b''
    margin = size // 6
    for y in range(size):
        raw += b'\x00'  # filter
        for x in range(size):
            # inner square or circle? just inset
            if margin <= x < size - margin and margin <= y < size - margin:
                raw += bytes([gr, gg, gb])
            else:
                raw += bytes([r, g, b])

    signature = b'\x89PNG\r\n\x1a\n'
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    ihdr = chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0))
    idat = chunk(b'IDAT', zlib.compress(raw, 9))
    iend = chunk(b'IEND', b'')
    data = signature + ihdr + idat + iend
    with open(path, 'wb') as f:
        f.write(data)
    with open(path_round, 'wb') as f:
        f.write(data)
    print(f'wrote {path} ({size}x{size})')

print('done')
