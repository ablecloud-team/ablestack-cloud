#!/usr/bin/env python3
"""Offline integrity and cross-layer checks for issue #1480 evidence."""
import hashlib,json,re
from pathlib import Path
root=Path(__file__).resolve().parents[1];repo=root.parents[2];checks=[]
def check(ok,name):
 if not ok:raise AssertionError(name)
 checks.append(name)
def read(name):return json.loads((root/name).read_text(encoding='utf-8'))
def browser(c,p):return read('browser/'+c+'-'+p+'.json')
for x in read('manifest.json'):
 p=root/x['path'];check(p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest()==x['sha256'],'artifact '+x['path'])
for x in read('source-sha256.json'):
 check(hashlib.sha256((repo/x['path']).read_bytes()).hexdigest()==x['sha256'],'source '+x['path'])
for c in ['31','32']:
 e=read(c+'-validation.json');q=e['qualification'];n=11 if c=='31' else 12;main=q['main']['snapshot'];ids=[x['copy']['id'] for x in main]
 check(q['status']=='PASS' and q['main']['count']==len(main)==len(set(ids))==n,c+' main unique reference count')
 for size in [1,2,10,20]:
  pages=[x for x in q['pages'] if x['size']==size];check(all(x['count']==n and len(x['ids'])<=size for x in pages),c+' bounded pages '+str(size))
  check([i for p in pages for i in p['ids']]==ids,c+' page union ordered '+str(size))
 check(len(q['unique'])==len(set(x['id'] for x in q['unique']))==2 and all('copy' not in x for x in q['unique']),c+' logical unique unit unchanged')
 other=q['other']['snapshot'];check(len(other)==q['other']['count']==len(set(x['copy']['id'] for x in other))==2,c+' two missing-reference Zones preserved')
 check(all(x['copy']['availability']=='Unknown' and not x['copy']['datastoreknown'] and x['copy'].get('virtualsize') is None and x['copy'].get('downloadpercent') is None for x in other),c+' unknown facts not fabricated')
 check(all(x['reason']['errorcode']==431 for x in q['rejections']) and len(q['rejections'])==4,c+' conflicting filter rejection')
 f={x['kind']:x['data'] for x in q['filters'] if x['kind']!='imagestoreid'}
 check((f['primary'].get('count') or 0)==(0 if c=='31' else 1) and (f['storageid'].get('count') or 0)==(f['primary'].get('count') or 0),c+' Primary pool filter')
 check(f['secondary']['count']==11 and f['zone2']['count']==2 and f['tagged']['count']==len(f['tagged']['snapshot'])==n,c+' secondary Zone tag counts')
 check(len([x for x in q['filters'] if x['kind']=='imagestoreid' and x['data']['count']==1])==10,c+' per store filter')
 check((q['projectScope'].get('count') or 0)==0,c+' actual project API scope')
 check(any(x['copy'].get('downloadpercent')==37 and x['copy']['availability']=='NotReady' for x in main),c+' partial progress state')
 check(any('controlled copy failure' in (x['copy'].get('error') or '') and x['copy']['availability']=='NotReady' for x in main),c+' failed copy error')
 check(len(e['physicalNormal'])==(2 if c=='31' else 4),c+' product physical count')
 check(len(e['physicalControlled'])==(10 if c=='31' else 12),c+' controlled Ready physical count distinct from product execution')
 for p in e['physicalNormal']+e['physicalControlled']:
  check(p['verified'] and p['info']['virtual-size']==1073741824 and 'Pattern verification failed' not in p['patterns'],c+' physical capacity and data '+str(p['reference']))
 check('not product copy execution' in e['controlled']['fixtureKind'],c+' controlled fixture provenance')
 check(len(e['toggleRemove'])==len(e['toggleRestore'])==3 and [x['sha256'] for x in e['toggleRemove']]==[x['sha256'] for x in e['toggleRestore']],c+' remove restore physical hashes')
 check(all(not x['present'] and x['hidden'] for x in e['toggleRemove']) and all(x['present'] and not x['hidden'] for x in e['toggleRestore']),c+' scoped physical removal and restore')
 p1=browser(c,'page1');p2=browser(c,'page2');allp=browser(c,'all-copies-dark')
 check(set(x['key'] for x in p1['copyRows'])==set(ids[:10]) and set(x['key'] for x in p2['copyRows'])==set(ids[10:]),c+' UI page members equal API')
 for pageMeta,apiRows in [(p1,main[:10]),(p2,main[10:])]:
  for zone in set(x.get('zoneid') for x in apiRows):
   expected=[x['copy']['id'] for x in apiRows if x.get('zoneid')==zone];actual=[x['key'] for x in pageMeta['copyRows'] if x['key'] in expected];check(actual==expected,c+' UI order within Zone '+str(zone))
 check(set(x['key'] for x in allp['copyRows'])==set(ids) and len(allp['copyRows'])==len(ids),c+' UI larger page all references')
 check(all(x['href'].startswith(('#/zone/','#/storagepool/','#/imagestore/')) for x in allp['links']),c+' separate navigation routes')
 check(any(x['href'].startswith('#/storagepool/') for x in allp['links'])==(c=='32'),c+' Primary store navigation')
 check('37%' in allp['root'] and 'controlled copy failure' in allp['root'],c+' visible progress and error')
 stale=browser(c,'failed-retained');denied=browser(c,'denied-cleared');retry=browser(c,'retry-restored')
 check(len(stale['copyRows'])==n and '마지막 정상' in stale['root'],c+' 503 retains current page')
 check(not denied['copyRows'] and '권한' in denied['root'] and '0개' in denied['pagination'],c+' 403 clears rows and count')
 check(len(retry['copyRows'])==n and '마지막 정상' not in retry['root'],c+' real retry recovers')
 late=browser(c,'late-other-unknown');check(len(late['copyRows'])==2 and all('확인 불가' in x['text'] and '0 GiB' not in x['text'] for x in late['copyRows']) and not any('STORE-' in x['text'] for x in late['copyRows']),c+' late response cannot overwrite new unknown rows')
 check(not browser(c,'project-scope')['copyRows'],c+' actual project change drops old rows')
 clamp=browser(c,'clamped-after-remove');check(len(clamp['copyRows'])==n-3 and '\n2\n' not in clamp['pagination'],c+' removed last page clamps')
 check(len(browser(c,'restored-after-add')['copyRows'])==10 and str(n)+'개' in browser(c,'restored-after-add')['pagination'],c+' restored count with page size10')
 one=browser(c,'single-after-bulk')['dialog']['text'];check(e['fixtures']['zone']['id'] in one and e['fixtures']['volumes'][0]['zoneid'] not in one,c+' bulk cancel then correct single Zone')
 for phase in ['dialog-dark','dialog-light','dialog-compact-dark','dialog-compact-light']:
  b=browser(c,phase);d=b['dialog'];v=b['viewport']
  check('mold-dialog-standard' in d['className'] and 0<=d['x'] and d['x']+d['width']<=v['width']+1 and 0<=d['y'] and d['y']+d['height']<=v['height']+1,c+' '+phase+' dialog in viewport')
  check(d['header']['y']+d['header']['height']<=d['body']['y']+1 and d['body']['y']+d['body']['height']<=d['footer']['y']+1,c+' '+phase+' sections do not overlap')
  check(all(bf['y']-(af['y']+af['height'])>=23 for af,bf in zip(d['fields'],d['fields'][1:])),c+' '+phase+' component gap')
  check('SNAPC1480' not in d['text'] and '취소' in d['footer']['text'] and '확인' in d['footer']['text'],c+' '+phase+' compact body and common footer')
  check(len(d['fields'])==(3 if 'compact' in phase else 2),c+' '+phase+' dynamic field coverage')
  def rgb(s):
   a=[float(x) for x in re.findall(r'[\d.]+',s)];return a[:3],a[3] if len(a)>3 else 1
  def lum(a):
   a=[x/255 for x in a];a=[x/12.92 if x<=.04045 else ((x+.055)/1.055)**2.4 for x in a];return sum(x*w for x,w in zip(a,[.2126,.7152,.0722]))
  for color in b['colors']:
   fg,alpha=rgb(color['color']);bg,_=rgb(color['background']);fg=[x*alpha+y*(1-alpha) for x,y in zip(fg,bg)];lo,hi=sorted([lum(fg),lum(bg)]);ratio=(hi+.05)/(lo+.05)
   check(ratio>=4.5,c+' '+phase+' text contrast '+color['text'])
 arch=e['archive'];check(arch['before']['count']==(1 if c=='31' else 2) and arch['after']['count']==1 and arch['after']['snapshot'][0]['copy']['role']=='Image',c+' product archive count')
 check(e['archivePhysical']['status']=='PASS' and e['archivePhysical']['secondary']['exists'],c+' retained secondary real hash')
 check(len(browser(c,'normal-before-archive')['copyRows'])==(1 if c=='31' else 2) and len(browser(c,'normal-after-archive')['copyRows'])==1,c+' normal UI matches actual archive')
 cc=e['controlledCleanup'];check(cc['stores']==cc['references']==10 and cc['originalDisplayAndSizeRestored'] and not cc['physical']['directoryExists'],c+' controlled metadata and files removed')
 final=e['final'];check(all(final[k]==0 for k in ['snapshots','volumes','references','stores','zones','projects','zoneReferences']) and not final['primary'] and not final['trash'] and not any(x['exists'] for x in final['secondary']),c+' active DB and physical residual zero')
 check(len(final['usage'])==2 and all(x['count']==1 for x in final['usage']),c+' one delete usage event per snapshot')
 check(e['quota']['secondaryMatches'] and e['quota']['snapshotMatches'],c+' resource counters match DAO calculation')
 check(e['preservation']['status']=='PASS' and all(x['unchanged'] for x in e['preservation']['preserved']) and all(x['count']==0 for x in e['preservation']['fixtureActive']),c+' operational inventory preserved and API fixtures zero')
 check('전체 0 개 항목' in browser(c,'cleanup-list')['listText'],c+' UI fixture search empty')
 check(all(e['view'][k] for k in ['atomicReplace','definerPreserved','securityPreserved','otherColumnsUnchanged']),c+' SQL view change scoped')
 rt=e['runtime'];check(rt['staticVerified'] and rt['staticFiles']==921 and rt['webInf'] and rt['configPreserved'] and len(rt['classes'])==6 and all(x['actual']==x['expected'] for x in rt['classes']),c+' final runtime artifact hashes')
 check(all(x['otherEntriesUnchanged'] for x in e['deployment']),c+' other JAR entries preserved')
 check(rt['service']=='active' and rt['http']==200 and len(e['ready']['hosts'])==5 and all(x['state']=='Up' for x in e['ready']['hosts']),c+' runtime health')
u=read('browser/ui-summary.json');check(all(x['paused']==1 and x['rows']==2 and x['lateMainAbsent'] for x in u['lateResponses']), 'actual delayed XHR tests')
check(u['newCopyDeleteSubmissions']==0 and u['themeRestored']=='dark' and u['interceptionCleared'] and u['viewportRestored'] and u['ownTabsClosed'],'browser safety and restoration')
for name in ['windows','wsl']:
 p=read('protected-'+name+'.json');check(p['before']==p['after'],'protected FT '+name+' unchanged')
for n,pattern in [('server-tests.log','Tests run: 33, Failures: 0, Errors: 0'),('ui-final-tests.log','Tests:       69 passed'),('ui-final-lint.log','No lint errors found!'),('ui-release-build.log','Build complete')]:
 check(pattern in (root/n).read_text(encoding='utf-8'),n+' successful result')
out={'status':'PASS','checks':len(checks),'javaTests':33,'uiSuites':5,'uiTests':69,'fullCloudCI':False}
(root/'verification.json').write_bytes((json.dumps(out,indent=2)+'\n').encode());print(json.dumps(out))
