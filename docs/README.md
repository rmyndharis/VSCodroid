# VSCodroid Documentation

## Document Index

### Numbered Planning Suite

Each of these carries `**Version**: 1.0-draft` and `**Date**: 2026-02-10` in its own header. That
line records when the document was first written, not when it last changed; see
[Document Status](#document-status) for what each one is today.

| # | Document | Description | Audience |
|---|----------|-------------|----------|
| 01 | [Product Requirements (PRD)](./01-PRD.md) | Vision, goals, user personas, feature scope | Everyone |
| 02 | [Software Requirements (SRS)](./02-SRS.md) | Functional & non-functional requirements | Developers, QA |
| 03 | [Architecture Design](./03-ARCHITECTURE.md) | System architecture, components, patterns, ADRs | Tech leads, Developers |
| 04 | [Technical Specification](./04-TECHNICAL_SPEC.md) | Implementation details, protocols, build system | Developers |
| 05 | [API & Interface Spec](./05-API_SPEC.md) | WebView bridge, server API, extension interface | Developers |
| 06 | [Security Design](./06-SECURITY.md) | Threat model, security controls, data protection | Everyone |
| 07 | [Testing Strategy](./07-TESTING_STRATEGY.md) | Test plan, test types, environments, milestone gates | QA, Developers |
| 08 | [Risk Assessment](./08-RISK_MATRIX.md) | Risk identification, analysis, mitigation | Project leads |
| 09 | [Development Guide](./09-DEVELOPMENT_GUIDE.md) | A pointer at where each build and contribution topic lives; the instructions are in `CONTRIBUTING.md` | Developers |
| 10 | [Release Plan](./10-RELEASE_PLAN.md) | CI/CD, versioning, Play Store, rollout | DevOps, Project leads |
| 11 | [Glossary](./11-GLOSSARY.md) | Terms, acronyms, technology definitions | Everyone |
| 12 | [Implementation Plan](./12-IMPLEMENTATION_PLAN.md) | Week-by-week task breakdown, dependencies, checkpoints | Project leads, Developers |

### Unnumbered References

Kept against the shipping app. Three of these are read by users or by reviewers rather than by
contributors.

| Document | Description | Audience |
|----------|-------------|----------|
| [User Guide](./USER_GUIDE.md) | Using the app: editor, terminal, extensions, debugging, SSH, toolchains | Users |
| [Device Test Checklist](./DEVICE_TEST_CHECKLIST.md) | Manual on-device pass, run after the automated tests | QA |
| [Legal Notices](./LEGAL_NOTICES.md) | Licenses and attribution for everything redistributed | Everyone |
| [Privacy Policy](./PRIVACY_POLICY.md) | What the app collects, and what it does not | Everyone |
| [Play Edge-to-Edge Note](./PLAY_EDGE_TO_EDGE.md) | The Play Console deprecation warning: which APIs it named, where they came from, how the bundle was measured | Developers |

## Reading Order

**New to the project?** Read in this order:
1. PRD (understand what we're building)
2. Architecture (understand how it works)
3. Glossary (if any terms are unfamiliar)
4. [`../CONTRIBUTING.md`](../CONTRIBUTING.md) (to start contributing; the Development Guide here only points at it)

**Technical deep-dive?**
1. Architecture → Technical Spec → API Spec

**Planning & management?**
1. PRD → SRS → Risk Assessment → Release Plan

## Related Files

- [`../README.md`](../README.md): project overview, features, requirements, building from source
- [`../MILESTONES.md`](../MILESTONES.md): development milestones (M0 to M6)
- [`../CONTRIBUTING.md`](../CONTRIBUTING.md): contribution guidelines, project structure, build scripts
- [`../CHANGELOG.md`](../CHANGELOG.md): change log (Keep a Changelog format)
- [`../SECURITY.md`](../SECURITY.md): security vulnerability reporting policy

## Document Status

Status is expressed by which table above a document sits in, and by whether it opens with a banner.
There is no per-document row here.

Most of the numbered suite is checked against the code and describes the build rather than the plan.
02, 05, 06, 07, 10 and 11 carry no banner and are written that way throughout. 03, 04 and 08 open
with a banner saying the same thing from the other side: where the document and the code could
disagree the code settles it, and the banner names the scripts and sources to read. 01 is written
against the code in its body, and its banner fixes the product scope to the date in its header and
then names two things the reader must not carry away wrong, the source the server is built from and
which toolchains ship.

Two things are deliberately kept as dated records under a banner, because a schedule and a pipeline
sketch have no present-tense reading. 12 is the week-by-week plan as it stood when it was written;
its banner names the build path it describes and points at `scripts/build-vscode-oss.sh` and
`patches/` for what happens now. §2 of 10 is the CI/CD sketch; the note at its head points at
`CONTRIBUTING.md`, which carries the table of steps each workflow runs, and at
`scripts/check-build-steps.py`, which `lint.yml` and `release.yml` run to fail a build whose
scripts that table has lost. The rest of 10 is maintained, §5 in particular being checked
against the manifest and the shipped strings before a submission.

09 is a pointer. It carries no instructions, only a table naming the part of `CONTRIBUTING.md`, or
the other document, that holds each topic. Commands that do not run are not a record worth keeping,
only something to copy by mistake, so the commands are gone and the table is what is left.

A banner is not a guarantee about the sentence next to it, and neither is the absence of one. The
code is still the only source of truth.

There is deliberately no "last updated" column. A date copied into a table is one edit away from
being wrong and nothing makes it fail loudly, and the `**Date**` line inside each document answers a
different question: when it was first written. Version control holds the answer exactly:

```bash
git log -1 --format='%cd  %s' --date=short -- docs/04-TECHNICAL_SPEC.md
```

For what the code does today, read the code.
