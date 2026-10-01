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

"""Fixed smoke of this process only; no delivered signals or guest writes."""
import base64
import hashlib
import gzip
import json
import os
from pathlib import Path
import runpy
import signal
import stat

config = json.loads(gzip.decompress(base64.b64decode('__CONFIG_BASE64__')).decode('utf-8'))
proof = dict(requestId=config['requestId'], readBundle=None, actionBundle=None,
             readRuntime=False, actionRuntime=False, code='CHECK_FAILED')
root = Path('/usr/libexec/ablestack-qemu-exec-tools/process')


def matched(profile):
    for bundle in config['bundles']:
        if bundle['profile'] != profile:
            continue
        valid = True
        for name, digest in bundle['sha256'].items():
            path = root / name
            info = path.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022 or info.st_size > 4194304:
                raise PermissionError('Unsafe adapter')
            if 'launcher' in name and not os.access(str(path), os.X_OK):
                raise PermissionError('Adapter launcher unavailable')
            if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                valid = False
        if valid:
            return bundle['id']
    return None


try:
    proof['readBundle'] = matched(config['readProfile'])
    if proof['readBundle'] is None:
        proof['code'] = 'TOOLS_REQUIRED'
    else:
        module = runpy.run_path(str(root / 'process_list_linux.py'), run_name='ablestack_readiness')
        record = module['stat_record'](Path('/proc/self/stat').read_text())
        if record[0] != os.getpid() or not record[4].isdigit():
            raise ValueError('Read identity mismatch')
        proof['readRuntime'] = True
        proof['code'] = 'READY'
        try:
            proof['actionBundle'] = matched(config['actionProfile'])
            if proof['actionBundle']:
                action = runpy.run_path(str(root / 'process_action_linux.py'), run_name='ablestack_readiness')
                if not callable(action.get('validate')) or not callable(action.get('action')):
                    raise ValueError('Action adapter exports unavailable')
                opener = action.get('open_target')
                if callable(opener):
                    # Only a hash-approved adapter may select its kernel-specific
                    # handle. Legacy kernels check signal 0 against this probe
                    # process; no signal is delivered to a VM workload.
                    descriptor, _ = opener(os.getpid())
                    os.close(descriptor)
                    proof['actionRuntime'] = True
                elif hasattr(os, 'pidfd_open') and hasattr(signal, 'pidfd_send_signal'):
                    descriptor = os.pidfd_open(os.getpid(), 0)
                    os.close(descriptor)
                    proof['actionRuntime'] = True
        except Exception:
            proof['actionRuntime'] = False
except FileNotFoundError:
    proof['code'] = 'TOOLS_REQUIRED'
except (OSError, ValueError, ImportError, KeyError):
    proof['code'] = 'CHECK_FAILED'
print(json.dumps(proof, separators=(',', ':')))
