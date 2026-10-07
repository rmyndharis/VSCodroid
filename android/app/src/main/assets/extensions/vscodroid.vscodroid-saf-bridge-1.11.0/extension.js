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
 * - vscodroid.manageDeviceFolders  : Lists the local copies of device folders and removes one
 * - vscodroid.clearCaches          : Deletes cached data, reports bytes freed
 * - vscodroid.manageToolchains     : Opens the Android Toolchains screen
 * - vscodroid.toggleExtraKeyRow    : Hides or shows the key row above the keyboard
 * - vscodroid.uiScale              : Sets the size of the whole interface
 * - vscodroid.about                : Opens the Android About dialog
 * - vscodroid.copyBugReport        : Opens a bug report to read, then copies it
 *
 * It also warns about a workspace folder opened by path on shared storage, and
 * offers Open Folder from Device instead; and it offers to reopen a device
 * folder still shown by its copy's hash under the folder's own name.
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
 * How long to wait for a command that only has to be relayed.
 *
 * The relay posts back from the page's main thread as soon as the bridge method
 * returns, and for most commands that is a field read or a hand-off to an
 * Activity, so anything past a moment means the relay is not there at all.
 */
const BRIDGE_TIMEOUT_MS = 5000;

/**
 * How long to wait for a command whose cost is the size of the user's disk.
 *
 * Four of the bridge methods walk directory trees before they can answer:
 * `getStorageBreakdown` sizes every component of the app's storage and the whole
 * of `filesDir` on top; `listSafMirrors` walks every copied device folder twice,
 * once to size it and once to ask whether the device folder holds everything in
 * it; `reclaimSafMirror` re-asks that second question and sizes the copy before
 * removing it; and `clearCaches` deletes trees. The app's own extracted tree is
 * around 875 MB before a single project is opened, so on any real install those
 * walks run for far longer than the deadline above, and until this existed they
 * were all given it: the storage screen and the device-folder screen, the only
 * two places in the app that can reclaim disk, answered "Bridge timeout: is the
 * app running on Android?" for every user who had enough files to need them.
 *
 * The number is not a guess about how long a walk takes; it cannot be, since it
 * is the user's disk. It is the point at which "still working" stops being a
 * plausible explanation and a relay that is not answering at all becomes one.
 */
const DISK_WALK_TIMEOUT_MS = 120000;

/**
 * Sends a command to the main page relay and returns a promise for the response.
 * @param {string} cmd
 * @param {Record<string, *>} [extra]
 * @param {number} [timeoutMs]
 * @returns {Promise<*>}
 */
