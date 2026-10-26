/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

import * as cp from 'child_process';
import * as net from 'net';
import { VSBuffer } from '../../base/common/buffer.js';
import { Emitter, Event } from '../../base/common/event.js';
import { Disposable, DisposableStore } from '../../base/common/lifecycle.js';
import { FileAccess } from '../../base/common/network.js';
import { delimiter, join } from '../../base/common/path.js';
import { IProcessEnvironment, isWindows } from '../../base/common/platform.js';
import { forkOrWorker } from '../../base/node/workerAsChildProcess.js';
import { removeDangerousEnvVariables } from '../../base/common/processes.js';
import { ProtocolConstants } from '../../base/parts/ipc/common/ipc.net.js';
import { createRandomIPCHandle, NodeSocket, WebSocketNodeSocket } from '../../base/parts/ipc/node/ipc.net.js';
import { IConfigurationService } from '../../platform/configuration/common/configuration.js';
import { ILogService } from '../../platform/log/common/log.js';
import { IRemoteExtensionHostStartParams } from '../../platform/remote/common/remoteAgentConnection.js';
import { getResolvedShellEnv } from '../../platform/shell/node/shellEnv.js';
import { IExtensionHostStatusService } from './extensionHostStatusService.js';
import { getNLSConfiguration } from './remoteLanguagePacks.js';
import { IServerEnvironmentService } from './serverEnvironmentService.js';
import { IPCExtHostConnection, SocketExtHostConnection, writeExtHostConnection } from '../../workbench/services/extensions/common/extensionHostEnv.js';
import { IExtHostReadyMessage, IExtHostReduceGraceTimeMessage, IExtHostSocketMessage } from '../../workbench/services/extensions/common/extensionHostProtocol.js';

export async function buildUserEnvironment(startParamsEnv: { [key: string]: string | null } = {}, withUserShellEnvironment: boolean, language: string, environmentService: IServerEnvironmentService, logService: ILogService, configurationService: IConfigurationService): Promise<IProcessEnvironment> {
	const nlsConfig = await getNLSConfiguration(language, environmentService.userDataPath);

	let userShellEnv: typeof process.env = {};
	if (withUserShellEnvironment) {
		try {
			userShellEnv = await getResolvedShellEnv(configurationService, logService, environmentService.args, process.env);
		} catch (error) {
			logService.error('ExtensionHostConnection#buildUserEnvironment resolving shell environment failed', error);
		}
	}

	const processEnv = process.env;

	const env: IProcessEnvironment = {
		...processEnv,
		...userShellEnv,
		...startParamsEnv,
		VSCODE_ESM_ENTRYPOINT: 'vs/workbench/api/node/extensionHostProcess',
		VSCODE_HANDLES_UNCAUGHT_ERRORS: 'true',
		VSCODE_NLS_CONFIG: JSON.stringify(nlsConfig)
	};

	const binFolder = environmentService.isBuilt ? join(environmentService.appRoot, 'bin') : join(environmentService.appRoot, 'resources', 'server', 'bin-dev');
	const remoteCliBinFolder = join(binFolder, 'remote-cli'); // contains the `code` command that can talk to the remote server

	let PATH = readCaseInsensitive(env, 'PATH');
	if (PATH) {
		PATH = remoteCliBinFolder + delimiter + PATH;
	} else {
		PATH = remoteCliBinFolder;
	}
	setCaseInsensitive(env, 'PATH', PATH);

	if (!environmentService.args['without-browser-env-var']) {
		env.BROWSER = join(binFolder, 'helpers', isWindows ? 'browser.cmd' : 'browser.sh'); // a command that opens a browser on the local machine
	}

	env.VSCODE_RECONNECTION_GRACE_TIME = String(environmentService.reconnectionGraceTime);
	logService.trace(`[reconnection-grace-time] Setting VSCODE_RECONNECTION_GRACE_TIME env var for extension host: ${environmentService.reconnectionGraceTime}ms (${Math.floor(environmentService.reconnectionGraceTime / 1000)}s)`);

	removeNulls(env);
	return env;
}

class ConnectionData {
	constructor(
		public readonly socket: NodeSocket | WebSocketNodeSocket,
		public readonly initialDataChunk: VSBuffer
	) { }

