/**
 * Self-check for the welcome extension's two "it has been done" markers.
 *
 * Both record that a workbench command ran, and both used to be written before
 * the command was asked to run. That is one bug in two places: the walkthrough
 * marker is a file in HOME, so a command that rejected, or an extension host
 * torn down inside the half-second delay, left an installation whose Get Started
 * page never opened again and was reachable only from the palette; the side bar
 * marker is workspace state, so a failed close left the bar open with a record
 * saying it had been handled. Neither had a listener on the rejection either, so
 * the failure surfaced in the host's log as an unhandled one rather than as
 * anything a user or a reader could act on.
 *
 * So each case runs the extension twice against the same fresh HOME: once with
 * the command rejecting, where nothing may be recorded and nothing may escape,
 * and once with it resolving, where the record must appear. The second half is
 * the control: without it an extension that simply never writes the marker at
 * all would pass.
 *
 *   node scripts/test-welcome.js
 */

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const Module = require('module');
const { newestExtensionDir } = require('./lib/bundled-extension');

const EXTENSION_DIR = newestExtensionDir('vscodroid.vscodroid-welcome-');
const EXTENSION = path.join(EXTENSION_DIR, 'extension.js');
const MANIFEST = JSON.parse(
    fs.readFileSync(path.join(EXTENSION_DIR, 'package.json'), 'utf8'),
);

// The user's key and the device fact the extension falls back to. Named once
// because the stub, the cases and the manifest check all have to spell them the
// same way, and a typo in any one of them reads as a feature that is off.
const AUTO_HIDE = 'vscodroid.layout.autoHideSideBar';
const COMPACT = 'vscodroid.layout.compactScreen';

// The extension waits this long before opening the walkthrough, so nothing can
// be asserted until it has elapsed. Read from the file rather than written out
// here, so a change to the delay cannot leave this checking a state the
// extension has not reached yet and calling the absence of a marker a pass.
const DELAY_MS = Number(/\}, (\d+)\);/.exec(fs.readFileSync(EXTENSION, 'utf8'))[1]);
assert.ok(DELAY_MS > 0, 'the walkthrough delay was not found in the extension');

const MARKER = '.vscodroid_welcome_shown';

// The extension requires 'vscode', which exists only inside the workbench.
// Resolved to a stub instead, the way scripts/test-process-monitor-extension.js
// does it.
let executeCommand = () => Promise.resolve();
// Every configuration key the extension reads, answered by name and
// independently of every other. One value plus a catch-all is what left the
// device fallback untested: [AUTO_HIDE] was answered with the case's own
// boolean and every other key with one fixed string, so the branch that reads
// [COMPACT] never ran once. That branch is the one every device takes, because
// the app writes the device fact and never writes the user's key, so nothing
// answers [AUTO_HIDE] until its owner opens Settings. Deleting the branch
// outright left this file green.
const settings = {};
// The listeners the extension registers on `window`, by event name. Held rather
// than dropped, because they are the whole of that feature: nothing else can
// reach them, and a stub without a `window` at all is what turned this script
// red when the first of them was added.
const listeners = {};
const listen = (event) => (fn) => {
    listeners[event] = fn;
    return { dispose() { delete listeners[event]; } };
};
const ran = [];
// Kept rather than discarded, the way scripts/test-process-monitor-extension.js
// keeps them: the palette entry is a handler nothing else here can reach, and it
// is the one command in the file a user runs on purpose.
const registered = new Map();
const vscodeStub = {
    commands: {
        registerCommand: (id, fn) => { registered.set(id, fn); return { dispose() {} }; },
        executeCommand: (id, ...rest) => {
            ran.push(id);
            return executeCommand(id, ...rest);
        },
    },
    window: {
        onDidChangeActiveTextEditor: listen('onDidChangeActiveTextEditor'),
        onDidOpenTerminal: listen('onDidOpenTerminal'),
        onDidChangeActiveTerminal: listen('onDidChangeActiveTerminal'),
    },
    workspace: {
        getConfiguration: () => ({ get: (key) => settings[key] }),
    },
};
const resolveFilename = Module._resolveFilename;
Module._resolveFilename = function (request, ...rest) {
    return request === 'vscode' ? 'vscode' : resolveFilename.call(this, request, ...rest);
};
require.cache.vscode = { id: 'vscode', filename: 'vscode', loaded: true, exports: vscodeStub };

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// An unhandled rejection is a real outcome here, not test hygiene: it is what
// the workbench used to log instead of anything about the walkthrough. Collected
// rather than fatal, so the assertion names it.
const escaped = [];
process.on('unhandledRejection', (reason) => { escaped.push(String(reason)); });

