#!/usr/bin/env python3
"""Verify R8 keep rules preserved every reflective entry point and error type.

    verify-r8-keeps.py [--mapping-dir <dir>] [--manifest <file>] [--kotlin-root <dir>]
    verify-r8-keeps.py --self-test

R8 failing is caught by Gradle; R8 succeeding while stripping or renaming a
reflectively invoked member is silent until the minified APK runs on a device.
This script parses AndroidManifest.xml, the @JavascriptInterface bridge
methods, VSCodroidApp, and every Throwable subclass under com.vscodroid, then
verifies against R8's seeds.txt and mapping.txt that every expected class and
bridge method survived unstripped and unrenamed.
"""

from __future__ import annotations

import argparse
import contextlib
import io
import pathlib
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_MAPPING_DIR = REPO_ROOT / "android/app/build/outputs/mapping/release"
DEFAULT_MANIFEST = REPO_ROOT / "android/app/src/main/AndroidManifest.xml"
DEFAULT_KOTLIN_ROOT = REPO_ROOT / "android/app/src/main/kotlin/com/vscodroid"
APP_NAMESPACE = "com.vscodroid"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

ANNOTATION_LINE = re.compile(r"^[ \t]*@(?:android\.webkit\.)?JavascriptInterface\b", re.M)
BRIDGE_METHOD = re.compile(
    r"@(?:android\.webkit\.)?JavascriptInterface[ \t]*(?:\n[ \t]*|[ \t]+)"
    r"(?:(?:public|internal|open|final|override|synchronized)\s+)*"
    r"fun\s+(?P<name>\w+)\s*\("
)
PACKAGE_DECL = re.compile(r"^\s*package\s+([A-Za-z_][\w.]*)", re.M)
CLASS_DECL = re.compile(r"\b(?:class|object)\s+(?P<name>[A-Za-z_]\w*)\b")
CTOR_MODIFIER = re.compile(r"(?:private|protected|internal|public|constructor)\b")
THROWABLE_SUPER = re.compile(
    r"\b(?:java\.lang\.)?(?:Throwable|Error|Exception|RuntimeException|IOException|"
    r"[A-Za-z_]\w*(?:Exception|Error|Throwable))\b"
)
SEED_MEMBER = re.compile(r"^(?P<cls>[^:\s]+):\s+(?P<ret>\S+)\s+(?P<name>[^(\s]+)\(")
MAPPING_CLASS = re.compile(r"^(?P<orig>[^\s#]+)\s+->\s+(?P<mapped>[^\s:]+):\s*$")
MAPPING_METHOD = re.compile(
    r"^    (?:\d+:\d+:)?(?P<ret>\S+)\s+(?P<name>[^(\s]+)\([^)]*\)"
    r"(?::\d+(?::\d+)?)?\s+->\s+(?P<mapped>\S+)\s*$"
)


def strip_kotlin_non_code(text: str) -> str:
    """Blank comments and string/char literals while preserving newlines and braces."""
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
        elif ch == "/" and nxt == "*":
            depth = 1
            out.extend((" ", " "))
            i += 2
            while i < n and depth > 0:
                if i + 1 < n and text[i] == "/" and text[i + 1] == "*":
                    depth += 1
                    out.extend((" ", " "))
                    i += 2
                elif i + 1 < n and text[i] == "*" and text[i + 1] == "/":
                    depth -= 1
                    out.extend((" ", " "))
                    i += 2
                else:
                    out.append("\n" if text[i] == "\n" else " ")
                    i += 1
        elif text.startswith('"""', i):
            out.extend((" ", " ", " "))
            i += 3
            while i < n:
                if text.startswith('"""', i):
                    out.extend((" ", " ", " "))
                    i += 3
                    break
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
        elif ch == '"':
            out.append(" ")
            i += 1
            while i < n:
                c = text[i]
                if c == "\\" and i + 1 < n:
                    out.extend((" ", "\n" if text[i + 1] == "\n" else " "))
                    i += 2
                elif c == '"':
                    out.append(" ")
                    i += 1
                    break
                else:
                    out.append("\n" if c == "\n" else " ")
                    i += 1
        elif ch == "'":
            out.append(" ")
            i += 1
            while i < n:
                c = text[i]
                if c == "\\" and i + 1 < n:
                    out.extend((" ", " "))
                    i += 2
                elif c == "'":
                    out.append(" ")
                    i += 1
                    break
                else:
                    out.append("\n" if c == "\n" else " ")
                    i += 1
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def resolve_manifest_name(raw: str, namespace: str = APP_NAMESPACE) -> str:
    if raw.startswith("."):
        return namespace + raw
    if "." not in raw:
        return f"{namespace}.{raw}"
    return raw


