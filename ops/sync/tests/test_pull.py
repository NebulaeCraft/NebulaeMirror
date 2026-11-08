"""python3 -I -m unittest discover -s ops/sync/tests"""
import json
import os
import socket
import struct
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import pull  # noqa: E402


class FakeRcon(threading.Thread):
    """Accepts RCON connections, checks the password, records every command."""

    def __init__(self, password='secret'):
        super().__init__(daemon=True)
        self.password = password
        self.commands = []
        self.srv = socket.socket()
        self.srv.bind(('127.0.0.1', 0))
        self.srv.listen(4)
        self.port = self.srv.getsockname()[1]

    @staticmethod
    def _recv(conn):
        head = conn.recv(4)
        if len(head) < 4:
            return None
        (n,) = struct.unpack('<i', head)
        data = b''
        while len(data) < n:
            data += conn.recv(n - len(data))
        rid, ptype = struct.unpack('<ii', data[:8])
        return rid, ptype, data[8:-2].decode()

    @staticmethod
    def _send(conn, rid, ptype, body):
        payload = struct.pack('<ii', rid, ptype) + body.encode() + b'\x00\x00'
        conn.sendall(struct.pack('<i', len(payload)) + payload)

    def run(self):
        while True:
            try:
                conn, _ = self.srv.accept()
            except OSError:
                return
            with conn:
                authed = False
                while True:
                    p = self._recv(conn)
                    if p is None:
                        break
                    rid, ptype, body = p
                    if ptype == 3:
                        authed = body == self.password
                        self._send(conn, rid if authed else -1, 2, '')
                    elif authed and ptype == 2:
                        self.commands.append(body)
                        self._send(conn, rid, 0, 'ok: ' + body)

    def close(self):
        self.srv.close()


class PullTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name
        self.remote = os.path.join(self.dir, 'origin')
        os.makedirs(os.path.join(self.remote, 'region'))
        os.makedirs(os.path.join(self.remote, 'DIM1', 'region'))
        for rel, data in [('level.dat', 'L'), ('region/r.0.0.mca', 'R'), ('region/r.0.0.mca.tmp', 'T'),
                          ('DIM1/region/r.0.0.mca', 'D'), ('session.lock', 'S')]:
            with open(os.path.join(self.remote, rel), 'w') as f:
                f.write(data)
        with open(os.path.join(self.dir, 'pw'), 'w') as f:
            f.write('secret\n')
        self.rcon = FakeRcon()
        self.rcon.start()

    def tearDown(self):
        self.rcon.close()
        self.tmp.cleanup()

    def config(self, **kw):
        cfg = {'rcon': {'host': '127.0.0.1', 'port': self.rcon.port, 'passwordFile': 'pw'},
               'remote': self.remote, 'dest': 'copy'}
        cfg.update(kw)
        return cfg

    def test_copies_overworld_regions_between_save_off_and_save_on(self):
        self.assertEqual(0, pull.pull(self.config(), self.dir))
        self.assertEqual(['save-off', 'save-all flush', 'save-on'], self.rcon.commands)
        copied = sorted(os.path.relpath(os.path.join(r, f), os.path.join(self.dir, 'copy'))
                        for r, _, fs in os.walk(os.path.join(self.dir, 'copy')) for f in fs)
        self.assertEqual(['.pull-last.json', '.pull.lock', 'level.dat', 'region/r.0.0.mca'], copied)
        with open(os.path.join(self.dir, 'copy', '.pull-last.json')) as f:
            self.assertTrue(json.load(f)['ok'])

    def test_save_on_even_when_rsync_fails(self):
        self.assertEqual(2, pull.pull(self.config(remote=os.path.join(self.dir, 'missing')), self.dir))
        self.assertEqual(['save-off', 'save-all flush', 'save-on'], self.rcon.commands)

    def test_wrong_password_sends_nothing(self):
        with open(os.path.join(self.dir, 'pw'), 'w') as f:
            f.write('wrong')
        with self.assertRaises(pull.RconError):
            pull.pull(self.config(), self.dir)
        self.assertEqual([], self.rcon.commands)

    def test_second_pull_on_same_dest_is_refused(self):
        dest = os.path.join(self.dir, 'copy')
        os.makedirs(dest)
        with open(os.path.join(dest, '.pull.lock'), 'a+') as held:
            self.assertTrue(pull._lock(held))
            self.assertEqual(3, pull.pull(self.config(), self.dir))
        self.assertEqual([], self.rcon.commands)

    def fake_robocopy(self, exit_code):
        """A stand-in robocopy that records its arguments and exits with exit_code."""
        script = os.path.join(self.dir, 'robocopy.sh')
        self.robolog = os.path.join(self.dir, 'robocopy.log')
        with open(script, 'w') as f:
            f.write('#!/bin/sh\necho "$@" >> %s\necho "   Files :  1  1"\nexit %d\n' % (self.robolog, exit_code))
        os.chmod(script, 0o755)
        return script

    def test_robocopy_exit_codes_below_8_are_success(self):
        cfg = self.config(copier='robocopy', robocopy=self.fake_robocopy(1))
        self.assertEqual(0, pull.pull(cfg, self.dir))
        with open(self.robolog) as f:
            calls = f.read().splitlines()
        self.assertEqual(2, len(calls))
        self.assertTrue(calls[0].startswith(self.remote + ' ' + os.path.join(self.dir, 'copy') + ' level.dat /COPY:DAT'))
        self.assertIn(os.path.join(self.remote, 'region') + ' ', calls[1])
        self.assertIn(' r.*.mca ', calls[1])
        self.assertIn('/MT:8', calls[1])
        self.assertNotIn('/L', calls[0].split())
        self.assertEqual(['save-off', 'save-all flush', 'save-on'], self.rcon.commands)

    def test_robocopy_failure_stops_and_still_saves_on(self):
        cfg = self.config(copier='robocopy', robocopy=self.fake_robocopy(8))
        self.assertEqual(2, pull.pull(cfg, self.dir))
        with open(self.robolog) as f:
            self.assertEqual(1, len(f.read().splitlines()), 'region copy is skipped after level.dat failed')
        self.assertEqual(['save-off', 'save-all flush', 'save-on'], self.rcon.commands)

    def test_robocopy_dry_run_lists_only(self):
        cfg = self.config(copier='robocopy', robocopy=self.fake_robocopy(0))
        self.assertEqual(0, pull.pull(cfg, self.dir, dry_run=True))
        with open(self.robolog) as f:
            self.assertTrue(all('/L' in line.split() for line in f.read().splitlines()))

    def test_copy_output_is_reemitted_as_utf8_without_warnings(self):
        """The mod reads pull.py's output as UTF-8. (The GBK branch of _decode needs Windows to test.)"""
        tool = os.path.join(self.dir, 'tool.sh')
        with open(tool, 'w') as f:
            f.write('#!/bin/sh\nprintf "\\346\\226\\207\\344\\273\\266: 1\\n"\nprintf "\\377\\376 bad\\n"\n')
        os.chmod(tool, 0o755)
        cfg_path = os.path.join(self.dir, 'pull.json')
        with open(cfg_path, 'w') as f:
            json.dump(self.config(copier='robocopy', robocopy=tool), f)
        import subprocess
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'pull.py')
        out = subprocess.run([sys.executable, '-I', '-W', 'error', script, '--config', cfg_path],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        text = out.stdout.decode('utf-8')  # strict, like the mod
        self.assertEqual(0, out.returncode, text)
        self.assertIn('tool.sh: \u6587\u4ef6: 1', text)
        self.assertIn(' bad', text, 'undecodable output is replaced, not fatal')
        self.assertNotIn('Warning', text)

    def test_rcon_null_copies_without_commands(self):
        self.assertEqual(0, pull.pull(self.config(rcon=None), self.dir))
        self.assertEqual([], self.rcon.commands)
        self.assertTrue(os.path.isfile(os.path.join(self.dir, 'copy', 'region', 'r.0.0.mca')))

    def test_unknown_copier_is_rejected_before_save_off(self):
        with self.assertRaises(SystemExit):
            pull.pull(self.config(copier='scp'), self.dir)
        self.assertEqual([], self.rcon.commands)

    def test_sigterm_during_rsync_still_turns_saving_back_on(self):
        import signal
        import subprocess
        import time
        slow = os.path.join(self.dir, 'slow-rsync.sh')
        with open(slow, 'w') as f:
            f.write('#!/bin/sh\nsleep 30\n')
        os.chmod(slow, 0o755)
        cfg_path = os.path.join(self.dir, 'pull.json')
        with open(cfg_path, 'w') as f:
            json.dump(self.config(rsync=slow), f)
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'pull.py')
        proc = subprocess.Popen([sys.executable, '-I', script, '--config', cfg_path],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        deadline = time.time() + 10
        while 'save-all flush' not in self.rcon.commands and time.time() < deadline:
            time.sleep(0.05)
        time.sleep(0.3)  # inside rsync now
        proc.send_signal(signal.SIGTERM)
        out, _ = proc.communicate(timeout=10)
        self.assertNotEqual(0, proc.returncode, out)
        self.assertEqual(['save-off', 'save-all flush', 'save-on'], self.rcon.commands, out)


class HeaderCopierTest(unittest.TestCase):
    """The "header" copier: decides by the region header (content), not by file size or time."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.src = os.path.join(self.tmp.name, 'origin')
        self.dst = os.path.join(self.tmp.name, 'copy')
        os.makedirs(os.path.join(self.src, 'region'))
        with open(os.path.join(self.src, 'level.dat'), 'wb') as f:
            f.write(b'L1')
        self.old = time.time() - 3600
        for name in ('r.0.0.mca', 'r.-1.2.mca'):
            self.region(name, saved=int(self.old), payload=b'A' * 5000)

    def tearDown(self):
        self.tmp.cleanup()

    def region(self, name, saved, payload, keep_stat=False):
        """A region file whose chunk 0 was saved at {saved}; keep_stat restores size and mtime afterwards
        (what Windows shows for a file the running server keeps open)."""
        path = os.path.join(self.src, 'region', name)
        before = os.stat(path) if keep_stat else None
        head = bytearray(8192)
        struct.pack_into('>i', head, 0, (2 << 8) | 2)
        struct.pack_into('>i', head, 4096, saved)
        with open(path, 'wb') as f:
            f.write(bytes(head) + payload)
        if before:
            self.assertEqual(before.st_size, os.path.getsize(path))
            os.utime(path, (before.st_atime, before.st_mtime))
        return path

    def run_copy(self, dry=False):
        self.lines = []
        orig = pull.log
        pull.log = self.lines.append
        try:
            return pull.header_copy(self.src, self.dst, dry, time.time() + 60)
        finally:
            pull.log = orig

    def copied(self):
        return [l.strip()[len('copy:'):].strip() for l in self.lines if l.startswith('copy:   ')]

    def read(self, *parts):
        with open(os.path.join(self.dst, *parts), 'rb') as f:
            return f.read()

    def test_first_copy_then_nothing(self):
        self.assertTrue(self.run_copy())
        self.assertEqual(['r.-1.2.mca', 'r.0.0.mca'], self.copied())
        self.assertEqual(b'L1', self.read('level.dat'))
        self.assertTrue(self.run_copy())
        self.assertEqual([], self.copied())

    def test_changed_header_is_copied_even_with_unchanged_size_and_time(self):
        self.assertTrue(self.run_copy())
        self.region('r.0.0.mca', saved=int(self.old) + 60, payload=b'B' * 5000, keep_stat=True)
        self.assertTrue(self.run_copy())
        self.assertEqual(['r.0.0.mca'], self.copied())
        self.assertTrue(self.read('region', 'r.0.0.mca').endswith(b'B' * 5000))

    def test_save_in_the_same_second_as_the_copy_is_copied_again(self):
        self.assertTrue(self.run_copy())
        # header unchanged, but a chunk saved "now" (right after our copy) could have new data in place
        path = self.region('r.0.0.mca', saved=int(time.time()), payload=b'A' * 5000)
        os.utime(os.path.join(self.dst, 'region', 'r.0.0.mca'))
        with open(os.path.join(self.dst, 'region', 'r.0.0.mca'), 'r+b') as f:
            f.write(open(path, 'rb').read(8192))  # make the copy's header identical
        self.assertTrue(self.run_copy())
        self.assertEqual(['r.0.0.mca'], self.copied())

    def test_level_dat_always_and_removed_regions(self):
        self.assertTrue(self.run_copy())
        with open(os.path.join(self.src, 'level.dat'), 'wb') as f:
            f.write(b'L2')
        os.remove(os.path.join(self.src, 'region', 'r.-1.2.mca'))
        self.assertTrue(self.run_copy())
        self.assertEqual(b'L2', self.read('level.dat'))
        self.assertFalse(os.path.exists(os.path.join(self.dst, 'region', 'r.-1.2.mca')))
        self.assertFalse(any(n.endswith('.pulltmp') for n in os.listdir(os.path.join(self.dst, 'region'))))

    def test_dry_run_writes_nothing(self):
        self.assertTrue(self.run_copy(dry=True))
        self.assertEqual(['r.-1.2.mca', 'r.0.0.mca'], self.copied())
        self.assertFalse(os.path.exists(os.path.join(self.dst, 'level.dat')))
        self.assertEqual([], os.listdir(os.path.join(self.dst, 'region')))

    def test_through_pull_with_rcon(self):
        rcon = FakeRcon()
        rcon.start()
        try:
            with open(os.path.join(self.tmp.name, 'pw'), 'w') as f:
                f.write('secret')
            cfg = {'rcon': {'host': '127.0.0.1', 'port': rcon.port, 'passwordFile': 'pw'},
                   'copier': 'header', 'remote': self.src, 'dest': self.dst}
            self.assertEqual(0, pull.pull(cfg, self.tmp.name))
            self.assertEqual(['save-off', 'save-all flush', 'save-on'], rcon.commands)
            self.assertEqual(b'L1', self.read('level.dat'))
        finally:
            rcon.close()


if __name__ == '__main__':
    unittest.main()
