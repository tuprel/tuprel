"""Executable-level proofs. The caller supplies a real, verified test volume."""

import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import tempfile
import threading
import unittest


BINARY = Path(sys.argv.pop(1)).resolve(strict=True)
VOLUME = Path(sys.argv.pop(1)).resolve(strict=True)
PROFILE = sys.argv.pop(1)
DEST = "20260101_000000_initial"
STAGE = ".migration-stage-" + "a" * 32
CONTENTS = b"-- fixture migration\n"
READY = re.compile(rb"READY (ext4|xfs|ntfs) ([0-9a-f]{1,16})"
                   rb"( [0-9a-f]{1,32}){3}\n")


class NativePublicationTest(unittest.TestCase):
    def setUp(self):
        fixture = tempfile.TemporaryDirectory(dir=VOLUME, prefix="tuprel native ")
        self.addCleanup(fixture.cleanup)
        self.root = Path(fixture.name).resolve(strict=True)
        self.catalog = self.root / "migrations"
        self.catalog.mkdir(mode=0o700)
        self.stage = self.make_stage(STAGE)
        self.final = self.catalog / DEST
        self.keep = self.catalog / "20250101_000000_existing"
        self.keep.mkdir()
        (self.keep / "migration.sql").write_bytes(b"-- preserved\n")

    def tearDown(self):
        self.assertEqual(b"-- preserved\n", (self.keep / "migration.sql").read_bytes())

    def make_stage(self, name):
        stage = self.root / name
        stage.mkdir(mode=0o700)
        (stage / "migration.sql").write_bytes(CONTENTS)
        return stage

    def start(self, stage=STAGE, name=DEST, root=None):
        env = {"SystemRoot": os.environ["SystemRoot"]} if os.name == "nt" else {}
        process = subprocess.Popen(
            [str(BINARY), "1", str(self.root if root is None else root), stage, name],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            cwd=BINARY.parent, env=env,
        )
        self.addCleanup(self.stop, process)
        return process

    @staticmethod
    def stop(process):
        if process.poll() is None:
            process.kill()
        process.communicate(timeout=10)

    def ready(self, process):
        output = queue.Queue()
        reader = threading.Thread(target=lambda: output.put(process.stdout.readline()), daemon=True)
        reader.start()
        line = output.get(timeout=10)
        match = READY.fullmatch(line)
        self.assertIsNotNone(match, line)
        self.assertEqual(PROFILE.encode("ascii"), match.group(1))

    @staticmethod
    def send(process, data=b"GO\n"):
        process.stdin.write(data)
        process.stdin.close()
        process.stdin = None

    def result(self, process, status, message, request=b"GO\n"):
        output, error = process.communicate(input=request if process.stdin is not None else None, timeout=10)
        self.assertEqual((status, message + b"\n", b""), (process.returncode, output, error))

    def assert_intact(self):
        self.assertEqual(CONTENTS, (self.stage / "migration.sql").read_bytes())

    def test_atomic_visibility_and_exact_bytes(self):
        process = self.start()
        self.ready(process)
        self.assertFalse(self.final.exists())
        observed = []
        stopped = threading.Event()

        def read():
            while not stopped.is_set():
                if self.final.exists():
                    try:
                        observed.append((self.final / "migration.sql").read_bytes())
                    except OSError as error:
                        observed.append(error)
                    return

        reader = threading.Thread(target=read, daemon=True)
        reader.start()
        try:
            self.result(process, 0, b"MOVED")
            reader.join(5)
        finally:
            stopped.set()
            reader.join(5)
        self.assertFalse(self.stage.exists())
        self.assertEqual(CONTENTS, (self.final / "migration.sql").read_bytes())
        self.assertEqual([CONTENTS], observed)

    def test_external_process_creates_destination_after_ready(self):
        process = self.start()
        self.ready(process)
        subprocess.run([sys.executable, "-c",
                        "import pathlib,sys; p=pathlib.Path(sys.argv[1]); p.mkdir(); "
                        "(p/'sentinel').write_bytes(b'external')", str(self.final)],
                       check=True, timeout=10)
        self.result(process, 11, b"COLLISION")
        self.assertEqual(b"external", (self.final / "sentinel").read_bytes())
        self.assert_intact()

    def test_existing_empty_directory_and_regular_file_are_preserved(self):
        for directory in (True, False):
            with self.subTest(directory=directory):
                if directory:
                    self.final.mkdir()
                else:
                    self.final.write_bytes(b"existing file")
                process = self.start()
                self.ready(process)
                self.result(process, 11, b"COLLISION")
                self.assert_intact()
                if directory:
                    self.assertEqual([], list(self.final.iterdir()))
                    self.final.rmdir()
                else:
                    self.assertEqual(b"existing file", self.final.read_bytes())
                    self.final.unlink()

    def test_two_native_processes_cannot_both_win(self):
        other = self.make_stage(".migration-stage-" + "b" * 32)
        (other / "migration.sql").write_bytes(b"second")
        contenders = (self.start(), self.start(stage=other.name))
        for process in contenders:
            self.ready(process)
        for process in contenders:
            self.send(process)
        results = []
        for process in contenders:
            out, err = process.communicate(timeout=10)
            self.assertEqual(b"", err)
            results.append((process.returncode, out))
        # An unclassified Windows I/O error is deliberately conservative.
        self.assertIn(sorted(results), [[(0, b"MOVED\n"), (11, b"COLLISION\n")],
                                        [(0, b"MOVED\n"), (12, b"UNKNOWN\n")]])
        winner = (self.final / "migration.sql").read_bytes()
        self.assertIn(winner, [CONTENTS, b"second"])
        loser = other if winner == CONTENTS else self.stage
        self.assertEqual(b"second" if winner == CONTENTS else CONTENTS, (loser / "migration.sql").read_bytes())

    def test_namespace_replacement_between_ready_and_go(self):
        process = self.start()
        self.ready(process)
        old = self.root / "old-migrations"
        if os.name == "nt":
            with self.assertRaises(PermissionError):
                self.catalog.rename(old)
            self.result(process, 10, b"REFUSED", b"")
        else:
            self.catalog.rename(old)
            try:
                self.catalog.mkdir()
                self.result(process, 10, b"REFUSED")
                self.assertFalse((old / DEST).exists())
                self.assertFalse(self.final.exists())
            finally:
                self.catalog.rmdir()
                old.rename(self.catalog)
        self.assert_intact()

    def test_rejects_aliases_and_links(self):
        alias = str(self.root) + os.sep + "." + os.sep + ".." + os.sep + self.root.name
        self.result(self.start(root=alias), 10, b"REFUSED", b"")
        self.result(self.start(name="../" + DEST), 10, b"REFUSED", b"")
        self.result(self.start(name=DEST + "."), 10, b"REFUSED", b"")
        if os.name == "nt":
            self.result(self.start(root=str(self.root)[0].lower() + str(self.root)[1:]), 10, b"REFUSED", b"")
        moved = self.root / "real-stage"
        self.stage.rename(moved)
        if os.name == "nt":
            subprocess.run([os.environ["ComSpec"], "/d", "/c", "mklink", "/J", str(self.stage), str(moved)],
                           check=True, stdout=subprocess.DEVNULL, timeout=10)
        else:
            self.stage.symlink_to(moved, target_is_directory=True)
        self.result(self.start(), 10, b"REFUSED", b"")
        self.assertFalse(self.final.exists())
        self.assertEqual(CONTENTS, (moved / "migration.sql").read_bytes())

    def test_interruption_before_permission_and_malformed_protocol(self):
        process = self.start()
        self.ready(process)
        process.kill()
        process.communicate(timeout=10)
        for malformed in (b"", b"GO\r\n", b"GO\nEXTRA", b"START\n"):
            process = self.start()
            self.ready(process)
            self.result(process, 10, b"REFUSED", malformed)
        self.assertFalse(self.final.exists())
        self.assert_intact()

    def test_refuses_a_symbolic_link_even_when_its_target_is_a_directory(self):
        moved = self.root / "real-stage"
        self.stage.rename(moved)
        self.stage.symlink_to(moved, target_is_directory=True)
        self.result(self.start(), 10, b"REFUSED", b"")
        self.assertFalse(self.final.exists())
        self.assertEqual(CONTENTS, (moved / "migration.sql").read_bytes())

    def test_catalog_access_failure_preserves_stage(self):
        process = self.start()
        self.ready(process)
        if os.name == "nt":
            # A child handle held without delete sharing blocks directory rename on NTFS.
            with (self.stage / "migration.sql").open("rb") as held:
                self.result(process, 12, b"UNKNOWN")
                self.assertEqual(CONTENTS, held.read())
        else:
            self.catalog.chmod(0o500)
            try:
                self.result(process, 12, b"UNKNOWN")
            finally:
                self.catalog.chmod(0o700)
        self.assertFalse(self.final.exists())
        self.assert_intact()

    def test_termination_racing_the_rename_has_no_partial_destination(self):
        process = self.start()
        self.ready(process)
        self.send(process)
        process.kill()
        process.communicate(timeout=10)
        visible = self.final if self.final.exists() else self.stage
        self.assertNotEqual(self.final.exists(), self.stage.exists())
        self.assertEqual(CONTENTS, (visible / "migration.sql").read_bytes())

    def test_lost_confirmation_leaves_destination_for_rediscovery(self):
        process = self.start()
        self.ready(process)
        self.send(process)
        # Deliberately discard the completion frame and inspect independently.
        process.communicate(timeout=10)
        self.assertFalse(self.stage.exists())
        self.assertEqual(CONTENTS, (self.final / "migration.sql").read_bytes())


if __name__ == "__main__":
    print(f"Native filesystem proof: {PROFILE}", flush=True)
    unittest.main(verbosity=2)
