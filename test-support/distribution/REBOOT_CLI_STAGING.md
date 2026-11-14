# Installer reboot and user-profile proof

Run this proof from a disposable VM console. Take a snapshot before starting
and keep the disk across restarts. Use a dedicated standard account named
`tuprel-reboot`. The scripts neither provision accounts nor reboot the guest.
Use the VM console to restart and sign in. Export evidence before restoring
the snapshot. Use test keys and synthetic consumer projects.

## Prepare two guests

Use one Windows x64 VM with a desktop console and one Linux x64 VM with a
local console. Attach persistent virtual disks with NTFS and ext4 respectively.
Pin the same PR commit in both checkouts. Provision the standard local test
account through the guest administrator, then sign in as `tuprel-reboot` for
fixture preparation and all harness steps. Keep the checkout, fixture and
evidence on the guest disk. Take the initial snapshot after provisioning and
before preparing the fixture. Keep a separate operator log for the commit,
guest OS version, filesystem, console logoff and restart times, and step exit
codes. Use the guest's normal restart operation for each restart row.

- Windows x64 with a local NTFS profile, PowerShell 7, a Java 21 JDK in
  an ASCII path, and the MSVC x64 toolchain. Use the normal execution policy
  for the installed launcher. Run the harness using a script source permitted
  by that policy. Keep a pre-existing user PATH entry and record its type.
  Choose an unused test profile without competing Tuprel installations.
- Linux x64 with a local ext4 home, Bash, PowerShell 7, a Java 21 JDK and GCC.
  Prepare mode-0600 `.bashrc` and `.bash_profile` files containing only
  comments and blank lines in the test account. Leave a distinct comment in
  `.bash_login` and `.profile` to verify that Bash selects only the first
  login file. Set the disposable home to mode 0700. The supported profile
  requires compatible directory ownership and modes along the path.

From the checkout in the guest, set `JAVA_HOME` to the installed JDK and run:

```powershell
./test-support/distribution/Verify-SignedCli.ps1 -PrepareRebootFixture
```

Keep the reported absolute fixture path across sessions. Preparation builds
the verifier and two signed archives. A freshly generated test key authorizes
only these fixtures. The bootstrap and native library are trusted test inputs.

## Execute in order

In each step set `$fixture` to the reported path. Use a clean terminal from
the guest desktop or console. Leave PATH inherited from the session.

```powershell
./test-support/distribution/Verify-RebootCli.ps1 -Fixture $fixture -DisposableGuest -Step install
```

| Step | Start condition | Proof |
|---|---|---|
| `install` | Fresh snapshot | Authenticate and install A. The pointer is absent. |
| `configure` | Restart | The pointer is still absent. Activate A and persist PATH. On Windows, record the registry value and type and the notification result separately. |
| `propagation` | Same boot and session, open a new terminal via Explorer on Windows | Resolve Tuprel from the inherited environment. Verify the version and check command shadowing. Record any failure even when the broadcast returned success. |
| `relogin` | Sign out and sign back in without restarting | Resolve the authorized version, then initialize and generate a consumer outside the checkout. |
| `prepare-update` | Restart | Verify A and install B beside it. Interrupt the JVM after creating the activation candidate. |
| `activate` | Restart | Verify that A remains active and the candidate bytes survived. Explicitly activate B. |
| `rollback` | Restart | Verify B. Refuse an ordinary downgrade and rollback to a revoked A. Restore the fixture policy and explicitly roll back to authorized A. |
| `interrupt-removal` | Restart | Verify A. Refuse active removal, remove inactive B and reinstall it. Activate B and interrupt removal of inactive A. Add unknown and modified fixture files. |
| `finish` | Restart | Verify B and refuse unsafe removal. Preserve both fixture files in the evidence directory, restore the original test bytes, resume the scoped removal and undo the PATH change. Verify original registry value and type or original startup file hashes. |
| `verify-removal` | Same boot and session, open a new terminal via Explorer on Windows | Verify that neither command resolver finds Tuprel after PATH removal. |

On Windows, repeat `Get-Command tuprel -All` and `where.exe tuprel` after a
final sign-out/sign-in and save the output. Capture the OS console restart
events and attach them to the guest evidence when sharing results.

Every step records an intent and creates a success record only after its
assertions complete. Check records under `reboot-evidence` for the same machine
and account across different boots, and the same boot with a different
Windows logon at `relogin`. Treat an intent without success as unresolved.
Inspect and export evidence. Restore the VM snapshot before repeating the
scenario. Keep the account's registry or profile data private.

Before restoring either snapshot, collect the evidence directory and the
operator log. Record SHA-256 hashes of the fixture manifests and signatures
and the public key fingerprint from the fixture, plus the hash of the exported
evidence archive. Review these records alongside the OS restart events:

- `configure` records the original registry value/type or Bash profile
  hashes. Compare the Windows notification result with the actual command
  resolution and version in `propagation` and `relogin`.
- Compare the active pointer hash and authorized version in `prepare-update`,
  `activate`, `rollback` and `interrupt-removal`. The `rollback` record
  contains the reverified selected version and both refusal outcomes.
- `finish` records PATH restoration and hashes of the preserved foreign and
  modified files. `verify-removal` records absence of the launcher in a
  fresh terminal. Save the Windows command resolver results after the final
  sign-in separately.

Keep the raw archive private because it contains the test SID or UID, guest
identifier, paths and original user PATH. Share a redacted matrix with step
results and digest references. Keep failures and unresolved intents in the
matrix, including failures that occur before the harness creates an intent.

## Evidence and limitations

| Gate | Required evidence |
|---|---|
| Windows/NTFS | Same-session Explorer propagation, fresh sign-in, native registry value and type, both command resolvers, and successful steps across Windows boots |
| Linux/ext4 | Interactive and login Bash, resolved path, original profile hashes, and successful steps across distinct kernel boot IDs |
| Recovery | Committed pointer and signed version after each restart, unchanged abandoned candidate, and retained unknown and modified files |

The reboot matrix remains pending until both guest transcripts exist.
Normal restart allows the OS to flush writes. For the recovery phase, use
disposable disks and a hypervisor-controlled abrupt power cut around candidate
write, rename and removal boundaries. Reopen the disk, verify signed bytes
and receipts, and record any ambiguous outcome before any explicit recovery
action. An interrupted process or clean reboot cannot supply that evidence.
