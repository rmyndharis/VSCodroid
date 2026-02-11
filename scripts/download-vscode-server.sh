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
new = 'this.h=await(async function(f,a,opts){var W=(await import("worker_threads")).Worker;var ea=opts.execArgv||[];var rl={};ea=ea.filter(function(x){var m=x.match(/^--max-old-space-size=(\\d+)/);if(m){rl.maxOldGenerationSizeMb=+m[1];return false}return!/^--max-semi-space-size/.test(x)});var w=new W(f,{argv:a,env:opts.env,execArgv:ea,resourceLimits:rl,stdout:true,stderr:true});w.pid=w.threadId;w.kill=function(){w.terminate()};return w})(jt.asFileUri("bootstrap-fork").fsPath,o,r)'
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

# Replace node-pty with pipe-based terminal shim (with PTY bridge support)
# The native pty.node is compiled for Linux glibc and won't load on Android Bionic.
# When VSCODROID_PTY_HELPER is set, spawns a C PTY bridge for real terminal support.
# Otherwise falls back to pipe-based terminal with JS line discipline.
echo ""
echo "Patching node-pty with pipe terminal shim..."
NODE_PTY_LIB="vscode-reh/node_modules/node-pty/lib"
if [ -d "$NODE_PTY_LIB" ]; then
    # Create pipeTerminal.js
    cat > "$NODE_PTY_LIB/pipeTerminal.js" <<'JSEOF'
"use strict";
/**
 * VSCodroid: Terminal shim for Android with dual-mode support.
 *
 * PTY mode (VSCODROID_PTY_HELPER set):
 *   Spawns a C PTY bridge that creates a real PTY via forkpty().
 *   All line discipline (echo, readline, colors) handled by the kernel.
 *   Supports vim, tmux, htop, job control, tab completion — everything.
 *
 * Pipe mode (fallback):
 *   Spawns shell with pipe stdio and emulates line discipline in JS.
 *   Basic command execution works but no interactive programs.
 */
