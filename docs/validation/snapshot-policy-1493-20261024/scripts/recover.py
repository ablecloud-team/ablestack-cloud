import sys,json,os
from remote import root,remote,sql
c=sys.argv[1];stage='/root/snapshot1493-stage';tool=stage+'/repair-snapshot-policy-types.py'
def run(args,expect_failure=False):
 try:
  out=json.loads(remote(c,['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'python3',tool]+args))
  assert not expect_failure
  return out
 except RuntimeError:
  if not expect_failure:raise
  return {'expectedFailure':True}
def state():
 return sql(c,"SELECT JSON_OBJECT('id',id,'uuid',uuid,'volume',volume_id,'type',snapshot_type,'description',type_description,'status',status,'removed',removed) FROM snapshots WHERE snapshot_type=8 OR id IN (SELECT snapshot_id FROM snapshot_details WHERE name='snapshot.type.recovery.1493') ORDER BY id; SELECT JSON_OBJECT('detailId',id,'snapshot',snapshot_id,'name',name,'value',value,'display',display) FROM snapshot_details WHERE name='snapshot.type.recovery.1493' ORDER BY id;")
before=state();no=run(['--output',stage+'/no-archive-plan.json']);assert sum(x['action']=='repair' for x in no['plan']['entries'])==1,no
assert state()==before
review=run(['--archive-policy-evidence',stage+'/archive-policy-evidence.json','--output',stage+'/reviewed-plan.json']);entries=review['plan']['entries'];assert sum(x['action']=='repair'for x in entries)==5,review
assert sorted(x['reason'] for x in entries if x['action']=='skip')==['explicit-non-policy-intent','insufficient-policy-and-successful-creation-job-evidence']
assert state()==before
target=next(x for x in entries if x['action']=='repair' and x['snapshot']['status']=='BackedUp')['snapshot']['uuid']
mutation="UPDATE snapshots SET type_description='REVIEW_DRIFT' WHERE uuid='"+target+"' AND name LIKE 'SNAPPOL1493-"+c+"%' AND snapshot_type=8 AND type_description='INTERNAL';"
sql(c,mutation)
drift=run(['--apply',stage+'/reviewed-plan.json','--sha256',review['plan_sha256']],True)
assert sum('detailId'in x for x in state())==0
sql(c,"UPDATE snapshots SET type_description='INTERNAL' WHERE uuid='"+target+"' AND type_description='REVIEW_DRIFT';")
assert state()==before
applied=run(['--apply',stage+'/reviewed-plan.json','--sha256',review['plan_sha256']]);assert len(applied['changed'])==5
again=run(['--apply',stage+'/reviewed-plan.json','--sha256',review['plan_sha256']]);assert not again['changed'] and len(again['already_applied'])==5
after=state();old={x['uuid']:x for x in before if'uuid'in x};new={x['uuid']:x for x in after if'uuid'in x}
for uuid,b in old.items():
 a=new[uuid];assert all(b[k]==a[k]for k in b if k not in ['type','description'])
 if uuid in applied['changed']:assert a['type']in[3,4,5,6]and a['description']in['HOURLY','DAILY','WEEKLY','MONTHLY']
 else:assert b==a
assert sum('detailId'in x for x in after)==5 and all(x['display']==0 for x in after if'detailId'in x)
out={'readOnlyPlanNoArchive':no,'reviewedWithArchive':review,'stalePlanRejected':drift,'applied':applied,'idempotent':again,'before':before,'after':after,'onlyTypeDescriptionAndHiddenAuditChanged':True}
(root/(c+'-history-recovery.json')).write_text(json.dumps(out,indent=2,default=str));print(c,'recovery PASS: 5 repaired (4 active +1 Destroyed history),2 controls skipped,stale rollback,idempotent')
