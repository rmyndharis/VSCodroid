'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const { execFileSync } = require('node:child_process');
const { stripTypeScriptTypes } = require('node:module');
const { EventEmitter } = require('node:events');
const { createHash } = require('node:crypto');

const root = path.join(__dirname, '..');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'server-startup-'));
function loadFixture(fixture, relative, patch, globals, exported, prepareSource = source => source) {
    const destination = path.join(temporary, relative);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.copyFileSync(path.join(__dirname, 'fixtures', fixture), destination);
    execFileSync('git', ['apply', '--include=' + relative, path.join(root, 'patches', patch)], { cwd: temporary });
    const source = prepareSource(fs.readFileSync(destination, 'utf8')).replace(/^export /gm, '');
    const context = { ...globals };
    vm.runInNewContext(stripTypeScriptTypes(source, { mode: 'transform', sourceUrl: relative }) +
        '\nglobalThis.selected = ' + exported + ';', context);
    return context.selected;
}

async function boundedHostWait(promise, label) {
    let timer;
    try {
        return await Promise.race([promise, new Promise((_resolve, reject) => {
            timer = setTimeout(() => reject(new Error('host fixture timed out: ' + label)), 3000);
        })]);
    } finally { clearTimeout(timer); }
}

function prepareHostSource(source, dependencies, identity) {
    identity.patchedSha256 = createHash('sha256').update(source).digest('hex');
    const expected = ['IServerEnvironmentService', 'ILogService', 'IExtensionHostStatusService', 'IConfigurationService'];
    const removed = Object.fromEntries(expected.map(name => [name, 0]));
    // This fixture constructs the class directly, so these service decorators do not inject anything.
    source = source.replace(/^([\t ]*)@(IServerEnvironmentService|ILogService|IExtensionHostStatusService|IConfigurationService)(?=[\t ]+private[\t ]+readonly\b)/gm,
        (_match, indent, name) => { removed[name]++; return indent; });
    assert.deepEqual(Object.values(removed), [1, 1, 1, 1], 'the connection service decorator set changed');
    assert.doesNotMatch(source, /^[\t ]*@\w+/m, 'a new runtime decorator needs an explicit fixture decision');
    const imports = [];
    source = source.replace(/^import (.+) from '([^']+)';$/gm, (_match, clause, name) => {
        assert.ok(Object.hasOwn(dependencies, name), 'unmodeled connection dependency: ' + name);
        imports.push(name);
        if (/^\* as \w+$/.test(clause)) return 'const ' + clause.slice(5) + ' = __hostDependencies[' + JSON.stringify(name) + '];';
        assert.match(clause, /^\{[\w\s,]+\}$/, 'the connection import shape changed');
        return 'const ' + clause + ' = __hostDependencies[' + JSON.stringify(name) + '];';
    });
    assert.equal(imports.length, 21, 'the complete connection import set changed');
    assert.equal(new Set(imports).size, 21);
    assert.doesNotMatch(source, /^import /m, 'an import was not bound to a named dependency double');
    identity.removedDecorators = removed;
    identity.imports = imports;
    return source;
}

