/**
 * VSCodroid Process Monitor
 *
 * Scans /proc to count and classify descendant phantom processes,
 * writes a JSON snapshot for the status bar extension to read,
 * and kills idle language servers under memory pressure.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const SCAN_INTERVAL_MS = 10_000;
const IDLE_KILL_THRESHOLD_MS = 5 * 60 * 1000; // 5 minutes

// What this app costs when nothing is happening, measured rather than guessed:
// on a cold start left untouched, /proc under the app's own uid holds the
// bootstrap, the editor server, the file watcher, the agent host, and the chat
// agent's model backend. Five, on API 33 and on API 37 alike.
//
// The number matters because the thresholds below are read against it. The soft
// budget used to be 5, chosen when the idle set was smaller, so a fresh install
// sat exactly on its own warning with nothing open and the status item was amber
// from the first paint. A warning that is always lit is one nobody reads, which
// costs more than the warning was worth.
const IDLE_BASELINE = 5;

// Idle, plus a terminal and two language servers: a session with real work in
// it. Below this the count says nothing a user could act on.
const SOFT_BUDGET = 8;

// Far enough above the soft budget to mean something has gone wrong rather than
// that the user is busy, and still well short of the hard limit, so there is
// room to act. This used to live in the status bar extension as a bare 10 with
// no relation to anything; both numbers now come from here, so they cannot
// drift apart in two languages with nothing to make them agree.
const ERROR_BUDGET = 14;

// Android's system-wide phantom process limit, shared with every other app.
const HARD_LIMIT = 32;
// The severities that justify shedding idle language servers, as words written
// by the Android side. It used to be a number compared with >= against Android's
// trim levels, which are not ordered by severity: TRIM_MEMORY_UI_HIDDEN is 20
// and means "the UI is hidden", so every app switch cleared a threshold meant
// for genuine memory pressure and killed every idle language server.
const KILL_ON_PRESSURE = new Set(['critical']);

// The file MainActivity.writeMemoryPressure() writes the severity into, and the
// word it writes there, are two literals on the far side of a language boundary
// from these. Nothing made them agree: the Kotlin tests compare against the
// PRESSURE_CRITICAL constant rather than its value, so changing that value to
// any other word left every one of them green while this set stopped matching
// and the idle kill stopped firing under real pressure -- a detector that is
// dead and silent, in an app built around a 32-process limit.
//
// Both are exported for scripts/test-process-monitor.js, which reads the Kotlin
// literals and refuses a disagreement in either direction.
const PRESSURE_FILENAME = 'vscodroid-memory-pressure';

// Where this app unpacks the editor, and the anchor the chat-backend rule in
// classify() is written against.
//
// Derived rather than spelled out. server.js requires this module out of its own
// directory and builds the server tree beside it the same way, from __dirname,
// so this is the same path by construction instead of by agreement. It was a
// hand-written copy of a location the Kotlin side owns in five places, and the
// half of that which nothing would have caught is a move of the PARENT
// directory: renaming the leaf drops the app into server.js's minimal
// health-check server and is loud, while moving `server/` elsewhere leaves every
// __dirname-relative path in server.js working and only stops this rule
// matching -- and the chat agent's model backend, 226 MB and one of five
// processes counted against the phantom budget, goes back to 'unknown', outside
// the idle kill and outside the command that sheds language servers by hand.
//
// Exported for scripts/test-process-monitor.js, which loads a copy of this file
// from a directory of its own and asks the same question there.
const REH_ROOT = path.join(__dirname, 'vscode-reh');

// classify() lower-cases the command line before comparing, so the needle is
// lower-cased once here to meet it. The device's path has no capitals; a
// checkout on a CI runner or a workstation does, and that is where this file's
// own tests run.
const REH_PREFIX = REH_ROOT.toLowerCase() + '/';

// How an entry matches depends on its shape, which namesProgram() below reads:
// a bare word has to name the program exactly (optionally with a .js, .mjs or
// .cjs extension), while an entry carrying a separator is distinctive enough to
// be found anywhere in the name. So 'gopls' matches gopls and gopls.js and
// nothing else, while 'rust-analyzer' still matches rust-analyzer-wrapper.
//
// The extension list the bare-word arm allows is Node's, so a bare word can only
// ever name a Node entry point. A server that launches as anything else, a .py
// file or a versioned binary, needs an entry carrying a separator so that it
// takes the substring arm instead. That is why run-jedi-language-server.py has
// an entry of its own below rather than being left to the bare word inside it.
const LANG_SERVER_PATTERNS = [
    'tsserver', 'typescript-language-server',
    'pylsp', 'python-language-server',
    // Both of these launch as a name with the bare pattern on the front, so
    // neither is reachable from 'pyright' or 'jedi' alone. ms-python runs
    // python_files/run-jedi-language-server.py through the interpreter, and it
    // is the default Python server this app writes into settings.json, so it is
    // the one most likely to be holding a process slot on a stock install;
    // pyright ships dist/pyright-langserver.js. Hyphens put both on the
    // substring arm, which is what a .py entry point requires.
    'pyright', 'pyright-langserver',
    'jedi', 'jedi-language-server',
    'gopls', 'rust-analyzer', 'clangd',
    // 'eslintServer' for the server the bundled ESLint extension forks, which
    // its client names as server/out/eslintServer.js. A bare 'eslint' reaches
    // the eslint CLI and nothing else now that a bare word has to be the whole
    // basename.
    //
    // 'vscode-eslint' was here and is gone. It reached nothing: that string only
    // ever appeared in the extension's directory name, which stopped being
    // compared when classification moved to argument basenames. Left in place it
    // was worse than inert, because a hyphen puts it on the substring arm, where
    // the only names it can still reach are a user's own: `node
    // vscode-eslint-shim.js` was classified langserver, tracked, and eligible
    // for the idle kill.
    'eslint', 'eslintServer',
    // The four bundled with the editor, named as they actually launch. They were
    // 'css-languageserver', 'html-languageserver' and 'json-languageserver',
    // which match nothing: the files are cssServerMain.js, htmlServerMain.js
    // and jsonServerMain.js. So the servers most likely to be running were the
    // ones the monitor could not see, and the idle-kill never reclaimed them
    // while they counted against the phantom-process budget.
    //
    // Renaming them was not enough on its own, and the second half took another
    // release to find: classify() lower-cases the command line, so a pattern
    // carrying a capital could never match it and these stayed invisible under
    // their correct names too. Matching is case-insensitive on both sides now,
    // which is why they are still spelled the way the files are.
    //
    // No extension, and that is the third half. Each client passes an
    // extensionless module path to `fork` -- cssClientMain.js builds
    // `./server/.../node/cssServerMain` and the markdown client builds
    // `./dist/serverWorkerMain` -- and Node puts that path in argv[1] verbatim.
    // Measured: `fork("/tmp/x/child")` gives the child argv[1] "/tmp/x/child",
    // so the basename classify() compares is "cssservermain", with nothing after
    // it. A pattern spelled with .js carries a dot, which takes it to the
    // substring arm, and "cssservermain".includes("cssservermain.js") is false.
    // As bare words they take the extension arm instead, which accepts the name
    // with no extension and with .js, so both spellings are covered whichever
    // way a future client launches them.
    //
    // scripts/test-process-monitor.js is what fails the build if this regresses.
    // check-langserver-patterns.py compares against the file name on disk, so it
    // certified all three as covered for as long as they were wrong; it now
    // requires the extensionless spelling too, which is the one that reaches
    // argv.
    'cssServerMain', 'htmlServerMain', 'jsonServerMain', 'serverWorkerMain',
    // 'tailwindcss' until the matching moved from the whole command line to the
    // basename of each argument. That string only ever appeared in the extension's
    // *directory* -- bradlc.vscode-tailwindcss-0.16.0 -- so the change quietly
    // un-classified the server it names.
    //
    // Both program names in full, rather than the 'tailwind' they share. A bare
    // 'tailwind' also names a user's own tailwind.js build script, and being
    // classified 'langserver' is not cosmetic: it puts a process into
    // lsCpuTracker and makes it eligible for the idle kill under memory
    // pressure. Killing a build someone is waiting on is a worse outcome than
    // failing to reclaim a language server.
    //
    // Two entries because neither names the other: tailwindModeServer.js is not
    // 'tailwindServer' with an extension on the end.
    //
    // check-langserver-patterns.py cannot see this one: it globs *ServerMain.js
    // under vscode-reh/extensions, and Tailwind is a marketplace extension living
    // under assets/extensions with a name that does not match the glob. The
    // fixtures in scripts/test-process-monitor.js are what guard it instead.
    'tailwindServer', 'tailwindModeServer'
];

let outputPath = '';
let pressurePath = '';
let rootPid = 0;
let myUid = 0;
let scanTimer = null;
// Overridable so the suite can point a scan at a fixture. /proc does not exist
// on a macOS workstation and holds an uncontrolled process list on a CI runner,
// which left every branch below unreachable from a test.
let procRoot = '/proc';

// Track language server CPU time for idle detection: pid -> { cpuTime, lastActive }
const lsCpuTracker = new Map();

function start(serverMainPid, options) {
    const tmpDir = process.env.TMPDIR || '/tmp';
    outputPath = path.join(tmpDir, 'vscodroid-processes.json');
    pressurePath = path.join(tmpDir, PRESSURE_FILENAME);
    rootPid = serverMainPid;
    myUid = process.getuid();
    procRoot = (options && options.procRoot) || '/proc';

    log('info', `Started (root PID=${rootPid}, UID=${myUid})`);

    // First scan immediately, then on interval
    scan();
    scanTimer = setInterval(scan, SCAN_INTERVAL_MS);
    // Don't prevent Node.js from exiting
    if (scanTimer.unref) scanTimer.unref();
}

function stop() {
    if (scanTimer) {
        clearInterval(scanTimer);
        scanTimer = null;
    }
}

function log(level, msg) {
    const ts = new Date().toISOString();
    console.log(`[${ts}] [process-monitor] [${level}] ${msg}`);
}

function readFileQuiet(filePath) {
    try { return fs.readFileSync(filePath, 'utf8'); } catch { return null; }
}

/**
 * Read /proc/{pid}/status to get PPid and Uid.
 * Returns { ppid, uid } or null.
 */
