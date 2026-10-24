# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with this
# work for additional information regarding copyright ownership. The ASF
# licenses this file to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations.
import json,os,shlex,subprocess,sys
from pathlib import Path
c=sys.argv[1];assert c in ('31','32');p='SNAP1479UI-'+c+'-%';account='snap1479ui-'+c+'-%'
reg=json.loads(Path('/home/ablecloud/work/snapshot-actions-1479-ui-20261023/'+c+'-fixtures.json').read_text());ids=','.join("'"+v['id']+"'" for v in reg['volumes'])
query=f"""SELECT JSON_OBJECT(
 'activeSnapshots',(SELECT COUNT(*) FROM snapshots WHERE name LIKE '{p}' AND removed IS NULL AND status<>'Destroyed'),
 'nonterminalReferences',(SELECT COUNT(*) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.name LIKE '{p}' AND r.state<>'Destroyed'),
 'activeVolumes',(SELECT COUNT(*) FROM volumes v LEFT JOIN vm_instance m ON m.id=v.instance_id WHERE (v.name LIKE '{p}' OR m.display_name LIKE '{p}' OR v.uuid IN ({ids})) AND v.removed IS NULL),
 'activeVMs',(SELECT COUNT(*) FROM vm_instance WHERE display_name LIKE '{p}' AND removed IS NULL),
 'activeTemplates',(SELECT COUNT(*) FROM vm_template WHERE name LIKE '{p}' AND removed IS NULL),
 'activeAccounts',(SELECT COUNT(*) FROM account WHERE account_name LIKE '{account}' AND removed IS NULL),
 'activeProjects',(SELECT COUNT(*) FROM projects WHERE name LIKE '{p}' AND removed IS NULL),
 'roles',(SELECT COUNT(*) FROM roles WHERE name LIKE '{p}'),
 'vmSnapshotFaultRows',(SELECT COUNT(*) FROM vm_snapshots WHERE name LIKE '{p}' AND removed IS NULL),
 'forbiddenVolumes',(SELECT COUNT(*) FROM volumes WHERE name LIKE '{p}' AND name LIKE '%must-not-exist%'),
 'forbiddenSnapshots',(SELECT COUNT(*) FROM snapshots WHERE name LIKE '{p}' AND name LIKE '%must-not-exist%')
);"""
cmd=['sshpass','-e','ssh','-n','-o','StrictHostKeyChecking=yes','ablecloud@10.10.'+c+'.10',shlex.join(['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'mysql','-uroot','-N','-B','--raw','cloud','-e',query])]
d=json.loads(subprocess.check_output(cmd,env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},text=True));Path('/home/ablecloud/work/snapshot-actions-1479-ui-20261023/'+c+'-final-counters.json').write_text(json.dumps(d,indent=2)+'\n');assert all(v==0 for v in d.values()),d;print(c,'DB fixture/forbidden counters all zero')
