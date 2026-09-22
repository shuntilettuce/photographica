#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Publish Snapmatica to CurseForge, alongside tools/modrinth.py.

Same shape as modrinth.py and for the same reasons: nothing is sent without
--publish, the token never appears on a command line, and the jar matrix is
stated once. JARS itself is imported from modrinth.py rather than copied, so the
two cannot drift apart.

CurseForge differs from Modrinth in ways that matter here:

  * Projects are created on the website and pass moderation; there is no API for
    it. So is the first upload to a new project. PROJECT_ID below has to be
    filled in by hand once the project exists.
  * Game versions are NUMERIC IDS that must be looked up, and the MODLOADER is
    one of them -- Forge and Fabric sit in the same list as 1.20.1, under a
    different gameVersionTypeID. `versions` prints what the account can see, and
    resolution is by exact name with a hard error on ambiguity, because guessing
    an id here silently publishes a jar against the wrong loader.
  * There is no delete. update-file can edit an uploaded file's metadata, which
    is the nearest thing to Modrinth's PATCH, but the file itself stays.
  * Uploads are queued for approval rather than appearing at once.

    python tools/curseforge.py versions                        # the id tables
    python tools/curseforge.py plan    1.3.2 --loaders forge,neoforge
    python tools/curseforge.py release 1.3.2 --loaders forge,neoforge --publish
