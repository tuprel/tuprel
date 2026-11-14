# Signed CLI staging

Run with PowerShell 7, a Java 21 JDK and the platform C compiler in the
source checkout. On Linux, pass an existing fixture parent on a local ext4
or XFS filesystem if the home directory uses another filesystem.

```powershell
pwsh -File test-support/distribution/Verify-SignedCli.ps1
```

The fixture builds two versioned archives, signs their version, platform and
SHA-256 digest with an ephemeral Ed25519 key, and verifies against the key
fingerprint before writing files. It uses a caller-controlled private temporary
directory. A signed archive is read once for verification and extraction.
Only the expected launchers and library paths are accepted. The installer
also checks each normalized destination against the fresh staging root
immediately before writing.

The fixture packages a standalone verifier JAR and invokes it from a
temporary directory outside the checkout. The JAR can verify, install and
remove the authenticated version. The verification key is a bounded Ed25519
SubjectPublicKeyInfo DER file. Supply its SHA-256 fingerprint separately from
the archive. The fixture key is ephemeral.

Install and removal hold the same lock. Installation writes a fresh sibling,
checks its bytes and then renames it atomically on the same filesystem. An
identical retry verifies the complete existing tree and returns success. A
different signed archive with the same version is refused. A receipt stores
the SHA-256 of the authenticated manifest. An abandoned staging sibling is
preserved for inspection and cannot be selected as an installation.

Removal validates the whole version before renaming it to a reserved removal
directory. A subsequent removal with the same signed inputs validates all
remaining files and resumes. Unknown or modified entries stop the operation
before any further deletion. The receipt stays until the known files and
subdirectories are gone. Installing the same version while removal is pending
is refused. An incomplete or ambiguous operation requires an explicit
follow-up invocation. Atomic visibility of the directory is separate from
durability after power loss.

The fixture halts a separate JVM after simulating the first deletion. It also
forces a Windows sharing violation or a Linux permission failure, restores
access, and verifies recovery from a new verifier process. It selects the two
versions and explicitly rolls back through a version-independent PowerShell
or Bash launcher. An external project in a path
with spaces runs the installed launcher for version, init, validate and
generate.

The staging root must already exist. The verifier loads the native library
built by the trusted staging bootstrap from an absolute path supplied
independently of the archive. A release bootstrap still needs to authenticate
that library and its own provenance. Install and removal require an
OS-validated root and refuse unsupported profiles. The archive currently uses
the installed Java runtime. Bundling Java also requires a security-update and
redistribution policy.

The Linux P0-02A proof exercises the native install-root primitive separately
on disposable ext4 and XFS volumes in CI. It requires a non-root effective UID,
an existing directory owned by that UID with mode 0700, and ancestors owned by
root or that UID with no group or other write permissions. It refuses symlinks,
dot components and unrecognised mount types. Creation uses an exclusive
descriptor-relative operation. If the validated directory is renamed after
opening, the write stays in the original directory. Activation will also have
to verify that the configured name still identifies the expected directory.
The proof assumes a trusted OS, mount namespace, administrator and current
UID. It does not establish a boundary against processes with the same UID.

The Windows P0-02A proof uses the process token SID and holds each directory
from a fixed local NTFS drive without delete sharing. It opens descendants and
creates new files relative to those handles, refusing reparse points, aliases
and unsafe access grants. It checks each held object's volume, file ID, owner
and DACL again before creation. The ACL policy conservatively rejects unsafe
allow entries even when a deny entry might override them. It permits creation
of unrelated sibling directories by other principals on trusted ancestors
when they lack rights to modify the held directory or delete its children.
The trusted identities are the current SID, SYSTEM, Administrators and
TrustedInstaller. The installation root itself must belong to the current SID.
The proof covers ACL and owner changes observed before creation. Administrators
and processes with the same SID are within the trust boundary.

The staging verifier keeps the validated root open during install and removal.
Linux staging paths are anchored to the held descriptor via procfs. Windows
retains the validated directory chain and creates payloads relative to checked
handles. Publication and quarantine use native no-replace renames. The
verifier compares directory identities across the rename and checks the signed
bytes again after publication. The staging test checks that an unsafe root or
missing native library is refused before the lock or payload is written.

Activation serializes with install, remove and launch. The private active
record binds the selected version to its signed manifest digest and records the
highest selected version. The launcher rechecks the selected release against a
signed staging authorization list and verifies the installed files against the
authenticated archive before invoking Java with an explicit classpath.
Rollback requires a signed rollback grant. Revoked versions and removal of
the active version are refused. The staging authorization list comes from the
trusted fixture bootstrap. Metadata freshness and rollback resistance across
key or policy rotations remain distribution work.

### P0-02D release metadata design for review

Use the four top-level TUF roles as the baseline for the release design.
The earlier single signed authorization document would give one key
authority over trust changes, freshness and artifact selection. It would
also need a custom mechanism to prevent mixing versions of metadata.
Prefer an existing TUF client after evaluating its Java 21 integration,
maintenance, dependency footprint and interoperability against a reference
repository. The existing per-bundle manifest still binds the package ZIP.
Use Targets to bind the exact ZIP and manifest bytes to the version and
platform.

