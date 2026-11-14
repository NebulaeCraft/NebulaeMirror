#!/usr/bin/env python3
"""Helper for updating the mirror after the origin's mods changed (stdlib only). See docs/mod-updates.md.

  python3 -I ops/update/update.py prepare
      Before the dump: compare data/mods.zip with the last dump and list the jars to replace, add or remove
      in the 1.12.2 dump client. The jars to copy are put into work/update/client-mods/.

  python3 -I ops/update/update.py run <dump .zip | dump dir> [--old <previous dump dir>]
      [--stats work/world-stats.json | --world <origin world dir or zip>] [--accept-dump] [--accept-generated]
      [--no-build] [--no-bump] [--skip-selftest] [--copy-to <dir>]
      After the dump: import it into dumps/, check it, compare it with the previous dump, look at the origin
      world's block counts, check mapping/overrides, run the generator and report what is new, run the tests,
      bump mod_version, build the jar, run the self-test, note the version in the README (版本) and write
      work/update/<dump>/report.md with the manual steps that remain (deploy, restart, refresh).

`run` stops (exit code 2) where a person has to decide something and says what. Fix it and run the same command
again: the import and the version bump are not repeated (work/update/state.json remembers them).
It never deploys anything and never touches the mirror server or the origin.
"""
import argparse
import collections
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

CODE = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, os.path.join(CODE, 'ops', 'scan'))
sys.path.insert(0, os.path.join(CODE, 'mirror', 'generator'))
import diff_dumps  # noqa: E402
import scan_world  # noqa: E402
from nebgen import overrides as ovr  # noqa: E402
from nebgen.vanilla import Vanilla121  # noqa: E402

STEPS = 10
STAMP = re.compile(r'(\d{8}-\d{6})')
VERSION_TOKEN = re.compile(r'^(?:v|r|mc)?\d[\w.]*$', re.I)
README_ANCHOR = '。版本号在 `mirror/mod/gradle.properties`'  # in the README's 版本 paragraph

# Mirror code that depends on a mod's internals (docs/mod-updates.md, 检查依赖 mod 内部数据的渲染器).
RENDERER_CHECKS = {
    'railcraft': 'outfitted tracks: check trackKitVariants, errors.json and tracks.variants in report.md; new '
                 'kits or renamed NBT fields need a change in OutfittedTrackKey/OutfittedTrack',
    'chiselsandbits': 'the compact voxel format (ChiselsBlob): a new blob format version is not read',
    'architecturecraft': 'the shape table and the bundled .objson models: new shapes or models need porting',
    'chisel': 'CTM rules -> Continuity: compare the ctm.* counts; new CTM types are not converted',
    'ctm': 'CTM rules -> Continuity: compare the ctm.* counts; new CTM types are not converted',
    'moarsigns': 'sign textures come from data/mods.zip: make sure it holds the new MoarSigns jar',
    'nebulaecraft': 'roadmark_text renderer and fonts: the generator must read the new source resources',
}


class Stop(Exception):
    """A person has to act before the update can go on."""


class Env:
    """Where everything lives. `root` is the repository; tests point it at a temporary copy."""

    def __init__(self, root, nebulaecraft=None):
        self.root = os.path.abspath(root)
        self.dumps = os.path.join(self.root, 'dumps')
        self.full = os.path.join(self.dumps, 'full')
        self.work = os.path.join(self.root, 'work')
        self.update = os.path.join(self.work, 'update')
        self.state_file = os.path.join(self.update, 'state.json')
        self.baseline_file = os.path.join(self.update, 'mods-baseline.json')
        self.client_mods = os.path.join(self.update, 'client-mods')
        self.mods_zip = os.path.join(self.root, 'data', 'mods.zip')
        self.overrides = os.path.join(self.root, 'mapping', 'overrides')
        self.mc = os.path.join(self.work, 'mc', '1.21.1')
        self.gen = os.path.join(self.work, 'gen')
        self.stats = os.path.join(self.work, 'world-stats.json')
        self.mod = os.path.join(self.root, 'mirror', 'mod')
        self.props = os.path.join(self.mod, 'gradle.properties')
        self.readme = os.path.join(self.root, 'README.md')
        self.dumper = os.path.join(self.root, 'legacy', 'dumper')
        self.nebulaecraft = os.path.abspath(nebulaecraft or os.path.join(self.root, '..', 'NebulaeCraft'))


class Log:
    """Prints as it goes and keeps the same text for report.md."""

    def __init__(self):
        self.lines = []
        self.attention = []

    def step(self, n, title):
        print('\n[%d/%d] %s' % (n, STEPS, title), flush=True)
        self.lines += ['', '## %d. %s' % (n, title), '']

    def info(self, text):
        print('  ' + text, flush=True)
        self.lines.append('- ' + text)

    def warn(self, text):
        print('  ! ' + text, flush=True)
        self.lines.append('- **' + text + '**')
        self.attention.append(text)

    def items(self, title, values, warn=False, console=20, report=300):
        values = list(values)
        (self.warn if warn else self.info)('%s (%d)%s' % (title, len(values), ':' if values else ''))
        if warn and values:  # the summary at the top of the report names the first few
            self.attention[-1] += ' ' + ', '.join(values[:5]) + (', …' if len(values) > 5 else '')
        for v in values[:console]:
            print('      ' + v)
        if len(values) > console:
            print('      ... +%d more (see report.md)' % (len(values) - console))
        self.lines += ['  - `%s`' % v for v in values[:report]]
        if len(values) > report:
            self.lines.append('  - ... +%d more' % (len(values) - report))


def read_json(path):
    with open(path, encoding='utf8') as f:
        return json.load(f)


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf8') as f:
        json.dump(data, f, ensure_ascii=False, indent=1)


