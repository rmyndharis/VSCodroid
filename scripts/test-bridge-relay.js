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
const EXTENSIONS_DIR = path.join(ROOT, 'android/app/src/main/assets/extensions');

/** How a literal dollar is written inside a Kotlin raw string. */
const DOLLAR_ESCAPE = "${'$'}";

/**
 * The bundled bridge extension, whatever version it is at.
 *
 * Not a hardcoded directory. Bumping the version of a bundled extension is
 * mandatory here whenever its contents change -- a device only re-extracts a
 * directory whose name it does not already have -- so a path pinned to one
 * version is a path that breaks on the next correct change, and the version in
 * it is stale the moment anyone edits the extension.
 *
 * Ordered by parsed version rather than by name, because 1.10.0 sorts below
 * 1.9.0 as text. check-welcome-claims.py picks its directory the same way.
 */
function bridgeExtension() {
    const version = (name) => {
        const tail = name.slice(name.lastIndexOf('-') + 1);
        const parts = tail.split('.').map(Number);
        return parts.some(Number.isNaN) ? [] : parts;
    };
    const dirs = fs.readdirSync(EXTENSIONS_DIR)
        .filter((d) => d.startsWith('vscodroid.vscodroid-saf-bridge-'))
        .sort((a, b) => {
            const x = version(a), y = version(b);
            for (let i = 0; i < Math.max(x.length, y.length); i += 1) {
                if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) - (y[i] || 0);
            }
            return 0;
        });
    assert.ok(dirs.length, `no vscodroid.vscodroid-saf-bridge-* under ${EXTENSIONS_DIR}`);
    return path.join(EXTENSIONS_DIR, dirs[dirs.length - 1], 'extension.js');
}

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

    // A bare $ is interpolated by Kotlin before the WebView ever sees the string,
    // so the injected script would not be the one written here. The escape for a
    // literal dollar is itself spelled with one, so valid escapes are removed
    // before looking -- checking the raw text would reject exactly the form this
    // message tells the reader to use.
    const withoutEscapes = js.split(DOLLAR_ESCAPE).join('');
    assert.ok(
        !withoutEscapes.includes('$'),
        'the relay contains a bare $, which Kotlin interpolates into the raw string before the ' +
        'WebView sees it. Escape it, or the injected script is not what is written here.',
    );
    assert.ok(js.includes('openExternalUrl'),
        'the extracted text does not mention openExternalUrl, so the wrong block was extracted');

    // Resolve the escapes the way Kotlin does, so what runs below is what runs there.
    return js.split(DOLLAR_ESCAPE).join('$');
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
 *
 * The limit worth knowing, because a reader would assume otherwise: both sides
 * are read as LITERALS. A command name built from a variable is absent from the
 * sent set and therefore unchecked -- silently, and in the one direction this
 * exists for. `sent.size > 0` catches total blindness, not a partial miss. All
 * twelve are literals today, which is what makes the check worth having and also
 * what would make the first non-literal invisible.
 */
