/**
 * VSCodroid platform compatibility fix.
 *
 * Termux-patched Node.js reports process.platform === "android" instead of "linux".
 * Many npm packages (Prisma, node-gyp, esbuild, sharp, etc.) don't recognize "android"
 * and fail during platform detection or native binary download.
 *
 * This preload script overrides process.platform to "linux" so the npm ecosystem
 * works as expected. Android's kernel IS Linux — the difference is Bionic libc vs glibc,
 * but most pure-JS platform checks only need to know the kernel.
 *
 * Loaded via NODE_OPTIONS="--require=<path>/platform-fix.js" for all Node.js processes.
 */
'use strict';

if (process.platform === 'android') {
  Object.defineProperty(process, 'platform', {
    value: 'linux',
    writable: false,
    enumerable: true,
    configurable: true
  });
}
