/**
 * Self-check for the platform override preloaded into every Node process.
 *
 * Environment.kt puts --require=<filesDir>/server/platform-fix.js in
 * NODE_OPTIONS, so this file loads into the server, every terminal command and
 * every script a user runs. What it decides is whether process.platform reports
 * "linux" instead of the truth, and a wrong yes is invisible: the process simply
 * behaves as though it were on a different operating system.
 *
 * The override only engages when the platform really is android, which no CI
 * runner reports. A second --require ahead of it supplies that, so what runs
 * here is the shipped file rather than a copy of its logic.
 *
 *   node scripts/test-platform-fix.js
 */

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const FIX = path.resolve(__dirname, '../android/app/src/main/assets/platform-fix.js');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'vscodroid-platform-'));

// Termux's Node reports "android"; nothing that runs this suite does.
const FAKE_ANDROID = path.join(tmp, 'fake-android.js');
fs.writeFileSync(
    FAKE_ANDROID,
    "Object.defineProperty(process, 'platform', { value: 'android', configurable: true });\n",
);

const PROBE = "console.log(JSON.stringify({ platform: process.platform, optIn: process.env.VSCODROID_PLATFORM_FIX || null }));\n";

/** Runs `script` under the preload and returns what it observed. */
function run(scriptPath, args = [], env = {}) {
    fs.mkdirSync(path.dirname(scriptPath), { recursive: true });
    fs.writeFileSync(scriptPath, PROBE);
    const out = execFileSync(
        process.execPath,
        ['--require', FAKE_ANDROID, '--require', FIX, scriptPath, ...args],
        { env: { ...process.env, ...env }, encoding: 'utf8' },
    );
    return JSON.parse(out);
}

const NM = path.join(tmp, 'node_modules');

const cases = [
    // label, script node is asked to run, extra argv, env, expected platform
    ['node-gyp itself', `${NM}/node-gyp/bin/node-gyp.js`, ['rebuild'], {}, 'linux'],
    ['node-gyp-build resolving prebuilds', `${NM}/node-gyp-build/bin.js`, [], {}, 'linux'],
    // The defect: a substring anywhere in argv used to be enough.
    ['a directory that merely says node-gyp', `${tmp}/home/node-gyp-notes/run.js`, [], {}, 'android'],
    ['node-gyp named only in an argument', `${tmp}/home/build.js`, ['--out', `${tmp}/home/node-gyp-notes/`], {}, 'android'],
    ['an ordinary user script', `${tmp}/home/serve.js`, [], {}, 'android'],
    // The opt-in npm and npx set.
    ['the npm opt-in', `${tmp}/home/install.js`, [], { VSCODROID_PLATFORM_FIX: '1' }, 'linux'],
];

let checked = 0;
for (const [label, script, args, env, want] of cases) {
    const got = run(script, args, env);
    assert.strictEqual(got.platform, want, `${label}: platform came out ${got.platform}, wanted ${want}`);
    // Rollup and esbuild ship real android-arm64 builds and break under the
    // override, so the flag must not reach a child.
    assert.strictEqual(got.optIn, null, `${label}: VSCODROID_PLATFORM_FIX survived into the process`);
    checked++;
}

fs.rmSync(tmp, { recursive: true, force: true });
console.log(`ok -- ${checked} invocations checked, the override engaged for ${cases.filter((c) => c[4] === 'linux').length}`);
