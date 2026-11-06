# Native migration rename helper

Internal C2.2 component. The Java CLI continues to offer preview. The native
component has one mutating operation. Java authenticates the installation,
stages the migration, records the attempt and reconciles the result.

Invoke the helper by its absolute path with the four arguments shown here.

```text
publisher 2 <absolute-tuprel-directory> <stage-basename> <migration-basename>
```

The stage basename is `.migration-stage-` followed by 32 lowercase hex
digits. The destination is a format-v1 directory basename under
`<absolute-tuprel-directory>/migrations`. The caller validates the full
format-v1 identity and SQL bytes. The native helper handles only directories
and basenames. It requires a private staging parent and prevalidated staging
contents, with access limited to the publisher. External writers in the
catalog can race the destination, but control of the staging namespace and its
ancestors is an operational prerequisite.

The helper opens and validates the directories before returning
`READY <profile> <volume> <root> <catalog> <stage>`. All identities are
lowercase hex. Linux uses the mount ID and inode numbers. Windows uses
the NTFS volume serial and file ID bytes. A single `GO\n` frame followed by
EOF authorizes one rename attempt. Output uses LF on both platforms.
When staging is absent and the verified destination is present, the helper
reports `PRESENT <profile> <volume> <root> <catalog> <object>` and exits
without requesting authorization. The object identity is the former stage
identity and must match the receipt.

Only one helper can hold the stage at a time. Linux takes a nonblocking
exclusive lock on its directory handle. Windows denies delete sharing.
Reconciliation cannot confirm absence while another helper still holds
the stage for an attempt.

| Exit code | Final frame | Meaning |
|---|---|---|
| 0 | `MOVED` | The OS acknowledged the rename and post-operation identity matched |
| 10 | `REFUSED` | Rejected before the rename attempt |
| 11 | `COLLISION` | An existing destination prevented the move |
| 12 | `UNKNOWN` | An attempted move or subsequent identity check could not be confirmed |
| 13 | `OBSERVED` | Read-only confirmation of directory identity after a `PRESENT` frame |

Linux uses `openat2`, `statx` mount IDs and `renameat2(RENAME_NOREPLACE)`.
It requires a kernel with those interfaces and a verified ext4 or XFS mount.
The directories must belong to the current user, with group and other writes
excluded. Windows retains canonical directory handles without delete sharing,
rejects reparse points and uses `FileRenameInfo` with replacement disabled.
The destination uses the canonical volume GUID path derived from the held
catalog handle. Windows volume validation requires local
NTFS. The Java caller clears the child environment and supplies only the
version and directory arguments.

## Building and packaging

Build the source revision twice in separate directories with the same
recorded compiler, SDK and linker. Compare the binaries byte for byte and
record the source revision and toolchain alongside the package. Use
`build-linux.sh` on Linux and `build-windows.cmd` with the x64 MSVC tools on
Windows. The runtime does not require a compiler.

The optional package contains `publisher` (Linux) or `publisher.exe`
(Windows), `manifest.txt` and the raw 64-byte `manifest.ed25519`. The
manifest is ASCII with LF, including a final LF.

```text
TUPREL-NATIVE-PUBLISHER 2
os linux
arch x86_64
sha256 <64 lowercase hex digits over the complete executable>
```

Use `os windows` for the Windows build. Sign the exact manifest bytes with
an offline Ed25519 key. OpenSSL 3 accepts the following command in a
trusted build directory. [OpenSSL pkeyutl](https://docs.openssl.org/3.0/man1/openssl-pkeyutl/)

```bash
openssl pkeyutl -sign -rawin -inkey "$SIGNING_KEY" -in manifest.txt -out manifest.ed25519
```

The trust pin is the public key selected by the installer from a separate
trusted channel. The installation and its ancestors must be controlled by
the installer and outside the project. Use OS ownership and ACLs to deny
the project and its writers permission to replace the executable, install
directory, metadata and trust pin. Java rechecks signature, version, OS,
architecture, path and SHA-256 before executing by absolute path. Path-based
process creation cannot pin a Java file handle to execution. Trust therefore
depends on enforcing that ownership boundary throughout verification and
launch. CI signs fixtures with in-memory test keys.

## Receipt and recovery

Java acquires the existing create lock, creates a private sibling stage and
writes the exact v1 SQL bytes with exclusive creation. Before `GO`, it
forces the receipt file with the operation generation, migration identity,
candidate SHA-256, previous catalog digest and native object identities.
The receipt and staging are outside the v1 catalog. Keep them out of
version control and retain them for reconciliation.

`MOVED` describes the rename and identity check. It conveys no persistence
guarantee after power loss and is insufficient on its own to mark an artifact
published. The caller must rediscover the catalog and verify exact bytes.
A lost or invalid final frame after `GO` leaves `PUBLICATION_UNKNOWN`.
A receipt blocks further cooperating writes. Reconciliation checks the
same generation, previous catalog, directory identities and exact bytes.
It can confirm publication or confirm that the original stage is intact
and the destination absent. A disagreement, invalid receipt or changed
identity remains unknown. An explicit acknowledgment rechecks the result
under the same lock before retiring the receipt. It leaves both migration
and staging directories alone. A collision that leaves an externally
created destination requires manual inspection. Never retry automatically
or remove a possibly published destination.

File flushing alone does not establish directory-entry persistence on
power loss. Mount and object identities can also change across remount,
reboot or restore. Missing or inconclusive evidence after one of these
events requires operator reconciliation.

CI uses dedicated loop-backed ext4 and XFS volumes on Linux and an NTFS
fixture on Windows. It verifies same-toolchain reproducibility, preservation
of existing bytes, competing processes, an external writer after `READY`,
links and directory identity, and interruption with independent observation.
CI also runs the Java-to-native proof on each volume, including signed
installations, lost confirmation before and after the rename, substituted
objects, collisions, and rejection of tampered manifests and binaries.
These proofs cover controlled x86-64 fixtures. User-facing activation
requires an approved installation and trust configuration.
