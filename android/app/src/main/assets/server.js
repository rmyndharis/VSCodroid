#!/usr/bin/env node
/**
 * VSCodroid Server Bootstrap
 * Launches VS Code Server (vscode-reh) with VSCodroid configuration.
 */

const path = require('path');
const fs = require('fs');
const os = require('os');

// Parse command-line arguments
const args = {};
process.argv.slice(2).forEach(arg => {
    const [key, value] = arg.split('=');
    args[key.replace(/^--/, '')] = value || true;
});

const HOST = args.host || '127.0.0.1';
const PORT = parseInt(args.port) || 13337;
const LOG_LEVEL = args.log || 'info';

const SERVER_DIR = path.dirname(__filename);
const REH_DIR = path.join(SERVER_DIR, 'vscode-reh');

// How long the editor server gets to answer a SIGTERM before it is SIGKILLed.
// Bounded from outside: ProcessManager force-kills this process a second after
// sending the signal, so anything at or beyond that never runs at all.
const CHILD_KILL_AFTER_SIGTERM_MS = 700;

// Product configuration override. Applied with a shallow Object.assign, so each
// nested object here replaces the built one whole. Nothing below depends on the
// port; the comment here said it did, and the branding overlay repeated it.
const productOverrides = {
    nameShort: 'VSCodroid',
    nameLong: 'VSCodroid',
    applicationName: 'vscodroid',
    dataFolderName: '.vscodroid',
    quality: 'stable',
    extensionsGallery: {
        serviceUrl: 'https://open-vsx.org/vscode/gallery',
        itemUrl: 'https://open-vsx.org/vscode/item',
        resourceUrlTemplate: 'https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}',
        controlUrl: '',
        nlsBaseUrl: ''
    },
    linkProtectionTrustedDomains: ['https://open-vsx.org'],
    telemetryOptIn: false,
    enableTelemetry: false
    // CDN URLs (webEndpointUrl, webviewContentExternalBaseUrlTemplate) are hardcoded
    // in workbench.js and cannot be overridden via product.json. The Android WebView
    // intercepts *.vscode-cdn.net requests and redirects them to localhost instead.
};

function log(level, message) {
    const levels = { error: 0, warn: 1, info: 2, debug: 3 };
    if (levels[level] <= levels[LOG_LEVEL]) {
        const timestamp = new Date().toISOString();
        console.log(`[${timestamp}] [${level}] ${message}`);
    }
}

