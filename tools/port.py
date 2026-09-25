"""
Ports snapmatica's Fabric/yarn sources to NeoForge/Mojang for one target version.

  1. resolves stonecutter conditionals (//? if / //?} elif / //?} else { / *//?})
  2. rewrites yarn class names (fq + simple) and member names through the
     dictionaries built by fetch_mappings.py
  3. drops Fabric-only imports and @Environment annotations (the loader glue is
     rewritten by hand afterwards)

Run:  python tools/port.py <srcDir> <outDir> [--mc=1.20.1]
"""
import io
import json
import os
import re
import sys

TARGET = (1, 21, 1)   # overridden by --mc on the command line
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")

IF_RE = re.compile(r"^(\s*)//\? (?:if|else if) (.*?)\s*\{$")
ELSEIF_RE = re.compile(r"^(\s*)(?:\*/)?//\?\}\s*(?:else if|elif)\s+(.*?)\s*\{$")
ELSE_RE = re.compile(r"^(\s*)(?:\*/)?//\?\}\s*else\s*\{$")
END_RE = re.compile(r"^(\s*)(?:\*/)?//\?\}\s*$")


def version_tuple(text):
    return tuple(int(p) for p in text.split("."))


def eval_cond(cond, target):
    cond = cond.strip()
    m = re.match(r"^(>=|<=|>|<|==)?\s*([\d.]+)$", cond)
    if not m:
        raise ValueError("unsupported stonecutter condition: %r" % cond)
    op, ver = m.groups()
    v = version_tuple(ver)
    t = target[: len(v)]
    return {"<": t < v, "<=": t <= v, ">": t > v, ">=": t >= v, "==": t == v,
            None: t == v}[op]


def resolve_stonecutter(text, target):
    """Returns source with all (possibly nested) //? blocks resolved for target."""
    lines = text.split("\n")

    def parse(i):
        """Parses lines[i:] until EOF or a //?} terminator.

        Returns (node, next_index). A node is {"branches": [(cond, items, commented)]}.
        """
        branches = [(None, [], False)]
        items, commented = branches[0][1], False
        while i < len(lines):
            line = lines[i]
            if IF_RE.match(line):
                node, i = parse_block(i, IF_RE.match(line).group(2))
                items.append(node)
                continue
            if ELSEIF_RE.match(line) or ELSE_RE.match(line):
                branches[-1] = (branches[-1][0], items, commented)
                new_cond = (ELSEIF_RE.match(line) or ELSEIF_RE.match(line)).group(2) \
                    if ELSEIF_RE.match(line) else None
                branches.append((new_cond, [], False))
                items = branches[-1][1]
                commented = False
                i += 1
                continue
            if END_RE.match(line):
                branches[-1] = (branches[-1][0], items, commented)
                return {"branches": branches}, i + 1
            if line.strip().startswith("/*"):
                commented = True
            items.append(line)
            i += 1
        branches[-1] = (branches[-1][0], items, commented)
        return {"branches": branches}, i

    def parse_block(i, cond):
        # lines[i] is the "//? if COND {" line
        i += 1
        branches = [(cond, [], False)]
        items, commented = branches[0][1], False
        while i < len(lines):
            line = lines[i]
            if IF_RE.match(line):
                node, i = parse_block(i, IF_RE.match(line).group(2))
                items.append(node)
                continue
            if ELSEIF_RE.match(line) or ELSE_RE.match(line):
                branches[-1] = (branches[-1][0], items, commented)
                new_cond = ELSEIF_RE.match(line).group(2) if ELSEIF_RE.match(line) else None
                branches.append((new_cond, [], False))
                items = branches[-1][1]
                commented = False
                i += 1
                continue
            if END_RE.match(line):
                branches[-1] = (branches[-1][0], items, commented)
                return {"branches": branches}, i + 1
            if line.strip().startswith("/*"):
                commented = True
            items.append(line)
            i += 1
        raise ValueError("unterminated stonecutter block (cond=%r)" % cond)

    def uncomment(items):
        """Remove the surrounding /* ... */ markers of an inactive branch."""
        res = list(items)
        if res and isinstance(res[0], str):
            j = res[0].find("/*")
            if j >= 0:
                res[0] = res[0][:j] + res[0][j + 2:]
        if res and isinstance(res[-1], str):
            j = res[-1].find("*//")
            if j >= 0:
                res[-1] = res[-1][:j] + res[-1][j + 3:]
            elif res[-1].rstrip().endswith("*/"):
                # The other legal shape: the body's last line closes the comment
                # itself -- `}*/` -- and the //?} marker follows on a line of its
                # own. Missing this left a stray */ in WorldRendererMixin for
                # 1.21.4, where the kept branch is a middle `else if`.
                t = res[-1].rstrip()
                res[-1] = t[:-2]
        return res

    def render(items, out):
        for it in items:
            if isinstance(it, dict):
                render_block(it, out)
            else:
                out.append(it)

    def render_block(node, out):
        # first branch whose condition holds for the target wins
        for cond, body, was_commented in node["branches"]:
            if cond is None or eval_cond(cond, target):
                if was_commented:
                    body = uncomment(body)
                render(body, out)
                return

    root, _ = parse(0)
    out = []
    render_block(root, out)
    return "\n".join(out)


