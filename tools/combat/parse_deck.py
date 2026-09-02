#!/usr/bin/env python3
"""Turn the client's combat-deck dumps into data/combat/moves_ingame.json.

    python tools/combat/parse_deck.py [bin/CombatLogs/deck-*.json]

The wiki-derived moves.json is a transcription of a third-party page. This is the
game's own text for the character who produced the dump: initiative cost, attack
weight, attack type, openings inflicted and reduced, damage, grievous fraction and
base cooldown - plus how many levels of each move the character has bought and how
many are in the deck, which nothing outside the client knows.

Stdlib only. Reads dumps, writes one JSON file, and cross-checks against the
wiki-derived pack. Like build_datapack.py it collects every problem first and writes
nothing if any survive, so a partial parse can never be mistaken for a good one.
"""

import json
import sys
import glob
import os
import re
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
DATA = os.path.join(ROOT, "data", "combat")
OUT = os.path.join(DATA, "moves_ingame.json")
# The same moves with every character-specific field removed. See publish() for the
# argument that this half is safe to commit and the other half is not.
PUBLIC = os.path.join(DATA, "moves_sheet.json")

# Fields that describe the CHARACTER rather than the game: what it has bought, how far
# it has levelled each move, and the raw sheet text those were read from.
PRIVATE_FIELDS = ("maxlevel", "decklevel", "pagina")
WIKI = os.path.join(DATA, "moves.json")

# The sheet colours each opening and each attack school, and the two use the same four
# colours - which is the mapping the whole model turns on, stated by the game itself
# rather than inferred. Keyed on the exact RGB the client renders.
COLOUR = {
    "128,255,160": ("green", "Off Balance", "Striking"),
    "128,192,255": ("blue", "Dizzy", "Backhanded"),
    "255,255,128": ("yellow", "Reeling", "Sweeping"),
    "255,128,128": ("red", "Cornered", "Oppressive"),
}
BY_WORD = {}
for _rgb, (_c, _open, _school) in COLOUR.items():
    BY_WORD[_open.lower()] = _c
    BY_WORD[_school.lower()] = _c

# Fields the sheet uses. Anything labelled but not listed here is kept as an extra
# rather than dropped, and reported - an unknown label is new game data, not noise.
KNOWN = [
    "Weapon", "Attack weight", "Block weight", "Attack type", "Attack types",
    "Openings", "Openings on you", "Reduces", "Damage", "Grievous damage",
    "Initiative points", "Cooldown", "When attacked", "Opponents' initiative points",
]

FIELD_RE = re.compile(r"^([A-Z][A-Za-z' ]{2,32}):\s*(.*)$")
# "+15% Cornered", "+7.5% Off Balance", "20% - mu Striking"
TERM_RE = re.compile(r"([+-]?\d+(?:\.\d+)?)\s*%\s*(.*)$")


def strip_markup(t):
    """Remove the client's rendering directives, keeping the text they wrap."""
    if not t:
        return ""
    prev = None
    while prev != t:
        prev = t
        t = re.sub(r"\$[a-z]+\[[^\]]*\]\{([^{}]*)\}", r"\1", t)
        t = re.sub(r"\$[a-z]+\{([^{}]*)\}", r"\1", t)
    t = re.sub(r"\$[a-z]+\[[^\]]*\]", "", t)
    return t


# "Attack weight: $img[gfx/hud/chr/unarmed,h=1ln] - mu" - the skill that feeds a move's
# attack weight is named by the icon, not by the text, so stripping the markup throws it
# away. That is the difference between a move weighted on Unarmed Combat and one weighted
# on Melee Combat, which is a factor of two on this character and decides every defence
# weight recovered from an opening gain.
SKILL_RE = re.compile(r"\$img\[gfx/hud/chr/([a-z]+)")


def skill_in(raw):
    """The character skill an attack- or block-weight line names by its icon."""
    m = SKILL_RE.search(raw or "")
    return m.group(1) if m else None


def mult_of(text):
    """The product of every percentage on a weight line, as a fraction.

    "Melee * 90% * mu" gives 0.9; a line with no percentage gives 1.0. Uppercut's sheet
    writes its factor as a bare "0.8" rather than "80%", so a plain decimal counts too.
    """
    if not text:
        return 1.0
    out = 1.0
    for tok in text.replace("·", " ").split():
        tok = tok.strip()
        try:
            if tok.endswith("%"):
                out *= float(tok[:-1]) / 100.0
            elif tok.replace(".", "", 1).isdigit() and "." in tok:
                out *= float(tok)
        except ValueError:
            pass
    return out


