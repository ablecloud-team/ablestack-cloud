# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Temporary fake filesystem only; no installer execution against the host."""
from pathlib import Path
import contextlib
import importlib.util
import io
import os
import stat
import tempfile
import types
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("protected_store_installer",
        REPO / "scripts/installer/storage-protected-stores.py")
stores = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stores)


class ProtectedStoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "var/lib").mkdir(parents=True, mode=0o755)
        (self.root / "var").chmod(0o755)
        self.parent = self.root / "var/lib" / stores.PARENT
        self.fd = os.open(self.root, stores.DIRECTORY_FLAGS)
        self.addCleanup(os.close, self.fd)

    def apply(self, initialize=True):
        stores._stores(self.fd, os.geteuid(), os.getegid(), initialize)

    def fingerprint(self):
        fields = ("st_dev", "st_ino", "st_mode", "st_uid", "st_gid", "st_size", "st_mtime_ns", "st_ctime_ns")
        return {str(p.relative_to(self.root)): tuple(getattr(p.lstat(), k) for k in fields)
                for p in self.root.rglob("*")}

    def rejected_unchanged(self, message):
        before = self.fingerprint()
        with self.assertRaisesRegex(stores.StoreError, message):
            self.apply()
        self.assertEqual(before, self.fingerprint())

    def test_absent_parent_and_exact_four_leaves(self):
        self.apply()
        self.assertEqual(set(stores.STORES), {p.name for p in self.parent.iterdir()})
        self.assertEqual(0o755, stat.S_IMODE(self.parent.stat().st_mode))
        for name in stores.STORES:
            s = (self.parent / name).stat()
            self.assertEqual((os.geteuid(), os.getegid(), 0o700),
                             (s.st_uid, s.st_gid, stat.S_IMODE(s.st_mode)))

    def test_idempotence_preserves_siblings_and_opaque_contents(self):
        self.apply()
        (self.parent / "unrelated-sibling").write_text("do not modify")
        (self.parent / stores.STORES[0] / "opaque-existing.zip").write_bytes(b"unchanged")
        before = self.fingerprint()
        self.apply()
        self.assertEqual(before, self.fingerprint())

    def test_check_missing_parent_creates_nothing(self):
        with self.assertRaisesRegex(stores.StoreError, "missing parent"):
            self.apply(False)
        self.assertFalse(self.parent.exists())

    def test_check_missing_leaf_creates_nothing(self):
        self.parent.mkdir(mode=0o755)
        before = self.fingerprint()
        with self.assertRaisesRegex(stores.StoreError, "missing protected store"):
            self.apply(False)
        self.assertEqual(before, self.fingerprint())

    def test_validate_existing_before_creating_missing(self):
        self.parent.mkdir(mode=0o755)
        (self.parent / stores.STORES[-1]).mkdir(mode=0o755)
        self.rejected_unchanged("unsafe ownership or mode")
        self.assertFalse((self.parent / stores.STORES[0]).exists())

    def test_unsafe_parent_is_not_repaired(self):
        self.parent.mkdir(mode=0o700)
        self.rejected_unchanged("unsafe ownership or mode")

    def test_unsafe_leaf_is_not_repaired(self):
        self.apply()
        (self.parent / stores.STORES[0]).chmod(0o755)
        self.rejected_unchanged("unsafe ownership or mode")

    def test_special_bits_rejected(self):
        self.apply()
        (self.parent / stores.STORES[0]).chmod(0o2700)
        self.rejected_unchanged("unsafe ownership or mode")

    def test_parent_symlink_rejected(self):
        target = self.root / "foreign"
        target.mkdir()
        self.parent.symlink_to(target, target_is_directory=True)
        self.rejected_unchanged("link or non-directory")

    def test_leaf_symlink_rejected(self):
        self.parent.mkdir(mode=0o755)
        target = self.root / "foreign"
        target.mkdir()
        (self.parent / stores.STORES[0]).symlink_to(target, target_is_directory=True)
        self.rejected_unchanged("link or non-directory")

    def test_file_leaf_rejected_without_reading_it(self):
        self.parent.mkdir(mode=0o755)
        (self.parent / stores.STORES[0]).write_bytes(b"must not inspect")
        self.rejected_unchanged("link or non-directory")

    def test_writable_ancestor_rejected(self):
        (self.root / "var/lib").chmod(0o777)
        self.rejected_unchanged("unsafe root ancestor")

    def test_symlink_ancestor_rejected(self):
        (self.root / "var/lib").rmdir()
        (self.root / "var").rmdir()
        target = self.root / "foreign"
        target.mkdir()
        (self.root / "var").symlink_to(target, target_is_directory=True)
        self.rejected_unchanged("link or non-directory")

    def test_readonly_check_never_mutates_and_every_open_is_nofollow(self):
        self.apply()
        original = os.open
        def readonly(path, flags, *args, **kwargs):
            self.assertTrue(flags & os.O_NOFOLLOW)
            self.assertTrue(flags & os.O_DIRECTORY)
            self.assertEqual(os.O_RDONLY, flags & os.O_ACCMODE)
            return original(path, flags, *args, **kwargs)
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(stores.os, "open", side_effect=readonly))
            for name in ("mkdir", "fchown", "fchmod", "fsync"):
                stack.enter_context(mock.patch.object(stores.os, name, side_effect=AssertionError("readonly mutation")))
            self.apply(False)

    def test_umask_does_not_change_new_parent_or_leaf_mode(self):
        previous = os.umask(0o077)
        try:
            self.apply()
        finally:
            os.umask(previous)
        self.assertEqual(0o755, stat.S_IMODE(self.parent.stat().st_mode))
        self.assertTrue(all(stat.S_IMODE((self.parent / n).stat().st_mode) == 0o700 for n in stores.STORES))

    def test_raced_existing_leaf_is_validated_not_repaired(self):
        self.parent.mkdir(mode=0o755)
        original = os.mkdir
        def race(path, mode=0o777, *, dir_fd=None):
            if path == stores.STORES[0]:
                original(path, 0o755, dir_fd=dir_fd)
                raise FileExistsError(path)
            return original(path, mode, dir_fd=dir_fd)
        with mock.patch.object(stores.os, "mkdir", side_effect=race), \
                mock.patch.object(stores.os, "fchown", side_effect=AssertionError("race repair")), \
                mock.patch.object(stores.os, "fchmod", side_effect=AssertionError("race repair")):
            with self.assertRaisesRegex(stores.StoreError, "unsafe ownership or mode"):
                self.apply()
        self.assertEqual(0o755, stat.S_IMODE((self.parent / stores.STORES[0]).stat().st_mode))

    def test_foreign_owner_and_group_rejected_with_exact_inode_stat_simulation(self):
        self.apply()
        leaf = self.parent / stores.STORES[0]
        inode = (leaf.stat().st_dev, leaf.stat().st_ino)
        raw_stat, raw_fstat = os.stat, os.fstat
        def shifted(info, field):
            if (info.st_dev, info.st_ino) != inode:
                return info
            values = {k: getattr(info, k) for k in dir(info) if k.startswith("st_")}
            values[field] += 1
            return types.SimpleNamespace(**values)
        for field in ("st_uid", "st_gid"):
            with self.subTest(field=field), \
                    mock.patch.object(stores.os, "stat", side_effect=lambda *a, **kw: shifted(raw_stat(*a, **kw), field)), \
                    mock.patch.object(stores.os, "fstat", side_effect=lambda *a: shifted(raw_fstat(*a), field)):
                with self.assertRaisesRegex(stores.StoreError, "unsafe ownership or mode"):
                    self.apply()

    def test_unprivileged_initialize_rejected_before_account_or_filesystem(self):
        with mock.patch.object(stores.os, "geteuid", return_value=1001), \
                mock.patch.object(stores.pwd, "getpwnam", side_effect=AssertionError("account lookup")), \
                mock.patch.object(stores.os, "open", side_effect=AssertionError("filesystem open")), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, stores.main(["--initialize"]))

    def test_main_check_does_not_require_root(self):
        self.apply()
        original = os.open
        def fake_root(path, flags, *args, **kwargs):
            return os.dup(self.fd) if path == "/" else original(path, flags, *args, **kwargs)
        with mock.patch.object(stores.os, "geteuid", return_value=1001), \
                mock.patch.object(stores.pwd, "getpwnam", return_value=types.SimpleNamespace(pw_uid=0, pw_gid=0)), \
                mock.patch.object(stores.grp, "getgrnam", return_value=types.SimpleNamespace(gr_gid=0)), \
                mock.patch.object(stores.os, "open", side_effect=fake_root), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, stores.main(["--check"]))

    def test_install_setup_and_early_precheck_share_the_same_helper(self):
        for rel in ("packaging/centos7/cloud.spec", "packaging/centos8/cloud.spec"):
            content = (REPO / rel).read_text()
            command = "storage-protected-stores.py --initialize || exit 1"
            self.assertIn(command, content)
            self.assertLess(content.index(command), content.index("systemctl enable", content.index("%post management")))
        self.assertIn("storage-protected-stores.py --initialize",
                      (REPO / "debian/cloudstack-management.postinst").read_text())
        setup = (REPO / "python/lib/cloudutils/serviceConfigServer.py").read_text()
        self.assertLess(setup.index("storage-protected-stores.py --initialize"), setup.index("stopService"))
        check = (REPO / "scripts/installer/pre-check.sh").read_text()
        self.assertLess(check.index('"$STORAGE_STORES_CHECKER" --check'), check.index("JAVA_MAJOR_VERSION="))
        self.assertIn("install matching cloudstack-common", check)
