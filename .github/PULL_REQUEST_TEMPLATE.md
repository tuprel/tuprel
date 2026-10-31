## Change

Describe the problem and resulting behaviour. Link the relevant issue or approved design.

## Validation

List commands and results. Disclose failed, unexecuted or skipped checks and their reasons.

## Documentation

Link the guides, references, examples or other pages updated with this change.
For an internal change with no public impact, use **Documentation not required**
and give a short, verifiable justification. This does not waive other quality gates.

## Limitations and compatibility

Describe known limitations, compatibility impact and any public API/CLI changes.
State when a category is not applicable and explain why.

## Review checklist

- [ ] Implementation matches the approved scope; incomplete features are not exposed as available.
- [ ] Relevant tests and checks passed; failures, unexecuted checks and skips are disclosed and justified.
- [ ] Applicable public documentation is updated in this PR, or Documentation not required has a verifiable justification.
- [ ] New or changed examples compile or run reproducibly; if no examples are affected, the reason is stated.
- [ ] Compatibility is reviewed, including the Java 21 baseline and affected platforms.
- [ ] Existing security requirements are met; commits, logs and assets contain no secrets or sensitive data.
- [ ] Known limitations and remaining validation gaps are documented accurately.
- [ ] Public API/CLI changes are reflected in references and any required approved ADRs; otherwise no public contract change is stated.
- [ ] Required CI checks, CodeQL and Dependency Review pass on the current HEAD; a green CI result does not replace the reviews above.
