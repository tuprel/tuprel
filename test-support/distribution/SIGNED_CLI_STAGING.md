# Signed CLI staging

Run with PowerShell 7 and a Java 21 JDK in the source checkout.

```powershell
pwsh -File test-support/distribution/Verify-SignedCli.ps1
```

The fixture builds two versioned archives, signs their version, platform and
SHA-256 digest with an ephemeral Ed25519 key, and verifies against the key
fingerprint before writing files. It uses a caller-controlled private temporary
directory. A signed archive is read once for verification and extraction.
Only the expected launchers and library paths are accepted.

The fixture installs versions side by side and switches the process PATH
explicitly. It confirms the selected version and restores the previous PATH.
Scoped removal checks the installed bytes and refuses unknown files before
deleting. Each invocation assumes the private directory remains under the
same user's exclusive control. Install and removal cooperate through a lock
in the private root. An install writes a temporary sibling and selects it with
a same-filesystem atomic move after all writes finish. The fixture simulates
an abandoned install and verifies a fresh attempt from the signed inputs. The
abandoned sibling remains available for inspection. A failed removal can leave
a partially removed version. Atomic visibility of the directory is separate
from durability after power loss.

The fixture packages a standalone verifier JAR and supplies the public key
as a bounded Ed25519 SubjectPublicKeyInfo DER file. It requires a SHA-256
fingerprint obtained separately from the bundle. The verifier authenticates
the archive without installing it and rejects a mismatched fingerprint.

This is staging evidence. A public installer still needs a trusted mechanism
to distribute its verification key, a tested user-directory and ACL policy,
documented recovery and an approved release channel. The archive currently
uses the installed Java runtime. Bundling Java also requires a security-update
and redistribution policy.

A local Windows proof used jdeps and jlink to produce a 56.2 MiB JDK 21
image, including a small fixture project. The image ran init, validate and
generate offline. JDBC, TLS, native integration and runtime updates still
need their own validation before a runtime can be bundled.
