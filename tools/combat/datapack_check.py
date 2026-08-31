# Verification harness for the combat data pack. Mirrors the tools/*Check.java convention:
# a single file with a main(), run on demand, exits 0 when every check passes and 1 otherwise.
#
#   python tools/combat/datapack_check.py
#
# Parsing checks run against the checked-in fixtures in tools/combat-fixtures/, so this never
# touches the network and a wiki edit cannot silently change what it verifies.

import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wiki

failures = 0

def check(what, got, want):
    global failures
    ok = got == want
    print("  %-52s %-28s %s" % (what, repr(got)[:28], "ok" if ok else "WANT " + repr(want)[:40]))
    if not ok:
        failures += 1

def primitives():
    print("wiki primitives")
    # Brace-depth: a nested {{#expr:}} must not terminate extraction early.
    text = "{{infobox creature\n| xobst = {{#expr:(4/11)-(-4/11)round6}}\n| hp = 850\n| armor = 65\n}}"
    block = wiki.extract_template(text, "infobox creature")
    check("extract spans nested braces", block is not None and block.endswith("}}"), True)
    f = wiki.fields(block)
    check("field after nested template", f.get("hp"), "850")
    check("second field after nested", f.get("armor"), "65")
    check("nested value kept whole", f.get("xobst"), "{{#expr:(4/11)-(-4/11)round6}}")
    # A pipe inside a wikilink is not a field separator.
    f2 = wiki.fields("{{infobox metaobj\n| loot = [[Fresh Bear Hide|hide]] x2\n| basedmg = 150\n}}")
    check("pipe inside wikilink ignored", f2.get("basedmg"), "150")
    check("missing template returns None", wiki.extract_template("no box here", "infobox creature"), None)
    # Found-but-never-closed must not be mistaken for "not present" -- it has to raise,
    # not return None, or a later consumer treating None as "no infobox" would silently
    # absorb a genuine parse failure into that same bucket.
    try:
        wiki.extract_template("{{infobox creature\n| hp = 850", "infobox creature")
        outcome = "no raise"
    except ValueError:
        outcome = "ValueError"
    check("unbalanced block raises ValueError", outcome, "ValueError")
    # Tolerant numerics: the wiki is not always numeric.
    check("plain int", wiki.num("850"), {"raw": "850", "value": 850})
    check("approx value", wiki.num("~500"), {"raw": "~500", "value": 500})
    check("unparseable kept raw", wiki.num("varies"), {"raw": "varies", "value": None})
    check("empty is null", wiki.num(""), {"raw": "", "value": None})

def main():
    primitives()
    print("\nALL CHECKS PASSED" if failures == 0 else "\n%d CHECK(S) FAILED" % failures)
    sys.exit(0 if failures == 0 else 1)

if __name__ == "__main__":
    main()
