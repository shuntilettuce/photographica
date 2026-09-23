#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Make the already-published 1.21.10 jars start.

CloudTimeMixin was gated at >=1.21.10, but the tick count is not an argument of
renderClouds until 1.21.11, so @ModifyVariable(argsOnly = true) had no target
and defaultRequire = 1 made that fatal. Every 1.21.10 jar carrying that mixin
crashes before the title screen: 1.3.0, 1.3.1 and 1.3.2, which is every release
since the mixin was written.

The obvious repair -- rebuild each version from its own commit -- is not
available and would not be safe if it were: only 1.3.2 has a commit naming it,
so the others would have to be guessed at by date, and a guess republished under
an old version number is worse than the crash.

So this does the one thing that is certainly right instead. In a FIXED build
CloudTimeMixin still exists on 1.21.10; it just falls to the else branch, which
targets WorldRenderer and injects nothing. A mixin that injects nothing and a
mixin that is not listed are the same mixin as far as the game is concerned. So
the repair is to drop that one name from the mixin config inside the published
jar. Every other byte stays as it was shipped -- a more faithful artifact than a
rebuild from a commit nobody can identify.

Which jars are broken is read out of the refmap rather than assumed: the mixin
processor resolved renderClouds against the real signature at build time, so the
descriptor it recorded says outright whether the long the handler wants was ever
there. The patched copy is then checked to differ from the published one in
exactly one entry.

NOTHING IS SENT WITHOUT --publish.

    python tools/repair_cloudmixin.py plan
    python tools/repair_cloudmixin.py repair --publish
