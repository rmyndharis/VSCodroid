'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../android/app/src/main/kotlin/com/vscodroid/MainActivityPageScripts.kt'), 'utf8');
const method = source.split('internal fun MainActivity.injectTerminalTypedCharacters()')[1];
const script = method ? method.split('"""')[1] : '';

function page(patched = true) {
    let clock = 0, next = 0;
    const tasks = new Map(), listeners = {};
    const window = {
        addEventListener(type, fn, capture) { (listeners[type] ||= []).push({fn, capture}); },
        setTimeout(fn, delay = 0, ...args) { const id = ++next; tasks.set(id, {fn, at: clock + Number(delay), args}); return id; },
        clearTimeout(id) { tasks.delete(id); },
    };
    function dispatch(type, target, {trusted = true, code = 229} = {}, body = () => {}) {
        const e = {type, target, isTrusted: trusted, keyCode: code, eventPhase: 1};
        for (const l of listeners[type] || []) if (l.capture) l.fn(e);
        e.eventPhase = 2; body(e);
        e.eventPhase = 3;
        for (const l of listeners[type] || []) if (!l.capture) l.fn(e);
        e.eventPhase = 0;
    }
    function advance(ms) {
        clock += ms;
        for (const [id, task] of [...tasks]) if (task.at <= clock && tasks.delete(id)) task.fn.apply(window, task.args);
    }
    const context = {window};
    if (patched && script) vm.runInNewContext(script, context);
    const ta = {value: '', classList: {contains: c => c === 'xterm-helper-textarea'}};
    const sent = [];
    // The shipped helper snapshots at keydown; its input handler skips keys whose keydown was seen.
    const model = {_textareaChangeTimer: undefined, calls: 0, composing: false};
    function key(options) {
        dispatch('keydown', ta, options, () => {
            if (model.composing) return;
            if (model._textareaChangeTimer) return;
            const before = ta.value;
            model._textareaChangeTimer = window.setTimeout(() => {
                model._textareaChangeTimer = undefined;
                model.calls++;
                if (model.composing) return;
                const after = ta.value, added = after.replace(before, '');
                if (after.length > before.length) sent.push(added);
                else if (after.length < before.length) sent.push('\x7f');
                else if (after !== before) sent.push(after);
            }, 0);
        });
    }
    function specialKey(code, options = {}) {
        dispatch('keydown', ta, {code, ...options}, () => {
            if (model.composing) {
                model.composing = false;
                sent.push(ta.value);
            }
            // CoreBrowserTerminal clears the field before sending Enter; the
            // other keys below take the ordinary key data path without a timer.
            if (code === 13) ta.value = '';
            const data = {13: '\r', 8: '\x7f', 9: '\t', 37: '\x1b[D'}[code];
            if (data) sent.push(data);
        });
    }
    function input(value) { ta.value = value; dispatch('input', ta); }
    return {window, ta, sent, model, key, specialKey, input, advance, dispatch, tasks, context};
}

// Negative control: the original timer wins the race and the input is never sent.
{
    const p = page(false); p.key(); p.advance(0); p.input('a'); p.advance(100);
    assert.deepEqual(p.sent, []);
}
{
    const p = page(); p.key(); p.input('a'); p.advance(100);
    assert.deepEqual(p.sent, ['a']); assert.equal(p.model.calls, 1);
}
{
    const p = page(); p.key(); p.advance(0); p.input('a'); p.advance(100);
    assert.deepEqual(p.sent, ['a'], 'timer before input must not drop the character');
    assert.equal(p.model.calls, 1);
}
{
    const p = page(); p.key(); p.advance(99); assert.equal(p.model.calls, 0);
    p.advance(1); assert.equal(p.model.calls, 1); assert.deepEqual(p.sent, []);
}
{
    const p = page(); p.key(); p.input('a'); p.key(); p.input('ab'); p.advance(100);
    assert.deepEqual(p.sent, ['a', 'b']); assert.equal(p.model.calls, 2);
}
{
    const p = page(); p.ta.value = 'ab'; p.key(); p.advance(0); p.input('a'); p.advance(100);
    assert.deepEqual(p.sent, ['\x7f']);
}
{
    const p = page(); p.key(); p.window.clearTimeout(p.model._textareaChangeTimer);
    p.input('a'); p.advance(100); assert.equal(p.model.calls, 0);
}
for (const options of [{trusted:false}, {code:65}]) {
    const p = page(); p.key(options); p.advance(0); assert.equal(p.model.calls, 1);
}
for (const delay of [1, null, false, '0']) {
    const p = page(); let calls = 0;
    p.dispatch('keydown', p.ta, {}, () => p.window.setTimeout(function _textareaChangeTimer() { calls++; }, delay));
    p.advance(1); assert.equal(calls, 1);
}
{
    const p = page(); let calls = 0;
    function _textareaChangeTimer() { calls++; }
    p.window.setTimeout(_textareaChangeTimer, 0); p.advance(0); assert.equal(calls, 1);
    p.dispatch('keydown', {classList:{contains:()=>false}}, {}, () => p.window.setTimeout(_textareaChangeTimer));
    p.advance(0); assert.equal(calls, 2);
    p.dispatch('keydown', p.ta, {}, () => p.window.setTimeout(() => calls++, 0));
    p.advance(0); assert.equal(calls, 3, 'renamed or unrelated callbacks pass through');
}
{
    const p = page(); let receiver, args;
    function _textareaChangeTimer(...values) { receiver = this; args = values; }
    let id;
    p.dispatch('keydown', p.ta, {}, () => { id = p.window.setTimeout(_textareaChangeTimer, undefined, 'a', 7); });
    assert.equal(typeof id, 'number'); p.input('a'); p.advance(100);
    assert.equal(receiver, p.window); assert.deepEqual(args, ['a', 7]);
}
{
    const p = page(); let calls = 0;
    p.dispatch('keydown', p.ta, {}, () => {
        p.window.setTimeout(function _textareaChangeTimer() { calls++; }, 0);
        p.window.setTimeout(function _textareaChangeTimer() { calls++; }, 0);
    });
    p.advance(0); assert.equal(calls, 1); p.input('a'); assert.equal(calls, 2);
}
{
    const p = page(); p.key();
    p.dispatch('input', {classList:{contains:()=>true}}); assert.equal(p.model.calls, 0);
    p.input('a'); assert.equal(p.model.calls, 1);
    const installed = p.window.setTimeout;
    vm.runInNewContext(script, p.context); assert.equal(p.window.setTimeout, installed);
}
assert.ok(script, 'terminal typing injector must exist');
assert.match(source.slice(source.indexOf('internal fun MainActivity.injectBridgeToken()'), source.indexOf('internal fun MainActivity.injectSafeAreaCSS()')), /injectTerminalTypedCharacters\(\)/);
const failures = [];
function row(name, test) {
    try { test(); console.log(`ok: ${name}`); }
    catch (error) { failures.push(name); console.error(`FAIL: ${name}\n${error.message}`); }
}
for (const ms of [0, 20, 60, 99]) {
    row(`Enter then a letter at ${ms} ms`, () => {
        const p = page(); p.ta.value = 'ab'; p.key(); p.specialKey(13);
        p.advance(ms); p.key(); p.input('c'); p.advance(100);
        assert.deepEqual(p.sent, ['\r', 'c']);
        assert.equal(p.model.calls, 2, 'Enter comparison finishes before the letter arms its own');
    });
}
row('two Enters send exactly two carriage returns', () => {
    const p = page(); p.ta.value = 'ab'; p.key(); p.specialKey(13);
    p.advance(20); p.key(); p.specialKey(13); p.advance(100);
    assert.deepEqual(p.sent, ['\r', '\r']);
    assert.equal(p.model.calls, 2);
});
row('Enter then Backspace sends only the requested deletion', () => {
    const p = page(); p.ta.value = 'ab'; p.key(); p.specialKey(13);
    p.advance(20); p.key(); p.specialKey(8); p.advance(100);
    assert.deepEqual(p.sent, ['\r', '\x7f']);
});
row('a letter visible during its hold is sent before Enter clears the field', () => {
    const p = page(); p.key(); p.ta.value = 'a'; p.advance(20);
    p.specialKey(13); p.dispatch('input', p.ta); p.advance(100);
    assert.deepEqual(p.sent, ['a', '\r']);
    assert.equal(p.model.calls, 1);
});
for (const code of [9, 37, 8, 0]) {
    row(`ordinary key ${code} finishes the comparison before the key is handled`, () => {
        const p = page(); p.ta.value = 'ab'; p.key(); p.specialKey(code);
        assert.equal(p.model.calls, 1);
        p.advance(100);
        assert.deepEqual(p.sent, code === 0 ? [] : [{9: '\t', 37: '\x1b[D', 8: '\x7f'}[code]]);
    });
}
row('a key-row event finishes the hold without requiring trust', () => {
    const p = page(); p.ta.value = 'ab'; p.key(); p.specialKey(13, {trusted: false});
    p.advance(100); assert.deepEqual(p.sent, ['\r']);
});
row('a key in another field does not finish this terminal hold', () => {
    const p = page(); p.key(); p.dispatch('keydown', {}, {code: 13});
    assert.equal(p.model.calls, 0);
    p.input('a'); assert.deepEqual(p.sent, ['a']);
});
row('another 229 still waits for input', () => {
    const p = page(); p.key(); p.key(); assert.equal(p.model.calls, 0);
    p.input('a'); p.advance(100); assert.deepEqual(p.sent, ['a']);
});
row('a composing keyboard has no comparison timer to release on Enter', () => {
    const p = page(); p.model.composing = true; p.ta.value = 'word'; p.key();
    assert.equal(p.model._textareaChangeTimer, undefined);
    p.specialKey(13); p.advance(100);
    assert.deepEqual(p.sent, ['word', '\r']); assert.equal(p.model.calls, 0);
});
row('a composition started after 229 skips the held comparison before Enter finalizes it', () => {
    const p = page(); p.key(); p.model.composing = true; p.ta.value = 'word';
    p.specialKey(13); p.advance(100);
    assert.deepEqual(p.sent, ['word', '\r']); assert.equal(p.model.calls, 1);
});

