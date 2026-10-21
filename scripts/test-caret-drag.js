/**
 * Self-check for the script that lets a finger drag the caret.
 *
 *   node scripts/test-caret-drag.js
 *
 * The script is JavaScript inside the Kotlin raw string of
 * `MainActivity.injectCaretDrag()`, handed to `evaluateJavascript`, so nothing
 * compiles it and `CaretDragWiringTest` can only match its text. What it has to
 * get right is a decision made at the first movement of a touch: which touches
 * it takes (a finger down on the text within reach of the one caret, nothing
 * selected, a first move sideways) and which it leaves to the editor, so that a
 * tap, a long press and a scroll go on as before. That, the distance a finger
 * keeps from the caret, the edge scrolling, the second finger and the lift are
 * the kind of thing that stays textually present while being wrong.
 *
 * This extracts the real script, runs it under `vm` against a stub window and a
 * stub editor, and drives the listeners it installs with fake touches and
 * pointers. The editor stub knows only the selectors the script uses and throws
 * on any other, so a script that starts asking for something else fails here
 * instead of quietly matching nothing. What a stub cannot say is what the real
 * editor does with the events: that was measured on a device, see the KDoc.
 *
 * Extraction is deliberately strict. If the raw string moves or changes shape
 * this fails saying so, rather than quietly checking an empty string.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const MAIN_ACTIVITY = path.join(
    __dirname, '..', 'android/app/src/main/kotlin/com/vscodroid/MainActivity.kt',
);

/** The text the WebView is given: the raw string with `trimIndent()` applied. */
function extractScript() {
    const lines = fs.readFileSync(MAIN_ACTIVITY, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('private fun injectCaretDrag()'));
    assert.notStrictEqual(
        fn, -1,
        'injectCaretDrag() is gone from MainActivity.kt, so this check has nothing to run. ' +
        'If the script moved, point this at its new home rather than deleting the check.',
    );
    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close !== -1 && close > open + 1,
        'could not find the raw string in injectCaretDrag(); its shape changed');
    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
    assert.ok(!js.includes('$'),
        'the script gained a Kotlin interpolation this check does not know how to fill');
    assert.ok(js.includes("'-monaco-gesturetap'"),
        'the extracted text never dispatches the editor tap, so the wrong block was extracted');
    return js;
}

const SCRIPT = extractScript();

/** What a rectangle in CSS pixels looks like to getBoundingClientRect. */
function rect(left, top, width, height) {
    return { left, top, width, height, right: left + width, bottom: top + height };
}

/**
 * One page: a window that records its listeners, a document with one focused
 * editor, and queues standing in for animation frames. The caret is 2 x 19 at
 * (200, 100) unless asked otherwise; the editor's scroller spans x 100..400 and y 50..450. The text
 * sits where `page.textAt` says, which a test moves to say that the editor scrolled.
 */
function newPage({ focused = true, carets = 1, selected = false, sash = false, caretLeft = 200, caretTop = 100,
    box = { left: 100, top: 50, width: 300, height: 400 } } = {}) {
    const listeners = [];
    const frames = new Map();
    let nextFrame = 1;
    const taps = [];
    const wheels = [];
    const classes = new Set();
    const textAt = { left: 0, top: 0 };

    const lines = {
        name: 'lines',
        contains: (el) => el === lines || el.inLines === true,
        dispatchEvent: (ev) => { taps.push(ev); },
        getBoundingClientRect: () => rect(textAt.left, textAt.top, 9999, 9999),
    };
    const scroller = {
        name: 'scroller',
        getBoundingClientRect: () => rect(box.left, box.top, box.width, box.height),
    };
    const caretRects = Array.from({ length: carets }, () => ({
        getBoundingClientRect: () => rect(caretLeft, caretTop, 2, 19),
    }));
    const editor = {
        name: 'editor',
        dispatchEvent: (ev) => { wheels.push(ev); },
        querySelectorAll(sel) {
            assert.strictEqual(sel, '.cursors-layer > .cursor', `unexpected selector ${sel}`);
            return caretRects;
        },
        querySelector(sel) {
            if (sel === '.selected-text') return selected ? {} : null;
            if (sel === '.lines-content') return lines;
            if (sel === '.monaco-scrollable-element') return scroller;
            throw new Error(`unexpected selector ${sel}`);
        },
    };
    const text = { name: 'text', inEditor: true, inLines: true };
    const sashEl = { name: 'sash', inEditor: true, inLines: false };
    const menuItem = { name: 'menu item', inEditor: true, inLines: false };
    const appended = [];
    const document = {
        head: { appendChild: (el) => appended.push(el) },
        documentElement: {
            classList: {
                add: (c) => classes.add(c),
                remove: (c) => classes.delete(c),
                contains: (c) => classes.has(c),
            },
        },
        createElement: () => ({}),
        createEvent: () => ({
            initEvent(type, bubbles, cancelable) { this.type = type; this.bubbles = bubbles; this.cancelable = cancelable; },
        }),
        querySelector(sel) {
            assert.strictEqual(sel, '.monaco-editor.focused', `unexpected selector ${sel}`);
            return focused ? editor : null;
        },
        elementsFromPoint: () => (sash ? [sashEl, text] : [text]),
    };
    const window = {
        addEventListener: (type, fn, opts) => listeners.push({ type, fn, opts }),
    };
    class WheelEvent {
        constructor(type, init) { this.type = type; Object.assign(this, init); }
    }
    const context = vm.createContext({
        window,
        document,
        WheelEvent,
        requestAnimationFrame: (fn) => { const id = nextFrame++; frames.set(id, fn); return id; },
        cancelAnimationFrame: (id) => { frames.delete(id); },
    });
    vm.runInContext(SCRIPT, context);

    let clock = 1000;
    const page = {
        listeners, taps, wheels, classes, appended, text, sashEl, menuItem, window, frames, textAt,
        /** Runs every queued animation frame once, in order, `step` ms after the last. */
        flush(step = 16.7) {
            clock += step;
            const due = [...frames.entries()];
            frames.clear();
            due.forEach(([, fn]) => fn(clock));
        },
        /** Fires an event through the listeners of its type, honouring stopImmediatePropagation. */
        dispatch(type, event) {
            event.type = type;
            event.defaultPrevented = false;
            event.stopped = false;
            event.preventDefault = function() { this.defaultPrevented = true; };
            event.stopImmediatePropagation = function() { this.stopped = true; };
            for (const l of listeners.filter((x) => x.type === type)) {
                l.fn(event);
                if (event.stopped) break;
            }
            return event;
        },
        fire(type, touches, { changed = touches, target = text } = {}) {
            return page.dispatch(type, { touches, changedTouches: changed, target });
        },
        /** A pointer event of the given type, through the listeners of its type. */
        pointer(type, x, y, id = 7, pointerType = 'touch') {
            return page.dispatch(type, { pointerId: id, pointerType, clientX: x, clientY: y });
        },
        /** The pointer event the platform sends just before a touch starts, then the touch. */
        start(x, y, { id = 0, pointerId = 7, pointerType = 'touch', target = text } = {}) {
            page.pointer('pointerdown', x, y, pointerId, pointerType);
            return page.fire('touchstart', [{ identifier: id, clientX: x, clientY: y }], { target });
        },
        move(x, y, id = 0) { return page.fire('touchmove', [{ identifier: id, clientX: x, clientY: y }]); },
        end(x, y, id = 0) { return page.fire('touchend', [], { changed: [{ identifier: id, clientX: x, clientY: y }] }); },
    };
    return page;
}

let passed = 0;
function check(name, fn) {
    try {
        fn();
        passed += 1;
        console.log(`  ok      ${name}`);
    } catch (error) {
        console.error(`  FAIL    ${name}\n          ${error.message.split('\n').join('\n          ')}`);
        process.exitCode = 1;
    }
}

