# VSCodroid — Software Planning Documentation

## Document Index

### Planning Suite — written 2026-02-10, kept as history

Each of these still carries `**Version**: 1.0-draft` and `**Date**: 2026-02-10` in its own header.
They record what was planned, not what was built — see [Document Status](#document-status).

| # | Document | Description | Audience |
|---|----------|-------------|----------|
| 01 | [Product Requirements (PRD)](./01-PRD.md) | Vision, goals, user personas, feature scope | Everyone |
| 02 | [Software Requirements (SRS)](./02-SRS.md) | Functional & non-functional requirements | Developers, QA |
| 03 | [Architecture Design](./03-ARCHITECTURE.md) | System architecture, components, patterns, ADRs | Tech leads, Developers |
| 04 | [Technical Specification](./04-TECHNICAL_SPEC.md) | Implementation details, protocols, build system | Developers |
| 05 | [API & Interface Spec](./05-API_SPEC.md) | WebView bridge, server API, extension interface | Developers |
| 06 | [Security Design](./06-SECURITY.md) | Threat model, security controls, data protection | Everyone |
| 07 | [Testing Strategy](./07-TESTING_STRATEGY.md) | Test plan, types, environments, coverage | QA, Developers |
| 08 | [Risk Assessment](./08-RISK_MATRIX.md) | Risk identification, analysis, mitigation | Project leads |
| 09 | [Development Guide](./09-DEVELOPMENT_GUIDE.md) | Superseded by `CONTRIBUTING.md`; a pointer at where each topic lives now | Developers |
| 10 | [Release Plan](./10-RELEASE_PLAN.md) | CI/CD, versioning, Play Store, rollout | DevOps, Project leads |
| 11 | [Glossary](./11-GLOSSARY.md) | Terms, acronyms, technology definitions | Everyone |
| 12 | [Implementation Plan](./12-IMPLEMENTATION_PLAN.md) | Week-by-week task breakdown, dependencies, checkpoints | Project leads, Developers |

### Maintained References

Kept current against the shipping app rather than frozen at plan time.

| Document | Description | Audience |
|----------|-------------|----------|
| [User Guide](./USER_GUIDE.md) | Using the app: editor, terminal, extensions, SSH, toolchains | Users |
| [Device Test Checklist](./DEVICE_TEST_CHECKLIST.md) | Manual on-device pass, run after the automated tests | QA |
| [Legal Notices](./LEGAL_NOTICES.md) | Licenses and attribution for everything redistributed | Everyone |
| [Privacy Policy](./PRIVACY_POLICY.md) | What the app collects, and what it does not | Everyone |

## Reading Order

**New to the project?** Read in this order:
1. PRD (understand what we're building)
2. Architecture (understand how it works)
3. Glossary (if any terms are unfamiliar)
4. [`../CONTRIBUTING.md`](../CONTRIBUTING.md) (to start contributing — the Development Guide here is planning-era)

**Technical deep-dive?**
1. Architecture → Technical Spec → API Spec

**Planning & management?**
1. PRD → SRS → Risk Assessment → Release Plan

## Related Files

- [`../README.md`](../README.md) — Project overview: features, requirements, building from source
- [`../MILESTONES.md`](../MILESTONES.md) — Development milestones (M0–M6)
- [`../CONTRIBUTING.md`](../CONTRIBUTING.md) — Contribution guidelines, project structure, build scripts
- [`../CHANGELOG.md`](../CHANGELOG.md) — Change log (Keep a Changelog format)
- [`../SECURITY.md`](../SECURITY.md) — Security vulnerability reporting policy

## Document Status

Status is expressed by which table above a document sits in, not by a per-document row here.

The **planning suite** (01–12) was written before the app existed and is kept for its reasoning, not
as a description of the build. Where one contradicts the code, the code is right. Two forms of note
appear in them, and they mean different things:

- **A banner at the top** (01, 03, 04, 07, 08, 12, and on §2 of 10, which is stale where the
  rest of that document is not) says the document is a dated record and names what has since
  overtaken it. The body below a banner is mostly left as written, on purpose: a plan, an ADR or a
  risk score is a record of what was thought at the time, and editing it would destroy the record
  rather than update it.
- **A pointer** (09) replaces the body outright. That document was procedure rather than
  reasoning: setup commands, gulp tasks, a branch strategy. Commands that no longer run leave no
  record to preserve, only something to copy by mistake, so the instructions were removed and what
  is left says where each topic lives now.
- **A correction inside the text**, a struck-through clause with what shipped beside it (02), or a
  rewritten definition (11), is used where a document's job is to describe rather than to
  remember. 05 and 06 are corrected the same way and carry no banner, because they have been kept
  against the code rather than frozen.

Neither note is a guarantee about the sentence next to it. The code is still the only source of
truth.

The **maintained references** are the unnumbered documents: the User Guide and Privacy Policy that
ship to users, the Legal Notices recording what is redistributed, and the device test checklist run
by hand on a real device.

There is deliberately no "last updated" column. The one that stood here read `2026-02-10` for all
eleven rows while several of the documents it listed had already changed, because a date copied into
a table is one edit away from being wrong and nothing makes it fail loudly. Version control already
holds the answer exactly:

```bash
git log -1 --format='%cd  %s' --date=short -- docs/04-TECHNICAL_SPEC.md
```

For what the code does today, read the code.
