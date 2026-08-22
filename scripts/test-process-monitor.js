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
// The interpreter FirstRunSetup writes into settings.json as
// python.defaultInterpreterPath, which is what ms-python launches its server
// with. Not every language server is a Node one, and the two rules differ there.
const PY = '/data/user/0/com.vscodroid/files/usr/bin/python3';
const REH = '/data/user/0/com.vscodroid/files/server/vscode-reh';
// Marketplace and bundled-by-us extensions are extracted here, which is a
// different tree from the editor's own extensions under REH.
const EXT = '/data/user/0/com.vscodroid/files/home/.vscodroid/extensions';
// The ceiling ProcessManager derives from device RAM and puts on the command
// line. It is on the real processes and it belongs on the fixtures, because it
// is the argument that used to be the only thing the details view printed.
const HEAP = '--max-old-space-size=488';
const DATA = '/data/user/0/com.vscodroid/files/home/.vscodroid/data';

const MAIN_ACTIVITY = path.join(
    __dirname, '../android/app/src/main/kotlin/com/vscodroid/MainActivity.kt',
);
// The second writer. The Activity reports pressure while it exists; the
// application class reports it for the whole process, which is the only one
// left once the task is swiped away and the server keeps running.
const VSCODROID_APP = path.join(
    __dirname, '../android/app/src/main/kotlin/com/vscodroid/VSCodroidApp.kt',
);
const ENVIRONMENT = path.join(
    __dirname, '../android/app/src/main/kotlin/com/vscodroid/util/Environment.kt',
);

// The status bar extension, loaded for its label table alone.
//
// It requires 'vscode', which exists only inside the workbench, so the name is
// resolved to a stub the way scripts/test-process-monitor-extension.js does.
// Nothing here activates it; only the exported table is read, and that is the
// point of taking the module rather than reading its source. A regex over the
// file would agree with anything once it stopped matching.
const Module = require('module');
const { newestExtensionDir } = require('./lib/bundled-extension');
const resolveFilename = Module._resolveFilename;
Module._resolveFilename = function (request, ...rest) {
    return request === 'vscode' ? 'vscode' : resolveFilename.call(this, request, ...rest);
};
require.cache.vscode = { id: 'vscode', filename: 'vscode', loaded: true, exports: {} };
const { TYPE_LABELS } = require(path.join(
    newestExtensionDir('vscodroid.vscodroid-process-monitor-'), 'extension.js',
));

/**
 * Every type the monitor really emitted has a word a person can read.
 *
 * The two vocabularies are declared in different files with no compiler and,
 * until this, nothing else between them. classify() gained 'bootstrap' so the
 * monitor would count the process it runs inside, the extension's table was not
 * told, and the tooltip rendered the internal word beside human ones: '1
 * bootstrap, 3 system'. The extension now falls back to the word 'unknown'
 * maps to rather than to the type itself, so a future gap is no longer visible
 * to a user, and this is what makes it visible to whoever opens the gap.
 *
 * Asked of the snapshot rather than of classify()'s source, so it covers the
 * type scan() assigns without going through the classifier at all, which is
 * exactly the one that was missing. What it cannot see is a classification no
 * fixture above produces; the count below is printed so a shrinking one is
 * readable from a run.
 */
function checkLabelCoverage(snapshot) {
    assert.ok(
        Object.keys(TYPE_LABELS).length > 0,
        'the extension exported no label table, so this check would agree with anything',
    );
    const types = [...new Set(snapshot.tree.map((entry) => entry.type))].sort();
    assert.ok(types.length > 1, `only ${types.length} type(s) reached the snapshot`);

    const unlabelled = types.filter((type) => !(type in TYPE_LABELS));
    assert.deepStrictEqual(
        unlabelled, [],
        'the monitor emits these types and the status bar extension has no word for ' +
        `them, so the tooltip would offer the reader an internal identifier: ${unlabelled.join(', ')}`,
    );
    return types;
}

/**
 * Every row says which program it is.
 *
 * The details view and the counter's tooltip are the only places a person can
 * find out what is holding a slot, and both print this string. It used to be
 * argv[0] plus argv[1], which on this device is the bundled Node binary plus the
 * heap ceiling for nearly every process the app owns: on an idle API 33 emulator
 * five of six rows read `libnode.so --max-old-space-size=488` and the view named
 * nothing.
 *
 * The pairs matter as much as the strings. `bootstrap-fork` is launched twice
 * with different `--type=`, so a rule that keeps only the program name leaves two
 * rows sharing one identity, and a rule that keeps only the first argument leaves
 * every row sharing the heap flag. Both failures are asserted for, and the
 * expectations are compared as a set so that a pid dropping out of the snapshot
 * cannot quietly empty the check.
 */