/**
 * Activates a fresh copy against a fresh HOME and returns what it recorded.
 *
 * `commandFor` decides what each executeCommand answers with, and the two layout
 * keys decide what the side bar does when `deliver`, an event name and the value
 * its listener is handed, fires. `autoHide` defaults to the value the manifest
 * contributes, so a case that says nothing about the layout measures a device
 * nobody has expressed a preference on, which is the state every device ships
 * in.
 */
async function activate(home, commandFor, {
    autoHide = MANIFEST.contributes.configuration.properties[AUTO_HIDE].default,
    compact = false,
    deliver = null,
} = {}) {
    executeCommand = commandFor;
    settings['workbench.secondarySideBar.defaultVisibility'] = 'hidden';
    settings[AUTO_HIDE] = autoHide;
    settings[COMPACT] = compact;
    for (const event of Object.keys(listeners)) delete listeners[event];
    ran.length = 0;
    process.env.HOME = home;
    const workspaceState = new Map();
    delete require.cache[require.resolve(EXTENSION)];
    const extension = require(EXTENSION);
    extension.activate({
        subscriptions: [],
        workspaceState: {
            get: (key) => workspaceState.get(key),
            update: (key, value) => { workspaceState.set(key, value); return Promise.resolve(); },
        },
    });
    // Past the delay, and past the microtask turn in which a rejection with no
    // listener is reported.
    await sleep(DELAY_MS + 400);
    // The event the side bar closes on has to be delivered while the extension
    // is still active. Recorded separately from the walkthrough's own close,
    // which has already happened by now, so a case can tell the two apart.
    const beforeOpen = ran.length;
    // Delivered the way the workbench delivers it, to whoever listens. An event
    // the extension does not subscribe to still fires, and doing nothing is then
    // the outcome to measure, so a missing listener is reported, not fatal.
    const listened = Boolean(deliver && listeners[deliver[0]]);
    if (listened) {
        const [event, value] = deliver;
        listeners[event](value);
        await sleep(50);
    }
    const afterOpen = ran.slice(beforeOpen);
    extension.deactivate();
    return {
        marker: fs.existsSync(path.join(home, MARKER)),
        aligned: workspaceState.get('vscodroid.secondarySideBar.aligned') === true,
        ran: ran.slice(),
        listened,
        onOpen: afterOpen,
    };
}

// A terminal as the workbench hands it to a listener: `hideFromUser` unset, as on
// one opened from the panel, the palette or a task.
const A_TERMINAL = { name: 'bash', creationOptions: {} };

// Every route to something on screen that the side bar leaves no room for, with
// the value the workbench hands its listener. Each runs through the same cases
// below, because each is a subscription of its own and can be dropped, or wired
// past the check, on its own. A terminal is on the list because the panel will
// not go below 300px: with the activity bar and the side bar beside it that is
// 506 on a 411 screen, and the 95 that run off the right edge hold the terminal
// tabs and most of the panel's buttons. Its route is the active terminal
// changing, which is what opening one on screen does as well as switching to one.
const ROUTES = {
    'opening a file': ['onDidChangeActiveTextEditor', { document: { uri: 'file:///workspace/a.txt' } }],
    'a terminal becoming the active one': ['onDidChangeActiveTerminal', A_TERMINAL],
};

const REAL_HOME = process.env.HOME;
const rejects = () => Promise.reject(new Error('no such command'));
const resolves = () => Promise.resolve();

