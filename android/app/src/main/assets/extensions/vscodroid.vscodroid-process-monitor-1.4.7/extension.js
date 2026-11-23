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
 * Whether a snapshot is too old or dated in the future.
 *
 * Written as the negation of "fresh enough" rather than as "older than", so a
 * snapshot carrying no timestamp comes out stale. The direct form is
 * `Date.now() - undefined > STALE_AFTER_MS`, which is NaN > N and therefore
 * false, so a file with no time in it was treated as current by both readers of
 * this answer. An absent field takes the safe answer here for the same reason
 * `budget.soft || 8` and `p.idle === true` do below.
 */
function isStale(snapshot) {
    const age = Date.now() - snapshot.timestamp;
    return !(age >= 0 && age <= STALE_AFTER_MS);
}

// The Google Antigravity extension starts an engine whose port any app on the phone can reach, so
// the app starts that engine only once `vscodroid.antigravity.runEngine` is on (platform-fix.js reads
// the setting where the engine would be started). With the setting off the extension's panel fails
// with a spawn error that says nothing about a setting, so this says it, once per window, and offers
// to turn the setting on. A machine setting, written to the same file the app reads.
//
// "Don't Show Again" lasts while the setting stays off: turning it on clears it, so a person who
// turns it off again later is told again.
//
// VS Code reads the settings file more leniently than platform-fix.js, which recovers from no syntax error
// (a missing comma, say) and reads nothing from a file over 1 MiB or nested more than 64 levels deep, and so
// keeps the engine off over a file whose box the Settings editor shows ticked.
// The panel then fails with the same spawn error, so this compares the two readings, through what
// platform-fix.js leaves on the extension host's global, and names the file to correct when they disagree.
const ANTIGRAVITY = 'Google.google-antigravity';
const ENGINE_DECLINED = 'antigravity.engineOfferDeclined';
const ENGINE_GATE = Symbol.for('vscodroid.engineGate');
let engineOffered = false;
let globalState;

