'use strict';

const assert = require('node:assert/strict');
const { EventEmitter, once } = require('node:events');
const fs = require('node:fs');
const { stripTypeScriptTypes } = require('node:module');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const { performance } = require('node:perf_hooks');
const vm = require('node:vm');
const { Worker } = require('node:worker_threads');
const { applyFixturePatch } = require('./lib/apply-fixture-patch');

if (typeof stripTypeScriptTypes !== 'function') {
	console.error(
		`${path.basename(__filename)}: needs node:module.stripTypeScriptTypes, `
		+ `which Node 22.13+ or 23.2+ has (this is ${process.version}). CI runs Node 24.`,
	);
	process.exit(1);
}

const root = path.join(__dirname, '..');
const patchesDir = process.env.VSCODROID_PATCHES_DIR
	? path.resolve(process.env.VSCODROID_PATCHES_DIR)
	: path.join(root, 'patches');

const UPSTREAM_BOOTSTRAP_FORK = [
	'/*---------------------------------------------------------------------------------------------',
	' *  Copyright (c) Microsoft Corporation. All rights reserved.',
	' *  Licensed under the MIT License. See License.txt in the project root for license information.',
	' *--------------------------------------------------------------------------------------------*/',
	'',
	"import * as performance from './vs/base/common/performance.js';",
	"import { removeGlobalNodeJsModuleLookupPaths, devInjectNodeModuleLookupPath } from './bootstrap-node.js';",
	"import { bootstrapESM } from './bootstrap-esm.js';",
	"import { enableNodeCompileCache, getNodeCompileCacheKindForUtilityProcess } from './vs/base/node/nodeCompileCache.js';",
	'',
	"performance.mark('code/fork/start');",
	'',
	'',
].join('\n');

