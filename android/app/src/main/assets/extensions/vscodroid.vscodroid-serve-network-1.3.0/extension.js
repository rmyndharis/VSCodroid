const vscode = require('vscode');
const net = require('net');
const os = require('os');

/**
 * Ports this command looks for, and the whole reason it has to guess.
 *
 * The first version of this file read /proc/net/tcp, which enumerates every
 * listening socket and needs no guessing at all. That is the right source and it
 * is unavailable: an Android app process runs in an SELinux domain with no access
 * to the proc_net_tcp_udp type, so both reads throw EACCES. Measured on an API 37
 * emulator by querying the policy directly, subject untrusted_app_34 against
 * proc_net_tcp_udp:file returns allowed=0, while the same subject against
 * app_data_file returns a full mask and the shell domain against the same file
 * returns a read mask. So the denial is real and specific, not an artefact of how
 * it was measured.
 *
 * Worse than being unavailable, it failed quietly: the two reads sat inside a
 * `catch { continue; }` written for devices without IPv6, so the command reported
 * "No dev server is running yet" on every device, including ones with a server
 * running and reachable.
 *
 * So: probe instead of enumerate, and accept that probing cannot find a port
 * nobody thought to list. These are the defaults of the frameworks someone is
 * most likely to be running here. Anything else is reachable through the "Enter a
 * port number" entry, which is why that entry is always offered rather than being
 * a fallback for when this list comes up empty.
 */
const COMMON_DEV_PORTS = [
    3000, // Next.js, Create React App, Express convention
    3001,
    4000, // Phoenix, some Express templates
    4200, // Angular
    4321, // Astro
    5000, // Flask, .NET
    5173, // Vite
    5174, // Vite, second instance
    8000, // Django, python -m http.server
    8080, // http-server, Spring Boot, Tomcat
    8081,
    8100, // Ionic
    8888, // Jupyter
    9000, // PHP, SonarQube
    1313, // Hugo
    1420, // Tauri
];

/** How long a probe waits before calling a port closed. */
const PROBE_TIMEOUT_MS = 300;

/**
 * Whether something accepts a TCP connection at this address and port.
 *
 * Deliberately closes the moment it connects. This is a liveness question, and
 * anything sent afterwards would reach a stranger's protocol.
 */
function canConnect(host, port, timeoutMs = PROBE_TIMEOUT_MS) {
    return new Promise((resolve) => {
        const socket = new net.Socket();
        let settled = false;
        const done = (answer) => {
            if (settled) return;
            settled = true;
            socket.destroy();
            resolve(answer);
        };
        socket.setTimeout(timeoutMs);
        socket.once('connect', () => done(true));
        socket.once('timeout', () => done(false));
        socket.once('error', () => done(false));
        socket.connect(port, host);
    });
}

/**
 * Which of [ports] are listening, and from which of this device's addresses each
 * of them answers.
 *
 * Two kinds of probe, because one cannot tell the difference and the difference
 * is the point of the command: loopback says the server is only reachable here,
 * the device's own addresses say another device can reach it. A server bound to
 * 127.0.0.1 answers the first and refuses the second, which is exactly the
 * distinction reading /proc used to give for free.
 *
 * Neither is a gate, and loopback used to be one. It was asked first and a
 * refusal ended the port there, which reads as "is anything listening at all"
 * and is not what it answers: `vite --host 192.168.1.50`, the invocation Vite
 * documents for binding a single address and the one this command's own advice
 * leads to, binds that address alone, so loopback refuses and the port was
 * dropped before any address was tried. The quick pick then offered no entry for
 * it and typing the port in answered "Nothing is listening on port 5173" about a
 * server that was up and already reachable from the other device.
 *
 * Asking everything at once costs less wall clock, not more: the loopback probe
 * and the address probes used to be sequential, so a scan could take two
 * PROBE_TIMEOUT_MS and now takes one. What rises is the number of sockets open
 * at once, and every one of them is dialled at this device.
 *
 * Every address, not the first one. A phone routinely has more than one: a VPN
 * puts up tun0 and mobile data puts up rmnet beside wlan0, and this probed
 * whichever `os.networkInterfaces()` happened to enumerate first and then
 * attached that one verdict to every address it listed. A server bound to one
 * interface -- `vite --host 192.168.1.5`, which is advice this command itself
 * gives -- failed a probe of the other one and was filed under "this device
 * only", telling the user to do again the thing they had already done.
 *
 * The probes dial this device's own addresses, so nothing leaves the machine and
 * no network permission is needed. They do traverse the non-loopback interface,
 * which is what makes the answer meaningful.
 *
 * Returns a Map of port to the addresses that answered, which is empty for a
 * port only loopback reaches. A port nothing answered at all is absent, which is
 * a different thing from present with an empty list.
 */
async function scanPorts(ports, lanAddresses, connect = canConnect) {
    const addresses = lanAddresses || [];
    const results = await Promise.all(
        ports.map(async (port) => {
            const [loopback, ...answers] = await Promise.all([
                connect('127.0.0.1', port),
                ...addresses.map(async (address) => ((await connect(address, port)) ? address : null)),
            ]);
            const answered = answers.filter(Boolean);
            // Absent only when nothing anywhere answered. An empty list is not
            // the same verdict: it is a port loopback reached and no address
            // did, which is the "this device only" entry the quick pick shows.
            if (!loopback && answered.length === 0) return null;
            return [port, answered];
        }),
    );
    return new Map(results.filter(Boolean));
}

/**
 * The addresses another device on this network could dial.
 *
 * Wi-Fi first, because "this network" is what the command is called and what the
 * other device is on. The enumeration order is the kernel's, so without this the
 * address offered first, and the one the manual path copies, is whichever
 * interface came up first -- a VPN's or mobile data's as readily as Wi-Fi's.
 */