# ── name mapping ─────────────────────────────────────────────────────────────────
def load_dict(name):
    return json.load(io.open(os.path.join(OUT, name), encoding="utf-8"))


CLASSES = load_dict("classes.json")                # yarn simple -> mojang simple
CLASSES_FQ = load_dict("classes_fq.json")          # yarn fq -> mojang fq
MEMBERS = load_dict("members.json")                # yarn name -> mojang (unambiguous)
MEMBERS_OWNER = {}
for _k, _v in load_dict("members_owner.json").items():   # "YarnOwner.member" -> mojang
    _owner, _member = _k.split(".", 1)
    MEMBERS_OWNER["%s.%s" % (CLASSES.get(_owner, _owner), _member)] = _v
JAVA_KEYWORDS = {"if", "else", "for", "while", "return", "new", "this", "super",
                 "class", "interface", "enum", "import", "package", "public",
                 "private", "protected", "static", "final", "void", "int", "long",
                 "float", "double", "boolean", "char", "byte", "short", "true",
                 "false", "null", "extends", "implements", "instanceof", "switch",
                 "case", "break", "continue", "default", "try", "catch", "finally",
                 "throw", "throws", "abstract", "synchronized", "native",
                 "transient", "volatile", "strictfp", "assert", "do", "goto",
                 "const", "record", "var", "yield", "sealed", "permits"}

FQ_RE = re.compile(r"\b(?:net|com)\.minecraft[a-zA-Z0-9_.$]*|com\.mojang[a-zA-Z0-9_.$]*")
SLASH_FQ_RE = re.compile(r"net/minecraft/[A-Za-z0-9_/$]*|com/mojang/[A-Za-z0-9_/$]*")
_WORD = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]*\b")
_FQ_SLASH = {k.replace(".", "/"): v.replace(".", "/") for k, v in CLASSES_FQ.items()}


# Yarn borrows a lot of names from the JDK. The dictionaries below are keyed on
# simple names only, so without these denylists a yarn entry happily rewrites the
# mod's calls into java.* -- Properties became BlockStateProperties, trim() became
# clipText(), Thread.sleep() became Thread.startSleeping(). Every name here was
# observed corrupting the 1.21.1 port.
JDK_CLASSES = {"Properties", "Clipboard", "Entry", "Style", "Font", "Component",
               "Button", "Timer", "List", "Queue", "Path", "Level", "Type"}
JDK_MEMBERS = {"toLowerCase", "toUpperCase", "trim", "flip", "poll", "pop", "push",
               "sleep", "hypot", "isNaN", "isInfinite", "parseBoolean", "parseInt",
               "parseFloat", "readLine", "setProperty", "getProperty", "keySet",
               "entrySet", "iterator", "order", "floorMod", "floorDiv",
               "createDirectories", "createDirectory", "lastModified", "length",
               "get", "set", "put", "add", "remove", "contains", "size", "close",
               "write", "read", "start", "run", "join", "format", "valueOf",
               "println", "print", "forName", "getBytes", "getName", "equals",
               "hashCode", "toString", "clone", "compareTo"}

