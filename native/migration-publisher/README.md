# Native migration rename helper

Internal C2.2a component. The Java CLI continues to offer preview. These
sources build validation binaries and define the only native operation.
Authenticating an installed binary, writing a recoverable receipt and
reconciling an uncertain result belong to the Java caller.

Invoke the helper by its absolute path with the four arguments shown here.

```text
publisher 1 <absolute-tuprel-directory> <stage-basename> <migration-basename>
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

| Exit code | Final frame | Meaning |
|---|---|---|
| 0 | `MOVED` | The OS acknowledged the rename and post-operation identity matched |
| 10 | `REFUSED` | Rejected before the rename attempt |
| 11 | `COLLISION` | An existing destination prevented the move |
| 12 | `UNKNOWN` | An attempted move or subsequent identity check could not be confirmed |

Linux uses `openat2`, `statx` mount IDs and `renameat2(RENAME_NOREPLACE)`.
It requires a kernel with those interfaces and a verified ext4 or XFS mount.
The directories must belong to the current user, with group and other writes
excluded. Windows retains canonical directory handles without delete sharing,
rejects reparse points and uses `FileRenameInfo` with replacement disabled.
The destination uses the canonical volume GUID path derived from the held
catalog handle. Windows volume validation requires local
NTFS. The caller must reject unverified binaries and environments and clear
the child environment of database credentials.

`MOVED` describes the rename and identity check. It conveys no persistence
guarantee after power loss and is insufficient on its own to mark an artifact
published. The caller must rediscover the catalog and verify exact bytes.
A lost or invalid final frame after `GO` requires explicit reconciliation.
Never retry automatically or remove a possibly published destination.

CI uses dedicated loop-backed ext4 and XFS volumes on Linux and an NTFS
fixture on Windows. It verifies same-toolchain reproducibility, preservation
of existing bytes, competing processes, an external writer after `READY`,
links and directory identity, and interruption with independent observation.
Candidate profiles remain gated until the Java integration also passes.
