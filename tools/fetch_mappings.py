"""
Builds a yarn -> (official) Mojang name dictionary for one Minecraft version by
chaining three published mapping sets:

  intermediary tiny  : obf          <-> intermediary
  yarn tiny          : intermediary <-> yarn(named)
  Mojang client.txt  : obf          <-> official Mojang names

Members are joined on (owner, descriptor, name). Descriptors are written in each
file's first namespace, so they are first rewritten into a common (obfuscated)
form. Joining on names alone is not enough: e.g. KeyMapping's field `g` and its
method `g()` are different members that share an obfuscated name.

Output: tools/out/*.json
Run:    python tools/fetch_mappings.py
"""
import io
import json
import os
import re
import urllib.request
import zipfile

MC = "1.21.4"
YARN = "1.21.4+build.8"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cache")

PRIMS = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
         "int": "I", "long": "J", "float": "F", "double": "D"}


def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    print("  GET", url)
    req = urllib.request.Request(url, headers={"User-Agent": "snapmatica-port/1.0"})
    with urllib.request.urlopen(req, timeout=180) as r, open(dest, "wb") as f:
        f.write(r.read())
    return dest


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "snapmatica-port/1.0"})
    return urllib.request.urlopen(req, timeout=180).read()


def jar_entry(jar, name):
    with zipfile.ZipFile(jar) as z:
        with z.open(name) as f:
            return f.read().decode("utf-8")


# ── type / descriptor rewriting ─────────────────────────────────────────────────
def one_type_to_jvm(t):
    arr = ""
    while t.endswith("[]"):
        arr += "["
        t = t[:-2]
    if t in PRIMS:
        return arr + PRIMS[t]
    return arr + "L" + t.replace(".", "/") + ";"


def to_jvm_desc(desc):
    """'(com.foo.Bar,float)void' -> '(Lcom/foo/Bar;F)V'"""
    args, _, ret = desc.partition(")")
    args = args.lstrip("(").replace("...", "[]")
    body = "" if not args else "".join(one_type_to_jvm(a) for a in args.split(","))
    return "(" + body + ")" + one_type_to_jvm(ret)


CLS_IN_DESC = re.compile(r"L([^;]+);")


def remap_desc(desc, clsmap):
    """Rewrite every class name inside a JVM descriptor through clsmap."""
    return CLS_IN_DESC.sub(
        lambda m: "L" + clsmap.get(m.group(1), m.group(1)) + ";", desc)
# ── tiny v2 ──────────────────────────────────────────────────────────────────────
def parse_tiny(text):
    """classes: {fqA: fqB}, members: {(clsA, descA, kind, nameA): (clsB, nameB)}

    descA is already expressed in namespace A's own class names — which for both
    published files is the left-hand namespace (official for intermediary,
    intermediary for yarn), so it is a usable join key.
    """
    classes = {}
    members = {}
    cur = None
    for line in text.split("\n"):
        if not line:
            continue
        if line.startswith("c\t"):
            p = line.split("\t")
            cur = (p[1], p[2])
            classes[p[1]] = p[2]
        elif line.startswith("\t") and cur:
            p = line.split("\t")
            kind = p[1]
            if kind not in ("m", "f"):
                continue
            toks = p[2:]
            if len(toks) == 3:
                desc, n0, n1 = toks
            elif len(toks) == 4:
                desc, n0, _d1, n1 = toks
            else:
                continue
            members[(cur[0], desc, kind, n0)] = (cur[1], n1)
    return classes, members


# ── Mojang proguard mappings ─────────────────────────────────────────────────────
CLS_RE = re.compile(r"^(\S+) -> (\S+):$")


def parse_mojang(text):
    """classes: {obf: mojang}

    raw: [(obfCls, descriptorSource, kind, mojangName, obfName)] — the descriptor
    is rebuilt later, once the mojang->obf class map exists.
    """
    classes = {}
    raw = []
    cur = None
    for line in text.split("\n"):
        if not line or line.startswith("#"):
            continue
        if not line.startswith(" "):
            m = CLS_RE.match(line.strip())
            if m:
                classes[m.group(2)] = m.group(1)     # obf -> mojang
                cur = m.group(2)
            continue
        if cur is None:
            continue
        s = re.sub(r"^\d+:\d+:", "", line.strip())   # strip "12:13:" source hints
        if s.endswith(":"):
            m = CLS_RE.match(s)
            if m:
                classes[m.group(2)] = m.group(1)
                cur = m.group(2)
            continue
        left, _, obf = s.rpartition(" -> ")
        if not left or not obf:
            continue
        if "(" in left:
            head = left.split("(", 1)[0].split()
            name = head[-1]
            ret = head[-2] if len(head) >= 2 else "void"
            args = left[left.index("("):left.rindex(")") + 1]
            raw.append((cur, args + ret, "m", name, obf.strip()))
        else:
            parts = left.split()
            if len(parts) >= 2:
                raw.append((cur, "", "f", parts[-1], obf.strip()))
    return classes, raw


