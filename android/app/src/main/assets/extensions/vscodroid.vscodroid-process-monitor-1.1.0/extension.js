// @ts-nocheck
'use strict';

const vscode = require('vscode');
const fs = require('fs');
const path = require('path');

const POLL_INTERVAL_MS = 10_000;

let statusBarItem;
let outputChannel;
let pollTimer;
let lastSnapshot = null;
let warningShownAtThreshold = false;
let criticalShownAtThreshold = false;

function activate(context) {
    const tmpDir = process.env.TMPDIR || '/tmp';
    const snapshotPath = path.join(tmpDir, 'vscodroid-processes.json');

    // Status bar item: right side, low priority (far right)
    statusBarItem = vscode.window.createStatusBarItem(
        vscode.StatusBarAlignment.Right, -100
    );
    statusBarItem.command = 'vscodroid.showProcesses';
    statusBarItem.name = 'Process Monitor';
    statusBarItem.text = '$(pulse) --';
    statusBarItem.tooltip = 'VSCodroid Process Monitor: loading...';
    statusBarItem.show();

    outputChannel = vscode.window.createOutputChannel('VSCodroid Processes');

    // Register commands
    const showCmd = vscode.commands.registerCommand('vscodroid.showProcesses', () => {
        showProcessTree();
    });

    const killIdleCmd = vscode.commands.registerCommand('vscodroid.killIdleServers', () => {
        killIdleLanguageServers();
    });

    // Poll the JSON snapshot file
    function poll() {
        try {
            const raw = fs.readFileSync(snapshotPath, 'utf8');
            const snapshot = JSON.parse(raw);
            lastSnapshot = snapshot;
            updateStatusBar(snapshot);
        } catch {
            // File not yet written or parse error: keep last state
        }
    }

    poll();
    pollTimer = setInterval(poll, POLL_INTERVAL_MS);

    context.subscriptions.push(statusBarItem, outputChannel, showCmd, killIdleCmd, {
        dispose: () => { clearInterval(pollTimer); }
    });
}

