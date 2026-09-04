// @ts-nocheck
'use strict';

const vscode = require('vscode');
const fs = require('fs');
const path = require('path');

const POLL_INTERVAL_MS = 10_000;

// How old a snapshot may be before what it says is no longer about now.
//
// The cadence that decides it is the writer's, process-monitor.js's
// SCAN_INTERVAL_MS, and not the POLL_INTERVAL_MS above, which only happens to
// equal it: a monitor slowed to save battery would make every snapshot this
// extension ever reads stale, and the status item would then read '--' forever
// with nothing here to say why. Written from the reader's constant because that
// is the one in this file, and pinned against the writer's in
// scripts/test-process-monitor.js at two of its beats of slack. Three missed
// writes is a writer that has stopped rather than one that is a beat behind. It
// does stop:
// process-monitor.js lives inside the bootstrap, and a bootstrap that is
// SIGKILLed while the editor server it forked keeps running leaves this
// extension polling a file nobody writes for the rest of the session. Nothing
// here could tell that from a count that was simply steady, because poll()
// swallows every read and parse failure and keeps the last snapshot.
const STALE_AFTER_MS = 3 * POLL_INTERVAL_MS;

/**
 * Whether a snapshot is too old for anything to be decided from it.
 *
 * Written as the negation of "fresh enough" rather than as "older than", so a
 * snapshot carrying no timestamp comes out stale. The direct form is
 * `Date.now() - undefined > STALE_AFTER_MS`, which is NaN > N and therefore
 * false, so a file with no time in it was treated as current by both readers of
 * this answer. An absent field takes the safe answer here for the same reason
 * `budget.soft || 8` and `p.idle === true` do below.
 */
