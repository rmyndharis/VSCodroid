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

const EXTENSION = path.join(
    newestExtensionDir('vscodroid.vscodroid-welcome-'), 'extension.js',
);

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
let sideBarVisibility = 'hidden';
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
    workspace: {
        getConfiguration: () => ({ get: () => sideBarVisibility }),
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
 * `commandFor` decides what each executeCommand answers with, which is the only
 * variable: the same extension, the same empty state, a command that works or a
 * command that does not.
 */
async function activate(home, commandFor) {
    executeCommand = commandFor;
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
    extension.deactivate();
    return {
        marker: fs.existsSync(path.join(home, MARKER)),
        aligned: workspaceState.get('vscodroid.secondarySideBar.aligned') === true,
        ran: ran.slice(),
    };
}

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
            'command or from the close in between, and the palette entry hands its failure back',
    );
}

main().catch((err) => {
    console.error(err);
    process.exit(1);
});
