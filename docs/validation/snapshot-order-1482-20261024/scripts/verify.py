"""Offline evidence/hash verification. Python 3 standard library only."""
import hashlib
import json
from pathlib import Path
import re
import uuid

folder = Path(__file__).resolve().parents[1]
repo = folder.parents[2]
checks = 0


def check(condition, message):
    global checks
    assert condition, message
    checks += 1


def read(name):
    return json.loads((folder / name).read_text(encoding='utf-8'))


def expected_rows(rows, fixture, args, user='admin'):
    selected = list(rows)
    if user in ['owner', 'other'] and 'projectid' not in args:
        selected = [x for x in selected if x['account_name'] == fixture['prefix'] + '-' + user]
    if args.get('keyword'):
        selected = [x for x in selected if args['keyword'].lower() in x['name'].lower()]
    if args.get('name'):
        selected = [x for x in selected if args['name'].lower() == x['name'].lower()]
    if args.get('account'):
        selected = [x for x in selected if args['account'] == x['account_name']]
    if args.get('volumeid'):
        volume = next(x for x in fixture['volumes'] if x['id'] == args['volumeid'])
        selected = [x for x in selected if x['volume_name'] == volume['name']]
    if args.get('domainid') and not args.get('isrecursive'):
        domain = next(x for x in [fixture['domain'], fixture['child']] if x['id'] == args['domainid'])
        selected = [x for x in selected if x['domain_name'] == domain['name']]
    if 'projectid' not in args:
        selected = [x for x in selected if x['project_id'] is None]
    elif args['projectid'] != '-1':
        selected = [x for x in selected if x['project_name'] == fixture['project']['name']]
    if args.get('zoneid'):
        selected = [x for x in selected if x['data_center_uuid'] == args['zoneid']]
    role = 'Primary' if args.get('storageid') else 'Image' if args.get('imagestoreid') else None
    if role:
        selected = [x for x in selected if x['store_role'] == role and x['store_uuid'] == (args.get('storageid') or args.get('imagestoreid'))]
    elif not args.get('showunique', True) and args.get('locationtype'):
        role = 'Primary' if args['locationtype'] == 'primary' else 'Image'
        selected = [x for x in selected if x['store_role'] == role]
    if 'tags[0].key' in args:
        tagged = {x['id'] for x in rows if x['tag_key'] == args['tags[0].key'] and x['tag_value'] == args['tags[0].value']}
        selected = [x for x in selected if x['id'] in tagged]
    unique = args.get('showunique', True)
    groups = {}
    for row in sorted(selected, key=lambda x: x['snapshot_store_pair']):
        groups.setdefault(row['id'] if unique else row['snapshot_store_pair'], row)
    ordered = list(groups.values())
    reverse = args.get('sortorder', 'desc') == 'desc'
    ordered.sort(key=lambda x: x['snapshot_store_pair'])
    ordered.sort(key=lambda x: x['id'], reverse=reverse)
    fields = {'created': 'created', 'id': 'id', 'name': 'name', 'state': 'status', 'volumename': 'volume_name', 'intervaltype': 'snapshot_type', 'virtualsize': 'size', 'account': 'account_name', 'domain': 'domain_name', 'project': 'project_name'}
    field = fields[args.get('sortkey', 'created')]

    def value(row):
        item = row[field]
        if isinstance(item, str):
            check(item.isascii(), 'This fixture oracle handles ASCII DB collation only')
            item = item.lower()
        return (item is not None, item if item is not None else '')

    ordered.sort(key=value, reverse=reverse)
    return ordered


def identity(row, unique=True):
    if unique:
        return row['uuid']
    source = row['uuid'] + ':' + row['snapshot_store_pair'] + ':' + row['data_center_uuid']
    return str(uuid.UUID(bytes=hashlib.md5(source.encode()).digest(), version=3))