function readProcStatus(pid) {
    const content = readFileQuiet(path.join(procRoot, String(pid), 'status'));
    if (!content) return null;

    let ppid = -1, uid = -1;
    for (const line of content.split('\n')) {
        if (line.startsWith('PPid:')) {
            ppid = parseInt(line.slice(5).trim(), 10);
        } else if (line.startsWith('Uid:')) {
            // Uid line has: real effective saved filesystem
            uid = parseInt(line.slice(4).trim().split(/\s+/)[0], 10);
        }
        if (ppid >= 0 && uid >= 0) break;
    }
    return (ppid >= 0 && uid >= 0) ? { ppid, uid } : null;
}

/**
 * Read /proc/{pid}/cmdline (null-separated).
 * Returns the full command string.
 */
function readCmdline(pid) {
    const content = readFileQuiet(path.join(procRoot, String(pid), 'cmdline'));
    if (!content) return '';
    return content.replace(/\0/g, ' ').trim();
}

/**
 * Read cumulative CPU time (utime + stime) from /proc/{pid}/stat.
 * Fields 14 and 15 (0-indexed) after splitting by space.
 */
function readCpuTime(pid) {
    const content = readFileQuiet(path.join(procRoot, String(pid), 'stat'));
    if (!content) return -1;
    // stat format: pid (comm) state ppid ... field14 field15 ...
    // comm can contain spaces/parens, so find the closing ')' first
    const closeParen = content.lastIndexOf(')');
    if (closeParen < 0) return -1;
    const fields = content.slice(closeParen + 2).split(' ');
    // After ')' and state, fields[0]=state, [1]=ppid, ... utime=[11], stime=[12]
    const utime = parseInt(fields[11], 10) || 0;
    const stime = parseInt(fields[12], 10) || 0;
    return utime + stime;
}