def read_text(path):
    with open(path, encoding='utf8') as f:
        return f.read()


# ---------------------------------------------------------------------------------------------- jars

def jar_stem(name):
    """'Future-MC-0.2.21.jar' -> 'future-mc': the file name without its version, to match two versions of a mod."""
    base = re.sub(r'\.jar$', '', os.path.basename(name), flags=re.I)
    kept = []
    for token in re.split(r'[-_+ ]', base):
        if VERSION_TOKEN.match(token):
            break
        kept.append(token.lower())
    return '-'.join(kept) or base.lower()


def zip_jars(mods_zip):
    """Jar file names in the origin's mods zip (any folder depth; '*.jar.bak' and the like are ignored)."""
    with zipfile.ZipFile(mods_zip) as z:
        return sorted({os.path.basename(n) for n in z.namelist() if n.lower().endswith('.jar')})


def loaded_jars(manifest):
    """Jar files the dump client loaded mods from, without Minecraft/Forge themselves."""
    out = set()
    for m in manifest.get('mods', []):
        src = m.get('source') or ''
        if src.lower().endswith('.jar') and src != 'minecraft.jar' and not src.startswith('forge-'):
            out.add(src)
    return sorted(out)


def mod_versions(manifest):
    return {m['id']: m.get('version') for m in manifest.get('mods', [])}


def compare_jars(loaded, origin, prev_loaded=()):
    """The dump client's jars against the origin's mods zip.
    mismatch: [(client jar, origin jar)] same mod, other file; missing: origin jars of mods the previous dump
    had loaded and this one has not; clientOnly: loaded jars the origin does not have at all (CTM, ...)."""
    origin_by_stem = {jar_stem(j): j for j in origin}
    special = ('nebulaecraft', 'mirrordumper')  # built from source, checked separately
    mismatch, client_only = [], []
    for jar in loaded:
        stem = jar_stem(jar)
        if stem in special or jar in origin:
            continue
        if stem in origin_by_stem:
            mismatch.append((jar, origin_by_stem[stem]))
        else:
            client_only.append(jar)
    now = {jar_stem(j) for j in loaded}
    before = {jar_stem(j) for j in prev_loaded}
    missing = [j for s, j in sorted(origin_by_stem.items()) if s in before and s not in now and s not in special]
    return {'mismatch': mismatch, 'missing': missing, 'clientOnly': client_only}


def nebulaecraft_version(env):
    """VERSION constant of the NebulaeCraft source (the authoritative NebulaeCraft), or None."""
    path = os.path.join(env.nebulaecraft, 'src', 'main', 'java', 'net', 'kuina', 'nebulaecraft', 'NebulaecraftMod.java')
    if not os.path.isfile(path):
        return None
    m = re.search(r'String\s+VERSION\s*=\s*"([^"]+)"', read_text(path))
    return m.group(1) if m else None


def dumper_version(env):
    path = os.path.join(env.dumper, 'build.gradle')
    if not os.path.isfile(path):
        return None
    m = re.search(r"^version\s*=\s*'([^']+)'", read_text(path), re.M)
    return m.group(1) if m else None


# ---------------------------------------------------------------------------------------------- prepare

