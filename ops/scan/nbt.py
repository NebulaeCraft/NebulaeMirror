"""Minimal read-only NBT decoder (stdlib only). Byte arrays stay `bytes`; int/long arrays become lists."""
import gzip
import io
import struct


def _read(b, t):
    if t == 1:
        return struct.unpack('>b', b.read(1))[0]
    if t == 2:
        return struct.unpack('>h', b.read(2))[0]
    if t == 3:
        return struct.unpack('>i', b.read(4))[0]
    if t == 4:
        return struct.unpack('>q', b.read(8))[0]
    if t == 5:
        return struct.unpack('>f', b.read(4))[0]
    if t == 6:
        return struct.unpack('>d', b.read(8))[0]
    if t == 7:
        n = _read(b, 3)
        return b.read(n)
    if t == 8:
        n = struct.unpack('>H', b.read(2))[0]
        return b.read(n).decode('utf8', 'replace')
    if t == 9:
        et = b.read(1)[0]
        n = _read(b, 3)
        return [_read(b, et) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            tt = b.read(1)[0]
            if tt == 0:
                return d
            k = _read(b, 8)
            d[k] = _read(b, tt)
    if t == 11:
        n = _read(b, 3)
        return list(struct.unpack('>%di' % n, b.read(4 * n)))
    if t == 12:
        n = _read(b, 3)
        return list(struct.unpack('>%dq' % n, b.read(8 * n)))
    raise ValueError('bad NBT tag %d' % t)


def load(raw):
    """Decode a root compound from raw (optionally gzip-compressed) bytes."""
    if raw[:2] == b'\x1f\x8b':
        raw = gzip.decompress(raw)
    b = io.BytesIO(raw)
    t = b.read(1)[0]
    _read(b, 8)
    return _read(b, t)
