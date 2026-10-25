import json,sys,uuid,hashlib
from remote import root,sql
c=sys.argv[1];reg=json.loads((root/(c+'-fixtures.json')).read_text());prefix=reg['prefix'];inv=json.loads((root/(c+'-existing-before.json')).read_text());owner=next(x for x in reg['accounts']if x['scenario']=='owner');other=next(x for x in reg['accounts']if x['scenario']=='other');vol=next(x for x in reg['volumes']if x['scenario']=='owner');retained=next(x for x in reg['volumes']if x['scenario']=='retained');pool=inv['listStoragePools']['storagepool'][0];store=inv['listImageStores']['imagestore'][0]
cases=[]
def add(name,args,where='1=1',user='admin'):
 cases.append(dict(name=name,user=user,args={k:v for k,v in {'listall':True,'keyword':prefix,'projectid':'-1','sortkey':'created','sortorder':'desc',**args}.items()if v is not None},where=where,rows=[]))
for unique in (True,False):
 mode='logical'if unique else'copies'
 choices=[('all',{},'1=1'),('manual',{'snapshottype':'MANUAL'},'snapshot_type=0'),('recurring',{'snapshottype':'RECURRING'},'snapshot_type IN (3,4,5,6)'),('volume',{'volumeid':vol['id']},"volume_uuid='"+vol['id']+"'"),('retained-volume',{'volumeid':retained['id']},"volume_uuid='"+retained['id']+"'"),('empty',{'keyword':prefix+'-no-match'},'0=1'),('zone',{'zoneid':pool['zoneid']},"data_center_uuid='"+pool['zoneid']+"'"),('primary',{'locationtype':'primary'},'1=1'if unique else"store_role='Primary'"),('secondary',{'locationtype':'secondary'},'1=1'if unique else"store_role='Image'"),('pool',{'storageid':pool['id']},"store_role='Primary' AND store_uuid='"+pool['id']+"'"),('image',{'imagestoreid':store['id']},"store_role='Image' AND store_uuid='"+store['id']+"'"),('owner',{'account':owner['name'],'domainid':reg['domain']['id'],'projectid':None},"account_name='"+owner['name']+"'"),('domain',{'domainid':reg['domain']['id'],'projectid':None},"domain_uuid='"+reg['domain']['id']+"' AND project_id IS NULL"),('recursive',{'domainid':reg['domain']['id'],'isrecursive':True,'projectid':None},'project_id IS NULL'),('project',{'projectid':reg['project']['id']},"project_uuid='"+reg['project']['id']+"'"),('tags',{'tags[0].key':'qa-search','tags[0].value':prefix},"id IN (SELECT id FROM snapshot_view WHERE tag_key='qa-search' AND tag_value='"+prefix+"')"),('multi-tags',{'tags[0].key':'qa-kind','tags[0].value':'HOURLY','tags[1].key':'qa-scenario','tags[1].value':'other'},"id IN (SELECT id FROM snapshot_view WHERE (tag_key='qa-kind' AND tag_value='HOURLY') OR (tag_key='qa-scenario' AND tag_value='other'))")]
 for interval,typ in [('HOURLY',3),('DAILY',4),('WEEKLY',5),('MONTHLY',6)]:
  for extra in ({},{'snapshottype':'RECURRING'},{'volumeid':vol['id']},{'volumeid':vol['id'],'snapshottype':'RECURRING'}):choices.append(('period-'+interval+'-'+str(len(choices)),{'intervaltype':interval,**extra},'snapshot_type='+str(typ)))
 choices.append(('combined',{'volumeid':vol['id'],'snapshottype':'RECURRING','intervaltype':'DAILY','zoneid':pool['zoneid'],'account':owner['name'],'domainid':reg['domain']['id'],'projectid':None,'tags[0].key':'qa-search','tags[0].value':prefix},'snapshot_type=4'))
 for name,args,where in choices:add(mode+'-'+name,{'showunique':unique,**args},where)
 for user,where in [('owner',"account_name='"+owner['name']+"'"),('other',"account_name='"+other['name']+"'"),('domainadmin','project_id IS NULL')]:
  add(mode+'-acl-'+user,{'showunique':unique,'projectid':None},where,user)
  add(mode+'-acl-'+user+'-recurring',{'showunique':unique,'projectid':None,'snapshottype':'RECURRING'},'('+where+') AND snapshot_type IN (3,4,5,6)',user)
 add(mode+'-owner-project',{'showunique':unique,'projectid':reg['project']['id']},"project_uuid='"+reg['project']['id']+"'",'owner')
queries=[]
for x in cases:
 u=x['args']['showunique'];partition='id'if u else'snapshot_store_pair'
 queries.append("SELECT JSON_OBJECT('case','"+x['name']+"','id',uuid,'pair',snapshot_store_pair,'zone',data_center_uuid,'name',name,'type',snapshot_type,'account',account_name) FROM (SELECT v.*, ROW_NUMBER() OVER(PARTITION BY "+partition+" ORDER BY snapshot_store_pair,tag_id) rn FROM snapshot_view v WHERE name LIKE '"+prefix+"%' AND removed IS NULL AND status<>'Destroyed' AND ("+x['where']+")) ranked WHERE rn=1 ORDER BY created DESC,id DESC"+(''if u else',snapshot_store_pair ASC')+';')
byname={x['name']:x for x in cases}
for row in sql(c,'\n'.join(queries)):
 row['copyid']=str(uuid.UUID(bytes=hashlib.md5((row['id']+':'+row['pair']+':'+row['zone']).encode()).digest(),version=3));byname[row.pop('case')]['rows'].append(row)
for x in cases:del x['where']
(root/(c+'-oracle.json')).write_text(json.dumps({'cluster':c,'method':'Independent snapshot_view SQL filters and ROW_NUMBER dedup then created/id/copy ordering','cases':cases},indent=2));print(c,len(cases),'oracle cases')
