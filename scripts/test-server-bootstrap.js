/**
 * Self-check for the product.json rewrite in the server bootstrap.
 *
 * server.js rewrites product.json on every start, and the platform treats
 * SIGKILL on this process as routine -- the watchdog in ProcessManager exists
 * for it. A kill landing inside that write leaves truncated JSON behind, and
 * the next start used to throw an uncaught SyntaxError before anything was
 * logged, so the watchdog restarted straight into the identical crash.
 *
 * The bootstrap resolves everything from its own __filename, so the fixture
 * holds a copy of the shipped file rather than a symlink -- Node resolves a
 * symlinked entry point back to its target and the copy would defeat itself.
 * The bytes are copied at test time, so what runs is what ships.
 *
 * dns-proxy.js and process-monitor.js are deliberately left out of the
 * fixture: server.js treats both as optional, and without them nothing here
 * binds a port or reads /proc.
 *
 *   node scripts/test-server-bootstrap.js
 */

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const SERVER_JS = path.resolve(__dirname, '../android/app/src/main/assets/server.js');

/** A fixture the bootstrap will accept: a server entry point and a product.json. */
function fixture(productJson) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vscodroid-bootstrap-'));
    fs.mkdirSync(path.join(dir, 'vscode-reh', 'out'), { recursive: true });
    fs.copyFileSync(SERVER_JS, path.join(dir, 'server.js'));
    fs.writeFileSync(path.join(dir, 'vscode-reh', 'out', 'server-main.js'), 'process.exit(0);\n');
    fs.writeFileSync(path.join(dir, 'vscode-reh', 'product.json'), productJson);
    return dir;
}

function boot(dir) {
    const result = spawnSync(process.execPath, [path.join(dir, 'server.js'), '--host=127.0.0.1'], {
        encoding: 'utf8',
        timeout: 20_000,
    });
    return { ...result, output: `${result.stdout || ''}${result.stderr || ''}` };
}

const UPSTREAM = JSON.stringify({ nameShort: 'Code - OSS', version: '1.133.0', quality: 'oss' }, null, 2);

// 1. A valid file is rewritten with the overrides, and nothing is left beside it.
{
    const dir = fixture(UPSTREAM);
    const run = boot(dir);
    assert.strictEqual(run.status, 0, `a valid product.json should boot cleanly:\n${run.output}`);

    const product = JSON.parse(fs.readFileSync(path.join(dir, 'vscode-reh', 'product.json'), 'utf8'));
    assert.strictEqual(product.nameShort, 'VSCodroid', 'the overrides were not applied');
    assert.strictEqual(product.version, '1.133.0', 'an upstream key was lost');
    assert.strictEqual(
        product.extensionsGallery.serviceUrl,
        'https://open-vsx.org/vscode/gallery',
        'the Open VSX gallery was not written',
    );

    const strays = fs.readdirSync(path.join(dir, 'vscode-reh')).filter((n) => n !== 'product.json' && n !== 'out');
    assert.deepStrictEqual(strays, [], `the rewrite left files behind: ${strays.join(', ')}`);
    fs.rmSync(dir, { recursive: true, force: true });
}

// 2. A file truncated by a kill mid-write must be named, not thrown. The
//    watchdog restarts this process, so an uncaught throw here is a crash loop
//    rather than an error.
{
    const dir = fixture('{\n  "nameShort": "Code - OS');
    const run = boot(dir);
    assert.ok(
        !/SyntaxError/.test(run.output),
        `a truncated product.json still throws an uncaught SyntaxError:\n${run.output}`,
    );
    assert.ok(
        /product\.json/.test(run.output),
        `nothing in the output names the file that is broken:\n${run.output}`,
    );
    assert.strictEqual(run.status, 0, `the bootstrap should carry on and let the server report:\n${run.output}`);
    fs.rmSync(dir, { recursive: true, force: true });
}

// 3. A write that cannot complete must leave the existing file intact. That is
//    the property the temp-file-then-rename buys, and the one a partial
//    writeFileSync did not have.
if (process.getuid && process.getuid() !== 0) {
    const dir = fixture(UPSTREAM);
    const rehDir = path.join(dir, 'vscode-reh');
    fs.chmodSync(rehDir, 0o500);
    let run;
    try {
        run = boot(dir);
    } finally {
        fs.chmodSync(rehDir, 0o700);
    }
    const after = fs.readFileSync(path.join(rehDir, 'product.json'), 'utf8');
    assert.strictEqual(after, UPSTREAM, `an unwritable directory damaged product.json:\n${run.output}`);
    assert.ok(!/Error: EACCES/.test(run.output), `the failed write was not handled:\n${run.output}`);
    fs.rmSync(dir, { recursive: true, force: true });
} else {
    console.log('note -- skipping the unwritable-directory case; root ignores the mode');
}

console.log('ok -- product.json survives a truncated file and an unwritable directory');
