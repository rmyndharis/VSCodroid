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

function spawnedUnder(call, { preload = null, dataDir = DATA_LINK, record = RECORD_SPAWN, main = SPAWN_MAIN, cwd = undefined, home = NO_SETTINGS_HOME } = {}) {
    const env = { ...process.env, HOME: home };
    delete env.LD_PRELOAD;
    if (preload !== null) env.FAKE_LD_PRELOAD = preload;
    if (dataDir === null) delete env.TERMUX_APP__DATA_DIR; else env.TERMUX_APP__DATA_DIR = dataDir;
    const out = execFileSync(
        process.execPath,
        ['--require', FAKE_ANDROID, '--require', SET_PRELOAD, '--require', record, '--require', FIX, main, JSON.stringify(call)],
        { env, encoding: 'utf8', timeout: 10000, cwd },
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
// Node takes null or undefined for the argument list and reads the options after it. A call written that way
// has to keep its options (cwd, env, stdio), which a rewrite that took the empty slot for them dropped or threw on.
assert.deepStrictEqual(
    spawnedUnder([ENGINE, '@undefined', SPAWN_OPTIONS]), [BIONIC_LINKER, [ENGINE], SPAWN_OPTIONS],
    'an undefined argument list lost the options that follow it',
);
assert.deepStrictEqual(
    spawnedUnder([ENGINE, null, SPAWN_OPTIONS]), [BIONIC_LINKER, [ENGINE], SPAWN_OPTIONS],
    'a null argument list lost the options that follow it',
);
assert.deepStrictEqual(spawnedUnder([ENGINE, null]), [BIONIC_LINKER, [ENGINE]], 'a null argument list alone was not started through the linker');
checked += 3;
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
for (const [label, how, call, expected] of [
    ['arguments, options and a callback', 'plain', [ENGINE, ['--version'], VERSION_OPTIONS, CALLBACK], [BIONIC_LINKER, [ENGINE, '--version'], VERSION_OPTIONS, null]],
    ['arguments and a callback', 'plain', [ENGINE, ['--version'], CALLBACK], [BIONIC_LINKER, [ENGINE, '--version'], null]],
    ['a callback only', 'plain', [ENGINE, CALLBACK], [BIONIC_LINKER, [ENGINE], null]],
    ['options and a callback, no argument list', 'plain', [ENGINE, VERSION_OPTIONS, CALLBACK], [BIONIC_LINKER, [ENGINE], VERSION_OPTIONS, null]],
    ['an empty argument list with options', 'plain', [ENGINE, [], VERSION_OPTIONS, CALLBACK], [BIONIC_LINKER, [ENGINE], VERSION_OPTIONS, null]],
    ['no arguments at all', 'plain', [ENGINE], [BIONIC_LINKER, [ENGINE]]],
    ['the promisified form, which the extension uses', 'promisified', [ENGINE, ['--version'], VERSION_OPTIONS], [BIONIC_LINKER, [ENGINE, '--version'], VERSION_OPTIONS]],
    ['a null argument list before options and a callback', 'plain', [ENGINE, null, VERSION_OPTIONS, CALLBACK], [BIONIC_LINKER, [ENGINE], VERSION_OPTIONS, null]],
    ['an undefined argument list before a callback', 'plain', [ENGINE, '@undefined', CALLBACK], [BIONIC_LINKER, [ENGINE], null]],
    ['an undefined argument list before options, promisified', 'promisified', [ENGINE, '@undefined', VERSION_OPTIONS], [BIONIC_LINKER, [ENGINE], VERSION_OPTIONS]],
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
    // An engine that resets in the middle of a body: the client must be let go, not left waiting.
    out.abortedBody = await getAs(front, '/abort', '127.0.0.1:' + front);
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
// The engine's own port answers any app on the phone, so nothing the Antigravity extension keeps in
// $HOME/.gemini runs until the person has turned on the machine setting `vscodroid.antigravity.runEngine`.
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
const throughLinker = (call) => (Array.isArray(call[1])
    ? [BIONIC_LINKER, [call[0], ...call[1]], ...call.slice(2)]
    : [BIONIC_LINKER, [call[0]], ...call.slice(1)]);

// The file is read as VS Code reads it: comments and trailing commas out, strings kept, the top-level key
// only, the last one when it is written twice, and a file that does not parse turns nothing on.
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

// With the setting off, whatever is asked of a program in ~/.gemini is left as written, which fails with
// EACCES as it did before: the engine's Go flag parser takes -hub and --hub=true for --hub, it listens in a
// second mode as well, and the extension and the engine update themselves. Only `agy --version`, the
// extension's check of what it downloaded, goes through the linker. With the setting on all of them run,
// the relay standing only in front of the start it knows.
const AGY_NEXT = put(path.join(OFF_HOME, '.gemini/bin/agy.next'), elf64(BIONIC_LINKER));
put(path.join(HUB_HOME, '.gemini/bin/agy.next'), elf64(BIONIC_LINKER));
const LINK_TO_OFF_ENGINE = path.join(tmp, 'outside/gemini-engine-link');
fs.symlinkSync(OFF_ENGINE, LINK_TO_OFF_ENGINE);
for (const [label, call] of [
    ['the hub as the extension starts it', [OFF_ENGINE, HUB_ARGS, SPAWN_OPTIONS]],
    ['--hub=true', [OFF_ENGINE, ['--hub=true', '--hub-port=41000'], SPAWN_OPTIONS]],
    ['single-dash flags', [OFF_ENGINE, ['-hub', '-hub-port=41000'], SPAWN_OPTIONS]],
    ['the remote control mode', [OFF_ENGINE, ['--remote-control'], SPAWN_OPTIONS]],
    ['no arguments', [OFF_ENGINE, SPAWN_OPTIONS]],
    ['--version with more after it', [OFF_ENGINE, ['--version', '--hub'], SPAWN_OPTIONS]],
    ['another program in ~/.gemini', [AGY_NEXT, HUB_ARGS, SPAWN_OPTIONS]],
    ['a link from elsewhere that ends at the engine', [LINK_TO_OFF_ENGINE, HUB_ARGS, SPAWN_OPTIONS]],
]) {
    assert.deepStrictEqual(spawnedUnder(call, { home: OFF_HOME }), call, `setting off, ${label}: the call was changed, so it runs`);
    // The start the relay knows is checked on its own below.
    const on = [call[0].replace(OFF_HOME, HUB_HOME), ...call.slice(1)];
    if (call[0] !== LINK_TO_OFF_ENGINE && !(call[0] === OFF_ENGINE && call[1] === HUB_ARGS)) {
        assert.deepStrictEqual(spawnedUnder(on, { home: HUB_HOME }), throughLinker(on), `setting on, ${label}: not started through the linker as it was written`);
    }
    checked++;
}
assert.deepStrictEqual(spawnedUnder([OFF_ENGINE, ['--version'], SPAWN_OPTIONS], { home: OFF_HOME }), throughLinker([OFF_ENGINE, ['--version'], SPAWN_OPTIONS]),
    'setting off, the extension\'s check of the engine\'s version was held back, so it downloads the engine again');
checked++;
for (const how of ['plain', 'promisified']) {
    const exec = (call, home) => spawnedUnder([how, call], { record: RECORD_EXEC, main: EXEC_MAIN, home });
    const hub = [OFF_ENGINE, ['--hub', '--hub-port=41000'], { timeout: 5000 }];
    assert.deepStrictEqual(exec(hub, OFF_HOME), [how, ...hub], `execFile (${how}) started the hub with the setting off`);
    assert.deepStrictEqual(exec([OFF_ENGINE, ['--hub', '--hub-port=41000'], '@callback'], OFF_HOME).slice(0, 2), [how, OFF_ENGINE], `execFile (${how}) with a callback was started with the setting off`);
    assert.deepStrictEqual(exec([OFF_ENGINE, ['--remote-control'], { timeout: 5000 }], OFF_HOME), [how, OFF_ENGINE, ['--remote-control'], { timeout: 5000 }], `execFile (${how}) of the remote control mode was started with the setting off`);
    const version = [OFF_ENGINE, ['--version'], { timeout: 5000 }];
    assert.deepStrictEqual(exec(version, OFF_HOME), [how, ...throughLinker(version)], `execFile (${how}) of the engine for its version is held back with the setting off`);
    // No relay here: an engine started by execFile with the setting on is an ordinary program on the port it was given.
    const onHub = [HUB_ENGINE, ['--hub', '--hub-port=41000'], { timeout: 5000 }];
    assert.deepStrictEqual(exec(onHub, HUB_HOME), [how, ...throughLinker(onHub)], `execFile (${how}) with the setting on did not go through the linker as it was written`);
    checked += 5;
}

// A program outside $HOME/.gemini is not the setting's to hold back, even one at a path ending in
// .gemini/bin/agy: it goes through the linker either way, and gets no relay.
for (const home of [OFF_HOME, HUB_HOME]) {
    const call = [ENGINE, HUB_ARGS, SPAWN_OPTIONS];
    assert.deepStrictEqual(spawnedUnder(call, { home }), throughLinker(call), `a program outside $HOME/.gemini was changed by the setting (${home === HUB_HOME ? 'on' : 'off'})`);
    checked++;
}

// The hub start the relay knows, with the setting on: the extension's own arguments, a CSRF token and one
// --add-dir per folder among them, keep everything but the port; anything else a folder's
// antigravity.serverArgs could add to them, a second copy of one of them included (the flag parser keeps
// the last), is refused, the call left as written.
const EXTENSION_ARGS = ['--hub', '--hub-port=41000', '--app_data_dir=antigravity', '--csrf_token=0123abcd', '--add-dir=/data/user/0/com.vscodroid/files/projects/a', '--add-dir=/data/user/0/com.vscodroid/files/projects/b c'];
{
    const got = spawnedUnder([HUB_ENGINE, EXTENSION_ARGS, SPAWN_OPTIONS], { home: HUB_HOME });
    assert.strictEqual(got[0], BIONIC_LINKER, 'the extension\'s own hub start was refused');
    assert.ok(/^--hub-port=\d+$/.test(got[1][2]) && got[1][2] !== '--hub-port=41000', 'with the setting on the engine kept the port the extension chose: ' + got[1][2]);
    assert.deepStrictEqual([got[1][0], got[1][1], ...got[1].slice(3)], [HUB_ENGINE, ...EXTENSION_ARGS.filter((a) => a !== '--hub-port=41000')], 'the extension\'s other arguments changed');
    checked++;
}
for (const [label, extra] of [
    ['a second port, which the flag parser takes', ['--hub-port=8080']],
    ['a second port spelled with one dash', ['-hub-port=8080']],
    ['a port given as a separate word', ['--hub-port', '8080']],
    ['a second data directory', ['--app_data_dir=/sdcard/antigravity']],
    ['a second token', ['--csrf_token=chosen-by-the-folder']],
    ['--hub twice', ['--hub']],
    ['an argument the extension does not build', ['--remote-control']],
    ['a bare word', ['serve']],
]) {
    const call = [HUB_ENGINE, [...EXTENSION_ARGS, ...extra], SPAWN_OPTIONS];
    assert.deepStrictEqual(spawnedUnder(call, { home: HUB_HOME }), call, `a hub start with ${label} was started`);
    checked++;
}
const hubRun = JSON.parse(execFileSync(
    process.execPath,
    ['--require', FAKE_ANDROID, '--require', RECORD_HUB, '--require', FIX, HUB_DRIVER, HUB_ENGINE],
    { env: { ...process.env, HOME: HUB_HOME, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 60000 },
).trim().split('\n').pop());
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
assert.ok(hubRun.abortedBody.error && hubRun.abortedBody.error !== 'no answer', 'a client was left waiting for a body the engine abandoned: ' + JSON.stringify(hubRun.abortedBody));
assert.ok(hubRun.killedWhenFrontIsTaken, 'the engine kept running with no relay when the extension\'s port was taken by someone else');
assert.strictEqual(hubRun.engineSawClientLeave, true, 'a client that left a response did not reach the engine: the relay kept its side open');
assert.strictEqual(hubRun.engineSawWaiterLeave, true, 'a client that left before the engine answered did not reach the engine');
assert.strictEqual(hubRun.upgradeHost, `127.0.0.1:${hubRun.back}`, 'the engine saw an upgrade whose Host is not its own');
assert.ok(hubRun.hangingEnded.error && hubRun.hangingEnded.error !== 'still open' && hubRun.hangingEnded.error !== 'no answer', 'a response open when the engine ended stayed open: ' + JSON.stringify(hubRun.hangingEnded));
checked += 11;
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
const capRun = JSON.parse(execFileSync(
    process.execPath,
    ['--require', FAKE_ANDROID, '--require', RECORD_HUB, '--require', FIX, CAP_DRIVER, HUB_ENGINE],
    { env: { ...process.env, HOME: HUB_HOME, TERMUX_APP__DATA_DIR: DATA_LINK, LD_PRELOAD: '' }, encoding: 'utf8', timeout: 60000 },
).trim().split('\n').pop());
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
const [engine, mode, note] = process.argv.slice(2);
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

// Only the engine's own launch is touched: another program with the same flags,
// and the engine without a hub port, keep their arguments.
const KEEP = [
    ['the engine without a port', HUB_ENGINE, ['--hub', '--app_data_dir=antigravity']],
    ['the engine with port 0', HUB_ENGINE, ['--hub', '--hub-port=0']],
    ['the engine without --hub', HUB_ENGINE, ['--hub-port=41000']],
    ['the engine with a port past 65535', HUB_ENGINE, ['--hub', '--hub-port=70000']],
    // Inside the data directory, so the loader rule applies and only the hub rule can be told apart by the arguments.
    ['another program in the data directory with the same flags', path.join(REAL_DATA, 'files/home/other-tool'), ['--hub', '--hub-port=41000']],
];
for (const [label, file, args] of KEEP) {
    if (!fs.existsSync(file)) put(file, elf64(BIONIC_LINKER));
    const inside = file.startsWith(REAL_DATA);
    const got = spawnedUnder([file, args, SPAWN_OPTIONS], { home: HUB_HOME });
    assert.deepStrictEqual(got[1], inside ? [file, ...args] : args, `${label}: the arguments changed`);
    checked++;
}

fs.rmSync(tmp, { recursive: true, force: true });
console.log(`ok -- ${checked} invocations checked, the override engaged for ${cases.filter((c) => c[4] === 'linux').length}`);
