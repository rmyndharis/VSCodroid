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
 * process-monitor.js is deliberately left out of the fixture: server.js treats
 * it as optional, and without it nothing here reads /proc. dns-proxy.js is
 * copied in only for the cases about the proxy, which are the ones that need a
 * port bound.
 *
 *   node scripts/test-server-bootstrap.js
 */

const assert = require('assert');
const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');
const { URL } = require('url');
const { spawn, spawnSync } = require('child_process');

const ASSETS = path.resolve(__dirname, '../android/app/src/main/assets');
const SERVER_JS = path.join(ASSETS, 'server.js');
const DNS_PROXY_JS = path.join(ASSETS, 'dns-proxy.js');
const PROCESS_MANAGER = path.resolve(
    __dirname, '../android/app/src/main/kotlin/com/vscodroid/service/ProcessManager.kt',
);

/**
 * A fixture the bootstrap will accept: a server entry point and a product.json.
 *
 * `serverMain` replaces the entry point's body, and `null` leaves it out
 * altogether. `dnsProxy` copies the real proxy in beside the bootstrap, which is
 * what makes the preload reach it; a string writes that text as the proxy
 * instead, for the cases about what the bootstrap does with the file rather than
 * about what the proxy does once loaded.
 */
function fixture(productJson, { serverMain = 'process.exit(0);\n', dnsProxy = false } = {}) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vscodroid-bootstrap-'));
    fs.mkdirSync(path.join(dir, 'vscode-reh', 'out'), { recursive: true });
    fs.copyFileSync(SERVER_JS, path.join(dir, 'server.js'));
    if (typeof dnsProxy === 'string') {
        fs.writeFileSync(path.join(dir, 'dns-proxy.js'), dnsProxy);
    } else if (dnsProxy) {
        fs.copyFileSync(DNS_PROXY_JS, path.join(dir, 'dns-proxy.js'));
    }
    if (serverMain !== null) {
        fs.writeFileSync(path.join(dir, 'vscode-reh', 'out', 'server-main.js'), serverMain);
    }
    fs.writeFileSync(path.join(dir, 'vscode-reh', 'product.json'), productJson);
    return dir;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Waits for a condition, and names it rather than timing out anonymously. */
async function until(predicate, what, timeoutMs = 15_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (predicate()) return;
        await sleep(25);
    }
    assert.fail(`timed out after ${timeoutMs} ms waiting for ${what}`);
}

/** Signal 0 asks the kernel whether a pid exists without disturbing it. */
function alive(pid) {
    try {
        process.kill(pid, 0);
        return true;
    } catch {
        return false;
    }
}

