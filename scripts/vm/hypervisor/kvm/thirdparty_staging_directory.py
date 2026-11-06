#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Validate an explicitly selected LOCAL directory without mounting another filesystem."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid

LOCAL_FILESYSTEMS = {'xfs', 'ext4', 'btrfs'}


def canonical_path(value):
    raw = str(value)
    path = Path(raw)
    if not path.is_absolute() or path == Path('/') or str(path) != raw or path.resolve() != path:
        raise ValueError('LOCAL staging must use a canonical non-root path without symbolic links: ' + raw)
    return path


def local_filesystem(path):
    response = subprocess.run(['findmnt', '--json', '--target', str(path), '--output', 'TARGET,FSTYPE,UUID'],
                              check=True, capture_output=True, text=True, timeout=30)
    mounts = json.loads(response.stdout)['filesystems']
    if len(mounts) != 1 or mounts[0].get('fstype') not in LOCAL_FILESYSTEMS:
        raise ValueError('LOCAL DIRECTORY requires an XFS, EXT4 or Btrfs filesystem')
    mount = mounts[0]
    if not mount.get('uuid'):
        raise ValueError('LOCAL filesystem UUID is unavailable')
    return mount['target'], str(uuid.UUID(mount['uuid']))


def prepare_directory(root, operation=None, required_bytes=0):
    root = canonical_path(root)
    operation = canonical_path(root if operation is None else operation)
    if not operation.is_relative_to(root) or not isinstance(required_bytes, int) or required_bytes < 0:
        raise ValueError('Invalid LOCAL staging destination or required capacity')
    ancestor = next(path for path in (root, *root.parents) if path.exists())
    if not ancestor.is_dir():
        raise ValueError('LOCAL staging parent is not a directory')
    expected = local_filesystem(ancestor)
    root.mkdir(parents=True, exist_ok=True)
    operation.mkdir(parents=True, exist_ok=True)
    # Preserve the chosen backing filesystem during creation; a nested mount,
    # redirected path or changed device must never become a new staging target.
    for path in (root, operation):
        canonical_path(path)
        if local_filesystem(path) != expected:
            raise ValueError('LOCAL staging directory resolves to another filesystem')
    with tempfile.TemporaryDirectory(prefix='.ablestack-staging-check.', dir=operation) as probe:
        with (Path(probe) / 'probe').open('wb') as stream:
            stream.write(b'ABLESTACK staging write check\n')
            stream.flush()
            os.fsync(stream.fileno())
    stat = os.statvfs(operation)
    available = stat.f_bavail * stat.f_frsize
    if available < required_bytes:
        raise ValueError('Insufficient capacity: required [%d] bytes, available [%d] bytes' % (required_bytes, available))
    if local_filesystem(operation) != expected:
        raise ValueError('LOCAL staging filesystem changed during validation')
    return available


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', required=True)
    parser.add_argument('--path', required=True)
    parser.add_argument('--required-bytes', type=int, default=0)
    args = parser.parse_args()
    try:
        print(prepare_directory(args.root, args.path, args.required_bytes))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, 'Third-party staging validation failed: %s\n' % error)


if __name__ == '__main__':
    main()
