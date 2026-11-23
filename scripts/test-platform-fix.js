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
const { execFileSync, spawnSync } = require('child_process');

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
        { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    ));
}
const EXTENSIONS = path.join(tmp, 'home/.vscodroid/extensions');
const osCases = [
    ['the Jupyter bundle', `${EXTENSIONS}/ms-toolsai.jupyter-2025.9.1/dist/extension.node.js`, 'linux'],
    // Its zeromq loader picks a native build by platform and must see android.
    ['zeromq inside the Jupyter extension', `${EXTENSIONS}/ms-toolsai.jupyter-2025.9.1/dist/node_modules/@aminya/node-gyp-build/index.js`, 'android'],
    ['the Python extension bundle', `${EXTENSIONS}/ms-python.python-2026.10.0-alpine-arm64/out/client/extension.js`, 'linux'],
    ['another file inside the Python extension', `${EXTENSIONS}/ms-python.python-2026.10.0-alpine-arm64/out/client/helper.js`, 'android'],
    ['another extension', `${EXTENSIONS}/esbenp.prettier-vscode-11.0.0/dist/extension.js`, 'android'],
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

// Open VSX auto-updates replace the bundled Python extension with an upstream
// copy whose platform detector maps only /^linux/ to OSType.Linux and leaves
// Android as OSType.Unknown. The preload rewrites that expression at compile
// time for the Python extension bundle alone, leaving process.platform and
// every other file untouched.
const PY_UNPATCHED = [
    "const OSType = { Windows: 'Windows', OSX: 'OSX', Linux: 'Linux', Unknown: 'Unknown' };",
    'function detect(e = process.platform) {',
    '    return /^win/.test(e)?OSType.Windows:/^darwin/.test(e)?OSType.OSX:/^linux/.test(e)?OSType.Linux:OSType.Unknown;',
    '}',
    "module.exports = { osType: detect(), os: require('os').platform(), real: process.platform };",
    '',
].join('\n');
const PY_ALREADY_PATCHED = PY_UNPATCHED.replace(
    '/^linux/.test(e)?OSType.Linux:OSType.Unknown',
    '/^linux|^android/.test(e)?OSType.Linux:OSType.Unknown',
);
const PY_DOLLAR_NS_SPACED = [
    "const n = { OSType: { Windows: 'Windows', OSX: 'OSX', Linux: 'Linux', Unknown: 'Unknown' } };",
    'function detect($e = process.platform) {',
    '    return /^win/.test($e) ? n.OSType.Windows : /^darwin/.test($e) ? n.OSType.OSX : /^linux/.test($e) ? n.OSType.Linux : n.OSType.Unknown;',
    '}',
    "module.exports = { osType: detect(), os: require('os').platform(), real: process.platform };",
    '',
].join('\n');
const PY_DOTTED_TEST = "const i = { Linux: 'Linux', Unknown: 'Unknown' }; module.exports = { osType: /^linux/.test(process.platform)?i.Linux:i.Unknown, os: require('os').platform(), real: process.platform };\n";
const PY_UNMATCHED = "module.exports = { osType: 'Unknown', os: require('os').platform(), real: process.platform };\n";
function compiledAs(modulePath, source) {
    fs.mkdirSync(path.dirname(modulePath), { recursive: true });
    fs.writeFileSync(modulePath, source);
    const main = path.join(tmp, 'load-python-ext.js');
    fs.writeFileSync(main, `console.log(JSON.stringify(require(${JSON.stringify(modulePath)})));\n`);
    const res = spawnSync(
        process.execPath,
        ['--require', FAKE_ANDROID, '--require', FIX, main],
        { encoding: 'utf8' },
    );
    assert.strictEqual(res.status, 0, `compiledAs failed: ${res.stderr}`);
    return { ...JSON.parse(res.stdout), stderr: res.stderr };
}
for (const [label, modulePath, source, wantOsType, wantOs, wantWarn] of [
    ['unpatched updated Python extension bundle', `${EXTENSIONS}/ms-python.python-2026.10.0-alpine-arm64/out/client/extension.js`, PY_UNPATCHED, 'Linux', 'linux', false],
    ['$-identifier, namespaced enum, and spaced ternary in Python bundle', `${EXTENSIONS}/ms-python.python-2026.11.0-alpine-arm64/out/client/extension.js`, PY_DOLLAR_NS_SPACED, 'Linux', 'linux', false],
    ['inlined process.platform dotted test argument in Python bundle', `${EXTENSIONS}/ms-python.python-2026.13.0-alpine-arm64/out/client/extension.js`, PY_DOTTED_TEST, 'Linux', 'linux', false],
    ['already-patched bundled Python extension', `${EXTENSIONS}/ms-python.python-2026.1.0/out/client/extension.js`, PY_ALREADY_PATCHED, 'Linux', 'linux', false],
    ['unmatched Python extension bundle shape logs a warning', `${EXTENSIONS}/ms-python.python-2026.12.0-alpine-arm64/out/client/extension.js`, PY_UNMATCHED, 'Unknown', 'linux', true],
    ['another extension with the same platform snippet', `${EXTENSIONS}/esbenp.prettier-vscode-11.0.0/out/client/extension.js`, PY_UNPATCHED, 'Unknown', 'android', false],
    ['another file inside the Python extension', `${EXTENSIONS}/ms-python.python-2026.10.0-alpine-arm64/out/client/other.js`, PY_UNPATCHED, 'Unknown', 'android', false],
    ['a user script with the same relative path outside extensions', `${tmp}/home/out/client/extension.js`, PY_UNPATCHED, 'Unknown', 'android', false],
]) {
    const got = compiledAs(modulePath, source);
    assert.strictEqual(got.osType, wantOsType, `${label}: detected OSType came out ${got.osType}, wanted ${wantOsType}`);
    assert.strictEqual(got.os, wantOs, `${label}: os.platform() came out ${got.os}, wanted ${wantOs}`);
    assert.strictEqual(got.real, 'android', `${label}: process.platform changed to ${got.real}`);
    if (wantWarn) {
        assert.ok(
            got.stderr.includes(modulePath),
            `${label}: expected stderr warning naming ${modulePath}, got ${JSON.stringify(got.stderr)}`,
        );
    } else {
        assert.strictEqual(got.stderr, '', `${label}: unexpected stderr output: ${JSON.stringify(got.stderr)}`);
    }
    checked++;
}

// The extension host is a worker started with the server's execArgv and an
// environment VS Code has stripped of NODE_OPTIONS, which is why server.js
// passes the preload there. A worker given it in execArgv has the hook.
const workerMain = path.join(tmp, 'worker-host.js');
const workerExt = `${EXTENSIONS}/ms-toolsai.jupyter-2025.9.1/dist/extension.node.js`;
const workerPyExt = `${EXTENSIONS}/ms-python.python-2026.10.0-alpine-arm64/out/client/extension.js`;
fs.writeFileSync(workerMain, `
const { Worker } = require('worker_threads');
const env = { ...process.env }; delete env.NODE_OPTIONS;
const w = new Worker(${JSON.stringify(`const { parentPort } = require('worker_threads'); parentPort.postMessage({ jupyter: require(${JSON.stringify(workerExt)}), python: require(${JSON.stringify(workerPyExt)}) });`)},
    { eval: true, env, execArgv: ['--require', ${JSON.stringify(FAKE_ANDROID)}, '--require', ${JSON.stringify(FIX)}] });
w.on('message', (m) => { console.log(JSON.stringify(m)); w.terminate(); });
w.on('error', (e) => { console.error(e); process.exit(1); });
`);
const inWorker = JSON.parse(execFileSync(process.execPath, [workerMain], { encoding: 'utf8', env: { ...process.env, NODE_OPTIONS: '' } }));
assert.strictEqual(inWorker.jupyter.os, 'linux', `a worker with the preload in execArgv: Jupyter os.platform() came out ${inWorker.jupyter.os}`);
assert.strictEqual(inWorker.python.osType, 'Linux', `a worker with the preload in execArgv: Python OSType came out ${inWorker.python.osType}`);
assert.strictEqual(inWorker.python.real, 'android', `a worker with the preload in execArgv: process.platform came out ${inWorker.python.real}`);
checked += 2;

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
    "const result = require('child_process').spawn(...JSON.parse(process.argv[2]).map((x) => (x === '@undefined' ? undefined : x)));\n" +
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
// An interpreter entry of 5000 bytes that begins with the linker's name: read whole it would pass for the
// linker, which is why the parser refuses an entry past 4096 bytes instead of reading it.
const OVERSIZED = put(path.join(REAL_DATA, 'files/home/oversized-tool'), elf64(BIONIC_LINKER + '\0'.repeat(5000 - BIONIC_LINKER.length)));
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

// The home these runs get has no settings file, so the Antigravity engine's hub is turned off in
// them unless a case names a home that turns it on.
const NO_SETTINGS_HOME = path.join(tmp, 'no-settings-home');
fs.mkdirSync(NO_SETTINGS_HOME, { recursive: true });

// LD_PRELOAD is set from inside the process, not in its environment, so the
// dynamic loader does not complain about a library that is not there.
const SET_PRELOAD = path.join(tmp, 'set-preload.js');
fs.writeFileSync(SET_PRELOAD, "if (process.env.FAKE_LD_PRELOAD) process.env.LD_PRELOAD = process.env.FAKE_LD_PRELOAD;\n");

// The exec interceptor a terminal preloads, under the prefix as the app lays it out (filesDir/usr). A program
// the shim starts through the linker is given it as well, but only while it is a file: Bionic aborts every exec
// in an environment whose preload is missing. So the runs below get a prefix that
// holds it unless a case names one that does not.
const PREFIX = path.join(DATA_LINK, 'files/usr');
const PRELOAD = put(path.join(PREFIX, 'lib/libtermux-exec.so'), 'elf');
const BARE_PREFIX = path.join(tmp, 'bare-usr');
fs.mkdirSync(BARE_PREFIX, { recursive: true });

/** The environment a run's process is started with. */
// Only what the shim reads, and PATH, so a recorded environment is short enough to read when a case fails and
// carries nothing of the machine running this.
function childEnv({ preload = null, dataDir = DATA_LINK, home = NO_SETTINGS_HOME, prefix = PREFIX } = {}) {
    const env = { PATH: process.env.PATH, HOME: home };
    if (preload !== null) env.FAKE_LD_PRELOAD = preload;
    if (dataDir !== null) env.TERMUX_APP__DATA_DIR = dataDir;
    if (prefix !== null) env.TERMUX__PREFIX = prefix;
    return env;
}

// What the system adds to the environment a process is started with, which the process then sees as its own
// (macOS adds __CF_USER_TEXT_ENCODING).
const ADDED_BY_SYSTEM = (() => {
    const given = { PATH: process.env.PATH };
    const seen = JSON.parse(execFileSync(process.execPath, ['-e', 'console.log(JSON.stringify(process.env))'], { env: given, encoding: 'utf8' }));
    Object.keys(given).forEach((name) => { delete seen[name]; });
    return seen;
})();

/** The environment a run's process sees as its own: what it was started with, marked by the shim with the real platform. */
const seenEnv = (conditions = {}) => ({ ...childEnv(conditions), ...ADDED_BY_SYSTEM, VSCODROID_REAL_PLATFORM: 'android' });

/**
 * The environment a start through the linker hands its program, for a call that names none of its own: the
 * process's own, the interceptor preloaded, and the program's path where the interceptor and the programs that
 * locate themselves look for it, since the linker names itself.
 */
const routedEnv = (file, conditions = {}) => ({ ...seenEnv(conditions), LD_PRELOAD: PRELOAD, TERMUX_EXEC__PROC_SELF_EXE: file });

function spawnedUnder(call, { preload = null, dataDir = DATA_LINK, record = RECORD_SPAWN, main = SPAWN_MAIN, cwd = undefined, home = NO_SETTINGS_HOME, prefix = PREFIX } = {}) {
    const env = childEnv({ preload, dataDir, home, prefix });
    const out = execFileSync(
        process.execPath,
        ['--require', FAKE_ANDROID, '--require', SET_PRELOAD, '--require', record, '--require', FIX, main, JSON.stringify(call)],
        { env, encoding: 'utf8', timeout: 10000, cwd },
    );
    return JSON.parse(out.trim().split('\n').pop());
}
const TOOL_ARGS = ['--flag', 'value'];
const SPAWN_OPTIONS = { cwd: tmp, stdio: ['ignore', 'pipe', 'pipe'] };

// Started through the linker, the program also gets the interceptor and its own path in a copy of the
// environment: the options' own copy, or options of its own when the call had none.
const ROUTED = { ...SPAWN_OPTIONS, env: routedEnv(ENGINE) };
assert.deepStrictEqual(
    spawnedUnder([ENGINE, TOOL_ARGS, SPAWN_OPTIONS]), [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], ROUTED],
    'a downloaded Android executable was not started through the linker with the interceptor, or its arguments and options changed',
);
assert.deepStrictEqual(
    spawnedUnder([ENGINE, SPAWN_OPTIONS]), [BIONIC_LINKER, [ENGINE], ROUTED],
    'a call without an argument list lost its options',
);
assert.deepStrictEqual(spawnedUnder([ENGINE, TOOL_ARGS]), [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], { env: routedEnv(ENGINE) }], 'a call without options was given none to carry the interceptor');
assert.deepStrictEqual(spawnedUnder([ENGINE]), [BIONIC_LINKER, [ENGINE], { env: routedEnv(ENGINE) }], 'a bare call was not started through the linker with the interceptor');
// The real path decides: a link outside the data directory that ends inside it. Its own path is the one named.
assert.deepStrictEqual(
    spawnedUnder([LINK_INTO_DATA, TOOL_ARGS, SPAWN_OPTIONS]), [BIONIC_LINKER, [LINK_INTO_DATA, ...TOOL_ARGS], { ...SPAWN_OPTIONS, env: routedEnv(LINK_INTO_DATA) }],
    'a link that resolves into the data directory was left alone',
);
checked += 5;
// Node takes null or undefined for the argument list and reads the options after it. A call written that way
// has to keep its options (cwd, env, stdio), which a rewrite that took the empty slot for them dropped or threw on.
assert.deepStrictEqual(
    spawnedUnder([ENGINE, '@undefined', SPAWN_OPTIONS]), [BIONIC_LINKER, [ENGINE], ROUTED],
    'an undefined argument list lost the options that follow it',
);
assert.deepStrictEqual(
    spawnedUnder([ENGINE, null, SPAWN_OPTIONS]), [BIONIC_LINKER, [ENGINE], ROUTED],
    'a null argument list lost the options that follow it',
);
assert.deepStrictEqual(spawnedUnder([ENGINE, null]), [BIONIC_LINKER, [ENGINE], { env: routedEnv(ENGINE) }], 'a null argument list alone was not started through the linker');
checked += 3;
// argv0 would reach the linker as its own argv[0], which it does not read: the program it starts is given its path
// there either way. All it changed was the first word of the process in /proc, where the process monitor and the
// end of an earlier engine look for the linker, so it is left out of a start through the linker, and only of one.
{
    const NAMED = { ...SPAWN_OPTIONS, argv0: 'agy' };
    assert.deepStrictEqual(spawnedUnder([ENGINE, TOOL_ARGS, NAMED]), [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], ROUTED], 'argv0 reached a start through the linker');
    assert.deepStrictEqual(spawnedUnder([ENGINE, TOOL_ARGS, NAMED], { prefix: BARE_PREFIX }), [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], SPAWN_OPTIONS],
        'argv0 reached a start through the linker made without the interceptor');
    assert.deepStrictEqual(spawnedUnder([OUTSIDE, TOOL_ARGS, NAMED]), [OUTSIDE, TOOL_ARGS, NAMED], 'argv0 was taken from a start that is not made through the linker');
    checked += 3;
}
// Without the interceptor on disk nothing is added, the program's own path included: an environment that
// names a preload Bionic cannot map aborts every exec in it, and the path without the interceptor would reach
// every program below that one, which no exec would correct. The call is then made as it was before.
for (const prefix of [BARE_PREFIX, null]) {
    for (const [call, expected] of [
        [[ENGINE, TOOL_ARGS, SPAWN_OPTIONS], [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], SPAWN_OPTIONS]],
        [[ENGINE, TOOL_ARGS], [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS]]],
        [[ENGINE, null, SPAWN_OPTIONS], [BIONIC_LINKER, [ENGINE], SPAWN_OPTIONS]],
    ]) {
        assert.deepStrictEqual(spawnedUnder(call, { prefix }), expected, `${prefix === null ? 'no prefix' : 'no interceptor under the prefix'}: the call gained an environment`);
        checked++;
    }
}
// A directory where the interceptor should be is no more a library Bionic can map than a missing file is.
{
    const DIR_PREFIX = path.join(tmp, 'dir-usr');
    fs.mkdirSync(path.join(DIR_PREFIX, 'lib/libtermux-exec.so'), { recursive: true });
    assert.deepStrictEqual(spawnedUnder([ENGINE, TOOL_ARGS, SPAWN_OPTIONS], { prefix: DIR_PREFIX }), [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], SPAWN_OPTIONS],
        'a directory at the interceptor\'s path: the call gained an environment that names it as a preload');
    checked++;
}
// A call's own environment is copied and kept: the interceptor goes before any preload it names, the variables
// the interceptor reads come from this process when the call leaves them out, and a second pass of the same
// start (the linker already in front, the interceptor already in place) changes nothing.
{
    const own = { ...SPAWN_OPTIONS, env: { PATH: '/usr/bin', LD_PRELOAD: '/x/libother.so' } };
    assert.deepStrictEqual(
        spawnedUnder([ENGINE, TOOL_ARGS, own]),
        [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], { ...SPAWN_OPTIONS, env: {
            PATH: '/usr/bin', LD_PRELOAD: `${PRELOAD}:/x/libother.so`, TERMUX_APP__DATA_DIR: DATA_LINK, TERMUX__PREFIX: PREFIX, TERMUX_EXEC__PROC_SELF_EXE: ENGINE,
        } }],
        'a call with an environment of its own did not keep it, or did not get the interceptor in front of its preload',
    );
    const once = spawnedUnder([ENGINE, TOOL_ARGS, SPAWN_OPTIONS]);
    assert.deepStrictEqual(spawnedUnder(once), once, 'a start already made through the linker with the interceptor changed on a second pass');
    checked += 2;
}
// The caller's options and environment are its own, and the process's environment is the host's: none of them
// gains the interceptor, so nothing else the extension host starts carries it.
{
    const MUTATION_MAIN = path.join(tmp, 'mutation-main.js');
    fs.writeFileSync(MUTATION_MAIN,
        "const [file] = JSON.parse(process.argv[2]);\n" +
        "const env = { PATH: '/usr/bin' };\nconst options = { env };\nconst bare = {};\n" +
        "require('child_process').spawn(file, ['--flag'], options);\nrequire('child_process').spawn(file, ['--flag'], bare);\n" +
        "console.log(JSON.stringify([options, bare, process.env.LD_PRELOAD || null, process.env.TERMUX_EXEC__PROC_SELF_EXE || null]));\n");
    assert.deepStrictEqual(spawnedUnder([ENGINE], { main: MUTATION_MAIN }), [{ env: { PATH: '/usr/bin' } }, {}, null, null],
        'the caller\'s options, its environment or the process\'s own environment gained the interceptor');
    checked++;
}
// Node reads a call's environment with for...in, keys the object inherits included (an extension may pass
// Object.create(process.env) with its own changes), and the rest of the options as their own properties only:
// measured on Node 22 and 24, every start ignores an inherited cwd, stdio, env or shell. A start through the
// linker hands Node the same, the environment's inherited keys in its copy and the inherited options still out of
// reach, and the shim decides on what Node acts on: an inherited shell or env is not the call's. JSON cannot carry
// a prototype, so the main builds the call and the recorder logs what Node reads of it.
const RECORD_AS_NODE = path.join(tmp, 'record-as-node.js');
fs.writeFileSync(RECORD_AS_NODE,
    "const cp = require('child_process');\n" +
    "const read = (x) => { if (!x || typeof x !== 'object' || Array.isArray(x)) return x; const own = { ...x }; " +
    "if (own.env) { const env = {}; for (const k in own.env) env[k] = own.env[k]; own.env = env; } return own; };\n" +
    "['spawn', 'execSync', 'execFileSync'].forEach((how) => { cp[how] = function () { console.log(JSON.stringify([how].concat(Array.prototype.slice.call(arguments).map(read)))); return 'child'; }; });\n");
