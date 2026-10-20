#!/usr/bin/env python3
"""Exercise the Copilot compile stage of build-vscode-oss.sh without a VS Code checkout.

    python3 scripts/test-copilot-compile-guard.py

The stage is cut out of the script between its two step banners and run under
bash with npm replaced, so what runs is the shipped text and not a copy of it.
The stub stands in for the compile and leaves behind whatever the scenario
names: the SDK entry, an empty sdk directory, or nothing.
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
    local n sdk
    n=$(( $(wc -l < "$SRC/npm-calls") ))
    sdk="$SRC/.build/extensions/copilot/node_modules/@github/copilot/sdk"
    case "$SCENARIO" in
        entry-first)  mkdir -p "$sdk"; echo js > "$sdk/index.js" ;;
        entry-second) mkdir -p "$sdk"; if [ "$n" -ge 2 ]; then echo js > "$sdk/index.js"; fi ;;
        empty)        mkdir -p "$sdk" ;;
        nothing)      ;;
    esac
}
"""


def stage():
    assert START in SCRIPT and END in SCRIPT, "the stage banners moved; update this test"
    return SCRIPT[SCRIPT.index(START):SCRIPT.index(END)]


class CopilotCompileStageTest(unittest.TestCase):
    def run_stage(self, scenario):
        with tempfile.TemporaryDirectory(prefix="vscodroid-copilot-stage-") as temporary:
            source = Path(temporary)
            (source / "npm-calls").touch()
            result = subprocess.run(
                ["bash", "-c", PREAMBLE + stage(), "bash", str(source)],
                env=dict(os.environ, SCENARIO=scenario),
                capture_output=True, text=True, timeout=30,
            )
            calls = (source / "npm-calls").read_text().splitlines()
        return result, calls

    def test_a_compile_that_leaves_the_sdk_entry_runs_once(self):
        result, calls = self.run_stage("entry-first")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ["run gulp compile-copilot-extension-build"])

    def test_an_empty_sdk_directory_gets_one_more_compile(self):
        result, calls = self.run_stage("entry-second")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ["run gulp compile-copilot-extension-build"] * 2,
                         "a directory without index.js passed the guard")

    def test_a_second_miss_fails_the_stage_after_exactly_two_compiles(self):
        for scenario in ("empty", "nothing"):
            with self.subTest(scenario=scenario):
                result, calls = self.run_stage(scenario)
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertEqual(len(calls), 2)
                self.assertIn("sdk/index.js", result.stderr)


if __name__ == "__main__":
    unittest.main()
