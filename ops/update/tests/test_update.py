"""python3 -I -m unittest discover -s ops/update/tests"""
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import update  # noqa: E402

README = ('## 版本\n\n此后：镜像 mod 1.7（签出区域的边界高亮）。版本号在 `mirror/mod/gradle.properties`'
          '（`mod_version`）中设置。\n')


def block(props, metas, look='aa'):
    """A dump block file: props {name: [values]}, metas [state dict or None per meta]."""
    table = [{'meta': i, 'state': s, 'roundTrip': i} if s is not None else {'meta': i, 'invalid': True}
             for i, s in enumerate(metas + [None] * (16 - len(metas)))]
    return {'properties': [{'name': k, 'type': 'enum', 'values': v} for k, v in props.items()],
            'storedProperties': list(props), 'metaTable': table, 'modelsDumped': True,
            'states': [{'props': s, 'model': {'layers': {'SOLID': look}}} for s in metas if s is not None]}


def dump_files(blocks, mods, stamp='2026-11-07T10:00:00+0800', errors=(), partial=(), tracks=5):
    """{relative path: text} of a small dump."""
    files = {'manifest.json': json.dumps({
        'schema': 1, 'dumper': '1.3', 'timestamp': stamp, 'options': {'mods': list(partial)},
        'mods': [{'id': i, 'version': v, 'source': s} for i, (v, s) in mods.items()],
        'summary': {'blocks': len(blocks), 'states': 1, 'models': 1, 'errors': len(errors),
                    'truncatedBlocks': 0, 'trackKitVariants': tracks}}),
        'errors.json': json.dumps(list(errors)),
        'blocks/index.json': json.dumps([{'name': n, 'file': 'blocks/%s.json' % n.replace(':', '/')}
                                         for n in blocks])}
    for name, b in blocks.items():
        files['blocks/%s.json' % name.replace(':', '/')] = json.dumps(b)
    return files


def write_tree(root, files):
    for rel, text in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w', encoding='utf8') as f:
            f.write(text)


def write_zip(path, files, prefix=''):
    with zipfile.ZipFile(path, 'w') as z:
        for rel, text in files.items():
            z.writestr(prefix + rel, text)


MODS_OLD = {'futuremc': ('0.2.6', 'Future-MC-0.2.21.jar'), 'railcraft': ('12.1.1', 'railcraft-12.1.1.jar'),
            'ctm': ('1.0', 'CTM-MC1.12.2-1.0.2.31.jar'), 'forge': ('14', 'forge-1.12.2-14.jar')}
MODS_NEW = dict(MODS_OLD, futuremc=('0.2.7', 'Future-MC-0.2.22.jar'))
FACING = {'facing': ['north', 'south']}
OLD_BLOCKS = {'futuremc:barrel': block(FACING, [{'facing': 'north'}, {'facing': 'south'}]),
              'futuremc:gone': block({}, [{}]),
              'railcraft:post': block({}, [{}]),
              'quark:turf': block({}, [{}], look='aa')}
NEW_BLOCKS = {'futuremc:barrel': block(FACING, [{'facing': 'south'}, {'facing': 'north'}]),  # metas swapped
              'futuremc:lantern': block({'hanging': ['true', 'false']}, [{'hanging': 'false'}, {'hanging': 'true'}]),
              'railcraft:post': block({}, [{}]),
              'quark:turf': block({}, [{}], look='bb')}


