'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { execFileSync } = require('node:child_process');
const { once } = require('node:events');
const { stripTypeScriptTypes } = require('node:module');

if (typeof stripTypeScriptTypes !== 'function') {
    console.error(`${path.basename(__filename)}: needs node:module.stripTypeScriptTypes, ` +
        `which Node 22.13+ or 23.2+ has (this is ${process.version}). CI runs Node 24.`);
    process.exit(1);
}

function fetchBounded(port, targetPath, timeoutMs = 1500, maxBytes = 64 * 1024 * 1024, options = {}) {
    return new Promise(resolve => {
        const start = Date.now();
        let settled = false;
        const finish = outcome => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve({ ...outcome, elapsedMs: Date.now() - start });
        };
        const req = http.get({
            host: '127.0.0.1',
            port,
            path: '/vscode-remote-resource?path=' + encodeURIComponent(targetPath),
            headers: { Connection: 'close', ...options.headers },
        }, res => {
            let totalBytes = 0;
            const smallChunks = [];
            res.on('data', chunk => {
                totalBytes += chunk.length;
                if (totalBytes <= 4096) smallChunks.push(chunk);
                if (options.abortAfterBytes && totalBytes >= options.abortAfterBytes) req.destroy();
                if (totalBytes > maxBytes) {
                    req.destroy();
                    finish({
                        status: res.statusCode ?? null,
                        bytes: totalBytes,
                        body: '',
                        completed: false,
                        error: 'MAX_BYTES_EXCEEDED',
                    });
                }
            });
            res.on('end', () => finish({
                status: res.statusCode ?? null,
                headers: res.headers,
                bytes: totalBytes,
                body: totalBytes <= 4096 ? Buffer.concat(smallChunks).toString('utf8') : '',
                completed: true,
                error: null,
            }));
            res.on('close', () => {
                if (!res.complete) {
                    finish({
                        status: res.statusCode ?? null,
                        bytes: totalBytes,
                        body: '',
                        completed: false,
                        error: 'PREMATURE_CLOSE',
                    });
                }
            });
            res.on('error', err => finish({
                status: res.statusCode ?? null,
                bytes: totalBytes,
                body: '',
                completed: false,
                error: err.code || err.message,
            }));
        });
        req.on('error', err => finish({
            status: null,
            bytes: 0,
            body: '',
            completed: false,
            error: err.code || err.message,
        }));
        const timer = setTimeout(() => {
            req.destroy();
            finish({
                status: null,
                bytes: 0,
                body: '',
                completed: false,
                error: 'TIMEOUT',
            });
        }, timeoutMs);
        options.onRequest?.(req);
    });
}