function checkCommandCoverage(relay) {
    const dispatched = new Set(
        [...relay.matchAll(/d\.cmd === ['"]([A-Za-z0-9_.-]+)['"]/g)].map((m) => m[1]),
    );
    const extensionsDir = path.join(ROOT, 'android/app/src/main/assets/extensions');
    const sent = new Set();
    for (const dir of fs.readdirSync(extensionsDir)) {
        const file = path.join(extensionsDir, dir, 'extension.js');
        if (!fs.existsSync(file)) continue;
        for (const m of fs.readFileSync(file, 'utf8').matchAll(/sendBridgeCommand\(['"]([A-Za-z0-9_.-]+)['"]/g)) {
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
    require(bridgeExtension()).activate(context);

    const openInBrowser = commands.get('vscodroid.openInBrowser');
    assert.ok(openInBrowser, 'the bundled extension no longer registers vscodroid.openInBrowser');

    async function run(url, answer) {
        shown.info.length = 0; shown.error.length = 0; bridgeCalls.length = 0;
        bridgeAnswer = answer;
        inputBoxAnswer = url;
        // No wait after this. The handler is async and awaits sendBridgeCommand,
        // whose promise settles only from the channel's onmessage or its own 5s
        // timeout, and the catch calls showErrorMessage synchronously -- so by the
        // time this returns, anything the user would see has already been pushed.
        // A sleep here would add nothing except a way for a contended runner to
        // fail two required workflows with a message about the relay.
        await openInBrowser();
        return { info: [...shown.info], error: [...shown.error], calls: [...bridgeCalls] };
    }

    // A URL the bridge declines has to reach the user. This is the whole point:
    // the relay used to post ok:true here, which resolved the extension's promise
    // and left its error handler unreachable.
    const LAN = 'http://192.168.1.5:3000';
    const refused = await run(LAN, false);
    assert.strictEqual(refused.calls.length, 1, 'the relay never reached the bridge');

    // What it passed, not just that it passed something. The relay calls
    // openExternalUrl(d.url, token) and the bridge validates the SECOND argument
    // as the session token; swapping them -- which reads like a tidy-up, since
    // every sibling branch takes the token first -- hands the URL to the token
    // check and refuses every open on device, while a length assertion stays green.
    assert.deepStrictEqual(
        refused.calls[0], { url: LAN, token: 'test-token' },
        'the relay passed the wrong arguments to the bridge: ' + JSON.stringify(refused.calls[0]),
    );
    assert.strictEqual(
        refused.error.length, 1,
        refused.error.length === 0
            ? 'the bridge was called but nothing reached the user. Either the relay stopped ' +
              'answering a declined URL -- the defect this check exists for -- or the await in ' +
              'run() no longer covers the channel round trip, which would be a fault in this ' +
              'check rather than in the relay. Rule the second out before reading the first.'
            : 'a declined URL must surface exactly one message; got ' + JSON.stringify(refused.error),
    );
    assert.ok(!refused.error[0].includes('undefined'),
        'the surfaced message leaked an undefined: ' + refused.error[0]);

    // The relay's own text has to be the text that arrives. Asserting only that
    // SOME message appeared accepts the failure this check exists to catch: a
    // relay that answers nothing leaves the extension to reject on its own
    // five-second timeout, which is also exactly one message, and says the app
    // may not be running.
    const declined = relay.match(/error: '([^']+)'/);
    assert.ok(declined, 'no decline message found in the relay; its shape changed');
    assert.ok(
        refused.error[0].includes(declined[1]),
        'the message the user saw is not the one the relay sends. Saw: ' + refused.error[0],
    );

    // Both conditions have to be named, because the bridge answers with a boolean
    // and cannot say which failed. Naming only the allow-list is wrong for the
    // other one, and reachably so: mailto is ON that list, so a device with no
    // mail app would be told its scheme is refused by a sentence listing that
    // scheme as allowed.
    for (const clause of ['https, mailto', 'accept the link']) {
        assert.ok(
            declined[1].includes(clause),
            'the decline message dropped "' + clause + '", so it now reads as a diagnosis of ' +
            'one cause when the bridge cannot tell the causes apart: ' + declined[1],
        );
    }

    // An allowed scheme that still fails to open. NOT the ActivityNotFound case:
    // the bridge is stubbed here and ignores the URL, so this run is identical to
    // the one above and cannot fail independently of it. What it does pin is that
    // the SAME message is surfaced for a scheme the message itself calls allowed,
    // which is the wording property -- the cause it stands in for lives in Kotlin
    // and is not reachable from this harness.
    const allowedButUnopened = await run('mailto:someone@example.com', false);
    assert.strictEqual(allowedButUnopened.error.length, 1,
        'an allowed URL that failed to open must still reach the user');
    assert.ok(
        allowedButUnopened.error[0].includes(declined[1]),
        'the mailto case surfaced a different message: ' + allowedButUnopened.error[0],
    );

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
    say(
        `ok -- a declined URL surfaces "${refused.error[0]}", an opened one stays silent; ` +
        `all ${coverage.sent} commands sent by an extension have a relay branch${unused}\n`,
    );
}

/**
 * Writes synchronously, because the exit below cannot wait for it otherwise.
 *
 * `process.exit()` is documented to leave pending writes to stdout unflushed,
 * and stdout is only synchronous when it is a TTY. Under CI it is a pipe, so a
 * console.log immediately before the exit is exactly the line that goes
 * missing -- and a check that exits 0 having printed nothing reads as a check
 * that did not run.
 */
function say(text) {
    fs.writeSync(1, text);
}

// Exit explicitly. The relay opens a BroadcastChannel inside the VM context and
// nothing here holds a reference to unref it, so the event loop stays alive and
// the process hangs forever after the last assertion -- green, and never
// finishing, which in CI is a stall rather than a pass.
main().then(
    () => process.exit(0),
    (e) => { fs.writeSync(2, `${e.message}\n`); process.exit(1); },
);
