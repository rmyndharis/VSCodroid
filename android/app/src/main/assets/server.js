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

// The tail of the intent address the same page navigates to, with or without the
// package a previous start pinned into it, for the same idempotence.
const CALLBACK_INTENT = /#Intent;scheme=vscodroid;(?:package=[A-Za-z0-9._]+;)?end/;

// Which external addresses open without the "Do you want VSCodroid to open the
// external website?" confirmation.
//
// github.com is not a convenience. The GitHub sign-in this build tries first is the
// device-code flow, and it ends in env.openExternal("https://github.com/login/device"),
// so without this entry the one screen between a user and a signed-in editor is a
// confirmation dialog. login.microsoftonline.com is the same case for the Microsoft
// sign-in, whose authorisation page is opened the same way. Everything else the
// workbench opens keeps the prompt.
//
// Loopback is deliberately absent. The matcher answers for localhost, *.localhost,
// 127.0.0.1 and [::1] on any port before it ever consults this list, so a dev-server
// preview already opens without a prompt and an entry here would only look like it
// was doing the work.
//
// Written with the scheme, so a bare host cannot also match plain http.
const TRUSTED_LINK_DOMAINS = ['https://open-vsx.org', 'https://github.com', 'https://login.microsoftonline.com'];

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

// What says the page already starts on the last theme; see the block that adds it.
const INITIAL_THEME_MARKER = 'vscodroid-initial-theme';

// The object the app gives the page to post the colour it paints itself with,
// PAGE_COLOR_OBJECT in VSCodroidWebView.kt; see the block that adds the script.
const PAGE_COLOR_OBJECT = 'vscodroidPageColor';

// The theme type for each base theme, as the workbench names it in its splash
// and in a class of its own element.
const SPLASH_THEME_TYPES = { 'vs': 'light', 'vs-dark': 'dark', 'hc-black': 'hcDark', 'hc-light': 'hcLight' };

