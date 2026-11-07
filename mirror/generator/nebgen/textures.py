"""Maps 1.12 sprite names to 1.21 texture ids and copies the files the mirror needs."""
import json
import os
import re
import shutil

from . import MOD_ID
from .png import write_checker_png
from .util import sanitize_name, sanitize_path
from .vanilla import vanilla_texture_candidates

MISSING = MOD_ID + ':block/missing'


def modded_texture_path(path):
    """'blocks/x/y' -> 'block/x/y'. Anything outside blocks/ goes under block/_/ so the block atlas still picks it up."""
    p = sanitize_path(path)
    if p.startswith('blocks/'):
        return 'block/' + p[len('blocks/'):]
    if p.startswith('block/'):
        return p
    return 'block/_/' + p


class TextureResolver:
    def __init__(self, dump, vanilla, assets_dir, report):
        self.dump = dump
        self.vanilla = vanilla
        self.assets = assets_dir
        self.report = report
        self.cache = {}
        self._missing_written = False

    def resolve(self, sprite):
        if sprite not in self.cache:
            self.cache[sprite] = self._resolve(sprite)
        return self.cache[sprite]

    def _resolve(self, sprite):
        if not sprite or sprite == 'missingno':
            return self._missing(sprite)
        if ':' not in sprite:
            # Runtime-registered sprite without a namespace (extrautils2 'glass_15', 'connected/...').
            # The dumper read it from the atlas into textures/minecraft/<name>.png.
            target = MOD_ID + ':block/legacy/_/' + sanitize_path(sprite)
            if self._copy('minecraft:' + sprite, target):
                self.report.count('textures.atlasOnly')
                return target
            return self._missing(sprite)
        ns, path = sprite.split(':', 1)
        if ns == 'minecraft':
            if path.startswith('blocks/'):
                for cand in vanilla_texture_candidates(path[len('blocks/'):]):
                    if self.vanilla.has_texture('block/' + cand):
                        self.report.count('textures.vanilla121')
                        return 'minecraft:block/' + cand
            elif path.startswith('items/'):
                # The 1.21 block atlas also stitches item/ textures.
                cand = 'item/' + path[len('items/'):]
                if self.vanilla.has_texture(cand):
                    self.report.count('textures.vanilla121')
                    return 'minecraft:' + cand
            # No 1.21 equivalent: ship the 1.12 file under our namespace.
            target = MOD_ID + ':block/legacy/minecraft/' + sanitize_path(path)
            if self._copy(sprite, target):
                self.report.count('textures.vanillaLegacy')
                self.report.item('vanillaTexturesWithout121', sprite)
                return target
            return self._missing(sprite)
        target = sanitize_name(ns) + ':' + modded_texture_path(path)
        if self._copy(sprite, target):
            self.report.count('textures.modded')
            return target
        return self._missing(sprite)

    def _copy(self, sprite, target):
        src = self.dump.texture_file(sprite)
        if not os.path.exists(src):
            return False
        ns, path = target.split(':', 1)
        dest = os.path.join(self.assets, ns, 'textures', path + '.png')
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        shutil.copyfile(src, dest)
        if os.path.exists(src + '.mcmeta'):
            self._copy_mcmeta(src + '.mcmeta', dest + '.mcmeta', target)
        return True

    def _copy_mcmeta(self, src, dest, target):
        """1.12 read .mcmeta leniently (trailing commas, comments); 1.21 is strict, so normalise or drop it."""
        with open(src, encoding='utf-8-sig', errors='replace') as f:
            text = f.read()
        try:
            data = json.loads(text)
        except ValueError:
            cleaned = re.sub(r'//[^\n]*', '', text)
            cleaned = re.sub(r',\s*([\]}])', r'\1', cleaned)
            try:
                data = json.loads(cleaned)
                self.report.item('mcmetaRepaired', target)
            except ValueError:
                self.report.item('mcmetaDropped', target)
                return
        with open(dest, 'w', encoding='utf8') as f:
            json.dump(data, f)

    def _missing(self, sprite):
        self.report.item('texturesMissing', sprite)
        if not self._missing_written:
            dest = os.path.join(self.assets, MOD_ID, 'textures', 'block', 'missing.png')
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            write_checker_png(dest)
            self._missing_written = True
        return MISSING