def split_damage(text):
    """A damage line as (share of the weapon's damage, flat damage).

    Exactly one of the two is set. "According to weapon * 25%" is a quarter share;
    "According to weapon" is a full one; "30" is flat.
    """
    if not text:
        return (None, None)
    if "weapon" in text.lower():
        return (mult_of(text), None)
    n = num(text)
    return (None, n) if n is not None else (None, None)


def colours_in(raw):
    """Colours named by the markup, before it is stripped, in order of appearance."""
    return [COLOUR[m][0] for m in re.findall(r"\$col\[(\d+,\d+,\d+)\]", raw)
            if m in COLOUR]


def parse_terms(raw, problems, where):
    """Parse "+15% Cornered, +5% Reeling" or "20% - mu Striking" into terms.

    The colour is taken from the markup where present and from the word otherwise;
    a term whose colour cannot be established is reported rather than dropped, since
    a silently missing opening is the difference between a move that opens red and
    one that opens nothing."""
    out = []
    plain = strip_markup(raw)
    marked = colours_in(raw)
    for i, part in enumerate(p.strip() for p in plain.split(",")):
        if not part:
            continue
        m = TERM_RE.match(part)
        if not m:
            problems.append("%s: cannot read term %r" % (where, part))
            continue
        rest = m.group(2).strip()
        # Scaled by the deck weighting when the text says so.
        mu = ("µ" in rest) or (" mu" in rest.lower())
        word = re.sub(r"^[·µ\s\-*x]*", "", rest).strip()
        colour = marked[i] if i < len(marked) else BY_WORD.get(word.lower())
        if colour is None:
            problems.append("%s: no colour for %r" % (where, part))
            continue
        out.append(OrderedDict([("pct", float(m.group(1))), ("name", word),
                                ("colour", colour), ("mu", mu)]))
    return out


def num(s):
    s = strip_markup(s).strip().rstrip("%")
    try:
        return float(s) if "." in s else int(s)
    except ValueError:
        return None


