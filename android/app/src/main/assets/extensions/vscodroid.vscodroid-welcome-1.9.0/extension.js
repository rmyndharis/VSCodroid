// @ts-nocheck
'use strict';

const vscode = require('vscode');
const fs = require('fs');
const path = require('path');

const WALKTHROUGH_ID = 'vscodroid.vscodroid-welcome#vscodroid.welcome';

// Set on a workspace once its secondary side bar has been put where this
// device's settings ask for it.
//
// Workspace-scoped rather than global because the layout record it corrects is
// workspace-scoped too, and it lives in the same browser storage as that record:
// clear one and the other goes with it, so a profile that loses its stored
// layout is realigned rather than left open with a marker saying it was handled.
const SIDE_BAR_ALIGNED = 'vscodroid.secondarySideBar.aligned';

/**
 * Puts the secondary side bar where this device's settings ask for it, once per
 * workspace.
 *
 * `workbench.secondarySideBar.defaultVisibility` cannot do this on its own here,
 * however plainly its name reads. The setting decides a workspace that has no
 * recorded layout, and by the time it is readable the record exists. The
 * workbench reaches this device's settings over the remote connection and does
 * not wait for them: it starts from a copy in browser storage, which the first
 * load in a profile has not written yet, so that load falls back to upstream's
 * `visibleInWorkspace`, opens the bar, and
 * stores `workbench.auxiliaryBar.hidden: false` against the workspace. Every
 * later load reads the stored value and never consults the default again, so the
 * bar stays open for the life of the workspace however the setting reads.
 *
 * On a phone that is roughly 45 percent of the width, spent on a chat view,
 * while the walkthrough beside it wraps to one word per line.
 *
 * Runs at most once per workspace, so the bar stays where the user leaves it:
 * opening it is a choice, and nothing here runs again to undo it. `hidden` is
 * the only value acted on; any other means the user asked for the bar and gets
 * it. The marker is written only after the command has run, so a launch that
 * fails to close the bar tries again on the next one instead of recording a job
 * it did not do.
 */
function alignSecondarySideBar(context) {
    if (context.workspaceState.get(SIDE_BAR_ALIGNED)) {
        return;
    }
    const visibility = vscode.workspace
        .getConfiguration()
        .get('workbench.secondarySideBar.defaultVisibility');
    if (visibility !== 'hidden') {
        return;
    }
    // closeAuxiliaryBar hides the part outright rather than toggling it, so a
    // workspace that already agrees costs nothing and still gets marked.
    Promise.resolve(
        vscode.commands.executeCommand('workbench.action.closeAuxiliaryBar')
    ).then(
        () => context.workspaceState.update(SIDE_BAR_ALIGNED, true),
        () => {}
    );
}