function isStale(snapshot) {
    return !(Date.now() - snapshot.timestamp <= STALE_AFTER_MS);
}

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

    // No command that signals a process. 'Kill Idle Servers' lived here and
    // SIGTERMed the idle rows, and measured on device it freed nothing: the
    // extension that owns a language server restarts it within a second under a
    // new pid, so the count never moved while the toast said the servers were
    // gone. process-monitor.js records the measurement at IDLE_THRESHOLD_MS.

    // Poll the JSON snapshot file
    function poll() {
        let fresh = null;
        try {
            fresh = JSON.parse(fs.readFileSync(snapshotPath, 'utf8'));
        } catch {
            // File not yet written or parse error: keep last state
        }
        if (fresh) lastSnapshot = fresh;

        // A frozen count reads exactly like a steady one, and this is the only
        // place a reader could be told otherwise. Read outside the catch above,
        // because a snapshot that keeps parsing and keeps its old timestamp is
        // the same failure: what matters is whether anyone is still writing. A
        // snapshot that has never been read at all keeps the loading text, which
        // already says what it is.
        //
        // Decided BEFORE anything is rendered from it, which is the order that
        // matters rather than a tidier shape. updateStatusBar composes and
        // latches the tiered notifications, so painting first meant a stale
        // snapshot at or above the error budget raised 'The live count is in the
        // status bar' and then the status bar was blanked to '--' underneath it,
        // latched, until the count next fell below the soft budget. The session
        // this guard exists for is exactly the one that reaches it: an adopted
        // server runs no monitor, and TMPDIR is never cleared, so the first poll
        // can read a previous boot's file at any count at all.
        if (lastSnapshot && isStale(lastSnapshot)) {
            statusBarItem.text = '$(pulse) --';
            statusBarItem.backgroundColor = undefined;
            statusBarItem.tooltip =
                `VSCodroid Process Monitor: no process data for ${STALE_AFTER_MS / 1000} s, ` +
                'so the count is no longer live.';
            return;
        }
        if (fresh) updateStatusBar(fresh);
    }

    poll();
    pollTimer = setInterval(poll, POLL_INTERVAL_MS);

    context.subscriptions.push(statusBarItem, outputChannel, showCmd, {
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
            // Decimal, like every other MB this app prints, so the figure
            // here is the one the phone's storage screen shows. Both sides of
            // usedPercent move together, so the percentage is unchanged; the
            // LOW threshold below now trips at 200 MB rather than at 209.7,
            // which is what it read as while the divisor was binary.
            const availableMB = Math.round((stats.bavail * stats.bsize) / 1000000);
            const totalMB = Math.round((stats.blocks * stats.bsize) / 1000000);
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
    // Nothing actionable is lost. The button opens the details view, which
    // reads lastSnapshot, refreshed by poll() every interval, so it shows the
    // current tree however old the sentence above it is -- and
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
            'Show Details'
        ).then(choice => {
            if (choice === 'Show Details') showProcessTree();
        });
    } else if (total >= soft && !warningShownAtThreshold) {
        warningShownAtThreshold = true;
        vscode.window.showWarningMessage(
            `Phantom processes are above the target of ${idle}. ` +
                'The live count is in the status bar.',
            'Show Details'
        ).then(choice => {
            if (choice === 'Show Details') showProcessTree();
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
        // lost, not the information: the status item recolours on every poll
        // and the details view is a tap away, which is why this is a quiet
        // failure rather than a loud one.
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
// never being marked idle.
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

    // The second reader of that answer, and the one a user reaches deliberately.
    // poll() blanks the status item to '--' for a snapshot nobody is refreshing,
    // but this view is what that blanked item's command opens: the user read a
    // tooltip saying the count is no longer live, tapped it, and was shown a
    // whole tree, a budget line and recommendations with nothing marking them
    // as the last ones written. Said here rather than instead of rendering
    // them, because the rows are still the best available answer.
    if (isStale(s)) {
        outputChannel.appendLine(
            `No process data for the last ${STALE_AFTER_MS / 1000} s, so the rows below are ` +
                'the last ones written rather than what is running now.'
        );
    }

    // A snapshot with no timestamp is one of the stale cases above, and it
    // reaches here: `new Date(undefined)` renders as 'Invalid Date', which reads
    // as a broken extension rather than as a missing field.
    const time = Number.isFinite(s.timestamp)
        ? new Date(s.timestamp).toLocaleTimeString()
        : 'time not recorded';

    outputChannel.appendLine(`VSCodroid Process Tree (${time})`);
    outputChannel.appendLine(`Total phantom processes: ${s.total}`);
    if (s.budget) {
        // Two numbers and not three. A `reclaim` threshold sat between them
        // while the monitor signalled idle servers at 24 processes; a snapshot
        // left on disk by that build still carries the field, and naming it
        // would promise an action nothing takes any more.
        outputChannel.appendLine(
            `Budget: ${s.budget.current}/${s.budget.soft} soft, ${s.budget.hard} hard limit`
        );
    }

    // Storage info
    try {
        const homeDir = process.env.HOME || '';
        if (homeDir) {
            const stats = fs.statfsSync(homeDir);
            const availableMB = Math.round((stats.bavail * stats.bsize) / 1000000);
            outputChannel.appendLine(`Storage available: ${availableMB} MB`);
        }
    } catch { /* ignore */ }

    outputChannel.appendLine('');
    outputChannel.appendLine('PID      PPID     TYPE            COMMAND');
    outputChannel.appendLine('───────  ───────  ──────────────  ────────────────────────');

    // `=== true` and not a truthiness test: a snapshot written before the row
    // carried the field has no answer to give, and an absent one has to mean
    // 'not known to be idle' rather than being coerced into one.
    for (const proc of s.tree || []) {
        const pid = String(proc.pid).padEnd(7);
        const ppid = String(proc.ppid).padEnd(7);
        const type = (proc.type || 'unknown').padEnd(14);
        const idle = proc.idle === true ? '  (idle)' : '';
        outputChannel.appendLine(`${pid}  ${ppid}  ${type}  ${proc.cmd || ''}${idle}`);
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
            // What frees a language server's slot is the extension that owns
            // it, and nothing else. Killing one, by hand or by this app, was
            // measured to bring it back under a new pid within a second, so the
            // advice names the owner rather than a signal. The idle count says
            // which ones are doing nothing for the user right now.
            const idle = langservers.filter(p => p.idle === true).length;
            outputChannel.appendLine(
                `  • ${langservers.length} language servers running, ${idle} idle for 5 min or more. ` +
                    'Each restarts if killed; to free its slot, disable the extension that starts it'
            );
        }
    }
}

function deactivate() {
    if (pollTimer) clearInterval(pollTimer);
}

// TYPE_LABELS is exported for scripts/test-process-monitor.js, which pairs it
// against the types a real scan produces; STALE_AFTER_MS for the same file,
// which holds it against the monitor's own scan cadence. Neither is read by the
// workbench.
module.exports = { activate, deactivate, TYPE_LABELS, STALE_AFTER_MS };
