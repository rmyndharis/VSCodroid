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

    context.subscriptions.push(openFolderCmd, recentFolderCmd);
}

function deactivate() {
    if (_channel) {
        _channel.close();
        _channel = undefined;
    }
}

// -- Helpers --

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