for cluster in ['31', '32']:
    evidence = read(cluster + '-validation.json')
    fixture = evidence['fixtures']
    db = read(cluster + '-db-fixtures.json')
    check(len({x['id'] for x in db}) == 30, cluster + ' real snapshots')
    check(len({x['snapshot_store_pair'] for x in db}) == (30 if cluster == '31' else 60), cluster + ' actual references')
    check(len(evidence['apiDb']['cases']) == 82, cluster + ' case count')
    for case in evidence['apiDb']['cases']:
        rows = expected_rows(db, fixture, case['args'], case['user'])
        expected = [identity(x, case['args']['showunique']) for x in rows]
        check(expected == case['expectedIds'] == case['actualIds'], cluster + ' independent order ' + case['name'])
        check(len(expected) == case['count'] == sum(case['pageLengths']), cluster + ' count ' + case['name'])
        check(len(set(expected)) == len(expected), cluster + ' no duplicates ' + case['name'])
        check(case['lastPageAndBeyond'], cluster + ' beyond last page ' + case['name'])
    check(len(evidence['apiDb']['invalidSort']) == 6 and all(x['errorcode'] == 431 for x in evidence['apiDb']['invalidSort']), 'Invalid sorts rejected')
    check(evidence['defaultCompatibility']['defaultMatchesExplicit'] and evidence['defaultCompatibility']['defaultLogicalMode'], 'Legacy/default contract')
    check(evidence['aclDenials']['status'] == 'PASS', 'Cross-owner denial')
    check(len(evidence['tieFixture']['before']) == 8, 'Intentional same-time fixture')
    runtime = evidence['runtime']
    check(runtime['clientHttp'] == 200 and runtime['mold'] == 'active', 'Runtime healthy')
    check(len(runtime['managementClasses']) == 4 and runtime['staticFiles'] == 921 and runtime['staticVerified'], 'Runtime hashes')
    check(runtime['webInfPreserved'] and runtime['originalHostsPreserved'] and runtime['hostsUp'] == 5, 'Server/hosts preserved')
    check(len(evidence['cleanup']) == 41 and all(x['complete'] for x in evidence['cleanup']), 'Normal API cleanup')
    check(not any(evidence['finalState']['database'].values()), 'Active DB fixture residuals zero')
    check(evidence['finalState']['physical']['remaining'] == [], 'Owned storage files/images absent')
    check(evidence['preservation']['status'] == 'PASS', 'Operational resources preserved')

    for proof_path in sorted((folder / 'browser').glob(cluster + '-*.json')):
        proof = json.loads(proof_path.read_text(encoding='utf-8'))
        if 'network' not in proof:
            continue
        network, dom = proof['network'], proof['dom']
        args = dict(network['args'])
        user = 'owner' if '-owner-ui-' in proof_path.name else 'other' if '-other-ui-' in proof_path.name else 'admin'
        args['showunique'] = args.get('showunique', 'true') != 'false'
        rows = expected_rows(db, fixture, args, user)
        page, size = int(args.get('page', 1)), int(args.get('pagesize', 20))
        expected = [x['uuid'] for x in rows[(page - 1) * size:page * size]]
        check(network['count'] == len(rows), 'UI/DB count ' + proof_path.name)
        check(expected == [x['id'] for x in network['rows']] == [x['id'] for x in dom['rows']], 'UI/API/DB order ' + proof_path.name)
        check(not any(re.match(r'^(sessionkey|apikey|secretkey|password|cookie|token)$', x, re.I) for x in args), 'No credentials in proof')

    final = read('browser/' + cluster + '-final-search-reset.json')
    check(final['network']['count'] == 8 and final['network']['args'].get('account') == fixture['prefix'] + '-owner', 'Final search retains ownership')
    check(final['network']['args']['page'] == '1', 'Final search resets page')

late = read('browser/31-late-response.json')
check(late['status'] == 'PASS' and late['interceptionCleared'] and late['oldResponseFinished'], 'Late response fully drained')
check(late['responseIds'] == late['dom']['ids'], 'Late response cannot overwrite latest')
for name in ['protected-windows-final.json', 'protected-wsl-final.json']:
    check(read(name)['unchanged'], 'Protected FT tree unchanged')
for item in read('source-sha256.json'):
    check(hashlib.sha256((repo / item['path']).read_bytes()).hexdigest() == item['sha256'], 'Source hash ' + item['path'])
for item in read('manifest.json'):
    data = (folder / item['path']).read_bytes()
    check(hashlib.sha256(data).hexdigest() == item['sha256'], 'Evidence hash ' + item['path'])
    if (folder / item['path']).suffix in ['.json', '.md', '.py', '.mjs', '.log']:
        check(not re.search(rb'Ablecloud\d{1,4}!|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', data), 'No secret material ' + item['path'])
for log, marker in [('api-build.log', 'BUILD SUCCESS'), ('server-tests.log', 'Tests run: 20, Failures: 0, Errors: 0'), ('ui-tests.log', 'Tests:       74 passed'), ('ui-lint.log', 'No lint errors found'), ('ui-build.log', 'Build complete.')]:
    check(marker in (folder / log).read_text(encoding='utf-8'), 'Final test/build log ' + log)
result = {'status': 'PASS', 'checks': checks, 'scope': 'Offline evidence consistency and SHA256; not Cloud CI'}
(folder / 'verification.json').write_bytes((json.dumps(result, indent=2) + '\n').encode('utf-8'))
print(json.dumps(result))