def parse_move(m, problems):
    raw = m.get("pagina")
    rec = OrderedDict()
    rec["res"] = m["res"]
    rec["name"] = m.get("name")
    # How far THIS CHARACTER has learned the move, not the game's ceiling. Everything
    # except stances goes to 5; a move showing a max of 1 has simply been picked up once.
    # Misreading it as the cap is easy and costly - it makes a move at 1 of 1 look fully
    # weighted, which would put its mu at the top of the range when it is really at the
    # bottom.
    rec["maxlevel"] = m.get("maxlevel")
    rec["decklevel"] = m.get("decklevel")
    if not raw:
        problems.append("%s: no pagina text" % m.get("name"))
        return None
    fields, notes, extras = OrderedDict(), [], []
    for line in raw.split("\n"):
        line = line.strip()
        if not line:
            continue
        fm = FIELD_RE.match(strip_markup(line))
        if not fm:
            notes.append(strip_markup(line).strip())
            continue
        label = fm.group(1).strip()
        # Re-take the value from the unstripped line so colour markup survives.
        value_raw = line.split(":", 1)[1].strip()
        if label not in KNOWN:
            extras.append(label)
        fields[label] = value_raw

    where = m.get("name")
    rec["weapon"] = strip_markup(fields.get("Weapon", "")).strip() or None
    rec["attack_weight"] = strip_markup(fields.get("Attack weight", "")).strip() or None
    rec["block_weight"] = strip_markup(fields.get("Block weight", "")).strip() or None
    # Which skill those weights are read from. Null for a move whose line says
    # "According to weapon", which the sheet's own closing note resolves to Melee Combat.
    rec["attack_skill"] = skill_in(fields.get("Attack weight", ""))
    rec["block_skill"] = skill_in(fields.get("Block weight", ""))
    rec["damage"] = strip_markup(fields.get("Damage", "")).strip() or None
    # The same line, as numbers a simulator can use without re-parsing English. A move
    # either takes a share of the weapon's damage or states a flat figure of its own,
    # never both, and the two scale differently: the weapon's share reads the weapon's
    # quality, while a flat figure is unarmed and reads the character's strength in its
    # place.
    rec["damage_share"], rec["damage_flat"] = split_damage(rec["damage"])
    # The percentage factors on the attack-weight line - the 90% in "Melee * 90% * mu".
    rec["weight_mult"] = mult_of(rec["attack_weight"])
    rec["block_mult"] = mult_of(rec["block_weight"])
    rec["grievous_pct"] = num(fields["Grievous damage"]) if "Grievous damage" in fields else None
    rec["initiative"] = num(fields["Initiative points"]) if "Initiative points" in fields else None
    # "Cooldown: 20" is a number; "Cooldown: 30 / mu" is a formula, and its presence is
    # itself a finding - it says the deck weighting shortens that move, which makes mu
    # readable straight off a reported cooldown instead of having to be fitted.
    cd_raw = strip_markup(fields.get("Cooldown", "")).strip()
    rec["cooldown_raw"] = cd_raw or None
    rec["cooldown_mu"] = None
    if cd_raw:
        cm = re.match(r"^\s*(\d+(?:\.\d+)?)\s*(?:/\s*(.+))?$", cd_raw)
        if cm:
            v = cm.group(1)
            rec["cooldown"] = float(v) if "." in v else int(v)
            rec["cooldown_mu"] = bool(cm.group(2))
        else:
            rec["cooldown"] = None
            problems.append("%s: cannot read cooldown %r" % (m.get("name"), cd_raw))
    else:
        rec["cooldown"] = None

    # Split after stripping, never before: "$col[255,128,128]{Oppressive}" contains two
    # commas of its own, and splitting the raw form turns one school into three shards.
    types_raw = fields.get("Attack type", fields.get("Attack types"))
    rec["attack_types"] = []
    if types_raw:
        marked = colours_in(types_raw)
        for i, t in enumerate(x.strip() for x in strip_markup(types_raw).split(",")):
            if not t:
                continue
            colour = marked[i] if i < len(marked) else BY_WORD.get(t.lower())
            if colour is None:
                problems.append("%s: no colour for attack type %r" % (where, t))
                continue
            rec["attack_types"].append(OrderedDict([("name", t), ("colour", colour)]))
    rec["openings"] = parse_terms(fields["Openings"], problems, where + " Openings") \
        if "Openings" in fields else []
    # Openings the move puts on the user, not the opponent. Folding these into
    # `openings` would record a cost as a benefit.
    rec["openings_on_self"] = parse_terms(fields["Openings on you"], problems,
                                          where + " Openings on you") \
        if "Openings on you" in fields else []
    rec["reduces"] = parse_terms(fields["Reduces"], problems, where + " Reduces") \
        if "Reduces" in fields else []
    rec["when_attacked"] = strip_markup(fields.get("When attacked", "")).strip() or None
    rec["opponent_initiative"] = num(fields["Opponents' initiative points"]) \
        if "Opponents' initiative points" in fields else None
    rec["notes"] = notes
    rec["pagina"] = raw
    if extras:
        problems.append("%s: unrecognised field label(s) %s" % (where, ", ".join(extras)))
    if rec["cooldown"] is None and not rec["cooldown_raw"]:
        problems.append("%s: no cooldown line" % where)
    return rec


def best_dump(paths):
    """The dump with the most moves carrying pagina text. Probes fire while the sheet
    is still loading, so early files in a session are legitimately thin."""
    best, chosen = None, None
    for p in paths:
        try:
            with open(p, "r", encoding="utf8") as f:
                d = json.load(f)
        except Exception:
            continue
        body = d.get("body", d)
        moves = body.get("moves") or []
        # Deck levels arrive in a separate server message from the action list, so an
        # early probe can have every move's text and no levels at all. Prefer a dump
        # that has both rather than silently reporting an empty deck.
        # Recency breaks a tie. Without it an equally rich dump from an earlier session
        # wins simply by being encountered first, and the deck reported is yesterday's -
        # which is how a move the character has since put points into showed up in the
        # fights while reading as level 0 in the deck.
        try:
            when = os.path.getmtime(p)
        except OSError:
            when = 0
        score = (sum(1 for m in moves if m.get("pagina")),
                 sum(1 for m in moves if (m.get("decklevel") or 0) > 0),
                 len(moves), when)
        if best is None or score > best:
            best, chosen = score, (p, body)
    return chosen


