#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
SERVER_DIR="$ROOT_DIR/server"
JNILIBS_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"

# VS Code 1.96.4 — last stable release using Node 20 (Electron 32)
VSCODE_VERSION="${VSCODE_VERSION:-1.96.4}"
ARCH="arm64"
QUALITY="stable"

# Microsoft's VS Code Server download URL
# This is the combined reh-web target (server + web client in one)
DOWNLOAD_URL="https://update.code.visualstudio.com/${VSCODE_VERSION}/server-linux-${ARCH}-web/${QUALITY}"
ARCHIVE_NAME="vscode-server-linux-${ARCH}-web.tar.gz"

echo "=== Downloading VS Code Server ${VSCODE_VERSION} (linux-${ARCH}-web) ==="

mkdir -p "$SERVER_DIR"
cd "$SERVER_DIR"

# Download
if [ ! -f "$ARCHIVE_NAME" ]; then
    echo "Downloading from Microsoft CDN..."
    echo "  URL: $DOWNLOAD_URL"
    curl -L --fail --show-error -o "$ARCHIVE_NAME" "$DOWNLOAD_URL"
    echo "  Downloaded: $(du -sh "$ARCHIVE_NAME" | cut -f1)"
else
    echo "Archive already downloaded: $ARCHIVE_NAME"
fi

# Extract
echo ""
echo "Extracting..."
rm -rf vscode-reh
mkdir -p vscode-reh
tar xzf "$ARCHIVE_NAME" -C vscode-reh --strip-components=1
echo "  Extracted: $(du -sh vscode-reh | cut -f1)"

# Verify entry point exists
if [ ! -f "vscode-reh/out/server-main.js" ]; then
    # Try alternative entry point location
    if [ -f "vscode-reh/server.js" ]; then
        echo "  Found server.js entry point (alternative layout)"
    else
        echo "ERROR: Could not find server entry point"
        echo "Contents of vscode-reh/:"
        ls -la vscode-reh/
        [ -d "vscode-reh/out" ] && ls vscode-reh/out/ | head -20
        exit 1
    fi
fi

# Patch product.json for VSCodroid
echo ""
echo "Patching product.json..."
PRODUCT_JSON="vscode-reh/product.json"
if [ -f "$PRODUCT_JSON" ]; then
    python3 - "$PRODUCT_JSON" <<'PYEOF'
import json, sys

path = sys.argv[1]
with open(path, 'r') as f:
    product = json.load(f)

# VSCodroid branding
product['nameShort'] = 'VSCodroid'
product['nameLong'] = 'VSCodroid'
product['applicationName'] = 'vscodroid'
product['dataFolderName'] = '.vscodroid'
product['serverApplicationName'] = 'vscodroid-server'
product['serverDataFolderName'] = '.vscodroid-server'

# Open VSX marketplace (replace Microsoft Marketplace)
product['extensionsGallery'] = {
    'serviceUrl': 'https://open-vsx.org/vscode/gallery',
    'itemUrl': 'https://open-vsx.org/vscode/item',
    'resourceUrlTemplate': 'https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}',
    'controlUrl': '',
    'nlsBaseUrl': ''
}

# Trusted domains
product['linkProtectionTrustedDomains'] = [
    'https://open-vsx.org'
]

# Disable telemetry
product['telemetryOptIn'] = False
product['enableTelemetry'] = False
product['showTelemetryOptOut'] = False

# VSCodroid default settings
# - Disable Workspace Trust (all workspaces are local/app-private)
product['configurationDefaults'] = product.get('configurationDefaults', {})
product['configurationDefaults']['security.workspace.trust.enabled'] = False

# Remove Microsoft-specific services
for key in ['msftInternalDomains', 'sendASmile', 'documentationUrl',
            'releaseNotesUrl', 'keyboardShortcutsUrlMac', 'keyboardShortcutsUrlLinux',
            'keyboardShortcutsUrlWin', 'introductoryVideosUrl', 'tipsAndTricksUrl',
            'newsletterSignupUrl', 'updateUrl', 'checksumUrl']:
    product.pop(key, None)

with open(path, 'w') as f:
    json.dump(product, f, indent=2)

