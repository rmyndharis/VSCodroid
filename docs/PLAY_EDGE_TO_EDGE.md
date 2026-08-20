# Play Console: "deprecated APIs for edge-to-edge"

**Cleared on 2026-08-19. The three flagged APIs are no longer in the bundle.**

This document said the opposite until that date, and said it firmly: "expected,
library-sourced, and cannot be cleared today. Do not burn a day re-deriving
this." The reasoning was wrong in one specific place, and the rest of this file
exists so the same wrong turn is not taken again.

## What Play flags, and where it came from

Play named three things: `Window.setStatusBarColor`, `Window.setNavigationBarColor`
and `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`. All three were inside one call,
`androidx.activity.enableEdgeToEdge()`, which `MainActivity`, `SplashActivity`
and `ToolchainActivity` each made before `super.onCreate()`.

Measured from the release dex with `dexdump`, de-obfuscated through the build's
own `mapping.txt`:

| API | Held by |
|---|---|
| `setStatusBarColor` | `EdgeToEdgeApi26`, `EdgeToEdgeApi29`, `EdgeToEdgeApi35` |
| `setNavigationBarColor` | `EdgeToEdgeApi26`, `EdgeToEdgeApi29`, `EdgeToEdgeApi35` |
| `layoutInDisplayCutoutMode` | `EdgeToEdgeApi28`, `EdgeToEdgeApi30` |
| `setStatusBarContrastEnforced` | `EdgeToEdgeApi29` |
| `setNavigationBarContrastEnforced` | `EdgeToEdgeApi29`, `EdgeToEdgeApi35` |

## Why the old conclusion was wrong

It described the flagged code as "the pre-API-35 compat half" of
`enableEdgeToEdge()`, and concluded that since Google tells apps to call that
function, the references are unavoidable.

**`EdgeToEdgeApi35` is in the table above.** The references were never confined
to the compat half, so the two obvious escapes both fail: raising `minSdk`
would not reach them, and neither would an `-assumevalues` rule telling R8 that
`SDK_INT >= 33`. Only dropping the call removes the classes.

The second half of the premise had also expired. `enableEdgeToEdge()` is the
right call when an app needs edge-to-edge on API levels that do not enforce it.
This app's `targetSdk` is 36, and API 35+ enforces edge-to-edge with **no
opt-out**, so on every device at or above 35 the function was setting colours
through setters the platform had already turned into no-ops.

## What replaced it

`util/ViewInsets.drawBehindSystemBars()`, plus five attributes in
`values/themes.xml`. Each piece maps to something the old call did:

| `enableEdgeToEdge()` did | Now |
|---|---|
| opt into edge-to-edge below API 35 | `WindowCompat.setDecorFitsSystemWindows(window, false)` |
| pin light system bar icons | `WindowInsetsControllerCompat.isAppearanceLight*Bars = false` |
| set both bars transparent | `android:statusBarColor` / `android:navigationBarColor` in the theme |
| set the cutout mode | `android:windowLayoutInDisplayCutoutMode` in the theme |
| stop the platform enforcing bar contrast | `android:enforceStatusBarContrast` / `android:enforceNavigationBarContrast` in the theme |

The last row was missing when this migration first landed, and its absence was
the one behaviour change the move actually caused. `EdgeToEdgeApi29.setUp` calls
`setStatusBarContrastEnforced(false)` and
`setNavigationBarContrastEnforced(nightMode == 0)`, and every call site passed
`SystemBarStyle.dark()`, so both arrived false. The platform defaults are not
symmetric: `PhoneWindow.generateLayout` defaults the status one to false and the
navigation one to **true**, so omitting them put a scrim behind the navigation
bar the theme had just made transparent.

The bar colours, the cutout mode and the contrast flags move to attributes rather
than disappearing, and that is the point: Play's scan reads bytecode, and an
attribute is not a method call.

Their scope is not uniform, and an earlier version of this section flattened it.
API 35+ ignores the two bar COLOURS and paints from the window background, and it
forces the cutout mode, so those three decide API 33 and 34 and nothing else. It
does **not** ignore `enforceNavigationBarContrast`, which still decides whether a
scrim is drawn behind a 3-button navigation bar on a current image.

`setDecorFitsSystemWindows` stays in the bundle and that is fine. It is not one
of the three Play named, and Play Core's asset pack code puts it there
regardless of what this app calls.

## The regression that testing caught

Dropping the transparent scrims is invisible on API 35+ and very visible below
it. On an API 33 emulator the status bar came back **blue**, the Material3
default, against the app's dark editor. The theme attributes above are what fix
it. Anyone changing this area should test on API 33 as well as a current image;
an API 37 emulator cannot see this class of defect at all, because every API
involved is already a no-op there.

## Measurements

Release APK, `dexdump` over `classes.dex`, before and after:

| Reference | Before | After |
|---|---|---|
| `Window.setStatusBarColor` | 3 | 0 |
| `Window.setNavigationBarColor` | 3 | 0 |
| `Window.setStatusBarContrastEnforced` | 1 | 0 |
| `Window.setNavigationBarContrastEnforced` | 2 | 0 |
| `layoutInDisplayCutoutMode` written from code | 2 | 0 |
| `androidx.activity.EdgeToEdge*` classes | 8 | 0 |
| `Window.setDecorFitsSystemWindows` | 2 | 2 |

`MaterialDatePicker.onStart()` was a fourth source and is already gone, removed
by the optimized resource shrinking that arrived with AGP 9: with Material's
date-picker layouts shrunk away, the AAPT-generated keep rule that held the
class no longer applied.

## Reading an obfuscated Play location

Still useful, and unchanged. A location like `a.o.a` is class `a.o` plus member
`a`. Grep for the **class**, stripping the last segment:

```
grep -nE '^[^ ].* -> a\.o:' android/app/build/outputs/mapping/release/mapping.txt
```

then read the indented member lines under that class header. Grepping the full
`a.o.a` matches nothing, because member mappings never appear at column 0. The
obfuscated names change every release, so re-derive them per upload.

## The sibling recommendation

"Edge-to-edge may not display for all users" is a different item and was always
actionable. Three real insets defects were found and fixed under it: the display
cutout in landscape, the Toolchains and first-run screens, and status-bar icon
contrast in device light mode. The app's insets handling lives in
`util/ViewInsets.padForSystemBars`, with its own listeners in `MainActivity` and
`ExtraKeyRow` where the IME inset has to be folded in as well.