function sendBridgeCommand(cmd, extra = {}, timeoutMs = BRIDGE_TIMEOUT_MS) {
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

        setTimeout(() => {
            if (_pending[id]) {
                delete _pending[id];
                reject(new Error('Bridge timeout: is the app running on Android?'));
            }
        }, timeoutMs);
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
                    // The bridge raises `existed` for a whole pair it found rather
                    // than made, and it never overwrites one. Reporting a creation
                    // either way told a user their key had just been replaced, which
                    // is the one thing that would break every host they had already
                    // added the old one to.
                    vscode.window.showInformationMessage(
                        result.existed
                            ? 'An SSH key is already set up at ~/.ssh/id_ed25519. Run "VSCodroid: Copy SSH Public Key" to add it to your Git host.'
                            : 'SSH key created at ~/.ssh/id_ed25519. Run "VSCodroid: Copy SSH Public Key" to add it to your Git host.'
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
                    await sendBridgeCommand('getStorageBreakdown', {}, DISK_WALK_TIMEOUT_MS)
                );
                const b = JSON.parse(json || '{}');

                // Total first, then the parts largest-first: on a device that is out of
                // space the question is which one to act on, and an alphabetical or
                // declaration-ordered list makes that a reading exercise.
                // Which rows the clear action can actually reach. Sent by the
                // Kotlin side, which is the only place that knows what
                // clearCaches deletes; a copy here would go stale the first time
                // that method changed.
                const clearable = new Set(
                    Array.isArray(b.clearable) ? b.clearable : []
                );

                const parts = Object.keys(b)
                    .filter((k) => k !== 'total' && k !== 'clearable')
                    .map((k) => ({
                        key: k,
                        bytes: Number(b[k]) || 0,
                        clearable: clearable.has(k)
                    }))
                    .sort((x, y) => y.bytes - x.bytes);

                /** @type {(vscode.QuickPickItem & { key?: string })[]} */
                const items = [
                    {
                        label: `$(database) Total: ${formatBytes(Number(b.total) || 0)}`,
                        description: 'app storage in use'
                    },
                    ...parts.map((p) => ({
                        key: p.key,
                        label: `$(circle-filled) ${STORAGE_LABELS[p.key] || p.key}`,
                        description: p.clearable
                            ? `${formatBytes(p.bytes)} (can be cleared)`
                            : formatBytes(p.bytes)
                    }))
                ];

                const picked = await vscode.window.showQuickPick(items, {
                    placeHolder: 'Storage in use; select a row that can be cleared'
                });
                if (!picked) return;
                // Only the rows the action can reach. Running it for the others
                // freed nothing and then reported success or "nothing to clear",
                // neither of which was about the row picked, so a user out of
                // space was told their largest directory had already been dealt
                // with.
                if (picked.key && clearable.has(picked.key)) {
                    vscode.commands.executeCommand('vscodroid.clearCaches');
                } else if (picked.key === 'saf_mirrors') {
                    // The one unclearable row that has somewhere to go. It is
                    // usually the largest, it is never freed automatically once
                    // anything has been built inside it, and until this branch
                    // existed choosing it produced a sentence and a dead end.
                    // The key is compared rather than a new flag added to the
                    // breakdown: `clearable` means "keys clearCaches empties",
                    // and widening it to mean "keys that do something" is how
                    // that set would come to include one this action cannot free.
                    vscode.commands.executeCommand('vscodroid.manageDeviceFolders');
                } else if (picked.key) {
                    vscode.window.showInformationMessage(
                        `${STORAGE_LABELS[picked.key] || picked.key} is not cached data ` +
                            'and cannot be cleared from here.'
                    );
                } else {
                    // The Total row, the only item built without a key. Every
                    // other unactionable row explains itself above, and this one
                    // is the first and largest thing on a screen a person opens
                    // because they are out of space: with no arm of its own it
                    // closed the picker in silence, which reads as a screen that
                    // does not work rather than as a row with nothing to do.
                    //
                    // It is NOT the sum of the rows, which is what this sentence
                    // said first: Total is every byte under the app's data and
                    // cache directories, while the rows name the subtrees that
                    // can be identified and acted on. The same change widened
                    // that gap, narrowing the cache row to the four directories
                    // the clear action reaches while leaving the whole cache
                    // directory in Total. A person out of disk adds the rows up,
                    // and is owed the two figures rather than an equality the
                    // breakdown does not maintain.
                    //
                    // Both numbers, rather than a sentence about the difference.
                    // A sentence would be one more claim to keep true as rows are
                    // added and narrowed, which is exactly what went wrong here;
                    // these are read off the same breakdown the rows are drawn
                    // from and cannot disagree with them.
                    const named = parts.reduce((sum, p) => sum + p.bytes, 0);
                    vscode.window.showInformationMessage(
                        `That is every byte this app is using: ${formatBytes(Number(b.total) || 0)}. ` +
                            `The rows below account for ${formatBytes(named)} of it. ` +
                            'Pick one of those to see what can be freed.'
                    );
                }
            } catch (/** @type {*} */ err) {
                vscode.window.showErrorMessage(
                    `Could not read storage usage: ${err.message}`
                );
            }
        }
    );

    // -- Device folder storage --

    /**
     * Lists the local copy of every device folder and offers to remove one.
     *
     * This is the only place in the app that can name them. Opening a device
     * folder copies it under the app's own storage, and the app keeps a grant for
     * the ten most recently opened folders; the eleventh releases its grant, and
     * with it the entry that carried the folder's name. The copy stays on disk.
     * The automatic cleanup then declines to remove it whenever it holds a file
     * the device folder does not, which is the ordinary state of any folder
     * somebody has run a build or a clone in, so the largest copies are exactly
     * the ones that are never reclaimed and never named anywhere else.
     */
    const manageDeviceFoldersCmd = vscode.commands.registerCommand(
        'vscodroid.manageDeviceFolders',
        async () => {
            /** @type {*[]} */
            let mirrors;
            // Reading the list gets its own catch. It fails for reasons that have
            // nothing to do with removing anything: the relay is not there, or the
            // bridge times out. The catch below composes a sentence naming a failed
            // removal, so sharing one with the listing told the user a folder's copy
            // had survived a removal they were never offered and never confirmed.
            try {
                const json = /** @type {string} */ (
                    await sendBridgeCommand('listSafMirrors', {}, DISK_WALK_TIMEOUT_MS)
                );
                mirrors = JSON.parse(json || '[]');

                if (mirrors.length === 0) {
                    vscode.window.showInformationMessage(
                        'No device folders have been copied into VSCodroid yet.'
                    );
                    return;
                }
            } catch (/** @type {*} */ err) {
                vscode.window.showErrorMessage(
                    `Could not read the list of device folder copies: ${err.message}`
                );
                return;
            }

            try {
                /** @type {(vscode.QuickPickItem & { mirror?: * })[]} */
                const items = mirrors.map((/** @type {*} */ m) => ({
                    mirror: m,
                    // A copy whose folder fell off the recent list has no name
                    // left anywhere, so say that rather than showing the hash,
                    // which reads like a folder name and is not one.
                    label: `$(folder) ${m.name || 'Unnamed folder'}`,
                    description:
                        formatBytes(Number(m.bytes) || 0) +
                        (m.granted ? '' : ' · no longer accessible'),
                    detail: m.reclaimable
                        ? 'Every file here is also in the device folder'
                        : 'Holds files that are NOT in the device folder'
                }));

                const picked = await vscode.window.showQuickPick(items, {
                    placeHolder: 'Local copies of device folders; select one to remove'
                });
                if (!picked || !picked.mirror) return;

                const m = picked.mirror;
                const name = m.name || 'this folder';
                const size = formatBytes(Number(m.bytes) || 0);
                // Two different warnings, because the two cases cost the user
                // different things and a single wording would have to be wrong
                // about one of them. Modal in both, since neither is undoable.
                const message = m.reclaimable
                    ? `Remove VSCodroid's local copy of ${name} and free ${size}? ` +
                      'The device folder itself is not touched, and every file in ' +
                      'the copy is already there.'
                    : `Remove VSCodroid's local copy of ${name} and free ${size}? ` +
                      'THIS DELETES FILES. Some files in the copy are not in the ' +
                      'device folder, including anything under node_modules, .git, ' +
                      '__pycache__ or .gradle, and they exist nowhere else. This ' +
                      'cannot be undone.';
                const confirm = await vscode.window.showWarningMessage(
                    message, { modal: true }, 'Remove'
                );
                if (confirm !== 'Remove') return;

                await sendBridgeCommand('reclaimSafMirror', {
                    hash: m.hash,
                    force: !m.reclaimable
                }, DISK_WALK_TIMEOUT_MS);
                // Straight back to the list: the usual reason for opening this is
                // to free space, and one folder is rarely the whole answer.
                vscode.commands.executeCommand('vscodroid.manageDeviceFolders');
            } catch (/** @type {*} */ err) {
                // The refusal reasons arrive here, and each names something that
                // has to change before the removal can happen: a folder that is
                // open, one still syncing, or one this session has had open,
                // which needs a restart before its copy can be removed safely.
                vscode.window.showErrorMessage(
                    `Could not remove that folder's local copy: ${err.message}`
                );
            }
        }
    );

    const clearCachesCmd = vscode.commands.registerCommand(
        'vscodroid.clearCaches',
        async () => {
            try {
                const freed = Number(await sendBridgeCommand('clearCaches', {}, DISK_WALK_TIMEOUT_MS)) || 0;
                // Say the number. A command that claims to free space without saying how
                // much is indistinguishable from one that did nothing, and "already
                // clear" is a useful answer rather than a failure.
                vscode.window.showInformationMessage(
                    freed > 0
                        ? `Freed ${formatBytes(freed)} of cached data.`
                        : 'Nothing to clear: no cached data was using space.'
                );
            } catch (/** @type {*} */ err) {
                vscode.window.showErrorMessage(`Could not clear caches: ${err.message}`);
            }
        }
    );

    // -- Toolchains --

    // The Toolchains screen had one way in: the launcher shortcut SplashActivity
    // publishes. A user who skipped the first-run picker, or who cleared the
    // shortcut, had no route from inside the editor at all, while the bridge
    // method and its relay branch have both been there the whole time with no
    // sender. This is that sender.
    const toolchainsCmd = vscode.commands.registerCommand(
        'vscodroid.manageToolchains',
        async () => {
            try {
                await sendBridgeCommand('openToolchainSettings');
            } catch (/** @type {*} */ err) {
                vscode.window.showErrorMessage(`Failed to open Toolchains: ${err.message}`);
            }
        }
    );

    // -- Extra Key Row --

    // A hardware keyboard carries every key the row offers, and the row takes its
    // height out of the editor whenever the soft keyboard is up, which some
    // keyboard apps keep up beside a hardware one. Listed on the remote indicator
    // as well as the palette: with the row hidden and no hardware keyboard, the
    // palette's Ctrl+Shift+P cannot be typed, and the way back has to be a tap.
    const toggleKeyRowCmd = vscode.commands.registerCommand(
        'vscodroid.toggleExtraKeyRow',
        async () => {
            try {
                const hidden = (await sendBridgeCommand('toggleExtraKeyRow')) === true;
                // The row only ever shows above the soft keyboard, and running a
                // command usually takes that keyboard down, so "shown" has nothing
                // to point at yet. Say when it will appear instead, and no more than
                // that: the row still stands down where the keyboard leaves the page
                // no height, which is a phone in landscape.
                vscode.window.showInformationMessage(
                    hidden
                        ? 'Extra key row hidden. Run "VSCodroid: Toggle Extra Key Row" again to bring it back.'
                        : 'Extra key row shown. It appears above the keyboard while you type, when the screen has room for it.'
                );
            } catch (/** @type {*} */ err) {
                vscode.window.showErrorMessage(`Could not toggle the extra key row: ${err.message}`);
            }
        }
    );

    // -- UI scale --

    // The editor and terminal font sizes reach those two and nothing else, and
    // the side bar, tabs, menus and status bar are drawn at a fixed size that is
    // small on a phone. This sets the size of the whole page, which the page
    // keeps and applies on every load. The page also says which sizes to offer,
    // since it is the side that knows the screen: those that leave the page at
    // least 320 pixels wide.
    const uiScaleCmd = vscode.commands.registerCommand('vscodroid.uiScale', async () => {
        try {
            const { scale, choices } = /** @type {{ scale: number, choices: number[] }} */ (
                await sendBridgeCommand('getUiScale')
            );
            if (choices.length < 2) {
                vscode.window.showInformationMessage(
                    'This screen is too narrow to show the interface any larger.'
                );
                return;
            }
            const picked = await vscode.window.showQuickPick(
                choices.map((s) => ({
                    label: `${Math.round(s * 100)}%`,
                    description: s === scale ? 'current' : undefined,
                    scale: s
                })),
                { placeHolder: 'Size of the whole interface: side bar, tabs, menus and editor' }
            );
            if (!picked || picked.scale === scale) return;
            // The page answers with the size in force, which is 100% when the one
            // picked did not take effect on this device.
            const now = await sendBridgeCommand('setUiScale', { scale: picked.scale });
            if (now === picked.scale) {
                vscode.window.showInformationMessage(`UI scale set to ${picked.label}.`);
            } else {
                vscode.window.showWarningMessage(
                    `${picked.label} did not take effect on this device, so the UI scale is back at 100%.`
                );
            }
        } catch (/** @type {*} */ err) {
            vscode.window.showErrorMessage(`Could not change the UI scale: ${err.message}`);
        }
    });

    // -- About --

    const aboutCmd = vscode.commands.registerCommand('vscodroid.about', async () => {
        try {
            await sendBridgeCommand('showAboutDialog');
        } catch (/** @type {*} */ err) {
            vscode.window.showErrorMessage(`Failed to open About: ${err.message}`);
        }
    });

    // -- Bug report --

    // The relay has answered generateBugReport all along and nothing sent it,
    // so a user whose editor froze or reloaded by itself had no report to send.
    // Opened in an editor rather than put straight on the clipboard: the report
    // quotes the server's output, which can name the user's files and folders,
    // and asks to be read before it is shared. Copy then takes what the editor
    // holds, so a line the user deleted stays out of what they paste.
    //
    // The notice hides itself after ten seconds, long before a report of a few
    // hundred lines has been read, so Copy also waits in the status bar for as
    // long as the newest report is open. The item needs an id: the workbench
    // keeps every extension host's items in one table, and an item without one
    // is numbered per host, so it took the process monitor's slot and lost it
    // again at that item's next update.
    const copyReportItem = vscode.window.createStatusBarItem('copyBugReport');
    copyReportItem.text = '$(copy) Copy Bug Report';
    /** @type {vscode.TextDocument | undefined} */
    let openReport;
    /** @param {vscode.TextDocument} doc */
    const copyReport = async (doc) => {
        await vscode.env.clipboard.writeText(doc.getText());
        vscode.window.showInformationMessage('Bug report copied.');
    };
    const reportClosedListener = vscode.workspace.onDidCloseTextDocument((closed) => {
        // A language change is reported as a close too, of a document that
        // stays open.
        if (closed !== openReport || vscode.workspace.textDocuments.includes(closed)) return;
        openReport = undefined;
        copyReportItem.hide();
    });
    const bugReportCmd = vscode.commands.registerCommand(
        'vscodroid.copyBugReport',
        // The status bar entry runs it with the report to copy; the palette
        // runs it with nothing, for a new report.
        async (/** @type {vscode.TextDocument | undefined} */ report) => {
            if (report) {
                await copyReport(report);
                return;
            }
            /** @type {vscode.TextDocument} */
            let doc;
            try {
                const report = /** @type {string} */ (await sendBridgeCommand('generateBugReport'));
                // Empty only when the bridge refused the session token.
                if (!report) {
                    throw new Error('VSCodroid did not accept the request. Reload the window and try again.');
                }
                // Plain text, so the editor does not run language detection on it.
                doc = await vscode.workspace.openTextDocument({ content: report, language: 'plaintext' });
                await vscode.window.showTextDocument(doc);
            } catch (/** @type {*} */ err) {
                vscode.window.showErrorMessage(`Could not create the bug report: ${err.message}`);
                return;
            }
            openReport = doc;
            copyReportItem.command = { title: 'Copy Bug Report', command: 'vscodroid.copyBugReport', arguments: [doc] };
            copyReportItem.show();
            const action = await vscode.window.showInformationMessage(
                'Read the bug report before you share it: the server log can name your files ' +
                    'and folders. Delete what you want kept private, then tap Copy, here or in ' +
                    'the status bar.',
                COPY
            );
            if (action === COPY) await copyReport(doc);
        }
    );

    // -- Shared storage opened by path --

    // The app holds no storage permission, so Android lets it list every
    // directory on shared storage and hides every file another app saved there.
    // The workbench's own Open Folder and Add Folder to Workspace dialogs reach
    // those directories by path, and the folder then opens with its subfolders
    // and none of its files, with nothing on screen to say why. Run once here,
    // which covers every route that loads the page (Open Folder, Open Recent,
    // the folder reopened at launch, a workspace file), and again when a folder
    // is added to an open workspace in place.
    //
    // Once per folder each time the editor server starts. Every reload and every
    // folder switch loads a new page, and with it a new extension host, so a set
    // kept here alone raised the same warning on each of them. The server
    // outlives its pages, and server.js notes the pid and port of every editor
    // server it starts, so the folders already warned about are kept in global
    // state with that note and forgotten once it changes. A server adopted after
    // its bootstrap died keeps its note, and it is the same server.
    //
    // A dialog, not a notification. The warning matters at the moment the folder
    // opens, and a warning toast hides itself after 12 seconds and is then only
    // a dot on the bell, which a phone user has no reason to open; on a first
    // open the toast is up while the user is reading the Explorer for the
    // missing files. Shown once per folder per start, stopping the user once
    // costs less than files that seem to be gone. Don't Show Again silences a
    // folder for good, for a user who works there knowingly, such as in a folder
    // whose files this app made and can see. The folders warned about and the
    // ones silenced are both kept by the key sharedStorageFolder gives them, so
    // opening a folder again under another spelling undoes neither.
    const readWarnedThisServer = async () => {
        let server = '';
        try {
            server = new TextDecoder().decode(await vscode.workspace.fs.readFile(
                vscode.Uri.joinPath(context.extensionUri, ...EDITOR_SERVER_NOTE)
            ));
        } catch (_) {
            // No note to tie the warnings to, so they last as long as this page,
            // which is no worse than before there was one.
        }
        const shown = /** @type {{ server?: string, folders?: string[] } | undefined} */ (
            context.globalState.get(SHOWN_SHARED_STORAGE)
        );
        return {
            server,
            folders: new Set(server && shown && shown.server === server ? shown.folders : []),
        };
    };
    // Read when the first folder on shared storage turns up, not on every page
    // load: most never open one.
    /** @type {ReturnType<typeof readWarnedThisServer> | undefined} */
    let warnedThisServer;
    const silencedSharedStorage = () =>
        /** @type {string[]} */ (context.globalState.get(SILENCED_SHARED_STORAGE, []));
    const warnSharedStorage = async () => {
        for (const folder of vscode.workspace.workspaceFolders || []) {
            const where = sharedStorageFolder(folder.uri.path);
            if (!where || silencedSharedStorage().includes(where.key)) continue;
            const warned = await (warnedThisServer = warnedThisServer || readWarnedThisServer());
            if (warned.folders.has(where.key)) continue;
            // Marked before the dialog, which stays up for as long as the user
            // takes and may end in a page load: Open Folder from Device opens
            // the device folder in place of this one.
            warned.folders.add(where.key);
            if (warned.server) {
                context.globalState.update(
                    SHOWN_SHARED_STORAGE, { server: warned.server, folders: [...warned.folders] }
                );
            }
            const { message, detail, buttons } = sharedStorageWarning(where, folder.name);
            const action = await vscode.window.showWarningMessage(
                message, { modal: true, detail }, ...buttons
            );
            if (action === OPEN_FROM_DEVICE) {
                vscode.commands.executeCommand('vscodroid.openFolderFromDevice');
            } else if (action === DONT_SHOW_AGAIN) {
                context.globalState.update(
                    SILENCED_SHARED_STORAGE, [...silencedSharedStorage(), where.key]
                );
            }
        }
    };
    warnSharedStorage();
    const workspaceFoldersListener =
        vscode.workspace.onDidChangeWorkspaceFolders(warnSharedStorage);

    // -- A device folder shown by its copy's hash --

    // A device folder is copied to saf-mirrors/<hash>, and the workbench names a
    // folder after the last segment of its path, so a copy opened there showed
    // the hash in the Explorer and the title. The app opens a copy through
    // saf-mirrors/by-name/<hash>/<the folder's name> instead, a link to it. A
    // copy made before that still opens by its hash, as the folder reopened at
    // launch after an update does, and moving it is not the app's to do
    // unasked: the workbench keeps a folder's open editors, unsaved changes and
    // terminals under the path it was opened by, so whatever was left unsaved
    // under the hash would stay there, out of sight. So the move is offered, and
    // made once nothing unsaved is held and no terminal is open; the editors
    // that were open are opened again under the new path.
    const offerNamedPath = async () => {
        const open = copyOpenByHash();
        if (!open) return;
        const hash = open.copy.slice(open.copy.lastIndexOf('/') + 1);
        const declined = /** @type {string[]} */ (context.globalState.get(DECLINED_NAMED_PATH, []));
        if (declined.includes(hash)) return;
        const named = await namedPathOf(open);
        if (!named) return;
        const name = named.slice(named.lastIndexOf('/') + 1);
        const action = await vscode.window.showInformationMessage(
            `This device folder is shown as ${hash}, the name of its copy in VSCodroid. ` +
                `Reopen it as ${name}?`,
            REOPEN, DONT_ASK_AGAIN
        );
        if (action === DONT_ASK_AGAIN) {
            context.globalState.update(DECLINED_NAMED_PATH, [...declined, hash]);
            return;
        }
        if (action !== REOPEN) return;
        if (moveWouldLose() || (await holdsBackups(context))) {
            vscode.window.showWarningMessage(
                `Save or close the files with unsaved changes and close the terminals first, ` +
                    `or they stay behind with ${hash}. The folder offers this again the next ` +
                    'time it opens.'
            );
            return;
        }
        const target = open.uri.with({ path: open.file ? `${named}/${open.file}` : named });
        await context.globalState.update(CARRIED_EDITORS, {
            to: target.path, root: named, files: editorsUnder(open.copy), at: Date.now()
        });
        await vscode.commands.executeCommand('vscode.openFolder', target, { forceReuseWindow: true });
    };
    // The page the move lands on opens the editors carried to it.
    const reopenCarriedEditors = async () => {
        const carried = context.globalState.get(CARRIED_EDITORS);
        if (!carried) return;
        await context.globalState.update(CARRIED_EDITORS, undefined);
        const here = vscode.workspace.workspaceFile ||
            ((vscode.workspace.workspaceFolders || [])[0] || {}).uri;
        if (!here || here.path !== carried.to || !(Date.now() - carried.at < CARRY_MS)) return;
        for (const file of carried.files) {
            try {
                await vscode.window.showTextDocument(
                    here.with({ path: `${carried.root}/${file}` }), { preview: false }
                );
            } catch (_) {
                // Deleted since the move: nothing to open.
            }
        }
    };
    reopenCarriedEditors().then(offerNamedPath).catch(() => {});

    context.subscriptions.push(
        workspaceFoldersListener,
        openFolderCmd,
        recentFolderCmd,
        openInBrowserCmd,
        generateSshKeyCmd,
        copySshPublicKeyCmd,
        storageUsageCmd,
        manageDeviceFoldersCmd,
        clearCachesCmd,
        toolchainsCmd,
        toggleKeyRowCmd,
        uiScaleCmd,
        aboutCmd,
        copyReportItem,
        reportClosedListener,
        bugReportCmd
    );
}

