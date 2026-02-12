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
const MEMORY_PRESSURE_KILL_LEVEL = 15; // TRIM_MEMORY_RUNNING_CRITICAL

const LANG_SERVER_PATTERNS = [
    'tsserver', 'typescript-language-server',
    'pylsp', 'pyright', 'python-language-server',
    'gopls', 'rust-analyzer', 'clangd',
    'eslint', 'vscode-eslint', 'css-languageserver',
    'html-languageserver', 'json-languageserver'
];

let outputPath = '';
let pressurePath = '';
let rootPid = 0;
let myUid = 0;
let scanTimer = null;

// Track language server CPU time for idle detection: pid -> { cpuTime, lastActive }
const lsCpuTracker = new Map();

function start(serverMainPid) {
    const tmpDir = process.env.TMPDIR || '/tmp';
    outputPath = path.join(tmpDir, 'vscodroid-processes.json');
    pressurePath = path.join(tmpDir, 'vscodroid-memory-pressure');
    rootPid = serverMainPid;
    myUid = process.getuid();

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
    const content = readFileQuiet(`/proc/${pid}/status`);
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
    const content = readFileQuiet(`/proc/${pid}/cmdline`);
    if (!content) return '';
    return content.replace(/\0/g, ' ').trim();
}

/**
 * Read cumulative CPU time (utime + stime) from /proc/{pid}/stat.
 * Fields 14 and 15 (0-indexed) after splitting by space.
 */
function readCpuTime(pid) {
    const content = readFileQuiet(`/proc/${pid}/stat`);
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

    if (cmd.includes('server-main.js')) return 'server';
    if (cmd.includes('bootstrap-fork') && cmd.includes('filewatcher')) return 'fileWatcher';
    // SAF sync engine: mirrors content:// URIs to local filesystem for VS Code access
    if (cmd.includes('saf-mirrors') || cmd.includes('saf-writeback') || cmd.includes('SafSync')) return 'safSync';
    // ptyHost is now a worker_thread — no longer visible in /proc
    if (cmd.includes('libtmux.so') || cmd.includes('/tmux')) return 'tmux';
    if (cmd.includes('libbash.so') || cmd.includes('/bash')) return 'terminal';
    if (cmd.includes('/sh') && !cmd.includes('bash')) return 'terminal';

    for (const pattern of LANG_SERVER_PATTERNS) {
        if (cmd.includes(pattern)) return 'langserver';
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
        try { entries = fs.readdirSync('/proc'); } catch { return; }

        for (const entry of entries) {
            if (!/^\d+$/.test(entry)) continue;
            const pid = parseInt(entry, 10);
            const status = readProcStatus(pid);
            if (!status || status.uid !== myUid) continue;
            allProcs.set(pid, status);
        }

        // 2. Classify each process (skip server.js bootstrap — that's us)
        const tree = [];
        const activeLsPids = new Set();

        for (const [pid, info] of allProcs) {
            if (pid === process.pid) continue; // skip ourselves (server.js)
            const cmdline = readCmdline(pid);
            if (!cmdline) continue;

            const type = classify(cmdline);
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
        if (pressure >= MEMORY_PRESSURE_KILL_LEVEL) {
            const killed = killIdleLangServers(now);
            for (const k of killed) {
                warnings.push(`Killed idle language server PID ${k.pid} (${k.cmd}) due to memory pressure`);
                log('warn', warnings[warnings.length - 1]);
            }
        }

        // 5. Write snapshot (excluding server.js bootstrap itself)
        const snapshot = {
            timestamp: now,
            total: tree.length,
            budget: { current: tree.length, soft: 8, hard: 12 },
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
    if (!content) return 0;
    // Delete after reading (one-shot signal)
    try { fs.unlinkSync(pressurePath); } catch { }
    return parseInt(content.trim(), 10) || 0;
}

module.exports = { start, stop };
