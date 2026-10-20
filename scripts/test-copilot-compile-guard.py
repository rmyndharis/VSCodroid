#!/usr/bin/env python3
"""Exercise the Copilot compile stage of build-vscode-oss.sh without a VS Code checkout.

    python3 scripts/test-copilot-compile-guard.py

The stage is cut out of the script between its two step banners and run under
bash with npm replaced, so what runs is the shipped text and not a copy of it.
The stub stands in for the compile. It leaves the extension's source tree as its
postinstall does and, in .build, whatever the scenario names: the SDK entry, an
empty or partial sdk directory, or nothing.
"""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = (ROOT / "scripts/build-vscode-oss.sh").read_text()

START = 'step "Copilot extension (compile-copilot-extension-build)"'
END = 'step "Package ('

# set -euo pipefail is the script's own, so a failing command ends the stage here as it does there.
PREAMBLE = r"""
set -euo pipefail
SRC="$1"
step() { :; }
elapsed() { :; }
npm() {
    echo "$*" >> "$SRC/npm-calls"
    local n sdk from
    n=$(( $(wc -l < "$SRC/npm-calls") ))
    sdk="$SRC/.build/extensions/copilot/node_modules/@github/copilot/sdk"
    from="$SRC/extensions/copilot/node_modules/@github/copilot/sdk"
    mkdir -p "$from/definitions/sidekick" "$from/builtin-skills/skill"
    echo a > "$from/definitions/one.yaml"
    echo b > "$from/definitions/sidekick/two.yaml"
    echo c > "$from/builtin-skills/skill/SKILL.md"
    case "$SOURCE_TREE" in
        no-definitions) rm -rf "$from/definitions" ;;
        no-skills)      rm -rf "$from/builtin-skills" ;;
    esac
    case "$SCENARIO" in
        entry-first)  mkdir -p "$sdk"; echo js > "$sdk/index.js" ;;
        entry-second) mkdir -p "$sdk"; if [ "$n" -ge 2 ]; then echo js > "$sdk/index.js"; fi ;;
        partial)      mkdir -p "$sdk/definitions"; echo js > "$sdk/index.js"
                      echo a > "$sdk/definitions/one.yaml" ;;
        empty)        mkdir -p "$sdk" ;;
        nothing)      ;;
    esac
}
"""

# The sdk directory of a build that worked: the entry and what the postinstall writes next to it.
COMPLETE = {
    "index.js": "js\n",
    "definitions/one.yaml": "a\n",
    "definitions/sidekick/two.yaml": "b\n",
    "builtin-skills/skill/SKILL.md": "c\n",
}


def stage():
    assert START in SCRIPT and END in SCRIPT, "the stage banners moved; update this test"
    return SCRIPT[SCRIPT.index(START):SCRIPT.index(END)]


def snapshot(sdk):
    if not sdk.is_dir():
        return {}
    return {path.relative_to(sdk).as_posix(): path.read_text()
            for path in sorted(sdk.rglob("*")) if path.is_file()}


class CopilotCompileStageTest(unittest.TestCase):
    def run_stage(self, scenario, source_tree="complete"):
        with tempfile.TemporaryDirectory(prefix="vscodroid-copilot-stage-") as temporary:
            source = Path(temporary)
            (source / "npm-calls").touch()
            result = subprocess.run(
                ["bash", "-c", PREAMBLE + stage(), "bash", str(source)],
                env=dict(os.environ, SCENARIO=scenario, SOURCE_TREE=source_tree),
                capture_output=True, text=True, timeout=30,
            )
            calls = (source / "npm-calls").read_text().splitlines()
            tree = snapshot(source / ".build/extensions/copilot/node_modules/@github/copilot/sdk")
        return result, calls, tree

    def test_a_compile_that_leaves_the_sdk_entry_runs_once(self):
        result, calls, _ = self.run_stage("entry-first")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ["run gulp compile-copilot-extension-build"])

    def test_an_empty_sdk_directory_gets_one_more_compile(self):
        result, calls, _ = self.run_stage("entry-second")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ["run gulp compile-copilot-extension-build"] * 2,
                         "a directory without index.js passed the guard")

    def test_a_second_miss_fails_the_stage_after_exactly_two_compiles(self):
        for scenario in ("empty", "nothing"):
            with self.subTest(scenario=scenario):
                result, calls, _ = self.run_stage(scenario)
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertEqual(len(calls), 2)
                self.assertIn("sdk/index.js", result.stderr)

    def test_what_the_postinstall_wrote_is_copied_into_build(self):
        result, _, tree = self.run_stage("entry-first")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(tree, COMPLETE)

    def test_a_partial_copy_in_build_ends_up_complete(self):
        result, _, tree = self.run_stage("partial")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(tree, COMPLETE)

    def test_a_directory_missing_from_the_source_tree_fails_the_stage(self):
        cases = (("no-definitions", "definitions"), ("no-skills", "builtin-skills"))
        for source_tree, name in cases:
            with self.subTest(source_tree=source_tree):
                result, _, _ = self.run_stage("entry-first", source_tree)
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertIn(name, result.stderr)


if __name__ == "__main__":
    unittest.main()