// Faithful model of xterm CompositionHelper (lines 65-237) + CoreBrowserTerminal key/input handling
// running in a window-scoped vm context so both `setTimeout` and `window.setTimeout` hit the injector.
function compositionPage(patched = true) {
    let clock = 0, next = 0;
    const tasks = new Map(), windowListeners = {}, taListeners = {};
    const sandbox = {
        Math, Number, Array, Function, Object, String, Error,
        addEventListener(type, fn, capture = false) {
            (windowListeners[type] ||= []).push({fn, capture: !!capture});
        },
        setTimeout(fn, delay = 0, ...args) {
            const id = ++next;
            tasks.set(id, {fn, at: clock + Number(delay || 0), args});
            return id;
        },
        clearTimeout(id) { tasks.delete(id); },
    };
    sandbox.window = sandbox;
    const ctx = vm.createContext(sandbox);
    if (patched && script) vm.runInContext(script, ctx);

    const ta = {
        value: '',
        selectionStart: 0,
        selectionEnd: 0,
        style: {},
        classList: {contains: c => c === 'xterm-helper-textarea'},
        addEventListener(type, fn, capture = false) {
            (taListeners[type] ||= []).push({fn, capture: !!capture});
        },
    };
    const sent = [], rawTriggers = [];

    function dispatch(type, target, {trusted = true, keyCode = 0, data, inputType, composed} = {}, mutate = null) {
        const e = {type, target, isTrusted: trusted, keyCode, data, inputType, composed, eventPhase: 1};
        for (const l of windowListeners[type] || []) if (l.capture) l.fn(e);
        if (target === ta) {
            e.eventPhase = 2;
            if (mutate) mutate(e);
            for (const l of taListeners[type] || []) if (l.capture) l.fn(e);
            for (const l of taListeners[type] || []) if (!l.capture) l.fn(e);
        } else if (mutate) {
            e.eventPhase = 2;
            mutate(e);
        }
        e.eventPhase = 3;
        for (const l of windowListeners[type] || []) if (!l.capture) l.fn(e);
        e.eventPhase = 0;
    }

    function advance(ms) {
        clock += ms;
        while (true) {
            let due = null;
            for (const [id, task] of tasks) {
                if (task.at <= clock) { due = [id, task]; break; }
            }
            if (!due) break;
            tasks.delete(due[0]);
            due[1].fn.apply(sandbox, due[1].args);
        }
    }

    function bindXtermInRealm(el, rawTriggers, sent, onTrigger) {
        const _compositionView = {textContent: '', style: {}, classList: {add() {}, remove() {}}};
        let _isComposing = false;
        let _isSendingComposition = false;
        let _compositionPosition = {start: 0, end: 0};
        let _compositionSuffix = '';
        let _dataAlreadySent = '';
        let _textareaChangeTimer = undefined;
        let _keyDownSeen = false;

        let updateRecursionRuns = 0;
        function updateCompositionElements(dontRecurse) {
            if (!dontRecurse) setTimeout(() => updateCompositionElements(true), 0);
            else updateRecursionRuns++;
        }
        function triggerDataEvent(d) {
            rawTriggers.push(d);
            if (d.length > 0) sent.push(d);
            onTrigger(d);
        }
        function _finalizeComposition(waitForPropagation) {
            _compositionView.classList.remove('active');
            _isComposing = false;
            if (!waitForPropagation) {
                _isSendingComposition = false;
                const input = el.value.substring(_compositionPosition.start, _compositionPosition.end);
                triggerDataEvent(input);
            } else {
                const currentCompositionPosition = {
                    start: _compositionPosition.start,
                    end: _compositionPosition.end,
                };
                const currentCompositionSuffix = _compositionSuffix;
                _isSendingComposition = true;
                setTimeout(() => {
                    if (_isSendingComposition) {
                        _isSendingComposition = false;
                        let input;
                        currentCompositionPosition.start += _dataAlreadySent.length;
                        if (_isComposing) {
                            input = el.value.substring(currentCompositionPosition.start, _compositionPosition.start);
                        } else {
                            const value = el.value;
                            const valueEnd = currentCompositionSuffix.length > 0 && value.endsWith(currentCompositionSuffix)
                                ? value.length - currentCompositionSuffix.length
                                : value.length;
                            input = value.substring(currentCompositionPosition.start, Math.max(currentCompositionPosition.start, valueEnd));
                        }
                        if (input.length > 0) triggerDataEvent(input);
                    }
                }, 0);
            }
        }
        function _handleAnyTextareaChanges() {
            if (_textareaChangeTimer) return;
            const oldValue = el.value;
            _textareaChangeTimer = window.setTimeout(() => {
                _textareaChangeTimer = undefined;
                if (!_isComposing) {
                    const newValue = el.value;
                    const diff = newValue.replace(oldValue, '');
                    _dataAlreadySent = diff;
                    if (newValue.length > oldValue.length) triggerDataEvent(diff);
                    else if (newValue.length < oldValue.length) triggerDataEvent('\x7f');
                    else if (newValue.length === oldValue.length && newValue !== oldValue) triggerDataEvent(newValue);
                }
            }, 0);
        }
        function compositionstart() {
            _isComposing = true;
            const start = el.selectionStart ?? el.value.length;
            const end = el.selectionEnd ?? start;
            _compositionPosition.start = Math.min(start, end);
            _compositionPosition.end = Math.max(start, end);
            _compositionSuffix = el.value.substring(_compositionPosition.end);
            _compositionView.textContent = '';
            _dataAlreadySent = '';
        }
        function compositionupdate(ev) {
            _compositionView.textContent = '\u200E' + (ev.data || '') + '\u200E';
            updateCompositionElements();
            setTimeout(() => {
                const end = el.selectionEnd ?? el.value.length;
                _compositionPosition.end = Math.max(_compositionPosition.start, end);
            }, 0);
        }
        function compositionend() { _finalizeComposition(true); }
        function keydown(ev) {
            _keyDownSeen = true;
            if (_isComposing || _isSendingComposition) {
                if (ev.keyCode === 20 || ev.keyCode === 229) return false;
                if (ev.keyCode === 16 || ev.keyCode === 17 || ev.keyCode === 18) return false;
                _finalizeComposition(false);
            }
            if (ev.keyCode === 229) {
                _handleAnyTextareaChanges();
                return false;
            }
            if (ev.keyCode === 13) {
                el.value = '';
                el.selectionStart = 0;
                el.selectionEnd = 0;
                triggerDataEvent('\r');
            } else if (ev.keyCode === 8) {
                triggerDataEvent('\x7f');
            } else if (ev.keyCode === 9) {
                triggerDataEvent('\t');
            }
            return true;
        }
        function inputEvent(ev) {
            if (ev.data && ev.inputType === 'insertText' && (!ev.composed || !_keyDownSeen)) {
                triggerDataEvent(ev.data);
                return true;
            }
            return false;
        }
        el.addEventListener('keydown', keydown, true);
        el.addEventListener('compositionstart', compositionstart, false);
        el.addEventListener('compositionupdate', compositionupdate, false);
        el.addEventListener('compositionend', compositionend, false);
        el.addEventListener('input', inputEvent, true);
        // CoreBrowserTerminal._handleTextAreaBlur, on the field itself: the field is emptied at every blur.
        el.addEventListener('blur', () => { el.value = ''; el.selectionStart = 0; el.selectionEnd = 0; }, false);
        return {
            state: () => ({_isComposing, _isSendingComposition, _compositionPosition: {..._compositionPosition}, _dataAlreadySent, hasChangeTimer: !!_textareaChangeTimer, updateRecursionRuns}),
        };
    }

    const helper = vm.runInContext(`(${bindXtermInRealm.toString()})`, ctx)(ta, rawTriggers, sent, () => {});
    const sendKey229 = () => dispatch('keydown', ta, {keyCode: 229});
    const sendEnter = () => dispatch('keydown', ta, {keyCode: 13});
    const compStart = () => dispatch('compositionstart', ta, {data: ''});
    const compUpdate = (data, selStart = ta.selectionStart, selEnd = ta.selectionEnd) => {
        ta.selectionStart = selStart;
        ta.selectionEnd = selEnd;
        dispatch('compositionupdate', ta, {data});
    };
    const compInput = (value, data) => {
        ta.value = value;
        ta.selectionStart = value.length;
        ta.selectionEnd = value.length;
        dispatch('input', ta, {data, inputType: 'insertCompositionText', composed: true});
    };
    const compEnd = (data, trusted = true) => dispatch('compositionend', ta, {data, trusted});
    const blur = (target = ta) => dispatch('blur', target, {});
    // `ends` lists the compositionend events each recorded one becomes, by their trust, in order: Android's WebView
    // delivers one, untrusted ([false]); a WebView that marks it trusted, or sends two, is the other cases.
    const replayEntries = (entries, {drainAtRaf = true, drainGapMs = null, finishAdvance = 100, ends = [true]} = {}) => {
        for (let i = 0; i < entries.length; i++) {
            const ev = entries[i];
            if (i > 0 && drainGapMs !== null) {
                const gap = ev.t - entries[i - 1].t;
                if (gap >= drainGapMs) advance(Math.floor(gap));
            }
            if (ev.type === 'raf') {
                if (drainAtRaf) advance(0);
                continue;
            }
            if (ev.type === 'beforeinput') continue;
            if (ev.type === 'keydown') {
                ta.value = ev.value;
                ta.selectionStart = ev.selectionStart;
                ta.selectionEnd = ev.selectionEnd;
                dispatch('keydown', ta, {keyCode: ev.keyCode});
            } else if (ev.type === 'compositionstart') {
                ta.value = ev.value;
                ta.selectionStart = ev.selectionStart;
                ta.selectionEnd = ev.selectionEnd;
                dispatch('compositionstart', ta, {data: ev.data});
            } else if (ev.type === 'compositionupdate') {
                ta.value = ev.value;
                ta.selectionStart = ev.selectionStart;
                ta.selectionEnd = ev.selectionEnd;
                dispatch('compositionupdate', ta, {data: ev.data});
            } else if (ev.type === 'input') {
                ta.value = ev.value;
                ta.selectionStart = ev.selectionStart;
                ta.selectionEnd = ev.selectionEnd;
                dispatch('input', ta, {data: ev.data, inputType: ev.inputType, composed: true});
            } else if (ev.type === 'compositionend') {
                ta.value = ev.value;
                ta.selectionStart = ev.selectionStart;
                ta.selectionEnd = ev.selectionEnd;
                for (const trusted of ends) dispatch('compositionend', ta, {data: ev.data, trusted});
            }
        }
        if (finishAdvance !== null) advance(finishAdvance);
        return sent.join('');
    };
    return {window: sandbox, ta, sent, rawTriggers, helper, advance, sendKey229, sendEnter, compStart, compUpdate, compInput, compEnd, blur, replayEntries, dispatch};
}