	public socketDrain(): Promise<void> {
		return this.socket.drain();
	}

	public toIExtHostSocketMessage(): IExtHostSocketMessage {

		let skipWebSocketFrames: boolean;
		let permessageDeflate: boolean;
		let inflateBytes: VSBuffer;

		if (this.socket instanceof NodeSocket) {
			skipWebSocketFrames = true;
			permessageDeflate = false;
			inflateBytes = VSBuffer.alloc(0);
		} else {
			skipWebSocketFrames = false;
			permessageDeflate = this.socket.permessageDeflate;
			inflateBytes = this.socket.recordedInflateBytes;
			this.socket.setRecordInflateBytes(false);
		}

		return {
			type: 'VSCODE_EXTHOST_IPC_SOCKET',
			initialDataChunk: (<Buffer>this.initialDataChunk.buffer).toString('base64'),
			skipWebSocketFrames: skipWebSocketFrames,
			permessageDeflate: permessageDeflate,
			inflateBytes: (<Buffer>inflateBytes.buffer).toString('base64'),
		};
	}
}

export class ExtensionHostConnection extends Disposable {

	private _onClose = this._register(new Emitter<void>());
	readonly onClose: Event<void> = this._onClose.event;

	private readonly _canSendSocket: boolean;
	private _disposed: boolean;
	private _remoteAddress: string;
	private _extensionHostProcess: cp.ChildProcess | null;
	private _connectionData: ConnectionData | null;
	/**
	 * The pipe half of the connection, and the state that lets a client reconnect
	 * reattach to the extension host that is already running.
	 *
	 * The named pipe server stays listening for the life of the connection rather
	 * than closing after the first connection, because the host re-dials it every
	 * time a client goes away. `_extHostSocket` is the accepted socket while a host
	 * is attached, and it is paused whenever no client is: a socket with no 'data'
	 * listener is still flowing, so anything the host wrote in the gap would be
	 * emitted to nobody and lost.
	 */
	private _extHostNamedPipeServer: net.Server | null;
	private _extHostSocket: net.Socket | null;
	private _pipeStore: DisposableStore | null;
	private _clientStore: DisposableStore | null;
	private _disconnectTimeout: Timeout | null;

	constructor(
		private readonly _reconnectionToken: string,
		remoteAddress: string,
		socket: NodeSocket | WebSocketNodeSocket,
		initialDataChunk: VSBuffer,
		@IServerEnvironmentService private readonly _environmentService: IServerEnvironmentService,
		@ILogService private readonly _logService: ILogService,
		@IExtensionHostStatusService private readonly _extensionHostStatusService: IExtensionHostStatusService,
		@IConfigurationService private readonly _configurationService: IConfigurationService
	) {
		super();
		// Forced off because the Extension Host runs as a worker_thread, and a socket
		// handle cannot be transferred to one. Left on, the constructor would pick the
		// branch that hands the socket over IPC; the pipe path this selects instead is
		// the same one Windows already uses.
		this._canSendSocket = false;
		this._disposed = false;
		this._remoteAddress = remoteAddress;
		this._extensionHostProcess = null;
		this._connectionData = new ConnectionData(socket, initialDataChunk);
		this._extHostNamedPipeServer = null;
		this._extHostSocket = null;
		this._pipeStore = null;
		this._clientStore = null;
		this._disconnectTimeout = null;
		if (!this._canSendSocket && socket instanceof WebSocketNodeSocket) {
			socket.setRecordInflateBytes(false);
		}
		this._watchClient(this._connectionData);

		this._log(`New connection established.`);
	}

	override dispose(): void {
		this._cleanResources();
		super.dispose();
	}

	private get _logPrefix(): string {
		return `[${this._remoteAddress}][${this._reconnectionToken.substr(0, 8)}][ExtensionHostConnection] `;
	}

	private _log(_str: string): void {
		this._logService.info(`${this._logPrefix}${_str}`);
	}

	private _logError(_str: string): void {
		this._logService.error(`${this._logPrefix}${_str}`);
	}

