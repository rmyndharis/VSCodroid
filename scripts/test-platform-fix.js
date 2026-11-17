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

// The Codex extension starts its static musl engine, which Android cannot run,
// with child_process.spawn on the path inside its own directory. While the
// toolchain's `codex` command exists that one spawn is pointed at it. A recorder
// stands in for the real spawn, loaded just ahead of the shipped file, so what is
// seen is the call that would have been made.
const RECORD_SPAWN = path.join(tmp, 'record-spawn.js');
fs.writeFileSync(
    RECORD_SPAWN,
    "const cp = require('child_process');\n" +
        "cp.spawn = function () { console.log(JSON.stringify(Array.prototype.slice.call(arguments))); return 'child'; };\n",
);
const CODEX_PREFIX = path.join(tmp, 'usr');
const CODEX_COMMAND = `${CODEX_PREFIX}/libexec/tcbin/codex`;
const BUNDLED = `${EXTENSIONS}/openai.chatgpt-26.1021.22027-alpine-arm64/bin/linux-aarch64/codex`;
const SPAWN_MAIN = path.join(tmp, 'spawn-main.js');
fs.writeFileSync(
    SPAWN_MAIN,
    "const result = require('child_process').spawn(...JSON.parse(process.argv[2]));\n" +
        "if (result !== 'child') throw new Error('the return value was lost: ' + result);\n",
);
function spawnedWith(call, { command = true, prefix = CODEX_PREFIX } = {}) {
    fs.rmSync(CODEX_COMMAND, { force: true });
    if (command) {
        fs.mkdirSync(path.dirname(CODEX_COMMAND), { recursive: true });
        fs.writeFileSync(CODEX_COMMAND, '');
    }
    const env = { ...process.env };
    if (prefix === null) delete env.PREFIX; else env.PREFIX = prefix;
    const out = execFileSync(
        process.execPath,
        ['--require', FAKE_ANDROID, '--require', RECORD_SPAWN, '--require', FIX, SPAWN_MAIN, JSON.stringify(call)],
        { env, encoding: 'utf8' },
    );
    return JSON.parse(out.trim().split('\n').pop());
}
const ARGS = ['-c', 'features.code_mode_host=true', 'app-server'];
const OPTIONS = { stdio: ['pipe', 'pipe', 'pipe'] };

assert.deepStrictEqual(
    spawnedWith([BUNDLED, ARGS, OPTIONS]), [CODEX_COMMAND, ARGS, OPTIONS],
    'the extension\'s engine was not redirected, or its arguments and options changed on the way',
);
assert.deepStrictEqual(
    spawnedWith([BUNDLED, OPTIONS]), [CODEX_COMMAND, OPTIONS],
    'a call without an argument list lost its options',
);
assert.deepStrictEqual(
    spawnedWith([BUNDLED, ARGS, OPTIONS], { command: false }), [BUNDLED, ARGS, OPTIONS],
    'the spawn was redirected to a command that is not installed',
);
assert.deepStrictEqual(
    spawnedWith([BUNDLED, ARGS, OPTIONS], { prefix: null }), [BUNDLED, ARGS, OPTIONS],
    'the spawn was redirected with no PREFIX to say where the command is',
);
checked += 4;
for (const [label, file] of [
    ['the helper beside the engine', `${BUNDLED}-code-mode-host`],
    ['the extension\'s ripgrep', BUNDLED.replace(/codex$/, 'rg')],
    ['another publisher\'s binary of the same name', `${EXTENSIONS}/acme.assistant-1.0.0-alpine-arm64/bin/linux-aarch64/codex`],
    ['a codex outside any extension', `${tmp}/home/bin/codex`],
    ['the command itself', CODEX_COMMAND],
    ['a bare name', 'codex'],
]) {
    assert.deepStrictEqual(spawnedWith([file, ARGS, OPTIONS]), [file, ARGS, OPTIONS], `${label}: the spawn was redirected`);
    checked++;
}

