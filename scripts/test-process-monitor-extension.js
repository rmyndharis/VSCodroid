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
// Kept rather than discarded, because the checks below ask which commands the
// extension registers and what the details command renders when a user runs it.
const commands = new Map();
// Collected rather than discarded, for the same reason the callbacks are: the
// details view is a user-facing render with branches of its own, and a stub that
// throws its lines away certified every one of them.
const printed = [];
// How each status bar item was made. The workbench keeps the items of every
// extension host in one table, and one made without an id is numbered per host,
// so the first such item of a web extension took this item's slot.
const statusItemArgs = [];
// What the Antigravity offer reads and writes: which extensions are installed, the machine setting, the
// configuration writes it made and the commands it ran, whether the workbench refuses the write, and the
// language the window is in. A case sets these before it activates.
const world = { installed: new Set(), runEngine: undefined, updates: [], executed: [], onExtensionsChange: null, answer: undefined, refuseWrite: null, locale: 'en' };
// vscode.l10n.t as the extension host answers it: the extension's bundle for the window's language when
// there is one, the English text otherwise, with {0} filled in.
const l10nDir = path.join(path.dirname(EXTENSION), 'l10n');
function translate(message, ...args) {
    const bundle = path.join(l10nDir, `bundle.l10n.${world.locale}.json`);
    const text = (world.locale !== 'en' && fs.existsSync(bundle) && JSON.parse(fs.readFileSync(bundle, 'utf8'))[message]) || message;
    return text.replace(/\{(\d+)\}/g, (all, n) => (args[n] === undefined ? all : String(args[n])));
}
const vscodeStub = {
    l10n: { t: translate },
    ConfigurationTarget: { Global: 1, Workspace: 2 },
    extensions: {
        getExtension: (id) => (world.installed.has(id.toLowerCase()) ? { id } : undefined),
        onDidChange: (listener) => { world.onExtensionsChange = listener; return { dispose() {} }; },
    },
    workspace: {
        getConfiguration: (section) => ({
            get: (key) => (section === 'vscodroid.antigravity' && key === 'runEngine' ? world.runEngine : undefined),
            update: (key, value, target) => {
                world.updates.push([section, key, value, target]);
                return world.refuseWrite ? Promise.reject(new Error(world.refuseWrite)) : Promise.resolve();
            },
        }),
    },
    StatusBarAlignment: { Left: 1, Right: 2 },
    ThemeColor: class { constructor(id) { this.id = id; } },
    commands: {
        registerCommand: (id, fn) => { commands.set(id, fn); return { dispose() {} }; },
        executeCommand: (id) => { world.executed.push(id); return Promise.resolve(); },
    },
    window: {
        createStatusBarItem: (...args) => { statusItemArgs.push(args); return { show() {}, hide() {}, dispose() {} }; },
        createOutputChannel: () => ({
            // clear() empties it, exactly as the real channel does, so each
            // render below is read on its own rather than on everything the file
            // has printed so far.
            appendLine: (line) => { printed.push(line); },
            clear() { printed.length = 0; },
            show() {},
            dispose() {},
        }),
        // The buttons are kept with the message. A 'Kill Idle Servers' button
        // stood beside 'Show Details' on both tiers, and a stub that dropped the
        // items could not tell a notification offering a signal from one that
        // does not.
        showWarningMessage: (message, ...items) => { shown.push({ level: 'warning', message, items }); return Promise.resolve(items.includes(world.answer) ? world.answer : undefined); },
        showErrorMessage: (message, ...items) => { shown.push({ level: 'error', message, items }); return Promise.resolve(undefined); },
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
 *
 * The default is not process-monitor.js's own budget, on purpose: every count
 * below is written against this one, and the blocks that pass a moved budget
 * are what show the tiers follow whatever the snapshot carries.
 *
 * Stamped with the current time, because the extension now refuses to act on a
 * snapshot older than a few poll intervals. A fixture frozen at zero is a
 * snapshot from 1970 and would exercise that refusal in every check here.
 */
function snapshot(total, terminals, langservers, budget = { idle: 5, soft: 8, error: 14 }) {
    const tree = [];
    for (let i = 0; i < total; i++) {
        const type = i < terminals ? 'terminal' : i < terminals + langservers ? 'langserver' : 'system';
        tree.push({ pid: 1000 + i, ppid: 1, type, cmd: `proc-${i}` });
    }
    return {
        timestamp: Date.now(), total,
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

    // Not "the target of N": the number it named is the idle baseline, what the app costs
    // when nothing is open. Said at the soft budget it read as a goal the user had missed
    // for opening a file, when the count is only something to keep an eye on.
    assert.ok(
        !/\btarget\b/i.test(quiet[0].message),
        `the warning calls the idle baseline a target: ${quiet[0].message}`,
    );

    // One button, and it opens the details view. The other one signalled the
    // idle rows, and measured on device the servers were back under new pids
    // within a second, so the button promised a slot it never freed.
    assert.deepStrictEqual(
        quiet[0].items, ['Show Details'],
        `the warning offers ${JSON.stringify(quiet[0].items)}; a button that signals a ` +
            'language server frees nothing, because its extension restarts it',
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
    assert.deepStrictEqual(
        twenty[0].items, ['Show Details'],
        `the error offers ${JSON.stringify(twenty[0].items)}; a button that signals a ` +
            'language server frees nothing, because its extension restarts it',
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

    // Spread wider than the shipped budget on purpose: the re-arm is at the
    // idle baseline and the tier above it is the soft budget, so a fixture
    // whose two numbers are adjacent has no count between them to probe with.
    const moved = { idle: 3, soft: 6, error: 9 };
    assert.strictEqual(poll(snapshot(6, 1, 1, moved)).length, 1, 'no warning at the soft budget');
    assert.deepStrictEqual(
        poll(snapshot(6, 1, 1, moved)), [],
        'the same warning was raised twice, so it is not latched and this check cannot tell a ' +
            'cleared latch from one that was never set',
    );

    // Four is above this snapshot's idle baseline of three, so the warning latch
    // must hold. It is at or below the 5 that used to be written here, which is
    // the whole discrimination: nothing else in this file can tell a reset
    // measured against the budget from one measured against that literal.
    assert.deepStrictEqual(poll(snapshot(4, 1, 0, moved)), [], 'a settling device was interrupted');
    assert.deepStrictEqual(
        poll(snapshot(6, 1, 1, moved)), [],
        'a count of 4 is above the idle baseline of 3 this snapshot names, yet the warning latch ' +
            'was cleared and the same warning raised again, so the reset is measured against a ' +
            'literal rather than against the budget',
    );

    // The other direction, so the assertion above is not satisfied by a latch
    // that never clears at all: AT the baseline it must clear, and the warning
    // must come back. At, not below: the baseline is what the app costs with
    // nothing happening, so asking for fewer asks for a count no session with
    // the workbench open can reach, and the tier was one-shot for the life of
    // the extension host.
    assert.deepStrictEqual(poll(snapshot(3, 1, 0, moved)), [], 'a quiet device was interrupted');
    assert.strictEqual(
        poll(snapshot(6, 1, 1, moved)).length, 1,
        'the warning never returns after the count fell back to the idle baseline, so the latch ' +
            'is permanent and the check above would pass on an extension that warns exactly once',
    );

    fs.rmSync(tmp, { recursive: true, force: true });
}

// One reading raises one notification, and the tier it raises is the severe one.
//
// The three tiers are an if/else-if chain over two independent latches, and the
// critical arm used to set only its own. So the poll after a critical one, at a
// count that had not moved, fell through to the soft arm and raised 'above the
// target' on top of the error already on screen: two notifications about one
// reading, the second the less severe, and both permanent afterwards because
// only a count below the idle baseline clears either. Measured against the
// shipped file at 3, 20, 20, 20: nothing, the error, the warning, nothing.
//
// Driven on ONE copy across polls, like the block above, because a fresh copy
// starts with both latches false and can only ever show the first of them.
//
// NEGATIVE CONTROL: drop `warningShownAtThreshold = true` from the critical arm
// of updateStatusBar() and the third poll raises the warning again, which is
// what the first assertion below refuses.
{
    const tmp = fs.mkdtempSync(path.join(BASE_TMP, 'vscodroid-tier-'));
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

    // A jump straight past the error budget, which is what a project opening
    // with a terminal and two language servers does between two 10 s polls.
    assert.deepStrictEqual(poll(snapshot(3, 1, 0)), [], 'a quiet device was interrupted');
    const raised = poll(snapshot(20, 9, 6));
    assert.strictEqual(raised.length, 1, `one reading raised ${raised.length} notifications`);
    assert.strictEqual(raised[0].level, 'error', `expected the error tier at 20, got ${raised[0].level}`);

    assert.deepStrictEqual(
        poll(snapshot(20, 9, 6)), [],
        'the poll after a critical one, at a count that had not moved, raised a second ' +
            'notification: the warning tier fires behind the error tier because the critical ' +
            'arm leaves the warning latch unset, and the user reads the milder of the two last',
    );

    // The other direction, so the assertion above is not met by an extension
    // that has simply gone quiet for good: at the baseline both latches clear,
    // and the tiers must still work afterwards.
    assert.deepStrictEqual(poll(snapshot(2, 1, 0)), [], 'a quiet device was interrupted');
    const again = poll(snapshot(20, 9, 6));
    assert.strictEqual(
        again.length, 1,
        'the critical tier never returns after the count fell to the idle baseline, so the ' +
            'assertion above would pass on an extension that notifies exactly once',
    );
    assert.strictEqual(again[0].level, 'error', `expected the error tier again, got ${again[0].level}`);

    fs.rmSync(tmp, { recursive: true, force: true });
}

// Each tier re-arms at the tier below it, and the two are not the same count.
//
// Both latches used to clear together and only below the idle baseline, which
// process-monitor.js measures as what the app costs doing nothing: five up to
// Code - OSS 1.138, the bootstrap, the server, the file watcher, the agent host
// and the chat backend, and three from 1.139.1, where patch 0020 keeps the last
// two from starting. No session with the workbench open goes under that, so on
// a device neither tier ever came back and both were one-shot for the life of
// the extension host. A recovery that a user can actually produce -- close the
// extra terminals, let the language servers idle-kill -- lands at 6 or 7, which
// is where the error tier has to re-arm if it is to fire on the next project
// that runs the count away.
//
// The warning tier deliberately does NOT re-arm there: 7 is still above the
// idle baseline that re-arms it, so repeating it says nothing the user did not act
// on. That is the second assertion, and it is the one that fails on the obvious
// over-correction of clearing both flags below the soft budget.
//
// NEGATIVE CONTROL: restore `} else if (total < idle) {` with both flags cleared
// inside it. The last assertion goes red -- 7 is not below 5, so the error tier
// never returns.
{
    const tmp = fs.mkdtempSync(path.join(BASE_TMP, 'vscodroid-rearm-'));
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

    assert.deepStrictEqual(poll(snapshot(3, 1, 0)), [], 'a quiet device was interrupted');
    assert.strictEqual(poll(snapshot(20, 9, 6)).length, 1, 'the critical tier raised nothing at 20');

    // The recovery: under the soft budget of 8, and nowhere near the idle
    // baseline of 5.
    assert.deepStrictEqual(poll(snapshot(7, 2, 1)), [], 'a recovering device was interrupted');

    assert.deepStrictEqual(
        poll(snapshot(10, 4, 2)), [],
        'the warning tier came back at a count that never returned to the idle baseline, so a ' +
            'user who has already been told once and acted on it is told again for a count ' +
            'they have improved',
    );

    const back = poll(snapshot(20, 9, 6));
    assert.strictEqual(
        back.length, 1,
        'the critical tier never returned after the count fell below the soft budget, so the ' +
            'notification carrying the Show Details button is raised once per extension ' +
            'host however often the count runs away afterwards',
    );
    assert.strictEqual(back[0].level, 'error', `expected the error tier again, got ${back[0].level}`);

    fs.rmSync(tmp, { recursive: true, force: true });
}

// Nothing in the extension signals a process, and no command offers to.
//
// 'Kill Idle Servers' SIGTERMed the idle rows and reported that they would
// restart on demand. Measured on device, the extension owning each server
// restarted it within a second under a new pid, so the count never moved and
// the only thing the command achieved was a re-index. The kill stub is
// installed for the whole of activation and the details view, and the pids are
// deliberately implausible, so a signal that did get sent would name nothing
// that exists.
//
// NEGATIVE CONTROL: register `vscodroid.killIdleServers` again and the first
// assertion goes red before anything is signalled.
{
    const tree = [
        { pid: 4000001, ppid: 1, type: 'langserver', cmd: 'idle-server', idle: true },
        { pid: 4000002, ppid: 1, type: 'langserver', cmd: 'busy-server', idle: false },
        { pid: 4000003, ppid: 1, type: 'terminal', cmd: 'bash' },
    ];
    const signalled = [];
    const realKill = process.kill;
    process.kill = (pid, signal) => { signalled.push([pid, signal]); };
    try {
        commands.clear();
        notificationFor({
            timestamp: Date.now(), total: 20,
            budget: { current: 20, idle: 5, soft: 8, error: 14, hard: 32 },
            tree,
        });
        assert.ok(commands.has('vscodroid.showProcesses'), 'the details view command is gone');
        assert.deepStrictEqual(
            [...commands.keys()].filter((id) => /kill|idle/i.test(id)), [],
            `a command that signals language servers is registered again: ${[...commands.keys()]}`,
        );
        commands.get('vscodroid.showProcesses')();
    } finally {
        process.kill = realKill;
    }
    assert.deepStrictEqual(
        signalled, [],
        `the extension signalled ${JSON.stringify(signalled)}; a language server's extension ` +
            'restarts it, so the signal frees no slot',
    );

    // The idle row is marked, because it is now what the reader has instead of
    // the button: which extension's server is doing nothing for them right now.
    const rows = printed.join('\n');
    assert.match(
        rows, /^4000001 .*idle-server  \(idle\)$/m,
        `the idle language server is not marked in the details view:\n${rows}`,
    );
    assert.doesNotMatch(
        rows, /busy-server.*\(idle\)/,
        `a language server the snapshot calls busy is marked idle:\n${rows}`,
    );
    assert.match(
        rows, /2 language servers running, 1 idle/,
        `the advice does not count the idle server:\n${rows}`,
    );
}

// A snapshot nobody is refreshing composes no notification.
//
// poll() swallows every read and parse failure and keeps the last snapshot, so a
// writer that has stopped is indistinguishable from a count that is simply
// steady -- and it does stop: process-monitor.js runs inside the bootstrap, and
// a bootstrap SIGKILLed while the editor server it forked keeps running leaves
// this extension reading a file nobody writes for the rest of the session.
//
// The staleness has to be decided before the status bar is painted rather than
// after. updateStatusBar latches the tiered notifications, so a stale snapshot
// at or above the error budget used to raise 'The live count is in the status
// bar' and then have the status bar blanked to '--' underneath it, latched until
// the count next fell below the soft budget. The count is 20 on purpose: at
// anything under the soft budget the notification arms would be silent whatever
// the timestamp, and the assertion would pass without testing anything.
{
    const stale = snapshot(20, 9, 6);
    stale.timestamp = Date.now() - 10 * 60 * 1000;

    // POSITIVE CONTROL, on the identical tree: current, it warns. Without it
    // the assertion below is satisfied by a fixture that could never have
    // produced a notification.
    const live = notificationFor({ ...stale, timestamp: Date.now() });
    assert.strictEqual(
        live.length, 1,
        `a current snapshot of 20 raised ${live.length} notifications, so the stale case ` +
            'below is not measuring the guard',
    );

    const old = notificationFor(stale);
    assert.deepStrictEqual(
        old, [],
        'a snapshot nobody is refreshing raised a notification, and it was composed before the ' +
            'status bar it points at was blanked: ' + JSON.stringify(old),
    );
}

// A snapshot with no timestamp at all is stale, not current.
//
// The direct test is `now - timestamp > STALE_AFTER_MS`, which is NaN > N and so
// false, so a file carrying no time was the one thing treated as fresher than a
// file written a second ago. Reached by a snapshot written before the field
// existed, or by a truncated one that still parses.
{
    const undated = snapshot(20, 9, 6);
    delete undated.timestamp;
    const raised = notificationFor(undated);
    assert.deepStrictEqual(
        raised, [],
        `a snapshot with no timestamp was rendered as current: ${JSON.stringify(raised)}`,
    );
}

// A backward clock change cannot turn a stopped writer's old file live again.
{
    const tmp = fs.mkdtempSync(path.join(BASE_TMP, 'vscodroid-clock-'));
    const file = path.join(tmp, 'vscodroid-processes.json');
    const writtenAt = 1800000000000;
    const content = JSON.stringify({ ...snapshot(20, 9, 6), timestamp: writtenAt });
    fs.writeFileSync(file, content);
    const originalNow = Date.now;
    const originalInterval = global.setInterval;
    const originalClear = global.clearInterval;
    const originalItem = vscodeStub.window.createStatusBarItem;
    const originalTmp = process.env.TMPDIR;
    let now = writtenAt + 60000;
    let poll;
    let item;
    let extension;
    try {
        Date.now = () => now;
        global.setInterval = (callback) => { poll = callback; return 1; };
        global.clearInterval = () => {};
        vscodeStub.window.createStatusBarItem = (...args) => (item = originalItem(...args));
        process.env.TMPDIR = tmp;
        shown.length = 0;
        delete require.cache[require.resolve(EXTENSION)];
        extension = require(EXTENSION);
        extension.activate({ subscriptions: [] });
        const limit = extension.STALE_AFTER_MS;
        const observed = [];
        for (const age of [60000, -3600000, -3480000, -1, 0, limit, limit + 1]) {
            now = writtenAt + age;
            const before = shown.length;
            poll();
            commands.get('vscodroid.showProcesses')();
            observed.push({
                age, text: item.text, notifications: shown.length - before,
                staleDetails: /^No process data/.test(printed[0]),
            });
            assert.strictEqual(fs.readFileSync(file, 'utf8'), content, 'the stopped writer changed its file');
        }
        console.log('snapshot clock observations: ' + JSON.stringify(observed));
        assert.deepStrictEqual(observed, [
            { age: 60000, text: '$(pulse) --', notifications: 0, staleDetails: true },
            { age: -3600000, text: '$(pulse) --', notifications: 0, staleDetails: true },
            { age: -3480000, text: '$(pulse) --', notifications: 0, staleDetails: true },
            { age: -1, text: '$(pulse) --', notifications: 0, staleDetails: true },
            { age: 0, text: '$(pulse) 20', notifications: 1, staleDetails: false },
            { age: limit, text: '$(pulse) 20', notifications: 0, staleDetails: false },
            { age: limit + 1, text: '$(pulse) --', notifications: 0, staleDetails: true },
        ], 'a future snapshot was presented as a live count');
    } finally {
        if (extension) extension.deactivate();
        Date.now = originalNow;
        global.setInterval = originalInterval;
        global.clearInterval = originalClear;
        vscodeStub.window.createStatusBarItem = originalItem;
        if (originalTmp === undefined) delete process.env.TMPDIR;
        else process.env.TMPDIR = originalTmp;
        fs.rmSync(tmp, { recursive: true, force: true });
    }
}

// No snapshot at all is no live count either, once the writer has had time to write one.
//
// An adopted editor server runs no monitor, since the monitor lives in the bootstrap that
// died, and TMPDIR can be emptied under it: Clear Caches empties it, and Android may
// reclaim the cache directory it sits in. A page loaded then never reads a snapshot, and
// the status item said 'loading...' for as long as the page stayed open. After the same
// three missed writes that mark a stopped writer, it says what the stale guard says.
//
// NEGATIVE CONTROL: put the condition in poll() back to `lastSnapshot && isStale(lastSnapshot)`
// and the second observation goes red.
{
    const tmp = fs.mkdtempSync(path.join(BASE_TMP, 'vscodroid-empty-'));
    const originalNow = Date.now;
    const originalInterval = global.setInterval;
    const originalItem = vscodeStub.window.createStatusBarItem;
    const originalTmp = process.env.TMPDIR;
    const startedAt = originalNow();
    let now = startedAt;
    let poll;
    let item;
    let extension;
    try {
        Date.now = () => now;
        global.setInterval = (callback) => { poll = callback; return 1; };
        vscodeStub.window.createStatusBarItem = (...args) => (item = originalItem(...args));
        process.env.TMPDIR = tmp;
        shown.length = 0;
        delete require.cache[require.resolve(EXTENSION)];
        extension = require(EXTENSION);
        extension.activate({ subscriptions: [] });
        const limit = extension.STALE_AFTER_MS;
        const observed = [];
        for (const age of [limit, limit + 1]) {
            now = startedAt + age;
            poll();
            observed.push({ age, text: item.text, noData: /^VSCodroid Process Monitor: no process data/.test(item.tooltip) });
        }
        assert.deepStrictEqual(observed, [
            { age: limit, text: '$(pulse) --', noData: false },
            { age: limit + 1, text: '$(pulse) --', noData: true },
        ], 'with no snapshot ever written the status item went on saying it was loading: ' + JSON.stringify(observed));
        assert.deepStrictEqual(shown, [], 'a page with no snapshot raised a notification');
    } finally {
        if (extension) extension.deactivate();
        Date.now = originalNow;
        global.setInterval = originalInterval;
        vscodeStub.window.createStatusBarItem = originalItem;
        if (originalTmp === undefined) delete process.env.TMPDIR;
        else process.env.TMPDIR = originalTmp;
        fs.rmSync(tmp, { recursive: true, force: true });
    }
}

// The heap ceiling is read each time the editor server starts, not when the app opens:
// reopening the app while its notification keeps the server running applies nothing, and
// a restart after a crash applies it with no app restart at all. So the setting says how
// to apply it now, by the notification's own Stop Server button, in every language, and
// the button's name is read from the strings the notification draws it with.
//
// NEGATIVE CONTROL: put back "Takes effect the next time the app is started" in any
// package.nls file and this goes red for that file.
{
    const RES = path.join(__dirname, '../android/app/src/main/res');
    const stopLabel = (locale) => {
        const qualifier = locale ? '-' + locale.replace(/-([a-z]+)$/, (all, region) => '-r' + region.toUpperCase()) : '';
        const strings = fs.readFileSync(path.join(RES, `values${qualifier}`, 'strings.xml'), 'utf8');
        const found = /<string name="action_stop">([^<]+)<\/string>/.exec(strings);
        assert.ok(found, `values${qualifier}/strings.xml has no action_stop, so this cannot say what the button is called`);
        return found[1];
    };
    const files = fs.readdirSync(path.dirname(EXTENSION)).filter((f) => /^package\.nls(\.[a-z-]+)?\.json$/.test(f)).sort();
    assert.strictEqual(files.length, 14, `expected the English base and 13 translations, found ${files.join(', ')}`);
    const wrong = files.filter((file) => {
        const text = JSON.parse(fs.readFileSync(path.join(path.dirname(EXTENSION), file), 'utf8'))['config.server.heapCeilingMb'] || '';
        return !text.includes(stopLabel(file.slice(12, -5))) || /next time the app is started/.test(text);
    });
    assert.deepStrictEqual(
        wrong, [],
        'the heap ceiling setting does not say it takes effect when the editor server next starts, by the ' +
            'notification\'s Stop Server, in: ' + wrong.join(', '),
    );
}

// The details view promises no reclaim, even off a snapshot that still names one.
//
// `budget.reclaim` was the count at which process-monitor.js signalled idle
// language servers, and the view rendered it on the budget line and in the
// advice as the one number that made something happen on its own. The signal is
// gone because it freed nothing, but a snapshot written by the previous build
// can still be on disk in TMPDIR, which an upgrade leaves in place (only Clear
// Caches, or Android reclaiming the cache directory it sits in, empties it), and
// the first poll after an upgrade reads it: naming the threshold then promises an
// action nothing takes. The advice names what does free a slot instead.
//
// NEGATIVE CONTROL: render `s.budget.reclaim` on the budget line again and the
// first assertion goes red.
{
    notificationFor(snapshot(20, 9, 6, { idle: 5, soft: 8, error: 14, reclaim: 24 }));
    commands.get('vscodroid.showProcesses')();
    const named = printed.join('\n');
    assert.match(
        named, /^Budget: 20\/8 soft, 32 hard limit$/m,
        `the budget line names a reclaim threshold nothing acts on:\n${named}`,
    );
    assert.ok(
        !/reclaim|once the count reaches|Kill Idle Servers|freed/.test(named),
        `the advice promises servers are freed by this app:\n${named}`,
    );
    assert.match(
        named, /6 language servers running, 0 idle .*disable the extension/,
        `the advice does not name the extension as what frees a slot:\n${named}`,
    );
}

// And the view says what the status bar already says about a stopped writer.
//
// It is the command the status bar item runs, so the blanked '$(pulse) --' and
// its tooltip lead here: the user read that the count is no longer live, tapped
// it, and was shown a full tree, a budget and recommendations with nothing
// marking them as the last ones written. The rows stay, because they are still
// the best answer available; what changes is that the view no longer presents
// them as now.
{
    const stale = snapshot(20, 9, 6, { idle: 5, soft: 8, error: 14, reclaim: 24 });
    stale.timestamp = Date.now() - 10 * 60 * 1000;
    notificationFor(stale);
    commands.get('vscodroid.showProcesses')();
    assert.match(
        printed[0] || '', /^No process data for the last \d+ s/,
        `a snapshot nobody is refreshing was rendered as current:\n${printed.join('\n')}`,
    );
    assert.match(
        printed.join('\n'), /^Total phantom processes: 20$/m,
        'the rows were dropped rather than marked; they are still the last answer there is',
    );

    // A snapshot with no timestamp is one of those, and it used to head the
    // view with `new Date(undefined)`, which renders as 'Invalid Date'.
    const undated = snapshot(20, 9, 6);
    delete undated.timestamp;
    notificationFor(undated);
    commands.get('vscodroid.showProcesses')();
    const text = printed.join('\n');
    assert.ok(
        !text.includes('Invalid Date'),
        `a snapshot with no timestamp was headed with an unparsed date:\n${text}`,
    );
    assert.match(
        text, /^No process data for the last \d+ s/,
        `a snapshot with no timestamp was rendered as current:\n${text}`,
    );
}

// The chat backend is not counted as a language server, and an agent host adds
// no button to the notification.
//
// The agent host's model backend is typed langserver so the monitor can call it
// idle, but no extension starts it, so the language-server advice would send the
// reader looking for one to disable. Patch 0020 keeps the host from starting on
// Android whatever chat.disableAIFeatures says, so neither the notification nor
// the advice points at that setting any more; this tree stands for a build where
// the host starts again.
//
// NEGATIVE CONTROL: drop `&& !chat.includes(p)` in showProcessTree() and the
// second assertion goes red; offer a second button on either tier while an
// agent host runs and the first does; print advice naming chat again and the
// third does.
{
    const tree = [
        { pid: 5001, ppid: 1, type: 'server', cmd: 'libnode.so server-main.js' },
        { pid: 5002, ppid: 5001, type: 'system', cmd: 'libnode.so bootstrap-fork --type=agentHost' },
        { pid: 5003, ppid: 5002, type: 'langserver', cmd: 'libnode.so copilot-android-arm64/index.js' },
        { pid: 5004, ppid: 5001, type: 'langserver', cmd: 'libnode.so eslintServer.js' },
        { pid: 5005, ppid: 5001, type: 'langserver', cmd: 'libnode.so jsonServerMain' },
    ];
    const snapAt = (total) => ({ timestamp: Date.now(), total, budget: { current: total, idle: 5, soft: 8, error: 14, hard: 32 }, tree });

    // Both tiers, because each is its own call: with only the error tier driven,
    // a button added to the warning alone passed. The error tier last, so the
    // details view below reads the same snapshot it always has.
    for (const [total, level] of [[10, 'warning'], [20, 'error']]) {
        const raised = notificationFor(snapAt(total));
        assert.deepStrictEqual(
            raised.map((n) => [n.level, n.items]), [[level, ['Show Details']]],
            `with an agent host running at ${total} the notification offers ${JSON.stringify(raised)}`,
        );
    }

    commands.get('vscodroid.showProcesses')();
    const advice = printed.join('\n');
    assert.match(
        advice, /2 language servers running/,
        `the chat backend is counted as a language server:\n${advice}`,
    );
    assert.doesNotMatch(
        advice, /Chat holds|disableAIFeatures/,
        `the advice names a chat setting that stops nothing:\n${advice}`,
    );
}

assert.ok(
    statusItemArgs.length > 0 && statusItemArgs.every((args) => typeof args[0] === 'string' && args[0] !== ''),
    'the status bar item is made without an id, so it shares a slot with another extension ' +
    'host\'s item: ' + JSON.stringify(statusItemArgs[0]),
);

// Zero-block filesystems (or virtual filesystems reporting totalMB === 0) must not
// produce NaN% in the storage tooltip.
{
    const tmp = fs.mkdtempSync(path.join(BASE_TMP, 'vscodroid-statfs-'));
    const originalStatfs = fs.statfsSync;
    const originalItem = vscodeStub.window.createStatusBarItem;
    const originalTmp = process.env.TMPDIR;
    const originalHome = process.env.HOME;
    const renderTooltip = (statResult) => {
        let item;
        fs.statfsSync = () => statResult;
        vscodeStub.window.createStatusBarItem = (...args) => (item = originalItem(...args));
        process.env.TMPDIR = tmp;
        process.env.HOME = tmp;
        fs.writeFileSync(path.join(tmp, 'vscodroid-processes.json'), JSON.stringify(snapshot(3, 1, 0)));
        delete require.cache[require.resolve(EXTENSION)];
        const extension = require(EXTENSION);
        extension.activate({ subscriptions: [] });
        extension.deactivate();
        return item.tooltip;
    };
    try {
        const zeroBlocks = renderTooltip({ blocks: 0, bavail: 0, bsize: 4096 });
        assert.ok(
            !zeroBlocks.includes('NaN'),
            `zero-block statfs produced NaN in tooltip: ${zeroBlocks}`,
        );
        assert.match(
            zeroBlocks, /Storage: 0 MB free \(0% used\)/,
            `zero-block statfs did not format storage usage as 0%: ${zeroBlocks}`,
        );
        const normal = renderTooltip({ blocks: 100000, bavail: 25000, bsize: 4096 });
        assert.match(
            normal, /Storage: 102 MB free \(75% used\)/,
            `positive-block statfs did not format storage usage accurately: ${normal}`,
        );
    } finally {
        fs.statfsSync = originalStatfs;
        vscodeStub.window.createStatusBarItem = originalItem;
        if (originalTmp === undefined) delete process.env.TMPDIR;
        else process.env.TMPDIR = originalTmp;
        if (originalHome === undefined) delete process.env.HOME;
        else process.env.HOME = originalHome;
        fs.rmSync(tmp, { recursive: true, force: true });
    }
}

// The Antigravity engine is off until the person turns on a machine setting, and the extension's own
// failure with the setting off is a spawn error that names no setting. So the offer is made when the
// extension is installed and the setting is off, once per window, also when the extension is installed
// later; turning it on writes the machine setting and reloads the window, and nothing else does. A write
// the workbench refuses is said, not dropped; "Don't Show Again" holds across windows until the setting
// is turned on; and the offer is in the window's language.
const TURN_ON = 'Turn On and Reload';
const MISMATCH = 'The setting Antigravity: Run Engine shows as on, but VSCodroid reads it as off in {0}, so the Google Antigravity engine stays off. VSCodroid reads nothing from that file when it has an error VS Code works around, such as a missing comma, when it is larger than 1 MiB, or when its nesting is more than 64 levels deep. Correct the file, then reload the window.';
const NEVER = "Don't Show Again";
const DECLINED = 'antigravity.engineOfferDeclined';
// Recorded only while these cases run, and refused in every one of them: an offer whose promise rejects
// unhandled is a button that silently does nothing.
const unhandled = [];
const recordUnhandled = (e) => { unhandled.push(String(e && e.message)); };
process.on('unhandledRejection', recordUnhandled);
// What platform-fix.js leaves on the extension host's global: its own reading of the setting and the file it
// reads it from. Absent where the shim is not loaded, as in every activation before these cases.
const ENGINE_GATE = Symbol.for('vscodroid.engineGate');
const SETTINGS_FILE = '/data/user/0/com.vscodroid/files/home/.vscodroid/data/Machine/settings.json';
async function antigravityOffer({ installed, runEngine, answer, installLater = false, state = new Map(), refuseWrite = null, locale = 'en', gateReads = undefined, turnedOffLater = false }) {
    if (gateReads === undefined) delete globalThis[ENGINE_GATE];
    else globalThis[ENGINE_GATE] = { engineTurnedOn: () => gateReads, settingsFile: SETTINGS_FILE };
    world.installed = new Set(installed && !installLater ? ['google.google-antigravity'] : []);
    world.runEngine = runEngine;
    world.answer = answer;
    world.refuseWrite = refuseWrite;
    world.locale = locale;
    world.updates = [];
    world.executed = [];
    world.onExtensionsChange = null;
    const tmp = fs.mkdtempSync(path.join(BASE_TMP, 'vscodroid-ext-'));
    process.env.TMPDIR = tmp;
    shown.length = 0;
    delete require.cache[require.resolve(EXTENSION)];
    const extension = require(EXTENSION);
    const globalState = {
        get: (key) => state.get(key),
        update: (key, value) => { if (value === undefined) state.delete(key); else state.set(key, value); return Promise.resolve(); },
    };
    const context = { subscriptions: [], globalState };
    extension.activate(context);
    if (installLater) {
        assert.strictEqual(shown.filter((m) => /Antigravity/.test(m.message)).length, 0, 'the offer was made with the extension not installed');
        world.installed.add('google.google-antigravity');
        world.onExtensionsChange();
    }
    // The person unticks the setting in this window; the engine runs on until a reload.
    if (turnedOffLater) world.runEngine = false;
    // A second change of the extensions must not ask again in the same window.
    if (world.onExtensionsChange) world.onExtensionsChange();
    await new Promise((resolve) => setImmediate(resolve));
    await new Promise((resolve) => setImmediate(resolve));
    extension.deactivate();
    fs.rmSync(tmp, { recursive: true, force: true });
    assert.deepStrictEqual(unhandled, [], `an offer's promise was rejected and nothing handled it (answer ${JSON.stringify(answer)})`);
    return {
        offers: shown.filter((m) => m.level === 'warning' && /Antigravity/.test(m.message)),
        errors: shown.filter((m) => m.level === 'error'),
        updates: world.updates.slice(),
        executed: world.executed.slice(),
        state,
    };
}

(async () => {
    const quiet = await antigravityOffer({ installed: false, runEngine: undefined });
    assert.strictEqual(quiet.offers.length, 0, 'the offer was made with the Antigravity extension not installed');

    const on = await antigravityOffer({ installed: true, runEngine: true });
    assert.strictEqual(on.offers.length, 0, 'the offer was made with the setting already on');

    // VS Code reads the settings file more leniently than the gate that starts the engine: a file with an error it
    // works around (a missing comma), or one past the size or the nesting the gate reads, shows the box ticked
    // while the engine stays off, and the extension's panel then fails with a spawn error that names no setting.
    // So the two readings are compared, and when they disagree the person is told, once per window, which file to
    // correct. Nothing is said when they agree, nor where the gate is not there to ask.
    const agree = await antigravityOffer({ installed: true, runEngine: true, gateReads: true });
    assert.deepStrictEqual(agree.offers, [], 'a notice was shown with VS Code and the gate both reading the setting as on');
    // An engine started in this window runs until the window reloads, so unticking the setting and then
    // installing or enabling any extension must not bring the offer that says the engine is kept off.
    const turnedOff = await antigravityOffer({ installed: true, runEngine: true, gateReads: true, turnedOffLater: true });
    assert.deepStrictEqual(turnedOff.offers, [], 'the engine started in this window and the offer then said VSCodroid keeps it off');
    const disagree = await antigravityOffer({ installed: true, runEngine: true, gateReads: false });
    assert.strictEqual(disagree.offers.length, 1, 'VS Code reads the setting as on and the gate as off, and the person was told nothing (or more than once): ' + disagree.offers.length);
    assert.ok(disagree.offers[0].message.includes(SETTINGS_FILE), 'the notice does not name the file to correct: ' + disagree.offers[0].message);
    assert.deepStrictEqual([disagree.offers[0].items, disagree.updates, disagree.executed], [[], [], []], 'the notice offered, wrote or ran something');
    const notInstalled = await antigravityOffer({ installed: false, runEngine: true, gateReads: false });
    assert.deepStrictEqual(notInstalled.offers, [], 'a notice about the engine was shown with the Antigravity extension not installed');
    const mismatchDe = await antigravityOffer({ installed: true, runEngine: true, gateReads: false, locale: 'de' });
    const germanBundle = JSON.parse(fs.readFileSync(path.join(path.dirname(EXTENSION), 'l10n/bundle.l10n.de.json'), 'utf8'));
    assert.strictEqual(mismatchDe.offers.length, 1);
    assert.ok(germanBundle[MISMATCH], 'the German bundle has no text for the notice');
    assert.strictEqual(mismatchDe.offers[0].message, germanBundle[MISMATCH].replace('{0}', SETTINGS_FILE), 'the notice was not shown in German');
    delete globalThis[ENGINE_GATE];

    const dismissed = await antigravityOffer({ installed: true, runEngine: false, answer: undefined });
    assert.strictEqual(dismissed.offers.length, 1, 'the offer was not made once: ' + dismissed.offers.length);
    assert.deepStrictEqual(dismissed.offers[0].items, [TURN_ON, NEVER]);
    assert.ok(/other apps on this phone/.test(dismissed.offers[0].message), 'the offer does not say what turning the engine on exposes');
    assert.deepStrictEqual([dismissed.updates, dismissed.executed, [...dismissed.state]], [[], [], []], 'a dismissed offer changed a setting, reloaded the window or was kept from coming back');

    const accepted = await antigravityOffer({ installed: true, runEngine: undefined, answer: TURN_ON });
    assert.deepStrictEqual(accepted.updates, [['vscodroid.antigravity', 'runEngine', true, 1]], 'accepting did not write the machine setting through the user target');
    assert.deepStrictEqual(accepted.executed, ['workbench.action.reloadWindow'], 'accepting did not reload the window, so the engine is not started');

    const later = await antigravityOffer({ installed: true, runEngine: false, installLater: true });
    assert.strictEqual(later.offers.length, 1, 'installing the extension after the window loaded was not followed by one offer: ' + later.offers.length);

    // The workbench refuses an extension's write to a settings file it cannot parse, and reports nothing.
    const reason = 'Unable to write into remote user settings. Please open the remote user settings file to correct errors/warnings in it and try again.';
    const refused = await antigravityOffer({ installed: true, runEngine: false, answer: TURN_ON, refuseWrite: reason });
    assert.strictEqual(refused.errors.length, 1, 'a refused write was not shown: ' + JSON.stringify(refused.errors));
    assert.ok(refused.errors[0].message.includes(reason), 'the error does not say why: ' + refused.errors[0].message);
    assert.deepStrictEqual(refused.executed, [], 'the window was reloaded although the setting was not written');

    // "Don't Show Again" lasts across windows, and only while the setting stays off.
    const state = new Map();
    const never = await antigravityOffer({ installed: true, runEngine: false, answer: NEVER, state });
    assert.deepStrictEqual([never.offers.length, never.updates, [...state]], [1, [], [[DECLINED, true]]], 'declining for good was not remembered, or changed the setting');
    const next = await antigravityOffer({ installed: true, runEngine: false, state });
    assert.strictEqual(next.offers.length, 0, 'the offer came back in the next window after "Don\'t Show Again"');
    await antigravityOffer({ installed: true, runEngine: true, state });
    assert.deepStrictEqual([...state], [], 'turning the setting on did not forget "Don\'t Show Again"');
    const offAgain = await antigravityOffer({ installed: true, runEngine: false, state });
    assert.strictEqual(offAgain.offers.length, 1, 'a person who turned the setting on and off again was not told again');

    // In the window's language, the buttons included, and a translated answer still does what it says.
    const german = JSON.parse(fs.readFileSync(path.join(path.dirname(EXTENSION), 'l10n/bundle.l10n.de.json'), 'utf8'));
    const de = await antigravityOffer({ installed: true, runEngine: false, answer: german[TURN_ON], locale: 'de' });
    assert.strictEqual(de.offers.length, 1);
    assert.strictEqual(de.offers[0].message, german[dismissed.offers[0].message], 'the offer was not shown in German');
    assert.deepStrictEqual(de.offers[0].items, [german[TURN_ON], german[NEVER]], 'the buttons were not shown in German');
    assert.deepStrictEqual(de.executed, ['workbench.action.reloadWindow'], 'the German button did not turn the engine on');

    // Non-English locale rendering for the monitor's own UI: status bar tooltip, type labels,
    // tiered notification + localized Show Details button, and process tree output.
    {
        const jaBundle = JSON.parse(fs.readFileSync(path.join(path.dirname(EXTENSION), 'l10n/bundle.l10n.ja.json'), 'utf8'));
        const tmp = fs.mkdtempSync(path.join(BASE_TMP, 'vscodroid-ja-'));
        const originalItem = vscodeStub.window.createStatusBarItem;
        const originalTmp = process.env.TMPDIR;
        const originalHome = process.env.HOME;
        const originalStatfs = fs.statfsSync;
        let item;
        world.locale = 'ja';
        world.installed = new Set();
        world.answer = jaBundle['Show Details'];
        try {
            fs.statfsSync = () => ({ blocks: 100000, bavail: 25000, bsize: 4096 });
            vscodeStub.window.createStatusBarItem = (...args) => (item = originalItem(...args));
            process.env.TMPDIR = tmp;
            process.env.HOME = tmp;
            const snap = snapshot(10, 3, 2);
            snap.tree[3].idle = true;
            fs.writeFileSync(path.join(tmp, 'vscodroid-processes.json'), JSON.stringify(snap));
            shown.length = 0;
            printed.length = 0;
            delete require.cache[require.resolve(EXTENSION)];
            const extension = require(EXTENSION);
            extension.activate({ subscriptions: [] });
            await new Promise((resolve) => setImmediate(resolve));
            extension.deactivate();

            assert.strictEqual(item.name, jaBundle['Process Monitor'], 'status bar name was not localized in Japanese');
            assert.ok(
                item.tooltip.includes(jaBundle['Phantom processes: {0}'].replace('{0}', '10')),
                'status bar tooltip header was not localized in Japanese: ' + item.tooltip,
            );
            assert.ok(
                item.tooltip.includes(jaBundle['terminal']) && item.tooltip.includes(jaBundle['language server']),
                'status bar tooltip type labels were not localized in Japanese: ' + item.tooltip,
            );
            assert.strictEqual(shown.length, 1, 'warning tier was not raised in Japanese');
            assert.strictEqual(
                shown[0].message,
                jaBundle['Phantom processes are adding up; Android may start ending them. The live count is in the status bar.'],
            );
            assert.deepStrictEqual(shown[0].items, [jaBundle['Show Details']]);
            const treeText = printed.join('\n');
            assert.ok(
                treeText.includes(jaBundle['Total phantom processes: {0}'].replace('{0}', '10')) &&
                    treeText.includes(jaBundle['(idle)']) &&
                    treeText.includes(jaBundle['Recommendations:']),
                'clicking localized Show Details did not render localized process tree:\n' + treeText,
            );
        } finally {
            world.locale = 'en';
            world.answer = undefined;
            fs.statfsSync = originalStatfs;
            vscodeStub.window.createStatusBarItem = originalItem;
            if (originalTmp === undefined) delete process.env.TMPDIR;
            else process.env.TMPDIR = originalTmp;
            if (originalHome === undefined) delete process.env.HOME;
            else process.env.HOME = originalHome;
            fs.rmSync(tmp, { recursive: true, force: true });
        }
    }

    // Every language the app ships has the exact same complete key set, non-empty values,
    // all Antigravity keys, and exact {0}/{1}/{2} placeholder parity across all 13 locales.
    const antigravityKeys = [dismissed.offers[0].message, TURN_ON, NEVER, 'The Antigravity engine could not be turned on: {0}', MISMATCH];
    assert.ok(refused.errors[0].message.startsWith(antigravityKeys[3].replace('{0}', '')), 'the error text is not the one the bundles translate');
    const bundles = fs.readdirSync(path.join(path.dirname(EXTENSION), 'l10n')).sort();
    const nls = fs.readdirSync(path.dirname(EXTENSION)).filter((f) => /^package\.nls\.[a-z-]+\.json$/.test(f)).map((f) => f.slice(12, -5)).sort();
    assert.deepStrictEqual(bundles, nls.map((l) => `bundle.l10n.${l}.json`), 'the l10n bundles are not the languages package.nls is translated into');
    const referenceKeys = Object.keys(german).sort();
    assert.ok(referenceKeys.length >= 30, `expected all process-monitor UI strings in l10n bundles, found only ${referenceKeys.length}`);
    for (const key of antigravityKeys) {
        assert.ok(referenceKeys.includes(key), `missing Antigravity key in reference bundle: ${key}`);
    }
    const placeholdersOf = (text) => (text.match(/\{\d+\}/g) || []).sort();
    for (const file of bundles) {
        const bundle = JSON.parse(fs.readFileSync(path.join(path.dirname(EXTENSION), 'l10n', file), 'utf8'));
        assert.deepStrictEqual(Object.keys(bundle).sort(), referenceKeys, `${file} key set drifted from reference bundle`);
        for (const key of referenceKeys) {
            assert.ok(typeof bundle[key] === 'string' && bundle[key].trim().length > 0, `${file}: empty value for "${key}"`);
            assert.deepStrictEqual(
                placeholdersOf(bundle[key]),
                placeholdersOf(key),
                `${file}: placeholder mismatch for "${key}"`,
            );
        }
    }
    const manifestL10n = JSON.parse(fs.readFileSync(path.join(path.dirname(EXTENSION), 'package.json'), 'utf8')).l10n;
    assert.strictEqual(manifestL10n, './l10n', 'package.json does not point the extension host at the bundles');

    const manifest = JSON.parse(fs.readFileSync(path.join(path.dirname(EXTENSION), 'package.json'), 'utf8'));
    const setting = manifest.contributes.configuration.properties['vscodroid.antigravity.runEngine'];
    assert.deepStrictEqual(
        [setting && setting.type, setting && setting.default, setting && setting.scope], ['boolean', false, 'machine'],
        'the engine setting is not a machine setting that is off by default, which is where platform-fix.js reads it',
    );
    process.removeListener('unhandledRejection', recordUnhandled);
    assert.deepStrictEqual(unhandled, [], 'a promise was rejected and nothing handled it');
    console.log('ok -- the Antigravity engine is offered once per window when its extension is installed and the setting is off, in the window\'s language, a refused write is shown, "Don\'t Show Again" holds until the setting is turned on, and turning it on writes the machine setting and reloads');
})().catch((e) => { console.error(e); process.exit(1); });

console.log(
    'ok -- both notification tiers say the same thing whatever the counts, stay quiet below ' +
    'them, come from the snapshot rather than from literals and offer only the details view, ' +
    'nothing signals a process, and the details view marks idle servers, promises no reclaim, ' +
    'marks a snapshot nobody is refreshing and does not count the chat backend as a language server, ' +
    'and its status bar item has an id of its own',
);
