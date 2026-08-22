# VSCodroid Privacy Policy

**Effective Date: February 13, 2026**
**Last Updated: August 22, 2026**

## Summary

VSCodroid is a code editor that runs entirely on your Android device. **We** do not collect, transmit, or store any personal data, and there is no server of ours for anything to reach. Your code and files stay on your device unless you send them somewhere yourself, for example by pushing to a Git remote.

Three exceptions to "nothing leaves the device", all under your control and none involving us. Android's own backup service copies your editor settings to your Google account if you have backup switched on (see **Android Backup** below). Anything you ask the app to fetch (an extension, a toolchain, an `npm install`) is a request you initiated (see **Network Access**).

The third is different in kind and worth reading before you use it: the app bundles **GitHub Copilot Chat**, and once you sign in to GitHub and use it, what you ask it and the code it attaches as context go to GitHub. That is the one feature here that sends your own work to someone else. It does nothing until you sign in, and it can be disabled or uninstalled.

---

## What Data We Collect

**None.** VSCodroid does not collect any personal information, usage data, analytics, crash reports, or telemetry of any kind. There are no user accounts, no sign-up, no login, and no registration required to use the app.

## How the App Works

VSCodroid runs a local code editor server entirely on your Android device. The editor interface (VS Code Workbench) connects to this local server over `localhost` (127.0.0.1) -- your device talking to itself. No data is sent to any external server as part of normal app operation.

All code execution -- whether Node.js, Python, Bash, or any other bundled runtime -- happens 100% on your device.

The app asks for four Android permissions of its own. `INTERNET` covers the
user-initiated cases listed under **Network Access** below, and the loopback
traffic between the editor page and the local server. `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_SPECIAL_USE` keep that local server alive while you are
working. `POST_NOTIFICATIONS` is for the notification showing the server's
status.

Two more are added by libraries the app is built with, and the Play listing
shows that merged set rather than ours, so they are named here as well.
`FOREGROUND_SERVICE_DATA_SYNC` comes from Google's asset delivery library, which
is what downloads a language toolchain when you pick one. The other is a
permission the app defines for itself, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
added by AndroidX at signature protection level, which means only code signed
with the same key as this app can use it; it exists so that a receiver the app
registers while running cannot be reached by another app.

There is no permission here for location, contacts, the camera, the microphone,
the phone state, or any device identifier.

## Network Access

VSCodroid requires the INTERNET permission for the following purposes only:

### Extension Marketplace (User-Initiated)