function deferred() { let resolve; const promise = new Promise(answer => { resolve = answer; }); return { promise, resolve }; }
class Store {
    constructor() { this.items = []; this.disposed = false; }
    add(item) { if (this.disposed) item.dispose(); else this.items.push(item); return item; }
    dispose() { if (this.disposed) return; this.disposed = true; for (const item of this.items.splice(0)) item.dispose(); }
}
class Disposable { constructor() { this.store = new Store(); } _register(item) { return this.store.add(item); } dispose() { this.store.dispose(); } }
class Emitter {
    constructor() { this.listeners = new Set(); this.event = listener => { this.listeners.add(listener); return { dispose: () => this.listeners.delete(listener) }; }; }
    fire(value) { for (const listener of [...this.listeners]) listener(value); }
    dispose() { this.listeners.clear(); }
}
function hostFixture(mode) {
    const environment = deferred(), environmentEntered = deferred(), listening = deferred();
    const servers = [], workers = [];
    let environmentCalls = 0, constructions = 0, closed = 0;
    class Socket {
        constructor() { this.endEvent = new Emitter(); this.closeEvent = new Emitter(); this.onEnd = this.endEvent.event; this.onClose = this.closeEvent.event; }
        end() {} dispose() {} drain() { return Promise.resolve(); }
    }
    class WebSocket extends Socket { setRecordInflateBytes() {} }
    class Pipe extends EventEmitter {
        constructor() { super(); this.closed = 0; servers.push(this); }
        listen(_name, callback) { this.finishListening = callback; listening.resolve(); if (mode !== 'pipe') queueMicrotask(callback); }
        close() { this.closed++; }
    }
    function forkOrWorker(thread) {
        assert.strictEqual(thread, true);
        constructions++;
        if (mode === 'fork-error') throw new Error('dummy worker construction failure');
        const worker = new EventEmitter();
        worker.pid = 42; worker.killed = 0;
        worker.stdout = new EventEmitter(); worker.stderr = new EventEmitter();
        worker.stdout.setEncoding = worker.stderr.setEncoding = () => {};
        worker.kill = () => { worker.killed++; };
        workers.push(worker);
        return worker;
    }
    const decorator = () => {};
    const dependencies = {
        child_process: { fork() { throw new Error('unexpected process creation'); } },
        net: { createServer: () => new Pipe() },
        '../../base/common/buffer.js': { VSBuffer: { alloc: size => ({ buffer: Buffer.alloc(size), byteLength: size }), wrap: buffer => ({ buffer, byteLength: buffer.length }) } },
        '../../base/common/event.js': { Emitter, Event: { fromNodeEventEmitter: (emitter, event) => listener => { emitter.on(event, listener); return { dispose: () => emitter.off(event, listener) }; } } },
        '../../base/common/lifecycle.js': { Disposable, DisposableStore: Store },
        '../../base/common/network.js': { FileAccess: { asFileUri: () => ({ fsPath: '/dummy/bootstrap-fork.js' }) } },
        '../../base/common/path.js': path,
        '../../base/common/platform.js': { isWindows: false },
        '../../base/node/workerAsChildProcess.js': { forkOrWorker },
        '../../base/common/processes.js': { removeDangerousEnvVariables() {} },
        '../../base/parts/ipc/common/ipc.net.js': { ProtocolConstants: { ReconnectionShortGraceTime: 300000 } },
        '../../base/parts/ipc/node/ipc.net.js': { createRandomIPCHandle: () => '/dummy/socket', NodeSocket: Socket, WebSocketNodeSocket: WebSocket },
        '../../platform/remote/common/remoteAgentConnection.js': {},
        '../../workbench/services/extensions/common/extensionHostProtocol.js': {},
        '../../platform/configuration/common/configuration.js': { IConfigurationService: decorator },
        '../../platform/log/common/log.js': { ILogService: decorator },
        '../../platform/shell/node/shellEnv.js': { getResolvedShellEnv: async () => ({}) },
        './extensionHostStatusService.js': { IExtensionHostStatusService: decorator },
        './remoteLanguagePacks.js': { getNLSConfiguration() { environmentCalls++; environmentEntered.resolve(); return mode === 'environment' || mode === 'disconnect' ? environment.promise : Promise.resolve({}); } },
        './serverEnvironmentService.js': { IServerEnvironmentService: decorator },
        '../../workbench/services/extensions/common/extensionHostEnv.js': { IPCExtHostConnection: class {}, SocketExtHostConnection: class {}, writeExtHostConnection() {} },
    };
    const sourceInfo = { fixtureSha256: createHash('sha256').update(fs.readFileSync(path.join(__dirname, 'fixtures', 'extension-host-connection.ts'))).digest('hex') };
    const Connection = loadFixture('extension-host-connection.ts', 'src/vs/server/node/extensionHostConnection.ts',
        '0037-retain-current-startup-state.patch', {
            __hostDependencies: dependencies, process: { execArgv: [], env: { PATH: '/bin' } },
            Buffer, setTimeout, clearTimeout, console,
        }, 'ExtensionHostConnection', source => prepareHostSource(source, dependencies, sourceInfo));
    const client = new Socket();
    const connection = new Connection('dummy-token', 'dummy-address', client,
        { byteLength: 0 }, { args: {}, appRoot: '/dummy', userDataPath: '/dummy', isBuilt: true, reconnectionGraceTime: 0 },
        { info() {}, error() {}, trace() {} }, { setExitInfo() {} }, { getValue: () => false });
    const closedSignal = deferred();
    connection.onClose(() => { closed++; connection.dispose(); closedSignal.resolve(); });
    return { connection, client, environment, environmentEntered, listening, servers, workers,
        closedSignal, sourceInfo, counts: () => ({ environmentCalls, constructions, closed }) };
}

