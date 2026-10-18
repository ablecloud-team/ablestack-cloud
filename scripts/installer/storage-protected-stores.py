#!/usr/bin/python3
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

"""Initialize/check only the management server's four protected local stores."""
import argparse
import contextlib
import grp
import os
import pwd
import stat
import sys

STORES = ("storage-config-artifacts", "storage-identity-capsules",
          "storage-rendered-validation", "storage-rendered-keys")
PARENT = "cloudstack-management"
DIRECTORY_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC


class StoreError(RuntimeError):
    pass


def _identity(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid)


def _directory(parent_fd, name):
    before = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    if not stat.S_ISDIR(before.st_mode):
        raise StoreError("link or non-directory: " + name)
    descriptor = os.open(name, DIRECTORY_FLAGS, dir_fd=parent_fd)
    try:
        actual = os.fstat(descriptor)
        named = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if _identity(actual) != _identity(before) or _identity(named) != _identity(actual):
            raise StoreError("directory changed: " + name)
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def _expect(descriptor, name, uid, gid, mode):
    info = os.fstat(descriptor)
    if (not stat.S_ISDIR(info.st_mode) or info.st_uid != uid or info.st_gid != gid
            or stat.S_IMODE(info.st_mode) != mode):
        raise StoreError("unsafe ownership or mode: " + name)


def _stores(root_fd, uid, gid, initialize):
    """The CLI anchors root_fd at '/'; tests use a private fake filesystem."""
    anchor = os.fstat(root_fd)
    if (not stat.S_ISDIR(anchor.st_mode) or anchor.st_uid != 0
            or stat.S_IMODE(anchor.st_mode) & 0o7022):
        raise StoreError("unsafe filesystem root")
    with contextlib.ExitStack() as stack:
        parent_fd = root_fd
        for name in ("var", "lib"):
            descriptor = _directory(parent_fd, name)
            stack.callback(os.close, descriptor)
            info = os.fstat(descriptor)
            if info.st_uid != 0 or stat.S_IMODE(info.st_mode) & 0o7022:
                raise StoreError("unsafe root ancestor: " + name)
            parent_fd = descriptor
        lib_fd = parent_fd
        created_parent = False
        try:
            parent_fd = _directory(lib_fd, PARENT)
        except FileNotFoundError:
            if not initialize:
                raise StoreError("missing parent: /var/lib/" + PARENT)
            try:
                os.mkdir(PARENT, 0o755, dir_fd=lib_fd)
                created_parent = True
            except FileExistsError:
                pass
            parent_fd = _directory(lib_fd, PARENT)
        stack.callback(os.close, parent_fd)
        if created_parent:
            os.fchown(parent_fd, 0, 0)
            os.fchmod(parent_fd, 0o755)
        _expect(parent_fd, PARENT, 0, 0, 0o755)

        # Validate every existing leaf before creating any missing leaf.
        missing = []
        for name in STORES:
            try:
                descriptor = _directory(parent_fd, name)
            except FileNotFoundError:
                if not initialize:
                    raise StoreError("missing protected store: " + name)
                missing.append(name)
                continue
            try:
                _expect(descriptor, name, uid, gid, 0o700)
            finally:
                os.close(descriptor)
        for name in missing:
            created = False
            try:
                os.mkdir(name, 0o700, dir_fd=parent_fd)
                created = True
            except FileExistsError:
                pass
            descriptor = _directory(parent_fd, name)
            try:
                if created:
                    os.fchown(descriptor, uid, gid)
                    os.fchmod(descriptor, 0o700)
                _expect(descriptor, name, uid, gid, 0o700)
                if created:
                    os.fsync(descriptor)
            finally:
                os.close(descriptor)
        for name in STORES:
            descriptor = _directory(parent_fd, name)
            try:
                _expect(descriptor, name, uid, gid, 0o700)
            finally:
                os.close(descriptor)
        if _identity(os.stat(PARENT, dir_fd=lib_fd, follow_symlinks=False)) != _identity(os.fstat(parent_fd)):
            raise StoreError("management store parent changed")
        if initialize and (created_parent or missing):
            os.fsync(parent_fd)
            os.fsync(lib_fd)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--initialize", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    if args.initialize and os.geteuid() != 0:
        print("ERROR: protected store initialization requires root", file=sys.stderr)
        return 1
    try:
        account, group = pwd.getpwnam("cloud"), grp.getgrnam("cloud")
        if account.pw_gid != group.gr_gid:
            raise StoreError("cloud account primary group differs")
        descriptor = os.open("/", DIRECTORY_FLAGS)
        try:
            _stores(descriptor, account.pw_uid, group.gr_gid, args.initialize)
        finally:
            os.close(descriptor)
    except (StoreError, OSError, KeyError) as error:
        detail = str(error) if isinstance(error, StoreError) else type(error).__name__
        print("ERROR: protected management stores: " + detail
              + "; run cloudstack-setup-management as root; unsafe existing paths require operator review",
              file=sys.stderr)
        return 1
    print("Protected management storage stores verified: 4")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