print(f"  Patched: {product['nameShort']} with Open VSX marketplace")
PYEOF
else
    echo "  WARNING: product.json not found, creating minimal one"
    cat > "$PRODUCT_JSON" <<'JSON'
{
  "nameShort": "VSCodroid",
  "nameLong": "VSCodroid",
  "applicationName": "vscodroid",
  "dataFolderName": ".vscodroid",
  "quality": "stable",
  "extensionsGallery": {
    "serviceUrl": "https://open-vsx.org/vscode/gallery",
    "itemUrl": "https://open-vsx.org/vscode/item",
    "resourceUrlTemplate": "https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}"
  },
  "telemetryOptIn": false,
  "enableTelemetry": false,
  "showTelemetryOptOut": false,
  "configurationDefaults": {
    "security.workspace.trust.enabled": false
  }
}
JSON
fi

# Patch vsda signing validation
# VS Code uses a native binary (vsda.node) for client-server handshake validation.
# The native binary is Linux x86_64 and won't work on Android ARM64.
# The WASM fallback is encrypted. We bypass the validation entirely since
# client and server run on the same device (localhost only, no security risk).
echo ""
echo "Patching vsda signing validation..."
WORKBENCH_JS="vscode-reh/out/vs/code/browser/workbench/workbench.js"
EXTHOST_JS="vscode-reh/out/vs/workbench/api/node/extensionHostProcess.js"

python3 - "$WORKBENCH_JS" "$EXTHOST_JS" <<'PYEOF'
import sys, re

files_patched = 0
for filepath in sys.argv[1:]:
    try:
        with open(filepath, 'r') as f:
            content = f.read()
    except FileNotFoundError:
        print(f"  WARNING: {filepath} not found, skipping")
        continue

    # Match pattern: !await <func>(x.signService.validate(...),y)
    # Replace with: !await <func>(Promise.resolve(true),y)
    pattern = r'!await (\w+)\(\w+\.signService\.validate\([^)]+\),(\w+)\)'
    replacement = r'!await \1(Promise.resolve(true),\2)'
    new_content, count = re.subn(pattern, replacement, content)

    if count > 0:
        with open(filepath, 'w') as f:
            f.write(new_content)
        print(f"  Patched: {filepath} ({count} replacement(s))")
        files_patched += 1
    else:
        print(f"  SKIP: Pattern not found in {filepath} (already patched?)")

print(f"  vsda bypass: {files_patched} file(s) patched")
PYEOF

# Disable Workspace Trust (Restricted Mode)
# VS Code forces workspace trust for remote connections (remoteAuthority),
# ignoring the security.workspace.trust.enabled setting. Since all workspaces
# in VSCodroid are local/app-private, we patch the trust check to always return true.
echo ""
echo "Patching workspace trust (disable Restricted Mode)..."
python3 - "$WORKBENCH_JS" <<'PYEOF'
import sys
path = sys.argv[1]
with open(path, 'r') as f:
    content = f.read()
old = 'isWorkspaceTrusted(){return this.t}'
new = 'isWorkspaceTrusted(){return!0}'
count = content.count(old)
if count > 0:
    content = content.replace(old, new)
    with open(path, 'w') as f:
        f.write(content)
    print(f"  Patched: {path} ({count} replacement(s))")
else:
    print(f"  SKIP: Pattern not found in {path} (already patched?)")
PYEOF

# Patch Extension Host to use worker_thread instead of child_process.fork()
# Android 12+ has a 32 phantom process limit. Each fork() counts as a phantom.
# Worker threads run inside the parent process, so they don't count.
# Three patches to server-main.js:
#   1. Force named pipe mode (ExtHost connects via Unix socket, not process.send() IPC)
#   2. Replace fork() with Worker() (same interface, runs as thread not process)
#   3. Guard .send() call (Workers don't have IPC channel)
echo ""
echo "Patching Extension Host to use worker_thread..."
SERVER_MAIN_JS="vscode-reh/out/server-main.js"
python3 - "$SERVER_MAIN_JS" <<'PYEOF'
import sys

path = sys.argv[1]
with open(path, 'r') as f:
    content = f.read()

original = content
patches = 0

# Patch 1: Force named pipe mode for ExtHost connection
old = 'this.c=!me||!this.n.args["socket-path"]'
new = 'this.c=!1'
count = content.count(old)
if count == 1:
    content = content.replace(old, new)
    patches += 1
    print(f"  Patch 1 (named pipe mode): OK")
else:
    print(f"  Patch 1 (named pipe mode): SKIP (found {count}x, expected 1)")

