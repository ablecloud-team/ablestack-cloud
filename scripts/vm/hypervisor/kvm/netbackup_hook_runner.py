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

"""Supervise only the local notify process group; Mold jobs and payloads remain owned by Mold."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import uuid


def process_info(pid):
    try:
        raw = Path('/proc', str(pid), 'stat').read_text()
        fields = raw.rsplit(')', 1)[1].split()
        if fields[0] == 'Z':
            return None
        boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
        return dict(pid=pid, token=boot + ':' + fields[19], ppid=int(fields[1]),
                    name=raw.split('(', 1)[1].rsplit(')', 1)[0])
    except FileNotFoundError:
        return None
    except (OSError, ValueError, IndexError) as error:
        raise RuntimeError('Cannot verify Hook process identity') from error


def caller_chain():
    result = []
    pid = os.getppid()
    for _ in range(12):
        if pid <= 1:
            break
        info = process_info(pid)
        if info is None:
            break
        result.append(info)
        if info['name'].startswith('bpbkar'):
            return result
        pid = info['ppid']
    # Without a recognizable NetBackup caller, only monitor our immediate caller.
    return result[:1]


def same_process(saved):
    if not saved or not saved.get('token'):
        return False
    current = process_info(saved['pid'])
    return current is not None and current['token'] == saved['token']


def netbackup_caller(chain):
    return next((dict(pid=item['pid'], token=item['token']) for item in chain
                 if item['name'].startswith('bpbkar')), None)


def paths(state_root, context, phase):
    root = Path(state_root) / 'hook-control'
    name = hashlib.sha256(context.encode()).hexdigest() + '.' + phase.lower()
    return root / (name + '.json'), root / (name + '.stop.json'), root / (name + '.lock')


def read_json(path):
    try:
        value = json.loads(path.read_text())
        if not isinstance(value, dict):
            raise ValueError('Invalid Hook control record')
        return value
    except FileNotFoundError:
        return None


def atomic(path, value):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix=path.name + '.')
    try:
        with os.fdopen(fd, 'w') as output:
            json.dump(value, output)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def log(message):
    print(time.strftime('%Y-%m-%d %H:%M:%S') + ' NetBackup Hook supervisor: ' + message, flush=True)


def matching_stop(record, receipt):
    return bool(record.get('attempt')) and receipt is not None and all(record.get(key) == receipt.get(key)
                                      for key in ('context', 'backupId', 'attempt'))


def accept_post(state_root, context, backup_id, job_id):
    record = read_json(paths(state_root, context, 'PRE')[0])
    if record is None:
        # Legacy contexts retain their existing Backup/Job ID checks in the shell worker.
        return True
    caller = netbackup_caller(caller_chain())
    matched = (record.get('context') == context and caller is not None
               and caller == record.get('netbackupCaller')
               and (not backup_id or record.get('backupId') == backup_id)
               and (not job_id or not record.get('jobId') or record['jobId'] == job_id))
    if not matched:
        log('Ignored POST from another or unconfirmed client attempt context=%s; saved state is preserved' % context)
    return matched


def request_stop(state_root, context, backup_id, job_id, status):
    record_path, stop_path, _ = paths(state_root, context, 'PRE')
    record = read_json(record_path)
    if not backup_id or not record or record.get('state') != 'RUNNING':
        return False
    caller = netbackup_caller(caller_chain())
    if (record.get('context') != context or record.get('backupId') != backup_id
            or (job_id and record.get('jobId') and record['jobId'] != job_id)
            or caller is None or caller != record.get('netbackupCaller')):
        log('Ignored unrelated or unconfirmed POST status=%s context=%s; active PRE is preserved' % (status, context))
        return False
    atomic(stop_path, dict(context=context, backupId=backup_id, attempt=record['attempt'],
                           reason='POST_STATUS_' + str(status)))
    log('Matched POST status=%s to PRE attempt=%s context=%s' % (status, record['attempt'], context))
    return True


def signal_group(pid, sig):
    try:
        os.killpg(pid, sig)
    except ProcessLookupError:
        pass


def group_exists(pid):
    try:
        os.killpg(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        # Lack of query permission is not evidence of termination.
        return True


def stop_group(pid, grace, process=None):
    signal_group(pid, signal.SIGTERM)
    deadline = time.monotonic() + grace
    while group_exists(pid) and time.monotonic() < deadline:
        if process is not None:
            process.poll()
        time.sleep(0.05)
    signal_group(pid, signal.SIGKILL)
    if process is not None:
        process.wait(timeout=5)


def context_unlocked(state_root, context):
    path = Path(state_root) / 'locks' / (context + '.lock')
    try:
        with path.open('r+') as stream:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
    except FileNotFoundError:
        return True
    except BlockingIOError:
        return False


def recover_worker(record, stop_path, state_root, context, grace):
    if not record or record.get('state') not in ('RUNNING', 'RECOVERY_REQUIRED'):
        return False
    if record.get('context') != context or same_process(record.get('supervisor')):
        raise RuntimeError('Previous Hook ownership cannot be reconciled')
    worker = record.get('worker')
    if not worker:
        raise RuntimeError('Previous Hook worker identity is missing')
    caller_lost = record.get('callers') and any(not same_process(item) for item in record['callers'])
    if not caller_lost and not matching_stop(record, read_json(stop_path)):
        raise RuntimeError('Previous Hook caller remains active; refusing an overlapping attempt')
    current = process_info(worker['pid'])
    if current is None or current['token'] == worker['token']:
        stop_group(worker['pid'], grace)
    # A reused PID belongs to a different process: never signal its group.
    if not context_unlocked(state_root, context):
        raise RuntimeError('Previous Hook termination is unconfirmed; context lock is preserved')
    log('Recovered abandoned Hook attempt=%s context=%s; Mold jobs and payloads are preserved' % (record['attempt'], context))
    return True


def run_supervised(state_root, context, phase, backup_id, job_id, command, timeout=86400, grace=10, poll=0.5):
    record_path, stop_path, lock_path = paths(state_root, context, phase)
    lock_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with lock_path.open('a+') as lock:
        os.fchmod(lock.fileno(), 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            log('Another %s Hook is supervised for context=%s' % (phase, context))
            return 1
        previous = read_json(record_path)
        recovered = recover_worker(previous, stop_path, state_root, context, grace)
        callers = caller_chain()
        supervisor = process_info(os.getpid())
        if not callers or supervisor is None:
            raise RuntimeError('Hook caller identity is unavailable; no worker was started')
        caller = netbackup_caller(callers)
        if (previous and previous.get('backupId') == backup_id and previous.get('jobId') == job_id
                and caller is not None and previous.get('netbackupCaller') == caller):
            raise RuntimeError('Retry reused the previous notify identity; a fresh NetBackup client attempt is required')
        record = dict(version=1, state='RUNNING', phase=phase, context=context, backupId=backup_id,
                      jobId=job_id, attempt=uuid.uuid4().hex, supervisor=supervisor, callers=callers,
                      netbackupCaller=caller, worker=None, startedAt=time.time())
        environment = dict(os.environ, ABLESTACK_NETBACKUP_HOOK_WORKER=phase)
        for name in ('ABLESTACK_NETBACKUP_RETIRED_PRE_PID', 'ABLESTACK_NETBACKUP_RETIRED_PRE_TOKEN'):
            environment.pop(name, None)
        if (phase == 'PRE' and previous and previous.get('worker')
                and (recovered or previous.get('state') in ('STOPPED', 'FAILED'))
                and not same_process(previous['worker']) and context_unlocked(state_root, context)):
            environment['ABLESTACK_NETBACKUP_RETIRED_PRE_PID'] = str(previous['worker']['pid'])
            environment['ABLESTACK_NETBACKUP_RETIRED_PRE_TOKEN'] = previous['worker']['token']
        reason = None
        exit_code = 1
        child = None
        previous_handlers = {}
        def interrupted(signum, frame):
            nonlocal reason
            reason = 'SIGNAL_' + str(signum)
        try:
            for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
                previous_handlers[sig] = signal.signal(sig, interrupted)
            child = subprocess.Popen(['bash', *command], start_new_session=True, close_fds=True,
                                     env=environment)
            record['worker'] = process_info(child.pid)
            atomic(record_path, record)
            deadline = time.monotonic() + timeout
            while True:
                result = child.poll()
                if result is not None:
                    exit_code = result if result >= 0 else 128 - result
                    break
                receipt = read_json(stop_path)
                if matching_stop(record, receipt):
                    reason = receipt['reason']
                if any(not same_process(item) for item in callers):
                    reason = 'CALLER_EXITED'
                if time.monotonic() >= deadline:
                    reason = 'TIMEOUT'
                if reason:
                    break
                time.sleep(poll)
        finally:
            # Includes descendants holding inherited fd 9 after their shell has exited.
            cleanup_error = None
            try:
                if child is not None:
                    stop_group(child.pid, grace, child)
            except (OSError, subprocess.SubprocessError) as error:
                cleanup_error = type(error).__name__
            for sig, handler in previous_handlers.items():
                signal.signal(sig, handler)
            record.update(state='STOPPED' if reason else 'COMPLETED' if exit_code == 0 else 'FAILED',
                          reason=reason, exitCode=exit_code, finishedAt=time.time())
            if cleanup_error or not context_unlocked(state_root, context):
                record['state'] = 'RECOVERY_REQUIRED'
                record['cleanupError'] = cleanup_error or 'Context lock remains held'
                exit_code = 1
            atomic(record_path, record)
            log('%s attempt=%s state=%s reason=%s context=%s' %
                (phase, record['attempt'], record['state'], reason or 'worker exit', context))
        return exit_code


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['run', 'request-stop', 'accept-post'])
    parser.add_argument('--phase', choices=['PRE', 'POST'], default='PRE')
    parser.add_argument('--context', required=True)
    parser.add_argument('--status', default='74')
    argv = list(sys.argv[1:] if argv is None else argv)
    split = argv.index('--') if '--' in argv else len(argv)
    args = parser.parse_args(argv[:split])
    command = argv[split + 1:]
    root = os.environ.get('STATE_ROOT', '/var/lib/ablestack/netbackup')
    backup_id = os.environ.get('BACKUP_ID', '')
    job_id = os.environ.get('NETBACKUP_JOB_ID', '')
    try:
        if args.action == 'accept-post':
            return 0 if accept_post(root, args.context, backup_id, job_id) else 2
        if args.action == 'request-stop':
            return 0 if request_stop(root, args.context, backup_id, job_id, args.status) else 2
        if not command or not backup_id:
            raise RuntimeError('Hook command and Backup ID are required')
        return run_supervised(root, args.context, args.phase, backup_id, job_id, command)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        log(str(error))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