	private _pipeSockets(extHostSocket: net.Socket, connectionData: ConnectionData): void {

		const disposables = new DisposableStore();
		this._pipeStore = disposables;

		const unpipe = () => {
			if (this._pipeStore === disposables) {
				this._pipeStore = null;
			}
			disposables.dispose();
		};

		// No listener for the client going away, on purpose. Its socket has been
		// watched since the moment it was stored (_watchClient), which is earlier
		// than this runs: a client can drop before the host's first dial, and a
		// listener attached here would be attached to a close that already fired.
		// _clientDisconnected takes this pipe down with the rest of the state
		// before it ends the host's socket, so the host's re-dial cannot race the
		// socket it is replacing.

		// The host going away does NOT take the client with it: it re-dials the pipe
		// after every client disconnect, and if it really died its own exit handler
		// runs _cleanResources.
		//
		// The client's socket is deliberately left attached, which is the one place
		// this side is not the mirror of _clientDisconnected. Disposing the store stops
		// this class reading it, but a browser client is a WebSocketNodeSocket and
		// its constructor put a 'data' listener on the NodeSocket underneath, so the
		// socket keeps being drained and whatever arrives in the gap is dropped with
		// nothing to notice -- the same hazard extHostSocket.pause() exists to avoid
		// on the other side. What makes it safe is what reaching here means: the pipe
		// is intra-process, the host being a worker_thread of this process, so a host
		// socket closing under an attached client means the worker died, and its
		// 'exit' handler runs _cleanResources, which ends the client socket right
		// after. If this ever becomes a real inter-process pipe, the client has to
		// come down here too.
		const onExtHostGone = () => {
			unpipe();
			if (this._extHostSocket === extHostSocket) {
				this._extHostSocket = null;
			}
		};
		disposables.add(Event.fromNodeEventEmitter<void>(extHostSocket, 'end')(onExtHostGone));
		disposables.add(Event.fromNodeEventEmitter<void>(extHostSocket, 'close')(onExtHostGone));
		disposables.add(Event.fromNodeEventEmitter<void>(extHostSocket, 'error')(onExtHostGone));

		disposables.add(connectionData.socket.onData((e) => extHostSocket.write(e.buffer)));
		disposables.add(Event.fromNodeEventEmitter<Buffer>(extHostSocket, 'data')((e) => {
			connectionData.socket.write(VSBuffer.wrap(e));
		}));

		if (connectionData.initialDataChunk.byteLength > 0) {
			extHostSocket.write(connectionData.initialDataChunk.buffer);
		}

		// Explicit, because _acceptExtHostPipe paused it: adding a 'data' listener
		// only resumes a stream that was never paused on purpose.
		extHostSocket.resume();
	}

	/**
	 * Takes the pipe connection the extension host dials, and attaches it to the
	 * client if there is one. Called for the first connection and for every re-dial
	 * the host makes after a client goes away.
	 */
	private _acceptExtHostPipe(extHostSocket: net.Socket): void {
		if (this._disposed || this._extHostSocket) {
			// One host at a time. A second socket can only be a stale dial.
			extHostSocket.destroy();
			return;
		}
		// Paused until a client is attached. Left flowing, everything the host writes
		// while it waits is emitted to no listener and dropped, and the protocol has
		// no way to notice: it is the same stream, so the client would resume mid-way
		// through a message that never arrived.
		extHostSocket.pause();
		this._extHostSocket = extHostSocket;
		if (this._connectionData) {
			this._pipeSockets(extHostSocket, this._connectionData);
		}
	}

	/**
	 * Watches the client's socket from the moment it is stored, whether or not the
	 * host has dialled the pipe yet.
	 *
	 * The listeners lived in _pipeSockets, which runs only once the host connects,
	 * and the host's boot is seconds on a phone. A client that dropped inside that
	 * window, a renderer crash or a folder switch right after opening a workspace,
	 * closed a socket nothing was listening to. WebSocketNodeSocket fires its close
	 * once, so the listener _pipeSockets attached afterwards never heard it, no
	 * grace was ever scheduled, and since the host on this path no longer
	 * terminates itself the worker lived until the server exited, one more per
	 * occurrence. _clientDisconnected disposes this store before it replaces or
	 * drops the socket, so a stale socket's late close reaches nothing.
	 */
	private _watchClient(connectionData: ConnectionData): void {
		const disposables = new DisposableStore();
		this._clientStore = disposables;
		const onClientGone = () => this._clientDisconnected();
		disposables.add(connectionData.socket.onEnd(onClientGone));
		disposables.add(connectionData.socket.onClose(onClientGone));
	}

