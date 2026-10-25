#!/usr/bin/env python3
"""Lowers Media Queries 4 range syntax in the bundled sing-box dashboard for WebViews before Chromium 104.

The dashboard's minifier turns `(max-width: 720px)` into `(width<=720px)`, which an older Android System WebView never
matches, so the phone layout never applies (#97). Every range in an @media prelude of the .css/.html files becomes
min-/max- features; a strict bound moves by a hair, like Bootstrap's .02px. A form this cannot lower, or a range left
anywhere afterwards (the .js files included), fails the build instead of shipping a page that breaks.

Usage: dashboard_css.py <dashboard dir>
"""

import os
import re
import sys

NUM = r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[a-zA-Z%]+)?"
RATIO = r"\d+\s*/\s*\d+"
VALUE = rf"(?:{RATIO}|{NUM})"
FEATURE = r"[a-zA-Z][a-zA-Z-]*"
OP = r"<=|>=|<|>|="

ONE_SIDED = re.compile(rf"^\s*({FEATURE})\s*({OP})\s*({VALUE})\s*$")
REVERSED = re.compile(rf"^\s*({VALUE})\s*({OP})\s*({FEATURE})\s*$")
TWO_SIDED = re.compile(rf"^\s*({VALUE})\s*(<=|<|>=|>)\s*({FEATURE})\s*(<=|<|>=|>)\s*({VALUE})\s*$")

MEDIA = re.compile(r"@media([^{};]*)\{")
RANGE_CHARS = re.compile(r"[<>=]")
# Level 4 only: `or`, a parenthesised `not (...)`, and a condition nested in a group. A query-level `not <type>` is Level 3.
OR_WORD = re.compile(r"(?<![\w-])or(?![\w-])")
NOT_GROUP = re.compile(r"(?<![\w-])not\s*\(")
NESTED_CONDITION = re.compile(r"^\s*(?:\(|not(?![\w-]))")
# A parenthesised group with up to two levels of parentheses inside, such as a calc() value.
_INNER = r"(?:[^()]|\([^()]*\))*"
_GROUP_BODY = rf"(?:[^()]|\({_INNER}\))*"
# A media range left in CSS text, also inside a JS string, or a range handed to matchMedia().
LEFTOVER = [
    re.compile(rf"@media[^{{}};\"'`]*\({_GROUP_BODY}[<>=]{_GROUP_BODY}\)"),
    re.compile(rf"matchMedia\(\s*[\"'`][^\"'`]*\({_GROUP_BODY}[<>=]{_GROUP_BODY}\)"),
]

# Shifts a strict bound inside the closed range of a min-/max- feature.
EPSILON = {"px": 0.02, "em": 0.001, "rem": 0.001, "dppx": 0.001, "x": 0.001, "dpi": 0.01, "dpcm": 0.01}

FLIP = {"<": ">", "<=": ">=", ">": "<", ">=": "<=", "=": "="}


class Unsupported(Exception):
    pass


def shift(value, op):
    """value for `feature op value` as an inclusive bound."""
    if op in ("<=", ">="):
        return value
    match = re.fullmatch(r"([+-]?(?:\d+\.?\d*|\.\d+))([a-zA-Z%]*)", value.strip())
    if not match or match.group(2).lower() not in EPSILON:
        raise Unsupported(f"strict bound {op} {value}")
    number, unit = float(match.group(1)), match.group(2)
    delta = EPSILON[unit.lower()]
    shifted = number - delta if op == "<" else number + delta
    return f"{shifted:.4f}".rstrip("0").rstrip(".") + unit


def bound(feature, op, value):
    """`(feature op value)` as a Level 3 feature."""
    value = value.strip()
    if op == "=":
        return f"({feature}:{value})"
    prefix = "max" if op in ("<", "<=") else "min"
    return f"({prefix}-{feature}:{shift(value, op)})"


