// @ts-nocheck
'use strict';

const vscode = require('vscode');
const fs = require('fs');
const path = require('path');

const WALKTHROUGH_ID = 'vscodroid.vscodroid-welcome#vscodroid.welcome';

function activate(context) {
    // File-based marker — survives force-stop (globalState does not,
    // because SIGKILL prevents VS Code from flushing its state DB)
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

    // Auto-open walkthrough on first activation only
    if (!fs.existsSync(markerFile)) {
        try { fs.writeFileSync(markerFile, '1'); } catch (_) {}
        // Small delay to let the workbench finish initializing
        setTimeout(() => {
            // Close sidebar first — on mobile the Explorer panel eats ~40%
            // of the screen, leaving the walkthrough content cramped.
            // This is transient (not a setting change), user can reopen anytime.
            vscode.commands.executeCommand('workbench.action.closeSidebar');
            vscode.commands.executeCommand(
                'workbench.action.openWalkthrough',
                WALKTHROUGH_ID,
                false
            );
        }, 1000);
    }
}

function deactivate() {}

module.exports = { activate, deactivate };