def crosscheck(recs, problems):
    """Compare against the wiki-derived pack. Disagreements are reported, never
    resolved: the game text is authoritative, but a mismatch is worth a human's eye
    because it may mean the wiki is stale, or that the two are naming different
    things."""
    try:
        with open(WIKI, "r", encoding="utf8") as f:
            wiki = json.load(f)
    except Exception:
        print("  (no wiki moves.json to compare against)")
        return
    rows = wiki if isinstance(wiki, list) else sum(
        (v for v in wiki.values() if isinstance(v, list)), [])
    byname = {}
    for w in rows:
        if isinstance(w, dict) and w.get("name"):
            byname[w["name"].strip().lower()] = w
    matched = agree = differ = 0
    for r in recs:
        w = byname.get((r["name"] or "").strip().lower())
        if w is None:
            continue
        matched += 1
        wc = w.get("cooldown")
        wc = wc.get("value") if isinstance(wc, dict) else wc
        if wc is None or r["cooldown"] is None:
            continue
        if abs(float(wc) - float(r["cooldown"])) < 0.001:
            agree += 1
        else:
            differ += 1
            print("    cooldown differs  %-24s game %-6s wiki %s"
                  % (r["name"], r["cooldown"], wc))
    print("  cross-check: %d of %d names matched the wiki pack, %d cooldowns agree, "
          "%d differ" % (matched, len(recs), agree, differ))


def main(argv):
    paths = []
    if argv:
        for a in argv:
            paths.extend(sorted(glob.glob(a)))
    else:
        # Every install on this machine. The Steam Workshop copy keeps its own directory,
        # and a client played through Steam writes its dumps there and nowhere near the
        # checkout.
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import fightlog
        for d in fightlog.find_log_dirs(ROOT):
            paths.extend(sorted(glob.glob(os.path.join(d, "deck-*.json"))))
    if not paths:
        print("no deck dumps found")
        return 2
    chosen = best_dump(paths)
    if chosen is None:
        print("no readable deck dump among %d file(s)" % len(paths))
        return 2
    path, body = chosen
    print("reading %s" % os.path.relpath(path, ROOT))

    problems, recs = [], []
    for m in body.get("moves") or []:
        r = parse_move(m, problems)
        if r is not None:
            recs.append(r)
    recs.sort(key=lambda r: r["name"] or r["res"])

    print("  %d move(s) parsed, %d with a deck level, %d point(s) of %s used"
          % (len(recs), sum(1 for r in recs if (r["decklevel"] or 0) > 0),
             sum(r["decklevel"] or 0 for r in recs), body.get("maxpoints")))
    crosscheck(recs, problems)

    if problems:
        print("\n%d problem(s); writing nothing:" % len(problems))
        for p in problems:
            print("  - " + p)
        return 1

    out = OrderedDict([
        ("source", "client combat-deck dump"),
        ("char", body.get("char")),
        ("maxpoints", body.get("maxpoints")),
        ("nsave", body.get("nsave")),
        ("colours", OrderedDict(
            (c, OrderedDict([("opening", o), ("school", s)]))
            for _rgb, (c, o, s) in sorted(COLOUR.items(), key=lambda kv: kv[1][0]))),
        ("moves", recs),
    ])
    os.makedirs(DATA, exist_ok=True)
    with open(OUT, "w", encoding="utf8") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("\nwrote %s  (gitignored - names a character and its build)"
          % os.path.relpath(OUT, ROOT))
    publish(out)
    return 0


def publish(out):
    """Write the character-free half of the dump, which is committed.

    The reason the full dump stays local is that it records which moves this character
    has bought and how far it has levelled each one. Everything else in it - a move's
    attack weight, the openings it inflicts and by how much, its damage, its cooldown -
    is a property of the game, true for every player, and no different in kind from the
    wiki-derived pack alongside it.

    Splitting on exactly that line gets the move data into the repository, where the
    simulator and the estimator can both depend on it being there, without publishing
    anything about whose character produced it. The opening percentages are the part
    that matters most: the wiki pack records which colour a move opens but not by how
    much, and without the percentage no defence weight can be recovered from a gain.
    """
    body = OrderedDict()
    for k, v in out.items():
        if k == "char":
            continue
        if k == "moves":
            v = [OrderedDict((mk, mv) for mk, mv in m.items()
                             if mk not in PRIVATE_FIELDS) for m in v]
        body[k] = v
    body["source"] = "client combat-deck dump, character-specific fields removed"
    with open(PUBLIC, "w", encoding="utf8") as f:
        json.dump(body, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("wrote %s  (committed - no character in it)"
          % os.path.relpath(PUBLIC, ROOT))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
