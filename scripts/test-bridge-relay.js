/**
 * Self-check for the BroadcastChannel bridge relay, end to end.
 *
 *   node scripts/test-bridge-relay.js
 *
 * The relay is JavaScript written inside a Kotlin raw string and handed to
 * `WebView.evaluateJavascript`. Nothing compiles it, nothing lints it, and no
 * unit test reads it -- Kotlin tests exercise the bridge methods it calls and
 * stop at the language boundary. So the half of a bridge change that is
 * actually user-visible had no cover at all: inverting a ternary here, or
 * going back to an unconditional `ok: true`, leaves every suite green and
 * restores the defect in full.
 *
 * This extracts the real relay from MainActivity.kt, runs it against a stub
 * AndroidBridge, and drives it through the real bundled extension, so the only
 * faked things are Android itself and the vscode API. What is asserted is what
 * the user would be shown.
 *
 * Extraction is deliberately strict. If the raw string moves or changes shape
 * this fails saying so, rather than quietly checking an empty string -- the
 * failure mode that makes a scan of nothing look like a pass.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const Module = require('module');
const vm = require('vm');

const ROOT = path.join(__dirname, '..');
const MAIN_ACTIVITY = path.join(
    ROOT, 'android/app/src/main/kotlin/com/vscodroid/MainActivity.kt',
);
const EXTENSION = path.join(
    ROOT,
    'android/app/src/main/assets/extensions/vscodroid.vscodroid-saf-bridge-1.3.0/extension.js',
);

/**
 * The body of the raw string in `injectBridgeRelay()`, with the indentation
 * `trimIndent()` removes taken off, which is the text the WebView is given.
 */
function extractRelay() {
    const lines = fs.readFileSync(MAIN_ACTIVITY, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('private fun injectBridgeRelay()'));
    assert.notStrictEqual(
        fn, -1,
        'injectBridgeRelay() is gone from MainActivity.kt, so this check has nothing to run. ' +
        'If the relay moved, point this at its new home rather than deleting the check.',
    );

    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close !== -1 && close > open + 1,
        'could not find the raw string in injectBridgeRelay(); its shape changed');

    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');

    // Kotlin would interpolate a bare $ before this ever reached the WebView,
    // and the result is not the script anyone wrote. Cheap to check, silent if not.
    assert.ok(
        !js.includes('$'),
        'the relay contains a $, which Kotlin interpolates into the raw string before the ' +
        'WebView sees it. Escape it as ${\'$\'} or the injected script is not what is written here.',
    );
    assert.ok(js.includes('openExternalUrl'),
        'the extracted text does not mention openExternalUrl, so the wrong block was extracted');
    return js;
}

// ---- the Android side, stubbed -------------------------------------------
let bridgeAnswer = false;
const bridgeCalls = [];
const AndroidBridge = {
    openExternalUrl(url, token) {
        bridgeCalls.push({ url, token });
        return bridgeAnswer;
    },
};

// ---- the vscode API, stubbed ---------------------------------------------
const shown = { info: [], error: [] };
let inputBoxAnswer = null;
const commands = new Map();
const vscodeStub = {
    commands: {
        registerCommand: (id, fn) => { commands.set(id, fn); return { dispose() {} }; },
        executeCommand: async () => {},
    },
    window: {
        showInputBox: async () => inputBoxAnswer,
        showInformationMessage: (m) => { shown.info.push(m); },
        showErrorMessage: (m) => { shown.error.push(m); },
        showWarningMessage: () => {},
        showQuickPick: async () => undefined,
        createStatusBarItem: () => ({ show() {}, hide() {}, dispose() {} }),
    },
    env: { clipboard: { writeText: async () => {} } },
    Uri: { parse: (s) => ({ toString: () => s }) },
    StatusBarAlignment: { Left: 1, Right: 2 },
};

const realLoad = Module._load;
Module._load = function (request) {
    if (request === 'vscode') return vscodeStub;
    return realLoad.apply(this, arguments);
};

/**
 * Every command a bundled extension sends must have a branch in the relay.
 *
 * The severity word is not the only string crossing this boundary: the command
 * names are twelve more, written once in a Kotlin raw string and again in the
 * extensions, with nothing comparing them. A name that matches no branch is not
 * an error anywhere -- the relay's `if/else if` chain simply ends, no reply is
 * posted, and the extension's promise rejects five seconds later with a timeout
 * that says the app might not be running. The cause and the symptom share no
 * words.
 *
 * Only this direction is asserted. Branches with no sender are dead code, not a
 * user-visible failure, and there are some; they are reported rather than
 * refused so removing one stays a deliberate decision.
 */
