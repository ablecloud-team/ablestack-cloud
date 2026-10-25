import json, re, sys
from remote import root, sql
c=sys.argv[1]
rows=[]
for file in sorted((root/'browser').glob(c+'-*-network.json')):
 for item in json.loads(file.read_text()).get('requests',[]):
  if item.get('method')!='POST' or item.get('command') not in ['createSnapshot','createVolume','createTemplate','revertSnapshot','extractSnapshot','archiveSnapshot','copySnapshot','deleteSnapshot']: continue
  args=item.get('args',{})
  if str(args.get('dryrun','')).lower()=='true':continue
  response=item.get('response') or {}; data=next(iter(response.values()),{})
  jobid=data.get('jobid') or args.get('customjobid')
  rows.append({'file':file.name,'command':item['command'],'status':item.get('status'),'jobid':jobid,'customjobid':args.get('customjobid'),'definiteRejected':item.get('status')==431,'requestId':item.get('requestId')})
uuids=sorted({x['jobid'] for x in rows if x['jobid'] and re.fullmatch('[a-f0-9-]{36}',x['jobid'])})
assert uuids
expected_failed={x['jobid'] for x in rows if x['command']=='copySnapshot' and x['file']==c+'-copy-submit-retry-network.json'}
assert len(expected_failed)==1
jobs=sql(c,"SELECT JSON_OBJECT('id',id,'uuid',uuid,'command',job_cmd,'status',job_status,'resultCode',job_result_code,'created',created) FROM async_job WHERE uuid IN ("+','.join("'"+x+"'" for x in uuids)+");")
for row in rows:
 matches=[x for x in jobs if x['uuid']==row['jobid']]
 if row['definiteRejected']: assert not matches, row
 elif row['jobid']: assert len(matches)==1 and matches[0]['status']==(2 if row['jobid'] in expected_failed else 1), row
out={'requests':rows,'jobs':jobs,'uniqueJobs':len(jobs),'successfulJobs':sum(x['status']==1 for x in jobs),'expectedServerFailures':sorted(expected_failed),'status':'PASS'}
(root/(c+'-ui-job-oracle.json')).write_text(json.dumps(out,indent=2,ensure_ascii=False));print(c,len(rows),'UI mutation requests,',len(jobs),'distinct DB jobs including the expected server failure')
