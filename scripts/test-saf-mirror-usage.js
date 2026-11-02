'use strict';

const fs = require('node:fs');
const vm = require('node:vm');
const os = require('node:os');
const hostPath = require('node:path');
const path = hostPath.posix;
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { stripTypeScriptTypes } = require('node:module');

// Added to node:module in 22.13 and 23.2. On anything older the destructured name
// is undefined and the first call below dies with "stripTypeScriptTypes is not a
// function", which says nothing about the Node that is missing it. scripts/setup.sh
// accepts Node 20, so a contributor on a version it allows reaches this. CI runs 24.
if (typeof stripTypeScriptTypes !== 'function') {
    console.error(
        `${hostPath.basename(__filename)}: needs node:module.stripTypeScriptTypes, `
        + `which Node 22.13+ or 23.2+ has (this is ${process.version}). CI runs Node 24.`,
    );
    process.exit(1);
}

const root = hostPath.join(__dirname, '..');
const temporary = fs.mkdtempSync(hostPath.join(os.tmpdir(), 'saf-mirror-usage-'));
let helperSource;
let configurationSource;
let diskSource;
const helperPath = 'src/vs/workbench/browser/safMirrorUsage.ts';
const configurationPath = 'src/vs/workbench/services/configuration/browser/configurationService.ts';
const diskPath = 'src/vs/platform/files/common/diskFileSystemProviderClient.ts';
try {
    for (const [fixture, target] of [
        ['saf-mirror-configuration.ts', configurationPath],
        ['saf-mirror-disk-client.ts', diskPath],
    ]) {
        const destination = hostPath.join(temporary, target);
        fs.mkdirSync(hostPath.dirname(destination), { recursive: true });
        fs.copyFileSync(hostPath.join(__dirname, 'fixtures', fixture), destination);
    }
    execFileSync('git', ['apply', '--include=' + helperPath, '--include=' + configurationPath,
        '--include=' + diskPath, hostPath.join(root, 'patches', '0035-claim-device-folder-copies-before-use.patch')], { cwd: temporary });
    execFileSync('git', ['apply', '--include=' + helperPath, '--include=' + configurationPath,
        hostPath.join(root, 'patches', '0038-publish-workspace-resource-roots.patch')], { cwd: temporary });
    execFileSync('git', ['apply', '--include=' + configurationPath,
        hostPath.join(root, 'patches', '0042-tolerate-non-list-workspace-folders.patch')], { cwd: temporary });
    helperSource = fs.readFileSync(hostPath.join(temporary, helperPath), 'utf8');
    configurationSource = fs.readFileSync(hostPath.join(temporary, configurationPath), 'utf8');
    diskSource = fs.readFileSync(hostPath.join(temporary, diskPath), 'utf8');
} finally {
    fs.rmSync(temporary, { recursive: true, force: true });
}
const workspaceSource = fs.readFileSync(hostPath.join(__dirname, 'fixtures/saf-mirror-workspace-folders.ts'), 'utf8');

