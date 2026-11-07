"""
dump (1.12) -> mirror (1.21.1)

Output layout (`--out`):
  mirror/blocks.json      block definitions the mirror mod registers (properties, per-state physics, items, names)
  mirror/forward.json     1.12 (name, meta) -> 1.21 block + props        (importer, origin -> mirror)
  mirror/reverse.json     1.21 block + stored props -> 1.12 (name, meta)  (submit, mirror -> origin)
  mirror/source.json      provenance: dump manifest, mod versions, generator schema
  assets/<ns>/blockstates/<path>.json         variants -> nebulaemirror:mesh/<hash>
  assets/nebulaemirror/meshes/<hash>.json     baked quads (loaded by the mod's model loading plugin)
  assets/<ns>/textures/block/...              textures (vanilla ones are referenced, not copied, when 1.21 has them)
  assets/<ns>/lang/{en_us,zh_cn}.json
  report.json / report.md
"""
import itertools
import os
import shutil
import time

from . import MOD_ID, SCHEMA, archetypes, overrides as ovr
from .dump import Dump
from .props import make_extra
from .report import Report
from .textures import MISSING, TextureResolver
from .util import content_hash, sanitize_id, variant_key, write_json
from .vanilla import Vanilla121

MARKER = '.nebgen-output'

LAYER_ORDER = ['solid', 'cutout_mipped', 'cutout', 'translucent']

SOUNDS = {
    'ROCK': 'stone', 'IRON': 'metal', 'WOOD': 'wood', 'CLOTH': 'wool', 'CARPET': 'wool', 'GLASS': 'glass',
    'GROUND': 'gravel', 'GRASS': 'grass', 'SAND': 'sand', 'LEAVES': 'grass', 'PLANTS': 'grass', 'VINE': 'vine',
    'ANVIL': 'anvil', 'SNOW': 'snow', 'CRAFTED_SNOW': 'snow', 'CLAY': 'gravel', 'ICE': 'glass',
    'PACKED_ICE': 'glass', 'GOURD': 'wood', 'CACTUS': 'wool', 'WEB': 'cobweb', 'REDSTONE_LIGHT': 'glass',
    'CIRCUITS': 'stone', 'SPONGE': 'grass', 'CAKE': 'wool', 'TNT': 'grass', 'CORAL': 'coral',
}


