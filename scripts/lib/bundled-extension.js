'use strict';

// Resolving a bundled extension by its version is how a self-check rots.
//
// Bumping the version of a bundled extension is mandatory in this repository
// whenever its contents change: a device only re-extracts a directory whose name
// it does not already have. So a path pinned to one version is a path that
// breaks on the next correct change, and the version written into it is stale
// the moment anyone edits the extension. That is not hypothetical; it is how
// `scripts/test-process-monitor-extension.js` came to fail on a branch whose
// only fault was bumping the extension it tests.
//
// Ordered by parsed version rather than by name, because 1.10.0 sorts below
// 1.9.0 as text. `check-welcome-claims.py` sorts its directory the same way for
// the same reason. The comparison here is not identical to that one: it
// zero-pads a shorter tail where Python's tuple compare would sort it first,
// which differs only between 1.2 and 1.2.0 and does not arise in this tree.

const fs = require('fs');
const path = require('path');

const EXTENSIONS_DIR = path.resolve(
    __dirname, '../../android/app/src/main/assets/extensions',
);

function parsedVersion(name) {
    const tail = name.slice(name.lastIndexOf('-') + 1);
    const parts = tail.split('.').map(Number);
    return parts.some(Number.isNaN) ? [] : parts;
}

/**
 * The newest bundled extension directory whose name starts with `prefix`.
 *
 * Throws rather than returning null: every caller is a self-check, and a check
 * that quietly examines nothing reads exactly like a check that passed.
 */
function newestExtensionDir(prefix) {
    const dirs = fs.readdirSync(EXTENSIONS_DIR)
        .filter((d) => d.startsWith(prefix))
        .sort((a, b) => {
            const x = parsedVersion(a);
            const y = parsedVersion(b);
            for (let i = 0; i < Math.max(x.length, y.length); i += 1) {
                const d = (x[i] || 0) - (y[i] || 0);
                if (d !== 0) return d;
            }
            return 0;
        });
    if (!dirs.length) {
        throw new Error(`no ${prefix}* under ${EXTENSIONS_DIR}`);
    }
    return path.join(EXTENSIONS_DIR, dirs[dirs.length - 1]);
}

module.exports = { EXTENSIONS_DIR, newestExtensionDir };