def lower_group(content):
    two = TWO_SIDED.match(content)
    if two:
        low, op1, feature, op2, high = two.groups()
        # `a < f < b` reads as `f > a` and `f < b`; the operators must point the same way.
        if (op1[0] == "<") != (op2[0] == "<"):
            raise Unsupported(f"mixed directions in ({content})")
        return f"{bound(feature, FLIP[op1], low)} and {bound(feature, op2, high)}"
    one = ONE_SIDED.match(content)
    if one:
        feature, op, value = one.groups()
        return bound(feature, op, value)
    rev = REVERSED.match(content)
    if rev:
        value, op, feature = rev.groups()
        return bound(feature, FLIP[op], value)
    raise Unsupported(f"({content})")


def top_groups(text):
    """(start, end) of each balanced top-level (...) in text, at any nesting depth inside."""
    spans, depth, start = [], 0, 0
    for i, ch in enumerate(text):
        if ch == "(":
            if depth == 0:
                start = i
            depth += 1
        elif ch == ")" and depth:
            depth -= 1
            if depth == 0:
                spans.append((start, i + 1))
    return spans


def lower_prelude(prelude):
    parts, last, changed = [], 0, False
    groups = top_groups(prelude)
    for start, end in groups:
        content = prelude[start + 1:end - 1]
        if not RANGE_CHARS.search(content):
            continue
        # A range with a function value, inside a nested condition or under a parenthesised not.
        if "(" in content:
            raise Unsupported(f"range with nested parentheses: @media{prelude}")
        parts += [prelude[last:start], lower_group(content)]
        last = end
        changed = True
    if not changed:
        return prelude
    parts.append(prelude[last:])
    lowered = "".join(parts)
    # Level 3 has no `or`, no `not (...)` and no nested conditions; old WebViews drop such a query whole.
    skeleton, last = [], 0
    for start, end in groups:
        skeleton += [prelude[last:start], "()"]
        last = end
        if NESTED_CONDITION.match(prelude[start + 1:end - 1]):
            raise Unsupported(f"nested condition: @media{prelude}")
    skeleton = "".join(skeleton) + prelude[last:]
    if OR_WORD.search(skeleton) or NOT_GROUP.search(skeleton):
        raise Unsupported(f"range inside not/or: @media{prelude}")
    return lowered


def lower_css(text):
    count = 0

    def replace(match):
        nonlocal count
        prelude = match.group(1)
        lowered = lower_prelude(prelude)
        if lowered != prelude:
            count += 1
        return f"@media{lowered}{{"

    return MEDIA.sub(replace, text), count


def main():
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        sys.exit("usage: dashboard_css.py <dashboard dir>")
    root = sys.argv[1]
    lowered_total = 0
    failures = []
    for folder, _, names in os.walk(root):
        for name in sorted(names):
            path = os.path.join(folder, name)
            ext = os.path.splitext(name)[1].lower()
            if ext not in (".css", ".html", ".htm", ".js", ".mjs"):
                continue
            with open(path, encoding="utf-8", errors="surrogateescape", newline="") as f:
                text = f.read()
            rel = os.path.relpath(path, root)
            if ext in (".css", ".html", ".htm"):
                try:
                    lowered, count = lower_css(text)
                except Unsupported as e:
                    failures.append(f"{rel}: cannot lower {e}")
                    continue
                if count:
                    with open(path, "w", encoding="utf-8", errors="surrogateescape", newline="") as f:
                        f.write(lowered)
                    print(f"{rel}: lowered {count} @media range queries")
                    lowered_total += count
                text = lowered
            for pattern in LEFTOVER:
                for match in pattern.finditer(text):
                    failures.append(f"{rel}: media range left: {match.group(0)[:120]}")
    if failures:
        for failure in failures:
            print(f"::error::{failure}", file=sys.stderr)
        sys.exit(1)
    print(f"dashboard: {lowered_total} @media range queries lowered")


if __name__ == "__main__":
    main()
