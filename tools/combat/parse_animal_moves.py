# Parses Category:Animal Moves fixtures into data/combat/animal_moves.json.
#
#   python tools/combat/parse_animal_moves.py
#
# The pages come in TWO shapes and both carry real data:
#   * 10 use {{Animal Combat Move | move= | inf1= | opn1= | rdc1= | note= }}, with colours
#     named by the four opening colours (Green/Blue/Yellow/Red).
#   * 31 are hand-written wikitables with *Attack type:/*Openings:/*Reduces: bullets using
#     {{RoB color|<school>|<label>}}, where <school> is the striking/backhanded/sweeping/
#     oppressive name directly (regardless of which bullet it appears in).
# Everything is normalised to the four school names so downstream code sees one vocabulary.
#
# `openings` and `reduces` are semantically OPPOSITE and are kept as separate fields, never
# folded together: `openings` is what a move INFLICTS on its target (inf1..4 / opn1..4 /
# *Openings:), `reduces` is what a move REMOVES from its own user (rdc1..4 / *Reduces:).
# Bristle (rdc1..4 = all four colours) is a restoration move -- recording that as `openings`
# would claim it inflicts every opening on its target, the exact opposite of the truth.
#
# Two pages -- Dark Heart Move (a pure IP generator, no opening data on the page at all) and
# Trunk Gunk (a ranged attack; "Attack type: Ranged" is literal text, not {{RoB color}}) sit
# outside the four-school system entirely. They still produce records (every page must), but
# datapack_check.py asserts the colourless set is EXACTLY {"Dark Heart Move", "Trunk Gunk"}
# rather than silently allowing "empty is fine" -- a third colourless page would still fail
# loudly. Trunk Gunk's literal "Ranged" is kept in raw_attack_type rather than discarded or
# stuffed into the attack_types vocabulary the simulator switches on.
#
# A page that HAS the {{Animal Combat Move}} template but where it never closes (unbalanced
# braces) is malformed, not absent -- wiki.extract_template raises ValueError for that case,
# and it must land in its own malformed bucket rather than falling through to the table
# parser, which would silently produce a half-parsed record.

import sys, os, json, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wiki

OUT = wiki.DATA

# Green/Blue/Yellow/Red are the opening colours; striking/backhanded/sweeping/oppressive are the
# matching attack schools. Verified against OptWnd.improvedOpeningsImageColor in the client.
COLOUR_TO_SCHOOL = {
    "green": "striking", "blue": "backhanded",
    "yellow": "sweeping", "red": "oppressive",
}
SCHOOLS = ("striking", "backhanded", "sweeping", "oppressive")

ROBCOLOR_RE = re.compile(r"\{\{RoB color\|\s*([a-zA-Z ]+?)\s*\|", re.I)
# Digits-only IP match, used against the template's note= field (e.g. "IP-2", no bullet marker).
NOTE_IP_RE = re.compile(r"IP\s*([+-]?\s*\d+)", re.I)
# The whole remainder of a "*IP ..." bullet line, used against table-form page text so an
# unparseable value (e.g. "IP ??" on Trumpeting Fury) still keeps its raw text via wiki.num
# instead of collapsing to a bare None.
TABLE_IP_RE = re.compile(r"^\*\s*IP\s*(.*)$", re.I | re.M)
BULLET_RE = r"\*\s*%s\s*:?(.*)"


def _dedup(seq):
    out = []
    for x in seq:
        if x and x not in out:
            out.append(x)
    return out


def _schools_in(fragment):
    # {{RoB color|<school>|<label>}} matches, filtered to the four known school names and
    # deduplicated in first-seen order. Works for Attack type / Openings / Reduces bullets alike
    # -- all three consistently use the school name as the RoB color CSS class, whatever the
    # bullet's own label text says (e.g. Bear Hug's Openings bullet uses class "striking" even
    # though its label is "Off Balance").
    if not fragment:
        return []
    raw = [m.lower().strip() for m in ROBCOLOR_RE.findall(fragment)]
    # Surface unknown school names rather than silently dropping them
    unknown = [m for m in raw if m not in SCHOOLS]
    if unknown:
        # Keep trace via stderr so build doesn't silently absorb a new school
        import sys as _sys
        _sys.stderr.write("unknown school(s) %r in fragment %r\n" % (unknown, fragment[:80]))
    return _dedup(m for m in raw if m in SCHOOLS)


def _bullet(text, label):
    # Return the stripped remainder of the "*<label>: ..." bullet, or None if that bullet is
    # absent altogether (as opposed to present-but-empty).
    m = re.search(BULLET_RE % re.escape(label), text, re.I)
    return m.group(1).strip() if m else None


