#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Journal only managed Client notify limits; never alter policies or server settings."""
import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile
import uuid


KEYS = ('BPSTART_TIMEOUT', 'BPEND_TIMEOUT', 'CLIENT_READ_TIMEOUT')


def digest(value):
    return hashlib.sha256(value).hexdigest()


def sync_directory(path):
    directory = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def pending(root):
    return sorted((Path(root) / 'hook-timeouts').glob('*.pending.json'))


def inventory(root):
    result = []
    for path in pending(root):
        value = json.loads(path.read_text())
        result.append({key: value[key] for key in ('operationid', 'state', 'previous', 'timeout')})
    return result


def ready_for_hook(root, timeout, bp_conf):
    for path in pending(root):
        value = json.loads(path.read_text())
        if (value.get('state') != 'APPLIED' or value.get('timeout') != timeout
                or not bp_conf.is_file() or bp_conf.is_symlink()
                or digest(bp_conf.read_bytes()) != value.get('appliedhash')):
            raise RuntimeError('NetBackup Hook timeout update is unconfirmed; resave backup.plugin.netbackup.hook.timeout after stopping policies')


def render(contents, seconds):
    seen = set()
    lines = []
    for line in contents.decode('utf-8').splitlines(keepends=True):
        match = re.match(r'^\s*(BPSTART_TIMEOUT|BPEND_TIMEOUT|CLIENT_READ_TIMEOUT)\s*=', line)
        if match:
            key = match[1]
            if key not in seen:
                lines.append(f'{key} = {seconds}\n')
                seen.add(key)
        else:
            lines.append(line)
    for key in KEYS:
        if key not in seen:
            if lines and not lines[-1].endswith('\n'):
                lines[-1] += '\n'
            lines.append(f'{key} = {seconds}\n')
    return ''.join(lines).encode('utf-8')


def atomic_json(path, value):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.parent.is_symlink():
        raise ValueError('Invalid Hook timeout journal directory')
    descriptor, name = tempfile.mkstemp(prefix='.timeout-', dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, 'w') as output:
            os.fchmod(output.fileno(), 0o600)
            json.dump(value, output)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def read_config(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid():
        raise ValueError('bp.conf must be an owned regular file')
    return path.read_bytes()


def replace_config(path, contents, expected):
    if digest(read_config(path)) != expected:
        raise ValueError('bp.conf changed since inspection; configuration was preserved')
    info = path.stat()
    descriptor, name = tempfile.mkstemp(prefix='.ablestack-timeout-', dir=path.parent)
    temporary = Path(name)
    try:
        shutil.copystat(path, temporary)
        with os.fdopen(descriptor, 'wb') as output:
            os.fchown(output.fileno(), info.st_uid, info.st_gid)
            os.fchmod(output.fileno(), stat.S_IMODE(info.st_mode))
            output.write(contents)
            output.flush()
            os.fsync(output.fileno())
        if digest(read_config(path)) != expected:
            raise ValueError('bp.conf changed during update; configuration was preserved')
        os.replace(temporary, path)
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def execute(request, context):
    operation = request['operationid']
    if str(uuid.UUID(operation)) != operation:
        raise ValueError('Invalid Hook timeout operation ID')
    seconds, previous = request['timeout'], request['previous']
    if any(type(value) is not int or not 1 <= value <= 2147483647 for value in (seconds, previous)):
        raise ValueError('Invalid Hook timeout in seconds')
    context.ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    with context.LOCK.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        action = request['action'].removeprefix('HOOK_TIMEOUT_')
        conf = context.setup.NETBACKUP_BP_CONF_PATH
        journal = context.ROOT / 'hook-timeouts' / (operation + '.pending.json')

        def idle():
            context.no_external_jobs(argparse.Namespace(netbackup_url=request['netbackupurl'],
                netbackup_apikey=request['netbackupapikey'], netbackup_client=request['hostname'],
                kvm_hostname=request['hostname'], kvm_ip=request['hostip']))

        def rollback(path):
            if not path.exists():
                return
            value = json.loads(path.read_text())
            current = digest(read_config(conf))
            if current not in (value['originalhash'], value['appliedhash']):
                raise ValueError('bp.conf no longer matches this update; rollback requires inspection')
            idle()
            if current != value['originalhash']:
                replace_config(conf, base64.b64decode(value['original']), current)
            context.setup.restart_netbackup_service()
            path.unlink()
            sync_directory(path.parent)

        def commit(path):
            if not path.exists():
                return
            value = json.loads(path.read_text())
            if value['state'] != 'APPLIED' or digest(read_config(conf)) != value['appliedhash']:
                raise ValueError('Hook timeout application remains unconfirmed')
            path.unlink()
            sync_directory(path.parent)

        if action == 'ROLLBACK':
            rollback(journal)
            return {'state': 'ROLLED_BACK'}
        if action == 'COMMIT':
            commit(journal)
            return {'state': 'COMMITTED'}
        if action == 'RECOVER':
            for path in pending(context.ROOT):
                value = json.loads(path.read_text())
                if previous == value['timeout'] and value['state'] == 'APPLIED':
                    commit(path)
                elif previous == value['previous']:
                    rollback(path)
                else:
                    raise ValueError('Global value does not identify the pending Host update')
            return {'state': 'RECOVERED'}

        status = context.status()
        if not status.get('configured') or not conf.exists():
            return {'state': 'SKIPPED'}
        if status.get('state') not in ('COMPLETED', 'CHECKED', 'POLICY_PATH_MISMATCH') or not status.get('policyinventorycomplete'):
            raise ValueError('Complete Host setup and confirm it is idle before updating Hook timeouts')
        idle()
        contents = read_config(conf)
        if action == 'CHECK':
            if pending(context.ROOT):
                raise ValueError('Reconcile the pending Hook timeout update first')
            return {'state': 'CHECKED', 'fingerprint': digest(contents)}
        if action != 'APPLY':
            raise ValueError('Invalid Hook timeout action')
        updated = render(contents, seconds)
        if not journal.exists():
            if updated == contents:
                return {'state': 'APPLIED'}
            if request.get('fingerprint') != digest(contents):
                raise ValueError('bp.conf changed since preflight')
            value = dict(operationid=operation, state='PREPARED', timeout=seconds, previous=previous,
                original=base64.b64encode(contents).decode('ascii'), originalhash=digest(contents), appliedhash=digest(updated))
            atomic_json(journal, value)
        else:
            value = json.loads(journal.read_text())
            if value['timeout'] != seconds or value['previous'] != previous:
                raise ValueError('Hook timeout operation identity differs')
        current = digest(contents)
        if current not in (value['originalhash'], value['appliedhash']):
            raise ValueError('bp.conf changed after update intent was saved')
        if current != value['appliedhash']:
            replace_config(conf, updated, current)
        if value['state'] != 'APPLIED':
            context.setup.restart_netbackup_service()
            value['state'] = 'APPLIED'
            atomic_json(journal, value)
        return {'state': 'APPLIED'}
