# VSCodroid Device Test Checklist

> Manual testing checklist for device matrix validation.
> Run after automated tests (`scripts/device-test.sh`) and instrumented tests pass.

## Session Info

| Field | Value |
|-------|-------|
| **App Version** | |
| **Date** | |
| **Tester** | |
| **Device** | |
| **Android Version** | |
| **WebView Version** | |

---

## 1. Device Matrix

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| DM-1 | Pixel device (reference) | Install + full test on Pixel 7/8/9 | All features work | | |
| DM-2 | Samsung device | Install + full test on Galaxy S/A series | All features work, Samsung keyboard compatible | | |
| DM-3 | Budget device (4GB RAM) | Install + open editor + terminal | App runs without OOM, <700MB RAM | | |
| DM-4 | Tablet | Install + test landscape/split-screen | Layout adapts, no cropped UI | | |

## 2. Android Versions

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| AV-1 | Android 13 (API 33) | Full install + test | All features work (minimum supported) | | |
| AV-2 | Android 14 (API 34) | Full install + test | All features work, .so extraction OK | | |
| AV-3 | Android 15 (API 35) | Full install + test | All features work | | |
| AV-4 | Android 16 (API 36) | Full install + test | All features work, 16KB pages OK | | |

## 3. Keyboard Input

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| KB-1 | GBoard typing | Open file, type code with GBoard | Characters appear correctly, no duplication | | |
| KB-2 | Samsung keyboard | Open file, type code with Samsung KB | Characters appear correctly | | |
| KB-3 | SwiftKey | Open file, type with SwiftKey | Characters appear correctly | | |
| KB-4 | Hardware keyboard | Connect BT/USB keyboard, type | All keys work including modifiers. Only hardware answers this row: the automated suite's `hardware_key_chord_reaches_workbench` injects a virtual device, which exercises dispatch but not pairing, layout mapping or how a real HID keyboard reports its modifiers | | |
| KB-5 | Extra Key Row: Tab | Press Tab in editor | Indentation inserted | | |
| KB-6 | Extra Key Row: Esc | Press Esc with menu open | Menu/dialog closes | | |
| KB-7 | Extra Key Row: Ctrl+S | Press Ctrl on EKR then S on keyboard | File saves (no error) | | |
| KB-8 | Extra Key Row: Ctrl+P | Press Ctrl on EKR then P | Quick Open dialog appears | | |
| KB-9 | Extra Key Row trackpad | Drag on the trackpad: slowly first, then keep going without lifting, then diagonally | Cursor steps character by character at first and speeds up the longer the drag gets; a diagonal drag moves on both axes. There are no arrow buttons to press: the trackpad replaced them, so a drag is the only route for a finger. KB-13 covers the other route, the pad's four accessibility actions | | |
| KB-10 | Extra Key Row, brackets on the textarea edit path | Reach the path by setting `editor.editContext` to false in the Settings editor's Remote tab and reopening a file, rather than by finding a device whose WebView is older than 121, which is no longer a device anyone has. Measured through the DevTools protocol on an API 37 emulator: with the setting off the editor holds one `textarea.inputarea` and no `div.native-edit-context`, which is the same path an old WebView takes. Then press { and ( on the key row. Those two are all it types directly: for } latch Shift and press ], and for ) long press the `()` key and pick it out of the popup (KB-14). Which page each key sits on depends on the screen width, so page through rather than counting (KB-16). Confirm the path first in remote debugging: `document.querySelectorAll("textarea.inputarea").length` is 1 | Each character is inserted, and Monaco auto-closes the pair | | |
| KB-11 | Extra Key Row, brackets on the EditContext edit path | On a device whose WebView is 121 or newer, same presses. Confirm the path first: `document.querySelectorAll("textarea.inputarea").length` is 0 and `document.querySelectorAll("div.native-edit-context").length` is at least 1. That count is one per open editor group, not a fixed 2 as this row used to say: measured through the DevTools protocol with a single group open, it is 1 | Each character is inserted. This is the path where anything written to a textarea is inert, so a pass here and a fail on KB-10 means the fix went to the wrong layer | | |
| KB-12 | Extra Key Row keys under a screen reader | Turn TalkBack on, open a file, swipe to a key on the row until it is announced, then double tap to activate it. **`adb shell input tap` cannot answer this row**: it injects below touch exploration, so a single tap types the character and the run looks like a pass whatever the code does. Drive it by hand, or from a test that performs ACTION_CLICK on the node | The character is inserted, exactly once. A modifier announces and latches, and the next key carries it | | |
| KB-13 | Trackpad arrows under a screen reader | With TalkBack on, swipe to the trackpad, open its actions menu and choose each of Move cursor left, right, up and down. Same caveat as KB-12: an injected tap or swipe proves nothing here, because a drag is what touch exploration consumes | The caret moves one step in the chosen direction. A drag is the only other route and a screen reader cannot perform one, so a failure here leaves no way to move the caret at all | | |
| KB-14 | Long-press alternates | With the keyboard up and the caret in a file, touch and hold the `()` key (last on page 1 on a 411dp phone, page 2 on a narrow one) and pick `)` | The popup appears fully on screen, right edge included: it is about two and a half times the width of the key it is centred on, so the edge is where it would run off. The soft keyboard stays up and the key row stays visible throughout, and `)` is inserted. Long press is the only route to that character, so a popup that closes the keyboard costs it entirely | | |
| KB-15 | Ctrl+Enter from the key row | Put the caret in the MIDDLE of a line, tap Ctrl on the key row, then press Enter on the soft keyboard | A new line opens below and the caret moves to it, leaving the line under the caret unsplit (Insert Line Below). A split line means the latch was spent without being applied: the soft keyboard reports Enter as an edit rather than as a key, so this is a different path from every other row key | | |
| KB-17 | Latched Ctrl and a composed word on the EditContext path | On a WebView 121 or newer device with Gboard suggestions on (KB-11's check says which path), latch Ctrl on the row, type a word, then space | The word is inserted plainly and the row's Ctrl clears as the word starts; the space is inserted and no suggest widget opens. Latch Ctrl and type `s` with a non-composing commit (suggestions off, or Samsung keyboard) as the control: the file still saves (KB-7) | | |
| KB-18 | Latched modifier and a frame | Latch Ctrl on the row, tap into a Simple Browser page or an extension webview and type, then tap back into the editor and type `a` | The row's Ctrl clears within a moment of focus entering the frame, and `a` is inserted rather than run as a chord | | |
| KB-19 | Escape on a hardware keyboard | FIRST establish the precondition, because without it this row cannot fail on any build: with the keyboard connected, run `adb shell dumpsys input`, find its `KeyCharacterMapFile`, and confirm that file contains `ESCAPE` with `base: fallback BACK`. Record the keyboard model and the map. Then open a file in the editor, click in it so the editor and not a terminal has focus, and press Esc once | The app stays in the foreground. Only hardware answers this row: an injected Escape carries `Virtual.kcm` and the emulator's own keyboard resolves to `qwerty2.kcm`, and neither declares the fallback. The editor is the target on purpose, and a terminal is not: xterm consumes the Escape keydown and writes `0x1b`, so a terminal never leaves the key unhandled and the route this row exists to test is never entered. Only the keyup leaks with the editor focused, which is enough, because the synthesised press carries the same action | | |
| KB-20 | Escape still reaches the page | Same keyboard and the same editor file, then a terminal: press Esc in the editor with a suggest widget open, and press Esc in a terminal running `cat -v` | The widget closes, and `^[` appears under `cat -v`. This is the control for KB-19: refusing to hand Escape back to Android must not take it from the page, and the two rows fail in opposite directions | | |
| KB-16 | Narrow phone paging | On a device or emulator whose portrait width is 360dp or less, bring the keyboard up and swipe through every page. `adb shell wm size` and `adb shell wm density` give the width in dp: pixels times 160, divided by density | There are more pages than the five a 411dp phone shows: six at 360dp, seven at 320dp. Every key still fills a comfortable target, no label is clipped, and the keys appear in the same order, only broken across more pages. The dots say how many there are | | |
| KB-21 | Keyboard only for text | Tap the Explorer icon, open a file from the tree, then tap a line of text | Stays down for the first two, comes up on the third with the caret where the tap landed | | |
| KB-22 | Deleting after a paste does not multiply a word | The report this exists for is a word repeated many times while deleting pasted code. It did not reproduce on an emulator across four attempts, and the emulator is not the reason: tapping Gboard's own key coordinates with `adb shell input tap` produces a real composing region, a tap on a key row coordinate drives the real `dispatchKeyEvent` path, and `adb shell input swipe X Y X Y 2000` drives Gboard's own backspace auto-repeat, so every ingredient is reachable there. What an emulator cannot vary is the reporter's Gboard build and a WebView old enough to use the textarea path, which is what a real device is for. Open a file, paste a block of code from the clipboard, then type a word with Gboard so a composition is pending (the underlined, uncommitted characters), tap a punctuation key on the extra key row, and hold backspace until the paste is gone. Watch Gboard's suggestion strip while you type, not only the file | Backspace removes one character per repeat and the file only shrinks. The thing to watch for on the way is the suggestion strip offering a word the file does not contain, for example the composed word doubled: that is the keyboard's buffer and the page disagreeing, and it was seen on an emulator without the file ever multiplying, so it is the state the report would grow out of rather than the report itself. Record the device, the Gboard version, the keyboard language and the WebView version | | |
| KB-23 | Control for KB-22 | The same file and the same paste, but type the punctuation from Gboard's own symbol page instead of the extra key row, and delete the same way | Identical behaviour. If KB-22 multiplies and this does not, the row's injected key event is what desynchronised the keyboard; if both do, it belongs to Monaco or to the WebView and not to this app | | |
| KB-24 | Ctrl with a capital typed on the soft keyboard | Tap **ctrl** on the key row so it lights, then use the soft keyboard's own Shift to type a capital `P` | The Command Palette opens, "Type the name of a command to run". Quick Open, "Search files by name", is the failure: it means the capital reached the page without its Shift, so `Ctrl+Shift+P` resolved as `Ctrl+P`. Measured on an API 37 emulator before the fix: the page received `{key:"P", code:"KeyP", ctrl:true, shift:false}`. Try `Ctrl` with a lower-case `s` as the control, which must still be Save and not Save As | | |
| KB-25 | The alternates go away with the row | Hold `{}` on the key row until `[` and `<` appear. With them up, turn the phone over; then hold `{}` again and let the keyboard go, by tapping outside a text area or with the keyboard's own hide key | Both times the alternates disappear with the row. Before the fix they stayed: after the rotation they sat over the middle of the soft keyboard, a screen's width from the key, and after the keyboard went down they sat over the editor with nothing under them, until the user tapped elsewhere. `smallestScreenWidthDp` does not change on rotation, so this is the case the popup's own teardown could not see | | |
| KB-26 | Menu survives the keyboard | With the keyboard up, press and hold a word in a file until the menu opens, then lift | The menu stays open and the keyboard stays up. Tap Rename Symbol: the rename box opens. Reopen it and tap Esc on the key row: it closes. Reopen it and tap the file above it: it closes. Before this row was added the menu closed itself about 60ms after opening | | |
| KB-27 | No chords beside menu items | Open the same menu, then the explorer's (hold a file), then the menubar's File menu | None of them shows a keyboard shortcut next to an item. Every item still runs on a tap | | |
| KB-28 | Trackpad and navigation keys in text boxes | In turn in the Explorer rename box (hold a file, Rename), the Command Palette and the find widget: type a word, drag the trackpad left 2 and type X; latch Shift and drag left 2; press Home and End on the last page. Then serve a page with two inputs from the terminal (`python3 -m http.server 8000` in a folder holding one), open it with `Simple Browser: Show` at `http://127.0.0.1:8000/`, and repeat in its first input. Last, in the Command Palette type `rel` so that Gboard shows it underlined (composing) and drag down 3, then, with `rel` still underlined, swipe the row to its last page and tap PgDn. Record the API level, the WebView version and the Gboard version, and whether Gboard showed the word underlined when each drag and the PgDn began | X lands two characters from the end, the Shift-drag selects two characters with the Shift badge lit until the finger lifts, and Home and End reach the ends of the text, in all four boxes. The composing drag down moves the Command Palette highlight 3, and PgDn then moves it down a page, as on the previous build. Tab on the row still indents in the editor (KB-5), and in the rename box it renames nothing. Before the fix the caret did not move in any of them | | |
| KB-29 | A drag past the edge of a text box | In the rename box with the caret at the end, drag right 4; with it at the start, drag left 4; with it in the middle, latch Alt and drag left 2, then latch Alt again and drag up 2. Repeat in the Command Palette, the find widget and the Problems filter (type `err` first). In the Settings editor tap Editor: Font Size, drag left 4 and right 4. In a file, latch Alt and drag left 2. Record the WebView version, and run `adb shell dumpsys input_method \| grep -E 'mServedView\|mInputShown'` after each | The box or the file stays open and focused, the keyboard and the key row stay up, and the Explorer still shows the old name. `mServedView` names `android.webkit.WebView` and `mInputShown` is true. In the four text boxes the Alt drag left takes the caret to the start of the text from WebView 149 and leaves it where it was below 149; Font Size keeps its value. In a Simple Browser input (KB-28), which nothing guards, drag right past the end and record where focus goes | | |
| KB-30 | Gboard Enter accepts in the Command Palette, Quick Open and an input box such as VSCodroid: Open in Browser | With Gboard, and nothing underlined when Enter is pressed (end the word with a space or a period): in the Command Palette type `about ` and press Gboard's Enter; in Quick Open type part of a file name and a period and press Enter; run **VSCodroid: Open in Browser**, type `abc ` and press Enter. Then latch Ctrl on the key row and repeat the Command Palette case, and in the Explorer create a file with New File the same way. Last, with a Python file open, type `change language mode ` in the Command Palette, then latch Shift on the key row and press Enter; repeat the Quick Open case with Shift latched after typing; and at the end of a line in the Python file latch Shift and press Enter. Record the API level and the Gboard version | Help: About opens once, the file opens and Quick Open closes, and the Open in Browser box closes with a message that no app could open the address. With Ctrl latched, About still opens once and Ctrl clears. New File creates exactly one file. With Shift latched, the list of languages opens in the same box, and the Shift badge goes dark while the keyboard is still up; Quick Open opens the file and closes, as without Shift; and the Python file gets a new line, with nothing run in the terminal. Before the fix each of the first three stayed open with nothing run, because Gboard's Enter in a one-line box reached the page with an empty `code` | | |
| KB-31 | A caret move while a word is composing | With a keyboard that composes, so typed letters show underlined (Gboard 12.4, the API 33 emulator's preload), type a word in a file and, while it is still underlined, press End on the key row, then type one letter. Repeat with a fresh word for a trackpad drag left out of the word, Tab accepting a suggestion, a tap on another line, and a long press on another word. Then open a `.js` file holding `const alpha1 = 1, alpha2 = 2, alpha3 = 3;` and, on an empty line, type `alp`, a space and Backspace, so that `alp` is underlined again under an open suggestion list on `alpha1`; drag the trackpad down 2, press Tab on the key row and type one letter. Repeat with a fresh `alp`, tapping the `alpha2` row instead of the drags and Tab. Last, add the line `kiwi delta alph` under the `const` line, tap just after `kiwi` and type `s` so that the word is underlined, press End, wait a second and press Enter; then type a space and `beta` at the end of the `const` line and, while `beta` is underlined, drag the trackpad down 1 and wait a second. Record the API level and the Gboard version, and whether the word was underlined when each action began | Each time the text stays intact: the underlined word is kept as typed, or replaced once by the suggestion Tab or the tap accepted, the caret goes where the key or the tap put it, and the letter lands at the caret. The long press still opens the menu with the keyboard up. In the `.js` file the drags move the list's highlight to `alpha3` and Tab inserts it, and the tap inserts `alpha2`. The list may then open again with the accepted word as its only row; that is expected. After End and after the last drag the caret is at the end of `kiwis delta alph` with no suggestion list open, and Enter starts a new line; a list of `alpha1`, `alpha2` and `alpha3` opening there, or Enter inserting one of them, means the server lacks patch 0023. Before the fix the keyboard's next edit landed where the underlined word had been: End while `kiwi` was underlined in `alpha delta kiwi charlie` left `alpha delta charlie charlie`, a tap on an empty line followed by `cha` typed `cchcha`, and Tab after the two drags, then `x`, left `alpha3xha3ha3` | | |
| KB-32 | A keyboard suggestion after the caret moves | With a keyboard that does not compose, so typed letters show no underline (Gboard 18, the API 36 emulator's preload), in a new plain-text file: type `kiwi`, tap between `ki` and `wi`, and tap `kiwi` in the suggestion strip. On a new line type `alpha beta charlie delta`, tap between `cha` and `rlie`, and tap `Charlie`. On a new line type `find me here`, tap after `find`, then past the end of the line, and tap the strip's first suggestion. Record the API level and the Gboard version. Over remote debugging, `document.querySelector('.native-edit-context').editContext.text` can be read after each step | The strip never offers a run the file does not hold, such as `kiwiiwik` or `hereereh`. The lines end as `kiwi ` and `alpha beta Charlie delta`, and the third keeps `find me ` and ends in `here` or the word tapped in its place. `editContext.text` equals the line under the caret after every step. Before the fix the three lines became `wiiwik `, `alpha beta charlCharlie ` and `find meereh `: the keyboard's hidden buffer kept a reversed copy of every typed letter after the line, and a selection-only update moved the caret past the keyboard's selection | | |
| KB-33 | The keyboard stays put away, and a read-only file never raises it | Open a file longer than the screen and tap a word. Put the keyboard away with Back, then drag to scroll and fling; tap a word, and repeat with the navigation bar's hide key. Then run **File: Toggle Active Editor Read-only in Session** from the Command Palette, tap a word, and press and hold another until the menu opens; run the command again and tap a word. Then hold a file in the Explorer and choose Rename, then hold the empty space under the files and choose New File. Last, tap a word, type a letter, turn the phone to landscape and back to portrait, and type another letter. Record the API level, the keyboard and its version | The keyboard comes up for each tap on a word, with the caret there, and stays down through every scroll and fling after it was put away. In the read-only file the tap moves the caret with the keyboard down and the menu offers Copy; once the command is run again a tap raises the keyboard. The rename and New File boxes raise it as before. After the rotation the keyboard is up and the second letter lands at the caret. Before the fix the first scroll after Back brought the keyboard back up, and a read-only file raised it for every tap on text. Measured on an API 33 emulator with Gboard 12.4, where a rotation never took the keyboard down and the letter after it landed at the caret. On an API 36 emulator with Gboard 18.2 each turn reports the keyboard down and up again, and there the keyboard was up after the rotation and the second letter landed at the caret. Samsung Keyboard and other keyboards were not measured | | |
| KB-34 | A slow swipe across the key row | Put the caret on a word that occurs several times in a file, so F7 moves it to the next one. On the page with F1 to F8, rest a finger on F7 for about a second, then swipe to the next page without lifting it. Back on that page, hold F7 for a second without moving and lift, then tap F7 twice quickly. On page 1 do the same swipe from `{}`, then from Ctrl, once with Ctrl dark and once with it lit. Next, tap Ctrl dark, hold it until it lights and, still holding it, swipe to the next page with another finger, then lift both. Last, back on page 1 with three-button navigation and Ctrl still lit, hold Ctrl until it goes dark and, still holding it, tap Back with another finger so the keyboard goes down; slide the held finger sideways, lift it, and tap the file to bring the keyboard back. Use a real finger: an injected swipe has no resting phase | Every swipe while the keyboard is up turns the page and does nothing else: the caret stays put, Ctrl is lit or dark as it was before the touch, and no alternates are left over the editor. The still hold moves the caret once, when the finger lifts, and the two quick taps move it twice. After the other finger's swipe the badge beside the page dots still reads ctrl, also once both fingers are up. When the keyboard comes back after the last step, Ctrl is dark and the badge is gone | | |
| KB-35 | Holding Ctrl while typing | Open the Search view and tap its search box. Hold Ctrl on the key row until it lights and, still holding it, type `p` on the soft keyboard with another finger; then lift and type a letter. Close Quick Open with Esc, tap the search box again, tap Ctrl and type `p` as the control. Run it with Gboard 18 (the API 36 emulator's preload) and with Gboard 12.4 (API 33) | With Gboard 18, Quick Open opens while Ctrl is still held and Ctrl goes dark; lifting the finger does not light it again, and the letter after it is typed into Quick Open. The control opens Quick Open too. Gboard 12.4 composes every letter, which makes no chord, so there `p` is typed into the search box and Ctrl goes dark, after the hold as after the tap, and lifting the finger does not light it again | | |
| KB-36 | Toolbars after an Alt chord from the row | Open a file. Latch Alt and tap Esc on the key row. Latch Alt again and drag the trackpad one step left. Then latch Alt and drag the trackpad several steps up, which moves the line | After each, the split button in the editor's title bar is Split Editor Right, its plain action, and a tap on it splits to the right; Split Editor Down is its Alt action and must not be what is left. The menu bar never takes focus. During the drag up the button may switch between the two at each step; it ends on Split Editor Right. The trackpad's step left is a real key press whose Alt release only Chromium can deliver to the page, so this row is the only check that it arrives; its steps up are announced, as Esc is | | |
| KB-37 | Ctrl+Tab from the key row | Open three files one after another. In the last one, latch Ctrl and tap Tab on the key row, then tap the last entry of the list that opens. Then press F1 on the key row's function key page to open the Command Palette; latch Ctrl and tap Tab; latch Ctrl and tap Tab again; latch Ctrl and drag the trackpad one step left; then tap an entry | From the file, the recently used editors list opens with the second editor highlighted and takes the focus, so the keyboard goes down and the key row with it; nothing opens until the tap, which opens the first file. From the Command Palette the list opens in the same box with its input still shown, the keyboard and the row stay up, and the second editor is highlighted; the second Ctrl+Tab moves the highlight down one, the list is still open after the drag, and the tap opens the entry. The list opening an editor on its own at any step means a release from the row reached it. The steps from the file were measured on an API 36 emulator; the Command Palette steps are read from the code | | |

## 4. Screen & Orientation

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| SC-1 | Portrait mode | Open app in portrait | Full UI visible, no overflow | | |
| SC-2 | Landscape mode | Rotate to landscape | UI reflows, editor uses full width | | |
| SC-3 | Rotation mid-edit | Type in editor, rotate device | No data loss, cursor position preserved | | |
| SC-4 | Split-screen | Enter split-screen with another app | VSCodroid resizes correctly | | |
| SC-5 | Display cutout | Test on device with notch/punch-hole | Safe area padding applied, no content clipped | | |
| SC-6 | Foldable (if available) | Fold/unfold device | UI adapts to new dimensions | | |
| SC-7 | Side bar auto-close on a phone | Portrait, open the Explorer, tap a file | Side bar closes on its own, editor takes the full width | | |
| SC-8 | Side bar stays open on a tablet | Same steps on a device wider than 600dp | Side bar stays where it was; `settings.json` has `vscodroid.layout.compactScreen` false and no `vscodroid.layout.autoHideSideBar` at all | | |
| SC-10 | The side bar setting has a control | Settings, search `autoHideSideBar` | The row draws a dropdown offering auto, on and off, not an "Edit in settings.json" link. Pick `on` on a tablet and `off` on a phone and check each one overrides the screen | | |
| SC-9 | A setting you change is the one that applies | Settings, User tab, set `editor.minimap.enabled` true, reopen a file, then restart the app | The minimap appears and is still there after the restart. It is the app's own defaults that must not win here | | |
| SC-11 | UI scale | Command Palette, **VSCodroid: UI Scale**, note the sizes offered and pick the largest. Tap a file in the Explorer, place the caret in a word, type, open a context menu, the Command Palette, a hover and a terminal, rotate, then reload the window and restart the app. Finish with 100% | The offer stops where the page would be under 320 CSS px wide (125% on a 411 dp phone). The whole interface is larger and fills the screen with nothing cut off at the right; taps, the caret, menus, the keyboard and the key row land where they should; the terminal is legible; the size survives the rotation, the reload and the restart, with no flash at 100% on load; 100% restores the original layout | | |

## 5. Editor Operations

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| ED-1 | Create new file | File > New File, type content, Ctrl+S | File saved, visible in explorer | | |
| ED-2 | Open existing file | Click file in explorer | File opens in editor tab | | |
| ED-3 | Large file (10k lines) | Open a 10,000+ line file | File loads, scrolling smooth | | |
| ED-4 | Find & Replace | Ctrl+H, search + replace text | Matches highlighted, replacement works | | |
| ED-5 | Multiple tabs | Open 5+ files in tabs | All tabs accessible, switch works | | |
| ED-6 | Copy/Paste (system) | Copy from external app, paste in editor | Text pastes correctly | | |
| ED-7 | Undo/Redo | Make edits, Ctrl+Z, Ctrl+Shift+Z | Undo and redo work correctly | | |
| ED-8 | Format document | Open JS file, run Format Document (Prettier) | File formatted, no errors | | |
| ED-9 | Application Menu with the keyboard up | Tap a text field so the keyboard rises, then tap the menubar button. Watch it for a few seconds rather than glancing: the failure this catches lasted about 40ms and left the button looking dead | The menu opens and stays open, listing File, Edit, Selection, View, Go and Run. Tapping outside still closes it, and tapping File still opens its submenu. Do not look for Esc here: the key row that carries it is hidden the moment the keyboard drops, which is the very event under test | | |
| ED-10 | Application Menu across a rotation | Open the Application Menu, tap File so its submenu opens, then rotate the device | Both menus close. They must not stay open: the submenu would be anchored where it no longer fits and would be clipped off the edge | | |
| ED-11 | An https preview with a bad certificate | Run `Simple Browser: Show` and enter `https://self-signed.badssl.com/`, then `https://expired.badssl.com/`, then the first one again. Offline variant: a local https server with a self-signed certificate, reached at `https://127.0.0.1:8443` | Each of the first two shows an empty tab plus a toast naming the host, the first saying the certificate is not trusted and the second that it is expired or not yet valid. The third shows no second toast: a repeat of a fact already said is suppressed. No dialog and no way to continue appears at any point | Pass | Verified 2026-08-21 on an API 33 emulator against a local self-signed server reached at `https://10.0.2.2:8443`, which is what the host is called from inside an emulator. Logcat: `TLS refused for 10.0.2.2:8443: UNTRUSTED`. The toast reads `Blocked 10.0.2.2:8443: certificate not trusted. Use http instead.` and renders whole. The pane stays empty, which is the symptom this exists to explain rather than remove. **Re-run: this result predates the subframe navigation rules, which an https preview goes through** |
| ED-12 | Where a preview's own links go | On a debug build with `adb logcat -s VSCodroid.WebViewClient` running, run `Simple Browser: Show` and enter `https://example.com`, then tap the link on that page | The linked page renders in the preview tab; the device browser does not open. Record whether logcat shows anything from the client for that navigation, because that is what this row exists to settle: the platform documents `shouldOverrideUrlLoading` as one that *may* be called for subframes, and whether it is here decides whether the subframe rules are live behaviour or defence in depth. The refusal line is `Logger.d`, so a release build prints nothing either way | | |
| ED-13 | Running a file under the debugger | Put `debugger;` in a `.js` file in the projects folder, open it, run **Debug: Select and Start Debugging** and pick **Node.js: Run Current File** | Execution stops on that line with the gutter arrow and the debug toolbar, and the terminal shows `Debugger attached.` A session that starts, shows the toolbar and never stops is the failure this row exists for: it looks like it is working | | |
| ED-14 | Attaching to a process you started | Run `node --inspect server.js` in a terminal, then start **Attach to Node.js** | The editor attaches and `Debugger attached.` appears in that terminal | | |

## 6. Extensions

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| EX-1 | Search marketplace | Open Extensions, search "python" | Results from Open VSX appear | | |
| EX-2 | Install extension | Install any extension from search | Downloads, installs, shows in sidebar | | |
| EX-3 | Extension webview | Open Claude Code or theme picker | Webview renders, interactive | | |
| EX-4 | Persist across restart | Install extension, kill + relaunch app | Extension still installed and active | | |
| EX-5 | Bundled extensions | Check Extensions sidebar after first run | Process Monitor, SAF bridge, Serve on Network, Welcome, Python, ESLint, Prettier and Tailwind visible; no third-party icon theme is bundled; file icons use the built-in Seti theme, and Minimal and VS Code Modern Icons are the other two built-in icon themes | | |
| EX-6 | Uninstall extension | Uninstall a previously installed extension | Removed cleanly, no errors | | |
| EX-7 | Jupyter notebook | Install **Jupyter** from Open VSX, `python3 -m venv .venv` in the terminal, open a `.ipynb`, **Select Kernel** > **Python Environments...** > that environment, run a cell printing something, then run a cell starting a subprocess (`import subprocess; subprocess.run(['sleep','600'])`) and press Interrupt | `ipykernel` installs into the environment (about half a minute, needs the network), the cell prints, and the interrupt ends both the cell and the `sleep` it started rather than leaving it behind. The kernel talks over the bundled zeromq addon, so a kernel that never becomes ready is that addon, not the extension | | |

## 7. Background / Foreground

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| BG-1 | Short background (30s) | Press Home, wait 30s, return | Editor state preserved, no reload | | |
| BG-2 | Medium background (5min) | Press Home, wait 5min, return | Health check runs, reconnects if needed | | |
| BG-3 | Long background (30min) | Press Home, wait 30min, return | Page reloads, server still running | | |
| BG-4 | Server process killed | `adb shell kill <node PID>` | Server auto-restarts, notification shows | | |
| BG-5 | Foreground notification | Check notification shade while app runs | "VSCodroid running" notification visible | | |
| BG-6 | Return after screen off | Lock screen, wait 2min, unlock | App resumes without crash | | |
| BG-7 | An adopted session keeps its network | `adb shell ps -A \| grep libnode` shows two processes; **`kill -9` the parent** (the lower PID, the one the other lists as its PPID), then relaunch the app. It must be SIGKILL: the bootstrap handles SIGTERM and kills its child on the way out, so a plain `kill` leaves nothing to adopt. `ps` shows `libnode` rather than `server.js` because that is argv[0] | Editor loads against the surviving server, the notification reads "Local development server active" with no warning beside it, and the session reaches the network: the marketplace lists extensions and `npm view express` prints a version | | |
| BG-8 | A server that will not come back says so | With the editor open, `adb shell kill -9` the `libnode` process repeatedly until the notification reads "Server crashed repeatedly" | The page stops reading "Starting server..." and states that the server could not be restarted, that files are safe, and offers **Try again**. Tapping it returns to the loading page and starts a new attempt; nothing requires force-stopping the app | | |
| BG-9 | A bootstrap killed under an open editor does not reload it | With a file edited and a terminal running `sleep 600`, `kill -9` the parent `libnode` as in BG-7 and keep the app in the foreground | Within a few seconds logcat shows `adopting it` and `Adopted the server the page is connected to; not reloading`; the page does not reload, the edit and the terminal are still there | | |

BG-7 proves where the DNS proxy lives. An adopted server outlived the bootstrap
that forked it, and the proxy that lets musl-built programs resolve names is
preloaded into the editor server itself, so it survives with it rather than dying
with the bootstrap.

A failure here is silent by construction: the editor looks entirely healthy and
only outbound requests fail, so nothing on screen says why the marketplace is
empty. Do not try to recover it by relaunching, which adopts the same orphan
again; tap Stop on the notification, which ends the recorded server, and start it
fresh.

## 8. Low Memory & Stress

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| ST-1 | Trim memory signal | `adb shell am send-trim-memory <PID> RUNNING_CRITICAL` | Process monitor kills idle LS, no crash | | |
| ST-2 | Many terminals | Open 10 terminal tabs | Bash spawns for each, process count reported | | |
| ST-3 | OOM recovery | Force WebView OOM (open huge file + extensions) | onRenderProcessGone fires, WebView recreated | | |
| ST-4 | Storage nearly full | Fill device storage to under 100 MB free, as Settings reports it | Warning toast shown, app still functional | | |
| ST-5 | Bug report after a renderer death | After ST-3, or on a debug build after crashing the renderer from DevTools (`Page.crash`), run **VSCodroid: Copy Bug Report** once the editor is back; delete one line, wait for the notification to go, then tap **Copy Bug Report** in the status bar and paste somewhere; close the report | An untitled editor opens with the report. Renderer Deaths has a line for the death, saying crashed or killed by the system, and Recent Exits lists the app's recent process exits or says none are recorded. The paste is the editor's text without the deleted line. The status bar entry goes with the report | | |

## 9. Performance Benchmarks

| ID | Metric | Steps | Target | Actual | Pass/Fail | Notes |
|----|--------|-------|--------|--------|-----------|-------|
| PF-1 | Cold start (first run) | Time from tap to editor visible. Record the number rather than pass/fail: no target has ever been measured, and extraction unpacks about 575 MiB across over 23,000 files one at a time | Progress advances throughout and the editor opens; write the elapsed time in Notes | | | |
| PF-2 | Cold start (subsequent) | Kill app, re-launch, time to editor | <5s | | | |
| PF-3 | Warm start | Home → return to app | <2s | | | |
| PF-4 | Memory (idle) | Open app, check `dumpsys meminfo` | <400MB | | | |
| PF-5 | Memory (active editing) | Edit file + terminal open | <700MB | | | |
| PF-6 | Battery (1hr session) | Use normally for 1hr, check battery usage | <15% | | | |
| PF-7 | npm install (cached) | Run `npm install` on cached project | <5s | | | |
| PF-8 | npm install (fresh) | Run `npm install` on new project | <60s | | | |
| PF-9 | Vite dev server start | In a Vite project, run `node node_modules/vite/bin/vite.js` (`npx vite` exits 126) | <500ms | | | |
| PF-10 | File open (small) | Open a <100 line file | <1s | | | |

## 10. Toolchains (On-Demand)

There is no Settings entry for this screen. Two routes reach it: **VSCodroid:
Manage Toolchains** from the Command Palette, and touch-and-hold on the app icon
followed by **Manage toolchains**. The rows below use the second, because it is
the one that still works when the editor does not. Confirm the first opens the
same screen once on any run.

Run each command in the app's own terminal. `adb shell run-as` will not answer
these: it runs in a different SELinux domain, one that is allowed to execute
files the app itself may not, so it reports success for a binary that fails on
the device.

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| TC-2 | Ruby install | Long-press app icon > Manage toolchains > Install Ruby | Downloads, extracts, `ruby --version` prints a version | | |
| TC-3 | Java install | Long-press app icon > Manage toolchains > Install Java | Downloads, extracts, `java -version` prints a version | | |
| TC-4 | Ruby and Java run | `ruby -e 'puts 1+1'`; write and run a `Hello.java` with `java Hello.java` | Both print their output | | |
| TC-9 | A program, not a person, runs a toolchain command | With Ruby installed, write a two-line `Makefile` whose recipe is `ruby -e 'puts 1+1'` and run `make` in the terminal; then add a `"type": "process"` task whose command is `ruby` with args `-e` and `puts 1+1`, and run it | Both print `2`. `make` uses `/system/bin/sh`, and a process task uses no shell at all, so neither reads any bash startup file; a `Permission denied` or exit 126 means the trampoline is not on PATH | | |
| TC-6 | Toolchain uninstall | Manage toolchains > uninstall one | Files removed, command no longer found in a new terminal | | |
| TC-7 | A sideloaded install pins one release | Install any toolchain on a build that is NOT from Play, with `adb logcat -s VSCodroid.ToolchainManager` running (Logger prefixes every tag, so the bare name matches nothing) | One line reading `Pinned this install to .../releases/download/<tag>`, naming a concrete tag rather than `latest`, and the install completes. A `Falling back to the unpinned release URL` line instead is not a failure, but record it: it means the resolve did not work on this network | | |
| TC-8 | A retired toolchain is reclaimed | On a device with Go installed from an earlier build, update and launch once, then open Manage toolchains | Go is gone from the list, `go` is not found in a new terminal, and its files are off the disk. `adb logcat -s VSCodroid.ToolchainManager` shows `Removing go: this build no longer offers it` | | |

Go was here as TC-1 and TC-5, the second of them recording `go build` as an
expected failure. It is no longer offered. `go` starts its compiler and linker as
separate programs from the app's own storage, and Android refuses to execute
anything stored there, a limit no packaging change reaches and one that
`-toolexec` cannot route around: it governs how `go` runs its tools, and `go`
itself is what fails to start. An install that still carries it is removed on the
first launch of a build that has this line, so the row to run instead is TC-8.

## 11. Terminal & Tools

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| TT-1 | bash interactive | Open terminal, run commands | Prompt works, history, tab completion | | |
| TT-2 | node | `node -e "console.log(1+1)"` | Prints 2 | | |
| TT-3 | git clone | `git clone https://github.com/user/repo` | Clones successfully with SSL | | |
| TT-4 | python3 | `python3 -c "print('hello')"` | Prints hello | | |
| TT-5 | npm init + install | `npm init -y && npm install express` | package.json created, express installed | | |
| TT-6 | SSH key gen | `ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519` in the terminal | Key pair created at `~/.ssh/id_ed25519`. The `-f` is not optional: OpenSSH derives its default key path from the system user database, which an app sandbox does not provide, so the bare command fails with `Saving key "..." failed: No such file or directory`. `AndroidBridge.generateSshKey` passes the same explicit path | | |
| TT-7 | SSH key read | `cat ~/.ssh/id_ed25519.pub` | Public key printed and selectable | | |
| TT-8 | tmux | `tmux new-session -d && tmux ls` | Session listed | | |
| TT-9 | ripgrep | `rg "pattern" .` | Search results shown | | |
| TT-10 | VS Code Search | Use Search sidebar (Ctrl+Shift+F) | Results appear, file navigation works | | |
| TT-11 | Commands outside the terminal | `bash -c 'type -t npm; type -t npx'`, then a `"type": "shell"` task running `npm -v` | Each reports `function`, and the task prints a version rather than "command not found". `sh -c 'npm -v'` and `timeout 20 npx --version` print a version too: outside bash, `npm` and `npx` are a link on PATH to the trampoline, as a toolchain command is (TC-9). "No such file or directory" or `ENOENT` there means the table has no npm row | | |
| TT-12 | Python packages with a compiled part | `pip install numpy pandas psutil`, then `python3 -c "import numpy, pandas, psutil; print(numpy.__version__, pandas.__version__, psutil.__version__)"` | All three install without building anything and the versions print: they come from the prebuilt Android builds pip is pointed at, not from PyPI, which has no Android wheel for any of them. A `Preparing metadata` step that runs a compiler and fails means `~/.pip/pip.conf` did not get the extra index | | |
| TT-13 | A command pip installed runs by name | `pip install cowsay`, press Home and return to the app, then in a terminal `hash -r; cowsay -t hi` | The cow prints. The command is reached through the same launcher as a toolchain's: the table that names it is rebuilt when the editor returns to the foreground, so a `command not found` before switching away is expected and one after it is the failure | | |

## 12. SAF & External Files

> The picker blocker ([#79](https://github.com/rmyndharis/VSCodroid/issues/79)) is
> fixed: the bridge extension declared `main`, so it loaded in the Node extension
> host where its `BroadcastChannel` reached nothing; it declares `browser` now and
> loads where its transport is. **These rows are executable and are the pre-release
> pass for the area that has changed most.**
>
> Run the whole section against **one** device folder that has at least one
> subdirectory and a `.vscode/` directory in it; several rows below depend on
> subdirectory contents, and a flat folder passes them without exercising anything.
>
> A failure here is usually silent by construction: the editor reports success and
> the device copy is what did not change. Verify from the **device** side (a file
> manager, or reopening the folder in another app), never from the editor's own view
> of the mirror.

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| SF-1 | Open external folder | Command palette > VSCodroid: Open Folder from Device | System picker opens; after granting, the folder appears in Explorer with its contents | | |
| SF-2 | Edit sync-back, top level | Edit a file in the folder's root, save | Change is present in the file **on the device**, not only in the editor | | |
| SF-3 | Recent folders | Open a folder, close the app, reopen, run VSCodroid: Open Recent Folder | The folder is listed and reopens; its mirror was not deleted by the listing itself | | |
| SF-4 | Save inside a subfolder | Edit and save `<sub>/<file>`, two levels down if the folder allows | Change reaches the device. Watches are registered per directory, so a subfolder save is a different code path from SF-2 | | |
| SF-5 | Dotfiles both ways | Edit `.vscode/settings.json` (or `.gitignore`) and save | Change reaches the device. The write-back filter used to drop anything beginning with a dot while the walk copied it in, so this appeared to work and changed nothing | | |
| SF-6 | Rename a directory | Rename a subdirectory that has files in it, from the editor | Directory appears on the device under the new name **with its contents**, and not under the old one. Renames arrive as an unpaired delete-then-create; the sync holds the delete for a second and pairs the two into one rename of the device's own document, so the old name left beside the new one, or a new directory missing what the old one held, means the rename did not reach the device as one | | |
| SF-7 | Device-side deletion sticks | Delete a file from the folder using a device file manager, then reopen the folder in the editor | The file stays deleted; it is not restored from the stale mirror | | |
| SF-8 | Revoked permission reclaimed | Revoke the folder's access in Android Settings > Apps > VSCodroid, relaunch | The mirror is reclaimed and the device copy is untouched. A mirror holding anything the device may lack is kept instead: a write that never reached the device, or a file the last sync did not record, such as a `.git` cloned in the terminal. A folder still granted must **not** be emptied while open | | |
| SF-9 | A save that did not reach the device | Create a file in the folder, force-stop the app immediately, relaunch and reopen the same folder | The file is present in the device folder, checked from a device file manager. Before, a write the sync never delivered stayed inside VSCodroid until the app was uninstalled, with nothing saying so | | |
| SF-10 | Conflicting edits | Edit a file in the editor, force-stop the app before the save reaches the device, edit the same file with another app, reopen the folder | The device's version is shown and the editor's version is beside it as `<name>.local-<number>`; neither is lost. Both appear in the device folder as well as in the editor | | |
| SF-11 | Conflicting edits, the other way round | With the folder closed, edit a file with another app; then open the folder in the editor, edit the same file there, force-stop the app before the save reaches the device, and reopen the folder | The editor's version wins on the device and the other app's version is beside it as `<name>.device-<time>`; neither is lost. An ordinary save with no device edit leaves no such copy | | |
| SF-12 | A device folder holding one workspace file | Grant a folder whose top level holds exactly one `.code-workspace`; then relaunch the app | It opens as that workspace rather than as the folder, and the same workspace comes back after the relaunch | | |
| SF-13 | A folder named like a workspace | Grant a folder whose own name ends in `.code-workspace` | It opens as a folder, not as an unreadable workspace with an empty window | | |
| SF-14 | A folder you closed stays closed | Open a folder, choose **File > Close Folder** (in the Command Palette, **Workspaces: Close Workspace**), force-stop the app, relaunch through the launcher | The empty window comes back, not the folder that was closed. Opening a folder again and relaunching must still reopen it | | |
| SF-15 | A second window is this window | Run **New Window** from the Command Palette, then **Open Folder in New Window** | The editor reuses its own window. The device browser must not come to the front, and no popup-blocked message appears over the editor | | |
| SF-16 | An external link still leaves the app | With a dev server running on another port, follow a link to it from the editor | The device browser opens it. This is the branch the window reuse above must not swallow | | |
| SF-17 | Conflicting edits while open | Open a folder; save a file once in the editor; change the same file with another app; edit and save it again in the editor | The first save adds no copy. After the second, the editor's version is on the device and the other app's version is beside it as `<name>.device-<time>`, in the Explorer and in the device folder. A further save with no change from the other app adds no second copy | | |
| SF-18 | Saves to a folder that settles them late | Open a folder on a phone or camera attached over USB (MTP), or a Nextcloud folder; save one file three times in the editor, with no other app involved; then change it with another app on this phone, working through the same folder, so that its length changes, and save it once more | Each of the first three saves reaches the device, and none adds a `<name>.device-<time>` copy of an earlier save or of the file as it was opened. After the last save the other app's version is beside the file as `<name>.device-<time>`. These providers report a save's final time and size only after the save has ended, which must not read as another app's edit. An Android phone attached over MTP keeps a file's old modification time, so there only a change of length shows the other app's edit. An edit made on the attached device itself is not seen until the folder is listed again | | |
| SF-19 | Reopening after a low-storage open | Open a folder and save one of its files in the editor; close the app, change a different file of the folder with another app, and fill the phone's storage until less than 150 MB is free; open the folder and save the first file again; then free the space and open the folder once more | The low-storage open says one file could not be copied, and the second save of the first file reaches the device. The last open shows the other app's version, and no `<name>.local-<number>` file appears in the Explorer or in the device folder | | |
| SF-20 | A phone folder opened by path | Save a file into `Documents/<folder>` with another app. In the editor, run **File > Open Folder**, type `/sdcard/Download` and open it, and cancel the dialog. Open `/storage/emulated/0/Documents/<folder>` the same way; tap **Open Folder from Device** in the dialog, then cancel the picker. Run **Developer: Reload Window**, then open the same folder as `/sdcard/documents/<folder>/`. Force-stop the app and relaunch it, and tap **Don't Show Again**; then force-stop and relaunch once more | The folder shows its subfolders and not the file. Each folder raises one dialog naming it, and for `/sdcard/Download` it says to pick a folder inside it; its button opens Android's folder picker. The reload and the second spelling raise none, the first relaunch raises the folder's dialog once more, and after **Don't Show Again** the second relaunch raises none. A device folder's copy never raises one | | |
| SF-21 | The device route is in view | Tap the remote indicator at the left end of the status bar; then choose **File > Close Folder** (in the Command Palette, **Workspaces: Close Workspace**) and look at the Explorer | The menu lists **VSCodroid: Open Folder from Device**, and the empty Explorer shows a button for it below the text about folders in the device's storage; both open Android's folder picker | | |
| SF-22 | Folders the picker treats differently | With **File > Open Folder**, open `/sdcard/Android/data`, then a folder under `/sdcard/Android/obb` if one exists. With an SD card or a USB drive attached, open its top, `/storage/<id>`, tap **Open Folder from Device** in the dialog and try to use that top folder in the picker | For `Android/data` and the `Android/obb` folder the dialog says Android keeps those folders to the app they belong to and that Open Folder from Device cannot open them, and offers only **Don't Show Again**. For the volume's top it names the SD card or USB drive and says a USB drive can be opened as it is, while on an SD card a folder inside has to be picked; the picker grants a USB drive's top and refuses an SD card's | | |
| SF-23 | A device folder shows its name | Open a folder with **Open Folder from Device**, then reopen it from **VSCodroid: Open Recent Folder**; force-stop the app and relaunch it; open a terminal, run `pwd` and `git status` in a repository folder, and save a file | Each time, the Explorer root and the title bar show the folder's name, not a twelve-character code. The terminal opens in the folder with `[saf]` as its prompt, and git sees the repository. The save reaches the device | | |
| SF-24 | A folder opened before names | Install the previous release, open a device folder, change a file without saving it and open a terminal. Install this build over it without clearing data and relaunch; choose **Reopen** in the notice, then save the file, close the terminal, relaunch and choose **Reopen** again | The folder reopens under its code with the unsaved change, and the notice offers its name. The first **Reopen** says to save and close the terminal first and moves nothing. The second reopens the folder under its name with the same files open. **File > Open Recent** lists both, and a further relaunch opens the folder by name with no notice | | |

---

## 13. Display Language

| ID | Scenario | Steps | Expected Result | Pass/Fail | Notes |
|----|----------|-------|-----------------|-----------|-------|
| DL-1 | Editor follows the phone | Set the phone to one of the thirteen languages, start the app | Menus, the Command Palette and settings descriptions are in that language, VSCodroid's own commands included | | |
| DL-2 | App screens follow it too | Same run, watch setup and the toolchain picker | Progress steps, the picker and its buttons are in that language | | |
| DL-3 | An unsupported language | Set the phone to one with no bundle, for example Vietnamese | Interface is English throughout, nothing half translated and no error | | |
| DL-4 | Per-app language | Android 13+, Settings, Apps, VSCodroid, Language, pick one | Both the app screens and the editor come back in it | | |
| DL-5 | The walkthrough and the VSCodroid commands | Same run, open Get Started, then the Command Palette and type `VSCodroid` | Walkthrough heading, subtitle and all four step titles in that language with the buttons still present; the VSCodroid commands show translated labels with the English original beside each, and typing the English name still finds them | | |
| DL-6 | First launch after an upgrade | Install the previous release, set the app to that language, then `adb install -r` the new build without clearing data and relaunch through SplashActivity | Translated on the FIRST launch. English on the first and translated on the second means the extension scan cache was not invalidated | | |

## Summary

| Category | Total | Pass | Fail | Skip |
|----------|-------|------|------|------|
| Device Matrix | 4 | | | |
| Android Versions | 4 | | | |
| Keyboard Input | 37 | | | |
| Screen & Orientation | 11 | | | |
| Editor Operations | 14 | | | |
| Extensions | 7 | | | |
| Background/Foreground | 9 | | | |
| Low Memory & Stress | 5 | | | |
| Performance | 10 | | | |
| Toolchains | 7 | | | |
| Terminal & Tools | 13 | | | |
| SAF & Files | 24 | | | |
| Display Language | 6 | | | |
| **Total** | **151** | | | |

**Overall Result**: [ ] PASS / [ ] FAIL

**Blockers / Critical Issues**:

**Notes**:
