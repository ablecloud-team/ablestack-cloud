# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


# Inject only disposable metadata to exercise a pre-existing conflict without an external backup appliance.
import json,os,subprocess,shlex,sys
from pathlib import Path
cluster,mode=sys.argv[1:3]
assert cluster in ('31','32') and mode in ('vm','backup','clear')
root=Path('/home/ablecloud/work/snapshot-actions-1479-20261023')
reg=json.loads((root/(cluster+'-fixtures.json')).read_text())
vm=reg['vm']['id'];prefix=reg['prefix']
assert vm and prefix=='SNAP1479-'+cluster
name=prefix+'-metadata-conflict'
q="SET @v=(SELECT id FROM vm_instance WHERE uuid='"+vm+"' AND display_name='"+prefix+"-vm' AND state='Stopped' AND removed IS NULL);"
if mode=='vm':
 q+="INSERT INTO vm_snapshots(uuid,name,display_name,vm_id,account_id,domain_id,service_offering_id,vm_snapshot_type,state,current,created) SELECT UUID(),'"+name+"','"+name+"',id,account_id,domain_id,service_offering_id,'Disk','Ready',0,NOW() FROM vm_instance WHERE id=@v;"
if mode=='backup':
 q+="INSERT INTO backup_offering(uuid,name,description,zone_id,provider,created) SELECT UUID(),'"+name+"','Disposable metadata conflict',data_center_id,'NetBackup',NOW() FROM vm_instance WHERE id=@v; SET @o=LAST_INSERT_ID(); INSERT INTO backups(uuid,vm_id,type,status,backup_offering_id,account_id,domain_id,zone_id,date,name) SELECT UUID(),id,'Full','Restoring',@o,account_id,domain_id,data_center_id,NOW(),'"+name+"' FROM vm_instance WHERE id=@v;"
if mode=='clear':
 q+="DELETE FROM vm_snapshots WHERE vm_id=@v AND name='"+name+"'; DELETE FROM backups WHERE vm_id=@v AND name='"+name+"'; DELETE FROM backup_offering WHERE @v IS NOT NULL AND name='"+name+"';"
q+="SELECT JSON_OBJECT('target',@v,'vmSnapshotConflicts',(SELECT COUNT(*) FROM vm_snapshots WHERE vm_id=@v AND name='"+name+"'),'backupConflicts',(SELECT COUNT(*) FROM backups WHERE vm_id=@v AND name='"+name+"'));"
args=['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'mysql','-uroot','-N','-B','--raw','cloud','-e',q]
text=subprocess.check_output(['sshpass','-e','ssh','-n','-p','22','-o','StrictHostKeyChecking=yes','ablecloud@10.10.'+cluster+'.10',shlex.join(args)],env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},text=True).strip()
result=json.loads(text);assert result['target'],'No stopped fixture matched'
if mode=='clear':assert not result['vmSnapshotConflicts'] and not result['backupConflicts']
else:assert result['vmSnapshotConflicts' if mode=='vm' else 'backupConflicts']==1
(root/(cluster+'-conflict-'+mode+'.json')).write_text(json.dumps(result,indent=2)+'\n')
print(cluster,mode,result)