/**
 * Does this argument's basename name the program a pattern describes?
 *
 * A bare word does not name a program wherever it appears: 'eslint' is inside
 * run-eslint.js and eslint.config.js, and 'tsserver' is inside tsserver-log.js,
 * which reaches this via `--out=/tmp/tsserver-log.js` because every argument is
 * tested and not only the one the launcher runs. Being classified 'langserver'
 * is not cosmetic: it puts the process into lsCpuTracker, and five minutes with
 * no CPU movement then make it a SIGTERM the next time Android reports critical
 * memory pressure. So a bare word has to be the whole name, give or take the
 * extension a Node entry point carries.
 *
 * Patterns carrying a separator, 'rust-analyzer' and 'cssServerMain.js', are
 * specific enough that finding one inside a name means what it says, and they
 * keep the substring test they need.
 */
function namesProgram(name, needle) {
    if (!/^\w+$/.test(needle)) return name.includes(needle);
    return ['', '.js', '.mjs', '.cjs'].some((ext) => name === needle + ext);
}

/**
 * The first argument that is not an option: the script the runtime was asked to
 * run.
 *
 * One definition for the two readers below. The row's name and the backend rule
 * in classify() both mean this argument, and a second copy of the rule is how
 * one of them comes to mean something slightly different.
 */
