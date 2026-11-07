"""Converts the CTM mod's 1.12 texture metadata (Chisel's connected / patterned / random textures) into Continuity
(OptiFine-format) CTM properties for the mirror's 1.21 client.

CTM keeps its rules in the sprite's .mcmeta ("ctm" section). Each sprite the mirror's meshes use is checked; the
supported types become `assets/nebulaemirror/optifine/ctm/<ns>/<path>[_<face>].properties` plus the tile images
cut out of CTM's sheets under `nebulaemirror:block/ctm/...`. Type mapping (CTM 1.12 / Chisel -> Continuity):

  ctm           base + 2x2 "-ctm" sheet  -> ctm_compact (0 base, 1 full, 2 vertical, 3 horizontal, 4 inner corners)
  ctmh          2x2 sheet                -> horizontal
  pillar/ctmv   base + 2x2 "-ctmv" sheet -> vertical, side faces only (vertical columns)
  pattern/v     NxN sheet                -> repeat, one file per face group so the tile phase matches CTM
  random/r, AR  NxN (AR: 2x2) sheet      -> random
  sctm          2x2 sheet                -> fixed (the unconnected tile)

Connections use connect=state, CTM's default (same block state). Textures with "light": 15 are emissive; that is
applied to the mesh quads by the caller (see emissive()).
"""
import json
import os

from . import MOD_ID
from .png import crop, read_png, write_png
from .util import sanitize_path

# CTM TextureContextGrid.Patterned vs Continuity RepeatSpriteProvider: Continuity index + (dx, dy) = CTM index.
_REPEAT_FACES = (
    ('top', ('top',), 0, 1),
    ('bottom', ('bottom',), 0, 2),
    ('ns', ('north', 'south'), 0, 0),
    ('west', ('west',), 1, 0),
    ('east', ('east',), -1, 0),
)


