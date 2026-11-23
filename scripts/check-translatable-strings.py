#!/usr/bin/env python3
"""Refuse a user-facing string that no translator can ever reach.

    check-translatable-strings.py
    check-translatable-strings.py --self-test

Two shipped surfaces are checked here:

  1. Android Kotlin sources under `android/app/src/main/kotlin`. Android ships
     one mechanism for translating what a user reads: a string resource, which
     the platform resolves per locale. A literal written into Kotlin stays
     English in every locale. Lint's `HardcodedText` covers XML layouts; this
     script covers Kotlin call and property sinks.
  2. Bundled first-party VS Code extensions under
     `android/app/src/main/assets/extensions/vscodroid.*`. Their user-facing
     runtime strings must go through `vscode.l10n.t(...)` backed by all 13
     non-English `l10n/bundle.l10n.<lang>.json` bundles with exact key and
     placeholder parity.

==============================================================================
WHAT THIS CANNOT SEE. Read this before trusting a pass.

The sink check is a predicate over CALL SHAPES: it finds a literal only where
the literal is written at the sink. Every way of putting a string somewhere
else first is invisible to it, and those ways are ordinary rather than exotic:

  * A string assigned to a variable, or returned by a helper, and displayed
    later. `val msg = "Storage low"; Toast.makeText(this, msg, ...)` passes.
  * A string held as data. `ToolchainRegistry.ToolchainInfo.description` is a
    constructor argument, not a sink, and the adapter that draws it passes a
    property rather than a literal. Both ends pass.
  * A string built by concatenation where no single part carries a letter.
  * A Kotlin raw string, the kind opened by three double quotes, at a sink.
    The tokeniser blanks raw strings rather than reading them as literals.
  * Server JavaScript and the web client outside `vscodroid.*` extensions.
  * A sink not in SINKS / JS_CALL_SINKS / JS_PROPERTY_SINKS / JS_OBJECT_SINKS.

So read a pass as "no literal was written at a recognised sink, and every
bundled extension l10n key matches all 13 locale bundles", never as "every
string in the whole app is guaranteed translatable".
==============================================================================

Exit 0 when clean, 1 on a finding, 2 when the tree or arguments are invalid.
"""

import json
import re
import sys
import tempfile
from pathlib import Path

# ---------------------------------------------------------------------------
# Kotlin sink scanning
# ---------------------------------------------------------------------------

