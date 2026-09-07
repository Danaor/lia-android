"""Generate the lexicon parity corpus from Lia Desktop's own lexicon.py.

Runs the desktop implementation over a word list against the REAL hspell
dictionary and records what it returns. `LexiconParityTest` then replays the
same words through the Kotlin port and must agree on every one.

Only Hebrew word forms are written out - no private content - so the corpus is
safe for the public repo. The dictionary itself is never copied.

    python tools/lexicon_parity.py
"""

from __future__ import annotations

import json
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import lia_keys  # noqa: E402

DESKTOP = os.environ.get("LIA_DESKTOP_DIR", lia_keys.DEFAULT_DESKTOP_DIR)

# The cases the plan names, plus everyday words that must not be touched.
CURATED = [
    "ביטולין", "לביטולין", "ובביטולין", "מעונין", "הבנין", "נישואין",
    "רבין", "עירובין", "קנין", "דין", "מין", "ענין", "בנין", "אין",
    "שלום", "פגישה", "מחשב", "ביטולים", "מעוניין", "בניין",
]


def main() -> int:
    appdata = os.environ.get("APPDATA") or os.path.expanduser("~")
    dic = os.environ.get("LIA_DICT_FILE") or os.path.join(
        appdata, "Lia", "lexicon", "he_IL.dic"
    )
    if not os.path.exists(dic):
        print(f"no dictionary at {dic} - enable the spelling guard on the desktop first",
              file=sys.stderr)
        return 2

    sys.path.insert(0, DESKTOP)
    import lexicon as desktop_lexicon  # type: ignore

    lex = desktop_lexicon.Lexicon(dic)
    lex.load()
    print(f"dictionary loaded: {lex.word_count()} forms")

    # Add real -ין forms harvested from the dictionary so the corpus covers
    # words a curated list would never think of.
    random.seed(11)
    harvested = []
    with open(dic, encoding="utf-8") as fh:
        next(fh, None)
        for line in fh:
            word = line.split("/", 1)[0].strip()
            if len(word) >= 4 and word.endswith("ים"):
                harvested.append(word[:-2] + "ין")
    random.shuffle(harvested)

    words = CURATED + harvested[:180]
    cases = []
    for word in words:
        cases.append(
            {
                "word": word,
                "valid": bool(lex.valid(word)),
                "fixed": lex.fix_word(word),
            }
        )

    out = os.path.join(ROOT, "fixtures", "lexicon_expectations.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(
            {
                "source": "lia desktop lexicon.py",
                "dictionary_sha256_prefix": "9bd95042",
                "cases": cases,
            },
            fh,
            ensure_ascii=False,
            indent=1,
        )
    fixed = sum(1 for c in cases if c["fixed"])
    print(f"wrote {out}: {len(cases)} cases, {fixed} of them auto-fixed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
