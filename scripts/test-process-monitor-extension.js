/**
 * Self-check for the process monitor extension's notifications.
 *
 * The status bar and the notification are rendered from the same local in the
 * same call, but only the status bar is ever rewritten: it is re-set on every
 * poll, while a notification is composed once and latched by
 * warningShownAtThreshold, and VS Code offers no way to edit one after it is
 * open. So any count baked into that text freezes at the moment it was shown,
 * and the user reads it beside a status bar that has moved on. Measured on an
 * API 35 emulator: the toast said 8 while the bar said 7.
 *
 * The assertion is not "the text contains no digits" -- the soft target is a
 * constant and belongs there. It is that the text is IDENTICAL for two
 * snapshots that differ in every count, which is what "states the condition,
 * not the measurement" actually means and holds whatever wording is chosen.
 *
 *   node scripts/test-process-monitor-extension.js
 */

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const Module = require('module');
const { newestExtensionDir } = require('./lib/bundled-extension');

// Resolved by prefix rather than pinned to a version. A bundled extension's
// version must move whenever its contents do, so a pinned path breaks on the
// next correct change, which is how this check came to fail on a branch whose
// only fault was bumping the extension it tests.
const EXTENSION = path.join(
    newestExtensionDir('vscodroid.vscodroid-process-monitor-'), 'extension.js',
);

// The extension requires 'vscode', which exists only inside the workbench.
// Resolve that name to a stub instead of a file on disk.
const shown = [];
// Kept rather than discarded, because one of the checks below is about what a
// command does when a user runs it. The callback was thrown away here, so the
// only command that sends signals had no cover at all.
const commands = new Map();
const vscodeStub = {
    StatusBarAlignment: { Left: 1, Right: 2 },
    ThemeColor: class { constructor(id) { this.id = id; } },
    commands: {
        registerCommand: (id, fn) => { commands.set(id, fn); return { dispose() {} }; },
    },
    window: {
        createStatusBarItem: () => ({ show() {}, hide() {}, dispose() {} }),
        createOutputChannel: () => ({ appendLine() {}, clear() {}, show() {}, dispose() {} }),
        showWarningMessage: (message) => { shown.push({ level: 'warning', message }); return Promise.resolve(undefined); },
        showErrorMessage: (message) => { shown.push({ level: 'error', message }); return Promise.resolve(undefined); },
        showInformationMessage: (message) => { shown.push({ level: 'info', message }); return Promise.resolve(undefined); },
    },
};
const resolveFilename = Module._resolveFilename;
Module._resolveFilename = function (request, ...rest) {
    return request === 'vscode' ? 'vscode' : resolveFilename.call(this, request, ...rest);
};
require.cache.vscode = { id: 'vscode', filename: 'vscode', loaded: true, exports: vscodeStub };

// Captured before the first call overwrites TMPDIR, which os.tmpdir() reads.
const BASE_TMP = os.tmpdir();

/**
 * Activates a FRESH copy of the extension against one snapshot and returns the
 * notification it raised. Fresh because warningShownAtThreshold is module
 * state: a second activation of the same instance is latched and stays silent,
 * which would make the comparison below pass by saying nothing twice.
 */
function notificationFor(snapshot) {
    const tmp = fs.mkdtempSync(path.join(BASE_TMP, 'vscodroid-ext-'));
    process.env.TMPDIR = tmp;
    fs.writeFileSync(path.join(tmp, 'vscodroid-processes.json'), JSON.stringify(snapshot));

    shown.length = 0;
    delete require.cache[require.resolve(EXTENSION)];
    const extension = require(EXTENSION);
    extension.activate({ subscriptions: [] });
    extension.deactivate();

    fs.rmSync(tmp, { recursive: true, force: true });
    return shown.slice();
}

/**
 * A snapshot whose tree really holds `total` entries, so the two agree.
 *
 * The budget is stated rather than left to the extension's fallbacks, because
 * the tiers are read off it: a fixture that omits one is exercising whatever
 * literal the extension falls back to, which is the thing the block at the
 * bottom of this file exists to catch.
 */
