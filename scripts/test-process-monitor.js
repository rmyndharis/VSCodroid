/**
 * Self-check for the process monitor's classification and its phantom count.
 *
 * Runs anywhere Node runs. The monitor reads /proc, which does not exist on a
 * macOS workstation and holds an uncontrolled process list on a CI runner, so
 * start() takes the directory to scan and this points it at a fixture.
 *
 * The assertions are made at the far end of the wire -- on the snapshot JSON
 * the status bar extension reads -- rather than on classify() directly. Both a
 * misclassification and a process the scan never reached present as a wrong
 * label in that file, and only one of those is a classify() defect.
 *
 *   node scripts/test-process-monitor.js
 */

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const monitor = require('../android/app/src/main/assets/process-monitor.js');

const UID = process.getuid();

/**
 * One fake /proc entry.
 *
 * The stat layout matters: readCpuTime() slices past the closing paren of comm
 * and then indexes 11 and 12 for utime and stime, so the filler between ppid
 * and those two has to be exactly nine fields wide.
 */
function writeProc(root, pid, argv, { uid = UID, ppid = 1 } = {}) {
    const dir = path.join(root, String(pid));
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(
        path.join(dir, 'status'),
        `Name:\tnode\nPPid:\t${ppid}\nUid:\t${uid}\t${uid}\t${uid}\t${uid}\n`,
    );
    fs.writeFileSync(path.join(dir, 'cmdline'), argv.join('\0') + '\0');
    fs.writeFileSync(
        path.join(dir, 'stat'),
        `${pid} (node) S ${ppid} ${'0 '.repeat(9)}7 3\n`,
    );
}

const NODE = '/data/data/com.vscodroid/lib/arm64/libnode.so';
const REH = '/data/user/0/com.vscodroid/files/server/vscode-reh';
// Marketplace and bundled-by-us extensions are extracted here, which is a
// different tree from the editor's own extensions under REH.
const EXT = '/data/user/0/com.vscodroid/files/home/.vscodroid/extensions';

const MAIN_ACTIVITY = path.join(
    __dirname, '../android/app/src/main/kotlin/com/vscodroid/MainActivity.kt',
);
const ENVIRONMENT = path.join(
    __dirname, '../android/app/src/main/kotlin/com/vscodroid/util/Environment.kt',
);

/**
 * The severity contract, read from both sides rather than restated here.
 *
 * Kotlin decides the word and writes it to a file; this monitor reads that file
 * and acts on the word. Neither side could see the other. Every Kotlin test
 * compares against the PRESSURE_CRITICAL *constant*, so editing its value to any
 * other word keeps them all green while KILL_ON_PRESSURE below stops matching --
 * and the idle kill then never fires under real memory pressure, silently. The
 * same hole runs the other way: widening this set, or renaming the file on
 * either side, was equally unobserved.
 *
 * So the words are lifted out of the Kotlin source and compared with the ones
 * this module actually uses. Reading the source is the weak half -- a pattern
 * that stops matching would find nothing and agree with everything -- so each
 * one asserts it matched before anything is concluded from it.
 */