// The caret centre is (200, 109.5); its left edge is x 200.
check('installs the listeners once, in the capture phase, and the move one cancelable', () => {
    const page = newPage();
    const by = (t) => page.listeners.filter((l) => l.type === t);
    for (const t of ['touchstart', 'touchmove', 'touchend', 'touchcancel', 'pointerdown', 'pointermove', 'pointerup', 'pointercancel']) {
        assert.strictEqual(by(t).length, 1, `expected one ${t} listener`);
    }
    for (const t of ['touchstart', 'touchmove', 'touchend']) {
        assert.ok(by(t)[0].opts.capture, `${t} is not in the capture phase, so the editor hears it first`);
    }
    assert.strictEqual(by('touchmove')[0].opts.passive, false,
        'a passive touchmove cannot be cancelled, so the editor would scroll under the drag');
    assert.strictEqual(page.appended.length, 1, 'expected the caret style to be added once');
    assert.strictEqual(page.window.__vscodroidCaretDragged, false);
});

check('does nothing when the editor has no focus', () => {
    const page = newPage({ focused: false });
    page.start(200, 110);
    const move = page.move(230, 110);
    assert.ok(!move.defaultPrevented && !move.stopped);
    assert.strictEqual(page.classes.size, 0);
});

check('a sideways drag from the caret takes the touch from the editor and moves the caret', () => {
    const page = newPage();
    const down = page.start(200, 110);
    assert.ok(!down.defaultPrevented && !down.stopped, 'the start must reach the editor untouched');
    const move = page.move(230, 110);
    assert.ok(move.defaultPrevented, 'the move was not cancelled, so the page would scroll');
    assert.ok(move.stopped, 'the move reached the editor, whose gesture would scroll it');
    assert.ok(page.classes.has('vscodroid-caret-drag'));
    page.flush();
    assert.strictEqual(page.taps.length, 1);
    const tap = page.taps[0];
    assert.strictEqual(tap.type, '-monaco-gesturetap');
    assert.strictEqual(tap.tapCount, 1);
    assert.strictEqual(tap.initialTarget, page.text);
    // The finger went down 0 right of the caret's left edge and 0.5 below its centre.
    assert.strictEqual(tap.clientX, 230);
    assert.strictEqual(Math.round(tap.clientY * 2) / 2, 110 - 0.5);
    assert.strictEqual(tap.pageX, tap.clientX);
    assert.strictEqual(tap.pageY, tap.clientY);
});

check('a finger below the caret keeps its distance for the whole drag', () => {
    const page = newPage();
    page.start(200, 139.5);           // 30 below the centre
    page.move(215, 139.5);
    page.move(215, 200);
    page.flush();
    const last = page.taps[page.taps.length - 1];
    assert.strictEqual(last.clientX, 215);
    assert.strictEqual(last.clientY, 200 - 30);
});

check('a first move up or down is a scroll and is left to the editor', () => {
    const page = newPage();
    page.start(200, 110);
    for (const [x, y] of [[201, 140], [205, 200], [260, 230]]) {
        const move = page.move(x, y);
        assert.ok(!move.defaultPrevented && !move.stopped, `move to ${x},${y} was taken`);
    }
    assert.strictEqual(page.classes.size, 0);
    page.flush();
    assert.strictEqual(page.taps.length, 0);
});

check('movement under the travel decides nothing, and the move after it can still be taken', () => {
    const page = newPage();
    page.start(200, 110);
    const small = page.move(203, 110);
    assert.ok(!small.defaultPrevented && !small.stopped);
    const real = page.move(215, 110);
    assert.ok(real.defaultPrevented && real.stopped);
});

check('a touch is held only inside the reach around the caret', () => {
    // Caret box is x 200..202, y 100..119. Reach: 24 either side, 8 above, 40 below.
    const cases = [
        [200 - 24, 110, true], [200 - 25, 110, false],
        [202 + 24, 110, true], [202 + 25, 110, false],
        [200, 100 - 8, true], [200, 100 - 9, false],
        [200, 119 + 40, true], [200, 119 + 41, false],
    ];
    for (const [x, y, taken] of cases) {
        const page = newPage();
        page.start(x, y);
        const move = page.move(x + 20, y);
        assert.strictEqual(move.defaultPrevented, taken,
            `a touch down at ${x},${y} should ${taken ? '' : 'not '}be taken`);
    }
});

