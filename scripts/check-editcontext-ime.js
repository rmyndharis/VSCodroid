/**
 * Self-check for the editor's handling of a composing keyboard, patches 0046 to 0054.
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
 * which neither Gboard recorded did. So do the cases that send the next letter, a Backspace or a
 * correction over the range of a stale letter, and no recording holds a correction taken back
 * either, nor a step of a keyboard of another script on a reopened letter: those cases state what
 * the editor does if a keyboard sends that.
 *
 * 0052, 0053 and 0054 are reached without their widgets. `_onListTap` runs on an object
 * that has the three members it reads, and `SuggestModel` is built on an editor made of
 * its event hooks, with upstream's refilter replaced by a counter and the call that arms
 * a quick suggestion by another.
 *
 * NEGATIVE CONTROL, measured on the 1.139.1 tree with Node 22.23.2. Against upstream's
 * files, with no patch applied, 204 of the 223 cases fail. Each of these single edits to a
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
 *         `_isRememberedWordRewrite`, `_isTailDropOnReopen`,
 *         `_isUntypedWriteKeepingCaret`, `_isUntypedInsertKeepingCaret` and
 *         `_isTypedWriteKeepingCaret`; `_isInStepRewrite` answering false; each of the
 *         three exemptions removed (one unit over one unit, a doubled word, a Backspace on
 *         two letters); a letter counted in units, the one unit over one unit or the two letters of the
 *         Backspace counted in UTF-16 units; any one code point counted as a letter;
 *         Han, kana and Hangul counted as letters; the bound raised from six to seven; a
 *         forced restart not counted; the count not started again at a new version of the
 *         model; the wait of half a minute removed, or kept for a spent count only; the
 *         time of a restart not taken, or taken at every update that is judged;
 *         the count started again at every cursor event; the clamp past the bound removed, or only the cut of its start; the typed
 *         flag never set, not set by a stale letter, and not cleared at compositionstart; the restart after a
 *         refusal not forced, or forced past the bound for a stale letter; the buffer not written back at a refusal; the call of
 *         `_typeStaleLetter` removed, or its letter taken as the whole text of a grown word, or
 *         not typed past the bound, or its test of the model's version removed;
 *         `_isLetterOverLastLetter` taking any letter for a base letter, keeping the accents (with
 *         NFD or without it), keeping the case, taking a letter and its combining accent for one
 *         letter, judging two letters with the same base letter, without its guard for a
 *         composition that typed, or testing one of its two letters only;
 *         `_isGrowthOfEarlierWord` allowed to touch the caret, or to keep it, or without the caret asked
 *         after the written text; the version the count belongs to not taken over after a
 *         stale letter; the range of a stale letter not kept, or the next update over it taken without
 *         the model's version, the caret the letter left (or with a caret that is never the
 *         caret's), its start, its end, the caret it asks for collapsed (or tested at its end
 *         alone) and right after what it writes (or after one unit, or at the range's end), the
 *         range's own text before the letter or the letter
 *         test (or one that takes any text), or with a Backspace on a word of two letters
 *         taken for a letter, any text that starts the range's text taken for that Backspace,
 *         the two letters counted in UTF-16 units, or the test that the text starts the range's
 *         text removed; that Backspace told
 *         by the range's text when the letter was typed alone, by the text it holds now alone,
 *         or by the buffer's text after the update; the range's own text
 *         read from the buffer as it is when the next update comes, or as the letter left it;
 *         `_takesBackReplacement` not called, or without the model's version, the text
 *         replaced, the start, the buffer's text of the range, where the buffer starts (or
 *         that start compared by identity) or its test for a text that starts with what it
 *         replaced; where the buffer starts taken anew when a deletion or a space at the end of
 *         the last replacement is kept with it; the last replacement kept across a
 *         text written over its end, a deletion inside it, one that reaches before it, or an edit
 *         of the editor's own; a space inserted at its end not kept with it, or kept without its
 *         text added; the last replacement kept across a letter, a digit or a combining mark
 *         inserted at its end, a text written over what follows it, or a space inserted there
 *         after an edit of the editor's own.
 *   0051  `_caretUnmoved` answering false; its single-caret test removed; the count of
 *         carets not taken when the others are dropped.
 *   0052  the bounds test of `_onListTap` removed.
 *   0053  the Android branch of the content listener removed, and taken on every
 *         platform; `_refilterWhileComposing` going on with no list open.
 *   0054  the call in the cursor listener removed, and made on every platform, and made
 *         for a caret that typing did not move, for one a cursor key moved, and for a
 *         selection; the place of a move that is not followed not noted;
 *         `_composesTypedLetters` answering true; its test for a converted script
 *         removed, or for a full-width form alone; its test for a last letter removed,
 *         widened to modifier letters, or without the dollar sign; its combining marks
 *         after the letter removed, or a lone mark or one after a modifier letter taken.
 *
 * Two edits change no case, and no case could see them: the reset in `_afterAppliedEdit`
 * removed, which runs only when the model's version has moved, and the next update starts the
 * count again for that reason; and in `_afterAppliedEdit` the test that a text kept with the
 * last replacement is inserted at its end and not written over it, removed: a text written over
 * the end of what was kept is then added to it, while the buffer lost what it replaced, so the
 * kept text is longer than what the buffer holds there and no take-back can match it.
 */

'use strict';

// The clock the patched files read, `performance.now()`. It stands still, so that a case
// decides to the millisecond how long the keyboard was silent, and `pause` alone moves it on.
// A whole number, because the real clock's fraction makes a sum of thirty seconds come out a
// hair over or under and a case at the boundary then passes on some runs only.
const clock = { now: 1000000 };
performance.now = () => clock.now;

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

    // Time that passes with nothing from the keyboard.
    pause(ms) {
        clock.now += ms;
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

    // A Backspace the keyboard sends as a key press, as Gboard 17.7 and 18.4 do: the editor runs
    // it as its own command, one edit of the unit before each caret, and no update reaches the
    // edit context. What the key does to the buffer's start until it is released is not modeled.
    async backspaceKey() {
        const { Range, ReplaceCommand, CursorChangeReason } = this.tree;
        this.run(this.cursors.map(selection => {
            const caret = this.model.offsetAt(selection.getPosition());
            return new ReplaceCommand(Range.fromPositions(this.model.positionAt(caret - 1), this.model.positionAt(caret)), '');
        }), CursorChangeReason.NotSet);
        await this.settle();
    }

    // Simulates a deferred prepareRender scheduler: intercepts _updateEditContext calls originating
    // from model content or cursor listeners, queueing them (capturing or omitting the edit epoch)
    // until flushDeferredUpdates() runs, while leaving direct calls (such as refusal recovery) live.
    deferRenderUpdates({ captureEpoch = false } = {}) {
        const original = this.editContext._updateEditContext.bind(this.editContext);
        this._deferredQueue = [];
        this._inModelOrCursorEvent = false;
        const origApply = this.model.applyEdits.bind(this.model);
        this.model.applyEdits = (edits) => {
            this._inModelOrCursorEvent = true;
            try {
                return origApply(edits);
            } finally {
                this._inModelOrCursorEvent = false;
            }
        };
        const origCursorEvent = this.cursorEvent.bind(this);
        this.cursorEvent = (reason) => {
            this._inModelOrCursorEvent = true;
            try {
                return origCursorEvent(reason);
            } finally {
                this._inModelOrCursorEvent = false;
            }
        };
        this.editContext._updateEditContext = (initializing = false, expectedEpoch) => {
            if (!initializing && this._inModelOrCursorEvent) {
                const epoch = captureEpoch ? this.editContext._editContextEpoch : undefined;
                this._deferredQueue.push(() => original(false, epoch));
                return;
            }
            return original(initializing, expectedEpoch);
        };
    }

    async flushDeferredUpdates() {
        const queue = this._deferredQueue?.splice(0) ?? [];
        for (const run of queue) {
            run();
        }
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
// What a stale letter leaves: the letter typed at the caret as one edit, the buffer written back to it, and one restart more.
const typedAtCaret = (text, caret, restarts = 1) => ({ text, carets: [caret], version: 2, restarts, buffer: text, bufferCaret: caret });
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

check('0050 fourth rule: one letter written over a reopened word is typed at the caret', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    return [p.state(), typedAtCaret('line twox', 9)];
});

check('0050 fourth rule: the same with the caret inside the word', async () => {
    const p = page('alpha beta Charlie delta', [22]);
    await p.compose();
    await p.update('z', 19, 24);
    return [p.state(), typedAtCaret('alpha beta Charlie delzta', 23)];
});

check('0050 fourth rule: a letter outside the basic plane is one letter', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('\u{1D431}', 5, 8);
    return [p.state(), typedAtCaret('line two\u{1D431}', 10)];
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

check('0050 seventh rule: a remembered word written over text after the caret is refused', async () => {
    const p = page('alpha beta Charlie delta', [2]);
    await p.compose();
    await p.update('be', 6, 8, 2);
    await p.update('alpha', 6, 8, 2);
    return [p.state(), refused('alpha beta Charlie delta', 2)];
});

check('0050 seventh rule: the word written back grown, the caret kept before the growth, is refused', async () => {
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

check('0050 the letter written over the last letter of a reopened word is typed at the caret', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 7, 8);
    return [p.state(), typedAtCaret('line twox', 9)];
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
    return [{ sixth: [sixth.text, sixth.restarts], ...p.state() }, { sixth: ['line two', 6], text: 'line twox', carets: [9], restarts: 6, buffer: 'line twox', bufferCaret: 9 }];
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

check('0050 bound: more than half a minute with no forced restart gives the budget back, so refusals of separate moments do not add up', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    await p.repeat(6, ...beyond);
    p.pause(30001);
    await p.repeat(6, ...beyond);
    return [p.state(), { text: 'above\nline twox\nbelow', carets: [15], version: 1, restarts: 12 }];
});

// The wait ends any count, not only one that is spent: five refusals of one moment and one of another are not six.
check('0050 bound: the wait ends a count that was not spent', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    await p.repeat(5, ...beyond);
    p.pause(30001);
    await p.repeat(6, ...beyond);
    return [p.state(), { text: 'above\nline twox\nbelow', carets: [15], version: 1, restarts: 11 }];
});

// The time is taken at a counted restart and nowhere else, so an update that is judged and not refused does not carry a count across the wait.
check('0050 bound: an update that is not refused does not keep a count from ending', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    await p.repeat(5, ...beyond);
    p.pause(20000);
    p.readOnly = true;
    await p.update('y', 9, 9);
    p.readOnly = false;
    p.pause(20000);
    await p.repeat(6, ...beyond);
    return [p.state(), { text: 'above\nline twox\nbelow', carets: [15], version: 1, restarts: 11 }];
});

// A person who tries the same thing again does so within seconds, and those tries have to add up: the seventh is what lets a pick the rules took for a stale write through.
check('0050 bound: restarts half a minute apart and less add up to the bound', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    for (let i = 0; i < 6; i++) {
        await p.update(...beyond);
        p.pause(30000);
    }
    await p.update(...beyond);
    return [p.state(), { text: 'above\nline twoxtwox\nbelow', carets: [15], restarts: 6 }];
});

