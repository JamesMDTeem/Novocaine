# Wikitext primitives and the Ring of Brodgar fetch layer for the combat data pack.
#
# Standard library only, by project constraint. Ring of Brodgar returns HTTP 403 to urllib's
# default User-Agent, so every request sets a real one.
#
# The two subtle pieces are extract_template (infobox values legitimately contain nested
# {{#expr:...}}, so a naive split on the first "}}" truncates most infoboxes after one field)
# and fields (a "|" inside [[a|b]] or {{...}} is not a parameter separator).
#
# safe_filename lives here, not in each script that needs it, so the writer (fetch_fixtures.py)
# and the readers (later parsing scripts) can never derive a fixture's filename two different
# ways and drift apart.

import json, urllib.request, urllib.parse
from pathlib import Path

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) NovocaineDataPack/0.1"}
BASE = "https://ringofbrodgar.com"
ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data" / "combat"
FIXTURES = Path(__file__).resolve().parents[1] / "combat-fixtures"


def safe_filename(title):
    return "".join(c if c.isalnum() or c in "-_ " else "_" for c in title).strip() + ".wikitext"


def _get(url):
    req = urllib.request.Request(url, headers=UA)
    return urllib.request.urlopen(req, timeout=30).read().decode("utf8", "replace")


def api(**kw):
    kw.setdefault("action", "query")
    kw.setdefault("format", "json")
    q = "&".join("%s=%s" % (k, urllib.parse.quote(str(v))) for k, v in kw.items())
    return json.loads(_get(BASE + "/api.php?" + q))


def pages_in(category):
    out, cont = [], None
    while True:
        kw = dict(list="categorymembers", cmtitle="Category:" + category, cmlimit=500)
        if cont:
            kw["cmcontinue"] = cont
        d = api(**kw)
        out += [c["title"] for c in d["query"]["categorymembers"]]
        cont = d.get("continue", {}).get("cmcontinue")
        if not cont:
            return out


def contents(titles):
    res = {}
    for i in range(0, len(titles), 40):
        d = api(titles="|".join(titles[i:i + 40]), prop="revisions",
                rvprop="content", rvslots="main")
        for p in d["query"]["pages"].values():
            try:
                res[p["title"]] = p["revisions"][0]["slots"]["main"]["*"]
            except Exception:
                res[p["title"]] = None
    return res


def raw(title):
    return _get(BASE + "/index.php?title=" + urllib.parse.quote(title) + "&action=raw")


def extract_template(text, name):
    """Return the full {{name ...}} block, tracking brace depth so nested templates
    (every creature's {{#expr:}} coordinates) do not terminate it early.

    Returns None only when {{name is absent altogether. If it is found but never
    closes (depth never returns to 0), that is a malformed page, not an absent
    template -- raise instead of returning None, so a later consumer that treats
    None as "this record legitimately has no infobox" cannot silently absorb a
    parse failure into that same bucket."""
    i = text.find("{{" + name)
    if i < 0:
        return None
    depth, j = 0, i
    while j < len(text):
        if text.startswith("{{", j):
            depth += 1
            j += 2
            continue
        if text.startswith("}}", j):
            depth -= 1
            j += 2
            if depth == 0:
                return text[i:j]
            continue
        j += 1
    raise ValueError("unbalanced {{%s}} block, never closed: %r" % (name, text[i:i + 60]))


def fields(block):
    """Split a template block into its top-level named parameters. A '|' nested inside
    {{ }} or [[ ]] belongs to the value, not to the parameter list."""
    if block is None:
        return {}
    inner = block[2:-2] if block.startswith("{{") and block.endswith("}}") else block
    depth, cur, parts = 0, "", []
    k = 0
    while k < len(inner):
        if inner.startswith("{{", k) or inner.startswith("[[", k):
            depth += 1
            cur += inner[k:k + 2]
            k += 2
            continue
        if inner.startswith("}}", k) or inner.startswith("]]", k):
            depth -= 1
            cur += inner[k:k + 2]
            k += 2
            continue
        if inner[k] == "|" and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += inner[k]
        k += 1
    parts.append(cur)
    out = {}
    for p in parts[1:]:
        if "=" in p:
            key, val = p.split("=", 1)
            out[key.strip()] = val.strip()
    return out


def num(s):
    """Wiki numerics are not always numeric ('~500', '1-5', 'varies'). Keep the raw string
    always; parse a value when one can be read, else None. Never fabricate a 0."""
    s = "" if s is None else str(s).strip()
    import re as _re
    m = _re.search(r"-?\d+(?:\.\d+)?", s)
    if not m:
        return {"raw": s, "value": None}
    t = m.group(0)
    v = float(t) if "." in t else int(t)
    return {"raw": s, "value": v}


def load_fixture(name):
    return (FIXTURES / name).read_text(encoding="utf8")
