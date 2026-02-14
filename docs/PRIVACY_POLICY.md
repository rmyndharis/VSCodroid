# VSCodroid Privacy Policy

**Effective Date: February 13, 2026**
**Last Updated: February 13, 2026**

## Summary

VSCodroid is a fully offline code editor for Android. It does not collect, transmit, or store any personal data. Your code, files, and settings never leave your device unless you explicitly choose to share them (for example, by pushing code to a remote Git repository).

---

## What Data We Collect

**None.** VSCodroid does not collect any personal information, usage data, analytics, crash reports, or telemetry of any kind. There are no user accounts, no sign-up, no login, and no registration required to use the app.

## How the App Works

VSCodroid runs a local code editor server entirely on your Android device. The editor interface (VS Code Workbench) connects to this local server over `localhost` (127.0.0.1) -- your device talking to itself. No data is sent to any external server as part of normal app operation.

All code execution -- whether Node.js, Python, Bash, or any other bundled runtime -- happens 100% on your device.

## Network Access

VSCodroid requires the INTERNET permission for the following purposes only:

### Extension Marketplace (User-Initiated)

When you browse, search for, or install extensions, the app connects to **Open VSX** (https://open-vsx.org), an open-source extension registry operated by the Eclipse Foundation. These requests are made by the VS Code Workbench running in the WebView, not by any analytics or tracking code. Open VSX has its own privacy policy at https://open-vsx.org/about.

### GitHub Authentication (User-Initiated)

If you choose to sign in to GitHub from within the editor (for example, to use GitHub-related extensions), the app opens a standard OAuth flow in your system browser. VSCodroid does not store or transmit your GitHub credentials. Authentication tokens are stored locally in the app's private storage on your device.

### Toolchain Downloads (User-Initiated)

Additional programming language toolchains (Go, Rust, Java, Ruby) can be downloaded on-demand via Google Play Asset Delivery. These downloads are handled by the Google Play Store infrastructure. VSCodroid does not collect any data during this process. Google's privacy policy applies to Play Store interactions: https://policies.google.com/privacy.

### Package Installation (User-Initiated)

When you run commands like `npm install` or `pip install` in the terminal, those package managers connect to their respective registries (npmjs.com, pypi.org, etc.) to download packages. This is standard developer tooling behavior and is entirely under your control.

### SSH Connections (User-Initiated)

If you use the bundled SSH client to connect to remote servers, those connections are initiated by you and go directly to the server you specify. VSCodroid does not proxy, monitor, or log SSH connections.

### Internal Communication (Localhost Only)

The VS Code editor UI communicates with the local server process over `localhost` (127.0.0.1). This traffic never leaves your device. It is not accessible to other apps or devices on your network.

## What We Do NOT Do

- We do **not** collect analytics or usage statistics
- We do **not** send crash reports to any external service
- We do **not** use any third-party tracking SDKs
- We do **not** serve advertisements
- We do **not** collect device identifiers, IP addresses, or location data
- We do **not** use cookies for tracking purposes
- We do **not** share any data with third parties
- We do **not** use Microsoft telemetry (all Microsoft telemetry code has been removed from the VS Code source)

## Data Stored on Your Device

The following data is stored locally on your device within the app's private sandbox:

- **Your project files**: Stored in the app's internal storage or in locations you grant access to
- **Editor settings and preferences**: Stored locally as JSON configuration files
- **Installed extensions**: Downloaded from Open VSX and stored locally
- **SSH keys**: If you generate SSH keys using the built-in tool, they are stored in the app's private `~/.ssh/` directory. They never leave your device unless you explicitly copy or use them
- **Terminal history**: Stored locally within the app
- **Extension data**: Any data created by installed extensions is stored locally

All of this data is removed when you uninstall the app or clear the app's data through Android Settings.

## Third-Party Services

VSCodroid itself includes no third-party analytics, advertising, or tracking SDKs. The only third-party service integrated at the system level is:

- **Google Play Asset Delivery**: Used solely for downloading optional language toolchain packs. This is a Google Play Store feature and is governed by Google's privacy policy.

Extensions you install from Open VSX are third-party software. Each extension may have its own privacy practices. We recommend reviewing extension descriptions and privacy information before installing them.

## Children's Privacy

VSCodroid is a software development tool intended for developers. It is not directed at children under the age of 13. We do not knowingly collect any information from children. Since VSCodroid collects no data from any user, there is no risk of children's data being collected.

## Data Security

Because VSCodroid does not collect or transmit user data, there is no central database or server to secure. Your files and settings are protected by Android's app sandboxing, which prevents other apps from accessing VSCodroid's private storage. We recommend using your device's built-in security features (screen lock, encryption) to protect your data.

## Changes to This Policy

If we make changes to this privacy policy, we will update the "Last Updated" date at the top of this document. Material changes will be noted in the app's release notes on the Google Play Store. The current version of this policy is always available at our GitHub repository and on the Google Play Store listing.

## Your Rights

Since VSCodroid does not collect any personal data, there is no personal data to access, correct, delete, or port. If you want to remove all app data from your device, you can uninstall the app or clear its data through Android Settings > Apps > VSCodroid > Clear Data.

## Open Source

VSCodroid is open source software. You can review the complete source code to verify our privacy practices at:
https://github.com/anthropics/vscodroid

## Contact

If you have questions about this privacy policy, please contact us at:

- **Email**: yudhi@rmyndharis.com
- **GitHub Issues**: https://github.com/anthropics/vscodroid/issues

---

_VSCodroid is built from the MIT-licensed Code - OSS source code. It is not affiliated with or endorsed by Microsoft Corporation. "Visual Studio Code" and "VS Code" are trademarks of Microsoft Corporation._
