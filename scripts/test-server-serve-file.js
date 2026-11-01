'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { execFileSync } = require('node:child_process');
const { stripTypeScriptTypes } = require('node:module');

if (typeof stripTypeScriptTypes !== 'function') {
    console.error(`${path.basename(__filename)}: needs node:module.stripTypeScriptTypes, ` +
        `which Node 22.13+ or 23.2+ has (this is ${process.version}). CI runs Node 24.`);
    process.exit(1);
}

function fetchBounded(port, targetPath, timeoutMs = 1500, maxBytes = 64 * 1024 * 1024) {
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
            headers: { Connection: 'close' },
        }, res => {
            let totalBytes = 0;
            const smallChunks = [];
            res.on('data', chunk => {
                totalBytes += chunk.length;
                if (totalBytes <= 4096) smallChunks.push(chunk);
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
        const context = {
            createReadStream: fs.createReadStream,
            promises: fs.promises,
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

        const server = http.createServer((req, res) => {
            const parsed = new URL(req.url, 'http://127.0.0.1');
            const filePath = parsed.searchParams.get('path') || '';
            serveFile(filePath, CacheControl.ETAG, logService, req, res, Object.create(null));
        });
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        const port = server.address().port;

        try {
            const regularRes = await fetchBounded(port, regularFile);
            assert.equal(regularRes.completed, true);
            assert.equal(regularRes.error, null);
            assert.equal(regularRes.status, 200, 'regular file must answer 200');
            assert.equal(regularRes.body, regularBody);

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

            const largeRes = await fetchBounded(port, largeFile, 5000, largeSize + 1024);
            assert.equal(largeRes.completed, true, 'large regular file must be served whole');
            assert.equal(largeRes.error, null);
            assert.equal(largeRes.status, 200);
            assert.equal(largeRes.bytes, largeSize);
        } finally {
            await new Promise(resolve => server.close(resolve));
        }
        console.log('ok - serveFile serves regular, empty, linked and large files and refuses devices, pipes and directories');
    } finally {
        fs.rmSync(temporary, { recursive: true, force: true });
    }
})();

if (require.main === module) module.exports.catch(error => { console.error(error); process.exitCode = 1; });
