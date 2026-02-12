<p align="center">
  <img src="docs/logo/vscodroid.png" alt="VSCodroid Logo" width="150">
</p>

<h1 align="center">VSCodroid</h1>

<p align="center">
  <strong>VS Code, natively on Android.</strong><br>
  A full-featured IDE on your phone — not a toy editor, not a cloud IDE.<br>
  Download. Open. Code.
</p>

<p align="center">
  <a href="https://github.com/rmyndharis/VSCodroid/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/rmyndharis/VSCodroid/ci.yml?style=flat-square&logo=github&label=CI" alt="CI"></a>
  <a href="https://github.com/rmyndharis/VSCodroid/releases"><img src="https://img.shields.io/github/v/release/rmyndharis/VSCodroid?style=flat-square&logo=github" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/rmyndharis/VSCodroid?style=flat-square" alt="License"></a>
  <a href="https://github.com/rmyndharis/VSCodroid/stargazers"><img src="https://img.shields.io/github/stars/rmyndharis/VSCodroid?style=flat-square&logo=github" alt="Stars"></a>
  <a href="https://github.com/rmyndharis/VSCodroid/issues"><img src="https://img.shields.io/github/issues/rmyndharis/VSCodroid?style=flat-square" alt="Issues"></a>
</p>

<p align="center">
  <a href="#-features">Features</a> •
  <a href="#-architecture">Architecture</a> •
  <a href="#-getting-started">Getting Started</a> •
  <a href="#-building-from-source">Build</a> •
  <a href="#-contributing">Contributing</a> •
  <a href="#-license">License</a>
</p>

---

## ✨ Features

