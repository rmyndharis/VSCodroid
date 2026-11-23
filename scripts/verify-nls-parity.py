#!/usr/bin/env python3
"""Verify translated NLS bundles cover all supported locales and match the server key count.

    verify-nls-parity.py [--server-out <dir>] [--nls-dir <dir>]
    verify-nls-parity.py --self-test

The workbench reads interface strings out of `globalThis._VSCODE_NLS_MESSAGES`
by array index, so every `<locale>.json` in `android/app/src/main/assets/nls/`
must exist for every supported locale declared in `scripts/build-nls-bundles.py`
and carry the exact same number of entries as `out/nls.keys.json` and
`out/nls.messages.json` in the packaged server tree.
"""

from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_SERVER_OUT = REPO_ROOT / "android/app/src/main/assets/vscode-reh/out"
DEFAULT_NLS_DIR = REPO_ROOT / "android/app/src/main/assets/nls"


def load_supported_locales() -> list[str]:
    """Read LOCALES from scripts/build-nls-bundles.py so the list has one owner."""
    builder_path = pathlib.Path(__file__).with_name("build-nls-bundles.py")
    spec = importlib.util.spec_from_file_location("vscodroid_build_nls_bundles", builder_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"could not load {builder_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    locales = list(getattr(module, "LOCALES", []))
    if not locales:
        raise RuntimeError(f"{builder_path} declared an empty LOCALES list")
    return locales


def parse_server_nls_count(server_out: pathlib.Path) -> tuple[int | None, list[str]]:
    """Parse nls.messages.json and nls.keys.json the same way build-nls-bundles.py does."""
    errors: list[str] = []
    messages_file = server_out / "nls.messages.json"
    keys_file = server_out / "nls.keys.json"
    for path in (messages_file, keys_file):
        if not path.is_file():
            errors.append(f"{path} is missing")
    if errors:
        return None, errors

    try:
        messages = json.loads(messages_file.read_text(encoding="utf-8"))
    except (OSError, ValueError) as err:
        return None, [f"{messages_file} is not readable JSON: {err}"]
    try:
        keys = json.loads(keys_file.read_text(encoding="utf-8"))
    except (OSError, ValueError) as err:
        return None, [f"{keys_file} is not readable JSON: {err}"]

    if not isinstance(messages, list) or not messages or not all(isinstance(item, str) for item in messages):
        return None, [f"{messages_file} must be a non-empty JSON array of strings"]
    if not isinstance(keys, list):
        return None, [f"{keys_file} must be a JSON array of [module, keys] pairs"]

    flat = 0
    for entry in keys:
        if (
            not isinstance(entry, list)
            or len(entry) != 2
            or not isinstance(entry[0], str)
            or not isinstance(entry[1], list)
            or not all(isinstance(k, str) for k in entry[1])
        ):
            return None, [f"{keys_file} contains a malformed [module, keys] entry"]
        flat += len(entry[1])

    if flat != len(messages):
        return None, [
            f"nls.keys.json describes {flat} messages, but nls.messages.json holds {len(messages)}"
        ]
    return flat, []


def verify(server_out: pathlib.Path, nls_dir: pathlib.Path, locales: list[str] | None = None) -> int:
    failed = False

    def report(ok: bool, label: str, detail: str = "") -> None:
        nonlocal failed
        print(f"  {'ok     ' if ok else 'FAIL   '} {label}{'' if ok else ('  ' + detail if detail else '')}")
        if not ok:
            failed = True

    if locales is None:
        try:
            locales = load_supported_locales()
        except (OSError, RuntimeError) as err:
            report(False, "supported locales list is readable", str(err))
            return 1

    expected_count, server_errors = parse_server_nls_count(server_out)
    if expected_count is None:
        for err in server_errors:
            report(False, "packaged server NLS metadata is valid", err)
        return 1
    report(True, f"packaged server defines {expected_count} _VSCODE_NLS_MESSAGES entries")

    if not nls_dir.is_dir():
        report(False, f"{nls_dir} exists", "run python3 scripts/build-nls-bundles.py")
        return 1

    expected_files = {f"{locale}.json" for locale in locales}
    stale = sorted(p.name for p in nls_dir.glob("*.json") if p.name not in expected_files)
    if stale:
        report(
            False,
            "no stale locale bundles in assets/nls/",
            f"unexpected bundle(s): {', '.join(stale)}",
        )
    else:
        report(True, "no stale locale bundles in assets/nls/")

    matched = 0
    for locale in locales:
        bundle_path = nls_dir / f"{locale}.json"
        if not bundle_path.is_file():
            report(False, f"{bundle_path.name} exists", "missing translated bundle")
            continue
        try:
            data = json.loads(bundle_path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as err:
            report(False, f"{bundle_path.name} is readable JSON", str(err))
            continue
        if not isinstance(data, list) or not all(isinstance(item, str) for item in data):
            report(False, f"{bundle_path.name} is a JSON array of strings", "wrong JSON structure")
            continue
        if len(data) != expected_count:
            report(
                False,
                f"{bundle_path.name} matches server key count ({expected_count})",
                f"found {len(data)} entries; rebuild bundles with scripts/build-nls-bundles.py",
            )
            continue
        matched += 1

    if matched == len(locales):
        report(True, f"all {len(locales)} locale bundles match {expected_count} entries")

    return 1 if failed else 0


def self_test() -> int:
    locales = load_supported_locales()
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        server_out = root / "vscode-reh/out"
        nls_dir = root / "nls"
        server_out.mkdir(parents=True)
        nls_dir.mkdir(parents=True)
        (server_out / "nls.messages.json").write_text(json.dumps(["A", "B"]), encoding="utf-8")
        (server_out / "nls.keys.json").write_text(json.dumps([["vs/m", ["a", "b"]]]), encoding="utf-8")

        def run_case() -> tuple[int, str]:
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                rc = verify(server_out, nls_dir, locales)
            return rc, buf.getvalue()

        for locale in locales:
            (nls_dir / f"{locale}.json").write_text(json.dumps(["1", "2"]), encoding="utf-8")
        rc, out = run_case()
        if rc != 0:
            print(f"  FAIL   self-test: valid {len(locales)}-locale tree was refused:\n{out}")
            return 1

        # Missing locale is refused.
        (nls_dir / f"{locales[0]}.json").unlink()
        rc, out = run_case()
        if rc == 0 or f"{locales[0]}.json" not in out:
            print(f"  FAIL   self-test: missing {locales[0]}.json was not refused:\n{out}")
            return 1

        # Length mismatch is refused.
        (nls_dir / f"{locales[0]}.json").write_text(json.dumps(["1"]), encoding="utf-8")
        rc, out = run_case()
        if rc == 0 or "found 1 entries" not in out:
            print(f"  FAIL   self-test: short {locales[0]}.json was not refused:\n{out}")
            return 1

        # Non-string element is refused.
        (nls_dir / f"{locales[0]}.json").write_text(json.dumps(["1", 2]), encoding="utf-8")
        rc, out = run_case()
        if rc == 0 or "JSON array of strings" not in out:
            print(f"  FAIL   self-test: non-string entry in {locales[0]}.json was not refused:\n{out}")
            return 1

        # Restore and add a stale locale file; must be refused.
        (nls_dir / f"{locales[0]}.json").write_text(json.dumps(["1", "2"]), encoding="utf-8")
        stale = nls_dir / "xx-stale.json"
        stale.write_text(json.dumps(["1", "2"]), encoding="utf-8")
        rc, out = run_case()
        if rc == 0 or "xx-stale.json" not in out:
            print(f"  FAIL   self-test: stale bundle was not refused:\n{out}")
            return 1
        stale.unlink()

        # Server keys/messages length disagreement is refused.
        (server_out / "nls.messages.json").write_text(json.dumps(["A"]), encoding="utf-8")
        rc, out = run_case()
        if rc == 0 or "describes 2 messages" not in out:
            print(f"  FAIL   self-test: server keys/messages mismatch was not refused:\n{out}")
            return 1

    print(f"  ok     self-test: NLS parity gate verifies all {len(locales)} locales and key counts")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-out", type=pathlib.Path, default=DEFAULT_SERVER_OUT)
    parser.add_argument("--nls-dir", type=pathlib.Path, default=DEFAULT_NLS_DIR)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    return verify(args.server_out, args.nls_dir)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