	/**
	 * The client's socket closed. Take the pipe down so the host re-dials, and start
	 * the clock on whether it is coming back.
	 */
	private _clientDisconnected(): void {
		if (this._disposed) {
			return;
		}
		if (this._clientStore) {
			this._clientStore.dispose();
			this._clientStore = null;
		}
		if (this._pipeStore) {
			this._pipeStore.dispose();
			this._pipeStore = null;
		}
		if (this._connectionData) {
			this._connectionData.socket.dispose();
			this._connectionData = null;
		}
		const extHostSocket = this._extHostSocket;
		this._extHostSocket = null;
		if (extHostSocket && !extHostSocket.destroyed && !extHostSocket.writableEnded) {
			// Ending it is what makes the reconnect work, rather than what breaks it.
			// The two ends speak PersistentProtocol and this server is a byte pipe in
			// the middle: whatever it read from the host and wrote into a socket that
			// was already closing is gone, so resuming the same stream onto a new
			// client socket would hand a fresh ProtocolReader the tail of a message it
			// never saw the head of. Closing the pipe makes the host redo the
			// reconnection handshake, which restarts its writer at a frame boundary
			// and replays what was not acknowledged.
			extHostSocket.end();
		}
		this._scheduleDisconnectTimeout();
	}

	/**
	 * How long a disconnected client has to come back before the extension host is
	 * disposed.
	 *
	 * Upstream leaves this to the host itself, which schedules the grace when its
	 * own socket closes. On this path the host's socket closing is the reconnect
	 * mechanism rather than the disconnect signal, so the decision has to be made
	 * here, where the client is.
	 *
	 * Capped at the short grace rather than the full one. An extension host is the
	 * most expensive thing this server holds, the app reloads the page outright
	 * after five minutes in the background, and a renderer crash orphans this
	 * connection under a token the client will never present again.
	 */
	private _scheduleDisconnectTimeout(): void {
		if (this._disconnectTimeout || this._disposed) {
			return;
		}
		const graceTime = Math.min(ProtocolConstants.ReconnectionShortGraceTime, this._environmentService.reconnectionGraceTime);
		this._log(`The client has disconnected, will wait ${graceTime}ms for a reconnection before disposing.`);
		this._disconnectTimeout = setTimeout(() => {
			this._disconnectTimeout = null;
			this._log(`The client did not reconnect in time.`);
			this._cleanResources();
		}, graceTime);
	}

	private async _sendSocketToExtensionHost(extensionHostProcess: cp.ChildProcess, connectionData: ConnectionData): Promise<void> {
		// Make sure all outstanding writes have been drained before sending the socket
		await connectionData.socketDrain();
		// Unreachable now that _canSendSocket is false: the initial connection takes
		// the pipe path and acceptReconnection re-pipes. A worker_thread has no
		// channel that can carry a socket handle, and send() would drop it while
		// reporting success, so this refuses rather than going quiet if the flag ever
		// comes back.
		this._logError(`<${extensionHostProcess.pid}> Cannot hand a socket to a worker_thread Extension Host; the client must reconnect from scratch.`);
	}

	public shortenReconnectionGraceTimeIfNecessary(): void {
		// Inert on this path, and left in place rather than emptied so the name still
		// matches the one ManagementConnection answers. The message is only listened
		// for in the SOCKET branch of _createExtHostProtocol, which _canSendSocket =
		// false makes unreachable; the pipe branch the host takes instead has no grace
		// runners at all, because the grace moved to this side of the pipe with
		// _scheduleDisconnectTimeout. Nothing here needs rescheduling either: that
		// timeout is already capped at the short grace this message exists to ask for.
		if (!this._extensionHostProcess) {
			return;
		}
		const msg: IExtHostReduceGraceTimeMessage = {
			type: 'VSCODE_EXTHOST_IPC_REDUCE_GRACE_TIME'
		};
		this._extensionHostProcess.send(msg);
	}

