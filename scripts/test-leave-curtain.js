/**
 * Self-check for the script that covers the workbench with the page's own
 * background from the moment the page is told to leave.
 *
 *   node scripts/test-leave-curtain.js
 *
 * The script is JavaScript inside the Kotlin raw string of
 * `MainActivity.injectLeaveCurtain()`, handed to `evaluateJavascript`, so
 * nothing compiles it and `LeaveCurtainWiringTest` can only match its text.
 * What it has to get right is when the curtain is up and when it is not, and
 * all of that is behaviour. The curtain has to exist as soon as the listener
 * returns, before any timer, because the workbench's shutdown runs inside the
 * same dispatch and the first frame painted after it must already be covered.
 * A leave the workbench vetoes has to end up with no curtain, whichever of the
 * two listeners runs first and whichever way it cancels, because the page the
 * user is then asked about is the one they have to be able to see. And nothing
 * may be left standing: a navigation that never commits, one the app hands to
 * another app or refuses (the page is told, `vscodroid-navigation-declined`), a
 * page restored from the back-forward cache, a second leave after a first that
 * was vetoed or declined.
 *
 * This extracts the real script, runs it under `vm` against a stub window, and
 * drives the listeners it installs with fake events and a fake clock.
 *
 * NEGATIVE CONTROL, measured, each by editing the script and running this:
 * raising the curtain from the zero-delay timer instead of the listener fails
 * six cases, the first of them the one that looks right after the dispatch;
 * removing the veto check fails the four that cancel the event and the stale
 * timer case; reading `defaultPrevented` alone fails the one that sets only
 * `returnValue`; dropping the five-second timer fails that case and the stale
 * timer case; dropping the `pageshow` listener fails the back-forward case and
 * the one that counts listeners; not lowering the old curtain before raising a
 * new one fails the replacement case; leaving the timer running when the
 * curtain is lowered fails the stale timer case; dropping the guard fails the
 * one that runs the script twice; a fixed dark colour for a page that has none
 * fails the two colourless cases; dropping the `vscodroid-navigation-declined`
 * listener fails the case that declines and the one that counts listeners; and
 * lowering the curtain on it without clearing the timer fails the case that
 * declines.
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

/**
 * The body of the raw string in `injectLeaveCurtain()`, with the indentation
 * `trimIndent()` removes taken off, which is the text the WebView is given.
 */
function extractScript() {
    const lines = fs.readFileSync(MAIN_ACTIVITY, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('private fun injectLeaveCurtain()'));
    assert.notStrictEqual(
        fn, -1,
        'injectLeaveCurtain() is gone from MainActivity.kt, so this check has nothing to ' +
        'run. If the script moved, point this at its new home rather than deleting the check.',
    );

    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close !== -1 && close > open + 1,
        'could not find the raw string in injectLeaveCurtain(); its shape changed');

    const body = lines.slice(open + 1, close);
    const indent = Math.min(
        ...body.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
    );
    const js = body.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
    assert.ok(!js.includes('$'),
        'the script gained a Kotlin interpolation this check does not know how to fill');
    assert.ok(js.includes("addEventListener('beforeunload'"),
        'the extracted text installs no beforeunload listener, so the wrong block was extracted');
    return js;
}

const SCRIPT = extractScript();

/**
 * A page with the script installed: the listeners it registered, the elements
 * it put on the root, and a clock that only moves when the case moves it.
 */