// The rows below replay three lines typed on a device with a Korean keyboard, each broken in its own way.
const K1_LINE2_BROKEN = [
    {seq: 586, t: 981630.8, type: 'keydown', value: '', selectionStart: 0, selectionEnd: 0, keyCode: 229},
    {seq: 587, t: 981632.5, type: 'compositionstart', value: '', selectionStart: 0, selectionEnd: 0, data: ''},
    {seq: 588, t: 981633.0, type: 'compositionupdate', value: '', selectionStart: 0, selectionEnd: 0, data: 'ㄱ'},
    {seq: 590, t: 981634.0, type: 'input', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, inputType: 'insertCompositionText', data: 'ㄱ', isComposing: true},
    {seq: 591, t: 981671.1, type: 'raf', value: 'ㄱ', selectionStart: 1, selectionEnd: 1},
    // The ㅡ tap produced zero DOM events for 2682.6 ms, then Gboard sent ㄹ over sel 0:1.
    {seq: 592, t: 984353.7, type: 'keydown', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 593, t: 984355.4, type: 'compositionupdate', value: 'ㄱ', selectionStart: 0, selectionEnd: 1, data: 'ㄹ'},
    {seq: 595, t: 984358.0, type: 'input', value: 'ㄹ', selectionStart: 1, selectionEnd: 1, inputType: 'insertCompositionText', data: 'ㄹ', isComposing: true},
    {seq: 596, t: 984361.8, type: 'keydown', value: 'ㄹ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 597, t: 984362.3, type: 'compositionupdate', value: 'ㄹ', selectionStart: 0, selectionEnd: 1, data: 'ㄹ'},
    {seq: 599, t: 984363.1, type: 'input', value: 'ㄹ', selectionStart: 1, selectionEnd: 1, inputType: 'insertCompositionText', data: 'ㄹ', isComposing: true},
    {seq: 600, t: 984363.1, type: 'compositionend', value: 'ㄹ', selectionStart: 1, selectionEnd: 1, data: 'ㄹ'},
    {seq: 601, t: 984366.3, type: 'keydown', value: 'ㄹ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 602, t: 984366.8, type: 'keydown', value: 'ㄹ', selectionStart: 1, selectionEnd: 1, keyCode: 13},
    {seq: 603, t: 984368.3, type: 'raf', value: '', selectionStart: 0, selectionEnd: 0},
];
const K1_LINE3_BROKEN = [
    {seq: 604, t: 985172.3, type: 'keydown', value: '', selectionStart: 0, selectionEnd: 0, keyCode: 229},
    {seq: 605, t: 985173.3, type: 'compositionstart', value: '', selectionStart: 0, selectionEnd: 0, data: ''},
    {seq: 606, t: 985174.2, type: 'compositionupdate', value: '', selectionStart: 0, selectionEnd: 0, data: 'ㄱ'},
    {seq: 608, t: 985175.6, type: 'input', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, inputType: 'insertCompositionText', data: 'ㄱ', isComposing: true},
    {seq: 609, t: 985176.7, type: 'keydown', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 610, t: 985177.2, type: 'compositionupdate', value: 'ㄱ', selectionStart: 0, selectionEnd: 1, data: '가'},
    {seq: 612, t: 985178.3, type: 'input', value: '가', selectionStart: 1, selectionEnd: 1, inputType: 'insertCompositionText', data: '가', isComposing: true},
    {seq: 613, t: 985178.9, type: 'keydown', value: '가', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 614, t: 985179.4, type: 'compositionupdate', value: '가', selectionStart: 0, selectionEnd: 1, data: '가'},
    {seq: 616, t: 985180.4, type: 'input', value: '가', selectionStart: 1, selectionEnd: 1, inputType: 'insertCompositionText', data: '가', isComposing: true},
    {seq: 617, t: 985180.4, type: 'compositionend', value: '가', selectionStart: 1, selectionEnd: 1, data: '가'},
    {seq: 618, t: 985181.3, type: 'keydown', value: '가', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 619, t: 985181.5, type: 'keydown', value: '가', selectionStart: 1, selectionEnd: 1, keyCode: 13},
    {seq: 620, t: 985182.6, type: 'raf', value: '', selectionStart: 0, selectionEnd: 0},
];
const K1_LINE4_BROKEN = [
    {seq: 621, t: 985925.1, type: 'keydown', value: '', selectionStart: 0, selectionEnd: 0, keyCode: 229},
    {seq: 622, t: 985928.8, type: 'compositionstart', value: '', selectionStart: 0, selectionEnd: 0, data: ''},
    {seq: 623, t: 985929.9, type: 'compositionupdate', value: '', selectionStart: 0, selectionEnd: 0, data: 'ㄴ'},
    {seq: 625, t: 985943.1, type: 'input', value: 'ㄴ', selectionStart: 1, selectionEnd: 1, inputType: 'insertCompositionText', data: 'ㄴ', isComposing: true},
    {seq: 626, t: 985944.8, type: 'raf', value: 'ㄴ', selectionStart: 1, selectionEnd: 1},
    {seq: 627, t: 986644.7, type: 'keydown', value: 'ㄴ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 628, t: 986645.4, type: 'compositionupdate', value: 'ㄴ', selectionStart: 0, selectionEnd: 1, data: 'ㅏ'},
    {seq: 630, t: 986651.3, type: 'input', value: 'ㅏ', selectionStart: 1, selectionEnd: 1, inputType: 'insertCompositionText', data: 'ㅏ', isComposing: true},
    {seq: 631, t: 986662.4, type: 'raf', value: 'ㅏ', selectionStart: 1, selectionEnd: 1},
    {seq: 632, t: 987601.7, type: 'keydown', value: 'ㅏ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 633, t: 987618.9, type: 'compositionupdate', value: 'ㅏ', selectionStart: 0, selectionEnd: 1, data: 'ㅏ'},
    {seq: 635, t: 987639.1, type: 'input', value: 'ㅏ', selectionStart: 1, selectionEnd: 1, inputType: 'insertCompositionText', data: 'ㅏ', isComposing: true},
    {seq: 636, t: 987639.2, type: 'compositionend', value: 'ㅏ', selectionStart: 1, selectionEnd: 1, data: 'ㅏ'},
    {seq: 637, t: 987641.1, type: 'keydown', value: 'ㅏ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 638, t: 987641.4, type: 'keydown', value: 'ㅏ', selectionStart: 1, selectionEnd: 1, keyCode: 13},
    {seq: 639, t: 987642.7, type: 'raf', value: '', selectionStart: 0, selectionEnd: 0},
];

row('recorded line 2 replayed (lost ㅡ tap): script and library alone both send ㄹ then CR', () => {
    const withCand = compositionPage(true);
    const libAlone = compositionPage(false);
    assert.equal(withCand.replayEntries(K1_LINE2_BROKEN, {drainAtRaf: true}), 'ㄹ\r');
    assert.equal(libAlone.replayEntries(K1_LINE2_BROKEN, {drainAtRaf: true}), 'ㄹ\r');
});
row('recorded line 4 replayed (overwritten ㄴ): script and library alone both send ㅏ then CR', () => {
    const withCand = compositionPage(true);
    const libAlone = compositionPage(false);
    assert.equal(withCand.replayEntries(K1_LINE4_BROKEN, {drainAtRaf: true}), 'ㅏ\r');
    assert.equal(libAlone.replayEntries(K1_LINE4_BROKEN, {drainAtRaf: true}), 'ㅏ\r');
});
row('recorded line 3 replayed (no timer turn before Enter): library alone loses 가; script keeps 가', () => {
    const libAlone = compositionPage(false);
    assert.equal(libAlone.replayEntries(K1_LINE3_BROKEN, {drainAtRaf: true}), '\r');
    const withCand = compositionPage(true);
    assert.equal(withCand.replayEntries(K1_LINE3_BROKEN, {drainAtRaf: true}), '가\r');
});
row('recorded line 3 replayed when compositionend timer runs before 229 and Enter beats 229 timer: library sends DEL, script keeps 가', () => {
    const splitAt = K1_LINE3_BROKEN.findIndex(e => e.seq === 618);
    const afterEnd = compositionPage(false);
    afterEnd.replayEntries(K1_LINE3_BROKEN.slice(0, splitAt), {drainAtRaf: false, finishAdvance: null});
    afterEnd.advance(0);
    assert.equal(afterEnd.replayEntries(K1_LINE3_BROKEN.slice(splitAt), {drainAtRaf: false}), '가\r\x7f');

    const withCand = compositionPage(true);
    withCand.replayEntries(K1_LINE3_BROKEN.slice(0, splitAt), {drainAtRaf: false, finishAdvance: null});
    withCand.advance(0);
    assert.equal(withCand.replayEntries(K1_LINE3_BROKEN.slice(splitAt), {drainAtRaf: false}), '가\r');
});

for (const sample of [
    {label: '2-jamo Korean syllable 가', steps: [['ㄱ', 'ㄱ'], ['가', '가']]},
    {label: '3-jamo Korean syllable 한', steps: [['ㅎ', 'ㅎ'], ['하', '하'], ['한', '한']]},
    {label: '3-jamo Korean syllable 글', steps: [['ㄱ', 'ㄱ'], ['그', '그'], ['글', '글']]},
    {label: 'English composing control cat', steps: [['c', 'c'], ['ca', 'ca'], ['cat', 'cat']]},
]) {
    const expected = sample.steps[sample.steps.length - 1][1];
    row(`${sample.label} with timer turns after compositionupdate and compositionend, Enter 1 ms later`, () => {
        // When compositionend's 0 ms timer fires before pre-Enter 229 and Enter beats 229's timer,
        // the unpatched library sends a spurious DEL while the script sends only syllable + CR.
        for (const [patched, want] of [[false, [expected, '\r', '\x7f']], [true, [expected, '\r']]]) {
            const p = compositionPage(patched);
            p.sendKey229();
            p.compStart();
            for (const [data, val] of sample.steps) {
                p.compUpdate(data, 0, p.ta.value.length);
                p.compInput(val, data);
                if (val !== expected) p.sendKey229();
                p.advance(0);
            }
            p.compEnd(expected);
            p.advance(1);
            p.sendKey229();
            p.sendEnter();
            p.advance(100);
            assert.deepEqual(p.sent, want);
        }
    });
    row(`${sample.label} with timer turn after compositionupdate only, Enter 1 ms after compositionend before compositionend timer`, () => {
        for (const patched of [false, true]) {
            const p = compositionPage(patched);
            p.sendKey229();
            p.compStart();
            for (const [data, val] of sample.steps) {
                p.compUpdate(data, 0, p.ta.value.length);
                p.compInput(val, data);
                p.advance(0);
                if (val !== expected) p.sendKey229();
            }
            p.compEnd(expected);
            p.sendKey229();
            p.sendEnter();
            p.advance(100);
            assert.deepEqual(p.sent, [expected, '\r']);
        }
    });
    row(`${sample.label} without any timer turn before Enter 1 ms after compositionend`, () => {
        // Without a 0 ms timer turn between compositionstart and Enter (+1 ms): library alone loses it
        const libBurst = compositionPage(false);
        libBurst.sendKey229();
        libBurst.compStart();
        for (const [data, val] of sample.steps) {
            libBurst.compUpdate(data, 0, libBurst.ta.value.length);
            libBurst.compInput(val, data);
            if (val !== expected) libBurst.sendKey229();
        }
        libBurst.compEnd(expected);
        libBurst.sendKey229();
        libBurst.sendEnter();
        libBurst.advance(100);
        assert.deepEqual(libBurst.sent, ['\r'], 'library alone loses the text without a timer turn');

        // Script keeps the syllable even with zero timer turns before Enter
        const candBurst = compositionPage(true);
        candBurst.sendKey229();
        candBurst.compStart();
        for (const [data, val] of sample.steps) {
            candBurst.compUpdate(data, 0, candBurst.ta.value.length);
            candBurst.compInput(val, data);
            if (val !== expected) candBurst.sendKey229();
        }
        candBurst.compEnd(expected);
        candBurst.sendKey229();
        candBurst.sendEnter();
        candBurst.advance(100);
        assert.deepEqual(candBurst.sent, [expected, '\r']);
    });
}

row('growing composition with timer turn only on first step loses tail on library alone and keeps full string with the script', () => {
    const lib = compositionPage(false);
    lib.sendKey229(); lib.compStart();
    lib.compUpdate('c', 0, 0); lib.compInput('c', 'c'); lib.advance(0);
    lib.sendKey229(); lib.compUpdate('ca', 0, 1); lib.compInput('ca', 'ca');
    lib.sendKey229(); lib.compUpdate('cat', 0, 2); lib.compInput('cat', 'cat');
    lib.compEnd('cat'); lib.sendKey229(); lib.sendEnter(); lib.advance(100);
    assert.deepEqual(lib.sent, ['c', '\r']);

    const cand = compositionPage(true);
    cand.sendKey229(); cand.compStart();
    cand.compUpdate('c', 0, 0); cand.compInput('c', 'c'); cand.advance(0);
    cand.sendKey229(); cand.compUpdate('ca', 0, 1); cand.compInput('ca', 'ca');
    cand.sendKey229(); cand.compUpdate('cat', 0, 2); cand.compInput('cat', 'cat');
    cand.compEnd('cat'); cand.sendKey229(); cand.sendEnter(); cand.advance(100);
    assert.deepEqual(cand.sent, ['cat', '\r']);
});

row('a jamo tap that produces no DOM event cannot be observed by the page; subsequent replacement is sent verbatim', () => {
    for (const patched of [false, true]) {
        const p = compositionPage(patched);
        let domEvents = 0;
        p.sendKey229(); domEvents++;
        p.compStart(); domEvents++;
        p.compUpdate('ㄱ', 0, 0); domEvents++;
        p.compInput('ㄱ', 'ㄱ'); domEvents++;
        p.advance(0);
        const beforeSilentTap = domEvents;
        // ㅡ tap produces zero DOM events on WebView
        assert.equal(domEvents, beforeSilentTap);
        p.sendKey229();
        p.compUpdate('ㄹ', 0, 1);
        p.compInput('ㄹ', 'ㄹ');
        p.advance(0);
        p.compEnd('ㄹ');
        p.sendKey229();
        p.sendEnter();
        p.advance(100);
        assert.deepEqual(p.sent, ['ㄹ', '\r']);
    }
});
row('updateCompositionElements 0 ms timer inside compositionupdate runs on advance(0) while _compositionPosition.end stays held until input', () => {
    const p = compositionPage(true);
    p.sendKey229();
    p.compStart();
    p.compUpdate('ㄱ', 0, 0);
    p.advance(0);
    assert.equal(p.helper.state().updateRecursionRuns, 1);
    assert.equal(p.helper.state()._compositionPosition.end, 0);
    p.compInput('ㄱ', 'ㄱ');
    assert.equal(p.helper.state()._compositionPosition.end, 1);
});
row('untrusted compositionupdate or compositionupdate on another field passes _compositionPosition.end through immediately', () => {
    const p = compositionPage(true);
    let untrustedCalls = 0, otherCalls = 0;
    p.dispatch('compositionupdate', p.ta, {trusted: false, data: 'a'}, () => {
        p.window.setTimeout(function() { /* _compositionPosition.end */ untrustedCalls++; }, 0);
    });
    p.advance(0);
    assert.equal(untrustedCalls, 1);
    p.dispatch('compositionupdate', {classList: {contains: () => false}}, {data: 'a'}, () => {
        p.window.setTimeout(function() { /* _compositionPosition.end */ otherCalls++; }, 0);
    });
    p.advance(0);
    assert.equal(otherCalls, 1);
});
row('compositionupdate without input still releases before a later keydown 229 so ASCII typing after compositionend stays protected', () => {
    const p = compositionPage(true);
    p.sendKey229();
    p.compStart();
    p.compUpdate('a', 0, 0);
    p.compInput('a', 'a');
    // Same-text compositionupdate with no following input event, then compositionend + 0 ms timer
    p.compUpdate('a', 0, 1);
    p.compEnd('a');
    p.advance(0);
    assert.deepEqual(p.sent, ['a']);
    // Next key is an ASCII soft key within 100 ms: must finish held composition timer so the new 229 can hold its own
    p.sendKey229();
    p.advance(0);
    p.ta.value = 'ab'; p.ta.selectionStart = 2; p.ta.selectionEnd = 2;
    p.dispatch('input', p.ta, {data: 'b', inputType: 'insertText', composed: true});
    p.advance(100);
    assert.deepEqual(p.sent, ['a', 'b']);
});

// Recorded on Android 16 with Gboard 18.5.6 (Korean 2-Bulsik). Gboard committed the first jamo of a syllable alone
// and composed the next one apart, and xterm's finalize timer for the first composition ran only after the Enter.
// 나 typed: ㄴ committed alone, then ㅏ.
const NA_FIRST_JAMO_ALONE = [
    {seq: 162, t: 675033.9, type: 'keydown', value: '', selectionStart: 0, selectionEnd: 0, keyCode: 229},
    {seq: 163, t: 675034.3, type: 'compositionstart', value: '', selectionStart: 0, selectionEnd: 0, data: ''},
    {seq: 164, t: 675035.3, type: 'compositionupdate', value: '', selectionStart: 0, selectionEnd: 0, data: 'ㄴ'},
    {seq: 166, t: 675036.4, type: 'input', value: 'ㄴ', selectionStart: 1, selectionEnd: 1, data: 'ㄴ', inputType: 'insertCompositionText'},
    {seq: 167, t: 675048.4, type: 'raf', value: '', selectionStart: 0, selectionEnd: 0},
    {seq: 168, t: 676604.4, type: 'compositionend', value: 'ㄴ', selectionStart: 1, selectionEnd: 1, data: 'ㄴ'},
    {seq: 169, t: 676605.5, type: 'keydown', value: 'ㄴ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 170, t: 676605.7, type: 'compositionstart', value: 'ㄴ', selectionStart: 1, selectionEnd: 1, data: ''},
    {seq: 171, t: 676606.4, type: 'compositionupdate', value: 'ㄴ', selectionStart: 1, selectionEnd: 1, data: 'ㅏ'},
    {seq: 173, t: 676617.9, type: 'input', value: 'ㄴㅏ', selectionStart: 2, selectionEnd: 2, data: 'ㅏ', inputType: 'insertCompositionText'},
    {seq: 174, t: 676669.7, type: 'keydown', value: 'ㄴㅏ', selectionStart: 2, selectionEnd: 2, keyCode: 229},
    {seq: 175, t: 676670.2, type: 'compositionupdate', value: 'ㄴㅏ', selectionStart: 1, selectionEnd: 2, data: 'ㅏ'},
    {seq: 177, t: 676674.2, type: 'input', value: 'ㄴㅏ', selectionStart: 2, selectionEnd: 2, data: 'ㅏ', inputType: 'insertCompositionText'},
    {seq: 178, t: 676674.2, type: 'compositionend', value: 'ㄴㅏ', selectionStart: 2, selectionEnd: 2, data: 'ㅏ'},
    {seq: 179, t: 676675.6, type: 'keydown', value: 'ㄴㅏ', selectionStart: 2, selectionEnd: 2, keyCode: 229},
    {seq: 180, t: 676676.0, type: 'keydown', value: 'ㄴㅏ', selectionStart: 2, selectionEnd: 2, keyCode: 13},
    {seq: 181, t: 676678.0, type: 'raf', value: '', selectionStart: 0, selectionEnd: 0},
];
// 글 typed: ㅡ written over ㄱ and committed alone, then ㄹ.
const GEUL_FIRST_JAMO_ALONE = [
    {seq: 665, t: 1457144.3, type: 'keydown', value: '', selectionStart: 0, selectionEnd: 0, keyCode: 229},
    {seq: 666, t: 1457152.0, type: 'compositionstart', value: '', selectionStart: 0, selectionEnd: 0, data: ''},
    {seq: 667, t: 1457153.1, type: 'compositionupdate', value: '', selectionStart: 0, selectionEnd: 0, data: 'ㄱ'},
    {seq: 669, t: 1457155.4, type: 'input', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, data: 'ㄱ', inputType: 'insertCompositionText'},
    {seq: 670, t: 1457157.3, type: 'raf', value: '', selectionStart: 0, selectionEnd: 0},
    {seq: 671, t: 1458383.8, type: 'keydown', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 672, t: 1458385.6, type: 'compositionupdate', value: 'ㄱ', selectionStart: 0, selectionEnd: 1, data: 'ㅡ'},
    {seq: 674, t: 1458399.4, type: 'input', value: 'ㅡ', selectionStart: 1, selectionEnd: 1, data: 'ㅡ', inputType: 'insertCompositionText'},
    {seq: 675, t: 1458403.6, type: 'compositionend', value: 'ㅡ', selectionStart: 1, selectionEnd: 1, data: 'ㅡ'},
    {seq: 676, t: 1458411.0, type: 'keydown', value: 'ㅡ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 677, t: 1458411.4, type: 'compositionstart', value: 'ㅡ', selectionStart: 1, selectionEnd: 1, data: ''},
    {seq: 678, t: 1458413.2, type: 'compositionupdate', value: 'ㅡ', selectionStart: 1, selectionEnd: 1, data: 'ㄹ'},
    {seq: 680, t: 1458424.3, type: 'input', value: 'ㅡㄹ', selectionStart: 2, selectionEnd: 2, data: 'ㄹ', inputType: 'insertCompositionText'},
    {seq: 681, t: 1458426.5, type: 'keydown', value: 'ㅡㄹ', selectionStart: 2, selectionEnd: 2, keyCode: 229},
    {seq: 682, t: 1458525.9, type: 'compositionupdate', value: 'ㅡㄹ', selectionStart: 1, selectionEnd: 2, data: 'ㄹ'},
    {seq: 684, t: 1458526.4, type: 'input', value: 'ㅡㄹ', selectionStart: 2, selectionEnd: 2, data: 'ㄹ', inputType: 'insertCompositionText'},
    {seq: 685, t: 1458526.4, type: 'compositionend', value: 'ㅡㄹ', selectionStart: 2, selectionEnd: 2, data: 'ㄹ'},
    {seq: 686, t: 1458527.5, type: 'keydown', value: 'ㅡㄹ', selectionStart: 2, selectionEnd: 2, keyCode: 229},
    {seq: 687, t: 1458527.7, type: 'keydown', value: 'ㅡㄹ', selectionStart: 2, selectionEnd: 2, keyCode: 13},
    {seq: 688, t: 1458529.0, type: 'raf', value: '', selectionStart: 0, selectionEnd: 0},
];
// 나 again, the whole line within 104 ms and no frame before the Enter.
const NA_FIRST_JAMO_ALONE_BURST = [
    {seq: 829, t: 5582218.6, type: 'keydown', value: '', selectionStart: 0, selectionEnd: 0, keyCode: 229},
    {seq: 830, t: 5582219.8, type: 'compositionstart', value: '', selectionStart: 0, selectionEnd: 0, data: ''},
    {seq: 831, t: 5582221.1, type: 'compositionupdate', value: '', selectionStart: 0, selectionEnd: 0, data: 'ㄴ'},
    {seq: 833, t: 5582222.3, type: 'input', value: 'ㄴ', selectionStart: 1, selectionEnd: 1, data: 'ㄴ', inputType: 'insertCompositionText'},
    {seq: 834, t: 5582223.1, type: 'compositionend', value: 'ㄴ', selectionStart: 1, selectionEnd: 1, data: 'ㄴ'},
    {seq: 835, t: 5582223.7, type: 'keydown', value: 'ㄴ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 836, t: 5582223.9, type: 'compositionstart', value: 'ㄴ', selectionStart: 1, selectionEnd: 1, data: ''},
    {seq: 837, t: 5582230.3, type: 'compositionupdate', value: 'ㄴ', selectionStart: 1, selectionEnd: 1, data: 'ㅏ'},
    {seq: 839, t: 5582292.0, type: 'input', value: 'ㄴㅏ', selectionStart: 2, selectionEnd: 2, data: 'ㅏ', inputType: 'insertCompositionText'},
    {seq: 840, t: 5582293.6, type: 'keydown', value: 'ㄴㅏ', selectionStart: 2, selectionEnd: 2, keyCode: 229},
    {seq: 841, t: 5582294.1, type: 'compositionupdate', value: 'ㄴㅏ', selectionStart: 1, selectionEnd: 2, data: 'ㅏ'},
    {seq: 843, t: 5582294.5, type: 'input', value: 'ㄴㅏ', selectionStart: 2, selectionEnd: 2, data: 'ㅏ', inputType: 'insertCompositionText'},
    {seq: 844, t: 5582294.5, type: 'compositionend', value: 'ㄴㅏ', selectionStart: 2, selectionEnd: 2, data: 'ㅏ'},
    {seq: 845, t: 5582322.4, type: 'keydown', value: 'ㄴㅏ', selectionStart: 2, selectionEnd: 2, keyCode: 229},
    {seq: 846, t: 5582322.6, type: 'keydown', value: 'ㄴㅏ', selectionStart: 2, selectionEnd: 2, keyCode: 13},
    {seq: 847, t: 5582323.8, type: 'raf', value: '', selectionStart: 0, selectionEnd: 0},
];
// The same split where a frame let the first finalize run before the second composition ended: 가 as ㄱ then ㅏ.
const GA_FIRST_JAMO_ALONE_IN_TIME = [
    {seq: 327, t: 813221.0, type: 'keydown', value: '', selectionStart: 0, selectionEnd: 0, keyCode: 229},
    {seq: 328, t: 813251.4, type: 'compositionstart', value: '', selectionStart: 0, selectionEnd: 0, data: ''},
    {seq: 329, t: 813254.1, type: 'compositionupdate', value: '', selectionStart: 0, selectionEnd: 0, data: 'ㄱ'},
    {seq: 331, t: 813256.5, type: 'input', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, data: 'ㄱ', inputType: 'insertCompositionText'},
    {seq: 332, t: 813257.7, type: 'compositionend', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, data: 'ㄱ'},
    {seq: 333, t: 813258.5, type: 'keydown', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 334, t: 813258.7, type: 'compositionstart', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, data: ''},
    {seq: 335, t: 813259.3, type: 'compositionupdate', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, data: 'ㅏ'},
    {seq: 337, t: 813260.4, type: 'input', value: 'ㄱㅏ', selectionStart: 2, selectionEnd: 2, data: 'ㅏ', inputType: 'insertCompositionText'},
    {seq: 338, t: 813261.6, type: 'raf', value: '', selectionStart: 0, selectionEnd: 0},
    {seq: 339, t: 813525.7, type: 'keydown', value: 'ㄱㅏ', selectionStart: 2, selectionEnd: 2, keyCode: 229},
    {seq: 340, t: 813538.0, type: 'compositionupdate', value: 'ㄱㅏ', selectionStart: 1, selectionEnd: 2, data: 'ㅏ'},
    {seq: 342, t: 813540.1, type: 'input', value: 'ㄱㅏ', selectionStart: 2, selectionEnd: 2, data: 'ㅏ', inputType: 'insertCompositionText'},
    {seq: 343, t: 813540.1, type: 'compositionend', value: 'ㄱㅏ', selectionStart: 2, selectionEnd: 2, data: 'ㅏ'},
    {seq: 344, t: 813540.9, type: 'keydown', value: 'ㄱㅏ', selectionStart: 2, selectionEnd: 2, keyCode: 229},
    {seq: 345, t: 813541.2, type: 'keydown', value: 'ㄱㅏ', selectionStart: 2, selectionEnd: 2, keyCode: 13},
    {seq: 346, t: 813544.9, type: 'raf', value: '', selectionStart: 0, selectionEnd: 0},
];
// 가 in one composition with no frame before the Enter.
const GA_ONE_COMPOSITION = [
    {seq: 508, t: 970699.5, type: 'keydown', value: '', selectionStart: 0, selectionEnd: 0, keyCode: 229},
    {seq: 509, t: 970701.5, type: 'compositionstart', value: '', selectionStart: 0, selectionEnd: 0, data: ''},
    {seq: 510, t: 970702.3, type: 'compositionupdate', value: '', selectionStart: 0, selectionEnd: 0, data: 'ㄱ'},
    {seq: 512, t: 970707.1, type: 'input', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, data: 'ㄱ', inputType: 'insertCompositionText'},
    {seq: 513, t: 970708.1, type: 'keydown', value: 'ㄱ', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 514, t: 970709.1, type: 'compositionupdate', value: 'ㄱ', selectionStart: 0, selectionEnd: 1, data: '가'},
    {seq: 516, t: 970710.6, type: 'input', value: '가', selectionStart: 1, selectionEnd: 1, data: '가', inputType: 'insertCompositionText'},
    {seq: 517, t: 970725.0, type: 'keydown', value: '가', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 518, t: 970725.6, type: 'compositionupdate', value: '가', selectionStart: 0, selectionEnd: 1, data: '가'},
    {seq: 520, t: 970726.2, type: 'input', value: '가', selectionStart: 1, selectionEnd: 1, data: '가', inputType: 'insertCompositionText'},
    {seq: 521, t: 970726.2, type: 'compositionend', value: '가', selectionStart: 1, selectionEnd: 1, data: '가'},
    {seq: 522, t: 970728.1, type: 'keydown', value: '가', selectionStart: 1, selectionEnd: 1, keyCode: 229},
    {seq: 523, t: 970728.5, type: 'keydown', value: '가', selectionStart: 1, selectionEnd: 1, keyCode: 13},
    {seq: 524, t: 970731.5, type: 'raf', value: '', selectionStart: 0, selectionEnd: 0},
];

// How compositionend arrives: Android's WebView (134 on Android 16, measured) delivers one per composition, not marked trusted; a
// WebView that marks it trusted, or one that sends a trusted and an untrusted one for the same composition, is covered as well, and
// in every case the composition's text is sent once.
const END_ORDERS = [
    ['untrusted, as Android\'s WebView delivers it', [false]],
    ['trusted then untrusted', [true, false]],
    ['untrusted then trusted', [false, true]],
    ['trusted', [true]],
];
for (const [label, entries, split] of [
    ['나 with ㄴ committed alone', NA_FIRST_JAMO_ALONE, 'ㄴㅏ'],
    ['글 with ㅡ committed alone', GEUL_FIRST_JAMO_ALONE, 'ㅡㄹ'],
    ['나 with ㄴ committed alone in a burst', NA_FIRST_JAMO_ALONE_BURST, 'ㄴㅏ'],
]) {
    for (const [order, ends] of END_ORDERS) {
        row(`recorded ${label}, Enter before its first finalize timer, compositionend ${order}: both jamo sent apart, once, none dropped`, () => {
            assert.equal(compositionPage(false).replayEntries(entries, {ends}), '\r');
            // The split the keyboard made stays a split; the first jamo is no longer lost, and nothing is sent twice.
            assert.equal(compositionPage(true).replayEntries(entries, {ends}), split + '\r');
        });
    }
}
for (const [order, ends] of END_ORDERS) {
    row(`recorded split whose first finalize ran in time, and a one-composition syllable, compositionend ${order}: sent as before, once`, () => {
        assert.equal(compositionPage(false).replayEntries(GA_FIRST_JAMO_ALONE_IN_TIME, {ends}), 'ㄱㅏ\r');
        assert.equal(compositionPage(true).replayEntries(GA_FIRST_JAMO_ALONE_IN_TIME, {ends}), 'ㄱㅏ\r');
        assert.equal(compositionPage(false).replayEntries(GA_ONE_COMPOSITION, {ends}), '\r');
        assert.equal(compositionPage(true).replayEntries(GA_ONE_COMPOSITION, {ends}), '가\r');
    });
    row(`an ended composition is sent before the next compositionstart, by its own timer when nothing comes, and once (compositionend ${order})`, () => {
        const p = compositionPage(true);
        const end = data => { for (const trusted of ends) p.compEnd(data, trusted); };
        p.sendKey229(); p.compStart(); p.compUpdate('ㄴ', 0, 0); p.compInput('ㄴ', 'ㄴ'); end('ㄴ');
        p.sendKey229(); p.compStart();
        assert.deepEqual(p.sent, ['ㄴ'], 'sent before the new composition starts');
        p.compUpdate('ㅏ', 1, 1); p.compInput('ㄴㅏ', 'ㅏ'); end('ㅏ');
        p.advance(0);
        assert.deepEqual(p.sent, ['ㄴ', 'ㅏ'], 'the second by its own timer');
        p.sendKey229(); p.sendEnter(); p.advance(100);
        assert.deepEqual(p.sent, ['ㄴ', 'ㅏ', '\r'], 'nothing sent twice');
    });
    row(`text typed after a composition ended is sent with it before an Enter from the key row (compositionend ${order})`, () => {
        const p = compositionPage(true);
        p.sendKey229(); p.compStart(); p.compUpdate('ㄴ', 0, 0); p.compInput('ㄴ', 'ㄴ');
        for (const trusted of ends) p.compEnd('ㄴ', trusted);
        p.sendKey229();
        p.ta.value = 'ㄴ1'; p.ta.selectionStart = 2; p.ta.selectionEnd = 2;
        p.dispatch('input', p.ta, {data: '1', inputType: 'insertText', composed: true});
        p.dispatch('keydown', p.ta, {keyCode: 13, trusted: false});
        p.advance(100);
        assert.deepEqual(p.sent, ['ㄴ1', '\r']);
    });
}
// The field's blur. xterm empties its field in the field's own blur handler. A tap elsewhere while a word composes commits
// it, and its compositionend comes just before the blur, in the same task (Android 16, WebView 134, Gboard 18.5.6 Korean,
// 5 of 5 taps in the editor's text): the composition's own send, still pending, then read an empty field.
const otherElement = {classList: {contains: () => false}};
for (const [order, ends] of END_ORDERS) {
    row(`a pending finalizer at the field's blur is sent once, before xterm empties the field (compositionend ${order})`, () => {
        const lib = compositionPage(false), cand = compositionPage(true);
        for (const p of [lib, cand]) {
            p.sendKey229(); p.compStart(); p.compUpdate('ㅏ', 0, 0); p.compInput('ㅏ', 'ㅏ');
            for (const trusted of ends) p.compEnd('ㅏ', trusted);
            p.blur();
            assert.equal(p.ta.value, '', 'the field is emptied at the blur');
        }
        assert.deepEqual(cand.sent, ['ㅏ'], 'sent at the blur, while the field still held it');
        lib.advance(100); cand.advance(100);
        assert.deepEqual(lib.sent, [], 'without the script the send reads the emptied field');
        assert.deepEqual(cand.sent, ['ㅏ'], 'and once');
    });
}
row('two pending finalizers at the field\'s blur both run before xterm empties the field, oldest first, each once', () => {
    const p = compositionPage(true), runs = [];
    p.ta.value = 'ab';
    for (const name of ['first', 'second']) {
        p.dispatch('compositionend', p.ta, {data: name, trusted: false}, () => {
            p.window.setTimeout(function() { /* _isSendingComposition */ runs.push([name, p.ta.value]); }, 0);
        });
    }
    p.blur();
    assert.deepEqual(runs, [['first', 'ab'], ['second', 'ab']]);
    p.advance(100);
    assert.equal(runs.length, 2);
    assert.deepEqual(p.sent, ['ab'], 'xterm\'s own sends for the two ends: the first sends, the second finds nothing to send');
});
row('the field\'s blur with nothing pending sends nothing', () => {
    for (const patched of [false, true]) {
        const p = compositionPage(patched);
        p.blur(); p.advance(100);
        assert.deepEqual(p.sent, []);
        p.sendKey229(); p.compStart(); p.compUpdate('ㅏ', 0, 0); p.compInput('ㅏ', 'ㅏ'); p.compEnd('ㅏ', false);
        p.advance(0);
        assert.deepEqual(p.sent, ['ㅏ'], 'sent by its own timer');
        p.blur(); p.advance(100);
        assert.deepEqual(p.sent, ['ㅏ'], 'nothing more at the blur after it');
    }
});
row('a finalizer already run by a key is not run again at the field\'s blur', () => {
    const p = compositionPage(true);
    p.sendKey229(); p.compStart(); p.compUpdate('ㅏ', 0, 0); p.compInput('ㅏ', 'ㅏ'); p.compEnd('ㅏ', false);
    p.sendEnter();
    assert.deepEqual(p.sent, ['ㅏ', '\r']);
    p.blur(); p.advance(100);
    assert.deepEqual(p.sent, ['ㅏ', '\r']);
});
row('the blur of an element that is not a terminal field runs nothing', () => {
    const p = compositionPage(true);
    p.sendKey229(); p.compStart(); p.compUpdate('ㅏ', 0, 0); p.compInput('ㅏ', 'ㅏ'); p.compEnd('ㅏ', false);
    p.blur(otherElement);
    assert.deepEqual(p.sent, [], 'nothing at another element\'s blur');
    p.advance(0);
    assert.deepEqual(p.sent, ['ㅏ'], 'the composition by its own timer, once');
});
row('a character typed after a composition, its change timer still pending at the blur: the same erase character with and without the script', () => {
    const outcomes = [];
    for (const patched of [false, true]) {
        const p = compositionPage(patched);
        p.sendKey229(); p.compStart(); p.compUpdate('ㄱ', 0, 0); p.compInput('ㄱ', 'ㄱ'); p.compEnd('ㄱ', false);
        p.advance(0);
        // The next character's key arms xterm's change timer; the field is emptied by a blur before the character arrives.
        p.sendKey229(); p.blur();
        p.advance(100);
        outcomes.push(p.sent.slice());
    }
    const shown = list => JSON.stringify(list).replace(/\x7f/g, '\\x7f');
    console.log(`   change timer at the blur, sent without the script ${shown(outcomes[0])}, with it ${shown(outcomes[1])}`);
    assert.deepEqual(outcomes[0], ['ㄱ', '\x7f'], 'the change timer finds the field shorter than it was and sends DEL');
    assert.deepEqual(outcomes[1], outcomes[0]);
});
row('measured at a tap in the editor\'s text while a Korean word composes: compositionend, then the blur in the same task; the word is sent once', () => {
    const lib = compositionPage(false), cand = compositionPage(true);
    for (const p of [lib, cand]) {
        // One key at a time, the page's tasks running between them: ㅎ committed alone, then ㅏ and ㄴ composed apart.
        p.ta.value = 'echo '; p.ta.selectionStart = 5; p.ta.selectionEnd = 5;
        p.sendKey229(); p.compStart(); p.compUpdate('ㅎ', 5, 5); p.compInput('echo ㅎ', 'ㅎ'); p.compEnd('ㅎ', false);
        p.advance(0);
        p.sendKey229(); p.compStart(); p.compUpdate('ㅏ', 6, 6); p.compInput('echo ㅎㅏ', 'ㅏ');
        p.advance(0);
        p.sendKey229(); p.compUpdate('ㅏㄴ', 6, 7); p.compInput('echo ㅎㅏㄴ', 'ㅏㄴ');
        p.advance(0);
        // The tap: the word's compositionend, then the blur, in one task.
        p.compEnd('ㅏㄴ', false); p.blur();
        p.advance(100);
    }
    assert.deepEqual(lib.sent, ['ㅎ'], 'without the script the word under composition is lost');
    assert.deepEqual(cand.sent, ['ㅎ', 'ㅏㄴ']);
});
row('not covered (not seen in 10 of 10 taps): a compositionend delivered after the blur finds the field already emptied', () => {
    for (const patched of [false, true]) {
        const p = compositionPage(patched);
        p.sendKey229(); p.compStart(); p.compUpdate('ㅏ', 0, 0); p.compInput('ㅏ', 'ㅏ');
        p.blur(); p.compEnd('ㅏ', false); p.advance(100);
        assert.deepEqual(p.sent, [], 'lost with and without the script');
    }
});
assert.equal(failures.length, 0, `terminal key boundaries failed: ${failures.join(', ')}`);

// Fast-path guard: normal timers outside terminal events must not stringify callbacks or filter ended.
{
    const sandbox = {
        addEventListener() {},
        setTimeout() { return 1; },
        clearTimeout() {},
    };
    sandbox.window = sandbox;
    const ctx = vm.createContext(sandbox);
    vm.runInContext(script, ctx);
    const counts = vm.runInContext(`
        (function() {
            var toStringCalls = 0;
            var filterCalls = 0;
            var origToString = Function.prototype.toString;
            var origFilter = Array.prototype.filter;
            Function.prototype.toString = function() {
                toStringCalls++;
                return origToString.apply(this, arguments);
            };
            Array.prototype.filter = function() {
                filterCalls++;
                return origFilter.apply(this, arguments);
            };
            try {
                var id1 = window.setTimeout(function normalZeroTimer() {}, 0);
                var id2 = window.setTimeout(function normalDelayedTimer() {}, 50);
                window.clearTimeout(id1);
                window.clearTimeout(id2);
            } finally {
                Function.prototype.toString = origToString;
                Array.prototype.filter = origFilter;
            }
            return { toStringCalls: toStringCalls, filterCalls: filterCalls };
        })();
    `, ctx);
    assert.equal(counts.toStringCalls, 0, `normal timers invoked Function.prototype.toString ${counts.toStringCalls} times`);
    assert.equal(counts.filterCalls, 0, `clearTimeout filtered empty ended array ${counts.filterCalls} times`);
}

console.log('ok: terminal input event orders, negative control, deletion, cancellation and pass-through');
