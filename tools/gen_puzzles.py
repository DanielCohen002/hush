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


def sudoku_pack(n, rnd, N=6, BR=2, BC=3, blanks=(16, 20)):

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
        sol = [0] * (N * N)
        fill(sol)
        given = sol[:]
        order = list(range(N * N))
        rnd.shuffle(order)
        removed = 0
        target = rnd.randint(*blanks)
        for cell in order:
            if removed >= target:
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


def wordsearch_pack(n, rnd, size=8, count=6, lens=(4, 6), min_count=20,
                    dirs=((0, 1), (1, 0), (1, 1), (-1, 1))):
    """dirs: (row step, col step); the default is right, down, down-right, up-right."""
    words, _ = common_words(lens[0], lens[1], min_count)
    words = sorted(words)
    out, seen = [], set()
    while len(out) < n:
        picks = rnd.sample(words, count)
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
        if len(placed) < count:
            continue
        letters = "etaoinsrhlcdumfpgwybvk"
        flat = "".join(ch or rnd.choice(letters) for row in grid for ch in row)
        if flat in seen:
            continue
        seen.add(flat)
        out.append({"g": flat.upper(), "w": [w.upper() for w in placed]})
    return out


# 5x5 block patterns ("#" = black). Every white cell must sit in a word of 3+ letters, no run
# of exactly 2, and the whites must be connected; validated below.
TEMPLATES = [
    [".....", ".#.#.", ".....", ".#.#.", "....."],   # lattice: 3 across x 3 down
    [".....", ".###.", ".###.", ".###.", "....."],   # frame: a ring of four words
    [".###.", ".###.", ".....", ".###.", ".###."],   # H: two posts and a bar
    [".....", ".####", ".....", ".####", "....."],   # comb: spine and three teeth
    [".....", "####.", ".....", ".####", "....."],   # snake: an S through the grid
    [".....", ".###.", ".###.", ".###.", "#...."],   # frame with one corner cut
]


def slots_of(tpl):
    """(across, down) slots as lists of (row, col), in reading order of their first cell."""
    h, w = len(tpl), len(tpl[0])
    across, down = [], []
    for r in range(h):
        run = []
        for c in range(w + 1):
            if c < w and tpl[r][c] != "#":
                run.append((r, c))
            else:
                if len(run) >= 3:
                    across.append(run)
                run = []
    for c in range(w):
        run = []
        for r in range(h + 1):
            if r < h and tpl[r][c] != "#":
                run.append((r, c))
            else:
                if len(run) >= 3:
                    down.append(run)
                run = []
    across.sort(key=lambda sl: sl[0])
    down.sort(key=lambda sl: sl[0])
    return across, down


def valid_template(tpl):
    h, w = len(tpl), len(tpl[0])
    whites = {(r, c) for r in range(h) for c in range(w) if tpl[r][c] != "#"}
    across, down = slots_of(tpl)
    covered = {cell for sl in across + down for cell in sl}
    if covered != whites:
        return False
    # no run of exactly 2 in either direction
    for lines in ([[tpl[r][c] for c in range(w)] for r in range(h)],
                  [[tpl[r][c] for r in range(h)] for c in range(w)]):
        for line in lines:
            for run in "".join(line).split("#"):
                if len(run) == 2:
                    return False
    seen, stack = set(), [next(iter(whites))]
    while stack:
        cur = stack.pop()
        if cur in seen:
            continue
        seen.add(cur)
        r, c = cur
        stack += [x for x in ((r + 1, c), (r - 1, c), (r, c + 1), (r, c - 1)) if x in whites]
    return seen == whites