| TUF role | Responsibility in the proposed distribution |
|---|---|
| Root | Establish trusted keys and signature thresholds. Routine rotation advances one version at a time and requires authorization from both the previous and successor Root thresholds. |
| Timestamp | Give online refresh a short validity window and bind the current Snapshot. Its signer has no authority to add targets. |
| Snapshot | Bind a consistent set of Targets metadata versions, lengths and hashes so an attacker cannot mix generations. |
| Targets | Authenticate package and manifest bytes, including their release and platform mapping. Tuprel policy determines which authenticated versions may be installed or run. |

See [TUF 1.0.37, roles and client workflow](https://theupdateframework.github.io/specification/v1.0.37/#detailed-client-workflow).

#### Time, enrollment and recovery

Proposed source of time and minimum Root version is an independent HTTPS
bootstrap endpoint whose identity is provisioned outside the package
repository. For each enrollment, recovery or metadata refresh, it returns
UTC time, the current minimum Root version and authenticated Root identity,
channel and profile, bound to a fresh request nonce. Verify the endpoint
identity, exact nonce and requested scope, and enforce a short deadline
measured with a monotonic clock. Use a single verified UTC sample for the
update. If TLS validation fails because the local clock is invalid, stop
and ask the operator to restore trustworthy time. An unavailable authority
blocks enrollment, recovery and mutation. A signed time or epoch value
copied from the package repository cannot stand in for a fresh response.

Require the authenticated floor and Root identity at first installation
and after loss or corruption of local security state. Reject a floor below
the persisted floor when a checkpoint exists. Recovery from a compromised
trust anchor requires an independently authenticated replacement anchor
and a new current checkpoint. A signature from the compromised key alone
cannot authorize recovery. After recovery, the replacement policy must
carry the applicable revocations and preserve immutable artifact identities.

For staging, propose a maximum 24-hour Timestamp lifetime, with inclusive
issue time and exclusive expiration. Other roles also require expiration
checks, with lifetimes to be validated separately. Reject authenticated time
earlier than the last trusted time. A checkpoint binds the trusted time,
Root and role versions, and authenticated metadata digests to the channel
and profile. Within a Root trust generation, lower versions or changed
bytes at the same version fail. Routine rotation and compromise recovery
must follow explicit transition rules for the affected roles. Commit the
checkpoint under the install lock before acting on the new authorization.
After an interrupted write, revalidate the committed record and require
fresh validation if it is missing, corrupt or ambiguous.

#### Operation policy

- Install, update and rollback require current authenticated metadata.
  Rollback also requires an explicit grant for the installed target. No
  operation may lower the metadata checkpoint.
- Launch uses a separate policy for the active, previously verified version.
  The offline experiment should check the installed bytes and committed
  authorization and refuse known revocations or missing security state.
  Measure the effect of an offline launch window before approving it. An
  offline client cannot discover a revocation issued after its last refresh.

#### Staging acceptance

The first fixture tests the 24-hour boundary and clock regression with a
deterministic, already authenticated time input. It neither provides the
source of time nor changes the existing staging launcher. Next, test
fresh enrollment and recovery with replayed responses, wrong scope or a
stale minimum Root version. Exercise old signed metadata, mixed generations,
revoked versions, rotation with insufficient signatures, compromise
recovery, and process interruption before and after checkpoint publication.
Measure offline launch behavior on Windows and Linux. Use ephemeral keys
and isolated fixtures. Keep the release wire format open until these
results and the selected client have been reviewed.

The commit point is one file replacement using renameat2 on Linux or
FileRenameInfo on NTFS. A refused Windows replacement can leave an inert
candidate next to the previous pointer. A new process reads only the committed
pointer and revalidates its target. The fixture terminates the activation JVM
before candidate creation, after candidate creation and immediately after the
replacement. A fresh launcher must observe the prior committed version until
replacement, then the new version. The staged candidate is preserved for
inspection. The fixture exercises process recovery. Host restart and power-loss
durability require separate evidence.

The Windows PATH staging tool changes the user registry value and a Tuprel
ownership marker in one registry transaction. It retains the PATH value type,
appends a single stable directory, and refuses pre-existing entries or
detectable shadowing. Removal is limited to the exact owned suffix and
preserves prior entries. A failed transaction is reported as refused or
unconfirmed. The staging proof builds a variant scoped to a disposable HKCU
key and starts PowerShell with the persisted value. Explorer propagation and
startup after reboot still need a separate user-profile proof.

The initial Bash PATH profile edits an existing mode-0600 startup file in a
checked home. Add requires a profile containing comments and blank lines. This
restriction keeps arbitrary Bash control flow out of automated edits. Use
`interactive` for `.bashrc` and `login` for the first existing Bash login
profile in Bash's startup order. An exact marked block adds the stable
launcher directory only when Tuprel is unresolved. Removal checks the full
block and preserves subsequent user edits outside it. Automated tests use a
disposable home and newly started Bash processes.

A local Windows proof used jdeps and jlink to produce a 56.2 MiB JDK 21
image, including a small fixture project. The image ran init, validate and
generate offline. JDBC, TLS, native integration and runtime updates still
need their own validation before a runtime can be bundled.
