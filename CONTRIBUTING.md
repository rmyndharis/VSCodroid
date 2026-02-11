# Contributing to VSCodroid

First off, thank you for considering contributing to VSCodroid! 🎉

This document provides guidelines and information to make the contribution process smooth and effective for everyone involved.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [How Can I Contribute?](#how-can-i-contribute)
- [Development Setup](#development-setup)
- [Pull Request Process](#pull-request-process)
- [Style Guidelines](#style-guidelines)
- [Commit Messages](#commit-messages)
- [Issue Guidelines](#issue-guidelines)

## Code of Conduct

This project adheres to the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code. Please report unacceptable behavior via the channels listed in the Code of Conduct.

## How Can I Contribute?

### 🐛 Reporting Bugs

Before creating a bug report, please check [existing issues](https://github.com/rmyndharis/VSCodroid/issues) to avoid duplicates.

When filing a bug, include:
- **Device model** and **Android version**
- **WebView version** (Settings → Apps → Android System WebView)
- **Steps to reproduce** the issue
- **Expected behavior** vs **actual behavior**
- **Screenshots or screen recordings** if applicable
- **Logs** from the app's debug console (if available)

### 💡 Suggesting Features

Feature requests are welcome! Please:
- Use the [Feature Request template](https://github.com/rmyndharis/VSCodroid/issues/new?template=feature_request.md)
- Describe the problem your feature would solve
- Explain your proposed solution
- Note any alternatives you've considered

### 📖 Improving Documentation

Documentation improvements are always welcome — from fixing typos to adding new guides. No contribution is too small.

### 🔧 Contributing Code

1. Look for issues labeled [`good first issue`](https://github.com/rmyndharis/VSCodroid/labels/good%20first%20issue) or [`help wanted`](https://github.com/rmyndharis/VSCodroid/labels/help%20wanted)
2. Comment on the issue to let others know you're working on it
3. Follow the [Development Setup](#development-setup) and [Pull Request Process](#pull-request-process)

## Development Setup

### Prerequisites

- **Android Studio** latest stable (Ladybug or later)
- **Android NDK** r27+
- **JDK** 17+
- **Node.js** 20 LTS (for building VS Code components)
- **Yarn** 1.x Classic (for VS Code build)
- **Python** 3.x (for node-gyp)
- **Git**

### Getting Started

```bash
# 1. Fork the repository on GitHub

# 2. Clone your fork
git clone https://github.com/<your-username>/VSCodroid.git
cd VSCodroid

# 3. Add upstream remote
git remote add upstream https://github.com/rmyndharis/VSCodroid.git

# 4. Create a feature branch
git checkout -b feature/my-awesome-feature

# 5. Open in Android Studio and sync Gradle
```

### Project Structure

```
VSCodroid/
├── android/                # Android application
│   ├── app/
│   │   ├── src/main/kotlin/com/vscodroid/  # Kotlin source
│   │   ├── src/main/res/                   # Android resources
│   │   ├── src/main/assets/                # VS Code server + web client
│   │   └── src/main/jniLibs/               # Native binaries (.so)
│   └── build.gradle.kts
├── server/                 # code-server fork (git submodule)
├── patches/                # VS Code / code-server patches
│   ├── code-server/        # Inherited patches
│   └── vscodroid/          # Custom patches
├── toolchains/             # Cross-compilation scripts
├── scripts/                # Build and utility scripts
├── test/                   # Test suites and fixtures
├── docs/                   # Project documentation
├── CLAUDE.md              # AI assistant context
├── CONTRIBUTING.md        # This file
├── LICENSE                # MIT License
└── README.md              # Project overview
```

### Building

```bash
# Build debug APK
cd android && ./gradlew assembleDebug

# Run tests
cd android && ./gradlew test

# Run lint checks
cd android && ./gradlew lint
```

## Pull Request Process

1. **Update your fork** with the latest upstream changes:
   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

2. **Make your changes** in a dedicated branch

3. **Test your changes** thoroughly:
   - Run the existing test suite
   - Test on at least one physical device (if possible)
   - Verify no regressions in core functionality

4. **Submit your Pull Request**:
   - Use a clear, descriptive title
   - Fill out the PR template completely
   - Reference any related issues (e.g., `Fixes #123`)
   - Include screenshots/recordings for UI changes

5. **Address review feedback** promptly and push updates

### PR Requirements

- [ ] Code follows the project's style guidelines
- [ ] Tests pass locally
- [ ] New code has appropriate test coverage
- [ ] Documentation updated (if applicable)
- [ ] Commit messages follow conventions
- [ ] No unrelated changes included

## Style Guidelines

### Kotlin

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use `ktlint` for formatting
- Prefer `val` over `var` where possible
- Use meaningful variable and function names
- Add KDoc comments for public APIs

### TypeScript / JavaScript

- Follow the existing VS Code codebase style
- Use TypeScript where possible
- Use ES6+ features

### General

- Keep functions small and focused
- Write self-documenting code
- Add comments for non-obvious logic
- Prefer composition over inheritance

## Commit Messages

We follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### Types

| Type | Description |
|---|---|
| `feat` | A new feature |
| `fix` | A bug fix |
| `docs` | Documentation changes |
| `style` | Code style changes (formatting, no logic change) |
| `refactor` | Code refactoring (no feature or fix) |
| `perf` | Performance improvements |
| `test` | Adding or updating tests |
| `build` | Build system or dependency changes |
| `ci` | CI/CD configuration changes |
| `chore` | Other changes (e.g., tooling, configs) |

### Examples

```
feat(terminal): add tmux session management
fix(webview): resolve keyboard overlap on Android 14
docs(readme): add build instructions for Windows
perf(server): lazy-load language servers on demand
```

## Issue Guidelines

### Labels

- `bug` — Something isn't working
- `enhancement` — New feature or request
- `documentation` — Documentation improvements
- `good first issue` — Good for newcomers
- `help wanted` — Extra attention is needed
- `question` — Further information is requested
- `wontfix` — This will not be worked on
- `duplicate` — This issue already exists

## Questions?

If you have questions about contributing, feel free to:
- Open a [Discussion](https://github.com/rmyndharis/VSCodroid/discussions)
- Ask in an existing issue

---

Thank you for helping make VSCodroid better! 🚀
