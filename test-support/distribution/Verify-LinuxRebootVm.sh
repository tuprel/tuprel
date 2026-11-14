#!/usr/bin/env bash
set -euo pipefail

# This orchestrator runs only inside the dedicated GitHub-hosted Linux job.
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]]
[[ "${GITHUB_REPOSITORY:-}" == tuprel/tuprel && "${GITHUB_EVENT_NAME:-}" == pull_request ]]
work="$(mktemp -d "${RUNNER_TEMP:?}/tuprel-guest.XXXXXXXX")"
evidence="$RUNNER_TEMP/tuprel-reboot-linux-evidence"
mkdir -m 700 "$evidence"
pid=''
cleanup() {
    local status=$?
    if (( status != 0 )); then
        printf 'FAIL: %s\n' "${phase:-capability probe}" | tee "$evidence/failure.txt"
        if [[ -f "$work/qemu.log" ]]; then
            sed -e "s|$work|<guest-work>|g" "$work/qemu.log" | head -c 2048 || true
        fi
        for log in qemu serial apt fixture step; do
            if [[ -f "$work/$log.log" ]]; then
                printf '%s log captured privately (%s bytes)\n' "$log" "$(wc -c < "$work/$log.log")"
            fi
        done
    fi
    if [[ -n "$pid" ]]; then
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
    rm -rf -- "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

kvm_mode=tcg
if [[ -c /dev/kvm && ( ! -r /dev/kvm || ! -w /dev/kvm ) ]]; then
    sudo setfacl -m "u:$(id -u):rw" /dev/kvm || true
fi
if [[ -r /dev/kvm && -w /dev/kvm ]]; then
    # KVM_GET_API_VERSION succeeds only if this runner can use the device.
    if python3 -c 'import fcntl, os; assert fcntl.ioctl(os.open("/dev/kvm", os.O_RDWR), 0xAE00) == 12'; then
        kvm_mode=kvm
    fi
fi
printf 'accelerator=%s\nkvm_device=%s\n' "$kvm_mode" "$(test -c /dev/kvm && echo present || echo absent)" |
    tee "$evidence/capability.txt"

phase='download and verify Ubuntu image'
image=ubuntu-24.04-minimal-cloudimg-amd64.img
origin=https://cloud-images.ubuntu.com/minimal/releases/noble/release-20261105
curl --fail --silent --show-error --proto '=https' --retry 2 --max-time 150 "$origin/$image" -o "$work/base.img"
printf '%s  %s\n' 406630c08b5e545fcc7209854b129d47e3af822db128c9f19336593b5a2d3fe0 "$work/base.img" |
    sha256sum --check --status
qemu-img create -q -f qcow2 -F qcow2 -b "$work/base.img" "$work/guest.qcow2" 10G
ssh-keygen -q -t ed25519 -N '' -f "$work/client"
ssh-keygen -q -t ed25519 -N '' -f "$work/guest-host"
port=49231
printf '[127.0.0.1]:%s %s\n' "$port" "$(cat "$work/guest-host.pub")" > "$work/known_hosts"
cat > "$work/cloud.yaml" <<EOF
#cloud-config
ssh_deletekeys: true
ssh_keys:
  ed25519_private: |
$(sed 's/^/    /' "$work/guest-host")
  ed25519_public: $(cat "$work/guest-host.pub")
ssh_pwauth: false
disable_root: true
users:
  - name: fixture-admin
    shell: /bin/bash
    sudo: 'ALL=(ALL) NOPASSWD:ALL'
    ssh_authorized_keys:
      - $(cat "$work/client.pub")
  - name: tuprel-reboot
    shell: /bin/bash
    ssh_authorized_keys:
      - $(cat "$work/client.pub")
EOF
cloud-localds "$work/seed.img" "$work/cloud.yaml"
qemu-system-x86_64 -accel "$kvm_mode" -m 4096 -smp 2 -display none -monitor none \
    -serial "file:$work/serial.log" -drive "file=$work/guest.qcow2,if=virtio,format=qcow2" \
    -drive "file=$work/seed.img,if=virtio,format=raw,readonly=on" \
    -nic "user,model=virtio-net-pci,hostfwd=tcp:127.0.0.1:$port-:22" >"$work/qemu.log" 2>&1 &
pid=$!

ssh_options=(-i "$work/client" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=3
    -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$work/known_hosts" -o LogLevel=ERROR)
guest() {
    timeout 300 ssh -p "$port" "${ssh_options[@]}" "$@"
}
admin() { guest fixture-admin@127.0.0.1 "$@"; }
tester() { guest tuprel-reboot@127.0.0.1 "$@"; }

wait_for_boot() {
    local previous="${1:-}" limit="${2:-120}" boot deadline=$((SECONDS + ${2:-120}))
    while (( SECONDS < deadline )); do
        kill -0 "$pid" 2>/dev/null || return 1
        if boot="$(admin cat /proc/sys/kernel/random/boot_id 2>/dev/null)" &&
            [[ "$boot" =~ ^[0-9a-f-]{36}$ && "$boot" != "$previous" ]]; then
            printf '%s\n' "$boot"
            return 0
        fi
        sleep 3
    done
    echo "No distinct guest boot within ${limit}s ($kvm_mode)" >&2
    return 1
}
restart() {
    # Schedule the reboot inside the guest after the SSH command has returned.
    admin sudo systemd-run --quiet --on-active=3s /usr/bin/systemctl reboot
    wait_for_boot "$1" "$2"
}

boot_limit=120
[[ "$kvm_mode" == kvm ]] || boot_limit=90
phase='first guest boot'
before="$(wait_for_boot '' "$boot_limit")"
admin cloud-init status --wait >/dev/null
tester 'test "$(findmnt -n -o FSTYPE --target "$HOME")" = ext4 &&
    systemd-detect-virt --vm --quiet && test "$(id -u)" -ne 0 &&
    printf "%s\n" "tuprel reboot persistence probe" > "$HOME/reboot-marker" && sync'
digest="$(tester 'sha256sum "$HOME/reboot-marker" | cut -d" " -f1')"
phase='guest reboot and ext4 marker verification'
after="$(restart "$before" "$boot_limit")"
[[ "$digest" =~ ^[0-9a-f]{64}$ && "$digest" == "$(tester 'sha256sum "$HOME/reboot-marker" | cut -d" " -f1')" ]]
printf 'status=PASS\nfilesystem=ext4\nboot_before=%s\nboot_after=%s\nmarker_sha256=%s\n' \
    "$before" "$after" "$digest" | tee "$evidence/restart.txt"
if [[ "$kvm_mode" != kvm ]]; then
    echo 'BLOCKED: short software-emulation probe only' | tee "$evidence/harness-status.txt"
    exit 0
fi

phase='install guest test toolchain'
echo 'Installing test toolchain inside the guest'
admin 'sudo apt-get -o Acquire::Retries=2 update -qq &&
    sudo DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=2 install -y --no-install-recommends \
        openjdk-21-jdk-headless gcc libc6-dev libicu74 libssl3t64 zlib1g libgssapi-krb5-2' >"$work/apt.log" 2>&1
pwsh_dir="$(dirname "$(readlink -f "$(command -v pwsh)")")"
tar -C "$pwsh_dir" -cf - . | admin 'sudo mkdir /opt/tuprel-pwsh && sudo tar -xf - -C /opt/tuprel-pwsh'
git archive --format=tar HEAD | tester 'mkdir -m 700 "$HOME/checkout" && tar -xf - -C "$HOME/checkout"'
phase='prepare signed guest fixture'
tester 'chmod 700 "$HOME"; umask 077
    for file in .bashrc .bash_profile .bash_login .profile; do
        printf "# Tuprel fixture %s\n" "$file" > "$HOME/$file" || exit
        chmod 600 "$HOME/$file" || exit
    done
    cd "$HOME/checkout" &&
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 /opt/tuprel-pwsh/pwsh -NoProfile \
        -File test-support/distribution/Verify-SignedCli.ps1 -PrepareRebootFixture' >"$work/fixture.log" 2>&1

run_step() {
    local step="$1"
    phase="guest harness step $step"
    echo "Guest harness step: $step"
    if ! tester bash -s -- "$step" >"$work/step.log" 2>&1 <<'GUEST'
set -euo pipefail
cd "$HOME/checkout"
shopt -s nullglob
fixtures=("$HOME"/tuprel\ signed\ cli\ café\ *)
[[ "${#fixtures[@]}" == 1 ]]
exec /opt/tuprel-pwsh/pwsh -NoProfile -File test-support/distribution/Verify-RebootCli.ps1 \
    -Fixture "${fixtures[0]}" -DisposableGuest -Step "$1"
GUEST
    then
        echo "FAIL: harness step $step" | tee "$evidence/harness-status.txt"
        return 1
    fi
    # Copy only allowlisted proof fields. Guest paths, account and machine IDs stay private.
    tester "cat -- \"\$HOME\"/tuprel*/reboot-evidence/$step.json" |
        jq '{step, boot, filesystem, recordedAtUtc, verifiedVersion, activatedVersion, pointerSha256,
             candidateDigest, rollback, removal, launcherAbsentFromNewTerminal,
             bashPathEntries: .bashResolution.pathEntriesPerShell,
             profileDigests: [.pathRestored.originalProfiles[]?.digest]}' >"$evidence/$step.json"
}

current="$after"
run_step install
for step in configure propagation relogin prepare-update activate rollback interrupt-removal finish verify-removal; do
    case "$step" in
        propagation|relogin|verify-removal) ;;
        *) current="$(restart "$current" "$boot_limit")" ;;
    esac
    run_step "$step"
done
echo 'PASS: full Linux/ext4 harness' | tee "$evidence/harness-status.txt"
