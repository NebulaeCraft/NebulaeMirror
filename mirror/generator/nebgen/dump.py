import os

from . import DUMP_SCHEMA
from .util import read_json


class Dump:
    """Read-only view of a MirrorDumper output directory (schema 1)."""

    def __init__(self, root):
        self.root = root
        self.manifest = read_json(os.path.join(root, 'manifest.json'))
        if self.manifest.get('schema') != DUMP_SCHEMA:
            raise ValueError('dump schema %r, generator expects %d' % (self.manifest.get('schema'), DUMP_SCHEMA))
        self.index = read_json(os.path.join(root, 'blocks', 'index.json'))
        self.sprites = read_json(os.path.join(root, 'sprites.json'))
        p = os.path.join(root, 'biomes.json')
        self.biomes = read_json(p) if os.path.exists(p) else {}
        self._models = {}
        self._lang = {}

    def mod_versions(self):
        return {m['id']: m['version'] for m in self.manifest.get('mods', [])}

    def blocks(self):
        for entry in self.index:
            if entry.get('failed') or 'file' not in entry:
                continue
            yield read_json(os.path.join(self.root, entry['file']))

    def model(self, h):
        m = self._models.get(h)
        if m is None:
            m = read_json(os.path.join(self.root, 'models', h[:2], h + '.json'))['quads']
            self._models[h] = m
        return m

    def texture_file(self, sprite):
        ns, path = sprite.split(':', 1)
        return os.path.join(self.root, 'textures', ns, path + '.png')

    def lang(self, ns, code):
        key = (ns, code)
        if key not in self._lang:
            self._lang[key] = parse_lang(os.path.join(self.root, 'lang', ns, code + '.lang'))
        return self._lang[key]

    def lang_namespaces(self):
        d = os.path.join(self.root, 'lang')
        return sorted(os.listdir(d)) if os.path.isdir(d) else []


def parse_lang(path):
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding='utf8', errors='replace') as f:
        for line in f:
            line = line.rstrip('\n').rstrip('\r')
            if not line or line.startswith('#') or '=' not in line:
                continue
            k, v = line.split('=', 1)
            out[k.strip().lstrip('﻿')] = v
    return out