function deactivate() {
    if (_channel) {
        _channel.close();
        _channel = undefined;
    }
}

// -- Helpers --

/** The bug report notice's button, compared against the choice it returns. */
const COPY = 'Copy';

/** The shared-storage warning's buttons, compared against the choice it returns. */
const OPEN_FROM_DEVICE = 'Open Folder from Device';
const DONT_SHOW_AGAIN = "Don't Show Again";

/** The globalState key holding the folders Don't Show Again silenced, by key. */
const SILENCED_SHARED_STORAGE = 'sharedStorageWarning.silenced';

/**
 * The globalState key holding the folders warned about while one editor server
 * runs, with that server's note: `{ server, folders }`.
 */
const SHOWN_SHARED_STORAGE = 'sharedStorageWarning.shown';

/**
 * The editor server's note, from this extension's own directory.
 *
 * Bundled extensions live in the server's `--extensions-dir`,
 * `<files>/home/.vscodroid/extensions/<this one>`, and server.js writes the pid
 * and port of each editor server it starts to `<files>/server/editor-server.pid`
 * (`Environment.getExtensionsDir`, `Environment.getServerDir`, `EDITOR_PID_FILE`).
 * Read through the editor's own file access, which reaches the server's files.
 */
const EDITOR_SERVER_NOTE = ['..', '..', '..', '..', 'server', 'editor-server.pid'];

