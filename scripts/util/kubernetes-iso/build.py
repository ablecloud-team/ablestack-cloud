#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
"""Build a version-locked Mold CKS payload without changing host container services."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
import urllib.parse

import yaml

HERE = Path(__file__).resolve().parent
MAX_ISO_BYTES = 2 * 1024**3


def run(args, **kwargs):
    return subprocess.check_output([str(x) for x in args], text=True, **kwargs).strip()


def sha256(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def safe_path(value):
    p = PurePosixPath(value)
    if p.is_absolute() or not p.parts or '..' in p.parts or '\\' in value:
        raise ValueError('unsafe payload path: ' + value)
    return str(p)


def check_recipe(recipe):
    if recipe.get('schema') != 1 or recipe.get('architecture') != 'amd64':
        raise ValueError('only schema 1 and validated amd64 recipes are supported')
    if not re.fullmatch(r'1\.(34|35|36|37)\.\d+', recipe['kubernetes_version']):
        raise ValueError('supported Kubernetes minor must match the pinned AutoScaler minor')
    if not re.fullmatch(r'[a-z0-9-]+', recipe['profile']):
        raise ValueError('invalid profile')
    required = {'k8s/kubeadm', 'k8s/kubelet', 'k8s/kubectl', 'network.yaml', 'headlamp.yaml', 'provider.yaml', 'autoscaler.yaml', 'cni/cni-plugins-amd64.tgz', 'cri-tools/crictl-linux-amd64.tar.gz', 'etcd/etcd-linux-amd64.tar.gz'}
    paths = [safe_path(f['path']) for f in recipe['files']]
    if len(paths) != len(set(paths)) or not required.issubset(paths):
        raise ValueError('duplicate paths or mandatory payload missing')
    for f in recipe['files']:
        url = urllib.parse.urlparse(f['url'])
        if url.scheme != 'https' or url.username or url.password or url.query or url.fragment:
            raise ValueError('source must be a public HTTPS URL without credentials')
        if any(x in url.path.split('/') for x in ('main', 'master', 'latest', 'latest_release')):
            raise ValueError('mutable source reference is forbidden')
        if not re.fullmatch('[a-f0-9]{64}', f['sha256']):
            raise ValueError('missing locked SHA256')
    refs = [x['reference'] for x in recipe['images']]
    for x in recipe['images']:
        if not re.fullmatch(r'[^\s]+@sha256:[a-f0-9]{64}', x['reference']):
            raise ValueError('image digest is required')
    if len(refs) != len(set(refs)):
        raise ValueError('duplicate image')
    for component in ('provider', 'autoscaler'):
        c = recipe['components'][component]
        if c['api_signature'] != 'HMAC-SHA256' or c['image'] not in refs or not re.fullmatch('[a-f0-9]{40}', c['source_sha']):
            raise ValueError('unapproved Mold component or missing provenance')
    minor='.'.join(recipe['kubernetes_version'].split('.')[:2])
    if recipe['components']['autoscaler'].get('kubernetes_minor','1.34') != minor:
        raise ValueError('Kubernetes/AutoScaler minor mismatch')
    sdk = recipe['components']['provider']['sdk']
    if not sdk['version'] or not re.fullmatch('[a-f0-9]{40}', sdk['source_sha']):
        raise ValueError('Provider SDK provenance is required')


def download(file, target, cache):
    digest = file['sha256']
    cached = cache / digest
    if not cached.exists():
        partial = cache / (digest + '.part-' + str(os.getpid()))
        try:
            run(['curl', '--fail', '--location', '--retry', '3', '--silent', '--show-error', '--proto', '=https', '--proto-redir', '=https', file['url'], '--output', partial])
            if sha256(partial) != digest:
                raise ValueError('download checksum mismatch: ' + file['path'])
            partial.replace(cached)
        finally:
            partial.unlink(missing_ok=True)
    if sha256(cached) != digest:
        raise ValueError('cached source checksum mismatch: ' + file['path'])
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(cached, target)


def rewrite_images(node, replacements):
    if isinstance(node, dict):
        for key, value in list(node.items()):
            if key == 'image' and isinstance(value, str):
                if value not in replacements:
                    raise ValueError('unlocked manifest image: ' + value)
                node[key] = replacements[value]
                node['imagePullPolicy'] = 'IfNotPresent'
            else:
                rewrite_images(value, replacements)
    elif isinstance(node, list):
        for value in node:
            rewrite_images(value, replacements)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recipe', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--cache', type=Path, default=Path(os.environ.get('XDG_CACHE_HOME', str(Path.home() / '.cache'))) / 'mold-kubernetes-iso')
    parser.add_argument('--development', action='store_true', help='allow dirty source; never eligible for Release')
    args = parser.parse_args()
    recipe_bytes = args.recipe.read_bytes()
    recipe = json.loads(recipe_bytes)
    check_recipe(recipe)
    if not re.fullmatch(r'r[1-9][0-9]*', args.revision):
        raise ValueError('revision must be r1, r2, ...')
    source = run(['git', 'rev-parse', 'HEAD'], cwd=HERE)
    dirty = bool(run(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=HERE))
    if dirty and not args.development:
        raise ValueError('Release build requires committed source; use --development for local preflight')
    for command in ('curl', 'skopeo', 'xorriso', 'cosign', 'go', 'containerd', 'ctr'):
        if not shutil.which(command):
            raise ValueError('missing build tool: ' + command)
    args.cache.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    version = recipe['kubernetes_version']
    name = f"kubernetes-v{version}-{recipe['profile']}-amd64-{args.revision}-{source[:8]}" + ('-local' if args.development else '')
    iso = args.output / (name + '.iso')
    if iso.exists():
        raise ValueError('immutable output already exists: ' + str(iso))
    with tempfile.TemporaryDirectory(prefix='mold-iso-') as temporary:
        payload = Path(temporary) / 'payload'
        payload.mkdir()
        replacements = {x['original']: x['reference'] for x in recipe['images']}
        for file in recipe['files']:
            target = payload / safe_path(file['path'])
            download(file, target, args.cache)
            if target.suffix == '.yaml':
                docs = list(yaml.safe_load_all(target.read_text()))
                rewrite_images(docs, replacements)
                target.write_text(yaml.safe_dump_all(docs, sort_keys=False))
            if file['path'] in ('k8s/kubeadm', 'k8s/kubelet', 'k8s/kubectl'):
                target.chmod(0o755)
        for binary_name in ('kubeadm', 'kubelet', 'kubectl'):
            run(['cosign', 'verify-blob', '--certificate', payload / ('k8s/' + binary_name + '.cert'), '--signature', payload / ('k8s/' + binary_name + '.sig'), '--certificate-identity', 'krel-staging@k8s-releng-prod.iam.gserviceaccount.com', '--certificate-oidc-issuer', 'https://accounts.google.com', payload / ('k8s/' + binary_name)])
        core = run([payload / 'k8s/kubeadm', 'config', 'images', 'list', '--kubernetes-version=v' + version]).splitlines()
        if set(core) != set(recipe['core_images']):
            raise ValueError('locked core images differ from exact kubeadm version')
        image_dir = payload / 'docker'
        image_dir.mkdir()
        images = []
        for image in recipe['images']:
            reference = image['reference']
            digest = reference.split('@sha256:')[1]
            print('Including ' + reference, flush=True)
            raw = subprocess.check_output(['skopeo', 'inspect', '--retry-times', '3', '--raw', 'docker://' + reference])
            if hashlib.sha256(raw).hexdigest() != digest:
                raise ValueError('registry manifest digest mismatch')
            info = json.loads(run(['skopeo', 'inspect', '--no-tags', '--retry-times', '3', 'docker://' + reference]))
            if info['Architecture'] != 'amd64' or info['Os'] != 'linux':
                raise ValueError('wrong image architecture: ' + reference)
            component = next((c for c in recipe['components'].values() if c.get('image') == reference), None)
            if component and (info.get('Labels', {}).get('org.opencontainers.image.revision') != component['source_sha'] or info.get('Labels', {}).get('io.ablestack.api-signature') != 'HMAC-SHA256'):
                raise ValueError('component image/source/signature provenance mismatch')
            archive = image_dir / (digest + '.tar')
            cached = args.cache / (digest + '.oci.tar')
            if not cached.exists():
                partial = args.cache / (digest + '.oci.part-' + str(os.getpid()))
                try:
                    run(['skopeo', 'copy', '--preserve-digests', 'docker://' + reference, 'oci-archive:' + str(partial) + ':' + image.get('import_alias', image['original'])])
                    partial.replace(cached)
                finally:
                    partial.unlink(missing_ok=True)
            shutil.copyfile(cached, archive)
            images.append(dict(image, path=str(archive.relative_to(payload)), sha256=sha256(archive)))
        (payload / 'docker/images.list').write_text(''.join(Path(x['path']).name+' '+x['reference'].split('@')[0]+'\n' for x in images))
        (payload / 'kubelet.service').write_text('[Unit]\nDescription=kubelet: Kubernetes Node Agent\nDocumentation=https://kubernetes.io/docs/\nWants=network-online.target\nAfter=network-online.target\n\n[Service]\nExecStart=/opt/bin/kubelet\nRestart=always\nStartLimitInterval=0\nRestartSec=10\n\n[Install]\nWantedBy=multi-user.target\n')
        (payload / '10-kubeadm.conf').write_text('[Service]\nEnvironment="KUBELET_KUBECONFIG_ARGS=--bootstrap-kubeconfig=/etc/kubernetes/bootstrap-kubelet.conf --kubeconfig=/etc/kubernetes/kubelet.conf"\nEnvironment="KUBELET_CONFIG_ARGS=--config=/var/lib/kubelet/config.yaml"\nEnvironmentFile=-/var/lib/kubelet/kubeadm-flags.env\nEnvironmentFile=-/etc/default/kubelet\nExecStart=\nExecStart=/opt/bin/kubelet $KUBELET_KUBECONFIG_ARGS $KUBELET_CONFIG_ARGS $KUBELET_KUBEADM_ARGS $KUBELET_EXTRA_ARGS\n')
        files = {str(p.relative_to(payload)): sha256(p) for p in sorted(payload.rglob('*')) if p.is_file()}
        manifest = dict(schema=1, kubernetes_version=version, architecture='amd64', profile=recipe['profile'], revision=args.revision, source_sha=source, development=args.development, recipe_sha256=hashlib.sha256(recipe_bytes).hexdigest(), components=recipe['components'], features=recipe['features'], images=images, files=files)
        (payload / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        bom = {'bomFormat':'CycloneDX', 'specVersion':'1.6', 'version':1, 'components':[]}
        for file in recipe['files']:
            bom['components'].append({'type':'file', 'name':file['path'], 'version':file.get('version', version), 'hashes':[{'alg':'SHA-256','content':files[file['path']]}], 'externalReferences':[{'type':'distribution','url':file['url']}]})
        for image in images:
            bom['components'].append({'type':'container', 'name':image['original'], 'version':image['reference'].split('@')[1], 'hashes':[{'alg':'SHA-256','content':image['reference'].split(':')[-1]}]})
        (payload / 'sbom.cdx.json').write_text(json.dumps(bom, indent=2) + '\n')
        checksums = ''.join(sha256(p) + '  ' + str(p.relative_to(payload)) + '\n' for p in sorted(payload.rglob('*')) if p.is_file())
        (payload / 'SHA256SUMS').write_text(checksums)
        epoch = run(['git', 'show', '-s', '--format=%ct', source], cwd=HERE)
        for p in payload.rglob('*'):
            os.utime(p, (int(epoch), int(epoch)))
        os.utime(payload, (int(epoch), int(epoch)))
        stamp = datetime.datetime.fromtimestamp(int(epoch), datetime.timezone.utc).strftime('%Y%m%d%H%M%S') + '00'
        run(['xorriso', '-as', 'mkisofs', '--modification-date=' + stamp, '--set_all_file_dates', stamp, '-quiet', '-V', 'CDROM', '-uid', '0', '-gid', '0', '-J', '-R', '-iso-level', '3', '-o', iso, payload], env=dict(os.environ, TZ='UTC'))
        if iso.stat().st_size >= MAX_ISO_BYTES:
            iso.unlink()
            raise ValueError('ISO exceeds GitHub Release 2 GiB asset limit')
        shutil.copyfile(payload / 'manifest.json', args.output / (name + '.manifest.json'))
        shutil.copyfile(payload / 'sbom.cdx.json', args.output / (name + '.sbom.cdx.json'))
    # A separate reader extracts the completed ISO and does not use the staging directory.
    run(['python3', HERE / 'validate.py', '--iso', iso, '--recipe', args.recipe, '--report', args.output / (name + '.validation.json')])
    digest = sha256(iso)
    (args.output / (name + '.sha256')).write_text(digest + '  ' + iso.name + '\n')
    (args.output / (name + '.registration.json')).write_text(json.dumps({'name':name, 'kubernetesversion':version, 'arch':'x86_64', 'checksum':'{SHA-256}' + digest, 'filename':iso.name, 'mincpunumber':2, 'minmemory':2048, 'minimum_resources_basis':'Mold supported-version API minimum; cluster node offerings require separate sizing', 'download_url':None, 'source_sha':source, 'release_eligible':not args.development}, indent=2)+'\n')
    print(json.dumps({'iso':str(iso), 'bytes':iso.stat().st_size, 'sha256':digest, 'source_sha':source}))


if __name__ == '__main__':
    main()
