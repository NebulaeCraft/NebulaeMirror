"""Assets for the mirror's TileEntity renderers that are not block textures, copied into the generator output
(so third-party files stay out of the repository, like every other extracted texture):

  * MoarSigns sign textures  -> assets/nebulaemirror/textures/moarsigns/<wood|metal>/...png   (from the mods zip)
  * NebulaeCraft roadmark fonts -> assets/nebulaemirror/roadmark/roadmark(_en).ttf
    (from the NebulaeCraft 2.26 source tree when present, else from the NebulaeCraft jar in the mods zip)
"""
import io
import os
import re
import zipfile

from . import MOD_ID

SIGNS_PREFIX = 'assets/moarsigns/textures/signs/'
FONT_PREFIX = 'assets/nebulaecraft/textures/font/'
FONTS = ('roadmark.ttf', 'roadmark_en.ttf')


def _jars(mods_zip, pattern):
    with zipfile.ZipFile(mods_zip) as z:
        for name in z.namelist():
            if re.search(pattern, os.path.basename(name), re.I) and name.endswith('.jar'):
                yield name, z.read(name)


def _write(dest, data):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, 'wb') as f:
        f.write(data)


def copy_extras(assets_dir, mods_zip, nebulaecraft_resources, report):
    if mods_zip and os.path.isfile(mods_zip):
        for _, data in _jars(mods_zip, r'^moarsigns'):
            with zipfile.ZipFile(io.BytesIO(data)) as jar:
                for n in jar.namelist():
                    if n.startswith(SIGNS_PREFIX) and n.endswith('.png'):
                        rel = n[len(SIGNS_PREFIX):].lower()
                        _write(os.path.join(assets_dir, MOD_ID, 'textures', 'moarsigns', rel), jar.read(n))
                        report.count('extras.moarsignsTextures')
    else:
        report.warn('extras', 'no mods zip: MoarSigns textures not copied (signs render untextured)')

    fonts = {}
    src = os.path.join(nebulaecraft_resources or '', FONT_PREFIX)
    for f in FONTS:
        p = os.path.join(src, f)
        if os.path.isfile(p):
            with open(p, 'rb') as fh:
                fonts[f] = fh.read()
    if len(fonts) < len(FONTS) and mods_zip and os.path.isfile(mods_zip):
        for _, data in _jars(mods_zip, r'^nebulaecraft'):
            with zipfile.ZipFile(io.BytesIO(data)) as jar:
                for f in FONTS:
                    if f not in fonts and FONT_PREFIX + f in jar.namelist():
                        fonts[f] = jar.read(FONT_PREFIX + f)
    for f, data in fonts.items():
        _write(os.path.join(assets_dir, MOD_ID, 'roadmark', f), data)
        report.count('extras.roadmarkFonts')
    if 'roadmark.ttf' not in fonts:
        report.warn('extras', 'roadmark.ttf not found: roadmark_text falls back to a system font')