const UPSTREAM_EXTENSION_HOST_CONNECTION = [
	'/*---------------------------------------------------------------------------------------------',
	' *  Copyright (c) Microsoft Corporation. All rights reserved.',
	' *  Licensed under the MIT License. See License.txt in the project root for license information.',
	' *--------------------------------------------------------------------------------------------*/',
	'',
	"import * as cp from 'child_process';",
	"import * as net from 'net';",
	"import { VSBuffer } from '../../base/common/buffer.js';",
	"import { Emitter, Event } from '../../base/common/event.js';",
	"import { Disposable, DisposableStore, toDisposable } from '../../base/common/lifecycle.js';",
	"import { FileAccess } from '../../base/common/network.js';",
	"import { delimiter, join } from '../../base/common/path.js';",
	"import { IProcessEnvironment, isWindows } from '../../base/common/platform.js';",
	"import { removeDangerousEnvVariables } from '../../base/common/processes.js';",
	"import { createRandomIPCHandle, NodeSocket, WebSocketNodeSocket } from '../../base/parts/ipc/node/ipc.net.js';",
	"import { IConfigurationService } from '../../platform/configuration/common/configuration.js';",
	"import { ILogService } from '../../platform/log/common/log.js';",
	"import { IRemoteExtensionHostStartParams } from '../../platform/remote/common/remoteAgentConnection.js';",
	"import { getResolvedShellEnv } from '../../platform/shell/node/shellEnv.js';",
	"import { IExtensionHostStatusService } from './extensionHostStatusService.js';",
	"import { getNLSConfiguration } from './remoteLanguagePacks.js';",
	"import { IServerEnvironmentService } from './serverEnvironmentService.js';",
	"import { IPCExtHostConnection, SocketExtHostConnection, writeExtHostConnection } from '../../workbench/services/extensions/common/extensionHostEnv.js';",
	"import { IExtHostReadyMessage, IExtHostReduceGraceTimeMessage, IExtHostSocketMessage } from '../../workbench/services/extensions/common/extensionHostProtocol.js';",
	'',
	'export async function buildUserEnvironment(startParamsEnv: { [key: string]: string | null } = {}, withUserShellEnvironment: boolean, language: string, environmentService: IServerEnvironmentService, logService: ILogService, configurationService: IConfigurationService): Promise<IProcessEnvironment> {',
	'\tconst nlsConfig = await getNLSConfiguration(language, environmentService.userDataPath);',
	'',
	'\tlet userShellEnv: typeof process.env = {};',
	'\tif (withUserShellEnvironment) {',
	'\t\ttry {',
	'\t\t\tuserShellEnv = await getResolvedShellEnv(configurationService, logService, environmentService.args, process.env);',
	'\t\t} catch (error) {',
	"\t\t\tlogService.error('ExtensionHostConnection#buildUserEnvironment resolving shell environment failed', error);",
	'\t\t}',
	'\t}',
	'',
	'\tconst processEnv = process.env;',
	'',
	'\tconst env: IProcessEnvironment = {',
	'\t\t...processEnv,',
	'\t\t...userShellEnv,',
	'\t\t...startParamsEnv,',
	"\t\tVSCODE_ESM_ENTRYPOINT: 'vs/workbench/api/node/extensionHostProcess',",
	"\t\tVSCODE_HANDLES_UNCAUGHT_ERRORS: 'true',",
	'\t\tVSCODE_NLS_CONFIG: JSON.stringify(nlsConfig)',
	'\t};',
	'',
	"\tconst binFolder = environmentService.isBuilt ? join(environmentService.appRoot, 'bin') : join(environmentService.appRoot, 'resources', 'server', 'bin-dev');",
	"\tconst remoteCliBinFolder = join(binFolder, 'remote-cli'); // contains the `code` command that can talk to the remote server",
	'',
	"\tlet PATH = readCaseInsensitive(env, 'PATH');",
	'\tif (PATH) {',
	'\t\tPATH = remoteCliBinFolder + delimiter + PATH;',
	'\t} else {',
	'\t\tPATH = remoteCliBinFolder;',
	'\t}',
	"\tsetCaseInsensitive(env, 'PATH', PATH);",
	'',
	"\tif (!environmentService.args['without-browser-env-var']) {",
	"\t\tenv.BROWSER = join(binFolder, 'helpers', isWindows ? 'browser.cmd' : 'browser.sh'); // a command that opens a browser on the local machine",
	'\t}',
	'',
	'\tenv.VSCODE_RECONNECTION_GRACE_TIME = String(environmentService.reconnectionGraceTime);',
	'\tlogService.trace(`[reconnection-grace-time] Setting VSCODE_RECONNECTION_GRACE_TIME env var for extension host: ${environmentService.reconnectionGraceTime}ms (${Math.floor(environmentService.reconnectionGraceTime / 1000)}s)`);',
	'',
	'\tremoveNulls(env);',
	'\treturn env;',
	'}',
	'',
	'class ConnectionData {',
	'\tconstructor(',
	'\t\tpublic readonly socket: NodeSocket | WebSocketNodeSocket,',
	'\t\tpublic readonly initialDataChunk: VSBuffer',
	'\t) { }',
	'',
	'\tpublic socketDrain(): Promise<void> {',
	'\t\treturn this.socket.drain();',
	'\t}',
	'',
	'\tpublic toIExtHostSocketMessage(): IExtHostSocketMessage {',
	'',
	'\t\tlet skipWebSocketFrames: boolean;',
	'\t\tlet permessageDeflate: boolean;',
	'\t\tlet inflateBytes: VSBuffer;',
	'',
	'\t\tif (this.socket instanceof NodeSocket) {',
	'\t\t\tskipWebSocketFrames = true;',
	'\t\t\tpermessageDeflate = false;',
	'\t\t\tinflateBytes = VSBuffer.alloc(0);',
	'\t\t} else {',
	'\t\t\tskipWebSocketFrames = false;',
	'\t\t\tpermessageDeflate = this.socket.permessageDeflate;',
	'\t\t\tinflateBytes = this.socket.recordedInflateBytes;',
	'\t\t\tthis.socket.setRecordInflateBytes(false);',
	'\t\t}',
	'',
	'\t\treturn {',
	"\t\t\ttype: 'VSCODE_EXTHOST_IPC_SOCKET',",
	"\t\t\tinitialDataChunk: (<Buffer>this.initialDataChunk.buffer).toString('base64'),",
	'\t\t\tskipWebSocketFrames: skipWebSocketFrames,',
	'\t\t\tpermessageDeflate: permessageDeflate,',
	"\t\t\tinflateBytes: (<Buffer>inflateBytes.buffer).toString('base64'),",
	'\t\t};',
	'\t}',
	'}',
	'',
	'export class ExtensionHostConnection extends Disposable {',
	'',
	'\tprivate _onClose = this._register(new Emitter<void>());',
	'\treadonly onClose: Event<void> = this._onClose.event;',
	'',
	'\tprivate readonly _canSendSocket: boolean;',
	'\tprivate _disposed: boolean;',
	'\tprivate _remoteAddress: string;',
	'\tprivate _extensionHostProcess: cp.ChildProcess | null;',
	'\tprivate _connectionData: ConnectionData | null;',
	'',
	'\tconstructor(',
	'\t\tprivate readonly _reconnectionToken: string,',
	'\t\tremoteAddress: string,',
	'\t\tsocket: NodeSocket | WebSocketNodeSocket,',
	'\t\tinitialDataChunk: VSBuffer,',
	'\t\t@IServerEnvironmentService private readonly _environmentService: IServerEnvironmentService,',
	'\t\t@ILogService private readonly _logService: ILogService,',
	'\t\t@IExtensionHostStatusService private readonly _extensionHostStatusService: IExtensionHostStatusService,',
	'\t\t@IConfigurationService private readonly _configurationService: IConfigurationService',
	'\t) {',
	'\t\tsuper();',
	"\t\tthis._canSendSocket = (!isWindows || !this._environmentService.args['socket-path']);",
	'\t\tthis._disposed = false;',
	'\t\tthis._remoteAddress = remoteAddress;',
	'\t\tthis._extensionHostProcess = null;',
	'\t\tthis._connectionData = new ConnectionData(socket, initialDataChunk);',
	'\t\tif (!this._canSendSocket && socket instanceof WebSocketNodeSocket) {',
	'\t\t\tsocket.setRecordInflateBytes(false);',
	'\t\t}',
	'',
	'\t\tthis._log(`New connection established.`);',
	'\t}',
	'',
	'\toverride dispose(): void {',
	'\t\tthis._cleanResources();',
	'\t\tsuper.dispose();',
	'\t}',
	'',
	'\tprivate get _logPrefix(): string {',
	'\t\treturn `[${this._remoteAddress}][${this._reconnectionToken.substr(0, 8)}][ExtensionHostConnection] `;',
	'\t}',
	'',
	'\tprivate _log(_str: string): void {',
	'\t\tthis._logService.info(`${this._logPrefix}${_str}`);',
	'\t}',
	'',
	'\tprivate _logError(_str: string): void {',
	'\t\tthis._logService.error(`${this._logPrefix}${_str}`);',
	'\t}',
	'',
	'\tprivate async _pipeSockets(extHostSocket: net.Socket, connectionData: ConnectionData): Promise<void> {',
	'',
	'\t\tconst disposables = new DisposableStore();',
	'\t\tdisposables.add(connectionData.socket);',
	'\t\tdisposables.add(toDisposable(() => {',
	'\t\t\tif (!extHostSocket.destroyed && !extHostSocket.writableEnded) {',
	'\t\t\t\textHostSocket.end();',
	'\t\t\t}',
	'\t\t}));',
	'',
	'\t\tconst stopAndCleanup = () => {',
	'\t\t\tdisposables.dispose();',
	'\t\t};',
	'',
	'\t\tdisposables.add(connectionData.socket.onEnd(stopAndCleanup));',
	'\t\tdisposables.add(connectionData.socket.onClose(stopAndCleanup));',
	'',
	"\t\tdisposables.add(Event.fromNodeEventEmitter<void>(extHostSocket, 'end')(stopAndCleanup));",
	"\t\tdisposables.add(Event.fromNodeEventEmitter<void>(extHostSocket, 'close')(stopAndCleanup));",
	"\t\tdisposables.add(Event.fromNodeEventEmitter<void>(extHostSocket, 'error')(stopAndCleanup));",
	'',
	'\t\tdisposables.add(connectionData.socket.onData((e) => extHostSocket.write(e.buffer)));',
	"\t\tdisposables.add(Event.fromNodeEventEmitter<Buffer>(extHostSocket, 'data')((e) => {",
	'\t\t\tconnectionData.socket.write(VSBuffer.wrap(e));',
	'\t\t}));',
	'',
	'\t\tif (connectionData.initialDataChunk.byteLength > 0) {',
	'\t\t\textHostSocket.write(connectionData.initialDataChunk.buffer);',
	'\t\t}',
	'\t}',
	'',
	'\tprivate async _sendSocketToExtensionHost(extensionHostProcess: cp.ChildProcess, connectionData: ConnectionData): Promise<void> {',
	'\t\t// Make sure all outstanding writes have been drained before sending the socket',
	'\t\tawait connectionData.socketDrain();',
	'\t\tconst msg = connectionData.toIExtHostSocketMessage();',
	'\t\tlet socket: net.Socket;',
	'\t\tif (connectionData.socket instanceof NodeSocket) {',
	'\t\t\tsocket = connectionData.socket.socket;',
	'\t\t} else {',
	'\t\t\tsocket = connectionData.socket.socket.socket;',
	'\t\t}',
	'\t\textensionHostProcess.send(msg, socket);',
	'\t}',
	'',
	'\tpublic shortenReconnectionGraceTimeIfNecessary(): void {',
	'\t\tif (!this._extensionHostProcess) {',
	'\t\t\treturn;',
	'\t\t}',
	'\t\tconst msg: IExtHostReduceGraceTimeMessage = {',
	"\t\t\ttype: 'VSCODE_EXTHOST_IPC_REDUCE_GRACE_TIME'",
	'\t\t};',
	'\t\tthis._extensionHostProcess.send(msg);',
	'\t}',
	'',
	'\tpublic acceptReconnection(remoteAddress: string, _socket: NodeSocket | WebSocketNodeSocket, initialDataChunk: VSBuffer): void {',
	'\t\tthis._remoteAddress = remoteAddress;',
	'\t\tthis._log(`The client has reconnected.`);',
	'\t\tif (!this._canSendSocket && _socket instanceof WebSocketNodeSocket) {',
	'\t\t\t_socket.setRecordInflateBytes(false);',
	'\t\t}',
	'\t\tconst connectionData = new ConnectionData(_socket, initialDataChunk);',
	'',
	'\t\tif (!this._extensionHostProcess) {',
	"\t\t\t// The extension host didn't even start up yet",
	'\t\t\tthis._connectionData = connectionData;',
	'\t\t\treturn;',
	'\t\t}',
	'',
	'\t\tthis._sendSocketToExtensionHost(this._extensionHostProcess, connectionData);',
	'\t}',
	'',
	'\tprivate _cleanResources(): void {',
	'\t\tif (this._disposed) {',
	'\t\t\t// already called',
	'\t\t\treturn;',
	'\t\t}',
	'\t\tthis._disposed = true;',
	'\t\tif (this._connectionData) {',
	'\t\t\tthis._connectionData.socket.end();',
	'\t\t\tthis._connectionData = null;',
	'\t\t}',
	'\t\tif (this._extensionHostProcess) {',
	'\t\t\tthis._extensionHostProcess.kill();',
	'\t\t\tthis._extensionHostProcess = null;',
	'\t\t}',
	'\t\tthis._onClose.fire(undefined);',
	'\t}',
	'',
	'\tpublic async start(startParams: IRemoteExtensionHostStartParams): Promise<void> {',
	'\t\ttry {',
	'\t\t\tlet execArgv: string[] = process.execArgv ? process.execArgv.filter(a => !/^--inspect(-brk)?=/.test(a)) : [];',
	'\t\t\t// eslint-disable-next-line local/code-no-any-casts, @typescript-eslint/no-explicit-any',
	'\t\t\tif (startParams.port && !(<any>process).pkg) {',
	'\t\t\t\texecArgv = [',
	"\t\t\t\t\t`--inspect${startParams.break ? '-brk' : ''}=${startParams.port}`,",
	"\t\t\t\t\t'--experimental-network-inspection'",
	'\t\t\t\t];',
	'\t\t\t}',
	'',
	'\t\t\tthis._log(`Starting extension host process...`);',
	'',
	'\t\t\tconst env = await buildUserEnvironment(startParams.env, true, startParams.language, this._environmentService, this._logService, this._configurationService);',
	'\t\t\tremoveDangerousEnvVariables(env);',
	'',
	'\t\t\tlet extHostNamedPipeServer: net.Server | null;',
	'',
	'\t\t\tif (this._canSendSocket) {',
	'\t\t\t\twriteExtHostConnection(new SocketExtHostConnection(), env);',
	'\t\t\t\textHostNamedPipeServer = null;',
	'\t\t\t} else {',
	'\t\t\t\tconst { namedPipeServer, pipeName } = await this._listenOnPipe();',
	'\t\t\t\twriteExtHostConnection(new IPCExtHostConnection(pipeName), env);',
	'\t\t\t\textHostNamedPipeServer = namedPipeServer;',
	'\t\t\t}',
	'',
	'\t\t\tconst opts = {',
	'\t\t\t\tenv,',
	'\t\t\t\texecArgv,',
	'\t\t\t\tsilent: true',
	'\t\t\t};',
	'',
	'\t\t\t// Refs https://github.com/microsoft/vscode/issues/189805',
	"\t\t\topts.execArgv.unshift('--dns-result-order=ipv4first');",
	'',
	'\t\t\t// Run Extension Host as fork of current process',
	"\t\t\tconst args = ['--type=extensionHost', `--transformURIs`];",
	"\t\t\tconst useHostProxy = this._environmentService.args['use-host-proxy'];",
	"\t\t\targs.push(`--useHostProxy=${useHostProxy ? 'true' : 'false'}`);",
	"\t\t\tif (this._configurationService.getValue<boolean>('extensions.supportNodeGlobalNavigator')) {",
	"\t\t\t\targs.push('--supportGlobalNavigator');",
	'\t\t\t}',
	"\t\t\tthis._extensionHostProcess = cp.fork(FileAccess.asFileUri('bootstrap-fork').fsPath, args, opts);",
	'\t\t\tconst pid = this._extensionHostProcess.pid;',
	'\t\t\tthis._log(`<${pid}> Launched Extension Host Process.`);',
	'',
	'\t\t\t// Catch all output coming from the extension host process',
	"\t\t\tthis._extensionHostProcess.stdout!.setEncoding('utf8');",
	"\t\t\tthis._extensionHostProcess.stderr!.setEncoding('utf8');",
	"\t\t\tconst onStdout = Event.fromNodeEventEmitter<string>(this._extensionHostProcess.stdout!, 'data');",
	"\t\t\tconst onStderr = Event.fromNodeEventEmitter<string>(this._extensionHostProcess.stderr!, 'data');",
	'\t\t\tthis._register(onStdout((e) => this._log(`<${pid}> ${e}`)));',
	'\t\t\tthis._register(onStderr((e) => this._log(`<${pid}><stderr> ${e}`)));',
	'',
	'\t\t\t// Lifecycle',
	"\t\t\tthis._extensionHostProcess.on('error', (err) => {",
	'\t\t\t\tthis._logError(`<${pid}> Extension Host Process had an error`);',
	'\t\t\t\tthis._logService.error(err);',
	'\t\t\t\tthis._cleanResources();',
	'\t\t\t});',
	'',
	"\t\t\tthis._extensionHostProcess.on('exit', (code: number, signal: string) => {",
	'\t\t\t\tthis._extensionHostStatusService.setExitInfo(this._reconnectionToken, { code, signal });',
	'\t\t\t\tthis._log(`<${pid}> Extension Host Process exited with code: ${code}, signal: ${signal}.`);',
	'\t\t\t\tthis._cleanResources();',
	'\t\t\t});',
	'',
	'\t\t\tif (extHostNamedPipeServer) {',
	"\t\t\t\textHostNamedPipeServer.on('connection', (socket) => {",
	'\t\t\t\t\textHostNamedPipeServer.close();',
	'\t\t\t\t\tthis._pipeSockets(socket, this._connectionData!);',
	'\t\t\t\t});',
	'\t\t\t} else {',
	'\t\t\t\tconst messageListener = (msg: IExtHostReadyMessage) => {',
	"\t\t\t\t\tif (msg.type === 'VSCODE_EXTHOST_IPC_READY') {",
	"\t\t\t\t\t\tthis._extensionHostProcess!.removeListener('message', messageListener);",
	'\t\t\t\t\t\tthis._sendSocketToExtensionHost(this._extensionHostProcess!, this._connectionData!);',
	'\t\t\t\t\t\tthis._connectionData = null;',
	'\t\t\t\t\t}',
	'\t\t\t\t};',
	"\t\t\t\tthis._extensionHostProcess.on('message', messageListener);",
	'\t\t\t}',
	'',
	'\t\t} catch (error) {',
	'\t\t\tthis._logError(`Failed to start extension host process`);',
	'\t\t\tthis._logService.error(error);',
	'\t\t\tthis._cleanResources();',
	'\t\t}',
	'\t}',
	'',
	'\tprivate _listenOnPipe(): Promise<{ pipeName: string; namedPipeServer: net.Server }> {',
	'\t\treturn new Promise<{ pipeName: string; namedPipeServer: net.Server }>((resolve, reject) => {',
	'\t\t\tconst pipeName = createRandomIPCHandle();',
	'',
	'\t\t\tconst namedPipeServer = net.createServer();',
	"\t\t\tnamedPipeServer.on('error', reject);",
	'\t\t\tnamedPipeServer.listen(pipeName, () => {',
	"\t\t\t\tnamedPipeServer?.removeListener('error', reject);",
	'\t\t\t\tresolve({ pipeName, namedPipeServer });',
	'\t\t\t});',
	'\t\t});',
	'\t}',
	'}',
	'',
	'function readCaseInsensitive(env: { [key: string]: string | undefined }, key: string): string | undefined {',
	'\tconst pathKeys = Object.keys(env).filter(k => k.toLowerCase() === key.toLowerCase());',
	'\tconst pathKey = pathKeys.length > 0 ? pathKeys[0] : key;',
	'\treturn env[pathKey];',
	'}',
	'',
	'function setCaseInsensitive(env: { [key: string]: unknown }, key: string, value: string): void {',
	'\tconst pathKeys = Object.keys(env).filter(k => k.toLowerCase() === key.toLowerCase());',
	'\tconst pathKey = pathKeys.length > 0 ? pathKeys[0] : key;',
	'\tenv[pathKey] = value;',
	'}',
	'',
	'function removeNulls(env: { [key: string]: unknown | null }): void {',
	"\t// Don't delete while iterating the object itself",
	'\tfor (const key of Object.keys(env)) {',
	'\t\tif (env[key] === null) {',
	'\t\t\tdelete env[key];',
	'\t\t}',
	'\t}',
	'}',
	'',
].join('\n');