function checkCommandCoverage(relay) {
    const dispatched = new Set(
        [...relay.matchAll(/d\.cmd === '([A-Za-z0-9_]+)'/g)].map((m) => m[1]),
    );
    const extensionsDir = path.join(ROOT, 'android/app/src/main/assets/extensions');
    const sent = new Set();
    for (const dir of fs.readdirSync(extensionsDir)) {
        const file = path.join(extensionsDir, dir, 'extension.js');
        if (!fs.existsSync(file)) continue;
        for (const m of fs.readFileSync(file, 'utf8').matchAll(/sendBridgeCommand\('([A-Za-z0-9_]+)'/g)) {
            sent.add(m[1]);
        }
    }

    // Both sides report success by finding nothing, so both are asserted to
    // have found something before anything is concluded from the comparison.
    assert.ok(dispatched.size > 0, 'no dispatch branches were read from the relay');
    assert.ok(sent.size > 0, 'no bridge commands were read from any bundled extension');

    const unhandled = [...sent].filter((c) => !dispatched.has(c)).sort();
    assert.deepStrictEqual(
        unhandled, [],
        `these commands are sent by a bundled extension and have no branch in the relay, so ` +
        `they post no reply and fail five seconds later as "Bridge timeout — is the app ` +
        `running on Android?", which names neither the command nor the real cause: ` +
        `${unhandled.join(', ')}`,
    );

    return {
        dispatched: dispatched.size,
        sent: sent.size,
        unused: [...dispatched].filter((c) => !sent.has(c)).sort(),
    };
}

async function main() {
    const relay = extractRelay();
    const coverage = checkCommandCoverage(relay);
    vm.runInNewContext(relay, {
        AndroidBridge,
        BroadcastChannel,
        window: { __vscodroid: { authToken: 'test-token' } },
        console,
    });

    const context = { subscriptions: [] };
    require(EXTENSION).activate(context);

    const openInBrowser = commands.get('vscodroid.openInBrowser');
    assert.ok(openInBrowser, 'the bundled extension no longer registers vscodroid.openInBrowser');

    async function run(url, answer) {
        shown.info.length = 0; shown.error.length = 0; bridgeCalls.length = 0;
        bridgeAnswer = answer;
        inputBoxAnswer = url;
        await openInBrowser();
        await new Promise((r) => setTimeout(r, 60));   // the channel round trip
        return { info: [...shown.info], error: [...shown.error], calls: [...bridgeCalls] };
    }

    // A URL the bridge declines has to reach the user. This is the whole point:
    // the relay used to post ok:true here, which resolved the extension's promise
    // and left its error handler unreachable.
    const refused = await run('http://192.168.1.5:3000', false);
    assert.strictEqual(refused.calls.length, 1, 'the relay never reached the bridge');
    assert.strictEqual(
        refused.error.length, 1,
        'a declined URL must surface exactly one message; got ' + JSON.stringify(refused.error),
    );
    assert.ok(!refused.error[0].includes('undefined'),
        'the surfaced message leaked an undefined: ' + refused.error[0]);

    // The control. Without it, a relay that reported failure unconditionally
    // would satisfy the assertion above and put an error in front of every
    // successful open.
    const opened = await run('http://localhost:3000', true);
    assert.strictEqual(opened.calls.length, 1, 'the relay never reached the bridge');
    assert.strictEqual(
        opened.error.length, 0,
        'a URL the bridge opened must not surface an error; got ' + JSON.stringify(opened.error),
    );

    // Cancelling the input box must not reach Android at all.
    for (const [label, value] of [['cancelled', undefined], ['whitespace', '   ']]) {
        const inert = await run(value, false);
        assert.strictEqual(inert.calls.length, 0, `${label} input still called the bridge`);
        assert.strictEqual(inert.error.length, 0, `${label} input surfaced an error`);
    }

    const unused = coverage.unused.length
        ? `; ${coverage.unused.length} relay branches have no sender (${coverage.unused.join(', ')})`
        : '';
    console.log(
        `ok -- a declined URL surfaces "${refused.error[0]}", an opened one stays silent; ` +
        `all ${coverage.sent} commands sent by an extension have a relay branch${unused}`,
    );
}

// Exit explicitly. The relay opens a BroadcastChannel inside the VM context and
// nothing here holds a reference to unref it, so the event loop stays alive and
// the process hangs forever after the last assertion -- green, and never
// finishing, which in CI is a stall rather than a pass.
main().then(
    () => process.exit(0),
    (e) => { console.error(e.message); process.exit(1); },
);