function checkCommandNames(snapshot, byPid) {
    const expected = new Map([
        [process.pid, 'libnode.so server.js'],
        [1001, 'libnode.so server-main.js'],
        [1007, 'libnode.so bootstrap-fork --type=fileWatcher'],
        [1029, 'libnode.so bootstrap-fork --type=agentHost'],
        [1030, 'libnode.so copilot-android-arm64/index.js'],
        [1004, 'libbash.so'],
    ]);
    for (const [pid, want] of expected) {
        const got = byPid.get(pid);
        assert.ok(got, `pid ${pid} never reached the snapshot, so its name was never compared`);
        assert.strictEqual(
            got.cmd, want,
            `pid ${pid} is printed as ${JSON.stringify(got.cmd)}; a reader cannot tell it ` +
            `from the other rows built on the same runtime`,
        );
    }

    const names = snapshot.tree.map((entry) => entry.cmd);
    assert.ok(
        !names.includes(`libnode.so ${HEAP}`),
        'a row is named after the runtime and its heap ceiling, which every process here ' +
        'shares, so the details view says nothing about it',
    );
    return expected.size;
}

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
    // Sorted on both sides, because the order is whatever readdir returned.
    assert.deepStrictEqual(
        callers.slice().sort(), [MAIN_ACTIVITY, VSCODROID_APP].sort(),
        'the set of sources that write the pressure file is not what this check reads. ' +
        `Found [${callers.join(', ')}], expected MainActivity.kt and VSCodroidApp.kt. ` +
        "Another writer's severity words would be outside the comparison; none at all " +
        'means the function was renamed and this check is comparing the monitor against ' +
        'nothing.',
    );
    // All of them, not the first, and asserted per caller rather than over the
    // pooled result: a writer aiming somewhere else is exactly the drift this
    // compares for, and neither match() without /g nor a scan of one file would
    // ever look at it.
    //
    // Per caller is what keeps the check counting its own subjects. Once there
    // are two writers, a pooled "we found a directory" test is satisfied by
    // either one of them, so a caller whose base expression moves off cacheDir
    // (to filesDir, say, which is the durable directory) contributes nothing,
    // the pool stays non-empty on the other writer's behalf, and every
    // comparison below agrees about a literal that writer no longer uses. The
    // component that drifted is then the one writing where nothing reads, and
    // this file says ok.
    const writerDirs = callers.flatMap((f) => {
        const dirs = [...fs.readFileSync(f, 'utf8')
            .matchAll(/applyMemoryPressure\(File\(cacheDir,\s*"([^"]+)"\)/g)]
            .map((m) => m[1]);
        assert.ok(
            dirs.length,
            `${path.basename(f)} calls applyMemoryPressure but not as ` +
            'applyMemoryPressure(File(cacheDir, "...")), so the directory that caller ' +
            'writes the pressure file into is outside every comparison below',
        );
        return dirs;
    });
    const writerDir = writerDirs.length ? [writerDirs[0]] : null;
    const envDir = environment.match(/val tmpDir = "\$cacheDir\/([^"]+)"/);
    assert.ok(
        writerDir,
        'could not find the directory the pressure file is written into, so the ' +
        'comparison below would be against nothing',
    );
    assert.ok(
        envDir,
        'could not find the directory Environment exports as TMPDIR, so the comparison below ' +
        'would be against nothing',
    );
    assert.deepStrictEqual(
        [...new Set(writerDirs)], writerDirs.slice(0, 1),
        'the pressure file is written into more than one directory (' +
        writerDirs.join(', ') + '), so at most one of them can be the one the monitor opens',
    );
    assert.strictEqual(
        writerDir[0], envDir[1],
        `the pressure file is written into cacheDir/${writerDir[0]} while TMPDIR -- ` +
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
        [1007, [NODE, HEAP, `${REH}/out/bootstrap-fork`, '--type=fileWatcher'], 'fileWatcher'],
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
        // The same specificity question for the patterns that are single words.
        // These four are one rule read in both directions, and either half alone
        // passes on the wrong one: a rule that only has to classify gopls is
        // satisfied by matching every basename containing it, and a rule that
        // only has to spare run-eslint.js is satisfied by matching nothing.
        [1013, [NODE, '/data/user/0/com.vscodroid/files/home/projects/site/run-eslint.js'],
            'unknown'],
        // The pattern reaches this one through a flag's value, not through the
        // script being run: every argument is tested, and the basename of
        // --out=/tmp/tsserver-log.js is tsserver-log.js.
        [1014, [NODE, '/data/user/0/com.vscodroid/files/home/projects/site/build.js',
            '--out=/tmp/tsserver-log.js'], 'unknown'],
        // A server whose program name is the pattern with nothing around it, and
        // one that is the pattern plus the extension its entry point carries.
        [1015, ['/data/user/0/com.vscodroid/files/usr/bin/gopls', '-mode=stdio'], 'langserver'],
        [1016, [NODE, `${REH}/extensions/node_modules/typescript/lib/tsserver.js`,
            '--useInferredProjectPerProjectRoot'], 'langserver'],
        // The three servers whose program name carries a bare pattern on the
        // front rather than being one. They are here because the bare-word rule
        // above cannot reach them and nothing else would say so: a server that
        // stops being classified keeps running, keeps its slot against the
        // 32-process budget, and reads exactly like no server running.
        //
        // The first two ship in this APK. The ESLint extension's client forks
        // server/out/eslintServer.js, and ms-python runs
        // python_files/run-jedi-language-server.py through the interpreter --
        // that one on every stock install, because settings.json is written with
        // python.languageServer set to Jedi.
        [1017, [NODE, `${EXT}/dbaeumer.vscode-eslint-3.0.34/server/out/eslintServer.js`,
            '--node-ipc', '--clientProcessId=1'], 'langserver'],
        [1018, [PY, `${EXT}/ms-python.python-2026.4.0/python_files/run-jedi-language-server.py`],
            'langserver'],
        // Not bundled, and the reason the entry for it carries hyphens: pyright
        // arrives with an extension the user installs.
        [1019, [NODE, '/data/user/0/com.vscodroid/files/home/projects/node_modules/pyright/dist/pyright-langserver.js',
            '--stdio'], 'langserver'],
        // The other direction for two of those entries, so that neither is
        // satisfied by a rule that matches everything: a user's own script whose
        // name merely contains the server's, one of them under the very
        // interpreter that runs the real one.
        [1020, [PY, '/data/user/0/com.vscodroid/files/home/projects/tools/jedi-scraper.py'],
            'unknown'],
        [1021, [NODE, '/data/user/0/com.vscodroid/files/home/projects/site/eslintServer.config.js'],
            'unknown'],
        // The editor's own servers as they actually launch, which is the spelling
        // that was missing. Every one of these clients passes an extensionless
        // module path to `fork`, and Node puts it in argv[1] verbatim, so the
        // basename carries no `.js`. The fixtures above at 1002 and 1003 named
        // the file on disk instead, which is why they passed for years while the
        // real processes were classified 'unknown'. Both spellings are covered
        // now, since a client is free to change which it passes.
        [1022, [NODE, `${REH}/extensions/css-language-features/server/dist/node/cssServerMain`,
            '--node-ipc'], 'langserver'],
        [1023, [NODE, `${REH}/extensions/html-language-features/server/dist/node/htmlServerMain`,
            '--node-ipc'], 'langserver'],
        [1024, [NODE, `${REH}/extensions/json-language-features/server/dist/node/jsonServerMain`,
            '--node-ipc'], 'langserver'],
        // Markdown's, which no pattern reached at all before and which the gate
        // could not even see: it globbed *ServerMain.js and this is
        // serverWorkerMain.js. Its client builds `./dist/serverWorkerMain` and
        // forks it over IPC, so it is a real process holding a slot.
        [1025, [NODE, `${REH}/extensions/markdown-language-features/dist/serverWorkerMain`,
            '--node-ipc'], 'langserver'],
        // And the other direction, so none of the four is satisfied by a rule
        // that matches too much. A bare word has to be the whole basename, so a
        // user's own file merely starting with one of these names stays theirs.
        [1026, [NODE, '/data/user/0/com.vscodroid/files/home/projects/site/cssServerMain.helper.js'],
            'unknown'],
        [1027, [NODE, '/data/user/0/com.vscodroid/files/home/projects/serverWorkerMain-shim.js'],
            'unknown'],
        // 'vscode-eslint' used to be a pattern, and a hyphen put it on the
        // substring arm where the only names it could still reach were the
        // user's. It is gone, and this is what its absence has to keep true.
        [1028, [NODE, '/data/user/0/com.vscodroid/files/home/projects/vscode-eslint-shim.js'],
            'unknown'],
        // The two processes a signed-out, untouched editor leaves running on this
        // device beyond its own core, measured on an API 33 and an API 37
        // emulator: the agent host, and the model backend it forks. The backend
        // is the one that was invisible. Its basename is `index.js`, so no
        // pattern above can name it, and as 'unknown' it was outside both the
        // idle kill and the command that sheds language servers by hand.
        [1029, [NODE, HEAP, `${REH}/out/bootstrap-fork`, '--type=agentHost',
            '--logsPath', `${DATA}/logs`], 'system'],
        [1030, [NODE, `${REH}/node_modules/@github/copilot-android-arm64/index.js`,
            '--headless', '--no-auto-update', '--stdio', '--no-auto-login'], 'langserver'],
        // The other direction for that needle. It carries the node_modules and
        // scope segments precisely so a name merely containing 'copilot' stays
        // the user's, and being classified 'langserver' is what makes a process
        // eligible to be killed.
        [1031, [NODE, '/data/user/0/com.vscodroid/files/home/projects/copilot-demo/index.js'],
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
    const labelled = checkLabelCoverage(snapshot);
    const named = checkCommandNames(snapshot, byPid);

    fs.rmSync(tmp, { recursive: true, force: true });
    console.log(
        `ok -- ${snapshot.total} processes counted, ${cases.length} classifications checked, ` +
        `${labelled.length} types all labelled by the status bar extension, ` +
        `${named} rows named after the program they run, ` +
        `pressure contract agrees on ${JSON.stringify(contract.critical)} in ${contract.filename}`,
    );
}

main();