function updateStatusBar(snapshot) {
    const total = snapshot.total || 0;
    const tree = snapshot.tree || [];

    statusBarItem.text = `$(pulse) ${total}`;

    // Read from the snapshot rather than repeated here. These two numbers used
    // to be literals in this file with a comment naming a third literal in
    // process-monitor.js, and nothing made the three agree: when the idle set
    // grew, the warning threshold stayed where it was and the item went amber on
    // a fresh install with nothing open. The fallbacks cover a snapshot written
    // before the budget carried them, which is only ever a stale file on disk.
    const budget = snapshot.budget || {};
    const soft = budget.soft || 8;
    const error = budget.error || 14;
    const idle = budget.idle || 5;
    if (total >= error) {
        statusBarItem.backgroundColor = new vscode.ThemeColor(
            'statusBarItem.errorBackground'
        );
    } else if (total >= soft) {
        statusBarItem.backgroundColor = new vscode.ThemeColor(
            'statusBarItem.warningBackground'
        );
    } else {
        statusBarItem.backgroundColor = undefined;
    }

    // Count by type
    const counts = {};
    for (const proc of tree) {
        const label = typeLabel(proc.type);
        counts[label] = (counts[label] || 0) + 1;
    }
    const parts = Object.entries(counts).map(([k, v]) => `${v} ${k}`);

    // Storage info in tooltip
    let storageInfo = '';
    try {
        const homeDir = process.env.HOME || '';
        if (homeDir) {
            const stats = fs.statfsSync(homeDir);
            const availableMB = Math.round((stats.bavail * stats.bsize) / (1024 * 1024));
            const totalMB = Math.round((stats.blocks * stats.bsize) / (1024 * 1024));
            const usedPercent = Math.round(((totalMB - availableMB) / totalMB) * 100);
            storageInfo = `\nStorage: ${availableMB} MB free (${usedPercent}% used)`;
            if (availableMB < 200) {
                storageInfo += ' ⚠ LOW';
            }
        }
    } catch { /* ignore */ }

    statusBarItem.tooltip = `Phantom processes: ${total}\n${parts.join(', ')}${storageInfo}`;

    // Tiered warnings.
    //
    // The condition, never the measurement. Both of these used to name the
    // count, the terminals and the language servers, and all three froze: the
    // status bar above is re-set on every poll, but a notification is composed
    // once, latched by the flags below, and VS Code gives no way to edit one
    // that is already open. So the two disagreed on screen -- measured on an
    // API 35 emulator, the toast read 8 while the bar read 7, and the toast was
    // simply older. The target of 5 stays because it is a constant, not a
    // reading.
    //
    // Nothing actionable is lost. Both buttons and the details view read
    // lastSnapshot, which poll() refreshes every interval, so they act on the
    // current tree however old the sentence above them is -- and
    // showProcessTree() prints the per-type counts and the recommendations that
    // used to be crammed in here, freshly, each time it is opened.
    //
    // The tiers are the snapshot's, like the colours above. They were 12 and 8
    // here while the bar coloured on 14 and 8, so between 12 and 13 an error
    // notification stood in front of a status item that was merely amber, and
    // nothing tied the 12 to any number the monitor publishes. The target named
    // in the warning is the idle baseline, which is a constant of the app rather
    // than a reading of this tree, so it stays in the sentence and now comes from
    // the same place as everything else.
    if (total >= error && !criticalShownAtThreshold) {
        // Both latches, because this count already meets the softer tier as
        // well: error is above soft, and the three arms are one else-if chain
        // over two independent flags. Setting only the critical one left the
        // warning latch false, so the NEXT poll at an unchanged high count fell
        // through to the soft arm and put 'above the target of 5' on top of the
        // error already on screen -- two notifications about one reading, the
        // second the less severe of the two, and both then permanent, since
        // only a count below the idle baseline clears either. Measured on polls
        // of 3, 20, 20, 20: nothing, the error, the warning, nothing.
        criticalShownAtThreshold = true;
        warningShownAtThreshold = true;
        vscode.window.showErrorMessage(
            'Too many phantom processes; Android may start killing them. ' +
                'The live count is in the status bar.',
            'Kill Idle Servers',
            'Show Details'
        ).then(choice => {
            if (choice === 'Kill Idle Servers') killIdleLanguageServers();
            else if (choice === 'Show Details') showProcessTree();
        });
    } else if (total >= soft && !warningShownAtThreshold) {
        warningShownAtThreshold = true;
        vscode.window.showWarningMessage(
            `Phantom processes are above the target of ${idle}. ` +
                'The live count is in the status bar.',
            'Kill Idle Servers',
            'Show Details'
        ).then(choice => {
            if (choice === 'Kill Idle Servers') killIdleLanguageServers();
            else if (choice === 'Show Details') showProcessTree();
        });
    } else if (total < soft) {
        // Re-arming, and each latch comes back at the tier below the one that
        // set it. This asked for `total < idle`, one BELOW the idle baseline,
        // which is the count process-monitor.js measures for a cold session
        // left untouched: five, the bootstrap, the server, the file watcher,
        // the agent host and the chat backend, on API 33 and API 37 alike. A
        // session with the workbench open never has fewer than the workbench
        // costs, so nothing on a device cleared either flag and both tiers were
        // one-shot for the life of the extension host. Only the prompt was
        // lost, not the remedy: the status item recolours on every poll and
        // 'Kill Idle Servers' stays a palette command that showProcessTree()
        // names, which is why this is a quiet failure rather than a loud one.
        //
        // The gap between firing and re-arming is the point, so the count
        // crossing one threshold cannot raise the same notification twice: the
        // critical tier fires at the error budget and comes back only once the
        // count is under the soft one, and the warning tier fires at the soft
        // budget and comes back only once the count is at the idle baseline,
        // which is the app doing nothing. `<=` and not `<` for that one: the
        // baseline is the floor, not a count to get below.
        //
        // The two are separate on purpose. A recovery to 6 or 7 has left the
        // busy range and deserves the error again if the count climbs back past
        // 14, but it is still above the target the warning names, so repeating
        // the warning there says nothing new. An escalation is not affected
        // either way: a latched warning does not block the critical arm, which
        // tests its own flag.
        criticalShownAtThreshold = false;
        if (total <= idle) warningShownAtThreshold = false;
    }
}

function killIdleLanguageServers() {
    if (!lastSnapshot) {
        vscode.window.showInformationMessage('No process data available.');
        return;
    }

    // Idle, not merely present. This filtered on the type alone and SIGTERMed
    // every language server the snapshot listed, which is not what the command
    // is called and not what a user pressing it under memory pressure is
    // agreeing to: the server doing the work they are waiting on is the one
    // most likely to be in the list. Idleness is CPU time between two scans and
    // is decided in process-monitor.js, which is the only side that can see it;
    // the row carries its answer.
    const langservers = (lastSnapshot.tree || []).filter(p => p.type === 'langserver');
    if (langservers.length === 0) {
        vscode.window.showInformationMessage('No language servers running.');
        return;
    }

    // `=== true` and not a truthiness test: a snapshot written before the row
    // carried this field has no answer to give, and an absent one has to mean
    // 'not known to be idle' rather than being coerced into one.
    const idle = langservers.filter(p => p.idle === true);
    if (idle.length === 0) {
        vscode.window.showInformationMessage(
            `${langservers.length} language server${langservers.length !== 1 ? 's' : ''} ` +
                'running, none idle. Idle ones are freed automatically under memory pressure.'
        );
        return;
    }

    let killed = 0;
    for (const proc of idle) {
        try {
            process.kill(proc.pid, 'SIGTERM');
            killed++;
        } catch {
            // Process may have already exited
        }
    }

    vscode.window.showInformationMessage(
        `Sent SIGTERM to ${killed} idle language server${killed !== 1 ? 's' : ''}. They will restart on demand.`
    );
}