# Patch 2: Replace fork() with Worker()
old = 'this.h=QC.fork(jt.asFileUri("bootstrap-fork").fsPath,o,r)'
new = 'this.h=await(async function(f,a,opts){var W=(await import("worker_threads")).Worker;var ea=opts.execArgv||[];var rl={};ea=ea.filter(function(x){var m=x.match(/^--max-old-space-size=(\\d+)/);if(m){rl.maxOldGenerationSizeMb=+m[1];return false}return!/^--max-semi-space-size/.test(x)});var w=new W(f,{argv:a,env:opts.env,execArgv:ea,resourceLimits:rl,stdout:true,stderr:true});w.pid=w.threadId;w._killed=false;var _on=w.on.bind(w);w.on=function(ev,fn){if(ev==="exit"){return _on(ev,function(code){w.connected=false;fn(w._killed?0:code,w._killed?"SIGTERM":null)})}if(ev==="error"){return _on(ev,fn)}return _on(ev,fn)};w.on("error",function(e){});w.kill=function(){w._killed=true;w.connected=false;try{w.postMessage({__type:"__vsc_disconnect"})}catch(e){}setTimeout(function(){try{w.terminate()}catch(e){}},200)};w.send=function(m){if(!w.connected)return false;try{w.postMessage(m);return true}catch(e){w.connected=false;return false}};w.connected=true;w.disconnect=function(){w.connected=false};return w})(jt.asFileUri("bootstrap-fork").fsPath,o,r)'
count = content.count(old)
if count == 1:
    content = content.replace(old, new)
    patches += 1
    print(f"  Patch 2 (fork->Worker): OK")
else:
    print(f"  Patch 2 (fork->Worker): SKIP (found {count}x, expected 1)")

# Patch 3: Guard .send() call (Workers lack IPC channel)
old = 'this.h.send(t)'
new = 'this.h.send&&this.h.send(t)'
count = content.count(old)
if count == 1:
    content = content.replace(old, new)
    patches += 1
    print(f"  Patch 3 (guard .send()): OK")
else:
    print(f"  Patch 3 (guard .send()): SKIP (found {count}x, expected 1)")

if content != original:
    with open(path, 'w') as f:
        f.write(content)
    print(f"  ExtHost worker_thread: {patches}/3 patches applied")
else:
    print(f"  ExtHost worker_thread: no changes (already patched?)")
PYEOF

# Patch bootstrap-fork.js to be Worker-aware (IPC bridge)
# bootstrap-fork.js is the entry point loaded by both fork() AND Worker().
# By prepending Worker detection + IPC bridge code at the top, all downstream
# modules (ptyHostMain.js etc.) get working process.send / process.on('message')
# / process.once('disconnect') for free when running as a worker_thread.
# In a fork: isMainThread=true, parentPort=null -> bridge code is a no-op.
echo ""
echo "Patching bootstrap-fork.js for Worker IPC bridge..."
BOOTSTRAP_FORK_JS="vscode-reh/out/bootstrap-fork.js"
python3 - "$BOOTSTRAP_FORK_JS" <<'PYEOF'
import sys
path = sys.argv[1]
with open(path, 'r') as f:
    content = f.read()

bridge = 'import{isMainThread as __iMT,parentPort as __pP}from"worker_threads";if(!__iMT&&__pP){process.connected=true;process.send=function(m){try{__pP.postMessage(m);return true}catch(e){process.connected=false;return false}};process.disconnect=function(){process.connected=false;process.emit("disconnect")};__pP.on("message",function(m){if(m&&m.__type==="__vsc_disconnect"){process.disconnect();return}process.emit("message",m)});}\n'

if content.startswith(bridge[:40]):
    print(f"  SKIP: {path} already has Worker IPC bridge")
else:
    content = bridge + content
    with open(path, 'w') as f:
        f.write(content)
    print(f"  Patched: {path} (Worker IPC bridge prepended)")
PYEOF

# Patch ptyHost to run as worker_thread instead of child_process.fork()
# The rg IPC class (used by both ptyHost and fileWatcher) uses fork() by default.
# We conditionally create a Worker instead when serverName==="Pty Host".
# This saves 1 phantom process slot on Android 12+.
# Two patches to server-main.js:
#   1. Add Worker import alongside fork import
#   2. Replace fork with Worker for Pty Host (with graceful disconnect + normalized exit)
echo ""
echo "Patching ptyHost to use worker_thread..."
python3 - "$SERVER_MAIN_JS" <<'PYEOF'
import sys

path = sys.argv[1]
with open(path, 'r') as f:
    content = f.read()

original = content
patches = 0

