/**
 * A loopback proxy that exists to give musl clients working DNS.
 *
 * The Claude Code CLI is a musl binary, and musl resolves names by reading
 * /etc/resolv.conf. Android has no such file and no writable /etc -- name
 * resolution there lives in Bionic, behind netd. So the CLI resolves nothing:
 * measured on an API 36 emulator, getaddrinfo("api.anthropic.com") fails with
 * EAI_AGAIN from a musl binary while the identical call from a Bionic one
 * returns an address, and a raw TCP connect to a literal address succeeds from
 * both. DNS is the only broken part.
 *
 * This process is Node, so it is Bionic, so its DNS works. Pointing the CLI at
 * 127.0.0.1 through HTTPS_PROXY means it only ever dials a literal address and
 * never resolves anything; the name is resolved here instead. With the proxy in
 * place the same CLI reaches api.anthropic.com and gets a real HTTP 401 back
 * from an invalid key, where without it every attempt failed with no status at
 * all and retried until it gave up.
 *
 * Both proxy shapes are implemented because HTTPS_PROXY is exported to the whole
 * server process, and VS Code's own proxy resolver reads it too: CONNECT for
 * TLS, and forwarding for a request that arrives as an absolute URI. Node's own
 * http/https modules ignore these variables, but that is a statement about
 * Node's modules and not about extension code, which is the mistake this
 * paragraph used to make: a library that reads the environment itself, needle
 * for one, forwards an absolute https URI over the plain leg rather than
 * opening a tunnel. So the forwarding leg carries TLS traffic in practice and
 * has to honour the scheme it was given. See the branch that picks between
 * http.request and https.request below for what it cost when it did not.
 *
 * The listener lives inside the editor server, not inside the bootstrap that
 * forks it, and that is a correctness requirement rather than a preference. The
 * bootstrap is SIGKILLed as a matter of routine here -- the OOM killer and
 * Android's phantom-process limit both do it, which is what ProcessManager's
 * watchdog exists for -- while the server it forked keeps running and keeps the
 * port, and the next launch adopts that survivor rather than losing the user's
 * session. A proxy bound in the bootstrap died with it and left the survivor
 * pointing HTTPS_PROXY at a port nothing was listening on for the whole of that
 * session: the Open VSX gallery, extension installs, the agent host, the CLI,
 * and git, npm and curl in every terminal, since terminals inherit the same
 * environment, all failed to reach the network while the workbench itself
 * looked healthy because it is reached by address through NO_PROXY. A running
 * process's environment cannot be changed from outside, so the address had to
 * become one the process can keep answering. `server.js` therefore preloads this
 * file into the child it forks (`--require`) and the child binds its own proxy
 * and sets its own environment, which costs no extra process against the
 * 32-process budget and gives the listener exactly the lifetime of the server
 * that uses it.
 *
 * Binding to 127.0.0.1 is not access control on Android. Loopback is not
 * per-app isolated -- any installed app can connect to another app's loopback
 * port -- so an unauthenticated forwarder here is reachable by every app on the
 * device, and would let any of them make arbitrary outbound connections that
 * appear to come from VSCodroid. The random port only raises the cost of
 * finding it. Hence Basic proxy auth with a token minted per boot: the
 * credentials ride in the proxy URL, which is how every standard proxy client
 * already learns them, so nothing downstream needs to know this exists.
 */

const http = require('http');
const https = require('https');
const net = require('net');
const crypto = require('crypto');
const { URL } = require('url');

/**
 * How long an origin has to answer before its leg is dropped.
 *
 * It bounds the setup only, and is cleared the moment a response starts or a
 * tunnel is spliced, so a long-lived stream with quiet stretches is never cut by
 * it. Without it an origin that accepts a connection and then says nothing --
 * routine on a mobile network -- pinned a descriptor at each end until TCP
 * keepalive gave up, in the same process that serves the workbench.
 */
const UPSTREAM_TIMEOUT_MS = 30_000;

/**
 * How long a connection may hold a descriptor without finishing its request
 * headers, and how often the server sweeps for the ones that have not.
 *
 * Both, because the sweep granularity is the other half of the bound: Node
 * destroys an overrun connection on the next tick of `connectionsCheckingInterval`,
 * so a peer is reclaimed somewhere between one and two of these. One number for
 * both keeps that window plainly five to ten seconds instead of hiding it in the
 * gap between two knobs.
 *
 * Five seconds because every legitimate client of this proxy is inside this app,
 * on loopback, and sends its headers in one packet; nothing here is a slow
 * network peer. What it bounds is the other traffic loopback carries on Android,
 * where any installed app can reach this port: Node's own defaults let a peer
 * that connects and then says nothing hold its slot for up to 90 s (60 s
 * headersTimeout collected on a 30 s sweep, both read back from a default
 * server on v20.19.5, v22.23.2 and v24.18.0), and maxConnections turns that from
 * a descriptor leak into a lockout, since 128 such sockets are the whole cap and
 * git, npm, the gallery and the CLI are then refused at accept time until it
 * expires.
 */