function page({ rootColor = '#1e1e1e', bodyColor = '', body = true, install = 1 } = {}) {
    let now = 0;
    let nextId = 1;
    let timers = [];
    const listeners = [];
    const onRoot = [];

    const element = () => ({
        style: { cssText: '' },
        attributes: {},
        setAttribute(name, value) { this.attributes[name] = value; },
        remove() {
            const at = onRoot.indexOf(this);
            if (at !== -1) onRoot.splice(at, 1);
        },
    });
    const documentElement = {
        style: { backgroundColor: rootColor },
        appendChild(el) { onRoot.push(el); return el; },
    };
    const document = {
        documentElement,
        body: body ? {} : null,
        createElement: () => element(),
    };
    const win = {
        addEventListener: (type, fn, capture) => listeners.push({ type, fn, capture }),
    };
    const context = vm.createContext({
        window: win,
        document,
        getComputedStyle: () => ({ backgroundColor: bodyColor }),
        setTimeout: (fn, ms) => {
            const id = nextId++;
            timers.push({ id, at: now + (ms || 0), fn });
            return id;
        },
        clearTimeout: (id) => { timers = timers.filter((t) => t.id !== id); },
    });
    for (let i = 0; i < install; i += 1) vm.runInContext(SCRIPT, context);

    return {
        listeners,
        onRoot,
        timerCount: () => timers.length,
        /** Runs every timer due within `ms`, in order, and leaves the clock there. */
        advance(ms) {
            const target = now + ms;
            for (;;) {
                const due = timers.filter((t) => t.at <= target).sort((a, b) => a.at - b.at || a.id - b.id)[0];
                if (!due) break;
                timers = timers.filter((t) => t !== due);
                now = due.at;
                due.fn();
            }
            now = target;
        },
        /**
         * Delivers one beforeunload to every listener in registration order, the
         * way the page's own listeners are called. `workbench` is a listener the
         * case adds to stand in for the lifecycle service: it can run before the
         * script's (`before`) or after it, and cancel the event the way
         * `vetoBeforeUnload` does, with `preventDefault` and `returnValue`, or
         * with only one of the two.
         */
        leave({ workbench = null, before = false } = {}) {
            const event = {
                type: 'beforeunload',
                defaultPrevented: false,
                returnValue: '',
                preventDefault() { this.defaultPrevented = true; },
            };
            const ours = listeners.filter((l) => l.type === 'beforeunload').map((l) => l.fn);
            const stand = workbench ? [() => {
                if (workbench.preventDefault) event.preventDefault();
                if (workbench.returnValue) event.returnValue = workbench.returnValue;
            }] : [];
            const order = before ? [...stand, ...ours] : [...ours, ...stand];
            order.forEach((fn) => fn(event));
            return event;
        },
        pageshow() {
            listeners.filter((l) => l.type === 'pageshow').forEach((l) => l.fn({ persisted: true }));
        },
        /** What `MainActivity.liftLeaveCurtain` dispatches when the app turns the navigation down. */
        declined() {
            listeners
                .filter((l) => l.type === 'vscodroid-navigation-declined')
                .forEach((l) => l.fn({ type: 'vscodroid-navigation-declined' }));
        },
    };
}

const cases = [];
const add = (name, check) => cases.push([name, check]);

add('the curtain is on the root as soon as the listener returns, before any timer ran', () => {
    const p = page();
    p.leave();
    assert.strictEqual(p.onRoot.length, 1, 'no curtain was raised in the dispatch');
    const css = p.onRoot[0].style.cssText;
    for (const part of ['position:fixed', 'top:0', 'left:0', 'right:0', 'bottom:0', 'z-index:2147483647']) {
        assert.ok(css.includes(part), `the curtain does not carry \`${part}\`: ${css}`);
    }
    assert.ok(css.includes('background:#1e1e1e'), `the curtain is not the page's colour: ${css}`);
    assert.strictEqual(p.onRoot[0].attributes['aria-hidden'], 'true');
});

add('a leave nobody cancels keeps the curtain after the next task', () => {
    const p = page();
    p.leave();
    p.advance(0);
    assert.strictEqual(p.onRoot.length, 1, 'the curtain went away for a leave that is happening');
});

add('a veto from a listener that runs after the script lowers it', () => {
    const p = page();
    p.leave({ workbench: { preventDefault: true, returnValue: 'unsaved' } });
    assert.strictEqual(p.onRoot.length, 1, 'the curtain was not up for the dispatch the veto came in');
    p.advance(0);
    assert.strictEqual(p.onRoot.length, 0, 'a vetoed leave left the curtain over the page');
});

add('a veto from a listener that runs before the script lowers it', () => {
    const p = page();
    p.leave({ workbench: { preventDefault: true, returnValue: 'unsaved' }, before: true });
    p.advance(0);
    assert.strictEqual(p.onRoot.length, 0, 'a vetoed leave left the curtain over the page');
});

add('a veto that only sets returnValue lowers it', () => {
    const p = page();
    p.leave({ workbench: { returnValue: 'unsaved' } });
    p.advance(0);
    assert.strictEqual(p.onRoot.length, 0, 'a leave cancelled through returnValue left the curtain');
});

add('a veto that only calls preventDefault lowers it', () => {
    const p = page();
    p.leave({ workbench: { preventDefault: true } });
    p.advance(0);
    assert.strictEqual(p.onRoot.length, 0, 'a leave cancelled through preventDefault left the curtain');
});

add('a navigation that never commits gets the page back after five seconds', () => {
    const p = page();
    p.leave();
    p.advance(4999);
    assert.strictEqual(p.onRoot.length, 1, 'the curtain was lowered before five seconds');
    p.advance(1);
    assert.strictEqual(p.onRoot.length, 0, 'the curtain is still there after five seconds');
    assert.strictEqual(p.timerCount(), 0, 'a timer is left running');
});