// The keyboard makes cursor events of its own, with its selection, so one cannot be what ends a count: a keyboard that loops would give the count back to itself.
check('0050 bound: a cursor event alone does not give the budget back, whatever its reason', async () => {
    const p = page('above\nline twox\nbelow', [15]);
    await p.repeat(6, ...beyond);
    await p.tap(14);
    await p.tap(15);
    await p.sameCursorAgain();
    await p.update(...beyond);
    return [p.state(), { text: 'above\nline twoxtwox\nbelow', carets: [15], restarts: 6 }];
});

// --- conditions of the rules that the cases above leave to the ones below ---

check('0046: after compositionend no rule judges an update that has no composition open', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.endComposition();
    await p.update('two', 0, 4, 8);
    return [p.state(), { text: 'two two', restarts: 0 }];
});

check('0050 fourth rule: a letter over a reopened word with the caret asked before it is applied', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 5, 8, 5);
    return [p.state(), { text: 'line x', carets: [5], restarts: 0 }];
});

check('0051: a caret removed with the primary unmoved moves the buffer to the caret line', async () => {
    const p = page('ab\ncd', [2, 5]);
    await p.update('\n', 2, 2);
    await p.tap(3);
    return [p.state(), { text: 'ab\n\ncd\n', carets: [3], buffer: '', bufferCaret: 0 }];
});

