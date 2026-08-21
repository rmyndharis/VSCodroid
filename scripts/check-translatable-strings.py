#!/usr/bin/env python3
"""Refuse a user-facing string that no translator can ever reach.

    check-translatable-strings.py

Android ships exactly one mechanism for translating what a user reads: a string
resource, which the platform resolves per locale. A literal written into Kotlin
is resolved at compile time and is the same in every locale for ever. Adding a
`values-xx/strings.xml` does not reach it, and nothing reports that it did not,
so the app half-translates and the half that stayed English looks like an
oversight in the translation rather than a defect in the code.

Nothing caught that. Lint's `HardcodedText` is the obvious candidate and it
reads layouts only: it is a detector over XML attributes, so a `Toast` raised
from Kotlin is outside its scope by construction. Measured on this tree before
this file existed: `HardcodedText` had no entry in `lint-baseline.xml` and the
build was green while sixty-three user-facing strings sat in Kotlin.

THIS SCRIPT IS HALF THE GATE. Lint's `HardcodedText` is the other half and is
the authority on layouts, which this file does not open. Neither half sees what
the other does, and a green from both is not "the app is translatable"; it is
"no literal reached a sink either of us recognises".

==============================================================================
WHAT THIS CANNOT SEE. Read this before trusting a pass.

The check is a predicate over CALL SHAPES: it finds a literal only where the
literal is written at the sink. Every way of putting a string somewhere else
first is invisible to it, and those ways are ordinary rather than exotic:

  * A string assigned to a variable, or returned by a helper, and displayed
    later. `val msg = "Storage low"; Toast.makeText(this, msg, ...)` passes.
    This is the biggest hole and it cannot be closed by pattern matching, only
    by data-flow analysis this file does not attempt.
  * A string held as data. `ToolchainRegistry.ToolchainInfo.description` is a
    constructor argument, not a sink, and the adapter that draws it passes a
    property rather than a literal. Both ends pass.
  * A string built by concatenation where no single part carries a letter.
  * Anything outside `app/src/main/kotlin`: the server's JavaScript, the
    bundled extensions, and the web client all speak to the user too, and all
    of them are somebody else's translation problem.
  * A sink not in SINKS below. The list is what this app calls today. A widget
    method nobody here uses yet is not watched, and adding a UI class means
    asking whether its setters belong here.

So read a pass as "no literal was written at a recognised sink", never as "the
app is translatable". A gate that claimed the second would be believed about the
part it cannot see, which is most of it.
==============================================================================

The prose predicate is deliberately loose in one direction. A literal counts as
prose when, after interpolations are removed, at least one ASCII letter is left,
or when a literal written inside one of those interpolations is prose by that
same rule. The second half is needed because removing an interpolation removes
whatever was written inside it, and a whole sentence fits in there:
`statusText.text = "${if (ok) "Up to date" else "An update is available"}"`
puts two sentences at a sink and leaves nothing outside the braces for the strip
to find. Only the literals inside those braces count, never the expression
holding them, so `"${files.size}"` stays clean: an identifier is not language,
and flagging one would send a translator after code.
That keeps `"$percent%"` and `"\n\n"` out, which are formatting rather than
language, and it keeps a key's label out only because a label is not passed to a
sink at all: `KeyItem.Button("Tab", "Tab", ...)` writes the character the key
types and the glyph drawn on it, and translating either would change what the
keyboard does. Only the `contentDescription` beside them is language, and it is
a resource id. That distinction is the reason this file matches sinks rather
than matching literals.

Tokenising properly rather than grepping, because both shortcuts fail here:

  * A line-oriented grep misses the multi-line call, and that is where these
    hide. Five of MainActivity's toasts are written `Toast.makeText(` with the
    message on the next line, and a grep for `Toast.makeText.*"` finds none of
    them. The window here is the argument list, from the opening parenthesis to
    its match, however many lines that spans.
  * A grep that ignores raw strings reads the JavaScript this app injects as
    Kotlin. `MainActivity` has `var text = '';` inside a `\"\"\"` block, which a
    scan for `text =` picks up as an assignment sink and then hunts a Kotlin
    literal through the rest of the script.

Comments are blanked for the same reason a sibling test blanks them: prose in
this repository names calls, and a comment that says what a sink does would be
read as the sink doing it.

Exit 0 when clean, 1 on a finding, 2 when the tree is not where it expects.
"""

import re
import sys
from pathlib import Path