module.exports = (async () => {
    const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'server-serve-file-'));
    try {
        const relativeTarget = 'src/vs/server/node/webClientServer.ts';
        const sourceFile = path.join(temporary, relativeTarget);
        fs.mkdirSync(path.dirname(sourceFile), { recursive: true });
        fs.copyFileSync(path.join(__dirname, 'fixtures/web-client-server.ts'), sourceFile);
        for (const name of ['0006-callback-intent-relay.patch', '0043-serve-only-regular-files.patch']) {
            const patch = path.join(__dirname, '../patches', name);
            execFileSync('git', ['apply', '--include=' + relativeTarget, patch], { cwd: temporary });
        }

        const rawSource = fs.readFileSync(sourceFile, 'utf8')
            .replace(/^import .*;$/gm, '')
            .replace(/^export /gm, '');
        const transformed = stripTypeScriptTypes(rawSource, {
            mode: 'transform',
            sourceUrl: 'webClientServer.ts',
        });

        const errors = [];
        const openedFiles = [];
        let afterStat = () => {};
        let beforeOpen = () => {};
        let openFlags;
        const promises = {
            ...fs.promises,
            async stat(filePath) {
                const stat = await fs.promises.stat(filePath);
                await afterStat(filePath);
                return stat;
            },
            async open(filePath, flags) {
                await beforeOpen(filePath);
                const file = await fs.promises.open(filePath, openFlags ?? flags);
                openedFiles.push(file);
                const stat = file.stat.bind(file);
                file.stat = async () => {
                    const result = await stat();
                    await afterStat(filePath);
                    return result;
                };
                return file;
            },
        };
        const context = {
            constants: fs.constants,
            createReadStream: fs.createReadStream,
            promises,
            crypto,
            Buffer,
            extname: path.extname,
            getMediaMime: () => undefined,
            FileAccess: { asFileUri: () => ({ fsPath: '' }) },
            console: { error() {} },
        };
        vm.runInNewContext(
            transformed + '\nglobalThis.serveFile = serveFile; globalThis.CacheControl = CacheControl;',
            context,
        );
        const { serveFile, CacheControl } = context;
        const logService = { error: err => errors.push(err) };

        const workspaceDir = path.join(temporary, 'workspace');
        fs.mkdirSync(workspaceDir, { recursive: true });

        const regularFile = path.join(workspaceDir, 'settings.json');
        const regularBody = '{"workbench.colorTheme":"Default Dark Modern"}\n';
        fs.writeFileSync(regularFile, regularBody);

        const emptyFile = path.join(workspaceDir, 'empty.json');
        fs.writeFileSync(emptyFile, '');

        const symlinkToRegular = path.join(workspaceDir, 'linked-settings.json');
        fs.symlinkSync(regularFile, symlinkToRegular);

        const symlinkToDevZero = path.join(workspaceDir, 'zero-settings.json');
        fs.symlinkSync('/dev/zero', symlinkToDevZero);

        const fifoPath = path.join(workspaceDir, 'pipe-settings.json');
        execFileSync('mkfifo', [fifoPath]);

        const subDir = path.join(workspaceDir, 'subdir');
        fs.mkdirSync(subDir);

        const largeFile = path.join(workspaceDir, 'large.bin');
        const largeSize = 24 * 1024 * 1024;
        fs.writeFileSync(largeFile, '');
        fs.truncateSync(largeFile, largeSize);

        const pendingRequests = new Set();
        const requestErrors = [];
        const responses = new Map();
        const server = http.createServer((req, res) => {
            const parsed = new URL(req.url, 'http://127.0.0.1');
            const filePath = parsed.searchParams.get('path') || '';
            responses.set(filePath, res);
            const headers = { 'X-Resource': 'preserved' };
            if (req.headers['x-invalid-header']) headers['Invalid\nHeader'] = 'invalid';
            const request = serveFile(filePath, CacheControl[req.headers['x-cache-control']] ?? CacheControl.ETAG,
                logService, req, res, headers);
            pendingRequests.add(request);
            request.then(() => pendingRequests.delete(request), error => {
                pendingRequests.delete(request);
                requestErrors.push(error);
            });
        });
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        const port = server.address().port;
        const drainRequests = async () => {
            let timer;
            try {
                await Promise.race([
                    Promise.all([...pendingRequests]),
                    new Promise((_, reject) => {
                        timer = setTimeout(() => reject(new Error('file requests must settle after completion or disconnect')), 1000);
                    }),
                ]);
                for (const file of openedFiles) assert.equal(file.fd, -1, 'every completed request must close its descriptor');
                assert.deepEqual(requestErrors, [], 'file requests must handle errors without rejecting');
            } finally {
                clearTimeout(timer);
            }
        };

        try {
            for (const replacement of ['/dev/zero', fifoPath, emptyFile]) {
                const raceFile = path.join(workspaceDir, 'replaced-settings.json');
                fs.rmSync(raceFile, { force: true });
                fs.writeFileSync(raceFile, regularBody);
                const stat = fs.statSync(raceFile);
                afterStat = filePath => {
                    if (filePath === raceFile) {
                        fs.unlinkSync(raceFile);
                        fs.symlinkSync(replacement, raceFile);
                    }
                };
                const raceRes = await fetchBounded(port, raceFile, 1000, 64 * 1024);
                assert.equal(raceRes.completed, true, 'replacing a validated path must not start an unbounded device stream');
                assert.equal(raceRes.error, null);
                assert.equal(raceRes.status, 200);
                assert.equal(raceRes.body, regularBody, 'the response must read the regular file that was validated');
                assert.equal(raceRes.headers.etag, `W/"${stat.ino}-${stat.size}-${stat.mtime.getTime()}"`);
                await drainRequests();
            }
            afterStat = () => {};

            for (const replacement of ['/dev/zero', fifoPath]) {
                const raceFile = path.join(workspaceDir, 'before-open-settings.json');
                fs.rmSync(raceFile, { force: true });
                fs.writeFileSync(raceFile, regularBody);
                beforeOpen = filePath => {
                    if (filePath === raceFile) {
                        fs.unlinkSync(raceFile);
                        fs.symlinkSync(replacement, raceFile);
                    }
                };
                const raceRes = await fetchBounded(port, raceFile, 1000, 64 * 1024);
                assert.equal(raceRes.completed, true, 'a special file present at open must receive a bounded response');
                assert.equal(raceRes.error, null);
                assert.equal(raceRes.status, 404);
                await drainRequests();
            }
            beforeOpen = () => {};

            const regularRes = await fetchBounded(port, regularFile);
            assert.equal(regularRes.completed, true);
            assert.equal(regularRes.error, null);
            assert.equal(regularRes.status, 200, 'regular file must answer 200');
            assert.equal(regularRes.body, regularBody);
            assert.equal(regularRes.headers['content-type'], 'application/json');
            assert.equal(regularRes.headers['x-resource'], 'preserved');

            const cachedRes = await fetchBounded(port, regularFile, 1000, 4096,
                { headers: { 'If-None-Match': regularRes.headers.etag } });
            assert.equal(cachedRes.completed, true);
            assert.equal(cachedRes.status, 304);
            assert.equal(cachedRes.bytes, 0);
            assert.equal(cachedRes.headers.etag, regularRes.headers.etag);
            assert.equal(cachedRes.headers['x-resource'], 'preserved');
            await drainRequests();

            for (const [cache, expected] of [['NO_CACHING', 'no-store'], ['NO_EXPIRY', 'public, max-age=31536000']]) {
                const cacheRes = await fetchBounded(port, regularFile, 1000, 4096,
                    { headers: { 'X-Cache-Control': cache } });
                assert.equal(cacheRes.status, 200);
                assert.equal(cacheRes.body, regularBody);
                assert.equal(cacheRes.headers['cache-control'], expected);
                assert.equal(cacheRes.headers.etag, undefined);
            }

            const rangeRes = await fetchBounded(port, regularFile, 1000, 4096,
                { headers: { Range: 'bytes=0-1' } });
            assert.equal(rangeRes.status, 200, 'the endpoint must retain its full-file response to Range requests');
            assert.equal(rangeRes.body, regularBody);

            const emptyRes = await fetchBounded(port, emptyFile);
            assert.equal(emptyRes.completed, true);
            assert.equal(emptyRes.error, null);
            assert.equal(emptyRes.status, 200, 'empty regular file must answer 200');
            assert.equal(emptyRes.bytes, 0);
            assert.equal(emptyRes.body, '');

            const symlinkRegularRes = await fetchBounded(port, symlinkToRegular);
            assert.equal(symlinkRegularRes.completed, true);
            assert.equal(symlinkRegularRes.error, null);
            assert.equal(symlinkRegularRes.status, 200, 'symlink to a regular file must answer 200');
            assert.equal(symlinkRegularRes.body, regularBody);

            const devZeroRes = await fetchBounded(port, symlinkToDevZero, 1000, 64 * 1024);
            assert.equal(devZeroRes.completed, true, 'symlink to /dev/zero must complete within bound');
            assert.equal(devZeroRes.error, null);
            assert.notEqual(devZeroRes.status, 200, 'symlink to /dev/zero must not answer 200');
            assert.equal(devZeroRes.status, 404);
            assert(devZeroRes.bytes <= 64, `expected small error body for /dev/zero, got ${devZeroRes.bytes} bytes`);

            const fifoResponses = await Promise.all([
                fetchBounded(port, fifoPath, 1000, 64 * 1024),
                fetchBounded(port, fifoPath, 1000, 64 * 1024),
                fetchBounded(port, fifoPath, 1000, 64 * 1024),
                fetchBounded(port, fifoPath, 1000, 64 * 1024),
            ]);
            for (const fifoRes of fifoResponses) {
                assert.equal(fifoRes.completed, true, 'FIFO request must complete within bound without blocking in open()');
                assert.equal(fifoRes.error, null);
                assert.notEqual(fifoRes.status, 200, 'FIFO must not answer 200');
                assert.equal(fifoRes.status, 404);
            }

            const afterFifoRes = await fetchBounded(port, regularFile, 1000);
            assert.equal(afterFifoRes.completed, true, 'request after four FIFO requests must be answered');
            assert.equal(afterFifoRes.error, null);
            assert.equal(afterFifoRes.status, 200);
            assert.equal(afterFifoRes.body, regularBody);

            const dirRes = await fetchBounded(port, subDir, 1000);
            assert.equal(dirRes.completed, true, 'directory request must receive a status response, not a connection reset');
            assert.equal(dirRes.error, null);
            assert.equal(dirRes.status, 404);

            const missingRes = await fetchBounded(port, path.join(workspaceDir, 'missing.json'));
            assert.equal(missingRes.completed, true);
            assert.equal(missingRes.status, 404);
            assert.equal(missingRes.body, 'Not found');

            afterStat = filePath => {
                if (filePath === regularFile) throw Object.assign(new Error('Unable to read file metadata'), { code: 'EIO' });
            };
            const failedStatRes = await fetchBounded(port, regularFile);
            assert.equal(failedStatRes.completed, true);
            assert.equal(failedStatRes.status, 404);
            await drainRequests();
            afterStat = () => {};

            openFlags = fs.constants.O_WRONLY;
            const failedReadRes = await fetchBounded(port, regularFile);
            assert.equal(failedReadRes.completed, false, 'a read error must terminate the response');
            assert.notEqual(failedReadRes.error, 'TIMEOUT');
            await drainRequests();
            openFlags = undefined;

            const invalidHeaderRes = await fetchBounded(port, regularFile, 1000, 4096,
                { headers: { 'X-Invalid-Header': 'true' } });
            assert.equal(invalidHeaderRes.completed, true);
            assert.equal(invalidHeaderRes.status, 404);
            await drainRequests();

            let abortClient;
            for (const failStat of [false, true]) {
                afterStat = async filePath => {
                    if (filePath === regularFile) {
                        const closed = once(responses.get(filePath), 'close');
                        abortClient.destroy();
                        await closed;
                        if (failStat) throw Object.assign(new Error('Unable to read file metadata'), { code: 'EIO' });
                    }
                };
                const beforeStreamRes = await fetchBounded(port, regularFile, 1000, 4096,
                    { onRequest: req => { abortClient = req; } });
                assert.equal(beforeStreamRes.completed, false);
                await drainRequests();
                assert.equal(responses.get(regularFile).headersSent, false, 'a disconnected response must not receive headers');
            }
            afterStat = () => {};

            const abortedRes = await fetchBounded(port, largeFile, 5000, largeSize + 1024,
                { abortAfterBytes: 64 * 1024 });
            assert.equal(abortedRes.completed, false);
            assert(abortedRes.bytes < largeSize, 'the client must disconnect before the large response completes');
            await drainRequests();

            const largeRes = await fetchBounded(port, largeFile, 5000, largeSize + 1024);
            assert.equal(largeRes.completed, true, 'large regular file must be served whole');
            assert.equal(largeRes.error, null);
            assert.equal(largeRes.status, 200);
            assert.equal(largeRes.bytes, largeSize);
            await drainRequests();
        } finally {
            await new Promise(resolve => server.close(resolve));
        }
        console.log('ok - serveFile preserves file identity, caching and bounded streaming and closes descriptors on every response path');
    } finally {
        fs.rmSync(temporary, { recursive: true, force: true });
    }
})();

if (require.main === module) module.exports.catch(error => { console.error(error); process.exitCode = 1; });
