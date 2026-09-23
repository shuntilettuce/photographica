#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Check every mixin target against the mappings for every version we ship.

A mixin that names something the game does not have fails at APPLY time, which
with `defaultRequire: 1` is a crash before the title screen -- and it crashes
only on the versions where the name is missing, so building and starting one
version proves nothing about the others. 1.21.10 shipped broken ten times over
because of exactly that: CloudRenderer exists there, so the target class checked
out, but the tick count is not one of renderClouds' arguments until 1.21.11.

  1.21.10  renderClouds(I, CloudRenderMode, F, Vec3d,    F)V
  1.21.11  renderClouds(I, CloudRenderMode, F, Vec3d, J, F)V

So this resolves the Stonecutter blocks for each version in turn and asks the
yarn mappings for that version whether every name the mixins use is really
there. It is faster than starting one client, let alone six.

What it checks:
  * @Mixin(X.class) -- the target class exists
  * method = "name" / "name(desc)ret" -- that method exists on the target
  * @At(target = "Lowner;name(desc)ret") -- owner and member exist
  * @ModifyVariable(argsOnly = true, ordinal = N) -- the target really takes
    N+1 arguments of the handler's type. This is the one that catches 1.21.10.

What it does NOT check: @Shadow and @Accessor/@Invoker names, because a mixin
may legitimately shadow an INHERITED member and these mappings are per-class
with no hierarchy. Those are listed as unchecked rather than guessed at.

    python tools/verify_mixins.py              # every version in the matrix
    python tools/verify_mixins.py 1.21.10      # just one