/**
 * Where a workspace folder sits on shared storage, where this app sees the
 * directories and not the files other apps saved, or null for a folder the app
 * sees in full. `below` is its path below the storage volume, '' at the top of
 * the volume, and `key` names the folder however its path was spelled.
 *
 * `/sdcard`, `/mnt/sdcard` and `/storage/self/primary` lead to
 * `/storage/emulated/<user>`, and the workbench keeps whichever spelling the
 * user typed, a trailing slash included; any other `/storage/<name>` is an SD
 * card or a USB drive, which a path cannot tell apart (`removable`).
 * `/storage/emulated` and `/storage/self` themselves are neither. Below the
 * volume, shared storage ignores case. So the key is the volume and the path
 * below it in lower case: `/sdcard/Documents/notes` and
 * `/storage/emulated/0/documents/notes/` are one folder, and the folders already
 * warned about and the ones silenced are matched by it.
 *
 * `picker` is what Android's folder picker does with the folder, in
 * ExternalStorageProvider from Android 11: on the device's storage and on an SD
 * card it does not grant the top of the volume, its Download folder or its
 * Android folder, though it grants a folder inside them ('inside'), and it does
 * not even list Android/data, Android/obb or Android/sandbox, so nothing in
 * those can be granted ('none'). A USB drive is granted whole, its top included.
 *
 * A device folder's copy never matches: it lives in the app's files directory
 * under `/data`. Nor does `Android/data/<package>/` at the top of a volume, the
 * old home of `~/projects`, whose files the app created and can see; any
 * package there is this one, since Android 11 keeps an app out of every other
 * app's directory there.
 * @param {string} folderPath
 * @returns {{ key: string, below: string, removable: boolean, picker: 'open' | 'inside' | 'none' } | null}
 */
