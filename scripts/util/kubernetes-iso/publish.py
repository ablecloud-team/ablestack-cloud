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
"""Publish validated assets once; never replace public Release assets."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def run(args):return subprocess.check_output(args,text=True).strip()


def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--directory',type=Path,required=True)
 p.add_argument('--repository',required=True)
 p.add_argument('--mode',choices=['origin-trial','official'],required=True)
 args=p.parse_args()
 if args.mode=='origin-trial' and args.repository!='dhslove/ablestack-cloud':raise ValueError('Origin trial repository mismatch')
 if args.mode=='official' and args.repository!='ablecloud-team/ablestack-cloud':raise ValueError('official repository mismatch')
 isos=list(args.directory.glob('*.iso'))
 if len(isos)!=1:raise ValueError('publish exactly one ISO per immutable Release')
 iso=isos[0];name=iso.stem
 report=json.loads((args.directory/(name+'.validation.json')).read_text())
 registration_path=args.directory/(name+'.registration.json')
 registration=json.loads(registration_path.read_text())
 if not registration['release_eligible'] or report['status']!='PASS' or report['containerd_import']['status']!='PASS':raise ValueError('validation gate failed')
 source=registration['source_sha']
 if source!=run(['git','rev-parse','HEAD']):raise ValueError('source HEAD mismatch')
 tag=name.replace('kubernetes-','k8s-',1)
 if args.mode=='official':
  manifest=json.loads((args.directory/(name+'.manifest.json')).read_text())
  if manifest['components']['autoscaler'].get('baseline_status') != 'stable':raise ValueError('official Release requires a stable minor-matched AutoScaler baseline')
  for component in manifest['components'].values():
   if not component['source_repository'].startswith('ablecloud-team/') or 'candidate_module' in component.get('sdk',{}):raise ValueError('official Release requires promoted Upstream component sources and SDK')
  if run(['git','describe','--exact-match','--tags',source])!=tag:raise ValueError('official immutable tag mismatch')
  subprocess.run(['git','fetch','upstream','ablestack-europa'],check=True)
  subprocess.run(['git','merge-base','--is-ancestor',source,'upstream/ablestack-europa'],check=True)
 if subprocess.run(['gh','release','view',tag,'--repo',args.repository],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:raise ValueError('immutable Release already exists')
 url='https://github.com/'+args.repository+'/releases/download/'+tag+'/'+iso.name
 registration['download_url']=url
 registration_path.write_text(json.dumps(registration,indent=2)+'\n')
 manifest=json.loads((args.directory/(name+'.manifest.json')).read_text())
 autoscaler=manifest['components']['autoscaler']
 notes=args.directory/(name+'.notes.ko.md')
 notes.write_text(f'''# Kubernetes {registration['kubernetesversion']} Mold ISO

- 대상: Europa / x86_64 / Mold SHA256 Provider 및 {'.'.join(registration['kubernetesversion'].split('.')[:2])}용 AutoScaler
- 배포 구분: {args.mode}
- source SHA: `{source}`
- AutoScaler baseline: `{autoscaler['original_baseline']}` / `{autoscaler['baseline_status']}` / `{autoscaler['binary_source_sha']}`
- ISO 독립 추출·버전·ELF·공식 바이너리 서명·매니페스트·OCI blob·격리 containerd import: PASS
- Mold URL 등록 주소: {url}
- Mold checksum: `{registration['checksum']}`
- CSI는 내부 SHA256 호환성 검증 전이므로 이 profile에 포함하지 않습니다.
- 클러스터 생성/확장/업그레이드 및 LB/VPC 런타임 검증은 별도 생명주기 검증 범위입니다.
''')
 files=sorted(x for x in args.directory.iterdir() if x.is_file() and x.name.startswith(name))
 sums=args.directory/'SHA256SUMS'
 def digest(path):
  h=hashlib.sha256()
  with path.open('rb') as f:
   for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
  return h.hexdigest()
 if digest(iso)!=report['iso_sha256'] or registration['checksum']!='{SHA-256}'+digest(iso):raise ValueError('ISO changed after validation')
 sums.write_text(''.join(digest(x)+'  '+x.name+'\n' for x in files))
 command=['gh','release','create',tag]+[str(x) for x in files]+[str(sums),'--repo',args.repository,'--target',source,'--latest=false','--title','Kubernetes '+registration['kubernetesversion']+' Mold ISO ('+args.mode+')','--notes-file',str(notes)]
 if args.mode=='origin-trial':command.append('--prerelease')
 subprocess.run(command,check=True)
 # Public GET is a separate download check; do not use authenticated gh download.
 downloaded=args.directory/(iso.name+'.public-download')
 try:
  subprocess.run(['curl','--fail','--location','--retry','3','--silent','--show-error',url,'-o',str(downloaded)],check=True)
  if digest(downloaded)!=digest(iso):raise ValueError('public Release download checksum mismatch')
 finally:downloaded.unlink(missing_ok=True)
 print(json.dumps({'release':'https://github.com/'+args.repository+'/releases/tag/'+tag,'iso_url':url,'public_download':'PASS'}))


if __name__=='__main__':main()