// The words a person reads in the status bar tooltip, one per type that
// process-monitor.js scan() can put in a snapshot.
//
// Two ways this went wrong, and the fallback below is why only one of them was
// visible. A type with no entry here was rendered as the internal word itself,
// so the tooltip read '1 bootstrap, 3 system' and offered the reader a term
// that means nothing outside this repository. Falling back to the word
// 'unknown' already maps to keeps that impossible whatever is added to the
// classifier next, and scripts/test-process-monitor.js now fails when a type
// the monitor really emits has no entry here, so the fallback is a floor
// rather than somewhere a type is meant to land.
//
// The other direction was 'ptyHost', an entry for a type classify() cannot
// return: the pty host became a worker_thread and stopped being a process in
// /proc at all, and the stale entry read as evidence that it still was.
// 'safSync' left for the same reason and did more damage on the way: the SAF
// sync engine is a thread inside the Android app process, so the type only ever
// landed on processes that happened to run inside a folder opened from device
// storage, and a language server among them was labelled 'storage' here while
// being outside every reclaim path.
const TYPE_LABELS = {
    bootstrap: 'system',
    server: 'system',
    fileWatcher: 'system',
    system: 'system',
    tmux: 'terminal',
    terminal: 'terminal',
    langserver: 'language server',
    unknown: 'other'
};

function typeLabel(type) {
    return TYPE_LABELS[type] || TYPE_LABELS.unknown;
}

function showProcessTree() {
    outputChannel.clear();
    outputChannel.show(true);

    if (!lastSnapshot) {
        outputChannel.appendLine('No process data available yet.');
        return;
    }

    const s = lastSnapshot;
    const time = new Date(s.timestamp).toLocaleTimeString();

    outputChannel.appendLine(`VSCodroid Process Tree (${time})`);
    outputChannel.appendLine(`Total phantom processes: ${s.total}`);
    if (s.budget) {
        outputChannel.appendLine(
            `Budget: ${s.budget.current}/${s.budget.soft} soft, ${s.budget.hard} hard limit`
        );
    }

    // Storage info
    try {
        const homeDir = process.env.HOME || '';
        if (homeDir) {
            const stats = fs.statfsSync(homeDir);
            const availableMB = Math.round((stats.bavail * stats.bsize) / (1024 * 1024));
            outputChannel.appendLine(`Storage available: ${availableMB} MB`);
        }
    } catch { /* ignore */ }

    outputChannel.appendLine('');
    outputChannel.appendLine('PID      PPID     TYPE            COMMAND');
    outputChannel.appendLine('───────  ───────  ──────────────  ────────────────────────');

    for (const proc of s.tree || []) {
        const pid = String(proc.pid).padEnd(7);
        const ppid = String(proc.ppid).padEnd(7);
        const type = (proc.type || 'unknown').padEnd(14);
        outputChannel.appendLine(`${pid}  ${ppid}  ${type}  ${proc.cmd || ''}`);
    }

    if (s.warnings && s.warnings.length > 0) {
        outputChannel.appendLine('');
        outputChannel.appendLine('Warnings:');
        for (const w of s.warnings) {
            outputChannel.appendLine(`  ⚠ ${w}`);
        }
    }

    // Recommendations
    const tree = s.tree || [];
    const langservers = tree.filter(p => p.type === 'langserver');
    const terminals = tree.filter(p => p.type === 'terminal' || p.type === 'tmux');
    // The same threshold the status item colours on, from the same place, so
    // advice appears exactly when the count is worth acting on.
    if (s.total >= ((s.budget && s.budget.soft) || 8)) {
        outputChannel.appendLine('');
        outputChannel.appendLine('Recommendations:');
        if (terminals.length > 2) {
            outputChannel.appendLine(`  • Close ${terminals.length - 1} terminals (${terminals.length} open, 1-2 recommended)`);
        }
        if (langservers.length > 1) {
            outputChannel.appendLine(`  • ${langservers.length} language servers active: idle ones auto-kill after 5 min under memory pressure`);
            outputChannel.appendLine(`  • Run "VSCodroid: Kill Idle Servers" to free them now`);
        }
    }
}

function deactivate() {
    if (pollTimer) clearInterval(pollTimer);
}

// TYPE_LABELS is exported for scripts/test-process-monitor.js, which pairs it
// against the types a real scan produces. Nothing in the workbench reads it.
module.exports = { activate, deactivate, TYPE_LABELS };
