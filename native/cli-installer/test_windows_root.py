"""Run install-root probes on a disposable, real NTFS directory."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import ctypes
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def run(*args):
    return subprocess.run(args, capture_output=True, timeout=15, check=True)


def invoke(binary, root, name="probe", *, report=False):
    result = subprocess.run(
        [binary, str(root), name], input=b"\n", capture_output=True, timeout=15, check=False,
    )
    if report and result.returncode:
        print(result.stderr.decode("ascii", errors="replace"), file=sys.stderr)
    return result.returncode, result.stdout.splitlines()


@contextmanager
def opened(binary, root, name):
    with subprocess.Popen([binary, str(root), name], stdin=subprocess.PIPE,
                          stdout=subprocess.PIPE) as process:
        try:
            assert process.stdout.readline().strip() == b"READY", root
            yield process
        finally:
            if process.poll() is None:
                process.kill()


def verify(binary, fixture):
    sid = re.search(rb"S-1-[\d-]+", run("whoami", "/user", "/fo", "csv", "/nh").stdout)[0].decode()
    run("icacls", str(fixture), "/setowner", "*" + sid)
    run("icacls", str(fixture), "/grant:r", "*" + sid + ":(OI)(CI)F")
    run("icacls", str(fixture), "/inheritance:r")
    root = fixture / "Tuprel caf\u00e9 \u65e5"
    root.mkdir()
    run("icacls", str(root), "/setowner", "*" + sid)
    run("icacls", str(root), "/grant:r", "*" + sid + ":(OI)(CI)F")
    run("icacls", str(root), "/inheritance:r")
    outside = fixture / "outside"
    outside.mkdir()
    sentinel = outside / "sentinel"
    sentinel.write_bytes(b"preserve")
    assert invoke(binary, root, report=True) == (0, [b"READY"])
    for name in ("probe", "", ".", "..", r"..\outside\sentinel", str(sentinel), "probe:stream", "probe."):
        assert invoke(binary, root, name) == (2, [b"READY"]), name
    assert sentinel.read_bytes() == b"preserve"
    assert (root / "probe").read_bytes() == b"installer-root-proof\n"

    with ThreadPoolExecutor(max_workers=4) as workers:
        outcomes = list(workers.map(lambda _: invoke(binary, root, "contended"), range(4)))
    assert sorted(outcomes) == [(0, [b"READY"])] + [(2, [b"READY"])] * 3
    assert (root / "contended").read_bytes() == b"installer-root-proof\n"

    junction = fixture / "junction"
    run("cmd.exe", "/d", "/c", "mklink", "/J", str(junction), str(root))
    try:
        for path in (junction, str(junction) + r"\child", str(root) + "\\",
                     str(fixture) + r"\.\\" + root.name, str(root).swapcase(),
                     "\\\\?\\" + str(root), str(root).replace("\\", "/")):
            assert invoke(binary, path) == (2, []), path
        short = ctypes.create_unicode_buffer(4096)
        assert 0 < ctypes.windll.kernel32.GetShortPathNameW(str(root), short, len(short)) < len(short)
        if short.value != str(root):
            assert invoke(binary, short.value) == (2, []), short.value
    finally:
        junction.rmdir()

    with opened(binary, root, "held") as process:
        try:
            root.rename(fixture / "relocated")
        except PermissionError:
            pass
        else:
            raise AssertionError("An open install root could be renamed")
        process.communicate(b"\n", timeout=15)
        assert process.returncode == 0
    assert (root / "held").is_file()

    for target in (root, fixture):
        with opened(binary, root, "after-acl-change") as process:
            run("icacls", str(target), "/grant", "*S-1-1-0:(WDAC)")
            try:
                process.communicate(b"\n", timeout=15)
                assert process.returncode == 2
                assert invoke(binary, root) == (2, [])
                assert not (root / "after-acl-change").exists()
            finally:
                run("icacls", str(target), "/remove:g", "*S-1-1-0")

    with opened(binary, root, "after-owner-change") as process:
        run("icacls", str(root), "/setowner", "*S-1-5-32-544")
        try:
            process.communicate(b"\n", timeout=15)
            assert process.returncode == 2
            assert invoke(binary, root) == (2, [])
            assert not (root / "after-owner-change").exists()
        finally:
            run("icacls", str(root), "/setowner", "*" + sid)
    assert invoke(binary, root, "restored") == (0, [b"READY"])


if __name__ == "__main__":
    directory = Path(os.environ["LOCALAPPDATA"])
    with tempfile.TemporaryDirectory(prefix="tuprel-install-", dir=directory.resolve()) as path:
        verify(sys.argv[1], Path(path))
    print("Verified Windows install root identity and handle-relative writes")
