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
    wiki.FIXTURES.mkdir(parents=True, exist_ok=True)
    index = {}
    for cat in CATEGORIES:
        titles = [t for t in wiki.pages_in(cat.replace(" ", "_")) if not t.startswith("Category:")]
        print("%-14s %d pages" % (cat, len(titles)))
        index[cat] = sorted(titles)
        for title, text in wiki.contents(titles).items():
            if text is None:
                print("  MISSING CONTENT: %s" % title)
                continue
            (wiki.FIXTURES / wiki.safe_filename(title)).write_text(text, encoding="utf8")
    for title in SINGLES:
        print("%-14s 1 page" % title)
        index.setdefault("Singles", []).append(title)
        (wiki.FIXTURES / wiki.safe_filename(title)).write_text(wiki.raw(title), encoding="utf8")
    (wiki.FIXTURES / "index.json").write_text(
        json.dumps(index, indent=2, sort_keys=True), encoding="utf8")
    print("wrote fixtures to %s" % wiki.FIXTURES)


if __name__ == "__main__":
    main()
