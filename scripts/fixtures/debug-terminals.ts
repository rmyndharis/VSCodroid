/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

const enum ShellType { cmd, powershell, bash }


export function prepareCommand(shell: string, args: string[], argsCanBeInterpretedByShell: boolean, cwd?: string, env?: { [key: string]: string | null }): string {

	shell = shell.trim().toLowerCase();

	// try to determine the shell type
	let shellType;
	if (shell.indexOf('powershell') >= 0 || shell.indexOf('pwsh') >= 0) {
		shellType = ShellType.powershell;
	} else if (shell.indexOf('cmd.exe') >= 0) {
		shellType = ShellType.cmd;
	} else if (shell.indexOf('bash') >= 0) {
		shellType = ShellType.bash;
	} else if (platform.isWindows) {
		shellType = ShellType.cmd; // pick a good default for Windows
	} else {
		shellType = ShellType.bash;	// pick a good default for anything else
	}

	let quote: (s: string) => string;
	// begin command with a space to avoid polluting shell history
	let command = ' ';

	switch (shellType) {

		case ShellType.powershell:

			quote = (s: string) => {
				s = s.replace(/\'/g, '\'\'');
				if (s.length > 0 && s.charAt(s.length - 1) === '\\') {
					return `'${s}\\'`;
				}
				return `'${s}'`;
			};

			if (cwd) {
				const driveLetter = getDriveLetter(cwd);
				if (driveLetter) {
					command += `${driveLetter}:; `;
				}
				command += `cd ${quote(cwd)}; `;
			}
			if (env) {
				for (const key in env) {
					const value = env[key];
					if (value === null) {
						command += `Remove-Item env:${key}; `;
					} else {
						command += `\${env:${key}}='${value.replace(/['\u2018\u2019]/g, quote => quote + quote)}'; `;
					}
				}
			}
			if (args.length > 0) {
				const arg = args.shift()!;
				const cmd = argsCanBeInterpretedByShell ? arg : quote(arg);
				command += (cmd[0] === '\'') ? `& ${cmd} ` : `${cmd} `;
				for (const a of args) {
					command += (a === '<' || a === '>' || argsCanBeInterpretedByShell) ? a : quote(a);
					command += ' ';
				}
			}
			break;

		case ShellType.cmd:

			quote = (s: string) => {
				// Note: Wrapping in cmd /C "..." complicates the escaping.
				// cmd /C "node -e "console.log(process.argv)" """A^>0"""" # prints "A>0"
				// cmd /C "node -e "console.log(process.argv)" "foo^> bar"" # prints foo> bar
				// Outside of the cmd /C, it could be a simple quoting, but here, the ^ is needed too
				s = s.replace(/\"/g, '""');
				s = s.replace(/([><!^&|])/g, '^$1');
				return (' "'.split('').some(char => s.includes(char)) || s.length === 0) ? `"${s}"` : s;
			};

			if (cwd) {
				const driveLetter = getDriveLetter(cwd);
				if (driveLetter) {
					command += `${driveLetter}: && `;
				}
				command += `cd ${quote(cwd)} && `;
			}
			if (env) {
				command += 'cmd /C "';
				for (const key in env) {
					let value = env[key];
					if (value === null) {
						command += `set "${key}=" && `;
					} else {
						value = value.replace(/[&^|<>]/g, s => `^${s}`);
						command += `set "${key}=${value}" && `;
					}
				}
			}
			for (const a of args) {
				command += (a === '<' || a === '>' || argsCanBeInterpretedByShell) ? a : quote(a);
				command += ' ';
			}
			if (env) {
				command += '"';
			}
			break;

		case ShellType.bash: {

			quote = (s: string) => {
				s = s.replace(/(["'\\\$!><#()\[\]*&^| ;{}?`])/g, '\\$1');
				return s.length === 0 ? `""` : s;
			};

			const hardQuote = (s: string) => {
				return /[^\w@%\/+=,.:^-]/.test(s) ? `'${s.replace(/'/g, '\'\\\'\'')}'` : s;
			};

			if (cwd) {
				command += `cd ${quote(cwd)} ; `;
			}
			if (env) {
				command += '/usr/bin/env';
				for (const key in env) {
					const value = env[key];
					if (value === null) {
						command += ` -u ${hardQuote(key)}`;
					} else {
						command += ` ${hardQuote(`${key}=${value}`)}`;
					}
				}
				command += ' ';
			}
			for (const a of args) {
				command += (a === '<' || a === '>' || argsCanBeInterpretedByShell) ? a : quote(a);
				command += ' ';
			}
			break;
		}
	}

	return command;
}