check('a touch far from the caret is never taken', () => {
    const page = newPage();
    page.start(350, 300);
    const move = page.move(380, 300);
    assert.ok(!move.defaultPrevented && !move.stopped);
});

check('a touch on something over the text is left to it, however near the caret', () => {
    // A menu item or a suggestion is a child of the editor and not of its lines: the
    // editor runs it on the tap its touchend would make, which a taken touch swallows.
    const page = newPage();
    page.start(205, 110, { target: page.menuItem });
    const move = page.move(235, 110);
    assert.ok(!move.defaultPrevented && !move.stopped);
    const lift = page.end(235, 110);
    assert.ok(!lift.defaultPrevented && !lift.stopped);
});

check('a stylus or anything that is not a finger is left alone', () => {
    for (const pointerType of ['pen', 'mouse', '', null]) {
        const page = newPage();
        page.start(200, 110, { pointerType });
        const move = page.move(230, 110);
        assert.ok(!move.defaultPrevented && !move.stopped,
            `a ${JSON.stringify(pointerType)} pointer's touch was taken`);
    }
    const finger = newPage();
    finger.start(200, 110, { pointerType: 'touch' });
    assert.ok(finger.move(230, 110).defaultPrevented);
});

check('a selection, several carets or a second finger leave the touch alone', () => {
    const selected = newPage({ selected: true });
    selected.start(200, 110);
    assert.ok(!selected.move(230, 110).defaultPrevented, 'a drag would have thrown the selection away');

    const several = newPage({ carets: 2 });
    several.start(200, 110);
    assert.ok(!several.move(230, 110).defaultPrevented, 'a drag would have thrown the extra cursors away');

    const two = newPage();
    two.pointer('pointerdown', 200, 110);
    two.fire('touchstart', [
        { identifier: 0, clientX: 200, clientY: 110 },
        { identifier: 1, clientX: 300, clientY: 300 },
    ]);
    assert.ok(!two.move(230, 110).defaultPrevented, 'two fingers are a pinch, not a caret drag');
});

check('a second finger landing during a drag is kept from the editor and the drag goes on', () => {
    const page = newPage();
    page.start(200, 110);
    assert.ok(page.move(230, 110).defaultPrevented);
    page.flush();
    page.pointer('pointerdown', 300, 300, 9);
    const second = page.fire('touchstart', [
        { identifier: 0, clientX: 230, clientY: 110 },
        { identifier: 1, clientX: 300, clientY: 300 },
    ]);
    assert.ok(second.stopped, 'the editor was told of the second finger, and would read its lift as a tap');
    assert.ok(page.classes.has('vscodroid-caret-drag'), 'the second finger ended the drag');
    assert.strictEqual(page.window.__vscodroidCaretDragged, true, 'the second finger cleared the mark the guard reads');
    // The first finger goes on: its moves are still taken, with the second finger in the list.
    const both = page.fire('touchmove', [
        { identifier: 0, clientX: 250, clientY: 110 },
        { identifier: 1, clientX: 301, clientY: 300 },
    ]);
    assert.ok(both.defaultPrevented && both.stopped);
    page.flush();
    assert.strictEqual(page.taps[page.taps.length - 1].clientX, 250);
    // The first finger lifts first, and its lift is still kept from the editor.
    page.pointer('pointerup', 250, 110, 7);
    const lift = page.end(250, 110, 0);
    assert.ok(lift.defaultPrevented && lift.stopped);
    assert.strictEqual(page.classes.size, 0);
});

check('the lift of a taken drag is kept from the editor, which would read it as a tap', () => {
    const page = newPage();
    page.start(200, 110);
    page.move(230, 110);
    page.flush();
    page.move(260, 110);
    const lift = page.end(260, 110);
    assert.ok(lift.defaultPrevented && lift.stopped);
    assert.strictEqual(page.classes.size, 0, 'the caret is still marked as held');
    assert.strictEqual(page.taps[page.taps.length - 1].clientX, 260,
        'the caret was not left where the finger was lifted');
    assert.strictEqual(page.frames.size, 0, 'a frame is still queued after the lift');
});