# Patch 1: Add Worker import alongside fork import
old = 'import{fork as XO}from"child_process"'
new = 'import{fork as XO}from"child_process";import{Worker as __VW}from"worker_threads"'
count = content.count(old)
# Check if already patched (has __VW import)
if 'import{Worker as __VW}from"worker_threads"' in content:
    print(f"  Patch 1 (Worker import): SKIP (already present)")
elif count == 1:
    content = content.replace(old, new)
    patches += 1
    print(f"  Patch 1 (Worker import): OK")
else:
    print(f"  Patch 1 (Worker import): SKIP (found {count}x, expected 1)")

# Patch 2: Replace fork with Worker for Pty Host
old2 = 'this.d=XO(this.i,e,t)'
new2 = 'this.d=this.j&&this.j.serverName==="Pty Host"?(function(p,a,o){var ea=(o.execArgv||[]).filter(function(x){return!/^--max-old-space-size/.test(x)&&!/^--max-semi-space-size/.test(x)});var rl={maxOldGenerationSizeMb:256};var w=new __VW(p,{argv:a,env:o.env,execArgv:ea,resourceLimits:rl,stdout:true,stderr:true});w.pid=w.threadId;w._killed=false;var _on=w.on.bind(w);w.on=function(ev,fn){if(ev==="exit"){return _on(ev,function(code){w.connected=false;fn(w._killed?0:code,w._killed?"SIGTERM":null)})}return _on(ev,fn)};w.kill=function(){w._killed=true;w.connected=false;try{w.postMessage({__type:"__vsc_disconnect"})}catch(e){}setTimeout(function(){try{w.terminate()}catch(e){}},200)};w.send=function(m){if(!w.connected)return false;try{w.postMessage(m);return true}catch(e){w.connected=false;return false}};w.connected=true;w.disconnect=function(){w.connected=false};return w})(this.i,e,t):XO(this.i,e,t)'
count = content.count(old2)
# Check if already patched
if 'this.j.serverName==="Pty Host"' in content:
    print(f"  Patch 2 (ptyHost Worker): SKIP (already present)")
elif count == 1:
    content = content.replace(old2, new2)
    patches += 1
    print(f"  Patch 2 (ptyHost Worker): OK")
else:
    print(f"  Patch 2 (ptyHost Worker): SKIP (found {count}x, expected 1)")

if content != original:
    with open(path, 'w') as f:
        f.write(content)
    print(f"  ptyHost worker_thread: {patches}/2 patches applied")
else:
    print(f"  ptyHost worker_thread: no changes (already patched?)")
PYEOF

# Replace native spdlog with JS shim
echo ""
echo "Patching spdlog native module..."
SPDLOG_INDEX="vscode-reh/node_modules/@vscode/spdlog/index.js"
if [ -f "$SPDLOG_INDEX" ]; then
    cat > "$SPDLOG_INDEX" <<'JSEOF'
// VSCodroid: Pure JS shim replacing native spdlog.node
const path = require('path');
const fs = require('fs');
exports.version = '1.0.0-vscodroid-shim';
const LEVEL = { TRACE: 0, DEBUG: 1, INFO: 2, WARN: 3, ERROR: 4, CRITICAL: 5, OFF: 6 };
let globalLevel = LEVEL.DEBUG;
exports.setLevel = function (level) { globalLevel = level; };
exports.setFlushOn = function () {};
class Logger {
    constructor(type, name, filepath, maxFileSize, maxFiles) {
        this._name = name; this._filepath = filepath; this._level = globalLevel; this._stream = null;
        try { fs.mkdirSync(path.dirname(filepath), { recursive: true }); this._stream = fs.createWriteStream(filepath, { flags: 'a' }); } catch (e) {}
    }
    _log(level, msg) { if (level < this._level || !this._stream) return; this._stream.write(`[${new Date().toISOString()}] [${this._name}] ${msg}\n`); }
    trace(msg) { this._log(LEVEL.TRACE, msg); } debug(msg) { this._log(LEVEL.DEBUG, msg); } info(msg) { this._log(LEVEL.INFO, msg); }
    warn(msg) { this._log(LEVEL.WARN, msg); } error(msg) { this._log(LEVEL.ERROR, msg); } critical(msg) { this._log(LEVEL.CRITICAL, msg); }
    getLevel() { return this._level; } setLevel(level) { this._level = level; } flush() {} drop() { if (this._stream) { this._stream.end(); this._stream = null; } }
    clearFormatters() {} setPattern() {} setAsyncMode() {}
}
exports.Logger = Logger;
async function createLogger(type, name, filepath, maxFileSize, maxFiles) {
    fs.mkdirSync(path.dirname(filepath), { recursive: true }); return new Logger(type, name, filepath, maxFileSize, maxFiles);
}
exports.createRotatingLogger = function (name, fp, sz, n) { return createLogger('rotating', name, fp, sz, n); };
exports.createAsyncRotatingLogger = function (name, fp, sz, n) { return createLogger('rotating_async', name, fp, sz, n); };
JSEOF
    echo "  Replaced spdlog with JS shim"