def build():
    os.makedirs(CACHE, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)

    inter_jar = download(
        "https://maven.fabricmc.net/net/fabricmc/intermediary/%s/intermediary-%s-v2.jar" % (MC, MC),
        os.path.join(CACHE, "intermediary.jar"))
    yarn_jar = download(
        "https://maven.fabricmc.net/net/fabricmc/yarn/%s/yarn-%s-v2.jar" % (YARN, YARN),
        os.path.join(CACHE, "yarn.jar"))

    mani = json.loads(get("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"))
    vurl = next(v["url"] for v in mani["versions"] if v["id"] == MC)
    vjson = json.loads(get(vurl))
    cli_path = download(vjson["downloads"]["client_mappings"]["url"],
                        os.path.join(CACHE, "client.txt"))

    print("parsing intermediary ...")
    i_cls, i_mem = parse_tiny(jar_entry(inter_jar, "mappings/mappings.tiny"))
    print("parsing yarn ...")
    y_cls, y_mem = parse_tiny(jar_entry(yarn_jar, "mappings/mappings.tiny"))
    print("parsing mojang ...")
    m_cls, m_raw = parse_mojang(io.open(cli_path, encoding="utf-8").read())

    int2obf_cls = {v: k for k, v in i_cls.items()}    # intermediary fq -> obf fq
    moj2obf_cls = {v: k for k, v in m_cls.items()}    # mojang fq -> obf fq
    # descriptors carry slashed internal names, so accept both spellings
    moj2obf_cls.update({v.replace(".", "/"): k for k, v in m_cls.items()})

    # intermediary member name -> (obfCls, obfDesc, kind, obfName)
    int_name_to_obf = {}
    for (obf_cls, desc, kind, obf_name), (_ic, int_name) in i_mem.items():
        int_name_to_obf[int_name] = (obf_cls, desc, kind, obf_name)

    # (intCls, intDesc, kind, intName) -> yarnName
    int_key_to_yarn = {}
    for (int_cls, desc, kind, int_name), (_yc, yarn_name) in y_mem.items():
        int_key_to_yarn[(int_cls, desc, kind, int_name)] = yarn_name

    # mojang members, keyed with descriptors rewritten into obfuscated names
    moj_key_to_name = {}
    for obf_cls, src, kind, moj_name, obf_name in m_raw:
        desc = remap_desc(to_jvm_desc(src), moj2obf_cls) if kind == "m" else ""
        moj_key_to_name[(obf_cls, desc, kind, obf_name)] = moj_name
    print("  intermediary keys: %d   mojang keys: %d" % (len(i_mem), len(moj_key_to_name)))

    # ── the three-way join, walked from the yarn side ────────────────────────────
    yarn_to_moj = {}          # (yarnClassFq, kind, yarnName) -> mojangName
    missing = 0
    for (int_cls, desc, kind, int_name), yarn_name in int_key_to_yarn.items():
        obf_key = int_name_to_obf.get(int_name)
        if obf_key is None:
            missing += 1
            continue
        obf_cls, obf_desc, obf_kind, obf_name = obf_key
        if int2obf_cls.get(int_cls) != obf_cls:
            missing += 1
            continue
        if kind == "m" and remap_desc(desc, int2obf_cls) != obf_desc:
            missing += 1
            continue
        # field descriptors are types, not signatures: drop them on both sides,
        # obfuscated field names are unique within a class anyway
        lookup_desc = "" if obf_kind == "f" else obf_desc
        moj_name = moj_key_to_name.get((obf_cls, lookup_desc, obf_kind, obf_name))
        if moj_name is None:
            missing += 1
            continue
        yarn_class = y_cls[int_cls]
        yarn_to_moj[(yarn_class, kind, yarn_name)] = moj_name

    print("member joins: %d resolved, %d missing" % (len(yarn_to_moj), missing))

    yarn_cls = {}
    for int_fq, yarn_fq in y_cls.items():
        obf = int2obf_cls.get(int_fq)
        if obf is None:
            continue
        moj = m_cls.get(obf)
        if moj is not None:
            yarn_cls[yarn_fq] = moj
    print("class joins: %d resolved" % len(yarn_cls))
    return yarn_cls, yarn_to_moj


def emit(yarn_cls, yarn_to_moj):
    simple = {}
    simple_fq = {}
    for yarn_fq, moj_fq in yarn_cls.items():
        ys = yarn_fq.split("/")[-1]
        ms = moj_fq.split(".")[-1]          # mojang names are dotted, not slashed
        simple_fq[yarn_fq.replace("/", ".")] = moj_fq
        simple.setdefault(ys, set()).add(ms)

    by_name = {}
    by_owner = {}
    for (yarn_class, kind, yarn_name), moj_name in yarn_to_moj.items():
        if yarn_name.startswith("method_") or yarn_name.startswith("field_"):
            continue
        by_name.setdefault(yarn_name, set()).add(moj_name)
        by_owner.setdefault((yarn_class.split("/")[-1], yarn_name), set()).add(moj_name)

    unamb = {k: list(v)[0] for k, v in by_name.items() if len(v) == 1}
    amb = {k: sorted(v) for k, v in by_name.items() if len(v) > 1}
    unamb_owner = {"%s.%s" % k: list(v)[0] for k, v in by_owner.items() if len(v) == 1}
    amb_owner = {"%s.%s" % k: sorted(v) for k, v in by_owner.items() if len(v) > 1}

    def dump(name, obj):
        json.dump(obj, io.open(os.path.join(OUT, name), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=0, sort_keys=True)

    dump("classes.json", {k: list(v)[0] for k, v in simple.items() if len(v) == 1})
    dump("classes_fq.json", simple_fq)
    dump("ambiguous_classes.json", {k: sorted(v) for k, v in simple.items() if len(v) > 1})
    dump("members.json", unamb)
    dump("members_owner.json", unamb_owner)
    dump("ambiguous.json", amb)
    dump("ambiguous_owner.json", amb_owner)
    print("classes: %d unique, %d ambiguous (of %d)"
          % (sum(1 for v in simple.values() if len(v) == 1), 
             sum(1 for v in simple.values() if len(v) > 1), len(simple)))
    print("members: %d unambiguous, %d ambiguous (owner-qualified: %d / %d)"
          % (len(unamb), len(amb), len(unamb_owner), len(amb_owner)))


if __name__ == "__main__":
    emit(*build())