class Project:
    """A temporary project root with a previous dump, overrides, 1.21 reference data and world stats."""

    def __init__(self, tmp):
        self.root = os.path.join(tmp, 'root')
        self.env = update.Env(self.root, os.path.join(tmp, 'no-nebulaecraft'))
        write_tree(os.path.join(self.root, 'dumps', 'full-20261107-100000'), dump_files(OLD_BLOCKS, MODS_OLD))
        os.symlink('full-20261107-100000', os.path.join(self.root, 'dumps', 'full'))
        write_tree(self.root, {
            'mapping/overrides/futuremc.json': json.dumps({'blocks': {
                'futuremc:barrel': {'target': 'minecraft:barrel'}, 'futuremc:gone': {'target': 'minecraft:stone'}}}),
            'work/mc/1.21.1/generated/reports/blocks.json': json.dumps({
                'minecraft:lantern': {'properties': {'hanging': ['true', 'false'], 'waterlogged': ['true', 'false']},
                                      'states': []},
                'minecraft:barrel': {'properties': {'facing': ['north', 'south', 'up']}, 'states': []}}),
            'work/mc/1.21.1/textures-block.txt': 'stone\n',
            'work/world-stats.json': json.dumps({'world': 'w', 'chunks': 9, 'states': {
                'futuremc:barrel:0': 3, 'futuremc:barrel:1': 4, 'futuremc:lantern:0': 2, 'railcraft:post:0': 1}}),
            'mirror/mod/gradle.properties': 'minecraft_version=1.21.1\nmod_version=1.7\narchives_base_name=NebulaeMirror\n',
            'README.md': README})
        os.makedirs(os.path.join(self.root, 'data'))
        self.mods_zip(['Future-MC-0.2.22.jar', 'railcraft-12.1.1.jar', 'spongeforge-1.12.2-7.4.7.jar'])
        self.new_zip = os.path.join(tmp, '20261120-101500.zip')
        write_zip(self.new_zip, dump_files(NEW_BLOCKS, MODS_NEW, stamp='2026-11-20T10:15:30+0800'))

    def mods_zip(self, jars):
        with zipfile.ZipFile(self.env.mods_zip, 'w') as z:
            for j in jars:
                z.writestr(j, 'jar ' + j)
            z.writestr('prism.jar.bak', 'x')

    def run(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = update.main(['--root', self.root, '--nebulaecraft', self.env.nebulaecraft] + list(args))
        return code, out.getvalue()

    def full(self):
        return os.readlink(self.env.full)


class JarTest(unittest.TestCase):
    def test_stem(self):
        for name, stem in {'Future-MC-0.2.21.jar': 'future-mc', 'railcraft-12.1.1.jar': 'railcraft',
                           'Quark-r1.6-179.jar': 'quark', 'CreativeCore_v1.10.71_mc1.12.2.jar': 'creativecore',
                           'extrautils2-1.12-1.9.9.jar': 'extrautils2', 'CTM-MC1.12.2-1.0.2.31.jar': 'ctm',
                           'worldedit-forge-mc1.12.2-6.1.10-dist.jar': 'worldedit-forge',
                           'phosphor-1.12.2-0.2.6+build50-universal.jar': 'phosphor', 'js.jar': 'js',
                           'RailwayDepotWorker-1.10.8+build.private.jar': 'railwaydepotworker'}.items():
            self.assertEqual(update.jar_stem(name), stem, name)

    def test_compare(self):
        cmp = update.compare_jars(
            ['Future-MC-0.2.21.jar', 'CTM-MC1.12.2-1.0.2.31.jar', 'NebulaeCraft-1.12.2-2.26.jar'],
            ['Future-MC-0.2.22.jar', 'railcraft-12.1.1.jar', 'NebulaeCraft-1.12.2-2.25.jar', 'spongeforge-1.jar'],
            ['Future-MC-0.2.21.jar', 'railcraft-12.1.1.jar'])
        self.assertEqual(cmp['mismatch'], [('Future-MC-0.2.21.jar', 'Future-MC-0.2.22.jar')])
        self.assertEqual(cmp['missing'], ['railcraft-12.1.1.jar'])
        self.assertEqual(cmp['clientOnly'], ['CTM-MC1.12.2-1.0.2.31.jar'])


class ImportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.env = update.Env(os.path.join(self.tmp.name, 'root'))
        self.files = dump_files({'a:b': block({}, [{}])}, MODS_OLD, stamp='2026-11-20T10:15:30+0800')

    def test_zip_with_and_without_top_folder(self):
        flat = os.path.join(self.tmp.name, 'dump.zip')  # no stamp in the name: named after the manifest
        write_zip(flat, self.files)
        dest, imported = update.import_dump(self.env, flat)
        self.assertTrue(imported)
        self.assertEqual(os.path.basename(dest), 'full-20261120-101530')
        self.assertTrue(os.path.isfile(os.path.join(dest, 'blocks', 'a', 'b.json')))
        self.assertEqual(update.import_dump(self.env, flat), (dest, False))

        nested = os.path.join(self.tmp.name, '20261121-090000.zip')
        write_zip(nested, self.files, prefix='mirrordump/20261121-090000/')
        with zipfile.ZipFile(nested, 'a') as z:
            z.writestr('__MACOSX/mirrordump/._manifest.json', 'junk')
        dest2, _ = update.import_dump(self.env, nested)
        self.assertEqual(os.path.basename(dest2), 'full-20261121-090000')
        self.assertTrue(os.path.isfile(os.path.join(dest2, 'manifest.json')))
        self.assertEqual(sorted(os.listdir(self.env.dumps)), ['full-20261120-101530', 'full-20261121-090000'])

    def test_unsafe_path_is_refused(self):
        bad = os.path.join(self.tmp.name, '20261122-090000.zip')
        write_zip(bad, dict(self.files, **{'../../escaped.txt': 'x'}))
        with self.assertRaises(update.Stop):
            update.import_dump(self.env, bad)
        self.assertEqual(os.listdir(self.env.dumps), [])
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, 'escaped.txt')))

    def test_other_dump_under_the_same_name(self):
        z = os.path.join(self.tmp.name, '20261120-101500.zip')
        write_zip(z, self.files)
        update.import_dump(self.env, z)
        write_zip(z, dump_files({}, MODS_OLD, stamp='2026-11-20T10:15:59+0800'))
        with self.assertRaises(update.Stop):
            update.import_dump(self.env, z)

    def test_not_a_dump(self):
        z = os.path.join(self.tmp.name, 'x.zip')
        write_zip(z, {'readme.txt': 'x'})
        with self.assertRaises(update.Stop):
            update.import_dump(self.env, z)