fi

# Replace native-watchdog with JS shim
echo ""
echo "Patching native-watchdog module..."
WATCHDOG_INDEX="vscode-reh/node_modules/native-watchdog/index.js"
if [ -f "$WATCHDOG_INDEX" ]; then
    cat > "$WATCHDOG_INDEX" <<'JSEOF'
// VSCodroid: Pure JS shim replacing native watchdog.node
// Process monitoring is handled by Kotlin ProcessManager on Android.
var hasStarted = false;
exports.start = function(pid) {
    if (typeof pid !== 'number' || Math.round(pid) !== pid) {
        throw new Error('Expected integer pid!');
    }
    if (hasStarted) {
        throw new Error('Can only monitor a single process!');
    }
    hasStarted = true;
};
exports.exit = function(code) {
    process.exit(code || 0);
};
JSEOF
    echo "  Replaced native-watchdog with JS shim"
fi

# Replace Linux node-pty binary with Android ARM64 build
# The pre-built pty.node is compiled for Linux glibc and won't load on Android Bionic.
# Our build-node-pty.sh cross-compiles node-pty with NDK for Android ARM64.
echo ""
echo "Setting up node-pty native module..."
NODE_PTY_DIR="vscode-reh/node_modules/node-pty"
if [ -d "$NODE_PTY_DIR" ]; then
    # Remove Linux binary (won't work on Android Bionic)
    rm -f "$NODE_PTY_DIR/build/Release/pty.node"
    # Remove pipeTerminal.js shim if it exists (from previous builds)
    rm -f "$NODE_PTY_DIR/lib/pipeTerminal.js"
    # The Android ARM64 pty.node is pre-built by scripts/build-node-pty.sh
    # and lives in the assets directory. Use ROOT_DIR (absolute) to avoid
    # subshell cd failures when the directory doesn't exist yet (CI).
    ANDROID_PTY_NODE="$ROOT_DIR/android/app/src/main/assets/vscode-reh/node_modules/node-pty/build/Release/pty.node"
    if [ -f "$ANDROID_PTY_NODE" ]; then
        mkdir -p "$NODE_PTY_DIR/build/Release"
        cp "$ANDROID_PTY_NODE" "$NODE_PTY_DIR/build/Release/pty.node"
        echo "  Copied Android ARM64 pty.node"
    else
        echo "  NOTE: Android pty.node not found. Run scripts/build-node-pty.sh first."
        echo "        Terminal will not work without native node-pty."
    fi
    echo "  node-pty native module ready"
else
    echo "  WARNING: node-pty directory not found"
fi

# Create browser entry point stubs for extensions
echo ""
echo "Creating browser extension stubs..."
python3 - <<'PYEOF'
import json, os, glob

STUB = '// VSCodroid: browser stub\nmodule.exports = { activate() {}, deactivate() {} };\n'
created = 0
for pkg_path in glob.glob('vscode-reh/extensions/*/package.json'):
    ext_dir = os.path.dirname(pkg_path)
    with open(pkg_path) as f:
        pkg = json.load(f)
    browser = pkg.get('browser')
    if not browser:
        continue
    browser_path = os.path.join(ext_dir, browser)
    if not browser_path.endswith('.js'):
        browser_path += '.js'
    if os.path.exists(browser_path):
        continue
    os.makedirs(os.path.dirname(browser_path), exist_ok=True)
    with open(browser_path, 'w') as f:
        f.write(STUB)
    created += 1
print(f"  Created {created} browser stub files")
PYEOF

# Remove CDN/Microsoft service URLs from product.json
# These are either handled by WebView interception (*.vscode-cdn.net)
# or are Microsoft-specific services not needed for VSCodroid
echo ""
echo "Removing CDN and Microsoft service URLs..."
python3 - "$PRODUCT_JSON" <<'PYEOF'
import json, sys
path = sys.argv[1]
with open(path, 'r') as f:
    product = json.load(f)
