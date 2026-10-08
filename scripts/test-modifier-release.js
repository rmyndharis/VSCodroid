/**
 * Self-check for the keyup the page is sent for each modifier a key row chord
 * carried.
 *
 *   node scripts/test-modifier-release.js
 *
 * The row's Ctrl, Alt and Shift are latches, so the page sees a modifier go
 * down with a chord and, unless something says otherwise, never come up. The
 * workbench tells its toolbars about a modifier only when one goes down or
 * comes up, so after Alt+Left from the row the editor's split button went on
 * showing Split Editor Down, and running it on a tap. A real press releases its
 * modifiers with real key events (`navigationKeyEvents`, which the Kotlin suite
 * and `TextEntryInstrumentedTest` check). The two scripts that send a chord as
 * DOM events, the announce script in `KeyInjector.announceKeystroke` and the
 * modifier interceptor's `beforeinput` listener, call `releaseModifiers`. The
 * scripts and the function are JavaScript in Kotlin raw strings, which nothing
 * compiles and the Kotlin tests can only match as text.
 *
 * This extracts the scripts, fills in their interpolations, runs them under
 * `vm` against a fake document, and feeds every event they dispatch to a copy
 * of the workbench's `ModifierKeyEmitter`, transcribed from the shipped
 * workbench.js (Code - OSS 1.139.1, minified as `Vy`; upstream it is in
 * `src/vs/base/browser/dom.ts`). What it checks is what the workbench
 * concludes: that no modifier is held once a chord is over, and that nothing
 * reads as the lone Alt press and release that focuses the menu bar.
 *
 * It also runs the recently used editors picker Ctrl+Tab opens, with the
 * quick input's `registerQuickNavigation` transcribed from the same file,
 * which accepts on a modifier's keyup in the picker's own container. Opened
 * from a file it takes the focus to its list, and the keyboard and the row go
 * down. Opened in the Command Palette it keeps the palette's input box, so the
 * row's chords and their releases land inside the container: one or two row
 * Ctrl+Tabs there, and a real Ctrl+Left from the trackpad, must leave it open,
 * and a hardware keyboard's own Ctrl release must still accept it. The real
 * press is modelled as the events Chromium hands the page for
 * `navigationKeyEvents`. A keyboard holding both Shifts must have each release
 * go on to the page.
 *
 * Last, a keyboard that composes, as Gboard 12.4 composes every letter, types
 * into a text box, with the composition events in Blink's order: a letter it
 * adds while Ctrl is latched must come out as the chord only once the box has
 * the text it had before the letter and no composition, with the box's own
 * `beforeinput` and `input` listeners never hearing the letter, over a word
 * still composing too, and so must the next latched letter. The box keeps the
 * focus throughout, so one that commits what it holds on blur, as the debug
 * view's inline boxes do, commits nothing and stays open. A box with no
 * selection API keeps the letter. In the terminal, with xterm's composition
 * handling copied from the shipped @xterm/xterm, the word composed before the
 * chord must reach the shell first, also when the workbench takes the chord
 * and the Quick Open it runs takes the focus at once, which the terminal then
 * takes back on the chord's keyup.
 *
 * NEGATIVE CONTROL, measured: against KeyInjector.kt at f66e462f, which sent
 * no release, 36 of the 77 cases fail; against the interceptor before it made
 * a chord of a composed letter, 16 of the 29 composing cases; against one
 * that blurred every box, 3 cases, the box that commits on blur committing
 * `fooa`, sending no chord and leaving Ctrl latched; against one that sent
 * the chord in the task that ended the composition, the case where Quick Open
 * takes the focus, the word lost; against one that released at the chord's
 * target with nothing stopping it at the window, 5 picker cases fail, the
 * first Ctrl+Tab in the Command Palette opening the editor it had just
 * highlighted; and against one that paired a release with a keydown by key
 * rather than by code, the case of a keyboard holding both Shifts fails.
 * Each of these changes fails at least one case: not calling the release from
 * either script, or from either of the interceptor's two chords; releasing
 * with the flag still set; sending the keyup at what has focus rather than at
 * the chord's own target; releasing in another order; and in the interceptor,
 * no stop at the window, a stop in the bubble phase, stopImmediatePropagation
 * or preventDefault in its place, or a stop that ignores the keydowns a
 * keyboard sends first. For a composed letter: the chord sent in the same
 * task, no blur of the terminal's textarea or a blur of any other box, the
 * text put back before the blur or whatever changed it, the box's
 * `beforeinput` or `input` listener hearing the letter, no guard while a
 * chord is pending, the guard dropped before the chord or never, a box with
 * no selection API taken for one with it, and the editor's own textarea, a
 * rewritten word or a character with no key taken for a letter.
 *
 * Extraction is strict: if a raw string moves or changes shape this fails
 * saying so, rather than quietly running an empty script.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SOURCE = fs.readFileSync(path.join(
    __dirname, '..', 'android/app/src/main/kotlin/com/vscodroid/keyboard/KeyInjector.kt',
), 'utf8').split('\n');

/** The lines of the raw string that opens at `open`, dedented as `trimIndent()` would. */
function rawString(open, what) {
    const close = SOURCE.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close > open + 1, `could not find the raw string of ${what}; its shape changed`);
    const body = SOURCE.slice(open + 1, close);
    const indent = Math.min(...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length));
    return body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
}

/** The raw string assigned to `val js` inside `declaration`. */
function scriptOf(declaration) {
    const fn = SOURCE.findIndex((l) => l.includes(declaration));
    assert.notStrictEqual(fn, -1,
        `${declaration} is gone from KeyInjector.kt, so this check has nothing to run. If the ` +
        'script moved, point this at its new home rather than deleting the check.');
    return rawString(SOURCE.findIndex((l, i) => i > fn && l.trim() === 'val js = """'), declaration);
}

const RELEASE_OPEN = SOURCE.findIndex((l) => l.startsWith('private val RELEASE_MODIFIERS_JS = """'));
const RELEASE = RELEASE_OPEN === -1 ? null : rawString(RELEASE_OPEN, 'RELEASE_MODIFIERS_JS');

