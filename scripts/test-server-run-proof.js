'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { execFileSync } = require('node:child_process');
const { stripTypeScriptTypes } = require('node:module');
const { createHmac } = require('node:crypto');
const http = require('node:http');
const net = require('node:net');

if (typeof stripTypeScriptTypes !== 'function') {
    console.error(`${path.basename(__filename)}: needs node:module.stripTypeScriptTypes, ` +
        `which Node 22.13+ or 23.2+ has (this is ${process.version}). CI runs Node 24.`);
    process.exit(1);
}

module.exports = (async () => {
    const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'server-run-proof-'));
    try {
        const sourceFile = path.join(temporary, 'src/server-main.ts');
        fs.mkdirSync(path.dirname(sourceFile));
        fs.copyFileSync(path.join(__dirname, 'fixtures/server-run-proof.ts'), sourceFile);
        const patch = path.join(__dirname, '../patches/0039-authenticate-the-editor-listener.patch');
        if (fs.existsSync(patch)) execFileSync('git', ['apply', patch], { cwd: temporary });
        const source = fs.readFileSync(sourceFile, 'utf8')
            .replace(/^import .*;$/gm, '');
        let installedHandler;
        async function request(key, suffix, method = 'GET') {
            let handler;
            const context = { http: { createServer(fn) { handler = fn; } }, createHmac,
                process: { env: key === undefined ? {} : { VSCODROID_SERVER_RUN_ID: key } } };
            vm.runInNewContext(stripTypeScriptTypes(source) + '\nglobalThis.install = installServerRequest;', context);
            context.install(async () => ({ handleRequest(_req, res) { res.writeHead(418); res.end('delegated'); } }), { mark() {} });
            installedHandler = handler;
            const response = { status: 0, headers: {}, body: '',
                writeHead(status, headers = {}) { this.status = status; this.headers = headers; return this; },
                end(body = '') { this.body = body; return this; } };
            await handler({ method, url: '/vscodroid-server-proof/' + suffix }, response);
            return response;
        }
        const first = await request('fixture-run-id', 'a'.repeat(32));
        assert.equal(first.status, 200, 'the editor listener must answer a run challenge before normal request handling');
        assert.equal(first.body, 'fdfd42f446dcfff9eae9b3e706d1d74c747a989a891d21f773422d71210575b6');
        assert.equal(first.headers['Cache-Control'], 'no-store');
        assert.equal(first.headers['Content-Type'], 'text/plain');
        const next = await request('fixture-run-id', 'b'.repeat(32));
        assert.equal(next.body, 'ab3025aca4e6b67a046c1143891abef8c550291ade7fb71c55da53af7448c0e2');
        assert.notEqual((await request('different-run-id', 'a'.repeat(32))).body, first.body);
        for (const key of [undefined, '']) {
            const missing = await request(key, 'a'.repeat(32));
            assert.equal(missing.status, 404);
            assert.equal(missing.body, '');
        }
        for (const suffix of ['', 'a'.repeat(31), 'a'.repeat(33), 'A'.repeat(32), 'a'.repeat(32) + '?extra=1']) {
            assert.equal((await request('fixture-run-id', suffix)).status, 418);
        }
        assert.equal((await request('fixture-run-id', 'a'.repeat(32), 'POST')).status, 418);
        assert.equal((await request('fixture-run-id', 'a'.repeat(100_000))).status, 418);
        // The app requests HTTP/1.0 so this writeHead/end route uses close framing.
        const server = http.createServer(installedHandler);
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        try {
            const wire = await new Promise((resolve, reject) => {
                const socket = net.connect(server.address().port, '127.0.0.1');
                const chunks = [];
                socket.setTimeout(2000, () => socket.destroy(new Error('proof response timed out')));
                socket.on('error', reject);
                socket.on('connect', () => socket.write('GET /vscodroid-server-proof/' +
                    'a'.repeat(32) + ' HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n'));
                socket.on('data', chunk => chunks.push(chunk));
                socket.on('end', () => resolve(Buffer.concat(chunks).toString('ascii')));
            });
            assert.match(wire, /^HTTP\/1\.1 200 /);
            assert.doesNotMatch(wire, /Transfer-Encoding:/i);
            assert.match(wire, /Connection: close\r\n/i);
            assert.equal(wire.split('\r\n\r\n')[1], first.body);
        } finally {
            await new Promise(resolve => server.close(resolve));
        }
        console.log('ok - editor listener signs fresh bounded challenges and refuses a missing run key');
    } finally {
        fs.rmSync(temporary, { recursive: true, force: true });
    }
})();

if (require.main === module) module.exports.catch(error => { console.error(error); process.exitCode = 1; });
