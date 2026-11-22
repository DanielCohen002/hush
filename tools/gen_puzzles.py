#!/usr/bin/env python3
"""Builds the puzzle packs shipped in app/src/main/assets/puzzles/.

The app never generates puzzles at runtime: it just walks through these packs, so a puzzle
never repeats until the whole pack has been used. To refresh the library, re-run this with a
different --seed (or bigger counts) and rebuild the app.

    pip install nltk && python tools/gen_puzzles.py

The bigger downloadable library the app fetches with "Download new puzzles" lives in
puzzle-packs/ at the repo root; rebuild it with a fresh seed to give users new puzzles:

    python tools/gen_puzzles.py --seed 7 --sudoku 1200 --wordsearch 800 --crossword 600 --out puzzle-packs

Word and clue source: Princeton WordNet (permissive licence), via nltk.
"""
import argparse
import json
import os
import random

import nltk

nltk.download("wordnet", quiet=True)
from nltk.corpus import wordnet as wn  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "puzzles")

# Keep the packs family-friendly: WordNet is a general dictionary.
BLOCK = set("""
anus arse bitch bomb booze bugger butt cocaine coitus crack crap cunt damn dick dildo drunk
fag fuck gay hell heroin homo horny incest jerk kill lust molest murder naked nazi negro nigga
nude orgy penis pervert piss porn porno poop prick pussy queer rape rapist rectum sex sexy
shit slave slut sperm spic suicide tit tits twat vagina virgin vomit whore wank weed
abuse arson attack blood corpse death devil drugs gun guns hang hate killer opium
""".split())


def common_words(min_len, max_len, min_count):
    """Lowercase single words with their most common sense, by WordNet corpus frequency."""
    freq = {}
    gloss = {}
    for syn in wn.all_synsets():
        for lem in syn.lemmas():
            w = lem.name()
            if not w.isalpha() or not w.islower() or not (min_len <= len(w) <= max_len):
                continue
            c = lem.count()
            freq[w] = freq.get(w, 0) + c
            if c >= gloss.get(w, (-1, ""))[0]:
                gloss[w] = (c, syn.definition())
    words = [w for w, c in freq.items() if c >= min_count and w not in BLOCK]
    return words, gloss


def clue_for(word, definition):
    first = definition.split(";")[0].strip()
    if "(" in first:
        first = first.split("(")[0].strip()
    stem = word[:4]
    if stem in first.lower() or len(first) < 6 or len(first) > 64:
        return None
    return first[0].upper() + first[1:]


def sudoku_pack(n, rnd):
    N, BR, BC = 6, 2, 3

    def can(g, pos, v):
        r, c = divmod(pos, N)
        for i in range(N):
            if g[r * N + i] == v or g[i * N + c] == v:
                return False
        br, bc = r // BR * BR, c // BC * BC
        return all(g[(br + a) * N + bc + b] != v for a in range(BR) for b in range(BC))

    def fill(g):
        if 0 not in g:
            return True
        pos = g.index(0)
        vals = list(range(1, N + 1))
        rnd.shuffle(vals)
        for v in vals:
            if can(g, pos, v):
                g[pos] = v
                if fill(g):
                    return True
                g[pos] = 0
        return False

    def count(g, limit):
        if 0 not in g:
            return 1
        pos, total = g.index(0), 0
        for v in range(1, N + 1):
            if can(g, pos, v):
                g[pos] = v
                total += count(g, limit - total)
                g[pos] = 0
                if total >= limit:
                    break
        return total

    out, seen = [], set()
    while len(out) < n:
        sol = [0] * 36
        fill(sol)
        given = sol[:]
        order = list(range(36))
        rnd.shuffle(order)
        removed = 0
        for cell in order:
            if removed >= rnd.randint(16, 20):
                break
            keep, given[cell] = given[cell], 0
            if count(given[:], 2) == 1:
                removed += 1
            else:
                given[cell] = keep
        key = "".join(map(str, given))
        if key not in seen:
            seen.add(key)
            out.append({"p": key, "s": "".join(map(str, sol))})
    return out


def wordsearch_pack(n, rnd):
    words, _ = common_words(4, 6, 20)
    words = sorted(words)
    dirs = [(0, 1), (1, 0), (1, 1), (-1, 1)]  # right, down, down-right, up-right
    size, out, seen = 8, [], set()
    while len(out) < n:
        picks = rnd.sample(words, 6)
        grid = [[""] * size for _ in range(size)]
        placed = []
        for w in picks:
            for _ in range(80):
                dr, dc = rnd.choice(dirs)
                r, c = rnd.randrange(size), rnd.randrange(size)
                er, ec = r + dr * (len(w) - 1), c + dc * (len(w) - 1)
                if not (0 <= er < size and 0 <= ec < size):
                    continue
                cells = [(r + dr * i, c + dc * i) for i in range(len(w))]
                if all(grid[a][b] in ("", w[i]) for i, (a, b) in enumerate(cells)):
                    for i, (a, b) in enumerate(cells):
                        grid[a][b] = w[i]
                    placed.append(w)
                    break
        if len(placed) < 6:
            continue
        letters = "etaoinsrhlcdumfpgwybvk"
        flat = "".join(ch or rnd.choice(letters) for row in grid for ch in row)
        if flat in seen:
            continue
        seen.add(flat)
        out.append({"g": flat.upper(), "w": [w.upper() for w in placed]})
    return out


def crossword_pack(n, rnd):
    """5x5 lattice: rows 0,2,4 across and columns 0,2,4 down are 5-letter words; (odd, odd)
    cells are black. Six words, nine crossings."""
    words, gloss = common_words(5, 5, 8)
    clues = {w: clue_for(w, gloss[w][1]) for w in words}
    words = sorted(w for w in words if clues[w])
    by_triple = {}
    for w in words:
        by_triple.setdefault((w[0], w[2], w[4]), []).append(w)
    out, seen, tries = [], set(), 0
    while len(out) < n and tries < 3_000_000:
        tries += 1
        a = [rnd.choice(words) for _ in range(3)]
        downs = []
        for j in (0, 2, 4):
            cand = by_triple.get((a[0][j], a[1][j], a[2][j]))
            if not cand:
                break
            downs.append(rnd.choice(cand))
        else:
            ws = a + downs
            if len(set(ws)) != 6:
                continue
            grid = [["#"] * 5 for _ in range(5)]
            for i, r in enumerate((0, 2, 4)):
                for c in range(5):
                    grid[r][c] = a[i][c]
            for i, c in enumerate((0, 2, 4)):
                for r in range(5):
                    grid[r][c] = downs[i][r]
            flat = "".join("".join(row) for row in grid).upper()
            if flat in seen:
                continue
            seen.add(flat)
            out.append({"g": flat, "a": [clues[w] for w in a], "d": [clues[w] for w in downs]})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--sudoku", type=int, default=400)
    ap.add_argument("--wordsearch", type=int, default=300)
    ap.add_argument("--crossword", type=int, default=250)
    ap.add_argument("--out", default=OUT, help="output directory (default: the app's bundled assets)")
    args = ap.parse_args()
    rnd = random.Random(args.seed)
    os.makedirs(args.out, exist_ok=True)
    for name, fn, n in (
        ("sudoku", sudoku_pack, args.sudoku),
        ("wordsearch", wordsearch_pack, args.wordsearch),
        ("crossword", crossword_pack, args.crossword),
    ):
        pack = fn(n, rnd)
        rnd.shuffle(pack)
        with open(os.path.join(args.out, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(pack, f, separators=(",", ":"))
        print(name, len(pack))


if __name__ == "__main__":
    main()
