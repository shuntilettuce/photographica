#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Checks a built jar for the things a short play session would not show.

A missing translation key does not crash: the screen just reads
`snapmatica.camera.tab_photo` where a label should be, and only on the screen
nobody opened. A config key written under one name and read under another does
not crash either: the setting silently resets every launch. Both are exactly
what a rename-by-regex produces, and neither is caught by the compiler, by the
mixin annotation processor, or by starting the game and taking one photograph.

    python tools/verify_resources.py <srcDir> <resourcesDir> [jar]
"""
import io
import json
import os
import re
import sys
import zipfile

TRANSLATABLE = re.compile(r'\btranslatable\(\s*"([^"]+)"')
# setProperty("k", ...) / getBool(p, "k", ...) and the other typed readers
WRITE_KEY = re.compile(r'\.setProperty\(\s*"([^"]+)"')
# The readers are written in a column, so there is whitespace before the paren.
READ_KEY = re.compile(r'\bget(?:Bool|Int|Float|Long|Double|String)\s*\(\s*\w+\s*,\s*"([^"]+)"')


def java_sources(root):
    for dp, _d, fs in os.walk(root):
        for f in fs:
            if f.endswith('.java'):
                yield os.path.join(dp, f)


def read(p):
    return io.open(p, encoding='utf-8').read()


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    src, res = sys.argv[1], sys.argv[2]
    jar = sys.argv[3] if len(sys.argv) > 3 else None
    bad = 0

    # ---- translation keys ---------------------------------------------------
    used = set()
    for p in java_sources(src):
        used |= set(TRANSLATABLE.findall(read(p)))
    used = {k for k in used if k.startswith('snapmatica') or k.startswith('key.')
            or k.startswith('category.')}
    langs = {}
    for name in ('en_us', 'ja_jp'):
        p = os.path.join(res, 'assets/snapmatica/lang/%s.json' % name)
        if not os.path.isfile(p):
            print('  MISSING lang file: %s' % p)
            bad += 1
            continue
        langs[name] = json.load(io.open(p, encoding='utf-8'))
    print('translation keys used in code: %d' % len(used))
    for name, d in langs.items():
        miss = sorted(used - set(d))
        print('  %s: %d keys, %d used-but-absent' % (name, len(d), len(miss)))
        for k in miss:
            print('      MISSING  %s' % k)
        bad += len(miss)
    if len(langs) == 2:
        only_en = sorted(set(langs['en_us']) - set(langs['ja_jp']))
        only_ja = sorted(set(langs['ja_jp']) - set(langs['en_us']))
        for k in only_en:
            print('      en_us only: %s' % k)
        for k in only_ja:
            print('      ja_jp only: %s' % k)

    # ---- config round-trip --------------------------------------------------
    cfg = os.path.join(src, 'dev/shunti/snapmatica/client/SnapmaticaConfig.java')
    if os.path.isfile(cfg):
        t = read(cfg)
        w, r = set(WRITE_KEY.findall(t)), set(READ_KEY.findall(t))
        print('config keys: %d written, %d read' % (len(w), len(r)))
        for k in sorted(w - r):
            print('      WRITTEN BUT NEVER READ  %s' % k)
        for k in sorted(r - w):
            print('      READ BUT NEVER WRITTEN  %s' % k)
        bad += len(w ^ r)

    # ---- what the jar actually ships ---------------------------------------
    if jar:
        names = set(zipfile.ZipFile(jar).namelist())
        want = ['assets/snapmatica/lang/en_us.json',
                'assets/snapmatica/lang/ja_jp.json',
                'assets/snapmatica/shaders/evf_blur.fsh',
                'assets/snapmatica/shaders/evf_blur.vsh',
                'assets/snapmatica/shaders/evf_peaking.fsh',
                'assets/snapmatica/textures/evf_bluenoise.bin',
                'snapmatica.mixins.json',
                'pack.mcmeta']
        print('jar %s' % os.path.basename(jar))
        for n in want:
            ok = n in names
            print('  %s %s' % ('ok     ' if ok else 'MISSING', n))
            bad += 0 if ok else 1
        # every mixin the config names must actually be in the jar
        cfgname = 'snapmatica.mixins.json'
        if cfgname in names:
            mc = json.loads(zipfile.ZipFile(jar).read(cfgname))
            pkg = mc['package'].replace('.', '/')
            for m in mc.get('client', []) + mc.get('mixins', []):
                cls = '%s/%s.class' % (pkg, m)
                ok = cls in names
                print('  %s mixin %s' % ('ok     ' if ok else 'MISSING', m))
                bad += 0 if ok else 1

    print('\n%s' % ('OK -- nothing missing.' if not bad else '%d problem(s).' % bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
