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
const PLATFORM_FIX_JS = path.join(ASSETS, 'platform-fix.js');
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
    // Always beside the bootstrap, as in the app, where NODE_OPTIONS preloads it
    // and server.js hands it on to the editor server.
    fs.copyFileSync(PLATFORM_FIX_JS, path.join(dir, 'platform-fix.js'));
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

// The formatter a marketplace search cannot surface; see EXTENSION_RECOMMENDATIONS.
const BLACK_FORMATTER = 'ms-python.black-formatter';

// The script that starts the page on the last theme; see INITIAL_THEME_MARKER.
const INITIAL_THEME_MARKER = 'vscodroid-initial-theme';

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
        // The extension host inherits this execArgv; NODE_OPTIONS is deleted
        // from its environment, so this is the only way platform-fix.js reaches it.
        const fixArgs = execArgv.filter((arg) => arg.includes('platform-fix.js'));
        assert.strictEqual(
            fixArgs.length === 1 && fixArgs[0].startsWith('--require=') && fixArgs[0].endsWith('/platform-fix.js'), true,
            `the editor server was not given platform-fix.js as one preload token: ${JSON.stringify(execArgv)}`,
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

// The workbench page is given the trusted-domain list, exactly once, and a page
// that does not carry the element it extends is reported rather than thrown.
//
// The page is where this has to land. product.json beside it is read by the
// bootstrap's own process and never by the browser: the product the workbench
// consults is inlined into its bundle at build time, so the list that decides
// whether a link opens without a confirmation reaches the editor only through the
// construction options in this page.
{
    const pageDir = ['vscode-reh', 'out', 'vs', 'code', 'browser', 'workbench'];
    const anchor =
        '<meta id="vscode-workbench-web-configuration" data-settings="{{WORKBENCH_WEB_CONFIGURATION}}">';
    const page = (head) => [
        '<!DOCTYPE html>', '<html>', '\t<head>', `\t\t${head}`, '\t</head>', '</html>', '',
    ].join('\n');

    const dir = fixture(UPSTREAM);
    const pagePath = path.join(dir, ...pageDir, 'workbench.html');
    fs.mkdirSync(path.dirname(pagePath), { recursive: true });
    fs.writeFileSync(pagePath, page(anchor));

    const run = boot(dir);
    assert.strictEqual(run.status, 0, `a tree carrying a workbench page should boot cleanly:\n${run.output}`);

    const once = fs.readFileSync(pagePath, 'utf8');
    assert.ok(once.includes('additionalTrustedDomains'), 'the page was not given a trusted-domain list');
    assert.ok(once.includes('https://github.com'), 'github.com did not reach the page');
    // A BARE <script>. The server hashes exactly that shape out of the page it has
    // just built and puts the hashes in the CSP it serves with it, so a tag that
    // carries any attribute is a script the page's own policy then refuses to run.
    assert.ok(/\n\t\t<script>\n/.test(once), 'the injected script is not the bare form the CSP hashing matches');
    // And it has to parse. The script is assembled from string fragments in
    // server.js, where nothing else would notice a missing bracket until a device
    // silently stopped applying the list.
    const bodies = [...once.matchAll(/<script>\n([\s\S]*?)\n\t\t<\/script>/g)].map((m) => m[1]);
    assert.strictEqual(bodies.length, 3, `expected the three injected scripts, got ${bodies.length}`);
    bodies.forEach((b) => new Function(b)); // eslint-disable-line no-new-func -- a parse check

    // The page is also given the extension recommendations, and they have to land
    // UNDER productConfiguration: the workbench deep merges that object into the
    // product inlined in its bundle, and a recommendation written anywhere else in
    // the settings is read by nothing.
    assert.ok(once.includes(BLACK_FORMATTER), `${BLACK_FORMATTER} did not reach the page`);

    // Run both scripts the way the page would, rather than trusting the text. A
    // recommendation that parses but writes to the wrong key would pass a string
    // check and reach a device suggesting nothing. The starting theme reads the
    // page's storage and has a case of its own below.
    {
        const settings = { additionalTrustedDomains: ['https://example.invalid'] };
        const el = {
            getAttribute: () => JSON.stringify(settings),
            setAttribute: (_name, value) => Object.assign(settings, JSON.parse(value)),
        };
        const document = { getElementById: (id) => (id === 'vscode-workbench-web-configuration' ? el : null) };
        bodies
            .filter((b) => !b.includes(INITIAL_THEME_MARKER))
            .forEach((b) => new Function('document', b)(document)); // eslint-disable-line no-new-func

        assert.ok(
            settings.additionalTrustedDomains.includes('https://example.invalid'),
            'the trusted-domain script dropped a domain the page already carried',
        );
        assert.ok(
            settings.additionalTrustedDomains.includes('https://github.com'),
            'the trusted-domain script did not add github.com',
        );
        assert.ok(
            settings.additionalTrustedDomains.includes('https://login.microsoftonline.com'),
            'the trusted-domain script did not add login.microsoftonline.com',
        );
        const recommended = settings.productConfiguration?.extensionRecommendations;
        assert.ok(recommended, 'no extensionRecommendations under productConfiguration');
        const entry = recommended[BLACK_FORMATTER];
        assert.ok(entry, `${BLACK_FORMATTER} is not among the recommendations`);
        // The shape the workbench actually reads: it keeps `onFileOpen` and then
        // tests `languages`, so an entry without both is carried and never fires.
        assert.ok(Array.isArray(entry.onFileOpen) && entry.onFileOpen.length, 'the entry carries no onFileOpen');
        assert.ok(
            entry.onFileOpen.every((c) => Array.isArray(c.languages) && c.languages.length),
            'an onFileOpen condition names no language, so the workbench never matches it',
        );
        assert.ok(
            entry.onFileOpen.some((c) => c.languages.includes('python')),
            'nothing recommends the formatter for Python',
        );
    }

    const twice = boot(dir);
    assert.strictEqual(twice.status, 0, `a second start should boot cleanly:\n${twice.output}`);
    assert.strictEqual(
        fs.readFileSync(pagePath, 'utf8'),
        once,
        'a second start stacked another copy of the script into the page',
    );

    const strays = fs.readdirSync(path.dirname(pagePath)).filter((n) => n !== 'workbench.html');
    assert.deepStrictEqual(strays, [], `the rewrite left files behind: ${strays.join(', ')}`);
    fs.rmSync(dir, { recursive: true, force: true });
}

// Every page load starts on the theme it is about to show: a folder with a theme
// of its own on the one it showed last, any other on the one the folder that most
// recently followed the user's theme showed, or else on the splash the workbench
// saved. Without it the page shows the WebView's own background until the
// workbench has loaded, the dark window colour whatever the theme, and a load
// that cannot use the stored theme (the first start, the first load after an
// update renamed the configured default, a folder whose own settings name another
// theme, any folder entered from one) shows the light theme before a dark one, or
// the dark one before a light one.
//
// NEGATIVE CONTROL: without the script in server.js the first assertion fails;
// with `initialColorTheme` handed over after the device switched to light mode,
// that case fails; without the observer of the head, the case of a theme changed
// while the page is open fails; with each folder started on its own record, the
// case of a theme picked in one folder and another opened fails; with only the
// shared splash, or with every record moved along when a window changes theme,
// the cases of a folder with a light theme of its own fail; marking a folder only
// on the first theme the workbench shows fails them too, since its own settings
// arrive later on its first load, and marking it after the user's first tap
// fails the case of a theme picked; telling themes apart by their colours rather
// than their ids fails the cases of a folder that colours its bars and of a theme
// with the user's colours; handing over nothing without any record or
// splash fails the case of a load before any splash; without the bound, or
// without moving a folder shown again to the end, the case of twenty folders
// fails; writing on every change of the head fails the title case; and without
// the catch around the write, the case of full storage fails.
{
    const anchor =
        '<meta id="vscode-workbench-web-configuration" data-settings="{{WORKBENCH_WEB_CONFIGURATION}}">';
    const dir = fixture(UPSTREAM);
    const pagePath = path.join(dir, 'vscode-reh', 'out', 'vs', 'code', 'browser', 'workbench', 'workbench.html');
    fs.mkdirSync(path.dirname(pagePath), { recursive: true });
    fs.writeFileSync(pagePath, `<!DOCTYPE html>\n<html>\n\t<head>\n\t\t${anchor}\n\t</head>\n</html>\n`);
    const run = boot(dir);
    assert.strictEqual(run.status, 0, `a tree carrying a workbench page should boot cleanly:\n${run.output}`);
    const body = [...fs.readFileSync(pagePath, 'utf8').matchAll(/<script>\n([\s\S]*?)\n\t\t<\/script>/g)]
        .map((m) => m[1])
        .find((b) => b.includes(INITIAL_THEME_MARKER));
    assert.ok(body, 'the page was not given a script that starts it on the last theme');

    // What Dark Modern's splash holds, measured on a device, with one colour made
    // translucent: the workbench reads every colour with Color.fromHex, which
    // turns rgba() into red.
    const darkModern = {
        baseTheme: 'vs-dark',
        colorInfo: {
            foreground: '#cccccc', background: '#1f1f1f', editorBackground: '#1f1f1f',
            titleBarBackground: '#181818', activityBarBackground: '#181818', sideBarBackground: '#181818',
            panelBackground: 'rgba(24, 24, 24, 0.5)', statusBarBackground: '#181818',
            statusBarNoFolderBackground: '#1f1f1f', editorGroupBorder: 'rgba(255, 255, 255, 0.09)',
        },
    };

    // A theme as the workbench shows it: its id, which the workbench writes as
    // classes of its element, the base theme first, and its colours in the CSS
    // variables of its stylesheet. A theme the workbench makes up, when it cannot
    // use the one it stored, has the base theme alone for an id.
    const vars = (colors) =>
        Object.fromEntries(Object.entries(colors).map(([id, c]) => [`--vscode-${id.replace(/\./g, '-')}`, c]));
    const shown = (id, colors) => ({ id, base: id.split(' ')[0], vars: vars(colors) });
    const lightModernColors = {
        foreground: '#3b3b3b', 'editor.background': '#ffffff', 'titleBar.activeBackground': '#f8f8f8',
        'activityBar.background': '#f8f8f8', 'sideBar.background': '#f8f8f8', 'panel.background': '#f8f8f8',
        'statusBar.background': '#f8f8f8', 'statusBar.noFolderBackground': '#f8f8f8',
    };
    const lightModern = shown('vs vscode-theme-defaults-themes-light_modern-json', lightModernColors);
    const darkModernShown = shown('vs-dark vscode-theme-defaults-themes-dark_modern-json',
        { 'editor.background': '#1f1f1f', 'statusBar.background': '#181818' });

    /**
     * Runs the script the way the page does, before the workbench reads its
     * settings, on a page whose address carries `search`. Pass the same `items`
     * to successive loads and they share the page's storage, the way loads of
     * one origin do. Answers what the workbench is then handed, what the page
     * paints before anything else, and what is left in storage for the next load.
     */
    const load = ({ splash, last, dark, settings = {}, refuse = false, full = false, items = {}, search = '' }) => {
        if (splash) items['monaco-parts-splash'] = JSON.stringify(splash);
        if (last) items['vscodroid-device-scheme'] = last;
        let writes = 0;
        const localStorage = {
            getItem: (k) => { if (refuse) throw new Error('SecurityError'); return k in items ? items[k] : null; },
            setItem: (k, v) => {
                if (full && k === 'vscodroid-folder-themes') throw new Error('QuotaExceededError');
                writes += k === 'vscodroid-folder-themes' ? 1 : 0;
                items[k] = String(v);
            },
        };
        let data = JSON.stringify(settings);
        const el = { getAttribute: () => data, setAttribute: (_name, value) => { data = value; } };
        const root = { style: {} };
        // The workbench the page builds later, showing a theme the way `shown`
        // does. Its classes come in the order workbench.js 1.139.1 writes them:
        // the first theme's before the workbench adds its own, and each theme
        // after that in place of the one before, at the end.
        const workbench = { classList: Object.assign([], { contains(c) { return this.includes(c); } }), vars: {} };
        const head = {};
        const document = {
            getElementById: (id) => (id === 'vscode-workbench-web-configuration' ? el : null),
            querySelector: (selector) => (selector === '.monaco-workbench' && workbench.built ? workbench : null),
            documentElement: root,
            head,
        };
        const matchMedia = (query) => ({
            matches: query === '(prefers-color-scheme: dark)' && (typeof dark === 'function' ? dark() : dark),
        });
        const observers = [];
        class MutationObserver {
            constructor(callback) { this.callback = callback; }
            observe(target, options) { observers.push({ target, options, callback: this.callback }); }
        }
        const getComputedStyle = (node) => ({ getPropertyValue: (name) => node.vars[name] || '' });
        const marks = [];
        const performance = { getEntriesByName: (name) => marks.filter((m) => m === name) };
        const listeners = [];
        const addEventListener = (type, listener) => listeners.push({ type, listener });
        // eslint-disable-next-line no-new-func
        new Function('document', 'localStorage', 'matchMedia', 'MutationObserver', 'getComputedStyle', 'location',
            'performance', 'addEventListener', body)(
            document, localStorage, matchMedia, MutationObserver, getComputedStyle, { search }, performance, addEventListener);
        const retitle = () => {
            observers
                .filter((o) => o.target === head && o.options.childList && o.options.subtree)
                .forEach((o) => o.callback([]));
            return root.style.backgroundColor;
        };
        return {
            settings: JSON.parse(data),
            painted: root.style.backgroundColor,
            recorded: items['vscodroid-device-scheme'],
            writes: () => writes,
            /**
             * The head changes the way a theme change rewrites its style element,
             * with the workbench then showing `theme`, or not built yet when that
             * is undefined. Answers the root's colour after.
             */
            changeHead: (theme) => {
                if (theme) {
                    const list = workbench.classList;
                    const before = workbench.id ? workbench.id.split(' ') : [];
                    const others = list.filter((c) => !before.includes(c));
                    list.length = 0;
                    list.push(...others, ...theme.id.split(' '));
                    if (!list.includes('monaco-workbench')) list.push('file-icons-enabled', 'monaco-workbench', 'linux', 'web');
                }
                Object.assign(workbench, theme ? { built: true, ...theme } : { built: false });
                return retitle();
            },
            /** The window title changes, which changes the head and leaves the theme alone. */
            retitle,
            /** The extensions have registered, which the workbench marks. */
            registered: () => marks.push('code/didLoadExtensions'),
            /** A tap on the page. */
            tap: () => listeners.filter((l) => l.type === 'pointerdown').forEach((l) => l.listener({})),
            /** A key press on the page. */
            press: () => listeners.filter((l) => l.type === 'keydown').forEach((l) => l.listener({})),
        };
    };

    // The first load after this update, on a phone in light mode, running the
    // default dark theme: the stored theme is unusable once, and the workbench
    // has to start dark rather than on the web default.
    {
        const page = load({ splash: darkModern, last: undefined, dark: false });
        assert.deepStrictEqual(page.settings.initialColorTheme, {
            themeType: 'dark',
            colors: {
                foreground: '#cccccc', 'editor.background': '#1f1f1f', 'titleBar.activeBackground': '#181818',
                'activityBar.background': '#181818', 'sideBar.background': '#181818',
                'statusBar.background': '#181818', 'statusBar.noFolderBackground': '#1f1f1f',
            },
        }, 'a load that cannot use the stored theme does not start on the dark one the splash recorded, ' +
            'or it was handed a colour that is not hex');
        assert.strictEqual(page.painted, '#1f1f1f', 'the page is not coloured before the workbench paints');
        assert.strictEqual(page.recorded, 'light', "the device's mode is not recorded for the next load");
    }

    // A light theme starts light, and the blank page is the light background.
    {
        const page = load({
            splash: { baseTheme: 'vs', colorInfo: { background: '#ffffff', statusBarBackground: '#f8f8f8' } },
            last: undefined, dark: true,
        });
        assert.deepStrictEqual(page.settings.initialColorTheme,
            { themeType: 'light', colors: { 'statusBar.background': '#f8f8f8' } },
            'a light theme does not start light');
        assert.strictEqual(page.painted, '#ffffff', 'a light theme does not get a light blank page');
    }

    // Both high-contrast types, and a splash without its background colour.
    for (const [baseTheme, themeType, painted] of [['hc-black', 'hcDark', '#1e1e1e'], ['hc-light', 'hcLight', '#ffffff']]) {
        const page = load({ splash: { baseTheme }, last: undefined, dark: false });
        assert.strictEqual(page.settings.initialColorTheme.themeType, themeType, `${baseTheme} does not start as ${themeType}`);
        assert.strictEqual(page.painted, painted, `${baseTheme} without a background colour is not given ${painted}`);
    }

    // A first start has no splash, and this app's default theme is dark. The
    // colours object is there even empty: without one the workbench colours the
    // theme it starts on from "Light 2026", the setting's value at that moment.
    {
        const page = load({ splash: undefined, last: undefined, dark: false });
        assert.deepStrictEqual(page.settings.initialColorTheme, { themeType: 'dark', colors: {} },
            'a first start does not start dark, or starts without a colours object');
        assert.strictEqual(page.painted, '#1e1e1e', 'a first start is not given the dark blank page');
    }

    // A second load before any window has saved a splash or shown a theme, which
    // happens only in the first second or so of a fresh install: nothing names a
    // theme yet, so a folder without a record is still started on the dark
    // default. Once the first load has shown a theme it has a record, and a load
    // that replaces it before its extensions register is handed nothing, one of
    // the loads server.js lists as starting on the wrong colour.
    {
        const page = load({ splash: undefined, last: 'dark', dark: true, search: '?folder=/projects/second' });
        assert.deepStrictEqual(page.settings.initialColorTheme, { themeType: 'dark', colors: {} },
            'a load before any window saved a splash does not start on the dark default');
    }

    // The cases below run successive loads against a model of the workbench the
    // page starts, built from its theme service constructor (workbench.js
    // 1.139.1). It keeps the one theme it stored, the last window's, only when this
    // window is configured for that same theme; else it starts on an unloaded
    // theme of the type and colours it was handed; else of the device's type when
    // window.autoDetectColorScheme is on, and light otherwise; with registry
    // defaults for every colour it was not given. A folder's own settings are
    // known then only from the copy its last load cached, so on its first load it
    // is configured for the user's theme. Once the extensions register, which the
    // workbench marks, the theme the window is configured for replaces the one it
    // started on and is stored, and so is a splash of it. Each of those steps
    // rewrites a style element in the head. The colours a folder's settings
    // customize go over whatever theme it shows, from that cached copy at the
    // start and from the settings once the extensions register; the theme stored
    // is the theme alone.
    const base = { dark: 'vs-dark', light: 'vs' };
    const registry = {
        dark: {
            foreground: '#cccccc', 'editor.background': '#1e1e1e', 'titleBar.activeBackground': '#3c3c3c',
            'activityBar.background': '#333333', 'sideBar.background': '#252526', 'panel.background': '#1e1e1e',
            'statusBar.background': '#007acc', 'statusBar.noFolderBackground': '#68217a',
        },
        light: {
            foreground: '#616161', 'editor.background': '#ffffff', 'titleBar.activeBackground': '#dddddd',
            'activityBar.background': '#2c2c2c', 'sideBar.background': '#f3f3f3', 'panel.background': '#ffffff',
            'statusBar.background': '#007acc', 'statusBar.noFolderBackground': '#68217a',
        },
    };
    // Dark+ and Visual Studio Dark set only the editor background, to the same
    // colour, and take every other colour here from the registry.
    const themes = {
        'Dark Modern': shown('vs-dark vscode-theme-defaults-themes-dark_modern-json', {
            foreground: '#cccccc', 'editor.background': '#1f1f1f', 'titleBar.activeBackground': '#181818',
            'activityBar.background': '#181818', 'sideBar.background': '#181818', 'panel.background': '#181818',
            'statusBar.background': '#181818', 'statusBar.noFolderBackground': '#1f1f1f',
        }),
        'Light Modern': lightModern,
        'Dark+': shown('vs-dark vscode-theme-defaults-themes-dark_plus-json', registry.dark),
        'Visual Studio Dark': shown('vs-dark vscode-theme-defaults-themes-dark_vs-json', registry.dark),
    };
    /** A theme with the colours a folder's settings customize over it. */
    const over = (theme, colors = {}) => ({ ...theme, vars: { ...theme.vars, ...vars(colors) } });
    const splashOf = (theme) => {
        const color = (id) => theme.vars[`--vscode-${id.replace(/\./g, '-')}`];
        return {
            baseTheme: theme.base,
            colorInfo: {
                foreground: color('foreground'), background: color('editor.background'),
                editorBackground: color('editor.background'), titleBarBackground: color('titleBar.activeBackground'),
                activityBarBackground: color('activityBar.background'), sideBarBackground: color('sideBar.background'),
                panelBackground: color('panel.background'), statusBarBackground: color('statusBar.background'),
                statusBarNoFolderBackground: color('statusBar.noFolderBackground'),
            },
        };
    };

    /**
     * A phone the app runs on, from the last window it showed before this
     * update: `splash` is the theme that window saved and stored. `own` maps a
     * folder or workspace to a theme its own settings name; every other window
     * follows `user`, the user's theme. `custom` maps a folder to the colours
     * its own settings customize. `renamed` is the first load after the
     * update, which reads the default under its old name from a cache, so a
     * window following it is configured for an id no theme has.
     */
    const phone = ({ splash, user = 'Dark Modern', own = {}, custom = {}, renamed = false, dark = true, autoDetect = false }) => {
        const items = { 'monaco-parts-splash': JSON.stringify(splashOf(themes[splash])) };
        const state = { stored: splash, user, own, renamed, dark, autoDetect };
        const cached = new Set();
        let open;
        return {
            items,
            state,
            /**
             * One page load, through to the theme the window is configured for.
             * `early` taps the page before the extensions register, and `between`
             * after that, before the configured theme replaces the one the
             * workbench started on. Answers how the load started.
             */
            visit(search, { early = false, between = false } = {}) {
                const query = new URLSearchParams(search);
                const where = query.get('folder') || query.get('workspace') || '';
                const name = state.autoDetect ? (state.dark ? 'Dark Modern' : 'Light Modern') : own[where] || state.user;
                const first = state.autoDetect ? name : (cached.has(where) && own[where]) || state.user;
                const usable = state.stored === first && !(state.renamed && first === state.user);
                const page = load({ items, dark: () => state.dark, search });
                const handed = page.settings.initialColorTheme;
                const type = handed ? handed.themeType : state.autoDetect && state.dark ? 'dark' : 'light';
                const started = over(
                    usable ? themes[first] : shown(base[type], { ...registry[type], ...(handed ? handed.colors : {}) }),
                    cached.has(where) ? custom[where] : {});
                page.changeHead(started);
                if (early) page.tap();
                page.registered();
                if (between) page.tap();
                const configured = over(themes[name], custom[where]);
                page.changeHead(configured);
                cached.add(where);
                Object.assign(state, { stored: name, renamed: false });
                items['monaco-parts-splash'] = JSON.stringify(splashOf(configured));
                open = { page, where };
                return { painted: page.painted, started, handed };
            },
            /**
             * The device switches between light and dark while a window is open;
             * with window.autoDetectColorScheme on, the workbench follows it.
             */
            switchDevice(dark) {
                state.dark = dark;
                if (!state.autoDetect) return;
                const name = dark ? 'Dark Modern' : 'Light Modern';
                const now = over(themes[name], custom[open.where]);
                open.page.changeHead(now);
                state.stored = name;
                items['monaco-parts-splash'] = JSON.stringify(splashOf(now));
            },
            /**
             * The user picks a theme in the window that is open, by touch or
             * `withKeys`. It goes to the folder's settings when they name one,
             * and to the user's otherwise.
             */
            pick(name, { withKeys = false } = {}) {
                if (withKeys) open.page.press(); else open.page.tap();
                if (own[open.where]) own[open.where] = name; else state.user = name;
                const now = over(themes[name], custom[open.where]);
                open.page.changeHead(now);
                state.stored = name;
                items['monaco-parts-splash'] = JSON.stringify(splashOf(now));
            },
        };
    };

    /** A load has to paint, and the workbench start on, the theme the window then shows. */
    const startsOn = (visit, name, what) => {
        assert.strictEqual(visit.painted, themes[name].vars['--vscode-editor-background'],
            `${what}: the page is painted ${visit.painted} before the workbench, where ${name} is about to show`);
        assert.strictEqual(visit.started.base, themes[name].base,
            `${what}: the workbench starts on a ${visit.started.base} theme before ${name}`);
    };

    // The theme changed in one folder, then another opened from Open Recent, and
    // back: the way a theme change usually ends. Every folder here follows the
    // user's theme, so each has to start on the theme picked last, wherever it was
    // picked, and not on the one the folder showed itself the time before.
    {
        const device = phone({ splash: 'Dark Modern', renamed: true });
        startsOn(device.visit('?folder=/p/npmcheck'), 'Dark Modern', 'the first load after this update');
        device.pick('Light Modern');
        startsOn(device.visit('?folder=/p/emptyp36'), 'Light Modern', 'a folder opened after Light Modern was picked in another');
        device.pick('Dark Modern');
        startsOn(device.visit('?folder=/p/npmcheck'), 'Dark Modern', 'a folder opened after Dark Modern was picked in another');
        device.pick('Light Modern', { withKeys: true });
        startsOn(device.visit('?folder=/p/emptyp36'), 'Light Modern', 'a folder opened after Light Modern was picked again');
    }

    // A folder whose own settings name Light Modern, entered after a window on the
    // default dark theme, and a folder on that theme entered after it. Each throws
    // the stored theme away, so the workbench starts on what it is handed, which
    // has to be that folder's theme and not the one the window before left. The
    // light folder's first load, where its settings arrive only as the extensions
    // register, is tapped while it loads. It is reached once through the app's
    // address, which encodes the path, and then through the one the workbench
    // builds, which does not.
    {
        const device = phone({ splash: 'Dark Modern', own: { '/projects/light': 'Light Modern' } });
        device.visit('?folder=/projects/dark');
        device.visit('?folder=%2Fprojects%2Flight', { early: true });
        startsOn(device.visit('?folder=/projects/dark'), 'Dark Modern',
            'a folder on the dark user theme entered after one with a light theme of its own');
        const light = device.visit('?folder=/projects/light');
        startsOn(light, 'Light Modern', 'a folder with a light theme of its own entered after a dark window');
        assert.deepStrictEqual(light.handed, { themeType: 'light', colors: lightModernColors },
            'a folder with a light theme of its own is not handed its own colours');
    }

    // The same folder beside one that follows the user's theme while that passes
    // through Light Modern and back. Moving every record that held a window's old
    // theme along with it, the obvious repair for the first case, swaps the two
    // here on every load from then on.
    {
        const device = phone({ splash: 'Dark Modern', own: { '/p/own-light': 'Light Modern' } });
        device.visit('?folder=/p/own-light');
        device.visit('?folder=/p/follow');
        device.visit('?folder=/p/follow');
        device.pick('Light Modern');
        device.visit('?folder=/p/follow');
        device.pick('Dark Modern');
        for (let round = 0; round < 2; round++) {
            startsOn(device.visit('?folder=/p/own-light'), 'Light Modern', 'a folder with a light theme of its own');
            startsOn(device.visit('?folder=/p/follow'), 'Dark Modern',
                'a folder that follows the dark user theme, after one with a light theme of its own');
        }
    }

    // A folder whose own settings colour its title, activity and status bars, as
    // Peacock writes them, and name no theme. It follows the user's theme, so a
    // theme picked in it is the one the next folder starts on, and one picked in
    // another folder is the one it starts on. Its colours differ from every other
    // folder's, so telling themes apart by their colours, rather than by the id
    // the workbench gives each, took it for a folder with a theme of its own, and
    // both went wrong. Its second load is tapped once the extensions register,
    // which leaves the theme it starts on to tell, with that theme's classes
    // where the workbench puts them at the start.
    {
        const bars = '#42b883';
        const peacock = { 'titleBar.activeBackground': bars, 'activityBar.background': bars, 'statusBar.background': bars };
        const device = phone({ splash: 'Dark Modern', custom: { '/p/peacock': peacock } });
        device.visit('?folder=/p/a');
        device.visit('?folder=/p/peacock');
        device.visit('?folder=/p/a');
        device.visit('?folder=/p/peacock', { between: true });
        device.pick('Light Modern');
        startsOn(device.visit('?folder=/p/a'), 'Light Modern',
            'a folder opened after Light Modern was picked in one whose settings colour its bars');
        device.pick('Dark Modern');
        startsOn(device.visit('?folder=/p/peacock'), 'Dark Modern',
            'a folder whose settings colour its bars, opened after Dark Modern was picked in another');
        assert.ok(!JSON.parse(device.items['vscodroid-folder-themes'])['/p/peacock'].own,
            'a folder whose settings colour its bars is taken to have a theme of its own');
    }

    // A folder whose own settings name Visual Studio Dark while the user's theme
    // is Dark+, two themes with the same colours in every part a record keeps.
    // Told apart by their ids, the folder is marked, so the folder opened after
    // it, where the theme it stored is unusable, is handed the user's theme
    // rather than starting on the light web default.
    {
        const device = phone({ splash: 'Dark+', user: 'Dark+', own: { '/p/vs': 'Visual Studio Dark' } });
        device.visit('?folder=/p/a');
        device.visit('?folder=/p/vs');
        startsOn(device.visit('?folder=/p/b'), 'Dark+',
            "a folder opened after one whose own theme has the user's colours");
        startsOn(device.visit('?folder=/p/vs'), 'Visual Studio Dark',
            "a folder whose own theme has the user's colours, opened again");
    }

    // A folder with a theme of its own that is the first load this script runs has
    // no window before it to be told apart by, so it is taken to follow the user's
    // theme, and the folder opened after it, which then shows another theme than
    // that one, to have a theme of its own. Neither mark outlives the next loads.
    {
        const device = phone({ splash: 'Light Modern', own: { '/p/light': 'Light Modern' } });
        device.visit('?folder=/p/light');
        device.visit('?folder=/p/a');
        device.visit('?folder=/p/b');
        startsOn(device.visit('?folder=/p/c'), 'Dark Modern', 'a folder opened once two others followed the dark user theme');
        device.visit('?folder=/p/light');
        startsOn(device.visit('?folder=/p/a'), 'Dark Modern', 'a folder first taken to have a dark theme of its own');
        startsOn(device.visit('?folder=/p/light'), 'Light Modern', 'a folder with a light theme of its own, put right');
    }

    // The empty window has no folder settings, so whatever it starts on, it shows
    // the user's theme. Opened after the folder that was taken to follow above,
    // it gives the next folder the user's theme to start on at once.
    {
        const device = phone({ splash: 'Light Modern', own: { '/p/light': 'Light Modern' } });
        device.visit('?folder=/p/light');
        device.visit('?ew=true');
        startsOn(device.visit('?folder=/p/c'), 'Dark Modern', 'a folder opened after the empty window');
    }

    // With window.autoDetectColorScheme on, the device switched to light mode
    // while a folder was open, and the workbench followed it by itself.
    {
        const device = phone({ splash: 'Dark Modern', autoDetect: true });
        device.visit('?folder=/p/a');
        device.visit('?folder=/p/b');
        device.switchDevice(false);
        startsOn(device.visit('?folder=/p/c'), 'Light Modern', 'a folder opened after the device went light in another');
    }

    // With window.autoDetectColorScheme on, the device switched to light mode
    // while the app was closed. That load follows the device by itself and is
    // not a sign of a theme of the folder's own, so the folders after it start on
    // the light theme it ended on.
    {
        const device = phone({ splash: 'Dark Modern', autoDetect: true });
        device.visit('?folder=/p/a');
        device.state.dark = false;
        device.visit('?folder=/p/b');
        startsOn(device.visit('?folder=/p/c'), 'Light Modern', 'a folder opened after a load that followed the device to light mode');
        startsOn(device.visit('?folder=/p/a'), 'Light Modern', 'a folder last shown dark, opened after the device went light');
    }

    // A workspace is a window of its own, recorded under its own file, not under
    // the empty window's key, where its own light theme would pass for the
    // user's dark one.
    {
        const device = phone({ splash: 'Dark Modern', own: { '/p/w.code-workspace': 'Light Modern' } });
        device.visit('?ew=true');
        device.visit('?workspace=/p/w.code-workspace');
        device.visit('?ew=true');
        startsOn(device.visit('?workspace=/p/w.code-workspace'), 'Light Modern', 'a workspace with a light theme of its own');
        startsOn(device.visit('?ew=true'), 'Dark Modern', 'the empty window after a workspace with a light theme of its own');
        assert.ok(Object.keys(JSON.parse(device.items['vscodroid-folder-themes'])).includes('/p/w.code-workspace'),
            'a workspace is not recorded under its own file');
    }

    // A folder never shown, on a load after the first. The splash then holds the
    // theme the workbench stored, which is unusable on that load only when the
    // folder's settings name another, so it is not handed over and the workbench
    // starts on its own default. The blank page still takes the splash colour,
    // which is right for every folder that follows the user's theme.
    {
        const page = load({ splash: darkModern, last: 'light', dark: false, search: '?folder=/projects/new' });
        assert.strictEqual(page.settings.initialColorTheme, undefined,
            "the last window's theme is handed to a folder never shown, and the workbench uses it only " +
            'when that folder is configured for another theme');
        assert.strictEqual(page.painted, '#1f1f1f', 'a folder never shown does not start its blank page on the splash');
    }

    // The device switched to light mode since the last load. With
    // window.autoDetectColorScheme on, the workbench is following the device and
    // its own pick is right, so it is left to make it, even in a folder with a
    // theme of its own, whose record is handed over otherwise.
    {
        const items = {
            'vscodroid-folder-themes': JSON.stringify({ '/projects/a': { ...darkModern, own: true } }),
            'vscodroid-device-scheme': 'dark',
        };
        assert.ok(load({ items: { ...items }, dark: true, search: '?folder=/projects/a' }).settings.initialColorTheme,
            'a folder with a theme of its own is not handed its record');
        const page = load({ items, splash: darkModern, dark: false, search: '?folder=/projects/a' });
        assert.strictEqual(page.settings.initialColorTheme, undefined,
            'the last theme is imposed on a load after the device changed mode, which the workbench ' +
            'follows by itself when window.autoDetectColorScheme is on');
        assert.strictEqual(page.recorded, 'light', 'the switch is not recorded, so the next load would see it again');
    }

    // A starting theme the page was already given is not replaced.
    {
        const own = { themeType: 'light', colors: { 'editor.background': '#fafafa' } };
        const page = load({ splash: darkModern, last: undefined, dark: true, settings: { initialColorTheme: own } });
        assert.deepStrictEqual(page.settings.initialColorTheme, own, 'a starting theme the page already carried was replaced');
    }

    // The theme changes while the page is open, here Light Modern to Dark Modern.
    // The root fills the space the soft keyboard gives back until the workbench
    // lays itself out again, so it has to follow: without the observer of the
    // head it stays white under the dark theme. Every change of the window title
    // changes the head too, and one that leaves the theme alone writes nothing.
    {
        const page = load({ splash: { baseTheme: 'vs', colorInfo: { background: '#ffffff' } }, last: 'light', dark: false });
        assert.strictEqual(page.painted, '#ffffff', 'a light theme does not get a light blank page');
        const dark = shown('vs-dark vscode-theme-defaults-themes-dark_modern-json', { 'editor.background': ' #1f1f1f' });
        assert.strictEqual(page.changeHead(dark), '#1f1f1f',
            'the root keeps the theme the page started on after the theme changed');
        page.retitle();
        assert.strictEqual(page.writes(), 1, 'a change of the head that leaves the theme alone rewrote the record');
    }

    // A head that changes before the workbench is built leaves the colour the page started with.
    {
        const page = load({ splash: darkModern, last: 'light', dark: false });
        assert.strictEqual(page.changeHead(undefined), '#1f1f1f', 'the root lost its colour before the workbench existed');
    }

    // Twenty folders are kept, the most recently shown, so the record cannot grow
    // into the storage the workbench's sealed secrets share. A folder shown again
    // counts as shown last, even with the theme it had.
    {
        const items = {};
        const show = (i) => load({ items, dark: true, search: `?folder=/projects/${i}` }).changeHead(darkModernShown);
        for (let i = 0; i < 20; i++) show(i);
        show(0);
        show(20);
        const kept = Object.keys(JSON.parse(items['vscodroid-folder-themes']));
        assert.strictEqual(kept.length, 20, `${kept.length} folders are kept rather than twenty`);
        assert.ok(kept.includes('/projects/0') && kept.includes('/projects/20') && !kept.includes('/projects/1'),
            `the folder dropped is not the one shown longest ago: ${kept.join(' ')}`);
    }

    // Storage that refuses leaves the page as upstream ships it.
    {
        const page = load({ splash: darkModern, last: 'dark', dark: true, refuse: true });
        assert.deepStrictEqual(page.settings, {}, 'a page whose storage refused was still changed');
        assert.strictEqual(page.painted, undefined, 'a page whose storage refused was still painted');
    }

    // Storage that is full refuses the record. The page still follows the theme,
    // and the refusal stays in the observer rather than reaching the page as an
    // uncaught error on every theme change.
    {
        const page = load({ splash: darkModern, last: 'dark', dark: true, full: true, search: '?folder=/p/full' });
        let root;
        assert.doesNotThrow(() => { root = page.changeHead(lightModern); }, 'a full storage throws out of the theme observer');
        assert.strictEqual(root, '#ffffff', 'the root stops following the theme once storage is full');
    }
    fs.rmSync(dir, { recursive: true, force: true });
}

// From 1.138 the page's policy trusts inline scripts by a per-request nonce and
// no longer hashes bare ones, so the injected scripts have to carry the page's
// own nonce placeholder, which the server fills in on every request.
//
// NEGATIVE CONTROL: always emit a bare <script> in extendWorkbenchPage and the
// first assertion goes red.
{
    const nonce = '{{WORKBENCH_SCRIPT_NONCE}}';
    const anchor =
        '<meta id="vscode-workbench-web-configuration" data-settings="{{WORKBENCH_WEB_CONFIGURATION}}">';
    const dir = fixture(UPSTREAM);
    const pagePath = path.join(dir, 'vscode-reh', 'out', 'vs', 'code', 'browser', 'workbench', 'workbench.html');
    fs.mkdirSync(path.dirname(pagePath), { recursive: true });
    fs.writeFileSync(pagePath, [
        '<!DOCTYPE html>', '<html>', '\t<head>',
        `\t\t<script nonce="${nonce}">`, '\t\t\tperformance.mark("code/didStartRenderer");', '\t\t</script>',
        `\t\t${anchor}`, '\t</head>', '</html>', '',
    ].join('\n'));

    const run = boot(dir);
    assert.strictEqual(run.status, 0, `a 1.138-shaped page should boot cleanly:\n${run.output}`);
    const page = fs.readFileSync(pagePath, 'utf8');
    const injected = [...page.matchAll(/<script nonce="\{\{WORKBENCH_SCRIPT_NONCE\}\}">\n(\t\t\t\/\* vscodroid-[\s\S]*?)\n\t\t<\/script>/g)];
    assert.strictEqual(
        injected.length, 3,
        `the injected scripts do not carry the page's nonce, so its policy refuses them:\n${page}`,
    );
    assert.ok(!/\n\t\t<script>\n/.test(page), 'a bare <script> was injected into a page trusted by nonce');
    injected.forEach((m) => new Function(m[1])); // eslint-disable-line no-new-func -- a parse check
    fs.rmSync(dir, { recursive: true, force: true });
}

// A page missing the element costs a log line, not a start. The bootstrap is
// restarted by the watchdog, so a throw here would be a crash loop.
{
    const dir = fixture(UPSTREAM);
    const pagePath = path.join(dir, 'vscode-reh', 'out', 'vs', 'code', 'browser', 'workbench', 'workbench.html');
    fs.mkdirSync(path.dirname(pagePath), { recursive: true });
    fs.writeFileSync(pagePath, '<!DOCTYPE html>\n<html></html>\n');

    const run = boot(dir);
    assert.strictEqual(run.status, 0, `a page without the element should still boot:\n${run.output}`);
    assert.ok(
        /Could not widen the trusted link domains/.test(run.output),
        `a page this cannot extend should be named:\n${run.output}`,
    );
    fs.rmSync(dir, { recursive: true, force: true });
}

// The sign-in callback page is pinned to this app, and a second start pins it
// again rather than stacking another package into the address. The page line is
// the one patch 0006 writes, copied rather than read from the packaged tree,
// which a checkout without the server does not have.
{
    const dir = fixture(UPSTREAM);
    const pagePath = path.join(dir, 'vscode-reh', 'out', 'vs', 'code', 'browser', 'workbench', 'callback.html');
    fs.mkdirSync(path.dirname(pagePath), { recursive: true });
    fs.writeFileSync(pagePath, [
        '<script>',
        "const payload = encodeURIComponent(JSON.stringify({ id: id, uri: uri }));",
        "window.location.href = 'intent://callback?data=' + payload + '#Intent;scheme=vscodroid;end';",
        '</script>',
        '',
    ].join('\n'));
    const bootAs = (pkg) => {
        const result = spawnSync(process.execPath, [path.join(dir, 'server.js'), '--host=127.0.0.1'], {
            encoding: 'utf8',
            timeout: 20_000,
            env: { ...process.env, VSCODROID_PACKAGE: pkg },
        });
        return { ...result, output: `${result.stdout || ''}${result.stderr || ''}` };
    };

    const once = bootAs('com.vscodroid.debug');
    assert.strictEqual(once.status, 0, `a start with a callback page should boot cleanly:\n${once.output}`);
    const first = fs.readFileSync(pagePath, 'utf8');
    assert.ok(
        first.includes("#Intent;scheme=vscodroid;package=com.vscodroid.debug;end'"),
        `the callback intent is not pinned to the package, so any app declaring the scheme is offered the sign-in:\n${first}`,
    );
    // The per-run secret this page used to carry is gone: /callback is answered
    // before the connection token check, so anything on the device could read it
    // over loopback. What a callback proves now is minted per request by the
    // workbench and arrives in the query, so nothing here writes into the page
    // but the package.
    assert.ok(!/nonce/i.test(first), `the callback page carries a secret again:\n${first}`);

    const twice = bootAs('com.vscodroid.debug');
    assert.strictEqual(twice.status, 0, `a second start should boot cleanly:\n${twice.output}`);
    const second = fs.readFileSync(pagePath, 'utf8');
    assert.strictEqual((second.match(/package=/g) || []).length, 1, `a second start stacked the package:\n${second}`);

    // Something that is not a package name is never written into the address.
    const odd = bootAs("x;end'+alert(1)+'");
    assert.strictEqual(odd.status, 0, `a malformed package should not stop the start:\n${odd.output}`);
    assert.ok(!fs.readFileSync(pagePath, 'utf8').includes('alert'), 'a malformed package reached the page');
    assert.match(odd.output, /Could not pin/, `an unpinned intent should be reported:\n${odd.output}`);
    fs.rmSync(dir, { recursive: true, force: true });
}

preloadRidesAsOneToken()
    .then(proxySurvivesTheBootstrap)
    .then(stoppingTakesTheEditorServerWithIt)
    .then(() => {
        console.log(
            'ok -- product.json survives a truncated file and an unwritable directory, a missing ' +
                'server tree is a failed start rather than a healthy one, the workbench page is ' +
                'given the trusted-domain list once and a page without the element it extends is ' +
                'reported rather than thrown, the page starts on the theme its folder showed last, ' +
                'the sign-in callback intent is pinned once per start, a proxy that does not parse costs only DNS, the ' +
                'preload rides as one token, the DNS proxy outlives the bootstrap, and a stop ' +
                'takes the editor server with it',
        );
    })
    .catch((err) => {
        console.error(err);
        process.exit(1);
    });