function sharedStorageFolder(folderPath) {
    const volume =
        /^\/(?:(storage\/emulated\/\d+|storage\/self\/primary|sdcard|mnt\/sdcard)|storage\/(?!(?:emulated|self)(?:\/|$))([^/]+))(?:\/(.*))?$/
            .exec(folderPath);
    if (!volume) return null;
    const below = (volume[3] || '').replace(/\/+$/, '');
    if (/^Android\/data\/[^/]+(\/|$)/i.test(below)) return null;
    return {
        key: `${volume[1] ? 'primary' : volume[2]}:${below}`.toLowerCase(),
        below,
        removable: !volume[1],
        picker: /^Android\/(data|obb|sandbox)(\/|$)/i.test(below) ? 'none'
            : /^(Download|Android)?$/i.test(below) ? 'inside' : 'open',
    };
}

/**
 * What the shared-storage warning says about a folder, and the buttons it offers.
 *
 * Open Folder from Device is offered wherever it can show the files, which is
 * not in Android/data, Android/obb or Android/sandbox. The wording for an SD
 * card or a USB drive covers both, since the path cannot say which it is.
 * @param {{ below: string, removable: boolean, picker: string }} where
 * @param {string} folderName
 * @returns {{ message: string, detail: string, buttons: string[] }}
 */
