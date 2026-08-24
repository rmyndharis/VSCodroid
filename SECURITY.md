# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| latest  | :white_check_mark: |

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability in VSCodroid, please report it responsibly.

### How to Report

**Please DO NOT open a public GitHub issue for security vulnerabilities.**

Instead, please report it by email:

📧 **Email**: yudhi@rmyndharis.com

Private vulnerability reporting is switched off for this repository, so GitHub's
"Report a vulnerability" form is not open to anyone outside the maintainers.
Email is the channel that reaches us.

### What to Include

- Description of the vulnerability
- Steps to reproduce the issue
- Potential impact assessment
- Suggested fix (if any)

### Response Timeline

| Action | Timeline |
|---|---|
| Acknowledgment | Within 48 hours |
| Initial assessment | Within 1 week |
| Fix development | Depends on severity |
| Public disclosure | After fix is released |

### Severity Levels

- **Critical**: Remote code execution, data exfiltration
- **High**: Privilege escalation, authentication bypass
- **Medium**: Information disclosure, denial of service
- **Low**: Minor issues with limited impact

### Security Considerations

VSCodroid runs code locally on your device. Key security areas:

- **Loopback only**: the server is started with `--host=127.0.0.1` (`ProcessManager.startServer`), so it binds no address reachable from the network
- **Connection token**: the server requires one on every route except `/version`, `/delay-shutdown` and `/callback` (`ProcessManager.connectionToken`). It generates the token itself and writes it mode 0600; the WebView URL carries it
- **Extension host**: extensions run in the extension host, which `patches/0004-exthost-as-worker-thread.patch` makes a worker thread inside the server's Node process. It is a fault boundary, not a security sandbox: an extension has the same reach over app-private storage and the network as the app itself
- **Bundled chat provider**: GitHub Copilot Chat ships in the server tree and `product.json` makes it the editor's chat provider. Sign-in is what gives it an account, and its chat features are unavailable before that, but it is not dormant until then: its manifest lists `onStartupFinished`, so it activates on every start, and its model backend runs signed out, one of the processes `process-monitor.js` counts in this app's idle baseline. Whether that backend sends anything before sign-in has not been established here. After sign-in, prompts and the source it attaches as context go to GitHub. `docs/PRIVACY_POLICY.md` is the user-facing statement of this
- **Extension signatures**: not verified. `extensions.verifySignature` is written `false` into the machine defaults, because signature checking loads `@vscode/vsce-sign`, which no Code - OSS build ships. A VSIX is trusted on the HTTPS connection to Open VSX and nothing else, and extension updates install unattended
- **Storage**: settings, secrets, the server tree, the mirrors of folders opened through the Storage Access Framework and, on a new install, the default workspace all live in the app-private directory. An install that already had `projects/` under `getExternalFilesDir(null)` keeps it there (`Environment.getProjectsDir`); new installs stopped using that location because shared storage cannot hold a symbolic link, which broke `npm install`
- **Permissions**: this repository's `AndroidManifest.xml` declares four, `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS`. The installed app holds six: the manifest merger adds `FOREGROUND_SERVICE_DATA_SYNC` (Play Asset Delivery) and the app's own signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (AndroidX), and a Play listing shows that merged set. `scripts/check-permission-claims.py` holds the published list to the manifest that ships

## Technical Security Design

For detailed threat model, security controls, and testing strategy, see the [Security Design Document](docs/06-SECURITY.md).

## Acknowledgments

We appreciate responsible disclosure and will acknowledge security researchers who report valid vulnerabilities (with your permission).
