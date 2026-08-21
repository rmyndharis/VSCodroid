package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * That no text a layout XML declares is too faint to read.
 *
 * ## Which surfaces this measures
 *
 * Exactly three files' worth, and naming them is the point: a title like "the setup
 * screens are readable" would claim the app, and what is read here is resources.
 *
 * - `res/layout`, every XML file in the directory, for the text elements they declare.
 * - `res/values/colors.xml`, for what each `@color/` reference resolves to.
 * - `res/values/themes.xml`, for the one label colour a layout leaves to the theme.
 *
 * And, as plainly, the surfaces it does NOT reach. Each is real text a user reads, and
 * none of it is covered by anything below:
 *
 * - Text coloured from Kotlin. `SplashActivity` builds the whole download row in code:
 *   its terminal result is pinned by `DownloadStateWiringTest`, which asserts the colour
 *   and the undimming as a pair, and the pack name and the running status label by
 *   nothing at all. `ToolchainPickerAdapter` paints a card's status badge the same way,
 *   and `ExtraKeyButton`, `KeyPageAdapter` and `LongPressPopup` paint the extra key row.
 * - Everything drawn inside the WebView, which is the whole editor: the workbench's own
 *   HTML and CSS, the themes an extension supplies, and anything an extension renders.
 *   That is the majority of the text this app puts on a screen, and no test in this
 *   repository measures a ratio in it.
 * - Grounds that are drawables rather than colours, and any resource qualifier other than
 *   the default: there is no `values-night` or `layout-*` in this module today, and a
 *   variant added later would go unread until this walk is widened to it.
 *
 * Widening any of that is a larger job than this class, and is deliberately not attempted
 * here. What this class must not do is read as though it had been.
 *
 * Six of them were. The brand blue was used as a text colour and measures 3.70:1 on the
 * window and 3.40:1 on a card; two labels were dimmed below the line with `alpha` rather
 * than with a colour; the picker's Continue button took its label colour from Material3,
 * which supplies a dark violet on a dark palette; and a download row's result was painted
 * without undimming the row it was painted on. WCAG AA asks 4.5:1 for text at these
 * sizes, and 3:1 for text large enough to count as large.
 *
 * ## The ratios are computed, never read
 *
 * Every figure quoted in a comment beside these resources is recomputed here from the
 * colours themselves. Asserting the number a comment states would measure the comment.
 * The formula is WCAG 2.1's: an sRGB channel is linearised, the three are weighted into a
 * relative luminance, and the ratio is `(lighter + 0.05) / (darker + 0.05)`. Alpha is
 * composited in sRGB, which is where `View.setAlpha` and `android:alpha` composite, so a
 * dimmed label is measured as the colour a screen actually shows.
 *
 * ## A predicate, not a list
 *
 * This does not name the six texts that were fixed. It walks every layout, finds every
 * element that paints text, resolves the ground it sits on from the nearest ancestor that
 * declares one, and measures. A seventh faint text added tomorrow reddens this without
 * anyone remembering to add it to a list, and a colour changed in `colors.xml` reddens
 * every text that uses it.
 *
 * ⚠️ What it cannot see even on the surfaces it does read. It reads resources, so it
 * proves the declared colours clear the ratio, not that a device renders them that way
 * and not that the layout puts the text on the ground this believes it does. The API 33
 * emulator and `docs/DEVICE_TEST_CHECKLIST.md` are the instrument for that half.
 *
 * The XML is parsed as a document rather than scanned as text, so a commented-out
 * attribute is a comment node and can satisfy nothing. That is stricter than anchoring a
 * search to the start of a line, and it is the reason no such anchor appears below except
 * where Kotlin source is read.
 */
class TextContrastTest {

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val APP_NS = "http://schemas.android.com/apk/res-auto"

        /** WCAG AA, for text below the large-text threshold. */
        const val AA_NORMAL = 4.5

        /** WCAG AA for large text: 18pt, or 14pt when bold. */
        const val AA_LARGE = 3.0