function snapshot(total, terminals, langservers, budget = { idle: 5, soft: 8, error: 14 }) {
    const tree = [];
    for (let i = 0; i < total; i++) {
        const type = i < terminals ? 'terminal' : i < terminals + langservers ? 'langserver' : 'system';
        tree.push({ pid: 1000 + i, ppid: 1, type, cmd: `proc-${i}` });
    }
    return {
        timestamp: 0, total,
        budget: { current: total, hard: 32, ...budget },
        tree, warnings: [],
    };
}

// The warning tier, at two counts that differ in every quantity it used to name.
{
    const quiet = notificationFor(snapshot(8, 3, 2));
    const busy = notificationFor(snapshot(11, 6, 4));

    // Positive control: "identical" is worthless if neither raised anything.
    assert.strictEqual(quiet.length, 1, `the warning tier raised nothing at 8: ${JSON.stringify(quiet)}`);
    assert.strictEqual(busy.length, 1, `the warning tier raised nothing at 11: ${JSON.stringify(busy)}`);
    assert.strictEqual(quiet[0].level, 'warning', `expected a warning, got ${quiet[0].level}`);

    assert.strictEqual(
        quiet[0].message,
        busy[0].message,
        'the warning text changes with the counts, so it freezes at whatever they were when it opened:\n' +
            `  at 8 processes : ${quiet[0].message}\n` +
            `  at 11 processes: ${busy[0].message}`,
    );
}

// The critical tier, same property.
{
    const fourteen = notificationFor(snapshot(14, 3, 2));
    const twenty = notificationFor(snapshot(20, 9, 6));

    assert.strictEqual(fourteen.length, 1, `the critical tier raised nothing at 14: ${JSON.stringify(fourteen)}`);
    assert.strictEqual(twenty.length, 1, `the critical tier raised nothing at 20: ${JSON.stringify(twenty)}`);
    assert.strictEqual(fourteen[0].level, 'error', `expected an error, got ${fourteen[0].level}`);

    assert.strictEqual(
        fourteen[0].message,
        twenty[0].message,
        'the critical text changes with the counts, so it freezes at whatever they were when it opened:\n' +
            `  at 14 processes: ${fourteen[0].message}\n` +
            `  at 20 processes: ${twenty[0].message}`,
    );
}

// Below the warning tier nothing should be raised at all.
{
    const calm = notificationFor(snapshot(4, 1, 0));
    assert.deepStrictEqual(calm, [], `a quiet device was interrupted: ${JSON.stringify(calm)}`);
}

// The tiers are the snapshot's, not this file's. Both toasts carried literals
// while the status bar read the budget, so the error toast fired at 12 while
// the bar turned red at 14; between those two the user was shown an error over
// an amber bar. Asserted by moving the budget rather than by naming a number,
// which is the only form that fails if a literal comes back.
{
    const moved = { idle: 3, soft: 4, error: 6 };
    const warned = notificationFor(snapshot(4, 1, 1, moved));
    assert.strictEqual(warned.length, 1, `no warning at the snapshot's own soft budget: ${JSON.stringify(warned)}`);
    assert.strictEqual(warned[0].level, 'warning', `expected a warning at soft, got ${warned[0].level}`);

    const errored = notificationFor(snapshot(6, 2, 2, moved));
    assert.strictEqual(errored.length, 1, `no notification at the snapshot's own error budget: ${JSON.stringify(errored)}`);
    assert.strictEqual(
        errored[0].level, 'error',
        `at six processes with an error budget of six the extension raised a ${errored[0].level}. ` +
            'The tier is a literal again, so it no longer agrees with the colour the status bar uses.',
    );

    const quiet = notificationFor(snapshot(3, 1, 0, moved));
    assert.deepStrictEqual(quiet, [], `a device at its idle baseline was interrupted: ${JSON.stringify(quiet)}`);
}

