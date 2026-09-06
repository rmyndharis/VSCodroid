/**
 * Self-check for the Serve on Network port scan and its reachable/local split.
 *
 * The command answers one question, can another device on this Wi-Fi open
 * this?, and both ways of getting it wrong cost the user something. Calling a
 * loopback-only server reachable hands them an address that refuses the
 * connection, and calling a reachable one local-only sends them to restart a
 * server that was already fine.
 *
 * That classification used to come from /proc/net/tcp, where the bound address
 * is printed and there is nothing to infer. It now comes from two probes,
 * because an Android app process cannot read that file: the SELinux policy gives
 * untrusted_app no access at all to proc_net_tcp_udp, so both reads threw and
 * the catch written for missing IPv6 swallowed them, leaving the command
 * reporting "no dev server" on every device. Two probes recover the same
 * distinction, loopback finds it, the device's own LAN address decides whether
 * anyone else can, and inference is exactly the kind of thing that needs a
 * test, which reading /proc did not.
 *
 * scanPorts takes its connector, so nothing here opens a socket: the fake
 * records what was dialled, which also pins the probe order. Runs anywhere Node
 * runs.
 *
 *   node scripts/test-serve-network.js
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const Module = require('module');

// The extension requires 'vscode' at load time, which only exists inside the
// extension host. Same stub approach as test-bridge-relay.js; nothing in the
// functions under test touches it.
const realLoad = Module._load;
Module._load = function (request, ...rest) {
    if (request === 'vscode') return {};
    return realLoad.call(this, request, ...rest);
};

/**
 * Resolved by globbing rather than by naming a version, so that bumping the
 * extension does not silently leave this checking a directory that no longer
 * ships. A miss is an error rather than a skip.
 */
function extensionPath() {
    const dir = path.join(__dirname, '..', 'android/app/src/main/assets/extensions');
    const matches = fs
        .readdirSync(dir)
        .filter((name) => name.startsWith('vscodroid.vscodroid-serve-network-'));
    assert.strictEqual(
        matches.length,
        1,
        `expected exactly one serve-network extension directory, found ${matches.length}: ${matches}`,
    );
    return path.join(dir, matches[0], 'extension.js');
}

const ext = require(extensionPath());

/** A connector that answers from a table and records every dial, in order. */
function fakeConnector(open) {
    const dialled = [];
    const connect = async (host, port) => {
        dialled.push(`${host}:${port}`);
        return open.has(`${host}:${port}`);
    };
    return { connect, dialled };
}

const LAN = '192.168.1.50';
// The second address a phone routinely has: a VPN's tun0, or mobile data's
// rmnet, up at the same time as Wi-Fi.
const VPN = '10.8.0.2';

async function reachableWhenBothAnswer() {
    const { connect } = fakeConnector(new Set(['127.0.0.1:3000', `${LAN}:3000`]));
    const found = await ext.scanPorts([3000], [LAN], connect);
    assert.deepStrictEqual([...found], [[3000, [LAN]]], 'a port both probes reach is reachable');
}

async function localOnlyWhenLanRefuses() {
    const { connect } = fakeConnector(new Set(['127.0.0.1:3000']));
    const found = await ext.scanPorts([3000], [LAN], connect);
    assert.deepStrictEqual(
        [...found],
        [[3000, []]],
        'a port only loopback reaches is local-only, not reachable',
    );
}

/**
 * A server bound to ONE interface answers on that address and nowhere else, and
 * the verdict has to be per address.
 *
 * This is where probing a single address cost something. One probe was run
 * against whichever address os.networkInterfaces() enumerated first, and its
 * answer was then attached to every address in the list: with the VPN first, a
 * server started as `vite --host 192.168.1.50` failed the tun0 probe and the
 * port was filed under "this device only", advising the user to rebind a server
 * they had already bound correctly. With Wi-Fi first, the tun0 address was
 * offered as reachable on the strength of a probe of a different interface.
 */
async function eachAddressGetsItsOwnVerdict() {
    const { connect, dialled } = fakeConnector(new Set(['127.0.0.1:3000', `${LAN}:3000`]));
    const found = await ext.scanPorts([3000], [VPN, LAN], connect);
    assert.deepStrictEqual(
        [...found],
        [[3000, [LAN]]],
        'the address that answered is the only one reported, whatever the enumeration order',
    );
    assert.ok(
        dialled.includes(`${VPN}:3000`) && dialled.includes(`${LAN}:3000`),
        `both addresses have to be probed, only these were: ${dialled.join(', ')}`,
    );
}