def parse_manifest_components(manifest_path: pathlib.Path, namespace: str = APP_NAMESPACE) -> tuple[str | None, list[str]]:
    """Return (application_fqcn, sorted component fqcns under namespace)."""
    root = ET.parse(manifest_path).getroot()
    app = root.find("application")
    if app is None:
        return None, []
    app_raw = app.get(f"{ANDROID_NS}name")
    app_fqcn = resolve_manifest_name(app_raw, namespace) if app_raw else None
    components: list[str] = []
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for elem in app.findall(tag):
            raw = elem.get(f"{ANDROID_NS}name")
            if not raw:
                continue
            fqcn = resolve_manifest_name(raw, namespace)
            if fqcn.startswith(namespace + "."):
                components.append(fqcn)
    return app_fqcn, sorted(set(components))


def skip_balanced(text: str, pos: int, open_ch: str, close_ch: str) -> int:
    """Starting at text[pos] == open_ch, return index right after matching close_ch."""
    n = len(text)
    depth = 0
    while pos < n:
        ch = text[pos]
        if ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return pos + 1
        pos += 1
    return pos


def parse_kotlin_classes_with_spans(clean: str, package: str) -> list[tuple[str, int, int, str]]:
    """Find class/object declarations with their body spans and supertype clauses."""
    results: list[tuple[str, int, int, str]] = []
    # Track enclosing named types by brace depth so nested classes use `$`.
    depth = 0
    scope_stack: list[tuple[int, str]] = []
    pos = 0
    n = len(clean)
    headers = list(CLASS_DECL.finditer(clean))
    header_idx = 0

    while pos < n:
        while header_idx < len(headers) and headers[header_idx].start() < pos:
            header_idx += 1
        if header_idx < len(headers) and headers[header_idx].start() == pos:
            m = headers[header_idx]
            header_idx += 1
            while scope_stack and scope_stack[-1][0] >= depth:
                scope_stack.pop()
            prev_non_ws = clean[:m.start()].rstrip()
            if prev_non_ws.endswith("::") or prev_non_ws.endswith("."):
                pos = m.end()
                continue

            name = m.group("name")
            prefix = scope_stack[-1][1] + "$" if scope_stack else f"{package}."
            fqcn = prefix + name

            scan = m.end()
            while scan < n and clean[scan] in " \t\r\n":
                scan += 1
            if scan < n and clean[scan] == "<":
                scan = skip_balanced(clean, scan, "<", ">")
                while scan < n and clean[scan] in " \t\r\n":
                    scan += 1
            while scan < n:
                mod = CTOR_MODIFIER.match(clean, scan)
                if not mod:
                    break
                scan = mod.end()
                while scan < n and clean[scan] in " \t\r\n":
                    scan += 1
            if scan < n and clean[scan] == "(":
                scan = skip_balanced(clean, scan, "(", ")")
                while scan < n and clean[scan] in " \t\r\n":
                    scan += 1

            supers = ""
            if scan < n and clean[scan] == ":":
                scan += 1
                supers_start = scan
                paren_depth = 0
                angle_depth = 0
                while scan < n:
                    c = clean[scan]
                    if c == "(":
                        paren_depth += 1
                    elif c == ")":
                        paren_depth = max(0, paren_depth - 1)
                    elif c == "<":
                        angle_depth += 1
                    elif c == ">":
                        angle_depth = max(0, angle_depth - 1)
                    elif paren_depth == 0 and angle_depth == 0:
                        if c in "{;":
                            break
                        if c == "\n":
                            tail = clean[supers_start:scan].rstrip()
                            if not tail.endswith(","):
                                break
                    scan += 1
                supers = clean[supers_start:scan].strip()
                while scan < n and clean[scan] in " \t\r\n":
                    scan += 1

            if scan < n and clean[scan] == "{":
                body_end = skip_balanced(clean, scan, "{", "}") - 1
                results.append((fqcn, scan, body_end, supers))
                scope_stack.append((depth, fqcn))
                depth += 1
                pos = scan + 1
                continue
            results.append((fqcn, m.start(), scan, supers))
            pos = scan
            continue

        ch = clean[pos]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth = max(0, depth - 1)
            while scope_stack and scope_stack[-1][0] >= depth:
                scope_stack.pop()
        pos += 1

    return results