// The extension host is a worker, and the preload reaches it through execArgv.
const spawnWorker = path.join(tmp, 'spawn-worker.js');
fs.mkdirSync(path.dirname(CODEX_COMMAND), { recursive: true });
fs.writeFileSync(CODEX_COMMAND, '');
fs.writeFileSync(spawnWorker, `
const { Worker } = require('worker_threads');
const env = { ...process.env, PREFIX: ${JSON.stringify(CODEX_PREFIX)} }; delete env.NODE_OPTIONS;
const w = new Worker(${JSON.stringify(`require('child_process').spawn(${JSON.stringify(BUNDLED)}, ['app-server']);`)},
    { eval: true, env, execArgv: ['--require', ${JSON.stringify(FAKE_ANDROID)}, '--require', ${JSON.stringify(RECORD_SPAWN)}, '--require', ${JSON.stringify(FIX)}] });
w.on('error', (e) => { console.error(e); process.exit(1); });
`);
const fromWorker = execFileSync(process.execPath, [spawnWorker], { encoding: 'utf8', env: { ...process.env, NODE_OPTIONS: '' } });
assert.deepStrictEqual(
    JSON.parse(fromWorker.trim().split('\n').pop()), [CODEX_COMMAND, ['app-server']],
    'a worker with the preload in execArgv did not redirect the engine',
);
checked++;

// An extension that downloads a program built for Android into app storage
// (Google Antigravity's engine) starts it with spawn, and the kernel refuses to
// execute anything under the data directory. The system linker can run the
// file, so that call becomes `linker64 <file> <args>`, but only for an ARM64 ELF
// that asks for that linker and whose real path is inside the data directory.
const BIONIC_LINKER = '/system/bin/linker64';

/** A minimal ELF64 little-endian header with one program header: PT_INTERP naming `interp`, or PT_LOAD when null. */
function elf64(interp) {
    const text = interp === null ? Buffer.alloc(0) : Buffer.from(`${interp}\0`, 'latin1');
    const buf = Buffer.alloc(120 + text.length);
    buf.writeUInt32BE(0x7f454c46, 0);
    buf[4] = 2; // 64-bit
    buf[5] = 1; // little endian
    buf[6] = 1;
    buf.writeUInt16LE(3, 16); // ET_DYN
    buf.writeUInt16LE(183, 18); // EM_AARCH64
    buf.writeUInt32LE(1, 20);
    buf.writeBigUInt64LE(64n, 32); // e_phoff
    buf.writeUInt16LE(64, 52); // e_ehsize
    buf.writeUInt16LE(56, 54); // e_phentsize
    buf.writeUInt16LE(1, 56); // e_phnum
    buf.writeUInt32LE(interp === null ? 1 : 3, 64); // p_type
    buf.writeUInt32LE(4, 68);
    buf.writeBigUInt64LE(120n, 72); // p_offset
    buf.writeBigUInt64LE(BigInt(text.length), 96); // p_filesz
    text.copy(buf, 120);
    return buf;
}

// The data directory is given through a symlinked spelling on purpose: the
// real path is what is compared, which is also how /data/data and /data/user/0
// stay one place.
const REAL_DATA = path.join(tmp, 'app-data');
const DATA_LINK = path.join(tmp, 'app-data-link');
fs.mkdirSync(path.join(REAL_DATA, 'files/home/.gemini/bin'), { recursive: true });
fs.mkdirSync(path.join(REAL_DATA, 'files/usr/bin'), { recursive: true });
fs.mkdirSync(path.join(tmp, 'native'), { recursive: true });
fs.mkdirSync(path.join(tmp, 'outside'), { recursive: true });
fs.symlinkSync(REAL_DATA, DATA_LINK);
const put = (file, content) => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, content); return file; };
const ENGINE = put(path.join(REAL_DATA, 'files/home/.gemini/bin/agy'), elf64(BIONIC_LINKER));
const MUSL = put(path.join(REAL_DATA, 'files/home/musl-tool'), elf64('/lib/ld-musl-aarch64.so.1'));
const STATIC = put(path.join(REAL_DATA, 'files/home/static-tool'), elf64(null));
const SCRIPT = put(path.join(REAL_DATA, 'files/home/script.sh'), '#!/bin/sh\nexit 0\n');
const NATIVE = put(path.join(tmp, 'native/libtool.so'), elf64(BIONIC_LINKER));
const OUTSIDE = put(path.join(tmp, 'outside/agy'), elf64(BIONIC_LINKER));
const LINK_TO_NATIVE = path.join(REAL_DATA, 'files/usr/bin/tool');
fs.symlinkSync(NATIVE, LINK_TO_NATIVE);
const LINK_INTO_DATA = path.join(tmp, 'outside/engine-link');
fs.symlinkSync(ENGINE, LINK_INTO_DATA);
// A plain open of a FIFO waits for a writer, which would freeze the extension host.
const FIFO = path.join(REAL_DATA, 'files/home/fifo');
execFileSync('mkfifo', [FIFO]);