const INHERITED_MAIN = path.join(tmp, 'inherited-main.js');
fs.writeFileSync(INHERITED_MAIN,
    "const [file, inherits] = JSON.parse(process.argv[2]);\n" +
    "let options;\n" +
    "if (inherits === 'env keys') {\n" +
    "    const env = Object.create({ PATH: '/inherited/bin', FOO: 'bar' });\n    env.OWN = '1';\n" +
    "    options = Object.create({ cwd: '/inherited/cwd', stdio: 'inherit' });\n    options.env = env;\n" +
    "} else if (inherits === 'shell') {\n    options = Object.create({ shell: true });\n" +
    "} else if (inherits === 'cwd') {\n    require('child_process').execSync(file, Object.create({ cwd: '/' }));\n    process.exit(0);\n" +
    "} else {\n    options = Object.create({ env: { PATH: '/inherited/bin' } });\n}\n" +
    "const result = require('child_process').spawn(file, ['--flag'], options);\n" +
    "if (result !== 'child') throw new Error('the return value was lost: ' + result);\n");
const asNode = (file, inherits, conditions = {}) => spawnedUnder([file, inherits], { record: RECORD_AS_NODE, main: INHERITED_MAIN, ...conditions });
const inherited = { PATH: '/inherited/bin', FOO: 'bar', OWN: '1' };
assert.deepStrictEqual(asNode(OUTSIDE, 'env keys'), ['spawn', OUTSIDE, ['--flag'], { env: inherited }], 'a call that is not the linker\'s is not read the way Node reads it');
assert.deepStrictEqual(
    asNode(ENGINE, 'env keys'),
    ['spawn', BIONIC_LINKER, [ENGINE, '--flag'], { env: {
        ...inherited, LD_PRELOAD: PRELOAD, TERMUX_APP__DATA_DIR: DATA_LINK, TERMUX__PREFIX: PREFIX, TERMUX_EXEC__PROC_SELF_EXE: ENGINE,
    } }],
    'a start through the linker lost what its environment inherits, or handed Node an inherited option Node ignores',
);
for (const inherits of ['shell', 'env option']) {
    assert.deepStrictEqual(asNode(OUTSIDE, inherits), ['spawn', OUTSIDE, ['--flag'], {}], `an inherited ${inherits} is not ignored the way Node ignores it`);
    assert.deepStrictEqual(asNode(ENGINE, inherits), ['spawn', BIONIC_LINKER, [ENGINE, '--flag'], { env: routedEnv(ENGINE) }],
        `an inherited ${inherits}, which Node ignores, decided how the program was started`);
}
checked += 6;
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
    ['an interpreter entry past 4096 bytes', [OVERSIZED, TOOL_ARGS, SPAWN_OPTIONS]],
    ['a process that has the terminal interceptor', [ENGINE, TOOL_ARGS, SPAWN_OPTIONS], { preload: '/data/user/0/com.vscodroid/files/usr/lib/libtermux-exec.so' }],
    ['no data directory in the environment, from a directory that holds the engine', [ENGINE, TOOL_ARGS, SPAWN_OPTIONS], { dataDir: null, cwd: REAL_DATA }],
    ['an empty data directory in the environment, from a directory that holds the engine', [ENGINE, TOOL_ARGS, SPAWN_OPTIONS], { dataDir: '', cwd: REAL_DATA }],
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
        "const args = call.map((x) => (x === '@callback' ? function () {} : x === '@undefined' ? undefined : x));\n" +
        "const result = how === 'promisified' ? util.promisify(cp.execFile)(...args) : cp.execFile(...args);\n" +
        "if (result !== 'child' && result !== 'promise') throw new Error('the return value was lost: ' + result);\n",
);
const execUnder = (how, call) => spawnedUnder([how, call], { record: RECORD_EXEC, main: EXEC_MAIN });
const CALLBACK = '@callback';
const VERSION_OPTIONS = { timeout: 5000 };
// The interceptor goes with these starts too, in a copy of the options, or in options put before the callback
// when the call has none.
const VERSION_ROUTED = { ...VERSION_OPTIONS, env: routedEnv(ENGINE) };
const ENV_ONLY = { env: routedEnv(ENGINE) };
for (const [label, how, call, expected] of [
    ['arguments, options and a callback', 'plain', [ENGINE, ['--version'], VERSION_OPTIONS, CALLBACK], [BIONIC_LINKER, [ENGINE, '--version'], VERSION_ROUTED, null]],
    ['arguments and a callback', 'plain', [ENGINE, ['--version'], CALLBACK], [BIONIC_LINKER, [ENGINE, '--version'], ENV_ONLY, null]],
    ['a callback only', 'plain', [ENGINE, CALLBACK], [BIONIC_LINKER, [ENGINE], ENV_ONLY, null]],
    ['options and a callback, no argument list', 'plain', [ENGINE, VERSION_OPTIONS, CALLBACK], [BIONIC_LINKER, [ENGINE], VERSION_ROUTED, null]],
    ['an empty argument list with options', 'plain', [ENGINE, [], VERSION_OPTIONS, CALLBACK], [BIONIC_LINKER, [ENGINE], VERSION_ROUTED, null]],
    ['no arguments at all', 'plain', [ENGINE], [BIONIC_LINKER, [ENGINE], ENV_ONLY]],
    ['the promisified form, which the extension uses', 'promisified', [ENGINE, ['--version'], VERSION_OPTIONS], [BIONIC_LINKER, [ENGINE, '--version'], VERSION_ROUTED]],
    ['a null argument list before options and a callback', 'plain', [ENGINE, null, VERSION_OPTIONS, CALLBACK], [BIONIC_LINKER, [ENGINE], VERSION_ROUTED, null]],
    ['a null options slot before a callback', 'plain', [ENGINE, ['--version'], null, CALLBACK], [BIONIC_LINKER, [ENGINE, '--version'], ENV_ONLY, null]],
    ['an undefined argument list before a callback', 'plain', [ENGINE, '@undefined', CALLBACK], [BIONIC_LINKER, [ENGINE], ENV_ONLY, null]],
    ['an undefined argument list before options, promisified', 'promisified', [ENGINE, '@undefined', VERSION_OPTIONS], [BIONIC_LINKER, [ENGINE], VERSION_ROUTED]],
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
const w = new Worker(${JSON.stringify(`require('child_process').spawn(${JSON.stringify(ENGINE)}, ['--version']);`)},
    { eval: true, env, execArgv: ['--require', ${JSON.stringify(FAKE_ANDROID)}, '--require', ${JSON.stringify(RECORD_SPAWN)}, '--require', ${JSON.stringify(FIX)}] });
w.on('error', (e) => { console.error(e); process.exit(1); });
`);
const fromBionicWorker = execFileSync(process.execPath, [bionicWorker], { encoding: 'utf8', env: { ...process.env, NODE_OPTIONS: '' } });
assert.deepStrictEqual(
    JSON.parse(fromBionicWorker.trim().split('\n').pop()), [BIONIC_LINKER, [ENGINE, '--version']],
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
        "cp.spawn = function () { globalThis.__spawned = Array.prototype.slice.call(arguments); const c = new EventEmitter(); c.pid = 4242; c.kill = function (signal) { c.killedWith = signal || 'SIGTERM'; }; globalThis.__child = c; return c; };\n" +
        // The server made last, which right after a hub start is the relay.
        "const http = require('http');\nconst createServer = http.createServer;\n" +
        "http.createServer = function () { const s = createServer.apply(this, arguments); globalThis.__relay = s; return s; };\n",
);
// The relay moves the engine to a port the kernel handed out a moment before, and the drivers below bind that
// port as the stand-in engine. Another program can take it in between, which says nothing about the relay, so
// such a run is made again with another port, up to four times in all.
// Any other failure, and a fourth taken port, is raised as it is.
const retryOnTakenPort = (run) => {
    for (let attempt = 1; ; attempt++) {
        try {
            return run();
        } catch (e) {
            if (attempt >= 4 || !/EADDRINUSE/.test(String(e.stderr) + String(e.message))) throw e;
        }
    }
};
{
    const taken = () => Object.assign(new Error('Command failed'), { stderr: 'Error: listen EADDRINUSE: address already in use 127.0.0.1:49438' });
    let calls = 0;
    assert.strictEqual(retryOnTakenPort(() => { if (++calls < 3) throw taken(); return 'ran'; }), 'ran', 'a taken port was not asked again');
    assert.strictEqual(calls, 3, `a run that met a taken port twice was made ${calls} times`);
    calls = 0;
    assert.throws(() => retryOnTakenPort(() => { calls++; throw taken(); }), /Command failed/, 'a port taken four times was not raised');
    assert.strictEqual(calls, 4, `a port taken every time was tried ${calls} times, wanted 4`);
    calls = 0;
    assert.throws(() => retryOnTakenPort(() => { calls++; throw new Error('the driver said no'); }), /the driver said no/, 'another failure was swallowed');
    assert.strictEqual(calls, 1, `a failure that is not a taken port was made ${calls} times`);
    checked += 3;
}
const runHubDriver = (driver) => retryOnTakenPort(() => JSON.parse(execFileSync(
    process.execPath,
    ['--require', FAKE_ANDROID, '--require', RECORD_HUB, '--require', FIX, driver, HUB_ENGINE],
    { env: { ...process.env, HOME: HUB_HOME, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 60000 },
).trim().split('\n').pop()));
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
const getAs = (port, path, host) => new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port, path, agent: false, timeout: 3000, headers: { Host: host } }, (res) => {
        let body = ''; res.on('data', (c) => { body += c; });
        res.on('end', () => resolve({ status: res.statusCode, body }));
        res.on('error', (e) => resolve({ error: e.code || e.message }));
        res.on('aborted', () => resolve({ error: 'aborted' }));
    });
    req.on('timeout', () => { req.destroy(); resolve({ error: 'no answer' }); });
    req.on('error', (e) => resolve({ error: e.code || e.message }));
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
    const seen = { requests: 0 };
    const engine = http.createServer((req, res) => {
        seen.host = req.headers.host;
        seen.requests++;
        if (req.url === '/hang') {
            res.writeHead(200, { 'Content-Type': 'text/event-stream' });
            res.write('first');
            return;
        }
        if (req.url === '/wait') {
            res.on('close', () => { seen.waitClosed = true; });
            return;
        }
        if (req.url === '/slow') {
            res.writeHead(200, { 'Content-Type': 'text/event-stream' });
            res.write('first');
            res.on('close', () => { seen.slowClosed = true; });
            return;
        }
        if (req.url === '/abort') {
            res.writeHead(200, { 'Content-Length': '100' });
            res.write('partial');
            setTimeout(() => res.socket.destroy(), 50);
            return;
        }
        if (req.url === '/dropbody') {
            // Gone before the body is read.
            req.socket.destroy();
            return;
        }
        if (req.url === '/switch') {
            // 101 to a request that asked for no upgrade. Node's client drops the connection and reports neither a
            // response nor an error.
            res.socket.write('HTTP/1.1 101 Switching Protocols\\r\\nUpgrade: other\\r\\nConnection: Upgrade\\r\\n\\r\\n');
            return;
        }
        if (req.url === '/reset') {
            // A body sent in chunks, cut by a reset: nothing in what was sent says how long it should have been.
            res.writeHead(200, { 'Content-Type': 'text/plain' });
            res.write('hello');
            setTimeout(() => res.socket.resetAndDestroy(), 50);
            return;
        }
        if (req.url === '/badreason') {
            // A status text with a control byte: Node's client takes it and Node's server will not write it back. The
            // socket stays open, so the engine can tell whether the relay let go of it.
            res.socket.on('close', () => { seen.badReasonClosed = true; });
            res.socket.write('HTTP/1.1 200 O\\x01K\\r\\nContent-Length: 5\\r\\n\\r\\nab');
            return;
        }
        res.setHeader('Content-Security-Policy', req.url === '/plain'
            ? "frame-ancestors 'self'; report-uri https://example.test/r"
            : "frame-ancestors 'self' http://127.0.0.1:* vscode-webview: vscode-file:; report-uri https://example.test/r");
        res.setHeader('Set-Cookie', ['a=1', 'b=2']);
        res.end('hello ' + req.url);
    });
    engine.on('upgrade', (req, socket) => {
        seen.upgradeHost = req.headers.host;
        socket.write('HTTP/1.1 101 Switching Protocols\\r\\nUpgrade: websocket\\r\\nConnection: Upgrade\\r\\n\\r\\n');
        socket.on('data', (d) => socket.write(d));
    });
    await new Promise((r) => engine.listen(back, '127.0.0.1', r));
    const page = await get(front, '/page?x=1');
    out.page = { status: page.status, csp: page.headers['content-security-policy'], cookies: page.headers['set-cookie'], body: page.body };
    out.engineSawHost = seen.host;
    out.plainCsp = (await get(front, '/plain')).headers['content-security-policy'];
    // A request that names another host, as a page whose name was pointed at 127.0.0.1 does.
    const before = seen.requests;
    out.foreignHost = await getAs(front, '/page', 'evil.example:' + front);
    out.foreignHostPort = await getAs(front, '/page', '127.0.0.1:' + (front + 1));
    out.noHostReached = seen.requests - before;
    out.localhostHost = await getAs(front, '/page', 'localhost:' + front);
    out.foreignUpgrade = await new Promise((resolve) => {
        const socket = net.connect(front, '127.0.0.1', () => socket.write('GET /ws HTTP/1.1\\r\\nHost: evil.example:' + front + '\\r\\nConnection: Upgrade\\r\\nUpgrade: websocket\\r\\n\\r\\n'));
        let data = '';
        socket.on('data', (d) => { data += d; });
        socket.on('close', () => resolve(data.split('\\r\\n')[0]));
        socket.setTimeout(3000, () => { socket.destroy(); });
    });
    // A client that resets the connection right after a refused upgrade: the relay's end of it must not raise an
    // error nobody listens for.
    const uncaught = [];
    process.on('uncaughtException', (e) => uncaught.push(e.code || e.message));
    await new Promise((resolve) => {
        const socket = net.connect(front, '127.0.0.1', () => {
            socket.write('GET /ws HTTP/1.1\\r\\nHost: evil.example:' + front + '\\r\\nConnection: Upgrade\\r\\nUpgrade: websocket\\r\\n\\r\\n');
            setTimeout(() => { socket.resetAndDestroy(); resolve(); }, 20);
        });
        socket.on('error', () => {});
    });
    await new Promise((r) => setTimeout(r, 300));
    out.uncaughtAfterRefusedUpgrade = uncaught.slice();
    // An answer whose status text the relay's own server will not write: the client gets a status and the engine's
    // connection is let go, where it used to throw inside the response callback and leave both waiting.
    const uncaughtBefore = uncaught.length;
    out.badReason = await getAs(front, '/badreason', '127.0.0.1:' + front);
    await new Promise((r) => setTimeout(r, 300));
    out.engineSawBadReasonClose = seen.badReasonClosed === true;
    out.uncaughtAfterBadReason = uncaught.slice(uncaughtBefore);
    // An engine that resets in the middle of a body: the client must be let go, not left waiting.
    out.abortedBody = await getAs(front, '/abort', '127.0.0.1:' + front);
    // The same in the middle of a body sent in chunks: the client must not be handed the part as the whole.
    out.resetChunked = await getAs(front, '/reset', '127.0.0.1:' + front);
    // An engine that switches protocols on a request that asked for none: the client gets a status.
    out.switched = await getAs(front, '/switch', '127.0.0.1:' + front);
    // A refusal while a large request body is on its way. The relay never reads the rest of that body, so
    // a connection it kept would be one that answers nothing more: it has to close it.
    out.refusedBody = await new Promise((resolve) => {
        let data = '';
        let settled = false;
        const done = (closed) => { if (!settled) { settled = true; resolve({ status: data.split('\\r\\n')[0], closed }); } };
        const socket = net.connect(front, '127.0.0.1', () => {
            socket.write('POST /dropbody HTTP/1.1\\r\\nHost: 127.0.0.1:' + front + '\\r\\nContent-Length: 4194304\\r\\n\\r\\n');
            socket.write(Buffer.alloc(65536));
        });
        socket.on('data', (d) => { data += d; });
        socket.on('close', () => done(true));
        socket.on('error', () => {});
        setTimeout(() => { done(false); socket.destroy(); }, 1500);
    });
    // A client that walks away from a response the engine is still writing: the engine has to see it go.
    await new Promise((resolve) => {
        const walker = http.get({ host: '127.0.0.1', port: front, path: '/slow', agent: false }, (res) => { res.once('data', () => { walker.destroy(); resolve(); }); });
        walker.on('error', () => {});
    });
    await new Promise((r) => setTimeout(r, 300));
    out.engineSawClientLeave = seen.slowClosed === true;
    // And one that leaves while the engine has not answered at all, a long poll for instance.
    await new Promise((resolve) => {
        const waiter = http.get({ host: '127.0.0.1', port: front, path: '/wait', agent: false }, () => {});
        waiter.on('error', () => {});
        setTimeout(() => { waiter.destroy(); resolve(); }, 200);
    });
    await new Promise((r) => setTimeout(r, 300));
    out.engineSawWaiterLeave = seen.waitClosed === true;
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
    {
        const squatter = net.createServer().listen(0, '127.0.0.1');
        await new Promise((r) => squatter.once('listening', r));
        const taken = squatter.address().port;
        // The relay says why it cannot listen, which is the point of this step and not a failure of the run.
        const report = console.error;
        console.error = () => {};
        require('child_process').spawn(process.argv[2], ['--hub', '--hub-port=' + taken]);
        await new Promise((r) => setTimeout(r, 300));
        console.error = report;
        out.killedWhenFrontIsTaken = globalThis.__child.killedWith || null;
        squatter.close();
    }
    out.upgradeHost = seen.upgradeHost;
    // A response still open when the engine ends is ended with it, not left for a client to wait on.
    const hanging = getAs(front, '/hang', '127.0.0.1:' + front);
    await new Promise((r) => setTimeout(r, 200));
    child.emit('exit', 0);
    out.hangingEnded = await Promise.race([hanging, new Promise((r) => setTimeout(() => r({ error: 'still open' }), 1500))]);
    await new Promise((r) => setTimeout(r, 150));
    out.afterExit = await get(front, '/healthz').then(() => 'still answering', (e) => e.code);
    out.noteAfterExit = require('fs').existsSync(noteFile);
    console.log(JSON.stringify(out));
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
`);
// The engine's own port answers any app on the phone, so the starts the shim sees of what the Antigravity
// extension keeps in $HOME/.gemini are held back until the person has turned on the machine setting
// `vscodroid.antigravity.runEngine`.
// Each home here lies inside the data directory, so the linker rule applies, and carries an engine of its
// own at .gemini/bin/agy, a file and not a link: a link out of the home would take the engine out of the
// setting's reach. The shim keeps its note of a started engine under the home's .vscodroid as well.
const machineSettings = (home) => path.join(home, '.vscodroid/data/Machine/settings.json');
const engineIn = (home) => path.join(home, '.gemini/bin/agy');
let homes = 0;
const homeWith = (text) => {
    const home = path.join(REAL_DATA, 'files', `home-${homes++}`);
    put(engineIn(home), elf64(BIONIC_LINKER));
    if (text !== null) put(machineSettings(home), text);
    return home;
};
const SETTING_ON = '{\n    // what the app wrote\n    "git.path": "/x/libgit.so",\n    "vscodroid.antigravity.runEngine": true,\n    "update.mode": "none"\n}\n';
const HUB_HOME = homeWith(SETTING_ON);
const HUB_ENGINE = engineIn(HUB_HOME);
const HUB_NOTE = path.join(HUB_HOME, '.vscodroid/antigravity-hub.json');
const OFF_HOME = homeWith(null);
const OFF_ENGINE = engineIn(OFF_HOME);
const HUB_ARGS = ['--hub', '--hub-port=41000', '--app_data_dir=antigravity'];
// `call` (a program, perhaps an argument list, then options) as the shim makes it through the linker, run
// from `home`: the program first among the linker's arguments, and the options carrying the interceptor.
// A program in the home's own ~/.gemini is started without the interceptor, as it always was: the interceptor
// would start whatever else in ~/.gemini it named.
const throughLinker = (call, home) => {
    const [file, ...rest] = call;
    const args = Array.isArray(rest[0]) ? rest.shift() : [];
    if (file.startsWith(path.join(home, '.gemini') + '/')) return [BIONIC_LINKER, [file, ...args], ...rest];
    return [BIONIC_LINKER, [file, ...args], { ...rest[0], env: routedEnv(file, { home }) }, ...rest.slice(1)];
};

// The file is read as VS Code builds its settings from it: comments and trailing commas out, strings kept,
// the keys of the root object split at their dots into a tree in file order (so the dotted key, the key
// written as an object under `vscodroid.antigravity` and the fully nested form all name the setting, and a
// later key replaces what an earlier one made), and a file that does not parse turns nothing on.
for (const [label, text, on] of [
    ['no settings file', null, false],
    ['the setting absent', '{\n    "update.mode": "none"\n}\n', false],
    ['the setting false', '{\n    "vscodroid.antigravity.runEngine": false\n}\n', false],
    ['the setting in a line comment', '{\n    // "vscodroid.antigravity.runEngine": true,\n    "update.mode": "none"\n}\n', false],
    ['the setting in a block comment', '{\n    /*\n    "vscodroid.antigravity.runEngine": true,\n    */\n    "update.mode": "none"\n}\n', false],
    ['the setting quoted inside another value', '{\n    "note": "\\"vscodroid.antigravity.runEngine\\": true"\n}\n', false],
    ['a file that is not text the reader understands', '\u0000\u0001', false],
    ['the key alone on its line inside another object', '{\n    "some.object": {\n        "vscodroid.antigravity.runEngine": true\n    }\n}\n', false],
    ['the key inside a language block', '{\n    "[python]": {\n        "vscodroid.antigravity.runEngine": true\n    }\n}\n', false],
    ['the key twice, true then false', '{\n    "vscodroid.antigravity.runEngine": true,\n    "vscodroid.antigravity.runEngine": false\n}\n', false],
    ['the key, then a scalar where its parent should be', '{\n    "vscodroid.antigravity.runEngine": true,\n    "vscodroid": 1\n}\n', false],
    ['a scalar where the parent should be, then the key', '{\n    "vscodroid": 1,\n    "vscodroid.antigravity.runEngine": true\n}\n', false],
    ['the key, then the same setting written false as an object', '{\n    "vscodroid.antigravity.runEngine": true,\n    "vscodroid.antigravity": { "runEngine": false }\n}\n', false],
    ['the key, then its first segment replaced by an empty object', '{\n    "vscodroid.antigravity.runEngine": true,\n    "vscodroid": {}\n}\n', false],
    ['a dot inside a nested key, which VS Code does not split', '{\n    "vscodroid": { "antigravity.runEngine": true }\n}\n', false],
    ['brackets nested far past what a settings file holds', '{\n    "vscodroid.antigravity.runEngine": true,\n    "x": ' + '['.repeat(20000) + ']'.repeat(20000) + '\n}\n', false],
    ['a string broken across lines', '{\n    "a": "x\ny",\n    "vscodroid.antigravity.runEngine": true\n}\n', false],
    ['a file past a megabyte', '{\n    "vscodroid.antigravity.runEngine": true,\n    "x": "' + 'x'.repeat(1 << 20) + '"\n}\n', false],
    ['a list, not an object', '[{ "vscodroid.antigravity.runEngine": true }]\n', false],
    ['null', 'null\n', false],
    ['true alone', 'true\n', false],
    ['a block comment that never closes', '{\n    /*\n    "vscodroid.antigravity.runEngine": true\n}\n', false],
    ['a value split by a comment', '{\n    "vscodroid.antigravity.runEngine": tr/**/ue\n}\n', false],
    ['the file the app writes, with the setting on', SETTING_ON, true],
    ['a single line', '{"vscodroid.antigravity.runEngine": true}', true],
    ['a byte order mark and a single line', '﻿{"vscodroid.antigravity.runEngine": true}', true],
    ['CRLF line ends', '{\r\n\t"update.mode": "none",\r\n\t"vscodroid.antigravity.runEngine": true\r\n}\r\n', true],
    ['the key written with an escaped dot', '{\n    "vscodroid.antigravity\\u002erunEngine": true\n}\n', true],
    ['// inside a string before the key', '{\n    "a": "http://example.test/",\n    "vscodroid.antigravity.runEngine": true\n}\n', true],
    ['/* inside a string before the key', '{\n    "a": "/*",\n    "vscodroid.antigravity.runEngine": true,\n    "b": "*/"\n}\n', true],
    ['comments around the key and the value', '{ /* x */\n    "vscodroid.antigravity.runEngine" /* y */ : /* z */ true // on\n}\n', true],
    ['trailing commas', '{\n    "a": [1, 2,],\n    "vscodroid.antigravity.runEngine": true,\n}\n', true],
    ['the key twice, false then true', '{\n    "vscodroid.antigravity.runEngine": false,\n    "vscodroid.antigravity.runEngine": true\n}\n', true],
    ['the setting written as an object under vscodroid.antigravity', '{\n    "vscodroid.antigravity": { "runEngine": true }\n}\n', true],
    ['the fully nested form', '{\n    "vscodroid": { "antigravity": { "runEngine": true } }\n}\n', true],
    ['the dotted key beside another key under vscodroid', '{\n    "vscodroid": { "other": 1 },\n    "vscodroid.antigravity.runEngine": true\n}\n', true],
    ['the key after a setting of the same object written false', '{\n    "vscodroid.antigravity": { "runEngine": false },\n    "vscodroid.antigravity.runEngine": true\n}\n', true],
    ['a raw tab inside a string before the key', '{\n    "a": "x\ty",\n    "vscodroid.antigravity.runEngine": true\n}\n', true],
    ['a line comment ended by a bare CR', '{\r    "a": 1, // a comment\r    "vscodroid.antigravity.runEngine": true\r}\r', true],
]) {
    const home = homeWith(text);
    const call = [engineIn(home), HUB_ARGS, SPAWN_OPTIONS];
    const got = spawnedUnder(call, { home });
    if (on) {
        assert.strictEqual(got[0], BIONIC_LINKER, `${label}: VS Code reads the setting as on and the engine's hub was not started`);
    } else {
        assert.deepStrictEqual(got, call, `${label}: VS Code reads the setting as off and the engine's hub was started`);
        assert.strictEqual(fs.existsSync(path.join(home, '.vscodroid/antigravity-hub.json')), false, `${label}: a note was kept for an engine that was not started`);
    }
    checked++;
}

// A FIFO where the machine settings file or the note of a started engine should be, or a link to one, holds a
// plain open until a writer comes, and the extension host or the editor server's start waited with it, for good.
// Only a regular file is read, opened without waiting: a settings file that is anything else turns nothing on, a
// note that is anything else names no engine, and no note is written into one.
{
    const noteIn = (home) => path.join(home, '.vscodroid/antigravity-hub.json');
    const fifoAt = (file) => { fs.mkdirSync(path.dirname(file), { recursive: true }); execFileSync('mkfifo', [file]); return file; };
    /** What `main` printed last, run under the shim from `home`, or 'hung' when it had not ended within 5 s. */
    const within5s = (main, call, home, record = RECORD_SPAWN, fix = FIX) => {
        try {
            return JSON.parse(execFileSync(
                process.execPath,
                ['--require', FAKE_ANDROID, '--require', SET_PRELOAD, '--require', record, '--require', fix, main, JSON.stringify(call)],
                { env: childEnv({ home }), encoding: 'utf8', timeout: 5000 },
            ).trim().split('\n').pop());
        } catch (e) {
            if (e.code === 'ETIMEDOUT') return 'hung';
            throw e;
        }
    };
    const settingsFifo = homeWith(null);
    fifoAt(machineSettings(settingsFifo));
    const settingsLink = homeWith(null);
    fs.mkdirSync(path.dirname(machineSettings(settingsLink)), { recursive: true });
    fs.symlinkSync(fifoAt(path.join(tmp, 'outside/settings-fifo')), machineSettings(settingsLink));
    for (const [label, home] of [['a FIFO', settingsFifo], ['a link to a FIFO', settingsLink]]) {
        const call = [engineIn(home), HUB_ARGS, SPAWN_OPTIONS];
        assert.deepStrictEqual(within5s(SPAWN_MAIN, call, home), call, `${label} at the machine settings path: the engine's start did not return as written`);
        assert.strictEqual(fs.existsSync(noteIn(home)), false, `${label} at the machine settings path: a note was kept for an engine that was not started`);
        checked++;
    }
    // The process monitor extension asks the gate itself when it activates.
    const GATE_MAIN = path.join(tmp, 'gate-main.js');
    fs.writeFileSync(GATE_MAIN, "console.log(JSON.stringify(globalThis[Symbol.for('vscodroid.engineGate')].engineTurnedOn()));\n");
    assert.strictEqual(within5s(GATE_MAIN, [], settingsFifo), false, 'a FIFO at the machine settings path: the gate the process monitor asks did not answer off');
    checked++;
    // A start the setting refuses ends the engine an earlier host started, which reads the note.
    const noteFifoOff = homeWith(null);
    fifoAt(noteIn(noteFifoOff));
    const refused = [engineIn(noteFifoOff), HUB_ARGS, SPAWN_OPTIONS];
    assert.deepStrictEqual(within5s(SPAWN_MAIN, refused, noteFifoOff), refused, 'a FIFO at the note: a start the setting refuses did not return as written');
    checked++;
    // With the setting on, the hub's start reads the note and writes its own, and the engine's exit reads it again.
    const FIFO_HUB_DRIVER = path.join(tmp, 'fifo-hub-driver.js');
    fs.writeFileSync(FIFO_HUB_DRIVER, `
const fs = require('fs');
const net = require('net');
const [engine, note, when] = JSON.parse(process.argv[2]);
const fifo = () => { fs.rmSync(note, { force: true }); require('child_process').execFileSync('mkfifo', [note]); };
if (when === 'before the start') fifo();
const s = net.createServer().listen(0, '127.0.0.1', () => {
    const front = s.address().port;
    s.close(() => {
        const child = require('child_process').spawn(engine, ['--hub', '--hub-port=' + front]);
        const written = fs.lstatSync(note).isFile() ? JSON.parse(fs.readFileSync(note, 'utf8')).pid : null;
        if (when === 'before the exit') { fifo(); child.emit('exit', 0); }
        console.log(JSON.stringify({ started: globalThis.__spawned[0], written, fifo: fs.lstatSync(note).isFIFO() }));
        process.exit(0);
    });
});
`);
    for (const [when, written] of [['before the start', null], ['before the exit', 4242]]) {
        const home = homeWith(SETTING_ON);
        assert.deepStrictEqual(within5s(FIFO_HUB_DRIVER, [engineIn(home), noteIn(home), when], home, RECORD_HUB), { started: BIONIC_LINKER, written, fifo: true },
            `a FIFO at the note ${when}: the hub's start or its exit did not return, or wrote into the FIFO`);
        checked++;
    }
    // The editor server's own start and exit end an engine the last one left, which reads the note.
    const SERVER_COPY = path.join(tmp, 'fifo-server/platform-fix.js');
    const SERVER_MAIN = put(path.join(tmp, 'fifo-server/vscode-reh/out/server-main.js'), "console.log('null');\n");
    fs.copyFileSync(FIX, SERVER_COPY);
    assert.strictEqual(within5s(SERVER_MAIN, [], noteFifoOff, RECORD_SPAWN, SERVER_COPY), null, 'a FIFO at the note: the editor server did not start and exit');
    checked++;
}

// Where ~/.gemini and the machine settings are is read when the file is loaded. An extension in the shared host
// may reassign or delete HOME later, and the decision must not follow it: the gate would be skipped (no
// ~/.gemini under the new HOME) or read a file of its own.
const homeChangedMain = (statement) => {
    const file = path.join(tmp, `home-changed-${homes++}.js`);
    fs.writeFileSync(
        file,
        `${statement}\n` +
            "const result = require('child_process').spawn(...JSON.parse(process.argv[2]));\n" +
            "if (result !== 'child') throw new Error('the return value was lost: ' + result);\n",
    );
    return file;
};
for (const [label, statement] of [
    ['HOME reassigned to a directory with no ~/.gemini', `process.env.HOME = ${JSON.stringify(tmp)};`],
    ['HOME deleted', 'delete process.env.HOME;'],
    ['HOME reassigned to a home whose setting is on', `process.env.HOME = ${JSON.stringify(HUB_HOME)};`],
]) {
    const call = [OFF_ENGINE, HUB_ARGS, SPAWN_OPTIONS];
    assert.deepStrictEqual(spawnedUnder(call, { home: OFF_HOME, main: homeChangedMain(statement) }), call, `setting off, ${label}: the engine's hub was started`);
    checked++;
}
{
    const call = [HUB_ENGINE, HUB_ARGS, SPAWN_OPTIONS];
    for (const [label, statement] of [
        ['HOME reassigned to a home whose setting is off', `process.env.HOME = ${JSON.stringify(OFF_HOME)};`],
        ['HOME deleted', 'delete process.env.HOME;'],
    ]) {
        assert.strictEqual(spawnedUnder(call, { home: HUB_HOME, main: homeChangedMain(statement) })[0], BIONIC_LINKER, `setting on, ${label}: the engine's hub was held back`);
        checked++;
    }
}

// With the setting off, whatever is asked of a program in ~/.gemini is left as written, which fails with
// EACCES as it did before: the engine's Go flag parser takes -hub and --hub=true for --hub, it listens in a
// second mode as well, and the extension and the engine update themselves. Only `agy --version`, the
// extension's check of what it downloaded, goes through the linker. With the setting on all of them run,
// the relay standing only in front of the start it knows.
const AGY_NEXT = put(path.join(OFF_HOME, '.gemini/bin/agy.next'), elf64(BIONIC_LINKER));
put(path.join(HUB_HOME, '.gemini/bin/agy.next'), elf64(BIONIC_LINKER));
const LINK_TO_OFF_ENGINE = path.join(tmp, 'outside/gemini-engine-link');
fs.symlinkSync(OFF_ENGINE, LINK_TO_OFF_ENGINE);
for (const [label, call, allowedWhenOn] of [
    ['the hub as the extension starts it', [OFF_ENGINE, HUB_ARGS, SPAWN_OPTIONS], null],
    ['--hub=true', [OFF_ENGINE, ['--hub=true', '--hub-port=41000'], SPAWN_OPTIONS], false],
    ['single-dash flags', [OFF_ENGINE, ['-hub', '-hub-port=41000'], SPAWN_OPTIONS], false],
    ['the remote control mode', [OFF_ENGINE, ['--remote-control'], SPAWN_OPTIONS], false],
    ['no arguments', [OFF_ENGINE, SPAWN_OPTIONS], true],
    ['--version with more after it', [OFF_ENGINE, ['--version', '--hub'], SPAWN_OPTIONS], false],
    ['--version of another program in ~/.gemini, which is not the extension\'s check of the engine', [AGY_NEXT, ['--version'], SPAWN_OPTIONS], true],
    ['another program in ~/.gemini', [AGY_NEXT, HUB_ARGS, SPAWN_OPTIONS], true],
    ['a link from elsewhere that ends at the engine', [LINK_TO_OFF_ENGINE, HUB_ARGS, SPAWN_OPTIONS], null],
]) {
    assert.deepStrictEqual(spawnedUnder(call, { home: OFF_HOME }), call, `setting off, ${label}: the call was changed, so it runs`);
    // The start the relay knows is checked on its own below.
    const on = [call[0].replace(OFF_HOME, HUB_HOME), ...call.slice(1)];
    if (allowedWhenOn === true) {
        assert.deepStrictEqual(spawnedUnder(on, { home: HUB_HOME }), throughLinker(on, HUB_HOME), `setting on, ${label}: not started through the linker as it was written`);
    } else if (allowedWhenOn === false) {
        assert.deepStrictEqual(spawnedUnder(on, { home: HUB_HOME }), on, `setting on, ${label}: a disallowed listener form was started through the linker`);
    }
    checked++;
}
assert.deepStrictEqual(spawnedUnder([OFF_ENGINE, ['--version'], SPAWN_OPTIONS], { home: OFF_HOME }), throughLinker([OFF_ENGINE, ['--version'], SPAWN_OPTIONS], OFF_HOME),
    'setting off, the extension\'s check of the engine\'s version was held back, so it downloads the engine again');
checked++;
// A program's path is read as text before it is resolved, with either resolver the shim may use: `..` after a link
// in ~/.gemini that leads out of it still spells out the engine, and the start is held back.
{
    const home = homeWith(null);
    fs.symlinkSync(path.join(tmp, 'outside'), path.join(home, '.gemini/out'));
    const call = [`${home}/.gemini/out/../bin/agy`, HUB_ARGS, SPAWN_OPTIONS];
    assert.deepStrictEqual(spawnedUnder(call, { home }), call, 'setting off, the engine spelled out with .. after a link was not read as text first, and was changed');
    checked++;
}
// The system's resolver is used only where it answers as the JavaScript one does on the shim's own directory: one
// that fails, or answers another path, is left out, and the gate reads with the JavaScript one.
for (const [label, answer] of [
    ['fails', "() => { throw Object.assign(new Error('refused'), { code: 'EPERM' }); }"],
    ['answers / for every path', "() => '/'"],
]) {
    const record = path.join(tmp, `record-native-${homes++}.js`);
    fs.writeFileSync(record, `require(${JSON.stringify(RECORD_SPAWN)});\nrequire('fs').realpathSync.native = ${answer};\n`);
    const hub = [OFF_ENGINE, HUB_ARGS, SPAWN_OPTIONS];
    assert.deepStrictEqual(spawnedUnder(hub, { home: OFF_HOME, record }), hub, `a system resolver that ${label}: the engine's hub was changed with the setting off`);
    assert.deepStrictEqual(spawnedUnder([ENGINE, TOOL_ARGS, SPAWN_OPTIONS], { home: OFF_HOME, record }), [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], { ...SPAWN_OPTIONS, env: routedEnv(ENGINE, { home: OFF_HOME }) }],
        `a system resolver that ${label}: a program outside ~/.gemini was not started through the linker with the interceptor`);
    checked += 2;
}
for (const how of ['plain', 'promisified']) {
    const exec = (call, home) => spawnedUnder([how, call], { record: RECORD_EXEC, main: EXEC_MAIN, home });
    const hub = [OFF_ENGINE, ['--hub', '--hub-port=41000'], { timeout: 5000 }];
    assert.deepStrictEqual(exec(hub, OFF_HOME), [how, ...hub], `execFile (${how}) started the hub with the setting off`);
    assert.deepStrictEqual(exec([OFF_ENGINE, ['--hub', '--hub-port=41000'], '@callback'], OFF_HOME).slice(0, 2), [how, OFF_ENGINE], `execFile (${how}) with a callback was started with the setting off`);
    assert.deepStrictEqual(exec([OFF_ENGINE, ['--remote-control'], { timeout: 5000 }], OFF_HOME), [how, OFF_ENGINE, ['--remote-control'], { timeout: 5000 }], `execFile (${how}) of the remote control mode was started with the setting off`);
    const version = [OFF_ENGINE, ['--version'], { timeout: 5000 }];
    assert.deepStrictEqual(exec(version, OFF_HOME), [how, ...throughLinker(version, OFF_HOME)], `execFile (${how}) of the engine for its version is held back with the setting off`);
    // No relay here: an engine started by execFile with the setting on is an ordinary program on the port it was given.
    const onHub = [HUB_ENGINE, ['--hub', '--hub-port=41000'], { timeout: 5000 }];
    assert.deepStrictEqual(exec(onHub, HUB_HOME), [how, ...throughLinker(onHub, HUB_HOME)], `execFile (${how}) with the setting on did not go through the linker as it was written`);
    checked += 5;
}

