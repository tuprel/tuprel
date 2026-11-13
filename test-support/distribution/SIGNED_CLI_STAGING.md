# Signed CLI staging

Run with PowerShell 7 and a Java 21 JDK in the source checkout.

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
versions and rolls back using the process PATH. An external project in a path
with spaces runs the installed launcher for version, init, validate and
generate.

The staging root must already be canonical, private and controlled by the
caller for the lifetime of the operation. A public installer also needs to
verify ownership and ACLs, manage persistent PATH safely, and obtain an
authenticated trust anchor and release channel. The archive currently uses
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
This primitive is not wired into the staging archive tool. Windows identity
validation and end-to-end installation remain separate P0-02 gates.

A local Windows proof used jdeps and jlink to produce a 56.2 MiB JDK 21
image, including a small fixture project. The image ran init, validate and
generate offline. JDBC, TLS, native integration and runtime updates still
need their own validation before a runtime can be bundled.
