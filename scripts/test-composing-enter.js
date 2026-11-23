/**
 * Self-check for the script that makes a soft keyboard's Enter reach the
 * workbench as Enter, and the quick pick's keys reach its keybindings while
 * a word is composing.
 *
 *   node scripts/test-composing-enter.js
 *
 * The script is JavaScript inside the Kotlin raw string of
 * `MainActivity.injectComposingEnter()`, handed to `evaluateJavascript`, so
 * nothing compiles it and `ComposingEnterWiringTest` can only match its text.
 * It handles two Enters Gboard sends. A composing one is replaced by an Enter
 * the workbench recognises. A non-composing one in a single-line input (the
 * IME action GO) arrives with an empty `code`, and the workbench resolves
 * keybindings from `code`, so the Command Palette, Quick Open and every
 * input box ignore it; the script fills the `code` in. Which Enter takes
 * which branch, and that a latched Ctrl or Alt keeps the empty `code` so the
 * key row can still build its chord from the `beforeinput` that follows, is
 * the kind of thing that stays textually present while being wrong. So is
 * what happens to a Shift latched alone: the filled Enter spends it, because
 * an Enter a keybinding accepts has no `beforeinput` to spend it in, and does
 * not carry it, because Quick Open and the editor read Shift+Enter as
 * something else.
 *
 * It also clears `isComposing` on a real PageUp, PageDown, Ctrl+Home,
 * Ctrl+End or Right pressed in the quick input while a word composes, which
 * the workbench otherwise answers with no keybinding at all, and leaves every
 * other key, target and synthetic event as it is.
 *
 * This extracts the real script, runs it under `vm` against a stub window, and
 * drives the keydown listener it installs with fake events. `code`,
 * `shiftKey` and `isComposing` are read-only accessors on the fake event, as
 * they are on a real KeyboardEvent, so assigning one does nothing and only
 * `Object.defineProperty` changes it. `isTrusted` is an own property, as on a
 * real one.
 * The combined cases also run the modifier interceptor, then type a letter by
 * insertText, insertCompositionText or EditContext. DOM dispatch, keybinding
 * acceptance, focus and the input method are modeled; native insets are not.
 *
 * NEGATIVE CONTROL, measured: against the script as it was before the fill
 * (main at 2ff780ca) the three cases that expect code Enter fail, with the
 * Shift case and the five that expect `isComposing` cleared, and the other
 * fifteen pass. Against main at 42ab78ed, which filled the code but spent no
 * Shift and cleared no `isComposing`, those last six fail and eighteen pass.
 * Writing `e.code = 'Enter'` in place of the `defineProperty` fails the three
 * and the Shift case; defining `shiftKey` on the filled Enter, or leaving the
 * latch standing, fails the Shift case; assigning
 * `isComposing` instead of defining it fails the five; and dropping the
 * editor exclusion from the quick input's keys fails the Quick Chat case.
 *
 * Extraction is deliberately strict. If the raw string moves or changes shape
 * this fails saying so, rather than quietly checking an empty string.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const util = require('util');
const vm = require('vm');

const MAIN_ACTIVITY = path.join(
    __dirname, '..', 'android/app/src/main/kotlin/com/vscodroid/MainActivityPageScripts.kt',
);

/**
 * The body of the raw string in `injectComposingEnter()`, with the indentation
 * `trimIndent()` removes taken off, which is the text the WebView is given.
 */
function extractScript() {
    const lines = fs.readFileSync(MAIN_ACTIVITY, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('internal fun MainActivity.injectComposingEnter()'));
    assert.notStrictEqual(
        fn, -1,
        'injectComposingEnter() is gone from MainActivity.kt, so this check has nothing to ' +
        'run. If the script moved, point this at its new home rather than deleting the check.',
    );

    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close !== -1 && close > open + 1,
        'could not find the raw string in injectComposingEnter(); its shape changed');

    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
    assert.ok(!js.includes('$'),
        'the script gained a Kotlin interpolation this check does not know how to fill');
    assert.ok(js.includes("addEventListener('keydown'"),
        'the extracted text installs no keydown listener, so the wrong block was extracted');
    return js;
}

