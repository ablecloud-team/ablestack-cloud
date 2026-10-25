import json, re, struct
from pathlib import Path

root = Path(__file__).resolve().parent
browser = root / 'browser'
checks = []

def read(name):
    p = root / name
    if not p.exists():
        p = root / 'evidence' / name
    return json.loads(p.read_text())

def logtext(name):
    p = root / name
    return (p if p.exists() else root / 'logs' / name).read_text()

def ui(c, phase):
    return read('browser/' + c + '-' + phase + '.json')

def ok(name, condition, **details):
    assert condition, (name, details)
    checks.append(dict(name=name, pass_=True, **details))

for c in ['31', '32']:
    for phase in ['tab-api-oracle', 'tab-api-after-ui']:
        rows = read(c + '-' + phase + '.json')['cases']
        ok(c + '-' + phase, len(rows) == 42 and all(x['pass'] for x in rows))
    rows = read(c + '-tab-readonly.json')
    ok(c + '-readonly-api', len(rows) == 5 and all(x['pass'] for x in rows))
    db = read(c + '-tab-db-before.json')
    reg = read(c + '-fixtures.json')
    owner = next(x for x in reg['snapshots'] if x['scenario'] == 'owner')['id']
    expected = {x['id'] for x in db['notes'] if x['entityid'] == owner and not x['removed']}
    pages = [ui(c, p) for p in ['comments-main', 'comments-page2', 'comments-page3']]
    ids = [x['id'] for p in pages for x in p['notes']]
    ok(c + '-comment-pages-sql', len(ids) == 26 and len(set(ids)) == 26 and set(ids) == expected)
    pages = [ui(c, p) for p in ['events-main', 'events-page2', 'events-page3']]
    ids = [x['id'] for p in pages for x in p['rows']]
    expected = {x['id'] for x in db['events'] if x['resourceid'] == owner}
    ok(c + '-event-pages-sql', len(ids) == 30 and len(set(ids)) == 30 and set(ids) == expected)
    for tab in ['zones', 'events', 'comments']:
        field = 'notes' if tab == 'comments' else 'rows'
        main = ui(c, tab + '-main')
        for failure in ['503', 'incomplete']:
            failed = ui(c, tab + '-' + failure)
            projection = lambda rows: [(x['id'], x.get('cells', x.get('text'))) for x in rows]
            ok(c + '-' + tab + '-' + failure, projection(failed[field]) == projection(main[field]) and '재시도' in failed['tabText'] and failed['affectedResponses'] == 1)
        denied = ui(c, tab + '-403')
        ok(c + '-' + tab + '-403-cleared', not denied[field] and '재시도' in denied['tabText'])
        late = ui(c, tab + '-late-ignored')
        ok(c + '-' + tab + '-late-scope', owner not in late['url'] and bool(late[field]))
        for failure in ['503', '403', 'incomplete']:
            final = ui(c, 'final-regression-' + tab + '-' + failure)
            recovered = ui(c, 'final-regression-' + tab + '-' + failure + '-recovered')
            recovered_dom = (browser / (c + '-final-regression-' + tab + '-' + failure + '-recovered.txt')).read_text()
            ok(c + '-final-' + tab + '-' + failure, final['affectedResponses'] == 1 and '재시도' in final['tabText'] and bool(recovered[field]) and '- button "재시도"' not in recovered_dom)
    clamp = ui(c, 'comments-last-page-clamped')
    ok(c + '-last-page-clamped', len(clamp['notes']) == 10 and clamp['pagination'][0]['active'] == '2' and '20' in clamp['tabText'])
    failed = ui(c, 'comment-submit-failed')
    ok(c + '-submit-failure-draft', failed['affectedResponses'] == 1 and failed['fields'][0]['value'].startswith(reg['prefix'] + '-ui-admin') and '변경에 실패' in failed['tabText'])
    late = ui(c, 'comment-late-completion')
    ok(c + '-mutation-late-new-draft', len(late['notes']) == 2 and late['fields'][0]['value'] == reg['prefix'] + '-new-scope-draft')
    ok(c + '-readonly-controls', not ui(c, 'readonly-comments')['fields'] and '보내기' not in ui(c, 'readonly-comments')['tabText'])
    empty = ui(c, 'zones-filter-empty')
    ok(c + '-local-filter-count', not empty['rows'] and empty['pagination'] == ui(c, 'zones-main')['pagination'])
    page2 = ui(c, 'zones-synthetic-page2')
    ok(c + '-synthetic-copy-page', page2['pagination'][0]['active'] == '2' and '11개' in page2['pagination'][0]['text'] and not any(x['selected'] for x in page2['rows']))
    ok(c + '-zone-delete-jobs-sql', len(read(c + '-ui-zone-jobs-db.json')) == 3)
    ok(c + '-ui-comment-deleted-sql', read(c + '-ui-deleted-db.json')[0]['removed'] is not None)
    ok(c + '-keyboard-create-delete-sql', read(c + '-ui-keyboard-db.json')['pass'])
    final = read(c + '-final-state.json')
    ok(c + '-normal-cleanup', not any(final['database'].values()) and not final['physical']['remaining'] and not final['physical']['rbdTrash'] and final['runtime']['clientHttp'] == 200)
    ok(c + '-operational-preservation', read(c + '-preservation.json')['status'] == 'PASS')
    deploy = read(c + '-ui-deploy-final.json')
    ok(c + '-deployment', deploy['files'] == 922 and deploy['verified'] and deploy['webInf'] and deploy['clientHttp'] == 200 and deploy['mold'] == 'active' and all(deploy['markers'].values()))

matrix = read('browser/final-layout-matrix.json')
ok('layout-36-unique', len(matrix) == 36 and len({(x['cluster'], x['tab'], x['theme'], x['width']) for x in matrix}) == 36)
for row in matrix:
    layout = row['layout']
    data = (browser / row['screenshot']).read_bytes()
    width, height = struct.unpack('>II', data[16:24])
    ok('layout-' + row['screenshot'], row['pass'] and width == row['width'] and height == 1000 and not layout['overlaps'] and layout['documentWidth'] <= width)
    if row['theme'] == 'dark':
        colors = [list(map(float, re.findall(r'[\d.]+', x['color'])[:3])) for x in layout['text']]
        ok('dark-text-' + row['screenshot'], bool(colors) and all(sum(rgb) > 100 for rgb in colors))
log = logtext('ui-tests-final.log')
ok('focused-tests', re.search(r'Test Suites:\s+11 passed, 11 total', log) and re.search(r'Tests:\s+149 passed, 149 total', log))
ok('production-build', 'Build complete.' in logtext('ui-build-final.log'))
ok('lint', 'No lint errors found!' in logtext('ui-lint.log'))
(root / 'evidence-verification.json').write_text(json.dumps(dict(status='PASS', count=len(checks), checks=checks), ensure_ascii=False, indent=2))
print(len(checks), 'independent evidence checks PASS')