        /**
         * The one card whose ground is swapped from Kotlin.
         *
         * `ToolchainPickerAdapter.bindPickerMode` repaints a selected card with
         * `colorCardSelected`, which is translucent, so the text on a selected card sits
         * on a different ground from the text on an unselected one. Naming the layout
         * here rather than deriving it is a fact read out of Kotlin, and the last case in
         * this class is what stops it going stale.
         */
        const val SWAPPED_GROUND_LAYOUT = "item_toolchain_card.xml"
    }

    private val layoutDir = File("src/main/res/layout")
    private val colorsXml = File("src/main/res/values/colors.xml")
    private val themesXml = File("src/main/res/values/themes.xml")
    private val pickerAdapter =
        File("src/main/kotlin/com/vscodroid/setup/ToolchainPickerAdapter.kt")

    // -- The WCAG arithmetic --

    private data class Rgb(val r: Int, val g: Int, val b: Int)

    /** Relative luminance, WCAG 2.1 section 1.4.3. */
    private fun luminance(c: Rgb): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)
    }

    private fun contrast(a: Rgb, b: Rgb): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /**
     * [fg] drawn at [alpha] over [bg].
     *
     * Composited in sRGB rather than in linear light, because that is where Android
     * composites: `View.setAlpha` and `android:alpha` both end in a paint alpha applied
     * to the 8-bit channel values. Compositing in linear light instead would give a
     * different, and wrong, answer for exactly the dimmed labels this exists to measure.
     */
    private fun over(fg: Rgb, bg: Rgb, alpha: Double) = Rgb(
        (alpha * fg.r + (1 - alpha) * bg.r).roundToInt(),
        (alpha * fg.g + (1 - alpha) * bg.g).roundToInt(),
        (alpha * fg.b + (1 - alpha) * bg.b).roundToInt(),
    )

    // -- Resolving what the resources name --

    private val namedColors: Map<String, String> by lazy {
        assertTrue(
            colorsXml.isFile,
            "colors.xml is not at ${colorsXml.absolutePath}; this test would otherwise " +
                "pass by measuring nothing",
        )
        val found = Regex("""<color\s+name="([^"]+)"\s*>\s*(#[0-9A-Fa-f]+)\s*</color>""")
            .findAll(colorsXml.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        // The parser control. Without it a pattern that stopped matching would report
        // every colour as unresolvable, which reads like a deleted resource rather than
        // a broken parse, and a pattern that matched nothing at all would make the
        // resolution below fail for the wrong reason.
        assertTrue(
            found.size >= 10,
            "only ${found.size} colours were parsed out of colors.xml, so the pattern " +
                "is not matching the file. Parsed: ${found.keys}",
        )
        found
    }

    /** A colour reference resolved to its opaque value and its own alpha. */
    private fun resolve(ref: String): Pair<Rgb, Double> = when {
        ref.startsWith("#") -> literal(ref)
        ref == "@android:color/white" -> Rgb(255, 255, 255) to 1.0
        ref == "@android:color/black" -> Rgb(0, 0, 0) to 1.0
        ref.startsWith("@color/") -> {
            val name = ref.removePrefix("@color/")
            val value = namedColors[name]
            // Loudly, not by skipping. A reference this cannot resolve is a text whose
            // ratio nobody measures, and a guard that quietly declines to measure is the
            // same as no guard.
            checkNotNull(value) { "colors.xml declares no $name, which $ref names" }
            literal(value)
        }
        else -> error(
            "cannot resolve $ref to a colour. Teach this test how before using it on a " +
                "text, or the text goes unmeasured while everything stays green",
        )
    }

    private fun literal(hex: String): Pair<Rgb, Double> {
        val digits = hex.removePrefix("#")
        return when (digits.length) {
            6 -> Rgb(digits.hex(0), digits.hex(2), digits.hex(4)) to 1.0
            8 -> Rgb(digits.hex(2), digits.hex(4), digits.hex(6)) to digits.hex(0) / 255.0
            else -> error("$hex is not a colour this test can read")
        }
    }

    private fun String.hex(at: Int) = substring(at, at + 2).toInt(16)

    /**
     * A ground reference flattened to something opaque.
     *
     * A translucent ground shows the window through it, so it has to be composited before
     * anything is measured against it. `colorCardSelected` is the one that is, and a card
     * painted with it sits on the window on both screens that show one.
     */
    private fun flatten(ref: String): Rgb {
        val (color, alpha) = resolve(ref)
        if (alpha >= 1.0) return color
        val (window, windowAlpha) = resolve("@color/colorBackground")
        check(windowAlpha >= 1.0) { "the window colour is translucent; nothing is behind it" }
        return over(color, window, alpha)
    }

    // -- Walking the layouts --

    /** One text-painting element, with every ground it can be shown on. */
    private class Painted(
        val where: String,
        val colorRef: String,
        val alpha: Double,
        val threshold: Double,
        val grounds: List<Pair<String, Rgb>>,
    )

    private fun layouts(): List<File> {
        assertTrue(
            layoutDir.isDirectory,
            "res/layout is not at ${layoutDir.absolutePath}; this test would otherwise " +
                "pass by walking nothing",
        )
        val files = layoutDir.listFiles()?.filter { it.extension == "xml" }?.sortedBy { it.name }
            .orEmpty()
        assertTrue(files.size >= 5, "only ${files.size} layouts found, so the walk is wrong")
        return files
    }

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    private fun descendants(root: Element): List<Element> {
        val out = mutableListOf(root)
        var i = 0
        while (i < out.size) {
            val children = out[i].childNodes
            for (c in 0 until children.length) {
                val node = children.item(c)
                if (node.nodeType == Node.ELEMENT_NODE) out += node as Element
            }
            i++
        }
        return out
    }

    private fun Element.attr(ns: String, name: String): String? =
        getAttributeNS(ns, name).takeIf { it.isNotEmpty() }

    /**
     * The ground an element's text sits on: the nearest ancestor, or itself, that declares
     * one.
     *
     * Nearest rather than the root, because a card declares its own and a text inside it
     * is not on the window. If nothing up the tree declares one this fails rather than
     * guessing, since a guessed ground is a measurement of nothing.
     */
    private fun groundsFor(el: Element, layout: String): List<Pair<String, Rgb>> {
        val declared = generateSequence<Node>(el) { it.parentNode }
            .filterIsInstance<Element>()
            .firstNotNullOfOrNull {
                it.attr(ANDROID_NS, "background") ?: it.attr(APP_NS, "cardBackgroundColor")
            }
        checkNotNull(declared) {
            "nothing from ${el.tagName} up to the root of $layout declares a background, " +
                "so there is no ground to measure this text against"
        }
        val grounds = mutableListOf(declared to flatten(declared))
        if (layout == SWAPPED_GROUND_LAYOUT) {
            grounds += "@color/colorCardSelected (selected)" to flatten("@color/colorCardSelected")
        }
        return grounds
    }

    /**
     * Whether WCAG counts this text as large, which lowers the ratio it has to clear.
     *
     * WCAG says 18pt, or 14pt bold, and defines a point against the CSS pixel: its own
     * note gives 1pt as 1.333px. Android measures text in sp and has no authoritative
     * mapping to either unit, so this reads an sp as a CSS pixel, which puts the
     * thresholds at 24sp and 18.67sp bold. That is the stricter of the two readings in
     * circulation, the other being to treat an sp as a point directly, and stricter is the
     * right default for a guard. Nothing in these layouts is near the boundary, so the
     * choice decides nothing today: the three texts it calls large all measure above 9:1.
     *
     * Text that declares no size is treated as normal for the same reason.
     */
    private fun isLarge(el: Element): Boolean {
        val sp = el.attr(ANDROID_NS, "textSize")?.removeSuffix("sp")?.toDoubleOrNull()
            ?: return false
        val pt = sp * 0.75
        val bold = el.attr(ANDROID_NS, "textStyle")?.contains("bold") == true
        return pt >= 18.0 || (bold && pt >= 14.0)
    }

    private fun paintedTexts(): List<Painted> = layouts().flatMap { file ->
        descendants(parse(file)).mapNotNull { el ->
            val ref = el.attr(ANDROID_NS, "textColor") ?: el.attr(APP_NS, "titleTextColor")
                ?: return@mapNotNull null
            val id = el.attr(ANDROID_NS, "id")?.substringAfterLast('/') ?: el.tagName
            Painted(
                where = "${file.name}/$id",
                colorRef = ref,
                alpha = el.attr(ANDROID_NS, "alpha")?.toDouble() ?: 1.0,
                threshold = if (isLarge(el)) AA_LARGE else AA_NORMAL,
                grounds = groundsFor(el, file.name),
            )
        }
    }

    private fun measure(text: Painted, ground: Rgb): Double =
        contrast(over(resolve(text.colorRef).first, ground, text.alpha), ground)

    // Locale.ROOT, so a failure message reads the same on a machine whose default locale
    // writes a decimal comma. Nothing here compares the string, but a reader comparing it
    // against a comment in colors.xml would be reading two different notations.
    private fun Double.two() = String.format(java.util.Locale.ROOT, "%.2f", this)

    // -- The cases --

    /**
     * Every text these layouts paint, on every ground it can be painted on.
     *
     * The two failures this was written over are both in here: the brand blue used as a
     * text colour, and `colorOnSurface` dimmed to 0.5, which is 3.62:1 on the window and
     * 3.47:1 on a card.
     */
    @Test
    fun `every text a layout paints clears the ratio WCAG asks for its size`() {
        val texts = paintedTexts()

        // The vacuity control. Everything below is a scan, and a scan that found nothing
        // reports success in exactly the voice of a scan that found nothing wrong. 14
        // texts across 6 layouts when this was written.
        assertTrue(
            texts.size >= 12,
            "only ${texts.size} texts were found across the layouts, so the walk is " +
                "wrong rather than the resources. Found: ${texts.map { it.where }}",
        )

        // The surface, carried into the verdict below. Whoever reads that message is
        // being told a text is unreadable, and the next thing they need is which texts
        // this looked at, or they will read the failure as a statement about the app's
        // text rather than about six layout files.
        val surface = "measured: ${layouts().size} files in ${layoutDir.path} " +
            "(${layouts().joinToString(", ") { it.name }}). Not measured: colours applied " +
            "from Kotlin, and anything the WebView renders"

        val failures = texts.flatMap { text ->
            text.grounds.mapNotNull { (groundRef, ground) ->
                val ratio = measure(text, ground)
                if (ratio >= text.threshold) {
                    null
                } else {
                    "${text.where}: ${text.colorRef} at alpha ${text.alpha} on " +
                        "$groundRef measures ${ratio.two()}:1, and WCAG AA asks " +
                        "${text.threshold}:1 for text this size"
                }
            }
        }

        assertEquals(
            emptyList<String>(), failures,
            "these texts are too faint to read. A colour or an alpha here is a decision " +
                "about whether a sighted user can read the screen at all, and nothing " +
                "else in this project measures one on this surface. $surface",
        )
    }

    /**
     * The negative control, and the whole reason the case above is worth having.
     *
     * A scan that clears everything proves nothing unless the same arithmetic refuses
     * something. Both paths are exercised, because a break in either would leave the scan
     * green over broken resources: the colour path, with the brand blue these layouts
     * moved away from as a text colour, and the alpha path, with `colorOnSurface` at the
     * 0.5 the two dimmed labels used to carry. If alpha were being ignored, the scan
     * would pass over a label restored to 0.5 and the control below is what catches that.
     *
     * The passing halves are asserted in the same case for the same reason: a control
     * that only ever refuses would be satisfied by arithmetic that refuses everything.
     */
    @Test
    fun `the arithmetic refuses the colours and the opacities these layouts moved off`() {
        val window = flatten("@color/colorBackground")
        val card = flatten("@color/colorSurface")
        val blue = resolve("@color/colorPrimary").first
        val onSurface = resolve("@color/colorOnSurface").first
        val lighter = resolve("@color/colorPrimaryText").first

        val refused = listOf(
            "colorPrimary on the window" to contrast(blue, window),
            "colorPrimary on a card" to contrast(blue, card),
            "colorOnSurface at 0.5 on the window" to
                contrast(over(onSurface, window, 0.5), window),
            "colorOnSurface at 0.5 on a card" to contrast(over(onSurface, card, 0.5), card),
        )
        refused.forEach { (what, ratio) ->
            assertTrue(
                ratio < AA_NORMAL,
                "$what measures ${ratio.two()}:1, which clears the $AA_NORMAL:1 line. " +
                    "This is a control: it names the state these layouts were changed " +
                    "away from, so if it passes the scan above is measuring something " +
                    "other than what it claims",
            )
        }

        val accepted = listOf(
            "colorPrimaryText on the window" to contrast(lighter, window),
            "colorPrimaryText on a card" to contrast(lighter, card),
            "colorOnSurface at 0.7 on the window" to
                contrast(over(onSurface, window, 0.7), window),
            "colorOnSurface at 0.7 on a card" to contrast(over(onSurface, card, 0.7), card),
        )
        accepted.forEach { (what, ratio) ->
            assertTrue(
                ratio >= AA_NORMAL,
                "$what measures ${ratio.two()}:1, under the $AA_NORMAL:1 line. The " +
                    "control above would pass for arithmetic that refuses everything, " +
                    "and this is the half that says it does not",
            )
        }
    }

    /**
     * A text button has to name its own colour.
     *
     * The scan above measures what a layout declares, and cannot see a colour that is not
     * there. `Widget.Material3.Button.TextButton` sets `android:textColor` to
     * `m3_text_button_foreground_color_selector`, whose enabled, uncheckable entry is
     * `?attr/colorOnContainer`, which `ThemeOverlay.Material3.Button.TextButton` points at
     * `?attr/colorPrimary`. Read out of material 1.14.0, the version this app resolves. So
     * a text button that declares nothing is labelled in the brand blue, which measures
     * 3.70:1 on the window and 3.40:1 on a card, and deleting the attribute is a silent
     * return to exactly that.
     *
     * A predicate over every such button rather than the two that exist, so the next one
     * added cannot arrive faint.
     */
    @Test
    fun `every text button names a colour of its own`() {
        val buttons = layouts().flatMap { file ->
            descendants(parse(file))
                .filter { it.getAttribute("style").contains("Button.TextButton") }
                .map { file.name to it }
        }

        assertTrue(
            buttons.isNotEmpty(),
            "no text buttons were found in any layout, so this case is vacuous rather " +
                "than satisfied",
        )

        val bare = buttons.filter { (_, el) -> el.attr(ANDROID_NS, "textColor") == null }
            .map { (layout, el) ->
                "$layout/${el.attr(ANDROID_NS, "id")?.substringAfterLast('/') ?: el.tagName}"
            }

        assertEquals(
            emptyList<String>(), bare,
            "these text buttons declare no android:textColor, so their labels come from " +
                "the theme's colorPrimary: 3.70:1 on the window and 3.40:1 on a card, " +
                "both under the ${AA_NORMAL}:1 line",
        )
    }

    /**
     * The picker's Continue button, whose label colour is not in any layout.
     *
     * The button declares a `backgroundTint` and no `textColor`, so its label is the
     * theme's `colorOnPrimary`. Overriding `colorPrimary` without setting that left
     * Material3 supplying its own, a dark violet from the dark palette; white is what the
     * theme names now.
     *
     * ⚠️ The margin here is 0.01. White on this blue measures 4.51:1 against a 4.5:1 line,
     * so any darkening of the button ground breaks it, and the failure message says both
     * numbers because "contrast too low" would not.
     *
     * A predicate over every tinted button that names no colour of its own, rather than
     * over this one button by id.
     */
    @Test
    fun `a tinted button's label clears the ground it is tinted with`() {
        val onPrimary = themeItem("colorOnPrimary")
        checkNotNull(onPrimary) {
            "Theme.VSCodroid sets no colorOnPrimary, so Material3 supplies its own from " +
                "the dark palette and the picker's Continue button is labelled in it"
        }
        val label = resolve(onPrimary).first

        val tinted = layouts().flatMap { file ->
            descendants(parse(file)).filter {
                it.attr(ANDROID_NS, "backgroundTint") != null &&
                    it.attr(ANDROID_NS, "textColor") == null
            }.map { Triple(file.name, it, it.attr(ANDROID_NS, "backgroundTint")!!) }
        }

        assertTrue(
            tinted.isNotEmpty(),
            "no button takes its label from the theme, so this case is vacuous rather " +
                "than satisfied",
        )

        tinted.forEach { (layout, el, tint) ->
            val ground = flatten(tint)
            val ratio = contrast(label, ground)
            val threshold = if (isLarge(el)) AA_LARGE else AA_NORMAL
            val id = el.attr(ANDROID_NS, "id")?.substringAfterLast('/') ?: el.tagName
            assertTrue(
                ratio >= threshold,
                "$layout/$id is labelled in $onPrimary on $tint, which measures " +
                    "${ratio.two()}:1 against a threshold of $threshold:1. This pair " +
                    "clears AA by 0.01 and nothing more, so a darker tint or a dimmer " +
                    "label breaks it",
            )
        }
    }

    /**
     * That the second ground the card is measured on is still the one it gets.
     *
     * `colorCardSelected` is named in this file because the swap happens in Kotlin, in
     * `ToolchainPickerAdapter.bindPickerMode`, and a layout cannot say it. That makes it
     * the one fact here that can go stale silently: if the adapter starts painting a
     * selected card some other colour, the scan goes on measuring a ground no card has.
     *
     * ⚠️ Source text, because the colour is chosen inside an Activity-scoped adapter this
     * module cannot instantiate. Anchored to the start of a line so a commented-out call
     * cannot satisfy it.
     */
    @Test
    fun `the selected card is still painted with the colour this measures`() {
        assertTrue(
            pickerAdapter.isFile,
            "${pickerAdapter.path} is not at ${pickerAdapter.absolutePath}; this test " +
                "would otherwise pass by reading nothing",
        )

        // Anchored at the start of a line, and the character class is the anchor: nothing
        // between the indent and the reference may be a slash, so a `//` cannot get in
        // front of it. Without that, `^\s*.*` would match a commented-out call as
        // readily as a live one, and commenting a line out is how a developer disables
        // something while debugging.
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*R\.color\.colorCardSelected""")
                .containsMatchIn(pickerAdapter.readText()),
            "ToolchainPickerAdapter no longer paints a selected card with " +
                "colorCardSelected, so the second ground this class measures the card's " +
                "text against is a colour nothing shows",
        )
    }

    /**
     * `name` to literal value for one item of the app's theme.
     *
     * A second reader of themes.xml beside [ThemeEdgeToEdgeTest], deliberately: that one
     * asks whether the window attributes are present and equal to a literal, and this one
     * asks what a colour measures. Sharing a parser would tie the two questions together
     * for no gain.
     */
    private fun themeItem(name: String): String? {
        assertTrue(
            themesXml.isFile,
            "themes.xml is not at ${themesXml.absolutePath}; this test would otherwise " +
                "pass by reading nothing",
        )
        val style = Regex("""<style\s+name="Theme\.VSCodroid".*?</style>""", RegexOption.DOT_MATCHES_ALL)
            .find(themesXml.readText())
        checkNotNull(style) { "Theme.VSCodroid is not declared in themes.xml" }
        val items = Regex("""<item\s+name="([^"]+)"\s*>([^<]*)</item>""")
            .findAll(style.value)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        // The parser control, in the shape ThemeEdgeToEdgeTest uses: a pattern that
        // silently matched nothing would report every item as deleted.
        assertTrue(
            items.size >= 5,
            "only ${items.size} items were parsed out of Theme.VSCodroid, so the " +
                "pattern is not matching the file. Parsed: $items",
        )
        return items[name]
    }
}
