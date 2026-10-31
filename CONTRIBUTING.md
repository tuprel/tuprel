<a id="contribuir-para-o-tuprel-orm"></a>

# Contributing to Tuprel

Contributions are made under the [Apache License 2.0](LICENSE).

<a id="fluxo"></a>

## Before making a change

Read the [documentation](docs/README.md) and current [execution plan](plans/PHASE_6_EXECUTION.md).
Open or reference an issue for the problem. Discuss changes to public APIs,
schema formats, module dependencies or security contracts before implementing them.
Accepted contracts and proposed designs are kept in [ADRs](docs/adr/) and [RFCs](docs/rfcs/).

Keep Java 21 as the baseline. PostgreSQL is the supported database; the core
does not depend on JPA, Hibernate or Spring. SQL values use bound parameters.
Public Java examples use explicit types.

<a id="branches-e-commits"></a>

## Branches and commits

Use a branch named for the work: `feat/`, `fix/`, `refactor/`, `docs/`,
`test/`, `chore/` or `perf/`, followed by a short English kebab-case name.
For example, `docs/public-documentation-experience`. Do not use prefixes
that identify tools, automation or authors. This applies to every phase.

Use small, cohesive commits in the form `type(scope): summary`, with an English
imperative summary. Common types are feat, fix, refactor, docs, test, chore,
perf, build and ci. Preserve published history and accurate authorship.

## Validate and open a pull request

1. Keep the change focused and add tests for behaviour and important invariants.
2. Update public guides when users need different instructions.
3. Run `./gradlew check` and relevant integration tests. PowerShell uses
   `.\gradlew.bat check`. See [Build and Test](docs/development/BUILD_AND_TEST.md)
   for the dedicated local PostgreSQL setup and isolated CI mode.
4. Run root and playground builds sequentially.
5. Describe the problem, resulting behaviour, tests, limitations and risks in the PR.
   Report failed or unexecuted checks honestly. A green CI run does not waive other gates.

Do not use H2 as proof of PostgreSQL compatibility, weaken security checks,
or remove tests to make a build pass. Use synthetic data in isolated test schemas.
Never commit local secrets, .env files, private keys, database dumps or personal data.

## Documentation Requirements

Public documentation is part of development. A user-facing change is complete
when its implementation, tests and applicable documentation are delivered in
the same pull request. Passing tests alone is not sufficient.

Update the pages affected by the change:

| Change | Documentation |
|---|---|
| Behaviour, usage or examples | [Guides](docs/guides/) |
| Commands, options, configuration or exit codes | [CLI reference](docs/reference/cli.md) |
| Public Java APIs | [Java API reference](docs/reference/java-api.md) and JavaDoc |
| Installation or onboarding | [Getting started](docs/getting-started/) |
| Errors, limitations or known problems | [Help](docs/help/) |
| Runnable examples | [Examples](examples/), kept compilable and reproducible |
| Major capabilities, primary workflow or compatibility | [README](README.md) |
| Changes relevant to users | [Changelog](CHANGELOG.md), following the [versioning policy](docs/development/VERSIONING.md) |
| New public pages | [Documentation index](docs/README.md) and relevant contextual links |

Choose the relevant pages; do not edit every document for every change. Use
clear technical English, explicit Java types and examples that match the actual
API. Verify commands, links, anchors and assets. Keep the approved README
identity, responsive diagrams, consistent badges and contextual navigation.
Do not invent output, compatibility, benchmarks or publication status.

Write documentation for the behaviour delivered by the pull request. A branch
preview does not make a feature available in the published product. Merge the
implementation and documentation together; keep planned and draft-PR features
clearly separate from what is available in main.

After integration, check the documentation against the resulting main commit.
Confirm examples and available commands, remove obsolete unavailability notices,
and retain limitations that still apply. Documentation must not contradict the
code in that revision.

For internal changes with no public impact, write **Documentation not required**
in the PR and give a short, verifiable reason. Identify the affected files or
behaviour and explain why users, public APIs, commands and examples are unaffected.
This exception does not waive tests, compatibility review, security requirements
or required CI checks.

<a id="alterações-de-arquitectura"></a>
<a id="dependências"></a>

## Architecture and dependencies

Changes to module boundaries, public APIs, schema/migration formats, compatibility
or security need an ADR. A new dependency needs a reason, license review,
maintenance/security assessment and an account of its transitive/runtime cost.

<a id="escrita-técnica-e-pull-requests"></a>

## Writing and review

Use clear technical English in public guides, JavaDoc and PRs. Internal plans,
audits and decisions use European Portuguese. Describe actual behaviour and
trade-offs; distinguish available, experimental and future work.

Do not invent test results, performance claims, compatibility or authorship.
Avoid promotional copy, repeated justifications, irrelevant process references
and automatic signatures. Comments should explain constraints that the code cannot.

<a id="segurança"></a>

## Security and support

Use [Issues](https://github.com/tuprel/tuprel/issues) for ordinary bugs and questions.
Report vulnerabilities privately through the channel in [SECURITY.md](SECURITY.md).
