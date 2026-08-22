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
 * On a phone that is roughly 45 percent of the width, spent on a chat view whose
 * provider this build prunes, while the walkthrough beside it wraps to one word
 * per line.
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
            vscode.commands.executeCommand(
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

    // Auto-open walkthrough on first activation only
    if (!fs.existsSync(markerFile)) {
        try { fs.writeFileSync(markerFile, '1'); } catch (_) {}
        // onStartupFinished already means the workbench is ready,
        // but a short delay avoids racing with layout restoration.
        setTimeout(async () => {
            await vscode.commands.executeCommand(
                'workbench.action.openWalkthrough',
                WALKTHROUGH_ID,
                false
            );
            // Close sidebar AFTER walkthrough is shown: on mobile the
            // Explorer panel eats ~40% of the screen. Closing after ensures
            // the walkthrough command doesn't re-trigger the sidebar.
            vscode.commands.executeCommand('workbench.action.closeSidebar');
        }, 500);
    }
}

function deactivate() {}

module.exports = { activate, deactivate };
