#!/usr/bin/env python3
"""Keep the documented bridge signatures in agreement with the registered ones.

    check-bridge-api-spec.py

`docs/05-API_SPEC.md` is the contract an extension author writes against, and
nothing has ever held it to `AndroidBridge.kt`. One pass over both files found
fourteen disagreements across twenty-eight methods -- a measured failure rate,
not a hypothetical one -- and half of them were invisible to a comparison of
method names, because the name matched and only the shape was wrong:

  * `generateSshKey`'s second argument was documented as `keyType`, "ed25519
    (default) or rsa". The algorithm is not selectable and that argument is the
    key's comment. Asking for RSA got you an ed25519 key with "rsa" written into
    its comment field, and nothing reported a problem. A wrong document that
    errors is a nuisance; one that succeeds differently ships a key someone
    believes is something else.
  * `openRecentFolder` was documented as returning `Boolean`, "true if URI is
    still valid and accessible". It returns Unit. The document invited a caller
    to branch on whether a lapsed SAF grant still works; there is no value to
    branch on, and the failure surfaces later, inside the sync.
  * `cancelToolchainInstall(authToken)` -- the documented form does not compile.

So this compares the three axes a caller can get wrong: the set of methods, each
parameter list, and each return type. Both directions, because a method the
document invents costs as much as one it omits.

What it deliberately does NOT check is the prose -- what a method does, what it
returns on refusal, what its JSON keys are. That part is still a person reading
the bodies. Drawing the line here is the point: a check that claims to cover
prose would have to be believed about the half it cannot see.

Anchored on `@JavascriptInterface` in both files rather than on `fun`, which
also matches the ProcessManager sketch in section 6 and the private helpers in
the bridge, neither of which is part of this contract.

The two directions do NOT fail the same way, and assuming they do is how the
one-line-annotation hole survived a review. A method the spec omits is caught by
the count; a method the *spec* declares in a shape this cannot parse is caught as
"registered on the bridge, absent from the spec", loudly. Only the code side ever
had a way to fail open, and only for the shape now covered above.

If you are mutating the sources to check this still fires: COMMIT FIRST. A
mutation harness restores with `git checkout -- <file>`, which is
indistinguishable from working correctly right up to the moment the file holds
something uncommitted -- and then it deletes it, reports success, and the loss is
invisible until a later probe happens to ask. That is not hypothetical: it ate
three fixes mid-review here, and only a probe returning `0 occurrences` for a
line just written revealed it. Committing first bounds the worst case to
restoring a state you chose.

Known limits, left unfixed on purpose. Both are wrong in the accusing direction
rather than the missing one, and neither shape exists in the tree; widening the
pattern for them would add surface for a hypothetical benefit:

  * An expression-bodied method --  `fun f(t: String): Boolean = g(t)` -- has its
    return type read as `Boolean = g(t)`, so a correctly documented `Boolean`
    reports a mismatch. If one ever appears, fix the METHOD or this pattern; do
    not "correct" a right document to match the garbage.
  * An annotated method inside a `/* */` block is counted and parsed as real, and
    reported as undocumented. Commented-out code will ask to be documented.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BRIDGE = ROOT / "android/app/src/main/kotlin/com/vscodroid/bridge/AndroidBridge.kt"
SPEC = ROOT / "docs/05-API_SPEC.md"

ANNOTATION = "@JavascriptInterface"

# Counted where a line STARTS with the annotation, not anywhere in the text. The
# spec discusses the annotation in prose as well as using it -- "all 28
# `@JavascriptInterface` methods take the token" -- and counting those mentions
# would inflate the expectation and fail a file that is correct. Prose mentions
# are mid-sentence or backticked, so anchoring the start is enough to exclude
# them.
#
# Deliberately NOT anchored at the end, and that is the whole reason this
# expression is worth a comment. It was `[ \t]*$`, which made the two counts
# below stop being independent: Kotlin permits
#
#     @JavascriptInterface fun sameLine(authToken: String): Boolean = true
#
# and against that shape ANNOTATION_LINE did not count it (nothing follows the
# annotation on its own line) *and* SIGNATURE did not parse it (it requires a
# newline after the annotation). Both missed, in the same direction, so the
# counts agreed and the guard was satisfied -- measured: twenty-nine methods
# present, "ok, all 28 match", exit 0. A redundancy that fails together is one
# check wearing two hats. Unanchored, the annotation is counted while the
# signature still is not, the counts disagree, and the guard fires.
ANNOTATION_LINE = re.compile(r"^[ \t]*" + ANNOTATION + r"\b", re.M)

# The Kotlin source ends a signature with `{`; the fenced blocks in the spec end
# it with a newline, sometimes after a trailing `//` note. One pattern covers
# all three. The parameter list is matched with [^)] so a signature broken
# across lines still resolves -- none are today, and a reformat should not be
# what makes this check stop seeing a method.
SIGNATURE = re.compile(
    ANNOTATION + r"[ \t]*\n"
    r"\s*fun\s+(?P<name>\w+)\s*"
    r"\((?P<params>[^)]*)\)"
    r"(?:[ \t]*:[ \t]*(?P<returns>[^\n{/]+?))?"
    r"[ \t]*(?://[^\n]*)?"
    r"[ \t]*(?:\{|\n)"
)


def signatures(path):
    """Every annotated signature in a file, plus how many should have been found.

    The second number is the whole point. A regex that silently stops matching
    reports an empty set, and an empty set compares clean against anything --
    which is the shape of a check that cannot fail reading exactly like a check
    that passed. Counting the annotations is independent of parsing them, so the
    two disagreeing says the pattern broke rather than that the code changed.
    """
    text = path.read_text()
    found = {}
    for m in SIGNATURE.finditer(text):
        found[m.group("name")] = (
            re.sub(r"\s+", " ", m.group("params")).strip(),
            (m.group("returns") or "Unit").strip(),
        )
    return found, len(ANNOTATION_LINE.findall(text))


def render(name, sig):
    params, returns = sig
    tail = "" if returns == "Unit" else f": {returns}"
    return f"fun {name}({params}){tail}"


def main() -> int:
    for path in (BRIDGE, SPEC):
        if not path.is_file():
            print(f"  FAIL   missing {path.relative_to(ROOT)}")
            return 1

    code, code_expected = signatures(BRIDGE)
    doc, doc_expected = signatures(SPEC)

    # Fail closed on the extraction itself, before comparing anything. Either
    # side coming back empty, or short of its own annotation count, means this
    # script lost its grip on the files rather than that they agree.
    for label, path, found, expected in (
        ("AndroidBridge.kt", BRIDGE, code, code_expected),
        ("05-API_SPEC.md", SPEC, doc, doc_expected),
    ):
        if not found:
            print(f"  FAIL   parsed no signatures out of {label}; the pattern "
                  f"stopped matching")
            return 1
        if len(found) != expected:
            print(f"  FAIL   {label} has {expected} {ANNOTATION} annotations but "
                  f"{len(found)} signatures parsed")
            print(f"         The pattern matched some and not others, so a "
                  f"clean result here would mean nothing.")
            return 1

    failed = False

    undocumented = sorted(set(code) - set(doc))
    if undocumented:
        print(f"  FAIL   registered on the bridge, absent from the spec")
        for name in undocumented:
            print(f"           {render(name, code[name])}")
        print(f"         Document them in {SPEC.relative_to(ROOT)} §2.4. An "
              f"undocumented method is one nobody can call on purpose.")
        failed = True

    invented = sorted(set(doc) - set(code))
    if invented:
        print(f"  FAIL   documented in the spec, not registered on the bridge")
        for name in invented:
            print(f"           {render(name, doc[name])}")
        print(f"         Remove them from {SPEC.relative_to(ROOT)}, or add them "
              f"to AndroidBridge.kt. Calling one of these fails at runtime.")
        failed = True

    for name in sorted(set(code) & set(doc)):
        axes = []
        if code[name][0] != doc[name][0]:
            axes.append("parameters")
        if code[name][1] != doc[name][1]:
            axes.append("return type")
        if axes:
            print(f"  FAIL   {name}: spec and code disagree on {' and '.join(axes)}")
            print(f"           code: {render(name, code[name])}")
            print(f"           spec: {render(name, doc[name])}")
            failed = True

    if not failed:
        print(f"  ok      all {len(code)} bridge methods match the spec on "
              f"name, parameters and return type")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