class FakeKeyboardEvent {
    constructor(type, init = {}) {
        this.type = type;
        this.init = init;
    }
    get key() { return this.init.key || ''; }
    get code() { return this.init.code || ''; }
    get keyCode() { return this.init.keyCode || 0; }
    get ctrlKey() { return !!this.init.ctrlKey; }
    get altKey() { return !!this.init.altKey; }
    get metaKey() { return !!this.init.metaKey; }
    get shiftKey() { return !!this.init.shiftKey; }
    get isComposing() { return !!this.init.isComposing; }
    get bubbles() { return !!this.init.bubbles; }
    get cancelable() { return !!this.init.cancelable; }
}

class FakeCompositionEvent {
    constructor(type, init = {}) {
        this.type = type;
        this.data = init.data;
        this.bubbles = !!init.bubbles;
    }
}

const listeners = [];
const win = {
    addEventListener: (type, fn, capture) => listeners.push({ type, fn, capture }),
};
vm.runInContext(extractScript(), vm.createContext({
    window: win,
    KeyboardEvent: FakeKeyboardEvent,
    CompositionEvent: FakeCompositionEvent,
}));

const keydowns = listeners.filter((l) => l.type === 'keydown');
assert.strictEqual(keydowns.length, 1, 'expected one keydown listener on the window');
assert.ok(keydowns[0].capture,
    'the keydown listener is not in the capture phase, so the workbench sees the key first');
const onKeydown = keydowns[0].fn;
const onCompositionUpdate = listeners.find((l) => l.type === 'compositionupdate').fn;

/**
 * What the listener did to one keydown: its `code`, `shiftKey` and
 * `isComposing` afterwards, whether `code` is now an own property (defined by
 * the script), whether the real key was stopped or cancelled, what was
 * dispatched in its place, and the key row's latch state it left.
 */
function press(init, { latched, inEditor = false, inQuickInput = false, trusted = true } = {}) {
    win.__vscodroid = latched && { ctrl: false, alt: false, shift: false, ...latched };
    const dispatched = [];
    const target = {
        tagName: 'INPUT',
        closest: (selector) => ((inEditor && selector.includes('.monaco-editor')) ||
            (inQuickInput && selector.includes('.quick-input-widget')) ? {} : null),
        dispatchEvent: (ev) => {
            dispatched.push(ev.type === 'keydown'
                ? `keydown ${ev.key} ${ev.code} ${ev.keyCode}${ev.ctrlKey ? ' ctrl' : ''}${ev.altKey ? ' alt' : ''}${ev.shiftKey ? ' shift' : ''}`
                : `${ev.type} ${ev.data}`);
            return true;
        },
    };
    const e = new FakeKeyboardEvent('keydown', { keyCode: 13, ...init });
    let stopped = false;
    let prevented = false;
    Object.defineProperty(e, 'isTrusted', { value: trusted });
    e.target = target;
    e.stopImmediatePropagation = () => { stopped = true; };
    e.preventDefault = () => { prevented = true; };
    onKeydown(e);
    return {
        code: e.code,
        defined: Object.prototype.hasOwnProperty.call(e, 'code'),
        shiftKey: e.shiftKey,
        isComposing: e.isComposing,
        stopped,
        prevented,
        dispatched,
        latch: win.__vscodroid ? { ...win.__vscodroid } : null,
    };
}

const NO_LATCH = {};
const CLEAR = { ctrl: false, alt: false, shift: false };
/** A key the script left alone, with no latch on the row, changed by `over`. */
const outcome = (over) => ({
    code: '',
    defined: false,
    shiftKey: false,
    isComposing: false,
    stopped: false,
    prevented: false,
    dispatched: [],
    latch: CLEAR,
    ...over,
});
const filled = outcome({ code: 'Enter', defined: true });
const gboardEnter = { key: 'Enter', code: '' };

