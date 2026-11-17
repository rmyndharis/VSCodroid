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
  // signed in to the engine. The engine has no option that closes this. So the app does not start the
  // engine's hub until the person has turned on `vscodroid.antigravity.runEngine`, a machine setting
  // the process monitor extension declares and offers when it sees the Antigravity extension. With the
  // setting off the extension's spawn is left as it was written and fails with EACCES, as it always
  // did here.
  //
  // Read from the machine settings file at each hub start, the way the app reads its heap setting:
  // comments are removed, strings are kept, and the key counts only at the start of a line, so one
  // that is commented out or quoted inside another value does not turn it on.
  var ENGINE_SETTING = /^\s*"vscodroid\.antigravity\.runEngine"\s*:\s*true\b/m;
  var STRING_OR_COMMENT = /"(?:\\.|[^"\\])*"|\/\/[^\n]*|\/\*[\s\S]*?\*\//g;

  function engineTurnedOn() {
    if (!process.env.HOME) return false;
    try {
      var settings = fs.readFileSync(path.join(process.env.HOME, '.vscodroid', 'data', 'Machine', 'settings.json'), 'utf8');
      return ENGINE_SETTING.test(settings.replace(STRING_OR_COMMENT, function (found) {
        return found.charAt(0) === '"' ? found : '';
      }));
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

  /**
   * For `agy --hub --hub-port=<n>`: the port the extension chose, the one the
   * engine is given instead and the argument list that says so. A random port
   * in the dynamic range, as the extension's own is. A clash makes the engine
   * print `port <n> is already in use`, which the extension's own port
   * collision handling matches; that path was read, not run.
   */
  function antigravityHub(file, args) {
    if (!ANTIGRAVITY_ENGINE.test(file) || args.indexOf('--hub') < 0) return null;
    for (var i = 0; i < args.length; i++) {
      var match = /^--hub-port=(\d{1,5})$/.exec(args[i]);
      if (!match || Number(match[1]) === 0 || Number(match[1]) > 65535) continue;
      var front = Number(match[1]);
      var back = front;
      while (back === front) back = 20000 + Math.floor(Math.random() * 40000);
      var changed = args.slice();
      changed[i] = '--hub-port=' + back;
      return { front: front, back: back, args: changed };
    }
    return null;
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

  /** Answers on `front` and relays to the engine on `back`, 502 while it is not accepting; closes when `child` ends. */
  function relayHub(front, back, child) {
    var http = require('http');
    var net = require('net');
    var pipeline = require('stream').pipeline;
    var sockets = new Set();
    var server = http.createServer(function (req, res) {
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
        res.writeHead(answer.statusCode, answer.statusMessage, sent);
        // Not pipe: an engine that resets in the middle of a body would leave the client waiting for ever.
        pipeline(answer, res, function () { /* both ends are already closed */ });
      });
      up.on('error', function () {
        if (!res.headersSent) res.writeHead(502);
        res.end();
      });
      res.on('close', function () { up.destroy(); });
      req.pipe(up);
    });
    // The page the engine serves opens a WebSocket back to it.
    server.on('upgrade', function (req, socket, head) {
      if (!ownHost(req.headers.host, front)) {
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
      up.on('error', function () { socket.destroy(); });
      socket.on('error', function () { up.destroy(); });
    });
    server.on('connection', function (socket) {
      sockets.add(socket);
      socket.on('close', function () { sockets.delete(socket); });
    });
    server.on('error', function (error) {
      console.error('[vscodroid] hub relay on ' + front + ': ' + error.message);
      // Without the relay the extension would poll a port the engine is not on, or one that someone else holds.
      // An engine that ends is something it recovers from, by starting another on a port it picks again.
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
  var HUB_NOTE = process.env.HOME ? path.join(process.env.HOME, '.vscodroid', 'antigravity-hub.json') : null;

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
  if (require('worker_threads').isMainThread && /(^|[\\/])server-main\.js$/.test(process.argv[1] || '')) {
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
    var hub = null;
    var turnedOff = false;
    var call = throughLinker(file, Array.prototype.slice.call(arguments, 1), function (args) {
      // Every hub start, with a port to relay or without: see ENGINE_SETTING.
      turnedOff = ANTIGRAVITY_ENGINE.test(file) && args.indexOf('--hub') >= 0 && !engineTurnedOn();
      hub = turnedOff ? null : antigravityHub(file, args);
      return hub ? hub.args : args;
    });
    if (call && !turnedOff) {
      if (hub) endEarlierHub();
      var started = realSpawn.apply(this, call);
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
    }
    // Turned off: an engine an earlier extension host started is ended as well, so that turning the
    // setting off and reloading the window leaves none running.
    if (turnedOff) endEarlierHub();
    return realSpawn.apply(this, arguments);
  };

  // execFile reaches the internal spawn, not the exported one replaced above, and
  // the Antigravity extension runs its engine once with it (`agy --version`) to
  // check what it downloaded; when that fails with EACCES it downloads the engine
  // again. spawnSync and execFileSync are not wrapped: the extension calls neither.
  var promisify = require('util').promisify;
  var realExecFile = childProcess.execFile;
  var realExecFilePromised = realExecFile[promisify.custom];
  childProcess.execFile = function (file) {
    return realExecFile.apply(this, throughLinker(file, Array.prototype.slice.call(arguments, 1)) || arguments);
  };
  // util.promisify(execFile) resolves { stdout, stderr } through this property of the original.
  if (realExecFilePromised) {
    childProcess.execFile[promisify.custom] = function (file) {
      return realExecFilePromised.apply(this, throughLinker(file, Array.prototype.slice.call(arguments, 1)) || arguments);
    };
  }
}
