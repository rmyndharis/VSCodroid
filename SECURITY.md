# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| latest  | :white_check_mark: |

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability in VSCodroid, please report it responsibly.

### How to Report

**Please DO NOT open a public GitHub issue for security vulnerabilities.**

Instead, please use one of these channels:

📧 **Email**: security@vscodroid.dev
🔒 **GitHub Security Advisory**: [Report a vulnerability](https://github.com/rmyndharis/VSCodroid/security/advisories/new) (preferred for detailed reports)

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

- **Process isolation**: Node.js server runs on localhost only
- **No remote access**: No network-exposed services by default
- **Extension safety**: Extensions run in the Extension Host sandbox
- **Storage**: All data stored in app-private directory
- **Permissions**: Minimal Android permissions requested

## Technical Security Design

For detailed threat model, security controls, and testing strategy, see the [Security Design Document](docs/06-SECURITY.md).

## Acknowledgments

We appreciate responsible disclosure and will acknowledge security researchers who report valid vulnerabilities (with your permission).
