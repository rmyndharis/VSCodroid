#!/usr/bin/env node
/**
 * VSCodroid Server Bootstrap
 * Launches VS Code Server (vscode-reh) with VSCodroid configuration.
 */

const path = require('path');
const fs = require('fs');
const os = require('os');
const crypto = require('crypto');

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

// The payload `callback.html` hands to the Android side, as it is built before a
// nonce is bound into it and as it is built afterwards. Both shapes are matched so
// the rewrite below is idempotent across restarts: on the second start the page on
// disk already carries the previous run's nonce, and a pattern that only knew the
// pristine form would silently stop binding. The nonce alternative is anchored to
// hex so it cannot run past the object it belongs to.
const CALLBACK_PAYLOAD = /JSON\.stringify\(\{ id: id, uri: uri(?:, nonce: '[0-9a-f]*')? \}\)/;

// Which external addresses open without the "Do you want VSCodroid to open the
// external website?" confirmation.
//
// github.com is not a convenience. The GitHub sign-in this build can run is the
// device-code flow, and it ends in env.openExternal("https://github.com/login/device"),
// so without this entry the one screen between a user and a signed-in editor is a
// confirmation dialog. Everything else the workbench opens keeps the prompt.
//
// Loopback is deliberately absent. The matcher answers for localhost, *.localhost,
// 127.0.0.1 and [::1] on any port before it ever consults this list, so a dev-server
// preview already opens without a prompt and an entry here would only look like it
// was doing the work.
//
// Written with the scheme, so a bare host cannot also match plain http.
const TRUSTED_LINK_DOMAINS = ['https://open-vsx.org', 'https://github.com'];

// What says the workbench page has already been given the list above, so a second
// start does not stack a second copy of the same script into it.
const TRUSTED_DOMAINS_MARKER = 'vscodroid-trusted-domains';

// The extensions the editor offers to install, and the reason this list exists at
// all rather than leaving people to search.
//
// Open VSX does not return ms-python.black-formatter for any text search, its own
// /api/-/search included, so the Extensions view cannot surface it however the
// query is phrased. Looking it up by identifier does return it, which is the route
// a recommendation takes, so a recommendation reaches an extension a search cannot.
// The one a search does return, mikoz.black-py, formats only once black has been
// installed separately with pip and stays silent when it has not.
//
// `languages` rather than a `**/*.py` glob: it also catches a file the editor knows
// is Python without the suffix saying so, and the workbench re-evaluates it when the
// language of an open file changes. `whenNotInstalled` names the extension itself so
// the offer stops once it is accepted, stated here rather than left to the
// notification service's own filtering, which is not measured.
const EXTENSION_RECOMMENDATIONS = {
    'ms-python.black-formatter': {
        onFileOpen: [
            {
                languages: ['python'],
                important: true,
                whenNotInstalled: ['ms-python.black-formatter'],
            },
        ],
    },
};

// What says the page already carries the recommendations. Separate from the marker
// above so either script can be added to a page that already has the other.
const RECOMMENDATIONS_MARKER = 'vscodroid-extension-recommendations';

/**
 * Replaces a file in one step, the way the product.json rewrite below does.
 *
 * This process is killed as a matter of routine, by the OOM killer and by
 * Android's phantom-process limit, so an in-place write interrupted partway
 * leaves a truncated file. rename(2) lands either side of a kill and never
 * inside it, and a write that cannot finish leaves the existing file untouched.
 */
function writeThroughRename(target, contents, mode) {
    const tmp = `${target}.${process.pid}.tmp`;
    try {
        fs.writeFileSync(tmp, contents, mode === undefined ? undefined : { mode });
        fs.renameSync(tmp, target);
    } catch (e) {
        try { fs.unlinkSync(tmp); } catch { /* nothing was written */ }
        throw e;
    }
}

