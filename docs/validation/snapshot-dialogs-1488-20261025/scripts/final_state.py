import json, re, sys, urllib.parse
from pathlib import Path
from remote import root, sql, remote

c = sys.argv[1]
r = json.loads((root / (c + '-fixtures.json')).read_text())
def ids(rows):
    values = [x['id'] for x in rows]
    assert values and all(re.fullmatch('[a-f0-9-]{36}', x) for x in values)
    return ','.join("'" + x + "'" for x in values)

sid, vid, aid = ids(r['snapshots']), ids(r['volumes']), ids(r['accounts'])
queries = {
    'snapshots': "SELECT COUNT(*) FROM snapshots WHERE uuid IN (" + sid + ") AND status<>'Destroyed' AND removed IS NULL",
    'references': "SELECT COUNT(*) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.uuid IN (" + sid + ") AND r.state<>'Destroyed'",
    'volumes': "SELECT COUNT(*) FROM volumes WHERE uuid IN (" + vid + ") AND removed IS NULL",
    'tags': "SELECT COUNT(*) FROM resource_tags WHERE resource_uuid IN (" + sid + ")",
    'accounts': "SELECT COUNT(*) FROM account WHERE uuid IN (" + aid + ") AND removed IS NULL",
    'domains': "SELECT COUNT(*) FROM domain WHERE uuid='" + r['domain']['id'] + "' AND removed IS NULL",
    'projects': "SELECT COUNT(*) FROM projects WHERE uuid='" + r['project']['id'] + "' AND removed IS NULL",
    'vms': "SELECT COUNT(*) FROM vm_instance WHERE uuid IN (" + ids(r['vms']) + ") AND removed IS NULL",
    'stores': "SELECT COUNT(*) FROM image_store WHERE uuid IN (" + ids([z['store'] for z in r['zones']]) + ") AND removed IS NULL",
    'zones': "SELECT COUNT(*) FROM data_center WHERE uuid IN (" + ids(r['zones']) + ") AND removed IS NULL",
    'agents': "SELECT COUNT(*) FROM host WHERE name LIKE '" + r['prefix'] + "-%' AND removed IS NULL"
}
if r.get('templates'):
    queries['templates'] = "SELECT COUNT(*) FROM vm_template WHERE uuid IN (" + ids(r['templates']) + ") AND removed IS NULL"
    queries['templateReferences'] = "SELECT COUNT(*) FROM template_store_ref r JOIN vm_template t ON t.id=r.template_id WHERE t.uuid IN (" + ids(r['templates']) + ") AND r.state<>'Destroyed' AND r.destroyed=0"
pairs = ','.join("'" + k + "',(" + v + ")" for k, v in queries.items())
out = {'database': sql(c, 'SELECT JSON_OBJECT(' + pairs + ');')[0]}
assert not any(out['database'].values()), out

paths = set(); primary_names = set()
for file in root.glob(c + '-db-*.json'):
    d = json.loads(file.read_text())
    primary_names.update(v['path'] for v in d.get('volumes', []) if v.get('path'))
    for ref in d.get('references', []):
        if ref.get('role') != 'Image' or not ref.get('path'):
            continue
        store = urllib.parse.urlparse(ref.get('url') or 'nfs://10.10.' + c + '.10/nfs/secondary')
        path = store.path.rstrip('/') + '/' + ref['path']
        assert path.startswith('/nfs/secondary/') and '..' not in path.split('/')
        paths.add(path)
    for row in d.get('templates', []):
        if row.get('path'):
            path = row['path'] if row['path'].startswith('/nfs/secondary/') else urllib.parse.urlparse(row['url']).path.rstrip('/') + '/' + row['path']
            assert path.startswith('/nfs/secondary/') and '..' not in path.split('/')
            paths.add(path)
code = "import pathlib,json;paths=" + repr(sorted(paths)) + ";print(json.dumps([x for x in paths if pathlib.Path(x).exists()]))"
remaining = json.loads(remote(c, ['sudo', '-n', 'python3', '-c', code]))
assert not remaining, remaining
volumes = sql(c, "SELECT JSON_OBJECT('path',path) FROM volumes WHERE uuid IN (" + vid + ")")
names = sorted(primary_names | {x['path'] for x in volumes if x.get('path')})
assert len(names) >= len(r['volumes'])
if c == '31':
    paths_primary = ['/mnt/glue-gfs/' + x for x in names]
    left = json.loads(remote(c, ['python3', '-c', "import pathlib,json;print(json.dumps([p for p in " + repr(paths_primary) + " if pathlib.Path(p).exists()]))"], 1))
    trash = []
else:
    live = json.loads(remote(c, ['rbd', 'ls', '--format', 'json', 'rbd'], 1))
    trash = json.loads(remote(c, ['rbd', 'trash', 'ls', '--format', 'json', 'rbd'], 1))
    left = [x for x in names if x in live]; trash = [x for x in trash if x.get('name') in names]
assert not left and not trash
vm_domains=sql(c, "SELECT JSON_OBJECT('instance',instance_name) FROM vm_instance WHERE uuid IN ("+ids(r['vms'])+")")
vm_names={x['instance'] for x in vm_domains if x.get('instance')} | {v.get('instancename') for v in r['vms'] if v.get('instancename')}
assert vm_names
for host in [1, 2, 3]:
    domains = remote(c, ['virsh', 'list', '--all', '--name'], host).splitlines()
    assert all(name not in domains for name in vm_names)

fixture = Path('/nfs/secondary') / r['prefix']
code = """import pathlib,shutil,json
p=pathlib.Path(TARGET)
assert p.parent==pathlib.Path('/nfs/secondary') and p.name.startswith('SNAPDIALOG1488-') and not p.is_symlink()
if p.exists():
 assert not any(x.is_file() or x.is_symlink() for x in p.rglob('*'))
 shutil.rmtree(p)
print(json.dumps({'fixtureNfsDirectoryAbsent':not p.exists()}))
""".replace('TARGET', repr(str(fixture)))
out['fixtureNfs'] = json.loads(remote(c, ['sudo', '-n', 'python3', '-c', code]))
out['physical'] = {'secondaryAndTemplateFilesChecked': len(paths), 'primaryVolumesChecked': len(names), 'remaining': remaining + left, 'rbdTrash': trash, 'fixtureVmDomainsAbsentOnAllHosts': True}
out['agents'] = json.loads((root / (c + '-agent-cleanup.json')).read_text())
out['runtime'] = {'mold': remote(c, ['systemctl', 'is-active', 'mold']), 'http': int(remote(c, ['curl', '-s', '-o', '/dev/null', '-w', '%{http_code}', 'http://127.0.0.1:8080/client/']))}
assert out['runtime'] == {'mold': 'active', 'http': 200}
(root / (c + '-final-state.json')).write_text(json.dumps(out, indent=2))
print(c, 'active DB and physical residual zero; original agent preserved; Mold active/HTTP200')