	public acceptReconnection(remoteAddress: string, _socket: NodeSocket | WebSocketNodeSocket, initialDataChunk: VSBuffer): void {
		this._remoteAddress = remoteAddress;
		this._log(`The client has reconnected.`);
		if (!this._canSendSocket && _socket instanceof WebSocketNodeSocket) {
			_socket.setRecordInflateBytes(false);
		}
		const connectionData = new ConnectionData(_socket, initialDataChunk);

		// A reconnection can arrive while the previous client socket is still open,
		// which is what a stale TCP connection looks like from in here. Put the old
		// one through the same teardown an observed disconnect gets rather than
		// attaching a second pipe on top of it: the host's socket carries one
		// PersistentProtocol stream and cannot be spliced onto a different client
		// halfway through, so it has to close and re-dial either way.
		if (this._connectionData) {
			this._clientDisconnected();
		}

		if (this._disconnectTimeout) {
			clearTimeout(this._disconnectTimeout);
			this._disconnectTimeout = null;
		}

		// Covers the host that has not started up yet and the host that is between
		// pipe connections alike: in both, _extHostSocket is null and the socket is
		// picked up by _acceptExtHostPipe as soon as the host dials.
		this._connectionData = connectionData;
		this._watchClient(connectionData);
		if (this._extHostSocket) {
			this._pipeSockets(this._extHostSocket, connectionData);
		}
	}

	private _cleanResources(): void {
		if (this._disposed) {
			// already called
			return;
		}
		this._disposed = true;
		if (this._disconnectTimeout) {
			clearTimeout(this._disconnectTimeout);
			this._disconnectTimeout = null;
		}
		if (this._clientStore) {
			this._clientStore.dispose();
			this._clientStore = null;
		}
		if (this._pipeStore) {
			this._pipeStore.dispose();
			this._pipeStore = null;
		}
		// Closed before the socket is destroyed, so a host that reacts to the close
		// by dialling again is refused by the operating system rather than accepted
		// and dropped in a loop.
		if (this._extHostNamedPipeServer) {
			this._extHostNamedPipeServer.close();
			this._extHostNamedPipeServer = null;
		}
		if (this._extHostSocket) {
			this._extHostSocket.destroy();
			this._extHostSocket = null;
		}
		if (this._connectionData) {
			this._connectionData.socket.end();
			this._connectionData = null;
		}
		if (this._extensionHostProcess) {
			this._extensionHostProcess.kill();
			this._extensionHostProcess = null;
		}
		this._onClose.fire(undefined);
	}