/**
 * Adds one script to the workbench page, once. Answers whether it added it.
 *
 * Everything the page needs that `product.json` cannot carry arrives this way, so
 * the shape is shared rather than written out per caller. The caller supplies the
 * lines that read and mutate `settings`; the wrapper around them, the marker that
 * makes a second start a no-op, and the write are the same every time.
 *
 * The tag has to stay a BARE `<script>`: the server hashes exactly that shape out
 * of the page it has just built and puts the hashes into the Content-Security-Policy
 * it serves with it, so a tag carrying any attribute is one the page's own policy
 * then refuses to run.
 *
 * A page that is not there is not a page this can fix, and a missing server tree is
 * already a failed start and a build-time gate in verify-server-tree.py. A page that
 * is there but carries no configuration element is a tree this does not understand,
 * and that is worth reporting rather than passing over.
 */
function extendWorkbenchPage(pagePath, marker, lines) {
    const html = fs.existsSync(pagePath) ? fs.readFileSync(pagePath, 'utf8') : null;
    if (html === null || html.includes(marker)) {
        return false;
    }
    const anchor =
        '<meta id="vscode-workbench-web-configuration" data-settings="{{WORKBENCH_WEB_CONFIGURATION}}">';
    if (!html.includes(anchor)) {
        throw new Error('the workbench page does not carry the configuration element this extends');
    }
    const script = [
        '',
        '\t\t<script>',
        `\t\t\t/* ${marker} */`,
        '\t\t\t(function () {',
        "\t\t\t\tvar el = document.getElementById('vscode-workbench-web-configuration');",
        '\t\t\t\tif (!el) { return; }',
        '\t\t\t\ttry {',
        "\t\t\t\t\tvar settings = JSON.parse(el.getAttribute('data-settings'));",
        ...lines,
        "\t\t\t\t\tel.setAttribute('data-settings', JSON.stringify(settings));",
        '\t\t\t\t} catch (e) { /* a broken configuration is the workbench own report to make */ }',
        '\t\t\t})();',
        '\t\t</script>',
    ].join('\n');
    writeThroughRename(pagePath, html.replace(anchor, () => anchor + script));
    return true;
}

// How long the editor server gets to answer a SIGTERM before it is SIGKILLed.
// Bounded from outside: ProcessManager force-kills this process a second after
// sending the signal, so anything at or beyond that never runs at all.
const CHILD_KILL_AFTER_SIGTERM_MS = 700;