// The third reading of the budget, and the only one a fresh copy cannot show.
//
// A warning is raised once and then latched by warningShownAtThreshold, and the
// latch is cleared only below the idle baseline. Every check above builds a new
// copy of the extension, where that flag starts false and the clearing is
// unreachable, so a threshold left as a literal there would be certified by all
// of them. Driven on ONE copy instead, across several polls, which is how the
// extension really runs.
{
    const tmp = fs.mkdtempSync(path.join(BASE_TMP, 'vscodroid-latch-'));
    process.env.TMPDIR = tmp;
    delete require.cache[require.resolve(EXTENSION)];
    const extension = require(EXTENSION);
    const poll = (snap) => {
        fs.writeFileSync(path.join(tmp, 'vscodroid-processes.json'), JSON.stringify(snap));
        shown.length = 0;
        extension.activate({ subscriptions: [] });
        extension.deactivate();
        return shown.slice();
    };

    const moved = { idle: 3, soft: 4, error: 6 };
    assert.strictEqual(poll(snapshot(4, 1, 1, moved)).length, 1, 'no warning at the soft budget');
    assert.deepStrictEqual(
        poll(snapshot(4, 1, 1, moved)), [],
        'the same warning was raised twice, so it is not latched and this check cannot tell a ' +
            'cleared latch from one that was never set',
    );

    // Three is this snapshot's idle baseline, not below it, so the latch must
    // hold. It IS below the 5 that used to be written here, which is the whole
    // discrimination: nothing else in this file can tell the two apart.
    assert.deepStrictEqual(poll(snapshot(3, 1, 0, moved)), [], 'a device at its baseline was interrupted');
    assert.deepStrictEqual(
        poll(snapshot(4, 1, 1, moved)), [],
        'a count of 3 is not below the idle baseline of 3 this snapshot names, yet the latch was ' +
            'cleared and the same warning raised again, so the reset is measured against a ' +
            'literal rather than against the budget',
    );

    // The other direction, so the assertion above is not satisfied by a latch
    // that never clears at all: below the baseline it must, and the warning
    // must come back.
    assert.deepStrictEqual(poll(snapshot(2, 1, 0, moved)), [], 'a quiet device was interrupted');
    assert.strictEqual(
        poll(snapshot(4, 1, 1, moved)).length, 1,
        'the warning never returns after the count fell below the idle baseline, so the latch is ' +
            'permanent and the check above would pass on an extension that warns exactly once',
    );

    fs.rmSync(tmp, { recursive: true, force: true });
}

// The command kills the idle ones and only those.
//
// It filtered on the type alone and SIGTERMed every language server the
// snapshot listed, under a name that promises the idle ones: the server doing
// the work a user is waiting on is the one most likely to be in that list.
// Idleness is CPU time between two scans, which only process-monitor.js can
// see, so the row carries its answer and this asserts the command reads it.
{
    const tree = [
        { pid: 4000001, ppid: 1, type: 'langserver', cmd: 'idle-server', idle: true },
        { pid: 4000002, ppid: 1, type: 'langserver', cmd: 'busy-server', idle: false },
        { pid: 4000003, ppid: 1, type: 'terminal', cmd: 'bash', idle: true },
    ];
    const signalled = [];
    // Installed before the command runs and put back in a finally. The pids are
    // deliberately implausible, so a stub that failed to install would signal
    // nothing that exists rather than something that does.
    const realKill = process.kill;
    process.kill = (pid, signal) => { signalled.push([pid, signal]); };
    try {
        notificationFor({
            timestamp: 0, total: 3,
            budget: { current: 3, idle: 5, soft: 8, error: 14, hard: 32 },
            tree, warnings: [],
        });
        const kill = commands.get('vscodroid.killIdleServers');
        assert.ok(kill, 'the extension no longer registers vscodroid.killIdleServers');
        kill();
    } finally {
        process.kill = realKill;
    }
    assert.deepStrictEqual(
        signalled, [[4000001, 'SIGTERM']],
        'the command named Kill Idle Servers signalled something other than exactly the idle ' +
            'language server: ' + JSON.stringify(signalled),
    );
}

console.log(
    'ok -- both notification tiers say the same thing whatever the counts, stay quiet below ' +
    'them, come from the snapshot rather than from literals, and Kill Idle Servers signals ' +
    'only the idle ones',
);