function networkAddresses() {
    const addresses = [];
    for (const [name, entries] of Object.entries(os.networkInterfaces())) {
        for (const entry of entries || []) {
            if (entry.family !== 'IPv4' || entry.internal) continue;
            addresses.push({ name, address: entry.address });
        }
    }
    return addresses.sort((a, b) => Number(b.name.startsWith('wlan')) - Number(a.name.startsWith('wlan')));
}

/** Ports to probe: the common list, plus anything the user has asked about before. */
function candidatePorts(extra) {
    const all = new Set(COMMON_DEV_PORTS);
    for (const port of extra) all.add(port);
    return [...all].sort((a, b) => a - b);
}

async function askForPort() {
    const answer = await vscode.window.showInputBox({
        title: 'Serve on Network',
        prompt: 'Which port is your server on?',
        placeHolder: '3000',
        validateInput: (value) => {
            const port = Number(value);
            if (!Number.isInteger(port) || port < 1 || port > 65535) {
                return 'Enter a port number between 1 and 65535.';
            }
            return null;
        },
    });
    return answer ? Number(answer) : null;
}

function activate(context) {
    // Ports the user has typed in this session. A server on an unusual port is
    // usually asked about more than once, and re-typing it every time would be
    // the sort of small friction that makes a command not worth running.
    const remembered = new Set();

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

        const addressList = addresses.map((entry) => entry.address);
        const interfaceOf = new Map(addresses.map((entry) => [entry.address, entry.name]));

        const found = await vscode.window.withProgress(
            { location: vscode.ProgressLocation.Window, title: 'Looking for dev servers…' },
            () => scanPorts(candidatePorts(remembered), addressList),
        );

        const reachable = [];
        const localOnly = [];
        for (const [port, answeredFrom] of found) {
            if (port === editorPort) continue;
            if (answeredFrom.length > 0) reachable.push([port, answeredFrom]);
            else localOnly.push(port);
        }
        reachable.sort(([a], [b]) => a - b);
        localOnly.sort((a, b) => a - b);

        const items = [];
        // One entry per address that actually answered, rather than one per
        // address this device happens to have. Loopback is not per-app isolated
        // on Android, so a listening port is not provably the user's own server
        // either; the wording states what was measured and leaves the ownership
        // to them.
        for (const [port, answeredFrom] of reachable) {
            for (const address of answeredFrom) {
                items.push({
                    label: `$(globe) http://${address}:${port}`,
                    description: interfaceOf.get(address),
                    detail: 'Answers from other devices on this network, select to copy',
                    url: `http://${address}:${port}`,
                });
            }
        }
        for (const port of localOnly) {
            items.push({
                label: `$(circle-slash) port ${port}, this device only`,
                detail:
                    'Answers on localhost alone, so other devices cannot reach it. If this is ' +
                    'your server, restart it bound to every interface: Vite `--host 0.0.0.0`, ' +
                    'Next.js `-H 0.0.0.0`, Node `server.listen(port, "0.0.0.0")`, ' +
                    'Python `--bind 0.0.0.0`.',
                url: null,
            });
        }

        // Always last, always present. Probing a fixed list cannot enumerate, and
        // an empty result means "not on the list I checked" rather than "nothing
        // is running", so the way out has to be offered even when something was
        // found, not only when nothing was.
        items.push({
            label: '$(edit) Enter a port number…',
            detail:
                items.length > 0
                    ? 'Your server is on a port this command does not check by default'
                    : `Checked ${COMMON_DEV_PORTS.length} common dev-server ports and found nothing listening`,
            ask: true,
            url: null,
        });

        const picked = await vscode.window.showQuickPick(items, {
            title: 'Serve on Network',
            placeHolder:
                reachable.length > 0
                    ? 'Pick an address to copy, then open it on the other device'
                    : 'Nothing reachable from other devices was found',
        });
        if (!picked) return;

        if (picked.ask) {
            const port = await askForPort();
            if (port === null) return;
            remembered.add(port);
            // The same scan, not a second copy of the same probes against one
            // address: this path had the identical single-address defect, and a
            // port typed in by hand is exactly where a selectively bound server
            // turns up.
            const typed = await scanPorts([port], addressList);
            if (!typed.has(port)) {
                vscode.window.showInformationMessage(
                    `Nothing is listening on port ${port}. Start the server, then run this again.`,
                );
                return;
            }
            const answeredFrom = typed.get(port);
            if (answeredFrom.length === 0) {
                vscode.window.showInformationMessage(
                    `Port ${port} answers this device only. Restart the server bound to 0.0.0.0 and run this again.`,
                );
                return;
            }
            const url = `http://${answeredFrom[0]}:${port}`;
            await vscode.env.clipboard.writeText(url);
            vscode.window.showInformationMessage(`Copied ${url}, open it on the other device.`);
            return;
        }

        if (!picked.url) {
            vscode.window.showInformationMessage(
                'That server only answers this device. Restart it bound to 0.0.0.0 and run this again.',
            );
            return;
        }

        await vscode.env.clipboard.writeText(picked.url);
        vscode.window.showInformationMessage(`Copied ${picked.url}, open it on the other device.`);
    });

    context.subscriptions.push(command);
}

function deactivate() {}

// scanPorts takes its connector so a test can drive it without opening sockets.
// The classification is the part that can be wrong in a way nobody would notice:
// calling a loopback-only server "reachable from other devices" sends someone to
// an address that refuses them, and the opposite hides a server that works. Which
// of this device's addresses answered is the same question one interface further
// on, and has the same property.
module.exports = { activate, deactivate, scanPorts, candidatePorts, COMMON_DEV_PORTS };