function scriptArgument(parts) {
    return parts.slice(1).find((arg) => !arg.startsWith('-')) || '';
}

/**
 * The name the details view prints for a process.
 *
 * This was the basename of argv[0] followed by argv[1], and on this device that
 * is the same string for nearly everything: every process the app owns is the
 * one bundled Node binary, and the first argument it is given is the heap
 * ceiling. Measured on an API 33 emulator sitting idle, five of the six rows
 * rendered as `libnode.so --max-old-space-size=488`, so the view whose whole job
 * is to say what is running distinguished none of them, and neither did the
 * counter's tooltip.
 *
 * What identifies a process is the first argument that is not an option: the
 * script the runtime was asked to run. `--type=` is carried along beside it
 * because `bootstrap-fork` is launched more than once with different ones and is
 * otherwise several rows sharing one name. A script called `index.js` is named
 * by the directory holding it rather than by itself, which is how a Node
 * package's entry point is spelled.
 */
function shortCommand(cmdline) {
    const parts = cmdline.split(' ').filter(Boolean);
    const program = path.basename(parts[0] || '');
    const script = scriptArgument(parts);
    const type = parts.find((arg) => arg.startsWith('--type='));

    let name = script ? path.basename(script) : '';
    if (name === 'index.js') {
        const pkg = path.basename(path.dirname(script));
        if (pkg && pkg !== '.' && pkg !== path.sep) name = `${pkg}/${name}`;
    }
    return [program, name, type].filter(Boolean).join(' ');
}

/**
 * Classify a process by its cmdline.
 */
