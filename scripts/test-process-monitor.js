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

    fs.rmSync(tmp, { recursive: true, force: true });
    console.log(`ok -- ${snapshot.total} processes counted, ${cases.length} classifications checked`);
}

main();
