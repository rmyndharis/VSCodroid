// @ts-check

/**
 * VSCodroid SAF Bridge Extension (Browser)
 *
 * Runs in the Web Extension Host (Web Worker) and communicates with the
 * AndroidBridge in the main WebView page via BroadcastChannel relay.
 *
 * The relay script is injected by MainActivity.injectBridgeRelay() into the
 * main page, where it listens on BroadcastChannel 'vscodroid-bridge' and
 * forwards commands to the AndroidBridge JavascriptInterface.
 *
 * Commands:
 * - vscodroid.openFolderFromDevice : Opens SAF folder picker
 * - vscodroid.openRecentFolder     : Shows Quick Pick of recently opened folders
 * - vscodroid.openInBrowser        : Opens a URL in the device browser
 * - vscodroid.generateSshKey       : Creates ~/.ssh/id_ed25519
 * - vscodroid.copySshPublicKey     : Copies the public key to the clipboard
 * - vscodroid.showStorageUsage     : Per-component storage breakdown
 * - vscodroid.clearCaches          : Deletes cached data, reports bytes freed
 * - vscodroid.about                : Opens the Android About dialog
 */

const vscode = require('vscode');

/** @type {BroadcastChannel | undefined} */
let _channel;

/** @type {Record<string, {resolve: Function, reject: Function}>} */
const _pending = {};

/**
 * Returns the shared BroadcastChannel, creating it on first call.
 * Installs a message handler to route responses to pending promises.
 */
function getChannel() {
    if (_channel) return _channel;
    _channel = new BroadcastChannel('vscodroid-bridge');
    _channel.onmessage = (e) => {
        const { id, ok, data, error } = e.data || {};
        const cb = _pending[id];
        if (!cb) return;
        delete _pending[id];
        if (ok) cb.resolve(data);
        else cb.reject(new Error(error || 'Bridge error'));
    };
    return _channel;
}

/**
 * Sends a command to the main page relay and returns a promise for the response.
 * @param {string} cmd
 * @param {Record<string, *>} [extra]
 * @returns {Promise<*>}
 */
function sendBridgeCommand(cmd, extra = {}) {
    return new Promise((resolve, reject) => {
        const id = Math.random().toString(36).slice(2);
        _pending[id] = { resolve, reject };

        try {
            getChannel().postMessage({ cmd, id, ...extra });
        } catch (/** @type {*} */ err) {
            delete _pending[id];
            reject(err);
            return;
        }

        // Timeout after 5 seconds (relay should respond almost instantly)
        setTimeout(() => {
            if (_pending[id]) {
                delete _pending[id];
                reject(new Error('Bridge timeout — is the app running on Android?'));
            }
        }, 5000);
    });
}

/**
 * @param {vscode.ExtensionContext} context
 */