def cmd_prepare(env, a):
    if not os.path.isfile(env.mods_zip):
        raise Stop('%s not found: put the origin\'s current mods zip there' % env.mods_zip)
    manifest_path = os.path.join(env.full, 'manifest.json')
    if not os.path.isfile(manifest_path):
        raise Stop('%s not found: there is no previous dump to compare with' % manifest_path)
    manifest = read_json(manifest_path)
    loaded, origin = loaded_jars(manifest), zip_jars(env.mods_zip)
    baseline = read_json(env.baseline_file) if os.path.isfile(env.baseline_file) else None
    loaded_stems = {jar_stem(j): j for j in loaded}
    origin_stems = {jar_stem(j): j for j in origin}
    special = ('nebulaecraft', 'mirrordumper')

    # Coremods are listed in the dump with source "minecraft.jar", so their jar is matched by mod id instead.
    core_ids = [m['id'].lower() for m in manifest.get('mods', [])
                if m.get('source') == 'minecraft.jar' and m['id'] not in ('minecraft', 'mcp')]
    coremods = [j for s, j in sorted(origin_stems.items())
                if s not in loaded_stems and any(s.replace('-', '').startswith(i) for i in core_ids)]

    replace = [(loaded_stems[s], j) for s, j in sorted(origin_stems.items())
               if s in loaded_stems and loaded_stems[s] != j and s not in special]
    if baseline is not None:
        replace += [('(coremod, earlier file)', j) for j in coremods if j not in baseline]
    unloaded = [j for s, j in sorted(origin_stems.items())
                if s not in loaded_stems and s not in special and j not in coremods]
    gone = [j for s, j in sorted(loaded_stems.items()) if s not in origin_stems and s not in special]
    if baseline is not None:
        base_stems = {jar_stem(j) for j in baseline}
        add = [j for j in unloaded if jar_stem(j) not in base_stems]
        remove = [j for j in gone if jar_stem(j) in base_stems]
        keep = [j for j in gone if jar_stem(j) not in base_stems]
    else:
        add, remove, keep = [], [], gone

    lines = ['Dump client changes, from %s against the last dump (%s, %s)'
             % (os.path.relpath(env.mods_zip, env.root), os.path.basename(os.path.realpath(env.full)),
                manifest.get('timestamp')), '']
    lines.append('Replace (%d):' % len(replace))
    lines += ['  %s  ->  %s' % r for r in replace]
    if baseline is not None:
        lines.append('Add, if the client needs it (new at the origin) (%d):' % len(add))
        lines += ['  ' + j for j in add]
        lines.append('Remove (gone from the origin) (%d):' % len(remove))
        lines += ['  ' + j for j in remove]
    else:
        lines.append('In the zip but not loaded by the last dump (%d): server-only so far; add any that is a new '
                     'content mod' % len(unloaded))
        lines += ['  ' + j for j in unloaded]
    lines.append('Client-only, keep (%d):' % len(keep))
    lines += ['  ' + j for j in keep]
    if coremods and baseline is None:
        lines.append('Coremods (%d): the dump does not record their file name; replace any the origin updated'
                     % len(coremods))
        lines += ['  ' + j for j in coremods]

    copies = []  # (description, source path or zip member name, target file name)
    neb_src, neb_dumped = nebulaecraft_version(env), mod_versions(manifest).get('nebulaecraft')
    neb_zip = origin_stems.get('nebulaecraft')
    lines += ['', 'NebulaeCraft: use the source build, version %s (last dump: %s; the zip has %s)'
              % (neb_src, neb_dumped, neb_zip)]
    if neb_src and neb_src != neb_dumped:
        jar = os.path.join(env.nebulaecraft, 'build', 'libs', 'NebulaeCraft-1.12.2-%s.jar' % neb_src)
        if os.path.isfile(jar):
            copies.append(('NebulaeCraft source build', jar, os.path.basename(jar)))
            lines.append('  replace it with %s' % os.path.basename(jar))
        else:
            lines.append('  build %s first (not found)' % jar)
    dv = dumper_version(env)
    lines.append('MirrorDumper: current version %s (last dump: %s)' % (dv, manifest.get('dumper')))
    if dv and dv != manifest.get('dumper'):
        jar = os.path.join(env.dumper, 'build', 'libs', 'MirrorDumper-1.12.2-%s.jar' % dv)
        if os.path.isfile(jar):
            copies.append(('MirrorDumper', jar, os.path.basename(jar)))
            lines.append('  replace the client\'s MirrorDumper jar with %s' % os.path.basename(jar))
        else:
            lines.append('  build it first: cd legacy/dumper && JAVA_HOME=$(/usr/libexec/java_home -v 1.8) '
                         './gradlew build')

    from_zip = [j for _, j in replace] + add
    if from_zip or copies:
        if os.path.isdir(env.client_mods):
            if os.listdir(env.client_mods) and not os.path.isfile(os.path.join(env.client_mods, 'CHANGES.txt')):
                raise Stop('%s exists and was not made by this tool: move it away' % env.client_mods)
            shutil.rmtree(env.client_mods)
        os.makedirs(env.client_mods)
        with zipfile.ZipFile(env.mods_zip) as z:
            members = {os.path.basename(n): n for n in z.namelist() if n.lower().endswith('.jar')}
            for j in from_zip:
                with z.open(members[j]) as src, open(os.path.join(env.client_mods, j), 'wb') as dst:
                    shutil.copyfileobj(src, dst)
        for _, src, name in copies:
            shutil.copyfile(src, os.path.join(env.client_mods, name))
        lines += ['', '%d jar(s) to copy to the dump client are in %s'
                  % (len(from_zip) + len(copies), os.path.relpath(env.client_mods, env.root))]
        with open(os.path.join(env.client_mods, 'CHANGES.txt'), 'w', encoding='utf8') as f:
            f.write('\n'.join(lines) + '\n')
    elif not remove:
        lines += ['', 'No jar changes since the last dump.']
    lines += ['', 'Then run a full dump (docs/dumper.md) and: python3 -I ops/update/update.py run <dump zip>']
    print('\n'.join(lines))
    return 0


# ---------------------------------------------------------------------------------------------- import

def dump_name(src, manifest):
    """dumps/ directory name of a dump: 'full-<yyyymmdd-hhmmss>' from the file name, else from the manifest."""
    m = STAMP.search(os.path.basename(os.path.normpath(src)))
    if m:
        return 'full-' + m.group(1)
    digits = re.sub(r'\D', '', manifest.get('timestamp') or '')
    if len(digits) < 14:
        raise Stop('cannot name the dump: no timestamp in its file name or manifest.json')
    return 'full-%s-%s' % (digits[:8], digits[8:14])


def _same_dump(dest, manifest):
    existing = read_json(os.path.join(dest, 'manifest.json'))
    if existing.get('timestamp') != manifest.get('timestamp'):
        raise Stop('%s exists and holds a different dump (%s, not %s): rename one of them'
                   % (dest, existing.get('timestamp'), manifest.get('timestamp')))


def import_dump(env, src):
    """Bring a dump (zip or directory) into dumps/. Returns (directory, True if it was imported now)."""
    src = os.path.abspath(src)
    if os.path.isdir(src):
        if not os.path.isfile(os.path.join(src, 'manifest.json')):
            raise Stop('%s has no manifest.json: not a MirrorDumper dump' % src)
        if os.path.realpath(os.path.dirname(os.path.realpath(src))) == os.path.realpath(env.dumps):
            return os.path.realpath(src), False
        manifest = read_json(os.path.join(src, 'manifest.json'))
        dest = os.path.join(env.dumps, dump_name(src, manifest))
        if os.path.isfile(os.path.join(dest, 'manifest.json')):
            _same_dump(dest, manifest)
            return dest, False
        tmp = dest + '.importing'
        shutil.rmtree(tmp, ignore_errors=True)
        shutil.copytree(src, tmp)
        os.rename(tmp, dest)
        return dest, True
    if not zipfile.is_zipfile(src):
        raise Stop('%s is neither a dump directory nor a zip' % src)
    with zipfile.ZipFile(src) as z:
        manifests = sorted((n for n in z.namelist() if n == 'manifest.json' or n.endswith('/manifest.json')),
                           key=lambda n: (n.count('/'), len(n)))
        if not manifests:
            raise Stop('%s has no manifest.json: not a MirrorDumper dump' % src)
        prefix = manifests[0][:-len('manifest.json')]
        manifest = json.loads(z.read(manifests[0]).decode('utf8'))
        dest = os.path.join(env.dumps, dump_name(src, manifest))
        if os.path.isfile(os.path.join(dest, 'manifest.json')):
            _same_dump(dest, manifest)
            return dest, False
        os.makedirs(env.dumps, exist_ok=True)
        tmp = dest + '.importing'
        shutil.rmtree(tmp, ignore_errors=True)
        base = os.path.realpath(tmp)
        try:
            for info in z.infolist():
                name = info.filename
                if name.endswith('/') or not name.startswith(prefix) or name.startswith('__MACOSX/'):
                    continue
                target = os.path.realpath(os.path.join(base, name[len(prefix):]))
                if not target.startswith(base + os.sep):
                    raise Stop('unsafe path in %s: %r' % (src, name))
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with z.open(info) as fin, open(target, 'wb') as fout:
                    shutil.copyfileobj(fin, fout)
        except BaseException:
            shutil.rmtree(tmp, ignore_errors=True)
            raise
        os.rename(tmp, dest)
    return dest, True