const HEADER_PHASE_MS = 5_000;

/**
 * Headers that address this proxy and must not travel on to the origin.
 *
 * `proxy-authorization` carries the token minted for this boot, so forwarding it
 * hands this device's credential to every host a client dials. `proxy-connection`
 * is the non-standard hop-by-hop cousin of `connection` that older clients still
 * send; past this hop it means nothing, and all it does at the origin is announce
 * that a proxy is in the path.
 *
 * One list rather than a test on each leg. Two legs forward headers, the plain
 * one and the upgrade one, and a per-leg copy of the rule is how they came to
 * strip different things while carrying the same paragraph of reasoning.
 */
const HOP_BY_HOP_HEADERS = ['proxy-authorization', 'proxy-connection'];

/**
 * The address a socket call wants, from the hostname a URL parser gives.
 *
 * WHATWG keeps the brackets on an IPv6 literal -- new URL("http://[::1]/")
 * reports "[::1]" -- while net.connect and http.request want the bare address
 * and hand anything else to DNS. The bracketed form therefore resolves nothing
 * and fails as ENOTFOUND naming a host that was never a name.
 *
 * @param {string} hostname
 * @returns {string}
 */
function bareHost(hostname) {
    return hostname.startsWith('[') ? hostname.slice(1, -1) : hostname;
}

/**
 * AggregateError -- what a failed Happy Eyeballs connect throws -- carries an
 * empty message, so the reason survives only in `errors`.
 *
 * @param {Error & {errors?: Error[]}} err
 * @returns {string}
 */
function reason(err) {
    return err.errors ? err.errors.map((e) => e.message).join('; ') : err.message;
}

/**
 * Binds the proxy and resolves with the environment the child should inherit.
 *
 * Never rejects. A proxy that cannot bind must not take the workbench down with
 * it, so failure resolves to an empty environment and everything simply talks
 * directly -- which is what the Bionic side wanted anyway; only musl clients
 * lose out.
 *
 * @param {(level: string, message: string) => void} log
 * @returns {Promise<Record<string, string>>}
 */
