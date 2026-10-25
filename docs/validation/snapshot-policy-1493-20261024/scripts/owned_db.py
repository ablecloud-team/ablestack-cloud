import sys,json,os,hashlib
from pathlib import Path
from remote import remote,sql,root
c,phase=sys.argv[1:3];args=json.loads(sys.argv[3]) if len(sys.argv)>3 else {}
prefix='SNAPPOL1493-'+c
prelude="""import json,os,pymysql,hashlib,pathlib
db=pymysql.connect(user='root',password=os.environ['MYSQL_PWD'],database='cloud',unix_socket='/var/lib/mysql/mysql.sock',cursorclass=pymysql.cursors.DictCursor)
cur=db.cursor()
"""+'a='+repr(args)+'\nprefix='+repr(prefix)+'\n'
if phase=='rekey':
 code="""db.begin()
cur.execute('SELECT p.*,v.uuid volume_uuid,v.name volume_name FROM snapshot_policy p JOIN volumes v ON v.id=p.volume_id WHERE p.uuid=%s FOR UPDATE',(a['uuid'],))
p=cur.fetchone();assert p and p['volume_name'].startswith(prefix) and not p['display'] and p['account_id']!=2
cur.execute('SELECT id FROM snapshot_policy WHERE id=8 FOR UPDATE');assert not cur.fetchone()
for table,column in [('snapshot_schedule','policy_id'),('snapshot_policy_details','policy_id')]:
 cur.execute('SELECT COUNT(*) n FROM '+table+' WHERE '+column+'=%s',(p['id'],));assert cur.fetchone()['n']==0
cur.execute('SELECT COUNT(*) n FROM resource_tags WHERE resource_id=%s AND resource_type=%s',(p['id'],'SnapshotPolicy'));assert cur.fetchone()['n']==0
cur.execute('UPDATE snapshot_policy SET id=8 WHERE id=%s AND uuid=%s',(p['id'],p['uuid']));assert cur.rowcount==1
db.commit();p['previous_id']=p['id'];p['id']=8
print(json.dumps({k:p[k] for k in ['id','uuid','volume_id','volume_uuid','account_id','domain_id','interval','previous_id']}))
"""
elif phase=='control':
 code="""db.begin();cur.execute('SELECT id,name,snapshot_type FROM snapshots WHERE uuid=%s FOR UPDATE',(a['uuid'],));s=cur.fetchone();assert s and s['name'].startswith(prefix) and s['snapshot_type']==0
cur.execute('UPDATE snapshots SET snapshot_type=8,type_description=%s WHERE id=%s',('INTERNAL',s['id']))
if a.get('intent'):cur.execute('INSERT INTO snapshot_details(snapshot_id,name,value,display) VALUES(%s,%s,%s,0)',(s['id'],'snapshot.creation.intent',a['intent']))
db.commit();print(json.dumps({'uuid':a['uuid'],'syntheticControl':True,'intent':a.get('intent')}))
"""
elif phase=='inspect':
 code="""cur.execute('SELECT s.id,s.uuid,s.volume_id,s.account_id,s.domain_id,s.name,s.snapshot_type,s.type_description,s.status,s.removed,v.uuid volume_uuid FROM snapshots s JOIN volumes v ON v.id=s.volume_id WHERE v.name LIKE %s ORDER BY s.id',(prefix+'%',));snaps=cur.fetchall()
cur.execute('SELECT d.snapshot_id,d.name,d.value,d.display FROM snapshot_details d JOIN snapshots s ON s.id=d.snapshot_id JOIN volumes v ON v.id=s.volume_id WHERE v.name LIKE %s ORDER BY d.id',(prefix+'%',));details=cur.fetchall()
cur.execute('SELECT p.id,p.uuid,p.volume_id,p.interval,p.max_snaps,p.display FROM snapshot_policy p JOIN volumes v ON v.id=p.volume_id WHERE v.name LIKE %s',(prefix+'%',));policies=cur.fetchall()
cur.execute('SELECT sc.id,sc.policy_id,sc.volume_id,sc.snapshot_id,sc.async_job_id,sc.scheduled_timestamp FROM snapshot_schedule sc JOIN volumes v ON v.id=sc.volume_id WHERE v.name LIKE %s',(prefix+'%',));schedules=cur.fetchall()
print(json.dumps({'snapshots':snaps,'details':details,'policies':policies,'schedules':schedules},default=str))
"""
elif phase=='due':
 code="""db.begin();cur.execute('SELECT p.id,p.volume_id FROM snapshot_policy p JOIN volumes v ON v.id=p.volume_id WHERE p.uuid=%s AND v.name LIKE %s FOR UPDATE',(a['uuid'],prefix+'%'));p=cur.fetchone();assert p
cur.execute('UPDATE snapshot_schedule SET scheduled_timestamp=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 MINUTE) WHERE policy_id=%s AND volume_id=%s AND async_job_id IS NULL',(p['id'],p['volume_id']));assert cur.rowcount==1
db.commit();print(json.dumps({'policy':p['id'],'fixtureScheduleMadeDue':True}))
"""
else:raise ValueError(phase)
print(remote(c,['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'python3','-c',prelude+code]))