def repoint_full(env, name):
    """Make dumps/full a symlink to dumps/<name>. Returns True when it changed."""
    if os.path.lexists(env.full) and not os.path.islink(env.full):
        raise Stop('%s is not a symlink: move it away' % env.full)
    if os.path.islink(env.full) and os.readlink(env.full) == name:
        return False
    tmp = env.full + '.new'
    if os.path.lexists(tmp):
        os.remove(tmp)
    os.symlink(name, tmp)
    os.replace(tmp, env.full)
    return True


# ---------------------------------------------------------------------------------------------- checks

def error_key(e):
    return '%s [%s] %s' % (e.get('block'), e.get('stage'), e.get('error'))


def validate_dump(env, new_dir, old_dir, log):
    """Returns the problems that must stop the update (empty when the dump is usable)."""
    manifest, old_manifest = read_json(os.path.join(new_dir, 'manifest.json')), \
        read_json(os.path.join(old_dir, 'manifest.json'))
    summary = manifest.get('summary', {})
    log.info('MirrorDumper %s, %s: %s blocks, %s states, %s models, %s errors'
             % (manifest.get('dumper'), manifest.get('timestamp'), summary.get('blocks'), summary.get('states'),
                summary.get('models'), summary.get('errors')))
    problems = []
    if manifest.get('schema') != 1:
        problems.append('dump schema %r, this tool and the generator read schema 1' % manifest.get('schema'))
    if manifest.get('options', {}).get('mods'):
        problems.append('partial dump (mods=%s): a full dump is needed' % ','.join(manifest['options']['mods']))
    if summary.get('truncatedBlocks'):
        problems.append('%s blocks were truncated by maxStates: raise -Dmirrordump.maxStates'
                        % summary['truncatedBlocks'])
    versions = mod_versions(manifest)
    if 'railcraft' in versions and not summary.get('trackKitVariants'):
        problems.append('Railcraft is loaded but trackKitVariants is 0: outfitted tracks were not dumped '
                        '(see errors.json, stage trackKits)')
    dv = dumper_version(env)
    if dv and manifest.get('dumper') != dv:
        log.warn('the dump was made with MirrorDumper %s, the current one is %s' % (manifest.get('dumper'), dv))

    def errors(d):
        path = os.path.join(d, 'errors.json')
        return read_json(path) if os.path.isfile(path) else []
    known = {error_key(e) for e in errors(old_dir)}
    fresh = sorted({error_key(e) for e in errors(new_dir)} - known)
    if fresh:
        log.items('errors.json entries the previous dump did not have; after a mod update they usually mean the '
                  'dumper\'s reflection no longer fits', fresh, warn=True)
    else:
        log.info('errors.json: nothing new')

    neb = nebulaecraft_version(env)
    if neb and versions.get('nebulaecraft') and versions['nebulaecraft'] != neb:
        problems.append('the dump has NebulaeCraft %s, the source (%s) is %s: dump with the source build'
                        % (versions['nebulaecraft'], env.nebulaecraft, neb))
    if os.path.isfile(env.mods_zip):
        cmp = compare_jars(loaded_jars(manifest), zip_jars(env.mods_zip), loaded_jars(old_manifest))
        for client, origin in cmp['mismatch']:
            problems.append('the dump client has %s, the origin (data/mods.zip) has %s' % (client, origin))
        for jar in cmp['missing']:
            problems.append('%s was loaded for the previous dump but not for this one' % jar)
        if not cmp['mismatch'] and not cmp['missing']:
            log.info('mod jars match data/mods.zip')
        if cmp['clientOnly']:
            log.info('client-only jars (not in data/mods.zip): ' + ', '.join(cmp['clientOnly']))
    else:
        log.warn('%s not found: the dump client\'s jars were not compared with the origin\'s' % env.mods_zip)
    return problems


def world_counts(stats, names):
    """How many blocks of each name the scanned origin world holds (all metas)."""
    names, out = set(names), collections.Counter()
    for key, count in stats.get('states', {}).items():
        name = key.rsplit(':', 1)[0]
        if name in names:
            out[name] += count
    return out