function sharedStorageWarning(where, folderName) {
    const place = where.below ? `in ${folderName}`
        : where.removable ? 'on this SD card or USB drive' : 'on your device storage';
    const message = `Android hides the files other apps saved ${place}.`;
    const hidden = 'They do not show here and cannot be opened.';
    const leftOut = 'apart from files over 50 MB and folders such as .git and node_modules';
    if (where.picker === 'none') {
        return {
            message,
            detail: `${hidden} Android keeps each folder in Android/data, Android/obb and ` +
                'Android/sandbox private to the app it belongs to, and on the device\'s ' +
                'storage and SD cards, Open Folder from Device cannot open them either.',
            buttons: [DONT_SHOW_AGAIN],
        };
    }
    const detail = where.picker === 'open'
        ? `${hidden} Open Folder from Device shows them, ${leftOut}.`
        : where.removable
            ? `${hidden} Open Folder from Device shows them, ${leftOut}. It can open this ` +
              'folder itself on a USB drive; on an SD card Android does not allow that, so ' +
              'pick a folder inside it.'
            : `${hidden} Android does not let an app open this folder itself from the device, ` +
              `so pick a folder inside it with Open Folder from Device, which shows them, ${leftOut}.`;
    return { message, detail, buttons: [OPEN_FROM_DEVICE, DONT_SHOW_AGAIN] };
}

