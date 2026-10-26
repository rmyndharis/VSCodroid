#!/usr/bin/env python3
"""Test browser backend loading and the pinned package rewrite.

Run without arguments for small fixtures, or pass --package to run inference
with an installed package's actual model and weights in a browser worker realm.
"""

import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("patch-language-detection.py")
LOADER = b'require("./"+r.u(e))'
INDEX = (b'globalThis.loadBackend=()=>{const r={u:()=>"979.js"};'
         b'const e=979;return ' + LOADER + b'.modules[4979]("sample");};\n')
CHUNK = (b'"use strict";exports.id=979;exports.ids=[979];'
         b'exports.modules={4979: content=>["cpu",content]};\n')

BROWSER = r"""
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const packageRoot = process.argv[1];
let factory;
const context = vm.createContext({
    console, performance, TextEncoder, TextDecoder, atob, btoa, URL,
    setTimeout, clearTimeout, WorkerGlobalScope: class WorkerGlobalScope {},
    navigator: { userAgent: 'Mozilla/5.0 Chrome/156.0.0.0', vendor: 'Google Inc.' },
    define(deps, callback) { assert.equal(deps.length, 0); factory = callback; },
});
context.define.amd = true;
context.self = context;
vm.runInContext(fs.readFileSync(path.join(packageRoot, 'dist/lib/index.js'), 'utf8'),
    context, {filename: 'language-detection-index.js', timeout: 10000});
context.ModelOperations = factory().ModelOperations;
let jsonLoads = 0, weightsLoads = 0;
context.options = {
    modelJsonLoaderFunc: async () => {
        jsonLoads++;
        return JSON.parse(fs.readFileSync(path.join(packageRoot, 'model/model.json'), 'utf8'));
    },
    weightsLoaderFunc: async () => {
        weightsLoads++;
        const data = fs.readFileSync(path.join(packageRoot, 'model/group1-shard1of1.bin'));
        return data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
    },
};
vm.runInContext('globalThis.operations = new ModelOperations(options)', context);
const samples = [
    ['py', `import json
from pathlib import Path

class Configuration:
    def __init__(self, directory):
        self.directory = Path(directory)

    def read_file(self, filename):
        with (self.directory / filename).open("r", encoding="utf-8") as stream:
            return json.load(stream)

if __name__ == "__main__":
    configuration = Configuration(".")
    for key, value in configuration.read_file("settings.json").items():
        print(f"{key}: {value}")
`],
    ['ts', `interface Thing {
    readonly size: number;
}
function makeThing(): Thing {
    let size = 0;
    return {
        get size(): number {
            return size;
        },
        set size(value: string | number | boolean) {
            let num = Number(value);
            if (!Number.isFinite(num)) {
                size = 0;
                return;
            }
            size = num;
        },
    };
}
`],
];
(async () => {
    assert.equal((await vm.runInContext('operations.runModel("short")', context)).length, 0);
    for (const [language, content] of [...samples, samples[0]]) {
        context.content = content;
        const results = await vm.runInContext('operations.runModel(content)', context);
        assert.equal(results[0].languageId, language);
        assert.ok(results[0].confidence > 0.2, 'result must clear the workbench confidence threshold');
        console.log(JSON.stringify({language, confidence: results[0].confidence}));
    }
    assert.equal(jsonLoads, 1);
    assert.equal(weightsLoads, 1);
    assert.equal(vm.runInContext('typeof require', context), 'undefined');
    assert.equal(vm.runInContext('typeof process', context), 'undefined');
    vm.runInContext('operations.dispose()', context);
})().catch(error => { console.error(error); process.exitCode = 1; });
"""


class LanguageDetectionTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("language_detection", SCRIPT)
        self.mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.mod)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.package = Path(self.temp.name)
        (self.package / "dist/lib").mkdir(parents=True)
        (self.package / "package.json").write_text(json.dumps({
            "name": "@vscode/vscode-languagedetection", "version": "1.0.23",
        }))
        self.index = self.package / "dist/lib/index.js"
        self.chunk = self.package / "dist/lib/979.js"
        self.index.write_bytes(INDEX)
        self.chunk.write_bytes(CHUNK)
        digest_patches = patch.multiple(self.mod,
            INDEX_SHA256=hashlib.sha256(INDEX).hexdigest(),
            CHUNK_SHA256=hashlib.sha256(CHUNK).hexdigest())
        digest_patches.start()
        self.addCleanup(digest_patches.stop)

    def test_backend_runs_without_a_browser_require_shim(self):
        source = """
const fs = require('node:fs'), vm = require('node:vm');
const context = vm.createContext({});
vm.runInContext(fs.readFileSync(process.argv[1], 'utf8'), context);
console.log(JSON.stringify(context.loadBackend()));
if (vm.runInContext('typeof require', context) !== 'undefined') process.exitCode = 2;
"""
        baseline = subprocess.run(["node", "-e", source, str(self.index)], capture_output=True, text=True, timeout=10)
        self.assertNotEqual(baseline.returncode, 0)
        self.assertIn("require is not defined", baseline.stderr)
        self.mod.patch_package(self.package)
        result = subprocess.run(["node", "-e", source, str(self.index)], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), ["cpu", "sample"])

    def test_repeated_patch_and_check_leave_the_files_unchanged(self):
        self.mod.patch_package(self.package)
        result = self.index.read_bytes()
        self.mod.patch_package(self.package)
        self.mod.patch_package(self.package, check_only=True)
        self.assertEqual(self.index.read_bytes(), result)
        self.assertEqual(self.chunk.read_bytes(), CHUNK)

    def test_check_refuses_an_unpatched_package_without_writing(self):
        with self.assertRaisesRegex(ValueError, "not inlined"):
            self.mod.patch_package(self.package, check_only=True)
        self.assertEqual(self.index.read_bytes(), INDEX)

    def test_unknown_index_is_rejected_without_writing(self):
        self.index.write_bytes(INDEX + b"unknown();")
        with self.assertRaisesRegex(ValueError, "index.js"):
            self.mod.patch_package(self.package)
        self.assertEqual(self.index.read_bytes(), INDEX + b"unknown();")

    def test_changed_backend_is_rejected_without_writing(self):
        self.chunk.write_bytes(CHUNK + b"unknown();")
        for check_only in (False, True):
            with self.subTest(check_only=check_only), self.assertRaisesRegex(ValueError, "979.js"):
                self.mod.patch_package(self.package, check_only)
        self.assertEqual(self.index.read_bytes(), INDEX)

    def test_unknown_package_version_is_rejected(self):
        (self.package / "package.json").write_text(json.dumps({
            "name": "@vscode/vscode-languagedetection", "version": "1.0.24",
        }))
        for check_only in (False, True):
            with self.subTest(check_only=check_only), self.assertRaisesRegex(ValueError, "1.0.23"):
                self.mod.patch_package(self.package, check_only)
        self.assertEqual(self.index.read_bytes(), INDEX)

    def test_changed_inlined_backend_is_rejected(self):
        self.mod.patch_package(self.package)
        self.index.write_bytes(self.index.read_bytes().replace(b'"cpu"', b'"bad"'))
        with self.assertRaisesRegex(ValueError, "index.js"):
            self.mod.patch_package(self.package, check_only=True)

    def test_server_verifier_requires_the_browser_backend(self):
        spec = importlib.util.spec_from_file_location("server_tree", SCRIPT.with_name("verify-server-tree.py"))
        verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(verifier)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            verifier.main(self.package)
        self.assertIn("FAIL    language model CPU backend is inlined", output.getvalue())


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--package":
        completed = subprocess.run(["node", "-e", BROWSER, sys.argv[2]], timeout=60)
        sys.exit(completed.returncode)
    unittest.main()
