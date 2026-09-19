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
SIBLING263 = os.path.join(os.path.dirname(ROOT), 'snapmatica263')

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
    ('26.3',          ['26.3'],             os.path.join(SIBLING263, 'build/libs')),
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
    """Every jar for a release, or an explanation of which one is missing."""
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


def swap_plan(s, version):
    """Which published files differ from what is built here, for a version already out."""
    pub = {v['version_number']: v for v in existing(s)
           if v['version_number'].startswith(version + '+')}
    out = []
    for suffix, _, d in JARS:
        num = '%s+%s' % (version, suffix)
        if num not in pub:
            sys.exit('%s is not published; use "release", not "swap".' % num)
        p = os.path.join(d, 'snapmatica-%s.jar' % num)
        if not os.path.isfile(p):
            sys.exit('not built: ' + p)
        v = pub[num]
        f = v['files'][0]
        new = sha512(p)
        if new != f['hashes']['sha512']:
            out.append({'version': v, 'path': p, 'old_sha': f['hashes']['sha512'],
                        'new_sha': new, 'name': os.path.basename(p)})
    return out


def show_swap(version):
    s = session(required=False)
    plan = swap_plan(s, version)
    print('SWAP %s -- %d published files would be REPLACED in place\n' % (version, len(plan)))
    print('The version entries, their numbers, dates and download counts survive; only the')
    print('attached jar changes. Anyone who already downloaded keeps what they have.\n')
    for e in plan:
        print('  %-22s  %s' % (e['version']['version_number'], e['name']))
        print('    was %s...  (%d downloads)' % (e['old_sha'][:24], e['version']['downloads']))
        print('    now %s...' % e['new_sha'][:24])
    if not plan:
        print('  nothing differs; the published files already match this build.')


def placeholder_jar():
    """A tiny real jar whose only job is to hold a version's file slot open.

    Needed because Modrinth forbids both halves of the obvious swap: uploading the
    replacement first fails with "Duplicate files are not allowed" (the filename is
    already taken), and deleting the old one first fails with "Versions must have at
    least one file uploaded to them". So something has to occupy the slot in between.

    It must be a DISTINCT file, not another copy of the replacement, and that is the
    part that is easy to get wrong: files are deleted by hash, so two attachments
    with identical bytes cannot be told apart and the delete removes whichever the
    server picks. Doing exactly that once left a version carrying the right jar under
    the wrong name.
    """
    import zipfile
    p = os.path.join(HERE, '.swap-placeholder.jar')
    if not os.path.isfile(p):
        with zipfile.ZipFile(p, 'w') as z:
            z.writestr('META-INF/MANIFEST.MF',
                       'Manifest-Version: 1.0\n'
                       'Comment: transient placeholder, held for seconds during a file swap\n')
    return p


def do_swap(version):
    """Replace each published jar in place, keeping the version, its date and its downloads.

    Four calls per version, and the order is forced by the two rules above:

        1. attach the placeholder under a name of its own   -> 2 files
        2. delete the old jar by its hash                   -> 1 file
        3. attach the replacement under the real name       -> 2 files
        4. delete the placeholder by ITS hash               -> 1 file

    Never fewer than one file, and every hash in play is distinct, so no delete is
    ever ambiguous. A failure part-way leaves the version carrying two jars or a
    placeholder, both of which are visible in the API and fixable by rerunning.
    """
    s = session()
    ph = placeholder_jar()
    ph_sha = sha512(ph)

    def attach(vid, path, filename, label):
        with open(path, 'rb') as fh:
            r = s.post('%s/version/%s/file' % (API, vid),
                       data={'data': json.dumps({'file_parts': ['file']})},
                       files={'file': (filename, fh, 'application/java-archive')},
                       timeout=180)
        if r.status_code >= 300:
            sys.exit('FAILED (%s) on %s: %s %s' % (label, vid, r.status_code, r.text[:400]))

    def drop(vid, sha, label):
        r = s.delete('%s/version_file/%s' % (API, sha),
                     params={'algorithm': 'sha512', 'version_id': vid}, timeout=60)
        if r.status_code >= 300:
            sys.exit('FAILED (%s) on %s: %s %s' % (label, vid, r.status_code, r.text[:400]))

    for e in swap_plan(s, version):
        vid = e['version']['id']
        hold = e['name'][:-4] + '.swap.jar'
        attach(vid, ph, hold, 'placeholder in')
        drop(vid, e['old_sha'], 'old jar out')
        attach(vid, e['path'], e['name'], 'replacement in')
        drop(vid, ph_sha, 'placeholder out')
        print('  swapped %s' % e['version']['version_number'])


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
    ap.add_argument('action', choices=['plan', 'plan-repair', 'plan-swap', 'release',
                                       'repair-deps', 'repair-changelogs', 'swap'])
    ap.add_argument('version', nargs='?')
    ap.add_argument('--publish', action='store_true',
                    help='actually send. Without it nothing leaves this machine.')
    a = ap.parse_args()

    if a.action in ('plan', 'release', 'plan-swap', 'swap') and not a.version:
        sys.exit('which version?  e.g.  python tools/modrinth.py plan 1.3.1')

    if a.action == 'plan':
        show_release(a.version)
    elif a.action == 'plan-repair':
        show_repair()
    elif a.action == 'plan-swap':
        show_swap(a.version)
    elif not a.publish:
        print('--publish not given, so nothing was sent. This is what it would do:\n')
        if a.action == 'release':
            show_release(a.version)
        elif a.action == 'swap':
            show_swap(a.version)
        else:
            show_repair()
    elif a.action == 'release':
        do_release(a.version)
    elif a.action == 'swap':
        do_swap(a.version)
    elif a.action == 'repair-deps':
        do_repair('deps')
    else:
        do_repair('changelogs')


if __name__ == '__main__':
    main()