"""
import io
import json
import os
import re
import sys
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MIXIN_DIR = os.path.join(ROOT, 'src/main/java/dev/shunti/snapmatica/client/mixin')
CACHE = os.path.join(HERE, 'cache')

PRIMS = set('VZBCSIJFD')


# ── Stonecutter ────────────────────────────────────────────────────────────────
IF_RE = re.compile(r"^(\s*)//\? (?:if|else if) (.*?)\s*\{$")
ELSEIF_RE = re.compile(r"^(\s*)(?:\*/)?//\?\}\s*(?:else if|elif)\s+(.*?)\s*\{$")
ELSE_RE = re.compile(r"^(\s*)(?:\*/)?//\?\}\s*else\s*\{$")
END_RE = re.compile(r"^(\s*)(?:\*/)?//\?\}\s*$")


def version_tuple(t):
    return tuple(int(p) for p in t.split('.'))


def eval_cond(cond, target):
    m = re.match(r"^(>=|<=|>|<|==)?\s*([\d.]+)$", cond.strip())
    if not m:
        raise ValueError('unsupported condition %r' % cond)
    op, ver = m.groups()
    v = version_tuple(ver)
    t = target[:len(v)]
    return {'<': t < v, '<=': t <= v, '>': t > v, '>=': t >= v,
            '==': t == v, None: t == v}[op]


def resolve(text, target):
    """Keep the branch that applies to `target`, drop the rest.

    The inactive branch is wrapped in /* */ in the source, so the kept text is
    uncommented and the dropped text goes entirely.
    """
    lines = text.split('\n')
    out, stack = [], []          # stack of (taken_already, this_branch_live)
    for line in lines:
        m = IF_RE.match(line)
        if m:
            live = eval_cond(m.group(2), target)
            stack.append([live, live])
            continue
        m = ELSEIF_RE.match(line) or ELSE_RE.match(line)
        if m and stack:
            taken = stack[-1][0]
            live = (not taken) if ELSE_RE.match(line) else \
                   ((not taken) and eval_cond(m.group(2), target))
            stack[-1] = [taken or live, live]
            continue
        if END_RE.match(line) and stack:
            stack.pop()
            continue
        if all(s[1] for s in stack):
            out.append(line)
    text = '\n'.join(out)
    # A kept branch that was the commented-out one loses its wrapper.
    text = re.sub(r'^\s*/\*(?=\S)', '', text, flags=re.M)
    text = re.sub(r'\*/\s*$', '', text, flags=re.M)
    return text


# ── yarn mappings ──────────────────────────────────────────────────────────────
def yarn_tiny(yarn_version):
    os.makedirs(CACHE, exist_ok=True)
    dest = os.path.join(CACHE, 'yarn-%s.tiny' % yarn_version)
    if os.path.isfile(dest) and os.path.getsize(dest) > 0:
        return io.open(dest, encoding='utf-8').read()
    url = ('https://maven.fabricmc.net/net/fabricmc/yarn/%s/yarn-%s-v2.jar'
           % (yarn_version, yarn_version))
    print('  fetching %s' % url)
    req = urllib.request.Request(url, headers={'User-Agent': 'snapmatica-verify/1.0'})
    raw = urllib.request.urlopen(req, timeout=300).read()
    text = zipfile.ZipFile(io.BytesIO(raw)).read('mappings/mappings.tiny').decode('utf-8')
    io.open(dest, 'w', encoding='utf-8', newline='\n').write(text)
    return text


CLS_IN_DESC = re.compile(r'L([^;]+);')


def load_mappings(yarn_version):
    """{namedClass: {'methods': {(name, namedDesc), ...}, 'fields': {...}}}

    Descriptors in the tiny file are written in the FIRST namespace
    (intermediary), so they are rewritten into named before anyone compares
    them with what a mixin wrote.
    """
    text = yarn_tiny(yarn_version)
    inter_to_named, raw = {}, []
    cur = None
    for line in text.split('\n'):
        p = line.split('\t')
        if line.startswith('c\t') and len(p) >= 3:
            cur = p[2]
            inter_to_named[p[1]] = p[2]
            raw.append((cur, []))
        elif cur and line.startswith('\tm\t') and len(p) >= 5:
            raw[-1][1].append(('m', p[2], p[4]))
        elif cur and line.startswith('\tf\t') and len(p) >= 5:
            raw[-1][1].append(('f', p[2], p[4]))

    def remap(desc):
        return CLS_IN_DESC.sub(
            lambda m: 'L' + inter_to_named.get(m.group(1), m.group(1)) + ';', desc)

    out = {}
    for cls, members in raw:
        e = out.setdefault(cls, {'methods': set(), 'fields': set()})
        for kind, desc, name in members:
            (e['methods'] if kind == 'm' else e['fields']).add((name, remap(desc)))
    return out


def desc_args(desc):
    """['I', 'Lnet/minecraft/Foo;', 'J'] from '(ILnet/minecraft/Foo;J)V'."""
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


# ── the mixins ─────────────────────────────────────────────────────────────────
MIXIN_CLASS = re.compile(r'@Mixin\s*\(\s*(?:targets\s*=\s*"([^"]+)"|([\w.$]+)\.class)'
                         r'([^)]*)\)', re.S)
ANNOTATION = re.compile(
    r'@(Inject|Redirect|ModifyVariable|ModifyArg|ModifyArgs|ModifyConstant|'
    r'ModifyExpressionValue|WrapOperation|WrapWithCondition)\s*\((.*?)\)\s*\n\s*'
    # The handler name carries the mod's prefix -- snapmatica$cloudExposureClock --
    # and $ is not in \w. Leaving it out made this regex match nothing at all, so
    # the checker passed 1.21.10 while 1.21.10 was crashing.
    r'(?:private|public|protected)?\s*(?:static\s+)?([\w.$<>\[\], ]+?)\s+([\w$]+)\s*\(',
    re.S)
IMPORT = re.compile(r'^import\s+([\w.$]+)\s*;', re.M)

JAVA_TO_DESC = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C',
                'short': 'S', 'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D'}


def fq(simple, imports, text):
    """The fully-qualified yarn name a simple name in this file refers to."""
    if '.' in simple:
        return simple.replace('.', '/')
    for imp in imports:
        if imp.rsplit('.', 1)[-1] == simple:
            return imp.replace('.', '/')
    return None


def check_file(path, target, maps):
    src = resolve(io.open(path, encoding='utf-8').read().replace('\r\n', '\n'), target)
    name = os.path.basename(path)
    problems, unchecked = [], []

    m = MIXIN_CLASS.search(src)
    if not m:
        return problems, unchecked
    raw_target, cls_target, tail = m.group(1), m.group(2), m.group(3) or ''
    if 'remap' in tail and 'false' in tail:
        unchecked.append('%s: remap = false (%s) -- third-party, not in these mappings'
                         % (name, raw_target or cls_target))
        return problems, unchecked

    imports = IMPORT.findall(src)
    owner = (raw_target.replace('.', '/') if raw_target
             else fq(cls_target, imports, src))
    if owner is None:
        unchecked.append('%s: cannot resolve @Mixin target %r to a class' % (name, cls_target))
        return problems, unchecked
    if owner not in maps:
        problems.append('%s: @Mixin target class %s does not exist' % (name, owner))
        return problems, unchecked

    methods = maps[owner]['methods']
    by_name = {}
    for mn, md in methods:
        by_name.setdefault(mn, []).append(md)

    for anno, body, ret_type, handler in ANNOTATION.findall(src):
        mm = re.search(r'method\s*=\s*"([^"]+)"', body)
        if not mm:
            continue
        spec = mm.group(1)
        tname, _, tdesc = spec.partition('(')
        tdesc = '(' + tdesc if tdesc else ''
        if tname not in by_name:
            problems.append('%s: %s targets %s.%s, which does not exist'
                            % (name, anno, owner.rsplit('/', 1)[-1], tname))
            continue
        cands = by_name[tname]
        if tdesc:
            if tdesc not in cands:
                problems.append('%s: %s targets %s%s; the mappings have %s'
                                % (name, anno, tname, tdesc, ' / '.join(cands)))
                continue
            cands = [tdesc]

        # The check that would have caught 1.21.10: a variable the target does
        # not take cannot be modified.
        if anno == 'ModifyVariable' and 'argsOnly' in body and 'true' in body:
            want = JAVA_TO_DESC.get(ret_type.strip())
            if want is None:
                r = fq(ret_type.strip(), imports, src)
                want = 'L%s;' % r if r else None
            om = re.search(r'ordinal\s*=\s*(\d+)', body)
            ordinal = int(om.group(1)) if om else 0
            if want:
                ok = any(desc_args(d).count(want) > ordinal for d in cands)
                if not ok:
                    shapes = ' / '.join('(' + ','.join(desc_args(d)) + ')' for d in cands)
                    problems.append(
                        '%s: %s wants argument #%d of type %s in %s, which takes %s'
                        % (name, anno, ordinal, want, tname, shapes))

        at = re.search(r'target\s*=\s*"L([\w/$]+);([\w$<>]+)(\([^"]*\)[^"]*)?"', body)
        if at:
            aowner, aname, adesc = at.group(1), at.group(2), at.group(3)
            if aowner not in maps:
                problems.append('%s: @At target class %s does not exist' % (name, aowner))
            else:
                hits = [d for n, d in maps[aowner]['methods'] if n == aname]
                if not hits:
                    problems.append('%s: @At targets %s.%s, which does not exist'
                                    % (name, aowner.rsplit('/', 1)[-1], aname))
                elif adesc and adesc not in hits:
                    problems.append('%s: @At targets %s%s; the mappings have %s'
                                    % (name, aname, adesc, ' / '.join(hits)))

    if '@Shadow' in src or '@Accessor' in src or '@Invoker' in src:
        unchecked.append('%s: has @Shadow/@Accessor/@Invoker (may be inherited -- '
                         'not checked)' % name)
    return problems, unchecked


def matrix():
    """version -> yarn mappings version, from the Stonecutter properties."""
    p = os.path.join(ROOT, 'stonecutter.properties.toml')
    text = io.open(p, encoding='utf-8').read()
    out, cur = {}, None
    for line in text.split('\n'):
        h = re.match(r'^\["([\d.]+)"\]', line.strip())
        if h:
            cur = h.group(1)
        elif cur:
            y = re.match(r'^yarn_mappings\s*=\s*"([^"]+)"', line.strip())
            if y:
                out[cur] = y.group(1)
                cur = None
    return out


def main():
    want = sys.argv[1:] or None
    versions = matrix()
    files = sorted(os.path.join(MIXIN_DIR, f) for f in os.listdir(MIXIN_DIR)
                   if f.endswith('.java'))
    bad = 0
    for mc in sorted(versions, key=version_tuple):
        if want and mc not in want:
            continue
        print('\n=== %s (yarn %s) -- %d mixins' % (mc, versions[mc], len(files)))
        maps = load_mappings(versions[mc])
        target = version_tuple(mc)
        problems, unchecked = [], []
        for f in files:
            p, u = check_file(f, target, maps)
            problems += p
            unchecked += u
        for u in unchecked:
            print('  .. %s' % u)
        for p in problems:
            print('  !! %s' % p)
        bad += len(problems)
        if not problems:
            print('  OK -- every name these mixins use exists in %s' % mc)
    print('\n%s' % ('OK' if not bad else '%d problem(s) across the matrix' % bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
