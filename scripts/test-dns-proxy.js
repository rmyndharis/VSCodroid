/**
 * Self-check for the loopback DNS proxy: what it lets through, and the bounds it
 * puts on a leg once it has.
 *
 * Runs anywhere Node runs -- dns-proxy.js is plain Node with no Android
 * dependency -- so the auth logic is provable before anything is pushed to a
 * device. Device testing still matters, but for the other half of the claim:
 * that real clients (the Claude Code CLI, Copilot) read the credentials out of
 * the proxy URL and send them. This file proves the proxy's side of that
 * contract.
 *
 *   node scripts/test-dns-proxy.js
 */

const assert = require('assert');
const fs = require('fs');
const http = require('http');
const net = require('net');
const os = require('os');
const path = require('path');
const { URL } = require('url');

const PROXY = require.resolve('../android/app/src/main/assets/dns-proxy.js');
const { start } = require(PROXY);

const logLines = [];
const log = (level, message) => logLines.push(`${level}: ${message}`);

/** Listens and resolves with the bound port. */
function listen(server) {
    return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));
}

/**
 * An origin that reports which headers reached it, and on /big streams a body
 * far larger than any socket buffer so a client can abort mid-response.
 */
function originServer() {
    return http.createServer((req, res) => {
        if (req.url === '/big') {
            res.writeHead(200, { 'content-type': 'application/octet-stream' });
            const chunk = Buffer.alloc(1 << 16, 0x61);
            let sent = 0;
            const pump = () => {
                while (sent < 64) {
                    sent++;
                    if (!res.write(chunk)) {
                        res.once('drain', pump);
                        return;
                    }
                }
                res.end();
            };
            res.on('error', () => {});
            pump();
            return;
        }
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify(req.headers));
    });
}

/** Speaks the proxy protocol over a raw socket and returns everything it said. */
function rawExchange(port, request) {
    return new Promise((resolve, reject) => {
        const socket = net.connect(port, '127.0.0.1', () => socket.write(request));
        let received = '';
        socket.on('data', (chunk) => {
            received += chunk;
            // A tunnel stays open after its 200; the response head is all we
            // need, so stop as soon as the headers are terminated.
            if (received.includes('\r\n\r\n')) {
                socket.destroy();
                resolve(received);
            }
        });
        socket.on('error', reject);
        socket.on('close', () => resolve(received));
    });
}

/**
 * Like rawExchange, but lets the server close the connection instead of
 * destroying it at the first CRLFCRLF. That distinction is the whole point of
 * the Connection: close test below -- reading the header text only proves the
 * proxy claimed it would close, not that it did.
 */
function rawExchangeUntilClose(port, request) {
    return new Promise((resolve, reject) => {
        const socket = net.connect(port, '127.0.0.1', () => socket.write(request));
        let received = '';
        let sawFin = false;
        socket.on('data', (chunk) => (received += chunk));
        socket.on('end', () => (sawFin = true));
        socket.on('error', reject);
        socket.on('close', () => resolve({ received, sawFin }));
        // A tunnel that stays open would hang this forever; fail loudly instead.
        socket.setTimeout(3000, () => {
            socket.destroy();
            resolve({ received, sawFin });
        });
    });
}

/**
 * The shipped proxy, loaded from a copy with the named top-level constants
 * rewritten to the given values.
 *
 * Every bound in dns-proxy.js is sized for a phone on a mobile network, so
 * exercising one against its shipped value costs that value in wall clock: half
 * a minute for the upstream timeout, ten seconds for the header sweep to give a
 * flooded cap back. A copy with the literals rewritten runs the same lines in a
 * couple of seconds. The same trick scripts/test-process-monitor.js uses to move
 * the monitor's tree: copy the shipped file, change the one thing under
 * examination, require the copy.
 *
 * Each substitution is asserted rather than assumed. A name that stopped
 * matching would leave the cases below running against the shipped value, where
 * they would either take that long to go green or pass without ever reaching the
 * bound they exist to hold.
 */
function proxyWithConstants(overrides) {
    let patched = fs.readFileSync(PROXY, 'utf8');
    for (const [name, value] of Object.entries(overrides)) {
        const before = patched;
        patched = patched.replace(
            new RegExp(`^const ${name} = [\\d_]+;$`, 'm'),
            `const ${name} = ${value};`,
        );
        assert.notStrictEqual(
            patched, before,
            `${name} is no longer a top-level literal in dns-proxy.js, so the cases below would ` +
                'run against whatever it is now and prove nothing about the bound it names',
        );
    }
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dns-proxy-'));
    const copy = path.join(dir, 'dns-proxy.js');
    fs.writeFileSync(copy, patched);
    const loaded = require(copy);
    // Gone the moment it has been evaluated: the module reads nothing from disk
    // after load, and a temp directory per run adds up on a workstation.
    fs.rmSync(dir, { recursive: true, force: true });
    return loaded;
}