When you browse, search for, or install extensions, the app connects to **Open VSX** (https://open-vsx.org), an open-source extension registry operated by the Eclipse Foundation. These requests are made by the VS Code Workbench running in the WebView, not by any analytics or tracking code. Open VSX has its own privacy policy at https://open-vsx.org/about.

### Signing In to a Service From the Editor (User-Initiated)

If an extension asks you to sign in (to GitHub, for example), the app hands that extension's authorisation URL to your device's browser, and the sign-in happens there rather than inside the app. VSCodroid never sees your password: it is typed into the provider's own page in the browser.

What comes back is a single opaque value that the editor itself issued and is waiting for. The app passes it to the editor page without reading, parsing or storing it, and without sending it anywhere. It is accepted only while a sign-in is plausibly in flight: the app records when it last opened a browser, and a callback arriving outside a ten-minute window from that moment is discarded rather than delivered, so another app on your device cannot inject one at will.

Any tokens the editor keeps afterwards are stored locally in the app's private storage on your device.

### GitHub Copilot Chat (Bundled, User-Initiated)

VSCodroid bundles **GitHub Copilot Chat**, published by GitHub, and the editor is configured to use it as its chat provider. The extension loads when the editor starts, and it is not something this app disables.

It has no account to work against until you sign in to GitHub, which happens through the browser flow described above, and its features are unavailable until you do.

Once you are signed in and you use it, **what you send goes to GitHub**. That means the message you type and the material the extension decides to attach as context, which normally includes code from the file you are working in and can include other parts of the project. That exchange is between you and GitHub under GitHub's terms and privacy statement (https://docs.github.com/site-policy). VSCodroid does not proxy it, read it, or keep a copy, and it is the only feature in this app that sends anything you write to a service we did not have to name elsewhere in this policy.

If you would rather it were not there, disable or uninstall **GitHub Copilot Chat** from the Extensions view; the editor works without it.

### Git Operations (User-Initiated)

When you push, pull, clone or fetch, the bundled Git client connects directly to the remote you configured: GitHub, GitLab, a self-hosted server, whatever you named. VSCodroid does not proxy these connections, add its own destinations, or record where you push.

### Toolchain Downloads (User-Initiated)

Additional programming language toolchains (Ruby, Java 17) can be downloaded on-demand. There are two delivery paths, and the app chooses between them at runtime by asking Android which package installed it:

- **Installed from the Google Play Store** (the installing package is `com.android.vending`): the toolchain arrives as a Google Play Asset Delivery pack, handled by the Google Play Store infrastructure. Google's privacy policy applies to Play Store interactions: https://policies.google.com/privacy.
- **Installed any other way** -- a sideloaded APK, a debug build, `adb install` -- or when the installing package cannot be read at all: the app downloads a ZIP over HTTPS from this project's GitHub Releases (https://github.com/rmyndharis/VSCodroid/releases), following GitHub's redirect to its release-asset host. GitHub's privacy policy applies to that download.

VSCodroid does not collect any data during either process.

### Package Installation (User-Initiated)

When you run commands like `npm install` or `pip install` in the terminal, those package managers connect to their respective registries (npmjs.com, pypi.org, etc.) to download packages. This is standard developer tooling behavior and is entirely under your control.

### SSH Connections (User-Initiated)

If you use the bundled SSH client to connect to remote servers, those connections are initiated by you and go directly to the server you specify. VSCodroid does not proxy, monitor, or log SSH connections.

### Internal Communication (Localhost Only)

The VS Code editor UI communicates with the local server process over `localhost` (127.0.0.1). This traffic never leaves your device. The server listens on the loopback interface only, so nothing on your network can reach it, and almost every route requires a token held in the app’s private storage before it will answer a request.

## Android Backup (On by Default)

VSCodroid allows Android's own backup service, so **one directory does leave the device** if you have backup enabled in your Android settings: `~/.vscodroid/data/Machine`, which holds the editor defaults VSCodroid writes for you. Settings and keybindings you change yourself are stored by the editor inside the WebView and are not in the backup. Android uploads it to your Google account, not to us. We never see it.

This is written as an allowlist, so everything not named above is excluded rather than the other way round. In particular these are **not** backed up:

- your SSH keys (`~/.ssh`) and the local server's connection token
- your projects and any folder you opened from device storage
- the bundled runtimes and any language toolchains you installed
- app preferences, deliberately: restoring those onto a device with empty storage would make the app believe setup had already run

To turn it off entirely, use Android's own control: **Settings → Google → Backup**, or the per-app backup setting your device provides. It is Android's switch, not ours.

## What We Do NOT Do

- We do **not** collect analytics or usage statistics
- We do **not** send crash reports to any external service
- We do **not** use any third-party tracking SDKs
- We do **not** serve advertisements
- We do **not** collect device identifiers, IP addresses, or location data
- We do **not** use cookies for tracking purposes
- We do **not** share any data with third parties. One thing this app ships does, and it is named rather than tucked under this line: GitHub Copilot Chat sends what you ask it, and the code it attaches as context, to GitHub, once you have signed in and used it. See the section above
- We do **not** send Microsoft telemetry. To be exact about how: the telemetry code is still in the editor build, and it is switched off and given nowhere to report to. `telemetryOptIn` is false in the product configuration applied at build time, both `telemetryOptIn` and `enableTelemetry` are set false again at every start, and the built `product.json` carries no telemetry endpoint for it to reach. Disabled and unaddressed, rather than deleted

## Data Stored on Your Device

The following data is stored locally on your device within the app's private sandbox:

- **Your project files**: Stored in the app's internal storage or in locations you grant access to
- **Copies of folders you open from device storage**: When you open a folder through the Android file picker, VSCodroid does not edit it in place. It copies the folder into the app's own storage, works on that copy, and writes your changes back to the original. So a second copy of that folder exists inside the app for as long as it stays open to you, and it is removed when you uninstall or clear app data along with everything else. Nothing about the copy leaves the device (it is excluded from backup), but you should know it is made
- **Editor settings and preferences**: Stored locally as JSON configuration files
- **Installed extensions**: Downloaded from Open VSX and stored locally
- **SSH keys**: If you generate SSH keys using the built-in tool, they are stored in the app's private `~/.ssh/` directory. They never leave your device unless you explicitly copy or use them
- **Terminal history**: Stored locally within the app
- **Extension data**: Any data created by installed extensions is stored locally

All of this data is removed when you uninstall the app or clear the app's data through Android Settings.

## Third-Party Services

VSCodroid itself includes no third-party analytics, advertising, or tracking SDKs. The third-party services it integrates are:

- **GitHub Copilot Chat**: Bundled with the app and configured as the editor's chat provider. Unused until you sign in to GitHub; from then on what you ask it and the code it attaches go to GitHub, under GitHub's terms and privacy statement. It can be disabled or uninstalled from the Extensions view.
- **Google Play Asset Delivery**: Used solely for downloading optional language toolchain packs on Play Store installs. This is a Google Play Store feature and is governed by Google's privacy policy.
- **GitHub Releases**: Used solely for downloading those same toolchain packs on installs that did not come from the Play Store. Governed by GitHub's privacy policy.

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
https://github.com/rmyndharis/VSCodroid

## Contact

If you have questions about this privacy policy, please contact us at:

- **Email**: yudhi@rmyndharis.com
- **GitHub Issues**: https://github.com/rmyndharis/VSCodroid/issues

---

_VSCodroid is built from the MIT-licensed Code - OSS source code. It is not affiliated with or endorsed by Microsoft Corporation. "Visual Studio Code" and "VS Code" are trademarks of Microsoft Corporation._