// A program outside $HOME/.gemini is not the setting's to hold back, even one at a path ending in
// .gemini/bin/agy: it goes through the linker either way, and gets no relay.
for (const home of [OFF_HOME, HUB_HOME]) {
    const call = [ENGINE, HUB_ARGS, SPAWN_OPTIONS];
    assert.deepStrictEqual(spawnedUnder(call, { home }), throughLinker(call, home), `a program outside $HOME/.gemini was changed by the setting (${home === HUB_HOME ? 'on' : 'off'})`);
    checked++;
}

// Every way an extension can start a program, recorded: the asynchronous and synchronous starts, and the two
// that run a command in a shell. exec reaches the exported execFile with `shell` set and the whole command as
// the file; execSync and execFileSync reach Node's internal spawnSync, so each is its own case.
const RECORD_ALL = path.join(tmp, 'record-all.js');
fs.writeFileSync(
    RECORD_ALL,
    "const cp = require('child_process');\n" +
        "const note = (how) => function () { console.log(JSON.stringify([how].concat(Array.prototype.slice.call(arguments)))); return 'child'; };\n" +
        "['spawn', 'spawnSync', 'execFile', 'execFileSync', 'execSync'].forEach((how) => { cp[how] = note(how); });\n" +
        "cp.execFile[require('util').promisify.custom] = note('promisified');\n",
);
const ALL_MAIN = path.join(tmp, 'all-main.js');
fs.writeFileSync(
    ALL_MAIN,
    "const cp = require('child_process');\n" +
        // { "@url": path } is that path as a file URL, { "@buffer": [bytes] } those bytes as a Buffer,
        // { "@uint8": [bytes] } as a Uint8Array that is not one, { "@toString": text } as a plain object
        // that coerces to text, and { "@flip": [first, second] } as a stateful object whose toString changes
        // between its first and second coercion.
        "const [how, call] = JSON.parse(process.argv[2], (k, v) => (v && typeof v['@url'] === 'string' ? require('url').pathToFileURL(v['@url'])\n" +
        "    : v && Array.isArray(v['@buffer']) ? Buffer.from(v['@buffer']) : v && Array.isArray(v['@uint8']) ? Uint8Array.from(v['@uint8'])\n" +
        "    : v && typeof v['@toString'] === 'string' ? { toString() { return v['@toString']; } }\n" +
        "    : v && Array.isArray(v['@flip']) ? (() => { let n = 0; return { toString() { return v['@flip'][Math.min(n++, v['@flip'].length - 1)]; } }; })()\n" +
        "    : v === '@symbol' ? Symbol('x') : v === '@nullproto' ? Object.create(null)\n" +
        "    : v === '@throw' ? { toString() { throw new Error('boom'); } } : v));\n" +
        "const args = call.map((x) => (x === '@callback' ? function () {} : x === '@undefined' ? undefined : x));\n" +
        "if (how === 'promisified') require('util').promisify(cp.execFile)(...args); else cp[how](...args);\n",
);
const callUnder = (how, call, conditions = {}) => spawnedUnder([how, call], { record: RECORD_ALL, main: ALL_MAIN, ...conditions });
// A cwd given as the bytes of `text` (and `more`) in a Buffer, or in a Uint8Array that is not one, and each as a
// recorder logs it.
const bytes = (text, ...more) => ({ '@buffer': [...Buffer.from(text), ...more] });
const asBuffer = (text, ...more) => ({ type: 'Buffer', data: [...Buffer.from(text), ...more] });
const view = (text) => ({ '@uint8': [...Buffer.from(text)] });
const asView = (text) => ({ ...[...Buffer.from(text)] });
// A call as a failure message shows it, a Buffer or a Uint8Array as its text.
const shown = (call) => JSON.stringify(call, (k, v) => (v && Array.isArray(v['@buffer']) ? `Buffer(${Buffer.from(v['@buffer'])})`
    : v && Array.isArray(v['@uint8']) ? `Uint8Array(${Buffer.from(v['@uint8'])})` : v));

