/**
 * VSCodroid platform compatibility fix: SELECTIVE override.
 *
 * Termux-patched Node.js reports process.platform === "android" instead of "linux".
 * Many npm packages (Prisma, node-gyp, etc.) don't recognize "android" and fail
 * during platform detection or native binary download.
 *
 * However, some tools (Rollup 4.57+, esbuild) have native android-arm64 builds.
 * A global override to "linux" breaks them; they try linux-arm64-musl instead.
 *
 * Strategy: Only override when explicitly opted in (npm/npx bash functions set
 * VSCODROID_PLATFORM_FIX=1) or when node-gyp is detected in argv.
 * The env var is deleted after consuming so child processes (Rollup, Vite, etc.)
 * see the real "android" platform.
 *
 * Loaded via NODE_OPTIONS="--require=<path>/platform-fix.js" for Node.js processes,
 * and through the editor server's execArgv for the workers it starts: VS Code
 * deletes NODE_OPTIONS from the extension host's environment, and a worker does
 * not run a preload it cannot see.
 */
'use strict';

if (process.platform === 'android') {
  // Expose real platform for tools that need introspection
  process.env.VSCODROID_REAL_PLATFORM = 'android';

  var shouldFix = false;

  // Opt-in: npm/npx bash functions set this
  if (process.env.VSCODROID_PLATFORM_FIX === '1') {
    shouldFix = true;
    delete process.env.VSCODROID_PLATFORM_FIX; // don't propagate to children
  }

  // Auto-detect node-gyp (spawned by npm as subprocess).
  //
  // Only argv[1], and only as a path segment. node-gyp is the script node was
  // asked to run, so it is always argv[1] and always inside its own package
  // directory. Scanning the whole argv for the substring meant any invocation
  // that merely mentioned the name got process.platform redefined underneath
  // it -- `node build.js --out ~/node-gyp-notes/` took the override, silently,
  // and a package that ships a real android-arm64 binary then resolved the
  // wrong one. Nothing logs this, and the symptom lands in whatever the process
  // did next.
  //
  // node-gyp-build is deliberately kept: it picks a prebuild by platform name,
  // it is what the old scan caught in practice, and dropping it here would be a
  // behaviour change dressed up as a tightening.
  if (!shouldFix) {
    var entry = (process.argv[1] || '').replace(/\\/g, '/');
    if (/(^|\/)node-gyp(-build)?(\/|\.js$|$)/.test(entry)) {
      shouldFix = true;
    }
  }

  if (shouldFix) {
    Object.defineProperty(process, 'platform', {
      value: 'linux',
      writable: false,
      enumerable: true,
      configurable: true
    });
  }

  // The Jupyter extension bundles pidtree, which chooses its backend from
  // os.platform() and throws for 'android'. The extension uses it to signal
  // every process under a kernel, whichever cell started it: SIGINT on
  // interrupt, SIGTERM on restart and shutdown. Without it, interrupting leaves
  // them running and restarting orphans them to pid 1, where each still counts
  // against Android's phantom process limit.
  // pidtree's linux backend runs `ps -A -o ppid,pid`, which toybox answers.
  //
  // Only that one bundle is told 'linux', through its own copy of `os`, so the
  // prebuild and binary choices every other extension makes from the platform
  // stay on the truth. zeromq's loader is a separate file and is not matched.
  var Module = require('module');
  var realLoad = Module._load;
  var JUPYTER_BUNDLE = /[\\/]ms-toolsai\.jupyter-[^\\/]+[\\/]dist[\\/]extension\.node\.js$/;
  var jupyterOs = null;
  Module._load = function (request, parent) {
    if ((request === 'os' || request === 'node:os') && parent && JUPYTER_BUNDLE.test(parent.filename || '')) {
      if (!jupyterOs) {
        jupyterOs = Object.assign({}, realLoad.apply(this, arguments), {
          platform: function () { return 'linux'; }
        });
      }
      return jupyterOs;
    }
    return realLoad.apply(this, arguments);
  };

  // The Codex extension ships its engine as a static musl executable, which
  // Android cannot start, and starts it with child_process.spawn on the path
  // inside its own directory. The Codex toolchain installs an engine that can
  // run behind the `codex` command (ToolchainManager.addCodexRows), so that one
  // spawn is pointed there. Everything else about the call is left as the
  // extension made it, including its arguments.
  //
  // Only while the command exists. Without the toolchain the spawn goes where it
  // always went and fails the way it always did, which is the honest outcome
  // for an engine nobody installed.
  //
  // Here and not as a link at the extension's path: the extension updates itself
  // into a new directory with a fresh copy of the unrunnable binary, and a link
  // would be missing from it until the next time the app came to the foreground.
  // Only spawn is wrapped, which is the call the extension makes: execFile and
  // fork reach the same function through a reference of their own.
  var fs = require('fs');
  var path = require('path');
  var childProcess = require('child_process');
  var CODEX_BUNDLED = /[\\/]openai\.chatgpt-[^\\/]+[\\/]bin[\\/][^\\/]+[\\/]codex$/;

  // An extension that downloads a program built for Android into app storage
  // (Google Antigravity's engine, ~/.gemini/bin/agy) starts it with
  // child_process.spawn, and the kernel refuses: SELinux does not let anything
  // under the app's data directory be executed. The terminal gets past that
  // with an exec interceptor, which the extension host does not load, so the
  // call fails with EACCES. The system linker can run the same file, and that
  // is all the interceptor does underneath: `linker64 <file> <args>`.
  //
  // Only a 64-bit ELF that asks for that linker (its PT_INTERP) and whose real
  // path is inside the data directory. The links under usr/bin end in the
  // native library directory, where a direct exec is allowed and keeps
  // /proc/self/exe honest; a musl binary needs its own loader; a script has no
  // interpreter entry at all. Left alone in a process that already has the
  // terminal's interceptor, which does this itself.
  var LINKER = '/system/bin/linker64';
  var APP_DATA = null;
  // Only when it is set: realpathSync('') answers the working directory, which would turn the rule on for
  // whatever lies under it.
  if (process.env.TERMUX_APP__DATA_DIR) {
    try { APP_DATA = fs.realpathSync(process.env.TERMUX_APP__DATA_DIR); } catch (e) { /* not a directory: stays off */ }
  }
  var INTERCEPTED = /libtermux-exec/.test(process.env.LD_PRELOAD || '');
  // The person's home, read once. An extension in the shared host may reassign or delete HOME later, and where
  // ~/.gemini and the machine settings are must not follow it: the setting would no longer hold anything back.
  var HOME = process.env.HOME || null;

  /** The PT_INTERP string of an open ELF64 little-endian file, or null. */
  function elfInterpreter(fd) {
    var header = Buffer.alloc(64);
    if (fs.readSync(fd, header, 0, 64, 0) < 64) return null;
    if (header.readUInt32BE(0) !== 0x7f454c46 || header[4] !== 2 || header[5] !== 1) return null;
    var phoff = Number(header.readBigUInt64LE(32));
    var phentsize = header.readUInt16LE(54);
    var phnum = header.readUInt16LE(56);
    if (phentsize < 56) return null;
    var entry = Buffer.alloc(phentsize);
    for (var i = 0; i < phnum; i++) {
      if (fs.readSync(fd, entry, 0, phentsize, phoff + i * phentsize) < phentsize) return null;
      if (entry.readUInt32LE(0) !== 3) continue;
      var size = Number(entry.readBigUInt64LE(32));
      if (size < 1 || size > 4096) return null;
      var text = Buffer.alloc(size);
      if (fs.readSync(fd, text, 0, size, Number(entry.readBigUInt64LE(8))) < size) return null;
      return text.toString('latin1').replace(/\0[\s\S]*$/, '');
    }
    return null;
  }

  function bionicInAppData(file) {
    if (!APP_DATA || INTERCEPTED || !path.isAbsolute(file)) return false;
    try {
      var real = fs.realpathSync(file);
      if (real.indexOf(APP_DATA + '/') !== 0) return false;
      // O_NONBLOCK: a plain open of a FIFO waits for a writer.
      var fd = fs.openSync(real, fs.constants.O_RDONLY | fs.constants.O_NONBLOCK);
      try { return elfInterpreter(fd) === LINKER; } finally { fs.closeSync(fd); }
    } catch (e) {
      return false;
    }
  }

  /**
   * What follows `file` in a child_process call (an argument list, options and a
   * callback, whichever are given) as a call to `linker64 <file> <args>`, or null
   * when the call is left alone. `change` may replace the argument list.
   */
  function throughLinker(file, rest, change) {
    if (typeof file !== 'string') return null;
    // Node takes null or undefined for the argument list, and options then follow it.
    var listed = Array.isArray(rest[0]) || rest[0] == null;
    var options = listed ? rest[1] : rest[0];
    if ((options && options.shell) || !bionicInAppData(file)) return null;
    var args = Array.isArray(rest[0]) ? rest[0] : [];
    return [LINKER, [file].concat(change ? change(args) : args)].concat(rest.slice(listed ? 1 : 0));
  }

  // The Antigravity engine's page refuses to be framed by anything but a short
  // list of ancestors: itself, localhost and 127.0.0.1 on any port, and
  // `vscode-webview:`, which is how desktop VS Code names the origin of its
  // webviews. Here a webview is served from https://<id>.vscode-cdn.net,
  // because the workbench is a web page, and that origin is not on the list,
  // so the browser refuses the frame and the panel stays empty. Every
  // *.vscode-cdn.net address is answered inside the app and never reaches the
  // network, so the only thing that origin can be is one of this app's own
  // webviews.
  //
  // The extension picks a free port, starts `agy --hub --hub-port=<port>`, polls
  // http://127.0.0.1:<port>/healthz and puts that address in an iframe. So the
  // engine is started on a port of its own and this process answers on the one
  // the extension chose. Everything passes through unchanged except the
  // `frame-ancestors` directive of a policy that already lists `vscode-webview:`,
  // which gains this app's webview origin; a policy that does not ask for VS
  // Code webviews is left as it is.
  var ANTIGRAVITY_ENGINE = /[\\/]\.gemini[\\/]bin[\\/]agy$/;

  // The engine's own port answers every app on the phone that can use the network, and the page it
  // serves there hands out the key its calls need, so an app that finds the port can use the account
  // signed in to the engine. The engine has no option that closes this. So the app runs nothing from
  // ~/.gemini, where the extension keeps what it downloads, until the person has turned on
  // `vscodroid.antigravity.runEngine`, a machine setting the process monitor extension declares and
  // offers when it sees the Antigravity extension. With the setting off such a call is left as it was
  // written and fails with EACCES, as it always did here, whatever its arguments: the engine is a Go
  // program whose flag parser takes `-hub` and `--hub=true` for `--hub`, it has a second listening
  // mode (`--remote-control`), and the extension and the engine both update themselves, so a list of
  // the arguments that start a listener would not stay complete. The one call let through is exactly
  // `agy --version`, which the extension runs to check the engine it downloaded and answers by
  // downloading the engine again when it fails. This is about what extensions start: a terminal runs
  // what the person types there through its own exec interceptor.
  //
  // Read from the machine settings file at each such call, and the way VS Code builds its settings from
  // it: the keys of the root object are split at their dots into a tree, in file order, so
  // `"vscodroid.antigravity.runEngine": true`, `"vscodroid.antigravity": {"runEngine": true}` and the fully
  // nested form all name this setting, a later key replaces what an earlier one made, and a key whose
  // parent is a scalar is dropped. The same key inside another object, a language block included, is not
  // this setting. A file that does not parse turns nothing on. VS Code recovers from some syntax errors (a
  // missing comma), so the Settings editor can show the box ticked over a file this reads as off, which
  // keeps the engine down, the safe side of the two.
  var ENGINE_SETTING = 'vscodroid.antigravity.runEngine';
  var SETTINGS_BYTES = 1 << 20;
  var SETTINGS_DEPTH = 64;

  /**
   * `text` as JSON that JSON.parse takes, or null when it is not a settings file: comments out (a line
   * comment ends at a CR as well as an LF, as VS Code's scanner ends it), a comma before a closing bracket
   * out, a raw control character inside a string escaped, a byte order mark dropped. One pass over the
   * text with strings kept whole, so no pattern can run away on a long one. A comment or a string that never
   * ends, a string that breaks across lines, or brackets nested past SETTINGS_DEPTH is not a settings file.
   */
  function settingsAsJson(text) {
    var out = [];
    var i = text.charCodeAt(0) === 0xFEFF ? 1 : 0;
    var depth = 0;
    var comma = -1; // where in `out` a comma that no token has followed yet stands
    while (i < text.length) {
      var c = text.charAt(i);
      if (c === '"') {
        var piece = '"';
        i++;
        for (;;) {
          if (i >= text.length) return null;
          var d = text.charAt(i);
          if (d === '"') break;
          if (d === '\\') {
            if (i + 1 >= text.length) return null;
            piece += d + text.charAt(i + 1);
            i += 2;
            continue;
          }
          if (d === '\n' || d === '\r') return null;
          piece += d < ' ' ? '\\u' + ('0000' + d.charCodeAt(0).toString(16)).slice(-4) : d;
          i++;
        }
        out.push(piece + '"');
        comma = -1;
        i++;
      } else if (c === '/' && text.charAt(i + 1) === '/') {
        while (i < text.length && text.charAt(i) !== '\n' && text.charAt(i) !== '\r') i++;
        out.push(' ');
      } else if (c === '/' && text.charAt(i + 1) === '*') {
        var close = text.indexOf('*/', i + 2);
        if (close < 0) return null;
        out.push(' ');
        i = close + 2;
      } else {
        if (c === ',') {
          comma = out.length;
        } else if (c === '}' || c === ']') {
          if (comma >= 0) out[comma] = ' ';
          comma = -1;
          depth--;
        } else if (c === '{' || c === '[') {
          if (++depth > SETTINGS_DEPTH) return null;
          comma = -1;
        } else if (c > ' ') {
          comma = -1;
        }
        out.push(c);
        i++;
      }
    }
    return out.join('');
  }

  /** The setting as the tree VS Code builds from the keys of `raw` holds it: only `true` turns it on. */
  function engineValue(raw) {
    if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return false;
    var root = Object.create(null);
    Object.keys(raw).forEach(function (key) {
      var segments = key.split('.');
      var last = segments.pop();
      var at = root;
      for (var i = 0; i < segments.length; i++) {
        var next = at[segments[i]];
        if (next === undefined) next = at[segments[i]] = Object.create(null);
        else if (next === null || typeof next !== 'object') return;
        at = next;
      }
      at[last] = raw[key];
    });
    var node = root;
    var names = ENGINE_SETTING.split('.');
    for (var j = 0; j < names.length; j++) {
      if (node === null || typeof node !== 'object') return false;
      node = node[names[j]];
    }
    return node === true;
  }

  function engineTurnedOn() {
    if (!HOME) return false;
    try {
      var file = path.join(HOME, '.vscodroid', 'data', 'Machine', 'settings.json');
      if (fs.statSync(file).size > SETTINGS_BYTES) return false;
      var json = settingsAsJson(fs.readFileSync(file, 'utf8'));
      return json !== null && engineValue(JSON.parse(json));
    } catch (e) {
      return false;
    }
  }

  /** Whether the real path of `file` is inside ~/.gemini; an error of any kind says it is not. */
  function fromGemini(file) {
    if (!HOME || !path.isAbsolute(file)) return false;
    try {
      return fs.realpathSync(file).indexOf(fs.realpathSync(path.join(HOME, '.gemini')) + '/') === 0;
    } catch (e) {
      return false;
    }
  }

  var WEBVIEW_ORIGIN = 'https://*.vscode-cdn.net';
  var HOP_BY_HOP = /^(connection|keep-alive|proxy-authenticate|proxy-authorization|te|trailer|transfer-encoding|upgrade)$/i;

  function widenFrameAncestors(policy) {
    return policy.split(';').map(function (directive) {
      var parts = directive.trim().split(/\s+/);
      if (parts[0].toLowerCase() !== 'frame-ancestors' || parts.indexOf('vscode-webview:') < 0) return directive;
      if (parts.indexOf(WEBVIEW_ORIGIN) < 0) parts.push(WEBVIEW_ORIGIN);
      return directive.match(/^\s*/)[0] + parts.join(' ');
    }).join(';');
  }

  // The arguments the extension builds for a hub start, by name. `antigravity.serverArgs` appends its own,
  // and the folder that is open can set it in its own settings (the editor runs without Workspace Trust), so
  // a hub start carrying any other argument is not started, nor one that repeats any but --add-dir: the flag
  // parser keeps the last of a repeated one, which would let the folder choose the engine's port or its data
  // directory. A repeated --add-dir only adds a directory the agent may use, as each folder of the workspace
  // already does.
  //
  // `--csrf_token=<value>` is the extension's own only for an engine older than 1.5.2, which takes the token as
  // a flag as well as in the environment (ANTIGRAVITY_SERVER_CSRF_TOKEN). It passes only with the value the
  // call's own environment carries: with a current engine the extension builds no such flag, so one that
  // arrives is a folder's, and would choose the token.
  var HUB_ARGUMENT = /^--(?:hub|hub-port=\d{1,5}|app_data_dir=[\s\S]*|add-dir=[\s\S]*)$/;
  var TOKEN_FLAG = '--csrf_token=';
  var TOKEN_ENV = 'ANTIGRAVITY_SERVER_CSRF_TOKEN';

  /**
   * For `agy --hub --hub-port=<n>`: the port the extension chose, the one the
   * engine is given instead and the argument list that says so. A random port
   * in the dynamic range, as the extension's own is. A clash makes the engine
   * print `port <n> is already in use`, which the extension's own port
   * collision handling matches; that path was read, not run. False for that
   * start with an argument outside HUB_ARGUMENT, null for anything else, which
   * runs as an ordinary program would, with no relay and no note.
   */
  function antigravityHub(file, args, env) {
    if (!ANTIGRAVITY_ENGINE.test(file) || args.indexOf('--hub') < 0) return null;
    var ports = args.filter(function (arg) { return /^--hub-port=/.test(arg); });
    if (!ports.length) return null;
    var token = env && typeof env === 'object' && typeof env[TOKEN_ENV] === 'string' ? env[TOKEN_ENV] : '';
    var seen = {};
    var refused = args.some(function (arg) {
      var name = arg.split('=')[0];
      var again = seen[name] && name !== '--add-dir';
      seen[name] = true;
      var own = arg.indexOf(TOKEN_FLAG) === 0
        ? token !== '' && arg.slice(TOKEN_FLAG.length) === token
        : HUB_ARGUMENT.test(arg);
      return again || !own;
    });
    if (refused) return false;
    var front = Number(ports[0].slice('--hub-port='.length));
    if (front === 0 || front > 65535) return null;
    var back = front;
    while (back === front) back = 20000 + Math.floor(Math.random() * 40000);
    return { front: front, back: back, args: args.map(function (arg) { return arg === ports[0] ? '--hub-port=' + back : arg; }) };
  }

  /**
   * Whether `host` names this relay's own address. The engine refuses a Host header that is not its own, which
   * is what stops a web page whose name was pointed at 127.0.0.1 (DNS rebinding) from reading its page, and the
   * relay rewrites Host for the engine, so the relay has to be the one to refuse.
   */
  function ownHost(host, front) {
    var value = String(host || '').toLowerCase();
    return value === '127.0.0.1:' + front || value === 'localhost:' + front;
  }

  // Any app on the phone can open connections to the relay, and it runs in the editor server, which shares
  // its file descriptors with the extension host and the terminal host. So it holds at most this many, each
  // with one more to the engine. A request has HEADERS_MS for its headers and REQUEST_MS for its body too,
  // which over loopback takes milliseconds; a response may take as long as it takes (measured on Node 22 and
  // 24). Node looks every 30 s (connectionsCheckingInterval), so a stalled request goes up to 30 s later. A tunnel quiet both ways for TUNNEL_IDLE_MS is closed: no keepalive of
  // the page's own was found in the extension or the engine, so that is a bound on abandoned tunnels, not
  // a margin over a measured interval.
  var RELAY_CONNECTIONS = 32;
  var HEADERS_MS = 15000;
  var REQUEST_MS = 30000;
  var TUNNEL_IDLE_MS = 10 * 60 * 1000;

  /** Answers on `front` and relays to the engine on `back`, 502 while it is not accepting; closes when `child` ends. */
  function relayHub(front, back, child) {
    var http = require('http');
    var net = require('net');
    var pipeline = require('stream').pipeline;
    var sockets = new Set();
    // The relay's own answer for an engine that gave none. The connection goes with it: a request body
    // the engine never read may still be on its way, and a connection kept alive would wait behind it.
    function refuse(res) {
      res.writeHead(502, 'Bad Gateway', { Connection: 'close' });
      res.end();
    }
    var server = http.createServer({ headersTimeout: HEADERS_MS, requestTimeout: REQUEST_MS }, function (req, res) {
      if (!ownHost(req.headers.host, front)) {
        res.writeHead(403);
        res.end();
        return;
      }
      var headers = {};
      Object.keys(req.headers).forEach(function (name) {
        if (!HOP_BY_HOP.test(name)) headers[name] = req.headers[name];
      });
      headers.host = '127.0.0.1:' + back;
      var up = http.request({ host: '127.0.0.1', port: back, method: req.method, path: req.url, headers: headers, agent: false }, function (answer) {
        var sent = [];
        for (var i = 0; i < answer.rawHeaders.length; i += 2) {
          var name = answer.rawHeaders[i];
          var value = answer.rawHeaders[i + 1];
          if (HOP_BY_HOP.test(name)) continue;
          sent.push(name, /^content-security-policy$/i.test(name) ? widenFrameAncestors(value) : value);
        }
        try {
          res.writeHead(answer.statusCode, answer.statusMessage, sent);
        } catch (e) {
          // Node's client takes a status text with a control byte that its server then refuses to write. Left
          // to throw, the client would wait for ever and the engine's connection would stay open. The refused
          // call has already stored the text on the response, so the 502 names its own, and the engine's
          // connection goes when this response closes, below.
          refuse(res);
          return;
        }
        // Not pipe: an engine that resets in the middle of a body would leave the client waiting for ever.
        pipeline(answer, res, function () { /* both ends are already closed */ });
      });
      up.on('error', function () {
        if (!res.headersSent) {
          refuse(res);
          return;
        }
        // Part of the answer is on its way already. Ended here, a body sent in chunks would read as
        // whole to the client; cut off, it reads as what it is.
        res.destroy();
      });
      // An answer that never becomes a response leaves nothing to relay and no error either: the engine
      // answering 101 to a request that asked for no upgrade is one, Node's client drops that connection and
      // says nothing. The client would wait for ever.
      up.on('close', function () {
        if (!res.headersSent && !res.destroyed) refuse(res);
      });
      res.on('close', function () { up.destroy(); });
      req.pipe(up);
    });
    // The page the engine serves opens a WebSocket back to it.
    server.on('upgrade', function (req, socket, head) {
      if (!ownHost(req.headers.host, front)) {
        // Node takes its own error listener off an upgrade socket, and a client that resets the connection
        // would raise an error nobody listens for.
        socket.on('error', function () {});
        socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
        return;
      }
      var up = net.connect(back, '127.0.0.1', function () {
        var lines = [req.method + ' ' + req.url + ' HTTP/1.1'];
        for (var i = 0; i < req.rawHeaders.length; i += 2) {
          lines.push(req.rawHeaders[i] + ': ' + (/^host$/i.test(req.rawHeaders[i]) ? '127.0.0.1:' + back : req.rawHeaders[i + 1]));
        }
        up.write(lines.join('\r\n') + '\r\n\r\n');
        if (head && head.length) up.write(head);
        up.pipe(socket);
        socket.pipe(up);
      });
      var idle = function () { socket.destroy(); up.destroy(); };
      socket.setTimeout(TUNNEL_IDLE_MS, idle);
      up.setTimeout(TUNNEL_IDLE_MS, idle);
      up.on('error', function () { socket.destroy(); });
      socket.on('error', function () { up.destroy(); });
    });
    server.maxConnections = RELAY_CONNECTIONS;
    server.on('connection', function (socket) {
      sockets.add(socket);
      socket.on('close', function () { sockets.delete(socket); });
    });
    server.on('error', function (error) {
      console.error('[vscodroid] hub relay on ' + front + ': ' + error.message);
      // Without the relay the extension would poll a port the engine is not on, or one that someone else holds,
      // so the engine is ended. The extension tries a new port only when the engine itself says its port is in
      // use, and restarts on its own only an engine that was ready, so this start fails with "Server failed to
      // start: process terminated by signal SIGTERM" and the engine stays down until something starts it again,
      // such as a reload of the window. The port is one the extension found free a moment earlier, so this needs
      // another app to take it in between.
      if (error.code === 'EADDRINUSE' || error.code === 'EACCES') {
        try { child.kill(); } catch (e) { /* already gone */ }
      }
    });
    server.listen(front, '127.0.0.1');
    child.once('exit', function () {
      server.close();
      sockets.forEach(function (socket) { socket.destroy(); });
    });
  }

  // When the system kills the editor server the app starts another, and the
  // engine of the first outlives it: a killed process forwards nothing to its
  // children. The orphan keeps its port, a page that carries the session token
  // and about 200 MB. So each start ends the one before it. The pid and port of
  // the engine started last are kept in a note, and the note is believed only
  // while that pid still runs the engine on that port.
  var HUB_NOTE = HOME ? path.join(HOME, '.vscodroid', 'antigravity-hub.json') : null;

  function endEarlierHub() {
    if (!HUB_NOTE) return;
    try {
      var note = JSON.parse(fs.readFileSync(HUB_NOTE, 'utf8'));
      var pid = Number(note.pid);
      if (!(pid > 1)) return;
      var words = fs.readFileSync('/proc/' + pid + '/cmdline', 'latin1').split('\0');
      // The engine as this file starts it: the linker, the engine's path, then its arguments. A process
      // that only names the engine among its arguments is not it, whatever pid it was given.
      if (words[0] === LINKER && ANTIGRAVITY_ENGINE.test(words[1] || '') && words.indexOf('--hub-port=' + note.port) >= 0) {
        process.kill(pid, 'SIGTERM');
      }
    } catch (e) { /* no note, or that pid is gone */ }
  }

  function keepHubNote(child, port) {
    if (!HUB_NOTE) return;
    try {
      fs.writeFileSync(HUB_NOTE, JSON.stringify({ pid: child.pid, port: port }));
    } catch (e) { return; }
    child.once('exit', function () {
      try {
        if (JSON.parse(fs.readFileSync(HUB_NOTE, 'utf8')).pid === child.pid) fs.unlinkSync(HUB_NOTE);
      } catch (e) { /* already replaced or removed */ }
    });
  }

  // The extension host starts only when the page connects, which can be long
  // after the app restarted the server, so the server's own start ends an engine
  // the last one left behind as well. Only on the main thread of the editor
  // server: the extension host and the terminal host load this file too, and the
  // terminal host starts when the first terminal opens, with an engine already
  // running. A worker has no argv[1] while preloads run on Node 22, so the name
  // test alone already skips it; the thread test keeps that from depending on it.
  // The file itself and not its name: NODE_OPTIONS loads this into every Node
  // process a terminal starts, and a script of the same name elsewhere is the
  // person's own. server.js forks the one beside this file.
  var EDITOR_SERVER = path.join(__dirname, 'vscode-reh', 'out', 'server-main.js');
  var editorServer = false;
  try {
    editorServer = !!process.argv[1] && fs.realpathSync(process.argv[1]) === fs.realpathSync(EDITOR_SERVER);
  } catch (e) { /* not a file, or no editor server here */ }
  if (require('worker_threads').isMainThread && editorServer) {
    endEarlierHub();
    // An engine the extension host started has no parent to wait for it once this process is gone. The app
    // stops the server with SIGTERM, which the server does not handle (it dies at once and runs no exit
    // handler), so the engine is ended here, and the signal is then given back to the process, which dies of
    // it as before. A server killed with SIGKILL reaches neither, and the next start ends the engine. A server
    // that exits by itself ends it in the exit handler.
    var onTerm = function () {
      endEarlierHub();
      process.removeListener('SIGTERM', onTerm);
      process.kill(process.pid, 'SIGTERM');
    };
    process.on('SIGTERM', onTerm);
    process.on('exit', endEarlierHub);
  }

  /**
   * What a child_process call of `file` with `rest` after it becomes, for spawn, execFile and the promisified
   * execFile alike: `call`, the arguments for the linker, or none when the call is left as written, and
   * `hub`, the relay to put in front of an engine only spawn (`relay`) starts. See ENGINE_SETTING for ~/.gemini.
   */
  function engineCall(file, rest, relay) {
    var gemini = typeof file === 'string' && fromGemini(file);
    var on = gemini && engineTurnedOn();
    var args = Array.isArray(rest[0]) ? rest[0] : [];
    // The options are the first of what follows, or the second after an argument list, as in throughLinker.
    var options = Array.isArray(rest[0]) || rest[0] == null ? rest[1] : rest[0];
    var hub = on ? antigravityHub(file, args, options && options.env) : null;
    var versionCheck = ANTIGRAVITY_ENGINE.test(file) && args.length === 1 && args[0] === '--version';
    var refused = (gemini && !on && !versionCheck) || hub === false;
    // An engine an earlier extension host started is ended as well, so that turning the setting off and
    // reloading the window leaves none running. The version check that is let through ends it too: the host
    // that started the engine may have ended without a word.
    if (refused || (gemini && !on)) endEarlierHub();
    if (refused) return {};
    if (!relay) hub = null;
    var call = throughLinker(file, rest, hub ? function () { return hub.args; } : null);
    return { call: call, hub: call && hub };
  }

  var realSpawn = childProcess.spawn;
  childProcess.spawn = function (file) {
    if (typeof file === 'string' && CODEX_BUNDLED.test(file)) {
      var command = (process.env.PREFIX || '') + '/libexec/tcbin/codex';
      if (fs.existsSync(command)) {
        var args = Array.prototype.slice.call(arguments);
        args[0] = command;
        return realSpawn.apply(this, args);
      }
    }
    var gate = engineCall(file, Array.prototype.slice.call(arguments, 1), true);
    if (!gate.call) return realSpawn.apply(this, arguments);
    var hub = gate.hub;
    if (hub) endEarlierHub();
    var started = realSpawn.apply(this, gate.call);
    if (hub && started && started.pid !== undefined) {
      try {
        relayHub(hub.front, hub.back, started);
        keepHubNote(started, hub.back);
      } catch (e) {
        console.error('[vscodroid] hub relay on ' + hub.front + ': ' + e.message);
        try { started.kill(); } catch (killed) { /* already gone */ }
      }
    }
    return started;
  };

  // execFile reaches the internal spawn, not the exported one replaced above, and
  // the Antigravity extension runs its engine once with it (`agy --version`) to
  // check what it downloaded; when that fails with EACCES it downloads the engine
  // again. spawnSync and execFileSync are not wrapped: the extension calls neither.
  var promisify = require('util').promisify;
  var realExecFile = childProcess.execFile;
  var realExecFilePromised = realExecFile[promisify.custom];

  // The call execFile is to make: the same rules as spawn, without a relay, so an engine's hub started
  // this way with the setting on is an ordinary program on the port it was given.
  function execFileCall(file, all) {
    return engineCall(file, Array.prototype.slice.call(all, 1), false).call || all;
  }
  childProcess.execFile = function (file) {
    return realExecFile.apply(this, execFileCall(file, arguments));
  };
  // util.promisify(execFile) resolves { stdout, stderr } through this property of the original.
  if (realExecFilePromised) {
    childProcess.execFile[promisify.custom] = function (file) {
      return realExecFilePromised.apply(this, execFileCall(file, arguments));
    };
  }
}
