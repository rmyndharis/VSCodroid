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

const EXTENSION = path.resolve(
    __dirname,
    '../android/app/src/main/assets/extensions/vscodroid.vscodroid-process-monitor-1.0.0/extension.js',
);

// The extension requires 'vscode', which exists only inside the workbench.
// Resolve that name to a stub instead of a file on disk.
const shown = [];
const vscodeStub = {
    StatusBarAlignment: { Left: 1, Right: 2 },
    ThemeColor: class { constructor(id) { this.id = id; } },
    commands: { registerCommand: () => ({ dispose() {} }) },
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

/** A snapshot whose tree really holds `total` entries, so the two agree. */
function snapshot(total, terminals, langservers) {
    const tree = [];
    for (let i = 0; i < total; i++) {
        const type = i < terminals ? 'terminal' : i < terminals + langservers ? 'langserver' : 'system';
        tree.push({ pid: 1000 + i, ppid: 1, type, cmd: `proc-${i}` });
    }
    return { timestamp: 0, total, budget: { current: total, soft: 5, hard: 32 }, tree, warnings: [] };
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
    const twelve = notificationFor(snapshot(12, 3, 2));
    const twenty = notificationFor(snapshot(20, 9, 6));

    assert.strictEqual(twelve.length, 1, `the critical tier raised nothing at 12: ${JSON.stringify(twelve)}`);
    assert.strictEqual(twenty.length, 1, `the critical tier raised nothing at 20: ${JSON.stringify(twenty)}`);
    assert.strictEqual(twelve[0].level, 'error', `expected an error, got ${twelve[0].level}`);

    assert.strictEqual(
        twelve[0].message,
        twenty[0].message,
        'the critical text changes with the counts, so it freezes at whatever they were when it opened:\n' +
            `  at 12 processes: ${twelve[0].message}\n` +
            `  at 20 processes: ${twenty[0].message}`,
    );
}

// Below the warning tier nothing should be raised at all.
{
    const calm = notificationFor(snapshot(4, 1, 0));
    assert.deepStrictEqual(calm, [], `a quiet device was interrupted: ${JSON.stringify(calm)}`);
}

console.log('ok -- both notification tiers say the same thing whatever the counts, and stay quiet below them');
