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

import json, urllib.request, urllib.parse, urllib.error
import time as _time
from pathlib import Path

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) NovocaineDataPack/0.1"}
BASE = "https://ringofbrodgar.com"
ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data" / "combat"
FIXTURES = Path(__file__).resolve().parents[1] / "combat-fixtures"


def safe_filename(title):
    return "".join(c if c.isalnum() or c in "-_ " else "_" for c in title).strip() + ".wikitext"


def _get(url, _retries=3):
    """GET url with timeout, UA, and bounded retry for transient failures.

    Retries on 429/5xx and on timeout/URLError with exponential backoff.
    Non-retryable 4xx (except 429) fail immediately. Stdlib only -- avoids
    adding requests/urllib3/tenacity for a single fetch script."""
    last_exc = None
    for attempt in range(_retries):
        req = urllib.request.Request(url, headers=UA)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                # Surface HTTP error codes that urlopen doesn't raise for 2xx
                status = getattr(resp, "status", 200)
                body = resp.read()
                if status == 429 or 500 <= status < 600:
                    raise urllib.error.HTTPError(url, status, "retryable %d" % status, resp.headers, None)
                return body.decode("utf8", "replace")
        except urllib.error.HTTPError as e:
            # Retry only 429 and 5xx; everything else is a hard failure
            if e.code == 429 or 500 <= e.code < 600:
                last_exc = e
                if attempt < _retries - 1:
                    # Honor Retry-After when present
                    try:
                        ra = e.headers.get("Retry-After")
                        delay = float(ra) if ra is not None else (1.5 ** attempt)
                    except Exception:
                        delay = 1.5 ** attempt
                    _time.sleep(min(delay, 10))
                    continue
            raise
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last_exc = e
            if attempt < _retries - 1:
                _time.sleep(1.5 ** attempt)
                continue
            raise
    if last_exc is not None:
        raise last_exc
    raise RuntimeError("unreachable _get retry")


def api(**kw):
    kw.setdefault("action", "query")
    kw.setdefault("format", "json")
    q = "&".join("%s=%s" % (k, urllib.parse.quote(str(v))) for k, v in kw.items())
    data = json.loads(_get(BASE + "/api.php?" + q))
    # MediaWiki signals errors as {"error": {...}} -- surface immediately
    if isinstance(data, dict) and "error" in data:
        raise RuntimeError("wiki api error: %s" % data["error"])
    if isinstance(data, dict) and "warnings" in data:
        # Warnings are not fatal but worth surfacing when debugging
        pass
    return data


def pages_in(category):
    out, cont = [], None
    while True:
        kw = dict(list="categorymembers", cmtitle="Category:" + category, cmlimit=500)
        if cont:
            kw["cmcontinue"] = cont
        d = api(**kw)
        try:
            members = d["query"]["categorymembers"]
        except KeyError:
            raise RuntimeError("wiki api missing categorymembers: %r" % d)
        out += [c["title"] for c in members]
        cont = d.get("continue", {}).get("cmcontinue")
        if not cont:
            return sorted(set(out))


def contents(titles):
    res = {}
    for i in range(0, len(titles), 40):
        batch = titles[i:i + 40]
        if not batch:
            continue
        d = api(titles="|".join(batch), prop="revisions",
                rvprop="content", rvslots="main")
        try:
            pages = d["query"]["pages"]
        except KeyError:
            raise RuntimeError("wiki api missing pages: %r" % d)
        for p in pages.values():
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
    # Case-insensitive and underscore/space tolerant: wiki normalises both.
    # Use a lower-cased scan so {{Infobox creature}} matches name "infobox creature".
    low = text.lower()
    needle = "{{" + name.lower()
    # MediaWiki treats underscores and spaces as equivalent in template names
    needle_sp = needle.replace("_", " ")
    needle_us = needle.replace(" ", "_")
    i = low.find(needle)
    if i < 0:
        i = low.find(needle_sp)
    if i < 0:
        i = low.find(needle_us)
    if i < 0:
        # Also accept optional whitespace between {{ and name
        import re as _re
        pat = _re.compile(r"\{\{\s*" + _re.escape(name) + r"\b", _re.I)
        m = pat.search(text)
        if m is None:
            return None
        i = m.start()
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
            # Defensive: depth must not go negative for well-formed input
            if depth < 0:
                raise ValueError("unbalanced {{%s}} block, never closed: %r" % (name, text[i:i + 60]))
            continue
        j += 1
    raise ValueError("unbalanced {{%s}} block, never closed: %r" % (name, text[i:i + 60]))


def fields(block):
    """Split a template block into its top-level named parameters. A '|' nested inside
    {{ }} or [[ ]] belongs to the value, not to the parameter list."""
    if block is None:
        return {}
    inner = block[2:-2] if block.startswith("{{") and block.endswith("}}") else block
    # Track a stack so [[ is closed by ]] and {{ by }}, not interchangeably.
    stack, cur, parts = [], "", []
    k = 0
    while k < len(inner):
        if inner.startswith("{{", k) or inner.startswith("[[", k):
            stack.append(inner[k:k + 2])
            cur += inner[k:k + 2]
            k += 2
            continue
        if inner.startswith("}}", k):
            if stack and stack[-1] == "{{":
                stack.pop()
            # Mismatched close still pops if anything is open, to avoid
            # infinite depth that would hide real separators.
            elif stack:
                stack.pop()
            cur += inner[k:k + 2]
            k += 2
            continue
        if inner.startswith("]]", k):
            if stack and stack[-1] == "[[":
                stack.pop()
            elif stack:
                stack.pop()
            cur += inner[k:k + 2]
            k += 2
            continue
        if inner[k] == "|" and not stack:
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