// The synchronous starts go through the linker as the asynchronous ones do, with the interceptor.
for (const [how, call, expected] of [
    ['spawnSync', [ENGINE, TOOL_ARGS, SPAWN_OPTIONS], [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], ROUTED]],
    ['spawnSync', [ENGINE], [BIONIC_LINKER, [ENGINE], { env: routedEnv(ENGINE) }]],
    ['execFileSync', [ENGINE, ['--version']], [BIONIC_LINKER, [ENGINE, '--version'], { env: routedEnv(ENGINE) }]],
    ['execFileSync', [ENGINE, null, VERSION_OPTIONS], [BIONIC_LINKER, [ENGINE], VERSION_ROUTED]],
    // Node reads a function in place of the argument list of execFileSync as its callback, and runs the call.
    ['execFileSync', [ENGINE, CALLBACK], [BIONIC_LINKER, [ENGINE], { env: routedEnv(ENGINE) }, null]],
    // A Buffer for the cwd names the directory a synchronous start runs in, here one that reaches nothing in ~/.gemini.
    ['spawnSync', [ENGINE, TOOL_ARGS, { cwd: bytes(tmp) }], [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], { cwd: asBuffer(tmp), env: routedEnv(ENGINE) }]],
]) {
    assert.deepStrictEqual(callUnder(how, call), [how, ...expected], `${how}: not started through the linker with the interceptor`);
    assert.deepStrictEqual(callUnder(how, call, { prefix: BARE_PREFIX }), [how, ...expected.map((x) => (x && x.env ? (({ env, ...rest }) => rest)(x) : x)).filter((x) => !(x && typeof x === 'object' && !Array.isArray(x) && !Object.keys(x).length))],
        `${how} with no interceptor on disk: the call gained an environment`);
    checked += 2;
}
// Null options are no options to execFileSync, as they are to execFile.
assert.deepStrictEqual(callUnder('execFileSync', [ENGINE, [], null]), ['execFileSync', BIONIC_LINKER, [ENGINE], { env: routedEnv(ENGINE) }],
    'execFileSync with null options: not started through the linker with the interceptor');
checked++;
for (const [how, call] of [
    ['spawnSync', [MUSL, TOOL_ARGS, SPAWN_OPTIONS]],
    ['execFileSync', [OUTSIDE, ['--version'], VERSION_OPTIONS]],
]) {
    assert.deepStrictEqual(callUnder(how, call), [how, ...call], `${how}: a call that is not the linker's was changed`);
    checked++;
}
// Every start through the linker leaves argv0 out (see the spawn cases), the engine's own included, which gets no
// interceptor: kept, its name in /proc was the one an earlier engine is ended by.
for (const [how, call, expected, home] of [
    ['spawnSync', [ENGINE, TOOL_ARGS, { argv0: 'tool' }], [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], { env: routedEnv(ENGINE) }]],
    ['execFile', [ENGINE, ['--version'], { argv0: 'tool' }, CALLBACK], [BIONIC_LINKER, [ENGINE, '--version'], { env: routedEnv(ENGINE) }, null]],
    ['execFileSync', [ENGINE, ['--version'], { argv0: 'tool' }], [BIONIC_LINKER, [ENGINE, '--version'], { env: routedEnv(ENGINE) }]],
    ['spawn', [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], { argv0: 'tool' }], [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], { env: routedEnv(ENGINE) }]],
    ['spawn', [HUB_ENGINE, ['--version'], { argv0: 'agy' }], [BIONIC_LINKER, [HUB_ENGINE, '--version'], {}], HUB_HOME],
    ['execFileSync', [HUB_ENGINE, ['--hub', '--hub-port=41000'], { argv0: 'agy' }], [BIONIC_LINKER, [HUB_ENGINE, '--hub', '--hub-port=41000'], {}], HUB_HOME],
]) {
    assert.deepStrictEqual(callUnder(how, call, home ? { home } : {}), [how, ...expected], `${how} of ${call[0]}: argv0 reached a start through the linker`);
    checked++;
}