function start(log) {
    // Node gives each address 250ms before it abandons that attempt and tries
    // the next family. api.anthropic.com handshakes in 227-500ms from here and
    // the IPv6 route is absent (EHOSTUNREACH), so the default lost roughly
    // three connects in five -- reported as an AggregateError whose message is
    // the empty string, which is why the warnings below could only ever say
    // "failed: ". Widening the window costs nothing when the first address
    // answers promptly. Process-wide on purpose: the plain-HTTP path here and
    // every other outbound connect in this process share the exposure.
    //
    // Which process that is has changed, and the blast radius with it. This is
    // preloaded into the editor server rather than run in the bootstrap, so the
    // setting now also governs the gallery query, extension downloads and the
    // agent host: on a host whose AAAA record resolves to a route that hangs
    // rather than refusing, each of those waits a second before trying IPv4
    // instead of a quarter of one. Measured here the IPv6 route is simply absent
    // and fails immediately, which is why the window is worth widening at all.
    net.setDefaultAutoSelectFamilyAttemptTimeout(1000);

    // Minted per boot and never persisted: a token that outlived the process
    // would be a credential on disk for a proxy that no longer exists.
    //
    // Hex, and it has to stay hex or something equally URL-inert. The token
    // travels inside a URL's userinfo, and every client that reads it back runs
    // it through decodeURIComponent first -- https-proxy-agent/dist/index.js:102
    // and http-proxy-agent/dist/index.js:82 both do. A '%' from some shorter
    // encoding would either be silently rewritten or throw URIError there,
    // which surfaces here as an inexplicable 407 in a client we do not own.
    const token = crypto.randomBytes(32).toString('hex');
    const expected = crypto
        .createHash('sha256')
        .update(Buffer.from(`vscodroid:${token}`).toString('base64'))
        .digest();

    /**
     * Matches the scheme case-insensitively and compares only the credentials.
     *
     * RFC 7235 makes the auth-scheme case-insensitive and Node passes the
     * header through exactly as it arrived -- measured: "basic QUJD" stays
     * "basic QUJD". Comparing the whole header string would therefore reject a
     * conforming client over nothing but its capitalisation, and the client
     * likeliest to be caught by that is the Claude Code CLI: a third-party
     * binary this project does not bundle and cannot inspect, whose failure
     * would look like an inexplicable 407.
     *
     * Digests before comparing so both buffers are always 32 bytes;
     * timingSafeEqual throws outright on a length mismatch, which would turn a
     * malformed header into a crash and leak the expected length besides.
     *
     * A duplicated header cannot be used to smuggle a second attempt past this:
     * proxy-authorization is one of the headers Node keeps the first of and
     * discards the rest -- also measured.
     *
     * @param {import('http').IncomingMessage} req
     * @returns {boolean}
     */
    function authorized(req) {
        const given = req.headers['proxy-authorization'];
        if (typeof given !== 'string') {
            return false;
        }
        const credentials = /^basic[ \t]+([A-Za-z0-9+/=]+)[ \t]*$/i.exec(given);
        if (!credentials) {
            return false;
        }
        return crypto.timingSafeEqual(crypto.createHash('sha256').update(credentials[1]).digest(), expected);
    }

    const CHALLENGE = 'Basic realm="vscodroid"';

    /**
     * Writes a final response on a CONNECT socket and then releases it.
     *
     * end() on its own does not release anything here. http.Server constructs
     * its sockets with allowHalfOpen -- measured true on the bundled runtime --
     * so end() sends our FIN and then waits for the peer's. A client that
     * simply stops talking never sends one, and the descriptor stays pinned for
     * the life of the process: measured, 100 rejected CONNECTs held 100
     * server-side sockets, still held at 65 seconds. Nothing reaps them,
     * because none of the HTTP timeouts govern a socket once 'connect' has
     * fired -- also measured, against a server whose timeouts demonstrably
     * still reap an ordinary request.
     *
     * The callback runs once writableFinished is set, so the response is fully
     * handed to the kernel before the descriptor closes; a challenge-response
     * client such as git still reads the whole 407 and retries with
     * credentials. It is also called on a socket that was already destroyed,
     * so there is no path where this silently does nothing.
     */
    const closeWith = (socket, response) => socket.end(response, () => socket.destroy());

    return new Promise((resolve) => {
        let settled = false;
        const done = (env) => {
            if (!settled) {
                settled = true;
                resolve(env);
            }
        };

        // Passed here and not assigned to the server afterwards: the sweep that
        // enforces the header bound is a constructor option too, and setting one
        // without the other leaves the bound at the sweep's 30 s granularity.
        // See HEADER_PHASE_MS and the paragraph above maxConnections.
        const server = http.createServer({
            connectionsCheckingInterval: HEADER_PHASE_MS,
            headersTimeout: HEADER_PHASE_MS,
        }, (req, res) => {
            if (!authorized(req)) {
                // No upstream leg exists yet, so there is nothing to tear down
                // -- but an unhandled 'error' on either stream is still fatal,
                // and a client that walked away before reading the 407 raises
                // one here.
                req.on('error', () => {});
                res.on('error', () => {});
                // "Connection: close" is what keeps an unauthenticated peer
                // inside the bound HEADER_PHASE_MS exists to impose. That sweep
                // watches the header phase only, and a peer that finishes its
                // headers steps out of its reach: the request body is never read
                // here, so the connection sits in Node's request phase, where the
                // only bound left is requestTimeout at its 300 s default.
                //
                // Measured against the shipped file, on the same socket that
                // sends complete headers and then dribbles one body byte every
                // 2 s: with keep-alive it was still holding its slot at 30 s and
                // the timer never advanced, because each byte resets it; with
                // this header it is gone in 3 ms. A peer that sends nothing after
                // the headers was already closed at 6 s (keepAliveTimeout plus
                // Node's 1 s buffer), so the header is not what bounds that one --
                // it is the dribble, where the difference is a slot held for as
                // long as the peer cares to hold it against 128 of them being the
                // whole cap.
                //
                // A challenge-response client is unaffected: it opens a fresh
                // connection for the retry, which on loopback costs nothing. This
                // is not the CONNECT leg's reason for the same header, where the
                // FIN is already sent by hand and the header is what stops git
                // retrying into a dead socket.
                res.writeHead(407, {
                    'Proxy-Authenticate': CHALLENGE,
                    Connection: 'close',
                }).end();
                return;
            }
            // Addressed to this proxy, so they stop here. See HOP_BY_HOP_HEADERS.
            for (const name of HOP_BY_HOP_HEADERS) delete req.headers[name];

            // Plain HTTP: the request line carries an absolute URI.
            let target;
            try {
                target = new URL(req.url);
            } catch {
                res.writeHead(400).end();
                return;
            }
            // Two schemes and no fallback. Anything else reaching the branch
            // below would be dialled as plain HTTP on port 80 under a scheme
            // that promised something different, which is the failure this leg
            // spent its whole life having for https.
            if (target.protocol !== 'http:' && target.protocol !== 'https:') {
                res.writeHead(400).end();
                return;
            }

            // The scheme picks the module and the default port. Getting that
            // wrong was not merely a broken request: this leg called
            // http.request for every absolute URI and defaulted the port to 80,
            // so an https:// request left the device in cleartext on port 80,
            // carrying whatever Authorization header the client had put on it.
            //
            // It read as a functional bug rather than a confidentiality one
            // because the origin answers instead of failing. GitHub replies 301
            // with a Location identical to the request URL, its ordinary
            // redirect to TLS, so a client that does not follow redirects
            // (needle, which several extensions bundle) sees a 301 with an
            // empty body and reports the site as broken.
            //
            // Only clients that forward an absolute https URI over this leg
            // were affected, which is why it survived: a TLS client normally
            // arrives by CONNECT. The ones that do not are the libraries that
            // read HTTPS_PROXY out of the environment themselves, and this
            // process exports it to the whole editor server and every terminal.
            //
            // servername is deliberately not passed, and the Host header below
            // is what makes that safe. Node does not take SNI from `host`: its
            // agent reads the OUTGOING Host header, strips the port, answers
            // with nothing when that value is an IP literal, and only falls
            // back to `host` when there is no Host header at all. Setting
            // servername by hand is not the way out either, because passing an
            // IP literal there fails the handshake outright.
            const secure = target.protocol === 'https:';
            const upstream = (secure ? https : http).request(
                {
                    // Brackets off, or an IPv6 literal reaches DNS as text.
                    // The CONNECT leg below has always done this; this leg
                    // never did, so http://[::1]:8080/ came back 502 with
                    // ENOTFOUND naming an address rather than a name.
                    host: bareHost(target.hostname),
                    port: target.port || (secure ? 443 : 80),
                    method: req.method,
                    path: target.pathname + target.search,
                    // Host follows the absolute URI and never the client's own
                    // header. RFC 9110 requires a proxy to prefer the
                    // request-target's authority, and here that is not
                    // bookkeeping: the header decides SNI and therefore which
                    // certificate the origin is checked against. Forwarded
                    // verbatim it is whatever the client wrote, and the shape
                    // `http.request` produces when it is handed an absolute URI
                    // as its path is the proxy's own address, which is an IP
                    // literal, which means no server name is sent at all.
                    // Measured on the bundled runtime by reading the record off
                    // the wire: Host `evil.example` put that name in the
                    // ClientHello for a socket dialled at 127.0.0.1, and an
                    // `IP:port` Host produced no server_name extension. An
                    // origin that keys its certificate on SNI, which
                    // raw.githubusercontent.com does, answers the second with
                    // the wrong certificate or with nothing.
                    //
                    // target.host carries the port only when it is not the
                    // scheme's default, which is what a Host header should say.
                    headers: { ...req.headers, host: target.host },
                },
                (upstreamRes) => {
                    // The origin has answered, so the setup bound below is done
                    // its job. Cleared rather than left running, or a response
                    // body that streams with quiet stretches would be cut in the
                    // middle by a timer meant for one that never started.
                    upstream.setTimeout(0);
                    res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
                    // An origin that dies part-way through its body raises the
                    // failure HERE, on the response, and not on the request that
                    // the handler below watches. Measured on node v22.23.2
                    // against an origin that writes a header and destroys the
                    // socket 50 ms later: the client sees `res:aborted`,
                    // `res:error(ECONNRESET)`, `res:close` and the request object
                    // gets only `close`. With nothing listening here, pipe never
                    // reaches 'end', so `res` is never finished and the client
                    // waits forever -- both legs held, no timeout left in the
                    // response phase to cut it. npm and git hang rather than
                    // retry.
                    //
                    // destroy(), not end(). Chunked is the framing at issue: on
                    // a truncated chunked body end() writes the terminating
                    // chunk, and the client reads a complete 200 carrying half a
                    // tarball (measured: complete=true, body "SHORT" of a longer
                    // stream). destroy() sends no terminator, so it surfaces as
                    // ECONNRESET, which is what it is. Under Content-Length the
                    // two agree, because Node refuses to under-deliver a declared
                    // length and resets either way.
                    upstreamRes.on('error', (err) => {
                        log('warn', `dns-proxy: ${target.hostname} died mid-response: ${reason(err)}`);
                        res.destroy();
                    });
                    upstreamRes.pipe(res);
                },
            );
            upstream.setTimeout(UPSTREAM_TIMEOUT_MS, () =>
                upstream.destroy(new Error(`no response within ${UPSTREAM_TIMEOUT_MS} ms`)));
            upstream.on('error', (err) => {
                log('warn', `dns-proxy: ${target.hostname} failed: ${reason(err)}`);
                // An upstream that dies mid-response has already had its status
                // relayed; writeHead would then throw ERR_HTTP_HEADERS_SENT,
                // and an uncaught throw here takes this process down, and this
                // process is now the editor server.
                //
                // Past the headers there is no status left to send and no honest
                // way to finish the body, so the connection goes down instead --
                // same reason as the response-side handler above, and stated
                // once there.
                if (res.headersSent) {
                    res.destroy();
                    return;
                }
                res.writeHead(502);
                res.end();
            });
            // A client that vanishes mid-transfer must tear down the upstream
            // leg, not surface as an unhandled 'error' -- same contract as the
            // CONNECT handler below.
            req.on('error', () => upstream.destroy());
            res.on('error', () => upstream.destroy());
            // 'error' is not enough on its own. A client that simply goes away
            // mid-response produces no failed write, so Node reports it as
            // 'close' and nothing else -- leaving this leg to drain the origin
            // into a socket nobody is reading. Firing on normal completion too
            // is harmless: the request is finished by then.
            res.on('close', () => upstream.destroy());
            req.pipe(upstream);
        });

        // CONNECT: open a tunnel and stay out of the bytes. Resolution of `host`
        // happens in this process, which is the whole point.
        server.on('connect', (req, clientSocket, head) => {
            // Registered before the first thing that can fail: a 407 written to
            // a client that has already walked away emits EPIPE here, and an
            // uncaught 'error' on a socket takes this process down, and this
            // process is now the editor server.
            let upstream = null;
            clientSocket.on('error', () => upstream && upstream.destroy());
            // Same reason as the plain-HTTP leg: an abrupt client is a 'close'
            // here, not an 'error', and without this the tunnel's far half stays
            // open with nothing on the near end.
            clientSocket.on('close', () => upstream && upstream.destroy());

            if (!authorized(req)) {
                // "Connection: close" is load-bearing, not decoration. git does
                // not send Basic preemptively -- http.proxyAuthMethod defaults
                // to anyauth, which means "expect a 407 first" -- so it answers
                // this challenge by retrying the CONNECT on the same socket.
                // end() has already sent FIN, and without this header the
                // client is entitled to think the connection is still reusable;
                // the retry lands on a dead socket and git reports "Proxy
                // CONNECT aborted" with nothing pointing at a proxy token.
                // Measured: adding this one header is what makes git work, and
                // Content-Length: 0 in its place does not.
                //
                // The plain-HTTP 407 above carries the same header for an
                // unrelated reason, spelled out there: Node frames that response
                // itself and would keep the connection alive, which is what let
                // an unauthenticated peer hold a slot indefinitely.
                closeWith(
                    clientSocket,
                    `HTTP/1.1 407 Proxy Authentication Required\r\n` +
                        `Proxy-Authenticate: ${CHALLENGE}\r\n` +
                        `Connection: close\r\n\r\n`,
                );
                return;
            }

            // Parsed rather than split on ':' because a CONNECT target may be an
            // IPv6 literal: "[::1]:443".split(':') yields ["[", "", "1]", "443"],
            // so the old form dialled a host named "[". The WHATWG parser gets
            // this right and rejects a malformed authority outright, which also
            // closes a second hole -- "host:99999999" used to reach net.connect
            // and throw ERR_SOCKET_BAD_PORT synchronously, taking down whichever
            // process holds the listener, which is now the editor server rather
            // than the bootstrap it was when that hole existed. It keeps the
            // brackets on an IPv6 hostname; net.connect wants the bare address.
            let host;
            let port;
            try {
                const authority = req.url;
                // The port is read from the raw text, never from the parser. A
                // WHATWG URL normalises away a port that equals the scheme
                // default, so `host:80` parses to an empty .port and would then
                // be dialled as 443 -- a wrong dial for a valid target. The
                // authority-form of CONNECT always carries an explicit port
                // (RFC 7231 4.3.6), so the last colon is the separator, and it
                // has to fall outside any bracketed IPv6 address.
                const sep = authority.lastIndexOf(':');
                if (sep === -1 || sep < authority.lastIndexOf(']')) {
                    throw new Error('no port in CONNECT target');
                }
                port = Number(authority.slice(sep + 1));
                if (!Number.isInteger(port) || port < 1 || port > 65535) {
                    throw new Error('port out of range');
                }
                // The host still comes from the parser, which is the part that
                // gets the bracketed IPv6 form right; it keeps the brackets and
                // net.connect wants them off.
                host = bareHost(new URL(`http://${authority}`).hostname);
                if (!host) {
                    throw new Error('no host in CONNECT target');
                }
            } catch {
                closeWith(clientSocket, 'HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
                return;
            }
            // Whether the 200 has gone out. It is the only thing separating a
            // tunnel that could not be built from one that broke, and this leg
            // had nothing recording it, the plain-HTTP leg beside it has
            // res.headersSent for the identical question.
            let established = false;
            upstream = net.connect(port, host, () => {
                established = true;
                // Same bound as the plain-HTTP leg, and cleared at the same
                // point: a tunnel is expected to sit idle for minutes at a time,
                // so only the dial is timed.
                upstream.setTimeout(0);
                clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
                if (head && head.length) {
                    upstream.write(head);
                }
                upstream.pipe(clientSocket);
                clientSocket.pipe(upstream);
            });
            upstream.setTimeout(UPSTREAM_TIMEOUT_MS, () =>
                upstream.destroy(new Error(`no answer within ${UPSTREAM_TIMEOUT_MS} ms`)));
            upstream.on('error', (err) => {
                if (established) {
                    // Nothing but tunnel bytes may follow a 2xx to CONNECT
                    // (RFC 9110 9.3.6), and this handler outlives the handshake:
                    // an origin that dies mid-session, an RST, routine on a
                    // mobile network, used to splice 47 bytes of plain HTTP
                    // into the middle of the stream. In a TLS session those
                    // bytes are read as a record with content type 0x48, so the
                    // client reports a protocol failure instead of the reset
                    // that actually happened, and a retryable error becomes one
                    // that is not.
                    log('warn', `dns-proxy: CONNECT ${host}:${port} tunnel broke: ${reason(err)}`);
                    clientSocket.destroy();
                    return;
                }
                log('warn', `dns-proxy: CONNECT ${host}:${port} failed: ${reason(err)}`);
                closeWith(clientSocket, 'HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n');
            });
        });

        // An upgrade request -- a plain ws:// dialled through this proxy -- arrives
        // on this event and nowhere else, and Node closes the connection outright
        // when nothing is listening for it. So such a client was dropped with no
        // status at all, while wss:// worked because it goes through CONNECT
        // above. HTTP_PROXY reaches the whole editor server and every terminal,
        // so that was ordinary tools rather than only the one CLI this file
        // exists for.
        //
        // Forwarded rather than refused: the request is re-sent in origin-form to
        // the host it names and the sockets are spliced once the origin answers,
        // so the 101 and everything after it are the origin's own bytes. Nothing
        // is read after the splice, for the same reason the CONNECT tunnel stays
        // out of its stream.
        server.on('upgrade', (req, clientSocket, head) => {
            // Registered before the first thing that can fail, exactly as on the
            // CONNECT leg: an uncaught 'error' on a socket takes this process
            // down, and this process is now the editor server.
            let upstream = null;
            let established = false;
            clientSocket.on('error', () => upstream && upstream.destroy());
            clientSocket.on('close', () => upstream && upstream.destroy());

            if (!authorized(req)) {
                closeWith(
                    clientSocket,
                    `HTTP/1.1 407 Proxy Authentication Required\r\n` +
                        `Proxy-Authenticate: ${CHALLENGE}\r\n` +
                        `Connection: close\r\n\r\n`,
                );
                return;
            }

            let target;
            try {
                target = new URL(req.url);
            } catch {
                closeWith(clientSocket, 'HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
                return;
            }
            // The same rule the plain leg keeps, and this leg was left out of it.
            // It spliced raw bytes to whatever the URI named while reading the
            // scheme for one thing only, the default port: an https:// upgrade
            // was dialled on 443 and then written in the clear, so a request
            // head carrying an Authorization header or a cookie left the device
            // unencrypted on the port that promised TLS, and every other scheme,
            // wss:// included, fell to 80 and was dialled as plain HTTP.
            //
            // Refused rather than tunnelled, because this leg has no TLS in it
            // and adding some would be a second implementation of what CONNECT
            // already does properly; a client that wants TLS through a proxy
            // opens a tunnel. ws: is allowed beside http: because it means the
            // same plaintext upgrade and is what a websocket client may put in
            // the request line; a proxied browser sends http: there, which is
            // what the case in scripts/test-dns-proxy.js does.
            if (target.protocol !== 'http:' && target.protocol !== 'ws:') {
                closeWith(clientSocket, 'HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
                return;
            }
            const port = Number(target.port) || 80;
            upstream = net.connect(port, bareHost(target.hostname), () => {
                const lines = [`${req.method} ${target.pathname}${target.search} HTTP/1.1`];
                for (let i = 0; i < req.rawHeaders.length; i += 2) {
                    // The same list the plain-HTTP leg deletes from, read from
                    // the same place: see HOP_BY_HOP_HEADERS.
                    if (HOP_BY_HOP_HEADERS.includes(req.rawHeaders[i].toLowerCase())) continue;
                    lines.push(`${req.rawHeaders[i]}: ${req.rawHeaders[i + 1]}`);
                }
                upstream.write(`${lines.join('\r\n')}\r\n\r\n`);
                if (head && head.length) {
                    upstream.write(head);
                }
                // The origin's first byte, not the TCP connect, is what ends
                // setup here. CONNECT can release the bound at connect because
                // that is where its own 200 goes out; this leg has promised the
                // client nothing until the origin's 101 arrives, so releasing
                // at connect reopened exactly the hole UPSTREAM_TIMEOUT_MS
                // exists to close. Measured against a copy of this file with
                // that constant rewritten to 600 ms, dialling an origin that
                // accepts TCP and then says nothing: the plain-HTTP leg
                // answered 502 and logged the bound, while the identical ws://
                // upgrade was still open six seconds later with no byte written
                // to the client and nothing logged -- a descriptor pinned at
                // each end plus one of the maxConnections slots, for the life
                // of the process.
                //
                // Attached before the pipes so no byte can slip between the
                // two: a 'data' listener resumes the stream on the next tick
                // and both are registered in this same turn.
                upstream.once('data', () => {
                    upstream.setTimeout(0);
                    established = true;
                });
                upstream.pipe(clientSocket);
                clientSocket.pipe(upstream);
            });
            upstream.setTimeout(UPSTREAM_TIMEOUT_MS, () =>
                upstream.destroy(new Error(`no answer within ${UPSTREAM_TIMEOUT_MS} ms`)));
            upstream.on('error', (err) => {
                if (established) {
                    // Past the splice nothing but the origin's bytes may follow,
                    // for the reason the CONNECT handler above spells out.
                    log('warn', `dns-proxy: upgrade ${target.host} broke: ${reason(err)}`);
                    clientSocket.destroy();
                    return;
                }
                log('warn', `dns-proxy: upgrade ${target.host} failed: ${reason(err)}`);
                closeWith(clientSocket, 'HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n');
            });
        });

        // A bound on a listener every app on the device can reach. The Basic
        // token defends what this proxy will DO and defended nothing about the
        // socket itself: every unauthenticated connection is a descriptor in the
        // process that also serves the workbench, and nothing else here limits
        // how many of them one peer may hold. Every legitimate client is inside
        // this app and its headers arrive in one packet, so the ceiling is far
        // above anything an npm install or a git fetch opens at once and far
        // below what a flood needs to hurt.
        //
        // It changes the shape of the failure rather than removing it, and the
        // cheaper shape is the point. Node closes connections past the cap at
        // accept time and cannot tell this app's clients from anyone else's, so
        // a local app that opens 128 sockets denies the proxy to git, npm and
        // the CLI. Without the cap the same app needed roughly 32k of them and
        // took the descriptors of the process serving the workbench with it.
        //
        // A cap alone would make that lockout permanent, which is what the
        // header-phase bound passed to createServer above is for: sockets that
        // hold a slot without ever completing a request are swept, so the peer
        // has to keep re-dialling to keep the proxy denied rather than
        // connecting once and walking away. Both values have to be given at
        // construction to get that: a bound enforced by a sweep is only as sharp
        // as the sweep, and assigning server.headersTimeout afterwards leaves
        // connectionsCheckingInterval at its 30 s default. Measured on node
        // v22.23.2, a peer held at headersTimeout = 1000 assigned that way died
        // at 30.0 s -- a number that reads as one second and never is.
        //
        // What the pair does, measured on node v20.19.5, v22.23.2 and v24.18.0
        // (the bundled runtime), with the two options at 1000 ms and 500 ms so
        // the timings are readable: a peer that sends nothing, and one that
        // sends a request line and stops, are both answered 408 and closed
        // between one and two sweeps later (1.5 s on v20, 1.0 s on the other
        // two), while an established CONNECT tunnel and an established upgrade
        // sit silent for 3 s and still carry a byte afterwards on all three --
        // Node stops tracking a connection once its parser is detached, which is
        // what either handshake does. A keep-alive connection idle for 2.5 s
        // between two requests is untouched, and server.unref() below still lets
        // the process exit immediately, because Node unrefs the sweep timer.
        // Reproduce by passing the two options to any http.createServer and
        // dialling it with a raw socket.
        server.maxConnections = 128;

        server.on('error', (err) => {
            log('warn', `dns-proxy: not started (${err.message}); musl clients will not resolve names`);
            done({});
        });

        // Port 0: the kernel picks a free one and the child is told which. No
        // fixed port to collide with, and nothing to persist between launches.
        server.listen(0, '127.0.0.1', () => {
            const { port } = server.address();
            // The log line is deliberately the credential-free form: this goes
            // to logcat and to the workbench output channel, both readable well
            // outside the process that owns the token.
            log('info', `dns-proxy listening on http://127.0.0.1:${port}`);
            const url = `http://vscodroid:${token}@127.0.0.1:${port}`;
            done({
                HTTP_PROXY: url,
                HTTPS_PROXY: url,
                http_proxy: url,
                https_proxy: url,
                // Loopback needs no resolution and must not come back through
                // here -- the workbench itself, and any MCP server a user runs
                // locally, are reached by address already.
                NO_PROXY: 'localhost,127.0.0.1,::1',
                no_proxy: 'localhost,127.0.0.1,::1',
            });
        });

        server.unref();
    });
}

module.exports = { start };

// Self-start, when `server.js` preloads this file into the editor server it
// forks. See the top of this file for why the listener has to live in that
// process rather than in the bootstrap.
//
// The `--require` and the flag are both taken out of this process's own state
// before anything else runs, and both halves matter. `fork` passes the parent's
// execArgv on by default and the editor server hands it to `new Worker` as well,
// so left in place the option that brought this file here would ride into every
// helper the editor server starts: the file watcher, the agent host, the
// extension host and the pty host would each load this module, and each bind a
// proxy of its own and point its own environment at it -- several listeners where
// one was asked for, on a port every app on the device can reach. Removing the
// option means they are never asked to load it; clearing the flag means that
// loading it would still do nothing.
//
// Nothing here may throw. A `--require` module that throws stops the process
// loading its main script at all, and that process is the editor server, so a
// failure to bind must cost musl clients their DNS and nothing else -- the
// contract [start] already documents, made mechanical.
//
// The variables land one turn late, and it is what the server does with them
// that makes that safe. [start] resolves in its listen callback, so process.env
// carries no proxy variables for the whole synchronous load of
// vscode-reh/out/server-main.js that follows this preload. The shipped bundle
// reads them per request: its proxy lookup takes an environment as a parameter
// and the request service builds that from process.env on every call, so a
// request made after the listener is up finds them. A bundle that instead
// snapshotted process.env at import would silently have no proxy, which is why
// this is written down rather than left to be rediscovered.
if (process.env.VSCODROID_DNS_PROXY === '1') {
    delete process.env.VSCODROID_DNS_PROXY;
    // By trailing path segment rather than by comparing the whole path with
    // __filename: the module loader hands a module its resolved real path, and a
    // checkout behind a symlink (macOS puts its temporary directories behind one)
    // spells the same file two ways.
    //
    // Both spellings of the option, because the option is spelled by another
    // file. `server.js` passes `--require=<path>` as one token on purpose -- a
    // path standing on its own is the first non-option argument, which is what
    // process-monitor.js names a process by, so the editor server's row would
    // read `libnode.so dns-proxy.js` -- and the two-token form is still taken out
    // here so that a preload either side spells differently is never one this
    // process cannot take back out.
    const isProxyPath = (arg) => String(arg || '').endsWith('/dns-proxy.js');
    process.execArgv = process.execArgv.filter((arg, i, all) => {
        if (arg === '--require' && isProxyPath(all[i + 1])) return false;
        if (all[i - 1] === '--require' && isProxyPath(arg)) return false;
        return !(String(arg).startsWith('--require=') && isProxyPath(arg));
    });
    const log = (level, message) =>
        console.log(`[${new Date().toISOString()}] [${level}] ${message}`);
    try {
        start(log).then(
            (env) => Object.assign(process.env, env),
            (e) => log('warn', `dns-proxy did not start: ${e.message}`),
        );
    } catch (e) {
        log('warn', `dns-proxy did not start: ${e.message}`);
    }
}
