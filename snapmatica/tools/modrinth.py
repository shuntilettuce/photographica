#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Publish Snapmatica to Modrinth, and repair what earlier releases left out.

Why a script and not the Minotaur Gradle plugin, which is the usual choice for a
Fabric mod:

  * Minotaur only CREATES versions. Fifty-four of the sixty-seven versions already
    published declare no Fabric API dependency and most carry no changelog, and
    fixing those needs PATCH on an existing version -- which Minotaur has no way
    to express.
  * Publishing stays out of the build. With Minotaur the upload is a Gradle task
    living beside `build`, and the failure mode is a stray invocation putting a
    release on the internet. Here nothing is sent without --publish.
  * The seven Stonecutter targets need no per-target configuration: the jar
    filenames already carry the game versions, and JARS below is the whole map.

NOTHING IS SENT WITHOUT --publish. The default prints exactly what would go, and
that is the form to read before agreeing to any of it.

The token is read from MODRINTH_TOKEN and is never printed, logged, or written
anywhere. Set it in the environment; do not put it in a file in this repository.

    python tools/modrinth.py plan          1.3.1     # what a release would send
    python tools/modrinth.py plan-repair             # what the backfill would change
    python tools/modrinth.py release       1.3.1 --publish
    python tools/modrinth.py repair-deps             --publish
    python tools/modrinth.py repair-changelogs       --publish
