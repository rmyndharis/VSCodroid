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
 * Loaded via NODE_OPTIONS="--require=<path>/platform-fix.js" for all Node.js processes.
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
}
