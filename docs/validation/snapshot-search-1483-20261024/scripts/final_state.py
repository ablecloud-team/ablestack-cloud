import json,sys,os
from remote import root,remote,sql

c=sys.argv[1];reg=json.loads((root/(c+'-fixtures.json')).read_text());paths=json.loads((root/(c+'-cleanup-paths.json')).read_text())
def quoted(items):return ','.join("'"+x+"'"for x in items)
sid=quoted([x['id']for x in reg['snapshots']+reg.get('retiredSnapshots',[])]);vid=quoted([x['id']for x in reg['volumes']]);aid=quoted([x['id']for x in reg['accounts']]);did=quoted([reg['domain']['id'],reg['child']['id']])
queries={
 'snapshots':"SELECT COUNT(*) FROM snapshots WHERE uuid IN ("+sid+") AND removed IS NULL AND status<>'Destroyed'",
 'volumes':"SELECT COUNT(*) FROM volumes WHERE uuid IN ("+vid+") AND removed IS NULL",
 'references':"SELECT COUNT(*) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.uuid IN ("+sid+") AND r.state<>'Destroyed'",
 'accounts':"SELECT COUNT(*) FROM account WHERE uuid IN ("+aid+") AND removed IS NULL",
 'domains':"SELECT COUNT(*) FROM domain WHERE uuid IN ("+did+") AND removed IS NULL",
 'project':"SELECT COUNT(*) FROM projects WHERE uuid='"+reg['project']['id']+"' AND removed IS NULL",
 'activePolicies':"SELECT COUNT(*) FROM snapshot_policy p JOIN volumes v ON v.id=p.volume_id WHERE v.uuid IN ("+vid+")",
 'pendingForcedCleanup':"SELECT COUNT(*) FROM snapshot_details d JOIN snapshots s ON s.id=d.snapshot_id WHERE s.uuid IN ("+sid+") AND d.name LIKE 'force.delete.%'"}
out={'database':{k:sql(c,"SELECT JSON_OBJECT('count',("+q+"));")[0]['count']for k,q in queries.items()}};assert not any(out['database'].values()),out
secondary=['/nfs/secondary/'+x['path']for x in paths['references']if x['role']=='Image'];secondary=list(dict.fromkeys(secondary));assert len(secondary)>=35 and all('..'not in x.split('/')for x in secondary)
code="import pathlib,json;p="+repr(secondary)+";left=[x for x in p if pathlib.Path(x).exists()];print(json.dumps(left))";left=json.loads(remote(c,['sudo','-n','python3','-c',code]));assert not left,left
names=[x['path']for x in paths['volumes']];assert len(names)==28
if c=='31':
 images=['/mnt/glue-gfs/'+x for x in names];code="import pathlib,json;p="+repr(images)+";print(json.dumps([x for x in p if pathlib.Path(x).exists()]))";left=json.loads(remote(c,['python3','-c',code],1));assert not left,left;trash=[]
else:
 live=json.loads(remote(c,['rbd','ls','--format','json','rbd'],1));trash=json.loads(remote(c,['rbd','trash','ls','--format','json','rbd'],1));left=[x for x in names if x in live];trash=[x for x in trash if x.get('name')in names];assert not left and not trash,(left,trash)
out['physical']={'secondaryFilesChecked':len(secondary),'primaryVolumesChecked':len(names),'primarySnapshotReferences':sum(x['role']=='Primary'for x in paths['references']),'remaining':left,'ownedRbdTrash':trash};out['normalApiCleanup']=True;out['forcedRecoveryUsed']=False
(root/(c+'-final-state.json')).write_text(json.dumps(out,indent=2));print(c,'final API/DB/storage residuals zero')