"""
import argparse
import hashlib
import io
import json
import os
import sys

import requests

PROJECT_ID = 'NbrQEYR9'          # Snapmatica
FABRIC_API = 'P7dR8mSH'
API = 'https://api.modrinth.com/v2'
UA = 'shuntilettuce/snapmatica-publisher (sunnyhorse.minecraft@gmail.com)'

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)                       # .../snapmatica
SIBLING26 = os.path.join(os.path.dirname(ROOT), 'snapmatica26')

# jar suffix -> the game versions that jar is for. The suffix is what Stonecutter
# already writes into the filename, so this is the only place the matrix is stated.
JARS = [
    ('1.20.1',        ['1.20.1'],           os.path.join(ROOT, 'versions/1.20.1/build/libs')),
    ('1.21-1.21.1',   ['1.21', '1.21.1'],   os.path.join(ROOT, 'versions/1.21.1/build/libs')),
    ('1.21.2-1.21.3', ['1.21.2', '1.21.3'], os.path.join(ROOT, 'versions/1.21.3/build/libs')),
    ('1.21.4',        ['1.21.4'],           os.path.join(ROOT, 'versions/1.21.4/build/libs')),
    ('1.21.10',       ['1.21.10'],          os.path.join(ROOT, 'versions/1.21.10/build/libs')),
    ('1.21.11',       ['1.21.11'],          os.path.join(ROOT, 'versions/1.21.11/build/libs')),
    ('26.1.2',        ['26.1.2'],           os.path.join(SIBLING26, 'build/libs')),
]


def changelogs():
    with io.open(os.path.join(HERE, 'changelogs.json'), encoding='utf-8') as f:
        d = json.load(f)
    d.pop('_note', None)
    return d


# Outside the repository on purpose: a token stored inside a git tree is a token that
# gets committed eventually. Nothing below ever prints, logs or copies its contents.
TOKEN_FILE = os.path.join(os.path.expanduser('~'), '.snapmatica-modrinth-token')


def token(required):
    """The token, from the environment or from a file only the user writes.

    A file rather than a command-line argument or a `setx`: a secret passed on a
    command line lands in shell history and is visible to anything that can list
    processes, while a file is read once by this process and never leaves it.
    """
    t = os.environ.get('MODRINTH_TOKEN', '').strip()
    if not t and os.path.isfile(TOKEN_FILE):
        with io.open(TOKEN_FILE, encoding='utf-8') as f:
            t = f.read().strip()
    if not t and required:
        sys.exit('No Modrinth token.\n\n'
                 'Put it on the single line of %s,\n'
                 'or set MODRINTH_TOKEN in the environment.\n\n'
                 'Do not paste it into a chat window, a commit, or a command line.'
                 % TOKEN_FILE)
    return t


def session(required=True):
    s = requests.Session()
    s.headers['User-Agent'] = UA
    t = token(required)
    if t:
        s.headers['Authorization'] = t
    return s


def existing(s):
    r = s.get('%s/project/%s/version' % (API, PROJECT_ID), timeout=30)
    r.raise_for_status()
    return r.json()


def sha512(path):
    h = hashlib.sha512()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def collect(version):
    """The seven jars for a release, or an explanation of which one is missing."""
    out, missing = [], []
    for suffix, game_versions, d in JARS:
        name = 'snapmatica-%s+%s.jar' % (version, suffix)
        p = os.path.join(d, name)
        (out if os.path.isfile(p) else missing).append(
            {'suffix': suffix, 'game_versions': game_versions, 'path': p, 'name': name})
    if missing:
        sys.exit('not built:\n' + '\n'.join('  ' + m['path'] for m in missing))
    return out


def payloads(version):
    logs = changelogs()
    out = []
    for i, j in enumerate(collect(version)):
        number = '%s+%s' % (version, j['suffix'])
        out.append({
            'name': number,
            'version_number': number,
            # Only the lead entry carries the text, which is the shape the project
            # already has -- the same changelog repeated seven times reads as noise
            # on a version list where all seven land the same minute.
            'changelog': logs.get(number, '') if i == 0 else '',
            'dependencies': [{'project_id': FABRIC_API, 'dependency_type': 'required'}],
            'game_versions': j['game_versions'],
            'version_type': 'beta' if ('beta' in version or 'alpha' in version) else 'release',
            'loaders': ['fabric'],
            'featured': False,
            'project_id': PROJECT_ID,
            'file_parts': ['file'],
            'primary_file': 'file',
        }, )
        out[-1]['_path'] = j['path']
    return out


def show_release(version):
    ps = payloads(version)
    print('RELEASE %s -- %d version entries would be CREATED on Modrinth\n' % (version, len(ps)))
    for p in ps:
        print('  %-24s %-9s %-22s %s' % (p['version_number'], p['version_type'],
                                         ','.join(p['game_versions']), ','.join(p['loaders'])))
        print('    file      %s' % os.path.basename(p['_path']))
        print('    sha512    %s' % sha512(p['_path'])[:32] + '...')
        print('    depends   Fabric API (required)')
        if p['changelog']:
            print('    changelog:')
            for line in p['changelog'].split('\n'):
                print('      | ' + line)
        print()


def do_release(version):
    s = session()
    have = {v['version_number'] for v in existing(s)}
    ps = payloads(version)
    clash = [p['version_number'] for p in ps if p['version_number'] in have]
    if clash:
        sys.exit('already published, refusing to duplicate:\n  ' + '\n  '.join(clash))
    for p in ps:
        path = p.pop('_path')
        with open(path, 'rb') as fh:
            r = s.post('%s/version' % API,
                       data={'data': json.dumps(p)},
                       files={'file': (os.path.basename(path), fh, 'application/java-archive')},
                       timeout=180)
        if r.status_code >= 300:
            sys.exit('FAILED on %s: %s %s' % (p['version_number'], r.status_code, r.text[:400]))
        print('  published %s' % p['version_number'])


def repair_plan(s):
    """What the backfill would touch, computed from what is actually published."""
    vs = existing(s)
    logs = changelogs()
    deps, cls = [], []
    for v in vs:
        if not any(d.get('project_id') == FABRIC_API for d in v.get('dependencies', [])):
            deps.append(v)
        want = logs.get(v['version_number'])
        if want and (v.get('changelog') or '').strip() != want.strip():
            cls.append((v, want))
    return vs, deps, cls


def show_repair():
    s = session(required=False)
    vs, deps, cls = repair_plan(s)
    print('REPAIR -- against the %d versions currently published\n' % len(vs))
    print('1. add the Fabric API dependency to %d versions that declare none:' % len(deps))
    for v in deps:
        print('     %s' % v['version_number'])
    print('\n   (nothing but Fabric API is declared anywhere, so replacing the')
    print('    dependency array destroys no other entry.)\n')
    print('2. rewrite %d changelogs as English then Japanese:' % len(cls))
    for v, want in cls:
        print('\n   --- %s' % v['version_number'])
        old = (v.get('changelog') or '').strip()
        print('   was: %s' % (old.split('\n')[0][:76] if old else '(empty)'))
        print('   now:')
        for line in want.split('\n'):
            print('     | ' + line)


def do_repair(kind):
    s = session()
    vs, deps, cls = repair_plan(s)
    if kind in ('deps', 'all'):
        for v in deps:
            r = s.patch('%s/version/%s' % (API, v['id']), timeout=30, json={
                'dependencies': [{'project_id': FABRIC_API, 'dependency_type': 'required'}]})
            if r.status_code >= 300:
                sys.exit('FAILED deps on %s: %s %s' % (v['version_number'], r.status_code, r.text[:300]))
            print('  deps      %s' % v['version_number'])
    if kind in ('changelogs', 'all'):
        for v, want in cls:
            r = s.patch('%s/version/%s' % (API, v['id']), timeout=30, json={'changelog': want})
            if r.status_code >= 300:
                sys.exit('FAILED changelog on %s: %s %s' % (v['version_number'], r.status_code, r.text[:300]))
            print('  changelog %s' % v['version_number'])


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('action', choices=['plan', 'plan-repair', 'release',
                                       'repair-deps', 'repair-changelogs'])
    ap.add_argument('version', nargs='?')
    ap.add_argument('--publish', action='store_true',
                    help='actually send. Without it nothing leaves this machine.')
    a = ap.parse_args()

    if a.action in ('plan', 'release') and not a.version:
        sys.exit('which version?  e.g.  python tools/modrinth.py plan 1.3.1')

    if a.action == 'plan':
        show_release(a.version)
    elif a.action == 'plan-repair':
        show_repair()
    elif not a.publish:
        print('--publish not given, so nothing was sent. This is what it would do:\n')
        (show_release(a.version) if a.action == 'release' else show_repair())
    elif a.action == 'release':
        do_release(a.version)
    elif a.action == 'repair-deps':
        do_repair('deps')
    else:
        do_repair('changelogs')


if __name__ == '__main__':
    main()