check('0049: a composition typed at every cursor exempts the same-length rewrite at the caret', async () => {
    const p = page('ab\ncb', [1, 4]);
    await p.compose();
    await p.update('ax', 0, 1);
    const mid = p.state();
    await p.update('xb', 0, 2, 2);
    return [{ mid: mid.text, ...p.state() }, { mid: 'axb\ncxb', text: 'xbb\ncxb', restarts: 0 }];
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
    // The caret's column on line 1 and the word that ends at it, which the cursor cases move.
    const at = { column: 1, word: '' };
    const selection = () => new tree.Selection(1, at.column, 1, at.column);
    const textModel = new Proxy({
        getWordUntilPosition: () => ({ word: at.word }),
    }, { get: (target, key) => key in target ? target[key] : standIn });
    const editor = new Proxy({
        getSelection: selection,
        getPosition: () => selection().getPosition(),
        hasModel: () => true,
        getModel: () => textModel,
        onDidCompositionStart: hook('compositionStart'),
        onDidCompositionEnd: hook('compositionEnd'),
        onDidChangeModelContent: hook('content'),
        onDidChangeCursorSelection: hook('cursor'),
    }, { get: (target, key) => key in target ? target[key] : standIn });
    const model = new tree.SuggestModel(editor, standIn, standIn, standIn, standIn, standIn, standIn, standIn, standIn);
    let refilters = 0;
    let armed = 0;
    model._refilterCompletionItems = () => { refilters++; };
    model._doTriggerQuickSuggest = () => { armed++; };
    model._triggerState = listOpen ? { auto: true } : undefined;
    // A caret move as the editor reports it: the word that now ends at the caret, the column, and who moved it.
    // With `anchor` it is a selection from that column to the caret.
    const caret = (word, column, source = 'keyboard', reason = tree.CursorChangeReason.NotSet, anchor = column) => {
        at.word = word;
        at.column = column;
        hooks.cursor({ selection: new tree.Selection(1, anchor, 1, column), reason, source });
    };
    return { hooks, caret, refilters: () => refilters, armed: () => armed, open: () => model._triggerState !== undefined };
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

// --- 0054: a word typed while a keyboard composes arms a quick suggestion ---

check('0054: a letter typed into a composing word arms a quick suggestion, and so does the next one', async () => {
    const model = suggestModel(android, false);
    model.hooks.compositionStart();
    model.caret('a', 2);
    model.caret('al', 3);
    return [model.armed(), 2];
});

check('0054: off Android a composing word arms nothing before it ends', async () => {
    const model = suggestModel(desktop, false);
    model.hooks.compositionStart();
    model.caret('a', 2);
    return [model.armed(), 0];
});

check('0054: a deletion inside a composing word arms nothing', async () => {
    const model = suggestModel(android, false);
    model.hooks.compositionStart();
    model.caret('alp', 4);
    model.caret('al', 3);
    return [model.armed(), 1];
});

// Outside a composition a caret that anything but typing moves ends an open list, and a cursor key refilters it. Inside one it is left as it is, as before this patch: the keyboard moves carets and sets selections of its own.
check('0054: a caret that typing did not move arms nothing, and leaves an open list open and as it was', async () => {
    const model = suggestModel(android, true);
    model.hooks.compositionStart();
    model.caret('alp', 4, 'api');
    model.caret('alp', 5, 'keyboard', android.CursorChangeReason.Explicit);
    model.caret('alp', 4, 'keyboard', android.CursorChangeReason.NotSet, 1);
    return [{ armed: model.armed(), open: model.open(), refilters: model.refilters() }, { armed: 0, open: true, refilters: 0 }];
});

// The place of a move that is not followed is still noted. Left at the place of the last followed move, the Backspace here read as a move to the right.
check('0054: a Backspace over an apostrophe arms nothing', async () => {
    const model = suggestModel(android, false);
    model.hooks.compositionStart();
    model.caret('don', 4);
    model.caret('', 5);
    model.caret('don', 4);
    return [model.armed(), 1];
});

check('0054: a letter typed after the keyboard moved the caret back arms a suggestion', async () => {
    const model = suggestModel(android, false);
    model.hooks.compositionStart();
    model.caret('alph', 5);
    model.caret('a', 2, 'keyboard', android.CursorChangeReason.Explicit);
    model.caret('ax', 3);
    return [model.armed(), 2];
});

check('0054: with a list open a typed letter arms no second one', async () => {
    const model = suggestModel(android, true);
    model.hooks.compositionStart();
    model.caret('a', 2);
    return [model.armed(), 0];
});

// The full-width k is what Gboard's Japanese keyboard holds while it waits for the vowel of ka (recorded on API 33, Gboard 12.4).
check('0054: kana, a Hangul syllable, a Han character, bopomofo and a full-width letter arm nothing, alone or after Latin letters', async () => {
    const model = suggestModel(android, false);
    model.hooks.compositionStart();
    let column = 1;
    for (const word of ['\u304b', '\u30ab', '\ud55c', '\u6f22', '\u3105', '\uff4b', 'ab\u304b', '\u6f22a', '\u3051\uff52']) {
        model.caret(word, ++column);
    }
    return [model.armed(), 0];
});

// U+02C6, U+02C7 and U+02CB are what Android's key character map shows for a dead circumflex, caron and grave, and Unicode files them as letters.
check('0054: a lone accent of a dead key arms nothing, a symbol or a modifier letter', async () => {
    const model = suggestModel(android, false);
    model.hooks.compositionStart();
    let column = 1;
    for (const word of ['\u00b4', 'a\u00a8', '\u02c6', '\u02c7', '\u02cb', '\u02ca', '\u30fc']) {
        model.caret(word, ++column);
    }
    return [model.armed(), 0];
});

// A mark that follows its letter belongs to the word: a Thai syllable with a tone, a Devanagari consonant with a vowel sign
// and a letter followed by a combining accent are typed and armed like a letter. Recorded on Gboard 12.4, API 33: Thai
// is composed word by word, and with the list closed the key of a tone or a vowel sign opened nothing.
check('0054: a word that ends in a combining mark arms, as one that ends in its letter does', async () => {
    const model = suggestModel(android, false);
    model.hooks.compositionStart();
    model.caret('e\u0301', 3);
    model.caret('\u0e01\u0e48', 5);
    const atMarks = model.armed();
    model.caret('\u0e01\u0e48\u0e01', 6);
    return [[atMarks, model.armed()], [2, 3]];
});

check('0054: a lone combining mark, one after a modifier letter and one after a converted script arm nothing', async () => {
    const model = suggestModel(android, false);
    model.hooks.compositionStart();
    let column = 1;
    for (const word of ['\u0301', '\u02c6\u0301', '\u304b\u3099']) {
        model.caret(word, ++column);
    }
    return [model.armed(), 0];
});

check('0054: a letter with a diacritic, a Cyrillic letter, a digit after a letter, an underscore and a dollar sign arm one each', async () => {
    const model = suggestModel(android, false);
    model.hooks.compositionStart();
    let column = 1;
    for (const word of ['\u00e9', '\u0436', 'a1', '_', '$']) {
        model.caret(word, ++column);
    }
    return [model.armed(), 5];
});

// A letter outside the basic plane is one unit to a person and two UTF-16 units to the string, and the two
// places that tell one unit from two counted the string. A Backspace on a reopened word of two such letters
// was refused and restarted input, six times, before the seventh was applied.
check('0050 a Backspace on a reopened two-letter word outside the basic plane is applied, as its basic-plane twin is', async () => {
    const p = page('\u{1E900}\u{1E901}', [4]);
    await p.compose();
    await p.update('\u{1E900}', 0, 4);
    return [p.state(), applied('\u{1E900}', 2)];
});
check('0050 the same where only the second letter is outside the basic plane', async () => {
    const p = page('a\u{1D431}', [3]);
    await p.compose();
    await p.update('a', 0, 3);
    return [p.state(), applied('a', 1)];
});
check('0050 the same after other text, for two Osage letters at the end of the line', async () => {
    const p = page('word \u{104B0}\u{104B1}', [9]);
    await p.compose();
    await p.update('\u{104B0}', 5, 9);
    return [p.state(), applied('word \u{104B0}', 7)];
});
check('0050 the first Backspace on such a word is applied, not the seventh', async () => {
    const p = page('\u{1E900}\u{1E901}', [4]);
    const seen = [];
    for (let i = 0; i < 7; i++) {
        await p.compose();
        await p.update('\u{1E900}', 0, 4);
        seen.push(p.state().text === '\u{1E900}' ? 'applied' : 'refused');
        if (p.state().text === '\u{1E900}') break;
    }
    return [seen, ['applied']];
});
check('0050 one letter outside the basic plane written over one, beside the same letter, is applied', async () => {
    const p = page('\u{1E900}\u{1E901}', [2]);
    await p.compose();
    await p.update('\u{1E901}', 0, 2, 2);
    return [p.state(), applied('\u{1E901}\u{1E901}', 2)];
});

// Conditions of the rules that no case above depended on. Each case below fails when its condition is
// loosened or removed, which is how they were found: an edit to one condition at a time, run against the
// cases that were here before.
check('0050 a word typed in front of the same word grows and moves the caret, and is applied', async () => {
    const p = page('the', [0]);
    await p.compose();
    await p.update('t', 0, 0);
    await p.update('th', 0, 1);
    await p.update('the', 0, 2);
    return [p.state(), applied('thethe', 3)];
});
check('0050 an untyped write of a text of the same length over the range that ends at the caret is refused', async () => {
    const p = page('abcd', [2]);
    await p.compose();
    await p.update('cd', 0, 2, 2);
    return [p.state(), refused('abcd', 2)];
});
check('0050 a longer suggestion taken on a reopened word is applied', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('two', 5, 8, 8);
    await p.update('twos', 5, 8);
    return [p.state(), applied('line twos', 9)];
});
check('0050 one letter over a reopened word that starts with that letter is typed at the caret', async () => {
    const p = page('line xyz', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    return [p.state(), typedAtCaret('line xyzx', 9)];
});
check('0050 in a composition that typed, one letter over an earlier longer range is refused', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 8, 8);
    await p.update('x', 0, 4, 9);
    return [p.state(), { text: 'line twox', carets: [9], version: 2, restarts: 1 }];
});
check('0050 the one-letter-over-a-word shape with no composition open is applied', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.endComposition();
    await p.update('x', 5, 8);
    return [p.state(), applied('line x', 6)];
});
check('0050 one digit over a reopened word is typed at the caret', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('7', 5, 8);
    return [p.state(), typedAtCaret('line two7', 9)];
});
check('0050 after a typed composition has ended, the write the ninth rule refuses is applied', async () => {
    const p = page('abc line two', [12]);
    await p.compose();
    await p.update('x', 12, 12);
    await p.endComposition();
    await p.update('abc', 12, 13, 13);
    return [p.state(), applied('abc line twoabc', 13)];
});
check('0050 with no composition open, an insertion that keeps the caret is applied', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.endComposition();
    await p.update('two', 8, 8, 8);
    return [p.state(), applied('line twotwo', 8)];
});
check('0050 a tail drop whose word is a multiple of the text without being its repetition is refused', async () => {
    const p = page('hell', [2]);
    await p.compose();
    await p.update('he', 0, 4, 2);
    return [p.state(), refused('hell', 2)];
});
check('0050 a write over a range around the caret whose text is not the part before the caret is applied', async () => {
    const p = page('alpha beta Charlie', [9]);
    await p.compose();
    await p.update('bxt', 6, 10, 9);
    return [p.state(), applied('alpha bxt Charlie', 9)];
});
check('0050 in a composition that has typed, the tail-drop shape is not judged by the second rule', async () => {
    const p = page('alpha beta Charlie', [9]);
    await p.compose();
    await p.update('x', 9, 9);
    await p.update('betx', 6, 11, 10);
    return [p.state(), applied('alpha betx Charlie', 10)];
});
check('0050 one letter over the word that starts at the caret is applied', async () => {
    const p = page('line two', [5]);
    await p.compose();
    await p.update('x', 5, 8);
    return [p.state(), applied('line x', 6)];
});
check('0050 the write the ninth rule refuses is refused over a range that starts at the beginning of the buffer', async () => {
    const p = page('two', [3]);
    await p.compose();
    await p.update('x', 3, 3);
    await p.update('ab', 0, 4, 4);
    return [p.state(), { text: 'twox', carets: [4], version: 2, restarts: 1 }];
});
check('0050 one kana, katakana or Hangul syllable over a reopened word of three is applied', async () => {
    const out = [];
    for (const [w, l] of [['あいう', 'か'], ['アイウ', 'カ'], ['가나다', '각']]) {
        const p = page(w, [3]);
        await p.compose();
        await p.update(l, 0, 3);
        out.push(p.state().text);
    }
    return [out, ['か', 'カ', '각']];
});
check('0050 off Android no rule refuses a write', async () => {
    const runs = [];
    const d = (text, carets) => new Page(desktop, text, carets);
    let p = d('line twzox', [8]); await p.compose(); await p.update('twz', 5, 10, 8); runs.push(p.state().restarts);
    p = d('line two', [8]); await p.compose(); await p.update('x', 5, 8); runs.push(p.state().restarts);
    p = d('alpha beta Charlie delta', [2]); await p.compose(); await p.update('alpha', 6, 8, 2); runs.push(p.state().restarts);
    p = d('line twox\nline two', [18]); await p.compose(); await p.update('two', 5, 8, 8); await p.update('twox', 5, 8, 8); runs.push(p.state().restarts);
    p = d('line two', [8]); await p.compose(); await p.update('two', 8, 8, 8); runs.push(p.state().restarts);
    p = d('abc line two', [12]); await p.compose(); await p.update('x', 12, 12); await p.update('abc', 12, 13, 13); runs.push(p.state().restarts);
    p = d('above\nline twox\nbelow', [15]); await p.update('twox', 74, 75, 9); runs.push(p.state().restarts);
    return [runs, [0, 0, 0, 0, 0, 0, 0]];
});
check('0050 off Android an update beyond the buffer is clamped to it, not refused', async () => {
    const p = new Page(desktop, 'above\nline twox\nbelow', [15]);
    await p.update('twox', 74, 75, 9);
    return [p.state().text, 'above\nline twoxtwox'];
});
check('0050 the tail drop of the first word of the buffer is refused', async () => {
    const p = page('twzox line', [3]);
    await p.compose();
    await p.update('twz', 0, 5, 3);
    return [p.state(), refused('twzox line', 3)];
});
check('0050 one letter over the reopened first word of the buffer is typed at the caret', async () => {
    const p = page('two', [3]);
    await p.compose();
    await p.update('x', 0, 3);
    return [p.state(), typedAtCaret('twox', 4)];
});
check('0050 recorded on Gboard 10.8, API 31: the letter over the whole reopened word is typed at the caret', async () => {
    const p = page('toa tob to', [10]);
    await p.compose();
    await p.update('c', 8, 10);
    return [p.state(), typedAtCaret('toa tob toc', 11)];
});
check('0050 recorded on Gboard 10.8, API 31: the letter over the last letter of the reopened word is typed at the caret', async () => {
    const p = page('toe to tog to', [13]);
    await p.compose();
    await p.update('h', 12, 13, 13);
    return [p.state(), typedAtCaret('toe to tog toh', 14)];
});
check('0050 recorded on Gboard 10.8, API 31, and Gboard 12.4, API 33: an earlier word grown by the letter is typed at the caret', async () => {
    const p = page('tob to tod to', [13]);
    await p.compose();
    await p.update('toe', 4, 6);
    return [p.state(), typedAtCaret('tob to tod toe', 14)];
});
check('0050 a letter over the last letter in the other case is the same letter and is applied', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('O', 7, 8, 8);
    return [p.state(), applied('line twO', 8)];
});
check('0050 an uppercase letter over a different last letter is typed at the caret', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('X', 7, 8, 8);
    return [p.state(), typedAtCaret('line twoX', 9)];
});
check('0050 a digit over the last digit of a reopened number is applied', async () => {
    const p = page('line 21', [7]);
    await p.compose();
    await p.update('3', 6, 7, 7);
    return [p.state(), applied('line 23', 7)];
});
check('0050 a kana over the last kana of a reopened word is applied, as a 12-key keyboard toggles one', async () => {
    const p = page('\u3044', [1]);
    await p.compose();
    await p.update('\u3042', 0, 1, 1);
    return [p.state(), applied('\u3042', 1)];
});
check('0050 in a composition that typed, a letter over the last letter is applied', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('twos', 5, 8);
    await p.update('t', 8, 9, 9);
    return [p.state(), applied('line twot', 9)];
});
check('0050 an earlier word grown by a letter with the caret moved by the growth is a completion and is applied', async () => {
    const p = page('tob ther ', [9]);
    await p.compose();
    await p.update('there', 4, 8, 10);
    return [p.state(), applied('tob there ', 10)];
});
check('0050 a growth of the word under the caret is typing and is applied', async () => {
    const p = page('tob to', [6]);
    await p.compose();
    await p.update('toe', 4, 6);
    return [p.state(), applied('tob toe', 7)];
});
// Past the bound a stale letter is still typed at the caret, which is not the input that does nothing the bound is for, and no restart
// is forced for it: a keyboard that answers every restart with the same stale write is not asked to read the text again. What it
// costs: such a keyboard has the letter of one key typed seven times.
check('0050 bound: six stale letters are typed for one run of refusals, and the seventh is typed as well with no restart forced', async () => {
    const p = page('line two', [8]);
    for (let i = 0; i < 7; i++) {
        await p.compose();
        await p.update('x', 5, p.state().text.length);
    }
    return [p.state(), { text: 'line twoxxxxxxx', carets: [15], restarts: 6, buffer: 'line twoxxxxxxx', bufferCaret: 15 }];
});
check('0050 bound: the same with the stale letter sent over the same range after every restart', async () => {
    const p = page('line two', [8]);
    for (let i = 0; i < 7; i++) {
        await p.compose();
        await p.update('x', 5, 8);
    }
    return [p.state(), { text: 'line twoxxxxxxx', carets: [15], restarts: 6, buffer: 'line twoxxxxxxx', bufferCaret: 15 }];
});
check('0050 bound: past the bound a stale letter that the editor discards leaves the buffer as the model is', async () => {
    const p = page('line two', [8]);
    for (let i = 0; i < 3; i++) {
        await p.compose();
        await p.update('line', 5, 8, 8);
        await p.compose();
        await p.update('two', 8, 8, 8);
    }
    p.readOnly = true;
    await p.compose();
    await p.update('x', 5, 8);
    return [p.state(), { text: 'line two', carets: [8], version: 1, restarts: 6, buffer: 'line two', bufferCaret: 8 }];
});
// A keyboard that did not read the text again after a stale letter was typed for it goes on with the range it kept and sends
// the next letter over it the same way. Nothing else has changed since (the model's version is the one the letter left), and a
// keyboard that read the text again names the letter's place at the caret, never that range: so its letter is typed at the
// caret too, with a composition open or none.
check('0050 the next letter over the range of a stale letter, in a new composition, is typed at the caret as well', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    await p.compose();
    await p.update('y', 5, 8);
    return [p.state(), { text: 'line twoxy', carets: [10], version: 3, restarts: 2, buffer: 'line twoxy', bufferCaret: 10 }];
});
check('0050 the same with no composition open', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    await p.update('y', 5, 8);
    return [p.state(), { text: 'line twoxy', carets: [10], version: 3, restarts: 2, buffer: 'line twoxy', bufferCaret: 10 }];
});
check('0050 the same for the range of the last letter', async () => {
    const p = page('toe to tog to', [13]);
    await p.compose();
    await p.update('h', 12, 13, 13);
    await p.compose();
    await p.update('i', 12, 13, 13);
    return [p.state(), { text: 'toe to tog tohi', carets: [15], version: 3, restarts: 2 }];
});
check('0050 the same for the range of an earlier word grown by the letter, with no composition open', async () => {
    const p = page('tob to tod to', [13]);
    await p.compose();
    await p.update('toe', 4, 6);
    await p.update('tof', 4, 6);
    return [p.state(), { text: 'tob to tod toef', carets: [15], version: 3, restarts: 2 }];
});
check('0050 a third letter over the same range is typed at the caret as well', async () => {
    const p = page('line two', [8]);
    for (const letter of ['x', 'y', 'z']) {
        await p.compose();
        await p.update(letter, 5, 8);
    }
    return [p.state(), { text: 'line twoxyz', carets: [11], version: 4, restarts: 3 }];
});
// The model's version is the witness that the letter was typed: one that the editor discarded keeps no range.
check('0050 after a stale letter that the editor discarded, the next letter over its range is judged on its own', async () => {
    const p = page('line two', [8]);
    p.readOnly = true;
    await p.compose();
    await p.update('x', 5, 8);
    p.readOnly = false;
    await p.update('y', 5, 8);
    return [p.state(), applied('line y', 6, 1)];
});
check('0050 the next letter over that range is typed at the caret after the buffer moved to the caret line', async () => {
    const p = page('line one', [8]);
    await p.update('\n', 8, 8);
    await p.update('two', 9, 9);
    const before = p.state().buffer;
    await p.compose();
    await p.update('x', 9, 12);
    await p.compose();
    await p.update('y', 9, 12);
    return [{ before, ...p.state() }, { before: 'line one\ntwo', text: 'line one\ntwoxy', carets: [14], restarts: 2 }];
});
check('0050 the next letter over the range of an earlier word grown by the letter is typed at the caret after the buffer moved to the caret line', async () => {
    const p = page('line one', [8]);
    await p.update('\n', 8, 8);
    await p.update('tob to tod to', 9, 9);
    const before = p.state().buffer;
    await p.compose();
    await p.update('toe', 13, 15);
    await p.compose();
    await p.update('tof', 13, 15);
    return [{ before, ...p.state() }, { before: 'line one\ntob to tod to', text: 'line one\ntob to tod toef', carets: [24], restarts: 2 }];
});
check('0050 after a stale letter, the range named again with its own text types nothing', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    await p.compose();
    await p.update('two', 5, 8, 8);
    return [p.state(), { text: 'line twox', version: 2, restarts: 1 }];
});
check('0050 after a stale letter, two letters over its range are judged on their own', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    await p.compose();
    await p.update('xy', 5, 8);
    return [p.state(), { text: 'line xyx', carets: [7], version: 3, restarts: 1 }];
});
check('0050 after a stale letter, a letter over another range that ends where it ended is judged on its own', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    await p.compose();
    await p.update('y', 7, 8);
    return [p.state(), applied('line twyx', 8, 1)];
});
check('0050 after a stale letter, a keyboard that read the text again opens the word at the caret and its letter is applied', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    await p.compose();
    await p.update('twox', 5, 9, 9);
    await p.update('twoxy', 5, 9);
    return [p.state(), applied('line twoxy', 10, 1)];
});
check('0050 after a stale letter, a letter over that range once the model has changed is judged on its own', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    await p.update('w', 9, 9);
    await p.update('y', 5, 8);
    return [p.state(), applied('line yxw', 6, 1)];
});
// The range is offsets in the buffer the keyboard last read, and a keyboard is told when the caret moves: while it is elsewhere, an
// update that names the range again comes from a keyboard that knows where the caret is, and the same offsets can name another
// word or another line. Nor is a Backspace on a word of two letters a letter, there as anywhere else in the patch.
check('0050 after a stale letter, a Backspace on the earlier word tapped at its end deletes a letter there', async () => {
    const p = page('tob to tod to', [13]);
    await p.compose();
    await p.update('toe', 4, 6);
    const before = p.state().text;
    await p.tap(6);
    await p.compose();
    await p.update('t', 4, 6, 5);
    return [{ before, ...p.state() }, { before: 'tob to tod toe', text: 'tob t tod toe', carets: [5], restarts: 1 }];
});
check('0050 after a stale letter, a Backspace on a word of two letters at the same offsets of another line deletes a letter there', async () => {
    const p = page('tob to tod to\n    if (x) {', [13]);
    await p.compose();
    await p.update('toe', 4, 6);
    await p.tap(21);
    await p.compose();
    await p.update('i', 4, 6, 5);
    return [p.state(), { text: 'tob to tod toe\n    i (x) {', carets: [20], restarts: 1 }];
});
check('0050 after a stale letter, a letter typed at the end of the earlier word tapped there is applied with no restart', async () => {
    const p = page('tob to tod to', [13]);
    await p.compose();
    await p.update('toe', 4, 6);
    await p.tap(6);
    await p.compose();
    await p.update('top', 4, 6);
    return [p.state(), { text: 'tob top tod toe', carets: [7], restarts: 1 }];
});
check('0050 after a stale letter, a word of one letter picked over the earlier word tapped at its end, with no composition open, is applied', async () => {
    const p = page('tob to tod to', [13]);
    await p.compose();
    await p.update('toe', 4, 6);
    await p.tap(6);
    await p.update('I', 4, 6);
    return [p.state(), { text: 'tob I tod toe', carets: [5], restarts: 1 }];
});
check('0050 after a stale letter over a word of two letters, a Backspace on the next line at the same offsets deletes a letter there', async () => {
    const p = page('go\nat', [2]);
    await p.compose();
    await p.update('x', 0, 2, 1);
    const before = p.state().text;
    await p.tap(6);
    await p.compose();
    await p.update('a', 0, 2, 1);
    return [{ before, ...p.state() }, { before: 'gox\nat', text: 'gox\na', carets: [5], restarts: 1 }];
});
check('0050 after a stale letter, a Backspace sent over the range of a word of two letters is applied as the deletion it is', async () => {
    const p = page('tob to tod to', [13]);
    await p.compose();
    await p.update('toe', 4, 6);
    await p.compose();
    await p.update('t', 4, 6, 5);
    return [p.state(), { text: 'tob t tod toe', carets: [5], restarts: 1 }];
});
check('0050 after a stale letter over a word of two letters outside the basic plane, a Backspace sent over its range is a deletion', async () => {
    const p = page('\u{104B0}\u{104B1}', [4]);
    await p.compose();
    await p.update('x', 0, 4, 1);
    await p.compose();
    await p.update('\u{104B0}', 0, 4, 2);
    return [p.state(), { text: '\u{104B0}x', carets: [2], restarts: 1 }];
});
check('0050 after a stale letter, the first letter of a longer word over its range is typed at the caret', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    await p.compose();
    await p.update('t', 5, 8);
    return [p.state(), { text: 'line twoxt', carets: [10], version: 3, restarts: 2 }];
});
check('0050 after a stale letter over a word of two letters, a letter that does not start the word is typed at the caret', async () => {
    const p = page('go', [2]);
    await p.compose();
    await p.update('x', 0, 2, 1);
    await p.compose();
    await p.update('y', 0, 2, 1);
    return [p.state(), { text: 'goxy', carets: [4], version: 3, restarts: 2 }];
});
check('0050 after a stale letter, a letter over that range once an edit elsewhere has changed the model is judged on its own, the caret back where it was', async () => {
    const p = page('line two\nabc', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    await p.tap(13);
    await p.paste('!');
    await p.tap(9);
    await p.compose();
    await p.update('y', 5, 8);
    return [p.state(), applied('line yx\nabc!', 6, 1)];
});
// A keyboard that goes on with the range sends the next letter the way it sent the stale one, with the caret asked right after
// what it writes. An update of the same range that asks for the caret anywhere else is not that letter: a correction of the
// earlier word asks for the caret moved by the change in length, and is applied as the honest update it is.
check('0050 after a stale letter, a correction of the earlier word over its range, the caret moved by the change in length, is applied', async () => {
    const p = page('tob to tod to', [13]);
    await p.compose();
    await p.update('toe', 4, 6);
    await p.compose();
    await p.update('top', 4, 6, 15);
    return [p.state(), { text: 'tob top tod toe', carets: [15], version: 3, restarts: 1 }];
});
check('0050 the same with a correction of one letter', async () => {
    const p = page('tob to tod to', [13]);
    await p.compose();
    await p.update('toe', 4, 6);
    await p.compose();
    await p.update('I', 4, 6, 13);
    return [p.state(), { text: 'tob I tod toe', carets: [13], version: 3, restarts: 1 }];
});
check('0050 the same correction with no composition open', async () => {
    const p = page('tob to tod to', [13]);
    await p.compose();
    await p.update('toe', 4, 6);
    await p.update('top', 4, 6, 15);
    return [p.state(), { text: 'tob top tod toe', carets: [15], version: 3, restarts: 1 }];
});
check('0050 after a stale letter, an update over its range that asks for a selection is judged on its own', async () => {
    const states = [];
    for (const selection of [[4, 5], [5, 6]]) {
        const p = page('tob to tod to', [13]);
        await p.compose();
        await p.update('toe', 4, 6);
        await p.compose();
        await p.update('I', 4, 6, ...selection);
        const { text, carets, restarts } = p.state();
        states.push({ text, carets, restarts });
    }
    return [states, [{ text: 'tob I tod toe', carets: [13], restarts: 1 }, { text: 'tob I tod toe', carets: [13], restarts: 1 }]];
});
check('0050 after a stale letter, the caret moved away and back with no edit, the next letter over its range is typed at the caret', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('x', 5, 8);
    await p.tap(3);
    await p.tap(9);
    await p.compose();
    await p.update('y', 5, 8);
    return [p.state(), { text: 'line twoxy', carets: [10], version: 3, restarts: 2 }];
});
// The letter's own cursor event can start the buffer at the caret's line, and a keyboard that read the text again then names
// other text with the same offsets. A Backspace is told by the text the range holds now as well as by the text it held when the
// letter was typed, and a correction by the caret it asks for.
check('0050 after a stale letter that moved the buffer to the caret line, a Backspace on the word at the caret over the same offsets is applied', async () => {
    const p = page('xx to', [5]);
    await p.update('\n', 5, 5);
    await p.update('zz a', 6, 6);
    const before = p.state().buffer;
    await p.compose();
    await p.update('toe', 3, 5, 6);
    const moved = p.state().buffer;
    await p.compose();
    await p.update('a', 3, 5, 4);
    return [{ before, moved, ...p.state() }, { before: 'xx to\nzz a', moved: 'zz ae', text: 'xx to\nzz a', carets: [10], version: 5, restarts: 1 }];
});
check('0050 the same with a correction of a word at the same offsets, the caret moved by the change in length', async () => {
    const p = page('to', [2]);
    await p.update('\n', 2, 2);
    await p.update('ab two', 3, 3);
    await p.compose();
    await p.update('toe', 0, 2, 3);
    const moved = p.state().buffer;
    await p.compose();
    await p.update('i', 0, 2, 6);
    return [{ moved, ...p.state() }, { moved: 'ab twoe', text: 'to\ni twoe', carets: [9], restarts: 1 }];
});
check('0050 after a stale letter that moved the buffer, a Backspace on its word of two letters over the offsets the keyboard kept is not typed as a letter', async () => {
    const p = page('line one', [8]);
    await p.update('\n', 8, 8);
    await p.update('go', 9, 9);
    await p.compose();
    await p.update('x', 9, 11, 10);
    const moved = p.state().buffer;
    await p.compose();
    await p.update('g', 9, 11, 10);
    return [{ moved, ...p.state() }, { moved: 'gox', text: 'line one\ngox', carets: [12], restarts: 2 }];
});
// What that costs: a next letter sent over the old offsets whose text starts the two letters the buffer holds there now is taken
// for that Backspace and judged on its own, so it is applied there: the letter typed before it is deleted and its own is not typed.
check('0050 known limit: after a stale letter that moved the buffer, a next letter that starts the two letters at the old offsets is taken for a Backspace', async () => {
    const p = page('', [0]);
    await p.update('\n', 0, 0);
    await p.update('ab', 1, 1);
    await p.compose();
    await p.update('x', 1, 3, 2);
    const moved = p.state().buffer;
    await p.compose();
    await p.update('b', 1, 3, 2);
    return [{ moved, ...p.state() }, { moved: 'abx', text: '\nab', carets: [3], restarts: 1 }];
});
// A keyboard that commits the word before a single space asks for the caret where it is, after the space: Chromium puts the
// caret at the end of a commit. The stale growth moves the caret back into the earlier word.
check('0050 a word before a space grown by a letter, the caret asked where it is, is applied', async () => {
    const p = page('the ', [4]);
    await p.compose();
    await p.update('then', 0, 3, 4);
    return [p.state(), applied('then ', 4)];
});
check('0050 the same for a word of two letters grown by its own last letter', async () => {
    const p = page('go ', [3]);
    await p.compose();
    await p.update('goo', 0, 2, 3);
    return [p.state(), applied('goo ', 3)];
});
// What that costs: a stale growth of the word right before a single space asks for the caret where it is too, and goes into
// that word, where the editor without the rule typed the letter at the caret.
check('0050 known limit: a stale growth of the word right before a single space goes into that word', async () => {
    const p = page('to to ', [6]);
    await p.compose();
    await p.update('toe', 3, 5, 6);
    return [p.state(), applied('to toe ', 6)];
});
// The keyboard took back a correction the editor applied for it: u corrected to you and a space, the space deleted, and the
// word reopened and written back as u with the caret after it. That is the one-letter-over-a-word shape, and it is the exact
// undo of the last replacement the page applied, with nothing but that deletion since.
check('0050 a correction taken back after its space was deleted is applied', async () => {
    const p = page('', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you ', 0, 1);
    await p.update('', 3, 4);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), applied('u', 1, 0)];
});
check('0050 a correction taken back at once is applied', async () => {
    const p = page('', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you', 0, 1);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), applied('u', 1, 0)];
});
check('0050 another letter over the corrected word is a stale letter and is typed at the caret', async () => {
    const p = page('', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you ', 0, 1);
    await p.update('', 3, 4);
    await p.compose();
    await p.update('x', 0, 3);
    return [p.state(), { text: 'youx', carets: [4], restarts: 1 }];
});
check('0050 the take-back after another edit of the model is a stale letter and is typed at the caret', async () => {
    const p = page('', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you', 0, 1);
    await p.update('s', 3, 3);
    await p.update('', 3, 4);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'youu', carets: [4], restarts: 1 }];
});
// A word completed or grown as it is typed starts with what it replaced, and is no correction to take back: the first letter
// typed over it after a turn is a stale letter.
check('0050 a stale letter over a word completed from its first letter is typed at the caret', async () => {
    const p = page('line ', [5]);
    await p.compose();
    await p.update('t', 5, 5);
    await p.update('the', 5, 6);
    await p.compose();
    await p.update('t', 5, 8);
    return [p.state(), { text: 'line thet', carets: [9], restarts: 1 }];
});
check('0050 the same after the space of the completion was deleted', async () => {
    const p = page('line ', [5]);
    await p.compose();
    await p.update('t', 5, 5);
    await p.update('the ', 5, 6);
    await p.endComposition();
    await p.update('', 8, 9);
    await p.compose();
    await p.update('t', 5, 8);
    return [p.state(), { text: 'line thet', carets: [9], restarts: 1 }];
});
check('0050 the take-back after an edit the editor made elsewhere is a stale letter and is typed at the caret', async () => {
    const p = page('\nabc', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you', 0, 1);
    await p.tap(7);
    await p.paste('!');
    await p.tap(3);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'youu\nabc!', carets: [4] }];
});
check('0050 the same after an edit the editor made elsewhere and the space deleted', async () => {
    const p = page('\nabc', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you ', 0, 1);
    await p.tap(8);
    await p.paste('!');
    await p.tap(4);
    await p.update('', 3, 4);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'youu\nabc!', carets: [4] }];
});
check('0050 the take-back shape on another line, where the buffer holds other text, is a stale letter and is typed at the caret', async () => {
    const p = page('\nabc', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you', 0, 1);
    await p.tap(7);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'you\nabcu', carets: [8] }];
});
// A tap or a cursor key that starts the buffer at another line moves no version, and the same offsets then name that line: where
// it holds the corrected word as well, the update has every mark of the take-back but the buffer it was named in.
check('0050 the take-back shape on another line that holds the corrected word is a stale letter and is typed at the caret', async () => {
    const p = page('\nyou', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you', 0, 1);
    await p.tap(7);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'you\nyouu', carets: [8], restarts: 1 }];
});
check('0050 the same after the space of the correction was deleted', async () => {
    const p = page('\nyou', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you ', 0, 1);
    await p.update('', 3, 4);
    await p.tap(7);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'you\nyouu', carets: [8], restarts: 1 }];
});
// A Backspace on that line, at the offsets where the correction ended, shortens what is kept of the correction as it would on the
// correction's own line, and what is kept still holds the start of the buffer the correction was made in. So does a space there.
check('0050 the take-back shape on another line, after a Backspace there where the correction ended, is a stale letter and is typed at the caret', async () => {
    const p = page('\nyou ', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you ', 0, 1);
    await p.tap(9);
    await p.update('', 3, 4);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'you \nyouu', carets: [9], restarts: 1 }];
});
check('0050 the same with a space typed on that line where the correction ended and then deleted', async () => {
    const p = page('\nyou', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you', 0, 1);
    await p.tap(7);
    await p.update(' ', 3, 3);
    await p.update('', 3, 4);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'you\nyouu', carets: [8], restarts: 1 }];
});
// The start is compared, not followed: back on the line of the correction with no edit between, the buffer starts where it did.
check('0050 a correction taken back after the caret went to another line and back is applied', async () => {
    const p = page('\nyou', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you', 0, 1);
    await p.tap(7);
    await p.tap(3);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), applied('u\nyou', 1, 0)];
});
check('0050 the take-back shape over another copy of the corrected word is a stale letter and is typed at the caret', async () => {
    const p = page('you ', [4]);
    await p.compose();
    await p.update('u', 4, 4);
    await p.endComposition();
    await p.update('you', 4, 5);
    await p.tap(3);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'youu you', carets: [4], restarts: 1 }];
});
check('0050 a text written over the space after a correction ends what is kept of the correction', async () => {
    const p = page('', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you ', 0, 1);
    await p.update('x', 3, 4, 3);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'youux', carets: [4], restarts: 1 }];
});
check('0050 a deletion inside the corrected text ends what is kept of the correction', async () => {
    const p = page('', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you ', 0, 1);
    await p.update('', 1, 2);
    await p.compose();
    await p.update('u', 0, 1, 1);
    return [p.state(), { text: 'yuu ', carets: [2], restarts: 1 }];
});
check('0050 a deletion that reaches before the corrected text ends what is kept of the correction', async () => {
    const p = page('aqzx', [2]);
    await p.update('xy', 1, 2);
    await p.update('', 0, 3);
    await p.tap(2);
    await p.compose();
    await p.update('q', 1, 2, 2);
    return [p.state(), { text: 'zxq', carets: [3], restarts: 1 }];
});
// A keyboard can send the correction and its space as two updates. The space, inserted at the end of the corrected text,
// is kept with it, so the Backspace on the space and the take-back after it read as they do when both came in one update.
check('0050 a correction taken back after its space, sent as an update of its own, was deleted is applied', async () => {
    const p = page('', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you', 0, 1);
    await p.update(' ', 3, 3);
    await p.update('', 3, 4);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), applied('u', 1, 0)];
});
// A letter, a digit or a mark typed at the end of the correction goes on with the word, and is no space or punctuation sent
// with it: the case of the s above, and these two.
for (const [name, typed] of [['a digit', '2'], ['a combining mark', '\u0301']]) {
    check(`0050 ${name} typed at the end of a correction ends what is kept of the correction`, async () => {
        const p = page('', [0]);
        await p.compose();
        await p.update('u', 0, 0);
        await p.endComposition();
        await p.update('you', 0, 1);
        await p.update(typed, 3, 3);
        await p.update('', 3, 4);
        await p.compose();
        await p.update('u', 0, 3);
        return [p.state(), { text: 'youu', carets: [4], restarts: 1 }];
    });
}
check('0050 a text written over what follows a correction ends what is kept of the correction', async () => {
    const p = page(' abc', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you', 0, 1);
    await p.update('-', 3, 4);
    await p.update('', 3, 4);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'youuabc', carets: [4], restarts: 1 }];
});
check('0050 the space of a correction sent after an edit the editor made elsewhere ends what is kept of the correction', async () => {
    const p = page('\nabc', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you', 0, 1);
    await p.tap(7);
    await p.paste('!');
    await p.tap(3);
    await p.update(' ', 3, 3);
    await p.update('', 3, 4);
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'youu\nabc!', carets: [4] }];
});
// What that costs: a Backspace that the editor runs as a key press is an edit of its own, which no update reports, so it ends
// what is kept, and the take-back after it is typed as a stale letter.
check('0050 known limit: the take-back after a Backspace sent as a key press is a stale letter and is typed at the caret', async () => {
    const p = page('', [0]);
    await p.compose();
    await p.update('u', 0, 0);
    await p.endComposition();
    await p.update('you ', 0, 1);
    await p.backspaceKey();
    await p.compose();
    await p.update('u', 0, 3);
    return [p.state(), { text: 'youu', carets: [4], restarts: 1 }];
});
// Two updates in one task, which only a script on the page can send: once the stale letter is typed the composition has typed,
// so a letter over the letter just typed is the rewrite of a composition in step and is applied.
check('0050 a stale letter typed at the caret counts as typed for the composition it was sent in', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.updatesInOneTask(['x', 5, 8], ['y', 8, 9, 9]);
    return [p.state(), { text: 'line twoy', carets: [9], restarts: 1 }];
});
// The letter over the last letter is a letter of the basic Latin alphabet, accented or not, compared by its base letter: case
// and the accents that NFD takes off are set aside, so an accent or a Telex step on the last letter (é over e, â over a) is
// applied, and a different letter is the stale write. A letter with a stroke (đ in a Vietnamese dd) is not judged, nor is a
// letter of another script: a step of its keyboard on a reopened letter would have the same shape, though none is recorded.
check('0050 an accented letter over a different last letter is typed at the caret', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('\u00E9', 7, 8, 8);
    return [p.state(), typedAtCaret('line two\u00E9', 9)];
});
check('0050 a Cyrillic letter over a different last letter is not judged and replaces it, as a Cyrillic step would', async () => {
    const p = page('\u0441\u043B\u043E\u0432\u043E', [5]);
    await p.compose();
    await p.update('\u0445', 4, 5, 5);
    return [p.state(), applied('\u0441\u043B\u043E\u0432\u0445', 5)];
});
check('0050 the same letter with an accent over the last letter is applied', async () => {
    const p = page('cafe', [4]);
    await p.compose();
    await p.update('\u00E9', 3, 4, 4);
    return [p.state(), applied('caf\u00E9', 4)];
});
check('0050 a digit over the last letter is not a letter and is applied', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('7', 7, 8, 8);
    return [p.state(), applied('line tw7', 8)];
});
check('0050 a letter over the last digit is applied', async () => {
    const p = page('line 21', [7]);
    await p.compose();
    await p.update('x', 6, 7, 7);
    return [p.state(), applied('line 2x', 7)];
});
check('0050 a Telex d with a stroke over the last letter d is applied', async () => {
    const p = page('d', [1]);
    await p.compose();
    await p.update('\u0111', 0, 1, 1);
    return [p.state(), applied('\u0111', 1)];
});
// Only a letter of the basic Latin alphabet, accented or not, is judged over the last letter. A step on a reopened letter of
// another script has the same shape: a transliteration, a phonetic or multi-tap keyboard, a final form, a dotless i.
check('0050 a Devanagari letter picked over a reopened Latin letter is applied', async () => {
    const p = page('k', [1]);
    await p.compose();
    await p.update('\u0915', 0, 1, 1);
    return [p.state(), applied('\u0915', 1)];
});
check('0050 a Bengali phonetic step over a reopened letter is applied', async () => {
    const p = page('\u0995', [1]);
    await p.compose();
    await p.update('\u0996', 0, 1, 1);
    return [p.state(), applied('\u0996', 1)];
});
check('0050 a Cyrillic multi-tap step over a reopened letter is applied', async () => {
    const p = page('\u0430', [1]);
    await p.compose();
    await p.update('\u0431', 0, 1, 1);
    return [p.state(), applied('\u0431', 1)];
});
check('0050 an Arabic teh marbuta over the last heh of a reopened word is applied', async () => {
    const p = page('\u0645\u062F\u0631\u0633\u0647', [5]);
    await p.compose();
    await p.update('\u0629', 4, 5, 5);
    return [p.state(), applied('\u0645\u062F\u0631\u0633\u0629', 5)];
});
check('0050 a Turkish dotless i over the last i of a reopened word is applied', async () => {
    const p = page('ki', [2]);
    await p.compose();
    await p.update('\u0131', 1, 2, 2);
    return [p.state(), applied('k\u0131', 2)];
});
check('0050 an i over the last dotless i of a reopened word is applied', async () => {
    const p = page('k\u0131', [2]);
    await p.compose();
    await p.update('i', 1, 2, 2);
    return [p.state(), applied('ki', 2)];
});
check('0050 an accented letter over a different accented last letter is typed at the caret', async () => {
    const p = page('caf\u00E9', [4]);
    await p.compose();
    await p.update('\u00F6', 3, 4, 4);
    return [p.state(), typedAtCaret('caf\u00E9\u00F6', 5)];
});
check('0050 a letter and a combining accent over the last letter are two code points and not judged as a letter', async () => {
    const p = page('line two', [8]);
    await p.compose();
    await p.update('e\u0301', 7, 8, 8);
    return [p.state(), refused('line two', 8)];
});
check('0050 a Hangul syllable over the last syllable of a reopened word is applied', async () => {
    const p = page('\uAC00\uB098', [2]);
    await p.compose();
    await p.update('\uB09C', 1, 2, 2);
    return [p.state(), applied('\uAC00\uB09C', 2)];
});
// What the stale letters cost: an honest pick of one letter over a reopened word has their shapes. Over a word of more letters
// it is the letter over the whole word, and over a word of one letter with another base letter it is the letter over the last
// letter, so the pick is typed after the word, and each try again is a stale letter as well, past the bound too.
check('0050 known limit: a one-letter pick over a reopened word of two letters is typed after the word', async () => {
    const p = page('is', [2]);
    await p.compose();
    await p.update('I', 0, 2);
    return [p.state(), typedAtCaret('isI', 3)];
});
check('0050 known limit: the same pick tried seven times is typed after the word seven times', async () => {
    const p = page('is', [2]);
    for (let tries = 0; tries < 7; tries++) {
        await p.compose();
        await p.update('I', 0, 2 + tries);
    }
    return [p.state(), { text: 'isIIIIIII', carets: [9], restarts: 6 }];
});
check('0050 known limit: a pick of another letter over a reopened word of one letter is typed after it', async () => {
    const p = page('x', [1]);
    await p.compose();
    await p.update('c', 0, 1, 1);
    return [p.state(), typedAtCaret('xc', 2)];
});
check('0050 known limit: an accented pick of another base letter over a reopened word of one letter is typed after it', async () => {
    const p = page('a', [1]);
    await p.compose();
    await p.update('\u00E9', 0, 1, 1);
    return [p.state(), typedAtCaret('a\u00E9', 2)];
});
check('0054 an uppercase and a titlecase final letter arm one each', async () => {
    const model = suggestModel(android, false);
    model.hooks.compositionStart();
    model.caret('A', 2);
    model.caret('A\u01C5', 3);
    return [model.armed(), 2];
});
check('0054 a composition ended by a Backspace move arms a suggestion at its end', async () => {
    const model = suggestModel(android, false);
    model.hooks.compositionStart();
    model.caret('alp', 4);
    model.caret('al', 3, 'deleteLeft');
    const before = model.armed();
    model.hooks.compositionEnd();
    return [[before, model.armed()], [1, 2]];
});

