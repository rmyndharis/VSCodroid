/**
 * Syntax check for every JavaScript raw string the Android shell hands to the WebView.
 *
 * Several of the page scripts have dedicated behavioral harnesses under `scripts/test-*.js`,
 * while the remaining injectors (safe-area CSS, touch context menu, list-edit keeper,
 * touch-target CSS (only its status bar scrolling has one, `test-status-bar-scroll.js`),
 * clipboard read fallback, memory-pressure forwarder, bridge token,
 * extension callback relay, and the connection health probe) only run inside a live
 * WebView. A syntax error in one of those unharnessed raw strings compiles cleanly in
 * Kotlin, passes every unit test that checks substring wiring, and fails silently at
 * runtime because `evaluateJavascript` drops a script that fails to parse and receives
 * a `null` result callback.
 *
 * This check walks the Kotlin source tree, discovers page scripts both by their
 * `evaluateJavascript` call sites and by the `inject*` / script-producer declarations
 * that own them, substitutes a valid JavaScript identifier for each Kotlin string
 * template, and compiles every script as a classic script with `vm.Script`.
 *
 *   node scripts/test-page-scripts.js
 */

'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..');
const KOTLIN_MAIN = path.join(ROOT, 'android/app/src/main/kotlin');

// Functions that return a JavaScript raw string to a caller that passes the
// returned value to evaluateJavascript.
const SCRIPT_PRODUCERS = new Set([
    'connectionHealthProbe',
    'uiScaleScript',
    'plainTextPageScript',
]);

function walkKotlinFiles(dir) {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) return walkKotlinFiles(full);
        return entry.name.endsWith('.kt') ? [full] : [];
    });
}