- **Real VS Code** — Monaco Editor, Workbench UI, Command Palette, and all the features you know
- **Extension Support** — Install themes, linters, language packs, and more from [Open VSX](https://open-vsx.org)
- **Integrated Terminal** — Full bash terminal with real PTY support (vim, tmux, readline all work)
- **Batteries Included** — Node.js, Python 3, Git, npm, SSH, and essential tools bundled out of the box
- **Offline-First** — Code without an internet connection. Everything runs locally on your device
- **Mobile-Optimized** — Extra Key Row (Ctrl, Alt, Tab, Esc, arrows), touch-friendly UI, clipboard bridge
- **SSH Key Management** — Generate ed25519 keys and copy public keys from the command palette
- **Language Picker** — Select your languages, Go/Ruby/Java auto-install via Play Store
- **Dev Server Preview** — Open localhost URLs in your device's browser for web app testing

## 🏗 Architecture

VSCodroid runs a local VS Code Server instance on your Android device. The VS Code Web Client renders inside a WebView, and the VS Code Server (Node.js) runs as a native ARM64 process — all on `localhost`.

```mermaid
flowchart TD
  subgraph APP["Android App (Kotlin)"]
    WV["WebView"]
    WEB["VS Code Web Client (vscode-web)"]
    KEY["Extra Key Row [Tab][Esc][Ctrl][Alt]"]
    NODE["Node.js Process (VS Code Server, vscode-reh, ARM64)"]
    REH["VS Code Server (vscode-reh)"]
    EXT["Extension Host"]
    TERM["Terminal (node-pty)"]
    FS["File System / Search"]
    WV --> WEB
    NODE --> REH
    REH --> EXT
    REH --> TERM
    REH --> FS
    KEY --> WV
  end
  WEB <--> LOCAL["localhost:PORT (HTTP + WebSocket)"]
  LOCAL <--> REH
```

> For full architecture details, see [Architecture Documentation](docs/03-ARCHITECTURE.md).

## 📱 Requirements

| Requirement | Minimum |
|---|---|
| Android | 13 (API 33) |
| Architecture | arm64-v8a |
| WebView | Chrome 105+ |
| RAM | 4 GB recommended |
| Storage | ~500 MB minimum (core + workspace) |

## 🚀 Getting Started

### Install from Release

1. Download from [Play Store](#) or [Releases](https://github.com/rmyndharis/VSCodroid/releases)
2. Install on your Android device
3. Open the app — core binaries extract automatically (~5-10 seconds)
4. Pick your languages (Go, Rust, Java, etc.) — they install automatically
5. Start coding! Editor, terminal, and all your tools are ready

### What's Included

| Tier | What | How |
|---|---|---|
| **Core (Base APK)** | Node.js, npm, Python 3, Git, Bash, SSH, tmux, make, ripgrep | Available immediately |
| **Toolchains (Asset Packs)** | Go, Ruby, Java | Select in Language Picker, auto-installed via Play Store |

## 🔨 Building from Source

### Prerequisites

- **Android Studio** latest stable version with Android API 36 support
- **Android NDK** r27+
- **Node.js** 20 LTS (for building VS Code)
- **Yarn** 1.x Classic (for VS Code build)
- **Python** 3.x (for node-gyp)
- **Git**

### Build Steps

```bash
# Clone the repository
git clone https://github.com/rmyndharis/VSCodroid.git
cd VSCodroid

# Build Node.js for ARM64 Android (see docs for details)
# This is a complex cross-compilation step — refer to docs/04-TECHNICAL_SPEC.md

# Build the Android app
cd android && ./gradlew assembleDebug

# Install on connected device
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

> **Note:** Building from source requires cross-compiling Node.js for ARM64 Android. See the [Development Guide](docs/09-DEVELOPMENT_GUIDE.md) for detailed instructions.

## 📦 Size Estimates

| Metric | Size |
|---|---|
| Play Store download (core) | ~150-200 MB |
| + Each toolchain (on-demand) | 20-100 MB per language |
| Installed storage (core) | ~300-400 MB |
| Installed storage (all toolchains) | ~600-800 MB |
| RAM usage (typical) | ~400-700 MB |

## 🤝 Contributing

We love contributions! Whether it's bug reports, feature requests, documentation, or code — every contribution counts.

Please read our [Contributing Guide](CONTRIBUTING.md) before submitting a Pull Request.

Quick links:
- 🐛 [Report a Bug](https://github.com/rmyndharis/VSCodroid/issues/new?template=bug_report.md)
- 💡 [Request a Feature](https://github.com/rmyndharis/VSCodroid/issues/new?template=feature_request.md)
- 📖 [Documentation](docs/)

## 📚 Documentation

| Document | Description |
|---|---|
| [Product Requirements](docs/01-PRD.md) | Vision, goals, and product scope |
| [Software Requirements](docs/02-SRS.md) | Detailed functional & non-functional requirements |
| [Architecture](docs/03-ARCHITECTURE.md) | System design and component architecture |
| [Technical Spec](docs/04-TECHNICAL_SPEC.md) | Implementation details and technical decisions |
| [API Spec](docs/05-API_SPEC.md) | Internal API and bridge interfaces |
| [Security](docs/06-SECURITY.md) | Security model and threat analysis |
| [Testing Strategy](docs/07-TESTING_STRATEGY.md) | Test plan and quality assurance |
| [Risk Matrix](docs/08-RISK_MATRIX.md) | Known risks and mitigation strategies |
| [Development Guide](docs/09-DEVELOPMENT_GUIDE.md) | How to set up and build the project |
| [Release Plan](docs/10-RELEASE_PLAN.md) | Release strategy, CI/CD, Play Store |
| [User Guide](docs/USER_GUIDE.md) | How to use VSCodroid (keyboard, terminal, extensions, SSH) |
| [Milestones](MILESTONES.md) | Development milestones M0–M6 |
| [Glossary](docs/11-GLOSSARY.md) | Terms and definitions |
| [Implementation Plan](docs/12-IMPLEMENTATION_PLAN.md) | Week-by-week task breakdown with dependencies |
| [Third-Party Attribution](NOTICE.md) | Licenses for all bundled software |
| [Privacy Policy](https://rmyndharis.github.io/VSCodroid/privacy-policy.html) | Data collection and privacy practices |

## 🔒 Security

If you discover a security vulnerability, please **do not** open a public issue. Instead, see our [Security Policy](SECURITY.md) for responsible disclosure instructions.

## ⚖️ Legal

VSCodroid is built from the MIT-licensed [Code - OSS](https://github.com/microsoft/vscode) source code. It is **not affiliated with or endorsed by Microsoft Corporation**.

- "Visual Studio Code" and "VS Code" are trademarks of Microsoft
- Uses [Open VSX](https://open-vsx.org) extension registry, not Microsoft Marketplace
- See [LICENSE](LICENSE) for full license text
- See [NOTICE.md](NOTICE.md) for third-party attribution
- See [Privacy Policy](https://rmyndharis.github.io/VSCodroid/privacy-policy.html) for data practices

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

<p align="center">
  Made with ❤️ for mobile developers everywhere by Yudhi Armyndharis and VSCodroid Community
</p>
