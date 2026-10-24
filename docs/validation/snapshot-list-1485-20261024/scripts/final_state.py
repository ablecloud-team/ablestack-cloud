import json,sys,re
from remote import root,sql,remote
c=sys.argv[1];r=json.loads((root/(c+'-fixtures.json')).read_text());paths=json.loads((root/(c+'-cleanup-paths.json')).read_text())
def quoted(xs):
 assert xs and all(re.fullmatch('[a-f0-9-]{36}',x)for x in xs);return ','.join("'"+x+"'"for x in xs)
sid=quoted([s['id']for s in r['snapshots']]);vid=quoted([v['id']for v in r['volumes']]);aid=quoted([v['id']for v in r['accounts']]);did=quoted([r['domain']['id']]);pid=quoted([r['project']['id']])
queries={
 'snapshots':"SELECT COUNT(*) FROM snapshots WHERE uuid IN ("+sid+") AND status<>'Destroyed' AND removed IS NULL",
 'volumes':"SELECT COUNT(*) FROM volumes WHERE uuid IN ("+vid+") AND removed IS NULL",
 'tags':"SELECT COUNT(*) FROM resource_tags WHERE resource_uuid IN ("+sid+")",
 'references':"SELECT COUNT(*) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.uuid IN ("+sid+") AND r.state<>'Destroyed'",
 'accounts':"SELECT COUNT(*) FROM account WHERE uuid IN ("+aid+") AND removed IS NULL",
 'domainAccounts':"SELECT COUNT(*) FROM account a JOIN domain d ON d.id=a.domain_id WHERE d.uuid IN ("+did+") AND a.removed IS NULL",
 'domains':"SELECT COUNT(*) FROM domain WHERE uuid IN ("+did+") AND removed IS NULL",
 'projects':"SELECT COUNT(*) FROM projects WHERE uuid IN ("+pid+") AND removed IS NULL",
 'policies':"SELECT COUNT(*) FROM snapshot_policy p JOIN volumes v ON v.id=p.volume_id WHERE v.uuid IN ("+vid+")",
 'schedules':"SELECT COUNT(*) FROM snapshot_schedule s JOIN volumes v ON v.id=s.volume_id WHERE v.uuid IN ("+vid+")",
 'forcedCleanup':"SELECT COUNT(*) FROM snapshot_details d JOIN snapshots s ON s.id=d.snapshot_id WHERE s.uuid IN ("+sid+") AND d.name LIKE 'force.delete.%'"}
pairs=','.join("'"+k+"',("+v+")"for k,v in queries.items());out={'database':sql(c,'SELECT JSON_OBJECT('+pairs+');')[0]};assert not any(out['database'].values()),out
secondary=list(dict.fromkeys('/nfs/secondary/'+x['path']for x in paths['references']if x['role']=='Image'and x['path']));assert len(secondary)==27 and all('..'not in x.split('/')for x in secondary)
left=json.loads(remote(c,['sudo','-n','python3','-c',"import pathlib,json;p="+repr(secondary)+";print(json.dumps([x for x in p if pathlib.Path(x).exists()]))"]));assert not left,left
names=[x['path']for x in paths['volumes']];assert len(names)==3
if c=='31':
 primary=['/mnt/glue-gfs/'+x for x in names];left=json.loads(remote(c,['python3','-c',"import pathlib,json;p="+repr(primary)+";print(json.dumps([x for x in p if pathlib.Path(x).exists()]))"],1));assert not left;trash=[]
else:
 live=json.loads(remote(c,['rbd','ls','--format','json','rbd'],1));trash=json.loads(remote(c,['rbd','trash','ls','--format','json','rbd'],1));left=[x for x in names if x in live];trash=[x for x in trash if x.get('name')in names];assert not left and not trash
code="""import pathlib,json,subprocess,urllib.request
assert pathlib.Path('/usr/share/cloudstack-management/webapp/WEB-INF').is_dir()
assert subprocess.check_output(['systemctl','is-active','mold'],text=True).strip()=='active'
assert urllib.request.urlopen('http://127.0.0.1:8080/client/').status==200
print(json.dumps({'webInf':True,'mold':'active','clientHttp':200}))
"""
out['runtime']=json.loads(remote(c,['sudo','-n','python3','-c',code]));out['physical']={'secondaryFilesChecked':27,'primaryVolumesChecked':3,'remaining':left,'rbdTrash':trash};out['normalApiCleanup']=True
(root/(c+'-final-state.json')).write_text(json.dumps(out,indent=2));print(c,'DB and physical residual zero, runtime PASS')