function offerAntigravityEngine() {
    if (engineOffered || !vscode.extensions.getExtension(ANTIGRAVITY)) return;
    const config = vscode.workspace.getConfiguration('vscodroid.antigravity');
    if (config.get('runEngine') === true) {
        // Nothing more in this window: an engine started with the setting on runs until the window reloads,
        // so the offer below would say the engine is kept off while it runs, if the person unticked the
        // setting and then installed or enabled any extension.
        engineOffered = true;
        if (globalState.get(ENGINE_DECLINED)) globalState.update(ENGINE_DECLINED, undefined);
        const gate = globalThis[ENGINE_GATE];
        if (gate && !gate.engineTurnedOn()) {
            vscode.window.showWarningMessage(vscode.l10n.t('The setting Antigravity: Run Engine shows as on, but VSCodroid reads it ' +
                'as off in {0}, so the Google Antigravity engine stays off. VSCodroid reads nothing from that file when it has an ' +
                'error VS Code works around, such as a missing comma, when it is larger than 1 MiB, or when its nesting is more ' +
                'than 64 levels deep. Correct the file, then reload the window.', gate.settingsFile));
        }
        return;
    }
    if (globalState.get(ENGINE_DECLINED)) return;
    engineOffered = true;
    const turnOn = vscode.l10n.t('Turn On and Reload');
    const never = vscode.l10n.t("Don't Show Again");
    vscode.window.showWarningMessage(
        vscode.l10n.t('Google Antigravity is installed, but VSCodroid keeps its engine off. While the engine runs, ' +
            'other apps on this phone can reach it and use the account signed in to it. Turn it on only ' +
            'on a phone whose apps you trust.'),
        turnOn,
        never
    ).then((choice) => {
        if (choice === never) return globalState.update(ENGINE_DECLINED, true);
        if (choice !== turnOn) return undefined;
        // An extension's write is not reported by the workbench, and it refuses one to a settings file it
        // cannot parse, which would leave the button doing nothing.
        return config.update('runEngine', true, vscode.ConfigurationTarget.Global).then(
            () => vscode.commands.executeCommand('workbench.action.reloadWindow'),
            (error) => vscode.window.showErrorMessage(
                vscode.l10n.t('The Antigravity engine could not be turned on: {0}', (error && error.message) || String(error)))
        );
    });
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
    const activatedAt = Date.now();

    // Status bar item: right side, low priority (far right). With an id: the
    // workbench keeps every extension host's items in one table, and an item
    // without one is numbered per host, so the first such item of a web
    // extension took this one's slot.
    statusBarItem = vscode.window.createStatusBarItem(
        'processMonitor', vscode.StatusBarAlignment.Right, -100
    );
    statusBarItem.command = 'vscodroid.showProcesses';
    statusBarItem.name = vscode.l10n.t('Process Monitor');
    statusBarItem.text = '$(pulse) --';
    statusBarItem.tooltip = vscode.l10n.t('VSCodroid Process Monitor: loading...');
    statusBarItem.show();

    outputChannel = vscode.window.createOutputChannel(vscode.l10n.t('VSCodroid Processes'));

    // Register commands
    const showCmd = vscode.commands.registerCommand('vscodroid.showProcesses', () => {
        showProcessTree();
    });

    // Now, and again when the set of extensions changes: the person may install Antigravity later.
    globalState = context.globalState;
    offerAntigravityEngine();
    context.subscriptions.push(vscode.extensions.onDidChange(offerAntigravityEngine));

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
        // server runs no monitor, and TMPDIR outlives the server that wrote into
        // it (only Clear Caches, or Android reclaiming the cache directory it sits
        // in, empties it), so the first poll can read a previous run's file at any
        // count at all. Or no file: then the loading text held for as long as the
        // page stayed open, so after the same three missed writes that mark a
        // stopped writer, a page that has read nothing says so too.
        if (lastSnapshot ? isStale(lastSnapshot) : Date.now() - activatedAt > STALE_AFTER_MS) {
            statusBarItem.text = '$(pulse) --';
            statusBarItem.backgroundColor = undefined;
            statusBarItem.tooltip = vscode.l10n.t(
                'VSCodroid Process Monitor: no process data for {0} s, so the count is no longer live.',
                STALE_AFTER_MS / 1000
            );
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
            if (Number.isFinite(availableMB) && Number.isFinite(totalMB)) {
                const usedPercent = totalMB > 0
                    ? Math.max(0, Math.min(100, Math.round(((totalMB - availableMB) / totalMB) * 100)))
                    : 0;
                const storageLine = availableMB < 200
                    ? vscode.l10n.t('Storage: {0} MB free ({1}% used) ⚠ LOW', availableMB, usedPercent)
                    : vscode.l10n.t('Storage: {0} MB free ({1}% used)', availableMB, usedPercent);
                storageInfo = `\n${storageLine}`;
            }
        }
    } catch { /* ignore */ }

    const header = vscode.l10n.t('Phantom processes: {0}', total);
    statusBarItem.tooltip = `${header}\n${parts.join(', ')}${storageInfo}`;

    // Tiered warnings.
    //
    // The condition, never the measurement. Both of these used to name the
    // count, the terminals and the language servers, and all three froze: the
    // status bar above is re-set on every poll, but a notification is composed
    // once, latched by the flags below, and VS Code gives no way to edit one
    // that is already open. So the two disagreed on screen: measured on an
    // API 35 emulator, the toast read 8 while the bar read 7, and the toast was
    // simply older. The warning used to name the idle baseline, a constant of the
    // app and not a reading, as "the target"; it no longer names a number at all, see
    // below.
    //
    // Nothing actionable is lost. The button opens the details view, which
    // reads lastSnapshot, refreshed by poll() every interval, so it shows the
    // current tree however old the sentence above it is, and
    // showProcessTree() prints the per-type counts and the recommendations that
    // used to be crammed in here, freshly, each time it is opened.
    //
    // The tiers are the snapshot's, like the colours above. They were 12 and 8
    // here while the bar coloured on 14 and 8, so between 12 and 13 an error
    // notification stood in front of a status item that was merely amber, and
    // nothing tied the 12 to any number the monitor publishes.
    if (total >= error && !criticalShownAtThreshold) {
        // Both latches, because this count already meets the softer tier as
        // well: error is above soft, and the three arms are one else-if chain
        // over two independent flags. Setting only the critical one left the
        // warning latch false, so the NEXT poll at an unchanged high count fell
        // through to the soft arm and put 'above the target of 5' on top of the
        // error already on screen: two notifications about one reading, the
        // second the less severe of the two, and both then permanent, since
        // only a count below the idle baseline clears either. Measured on polls
        // of 3, 20, 20, 20: nothing, the error, the warning, nothing.
        criticalShownAtThreshold = true;
        warningShownAtThreshold = true;
        const showDetails = vscode.l10n.t('Show Details');
        vscode.window.showErrorMessage(
            vscode.l10n.t('Too many phantom processes; Android may start killing them. The live count is in the status bar.'),
            showDetails
        ).then((choice) => {
            if (choice === showDetails) showProcessTree();
        });
    } else if (total >= soft && !warningShownAtThreshold) {
        warningShownAtThreshold = true;
        // Not "the target of N". The number it named was the idle baseline, what the app
        // costs with nothing open, and said at the soft budget it read as a goal the user
        // had missed by opening a file. The sentence is the condition, as the error's is.
        const showDetails = vscode.l10n.t('Show Details');
        vscode.window.showWarningMessage(
            vscode.l10n.t('Phantom processes are adding up; Android may start ending them. The live count is in the status bar.'),
            showDetails
        ).then((choice) => {
            if (choice === showDetails) showProcessTree();
        });
    } else if (total < soft) {
        // Re-arming, and each latch comes back at the tier below the one that
        // set it. This asked for `total < idle`, one BELOW the idle baseline,
        // which is the count process-monitor.js measures for a cold session
        // left untouched: five up to Code - OSS 1.138, the bootstrap, the
        // server, the file watcher, the agent host and the chat backend, and
        // three from 1.139.1, where patch 0020 keeps the last two from
        // starting. A session with the workbench open never has fewer than the
        // workbench costs, so nothing on a device cleared either flag and both
        // tiers were one-shot for the life of the extension host. Only the
        // prompt was lost, not the information: the status item recolours on
        // every poll and the details view is a tap away, which is why this is a
        // quiet failure rather than a loud one.
        //
        // The gap between firing and re-arming is the point, so the count
        // crossing one threshold cannot raise the same notification twice: the
        // critical tier fires at the error budget and comes back only once the
        // count is under the soft one, and the warning tier fires at the soft
        // budget and comes back only once the count is at the idle baseline,
        // which is the app doing nothing. `<=` and not `<` for that one: the
        // baseline is the floor, not a count to get below.
        //
        // The two are separate on purpose. A recovery to under the soft budget
        // but above the idle baseline has left the busy range and deserves the
        // error again if the count climbs back to the error budget, but it is
        // still above the soft budget the warning fires at, so repeating the warning
        // there says nothing new. An escalation is not affected either way: a
        // latched warning does not block the critical arm, which tests its own
        // flag.
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
    return vscode.l10n.t(TYPE_LABELS[type] || TYPE_LABELS.unknown);
}

