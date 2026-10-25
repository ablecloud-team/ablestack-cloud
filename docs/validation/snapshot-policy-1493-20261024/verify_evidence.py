"""Offline source/evidence consistency checks; no remote writes or credentials."""
import hashlib
import json
from pathlib import Path
import struct

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]


def read(name):
    return json.loads((HERE / name).read_text(encoding='utf-8'))


sources = read('source-sha256.json')
assert len(sources) == 8
for source in sources:
    assert hashlib.sha256((REPO / source['path']).read_bytes()).hexdigest() == source['sha256'], source['path']
checks = read('checks.json')
assert sum(x['tests'] for x in checks['java'].values()) == 124
assert all(x['pass'] and x['checkstyle'] == 'PASS' for x in checks['java'].values())
assert checks['python']['tests'] == 12 and checks['python']['pass']
assert not checks['fullCloudBuild'] and not checks['actionsDispatched'] and not checks['uiSourceChanged']
ui_count = 0
for cluster in ['31', '32']:
    recovery = read(cluster + '-history-recovery.json')
    assert len(recovery['applied']['changed']) == 5
    assert len(recovery['idempotent']['already_applied']) == 5 and not recovery['idempotent']['changed']
    assert recovery['stalePlanRejected']['expectedFailure'] and recovery['onlyTypeDescriptionAndHiddenAuditChanged']
    entries = recovery['reviewedWithArchive']['plan']['entries']
    assert len([e for e in entries if e['action'] == 'skip']) == 2
    assert sorted(e['target_type'] for e in entries if e['action'] == 'repair' and e['snapshot']['status'] == 'BackedUp') == [3, 4, 5, 6]
    cases = read(cluster + '-api-db-validation.json')
    assert cases['pass'] == len(cases['cases']) == 90 and all(c['pass'] for c in cases['cases'])
    assert len([c for c in cases['cases'] if c.get('denied')]) == 8
    for case in cases['cases']:
        if not case.get('denied'):
            assert case['ids'] == case['expectedIds'] and case['count'] == len(case['ids'])
    assert len(read(cluster + '-api-rejections.json')) == 5
    acl = read(cluster + '-allocation-acl.json')
    assert len(acl['checks']) == 3 and acl['noUnauthorizedAllocation']
    lifecycle = read(cluster + '-policy-api-lifecycle.json')
    assert len(lifecycle['created']) == len(lifecycle['deleted']) == 12 and lifecycle['allNormallyDeleted']
    fixtures = read(cluster + '-fixtures.json')
    assert len(fixtures['snapshots']) == 14 and all(p['deleted'] for p in fixtures['policies'])
    assert read(cluster + '-retention.json')['retained']['count'] == 2
    scheduler = read(cluster + '-scheduler.json')
    assert scheduler['created']['intervaltype'] == 'HOURLY' and scheduler['systemJobObserved']
    assert all(s['policy_id'] == 8 and s['async_job_id'] is None and s['snapshot_id'] is None for s in scheduler['nextSchedule'])
    job = read(cluster + '-scheduler-job.json')[0]
    assert job['user'] == 1 and job['status'] == 1 and job['policy'] == '8'
    final = read(cluster + '-final-state.json')
    assert not any(final['database'].values()) and final['normalApiCleanup']
    assert final['runtime'] == dict(verifiedClassCount=5, webInf=True, mold='active', clientHttp=200)
    assert final['physical']['secondaryChecked'] == 14 and final['physical']['primaryVolumesChecked'] == 2
    assert not final['physical']['remaining'] and not final['physical']['rbdTrash']
    preservation = read(cluster + '-preservation.json')
    assert preservation['status'] == 'PASS' and len(preservation['preserved']) == 8
    assert all(x['unchanged'] for x in preservation['preserved']) and not any(x['count'] for x in preservation['fixtureActive'])
    deployment = read(cluster + '-deployment.json')
    assert deployment['originalHostsUp'] == 5 and deployment['webapp']['beforeAfterEqual']
    assert deployment['management'][0]['otherEntriesUnchanged']
    ui = read('browser/' + cluster + '-ui.json')
    for record in ui:
        assert record['api']['http'] == 200 and record['api']['count'] == len(record['rows'])
        assert [r['id'] for r in record['rows']] == [r['id'] for r in record['api']['snapshots']]
    assert ui[-1]['name'] == 'cleanup-empty' and not ui[-1]['rows']
    ui_count += len(ui)
    png = (HERE / 'browser' / (cluster + '-monthly.png')).read_bytes()
    width, height = struct.unpack('>II', png[16:24])
    assert width >= 900 and height >= 700
    print(cluster, 'PASS: 90 API/DB, 5 history repairs, scheduler, cleanup and original inventory')
assert ui_count == 13
assert all(x['unchanged'] for x in read('protected-trees.json').values())
assert len(read('32-deployment-incident.json')['oomLines']) == 4
print('PASS: 124 Java / 12 Python / 13 live UI scenarios and protected workspaces')