// --- 0024 deferred render and edit-epoch guard ---

check('0024 deferred: interleaved textupdates before prepareRender do not drop edits or trigger false beyond-buffer refusal', async () => {
    const p = page('hello', [5]);
    p.deferRenderUpdates();
    await p.compose();
    await p.update('helloa', 0, 5);
    await p.update('helloab', 0, 6);
    await p.flushDeferredUpdates();
    return [p.state(), { text: 'helloab', carets: [7], version: 3, restarts: 0, buffer: 'helloab', bufferCaret: 7 }];
});

check('0024 deferred: delayed prepareRender after an IME Enter does not clobber active composition', async () => {
    const p = page('hello', [5]);
    p.deferRenderUpdates();
    await p.compose();
    p.host.fire('beforeinput', { inputType: 'insertParagraph' });
    await p.settle();
    await p.flushDeferredUpdates();
    return [{ ...p.state(), composing: p.buffer.composing },
        { text: 'hello\n', carets: [6], version: 2, restarts: 0, buffer: 'hello\n', bufferCaret: 6, composing: true }];
});

check('0024 deferred: stale captured-epoch _updateEditContext cannot clobber a newer IME edit', async () => {
    const p = page('hello', [5]);
    p.deferRenderUpdates({ captureEpoch: true });
    await p.compose();
    const staleEpoch = p.editContext._editContextEpoch;
    await p.update('helloa', 0, 5);
    // Simulate Blink applying the next keystroke before the stale render callback fires.
    p.buffer.text = 'helloab';
    p.editContext._updateEditContext(false, staleEpoch);
    const bufferAfterStale = p.buffer.text;
    // Restore buffer to post-first-edit text before sending the real second textupdate.
    p.buffer.text = 'helloa';
    await p.update('helloab', 0, 6);
    await p.flushDeferredUpdates();
    return [{ ...p.state(), bufferAfterStale },
        { text: 'helloab', carets: [7], version: 3, restarts: 0, buffer: 'helloab', bufferCaret: 7, bufferAfterStale: 'helloab' }];
});