removed = 0
for key in ['webEndpointUrl', 'webEndpointUrlTemplate', 'webviewContentExternalBaseUrlTemplate',
            'nlsCoreBaseUrl', 'profileTemplatesUrl', 'settingsSearchUrl',
            'configurationSync.store', 'editSessions.store', 'chatParticipantRegistry',
            'downloadUrl', 'webUrl']:
    if key in product:
        del product[key]
        removed += 1
with open(path, 'w') as f:
    json.dump(product, f, indent=2)
print(f"  Removed {removed} CDN/service URL(s)")
PYEOF

# Patch platform detection for Android
# Termux-patched Node.js reports process.platform as "android" instead of "linux".
# VS Code's minified code has several patterns that check for "linux":
#   1. Boolean aliases: Pt=me.platform==="linux" (fixes 6+ downstream uses each)
#   2. switch/case: case"linux": in switch(process.platform)
#   3. Equality checks: process.platform==="linux"
#   4. Inequality checks: process.platform!=="linux"
# We patch all of them to also accept "android".
echo ""
echo "Patching platform detection for Android..."
python3 - vscode-reh/out/server-main.js vscode-reh/out/vs/platform/terminal/node/ptyHostMain.js <<'PYEOF'
import re, sys

for filepath in sys.argv[1:]:
    try:
        with open(filepath, 'r') as f:
            content = f.read()
    except FileNotFoundError:
        print(f"  WARNING: {filepath} not found, skipping")
        continue

    original = content
    total = 0

    # 1. case"linux": -> case"android":case"linux":
    #    But avoid double-patching if case"android": already precedes it
    count = content.count('case"linux":') - content.count('case"android":case"linux":')
    content = content.replace('case"linux":', 'case"android":case"linux":')
    # Fix any double-patch from the replace (case"android":case"android":case"linux":)
    content = content.replace('case"android":case"android":', 'case"android":')
    total += count

    # 2. <var>.platform==="linux" -> (<var>.platform==="linux"||<var>.platform==="android")
    #    Matches aliased forms (me.platform, ks.platform) and direct process.platform
    pattern = r'(\w+\.platform)==="linux"'
    def repl_eq(m):
        var = m.group(1)
        return f'({var}==="linux"||{var}==="android")'
    content, n = re.subn(pattern, repl_eq, content)
    total += n

    # 3. <var>.platform!=="linux" -> (<var>.platform!=="linux"&&<var>.platform!=="android")
    pattern = r'(\w+\.platform)!=="linux"'
    def repl_neq(m):
        var = m.group(1)
        return f'({var}!=="linux"&&{var}!=="android")'
    content, n = re.subn(pattern, repl_neq, content)
    total += n

    if content != original:
        with open(filepath, 'w') as f:
            f.write(content)
        print(f"  Patched: {filepath} ({total} substitution(s))")
    else:
        print(f"  SKIP: No patterns found in {filepath}")
PYEOF

# Brand walkthrough strings
echo ""
echo "Branding NLS strings..."
for NLS_FILE in vscode-reh/out/nls.messages.js vscode-reh/out/nls.messages.json; do
    if [ -f "$NLS_FILE" ]; then
        python3 -c "
import sys
with open(sys.argv[1], 'r') as f: c = f.read()
c = c.replace('Get Started with VS Code for the Web', 'Get Started with VSCodroid')
c = c.replace('Setup VS Code Web', 'Setup VSCodroid')
# Remove telemetry notice from walkthrough footer — VSCodroid does not collect usage data.
# This blanks the template string; the {1} and {2} link texts become harmless since
# the parent sentence is empty, so nothing renders.
c = c.replace('{0} collects usage data. Read our {1} and learn how to {2}.', '')
with open(sys.argv[1], 'w') as f: f.write(c)
" "$NLS_FILE"
        echo "  Patched: $NLS_FILE"
    fi
done

# Disable service workers in webview pre-load
# Android WebView's ServiceWorkerClient can intercept SW script fetches,
# but the SW activation/claiming cycle doesn't work properly in cross-origin
# iframes. Instead, we handle vscode-resource: URLs directly in
# shouldInterceptRequest, making service workers unnecessary.
WEBVIEW_INDEX="vscode-reh/out/vs/workbench/contrib/webview/browser/pre/index.html"
if [ -f "$WEBVIEW_INDEX" ]; then
    echo ""
    echo "Patching webview pre-load (disable service workers + relax CSP)..."
    # Disable service workers — Android WebView's SW lifecycle doesn't work properly
    # in cross-origin iframes. shouldInterceptRequest handles vscode-resource: URLs instead.
    python3 -c "
