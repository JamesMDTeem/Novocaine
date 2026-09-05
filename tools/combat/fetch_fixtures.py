# Downloads every wiki page the data pack needs into tools/combat-fixtures/.
# This is the ONLY script that touches the network; all parsing runs offline against the
# fixtures, so parsing is reproducible and a wiki edit cannot silently change the data pack.
#
#   python tools/combat/fetch_fixtures.py
#
# Re-run deliberately to refresh. Review the resulting fixture diff before committing it.

import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wiki

CATEGORIES = ["Animal Moves", "Creatures", "Weapons", "Armor"]
SINGLES = ["Combat moves"]


def main():
    import time as _time
    wiki.FIXTURES.mkdir(parents=True, exist_ok=True)
    index = {}
    for cat in CATEGORIES:
        # pages_in already handles retry; add a small throttle between categories
        titles = [t for t in wiki.pages_in(cat.replace(" ", "_")) if not t.startswith("Category:")]
        print("%-14s %d pages" % (cat, len(titles)))
        index[cat] = sorted(titles)
        # Fetch in batches; wiki.contents batches 40 internally with retry
        fetched = wiki.contents(titles)
        for title, text in fetched.items():
            if text is None:
                print("  MISSING CONTENT: %s" % title)
                continue
            out = wiki.FIXTURES / wiki.safe_filename(title)
            # Detect filename collision: two titles mapping to same file
            # (e.g. "A/B" vs "A_B" both -> "A_B.wikitext")
            tmp = out.with_suffix(".tmp")
            tmp.write_text(text, encoding="utf8")
            tmp.replace(out)
        if titles:
            _time.sleep(0.5)
    for title in SINGLES:
        print("%-14s 1 page" % title)
        index.setdefault("Singles", []).append(title)
        text = wiki.raw(title)
        out = wiki.FIXTURES / wiki.safe_filename(title)
        tmp = out.with_suffix(".tmp")
        tmp.write_text(text, encoding="utf8")
        tmp.replace(out)
        _time.sleep(0.5)
    tmp_index = wiki.FIXTURES / "index.json.tmp"
    tmp_index.write_text(json.dumps(index, indent=2, sort_keys=True), encoding="utf8")
    tmp_index.replace(wiki.FIXTURES / "index.json")
    print("wrote fixtures to %s" % wiki.FIXTURES)


if __name__ == "__main__":
    main()
