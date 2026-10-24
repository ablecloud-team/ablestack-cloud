"""Verify curated #1483 evidence without credentials, a cluster, or extra dependencies."""
import hashlib
import json
from pathlib import Path
base = Path(__file__).resolve().parent
checks = json.loads((base / 'checks.json').read_text(encoding='utf-8'))
assert checks['api']['tests'] == 5 and checks['server']['tests'] == 26
assert checks['ui']['tests'] == 95 and checks['ui']['suites'] == 6
assert all(checks[key]['success'] for key in ('api', 'server', 'ui', 'lint', 'productionUi'))
for source in json.loads((base / 'source-sha256.json').read_text(encoding='utf-8')):
    assert hashlib.sha256((base.parents[2] / source['path']).read_bytes()).hexdigest() == source['sha256'], source['path']
for cluster in ('31', '32'):
    d = json.loads((base / (cluster + '-validation.json')).read_text(encoding='utf-8'))
    cases = d['apiDb']['cases']
    assert len(cases) == 82
    for case in cases:
        assert case['actualIds'] == case['expectedIds'], case['name']
        assert case['count'] == len(case['actualIds']) == len(set(case['actualIds']))
        assert sum(case['pageLengths']) == case['count']
        assert case['lastPageAndBeyond']
    assert len(d['apiDb']['invalid']) == 5
    assert d['candidates']['status'] == 'PASS' and d['candidates']['tiePagesMatchDb']
    assert len(d['candidates']['checks']) == 9
    assert len(d['aclDenials']['checks']) == 3
    assert d['runtime']['clientHttp'] == 200 and d['runtime']['mold'] == 'active'
    assert d['runtime']['staticVerified'] and d['runtime']['webInfPreserved']
    assert len(d['runtime']['managementClasses']) == 3
    assert d['finalState']['normalApiCleanup']
    assert not any(d['finalState']['database'].values())
    assert not d['finalState']['physical']['remaining']
    assert not d['finalState']['physical']['ownedRbdTrash']
    assert d['preservation']['status'] == 'PASS'
    assert all(x['unchanged'] for x in d['preservation']['preserved'])
    assert not any(x['count'] for x in d['preservation']['fixtureActive'])
    print(cluster, 'PASS: 82 API/DB cases, candidates, ACL, deployment, cleanup')
for name in ('31-final-light', '32-final-dark', '32-final-mobile'):
    layout = json.loads((base / 'browser' / (name + '-layout.json')).read_text(encoding='utf-8'))
    assert not layout['overlaps'] and 'snapshot-standard-dialog' in layout['class']
    assert abs(layout['modal']['w'] - (366 if name.endswith('mobile') else 720)) < 1
    assert layout['gap'] == '16px 20px'
assert not json.loads((base / 'browser/31-final-light-layout.json').read_text(encoding='utf-8'))['dark']
late = json.loads((base / 'browser/32-late-candidate-options.json').read_text(encoding='utf-8'))
late_record = next(r for r in json.loads((base / 'browser/32-ui.json').read_text(encoding='utf-8')) if r['label'] == 'final2-late-owner-response-ignored')
volume_ids = {row['id'] for response in late_record['responses'] if response['args']['command'] == 'listVolumes' for row in response['rows']}
# Ant retains the account popup DOM too; identify volume options by the recorded response IDs.
volume_options = [option for option in late['options'] if any(option.endswith('· ' + volume_id[:8]) for volume_id in volume_ids)]
assert len(volume_options) == 1 and 'SNAPSEARCH1483-32-other' in volume_options[0]
assert 'SNAPSEARCH1483-32-other' in late['selectedAccount']
for cluster in ('31', '32'):
    records = json.loads((base / 'browser' / (cluster + '-ui.json')).read_text(encoding='utf-8'))
    final = [r for r in records if r['phase'] == 'final-live']
    assert any('project' in r['label'] and len(r['rows']) == 3 for r in final)
    assert all(response['args'].get('projectid') != '0' for r in final for response in r['responses'])
protected = json.loads((base / 'protected-trees.json').read_text(encoding='utf-8'))
assert all(protected[host]['statusPreserved'] and protected[host]['trackedDiffPreserved'] for host in ('windows', 'wsl'))
print('PASS: original browser layout, scope, late response and protected workspace evidence')
