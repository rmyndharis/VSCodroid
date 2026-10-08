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
 * which accepts on a modifier's keyup in the picker's own container. Two row
 * Ctrl+Tabs, and a real Ctrl+Left from the trackpad, must leave it open, and
 * a hardware keyboard's own Ctrl release must still accept it. The real press
 * is modelled as the events Chromium hands the page for `navigationKeyEvents`.
 * A keyboard holding both Shifts must have each release go on to the page.
 *
 * NEGATIVE CONTROL, measured: against KeyInjector.kt at 54352514, which sent
 * no release, 19 of the 42 cases fail; against one that released at the
 * chord's target with nothing stopping it at the window, the 3 picker cases
 * fail, the second Ctrl+Tab accepting the editor it had just highlighted; and
 * against one that paired a release with a keydown by key rather than by
 * code, the case of a keyboard holding both Shifts fails.
 * Each of these changes fails at least one case: not calling the release from
 * either script, or from either of the interceptor's two chords; releasing
 * with the flag still set; sending the keyup at what has focus rather than at
 * the chord's own target; releasing in another order; and in the interceptor,
 * no stop at the window, a stop in the bubble phase, stopImmediatePropagation
 * or preventDefault in its place, or a stop that ignores the keydowns a
 * keyboard sends first.
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
    page.context = vm.createContext({
        window,
        document,
        KeyboardEvent,
        navigator: { userAgent: 'Chrome/153.0.0.0' },
        setTimeout: () => {},
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
 * Ctrl+Tab; its input is hidden, so `update` gives the list DOM focus. While it
 * is open, Ctrl+Tab runs `quickOpenNavigateNextInEditorPicker`, which moves the
 * highlight down and sets quick navigate again. `registerQuickNavigation` is a
 * keyup listener on the widget's container, which accepts the highlighted
 * editor on the keyup of a modifier a quick navigate keybinding holds; minified:
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
function recentEditorsPicker(page) {
    const container = page.newNode('quick-input-widget', page.document.body);
    const list = page.newNode('quick-input-list', container);
    const picker = { open: false, active: -1, accepted: [], quickNavigate: null };
    page.window.addEventListener('keydown', (e) => {
        if (e.key !== 'Tab' || !e.ctrlKey || e.altKey || e.shiftKey || e.metaKey) return;
        if (picker.open) {
            picker.active += 1;
        } else {
            Object.assign(picker, { open: true, active: 1 });
            page.document.activeElement = list;
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

// Ctrl+Tab twice from the row: the picker opens, the highlight moves down, and
// nothing accepts until a tap or Enter. The second chord is typed with focus on
// the picker's list, inside the container that accepts on a Ctrl keyup, so its
// release must not reach that container.
{
    const page = newPage();
    intercept(page);
    const picker = recentEditorsPicker(page);
    announce(page, 'Tab', 'Tab', 9, { ctrl: true });
    announce(page, 'Tab', 'Tab', 9, { ctrl: true });
    cases.push(['two row Ctrl+Tabs leave the recently used editors picker open',
        JSON.stringify({ accepted: picker.accepted, open: picker.open }), JSON.stringify({ accepted: [], open: true })]);
    cases.push(['and the second moves its highlight down one', picker.active, 2]);
    settled('two row Ctrl+Tabs', page);
}

// A Ctrl latched for a trackpad drag inside the picker: the press is real, so
// Chromium sends the release to the list, which is inside the container.
{
    const page = newPage();
    intercept(page);
    const picker = recentEditorsPicker(page);
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
    const picker = recentEditorsPicker(page);
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
