<a id="política-de-segurança"></a>

# Security policy

Tuprel handles SQL, credentials, migration artifacts and persistent data.
A vulnerability can affect confidentiality, integrity or availability.

<a id="reportar-uma-vulnerabilidade"></a>

## Report a vulnerability privately

Use **Report a vulnerability** in the repository's
[Security tab](https://github.com/tuprel/tuprel/security).
GitHub Private Vulnerability Reporting is enabled.

Do not publish exploitable details, credentials, database contents or sensitive
logs in a public issue. If you cannot use GitHub's private reporting flow,
contact a maintainer through an already established private channel; this
project does not advertise a separate security email or response SLA.

## Supported versions

Tuprel has no stable public release or supported release branches yet.
Reports against the current source are welcome. Published version support
will be defined when releases exist.

<a id="áreas-críticas"></a>

## Relevant areas

- SQL injection, identifier validation and parameter binding.
- Credential or data exposure in errors, logs and generated artifacts.
- Migration integrity, unsafe execution and concurrent deployment.
- Filesystem traversal and code-generation ownership bypass.
- Malformed schema/code input and unsafe configuration execution.
- Vulnerable or compromised dependencies.

The contributor-facing [security requirements](docs/security/SECURITY_REQUIREMENTS.md)
describe the technical baseline. See [Contributing](CONTRIBUTING.md) for the
normal development workflow.
