'use strict';

// Execute the registered Run File command, including its save and toolchain gates.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { execFileSync } = require('node:child_process');
const { newestExtensionDir } = require('./lib/bundled-extension');

const extension = path.join(newestExtensionDir('vscodroid.vscodroid-saf-bridge-'), 'extension.js');
const source = fs.readFileSync(extension, 'utf8');
const home = '/data/user/0/com.vscodroid/files/home';

async function run(code, { language = 'javascript', file = '/work/a.js', dirty = false,
    saved = true, schedule, read, choose, save, installed = '[{"name":"java"},{"name":"ruby"},{"name":"cpp"}]', readError } = {}) {
    const commands = new Map();
    const events = [];
    class Uri {
        constructor(value) { this.path = value; }
        static joinPath(base, ...parts) { return new Uri(path.posix.join(base.path, ...parts)); }
        toString() { return `file://${this.path}`; }
    }
    const document = {
        uri: new Uri(file), languageId: language, isUntitled: false, isDirty: dirty,
        async save() { events.push('save'); if (save) return save(this); this.isDirty = !saved; return saved; },
    };
    const disposable = () => ({ dispose() {} });
    const vscode = {
        Uri,
        commands: { registerCommand(id, callback) { commands.set(id, callback); return disposable(); },
            async executeCommand(id) { events.push(['command', id]); } },
        workspace: {
            fs: { async readFile(uri) {
                events.push(['read', uri.path]);
                if (read) return read(uri);
                if (readError) throw readError;
                return Buffer.from(installed);
            } },
            onDidCloseTextDocument: disposable, onDidChangeWorkspaceFolders: disposable,
            getWorkspaceFolder: () => undefined, workspaceFolders: [], textDocuments: [document],
        },
        window: {
            activeTextEditor: { document }, terminals: [], createStatusBarItem: disposable,
            showInformationMessage: (message) => { events.push(['info', message]); return choose?.(message); },
            showErrorMessage: (message) => { events.push(['error', message]); },
            createTerminal(options) {
                events.push(['terminal', options.cwd]);
                const terminal = { name: options.name, disposed: false, show() {},
                    sendText(line) { events.push(['line', line]); },
                    dispose() { this.disposed = true; vscode.window.terminals = vscode.window.terminals.filter(item => item !== this); } };
                vscode.window.terminals.push(terminal);
                return terminal;
            },
        },
    };
    const sandbox = { require: () => vscode, module: { exports: {} }, TextDecoder };
    vm.runInNewContext(code, sandbox, { filename: extension });
    sandbox.module.exports.activate({
        extensionUri: new Uri(home + '/.vscodroid/extensions/vscodroid.saf-bridge-1.0.0'),
        subscriptions: [], globalState: { get: (_, fallback) => fallback },
    });
    if (schedule) await schedule({ commands, document, events, vscode, Uri, extension: sandbox.module.exports });
    else await commands.get('vscodroid.runFile')();
    return events;
}

async function checks(code) {
    for (const file of ['/work/a space.js', "/work/a'quote.js", '/work/a;printf INJECTED;.js', '/work/a$(printf INJECTED).js']) {
        const events = await run(code, { file, dirty: true });
        const sent = events.find((event) => event[0] === 'line');
        assert.ok(sent, 'a saved file was not sent to the terminal');
        const line = sent[1];
        assert.equal(execFileSync('/bin/sh', ['-c', 'node() { printf "%s" "$1"; }; ' + line], { encoding: 'utf8' }), file);
        assert.ok(events.indexOf('save') < events.findIndex((event) => event[0] === 'terminal'));
    }
    // A control character in a path reaches the terminal as a keystroke: Ctrl-U and Ctrl-C act
    // on the line being typed, so the rest of the name would run as a command of its own, and
    // quoting cannot stop that. Such a name is refused, and nothing is saved or started.
    for (const name of ['/work/a\u0015touch x #.js', '/work/a\u0003b.js', '/work/a\nb.js', '/work/a\u007fb.js']) {
        const events = await run(code, { file: name, dirty: true });
        assert.ok(events.some((event) => event[0] === 'error'), 'a name with a control character was not refused: ' + JSON.stringify(name));
        assert.ok(!events.some((event) => event[0] === 'terminal' || event[0] === 'line' || event === 'save'), 'a name with a control character reached the terminal: ' + JSON.stringify(name));
    }
    const refused = await run(code, { dirty: true, saved: false });
    assert.ok(refused.some((event) => event[0] === 'error'));
    assert.ok(!refused.some((event) => event[0] === 'terminal'));
    const java = await run(code, { language: 'java', file: '/work/Hello.java' });
    assert.equal(java.find((event) => event[0] === 'read')[1], home + '/.vscodroid/toolchains.json');
    assert.ok(java.some((event) => event[0] === 'line'));
    const missing = await run(code, { language: 'ruby', installed: '[]' });
    assert.ok(!missing.some((event) => event[0] === 'terminal'));
    const unknown = await run(code, { language: 'ruby', readError: { code: 'Unavailable' } });
    assert.ok(unknown.some((event) => event[0] === 'line'));
    const absent = await run(code, { language: 'ruby', readError: { code: 'FileNotFound' } });
    assert.ok(!absent.some((event) => event[0] === 'terminal'));
}