check('0024 deferred: external paste followed by deferred prepareRender still restarts input over open composition', async () => {
    const p = page('helloab', [7]);
    p.deferRenderUpdates();
    await p.compose();
    await p.update('helloab', 0, 7, 7);
    await p.paste('XYZ');
    await p.flushDeferredUpdates();
    return [p.state(), { text: 'helloabXYZ', carets: [10], restarts: 1, buffer: 'helloabXYZ', bufferCaret: 10 }];
});

check('0024 deferred: stale textupdate arriving after a deferred external edit is refused and preserves pasted text', async () => {
    const p = page('hello', [5]);
    p.deferRenderUpdates();
    await p.compose();
    await p.paste('XYZ');
    // Stale update computed against pre-paste "hello" arrives before prepareRender flushes.
    await p.update('helloa', 0, 5, 6);
    await p.flushDeferredUpdates();
    return [p.state(), { text: 'helloXYZ', carets: [8], version: 2, restarts: 1, buffer: 'helloXYZ', bufferCaret: 8 }];
});

check('0024 deferred: external tap to another line at the same offset clears own-edit version so restart still runs', async () => {
    const p = page('alpha\nbeta12', [5]);
    p.deferRenderUpdates();
    await p.compose();
    await p.update('alphax', 0, 5);
    await p.flushDeferredUpdates();
    await p.tap(13);
    await p.flushDeferredUpdates();
    return [p.state(), { text: 'alphax\nbeta12', carets: [13], restarts: 1, buffer: 'beta12', bufferCaret: 6 }];
});