function trimIndent(raw) {
    const lines = raw.split('\n');
    if (lines.length && lines[0].trim() === '') lines.shift();
    if (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
    const nonBlank = lines.filter((line) => line.trim() !== '');
    const indent = nonBlank.length
        ? Math.min(...nonBlank.map((line) => line.length - line.trimStart().length))
        : 0;
    return lines.map((line) => (line.trim() !== '' ? line.slice(indent) : '')).join('\n');
}

/**
 * Replaces Kotlin raw-string templates (`${'$'}`, `${...}`, and `$identifier`)
 * with valid JavaScript text so `vm.Script` sees the grammar the page sees after
 * Kotlin string interpolation runs. Unknown or malformed template shapes fail
 * closed instead of passing through to the parser by accident.
 */
function replaceKotlinTemplates(text, label) {
    let out = '';
    for (let i = 0; i < text.length;) {
        if (text[i] === '$') {
            if (text.startsWith("${'$'}", i)) {
                out += '$';
                i += 6;
                continue;
            }
            if (text[i + 1] === '{') {
                let depth = 0;
                let j = i + 1;
                let quote = null;
                for (; j < text.length; j += 1) {
                    const ch = text[j];
                    if (quote) {
                        if (ch === '\\') {
                            j += 1;
                            continue;
                        }
                        if (ch === quote) quote = null;
                        continue;
                    }
                    if (ch === '"' || ch === '\'') {
                        quote = ch;
                        continue;
                    }
                    if (ch === '{') depth += 1;
                    if (ch === '}') {
                        depth -= 1;
                        if (depth === 0) break;
                    }
                }
                assert.strictEqual(
                    depth,
                    0,
                    `${label}: unclosed Kotlin template starting at offset ${i}`,
                );
                out += '__K__';
                i = j + 1;
                continue;
            }
            const bare = /^\$([A-Za-z_]\w*)/.exec(text.slice(i, i + 120));
            if (bare) {
                out += '__K__';
                i += bare[0].length;
                continue;
            }
            assert(
                text[i + 1] !== '`' && !/^[\u0080-\uffff]/.test(text[i + 1] || ''),
                `${label}: unsupported Kotlin template shape near ${JSON.stringify(text.slice(i, i + 20))}`,
            );
        }
        out += text[i];
        i += 1;
    }
    return out;
}

function compilePageScript(rawBody, label) {
    const js = replaceKotlinTemplates(trimIndent(rawBody), label);
    // WebView.evaluateJavascript runs its input as a classic script, not as a
    // function body: a top-level `return` is a SyntaxError in the page and must
    // be rejected here as well.
    // eslint-disable-next-line no-new
    new vm.Script(js, { filename: label });
}

function collectRawStrings() {
    const rows = [];
    const funRe = /(?:^|\n)[ \t]*(?:(?:private|internal|public|protected|override|suspend)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)\s*\(/g;
    const declRe = /(?:^|\n)[ \t]*(?:(?:private|internal|public|protected|const|override|suspend)\s+)*(?:fun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)\s*\(|val\s+(\w+)\b|var\s+(\w+)\b)/g;

    for (const file of walkKotlinFiles(KOTLIN_MAIN).sort()) {
        const src = fs.readFileSync(file, 'utf8');
        const rel = path.relative(ROOT, file).replace(/\\/g, '/');
        let pos = 0;
        for (;;) {
            const open = src.indexOf('"""', pos);
            if (open === -1) break;
            assert(
                src[open - 1] !== '$',
                `${rel}: multi-dollar raw string interpolation is not supported by test-page-scripts.js`,
            );
            let close = src.indexOf('"""', open + 3);
            assert(close !== -1, `${rel}: unclosed raw string starting at offset ${open}`);
            while (src[close + 3] === '"') close += 1;

            const body = src.slice(open + 3, close);
            const line = src.slice(0, open).split('\n').length;
            const before = src.slice(Math.max(0, open - 240), open);
            const lineStart = src.lastIndexOf('\n', open) + 1;
            const evalCtx =
                /evaluateJavascript\(\s*$/.test(before) ||
                /evaluateJavascript\(\s*$/.test(src.slice(lineStart, open));
            const docStartCtx = /addDocumentStartJavaScript\(/.test(before);

            const head = src.slice(0, open);
            let enclosingFun = null;
            for (let m = funRe.exec(head); m; m = funRe.exec(head)) {
                enclosingFun = m[1];
            }
            funRe.lastIndex = 0;

            let decl = null;
            for (let m = declRe.exec(head); m; m = declRe.exec(head)) {
                decl = m[1] || m[2] || m[3];
            }
            declRe.lastIndex = 0;

            rows.push({
                rel,
                line,
                enclosingFun,
                decl,
                evalCtx,
                docStartCtx,
                producerCtx: SCRIPT_PRODUCERS.has(enclosingFun) || SCRIPT_PRODUCERS.has(decl),
                keyInjectorCtx: rel.endsWith('/KeyInjector.kt'),
                body,
            });
            pos = close + 3;
        }
    }
    return rows;
}

// Verify the negative controls first so a broken compile helper cannot report green.
assert.throws(
    () => compilePageScript('cosnt broken = 1;', 'negative-control:typo'),
    SyntaxError,
    'classic-script compile must reject a keyword typo',
);
assert.throws(
    () => compilePageScript('return 1;', 'negative-control:top-level-return'),
    SyntaxError,
    'classic-script compile must reject a top-level return outside a function',
);
assert.throws(
    () => replaceKotlinTemplates('${unclosed', 'negative-control:unclosed-template'),
    /unclosed Kotlin template/,
    'template replacement must reject an unclosed braced expression',
);
assert.throws(
    () => replaceKotlinTemplates('$`backticked`', 'negative-control:backticked-template'),
    /unsupported Kotlin template shape/,
    'template replacement must reject backticked Kotlin identifiers',
);

const allRawStrings = collectRawStrings();

// Rule 1: every raw string handed directly to evaluateJavascript or addDocumentStartJavaScript.
const evalScripts = allRawStrings.filter((r) => r.evalCtx || r.docStartCtx);
assert.strictEqual(
    evalScripts.length,
    16,
    `expected 16 raw strings directly in evaluateJavascript(...) call sites, found ${evalScripts.length}`,
);

// Rule 2 (independent of call-site formatting): every `private fun inject*` in MainActivity.kt
// must contribute a raw string that is parsed here. Splitting `evaluateJavascript("""...""")`
// into a local variable inside an injector must not drop the script from coverage.
const mainActivityPath = path.join(KOTLIN_MAIN, 'com/vscodroid/MainActivity.kt');
const mainActivitySrc = fs.readFileSync(mainActivityPath, 'utf8');
const injectDecls = [...mainActivitySrc.matchAll(/^\s*private\s+fun\s+(inject\w+)\s*\(/gm)].map(
    (m) => m[1],
);
assert.strictEqual(
    injectDecls.length,
    14,
    `expected 14 private fun inject* declarations in MainActivity.kt, found ${injectDecls.length}`,
);
for (const fn of injectDecls) {
    const match = evalScripts.find((r) => r.enclosingFun === fn);
    assert(
        match,
        `MainActivity.${fn} is declared as a page injector but no evaluateJavascript raw string was found inside it`,
    );
}

// Rule 3: helper functions that return a raw string for the caller to evaluate, plus KeyInjector.
const producerScripts = allRawStrings.filter((r) => r.producerCtx);
assert.strictEqual(
    producerScripts.length,
    SCRIPT_PRODUCERS.size,
    `expected ${SCRIPT_PRODUCERS.size} script-producer raw strings (${[...SCRIPT_PRODUCERS].join(', ')}), found ${producerScripts.length}`,
);

const keyInjectorScripts = allRawStrings.filter((r) => r.keyInjectorCtx);
assert.strictEqual(
    keyInjectorScripts.length,
    3,
    `expected 3 raw strings in KeyInjector.kt, found ${keyInjectorScripts.length}`,
);

const scriptsToParse = [
    ...new Map(
        [...evalScripts, ...producerScripts, ...keyInjectorScripts].map((r) => [
            `${r.rel}:${r.line}`,
            r,
        ]),
    ).values(),
];
assert.strictEqual(
    scriptsToParse.length,
    22,
    `expected 22 total page scripts to parse, found ${scriptsToParse.length}`,
);

// Rule 4: every non-HTML raw string in MainActivity.kt must be covered by scriptsToParse,
// so renaming an `inject*` helper and binding its raw string to a local variable cannot
// silently drop a script from syntax checking.
const parsedLocations = new Set(scriptsToParse.map((r) => `${r.rel}:${r.line}`));
const mainActivityRawStrings = allRawStrings.filter((r) => r.rel.endsWith('/MainActivity.kt'));
assert.strictEqual(
    mainActivityRawStrings.length,
    21,
    `expected 21 total raw strings in MainActivity.kt, found ${mainActivityRawStrings.length}`,
);
const mainActivityHtmlDocs = mainActivityRawStrings.filter((r) =>
    /^\s*<(?:html|div)\b/i.test(r.body),
);
assert.strictEqual(
    mainActivityHtmlDocs.length,
    3,
    `expected 3 HTML fragment/document raw strings in MainActivity.kt, found ${mainActivityHtmlDocs.length}`,
);
for (const r of mainActivityRawStrings) {
    if (/^\s*<(?:html|div)\b/i.test(r.body)) continue;
    assert(
        parsedLocations.has(`${r.rel}:${r.line}`),
        `${r.rel}:${r.line}(${r.enclosingFun || r.decl || 'raw-string'}) is a non-HTML raw string in MainActivity.kt that is not covered by scriptsToParse`,
    );
}

for (const script of scriptsToParse) {
    const owner = script.enclosingFun || script.decl || 'raw-string';
    const label = `${script.rel}:${script.line}(${owner})`;
    compilePageScript(script.body, label);
}

console.log(`Parsed ${scriptsToParse.length} page scripts (${evalScripts.length} evaluateJavascript, ${producerScripts.length} producers, ${keyInjectorScripts.length} KeyInjector).`);