async function withTimeout(promise, ms, label, onTimeout) {
	let timer;
	try {
		return await Promise.race([
			promise,
			new Promise((_resolve, reject) => {
				timer = setTimeout(() => {
					if (onTimeout) onTimeout();
					reject(new Error(`timed out after ${ms}ms: ${label}`));
				}, ms);
			}),
		]);
	} finally {
		clearTimeout(timer);
	}
}

function buildBootstrapWorkerScript(temporary, patchedBootstrapSource) {
	const transformed = stripTypeScriptTypes(
		patchedBootstrapSource
			.replace(/^import \* as performance from .*$/gm, 'const performance = { mark() {} };')
			.replace(/^import \{ removeGlobalNodeJsModuleLookupPaths.*$/gm, '')
			.replace(/^import \{ bootstrapESM.*$/gm, '')
			.replace(/^import \{ enableNodeCompileCache.*$/gm, '')
			.replace(/^import \{ isMainThread, parentPort \} from 'worker_threads';$/gm,
				"const { isMainThread, parentPort } = require('node:worker_threads');"),
		{ mode: 'transform', sourceUrl: 'bootstrap-fork.ts' },
	);

	const workerScript = [
		"'use strict';",
		"const fs = require('node:fs');",
		"const { workerData } = require('node:worker_threads');",
		transformed,
		'const view = workerData && workerData.sab ? new Int32Array(workerData.sab) : null;',
		'const exitFile = process.env.TEST_EXIT_FILE || (workerData && workerData.exitFile) || null;',
		"process.on('disconnect', () => {",
		'\tif (view) {',
		'\t\tAtomics.add(view, 0, 1);',
		'\t\tAtomics.store(view, 1, process.connected === false ? 1 : 2);',
		'\t}',
		"\tprocess.send({ type: 'sync-teardown-1', connected: process.connected });",
		'\tif (workerData && workerData.reentrant) {',
		'\t\tprocess.disconnect();',
		'\t}',
		'});',
		"process.on('disconnect', () => {",
		'\tif (view) Atomics.add(view, 3, 1);',
		"\tprocess.send({ type: 'sync-teardown-2', connected: process.connected });",
		'});',
		"process.once('exit', (code) => {",
		'\tif (view) {',
		'\t\tAtomics.store(view, 4, 1);',
		'\t\tAtomics.store(view, 5, code);',
		'\t}',
		'\tif (exitFile) {',
		'\t\tfs.writeFileSync(exitFile, `exit:${code}`, "utf8");',
		'\t}',
		'});',
		"process.on('message', (msg) => {",
		"\tif (msg && msg.type === 'self-disconnect') {",
		'\t\tprocess.disconnect();',
		'\t}',
		'});',
		"process.send({ type: 'ready' });",
	].join('\n');

	const scriptPath = path.join(temporary, 'bootstrap-worker.cjs');
	fs.writeFileSync(scriptPath, workerScript, 'utf8');
	return scriptPath;
}

function loadWorkerAsChildProcess(patchedSource) {
	const source = stripTypeScriptTypes(
		patchedSource
			.replace(/^import \{ ChildProcess, ForkOptions \} from 'child_process';$/gm, '')
			.replace(/^import \{ EventEmitter \} from 'events';$/gm, '')
			.replace(/^import \{ Worker \} from 'worker_threads';$/gm, '')
			.replace(/^export /gm, ''),
		{ mode: 'transform', sourceUrl: 'workerAsChildProcess.ts' },
	);

	const activeTimers = new Set();
	const trackedSetTimeout = (fn, delay, ...args) => {
		const handle = setTimeout(() => {
			activeTimers.delete(handle);
			fn(...args);
		}, delay);
		activeTimers.add(handle);
		return handle;
	};
	const trackedClearTimeout = (handle) => {
		activeTimers.delete(handle);
		clearTimeout(handle);
	};

	const context = {
		EventEmitter,
		Worker,
		setTimeout: trackedSetTimeout,
		clearTimeout: trackedClearTimeout,
		parseInt,
	};
	vm.runInNewContext(
		source + '\nglobalThis.WorkerAsChildProcess = WorkerAsChildProcess; globalThis.forkOrWorker = forkOrWorker;',
		context,
	);
	return {
		WorkerAsChildProcess: context.WorkerAsChildProcess,
		activeTimers,
	};
}

function loadPatchedExtensionHostConnection(temporary, patch0004, nextPipePath) {
	const rel = 'src/vs/server/node/extensionHostConnection.ts';
	const file = path.join(temporary, rel);
	fs.mkdirSync(path.dirname(file), { recursive: true });
	fs.writeFileSync(file, UPSTREAM_EXTENSION_HOST_CONNECTION, 'utf8');
	applyFixturePatch(temporary, patch0004, { include: [rel], changes: [rel] });

	let source = fs.readFileSync(file, 'utf8');
	source = source.replace(
		/^([\t ]*)@(IServerEnvironmentService|ILogService|IExtensionHostStatusService|IConfigurationService)(?=[\t ]+private[\t ]+readonly\b)/gm,
		(_match, indent) => indent,
	);
	source = source.replace(/^export /gm, '');

	class DisposableStore {
		constructor() {
			this._items = new Set();
			this._disposed = false;
		}
		add(item) {
			if (this._disposed) {
				item.dispose();
			} else {
				this._items.add(item);
			}
			return item;
		}
		clear() {
			for (const item of [...this._items]) {
				item.dispose();
			}
			this._items.clear();
		}
		dispose() {
			if (this._disposed) {
				return;
			}
			this._disposed = true;
			this.clear();
		}
	}

	class Disposable {
		constructor() {
			this._store = new DisposableStore();
		}
		_register(item) {
			return this._store.add(item);
		}
		dispose() {
			this._store.dispose();
		}
	}

	class Emitter {
		constructor() {
			this._listeners = new Set();
			this.event = (listener) => {
				this._listeners.add(listener);
				return { dispose: () => this._listeners.delete(listener) };
			};
		}
		fire(value) {
			for (const listener of [...this._listeners]) {
				listener(value);
			}
		}
		dispose() {
			this._listeners.clear();
		}
	}

	class FakeClientSocket {
		constructor() {
			this.dataEmitter = new Emitter();
			this.endEmitter = new Emitter();
			this.closeEmitter = new Emitter();
			this.onData = this.dataEmitter.event;
			this.onEnd = this.endEmitter.event;
			this.onClose = this.closeEmitter.event;
			this.received = [];
			this.ended = false;
			this.disposed = false;
		}
		write(vsBuf) {
			this.received.push(Buffer.from(vsBuf.buffer));
			this.onWritten?.();
		}
		end() {
			this.ended = true;
		}
		dispose() {
			this.disposed = true;
		}
		drain() {
			return Promise.resolve();
		}
	}

	class FakeWebSocket extends FakeClientSocket {
		setRecordInflateBytes() {}
	}

	const createdServers = [];
	const realCreateServer = net.createServer.bind(net);
	const wrappedNet = {
		...net,
		createServer(...args) {
			const srv = realCreateServer(...args);
			createdServers.push(srv);
			return srv;
		},
	};

	const spawnedWorkers = [];
	function forkOrWorker(useWorker) {
		assert.strictEqual(useWorker, true);
		const worker = new EventEmitter();
		worker.pid = 700 + spawnedWorkers.length;
		worker.killed = 0;
		worker.stdout = new EventEmitter();
		worker.stderr = new EventEmitter();
		worker.stdout.setEncoding = () => {};
		worker.stderr.setEncoding = () => {};
		worker.kill = () => { worker.killed++; };
		spawnedWorkers.push(worker);
		return worker;
	}

	const VSBuffer = {
		alloc(size) {
			return { buffer: Buffer.alloc(size), byteLength: size };
		},
		wrap(buffer) {
			return { buffer, byteLength: buffer.length };
		},
		fromString(text) {
			const buffer = Buffer.from(text, 'utf8');
			return { buffer, byteLength: buffer.length };
		},
	};

	const decorator = () => {};
	const dependencies = {
		child_process: { fork() { throw new Error('unexpected fork'); } },
		net: wrappedNet,
		'../../base/common/buffer.js': { VSBuffer },
		'../../base/common/event.js': {
			Emitter,
			Event: {
				fromNodeEventEmitter(emitter, event) {
					return (listener) => {
						emitter.on(event, listener);
						return { dispose: () => emitter.off(event, listener) };
					};
				},
			},
		},
		'../../base/common/lifecycle.js': { Disposable, DisposableStore },
		'../../base/common/network.js': { FileAccess: { asFileUri: () => ({ fsPath: '/dummy/bootstrap-fork.js' }) } },
		'../../base/common/path.js': path,
		'../../base/common/platform.js': { isWindows: false },
		'../../base/node/workerAsChildProcess.js': { forkOrWorker },
		'../../base/common/processes.js': { removeDangerousEnvVariables() {} },
		'../../base/parts/ipc/common/ipc.net.js': { ProtocolConstants: { ReconnectionShortGraceTime: 300000 } },
		'../../base/parts/ipc/node/ipc.net.js': {
			createRandomIPCHandle: nextPipePath,
			NodeSocket: FakeClientSocket,
			WebSocketNodeSocket: FakeWebSocket,
		},
		'../../platform/remote/common/remoteAgentConnection.js': {},
		'../../workbench/services/extensions/common/extensionHostProtocol.js': {},
		'../../platform/configuration/common/configuration.js': { IConfigurationService: decorator },
		'../../platform/log/common/log.js': { ILogService: decorator },
		'../../platform/shell/node/shellEnv.js': { getResolvedShellEnv: async () => ({}) },
		'./extensionHostStatusService.js': { IExtensionHostStatusService: decorator },
		'./remoteLanguagePacks.js': { getNLSConfiguration: async () => ({}) },
		'./serverEnvironmentService.js': { IServerEnvironmentService: decorator },
		'../../workbench/services/extensions/common/extensionHostEnv.js': {
			IPCExtHostConnection: class { constructor(pipe) { this.pipe = pipe; } },
			SocketExtHostConnection: class {},
			writeExtHostConnection() {},
		},
	};

	source = source.replace(/^import (.+) from '([^']+)';$/gm, (_match, clause, name) => {
		assert.ok(Object.hasOwn(dependencies, name), 'unexpected import: ' + name);
		if (/^\* as \w+$/.test(clause)) {
			return 'const ' + clause.slice(5) + ' = __deps[' + JSON.stringify(name) + '];';
		}
		return 'const ' + clause + ' = __deps[' + JSON.stringify(name) + '];';
	});

	const transformed = stripTypeScriptTypes(source, {
		mode: 'transform',
		sourceUrl: 'extensionHostConnection.ts',
	});

	const context = {
		__deps: dependencies,
		process: { execArgv: [], env: { PATH: '/bin' } },
		Buffer,
		setTimeout,
		clearTimeout,
		console,
	};
	vm.runInNewContext(transformed + '\nglobalThis.ExtensionHostConnection = ExtensionHostConnection;', context);

	return {
		ExtensionHostConnection: context.ExtensionHostConnection,
		FakeClientSocket,
		VSBuffer,
		createdServers,
		spawnedWorkers,
	};
}