function classify(cmdline) {
    const cmd = cmdline.toLowerCase();

    // Main Android app process: not a phantom, managed by Activity Manager.
    // Its cmdline is just the package name. Other processes also have the package
    // name in their binary PATH (e.g. /data/app/.../com.vscodroid.debug-.../lib/...)
    // so we must check for exact match, not substring.
    if (/^com\.vscodroid(\.\w+)?$/.test(cmd)) return 'app';

    // What a process IS gets decided on the basename of each argument; where it
    // happens to live does not. Testing the whole command line made every
    // pattern match its own name inside an unrelated path: 'eslint' matched
    // /projects/my-eslint-tool/index.js and put a user's build script one idle
    // period away from SIGTERM, and '/sh' matched /share and /shim. A launcher
    // names what it runs in one argument, and the directories above that name
    // are the user's to choose.
    //
    // A 'safSync' rule used to stay on the whole line here, kept for being
    // directories rather than program names, and it was 'vscode-eslint' again in
    // another shape. Its subject is not a process: saf-writeback is a thread
    // inside the Android app process (SafSyncEngine) and nothing under assets/
    // is named safsync, so the only string it could ever reach was saf-mirrors,
    // which is the root of the local copy of a folder opened through the SAF
    // picker and therefore a directory the user chose. The cost was not a label.
    // A language server whose own program path lies inside a device folder, a
    // venv interpreter selected there or a workspace tsserver under its
    // node_modules, returned here before the patterns below were read, so it
    // never entered lsCpuTracker, never carried an idle verdict, and sat outside
    // the pressure kill and outside 'Kill Idle Servers' alike while holding one
    // of the 32 phantom slots. Where a process runs says nothing about what it
    // is, mirrors included.
    const parts = cmd.split(' ').filter(Boolean);
    const names = parts.map((arg) => path.basename(arg));

    if (cmd.includes('server-main.js')) return 'server';
    if (cmd.includes('bootstrap-fork') && cmd.includes('filewatcher')) return 'fileWatcher';
    // ptyHost is now a worker_thread: no longer visible in /proc
    if (names.includes('libtmux.so') || names.includes('tmux')) return 'tmux';
    if (names.includes('libbash.so') || names.includes('bash')) return 'terminal';
    // No "and not bash" guard needed once this is an exact name rather than a
    // substring: 'bash' is simply not 'sh'.
    if (names.includes('sh')) return 'terminal';

    // The agent host's model backend, which `bootstrap-fork --type=agentHost`
    // launches as node_modules/@github/copilot-<platform>/index.js. No
    // LANG_SERVER_PATTERNS entry can reach it, because the basename every rule
    // above compares is `index.js`: that names the package's entry point and not
    // the program, so a bare word would miss it and a substring would claim every
    // index.js on the device, the user's own included. Matched on the package path
    // instead, the one rule here that reads a directory rather than a program
    // name, and the node_modules segment is what keeps the needle off a directory
    // someone chose themselves.
    //
    // Being unclassified was not cosmetic here. Measured idle on an API 33 and an
    // API 37 emulator, signed out, nothing but the Welcome tab open: 226 MB
    // resident, the largest process this app owns after the Android process
    // itself, and one of only five counted against the phantom budget. As
    // 'unknown' it was in neither reclaim path -- not lsCpuTracker, so the idle
    // kill could not shed it under critical memory pressure, and not the
    // 'Kill Idle Servers' command, which filters on this very type. Both exist for
    // a lazily started, idle-killable server forked by a host, which is what this
    // is.
    //
    // `node_modules/@github/copilot-` on its own is not ours. @github/copilot
    // names eight @github/copilot-<platform> packages as optionalDependencies in
    // its own manifest, so a user who installs the Copilot CLI in a project of
    // their own has that exact fragment under their own node_modules, and a
    // substring over the whole line reaches it. That is the defect that took
    // 'vscode-eslint' out of the list above, in the same shape: the names a loose
    // needle can still reach are the user's, and this label is what makes a
    // process eligible for the SIGTERM below.
    //
    // So the rule asks for the tree this app unpacks, in the one argument that
    // names the program. REH_ROOT is that tree, derived above from where this
    // file sits rather than written out here a second time. It holds both alias
    // sites FirstRunSetup.setupCopilotAndroidAliases builds, the agent host's
    // and the session provider's, and a project directory cannot be inside it.
    const script = scriptArgument(parts);
    if (script.startsWith(REH_PREFIX) &&
        script.includes('/node_modules/@github/copilot-')) return 'langserver';

    for (const pattern of LANG_SERVER_PATTERNS) {
        const needle = pattern.toLowerCase();
        if (names.some((name) => namesProgram(name, needle))) return 'langserver';
    }

    // Generic node bootstrap-fork (extension host, search, etc.)
    if (cmd.includes('bootstrap-fork')) return 'system';

    return 'unknown';
}

