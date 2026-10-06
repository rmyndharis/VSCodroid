#!/usr/bin/env python3
"""Exercise ABI isolation at download/staging/provenance boundaries."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
HELPER = ROOT / "scripts/lib/android-target.sh"


class TargetPackagingTest(unittest.TestCase):
    def shell(self, abi, script, *args):
        env = dict(os.environ, VSCODROID_ABI=abi)
        return subprocess.run(
            ["bash", "-eu", "-c", '. "$1"; ' + script, "test", str(HELPER), *args],
            env=env, text=True, capture_output=True,
        )

    def test_target_mapping_and_invalid_target(self):
        for abi, expected in (
            ("arm64-v8a", "aarch64 arm64 aarch64-linux-android 183"),
            ("x86_64", "x86_64 x64 x86_64-linux-android 62"),
        ):
            result = self.shell(abi, 'echo "$TERMUX_ARCH $NODE_ARCH $NDK_TARGET $ELF_MACHINE"')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), expected)
        self.assertNotEqual(self.shell("amd64", "true").returncode, 0)

    def test_staging_refuses_switch_before_relabeling(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets = Path(tmp) / "assets"
            first = self.shell("arm64-v8a", 'android_target_require_staging "$2"', str(assets))
            self.assertEqual(first.returncode, 0, first.stderr)
            marker = assets / ".vscodroid-abi"
            second = self.shell("x86_64", 'android_target_require_staging "$2"', str(assets))
            self.assertNotEqual(second.returncode, 0)
            self.assertEqual(marker.read_text().strip(), "arm64-v8a")

    def test_unmarked_native_payload_is_not_rebranded(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets = Path(tmp) / "assets"
            payload = assets / "usr/lib/libdependency.so"
            payload.parent.mkdir(parents=True)
            payload.write_bytes(b"\x7fELF legacy payload")
            result = self.shell("x86_64", 'android_target_require_staging "$2"', str(assets))
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((assets / ".vscodroid-abi").exists())

    def test_provenance_reads_only_selected_architecture(self):
        spec = importlib.util.spec_from_file_location("manifest", ROOT / "scripts/write-build-manifest.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            module.ROOT = Path(tmp)
            module.ANDROID_ABI = "x86_64"
            for abi, version in (("x86_64", "24.18.0-x64"), ("arm64-v8a", "wrong-arm")):
                cache = module.ROOT / "toolchains/termux-packages" / abi
                cache.mkdir(parents=True)
                (cache / "resolved-node.tsv").write_text(f"nodejs-lts\tnodejs-lts_{version}_{abi}.deb\t{'a' * 64}\n")
            entries = module.resolved_entries()
            self.assertEqual(len(entries), 1)
            self.assertEqual(entries[0][1], "24.18.0-x64")


if __name__ == "__main__":
    unittest.main()
