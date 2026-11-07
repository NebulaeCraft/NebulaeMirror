#!/usr/bin/env python3
"""Refresh the mirror's local copy of the 1.12 origin world (stdlib only; Linux, macOS and Windows).

    python3 -I ops/sync/pull.py --config pull.json

1. Over the origin's RCON: `save-off`, then `save-all flush` (the origin stops writing region files and
   finishes every pending write).
2. Copy `level.dat` and `region/r.*.mca` of the overworld into `dest`; only changed files are copied.
   "copier": "rsync" (default; remote may be host:path over ssh) or "robocopy" (Windows; remote is a local
   folder or a UNC share such as \\ORIGIN\mcworld).
3. `save-on` again. This always runs: also when the copy fails or times out, on SIGTERM/SIGINT/Ctrl+Break,
   and when stdout is gone (the mirror server stopped while a pull was running).

The mirror mod runs this as its `pullCommand` before each sync (config/nebulaemirror-sync.json) and never
kills it while it runs (on Windows a kill would skip `save-on`), so the timeouts below are what bound it.
It can also run on its own, e.g. by hand before `/nebulaemirror sync apply`. Exit code 0: `dest` is consistent.

Config (JSON; on Windows write paths with forward slashes or doubled backslashes):
    {
      "rcon": {"host": "192.168.1.10", "port": 25575, "passwordFile": "rcon-password"},
      "copier": "rsync",                                   or "robocopy"
      "remote": "mc@192.168.1.10:/srv/minecraft/world/",   the origin world directory
      "dest": "/srv/mirror/world12",                       local copy (the mod's "source")
      "rsync": "rsync", "rsyncArgs": ["-e", "ssh -i key"], optional, rsync only
      "robocopy": "robocopy", "robocopyThreads": 8,        optional, robocopy only
      "rconTimeoutSeconds": 300,                           optional, wait for `save-all flush`
      "copyTimeoutSeconds": 1200                           optional
    }
Relative paths are relative to the config file. The RCON password may also come from the environment
variable named by "passwordEnv" (default NEBULAEMIRROR_RCON_PASSWORD); never from the command line.
"""
import argparse
import json
import os
import signal
import socket
import struct
import subprocess
import sys
import time

AUTH, AUTH_RESPONSE, COMMAND, RESPONSE = 3, 2, 2, 0
RSYNC_FILTER = ['--include=/level.dat', '--include=/region/', '--include=/region/r.*.mca', '--exclude=*']


def log(msg):
    try:
        print(time.strftime('%H:%M:%S ') + msg, flush=True)
    except (OSError, ValueError):
        pass  # nobody is reading any more (the server stopped); keep going so save-on still happens


def _lock(f):
    """Non-blocking exclusive lock on an open file; False if another pull holds it."""
    try:
        if os.name == 'nt':
            import msvcrt
            f.seek(0)
            msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return True
    except OSError:
        return False


class RconError(Exception):
    pass


class Rcon:
    """Minimal Minecraft RCON client (Source RCON framing, little-endian)."""

    def __init__(self, host, port, password, timeout):
        self.sock = socket.create_connection((host, port), timeout=10)
        self.sock.settimeout(timeout)
        self.next_id = 1
        try:
            rid = self._send(AUTH, password)
            while True:
                pid, ptype, _ = self._recv()
                if ptype == AUTH_RESPONSE:
                    if pid == -1 or pid != rid:
                        raise RconError('RCON authentication failed')
                    return
        except BaseException:
            self.close()
            raise

    def _send(self, ptype, body):
        rid = self.next_id
        self.next_id += 1
        payload = struct.pack('<ii', rid, ptype) + body.encode('utf-8') + b'\x00\x00'
        self.sock.sendall(struct.pack('<i', len(payload)) + payload)
        return rid

    def _read(self, n):
        buf = b''
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise RconError('RCON connection closed')
            buf += chunk
        return buf

    def _recv(self):
        (length,) = struct.unpack('<i', self._read(4))
        if length < 10 or length > 1 << 20:
            raise RconError('bad RCON packet length %d' % length)
        data = self._read(length)
        pid, ptype = struct.unpack('<ii', data[:8])
        return pid, ptype, data[8:-2].decode('utf-8', 'replace')

    def command(self, cmd):
        rid = self._send(COMMAND, cmd)
        while True:
            pid, ptype, body = self._recv()
            if pid == rid and ptype == RESPONSE:
                return body

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def password(rcon_cfg, base):
    if rcon_cfg.get('passwordFile'):
        path = os.path.join(base, os.path.expanduser(rcon_cfg['passwordFile']))
        with open(path, encoding='utf-8') as f:
            return f.read().strip()
    env = rcon_cfg.get('passwordEnv', 'NEBULAEMIRROR_RCON_PASSWORD')
    if os.environ.get(env):
        return os.environ[env]
    raise SystemExit('no RCON password: set rcon.passwordFile in the config or $%s' % env)


def _terminate(signum, frame):
    raise SystemExit('stopped by signal %d' % signum)


