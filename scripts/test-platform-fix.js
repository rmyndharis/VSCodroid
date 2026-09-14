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

// The Jupyter extension's bundle, and only it, is told os.platform() is linux:
// pidtree inside it throws for 'android', which leaves the processes a notebook
// cell started running after an interrupt and orphaned after a restart. The
// probe is loaded by a main script, the way the extension host requires an
// extension, because the hook keys on the requiring module's own path.
const OS_PROBE = "module.exports = { os: require('os').platform(), nodeOs: require('node:os').platform(), real: process.platform };\n";
function osSeenBy(modulePath) {
    fs.mkdirSync(path.dirname(modulePath), { recursive: true });
    fs.writeFileSync(modulePath, OS_PROBE);
    const main = path.join(tmp, 'load-extension.js');
    fs.writeFileSync(main, `console.log(JSON.stringify(require(${JSON.stringify(modulePath)})));\n`);
    return JSON.parse(execFileSync(
        process.execPath,
        ['--require', FAKE_ANDROID, '--require', FIX, main],
        { encoding: 'utf8' },
    ));
}
const EXTENSIONS = path.join(tmp, 'home/.vscodroid/extensions');
const osCases = [
    ['the Jupyter bundle', `${EXTENSIONS}/ms-toolsai.jupyter-2025.9.1/dist/extension.node.js`, 'linux'],
    // Its zeromq loader picks a native build by platform and must see android.
    ['zeromq inside the Jupyter extension', `${EXTENSIONS}/ms-toolsai.jupyter-2025.9.1/dist/node_modules/@aminya/node-gyp-build/index.js`, 'android'],
    ['another extension', `${EXTENSIONS}/ms-python.python-2026.1.0/out/client/extension.js`, 'android'],
    ['a user script named like the bundle, outside an extension', `${tmp}/home/dist/extension.node.js`, 'android'],
];
for (const [label, modulePath, want] of osCases) {
    const got = osSeenBy(modulePath);
    assert.strictEqual(got.os, want, `${label}: os.platform() came out ${got.os}, wanted ${want}`);
    assert.strictEqual(got.nodeOs, want, `${label}: node:os platform() came out ${got.nodeOs}, wanted ${want}`);
    // The process itself keeps the truth either way.
    assert.strictEqual(got.real, 'android', `${label}: process.platform changed to ${got.real}`);
    checked++;
}

// The extension host is a worker started with the server's execArgv and an
// environment VS Code has stripped of NODE_OPTIONS, which is why server.js
// passes the preload there. A worker given it in execArgv has the hook.
const workerMain = path.join(tmp, 'worker-host.js');
const workerExt = `${EXTENSIONS}/ms-toolsai.jupyter-2025.9.1/dist/extension.node.js`;
fs.writeFileSync(workerMain, `
const { Worker } = require('worker_threads');
const env = { ...process.env }; delete env.NODE_OPTIONS;
const w = new Worker(${JSON.stringify(`const { parentPort } = require('worker_threads'); parentPort.postMessage(require(${JSON.stringify(workerExt)}));`)},
    { eval: true, env, execArgv: ['--require', ${JSON.stringify(FAKE_ANDROID)}, '--require', ${JSON.stringify(FIX)}] });
w.on('message', (m) => { console.log(JSON.stringify(m)); w.terminate(); });
w.on('error', (e) => { console.error(e); process.exit(1); });
`);
const inWorker = JSON.parse(execFileSync(process.execPath, [workerMain], { encoding: 'utf8', env: { ...process.env, NODE_OPTIONS: '' } }));
assert.strictEqual(inWorker.os, 'linux', `a worker with the preload in execArgv: os.platform() came out ${inWorker.os}`);
checked++;

fs.rmSync(tmp, { recursive: true, force: true });
console.log(`ok -- ${checked} invocations checked, the override engaged for ${cases.filter((c) => c[4] === 'linux').length}`);