async function closedPortIsAbsent() {
    const { connect } = fakeConnector(new Set());
    const found = await ext.scanPorts([3000, 5173], [LAN], connect);
    assert.strictEqual(found.size, 0, 'ports nothing answers are not reported at all');
}

/**
 * The case the loopback gate lost outright: a server bound to one LAN address
 * and nothing else.
 *
 * `vite --host 192.168.1.50` binds that address alone, so loopback refuses. The
 * scan used to end the port there and the command reported no dev server, both
 * in the list and for a port typed in by hand, about a server that was up and
 * already answering the other device.
 */
async function boundToTheLanAddressAloneIsFound() {
    const { connect } = fakeConnector(new Set([`${LAN}:5173`]));
    const found = await ext.scanPorts([5173], [LAN], connect);
    assert.deepStrictEqual(
        [...found],
        [[5173, [LAN]]],
        'a server bound to the LAN address alone is reported as reachable',
    );
}

/**
 * Every probe goes out for every port, loopback included, and the port is
 * dropped only when all of them refuse. Dialling is asserted as a set: they are
 * issued together now, so the order is the scheduler's and pinning it would fail
 * for a reason that is not a defect.
 */
async function everyAddressIsProbedEvenWhenLoopbackRefuses() {
    const { connect, dialled } = fakeConnector(new Set());
    await ext.scanPorts([3000], [VPN, LAN], connect);
    assert.deepStrictEqual(
        [...dialled].sort(),
        ['10.8.0.2:3000', '127.0.0.1:3000', '192.168.1.50:3000'],
        `a loopback refusal still ends the port before its addresses are tried: ${dialled.join(', ')}`,
    );
}

async function noLanAddressMeansLocalOnly() {
    const { connect, dialled } = fakeConnector(new Set(['127.0.0.1:8080']));
    const found = await ext.scanPorts([8080], null, connect);
    assert.deepStrictEqual([...found], [[8080, []]], 'without a LAN address nothing is reachable');
    assert.deepStrictEqual(dialled, ['127.0.0.1:8080'], 'and no second probe is attempted');
}

function candidatesMergeAndSort() {
    const merged = ext.candidatePorts(new Set([3000, 42, 9999]));
    assert.ok(merged.includes(42) && merged.includes(9999), 'remembered ports are probed');
    assert.strictEqual(
        merged.filter((p) => p === 3000).length,
        1,
        'a remembered port already in the default list is not probed twice',
    );
    assert.deepStrictEqual(merged, [...merged].sort((a, b) => a - b), 'candidates are sorted');
}

function defaultsAreSane() {
    assert.ok(ext.COMMON_DEV_PORTS.length >= 10, 'the default list is not empty or truncated');
    assert.strictEqual(
        new Set(ext.COMMON_DEV_PORTS).size,
        ext.COMMON_DEV_PORTS.length,
        'the default list has no duplicates',
    );
    for (const port of ext.COMMON_DEV_PORTS) {
        assert.ok(
            Number.isInteger(port) && port > 0 && port < 65536,
            `${port} is not a usable TCP port`,
        );
    }
}

async function main() {
    await reachableWhenBothAnswer();
    await localOnlyWhenLanRefuses();
    await eachAddressGetsItsOwnVerdict();
    await closedPortIsAbsent();
    await boundToTheLanAddressAloneIsFound();
    await everyAddressIsProbedEvenWhenLoopbackRefuses();
    await noLanAddressMeansLocalOnly();
    candidatesMergeAndSort();
    defaultsAreSane();

    say(
        `  ok     reachable/local-only split holds over ${ext.COMMON_DEV_PORTS.length} default ports, ` +
            'each address is judged on its own probe, a server bound only to the LAN ' +
            'address is found, and a port is dropped only once every probe has refused\n',
    );
}

/**
 * Writes synchronously: process.exit leaves pending stdout writes unflushed when
 * stdout is a pipe, which is what it is under CI. A check that exits 0 having
 * printed nothing reads as a check that never ran.
 */
function say(text) {
    fs.writeSync(1, text);
}

main().then(
    () => process.exit(0),
    (e) => {
        fs.writeSync(2, `${e.message}\n`);
        process.exit(1);
    },
);