// The splash colours the starting theme is given and a folder's record keeps,
// keyed to the colour id each is.
// The parts that fill the screen, and no more: everything else takes the registry
// default for the theme type until the real theme arrives a few seconds later.
const SPLASH_COLOR_IDS = {
    foreground: 'foreground',
    editorBackground: 'editor.background',
    titleBarBackground: 'titleBar.activeBackground',
    activityBarBackground: 'activityBar.background',
    sideBarBackground: 'sideBar.background',
    panelBackground: 'panel.background',
    statusBarBackground: 'statusBar.background',
    statusBarNoFolderBackground: 'statusBar.noFolderBackground',
};

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
 * The tag is written the way the page's own inline scripts are, because the policy
 * the server sends with the page trusts only that shape. From 1.138 each of them
 * carries `nonce="{{WORKBENCH_SCRIPT_NONCE}}"`, which the server fills with a fresh
 * nonce per request and names in `script-src`, and a script without it is refused.
 * Before that the server hashed every BARE `<script>` out of the page it had built,
 * so a tag carrying any attribute was the one refused.
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
    const nonce = '{{WORKBENCH_SCRIPT_NONCE}}';
    const open = html.includes(`<script nonce="${nonce}">`) ? `<script nonce="${nonce}">` : '<script>';
    const script = [
        '',
        `\t\t${open}`,
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
            // an uncaught throw here is a crash loop that ends on the page saying
            // the server could not be restarted, with no editor at all; the
            // server below will report the same file in its own terms, after this
            // line has already named it.
            log('error', `Could not apply the product configuration to ${productJsonPath}: ${e.message}`);
            log('error', 'A truncated product.json is repaired by the asset extraction that ' +
                'runs on the next app update, or by clearing app data.');
        }
    }

    // Pin the sign-in callback intent to this app's package.
    //
    // The `vscodroid://callback` scheme is one any installed app can declare, and
    // `callback.html` runs in the browser, so an unpinned intent resolves to every
    // app declaring it and the browser shows a chooser: picking the wrong entry
    // hands that app the provider's result. A debug and a release build installed
    // side by side declare it twice with nothing malicious involved.
    //
    // VSCODROID_PACKAGE comes from Environment.kt, so a debug build pins its own
    // `.debug` id. A missing or malformed value leaves the intent unpinned, which
    // is how it always was.
    //
    // What proves a callback is the browser's and not some other app's is no
    // longer written here. It used to be a per-run secret bound into this same
    // page, and `/callback` is answered before the connection-token check (patch
    // 0012), so a plain GET over loopback from another uid returned the page with
    // that secret in it, measured on an API 33 emulator. The secret is minted per
    // request by the workbench instead (patch 0019), travels out in the callback
    // URL the extension redirects to and comes back in its query; nothing serves
    // it.
    //
    // Rewritten on every start, like product.json above and through the same
    // temporary file and rename, and idempotent: the pattern matches the page
    // whether it is pristine or already pinned.
    const callbackHtmlPath = path.join(REH_DIR, 'out/vs/code/browser/workbench/callback.html');
    // An upgrade from a build that bound a secret into the page leaves that
    // secret on disk, where nothing reads it any more. Removed here rather than
    // left: it is the value those builds accepted a callback on, and an install
    // that keeps it keeps a live secret for a mechanism that is gone.
    try { fs.unlinkSync(path.join(SERVER_DIR, 'auth-callback.nonce')); } catch { /* already gone */ }
    try {
        const html = fs.readFileSync(callbackHtmlPath, 'utf8');
        const pkg = process.env.VSCODROID_PACKAGE || '';
        if (!/^[A-Za-z]\w*(\.[A-Za-z]\w*)+$/.test(pkg)) {
            throw new Error('VSCODROID_PACKAGE is missing or malformed');
        }
        if (!CALLBACK_INTENT.test(html)) {
            throw new Error('the callback page does not build the intent this pins');
        }
        writeThroughRename(
            callbackHtmlPath,
            html.replace(CALLBACK_INTENT, `#Intent;scheme=vscodroid;package=${pkg};end`)
        );
        log('info', 'Sign-in callback intent pinned to ' + pkg);
    } catch (e) {
        log('error', `Could not pin the sign-in callback intent: ${e.message}`);
        log('error', 'Sign-in still works; the intent may offer a chooser.');
    }

    // Give the page the trusted-domain list, which the product.json rewrite above
    // cannot reach.
    //
    // That rewrite reaches THIS process's IProductService and stops there. The page
    // never reads the file: the product object is inlined into the workbench bundle
    // at build time, and the only product the server hands the page at runtime is a
    // three-key object that does not carry this list. So what the confirmation
    // dialog consults is branding/product.json as it stood at the last server build,
    // and widening it there alone would reach a device only after a server
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
    // branding/product.json carries open-vsx.org and github.com for the next server
    // build, but not login.microsoftonline.com: a branding list the published tree
    // does not carry fails fetch-vscode-oss.sh, so that entry joins it with the next
    // server rebuild. Whatever the branding holds, this still adds every entry: the
    // membership test below sees only `additionalTrustedDomains`, which the server
    // never sets, and the workbench appends that list to the inlined one without
    // removing repeats. A repeated entry matches the same addresses, so it is
    // harmless, and the script can go only once the oldest server release shipped
    // carries every entry of TRUSTED_LINK_DOMAINS in its branding.
    // How the script is inserted, and which tag it has to use, is at
    // [extendWorkbenchPage].
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
    // A later build that inlined them anyway would not be seen by the membership
    // test, which reads only the page's settings; the deep merge would then write
    // the same entry over itself, which changes nothing.
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

    // Start every page load on the theme it is about to show.
    //
    // Two gaps. On every reload, folder switch and cold start the page paints
    // nothing of its own until the 18 MB workbench.js has loaded and applied a
    // theme, about a second on an API 36 emulator, and the WebView's own
    // background shows through, which VSCodroidWebView.configure makes the dark
    // window colour: right for a dark theme, wrong for a light one. And a load that
    // cannot use the theme the workbench stored starts on the web default, the
    // light one, for the one to three seconds until the extensions register. The
    // stored theme is the last window's, one per profile, and is dropped whenever
    // the theme this window is configured for is another: on a fresh install,
    // which has none stored; on the first load after an update that renamed the
    // configured default, which is read from a cache holding the old name until
    // then; in a folder whose own settings name another theme than the window
    // before it showed, once those settings are in the cache the workbench reads
    // them from at startup; and in any folder entered from one like that.
    //
    // The workbench records what it last painted in localStorage, the base theme
    // and the colours of each part, and nothing in the web page reads it back:
    // that splash is drawn only by the desktop bootstrap. It is one for the whole
    // app, the last window's, so this keeps records of its own in the same shape,
    // one for each folder or workspace the page's address opens: the theme that
    // window showed last, with its id, and whether it is a theme of the folder's
    // own. A folder with a theme of its own starts on its own record. Any other
    // starts on the record of the folder shown most recently that follows the
    // user's theme, which is that theme as it is now, where its own record is
    // stale once the user picks a theme in another folder. With no such record
    // yet, the workbench's splash is used. Whichever it is colours the root
    // before the first paint.
    //
    // Whether a folder has a theme of its own is read from what the workbench
    // shows, against the record of the window before. A theme is told by its id,
    // which the workbench writes as two classes of its element, the base theme
    // and the one after it, and not by its colours: a folder's own settings can
    // customize those under the user's theme, as Peacock does with the title,
    // activity and status bars, and two themes can share all of them, as Dark+
    // and Visual Studio Dark do. A theme the workbench makes up, when it cannot
    // use the one it stored, has only the base theme for an id, so the class
    // after it is one of the workbench's own and matches no theme. Entered from a
    // window that follows the user's theme, the folder has one of its own when
    // the workbench shows another theme than that window did, read again at each
    // one it shows until the user does something: first the one it starts on,
    // which is that window's stored theme only when the folder is configured for
    // the same one, then the one it is configured for, which a folder's own
    // settings name at startup only from its second load on, and on its first
    // only once its settings file has been read, which on an API 36 emulator
    // came seconds after the extensions registered. From the second load on the
    // workbench keeps the copy it cached until the file has been read (patch
    // 0026); before that it showed the user's theme in that time, and a tap then
    // left the mark wrong. The first tap or key press after the extensions
    // register ends the reading, because a theme change is then the user's pick
    // and moves the record with it; taps while the page loads do not count.
    // Nothing else sets or clears the mark: not a load entered from a folder with
    // a theme of its own, whose stored theme says nothing about whether this
    // folder follows the user's; not the first load this script runs, which has no
    // record of a window before; not a load after the device switched between
    // light and dark, or while it does; and the empty window, which has no folder
    // settings, always follows.
    //
    // The same theme is handed to the workbench as `initialColorTheme`, which it
    // uses only when its stored theme is unusable: to a folder with a theme of its
    // own; to any folder entered from one, when the stored theme is that folder's
    // and not the user's; on the first load this script runs, when after this
    // update the stored theme is usually unusable only because the cached default
    // still spells it the old way, so the splash, the last window's theme, is
    // right unless that window had a theme of its own and this load opens another
    // folder, where a cold start reopens that one; and, with no record or splash
    // at all, which is only ever the first seconds of a fresh install, as the dark
    // default this app configures. Any other folder entered from a window that
    // follows the user's theme is handed nothing. That window's stored theme, once
    // it has stored one, is right for every folder that follows the user's too, so
    // the workbench would use a handed theme only in a folder with a theme of its
    // own not yet marked, and the user's is the one theme such a folder is known
    // not to show; it starts on its own default instead, as upstream does. Hex
    // colours only, because the workbench parses each one with Color.fromHex,
    // which turns anything else into red, and a theme can hold a translucent
    // colour, written as rgba(). Always with a colours object, an empty one
    // included: without it the workbench colours the theme it starts on from the
    // light map of "Light 2026", the web build's own default, which is the
    // setting's value until the extensions register.
    //
    // Nothing is handed over when the device has switched between light and dark
    // since the last load. With window.autoDetectColorScheme on, the stored theme
    // is unusable on exactly that load because the workbench is following the
    // device, and the type it picks for itself is then the right one, where the
    // record holds the other. The page cannot read that setting, so the switch is
    // what this goes by, and each load records the device's mode for the next one
    // to compare. The root is still coloured from the record, which is right for
    // everyone the setting does not apply to. The first load this script runs has
    // no mode to compare, so with the setting on and the device switched since the
    // last session, it starts on the old mode's theme until the extensions
    // register.
    //
    // The root shows again after the first paint. When the window grows, as it
    // does when the soft keyboard goes down, the space given back shows the root
    // until the workbench has laid itself out again, a tenth of a second or more
    // on an API 36 emulator: the usual case when a folder is opened from a box
    // that had the keyboard up. So the root follows the theme the workbench shows
    // rather than keeping the one the page started on, and the record is taken
    // from the same reading. Where the page has not painted at the new size at
    // all, as below the last frame that a reload or a folder opened that way
    // holds until the next page paints, what shows is the window behind the
    // view, so each colour the root is given is also posted to the app, which
    // gives that window the colour and the bars on it icons that read on it
    // (addPageColorListener in VSCodroidWebView.kt). A theme change rewrites a
    // style element in the head, so each change to the head reads the
    // workbench's colours again. So does every change of the window title,
    // which the workbench makes on each editor switch and change of dirty state,
    // at the cost of that style read: storage is written on a load's first
    // reading and then only when the theme changed, and a colour is posted only
    // when it changed. Taking the colour as the page is left was tried and is
    // too late: the keyboard starts to go down before the workbench navigates.
    //
    // Twenty folders are kept, the most recently shown, so the record cannot grow
    // into the storage the workbench's sealed secrets share. Loads that still
    // start on the wrong colour: the first load of a folder with a theme of its
    // own, which has no record yet; the first load after this update, when it
    // opens another folder than the last window and that window had a theme of
    // its own, as above; a load after the device switched, as above, which is
    // handed nothing with the setting off as well, so where the workbench cannot
    // use its stored theme, in a folder with a theme of its own or one entered
    // from it, it starts on the web default, the light one; on a fresh install, a
    // load that replaces the first one before that one's extensions have
    // registered, which finds the record of the dark default the first one
    // started on, is handed nothing, starts on the light web default because no
    // theme is stored yet, and is taken to have a theme of its own; and those
    // around a mark this cannot read. A folder with a theme of its own that is
    // the first load this script runs, that is opened for the first time right
    // after another folder with a theme of its own, or that is tapped on its
    // first load after the extensions register and before its settings file has
    // been read, is taken to follow the user's theme, and the
    // folder opened after it to have a theme of its own. Each wrong mark is put
    // right the next time its folder is entered from one that follows the user's
    // theme. A record keeps the colours its folder showed, that folder's own
    // customizations included, so a load started on another folder's record
    // starts on those too: the blank page takes its editor colour, and a
    // workbench that takes the record as its starting theme shows the parts that
    // folder coloured, Peacock's bars among them, in its colours until the
    // extensions register.
    //
    // Reading the folder's own settings would put some of those loads right, and
    // is not done. This file does not serve the page: it adds this script to the
    // template once, and the editor server fills the template on each request,
    // so only the page knows the folder; it could fetch the folder's
    // settings.json through /vscode-remote-resource. What the load starts on is
    // still the workbench's choice. Its configuration starts on its own cached
    // copy of a remote folder's settings, empty until the folder has been opened
    // once, and turns to the file only once the remote file system has
    // registered and the file has been read.
    // The theme service reads that configuration as the workbench starts, in the
    // task that marks code/willStartWorkbench, and in eight loads measured on an
    // API 36 emulator the file system registered 385 to 713 ms after that mark.
    // So on a folder's first load the workbench finds the user's theme
    // configured. Entered from a window that follows the user's theme, it keeps
    // that window's stored theme whatever the page hands it, and colouring the
    // root from the file would give that load three colours where it now has
    // two: the folder's until the workbench draws, the user's until the
    // workbench has read the file, and the folder's again. Entered from a window
    // with a theme of its own, the stored theme is not the user's, so the workbench
    // takes initialColorTheme, and a type read from the file would start the
    // load on the folder's type where it now starts on the user's. Reading the
    // file would also mark a folder from its settings rather than from what the
    // workbench shows. Neither is a small change. The start needs a request the
    // page waits for, because the workbench reads initialColorTheme once, at
    // startup; a reader for the comments and trailing commas settings.json
    // allows, and for the settings of a workspace file; and a map from each
    // installed theme's name, old names included, to its type, which a script
    // written into the page once per update cannot keep current as themes are
    // installed. A type alone would still start on that type's default colours
    // rather than the folder's theme. The mark needs that request on every load,
    // because a folder's settings can change between loads. What the two would
    // put right is a folder with a theme of its own opened for the first time
    // right after another, and the wrong marks above, each of which puts itself
    // right.
    //
    // Anything that throws in here leaves the page as upstream ships it.
    try {
        const added = extendWorkbenchPage(workbenchHtmlPath, INITIAL_THEME_MARKER, [
            '\t\t\t\t\tvar query = new URLSearchParams(location.search);',
            "\t\t\t\t\tvar where = query.get('folder') || query.get('workspace') || '';",
            "\t\t\t\t\tvar seen = JSON.parse(localStorage.getItem('vscodroid-folder-themes')) || {};",
            '\t\t\t\t\tvar keys = Object.keys(seen), prev = seen[keys[keys.length - 1]], mine = seen[where];',
            '\t\t\t\t\tvar user = seen[keys.filter(function (k) { return !seen[k].own; }).pop()];',
            '\t\t\t\t\tvar own = !!(mine && mine.own);',
            "\t\t\t\t\tvar shared = JSON.parse(localStorage.getItem('monaco-parts-splash'));",
            '\t\t\t\t\tvar splash = (own ? mine : user || shared) || {};',
            '\t\t\t\t\tvar info = splash.colorInfo || {};',
            `\t\t\t\t\tvar types = ${JSON.stringify(SPLASH_THEME_TYPES)}, type = types[splash.baseTheme] || 'dark';`,
            `\t\t\t\t\tvar ids = ${JSON.stringify(SPLASH_COLOR_IDS)};`,
            '\t\t\t\t\tvar hex = /^#[0-9a-f]{6}([0-9a-f]{2})?$/i;',
            "\t\t\t\t\tvar mode = function () { return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'; };",
            "\t\t\t\t\tvar scheme = mode(), last = localStorage.getItem('vscodroid-device-scheme');",
            "\t\t\t\t\tlocalStorage.setItem('vscodroid-device-scheme', scheme);",
            "\t\t\t\t\tvar blank = type === 'light' || type === 'hcLight' ? '#ffffff' : '#1e1e1e';",
            `\t\t\t\t\tvar sink = window.${PAGE_COLOR_OBJECT}, told;`,
            '\t\t\t\t\tvar paint = function (color) {',
            '\t\t\t\t\t\tdocument.documentElement.style.backgroundColor = color;',
            '\t\t\t\t\t\tif (sink && color !== told) { told = color; sink.postMessage(color); }',
            '\t\t\t\t\t};',
            '\t\t\t\t\tpaint(hex.test(info.background) ? info.background : blank);',
            '\t\t\t\t\tif (!settings.initialColorTheme && (last === null || (last === scheme && (own || (prev && prev.own) || !(user || shared))))) {',
            '\t\t\t\t\t\tvar colors = {};',
            '\t\t\t\t\t\tfor (var key in ids) { if (hex.test(info[key])) { colors[ids[key]] = info[key]; } }',
            '\t\t\t\t\t\tsettings.initialColorTheme = { themeType: type, colors: colors };',
            '\t\t\t\t\t}',
            '\t\t\t\t\tvar acted, written;',
            "\t\t\t\t\tvar act = function () { acted = acted || performance.getEntriesByName('code/didLoadExtensions').length > 0; };",
            "\t\t\t\t\taddEventListener('pointerdown', act, true);",
            "\t\t\t\t\taddEventListener('keydown', act, true);",
            '\t\t\t\t\tnew MutationObserver(function () {',
            "\t\t\t\t\t\tvar wb = document.querySelector('.monaco-workbench');",
            '\t\t\t\t\t\tvar style = wb && getComputedStyle(wb);',
            "\t\t\t\t\t\tvar now = style ? style.getPropertyValue('--vscode-editor-background').trim() : '';",
            '\t\t\t\t\t\tif (!now) { return; }',
            '\t\t\t\t\t\tpaint(now);',
            '\t\t\t\t\t\tvar base = Object.keys(types).filter(function (t) { return wb.classList.contains(t); })[0];',
            '\t\t\t\t\t\tvar classes = [].slice.call(wb.classList), at = classes.indexOf(base);',
            "\t\t\t\t\t\tvar shown = { baseTheme: base, theme: classes.slice(at, at + 2).join(' '), colorInfo: { background: now } };",
            "\t\t\t\t\t\tfor (var key in ids) { shown.colorInfo[key] = style.getPropertyValue('--vscode-' + ids[key].replace(/\\./g, '-')).trim(); }",
            '\t\t\t\t\t\tvar entry = JSON.stringify(shown);',
            '\t\t\t\t\t\tif (entry === written) { return; }',
            '\t\t\t\t\t\tif (!acted && last === mode() && prev && !prev.own) { own = !!where && shown.theme !== prev.theme; }',
            '\t\t\t\t\t\twritten = entry;',
            '\t\t\t\t\t\tif (own) { shown.own = true; }',
            '\t\t\t\t\t\tdelete seen[where];',
            '\t\t\t\t\t\tseen[where] = shown;',
            '\t\t\t\t\t\tvar kept = Object.keys(seen);',
            '\t\t\t\t\t\tif (kept.length > 20) { delete seen[kept[0]]; }',
            "\t\t\t\t\t\ttry { localStorage.setItem('vscodroid-folder-themes', JSON.stringify(seen)); } catch (e) { /* full: only the record is lost */ }",
            '\t\t\t\t\t}).observe(document.head, { childList: true, subtree: true });',
        ]);
        if (added) {
            log('info', 'The workbench page starts on the last theme');
        }
    } catch (e) {
        log('error', `Could not give the workbench page its starting theme: ${e.message}`);
        log('error', 'The editor still works; a page load may show another theme for a moment first.');
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

    // platform-fix.js is in NODE_OPTIONS, which is how this process and the
    // server get it, but VS Code deletes NODE_OPTIONS from the extension host's
    // environment before starting it. The extension host takes the server's
    // execArgv instead, so the preload rides there as well; it is what lets the
    // Jupyter extension signal the processes its kernels started. Not loaded
    // here first the way dns-proxy.js is: NODE_OPTIONS already preloaded this
    // same file into this process, so one that does not load has stopped the
    // app before this line runs.
    execArgv.push(`--require=${path.join(SERVER_DIR, 'platform-fix.js')}`);

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