async function main() {
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'vscodroid-welcome-'));
    try {
        const broken = await activate(fs.mkdtempSync(path.join(base, 'broken-')), rejects);
        assert.ok(
            broken.ran.includes('workbench.action.openWalkthrough'),
            `the walkthrough was never asked for, so nothing below is measured: ${broken.ran}`,
        );
        assert.strictEqual(
            broken.marker, false,
            'the marker recording that the walkthrough has been shown was written for a command ' +
                'that failed, so this installation never sees Get Started again',
        );
        assert.strictEqual(
            broken.aligned, false,
            'the workspace was marked as having had its secondary side bar aligned by a close ' +
                'that failed, so the bar stays open and nothing tries again',
        );
        assert.deepStrictEqual(
            escaped, [],
            `a rejected workbench command escaped as an unhandled rejection: ${escaped}`,
        );

        // The control. Same extension, same empty state, a command that works.
        const working = await activate(fs.mkdtempSync(path.join(base, 'working-')), resolves);
        assert.strictEqual(
            working.marker, true,
            'the marker was not written after the walkthrough opened, so it opens again on every ' +
                'launch and the check above would pass on an extension that never writes it',
        );
        assert.strictEqual(
            working.aligned, true,
            'the workspace was not marked after the secondary side bar was closed, so the close ' +
                'runs again on every launch and reverses a bar the user opened',
        );
        assert.deepStrictEqual(escaped, [], `an unhandled rejection escaped: ${escaped}`);

        // The close of the Explorer, which neither case above can reach: it runs
        // in the success continuation of the walkthrough command, so the broken
        // case never gets there and the working one never fails there. A
        // rejection in a continuation starts a chain of its own, which is why the
        // handler on the call that opened it does not cover this.
        const closeFails = await activate(
            fs.mkdtempSync(path.join(base, 'close-fails-')),
            (id) => (id === 'workbench.action.closeSidebar' ? rejects() : resolves()),
        );
        assert.ok(
            closeFails.ran.includes('workbench.action.closeSidebar'),
            `the side bar was never asked to close, so nothing below is measured: ${closeFails.ran}`,
        );
        assert.strictEqual(
            closeFails.marker, true,
            'the walkthrough opened and its marker was not written, so a failed close of the ' +
                'Explorer panel costs the user the record that Get Started has been shown',
        );
        assert.deepStrictEqual(
            escaped, [],
            `a rejected close of the side bar escaped as an unhandled rejection: ${escaped}`,
        );

        // The manifest half of the same feature. The Settings editor picks a
        // control from the declared type, and it renders a two-member type array
        // as `nullable-integer`, `nullable-number` or, for anything else,
        // `complex` -- and `complex` is an "Edit in settings.json" link, not a
        // control. `["boolean", "null"]` shipped as exactly that: the one place
        // the description tells the user to set this was the one place they
        // could not. A checkbox is no way back either, because unset and off
        // look the same in one and unset is the answer that follows the screen.
        const declared = MANIFEST.contributes.configuration.properties[AUTO_HIDE];
        assert.strictEqual(
            declared.type, 'string',
            'the setting is no longer declared as a single string type, so the Settings editor ' +
                'may resolve it to `complex` and draw an "Edit in settings.json" link where the ' +
                `control should be: ${JSON.stringify(declared.type)}`,
        );
        assert.deepStrictEqual(
            declared.enum, ['auto', 'on', 'off'],
            'the three answers are what the cases below drive by name and what the description ' +
                `in all fourteen bundles describes: ${JSON.stringify(declared.enum)}`,
        );
        assert.strictEqual(
            declared.default, 'auto',
            'the default has to be the value that follows the screen, or a device nobody has ' +
                `expressed a preference on gets a decision it never made: ${declared.default}`,
        );

        // The side bar closing itself when something it leaves no room for comes
        // on screen, which is the whole of the portrait layout fix on this side.
        // The same cases for every route, because two keys decide it and each of
        // them can be wrong on its own: the app writes the device fact, the user
        // writes the preference, and `auto` is what every device carries until
        // someone opens Settings.
        for (const [route, deliver] of Object.entries(ROUTES)) {
            const phone = await activate(
                fs.mkdtempSync(path.join(base, 'phone-')), resolves,
                { compact: true, deliver },
            );
            assert.ok(
                phone.listened,
                `the extension no longer listens to ${deliver[0]}, so nothing closes the side bar ` +
                    `on a phone after ${route}`,
            );
            assert.ok(
                phone.onOpen.includes('workbench.action.closeSidebar'),
                `${route} did not close the side bar on a phone with no preference set, which ` +
                    `is every phone until its user opens Settings: ${phone.onOpen}`,
            );

            const tablet = await activate(
                fs.mkdtempSync(path.join(base, 'tablet-')), resolves,
                { compact: false, deliver },
            );
            assert.deepStrictEqual(
                tablet.onOpen, [],
                `${route} closed the side bar on a screen wide enough for both, so a tablet ` +
                    `loses its file tree every time: ${tablet.onOpen}`,
            );

            // The other half: the user's answer outranks the screen, both ways
            // round. Each case is given the device fact that disagrees with it,
            // so an extension that reads only the fact would fail both.
            const forcedOn = await activate(
                fs.mkdtempSync(path.join(base, 'forced-on-')), resolves,
                { autoHide: 'on', compact: false, deliver },
            );
            assert.ok(
                forcedOn.onOpen.includes('workbench.action.closeSidebar'),
                `${route}: a user who asked for the side bar to close was ignored on a screen ` +
                    `wide enough for both, so their own setting decides nothing: ${forcedOn.onOpen}`,
            );

            const forcedOff = await activate(
                fs.mkdtempSync(path.join(base, 'forced-off-')), resolves,
                { autoHide: 'off', compact: true, deliver },
            );
            assert.deepStrictEqual(
                forcedOff.onOpen, [],
                `${route}: a user who turned this off on a phone had it closed anyway, which is ` +
                    `the defect the setting exists to let them fix: ${forcedOff.onOpen}`,
            );

            // The same rejection rule as everywhere else in this file: the close
            // is nobody's request, so its failure is not the user's to see.
            const closeRejects = await activate(
                fs.mkdtempSync(path.join(base, 'open-close-fails-')),
                (id) => (id === 'workbench.action.closeSidebar' ? rejects() : resolves()),
                { autoHide: 'on', deliver },
            );
            assert.ok(
                closeRejects.onOpen.includes('workbench.action.closeSidebar'),
                `${route}: the side bar was never asked to close, so the rejection below is not ` +
                    'measured',
            );
            assert.deepStrictEqual(
                escaped, [],
                `a rejected close after ${route} escaped as an unhandled rejection: ${escaped}`,
            );
        }

        // Two terminal events that put nothing on screen, on a phone where a
        // terminal coming on screen closes the bar. A terminal is reported open
        // whether or not anything shows it: a task set to reveal never or silent,
        // or an extension's createTerminal() without show(), adds one to the
        // panel and leaves the panel hidden, so closing the bar for it took the
        // Explorer away with nothing in its place. What shows a terminal also
        // makes it the active one, and this delivers the open alone. And the
        // active terminal becomes undefined when the last one closes, which
        // empties the panel rather than filling it.
        const unshown = await activate(
            fs.mkdtempSync(path.join(base, 'unshown-terminal-')), resolves,
            { compact: true, deliver: ['onDidOpenTerminal', A_TERMINAL] },
        );
        assert.deepStrictEqual(
            unshown.onOpen, [],
            'a terminal that opened without becoming the active one closed the side bar on a ' +
                'phone, so a task running out of sight takes away the view the user was ' +
                `looking at: ${unshown.onOpen}`,
        );

        const noneActive = await activate(
            fs.mkdtempSync(path.join(base, 'no-active-terminal-')), resolves,
            { compact: true, deliver: ['onDidChangeActiveTerminal', undefined] },
        );
        assert.deepStrictEqual(
            noneActive.onOpen, [],
            'closing the last terminal closed the side bar on a phone, where nothing came on ' +
                `screen to need the room: ${noneActive.onOpen}`,
        );

        // The palette entry. Nothing above calls it, because the extension only
        // registers it, and it is the one command here a user chooses: its
        // failure is theirs to be told about, so the handler hands the thenable
        // back to the workbench instead of swallowing it. Both halves are
        // asserted, because a `.catch(() => {})` satisfies the second alone.
        await activate(fs.mkdtempSync(path.join(base, 'palette-')), rejects);
        const open = registered.get('vscodroid.welcome.open');
        assert.ok(open, 'the extension no longer registers vscodroid.welcome.open');
        await assert.rejects(
            async () => open(),
            'the palette command swallowed the failure of the walkthrough it exists to open, so ' +
                'a user who ran it is shown nothing at all',
        );
        assert.deepStrictEqual(escaped, [], `an unhandled rejection escaped: ${escaped}`);
    } finally {
        process.env.HOME = REAL_HOME;
        fs.rmSync(base, { recursive: true, force: true });
    }

    console.log(
        'ok -- neither the walkthrough marker nor the side bar marker is recorded for a command ' +
            'that failed, both are recorded for one that ran, no rejection escapes from either ' +
            'command or from the close in between, the palette entry hands its failure back, ' +
            'the layout setting is declared as something the Settings editor can draw a control ' +
            'for, and opening a file or a terminal becoming the active one closes the side bar ' +
            'where the screen asks for it and where the user does, and nowhere else, not even ' +
            'for a terminal that opens without becoming active',
    );
}

main().catch((err) => {
    console.error(err);
    process.exit(1);
});