const cases = [
    ['a code-less Enter gets code Enter and goes on to the workbench',
        press(gboardEnter, { latched: NO_LATCH }), filled],
    ['the same before the key row has set up its latch state',
        press(gboardEnter), { ...filled, latch: null }],
    ['the same inside the editor, where its rename box lives',
        press(gboardEnter, { latched: NO_LATCH, inEditor: true }), filled],
    ['with Ctrl latched it keeps code "" for the key row to chord',
        press(gboardEnter, { latched: { ctrl: true } }), outcome({ latch: { ...CLEAR, ctrl: true } })],
    ['with Alt latched it keeps code "" for the key row to chord',
        press(gboardEnter, { latched: { alt: true } }), outcome({ latch: { ...CLEAR, alt: true } })],
    ['with Shift latched it gets code Enter but not Shift, and spends the latch',
        press(gboardEnter, { latched: { shift: true } }), filled],
    ['with Ctrl and Shift latched it keeps code "" and all of the latch for the chord',
        press(gboardEnter, { latched: { ctrl: true, shift: true } }),
        outcome({ latch: { ...CLEAR, ctrl: true, shift: true } })],
    ['an Enter that already has code Enter is left alone',
        press({ key: 'Enter', code: 'Enter' }, { latched: NO_LATCH }), outcome({ code: 'Enter' })],
    ['a trusted code-bearing Enter spends the row Shift without applying it',
        press({ key: 'Enter', code: 'Enter' }, { latched: { shift: true } }),
        outcome({ code: 'Enter' })],
    ['a code-less key that is not Enter is left alone',
        press({ key: 'Unidentified', code: '', keyCode: 229 }, { latched: NO_LATCH }), outcome({})],
];

onCompositionUpdate({ data: 'alpha' });
cases.push(['a composing Enter is still stopped and replaced',
    press({ ...gboardEnter, isComposing: true }, { latched: NO_LATCH }),
    outcome({
        isComposing: true,
        stopped: true,
        dispatched: ['compositionend alpha', 'keydown Enter Enter 13'],
    })]);
cases.push(['a composing Enter with Shift latched is replaced and spends the latch',
    press({ ...gboardEnter, isComposing: true }, { latched: { shift: true } }),
    outcome({
        isComposing: true,
        stopped: true,
        dispatched: ['compositionend alpha', 'keydown Enter Enter 13'],
    })]);
const enterLatches = [
    { ctrl: true }, { alt: true }, { ctrl: true, alt: true },
    { ctrl: true, shift: true }, { alt: true, shift: true },
    { ctrl: true, alt: true, shift: true },
];
for (const latched of enterLatches) {
    const names = Object.keys(latched).join('+');
    cases.push([`a composing Enter carries and spends ${names}`,
        press({ ...gboardEnter, isComposing: true }, { latched }),
        outcome({
            isComposing: true,
            stopped: true,
            prevented: true,
            dispatched: ['compositionend alpha', `keydown Enter Enter 13 ${Object.keys(latched).join(' ')}`],
        })]);
}
cases.push(['a composing Enter inside the editor is left alone',
    press({ ...gboardEnter, isComposing: true }, { latched: NO_LATCH, inEditor: true }),
    outcome({ isComposing: true })]);

// The row's real keys while a word composes. Only the keys the quick input
// binds, pressed for real inside it, lose `isComposing`; every other key,
// target and synthetic event keeps it.
const composingKey = (key, more) => ({ key, code: key, isComposing: true, ...more });
const inQuickInput = { latched: NO_LATCH, inQuickInput: true };
const reachesBindings = (key) => outcome({ code: key });
const keepsComposing = (key) => outcome({ code: key, isComposing: true });
for (const key of ['PageDown', 'PageUp', 'ArrowRight']) {
    cases.push([`a composing ${key} in the quick input reaches its keybinding`,
        press(composingKey(key), inQuickInput), reachesBindings(key)]);
}
for (const key of ['Home', 'End']) {
    cases.push([`a composing Ctrl+${key} in the quick input reaches its keybinding`,
        press(composingKey(key, { ctrlKey: true }), inQuickInput), reachesBindings(key)]);
    cases.push([`a composing ${key} without Ctrl, which the quick input does not bind, is left alone`,
        press(composingKey(key), inQuickInput), keepsComposing(key)]);
}
cases.push(['a composing ArrowLeft, which the quick input does not bind, is left alone',
    press(composingKey('ArrowLeft'), inQuickInput), keepsComposing('ArrowLeft')]);