"""
import argparse
import io
import json
import os
import sys

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from modrinth import JARS, changelogs, sha512  # noqa: E402

# Filled in once the project exists on the website. There is no API to create it.
PROJECT_ID = None

API = 'https://minecraft.curseforge.com/api'
UA = 'shuntilettuce/snapmatica-publisher (sunnyhorse.minecraft@gmail.com)'

# Modrinth loader name -> the name CurseForge files it under in /game/versions.
LOADER_NAME = {'fabric': 'Fabric', 'forge': 'Forge', 'neoforge': 'NeoForge',
               'quilt': 'Quilt'}
FABRIC_API_SLUG = 'fabric-api'

# Snapmatica draws photographs; there is nothing for it to do on a server.
ENVIRONMENT = 'Client'

TOKEN_FILE = os.path.join(os.path.expanduser('~'), '.snapmatica-curseforge-token')


def token():
    """From a file outside the repository, or the environment. Never from argv.

    A token on a command line is a token in the shell history and in every
    process list on the machine while the upload runs.
    """
    if os.path.isfile(TOKEN_FILE):
        with io.open(TOKEN_FILE, encoding='utf-8') as f:
            t = f.readline().strip()
        if t:
            return t
    t = os.environ.get('CURSEFORGE_TOKEN', '').strip()
    if t:
        return t
    sys.exit('no token: put it on the first line of %s, or set CURSEFORGE_TOKEN.\n'
             'Generate one at https://authors-old.curseforge.com/account/api-tokens'
             % TOKEN_FILE)


def session():
    s = requests.Session()
    s.headers.update({'X-Api-Token': token(), 'User-Agent': UA})
    return s


def game_versions(s):
    r = s.get('%s/game/versions' % API, timeout=60)
    if r.status_code >= 300:
        sys.exit('GET /game/versions failed: %s %s' % (r.status_code, r.text[:300]))
    return r.json()


def resolve(all_versions, name):
    """The single id CurseForge knows `name` by, or an error naming the choices.

    Exact match only, and ambiguity is fatal. A wrong id here does not fail --
    it publishes the jar against the wrong Minecraft version or the wrong
    loader, which is worse than not publishing at all.
    """
    hits = [v for v in all_versions if v['name'] == name]
    if not hits:
        near = sorted({v['name'] for v in all_versions
                       if name.lower() in v['name'].lower()})[:8]
        sys.exit('CurseForge has no game version named %r.%s'
                 % (name, ('  Did you mean: %s' % ', '.join(near)) if near else ''))
    if len(hits) > 1:
        sys.exit('%r is ambiguous on CurseForge -- %s. Narrow it in the script '
                 'by gameVersionTypeID before publishing anything.'
                 % (name, ', '.join('id %s (type %s)' % (h['id'], h['gameVersionTypeID'])
                                    for h in hits)))
    return hits[0]['id']


def collect(version, loaders=None):
    """The jars for this release, or which one is missing."""
    out, missing = [], []
    for suffix, mc_versions, d, ldrs in JARS:
        if loaders and not set(ldrs) & set(loaders):
            continue
        name = 'snapmatica-%s+%s.jar' % (version, suffix)
        p = os.path.join(d, name)
        (out if os.path.isfile(p) else missing).append(
            {'suffix': suffix, 'mc': mc_versions, 'path': p,
             'name': name, 'loaders': ldrs,
             'number': '%s+%s' % (version, suffix)})
    if missing:
        sys.exit('not built:\n' + '\n'.join('  ' + m['path'] for m in missing))
    if not out:
        sys.exit('no jars match --loaders ' + ','.join(loaders or []))
    return out


def payloads(version, all_versions, loaders=None, release_type=None):
    logs = changelogs()
    out = []
    for j in collect(version, loaders):
        names = list(j['mc']) + [LOADER_NAME[l] for l in j['loaders']] + [ENVIRONMENT]
        ids = [resolve(all_versions, n) for n in names] if all_versions else []
        meta = {
            'displayName': j['number'],
            'changelog': logs.get(j['number'], ''),
            'changelogType': 'markdown',
            'releaseType': release_type or (
                'beta' if ('beta' in version or 'alpha' in version) else 'release'),
            'gameVersions': ids,
            # Fabric API exists for the Fabric jars only; declaring it on a Forge
            # file would be a dependency nobody can satisfy.
            'relations': ({'projects': [{'slug': FABRIC_API_SLUG,
                                         'type': 'requiredDependency'}]}
                          if 'fabric' in j['loaders'] else {'projects': []}),
        }
        out.append({'meta': meta, 'path': j['path'], 'names': names,
                    'number': j['number'], 'loaders': j['loaders']})
    return out


def show_versions(s):
    vs = game_versions(s)
    by_type = {}
    for v in vs:
        by_type.setdefault(v['gameVersionTypeID'], []).append(v)
    print('%d game versions visible to this token, in %d types\n' % (len(vs), len(by_type)))
    wanted = set(LOADER_NAME.values()) | {ENVIRONMENT}
    for t, group in sorted(by_type.items()):
        names = [g['name'] for g in group]
        mark = ' <-- loader/environment' if wanted & set(names) else ''
        print('  type %-8s %3d entries%s' % (t, len(group), mark))
        print('      %s' % ', '.join(sorted(names)[:12]))
    print('\nnames this script needs:')
    for n in sorted(wanted):
        hits = [v for v in vs if v['name'] == n]
        print('  %-10s %s' % (n, ', '.join('id %s (type %s)' % (h['id'], h['gameVersionTypeID'])
                                           for h in hits) or 'NOT FOUND'))


def show_release(version, ps):
    print('RELEASE %s -- %d file(s) would be UPLOADED to CurseForge project %s\n'
          % (version, len(ps), PROJECT_ID))
    for p in ps:
        m = p['meta']
        print('  %-24s %-8s %s' % (p['number'], m['releaseType'], ','.join(p['names'])))
        print('    file      %s' % os.path.basename(p['path']))
        print('    sha512    %s...' % sha512(p['path'])[:32])
        print('    ids       %s' % (m['gameVersions'] or '(not resolved -- no token)'))
        print('    depends   %s' % (', '.join(r['slug'] for r in m['relations']['projects'])
                                    or 'none'))
        if m['changelog']:
            print('    changelog:')
            for line in m['changelog'].split('\n'):
                print('      | ' + line)
        print()
    print('Uploads are queued for CurseForge approval; they do not appear at once.')


def do_release(s, ps):
    for p in ps:
        with open(p['path'], 'rb') as fh:
            r = s.post('%s/projects/%s/upload-file' % (API, PROJECT_ID),
                       data={'metadata': json.dumps(p['meta'])},
                       files={'file': (os.path.basename(p['path']), fh,
                                       'application/java-archive')},
                       timeout=300)
        if r.status_code >= 300:
            sys.exit('FAILED on %s: %s %s' % (p['number'], r.status_code, r.text[:400]))
        print('  uploaded %s -> file id %s' % (p['number'], r.json().get('id')))


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('action', choices=['versions', 'plan', 'release'])
    ap.add_argument('version', nargs='?')
    ap.add_argument('--loaders', default=None,
                    help='comma-separated: fabric,forge,neoforge. Default: all of them.')
    ap.add_argument('--release-type', choices=['release', 'beta', 'alpha'], default=None)
    ap.add_argument('--publish', action='store_true',
                    help='actually send. Without it nothing leaves this machine.')
    a = ap.parse_args()
    loaders = [x.strip() for x in a.loaders.split(',')] if a.loaders else None

    if a.action == 'versions':
        show_versions(session())
        return 0

    if not a.version:
        sys.exit('which version?  e.g.  python tools/curseforge.py plan 1.3.2')
    if PROJECT_ID is None:
        sys.exit('PROJECT_ID is not set. Create the project on curseforge.com first '
                 '(there is no API for it, and it has to clear moderation), then put '
                 'its numeric id at the top of this script.')

    # Resolving ids needs the token, so a plan without one still prints the shape.
    all_versions = None
    if os.path.isfile(TOKEN_FILE) or os.environ.get('CURSEFORGE_TOKEN'):
        all_versions = game_versions(session())
    ps = payloads(a.version, all_versions, loaders, a.release_type)

    if a.action == 'plan' or not a.publish:
        if a.action == 'release':
            print('--publish not given, so nothing was sent. This is what it would do:\n')
        show_release(a.version, ps)
        return 0
    if all_versions is None:
        sys.exit('refusing to upload without resolved game version ids.')
    do_release(session(), ps)
    return 0


if __name__ == '__main__':
    sys.exit(main())