check('0024 deferred: external edit on an unrelated line outside the EditContext window does not disrupt typing', async () => {
    const p = page('alpha\n\nomega', [5]);
    p.deferRenderUpdates();
    await p.compose();
    const endRange = p.tree.Range.fromPositions(p.model.positionAt(12), p.model.positionAt(12));
    p.model.applyEdits([{ command: 0, range: endRange, text: '!' }]);
    await p.update('alphax', 0, 5);
    await p.flushDeferredUpdates();
    return [p.state(), { text: 'alphax\n\nomega!', carets: [6], restarts: 0, buffer: 'alphax', bufferCaret: 6 }];
});

check('0024 deferred: rapid interleaved multi-cursor IME updates with deferred flush preserve every typed character', async () => {
    const p = page('alpha\nbravo', [5, 11]);
    p.deferRenderUpdates();
    await p.compose();
    await p.update('alphax', 0, 5);
    await p.update('alphaxy', 0, 6);
    await p.update('alphaxyz', 0, 7);
    await p.flushDeferredUpdates();
    return [p.state(), { text: 'alphaxyz\nbravoxyz', carets: [8, 17], restarts: 0, buffer: 'alphaxyz', bufferCaret: 8 }];
});

check('0024 deferred: captured-epoch flush after IME Enter syncs buffer and clears dirty flag', async () => {
    const p = page('hello', [5]);
    p.deferRenderUpdates({ captureEpoch: true });
    await p.compose();
    p.host.fire('beforeinput', { inputType: 'insertParagraph' });
    await p.settle();
    await p.flushDeferredUpdates();
    return [{
        ...p.state(),
        previousText: p.editContext._previousEditContextText,
        dirty: p.editContext._editContextWindowDirty,
        modelVersion: p.editContext._editContextModelVersion,
        composing: p.buffer.composing,
    }, {
        text: 'hello\n',
        carets: [6],
        buffer: 'hello\n',
        bufferCaret: 6,
        previousText: 'hello\n',
        dirty: false,
        modelVersion: p.model.version,
        restarts: 0,
        composing: true,
    }];
});