function deferred() {
    let resolve;
    const promise = new Promise(answer => { resolve = answer; });
    return { promise, resolve };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
function another(document, Uri, name, languageId = 'javascript') {
    return { ...document, uri: new Uri('/work/' + name), languageId,
        async save() { this.isDirty = false; return true; } };
}
async function orderingChecks(code) {
    const lines = events => events.filter(event => event[0] === 'line').map(event => event[1]);
    {
        const gate = deferred();
        await run(code, { save: async document => { await gate.promise; document.isDirty = false; return true; },
            schedule: async ({ commands, document, events, vscode, Uri }) => {
                const old = commands.get('vscodroid.runFile')();
                vscode.window.activeTextEditor = { document: another(document, Uri, 'b.js') };
                await commands.get('vscodroid.runFile')();
                const terminal = vscode.window.terminals[0];
                gate.resolve(); await old;
                assert.equal(terminal.disposed, false, 'a late save replaced the newer terminal');
                assert.deepEqual(lines(events), ["node '/work/b.js'"]);
            } });
    }
    {
        const gate = deferred();
        await run(code, { language: 'ruby', read: () => gate.promise,
            schedule: async ({ commands, document, events, vscode, Uri }) => {
                const old = commands.get('vscodroid.runFile')();
                vscode.window.activeTextEditor = { document: another(document, Uri, 'b.js') };
                await commands.get('vscodroid.runFile')();
                const terminal = vscode.window.terminals[0];
                gate.resolve(Buffer.from('[{"name":"ruby"}]')); await old;
                assert.equal(terminal.disposed, false, 'a late toolchain read replaced the newer terminal');
                assert.deepEqual(lines(events), ["node '/work/b.js'"]);
            } });
    }
    {
        const gate = deferred();
        await run(code, { language: 'ruby', installed: '[]', choose: () => gate.promise,
            schedule: async ({ commands, document, events, vscode, Uri }) => {
                const old = commands.get('vscodroid.runFile')();
                await tick();
                vscode.window.activeTextEditor = { document: another(document, Uri, 'b.js') };
                await commands.get('vscodroid.runFile')();
                gate.resolve('Manage Toolchains'); await old;
                assert.deepEqual(lines(events), ["node '/work/b.js'"]);
                assert.equal(events.some(event => event[0] === 'command'), false, 'an old prompt opened toolchain settings');
            } });
    }
    await run(code, { schedule: async ({ commands, document, events, vscode, Uri }) => {
        await commands.get('vscodroid.runFile')();
        const terminal = vscode.window.terminals[0];
        vscode.window.activeTextEditor = { document: another(document, Uri, 'b.js') };
        await commands.get('vscodroid.runFile')();
        assert.equal(terminal.disposed, true);
        assert.deepEqual(lines(events), ["node '/work/a.js'", "node '/work/b.js'"]);
    } });
    for (const invalid of ['unsupported', 'untitled']) {
        const gate = deferred();
        await run(code, { save: async document => { await gate.promise; document.isDirty = false; return true; },
            schedule: async ({ commands, document, events, vscode, Uri }) => {
                const old = commands.get('vscodroid.runFile')();
                const other = another(document, Uri, 'other.js', invalid === 'unsupported' ? 'plaintext' : 'javascript');
                other.isUntitled = invalid === 'untitled';
                vscode.window.activeTextEditor = { document: other };
                await commands.get('vscodroid.runFile')();
                gate.resolve(); await old;
                assert.deepEqual(lines(events), ["node '/work/a.js'"], invalid + ' request canceled an accepted run');
            } });
    }
    {
        const gate = deferred();
        await run(code, { save: async document => { await gate.promise; document.isDirty = false; return true; },
            schedule: async ({ commands, events, extension }) => {
                const old = commands.get('vscodroid.runFile')();
                extension.deactivate(); gate.resolve(); await old;
                assert.deepEqual(lines(events), []);
            } });
    }
    {
        const gate = deferred();
        await run(code, { installed: '[]', save: async document => { await gate.promise; document.isDirty = false; return true; },
            schedule: async ({ commands, document, events, vscode, Uri }) => {
                const old = commands.get('vscodroid.runFile')();
                vscode.window.activeTextEditor = { document: another(document, Uri, 'b.rb', 'ruby') };
                await commands.get('vscodroid.runFile')();
                gate.resolve(); await old;
                assert.deepEqual(lines(events), [], 'an older run resumed after a newer accepted request');
            } });
    }
    await run(code, { schedule: async ({ commands, document, events, vscode, Uri }) => {
        const selected = another(document, Uri, 'selected.js');
        vscode.workspace.textDocuments.push(selected);
        await commands.get('vscodroid.runFile')(selected.uri);
        assert.deepEqual(lines(events), ["node '/work/selected.js'"]);
    } });
}

(async () => {
    await checks(source);
    await orderingChecks(source);
    for (const [before, after] of [
        ['runner.command(shellQuote(document.uri.path))', 'runner.command(document.uri.path)'],
        ['await document.save();', ''],
        ['if (document.isDirty)', 'if (false)'],
        ["['..', '..', 'toolchains.json']", "['..', 'toolchains.json']"],
    ]) {
        assert.ok(source.includes(before), 'mutation target is absent: ' + before);
        await assert.rejects(() => checks(source.replace(before, after)), undefined, 'undetected mutation: ' + before);
    }
    console.log('ok: Run File quoting, save ordering, toolchain lookup, nine invocation schedules, and four mutations');
})().catch((error) => { console.error(error); process.exitCode = 1; });
