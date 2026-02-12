// @ts-nocheck
'use strict';

const vscode = require('vscode');
const fs = require('fs');
const path = require('path');

const POLL_INTERVAL_MS = 10_000;

let statusBarItem;
let outputChannel;
let pollTimer;
let lastSnapshot = null;
let warningShownAtThreshold = false;

function activate(context) {
    const tmpDir = process.env.TMPDIR || '/tmp';
    const snapshotPath = path.join(tmpDir, 'vscodroid-processes.json');

    // Status bar item — right side, low priority (far right)
    statusBarItem = vscode.window.createStatusBarItem(
        vscode.StatusBarAlignment.Right, -100
    );
    statusBarItem.command = 'vscodroid.showProcesses';
    statusBarItem.name = 'Process Monitor';
    statusBarItem.text = '$(pulse) --';
    statusBarItem.tooltip = 'VSCodroid Process Monitor: loading...';
    statusBarItem.show();

    outputChannel = vscode.window.createOutputChannel('VSCodroid Processes');

    // Register command
    const showCmd = vscode.commands.registerCommand('vscodroid.showProcesses', () => {
        showProcessTree();
    });

    // Poll the JSON snapshot file
    function poll() {
        try {
            const raw = fs.readFileSync(snapshotPath, 'utf8');
            const snapshot = JSON.parse(raw);
            lastSnapshot = snapshot;
            updateStatusBar(snapshot);
        } catch {
            // File not yet written or parse error — keep last state
        }
    }

    poll();
    pollTimer = setInterval(poll, POLL_INTERVAL_MS);

    context.subscriptions.push(statusBarItem, outputChannel, showCmd, {
        dispose: () => { clearInterval(pollTimer); }
    });
}

function updateStatusBar(snapshot) {
    const total = snapshot.total || 0;

    statusBarItem.text = `$(pulse) ${total}`;

    // Color thresholds
    if (total > 10) {
        statusBarItem.backgroundColor = new vscode.ThemeColor(
            'statusBarItem.errorBackground'
        );
    } else if (total >= 6) {
        statusBarItem.backgroundColor = new vscode.ThemeColor(
            'statusBarItem.warningBackground'
        );
    } else {
        statusBarItem.backgroundColor = undefined;
    }

    // Tooltip with breakdown
    const counts = {};
    for (const proc of snapshot.tree || []) {
        const label = typeLabel(proc.type);
        counts[label] = (counts[label] || 0) + 1;
    }
    const parts = Object.entries(counts).map(([k, v]) => `${v} ${k}`);
    statusBarItem.tooltip = `Phantom processes: ${total}\n${parts.join(', ')}`;

    // Warning notification at threshold crossing
    if (total >= 10 && !warningShownAtThreshold) {
        warningShownAtThreshold = true;
        vscode.window.showWarningMessage(
            `Running ${total} phantom processes. Consider closing unused terminals or restarting language servers.`,
            'Show Details'
        ).then(choice => {
            if (choice === 'Show Details') {
                showProcessTree();
            }
        });
    } else if (total < 8) {
        // Reset so warning can fire again if count goes back up
        warningShownAtThreshold = false;
    }
}

function typeLabel(type) {
    const labels = {
        server: 'system',
        fileWatcher: 'system',
        safSync: 'storage',
        ptyHost: 'system',
        system: 'system',
        tmux: 'terminal',
        terminal: 'terminal',
        langserver: 'language server',
        unknown: 'other'
    };
    return labels[type] || type;
}

function showProcessTree() {
    outputChannel.clear();
    outputChannel.show(true);

    if (!lastSnapshot) {
        outputChannel.appendLine('No process data available yet.');
        return;
    }

    const s = lastSnapshot;
    const time = new Date(s.timestamp).toLocaleTimeString();

    outputChannel.appendLine(`VSCodroid Process Tree (${time})`);
    outputChannel.appendLine(`Total phantom processes: ${s.total}`);
    if (s.budget) {
        outputChannel.appendLine(
            `Budget: ${s.budget.current}/${s.budget.soft} soft, ${s.budget.hard} hard limit`
        );
    }
    outputChannel.appendLine('');
    outputChannel.appendLine('PID      PPID     TYPE            COMMAND');
    outputChannel.appendLine('───────  ───────  ──────────────  ────────────────────────');

    for (const proc of s.tree || []) {
        const pid = String(proc.pid).padEnd(7);
        const ppid = String(proc.ppid).padEnd(7);
        const type = (proc.type || 'unknown').padEnd(14);
        outputChannel.appendLine(`${pid}  ${ppid}  ${type}  ${proc.cmd || ''}`);
    }

    if (s.warnings && s.warnings.length > 0) {
        outputChannel.appendLine('');
        outputChannel.appendLine('Warnings:');
        for (const w of s.warnings) {
            outputChannel.appendLine(`  ⚠ ${w}`);
        }
    }
}

function deactivate() {
    if (pollTimer) clearInterval(pollTimer);
}

module.exports = { activate, deactivate };