check('0024 deferred: captured-epoch flush after single textupdate clears dirty flag and updates caches', async () => {
    const p = page('hello', [5]);
    p.deferRenderUpdates({ captureEpoch: true });
    await p.compose();
    await p.update('helloa', 0, 5);
    await p.flushDeferredUpdates();
    return [{
        ...p.state(),
        previousText: p.editContext._previousEditContextText,
        dirty: p.editContext._editContextWindowDirty,
        modelVersion: p.editContext._editContextModelVersion,
    }, {
        text: 'helloa',
        carets: [6],
        buffer: 'helloa',
        bufferCaret: 6,
        previousText: 'helloa',
        dirty: false,
        modelVersion: p.model.version,
        restarts: 0,
    }];
});

check('0024 deferred: external tap back to pre-edit caret in multi-line window matches sync baseline', async () => {
    const setup = async () => {
        const p = page('alpha', [5]);
        await p.compose();
        p.host.fire('beforeinput', { inputType: 'insertParagraph' });
        await p.settle();
        await p.update('beta', 6, 6);
        return p;
    };
    const snapshot = p => ({
        text: p.model.value,
        carets: p.state().carets,
        buffer: p.buffer.text,
        bufferCaret: p.state().bufferCaret,
        startLine: p.editContext._editContextStart.lineNumber,
        startColumn: p.editContext._editContextStart.column,
        restarts: p.restarts,
    });
    const sync = await setup();
    await sync.update('betax', 6, 10);
    await sync.tap(10);

    const deferred = await setup();
    deferred.deferRenderUpdates({ captureEpoch: true });
    await deferred.update('betax', 6, 10);
    await deferred.tap(10);
    await deferred.flushDeferredUpdates();

    const expected = {
        text: 'alpha\nbetax',
        carets: [10],
        buffer: 'betax',
        bufferCaret: 4,
        startLine: 2,
        startColumn: 1,
        restarts: 1,
    };
    return [[snapshot(sync), snapshot(deferred)], [expected, expected]];
});