check('the lift of a touch that was not taken is left alone', () => {
    const page = newPage();
    page.start(200, 110);
    page.move(205, 200);
    const lift = page.end(205, 200);
    assert.ok(!lift.defaultPrevented && !lift.stopped);
    const far = newPage();
    far.start(380, 300);
    const farLift = far.end(380, 300);
    assert.ok(!farLift.defaultPrevented && !farLift.stopped);
});

check('a touch that never moved leaves the tap and the long press to the editor', () => {
    const page = newPage();
    page.start(200, 110);
    const lift = page.end(200, 110);
    assert.ok(!lift.defaultPrevented && !lift.stopped);
    page.flush();
    assert.strictEqual(page.taps.length, 0);
});

check('a cancelled touch ends the drag without hiding the cancel from anyone', () => {
    const page = newPage();
    page.start(200, 110);
    page.move(230, 110);
    const cancel = page.fire('touchcancel', [], { changed: [{ identifier: 0, clientX: 230, clientY: 110 }] });
    assert.ok(!cancel.defaultPrevented && !cancel.stopped);
    assert.strictEqual(page.classes.size, 0);
    assert.strictEqual(page.frames.size, 0);
});

check('a lone new touch ends a drag whose lift never came', () => {
    const page = newPage();
    page.start(200, 110);
    page.move(230, 110);
    assert.ok(page.classes.has('vscodroid-caret-drag'));
    page.start(300, 300, { id: 5, pointerId: 8 });
    assert.strictEqual(page.classes.size, 0);
    assert.strictEqual(page.frames.size, 0);
});

check('only the finger that went down is followed', () => {
    const page = newPage();
    page.start(200, 110, { id: 3 });
    const other = page.move(230, 110, 7);
    assert.ok(!other.defaultPrevented);
});

check('the caret is aimed at the first element under the point that belongs to the lines', () => {
    const page = newPage({ sash: true });
    page.start(200, 110);
    page.move(230, 110);
    page.flush();
    assert.strictEqual(page.taps.length, 1, 'the sash on top hid the text and no tap was sent');
    assert.strictEqual(page.taps[0].initialTarget, page.text);
});

check('nothing is sent when no line is under the point', () => {
    const page = newPage();
    page.text.inLines = false;
    page.start(200, 110, { target: { inLines: true } });
    page.move(230, 110);
    page.flush();
    assert.strictEqual(page.taps.length, 0);
});

check('one tap a frame, and none for a finger that has not moved', () => {
    const page = newPage();
    page.start(200, 110);
    page.move(230, 110);
    page.move(240, 110);
    page.move(250, 110);
    page.flush();
    assert.strictEqual(page.taps.length, 1, 'three moves in one frame sent more than one tap');
    page.flush();
    assert.strictEqual(page.taps.length, 1, 'a frame with no movement sent a tap');
});

check('near the lower edge the text scrolls, faster the nearer, and the caret stays inside', () => {
    // Scroller y 50..450, EDGE 32: the band starts at 418.
    const slow = newPage();
    slow.start(200, 110);
    slow.move(230, 110);
    slow.move(230, 424);
    slow.flush();
    const fast = newPage();
    fast.start(200, 110);
    fast.move(230, 110);
    fast.move(230, 448);
    fast.flush();
    assert.strictEqual(slow.wheels.length, 1);
    assert.strictEqual(fast.wheels.length, 1);
    assert.ok(slow.wheels[0].deltaY < 0 && fast.wheels[0].deltaY < slow.wheels[0].deltaY,
        `down the page is a negative delta for the editor, and more so nearer the edge: ` +
        `${slow.wheels[0].deltaY} then ${fast.wheels[0].deltaY}`);
    assert.strictEqual(slow.wheels[0].deltaMode, 0);
    assert.ok(fast.taps[fast.taps.length - 1].clientY <= 450 - 8, 'the aim reached the sash on the edge');
    assert.ok(fast.frames.size === 1, 'the scroll does not carry on while the finger is held');
});