# Classes whose static calls must never be rewritten, whatever the member is called.
JDK_OWNERS = {"System", "Math", "StrictMath", "Thread", "Runtime", "Float", "Double",
              "Integer", "Long", "Short", "Byte", "Boolean", "Character", "String",
              "Arrays", "Collections", "Objects", "Files", "Paths", "Path",
              "Optional", "Stream", "IntStream", "ByteBuffer", "ByteOrder",
              "StandardCharsets", "TimeUnit", "Instant", "Duration", "UUID"}

IMPORT_RE = re.compile(r"^import\s+(?:static\s+)?([\w.$]+)\s*;", re.M)


def foreign_simple_names(text):
    """Simple names this file imports from outside Minecraft -- never rewrite those.

    `import java.util.Properties;` in a file is proof that its `Properties` is the
    JDK's, whatever yarn happens to call one of its own classes."""
    out = set()
    for fq in IMPORT_RE.findall(text):
        if fq.startswith(("net.minecraft", "com.mojang", "net.fabricmc")):
            continue
        out.add(fq.rsplit(".", 1)[-1])
    return out


def map_fq(dotted):
    """The Mojang spelling of a dotted yarn reference, or None if it names no class.

    FQ_RE takes everything dotted after net.minecraft, so what arrives is often a
    class with members hanging off it -- net.minecraft.util.math.MathHelper.lerp --
    and sometimes a nested class written with dots, as Java source spells it:
    net.minecraft.world.RaycastContext.ShapeType. Looking the whole run up as one
    class name found neither, the run was left as yarn, and the simple-name pass
    then rewrote only its tail, producing net.minecraft.util.math.Mth: a path that
    exists in neither mapping. So find the longest prefix that is a class --
    trying each split between package-and-outer (dots) and nesting ($) -- map
    that, and hand the rest back untouched for the member pass.
    """
    parts = dotted.split(".")
    for n in range(len(parts), 0, -1):
        for j in range(n, 0, -1):
            key = ".".join(parts[:j])
            if j < n:
                key += "$" + "$".join(parts[j:n])
            hit = CLASSES_FQ.get(key)
            if hit:
                return hit.replace("$", ".") + "".join("." + p for p in parts[n:])
    return None


def rewrite_classes(text):
    keep = foreign_simple_names(text) | JDK_CLASSES | PROTECTED
    # dotted fully-qualified names
    def fq_sub(m):
        return map_fq(m.group(0)) or m.group(0)
    text = FQ_RE.sub(fq_sub, text)
    # slashed fully-qualified names (annotation descriptors: Lnet/minecraft/...;)
    def slash_sub(m):
        return _FQ_SLASH.get(m.group(0), m.group(0))
    text = SLASH_FQ_RE.sub(slash_sub, text)
    # then simple names via cheap word scanning + dict lookup -- but never inside
    # a qualified name the passes above already produced. Letting it in there is
    # a second rename in the same run: net.minecraft.util.PlayerInput became
    # net.minecraft.world.entity.player.Input, and this pass then turned that
    # Mojang `Input` into `ClientInput` because yarn has a class of that name.
    def word_sub(m):
        w = m.group(0)
        if w in keep:
            return w
        return CLASSES.get(w, w)
    out, last = [], 0
    for m in FQ_RE.finditer(text):
        out.append(_WORD.sub(word_sub, text[last:m.start()]))
        out.append(m.group(0))
        last = m.end()
    out.append(_WORD.sub(word_sub, text[last:]))
    return "".join(out)


