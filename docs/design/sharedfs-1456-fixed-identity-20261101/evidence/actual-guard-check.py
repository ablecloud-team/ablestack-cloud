# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
from pathlib import Path
import sys,json
sys.path.insert(0,str(Path('/opt/ablestack/storage-runtime/current').resolve()/'lib/ablestack-storage'))
import shared_directory_policy as h
checks=[]
for name,config,mode,expected in [('setgid',{'inheritOwner':False,'forceUser':'managed','forceGroup':'managed'},'2775','SMB_PARENT_IDENTITY_CONFLICT'),('inherit',{'inheritOwner':True,'forceUser':'managed','forceGroup':'managed'},'0770','SMB_PARENT_IDENTITY_CONFLICT'),('ordinary',{'inheritOwner':False,'forceUser':'','forceGroup':''},'2775',None)]:
 try:h.inheritance_conflict(config,mode);code=None
 except h.PolicyError as e:code=e.code
 assert code==expected,(name,code)
 checks.append({'name':name,'expected':expected,'actual':code,'passed':True})
print(json.dumps({'actualProtectedHelper':True,'checks':checks}))
