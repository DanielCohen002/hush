#!/usr/bin/env python3
"""Builds the puzzle packs shipped in app/src/main/assets/puzzles/.

The app never generates puzzles at runtime: it just walks through these packs, so a puzzle
never repeats until the whole pack has been used. To refresh the library, re-run this with a
different --seed (or bigger counts) and rebuild the app.

    pip install nltk wordfreq && python tools/gen_puzzles.py

The bigger downloadable library the app fetches with "Download new puzzles" lives in
puzzle-packs/ at the repo root; rebuild it with a fresh seed to give users new puzzles:

    python tools/gen_puzzles.py --seed 7 --sudoku 1200 --wordsearch 800 --crossword 600 --hangman 800 --out puzzle-packs

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
            gloss.setdefault(w, []).append((c, syn.definition(), syn.pos()))
    words = [w for w, c in freq.items() if c >= min_count and w not in BLOCK]
    # Most common sense first; a word's rarer senses (seen at least twice) give it extra clues.
    for w, senses in gloss.items():
        senses.sort(key=lambda t: -t[0])
        gloss[w] = [t for i, t in enumerate(senses) if i == 0 or (i < 3 and t[0] >= 2)]
    return words, gloss


POS_NAME = {"n": "noun", "v": "verb", "a": "adjective", "s": "adjective", "r": "adverb"}


def clue_for(word, definition, pos):
    """Short plain definition plus its part of speech, e.g. "Operate or control a vehicle (verb)"."""
    first = definition.split(";")[0].strip()
    if "(" in first:
        first = first.split("(")[0].strip()
    stem = word[:4]
    if stem in first.lower() or len(first) < 6 or len(first) > 44:
        return None
    return "%s (%s)" % (first[0].upper() + first[1:], POS_NAME.get(pos, "word"))


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
    # Least-used words first, and never a word from the puzzle just before, so words do not repeat
    # soon or back to back (the pack is walked in order, so it is not shuffled afterwards).
    usage = {}
    prev = set()
    recent = []  # (cells used by the words, each word's cells) of the last few puzzles
    out, seen = [], set()
    while len(out) < n:
        pool = sorted((w for w in words if w not in prev), key=lambda w: (usage.get(w, 0), rnd.random()))
        picks = []
        for w in pool:  # no word may be part of another word in the same puzzle ("ear" and "heart")
            if all(w not in x and x not in w for x in picks):
                picks.append(w)
                if len(picks) == count:
                    break
        rnd.shuffle(picks)
        grid = [[""] * size for _ in range(size)]
        placed = []
        lines = []
        for w in picks:
            for _ in range(80):
                dr, dc = rnd.choice(dirs)
                r, c = rnd.randrange(size), rnd.randrange(size)
                er, ec = r + dr * (len(w) - 1), c + dc * (len(w) - 1)
                if not (0 <= er < size and 0 <= ec < size):
                    continue
                cells = [(r + dr * i, c + dc * i) for i in range(len(w))]
                # A word may cross or share a letter with others, but never sit fully inside other words' cells.
                if set(cells) <= set().union(*lines, set()) or any(ln <= set(cells) for ln in lines):
                    continue
                if all(grid[a][b] in ("", w[i]) for i, (a, b) in enumerate(cells)):
                    for i, (a, b) in enumerate(cells):
                        grid[a][b] = w[i]
                    placed.append(w)
                    lines.append(frozenset(cells))
                    break
        if len(placed) < count:
            continue
        # The overall footprint and the lines of the found words must not look like the last few puzzles.
        foot = set().union(*lines)
        if any(len(foot & f) / len(foot | f) > 0.4 or set(lines) & set(ls) for f, ls in recent):
            continue
        recent = (recent + [(foot, lines)])[-3:]
        letters = "etaoinsrhlcdumfpgwybvk"
        flat = "".join(ch or rnd.choice(letters) for row in grid for ch in row)
        if flat in seen:
            continue
        seen.add(flat)
        for w in placed:
            usage[w] = usage.get(w, 0) + 1
        prev = set(placed)
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


HANGMAN_SUBJECTS = {
    "noun.animal": "Animal", "noun.food": "Food", "noun.plant": "Plant",
    "noun.body": "Body part", "noun.phenomenon": "Nature", "noun.object": "Nature",
}
# Odd picks that slipped through the subject lookup.
HANGMAN_BLOCK = set("human molly spam drag anterior posterior total sound murphy cole timothy marc maria costa dale must nova giant cows veronica charger sierra ascent calculus silva ness benny milo fanny brent blanc coco hash elves swine brow".split())


def hangman_pack(n, rnd, lens, min_zipf):
    """Hangman: one common noun each, with its subject (animal, food...) as the only hint.
    Commonness comes from the wordfreq package (Zipf scale); a word's subject is that of its
    first (most common) WordNet sense, which must be a noun. Picked evenly across subjects."""
    from wordfreq import zipf_frequency
    cands = {lem.name() for syn in wn.all_synsets("n") for lem in syn.lemmas()}
    groups = {}
    for w in sorted(cands):
        if not w.isalpha() or not w.islower() or not (lens[0] <= len(w) <= lens[1]) or w in BLOCK or w in HANGMAN_BLOCK:
            continue
        if zipf_frequency(w, "en") < min_zipf:
            continue
        syns = wn.synsets(w)
        first = syns[0]
        # Mostly-noun words only: "come" or "simple" would be odd under a subject.
        if sum(1 for x in syns if x.pos() != "n") > 1:
            continue
        subject = HANGMAN_SUBJECTS.get(first.lexname()) if first.pos() == "n" else None
        if subject:
            groups.setdefault(subject, []).append({"w": w, "c": subject})
    for g in groups.values():
        rnd.shuffle(g)
    out = []
    while len(out) < n and any(groups.values()):
        for g in groups.values():
            if g and len(out) < n:
                out.append(g.pop())
    return out


def crossword_pack(n, rnd):
    """Mini crosswords on several different 5x5 block patterns (TEMPLATES), filled from
    WordNet words with a dictionary-definition clue each."""
    w45, g45 = common_words(4, 5, 12)
    w3, g3 = common_words(3, 3, 30)
    gloss = {**g45, **g3}
    # Every word keeps a list of possible clues (one per common sense).
    clues = {}
    for w in w45 + w3:
        got = [clue_for(w, d, pos) for _, d, pos in gloss[w]]
        clues[w] = [c for c in dict.fromkeys(got) if c]
    clue_use = {}
    by_len = {}
    for w in sorted(w45 + w3):
        if clues[w]:
            by_len.setdefault(len(w), []).append(w)
    by_len_set = {k: set(v) for k, v in by_len.items()}
    templates = [t for t in TEMPLATES if valid_template(t)]
    out, seen, tries = [], set(), 0
    usage = {}  # how often each word is already in the pack: least-used words are tried first

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
        best_cands.sort(key=lambda w: (usage.get(w, 0), rnd.random()))
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
        for w in words_in:
            usage[w] = usage.get(w, 0) + 1
        word = lambda sl: "".join(grid[r][c] for r, c in sl)

        def pick(sl):
            c = min(clues[word(sl)], key=lambda x: (clue_use.get(x, 0), rnd.random()))
            clue_use[c] = clue_use.get(c, 0) + 1
            return c

        out.append({"g": flat, "a": [pick(sl) for sl in across], "d": [pick(sl) for sl in down]})
    return out


def spread_shapes(pack, rnd):
    """Reorder crosswords so two in a row never share a block pattern (also from the last back to the
    first, since the app wraps around). Always takes the shape with the most puzzles left, which keeps
    the shapes evenly mixed."""
    by_shape = {}
    for p in pack:
        by_shape.setdefault("".join("#" if ch == "#" else "." for ch in p["g"]), []).append(p)
    for _ in range(50):
        left = {k: list(v) for k, v in by_shape.items()}
        for v in left.values():
            rnd.shuffle(v)
        out, prev = [], None
        while any(left.values()):
            opts = [k for k, v in left.items() if v and k != prev]
            if not opts:
                break
            top = max(len(left[k]) for k in opts)
            k = rnd.choice([k for k in opts if len(left[k]) == top])
            out.append(left[k].pop())
            prev = k
        shape = lambda p: "".join("#" if ch == "#" else "." for ch in p["g"])
        if len(out) == len(pack) and shape(out[0]) != shape(out[-1]):
            return out
    return pack


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--sudoku", type=int, default=400)
    ap.add_argument("--wordsearch", type=int, default=300)
    ap.add_argument("--crossword", type=int, default=250)
    ap.add_argument("--hangman", type=int, default=300)
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
        # Hangman: easy = common 4-5 letter nouns, standard = 6-8 letters; the hint is the subject.
        ("hangman5", lambda n: hangman_pack(n, rnd, (4, 5), 3.4), args.easy),
        ("hangman", lambda n: hangman_pack(n, rnd, (6, 8), 3.4), args.hangman),
    )
    only = {x for x in args.only.split(",") if x}
    for name, fn, n in packs:
        if only and name not in only:
            continue
        pack = fn(n)
        if not name.startswith(("wordsearch", "hangman")):
            rnd.shuffle(pack)
        if name == "crossword":
            pack = spread_shapes(pack, rnd)
        with open(os.path.join(args.out, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(pack, f, separators=(",", ":"))
        print(name, len(pack))


if __name__ == "__main__":
    main()