(async () => {
    let cases = 0;
    const prepare = loadFixture('debug-terminals.ts', 'src/vs/workbench/contrib/debug/node/terminals.ts',
        '0018-debug-terminal-without-usr-bin-env.patch',
        { platform: { isWindows: false }, getDriveLetter: () => undefined }, 'prepareCommand');
    const runShell = script => execFileSync('/bin/bash', ['--noprofile', '--norc', '-c', script],
        { encoding: 'utf8', timeout: 3000 });
    const child = 'printf "child:%s:%s\\n" "${SESSION_FLAG-unset}" "${CHILD_ONLY-unset}"';
    for (const [env, expected] of [
        [{ SESSION_FLAG: null }, 'child:unset:unset'],
        [{ SESSION_FLAG: null, CHILD_ONLY: 'one two' }, 'child:unset:one two'],
        [{ SESSION_FLAG: 'child' }, 'child:child:unset'],
    ]) {
        const command = prepare('/bin/bash', ['/bin/sh', '-c', child], false, undefined, env);
        assert.equal(runShell('export SESSION_FLAG=keep\nunset CHILD_ONLY\n' + command +
            '\nprintf "parent:%s:%s\\n" "$SESSION_FLAG" "${CHILD_ONLY-unset}"\n').trim(),
        expected + '\nparent:keep:unset');
        cases++;
    }
    const comment = prepare('/bin/bash', ['printf "child:%s\\n" "${SESSION_FLAG-unset}" # adapter comment'],
        true, undefined, { SESSION_FLAG: null });
    assert.equal(runShell('export SESSION_FLAG=keep\n' + comment + '\nprintf "parent:%s\\n" "$SESSION_FLAG"\n').trim(),
        'child:unset\nparent:keep');
    cases++;
    for (const env of [{}, { SESSION_FLAG: null }, { SESSION_FLAG: 'child' }]) {
        assert.equal(runShell(prepare('/bin/bash', [], false, undefined, env)), '');
        cases++;
    }
    assert.throws(() => runShell(prepare('/bin/bash', ['/bin/sh', '-c', 'exit 17'], false,
        undefined, { SESSION_FLAG: null })), error => error.status === 17);
    cases++;
    assert.equal(runShell(prepare('/bin/bash', ['printf', 'control'], false)), 'control');
    cases++;
    assert.equal(runShell(prepare('/bin/bash', ['printf', 'control'], false, undefined,
        { 'BAD-NAME': 'value', 'BAD;NAME': 'value' })), 'control');
    cases++;

    const callbacks = new Map();
    const calls = { exits: [], delegated: [] };
    const exited = Symbol('exit');
    const install = loadFixture('server-listener-errors.ts', 'src/server-main.ts',
        '0036-exit-when-the-editor-cannot-bind.patch',
        { console: { error() {} }, process: { exit(code) { calls.exits.push(code); throw exited; } } },
        'installListenerErrors');
    async function checkError(server, error, expectedExit) {
        calls.exits.length = 0;
        calls.delegated.length = 0;
        install(Object.assign(server, { on(event, callback) { callbacks.set(event, callback); } }), async () => ({
            handleServerError(value) { calls.delegated.push(value); },
        }));
        try { await callbacks.get('error')(error); }
        catch (failure) { if (failure !== exited) throw failure; }
        assert.deepEqual(calls.exits, expectedExit ? [1] : []);
        assert.deepEqual(calls.delegated, expectedExit ? [] : [error]);
        cases++;
    }
    await checkError({ listening: false }, Object.assign(new Error('dummy'), { code: 'EOTHER' }), false);
    await checkError({ listening: true }, Object.assign(new Error('dummy'), { code: 'EADDRINUSE' }), false);
    const holder = http.createServer();
    const listener = http.createServer();
    try {
        await new Promise(resolve => holder.listen(0, '127.0.0.1', resolve));
        const error = await new Promise(resolve => {
            listener.once('error', resolve);
            listener.listen(holder.address().port, '127.0.0.1');
        });
        assert.equal(error.code, 'EADDRINUSE');
        assert.equal(listener.listening, false);
        await checkError(listener, error, true);
    } finally {
        listener.close();
        await new Promise(resolve => holder.close(resolve));
    }
    const hostReports = [], hostFailures = [], hostSources = new Set();
    let hostSource;
    for (const mode of ['environment', 'pipe', 'disconnect', 'fork-error', 'normal', 'already-disposed']) {
        const f = hostFixture(mode);
        hostSource = f.sourceInfo;
        hostSources.add(hostSource.patchedSha256);
        try {
            if (mode === 'already-disposed') f.connection.dispose();
            const starting = f.connection.start({ language: 'en', env: {} });
            if (mode === 'environment' || mode === 'disconnect') {
                await boundedHostWait(f.environmentEntered.promise, mode + ' environment entry');
                if (mode === 'disconnect') { f.client.closeEvent.fire(); await boundedHostWait(f.closedSignal.promise, mode + ' disconnect disposal'); }
                else f.connection.dispose();
                f.environment.resolve({});
            } else if (mode === 'pipe') {
                await boundedHostWait(f.listening.promise, mode + ' pipe listen');
                f.connection.dispose();
                f.servers[0].finishListening();
            }
            await boundedHostWait(starting, mode + ' startup completion');
            if (['environment', 'pipe', 'disconnect', 'already-disposed'].includes(mode)) assert.equal(f.counts().constructions, 0, 'a disposed connection constructed a late worker');
            if (mode === 'already-disposed') assert.equal(f.counts().environmentCalls, 0);
            if (mode === 'fork-error') {
                assert.equal(f.counts().constructions, 1);
                assert.equal(f.servers[0].closed, 1, 'a failed worker left its pipe unowned');
            }
            if (mode === 'normal') {
                assert.equal(f.counts().environmentCalls, 1);
                assert.equal(f.counts().constructions, 1);
                assert.equal(f.servers.length, 1);
                assert.equal(f.workers.length, 1);
                assert.equal(f.servers[0].closed, 0);
                assert.equal(f.workers[0].killed, 0);
                assert.equal(f.counts().closed, 0);
            }
            f.connection.dispose(); f.connection.dispose();
            assert.ok(f.servers.every(server => server.closed === 1), 'a late pipe survived disposal');
            assert.ok(f.workers.every(worker => worker.killed === 1), 'an owned worker survived or was killed twice');
            assert.equal(f.counts().closed, 1);
            hostReports.push({ mode, passed: true });
        } catch (error) {
            hostReports.push({ mode, passed: false, message: error.message });
            hostFailures.push(error);
        } finally {
            f.environment.resolve({}); f.connection.dispose();
            f.servers.forEach(server => server.finishListening?.());
            cases++;
        }
    }
    assert.equal(hostSources.size, 1, 'host cases ran different connection implementations');
    console.log(JSON.stringify({ hostSource, hostReports, scope: 'complete connection class with in-memory transport and worker dependencies' }));
    if (hostFailures.length) throw new AggregateError(hostFailures, 'extension-host startup controls failed');

    console.log(JSON.stringify({ cases, result: 'passed', scope: 'patched source fixtures, shell, listener and in-memory host lifecycle controls' }));
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => {
    fs.rmSync(temporary, { recursive: true, force: true });
});