"""
import io
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from modrinth import (API, PROJECT_ID, session, existing, sha512,  # noqa: E402
                      placeholder_jar, HERE)

BROKEN_ON = '1.21.10'
MIXIN = 'CloudTimeMixin'
# The config's name is read from the jar, not assumed. Assuming it cost a whole
# round of this: the Fabric jars call it snapmatica.client.mixins.json, the
# loader ports call it snapmatica.mixins.json, and a checker that cannot find
# the file it is looking for must say so rather than report "clean".
STAGE = os.path.join(HERE, '.repair-1.21.10')


def fetch(s, url, dest):
    r = s.get(url, timeout=300)
    if r.status_code >= 300:
        sys.exit('download failed: %s %s' % (r.status_code, url))
    with open(dest, 'wb') as f:
        f.write(r.content)
    return dest


def config_name(z):
    """The mixin config in this jar. Absence or ambiguity is an error, not a pass."""
    cands = [n for n in z.namelist()
             if n.endswith('.mixins.json') and '/' not in n]
    if len(cands) != 1:
        sys.exit('expected exactly one *.mixins.json at the jar root, found %s' % cands)
    return cands[0]


def diagnose(path):
    """(config name, cfg, verdict) -- is this jar one of the broken ones?

    The evidence is in the refmap the mixin processor wrote at build time. It
    resolved renderClouds against the real signature for the version being
    built, so the descriptor it recorded says outright whether the long the
    handler wants was ever there:

      1.21.10  method_62168(I, class_4063, F, class_243,    F)V   <- no J
      1.21.11  method_62168(I, class_4063, F, class_243, J, F)V

    A mixin whose target has no argument of the handler's type cannot apply,
    and defaultRequire = 1 turns that into a crash.
    """
    with zipfile.ZipFile(path) as z:
        cname = config_name(z)
        cfg = json.loads(z.read(cname))
        listed = MIXIN in cfg.get('client', []) + cfg.get('mixins', [])
        if not listed:
            return cname, cfg, 'not listed -- nothing to repair'
        ref = cfg.get('refmap')
        if not ref or ref not in z.namelist():
            sys.exit('%s: %s is listed but there is no refmap (%r) to judge it by'
                     % (os.path.basename(path), MIXIN, ref))
        key = '%s/%s' % (cfg['package'].replace('.', '/'), MIXIN)
        entry = json.loads(z.read(ref)).get('mappings', {}).get(key)
        if not entry:
            return cname, cfg, 'no refmap entry -- injects nothing here, already fine'
        desc = entry.get('renderClouds', '')
        if 'J' in desc[desc.index('('):desc.rindex(')')] if '(' in desc else False:
            return cname, cfg, 'target takes the tick count -- already fine'
        return cname, cfg, 'BROKEN'


def patch(src, dest, cname):
    """Copy the jar, dropping MIXIN from the mixin config. Everything else as-is."""
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dest, 'w') as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if info.filename == cname:
                cfg = json.loads(data)
                for key in ('client', 'mixins', 'server'):
                    if key in cfg:
                        cfg[key] = [m for m in cfg[key] if m != MIXIN]
                data = (json.dumps(cfg, indent=2, ensure_ascii=False) + '\n').encode('utf-8')
            # Keep each entry's own compression so the copy stays a like-for-like jar.
            out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out.compress_type = info.compress_type
            out.external_attr = info.external_attr
            zout.writestr(out, data)


def verify(src, dest, cname):
    """The patched jar must differ from the published one in exactly one entry."""
    with zipfile.ZipFile(src) as a, zipfile.ZipFile(dest) as b:
        na, nb = a.namelist(), b.namelist()
        if na != nb:
            return 'entry list changed'
        differing = [n for n in na if a.read(n) != b.read(n)]
        if differing != [cname]:
            return 'differs in %s, expected only %s' % (differing or 'nothing', cname)
        cfg = json.loads(b.read(cname))
        if MIXIN in cfg.get('client', []) + cfg.get('mixins', []):
            return '%s is still listed' % MIXIN
    return None


def plan():
    s = session()
    os.makedirs(STAGE, exist_ok=True)
    rows = []
    for v in sorted(existing(s), key=lambda x: x['date_published']):
        if BROKEN_ON not in v['game_versions']:
            continue
        f = v['files'][0]
        src = os.path.join(STAGE, 'published-' + f['filename'])
        if not os.path.isfile(src):
            fetch(s, f['url'], src)
        cname, cfg, verdict = diagnose(src)
        if verdict != 'BROKEN':
            rows.append((v, f, None, verdict))
            continue
        dest = os.path.join(STAGE, f['filename'])
        patch(src, dest, cname)
        bad = verify(src, dest, cname)
        rows.append((v, f, None if bad else dest, bad or 'BROKEN -> repaired'))
    return rows


def show(rows):
    todo = [r for r in rows if r[2]]
    print('REPAIR -- %d of %d published %s jars would be replaced in place\n'
          % (len(todo), len(rows), BROKEN_ON))
    for v, f, dest, note in rows:
        print('  %-24s  DL %-4s %s' % (v['version_number'], v['downloads'], note))
        if dest:
            print('      old sha512  %s...' % f['hashes']['sha512'][:32])
            print('      new sha512  %s...' % sha512(dest)[:32])
    print('\nThe version id, publication date and download count are kept; only the\n'
          'file behind them changes.')


def repair(rows):
    s = session()
    ph = placeholder_jar()
    ph_sha = sha512(ph)

    def attach(vid, path, filename, label):
        with open(path, 'rb') as fh:
            r = s.post('%s/version/%s/file' % (API, vid),
                       data={'data': json.dumps({'file_parts': ['file']})},
                       files={'file': (filename, fh, 'application/java-archive')},
                       timeout=300)
        if r.status_code >= 300:
            sys.exit('FAILED (%s) on %s: %s %s' % (label, vid, r.status_code, r.text[:400]))

    def drop(vid, sha, label):
        r = s.delete('%s/version_file/%s' % (API, sha),
                     params={'algorithm': 'sha512', 'version_id': vid}, timeout=60)
        if r.status_code >= 300:
            sys.exit('FAILED (%s) on %s: %s %s' % (label, vid, r.status_code, r.text[:400]))

    for v, f, dest, note in rows:
        if not dest:
            continue
        vid, name = v['id'], f['filename']
        hold = name[:-4] + '.swap.jar'
        attach(vid, ph, hold, 'placeholder in')
        drop(vid, f['hashes']['sha512'], 'old jar out')
        attach(vid, dest, name, 'replacement in')
        drop(vid, ph_sha, 'placeholder out')
        print('  repaired %s' % v['version_number'])


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else 'plan'
    publish = '--publish' in sys.argv
    rows = plan()
    if action == 'plan' or not publish:
        if action != 'plan':
            print('--publish not given, so nothing was sent. This is what it would do:\n')
        show(rows)
        return 0
    repair(rows)
    return 0


if __name__ == '__main__':
    sys.exit(main())
