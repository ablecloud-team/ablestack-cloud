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
"""Owned, restricted VirtualBMC lifecycle. Never prints library errors or credentials."""
import configparser
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path('/var/lib/cloudstack/vbmc')
CONF = ROOT / 'virtualbmc.conf'


def run(args, check=True, env=None, data=None):
    result = subprocess.run(args, input=data, text=True, capture_output=True,
                            timeout=25, env=env)
    if check and result.returncode:
        raise RuntimeError('command failed: ' + Path(args[0]).name)
    return result


def atomic(path, content):
    tmp = path.with_suffix('.tmp')
    with open(tmp, 'w', encoding='utf8') as stream:
        os.chmod(tmp, 0o600)
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(tmp, path)


def environment():
    env = os.environ.copy()
    env.pop('CLOUD_VBMC_PASSWORD', None)
    env['VIRTUALBMC_CONFIG'] = str(CONF)
    return env


def vbmc(args, password=None):
    # Keep the credential out of process argv and all captured diagnostics.
    env = environment()
    if password is not None:
        env['CLOUD_VBMC_PASSWORD'] = password
    code = ("import os,sys; from virtualbmc.cmd.vbmc import main; "
            "a=sys.argv[1:]; p=os.environ.pop('CLOUD_VBMC_PASSWORD',None); "
            "a.extend(['--password',p] if p else []); sys.exit(main(a))")
    return run([sys.executable, '-c', code] + args, env=env)


def records():
    return [json.loads(p.read_text()) for p in sorted(ROOT.glob('*.json'))]


def firewall_rules(item):
    base = ['-d', item['address'], '-p', 'udp', '--dport', str(item['port'])]
    tag = ['-m', 'comment', '--comment', 'cloud-vbmc-' + item['token']]
    return [
        ['ipv4', 'filter', 'INPUT', '-100'] + base + ['!', '-s', item['cidr']] + tag + ['-j', 'DROP'],
        ['ipv4', 'filter', 'INPUT', '-99'] + base + ['-s', item['cidr']] + tag + ['-j', 'ACCEPT'],
    ]


def firewall_rule(item, remove=False, verify=False):
    rules = firewall_rules(item)
    if remove:
        rules.reverse()  # Remove acceptance first. Endpoint is already stopped.
    for permanent in (True, False):
        prefix = ['firewall-cmd'] + (['--permanent'] if permanent else []) + ['--direct']
        for rule in rules:
            present = run(prefix + ['--query-rule'] + rule, check=False).returncode == 0
            if verify:
                if not present:
                    raise RuntimeError('Virtual BMC source restriction missing')
            elif remove and present:
                run(prefix + ['--remove-rule'] + rule)
            elif not remove and not present:
                run(prefix + ['--add-rule'] + rule)


def firewall():
    run(['firewall-cmd', '--state'])
    text = Path('/etc/firewalld/firewalld.conf').read_text()
    if not re.search(r'^FirewallBackend=iptables\s*$', text, re.MULTILINE):
        raise RuntimeError('firewalld iptables backend is required')
    for item in records():
        firewall_rule(item)


def domain(item):
    uuid = run(['virsh', 'domuuid', item['name']]).stdout.strip()
    state = run(['virsh', 'domstate', item['name']]).stdout.strip()
    if uuid != item['uuid'] or state != 'running':
        raise RuntimeError('domain identity or running state changed')


def config(item):
    parser = configparser.ConfigParser()
    parser.read(ROOT / 'instances' / item['name'] / 'config')
    if not parser.has_section('VirtualBMC'):
        raise RuntimeError('VirtualBMC configuration missing')
    return parser['VirtualBMC']


def health(item):
    domain(item)
    c = config(item)
    if int(c['port']) != item['port'] or c['address'] != item['address']:
        raise RuntimeError('endpoint ownership mismatch')
    env = environment()
    env['IPMI_PASSWORD'] = c['password']
    run(['ipmitool', '-I', 'lanplus', '-H', item['address'], '-p', str(item['port']),
         '-U', c['username'], '-E', '-N', '2', '-R', '1', 'chassis', 'power', 'status'], env=env)


def setup():
    (ROOT / "instances").mkdir(mode=0o700, exist_ok=True)
    for command in ['firewall-cmd', 'virsh', 'ipmitool', 'systemctl', 'vbmcd']:
        if not shutil.which(command):
            raise RuntimeError('required host dependency missing: ' + command)
    atomic(CONF, '[default]\nconfig_dir=' + str(ROOT / 'instances') +
           '\npid_file=' + str(ROOT / 'master.pid') +
           '\nserver_port=50892\nshow_passwords=false\n[log]\ndebug=false\n')
    helper = str(Path(__file__).resolve())
    # Isolated daemon/config: never adopt or terminate an unmanaged vbmcd.
    unit = ('[Unit]\nDescription=CloudStack owned Virtual BMC\nAfter=network.target libvirtd.service firewalld.service\nBindsTo=firewalld.service\n'
            '[Service]\nType=simple\nUMask=0077\nEnvironment=VIRTUALBMC_CONFIG=' + str(CONF) +
            '\nExecStartPre=/usr/bin/python3 ' + helper + ' firewall\nExecStart=' + shutil.which('vbmcd') +
            ' --foreground\nRestart=on-failure\nRestartSec=5\n[Install]\nWantedBy=multi-user.target\n')
    atomic(Path('/etc/systemd/system/cloudstack-vbmcd.service'), unit)
    atomic(Path('/etc/systemd/system/cloudstack-vbmc-reconcile.service'),
           '[Unit]\nDescription=Reconcile CloudStack VBMC endpoints\nAfter=cloudstack-vbmcd.service\n'
           '[Service]\nType=oneshot\nUMask=0077\nExecStart=/usr/bin/python3 ' + helper + ' reconcile\n')
    atomic(Path('/etc/systemd/system/cloudstack-vbmc-reconcile.timer'),
           '[Unit]\nDescription=Check CloudStack VBMC domain identity\n[Timer]\nOnBootSec=30\nOnUnitActiveSec=30\n'
           '[Install]\nWantedBy=timers.target\n')
    run(['systemctl', 'daemon-reload'])
    # The pre-start firewall command must not acquire the lifecycle lock.
    run(['systemctl', 'enable', '--now', 'cloudstack-vbmcd.service', 'cloudstack-vbmc-reconcile.timer'])


