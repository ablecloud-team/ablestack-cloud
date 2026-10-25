import json,sys,os,uuid,hashlib
from pathlib import Path
from remote import root,sql

c=sys.argv[1];reg=json.loads((root/(c+'-fixtures.json')).read_text());prefix=reg['prefix'];owner=next(x for x in reg['accounts']if x['scenario']=='owner');other=next(x for x in reg['accounts']if x['scenario']=='other');vol=next(x for x in reg['volumes']if x['scenario']=='owner');store=json.loads((root/(c+'-existing-before.json')).read_text())['listImageStores']['imagestore'][0];pool=json.loads((root/(c+'-existing-before.json')).read_text())['listStoragePools']['storagepool'][0]
fields={'created':'created','id':'id','name':'name','state':'status','volumename':'volume_name','intervaltype':'snapshot_type','virtualsize':'size','account':'account_name','domain':'domain_name','project':'project_name'}
cases=[]
def add(name,args,where='1=1',user='admin'):
 cases.append({'name':name,'user':user,'args':{'listall':True,'keyword':prefix,'projectid':'-1',**args},'where':where,'rows':[]})
for unique in [True,False]:
 for key in fields:
  for order in ['asc','desc']:add(('unique'if unique else'copies')+'-'+key+'-'+order,{'showunique':unique,'sortkey':key,'sortorder':order})
 add(('unique'if unique else'copies')+'-legacy-default',{'showunique':unique})
 for name,args,where in [
 ('name',{'name':prefix+'-same'},"name='"+prefix+"-same'"),
 ('keyword',{'keyword':prefix+'-W-'},"name LIKE '"+prefix+"-W-%'"),
 ('empty',{'keyword':prefix+'-no-match'},'0=1'),
 ('volume',{'volumeid':vol['id']},"volume_uuid='"+vol['id']+"'"),
 ('account',{'account':owner['name'],'domainid':reg['domain']['id']},"account_name='"+owner['name']+"'"),
 ('project',{'projectid':reg['project']['id']},"project_uuid='"+reg['project']['id']+"'"),
 ('default-project-scope',{'projectid':None},'project_id IS NULL'),
 ('domain',{'domainid':reg['domain']['id'],'projectid':None},"domain_uuid='"+reg['domain']['id']+"' AND project_id IS NULL"),
 ('domain-recursive',{'domainid':reg['domain']['id'],'isrecursive':True,'projectid':None},'project_id IS NULL'),
 ('zone',{'zoneid':pool['zoneid']},"data_center_uuid='"+pool['zoneid']+"'"),
 ('primary',{'locationtype':'primary'},'1=1'if unique else"store_role='Primary'"),
 ('secondary',{'locationtype':'secondary'},'1=1'if unique else"store_role='Image'"),
 ('pool',{'storageid':pool['id']},"store_role='Primary' AND store_uuid='"+pool['id']+"'"),
 ('image-store',{'imagestoreid':store['id']},"store_role='Image' AND store_uuid='"+store['id']+"'"),
 ('tags',{'tags[0].key':'qa-order','tags[0].value':prefix},"id IN (SELECT id FROM snapshot_view WHERE tag_key='qa-order' AND tag_value='"+prefix+"')"),
 ('combined',{'name':prefix+'-same','account':owner['name'],'domainid':reg['domain']['id'],'zoneid':pool['zoneid'],'tags[0].key':'qa-order','tags[0].value':prefix},"name='"+prefix+"-same' AND account_name='"+owner['name']+"' AND id IN (SELECT id FROM snapshot_view WHERE tag_key='qa-order' AND tag_value='"+prefix+"')")]:
  add(('unique'if unique else'copies')+'-'+name,{'showunique':unique,'sortkey':'name','sortorder':'asc',**args},where)
 for user,scope in [('owner',"account_name='"+owner['name']+"'"),('other',"account_name='"+other['name']+"'"),('domainadmin','project_id IS NULL')]:
  add(('unique'if unique else'copies')+'-acl-'+user,{'showunique':unique,'sortkey':'created','sortorder':'asc','projectid':None},scope,user)
 add(('unique'if unique else'copies')+'-owner-project',{'showunique':unique,'sortkey':'name','sortorder':'desc','projectid':reg['project']['id']},"project_uuid='"+reg['project']['id']+"'",'owner')
queries=[]
for case in cases:
 case['args']={k:v for k,v in case['args'].items()if v is not None};
 if 'account' in case['args']:case['args'].pop('projectid',None)
 u=case['args']['showunique'];k=fields[case['args'].get('sortkey','created')];order=case['args'].get('sortorder','desc').upper();partition='id'if u else'snapshot_store_pair'
 queries.append("SELECT JSON_OBJECT('case','"+case['name']+"','id',uuid,'pair',snapshot_store_pair,'zone',data_center_uuid,'name',name,'created',created,'account',account_name) FROM (SELECT v.*, ROW_NUMBER() OVER(PARTITION BY "+partition+" ORDER BY snapshot_store_pair,tag_id) rn FROM snapshot_view v WHERE name LIKE '"+prefix+"%' AND removed IS NULL AND status<>'Destroyed' AND ("+case['where']+")) ranked WHERE rn=1 ORDER BY "+k+' '+order+(',id '+order if k!='id'else'')+(''if u else',snapshot_store_pair ASC')+';')
rows=sql(c,'\n'.join(queries));byname={x['name']:x for x in cases}
for row in rows:
 identity=row['id']+':'+row['pair']+':'+row['zone'];row['copyid']=str(uuid.UUID(bytes=hashlib.md5(identity.encode()).digest(),version=3));byname[row.pop('case')]['rows'].append(row)
for x in cases:del x['where']
(root/(c+'-oracle.json')).write_text(json.dumps({'cluster':c,'method':'Independent MySQL ROW_NUMBER partitioned by snapshot or reference after filters, no production helper','cases':cases},indent=2));print(c,'oracle',len(cases),'cases',len(rows),'rows')