// A command run in a shell is made as written: no interceptor and no program path, whatever the shell is asked
// to start, an Android program in the app's storage included. With the interceptor in its environment the
// shell would start any script or program there that a command names, which Android's refusal of such an exec
// is what prevents. A program the shim itself starts through the linker, in the same run, gets both.
for (const [how, call, expected] of [
    ['exec', ['cc hello.c -o hello', CALLBACK], ['execFile', 'cc hello.c -o hello', { shell: true }, null]],
    ['exec', [`${ENGINE} --flag`, { cwd: tmp }, CALLBACK], ['execFile', `${ENGINE} --flag`, { cwd: tmp, shell: true }, null]],
    ['execSync', ['cc hello.c'], ['execSync', 'cc hello.c']],
    ['execSync', [`${SCRIPT} --flag`, { env: { PATH: '/usr/bin' } }], ['execSync', `${SCRIPT} --flag`, { env: { PATH: '/usr/bin' } }]],
    ['spawn', [ENGINE, TOOL_ARGS, { shell: true }], ['spawn', ENGINE, TOOL_ARGS, { shell: true }]],
    ['spawnSync', [`${ENGINE} --flag`, { shell: true }], ['spawnSync', `${ENGINE} --flag`, { shell: true }]],
    ['execFile', [ENGINE, ['--version'], { shell: true }, CALLBACK], ['execFile', ENGINE, ['--version'], { shell: true }, null]],
    ['execFileSync', [ENGINE, ['--version'], { shell: '/bin/sh' }], ['execFileSync', ENGINE, ['--version'], { shell: '/bin/sh' }]],
]) {
    assert.deepStrictEqual(callUnder(how, call), expected, `${how} in a shell: the shell was given an environment, the interceptor in it`);
    checked++;
}
for (const how of ['spawn', 'spawnSync', 'execFile', 'execFileSync']) {
    const [, linker, args, options] = callUnder(how, [ENGINE, TOOL_ARGS]);
    assert.deepStrictEqual([linker, args, options && options.env && options.env.LD_PRELOAD, options && options.env && options.env.TERMUX_EXEC__PROC_SELF_EXE],
        [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], PRELOAD, ENGINE], `${how} of an Android program in the app's storage, without a shell: not given the interceptor and its own path`);
    checked++;
}
// The linker written out by the caller is the start of the program after it, and gets what such a start gets.
// A loader option in front of the program (--list) runs nothing, and a program the linker is not for is left alone.
for (const [label, call, expected] of [
    ['the linker by its path', [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], SPAWN_OPTIONS], [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], ROUTED]],
    ['the linker by its name', ['linker64', [ENGINE, ...TOOL_ARGS], SPAWN_OPTIONS], [BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], ROUTED]],
    ['the linker by its real path', ['/apex/com.android.runtime/bin/linker64', [ENGINE], SPAWN_OPTIONS], [BIONIC_LINKER, [ENGINE], ROUTED]],
]) {
    assert.deepStrictEqual(callUnder('spawn', call), ['spawn', ...expected], `${label}: not started as the program after it`);
    checked++;
}
for (const [label, call] of [
    ['a loader option before the program', [BIONIC_LINKER, ['--list', ENGINE], SPAWN_OPTIONS]],
    ['a musl program after the linker', [BIONIC_LINKER, [MUSL, ...TOOL_ARGS], SPAWN_OPTIONS]],
    ['a program outside the data directory after the linker', [BIONIC_LINKER, [OUTSIDE], SPAWN_OPTIONS]],
    ['the linker with nothing after it', [BIONIC_LINKER, [], SPAWN_OPTIONS]],
]) {
    assert.deepStrictEqual(callUnder('spawn', call), ['spawn', ...call], `${label}: the call was changed`);
    checked++;
}

// With the setting off, a start of something in ~/.gemini is held back in each way the call can spell it out (a
// program told what to run, or a name a shell builds, is not seen). Written out with the linker,
// the call is made without it: the program is started directly, which Android refuses with EACCES as it refuses
// every other start of it, and in each API's own shape. A command run in a shell gets no interceptor, so the
// shell's own start of something there is refused as well; one that names something in ~/.gemini and also names
// the linker, which the shell could run, is made as that direct start instead.
const OFF_BIN = path.join(OFF_HOME, '.gemini/bin');
for (const [how, call, expected] of [
    ['spawn', [BIONIC_LINKER, [OFF_ENGINE, ...HUB_ARGS], SPAWN_OPTIONS], [OFF_ENGINE, HUB_ARGS, SPAWN_OPTIONS]],
    ['spawn', ['linker64', [OFF_ENGINE, '--remote-control']], [OFF_ENGINE, ['--remote-control']]],
    ['spawn', [BIONIC_LINKER, ['./agy', '--hub'], { cwd: OFF_BIN }], [OFF_ENGINE, ['--hub'], { cwd: OFF_BIN }]],
    ['spawnSync', [BIONIC_LINKER, [OFF_ENGINE, ...HUB_ARGS], SPAWN_OPTIONS], [OFF_ENGINE, HUB_ARGS, SPAWN_OPTIONS]],
    ['execFile', [BIONIC_LINKER, [OFF_ENGINE, '--hub'], CALLBACK], [OFF_ENGINE, ['--hub'], null]],
    ['promisified', [BIONIC_LINKER, [OFF_ENGINE, '--hub'], VERSION_OPTIONS], [OFF_ENGINE, ['--hub'], VERSION_OPTIONS]],
    ['execFileSync', [BIONIC_LINKER, [AGY_NEXT, '--version']], [AGY_NEXT, ['--version']]],
    // Node coerces non-string argv entries (Buffer or objects with toString) to text before exec, so the linker
    // gate must coerce rest[0][0] as well instead of treating only typeof === 'string' as linked.
    ['spawn', [BIONIC_LINKER, [bytes(OFF_ENGINE), ...HUB_ARGS], SPAWN_OPTIONS], [OFF_ENGINE, HUB_ARGS, SPAWN_OPTIONS]],
    ['spawn', [BIONIC_LINKER, [{ '@toString': OFF_ENGINE }, '--hub'], SPAWN_OPTIONS], [OFF_ENGINE, ['--hub'], SPAWN_OPTIONS]],
    ['spawn', [BIONIC_LINKER, [bytes('./agy'), '--hub'], { cwd: OFF_BIN }], [OFF_ENGINE, ['--hub'], { cwd: OFF_BIN }]],
    ['spawnSync', ['linker64', [bytes(OFF_ENGINE), '--remote-control']], [OFF_ENGINE, ['--remote-control']]],
    // Non-string argv entries must be frozen into plain strings once so a stateful toString() cannot
    // present a benign value during gate checks and mutate to the engine or a disallowed flag at exec.
    ['spawn', [BIONIC_LINKER, [{ '@flip': ['/usr/bin/true', OFF_ENGINE] }, '--hub'], SPAWN_OPTIONS], [BIONIC_LINKER, ['/usr/bin/true', '--hub'], SPAWN_OPTIONS]],
    ['spawn', [BIONIC_LINKER, [{ '@flip': ['--list', OFF_ENGINE] }, '--hub'], SPAWN_OPTIONS], [BIONIC_LINKER, ['--list', '--hub'], SPAWN_OPTIONS]],
    // Async spawn and execFile do not throw on uncoercible argv elements (Symbol, null-prototype object,
    // or throwing toString): Node's ProcessWrap turns them into '' and still executes, so the gate must
    // substitute '' and refuse rather than bailing out.
    ['spawn', [BIONIC_LINKER, [OFF_ENGINE, '--hub', '@symbol'], SPAWN_OPTIONS], [OFF_ENGINE, ['--hub', ''], SPAWN_OPTIONS]],
    ['spawn', [BIONIC_LINKER, [OFF_ENGINE, '--hub', '@nullproto'], SPAWN_OPTIONS], [OFF_ENGINE, ['--hub', ''], SPAWN_OPTIONS]],
    ['spawn', [BIONIC_LINKER, [OFF_ENGINE, '--hub', '@throw'], SPAWN_OPTIONS], [OFF_ENGINE, ['--hub', ''], SPAWN_OPTIONS]],
    ['execFile', [BIONIC_LINKER, [OFF_ENGINE, '--hub', '@symbol'], CALLBACK], [OFF_ENGINE, ['--hub', ''], null]],
    ['execFile', [BIONIC_LINKER, [OFF_ENGINE, '--hub', '@nullproto'], CALLBACK], [OFF_ENGINE, ['--hub', ''], null]],
    ['execFile', [BIONIC_LINKER, [OFF_ENGINE, '--hub', '@throw'], CALLBACK], [OFF_ENGINE, ['--hub', ''], null]],
]) {
    assert.deepStrictEqual(callUnder(how, call, { home: OFF_HOME }), [how, ...expected], `setting off, ${how} of the linker with a program in ~/.gemini was not made without the linker`);
    checked++;
}
assert.deepStrictEqual(
    callUnder('spawn', [HUB_ENGINE, [{ '@flip': ['--version', '--remote-control'] }], SPAWN_OPTIONS], { home: HUB_HOME }),
    ['spawn', BIONIC_LINKER, [HUB_ENGINE, '--version'], SPAWN_OPTIONS],
    'setting on, a stateful argv object was not frozen to its inspected string before passing to the linker',
);
for (const [how, call, expected] of [
    ['spawn', [BIONIC_LINKER, [HUB_ENGINE, '--remote-control', '@symbol'], SPAWN_OPTIONS], [HUB_ENGINE, ['--remote-control', ''], SPAWN_OPTIONS]],
    ['spawn', [BIONIC_LINKER, [HUB_ENGINE, '--hub', '--hub-port=41000', '@symbol'], SPAWN_OPTIONS], [HUB_ENGINE, ['--hub', '--hub-port=41000', ''], SPAWN_OPTIONS]],
    ['execFile', [BIONIC_LINKER, [HUB_ENGINE, '--remote-control', '@symbol'], CALLBACK], [HUB_ENGINE, ['--remote-control', ''], null]],
]) {
    assert.deepStrictEqual(
        callUnder(how, call, { home: HUB_HOME }),
        [how, ...expected],
        `setting on, ${how} with an uncoercible argv element bypassed hub validation`,
    );
    checked++;
}
checked++;
for (const [how, call] of [
    ['spawnSync', [OFF_ENGINE, HUB_ARGS, SPAWN_OPTIONS]],
    ['execFileSync', [OFF_ENGINE, ['--remote-control']]],
]) {
    assert.deepStrictEqual(callUnder(how, call, { home: OFF_HOME }), [how, ...call], `setting off, ${how} of a program in ~/.gemini was changed, so it runs`);
    checked++;
}
// The version check is let through whichever way it is written.
assert.deepStrictEqual(callUnder('spawnSync', [BIONIC_LINKER, [OFF_ENGINE, '--version']], { home: OFF_HOME }),
    ['spawnSync', BIONIC_LINKER, [OFF_ENGINE, '--version']],
    'setting off, the version check written with the linker was held back, or given the interceptor');
checked++;
for (const [how, call, expected] of [
    ['exec', [`${OFF_ENGINE} --hub`, CALLBACK], ['execFile', `${OFF_ENGINE} --hub`, { shell: true }, null]],
    ['execSync', ['~/.gemini/bin/agy --hub'], ['execSync', '~/.gemini/bin/agy --hub']],
    ['execSync', ['cd "$HOME/.gemini/bin" && ./agy --hub'], ['execSync', 'cd "$HOME/.gemini/bin" && ./agy --hub']],
    ['spawn', [OFF_ENGINE, ['--hub'], { shell: true }], ['spawn', OFF_ENGINE, ['--hub'], { shell: true }]],
    ['exec', [`${BIONIC_LINKER} ${OFF_ENGINE} --hub`, CALLBACK], ['execFile', OFF_ENGINE, [], { shell: false }, null]],
    ['execSync', ['linker64 "$HOME/.gemini/bin/agy" --hub'], ['execFileSync', OFF_ENGINE, [], { shell: false }]],
    ['execSync', ['cd ~/.gemini/bin; /system/bin/linker64 ./agy'], ['execFileSync', OFF_BIN, [], { shell: false }]],
    ['execSync', ['X=$HOME/.gem""ini/bin/agy; linker64 $X --hub'], ['execFileSync', path.join(OFF_HOME, '.gemini'), [], { shell: false }]],
    // The shell takes quotes and backslashes out of a word, and a backslash before a line break out with the
    // break, so each of these runs /system/bin/linker64 ~/.gemini/bin/agy (checked with sh -c).
    ['execSync', ['link""er64 ~/.gemini/bin/agy --hub'], ['execFileSync', OFF_ENGINE, [], { shell: false }]],
    ['execSync', ['/system/bin/linker\\64 ~/.gemini/bin/agy --hub'], ['execFileSync', OFF_ENGINE, [], { shell: false }]],
    ['execSync', ['linker64 ~/.gem\\ini/bin/agy --hub'], ['execFileSync', OFF_ENGINE, [], { shell: false }]],
    ['execSync', ['/system/bin/link\\\ner64 ~/.gem\\\nini/bin/agy --hub'], ['execFileSync', OFF_ENGINE, [], { shell: false }]],
    ['exec', [`linker\\64 ${OFF_ENGINE} --hub`, CALLBACK], ['execFile', OFF_ENGINE, [], { shell: false }, null]],
    ['spawn', [BIONIC_LINKER, [OFF_ENGINE, '--hub'], { ...SPAWN_OPTIONS, shell: true }], ['spawn', OFF_ENGINE, [], { ...SPAWN_OPTIONS, shell: false }]],
    ['spawnSync', [`${BIONIC_LINKER} ${OFF_ENGINE}`, { shell: true }], ['spawnSync', OFF_ENGINE, [], { shell: false }]],
    ['execFile', [BIONIC_LINKER, [OFF_ENGINE], { shell: true }, CALLBACK], ['execFile', OFF_ENGINE, [], { shell: false }, null]],
    ['execFileSync', [BIONIC_LINKER, [OFF_ENGINE], { shell: true }], ['execFileSync', OFF_ENGINE, [], { shell: false }]],
]) {
    assert.deepStrictEqual(callUnder(how, call, { home: OFF_HOME }), expected, `setting off, ${how} of a shell command naming ~/.gemini: ${JSON.stringify(call)}`);
    checked++;
}
// The command is read in the directory Node runs it in, not one its options inherit, which Node ignores: run
// from ~/.gemini/bin, `linker64 ./agy` is the engine.
assert.deepStrictEqual(asNode('linker64 ./agy --hub', 'cwd', { home: OFF_HOME, cwd: OFF_BIN }),
    ['execFileSync', path.join(fs.realpathSync(OFF_BIN), 'agy'), [], { shell: false }],
    'setting off, a command was read from a working directory its options inherit, which Node ignores, and started the engine');