check('the wheel goes to the editor root and does not bubble out of it', () => {
    // The editor listens on its root, so a wheel sent to the scroller inside it never arrives,
    // and one that bubbles goes on to the notebook or the commit box around an editor that
    // had nothing to scroll.
    const page = newPage();
    page.start(200, 110);
    page.move(230, 110);
    page.move(230, 440);
    page.flush();
    assert.strictEqual(page.wheels.length, 1);
    assert.strictEqual(page.wheels[0].type, 'wheel');
    assert.strictEqual(page.wheels[0].bubbles, false, 'the wheel bubbles out of the editor');
});

check('near the upper edge the delta is positive, and out of the band there is no scrolling', () => {
    const page = newPage();
    page.start(200, 110);
    page.move(230, 110);
    page.move(230, 60);
    page.flush();
    assert.ok(page.wheels.length === 1 && page.wheels[0].deltaY > 0);
    assert.ok(page.taps[page.taps.length - 1].clientY >= 50 + 8, 'the aim reached the sash on the edge');

    const middle = newPage();
    middle.start(200, 110);
    middle.move(230, 110);
    middle.move(230, 250);
    middle.flush();
    assert.strictEqual(middle.wheels.length, 0);
    assert.strictEqual(middle.frames.size, 0);
});

check('the speed follows the time a frame took, not the number of frames', () => {
    const at60 = newPage();
    at60.start(200, 110);
    at60.move(230, 110);
    at60.move(230, 440);
    at60.flush();
    at60.textAt.top -= 5;
    at60.flush(16.7);
    const at120 = newPage();
    at120.start(200, 110);
    at120.move(230, 110);
    at120.move(230, 440);
    at120.flush();
    at120.textAt.top -= 5;
    at120.flush(8.35);
    const sixty = at60.wheels[1].deltaY;
    const hundredTwenty = at120.wheels[1].deltaY;
    assert.ok(Math.abs(hundredTwenty - sixty / 2) < 0.01,
        `a frame half as long should scroll half as far: ${sixty} then ${hundredTwenty}`);
});

check('scrolling rests once the text stops moving, and starts again with the finger', () => {
    const page = newPage();
    page.start(200, 110);
    page.move(230, 110);
    page.move(230, 440);
    let frames = 0;
    // The text never moves: nothing to scroll, as at the end of a file.
    for (let i = 0; i < 20 && page.frames.size > 0; i++) {
        page.flush();
        frames += 1;
    }
    assert.strictEqual(page.frames.size, 0, 'the loop never rested');
    assert.ok(frames <= 6, `the loop ran ${frames} frames with nothing to scroll`);
    const wheelsAtRest = page.wheels.length;
    page.flush();
    assert.strictEqual(page.wheels.length, wheelsAtRest, 'a rested loop still scrolled');
    page.pointer('pointermove', 232, 440);
    assert.strictEqual(page.frames.size, 1, 'the finger moving did not start the scrolling again');
});

check('scrolling goes on while the text keeps moving', () => {
    const page = newPage();
    page.start(200, 110);
    page.move(230, 110);
    page.move(230, 440);
    for (let i = 0; i < 40; i++) {
        page.textAt.top -= 5;
        page.flush();
    }
    assert.strictEqual(page.frames.size, 1, 'the loop stopped while the text was still moving');
    assert.ok(page.wheels.length >= 40);
});

check('a finger that goes down in the lower band runs along it without scrolling, and scrolls once it pushes on', () => {
    // The caret is on the last visible line: its centre, 429.5, is inside the band that starts at 418.
    const page = newPage({ caretTop: 420 });
    page.start(200, 430);
    page.move(215, 430);
    page.move(240, 430);
    page.flush();
    page.flush();
    assert.strictEqual(page.wheels.length, 0, 'the text scrolled under a finger that only moved along the line');
    assert.ok(page.taps.length >= 1, 'the caret did not move along the line');
    page.move(240, 450);
    page.flush();
    assert.ok(page.wheels.length >= 1 && page.wheels[0].deltaY < 0, 'pushing toward the edge did not scroll');
});