/** The named path offer's buttons, compared against the choice it returns. */
const REOPEN = 'Reopen';
const DONT_ASK_AGAIN = "Don't Ask Again";

/** The globalState key holding the copies whose move to a named path was declined. */
const DECLINED_NAMED_PATH = 'namedPath.declined';

/**
 * The globalState key carrying the open editors across that move:
 * `{ to, root, files, at }`, the path moved to, the named path the files are
 * relative to, the files, the active one last, and when.
 */
const CARRIED_EDITORS = 'namedPath.carried';

/** How long carried editors wait for their page, which a slow start can delay. */
const CARRY_MS = 120000;

/** Where the copies' named paths live under saf-mirrors: SafStorageManager.NAMED_DIR. */
const NAMED_DIR = 'by-name';

/**
 * The device folder copy this page has open by its hash: the folder itself, or
 * a workspace file at the top of one. `uri` is what is open, `copy` the copy's
 * path and `file` the workspace file's name, or '' for the folder. Null for
 * anything else, a workspace of several folders included.
 * @returns {{ uri: *, copy: string, file: string } | null}
 */
function copyOpenByHash() {
    const workspaceFile = vscode.workspace.workspaceFile;
    if (workspaceFile) {
        const m = /^(.*\/saf-mirrors\/[0-9a-f]{12})\/([^/]+\.code-workspace)$/.exec(workspaceFile.path);
        return m ? { uri: workspaceFile, copy: m[1], file: m[2] } : null;
    }
    const folders = vscode.workspace.workspaceFolders || [];
    if (folders.length !== 1 || !/\/saf-mirrors\/[0-9a-f]{12}$/.test(folders[0].uri.path)) return null;
    return { uri: folders[0].uri, copy: folders[0].uri.path, file: '' };
}