var __extends = (this && this.__extends) || (function () {
    var extendStatics = function (d, b) {
        extendStatics = Object.setPrototypeOf ||
            ({ __proto__: [] } instanceof Array && function (d, b) { d.__proto__ = b; }) ||
            function (d, b) { for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p]; };
        return extendStatics(d, b);
    };
    return function (d, b) {
        extendStatics(d, b);
        function __() { this.constructor = d; }
        d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.PipeTerminal = void 0;
var child_process = require("child_process");
var stream = require("stream");
var fs = require("fs");
var terminal_1 = require("./terminal");
var utils_1 = require("./utils");
var DEFAULT_FILE = 'sh';
var PTY_HELPER = process.env.VSCODROID_PTY_HELPER || '';
var TMUX_PATH = process.env.VSCODROID_TMUX || '';
var _sessionCounter = 0;
var PipeTerminal = (function (_super) {
    __extends(PipeTerminal, _super);
    function PipeTerminal(file, args, opt) {
        var _this = _super.call(this, opt) || this;
        _this._emittedClose = false;
        _this._isPtyMode = false;
        _this._tmuxSessionId = null;
        if (typeof args === 'string') {
            throw new Error('args as a string is not supported on unix.');
        }
        args = args || [];
        file = file || DEFAULT_FILE;
        opt = opt || {};
        opt.env = opt.env || process.env;
        _this._cols = opt.cols || terminal_1.DEFAULT_COLS;
        _this._rows = opt.rows || terminal_1.DEFAULT_ROWS;
        var env = utils_1.assign({}, opt.env);
        if (opt.env === process.env) { _this._sanitizeEnv(env); }
        var cwd = opt.cwd || process.cwd();
        env.PWD = cwd;
        var name = opt.name || env.TERM || 'xterm-256color';
        env.TERM = name;
        var encoding = (opt.encoding === undefined ? 'utf8' : opt.encoding);
        // Check if PTY bridge is available
        var usePty = PTY_HELPER && fs.existsSync(PTY_HELPER);
        _this._isPtyMode = usePty;
        var child;
        if (usePty) {
            // === PTY MODE ===
            // Spawn: ptybridge -c COLS -r ROWS <shell> [args...]
            // The bridge creates a real PTY — kernel handles all line discipline.
            if (args.length === 0 && file && file.indexOf('bash') !== -1) {
                args = ['-i'];  // Readline works with real PTY, no --noediting needed
            }
            // Wrap in tmux for session persistence across crashes
            if (TMUX_PATH && fs.existsSync(TMUX_PATH)) {
                var sessionId = 'vsc-' + process.pid + '-' + (_sessionCounter++);
                _this._tmuxSessionId = sessionId;
                // tmux new-session -A: attach if exists, create if not
                args = ['new-session', '-A', '-s', sessionId, '--', file].concat(args);
                file = TMUX_PATH;
            }
            var bridgeArgs = ['-c', String(_this._cols), '-r', String(_this._rows), file].concat(args);
            try {
                child = child_process.spawn(PTY_HELPER, bridgeArgs, {
                    stdio: ['pipe', 'pipe', 'pipe'], env: env, cwd: cwd
                });
            } catch (spawnErr) {
                // PTY bridge failed to spawn — fall through to pipe mode
                usePty = false;
                _this._isPtyMode = false;
            }
        }
        if (!usePty) {
            // === PIPE MODE (fallback) ===
            if (args.length === 0 && file && file.indexOf('bash') !== -1) {
                args = ['--noediting', '-i'];
            }
            var argv0 = (file.match(/lib(\w+)\.so$/) || [])[1] || undefined;
            try {
                child = child_process.spawn(file, args, { argv0: argv0, stdio: ['pipe', 'pipe', 'pipe'], env: env, cwd: cwd });
            } catch (spawnErr) {
                if (file !== '/system/bin/sh') {
                    child = child_process.spawn('/system/bin/sh', [], { stdio: ['pipe', 'pipe', 'pipe'], env: env, cwd: cwd });
                } else { throw spawnErr; }
            }
        }
        _this._child = child;
        var _startTime = Date.now();
        var _suppressRe = /cannot set terminal process group|no job control in this shell/;
        _this._socket = new stream.PassThrough();
        if (encoding !== null) { _this._socket.setEncoding(encoding); }
        if (_this._isPtyMode) {
            // PTY mode: direct passthrough — PTY driver handles ONLCR, echo, etc.
            if (child.stdout) {
                child.stdout.on('data', function (data) {
                    if (!_this._socket.destroyed) { _this._socket.push(data); }
                });
            }
            if (child.stderr) {
                child.stderr.on('data', function (data) {
                    if (!_this._socket.destroyed) { _this._socket.push(data); }
                });
            }
        } else {
            // Pipe mode: simulate PTY output (ONLCR) and suppress startup noise
            function onlcr(buf) {
                if (typeof buf === 'string') { return buf.replace(/\n/g, '\r\n'); }
                var chunks = []; var lastIdx = 0;
                for (var j = 0; j < buf.length; j++) {
                    if (buf[j] === 0x0a) {
                        if (j > lastIdx) chunks.push(buf.slice(lastIdx, j));
                        chunks.push(Buffer.from([0x0d, 0x0a]));
                        lastIdx = j + 1;
                    }
                }
                if (lastIdx < buf.length) chunks.push(buf.slice(lastIdx));
                return chunks.length === 1 ? chunks[0] : Buffer.concat(chunks);
            }
            if (child.stdout) { child.stdout.on('data', function (data) { if (!_this._socket.destroyed) { _this._socket.push(onlcr(data)); } }); }
            if (child.stderr) { child.stderr.on('data', function (data) {
                if (_this._socket.destroyed) return;
                if (Date.now() - _startTime < 3000) {
                    var str = typeof data === 'string' ? data : data.toString('utf8');
                    if (_suppressRe.test(str)) return;
                }
                _this._socket.push(onlcr(data));
            }); }
        }
        child.on('exit', function (code, signal) {
            if (!_this._emittedClose) {
                _this._emittedClose = true;
                if (!_this._socket.destroyed) { _this._socket.push(null); }
                _this.emit('close');
            }
            _this.emit('exit', code, signal);
        });
        child.on('error', function (err) { if (_this.listeners('error').length > 1) { _this.emit('error', err); } });
        _this._pid = child.pid || 0;
        _this._fd = -1;
        _this._file = file;
        _this._name = name;
        _this._readable = true;
        _this._writable = true;
        _this._lineBuf = [];
        _this._forwardEvents();
        return _this;
    }
    PipeTerminal.prototype._write = function (data) {
        if (!this._child || !this._child.stdin || this._child.stdin.destroyed) return;
        if (this._isPtyMode) {
            // PTY mode: direct passthrough — the real PTY handles everything
            var buf = typeof data === 'string' ? Buffer.from(data) : data;
            this._child.stdin.write(buf);
            return;
        }
        // Pipe mode: line discipline emulation
        var buf = typeof data === 'string' ? Buffer.from(data) : data;
        var sock = this._socket;
        for (var i = 0; i < buf.length; i++) {
            var b = buf[i];
            if (b === 0x0d) {
                if (!sock.destroyed) sock.push('\r\n');
                var line = Buffer.from(this._lineBuf.concat([0x0a]));
                this._lineBuf = [];
                this._child.stdin.write(line);
            } else if (b === 0x7f || b === 0x08) {
                if (this._lineBuf.length > 0) {
                    this._lineBuf.pop();
                    if (!sock.destroyed) sock.push('\b \b');
                }
            } else if (b === 0x03) {
                this._lineBuf = [];
                try { process.kill(this._child.pid, 'SIGINT'); } catch(e) {}
                if (!sock.destroyed) sock.push('^C\r\n');
            } else if (b === 0x1c) {
                this._lineBuf = [];
                try { process.kill(this._child.pid, 'SIGQUIT'); } catch(e) {}
                if (!sock.destroyed) sock.push('^\\\r\n');
            } else if (b === 0x04) {
                if (this._lineBuf.length === 0) {
                    this._child.stdin.end();
                }
            } else if (b === 0x15) {
                var len = this._lineBuf.length;
                if (len > 0 && !sock.destroyed) {
                    var erase = ''; for (var j = 0; j < len; j++) erase += '\b \b';
                    sock.push(erase);
                    this._lineBuf = [];
                }
            } else if (b === 0x17) {
                while (this._lineBuf.length > 0 && this._lineBuf[this._lineBuf.length-1] === 0x20) {
                    this._lineBuf.pop(); if (!sock.destroyed) sock.push('\b \b');
                }
                while (this._lineBuf.length > 0 && this._lineBuf[this._lineBuf.length-1] !== 0x20) {
                    this._lineBuf.pop(); if (!sock.destroyed) sock.push('\b \b');
                }
            } else if (b === 0x0c) {
                if (!sock.destroyed) sock.push('\x1b[2J\x1b[H');
            } else if (b === 0x09) {
                this._lineBuf.push(b);
                if (!sock.destroyed) sock.push('\t');
            } else if (b === 0x1b) {
                var end = i + 1;
                if (end < buf.length && buf[end] === 0x5b) {
                    end++; while (end < buf.length && buf[end] >= 0x20 && buf[end] < 0x40) end++;
                    if (end < buf.length) end++;
                }
                i = end - 1;
            } else if (b >= 0x20 && b <= 0x7e) {
                this._lineBuf.push(b);
                if (!sock.destroyed) sock.push(Buffer.from([b]));
            } else if (b >= 0xc0) {
                var sLen = (b < 0xe0) ? 2 : (b < 0xf0) ? 3 : 4;
                var seq = buf.slice(i, Math.min(i + sLen, buf.length));
                for (var k = 0; k < seq.length; k++) this._lineBuf.push(seq[k]);
                if (!sock.destroyed) sock.push(seq);
                i += seq.length - 1;
            }
        }
    };
    Object.defineProperty(PipeTerminal.prototype, "fd", { get: function () { return this._fd; }, enumerable: false, configurable: true });
    Object.defineProperty(PipeTerminal.prototype, "ptsName", {
        get: function () { return this._isPtyMode ? 'pty-bridge' : 'pipe'; },
        enumerable: false, configurable: true
    });
    PipeTerminal.prototype.resize = function (cols, rows) {
        if (cols <= 0 || rows <= 0) return;
        this._cols = cols;
        this._rows = rows;
        if (this._isPtyMode && this._child && this._child.pid) {
            // Write size file and send SIGWINCH to PTY bridge
            try {
                var tmpdir = process.env.TMPDIR || '/tmp';
                fs.writeFileSync(tmpdir + '/.pty-size-' + this._child.pid, cols + ' ' + rows);
                process.kill(this._child.pid, 'SIGWINCH');
            } catch (e) {}
        }
    };
    PipeTerminal.prototype.clear = function () {};
    PipeTerminal.prototype.kill = function (signal) {
        try {
            if (this._child && this._child.pid) {
                // Clean up tmux session on terminal close (SIGHUP)
                if (this._tmuxSessionId && TMUX_PATH && (!signal || signal === 'SIGHUP')) {
                    try {
                        child_process.spawnSync(TMUX_PATH, ['kill-session', '-t', this._tmuxSessionId],
                            { timeout: 2000, stdio: 'ignore' });
                    } catch (e) {}
                }
                process.kill(this._child.pid, signal || 'SIGHUP');
            }
        } catch (e) {}
    };
    PipeTerminal.prototype.destroy = function () {
        this._close();
        // Kill tmux session on explicit close to prevent orphans
        if (this._tmuxSessionId && TMUX_PATH) {
            try {
                child_process.spawnSync(TMUX_PATH, ['kill-session', '-t', this._tmuxSessionId],
                    { timeout: 2000, stdio: 'ignore' });
            } catch (e) {}
        }
        if (this._child) {
            this._child.stdin && this._child.stdin.destroy();
            this._child.stdout && this._child.stdout.destroy();
            this._child.stderr && this._child.stderr.destroy();
            try { this._child.kill('SIGHUP'); } catch (e) {}
        }
        if (!this._socket.destroyed) { this._socket.destroy(); }
    };
    Object.defineProperty(PipeTerminal.prototype, "process", {
        get: function () {
            if (this._child && this._child.pid) {
                try {
                    var cmdline = fs.readFileSync('/proc/' + this._child.pid + '/cmdline', 'utf8');
                    var name = cmdline.split('\0')[0];
                    if (name) return name;
                } catch (e) {}
            }
            return this._file;
        },
        enumerable: false, configurable: true
    });
    PipeTerminal.prototype._sanitizeEnv = function (env) {
        delete env['TMUX']; delete env['TMUX_PANE'];
        delete env['STY']; delete env['WINDOW'];
        delete env['WINDOWID']; delete env['TERMCAP'];
        delete env['COLUMNS']; delete env['LINES'];
    };
    PipeTerminal.open = function () { throw new Error('PipeTerminal.open() is not supported'); };
    return PipeTerminal;
}(terminal_1.Terminal));
exports.PipeTerminal = PipeTerminal;
JSEOF

    # Patch index.js to fall back to PipeTerminal
    cat > "$NODE_PTY_LIB/index.js" <<'JSEOF'
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.native = exports.open = exports.createTerminal = exports.fork = exports.spawn = void 0;
var terminalCtor;
if (process.platform === 'win32') {
    terminalCtor = require('./windowsTerminal').WindowsTerminal;
} else {
    try {
        terminalCtor = require('./unixTerminal').UnixTerminal;
    } catch (e) {
        terminalCtor = require('./pipeTerminal').PipeTerminal;
    }
}
function spawn(file, args, opt) { return new terminalCtor(file, args, opt); }
exports.spawn = spawn;
function fork(file, args, opt) { return new terminalCtor(file, args, opt); }
exports.fork = fork;
function createTerminal(file, args, opt) { return new terminalCtor(file, args, opt); }
exports.createTerminal = createTerminal;
function open(options) { return terminalCtor.open(options); }
exports.open = open;
try { exports.native = (process.platform !== 'win32' ? require('../build/Release/pty.node') : null); }
catch (e) { exports.native = null; }
JSEOF
    echo "  Created pipeTerminal.js and patched index.js"
else
    echo "  WARNING: node-pty lib directory not found"
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