class Uri {
    constructor(scheme, authority, uriPath) { this.scheme = scheme; this.authority = authority; this.path = uriPath; }
    static isUri(value) { return value instanceof Uri; }
    static parse(value) {
        const parsed = new URL(value);
        return new Uri(parsed.protocol.slice(0, -1), parsed.host, decodeURIComponent(parsed.pathname));
    }
    with(change) { return new Uri(change.scheme || this.scheme, change.authority || this.authority, change.path || this.path); }
    toString() { return `${this.scheme}://${this.authority}${this.path}`; }
}
class WorkspaceFolder {
    constructor(data, raw) { Object.assign(this, data); this.raw = raw; }
}
const extUri = {
    dirname: uri => uri.with({ path: path.dirname(uri.path) }),
    resolvePath: (uri, relative) => uri.with({ path: path.resolve(uri.path, relative) }),
    getComparisonKey: uri => uri.toString(),
    basenameOrAuthority: uri => path.basename(uri.path) || uri.authority,
};
function transpile(source, filename) {
    return stripTypeScriptTypes(source.replace(/^import .*;\n/gm, '').replace(/^export /gm, ''),
        { mode: 'transform', sourceUrl: filename });
}
const workspaceContext = { URI: Uri, WorkspaceFolder, posix: path, console };
vm.runInNewContext(transpile(workspaceSource, 'workspace-folders.ts') + '\nglobalThis.toWorkspaceFolders = toWorkspaceFolders;', workspaceContext);
const toWorkspaceFolders = workspaceContext.toWorkspaceFolders;
function browserFixture(nativeAnswer = true) {
    const events = [];
    const publications = [];
    const listeners = new Set();
    const timers = new Map();
    let nextTimer = 0;
    const browser = {
        __vscodroid: { authToken: 'session', pageGeneration: 7 },
        AndroidBridge: { claimWorkspacePaths(token, json) {
            assert.equal(token, 'session');
            events.push({ type: 'claim', paths: JSON.parse(json) });
            return nativeAnswer;
        }, publishWorkspaceRoots(token, generation, json) {
            assert.equal(token, 'session');
            assert.equal(generation, 7);
            publications.push(JSON.parse(json));
            return true;
        } },
        addEventListener(name, callback) { assert.equal(name, 'vscodroid-bridge-ready'); listeners.add(callback); },
        removeEventListener(name, callback) { listeners.delete(callback); },
    };
    const module = { exports: {} };
    const lifecycle = { toDisposable: callback => { let done = false; return { dispose() { if (!done) { done = true; callback(); } } }; } };
    vm.runInNewContext(transpile(helperSource, 'safMirrorUsage.ts') + '\nexports.claimSafMirrorPaths = claimSafMirrorPaths; exports.protectSafMirrorChannel = protectSafMirrorChannel;', {
        module, exports: module.exports, mainWindow: browser, URI: Uri, toDisposable: lifecycle.toDisposable,
        Schemas: { file: 'file', vscodeRemote: 'vscode-remote' }, localize: (_key, message) => message, Error, console,
        setTimeout(callback, delay) { assert.equal(delay, 5000); const id = ++nextTimer; timers.set(id, callback); return id; },
        clearTimeout(id) { timers.delete(id); },
    });
    const channel = {
        async call(command, args) { events.push({ type: 'call', command, args }); return command; },
        listen(event, args) { return callback => {
            events.push({ type: 'listen', event, args });
            return { dispose() { events.push({ type: 'dispose', event }); } };
        }; },
    };
    return { browser, events, publications, helper: module.exports, channel, listeners, timers, lifecycle,
        expire() { [...timers.values()].forEach(callback => callback()); },
        ready() { browser.__vscodroid = { authToken: 'session', pageGeneration: 7 }; [...listeners].forEach(callback => callback()); } };
}
function serviceFixture(helper, workspace, publish, warnings = []) {
    class Workspace {
        constructor(id, folders, transient, configuration) {
            this.id = id;
            this.folders = folders;
            this.transient = transient;
            this.configuration = configuration;
            this.initialized = false;
        }
    }
    const context = { claimSafMirrorPaths: helper.claimSafMirrorPaths, mark() {},
        toWorkspaceFolders, Workspace,
        ConfigurationTarget: { WORKSPACE_FOLDER: 1, WORKSPACE: 2 } };
    vm.runInNewContext(transpile(configurationSource, 'configurationService.ts') + '\nglobalThis.SelectedWorkspaceService = WorkspaceService;', context);
    return Object.assign(new context.SelectedWorkspaceService(), {
        initialized: false,
        initialWorkspaceSettings: undefined,
        logService: { warn: (message, error) => warnings.push(error ?? message) },
        workspace: { folders: [], configuration: undefined },
        _configuration: { toData: () => ({}), compareAndUpdateWorkspaceConfiguration: () => ({}) },
        _onDidChangeWorkspaceFolders: { fire: () => publish('member-event') },
        async createWorkspace() { return workspace; },
        async updateWorkspaceAndInitializeConfiguration(value) { this.workspace = value; publish('initial-publication'); },
        checkAndMarkWorkspaceComplete() {},
        compareFolders(before, after) { return { added: after, removed: before, changed: [] }; },
        async onFoldersChanged() { publish('configuration-load'); return {}; },
        async handleWillChangeWorkspaceFolders() { publish('member-will-event'); },
        triggerConfigurationChange() {},
        updateRestrictedSettings() {},
    });
}
function diskFixture(fixture) {
    const errors = [];
    const context = { generateUuid: () => 'request', toDisposable: fixture.lifecycle.toDisposable,
        toErrorMessage: error => error.message };
    vm.runInNewContext(transpile(diskSource, 'diskFileSystemProviderClient.ts') + '\nglobalThis.SelectedDiskClient = DiskFileSystemProviderClient;', context);
    const client = Object.assign(new context.SelectedDiskClient(), {
        sessionId: 'session', channel: fixture.helper.protectSafMirrorChannel(fixture.channel),
        _onDidWatchError: { fire: error => errors.push(error) },
    });
    return { client, errors };
}
const filesRoot = '/data/user/0/com.vscodroid/files';
const memberB = `${filesRoot}/saf-mirrors/bbbbbbbbbbbb`;
const remote = uriPath => new Uri('vscode-remote', 'localhost', uriPath);
const settle = () => new Promise(resolve => setImmediate(resolve));
(async () => {
    let cases = 0;
    for (const configPath of [`${filesRoot}/projects/team.code-workspace`, `${filesRoot}/saf-mirrors/aaaaaaaaaaaa/team.code-workspace`]) {
        const relative = path.relative(path.dirname(configPath), memberB);
        const folders = toWorkspaceFolders([{ path: relative }, { uri: remote(memberB).toString() }], remote(configPath), extUri);
        assert.equal(folders.length, 1);
        assert.equal(folders[0].uri.path, memberB);
        const fixture = browserFixture();
        delete fixture.browser.__vscodroid.authToken;
        const service = serviceFixture(fixture.helper, { folders, configuration: remote(configPath) },
            type => fixture.events.push({ type }));
        const initializing = service.initialize({});
        await settle();
        assert.deepEqual(fixture.events, [], 'initial publication ran before native acknowledgement');
        fixture.ready();
        await initializing;
        assert.equal(fixture.events[0].type, 'claim');
        assert(fixture.events[0].paths.includes(memberB));
        assert.equal(fixture.events[1].type, 'initial-publication');
        cases++;
    }
    {
        const fixture = browserFixture(false);
        const warnings = [];
        const service = serviceFixture(fixture.helper, {}, type => fixture.events.push({ type }), warnings);
        const folders = toWorkspaceFolders([{ path: memberB }], remote(`${filesRoot}/projects/team.code-workspace`), extUri);
        await service.updateWorkspaceConfiguration(folders, {}, false);
        assert.equal(service.workspace.folders, folders, 'updateWorkspaceConfiguration left folders unset when a copy was gone');
        assert.deepEqual(fixture.publications, [], 'the roots of a refused copy were published during folder update');
        assert.equal(warnings.length, 2);
        warnings.forEach(warning => assert.match(warning.message, /local copy/i));
        cases++;
    }
    {
        const fixture = browserFixture(false);
        const guarded = fixture.helper.protectSafMirrorChannel(fixture.channel);
        for (const command of ['stat', 'realpath', 'readdir', 'readFile', 'writeFile', 'open', 'rename', 'copy', 'mkdir', 'delete', 'cloneFile', 'watch']) {
            await assert.rejects(guarded.call(command, [remote(`${memberB}/note.txt`), remote(`${memberB}/other.txt`)]), /local copy/i);
        }
        assert(fixture.events.every(event => event.type === 'claim'), 'a rejected access reached the provider channel');
        cases++;
    }
    {
        const fixture = browserFixture();
        const guarded = fixture.helper.protectSafMirrorChannel(fixture.channel);
        await guarded.call('open', [remote(`${memberB}/note.txt`), { create: true }]);
        await guarded.call('write', [4, 0, new Uint8Array([1]), 0, 1]);
        assert.deepEqual(fixture.events.map(event => event.type), ['claim', 'call', 'call']);
        assert.deepEqual(fixture.publications, [], 'filesystem access published resource roots');
        cases++;
    }
    {
        const fixture = browserFixture();
        delete fixture.browser.__vscodroid.authToken;
        const guarded = fixture.helper.protectSafMirrorChannel(fixture.channel);
        const subscription = guarded.listen('readFileStream', [remote(`${memberB}/note.txt`)])(() => {});
        await settle();
        assert.deepEqual(fixture.events, [], 'a stream subscribed before native acknowledgement');
        subscription.dispose();
        fixture.ready();
        await settle();
        assert(fixture.events.every(event => event.type !== 'listen'), 'a canceled stream started later');
        cases++;
    }
    {
        const fixture = browserFixture(false);
        const guarded = fixture.helper.protectSafMirrorChannel(fixture.channel);
        let error;
        const subscription = guarded.listen('readFileStream', [remote(`${memberB}/note.txt`)])(value => { error = value; });
        await settle();
        assert.match(error.message, /local copy/i);
        assert(fixture.events.every(event => event.type !== 'listen'));
        subscription.dispose();
        cases++;
    }
    {
        const fixture = browserFixture();
        delete fixture.browser.AndroidBridge;
        await fixture.helper.protectSafMirrorChannel(fixture.channel).call('stat', [remote('/ordinary/project')]);
        assert.deepEqual(fixture.events.map(event => event.type), ['call']);
        cases++;
    }
    {
        const fixture = browserFixture();
        delete fixture.browser.__vscodroid.authToken;
        const subscription = fixture.helper.protectSafMirrorChannel(fixture.channel).listen('fileChange', ['session'])(() => {});
        assert.equal(fixture.events[0].type, 'listen');
        subscription.dispose();
        assert.equal(fixture.events[1].type, 'dispose');
        cases++;
    }
    for (const where of ['lookup', 'subscribe']) {
        const fixture = browserFixture();
        const failure = new Error(`stream ${where} failed`);
        fixture.channel.listen = () => {
            if (where === 'lookup') throw failure;
            return () => { throw failure; };
        };
        const unhandled = [];
        const reportUnhandled = error => unhandled.push(error);
        process.on('unhandledRejection', reportUnhandled);
        let received;
        try {
            const subscription = fixture.helper.protectSafMirrorChannel(fixture.channel)
                .listen('readFileStream', [remote(`${memberB}/note.txt`)])(error => { received = error; });
            await settle();
            await settle();
            assert.equal(received, failure, 'the stream subscriber did not receive its setup failure');
            assert.deepEqual(unhandled, [], 'stream setup escaped as an unhandled promise rejection');
            subscription.dispose();
        } finally {
            process.off('unhandledRejection', reportUnhandled);
        }
        cases++;
    }
    {
        const fixture = browserFixture();
        let callback;
        let disposals = 0;
        fixture.channel.listen = () => listener => {
            callback = listener;
            return { dispose() { disposals++; } };
        };
        const seen = [];
        const subscription = fixture.helper.protectSafMirrorChannel(fixture.channel)
            .listen('readFileStream', [remote(`${memberB}/note.txt`)])(event => seen.push(event));
        await settle();
        assert.equal(typeof callback, 'function');
        callback('first bytes');
        callback('end');
        assert.deepEqual(seen, ['first bytes', 'end']);
        subscription.dispose();
        subscription.dispose();
        assert.equal(disposals, 1, 'an active stream subscription was not retired exactly once');
        cases++;
    }
    {
        const fixture = browserFixture(false);
        let received = 0;
        const subscription = fixture.helper.protectSafMirrorChannel(fixture.channel)
            .listen('readFileStream', [remote(`${memberB}/note.txt`)])(() => { received++; });
        subscription.dispose();
        await settle();
        assert.equal(received, 0, 'a canceled stream received a later claim error');
        assert(fixture.events.every(event => event.type !== 'listen'));
        cases++;
    }
    {
        const fixture = browserFixture();
        delete fixture.browser.__vscodroid.authToken;
        const { client, errors } = diskFixture(fixture);
        const subscription = client.watch(remote(memberB), {});
        subscription.dispose();
        await settle();
        assert.deepEqual(fixture.events, [], 'watch or unwatch dispatched before acknowledgement');
        fixture.ready();
        await settle();
        await settle();
        assert.deepEqual(fixture.events.map(event => event.type === 'call' ? event.command : event.type),
            ['claim', 'watch', 'unwatch'], 'disposed watch was left registered after delayed admission');
        assert.deepEqual(errors, []);
        cases++;
    }
    {
        const fixture = browserFixture(false);
        const { client, errors } = diskFixture(fixture);
        const subscription = client.watch(remote(memberB), {});
        subscription.dispose();
        await settle();
        assert.equal(errors.length, 1);
        assert.match(errors[0], /local copy/i);
        assert(fixture.events.every(event => event.type === 'claim'), 'denied watch reached the provider');
        cases++;
    }
    {
        const fixture = browserFixture();
        const failure = new Error('unwatch failed');
        fixture.channel.call = async command => {
            if (command === 'unwatch') throw failure;
        };
        const { client, errors } = diskFixture(fixture);
        const subscription = client.watch(remote(memberB), {});
        await settle();
        subscription.dispose();
        await settle();
        assert.deepEqual(errors, [failure.message]);
        cases++;
    }
    {
        const fixture = browserFixture();
        delete fixture.browser.__vscodroid.authToken;
        const folders = toWorkspaceFolders([{ path: memberB }], remote(filesRoot + '/projects/team.code-workspace'), extUri);
        const service = serviceFixture(fixture.helper, {}, type => fixture.events.push({ type }));
        const previous = service.workspace.folders;
        const updating = service.updateWorkspaceConfiguration(folders, {}, false);
        await settle();
        assert.equal(service.workspace.folders, previous);
        assert.deepEqual(fixture.events, []);
        fixture.ready();
        await updating;
        assert.equal(fixture.events[0].type, 'claim');
        assert.equal(service.workspace.folders, folders);
        assert.deepEqual(fixture.events.slice(1).map(event => event.type),
            ['claim', 'configuration-load', 'member-will-event', 'member-event']);
        assert.deepEqual(fixture.publications, [[memberB]]);
        cases++;
    }
    {
        // A copy the native side refuses, one that is gone for instance, must not leave the workspace
        // unset: the page would stay blank. Each filesystem call is refused on its own.
        const fixture = browserFixture(false);
        const warnings = [];
        const service = serviceFixture(fixture.helper, { folders: [{ uri: remote(memberB) }] },
            type => fixture.events.push({ type }), warnings);
        const previous = service.workspace;
        await service.initialize({});
        assert.notEqual(service.workspace, previous, 'a refused claim left the workspace unset');
        assert.deepEqual(fixture.events.map(event => event.type), ['claim', 'initial-publication', 'claim']);
        assert.deepEqual(fixture.publications, [], 'the roots of a refused copy were published');
        assert.equal(warnings.length, 2);
        warnings.forEach(warning => assert.match(warning.message, /local copy/i));
        await assert.rejects(fixture.helper.protectSafMirrorChannel(fixture.channel).call('stat', [remote(memberB)]), /local copy/i);
        cases++;
    }
    {
        const fixture = browserFixture();
        delete fixture.browser.__vscodroid.authToken;
        const failure = new Error('canceled');
        const token = { isCancellationRequested: false };
        fixture.channel.call = async (_command, _args, receivedToken) => {
            assert.equal(receivedToken, token, 'the original cancellation token was replaced');
            if (receivedToken.isCancellationRequested) throw failure;
        };
        const pending = fixture.helper.protectSafMirrorChannel(fixture.channel).call('readFile', [remote(memberB)], token);
        const rejection = assert.rejects(pending, error => error === failure);
        token.isCancellationRequested = true;
        fixture.ready();
        await rejection;
        cases++;
    }
    {
        const fixture = browserFixture();
        delete fixture.browser.__vscodroid.authToken;
        const pending = fixture.helper.protectSafMirrorChannel(fixture.channel).call('stat', [remote(memberB)]);
        const rejection = assert.rejects(pending, /local copy/i);
        assert.equal(fixture.listeners.size, 1);
        fixture.expire();
        await rejection;
        assert.equal(fixture.listeners.size, 0);
        assert.equal(fixture.timers.size, 0);
        fixture.ready();
        await settle();
        assert.deepEqual(fixture.events, [], 'timeout did not retire the deferred access');
        cases++;
    }
    {
        const fixture = browserFixture();
        await fixture.helper.claimSafMirrorPaths([remote(memberB), remote(memberB), new Uri('file', '', memberB),
            new Uri('untitled', '', '/note')]);
        assert.deepEqual(fixture.events[0].paths, [memberB]);
        cases++;
    }
    {
        const fixture = browserFixture();
        delete fixture.browser.AndroidBridge.claimWorkspacePaths;
        await assert.rejects(fixture.helper.protectSafMirrorChannel(fixture.channel).call('stat', [remote(memberB)]), /local copy/i);
        assert.deepEqual(fixture.events, []);
        cases++;
    }
    {
        const fixture = browserFixture();
        await fixture.helper.claimSafMirrorPaths([remote('/a'), remote('/config/work.code-workspace')], true);
        await fixture.helper.claimSafMirrorPaths([remote('/b')], true);
        await fixture.helper.claimSafMirrorPaths([], true);
        assert.deepEqual(fixture.publications, [['/a', '/config/work.code-workspace'], ['/b'], []]);
        cases++;
    }
    {
        const fixture = browserFixture();
        fixture.browser.__vscodroid = {};
        const pending = fixture.helper.claimSafMirrorPaths([remote('/a')], true);
        assert.deepEqual(fixture.publications, []);
        fixture.ready();
        await pending;
        assert.deepEqual(fixture.publications, [['/a']]);
        cases++;
    }
    {
        const fixture = browserFixture();
        const workspace = { folders: [{ uri: remote('/member') }], configuration: remote('/config/work.code-workspace') };
        const service = serviceFixture(fixture.helper, workspace, () => assert.deepEqual(fixture.publications, []));
        service.checkAndMarkWorkspaceComplete = () => assert.deepEqual(fixture.publications,
            [['/member', '/config/work.code-workspace']]);
        await service.initialize({});
        cases++;
    }
    {
        const fixture = browserFixture();
        const service = serviceFixture(fixture.helper, {}, () => assert.deepEqual(fixture.publications,
            [['/new', '/config/work.code-workspace']]));
        service.workspace.configuration = remote('/config/work.code-workspace');
        await service.updateWorkspaceConfiguration([{ uri: remote('/new') }], {}, false);
        cases++;
    }
    {
        const fixture = browserFixture();
        const service = serviceFixture(fixture.helper, { folders: [{ uri: remote('/initial') }] }, () => {});
        service.updateWorkspaceAndInitializeConfiguration = async workspace => {
            service.workspace = { ...workspace };
            await service.updateWorkspaceConfiguration([{ uri: remote('/current') }], {}, false);
        };
        await service.initialize({});
        assert.deepEqual(fixture.publications, [['/current'], ['/current']],
            'finishing initialization restored resource roots replaced by a folder update');
        cases++;
    }
    {
        const configPath = remote(`${filesRoot}/projects/team.code-workspace`);
        const parseWorkspaceRaw = text => {
            try {
                const raw = text ? JSON.parse(text) : {};
                return (raw && typeof raw === 'object' && !Array.isArray(raw) ? raw.folders : undefined) || [];
            } catch {
                if (/^\s*\{\s*"folders"\s*:\s*\{\s*$/.test(text)) return {};
                return [];
            }
        };
        async function runMultiFolderScenario({ initialText, cachedText, fileText, liveEdits = [], onClaim, onPublish }) {
            const fixture = browserFixture();
            const warnings = [];
            let claimCount = 0;
            let publishCount = 0;
            if (onClaim) {
                fixture.browser.AndroidBridge.claimWorkspacePaths = (token, json) => {
                    assert.equal(token, 'session');
                    claimCount++;
                    const paths = JSON.parse(json);
                    fixture.events.push({ type: 'claim', paths });
                    return onClaim(claimCount, paths);
                };
            }
            if (onPublish) {
                fixture.browser.AndroidBridge.publishWorkspaceRoots = (token, generation, json) => {
                    assert.equal(token, 'session');
                    assert.equal(generation, 7);
                    publishCount++;
                    const paths = JSON.parse(json);
                    const allowed = onPublish(publishCount, paths);
                    if (allowed) fixture.publications.push(paths);
                    return allowed;
                };
            }
            const unhandled = [];
            const onUnhandled = error => unhandled.push(error);
            process.on('unhandledRejection', onUnhandled);
            try {
                let currentFolders = [];
                let configInitialized = false;
                let barrierOpen = false;
                const folderEvents = [];
                const service = serviceFixture(fixture.helper, null, type => {
                    if (type === 'member-event') folderEvents.push(Array.from(service.workspace.folders, f => f.uri.path));
                }, warnings);
                delete service.createWorkspace;
                service.workspace = undefined;
                service.isWorkspaceTrusted = true;
                service.initialWorkspaceSettings = initialText;
                service.uriIdentityService = { extUri: { ...extUri, ignorePathCasing: () => false } };
                service.compareFolders = (before, after) => {
                    const beforePaths = before.map(f => f.uri.path);
                    const afterPaths = after.map(f => f.uri.path);
                    return {
                        added: after.filter(f => !beforePaths.includes(f.uri.path)),
                        removed: before.filter(f => !afterPaths.includes(f.uri.path)),
                        changed: [],
                    };
                };
                service.toValidWorkspaceFolders = async folders => folders;
                service.checkAndMarkWorkspaceComplete = () => {
                    if (!barrierOpen && service.workspace && service.workspace.initialized) {
                        barrierOpen = true;
                    }
                };
                service.createWorkspace = arg => service.createMultiFolderWorkspace(arg);
                let deferredProviderReload;
                service.workspaceConfiguration = {
                    get initialized() { return configInitialized; },
                    isTransient: () => false,
                    getFolders: () => currentFolders,
                    getConfiguration: () => ({}),
                    async initialize(_id, _trusted, initialContents) {
                        const firstText = initialContents !== undefined ? (initialContents ?? '') : (cachedText ?? '');
                        currentFolders = parseWorkspaceRaw(firstText);
                        deferredProviderReload = async () => {
                            currentFolders = parseWorkspaceRaw(fileText);
                            configInitialized = true;
                            service.onWorkspaceConfigurationChanged(true).then(() => {
                                service.workspace.initialized = service.workspaceConfiguration.initialized;
                                service.checkAndMarkWorkspaceComplete(true);
                            });
                        };
                    },
                };
                await service.initialize({ id: 'w1', configPath });
                await deferredProviderReload();
                await settle();
                await settle();
                const snapshots = [service.workspace ? Array.from(service.workspace.folders, f => f.uri.path) : null];
                for (const editText of liveEdits) {
                    currentFolders = parseWorkspaceRaw(editText);
                    service.onWorkspaceConfigurationChanged(false).then(() => {
                        service.workspace.initialized = service.workspaceConfiguration.initialized;
                        service.checkAndMarkWorkspaceComplete(false);
                    });
                    await settle();
                    await settle();
                    snapshots.push(Array.from(service.workspace.folders, f => f.uri.path));
                }
                return {
                    workspaceDefined: service.workspace !== undefined,
                    folders: service.workspace ? Array.from(service.workspace.folders, f => f.uri.path) : null,
                    barrierOpen,
                    unhandled,
                    folderEvents,
                    snapshots,
                    warnings,
                    publications: fixture.publications,
                };
            } finally {
                process.off('unhandledRejection', onUnhandled);
            }
        }
        const validTwo = JSON.stringify({ folders: [{ path: '/a' }, { path: '/b' }] });
        const validOne = JSON.stringify({ folders: [{ path: '/c' }] });
        const control = await runMultiFolderScenario({ initialText: validTwo, fileText: validTwo });
        assert.equal(control.workspaceDefined, true);
        assert.equal(control.barrierOpen, true);
        assert.deepEqual(control.folders, ['/a', '/b']);
        assert.deepEqual(control.unhandled, []);
        cases++;

        for (const badText of [
            JSON.stringify({ folders: { path: '/a' } }),
            '{"folders": 5}',
            '{"folders": true}',
            '{"folders": {',
        ]) {
            const firstLoad = await runMultiFolderScenario({ initialText: badText, fileText: badText });
            assert.equal(firstLoad.workspaceDefined, true, `workspace undefined for ${badText}`);
            assert.equal(firstLoad.barrierOpen, true, `barrier stayed closed for ${badText}`);
            assert.deepEqual(firstLoad.folders, []);
            assert.deepEqual(firstLoad.unhandled, []);
            cases++;
        }

        const cachedObject = JSON.stringify({ folders: { path: '/a' } });
        const cacheOnly = await runMultiFolderScenario({
            initialText: undefined,
            cachedText: cachedObject,
            fileText: cachedObject,
        });
        assert.equal(cacheOnly.workspaceDefined, true);
        assert.equal(cacheOnly.barrierOpen, true);
        assert.deepEqual(cacheOnly.folders, []);
        assert.deepEqual(cacheOnly.unhandled, []);
        cases++;

        const repairedAfterPoisonedCache = await runMultiFolderScenario({
            initialText: undefined,
            cachedText: cachedObject,
            fileText: validTwo,
        });
        assert.equal(repairedAfterPoisonedCache.workspaceDefined, true);
        assert.equal(repairedAfterPoisonedCache.barrierOpen, true);
        assert.deepEqual(repairedAfterPoisonedCache.folders, ['/a', '/b']);
        assert.deepEqual(repairedAfterPoisonedCache.unhandled, []);
        cases++;

        {
            const fixture = browserFixture();
            const service = serviceFixture(fixture.helper, null, () => {});
            const rawlessFolder = new WorkspaceFolder({ uri: remote('/active'), name: 'active', index: 0 });
            service.workspace = { folders: [rawlessFolder], configuration: configPath, initialized: true };
            service.workspaceConfiguration = { getFolders: () => ({}), getConfiguration: () => ({}) };
            service.uriIdentityService = { extUri };
            service.toValidWorkspaceFolders = async folders => folders;
            service.compareFolders = (before, after) => ({
                added: after.filter(folder => !before.includes(folder)),
                removed: before.filter(folder => !after.includes(folder)), changed: [],
            });
            await service.onWorkspaceConfigurationChanged(false);
            assert.deepEqual(service.workspace.folders, [rawlessFolder]);
            cases++;
        }

        const runningWindow = await runMultiFolderScenario({
            initialText: validTwo,
            fileText: validTwo,
            liveEdits: [
                cachedObject,
                '{"folders": "src"}',
                '{"folders": 5}',
                '{"folders": true}',
                '{"folders": {',
                validOne,
            ],
        });
        assert.equal(runningWindow.workspaceDefined, true);
        assert.equal(runningWindow.barrierOpen, true);
        assert.deepEqual(runningWindow.unhandled, []);
        assert.deepEqual(runningWindow.snapshots, [
            ['/a', '/b'],
            ['/a', '/b'],
            ['/a', '/b'],
            ['/a', '/b'],
            ['/a', '/b'],
            ['/a', '/b'],
            ['/c'],
        ]);
        assert.deepEqual(runningWindow.folderEvents, [['/c']]);
        cases++;

        const savedWithMissingCopy = JSON.stringify({ folders: [{ path: '/a' }, { path: '/b' }] });
        const refusedHeadInUpdate = await runMultiFolderScenario({
            initialText: undefined,
            cachedText: JSON.stringify({ folders: [{ path: '/a' }] }),
            fileText: savedWithMissingCopy,
            onClaim: (_count, paths) => !paths.includes('/b'),
            onPublish: (_count, _paths) => true,
        });
        assert.equal(refusedHeadInUpdate.workspaceDefined, true);
        assert.equal(refusedHeadInUpdate.barrierOpen, true, 'complete-workspace barrier stayed closed when update head claim was refused');
        assert.deepEqual(refusedHeadInUpdate.folders, ['/a', '/b']);
        assert.deepEqual(refusedHeadInUpdate.unhandled, []);
        assert.deepEqual(refusedHeadInUpdate.publications, [['/a', configPath.path]]);
        cases++;
        assert.equal(refusedHeadInUpdate.warnings.length, 2);
        refusedHeadInUpdate.warnings.forEach(warning => assert.match(warning.message, /local copy/i));
        cases++;

        const refusedPublishInUpdate = await runMultiFolderScenario({
            initialText: undefined,
            cachedText: JSON.stringify({ folders: [{ path: '/a' }] }),
            fileText: savedWithMissingCopy,
            onClaim: () => true,
            onPublish: (_count, paths) => !paths.includes('/b'),
        });
        assert.equal(refusedPublishInUpdate.workspaceDefined, true);
        assert.equal(refusedPublishInUpdate.barrierOpen, true, 'complete-workspace barrier stayed closed when update publishing claim was refused');
        assert.deepEqual(refusedPublishInUpdate.folders, ['/a', '/b']);
        assert.deepEqual(refusedPublishInUpdate.unhandled, []);
        assert.deepEqual(refusedPublishInUpdate.publications, [['/a', configPath.path]]);
        assert.equal(refusedPublishInUpdate.warnings.length, 1);
        assert.match(refusedPublishInUpdate.warnings[0].message, /local copy/i);
        cases++;
    }
    console.log(JSON.stringify({ cases, result: 'passed', scope: 'selected actual methods and functions with bounded dependency stubs' }));
})().catch(error => { console.error(error); process.exitCode = 1; });