function checkPressureContract(tmp) {
    const kotlin = fs.readFileSync(MAIN_ACTIVITY, 'utf8');

    const literal = (name, pattern) => {
        const m = kotlin.match(pattern);
        assert.ok(
            m,
            `${pattern} matched nothing in MainActivity.kt, so this check is comparing ` +
            `the monitor against nothing. Find what ${name} is called now and fix the pattern.`,
        );
        return m[1];
    };

    const critical = literal('PRESSURE_CRITICAL',
        /internal const val PRESSURE_CRITICAL\s*=\s*"([^"]+)"/);
    const moderate = literal('PRESSURE_MODERATE',
        /internal const val PRESSURE_MODERATE\s*=\s*"([^"]+)"/);
    const filename = literal('the pressure file',
        /File\(tmpDir,\s*"([^"]+)"\)\.writeText\(pressure\)/);

    assert.deepStrictEqual(
        [...monitor.KILL_ON_PRESSURE].sort(), [critical],
        `the monitor kills on ${JSON.stringify([...monitor.KILL_ON_PRESSURE])} but Kotlin ` +
        `writes ${JSON.stringify(critical)} for pressure worth shedding work over. One side ` +
        `was renamed without the other, and the idle kill fires on nothing.`,
    );

    assert.ok(
        !monitor.KILL_ON_PRESSURE.has(moderate),
        `${JSON.stringify(moderate)} is the level Kotlin reports without asking for anything ` +
        `to be killed; acting on it sheds language servers the device has room for.`,
    );

    assert.strictEqual(
        monitor.PRESSURE_FILENAME, filename,
        'Kotlin writes the severity to a different filename than the monitor opens, so the ' +
        'signal is delivered to a path nothing reads.',
    );

    // The filename is only half the path. The directory is a third literal, in a
    // third place: MainActivity hands applyMemoryPressure a File built from
    // cacheDir, while the monitor opens $TMPDIR, which Environment builds from
    // cacheDir separately. The monitor follows Environment by construction -- it
    // reads the variable Environment sets -- so the pair that can silently drift
    // is these two Kotlin expressions, and nothing compared them either.
    const environment = fs.readFileSync(ENVIRONMENT, 'utf8');

    // "Every writer" is a claim about the whole tree, and the scan below reads one
    // file. Checked rather than assumed, in both directions: a caller added in
    // another source would be outside the comparison while the summary line still
    // says the contract agrees, and a renamed function would leave the scan
    // matching nothing and agreeing with everything.
    //
    // The lookbehind is what keeps the second case from passing: searching for a
    // function name finds its own declaration, so `fun applyMemoryPressure(` at
    // the bottom of MainActivity.kt would satisfy a plain search whether or not
    // anything still calls it.
    const callers = [];
    const walk = (dir) => {
        for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, e.name);
            if (e.isDirectory()) walk(full);
            else if (e.name.endsWith('.kt') &&
                     /(?<!fun\s)applyMemoryPressure\s*\(/.test(fs.readFileSync(full, 'utf8'))) {
                callers.push(full);
            }
        }
    };
    walk(path.join(__dirname, '../android/app/src/main/kotlin'));
    assert.deepStrictEqual(
        callers, [MAIN_ACTIVITY],
        'the set of sources that write the pressure file is not what this check reads. ' +
        `Found [${callers.join(', ')}], expected only MainActivity.kt. Another writer's ` +
        'severity words would be outside the comparison; none at all means the function ' +
        'was renamed and this check is comparing the monitor against nothing.',
    );
    // All of them, not the first: a second writer aiming somewhere else is exactly
    // the drift this compares for, and match() without /g would never look at it.
    const writerDirs = [...kotlin.matchAll(/applyMemoryPressure\(File\(cacheDir,\s*"([^"]+)"\)/g)]
        .map((m) => m[1]);
    const writerDir = writerDirs.length ? [writerDirs[0]] : null;
    const envDir = environment.match(/val tmpDir = "\$cacheDir\/([^"]+)"/);
    assert.ok(
        writerDir,
        'could not find the directory MainActivity writes the pressure file into, so the ' +
        'comparison below would be against nothing',
    );
    assert.ok(
        envDir,
        'could not find the directory Environment exports as TMPDIR, so the comparison below ' +
        'would be against nothing',
    );
    assert.deepStrictEqual(
        [...new Set(writerDirs)], writerDirs.slice(0, 1),
        'MainActivity writes the pressure file into more than one directory (' +
        writerDirs.join(', ') + '), so at most one of them can be the one the monitor opens',
    );
    assert.strictEqual(
        writerDir[0], envDir[1],
        `MainActivity writes the pressure file into cacheDir/${writerDir[0]} while TMPDIR -- ` +
        `which is the directory the monitor opens -- is cacheDir/${envDir[1]}. The severity is ` +
        'written where nothing looks for it, and the filenames agreeing hides it.',
    );

    // The constant above is only worth comparing if it is the one start() built
    // pressurePath from, and the read is only one-shot if the file is gone
    // afterwards -- without the unlink a single critical event would make every
    // later scan kill idle servers forever.
    const file = path.join(tmp, monitor.PRESSURE_FILENAME);
    fs.writeFileSync(file, `${critical}\n`);
    assert.strictEqual(
        monitor.readMemoryPressure(), critical,
        `the monitor did not read ${critical} back from ${monitor.PRESSURE_FILENAME}; the path ` +
        'start() opens is not the one the exported name describes',
    );
    assert.ok(!fs.existsSync(file), 'the pressure signal was not consumed, so it will fire again');

    return { critical, moderate, filename };
}

