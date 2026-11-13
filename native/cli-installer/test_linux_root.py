"""Exercise directory identity and write confinement on a disposable local volume."""

import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import stat
import subprocess
import sys
import tempfile


def invoke(binary, root, name="probe"):
    return subprocess.run(
        [binary, str(root)], input=name + "\n", capture_output=True, text=True,
        timeout=10, check=False,
    )


def verify(binary, workspace):
    root = workspace / "installation with spaces"
    root.mkdir(mode=0o700)
    outside = workspace / "outside"
    outside.mkdir(mode=0o700)
    sentinel = outside / "sentinel"
    sentinel.write_bytes(b"preserve")
    alias = workspace / "alias"
    alias.symlink_to(root, target_is_directory=True)
    (root / "link").symlink_to(sentinel)
    os.link(sentinel, root / "hard-link")

    first = invoke(binary, root)
    assert (first.returncode, first.stdout) == (0, "READY\n")
    assert stat.S_IMODE((root / "probe").stat().st_mode) == 0o600
    for name in ("probe", "link", "hard-link", "", ".", "..", "../outside/sentinel", str(sentinel)):
        result = invoke(binary, root, name)
        assert (result.returncode, result.stdout) == (2, "READY\n"), name
    assert sentinel.read_bytes() == b"preserve"
    assert (root / "probe").read_bytes() == b"installer-root-proof\n"

    with ThreadPoolExecutor(max_workers=4) as workers:
        results = list(workers.map(lambda _: invoke(binary, root, "contended"), range(4)))
    assert sorted(result.returncode for result in results) == [0, 2, 2, 2]
    assert (root / "contended").read_bytes() == b"installer-root-proof\n"

    for path in (alias, str(workspace) + "/./" + root.name, str(root) + "/",
                 str(workspace) + "//" + root.name, alias / "child", "/"):
        result = invoke(binary, path)
        assert (result.returncode, result.stdout) == (2, ""), path

    root.chmod(0o750)
    result = invoke(binary, root)
    assert (result.returncode, result.stdout) == (2, "")
    root.chmod(0o700)
    workspace.chmod(0o770)
    result = invoke(binary, root)
    assert (result.returncode, result.stdout) == (2, "")
    workspace.chmod(0o700)

    # The held descriptor still identifies the validated object after pathname substitution.
    retained = workspace / "retained"
    with subprocess.Popen([binary, str(root)], stdin=subprocess.PIPE,
                          stdout=subprocess.PIPE, text=True) as process:
        try:
            assert process.stdout.readline() == "READY\n"
            root.rename(retained)
            root.symlink_to(outside, target_is_directory=True)
            process.communicate("held-descriptor\n", timeout=10)
            assert process.returncode == 0
        finally:
            if process.poll() is None:
                process.kill()
    assert (retained / "held-descriptor").is_file()
    assert not (outside / "held-descriptor").exists()

    root.unlink()
    root.mkdir(mode=0o700)
    subprocess.run(["sudo", "-n", "chown", "0", str(root)], check=True, timeout=10)
    try:
        result = invoke(binary, root)
        assert (result.returncode, result.stdout) == (2, "")
    finally:
        subprocess.run(["sudo", "-n", "chown", str(os.getuid()), str(root)], check=True, timeout=10)


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="installer-root-", dir=sys.argv[2]) as fixture:
        verify(sys.argv[1], Path(fixture))
    print("Verified install root identity and descriptor-relative writes")
