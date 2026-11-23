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
  var PYTHON_BUNDLE = /[\\/]ms-python\.python-[^\\/]+[\\/]out[\\/]client[\\/]extension\.js$/;
  var linuxOs = null;
  Module._load = function (request, parent) {
    if ((request === 'os' || request === 'node:os') && parent &&
        (JUPYTER_BUNDLE.test(parent.filename || '') || PYTHON_BUNDLE.test(parent.filename || ''))) {
      if (!linuxOs) {
        linuxOs = Object.assign({}, realLoad.apply(this, arguments), {
          platform: function () { return 'linux'; }
        });
      }
      return linuxOs;
    }
    return realLoad.apply(this, arguments);
  };

  // The Python extension checks process.platform against /^linux/ to set its
  // OSType, which falls through to OSType.Unknown on Android. Open VSX updates
  // replace the build-time patched copy with an upstream bundle, so rewrite
  // that expression at compile time for this bundle alone while leaving
  // process.platform and every other file untouched.
  var PYTHON_PLATFORM_TEST = /\/\^linux\/\.test\(([\w$]+(?:\.[\w$]+)*)\)\s*\?\s*([\w$]+(?:\.[\w$]+)*)\.Linux\s*:\s*\2\.Unknown/g;
  var PYTHON_PLATFORM_PATCHED = /\/\^linux\|\^android\/\.test\(([\w$]+(?:\.[\w$]+)*)\)\s*\?\s*([\w$]+(?:\.[\w$]+)*)\.Linux\s*:\s*\2\.Unknown/;
  var realCompile = Module.prototype._compile;
  Module.prototype._compile = function (content, filename) {
    if (typeof filename === 'string' && PYTHON_BUNDLE.test(filename) && typeof content === 'string') {
      var rewritten = content.replace(PYTHON_PLATFORM_TEST, function (_, arg, enumRef) {
        return '/^linux|^android/.test(' + arg + ')?' + enumRef + '.Linux:' + enumRef + '.Unknown';
      });
      if (rewritten === content && !PYTHON_PLATFORM_PATCHED.test(content)) {
        console.warn('[vscodroid] python platform check pattern not found in ' + filename);
      }
      var args = Array.prototype.slice.call(arguments);
      args[0] = rewritten;
      return realCompile.apply(this, args);
    }
    return realCompile.apply(this, arguments);
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
  // Only spawn is redirected, which is the call the extension makes. execFile,
  // the synchronous starts and fork reach Node's internal spawn and spawnSync
  // through references of their own; the wrappers further down for all but fork
  // carry the rules below, not this redirect.
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
  // terminal's interceptor, which does this itself. The same for spawnSync and
  // execFileSync, and for a call that names the linker itself in front of the
  // program, which is taken as a call of that program.
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

  // A program started through the linker is also given the terminal's exec interceptor, and its own path. The
  // linker names itself as the program's path, so a program that finds its files from that path looks beside the
  // linker; clang and libLLVM read TERMUX_EXEC__PROC_SELF_EXE first, which is how the interceptor answers that in
  // a terminal. And without the interceptor, whatever that program starts from the app's storage (a `#!` script,
  // the linker a compiler runs, a program it built) is refused the way the first start would have been. A command
  // run in a shell gets neither: with the interceptor, an extension could run any script or program in the app's
  // storage by naming it in a command, and Android's refusal of such an exec is what stops that.
  //
  // The two go together and only while the interceptor is a file on disk: Bionic aborts every exec in an
  // environment whose preload it cannot map, and the interceptor is what replaces or clears the path for the
  // programs below. Found under TERMUX__PREFIX, as the interceptor finds the app's own files
  // (Environment.getExecPreloadPath names the same file), read once as HOME is. Each call gets a copy of its own
  // environment, or of this process's when it names none; this process's own is never changed, so nothing else
  // the host starts carries the interceptor.
  var EXEC_PRELOAD = process.env.TERMUX__PREFIX ? path.join(process.env.TERMUX__PREFIX, 'lib', 'libtermux-exec.so') : null;
  // What the interceptor reads to tell the app's paths from the system's. The server environment carries them; a
  // call with an environment of its own may leave them out.
  var INTERCEPTOR_VARS = {};
  ['TERMUX_APP__DATA_DIR', 'TERMUX_APP__LEGACY_DATA_DIR', 'TERMUX__PREFIX'].forEach(function (name) {
    if (process.env[name]) INTERCEPTOR_VARS[name] = process.env[name];
  });

  /**
   * `base` (a call's environment, or this process's when null) with the interceptor preloaded ahead of anything it
   * preloads already, the variables the interceptor reads and `self`, the program's own path; null when the
   * interceptor is not a file now, this process already has it, or `base` is not an object. The copy takes the keys
   * `base` inherits as well, as Node reads an environment (for...in): an extension may hand over
   * Object.create(process.env) with its own changes, and a copy of the own keys alone left the program no PATH.
   */
  function interceptorEnv(base, self) {
    if (!EXEC_PRELOAD || INTERCEPTED || (base != null && typeof base !== 'object')) return null;
    try {
      if (!fs.statSync(EXEC_PRELOAD).isFile()) return null;
    } catch (e) {
      return null;
    }
    var from = base || process.env;
    var env = {};
    for (var key in from) env[key] = from[key];
    var preloads = typeof env.LD_PRELOAD === 'string' ? env.LD_PRELOAD : '';
    if (preloads.split(/[:\s]+/).indexOf(EXEC_PRELOAD) < 0) env.LD_PRELOAD = preloads ? EXEC_PRELOAD + ':' + preloads : EXEC_PRELOAD;
    Object.keys(INTERCEPTOR_VARS).forEach(function (name) {
      if (env[name] == null) env[name] = INTERCEPTOR_VARS[name];
    });
    env.TERMUX_EXEC__PROC_SELF_EXE = self;
    return env;
  }

  /**
   * `tail`, what follows a call's argument list (its options, then a callback), with `extra` in a copy of the
   * options, or in options of its own put before the callback when the call has none.
   */
  function withOptions(tail, extra) {
    var out = tail.slice();
    if (out.length && (out[0] == null || typeof out[0] === 'object')) out[0] = Object.assign({}, out[0], extra);
    else out.unshift(extra);
    return out;
  }

  /**
   * The directory a call made through `how` (spawn, execSync, ...) runs in, as a path; null when it cannot be read
   * as one, which could be ~/.gemini. Node takes a Uint8Array for it as well: a synchronous start runs in the string
   * it makes of it, String(cwd), which for one that is not a Buffer is a directory of comma-separated numbers, and an
   * asynchronous start ignores it and runs in this process's own directory (measured on Node 22 and 24).
   */
  function cwdOf(options, how) {
    try {
      var cwd = options && options.cwd;
      if (require('util').types.isUint8Array(cwd)) cwd = /Sync$/.test(how) ? String(cwd) : null;
      if (!cwd) return process.cwd();
      return path.resolve(typeof cwd === 'string' ? cwd : require('url').fileURLToPath(cwd));
    } catch (e) {
      return null;
    }
  }

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
   * The text of `file` when the open file is a regular one of at most `limit` bytes, else null; throws when it
   * cannot be opened. O_NONBLOCK, and the open descriptor judged, as bionicInAppData opens a program: a FIFO put
   * at the path, or a link to one, held a plain open until a writer came, and the thread that read it waited for good.
   */
  function regularFileText(file, limit) {
    var fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NONBLOCK);
    try {
      var stat = fs.fstatSync(fd);
      return stat.isFile() && stat.size <= limit ? fs.readFileSync(fd, 'utf8') : null;
    } finally {
      fs.closeSync(fd);
    }
  }

  /**
   * What follows `file` in a child_process call (an argument list, options and a
   * callback, whichever are given) as a call to `linker64 <file> <args>` with the
   * interceptor and the program's own path in its options and no argv0, or null
   * when the call is left alone. `change` may replace the argument list; `cwd` is where the call runs (cwdOf).
   */
  function throughLinker(file, rest, change, cwd) {
    if (typeof file !== 'string') return null;
    // Node takes null or undefined for the argument list, and options then follow it.
    var listed = Array.isArray(rest[0]) || rest[0] == null;
    var tail = rest.slice(listed ? 1 : 0);
    var options = tail[0];
    if ((options && options.shell) || !bionicInAppData(file)) return null;
    var args = Array.isArray(rest[0]) ? rest[0] : [];
    // Not to a start that could reach ~/.gemini, the engine's own included (see ENGINE_SETTING).
    var env = geminiNamed([file].concat(args), options, false, cwd) ? null : interceptorEnv(options && options.env, file);
    // Node would hand argv0 to the linker as its own argv[0], which the linker does not read: the program it starts
    // is given its path there either way. All argv0 changed was the first word of the process in /proc, where the
    // process monitor and endEarlierHub look for the linker, so a copy of the options goes without it.
    if (env || (options !== null && typeof options === 'object')) {
      tail = withOptions(tail, env ? { env: env } : {});
      delete tail[0].argv0;
    }
    return [LINKER, [file].concat(change ? change(args) : args)].concat(tail);
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
  // signed in to the engine. The engine has no option that closes this. So the app holds back the starts it
  // sees of anything in ~/.gemini, where the extension keeps what it downloads, until the person has turned on
  // `vscodroid.antigravity.runEngine`, a machine setting the process monitor extension declares and
  // offers when it sees the Antigravity extension. With the setting off such a call is left as it was
  // written and fails with EACCES, as it always did here, and one that writes the linker out in front of
  // the program is made without it, which fails the same way, whatever its arguments: the engine is a Go
  // program whose flag parser takes `-hub` and `--hub=true` for `--hub`, it has a second listening
  // mode (`--remote-control`), and the extension and the engine both update themselves, so a list of
  // the arguments that start a listener would not stay complete. The one call let through is exactly
  // `agy --version`, which the extension runs to check the engine it downloaded and answers by
  // downloading the engine again when it fails. This is about what extensions start: a terminal runs
  // what the person types there through its own exec interceptor.
  //
  // The terminal's interceptor, which a start through the linker is given, would start a program in ~/.gemini
  // as readily as any other. So a start that could reach ~/.gemini gets none, whatever the setting says: one
  // that names a path there among its program and arguments, runs there, or searches a PATH directory there,
  // and the engine itself, which runs as it always did. A command run in a shell gets no interceptor at all, so
  // the shell's own start of anything there is refused; but the shell can run the linker. So the command is read
  // as words, quotes and backslashes taken out (a backslash before a line break with the break, which the shell
  // joins) and a leading `~` or `$HOME` read as the home, and with the setting off one that
  // names something in ~/.gemini (a path there, a directory it runs in or searches, or a word that spells the
  // name) and also names the linker is made as a start of what it names, without the shell, which fails the
  // same way. Only what the call spells out can be seen: a program that is told what to run (`sh -c` written as
  // a program and its arguments, an interpreter, `env` or `nohup` in front of the linker, `fork` given the linker as its `execPath`, which
  // child_process starts through a reference of its own), a name the shell builds from a variable, a pattern or
  // an escape it expands itself (`$'\x36'`), and a path that goes through a link and then back up with `..` (read as
  // text, where the kernel follows the link first) are beyond this. Neither is a fence against code in the
  // extension host, which could copy the engine anywhere; it holds back what an extension that means no harm
  // starts on its own.
  //
  // Read from the machine settings file at each such call, and the way VS Code builds its settings from
  // it: the keys of the root object are split at their dots into a tree, in file order, so
  // `"vscodroid.antigravity.runEngine": true`, `"vscodroid.antigravity": {"runEngine": true}` and the fully
  // nested form all name this setting, a later key replaces what an earlier one made, and a key whose
  // parent is a scalar is dropped. The same key inside another object, a language block included, is not
  // this setting. A file that does not parse turns nothing on, nor does one past SETTINGS_BYTES or nested past
  // SETTINGS_DEPTH, nor anything there that is not a regular file (regularFileText). VS Code recovers from some
  // syntax errors (a missing comma) and need not share those two limits, so the Settings editor can show the box
  // ticked over a file this reads as off, which keeps the engine down, the safe side of the two.
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

  var SETTINGS_FILE = HOME ? path.join(HOME, '.vscodroid', 'data', 'Machine', 'settings.json') : null;

  function engineTurnedOn() {
    if (!SETTINGS_FILE) return false;
    try {
      var text = regularFileText(SETTINGS_FILE, SETTINGS_BYTES);
      var json = text === null ? null : settingsAsJson(text);
      return json !== null && engineValue(JSON.parse(json));
    } catch (e) {
      return false;
    }
  }

  // The gate's own reading, for the process monitor extension: VS Code reads the setting more leniently than
  // engineValue does, so the Settings editor can show it on while the engine stays off, and the extension says so.
  globalThis[Symbol.for('vscodroid.engineGate')] = Object.freeze({ engineTurnedOn: engineTurnedOn, settingsFile: SETTINGS_FILE });

  /**
   * The real path of `file`, from the system's resolver where it answers as the JavaScript one does: one call where
   * the JavaScript one looks at each part of the path in turn, which every word of a long command paid for (the
   * shim's own work for a program given 20,000 arguments with ~/.gemini present: 50 ms against 80 on Linux with
   * Node 24, 105 against 125 on macOS with Node 22). The JavaScript one first resolves the path as text (`..` and a
   * trailing slash), so this does too; the two then agreed on links, `..` after a link, loops and a FIFO on Linux
   * and macOS. Bionic's resolver was not compared there, so NATIVE_REALPATH asks both once, on this file's own
   * directory, which a device reaches through the /data/user/0 link, and a resolver that fails or answers otherwise
   * is not used.
   */
  var NATIVE_REALPATH = false;
  try {
    NATIVE_REALPATH = fs.realpathSync.native(__dirname) === fs.realpathSync(__dirname);
  } catch (e) { /* the JavaScript resolver */ }

  function realPathOf(file) {
    var resolved = path.resolve(file);
    return NATIVE_REALPATH ? fs.realpathSync.native(resolved) : fs.realpathSync(resolved);
  }

  /** The real path of ~/.gemini, or null while there is none. Read at each call: the extension makes it later. */
  function geminiRoot() {
    if (!HOME) return null;
    try {
      return realPathOf(path.join(HOME, '.gemini'));
    } catch (e) {
      return null;
    }
  }

  /** Whether the real path of `file` is `root` (geminiRoot) or inside it; an error of any kind says it is not. */
  function within(root, file) {
    if (root === null || !path.isAbsolute(file)) return false;
    try {
      var real = realPathOf(file);
      return real === root || real.indexOf(root + '/') === 0;
    } catch (e) {
      return false;
    }
  }

  /** Whether the real path of `file` is ~/.gemini or inside it; an error of any kind says it is not. */
  function fromGemini(file) {
    return within(geminiRoot(), file);
  }

  /**
   * What a call could reach in ~/.gemini: the first of `words` (its program and arguments, or the words of its
   * shell command when `shell` is set) that is a path there, read from `cwd`, the directory the call runs in
   * (cwdOf); else ~/.gemini itself when that directory is there or cannot be read as a path, a directory of the
   * PATH the call searches is there, or a word of a shell command spells the name; null for none. See
   * ENGINE_SETTING.
   */
  function geminiNamed(words, options, shell, cwd) {
    var gemini = HOME ? path.join(HOME, '.gemini') : null;
    // Resolved once for the call, not once for each word: a command or an argument list may hold thousands.
    var root = gemini && path.isAbsolute(gemini) ? geminiRoot() : null;
    if (root === null) return null;
    if (cwd === null) return gemini;
    var spelled = false;
    for (var i = 0; i < words.length; i++) {
      var word = String(words[i]);
      if (shell) word = word.replace(/["'\\]/g, '').replace(/^(?:~|\$\{HOME\}|\$HOME)(?=\/|$)/, HOME);
      if (!word) continue;
      var full = path.resolve(cwd, word);
      if (within(root, full)) return full;
      if (shell && word.indexOf('.gemini') >= 0) spelled = true;
    }
    var env = (options && options.env) || process.env;
    var dirs = typeof env.PATH === 'string' ? env.PATH.split(':') : [];
    var searched = dirs.some(function (dir) { return dir !== '' && within(root, path.resolve(cwd, dir)); });
    return spelled || searched || within(root, cwd) ? gemini : null;
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
  var HUB_ARGUMENT = /^--(?:hub|hub-port=[1-9]\d{0,4}|app_data_dir=[\s\S]*|add-dir=[\s\S]*)$/;
  var HUB_LISTENER_FLAG = /^--?(?:hub|hub-port|remote-control)(?:=|$)/;
  var TOKEN_FLAG = '--csrf_token=';
  var TOKEN_ENV = 'ANTIGRAVITY_SERVER_CSRF_TOKEN';

  function canonicalPath(file) {
    try { return realPathOf(file); } catch (e) { return path.resolve(file); }
  }

  function isEnginePath(file) {
    if (!file) return false;
    return ANTIGRAVITY_ENGINE.test(path.resolve(file)) || ANTIGRAVITY_ENGINE.test(canonicalPath(file));
  }

  /**
   * A port nothing holds on 127.0.0.1 at this moment, other than `not`: bound without listening and let
   * go, as the extension picks its own. Synchronous, because [antigravityHub] sits inside a spawn that
   * must return its child at once, and a listening server answers its address only after a lookup.
   * Null where this runtime has no such handle (it is an underscore export of net) or refuses one.
   */
  function loopbackPortNow(not) {
    try {
      for (var i = 0; i < 3; i++) {
        var handle = require('net')._createServerHandle('127.0.0.1', 0, 4);
        if (typeof handle === 'number') return null;
        var out = {};
        var failed = handle.getsockname(out);
        handle.close();
        if (!failed && out.port !== not) return out.port;
      }
    } catch (e) { /* the runtime has no such handle: the caller draws one */ }
    return null;
  }

  /**
   * For `agy --hub --hub-port=<n>`: the port the extension chose, the one the
   * engine is given instead and the argument list that says so. A port nothing
   * held a moment ago, the way the extension picks its own ([loopbackPortNow]);
   * where that cannot be had, a random one in the dynamic range. A clash in the
   * moment between the pick and the engine's bind makes the engine print
   * `port <n> is already in use`, which the extension's own port collision
   * handling matches; that path was read, not run. False for that
   * start with an argument outside HUB_ARGUMENT, null for anything else, which
   * runs as an ordinary program would, with no relay and no note.
   */
  function antigravityHub(file, args, env) {
    if (!isEnginePath(file)) return null;
    // Node starts an argument that is not a string as its text, so it is read as that text.
    args = args.map(String);
    if (!args.some(function (arg) { return HUB_LISTENER_FLAG.test(arg); })) return null;
    if (args.indexOf('--hub') < 0) return false;
    var ports = args.filter(function (arg) { return /^--hub-port=/.test(arg); });
    if (!ports.length) return false;
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
    if (front === 0 || front > 65535) return false;
    var back = loopbackPortNow(front);
    if (back === null) {
      back = front;
      while (back === front) back = 20000 + Math.floor(Math.random() * 40000);
    }
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
  // and the memory of a large process. So each start ends the one before it. The pid and port of
  // the engine started last are kept in a note, and the note is believed only
  // while that pid still runs the engine on that port.
  var HUB_NOTE = HOME ? path.join(HOME, '.vscodroid', 'antigravity-hub.json') : null;

  function endEarlierHub() {
    if (!HUB_NOTE) return;
    try {
      var text = regularFileText(HUB_NOTE, Infinity);
      if (text === null) return;
      var note = JSON.parse(text);
      var pid = Number(note.pid);
      if (!(pid > 1)) return;
      var words = fs.readFileSync('/proc/' + pid + '/cmdline', 'latin1').split('\0');
      // The engine as this file starts it: the linker, the engine's path, then its arguments. A process
      // that only names the engine among its arguments is not it, whatever pid it was given.
      if (words[0] === LINKER && isEnginePath(words[1]) && words.indexOf('--hub-port=' + note.port) >= 0) {
        process.kill(pid, 'SIGTERM');
      }
    } catch (e) { /* no note, or that pid is gone */ }
  }

  function keepHubNote(child, port) {
    if (!HUB_NOTE) return;
    try {
      // Into a regular file only, opened without waiting: an open for writing of a FIFO waits for a reader.
      var fd = fs.openSync(HUB_NOTE, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_TRUNC | fs.constants.O_NONBLOCK, 0o666);
      try {
        if (!fs.fstatSync(fd).isFile()) return;
        fs.writeSync(fd, JSON.stringify({ pid: child.pid, port: port }));
      } finally {
        fs.closeSync(fd);
      }
    } catch (e) { return; }
    child.once('exit', function () {
      try {
        var text = regularFileText(HUB_NOTE, Infinity);
        if (text !== null && JSON.parse(text).pid === child.pid) fs.unlinkSync(HUB_NOTE);
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
   * What a child_process call of `file` with `rest` after it becomes, for every start wrapped below: `call`, the
   * arguments to make it with instead, or none when it is made as written; `hub`, the relay to put in front of
   * an engine only spawn starts. `how` names the function called (spawn, spawnSync, execFile or execFileSync).
   * See ENGINE_SETTING for ~/.gemini.
   */
  function startCall(file, rest, how) {
    if (typeof file !== 'string') return {};
    var listed = Array.isArray(rest[0]) || rest[0] == null;
    var tail = rest.slice(listed ? 1 : 0);
    var options = tail[0];
    // Options Node itself refuses are left for Node to refuse: a list, anything but an object or a function, and a
    // function or null where the call takes no callback. spawn and spawnSync take neither; execFileSync takes a
    // function in place of the argument list, and one after it reaches spawnSync as the options.
    var callback = typeof options === 'function';
    if (Array.isArray(options) || (options != null && typeof options !== 'object' && !callback)) return {};
    if (/^spawn/.test(how) && (options === null || callback)) return {};
    if (how === 'execFileSync' && listed && callback) return {};
    // Node acts on the options' own properties alone, an inherited cwd, env or shell ignored, so the call is read,
    // and made when it is rewritten, from a copy of those.
    if (options && typeof options === 'object') {
      tail = [Object.assign({}, options)].concat(tail.slice(1));
      rest = (listed ? [rest[0]] : []).concat(tail);
    }
    var cwd = cwdOf(tail[0], how);
    if (tail[0] && tail[0].shell) return shellCall(file, rest, tail, cwd);
    return engineCall(file, rest, how === 'spawn', cwd, how === 'spawnSync' || how === 'execFileSync');
  }

  function argText(value) {
    try { return '' + value; } catch (e) { return null; }
  }

  /**
   * Freezes an argument array containing any non-string element into plain strings once,
   * preventing stateful objects from returning a different value when Node coerces argv later.
   * Returns null when every entry is already a string (or not an array). For synchronous and
   * shell calls, where Node throws on uncoercible values, returns undefined so Node throws;
   * for async non-shell spawn and execFile, substitutes '' to match ProcessWrap::Spawn.
   */
  function freezeArgs(list, throwOnUncoercible) {
    if (!Array.isArray(list)) return null;
    var hasNonString = false;
    for (var i = 0; i < list.length; i++) {
      if (typeof list[i] !== 'string') { hasNonString = true; break; }
    }
    if (!hasNonString) return null;
    var out = new Array(list.length);
    for (var j = 0; j < list.length; j++) {
      var s = argText(list[j]);
      if (s === null) {
        if (throwOnUncoercible) return undefined;
        s = '';
      }
      out[j] = s;
    }
    return out;
  }

  /**
   * startCall for a command run in a shell, which Node makes of the file and the argument list joined by spaces.
   * It is made as written, with no interceptor, unless the setting refuses it (see ENGINE_SETTING). A command that
   * does not name the linker is not read further: without it the shell's own start of anything in ~/.gemini is
   * refused, and reading every command a shell runs would cost each of them a look at every word. So such a
   * command does not end an earlier engine either, which the engine's own start, its version check, and the
   * editor server's start and SIGTERM still do. `cwd` is where the command runs (cwdOf).
   */
  function shellCall(file, rest, tail, cwd) {
    var frozen = freezeArgs(rest[0], true);
    if (frozen === undefined) return {};
    var argList = frozen !== null ? frozen : (Array.isArray(rest[0]) ? rest[0] : []);
    // A backslash before a line break joins the two lines, in a word as anywhere else, so the pair goes first.
    var text = [file].concat(argList).join(' ').replace(/\\\n/g, '');
    if (!/linker64/.test(text.replace(/["'\\]/g, ''))) {
      return frozen !== null ? { call: [file, frozen].concat(tail) } : {};
    }
    var named = geminiNamed(text.split(/[\s;&|()<>`]+/), tail[0], true, cwd);
    if (!named || engineTurnedOn()) {
      return frozen !== null ? { call: [file, frozen].concat(tail) } : {};
    }
    endEarlierHub();
    return { call: [named, []].concat(withOptions(tail, { shell: false })) };
  }

  /** startCall for a call made without a shell, from `cwd` (cwdOf). */
  function engineCall(file, rest, relay, cwd, isSync) {
    var origFile = file;
    var origRest = rest;
    var frozen = freezeArgs(rest[0], isSync);
    if (frozen === undefined) return {};
    if (frozen !== null) {
      rest = [frozen].concat(rest.slice(1));
      origRest = rest;
    }
    var isLinker = path.basename(file) === 'linker64';
    // `linker64 <program> <args>`, the linker written out by the caller, is a start of the program: the same rules
    // decide it, and a refusal is the program started without the linker. A loader option in front of the
    // program (--list) runs nothing.
    var firstArg = isLinker && Array.isArray(rest[0]) && rest[0].length > 0 ? rest[0][0] : null;
    var linked = firstArg !== null && firstArg !== '' && firstArg.charAt(0) !== '-';
    if (linked) {
      file = cwd === null ? firstArg : path.resolve(cwd, firstArg);
      rest = [rest[0].slice(1)].concat(rest.slice(1));
    }
    // A program named from a directory that cannot be read as a path may be in ~/.gemini.
    var gemini = path.isAbsolute(file) ? fromGemini(file) : linked && geminiRoot() !== null;
    var on = gemini && engineTurnedOn();
    var args = Array.isArray(rest[0]) ? rest[0] : [];
    // The options are the first of what follows, or the second after an argument list, as in throughLinker.
    var options = Array.isArray(rest[0]) || rest[0] == null ? rest[1] : rest[0];
    var hub = on ? antigravityHub(file, args, options && options.env) : null;
    var versionCheck = path.isAbsolute(file) && ANTIGRAVITY_ENGINE.test(path.resolve(file)) && args.length === 1 && args[0] === '--version';
    var refused = (gemini && !on && !versionCheck) || hub === false;
    // An engine an earlier extension host started is ended as well, so that turning the setting off and
    // reloading the window leaves none running. The version check that is let through ends it too: the host
    // that started the engine may have ended without a word.
    if (refused || (gemini && !on)) endEarlierHub();
    if (refused) return linked ? { call: [file].concat(rest) } : {};
    if (!relay) hub = null;
    var call = throughLinker(file, rest, hub ? function () { return hub.args; } : null, cwd);
    if (!call && isLinker && frozen !== null) {
      return { call: [origFile].concat(origRest) };
    }
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
    var gate = startCall(file, Array.prototype.slice.call(arguments, 1), 'spawn');
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

  // execFile, execFileSync and execSync reach Node's internal spawn and spawnSync, not the exported ones
  // replaced here, so each is wrapped on its own. The Antigravity extension runs its engine once with execFile
  // (`agy --version`) to check what it downloaded; when that fails with EACCES it downloads the engine again.
  // exec, and its promisified form, reach the exported execFile with `shell` set and the whole command as the
  // file.
  var promisify = require('util').promisify;
  var realExecFile = childProcess.execFile;
  var realExecFilePromised = realExecFile[promisify.custom];
  var realSpawnSync = childProcess.spawnSync;
  var realExecFileSync = childProcess.execFileSync;
  var realExecSync = childProcess.execSync;

  // The call to make: the same rules as spawn, without a relay, so an engine's hub started this way with the
  // setting on is an ordinary program on the port it was given.
  function plainCall(all, how) {
    return startCall(all[0], Array.prototype.slice.call(all, 1), how).call || all;
  }
  childProcess.execFile = function () {
    return realExecFile.apply(this, plainCall(arguments, 'execFile'));
  };
  // util.promisify(execFile) resolves { stdout, stderr } through this property of the original.
  if (realExecFilePromised) {
    childProcess.execFile[promisify.custom] = function () {
      return realExecFilePromised.apply(this, plainCall(arguments, 'execFile'));
    };
  }
  childProcess.spawnSync = function () {
    return realSpawnSync.apply(this, plainCall(arguments, 'spawnSync'));
  };
  childProcess.execFileSync = function () {
    return realExecFileSync.apply(this, plainCall(arguments, 'execFileSync'));
  };
  childProcess.execSync = function (command, options) {
    // Node runs the command whatever stands in place of the options: it reads their own properties, and a string,
    // a number or a function has none it uses. So the command is read as it runs, never handed on unread.
    var read = options !== null && typeof options === 'object' ? Object.assign({}, options) : {};
    // A refusal is a start of what the command names without a shell, which execSync cannot make.
    var gate = shellCall(command, [read], [read], cwdOf(read, 'execSync'));
    return gate.call ? realExecFileSync.apply(this, gate.call) : realExecSync.apply(this, arguments);
  };
}