def world_impact(env, a, new_dir, old_dir, d, log):
    """Returns the blocks whose chunks need a refresh: [(reason, name, count)]."""
    if a.world:
        log.info('scanning %s (about 5 minutes for the full world)' % a.world)
        stats = scan_world.scan(a.world, a.workers, 0)
        write_json(env.stats, stats)
        path = env.stats
    else:
        path = a.stats or env.stats
        if not os.path.isfile(path):
            log.warn('no world stats (%s): give --world <origin world copy> or --stats to learn whether placed '
                     'blocks are affected' % path)
            return None
        stats = read_json(path)
    log.info('origin world stats: %s, scanned %s (%s chunks); use --world for a fresh copy'
             % (stats.get('world'), time.strftime('%Y-%m-%d %H:%M', time.localtime(os.path.getmtime(path))),
                stats.get('chunks')))
    counts = world_counts(stats, d['added'] + d['removed'] + d['changed'])
    refresh = []
    for kind, reason in (('added', 'new block already placed at the origin: an unknown placeholder until refreshed'),
                         ('removed', 'removed block still in the origin world: refresh after deploying'),
                         ('changed', 'mapping changed: converted chunks are wrong until refreshed')):
        placed = [(n, counts[n]) for n in d[kind] if counts[n]]
        refresh += [(reason, n, c) for n, c in placed]
        if d[kind]:
            log.info('%s: %d of %d blocks are in the origin world' % (kind, len(placed), len(d[kind])))
    if refresh:
        log.items('blocks whose chunks need `sync refresh`', ['%s x%d (%s)' % (n, c, r) for r, n, c in refresh],
                  warn=True)
    else:
        log.info('no world work needed for these stats')
    before = scan_world.coverage(stats, old_dir)
    after = scan_world.coverage(stats, new_dir)
    expected = set(before['blocksMissingFromDump']) | set(d['removed'])
    surprise = sorted(set(after['blocksMissingFromDump']) - expected)
    if surprise:
        log.items('blocks the world uses that the new dump lacks (dump client without a mod, or a wrong version)',
                  ['%s x%d' % (n, after['blocksMissingFromDump'][n]) for n in surprise], warn=True)
    known = sorted(set(after['blocksMissingFromDump']) - set(surprise))
    if known:
        log.info('missing from the dump as before or removed now (become unknown placeholders): '
                 + ', '.join('%s x%d' % (n, after['blocksMissingFromDump'][n]) for n in known))
    bad = sorted(set(after['metaInvalid']) - set(before['metaInvalid']))
    if bad:
        log.items('(name:meta) in the world that the new dump cannot decode', bad, warn=True)
    return refresh


def draft_override(name, block, vanilla):
    """A starting rule for a new block that looks like a 1.21 vanilla block: (rule or None, notes)."""
    target = 'minecraft:' + name.split(':', 1)[1]
    if not vanilla.has_block(target):
        return None, ['no 1.21.1 block is named %s: find the equivalent by hand, or leave the block generated'
                      % target]
    rule, notes, props = {'target': target}, [], {}
    allowed = vanilla.properties(target)
    stored = block.get('storedProperties')
    for p in block.get('properties', []):
        values = [str(v) for v in p.get('values', [])]
        if len(values) <= 1 or (stored is not None and p['name'] not in stored):
            continue  # single-valued properties carry nothing; derived ones are recomputed in 1.21
        if p['name'] in allowed and set(values) <= set(allowed[p['name']]):
            props[p['name']] = p['name']
        else:
            notes.append('property %s=%s has no direct match in %s (%s): write props/values/metas by hand'
                         % (p['name'], '|'.join(values), target,
                            ', '.join('%s=%s' % (k, '|'.join(v)) for k, v in sorted(allowed.items())) or 'no properties'))
    if props:
        rule['props'] = props
    rest = sorted(set(allowed) - set(props.values()))
    if rest:
        notes.append('%s also has %s: its default is used unless "state" sets it' % (target, ', '.join(rest)))
    return rule, notes


def check_overrides(env, new_dir, old_dir, d, log, run_dir):
    """Returns the added blocks that may need a hand-written mapping onto a vanilla block."""
    rules = ovr.load(env.overrides) if os.path.isdir(env.overrides) else {}
    index = {b['name']: b for b in read_json(os.path.join(new_dir, 'blocks', 'index.json'))}
    old_index = {b['name'] for b in read_json(os.path.join(old_dir, 'blocks', 'index.json'))}
    stale = sorted(n for n in rules if n not in index)
    if stale:
        log.items('overrides for blocks that are not in the dump: delete or rename them in mapping/overrides/',
                  ['%s%s' % (n, ' (removed by this update)' if n in old_index else '') for n in stale], warn=True)
    vanilla = Vanilla121(env.mc)
    namespaces = {n.split(':')[0] for n in rules}
    drafts, notes, candidates = {}, {}, []
    for name in d['added']:
        if name in rules:
            continue
        ns, path = name.split(':', 1)
        if ns not in namespaces and not vanilla.has_block('minecraft:' + path):
            continue
        candidates.append(name)
        rule, why = draft_override(name, read_json(os.path.join(new_dir, index[name]['file'])), vanilla)
        if rule:
            drafts[name] = rule
        if why:
            notes[name] = why
    path = os.path.join(run_dir, 'override-suggestions.json')
    if candidates:
        write_json(path, {'comment': 'Drafts only: copy what is right into mapping/overrides/<mod>.json; the '
                                     'generator validates every target property and value.',
                          'blocks': drafts, 'notes': notes})
        log.items('new blocks that may be 1.21 vanilla blocks and have no override (drafts in %s)'
                  % os.path.relpath(path, env.root),
                  ['%s -> %s' % (n, drafts[n]['target'] if n in drafts else '?') for n in candidates], warn=True)
    else:
        if os.path.isfile(path):
            os.remove(path)
        log.info('no new block needs an override decision (%d rules, %d new blocks)' % (len(rules), len(d['added'])))
    return candidates


# ---------------------------------------------------------------------------------------------- generator