/** `js` with `$RELEASE_MODIFIERS_JS` and the other `fills` put in, refusing any it does not know. */
function fill(js, fills) {
    if (js.includes('$RELEASE_MODIFIERS_JS')) {
        assert.ok(RELEASE, 'a script interpolates RELEASE_MODIFIERS_JS, which is no longer defined');
        js = js.split('$RELEASE_MODIFIERS_JS').join(RELEASE);
    }
    for (const [name, value] of Object.entries(fills)) js = js.split(name).join(value);
    assert.ok(!js.includes('$'), 'a script gained a Kotlin interpolation this check does not know how to fill');
    return js;
}

const ANNOUNCE = scriptOf('private fun announceKeystroke(');
const INTERCEPTOR = fill(scriptOf('fun setupModifierInterceptor()'), { '$keyLookup': '{}' });

/**
 * The workbench's ModifierKeyEmitter, a capture listener on the window for
 * keydown and keyup. It fires, which is the only time a toolbar reads the
 * modifiers again, for a modifier newly down or one released, and for nothing
 * else. `fired` keeps what each firing reported.
 */
function modifierKeyEmitter() {
    const status = { altKey: false, ctrlKey: false, metaKey: false, shiftKey: false };
    const fired = [];
    const take = (e) => {
        status.altKey = e.altKey; status.ctrlKey = e.ctrlKey; status.metaKey = e.metaKey; status.shiftKey = e.shiftKey;
    };
    return {
        status,
        fired,
        keydown(e) {
            if (e.defaultPrevented || (e.keyCode === 18 && e.repeat)) return;
            if (e.altKey && !status.altKey) status.lastKeyPressed = 'alt';
            else if (e.ctrlKey && !status.ctrlKey) status.lastKeyPressed = 'ctrl';
            else if (e.metaKey && !status.metaKey) status.lastKeyPressed = 'meta';
            else if (e.shiftKey && !status.shiftKey) status.lastKeyPressed = 'shift';
            else if (e.keyCode !== 18) status.lastKeyPressed = undefined;
            else return;
            take(e);
            if (status.lastKeyPressed) fired.push({ ...status });
        },
        keyup(e) {
            if (e.defaultPrevented) return;
            if (!e.altKey && status.altKey) status.lastKeyReleased = 'alt';
            else if (!e.ctrlKey && status.ctrlKey) status.lastKeyReleased = 'ctrl';
            else if (!e.metaKey && status.metaKey) status.lastKeyReleased = 'meta';
            else if (!e.shiftKey && status.shiftKey) status.lastKeyReleased = 'shift';
            else status.lastKeyReleased = undefined;
            if (status.lastKeyPressed !== status.lastKeyReleased) status.lastKeyPressed = undefined;
            take(e);
            if (status.lastKeyReleased) fired.push({ ...status });
        },
    };
}

/**
 * Anything listeners can be added to, inside `parent` if it has one. A node
 * that is not `connected` is out of the document.
 */
class Node {
    constructor(name, parent = null) {
        Object.assign(this, { name, parent, tagName: 'DIV', connected: true, listeners: [] });
    }
    addEventListener(type, fn, capture) {
        this.listeners.push({ type, fn, capture: capture === true || !!(capture && capture.capture) });
    }
    removeEventListener(type, fn) {
        this.listeners = this.listeners.filter((l) => l.type !== type || l.fn !== fn);
    }
}

/**
 * A page holding the workbench's emitter on its window, unless `emitter` is
 * false and the case attaches it later, and `focused` as the active element.
 * Every event dispatched is logged with the node it was sent at. It travels as
 * a browser sends it: capture from the window down through the node's parents
 * to the node, then back up, with `stopPropagation` ending the trip once the
 * node it was called at is done. One sent at a node out of the document reaches
 * that node alone. `isTrusted` is false on an event a script builds and true
 * on one the browser sends for a real key (`real` below).
 */
function newPage({ emitter = true } = {}) {
    const window = new Node('window');
    const document = new Node('document');
    document.body = new Node('body');
    document.body.tagName = 'BODY';
    const page = { window, document, log: [], emitter: modifierKeyEmitter() };
    page.attachEmitter = () => {
        window.addEventListener('keydown', (e) => page.emitter.keydown(e), true);
        window.addEventListener('keyup', (e) => page.emitter.keyup(e), true);
    };
    if (emitter) page.attachEmitter();
    page.focused = document.activeElement = new Node('editor');
    page.window.__vscodroid = {};
    class KeyboardEvent {
        constructor(type, init) {
            Object.assign(this, { repeat: false, defaultPrevented: false, isTrusted: false }, init, { type });
        }
        preventDefault() { if (this.cancelable) this.defaultPrevented = true; }
        stopPropagation() { this.stopped = true; }
        stopImmediatePropagation() { this.stopped = this.stoppedNow = true; }
        composedPath() { return [this.target]; }
    }
    page.KeyboardEvent = KeyboardEvent;
    const dispatchEvent = function(event) {
        event.target = this;
        page.log.push({ at: this.name, ...event });
        const route = [];
        for (let n = this; n; n = n.parent) route.unshift(n);
        if (this.connected) route.unshift(window);
        const run = (node, capture) => {
            for (const l of node.listeners.filter((x) => x.type === event.type && x.capture === capture)) {
                if (event.stoppedNow) return;
                l.fn.call(node, event);
            }
        };
        for (const node of route) if (!event.stopped) run(node, true);
        for (const node of route.reverse()) if (!event.stopped) run(node, false);
        return !event.defaultPrevented;
    };
    for (const node of [document.body, page.focused]) node.dispatchEvent = dispatchEvent;
    page.newNode = (name, parent) => Object.assign(new Node(name, parent), { dispatchEvent });
    // Timers wait until a case runs them, in the order they were set, as the
    // browser runs zero timeouts once the task that set them is over. One that
    // throws is reported, as the browser reports it, and the rest still run.
    // Running one when none is set does nothing, so a script that set none
    // fails the case's own checks and is not reported as a timer that threw.
    page.timers = [];
    page.runTimer = () => {
        const timer = page.timers.shift();
        if (!timer) return;
        try { timer(); } catch (e) { timerErrors.push(e.message); }
    };
    page.runTimers = () => { while (page.timers.length) page.runTimer(); };
    page.context = vm.createContext({
        window,
        document,
        KeyboardEvent,
        InputEvent: KeyboardEvent,
        navigator: { userAgent: 'Chrome/153.0.0.0' },
        setTimeout: (fn) => { page.timers.push(fn); },
    });
    return page;
}

