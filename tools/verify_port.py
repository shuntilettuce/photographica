#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Check a ported tree against the Fabric/yarn tree it came from.

A port is supposed to be a RENAME and nothing else: every yarn name becomes its
Mojang equivalent and the code around it is untouched. That is a property a
machine can check, and it is worth checking, because the rename is done by
regex over forty-odd files and a regex that matches one character too many
changes behaviour silently -- the compiler is happy, the game starts, and the
damage only shows up in a photograph.

So: resolve the Fabric sources' Stonecutter blocks for the target version, then
compare each file with its ported counterpart after replacing every IDENTIFIER
with a placeholder. What is left is the skeleton -- operators, literals,
punctuation, and the shape of every call. Those must match exactly. A
difference means the port changed something other than a name.

What this does NOT catch, and what still needs a human or the game: a rename to
the WRONG name that happens to compile, and any bug that was already in the
Fabric source (the 1.20.1 toe-in sign was one of those).

    python tools/verify_port.py <fabricSrcDir> <portedSrcDir> --mc=1.20.1
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import port  # noqa: E402  -- resolve_stonecutter / version_tuple

# Order matters: strings and comments first, so their contents cannot be
# mistaken for code.
TOKEN = re.compile(r"""
      (?P<block>   /\*.*?\*/                         )
    | (?P<line>    //[^\n]*                          )
    | (?P<str>     "(?:\\.|[^"\\])*"                 )
    | (?P<chr>     '(?:\\.|[^'\\])*'                 )
    | (?P<num>     \d[\w.]*                          )
    | (?P<ident>   [A-Za-z_$][A-Za-z0-9_$]*          )
    | (?P<ws>      \s+                               )
    | (?P<op>      .                                 )
""", re.X | re.S)

# Identifiers that are part of the language rather than a name anyone could
# rename. If one of these turns into another the meaning changed, so they stay
# in the skeleton instead of being blanked.
KEYWORDS = {
    'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char',
    'class', 'const', 'continue', 'default', 'do', 'double', 'else', 'enum',
    'extends', 'final', 'finally', 'float', 'for', 'goto', 'if', 'implements',
    'import', 'instanceof', 'int', 'interface', 'long', 'native', 'new',
    'package', 'private', 'protected', 'public', 'return', 'short', 'static',
    'strictfp', 'super', 'switch', 'synchronized', 'this', 'throw', 'throws',
    'transient', 'try', 'void', 'volatile', 'while', 'true', 'false', 'null',
    'record', 'var', 'yield', 'sealed', 'permits',
}


ID = '<id>'

# The header is not logic and cannot match: package paths differ in depth
# (net.minecraft.text.Text against net.minecraft.network.chat.Component), and
# the Fabric-only imports and @Environment are removed by the port on purpose.
HEADER = re.compile(r'^\s*(?:package|import)\b[^;]*;[ \t]*\n?', re.M)
ENVIRONMENT = re.compile(r'@Environment\s*\([^)]*\)[ \t]*\n?')

# The three shapes a mixin target takes: a bare method name, a name with a JVM
# descriptor, and an @At target naming the owner as well.
SIGNATURE = re.compile(r'^"(?:L[\w/$]+;)?[A-Za-z_$][A-Za-z0-9_$]*'
                       r'(?:\([^"]*\)[^"]*)?"$')


def skeleton(src):
    """(tokens, positions) with identifiers blanked and comments dropped."""
    src = ENVIRONMENT.sub('', HEADER.sub('', src))
    out, lines = [], []
    line = 1
    for m in TOKEN.finditer(src):
        kind = m.lastgroup
        text = m.group()
        if kind in ('block', 'line', 'ws'):
            line += text.count('\n')
            continue
        if kind == 'ident' and text not in KEYWORDS:
            text = ID
        elif kind == 'str' and SIGNATURE.match(text):
            # A mixin target is a name in a string: method = "getTimeOfDay"
            # becomes "getDayTime", and the descriptor spells whole packages out.
            # Those are renames like any other. Anything with punctuation a name
            # cannot have -- a lang key, a format string -- is left alone and
            # compared strictly.
            text = '<sig>'
        out.append(text)
        lines.append(line)
        line += m.group().count('\n')
    return out, lines