check('the same at the upper band', () => {
    // Caret centre 69.5, inside the band that ends at 82.
    const page = newPage({ caretTop: 60 });
    page.start(200, 70);
    page.move(215, 70);
    page.move(240, 70);
    page.flush();
    page.flush();
    assert.strictEqual(page.wheels.length, 0, 'the text scrolled under a finger that only moved along the line');
    page.move(240, 50);
    page.flush();
    assert.ok(page.wheels.length >= 1 && page.wheels[0].deltaY > 0, 'pushing toward the edge did not scroll');
});

check('a finger that leaves the band and comes back scrolls, whatever the distance it moved', () => {
    const page = newPage({ caretTop: 420 });
    page.start(200, 430);
    page.move(215, 430);
    page.move(215, 400);          // out of the band, 30 up
    page.flush();
    page.move(215, 430);          // back in, 0 further down than it began
    page.flush();
    assert.ok(page.wheels.length >= 1 && page.wheels[0].deltaY < 0, 'entering the band again did not scroll');
});

check('a finger that goes down in a side band runs along it, and scrolls sideways once it pushes on', () => {
    const page = newPage({ caretLeft: 382 });     // the band starts at 368
    page.start(384, 110);
    page.move(375, 110);
    page.flush();       // still inside the band
    assert.strictEqual(page.wheels.length, 0, 'the text scrolled sideways under a finger that had not pushed on');
    page.move(340, 110);
    page.flush();
    page.flush();
    assert.strictEqual(page.wheels.length, 0, 'the text scrolled sideways under a finger moving away from the edge');
    page.move(402, 110);
    page.flush();
    assert.ok(page.wheels.length >= 1 && page.wheels[0].deltaX < 0, 'pushing toward the side edge did not scroll');
});

check('an editor with no room for the bands does not scroll at its edges', () => {
    // 79px high, as a phone in landscape leaves with the keyboard up: the bands would cover every line.
    const page = newPage({ box: { left: 100, top: 50, width: 300, height: 79 } });
    page.start(200, 110);
    page.move(230, 110);
    page.move(230, 135);
    page.move(230, 52);
    page.move(230, 128);
    page.flush();
    page.flush();
    assert.strictEqual(page.wheels.length, 0, 'a short editor scrolled');
    assert.ok(page.taps.length >= 1, 'the caret did not move either');
    assert.strictEqual(page.frames.size, 0);
});

check('the bands appear once the editor is tall enough for them', () => {
    for (const [height, scrolls] of [[103, false], [104, true]]) {
        const page = newPage({ box: { left: 100, top: 50, width: 300, height }, caretTop: 90 });
        page.start(200, 100);
        page.move(230, 100);
        page.move(230, 148);
        page.flush();
        assert.strictEqual(page.wheels.length > 0, scrolls, `an editor ${height}px high should ${scrolls ? '' : 'not '}scroll`);
    }
});

check('a finger carried outside the editor aims at its nearest edge', () => {
    const page = newPage();
    page.start(200, 110);
    page.move(230, 110);
    page.move(900, -300);
    page.flush();
    const tap = page.taps[page.taps.length - 1];
    assert.ok(tap.clientX <= 399 && tap.clientX >= 101);
    assert.ok(tap.clientY >= 58 && tap.clientY <= 442);
});

check('the guard is told at once that a drag was taken, and until the next touch', () => {
    const page = newPage();
    page.start(200, 110);
    assert.strictEqual(page.window.__vscodroidCaretDragged, false, 'marked before anything was taken');
    page.move(203, 110);
    assert.strictEqual(page.window.__vscodroidCaretDragged, false, 'marked by a move under the travel');
    page.move(212, 110);
    assert.strictEqual(page.window.__vscodroidCaretDragged, true, 'not marked once the drag was taken');
    page.pointer('pointerup', 212, 110);
    assert.strictEqual(page.window.__vscodroidCaretDragged, true,
        'cleared before the guard saw the pointer go up');
    page.pointer('pointerdown', 300, 300, 8);
    assert.strictEqual(page.window.__vscodroidCaretDragged, false, 'still marked for the next touch');
});