/** One announced press, as `announceKeystroke` builds it for `key` with these modifiers. */
function announce(page, key, code, keyCode, mods = {}) {
    vm.runInContext(fill(ANNOUNCE, {
        '${jsKey}': JSON.stringify(key),
        '${jsCode}': JSON.stringify(code),
        '${keyDef.keyCode}': String(keyCode),
        '${ctrlKey}': String(!!mods.ctrl),
        '${altKey}': String(!!mods.alt),
        '${effectiveShift}': String(!!mods.shift),
        '${metaKey}': String(!!mods.meta),
    }), page.context);
}

/** The interceptor installed on `page`, and its `beforeinput` listener. */
function intercept(page) {
    vm.runInContext(INTERCEPTOR, page.context);
    const listener = page.document.listeners.find((l) => l.type === 'beforeinput');
    assert.ok(listener, 'the interceptor installs no beforeinput listener');
    return (inputType, data) => listener.fn({
        inputType, data, preventDefault() {}, stopImmediatePropagation() {},
    });
}

/** The DOM keyCode of each modifier, and the workbench's own KeyCode for it. */
const KEY_CODE = { 16: 4, 17: 5, 18: 6, 91: 57 };

/**
 * The recently used editors picker the workbench opens on Ctrl+Tab, and how it
 * accepts, from the shipped workbench.js. The keybinding service, a bubble
 * listener on the window, runs `quickOpenPreviousRecentlyUsedEditorInGroup`
 * (primary 2050, Ctrl+Tab), which shows the picker with the second editor
 * highlighted and `quickNavigateConfiguration: { keybindings }` holding
 * Ctrl+Tab. Where the focus then is decides whether the row can reach it.
 * Opened from the editor, the picker hides its input, so `update` gives the
 * list DOM focus, and Chromium hides the soft keyboard whenever focus leaves
 * an editable element, which takes the key row with it. Opened over a quick
 * pick that is showing, as Ctrl+Tab from the row in the Command Palette opens
 * it, it keeps that pick's input box, `hideInput=!!h.quickNavigate&&!a` with
 * `a` the visible quick access, and the box keeps the focus, so the keyboard
 * and the row stay up and every chord from the row is typed inside the
 * container, its release with it (`overPalette`). While it is open, Ctrl+Tab
 * runs `quickOpenNavigateNextInEditorPicker`, which moves the highlight down
 * and sets quick navigate again. `registerQuickNavigation` is a keyup listener
 * on the widget's container, which accepts the highlighted editor on the keyup
 * of a modifier a quick navigate keybinding holds; minified:
 *
 *   G(this.ui.container,ne.KEY_UP,e=>{if(this.canSelectMany||!this._quickNavigate)return;
 *   let t=new Yt(e),i=t.keyCode;this._quickNavigate.keybindings.some(a=>{let c=a.getChords();
 *   return c.length>1?!1:c[0].shiftKey&&i===4?!(t.ctrlKey||t.altKey||t.metaKey):
 *   !!(c[0].altKey&&i===6||c[0].ctrlKey&&i===5||c[0].metaKey&&i===57)})&&(this.activeItems[0]&&
 *   (...,this.handleAccept(!1)),this._quickNavigate=void 0)})
 *
 * `Yt` is StandardKeyboardEvent, whose keyCode maps the DOM's 16, 17, 18 and
 * 91 to 4, 5, 6 and 57 and whose modifier flags are also set by the key itself.
 */
function recentEditorsPicker(page, { overPalette = false } = {}) {
    const container = page.newNode('quick-input-widget', page.document.body);
    const input = page.newNode('quick-input-box', container);
    const list = page.newNode('quick-input-list', container);
    if (overPalette) page.document.activeElement = input;
    const picker = { open: false, active: -1, accepted: [], quickNavigate: null, list };
    page.window.addEventListener('keydown', (e) => {
        if (e.key !== 'Tab' || !e.ctrlKey || e.altKey || e.shiftKey || e.metaKey) return;
        if (picker.open) {
            picker.active += 1;
        } else {
            Object.assign(picker, { open: true, active: 1 });
            if (!overPalette) page.document.activeElement = list;
        }
        picker.quickNavigate = { keybindings: [{ ctrlKey: true, shiftKey: false, altKey: false, metaKey: false }] };
    });
    container.addEventListener('keyup', (e) => {
        if (!picker.quickNavigate) return;
        const keyCode = KEY_CODE[e.keyCode] || 0;
        const t = { ctrlKey: e.ctrlKey || keyCode === 5, altKey: e.altKey || keyCode === 6, metaKey: e.metaKey || keyCode === 57 };
        const trigger = picker.quickNavigate.keybindings.some((c) => (c.shiftKey && keyCode === 4
            ? !(t.ctrlKey || t.altKey || t.metaKey)
            : !!((c.altKey && keyCode === 6) || (c.ctrlKey && keyCode === 5) || (c.metaKey && keyCode === 57))));
        if (!trigger) return;
        picker.accepted.push(picker.active);
        Object.assign(picker, { open: false, quickNavigate: null });
    });
    return picker;
}