function main() {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'vscodroid-monitor-'));
    const proc = path.join(tmp, 'proc');
    process.env.TMPDIR = tmp;

    // The monitor runs inside this very process, so its own pid has to be one
    // of the fixture entries for the count to mean anything.
    writeProc(proc, process.pid, [NODE, '--max-old-space-size=512', '/data/user/0/com.vscodroid/files/server/server.js', '--host=127.0.0.1']);

    const cases = [
        // pid, argv, the type the snapshot must carry
        [1001, [NODE, `${REH}/out/server-main.js`, '--host', '127.0.0.1'], 'server'],
        [1002, [NODE, `${REH}/extensions/css-language-features/server/dist/node/cssServerMain.js`, '--node-ipc'], 'langserver'],
        [1003, [NODE, `${REH}/extensions/json-language-features/server/dist/node/jsonServerMain.js`, '--node-ipc'], 'langserver'],
        [1004, ['/data/data/com.vscodroid/lib/arm64/libbash.so', '-l'], 'terminal'],
        [1005, [NODE, '/data/user/0/com.vscodroid/files/home/projects/my-eslint-tool/index.js'], 'unknown'],
        [1006, [NODE, '/data/user/0/com.vscodroid/files/usr/share/reporter/index.js'], 'unknown'],
        [1007, [NODE, `${REH}/out/bootstrap-fork.js`, '--type=fileWatcher'], 'fileWatcher'],
        [1008, [NODE, '/data/user/0/com.vscodroid/files/saf-mirrors/a1b2c3/sync.js'], 'safSync'],
        // A marketplace language server, not one of the three bundled with the
        // editor, and the reason it is here: check-langserver-patterns.py globs
        // *ServerMain.js under vscode-reh/extensions and cannot see this file at
        // all. The pattern for it matched the extension's directory name until
        // classification moved to argument basenames, and nothing noticed.
        [1009, [NODE, `${EXT}/bradlc.vscode-tailwindcss-0.16.0/dist/tailwindServer.js`,
            '--node-ipc', '--clientProcessId=1'], 'langserver'],
        [1010, [NODE, `${EXT}/bradlc.vscode-tailwindcss-0.16.0/dist/tailwindModeServer.js`,
            '--node-ipc'], 'langserver'],
        // The other half of that pattern: a bare 'tailwind' would match all three of
        // these, and 'langserver' is not a label -- it makes a process eligible for
        // the idle kill under memory pressure. These are the user's own work.
        [1011, [NODE, '/data/user/0/com.vscodroid/files/home/projects/site/tailwind.config.js'],
            'unknown'],
        [1012, [NODE, '/data/user/0/com.vscodroid/files/home/projects/build-tailwind.js'],
            'unknown'],
    ];
    for (const [pid, argv] of cases) {
        writeProc(proc, pid, argv);
    }

    // The main Android process is not a phantom and must not reach the tree.
    writeProc(proc, 1100, ['com.vscodroid.debug']);
    // Another app's process, same device, different uid.
    writeProc(proc, 1200, [NODE, `${REH}/out/server-main.js`], { uid: UID + 1 });

    monitor.start(1001, { procRoot: proc });
    monitor.stop();

    const snapshot = JSON.parse(fs.readFileSync(path.join(tmp, 'vscodroid-processes.json'), 'utf8'));
    const byPid = new Map(snapshot.tree.map((entry) => [entry.pid, entry]));

    for (const [pid, argv, want] of cases) {
        const got = byPid.get(pid);
        assert.ok(got, `pid ${pid} (${argv.join(' ')}) never reached the snapshot`);
        assert.strictEqual(got.type, want, `pid ${pid} (${argv.join(' ')}) was labelled ${got.type}`);
    }

    assert.ok(!byPid.has(1100), 'the main Android process was counted as a phantom');
    assert.ok(!byPid.has(1200), "another uid's process was counted against our budget");

    // Android's phantom accounting is per-UID and counts the process this code
    // runs inside, which ProcessBuilder launched like any other. Skipping it
    // reported one fewer than the limit is measured against.
    assert.ok(byPid.has(process.pid), 'the bootstrap process the monitor runs in was not counted');

    assert.strictEqual(snapshot.total, snapshot.tree.length, 'total disagrees with the tree it summarises');
    assert.strictEqual(snapshot.budget.current, snapshot.total, 'budget.current disagrees with total');
    assert.strictEqual(snapshot.total, cases.length + 1, 'the count moved');

    const contract = checkPressureContract(tmp);

    fs.rmSync(tmp, { recursive: true, force: true });
    console.log(
        `ok -- ${snapshot.total} processes counted, ${cases.length} classifications checked, ` +
        `pressure contract agrees on ${JSON.stringify(contract.critical)} in ${contract.filename}`,
    );
}

main();