# Methods whose string arguments are read or spoken. Every one of them is called
# in this app today; see the docstring on why the list is not longer.
SINKS = (
    # Toast and AlertDialog.
    "makeText", "setTitle", "setMessage", "setPositiveButton", "setNegativeButton",
    "setNeutralButton", "setItems", "setSingleChoiceItems", "setMultiChoiceItems",
    # Views.
    "setText", "setHint", "setContentDescription", "setStateDescription",
    # Notifications.
    "setContentTitle", "setContentText", "setSubText", "setTicker",
    # Launcher shortcuts.
    "setShortLabel", "setLongLabel",
    # Accessibility actions, whose label is read out as a menu entry.
    "addAccessibilityAction",
)

# The same sinks reached as properties. Kotlin turns `setText(x)` into `text = x`
# on a View, and this app writes it that way far more often than it calls the
# setter, so a check that watched only the calls would watch the rarer half.
PROPERTY_SINKS = ("text", "hint", "contentDescription", "stateDescription")

SINK_RE = re.compile(r"\b(" + "|".join(SINKS) + r")\s*\(")
# Not preceded by a word character, so `statusText.text` matches on `text` while
# `contentText` and `statusText` do not. A preceding dot is allowed on purpose:
# `row.statusText.text = "..."` is the shape this app actually writes.
#
# `val`/`var` in front of the name is a declaration and not a sink at all:
# `val text = "Some sentence"` names a local whose value reaches no widget, and
# reading it as one made the check fail a line that no translator needs. The
# keyword is matched into a group and the match dropped, rather than excluded by
# a lookbehind, because a lookbehind has to be fixed width here and `val  text`
# would walk straight back in.
PROPERTY_RE = re.compile(
    r"(?<![\w])(?:(val|var)\s+)?(" + "|".join(PROPERTY_SINKS) + r")\s*=(?!=)")

# `${ ... }` first, so a `$` inside an expression is not read as a simple one.
INTERPOLATION_RE = re.compile(r"\$\{[^{}]*\}|\$[A-Za-z_][A-Za-z0-9_]*")

SENTINEL = "\x00"


def tokenize(src):
    """Blank what is not code, and record where the string literals are.

    Returns (masked, literals). `masked` is the same length as `src` with
    comments and raw strings replaced by spaces and every ordinary string
    literal replaced by a run of SENTINEL, so offsets and line numbers still
    line up and a parenthesis inside a string cannot unbalance the scan.
    `literals` is a list of (start, end, content, nested) for the ordinary
    literals, where `nested` holds the contents of the literals written inside
    that one's interpolations.

    Kotlin block comments nest, which is why a depth counter is kept rather
    than a search for the first `*/`.
    """
    out = list(src)
    literals = []
    i, n = 0, len(src)

    def blank(a, b):
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith("/*", j):
                    depth += 1
                    j += 2
                elif src.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            blank(i, j)
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            blank(i, j)
            i = j
        elif c == '"':
            j, content, nested = scan_string(src, i)
            literals.append((i, j, content, nested))
            for k in range(i, j):
                out[k] = SENTINEL if src[k] != "\n" else "\n"
            i = j
        elif c == "'":
            # A char literal. Blanked so that `'"'` cannot open a string.
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
            blank(i, j)
            i = j
        else:
            i += 1
    return "".join(out), literals


def scan_string(src, start):
    """Walk one ordinary literal from its opening quote past its closing one.

    Returns (end, content, nested). Interpolations get a brace-depth walk of
    their own because they may hold string literals: `"${pkg?.name ?: "unknown"}"`
    is one literal, and a scan that stopped at the third quote would call the
    rest of the line code. Those inner literals are kept in `nested` rather than
    discarded: `content` carries the interpolation whole and `is_prose` strips it
    again, so a sentence written inside one reaches nothing unless it is carried
    out separately.
    """
    i, n = start + 1, len(src)
    body, nested = [], []
    while i < n:
        c = src[i]
        if c == "\\":
            body.append(src[i:i + 2])
            i += 2
        elif c == "$" and i + 1 < n and src[i + 1] == "{":
            depth, j = 1, i + 2
            while j < n and depth:
                if src[j] == '"':
                    j, inner, inner_nested = scan_string(src, j)
                    nested.append(inner)
                    nested.extend(inner_nested)
                    continue
                if src[j] == "{":
                    depth += 1
                elif src[j] == "}":
                    depth -= 1
                j += 1
            body.append(src[i:j])
            i = j
        elif c == '"':
            return i + 1, "".join(body), nested
        elif c == "\n":
            # An unterminated literal. Kotlin would not compile, so this is a
            # tokeniser fault rather than a finding; stopping here keeps the
            # rest of the file readable instead of shifting every offset after it.
            return i, "".join(body), nested
        else:
            body.append(c)
            i += 1
    return n, "".join(body), nested