class CtmConverter:
    def __init__(self, dump, assets_dir, report):
        self.dump = dump
        self.assets = assets_dir
        self.report = report
        self._meta = {}

    # ------------------------------------------------------------------ metadata

    def meta(self, sprite):
        """The "ctm" section of a 1.12 sprite's .mcmeta, or None."""
        if sprite not in self._meta:
            self._meta[sprite] = self._read_meta(sprite)
        return self._meta[sprite]

    def _read_meta(self, sprite):
        if not sprite or ':' not in sprite:
            return None
        path = self.dump.texture_file(sprite) + '.mcmeta'
        if not os.path.exists(path):
            return None
        try:
            with open(path, encoding='utf-8-sig') as f:
                data = json.load(f)
        except ValueError:
            return None
        ctm = data.get('ctm')
        return ctm if isinstance(ctm, dict) else None

    def emissive(self, sprite):
        """CTM "light": 15 renders the texture full bright (CTM AbstractTexture); lower levels are not reproduced."""
        ctm = self.meta(sprite)
        if not ctm:
            return False
        light = (ctm.get('extra') or {}).get('light')
        if isinstance(light, dict):
            light = max(light.get('block', 0), light.get('sky', 0))
        return isinstance(light, (int, float)) and light >= 15

    # ------------------------------------------------------------------ conversion

    def convert(self, sprites):
        """sprites: {1.12 sprite name: 1.21 texture id} actually used by the mirror's meshes."""
        for sprite, target in sorted(sprites.items()):
            ctm = self.meta(sprite)
            if not ctm or target.startswith('minecraft:') or target.startswith(MOD_ID + ':block/missing'):
                continue
            kind = str(ctm.get('type', 'normal')).lower()
            try:
                done = self._convert(sprite, target, kind, ctm)
            except (OSError, ValueError, KeyError, IndexError) as e:
                self.report.warn(sprite, 'CTM %s not converted: %s: %s' % (kind, type(e).__name__, e))
                continue
            if done:
                self.report.count('ctm.converted')
                self.report.count('ctm.' + kind)
            elif kind not in ('normal', 'bwm_strata'):
                self.report.item('ctmUnsupported', '%s (%s)' % (sprite, kind))

    def _convert(self, sprite, target, kind, ctm):
        extra = ctm.get('extra') or {}
        textures = ctm.get('textures') or []
        if kind == 'ctm':
            if not textures:
                return False
            tiles = self._cut(textures[0], 2, 2, sprite)
            # CTMLogic: the -ctm sheet is TL fully connected, TR vertical, BL horizontal, BR inner corners.
            self._write(target, 'ctm_compact', [target, tiles[0], tiles[1], tiles[2], tiles[3]], connect=True)
            return True
        if kind in ('ctmh', 'ctm_horizontal'):
            t = self._cut(sprite, 2, 2, sprite)
            # TextureCTMH: none TL, both TR, left only BR, right only BL; Continuity: right, both, left, none.
            self._write(target, 'horizontal', [t[2], t[1], t[3], t[0]], connect=True)
            return True
        if kind in ('pillar', 'ctmv'):
            if not textures:
                return False
            t = self._cut(textures[0], 2, 2, sprite)
            # TextureCTMV on a vertical column's sides: both BL, up only BR, down only TR, none the base texture.
            # Continuity vertical: up only, both, down only, none.
            self._write(target, 'vertical', [t[3], t[2], t[1], target], connect=True, faces='sides')
            return True
        if kind in ('pattern', 'v'):
            w, h = self._size(extra)
            t = self._cut(sprite, w, h, sprite)
            for suffix, faces, dx, dy in _REPEAT_FACES:
                order = [t[((y + dy) % h) * w + (x + dx) % w] for y in range(h) for x in range(w)]
                self._write(target, 'repeat', order, faces=' '.join(faces), suffix=suffix,
                            extra={'width': w, 'height': h})
            return True
        if kind in ('random', 'r', 'ar'):
            w, h = (2, 2) if kind == 'ar' else self._size(extra)
            self._write(target, 'random', self._cut(sprite, w, h, sprite))
            return True
        if kind == 'sctm':
            self._write(target, 'fixed', [self._cut(sprite, 2, 2, sprite)[0]])
            return True
        return False

    @staticmethod
    def _size(extra):
        if 'width' in extra and 'height' in extra:
            return int(extra['width']), int(extra['height'])
        s = int(extra.get('size', 2))
        return s, s

    def _cut(self, sheet, cols, rows, owner):
        """Cut a sheet into cols x rows tiles (row-major); animated sheets keep their frames and animation."""
        src = self.dump.texture_file(sheet)
        width, height, pixels = read_png(src)
        tw = width // cols
        frame_h = tw * rows  # tiles are square
        if tw == 0 or frame_h == 0 or height % frame_h:
            raise ValueError('%s is %dx%d, not a %dx%d sheet' % (sheet, width, height, cols, rows))
        frames = height // frame_h
        animation = self._animation(src) if frames > 1 else None
        ns, path = sheet.split(':', 1)
        base = 'block/ctm/%s/%s' % (sanitize_path(ns), sanitize_path(path))
        out = []
        for ty in range(rows):
            for tx in range(cols):
                tile = []
                for f in range(frames):
                    tile += crop(pixels, tx * tw, f * frame_h + ty * tw, tw, tw)
                rel = '%s/%d' % (base, ty * cols + tx)
                dest = os.path.join(self.assets, MOD_ID, 'textures', rel + '.png')
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                write_png(dest, tw, tw * frames, tile)
                if animation:
                    with open(dest + '.mcmeta', 'w', encoding='utf8') as f:
                        json.dump({'animation': animation}, f)
                out.append(MOD_ID + ':' + rel)
        self.report.count('ctm.tiles', len(out))
        return out

    @staticmethod
    def _animation(png_path):
        try:
            with open(png_path + '.mcmeta', encoding='utf-8-sig') as f:
                anim = json.load(f).get('animation')
        except (OSError, ValueError):
            return None
        if isinstance(anim, dict):
            anim = {k: v for k, v in anim.items() if k in ('frametime', 'interpolate', 'frames')}
        return anim if isinstance(anim, dict) else None

    def _write(self, target, method, tiles, connect=False, faces=None, suffix=None, extra=None):
        ns, path = target.split(':', 1)
        # Continuity reads a "block_<id>" file name as matchBlocks=<id> (e.g. block_stone), so drop that prefix.
        stem = path[len('block/'):] if path.startswith('block/') else path
        name = '%s/%s%s' % (sanitize_path(ns), stem.replace('/', '_'), '_' + suffix if suffix else '')
        lines = ['# generated by NebulaeMirror from the CTM 1.12 metadata of ' + target,
                 'method=' + method,
                 'matchTiles=' + target,
                 'tiles=' + ' '.join(tiles)]
        if connect:
            lines.append('connect=state')
        if faces:
            lines.append('faces=' + faces)
        for k, v in (extra or {}).items():
            lines.append('%s=%s' % (k, v))
        dest = os.path.join(self.assets, MOD_ID, 'optifine', 'ctm', name + '.properties')
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, 'w', encoding='utf8') as f:
            f.write('\n'.join(lines) + '\n')
        self.report.count('ctm.properties')