/**
 * What Chromium hands the page for a real press: `key` at the focused element,
 * down then up, with the latched modifiers, as an event the browser sent.
 * Then, as `navigationKeyEvents` builds the press, the release of each latched
 * modifier, Alt, Ctrl and Shift in that order, each with the rest still held.
 * The release has no keydown before it: nothing presses a latch.
 */
function realPress(page, key, code, keyCode, mods = {}) {
    const held = { ctrlKey: !!mods.ctrl, altKey: !!mods.alt, shiftKey: !!mods.shift, metaKey: false };
    const send = (type, init) => page.document.activeElement.dispatchEvent(new page.KeyboardEvent(type, {
        ...held, ...init, bubbles: true, cancelable: true, composed: true, isTrusted: true,
    }));
    send('keydown', { key, code, keyCode, which: keyCode });
    send('keyup', { key, code, keyCode, which: keyCode });
    for (const [flag, k, c, kc] of [['altKey', 'Alt', 'AltLeft', 18], ['ctrlKey', 'Control', 'ControlLeft', 17],
        ['shiftKey', 'Shift', 'ShiftLeft', 16]]) {
        if (!held[flag]) continue;
        held[flag] = false;
        send('keyup', { key: k, code: c, keyCode: kc, which: kc });
    }
}

/** A hardware keyboard's key, `type` only: a modifier there goes down and comes up as a key of its own. */
function hardwareKey(page, type, key, code, keyCode, mods = {}) {
    page.document.activeElement.dispatchEvent(new page.KeyboardEvent(type, {
        key, code, keyCode, which: keyCode, ctrlKey: !!mods.ctrl, altKey: false, shiftKey: false, metaKey: false,
        bubbles: true, cancelable: true, composed: true, isTrusted: true,
    }));
}

const MODIFIER_KEYS = ['Alt', 'Control', 'Shift', 'Meta'];

/** The log as `type key [mods]`, the way a reader would write the sequence out. */
const sequence = (page) => page.log.map((e) => [e.type, e.key]
    .concat(['ctrlKey', 'altKey', 'shiftKey', 'metaKey'].filter((m) => e[m]).map((m) => m.slice(0, -3)))
    .join(' '));

/** The workbench's conclusions once the scripts are done. */
function outcome(page) {
    const { status, fired } = page.emitter;
    return {
        // What a toolbar last read: Split Editor Down while this is true.
        toolbarAlt: fired.length > 0 && fired[fired.length - 1].altKey,
        held: ['altKey', 'ctrlKey', 'shiftKey', 'metaKey'].filter((m) => status[m]),
        // The menu bar focuses itself on an Alt pressed and released with nothing between.
        menuBarAlt: fired.some((s) => !s.altKey && !s.ctrlKey && !s.shiftKey && !s.metaKey &&
            s.lastKeyPressed === 'alt' && s.lastKeyReleased === 'alt'),
        modifierPressed: page.log.some((e) => e.type === 'keydown' && MODIFIER_KEYS.includes(e.key)),
    };
}

const cases = [];
const timerErrors = [];
const settled = (name, page) => {
    const o = outcome(page);
    cases.push([`${name}: no toolbar is left on its Alt action`, o.toolbarAlt, false]);
    cases.push([`${name}: no modifier is left held`, o.held.join('+'), '']);
    cases.push([`${name}: nothing reads as a lone Alt for the menu bar`, o.menuBarAlt || o.modifierPressed, false]);
};

// Alt and the row's Esc, the measured case on the announce route.
{
    const page = newPage();
    announce(page, 'Escape', 'Escape', 27, { alt: true });
    cases.push(['Alt+Esc comes up as Esc, then Alt with nothing held', sequence(page).join(', '),
        'keydown Escape alt, keyup Escape alt, keyup Alt']);
    settled('Alt+Esc', page);
}

// Every modifier at once: each comes up in turn, leaving the others held.
{
    const page = newPage();
    announce(page, 'F5', 'F5', 116, { ctrl: true, alt: true, shift: true });
    cases.push(['Ctrl+Alt+Shift+F5 releases Alt, then Ctrl, then Shift', sequence(page).slice(2).join(', '),
        'keyup Alt ctrl shift, keyup Control shift, keyup Shift']);
    settled('Ctrl+Alt+Shift+F5', page);
}

// A key with nothing held sends nothing more.
{
    const page = newPage();
    announce(page, 'Tab', 'Tab', 9);
    cases.push(['a plain Tab is a keydown and a keyup and nothing else', sequence(page).join(', '),
        'keydown Tab, keyup Tab']);
}

// A chord that moves focus, as F1 opening the Command Palette does: the
// release goes where the chord's keyup went, which the window still hears.
{
    const page = newPage();
    const palette = page.newNode('palette');
    page.focused.addEventListener('keydown', () => { page.document.activeElement = palette; });
    announce(page, 'F1', 'F1', 112, { alt: true });
    cases.push(['the release after a chord that moved focus goes where the chord went',
        page.log.filter((e) => e.key === 'Alt').map((e) => e.at).join(','), 'editor']);
    settled('a chord that moved focus', page);
}

// A chord that takes its own target out of the document, as closing a box
// does. The window then hears neither its keyup nor the release, and the
// toolbars stay as the chord left them. What must not happen is the window
// hearing the release alone: after an Alt chord whose keyup it never heard,
// that reads as Alt pressed and released by itself, and focuses the menu bar.
{
    const page = newPage();
    page.focused.addEventListener('keydown', () => {
        page.focused.connected = false;
        page.document.activeElement = page.newNode('next');
    });
    announce(page, 'Escape', 'Escape', 27, { alt: true });
    const o = outcome(page);
    cases.push(['a chord that removed its target never reads as a lone Alt', o.menuBarAlt || o.modifierPressed, false]);
}

// Two Alt chords in a row are two chords, not an Alt pressed and released.
{
    const page = newPage();
    announce(page, 'Escape', 'Escape', 27, { alt: true });
    announce(page, 'F1', 'F1', 112, { alt: true });
    settled('two Alt chords', page);
}