def _table_note(text):
    # Every "*" bullet in the cell that isn't Attack type/Openings/Reduces/IP is free-text
    # commentary the wiki author added (e.g. "Used only in Rage mode.", "Strongest Attack",
    # "Shoot a projectile with 50 damage that can be dodged"). Keep all of it, joined -- matching
    # only one narrow phrase (as the brief's illustrative regex did) would silently drop the
    # rest, and for Dark Heart Move / Trunk Gunk this free text is the ONLY real content the
    # page has beyond the IP bullet.
    skip = re.compile(r"^(Attack type|Openings|Reduces|IP)\b", re.I)
    extra = [ln.strip() for ln in re.findall(r"^\*\s*(.*)$", text, re.M)
             if ln.strip() and not skip.match(ln.strip())]
    return " ".join(extra)


def _template_note(raw_note):
    # Template note= sometimes carries ONLY the IP delta ("IP-2") and nothing else -- that
    # value already lives in the ip field, so strip it here rather than duplicating it as
    # commentary. Whatever remains is genuine free text.
    if not raw_note:
        return ""
    return NOTE_IP_RE.sub("", raw_note).strip(" .,-")


def _from_template(title, block, mismatches):
    f = wiki.fields(block)
    move_param = f.get("move", "").strip()
    if move_param and move_param != title:
        mismatches.append((title, move_param))
    def _colour_schools(keys):
        out = []
        for k in keys:
            v = f.get(k, "").strip()
            if not v:
                continue
            mapped = COLOUR_TO_SCHOOL.get(v.lower())
            if mapped is None:
                import sys as _sys
                _sys.stderr.write("unknown colour %r in %s %s\n" % (v, title, k))
                continue
            if mapped not in out:
                out.append(mapped)
        return out
    atk = _colour_schools(("inf1", "inf2", "inf3", "inf4"))
    opn = _colour_schools(("opn1", "opn2", "opn3", "opn4"))
    rdc = _colour_schools(("rdc1", "rdc2", "rdc3", "rdc4"))
    raw_note = f.get("note", "")
    m = NOTE_IP_RE.search(raw_note)
    ip = wiki.num(m.group(1)) if m else None
    return {"name": title, "source": "template",
            "attack_types": atk, "raw_attack_type": None,
            "openings": opn, "reduces": rdc,
            "ip": ip, "note": _template_note(raw_note)}


def _from_table(title, text):
    attack_line = _bullet(text, "Attack type")
    opn_line = _bullet(text, "Openings")
    rdc_line = _bullet(text, "Reduces")
    atk = _schools_in(attack_line) if attack_line is not None else []
    opn = _schools_in(opn_line) if opn_line is not None else []
    rdc = _schools_in(rdc_line) if rdc_line is not None else []
    # An "Attack type:" bullet that names no recognised school (Trunk Gunk: "Ranged") is real
    # information the {{RoB color}} scan can't capture -- keep it verbatim rather than dropping
    # it, but keep it OUT of attack_types so that vocabulary stays clean for the simulator.
    raw_attack_type = attack_line if (attack_line is not None and not atk) else None
    m = TABLE_IP_RE.search(text)
    ip = wiki.num(m.group(1)) if m else None
    return {"name": title, "source": "table",
            "attack_types": atk, "raw_attack_type": raw_attack_type,
            "openings": opn, "reduces": rdc,
            "ip": ip, "note": _table_note(text)}


def parse():
    index = json.loads(wiki.load_fixture("index.json"))
    records, unparsed, malformed, mismatches = [], [], [], []
    for title in index["Animal Moves"]:
        try:
            text = wiki.load_fixture(wiki.safe_filename(title))
        except FileNotFoundError:
            unparsed.append(title)
            continue
        try:
            block = wiki.extract_template(text, "Animal Combat Move")
        except ValueError:
            malformed.append(title)
            continue
        if block is not None:
            records.append(_from_template(title, block, mismatches))
        else:
            records.append(_from_table(title, text))
    records.sort(key=lambda r: r["name"])
    return records, sorted(unparsed), sorted(malformed), mismatches


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    records, unparsed, malformed, mismatches = parse()
    if unparsed:
        print("UNPARSED animal moves (%d): %s" % (len(unparsed), unparsed))
    if malformed:
        print("MALFORMED %d page(s) with an unbalanced {{Animal Combat Move}} block:" % len(malformed))
        for t in malformed:
            print("    %s" % t)
    if mismatches:
        print("move= parameter disagrees with page title (page title wins, kept as name):")
        for title, move_param in mismatches:
            print("    %-24s move=%s" % (title, move_param))
    (OUT / "animal_moves.json").write_text(
        json.dumps(records, indent=2, sort_keys=True), encoding="utf8")
    tpl = len([r for r in records if r["source"] == "template"])
    print("animal moves %3d records (%d template, %d table) -> %s"
          % (len(records), tpl, len(records) - tpl, OUT / "animal_moves.json"))
    if unparsed or malformed:
        sys.exit(1)


if __name__ == "__main__":
    main()
