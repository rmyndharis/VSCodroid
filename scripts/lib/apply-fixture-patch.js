'use strict';

/**
 * Applies a patch from patches/ to fixture files under a scratch directory, as
 * patch(1) would, and refuses one that changed none of the files it was meant to.
 *
 * Run inside a git work tree, `git apply` takes the patch's paths from that
 * tree's top and skips every one outside the current directory, exiting 0. With
 * TMPDIR inside a checkout, every patch in these self-checks was a no-op and
 * each test then failed on the unpatched fixture with a message about something
 * else, or passed over it. The ceiling keeps git from finding a repository above
 * the scratch directory, and the comparison afterwards makes a patch that
 * applied nothing a failure that says so.
 */

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

function contents(file) {
    try {
        return fs.readFileSync(file, 'utf8');
    } catch (error) {
        if (error.code === 'ENOENT') return null;
        throw error;
    }
}

/**
 * Applies `patch` in `directory`, limited to `include` when it is given, and
 * asserts that every path in `changes` (relative to `directory`) differs
 * afterwards. A file the patch creates counts as changed.
 */
function applyFixturePatch(directory, patch, { include = [], changes }) {
    assert.ok(fs.existsSync(patch), `${patch} is gone, so the fixture would be checked unpatched`);
    assert.ok(changes && changes.length, 'name the files the patch has to change');
    const before = changes.map((relative) => contents(path.join(directory, relative)));
    const env = { ...process.env, GIT_CEILING_DIRECTORIES: path.dirname(fs.realpathSync(directory)) };
    for (const name of ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR']) delete env[name];
    execFileSync('git', ['apply', ...include.map((relative) => '--include=' + relative), patch],
        { cwd: directory, env });
    changes.forEach((relative, i) => assert.notStrictEqual(
        contents(path.join(directory, relative)), before[i],
        `${path.basename(patch)} left ${relative} as it was`,
    ));
}

module.exports = { applyFixturePatch };
