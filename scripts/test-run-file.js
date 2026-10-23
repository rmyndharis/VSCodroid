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
    saved = true, installed = '[{"name":"java"},{"name":"ruby"},{"name":"cpp"}]', readError } = {}) {
    const commands = new Map();
    const events = [];
    class Uri {
        constructor(value) { this.path = value; }
        static joinPath(base, ...parts) { return new Uri(path.posix.join(base.path, ...parts)); }
    }
    const document = {
        uri: new Uri(file), languageId: language, isUntitled: false, isDirty: dirty,
        async save() { events.push('save'); this.isDirty = !saved; return saved; },
    };
    const disposable = () => ({ dispose() {} });
    const vscode = {
        Uri,
        commands: { registerCommand(id, callback) { commands.set(id, callback); return disposable(); } },
        workspace: {
            fs: { async readFile(uri) {
                events.push(['read', uri.path]);
                if (readError) throw readError;
                return Buffer.from(installed);
            } },
            onDidCloseTextDocument: disposable, onDidChangeWorkspaceFolders: disposable,
            getWorkspaceFolder: () => undefined, workspaceFolders: [], textDocuments: [document],
        },
        window: {
            activeTextEditor: { document }, terminals: [], createStatusBarItem: disposable,
            showInformationMessage: (message) => { events.push(['info', message]); },
            showErrorMessage: (message) => { events.push(['error', message]); },
            createTerminal(options) {
                events.push(['terminal', options.cwd]);
                return { show() {}, sendText(line) { events.push(['line', line]); } };
            },
        },
    };
    const sandbox = { require: () => vscode, module: { exports: {} }, TextDecoder };
    vm.runInNewContext(code, sandbox, { filename: extension });
    sandbox.module.exports.activate({
        extensionUri: new Uri(home + '/.vscodroid/extensions/vscodroid.saf-bridge-1.0.0'),
        subscriptions: [], globalState: { get: (_, fallback) => fallback },
    });
    await commands.get('vscodroid.runFile')();
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

(async () => {
    await checks(source);
    for (const [before, after] of [
        ['runner.command(shellQuote(document.uri.path))', 'runner.command(document.uri.path)'],
        ['await document.save();', ''],
        ['if (document.isDirty)', 'if (false)'],
        ["['..', '..', 'toolchains.json']", "['..', 'toolchains.json']"],
    ]) {
        assert.ok(source.includes(before), 'mutation target is absent: ' + before);
        await assert.rejects(() => checks(source.replace(before, after)), undefined, 'undetected mutation: ' + before);
    }
    console.log('ok: Run File quoting, save ordering, toolchain lookup, and four mutations');
})().catch((error) => { console.error(error); process.exitCode = 1; });