def delete(item, path):
    # Deletion is repeatable even if an earlier command timed out after success.
    cfg = ROOT / 'instances' / item['name'] / 'config'
    if cfg.exists():
        c = config(item)
        if int(c['port']) != item['port'] or c['address'] != item['address']:
            raise RuntimeError('refusing to delete a different endpoint')
        vbmc(['delete', item['name']])
    if cfg.exists():
        raise RuntimeError('configuration removal unconfirmed')
    listeners = run(['ss', '-H', '-lun', 'sport', '=', str(item['port'])]).stdout.strip()
    if listeners:
        raise RuntimeError('port is still listening')
    # A failed removal keeps identity and reservation available for an exact retry.
    firewall_rule(item, remove=True)
    path.unlink()


def execute(action, name, port, token, address, cidr, password):
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', name) or not re.fullmatch(r'[a-f0-9-]{36}', token):
        raise RuntimeError('invalid endpoint identity')
    port = int(port)
    if not 1024 <= port <= 65535:
        raise RuntimeError('invalid port')
    address = str(ipaddress.IPv4Address(address))
    cidr = str(ipaddress.IPv4Network(cidr, strict=False))
    path = ROOT / (str(port) + '.json')
    item = json.loads(path.read_text()) if path.exists() else None
    if item and (item['token'] != token or item['name'] != name or item['address'] != address or item['cidr'] != cidr):
        raise RuntimeError('allocation ownership mismatch')
    if action == 'delete' and item is None:
        # A missing manifest is safe only if neither a config nor a listener exists.
        if (ROOT / 'instances' / name / 'config').exists() or run(['ss', '-H', '-lun', 'sport', '=', str(port)]).stdout.strip():
            raise RuntimeError('unowned endpoint requires manual reconciliation')
        return
    if action == 'start':
        if not password or '%' in password or not re.fullmatch(r'[!-~]{16,20}', password):
            raise RuntimeError('invalid credential')
        if item is None:
            if (ROOT / 'instances' / name / 'config').exists() or run(['ss', '-H', '-lun', 'sport', '=', str(port)]).stdout.strip():
                raise RuntimeError('endpoint already in use')
            item = dict(name=name, port=port, token=token, address=address, cidr=cidr,
                        uuid=run(['virsh', 'domuuid', name]).stdout.strip())
            domain(item)
            atomic(path, json.dumps(item))
        setup()
        firewall()
        cfg = ROOT / 'instances' / name / 'config'
        if not cfg.exists():
            vbmc(['add', name, '--port', str(port), '--address', address, '--username', 'vbmc'], password)
        vbmc(['start', name])
        time.sleep(1)
        health(item)
    elif action == 'delete':
        setup()
        delete(item, path)
    elif action == 'check':
        if item is None:
            raise RuntimeError('endpoint manifest missing')
        run(['systemctl', 'is-active', 'cloudstack-vbmcd.service'])
        run(['firewall-cmd', '--state'])
        firewall_rule(item, verify=True)
        health(item)
    else:
        raise RuntimeError('unsupported operation')


def main():
    os.umask(0o077)
    os.environ['LC_ALL'] = 'C'
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(ROOT, 0o700)
    if sys.argv[1:] == ['firewall']:
        firewall()
        # Runs before the owned daemon starts, so no concurrent config writer.
        # A recreated domain with the same name must never inherit power control.
        for item in records():
            try:
                domain(item)
            except Exception:
                cfg = ROOT / 'instances' / item['name'] / 'config'
                if cfg.exists():
                    parser = configparser.ConfigParser()
                    parser.read(cfg)
                    parser['VirtualBMC']['active'] = 'False'
                    import io
                    content = io.StringIO()
                    parser.write(content)
                    atomic(cfg, content.getvalue())
        return
    with open(ROOT / 'lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if sys.argv[1:] == ['reconcile']:
            firewall()
            if records():
                run(['systemctl', 'start', 'cloudstack-vbmcd.service'])
            for item in records():
                try:
                    domain(item)
                except Exception:
                    # Cloud defines/undefines domains; never recreate or power on a VM.
                    vbmc(['stop', item['name']])
            return
        if len(sys.argv) != 7:
            raise RuntimeError('invalid arguments')
        execute(*sys.argv[1:], os.environ.pop('CLOUD_VBMC_PASSWORD', None))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('Virtual BMC operation not confirmed; allocation remains reserved', file=sys.stderr)
        sys.exit(1)