check('a drag that is only a scroll does not mark the guard', () => {
    const page = newPage();
    page.start(200, 110);
    page.move(205, 200);
    assert.strictEqual(page.window.__vscodroidCaretDragged, false);
});

check('when the touch events stop mid-drag the pointer events carry it, and end it', () => {
    // The editor redraws its lines as the text scrolls and the element the touch began on
    // goes out of the page: the touch listeners hear nothing more, the pointer ones do.
    const page = newPage();
    page.start(200, 110);
    assert.ok(page.move(230, 110).defaultPrevented);
    page.flush();
    page.pointer('pointermove', 260, 110);
    page.flush();
    assert.strictEqual(page.taps[page.taps.length - 1].clientX, 260, 'the caret did not follow the pointer');
    page.pointer('pointermove', 300, 140);
    page.pointer('pointerup', 300, 140);
    assert.strictEqual(page.taps[page.taps.length - 1].clientX, 300, 'the caret was not left where the finger was lifted');
    assert.strictEqual(page.classes.size, 0, 'the caret is still marked as held after the pointer went up');
    assert.strictEqual(page.frames.size, 0);
    // If the touchend does arrive after all, the editor must not read it as a tap.
    const late = page.end(300, 140);
    assert.ok(late.defaultPrevented && late.stopped, 'a late touchend reached the editor');
});

check('a pointer that is not the one that went down changes nothing', () => {
    const page = newPage();
    page.start(200, 110, { pointerId: 7 });
    page.move(230, 110);
    page.flush();
    const before = page.taps.length;
    page.pointer('pointermove', 350, 300, 9);
    page.pointer('pointerup', 350, 300, 9);
    page.flush();
    assert.strictEqual(page.taps.length, before);
    assert.ok(page.classes.has('vscodroid-caret-drag'), 'another finger ended the drag');
});

check('a cancelled pointer ends the drag where it was and does not move the caret again', () => {
    const page = newPage();
    page.start(200, 110);
    page.move(230, 110);
    page.flush();
    page.pointer('pointermove', 260, 110);
    const before = page.taps.length;
    page.pointer('pointercancel', 260, 110);
    assert.strictEqual(page.taps.length, before);
    assert.strictEqual(page.classes.size, 0);
    assert.strictEqual(page.frames.size, 0);
});

check('the next touch is not mistaken for the end of the last drag', () => {
    const page = newPage();
    page.start(200, 110);
    page.move(230, 110);
    page.flush();
    page.pointer('pointerup', 230, 110);
    // No touchend ever came for that drag. A tap that follows has to reach the editor whole.
    page.start(300, 300);
    const lift = page.end(300, 300);
    assert.ok(!lift.defaultPrevented && !lift.stopped, 'the next tap lost its touchend');
});

check('a pointer event with no drag, or an unclaimed one, is ignored', () => {
    const page = newPage();
    page.pointer('pointermove', 260, 110);
    page.pointer('pointerup', 260, 110);
    page.start(200, 110);
    page.pointer('pointermove', 205, 200);
    page.pointer('pointerup', 205, 200);
    page.flush();
    assert.strictEqual(page.taps.length, 0);
    const lift = page.end(205, 200);
    assert.ok(!lift.defaultPrevented && !lift.stopped);
});

check('running the script again adds nothing', () => {
    const page = newPage();
    const before = page.listeners.length;
    vm.runInContext(SCRIPT, vm.createContext({
        window: page.window,
        document: {}, WheelEvent: class {},
        requestAnimationFrame: () => 0, cancelAnimationFrame: () => {},
    }));
    assert.strictEqual(page.listeners.length, before);
});

console.log(`${passed} checks passed`);