function scan() {
    try {
        const warnings = [];
        const now = Date.now();

        // 1. Read all PIDs from /proc owned by our UID.
        //    Android's phantom process killer counts ALL child processes per UID,
        //    not just tree descendants. Tmux daemonizes (reparents to init PID 1),
        //    so tree-walking from rootPid would miss tmux server + its bash children.
        const allProcs = new Map();
        let entries;
        try { entries = fs.readdirSync(procRoot); } catch { return; }

        for (const entry of entries) {
            if (!/^\d+$/.test(entry)) continue;
            const pid = parseInt(entry, 10);
            const status = readProcStatus(pid);
            if (!status || status.uid !== myUid) continue;
            allProcs.set(pid, status);
        }

        // 2. Classify each process, this one included
        const tree = [];
        const activeLsPids = new Set();

        for (const [pid, info] of allProcs) {
            const cmdline = readCmdline(pid);
            if (!cmdline) continue;

            // This code runs inside server.js, which ProcessBuilder launched and
            // which Android's per-UID phantom accounting counts like any other
            // process. Skipping it as "ourselves" reported one fewer than the
            // budget below is measured against, so every threshold fired one
            // process late. Named rather than classified: the bootstrap's own
            // command line matches no pattern and would come out 'unknown'.
            const type = pid === process.pid ? 'bootstrap' : classify(cmdline);
            if (type === 'app') continue; // main Android process, not a phantom
            const entry = { pid, ppid: info.ppid, type, cmd: shortCommand(cmdline) };
            tree.push(entry);

            if (type === 'langserver') {
                activeLsPids.add(pid);
                trackLangServer(pid, now);
                // After the tracking, never before. trackLangServer is what
                // moves lastActive when the CPU time has changed, so asking
                // first answers from the previous scan's reading and reports a
                // server idle in the very scan that saw it do work. A first
                // sighting is not what makes the order matter -- there is no
                // tracker entry then and isIdle says false either way -- so the
                // fixture that holds this is one whose CPU time moves.
                entry.idle = isIdle(pid, now);
            }
        }

        // Clean up tracked LS that no longer exist
        for (const pid of lsCpuTracker.keys()) {
            if (!activeLsPids.has(pid)) {
                lsCpuTracker.delete(pid);
            }
        }

        // 4. Check memory pressure and kill idle LS if needed
        const pressure = readMemoryPressure();
        if (KILL_ON_PRESSURE.has(pressure)) {
            const killed = killIdleLangServers(now);
            for (const k of killed) {
                warnings.push(`Killed idle language server PID ${k.pid} (${k.cmd}) due to memory pressure`);
                log('warn', warnings[warnings.length - 1]);
            }
        }

        // 5. Write snapshot
        const snapshot = {
            timestamp: now,
            total: tree.length,
            budget: {
                current: tree.length,
                idle: IDLE_BASELINE,
                soft: SOFT_BUDGET,
                error: ERROR_BUDGET,
                hard: HARD_LIMIT,
            },
            tree,
            warnings
        };

        // TMPDIR is cacheDir/tmp, and Android deletes an app's cache directory
        // under storage pressure while the app keeps running, so the directory
        // this writes into can go mid-session. Both Kotlin writers into that
        // same path recreate it on the way past (ProcessManager.startServer,
        // MainActivity.writeMemoryPressure); this one did not, and the write
        // then threw ENOENT into a catch that only logs. Nothing else here
        // recreates it, so the status bar counter, its tooltip and the process
        // tree all froze on their last snapshot for the life of the server, and
        // 'Kill Idle Servers' went on SIGTERMing pids read out of it.
        fs.mkdirSync(path.dirname(outputPath), { recursive: true });
        fs.writeFileSync(outputPath, JSON.stringify(snapshot), 'utf8');
    } catch (e) {
        log('error', `Scan failed: ${e.message}`);
    }
}