// The interceptor: a letter typed on the soft keyboard with Alt latched.
{
    const page = newPage();
    const input = intercept(page);
    page.window.__vscodroid.alt = true;
    input('insertText', 'p');
    cases.push(['soft keyboard p with Alt latched comes up as p, then Alt', sequence(page).join(', '),
        'keydown p alt, keyup p alt, keyup Alt']);
    settled('soft keyboard Alt+p', page);
}

// The interceptor's other chord: an edit that stands for a key.
{
    const page = newPage();
    const input = intercept(page);
    page.window.__vscodroid.ctrl = true;
    input('deleteContentBackward', null);
    cases.push(['Backspace with Ctrl latched comes up as Backspace, then Ctrl', sequence(page).join(', '),
        'keydown Backspace ctrl, keyup Backspace ctrl, keyup Control']);
    settled('soft keyboard Ctrl+Backspace', page);
}

// Input the interceptor has no chord for sends nothing, so it releases nothing.
{
    const page = newPage();
    const input = intercept(page);
    page.window.__vscodroid.shift = true;
    input('insertText', 'a');
    page.window.__vscodroid.ctrl = true;
    input('insertFromPaste', 'abc');
    cases.push(['a lone Shift and a paste dispatch nothing', sequence(page).join(', '), '']);
}

// Ctrl+Tab from the row in a file: the picker opens and takes the focus to its
// list. The chord's release goes to the editor it was typed in, outside the
// picker, and the keyboard goes down with the row, which can send the picker
// nothing more.
{
    const page = newPage();
    intercept(page);
    const picker = recentEditorsPicker(page);
    announce(page, 'Tab', 'Tab', 9, { ctrl: true });
    cases.push(['a row Ctrl+Tab in a file leaves the picker open, its list focused',
        JSON.stringify({ accepted: picker.accepted, open: picker.open, list: page.document.activeElement === picker.list }),
        JSON.stringify({ accepted: [], open: true, list: true })]);
    settled('a row Ctrl+Tab in a file', page);
}

// Ctrl+Tab from the row in the Command Palette: the picker keeps the palette's
// input box, so the chord is typed inside the container that accepts on a Ctrl
// keyup, and so is its release, which must not reach that container. Nothing
// accepts until a tap or Enter, and a second Ctrl+Tab moves the highlight down.
{
    const page = newPage();
    intercept(page);
    const picker = recentEditorsPicker(page, { overPalette: true });
    announce(page, 'Tab', 'Tab', 9, { ctrl: true });
    cases.push(['a row Ctrl+Tab in the Command Palette leaves the picker open',
        JSON.stringify({ accepted: picker.accepted, open: picker.open }), JSON.stringify({ accepted: [], open: true })]);
    announce(page, 'Tab', 'Tab', 9, { ctrl: true });
    cases.push(['and so does a second one',
        JSON.stringify({ accepted: picker.accepted, open: picker.open }), JSON.stringify({ accepted: [], open: true })]);
    cases.push(['which moves its highlight down one', picker.active, 2]);
    settled('two row Ctrl+Tabs in the Command Palette', page);
}

// A Ctrl latched for a trackpad drag inside that picker: the press is real, so
// Chromium sends the release to the focused input, which is inside the container.
{
    const page = newPage();
    intercept(page);
    const picker = recentEditorsPicker(page, { overPalette: true });
    announce(page, 'Tab', 'Tab', 9, { ctrl: true });
    realPress(page, 'ArrowLeft', 'ArrowLeft', 37, { ctrl: true });
    cases.push(['a real Ctrl+Left inside the picker accepts nothing', picker.accepted.length, 0]);
    settled('a real Ctrl+Left inside the picker', page);
}

// The real release still reaches the workbench's emitter when the interceptor
// was installed first: the page loads the interceptor at onPageFinished, and
// the emitter is created whenever the workbench first asks for it.
{
    const page = newPage({ emitter: false });
    intercept(page);
    page.attachEmitter();
    const picker = recentEditorsPicker(page, { overPalette: true });
    realPress(page, 'ArrowLeft', 'ArrowLeft', 37, { alt: true });
    announce(page, 'Tab', 'Tab', 9, { ctrl: true });
    realPress(page, 'ArrowRight', 'ArrowRight', 39, { ctrl: true });
    cases.push(['installed before the emitter, the real releases accept nothing', picker.accepted.length, 0]);
    settled('installed before the emitter', page);
}

// A hardware keyboard holding both Shifts: each release follows the keydown of
// its own key, so both go on to the page.
{
    const page = newPage();
    intercept(page);
    let heard = 0;
    page.focused.addEventListener('keyup', (e) => { if (e.key === 'Shift') heard += 1; });
    hardwareKey(page, 'keydown', 'Shift', 'ShiftLeft', 16);
    hardwareKey(page, 'keydown', 'Shift', 'ShiftRight', 16);
    hardwareKey(page, 'keyup', 'Shift', 'ShiftLeft', 16);
    hardwareKey(page, 'keyup', 'Shift', 'ShiftRight', 16);
    cases.push(['a hardware keyboard releasing both Shifts has both releases go on', heard, 2]);
}

// The control: a hardware keyboard's Ctrl comes up as a key of its own, after
// its keydown, and that release accepts the picker as on a desktop.
{
    const page = newPage();
    intercept(page);
    const picker = recentEditorsPicker(page);
    hardwareKey(page, 'keydown', 'Control', 'ControlLeft', 17, { ctrl: true });
    hardwareKey(page, 'keydown', 'Tab', 'Tab', 9, { ctrl: true });
    hardwareKey(page, 'keyup', 'Tab', 'Tab', 9, { ctrl: true });
    hardwareKey(page, 'keyup', 'Control', 'ControlLeft', 17);
    cases.push(['a hardware keyboard releasing Ctrl still accepts the picker', JSON.stringify(picker.accepted), '[1]']);
    cases.push(['and leaves no Ctrl held', outcome(page).held.join('+'), '']);
}