/**
 * The copy's named path, `saf-mirrors/by-name/<hash>/<name>`, which the app
 * makes when a page opens the copy, or null while there is not exactly one.
 * @param {{ uri: *, copy: string }} open
 * @returns {Promise<string | null>}
 */
async function namedPathOf(open) {
    const at = open.copy.lastIndexOf('/');
    const dir = open.uri.with({
        path: `${open.copy.slice(0, at)}/${NAMED_DIR}/${open.copy.slice(at + 1)}`
    });
    try {
        const links = (await vscode.workspace.fs.readDirectory(dir)).filter(([, type]) =>
            (type & vscode.FileType.SymbolicLink) && (type & vscode.FileType.Directory));
        return links.length === 1 ? `${dir.path}/${links[0][0]}` : null;
    } catch (_) {
        return null;
    }
}

/** Whether moving this page would lose a terminal or unsaved changes it shows. */
function moveWouldLose() {
    return vscode.window.terminals.length > 0 ||
        vscode.workspace.textDocuments.some((d) => d.isDirty) ||
        (vscode.workspace.notebookDocuments || []).some((d) => d.isDirty) ||
        vscode.window.tabGroups.all.some((g) => g.tabs.some((t) => t.isDirty));
}

/**
 * Whether the workbench holds a backup of unsaved changes for this page's folder.
 *
 * The editors with unsaved changes are reopened by the workbench after this
 * extension starts, so `moveWouldLose` can miss them for a moment, and a Reopen
 * chosen then would leave them behind. Their backups can be asked for at once:
 * the workbench keeps them under `Backups/<id>`, beside `workspaceStorage/<id>`,
 * which holds this extension's storageUri for the folder. A layout that moves
 * reads as no such directory, which leaves the check above as the guard it was.
 * Any other failure reads as yes.
 * @param {vscode.ExtensionContext} context
 * @returns {Promise<boolean>}
 */
async function holdsBackups(context) {
    const storage = context.storageUri;
    if (!storage) return true;
    const id = storage.path.split('/').slice(-2)[0];
    const backups = vscode.Uri.joinPath(storage, '..', '..', '..', 'Backups', id);
    try {
        for (const [name, type] of await vscode.workspace.fs.readDirectory(backups)) {
            if (!(type & vscode.FileType.Directory)) return true;
            if ((await vscode.workspace.fs.readDirectory(vscode.Uri.joinPath(backups, name))).length) {
                return true;
            }
        }
        return false;
    } catch (/** @type {*} */ err) {
        return !(err && err.code === 'FileNotFound');
    }
}

/**
 * The files open in text editors under `copy`, as paths relative to it, each
 * once, with the active editor's file last so that it ends up in front.
 * @param {string} copy
 * @returns {string[]}
 */
function editorsUnder(copy) {
    const groups = vscode.window.tabGroups;
    const active = groups.activeTabGroup && groups.activeTabGroup.activeTab;
    const tabs = groups.all.flatMap((g) => g.tabs).filter((t) => t !== active);
    const files = [...tabs, active]
        .map((t) => (t && t.input instanceof vscode.TabInputText ? t.input.uri.path : ''))
        .filter((p) => p.startsWith(`${copy}/`))
        .map((p) => p.slice(copy.length + 1));
    return files.filter((f, i) => files.lastIndexOf(f) === i);
}

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
    projects: 'Projects',
    cache: 'Cache'
};

/**
 * Formats a byte count for people, not for machines.
 *
 * Decimal, matching the app's own StorageManager.formatSize and the phone's
 * storage screen. This screen's total is the one figure a user can hold beside
 * Settings > Apps > VSCodroid > Storage, so it has to be counted the same way.
 * @param {number} bytes
 * @returns {string}
 */
function formatBytes(bytes) {
    if (!bytes || bytes < 0) return '0 B';
    if (bytes < 1000) return `${bytes} B`;
    const units = ['KB', 'MB', 'GB'];
    let value = bytes / 1000;
    let unit = 0;
    while (value >= 1000 && unit < units.length - 1) {
        value /= 1000;
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
