import json,sys,os,re,zipfile,hashlib
from pathlib import Path
from remote import root,remote,sql
c,phase=sys.argv[1:3];reg=json.loads((root/(c+'-fixtures.json')).read_text())
def quoted(items):
 assert all(re.fullmatch('[a-f0-9-]{36}',x)for x in items)
 return ','.join("'"+x+"'"for x in items)
sid=quoted([s['id']for s in reg['snapshots']]);vid=quoted([v['id']for v in reg['volumes']]);aid=quoted([a['id']for a in reg['accounts']]);did=quoted([reg['domain']['id']])
if phase=='capture':
 refs=sql(c,"SELECT JSON_OBJECT('snapshot',s.uuid,'role',r.store_role,'path',r.install_path,'state',r.state) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.uuid IN ("+sid+") AND r.install_path IS NOT NULL;")
 vols=sql(c,"SELECT JSON_OBJECT('uuid',uuid,'path',path) FROM volumes WHERE uuid IN ("+vid+");")
 sch=next(s for s in reg['snapshots']if s['scenario']=='scheduler')
 jobs=sql(c,"SELECT JSON_OBJECT('id',j.id,'uuid',j.uuid,'user',j.user_id,'account',j.account_id,'status',j.job_status,'snapshot',s.uuid,'policy',JSON_UNQUOTE(JSON_EXTRACT(j.job_cmd_info,'$.policyid')),'volume',JSON_UNQUOTE(JSON_EXTRACT(j.job_cmd_info,'$.volumeid')),'snapshotParam',JSON_UNQUOTE(JSON_EXTRACT(j.job_cmd_info,'$.id'))) FROM async_job j JOIN snapshots s ON s.id=j.instance_id WHERE s.uuid='"+sch['id']+"' AND j.instance_type='Snapshot' AND j.job_cmd='org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotCmd';")
 assert len(jobs)==1 and jobs[0]['user']==1 and jobs[0]['status']==1 and jobs[0]['policy']=='8',jobs
 (root/(c+'-cleanup-paths.json')).write_text(json.dumps({'references':refs,'volumes':vols},indent=2));(root/(c+'-scheduler-job.json')).write_text(json.dumps(jobs,indent=2));print(c,'cleanup paths and real scheduler system job captured')
elif phase=='verify':
 q={
 'snapshots':"SELECT COUNT(*) FROM snapshots WHERE uuid IN ("+sid+") AND status<>'Destroyed' AND removed IS NULL",
 'volumes':"SELECT COUNT(*) FROM volumes WHERE uuid IN ("+vid+") AND removed IS NULL",
 'references':"SELECT COUNT(*) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.uuid IN ("+sid+") AND r.state<>'Destroyed'",
 'accounts':"SELECT COUNT(*) FROM account WHERE uuid IN ("+aid+") AND removed IS NULL",
 'domains':"SELECT COUNT(*) FROM domain WHERE uuid IN ("+did+") AND removed IS NULL",
 'policies':"SELECT COUNT(*) FROM snapshot_policy p JOIN volumes v ON v.id=p.volume_id WHERE v.uuid IN ("+vid+")",
 'schedules':"SELECT COUNT(*) FROM snapshot_schedule sc JOIN volumes v ON v.id=sc.volume_id WHERE v.uuid IN ("+vid+")",
 'forcedCleanup':"SELECT COUNT(*) FROM snapshot_details d JOIN snapshots s ON s.id=d.snapshot_id WHERE s.uuid IN ("+sid+") AND d.name LIKE 'force.delete.%'"}
 out={'database':{k:sql(c,"SELECT JSON_OBJECT('count',("+v+"));")[0]['count']for k,v in q.items()}}
 assert not any(out['database'].values()),out
 paths=json.loads((root/(c+'-cleanup-paths.json')).read_text());secondary=list(dict.fromkeys('/nfs/secondary/'+x['path'] for x in paths['references']if x['role']=='Image'));assert secondary and all('..'not in x.split('/')for x in secondary)
 left=json.loads(remote(c,['sudo','-n','python3','-c',"import pathlib,json;p="+repr(secondary)+";print(json.dumps([x for x in p if pathlib.Path(x).exists()]))"]));assert not left,left
 names=[x['path']for x in paths['volumes']]
 if c=='31':
  primary=['/mnt/glue-gfs/'+x for x in names];left=json.loads(remote(c,['python3','-c',"import pathlib,json;p="+repr(primary)+";print(json.dumps([x for x in p if pathlib.Path(x).exists()]))"],1));assert not left;trash=[]
 else:
  live=json.loads(remote(c,['rbd','ls','--format','json','rbd'],1));trash=json.loads(remote(c,['rbd','trash','ls','--format','json','rbd'],1));left=[x for x in names if x in live];trash=[x for x in trash if x.get('name')in names];assert not left and not trash
 manifest=json.loads((root/('server-'+c)/'manifest.json').read_text())
 code="""import zipfile,pathlib,hashlib,json,subprocess,urllib.request
m=MANIFEST
j=list(pathlib.Path('/usr/share/cloudstack-management/lib').glob('cloudstack-*.jar'));assert len(j)==1
with zipfile.ZipFile(j[0]) as z:
 for x in m:assert hashlib.sha256(z.read(x['path'])).hexdigest()==x['sha256']
assert pathlib.Path('/usr/share/cloudstack-management/webapp/WEB-INF').is_dir()
assert subprocess.check_output(['systemctl','is-active','mold'],text=True).strip()=='active'
assert urllib.request.urlopen('http://127.0.0.1:8080/client/').status==200
print(json.dumps({'verifiedClassCount':len(m),'webInf':True,'mold':'active','clientHttp':200}))
""".replace('MANIFEST',repr(manifest))
 out['runtime']=json.loads(remote(c,['sudo','-n','python3','-c',code]));out['physical']={'secondaryChecked':len(secondary),'primaryVolumesChecked':len(names),'remaining':left,'rbdTrash':trash};out['normalApiCleanup']=True
 (root/(c+'-final-state.json')).write_text(json.dumps(out,indent=2));print(c,'final DB/physical residual 0, runtime5classes/WEB-INF/Mold/HTTP200 PASS')
else:raise ValueError(phase)