	public async start(startParams: IRemoteExtensionHostStartParams): Promise<void> {
		try {
			let execArgv: string[] = process.execArgv ? process.execArgv.filter(a => !/^--inspect(-brk)?=/.test(a)) : [];
			// eslint-disable-next-line local/code-no-any-casts, @typescript-eslint/no-explicit-any
			if (startParams.port && !(<any>process).pkg) {
				execArgv = [
					`--inspect${startParams.break ? '-brk' : ''}=${startParams.port}`,
					'--experimental-network-inspection'
				];
			}

			this._log(`Starting extension host process...`);

			const env = await buildUserEnvironment(startParams.env, true, startParams.language, this._environmentService, this._logService, this._configurationService);
			removeDangerousEnvVariables(env);

			let extHostNamedPipeServer: net.Server | null;

			if (this._canSendSocket) {
				writeExtHostConnection(new SocketExtHostConnection(), env);
				extHostNamedPipeServer = null;
			} else {
				const { namedPipeServer, pipeName } = await this._listenOnPipe();
				writeExtHostConnection(new IPCExtHostConnection(pipeName), env);
				extHostNamedPipeServer = namedPipeServer;
			}

			const opts = {
				env,
				execArgv,
				silent: true
			};

			// Refs https://github.com/microsoft/vscode/issues/189805
			opts.execArgv.unshift('--dns-result-order=ipv4first');

			// Run Extension Host as fork of current process
			const args = ['--type=extensionHost', `--transformURIs`];
			const useHostProxy = this._environmentService.args['use-host-proxy'];
			args.push(`--useHostProxy=${useHostProxy ? 'true' : 'false'}`);
			if (this._configurationService.getValue<boolean>('extensions.supportNodeGlobalNavigator')) {
				args.push('--supportGlobalNavigator');
			}
			// A thread rather than a process. Android 12+ kills background child
			// processes past a system-wide limit of 32, and the Extension Host is the
			// longest-lived fork the server makes. bootstrap-fork gives the module the
			// same IPC either way.
			this._extensionHostProcess = forkOrWorker(true, cp.fork, FileAccess.asFileUri('bootstrap-fork').fsPath, args, opts);
			const pid = this._extensionHostProcess.pid;
			this._log(`<${pid}> Launched Extension Host Process.`);

			// Catch all output coming from the extension host process
			this._extensionHostProcess.stdout!.setEncoding('utf8');
			this._extensionHostProcess.stderr!.setEncoding('utf8');
			const onStdout = Event.fromNodeEventEmitter<string>(this._extensionHostProcess.stdout!, 'data');
			const onStderr = Event.fromNodeEventEmitter<string>(this._extensionHostProcess.stderr!, 'data');
			this._register(onStdout((e) => this._log(`<${pid}> ${e}`)));
			this._register(onStderr((e) => this._log(`<${pid}><stderr> ${e}`)));

			// Lifecycle
			this._extensionHostProcess.on('error', (err) => {
				this._logError(`<${pid}> Extension Host Process had an error`);
				this._logService.error(err);
				this._cleanResources();
			});

			this._extensionHostProcess.on('exit', (code: number, signal: string) => {
				this._extensionHostStatusService.setExitInfo(this._reconnectionToken, { code, signal });
				this._log(`<${pid}> Extension Host Process exited with code: ${code}, signal: ${signal}.`);
				this._cleanResources();
			});

			if (extHostNamedPipeServer) {
				// Kept listening instead of closed after the first connection. The host
				// re-dials this pipe every time a client goes away, which is how it
				// survives a reconnection without a socket handle ever crossing the
				// worker boundary.
				this._extHostNamedPipeServer = extHostNamedPipeServer;
				extHostNamedPipeServer.on('connection', (socket) => {
					this._acceptExtHostPipe(socket);
				});
			} else {
				const messageListener = (msg: IExtHostReadyMessage) => {
					if (msg.type === 'VSCODE_EXTHOST_IPC_READY') {
						this._extensionHostProcess!.removeListener('message', messageListener);
						this._sendSocketToExtensionHost(this._extensionHostProcess!, this._connectionData!);
						this._connectionData = null;
					}
				};
				this._extensionHostProcess.on('message', messageListener);
			}

		} catch (error) {
			this._logError(`Failed to start extension host process`);
			this._logService.error(error);
			this._cleanResources();
		}
	}

	private _listenOnPipe(): Promise<{ pipeName: string; namedPipeServer: net.Server }> {
		return new Promise<{ pipeName: string; namedPipeServer: net.Server }>((resolve, reject) => {
			const pipeName = createRandomIPCHandle();

			const namedPipeServer = net.createServer();
			namedPipeServer.on('error', reject);
			namedPipeServer.listen(pipeName, () => {
				namedPipeServer?.removeListener('error', reject);
				resolve({ pipeName, namedPipeServer });
			});
		});
	}
}

function readCaseInsensitive(env: { [key: string]: string | undefined }, key: string): string | undefined {
	const pathKeys = Object.keys(env).filter(k => k.toLowerCase() === key.toLowerCase());
	const pathKey = pathKeys.length > 0 ? pathKeys[0] : key;
	return env[pathKey];
}

function setCaseInsensitive(env: { [key: string]: unknown }, key: string, value: string): void {
	const pathKeys = Object.keys(env).filter(k => k.toLowerCase() === key.toLowerCase());
	const pathKey = pathKeys.length > 0 ? pathKeys[0] : key;
	env[pathKey] = value;
}

function removeNulls(env: { [key: string]: unknown | null }): void {
	// Don't delete while iterating the object itself
	for (const key of Object.keys(env)) {
		if (env[key] === null) {
			delete env[key];
		}
	}
}
