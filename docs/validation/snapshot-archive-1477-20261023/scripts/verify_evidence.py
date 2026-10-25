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


# Verification of saved evidence; never mutates a cluster.
import hashlib,json,sys
from pathlib import Path
root=Path(sys.argv[1] if len(sys.argv)>1 else '/home/ablecloud/work/snapshot-archive-1477-20261023')
def read(name):return json.loads((root/name).read_text())
summary={}
for c in ['31','32']:
    normal=read(c+'-archive-normal.json')
    assert normal['staleRejected'] and all(x['status']=='fulfilled' for x in normal['results'])
    assert normal['after']['primaryreferences']==0 and normal['after']['secondaryreferences']==1
    failed=read(c+'-archive-verification-fail.json');assert 'SECONDARY_VERIFICATION_FAILED' in failed['failure'] and failed['before']['scopehash']==failed['after']['scopehash']
    missing=read(c+'-normal-missing-metadata.json');assert 'INCOMPLETE_STORE_REFERENCE' in missing['failure'] and missing['before']['scopehash']==missing['after']['scopehash']
    ambiguous=read(c+'-archive-ambiguous.json');assert not ambiguous['before']['eligible'] and ambiguous['before']['scopehash']==ambiguous['after']['scopehash']
    denied=read(c+'-tenant-force-denied.json');assert len(denied['results'])==2 and all(x['denied'] for x in denied['results']) and denied['before']['scopehash']==denied['after']['scopehash']
    assert read(c+'-tenant-archive.json')['after']['reason']=='ALREADY_ARCHIVED'
    for scenario in ['force-busy','force-no-native','force-no-mapping']:
        first=read(c+'-'+scenario+'-force.json')['result'];assert first['force'] and first['dbcleanupcomplete'] and first['state']=='Destroyed'
        if scenario=='force-busy':
            assert not first['physicalcleanupcomplete'] and first['pendingcleanup']==1
            retry=read(c+'-'+scenario+'-force-retry.json')['result'];assert retry['physicalcleanupcomplete'] and retry['pendingcleanup']==0
        elif scenario=='force-no-native':assert first['physicalcleanupcomplete'] and first['pendingcleanup']==0
        else:assert not first['physicalcleanupcomplete'] and first['cleanupresult'][0]['reason']=='NO_IDENTIFIABLE_STORE_REFERENCE'
    for scenario in ['force-busy','force-no-native','force-path']:
        repeat=read(c+'-'+scenario+'-force-repeat.json')['result'];assert repeat['physicalcleanupcomplete'] and repeat['pendingcleanup']==0
    db_only=read(c+'-force-path-db-only-db.json');reg=read(c+'-fixtures.json');target=next(x for x in reg['snapshots'] if x['scenario']=='force-path')
    target_rows=[x for x in db_only['snapshots'] if x['uuid']==target['id']];assert target_rows and all(x['state']=='Destroyed' and x['refstate']=='Destroyed' for x in target_rows)
    assert any(x['key'].startswith('snapshot.force-delete.pending.') and x['snapshot']==target['id'] for x in db_only['forcedCleanupAudit'])
    before=read(c+'-existing-before.json');after=read(c+'-existing-after.json');inventories={}
    for cmd,key,fields in [('listSnapshots','snapshot',['id','name','state','volumeid','zoneid','locationtype','physicalsize','virtualsize','snapshottype','intervaltype','account','domainid']),('listVolumes','volume',['id','name','state','virtualmachineid','path','storage','storageid','size','account','domainid'])]:
        old=before[cmd].get(key,[]);new=after[cmd].get(key,[]);new_by_id={x['id']:x for x in new}
        assert not any(x.get('name','').startswith('SNAP1477-') for x in new)
        for x in old:
            assert x['id'] in new_by_id
            assert {k:x.get(k) for k in fields}=={k:new_by_id[x['id']].get(k) for k in fields}
        projection=sorted([{k:x.get(k) for k in fields} for x in old],key=lambda x:x['id'])
        inventories[key]={'beforeCount':len(old),'afterCount':len(new),'originalResourcesPreserved':True,'projectionSha256':hashlib.sha256(json.dumps(projection,sort_keys=True).encode()).hexdigest()}
    d=read(c+'-final-db.json');p=read(c+'-final-physical.json')
    assert not [x for x in d['snapshots'] if x['state']!='Destroyed' and x['removed'] is None]
    assert not [x for x in d['snapshots'] if x['refstate'] not in [None,'Destroyed']]
    assert all(x['state']=='Expunged' and x['removed'] for x in d['volumes'])
    assert len(d['forcedCleanupAudit'])==1 and d['forcedCleanupAudit'][0]['key']=='snapshot.force-delete.unmapped' and d['forcedCleanupAudit'][0]['display']==0
    assert not any(x['exists'] for x in p['images'])
    if c=='32':assert not any(x['exists'] for x in p['sourceVolumes']) and not p['fixtureClones']
    else:assert not p['sourceVolumePaths']
    actual={(x['owner'],x['type']):x['count'] for x in d['counts']}
    for x in d['expectedCounts']:
        if x['owner']=='admin':
            for k in ['snapshot','secondary_storage']:assert actual[(x['owner'],k)]==x[k]
    pre=read(c+'-before-cleanup-db.json');assert all(x['count']==1 for x in pre['usageEvents'])
    # Known pre-existing deleteSnapshotDirsForAccount bug: tenant deletion publishes a second delete event.
    duplicate=[x for x in d['usageEvents'] if x['count']!=1]
    assert len(duplicate)==1 and duplicate[0]['name']=='SNAP1477-'+c+'-tenant' and duplicate[0]['type']=='SNAPSHOT.DELETE' and duplicate[0]['count']==2
    hosts=[json.loads(x) for x in (root/(c+'-hosts.jsonl')).read_text().splitlines()];assert len(hosts)==3 and all(x['status']=='Up' for x in hosts)
    summary[c]={'fixtureSnapshots':len(reg['snapshots']),'fixtureVolumes':len(reg['volumes']),'activeSnapshots':0,'unsettledReferences':0,'pendingPhysicalCleanup':0,'knownSecondaryFiles':0,'sourceVolumesRemaining':0,'fixtureClonesRemaining':0,'historicalUnmappedAudits':1,'resourceCountsMatchDao':True,'usageEventsOnceBeforeAccountCleanup':True,'knownAccountCleanupDuplicateDeleteEvents':duplicate,'routingHostsUp':3,'originalInventory':inventories}
failed=read('32-archive-backup-failed-db.json');retried=read('32-archive-backup-retried-db.json')
b=[x for x in failed['snapshots'] if x['name']=='SNAP1477-32-residue' and x['role']=='Image' and x['refstate']=='Allocated']
a=[x for x in retried['snapshots'] if x['name']=='SNAP1477-32-residue' and x['role']=='Image' and x['refstate']=='Ready']
assert len(b)==len(a)==1 and b[0]['referenceid']==a[0]['referenceid']==76
for scenario in ['force-secondary-missing','force-primary-only']:
    result=read('32-'+scenario+'-force.json')['result'];assert result['dbcleanupcomplete'] and result['physicalcleanupcomplete'] and result['pendingcleanup']==0
(root/'final-verification.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