function activate(context) {

    // -- Open Folder from Device --

    const openFolderCmd = vscode.commands.registerCommand(
        'vscodroid.openFolderFromDevice',
        async () => {
            try {
                await sendBridgeCommand('openFolderPicker');
            } catch (/** @type {*} */ err) {
                vscode.window.showWarningMessage(
                    `Failed to open folder picker: ${err.message}`
                );
            }
        }
    );

    // -- Open Recent Folder --

    const recentFolderCmd = vscode.commands.registerCommand(
        'vscodroid.openRecentFolder',
        async () => {
            try {
                const json = /** @type {string} */ (
                    await sendBridgeCommand('getRecentFolders')
                );
                const folders = JSON.parse(json || '[]');

                if (folders.length === 0) {
                    const action = await vscode.window.showInformationMessage(
                        'No recent folders. Would you like to open a folder from your device?',
                        'Open Folder'
                    );
                    if (action === 'Open Folder') {
                        vscode.commands.executeCommand('vscodroid.openFolderFromDevice');
                    }
                    return;
                }

                /** @type {vscode.QuickPickItem[]} */
                const items = folders.map(
                    (/** @type {{ name: string; uri: string; lastOpened: number }} */ f) => ({
                        label: `$(folder) ${f.name}`,
                        description: formatRelativeTime(f.lastOpened),
                        detail: f.uri
                    })
                );

                // Add "Browse..." option at the bottom
                items.push({
                    label: '$(folder-opened) Browse device...',
                    description: 'Open SAF folder picker',
                    detail: ''
                });

                const selected = await vscode.window.showQuickPick(items, {
                    placeHolder: 'Select a recent folder or browse device',
                    matchOnDescription: true,
                    matchOnDetail: true
                });

                if (!selected) return;

                const selectedDetail = selected.detail || '';
                if (!selectedDetail) {
                    vscode.commands.executeCommand('vscodroid.openFolderFromDevice');
                } else {
                    await sendBridgeCommand('openRecentFolder', { uri: selectedDetail });
                }
            } catch (/** @type {*} */ err) {
                vscode.window.showErrorMessage(
                    `Failed to load recent folders: ${err.message}`
                );
            }
        }
    );

    // -- Open in Browser --

    const openInBrowserCmd = vscode.commands.registerCommand(
        'vscodroid.openInBrowser',
        async () => {
            const url = await vscode.window.showInputBox({
                title: 'Open in Browser',
                prompt: 'Enter a URL to open in your device browser',
                value: 'http://localhost:'
            });
            if (!url || !url.trim()) return;

            try {
                await sendBridgeCommand('openExternalUrl', { url: url.trim() });
            } catch (/** @type {*} */ err) {
                vscode.window.showErrorMessage(`Failed to open browser: ${err.message}`);
            }
        }
    );

    // -- SSH keys --

    const generateSshKeyCmd = vscode.commands.registerCommand(
        'vscodroid.generateSshKey',
        async () => {
            try {
                const json = /** @type {string} */ (await sendBridgeCommand('generateSshKey'));
                const result = JSON.parse(json || '{}');
                if (result.success) {
                    vscode.window.showInformationMessage(
                        'SSH key created at ~/.ssh/id_ed25519. Run "VSCodroid: Copy SSH Public Key" to add it to your Git host.'
                    );
                } else {
                    vscode.window.showErrorMessage(
                        `SSH key generation failed: ${result.error || 'unknown error'}`
                    );
                }
            } catch (/** @type {*} */ err) {
                vscode.window.showErrorMessage(`SSH key generation failed: ${err.message}`);
            }
        }
    );

    const copySshPublicKeyCmd = vscode.commands.registerCommand(
        'vscodroid.copySshPublicKey',
        async () => {
            try {
                const pubKey = /** @type {string} */ (await sendBridgeCommand('getSshPublicKey'));
                if (!pubKey || !pubKey.trim()) {
                    vscode.window.showWarningMessage(
                        'No SSH key yet. Run "VSCodroid: Generate SSH Key" first.'
                    );
                    return;
                }
                // The editor's own clipboard, so this needs nothing from Android.
                await vscode.env.clipboard.writeText(pubKey.trim());
                vscode.window.showInformationMessage('SSH public key copied to clipboard.');
            } catch (/** @type {*} */ err) {
                vscode.window.showErrorMessage(`Could not read the SSH public key: ${err.message}`);
            }
        }
    );

    // -- Storage --

    const storageUsageCmd = vscode.commands.registerCommand(
        'vscodroid.showStorageUsage',
        async () => {
            try {
                const json = /** @type {string} */ (
                    await sendBridgeCommand('getStorageBreakdown')
                );
                const b = JSON.parse(json || '{}');

                // Total first, then the parts largest-first: on a device that is out of
                // space the question is which one to act on, and an alphabetical or
                // declaration-ordered list makes that a reading exercise.
                const parts = Object.keys(b)
                    .filter((k) => k !== 'total')
                    .map((k) => ({ key: k, bytes: Number(b[k]) || 0 }))
                    .sort((x, y) => y.bytes - x.bytes);

                /** @type {vscode.QuickPickItem[]} */
                const items = [
                    {
                        label: `$(database) Total: ${formatBytes(Number(b.total) || 0)}`,
                        description: 'app storage in use'
                    },
                    ...parts.map((p) => ({
                        label: `$(circle-filled) ${STORAGE_LABELS[p.key] || p.key}`,
                        description: formatBytes(p.bytes)
                    }))
                ];

                const picked = await vscode.window.showQuickPick(items, {
                    placeHolder: 'Storage in use — select to free up cached data'
                });
                // Any selection means "now do something about it"; there is nothing to
                // drill into, and offering a dead-end list on a full device is the
                // problem this command exists to solve.
                if (picked) {
                    vscode.commands.executeCommand('vscodroid.clearCaches');
                }
            } catch (/** @type {*} */ err) {
                vscode.window.showErrorMessage(
                    `Could not read storage usage: ${err.message}`
                );
            }
        }
    );

    const clearCachesCmd = vscode.commands.registerCommand(
        'vscodroid.clearCaches',
        async () => {
            try {
                const freed = Number(await sendBridgeCommand('clearCaches')) || 0;
                // Say the number. A command that claims to free space without saying how
                // much is indistinguishable from one that did nothing, and "already
                // clear" is a useful answer rather than a failure.
                vscode.window.showInformationMessage(
                    freed > 0
                        ? `Freed ${formatBytes(freed)} of cached data.`
                        : 'Nothing to clear — no cached data was using space.'
                );
            } catch (/** @type {*} */ err) {
                vscode.window.showErrorMessage(`Could not clear caches: ${err.message}`);
            }
        }
    );

    // -- About --

    const aboutCmd = vscode.commands.registerCommand('vscodroid.about', async () => {
        try {
            await sendBridgeCommand('showAboutDialog');
        } catch (/** @type {*} */ err) {
            vscode.window.showErrorMessage(`Failed to open About: ${err.message}`);
        }
    });

    context.subscriptions.push(
        openFolderCmd,
        recentFolderCmd,
        openInBrowserCmd,
        generateSshKeyCmd,
        copySshPublicKeyCmd,
        storageUsageCmd,
        clearCachesCmd,
        aboutCmd
    );
}