/**
 * Closes the side bar when a file or a terminal is opened on a phone, in either
 * orientation.
 *
 * A phone in portrait is about 411dp wide. The activity bar takes 36 of that and
 * the side bar will not go below 170 however far its divider is dragged, so
 * opening a file from the Explorer leaves the file itself under half the screen,
 * wrapping at a few words a line. Closing the tree once the user has chosen from
 * it is what a phone should do, and it is reversible with one tap on the same
 * icon they opened it with.
 *
 * Gated on a setting rather than measured here, because this side cannot measure
 * it: nothing in the extension API reports the window's width. The app writes
 * `vscodroid.layout.compactScreen` (FirstRunSetup, true below the 600dp smallest
 * width Android itself calls a phone) into the settings file it owns, and writes
 * `vscodroid.layout.autoHideSideBar` nowhere at all: that key is the user's, it
 * lives in their own settings file, and `auto` there means follow the screen.
 * Smallest width does not change with rotation, so a phone turned to landscape
 * still closes the bar although there is room for it there.
 *
 * Three string values rather than the nullable boolean this began as, and the
 * shape is not cosmetic. The Settings editor resolves a two-member type array to
 * `nullable-integer`, `nullable-number` or `complex`, in that order, so
 * `["boolean", "null"]` lands on `complex` and the row draws an "Edit in
 * settings.json" link where the control should be: the setting could not be
 * changed in the one place its own description tells the user to change it. A
 * plain boolean draws a checkbox and loses the third answer, because an unset
 * checkbox and a false one look the same, and unset is the one that follows the
 * screen.
 *
 * Read on every event and not once at activation, so turning it off takes effect
 * on the next file rather than on the next launch.
 *
 * `onDidChangeActiveTextEditor` and not an Explorer selection event: the editor
 * becoming active is the moment the side bar has done its job, and it covers
 * every route to a file, the Quick Open list and a link in a hover included. It
 * also fires on a plain tab switch, where the bar is already closed and
 * `closeSidebar` costs nothing.
 *
 * A terminal is worse off than a file, because the panel will not go below 300.
 * With the activity bar and the side bar beside it that is 506 on a 411 screen,
 * and the 95 that run off the right edge hold the terminal's tab list, so a
 * tab's menu and its rename cannot be reached, and most of the panel's buttons.
 * `onDidChangeActiveTerminal` and not `onDidOpenTerminal`: every route that puts
 * a new terminal on screen also makes it the active one (Create New Terminal,
 * `Terminal.show()`, a task that reveals, a debug session's terminal, a terminal
 * in the editor area), and the same event covers switching to one that already
 * exists. The open event also arrives for a terminal nothing shows, such as a
 * task set to reveal never or silent, or an extension's `createTerminal()`
 * without `show()`, and closing the bar for one of those took the view away, and
 * the keyboard focus with it, with nothing in its place. A terminal created with
 * `hideFromUser` cannot become the active one without being shown.
 *
 * `closeSidebar` closes whichever view is showing, not the Explorer alone, and
 * that is the intent: a phone that has just put a file on screen has no room
 * for Search or Source Control beside it either, and the same icon the user
 * opened brings it back.
 *
 * Cases it deliberately does not cover. Tapping a file that is already the
 * active editor changes no editor, so no event arrives and the side bar stays.
 * Nothing else changed on screen either, so the tap looks like what it was.
 * Showing the panel again on the terminal that was already active is the same:
 * the extension host reports a change of active terminal only when it is a
 * different one. And opening the side bar while the panel is showing overflows
 * as before, because that is the user asking for the bar, and taking it away
 * again would leave no way to open it.
 *
 * Cases where it closes the bar without need, accepted because nothing in the
 * extension API tells them apart. A terminal added to an empty panel becomes the
 * active one even when nothing shows it, so a task that never reveals, run with
 * no terminal open, still closes the bar. When the active terminal closes and
 * another is left in the panel or the editor area, that one becomes active and
 * the bar closes with nothing new on screen. Terminals still running when the
 * window loads again, as after Reload Window, reconnect after this extension
 * has started and one becomes active, so a side bar left open closes a few
 * seconds after the load. And a Terminal view moved into the primary side bar
 * is closed with it when a terminal is created or switched there, since
 * nothing reports where a view lives: `off` keeps it, and so does the
 * secondary side bar, which `closeSidebar` does not touch.
 */
function autoHideSideBar(context) {
    const closeSideBar = () => {
        // Two keys, and the split is the point. The app owns
        // `vscodroid.layout.compactScreen`, which is a fact about the device it
        // cannot express as a static default; the user owns
        // `vscodroid.layout.autoHideSideBar`, and `auto` means "follow the
        // screen". They were one key, written by the app into the settings file
        // the workbench merges ON TOP of the user's own, so changing it in
        // Settings did nothing at all.
        //
        // Anything that is not an explicit answer follows the screen: `auto`, a
        // value missing because the contributed default has not been read yet,
        // and the boolean an older build of this app wrote all mean the same
        // thing here, and that boolean carried the device's own answer anyway.
        const config = vscode.workspace.getConfiguration();
        const chosen = config.get('vscodroid.layout.autoHideSideBar');
        const enabled = chosen === 'on' || chosen === 'off'
            ? chosen === 'on'
            : config.get('vscodroid.layout.compactScreen') === true;
        if (!enabled) {
            return;
        }
        // Fire and forget with its own handler, the shape every other
        // executeCommand in this file uses and for the same reason: nobody asked
        // for this one, so a rejection is not theirs to see.
        Promise.resolve(
            vscode.commands.executeCommand('workbench.action.closeSidebar')
        ).catch(() => {});
    };
    context.subscriptions.push(
        vscode.window.onDidChangeActiveTextEditor((editor) => {
            if (editor) {
                closeSideBar();
            }
        }),
        vscode.window.onDidChangeActiveTerminal((terminal) => {
            // Undefined when the last terminal closes, which frees room.
            if (terminal) {
                closeSideBar();
            }
        })
    );
}