// LD_PRELOAD is set from inside the process, not in its environment, so the
// dynamic loader does not complain about a library that is not there.
const SET_PRELOAD = path.join(tmp, 'set-preload.js');
fs.writeFileSync(SET_PRELOAD, "if (process.env.FAKE_LD_PRELOAD) process.env.LD_PRELOAD = process.env.FAKE_LD_PRELOAD;\n");

function spawnedUnder(call, { preload = null, dataDir = DATA_LINK, record = RECORD_SPAWN, main = SPAWN_MAIN } = {}) {
    const env = { ...process.env };
    delete env.LD_PRELOAD;
    if (preload !== null) env.FAKE_LD_PRELOAD = preload;
    if (dataDir === null) delete env.TERMUX_APP__DATA_DIR; else env.TERMUX_APP__DATA_DIR = dataDir;
    const out = execFileSync(
        process.execPath,
        ['--require', FAKE_ANDROID, '--require', SET_PRELOAD, '--require', record, '--require', FIX, main, JSON.stringify(call)],
        { env, encoding: 'utf8', timeout: 10000 },
    );
    return JSON.parse(out.trim().split('\n').pop());
}
const TOOL_ARGS = ['--flag', 'value'];
const SPAWN_OPTIONS = { cwd: tmp, stdio: ['ignore', 'pipe', 'pipe'] };

assert.deepStrictEqual(
    spawnedUnder([ENGINE, TOOL_ARGS, SPAWN_OPTIONS]), [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], SPAWN_OPTIONS],
    'a downloaded Android executable was not started through the linker, or its arguments and options changed',
);
assert.deepStrictEqual(
    spawnedUnder([ENGINE, SPAWN_OPTIONS]), [BIONIC_LINKER, [ENGINE], SPAWN_OPTIONS],
    'a call without an argument list lost its options',
);
assert.deepStrictEqual(spawnedUnder([ENGINE, TOOL_ARGS]), [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS]], 'a call without options gained one');
assert.deepStrictEqual(spawnedUnder([ENGINE]), [BIONIC_LINKER, [ENGINE]], 'a bare call was not started through the linker');
// The real path decides: a link outside the data directory that ends inside it.
assert.deepStrictEqual(
    spawnedUnder([LINK_INTO_DATA, TOOL_ARGS, SPAWN_OPTIONS]), [BIONIC_LINKER, [LINK_INTO_DATA, ...TOOL_ARGS], SPAWN_OPTIONS],
    'a link that resolves into the data directory was left alone',
);
checked += 5;
for (const [label, call, conditions] of [
    ['a musl executable, which needs its own loader', [MUSL, TOOL_ARGS, SPAWN_OPTIONS]],
    ['a static or shared object with no interpreter entry', [STATIC, TOOL_ARGS, SPAWN_OPTIONS]],
    ['a script', [SCRIPT, TOOL_ARGS, SPAWN_OPTIONS]],
    ['an Android executable outside the data directory', [OUTSIDE, TOOL_ARGS, SPAWN_OPTIONS]],
    ['a usr/bin link that ends in the native library directory', [LINK_TO_NATIVE, TOOL_ARGS, SPAWN_OPTIONS]],
    ['a call that asks for a shell', [ENGINE, TOOL_ARGS, { ...SPAWN_OPTIONS, shell: true }]],
    ['a bare command name', ['agy', TOOL_ARGS, SPAWN_OPTIONS]],
    ['a missing file', [path.join(REAL_DATA, 'files/home/nothing'), TOOL_ARGS, SPAWN_OPTIONS]],
    ['a directory', [path.join(REAL_DATA, 'files/home'), TOOL_ARGS, SPAWN_OPTIONS]],
    ['a FIFO, which a plain open waits on', [FIFO, TOOL_ARGS, SPAWN_OPTIONS]],
    ['a process that has the terminal interceptor', [ENGINE, TOOL_ARGS, SPAWN_OPTIONS], { preload: '/data/user/0/com.vscodroid/files/usr/lib/libtermux-exec.so' }],
    ['no data directory in the environment', [ENGINE, TOOL_ARGS, SPAWN_OPTIONS], { dataDir: null }],
]) {
    assert.deepStrictEqual(spawnedUnder(call, conditions), call, `${label}: the spawn was changed`);
    checked++;
}

