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
"""Operator-only reconciliation of one completed Q4 read lease; never replay a read."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import stat
import subprocess
import uuid

ROOT = Path('/run/ablestack-vm-operations')


class ReconcileError(Exception):
    pass


def private(path, directory=False, lockfile=False):
    info = path.lstat()
    expected = stat.S_ISDIR if directory else stat.S_ISREG
    if (not expected(info.st_mode) or info.st_uid != os.geteuid()
            or info.st_mode & (0o022 if lockfile else 0o077)
            or (not directory and info.st_nlink != 1)):
        raise ReconcileError('Unsafe operation path')
    return info


def owner_alive(record):
    path = Path('/proc') / str(record['ownerPid']) / 'stat'
    try:
        value = path.read_text().rsplit(')', 1)[1].split()[19]
        return value == record['ownerStartTicks']
    except FileNotFoundError:
        return False


def virsh(*args):
    try:
        result = subprocess.run(['virsh', '-c', 'qemu:///system', *args],
                                capture_output=True, text=True, timeout=5, check=True)
        # QGA base64 wraps a bounded 1 MiB snapshot; reserve its envelope.
        if len(result.stdout.encode()) > 2 * 1048576:
            raise ReconcileError('Observation output limit exceeded')
        return result.stdout
    except subprocess.CalledProcessError as error:
        raise ReconcileError('Observation ' + args[0] + ' rejected (exit ' + str(error.returncode) + '); lease retained') from None
    except (subprocess.SubprocessError, OSError):
        raise ReconcileError('Observation ' + args[0] + ' unavailable; lease retained') from None


def reconcile(vm, request, apply=False, root=ROOT):
    if str(uuid.UUID(vm)) != vm or str(uuid.UUID(request)) != request:
        raise ReconcileError('Canonical VM and request UUIDs required')
    locks = root / 'locks'
    directory = root / vm
    for path in (root, locks, directory):
        private(path, directory=True)
    lock = locks / (vm + '.lock')
    # Existing flock files may be 0644 inside root-only directories.
    private(lock, lockfile=True)
    with os.fdopen(os.open(lock, os.O_RDWR | os.O_NOFOLLOW), 'r+') as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ReconcileError('VM operation is active; lease retained') from None
        marker = directory / ('q4-read-' + request + '.json')
        info = private(marker)
        with os.fdopen(os.open(marker, os.O_RDONLY | os.O_NOFOLLOW), 'r') as stream:
            opened = os.fstat(stream.fileno())
            if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino):
                raise ReconcileError('Read lease changed')
            data = stream.read(8193)
        if len(data) > 8192:
            raise ReconcileError('Read lease exceeds size limit')
        record = json.loads(data)
        if (record.get('kind') != 'q4-read-lease' or record.get('stage') != 'UNKNOWN'
                or record.get('vmUuid') != vm or record.get('requestId') != request
                or type(record.get('guestExecPid')) is not int or record['guestExecPid'] < 1
                or type(record.get('ownerPid')) is not int or record['ownerPid'] < 1
                or not isinstance(record.get('ownerStartTicks'), str)
                or record.get('hostBootId') != Path('/proc/sys/kernel/random/boot_id').read_text().strip()):
            raise ReconcileError('Lease lacks a verifiable same-host execution identity')
        receipt = dict(vmUuid=vm, requestId=request, guestExecPid=record['guestExecPid'], removed=False)
        if not apply:
            return receipt
        if owner_alive(record):
            raise ReconcileError('Original reader still owns this operation')
        # domuuid accepts name/id, whereas domname resolves a UUID.
        domain = virsh('domname', vm).strip()
        if not domain or domain.startswith('-') or virsh('domuuid', domain).strip() != vm:
            raise ReconcileError('Domain identity changed')
        response = json.loads(virsh('qemu-agent-command', vm, '--timeout', '3',
                             json.dumps(dict(execute='guest-exec-status',
                                             arguments=dict(pid=record['guestExecPid'])))))
        # Missing/reaped execution IDs are NOT proof of completion.
        if (not isinstance(response, dict) or not isinstance(response.get('return'), dict)
                or response['return'].get('exited') is not True):
            raise ReconcileError('Guest completion remains unknown; lease retained')
        actual = private(marker)
        if ((actual.st_dev, actual.st_ino) != (opened.st_dev, opened.st_ino)
                or marker.read_text() != data):
            raise ReconcileError('Read lease changed after completion observation')
        marker.unlink()
        receipt.update(removed=True, guestCompletionVerified=True)
        return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vm', required=True)
    parser.add_argument('--request', required=True)
    parser.add_argument('--apply', action='store_true', help='Query original execution and remove only its proven completed read lease')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ReconcileError('Run as root on the VM host')
    print(json.dumps(reconcile(args.vm, args.request, args.apply), indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ReconcileError, OSError, ValueError, KeyError) as error:
        raise SystemExit('REFUSED: ' + str(error)) from None