/**
 * Whether a tracked language server has gone IDLE_KILL_THRESHOLD_MS without its
 * CPU time moving.
 *
 * One definition, two reclaim paths. The pressure kill below applies it here,
 * and the snapshot carries its answer so that the 'Kill Idle Servers' command
 * can apply the same one: that command runs in the extension host, which sees
 * nothing of this process but the JSON file, so before this it filtered on the
 * type alone and SIGTERMed every language server, including the one the user was
 * waiting on. A process whose CPU time could not be read is never tracked, and
 * so is never claimed to be idle.
 */
function isIdle(pid, now) {
    const tracked = lsCpuTracker.get(pid);
    if (!tracked) return false;
    // A SIGTERM this process has not answered keeps it eligible whatever its CPU
    // reading says. Answering a signal is itself work: a server that runs a
    // handler and then does not exit spends CPU doing it, which moves lastActive
    // on the next scan and would otherwise report a process that is refusing to
    // go as busy for another five minutes. The record is dropped when the
    // process really goes, by the cleanup loop in scan().
    if (tracked.termSentAt) return true;
    return now - tracked.lastActive >= IDLE_KILL_THRESHOLD_MS;
}

function trackLangServer(pid, now) {
    const cpuTime = readCpuTime(pid);
    if (cpuTime < 0) return;

    const prev = lsCpuTracker.get(pid);
    // CPU time only ever goes up within one process, so a reading that went
    // backwards is a different process wearing a recycled pid. It starts over
    // rather than inheriting its predecessor's record: that record can carry a
    // SIGTERM this process never received, and the escalation below would then
    // SIGKILL a server that had just started.
    if (!prev || cpuTime < prev.cpuTime) {
        lsCpuTracker.set(pid, { cpuTime, lastActive: now });
        return;
    }

    if (cpuTime !== prev.cpuTime) {
        // CPU time changed: process is active. Spread rather than rebuilt, so
        // that an unanswered SIGTERM survives the update; see isIdle above.
        lsCpuTracker.set(pid, { ...prev, cpuTime, lastActive: now });
    }
    // else: cpuTime unchanged, lastActive stays the same (idle)
}

function killIdleLangServers(now) {
    const killed = [];
    // A copied key list rather than the live map: the deletions below happen
    // inside the loop, and iterating a Map while removing from it is a question
    // this does not need to have an answer to.
    for (const pid of [...lsCpuTracker.keys()]) {
        if (isIdle(pid, now)) {
            const tracked = lsCpuTracker.get(pid);
            // A pid still carrying the last SIGTERM is one that outlived it: the
            // mark is only ever set by an earlier scan, so at least one scan
            // interval has passed and the server has had its chance to exit.
            // Escalate rather than repeat. A second SIGTERM to a process that
            // ignored the first reclaims nothing, and this path runs only when
            // Android has reported critical memory pressure over a server that
            // has not moved in five minutes.
            const escalate = !!tracked.termSentAt;
            try {
                const cmdline = readCmdline(pid);
                process.kill(pid, escalate ? 'SIGKILL' : 'SIGTERM');
                killed.push({ pid, cmd: cmdline });
                // The attempt is recorded rather than forgotten. Deleting here
                // erased the only evidence that a signal was ever sent, so a
                // process that survived it was re-tracked by the next scan as a
                // first sighting and bought itself another five idle minutes,
                // every time, while the warning above had already told the user
                // it was killed. Removal belongs to the cleanup loop in scan(),
                // which drops the pids that really went and runs before this in
                // the same scan.
                lsCpuTracker.set(pid, { ...tracked, termSentAt: now });
            } catch {
                // Process already gone
                lsCpuTracker.delete(pid);
            }
        }
    }
    return killed;
}

function readMemoryPressure() {
    const content = readFileQuiet(pressurePath);
    if (!content) return '';
    // Delete after reading (one-shot signal)
    try { fs.unlinkSync(pressurePath); } catch { }
    return content.trim();
}

module.exports = {
    start, stop, readMemoryPressure, KILL_ON_PRESSURE, PRESSURE_FILENAME, REH_ROOT,
};