def pull(cfg, base, dry_run=False):
    """Returns 0 on success. save-on is sent whenever save-off was."""
    dest = os.path.join(base, os.path.expanduser(cfg['dest']))
    os.makedirs(dest, exist_ok=True)
    with open(os.path.join(dest, '.pull.lock'), 'a+') as lock:
        if not _lock(lock):
            log('another pull is running on %s' % dest)
            return 3
        return _pull_locked(cfg, base, dest, dry_run)


def _copy_commands(cfg, base, dest, dry_run):
    """The copy step as a list of (command, ok(returncode)) pairs."""
    copier = cfg.get('copier', 'rsync')
    if copier == 'rsync':
        remote = cfg['remote'] if cfg['remote'].endswith('/') else cfg['remote'] + '/'
        cmd = [cfg.get('rsync', 'rsync'), '-a', '--delete', '--stats'] + list(cfg.get('rsyncArgs', [])) \
            + RSYNC_FILTER + (['--dry-run'] if dry_run else []) + [remote, dest.rstrip('/\\') + '/']
        return [(cmd, lambda rc: rc == 0)]
    if copier == 'robocopy':
        src = os.path.normpath(os.path.join(base, os.path.expanduser(cfg['remote'])))
        dst = os.path.normpath(dest)
        exe = cfg.get('robocopy', 'robocopy')
        # Copies a file when its size or time differs; few retries; summary only. Exit codes < 8 are success.
        opts = ['/COPY:DAT', '/DCOPY:T', '/R:3', '/W:5', '/NP', '/NFL', '/NDL', '/NJH', '/XJ'] \
            + (['/L'] if dry_run else [])
        ok = lambda rc: 0 <= rc < 8  # noqa: E731
        return [([exe, src, dst, 'level.dat'] + opts, ok),
                ([exe, os.path.join(src, 'region'), os.path.join(dst, 'region'), 'r.*.mca'] + opts
                 + ['/MT:%d' % int(cfg.get('robocopyThreads', 8))], ok)]
    raise SystemExit('unknown copier %r (use "rsync" or "robocopy")' % copier)


def _pull_locked(cfg, base, dest, dry_run):
    commands = _copy_commands(cfg, base, dest, dry_run)  # config errors surface before save-off
    rc = cfg['rcon']
    started = time.time()
    rcon = Rcon(rc.get('host', '127.0.0.1'), int(rc.get('port', 25575)), password(rc, base),
                float(cfg.get('rconTimeoutSeconds', 300)))
    saving_off = False
    result = {'started': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'ok': False}
    try:
        log('origin: ' + (rcon.command('save-off').strip() or 'save-off sent'))
        saving_off = True
        log('origin: ' + (rcon.command('save-all flush').strip() or 'save-all flush done'))
        timeout = float(cfg.get('copyTimeoutSeconds', cfg.get('rsyncTimeoutSeconds', 1200)))
        t0 = time.time()
        for cmd, ok in commands:
            name = os.path.basename(cmd[0])
            log('copy: ' + ' '.join(cmd))
            proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                  errors='replace', timeout=max(1.0, timeout - (time.time() - t0)))
            for line in proc.stdout.splitlines():
                if line.strip():
                    log(name + ': ' + line.rstrip())
            result.setdefault('copyExit', []).append(proc.returncode)
            if not ok(proc.returncode):
                log('%s failed with exit code %d' % (name, proc.returncode))
                result['copySeconds'] = round(time.time() - t0, 1)
                return 2
        result['copySeconds'] = round(time.time() - t0, 1)
        result['ok'] = True
        return 0
    finally:
        if saving_off:
            try:
                log('origin: ' + (rcon.command('save-on').strip() or 'save-on sent'))
            except (OSError, RconError) as e:
                # The connection dropped mid-run: reconnect once so the origin does not stay unsaved.
                log('save-on failed (%s), reconnecting' % e)
                rcon.close()
                rcon = Rcon(rc.get('host', '127.0.0.1'), int(rc.get('port', 25575)), password(rc, base), 30)
                log('origin: ' + (rcon.command('save-on').strip() or 'save-on sent'))
        rcon.close()
        result['seconds'] = round(time.time() - started, 1)
        if not dry_run:
            with open(os.path.join(dest, '.pull-last.json'), 'w', encoding='utf-8') as f:
                json.dump(result, f, indent=2)
        log('pull %s in %.1f s' % ('done' if result['ok'] else 'FAILED', result['seconds']))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--config', required=True, help='pull config JSON (see the module docstring)')
    ap.add_argument('--dry-run', action='store_true', help='list what would be copied (rsync --dry-run / robocopy /L); save-off/flush/on still happen')
    args = ap.parse_args(argv)
    for sig in ('SIGTERM', 'SIGBREAK'):
        if hasattr(signal, sig):
            signal.signal(getattr(signal, sig), _terminate)
    with open(args.config, encoding='utf-8') as f:
        cfg = json.load(f)
    base = os.path.dirname(os.path.abspath(args.config))
    try:
        return pull(cfg, base, args.dry_run)
    except (OSError, RconError, subprocess.TimeoutExpired) as e:
        log('pull failed: %s' % e)
        return 1


if __name__ == '__main__':
    sys.exit(main())