function showProcessTree() {
    outputChannel.clear();
    outputChannel.show(true);

    if (!lastSnapshot) {
        outputChannel.appendLine(vscode.l10n.t('No process data available yet.'));
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
            vscode.l10n.t(
                'No process data for the last {0} s, so the rows below are the last ones written rather than what is running now.',
                STALE_AFTER_MS / 1000
            )
        );
    }

    // A snapshot with no timestamp is one of the stale cases above, and it
    // reaches here: `new Date(undefined)` renders as 'Invalid Date', which reads
    // as a broken extension rather than as a missing field.
    const time = Number.isFinite(s.timestamp)
        ? new Date(s.timestamp).toLocaleTimeString()
        : vscode.l10n.t('time not recorded');

    outputChannel.appendLine(vscode.l10n.t('VSCodroid Process Tree ({0})', time));
    outputChannel.appendLine(vscode.l10n.t('Total phantom processes: {0}', s.total));
    if (s.budget) {
        // Two numbers and not three. A `reclaim` threshold sat between them
        // while the monitor signalled idle servers at 24 processes; a snapshot
        // left on disk by that build still carries the field, and naming it
        // would promise an action nothing takes any more.
        outputChannel.appendLine(
            vscode.l10n.t('Budget: {0}/{1} soft, {2} hard limit', s.budget.current, s.budget.soft, s.budget.hard)
        );
    }

    // Storage info
    try {
        const homeDir = process.env.HOME || '';
        if (homeDir) {
            const stats = fs.statfsSync(homeDir);
            const availableMB = Math.round((stats.bavail * stats.bsize) / 1000000);
            outputChannel.appendLine(vscode.l10n.t('Storage available: {0} MB', availableMB));
        }
    } catch { /* ignore */ }

    outputChannel.appendLine('');
    outputChannel.appendLine(vscode.l10n.t('PID      PPID     TYPE            COMMAND'));
    outputChannel.appendLine('───────  ───────  ──────────────  ────────────────────────');

    // `=== true` and not a truthiness test: a snapshot written before the row
    // carried the field has no answer to give, and an absent one has to mean
    // 'not known to be idle' rather than being coerced into one.
    const idleSuffix = `  ${vscode.l10n.t('(idle)')}`;
    for (const proc of s.tree || []) {
        const pid = String(proc.pid).padEnd(7);
        const ppid = String(proc.ppid).padEnd(7);
        const type = (proc.type || 'unknown').padEnd(14);
        const idle = proc.idle === true ? idleSuffix : '';
        outputChannel.appendLine(`${pid}  ${ppid}  ${type}  ${proc.cmd || ''}${idle}`);
    }

    // Recommendations
    const tree = s.tree || [];
    // The chat agent host's model backend is typed langserver so the monitor can
    // call it idle, but no extension starts it: the language-server advice below
    // would send the reader looking for one to disable, so it is left out. Patch
    // 0020 keeps the host from starting on Android; this covers a build where it
    // starts again.
    const chat = chatProcesses(tree);
    const langservers = tree.filter(p => p.type === 'langserver' && !chat.includes(p));
    const terminals = tree.filter(p => p.type === 'terminal' || p.type === 'tmux');
    // The same threshold the status item colours on, from the same place, so
    // advice appears exactly when the count is worth acting on.
    if (s.total >= ((s.budget && s.budget.soft) || 8)) {
        outputChannel.appendLine('');
        outputChannel.appendLine(vscode.l10n.t('Recommendations:'));
        if (terminals.length > 2) {
            outputChannel.appendLine(
                `  • ${vscode.l10n.t('Close {0} terminals ({1} open, 1-2 recommended)', terminals.length - 1, terminals.length)}`
            );
        }
        if (langservers.length > 1) {
            // What frees a language server's slot is the extension that owns
            // it, and nothing else. Killing one, by hand or by this app, was
            // measured to bring it back under a new pid within a second, so the
            // advice names the owner rather than a signal. The idle count says
            // which ones are doing nothing for the user right now.
            const idle = langservers.filter(p => p.idle === true).length;
            outputChannel.appendLine(
                `  • ${vscode.l10n.t('{0} language servers running, {1} idle for 5 min or more. Each restarts if killed; to free its slot, disable the extension that starts it', langservers.length, idle)}`
            );
        }
    }
}

// The agent host and every process it started, which is its model backend.
// Matched on the fork's own --type argument, the way process-monitor.js tells a
// file watcher from any other bootstrap-fork.
function chatProcesses(tree) {
    const host = tree.find(p => /(^| )--type=agentHost( |$)/.test(p.cmd || ''));
    return host ? tree.filter(p => p === host || p.ppid === host.pid) : [];
}

function deactivate() {
    if (pollTimer) clearInterval(pollTimer);
}

// TYPE_LABELS is exported for scripts/test-process-monitor.js, which pairs it
// against the types a real scan produces; STALE_AFTER_MS for the same file,
// which holds it against the monitor's own scan cadence. Neither is read by the
// workbench.
module.exports = { activate, deactivate, TYPE_LABELS, STALE_AFTER_MS };