/** The status an unauthenticated request through the proxy comes back with. */
function proxyAnswers(port) {
    return new Promise((resolve) => {
        const req = http.request(
            { host: '127.0.0.1', port, path: 'http://127.0.0.1:1/', agent: false },
            (res) => {
                res.resume();
                resolve(res.statusCode);
            },
        );
        req.on('error', () => resolve(null));
        req.end();
    });
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
    // Every field, not just the one that is obviously load-bearing. The rewrite
    // is a shallow Object.assign, so this nested object replaces whatever was
    // built rather than merging into it: a field dropped from the literal in
    // server.js becomes undefined in the shipped product.json, silently, and
    // controlUrl and nlsBaseUrl appear nowhere else in the repository to say so.
    assert.deepStrictEqual(
        Object.keys(product.extensionsGallery).sort(),
        ['controlUrl', 'itemUrl', 'nlsBaseUrl', 'resourceUrlTemplate', 'serviceUrl'],
        'the gallery configuration does not carry the fields it did; a shallow assign replaces ' +
            'the whole object, so anything missing here is missing from the shipped product.json',
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

// 4. A missing server entry point ends the bootstrap rather than standing in for
//    it. What stood in was a minimal HTTP server answering 200 to every path,
//    `/version` included, which is exactly what ProcessManager.probeVersion
//    accepts as readiness: a broken install reported a healthy start and the app
//    navigated the WebView to a page telling whoever held the phone to run two
//    shell scripts.
{
    const dir = fixture(UPSTREAM, { serverMain: null });
    const run = boot(dir);
    assert.notStrictEqual(
        run.status, 0,
        `a bootstrap with no server to launch exited cleanly:\n${run.output}`,
    );
    assert.match(
        run.output,
        /server-main\.js/,
        `nothing in the output names the entry point that is missing:\n${run.output}`,
    );
    assert.ok(
        !/<html>|<!DOCTYPE/i.test(run.output),
        `the bootstrap still serves a page in place of the server:\n${run.output}`,
    );
    fs.rmSync(dir, { recursive: true, force: true });
}

// 5. A dns-proxy.js that does not load costs musl clients their DNS and nothing
//    else. Preloading is the one thing the bootstrap asks of that file that can
//    take the whole editor server with it: a module named by `--require` that
//    throws while it is evaluated stops the process loading its main script at
//    all, so a truncated copy would leave the app with no server and a watchdog
//    restarting straight into the same failure. Checking the path exists does not
//    answer this; only loading it does.
{
    const dir = fixture(UPSTREAM, {
        serverMain: "require('fs').writeFileSync(require('path').join(__dirname, 'ran'), '1');\n",
        dnsProxy: 'this file was truncated mid-write(\n',
    });
    const run = boot(dir);
    assert.ok(
        fs.existsSync(path.join(dir, 'vscode-reh', 'out', 'ran')),
        `a dns-proxy.js that does not parse stopped the editor server starting:\n${run.output}`,
    );
    assert.strictEqual(run.status, 0, `the bootstrap should carry on without the proxy:\n${run.output}`);
    assert.match(
        run.output,
        /dns-proxy/,
        `nothing in the output names the proxy that could not be loaded:\n${run.output}`,
    );
    fs.rmSync(dir, { recursive: true, force: true });
}

/**
 * The preload rides as one token, because its shape decides what the process
 * monitor calls the editor server.
 *
 * `scriptArgument` in process-monitor.js names a process by the first argument
 * that is not an option, which is how the details view and the status bar
 * tooltip stopped reading `libnode.so --max-old-space-size=488` for five rows out
 * of six. `--require` followed by the path puts that path in exactly that
 * position, so the editor server's row reads `libnode.so dns-proxy.js` and the
 * one process a reader is looking for is named after a module it preloads.
 *
 * Asserted against a stand-in proxy rather than the real one: the real file's
 * first act is to take this option back out of its own process, so by the time
 * anything it exports can be asked, the evidence is gone. What is being measured
 * here is what the bootstrap emits.
 */
async function preloadRidesAsOneToken() {
    const dnsProxy = [
        '// Only the child records. This file is loaded in the bootstrap too, to',
        '// prove it parses, and that copy sees no preload option at all.',
        "if (process.env.VSCODROID_DNS_PROXY === '1') {",
        "    require('fs').writeFileSync(",
        "        require('path').join(__dirname, 'preload.json'),",
        '        JSON.stringify(process.execArgv),',
        '    );',
        '}',
        'module.exports = { start: () => Promise.resolve({}) };',
        '',
    ].join('\n');

    const dir = fixture(UPSTREAM, { serverMain: 'setInterval(() => {}, 1000);\n', dnsProxy });
    const preloadPath = path.join(dir, 'preload.json');
    const pidFile = path.join(dir, 'editor-server.pid');
    const bootstrap = spawn(process.execPath, [path.join(dir, 'server.js'), '--host=127.0.0.1'], {
        stdio: 'ignore',
    });
    let childPid = 0;
    try {
        await until(() => fs.existsSync(preloadPath), 'the editor server to report its preload');
        if (fs.existsSync(pidFile)) childPid = JSON.parse(fs.readFileSync(pidFile, 'utf8')).pid;
        const execArgv = JSON.parse(fs.readFileSync(preloadPath, 'utf8'));

        const proxyArgs = execArgv.filter((arg) => arg.includes('dns-proxy.js'));
        assert.strictEqual(
            proxyArgs.length, 1,
            `the editor server was given ${proxyArgs.length} preload arguments naming the proxy, ` +
                `so nothing below is measuring the one: ${JSON.stringify(execArgv)}`,
        );
        assert.ok(
            proxyArgs[0].startsWith('--require='),
            'the proxy path is a preload argument of its own, so it is the first non-option ' +
                'argument on the editor server\'s command line and the process monitor names ' +
                `that row after the proxy rather than after server-main.js: ${proxyArgs[0]}`,
        );
        assert.ok(
            !execArgv.includes('--require'),
            `a bare --require survives beside the joined form: ${JSON.stringify(execArgv)}`,
        );
    } finally {
        if (childPid) { try { process.kill(childPid, 'SIGKILL'); } catch { /* already gone */ } }
        bootstrap.kill('SIGKILL');
        fs.rmSync(dir, { recursive: true, force: true });
    }
}

/**
 * The proxy outlives the bootstrap, because it is not in the bootstrap.
 *
 * dns-proxy.js binds a port and mints a token per boot, and the editor server
 * gets that address once, in its environment, which nothing can change while it
 * runs. This process is SIGKILLed as a matter of routine -- the OOM killer and
 * Android's phantom-process limit both do it -- and the forked server survives
 * holding the port, which is the survivor ProcessManager adopts on the next
 * launch rather than losing the user's session. With the listener in the
 * bootstrap, that adopted server spent its whole session pointing at a closed
 * port: the Open VSX gallery, extension installs, the agent host and git, npm and
 * curl in every terminal all failed to reach the network, while the workbench
 * looked healthy because it is reached by address through NO_PROXY.
 *
 * So the bootstrap is killed the way Android kills it and the proxy is asked
 * whether it is still there. 407 rather than merely accepting a connection: it
 * proves the answer came from this proxy and not from something else that has
 * since taken the port.
 */
async function proxySurvivesTheBootstrap() {
    const serverMain = [
        "const fs = require('fs');",
        "const path = require('path');",
        '// The proxy binds on the event loop, so the environment it sets is',
        '// readable a tick after this module is loaded rather than during it.',
        'setTimeout(() => {',
        "    fs.writeFileSync(path.join(__dirname, 'report.json'), JSON.stringify({",
        "        proxy: process.env.HTTPS_PROXY || '',",
        '        execArgv: process.execArgv,',
        '    }));',
        '}, 300);',
        '// Outlives its parent, which is the whole point of the case.',
        'setInterval(() => {}, 1000);',
        '',
    ].join('\n');

    const dir = fixture(UPSTREAM, { serverMain, dnsProxy: true });
    const reportPath = path.join(dir, 'vscode-reh', 'out', 'report.json');
    const pidFile = path.join(dir, 'editor-server.pid');
    const bootstrap = spawn(process.execPath, [path.join(dir, 'server.js'), '--host=127.0.0.1'], {
        stdio: 'ignore',
    });
    let childPid = 0;
    try {
        // Parsed inside the wait rather than after it: the report is written
        // without a rename, so a read that lands mid-write is a parse error and
        // not a case worth failing on.
        let report = null;
        await until(() => {
            try {
                report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
                return true;
            } catch {
                return false;
            }
        }, 'the editor server to report its environment');
        assert.match(
            report.proxy,
            /^http:\/\/vscodroid:[0-9a-f]+@127\.0\.0\.1:\d+$/,
            `the editor server was given no usable proxy address: ${JSON.stringify(report.proxy)}`,
        );
        // The option that brought the proxy into this process is taken back out
        // of it. fork() passes execArgv on by default and the editor server hands
        // it to `new Worker` too, so anything left here is preloaded into the file
        // watcher, the agent host, the extension host and the pty host as well.
        assert.deepStrictEqual(
            report.execArgv.filter((arg) => arg.includes('dns-proxy') || arg === '--require'),
            [],
            'the editor server still carries the preload option, so every helper it forks ' +
                `loads the proxy module too: ${JSON.stringify(report.execArgv)}`,
        );

        const proxyPort = Number(new URL(report.proxy).port);
        assert.strictEqual(
            await proxyAnswers(proxyPort), 407,
            'the proxy did not answer while everything was still running, so nothing below is ' +
                'being measured',
        );

        childPid = JSON.parse(fs.readFileSync(pidFile, 'utf8')).pid;
        bootstrap.kill('SIGKILL');
        await until(() => !alive(bootstrap.pid), 'the bootstrap to go');
        assert.ok(alive(childPid), 'the editor server did not outlive its bootstrap, so the ' +
            'adoption case this is about was never reached');

        assert.strictEqual(
            await proxyAnswers(proxyPort), 407,
            'the address in the surviving editor server\'s HTTPS_PROXY stopped answering when ' +
                'the bootstrap was killed, so every request that honours it fails for the whole ' +
                'of the session the next launch adopts',
        );
    } finally {
        if (childPid) { try { process.kill(childPid, 'SIGKILL'); } catch { /* already gone */ } }
        bootstrap.kill('SIGKILL');
        fs.rmSync(dir, { recursive: true, force: true });
    }
}

/**
 * Stopping the bootstrap stops the editor server it forked.
 *
 * ProcessManager sends SIGTERM, waits GRACEFUL_STOP_TIMEOUT_MS and then
 * force-kills the bootstrap alone -- destroyForcibly signals one pid, and fork()
 * sets no PDEATHSIG -- so a child still unwinding after that second was left
 * running with no service, no notification and no lever, after a Stop that
 * reported success. The escalation has to happen inside that second or it never
 * happens, which is what the delay is checked against here.
 */
async function stoppingTakesTheEditorServerWithIt() {
    const serverMain = [
        "const fs = require('fs');",
        "const path = require('path');",
        '// Traps the signal and keeps running, which is the case that produced',
        '// the orphan: a loaded device, many extensions, a pty host mid-write.',
        "process.on('SIGTERM', () => {});",
        'setInterval(() => {}, 1000);',
        '// Announced only once the handler is installed. The pid note is written',
        '// by the bootstrap at fork time, so waiting on that alone signals a',
        '// process that may not have run a line yet -- and a SIGTERM arriving',
        '// then is answered by the default handler, which ends it whatever this',
        '// file says.',
        "fs.writeFileSync(path.join(__dirname, 'trapping'), '1');",
        '',
    ].join('\n');

    const grace = /GRACEFUL_STOP_TIMEOUT_MS = ([0-9_]+)L/.exec(
        fs.readFileSync(PROCESS_MANAGER, 'utf8'),
    );
    assert.ok(grace, 'GRACEFUL_STOP_TIMEOUT_MS was not found in ProcessManager.kt, so the delay ' +
        'below is being compared against nothing. Find what it is called now and fix the pattern.');
    const escalation = /CHILD_KILL_AFTER_SIGTERM_MS = (\d+)/.exec(fs.readFileSync(SERVER_JS, 'utf8'));
    assert.ok(escalation, 'CHILD_KILL_AFTER_SIGTERM_MS was not found in server.js');
    assert.ok(
        Number(escalation[1]) < Number(grace[1].replace(/_/g, '')),
        `the bootstrap waits ${escalation[1]} ms before force-killing the editor server, and ` +
            `ProcessManager force-kills the bootstrap after ${grace[1]} ms. The escalation never ` +
            'runs, and a slow editor server is orphaned exactly as it was before.',
    );

    const dir = fixture(UPSTREAM, { serverMain });
    const pidFile = path.join(dir, 'editor-server.pid');
    const trapping = path.join(dir, 'vscode-reh', 'out', 'trapping');
    const bootstrap = spawn(process.execPath, [path.join(dir, 'server.js'), '--host=127.0.0.1'], {
        stdio: 'ignore',
    });
    let childPid = 0;
    try {
        await until(() => fs.existsSync(pidFile), 'the bootstrap to record the editor server pid');
        childPid = JSON.parse(fs.readFileSync(pidFile, 'utf8')).pid;
        await until(() => fs.existsSync(trapping), 'the editor server to install its SIGTERM trap');

        bootstrap.kill('SIGTERM');
        await until(
            () => !alive(childPid),
            'the editor server to be ended by the bootstrap that forked it',
            5_000,
        );
        await until(() => !alive(bootstrap.pid), 'the bootstrap to exit once its child had gone');
    } finally {
        if (childPid) { try { process.kill(childPid, 'SIGKILL'); } catch { /* already gone */ } }
        bootstrap.kill('SIGKILL');
        fs.rmSync(dir, { recursive: true, force: true });
    }
}

preloadRidesAsOneToken()
    .then(proxySurvivesTheBootstrap)
    .then(stoppingTakesTheEditorServerWithIt)
    .then(() => {
        console.log(
            'ok -- product.json survives a truncated file and an unwritable directory, a missing ' +
                'server tree is a failed start rather than a healthy one, a proxy that does not ' +
                'parse costs only DNS, the preload rides as one token, the DNS proxy outlives ' +
                'the bootstrap, and a stop takes the editor server with it',
        );
    })
    .catch((err) => {
        console.error(err);
        process.exit(1);
    });