checked++;
// Node takes a Uint8Array for the cwd as well. A synchronous start runs in the string Node makes of it, and an
// asynchronous one ignores it and runs in the process's own directory, as the editor server's extension host does
// in the app's files directory, from which home-<n>/.gemini/bin/agy is the engine. The call is read where it runs.
// A cwd that cannot be read as a path at all, which Node refuses, is taken as one that could be in ~/.gemini.
{
    const FILES = path.join(REAL_DATA, 'files');
    const FROM_FILES = path.relative(FILES, OFF_ENGINE);
    for (const [how, call, expected, cwd] of [
        ['execSync', ['linker64 ./agy --hub', { cwd: bytes(OFF_BIN) }], ['execFileSync', OFF_ENGINE, [], { cwd: asBuffer(OFF_BIN), shell: false }]],
        ['spawnSync', ['linker64', ['./agy', '--hub'], { cwd: bytes(OFF_BIN) }], ['spawnSync', OFF_ENGINE, ['--hub'], { cwd: asBuffer(OFF_BIN) }]],
        ['execFileSync', ['linker64', ['./agy', '--hub'], { cwd: bytes(OFF_BIN) }], ['execFileSync', OFF_ENGINE, ['--hub'], { cwd: asBuffer(OFF_BIN) }]],
        ['spawn', ['linker64', [FROM_FILES, '--hub'], { cwd: bytes('/') }], ['spawn', path.join(fs.realpathSync(FILES), FROM_FILES), ['--hub'], { cwd: asBuffer('/') }], FILES],
        ['exec', ['linker64 ./agy --hub', { cwd: bytes('/') }, CALLBACK], ['execFile', path.join(fs.realpathSync(OFF_BIN), 'agy'), [], { cwd: asBuffer('/'), shell: false }, null], OFF_BIN],
        // Asynchronous, so not where the Buffer points: ./agy is not the engine here.
        ['spawn', ['linker64', ['./agy', '--hub'], { cwd: bytes(OFF_BIN) }], ['spawn', 'linker64', ['./agy', '--hub'], { cwd: asBuffer(OFF_BIN) }], tmp],
        // A Uint8Array that is not a Buffer is made a string of its numbers, a directory below this one: not the engine.
        ['spawnSync', ['linker64', ['./agy', '--hub'], { cwd: view(OFF_BIN) }], ['spawnSync', 'linker64', ['./agy', '--hub'], { cwd: asView(OFF_BIN) }], tmp],
        ['execSync', ['linker64 ./agy --hub', { cwd: {} }], ['execFileSync', path.join(OFF_HOME, '.gemini'), [], { cwd: {}, shell: false }]],
        ['spawnSync', ['linker64', ['./agy', '--hub'], { cwd: {} }], ['spawnSync', './agy', ['--hub'], { cwd: {} }]],
    ]) {
        assert.deepStrictEqual(callUnder(how, call, { home: OFF_HOME, cwd }), expected,
            `setting off, ${how} of ${shown(call)} from ${cwd || 'the default directory'}: not read in the directory Node runs it in`);
        checked++;
    }
    // With no ~/.gemini there is nothing such a cwd could reach: the calls go as they would from anywhere else.
    for (const [how, call, expected] of [
        ['execSync', ['linker64 ./agy --hub', { cwd: {} }], ['execSync', 'linker64 ./agy --hub', { cwd: {} }]],
        ['spawnSync', ['linker64', ['./agy', '--hub'], { cwd: {} }], ['spawnSync', 'linker64', ['./agy', '--hub'], { cwd: {} }]],
        ['spawn', [ENGINE, TOOL_ARGS, { cwd: {} }], ['spawn', BIONIC_LINKER, [ENGINE, ...TOOL_ARGS], { cwd: {}, env: routedEnv(ENGINE) }]],
    ]) {
        assert.deepStrictEqual(callUnder(how, call), expected, `no ~/.gemini, ${how} of ${shown(call)}: changed for a cwd that is not a path`);
        checked++;
    }
}
// What the rule above follows, measured on Node itself without the shim: a synchronous start runs in the string
// Node makes of a Uint8Array (one that is not a Buffer names a directory of its numbers), an asynchronous one
// ignores it. If a Node release changes this, the reading above has to change with it.
{
    const NODE_CWD = path.join(tmp, 'node-cwd.js');
    fs.writeFileSync(NODE_CWD,
        "const cp = require('child_process');\n" +
        "const where = ['-e', 'process.stdout.write(process.cwd())'];\n" +
        "const dir = process.argv[2];\n" +
        "const sync = String(cp.spawnSync(process.execPath, where, { cwd: Buffer.from(dir) }).stdout);\n" +
        "const view = String(cp.spawnSync(process.execPath, where, { cwd: Uint8Array.from(Buffer.from('/')) }).stdout);\n" +
        "let out = '';\nconst child = cp.spawn(process.execPath, where, { cwd: Buffer.from(dir) });\n" +
        "child.stdout.on('data', (d) => { out += d; });\nchild.on('close', () => console.log(JSON.stringify({ sync, view, async: out })));\n");
    fs.mkdirSync(path.join(tmp, '47'), { recursive: true });
    const seen = JSON.parse(execFileSync(process.execPath, [NODE_CWD, OFF_BIN], { cwd: tmp, encoding: 'utf8', timeout: 10000 }));
    assert.deepStrictEqual(seen, { sync: fs.realpathSync(OFF_BIN), view: fs.realpathSync(path.join(tmp, '47')), async: fs.realpathSync(tmp) },
        'Node no longer runs a start in the directory a Uint8Array cwd names the way the shim reads it');
    checked++;
}
// A program started through the linker that could reach ~/.gemini gets no interceptor in either state of the
// setting, so what it would start there is refused as it was before: one that is handed a path there, runs
// there or searches a PATH directory there, and the engine itself. A shell gets none at any time, these
// included. The setting decides only what is refused; the interceptor is what would let any of these start the
// engine.
const WRAPPER = put(path.join(REAL_DATA, 'files/usr/bin/wrapper'), elf64(BIONIC_LINKER));
for (const home of [OFF_HOME, HUB_HOME]) {
    const bin = path.join(home, '.gemini/bin');
    const engine = engineIn(home);
    const searching = { env: { PATH: `${bin}:/usr/bin` } };
    for (const [how, call, expected, cwd] of [
        ['execSync', ['agy --hub', searching], ['execSync', 'agy --hub', searching]],
        ['execSync', ['make', { cwd: bin }], ['execSync', 'make', { cwd: bin }]],
        ['exec', ['agy --hub', { cwd: bin }, CALLBACK], ['execFile', 'agy --hub', { cwd: bin, shell: true }, null]],
        ['execSync', [`${engine} --version`], ['execSync', `${engine} --version`]],
        ['spawn', [WRAPPER, [engine, '--hub']], ['spawn', BIONIC_LINKER, [WRAPPER, engine, '--hub']]],
        ['spawn', [WRAPPER, ['./agy'], { cwd: bin }], ['spawn', BIONIC_LINKER, [WRAPPER, './agy'], { cwd: bin }]],
        ['spawnSync', [WRAPPER, [], searching], ['spawnSync', BIONIC_LINKER, [WRAPPER], searching]],
        ['execFileSync', [WRAPPER, [path.join(home, '.gemini')]], ['execFileSync', BIONIC_LINKER, [WRAPPER, path.join(home, '.gemini')]]],
        // Runs there, with arguments that name nothing there: the directory, and the same as a file URL.
        ['spawn', [WRAPPER, ['--flag'], { cwd: bin }], ['spawn', BIONIC_LINKER, [WRAPPER, '--flag'], { cwd: bin }]],
        ['spawn', [WRAPPER, ['--flag'], { cwd: { '@url': bin } }], ['spawn', BIONIC_LINKER, [WRAPPER, '--flag'], { cwd: require('url').pathToFileURL(bin).href }]],
        // The same as a Buffer, which a synchronous start runs in, and an asynchronous start run from there, whatever
        // its Buffer names.
        ['spawnSync', [WRAPPER, ['--flag'], { cwd: bytes(bin) }], ['spawnSync', BIONIC_LINKER, [WRAPPER, '--flag'], { cwd: asBuffer(bin) }]],
        ['execFileSync', [WRAPPER, ['./agy'], { cwd: bytes(bin) }], ['execFileSync', BIONIC_LINKER, [WRAPPER, './agy'], { cwd: asBuffer(bin) }]],
        ['spawn', [WRAPPER, ['--flag'], { cwd: bytes('/') }], ['spawn', BIONIC_LINKER, [WRAPPER, '--flag'], { cwd: asBuffer('/') }], bin],
        // A cwd that is not a path, which may be anywhere.
        ['spawn', [WRAPPER, ['--flag'], { cwd: {} }], ['spawn', BIONIC_LINKER, [WRAPPER, '--flag'], { cwd: {} }]],
    ]) {
        assert.deepStrictEqual(callUnder(how, call, { home, cwd }), expected,
            `setting ${home === HUB_HOME ? 'on' : 'off'}, ${how} of ${shown(call)}: given the interceptor, which would start what it reaches in ~/.gemini`);
        checked++;
    }
}
// With the setting on, the start written out with the linker is the start the relay knows: its port is swapped,
// and an argument outside the extension's own keeps it from starting, as it does without the linker.
{
    const relayed = callUnder('spawn', [BIONIC_LINKER, [HUB_ENGINE, ...HUB_ARGS], SPAWN_OPTIONS], { home: HUB_HOME });
    assert.strictEqual(relayed[1], BIONIC_LINKER, 'setting on, the hub written with the linker was not started through it');
    assert.ok(/^--hub-port=\d+$/.test(relayed[2][2]) && relayed[2][2] !== '--hub-port=41000', `setting on, the hub written with the linker kept the extension's port: ${relayed[2][2]}`);
    const extra = [HUB_ENGINE, [...HUB_ARGS, '--remote-control'], SPAWN_OPTIONS];
    assert.deepStrictEqual(callUnder('spawn', [BIONIC_LINKER, [extra[0], ...extra[1]], SPAWN_OPTIONS], { home: HUB_HOME }), ['spawn', ...extra],
        'setting on, a hub start with an argument the extension does not build, written with the linker, was started');
    checked += 2;
}

// The refusals, made for real: each API reports EACCES for the engine, where the linker written out (absent on
// this host, which is what the start would have run) answered something else.
const REAL_MAIN = path.join(tmp, 'real-main.js');
fs.writeFileSync(
    REAL_MAIN,
    "const cp = require('child_process');\n" +
        "const [how, raw] = JSON.parse(process.argv[2], (k, v) => (v && Array.isArray(v['@buffer']) ? Buffer.from(v['@buffer']) : v));\n" +
        "const call = raw.map((x) => (x === '@callback' ? function () {} : x === '@undefined' ? undefined : x));\n" +
        "const done = (what) => { console.log(JSON.stringify(what)); process.exit(0); };\n" +
        "setTimeout(() => done('no answer'), 8000);\n" +
        "try {\n" +
        "    if (how === 'spawn') { const c = cp.spawn(...call); c.on('error', (e) => done(e.code)); c.on('exit', (code) => done('exit ' + code)); }\n" +
        "    else if (how === 'spawnSync') { const r = cp.spawnSync(...call); done(r.error ? r.error.code : 'exit ' + r.status); }\n" +
        "    else if (how === 'execFile' || how === 'exec') cp[how](...call, (e) => done(e ? e.code : 'exit 0'));\n" +
        "    else { cp[how](...call); done('exit 0'); }\n" +
        "} catch (e) { done(e.code !== undefined ? e.code : 'exit ' + e.status); }\n",
);
const realUnder = (how, call, cwd = tmp) => JSON.parse(execFileSync(
    process.execPath,
    ['--require', FAKE_ANDROID, '--require', FIX, REAL_MAIN, JSON.stringify([how, call])],
    { env: childEnv({ home: OFF_HOME }), encoding: 'utf8', timeout: 15000, cwd },
).trim().split('\n').pop());
for (const [how, call, cwd] of [
    ['spawn', [BIONIC_LINKER, [OFF_ENGINE, '--hub']]],
    ['spawn', [BIONIC_LINKER, [bytes(OFF_ENGINE), '--hub']]],
    ['spawnSync', [BIONIC_LINKER, [OFF_ENGINE, '--hub']]],
    ['execFile', [BIONIC_LINKER, [OFF_ENGINE, '--hub']]],
    ['execFileSync', [BIONIC_LINKER, [OFF_ENGINE, '--hub'], { stdio: 'ignore' }]],
    ['exec', [`${BIONIC_LINKER} ${OFF_ENGINE} --hub`]],
    ['execSync', ['linker64 ~/.gemini/bin/agy --hub', { stdio: 'ignore' }]],
    ['execSync', ['X=$HOME/.gem""ini/bin/agy; linker64 $X --hub', { stdio: 'ignore' }]],
    ['execSync', ['/system/bin/linker\\64 ~/.gem\\ini/bin/agy --hub', { stdio: 'ignore' }]],
    ['spawn', [BIONIC_LINKER, [OFF_ENGINE, '--hub'], { shell: true }]],
    // A Buffer for the cwd: where a synchronous start runs, and an asynchronous start run from the files directory.
    ['execSync', ['linker64 ./agy --hub', { cwd: bytes(OFF_BIN), stdio: 'ignore' }]],
    ['spawnSync', ['linker64', ['./agy', '--hub'], { cwd: bytes(OFF_BIN) }]],
    ['spawn', ['linker64', [path.relative(path.join(REAL_DATA, 'files'), OFF_ENGINE), '--hub'], { cwd: bytes('/') }], path.join(REAL_DATA, 'files')],
]) {
    assert.strictEqual(realUnder(how, call, cwd), 'EACCES', `setting off, ${how} of ${shown(call)} did not fail with EACCES`);
    checked++;
}
// Options Node refuses are left for Node to refuse: made through the linker, the start would succeed where the
// start as written throws. execSync is the exception: Node runs its command whatever stands in place of the
// options (it reads their own properties, and a string, a number or a function has none it uses), so the
// command is read as any other, and with the setting off it does not start the engine through the shell.
for (const [label, how, call, want] of [
    ['an Android program with a string for its argument list', 'spawn', [ENGINE, 'x'], 'ERR_INVALID_ARG_TYPE'],
    ['an Android program with a string for its argument list', 'spawnSync', [ENGINE, 'x'], 'ERR_INVALID_ARG_TYPE'],
    ['an Android program with a number for its options', 'execFileSync', [ENGINE, [], 5], 'ERR_INVALID_ARG_TYPE'],
    ['an Android program with a list for its options', 'spawn', [ENGINE, [], []], 'ERR_INVALID_ARG_TYPE'],
    // spawn and spawnSync take no callback, and refuse null options; execFileSync hands a function after its
    // argument list to spawnSync as the options.
    ['an Android program with null for its options', 'spawn', [ENGINE, [], null], 'ERR_INVALID_ARG_TYPE'],
    ['an Android program with null for its options', 'spawnSync', [ENGINE, [], null], 'ERR_INVALID_ARG_TYPE'],
    ['an Android program with null for its options after no argument list', 'spawn', [ENGINE, '@undefined', null], 'ERR_INVALID_ARG_TYPE'],
    ['an Android program with a function for its options', 'spawn', [ENGINE, [], '@callback'], 'ERR_INVALID_ARG_TYPE'],
    ['an Android program with a function for its options', 'spawnSync', [ENGINE, [], '@callback'], 'ERR_INVALID_ARG_TYPE'],
    ['an Android program with a function for its argument list', 'spawn', [ENGINE, '@callback'], 'ERR_INVALID_ARG_TYPE'],
    ['an Android program with a function for its argument list', 'spawnSync', [ENGINE, '@callback'], 'ERR_INVALID_ARG_TYPE'],
    ['an Android program with a function for its options', 'execFileSync', [ENGINE, [], '@callback'], 'ERR_INVALID_ARG_TYPE'],
    ['an Android program with a function for its options after a null argument list', 'execFileSync', [ENGINE, null, '@callback'], 'ERR_INVALID_ARG_TYPE'],
    ['the linker and the engine with a string for the options', 'execSync', ['linker64 ~/.gemini/bin/agy --hub', 'x'], 'EACCES'],
    ['the linker and the engine with a number for the options', 'execSync', ['linker64 ~/.gemini/bin/agy --hub', 5], 'EACCES'],
    ['the linker and the engine with a function for the options', 'execSync', ['linker64 ~/.gemini/bin/agy --hub', '@callback'], 'EACCES'],
]) {
    const got = realUnder(how, call);
    assert.strictEqual(got, want, `setting off, ${how} of ${label}: ended in ${got}, wanted ${want}`);
    checked++;
}

// The gate's own reading, for the process monitor extension to compare with what VS Code shows: the file it
// reads, and whether it reads the setting as on. A file VS Code reads with an error it works around (a missing
// comma) is off here.
{
    const GATE_MAIN = path.join(tmp, 'gate-main.js');
    fs.writeFileSync(GATE_MAIN, "const gate = globalThis[Symbol.for('vscodroid.engineGate')];\nconsole.log(JSON.stringify(gate ? { on: gate.engineTurnedOn(), file: gate.settingsFile } : null));\n");
    const gateIn = (home) => spawnedUnder([], { main: GATE_MAIN, home });
    const lenient = homeWith('{\n    "update.mode": "none"\n    "vscodroid.antigravity.runEngine": true\n}\n');
    assert.deepStrictEqual(gateIn(HUB_HOME), { on: true, file: machineSettings(HUB_HOME) }, 'the gate does not say it reads the setting as on');
    assert.deepStrictEqual(gateIn(OFF_HOME), { on: false, file: machineSettings(OFF_HOME) }, 'the gate does not say it reads the setting as off');
    assert.deepStrictEqual(gateIn(lenient), { on: false, file: machineSettings(lenient) }, 'a file with a missing comma read as on');
    checked += 3;
}