class Generator:
    def __init__(self, dump_dir, mc_dir, overrides_dir, out_dir):
        self.report = Report()
        self.dump = Dump(dump_dir)
        self.vanilla = Vanilla121(mc_dir)
        self.rules = ovr.load(overrides_dir) if overrides_dir and os.path.isdir(overrides_dir) else {}
        self.out = out_dir
        self.assets = os.path.join(out_dir, 'assets')
        self.textures = TextureResolver(self.dump, self.vanilla, self.assets, self.report)
        self.meshes_written = set()
        self.shapes = []
        self._shape_index = {}
        self.blocks = []
        self.forward = {}
        self.reverse = {}
        self.lang = {}  # ns -> code -> {key: text}

    # ------------------------------------------------------------------ driver

    def run(self):
        start = time.time()
        self._prepare_out()
        for problem in archetypes.verify_against_vanilla(self.vanilla):
            self.report.error('archetype spec out of date: ' + problem)

        deferred_targets = []
        for block in self.dump.blocks():
            name = block['name']
            ns = name.split(':')[0]
            if ns == 'minecraft':
                continue  # vanilla goes through DFU in the importer
            self.report.count('blocks.dumped')
            rule = self.rules.get(name, {})
            if rule.get('unknown'):
                self.report.count('blocks.unknownByOverride')
                self.report.item('unknownByOverride', name)
                continue
            if 'target' in rule:
                deferred_targets.append((block, rule))
                continue
            try:
                self._generate_block(block, rule.get('archetype'))
            except Exception as e:  # keep going; one broken block must not sink the run
                self.report.error('%s: %s: %s' % (name, type(e).__name__, e))
                self.report.count('blocks.failed')

        generated_ids = {b['id'] for b in self.blocks}
        for block, rule in deferred_targets:
            self._map_to_target(block, rule, generated_ids)

        for name in self.rules:
            if not any(b['name'] == name for b in self.dump.index):
                self.report.warn(name, 'override for a block that is not in the dump')

        self._self_check()
        self._write_outputs()
        self.report.count('seconds', int(time.time() - start))
        return self.report

    def _prepare_out(self):
        if os.path.exists(self.out):
            if not os.path.exists(os.path.join(self.out, MARKER)):
                raise SystemExit('refusing to clear %s: it was not created by the generator' % self.out)
            shutil.rmtree(self.out)
        os.makedirs(self.out)
        open(os.path.join(self.out, MARKER), 'w').close()

    # ------------------------------------------------------------------ generated blocks

    def _generate_block(self, block, forced_archetype):
        name = block['name']
        bid = sanitize_id(name)
        if bid != name:
            self.report.item('sanitizedIds', '%s -> %s' % (name, bid))
        states = block.get('states', [])
        if not states:
            raise ValueError('no states dumped')
        by_props = {variant_key(s['props']): s for s in states}
        legacy_defs = {p['name']: p for p in block['properties']}
        stored = set(block.get('storedProperties', []))

        arch = archetypes.classify(block, forced_archetype)
        own = list(arch.props) if arch else []
        taken = {p.name for p in own}
        own_legacy = {p.legacy for p in own if p.legacy}
        extras = [make_extra(legacy_defs[n], taken) for n in legacy_defs if n not in own_legacy]
        keyed_own = [p for p in own if not p.ignored]
        self.report.count('archetype.' + (arch.name if arch else 'generic'))

        def to_121(legacy_props):
            out = {}
            for p in keyed_own:
                if p.legacy is None:
                    continue
                out[p.name] = p.value_121(legacy_props[p.legacy])
            for e in extras:
                out[e.name] = e.from_legacy(legacy_props[e.legacy])
            return out

        def to_legacy(p121):
            out = {}
            for p in keyed_own:
                if p.legacy is not None:
                    out[p.legacy] = p.legacy_value(p121[p.name])
            for e in extras:
                out[e.legacy] = e.legacy_value(p121[e.name])
            return out

        # 1.21 state space (variant keys never include ignored props such as waterlogged).
        if arch is None:
            combos = [to_121(s['props']) for s in states]
        else:
            extra_combos = []
            seen = set()
            for s in states:
                ec = {e.name: e.from_legacy(s['props'][e.legacy]) for e in extras}
                k = variant_key(ec)
                if k not in seen:
                    seen.add(k)
                    extra_combos.append(ec)
            combos = []
            names = [p.name for p in keyed_own]
            for values in itertools.product(*[p.values for p in keyed_own]):
                own_part = dict(zip(names, values))
                for ec in extra_combos:
                    c = dict(own_part)
                    c.update(ec)
                    combos.append(c)

        default_legacy = block['defaultState']
        default_state = by_props.get(variant_key(default_legacy), states[0])
        variants, physics = {}, {}
        layers, tints, emissive, fallbacks, empty, missing_tex = set(), set(), False, 0, 0, False
        for p121 in combos:
            legacy = to_legacy(p121)
            s = by_props.get(variant_key(legacy))
            if s is None:
                s = self._closest_state(states, legacy, stored) or default_state
                fallbacks += 1
            mesh_id, info = self._mesh(s)
            key = variant_key(p121)
            variants[key] = {'model': mesh_id}
            physics[key] = self._physics(s, generic=arch is None)
            layers |= info['layers']
            tints |= info['tints']
            emissive |= info['emissive']
            empty += info['empty']
            missing_tex |= info['missing']
        if fallbacks:
            self.report.warn(name, 'some 1.21 states have no exact 1.12 state; nearest state used')
            self.report.count('states.fallback', fallbacks)
        if empty:
            self.report.warn(name, 'states without quads (TESR / built-in / invisible)')
        if missing_tex:
            self.report.warn(name, 'model uses a texture that is missing in 1.12 too (shows as missing texture)')
        if arch is None:
            # Single-valued properties can't vary, so "not encoded in meta" is harmless for them.
            derived = [p for p in block.get('derivedProperties', []) if len(legacy_defs[p]['values']) > 1]
            if derived:
                self.report.warn(name, 'generic block with neighbour-derived 1.12 properties (mirror shows them as stored)')
        if arch and arch.pair_merge:
            self.report.item('pairMergeBlocks', bid)
        self.report.count('states.generated', len(combos))

        ns, path = bid.split(':', 1)
        write_json(os.path.join(self.assets, ns, 'blockstates', path + '.json'), {'variants': variants}, pretty=False)

        # forward / reverse
        metas = {}
        for m in block['metaTable']:
            if m.get('roundTrip') != m['meta']:
                continue
            metas[str(m['meta'])] = to_121(m['state'])
        self.forward[name] = {'target': bid, 'metas': metas}
        keys = [p.name for p in keyed_own if p.legacy in stored] + [e.name for e in extras if e.legacy in stored]
        self._add_reverse(bid, keys, name, metas, override=False)

        material = default_state.get('material', 'ROCK')
        names = self._names(block)
        prop_json = [self._own_json(p, p.legacy in stored) for p in own] + \
                    [e.to_json(e.legacy in stored) for e in extras]
        default_121 = to_121(default_legacy)
        self.blocks.append({
            'id': bid,
            'source': name,
            'archetype': arch.name if arch else 'generic',
            'properties': prop_json,
            'default': default_121,
            'material': material,
            'sound': SOUNDS.get(material, 'stone'),
            'renderLayer': max(layers, key=LAYER_ORDER.index) if layers else 'solid',
            'emissive': emissive,
            'offset': block.get('offsetType') if block.get('offsetType') not in (None, 'NONE') else None,
            'legacyBlockEntity': any(s.get('hasTileEntity') for s in states),
            'tints': self._tints(states, tints),
            'states': physics,
            'items': self._items(block, to_121),
            'names': names,
            'translationKey': 'block.%s.%s' % (ns, path.replace('/', '.')),
        })
        for code, text in names.items():
            self._lang(ns, code)['block.%s.%s' % (ns, path.replace('/', '.'))] = text
        if self.blocks[-1]['legacyBlockEntity']:
            self.report.item('legacyBlockEntityBlocks', bid)

    @staticmethod
    def _own_json(p, stored):
        d = {'name': p.name, 'kind': p.kind, 'values': p.values, 'archetype': True, 'stored': stored}
        if p.legacy:
            d['legacy'] = p.legacy
        if p.ignored:
            d['ignored'] = True
        if p.derived:
            d['derived'] = True
        if p.to_legacy:
            d['legacyValues'] = p.to_legacy
        return d

    @staticmethod
    def _closest_state(states, legacy, stored):
        """Prefer a state that agrees on every stored property, then on as many properties as possible."""
        best, best_score = None, -1
        for s in states:
            props = s['props']
            if any(props.get(k) != legacy.get(k) for k in stored if k in legacy):
                continue
            score = sum(1 for k, v in legacy.items() if props.get(k) == v)
            if score > best_score:
                best, best_score = s, score
        return best

    # ------------------------------------------------------------------ meshes / physics

    def _mesh(self, state):
        model = state.get('model') or {}
        quads = []
        info = {'layers': set(), 'tints': set(), 'emissive': False, 'empty': 0, 'missing': False}
        for layer, h in (model.get('layers') or {}).items():
            lname = layer.lower()
            for q in self.dump.model(h):
                q2 = dict(q)
                q2['layer'] = lname
                q2['sprite'] = self.textures.resolve(q.get('sprite'))
                if q2['sprite'] == MISSING:
                    info['missing'] = True
                quads.append(q2)
                info['layers'].add(lname)
                if 'tint' in q:
                    info['tints'].add(q['tint'])
                if q.get('emissive'):
                    info['emissive'] = True
        if not quads:
            info['empty'] = 1
        particle = model.get('particle')
        mesh = {
            'ao': model.get('ao', True),
            'particle': self.textures.resolve(particle) if particle else MISSING,
            'quads': quads,
        }
        h = content_hash(mesh)
        if h not in self.meshes_written:
            write_json(os.path.join(self.assets, MOD_ID, 'meshes', h + '.json'), mesh, pretty=False)
            self.meshes_written.add(h)
            self.report.count('meshes')
            self.report.count('quads', len(quads))
        return MOD_ID + ':mesh/' + h, info

    def _shape(self, box):
        key = tuple(box)
        if key not in self._shape_index:
            self._shape_index[key] = len(self.shapes)
            self.shapes.append(list(box))
        return self._shape_index[key]

    def _physics(self, s, generic):
        d = {
            'light': s.get('light', 0),
            'opaque': bool(s.get('opaqueCube', False)),
            'fullCube': bool(s.get('fullCube', False)),
        }
        if generic:
            # Archetypes use the 1.21 behaviour class's own shapes.
            if 'bounds' in s:
                d['shape'] = self._shape(s['bounds'])
            if s.get('noCollision'):
                d['collision'] = None
            elif 'collision' in s:
                d['collision'] = self._shape(s['collision'])
        return d

    def _tints(self, states, indices):
        out = {}
        plains = self.dump.biomes.get('minecraft:plains', {})
        for t in sorted(indices):
            info = None
            for s in states:
                c = (s.get('colors') or {}).get(str(t))
                if c and 'plains' in c:
                    info = c
                    break
            if info is None:
                out[str(t)] = {'kind': 'constant', 'color': -1}
                continue
            color = info['plains'] & 0xFFFFFF
            if info.get('biomeDependent'):
                if color == plains.get('grassColor', -2) & 0xFFFFFF:
                    out[str(t)] = {'kind': 'grass'}
                elif color == plains.get('foliageColor', -2) & 0xFFFFFF:
                    out[str(t)] = {'kind': 'foliage'}
                else:
                    out[str(t)] = {'kind': 'constant', 'color': color}
                    self.report.warn('', 'biome-dependent tint not recognised as grass/foliage; plains colour used')
            else:
                out[str(t)] = {'kind': 'constant', 'color': color}
        return out

    # ------------------------------------------------------------------ names / items / lang

    def _lookup_name(self, key, fallback):
        names = {}
        for code in ('en_us', 'zh_cn'):
            for ns in self.dump.lang_namespaces():
                text = self.dump.lang(ns, code).get(key)
                if text is not None:
                    names[code] = text
                    break
        if not names and fallback:
            names = {'en_us': fallback, 'zh_cn': fallback}
        names.setdefault('en_us', names.get('zh_cn'))
        names.setdefault('zh_cn', names.get('en_us'))
        return names

    def _names(self, block):
        items = block.get('items') or []
        if items:
            it = items[0]
            names = self._lookup_name((it.get('translationKey') or '') + '.name', it.get('displayName'))
            if names.get('en_us'):
                return names
        tk = block.get('translationKey') or ''
        names = self._lookup_name(tk + '.name', None)
        if names.get('en_us'):
            return names
        path = block['name'].split(':', 1)[1]
        return {'en_us': path, 'zh_cn': path}

    @staticmethod
    def _item_state(block, item_meta, item_key):
        """
        Block state an item subtype places. Item damage is NOT the block meta (meta 1 may be facing=south while
        item 1 is subtype1), so try, in order: getPickBlock damage, damageDropped, a property value named in
        the item's translation key (MCreator: tile.x.subtype1), and finally item damage == block meta.
        Among matches, prefer the state closest to the default state.
        """
        valid = {m['meta'] for m in block['metaTable'] if m.get('roundTrip') == m['meta']}
        states = [s for s in block.get('states', []) if s.get('meta') in valid]
        default = block['defaultState']

        def closest(cands):
            best, best_score = None, -1
            for s in cands:
                score = sum(1 for k, v in s['props'].items() if default.get(k) == v)
                if score > best_score:
                    best, best_score = s['props'], score
            return best

        for signal in ('pick', 'drop'):
            cands = [s for s in states if s.get(signal) == item_meta]
            if cands:
                return closest(cands), signal
        tokens = set((item_key or '').replace(':', '.').split('.'))
        cands = [s for s in states if any(str(v) in tokens for k, v in s['props'].items() if k not in ('facing',))]
        if cands:
            return closest(cands), 'name'
        m = next((m for m in block['metaTable'] if m['meta'] == item_meta and m.get('roundTrip') == item_meta), None)
        return (m['state'], 'meta') if m else (None, None)

    def _items(self, block, to_121):
        ns = sanitize_id(block['name']).split(':')[0]
        out = []
        for it in block.get('items') or []:
            if it.get('item') and it['item'] != block['name']:
                self.report.item('foreignItemSubtypes', '%s lists %s:%s' % (block['name'], it['item'], it['meta']))
                continue
            key = (it.get('translationKey') or '') + '.name'
            names = self._lookup_name(key, it.get('displayName'))
            entry = {'meta': it['meta'], 'key': key, 'names': names}
            st, how = self._item_state(block, it['meta'], it.get('translationKey'))
            if st is not None:
                entry['state'] = to_121(st)
                self.report.count('items.by_' + how)
            else:
                self.report.warn(block['name'], 'item subtype with no placeable state')
            if it.get('tab'):
                entry['tab'] = it['tab']
            out.append(entry)
            for code, text in names.items():
                if text:
                    self._lang(ns, code)[key] = text
        return out

    def _lang(self, ns, code):
        return self.lang.setdefault(ns, {}).setdefault(code, {})

    # ------------------------------------------------------------------ overrides onto existing blocks

    def _map_to_target(self, block, rule, generated_ids):
        name = block['name']
        target = rule['target']
        metas = {}
        problems = []
        for m in block['metaTable']:
            if m.get('roundTrip') != m['meta']:
                continue
            p = ovr.target_props(rule, m['state'], m['meta'])
            if p is None:
                continue
            problems += ovr.validate_target(rule, p, self.vanilla, generated_ids)
            metas[str(m['meta'])] = p
        if problems:
            for pr in sorted(set(problems)):
                self.report.error('override %s: %s' % (name, pr))
            return
        self.forward[name] = {'target': target, 'metas': metas, 'override': True}
        self.report.count('blocks.mappedByOverride')
        if rule.get('reverse', True):
            keys = sorted({k for p in metas.values() for k in p})
            self._add_reverse(target, keys, name, metas, override=True)

    # ------------------------------------------------------------------ reverse

    def _add_reverse(self, target, keys, name, metas, override):
        entry = self.reverse.setdefault(target, {'keys': keys, 'map': {}})
        if entry['keys'] != keys:
            # Several 1.12 blocks map onto one target with different key sets: widen to the union.
            merged = sorted(set(entry['keys']) | set(keys))
            self.report.warn(target, 'reverse keys widened because several 1.12 blocks share this target')
            entry['keys'] = merged
            keys = merged
        if override:
            entry['override'] = True
        for meta, p in metas.items():
            k = variant_key({key: p[key] for key in keys if key in p})
            if k in entry['map']:
                prev = entry['map'][k]
                if prev != [name, int(meta)]:
                    self.report.item('reverseAmbiguous', '%s[%s] <- %s:%s (kept %s:%s)' % (
                        target, k, name, meta, prev[0], prev[1]))
                continue
            entry['map'][k] = [name, int(meta)]

    # ------------------------------------------------------------------ self check

    def _self_check(self):
        """Invariants the mirror relies on; violations are errors (non-zero exit)."""
        by_id = {b['id']: b for b in self.blocks}
        # 1. every forward meta is a legal state and comes back through reverse to the same (name, meta)
        for name, fw in self.forward.items():
            target = fw['target']
            rev = self.reverse.get(target)
            block = by_id.get(target)
            for meta, props in fw['metas'].items():
                if block is not None:
                    allowed = {p['name']: p['values'] for p in block['properties'] if not p.get('ignored')}
                    bad = [k for k, v in props.items() if k not in allowed or v not in allowed[k]]
                    if bad or set(props) != set(allowed):
                        self.report.error('%s:%s forward state %s does not match %s properties' % (
                            name, meta, props, target))
                if rev is None:
                    if fw.get('override') and not self.rules.get(name, {}).get('reverse', True):
                        continue
                    self.report.error('%s: no reverse entry for target %s' % (name, target))
                    break
                k = variant_key({key: props[key] for key in rev['keys'] if key in props})
                back = rev['map'].get(k)
                if back != [name, int(meta)]:
                    if back is not None and fw.get('override'):
                        continue  # deliberate many-to-one onto an existing block, listed in reverseAmbiguous
                    self.report.error('%s:%s round trip -> %s' % (name, meta, back))
            self.report.count('check.forwardBlocks')
        # 2. every generated block's blockstate covers its whole keyed state space exactly once
        for b in self.blocks:
            keyed = [p for p in b['properties'] if not p.get('ignored')]
            expected = 1
            for p in keyed:
                expected *= len(p['values'])
            have = len(b['states'])
            if b['archetype'] != 'generic' and have != expected:
                self.report.error('%s: %d variants, expected %d' % (b['id'], have, expected))
            if b['archetype'] == 'generic' and have > expected:
                self.report.error('%s: %d variants exceed the %d possible' % (b['id'], have, expected))
            if variant_key(b['default']) not in b['states']:
                self.report.error('%s: default state %s has no variant' % (b['id'], b['default']))
        self.report.count('check.blocks', len(self.blocks))

    # ------------------------------------------------------------------ output

    def _write_outputs(self):
        mirror = os.path.join(self.out, 'mirror')
        self.blocks.sort(key=lambda b: b['id'])
        write_json(os.path.join(mirror, 'blocks.json'), {
            'schema': SCHEMA, 'shapes': self.shapes, 'blocks': self.blocks}, pretty=False)
        write_json(os.path.join(mirror, 'forward.json'), {
            'schema': SCHEMA, 'blocks': dict(sorted(self.forward.items()))}, pretty=False)
        write_json(os.path.join(mirror, 'reverse.json'), {
            'schema': SCHEMA, 'blocks': dict(sorted(self.reverse.items()))}, pretty=False)
        write_json(os.path.join(mirror, 'source.json'), {
            'schema': SCHEMA,
            'dump': {k: self.dump.manifest.get(k) for k in ('schema', 'dumper', 'minecraft', 'forge', 'timestamp')},
            'mods': self.dump.mod_versions(),
            'overrides': sorted(self.rules),
            'generated': time.strftime('%Y-%m-%dT%H:%M:%S%z'),
        })
        for ns, langs in self.lang.items():
            for code, entries in langs.items():
                write_json(os.path.join(self.assets, ns, 'lang', code + '.json'), dict(sorted(entries.items())))
        self.report.count('blocks.generated', len(self.blocks))
        self.report.count('shapes', len(self.shapes))
        self.report.count('forward.blocks', len(self.forward))
        self.report.count('forward.metas', sum(len(v['metas']) for v in self.forward.values()))
        self.report.count('reverse.targets', len(self.reverse))
        write_json(os.path.join(self.out, 'report.json'), self.report.to_json())
        with open(os.path.join(self.out, 'report.md'), 'w', encoding='utf8') as f:
            f.write(self.report.to_markdown())


def generate(dump_dir, mc_dir, overrides_dir, out_dir):
    return Generator(dump_dir, mc_dir, overrides_dir, out_dir).run()
