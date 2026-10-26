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

"""Remove only the verified original metadata of an external file restore.

A durable inventory is recorded before unlinking anything. Replaying a completed
receipt cannot remove files subsequently restored to the same timestamp path.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import stat
import sys
import time
import xml.etree.ElementTree as ET

from thirdparty_staging_cleanup import validate_job_directory

METADATA = {".staging.complete", ".volume-bootstrap", "backup-manifest.json", "domain-config.xml",
            "domblklist.xml", "domiflist.xml", "dominfo.xml", "rbd-backup.meta"}
MAX_FILE = 16 * 1024 * 1024


def fingerprint(info):
    return [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns]


def read_file(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_FILE or info.st_nlink != 1:
            raise RuntimeError("Metadata is not a small, individually owned regular file: " + str(path))
        with os.fdopen(fd, 'r', encoding='utf-8', closefd=False) as stream:
            return stream.read(MAX_FILE + 1)
    finally:
        os.close(fd)


def atomic(path, value):
    temporary = path.with_suffix('.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(value, stream, sort_keys=True)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def inventory(plan, destination):
    root = Path(destination['path'])
    config = destination['configuration']
    expected = Path(config['rootPath']) / plan['provider'] / plan['vmName'] / plan['manifest']['timestamp']
    if root != expected or str(root) != destination['path']:
        raise RuntimeError('Metadata destination differs from its recorded configuration')
    validate_job_directory(plan['provider'], root, Path(config['rootPath']) / plan['provider'])
    if not root.exists():
        return {'path': str(root), 'absent': True}
    root_stat = root.lstat()
    if not stat.S_ISDIR(root_stat.st_mode):
        raise RuntimeError('Metadata destination is not a directory')
    start, end = plan['proof']['startedAt'] / 1000, plan['proof']['endedAt'] / 1000
    if start <= 0 or end < start or end > time.time() + 300:
        raise RuntimeError('External restore completion is not confirmed')
    files = []
    for entry in sorted(root.iterdir()):
        info = entry.lstat()
        if entry.name == 'checkpoints' and stat.S_ISDIR(info.st_mode):
            for checkpoint in sorted(entry.iterdir()):
                if not checkpoint.name.endswith('.xml'):
                    raise RuntimeError('Unknown checkpoint content; metadata cleanup deferred')
                files.append(checkpoint)
        elif entry.name in METADATA and stat.S_ISREG(info.st_mode):
            files.append(entry)
        else:
            raise RuntimeError('Payload or unknown content is still present; metadata cleanup deferred: ' + entry.name)
    snapshots = []
    for path in files:
        before = path.lstat()
        read_file(path)  # rejects links, devices and oversized files
        if not (start - 5 <= before.st_ctime <= end + 5):
            raise RuntimeError('Metadata is outside this external restore time window: ' + path.name)
        if fingerprint(path.lstat()) != fingerprint(before):
            raise RuntimeError('Metadata changed during ownership validation')
        snapshots.append({'name': str(path.relative_to(root)), 'stat': fingerprint(before)})
    manifest = json.loads(read_file(root / 'backup-manifest.json'))
    expected_manifest = plan['manifest']
    for key in ('version', 'provider', 'backupUuid', 'vmName', 'timestamp', 'backupType', 'volumes'):
        if manifest.get(key) != expected_manifest.get(key):
            raise RuntimeError('Restored metadata differs from its recorded backup: ' + key)
    if manifest.get('complete') is not True or manifest.get('backupUuid') != plan['backupUuid']:
        raise RuntimeError('Restored manifest does not identify a completed backup')
    domain = ET.fromstring(read_file(root / 'domain-config.xml'))
    if domain.tag != 'domain' or domain.findtext('name') != plan['vmName'] or domain.findtext('uuid') != plan['vmUuid']:
        raise RuntimeError('Restored domain XML belongs to another VM')
    marker = dict(line.split('=', 1) for line in read_file(root / '.staging.complete').splitlines() if '=' in line)
    if marker.get('volume_pipeline') != 'true' or marker.get('backup_uuid') != plan['backupUuid']:
        raise RuntimeError('Restored completion marker belongs to another backup')
    checkpoints = root / 'checkpoints'
    return {'path': str(root), 'directory': [root_stat.st_dev, root_stat.st_ino], 'files': snapshots,
            'checkpoints': [checkpoints.stat().st_dev, checkpoints.stat().st_ino] if checkpoints.exists() else None}


def remove_inventory(plan, saved):
    if saved.get('absent'):
        return
    root = Path(saved['path'])
    destination = next(item for item in plan['destinations'] if item['path'] == str(root))
    validate_job_directory(plan['provider'], root, Path(destination['configuration']['rootPath']) / plan['provider'])
    if not root.exists():
        return
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    checkpoint_fd = None
    try:
        current = os.fstat(fd)
        if [current.st_dev, current.st_ino] != saved['directory']:
            raise RuntimeError('Metadata directory was replaced; original cleanup receipt cannot be reused')
        if saved.get('checkpoints') and (root / 'checkpoints').exists():
            checkpoint_fd = os.open('checkpoints', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            current = os.fstat(checkpoint_fd)
            if [current.st_dev, current.st_ino] != saved['checkpoints']:
                raise RuntimeError('Checkpoint directory was replaced')
        # Validate the entire remaining inventory before deleting any file on each retry.
        remaining = []
        for item in saved['files']:
            parts = Path(item['name']).parts
            target_fd = checkpoint_fd if len(parts) == 2 and parts[0] == 'checkpoints' else fd
            if len(parts) not in (1, 2) or target_fd is None:
                raise RuntimeError('Invalid saved metadata inventory')
            try:
                info = os.stat(parts[-1], dir_fd=target_fd, follow_symlinks=False)
            except FileNotFoundError:
                continue
            if fingerprint(info) != item['stat'] or not stat.S_ISREG(info.st_mode):
                raise RuntimeError('A later operation changed metadata; cleanup deferred: ' + item['name'])
            remaining.append((target_fd, parts[-1], item['stat']))
        for target_fd, name, expected in remaining:
            if fingerprint(os.stat(name, dir_fd=target_fd, follow_symlinks=False)) != expected:
                raise RuntimeError('Metadata changed before deletion')
            os.unlink(name, dir_fd=target_fd)
        if checkpoint_fd is not None:
            if os.listdir(checkpoint_fd):
                raise RuntimeError('New checkpoint files are present; cleanup deferred')
            os.rmdir('checkpoints', dir_fd=fd)
        if os.listdir(fd):
            raise RuntimeError('New files are present; cleanup deferred')
        if [root.stat().st_dev, root.stat().st_ino] != saved['directory']:
            raise RuntimeError('Metadata directory changed before removal')
        root.rmdir()
    finally:
        if checkpoint_fd is not None:
            os.close(checkpoint_fd)
        os.close(fd)


def cleanup(plan, receipt_root):
    if (plan.get('version') != 1 or plan.get('provider') not in {'ablestack-netbackup', 'ablestack-veeam'}
            or not re.fullmatch(r'external-restore-[0-9a-f-]{36}', plan.get('id', '')) or not plan.get('destinations')):
        raise RuntimeError('Invalid rejected external restore cleanup plan')
    receipt_root = Path(receipt_root)
    receipt_root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if any(path.is_symlink() for path in (receipt_root, *receipt_root.parents)):
        raise RuntimeError('Cleanup receipt directory cannot traverse symbolic links')
    lock_fd = os.open(receipt_root / (plan['id'] + '.lock'), os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        receipt_path = receipt_root / (plan['id'] + '.json')
        identity = {key: plan[key] for key in ('version', 'id', 'provider', 'jobId', 'externalId', 'backupUuid', 'vmUuid', 'proof', 'destinations')}
        if receipt_path.exists():
            receipt = json.loads(read_file(receipt_path))
            if receipt.get('identity') != identity:
                raise RuntimeError('Cleanup receipt belongs to another external restore')
        else:
            receipt = {'identity': identity, 'completed': False,
                       'inventory': [inventory(plan, destination) for destination in plan['destinations']]}
            atomic(receipt_path, receipt)
        if receipt.get('completed'):
            return
        for saved in receipt['inventory']:
            remove_inventory(plan, saved)
        receipt['completed'] = True
        atomic(receipt_path, receipt)
    finally:
        os.close(lock_fd)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--plan-file', required=True)
    parser.add_argument('--receipt-root', required=True)
    args = parser.parse_args()
    try:
        plan = json.loads(read_file(Path(args.plan_file)))
        cleanup(plan, args.receipt_root)
        print('ABLESTACK_EXTERNAL_CLEANUP=' + plan['id'])
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, ET.ParseError) as error:
        parser.exit(1, 'Rejected restore metadata cleanup deferred: %s\n' % error)


if __name__ == '__main__':
    main()
