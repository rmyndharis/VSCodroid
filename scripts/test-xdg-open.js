/**
 * Self-check for the browser opener the execution trampoline runs.
 *
 * Node's "open a browser" helpers spawn the literal command `xdg-open`, and until
 * this file shipped nothing on the device answered to that name. What it sends is
 * the message VS Code's own remote CLI sends for `--openExternal`, over the socket
 * the extension host publishes as VSCODE_IPC_HOOK_CLI, so the shape below is not
 * ours to choose: a field renamed here is a URL the editor drops on the floor.
 *
 * The refusals matter as much as the send. The handler on the other end skips a
 * `file:` URI silently and still answers 200, so an opener that forwarded one
 * would report success for an address nothing opened.
 *
 *   node scripts/test-xdg-open.js
 */

const assert = require('assert');
const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');

const OPENER = path.resolve(__dirname, '../android/app/src/main/assets/xdg-open.js');

/**
 * Runs the opener, with the socket it should talk to and the arguments given.
 *
 * Asynchronously, and that is not a style choice: the editor these cases stand in
 * for is an HTTP server in THIS process, so a synchronous spawn would block the
 * event loop that has to accept the connection and every case would time out
 * against a server that never answered.
 */
function open(args, { socketPath } = {}) {
    const env = { ...process.env };
    delete env.VSCODE_IPC_HOOK_CLI;
    if (socketPath) env.VSCODE_IPC_HOOK_CLI = socketPath;
    return new Promise((resolve) => {
        const child = spawn(process.execPath, [OPENER, ...args], { env });
        const chunks = [];
        child.stdout.on('data', (c) => chunks.push(c));
        child.stderr.on('data', (c) => chunks.push(c));
        const timer = setTimeout(() => child.kill('SIGKILL'), 15_000);
        child.on('close', (status) => {
            clearTimeout(timer);
            resolve({ status, output: chunks.join('') });
        });
    });
}

/** A stand-in for the editor's CLI server, recording what it was sent. */
function editor(statusCode = 200) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vscodroid-opener-'));
    const socketPath = path.join(dir, 'cli.sock');
    const received = [];
    const server = http.createServer((req, res) => {
        const chunks = [];
        req.on('data', (c) => chunks.push(c));
        req.on('end', () => {
            received.push({ method: req.method, body: chunks.join('') });
            res.writeHead(statusCode, { 'content-type': 'application/json' });
            res.end('null');
        });
    });
    return {
        socketPath,
        received,
        listen: () => new Promise((resolve) => server.listen(socketPath, resolve)),
        close: () => new Promise((resolve) => {
            server.close(() => {
                fs.rmSync(dir, { recursive: true, force: true });
                resolve();
            });
        }),
    };
}

async function sendsTheMessageTheEditorAnswers() {
    const cli = editor();
    await cli.listen();
    try {
        const run = await open(['http://127.0.0.1:5500/index.html'], { socketPath: cli.socketPath });
        assert.strictEqual(run.status, 0, `opening a preview URL should succeed:\n${run.output}`);
        assert.strictEqual(cli.received.length, 1, 'the editor was not asked to open anything');
        assert.strictEqual(cli.received[0].method, 'POST', 'the message did not arrive as a POST');
        assert.deepStrictEqual(
            JSON.parse(cli.received[0].body),
            { type: 'openExternal', uris: ['http://127.0.0.1:5500/index.html'] },
            'the message is not the one the editor handler reads; it switches on `type` and ' +
                'iterates `uris`, so a renamed field is silently dropped',
        );
    } finally {
        await cli.close();
    }
}

async function severalAddressesRideInOneMessage() {
    const cli = editor();
    await cli.listen();
    try {
        const run = await open(['https://a.example', 'https://b.example'], { socketPath: cli.socketPath });
        assert.strictEqual(run.status, 0, `two addresses should still succeed:\n${run.output}`);
        assert.deepStrictEqual(
            JSON.parse(cli.received[0].body).uris,
            ['https://a.example', 'https://b.example'],
            'only one of the addresses given reached the editor',
        );
    } finally {
        await cli.close();
    }
}

async function anEditorThatRefusesIsAFailure() {
    const cli = editor(500);
    await cli.listen();
    try {
        const run = await open(['https://example.com'], { socketPath: cli.socketPath });
        assert.notStrictEqual(run.status, 0, 'a refused open reported success');
        assert.ok(
            /answered 500/.test(run.output),
            `the caller should be told what the editor said:\n${run.output}`,
        );
    } finally {
        await cli.close();
    }
}

async function whatCannotBeOpenedIsRefusedRatherThanSent() {
    const cli = editor();
    await cli.listen();
    try {
        // The handler skips a non-http scheme and answers 200 anyway, so forwarding
        // one would exit 0 for an address that was never opened. Refused here, and
        // before the connection, so the editor is not asked at all.
        for (const arg of ['file:///etc/passwd', '/home/user/index.html', 'vscodroid://callback']) {
            const run = await open([arg], { socketPath: cli.socketPath });
            assert.notStrictEqual(run.status, 0, `${arg} was not refused`);
            assert.ok(
                /only http and https/.test(run.output),
                `${arg} should be refused by name:\n${run.output}`,
            );
        }
        assert.strictEqual(cli.received.length, 0, 'a refused address still reached the editor');
    } finally {
        await cli.close();
    }
}

async function nothingToOpenWithIsNamed() {
    const noArgs = await open([], { socketPath: '/nonexistent.sock' });
    assert.notStrictEqual(noArgs.status, 0, 'an empty argument list reported success');
    assert.ok(/no address given/.test(noArgs.output), `an empty call should say so:\n${noArgs.output}`);

    // Reached outside a session there is no editor to hand it to, and saying so
    // beats a stack trace: this runs where a user is watching a task fail.
    const noSocket = await open(['https://example.com']);
    assert.notStrictEqual(noSocket.status, 0, 'opening with no editor reported success');
    assert.ok(
        /VSCODE_IPC_HOOK_CLI/.test(noSocket.output),
        `a missing editor socket should be named:\n${noSocket.output}`,
    );

    const deadSocket = await open(['https://example.com'], { socketPath: path.join(os.tmpdir(), 'no-such.sock') });
    assert.notStrictEqual(deadSocket.status, 0, 'a socket nothing is listening on reported success');
}

sendsTheMessageTheEditorAnswers()
    .then(severalAddressesRideInOneMessage)
    .then(anEditorThatRefusesIsAFailure)
    .then(whatCannotBeOpenedIsRefusedRatherThanSent)
    .then(nothingToOpenWithIsNamed)
    .then(() => {
        console.log(
            'ok -- the browser opener sends the openExternal message the editor handler reads, ' +
                'carries every address given, fails when the editor refuses, refuses what the ' +
                'handler would have skipped silently, and names a missing editor rather than ' +
                'throwing at one',
        );
    })
    .catch((err) => {
        console.error(err);
        process.exit(1);
    });