function activate(context) {
    // File-based marker, kept because it outlives the workbench profile: a
    // cleared WebView data directory takes extension state with it, and the
    // walkthrough should not reappear for that.
    //
    // The reason recorded here used to be that extension state does not survive
    // a force-stop at all, because SIGKILL stops VS Code flushing its state DB.
    // Measured on API 33: a counter in globalState and one in workspaceState both
    // came back incremented across five force-stop and relaunch cycles, so that
    // reason was wrong and [alignSecondarySideBar] relies on the opposite.
    const markerFile = path.join(
        process.env.HOME || process.env.USERPROFILE || '/tmp',
        '.vscodroid_welcome_shown'
    );

    // Command to open the walkthrough manually (from Command Palette)
    context.subscriptions.push(
        vscode.commands.registerCommand('vscodroid.welcome.open', () => {
            // Returned rather than dropped. The other three executeCommand calls
            // in this file are fire-and-forget and each carries its own rejection
            // handler, because nobody asked for them; this one is a palette entry
            // the user chose, so the failure belongs to whoever ran it. Handing
            // the thenable back makes the workbench the handler: it reports a
            // contributed command that failed, where a swallowed rejection left
            // the user tapping a menu item that did nothing at all.
            return vscode.commands.executeCommand(
                'workbench.action.openWalkthrough',
                WALKTHROUGH_ID,
                false
            );
        })
    );

    // No-op command used as completion event for the tools step
    context.subscriptions.push(
        vscode.commands.registerCommand('vscodroid.welcome.toolsDismissed', () => {
            // Completion event fires automatically when command runs
        })
    );

    // Deliberately outside the marker below. That marker records that the
    // walkthrough has been shown, and every device upgrading into this release
    // already has it, which is exactly the population whose stored layout needs
    // correcting.
    alignSecondarySideBar(context);

    // Outside the marker below for the same reason: every device upgrading into
    // this release already has it, and they are exactly the population whose
    // side bar has been eating the screen.
    autoHideSideBar(context);

    // Auto-open walkthrough on first activation only
    if (!fs.existsSync(markerFile)) {
        // onStartupFinished already means the workbench is ready,
        // but a short delay avoids racing with layout restoration.
        setTimeout(() => {
            // The marker records that the walkthrough has been SHOWN, so it is
            // written in the command's success continuation and nowhere else --
            // the same shape, and for the same reason, as alignSecondarySideBar
            // above. Written first, a command that rejected or an extension host
            // torn down inside this delay left the marker behind and the
            // walkthrough never opened again for that installation, reachable
            // only from the palette, and the rejection surfaced as an unhandled
            // one in the host rather than as anything actionable.
            Promise.resolve(
                vscode.commands.executeCommand(
                    'workbench.action.openWalkthrough',
                    WALKTHROUGH_ID,
                    false
                )
            ).then(
                () => {
                    try { fs.writeFileSync(markerFile, '1'); } catch (_) {}
                    // Close sidebar AFTER walkthrough is shown: on mobile the
                    // Explorer panel eats ~40% of the screen. Closing after
                    // ensures the walkthrough command doesn't re-trigger the
                    // sidebar.
                    //
                    // Its own handler, and not covered by the one on the call
                    // above: this runs in that call's success continuation, so a
                    // rejection here starts a fresh chain that nothing is
                    // listening to. Nothing is retried, because the marker is
                    // already written and the walkthrough is already open; the
                    // cost of the close failing is a sidebar the user can close
                    // themselves, not an unhandled rejection in the host.
                    Promise.resolve(
                        vscode.commands.executeCommand('workbench.action.closeSidebar')
                    ).catch(() => {});
                },
                () => {}
            );
        }, 500);
    }
}

function deactivate() {}

module.exports = { activate, deactivate };
