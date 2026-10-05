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
"""Independent ISO extraction, binary and OCI-content validation."""
import argparse
import os
import time
import shutil
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import yaml


def hash_file(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for b in iter(lambda: stream.read(1024*1024), b''):
            h.update(b)
    return h.hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def elf_amd64(data, name):
    require(data[:4] == b'\x7fELF' and data[4:6] == b'\x02\x01' and data[18:20] == b'\x3e\x00', 'not linux amd64 ELF: ' + name)


def manifest_images(node):
    result = set()
    if isinstance(node, dict):
        for key, value in node.items():
            if key == 'image':
                require('@sha256:' in value and node.get('imagePullPolicy') == 'IfNotPresent', 'mutable image or pull policy')
                result.add(value)
            else:
                result.update(manifest_images(value))
    elif isinstance(node, list):
        for value in node:
            result.update(manifest_images(value))
    return result


def validate_payload(root, recipe, recipe_hash):
    manifest = json.loads((root/'manifest.json').read_text())
    require(manifest['recipe_sha256'] == recipe_hash, 'recipe provenance mismatch')
    require(manifest['kubernetes_version'] == recipe['kubernetes_version'] and manifest['architecture'] == 'amd64', 'version/architecture mismatch')
    require(manifest['components'] == recipe['components'], 'component provenance mismatch')
    actual_files = {str(p.relative_to(root)) for p in root.rglob('*') if p.is_file()}
    listed = {}
    for line in (root/'SHA256SUMS').read_text().splitlines():
        digest, name = line.split('  ',1)
        require(re.fullmatch('[a-f0-9]{64}', digest) is not None and '..' not in Path(name).parts and not Path(name).is_absolute(), 'invalid checksum entry')
        require(name not in listed, 'duplicate checksum entry')
        listed[name]=digest
    require(set(listed) == actual_files-{'SHA256SUMS'}, 'unlisted or missing payload file')
    for name,digest in listed.items():
        path=root/name
        require(not path.is_symlink() and hash_file(path)==digest, 'payload checksum mismatch: '+name)
    for name,digest in manifest['files'].items():
        require(listed.get(name)==digest, 'manifest checksum mismatch: '+name)
    for file in recipe['files']:
        if not file['path'].endswith('.yaml'):
            require(listed[file['path']] == file['sha256'], 'locked source checksum mismatch: '+file['path'])
    required = {x['path'] for x in recipe['files']} | {'kubelet.service','10-kubeadm.conf','manifest.json','sbom.cdx.json','SHA256SUMS','docker/images.list'}
    require(required.issubset(actual_files) and all((root/x).stat().st_size>0 for x in required),'mandatory consumer payload missing or empty')
    version='v'+recipe['kubernetes_version']
    versions={}
    for name,args in [('kubeadm',['version','-o','short']),('kubelet',['--version']),('kubectl',['version','--client=true','-o','json'])]:
        binary=root/'k8s'/name
        with binary.open('rb') as f: elf_amd64(f.read(64), name)
        binary.chmod(0o755)
        output=subprocess.check_output([str(binary)]+args,text=True,timeout=30).strip()
        if name=='kubectl': require(json.loads(output)['clientVersion']['gitVersion']==version, 'wrong kubectl version')
        else: require(output.split()[-1]==version,'wrong '+name+' version')
        versions[name]=version
    for name in ('kubeadm','kubelet','kubectl'):
        subprocess.run(['cosign','verify-blob','--certificate',str(root/'k8s'/ (name+'.cert')),'--signature',str(root/'k8s'/ (name+'.sig')),'--certificate-identity','krel-staging@k8s-releng-prod.iam.gserviceaccount.com','--certificate-oidc-issuer','https://accounts.google.com',str(root/'k8s'/name)],check=True,stdout=subprocess.DEVNULL)
    core=subprocess.check_output([str(root/'k8s/kubeadm'),'config','images','list','--kubernetes-version='+version],text=True,timeout=30).splitlines()
    require(set(core)==set(recipe['core_images']), 'core image set mismatch')
    for path in ['cni/cni-plugins-amd64.tgz','cri-tools/crictl-linux-amd64.tar.gz','etcd/etcd-linux-amd64.tar.gz']:
        with tarfile.open(root/path) as archive:
            found=0
            for member in archive.getmembers():
                if member.isfile():
                    stream=archive.extractfile(member); data=stream.read(64)
                    if data.startswith(b'\x7fELF'): elf_amd64(data,member.name); found+=1
            require(found>0,'archive has no binaries: '+path)
    refs=set()
    for name in ['network.yaml','headlamp.yaml','provider.yaml','autoscaler.yaml']:
        docs=list(yaml.safe_load_all((root/name).read_text()))
        require(all(isinstance(x,dict) and x.get('apiVersion') and x.get('kind') for x in docs if x is not None),'invalid Kubernetes YAML: '+name)
        refs.update(manifest_images(docs))
    expected={x['reference'] for x in recipe['images']}
    require(refs.issubset(expected), 'manifest image missing from lock')
    require({x['reference'] for x in manifest['images']}==expected, 'missing or extra image archive')
    mapping = (root/'docker/images.list').read_text().splitlines()
    expected_mapping = [Path(x['path']).name+' '+x['reference'].split('@')[0] for x in manifest['images']]
    require(len(mapping)==len(set(mapping)) and set(mapping)==set(expected_mapping), 'image import repository mapping mismatch')
    # OCI manifests/config/layers are checked from archive bytes, without registry access.
    archive_reports=[]
    for image in manifest['images']:
        with tarfile.open(root/image['path']) as archive:
            entries={m.name:m for m in archive.getmembers() if m.isfile()}
            require('index.json' in entries and 'oci-layout' in entries,'not an OCI archive')
            def blob(digest):
                require(re.fullmatch(r'sha256:[a-f0-9]{64}',digest) is not None,'invalid OCI digest')
                name='blobs/sha256/'+digest.split(':')[1]
                require(name in entries,'missing OCI blob '+name)
                data=archive.extractfile(entries[name]).read()
                require(hashlib.sha256(data).hexdigest()==digest.split(':')[1],'corrupt OCI blob')
                return data
            index=json.load(archive.extractfile(entries['index.json']))
            require(len(index['manifests'])==1,'ambiguous OCI index')
            descriptor=index['manifests'][0]
            require(descriptor['digest']==image['reference'].split('@')[1], 'OCI image digest changed')
            require(descriptor.get('annotations',{}).get('org.opencontainers.image.ref.name')==image.get('import_alias',image['original']),'core image import alias missing')
            content=json.loads(blob(descriptor['digest']))
            config=json.loads(blob(content['config']['digest']))
            require(config['architecture']=='amd64' and config['os']=='linux','OCI architecture mismatch')
            diff_ids=config.get('rootfs',{}).get('diff_ids',[])
            require(len(diff_ids)==len(content['layers']),'OCI layer count mismatch')
            binary_data = None
            component = next((c for c in recipe['components'].values() if c.get('image') == image['reference']), None)
            for layer,diff_id in zip(content['layers'],diff_ids):
                raw=blob(layer['digest'])
                stream=gzip.GzipFile(fileobj=io.BytesIO(raw)) if raw[:2]==b'\x1f\x8b' else io.BytesIO(raw)
                h=hashlib.sha256()
                for part in iter(lambda:stream.read(1024*1024),b''):h.update(part)
                require('sha256:'+h.hexdigest()==diff_id,'OCI uncompressed layer mismatch')
                if component:
                    with tarfile.open(fileobj=io.BytesIO(raw), mode='r:*') as layer_tar:
                        names={m.name.lstrip('./'):m for m in layer_tar.getmembers() if m.isfile()}
                        name=component['binary']
                        if name in names: binary_data=layer_tar.extractfile(names[name]).read()
            if component:
                labels=config.get('config',{}).get('Labels',{})
                require(labels.get('org.opencontainers.image.revision')==component['source_sha'] and labels.get('io.ablestack.api-signature')=='HMAC-SHA256','component image source/signature label mismatch')
                require(binary_data is not None, 'Mold component binary missing')
                elf_amd64(binary_data[:64],component['binary'])
                if 'customization_files' in component:
                    require(config.get('config',{}).get('Labels',{}).get('io.ablestack.mold-client-sha256') == component['customization_files']['client.go'], 'AutoScaler customization provenance mismatch')
                with tempfile.TemporaryDirectory(prefix='mold-binary-') as binary_dir:
                    binary_path=Path(binary_dir)/component['binary'];binary_path.write_bytes(binary_data)
                    modules=subprocess.check_output(['go','version','-m',str(binary_path)],text=True)
                    require('vcs.revision='+component.get('binary_source_sha',component['source_sha']) in modules,'component binary source SHA mismatch')
                    if 'sdk' in component:
                        sdk=component['sdk']
                        require(sdk['module'] in modules and sdk['version'] in modules, 'Provider binary SDK provenance mismatch')
            archive_reports.append({'reference':image['reference'],'architecture':'amd64','layers':len(diff_ids)})
    return {'status':'PASS','scope':'ISO payload, source hashes, binary versions/ELF, Kubernetes YAML, OCI manifest/config/layer hashes; cluster lifecycle runtime is a separate gate','kubernetes_version':recipe['kubernetes_version'],'files':len(listed),'binaries':versions,'images':archive_reports,'binary_signatures':'Kubernetes official keyless signatures verified','component_binaries':'ELF and embedded Go source/SDK provenance verified','recipe_sha256':recipe_hash,'source_sha':manifest['source_sha']}


def verify_containerd_import(root, images):
    # Start an isolated daemon; do not touch the host's service, socket or namespace.
    with tempfile.TemporaryDirectory(prefix='mold-ctr-') as temporary:
        base=Path(temporary);socket=base/'containerd.sock'
        config=base/'config.toml'
        config.write_text('version = 2\nroot = "'+str(base/'root')+'"\nstate = "'+str(base/'state')+'"\ndisabled_plugins = ["io.containerd.grpc.v1.cri", "io.containerd.runtime.v1.linux", "io.containerd.runtime.v2.task"]\n[grpc]\naddress = "'+str(socket)+'"\nuid = '+str(os.getuid())+'\ngid = '+str(os.getgid())+'\n[ttrpc]\naddress = "'+str(base/'ttrpc.sock')+'"\nuid = '+str(os.getuid())+'\ngid = '+str(os.getgid())+'\n[plugins."io.containerd.internal.v1.opt"]\npath = "'+str(base/'opt')+'"\n')
        with (base/'daemon.log').open('w') as log:
            process=subprocess.Popen(['containerd','-c',str(config)],stdout=log,stderr=log)
            try:
                for _ in range(100):
                    if socket.exists(): break
                    require(process.poll() is None,'isolated containerd failed to start: '+(base/'daemon.log').read_text())
                    time.sleep(.1)
                require(socket.exists(),'isolated containerd startup timeout')
                command=['ctr','-a',str(socket),'-n','mold-iso-validation']
                for image in images:
                    subprocess.run(command+['images','import','--digests','--base-name',image['reference'].split('@')[0],'--no-unpack',str(root/image['path'])],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                imported=subprocess.check_output(command+['images','list','--quiet'],text=True).splitlines()
                for image in images:
                    require(image.get('import_alias',image['original']) in imported,'runtime import alias missing: '+image['original'])
                    require(image['reference'] in imported,'runtime import digest missing: '+image['reference'])
            finally:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill();process.wait()
    return {'status':'PASS','scope':'isolated containerd imports with exact kubeadm aliases and digest references; no workloads started'}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--iso',type=Path,required=True)
    parser.add_argument('--recipe',type=Path,required=True)
    parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args()
    recipe_bytes=args.recipe.read_bytes()
    with tempfile.TemporaryDirectory(prefix='mold-iso-verify-') as temporary:
        root=Path(temporary)/'payload'
        info=subprocess.check_output(['xorriso','-indev',str(args.iso),'-pvd_info'],text=True,stderr=subprocess.STDOUT)
        require("Volume Id    : CDROM" in info or "Volume id    : 'CDROM'" in info,'ISO volume label must be CDROM')
        subprocess.run(['xorriso','-osirrox','on','-indev',str(args.iso),'-extract','/',str(root)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        report=validate_payload(root,json.loads(recipe_bytes),hashlib.sha256(recipe_bytes).hexdigest())
        report['containerd_import']=verify_containerd_import(root,json.loads((root/'manifest.json').read_text())['images'])
    report.update(iso=args.iso.name,iso_sha256=hash_file(args.iso),bytes=args.iso.stat().st_size)
    args.report.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))


if __name__=='__main__':
    main()