/** Issues a proxied GET with the Proxy-Authorization header set verbatim. */
function proxiedGetRaw(proxyPort, targetUrl, headerValue) {
    return new Promise((resolve, reject) => {
        const headers = headerValue === '' ? {} : { 'proxy-authorization': headerValue };
        const req = http.request({ host: '127.0.0.1', port: proxyPort, path: targetUrl, headers, agent: false }, (res) => {
            res.resume();
            res.on('end', () => resolve({ status: res.statusCode }));
        });
        req.on('error', reject);
        req.end();
    });
}

/** Issues a proxied plain-HTTP GET, optionally authenticated. */
function proxiedGet(proxyPort, targetUrl, credentials, extraHeaders = {}) {
    return new Promise((resolve, reject) => {
        const headers = { ...extraHeaders };
        if (credentials) {
            headers['proxy-authorization'] = `Basic ${Buffer.from(credentials).toString('base64')}`;
        }
        const req = http.request({ host: '127.0.0.1', port: proxyPort, path: targetUrl, headers, agent: false }, (res) => {
            let body = '';
            res.on('data', (chunk) => (body += chunk));
            res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }));
        });
        req.on('error', reject);
        req.end();
    });
}

async function main() {
    const env = await start(log);
    assert.ok(env.HTTPS_PROXY, 'proxy failed to bind; nothing else here is meaningful');

    const proxy = new URL(env.HTTPS_PROXY);
    const proxyPort = Number(proxy.port);
    const goodCredentials = `${proxy.username}:${decodeURIComponent(proxy.password)}`;

    const origin = originServer();
    const originPort = await listen(origin);
    // The echo sockets need their own error handler: these tests deliberately
    // RST the client, and a bare socket.pipe(socket) would die of the
    // ECONNRESET that arrives here -- a crash in the harness, mistakable for a
    // crash in the proxy.
    // Tracked so teardown can close them: the abort tests deliberately leave the
    // proxy's upstream leg open, and server.close() waits on live connections,
    // which would hang this script after every assertion had already passed.
    const echoSockets = new Set();
    const echoHandler = (socket) => {
        echoSockets.add(socket);
        socket.on('close', () => echoSockets.delete(socket));
        socket.on('error', () => {});
        socket.pipe(socket);
    };
    const echo = net.createServer(echoHandler);
    const echoPort = await listen(echo);

    // The four env vars must agree; a client that reads http_proxy and one that
    // reads HTTPS_PROXY have to end up at the same credentials.
    for (const key of ['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy']) {
        assert.strictEqual(env[key], env.HTTPS_PROXY, `${key} disagrees with HTTPS_PROXY`);
    }
    assert.ok(proxy.username && proxy.password, 'proxy URL carries no credentials');

    // --- plain HTTP -------------------------------------------------------
    const anonymous = await proxiedGet(proxyPort, `http://127.0.0.1:${originPort}/`);
    assert.strictEqual(anonymous.status, 407, 'unauthenticated HTTP was not rejected');
    assert.match(anonymous.headers['proxy-authenticate'] || '', /^Basic /, 'no Basic challenge on 407');

    const wrongToken = await proxiedGet(proxyPort, `http://127.0.0.1:${originPort}/`, 'vscodroid:not-the-token');
    assert.strictEqual(wrongToken.status, 407, 'a wrong token was accepted');

    const authenticated = await proxiedGet(proxyPort, `http://127.0.0.1:${originPort}/`, goodCredentials);
    assert.strictEqual(authenticated.status, 200, 'a correct token was rejected');

    // RFC 7235 makes the scheme case-insensitive. A client that sends "basic"
    // is conforming, and the one binary here that cannot be inspected -- the
    // Claude Code CLI -- is exactly where that would surface as a mystery 407.
    const lowercased = await rawExchange(
        proxyPort,
        `GET http://127.0.0.1:${originPort}/ HTTP/1.1\r\nHost: 127.0.0.1:${originPort}\r\n` +
            `proxy-authorization: basic ${Buffer.from(goodCredentials).toString('base64')}\r\n\r\n`,
    );
    assert.match(lowercased, /^HTTP\/1\.1 200 /, `a lowercase "basic" scheme was rejected: ${lowercased}`);

    // Node keeps the first proxy-authorization header and discards the rest, so
    // a junk header cannot be used to shadow a valid one appended after it.
    const duplicated = await rawExchange(
        proxyPort,
        `GET http://127.0.0.1:${originPort}/ HTTP/1.1\r\nHost: 127.0.0.1:${originPort}\r\n` +
            `Proxy-Authorization: Basic ${Buffer.from('vscodroid:wrong').toString('base64')}\r\n` +
            `Proxy-Authorization: Basic ${Buffer.from(goodCredentials).toString('base64')}\r\n\r\n`,
    );
    assert.match(duplicated, /^HTTP\/1\.1 407 /, `a duplicated header slipped a bad credential through: ${duplicated}`);

    // A header that is not Basic at all must not reach timingSafeEqual.
    for (const malformed of ['', 'Basic', 'Bearer abc', 'Basic !!!!', 'Basic']) {
        const rejected = await proxiedGetRaw(proxyPort, `http://127.0.0.1:${originPort}/`, malformed);
        assert.strictEqual(rejected.status, 407, `malformed credential "${malformed}" was not rejected`);
    }

    // The credential authenticates the client to the proxy and must not travel
    // any further; forwarding it would hand the device's token to every origin.
    const seenByOrigin = JSON.parse(authenticated.body);
    assert.ok(
        !('proxy-authorization' in seenByOrigin),
        'the proxy leaked Proxy-Authorization upstream: ' + JSON.stringify(seenByOrigin),
    );

    // Proxy-Connection stops here too, by the same argument. It is the
    // non-standard hop-by-hop cousin of Connection that older clients still send,
    // and it was forwarded to the origin while the reasoning beside the line that
    // dropped the credential explained why such a header must not be. Sent
    // explicitly rather than hoped for: no client in the suite sends it by
    // itself, so without this the header never reaches the proxy at all and the
    // assertion would hold over nothing.
    const hopByHop = await proxiedGet(
        proxyPort, `http://127.0.0.1:${originPort}/`, goodCredentials,
        { 'proxy-connection': 'Keep-Alive' },
    );
    assert.strictEqual(hopByHop.status, 200, 'a request carrying Proxy-Connection was rejected');
    const hopSeen = JSON.parse(hopByHop.body);
    assert.ok(
        !('proxy-connection' in hopSeen),
        'the proxy forwarded Proxy-Connection upstream: ' + JSON.stringify(hopSeen),
    );

    // --- CONNECT ----------------------------------------------------------
    const connectHead = `CONNECT 127.0.0.1:${echoPort} HTTP/1.1\r\nHost: 127.0.0.1:${echoPort}\r\n`;

    const anonymousTunnel = await rawExchange(proxyPort, `${connectHead}\r\n`);
    assert.match(anonymousTunnel, /^HTTP\/1\.1 407 /, `unauthenticated CONNECT was not rejected: ${anonymousTunnel}`);
    assert.match(anonymousTunnel, /Proxy-Authenticate: Basic /, 'no Basic challenge on the CONNECT 407');

    // git does not send Basic preemptively: http.proxyAuthMethod defaults to
    // anyauth, so it expects the 407 and retries the CONNECT. Because the
    // challenge is written with end(), the socket is already closing, and
    // without this header the client retries onto a dead socket and reports
    // "Proxy CONNECT aborted" -- an error that names neither the proxy nor the
    // token. Every client that authenticates preemptively passes the tests
    // above whether or not this header is present, so this assertion is the
    // only thing standing between git-over-HTTPS and a silent regression.
    assert.match(
        anonymousTunnel,
        /Connection: close/i,
        `the CONNECT 407 omits "Connection: close"; challenge-response clients such as git will abort: ${anonymousTunnel}`,
    );

    const badTunnel = await rawExchange(
        proxyPort,
        `${connectHead}Proxy-Authorization: Basic ${Buffer.from('vscodroid:wrong').toString('base64')}\r\n\r\n`,
    );
    assert.match(badTunnel, /^HTTP\/1\.1 407 /, 'a wrong token opened a tunnel');

    const goodTunnel = await rawExchange(
        proxyPort,
        `${connectHead}Proxy-Authorization: Basic ${Buffer.from(goodCredentials).toString('base64')}\r\n\r\n`,
    );
    assert.match(goodTunnel, /^HTTP\/1\.1 200 /, `a correct token was refused a tunnel: ${goodTunnel}`);

    // The header text above only proves the proxy said it would close. This
    // proves it did: a challenge-response client retries on this connection, and
    // if the FIN never arrives it waits on a socket that is already gone.
    const closed = await rawExchangeUntilClose(proxyPort, `${connectHead}\r\n`);
    assert.match(closed.received, /^HTTP\/1\.1 407 /, 'unauthenticated CONNECT was not rejected');
    assert.ok(closed.sawFin, 'the proxy announced Connection: close but never closed the connection');

    // --- IPv6 literals ----------------------------------------------------
    // Both legs, because they had different bugs. The parser reports an IPv6
    // hostname with its brackets, and every socket call here wants it without:
    // the plain-HTTP leg passed the bracketed form to DNS and came back 502
    // with ENOTFOUND naming an address, while the CONNECT leg had already
    // learned to strip. Skipped where the loopback has no IPv6.
    const origin6 = originServer();
    const origin6Port = await new Promise((resolve) => {
        origin6.once('error', () => resolve(null));
        origin6.listen(0, '::1', () => resolve(origin6.address().port));
    });
    if (origin6Port) {
        const viaLiteral = await proxiedGet(proxyPort, `http://[::1]:${origin6Port}/`, goodCredentials);
        assert.strictEqual(viaLiteral.status, 200, 'a plain-HTTP request to an IPv6 literal was not forwarded');
        origin6.close();
    } else {
        console.log('note -- no IPv6 loopback here, skipped the plain-HTTP IPv6 case');
    }

    // "[::1]:443".split(':') yields ["[", "", "1]", "443"], so the pre-parser
    // form dialled a host named "[". Skipped where the loopback has no IPv6.
    const echo6 = net.createServer(echoHandler);
    const echo6Port = await new Promise((resolve) => {
        echo6.once('error', () => resolve(null));
        echo6.listen(0, '::1', () => resolve(echo6.address().port));
    });
    if (echo6Port) {
        const tunnel6 = await rawExchange(
            proxyPort,
            `CONNECT [::1]:${echo6Port} HTTP/1.1\r\nHost: [::1]:${echo6Port}\r\n` +
                `Proxy-Authorization: Basic ${Buffer.from(goodCredentials).toString('base64')}\r\n\r\n`,
        );
        assert.match(tunnel6, /^HTTP\/1\.1 200 /, `an IPv6 CONNECT target was refused: ${tunnel6}`);
        echo6.close();
    } else {
        console.log('note -- no IPv6 loopback here, skipped the IPv6 CONNECT case');
    }

    // A URL parser normalises away a port that matches the scheme default, so
    // reading the port back from it turns "host:80" into an empty string and
    // then, via `|| 443`, into a dial to the wrong port entirely. The port must
    // come from the raw target text. Only 80 can trigger this, so the check
    // needs port 80 to be closed here to be meaningful.
    const port80Closed = await new Promise((resolve) => {
        const probe = net.connect(80, '127.0.0.1');
        probe.on('connect', () => {
            probe.destroy();
            resolve(false);
        });
        probe.on('error', () => resolve(true));
    });
    if (port80Closed) {
        const before = logLines.length;
        await rawExchange(
            proxyPort,
            `CONNECT 127.0.0.1:80 HTTP/1.1\r\nHost: 127.0.0.1:80\r\n` +
                `Proxy-Authorization: Basic ${Buffer.from(goodCredentials).toString('base64')}\r\n\r\n`,
        );
        const logged = logLines.slice(before).join('\n');
        assert.match(
            logged,
            /CONNECT 127\.0\.0\.1:80 failed/,
            `a CONNECT to port 80 was not dialled on port 80: ${logged || '(nothing logged)'}`,
        );
    } else {
        console.log('note -- something is listening on port 80 here, skipped the default-port case');
    }

    // A port outside the valid range used to reach net.connect and throw
    // ERR_SOCKET_BAD_PORT synchronously, taking down whichever process holds the
    // listener. That is now the editor server, not the bootstrap that forks it.
    const badPort = await rawExchange(
        proxyPort,
        `CONNECT example.com:99999999 HTTP/1.1\r\nHost: example.com\r\n` +
            `Proxy-Authorization: Basic ${Buffer.from(goodCredentials).toString('base64')}\r\n\r\n`,
    );
    assert.match(badPort, /^HTTP\/1\.1 400 /, `a malformed CONNECT target was not rejected: ${badPort}`);

    // --- an origin that dies after the tunnel is up -----------------------
    // The upstream error handler is registered once and lives for the whole
    // tunnel, so a failure AFTER the 200 used to be answered with a 502 written
    // into the tunnelled byte stream. For a TLS session those 47 bytes are read
    // as a record with content type 0x48 ('H'), so the client reports a
    // protocol failure rather than the reset that actually happened, turning
    // a retryable error into one that is not.
    //
    // A clean close does not reproduce it: the proxy sees 'end', not 'error'.
    // It takes an RST, which is the routine case on a mobile network.
    const rude = net.createServer((socket) => {
        socket.on('error', () => {});
        socket.write('HELLO');
        setTimeout(() => socket.resetAndDestroy(), 50);
    });
    const rudePort = await listen(rude);
    const beforeRude = logLines.length;
    const afterEstablished = await new Promise((resolve) => {
        const socket = net.connect(proxyPort, '127.0.0.1', () => {
            socket.write(
                `CONNECT 127.0.0.1:${rudePort} HTTP/1.1\r\nHost: 127.0.0.1:${rudePort}\r\n` +
                    `Proxy-Authorization: Basic ${Buffer.from(goodCredentials).toString('base64')}\r\n\r\n`,
            );
        });
        let received = '';
        socket.on('data', (chunk) => (received += chunk));
        socket.on('error', () => resolve(received));
        socket.on('close', () => resolve(received));
        socket.setTimeout(3000, () => {
            socket.destroy();
            resolve(received);
        });
    });
    assert.match(afterEstablished, /^HTTP\/1\.1 200 /, `the tunnel was never established: ${afterEstablished}`);
    assert.ok(
        afterEstablished.includes('HELLO'),
        `the origin's bytes never reached the client, so this proves nothing: ${afterEstablished}`,
    );
    assert.ok(
        !/HTTP\/1\.1 5\d\d/.test(afterEstablished.slice('HTTP/1.1 200 Connection Established\r\n\r\n'.length)),
        `a status line was written into an established tunnel: ${JSON.stringify(afterEstablished)}`,
    );
    // The failure still has to be diagnosable; silencing it would satisfy the
    // assertion above just as well.
    assert.match(
        logLines.slice(beforeRude).join('\n'),
        /CONNECT 127\.0\.0\.1:\d+ tunnel broke/,
        `a tunnel that broke mid-session said nothing: ${logLines.slice(beforeRude).join('\n') || '(nothing logged)'}`,
    );
    rude.close();

    // --- a client that walks away mid-flight ------------------------------
    // The error listeners in both handlers exist so an aborted client cannot
    // raise an unhandled 'error' and take the editor server down with it: the
    // listener is preloaded into that process, not into the bootstrap.
    //
    // Aborting has to be done carefully to exercise them at all. A destroy()
    // issued after the write has drained closes the connection cleanly, and a
    // clean close reaches the proxy as 'end', never 'error' -- an earlier
    // version of this test did exactly that and passed with every listener
    // deleted. Leaving unread data in the receive buffer at close turns the
    // FIN into an RST, which is what the proxy sees as an error on a socket it
    // is still writing to.
    const abortMidTunnel = () =>
        new Promise((resolve) => {
            const socket = net.connect(proxyPort, '127.0.0.1', () => {
                socket.write(
                    `${connectHead}Proxy-Authorization: Basic ${Buffer.from(goodCredentials).toString('base64')}\r\n\r\n`,
                );
            });
            let armed = false;
            socket.on('data', () => {
                if (armed) return;
                armed = true;
                // Flood the echo server, then vanish without reading a byte of
                // what comes back.
                socket.write('x'.repeat(1 << 20));
                setImmediate(() => {
                    socket.resume = () => {};
                    socket.destroy();
                    resolve();
                });
            });
            socket.on('error', resolve);
            socket.setTimeout(3000, () => {
                socket.destroy();
                resolve();
            });
        });

    const abortMidResponse = () =>
        new Promise((resolve) => {
            const socket = net.connect(proxyPort, '127.0.0.1', () => {
                socket.write(
                    `GET http://127.0.0.1:${originPort}/big HTTP/1.1\r\nHost: x\r\n` +
                        `Proxy-Authorization: Basic ${Buffer.from(goodCredentials).toString('base64')}\r\n\r\n`,
                );
            });
            socket.on('data', () => socket.destroy());
            socket.on('error', resolve);
            socket.on('close', resolve);
            socket.setTimeout(3000, () => {
                socket.destroy();
                resolve();
            });
        });

    await abortMidTunnel();
    await abortMidResponse();

    // If either abort had killed the proxy, this would not answer.
    const stillAlive = await proxiedGet(proxyPort, `http://127.0.0.1:${originPort}/`, goodCredentials);
    assert.strictEqual(stillAlive.status, 200, 'the proxy died after a client aborted mid-transfer');

    // --- a rejected CONNECT must not pin a descriptor ---------------------
    // The response text is identical whether or not the socket is released, so
    // this is the one case in the file that has to be asserted by counting.
    // http.Server builds its sockets with allowHalfOpen, so end() sends our FIN
    // and waits for a peer FIN that a client with no interest in replying never
    // sends; and no HTTP timeout governs a socket once 'connect' has fired. A
    // co-installed app could otherwise hold descriptors in this process for
    // free, without a token and without a single line in the log.
    //
    // _getActiveHandles is private, and used deliberately: both ends live in
    // this process, so it is the only vantage point that sees the server side
    // at all.
    const liveSockets = () =>
        process._getActiveHandles().filter((h) => h.constructor && h.constructor.name === 'Socket' && !h.destroyed)
            .length;

    const PROBES = 40;
    const baseline = liveSockets();
    const probes = [];
    for (let i = 0; i < PROBES; i++) {
        await new Promise((resolve) => {
            const socket = net.connect(proxyPort, '127.0.0.1', () => {
                socket.write(`${connectHead}\r\n`);
                probes.push(socket);
                resolve();
            });
            socket.on('error', resolve);
        });
    }
    await new Promise((resolve) => setTimeout(resolve, 500));

    // Every probe still holds its own client socket on purpose -- a peer that
    // hangs up is exactly the case that already worked. What must not survive
    // is the server's half.
    const held = liveSockets() - baseline - probes.length;
    assert.ok(
        held <= 0,
        `${held} of ${PROBES} rejected CONNECTs left a server-side socket pinned; ` +
            'end() without a release leaves them half-open and nothing reaps them',
    );
    probes.forEach((socket) => socket.destroy());

    // --- ws:// through the proxy -------------------------------------------
    // An upgrade request reaches the server on its own event, and Node closes
    // the connection outright when nothing is listening for it: a client dialling
    // ws:// was dropped with no status at all, while wss:// worked because it
    // goes through CONNECT. HTTP_PROXY reaches the whole editor server and every
    // terminal, so that was ordinary tools rather than only the CLI this proxy
    // exists for.
    const wsOrigin = http.createServer((req, res) => {
        res.writeHead(426).end();
    });
    // Recorded so the hop-by-hop rule can be asked of this leg too: it builds the
    // forwarded request head by hand, out of rawHeaders, rather than handing an
    // object to http.request, so the two legs strip headers in two different
    // places and drifted apart once already.
    let wsSeen = null;
    wsOrigin.on('upgrade', (req, socket) => {
        wsSeen = req.headers;
        socket.write(
            'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n',
        );
        socket.on('error', () => {});
        socket.pipe(socket);
    });
    const wsPort = await listen(wsOrigin);
    const upgraded = await new Promise((resolve) => {
        const socket = net.connect(proxyPort, '127.0.0.1', () => {
            socket.write(
                `GET http://127.0.0.1:${wsPort}/socket HTTP/1.1\r\nHost: 127.0.0.1:${wsPort}\r\n` +
                    `Upgrade: websocket\r\nConnection: Upgrade\r\n` +
                    `Proxy-Connection: Keep-Alive\r\n` +
                    `Proxy-Authorization: Basic ${Buffer.from(goodCredentials).toString('base64')}\r\n\r\n`,
            );
        });
        let received = '';
        socket.on('data', (chunk) => {
            received += chunk;
            // The 101 first, then a byte of our own back through the tunnel, so
            // this cannot pass on a handshake that is relayed and then spliced to
            // nothing.
            if (received.includes('\r\n\r\n') && !received.endsWith('\r\n\r\n')) {
                socket.destroy();
                resolve(received);
            } else if (received.includes('\r\n\r\n')) {
                socket.write('ping');
            }
        });
        socket.on('error', () => resolve(received));
        socket.on('close', () => resolve(received));
        socket.setTimeout(3000, () => {
            socket.destroy();
            resolve(received);
        });
    });
    assert.match(
        upgraded,
        /^HTTP\/1\.1 101 /,
        `a ws:// upgrade through the proxy was not relayed: ${JSON.stringify(upgraded)}`,
    );
    assert.ok(
        upgraded.includes('ping'),
        `the upgrade was answered but the sockets were never spliced: ${JSON.stringify(upgraded)}`,
    );
    assert.ok(wsSeen, 'the upgrade never reached the origin, so its headers were never compared');
    for (const header of ['proxy-authorization', 'proxy-connection']) {
        assert.ok(
            !(header in wsSeen),
            `the upgrade leg forwarded ${header} to the origin: ${JSON.stringify(wsSeen)}`,
        );
    }

    // And the credential still stops at the proxy on this leg too.
    const anonymousUpgrade = await rawExchange(
        proxyPort,
        `GET http://127.0.0.1:${wsPort}/socket HTTP/1.1\r\nHost: 127.0.0.1:${wsPort}\r\n` +
            `Upgrade: websocket\r\nConnection: Upgrade\r\n\r\n`,
    );
    assert.match(
        anonymousUpgrade,
        /^HTTP\/1\.1 407 /,
        `an unauthenticated ws:// upgrade was tunnelled: ${anonymousUpgrade}`,
    );
    wsOrigin.close();

    // --- a flood of unauthenticated sockets is bounded --------------------
    // The token defends what the proxy will do and nothing about the socket
    // itself, and every app on the device can reach this port. Each half-open
    // connection is a descriptor in the process that also serves the workbench,
    // held until Node's header sweep collects it; what the proxy bounds, and
    // what this measures, is how many of them one peer can hold at once.
    const FLOOD = 160;
    const flood = [];
    let closedByServer = 0;
    for (let i = 0; i < FLOOD; i++) {
        flood.push(
            await new Promise((resolve) => {
                const socket = net.connect(proxyPort, '127.0.0.1', () => resolve(socket));
                socket.on('close', () => { closedByServer++; });
                socket.on('error', () => resolve(socket));
            }),
        );
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
    assert.ok(
        closedByServer > 0,
        `${FLOOD} sockets that sent nothing were all accepted and held; an unauthenticated peer ` +
            'can take as many descriptors as it likes in the process that serves the editor',
    );
    flood.forEach((socket) => socket.destroy());

    // --- the setup timer, and the release that has to follow it -----------
    // An origin that accepts a connection and then says nothing is routine on a
    // mobile network. Untimed, its leg pinned a descriptor at each end until TCP
    // keepalive gave up, in the process that also serves the workbench. Timed
    // but never released, a response that streams with quiet stretches -- a
    // large clone over http, a slow gallery page -- is cut in the middle by a
    // timer meant for one that never started. The two are one constant and one
    // line apart and neither had a case here.
    const SHORT_MS = 500;
    const GAP_MS = 3 * SHORT_MS;
    // The header sweep, shortened on the same copy. Longer than SHORT_MS so the
    // flood below can be built and probed inside one sweep interval; a reclaim
    // it observes therefore has to be the sweep and cannot be a race.
    const SWEEP_MS = 1500;
    const shortProxy = proxyWithConstants({
        UPSTREAM_TIMEOUT_MS: SHORT_MS,
        HEADER_PHASE_MS: SWEEP_MS,
    });
    const shortEnv = await shortProxy.start(log);
    assert.ok(shortEnv.HTTPS_PROXY, 'the copy with the shortened timeout never bound');
    const shortProxyUrl = new URL(shortEnv.HTTPS_PROXY);
    const shortPort = Number(shortProxyUrl.port);
    const shortCredentials =
        `${shortProxyUrl.username}:${decodeURIComponent(shortProxyUrl.password)}`;

    // Accepts and then says nothing at all. Its sockets join the teardown set,
    // because the proxy is expected to abandon this leg with the far half still
    // open and server.close() waits on a live connection.
    const mute = net.createServer((socket) => {
        echoSockets.add(socket);
        socket.on('close', () => echoSockets.delete(socket));
        socket.on('error', () => {});
    });
    const mutePort = await listen(mute);
    const beforeMute = logLines.length;
    const unanswered = await Promise.race([
        proxiedGet(shortPort, `http://127.0.0.1:${mutePort}/`, shortCredentials),
        // A leg with no timer on it never answers at all, and an assertion that
        // waits on that forever is a hang for whoever runs this rather than a
        // failure with a name. Unref'd, so it cannot hold the process open once
        // the real answer wins the race.
        new Promise((resolve) => {
            setTimeout(
                () => resolve({ status: `nothing at all within ${12 * SHORT_MS} ms` }),
                12 * SHORT_MS,
            ).unref();
        }),
    ]);
    assert.strictEqual(
        unanswered.status, 502,
        `an origin that accepted and then said nothing was answered ${unanswered.status}; ` +
            'without the setup timer this leg is held until TCP keepalive gives up',
    );
    // Which timer produced it: a 502 is also what a refused dial gives, and this
    // origin accepted. The message names the bound, so nothing else can satisfy
    // this.
    assert.match(
        logLines.slice(beforeMute).join('\n'),
        new RegExp(`no response within ${SHORT_MS} ms`),
        `the 502 did not come from the upstream timeout: ` +
            `${logLines.slice(beforeMute).join('\n') || '(nothing logged)'}`,
    );

    // The same mute origin, dialled as a ws:// upgrade. The upgrade leg is the
    // one that has to wait for the origin's 101 before it has promised the
    // client anything, so its bound has to survive TCP connect; CONNECT's does
    // not, because CONNECT answers 200 at connect. Releasing it at connect on
    // this leg left the socket open with no status and nothing logged, holding
    // a descriptor at each end and one of the maxConnections slots for the life
    // of the process.
    const beforeMuteUpgrade = logLines.length;
    const muteUpgrade = await Promise.race([
        rawExchange(
            shortPort,
            `GET http://127.0.0.1:${mutePort}/socket HTTP/1.1\r\nHost: 127.0.0.1:${mutePort}\r\n` +
                `Upgrade: websocket\r\nConnection: Upgrade\r\n` +
                `Proxy-Authorization: Basic ${Buffer.from(shortCredentials).toString('base64')}\r\n\r\n`,
        ),
        new Promise((resolve) => {
            setTimeout(() => resolve(`nothing at all within ${12 * SHORT_MS} ms`), 12 * SHORT_MS).unref();
        }),
    ]);
    assert.match(
        muteUpgrade,
        /^HTTP\/1\.1 502 /,
        `a ws:// upgrade to an origin that accepted and then said nothing got ${JSON.stringify(muteUpgrade)}; ` +
            'the setup bound is released at TCP connect on this leg, so nothing ever fails it',
    );
    assert.match(
        logLines.slice(beforeMuteUpgrade).join('\n'),
        new RegExp(`no answer within ${SHORT_MS} ms`),
        `the upgrade 502 did not come from the upstream timeout: ` +
            `${logLines.slice(beforeMuteUpgrade).join('\n') || '(nothing logged)'}`,
    );

    // And the release. The gap is longer than the timeout, so a timer left
    // running kills this leg in the middle of a body the origin is still
    // writing; the client then sees the first half and a clean end, which is
    // indistinguishable from a short response.
    const gapped = http.createServer((req, res) => {
        res.writeHead(200, { 'content-type': 'text/plain' });
        res.write('first');
        setTimeout(() => res.end(' last'), GAP_MS);
    });
    const gappedPort = await listen(gapped);
    const streamed = await proxiedGet(shortPort, `http://127.0.0.1:${gappedPort}/`, shortCredentials);
    assert.strictEqual(streamed.status, 200, `a slow-streaming origin was answered ${streamed.status}`);
    assert.strictEqual(
        streamed.body, 'first last',
        `a body with a ${GAP_MS} ms silence in it arrived as ${JSON.stringify(streamed.body)}: ` +
            'the setup timer is still running once the response has started, so any stream ' +
            'quieter than the timeout is truncated with no error anywhere',
    );

    // The same release on the tunnel leg, which is the one every https request
    // through this proxy takes. A tunnel is expected to sit idle for minutes at
    // a time -- an interactive CLI session is mostly silence -- so a dial timer
    // left running there tears down the connection this file exists for, in the
    // middle of using it.
    const idleTunnel = await new Promise((resolve) => {
        const socket = net.connect(shortPort, '127.0.0.1', () => {
            socket.write(
                `CONNECT 127.0.0.1:${echoPort} HTTP/1.1\r\nHost: 127.0.0.1:${echoPort}\r\n` +
                    `Proxy-Authorization: Basic ${Buffer.from(shortCredentials).toString('base64')}\r\n\r\n`,
            );
        });
        let received = '';
        socket.on('data', (chunk) => {
            received += chunk;
            // The 200 first. Then nothing from either half for longer than the
            // bound -- the echo server speaks only when spoken to, so the gap is
            // real silence -- and only then a byte, which has to come back.
            if (received.endsWith('\r\n\r\n')) {
                setTimeout(() => socket.write('ping'), GAP_MS);
                return;
            }
            socket.destroy();
            resolve(received);
        });
        socket.on('error', () => resolve(received));
        socket.on('close', () => resolve(received));
        socket.setTimeout(4 * GAP_MS, () => {
            socket.destroy();
            resolve(received);
        });
    });
    assert.match(idleTunnel, /^HTTP\/1\.1 200 /, `the tunnel was never established: ${idleTunnel}`);
    assert.ok(
        idleTunnel.includes('ping'),
        `a tunnel idle for ${GAP_MS} ms carried nothing afterwards: ${JSON.stringify(idleTunnel)}. ` +
            'The dial timer is still running once the tunnel is up, so every session quieter than ' +
            'it is torn down while both ends still believe they have one.',
    );

    // --- and the cap has to be given back ---------------------------------
    // The flood case above proves the cap holds. This one proves it recovers:
    // the cap alone turns a descriptor leak into a lockout, since a local app
    // that opens maxConnections sockets and then walks away leaves git, npm,
    // the gallery and the CLI refused at accept time with nothing to reap them.
    // Held sockets that never finish their request headers are swept, so the
    // peer has to keep re-dialling to keep the proxy denied.
    const HOLD = 160;
    const holders = [];
    for (let i = 0; i < HOLD; i++) {
        holders.push(
            await new Promise((resolve) => {
                const socket = net.connect(shortPort, '127.0.0.1', () => resolve(socket));
                socket.on('error', () => resolve(socket));
            }),
        );
    }
    // Still inside the first sweep interval, so the cap is full: the proxy is
    // unreachable and a request through it cannot even get a status back.
    const whileHeld = await proxiedGet(shortPort, `http://127.0.0.1:${originPort}/`, shortCredentials)
        .then((res) => `answered ${res.status}`, (err) => err.code || err.message);
    assert.ok(
        !whileHeld.startsWith('answered'),
        `${HOLD} sockets holding the cap did not deny a legitimate request (${whileHeld}); ` +
            'the case below cannot then tell recovery from a cap that was never reached',
    );
    // Past two sweeps, so anything still in the header phase has been collected
    // whichever tick caught it.
    await new Promise((resolve) => setTimeout(resolve, 2 * SWEEP_MS + SHORT_MS));
    const afterSweep = await proxiedGet(shortPort, `http://127.0.0.1:${originPort}/`, shortCredentials)
        .then((res) => `answered ${res.status}`, (err) => err.code || err.message);
    assert.strictEqual(
        afterSweep, 'answered 200',
        `the proxy was still denied ${2 * SWEEP_MS + SHORT_MS} ms after the flood stopped ` +
            `(${afterSweep}); without a header-phase bound the sockets hold the cap until Node's ` +
            'own default collects them, so one app can lock every other client out at will',
    );
    holders.forEach((socket) => socket.destroy());

    // --- the token must not escape into any log ---------------------------
    // Both instances log through the same collector, so both credentials are
    // asked of every line.
    const secrets = [proxy, shortProxyUrl].map((url) => decodeURIComponent(url.password));
    for (const line of logLines) {
        for (const secret of secrets) {
            assert.ok(!line.includes(secret), `the token reached the log: ${line}`);
        }
    }

    echoSockets.forEach((socket) => socket.destroy());
    origin.close();
    echo.close();
    mute.close();
    gapped.close();
    console.log(
        `ok -- ws:// relayed, ${FLOOD} unauthenticated sockets bounded and the cap given back ` +
            `within ${2 * SWEEP_MS + SHORT_MS} ms, a silent origin dropped at ${SHORT_MS} ms on ` +
            `the plain and the upgrade leg alike, a ${GAP_MS} ms gap survived in a body and in a ` +
            `tunnel, ${logLines.length} log line(s), none carrying a token`,
    );
}

main()
    .then(() => {
        // The abort cases above leave nothing behind only if the proxy tears
        // down the far half of each connection. When it does not, every
        // assertion still passes and the process simply never exits -- which is
        // how that leak hid in the first place. This turns the hang into a named
        // failure instead of a timeout someone has to interpret.
        const bail = setTimeout(() => {
            console.error(
                'FAIL: assertions passed but the event loop is still busy.\n' +
                    'Something the proxy opened was never closed -- most likely an upstream\n' +
                    'leg left draining after a client went away.',
            );
            process.exit(1);
        }, 2000);
        bail.unref();
    })
    .catch((err) => {
        console.error(err);
        process.exit(1);
    });
