/**
 * Self-check for the editor's handling of a composing keyboard, patches 0046 to 0053.
 *
 *   node scripts/check-editcontext-ime.js <patched vscode source root>
 *
 * Those patches decide, update by update, what a soft keyboard may write into the editor:
 * which updates are applied, which are refused, at which cursors, and when input restarts.
 * The decisions live in `nativeEditContext.ts` of Code - OSS, which this repository holds
 * only as diffs, so nothing here compiles them and a fingerprint row can only say that
 * their text reached the bundle. This runs the patched file itself. It reads it from a
 * source tree that has every patch applied, turns it into JavaScript with Node's own
 * `module.stripTypeScriptTypes`, and evaluates it together with the few upstream modules
 * that are small or carry what the patches call (REAL below: positions, ranges,
 * selections, the replace command and the composition operation of 0024, the focus
 * tracker). Everything else the file imports is a fake of a few lines (`fakes`) or an
 * inert stand-in, so an import that upstream adds does not stop the run.
 * build-vscode-oss.sh runs this on the tree it has just patched, beside
 * check-editcontext-sync.py, and a failure stops the build.
 *
 * It needs the transform mode of `stripTypeScriptTypes`, because the files hold enums and
 * parameter properties, which are not types to erase. Node 22.23 and Node 24.18, the build
 * host's at the 1.139.1 pin, have it. Node 26.11 accepts only the strip mode and this
 * exits 2 there: a pin whose `.nvmrc` moves the build host that far needs the loader to
 * turn those two forms, and the one namespace of editContextFactory.ts, into JavaScript
 * itself.
 *
 * What the fakes model, because the rules depend on it:
 *
 *   - The EditContext holds its own text and selection, and the browser has applied an
 *     update to them when `textupdate` fires. The event's fields are getters on a
 *     prototype, as the browser's are, so a spread copies none of them.
 *   - A dispatch from here is a task, and the microtasks queued in it run before the next
 *     one, as they do after a listener the browser called. `updatesInOneTask` is the
 *     exception that a script on the page can make and a keyboard cannot.
 *   - `blur()` and `focus()` on the editing host fire their events at once. A blur ends a
 *     composition the keyboard had open, with `compositionend` after the `blur` listeners,
 *     and no composition is open after a restart until a case opens one.
 *   - An edit goes through the real composition and replace commands, applied to a model
 *     that is one string with a version. The content listener hears the edit first and
 *     the cursor event follows, the order the editor gives an edit made through a cursor.
 *     Overlapping edits throw: the editor drops a cursor there and this model does not.
 *
 * What they do not model is the keyboard. A case states what the keyboard sends, taken
 * from the recordings the patch descriptions quote, and what the editor must hold
 * afterwards. That holds for what follows a refusal as well: the two cases named "as
 * recorded" replay what Gboard sent after the restart a refusal forced, and the two named
 * "a keyboard that goes on with the range" replay one that did not read the text again,
 * which neither Gboard recorded did.
 *
 * 0052 and 0053 are reached without their widgets. `_onListTap` runs on an object that
 * has the three members it reads, and `SuggestModel` is built on an editor made of its
 * event hooks, with upstream's refilter replaced by a counter.
 *
 * NEGATIVE CONTROL, measured on the 1.139.1 tree with Node 22.23.2. Against upstream's
 * files, with no patch applied, 77 of the 84 cases fail. Each of these single edits to a
 * copy of a patched file fails at least one case:
 *
 *   0046  the body of the blur listener removed; the composing clause dropped from the
 *         restart test.
 *   0047  the constructor's fill removed; its `initializing` argument ignored.
 *   0048  in `_isSelectionOnly`: the equal-text alternative removed, the collapsed
 *         selection no longer required, a moved selection accepted with several cursors.
 *   0049  `_cursorsHoldSameText` answering true, and answering false;
 *         `_inputAtEveryCursor` answering nothing; its test for a paste removed; the
 *         surrogate test of its deletion removed; a longer deletion taken for a Backspace;
 *         `_dismissingCursors` not set.
 *   0050  the call removed from the chain for `_isBeyondBuffer`,
 *         `_isRememberedWordRewrite`, `_isTailDropOnReopen`, `_isOneUnitOverWord`,
 *         `_isUntypedWriteKeepingCaret`, `_isUntypedInsertKeepingCaret` and
 *         `_isTypedWriteKeepingCaret`; `_isInStepRewrite` answering false; each of the
 *         three exemptions removed (one unit over one unit, a doubled word, a Backspace on
 *         two letters); a letter counted in units; any one code point counted as a letter;
 *         Han, kana and Hangul counted as letters; the bound raised from six to seven; a
 *         forced restart not counted; the count not started again at a new version of the
 *         model; the clamp past the bound removed, or only the cut of its start; the typed
 *         flag never set, and not cleared at compositionstart; the restart after a
 *         refusal not forced; the buffer not written back at a refusal.
 *   0051  `_caretUnmoved` answering false; its single-caret test removed; the count of
 *         carets not taken when the others are dropped.
 *   0052  the bounds test of `_onListTap` removed.
 *   0053  the Android branch of the content listener removed, and taken on every
 *         platform; `_refilterWhileComposing` going on with no list open.
 *
 * Three edits change no case, and no case could see them. The calls of
 * `_isStaleWriteAfterCaret` and `_isGrowthAfterCaret` removed from the chain:
 * `_isUntypedWriteKeepingCaret` refuses everything those two refuse. The reset in
 * `_afterAppliedEdit` removed: it runs only when the model's version has moved, and the
 * next update starts the count again for that reason.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const util = require('util');
const { stripTypeScriptTypes } = require('node:module');

// stripTypeScriptTypes announces that it is experimental on every call.
process.removeAllListeners('warning');
process.on('warning', (warning) => {
    if (warning.name !== 'ExperimentalWarning') {
        console.warn(warning);
    }
});
// Enums and parameter properties are not types to erase: only the transform mode turns
// them into JavaScript, and a Node without it cannot run this.
try {
    stripTypeScriptTypes('enum Probe { A }', { mode: 'transform' });
} catch (error) {
    console.error(`check-editcontext-ime.js needs module.stripTypeScriptTypes with its transform mode, which Node ${process.version} lacks: ${error.message}`);
    process.exit(2);
}

const NATIVE = 'vs/editor/browser/controller/editContext/native/';
const SUGGEST = 'vs/editor/contrib/suggest/browser/';

if (process.argv.length !== 3 || !fs.existsSync(path.join(process.argv[2], 'src', `${NATIVE}nativeEditContext.ts`))) {
    console.error('usage: node scripts/check-editcontext-ime.js <patched vscode source root>');
    process.exit(2);
}
const SRC = path.join(process.argv[2], 'src');

// Loaded from the tree as they are. A module that is neither named here nor faked is a stand-in.
const REAL = new Set([
    'vs/editor/common/core/position',
    'vs/editor/common/core/range',
    'vs/editor/common/core/selection',
    'vs/editor/common/core/ranges/offsetRange',
    'vs/editor/common/cursorEvents',
    'vs/editor/common/commands/replaceCommand',
    'vs/editor/common/cursor/cursorTypeEditOperations',
    `${NATIVE}editContextFactory`,
    `${NATIVE}nativeEditContextUtils`,
    `${NATIVE}nativeEditContext`,
    `${SUGGEST}suggestWidget`,
    `${SUGGEST}suggestModel`,
]);

// Answers every property, call and construction with itself, so code that only passes a
// service along, registers a listener nobody fires or reads an option keeps running.
const standIn = new Proxy(function () { }, {
    get: (target, key) => key === 'prototype' ? target.prototype
        : key === Symbol.toPrimitive ? () => 0
            : key === 'then' || typeof key === 'symbol' ? undefined : standIn,
    apply: () => standIn,
    construct: (target, args, newTarget) => newTarget === standIn ? standIn : Object.create(newTarget.prototype),
});

class Target {
    constructor() {
        this.listeners = new Map();
    }
    addEventListener(type, listener) {
        this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
    }
    fire(type, event = {}) {
        for (const listener of this.listeners.get(type) ?? []) {
            listener.call(this, event);
        }
    }
}

class EditContext extends Target {
    constructor() {
        super();
        this.text = '';
        this.selectionStart = 0;
        this.selectionEnd = 0;
        // The keyboard's side: whether it has a composition open on this context.
        this.composing = false;
        this.writes = 0;
    }
    updateText(start, end, text) {
        this.text = this.text.slice(0, start) + text + this.text.slice(end);
        this.writes++;
    }
    updateSelection(start, end) {
        this.selectionStart = start;
        this.selectionEnd = end;
    }
}

class TextUpdate {
    #fields;
    constructor(fields) {
        this.#fields = fields;
    }
    get text() { return this.#fields.text; }
    get updateRangeStart() { return this.#fields.updateRangeStart; }
    get updateRangeEnd() { return this.#fields.updateRangeEnd; }
    get selectionStart() { return this.#fields.selectionStart; }
    get selectionEnd() { return this.#fields.selectionEnd; }
}

class Element extends Target {
    constructor(document, name) {
        super();
        this.ownerDocument = document;
        this.name = name;
    }
    setAttribute() { }
    focus() {
        const document = this.ownerDocument;
        if (document.activeElement === this) {
            return;
        }
        document.activeElement?.blur();
        document.activeElement = this;
        document.log.push(`focus ${this.name}`);
        this.fire('focus');
    }
    blur() {
        const document = this.ownerDocument;
        if (document.activeElement !== this) {
            return;
        }
        document.activeElement = null;
        document.log.push(`blur ${this.name}`);
        this.fire('blur');
        if (this.editContext?.composing) {
            this.editContext.composing = false;
            this.editContext.fire('compositionend');
        }
    }
}

function makeDocument() {
    const document = {
        activeElement: null,
        log: [],
        made: [],
        defaultView: Object.assign(new Target(), { EditContext }),
        createElement(name) {
            const element = new Element(document, name);
            document.made.push(element);
            return element;
        },
    };
    return document;
}

function fakes(isAndroid, document, real) {
    const { Position } = real('vs/editor/common/core/position');
    return {
        'vs/base/common/platform': { isAndroid },
        'vs/base/common/strings': {
            isHighSurrogate: code => 0xD800 <= code && code <= 0xDBFF,
            isLowSurrogate: code => 0xDC00 <= code && code <= 0xDFFF,
        },
        'vs/base/common/lifecycle': {
            Disposable: class {
                _register(value) { return value; }
                dispose() { }
            },
        },
        'vs/base/browser/dom': {
            addDisposableListener(node, type, listener) {
                node.addEventListener(type, listener);
                return { dispose() { } };
            },
            getActiveElement: () => document.activeElement,
            getWindow: () => document.defaultView,
            getWindowId: () => 1,
            getShadowRoot: () => null,
            getDomNodePagePosition: node => node.rect,
        },
        'vs/base/browser/fastDomNode': {
            FastDomNode: class {
                constructor(domNode) { this.domNode = domNode; }
                setClassName() { }
                setAttribute() { }
            },
        },
        'vs/editor/browser/controller/editContext/editContext': {
            AbstractEditContext: class {
                constructor(context) { this._context = context; }
                _register(value) { return value; }
                dispose() { }
            },
        },
        'vs/editor/common/cursorCommon': {
            CursorState: { fromModelSelection: selection => ({ modelState: { selection } }) },
            EditOperationResult: class {
                constructor(type, commands) { this.commands = commands; }
            },
        },
        'vs/editor/common/core/text/positionToOffset': {
            PositionOffsetTransformer: class {
                constructor(text) { this.text = text; }
                getPosition(offset) {
                    const before = this.text.slice(0, offset).split('\n');
                    return new Position(before.length, before[before.length - 1].length + 1);
                }
            },
        },
    };
}

// The patched file and what it stands on, evaluated for one platform.
function load(isAndroid) {
    const document = makeDocument();
    const cache = new Map();
    const real = (id) => {
        if (cache.has(id)) {
            return cache.get(id);
        }
        // A parameter decorator is not JavaScript and type stripping leaves it in place.
        const source = fs.readFileSync(path.join(SRC, `${id}.ts`), 'utf8')
            .replace(/@\w+\s+(?=(?:(?:private|protected|public|readonly)\s+)*\w+\??:)/g, '');
        const names = [];
        const body = stripTypeScriptTypes(source, { mode: 'transform' })
            .replace(/^import\s*\{([^}]*)\}\s*from\s*(['"])([^'"]+)\2;?/gm,
                (all, list, quote, from) => `const {${list.replace(/\bas\b/g, ':')}} = __import(${JSON.stringify(from)});`)
            .replace(/^import\s*\*\s*as\s+(\w+)\s+from\s*(['"])([^'"]+)\2;?/gm,
                (all, name, quote, from) => `const ${name} = __import(${JSON.stringify(from)});`)
            .replace(/^import\s*(['"])[^'"]+\1;?/gm, '')
            .replace(/^export\s+(?=(?:async\s+)?(?:class|function|const|let|var)\s+(\w+))/gm,
                (all, name) => { names.push(name); return ''; });
        const unread = body.match(/^(?:import|export)\b.*$/m);
        if (unread) {
            throw new Error(`${id}.ts: the loader does not read this form: ${unread[0]}`);
        }
        const resolve = from => path.posix.normalize(path.posix.join(path.posix.dirname(id), from)).replace(/\.js$/, '');
        const exports = new Function('__import', 'document', `${body}\nreturn { ${names.join(', ')} };`)(
            from => imported(resolve(from)), document);
        cache.set(id, exports);
        return exports;
    };
    let fake = {};
    const imported = id => REAL.has(id) ? real(id)
        : fake[id] ? new Proxy(fake[id], { get: (target, key) => key in target ? target[key] : standIn })
            : standIn;
    fake = fakes(isAndroid, document, real);
    return {
        document,
        ...real('vs/editor/common/core/position'),
        ...real('vs/editor/common/core/range'),
        ...real('vs/editor/common/core/selection'),
        ...real('vs/editor/common/cursorEvents'),
        ...real('vs/editor/common/commands/replaceCommand'),
        ...real('vs/editor/common/cursor/cursorTypeEditOperations'),
        ...real(`${NATIVE}nativeEditContext`),
        ...real(`${SUGGEST}suggestWidget`),
        ...real(`${SUGGEST}suggestModel`),
    };
}

// The text model: one string, lines broken by \n, and a version that every edit moves.
class Model {
    constructor(tree, text) {
        this.tree = tree;
        this.value = text;
        this.version = 1;
        this.listeners = [];
    }
    lines() { return this.value.split('\n'); }
    getVersionId() { return this.version; }
    getLineCount() { return this.lines().length; }
    getLineMaxColumn(lineNumber) { return this.lines()[lineNumber - 1].length + 1; }
    offsetAt(position) {
        return this.lines().slice(0, position.lineNumber - 1).reduce((sum, line) => sum + line.length + 1, 0) + position.column - 1;
    }
    positionAt(offset) {
        const before = this.value.slice(0, offset).split('\n');
        return new this.tree.Position(before.length, before[before.length - 1].length + 1);
    }
    validatePosition(position) {
        const lineNumber = Math.min(Math.max(position.lineNumber, 1), this.getLineCount());
        const column = Math.min(Math.max(position.column, 1), this.getLineMaxColumn(lineNumber));
        return new this.tree.Position(lineNumber, column);
    }
    isValidRange(range) {
        return [range.getStartPosition(), range.getEndPosition()].every(position => {
            const offset = this.offsetAt(position);
            // Not between the halves of a surrogate pair, which the editor's model refuses too.
            return position.equals(this.validatePosition(position)) &&
                !(/[\ud800-\udbff]/.test(this.value[offset - 1] ?? '') && /[\udc00-\udfff]/.test(this.value[offset] ?? ''));
        });
    }
    getValueInRange(range) {
        return this.value.slice(this.offsetAt(range.getStartPosition()), this.offsetAt(range.getEndPosition()));
    }
    getValueLengthInRange(range) { return this.getValueInRange(range).length; }
    onDidChangeContent(listener) {
        this.listeners.push(listener);
        return { dispose() { } };
    }
    // Applies the edits and answers, for each, the range its text now occupies.
    applyEdits(edits) {
        const spans = edits
            .map(edit => ({ ...edit, start: this.offsetAt(edit.range.getStartPosition()), end: this.offsetAt(edit.range.getEndPosition()) }))
            .sort((a, b) => a.start - b.start || a.end - b.end);
        let value = '';
        let at = 0;
        for (const span of spans) {
            if (span.start < at) {
                throw new Error('overlapping edits: the editor drops a cursor here, which this model does not');
            }
            value += this.value.slice(at, span.start);
            span.written = value.length;
            value += span.text;
            at = span.end;
        }
        value += this.value.slice(at);
        // An edit that replaces nothing with nothing is none: no version, no event.
        const changes = spans.filter(span => span.start !== span.end || span.text !== '').map(span => ({ range: span.range, text: span.text }));
        if (changes.length) {
            this.value = value;
            this.version++;
            for (const listener of this.listeners) {
                listener({ changes });
            }
        }
        return spans.map(span => ({
            command: span.command,
            range: this.tree.Range.fromPositions(this.positionAt(span.written), this.positionAt(span.written + span.text.length)),
        }));
    }
}

// An editor with a keyboard attached: the model, the cursors, the patched edit context
// and the EditContext it made, with the editing host focused.
class Page {
    constructor(tree, text, carets, options = {}) {
        const document = tree.document;
        document.activeElement = null;
        document.log = [];
        document.made = [];
        this.tree = tree;
        this.document = document;
        this.model = new Model(tree, text);
        this.readOnly = false;
        this.cursors = carets.map(caret => this.selection(caret));
        const viewModel = {
            model: this.model,
            getCursorStates: () => this.cursors.map(selection => ({ modelState: { selection } })),
            getPrimaryCursorState: () => ({ modelState: { selection: this.cursors[0] } }),
            setCursorStates: (source, reason, states) => this.setCursors(states.map(state => state.modelState.selection), reason),
            setHasFocus: (focused) => { document.log.push(`editor focused ${focused}`); },
            onCompositionStart() { },
            onCompositionEnd() { },
        };
        const { ReplaceCommand, CompositionOperation, CursorChangeReason, Selection } = tree;
        const viewController = {
            type: typed => this.run(this.cursors.map(selection => new ReplaceCommand(selection, typed)), CursorChangeReason.NotSet),
            paste: pasted => this.run(this.cursors.map(selection => new ReplaceCommand(selection, pasted)), CursorChangeReason.Paste),
            // As the cursor controller does it: nothing to replace and nothing to write only moves the carets.
            compositionType: (typed, before, after, delta) => typed === '' && before === 0 && after === 0
                ? delta !== 0 && this.setCursors(this.cursors.map((selection) => {
                    const position = selection.getPosition();
                    return new Selection(position.lineNumber, position.column + delta, position.lineNumber, position.column + delta);
                }), CursorChangeReason.NotSet)
                : this.run(CompositionOperation.getEdits(undefined, undefined, this.model, this.cursors, typed, before, after, delta).commands, CursorChangeReason.NotSet),
            compositionStart() { },
            compositionEnd() { },
        };
        const context = { viewModel, configuration: { options: { get: () => 0 } } };
        const guard = { domNode: new Element(document, 'guard'), appendChild() { } };
        this.editContext = new tree.NativeEditContext('editor', context, guard, viewController, standIn, standIn, standIn);
        this.host = document.made[0];
        this.host.name = 'host';
        this.buffer = this.host.editContext;
        if (options.focus !== false) {
            this.editContext.focus();
        }
    }

    // A caret is an offset into the whole text, a selection a pair of them.
    selection(caret) {
        const [anchor, active = anchor] = [caret].flat();
        return this.tree.Selection.fromPositions(this.model.positionAt(anchor), this.model.positionAt(active));
    }

    setCursors(selections, reason) {
        const moved = selections.length !== this.cursors.length || selections.some((selection, i) => !selection.equalsSelection(this.cursors[i]));
        this.cursors = selections;
        if (moved) {
            this.cursorEvent(reason);
        }
    }

    cursorEvent(reason) {
        this.editContext.onCursorStateChanged({ selections: this.cursors, modelSelections: this.cursors, reason });
    }

    // Runs one command per cursor: every edit in one change of the model, then the cursors
    // where the commands leave them, then the cursor event if anything changed.
    run(commands, reason) {
        if (this.readOnly) {
            return;
        }
        const version = this.model.version;
        const edits = [];
        commands.forEach((command, i) => command?.getEditOperations(this.model, {
            addEditOperation: (range, text) => edits.push({ command: i, range, text: text ?? '' }),
            addTrackedEditOperation: (range, text) => edits.push({ command: i, range, text: text ?? '' }),
        }));
        const written = this.model.applyEdits(edits);
        const selections = commands.map((command, i) => command
            ? command.computeCursorState(this.model, { getInverseEditOperations: () => written.filter(edit => edit.command === i) })
            : this.cursors[i]);
        const moved = selections.some((selection, i) => !selection.equalsSelection(this.cursors[i]));
        this.cursors = selections;
        if (moved || this.model.version !== version) {
            this.cursorEvent(reason);
        }
    }

    // One turn of the event loop, so the microtasks of the task before have run.
    settle() {
        return new Promise(resolve => setImmediate(resolve));
    }

    // The keyboard opens a composition.
    async compose() {
        this.buffer.composing = true;
        this.buffer.fire('compositionstart');
        await this.settle();
    }

    async endComposition() {
        this.buffer.composing = false;
        this.buffer.fire('compositionend');
        await this.settle();
    }

    // The keyboard writes `text` over start..end of the buffer and asks for the caret, or the
    // selection, at the offsets given: after the text unless said otherwise.
    send(text, start, end, caret = start + text.length, caretEnd = caret) {
        this.buffer.text = this.buffer.text.slice(0, start) + text + this.buffer.text.slice(end);
        this.buffer.selectionStart = caret;
        this.buffer.selectionEnd = caretEnd;
        this.buffer.fire('textupdate', new TextUpdate({ text, updateRangeStart: start, updateRangeEnd: end, selectionStart: caret, selectionEnd: caretEnd }));
    }

    async update(text, start, end, caret, caretEnd) {
        this.send(text, start, end, caret, caretEnd);
        await this.settle();
    }

    // Several updates with no microtask between them, which only a script on the page can do.
    async updatesInOneTask(...updates) {
        for (const update of updates) {
            this.send(...update);
        }
        await this.settle();
    }

    // The same update `times` times, a task each.
    async repeat(times, ...update) {
        for (let i = 0; i < times; i++) {
            await this.update(...update);
        }
    }

    // A tap or a cursor key: the editor moves the carets itself.
    async tap(...carets) {
        this.setCursors(carets.map(caret => this.selection(caret)), this.tree.CursorChangeReason.Explicit);
        await this.settle();
    }

    // The view sends the cursors again where they are, as it does when its lines wrap anew.
    async sameCursorAgain() {
        this.cursorEvent(this.tree.CursorChangeReason.NotSet);
        await this.settle();
    }

    // A paste the editor makes, from its menu or a hardware key.
    async paste(text) {
        this.run(this.cursors.map(selection => new this.tree.ReplaceCommand(selection, text)), this.tree.CursorChangeReason.Paste);
        await this.settle();
    }

    // A restart of input is the host blurred and focused again with nothing told to the
    // editor in between, which is what the paused focus tracker leaves in the log.
    get restarts() {
        return this.document.log.filter((entry, i, log) => entry === 'blur host' && log[i + 1] === 'focus host').length;
    }

    state() {
        const offsets = selection => {
            const anchor = this.model.offsetAt(new this.tree.Position(selection.selectionStartLineNumber, selection.selectionStartColumn));
            const active = this.model.offsetAt(selection.getPosition());
            return anchor === active ? active : [anchor, active];
        };
        return {
            text: this.model.value,
            carets: this.cursors.map(offsets),
            restarts: this.restarts,
            version: this.model.version,
            buffer: this.buffer.text,
            bufferCaret: this.buffer.selectionStart === this.buffer.selectionEnd ? this.buffer.selectionStart : [this.buffer.selectionStart, this.buffer.selectionEnd],
            writes: this.buffer.writes,
            editorFocus: this.document.log.filter(entry => entry.startsWith('editor focused')),
        };
    }
}

const cases = [];

// A case builds a page, plays the keyboard and answers the page's state with what it wants
// of it. Only the members named in what it wants are compared.
function check(name, play) {
    cases.push([name, play]);
}

const android = load(true);
const desktop = load(false);
const page = (text, carets, options) => new Page(android, text, carets, options);

// What a refusal leaves: the text and the caret as they were, no edit, one restart more.
const refused = (text, caret, restarts = 1) => ({ text, carets: [caret], version: 1, restarts, buffer: text, bufferCaret: caret });
// What an applied update leaves, with no restart unless said.
const applied = (text, caret, restarts = 0) => ({ text, carets: [caret].flat(), restarts });

// --- 0047: the buffer is there before the keyboard connects ---

check('0047: the buffer is filled from the primary selection when the edit context is built', async () => {
    const p = page('first\nSEED BETA 1\nlast', [10], { focus: false });
    return [p.state(), { buffer: 'SEED BETA 1', bufferCaret: 4, text: 'first\nSEED BETA 1\nlast', carets: [10], restarts: 0 }];
});

check('0047: a selection over two lines fills the buffer with both', async () => {
    const p = page('first\nSEED BETA 1\nlast', [[2, 10]], { focus: false });
    return [p.state(), { buffer: 'first\nSEED BETA 1', bufferCaret: [2, 10], restarts: 0 }];
});

check('0047: the first fill restarts nothing, though the host takes focus in the same task', async () => {
    const p = page('SEED BETA 1', [0]);
    await p.settle();
    return [p.state(), { buffer: 'SEED BETA 1', bufferCaret: 0, text: 'SEED BETA 1', carets: [0], restarts: 0 }];
});

check('0047: the first letter then lands at the caret', async () => {
    const p = page('SEED BETA 1', [0]);
    await p.compose();
    await p.update('a', 0, 0);
    return [p.state(), applied('aSEED BETA 1', 1)];
});

// --- 0048: a word written over itself is no edit ---

check('0048: a word written over its own range, the selection collapsed, makes no edit', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('two', 5, 8, 8);
    return [p.state(), { text: 'line two', carets: [8], version: 1, restarts: 0 }];
});

check('0048: the same update asking for the caret elsewhere moves the caret and makes no edit', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('two', 5, 8, 6);
    return [p.state(), { text: 'line two', carets: [6], version: 1, restarts: 0, buffer: 'line two', bufferCaret: 6 }];
});

check('0048: a replacement of the same length still edits', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('twx', 5, 8);
    return [p.state(), { text: 'line twx', carets: [8], version: 2, restarts: 0 }];
});

check('0048: an equal commit over a selection is still typing', async () => {
    const p = page('line two', [[5, 8]]);
    await p.compose();
    await p.update('two', 5, 8);
    return [p.state(), { text: 'line two', carets: [8], version: 2, restarts: 0 }];
});

check('0048: with two cursors an equal update that keeps its selection touches neither word', async () => {
    const p = page('alpha\nbravo', [5, 11]);
    await p.compose();
    await p.update('alpha', 0, 5, 5);
    return [p.state(), { text: 'alpha\nbravo', carets: [5, 11], version: 1, restarts: 0 }];
});

check('0048: with the same word at two cursors, an equal update that moves the caret keeps both cursors', async () => {
    const p = page('alpha\nalpha', [5, 11]);
    await p.compose();
    await p.update('alpha', 0, 5, 3);
    return [p.state(), { text: 'alpha\nalpha', carets: [3, 9], restarts: 0 }];
});

// --- 0046: an edit that is not the keyboard's, across an open composition ---

check('0046: a paste across an open composition restarts input, and the editor keeps the focus', async () => {
    const p = page('helloab', [7]);
    await p.compose();
    await p.update('helloab', 0, 7, 7);
    await p.paste('XYZ');
    return [p.state(), { text: 'helloabXYZ', carets: [10], restarts: 1, buffer: 'helloabXYZ', bufferCaret: 10, editorFocus: ['editor focused true'] }];
});

check('0046: the same paste with no composition open restarts nothing', async () => {
    const p = page('helloab', [7]);
    await p.paste('XYZ');
    return [p.state(), { text: 'helloabXYZ', carets: [10], restarts: 0 }];
});

check('0046: the keyboard typing in its own composition restarts nothing', async () => {
    const p = page('hello', [5]);
    await p.compose();
    await p.update('helloa', 0, 5);
    await p.update('helloab', 0, 6);
    return [p.state(), applied('helloab', 7)];
});

check('0046: a blur clears the open mark, so a composition ended without its event costs no restart later', async () => {
    const p = page('helloab', [7]);
    await p.compose();
    // An empty commit: the keyboard's composition is over and no compositionend said so.
    p.buffer.composing = false;
    // The page's own guard ends what it takes for an open composition, with a blur and a focus.
    p.host.blur();
    p.host.focus();
    await p.paste('XYZ');
    return [p.state(), { text: 'helloabXYZ', carets: [10], restarts: 0 }];
});

// --- 0049: several cursors ---

check('0049: typed text goes to every cursor where the cursors hold different words', async () => {
    const p = page('alpha\nbravo', [5, 11]);
    await p.compose();
    await p.update('alphax', 0, 5);
    return [p.state(), applied('alphax\nbravox', [6, 13])];
});

check('0049: one Backspace goes to every cursor where the cursors hold different words', async () => {
    const p = page('alphax\nbravox', [6, 13]);
    await p.compose();
    await p.update('alpha', 0, 6);
    return [p.state(), applied('alpha\nbravo', [5, 11])];
});

check('0049: a correction of the word that every cursor holds reaches every cursor', async () => {
    const p = page('alpha\nalpha', [5, 11]);
    await p.compose();
    await p.update('Alpha', 0, 5);
    return [p.state(), applied('Alpha\nAlpha', [5, 11])];
});

check('0049: a suggestion at cursors that hold different words goes to the primary cursor alone', async () => {
    const p = page('alphax\nbravox', [6, 13]);
    await p.compose();
    const writes = p.buffer.writes;
    await p.update('alphabet', 0, 6);
    // The buffer holds the keyboard's change already: dropping the cursors writes nothing to it.
    return [{ ...p.state(), written: p.buffer.writes - writes }, { ...applied('alphabet\nbravox', [8]), written: 0 }];
});

check('0049: a commit long enough to be a paste goes to the primary cursor alone', async () => {
    const clip = 'x'.repeat(70);
    const p = page('alpha\nbravo', [5, 11]);
    await p.update(`alpha${clip}`, 0, 5);
    return [p.state(), applied(`alpha${clip}\nbravo`, [75])];
});

check('0049: a word deleted whole is not a Backspace and goes to the primary cursor alone', async () => {
    const p = page('alphax\nbravox', [6, 13]);
    await p.compose();
    await p.update('', 0, 6);
    return [p.state(), applied('\nbravox', [0])];
});

check('0049: a Backspace that would cut a pair in half at another cursor goes to the primary cursor alone', async () => {
    const p = page('ab\na\u{1F600}', [2, 6]);
    await p.compose();
    await p.update('a', 0, 2);
    return [p.state(), applied('a\na\u{1F600}', [1])];
});

check('0049: an emoji typed goes whole to every cursor', async () => {
    const p = page('a\nb', [1, 3]);
    await p.compose();
    await p.update('a\u{1F600}', 0, 1);
    return [p.state(), applied('a\u{1F600}\nb\u{1F600}', [3, 7])];
});

check('0049: off Android the primary word is written at every cursor, as upstream writes it', async () => {
    const p = new Page(desktop, 'alpha\nbravo', [5, 11]);
    await p.compose();
    await p.update('alphax', 0, 5);
    return [p.state(), applied('alphax\nalphax', [6, 13])];
});

// --- 0051: the buffer stays while the caret does ---

// A line typed after the keyboard's own Enter: the buffer keeps its start on the line above (0024).
async function wordOnNewLine(composing) {
    const p = page('line one', [8]);
    await p.update('\n', 8, 8);
    if (composing) {
        await p.compose();
    }
    await p.update('two', 9, 9);
    return p;
}

check('0051: the buffer starts on the line above after the keyboard typed Enter (the state the next cases stand on)', async () => {
    const p = await wordOnNewLine(true);
    return [p.state(), { text: 'line one\ntwo', carets: [12], buffer: 'line one\ntwo', bufferCaret: 12, restarts: 0 }];
});

check('0051: a cursor event that leaves the single caret in place keeps the buffer and writes nothing', async () => {
    const p = await wordOnNewLine(true);
    const writes = p.buffer.writes;
    await p.sameCursorAgain();
    return [{ ...p.state(), written: p.buffer.writes - writes }, { text: 'line one\ntwo', carets: [12], buffer: 'line one\ntwo', bufferCaret: 12, restarts: 0, written: 0 }];
});

check('0051: a tap moves the buffer to the caret line as before', async () => {
    const p = await wordOnNewLine(false);
    await p.tap(10);
    return [p.state(), { text: 'line one\ntwo', carets: [10], buffer: 'two', bufferCaret: 1, restarts: 0 }];
});

check('0051: a second caret moves the buffer to the primary caret line as before', async () => {
    const p = await wordOnNewLine(false);
    await p.tap(12, 3);
    return [p.state(), { carets: [12, 3], buffer: 'two', bufferCaret: 3, restarts: 0 }];
});

check('0051: the count of carets is taken when the others are dropped, so a cursor event after a discarded edit keeps the buffer', async () => {
    const p = page('ab\ncd', [2, 5]);
    await p.update('\n', 2, 2);
    const before = p.state();
    // A read-only editor from here: the edit that follows the drop is discarded.
    p.readOnly = true;
    await p.compose();
    await p.update('zz', 0, 2);
    await p.sameCursorAgain();
    return [{ before: [before.text, before.carets, before.buffer], ...p.state() },
        { before: ['ab\n\ncd\n', [3, 7], 'ab\n'], text: 'ab\n\ncd\n', carets: [3], buffer: 'ab\n', bufferCaret: 3 }];
});

// --- 0050: updates a keyboard in step does not send ---

check('0050 first rule: the word before the caret written over an earlier range is refused', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('two', 0, 4, 8);
    return [p.state(), refused('line two', 8)];
});

check('0050 first rule: the word after the caret written over the range before it is refused (the Tab inside a word)', async () => {
    const p = page('WO  RD ONE', [4]);
    await p.compose();
    await p.update('WO', 0, 2, 4);
    await p.update('RD', 0, 2, 4);
    return [p.state(), refused('WO  RD ONE', 4)];
});

check('0050 first rule: the typed letter joined to the word after it and written over the letter is refused', async () => {
    const p = page('SEED BETA 1', [0]);
    await p.compose();
    await p.update('a', 0, 0);
    await p.update('aSEED', 0, 1, 1);
    return [p.state(), { text: 'aSEED BETA 1', carets: [1], version: 2, restarts: 1, buffer: 'aSEED BETA 1', bufferCaret: 1 }];
});

check('0050 first rule: in a composition that has typed, the word written over an earlier range is refused', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 8, 8);
    await p.update('twox', 0, 4, 9);
    return [p.state(), { text: 'line twox', carets: [9], version: 2, restarts: 1, buffer: 'line twox', bufferCaret: 9 }];
});

check('0050 first rule: a word that grows in place in a composition that has typed is applied, though the same text follows the caret', async () => {
    const p = page('하낙', [0]);
    await p.compose();
    await p.update('하나', 0, 0);
    await p.update('하낙', 0, 2, 2);
    return [p.state(), applied('하낙하낙', 2)];
});

check('0050 first rule: one unit written over one unit is applied (a kana toggled beside the same kana)', async () => {
    const p = page('あい', [1]);
    await p.compose();
    await p.update('い', 0, 1, 1);
    return [p.state(), applied('いい', 1)];
});

check('0050 second rule: the part of a reopened word before the caret written over the whole word is refused', async () => {
    const p = page('line twzox', [8]);
    await p.compose();
    await p.update('twz', 5, 10, 8);
    return [p.state(), refused('line twzox', 8)];
});

check('0050 second rule: a doubled word taken back is applied', async () => {
    const p = page('twotwo', [3]);
    await p.compose();
    await p.update('two', 0, 6, 3);
    return [p.state(), applied('two', 3)];
});

check('0050 fourth rule: one letter written over a reopened word is refused', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    return [p.state(), refused('line two', 8)];
});

check('0050 fourth rule: the same with the caret inside the word', async () => {
    const p = page('alpha beta Charlie delta', [22]);
    await p.compose();
    await p.update('z', 19, 24);
    return [p.state(), refused('alpha beta Charlie delta', 22)];
});

check('0050 fourth rule: a letter outside the basic plane is one letter', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('\u{1D431}', 5, 8);
    return [p.state(), refused('line two', 8)];
});

check('0050 fourth rule: an emoji the strip offers for the reopened word is not a letter and is applied', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('\u{1F600}', 5, 8);
    return [p.state(), applied('line \u{1F600}', 7)];
});

check('0050 fourth rule: a Backspace on a reopened word of two letters is applied', async () => {
    const p = page('ab', [2]);
    await p.compose();
    await p.update('a', 0, 2);
    return [p.state(), applied('a', 1)];
});

check('0050 fourth rule: one kanji for two kana is a conversion and is applied', async () => {
    const p = page('かな', [2]);
    await p.compose();
    await p.update('金', 0, 2);
    return [p.state(), applied('金', 1)];
});

check('0050 fifth rule: a remembered word written over text after the caret is refused', async () => {
    const p = page('alpha beta Charlie delta', [2]);
    await p.compose();
    await p.update('be', 6, 8, 2);
    await p.update('alpha', 6, 8, 2);
    return [p.state(), refused('alpha beta Charlie delta', 2)];
});

check('0050 sixth rule: the word written back grown, the caret kept before the growth, is refused', async () => {
    const p = page('line twox\nline two', [18]);
    await p.compose();
    await p.update('two', 5, 8, 8);
    await p.update('twox', 5, 8, 8);
    return [p.state(), { text: 'line twox\nline two', carets: [18], version: 1, restarts: 1, buffer: 'line two', bufferCaret: 8 }];
});

check('0050 seventh rule: a write that keeps the caret away from the end of what it writes is refused', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('two', 5, 8, 8);
    await p.update('line', 5, 8, 8);
    return [p.state(), refused('line two', 8)];
});

check('0050 seventh rule: what it costs, a correction of an earlier word that keeps the caret is refused', async () => {
    const p = page('teh quick', [9]);
    await p.compose();
    await p.update('the', 0, 3, 9);
    return [p.state(), refused('teh quick', 9)];
});

check('0050 seventh rule: a suggestion taken on the reopened word, the caret after its text, is applied', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('lin', 5, 8);
    return [p.state(), applied('line lin', 8)];
});

check('0050 eighth rule: the word inserted again at the caret, the caret kept before it, is refused', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('', 8, 8, 8);
    await p.update('two', 8, 8, 8);
    return [p.state(), refused('line two', 8)];
});

check('0050 eighth rule: a letter typed at the caret is applied', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('', 8, 8, 8);
    await p.update('x', 8, 8);
    return [p.state(), applied('line twox', 9)];
});

check('0050 eighth rule, known limit: the same insertion in a composition that has typed is applied', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 8, 8);
    await p.update('two', 9, 9, 9);
    return [p.state(), applied('line twoxtwo', 9)];
});

check('0050 ninth rule: a text from elsewhere written over the letter just typed, the caret kept, is refused', async () => {
    const p = page('abc line two', [12]);
    await p.compose();
    await p.update('x', 12, 12);
    await p.update('abc', 12, 13, 13);
    return [p.state(), { text: 'abc line twox', carets: [13], version: 2, restarts: 1, buffer: 'abc line twox', bufferCaret: 13 }];
});

check('0050 ninth rule: a second letter in the same composition is applied', async () => {
    const p = page('abc line two', [12]);
    await p.compose();
    await p.update('x', 12, 12);
    await p.update('xy', 12, 13);
    return [p.state(), applied('abc line twoxy', 14)];
});

check('0050 guard: a write that ends beyond the buffer is refused with no composition open', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    await p.update('twox', 74, 75, 9);
    return [p.state(), { text: 'above\nline twox\nbelow', carets: [15], version: 1, restarts: 1, buffer: 'line twox', bufferCaret: 9 }];
});

check('0050 guard: and in a composition that has typed', async () => {
    const p = page('above\nline two\nbelow', [14]);
    await p.compose();
    await p.update('x', 8, 8);
    await p.update('twox', 74, 75, 9);
    return [p.state(), { text: 'above\nline twox\nbelow', carets: [15], version: 2, restarts: 1, buffer: 'line twox', bufferCaret: 9 }];
});

check('0050 guard: a write over the last units of the buffer is not beyond it', async () => {
    const p = page('above\nline two\nbelow', [14]);
    await p.update('twox', 5, 8);
    return [p.state(), applied('above\nline twox\nbelow', 15)];
});

check('0050: a composition that starts has typed nothing, whatever the one before it typed', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 8, 8);
    await p.endComposition();
    await p.compose();
    await p.update('twox', 9, 9, 9);
    return [p.state(), { text: 'line twox', carets: [9], version: 2, restarts: 1 }];
});

check('0050: a refusal restarts input though the buffer holds what it held', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    await p.update('', 74, 75, 9);
    return [p.state(), { text: 'above\nline twox\nbelow', carets: [15], version: 1, restarts: 1 }];
});

check('0050: off Android nothing is refused', async () => {
    const p = new Page(desktop, 'line two', [8]);
    await p.compose();
    await p.update('two', 0, 4, 8);
    return [p.state(), { text: 'two two', restarts: 0 }];
});

check('0050: a refusal leaves every cursor where it was', async () => {
    const p = page('line two\nline two', [8, 17]);
    await p.compose();
    await p.update('two', 0, 4, 8);
    return [p.state(), { text: 'line two\nline two', carets: [8, 17], version: 1, restarts: 1 }];
});

check('0050 known limit: the letter written over one reopened letter is applied, as a Hangul step is', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 7, 8);
    return [p.state(), applied('line twx', 8)];
});

// --- 0050: what follows a refusal ---

// The reopened word written back short (second rule), refused, and input restarted.
async function tailDropRefused() {
    const p = page('alpha beta Charlie delta', [9]);
    await p.compose();
    await p.update('bet', 6, 10, 9);
    return p;
}

check('0050 second rule: the tail drop is refused and input restarts (the state the next cases stand on)', async () => {
    const p = await tailDropRefused();
    return [p.state(), refused('alpha beta Charlie delta', 9)];
});

// After the restart Gboard reads the text and the caret again: it opens the word at the caret
// with the word's own text and sends the next letter as an insertion at the caret. Recorded
// on an API 33 emulator with Gboard 12.4 (the tail drop sent to the editor while the keyboard
// held the word open, and the Tab below in 4 of 4 runs) and on an API 31 emulator with
// Gboard 10.8 (the Tab, 3 of 3 runs), with the letter 0.4 to 3 s after the refusal.
check('0050 after the restart, as recorded: the keyboard opens the word again with its own text and the letter lands at the caret', async () => {
    const p = await tailDropRefused();
    await p.compose();
    await p.update('beta', 6, 10, 9);
    await p.endComposition();
    await p.update('z', 9, 9);
    return [p.state(), applied('alpha betza Charlie delta', 10, 1)];
});

check('0050 after the restart, as recorded: the Tab inside a word, refused, and the letter typed after it', async () => {
    const p = page('WO  RD ONE', [4]);
    await p.compose();
    await p.update('WO', 0, 2, 4);
    await p.update('RD', 0, 2, 4);
    await p.compose();
    await p.repeat(2, 'RD', 4, 6, 4);
    await p.endComposition();
    await p.compose();
    await p.update('z', 4, 4);
    return [p.state(), applied('WO  zRD ONE', 5, 1)];
});

check('0050 after the restart, a keyboard that goes on with the range that was refused, in a new composition: its letter is applied', async () => {
    const p = await tailDropRefused();
    await p.compose();
    await p.update('betz', 6, 10);
    return [p.state(), applied('alpha betz Charlie delta', 10, 1)];
});

check('0050 after the restart, a keyboard that goes on with the range and opens no composition: the same letter is applied as well', async () => {
    const p = await tailDropRefused();
    await p.update('betz', 6, 10);
    return [p.state(), applied('alpha betz Charlie delta', 10, 1)];
});

check('0050: a refusal ends the composition it was made in, so the same stale write sent again with none open is applied', async () => {
    const p = await tailDropRefused();
    await p.update('bet', 6, 10, 9);
    return [p.state(), applied('alpha bet Charlie delta', 9, 1)];
});

check('0050: a refusal is of its own update only, so the letter a page script sends in the same task over the same range is applied', async () => {
    const p = page('alpha beta Charlie delta', [9]);
    await p.compose();
    await p.updatesInOneTask(['bet', 6, 10, 9], ['betz', 6, 10]);
    return [p.state(), applied('alpha betz Charlie delta', 10, 1)];
});

// --- 0050: the bound on forced restarts ---

const beyond = ['twox', 74, 75, 9];

check('0050 bound: six refusals for one version of the model, and the seventh update is applied, cut at the end of the buffer', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    await p.repeat(6, ...beyond);
    const sixth = p.state();
    await p.update(...beyond);
    return [{ sixth: [sixth.text, sixth.restarts], ...p.state() },
        { sixth: ['above\nline twox\nbelow', 6], text: 'above\nline twoxtwox\nbelow', carets: [15], restarts: 6 }];
});

check('0050 bound: past the bound a deletion that starts beyond the buffer edits nothing, and the text after the caret is not written again', async () => {
    const p = page('above\nline twox\nbelow', [11]);
    await p.repeat(7, '', 74, 75, 5);
    return [p.state(), { text: 'above\nline twox\nbelow', carets: [11], version: 1, restarts: 6 }];
});

check('0050 bound: the applied update moved the version, so the next one is refused again', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    await p.repeat(8, ...beyond);
    return [p.state(), { text: 'above\nline twoxtwox\nbelow', carets: [15], restarts: 7 }];
});

check('0050 bound: a letter typed gives the budget back', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    await p.repeat(6, ...beyond);
    await p.update('y', 9, 9);
    await p.repeat(6, ...beyond);
    return [p.state(), { text: 'above\nline twoxy\nbelow', carets: [16], restarts: 12 }];
});

check('0050 bound: an edit the editor makes gives the budget back', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    await p.repeat(6, ...beyond);
    await p.paste('P');
    await p.repeat(6, ...beyond);
    return [p.state(), { text: 'above\nline twoxP\nbelow', carets: [16], restarts: 12 }];
});

check('0050 bound: the budget is one for every shape, three refusals of one rule and three of another spend it', async () => {
    const p = page('line two', [8]);
    for (let i = 0; i < 3; i++) {
        await p.compose();
        await p.update('line', 5, 8, 8);
    }
    for (let i = 0; i < 3; i++) {
        await p.compose();
        await p.update('two', 8, 8, 8);
    }
    const sixth = p.state();
    await p.compose();
    await p.update('x', 5, 8);
    return [{ sixth: [sixth.text, sixth.restarts], ...p.state() }, { sixth: ['line two', 6], text: 'line x', carets: [6], restarts: 6 }];
});

check('0050 bound: refusals in one task make one restart and count once', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    await p.updatesInOneTask(beyond, beyond);
    await p.repeat(5, ...beyond);
    const sixth = p.state();
    await p.update(...beyond);
    return [{ sixth: [sixth.text, sixth.restarts], ...p.state() },
        { sixth: ['above\nline twox\nbelow', 6], text: 'above\nline twoxtwox\nbelow', restarts: 6 }];
});

check('0050 bound: a refusal with the focus elsewhere restarts nothing and counts for nothing', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    p.document.createElement('other').focus();
    await p.repeat(8, ...beyond);
    return [p.state(), { text: 'above\nline twox\nbelow', carets: [15], version: 1, restarts: 0 }];
});

check('0050 bound: an update the editor discards does not give the budget back', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    await p.repeat(3, ...beyond);
    p.readOnly = true;
    await p.update('y', 9, 9);
    p.readOnly = false;
    await p.repeat(4, ...beyond);
    return [p.state(), { text: 'above\nline twoxtwox\nbelow', restarts: 6 }];
});

// --- 0052: a touch handed to a row from outside the list ---

function listTap(pageX, pageY) {
    const calls = [];
    android.SuggestWidget.prototype._onListTap.call({
        _list: { getHTMLElement: () => ({ rect: { left: 10, top: 100, width: 200, height: 80 } }) },
        editor: { trigger: (...args) => calls.push(args) },
        _onListMouseDownOrTap: () => calls.push('row accepted'),
    }, { browserEvent: { pageX, pageY } });
    return calls;
}

check('0052: a touch inside the list accepts its row', async () => [listTap(50, 100), ['row accepted']]);
check('0052: a touch one pixel above the list hides the suggestions and accepts nothing', async () => [listTap(50, 99), [['touch', 'hideSuggestWidget', null]]]);
check('0052: a touch at the right edge is outside', async () => [listTap(210, 120), [['touch', 'hideSuggestWidget', null]]]);
check('0052: a touch at the bottom edge is outside', async () => [listTap(50, 180), [['touch', 'hideSuggestWidget', null]]]);

// --- 0053: an open list follows the composed word ---

function suggestModel(tree, listOpen) {
    const hooks = {};
    const hook = name => (listener) => { hooks[name] = listener; return standIn; };
    const editor = new Proxy({
        getSelection: () => null,
        onDidCompositionStart: hook('compositionStart'),
        onDidCompositionEnd: hook('compositionEnd'),
        onDidChangeModelContent: hook('content'),
    }, { get: (target, key) => key in target ? target[key] : standIn });
    const model = new tree.SuggestModel(editor, standIn, standIn, standIn, standIn, standIn, standIn, standIn, standIn);
    let refilters = 0;
    model._refilterCompletionItems = () => { refilters++; };
    model._triggerState = listOpen ? { auto: true } : undefined;
    return { hooks, refilters: () => refilters };
}

check('0053: a content change while a keyboard composes refilters an open list', async () => {
    const model = suggestModel(android, true);
    model.hooks.compositionStart();
    model.hooks.content();
    model.hooks.content();
    return [model.refilters(), 2];
});

check('0053: with no list open a composition update opens and refilters nothing', async () => {
    const model = suggestModel(android, false);
    model.hooks.compositionStart();
    model.hooks.content();
    return [model.refilters(), 0];
});

check('0053: off Android a composition still waits for its end', async () => {
    const model = suggestModel(desktop, true);
    model.hooks.compositionStart();
    model.hooks.content();
    return [model.refilters(), 0];
});

check('0053: with no composition an open list is refiltered as upstream does it', async () => {
    const model = suggestModel(android, true);
    model.hooks.content();
    return [model.refilters(), 1];
});

// A case that never settles leaves the event loop nothing to wait for, and Node then exits 0
// without the summary: the timer keeps the run alive and fails the case, and the exit check
// fails a run that ended before every case had been run.
const SETTLE_MS = 10000;

(async () => {
    let failed = 0;
    let ran = 0;
    process.on('exit', () => {
        if (ran !== cases.length) {
            console.error(`check-editcontext-ime.js: ${ran} of ${cases.length} cases ran`);
            process.exitCode = 1;
        }
    });
    for (const [name, play] of cases) {
        let got;
        let want;
        let timer;
        try {
            [got, want] = await Promise.race([play(), new Promise((resolve, reject) => {
                timer = setTimeout(() => reject(new Error(`the case did not settle within ${SETTLE_MS} ms`)), SETTLE_MS);
            })]);
            if (want !== null && typeof want === 'object' && !Array.isArray(want)) {
                got = Object.fromEntries(Object.keys(want).map(key => [key, got[key]]));
            }
        } catch (error) {
            got = `threw ${error.stack}`;
        } finally {
            clearTimeout(timer);
        }
        ran += 1;
        if (util.isDeepStrictEqual(got, want)) {
            console.log(`ok   ${name}`);
        } else {
            failed += 1;
            console.error(`FAIL ${name}\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`);
        }
    }
    console.log(`\n${cases.length - failed}/${cases.length} passed`);
    process.exit(failed ? 1 : 0);
})();
