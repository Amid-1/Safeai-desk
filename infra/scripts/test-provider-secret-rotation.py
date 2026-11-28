#!/usr/bin/env python3
"""Isolated filesystem tests. Never read /run/secrets or real credentials."""
import fcntl
import importlib.util
import os
from pathlib import Path
import stat
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "rotation", Path(__file__).with_name("rotate-provider-secret.py"))
rotation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rotation)

OLD = b"sk-fixture-old-0123456789"
NEW = b"sk-fixture-new-0123456789"


class RotationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.root.chmod(0o750)
        self.name = rotation.NAMES["openai"]
        self.target = self.root / self.name
        self.target.write_bytes(OLD)
        self.target.chmod(0o640)
        self.owner = {"uid": os.getuid(), "gid": os.getgid()}

    def test_valid_rotation_replaces_inode_and_preserves_existing_reader(self):
        rotation.check_file(self.root, self.name, **self.owner)
        with self.target.open("rb") as old_reader:
            old_inode = os.fstat(old_reader.fileno()).st_ino
            rotation.rotate(self.root, self.name, NEW + b"\n", **self.owner)
            self.assertEqual(old_reader.read(), OLD)
        self.assertEqual(self.target.read_bytes(), NEW)
        self.assertNotEqual(self.target.stat().st_ino, old_inode)
        self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), 0o640)
        rotation.check_file(self.root, self.name, **self.owner)
        self.assertEqual(list(self.root.iterdir()), [self.target])

    def test_invalid_inputs_preserve_old_file(self):
        for key in (b"", b"test-placeholder-value", b"REPLACE_ME_PROVIDER_KEY",
                    NEW + b"\n\n", NEW + b"\r\n", b" " + NEW, NEW + b"\0",
                    NEW + b"\t", b"x" * 4097):
            with self.subTest(length=len(key)):
                with self.assertRaises(ValueError):
                    rotation.rotate(self.root, self.name, key, **self.owner)
                self.assertEqual(self.target.read_bytes(), OLD)

    def test_symlink_hardlink_and_wrong_mode_are_rejected(self):
        self.target.chmod(0o644)
        with self.assertRaises(ValueError):
            rotation.rotate(self.root, self.name, NEW, **self.owner)
        self.target.chmod(0o640)
        other = self.root / "other"
        os.link(self.target, other)
        with self.assertRaises(ValueError):
            rotation.rotate(self.root, self.name, NEW, **self.owner)
        self.target.unlink()
        self.target.symlink_to(other)
        with self.assertRaises(ValueError):
            rotation.rotate(self.root, self.name, NEW, **self.owner)
        self.assertEqual(other.read_bytes(), OLD)

    def test_concurrent_rotation_cannot_modify_file(self):
        fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError):
                rotation.rotate(self.root, self.name, NEW, **self.owner)
            self.assertEqual(self.target.read_bytes(), OLD)
        finally:
            os.close(fd)


if __name__ == "__main__":
    unittest.main(verbosity=2)