def parse_bridge_methods(kotlin_root: pathlib.Path) -> tuple[dict[str, set[str]], list[str]]:
    """Collect @JavascriptInterface methods by owning class under bridge/."""
    bridge_dir = kotlin_root / "bridge"
    errors: list[str] = []
    methods_by_class: dict[str, set[str]] = {}
    if not bridge_dir.is_dir():
        return {}, [f"bridge directory missing: {bridge_dir}"]

    for kt_file in sorted(bridge_dir.glob("*.kt")):
        raw = kt_file.read_text(encoding="utf-8")
        clean = strip_kotlin_non_code(raw)
        pkg_match = PACKAGE_DECL.search(clean)
        if not pkg_match:
            continue
        package = pkg_match.group(1)
        expected_count = len(ANNOTATION_LINE.findall(clean))
        classes = parse_kotlin_classes_with_spans(clean, package)
        parsed_count = 0
        for m in BRIDGE_METHOD.finditer(clean):
            parsed_count += 1
            offset = m.start()
            # Innermost enclosing class containing the annotated method.
            owners = [
                (end - start, fqcn)
                for fqcn, start, end, _ in classes
                if start <= offset <= end
            ]
            if not owners:
                errors.append(f"{kt_file.name}: @JavascriptInterface fun {m.group('name')} has no enclosing class")
                continue
            owner = min(owners)[1]
            methods_by_class.setdefault(owner, set()).add(m.group("name"))
        if expected_count != parsed_count:
            errors.append(
                f"{kt_file.name}: found {expected_count} @JavascriptInterface annotations "
                f"but parsed {parsed_count} methods"
            )
    return methods_by_class, errors


def parse_throwable_subclasses(kotlin_root: pathlib.Path) -> list[str]:
    """Collect every com.vscodroid Throwable/Exception/Error subclass in Kotlin sources."""
    found: set[str] = set()
    for kt_file in sorted(kotlin_root.rglob("*.kt")):
        clean = strip_kotlin_non_code(kt_file.read_text(encoding="utf-8"))
        pkg_match = PACKAGE_DECL.search(clean)
        if not pkg_match:
            continue
        package = pkg_match.group(1)
        for fqcn, _, _, supers in parse_kotlin_classes_with_spans(clean, package):
            if supers and THROWABLE_SUPER.search(supers):
                found.add(fqcn)
    return sorted(found)


def parse_seeds(seeds_path: pathlib.Path) -> tuple[set[str], dict[str, set[str]]]:
    """Return (kept_classes, kept_methods_by_class) from R8's seeds.txt."""
    classes: set[str] = set()
    methods: dict[str, set[str]] = {}
    for raw in seeds_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if ":" not in line:
            classes.add(line)
            continue
        m = SEED_MEMBER.match(line)
        if m:
            methods.setdefault(m.group("cls"), set()).add(m.group("name"))
    return classes, methods


def parse_mapping(mapping_path: pathlib.Path) -> tuple[dict[str, str], dict[str, dict[str, set[str]]]]:
    """Return (class_map, method_targets[orig_class][orig_method] -> {mapped_names})."""
    class_map: dict[str, str] = {}
    method_targets: dict[str, dict[str, set[str]]] = {}
    current_class: str | None = None
    for line in mapping_path.read_text(encoding="utf-8").splitlines():
        if not line or line.lstrip().startswith("#"):
            continue
        if not line.startswith("    "):
            m = MAPPING_CLASS.match(line)
            if m:
                current_class = m.group("orig")
                class_map[current_class] = m.group("mapped")
            else:
                current_class = None
            continue
        if current_class is None:
            continue
        mm = MAPPING_METHOD.match(line)
        if mm:
            orig_name = mm.group("name")
            if "." in orig_name:
                # Inlined frame from another class, not a member of current_class.
                continue
            method_targets.setdefault(current_class, {}).setdefault(orig_name, set()).add(mm.group("mapped"))
    return class_map, method_targets


