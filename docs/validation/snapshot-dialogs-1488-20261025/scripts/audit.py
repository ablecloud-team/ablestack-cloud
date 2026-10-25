import json, re, sys, urllib.parse
from remote import root, sql, remote

c, phase = sys.argv[1:3]
r = json.loads((root / (c + '-fixtures.json')).read_text())
def ids(rows):
    values = [x['id'] for x in rows]
    assert values and all(re.fullmatch('[a-f0-9-]{36}', x) for x in values)
    return ','.join("'" + x + "'" for x in values)

sids = ids(r['snapshots']); vids = ids(r['volumes'])
out = {
    'snapshots': sql(c, "SELECT JSON_OBJECT('id',s.uuid,'name',s.name,'state',s.status,'removed',s.removed,'volume',v.uuid,'volumeActive',v.removed IS NULL,'account',a.account_name) FROM snapshots s JOIN volumes v ON v.id=s.volume_id JOIN account a ON a.id=s.account_id WHERE s.uuid IN (" + sids + ");"),
    'references': sql(c, "SELECT JSON_OBJECT('id',r.id,'snapshot',s.uuid,'role',r.store_role,'state',r.state,'download',r.download_state,'size',r.size,'physical',r.physical_size,'path',r.install_path,'store',COALESCE(i.uuid,p.uuid),'url',i.url,'zone',COALESCE(i.data_center_id,p.data_center_id)) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id LEFT JOIN image_store i ON r.store_role='Image' AND i.id=r.store_id LEFT JOIN storage_pool p ON r.store_role='Primary' AND p.id=r.store_id WHERE s.uuid IN (" + sids + ");"),
    'volumes': sql(c, "SELECT JSON_OBJECT('id',v.uuid,'name',v.name,'state',v.state,'removed',v.removed,'size',v.size,'offering',d.uuid,'path',v.path,'vm',vm.uuid) FROM volumes v LEFT JOIN disk_offering d ON d.id=v.disk_offering_id LEFT JOIN vm_instance vm ON vm.id=v.instance_id WHERE v.uuid IN (" + vids + ");"),
    'templates': [],
    'physical': []
}
if r.get('templates'):
    out['templates'] = sql(c, "SELECT JSON_OBJECT('id',t.uuid,'name',t.name,'format',t.format,'removed',t.removed,'state',r.state,'size',r.size,'physical',r.physical_size,'path',r.install_path,'url',i.url) FROM vm_template t LEFT JOIN template_store_ref r ON r.template_id=t.id AND r.store_role='Image' LEFT JOIN image_store i ON i.id=r.store_id WHERE t.uuid IN (" + ids(r['templates']) + ");")

if phase in ['created', 'copy', 'zone-deleted', 'archive-verified']:
    target = next(x for x in r['snapshots'] if x['scenario'] == 'ui-source')
    refs = [x for x in out['references'] if x['snapshot'] == target['id'] and x['state'] == 'Ready' and x['role'] == 'Image']
    assert len(refs) == (2 if phase == 'copy' else 1), refs
    for ref in refs:
        store = urllib.parse.urlparse(ref['url'])
        assert store.scheme == 'nfs' and store.hostname == '10.10.' + c + '.10' and store.path.startswith('/nfs/secondary')
        path = store.path.rstrip('/') + '/' + ref['path']
        assert '..' not in path.split('/')
        info = json.loads(remote(c, ['sudo', '-n', 'qemu-img', 'info', '--output=json', path]))
        assert info['format'] == 'qcow2' and info['virtual-size'] == 1073741824 and ref['size'] == 1073741824
        pattern = remote(c, ['sudo', '-n', 'timeout', '30', 'qemu-io', '-r', '-f', 'qcow2', '-c', 'read -P 0x5a 0 1M', path])
        digest = remote(c, ['sudo', '-n', 'sha256sum', path]).split()[0]
        size = int(remote(c, ['sudo', '-n', 'stat', '-c', '%s', path]))
        assert size == ref['physical']
        out['physical'].append(dict(snapshot=target['id'], store=ref['store'], path=path, info=info, sha256=digest, fileSize=size, pattern=pattern))
    assert len({x['sha256'] for x in out['physical']}) == 1
    if phase == 'zone-deleted':
        assert refs[0]['store'] == r['zones'][0]['store']['id']
        assert next(x for x in out['snapshots'] if x['id'] == target['id'])['state'] == 'BackedUp'
        before = json.loads((root / (c + '-db-copy.json')).read_text())
        original = next(x for x in before['physical'] if x['store'] != r['zones'][0]['store']['id'])['path']
        assert remote(c, ['sudo', '-n', 'test', '!', '-e', original]) == ''
        out['deletedSourceBackup'] = original

if phase == 'derived':
    source = next(x for x in r['volumes'] if x['scenario'] == 'owner')
    source_db = next(x for x in out['volumes'] if x['id'] == source['id'])
    volumes = [x for x in out['volumes'] if any(v['id'] == x['id'] and v['scenario'] == 'ui-volume' for v in r['volumes'])]
    assert volumes and out['templates']
    for volume in volumes:
        assert volume['state'] == 'Ready' and volume['removed'] is None and volume['vm'] is None
        assert volume['size'] == 1073741824 and volume['offering'] == source_db['offering']
        image = '/mnt/glue-gfs/' + volume['path'] if c == '31' else 'rbd:rbd/' + volume['path']
        info = json.loads(remote(c, ['qemu-img', 'info', '--output=json', image], 1))
        assert info['virtual-size'] == 1073741824
        pattern = remote(c, ['timeout', '30', 'qemu-io', '-r', '-f', 'qcow2' if c == '31' else 'raw', '-c', 'read -P 0x5a 0 1M', image], 1)
        out['physical'].append(dict(volume=volume['id'], image=image, info=info, pattern=pattern))
    for template in out['templates']:
        assert template['removed'] is None and template['format'] == 'QCOW2' and template['size'] == 1073741824
        store = urllib.parse.urlparse(template['url'])
        path = template['path'] if template['path'].startswith('/nfs/secondary/') else store.path.rstrip('/') + '/' + template['path']
        assert path.startswith('/nfs/secondary/') and '..' not in path.split('/')
        info = json.loads(remote(c, ['sudo', '-n', 'qemu-img', 'info', '--output=json', path]))
        assert info['format'] == 'qcow2' and info['virtual-size'] == 1073741824
        pattern = remote(c, ['sudo', '-n', 'timeout', '30', 'qemu-io', '-r', '-f', 'qcow2', '-c', 'read -P 0x5a 0 1M', path])
        out['physical'].append(dict(template=template['id'], path=path, info=info, pattern=pattern))

(root / (c + '-db-' + phase + '.json')).write_text(json.dumps(out, ensure_ascii=False, indent=2))
print(c, phase, 'independent DB', len(out['snapshots']), 'snapshots;', len(out['physical']), 'physical data checks')