function deactivate() {
    if (_channel) {
        _channel.close();
        _channel = undefined;
    }
}

// -- Helpers --

/**
 * Human names for the keys StorageManager.getStorageBreakdown returns. A key with no
 * entry falls back to the raw key rather than being hidden, so a new component added on
 * the Kotlin side still shows up here instead of silently going missing from the total.
 * @type {Record<string, string>}
 */
const STORAGE_LABELS = {
    vscode_server: 'Editor server',
    extensions: 'Extensions',
    user_data: 'Settings and history',
    logs: 'Logs',
    tools: 'Toolchains and tools',
    saf_mirrors: 'Device folder mirrors',
    cache: 'Cache'
};

/**
 * Formats a byte count for people, not for machines.
 * @param {number} bytes
 * @returns {string}
 */
function formatBytes(bytes) {
    if (!bytes || bytes < 0) return '0 B';
    if (bytes < 1024) return `${bytes} B`;
    const units = ['KB', 'MB', 'GB'];
    let value = bytes / 1024;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
        value /= 1024;
        unit++;
    }
    return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

/**
 * Formats a timestamp into a human-readable relative time string.
 * @param {number} timestamp
 * @returns {string}
 */
function formatRelativeTime(timestamp) {
    if (!timestamp) return '';
    const diff = Date.now() - timestamp;
    const seconds = Math.floor(diff / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    const days = Math.floor(hours / 24);

    if (days > 0) return `${days}d ago`;
    if (hours > 0) return `${hours}h ago`;
    if (minutes > 0) return `${minutes}m ago`;
    return 'just now';
}

module.exports = { activate, deactivate };
