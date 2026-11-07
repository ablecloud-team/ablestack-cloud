#!/usr/bin/env python3
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


"""Run as root inside a disposable network namespace: unshare -n python3 test_modifyvlan.py."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess

SCRIPT = str(Path(__file__).with_name('modifyvlan.sh'))
checks = []

def run(*args, ok=True):
    p = subprocess.run(args, capture_output=True, text=True, timeout=15)
    assert (p.returncode == 0) == ok, (args, p.returncode, p.stdout, p.stderr)
    return p

def exists(name):
    return subprocess.run(['ip', 'link', 'show', 'dev', name], capture_output=True).returncode == 0

def bridge(pif, vid):
    legacy = 'br%s-%s' % (pif, vid)
    token = base64.b32encode(hashlib.sha256(pif.encode()).digest()[:5]).decode().lower()
    return legacy if len(legacy) <= 15 else 'br%s-%s' % (token, vid)

def child(pif, vid):
    legacy = '%s.%s' % (pif, vid)
    return legacy if len(legacy) <= 15 else 'vlan' + hashlib.sha256(('%s\n%s' % (pif, vid)).encode()).hexdigest()[:11]

def modify(op, pif, vid, br=None, ok=True, delete=True):
    return run('bash', SCRIPT, '-o', op, '-p', pif, '-v', str(vid), '-b', br or bridge(pif, vid), '-d', str(delete).lower(), ok=ok)

for pif in ('eth0', 'ens1f0np0', 'bond0', 'bond12345678901', 'ens123456789012'):
    run('ip', 'link', 'add', pif, 'type', 'dummy')
    run('ip', 'link', 'set', pif, 'up')
    for vid in (1, 631, 1000, 3832, 4094):
        br, dev = bridge(pif, vid), child(pif, vid)
        modify('add', pif, vid)
        assert exists(br) and exists(dev)
        if vid == 3832 and os.environ.get('VLAN_PROBE_CLASSPATH'):
            run('java', '-cp', os.environ['VLAN_PROBE_CLASSPATH'], 'VlanLookupProbe', br, str(vid))
            checks.append({'pif':pif,'kernel_vlan_lookup_via_deployed_agent':'PASS'})
        before = Path('/sys/class/net/%s/ifindex' % br).read_text()
        modify('add', pif, vid)
        assert Path('/sys/class/net/%s/ifindex' % br).read_text() == before
        modify('delete', pif, vid, delete=False)
        assert exists(br)
        run('ip', 'tuntap', 'add', 'vnet1580', 'mode', 'tap')
        run('ip', 'link', 'set', 'vnet1580', 'master', br)
        modify('delete', pif, vid, ok=False)
        assert exists(br) and exists(dev) and exists('vnet1580')
        run('ip', 'link', 'delete', 'vnet1580')
        # Compact deletion receives the name token from IVS or undefined from the bridge driver.
        modify('delete', 'undefined', vid, br=br)
        assert not exists(br) and not exists(dev)
        modify('delete', pif, vid)
        checks.append({'pif': pif, 'vlan': vid, 'bridge': br, 'vlan_device': dev, 'lifecycle': 'PASS'})

# Pre-existing, unmarked legacy bridge and VLAN retain their ifindexes on reconnect.
br = bridge('eth0', 632)
run('ip', 'link', 'add', 'link', 'eth0', 'name', 'eth0.632', 'type', 'vlan', 'id', '632')
run('ip', 'link', 'add', br, 'type', 'bridge')
run('ip', 'link', 'set', 'eth0.632', 'master', br)
index = Path('/sys/class/net/%s/ifindex' % br).read_text()
modify('add', 'eth0', 632)
assert Path('/sys/class/net/%s/ifindex' % br).read_text() == index
modify('delete', 'eth0', 632)
checks.append({'legacy_unmarked_reuse_and_delete': 'PASS'})

# Forced compact-name collision must not attach/delete the other parent's network.
vid = 3832
br = bridge('ens1f0np0', vid)
modify('add', 'ens1f0np0', vid)
modify('add', 'bond0', vid, br=br, ok=False)
modify('delete', 'bond0', vid, br=br, ok=False)
modify('delete', 'bond0', 3831, br=br, ok=False)
assert not exists('bond0.3832') and exists(child('ens1f0np0', vid))
modify('delete', 'ens1f0np0', vid)
checks.append({'bridge_collision_refused': 'PASS'})

# A non-VLAN device at the child name, or a VLAN owned by another bridge, is never reused.
run('ip', 'link', 'add', 'eth0.633', 'type', 'dummy')
modify('add', 'eth0', 633, ok=False)
assert exists('eth0.633') and not exists(bridge('eth0', 633))
run('ip', 'link', 'delete', 'eth0.633')
run('ip', 'link', 'add', 'link', 'eth0', 'name', 'eth0.634', 'type', 'vlan', 'id', '635')
modify('add', 'eth0', 634, ok=False)
assert exists('eth0.634') and not exists(bridge('eth0', 634))
run('ip', 'link', 'delete', 'eth0.634')
checks.append({'child_name_and_vid_collision_refused': 'PASS'})

# Concurrent invocations converge on a single pair without a spurious failure.
args = ['bash', SCRIPT, '-o', 'add', '-p', 'ens1f0np0', '-v', '4094', '-b', bridge('ens1f0np0', 4094)]
workers = [subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE) for _ in range(8)]
for worker in workers:
    out, err = worker.communicate(timeout=20)
    assert worker.returncode == 0, (out, err)
modify('delete', 'ens1f0np0', 4094)
checks.append({'concurrent_creation': 'PASS'})
for vid in (0, 4096):
    modify('add', 'eth0', vid, ok=False)
checks.append({'vlan_boundaries': 'PASS'})
print(json.dumps({'checks': checks, 'count': len(checks), 'result': 'PASS'}))