def diff_reports(old, new):
    """What the new generator report has that the old one did not."""
    oc, nc = old.get('counts', {}), new.get('counts', {})
    counts = [(k, oc.get(k, 0), nc.get(k, 0)) for k in sorted(set(oc) | set(nc))
              if k != 'seconds' and oc.get(k, 0) != nc.get(k, 0)]
    warnings = {}
    for msg, blocks in new.get('warnings', {}).items():
        fresh = sorted(set(blocks) - set(old.get('warnings', {}).get(msg, [])))
        if fresh:
            warnings[msg] = fresh
    items = {}
    for key, values in new.get('items', {}).items():
        fresh = [v for v in values if v not in set(old.get('items', {}).get(key, []))]
        if fresh:
            items[key] = fresh
    return {'counts': counts, 'warnings': warnings, 'items': items}


def run_generator(env, new_dir, old_dir, d, log, run_dir):
    before_path = os.path.join(run_dir, 'report-before.json')
    current = os.path.join(env.gen, 'report.json')
    source = os.path.join(env.gen, 'mirror', 'source.json')
    if not os.path.isfile(before_path) and os.path.isfile(current) and os.path.isfile(source):
        made_from = read_json(source).get('dump', {}).get('timestamp')
        if made_from == read_json(os.path.join(new_dir, 'manifest.json')).get('timestamp'):
            log.info('work/gen already comes from this dump: no earlier report to compare with')
        else:
            shutil.copyfile(current, before_path)
            if made_from != read_json(os.path.join(old_dir, 'manifest.json')).get('timestamp'):
                log.warn('the earlier generator output came from another dump (%s) than the previous one: the '
                         'comparison below is against that' % made_from)
    cmd = [sys.executable, '-I', os.path.join(CODE, 'mirror', 'generator', 'generate.py'), '--dump', new_dir,
           '--mc', env.mc, '--overrides', env.overrides, '--out', env.gen, '--mods', env.mods_zip,
           '--nebulaecraft', os.path.join(env.nebulaecraft, 'src', 'main', 'resources')]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for line in proc.stdout.splitlines():
        print('    ' + line)
    report = read_json(current) if os.path.isfile(current) else {}
    if proc.returncode != 0:
        for e in report.get('errors', [])[:50]:
            log.warn('generator error: ' + e)
        raise Stop('the generator failed (exit %d); see %s' % (proc.returncode, os.path.join(env.gen, 'report.md')))
    c = report.get('counts', {})
    log.info('generated %s blocks, %s states, %s meshes; %s mapped by override; 0 errors'
             % (c.get('blocks.generated'), c.get('states.generated'), c.get('meshes'),
                c.get('blocks.mappedByOverride')))
    added = set(d['added'])
    te = sorted(added & set(report.get('items', {}).get('legacyBlockEntityBlocks', [])))
    if te:
        log.items('new blocks with a TileEntity: they show only their static model unless a renderer exists '
                  '(docs/legacy-renderers.md)', te, warn=True)
    if not os.path.isfile(before_path):
        return report
    delta = diff_reports(read_json(before_path), report)
    for msg, blocks in sorted(delta['warnings'].items()):
        log.items('new warning: ' + msg, blocks, warn=bool(added & set(blocks)) or 'not in the dump' in msg)
    for key, values in sorted(delta['items'].items()):
        if key != 'legacyBlockEntityBlocks':
            log.items('new in %s' % key, values, warn=key in ('texturesMissing', 'reverseAmbiguous'))
    if delta['counts']:
        log.items('counts that changed', ['%s: %s -> %s' % c for c in delta['counts']])
    if not any(delta.values()):
        log.info('report unchanged against the earlier run')
    return report


def renderer_hints(d, log):
    changed = {m: (old, new) for m, old, new in d['mods']}
    any_hint = False
    for mod, text in RENDERER_CHECKS.items():
        if mod in changed:
            log.warn('%s %s -> %s: %s' % (mod, changed[mod][0], changed[mod][1], text))
            any_hint = True
    for mod, (old, new) in sorted(changed.items()):
        if old is None and mod != 'mirrordumper':
            log.warn('new mod %s %s: check whether its blocks need a TileEntity renderer' % (mod, new))
            any_hint = True
    if not any_hint:
        log.info('no mod with mirror-side renderer code changed version')


# ---------------------------------------------------------------------------------------------- build

def gradle_props(text):
    return dict(line.split('=', 1) for line in text.splitlines() if '=' in line and not line.startswith('#'))


def bump_version(text):
    """Add one to the minor number of mod_version. Returns (new text, old version, new version)."""
    m = re.search(r'^mod_version=(\d+)\.(\d+)[ \t]*$', text, re.M)
    if not m:
        raise Stop('mod_version=<major>.<minor> not found in gradle.properties')
    old = '%s.%s' % m.groups()
    new = '%s.%d' % (m.group(1), int(m.group(2)) + 1)
    return text[:m.start()] + 'mod_version=' + new + text[m.end():], old, new


def update_summary(d):
    """Short text for the README (which is in Chinese): which origin mods changed."""
    mods = ['%s %s → %s' % (m, old, new) for m, old, new in d['mods']
            if m != 'mirrordumper' and old is not None and new is not None]
    mods += ['%s %s 新增' % (m, new) for m, old, new in d['mods'] if old is None and m != 'mirrordumper']
    mods += ['%s 移除' % m for m, old, new in d['mods'] if new is None and m != 'mirrordumper']
    text = '、'.join(mods[:4]) + (' 等' if len(mods) > 4 else '')
    blocks = '、'.join('%s %d 个方块' % (label, len(d[k]))
                      for k, label in (('added', '新增'), ('removed', '移除'), ('changed', '变更')) if d[k])
    return '原服 mod 更新' + ('：' + '；'.join(x for x in (text, blocks) if x) if text or blocks else '')