async function waitForData(socketOrClient, expectedText, timeoutMs = 1000) {
	if (socketOrClient instanceof net.Socket) {
		let received = '';
		return withTimeout(new Promise((resolve, reject) => {
			const onData = (chunk) => {
				received += chunk.toString('utf8');
				if (received.includes(expectedText)) {
					cleanup();
					resolve(received);
				}
			};
			const onError = (err) => { cleanup(); reject(err); };
			const onClose = () => {
				if (!received.includes(expectedText)) {
					cleanup();
					reject(new Error(`socket closed before receiving ${JSON.stringify(expectedText)} (got ${JSON.stringify(received)})`));
				}
			};
			const cleanup = () => {
				socketOrClient.off('data', onData);
				socketOrClient.off('error', onError);
				socketOrClient.off('close', onClose);
			};
			socketOrClient.on('data', onData);
			socketOrClient.on('error', onError);
			socketOrClient.on('close', onClose);
		}), timeoutMs, `socket data ${expectedText}`);
	}

	const check = () => Buffer.concat(socketOrClient.received).toString('utf8');
	if (check().includes(expectedText)) {
		return check();
	}
	return withTimeout(new Promise((resolve) => {
		socketOrClient.onWritten = () => {
			const text = check();
			if (text.includes(expectedText)) {
				socketOrClient.onWritten = null;
				resolve(text);
			}
		};
	}), timeoutMs, `client data ${expectedText}`);
}

