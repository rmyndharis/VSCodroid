const vscode = require('vscode');
const fs = require('fs');
const os = require('os');

// Loopback, in the byte order /proc/net/tcp prints addresses in: little-endian
// hex, so 127.0.0.1 reads back as 0100007F. A server on this address answers
// only to this device, which is the whole distinction this command exists to
// explain.
const LOOPBACK_V4 = '0100007F';
const ANY_V4 = '00000000';
const ANY_V6 = '00000000000000000000000000000000';
const LOOPBACK_V6 = '00000000000000000000000001000000';

const TCP_LISTEN = '0A';

/**
 * Every TCP port this device is listening on, and whether it answers the
 * network or only itself.
 *
 * Read from /proc rather than by probing: probing a port tells you something
 * answered, not who is allowed to reach it, and the difference between
 * "listening on 0.0.0.0" and "listening on 127.0.0.1" is exactly what a user
 * needs to be told.
 */
function listeningPorts(read = (p) => fs.readFileSync(p, 'utf8')) {
    const found = new Map();

    for (const [path, any] of [
        ['/proc/net/tcp', ANY_V4],
        ['/proc/net/tcp6', ANY_V6],
    ]) {
        let content;
        try {
            content = read(path);
        } catch {
            continue; // tcp6 is absent on devices without IPv6; not an error
        }

        for (const line of content.split('\n').slice(1)) {
            const fields = line.trim().split(/\s+/);
            if (fields.length < 4 || fields[3] !== TCP_LISTEN) continue;

            const [address, portHex] = fields[1].split(':');
            const port = parseInt(portHex, 16);
            if (!port) continue;

            // A port bound to both stacks appears twice; reachable wins, because
            // one interface answering the network is enough.
            const reachable = address === any;
            if (!found.has(port) || reachable) found.set(port, reachable);
        }
    }
    return found;
}

/** The addresses another device on this network could dial. */
function networkAddresses() {
    const addresses = [];
    for (const [name, entries] of Object.entries(os.networkInterfaces())) {
        for (const entry of entries || []) {
            if (entry.family !== 'IPv4' || entry.internal) continue;
            addresses.push({ name, address: entry.address });
        }
    }
    return addresses;
}

function activate(context) {
    const command = vscode.commands.registerCommand('vscodroid.serveOnNetwork', async () => {
        const addresses = networkAddresses();
        if (addresses.length === 0) {
            vscode.window.showWarningMessage(
                'This device is not on a network right now. Connect to Wi-Fi, then run this again.',
            );
            return;
        }

        // The editor's own server is listening too, and offering it would send
        // someone to a page that is not theirs. Kotlin exports the port it
        // chose; if that is ever missing, showing one extra entry is a smaller
        // failure than hiding a real one, so nothing is filtered in that case.
        const editorPort = parseInt(process.env.VSCODROID_PORT || '', 10);

        const ports = listeningPorts();
        const reachable = [];
        const localOnly = [];
        for (const [port, isReachable] of ports) {
            if (port === editorPort) continue;
            (isReachable ? reachable : localOnly).push(port);
        }
        reachable.sort((a, b) => a - b);
        localOnly.sort((a, b) => a - b);

        if (reachable.length === 0 && localOnly.length === 0) {
            vscode.window.showInformationMessage(
                'No dev server is running yet. Start one in the terminal, then run this again.',
            );
            return;
        }

        const items = [];
        for (const port of reachable) {
            for (const { address, name } of addresses) {
                items.push({
                    label: `$(globe) http://${address}:${port}`,
                    description: name,
                    detail: 'Reachable from other devices on this network — select to copy',
                    url: `http://${address}:${port}`,
                });
            }
        }
        for (const port of localOnly) {
            items.push({
                label: `$(circle-slash) port ${port} — this device only`,
                detail:
                    'Listening on localhost, so other devices cannot reach it. Restart the server ' +
                    'bound to every interface: Vite `--host 0.0.0.0`, Next.js `-H 0.0.0.0`, ' +
                    'Node `server.listen(port, "0.0.0.0")`, Python `--bind 0.0.0.0`.',
                url: null,
            });
        }

        const picked = await vscode.window.showQuickPick(items, {
            title: 'Serve on Network',
            placeHolder:
                reachable.length > 0
                    ? 'Pick an address to copy, then open it on the other device'
                    : 'Nothing is reachable from other devices yet',
        });
        if (!picked) return;

        if (!picked.url) {
            vscode.window.showInformationMessage(
                'That server only answers this device. Restart it bound to 0.0.0.0 and run this again.',
            );
            return;
        }

        await vscode.env.clipboard.writeText(picked.url);
        vscode.window.showInformationMessage(`Copied ${picked.url} — open it on the other device.`);
    });

    context.subscriptions.push(command);
}

function deactivate() {}

// listeningPorts is exported so it can be exercised against captured /proc
// output. The parsing is the part that can be wrong in a way nobody would
// notice — a mis-read address turns "other devices can reach this" into its
// opposite — and a test that re-implemented the parser would only agree with
// itself.
module.exports = { activate, deactivate, listeningPorts, ANY_V4, LOOPBACK_V4 };
