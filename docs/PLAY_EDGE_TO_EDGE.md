# Play Console: "deprecated APIs for edge-to-edge" — read this before investigating

**The warning is expected, library-sourced, and cannot be cleared today. Do not
burn a day re-deriving this.** Verified 2026-08-14.

Play flags `Window.setStatusBarColor`, `Window.setNavigationBarColor` and
`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` in the app bundle. De-obfuscated
against this repo's own R8 map (`android/app/build/outputs/mapping/release/mapping.txt`):

| Play location | Real symbol |
|---|---|
| `a.o.a` | `androidx.activity.EdgeToEdgeApi26.setUp()` |
| `a.q.a` | `androidx.activity.EdgeToEdgeApi29.setUp()` |
| `a.n.b` | `androidx.activity.EdgeToEdge` (`enableEdgeToEdge()` internals) |
| `com.google.android.material.datepicker.n.y` | `MaterialDatePicker.onStart()` |

(The obfuscated names change every release. Re-derive with
`grep -nE '^[^ ].* -> <name>:' .../mapping.txt` — member lines under a class
header carry the method mapping.)

Facts, with sources:

- The flagged code is the pre-API-35 compat half of `enableEdgeToEdge()` itself —
  the API Google's other recommendation tells apps to call. On devices below 35
  it must call the deprecated setters to draw scrims; on 35+ a different path
  runs. Play's scan is static and flags the bytecode's existence.
- No androidx.activity release avoids the references — they exist at
  androidx-main tip-of-tree (`EdgeToEdge.kt`, `@Suppress("DEPRECATION")` on every
  impl including the API-35 one). Material 1.14.0's `EdgeToEdgeUtils` is
  identical to alpha09 on this point. Sources: androidx-main `EdgeToEdge.kt`;
  material tags `1.14.0-alpha09`/`1.14.0`; material issue #4626.
- `MaterialDatePicker` is not used by app code; R8 keeps it via an
  AAPT-generated keep rule from Material's own layouts (see
  `build/outputs/mapping/release/configuration.txt`).
- `SHORT_EDGES` never executes: minSdk 33 selects the API-30+ path (`ALWAYS`).
- The warning is a non-blocking recommendation. Evidence (no formal Google
  statement exists): Flutter team, flutter/flutter#169810 — "know that it will
  not impact your users"; dotnet/android#10304 (app with the warning published);
  Google's own SDKs trigger it. Expect it to reappear on every upload until
  AndroidX changes; that is not a regression.
- The AAB embeds the R8 map (`BUNDLE-METADATA/.../proguard.map`), so Play
  already has the mapping — this scan simply reports obfuscated names anyway.

What WAS actionable was the sibling recommendation ("Edge-to-edge may not
display for all users"): three real insets defects were found and fixed —
display cutout in landscape, the Toolchains/first-run screens, and status-bar
icon contrast in device light mode. See the CHANGELOG entries that landed with
this document for what each one was.