# Methods whose string arguments are read or spoken. Every one of them is called
# in this app today; see the docstring on why the list is not longer.
SINKS = (
    # Toast and AlertDialog.
    "makeText", "showNotice", "setTitle", "setMessage", "setPositiveButton", "setNegativeButton",
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
PROPERTY_RE = re.compile(
    r"(?<![\w])(?:(val|var)\s+)?(" + "|".join(PROPERTY_SINKS) + r")\s*=(?!=)")

# `${ ... }` first, so a `$` inside an expression is not read as a simple one.
INTERPOLATION_RE = re.compile(r"\$\{[^{}]*\}|\$[A-Za-z_][A-Za-z0-9_]*")

SENTINEL = "\x00"


def tokenize(src):
    """Blank what is not Kotlin code, and record where the string literals are.

    Returns (masked, literals). `masked` is the same length as `src` with
    comments and raw strings replaced by spaces and every ordinary string
    literal replaced by a run of SENTINEL, so offsets and line numbers still
    line up and a parenthesis inside a string cannot unbalance the scan.
    `literals` is a list of (start, end, content, nested) for the ordinary
    literals, where `nested` holds the contents of the literals written inside
    that one's interpolations.
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
    """Walk one ordinary Kotlin literal from its opening quote past its closing one."""
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
            return i, "".join(body), nested
        else:
            body.append(c)
            i += 1
    return n, "".join(body), nested


def is_prose(content, nested=()):
    """True when what is left after the interpolations is language, or when a
    literal written inside one of them is.
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
    """The right-hand side, to the end of the statement rather than the line."""
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
    return sorted(set(findings))


# ---------------------------------------------------------------------------
# Bundled extension JS sink scanning & l10n bundle verification
# ---------------------------------------------------------------------------

EXPECTED_LOCALES = (
    "cs", "de", "es", "fr", "it", "ja", "ko", "pl", "pt-br", "ru", "tr", "zh-cn", "zh-tw",
)

BANNED_DASHES = {
    chr(0x2013): "U+2013",
    chr(0x2014): "U+2014",
    chr(0x2015): "U+2015",
}

JS_CALL_SINKS = (
    "showInformationMessage",
    "showWarningMessage",
    "showErrorMessage",
    "showQuickPick",
    "showInputBox",
    "withProgress",
    "createOutputChannel",
    "appendLine",
)

# Only the first argument is user-facing for these two: withProgress takes a
# task callback as its second argument, and createOutputChannel takes an
# optional language id as its second argument.
JS_FIRST_ARG_ONLY_SINKS = frozenset(("withProgress", "createOutputChannel"))

# Technical object fields inside option bags (showInputBox/showQuickPick/etc.)
# whose string literals are defaults or identifiers rather than UI prose.
JS_NON_UI_OBJECT_KEYS = frozenset(("value", "command", "language", "id", "scheme"))

JS_PROPERTY_SINKS = ("tooltip", "name", "text")
JS_OBJECT_SINKS = ("placeHolder", "prompt", "detail", "title")

JS_CALL_SINK_RE = re.compile(r"\.\s*(" + "|".join(JS_CALL_SINKS) + r")\s*\(")
JS_PROPERTY_SINK_RE = re.compile(
    r"(?:([A-Za-z_$][A-Za-z0-9_$]*)\s*)?\.\s*(" + "|".join(JS_PROPERTY_SINKS) + r")\s*=(?!=)"
)
JS_OBJECT_SINK_RE = re.compile(r"(?<![\w.$])(" + "|".join(JS_OBJECT_SINKS) + r")\s*:")
JS_NON_UI_KEY_RE = re.compile(r"(?<![\w.$])(" + "|".join(JS_NON_UI_OBJECT_KEYS) + r")\s*:")
JS_L10N_CALL_RE = re.compile(r"\bl10n\s*\.\s*t\s*\(")
JS_CONST_OBJECT_RE = re.compile(r"\b(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*\{")

CODICON_RE = re.compile(r"\$\([a-z0-9-]+\)")
PLACEHOLDER_RE = re.compile(r"\{\d+\}")
JS_REGEX_PREV_KEYWORDS = frozenset((
    "return", "case", "throw", "typeof", "void", "delete", "in",
    "instanceof", "new", "of", "await", "yield", "else", "do",
))
JS_REGEX_PREV_PUNCT = frozenset("([{,;:=!&|^~+-*%<>?")


def unescape_js_string(raw):
    """Decode standard JS escape sequences inside a literal's body."""
    out = []
    i, n = 0, len(raw)
    simple = {
        "n": "\n", "r": "\r", "t": "\t", "b": "\b", "f": "\f", "v": "\v",
        "0": "\0", "'": "'", '"': '"', "`": "`", "\\": "\\", "$": "$",
    }
    while i < n:
        c = raw[i]
        if c != "\\" or i + 1 >= n:
            out.append(c)
            i += 1
            continue
        nxt = raw[i + 1]
        if nxt in simple:
            out.append(simple[nxt])
            i += 2
        elif nxt == "\n":
            i += 2
        elif nxt == "x" and i + 3 < n:
            try:
                out.append(chr(int(raw[i + 2:i + 4], 16)))
                i += 4
            except ValueError:
                out.append(nxt)
                i += 2
        elif nxt == "u":
            if i + 2 < n and raw[i + 2] == "{":
                close = raw.find("}", i + 3)
                if close != -1:
                    try:
                        out.append(chr(int(raw[i + 3:close], 16)))
                        i = close + 1
                        continue
                    except ValueError:
                        pass
            elif i + 5 < n:
                try:
                    out.append(chr(int(raw[i + 2:i + 6], 16)))
                    i += 6
                    continue
                except ValueError:
                    pass
            out.append(nxt)
            i += 2
        else:
            out.append(nxt)
            i += 2
    return "".join(out)


def scan_js_quoted(src, start, quote):
    """Walk one JS single- or double-quoted literal."""
    i, n = start + 1, len(src)
    body = []
    while i < n:
        c = src[i]
        if c == "\\" and i + 1 < n:
            body.append(src[i:i + 2])
            i += 2
        elif c == quote:
            return i + 1, "".join(body)
        elif c == "\n":
            return i, "".join(body)
        else:
            body.append(c)
            i += 1
    return n, "".join(body)


def scan_js_template(src, start, out, literals):
    """Walk a JS template literal, preserving expressions inside `${...}` in `out`.

    Static chunks of the template are replaced with SENTINEL in `out` and
    recorded as a single template entry in `literals` (with static text only),
    while expressions inside `${...}` remain visible in `out` so nested calls
    such as `vscode.l10n.t(...)` and raw literals inside ternaries are both
    tokenized and checked accurately.
    """
    i, n = start + 1, len(src)
    static_parts = []
    has_expr = False
    if src[start] != "\n":
        out[start] = SENTINEL
    while i < n:
        c = src[i]
        if c == "\\" and i + 1 < n:
            static_parts.append(src[i:i + 2])
            for k in (i, i + 1):
                if src[k] != "\n":
                    out[k] = SENTINEL
            i += 2
        elif c == "$" and i + 1 < n and src[i + 1] == "{":
            has_expr = True
            # Keep `${` and matching `}` visible in `out` as code delimiters
            # (spaces for `$`, `{` and `}` kept) so nested expressions parse.
            out[i] = " "
            out[i + 1] = "{"
            i = tokenize_js_range(src, i + 2, n, out, literals, stop_on_brace=True)
            if i < n and src[i] == "}":
                out[i] = "}"
                i += 1
        elif c == "`":
            if src[i] != "\n":
                out[i] = SENTINEL
            end = i + 1
            static_text = unescape_js_string("".join(static_parts))
            literals.append((start, end, static_text, "`", has_expr))
            return end
        else:
            static_parts.append(c)
            if c != "\n":
                out[i] = SENTINEL
            i += 1
    static_text = unescape_js_string("".join(static_parts))
    literals.append((start, n, static_text, "`", has_expr))
    return n


def tokenize_js_range(src, start, end, out, literals, stop_on_brace=False):
    """Tokenize JS code from `start` up to `end` (or matching `}` if nested)."""
    i = start
    brace_depth = 0
    prev_kind = "punct"
    prev_text = "("

    def blank(a, b):
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    def can_start_regex():
        if prev_kind == "keyword" and prev_text in JS_REGEX_PREV_KEYWORDS:
            return True
        if prev_kind == "punct" and (prev_text in JS_REGEX_PREV_PUNCT or prev_text == "=>"):
            return True
        return False

    while i < end:
        c = src[i]
        if c.isspace():
            i += 1
            continue
        if stop_on_brace and c == "}" and brace_depth == 0:
            return i
        if c == "/" and i + 1 < end and src[i + 1] == "/":
            j = src.find("\n", i, end)
            j = end if j < 0 else j
            blank(i, j)
            i = j
            continue
        if c == "/" and i + 1 < end and src[i + 1] == "*":
            j = src.find("*/", i + 2, end)
            j = end if j < 0 else j + 2
            blank(i, j)
            i = j
            continue
        if c == "/" and can_start_regex():
            # Regex literal: respect escapes and [...] character classes.
            j = i + 1
            in_class = False
            while j < end:
                rc = src[j]
                if rc == "\\" and j + 1 < end:
                    j += 2
                    continue
                if rc == "[":
                    in_class = True
                elif rc == "]":
                    in_class = False
                elif rc == "/" and not in_class:
                    j += 1
                    while j < end and src[j].isalpha():
                        j += 1
                    break
                elif rc == "\n":
                    break
                j += 1
            blank(i, j)
            prev_kind, prev_text = "literal", "/"
            i = j
            continue
        if c in ("'", '"'):
            j, raw = scan_js_quoted(src, i, c)
            decoded = unescape_js_string(raw)
            literals.append((i, j, decoded, c, False))
            for k in range(i, j):
                out[k] = SENTINEL if src[k] != "\n" else "\n"
            prev_kind, prev_text = "literal", c
            i = j
            continue
        if c == "`":
            j = scan_js_template(src, i, out, literals)
            prev_kind, prev_text = "literal", "`"
            i = j
            continue
        if c == "{":
            brace_depth += 1
            prev_kind, prev_text = "punct", "{"
            i += 1
            continue
        if c == "}":
            brace_depth = max(0, brace_depth - 1)
            prev_kind, prev_text = "punct", "}"
            i += 1
            continue
        if c.isalpha() or c in ("_", "$"):
            j = i + 1
            while j < end and (src[j].isalnum() or src[j] in ("_", "$")):
                j += 1
            word = src[i:j]
            prev_kind = "keyword" if word in JS_REGEX_PREV_KEYWORDS else "ident"
            prev_text = word
            i = j
            continue
        if c.isdigit():
            j = i + 1
            while j < end and (src[j].isalnum() or src[j] in (".", "_")):
                j += 1
            prev_kind, prev_text = "number", src[i:j]
            i = j
            continue
        if c == "=" and i + 1 < end and src[i + 1] == ">":
            prev_kind, prev_text = "punct", "=>"
            i += 2
            continue
        if c in ("+", "-") and i + 1 < end and src[i + 1] == c:
            prev_kind, prev_text = "incdec", c + c
            i += 2
            continue
        prev_kind, prev_text = "punct", c
        i += 1
    return i


def tokenize_js(src):
    """Tokenize a JS source file into `(masked, literals)`.

    Each literal tuple is `(start, end, decoded_text, quote, has_expr)`.
    """
    out = list(src)
    literals = []
    tokenize_js_range(src, 0, len(src), out, literals, stop_on_brace=False)
    literals.sort(key=lambda item: item[0])
    return "".join(out), literals


def is_js_prose(text):
    """True when static JS text carries user-facing prose after stripping codicons."""
    stripped = CODICON_RE.sub("", text)
    return bool(re.search(r"[A-Za-z]", stripped))


def first_arg_window(masked, open_paren):
    """Span of the first argument inside `(...)`, stopping at a depth-1 comma or `)`."""
    depth, i, n = 0, open_paren, len(masked)
    while i < n:
        c = masked[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return open_paren + 1, i
        elif c == "," and depth == 1:
            return open_paren + 1, i
        i += 1
    return open_paren + 1, n


def js_assignment_window(masked, equals):
    """Right-hand side of a JS property assignment, ending at `;` or statement end."""
    depth, i, n = 0, equals + 1, len(masked)
    while i < n:
        c = masked[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return equals + 1, i
            depth -= 1
        elif c == ";" and depth == 0:
            return equals + 1, i
        elif c == "\n" and depth == 0:
            if not masked[equals:i].rstrip().endswith(("+", ",", "=", "?", ":", "&&", "||")):
                return equals + 1, i
        i += 1
    return equals + 1, n


def js_object_value_window(masked, colon):
    """Value expression after `key:` in a JS object literal."""
    depth, i, n = 0, colon + 1, len(masked)
    while i < n:
        c = masked[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return colon + 1, i
            depth -= 1
        elif c in (",", ";") and depth == 0:
            return colon + 1, i
        i += 1
    return colon + 1, n


def brace_window(masked, open_brace):
    """Span from `{` to its matching `}`."""
    depth, i, n = 0, open_brace, len(masked)
    while i < n:
        if masked[i] == "{":
            depth += 1
        elif masked[i] == "}":
            depth -= 1
            if depth == 0:
                return open_brace, i
        i += 1
    return open_brace, n


def scan_js(path, src):
    """Return unlocalized prose findings in a bundled extension JS file."""
    masked, literals = tokenize_js(src)

    # Spans exempt from raw-prose errors:
    # 1. The first argument of every `l10n.t(...)` call (the localized key itself).
    # 2. Non-UI option keys (e.g. `value: 'http://localhost:'`, `command: 'vscodroid.x'`).
    exempt_spans = []
    for m in JS_L10N_CALL_RE.finditer(masked):
        exempt_spans.append(first_arg_window(masked, m.end() - 1))
    for m in JS_NON_UI_KEY_RE.finditer(masked):
        exempt_spans.append(js_object_value_window(masked, m.end() - 1))

    def is_exempt(start, end):
        return any(lo <= start and end <= hi for lo, hi in exempt_spans)

    windows = []
    for m in JS_CALL_SINK_RE.finditer(masked):
        sink = m.group(1)
        if sink in JS_FIRST_ARG_ONLY_SINKS:
            lo, hi = first_arg_window(masked, m.end() - 1)
        else:
            lo, hi = call_window(masked, m.end() - 1)
        windows.append((sink, lo, hi))
    for m in JS_PROPERTY_SINK_RE.finditer(masked):
        recv, prop = (m.group(1) or "").lower(), m.group(2)
        # JS Error.prototype.name is an internal error-class discriminator (e.g.
        # `error.name = 'BridgeTimeoutError'`), not a UI label.
        if prop == "name" and recv in ("err", "error"):
            continue
        windows.append((f".{prop} =", *js_assignment_window(masked, m.end() - 1)))
    for m in JS_OBJECT_SINK_RE.finditer(masked):
        windows.append((f"{m.group(1)}:", *js_object_value_window(masked, m.end() - 1)))

    findings = []
    for sink, lo, hi in windows:
        for start, end, decoded, quote, has_expr in literals:
            if not (lo <= start and end <= hi):
                continue
            if is_exempt(start, end):
                continue
            if is_js_prose(decoded):
                findings.append((path, src.count("\n", 0, start) + 1, sink, decoded))
    return sorted(set(findings))


def extract_const_string_tables(masked, literals):
    """Map top-level `const TABLE = { k: 'val', ... }` names to their string values."""
    tables = {}
    for m in JS_CONST_OBJECT_RE.finditer(masked):
        name = m.group(1)
        lo, hi = brace_window(masked, m.end() - 1)
        body = masked[lo + 1:hi]
        # Walk top-level key: value entries inside the object literal.
        values = []
        ok = True
        for km in re.finditer(r"(?<![\w.$])([A-Za-z0-9_$]+)\s*:", body):
            val_lo, val_hi = js_object_value_window(masked, lo + 1 + km.end() - 1)
            entry_lits = [lit for lit in literals if val_lo <= lit[0] and lit[1] <= val_hi]
            if len(entry_lits) == 1 and not entry_lits[0][4]:
                # Verify the value expression contains only the single literal.
                expr = masked[val_lo:val_hi].replace(SENTINEL, "").strip()
                if expr == "":
                    values.append(entry_lits[0][2])
                    continue
            ok = False
            break
        if ok and values:
            tables[name] = sorted(set(values))
    return tables


def extract_l10n_keys(path, src):
    """Extract all `vscode.l10n.t(...)` keys from a JS file.

    Returns `(keys_set, errors_list)`. Resolves:
      * Single string literals and `+`-concatenated string literals.
      * Lookup expressions referencing a `const TABLE = { k: 'literal', ... }`
        object (e.g. `vscode.l10n.t(TYPE_LABELS[type] || TYPE_LABELS.unknown)`).
    Reports an error for any `l10n.t(...)` call whose first argument cannot be
    resolved statically.
    """
    masked, literals = tokenize_js(src)
    tables = extract_const_string_tables(masked, literals)
    keys = set()
    errors = []

    for m in JS_L10N_CALL_RE.finditer(masked):
        lo, hi = first_arg_window(masked, m.end() - 1)
        line = src.count("\n", 0, m.start()) + 1
        arg_masked = masked[lo:hi]
        arg_lits = [lit for lit in literals if lo <= lit[0] and lit[1] <= hi]

        # Case 1: one or more string literals joined solely by `+`.
        if arg_lits and all(not lit[4] for lit in arg_lits):
            remainder = arg_masked
            for start, end, _, _, _ in sorted(arg_lits, key=lambda x: x[0], reverse=True):
                remainder = remainder[:start - lo] + " " + remainder[end - lo:]
            tokens = remainder.split()
            if all(tok == "+" for tok in tokens) and len(tokens) == len(arg_lits) - 1:
                keys.add("".join(lit[2] for lit in arg_lits))
                continue

        # Case 2: lookup into a static string table (e.g. `TYPE_LABELS[x] || TYPE_LABELS.unknown`).
        table_refs = sorted({
            name for name in re.findall(r"\b([A-Za-z_$][A-Za-z0-9_$]*)\b", arg_masked)
            if name in tables
        })
        if table_refs and not arg_lits:
            for name in table_refs:
                keys.update(tables[name])
            continue

        snippet = src[lo:hi].strip().replace("\n", " ")
        if len(snippet) > 60:
            snippet = snippet[:57] + "..."
        errors.append(
            f"{path}:{line}: vscode.l10n.t() key cannot be statically resolved: {snippet}"
        )

    return keys, errors


def check_bundled_extensions(root):
    """Scan bundled `vscodroid.*` extensions for raw UI prose and l10n bundle parity.

    Returns `(js_files_count, js_literals_count, l10n_keys_count, sink_findings, bundle_errors)`.
    """
    assets_dir = root / "android/app/src/main/assets"
    ext_root = assets_dir / "extensions"
    if not assets_dir.is_dir():
        # Minimal Kotlin-only test fixture (no assets directory at all).
        return 0, 0, 0, [], []
    if not ext_root.is_dir():
        return 0, 0, 0, [], [f"error: {ext_root} is not a directory"]

    ext_dirs = sorted(p for p in ext_root.glob("vscodroid.*") if p.is_dir())
    if not ext_dirs:
        return 0, 0, 0, [], [f"error: no vscodroid.* bundled extensions under {ext_root}"]

    js_files = 0
    js_literals = 0
    total_l10n_keys = 0
    sink_findings = []
    bundle_errors = []

    for ext_dir in ext_dirs:
        rel_dir = ext_dir.relative_to(root).as_posix()
        manifest_path = ext_dir / "package.json"
        if not manifest_path.is_file():
            bundle_errors.append(f"{rel_dir}: missing package.json")
            continue
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except Exception as exc:
            bundle_errors.append(f"{rel_dir}/package.json: invalid JSON ({exc})")
            continue

        js_path = ext_dir / "extension.js"
        used_keys = set()
        if js_path.is_file():
            js_files += 1
            src = js_path.read_text(encoding="utf-8")
            _, lits = tokenize_js(src)
            js_literals += len(lits)
            rel_js = js_path.relative_to(root)
            sink_findings.extend(scan_js(rel_js, src))
            used_keys, key_errors = extract_l10n_keys(rel_js.as_posix(), src)
            bundle_errors.extend(key_errors)

        l10n_field = manifest.get("l10n")
        if used_keys and not l10n_field:
            bundle_errors.append(
                f"{rel_dir}/package.json: uses vscode.l10n.t() ({len(used_keys)} keys) "
                'but does not declare "l10n": "./l10n"'
            )
            continue
        if not l10n_field:
            continue

        total_l10n_keys += len(used_keys)
        if not used_keys:
            bundle_errors.append(
                f"{rel_dir}: declares l10n ({l10n_field!r}) but uses 0 vscode.l10n.t() keys"
            )
            continue

        l10n_dir = ext_dir / l10n_field
        if not l10n_dir.is_dir():
            bundle_errors.append(f"{rel_dir}: declared l10n directory {l10n_field!r} does not exist")
            continue

        expected_files = {f"bundle.l10n.{loc}.json" for loc in EXPECTED_LOCALES}
        actual_files = {p.name for p in l10n_dir.iterdir() if p.is_file()}
        for missing_file in sorted(expected_files - actual_files):
            bundle_errors.append(f"{rel_dir}/{l10n_field}/{missing_file}: missing locale bundle")
        for extra_file in sorted(actual_files - expected_files):
            bundle_errors.append(f"{rel_dir}/{l10n_field}/{extra_file}: unexpected file in l10n/")

        # Also cross-check against package.nls.<lang>.json locales when present.
        nls_locales = {
            p.name[len("package.nls."):-len(".json")]
            for p in ext_dir.glob("package.nls.*.json")
            if p.name != "package.nls.json"
        }
        if nls_locales and nls_locales != set(EXPECTED_LOCALES):
            bundle_errors.append(
                f"{rel_dir}: package.nls locales {sorted(nls_locales)} differ from expected "
                f"l10n locales {list(EXPECTED_LOCALES)}"
            )

        for loc in EXPECTED_LOCALES:
            bundle_path = l10n_dir / f"bundle.l10n.{loc}.json"
            if not bundle_path.is_file():
                continue
            rel_bundle = bundle_path.relative_to(root).as_posix()
            try:
                bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
            except Exception as exc:
                bundle_errors.append(f"{rel_bundle}: invalid JSON ({exc})")
                continue
            if not isinstance(bundle, dict):
                bundle_errors.append(f"{rel_bundle}: root must be a JSON object")
                continue

            bundle_keys = set(bundle.keys())
            for missing_key in sorted(used_keys - bundle_keys):
                shown = missing_key if len(missing_key) <= 60 else missing_key[:57] + "..."
                bundle_errors.append(f"{rel_bundle}: missing key {shown!r}")
            for stale_key in sorted(bundle_keys - used_keys):
                shown = stale_key if len(stale_key) <= 60 else stale_key[:57] + "..."
                bundle_errors.append(f"{rel_bundle}: stale extra key {shown!r}")

            for key in sorted(used_keys & bundle_keys):
                val = bundle[key]
                if not isinstance(val, str) or not val.strip():
                    bundle_errors.append(f"{rel_bundle}: empty or non-string value for {key!r}")
                    continue
                expected_ph = sorted(PLACEHOLDER_RE.findall(key))
                actual_ph = sorted(PLACEHOLDER_RE.findall(val))
                if expected_ph != actual_ph:
                    bundle_errors.append(
                        f"{rel_bundle}: placeholder mismatch for {key!r}: "
                        f"expected {expected_ph}, got {actual_ph}"
                    )
                for dash, code in BANNED_DASHES.items():
                    if dash in val:
                        bundle_errors.append(
                            f"{rel_bundle}: contains banned dash {code} in value for {key!r}"
                        )

    return js_files, js_literals, total_l10n_keys, sink_findings, bundle_errors


# ---------------------------------------------------------------------------
# Self-test
# ---------------------------------------------------------------------------

def self_test():
    """Verify the JS tokenizer, sink scanner, and l10n bundle checks against fixtures."""
    # 1. Regex literals carrying quotes must not break string tokenization.
    regex_src = (
        "function f(p) {\n"
        "  const q = `'${p.replace(/'/g, `'\\\\''`)}'`;\n"
        "  vscode.window.showErrorMessage('Raw error after regex');\n"
        "}\n"
    )
    hits = scan_js(Path("ext.js"), regex_src)
    if len(hits) != 1 or hits[0][3] != "Raw error after regex":
        print(f"FAIL self-test: regex literal broke scanner: {hits}", file=sys.stderr)
        return 1

    # 2. Localized strings, codicon-only templates, and non-UI value fields pass;
    #    raw literals at every recognised JS sink are refused.
    clean_js = (
        "const TYPE_LABELS = { a: 'system', b: 'other' };\n"
        "function run() {\n"
        "  vscode.window.showInformationMessage(vscode.l10n.t('Hello {0}', name));\n"
        "  vscode.window.showInputBox({ title: vscode.l10n.t('Open'), value: 'http://localhost:', placeHolder: '3000' });\n"
        "  vscode.window.withProgress({ title: vscode.l10n.t('Working') }, () => fetch('https://example.invalid'));\n"
        "  item.text = `$(copy) ${vscode.l10n.t('Copy')}`;\n"
        "  item.text = '$(pulse) --';\n"
        "  item.name = vscode.l10n.t('Monitor');\n"
        "  item.tooltip = vscode.l10n.t('Ready');\n"
        "  error.name = 'BridgeTimeoutError';\n"
        "  ch.appendLine(`${pid}  ${cmd || ''}`);\n"
        "  return vscode.l10n.t(TYPE_LABELS[x] || TYPE_LABELS.b);\n"
        "}\n"
    )
    clean_hits = scan_js(Path("clean.js"), clean_js)
    if clean_hits:
        print(f"FAIL self-test: clean JS produced false positives: {clean_hits}", file=sys.stderr)
        return 1
    keys, key_errs = extract_l10n_keys("clean.js", clean_js)
    expected_keys = {"Hello {0}", "Open", "Working", "Copy", "Monitor", "Ready", "system", "other"}
    if key_errs or keys != expected_keys:
        print(f"FAIL self-test: extracted keys mismatch: {keys} errors={key_errs}", file=sys.stderr)
        return 1

    dirty_cases = [
        ("showWarningMessage", "vscode.window.showWarningMessage('Watch out');"),
        ("showErrorMessage", "vscode.window.showErrorMessage(`Failed: ${err}`);"),
        ("showInformationMessage", "vscode.window.showInformationMessage(vscode.l10n.t('Ok'), 'Raw Button');"),
        ("showQuickPick", "vscode.window.showQuickPick(items, { placeHolder: 'Pick one' });"),
        ("showInputBox", "vscode.window.showInputBox({ prompt: 'Enter URL' });"),
        ("withProgress", "vscode.window.withProgress({ title: 'Loading...' }, () => {});"),
        ("createOutputChannel", "vscode.window.createOutputChannel('My Channel');"),
        ("appendLine", "channel.appendLine('Raw output line');"),
        (".tooltip =", "statusBarItem.tooltip =\n    'Loading status';"),
        (".name =", "statusBarItem.name = 'Status Item';"),
        (".text =", "statusBarItem.text = '$(copy) Raw Label';"),
        ("detail:", "items.push({ detail: 'Raw detail text' });"),
    ]
    for label, snippet in dirty_cases:
        found = scan_js(Path("dirty.js"), snippet)
        if not found:
            print(f"FAIL self-test: missed raw JS sink {label}: {snippet}", file=sys.stderr)
            return 1

    # 3. End-to-end l10n bundle verification (missing key, stale key, placeholder mismatch, banned dash).
    with tempfile.TemporaryDirectory() as tmp:
        troot = Path(tmp)
        ext = troot / "android/app/src/main/assets/extensions/vscodroid.demo-1.0.0"
        l10n = ext / "l10n"
        l10n.mkdir(parents=True)
        (ext / "package.json").write_text(json.dumps({
            "name": "demo", "version": "1.0.0", "main": "./extension.js", "l10n": "./l10n",
        }), encoding="utf-8")
        (ext / "extension.js").write_text(
            "const msg = vscode.l10n.t('Count: {0}', n);\n", encoding="utf-8"
        )
        for loc in EXPECTED_LOCALES:
            (l10n / f"bundle.l10n.{loc}.json").write_text(
                json.dumps({"Count: {0}": f"[{loc}] {{0}}"}), encoding="utf-8"
            )
        _, _, key_count, sinks, errs = check_bundled_extensions(troot)
        if key_count != 1 or sinks or errs:
            print(f"FAIL self-test: valid fixture failed: sinks={sinks} errs={errs}", file=sys.stderr)
            return 1

        # Break German bundle: placeholder mismatch + stale key.
        (l10n / "bundle.l10n.de.json").write_text(
            json.dumps({"Count: {0}": "Anzahl ohne Platzhalter", "Stale": "Alt"}), encoding="utf-8"
        )
        _, _, _, _, bad_errs = check_bundled_extensions(troot)
        if not any("placeholder mismatch" in e for e in bad_errs) or not any("stale extra key" in e for e in bad_errs):
            print(f"FAIL self-test: broken bundle not caught: {bad_errs}", file=sys.stderr)
            return 1

        # Break French bundle: banned em dash.
        (l10n / "bundle.l10n.de.json").write_text(
            json.dumps({"Count: {0}": "Anzahl: {0}"}), encoding="utf-8"
        )
        (l10n / "bundle.l10n.fr.json").write_text(
            json.dumps({"Count: {0}": "Total " + chr(0x2014) + " {0}"}), encoding="utf-8"
        )
        _, _, _, _, dash_errs = check_bundled_extensions(troot)
        if not any("U+2014" in e for e in dash_errs):
            print(f"FAIL self-test: banned dash in l10n bundle not caught: {dash_errs}", file=sys.stderr)
            return 1

    print(f"ok: self-test passed ({len(dirty_cases)} JS sink shapes, regex lexer, and 13-locale l10n checks)")
    return 0


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

    js_files, js_literals, l10n_keys, js_findings, bundle_errors = check_bundled_extensions(root)

    failed = False
    if findings:
        failed = True
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

    if js_findings:
        failed = True
        print(f"FAIL: {len(js_findings)} unlocalized user-facing string(s) in bundled "
              f"extension JavaScript.\n", file=sys.stderr)
        for path, line, sink, content in js_findings:
            flat = content.replace("\n", "\\n")
            shown = flat if len(flat) <= 70 else flat[:67] + "..."
            print(f"  {path}:{line}  {sink}  \"{shown}\"", file=sys.stderr)
        print("\nWrap each user-facing string in vscode.l10n.t(...) and add translations "
              "to all 13 l10n/bundle.l10n.<lang>.json files.", file=sys.stderr)

    if bundle_errors:
        failed = True
        print(f"FAIL: {len(bundle_errors)} bundled extension l10n bundle problem(s).\n",
              file=sys.stderr)
        for err in bundle_errors:
            print(f"  {err}", file=sys.stderr)

    if failed:
        return 1

    # Control checks: 0 literals means the tokeniser stopped reading.
    if not literals_seen:
        print(f"error: {len(files)} Kotlin sources and 0 string literals; the tokeniser is not "
              "reading them, so a pass here would mean nothing", file=sys.stderr)
        return 2
    if js_files and not js_literals:
        print(f"error: {js_files} bundled extension JS files and 0 string literals; "
              "the JS tokeniser is not reading them", file=sys.stderr)
        return 2

    summary = (
        f"ok: {len(files)} Kotlin sources, {literals_seen} string literals, "
        "no prose literal at a recognised sink"
    )
    if js_files:
        summary += (
            f"; {js_files} bundled extension JS files, {js_literals} JS literals, "
            f"{l10n_keys} l10n keys verified across {len(EXPECTED_LOCALES)} locales"
        )
    print(summary)
    return 0


if __name__ == "__main__":
    if sys.argv[1:] not in ([], ["--self-test"]):
        print("usage: check-translatable-strings.py [--self-test]", file=sys.stderr)
        sys.exit(2)
    sys.exit(self_test() if sys.argv[1:] else main())