/**
 * A focused text box on `page`, inside its document, fed the way Chromium
 * hands a composing keyboard's edits to one. `compose(text)` is the keyboard
 * setting its composition to `text`, which Blink's
 * InputMethodController::SetComposition turns into a compositionstart for a
 * new composition, a compositionupdate with the whole text, a `beforeinput`
 * of `insertCompositionText` that cannot be cancelled, the text put in place
 * of the composition (of the selection, for a new one) and an `input`.
 * `blur()` is FocusController::SetFocusedElement, which finishes the
 * composition first, keeping its text, with a compositionend, and then fires
 * blur. A script that sets `value` ends the composition with no event at all:
 * TextControlElement::SetInnerEditorValue replaces the text node the
 * composition range is in, the range collapses, InputMethodController's
 * HasComposition is false from then on, and the keyboard's next update starts
 * a new composition with a compositionstart (read in Blink, not measured).
 * Chromium fires a keydown of key code 229 before each update too;
 * nothing here listens for it. `heard` is what the box's own `input`
 * listener saw, and `heardBefore` its `beforeinput` listener. With
 * `selection` false the box has no selection API, as an email box has none:
 * `selectionStart` is null and `setSelectionRange` throws.
 */
function textBox(page, { value = '', className = 'input', tagName = 'TEXTAREA', selection = true } = {}) {
    page.document.body.parent = page.document;
    const box = page.newNode('text-box', page.document.body);
    const classes = className.split(' ');
    const caret = selection ? value.length : null;
    Object.assign(box, {
        tagName, selectionStart: caret, selectionEnd: caret, selectionDirection: 'none',
        classList: { contains: (c) => classes.includes(c) }, heard: [], heardBefore: [],
    });
    box.setSelectionRange = (start, end, direction) => {
        if (!selection) throw new Error(`the ${tagName} box does not support selection`);
        Object.assign(box, { selectionStart: start, selectionEnd: end, selectionDirection: direction || 'none' });
    };
    let content = value;
    let composition = null;
    Object.defineProperty(box, 'value', { get: () => content, set: (v) => { content = v; composition = null; } });
    box.composing = () => composition !== null;
    const fire = (type, init = {}) => box.dispatchEvent(new page.KeyboardEvent(type,
        { bubbles: true, cancelable: false, composed: true, ...init }));
    box.compose = (text) => {
        if (!composition) {
            fire('compositionstart', { data: '' });
            composition = selection ? [box.selectionStart, box.selectionEnd] : [box.value.length, box.value.length];
        }
        fire('compositionupdate', { data: text });
        fire('beforeinput', { inputType: 'insertCompositionText', data: text, isComposing: true });
        // A listener that took the focus away meanwhile has the text land in
        // whatever took it, which is not modelled; this box gets none of it.
        if (!composition || page.document.activeElement !== box) return;
        content = content.slice(0, composition[0]) + text + content.slice(composition[1]);
        composition = [composition[0], composition[0] + text.length];
        if (selection) box.selectionStart = box.selectionEnd = composition[1];
        fire('input', { inputType: 'insertCompositionText', data: text, isComposing: true });
    };
    box.blur = () => {
        if (page.document.activeElement !== box) return;
        if (composition) {
            const text = box.value.slice(composition[0], composition[1]);
            composition = null;
            fire('compositionend', { data: text });
        }
        page.document.activeElement = page.document.body;
        fire('blur', { bubbles: false });
    };
    box.focus = () => {
        page.document.activeElement = box;
        fire('focus', { bubbles: false });
    };
    box.addEventListener('input', () => box.heard.push(box.value));
    box.addEventListener('beforeinput', (e) => box.heardBefore.push(e.data));
    page.document.activeElement = box;
    return box;
}

/**
 * The terminal's helper textarea with xterm's handling of it, copied from the
 * shipped @xterm/xterm (lib/xterm.mjs): CompositionHelper's compositionstart,
 * compositionupdate and compositionend, its keydown, which finishes a
 * composition on any key but 229 and the modifiers, and _finalizeComposition,
 * which sends what was composed, from where the composition started, in a
 * zero timeout after a compositionend; and the terminal's own blur handler,
 * which empties the textarea, and its keyup handler, which gives the textarea
 * the focus for any key but a modifier. A key the workbench takes
 * (`workbench`, as VS Code's custom key handler does for a command it keeps
 * from the shell, which it is asked again for the keyup) goes no further.
 * Ctrl+C is sent as ^C. `sent` is what reaches the shell.
 */
function terminal(page, workbench = () => false) {
    const box = textBox(page, { className: 'xterm-helper-textarea' });
    const sent = [];
    const h = { composing: false, sending: false, start: 0, end: 0, suffix: '' };
    const finalize = (wait) => {
        h.composing = false;
        if (!wait) {
            h.sending = false;
            sent.push(box.value.substring(h.start, h.end));
            return;
        }
        const at = { start: h.start, end: h.end };
        const suffix = h.suffix;
        h.sending = true;
        page.context.setTimeout(() => {
            if (!h.sending) return;
            h.sending = false;
            let r;
            if (h.composing) {
                r = box.value.substring(at.start, h.start);
            } else {
                const v = box.value;
                const o = suffix.length > 0 && v.endsWith(suffix) ? v.length - suffix.length : v.length;
                r = v.substring(at.start, Math.max(at.start, o));
            }
            if (r.length > 0) sent.push(r);
        });
    };
    box.addEventListener('compositionstart', () => {
        h.composing = true;
        h.start = Math.min(box.selectionStart, box.selectionEnd);
        h.end = Math.max(box.selectionStart, box.selectionEnd);
        h.suffix = box.value.substring(h.end);
    });
    box.addEventListener('compositionupdate', () => {
        page.context.setTimeout(() => { h.end = Math.max(h.start, box.selectionEnd); });
    });
    box.addEventListener('compositionend', () => finalize(true));
    box.addEventListener('keydown', (e) => {
        if (workbench(e)) return;
        if (h.composing || h.sending) {
            if ([20, 229, 16, 17, 18].includes(e.keyCode)) return;
            finalize(false);
        }
        if (e.ctrlKey && e.key === 'c') sent.push('\x03');
    }, true);
    box.addEventListener('keyup', (e) => {
        if (workbench(e) || [16, 17, 18, 91, 92, 93, 224].includes(e.keyCode) || e.key === 'Meta') return;
        box.focus();
    }, true);
    box.addEventListener('blur', () => { box.value = ''; });
    box.sent = () => sent.filter((d) => d !== '');
    return box;
}

