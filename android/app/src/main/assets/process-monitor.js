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

const LANG_SERVER_PATTERNS = [
    'tsserver', 'typescript-language-server',
    'pylsp', 'pyright', 'python-language-server', 'jedi',
    'gopls', 'rust-analyzer', 'clangd',
    'eslint', 'vscode-eslint',
    // The three bundled with VS Code, named as they actually launch. They were
    // 'css-languageserver', 'html-languageserver' and 'json-languageserver',
    // which match nothing: the processes are cssServerMain.js, htmlServerMain.js
    // and jsonServerMain.js. So the servers most likely to be running were the
    // ones the monitor could not see, and the idle-kill never reclaimed them
    // while they counted against the phantom-process budget.
    //
    // Renaming them was not enough on its own, and the second half took another
    // release to find: classify() lower-cases the command line, so a pattern
    // carrying a capital could never match it and these three stayed invisible
    // under their correct names too. Matching is case-insensitive on both sides
    // now, which is why they are still spelled the way the files are.
    // scripts/check-langserver-patterns.py fails the build if these stop matching
    // what ships; scripts/test-process-monitor.js fails it if the matching
    // itself regresses.
    'cssServerMain.js', 'htmlServerMain.js', 'jsonServerMain.js',
    // 'tailwindcss' until the matching moved from the whole command line to the
    // basename of each argument. That string only ever appeared in the extension's
    // *directory* -- bradlc.vscode-tailwindcss-0.16.0 -- so the change quietly
    // un-classified the server it names.
    //
    // Both program names in full, rather than the 'tailwind' they share. A bare
    // 'tailwind' also matches a user's own tailwind.config.js, build-tailwind.js,
    // or an npx tailwindcss run, and being classified 'langserver' is not cosmetic:
    // it puts a process into lsCpuTracker and makes it eligible for the idle kill
    // under memory pressure. Killing a build someone is waiting on is a worse
    // outcome than failing to reclaim a language server.
    //
    // Two entries because neither contains the other: 'tailwindmodeserver.js' does
    // not contain 'tailwindserver'.
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
 * Classify a process by its cmdline.
 */
function classify(cmdline) {
    const cmd = cmdline.toLowerCase();

    // Main Android app process — not a phantom, managed by Activity Manager.
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
    // The paths below stay on the whole line deliberately: saf-mirrors and
    // saf-writeback ARE directories rather than program names, so a basename
    // would never see them.
    const names = cmd.split(' ').filter(Boolean).map((arg) => path.basename(arg));

    if (cmd.includes('server-main.js')) return 'server';
    if (cmd.includes('bootstrap-fork') && cmd.includes('filewatcher')) return 'fileWatcher';
    // SAF sync engine: mirrors content:// URIs to local filesystem for VS Code access
    if (cmd.includes('saf-mirrors') || cmd.includes('saf-writeback') || cmd.includes('safsync')) return 'safSync';
    // ptyHost is now a worker_thread — no longer visible in /proc
    if (names.includes('libtmux.so') || names.includes('tmux')) return 'tmux';
    if (names.includes('libbash.so') || names.includes('bash')) return 'terminal';
    // No "and not bash" guard needed once this is an exact name rather than a
    // substring: 'bash' is simply not 'sh'.
    if (names.includes('sh')) return 'terminal';

    for (const pattern of LANG_SERVER_PATTERNS) {
        const needle = pattern.toLowerCase();
        if (names.some((name) => name.includes(needle))) return 'langserver';
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
            const parts = cmdline.split(' ');
            const shortCmd = path.basename(parts[0]) + (parts[1] ? ' ' + parts[1] : '');

            tree.push({ pid, ppid: info.ppid, type, cmd: shortCmd });

            if (type === 'langserver') {
                activeLsPids.add(pid);
                trackLangServer(pid, now);
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
            budget: { current: tree.length, soft: 5, hard: 32 },
            tree,
            warnings
        };

        fs.writeFileSync(outputPath, JSON.stringify(snapshot), 'utf8');
    } catch (e) {
        log('error', `Scan failed: ${e.message}`);
    }
}

function trackLangServer(pid, now) {
    const cpuTime = readCpuTime(pid);
    if (cpuTime < 0) return;

    const prev = lsCpuTracker.get(pid);
    if (!prev) {
        lsCpuTracker.set(pid, { cpuTime, lastActive: now });
        return;
    }

    if (cpuTime !== prev.cpuTime) {
        // CPU time changed — process is active
        lsCpuTracker.set(pid, { cpuTime, lastActive: now });
    }
    // else: cpuTime unchanged, lastActive stays the same (idle)
}

function killIdleLangServers(now) {
    const killed = [];
    for (const [pid, info] of lsCpuTracker) {
        if (now - info.lastActive >= IDLE_KILL_THRESHOLD_MS) {
            try {
                const cmdline = readCmdline(pid);
                process.kill(pid, 'SIGTERM');
                killed.push({ pid, cmd: cmdline });
                lsCpuTracker.delete(pid);
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

module.exports = { start, stop, readMemoryPressure, KILL_ON_PRESSURE, PRESSURE_FILENAME };
