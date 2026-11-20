#!/usr/bin/env bash
# upload-systemvm-template.sh: ISO -> extract System VM -> HTTP/API registration -> Ready.
set -euo pipefail
export MOLD_SYSTEMVM_SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exec python3 - "$@" <<'PYTHON'
import argparse
import base64
import http.cookiejar
from contextlib import contextmanager
import getpass
import hashlib
import hmac
import http.server
import ipaddress
import json
import os
from pathlib import Path
import signal
import shutil
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


class Failure(Exception):
    pass


def log(message):
    print(time.strftime('[%Y-%m-%d %H:%M:%S] ') + message, flush=True)


def checksum(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def prompt(message, default=None):
    try:
        # Text r+ requires a seekable stream on Linux; a terminal is not seekable.
        # Read/write handles must be separate (stdin contains this Python heredoc).
        with open('/dev/tty', 'w') as output, open('/dev/tty', 'r') as terminal:
            output.write(message + (' [{}]'.format(default) if default else '') + ': ')
            output.flush()
            value = terminal.readline()
            if not value:
                raise Failure('Interactive input ended.')
            return value.strip() or default or ''
    except OSError:
        raise Failure('Interactive terminal required. Run from a terminal or use --config and explicit options.')


def choose(message, values):
    if not values:
        raise Failure(message + ': no candidates found.')
    if len(values) == 1:
        return values[0]
    for index, value in enumerate(values, 1):
        log('{}. {}'.format(index, value))
    selection = prompt(message + ' (number)')
    if not selection.isdigit() or not 1 <= int(selection) <= len(values):
        raise Failure('Invalid selection.')
    return values[int(selection) - 1]


def ensure_user_keys(session_call, login, check_only=False):
    keys = session_call('getUserKeys', id=login['userid'], sessionkey=login['sessionkey']).get('userkeys', {})
    if keys.get('apikeyaccess') is False:
        raise Failure('API key access is disabled. Enable it separately; access policy was not changed.')
    if keys.get('apikey') and keys.get('secretkey'):
        log('Existing API keys loaded into memory only; keys were not changed.')
        return keys
    if keys.get('apikey') or keys.get('secretkey'):
        raise Failure('Only one API key component exists. Refusing to rotate existing credentials; '
                      'check the account separately.')
    if check_only:
        raise Failure('No existing API keys. Run without --check-only to provision the initial key pair.')
    log('No existing API keys; provisioning initial API key pair for the logged-in administrator.')
    # registerUserKeys rotates a key pair: call only when BOTH existing values are absent.
    # Do not retry automatically if the response is lost: the next run queries getUserKeys first.
    result = session_call('registerUserKeys', id=login['userid'], sessionkey=login['sessionkey'])
    generated = result.get('userkeys', result)
    if not generated.get('apikey') or not generated.get('secretkey'):
        raise Failure('Key provisioning returned an incomplete response. Check existing keys before retrying.')
    log('Initial API keys provisioned; values are not logged or saved to local files.')
    return generated


def authenticated_api(args):
    if args.config:
        path = Path(args.config)
        if stat.S_IMODE(path.stat().st_mode) & 0o077:
            raise Failure('Config permissions must exclude group/others; run chmod 600 on the config file.')
        return Api(json.loads(path.read_text()))
    endpoint = args.api_url or 'http://ccvm:8080/client/api'
    log('Management API: ' + endpoint)
    config = dict(api_url=endpoint, ca_file=args.ca_file,
                  signature_algorithm=args.signature_algorithm, api_key='pending', secret_key='pending')
    api = Api(config)  # Validate endpoint and TLS before sending credentials.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        urllib.request.HTTPSHandler(context=api.context))

    def session_call(command, **params):
        params.update(command=command, response='json')
        try:
            with opener.open(endpoint, data=urllib.parse.urlencode(params).encode(), timeout=60) as response:
                body = json.load(response)
        except (urllib.error.URLError, ValueError, TimeoutError):
            raise Failure('Admin session {} failed; check address, credentials and API access.'.format(command))
        result = body.get(command.lower() + 'response', {})
        if not result or 'errorcode' in result:
            raise Failure('Admin session {} failed.'.format(command))
        return result

    username = args.username or prompt('Administrator username', 'admin')
    password = getpass.getpass('Administrator password: ')
    login = session_call('login', username=username, password=password, domain=args.domain)
    del password
    try:
        if str(login.get('type')) != '1':
            raise Failure('A root administrator account is required to register SYSTEM templates.')
        if not login.get('userid') or not login.get('sessionkey'):
            raise Failure('Incomplete login response (2FA or account configuration may require separate authentication).')
        keys = ensure_user_keys(session_call, login, args.check_only)
        api.key, api.secret = keys['apikey'], keys['secretkey']
        log('Administrator authenticated; API credentials ready.')
        return api
    finally:
        try:
            session_call('logout', sessionkey=login.get('sessionkey', ''))
        except Failure:
            log('WARNING: login session logout failed; no credentials were saved to disk.')


def discover_registration(args, api):
    if not args.zone_id:
        zones = api.list_records('listZones', 'zone', listall='true')
        selected = []
        for zone in zones:
            clusters = api.list_records('listClusters', 'cluster', zoneid=zone['id'], listall='true')
            if any(str(cluster.get('hypervisortype', '')).lower() == 'kvm' for cluster in clusters):
                if str(zone.get('allocationstate', '')).lower() != 'enabled':
                    raise Failure('A KVM zone is disabled: {}. Check cross-zone deployment before registration.'.format(zone['name']))
                selected.append(zone['id'])
        if not selected:
            raise Failure('No enabled KVM zones found. Check permissions or specify --zone-id.')
        args.zone_id = selected
        log('Automatically selected KVM zones: ' + ', '.join(selected))
    if not args.os_type_id:
        templates = api.templates()
        os_ids = sorted({t['ostypeid'] for t in templates
            if t.get('templatetype') == 'SYSTEM' and t.get('isready') is True
            and str(t.get('hypervisor', '')).lower() == 'kvm' and t.get('ostypeid')
            and t.get('zoneid') in args.zone_id})
        if not os_ids:
            raise Failure('No ready KVM SYSTEM template to infer OS type. Specify --os-type-id.')
        args.os_type_id = choose('Choose OS type UUID from existing ready System VM templates', os_ids)
        log('System VM OS type: ' + args.os_type_id)
    if not args.host_ip:
        parsed = urllib.parse.urlsplit(api.endpoint)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
            route.connect((parsed.hostname, parsed.port or (443 if parsed.scheme == 'https' else 80)))
            args.host_ip = route.getsockname()[0]
        log('Host IP selected using route to management server: ' + args.host_ip)
    address = ipaddress.IPv4Address(args.host_ip)
    if address.is_unspecified or address.is_loopback or address.is_multicast:
        raise Failure('host-ip must be a specific IPv4 address reachable from SSVMs.')


@contextmanager
def temporary_firewall(args, api):
    added = []
    zone = None
    try:
        if shutil.which('firewall-cmd') and subprocess.run(
                ['firewall-cmd', '--state'], stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL).returncode == 0:
            interfaces = json.loads(subprocess.check_output(['ip', '-j', 'address'], text=True))
            names = [item['ifname'] for item in interfaces if any(
                address.get('local') == args.host_ip for address in item.get('addr_info', []))]
            if len(names) != 1:
                raise Failure('Cannot identify HTTP interface for temporary firewall rule.')
            result = subprocess.run(['firewall-cmd', '--get-zone-of-interface=' + names[0]],
                                    capture_output=True, text=True)
            zone = result.stdout.strip()
            if result.returncode or not zone or zone == 'no zone':
                zone = subprocess.check_output(['firewall-cmd', '--get-default-zone'], text=True).strip()
            addresses = {socket.gethostbyname(urllib.parse.urlsplit(api.endpoint).hostname)}
            systemvms = api.list_records('listSystemVms', 'systemvm', listall='true')
            for vm in systemvms:
                if vm.get('systemvmtype') == 'secondarystoragevm' and vm.get('zoneid') in args.zone_id:
                    for field in ('privateip', 'publicip'):
                        if vm.get(field):
                            addresses.add(str(ipaddress.IPv4Address(vm[field])))
            if len(addresses) < 2:
                raise Failure('Cannot discover SSVM addresses for temporary HTTP access.')
            for address in sorted(addresses):
                rule = 'rule family="ipv4" source address="{}/32" port port="{}" protocol="tcp" accept'.format(
                    address, args.port)
                prefix = ['firewall-cmd', '--zone=' + zone]
                if subprocess.run(prefix + ['--query-rich-rule=' + rule],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
                    continue
                subprocess.run(prefix + ['--add-rich-rule=' + rule,
                    '--timeout=' + str(args.timeout + 300)], check=True, stdout=subprocess.DEVNULL)
                added.append(rule)
            log('Temporary HTTP firewall access added for SSVM/management source IPs only.')
        yield
    finally:
        for rule in reversed(added):
            result = subprocess.run(['firewall-cmd', '--zone=' + zone, '--remove-rich-rule=' + rule],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if result.returncode:
                log('WARNING: firewall cleanup failed; timed rule will expire automatically.')
        if added:
            log('Temporary HTTP firewall cleanup completed.')


@contextmanager
def prepare_image(args):
    if args.template:
        yield Path(args.template).resolve(strict=True)
        return
    for tool in ('guestfish', 'bzip2'):
        if not shutil.which(tool):
            raise Failure(tool + ' is required for ISO extraction.')
    source = Path(args.iso or args.base_image).resolve(strict=True)
    workspace = Path(tempfile.mkdtemp(prefix='mold-systemvm-', dir=args.work_dir))
    mounted = False
    preserve = args.keep_workdir or args.extract_only
    log('Working directory: ' + str(workspace))
    try:
        if args.base_image:
            base = source
            if not base.is_file():
                raise Failure('base-image must be a complete QCOW2 file.')
        else:
            if source.is_dir():
                iso_root = source
            elif source.is_file():
                iso_root = workspace / 'iso'
                iso_root.mkdir()
                subprocess.run(['mount', '-o', 'loop,ro', str(source), str(iso_root)], check=True)
                mounted = True
            else:
                raise Failure('iso must be an ISO file or an already mounted ISO directory.')
            whole = iso_root / 'template' / 'ablestack-template.qcow2'
            if whole.is_file():
                base = whole
            else:
                parts = sorted((iso_root / 'template').glob('ablestack-template.qcow2_split.*'))
                if not parts:
                    raise Failure('No ablestack-template.qcow2 or split image found in {}. '
                                  'Specify --iso with the directory containing template/.'.format(iso_root / 'template'))
                for index, part in enumerate(parts):
                    if index >= 26 * 26:
                        raise Failure('Too many split image parts.')
                    suffix = chr(97 + index // 26) + chr(97 + index % 26)
                    if part.name != 'ablestack-template.qcow2_split.' + suffix or not part.is_file():
                        raise Failure('Split image parts must be contiguous: aa, ab, ac, ...')
                total = sum(part.stat().st_size for part in parts)
                if shutil.disk_usage(workspace).free < total + 1024 ** 3:
                    raise Failure('Insufficient space for merged base image plus 1 GiB extraction reserve.')
                base = workspace / 'ablestack-template.qcow2'
                log('Merging {} parts ({} bytes); ISO is read-only.'.format(len(parts), total))
                with base.open('xb') as target:
                    for part in parts:
                        log('Merging ' + part.name)
                        with part.open('rb') as fragment:
                            shutil.copyfileobj(fragment, target, 8 * 1024 * 1024)
        env = dict(os.environ)
        env.setdefault('LIBGUESTFS_BACKEND', 'direct')
        command = ['guestfish', '--ro', '--format=qcow2', '-a', str(base), '-i']
        guest_dir = '/usr/share/ablestack'
        if args.guest_template:
            guest = args.guest_template
        else:
            log('Finding System VM template inside base QCOW2 (read-only)...')
            names = subprocess.check_output(command + ['ls', guest_dir], env=env, text=True).splitlines()
            candidates = [name for name in names if name.startswith('systemvmtemplate-')
                          and name.endswith(('.qcow2', '.qcow2.bz2')) and '/' not in name]
            if len(candidates) != 1:
                raise Failure('Expected exactly one inner System VM template; found {}. '
                              'Use --guest-template with an exact path.'.format(candidates))
            guest = guest_dir + '/' + candidates[0]
        if not guest.startswith('/') or '..' in Path(guest).parts or any(x in guest for x in '*?[]\r\n'):
            raise Failure('guest-template must be an exact absolute guest path without wildcards.')
        if not guest.endswith(('.qcow2', '.qcow2.bz2')):
            raise Failure('Inner template must end with .qcow2 or .qcow2.bz2.')
        size = int(subprocess.check_output(command + ['filesize', guest], env=env, text=True).strip())
        if size <= 0 or shutil.disk_usage(workspace).free < size + 64 * 1024 ** 2:
            raise Failure('Empty template or insufficient extraction space.')
        # Never serve the base QCOW2, ISO contents, or partial extraction.
        export = workspace / 'export'
        export.mkdir(mode=0o700)
        image = export / Path(guest).name
        log('Extracting {} ({} bytes)...'.format(guest, size))
        subprocess.run(command + ['download', guest, str(image)], env=env, check=True)
        if image.stat().st_size != size:
            raise Failure('Extracted template size mismatch.')
        if image.name.endswith('.bz2'):
            log('Checking extracted bzip2 archive integrity...')
            subprocess.run(['bzip2', '-t', str(image)], check=True)
        if base == workspace / 'ablestack-template.qcow2':
            base.unlink()
            log('Temporary merged base QCOW2 removed.')
        yield image
    finally:
        if mounted:
            result = subprocess.run(['umount', str(workspace / 'iso')])
            if result.returncode:
                preserve = True
                log('WARNING: temporary ISO mount could not be released; retaining workspace.')
        if preserve:
            log('Working files retained: ' + str(workspace))
        else:
            shutil.rmtree(workspace)
            log('Temporary working files removed.')


class Api:
    def __init__(self, config):
        self.endpoint = config['api_url']
        parsed = urllib.parse.urlsplit(self.endpoint)
        if parsed.scheme not in ('http', 'https') or not parsed.netloc or parsed.query or parsed.fragment:
            raise Failure('api_url must be an HTTP(S) API endpoint without query parameters.')
        if parsed.username or parsed.password:
            raise Failure('Do not embed credentials in api_url.')
        self.key = config.get('api_key') or prompt('Admin API key')
        self.secret = config.get('secret_key') or getpass.getpass('Admin secret key: ')
        if not self.key or not self.secret:
            raise Failure('API credentials are required.')
        self.context = ssl.create_default_context(cafile=config.get('ca_file'))
        algorithm = config.get('signature_algorithm', 'sha256').lower()
        if algorithm not in ('sha256', 'sha1'):
            raise Failure('signature_algorithm must be sha256 or sha1.')
        self.digestmod = getattr(hashlib, algorithm)
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPSHandler(context=self.context))

    def call(self, command, **params):
        params.update(command=command, apikey=self.key, response='json')
        params = {key: str(value) for key, value in params.items()}
        canonical = '&'.join(
            key.lower() + '=' + urllib.parse.quote_plus(params[key], safe='').replace('+', '%20')
            for key in sorted(params, key=str.lower)).lower()
        params['signature'] = base64.b64encode(hmac.new(
            self.secret.encode(), canonical.encode(), self.digestmod).digest()).decode()
        request = urllib.request.Request(self.endpoint,
            data=urllib.parse.urlencode(params).encode(), method='POST')
        try:
            with self.opener.open(request, timeout=60) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            try:
                body = json.load(error)
                message = body.get(command.lower() + 'response', body.get('errorresponse', {})).get('errortext', '')
            except (ValueError, AttributeError):
                message = ''
            raise Failure('API {} failed: HTTP {} {}'.format(command, error.code, message))
        except (urllib.error.URLError, ValueError, TimeoutError) as error:
            raise Failure('API {} failed (network, TLS, timeout or JSON error): {}'.format(
                command, type(error).__name__))
        payload = result.get(command.lower() + 'response', result.get('errorresponse'))
        if not isinstance(payload, dict):
            raise Failure('Unexpected API response for ' + command)
        if 'errorcode' in payload:
            raise Failure('API {}: {}'.format(command, payload.get('errortext', 'unknown error')))
        return payload

    def templates(self, **params):
        return self.list_records('listTemplates', 'template', templatefilter='all', listall='true',
                                 showunique='false', **params)

    def list_records(self, command, collection, **params):
        records = []
        for page in range(1, 10001):
            response = self.call(command, page=page, pagesize=100, **params)
            batch = response.get(collection, [])
            records.extend(batch)
            if not batch or len(records) >= int(response.get('count', len(records))):
                return records
        raise Failure(command + ' pagination limit exceeded.')


class ImageHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_HEAD(self):
        self.serve(False)

    def do_GET(self):
        self.serve(True)

    def serve(self, send_body):
        if self.path != self.server.image_route:
            self.send_error(404)
            return
        try:
            with self.server.image_path.open('rb') as source:
                self.send_response(200)
                self.send_header('Content-Type', 'application/octet-stream')
                self.send_header('Content-Length', str(os.fstat(source.fileno()).st_size))
                self.end_headers()
                if send_body:
                    while True:
                        chunk = source.read(1024 * 1024)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass


def validate_template(record, name, digest):
    if record.get('name') != name or record.get('templatetype') != 'SYSTEM':
        raise Failure('Existing template name/type does not match the requested SYSTEM template.')
    if str(record.get('hypervisor', '')).lower() != 'kvm':
        raise Failure('Existing template hypervisor is not KVM.')
    recorded = str(record.get('checksum', '')).lower()
    if recorded not in (digest, '{sha-256}' + digest):
        raise Failure('Existing template checksum differs or is unavailable. Use a new unique name; '
                      'do not overwrite the existing template.')


def register_image(args, image):
    if not image.is_file() or not str(image).endswith(('.qcow2', '.qcow2.bz2')):
        raise Failure('Template must be a regular .qcow2 or .qcow2.bz2 file.')
    log('Calculating image SHA-256...')
    digest = checksum(image)
    if args.sha256 and digest != args.sha256.lower():
        raise Failure('Image SHA-256 mismatch; nothing registered.')
    log('Template: ' + str(image))
    log('SHA-256: ' + digest)
    if not args.sha256:
        log('Checksum derived from ISO image; no trusted inner-template checksum supplied.')
    if args.extract_only:
        (image.parent / 'SHA256SUMS').write_text(digest + '  ' + image.name + '\n')
        log('Extraction validation complete. No HTTP server or Mold registration started.')
        return
    with temporary_firewall(args, args.api):
        register_ready(args, image, digest, args.api)


def main():
    parser = argparse.ArgumentParser(description='Serve a mounted ISO image and register a KVM SYSTEM template. '
        'Registers cross-zone; waits for every explicitly specified target zone. Does not recreate VMs.')
    source = parser.add_mutually_exclusive_group()
    source.add_argument('--iso', help='ISO file or mounted ISO directory; merge and extract automatically')
    source.add_argument('--base-image', help='Complete base QCOW2; automatically extract the inner template')
    source.add_argument('--template', help='Already extracted .qcow2 or .qcow2.bz2 (legacy mode)')
    parser.add_argument('--config', help='Private JSON: api_url, api_key, secret_key, optional ca_file')
    parser.add_argument('--api-url', help='Management API URL; default http://ccvm:8080/client/api')
    parser.add_argument('--username', help='Administrator username; default prompt suggests admin')
    parser.add_argument('--domain', default='/', help='Login domain path, default /')
    parser.add_argument('--ca-file', help='Trusted private CA certificate for HTTPS login')
    parser.add_argument('--signature-algorithm', choices=['sha256', 'sha1'], default='sha256')
    parser.add_argument('--sha256', help='Optional trusted SHA-256 of inner template; required with --template')
    parser.add_argument('--name', help='Unique template release name; defaults to inner filename for ISO/base mode')
    parser.add_argument('--guest-template', help='Exact guest template path when automatic discovery is ambiguous')
    parser.add_argument('--work-dir', default='/var/tmp', help='Existing parent directory for temporary work files')
    parser.add_argument('--keep-workdir', action='store_true', help='Keep extracted file after registration')
    parser.add_argument('--extract-only', action='store_true', help='Validate extraction only; retain output, do not call Mold')
    parser.add_argument('--check-only', action='store_true', help='Authenticate and discover settings only; no extraction or registration')
    parser.add_argument('--display-text')
    parser.add_argument('--os-type-id', help='OS UUID used for the validated System VM template')
    parser.add_argument('--zone-id', action='append', help='Target KVM zone UUID; repeat for ALL target zones')
    parser.add_argument('--host-ip', help='IPv4 address of this host reachable from SSVMs')
    parser.add_argument('--port', type=int, default=18080)
    parser.add_argument('--poll-interval', type=int, default=15)
    parser.add_argument('--timeout', type=int, default=7200, help='Ready wait timeout in seconds')
    parser.add_argument('--resume-template-id', help='Monitor an already registered template without registering again')
    args = parser.parse_args()
    if not (1 <= args.port <= 65535) or args.poll_interval < 1 or args.timeout < 1:
        raise Failure('Invalid port, poll interval or timeout.')
    if not any((args.iso, args.base_image, args.template)):
        script_dir = Path(os.environ.get('MOLD_SYSTEMVM_SCRIPT_DIR', '.')).resolve()
        # ISO layouts may place this script at the root or in root/update/.
        for iso_root in (script_dir, script_dir.parent):
            template_dir = iso_root / 'template'
            if (template_dir / 'ablestack-template.qcow2').is_file() or any(
                    path.is_file() for path in template_dir.glob('ablestack-template.qcow2_split.*')):
                args.iso = str(iso_root)
                break
        if not args.iso:
            sibling_isos = sorted(str(path) for path in script_dir.glob('*.iso') if path.is_file())
            if sibling_isos:
                args.iso = choose('Choose ISO next to the script', sibling_isos)
            elif Path('/mnt/iso-content/template').is_dir():
                args.iso = '/mnt/iso-content'
            else:
                args.iso = prompt('ISO file or extracted/mounted ISO directory', str(script_dir))
        log('ISO source: ' + args.iso)
    if args.template and not args.sha256:
        raise Failure('--template requires --sha256.')
    if args.sha256 and (len(args.sha256) != 64 or any(c not in '0123456789abcdef' for c in args.sha256.lower())):
        raise Failure('sha256 must be 64 hexadecimal characters.')
    if not args.extract_only:
        args.api = authenticated_api(args)
        discover_registration(args, args.api)
        if args.check_only:
            log('Preflight complete. No extraction, HTTP server or template registration performed.')
            return
    with prepare_image(args) as image:
        if not args.name:
            args.name = image.name
            for suffix in ('.qcow2.bz2', '.qcow2'):
                if args.name.endswith(suffix):
                    args.name = args.name[:-len(suffix)]
                    break
        register_image(args, image)


def register_ready(args, image, digest, api):
    zone_ids = list(dict.fromkeys(args.zone_id))
    zones = {}
    for zone_id in zone_ids:
        response = api.call('listZones', id=zone_id, listall='true')
        matching = [zone for zone in response.get('zone', []) if zone.get('id') == zone_id]
        if len(matching) != 1:
            raise Failure('Cannot access target zone ' + zone_id)
        if str(matching[0].get('allocationstate', '')).lower() != 'enabled':
            raise Failure('Target zone is not enabled: ' + zone_id)
        zones[zone_id] = matching[0].get('name', zone_id)
    response = api.call('listOsTypes', id=args.os_type_id)
    if not any(item.get('id') == args.os_type_id for item in response.get('ostype', [])):
        raise Failure('OS type UUID is not available.')
    records = api.templates(**({'id': args.resume_template_id} if args.resume_template_id else {'name': args.name}))
    records = [record for record in records if record.get('name') == args.name]
    ids = {record.get('id') for record in records}
    if len(ids) > 1:
        raise Failure('Multiple existing templates share this name; specify a unique release name.')
    if args.resume_template_id and not records:
        raise Failure('Resume template was not found with this name.')
    for record in records:
        validate_template(record, args.name, digest)
    template_id = next(iter(ids), None)
    server = None
    try:
        server = http.server.ThreadingHTTPServer((args.host_ip, args.port), ImageHandler)
        server.daemon_threads = True
        server.image_path = image
        # Stable route allows a failed/interrupted download to resume on the next run.
        server.image_route = '/systemvm/' + digest + '/' + urllib.parse.quote(image.name, safe='')
        url = 'http://{}:{}{}'.format(args.host_ip, args.port, server.image_route)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        log('Serving only the selected image: ' + url)
        if template_id:
            log('Reusing existing template ID: ' + template_id)
            # A partially downloaded registration must still reference this server.
            for record in records:
                old_url = record.get('url')
                if record.get('isready') is not True and old_url != url:
                    raise Failure('Pending template URL is different or unavailable. Restart with the original '
                                  'host IP, port and image filename; existing registration was left intact.')
        else:
            log('Registering SYSTEM template cross-zone (zoneid=-1).')
            response = api.call('registerTemplate', name=args.name,
                displaytext=args.display_text or args.name, url=url, format='QCOW2', hypervisor='KVM',
                templatetype='SYSTEM', zoneid='-1', ostypeid=args.os_type_id,
                arch='x86_64', checksum='{SHA-256}' + digest, ispublic='false',
                isfeatured='false', passwordenabled='false', requireshvm='true')
            registered = response.get('template', [])
            if isinstance(registered, dict):
                registered = [registered]
            ids = {item.get('id') for item in registered if item.get('id')}
            if len(ids) != 1:
                raise Failure('Registration response has no unique template ID. Check Mold before rerunning.')
            template_id = ids.pop()
            log('Registered template ID: ' + template_id)
        deadline = time.monotonic() + args.timeout
        previous = None
        while True:
            all_ready = True
            statuses = []
            for zone_id in zone_ids:
                rows = [row for row in api.templates(id=template_id, zoneid=zone_id)
                        if row.get('id') == template_id and row.get('zoneid') == zone_id]
                ready = bool(rows) and all(row.get('isready') is True for row in rows)
                all_ready = all_ready and ready
                descriptions = [str(row.get('status', 'not ready')) for row in rows]
                statuses.append('{}: {}'.format(zones[zone_id], 'Ready' if ready else
                    '; '.join(descriptions) or 'waiting for zone entry'))
                for row in rows:
                    if not ready and str(row.get('status', '')).lower().startswith(('download error', 'error')):
                        raise Failure('Download failed in {}: {}'.format(zones[zone_id], row.get('status')))
            state = ' | '.join(statuses)
            if state != previous:
                log(state)
                previous = state
            if all_ready:
                log('SUCCESS: all specified target zones are Ready. Template ID: ' + template_id)
                log('Existing VMs were not recreated. Boot and validate test System VMs before rollout.')
                return
            if time.monotonic() >= deadline:
                raise Failure('Ready wait timed out. Registration remains; rerun with the same arguments '
                              'and --resume-template-id ' + template_id)
            time.sleep(args.poll_interval)
    finally:
        if server is not None:
            server.shutdown()
            server.server_close()
            log('Temporary HTTP server stopped. Existing registrations are retained.')
        if template_id:
            log('Template ID for verification/resume: ' + template_id)


def interrupted(signum, frame):
    raise KeyboardInterrupt


signal.signal(signal.SIGTERM, interrupted)
try:
    main()
except KeyboardInterrupt:
    print('Interrupted. Check the retained registration before resuming.', file=sys.stderr)
    sys.exit(130)
except (Failure, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
    print('ERROR: ' + str(error), file=sys.stderr)
    sys.exit(1)
PYTHON