// The same extension runs its engine once with execFile (`agy --version`) to check
// what it downloaded, and downloads the engine again when that fails. execFile
// reaches the internal spawn, not the exported one, so it is wrapped on its own.
// util.promisify(execFile) resolves { stdout, stderr } through a property of the
// original function, which the wrapper has to carry over.
const RECORD_EXEC = path.join(tmp, 'record-exec.js');
fs.writeFileSync(
    RECORD_EXEC,
    "const cp = require('child_process');\n" +
        "const util = require('util');\n" +
        "const note = (how) => function () { console.log(JSON.stringify([how].concat(Array.prototype.slice.call(arguments)))); return how === 'plain' ? 'child' : 'promise'; };\n" +
        "cp.execFile = note('plain');\n" +
        "cp.execFile[util.promisify.custom] = note('promisified');\n",
);
const EXEC_MAIN = path.join(tmp, 'exec-main.js');
fs.writeFileSync(
    EXEC_MAIN,
    "const cp = require('child_process');\n" +
        "const util = require('util');\n" +
        "const [how, call] = JSON.parse(process.argv[2]);\n" +
        "const args = call.map((x) => (x === '@callback' ? function () {} : x));\n" +
        "const result = how === 'promisified' ? util.promisify(cp.execFile)(...args) : cp.execFile(...args);\n" +
        "if (result !== 'child' && result !== 'promise') throw new Error('the return value was lost: ' + result);\n",
);
const execUnder = (how, call) => spawnedUnder([how, call], { record: RECORD_EXEC, main: EXEC_MAIN });
const CALLBACK = '@callback';
const VERSION_OPTIONS = { timeout: 5000 };
for (const [label, how, call, expected] of [
    ['arguments, options and a callback', 'plain', [ENGINE, ['--version'], VERSION_OPTIONS, CALLBACK], [BIONIC_LINKER, [ENGINE, '--version'], VERSION_OPTIONS, null]],
    ['arguments and a callback', 'plain', [ENGINE, ['--version'], CALLBACK], [BIONIC_LINKER, [ENGINE, '--version'], null]],
    ['a callback only', 'plain', [ENGINE, CALLBACK], [BIONIC_LINKER, [ENGINE], null]],
    ['options and a callback, no argument list', 'plain', [ENGINE, VERSION_OPTIONS, CALLBACK], [BIONIC_LINKER, [ENGINE], VERSION_OPTIONS, null]],
    ['an empty argument list with options', 'plain', [ENGINE, [], VERSION_OPTIONS, CALLBACK], [BIONIC_LINKER, [ENGINE], VERSION_OPTIONS, null]],
    ['no arguments at all', 'plain', [ENGINE], [BIONIC_LINKER, [ENGINE]]],
    ['the promisified form, which the extension uses', 'promisified', [ENGINE, ['--version'], VERSION_OPTIONS], [BIONIC_LINKER, [ENGINE, '--version'], VERSION_OPTIONS]],
]) {
    assert.deepStrictEqual(execUnder(how, call), [how, ...expected], `execFile, ${label}: not started through the linker, or its arguments changed`);
    checked++;
}
for (const [label, how, call] of [
    ['a musl executable', 'plain', [MUSL, ['--version'], VERSION_OPTIONS, CALLBACK]],
    ['a call that asks for a shell', 'plain', [ENGINE, ['--version'], { shell: true }, CALLBACK]],
    ['an executable outside the data directory', 'promisified', [OUTSIDE, ['--version'], VERSION_OPTIONS]],
]) {
    assert.deepStrictEqual(execUnder(how, call), [how, ...call.map((x) => (x === CALLBACK ? null : x))], `execFile, ${label}: the call was changed`);
    checked++;
}
// With nothing to rewrite, the real promisified call still resolves { stdout, stderr }.
const REAL_EXEC_MAIN = path.join(tmp, 'real-exec-main.js');
fs.writeFileSync(
    REAL_EXEC_MAIN,
    "const cp = require('child_process');\n" +
        "require('util').promisify(cp.execFile)(process.execPath, ['-e', \"console.log('out'); console.error('err')\"])\n" +
        '    .then((result) => console.log(JSON.stringify(result)));\n',
);
const realExec = execFileSync(
    process.execPath,
    ['--require', FAKE_ANDROID, '--require', FIX, REAL_EXEC_MAIN],
    { encoding: 'utf8', env: { ...process.env, TERMUX_APP__DATA_DIR: '' }, timeout: 10000 },
);
assert.deepStrictEqual(JSON.parse(realExec.trim().split('\n').pop()), { stdout: 'out\n', stderr: 'err\n' }, 'the promisified execFile no longer resolves { stdout, stderr }');
checked++;

