#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Check every mixin target in this tree against the game jar it builds against.

A mixin that names something the game does not have compiles, packages and
passes every check that reads source -- and then fails at APPLY time, which with
defaultRequire = 1 is a crash before the title screen. The Fabric tree has a
yarn-mappings version of this (snapmatica/tools/verify_mixins.py); this one asks
the Mojang-named jar ModDevGradle already produced, via javap, so there is no
mapping to fetch or trust.

It exists because the 1.21.4 port carried getBasicProjectionMatrix -- a yarn
name -- in a @Inject method string. The compiler has no opinion on a string.

Checks:
  * @Mixin(X.class) / @Mixin(targets = "...") -- the class exists
    (remap = false targets are third-party and listed as unchecked)
  * method = "name" / "name(desc)ret" -- that method exists on the target,
    walking superclasses
  * @At(target = "Lowner;name(desc)ret") -- owner and member exist
  * @ModifyVariable(argsOnly = true, ordinal = N) -- the target really takes
    N+1 arguments of the handler's type
  * @Shadow fields and methods -- exist on the target or a superclass

    python tools/verify_mixins.py
"""
import glob
import io
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MIXIN_DIR = os.path.join(ROOT, 'src', 'main', 'java', 'dev', 'shunti', 'snapmatica',
                         'client', 'mixin')

JAVA_TO_DESC = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C',
                'short': 'S', 'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D'}


def game_jar():
    jars = glob.glob(os.path.join(ROOT, 'build', 'moddev', 'artifacts', '*-merged.jar'))
    if len(jars) != 1:
        sys.exit('expected one *-merged.jar under build/moddev/artifacts (run a build '
                 'first), found %s' % jars)
    return jars[0]


_cache = {}


def members(jar, cls):
    """{'methods', 'fields', 'abstract', 'bridge': {(name, desc)}, 'super': dotted} or None."""
    if cls in _cache:
        return _cache[cls]
    # -v rather than -s: the access flags are what say a method is a synthetic
    # bridge (javac's erased twin of a generic override) or abstract, and both
    # matter to where an @Inject can land.
    r = subprocess.run(['javap', '-p', '-v', '-cp', jar, cls],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.returncode != 0 or not r.stdout.strip():
        _cache[cls] = None
        return None
    out = {'methods': set(), 'fields': set(), 'super': None,
           'abstract': set(), 'bridge': set()}
    lines = r.stdout.split('\n')
    head = next((l for l in lines if re.search(r'\b(class|interface|enum|record)\b', l)), '')
    sm = re.search(r'\bextends\s+([\w.$]+)', head)
    if sm and 'interface' not in head:
        out['super'] = sm.group(1)
    sig = None
    last = None
    for l in lines:
        s = l.strip()
        if s.startswith('flags:') and last:
            if 'ACC_BRIDGE' in s:
                out['bridge'].add(last)
            if 'ACC_ABSTRACT' in s:
                out['abstract'].add(last)
            last = None
            continue
        if s.startswith('descriptor:'):
            if sig is None:
                continue
            d = s.split(':', 1)[1].strip()
            nm = re.search(r'([\w$<>]+)\s*\(', sig)
            if d.startswith('(') and nm:
                name = nm.group(1).rsplit('.', 1)[-1]
                if name == cls.rsplit('.', 1)[-1].split('$')[-1]:
                    name = '<init>'
                out['methods'].add((name, d))
                last = (name, d)
            else:
                fm = re.search(r'([\w$]+)\s*;\s*$', sig)
                if fm:
                    out['fields'].add((fm.group(1), d))
            sig = None
        elif s and not s.startswith(('Compiled from', '}')) and s.endswith(';'):
            sig = s
    _cache[cls] = out
    return out


def all_members(jar, cls, kind):
    seen, cur = set(), cls
    while cur and cur not in ('java.lang.Object',):
        m = members(jar, cur)
        if m is None:
            break
        seen |= m[kind]
        cur = m['super']
    return seen


def desc_args(desc):
    body = desc[desc.index('(') + 1:desc.rindex(')')]
    args, i = [], 0
    while i < len(body):
        j = i
        while body[j] == '[':
            j += 1
        if body[j] == 'L':
            j = body.index(';', j)
        args.append(body[i:j + 1])
        i = j + 1
    return args


MIXIN_RE = re.compile(r'@Mixin\s*\(\s*(?:targets\s*=\s*"([^"]+)"|([\w.$]+)\.class)([^)]*)\)')
IMPORT_RE = re.compile(r'^import\s+([\w.$]+)\s*;', re.M)
INJECT_RE = re.compile(
    r'@(Inject|Redirect|ModifyVariable|ModifyArg|ModifyArgs|ModifyConstant|'
    r'ModifyExpressionValue|WrapOperation|WrapWithCondition)\s*\((.*?)\)\s*\n\s*'
    # handler names carry the mod's prefix, snapmatica$..., and $ is not in \w
    r'(?:private|public|protected)?\s*(?:static\s+)?([\w.$<>\[\], ]+?)\s+([\w$]+)\s*\(',
    re.S)
SHADOW_RE = re.compile(r'@Shadow\s+(?:@\w+\s+)*(?:(?:private|public|protected|final|static|'
                       r'abstract)\s+)*([\w.$<>\[\]]+)\s+([\w$]+)\s*([(;=])')


def fq(simple, imports, pkg):
    if '.' in simple:
        return simple
    for imp in imports:
        if imp.rsplit('.', 1)[-1] == simple:
            return imp
    return None


def check(path, jar):
    src = io.open(path, encoding='utf-8').read()
    name = os.path.basename(path)
    probs, notes = [], []
    m = MIXIN_RE.search(src)
    if not m:
        return probs, notes
    targets, cls, tail = m.group(1), m.group(2), m.group(3) or ''
    if re.search(r'remap\s*=\s*false', tail):
        notes.append('%s: remap = false (%s) -- third-party, not in the game jar'
                     % (name, targets or cls))
        return probs, notes
    imports = IMPORT_RE.findall(src)
    owner = targets.replace('/', '.') if targets else fq(cls, imports, None)
    if not owner:
        probs.append('%s: cannot resolve @Mixin(%s) to a class' % (name, cls))
        return probs, notes
    if members(jar, owner) is None:
        probs.append('%s: @Mixin target %s does not exist' % (name, owner))
        return probs, notes
    methods = all_members(jar, owner, 'methods')
    abstract = members(jar, owner)['abstract']
    bridge = members(jar, owner)['bridge']
    fields = all_members(jar, owner, 'fields')
    by_name = {}
    for n, d in methods:
        by_name.setdefault(n, []).append(d)

    for anno, body, ret, handler in INJECT_RE.findall(src):
        mm = re.search(r'method\s*=\s*"([^"]+)"', body)
        if mm:
            spec = mm.group(1)
            tname, _, rest = spec.partition('(')
            tdesc = '(' + rest if rest else ''
            if tname not in by_name:
                probs.append('%s: %s targets %s.%s, which does not exist'
                             % (name, anno, owner.rsplit('.', 1)[-1], tname))
                continue
            cands = by_name[tname]
            # Two names in yarn can be one name in Mojang's mappings. On 1.21.4
            # getAndUpdateRenderState(Entity, float) and createRenderState() are
            # both createRenderState, and a bare method = "createRenderState"
            # landed on the ABSTRACT one -- no instructions to inject into, and
            # mixin died with a NullPointerException before the title screen.
            # A name that is overloaded on the target has to say which.
            # Bridges do not count: mixin passes over synthetic methods when it
            # matches by name, which is why renderNameTag on PlayerRenderer has
            # always worked on 1.21.1 despite its erased twin.
            real = sorted(set(d for d in cands if (tname, d) not in bridge))
            if not tdesc and len(real) > 1:
                probs.append('%s: %s targets %s by name alone, but %s has %d overloads: %s'
                             ' -- give the descriptor'
                             % (name, anno, tname, owner.rsplit('.', 1)[-1],
                                len(real), ' / '.join(real)))
                continue
            if tdesc and tdesc not in cands:
                probs.append('%s: %s targets %s%s; the jar has %s'
                             % (name, anno, tname, tdesc, ' / '.join(cands)))
                continue
            if tdesc:
                cands = [tdesc]
            if anno not in ('Redirect',) and all((tname, d) in abstract for d in cands):
                probs.append('%s: %s targets %s%s, which is abstract -- nothing to inject into'
                             % (name, anno, tname, cands[0]))
            if anno == 'ModifyVariable' and re.search(r'argsOnly\s*=\s*true', body):
                want = JAVA_TO_DESC.get(ret.strip())
                if want is None:
                    r = fq(ret.strip(), imports, None)
                    want = 'L%s;' % r.replace('.', '/') if r else None
                om = re.search(r'ordinal\s*=\s*(\d+)', body)
                k = int(om.group(1)) if om else 0
                if want and not any(desc_args(d).count(want) > k for d in cands):
                    probs.append('%s: %s wants argument #%d of type %s in %s, which takes %s'
                                 % (name, anno, k, want, tname,
                                    ' / '.join('(' + ','.join(desc_args(d)) + ')'
                                               for d in cands)))
        at = re.search(r'target\s*=\s*"L([\w/$]+);([\w$<>]+)(\([^"]*\)[^"]*)?"', body)
        if at:
            aowner = at.group(1).replace('/', '.')
            aname, adesc = at.group(2), at.group(3)
            if members(jar, aowner) is None:
                probs.append('%s: @At target class %s does not exist' % (name, aowner))
            else:
                hits = [d for n, d in all_members(jar, aowner, 'methods') if n == aname]
                if not hits:
                    probs.append('%s: @At targets %s.%s, which does not exist'
                                 % (name, aowner.rsplit('.', 1)[-1], aname))
                elif adesc and adesc not in hits:
                    probs.append('%s: @At targets %s%s; the jar has %s'
                                 % (name, aname, adesc, ' / '.join(hits)))

    for typ, sname, kind in SHADOW_RE.findall(src):
        pool = methods if kind == '(' else fields
        if not any(n == sname for n, _ in pool):
            probs.append('%s: @Shadow %s %s is not on %s or its superclasses'
                         % (name, 'method' if kind == '(' else 'field', sname,
                            owner.rsplit('.', 1)[-1]))

    for inv, tname in re.findall(r'@(Invoker|Accessor)\s*\(\s*"([^"]+)"', src):
        pool = methods if inv == 'Invoker' else fields
        if not any(n == tname for n, _ in pool):
            probs.append('%s: @%s("%s") is not on %s' % (name, inv, tname,
                                                         owner.rsplit('.', 1)[-1]))
    return probs, notes


def main():
    jar = game_jar()
    files = sorted(glob.glob(os.path.join(MIXIN_DIR, '*.java')))
    print('%d mixins against %s' % (len(files), os.path.basename(jar)))
    bad = 0
    for f in files:
        p, n = check(f, jar)
        for x in n:
            print('  .. ' + x)
        for x in p:
            print('  !! ' + x)
        bad += len(p)
    print('\n%s' % ('OK -- every name these mixins use exists in the game jar'
                    if not bad else '%d problem(s)' % bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
