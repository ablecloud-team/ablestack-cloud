import sys,json,uuid,hashlib
from remote import root,sql
c=sys.argv[1];r=json.loads((root/(c+'-fixtures.json')).read_text());prefix=r['prefix'];owner=next(x for x in r['accounts']if x['scenario']=='owner');other=next(x for x in r['accounts']if x['scenario']=='other');v=next(x for x in r['volumes']if x['scenario']=='owner')
cases=[]
for role in ['admin','owner','other']:
 for unique in [True,False]:
  for name,args,where in [('all',{},'1=1'),('manual',{'snapshottype':'MANUAL'},'snapshot_type=0'),('recurring',{'snapshottype':'RECURRING'},'snapshot_type IN (3,4,5,6)')]+[(interval+'-'+label,{'intervaltype':interval,**extra},'snapshot_type='+str(i+3))for i,interval in enumerate(['HOURLY','DAILY','WEEKLY','MONTHLY'])for label,extra in [('only',{}),('recurring',{'snapshottype':'RECURRING'}),('volume',{'volumeid':v['id']})]]:
   if role!='admin':where+=' AND account_name='+"'"+(owner if role=='owner' else other)['name']+"'"
   cases.append({'name':role+'-'+str(unique)+'-'+name,'role':role,'args':{'showunique':unique,'keyword':prefix,'listall':True,'sortkey':'created','sortorder':'desc',**args},'where':where,'expected':[]})
queries=[]
for case in cases:
 partition='id'if case['args']['showunique']else'snapshot_store_pair'
 queries.append("SELECT JSON_OBJECT('case','"+case['name']+"','uuid',uuid,'pair',snapshot_store_pair,'zone',data_center_uuid,'type',snapshot_type) FROM (SELECT v.*,ROW_NUMBER() OVER(PARTITION BY "+partition+" ORDER BY snapshot_store_pair,tag_id) rn FROM snapshot_view v WHERE name LIKE '%"+prefix+"%' AND removed IS NULL AND status<>'Destroyed' AND snapshot_type NOT IN (2,7) AND ("+case['where']+")) x WHERE rn=1 ORDER BY created DESC,id DESC"+(''if case['args']['showunique']else',snapshot_store_pair ASC')+';')
byname={x['name']:x for x in cases}
for row in sql(c,'\n'.join(queries)):
 row['copyid']=str(uuid.UUID(bytes=hashlib.md5((row['uuid']+':'+row['pair']+':'+row['zone']).encode()).digest(),version=3));byname[row.pop('case')]['expected'].append(row)
for x in cases:del x['where']
(root/(c+'-oracle.json')).write_text(json.dumps({'method':'Independent snapshot_view SQL filter/ACL/ROW_NUMBER and created/id/copy order','cases':cases},indent=2));print(c,len(cases),'independent cases')
