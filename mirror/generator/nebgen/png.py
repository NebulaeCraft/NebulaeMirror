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


def _chunk(tag, data):
    return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)


def read_png(path):
    """Decode a non-interlaced PNG into (width, height, rows of RGBA bytes). Supports every 8-bit colour type and
    paletted / grey images of 1, 2, 4 or 8 bits (what resource packs contain); 16-bit is reduced to 8."""
    with open(path, 'rb') as f:
        data = f.read()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('not a PNG')
    pos, idat, palette, trns = 8, [], None, None
    width = height = depth = ctype = interlace = None
    while pos < len(data):
        ln, = struct.unpack('>I', data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if tag == b'IHDR':
            width, height, depth, ctype, _, _, interlace = struct.unpack('>IIBBBBB', body)
        elif tag == b'PLTE':
            palette = body
        elif tag == b'tRNS':
            trns = body
        elif tag == b'IDAT':
            idat.append(body)
        elif tag == b'IEND':
            break
    if interlace:
        raise ValueError('interlaced PNG')
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bits = depth * channels
    stride = (width * bits + 7) // 8
    bpp = max(1, bits // 8)
    raw = zlib.decompress(b''.join(idat))
    rows, prev, p = [], bytearray(stride), 0
    for _ in range(height):
        ftype = raw[p]
        line = bytearray(raw[p + 1:p + 1 + stride])
        p += 1 + stride
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if ftype == 1:
                line[i] = (line[i] + a) & 255
            elif ftype == 2:
                line[i] = (line[i] + b) & 255
            elif ftype == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 255
            elif ftype == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        rows.append(_to_rgba(line, width, depth, ctype, palette, trns))
    return width, height, rows


def _samples(line, width, depth, channels):
    if depth == 8:
        return line
    if depth == 16:
        return line[0::2]
    out, mask, per = bytearray(), (1 << depth) - 1, 8 // depth
    for i in range(width * channels):
        byte = line[i // per]
        shift = 8 - depth * (i % per + 1)
        out.append((byte >> shift) & mask)
    return out


def _to_rgba(line, width, depth, ctype, palette, trns):
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    s = _samples(line, width, depth, channels)
    out = bytearray(width * 4)
    scale = 255 // ((1 << depth) - 1) if depth < 8 else 1
    for x in range(width):
        if ctype == 6:
            out[x * 4:x * 4 + 4] = s[x * 4:x * 4 + 4]
        elif ctype == 2:
            r, g, b = s[x * 3:x * 3 + 3]
            out[x * 4:x * 4 + 4] = bytes((r, g, b, 255))
        elif ctype == 3:
            i = s[x]
            r, g, b = palette[i * 3:i * 3 + 3]
            a = trns[i] if trns and i < len(trns) else 255
            out[x * 4:x * 4 + 4] = bytes((r, g, b, a))
        elif ctype == 0:
            v = s[x] * scale
            out[x * 4:x * 4 + 4] = bytes((v, v, v, 255))
        else:  # 4: grey + alpha
            v, a = s[x * 2], s[x * 2 + 1]
            out[x * 4:x * 4 + 4] = bytes((v, v, v, a))
    return bytes(out)


def write_png(path, width, height, rows):
    """Write RGBA rows (bytes of width*4) as an 8-bit RGBA PNG."""
    raw = zlib.compress(b''.join(b'\0' + r for r in rows), 9)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(_chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)))
        f.write(_chunk(b'IDAT', raw))
        f.write(_chunk(b'IEND', b''))


def crop(rows, x, y, w, h):
    return [r[x * 4:(x + w) * 4] for r in rows[y:y + h]]