add('a navigation the app declines takes the curtain down at once', () => {
    const p = page();
    p.leave();
    assert.strictEqual(p.onRoot.length, 1, 'no curtain was raised for the leave');
    // The app answers a task or more later, after the veto check has run.
    p.advance(0);
    p.declined();
    assert.strictEqual(p.onRoot.length, 0, 'the curtain stayed over a page nothing is replacing');
    assert.strictEqual(p.timerCount(), 0, 'its five second timer is still running');
});

add('a declined navigation with no curtain up is harmless', () => {
    const p = page();
    p.declined();
    assert.strictEqual(p.onRoot.length, 0);
    assert.strictEqual(p.timerCount(), 0);
    p.leave();
    assert.strictEqual(p.onRoot.length, 1, 'a declined navigation before it left the next leave uncovered');
});

add('a leave after a declined one is covered again for its own five seconds', () => {
    const p = page();
    p.leave();
    p.advance(1000);
    p.declined();
    p.advance(1000);
    p.leave();
    assert.strictEqual(p.onRoot.length, 1, 'the second leave was not covered');
    p.advance(4999);
    assert.strictEqual(p.onRoot.length, 1, 'the declined leave\'s timer took the second curtain down');
    p.advance(1);
    assert.strictEqual(p.onRoot.length, 0);
});

add('a page restored from the back-forward cache is not left covered', () => {
    const p = page();
    p.leave();
    p.pageshow();
    assert.strictEqual(p.onRoot.length, 0, 'pageshow did not lower the curtain');
});

add('a second leave replaces the curtain rather than stacking another', () => {
    const p = page();
    p.leave();
    p.leave();
    assert.strictEqual(p.onRoot.length, 1, `${p.onRoot.length} curtains are on the root`);
});

add('a lowered curtain leaves no timer to lower the next one early', () => {
    const p = page();
    p.leave({ workbench: { preventDefault: true } });
    p.advance(0);
    assert.strictEqual(p.timerCount(), 0, 'the first leave left its timer running');
    p.advance(1000);
    p.leave();
    p.advance(4000);
    assert.strictEqual(p.onRoot.length, 1, 'the first leave\'s timer took the second curtain down');
    p.advance(1000);
    assert.strictEqual(p.onRoot.length, 0, 'the second curtain outlived its own five seconds');
});

add('the curtain is the root\'s colour when the page script set one', () => {
    const p = page({ rootColor: '#ffffff', bodyColor: 'rgb(30, 30, 30)' });
    p.leave();
    assert.ok(p.onRoot[0].style.cssText.endsWith('background:#ffffff'), p.onRoot[0].style.cssText);
});

add('the curtain is the body\'s colour when the root has none', () => {
    const p = page({ rootColor: '', bodyColor: 'rgb(30, 30, 30)' });
    p.leave();
    assert.ok(p.onRoot[0].style.cssText.endsWith('background:rgb(30, 30, 30)'), p.onRoot[0].style.cssText);
});

add('a page with no colour of its own gets no curtain', () => {
    for (const bodyColor of ['', 'transparent', 'rgba(0, 0, 0, 0)']) {
        const p = page({ rootColor: '', bodyColor });
        p.leave();
        assert.strictEqual(p.onRoot.length, 0, `a curtain was raised over a page with colour "${bodyColor}"`);
        assert.strictEqual(p.timerCount(), 0, 'a timer was started with nothing to lower');
    }
});

add('a document with no body and no root colour is left alone', () => {
    const p = page({ rootColor: '', body: false });
    p.leave();
    assert.strictEqual(p.onRoot.length, 0);
});

add('running the script twice installs one listener of each kind', () => {
    const p = page({ install: 2 });
    const kinds = p.listeners.map((l) => l.type).sort();
    assert.deepStrictEqual(kinds, ['beforeunload', 'pageshow', 'vscodroid-navigation-declined']);
    p.leave();
    assert.strictEqual(p.onRoot.length, 1);
});

add('the beforeunload listener is in the capture phase', () => {
    const p = page();
    const unload = p.listeners.find((l) => l.type === 'beforeunload');
    assert.ok(unload.capture, 'a listener in the bubbling phase runs behind the page\'s own and any ' +
        'that stops propagation');
});

let failed = 0;
for (const [name, check] of cases) {
    try {
        check();
        console.log(`ok   ${name}`);
    } catch (e) {
        failed += 1;
        console.error(`FAIL ${name}\n     ${e.message}`);
    }
}
console.log(`\n${cases.length - failed}/${cases.length} passed`);
process.exit(failed ? 1 : 0);
