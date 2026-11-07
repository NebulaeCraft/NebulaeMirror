import struct
import zlib


def write_checker_png(path, size=16, a=0xFFF800F8, b=0xFF000000):
    """Magenta/black checkerboard, the usual 'missing texture' look (stdlib only)."""
    rows = []
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            c = a if ((x // (size // 2)) + (y // (size // 2))) % 2 == 0 else b
            row += bytes([(c >> 16) & 255, (c >> 8) & 255, c & 255, (c >> 24) & 255])
        rows.append(bytes(row))
    raw = zlib.compress(b''.join(rows))

    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)

    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)))
        f.write(chunk(b'IDAT', raw))
        f.write(chunk(b'IEND', b''))
