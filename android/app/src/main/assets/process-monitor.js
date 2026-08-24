/**
 * VSCodroid Process Monitor
 *
 * Scans /proc to count and classify the processes this app's uid owns, marks
 * the language servers among them idle once their CPU time has sat still, and
 * writes a JSON snapshot for the status bar extension to read.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const SCAN_INTERVAL_MS = 10_000;

// How long a language server's CPU time has to sit still before the snapshot
// reports it idle. Reported, never acted on.
//
// This module used to SIGTERM idle servers under critical memory pressure and
// at 24 processes, and the status bar extension offered the same by hand.
// Measured on an API 37 emulator: a SIGTERM to cssServerMain was answered by a
// new cssServerMain under a fresh pid within one second, and the process count
// never moved. Every server the patterns below name is owned by a client that
// restarts it the moment it exits, vscode-languageclient's default error
// handler for the CSS, HTML, JSON, Markdown, ESLint, Tailwind and Python
// clients and tsserver's own exit handler for TypeScript, and none of those
// clients' restart caps is reached by a kill every five minutes. The restart
// was a first sighting here, so the same server went idle again five minutes
// later and was killed again, for the life of the session, each cycle costing
// a re-index on a device already short of memory. The only party that can stop
// one is the extension that starts it, and there is no API for asking it to,
// so the snapshot says which servers are idle and the details view says that
// disabling the owning extension is what frees the slot.
const IDLE_THRESHOLD_MS = 5 * 60 * 1000;

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

// Where this app unpacks the editor, and the anchor the chat-backend rule in
// classify() is written against.
//
// Derived rather than spelled out. server.js requires this module out of its own
// directory and builds the server tree beside it the same way, from __dirname,
// so this is the same path by construction instead of by agreement. It was a
// hand-written copy of a location the Kotlin side owns in five places, and the
// half of that which nothing would have caught is a move of the PARENT
// directory: renaming the leaf makes server.js exit naming the entry point it
// cannot find, which is loud, while moving `server/` elsewhere leaves every
// __dirname-relative path in server.js working and only stops this rule
// matching -- and the chat agent's model backend, 226 MB and one of five
// processes counted against the phantom budget, goes back to 'unknown', shown
// as 'other' in the tooltip and never marked idle in the details view.
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
    // neither is reachable from a bare 'pyright' or 'jedi'. ms-python runs
    // python_files/run-jedi-language-server.py through the interpreter, and it
    // is the default Python server this app writes into settings.json, so it is
    // the one most likely to be holding a process slot on a stock install;
    // pyright ships dist/pyright-langserver.js. Hyphens put both on the
    // substring arm, which is what a .py entry point requires.
    'pyright', 'pyright-langserver',
    // 'jedi' alone was here and reached nothing: a bare word has to be the whole
    // basename, and no program on this device is called jedi or jedi.js. The one
    // that exists is run-jedi-language-server.py, which the hyphenated entry
    // matches on the substring arm.
    'jedi-language-server',
    'gopls', 'rust-analyzer', 'clangd',
    // 'eslintServer' for the server the bundled ESLint extension forks, which
    // its client names as server/out/eslintServer.js.
    //
    // A bare 'eslint' was beside it and named the wrong program. The basename of
    // `node node_modules/eslint/bin/eslint.js`, which is how the CLI is reached
    // here because SELinux refuses to execve the .bin shim under filesDir, is
    // eslint.js -- the whole basename plus a Node extension, which is exactly
    // what the bare-word arm accepts. So running ESLint from a terminal or a
    // task was reported as a language server, and once it sat still as an idle
    // one, while the server it looked like it covered was already covered by
    // the entry below.
    //
    // 'vscode-eslint' was here and is gone. It reached nothing: that string only
    // ever appeared in the extension's directory name, which stopped being
    // compared when classification moved to argument basenames. Left in place it
    // was worse than inert, because a hyphen puts it on the substring arm, where
    // the only names it can still reach are a user's own: `node
    // vscode-eslint-shim.js` was classified langserver and shown to the user as
    // one.
    'eslintServer',
    // The four bundled with the editor, named as they actually launch. They were
    // 'css-languageserver', 'html-languageserver' and 'json-languageserver',
    // which match nothing: the files are cssServerMain.js, htmlServerMain.js
    // and jsonServerMain.js. So the servers most likely to be running were the
    // ones the monitor could not name, while they counted against the
    // phantom-process budget.
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
    // 'tailwind' also names a user's own tailwind.js build script, and the label
    // is what the details view and the advice beside it are built on: a build
    // someone is waiting on, reported as an idle language server, points them
    // at an extension that does not own it.
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
let myUid = 0;
let scanTimer = null;
// Overridable so the suite can point a scan at a fixture. /proc does not exist
// on a macOS workstation and holds an uncontrolled process list on a CI runner,
// which left every branch below unreachable from a test.
let procRoot = '/proc';

// Track language server CPU time for idle detection: pid -> { cpuTime, lastActive }
const lsCpuTracker = new Map();

// No pid parameter, and its absence is the accurate description. This took the
// forked editor server's pid, assigned it to a module-level `rootPid` and printed
// it in one log line; nothing else ever read it. The scan is by uid and has to be
// (see the note in scan()), so a parameter that looks like it selects a process
// tree and selects nothing is a false lead for whoever reads this next.
function start(options) {
    const tmpDir = process.env.TMPDIR || '/tmp';
    outputPath = path.join(tmpDir, 'vscodroid-processes.json');
    myUid = process.getuid();
    procRoot = (options && options.procRoot) || '/proc';

    log('info', `Started (UID=${myUid})`);

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
 * tested and not only the one the launcher runs. The label is what the user is
 * shown: five minutes with no CPU movement then report the process as an idle
 * language server, beside advice that names extensions as what to disable. So
 * a bare word has to be the whole name, give or take the extension a Node entry
 * point carries.
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
    // /projects/my-eslint-tool/index.js and reported a user's build script as a
    // language server, and '/sh' matched /share and /shim. A launcher
    // names what it runs in one argument, and the directories above that name
    // are the user's to choose.
    //
    // A 'safSync' rule used to stay on the whole line here, kept for being
    // directories rather than program names, and it was 'vscode-eslint' again in
    // another shape. Its subject is not a process: saf-writeback is a thread
    // inside the Android app process (SafSyncEngine) and nothing under assets/
    // is named safsync, so the only string it could ever reach was saf-mirrors,
    // which is the root of the local copy of a folder opened through the SAF
    // picker and therefore a directory the user chose. A language server whose
    // own program path lies inside a device folder, a venv interpreter selected
    // there or a workspace tsserver under its node_modules, returned here
    // before the patterns below were read, so it never entered lsCpuTracker and
    // never carried an idle verdict while holding one of the 32 phantom slots.
    // Where a process runs says nothing about what it is, mirrors included.
    const parts = cmd.split(' ').filter(Boolean);
    const names = parts.map((arg) => path.basename(arg));

    // These two read basenames like everything below them. They were the last
    // whole-line tests in this function, left behind when the rest moved, and
    // they carried the same defect the note above describes: any path containing
    // 'server-main.js' was labelled 'server' and any path containing
    // 'bootstrap-fork' was labelled 'system', both of which return before
    // LANG_SERVER_PATTERNS is read. A user's own directory was enough to put a
    // language server outside lsCpuTracker, and so outside the idle verdict,
    // while it went on holding a phantom slot.
    //
    // The `--type=` argument rather than the string anywhere on the line for the
    // same reason: it is the argument the launcher uses to say what it started,
    // and its basename is the whole argument, so `names` cannot see it.
    const forkType = parts.find((arg) => arg.startsWith('--type='));
    if (names.includes('server-main.js')) return 'server';
    if (names.includes('bootstrap-fork') && forkType === '--type=filewatcher') return 'fileWatcher';
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
    // 'unknown' it was never in lsCpuTracker, so the details view could not say
    // it was idle, which for a backend nobody is talking to is the one fact
    // worth showing.
    //
    // `node_modules/@github/copilot-` on its own is not ours. @github/copilot
    // names eight @github/copilot-<platform> packages as optionalDependencies in
    // its own manifest, so a user who installs the Copilot CLI in a project of
    // their own has that exact fragment under their own node_modules, and a
    // substring over the whole line reaches it. That is the defect that took
    // 'vscode-eslint' out of the list above, in the same shape: the names a loose
    // needle can still reach are the user's, and this label is what the user is
    // told about the process.
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
    if (names.includes('bootstrap-fork')) return 'system';

    return 'unknown';
}

function scan() {
    try {
        const now = Date.now();

        // 1. Read all PIDs from /proc owned by our UID.
        //    Android's phantom process killer counts ALL child processes per UID,
        //    not just tree descendants. Tmux daemonizes (reparents to init PID 1),
        //    so tree-walking from the forked server would miss tmux server + its
        //    bash children.
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

        // 3. Clean up tracked LS that no longer exist
        for (const pid of lsCpuTracker.keys()) {
            if (!activeLsPids.has(pid)) {
                lsCpuTracker.delete(pid);
            }
        }

        // 4. Write snapshot. Nothing is signalled between the scan and the
        //    write: see IDLE_THRESHOLD_MS for why a kill here reclaims nothing.
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
            tree
        };

        // TMPDIR is cacheDir/tmp, and Android deletes an app's cache directory
        // under storage pressure while the app keeps running, so the directory
        // this writes into can go mid-session. The Kotlin writer into that
        // same path recreates it on the way past (ProcessManager.startServer);
        // this one did not, and the write
        // then threw ENOENT into a catch that only logs. Nothing else here
        // recreates it, so the status bar counter, its tooltip and the process
        // tree all froze on their last snapshot for the life of the server.
        //
        // Through a temporary file and a rename, for the same reason server.js
        // writes product.json and the pid note that way. The reader is a
        // different process on a timer of the same period as this one, so once
        // the two phase-align a readFileSync lands inside the truncate-and-write
        // and comes back as JSON that does not parse. The extension answers that
        // by keeping its previous state, which is exactly what it does when the
        // monitor has stopped writing altogether, so the two are indistinguishable
        // from the side that reads it. rename(2) replaces the file in one step:
        // a reader sees the old snapshot or the new one.
        //
        // One fixed name and not one per pid. This runs every SCAN_INTERVAL_MS in
        // a process the platform SIGKILLs as a matter of routine, so a kill
        // landing between the write and the rename leaves the temporary file
        // behind and nothing sweeps TMPDIR; a pid in the name makes each of those
        // a new file that accumulates for the life of the install. There is
        // exactly one writer, so a leftover is simply overwritten by the next
        // scan.
        fs.mkdirSync(path.dirname(outputPath), { recursive: true });
        const tmpPath = `${outputPath}.tmp`;
        try {
            fs.writeFileSync(tmpPath, JSON.stringify(snapshot), 'utf8');
            fs.renameSync(tmpPath, outputPath);
        } catch (e) {
            try { fs.unlinkSync(tmpPath); } catch { /* nothing was written */ }
            throw e;
        }
    } catch (e) {
        log('error', `Scan failed: ${e.message}`);
    }
}