def scan_own_names(src_dir):
    """Method/field names declared by the mod's own (non-mixin) classes;
    those must never be rewritten to vanilla names even if they collide."""
    meth = re.compile(r"^\s*(?:public|protected|private|static|final|synchronized|"
                      r"abstract|native|\s)*[\w$<>\[\],.?]+[\w$<>\[\].?]\s+(\w+)\s*\(")
    field = re.compile(r"^\s*(?:public|protected|private|static|final|volatile|"
                       r"transient|\s)*[\w$<>\[\],.?]+\s+(\w+)\s*[=;]")
    names = set()
    for root, _d, files in os.walk(src_dir):
        if os.path.basename(root) == "mixin":
            continue
        for f in files:
            if not f.endswith(".java"):
                continue
            for line in io.open(os.path.join(root, f), encoding="utf-8"):
                s = line.strip()
                if s.startswith(("if ", "for ", "while ", "switch ", "catch ",
                                 "return ", "new ", "throw ", "import ", "package ",
                                 "//", "*", "else", "do ")):
                    continue
                m = meth.match(line) or field.match(line)
                if m:
                    names.add(m.group(1))
    return names


PROTECTED = set()


def rewrite_members(text):
    # owner-qualified: Owner.member, where Owner is a known class simple name
    def owner_sub(m):
        owner, member = m.group(1), m.group(2)
        if member in PROTECTED or member in JDK_MEMBERS or owner in JDK_OWNERS:
            return m.group(0)
        rep = MEMBERS_OWNER.get("%s.%s" % (owner, member))
        if rep is None or rep == member:
            return m.group(0)
        return "%s.%s" % (owner, rep)
    text = re.sub(r"\b([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)\b",
                  owner_sub, text)
    # global: .member( — only unambiguous names
    def global_sub(m):
        name = m.group(1)
        if name in PROTECTED or name in JDK_MEMBERS:
            return m.group(0)
        # A call on a JDK class is the JDK's, whatever yarn named one of its own
        # methods. 1.21.4's yarn grew a currentTimeMs, and every
        # System.currentTimeMillis() in the mod became System.currentTimeMs() --
        # 48 compile errors from one name. Ask who owns the call, not just what it
        # is called.
        k = m.start() - 1
        while k >= 0 and (m.string[k].isalnum() or m.string[k] == '_'):
            k -= 1
        if m.string[k + 1:m.start()] in JDK_OWNERS:
            return m.group(0)
        rep = MEMBERS.get(name)
        if rep is None or rep == name:
            return m.group(0)
        return "." + rep
    text = re.sub(r"\.([A-Za-z_][A-Za-z0-9_]*)\s*(?=\()", global_sub, text)
    return text


MIXIN_CLASS_RE = re.compile(r"@Mixin\s*\(\s*(\w+)\.class")
MIXIN_TARGETS_RE = re.compile(r"@Mixin\s*\(\s*targets\s*=\s*\"([^\"]+)\"")
MIXIN_ANNO_RE = re.compile(r"@(?:Shadow|Invoker|Accessor|Mixin|Inject|Redirect|"
                           r"ModifyVariable|ModifyExpressionValue|WrapOperation)\b"
                           r"|method\s*=|target\s*=")
STR_METHOD_RE = re.compile(r'"([A-Za-z_][A-Za-z0-9_]*)\s*(\([^"]*?\))?"')
DECL_METHOD_RE = re.compile(r"\b(\w+)\s*\(")
DECL_FIELD_RE = re.compile(r"\b(\w+)\s*[=;]")


def mixin_target_yarn_name(original_text):
    m = MIXIN_CLASS_RE.search(original_text)
    if m:
        return m.group(1)
    m = MIXIN_TARGETS_RE.search(original_text)
    if m:
        return m.group(1).replace("/", ".").rsplit(".", 1)[-1]
    return None


