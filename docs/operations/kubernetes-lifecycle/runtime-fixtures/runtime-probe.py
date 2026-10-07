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
"""Read-only cluster-31 probes. Credentials and raw API output stay private."""
import argparse
import base64
import hashlib
import hmac
import ipaddress
import json
import os
import pathlib
import stat
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

MANAGEMENT = '10.10.31.10'
READ_COMMANDS = ('listKubernetesClusters', 'listLoadBalancerRules',
                 'listLoadBalancerRuleInstances', 'listPortForwardingRules',
                 'listFirewallRules', 'listPublicIpAddresses', 'queryAsyncJobResult')


class ProbeError(Exception):
    pass


def private_json(path):
    p = pathlib.Path(path)
    if os.name == 'posix' and stat.S_IMODE(p.stat().st_mode) & 0o077:
        raise ProbeError('private input must not be accessible by group or others')
    try:
        return json.loads(p.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        raise ProbeError('cannot read private JSON input') from None


def private_output(path, value):
    # Exclusive creation preserves earlier diagnostic evidence.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        json.dump(value, f, indent=2)


def api_params(values):
    params = {}
    for value in values:
        key, sep, item = value.partition('=')
        key = key.lower()
        if not sep or not key or key in params:
            raise ProbeError('API parameters must be unique key=value pairs')
        if key.replace('-', '').replace('_', '') in ('apikey', 'secretkey', 'signature', 'response', 'command'):
            raise ProbeError('credential and command overrides are forbidden')
        params[key] = item
    if 'pagesize' in params:
        params.setdefault('page', '1')
        try:
            if int(params['pagesize']) < 1 or int(params['page']) < 1:
                raise ValueError()
        except ValueError:
            raise ProbeError('page and pagesize must be positive integers') from None
    return params


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def probe_api(args):
    credentials = private_json(args.credentials)
    parsed = urllib.parse.urlsplit(credentials['url'])
    if (parsed.hostname != MANAGEMENT or parsed.port != 8080
            or parsed.scheme not in ('http', 'https')
            or parsed.path != '/client/api' or parsed.query or parsed.fragment
            or parsed.username or parsed.password):
        raise ProbeError('API destination must be cluster-31 management /client/api')
    params = api_params(args.param)
    if args.validate_only:
        print(json.dumps({'mode': 'api', 'command': args.command,
                          'page': params.get('page'), 'pagesize': params.get('pagesize')}))
        return 0
    params.update(command=args.command, apiKey=credentials['apiKey'], response='json')
    canonical = '&'.join(key + '=' + urllib.parse.quote(str(params[key]), safe='')
                         for key in sorted(params, key=str.lower)).lower()
    signature = base64.b64encode(hmac.new(credentials['secretKey'].encode(),
                                         canonical.encode(), hashlib.sha256).digest()).decode()
    url = credentials['url'] + '?' + urllib.parse.urlencode(dict(params, signature=signature))
    status = 200
    try:
        with urllib.request.build_opener(NoRedirect()).open(url, timeout=30) as response:
            raw = json.load(response)
    except urllib.error.HTTPError as error:
        status = error.code
        try:
            raw = json.load(error)
        except ValueError:
            raise ProbeError('HTTP error %s without a JSON response' % status) from None
    except (OSError, ValueError):
        raise ProbeError('API transport or response decoding failed') from None
    private_output(args.private_output, raw)
    if not isinstance(raw, dict) or len(raw) != 1:
        raise ProbeError('unexpected API response envelope')
    response = next(iter(raw.values()))
    if not isinstance(response, dict):
        raise ProbeError('unexpected API response payload')
    summary = {'mode': 'api', 'command': args.command, 'http': status,
               'errorcode': response.get('errorcode'), 'count': response.get('count'),
               'jobstatus': response.get('jobstatus')}
    print(json.dumps(summary))
    if status != 200 or response.get('errorcode') is not None:
        raise ProbeError('API returned an error; raw response saved privately')
    return 0


def probe_lb(args):
    target = ipaddress.ip_address(args.public_ip)
    if target not in ipaddress.ip_network('10.10.31.0/24'):
        raise ProbeError('public IP must belong to the cluster-31 test subnet')
    if not 1 <= args.port <= 65535 or not 1 <= args.duration <= 86400:
        raise ProbeError('port must be 1..65535 and duration 1..86400 seconds')
    network = ipaddress.ip_network(args.allowed_source_cidr, strict=True)
    if ipaddress.ip_address(MANAGEMENT) not in network:
        raise ProbeError('management caller is outside the allowed source CIDR')
    ssh = private_json(args.ssh_argv)
    if (not isinstance(ssh, list) or not ssh or not all(isinstance(s, str) for s in ssh)
            or ssh[-1] != 'root@' + MANAGEMENT):
        raise ProbeError('SSH destination must be root@cluster-31 management')
    summary = {'mode': 'lb', 'source': MANAGEMENT, 'public_ip': str(target),
               'port': args.port, 'duration_seconds': args.duration}
    print(json.dumps(summary), flush=True)
    if args.validate_only:
        return 0
    # Run the HTTP requests on the permitted caller, never on the local WSL host.
    script = '''import datetime,json,time,urllib.request
url=URL; duration=DURATION
start=time.monotonic(); begun=datetime.datetime.now(datetime.timezone.utc).isoformat()
requests=0; errors=0; maxlat=0; kinds={}
while time.monotonic()-start<duration:
 t=time.monotonic(); requests+=1
 try:
  with urllib.request.urlopen(url,timeout=2) as r:
   if r.status!=200 or not r.read().decode().startswith('rt-web-'):raise ValueError()
 except Exception as e:
  errors+=1; name=type(e).__name__;kinds[name]=kinds.get(name,0)+1
 maxlat=max(maxlat,time.monotonic()-t)
 if requests%600==0:print(json.dumps({'event':'sample','requests':requests,'errors':errors}),flush=True)
 time.sleep(max(0,.1-(time.monotonic()-t)))
print(json.dumps({'event':'result','started_at':begun,'ended_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'duration_seconds':time.monotonic()-start,'requests':requests,'errors':errors,'error_types':kinds,'max_latency_seconds':maxlat}),flush=True)
raise SystemExit(1 if errors else 0)
'''.replace('URL', repr('http://%s:%d/' % (target, args.port))).replace('DURATION', str(args.duration))
    with subprocess.Popen(ssh + ['python3 -u -'], stdin=subprocess.PIPE,
                          stdout=sys.stdout, stderr=subprocess.DEVNULL, text=True) as child:
        try:
            child.communicate(script)
        except KeyboardInterrupt:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
            return 130
        if child.returncode:
            raise ProbeError('management probe exited with status %s' % child.returncode)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='mode', required=True)
    lb = commands.add_parser('lb', help='HTTP from the permitted management caller')
    lb.add_argument('--ssh-argv', required=True, help='private JSON argv; never printed')
    lb.add_argument('--public-ip', required=True)
    lb.add_argument('--port', type=int, required=True)
    lb.add_argument('--duration', type=int, default=60)
    lb.add_argument('--allowed-source-cidr', default=MANAGEMENT + '/32')
    lb.add_argument('--validate-only', action='store_true')
    api = commands.add_parser('api', help='read-only signed API probe with fail-closed errors')
    api.add_argument('--credentials', required=True, help='private JSON url/apiKey/secretKey')
    api.add_argument('--command', choices=READ_COMMANDS, required=True)
    api.add_argument('--param', action='append', default=[])
    api.add_argument('--private-output', required=True, help='new private raw-response file')
    api.add_argument('--validate-only', action='store_true')
    args = parser.parse_args()
    try:
        return probe_lb(args) if args.mode == 'lb' else probe_api(args)
    except (ProbeError, OSError, KeyError, ValueError) as error:
        # Never print request URLs, response text, keys, or the SSH argv.
        print('probe failed: ' + (str(error) if isinstance(error, ProbeError)
                                 else type(error).__name__), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