cases.push(['a composing PageDown in a text box outside the quick input is left alone',
    press(composingKey('PageDown'), { latched: NO_LATCH }), keepsComposing('PageDown')]);
cases.push(['a composing PageDown inside the editor is left alone',
    press(composingKey('PageDown'), { latched: NO_LATCH, inEditor: true }), keepsComposing('PageDown')]);
cases.push(['a composing PageDown in an editor inside the quick input, as Quick Chat has, is left alone',
    press(composingKey('PageDown'), { ...inQuickInput, inEditor: true }), keepsComposing('PageDown')]);
cases.push(['a composing PageDown that a script built is left alone',
    press(composingKey('PageDown'), { ...inQuickInput, trusted: false }), keepsComposing('PageDown')]);

// Run both page scripts together: accepting Enter cancels its default, so no
// beforeinput follows it to spend a modifier before the next character.
const injectorSource = fs.readFileSync(path.join(path.dirname(MAIN_ACTIVITY), 'keyboard/KeyInjector.kt'), 'utf8');
const injectorMethod = injectorSource.indexOf('fun setupModifierInterceptor()');
const injectorStart = injectorSource.indexOf('"""', injectorMethod) + 3;
const injectorEnd = injectorSource.indexOf('""".trimIndent()', injectorStart);
assert(injectorMethod >= 0 && injectorStart > injectorMethod && injectorEnd > injectorStart,
    'could not extract the modifier interceptor');
// These cases type Latin letters, so no punctuation lookup or modifier keyup is needed.
const interceptor = injectorSource.slice(injectorStart, injectorEnd)
    .replace('$keyLookup', '{}').replace('$RELEASE_MODIFIERS_JS', 'function releaseModifiers() {}');
assert(!interceptor.includes('$'), 'unhandled interpolation in the modifier interceptor');

