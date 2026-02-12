/**
 * VSCodroid platform compatibility fix — SELECTIVE override.
 *
 * Termux-patched Node.js reports process.platform === "android" instead of "linux".
 * Many npm packages (Prisma, node-gyp, etc.) don't recognize "android" and fail
 * during platform detection or native binary download.
 *
 * However, some tools (Rollup 4.57+, esbuild) have native android-arm64 builds.
 * A global override to "linux" breaks them — they try linux-arm64-musl instead.
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

  // Auto-detect node-gyp (spawned by npm as subprocess)
  if (!shouldFix) {
    for (var i = 1; i < process.argv.length; i++) {
      if (process.argv[i] && process.argv[i].indexOf('node-gyp') !== -1) {
        shouldFix = true;
        break;
      }
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
