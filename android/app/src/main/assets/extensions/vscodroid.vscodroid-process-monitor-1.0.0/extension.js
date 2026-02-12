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
let criticalShownAtThreshold = false;

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

    // Register commands
    const showCmd = vscode.commands.registerCommand('vscodroid.showProcesses', () => {
        showProcessTree();
    });

    const killIdleCmd = vscode.commands.registerCommand('vscodroid.killIdleServers', () => {
        killIdleLanguageServers();
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

    context.subscriptions.push(statusBarItem, outputChannel, showCmd, killIdleCmd, {
        dispose: () => { clearInterval(pollTimer); }
    });
}

function updateStatusBar(snapshot) {
    const total = snapshot.total || 0;
    const tree = snapshot.tree || [];

    statusBarItem.text = `$(pulse) ${total}`;

    // Color thresholds (soft budget = 5, system hard limit = 32)
    if (total >= 10) {
        statusBarItem.backgroundColor = new vscode.ThemeColor(
            'statusBarItem.errorBackground'
        );
    } else if (total >= 5) {
        statusBarItem.backgroundColor = new vscode.ThemeColor(
            'statusBarItem.warningBackground'
        );
    } else {
        statusBarItem.backgroundColor = undefined;
    }

    // Count by type
    const counts = {};
    let langserverCount = 0;
    let terminalCount = 0;
    for (const proc of tree) {
        const label = typeLabel(proc.type);
        counts[label] = (counts[label] || 0) + 1;
        if (proc.type === 'langserver') langserverCount++;
        if (proc.type === 'terminal' || proc.type === 'tmux') terminalCount++;
    }
    const parts = Object.entries(counts).map(([k, v]) => `${v} ${k}`);

    // Storage info in tooltip
    let storageInfo = '';
    try {
        const homeDir = process.env.HOME || '';
        if (homeDir) {
            const stats = fs.statfsSync(homeDir);
            const availableMB = Math.round((stats.bavail * stats.bsize) / (1024 * 1024));
            const totalMB = Math.round((stats.blocks * stats.bsize) / (1024 * 1024));
            const usedPercent = Math.round(((totalMB - availableMB) / totalMB) * 100);
            storageInfo = `\nStorage: ${availableMB} MB free (${usedPercent}% used)`;
            if (availableMB < 200) {
                storageInfo += ' ⚠ LOW';
            }
        }
    } catch { /* ignore */ }

    statusBarItem.tooltip = `Phantom processes: ${total}\n${parts.join(', ')}${storageInfo}`;

    // Tiered warnings
    if (total >= 12 && !criticalShownAtThreshold) {
        criticalShownAtThreshold = true;
        const msg = `Critical: ${total} phantom processes! Android may kill them.`;
        const suggestions = [];
        if (terminalCount > 2) suggestions.push(`Close ${terminalCount - 1} of ${terminalCount} terminals`);
        if (langserverCount > 1) suggestions.push(`${langserverCount} language servers active`);

        vscode.window.showErrorMessage(
            suggestions.length > 0 ? `${msg} ${suggestions.join('. ')}.` : msg,
            'Kill Idle Servers',
            'Show Details'
        ).then(choice => {
            if (choice === 'Kill Idle Servers') killIdleLanguageServers();
            else if (choice === 'Show Details') showProcessTree();
        });
    } else if (total >= 8 && !warningShownAtThreshold) {
        warningShownAtThreshold = true;
        const suggestions = [];
        if (terminalCount > 2) suggestions.push(`${terminalCount} terminals open`);
        if (langserverCount > 1) suggestions.push(`${langserverCount} language servers`);
        const hint = suggestions.length > 0 ? ` (${suggestions.join(', ')})` : '';

        vscode.window.showWarningMessage(
            `Running ${total} phantom processes${hint}. Target: ≤5.`,
            'Kill Idle Servers',
            'Show Details'
        ).then(choice => {
            if (choice === 'Kill Idle Servers') killIdleLanguageServers();
            else if (choice === 'Show Details') showProcessTree();
        });
    } else if (total < 5) {
        warningShownAtThreshold = false;
        criticalShownAtThreshold = false;
    }
}

function killIdleLanguageServers() {
    if (!lastSnapshot) {
        vscode.window.showInformationMessage('No process data available.');
        return;
    }

    const langservers = (lastSnapshot.tree || []).filter(p => p.type === 'langserver');
    if (langservers.length === 0) {
        vscode.window.showInformationMessage('No language servers running.');
        return;
    }

    let killed = 0;
    for (const proc of langservers) {
        try {
            process.kill(proc.pid, 'SIGTERM');
            killed++;
        } catch {
            // Process may have already exited
        }
    }

    vscode.window.showInformationMessage(
        `Sent SIGTERM to ${killed} language server${killed !== 1 ? 's' : ''}. They will restart on demand.`
    );
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

    // Storage info
    try {
        const homeDir = process.env.HOME || '';
        if (homeDir) {
            const stats = fs.statfsSync(homeDir);
            const availableMB = Math.round((stats.bavail * stats.bsize) / (1024 * 1024));
            outputChannel.appendLine(`Storage available: ${availableMB} MB`);
        }
    } catch { /* ignore */ }

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

    // Recommendations
    const tree = s.tree || [];
    const langservers = tree.filter(p => p.type === 'langserver');
    const terminals = tree.filter(p => p.type === 'terminal' || p.type === 'tmux');
    if (s.total >= 5) {
        outputChannel.appendLine('');
        outputChannel.appendLine('Recommendations:');
        if (terminals.length > 2) {
            outputChannel.appendLine(`  • Close ${terminals.length - 1} terminals (${terminals.length} open, 1-2 recommended)`);
        }
        if (langservers.length > 1) {
            outputChannel.appendLine(`  • ${langservers.length} language servers active — idle ones auto-kill after 5 min under memory pressure`);
            outputChannel.appendLine(`  • Run "VSCodroid: Kill Idle Servers" to free them now`);
        }
    }
}

function deactivate() {
    if (pollTimer) clearInterval(pollTimer);
}

module.exports = { activate, deactivate };