// The extension host is a worker, and the preload reaches it through execArgv.
const bionicWorker = path.join(tmp, 'bionic-worker.js');
fs.writeFileSync(bionicWorker, `
const { Worker } = require('worker_threads');
const env = { ...process.env, TERMUX_APP__DATA_DIR: ${JSON.stringify(DATA_LINK)} }; delete env.NODE_OPTIONS; delete env.LD_PRELOAD;
const w = new Worker(${JSON.stringify(`require('child_process').spawn(${JSON.stringify(ENGINE)}, ['--hub']);`)},
    { eval: true, env, execArgv: ['--require', ${JSON.stringify(FAKE_ANDROID)}, '--require', ${JSON.stringify(RECORD_SPAWN)}, '--require', ${JSON.stringify(FIX)}] });
w.on('error', (e) => { console.error(e); process.exit(1); });
`);
const fromBionicWorker = execFileSync(process.execPath, [bionicWorker], { encoding: 'utf8', env: { ...process.env, NODE_OPTIONS: '' } });
assert.deepStrictEqual(
    JSON.parse(fromBionicWorker.trim().split('\n').pop()), [BIONIC_LINKER, [ENGINE, '--hub']],
    'a worker with the preload in execArgv did not start the executable through the linker',
);
checked++;

// The Antigravity engine's page refuses to be framed by an extension webview
// here, whose origin is https://<id>.vscode-cdn.net and not the `vscode-webview:`
// the page asks for. The extension starts `agy --hub --hub-port=<n>`, polls that
// port and frames it, so the engine is started on a port of its own while this
// process answers on <n> and widens `frame-ancestors`, only where the policy
// already names `vscode-webview:`. The driver below plays the extension and a
// stand-in engine, against the shipped file with a recorder for the spawn.
const RECORD_HUB = path.join(tmp, 'record-hub.js');
fs.writeFileSync(
    RECORD_HUB,
    "const cp = require('child_process');\nconst EventEmitter = require('events');\n" +
        "cp.spawn = function () { globalThis.__spawned = Array.prototype.slice.call(arguments); const c = new EventEmitter(); c.pid = 4242; return c; };\n",
);
const HUB_DRIVER = path.join(tmp, 'hub-driver.js');
fs.writeFileSync(HUB_DRIVER, `
const http = require('http');
const net = require('net');
const free = () => new Promise((resolve) => { const s = net.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); }); });
const get = (port, path) => new Promise((resolve, reject) => {
    const req = http.get({ host: '127.0.0.1', port, path, agent: false, timeout: 3000 }, (res) => {
        let body = ''; res.on('data', (c) => { body += c; });
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }));
    });
    req.on('timeout', () => req.destroy(new Error('no answer from ' + path)));
    req.on('error', reject);
});
setTimeout(() => { console.error('the driver gave up waiting'); process.exit(1); }, 20000).unref();
(async () => {
    const front = await free();
    const child = require('child_process').spawn(process.argv[2], ['--hub', '--hub-port=' + front, '--app_data_dir=antigravity']);
    const spawned = globalThis.__spawned;
    const back = Number(spawned[1].find((a) => a.startsWith('--hub-port=')).split('=')[1]);
    const noteFile = process.env.HOME + '/.vscodroid/antigravity-hub.json';
    const out = { front, back, spawned, noteAfterSpawn: JSON.parse(require('fs').readFileSync(noteFile, 'utf8')) };
    await new Promise((r) => setTimeout(r, 150));
    out.beforeEngine = (await get(front, '/healthz')).status;
    const seen = {};
    const engine = http.createServer((req, res) => {
        seen.host = req.headers.host;
        res.setHeader('Content-Security-Policy', req.url === '/plain'
            ? "frame-ancestors 'self'; report-uri https://example.test/r"
            : "frame-ancestors 'self' http://127.0.0.1:* vscode-webview: vscode-file:; report-uri https://example.test/r");
        res.setHeader('Set-Cookie', ['a=1', 'b=2']);
        res.end('hello ' + req.url);
    });
    engine.on('upgrade', (req, socket) => {
        socket.write('HTTP/1.1 101 Switching Protocols\\r\\nUpgrade: websocket\\r\\nConnection: Upgrade\\r\\n\\r\\n');
        socket.on('data', (d) => socket.write(d));
    });
    await new Promise((r) => engine.listen(back, '127.0.0.1', r));
    const page = await get(front, '/page?x=1');
    out.page = { status: page.status, csp: page.headers['content-security-policy'], cookies: page.headers['set-cookie'], body: page.body };
    out.engineSawHost = seen.host;
    out.plainCsp = (await get(front, '/plain')).headers['content-security-policy'];
    out.echo = await new Promise((resolve, reject) => {
        const socket = net.connect(front, '127.0.0.1', () => socket.write('GET /ws HTTP/1.1\\r\\nHost: 127.0.0.1:' + front + '\\r\\nConnection: Upgrade\\r\\nUpgrade: websocket\\r\\n\\r\\n'));
        let data = '';
        socket.on('data', (d) => {
            data += d;
            if (data.includes('\\r\\n\\r\\n') && !data.includes('PING')) socket.write('PING');
            if (data.endsWith('PING')) { socket.destroy(); resolve(data); }
        });
        socket.setTimeout(3000, () => { socket.destroy(); reject(new Error('the tunnel did not answer')); });
        socket.on('close', () => reject(new Error('the tunnel closed before it echoed')));
        socket.on('error', reject);
    });
    child.emit('exit', 0);
    await new Promise((r) => setTimeout(r, 150));
    out.afterExit = await get(front, '/healthz').then(() => 'still answering', (e) => e.code);
    out.noteAfterExit = require('fs').existsSync(noteFile);
    console.log(JSON.stringify(out));
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
`);
// The shim keeps a note of the engine it started under $HOME/.vscodroid, so the
// runs get a home of their own.
const HUB_HOME = path.join(tmp, 'hub-home');
fs.mkdirSync(path.join(HUB_HOME, '.vscodroid'), { recursive: true });
const HUB_NOTE = path.join(HUB_HOME, '.vscodroid/antigravity-hub.json');
const hubRun = JSON.parse(execFileSync(
    process.execPath,
    ['--require', FAKE_ANDROID, '--require', RECORD_HUB, '--require', FIX, HUB_DRIVER, ENGINE],
    { env: { ...process.env, HOME: HUB_HOME, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 60000 },
).trim().split('\n').pop());
assert.deepStrictEqual(hubRun.noteAfterSpawn, { pid: 4242, port: hubRun.back }, 'the note does not name the engine that was started and the port it was given');
assert.strictEqual(hubRun.noteAfterExit, false, 'the note outlived the engine it names');
assert.strictEqual(hubRun.spawned[0], BIONIC_LINKER, 'the engine was not started through the linker');
assert.strictEqual(hubRun.spawned[1][0], ENGINE);
assert.notStrictEqual(hubRun.back, hubRun.front, 'the engine was given the port the extension chose, so the relay has nothing to stand in front of');
assert.deepStrictEqual(
    hubRun.spawned[1].slice(1), ['--hub', `--hub-port=${hubRun.back}`, '--app_data_dir=antigravity'],
    'the engine\'s other arguments changed, or its port was not swapped',
);
assert.strictEqual(hubRun.beforeEngine, 502, 'the extension\'s probe needs a not-ready answer while the engine is not accepting');
assert.strictEqual(hubRun.page.status, 200);
assert.strictEqual(hubRun.page.body, 'hello /page?x=1', 'the body did not pass through untouched');
assert.deepStrictEqual(hubRun.page.cookies, ['a=1', 'b=2'], 'repeated headers were merged or dropped');
assert.strictEqual(
    hubRun.page.csp,
    "frame-ancestors 'self' http://127.0.0.1:* vscode-webview: vscode-file: https://*.vscode-cdn.net; report-uri https://example.test/r",
    'the policy that names vscode-webview: was not widened to this app\'s webview origin, or something else in it changed',
);
assert.strictEqual(hubRun.plainCsp, "frame-ancestors 'self'; report-uri https://example.test/r", 'a policy that does not ask for VS Code webviews was widened');
assert.strictEqual(hubRun.engineSawHost, `127.0.0.1:${hubRun.back}`, 'the engine saw a Host that is not its own');
assert.ok(/^HTTP\/1\.1 101 /.test(hubRun.echo) && hubRun.echo.endsWith('PING'), 'a WebSocket upgrade did not tunnel through');
assert.strictEqual(hubRun.afterExit, 'ECONNREFUSED', 'the relay kept answering after the engine ended');
checked++;

// When the system kills the editor server the engine outlives it, so the next
// start ends the engine named in the note, but only while that pid still runs the
// engine on that port. The check reads /proc, which only Linux has; the driver
// plays the extension against a stand-in process.
if (fs.existsSync('/proc/self/cmdline')) {
    const REAP_DRIVER = path.join(tmp, 'reap-driver.js');
    fs.writeFileSync(REAP_DRIVER, `
const cp = require('child_process');
const fs = require('fs');
const [engine, mode, note] = process.argv.slice(2);
let pid;
let dummy = null;
if (mode === 'dead') {
    pid = 2147483000;
} else {
    const words = mode === 'wrong-port' ? ['/x/.gemini/bin/agy', '--hub', '--hub-port=41998']
        : mode === 'not-the-engine' ? ['/x/other', '--hub', '--hub-port=41999']
        : ['/x/.gemini/bin/agy', '--hub', '--hub-port=41999'];
    dummy = cp.spawn(process.execPath, ['-e', 'setInterval(function () {}, 1000)'].concat(words), { stdio: 'ignore' });
    pid = dummy.pid;
}
fs.writeFileSync(note, JSON.stringify({ pid: pid, port: 41999 }));
const started = cp.spawn(engine, ['--hub', '--hub-port=41500']);
started.on('error', () => {});
const done = (result) => { console.log(JSON.stringify({ result: result })); if (dummy) dummy.kill('SIGKILL'); process.exit(0); };
if (dummy) {
    dummy.on('exit', (code, signal) => done('ended by ' + signal));
    setTimeout(() => done('still running'), 3000);
} else {
    setTimeout(() => done('no crash'), 300);
}
`);
    for (const [mode, want] of [
        ['match', 'ended by SIGTERM'],
        ['wrong-port', 'still running'],
        ['not-the-engine', 'still running'],
        ['dead', 'no crash'],
    ]) {
        fs.rmSync(HUB_NOTE, { force: true });
        const got = JSON.parse(execFileSync(
            process.execPath,
            ['--require', FAKE_ANDROID, '--require', FIX, REAP_DRIVER, ENGINE, mode, HUB_NOTE],
            { env: { ...process.env, HOME: HUB_HOME, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 30000 },
        ).trim().split('\n').pop());
        assert.strictEqual(got.result, want, `an earlier engine (${mode}): ${got.result}, wanted ${want}`);
        checked++;
    }

    // The editor server's own start does the same, because the extension host
    // may not start for a long time after the app restarted the server. Only its
    // main thread: a worker whose script has the same name, and any other
    // program, must leave the engine alone (the terminal host is a worker that
    // starts later, with an engine already running).
    const START_DRIVER = path.join(tmp, 'start-driver.js');
    fs.writeFileSync(START_DRIVER, `
const cp = require('child_process');
const fs = require('fs');
const path = require('path');
const [mode, note, dir, fake, fix] = process.argv.slice(2);
const dummy = cp.spawn(process.execPath, ['-e', 'setInterval(function () {}, 1000)', '/x/.gemini/bin/agy', '--hub', '--hub-port=41999'], { stdio: 'ignore' });
fs.writeFileSync(note, JSON.stringify({ pid: dummy.pid, port: 41999 }));
const idle = 'setTimeout(function () {}, 2500);';
const named = path.join(dir, 'server-main.js');
const other = path.join(dir, 'other.js');
let args;
if (mode === 'worker-start') {
    fs.writeFileSync(named, idle);
    fs.writeFileSync(other, "const { Worker } = require('worker_threads'); new Worker(" + JSON.stringify(named) + ", { execArgv: ['--require', " + JSON.stringify(fake) + ", '--require', " + JSON.stringify(fix) + "] });");
    args = [other];
} else {
    const main = mode === 'other-main' ? other : named;
    fs.writeFileSync(main, idle);
    args = ['--require', fake, '--require', fix, main];
}
const started = cp.spawn(process.execPath, args, { stdio: 'ignore' });
const done = (result) => { console.log(JSON.stringify({ result: result })); dummy.kill('SIGKILL'); started.kill('SIGKILL'); process.exit(0); };
dummy.on('exit', (code, signal) => done('ended by ' + signal));
setTimeout(() => done('still running'), 4000);
`);
    for (const [mode, want] of [
        ['server-start', 'ended by SIGTERM'],
        ['worker-start', 'still running'],
        ['other-main', 'still running'],
    ]) {
        fs.rmSync(HUB_NOTE, { force: true });
        const got = JSON.parse(execFileSync(
            process.execPath,
            [START_DRIVER, mode, HUB_NOTE, tmp, FAKE_ANDROID, FIX],
            { env: { ...process.env, HOME: HUB_HOME, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 30000 },
        ).trim().split('\n').pop());
        assert.strictEqual(got.result, want, `an earlier engine at start (${mode}): ${got.result}, wanted ${want}`);
        checked++;
    }
} else {
    console.log('skipped: ending an earlier engine reads /proc, which this system does not have');
}

// Only the engine's own launch is touched: another program with the same flags,
// and the engine without a hub port, keep their arguments.
const KEEP = [
    ['the engine without a port', ENGINE, ['--hub', '--app_data_dir=antigravity']],
    ['the engine with port 0', ENGINE, ['--hub', '--hub-port=0']],
    ['the engine without --hub', ENGINE, ['--hub-port=41000']],
    ['another program with the same flags', OUTSIDE.replace(/agy$/, 'other'), ['--hub', '--hub-port=41000']],
];
for (const [label, file, args] of KEEP) {
    if (!fs.existsSync(file)) put(file, elf64(BIONIC_LINKER));
    const inside = file.startsWith(REAL_DATA);
    const got = spawnedUnder([file, args, SPAWN_OPTIONS]);
    assert.deepStrictEqual(got[1], inside ? [file, ...args] : args, `${label}: the arguments changed`);
    checked++;
}

fs.rmSync(tmp, { recursive: true, force: true });
console.log(`ok -- ${checked} invocations checked, the override engaged for ${cases.filter((c) => c[4] === 'linux').length}`);