/** The keydowns and keyups a case's page saw at `at`, as `sequence` writes them. */
const keys = (page, at = 'text-box') => sequence(page).filter((line, i) => page.log[i].at === at && /^key/.test(line));

// A composing keyboard in a text box, Gboard 12.4's way with every letter:
// Ctrl latched, then `p`. The letter is the box's before anything can refuse
// it, so the chord comes once the composition is over, and the box gets its
// text back.
{
    const page = newPage();
    intercept(page);
    const box = textBox(page);
    let atChord = null;
    box.addEventListener('keydown', (e) => { if (e.key === 'p') atChord = [box.value, box.composing()]; });
    page.window.__vscodroid.ctrl = true;
    box.compose('p');
    cases.push(['a composed letter with Ctrl latched sends nothing while the letter goes in', keys(page).join(', '), '']);
    page.runTimers();
    cases.push(['then Ctrl+P, and Ctrl comes up', keys(page).join(', '), 'keydown p ctrl, keyup p ctrl, keyup Control']);
    cases.push(['and the box has its text back, with no composition, before the chord',
        JSON.stringify(atChord), JSON.stringify(['', false])]);
    cases.push(['and the box is as it was before the letter',
        JSON.stringify([box.value, box.selectionStart, box.selectionEnd]), JSON.stringify(['', 0, 0])]);
    cases.push(['and the box never heard the letter', JSON.stringify([box.heardBefore, box.heard]), '[[],[]]']);
    cases.push(['and Ctrl is spent', page.window.__vscodroid.ctrl, false]);
    cases.push(['and the box keeps the focus throughout',
        page.document.activeElement === box && !page.log.some((e) => e.at === 'text-box' && e.type === 'blur'), true]);
    settled('a composed Ctrl+P', page);
}

// A box that commits what it holds when it loses the focus, and closes, as
// the debug view's Watch and Set Value boxes, the breakpoint boxes, the
// terminal tab rename box and the Ports view's do in the shipped workbench.
// Ctrl then `a` over a composing `foo` leaves it open with `foo`, sends it
// Ctrl+A and spends the latch. A blur there committed `fooa` and closed the
// box, and the chord, sent at a box no longer in the document, never came.
{
    const page = newPage();
    intercept(page);
    const box = textBox(page, { tagName: 'INPUT' });
    const committed = [];
    box.addEventListener('blur', () => {
        committed.push(box.value);
        box.parent = null;
        box.connected = false;
    });
    for (const text of ['f', 'fo', 'foo']) box.compose(text);
    page.window.__vscodroid.ctrl = true;
    box.compose('fooa');
    page.runTimers();
    cases.push(['Ctrl then a over a word in a box that commits on blur is Ctrl+A, and commits nothing',
        JSON.stringify([keys(page).filter((k) => k.startsWith('keydown')), committed, box.value]),
        JSON.stringify([['keydown a ctrl'], [], 'foo'])]);
    cases.push(['and spends the latch', page.window.__vscodroid.ctrl, false]);
}

// Over a word the keyboard is still composing, as when the latch comes in the
// middle of typing: the word stays as typed and only the letter is the chord.
{
    const page = newPage();
    intercept(page);
    const box = textBox(page);
    box.compose('f');
    box.compose('fo');
    page.window.__vscodroid.ctrl = true;
    box.compose('fop');
    page.runTimers();
    cases.push(['a letter added to a composing word with Ctrl latched is Ctrl+P', keys(page).join(', '),
        'keydown p ctrl, keyup p ctrl, keyup Control']);
    cases.push(['and leaves the word as typed, the caret after it',
        JSON.stringify([box.value, box.selectionStart, box.selectionEnd]), JSON.stringify(['fo', 2, 2])]);
    cases.push(['and the box heard the word and not the letter', JSON.stringify([box.heardBefore, box.heard]),
        JSON.stringify([['f', 'fo'], ['f', 'fo']])]);
}

// The next letter with Ctrl latched again is a chord of its own, as Ctrl+A
// and then Ctrl+C select the box's text and copy it.
{
    const page = newPage();
    intercept(page);
    const box = textBox(page);
    for (const letter of ['a', 'c']) {
        page.window.__vscodroid.ctrl = true;
        box.compose(letter);
        page.runTimers();
    }
    cases.push(['two composed letters, each with Ctrl latched, are two chords',
        keys(page).filter((k) => k.startsWith('keydown')).join(', '), 'keydown a ctrl, keydown c ctrl']);
}

// A capital is a shifted key, as on the insertText route: Ctrl+Shift+P.
{
    const page = newPage();
    intercept(page);
    const box = textBox(page);
    page.window.__vscodroid.ctrl = true;
    box.compose('P');
    page.runTimers();
    cases.push(['a composed capital with Ctrl latched is Ctrl+Shift+P', keys(page)[0], 'keydown P ctrl shift']);
}

// What is not one character more is left to the page and spends the latch:
// a word the keyboard rewrote, and a character with no key on a US layout.
{
    const page = newPage();
    intercept(page);
    const box = textBox(page);
    box.compose('teh');
    page.window.__vscodroid.alt = true;
    box.compose('them');
    page.window.__vscodroid.ctrl = true;
    box.compose('them\u00e9');
    page.runTimers();
    cases.push(['a rewritten word and a letter with no key are typed, with no chord',
        JSON.stringify([box.value, keys(page)]), JSON.stringify(['them\u00e9', []])]);
    cases.push(['and spend the latch', JSON.stringify([page.window.__vscodroid.ctrl, page.window.__vscodroid.alt]),
        JSON.stringify([false, false])]);
}