import sys
with open(sys.argv[1], 'r') as f: c = f.read()
c = c.replace(\"const disableServiceWorker = searchParams.has('disableServiceWorker');\",
              'const disableServiceWorker = true; // VSCodroid: SW disabled, shouldInterceptRequest handles resources')
with open(sys.argv[1], 'w') as f: f.write(c)
" "$WEBVIEW_INDEX"
    # Relax CSP to allow modified inline script (hash changes with our patches)
    python3 -c "
import re, sys
with open(sys.argv[1], 'r') as f: c = f.read()
c = re.sub(r\"script-src 'sha256-[^']*' 'self'\", \"script-src 'unsafe-inline' 'self'\", c)
with open(sys.argv[1], 'w') as f: f.write(c)
" "$WEBVIEW_INDEX"
    echo "  Patched: $WEBVIEW_INDEX"
fi

# Patch callback.html for Android intent-based relay
# VS Code's callback.html writes auth tokens to localStorage, but on Android the
# callback opens in Chrome while the workbench runs in WebView — separate localStorage
# domains. We add a redirect to vscodroid://callback after the localStorage write so
# the Android app can relay the token back into the WebView's localStorage.
CALLBACK_HTML="vscode-reh/out/vs/code/browser/workbench/callback.html"
if [ -f "$CALLBACK_HTML" ]; then
    echo ""
    echo "Patching callback.html (Android intent-based auth relay)..."
    python3 -c "
import sys
with open(sys.argv[1], 'r') as f: c = f.read()
old = \"localStorage.setItem(\`vscode-web.url-callbacks[\${id}]\`, JSON.stringify(uri));\"
relay = old + '''
\t\t\t// VSCodroid: Relay callback to Android app via intent:// deep link.
\t\t\t// On Android, callback.html opens in Chrome (system browser) while
\t\t\t// the workbench runs in WebView — separate localStorage domains.
\t\t\t// Must use intent:// URI format — Chrome blocks custom scheme navigation
\t\t\t// (vscodroid://) from script context without user gesture. intent:// is
\t\t\t// always allowed and resolves via the manifest intent-filter.
\t\t\t// Must execute synchronously (no setTimeout) — async loses activation.
\t\t\ttry {
\t\t\t\tvar cd = encodeURIComponent(JSON.stringify({id: id, uri: uri}));
\t\t\t\twindow.location.href = 'intent://callback?data=' + cd + '#Intent;scheme=vscodroid;end';
\t\t\t} catch(e) {}'''
if old in c:
    c = c.replace(old, relay)
    with open(sys.argv[1], 'w') as f: f.write(c)
    print('  Patched: ' + sys.argv[1] + ' (added vscodroid://callback relay)')
else:
    print('  SKIP: localStorage.setItem pattern not found (already patched?)')
" "$CALLBACK_HTML"
fi

# Enable persisted secret storage
# VS Code's browser credential service declares encryption unavailable, forcing
# SecretStorageService to use in-memory storage (secrets lost on restart).
# The encrypt/decrypt methods are already identity (no-op), so flipping this to
# true just routes secrets to IndexedDB (persisted) instead of a RAM Map.
# Same security as --password-store=basic on desktop Linux.
WORKBENCH_JS="vscode-reh/out/vs/code/browser/workbench/workbench.js"
if [ -f "$WORKBENCH_JS" ]; then
    echo ""
    echo "Patching workbench.js (enable persisted secret storage)..."
    python3 -c "
import sys
with open(sys.argv[1], 'r') as f: c = f.read()
old = 'isEncryptionAvailable(){return Promise.resolve(!1)}'
new = 'isEncryptionAvailable(){return Promise.resolve(!0)}'
if old in c:
    c = c.replace(old, new)
    with open(sys.argv[1], 'w') as f: f.write(c)
    print('  Patched: ' + sys.argv[1] + ' (secret storage: in-memory -> persisted)')
else:
    print('  SKIP: isEncryptionAvailable pattern not found (already patched?)')
" "$WORKBENCH_JS"
fi

# Mobile-friendly CSS overrides for hamburger dropdown menu
# Appends touch-optimized styles (larger touch targets, readable font sizes) to workbench.css.
# Uses !important to override VS Code's minified rules that precede these.
echo ""
echo "Patching workbench.css (mobile-friendly menu overrides)..."
WORKBENCH_CSS="vscode-reh/out/vs/code/browser/workbench/workbench.css"
if [ -f "$WORKBENCH_CSS" ]; then
    cat >> "$WORKBENCH_CSS" << 'CSSEOF'

/* VSCodroid: Mobile-friendly hamburger menu overrides */
.menubar-menu-items-holder { min-width: 280px !important; }
.monaco-menu .monaco-action-bar.vertical .action-item { min-height: 44px !important; }
.monaco-menu .monaco-action-bar.vertical .action-label { font-size: 14px !important; padding: 8px 24px 8px 12px !important; line-height: 28px !important; }
.monaco-menu .submenu-indicator { font-size: 16px !important; }
.monaco-menu .keybinding { font-size: 12px !important; }
.monaco-menu .monaco-action-bar.vertical .action-label.separator { margin: 4px 8px !important; }
CSSEOF
    echo "  Appended mobile menu CSS overrides to $WORKBENCH_CSS"
else
    echo "  WARNING: $WORKBENCH_CSS not found, skipping CSS patch"
fi

# Bundle ripgrep for VS Code Search
# @vscode/ripgrep expects a binary at node_modules/@vscode/ripgrep/bin/rg.
# The REH archive includes an ARM64 rg binary — copy it to jniLibs for execute permission.
echo ""
echo "Bundling ripgrep for VS Code Search..."
mkdir -p "$JNILIBS_DIR"
RG_BIN="vscode-reh/node_modules/@vscode/ripgrep/bin/rg"

if [ -f "$RG_BIN" ]; then
    # Verify it's an ARM64 binary (not x86_64)
    RG_ARCH="$(file "$RG_BIN" | grep -o 'aarch64\|ARM aarch64\|x86.64' || echo "unknown")"
    if echo "$RG_ARCH" | grep -qi 'aarch64\|ARM'; then
        cp "$RG_BIN" "$JNILIBS_DIR/libripgrep.so"
        echo "  Copied ARM64 rg -> libripgrep.so ($(du -sh "$JNILIBS_DIR/libripgrep.so" | cut -f1))"
    else
        echo "  WARNING: rg binary is $RG_ARCH (not ARM64), downloading correct binary..."
        RG_VERSION="13.0.0-10"
        RG_URL="https://github.com/microsoft/ripgrep-prebuilt/releases/download/v${RG_VERSION}/ripgrep-v${RG_VERSION}-aarch64-unknown-linux-musl.tar.gz"
        curl -L --fail --show-error -o /tmp/rg-arm64.tar.gz "$RG_URL"
        tar xzf /tmp/rg-arm64.tar.gz -C /tmp rg
        cp /tmp/rg "$JNILIBS_DIR/libripgrep.so"
        rm -f /tmp/rg /tmp/rg-arm64.tar.gz
        echo "  Downloaded ARM64 rg -> libripgrep.so ($(du -sh "$JNILIBS_DIR/libripgrep.so" | cut -f1))"
    fi
else
    echo "  rg binary not found in REH archive, downloading..."
    RG_VERSION="13.0.0-10"
    RG_URL="https://github.com/microsoft/ripgrep-prebuilt/releases/download/v${RG_VERSION}/ripgrep-v${RG_VERSION}-aarch64-unknown-linux-musl.tar.gz"
    curl -L --fail --show-error -o /tmp/rg-arm64.tar.gz "$RG_URL"
    tar xzf /tmp/rg-arm64.tar.gz -C /tmp rg
    cp /tmp/rg "$JNILIBS_DIR/libripgrep.so"
    rm -f /tmp/rg /tmp/rg-arm64.tar.gz
    echo "  Downloaded ARM64 rg -> libripgrep.so ($(du -sh "$JNILIBS_DIR/libripgrep.so" | cut -f1))"
fi

# Summary
echo ""
echo "=== VS Code Server ready ==="
echo "Location: $SERVER_DIR/vscode-reh/"
echo "Size: $(du -sh vscode-reh | cut -f1)"
echo "Entry point: vscode-reh/out/server-main.js"
[ -f "$PRODUCT_JSON" ] && echo "Product: $(python3 -c "import json; print(json.load(open('$PRODUCT_JSON'))['nameShort'])")"
echo ""
echo "Next: ./scripts/package-assets.sh"
