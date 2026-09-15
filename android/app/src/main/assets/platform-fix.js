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
}
