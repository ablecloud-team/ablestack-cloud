import json, re, sys
from pathlib import Path
sys.path.insert(0, '/home/ablecloud/work/snapshot-detail-1486-20261025/scripts')
from remote import root, sql

def read(c, phase):
    return json.loads((root/'browser'/(c+'-'+phase+'.json')).read_text())

def size(n):
    if n == 0: return '0 B'
    i = 0
    while n >= 1024 and i < 5:
        n /= 1024; i += 1
    return f'{n:.2f} '+['B','KiB','MiB','GiB','TiB','PiB'][i]

for c in ['31','32']:
    r=json.loads((root/(c+'-fixtures.json')).read_text())
    api=json.loads((root/(c+'-detail-api-oracle.json')).read_text())
    cases=[]
    def passed(name): cases.append({'name':name,'pass':True})
    for scenario, phase in [('vmdata','main'),('root','root'),('owner','owner'),('project','project'),('deleted','deleted')]:
        expected=next(x for x in api['observed'] if x['scenario']==scenario)
        p=read(c,phase); s=expected['record']
        assert p['summary'] and p['properties'] and s['id'] in p['text']
        assert size(s['virtualsize']) in p['text'] and size(s['physicalsize']) in p['text']
        assert {x['id'] for x in p['copies']} == {x['copy']['id'] for x in expected['copies']}
        for copy in expected['copies']:
            rendered=next(x['text'] for x in p['copies'] if x['id']==copy['copy']['id'])
            assert copy['datastorename'] in rendered and size(copy['copy']['virtualsize']) in rendered
        if scenario=='project':
            assert all('projectid='+s['projectid'] in x['href'] for x in p['links'] if x['href'].startswith(('#/volume/','#/vm/')))
        passed(phase+'-identity-size-copy-api-db')
    for phase in ['403-cleared','missing-cleared','other-denied','domainadmin-root-denied']:
        p=read(c,phase); assert not p['summary'] and not p['properties'] and 'baseline' not in p['text']
        assert '재시도' in (root/'browser'/(c+'-'+phase+'.txt')).read_text()
        passed(phase)
    for phase in ['503-retained','copies-incomplete-retained']:
        p=read(c,phase); assert p['summary'] and p['properties'] and r['prefix']+'-vmdata' in p['text'] and 'baseline' in p['text']
        assert 'button "down 작업":' not in (root/'browser'/(c+'-'+phase+'.txt')).read_text()
        passed(phase)
    for phase in ['deleted','related403']:
        p=read(c,phase); assert not any(x['href'].startswith(('#/volume/','#/vm/')) for x in p['links'])
        assert '조회할 수 없습니다' in p['text']; passed(phase+'-no-dead-source-links')
    assert 'menuitem "볼륨 생성"' in (root/'browser'/(c+'-deleted-actions.txt')).read_text();passed('deleted-source-volume-create-retained')
    p=read(c,'late-response-ignored'); assert p['heldResponses']>0 and p['expectedId'] in p['text'];passed('late-response-ignored')
    p=read(c,'synthetic-chain-size'); assert p['synthetic'] and '0 B' in p['text'] and '미확인' in p['text'] and size(p['chainBytes']) in p['text']
    assert any(x['href'].startswith('#/snapshot/'+p['parentId']) for x in p['links']);passed('synthetic-parent-link-zero-unknown')
    for phase in ['read-only-chain-off','admin-chain-off']:
        p=read(c,phase);assert '체인 물리 크기' not in p['text'] and '부모 스냅샷' not in p['text'];passed(phase)
    p=read(c,'read-only-chain-off');assert not any(x['href'].startswith(('#/volume/','#/vm/','#/storagepool/','#/imagestore/','#/account?','#/domain/')) for x in p['links'])
    assert '새 태그' not in p['text'] and 'baseline' in p['text'];passed('readonly-information-mutation-controls')
    for phase in ['owner-role','owner-project','other-own','domainadmin-child']:
        p=read(c,phase);assert p['summary'] and p['properties'];passed(phase)
    p=read(c,'tags503-retained');assert p['tagCreateDisabled'] and 'baseline' in p['text'];passed('tag-read-failure-retention-write-gate')
    assert 'detail1486 =' in read(c,'tag-created')['text'] and 'detail1486 =' not in read(c,'tag-deleted')['text'];passed('tag-job-completion-refresh')
    for mode in ['light','dark']:
        for w in [390,768,1280]:
            p=read(c,mode+'-'+str(w));l=p['layout'];assert l['width']==w and l['noOverlap'] and l['pageOverflowContained']
            assert all(x['weight']=='700' and x['margin']=='5px' for x in l['items'])
            png=(root/'browser'/(c+'-'+mode+'-'+str(w)+'.png')).read_bytes();assert int.from_bytes(png[16:20],'big')==w
            passed(mode+'-'+str(w)+'-vm-fields-layout')
    jobs=set()
    for phase, command in [('tag-create','createTags'),('tag-delete','deleteTags')]:
        n=read(c,phase+'-network')['requests'];n=[x for x in n if x['command'] in ['listTags','createTags','deleteTags','queryAsyncJobResult']]
        (root/'browser'/(c+'-'+phase+'-network.json')).write_text(json.dumps({'requests':n},ensure_ascii=False,indent=2))
        assert any(x['command']==command and x['status']==200 for x in n)
        for x in n:
            for v in (x.get('response')or{}).values():
                if isinstance(v,dict) and v.get('jobid'):jobs.add(v['jobid'])
            if x['command']=='queryAsyncJobResult' and x['args'].get('jobid'):jobs.add(x['args']['jobid'])
    assert len(jobs)==2 and all(re.fullmatch('[a-f0-9-]{36}',x) for x in jobs)
    rows=sql(c,"SELECT JSON_OBJECT('uuid',uuid,'command',job_cmd,'status',job_status,'resultCode',job_result_code) FROM async_job WHERE uuid IN ("+','.join("'"+x+"'"for x in jobs)+")")
    assert len(rows)==2 and all(x['status']==1 for x in rows);passed('actual-ui-tag-jobs-db-terminal')
    sid=next(x['id'] for x in r['snapshots'] if x['scenario']=='vmdata')
    assert sql(c,"SELECT JSON_OBJECT('count',COUNT(*)) FROM resource_tags WHERE resource_uuid='"+sid+"' AND `key`='detail1486'")[0]['count']==0;passed('ui-tag-deletion-db-zero')
    p=read(c,'keyboard-copy');assert p['copyUiSuccess'];passed('keyboard-copy-success-ui')
    links=['volume','account','domain','zone','imagestore']+(['storagepool']if c=='32'else[])
    for kind in links:
        p=read(c,'link-'+kind);assert p['url'].split('#')[1]==p['expectedHref'][1:] and p['text'];passed('actual-link-'+kind)
    (root/(c+'-ui-db-oracle.json')).write_text(json.dumps({'cases':cases,'jobs':rows,'clipboardLimitation':'UI keyboard/click success confirmed; automation clipboard read empty; OS bytes not verified','chainLimitation':'No physical parent chains on either cluster; positive UI contract uses labelled response injection'},ensure_ascii=False,indent=2))
    print(c,'UI / API / independent DB qualification PASS',len(cases),'cases; 2 actual tag jobs terminal')