async function runTests() {
	const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'worker-thread-patches-'));
	const patch0003 = path.join(patchesDir, '0003-ptyhost-as-worker-thread.patch');
	const patch0004 = path.join(patchesDir, '0004-exthost-as-worker-thread.patch');
	const patch0037 = path.join(patchesDir, '0037-retain-current-startup-state.patch');

	try {
		// Apply patch 0003 onto a fresh scratch tree containing upstream bootstrap-fork.ts
		const dir0003 = path.join(temporary, 'p0003');
		const bootstrapRel = 'src/bootstrap-fork.ts';
		const adapterRel = 'src/vs/base/node/workerAsChildProcess.ts';
		fs.mkdirSync(path.join(dir0003, 'src'), { recursive: true });
		fs.writeFileSync(path.join(dir0003, bootstrapRel), UPSTREAM_BOOTSTRAP_FORK, 'utf8');
		applyFixturePatch(dir0003, patch0003, {
			include: [bootstrapRel, adapterRel],
			changes: [bootstrapRel, adapterRel],
		});

		const patchedBootstrap = fs.readFileSync(path.join(dir0003, bootstrapRel), 'utf8');
		const patchedAdapter = fs.readFileSync(path.join(dir0003, adapterRel), 'utf8');
		const bootstrapWorkerPath = buildBootstrapWorkerScript(temporary, patchedBootstrap);
		const { WorkerAsChildProcess, activeTimers } = loadWorkerAsChildProcess(patchedAdapter);

		// 1A: bootstrap-fork receives __vsc_disconnect, runs synchronous disconnect listeners
		// (including re-entrant process.disconnect()) before closing parentPort, exits < 200ms,
		// and executes process.once('exit').
		{
			const sab = new SharedArrayBuffer(8 * Int32Array.BYTES_PER_ELEMENT);
			const view = new Int32Array(sab);
			const worker = new Worker(bootstrapWorkerPath, { workerData: { sab, reentrant: true } });
			const messages = [];
			const ready = new Promise((resolve) => {
				worker.on('message', (msg) => {
					messages.push(msg);
					if (msg && msg.type === 'ready') resolve();
				});
			});
			await withTimeout(ready, 2000, 'bootstrap-fork worker ready');

			const t0 = performance.now();
			const exited = once(worker, 'exit');
			worker.postMessage({ __vsc_disconnect: true });
			const [exitCode] = await withTimeout(exited, 1000, 'bootstrap-fork natural exit after __vsc_disconnect', () => {
				worker.terminate();
			});
			const elapsed = performance.now() - t0;

			assert.equal(exitCode, 0, 'bootstrap-fork worker must exit cleanly with code 0');
			assert.ok(elapsed < 200, `bootstrap-fork worker took ${elapsed.toFixed(1)}ms to exit after __vsc_disconnect; expected < 200ms`);
			assert.equal(Atomics.load(view, 0), 1, 'first disconnect listener must run exactly once');
			assert.equal(Atomics.load(view, 1), 1, 'process.connected must be false inside disconnect listener');
			assert.equal(Atomics.load(view, 3), 1, 'second disconnect listener must run even after re-entrant process.disconnect()');
			assert.equal(Atomics.load(view, 4), 1, 'process.once("exit") must execute on natural drain');
			assert.equal(Atomics.load(view, 5), 0, 'process.once("exit") must observe exit code 0');
			assert.ok(messages.some((m) => m && m.type === 'sync-teardown-1' && m.connected === false),
				'parent must receive synchronous IPC message sent during first disconnect listener before port.close()');
			assert.ok(messages.some((m) => m && m.type === 'sync-teardown-2' && m.connected === false),
				'parent must receive synchronous IPC message sent during second disconnect listener before port.close()');
		}

		// 1B: bootstrap-fork direct process.disconnect() sets connected=false, runs listeners
		// before closing parentPort, exits < 200ms, and executes process.once('exit').
		{
			const sab = new SharedArrayBuffer(8 * Int32Array.BYTES_PER_ELEMENT);
			const view = new Int32Array(sab);
			const worker = new Worker(bootstrapWorkerPath, { workerData: { sab, reentrant: false } });
			const messages = [];
			const ready = new Promise((resolve) => {
				worker.on('message', (msg) => {
					messages.push(msg);
					if (msg && msg.type === 'ready') resolve();
				});
			});
			await withTimeout(ready, 2000, 'self-disconnect worker ready');

			const t0 = performance.now();
			const exited = once(worker, 'exit');
			worker.postMessage({ type: 'self-disconnect' });
			const [exitCode] = await withTimeout(exited, 1000, 'bootstrap-fork natural exit after process.disconnect()', () => {
				worker.terminate();
			});
			const elapsed = performance.now() - t0;

			assert.equal(exitCode, 0);
			assert.ok(elapsed < 200, `process.disconnect() took ${elapsed.toFixed(1)}ms to drain; expected < 200ms`);
			assert.equal(Atomics.load(view, 0), 1);
			assert.equal(Atomics.load(view, 1), 1, 'process.disconnect() must clear process.connected before emitting disconnect');
			assert.equal(Atomics.load(view, 3), 1);
			assert.equal(Atomics.load(view, 4), 1, 'process.once("exit") must execute after process.disconnect()');
			assert.ok(messages.some((m) => m && m.type === 'sync-teardown-1'));
			assert.ok(messages.some((m) => m && m.type === 'sync-teardown-2'));
		}

		// 2A: WorkerAsChildProcess.kill('SIGTERM') against patched bootstrap-fork exits < 200ms,
		// fires worker process.once('exit'), reports (0, 'SIGTERM'), and clears its grace timer.
		{
			const exitFile = path.join(temporary, 'graceful-exit.txt');
			const child = new WorkerAsChildProcess(bootstrapWorkerPath, [], {
				env: { ...process.env, TEST_EXIT_FILE: exitFile },
			});
			await withTimeout(new Promise((resolve) => {
				child.on('message', (msg) => { if (msg && msg.type === 'ready') resolve(); });
			}), 2000, 'WorkerAsChildProcess graceful worker ready');

			const t0 = performance.now();
			const exited = once(child, 'exit');
			child.kill('SIGTERM');
			const [code, signal] = await withTimeout(exited, 1000, 'WorkerAsChildProcess graceful SIGTERM exit');
			const elapsed = performance.now() - t0;

			assert.equal(code, 0);
			assert.equal(signal, 'SIGTERM');
			assert.ok(elapsed < 200, `graceful WorkerAsChildProcess.kill('SIGTERM') took ${elapsed.toFixed(1)}ms; expected < 200ms`);
			assert.equal(activeTimers.size, 0, 'grace timer must be cleared when worker exits');
			assert.equal(fs.readFileSync(exitFile, 'utf8'), 'exit:0', 'worker process.once("exit") must run on graceful kill');
		}

		// 2B & 2C: WorkerAsChildProcess.kill('SIGTERM') followed immediately by kill('SIGKILL')
		// and kill(9) terminates a stubborn worker in < 200ms with signal 'SIGKILL' and clears
		// the pending grace timer.
		const stubbornWorkerPath = path.join(temporary, 'stubborn-worker.cjs');
		fs.writeFileSync(stubbornWorkerPath, [
			"'use strict';",
			"const { parentPort } = require('node:worker_threads');",
			'setInterval(() => {}, 1000);',
			"parentPort.on('message', () => {});",
			"parentPort.postMessage({ type: 'ready' });",
		].join('\n'), 'utf8');

		for (const escalateSignal of ['SIGKILL', 9]) {
			const child = new WorkerAsChildProcess(stubbornWorkerPath, [], { env: { ...process.env } });
			await withTimeout(new Promise((resolve) => {
				child.on('message', (msg) => { if (msg && msg.type === 'ready') resolve(); });
			}), 2000, `stubborn worker ready (${String(escalateSignal)})`);

			const t0 = performance.now();
			const exited = once(child, 'exit');
			child.kill('SIGTERM');
			assert.equal(activeTimers.size, 1, 'SIGTERM must arm the grace timer');
			child.kill(escalateSignal);
			assert.equal(activeTimers.size, 0, `kill(${JSON.stringify(escalateSignal)}) must clear the pending grace timer immediately`);

			const [code, signal] = await withTimeout(exited, 1000, `SIGKILL escalation (${String(escalateSignal)})`, () => {
				child._worker.terminate();
			});
			const elapsed = performance.now() - t0;

			assert.equal(code, 0);
			assert.equal(signal, 'SIGKILL', `kill(${JSON.stringify(escalateSignal)}) must report signal 'SIGKILL'`);
			assert.ok(elapsed < 200, `SIGKILL escalation (${String(escalateSignal)}) took ${elapsed.toFixed(1)}ms; expected < 200ms`);
			assert.equal(activeTimers.size, 0);
		}

		// 2D: Calling kill() after a worker has already exited must not arm a dangling timer
		// or emit a duplicate exit event.
		{
			const quickWorkerPath = path.join(temporary, 'quick-worker.cjs');
			fs.writeFileSync(quickWorkerPath, "'use strict';\n", 'utf8');
			const child = new WorkerAsChildProcess(quickWorkerPath, [], { env: { ...process.env } });
			let exitCount = 0;
			child.on('exit', () => { exitCount++; });
			const [code, signal] = await withTimeout(once(child, 'exit'), 2000, 'quick worker natural exit');
			assert.equal(code, 0);
			assert.equal(signal, null);
			child.kill('SIGTERM');
			child.kill('SIGKILL');
			assert.equal(activeTimers.size, 0, 'kill() after exit must not arm a grace timer');
			assert.equal(exitCount, 1);
		}

		// Verify patch 0004 + patch 0037 both apply cleanly on upstream extensionHostConnection.ts
		{
			const stackDir = path.join(temporary, 'stack-0004-0037');
			const rel = 'src/vs/server/node/extensionHostConnection.ts';
			fs.mkdirSync(path.join(stackDir, path.dirname(rel)), { recursive: true });
			fs.writeFileSync(path.join(stackDir, rel), UPSTREAM_EXTENSION_HOST_CONNECTION, 'utf8');
			applyFixturePatch(stackDir, patch0004, { include: [rel], changes: [rel] });
			if (fs.existsSync(patch0037)) {
				applyFixturePatch(stackDir, patch0037, { include: [rel], changes: [rel] });
			}
		}

		// Load patched ExtensionHostConnection from patch 0004
		let pipeCounter = 0;
		const pipePaths = [];
		const nextPipePath = () => {
			const p = path.join(temporary, `ext-${++pipeCounter}.sock`);
			pipePaths.push(p);
			return p;
		};
		const dir0004 = path.join(temporary, 'p0004');
		const {
			ExtensionHostConnection,
			FakeClientSocket,
			VSBuffer,
			createdServers,
		} = loadPatchedExtensionHostConnection(dir0004, patch0004, nextPipePath);

		async function startConnectionWithoutClient() {
			const initialClient = new FakeClientSocket();
			const connection = new ExtensionHostConnection(
				'reconn-token-1234',
				'127.0.0.1',
				initialClient,
				VSBuffer.alloc(0),
				{ args: {}, appRoot: '/dummy', userDataPath: '/dummy', isBuilt: true, reconnectionGraceTime: 300000 },
				{ info() {}, error() {}, trace() {} },
				{ setExitInfo() {} },
				{ getValue: () => false },
			);
			const serverIndexBefore = createdServers.length;
			await connection.start({ language: 'en', env: {} });
			const server = createdServers[serverIndexBefore];
			const pipePath = pipePaths[pipePaths.length - 1];
			// Drop the initial client so _connectionData === null
			initialClient.closeEmitter.fire();
			return { connection, server, pipePath };
		}

		async function dialPipe(server, pipePath) {
			const accepted = once(server, 'connection');
			const peer = net.createConnection(pipePath);
			await withTimeout(once(peer, 'connect'), 1000, 'worker pipe connect');
			const [serverSocket] = await withTimeout(accepted, 1000, 'server accepted pipe');
			return { peer, serverSocket };
		}

		// 3A: Paused _extHostSocket emits 'error' while _connectionData === null:
		// must not escape unhandled, must destroy/clear _extHostSocket so a second dial succeeds
		// and acceptReconnection() pipes to the fresh socket.
		{
			const { connection, server, pipePath } = await startConnectionWithoutClient();
			let peer1;
			let peer2;
			try {
				const first = await dialPipe(server, pipePath);
				peer1 = first.peer;
				const sock1 = first.serverSocket;
				assert.equal(sock1.isPaused(), true, 'accepted pipe socket must be paused when no client is attached');

				assert.doesNotThrow(() => {
					sock1.emit('error', new Error('simulated paused pipe error'));
				}, 'error on paused _extHostSocket must not escape unhandled');
				assert.equal(sock1.destroyed, true, 'errored paused _extHostSocket must be destroyed');

				// Second worker dial must be accepted rather than destroyed as stale
				const second = await dialPipe(server, pipePath);
				peer2 = second.peer;
				const sock2 = second.serverSocket;
				await new Promise((r) => setTimeout(r, 25));
				assert.equal(sock2.destroyed, false, 'second worker dial must succeed after errored paused socket cleared');
				assert.equal(peer2.destroyed, false);

				const client2 = new FakeClientSocket();
				const workerGot = waitForData(peer2, 'from-client-after-error');
				connection.acceptReconnection('127.0.0.1', client2, VSBuffer.fromString('from-client-after-error'));
				assert.equal(await workerGot, 'from-client-after-error');

				const clientGot = waitForData(client2, 'from-worker-after-error');
				peer2.write('from-worker-after-error');
				assert.equal(await clientGot, 'from-worker-after-error');
			} finally {
				peer1?.destroy();
				peer2?.destroy();
				connection.dispose();
			}
		}

		// 3B: Paused _extHostSocket closes cleanly from peer while _connectionData === null:
		// must clear _extHostSocket so a second worker dial succeeds and acceptReconnection()
		// pipes to the fresh socket.
		{
			const { connection, server, pipePath } = await startConnectionWithoutClient();
			let peer1;
			let peer2;
			try {
				const first = await dialPipe(server, pipePath);
				peer1 = first.peer;
				const sock1 = first.serverSocket;
				const closed = once(sock1, 'close');
				peer1.end();
				await withTimeout(closed, 1000, 'paused socket close after clean peer end');
				assert.equal(sock1.destroyed, true);

				const second = await dialPipe(server, pipePath);
				peer2 = second.peer;
				const sock2 = second.serverSocket;
				await new Promise((r) => setTimeout(r, 25));
				assert.equal(sock2.destroyed, false, 'second worker dial must succeed after paused socket closed cleanly');
				assert.equal(peer2.destroyed, false);

				const client2 = new FakeClientSocket();
				const workerGot = waitForData(peer2, 'from-client-after-close');
				connection.acceptReconnection('127.0.0.1', client2, VSBuffer.fromString('from-client-after-close'));
				assert.equal(await workerGot, 'from-client-after-close');

				const clientGot = waitForData(client2, 'from-worker-after-close');
				peer2.write('from-worker-after-close');
				assert.equal(await clientGot, 'from-worker-after-close');
			} finally {
				peer1?.destroy();
				peer2?.destroy();
				connection.dispose();
			}
		}

		// 3C: Ownership handoff from _extHostPipeStore to _pipeStore on acceptReconnection:
		// no duplicate error/close/end listeners, buffered bytes survive pause, and dispose()
		// cleans up all listeners.
		{
			const { connection, server, pipePath } = await startConnectionWithoutClient();
			let peer1;
			try {
				const first = await dialPipe(server, pipePath);
				peer1 = first.peer;
				const sock1 = first.serverSocket;
				const pausedCloseCount = sock1.listenerCount('close');
				const pausedEndCount = sock1.listenerCount('end');
				assert.equal(sock1.listenerCount('error'), 1, 'paused socket must have 1 error listener');
				assert.ok(pausedCloseCount >= 1);
				assert.ok(pausedEndCount >= 1);

				peer1.write('buffered-while-paused');
				await new Promise((r) => setTimeout(r, 25));

				const client2 = new FakeClientSocket();
				const clientGot = waitForData(client2, 'buffered-while-paused');
				connection.acceptReconnection('127.0.0.1', client2, VSBuffer.alloc(0));

				assert.equal(sock1.listenerCount('error'), 1, '_pipeSockets must clear _extHostPipeStore before attaching _pipeStore error listener');
				assert.equal(sock1.listenerCount('close'), pausedCloseCount, '_pipeSockets must clear _extHostPipeStore before attaching _pipeStore close listener');
				assert.equal(sock1.listenerCount('end'), pausedEndCount, '_pipeSockets must clear _extHostPipeStore before attaching _pipeStore end listener');
				assert.equal(await clientGot, 'buffered-while-paused', 'bytes written while paused must reach reconnected client');

				connection.dispose();
				assert.equal(sock1.listenerCount('error'), 0);
				assert.equal(sock1.listenerCount('close'), pausedCloseCount - 1);
				assert.equal(sock1.listenerCount('end'), pausedEndCount - 1);
				assert.equal(sock1.destroyed, true);
			} finally {
				peer1?.destroy();
				connection.dispose();
			}
		}

		// 3D: _cleanResources() disposes _extHostPipeStore while a socket is paused.
		{
			const { connection, server, pipePath } = await startConnectionWithoutClient();
			let peer1;
			try {
				const first = await dialPipe(server, pipePath);
				peer1 = first.peer;
				const sock1 = first.serverSocket;
				const pausedCloseCount = sock1.listenerCount('close');
				const pausedEndCount = sock1.listenerCount('end');
				assert.equal(sock1.listenerCount('error'), 1);
				connection.dispose();
				assert.equal(sock1.listenerCount('error'), 0, '_cleanResources must dispose _extHostPipeStore listeners');
				assert.equal(sock1.listenerCount('close'), pausedCloseCount - 1);
				assert.equal(sock1.listenerCount('end'), pausedEndCount - 1);
				assert.equal(sock1.destroyed, true);
			} finally {
				peer1?.destroy();
				connection.dispose();
			}
		}

		console.log('ok - worker-thread patches close parentPort on disconnect, honor SIGKILL escalation, and watch paused extHost pipe sockets');
	} finally {
		fs.rmSync(temporary, { recursive: true, force: true });
	}
}

module.exports = runTests();
if (require.main === module) {
	module.exports.catch((error) => {
		console.error(error);
		process.exitCode = 1;
	});
}
