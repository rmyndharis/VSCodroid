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
- **Storage**: app data lives in the app-private directory, including the mirrors of folders opened through the Storage Access Framework
- **Permissions**: the manifest requests four: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS`

## Technical Security Design

For detailed threat model, security controls, and testing strategy, see the [Security Design Document](docs/06-SECURITY.md).

## Acknowledgments

We appreciate responsible disclosure and will acknowledge security researchers who report valid vulnerabilities (with your permission).