// The hub start the relay knows, with the setting on: the extension's own arguments, a CSRF token and one
// --add-dir per folder among them, keep everything but the port; anything else a folder's
// antigravity.serverArgs could add to them, a second copy of one of them included (the flag parser keeps
// the last), is refused, the call left as written.
// The token is the extension's own as a flag only for an engine older than 1.5.2, and then the call's own
// environment carries the same value (ANTIGRAVITY_SERVER_CSRF_TOKEN, which it always sets); a current engine
// gets the environment alone.
const FOLDERS = ['--add-dir=/data/user/0/com.vscodroid/files/projects/a', '--add-dir=/data/user/0/com.vscodroid/files/projects/b c'];
const EXTENSION_ARGS = ['--hub', '--hub-port=41000', '--app_data_dir=antigravity', '--csrf_token=0123abcd', ...FOLDERS];
const CURRENT_ENGINE_ARGS = ['--hub', '--hub-port=41000', '--app_data_dir=antigravity', ...FOLDERS];
const withToken = (value) => ({ ...SPAWN_OPTIONS, env: { PATH: '/usr/bin', ANTIGRAVITY_SERVER_CSRF_TOKEN: value } });
for (const [label, args, options] of [
    ['an engine older than 1.5.2, token as flag and in the environment', EXTENSION_ARGS, withToken('0123abcd')],
    ['a current engine, token in the environment only', CURRENT_ENGINE_ARGS, withToken('0123abcd')],
    ['a call with no environment of its own, and no token flag', CURRENT_ENGINE_ARGS, SPAWN_OPTIONS],
]) {
    const got = spawnedUnder([HUB_ENGINE, args, options], { home: HUB_HOME });
    assert.strictEqual(got[0], BIONIC_LINKER, `the extension's own hub start (${label}) was refused`);
    assert.ok(/^--hub-port=\d+$/.test(got[1][2]) && got[1][2] !== '--hub-port=41000', `(${label}) the engine kept the port the extension chose: ${got[1][2]}`);
    assert.deepStrictEqual([got[1][0], got[1][1], ...got[1].slice(3)], [HUB_ENGINE, ...args.filter((a) => a !== '--hub-port=41000')], `(${label}) the extension's other arguments changed`);
    checked++;
}
// A token flag with any value other than the environment's is a folder's: it would choose the token the
// engine and the page share. With no flag built by the extension for a current engine, any flag is the folder's.
for (const [label, args, options] of [
    ['a flag with no environment to match', EXTENSION_ARGS, SPAWN_OPTIONS],
    ['a flag whose value is not the environment\'s', EXTENSION_ARGS, withToken('another')],
    ['a flag with an environment that is not an object', EXTENSION_ARGS, { ...SPAWN_OPTIONS, env: 'ANTIGRAVITY_SERVER_CSRF_TOKEN=0123abcd' }],
    ['an empty flag and an empty environment value', [...CURRENT_ENGINE_ARGS, '--csrf_token='], withToken('')],
    ['an empty flag and no environment value', [...CURRENT_ENGINE_ARGS, '--csrf_token='], { ...SPAWN_OPTIONS, env: { PATH: '/usr/bin' } }],
    ['a folder\'s flag beside the real token in the environment of a current engine', [...CURRENT_ENGINE_ARGS, '--csrf_token=chosen-by-the-folder'], withToken('0123abcd')],
    ['a folder\'s flag when the call has no environment', [...CURRENT_ENGINE_ARGS, '--csrf_token=chosen-by-the-folder'], SPAWN_OPTIONS],
    ['the extension\'s flag twice', [...EXTENSION_ARGS, '--csrf_token=0123abcd'], withToken('0123abcd')],
]) {
    const call = [HUB_ENGINE, args, options];
    assert.deepStrictEqual(spawnedUnder(call, { home: HUB_HOME }), call, `a hub start with ${label} was started`);
    checked++;
}
for (const [label, extra] of [
    ['a second port, which the flag parser takes', ['--hub-port=8080']],
    ['a second port spelled with one dash', ['-hub-port=8080']],
    ['a port given as a separate word', ['--hub-port', '8080']],
    ['a second data directory', ['--app_data_dir=/sdcard/antigravity']],
    ['--hub twice', ['--hub']],
    ['an argument the extension does not build', ['--remote-control']],
    ['a bare word', ['serve']],
    // Node starts an argument that is not a string as its text, so it is read as one, never thrown on.
    ['a number, which Node starts as its text', [8080]],
]) {
    // With the token in the environment, which the first loop above starts, so the added argument is the only
    // thing that can refuse the start. Without it every case here was refused for the token flag alone.
    const call = [HUB_ENGINE, [...EXTENSION_ARGS, ...extra], withToken('0123abcd')];
    assert.deepStrictEqual(spawnedUnder(call, { home: HUB_HOME }), call, `a hub start with ${label} was started`);
    checked++;
}
const hubRun = runHubDriver(HUB_DRIVER);
assert.deepStrictEqual(hubRun.noteAfterSpawn, { pid: 4242, port: hubRun.back }, 'the note does not name the engine that was started and the port it was given');
assert.strictEqual(hubRun.noteAfterExit, false, 'the note outlived the engine it names');
assert.strictEqual(hubRun.spawned[0], BIONIC_LINKER, 'the engine was not started through the linker');
assert.strictEqual(hubRun.spawned[1][0], HUB_ENGINE);
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
assert.strictEqual(hubRun.foreignHost.status, 403, 'a request naming another host reached the engine: the relay rewrites Host, so DNS rebinding would get through');
assert.strictEqual(hubRun.foreignHostPort.status, 403, 'a request naming this host with another port was let through');
assert.strictEqual(hubRun.noHostReached, 0, 'the engine saw a request that named another host');
assert.strictEqual(hubRun.localhostHost.status, 200, 'a request naming localhost and the relay\'s own port was refused');
assert.ok(/^HTTP\/1\.1 403 /.test(hubRun.foreignUpgrade), 'a WebSocket upgrade naming another host was tunnelled: ' + hubRun.foreignUpgrade);
assert.deepStrictEqual(hubRun.uncaughtAfterRefusedUpgrade, [], 'a client that reset the connection after a refused upgrade made the relay raise an error nobody listens for');
assert.strictEqual(hubRun.badReason.status, 502, 'a client got no status for an engine answer whose text the relay cannot write: ' + JSON.stringify(hubRun.badReason));
assert.deepStrictEqual(hubRun.uncaughtAfterBadReason, [], 'the relay threw on an engine answer whose status text it cannot write');
assert.strictEqual(hubRun.engineSawBadReasonClose, true, 'the relay kept the engine\'s connection after it could not pass the answer on');

// The port the engine is given is one nothing held a moment before. The earlier draw asked Math.random for
// a port in 20000..59999 and nothing else, so a listener on exactly the port it drew was handed to the engine.
const PORT_DRIVER = path.join(tmp, 'port-driver.js');
fs.writeFileSync(PORT_DRIVER, `
const net = require('net');
const hold = () => new Promise((resolve) => {
    const attempt = () => {
        const p = 20000 + Math.floor(Math.random() * 40000);
        const s = net.createServer();
        s.once('error', attempt);
        s.listen(p, '127.0.0.1', () => resolve({ s, p }));
    };
    attempt();
});
const free = () => new Promise((resolve) => { const s = net.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); }); });
(async () => {
    const { p } = await hold();
    const front = await free();
    const draw = Math.random;
    Math.random = () => (p - 20000 + 0.5) / 40000;
    require('child_process').spawn(process.argv[2], ['--hub', '--hub-port=' + front, '--app_data_dir=antigravity']);
    Math.random = draw;
    const back = Number(globalThis.__spawned[1].find((a) => a.startsWith('--hub-port=')).split('=')[1]);
    console.log(JSON.stringify({ held: p, back }));
    process.exit(0);
})();
`);
const portRun = runHubDriver(PORT_DRIVER);
assert.notStrictEqual(portRun.back, portRun.held, 'the engine was given a port that something already held');
checked++;
assert.ok(hubRun.abortedBody.error && hubRun.abortedBody.error !== 'no answer', 'a client was left waiting for a body the engine abandoned: ' + JSON.stringify(hubRun.abortedBody));
assert.ok(hubRun.resetChunked.error && hubRun.resetChunked.error !== 'no answer', 'a body the engine cut off in the middle of its chunks reached the client as a whole answer: ' + JSON.stringify(hubRun.resetChunked));
assert.strictEqual(hubRun.switched.status, 502, 'a client got no status for an engine that switched protocols unasked: ' + JSON.stringify(hubRun.switched));
assert.ok(/^HTTP\/1\.1 502 /.test(hubRun.refusedBody.status) && hubRun.refusedBody.closed, 'the relay kept a connection it had refused, with the rest of a request body nobody reads still expected on it: ' + JSON.stringify(hubRun.refusedBody));
assert.ok(hubRun.killedWhenFrontIsTaken, 'the engine kept running with no relay when the extension\'s port was taken by someone else');
assert.strictEqual(hubRun.engineSawClientLeave, true, 'a client that left a response did not reach the engine: the relay kept its side open');
assert.strictEqual(hubRun.engineSawWaiterLeave, true, 'a client that left before the engine answered did not reach the engine');
assert.strictEqual(hubRun.upgradeHost, `127.0.0.1:${hubRun.back}`, 'the engine saw an upgrade whose Host is not its own');
assert.ok(hubRun.hangingEnded.error && hubRun.hangingEnded.error !== 'still open' && hubRun.hangingEnded.error !== 'no answer', 'a response open when the engine ended stayed open: ' + JSON.stringify(hubRun.hangingEnded));
checked += 17;
checked++;

// Any app on the phone can connect to the relay, which runs in the editor server and shares its file
// descriptors with the extension host and the terminal host. So it holds 32 connections at once and closes
// the rest unanswered, takes them again once they are gone, gives a request a bounded time for its headers
// and its body (Node's own are 60 s and 300 s) and closes a tunnel that has been idle for long enough.
const CAP_DRIVER = path.join(tmp, 'cap-driver.js');
fs.writeFileSync(CAP_DRIVER, `
const http = require('http');
const net = require('net');
const free = () => new Promise((resolve) => { const s = net.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); }); });
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
setTimeout(() => { console.error('the driver gave up waiting'); process.exit(1); }, 20000).unref();
(async () => {
    const front = await free();
    require('child_process').spawn(process.argv[2], ['--hub', '--hub-port=' + front]);
    const relay = globalThis.__relay;
    const back = Number(globalThis.__spawned[1].find((a) => a.startsWith('--hub-port=')).split('=')[1]);
    // An engine that waits for the body of a POST, which these never send.
    const engine = http.createServer((req, res) => { if (req.method !== 'POST') res.end('ok'); });
    engine.on('upgrade', (req, socket) => socket.write('HTTP/1.1 101 Switching Protocols\\r\\nUpgrade: websocket\\r\\nConnection: Upgrade\\r\\n\\r\\n'));
    await new Promise((r) => engine.listen(back, '127.0.0.1', r));
    const out = { headersTimeout: relay.headersTimeout, requestTimeout: relay.requestTimeout, refused: 0 };
    const held = [];
    for (let i = 0; i < 40; i++) {
        const socket = net.connect(front, '127.0.0.1', () => socket.write('POST /x HTTP/1.1\\r\\nHost: 127.0.0.1:' + front + '\\r\\nContent-Length: 1000000\\r\\n\\r\\n'));
        let answered = false;
        socket.on('data', () => { answered = true; });
        socket.on('error', () => {});
        socket.on('close', () => { if (!answered) out.refused++; });
        held.push(socket);
        await pause(5);
    }
    await pause(1000);
    const refusedWhileHeld = out.refused;
    held.forEach((socket) => socket.destroy());
    await pause(300);
    out.refused = refusedWhileHeld;
    out.afterRelease = await new Promise((resolve) => {
        http.get({ host: '127.0.0.1', port: front, path: '/healthz', agent: false, timeout: 3000 }, (res) => { res.resume(); resolve(res.statusCode); })
            .on('error', (e) => resolve(e.code || e.message));
    });
    const idle = new Promise((resolve) => relay.once('upgrade', (req, socket) => setImmediate(() => resolve(socket.timeout || null))));
    const tunnel = net.connect(front, '127.0.0.1', () => tunnel.write('GET /ws HTTP/1.1\\r\\nHost: 127.0.0.1:' + front + '\\r\\nConnection: Upgrade\\r\\nUpgrade: websocket\\r\\n\\r\\n'));
    tunnel.on('error', () => {});
    out.tunnelIdle = await idle;
    tunnel.destroy();
    console.log(JSON.stringify(out));
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
`);
const capRun = runHubDriver(CAP_DRIVER);
assert.strictEqual(capRun.refused, 8, `40 connections held at the relay: ${capRun.refused} closed unanswered, wanted the 8 past 32`);
assert.strictEqual(capRun.afterRelease, 200, `the relay did not take a request once the held connections were gone: ${capRun.afterRelease}`);
assert.ok(capRun.requestTimeout > 0 && capRun.requestTimeout <= 60000, `a request may take ${capRun.requestTimeout} ms to send its body`);
assert.ok(capRun.headersTimeout > 0 && capRun.headersTimeout <= capRun.requestTimeout, `a request may take ${capRun.headersTimeout} ms to send its headers`);
assert.ok(capRun.tunnelIdle > 0, 'an idle tunnel is never closed');
checked += 3;

