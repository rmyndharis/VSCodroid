'use strict';

/**
 * `xdg-open` for this device: hands a URL to the editor, which hands it to Android.
 *
 * Node's "open a browser" helpers spawn the LITERAL command `xdg-open` on Linux,
 * and Android counts as Linux to every one of them. Nothing on this device's PATH
 * answered to that name: `setupToolSymlinks` puts ten commands in `usr/bin` and
 * none of them opens anything. So a preview server printed its own abort line and
 * the user copied the address into a browser by hand.
 *
 * This is reached by its bare name through the execution trampoline, which is what
 * makes it runnable at all. A file under `filesDir` cannot be `execve`d, so there
 * is no shebang here to be refused: the table names `libnode.so` in
 * `nativeLibraryDir` as the interpreter and this file as its argument.
 *
 * The URL travels over the CLI socket the editor is already listening on. The
 * extension host publishes it as VSCODE_IPC_HOOK_CLI, every process an extension
 * spawns inherits it, and `{"type":"openExternal"}` is the same message VS Code's
 * own remote CLI sends for `--openExternal`. From there the editor calls
 * `env.openExternal`, which reaches the page, the `window.open` override, the
 * Android bridge and a Custom Tab. Nothing new is invented to carry it.
 *
 * `file:` URIs are refused here rather than sent. The handler on the other end
 * skips them silently and answers 200, so forwarding one would report success for
 * an address nothing opened. Opening a path is a different job with a different
 * message, and is deliberately not this one.
 */

const http = require('http');

const socketPath = process.env.VSCODE_IPC_HOOK_CLI;
const args = process.argv.slice(2).filter((arg) => arg !== '');

function fail(message) {
    process.stderr.write(`xdg-open: ${message}\n`);
    process.exit(1);
}

if (args.length === 0) {
    fail('no address given');
}

const unopenable = args.filter((arg) => !/^https?:\/\//i.test(arg));
if (unopenable.length > 0) {
    fail(`only http and https addresses can be opened, not ${unopenable[0]}`);
}

if (!socketPath) {
    fail('no editor to open this with (VSCODE_IPC_HOOK_CLI is unset)');
}

const body = JSON.stringify({ type: 'openExternal', uris: args });

// NO REPLY TIMEOUT, and that is a correction rather than an omission.
//
// One was added here and taken out again the same day. The reasoning was that a
// socket which accepts and then never answers is the one failure not covered by
// an event, so a deadline would turn it into one. What that missed is what the
// reply is waiting FOR. `server-main.js` answers this request with
// `case "openExternal": s = await this.openExternal(i)` and only then `n(200, s)`,
// and `openExternal` awaits `_remoteCLI.openExternal` for every URI. That command
// puts the trusted-domain confirmation on screen for any address outside the
// list in `assets/server.js`, and it does not resolve until the person taps Open.
// A deadline of any length that a user might exceed therefore reports failure for
// a link that is about to open, on the ordinary path rather than a rare one.
//
// The hang it was meant to catch needs a wedged extension host, which is rarer
// and less costly than breaking every confirmed link. If it is ever worth
// guarding, guard the CONNECT, which no human is waiting inside of, and leave the
// reply alone.

const request = http.request(
    {
        socketPath,
        path: '/',
        method: 'POST',
        headers: {
            'content-type': 'application/json',
            'content-length': Buffer.byteLength(body),
        },
    },
    (response) => {
        response.resume();
        response.on('end', () => {
            if (response.statusCode === 200) {
                process.exit(0);
            }
            fail(`the editor answered ${response.statusCode}`);
        });
    },
);
request.on('error', (err) => fail(err.message));
request.end(body);