check('0024 deferred: external tap back to pre-edit caret refuses subsequent stale IME textupdate', async () => {
    const p = page('hello', [5]);
    p.deferRenderUpdates({ captureEpoch: true });
    await p.compose();
    await p.update('helloa', 0, 5);
    await p.tap(5);
    await p.update('helloab', 0, 6);
    await p.flushDeferredUpdates();
    return [p.state(), { text: 'helloa', carets: [5], version: 2, restarts: 1, buffer: 'helloa', bufferCaret: 5 }];
});

check('0024 deferred: unrelated out-of-window edit during deferred IME Enter preserves composition', async () => {
    const p = page('alpha\n\nomega', [5]);
    p.deferRenderUpdates({ captureEpoch: true });
    await p.compose();
    p.host.fire('beforeinput', { inputType: 'insertParagraph' });
    await p.settle();
    const endOffset = p.model.value.length;
    const endRange = p.tree.Range.fromPositions(p.model.positionAt(endOffset), p.model.positionAt(endOffset));
    p.model.applyEdits([{ command: 0, range: endRange, text: '!' }]);
    await p.flushDeferredUpdates();
    return [{ ...p.state(), composing: p.buffer.composing },
        { text: 'alpha\n\n\nomega!', carets: [6], buffer: 'alpha\n', bufferCaret: 6, restarts: 0, composing: true }];
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