class PiecesTest(unittest.TestCase):
    def test_diff(self):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = os.path.join(tmp, 'old'), os.path.join(tmp, 'new')
            write_tree(old, dump_files(OLD_BLOCKS, MODS_OLD))
            write_tree(new, dump_files(NEW_BLOCKS, MODS_NEW))
            d = update.diff_dumps.diff(old, new)
        self.assertEqual(d['mods'], [('futuremc', '0.2.6', '0.2.7')])
        self.assertEqual(d['added'], ['futuremc:lantern'])
        self.assertEqual(d['removed'], ['futuremc:gone'])
        self.assertEqual(d['changed'], ['futuremc:barrel'])
        self.assertEqual(d['appearance'], ['quark:turf'])

    def test_draft_override(self):
        class Vanilla:
            blocks = {'minecraft:lantern': {'hanging': ['true', 'false'], 'waterlogged': ['true', 'false']},
                      'minecraft:stairs': {'facing': ['north'], 'half': ['top', 'bottom']}}

            def has_block(self, b):
                return b in self.blocks

            def properties(self, b):
                return self.blocks[b]
        rule, notes = update.draft_override('futuremc:lantern', NEW_BLOCKS['futuremc:lantern'], Vanilla())
        self.assertEqual(rule, {'target': 'minecraft:lantern', 'props': {'hanging': 'hanging'}})
        self.assertIn('waterlogged', notes[0])
        rule, notes = update.draft_override('quark:stairs', block({'facing': ['north', 'up'], 'one': ['x']}, [{}]),
                                            Vanilla())
        self.assertEqual(rule, {'target': 'minecraft:stairs'})
        self.assertIn('facing=north|up', notes[0])
        self.assertEqual(update.draft_override('quark:nothing', block({}, [{}]), Vanilla())[0], None)

    def test_diff_reports(self):
        old = {'counts': {'meshes': 5, 'seconds': 3, 'same': 1}, 'warnings': {'w': ['a:x']},
               'items': {'reverseAmbiguous': ['r1']}}
        new = {'counts': {'meshes': 6, 'seconds': 9, 'same': 1, 'new': 2}, 'warnings': {'w': ['a:x', 'a:y'], 'v': ['a:z']},
               'items': {'reverseAmbiguous': ['r1', 'r2']}}
        self.assertEqual(update.diff_reports(old, new), {
            'counts': [('meshes', 5, 6), ('new', 0, 2)], 'warnings': {'w': ['a:y'], 'v': ['a:z']},
            'items': {'reverseAmbiguous': ['r2']}})
        self.assertFalse(any(update.diff_reports(new, new).values()))

    def test_version_and_readme(self):
        text, old, new = update.bump_version('a=1\nmod_version=1.9\nb=2\n')
        self.assertEqual((text, old, new), ('a=1\nmod_version=1.10\nb=2\n', '1.9', '1.10'))
        with self.assertRaises(update.Stop):
            update.bump_version('mod_version=0.1.0\n')
        d = {'mods': [('futuremc', '0.2.6', '0.2.7'), ('mirrordumper', '1.2', '1.3'), ('newmod', None, '1.0')],
             'added': ['a:b', 'a:c'], 'removed': [], 'changed': ['a:d']}
        summary = update.update_summary(d)
        self.assertEqual(summary, '原服 mod 更新：futuremc 0.2.6 → 0.2.7、newmod 1.0 新增；新增 2 个方块、变更 1 个方块')
        text = update.update_readme(README, '1.8', summary)
        self.assertIn('边界高亮）；镜像 mod 1.8（原服 mod 更新：futuremc 0.2.6 → 0.2.7、newmod 1.0 新增；'
                      '新增 2 个方块、变更 1 个方块）。版本号在 `mirror/mod/gradle.properties`', text)
        self.assertEqual(update.update_readme(text, '1.8', summary), text)
        self.assertIsNone(update.update_readme('no versions here', '1.8', summary))


class RunTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.p = Project(self.tmp.name)
        patch = mock.patch.object(update, 'run_generator', lambda *a, **k: {})
        patch.start()
        self.addCleanup(patch.stop)

    def report(self):
        return update.read_text(os.path.join(self.p.env.update, 'full-20261120-101500', 'report.md'))

    def test_stops_for_overrides_then_goes_on(self):
        code, out = self.p.run('run', self.p.new_zip, '--no-build')
        self.assertEqual(code, 2, out)
        self.assertIn('STOPPED: 1 new block(s) may need an override', out)
        self.assertEqual(self.p.full(), 'full-20261120-101500')
        report = self.report()
        self.assertIn('**Stopped: 1 new block', report)
        self.assertIn('`futuremc:lantern -> minecraft:lantern`', report)
        self.assertIn('`futuremc:gone (removed by this update)`', report)          # stale override
        self.assertIn('`futuremc:barrel x7 (mapping changed', report)              # placed, needs a refresh
        self.assertIn('`futuremc:lantern x2 (new block already placed', report)
        self.assertIn('`futuremc: 0.2.6 -> 0.2.7`', report)
        drafts = update.read_json(os.path.join(self.p.env.update, 'full-20261120-101500', 'override-suggestions.json'))
        self.assertEqual(drafts['blocks'], {'futuremc:lantern': {'target': 'minecraft:lantern',
                                                                'props': {'hanging': 'hanging'}}})

        # Second run: dumps/full already points to the new dump, the previous one comes from the state file.
        code, out = self.p.run('run', self.p.new_zip, '--no-build', '--accept-generated')
        self.assertEqual(code, 0, out)
        self.assertIn('already in dumps/full-20261120-101500', out)
        self.assertIn('previous dump: full-20261107-100000', out)
        self.assertIn('[10/10]', out)
        self.assertNotIn('Stopped', self.report())
        self.assertIn('mod_version=1.7', update.read_text(self.p.env.props))        # --no-build changes nothing
        self.assertEqual(update.read_text(self.p.env.readme), README)

    def test_override_written_by_hand_passes(self):
        update.write_json(os.path.join(self.p.env.overrides, 'futuremc.json'), {'blocks': {
            'futuremc:barrel': {'target': 'minecraft:barrel'},
            'futuremc:lantern': {'target': 'minecraft:lantern', 'props': {'hanging': 'hanging'}}}})
        code, out = self.p.run('run', self.p.new_zip, '--no-build')
        self.assertEqual(code, 0, out)
        self.assertIn('no new block needs an override decision', out)

    def test_bad_dump_stops_before_repointing(self):
        bad = os.path.join(self.tmp.name, '20261121-101500.zip')
        write_zip(bad, dump_files(NEW_BLOCKS, MODS_OLD, stamp='2026-11-21T10:15:00+0800', partial=['futuremc'],
                                  tracks=0, errors=[{'block': 'a:b', 'stage': 'model', 'error': 'NPE'}]))
        code, out = self.p.run('run', bad, '--no-build')
        self.assertEqual(code, 2, out)
        self.assertIn('partial dump (mods=futuremc)', out)
        self.assertIn('trackKitVariants is 0', out)
        self.assertIn('the dump client has Future-MC-0.2.21.jar, the origin (data/mods.zip) has Future-MC-0.2.22.jar', out)
        self.assertIn('a:b [model] NPE', out)
        self.assertEqual(self.p.full(), 'full-20261107-100000')
        code, out = self.p.run('run', bad, '--no-build', '--accept-dump', '--accept-generated')
        self.assertEqual(code, 0, out)
        self.assertEqual(self.p.full(), 'full-20261121-101500')

    def test_same_dump_as_full_needs_old(self):
        code, out = self.p.run('run', os.path.join(self.p.env.dumps, 'full-20261107-100000'), '--no-build')
        self.assertEqual(code, 2, out)
        self.assertIn('pass --old', out)

    def test_build_bumps_once(self):
        calls = []

        def fake_gradle(env, *tasks):
            calls.append(tasks)
            props = update.gradle_props(update.read_text(env.props))
            if tasks == ('build',):
                write_tree(os.path.join(env.mod, 'build', 'libs'),
                           {'NebulaeMirror-1.21.1-%s.jar' % props['mod_version']: 'jar'})
            else:
                update.write_json(os.path.join(env.mod, 'run', 'selftest', 'nebulaemirror-selftest.json'),
                                  {'blocks': 1, 'missingStates': [], 'missingItems': []})
        share = os.path.join(self.tmp.name, 'share')
        ok = mock.Mock(returncode=0, stdout='Ran 12 tests in 1s\n\nOK\n')
        with mock.patch.object(update, 'gradle', fake_gradle), mock.patch.object(update.subprocess, 'run', return_value=ok):
            for _ in range(2):  # the second run must not bump again
                code, out = self.p.run('run', self.p.new_zip, '--accept-generated', '--copy-to', share)
                self.assertEqual(code, 0, out)
        self.assertEqual(calls, [('build',), ('runSelftestClient',)] * 2)
        self.assertIn('mod_version=1.8\n', update.read_text(self.p.env.props))
        readme = update.read_text(self.p.env.readme)
        self.assertEqual(readme.count('镜像 mod 1.8（原服 mod 更新：futuremc 0.2.6 → 0.2.7；新增 1 个方块、'
                                      '移除 1 个方块、变更 1 个方块）'), 1)
        self.assertTrue(os.path.isfile(os.path.join(share, 'NebulaeMirror-1.21.1-1.8.jar')))
        self.assertTrue(os.path.isfile(os.path.join(self.p.env.update, 'full-20261120-101500',
                                                    'NebulaeMirror-1.21.1-1.8.jar')))
        self.assertIn('Future-MC-0.2.22.jar', update.read_json(self.p.env.baseline_file))


class PrepareTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.p = Project(self.tmp.name)

    def test_lists_and_extracts_changed_jars(self):
        code, out = self.p.run('prepare')
        self.assertEqual(code, 0, out)
        self.assertIn('Future-MC-0.2.21.jar  ->  Future-MC-0.2.22.jar', out)
        self.assertIn('In the zip but not loaded by the last dump (1)', out)
        self.assertIn('Client-only, keep (1):\n  CTM-MC1.12.2-1.0.2.31.jar', out)
        self.assertEqual(sorted(os.listdir(self.p.env.client_mods)), ['CHANGES.txt', 'Future-MC-0.2.22.jar'])
        self.assertEqual(update.read_text(os.path.join(self.p.env.client_mods, 'Future-MC-0.2.22.jar')),
                         'jar Future-MC-0.2.22.jar')

    def test_with_baseline_tells_new_from_server_only(self):
        update.write_json(self.p.env.baseline_file, ['Future-MC-0.2.21.jar', 'railcraft-12.1.1.jar',
                                                     'spongeforge-1.12.2-7.4.7.jar', 'Old-1.0.jar'])
        self.p.mods_zip(['Future-MC-0.2.21.jar', 'spongeforge-1.12.2-7.4.7.jar', 'NewMod-1.0.jar'])
        code, out = self.p.run('prepare')
        self.assertEqual(code, 0, out)
        self.assertIn('Replace (0):', out)
        self.assertIn('(new at the origin) (1):\n  NewMod-1.0.jar', out)
        self.assertIn('Remove (gone from the origin) (1):\n  railcraft-12.1.1.jar', out)
        self.assertNotIn('spongeforge', out)

    def test_no_changes(self):
        self.p.mods_zip(['Future-MC-0.2.21.jar', 'railcraft-12.1.1.jar'])
        code, out = self.p.run('prepare')
        self.assertIn('No jar changes since the last dump.', out)
        self.assertFalse(os.path.exists(self.p.env.client_mods))


if __name__ == '__main__':
    unittest.main()
