#!/usr/bin/env python3
r"""Refresh the mirror's local copy of the 1.12 origin world (stdlib only; Linux, macOS and Windows).

    python3 -I ops/sync/pull.py --config pull.json

1. Over the origin's RCON: `save-off`, then `save-all flush` (the origin stops writing region files and
   finishes every pending write).
2. Copy `level.dat` and `region/r.*.mca` of the overworld into `dest`; only changed files are copied.
   "copier":
     "header"   (recommended on Windows; remote is a local folder or a share such as \\ORIGIN\mcworld):
                done by this script. A region file is copied when its 8 KiB header (chunk locations and save
                timestamps, which 1.12 rewrites on every chunk save) differs from the copy's. Unlike file sizes
                and times, the header is file content, so it is right even for files the running origin keeps
                open (Windows does not update their size/time until they are closed). level.dat: always copied.
     "rsync"    (default; remote may be host:path over ssh). Fine on Linux/macOS.
     "robocopy" (Windows) compares sizes and times, so it misses files the running origin has open: avoid.
3. `save-on` again. This always runs: also when the copy fails or times out, on SIGTERM/SIGINT/Ctrl+Break,
   and when stdout is gone (the mirror server stopped while a pull was running).

The mirror mod runs this as its `pullCommand` before each sync (config/nebulaemirror-sync.json) and never
kills it while it runs (on Windows a kill would skip `save-on`), so the timeouts below are what bound it.
It can also run on its own, e.g. by hand before `/nebulaemirror sync apply`. Exit code 0: `dest` is consistent.

Config (JSON; on Windows write paths with forward slashes or doubled backslashes):
    {
      "rcon": {"host": "192.168.1.10", "port": 25575, "passwordFile": "rcon-password"},
      "copier": "rsync",                                   or "header" / "robocopy"
      "remote": "mc@192.168.1.10:/srv/minecraft/world/",   the origin world directory
      "dest": "/srv/mirror/world12",                       local copy (the mod's "source")
      "rsync": "rsync", "rsyncArgs": ["-e", "ssh -i key"], optional, rsync only
      "robocopy": "robocopy", "robocopyThreads": 8,        optional, robocopy only
      "rconTimeoutSeconds": 300,                           optional, wait for `save-all flush`
      "copyTimeoutSeconds": 1200                           optional
    }
"rcon": null skips save-off/flush/on (test servers only: vanilla 1.12 RCON drops the connection after the
login, SpongeForge's does not). Relative paths are relative to the config file. The RCON password may also come from the environment
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
REGION_HEADER = 8192  # 1024 chunk locations + 1024 save timestamps (big-endian ints)
RSYNC_FILTER = ['--include=/level.dat', '--include=/region/', '--include=/region/r.*.mca', '--exclude=*']


def log(msg):
    stamp = time.strftime('%H:%M:%S ')
    try:
        for line in (msg.splitlines() or ['']):
            print(stamp + line, flush=True)
    except (OSError, ValueError):
        pass  # nobody is reading any more (the server stopped); keep going so save-on still happens


def _decode(raw):
    """Output of a copy tool: UTF-8 if it is valid UTF-8, else the console code page tools use when piped
    (OEM on Windows, e.g. GBK on Chinese systems; the locale elsewhere)."""
    try:
        return raw.decode('utf-8')
    except UnicodeDecodeError:
        import locale
        return raw.decode('oem' if os.name == 'nt' else locale.getpreferredencoding(False), 'replace')


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


class NoRcon:
    """Stand-in when the config has "rcon": null (test setups): no commands are sent."""

    def command(self, cmd):
        return '(no RCON) ' + cmd + ' skipped'

    def close(self):
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


def _read_head(path, n=REGION_HEADER):
    try:
        with open(path, 'rb') as f:
            return f.read(n)
    except FileNotFoundError:
        return None


def _newest_chunk(head):
    """Latest chunk save time (epoch seconds) in a region header, 0 for none."""
    if len(head) < REGION_HEADER:
        return 0
    return max(struct.unpack('>1024i', head[4096:8192]))


def _copy_file(src, dst):
    """Whole-file copy through a temp file, so dst is never half written. dst gets the current time as mtime."""
    tmp = dst + '.pulltmp'
    try:
        with open(src, 'rb') as fin, open(tmp, 'wb') as fout:
            while True:
                buf = fin.read(1 << 20)
                if not buf:
                    break
                fout.write(buf)
        os.replace(tmp, dst)
    except BaseException:
        try:
            os.remove(tmp)
        except OSError:
            pass
        raise
    return os.path.getsize(dst)


def header_copy(src, dest, dry_run, deadline):
    """The "header" copier. Returns True on success; logs what it did."""
    t0 = time.time()
    os.makedirs(os.path.join(dest, 'region'), exist_ok=True)
    if not os.path.isfile(os.path.join(src, 'level.dat')):
        log('copy: %s has no level.dat' % src)
        return False
    if not dry_run:
        _copy_file(os.path.join(src, 'level.dat'), os.path.join(dest, 'level.dat'))
    names = sorted(n for n in os.listdir(os.path.join(src, 'region')) if n.startswith('r.') and n.endswith('.mca'))
    copied, size, unsure = [], 0, 0
    for n in names:
        if time.time() > deadline:
            log('copy: timed out after %d of %d region files' % (names.index(n), len(names)))
            return False
        s, d = os.path.join(src, 'region', n), os.path.join(dest, 'region', n)
        head, mine = _read_head(s), _read_head(d)
        if mine == head and head is not None:
            # Same header. Unless a chunk was saved in the same second as (or after) our last copy, the content
            # is the same too; when that cannot be ruled out, copy again (once; the copy's time then settles it).
            if _newest_chunk(head) < int(os.path.getmtime(d)) - 1:
                continue
            unsure += 1
        copied.append(n)
        if not dry_run:
            size += _copy_file(s, d)
    removed = []
    for n in sorted(os.listdir(os.path.join(dest, 'region'))):
        if n.startswith('r.') and n.endswith('.mca') and n not in names:
            removed.append(n)
            if not dry_run:
                os.remove(os.path.join(dest, 'region', n))
    log('copy: level.dat%s; region: %d files checked, %d %s (%.1f MB%s), %d removed, in %.1f s'
        % (' (dry run: nothing written)' if dry_run else '', len(names), len(copied),
           'would be copied' if dry_run else 'copied', size / 1048576.0,
           ', %d to be sure about same-second saves' % unsure if unsure else '', len(removed), time.time() - t0))
    for n in copied[:20]:
        log('copy:   ' + n)
    if len(copied) > 20:
        log('copy:   ... and %d more' % (len(copied) - 20))
    return True


def _copy_commands(cfg, base, dest, dry_run):
    """The copy step as a list of (command, ok(returncode)) pairs, or of functions(deadline) -> bool."""
    copier = cfg.get('copier', 'rsync')
    if copier == 'header':
        src = os.path.normpath(os.path.join(base, os.path.expanduser(cfg['remote'])))
        return [lambda deadline: header_copy(src, dest, dry_run, deadline)]
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
    raise SystemExit('unknown copier %r (use "header", "rsync" or "robocopy")' % copier)


def _pull_locked(cfg, base, dest, dry_run):
    commands = _copy_commands(cfg, base, dest, dry_run)  # config errors surface before save-off
    if cfg.get('copier') == 'robocopy':
        log('WARNING: robocopy decides by file size and time, which Windows does not update for region files the '
            'running origin keeps open, so changed regions are skipped. Use "copier": "header"')
    rc = cfg['rcon']
    started = time.time()
    if rc is None:
        # Test setups only: without save-off the origin may write region files while they are copied.
        log('WARNING: "rcon" is null: copying without save-off / save-all flush')
        rcon = NoRcon()
    else:
        rcon = Rcon(rc.get('host', '127.0.0.1'), int(rc.get('port', 25575)), password(rc, base),
                    float(cfg.get('rconTimeoutSeconds', 300)))
    saving_off = False
    result = {'started': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'ok': False}
    try:
        try:
            log('origin: ' + (rcon.command('save-off').strip() or 'save-off sent'))
        except (OSError, RconError) as e:
            raise RconError('the server dropped the RCON connection after the login (%s). Vanilla 1.12 RCON does '
                            'that after every packet; SpongeForge does not' % e)
        saving_off = rc is not None
        log('origin: ' + (rcon.command('save-all flush').strip() or 'save-all flush done'))
        timeout = float(cfg.get('copyTimeoutSeconds', cfg.get('rsyncTimeoutSeconds', 1200)))
        t0 = time.time()
        for step in commands:
            if callable(step):
                ok_ = step(t0 + timeout)
                result.setdefault('copyExit', []).append(0 if ok_ else 1)
                if not ok_:
                    result['copySeconds'] = round(time.time() - t0, 1)
                    return 2
                continue
            cmd, ok = step
            name = os.path.basename(cmd[0])
            log('copy: ' + ' '.join(cmd))
            proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  timeout=max(1.0, timeout - (time.time() - t0)))
            for line in _decode(proc.stdout).splitlines():
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
    # The mirror mod reads this output as UTF-8; Windows would otherwise write the ANSI code page (GBK...).
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8', errors='replace')
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--config', required=True, help='pull config JSON (see the module docstring)')
    ap.add_argument('--dry-run', action='store_true', help='list what would be copied (nothing is written); save-off/flush/on still happen')
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