// A box with no selection API, as an email box: its selection cannot be put
// back, so the letter is typed, with no chord, and spends the latch, as on
// the insertText route a character with no chord does.
{
    const page = newPage();
    intercept(page);
    const box = textBox(page, { tagName: 'INPUT', selection: false });
    page.window.__vscodroid.ctrl = true;
    box.compose('p');
    page.runTimers();
    cases.push(['a box with no selection API keeps a composed letter, with no chord, and spends the latch',
        JSON.stringify([box.value, keys(page), page.window.__vscodroid.ctrl]), JSON.stringify(['p', [], false])]);
}

// The editor's own textarea host reads compositions itself and is left alone.
{
    const page = newPage();
    intercept(page);
    const box = textBox(page, { className: 'inputarea monaco-mouse-cursor-text' });
    page.window.__vscodroid.ctrl = true;
    box.compose('p');
    page.runTimers();
    cases.push(["the editor's textarea keeps the letter, with no chord",
        JSON.stringify([box.value, keys(page)]), JSON.stringify(['p', []])]);
}

// Nothing latched: the composition is the page's, untouched.
{
    const page = newPage();
    intercept(page);
    const box = textBox(page);
    box.compose('p');
    page.runTimers();
    cases.push(['with nothing latched a composed letter is just typed',
        JSON.stringify([box.value, box.heard, keys(page)]), JSON.stringify(['p', ['p'], []])]);
}

// A second update before the chord's task, the keyboard faster than the
// page: it is left to the page, and nothing is taken out of the box.
{
    const page = newPage();
    intercept(page);
    const box = textBox(page);
    page.window.__vscodroid.ctrl = true;
    box.compose('p');
    box.compose('pk');
    page.runTimers();
    cases.push(['an update before the chord is typed, and the chord still follows',
        JSON.stringify([box.value, keys(page)[0]]), JSON.stringify(['pk', 'keydown p ctrl'])]);
}

// The same between the task that ends the composition and the chord's: a
// word the keyboard starts then is the page's too, with Ctrl still latched,
// and the chord is still the only one.
{
    const page = newPage();
    intercept(page);
    const box = textBox(page);
    page.window.__vscodroid.ctrl = true;
    box.compose('p');
    page.runTimer();
    box.compose('k');
    page.runTimers();
    cases.push(['a word started before the chord is typed, and the chord follows alone',
        JSON.stringify([box.value, keys(page).filter((k) => k.startsWith('keydown'))]),
        JSON.stringify(['k', ['keydown p ctrl']])]);
}

// The terminal: Ctrl then `c` at an empty prompt is ^C and nothing else.
{
    const page = newPage();
    intercept(page);
    const term = terminal(page);
    page.window.__vscodroid.ctrl = true;
    term.compose('c');
    page.runTimers();
    cases.push(['a composed Ctrl+C in the terminal sends ^C alone', JSON.stringify(term.sent()), JSON.stringify(['\x03'])]);
}

// Over a word the terminal is still composing: the word reaches the shell,
// then ^C, as `ab` then Ctrl+C would from a keyboard that commits.
{
    const page = newPage();
    intercept(page);
    const term = terminal(page);
    term.compose('a');
    term.compose('ab');
    page.window.__vscodroid.ctrl = true;
    term.compose('abc');
    page.runTimers();
    cases.push(['a composed Ctrl+C over `ab` in the terminal sends ab, then ^C',
        JSON.stringify(term.sent()), JSON.stringify(['ab', '\x03'])]);
}

// A chord the workbench takes from the terminal never reaches xterm's
// composition handling, so only the compositionend sends the word, read back
// from the textarea in a zero timeout. Ctrl+P is one, and it takes the focus
// at once: the keybinding service, a bubble listener on the window, runs
// Quick Open, whose show focuses its input box before the keydown is over,
// and the textarea empties itself as it loses the focus.
//
// The chord's keyup still goes to the textarea, where its keydown went, and
// there the workbench lets it through: with Quick Open showing, Ctrl+P
// resolves to quickOpenNavigateNextInFilePicker, which is not one it keeps
// from the shell. So xterm gives the textarea the focus back, and Quick Open,
// which closes when it loses the focus, closes again at once, as it does on
// main for a Ctrl+P from the row in the terminal.
{
    const page = newPage();
    intercept(page);
    const term = terminal(page, (e) => e.ctrlKey && e.key === 'p' && page.document.activeElement !== quickOpen);
    const quickOpen = page.newNode('quick-input-box', page.document.body);
    page.window.addEventListener('keydown', (e) => {
        if (!e.ctrlKey || e.key !== 'p') return;
        term.blur();
        page.document.activeElement = quickOpen;
    });
    let atKeyup = null;
    page.window.addEventListener('keyup', (e) => { if (e.key === 'p') atKeyup = page.document.activeElement; }, true);
    term.compose('a');
    term.compose('ab');
    page.window.__vscodroid.ctrl = true;
    term.compose('abp');
    page.runTimers();
    cases.push(['a chord the workbench takes from the terminal, focusing Quick Open, still lets ab reach the shell',
        JSON.stringify(term.sent()), JSON.stringify(['ab'])]);
    cases.push(['and Quick Open has the focus when the chord comes up', atKeyup === quickOpen, true]);
    cases.push(["and the terminal takes it back on the chord's keyup", page.document.activeElement === term, true]);
}

cases.push(['no timer threw', timerErrors.join('; '), '']);

let failed = 0;
for (const [name, got, want] of cases) {
    if (got === want) {
        console.log(`ok   ${name}`);
    } else {
        failed += 1;
        console.error(`FAIL ${name}: got ${JSON.stringify(got)}, want ${JSON.stringify(want)}`);
    }
}
console.log(`\n${cases.length - failed}/${cases.length} passed`);
process.exit(failed ? 1 : 0);
