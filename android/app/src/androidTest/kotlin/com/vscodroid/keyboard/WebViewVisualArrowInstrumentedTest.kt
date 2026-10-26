package com.vscodroid.keyboard

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.PixelCopy
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vscodroid.ToolchainActivity
import com.vscodroid.webview.VSCodroidWebView
import java.io.File
import java.io.FilterOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A visible regression through the production WebView and key injector. */
@RunWith(AndroidJUnit4::class)
class WebViewVisualArrowInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: WebView
    private lateinit var injector: KeyInjector
    private lateinit var steps: File
    private var sequence = 0

    private data class Case(val kind: String, val offset: Int, val key: String)

    private class Pending {
        val done = CountDownLatch(1)
        val answer = AtomicReference<String?>(null)
    }

    @Test
    fun nativeHorizontalArrowsKeepTheirVisualMovement() {
        val arguments = InstrumentationRegistry.getArguments()
        val token = arguments.getString("visualCheckpointToken")
        assumeTrue("supply a fresh visualCheckpointToken and view every checkpoint before acknowledging it",
            token != null && Regex("[a-f0-9]{32}").matches(token))
        val selected = arguments.getString("visualArrowCase") ?: "input-0-left"
        val cases = mapOf(
            "input-0-left" to Case("input", 0, "ArrowLeft"),
            "input-3-right" to Case("input", 3, "ArrowRight"),
            "textarea-0-left" to Case("textarea", 0, "ArrowLeft"),
            "textarea-3-right" to Case("textarea", 3, "ArrowRight"),
        )
        val case = requireNotNull(cases[selected]) { "unknown visualArrowCase: $selected" }
        steps = File(instrumentation.targetContext.cacheDir, "webview-arrow-$token")
        check(steps.mkdir()) { "checkpoint directory must be fresh" }
        lateinit var baseline: JSONObject

        ActivityScenario.launch(ToolchainActivity::class.java).use { scenario ->
            try {
                checkpoint("host", "Install the baseline fixture")
                baseline = exercise(scenario, case, guarded = false)
                val guarded = exercise(scenario, case, guarded = true)
                checkpoint("comparison", "Compare the guarded and native results", JSONObject().apply {
                    put("native", baseline)
                    put("guarded", guarded)
                })
                assertTrue("the native baseline did not move, so this case proves no visual movement",
                    baseline.getInt("start") != case.offset)
                assertEquals("the guard changed the renderer's visual move", baseline.getInt("start"), guarded.getInt("start"))
                assertEquals(baseline.getInt("end"), guarded.getInt("end"))
                assertEquals("probe", guarded.getString("active"))
            } finally {
                scenario.onActivity {
                    if (::webView.isInitialized) webView.destroy()
                }
            }
        }
    }

    private fun exercise(
        scenario: ActivityScenario<ToolchainActivity>,
        case: Case,
        guarded: Boolean,
    ): JSONObject {
        val phase = if (guarded) "guarded" else "native"
        val loaded = CountDownLatch(1)
        scenario.onActivity { activity ->
            if (!::webView.isInitialized) {
                webView = WebView(activity)
                VSCodroidWebView.configure(webView)
                activity.setContentView(webView)
                injector = KeyInjector(webView)
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    loaded.countDown()
                }
            }
            webView.loadDataWithBaseURL("https://rtl-probe.invalid/", PAGE, "text/html", "utf-8", null)
        }
        checkpoint("$phase-load", "View the fixture before allowing its ready check")
        assertTrue("the fixture did not finish loading", loaded.await(5, TimeUnit.SECONDS))

        if (guarded) {
            scenario.onActivity { injector.setupModifierInterceptor() }
            checkpoint("guard-installed", "View the full interceptor before checking installation")
            val installed = evaluate(scenario, "JSON.stringify(window.rtlProbe.report())")
            checkpoint("guard-measured", "View the interceptor installation measurement")
            assertTrue("the production interceptor did not install", read(installed).getBoolean("guarded"))
        }

        val spec = JSONObject().apply {
            put("kind", case.kind)
            put("offset", case.offset)
            put("key", case.key)
            put("phase", phase)
        }
        val prepared = evaluate(scenario, "JSON.stringify(window.rtlProbe.prepare($spec))")
        val before = read(prepared)
        checkpoint("$phase-prepared", "View the focused field and starting caret before pressing ${case.key}")
        assertEquals("probe", before.getString("active"))
        assertEquals(case.offset, before.getInt("start"))
        assertEquals(case.offset, before.getInt("end"))
        assertTrue("this runtime has no Selection.modify", before.getBoolean("selectionModify"))

        scenario.onActivity { injector.injectKey(case.key) }
        checkpoint("$phase-pressed", "View the completed native press before measuring its result")
        val measured = read(evaluate(scenario, "JSON.stringify(window.rtlProbe.snapshot())"))
        checkpoint("$phase-measured", "View the screen paired with the measurement", measured)
        assertEquals("probe", measured.getString("active"))
        assertEquals("\u05d0\u05d1\u05d2", measured.getString("value"))
        assertEquals(measured.getInt("start"), measured.getInt("end"))
        val events = measured.getJSONArray("events")
        assertEquals("the native press did not complete exactly once", 1, events.length())
        val event = events.getJSONObject(0)
        assertEquals(case.key, event.getString("key"))
        assertTrue("the injector used a synthetic DOM keydown", event.getBoolean("trusted"))
        assertTrue("the injector used a synthetic DOM keyup", event.getBoolean("keyupTrusted"))
        assertFalse(event.getBoolean("ctrl") || event.getBoolean("alt") || event.getBoolean("shift") || event.getBoolean("meta"))
        assertEquals("the wrong interceptor state was loaded", guarded, measured.getBoolean("guarded"))
        assertEquals("the guard did not decide the collapsed-caret native press", guarded, event.getBoolean("prevented"))
        return measured
    }

    private fun evaluate(scenario: ActivityScenario<ToolchainActivity>, script: String): Pending {
        val pending = Pending()
        scenario.onActivity {
            webView.evaluateJavascript(script) {
                pending.answer.set(it)
                pending.done.countDown()
            }
        }
        return pending
    }

    private fun read(pending: Pending): JSONObject {
        assertTrue("JavaScript did not answer", pending.done.await(5, TimeUnit.SECONDS))
        val raw = requireNotNull(pending.answer.get())
        val decoded = JSONTokener(raw).nextValue()
        check(decoded is String) { "JavaScript returned no JSON string: $raw" }
        return JSONObject(decoded)
    }

    private fun checkpoint(label: String, next: String, measured: JSONObject? = null) {
        val step = ++sequence
        check(step <= 32)
        val stem = "%03d".format(java.util.Locale.ROOT, step)
        val screenshot = if (::webView.isInitialized) {
            val rendered = CountDownLatch(1)
            val copied = AtomicReference<Bitmap>()
            val copyResult = AtomicReference<Int>()
            instrumentation.runOnMainSync {
                val window = (webView.context as ToolchainActivity).window
                val decor = window.decorView
                copied.set(Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888))
                webView.postVisualStateCallback(step.toLong(), object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        webView.viewTreeObserver.registerFrameCommitCallback {
                            PixelCopy.request(window, copied.get(), { result ->
                                copyResult.set(result)
                                rendered.countDown()
                            }, webView.handler)
                        }
                        webView.invalidate()
                    }
                })
            }
            try {
                assertTrue("WebView did not render the checkpoint", rendered.await(5, TimeUnit.SECONDS))
                assertEquals("WebView frame copy failed", PixelCopy.SUCCESS, copyResult.get())
                copied.get()
            } catch (failure: Throwable) {
                copied.get()?.recycle()
                throw failure
            }
        } else {
            checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "no screenshot at $label" }
        }
        try {
            File(steps, "$stem.png").outputStream().use { target ->
                val bounded = object : FilterOutputStream(target) {
                    private var count = 0L
                    override fun write(value: Int) {
                        check(++count <= 4L * 1024 * 1024) { "checkpoint PNG limit" }
                        out.write(value)
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        count += length
                        check(count <= 4L * 1024 * 1024) { "checkpoint PNG limit" }
                        out.write(bytes, offset, length)
                    }
                }
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, bounded))
            }
        } finally {
            screenshot.recycle()
        }
        val status = JSONObject().apply {
            put("step", step)
            put("label", label)
            put("next", next)
            put("screenshot", "$stem.png")
            put("measured", measured)
            put("webviewPackage", WebView.getCurrentWebViewPackage()?.let { "${it.packageName}/${it.versionName}" })
        }
        File(steps, "$stem.json").writeText(status.toString())
        instrumentation.sendStatus(1, android.os.Bundle().apply {
            putString("visualCheckpoint", status.toString())
        })
        val ack = File(steps, "$stem.ack")
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (!ack.isFile && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        check(ack.isFile && ack.length() <= 8 && ack.readText() == step.toString()) { "checkpoint $step was not acknowledged" }
        check(ack.delete())
    }

    private companion object {
        val PAGE = """
            <!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Visual horizontal arrows</title>
            <style>body{margin:24px;background:white;color:black;font:16px sans-serif}
            .line{display:flex;align-items:center;gap:12px}#field{flex:1;min-width:0}
            input,textarea{box-sizing:border-box;width:100%;font:40px sans-serif;border:2px solid #333;caret-color:red}
            textarea{height:100px}button{font:18px sans-serif}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style>
            <h2>Visual horizontal arrows</h2><div class="line"><button>Left</button><div id="field"></div><button>Right</button></div>
            <pre id="status">Fixture loaded. Waiting for preparation.</pre>
            <script>
            (function(){
                var field=null, spec=null, down=null, records=[];
                function snapshot(){return {active:document.activeElement && document.activeElement.id,
                    value:field && field.value,start:field && field.selectionStart,end:field && field.selectionEnd,
                    guarded:window.__vscodroid_modifier_interceptor===true,
                    selectionModify:!!(window.getSelection && typeof window.getSelection().modify==='function'),
                    userAgent:navigator.userAgent,events:records};}
                function show(){document.getElementById('status').textContent=JSON.stringify({spec:spec,state:snapshot()},null,2);}
                window.rtlProbe={snapshot:snapshot,report:function(){show();return snapshot();},prepare:function(value){spec=value;records=[];down=null;
                    field=document.createElement(spec.kind);field.id='probe';field.dir='rtl';
                    field.value='\u05d0\u05d1\u05d2';field.spellcheck=false;
                    document.getElementById('field').replaceChildren(field);field.focus();
                    field.setSelectionRange(spec.offset,spec.offset);show();return snapshot();}};
                window.addEventListener('keydown',function(e){if(e.key!=='ArrowLeft' && e.key!=='ArrowRight')return;
                    down={event:e,key:e.key,trusted:e.isTrusted,ctrl:e.ctrlKey,alt:e.altKey,shift:e.shiftKey,meta:e.metaKey};},true);
                window.addEventListener('keyup',function(e){if(!down || e.key!==down.key)return;var press=down;down=null;
                    setTimeout(function(){records.push({key:press.key,trusted:press.trusted,keyupTrusted:e.isTrusted,
                        prevented:press.event.defaultPrevented,ctrl:press.ctrl,alt:press.alt,shift:press.shift,meta:press.meta});show();},0);},true);
                document.addEventListener('selectionchange',function(){if(field)show();});
            })();
            </script>
        """.trimIndent()
    }
}