// Product configuration override. Applied with a shallow Object.assign, so each
// nested object here replaces the built one whole. One key below, nlsCoreBaseUrl,
// is built from the port, and why it has to be is written beside it; nothing else
// here depends on it, and the comment used to say that of the whole object.
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
    linkProtectionTrustedDomains: TRUSTED_LINK_DOMAINS,
    telemetryOptIn: false,
    enableTelemetry: false,
    // Where the page is told to fetch its translated interface strings.
    //
    // `out/server-main.js` appends `<commit>/<version>/<locale>/nls.messages.js`
    // to this and puts the result in a script tag, and hands the page an empty
    // src when the key is missing, which is why the interface used to be English
    // whatever language the device was in. The address is a path on the app's own
    // origin, and nothing serves it over HTTP: the Android WebView answers it
    // from the bundles in the APK. See VSCodroidWebViewClient.NLS_PATH_PREFIX,
    // which is the other half of this contract.
    //
    // Only ever requested when the locale does not start with "en", so an
    // English device never asks for it at all.
    //
    // A whole address rather than the bare path, because the same value is put
    // into the page's `script-src` Content-Security-Policy by the same function
    // that builds the script tag. A path is not a valid CSP source: the browser
    // drops that entry, says so in the console on every load, and the bundle
    // then loads only because 'self' happens to be in the list beside it. It is
    // this app's own origin either way, so naming it in full costs nothing and
    // stops the interface from silently falling back to English the day that
    // list gets stricter.
    nlsCoreBaseUrl: `http://${HOST}:${PORT}/_nls/`
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

    // English, and not because the device might not be: this variable cannot
    // decide the language whatever is written into it. `out/server-main.js`
    // resolves its own configuration with `userLocale` and `osLocale` hardcoded
    // to "en" and then assigns the result over this variable, before anything
    // reads it, so the value here reaches nothing. The node extension host is
    // not a way round it either: the server builds that child's environment
    // through `resolveNLSConfiguration`, which answers `resolvedLanguage: "en"`
    // without a `languagepacks.json`, so `vscode.env.language` and `vscode.l10n`
    // stay English there.
    //
    // An extension MANIFEST takes a different path and needs no language pack:
    // `RemoteExtensionsScannerService.scanExtensions` is keyed on the language
    // the client sends, and reads `package.nls.<language>.json` beside each
    // `package.json`. That is how the bundled extensions' commands, settings and
    // walkthrough are translated, and why the bundle names this app resolves are
    // also filenames those manifests have to match.
    //
    // What the device's language does reach is the page, which gets its strings
    // over HTTP (see nlsCoreBaseUrl above), so the interface is translated and
    // the strings this process logs are not. Removing this line is not the
    // cleanup it looks like: it predates the translations and the shape it
    // writes is what the loader expects if upstream ever stops overwriting it.
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

    // Bind the sign-in callback to a secret only this server's own page can know.
    //
    // The `vscodroid://callback` intent-filter is exported and BROWSABLE, so any
    // app on the device and any page in any browser can fire it. Everything the
    // Android side could check before this was guessable: the request id is a
    // counter the workbench starts at one per page, and the window around it is
    // ten minutes. A page that knew a sign-in was in flight could therefore forge
    // the callback, hand the signing-in extension an OAuth code of its choosing
    // with the confirmation prompt suppressed, and take the pending id with it so
    // the user's real callback was dropped.
    //
    // `callback.html` is served from this server's own origin, so no other origin
    // can read what is written into it. The nonce goes into the intent payload the
    // page builds and into a file inside the app sandbox; the Android side accepts
    // a callback only when the two match.
    //
    // Rewritten on every start, like product.json above and through the same
    // temporary file and rename: the value has to be new for each run, and the
    // pattern matches the page whether it is pristine or still carries the
    // previous run's nonce.
    //
    // The nonce file is removed first and written last, so no window exists in
    // which the page carries a secret the Android side cannot check. If any of
    // this fails there is simply no file, and the Android side falls back to the
    // matching it did before, which is what keeps a page this cannot rewrite from
    // costing the user their ability to sign in at all.
    const callbackHtmlPath = path.join(REH_DIR, 'out/vs/code/browser/workbench/callback.html');
    const noncePath = path.join(SERVER_DIR, 'auth-callback.nonce');
    try { fs.unlinkSync(noncePath); } catch { /* nothing to clear */ }
    try {
        const html = fs.readFileSync(callbackHtmlPath, 'utf8');
        if (!CALLBACK_PAYLOAD.test(html)) {
            throw new Error('the callback page does not build the payload this binds to');
        }
        const nonce = crypto.randomBytes(32).toString('hex');
        const bound = html.replace(
            CALLBACK_PAYLOAD,
            `JSON.stringify({ id: id, uri: uri, nonce: '${nonce}' })`
        );
        writeThroughRename(callbackHtmlPath, bound);
        writeThroughRename(noncePath, nonce, 0o600);
        log('info', 'Sign-in callbacks bound to this run');
    } catch (e) {
        log('error', `Could not bind the sign-in callback: ${e.message}`);
        log('error', 'Sign-in still works, but a callback is matched by request id alone.');
    }

    // Give the page the trusted-domain list, which the product.json rewrite above
    // cannot reach.
    //
    // That rewrite reaches THIS process's IProductService and stops there. The page
    // never reads the file: the product object is inlined into the workbench bundle
    // at build time, and the only product the server hands the page at runtime is a
    // three-key object that does not carry this list. So what the confirmation
    // dialog consults is branding/product.json as it stood at the last server build,
    // and widening it there alone would reach a device only after a ~30 minute
    // rebuild and a new server release.
    //
    // The workbench adds `additionalTrustedDomains` from its web construction
    // options to whatever the product lists, and those options are JSON in a meta
    // element of a page this server rebuilds FROM A TEMPLATE ON EVERY REQUEST. So a
    // script added to that template arrives with an ordinary app update, where
    // editing the bundle would not: /static is served `max-age=31536000` with no
    // ETag and no Last-Modified under a URL that does not change between runs, so a
    // WebView that has loaded this build once would keep its cached bundle for a
    // year and never see the edit. The document carries no caching headers at all.
    //
    // branding/product.json carries the same list for the next server build. After
    // it, this adds entries the page already has, which is a no-op by the membership
    // test below rather than by luck. How the script is inserted, and why it stays a
    // bare <script>, is at [extendWorkbenchPage].
    const workbenchHtmlPath = path.join(REH_DIR, 'out/vs/code/browser/workbench/workbench.html');
    try {
        const added = extendWorkbenchPage(workbenchHtmlPath, TRUSTED_DOMAINS_MARKER, [
            '\t\t\t\t\tvar trusted = settings.additionalTrustedDomains || [];',
            `\t\t\t\t\tvar wanted = ${JSON.stringify(TRUSTED_LINK_DOMAINS)};`,
            '\t\t\t\t\tfor (var i = 0; i < wanted.length; i++) {',
            '\t\t\t\t\t\tif (trusted.indexOf(wanted[i]) === -1) { trusted.push(wanted[i]); }',
            '\t\t\t\t\t}',
            '\t\t\t\t\tsettings.additionalTrustedDomains = trusted;',
        ]);
        if (added) {
            log('info', 'Trusted link domains given to the workbench page');
        }
    } catch (e) {
        log('error', `Could not widen the trusted link domains: ${e.message}`);
        log('error', 'External links still open, behind the confirmation dialog.');
    }

    // Give the page the extension recommendations, which reach it the same way and
    // for the same reason: the product the workbench consults is inlined into its
    // bundle at build time, and the three-key product this server hands the page at
    // runtime does not carry them.
    //
    // Merged under `productConfiguration` rather than set over it. The page deep
    // merges that object into the inlined product, so an entry added here joins the
    // built one instead of replacing it, and an identifier the page already carries
    // is left alone by the membership test below.
    //
    // Deliberately NOT also in branding/product.json, where the trusted-domain list
    // above does live. That list is read by this process too, through the
    // product.json rewrite; recommendations are read only by the page, so a build-time
    // copy would buy nothing and give the two places to drift. It would also have to
    // be added to the locked product.json key set that build-vscode-oss.sh checks.
    // The membership test still holds if a later build inlines them anyway.
    try {
        const added = extendWorkbenchPage(workbenchHtmlPath, RECOMMENDATIONS_MARKER, [
            '\t\t\t\t\tvar product = settings.productConfiguration || {};',
            '\t\t\t\t\tvar have = product.extensionRecommendations || {};',
            `\t\t\t\t\tvar wanted = ${JSON.stringify(EXTENSION_RECOMMENDATIONS)};`,
            '\t\t\t\t\tfor (var id in wanted) {',
            '\t\t\t\t\t\tif (!Object.prototype.hasOwnProperty.call(have, id)) { have[id] = wanted[id]; }',
            '\t\t\t\t\t}',
            '\t\t\t\t\tproduct.extensionRecommendations = have;',
            '\t\t\t\t\tsettings.productConfiguration = product;',
        ]);
        if (added) {
            log('info', 'Extension recommendations given to the workbench page');
        }
    } catch (e) {
        log('error', `Could not add the extension recommendations: ${e.message}`);
        log('error', 'The editor still works; a formatter is just never suggested.');
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
        // Stops the server pointing BROWSER at a script that cannot run.
        //
        // Without the flag the extension host is handed
        // BROWSER=<appRoot>/bin/helpers/browser.sh, and every Node helper that
        // opens a browser prefers $BROWSER over anything else. That script is a
        // shebang file under filesDir, which SELinux refuses to execve, and it
        // execs a `$ROOT/node` the packaged tree does not carry, so it could never
        // have worked. Leaving it set means the helpers stop at a dead end instead
        // of falling through to `xdg-open`, which is the name this build now
        // answers to through the execution trampoline.
        '--without-browser-env-var',
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