def is_prose(content, nested=()):
    """True when what is left after the interpolations is language, or when a
    literal written inside one of them is.

    Only those inner literals are asked, never the expression holding them, so a
    count, an identifier or a call inside `${ }` is still not language.
    """
    if re.search(r"[A-Za-z]", INTERPOLATION_RE.sub("", content)):
        return True
    return any(is_prose(inner) for inner in nested)


def call_window(masked, open_paren):
    """The argument list, from its opening parenthesis to the matching one."""
    depth, i, n = 0, open_paren, len(masked)
    while i < n:
        if masked[i] == "(":
            depth += 1
        elif masked[i] == ")":
            depth -= 1
            if depth == 0:
                return open_paren, i
        i += 1
    return open_paren, n


def assignment_window(masked, equals):
    """The right-hand side, to the end of the statement rather than the line.

    A statement ends at the first newline where every bracket opened since the
    `=` is closed and the line does not trail an operator. `text = "a" +\\n"b"`
    is one statement and both halves have to be in the window, or a message
    split across lines is judged on its first fragment alone.
    """
    depth, i, n = 0, equals + 1, len(masked)
    while i < n:
        c = masked[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return equals, i
            depth -= 1
        elif c == "\n" and depth == 0:
            if not masked[equals:i].rstrip().endswith(("+", ",", "=")):
                return equals, i
        i += 1
    return equals, n


def scan(path, src):
    masked, literals = tokenize(src)
    windows = []
    for m in SINK_RE.finditer(masked):
        windows.append((m.group(1), *call_window(masked, m.end() - 1)))
    for m in PROPERTY_RE.finditer(masked):
        if m.group(1):
            continue
        windows.append((m.group(2), *assignment_window(masked, m.end() - 1)))

    findings = []
    for name, lo, hi in windows:
        for start, end, content, nested in literals:
            if lo <= start and end <= hi and is_prose(content, nested):
                findings.append((path, src.count("\n", 0, start) + 1, name, content))
    # One sink can sit inside another's window (`setTitle(getString(...))` is
    # not, but a nested builder is), so the same literal can be reported twice.
    return sorted(set(findings))


def main():
    root = Path(__file__).resolve().parent.parent
    sources = root / "android/app/src/main/kotlin"
    if not sources.is_dir():
        print(f"error: {sources} is not a directory; this check cannot run", file=sys.stderr)
        return 2

    files = sorted(sources.rglob("*.kt"))
    if not files:
        print(f"error: no Kotlin sources under {sources}; a pass here would mean nothing",
              file=sys.stderr)
        return 2

    findings, literals_seen = [], 0
    for path in files:
        src = path.read_text(encoding="utf-8")
        literals_seen += len(tokenize(src)[1])
        findings.extend(scan(path.relative_to(root), src))

    if findings:
        print(f"FAIL: {len(findings)} user-facing string(s) written in Kotlin rather than "
              f"in strings.xml.\n", file=sys.stderr)
        for path, line, sink, content in findings:
            shown = content if len(content) <= 70 else content[:67] + "..."
            print(f"  {path}:{line}  {sink}  \"{shown}\"", file=sys.stderr)
        print("\nNo translation can reach these: a Kotlin literal is resolved at compile "
              "time and\nstays English in every locale. Move each one into "
              "android/app/src/main/res/values/strings.xml\nand pass it as "
              "getString(R.string.<name>), with %1$s style arguments for any value\n"
              "interpolated into it, so a translator can reorder them.", file=sys.stderr)
        return 1

    # The literal count is the control. It answers the question a bare "ok"
    # cannot: whether the tokeniser is still reading these files at all. A
    # rename that moved the sources, or an edge case that made scan_string
    # swallow a file, would report zero findings over zero literals and read
    # exactly like a clean tree.
    # The sentence says what the scan establishes, which is narrower than what a
    # reader wants it to say. Two classes pass it while being read by a user: a
    # sink this file does not list, and a literal is_prose judges to be formatting
    # rather than language. `row.statusText.text = "$percent%"` was the second of
    # those; is_prose needs a letter to survive the interpolation strip, and no
    # letter survives that one. Widening is_prose is not the answer either, since
    # it would flag every numeric format string in the app. So the line reports the
    # predicate that was actually evaluated, and the docstring above says what sits
    # outside it.
    print(f"ok: {len(files)} Kotlin sources, {literals_seen} string literals, "
          "no prose literal at a recognised sink")
    return 0


if __name__ == "__main__":
    sys.exit(main())