def class_kept_unrenamed(
    fqcn: str,
    seed_classes: set[str],
    class_map: dict[str, str],
) -> tuple[bool, str]:
    if fqcn not in seed_classes:
        return False, "absent from seeds.txt (not matched by any keep rule)"
    mapped = class_map.get(fqcn)
    if mapped is None:
        return True, ""
    if mapped.startswith("R8$$REMOVED$$"):
        return False, f"stripped by R8 ({mapped})"
    if mapped != fqcn:
        return False, f"renamed in mapping.txt to {mapped}"
    return True, ""


def verify(
    mapping_dir: pathlib.Path,
    manifest_path: pathlib.Path,
    kotlin_root: pathlib.Path,
) -> int:
    failed = False

    def report(ok: bool, label: str, detail: str = "") -> None:
        nonlocal failed
        print(f"  {'ok     ' if ok else 'FAIL   '} {label}{'' if ok else ('  ' + detail if detail else '')}")
        if not ok:
            failed = True

    seeds_path = mapping_dir / "seeds.txt"
    mapping_path = mapping_dir / "mapping.txt"
    for path in (manifest_path, seeds_path, mapping_path):
        if not path.is_file():
            report(False, f"{path} exists", "run :app:minifyReleaseWithR8 first")
            return 1
    if not kotlin_root.is_dir():
        report(False, f"{kotlin_root} exists", "Kotlin source root is missing")
        return 1

    seed_classes, seed_methods = parse_seeds(seeds_path)
    class_map, method_targets = parse_mapping(mapping_path)
    if not seed_classes or not class_map:
        report(False, "seeds.txt and mapping.txt are non-empty",
               f"seeds={len(seed_classes)} classes, mapping={len(class_map)} classes")
        return 1

    app_fqcn, components = parse_manifest_components(manifest_path)
    if app_fqcn != f"{APP_NAMESPACE}.VSCodroidApp":
        report(False, "AndroidManifest.xml declares com.vscodroid.VSCodroidApp",
               f"found {app_fqcn!r}")
    else:
        ok, why = class_kept_unrenamed(app_fqcn, seed_classes, class_map)
        report(ok, f"Application class {app_fqcn} kept unrenamed", why)

    if not components:
        report(False, "AndroidManifest.xml declares app components", "parsed 0 components")
    else:
        bad_components: list[str] = []
        for comp in components:
            ok, why = class_kept_unrenamed(comp, seed_classes, class_map)
            if not ok:
                bad_components.append(f"{comp}: {why}")
        if bad_components:
            for item in bad_components:
                report(False, "manifest component kept unrenamed", item)
        else:
            report(True, f"all {len(components)} manifest components kept unrenamed")

    bridge_methods, bridge_errors = parse_bridge_methods(kotlin_root)
    for err in bridge_errors:
        report(False, "bridge @JavascriptInterface extraction succeeded", err)
    total_bridge = sum(len(ms) for ms in bridge_methods.values())
    if total_bridge == 0:
        report(False, "@JavascriptInterface methods discovered under bridge/", "found 0 methods")
    else:
        bad_bridge: list[str] = []
        for cls, methods in sorted(bridge_methods.items()):
            cls_ok, cls_why = class_kept_unrenamed(cls, seed_classes, class_map)
            if not cls_ok:
                bad_bridge.append(f"{cls}: {cls_why}")
                continue
            kept_in_seeds = seed_methods.get(cls, set())
            mapped_in_cls = method_targets.get(cls, {})
            for method in sorted(methods):
                if method not in kept_in_seeds:
                    bad_bridge.append(f"{cls}.{method}: absent from seeds.txt")
                    continue
                targets = mapped_in_cls.get(method)
                if targets is not None and targets != {method}:
                    renamed = sorted(targets - {method}) or sorted(targets)
                    bad_bridge.append(f"{cls}.{method}: renamed in mapping.txt to {', '.join(renamed)}")
        if bad_bridge:
            for item in bad_bridge:
                report(False, "@JavascriptInterface method kept unrenamed", item)
        else:
            report(True, f"all {total_bridge} @JavascriptInterface methods across {len(bridge_methods)} bridge class(es) kept unrenamed")

    throwables = parse_throwable_subclasses(kotlin_root)
    if not throwables:
        report(False, "com.vscodroid Throwable subclasses discovered", "found 0 subclasses")
    else:
        bad_throwables: list[str] = []
        for cls in throwables:
            ok, why = class_kept_unrenamed(cls, seed_classes, class_map)
            if not ok:
                bad_throwables.append(f"{cls}: {why}")
        if bad_throwables:
            for item in bad_throwables:
                report(False, "com.vscodroid Throwable subclass kept unrenamed", item)
        else:
            report(True, f"all {len(throwables)} com.vscodroid Throwable subclasses kept unrenamed")

    return 1 if failed else 0