QUAL = '<qual>'


def collapse_qualifiers(toks, lines):
    """Fold `a . b . c` into one token, because qualifier DEPTH is a rename too.

    net.minecraft.util.math.Vec3d and net.minecraft.world.phys.Vec3 name the same
    class at different depths, and a raw token count would call that a change. A
    trailing member keeps its own token -- the run before a `(` gives up its last
    name -- so `x.getLocation()` and `x.getBlockPos().getLocation()` still differ.
    """
    out, ol = [], []
    i = 0
    while i < len(toks):
        if toks[i] == ID:
            j = i
            while j + 2 < len(toks) and toks[j + 1] == '.' and toks[j + 2] == ID:
                j += 2
            if j > i:
                member = j + 1 < len(toks) and toks[j + 1] == '('
                out.append(QUAL)
                ol.append(lines[i])
                if member:
                    out.extend(['.', ID])
                    ol.extend([lines[j], lines[j]])
                i = j + 1
                continue
        out.append(toks[i])
        ol.append(lines[i])
        i += 1
    return out, ol


def compare(a_src, b_src):
    """None when the two are the same modulo names, else a description."""
    a, al = collapse_qualifiers(*skeleton(a_src))
    b, bl = collapse_qualifiers(*skeleton(b_src))
    if a == b:
        return None
    # First divergence, with a little context, named by line on each side.
    n = min(len(a), len(b))
    i = 0
    while i < n and a[i] == b[i]:
        i += 1
    ctx_a = ' '.join(a[max(0, i - 8):i + 8])
    ctx_b = ' '.join(b[max(0, i - 8):i + 8])
    return ('diverges at yarn line %s / ported line %s\n'
            '      yarn:   %s\n'
            '      ported: %s'
            % (al[i] if i < len(al) else '?', bl[i] if i < len(bl) else '?',
               ctx_a, ctx_b))


def walk(root):
    for dp, _dirs, files in os.walk(root):
        for f in files:
            if f.endswith('.java'):
                p = os.path.join(dp, f)
                yield os.path.relpath(p, root).replace('\\', '/'), p


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    target = None
    for a in sys.argv[1:]:
        if a.startswith('--mc='):
            target = port.version_tuple(a.split('=', 1)[1])
    if len(args) != 2 or target is None:
        print(__doc__)
        sys.exit(1)
    yarn_root, ported_root = args

    yarn = dict(walk(yarn_root))
    ported = dict(walk(ported_root))

    # The loader glue is rewritten by hand on purpose, so it is not a rename and
    # cannot be compared this way. Everything else must be.
    BY_HAND = {'dev/shunti/snapmatica/client/SnapmaticaClient.java'}

    only_yarn = sorted(set(yarn) - set(ported))
    only_ported = sorted(set(ported) - set(yarn))
    bad = []
    checked = 0
    for rel in sorted(set(yarn) & set(ported)):
        if rel in BY_HAND:
            continue
        a = port.resolve_stonecutter(
            io.open(yarn[rel], encoding='utf-8').read().replace('\r\n', '\n'), target)
        b = io.open(ported[rel], encoding='utf-8').read().replace('\r\n', '\n')
        checked += 1
        d = compare(a, b)
        if d:
            bad.append((rel, d))

    print('checked %d files against %s' % (checked, '.'.join(map(str, target))))
    for rel in only_yarn:
        print('  MISSING from the port: %s' % rel)
    for rel in only_ported:
        print('  added by the port (not compared): %s' % rel)
    for rel in BY_HAND & set(ported):
        print('  hand-written loader glue (not compared): %s' % rel)
    if not bad:
        print('\nOK -- every compared file differs from the Fabric source by names only.')
        return 0
    print('\n%d file(s) changed more than names:\n' % len(bad))
    for rel, d in bad:
        print('  %s\n    %s\n' % (rel, d))
    return 1


if __name__ == '__main__':
    sys.exit(main())