// When the system kills the editor server the engine outlives it, so the next
// start ends the engine named in the note, but only while that pid still runs the
// engine on that port. The check reads /proc, which only Linux has; the driver
// plays the extension against a stand-in process.
if (fs.existsSync('/proc/self/cmdline')) {
    // A stand-in reads in /proc as the engine does on a device: the linker's name as its first word (argv0
    // is only a name, the program is this Node), the engine's path as its second, then the arguments.
    const STANDIN_ENGINE = put(path.join(tmp, 'standin/.gemini/bin/agy'), 'setInterval(function () {}, 1000);\n');
    const STANDIN_OTHER = put(path.join(tmp, 'standin/other'), 'setInterval(function () {}, 1000);\n');
    const standInEnv = { STANDIN_ENGINE, STANDIN_OTHER };
    const REAP_DRIVER = path.join(tmp, 'reap-driver.js');
    fs.writeFileSync(REAP_DRIVER, `
const cp = require('child_process');
const fs = require('fs');
const [engine, mode, note, call] = process.argv.slice(2);
let pid;
let dummy = null;
if (mode === 'dead') {
    pid = 2147483000;
} else {
    const linker = { stdio: 'ignore', argv0: '/system/bin/linker64' };
    dummy = mode === 'wrong-port' ? cp.spawn(process.execPath, [process.env.STANDIN_ENGINE, '--hub', '--hub-port=41998'], linker)
        : mode === 'not-the-engine' ? cp.spawn(process.execPath, [process.env.STANDIN_OTHER, '--hub', '--hub-port=41999'], linker)
        : mode === 'only-names-the-engine' ? cp.spawn(process.execPath, ['-e', 'setInterval(function () {}, 1000)', process.env.STANDIN_ENGINE, '--hub', '--hub-port=41999'], { stdio: 'ignore' })
        : mode === 'not-under-the-linker' ? cp.spawn(process.execPath, [process.env.STANDIN_ENGINE, '--hub', '--hub-port=41999'], { stdio: 'ignore' })
        : cp.spawn(process.execPath, [process.env.STANDIN_ENGINE, '--hub', '--hub-port=41999'], linker);
    pid = dummy.pid;
}
fs.writeFileSync(note, JSON.stringify({ pid: pid, port: 41999 }));
// A shell command that names the linker and the engine, which the setting refuses as it refuses the hub, and one
// that names the engine alone, which is not read.
const started = call === 'shell' ? cp.exec('/system/bin/linker64 ' + engine + ' --hub', function () {})
    : call === 'shell without the linker' ? cp.exec(engine + ' --hub', function () {})
    : call === 'bare name' ? cp.spawn('agy', ['--hub'])
    : cp.spawn(engine, call === 'version' ? ['--version'] : ['--hub', '--hub-port=41500']);
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
        ['only-names-the-engine', 'still running'],
        ['not-under-the-linker', 'still running'],
        ['dead', 'no crash'],
    ]) {
        fs.rmSync(HUB_NOTE, { force: true });
        const got = JSON.parse(execFileSync(
            process.execPath,
            ['--require', FAKE_ANDROID, '--require', FIX, REAP_DRIVER, HUB_ENGINE, mode, HUB_NOTE],
            { env: { ...process.env, ...standInEnv, HOME: HUB_HOME, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 30000 },
        ).trim().split('\n').pop());
        assert.strictEqual(got.result, want, `an earlier engine (${mode}): ${got.result}, wanted ${want}`);
        checked++;
    }
    // With the setting turned off the hub is not started, and an engine an earlier extension host started is
    // ended all the same: turning the setting off and reloading the window must leave none running.
    {
        const home = homeWith('{\n    "update.mode": "none"\n}\n');
        const got = JSON.parse(execFileSync(
            process.execPath,
            ['--require', FAKE_ANDROID, '--require', FIX, REAP_DRIVER, engineIn(home), 'match', path.join(home, '.vscodroid/antigravity-hub.json')],
            { env: { ...process.env, ...standInEnv, HOME: home, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 30000 },
        ).trim().split('\n').pop());
        assert.strictEqual(got.result, 'ended by SIGTERM', `an engine left running when the setting is turned off: ${got.result}`);
        checked++;
        // The extension's check of the engine's version is let through with the setting off, and ends an earlier
        // engine all the same: the extension host that started it may have ended without a word.
        const versionRun = JSON.parse(execFileSync(
            process.execPath,
            ['--require', FAKE_ANDROID, '--require', FIX, REAP_DRIVER, engineIn(home), 'match', path.join(home, '.vscodroid/antigravity-hub.json'), 'version'],
            { env: { ...process.env, ...standInEnv, HOME: home, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 30000 },
        ).trim().split('\n').pop());
        assert.strictEqual(versionRun.result, 'ended by SIGTERM', `the version check left an engine running with the setting off: ${versionRun.result}`);
        checked++;
        // So does a shell command that names the linker and the engine, which is refused the same way.
        const shellRun = JSON.parse(execFileSync(
            process.execPath,
            ['--require', FAKE_ANDROID, '--require', FIX, REAP_DRIVER, engineIn(home), 'match', path.join(home, '.vscodroid/antigravity-hub.json'), 'shell'],
            { env: { ...process.env, ...standInEnv, HOME: home, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 30000 },
        ).trim().split('\n').pop());
        assert.strictEqual(shellRun.result, 'ended by SIGTERM', `a refused shell command left an engine running with the setting off: ${shellRun.result}`);
        checked++;
        // A shell command that does not name the linker is not read, so it ends nothing: the starts above still do.
        const unreadRun = JSON.parse(execFileSync(
            process.execPath,
            ['--require', FAKE_ANDROID, '--require', FIX, REAP_DRIVER, engineIn(home), 'match', path.join(home, '.vscodroid/antigravity-hub.json'), 'shell without the linker'],
            { env: { ...process.env, ...standInEnv, HOME: home, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 30000 },
        ).trim().split('\n').pop());
        assert.strictEqual(unreadRun.result, 'still running', `a shell command naming the engine without the linker ended an earlier engine: ${unreadRun.result}`);
        checked++;
        // Nor does a program named without a path, which is not one in ~/.gemini.
        const bareRun = JSON.parse(execFileSync(
            process.execPath,
            ['--require', FAKE_ANDROID, '--require', FIX, REAP_DRIVER, engineIn(home), 'match', path.join(home, '.vscodroid/antigravity-hub.json'), 'bare name'],
            { env: { ...process.env, ...standInEnv, HOME: home, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 30000 },
        ).trim().split('\n').pop());
        assert.strictEqual(bareRun.result, 'still running', `a program named without a path ended an earlier engine: ${bareRun.result}`);
        checked++;
    }

    // The editor server's own start does the same, because the extension host
    // may not start for a long time after the app restarted the server. Only its
    // main thread: a worker whose script has the same name, and any other
    // program, must leave the engine alone (the terminal host is a worker that
    // starts later, with an engine already running).
    //
    // The editor server is the file server.js forks, vscode-reh/out/server-main.js beside platform-fix.js, so
    // these runs use a copy of the shipped file laid out that way. A script of that name anywhere else is a
    // person's own program, which a terminal starts with this file preloaded as well.
    const SERVER_FIX = path.join(tmp, 'server/platform-fix.js');
    const EDITOR_DIR = path.join(tmp, 'server/vscode-reh/out');
    const ELSEWHERE_DIR = path.join(tmp, 'project');
    fs.mkdirSync(EDITOR_DIR, { recursive: true });
    fs.mkdirSync(ELSEWHERE_DIR, { recursive: true });
    fs.copyFileSync(FIX, SERVER_FIX);
    const START_DRIVER = path.join(tmp, 'start-driver.js');
    fs.writeFileSync(START_DRIVER, `
const cp = require('child_process');
const fs = require('fs');
const path = require('path');
const [mode, note, dir, fake, fix, elsewhere] = process.argv.slice(2);
const dummy = cp.spawn(process.execPath, [process.env.STANDIN_ENGINE, '--hub', '--hub-port=41999'], { stdio: 'ignore', argv0: '/system/bin/linker64' });
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
    const main = mode === 'other-main' ? other : mode === 'elsewhere-main' ? path.join(elsewhere, 'server-main.js') : named;
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
        ['elsewhere-main', 'still running'],
    ]) {
        fs.rmSync(HUB_NOTE, { force: true });
        const got = JSON.parse(execFileSync(
            process.execPath,
            [START_DRIVER, mode, HUB_NOTE, EDITOR_DIR, FAKE_ANDROID, SERVER_FIX, ELSEWHERE_DIR],
            { env: { ...process.env, ...standInEnv, HOME: HUB_HOME, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 30000 },
        ).trim().split('\n').pop());
        assert.strictEqual(got.result, want, `an earlier engine at start (${mode}): ${got.result}, wanted ${want}`);
        checked++;
    }
    // The engine the extension host started has no parent to wait for it once the server that ran the host is
    // gone, so a server that exits on its own, or is stopped with SIGTERM as the app does, ends the engine in the
    // note. The server has no handler for SIGTERM of its own (a VS Code server installs one only for its exit
    // diagnostics): it dies of the signal at once and runs no exit handler, so the script gives it none either
    // and must leave it dying of the signal, not exiting with a code. The engine is made after the server has
    // started, so the end can only be the exit's or the signal's.
    const EXIT_DRIVER = path.join(tmp, 'exit-driver.js');
    fs.writeFileSync(EXIT_DRIVER, `
const cp = require('child_process');
const fs = require('fs');
const path = require('path');
const [note, dir, fake, fix, how, elsewhere] = process.argv.slice(2);
const script = /^elsewhere/.test(how) ? path.join(elsewhere, 'server-main.js') : path.join(dir, /^other/.test(how) ? 'other-main.js' : 'server-main.js');
// A program of the person's own with a SIGTERM handler of its own, which has to run once per signal.
const count = path.join(elsewhere, 'handled');
fs.rmSync(count, { force: true });
fs.writeFileSync(script, how === 'elsewhere-signal'
    ? "process.on('SIGTERM', function () { require('fs').appendFileSync(" + JSON.stringify(count) + ", 'x'); setTimeout(function () { process.exit(0); }, 300); }); setInterval(function () {}, 1000);"
    : /signal$/.test(how)
        ? "setInterval(function () {}, 1000);"
        : "setTimeout(function () { process.exit(0); }, 1500);");
const started = cp.spawn(process.execPath, ['--require', fake, '--require', fix, script], { stdio: 'ignore' });
let dummy = null;
let aliveBefore = null;
let serverEnd = null;
let ended = null;
started.on('exit', (code, signal) => { serverEnd = signal || 'code ' + code; });
const finish = () => { console.log(JSON.stringify({ aliveBefore, ended, serverEnd, handled: fs.existsSync(count) ? fs.readFileSync(count, 'utf8').length : 0 })); if (dummy) dummy.kill('SIGKILL'); started.kill('SIGKILL'); process.exit(0); };
setTimeout(() => {
    dummy = cp.spawn(process.execPath, [process.env.STANDIN_ENGINE, '--hub', '--hub-port=41999'], { stdio: 'ignore', argv0: '/system/bin/linker64' });
    fs.writeFileSync(note, JSON.stringify({ pid: dummy.pid, port: 41999 }));
    dummy.on('exit', (code, signal) => { ended = signal; setTimeout(finish, 500); });
}, 700);
setTimeout(() => { aliveBefore = dummy !== null && dummy.exitCode === null && dummy.signalCode === null; }, 1100);
if (/signal$/.test(how)) setTimeout(() => started.kill('SIGTERM'), 1200);
setTimeout(finish, 6000);
`);
    for (const [how, want] of [
        ['exit', { aliveBefore: true, ended: 'SIGTERM', serverEnd: 'code 0', handled: 0 }],
        ['signal', { aliveBefore: true, ended: 'SIGTERM', serverEnd: 'SIGTERM', handled: 0 }],
        ['other', { aliveBefore: true, ended: null, serverEnd: 'code 0', handled: 0 }],
        ['other-signal', { aliveBefore: true, ended: null, serverEnd: 'SIGTERM', handled: 0 }],
        ['elsewhere', { aliveBefore: true, ended: null, serverEnd: 'code 0', handled: 0 }],
        ['elsewhere-signal', { aliveBefore: true, ended: null, serverEnd: 'code 0', handled: 1 }],
    ]) {
        fs.rmSync(HUB_NOTE, { force: true });
        const got = JSON.parse(execFileSync(
            process.execPath,
            [EXIT_DRIVER, HUB_NOTE, EDITOR_DIR, FAKE_ANDROID, SERVER_FIX, how, ELSEWHERE_DIR],
            { env: { ...process.env, ...standInEnv, HOME: HUB_HOME, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 30000 },
        ).trim().split('\n').pop());
        assert.deepStrictEqual(got, want, `the engine at the server's end (${how}): ${JSON.stringify(got)}, wanted ${JSON.stringify(want)}`);
        checked++;
    }
} else {
    console.log('skipped: ending an earlier engine reads /proc, which this system does not have');
}

// Non-canonical paths to the engine (dot segments, a symlink inside ~/.gemini, or ~/.gemini/bin/agy
// itself pointing to a versioned binary under ~/.gemini/versions/) must still pass through
// antigravityHub: valid hub arguments get relayed and disallowed ones are refused.
{
    const HUB_ALIAS = path.join(HUB_HOME, '.gemini/bin/agy-alias');
    fs.symlinkSync(HUB_ENGINE, HUB_ALIAS);
    const VERSIONED_HOME = path.join(REAL_DATA, 'files', `home-${homes++}`);
    const VERSIONED_TARGET = put(path.join(VERSIONED_HOME, '.gemini/versions/1.6.0/agy-bin'), elf64(BIONIC_LINKER));
    const VERSIONED_AGY = path.join(VERSIONED_HOME, '.gemini/bin/agy');
    fs.mkdirSync(path.dirname(VERSIONED_AGY), { recursive: true });
    fs.symlinkSync(VERSIONED_TARGET, VERSIONED_AGY);
    put(machineSettings(VERSIONED_HOME), SETTING_ON);
    const VERSIONED_OFF_HOME = path.join(REAL_DATA, 'files', `home-${homes++}`);
    const VERSIONED_OFF_TARGET = put(path.join(VERSIONED_OFF_HOME, '.gemini/versions/1.6.0/agy-bin'), elf64(BIONIC_LINKER));
    const VERSIONED_OFF_AGY = path.join(VERSIONED_OFF_HOME, '.gemini/bin/agy');
    fs.mkdirSync(path.dirname(VERSIONED_OFF_AGY), { recursive: true });
    fs.symlinkSync(VERSIONED_OFF_TARGET, VERSIONED_OFF_AGY);

    for (const [label, target, home] of [
        ['dot segment', path.join(HUB_HOME, '.gemini/bin') + '/./agy', HUB_HOME],
        ['parent segment', path.join(HUB_HOME, '.gemini/bin') + '/../bin/agy', HUB_HOME],
        ['symlink inside ~/.gemini', HUB_ALIAS, HUB_HOME],
        ['versioned symlink at ~/.gemini/bin/agy', VERSIONED_AGY, VERSIONED_HOME],
        ['dot segment to versioned symlink', path.join(VERSIONED_HOME, '.gemini/bin') + '/./agy', VERSIONED_HOME],
    ]) {
        const relayed = spawnedUnder([target, HUB_ARGS, SPAWN_OPTIONS], { home });
        assert.strictEqual(relayed[0], BIONIC_LINKER, `${label}: a valid hub start was not started through the linker`);
        assert.ok(/^--hub-port=\d+$/.test(relayed[1][2]) && relayed[1][2] !== '--hub-port=41000',
            `${label}: the hub port was not rewritten for the relay: ${relayed[1][2]}`);
        const bad = [target, [...HUB_ARGS, '--remote-control'], SPAWN_OPTIONS];
        assert.deepStrictEqual(spawnedUnder(bad, { home }), bad,
            `${label}: a disallowed argument reached the linker through a non-canonical path`);
        checked += 2;
    }

    // With setting OFF, normalized ~/.gemini/bin/./agy --version and versioned ~/.gemini/bin/agy --version
    // remain allowed, while an external symlink outside ~/.gemini is still refused for --version.
    const dotVersion = [path.join(OFF_HOME, '.gemini/bin') + '/./agy', ['--version'], SPAWN_OPTIONS];
    assert.strictEqual(spawnedUnder(dotVersion, { home: OFF_HOME })[0], BIONIC_LINKER,
        'setting off, ~/.gemini/bin/./agy --version was held back');
    const verOffCall = [VERSIONED_OFF_AGY, ['--version'], SPAWN_OPTIONS];
    assert.strictEqual(spawnedUnder(verOffCall, { home: VERSIONED_OFF_HOME })[0], BIONIC_LINKER,
        'setting off, versioned ~/.gemini/bin/agy --version was held back');
    const extLinkVersion = [LINK_TO_OFF_ENGINE, ['--version'], SPAWN_OPTIONS];
    assert.deepStrictEqual(spawnedUnder(extLinkVersion, { home: OFF_HOME }), extLinkVersion,
        'setting off, an external symlink to agy was allowed through for --version');
    checked += 3;
}

// Disallowed hub and listener forms targeting the engine (missing, leading-zero, or out-of-range port,
// Go flag variants, remote-control mode) are refused rather than started directly without a relay.
// Non-hub engine invocations and other programs carrying the same flags still run through the linker
// with their arguments untouched.
const REFUSED_HUB = [
    ['the engine without a port', HUB_ENGINE, ['--hub', '--app_data_dir=antigravity']],
    ['the engine with port 0', HUB_ENGINE, ['--hub', '--hub-port=0']],
    ['the engine with all-zero port', HUB_ENGINE, ['--hub', '--hub-port=0000']],
    ['the engine with leading-zero port', HUB_ENGINE, ['--hub', '--hub-port=01000']],
    ['the engine with port 65536', HUB_ENGINE, ['--hub', '--hub-port=65536']],
    ['the engine with a port past 65535', HUB_ENGINE, ['--hub', '--hub-port=70000']],
    ['the engine without --hub', HUB_ENGINE, ['--hub-port=41000']],
    ['single-dash -hub', HUB_ENGINE, ['-hub', '--hub-port=41000']],
    ['--hub=true', HUB_ENGINE, ['--hub=true', '--hub-port=41000']],
    ['single-dash -hub-port=', HUB_ENGINE, ['--hub', '-hub-port=41000']],
    ['space-separated --hub-port', HUB_ENGINE, ['--hub', '--hub-port', '9000']],
    ['space-separated -hub-port', HUB_ENGINE, ['-hub', '-hub-port', '9000']],
    ['--remote-control', HUB_ENGINE, ['--remote-control']],
    ['single-dash -remote-control', HUB_ENGINE, ['-remote-control']],
    ['--remote-control with value', HUB_ENGINE, ['--remote-control=true']],
];
for (const [label, file, args] of REFUSED_HUB) {
    const call = [file, args, SPAWN_OPTIONS];
    assert.deepStrictEqual(spawnedUnder(call, { home: HUB_HOME }), call, `${label}: a disallowed listener form was started`);
    checked++;
}
assert.deepStrictEqual(
    callUnder('execFileSync', [HUB_ENGINE, ['--hub', '--hub-port=01000'], SPAWN_OPTIONS], { home: HUB_HOME }),
    ['execFileSync', HUB_ENGINE, ['--hub', '--hub-port=01000'], SPAWN_OPTIONS],
    'execFileSync with a leading-zero hub port was not refused',
);
checked++;
const KEEP = [
    ['the engine checking its version', HUB_ENGINE, ['--version']],
    ['the engine showing help', HUB_ENGINE, ['--help']],
    // Inside the data directory, so the loader rule applies and only the hub rule can be told apart by the arguments.
    ['another program in the data directory with the same flags', path.join(REAL_DATA, 'files/home/other-tool'), ['--hub', '--hub-port=41000']],
];
for (const [label, file, args] of KEEP) {
    if (!fs.existsSync(file)) put(file, elf64(BIONIC_LINKER));
    const inside = file.startsWith(REAL_DATA);
    const got = spawnedUnder([file, args, SPAWN_OPTIONS], { home: HUB_HOME });
    assert.strictEqual(got[0], BIONIC_LINKER, `${label}: was not started through the linker`);
    assert.deepStrictEqual(got[1], inside ? [file, ...args] : args, `${label}: the arguments changed`);
    checked++;
}

fs.rmSync(tmp, { recursive: true, force: true });
console.log(`ok -- ${checked} invocations checked, the override engaged for ${cases.filter((c) => c[4] === 'linux').length}`);