/**
 * Whether a tracked language server has gone IDLE_THRESHOLD_MS without its CPU
 * time moving.
 *
 * Decided here because this is the only side that can see it: the status bar
 * extension runs in the extension host and sees nothing of this process but the
 * JSON file, so the row carries the answer and the details view prints it. A
 * process whose CPU time could not be read is never tracked, and so is never
 * claimed to be idle.
 */
function isIdle(pid, now) {
    const tracked = lsCpuTracker.get(pid);
    if (!tracked) return false;
    return now - tracked.lastActive >= IDLE_THRESHOLD_MS;
}

function trackLangServer(pid, now) {
    const cpuTime = readCpuTime(pid);
    if (cpuTime < 0) return;

    const prev = lsCpuTracker.get(pid);
    // Any movement is activity. CPU time only ever goes up within one process,
    // so a reading that went backwards is a different process wearing a
    // recycled pid, and it starts its idle clock over like any first sighting.
    if (!prev || cpuTime !== prev.cpuTime) {
        lsCpuTracker.set(pid, { cpuTime, lastActive: now });
    }
    // else: cpuTime unchanged, lastActive stays the same (idle)
}

// SCAN_INTERVAL_MS is exported for scripts/test-process-monitor.js alone: the
// status bar extension decides from its own constant how old a snapshot may be
// before it stops trusting it, and that bound only holds while this cadence
// stays under it. Nothing on device reads it.
module.exports = { start, stop, REH_ROOT, SCAN_INTERVAL_MS };