def update_readme(text, version, summary):
    """Note a mirror mod version in the README's 版本 paragraph. None when the anchor is gone."""
    if '镜像 mod %s（' % version in text:
        return text
    if README_ANCHOR not in text:
        return None
    return text.replace(README_ANCHOR, '；镜像 mod %s（%s）%s' % (version, summary, README_ANCHOR), 1)


def gradle(env, *tasks):
    cmd = ['./gradlew'] + list(tasks) + ['-PgenDir=' + env.gen]
    print('    $ ' + ' '.join(cmd), flush=True)
    if subprocess.run(cmd, cwd=env.mod).returncode != 0:
        raise Stop('`%s` failed in %s' % (' '.join(cmd), env.mod))


def build(env, a, state, log):
    """Tests, version bump, jar, self-test. Returns (version, jar path)."""
    tests = [sys.executable, '-I', '-m', 'unittest', 'discover', '-s', os.path.join(CODE, 'mirror', 'generator', 'tests')]
    proc = subprocess.run(tests, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if proc.returncode != 0:
        print(proc.stdout)
        raise Stop('the generator tests failed')
    ran = re.search(r'Ran \d+ tests?', proc.stdout)
    log.info('generator tests pass%s' % (' (%s)' % ran.group(0) if ran else ''))
    text = read_text(env.props)
    version = gradle_props(text).get('mod_version')
    if a.no_bump:
        log.info('mod_version stays %s (--no-bump)' % version)
    elif state.get('bumpedTo') == version:
        log.info('mod_version is already %s for this update' % version)
    else:
        text, old, version = bump_version(text)
        with open(env.props, 'w', encoding='utf8') as f:
            f.write(text)
        state['bumpedTo'] = version
        save_state(env, state)
        log.info('mod_version %s -> %s' % (old, version))
    props = gradle_props(text)
    jar = os.path.join(env.mod, 'build', 'libs', '%s-%s-%s.jar'
                       % (props.get('archives_base_name'), props.get('minecraft_version'), version))
    gradle(env, 'build')
    if not os.path.isfile(jar):
        raise Stop('the build passed but %s is not there' % jar)
    log.info('built %s' % os.path.relpath(jar, env.root))
    if a.skip_selftest:
        log.warn('self-test skipped (--skip-selftest): run ./gradlew runSelftestClient before deploying')
        return version, jar
    result = os.path.join(env.mod, 'run', 'selftest', 'nebulaemirror-selftest.json')
    started = time.time()
    gradle(env, 'runSelftestClient')
    if not os.path.isfile(result) or os.path.getmtime(result) < started - 1:
        raise Stop('the self-test did not write %s' % result)
    st = read_json(result)
    log.info('self-test: %s blocks, %s states (%s with a mesh), %s items (%s with a mesh)'
             % (st.get('blocks'), st.get('states'), st.get('statesWithMesh'), st.get('items'),
                st.get('itemsWithMesh')))
    if st.get('missingStates') or st.get('missingItems'):
        log.items('states without a mesh model', [str(x) for x in st.get('missingStates', [])], warn=True)
        log.items('items without a mesh model', [str(x) for x in st.get('missingItems', [])], warn=True)
        raise Stop('the self-test found blocks or items without a model')
    return version, jar


# ---------------------------------------------------------------------------------------------- run

def load_state(env):
    return read_json(env.state_file) if os.path.isfile(env.state_file) else {}


def save_state(env, state):
    write_json(env.state_file, state)


def write_report(env, log, run_dir, name, stopped):
    head = ['# Mirror update for dump %s' % name, '',
            'Written %s by ops/update/update.py.' % time.strftime('%Y-%m-%d %H:%M'), '']
    if stopped:
        head += ['**Stopped: %s**' % stopped, '', 'Fix it and run the same command again.', '']
    if log.attention:
        head += ['## Needs attention', ''] + ['- ' + t for t in log.attention]
    path = os.path.join(run_dir, 'report.md')
    os.makedirs(run_dir, exist_ok=True)
    with open(path, 'w', encoding='utf8') as f:
        f.write('\n'.join(head + log.lines) + '\n')
    return path


def cmd_run(env, a):
    log = Log()
    run_dir = name = None
    stopped = None
    try:
        log.step(1, 'Import the dump')
        new_dir, imported = import_dump(env, a.dump)
        name = os.path.basename(new_dir)
        run_dir = os.path.join(env.update, name)
        os.makedirs(run_dir, exist_ok=True)
        log.info('%s dumps/%s' % ('imported into' if imported else 'already in', name))
        state = load_state(env)
        if state.get('dump') != name:
            state = {'dump': name}
        if a.old:
            state['previous'] = os.path.realpath(a.old)
        elif 'previous' not in state:
            if not os.path.isdir(env.full):
                raise Stop('there is no dumps/full to compare with: pass --old <previous dump dir>')
            state['previous'] = os.path.realpath(env.full)
        old_dir = state['previous']
        if old_dir == os.path.realpath(new_dir):
            raise Stop('dumps/full already points to this dump: pass --old <previous dump dir>')
        if not os.path.isfile(os.path.join(old_dir, 'manifest.json')):
            raise Stop('%s is not a dump' % old_dir)
        save_state(env, state)
        log.info('previous dump: %s' % os.path.basename(old_dir))

        log.step(2, 'Check the dump')
        problems = validate_dump(env, new_dir, old_dir, log)
        for p in problems:
            log.warn(p)
        if problems and not a.accept_dump:
            raise Stop('the dump has %d problem(s); dump again, or pass --accept-dump if they are fine'
                       % len(problems))
        if repoint_full(env, name):
            log.info('dumps/full -> %s' % name)

        log.step(3, 'Compare with the previous dump')
        d = diff_dumps.diff(old_dir, new_dir)
        log.items('mods whose version changed', ['%s: %s -> %s' % m for m in d['mods']])
        log.items('added blocks', d['added'])
        log.items('removed blocks', d['removed'])
        log.items('CHANGED blocks (properties or meta table: the same stored (name, meta) now means something '
                  'else)', d['changed'], warn=bool(d['changed']))
        log.items('blocks that only look different', d['appearance'])

        log.step(4, 'Origin world')
        refresh = world_impact(env, a, new_dir, old_dir, d, log)

        log.step(5, 'Overrides')
        if not os.path.isfile(os.path.join(env.mc, 'generated', 'reports', 'blocks.json')):
            raise Stop('no 1.21.1 reference data in %s: run ops/mc/fetch_121.sh once' % env.mc)
        candidates = check_overrides(env, new_dir, old_dir, d, log, run_dir)
        if candidates and not a.accept_generated:
            raise Stop('%d new block(s) may need an override; this has to be decided before the first deploy. '
                       'Add rules to mapping/overrides/, or pass --accept-generated to ship the rest as '
                       'ordinary mod blocks' % len(candidates))

        log.step(6, 'Generate')
        run_generator(env, new_dir, old_dir, d, log, run_dir)

        log.step(7, 'Mod-specific renderer code')
        renderer_hints(d, log)

        jar = version = None
        log.step(8, 'Test and build')
        if a.no_build:
            log.info('skipped (--no-build)')
        else:
            version, jar = build(env, a, state, log)

        log.step(9, 'README')
        if a.no_build:
            log.info('skipped (--no-build)')
        else:
            before = read_text(env.readme)
            text = update_readme(before, version, update_summary(d))
            if text == before:
                log.info('README.md already notes 镜像 mod %s' % version)
            elif text is None:
                log.warn('the 版本 paragraph of README.md was not recognised: note 镜像 mod %s by hand' % version)
            else:
                with open(env.readme, 'w', encoding='utf8') as f:
                    f.write(text)
                log.info('README.md 版本: 镜像 mod %s（%s）' % (version, update_summary(d)))

        log.step(10, 'What is left to do by hand')
        if jar:
            shutil.copyfile(jar, os.path.join(run_dir, os.path.basename(jar)))
            if a.copy_to:
                os.makedirs(a.copy_to, exist_ok=True)
                shutil.copyfile(jar, os.path.join(a.copy_to, os.path.basename(jar)))
                log.info('jar copied to %s' % a.copy_to)
            if os.path.isfile(env.mods_zip):
                write_json(env.baseline_file, zip_jars(env.mods_zip))
            log.info('give %s to the mirror server (Windows) and to every client: old jars cannot join. '
                     'Restart the mirror server' % os.path.relpath(os.path.join(run_dir, os.path.basename(jar)), env.root))
        else:
            log.info('run again without --no-build to bump the version and build the jar')
        if refresh:
            log.info('world: `/nm sync now` before the restart, then `/nm sync refresh <cx1> <cz1> <cx2> <cz2>` '
                     'over the areas that hold the %d block(s) listed in step 4 (this undoes mirror edits there, '
                     'except in checked-out zones)' % len(refresh))
        elif refresh is None:
            log.info('world: unknown (no stats); see docs/mod-updates.md, 更新世界')
        else:
            log.info('world: nothing to refresh')
        log.info('check: `/nm sync status`, `/nm stats <chunk range>`, place the new blocks and write one back '
                 'from a small zone')
        if d['added'] or d['removed'] or d['changed']:
            log.info('commit the changes (mapping/overrides, gradle.properties, README.md)')
    except Stop as s:
        stopped = str(s)
    if run_dir:
        report = write_report(env, log, run_dir, name, stopped)
        print('\nreport: %s' % os.path.relpath(report, env.root))
    if stopped:
        print('\nSTOPPED: %s\nFix it and run the same command again.' % stopped)
        return 2
    if log.attention:
        print('\n%d point(s) need attention (marked "!" above, listed at the top of the report).' % len(log.attention))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--root', default=CODE, help='project data root (default: this repository; used by the tests)')
    ap.add_argument('--nebulaecraft', help='NebulaeCraft source checkout (default: ../NebulaeCraft)')
    sub = ap.add_subparsers(dest='command', required=True)
    sub.add_parser('prepare', help='list the jar changes for the dump client')
    run = sub.add_parser('run', help='everything after the dump')
    run.add_argument('dump', help='the new dump: a zip from the dump client, or a directory')
    run.add_argument('--old', help='previous dump directory (default: what dumps/full points to)')
    run.add_argument('--stats', help='world stats JSON from ops/scan/scan_world.py (default: work/world-stats.json)')
    run.add_argument('--world', help='fresh copy of the origin world (directory or zip): scan it first')
    run.add_argument('--workers', type=int, default=os.cpu_count(), help='processes for --world')
    run.add_argument('--accept-dump', action='store_true', help='go on although the dump checks found problems')
    run.add_argument('--accept-generated', action='store_true',
                     help='ship new blocks that might be vanilla blocks as ordinary mod blocks')
    run.add_argument('--no-build', action='store_true', help='stop after the reports: no version bump, no jar')
    run.add_argument('--no-bump', action='store_true', help='build without changing mod_version')
    run.add_argument('--skip-selftest', action='store_true', help='do not run ./gradlew runSelftestClient')
    run.add_argument('--copy-to', help='also copy the built jar into this directory (e.g. a share)')
    a = ap.parse_args(argv)
    env = Env(a.root, a.nebulaecraft)
    try:
        return cmd_prepare(env, a) if a.command == 'prepare' else cmd_run(env, a)
    except Stop as s:
        print('STOPPED: %s' % s)
        return 2


if __name__ == '__main__':
    sys.exit(main())
