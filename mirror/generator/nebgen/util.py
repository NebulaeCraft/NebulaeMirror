import hashlib
import json
import os
import re

_ID_OK = re.compile(r'[^a-z0-9/._-]')
_NAME_OK = re.compile(r'[^a-z0-9_]')


def sanitize_path(path):
    """1.21 Identifier paths allow [a-z0-9/._-]."""
    return _ID_OK.sub('_', path.lower())


def sanitize_name(name):
    """Property names and values allow [a-z0-9_]."""
    return _NAME_OK.sub('_', str(name).lower())


def sanitize_id(rl):
    ns, path = rl.split(':', 1)
    return sanitize_name(ns) + ':' + sanitize_path(path)


def write_json(path, data, pretty=True):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf8') as f:
        if pretty:
            json.dump(data, f, ensure_ascii=False, indent=1, sort_keys=False)
        else:
            json.dump(data, f, ensure_ascii=False, separators=(',', ':'))
        f.write('\n')


def read_json(path):
    with open(path, encoding='utf8') as f:
        return json.load(f)


def content_hash(data):
    text = json.dumps(data, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
    return hashlib.sha1(text.encode('utf8')).hexdigest()[:20]


def variant_key(props):
    """Blockstate variant key; order-independent in Minecraft, sorted here for determinism."""
    return ','.join('%s=%s' % (k, props[k]) for k in sorted(props))