function acceptedThenTyped(latched, route) {
    const timers = [];
    const listen = (node) => Object.assign(node, {
        listeners: [],
        addEventListener(type, fn, capture) { this.listeners.push({ type, fn, capture: !!capture }); },
        removeEventListener(type, fn) { this.listeners = this.listeners.filter((l) => l.type !== type || l.fn !== fn); },
    });
    const window = listen({});
    const document = listen({});
    function dispatch(target, event) {
        let stopped = false;
        Object.assign(event, {
            target, defaultPrevented: false,
            preventDefault() { if (this.cancelable) this.defaultPrevented = true; },
            stopPropagation() { stopped = true; },
            stopImmediatePropagation() { stopped = true; },
            composedPath: () => [target, document, window],
        });
        const outer = window.event;
        window.event = event;
        try {
            const phases = [[window, true], [document, true], [target, true], [target, false]];
            if (event.bubbles) phases.push([document, false], [window, false]);
            for (const [node, capture] of phases) {
                for (const l of node.listeners.slice()) {
                    if (l.type !== event.type || l.capture !== capture) continue;
                    l.fn(event);
                    if (stopped) return !event.defaultPrevented;
                }
            }
            return !event.defaultPrevented;
        } finally { window.event = outer; }
    }
    const element = (props) => {
        const node = listen(props);
        node.dispatchEvent = (event) => dispatch(node, event);
        return node;
    };
    const input = element({
        tagName: 'INPUT', classList: { contains: () => false }, closest: () => null,
        value: 'file', selectionStart: 4, selectionEnd: 4,
        setSelectionRange(start, end) { this.selectionStart = start; this.selectionEnd = end; },
    });
    class EditContext {
        constructor() { listen(this); this.text = ''; this.selectionStart = this.selectionEnd = 0; }
        updateText(start, end, text) { this.text = this.text.slice(0, start) + text + this.text.slice(end); }
        updateSelection(start, end) { this.selectionStart = start; this.selectionEnd = end; }
        dispatchEvent(event) { for (const l of this.listeners.slice()) if (l.type === event.type) l.fn(event); }
    }
    const own = new EditContext();
    own.text = 'file'; own.selectionStart = own.selectionEnd = 4;
    const host = element({
        tagName: 'DIV', classList: { contains: (name) => name === 'native-edit-context' }, editContext: own,
        focus() { document.activeElement = host; dispatch(host, { type: 'focusin', bubbles: true }); },
        blur() { document.activeElement = null; dispatch(host, { type: 'focusout', bubbles: true }); },
    });
    document.activeElement = input;
    const make = function(type, init) { return { type, ...init }; };
    const context = vm.createContext({
        window, document, navigator: { userAgent: '' }, EditContext,
        KeyboardEvent: FakeKeyboardEvent, CompositionEvent: FakeCompositionEvent,
        InputEvent: make, TextUpdateEvent: make,
        setTimeout(fn) { timers.push(fn); }, requestAnimationFrame(fn) { timers.push(fn); },
    });
    vm.runInContext(interceptor, context);
    vm.runInContext(extractScript(), context);
    const flush = () => { for (const fn of timers.splice(0)) fn(); };
    const mods = () => {
        const m = window.__vscodroid;
        return { ctrl: !!m.ctrl, alt: !!m.alt, shift: !!m.shift };
    };
    const nextChords = [];
    let accepted = null;
    // Binding resolution and focus after acceptance are the modeled boundary.
    window.addEventListener('keydown', (event) => {
        const flags = { ctrl: event.ctrlKey, alt: event.altKey, shift: event.shiftKey };
        if (event.key === 'a') nextChords.push(flags);
        if (event.key === 'Enter' && event.code === 'Enter' && !event.isComposing) {
            accepted = flags;
            event.preventDefault();
            if (route === 'EditContext') host.focus();
        }
    }, false);
    input.dispatchEvent(new FakeCompositionEvent('compositionupdate', { data: 'file', bubbles: true }));
    Object.assign(window.__vscodroid, CLEAR, latched);
    flush();
    const enter = new FakeKeyboardEvent('keydown', { ...gboardEnter, isComposing: true, bubbles: true, cancelable: true });
    Object.defineProperty(enter, 'isTrusted', { value: true });
    const cancelled = !input.dispatchEvent(enter);
    const afterEnter = mods();
    flush();
    if (route === 'EditContext') {
        const ec = host.editContext;
        ec.dispatchEvent({ type: 'compositionstart' });
        const start = ec.selectionStart;
        ec.updateText(start, ec.selectionEnd, 'a');
        ec.updateSelection(start + 1, start + 1);
        ec.dispatchEvent({ type: 'textupdate', text: 'a', updateRangeStart: start, updateRangeEnd: start });
    } else {
        if (route === 'insertCompositionText') {
            input.dispatchEvent(new FakeCompositionEvent('compositionupdate', { data: 'a', bubbles: true }));
        }
        const allowed = input.dispatchEvent({ type: 'beforeinput', inputType: route, data: 'a', bubbles: true,
            cancelable: route === 'insertText' });
        if (allowed) {
            input.value += 'a'; input.selectionStart = input.selectionEnd = input.value.length;
            input.dispatchEvent({ type: 'input', inputType: route, data: 'a', bubbles: true });
        }
    }
    flush();
    return { accepted, cancelled, afterEnter, value: route === 'EditContext' ? own.text : input.value, nextChords };
}

for (const latched of [{}, { shift: true }, ...enterLatches]) {
    const names = Object.keys(latched).join('+') || 'no modifier';
    for (const route of ['insertText', 'insertCompositionText', 'EditContext']) {
        cases.push([`accepted composing Enter with ${names} leaves the next ${route} letter literal`,
            acceptedThenTyped(latched, route),
            { accepted: { ...CLEAR, ...latched, shift: !!((latched.ctrl || latched.alt) && latched.shift) },
                cancelled: true, afterEnter: CLEAR, value: 'filea', nextChords: [] }]);
    }
}

let failed = 0;
for (const [name, got, want] of cases) {
    if (util.isDeepStrictEqual(got, want)) {
        console.log(`ok   ${name}`);
    } else {
        failed += 1;
        console.error(`FAIL ${name}\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`);
    }
}
console.log(`\n${cases.length - failed}/${cases.length} passed`);
process.exit(failed ? 1 : 0);