// Check if vscode-reh exists
//
// A missing entry point ends this process rather than binding the port with
// something else. What used to stand in was a minimal HTTP server answering 200
// to every path -- `/version` included, which is exactly what ProcessManager's
// readiness probe accepts -- serving a page that told whoever was holding the
// phone to run two shell scripts from this repository. So a broken install
// reported a healthy start and put developer instructions in front of a user,
// which is a worse outcome than the failure it was standing in for. Exiting
// non-zero leaves the port unbound, the readiness poll fails, and the log names
// the file that is missing.
const rehEntryPoint = path.join(REH_DIR, 'out', 'server-main.js');
if (!fs.existsSync(rehEntryPoint)) {
    log('error', `vscode-reh entry point not found at ${rehEntryPoint}`);
    log('error', 'The server tree was never unpacked, or was removed after setup ' +
        'recorded it. Clearing app data re-runs the extraction; in a checkout, ' +
        './scripts/fetch-vscode-oss.sh && ./scripts/package-assets.sh builds it.');
    process.exit(1);
} else {
    // Launch VS Code Server
    log('info', `Starting VS Code Server on http://${HOST}:${PORT}`);

    // Inject product overrides
    process.env.VSCODE_NLS_CONFIG = JSON.stringify({ locale: 'en', availableLanguages: {} });

    // Override product.json.
    //
    // Through a temporary file and a rename, because this process is killed as a
    // matter of routine -- ProcessManager's watchdog exists to notice SIGKILL
    // from the OOM killer and from Android's phantom-process limit. An in-place
    // writeFileSync interrupted partway leaves truncated JSON, and rename(2)
    // replaces the file in one step instead: a kill lands either side of it and
    // never inside it. It also means a write that cannot finish -- no space, a
    // directory that turned read-only -- leaves the existing file untouched
    // rather than half-replaced.
    //
    // No fsync. The threat here is the process dying, not the device losing
    // power, and the page cache outlives the process.
    const productJsonPath = path.join(REH_DIR, 'product.json');
    if (fs.existsSync(productJsonPath)) {
        try {
            const product = JSON.parse(fs.readFileSync(productJsonPath, 'utf8'));
            Object.assign(product, productOverrides);
            const tmpPath = `${productJsonPath}.${process.pid}.tmp`;
            try {
                fs.writeFileSync(tmpPath, JSON.stringify(product, null, 2));
                fs.renameSync(tmpPath, productJsonPath);
            } catch (e) {
                try { fs.unlinkSync(tmpPath); } catch { /* nothing was written */ }
                throw e;
            }
            log('info', 'Product configuration updated');
        } catch (e) {
            // Carrying on beats exiting. The watchdog restarts this process, so
            // an uncaught throw here is a crash loop that reaches the user as a
            // white screen with no explanation; the server below will report the
            // same file in its own terms, after this line has already named it.
            log('error', `Could not apply the product configuration to ${productJsonPath}: ${e.message}`);
            log('error', 'A truncated product.json is repaired by the asset extraction that ' +
                'runs on the next app update, or by clearing app data.');
        }
    }

    // Build server arguments.
    //
    // No connection-token flag of any kind, and that absence is the security
    // property rather than an omission. With none of --without-connection-token,
    // --connection-token or --connection-token-file present, the server reads
    // <server-data-dir>/data/token -- not <user-data-dir>/token, because
    // server.main.ts rewrites the user-data path to <server-data-dir>/data
    // before the token resolver sees it -- generates one with crypto.randomUUID
    // if it is absent, writes it back with mode 0600, and then requires it on
    // every route except /version, /delay-shutdown and /callback -- the last
    // of those added by patch 0012, because it is reached by the system browser
    // at the end of an OAuth redirect and carries neither cookie nor token. Passing --connection-token-file here instead would be
    // worse in a specific way: the forwarding list below is a whitelist, so an
    // unlisted flag is dropped silently and the server would run wide open with
    // nothing in the log to say so.
    const serverArgs = [
        rehEntryPoint,
        '--host', HOST,
        '--port', String(PORT),
        '--accept-server-license-terms',
        // Without this every folder opens in Restricted Mode, which blocks most
        // extensions from activating.
        //
        // The security.workspace.trust.enabled setting cannot do it, and for two
        // reasons rather than one. The setting is registered with
        // ConfigurationScope.APPLICATION, and the remote side contributes only
        // REMOTE_MACHINE_SCOPES: MACHINE, WINDOW, RESOURCE, LANGUAGE_OVERRIDABLE,
        // MACHINE_OVERRIDABLE (configuration.ts:387), so an application-scoped
        // setting is ignored here whatever file it is in. Separately, until
        // 2026-08-12 the file this app wrote was not read at all: the workbench
        // takes remote settings from <server-data-dir>/data/Machine/settings.json
        // (server.main.ts:39-40, environmentService.ts:86,
        // remoteAgentEnvironmentImpl.ts:112), and we were writing a sibling
        // User/settings.json. Fixing the path made every other default take
        // effect; it does not make this one work.
        // isWorkspaceTrustEnabled() checks environmentService.disableWorkspaceTrust
        // before it consults the configuration at all, so the flag is the only
        // route that works, and webClientServer passes it through to the web
        // client as enableWorkspaceTrust.
        //
        // Deliberate trade-off, not an oversight: the default workspace is the
        // user's own projects directory inside the app sandbox, where a trust
        // prompt asks about files they created themselves on their own device.
        // The exposure this accepts is a folder opened through the SAF picker
        // from somewhere else, whose eslint.config.js the bundled ESLint
        // extension then loads and executes without asking.
        //
        // It cannot be decided per folder from here, and that is a fact about
        // where the flag is read rather than a shortcut taken in this file. The
        // server parses it once at spawn and answers every page load from that
        // single value -- `enableWorkspaceTrust: !args["disable-workspace-trust"]`
        // in vscode-reh/out/server-main.js -- while this app spawns the server
        // before any folder has been chosen and switches folders by navigating
        // the same WebView on the same port. Following the folder would mean
        // restarting the server on every switch, which throws away the session
        // that reused port exists to keep. The place with both the folder and a
        // user to ask is the SAF picker, on the Android side.
        //
        // And the flag buys more than convenience: dbaeumer.vscode-eslint and
        // ms-python.python both declare `untrustedWorkspaces.supported: false`
        // in their manifests, so without it neither activates at all, for the
        // user's own projects directory just as much as for a device folder.
        '--disable-workspace-trust',
        '--log', LOG_LEVEL
    ];

    // Forward relevant CLI args
    ['extensions-dir', 'user-data-dir', 'server-data-dir', 'logsPath'].forEach(key => {
        if (args[key]) serverArgs.push(`--${key}`, args[key]);
    });

    // Launch server
    const { fork } = require('child_process');

    // The DNS proxy is preloaded INTO the child rather than started here, and
    // the child binds it and sets its own HTTP(S)_PROXY. This process is
    // SIGKILLed as a matter of routine and the child survives it holding the
    // port, which is the case ProcessManager adopts on the next launch; a proxy
    // bound here died with this process and left that survivor pointing at a
    // closed port for its whole session, with nothing able to change the
    // environment of a process already running. See dns-proxy.js for the rest.
    //
    // `--require` costs no extra process. It would ride into every helper the
    // editor server forks, because fork passes execArgv on by default and the
    // server hands it to `new Worker` as well, so the module takes both the
    // option and the flag back out of that process before anything else runs;
    // see the self-start block at the bottom of dns-proxy.js.
    //
    // The file is loaded here before it is asked for over there, and existing is
    // not the property that matters. A `--require` module that does not parse, or
    // that throws while it is being evaluated, stops the child loading its main
    // script at all: a truncated dns-proxy.js would then cost the app its editor
    // server, where the contract is that it costs musl clients their DNS and
    // nothing else. Loading it here proves it parses and does nothing further --
    // the self-start block at the bottom of that file gates on
    // VSCODROID_DNS_PROXY, which is set on the child's environment below and
    // never on this process's.
    //
    // One token, not `--require` followed by the path. process-monitor.js names
    // a process by the first argument that is not an option, so a path standing
    // on its own becomes the editor server's identity: its row in the process
    // tree and the status bar tooltip both read `libnode.so dns-proxy.js`, which
    // is the very confusion that naming rule was written to end. Attached to the
    // option it stays an option, and the row names server-main.js again.
    const childEnv = { ...process.env };
    const execArgv = [...process.execArgv];
    const dnsProxyPath = path.join(SERVER_DIR, 'dns-proxy.js');
    try {
        require(dnsProxyPath);
        execArgv.push(`--require=${dnsProxyPath}`);
        childEnv.VSCODROID_DNS_PROXY = '1';
    } catch (e) {
        log('warn', `dns-proxy not usable at ${dnsProxyPath} (${e.message}); ` +
            'musl clients will not resolve names');
    }

    const server = fork(serverArgs[0], serverArgs.slice(1), {
        env: childEnv,
        execArgv,
        stdio: 'inherit'
    });

    // Who holds the port, recorded from the side that knows.
    //
    // This process can be SIGKILLed while the child it forked keeps running
    // and keeps the socket, routine here, and the reason the Kotlin side
    // adopts a surviving server rather than spawning one that cannot bind.
    // But the survivor is anonymous to the next launch: the Process handle
    // died with the parent, and Android denies an app any read of
    // /proc/net/tcp, so the port cannot be mapped back to a pid.
    //
    // So write the pid down while it is still known. The alternative the
    // Kotlin side used was to ask over HTTP whether the port holder accepted
    // our connection token, which meant sending the token to whoever held
    // the port, before knowing whether they were ours. Anything on Android
    // can bind a loopback port; that made the test hand the secret to the one
    // party it was meant to identify.
    //
    // The port is written with the pid on purpose: a stale file from an
    // earlier run on a different port must not vouch for this one.
    const pidFile = path.join(SERVER_DIR, 'editor-server.pid');
    try {
        const tmp = pidFile + '.tmp';
        fs.writeFileSync(tmp, JSON.stringify({ pid: server.pid, port: PORT }));
        fs.renameSync(tmp, pidFile);
    } catch (e) {
        // Not fatal: adoption is an optimisation, and its absence costs a
        // restart rather than a session.
        log('warn', 'Could not record the editor server pid: ' + e.message);
    }

    const clearPidFile = () => {
        try {
            fs.unlinkSync(pidFile);
        } catch {
            // Already gone, or never written. Either way there is nothing to
            // clean up, and a stale file is handled by the reader anyway.
        }
    };

    // Start process monitor (non-fatal if it fails)
    try {
        const monitor = require(path.join(SERVER_DIR, 'process-monitor.js'));
        monitor.start();
    } catch (e) {
        log('warn', 'Process monitor failed to start: ' + e.message);
    }

    server.on('error', (err) => {
        log('error', `Failed to start VS Code Server: ${err.message}`);
        process.exit(1);
    });

    server.on('exit', (code, signal) => {
        // A killed child reports code === null and the signal separately, and
        // `code || 0` collapsed that to a clean zero -- so a server killed for
        // running out of memory, or by Android's phantom-process limit, was
        // logged as having exited cleanly while the watchdog restarted it. The
        // log then said both, one line apart.
        //
        // 128 + signum is the shell convention, and it is what the Kotlin side
        // already expects: its 137 branch exists to name SIGKILL and could
        // never be reached.
        // Cleared on the child's exit rather than on this process's, because
        // this process being killed is exactly the case the file exists for.
        clearPidFile();
        if (signal) {
            const signum = os.constants.signals[signal] || 0;
            log('warn', `VS Code Server killed by ${signal}`);
            process.exit(128 + signum);
        }
        log('info', `VS Code Server exited with code ${code}`);
        process.exit(code ?? 0);
    });

    // Shutting down means the editor server too, and this is the only side that
    // holds a handle on it.
    //
    // ProcessManager sends this SIGTERM, waits GRACEFUL_STOP_TIMEOUT_MS and then
    // force-kills THIS process; Java's destroyForcibly signals one pid, and
    // fork() sets no PDEATHSIG, so a child still unwinding when that second
    // elapsed was left running with no service, no notification and no way for
    // the user to end it, after a Stop that reported success. Escalating from
    // here closes it: the timer has to fire inside that window or never, which is
    // why it is well under the second ProcessManager allows, and
    // scripts/test-server-bootstrap.js reads that constant and refuses a delay
    // that is not.
    //
    // Unref'd so it cannot hold this process open on its own; the child's exit
    // handler above calls process.exit long before it matters.
    process.on('SIGTERM', () => {
        log('info', 'Received SIGTERM, shutting down...');
        server.kill('SIGTERM');
        const escalate = setTimeout(() => {
            log('warn', 'VS Code Server did not exit; sending SIGKILL');
            server.kill('SIGKILL');
        }, CHILD_KILL_AFTER_SIGTERM_MS);
        if (escalate.unref) escalate.unref();
    });
}