def self_test() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        manifest = root / "AndroidManifest.xml"
        kotlin = root / "kotlin/com/vscodroid"
        mapping_dir = root / "mapping"
        (kotlin / "bridge").mkdir(parents=True)
        (kotlin / "setup").mkdir(parents=True)
        mapping_dir.mkdir(parents=True)

        manifest.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '  <application android:name=".VSCodroidApp">\n'
            '    <activity android:name=".MainActivity" />\n'
            '    <service android:name=".service.NodeService" />\n'
            '    <provider android:name=".storage.FixtureProvider" />\n'
            '  </application>\n'
            '</manifest>\n',
            encoding="utf-8",
        )
        (kotlin / "bridge/AndroidBridge.kt").write_text(
            'package com.vscodroid.bridge\n'
            'class AndroidBridge(\n'
            '    private val onBack: () -> Boolean = { false },\n'
            '    private val onAnswer: (String, Boolean) -> Unit = { _, _ -> },\n'
            ') {\n'
            '    /* @JavascriptInterface\n'
            '       fun ignoredCommentedMethod() {} */\n'
            '    @JavascriptInterface\n'
            '    fun copyToClipboard(token: String, text: String): Boolean = true\n'
            '    @JavascriptInterface\n'
            '    fun openFolder(token: String) {}\n'
            '}\n',
            encoding="utf-8",
        )
        (kotlin / "setup/ErrorsFixture.kt").write_text(
            'package com.vscodroid.setup\n'
            'import java.io.IOException\n'
            'class Outer {\n'
            '    private class NestedFailure(msg: String) : IOException(msg)\n'
            '    val embeddedPython = """\n'
            '        class Errors(logging.StreamHandler):\n'
            '            pass\n'
            '    """\n'
            '}\n'
            'private class TopFailure(msg: String) : Exception(msg)\n',
            encoding="utf-8",
        )

        good_seeds = "\n".join([
            "com.vscodroid.VSCodroidApp",
            "com.vscodroid.MainActivity",
            "com.vscodroid.service.NodeService",
            "com.vscodroid.storage.FixtureProvider",
            "com.vscodroid.bridge.AndroidBridge",
            "com.vscodroid.bridge.AndroidBridge: boolean copyToClipboard(java.lang.String,java.lang.String)",
            "com.vscodroid.bridge.AndroidBridge: void openFolder(java.lang.String)",
            "com.vscodroid.setup.Outer$NestedFailure",
            "com.vscodroid.setup.TopFailure",
        ]) + "\n"
        good_mapping = "\n".join([
            "com.vscodroid.VSCodroidApp -> com.vscodroid.VSCodroidApp:",
            '# {"id":"sourceFile","fileName":"VSCodroidApp.kt"}',
            "com.vscodroid.MainActivity -> com.vscodroid.MainActivity:",
            "com.vscodroid.service.NodeService -> com.vscodroid.service.NodeService:",
            "com.vscodroid.storage.FixtureProvider -> com.vscodroid.storage.FixtureProvider:",
            "com.vscodroid.bridge.AndroidBridge -> com.vscodroid.bridge.AndroidBridge:",
            '# {"id":"sourceFile","fileName":"AndroidBridge.kt"}',
            "    1:5:boolean copyToClipboard(java.lang.String,java.lang.String):10:14 -> copyToClipboard",
            "    6:9:void openFolder(java.lang.String):20:23 -> openFolder",
            "com.vscodroid.setup.Outer$NestedFailure -> com.vscodroid.setup.Outer$NestedFailure:",
            "com.vscodroid.setup.TopFailure -> com.vscodroid.setup.TopFailure:",
        ]) + "\n"

        def run_case(seeds: str, mapping: str) -> tuple[int, str]:
            (mapping_dir / "seeds.txt").write_text(seeds, encoding="utf-8")
            (mapping_dir / "mapping.txt").write_text(mapping, encoding="utf-8")
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                rc = verify(mapping_dir, manifest, kotlin)
            return rc, buf.getvalue()

        rc, out = run_case(good_seeds, good_mapping)
        if rc != 0:
            print(f"  FAIL   self-test: valid fixture was refused:\n{out}")
            return 1

        # Embedded Python class inside raw string must not be treated as a Kotlin Throwable.
        if "Errors" in out:
            print(f"  FAIL   self-test: raw-string Python class leaked into Throwable scan:\n{out}")
            return 1

        cases = [
            (
                "missing @JavascriptInterface seed",
                good_seeds.replace("com.vscodroid.bridge.AndroidBridge: void openFolder(java.lang.String)\n", ""),
                good_mapping,
                "AndroidBridge.openFolder",
            ),
            (
                "renamed @JavascriptInterface method",
                good_seeds,
                good_mapping.replace("-> copyToClipboard", "-> a"),
                "AndroidBridge.copyToClipboard",
            ),
            (
                "partially renamed @JavascriptInterface overload",
                good_seeds,
                good_mapping.replace(
                    "    1:5:boolean copyToClipboard(java.lang.String,java.lang.String):10:14 -> copyToClipboard",
                    "    1:5:boolean copyToClipboard(java.lang.String,java.lang.String):10:14 -> copyToClipboard\n"
                    "    10:14:boolean copyToClipboard(java.lang.String):15:19 -> a",
                ),
                "AndroidBridge.copyToClipboard",
            ),
            (
                "missing manifest service seed",
                good_seeds.replace("com.vscodroid.service.NodeService\n", ""),
                good_mapping,
                "NodeService",
            ),
            (
                "renamed VSCodroidApp",
                good_seeds,
                good_mapping.replace(
                    "com.vscodroid.VSCodroidApp -> com.vscodroid.VSCodroidApp:",
                    "com.vscodroid.VSCodroidApp -> x0:",
                ),
                "VSCodroidApp",
            ),
            (
                "renamed Throwable subclass",
                good_seeds,
                good_mapping.replace(
                    "com.vscodroid.setup.TopFailure -> com.vscodroid.setup.TopFailure:",
                    "com.vscodroid.setup.TopFailure -> oo:",
                ),
                "TopFailure",
            ),
            (
                "stripped nested Throwable subclass",
                good_seeds,
                good_mapping.replace(
                    "com.vscodroid.setup.Outer$NestedFailure -> com.vscodroid.setup.Outer$NestedFailure:",
                    "com.vscodroid.setup.Outer$NestedFailure -> R8$$REMOVED$$CLASS$$1:",
                ),
                "Outer$NestedFailure",
            ),
        ]
        for label, seeds, mapping, needle in cases:
            rc, out = run_case(seeds, mapping)
            if rc == 0 or needle not in out:
                print(f"  FAIL   self-test: {label} was not refused as expected:\n{out}")
                return 1

    print(f"  ok     self-test: R8 keep verifier passed clean fixture and refused all {len(cases)} regressions")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mapping-dir", type=pathlib.Path, default=DEFAULT_MAPPING_DIR)
    parser.add_argument("--manifest", type=pathlib.Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--kotlin-root", type=pathlib.Path, default=DEFAULT_KOTLIN_ROOT)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    return verify(args.mapping_dir, args.manifest, args.kotlin_root)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
