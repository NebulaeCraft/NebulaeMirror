"""python3 -I -m unittest discover -s ops/sync/tests"""
import json
import os
import socket
import struct
import sys
import tempfile
import threading
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


if __name__ == '__main__':
    unittest.main()
