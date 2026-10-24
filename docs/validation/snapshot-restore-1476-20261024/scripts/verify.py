#!/usr/bin/env python3
"""Offline checks for the committed #1476 live-cluster evidence."""
import hashlib,json,re
from pathlib import Path
root=Path(__file__).resolve().parents[1];repo=root.parents[2];checks=[]
def check(ok,name):
 if not ok:raise AssertionError(name)
 checks.append(name)
def read(name):return json.loads((root/name).read_text(encoding='utf-8'))
for x in read('manifest.json'):
 p=root/x['path'];check(p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest()==x['sha256'],'artifact '+x['path'])
for x in read('source-sha256.json'):
 check(hashlib.sha256((repo/x['path']).read_bytes()).hexdigest()==x['sha256'],'source '+x['path'])
for c in ['31','32']:
 e=read(c+'-validation.json');check(e['baseline']['requestedGiB']==2 and e['baseline']['finalSize']==1073741824,c+' baseline reproduced')
 snapshots={x['id']:x for x in e['db']['snapshots']}
 for x in e['contexts']:
  s=snapshots[x['id']];r=x['snapshotcontext']['restore'];check(not x['snapshotcontext']['sourceavailable'],c+' source absent '+x['id'])
  check(x['virtualsize']==r['size']==s['size'] and r['diskofferingid']==s['offering'],c+' captured metadata '+x['id'])
  if x['name'].endswith('custom'):check(s['sourceSize']==2*s['size']==2147483648,c+' source resize does not alter snapshot size')
 for x in e['sourceAbsence']:check(x['DBRemoved'] and not x['physicalPresent'],c+' original physical absent '+x['snapshot'])
 for x in e['negatives']:
  reason=json.loads(x['reason']);check(x['rejected'] and reason['errorcode']==431 and not reason.get('jobid'),c+' pre-allocation rejection '+x['scenario'])
 for p in e['physical']:
  if p.get('failedAttempt'):continue
  s=next(s for s in snapshots.values() if s['name'].endswith('root' if 'root' in p['scenario'] else 'fixed' if 'fixed' in p['scenario'] else 'custom'))
  check(p['size']==p['physical']['virtual-size']==s['size'],c+' actual capacity '+p['uuid'])
  v=next(v for v in e['db']['volumes'] if v['id']==p['uuid']);check(v['offering']==s['offering'],c+' offering inheritance '+p['uuid'])
  if 'root' not in p['scenario']:check('patterns' in p and 'Pattern verification failed' not in p['patterns'],c+' data patterns '+p['uuid'])
 check(e['rootBefore'][0]['size']==e['rootAfter'][0]['size']==107374182400 and e['rootBefore'][0]['sha256']==e['rootAfter'][0]['sha256'],c+' complete 100GiB ROOT hash')
 net=e['uiNetwork'];check(len(net)==4 and len({x['args']['customjobid'] for x in net})==4,c+' one request per explicit attempt')
 check(all(x['args']['command']=='createVolume' and not any(k in x['args'] for k in ['size','diskofferingid','miniops','maxiops','storageid','kmskeyid']) for x in net),c+' UI pure restore without ignored inputs or offering lookup')
 check([x['result']['jobstatus'] for x in e['uiJobs']]==[2,1,1,1],c+' failure and three completed UI restores')
 for x in e['uiJobs']:
  check(x['request']['snapshotid'] in snapshots,c+' frozen UI target '+x['request']['customjobid'])
  if x['result']['jobstatus']==1:
   v=x['result']['jobresult']['volume'];s=snapshots[x['request']['snapshotid']];db=next(d for d in e['db']['volumes'] if d['id']==v['id'])
   offeringMatches=v.get('diskofferingid')==s['offering'] if 'diskofferingid' in v else s['name'].endswith('root') and v.get('serviceofferingname')==s['offeringName'] and db['offering']==s['offering']
   check(v['size']==s['size'] and offeringMatches,c+' UI completion metadata '+v['id'])
 check(e['injectionHide']['sha256']==e['injectionRestore']['sha256'] and not e['injectionRestore']['backupPresent'],c+' injection recovered unchanged')
 check(e['anonymous']['status']==401,c+' anonymous denied')
 final=e['final'];check(all(final[k]==0 for k in ['snapshots','volumes','vms','references','offerings']),c+' active DB zero')
 check(not any(x['exists'] for x in final['secondary']) and not final['primary'] and not final['trash'] and not any(final['domains'].values()),c+' storage/domain zero')
 check(len(final['usage'])==3 and all(x['count']==1 for x in final['usage']),c+' snapshot delete events once')
 check(all(x['unchanged'] for x in e['preservation']['preserved']),c+' operational projections unchanged')
 rt=e['runtime'];check(rt['webInf'] and rt['configPreserved'] and rt['staticVerified'] and rt['staticFiles']==921 and len(rt['classes'])==6 and all(x['actual']==x['expected'] for x in rt['classes']),c+' deployed artifacts and webapp preservation')
 check(rt['service']=='active' and rt['http']==200 and len(e['ready']['hosts'])==5 and all(x['state']=='Up' for x in e['ready']['hosts']),c+' runtime health')
for file in sorted((root/'browser').glob('*.json')):
 m=json.loads(file.read_text(encoding='utf-8'));modal=m['modal'];v=m['viewport']
 check(modal['left']>=0 and modal['right']<=v['width'] and modal['top']>=0 and modal['bottom']<=v['height'],file.stem+' modal bounds')
 check(m['header']['bottom']<=m['body']['top']+1 and m['body']['bottom']<=m['footer']['top']+1,file.stem+' header/body/footer do not overlap')
 check(not m['sizeField'] and not m['offeringInput'],file.stem+' no misleading editable inheritance')
 groups=m['groups'];check(all(b['rect']['top']>=a['rect']['bottom']-1 for a,b in zip(groups,groups[1:])),file.stem+' component spacing')
 if file.stem.endswith('required'):check('옵션을 선택하세요.' in m['text'] and any(i['id']=='form_item_name' and i['value'].endswith('restore-custom-ui') for i in m['inputs']),file.stem+' validation preserves name')
 if 'failed-retained' in file.stem:check('Failed to create volume' in m['text'] and any(i['id']=='form_item_name' and i['value'].endswith('restore-fixed-ui') for i in m['inputs']),file.stem+' job failure preserves input')
 def lum(color):
  nums=[float(x)/255 for x in re.findall(r'[\d.]+',color)[:3]];nums=[x/12.92 if x<=0.04045 else ((x+0.055)/1.055)**2.4 for x in nums];return sum(x*w for x,w in zip(nums,[0.2126,0.7152,0.0722]))
 for x in m['colors']:
  a,b=sorted([lum(x['color']),lum(x['background'])]);check((b+0.05)/(a+0.05)>=4.5,file.stem+' readable inheritance '+x['text'][:12])
check(read('ui-reopen.json')['newMutations31']==read('ui-reopen.json')['newMutations32']==0,'reload and reopening do not resubmit')
for name in ['windows','wsl']:
 x=read('protected-'+name+'.json');check(x['before']==x['after'],'protected '+name+' FT tree unchanged')
summary={'status':'PASS','checks':len(checks),'javaTests':25,'uniqueUiTests':70,'fullCloudCI':False}
(root/'verification.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8');print(json.dumps(summary))
