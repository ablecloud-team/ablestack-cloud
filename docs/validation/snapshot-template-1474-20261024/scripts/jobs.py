import json,sys
from remote import root,sql
for c in ['31','32']:
 q="SELECT JSON_OBJECT('uuid',j.uuid,'status',j.job_status,'cmd',j.job_cmd,'instancetype',j.instance_type,'instanceid',j.instance_id,'name',JSON_UNQUOTE(JSON_EXTRACT(j.job_cmd_info,'$.name')),'snapshotid',JSON_UNQUOTE(JSON_EXTRACT(j.job_cmd_info,'$.snapshotid')),'resultCode',j.job_result_code) FROM async_job j WHERE j.job_cmd_info LIKE '%SNAPT1474-"+c+"%' OR j.uuid='a72bf790-b8d8-462d-919d-2537b345cf95';"
 rows=sql(c,q);(root/(c+'-jobs-db.json')).write_text(json.dumps(rows,indent=2));print(c,'DB jobs',len(rows),'pending',sum(x['status']==0 for x in rows))
