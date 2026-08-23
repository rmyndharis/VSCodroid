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
// Collected rather than discarded, for the same reason the callbacks are: the
// details view is a user-facing render with branches of its own, and a stub that
// throws its lines away certified every one of them.
const printed = [];
const vscodeStub = {
    StatusBarAlignment: { Left: 1, Right: 2 },
    ThemeColor: class { constructor(id) { this.id = id; } },
    commands: {
        registerCommand: (id, fn) => { commands.set(id, fn); return { dispose() {} }; },
    },
    window: {
        createStatusBarItem: () => ({ show() {}, hide() {}, dispose() {} }),
        createOutputChannel: () => ({
            // clear() empties it, exactly as the real channel does, so each
            // render below is read on its own rather than on everything the file
            // has printed so far.
            appendLine: (line) => { printed.push(line); },
            clear() { printed.length = 0; },
            show() {},
            dispose() {},
        }),
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
// process-monitor.js measures as what the app costs doing nothing: five, the
// bootstrap, the server, the file watcher, the agent host and the chat backend.
// No session with the workbench open goes under that, so on a device neither
// tier ever came back and both were one-shot for the life of the extension
// host. A recovery that a user can actually produce -- close the extra
// terminals, let the language servers idle-kill -- lands at 6 or 7, which is
// where the error tier has to re-arm if it is to fire on the next project that
// runs the count away.
//
// The warning tier deliberately does NOT re-arm there: 7 is still above the
// target that warning names, so repeating it says nothing the user did not act
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
            'notification carrying the Kill Idle Servers button is raised once per extension ' +
            'host however often the count runs away afterwards',
    );
    assert.strictEqual(back[0].level, 'error', `expected the error tier again, got ${back[0].level}`);

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
            timestamp: Date.now(), total: 3,
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

// And nothing at all off a snapshot nobody is refreshing.
//
// poll() swallows every read and parse failure and keeps the last snapshot, so a
// writer that has stopped is indistinguishable from a count that is simply
// steady -- and it does stop: process-monitor.js runs inside the bootstrap, and
// a bootstrap SIGKILLed while the editor server it forked keeps running leaves
// this extension reading a file nobody writes for the rest of the session. The
// pids in that file are this app's uid, so the kernel delivers whatever it is
// told, against a process tree that has moved on.
//
// And nothing is composed from it either, which is the half that has to be
// decided before the status bar is painted rather than after. updateStatusBar
// latches the tiered notifications, so a stale snapshot at or above the error
// budget used to raise 'The live count is in the status bar' and then have the
// status bar blanked to '--' underneath it, latched until the count next fell
// below the soft budget. The count is 20 on purpose: at anything under the soft
// budget the notification arms would be silent whatever the timestamp, and the
// assertion would pass without testing anything.
//
// Same tree and same idle row as the check above, so the only difference between
// signalling and not is the age of the snapshot.
{
    const stale = snapshot(20, 9, 6);
    stale.timestamp = Date.now() - 10 * 60 * 1000;
    // One row the command is entitled to act on, so the refusal below is the
    // only thing standing between it and a SIGTERM.
    stale.tree.find((p) => p.type === 'langserver').idle = true;
    const idlePid = stale.tree.find((p) => p.idle).pid;

    const run = (snap) => {
        const signalled = [];
        const realKill = process.kill;
        process.kill = (pid, signal) => { signalled.push([pid, signal]); };
        try {
            const raised = notificationFor(snap);
            commands.get('vscodroid.killIdleServers')();
            return { raised, signalled };
        } finally {
            process.kill = realKill;
        }
    };

    // POSITIVE CONTROL, on the identical tree: current, it warns and it signals.
    // Without it both assertions below are satisfied by a fixture that could
    // never have produced either.
    const live = run({ ...stale, timestamp: Date.now() });
    assert.strictEqual(
        live.raised.length, 1,
        `a current snapshot of 20 raised ${live.raised.length} notifications, so the stale case ` +
            'below is not measuring the guard',
    );
    assert.deepStrictEqual(
        live.signalled, [[idlePid, 'SIGTERM']],
        `a current snapshot did not reach the idle server: ${JSON.stringify(live.signalled)}`,
    );

    const old = run(stale);
    assert.deepStrictEqual(
        old.signalled, [],
        'Kill Idle Servers signalled pids read out of a ten-minute-old snapshot: ' +
            JSON.stringify(old.signalled),
    );
    assert.deepStrictEqual(
        old.raised, [],
        'a snapshot nobody is refreshing raised a notification, and it was composed before the ' +
            'status bar it points at was blanked: ' + JSON.stringify(old.raised),
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
    undated.tree.find((p) => p.type === 'langserver').idle = true;
    const signalled = [];
    const realKill = process.kill;
    process.kill = (pid, signal) => { signalled.push([pid, signal]); };
    let raised;
    try {
        raised = notificationFor(undated);
        commands.get('vscodroid.killIdleServers')();
    } finally {
        process.kill = realKill;
    }
    assert.deepStrictEqual(
        raised, [],
        `a snapshot with no timestamp was rendered as current: ${JSON.stringify(raised)}`,
    );
    assert.deepStrictEqual(
        signalled, [],
        'Kill Idle Servers signalled pids read out of a snapshot with no timestamp: ' +
            JSON.stringify(signalled),
    );
}

// The details view names the number that acts on its own, and only when the
// snapshot carries it.
//
// The reclaim budget is the one figure here that makes something happen without
// a user: at or above it process-monitor.js sheds idle language servers whether
// or not Android ever reports memory pressure. It was published in the snapshot
// and rendered nowhere, so the only automatic action this app takes was visible
// solely in the warning left behind afterwards. Both renders are read, because
// each has a fallback for a snapshot written before the field existed and a
// fallback that fires when it should not is the same bug with a different shape.
{
    notificationFor(snapshot(20, 9, 6, { idle: 5, soft: 8, error: 14, reclaim: 24 }));
    commands.get('vscodroid.showProcesses')();
    const named = printed.join('\n');
    assert.match(
        named, /^Budget: 20\/8 soft, 24 reclaim, 32 hard limit$/m,
        `the budget line does not name the reclaim threshold:\n${named}`,
    );
    assert.match(
        named, /once the count reaches 24/,
        `the language-server advice names memory pressure as the only trigger:\n${named}`,
    );

    // The control, on the same counts: without the field neither number is
    // guessed, and both lines are still rendered.
    notificationFor(snapshot(20, 9, 6));
    commands.get('vscodroid.showProcesses')();
    const silent = printed.join('\n');
    assert.ok(
        !/reclaim|once the count reaches/.test(silent),
        `a snapshot with no reclaim budget had one rendered anyway:\n${silent}`,
    );
    assert.match(
        silent, /^Budget: 20\/8 soft, 32 hard limit$/m,
        `the budget line itself went missing with the field:\n${silent}`,
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

console.log(
    'ok -- both notification tiers say the same thing whatever the counts, stay quiet below ' +
    'them, come from the snapshot rather than from literals, Kill Idle Servers signals ' +
    'only the idle ones and nothing at all when the snapshot has stopped moving, and the ' +
    'details view names the reclaim threshold and marks a snapshot nobody is refreshing',
);
