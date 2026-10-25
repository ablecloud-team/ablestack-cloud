import json, hashlib, shutil
from pathlib import Path
root=Path('/home/ablecloud/work/snapshot-detail-1486-20261025')
repo=Path('/home/ablecloud/work/dhslove/cloud-epic-1473-snapshots')
dest=repo/'docs/validation/snapshot-detail-1486-20261025'
for n in ['scripts','browser','logs','evidence']:(dest/n).mkdir(exist_ok=True)
paths=['ui/src/views/AutogenView.vue','ui/src/components/view/InfoCard.vue','ui/src/components/view/ResourceView.vue','ui/src/config/section/storage.js','ui/public/locales/en.json','ui/public/locales/ko_KR.json','ui/tests/unit/components/view/ResourceView.spec.js','ui/src/utils/storageSnapshotDetail.js','ui/src/views/storage/StorageSnapshotSummary.vue','ui/src/views/storage/StorageSnapshotDetails.vue','ui/src/style/components/view/resource-detail-fields.css','ui/tests/unit/utils/storageSnapshotDetail.spec.js','ui/tests/unit/views/AutogenStorageSnapshotDetail.spec.js','ui/tests/unit/components/view/StorageSnapshotSummary.spec.js']
manifest=[{'path':p,'sha256':hashlib.sha256((repo/p).read_bytes()).hexdigest()}for p in paths]
(dest/'source-manifest.json').write_text(json.dumps(manifest,indent=2))
for f in (root/'scripts').glob('*'):
 if f.is_file() and f.suffix in ['.py','.mjs'] and f.name not in ['curate.py']:
  shutil.copy2(f,dest/'scripts'/f.name)
shutil.copy2(root/'scripts/curate.py',dest/'scripts/curate.py')
for f in (root/'browser').iterdir():
 if f.is_file() and not f.name.startswith('dev-'):shutil.copy2(f,dest/'browser'/f.name)
for n in ['ui-tests-final.log','ui-lint.log','ui-build.log']:shutil.copy2(root/n,dest/'logs'/n)
for c in ['31','32']:
 for suffix in ['detail-api-oracle.json','readonly-api.json','db-before.json','physical-before.json','tag-created-db.json','ui-db-oracle.json','ui-deploy.json','cleanup.json','final-state.json','preservation.json']:
  shutil.copy2(root/(c+'-'+suffix),dest/'evidence'/(c+'-'+suffix))
 r=json.loads((root/(c+'-fixtures.json')).read_text())
 receipt={'prefix':r['prefix'],'domain':{'id':r['domain']['id'],'name':r['domain']['name']},'project':{'id':r['project']['id'],'name':r['project']['name']},'accounts':[{k:a[k]for k in ['id','name','scenario']}for a in r['accounts']],'snapshots':[{k:a[k]for k in ['id','name','scenario','volumeid']}for a in r['snapshots']],'volumes':[{k:a[k]for k in ['id','name','scenario']}for a in r['volumes']],'vms':[{k:a[k]for k in ['id','name','displayname']}for a in r['vms']],'role':{'id':r['readOnlyRole']['id'],'name':r['readOnlyRole']['name']},'chainConfigBefore':r['chainConfigBefore']}
 (dest/'evidence'/(c+'-fixture-identities.json')).write_text(json.dumps(receipt,indent=2))
 ids={s['id']for s in r['snapshots']};trace=[json.loads(x)for x in(root/(c+'-api.jsonl')).read_text().splitlines()];trace=[x for x in trace if x['command']=='listSnapshots'and x['args'].get('id')in ids]
 (dest/'evidence'/(c+'-snapshot-api-trace.jsonl')).write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n'for x in trace))
shutil.copy2(root/'protection-verified.json',dest/'evidence/protection-verified.json')
shutil.copy2(root/'ui-stage/ui-manifest.json',dest/'ui-asset-manifest.json')
summary={'issue':1486,'base':'5ed664e6c7e4d0cdcacc18794dddae3c405a16ff','branch':'dhslove/epic-1473-storage-snapshots','tests':162,'suites':10,'sourceFiles':14,'lint':'PASS','uiBuild':'PASS','artifactSha256':hashlib.sha256((root/'ui.tgz').read_bytes()).hexdigest(),'clusters':{}}
for c in ['31','32']:
 ui=json.loads((root/(c+'-ui-db-oracle.json')).read_text());summary['clusters'][c]={'apiDbAclCases':44,'readOnlyApiCases':5,'uiDbCases':len(ui['cases']),'actualTagJobs':2,'backupImages':7,'trackedVolumes':6,'screenshots':6,'activeResidual':0}
(dest/'evidence-summary.json').write_text(json.dumps(summary,indent=2))
for f in dest.rglob('*.log'):f.write_text('\n'.join(x.rstrip()for x in f.read_text().splitlines())+'\n')
files=[{'path':str(f.relative_to(dest)),'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}for f in dest.rglob('*')if f.is_file()and f.name!='evidence-manifest.json']
(dest/'evidence-manifest.json').write_text(json.dumps(files,indent=2))
print('Curated',len(files)+1,'evidence files, 14 source paths')