def crossword_pack(n, rnd):
    """Mini crosswords on several different 5x5 block patterns (TEMPLATES), filled from
    WordNet words with a dictionary-definition clue each."""
    w45, g45 = common_words(4, 5, 8)
    w3, g3 = common_words(3, 3, 30)
    gloss = {**g45, **g3}
    clues = {w: clue_for(w, gloss[w][1]) for w in w45 + w3}
    by_len = {}
    for w in sorted(w45 + w3):
        if clues[w]:
            by_len.setdefault(len(w), []).append(w)
    by_len_set = {k: set(v) for k, v in by_len.items()}
    templates = [t for t in TEMPLATES if valid_template(t)]
    out, seen, tries = [], set(), 0

    def fill(grid, slots, used, budget):
        # most-constrained slot first
        best, best_cands = None, None
        for sl in slots:
            pat = [grid[r][c] for r, c in sl]
            if all(pat):
                # A completed slot (placed, or forced by its crossings) must be a real word.
                if "".join(pat) not in by_len_set[len(sl)]:
                    return False
                continue
            cands = [w for w in by_len[len(sl)] if w not in used
                     and all(p is None or p == ch for p, ch in zip(pat, w))]
            if best is None or len(cands) < len(best_cands):
                best, best_cands = sl, cands
                if not cands:
                    return False
        if best is None:
            return True
        rnd.shuffle(best_cands)
        for w in best_cands[:12]:
            budget[0] -= 1
            if budget[0] < 0:
                return False
            old = [grid[r][c] for r, c in best]
            for (r, c), ch in zip(best, w):
                grid[r][c] = ch
            used.add(w)
            if fill(grid, slots, used, budget):
                return True
            used.discard(w)
            for (r, c), ch in zip(best, old):
                grid[r][c] = ch
        return False

    # Dense shapes can be infeasible with a few-thousand-word list: a shape that keeps failing
    # without ever succeeding is dropped, so the rest of the budget goes to shapes that work.
    fails = {id(t): 0 for t in templates}
    wins = {id(t): 0 for t in templates}
    while len(out) < n and tries < 20000 and templates:
        tries += 1
        tpl = rnd.choice(templates)
        across, down = slots_of(tpl)
        grid = [[None if ch != "#" else "#" for ch in row] for row in tpl]
        used = set()
        if not fill(grid, across + down, used, [250]):
            fails[id(tpl)] += 1
            if fails[id(tpl)] >= 120 and wins[id(tpl)] == 0:
                templates.remove(tpl)
                print("  dropped infeasible shape:", " ".join(tpl))
            continue
        wins[id(tpl)] += 1
        words_in = ["".join(grid[r][c] for r, c in sl) for sl in across + down]
        if len(set(words_in)) != len(words_in):
            continue  # a crossing-forced word repeated another one
        flat = "".join(ch for row in grid for ch in row).upper()
        if flat in seen:
            continue
        seen.add(flat)
        word = lambda sl: "".join(grid[r][c] for r, c in sl)
        out.append({"g": flat, "a": [clues[word(sl)] for sl in across], "d": [clues[word(sl)] for sl in down]})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--sudoku", type=int, default=400)
    ap.add_argument("--wordsearch", type=int, default=300)
    ap.add_argument("--crossword", type=int, default=250)
    ap.add_argument("--easy", type=int, default=300, help="count for each of the easy packs")
    ap.add_argument("--only", default="", help="comma-separated pack names to rebuild (default: all)")
    ap.add_argument("--out", default=OUT, help="output directory (default: the app's bundled assets)")
    args = ap.parse_args()
    rnd = random.Random(args.seed)
    os.makedirs(args.out, exist_ok=True)
    packs = (
        ("sudoku", lambda n: sudoku_pack(n, rnd), args.sudoku),
        # Easy: 4x4 sudoku (2x2 boxes), about half the cells given.
        ("sudoku4", lambda n: sudoku_pack(n, rnd, N=4, BR=2, BC=2, blanks=(6, 8)), args.easy),
        ("wordsearch", lambda n: wordsearch_pack(n, rnd), args.wordsearch),
        # Easy: 6x6 grid, 4 short common words, across, down and both diagonals.
        ("wordsearch6", lambda n: wordsearch_pack(n, rnd, size=6, count=4, lens=(3, 5),
                                                   min_count=60, dirs=((0, 1), (1, 0), (1, 1), (-1, 1))), args.easy),
        ("crossword", lambda n: crossword_pack(n, rnd), args.crossword),
    )
    only = {x for x in args.only.split(",") if x}
    for name, fn, n in packs:
        if only and name not in only:
            continue
        pack = fn(n)
        rnd.shuffle(pack)
        with open(os.path.join(args.out, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(pack, f, separators=(",", ":"))
        print(name, len(pack))


if __name__ == "__main__":
    main()