def rewrite_mixin_members(text, yarn_owner):
    """Inside a mixin, target-class member names must be rewritten where they
    appear without a dot prefix: @Shadow declarations and annotation strings.
    Comments and plain code lines are left alone (the dot-call pass already ran)."""
    owner_map = {}
    for k, v in MEMBERS_OWNER.items():
        if k.split(".")[0] == CLASSES.get(yarn_owner, yarn_owner):
            owner_map[k.split(".", 1)[1]] = v
    merged = dict(MEMBERS)
    merged.update(owner_map)
    merged = {k: v for k, v in merged.items() if k != v and k not in JAVA_KEYWORDS}
    if not merged:
        return text

    def sub_word(w):
        return merged.get(w, w)

    def rewrite_line(line):
        def str_sub(m):
            name = m.group(1)
            rep = sub_word(name)
            if rep == name:
                return m.group(0)
            return '"%s%s"' % (rep, m.group(2) or "")
        line = STR_METHOD_RE.sub(str_sub, line)
        mm = DECL_METHOD_RE.search(line)
        if mm and "(" in line and not line.lstrip().startswith(
                ("if ", "for ", "while ", "switch ", "return", "new ", "throw")):
            rep = sub_word(mm.group(1))
            if rep != mm.group(1):
                return line[:mm.start(1)] + rep + line[mm.end(1):]
        fm = DECL_FIELD_RE.search(line)
        if fm:
            rep = sub_word(fm.group(1))
            if rep != fm.group(1):
                line = line[:fm.start(1)] + rep + line[fm.end(1):]
        return line

    out_lines = []
    in_block = False
    pending_decl = False
    for line in text.split("\n"):
        stripped = line.strip()
        if in_block:
            out_lines.append(line)
            if "*/" in stripped:
                in_block = False
            continue
        if stripped.startswith("/*"):
            out_lines.append(line)
            if "*/" not in stripped:
                in_block = True
            continue
        if stripped.startswith("//") or stripped.startswith("*"):
            out_lines.append(line)
            continue
        if MIXIN_ANNO_RE.search(line):
            out_lines.append(rewrite_line(line))
            pending_decl = any(a in line for a in ("@Shadow", "@Invoker", "@Accessor"))
            continue
        if pending_decl:
            out_lines.append(rewrite_line(line))
            pending_decl = False
            continue
        out_lines.append(line)
    return "\n".join(out_lines)


FABRIC_IMPORTS = re.compile(r"^\s*import\s+net\.fabricmc\.[^;]+;\s*$", re.MULTILINE)
ENV_ANNOTATION = re.compile(r"^\s*@Environment\s*\(\s*EnvType\.CLIENT\s*\)\s*$", re.MULTILINE)


def strip_fabric(text):
    text = FABRIC_IMPORTS.sub("", text)
    text = ENV_ANNOTATION.sub("", text)
    return re.sub(r"\n{4,}", "\n\n\n", text)


def main():
    global TARGET
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    for a in sys.argv[1:]:
        if a.startswith("--mc="):
            TARGET = version_tuple(a.split("=", 1)[1])
    if len(args) != 2:
        print(__doc__)
        sys.exit(1)
    src_dir, dst_dir = args
    # Porting an already-ported tree is what produced Text -> Component ->
    # TypedDataComponent and TextRenderer -> Font -> GlyphProvider in the first
    # NeoForge attempt: every rewrite is a single pass, but two passes compose.
    for _r, _d, _f in os.walk(src_dir):
        for _n in _f:
            if _n.endswith(".java") and "import net.minecraft.client.Minecraft;" in                     io.open(os.path.join(_r, _n), encoding="utf-8").read():
                sys.exit("refusing to run: %s looks already ported (Mojang names "
                         "present). Port from the Fabric/yarn tree." % src_dir)
    print("target %s" % ".".join(str(v) for v in TARGET))
    PROTECTED.update(scan_own_names(src_dir))
    count = 0
    for root, _dirs, files in os.walk(src_dir):
        for f in files:
            if not f.endswith(".java"):
                continue
            src = os.path.join(root, f)
            rel = os.path.relpath(src, src_dir)
            dst = os.path.join(dst_dir, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            text = io.open(src, encoding="utf-8").read().replace("\r\n", "\n")
            original = text
            text = resolve_stonecutter(text, TARGET)
            text = rewrite_classes(text)
            text = rewrite_members(text)
            if os.path.basename(root) == "mixin":
                target = mixin_target_yarn_name(original)
                if target:
                    text = rewrite_mixin_members(text, target)
            text = strip_fabric(text)
            io.open(dst, "w", encoding="utf-8", newline="\n").write(text)
            count += 1
    print("ported %d files -> %s" % (count, dst_dir))


if __name__ == "__main__":
    main()