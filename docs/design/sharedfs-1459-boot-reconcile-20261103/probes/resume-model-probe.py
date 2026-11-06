#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Pure design contracts injected on actual SystemVM, not a product worker."""
from pathlib import Path
import json,hashlib,tempfile,math,fcntl,os
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='2e65bb57-9f05-39ef-bd89-08daadd33364'
def budget(probes,listeners):
 return min(900,max(90,45+20*listeners+math.ceil(probes/4)*3))
def reusable(row,boot,desired,identity):
 return row['bootId']==boot and row['desiredHash']==desired and row['runtimeIdentity']==identity
checks=[]
matrix=[{'exports':n,'mode':mode,'listeners':l,'probes':n*(2 if mode=='V3V4_DUAL' else l),'budgetSeconds':budget(n*(2 if mode=='V3V4_DUAL' else l),l)} for n in (1,10,100) for mode,l in [('V4_ONLY',2),('V3V4_DUAL',1)]]
assert matrix[-1]['budgetSeconds']==215 and matrix[-2]['budgetSeconds']==235
checks.append({'name':'bounded workload budget is derived from actual probe work, not fixed per service','passed':True,'matrix':matrix})
with tempfile.TemporaryDirectory(prefix='epic1459-design-model-',dir='/var/tmp') as temp:
 p=Path(temp);state={'bootId':'boot-a','desiredHash':'desired-a','runtimeIdentity':'managed-pid-start-config-fs-a','protocols':{'NFS':'VERIFYING','SMB':'READY','ISCSI':'READY','NVMEOF':'READY'},'applyCounts':{'NFS':1,'SMB':1,'ISCSI':1,'NVMEOF':1},'completed':['export-1:2049:v4'],'pending':['export-2:2050:v4'],'firstFailure':{'phase':'EXPORT_VISIBILITY','code':'NFS_PROBE_TIMEOUT','export':'export-2'},'attempts':1}
 (p/'status.json').write_text(json.dumps(state));again=json.loads((p/'status.json').read_text());assert reusable(again,'boot-a','desired-a','managed-pid-start-config-fs-a')
 # Retry only observational work; protocol apply counters remain untouched.
 initial=dict(again['applyCounts']);failure=dict(again['firstFailure']);again['completed']+=again['pending'];again['pending']=[];again['protocols']['NFS']='READY';again['attempts']+=1
 assert initial==again['applyCounts'] and again['firstFailure']==failure
 checks.append({'name':'journal reopen and lost response reuse same boot/desired identity; only pending observation resumes','passed':True,'applyCounts':again['applyCounts'],'firstFailurePreserved':True})
 assert not reusable(again,'boot-b','desired-a','managed-pid-start-config-fs-a')
 assert not reusable(again,'boot-a','desired-b','managed-pid-start-config-fs-a')
 assert not reusable(again,'boot-a','desired-a','foreign-process-or-remounted-fs')
 checks.append({'name':'new boot, desired mutation and runtime/FS identity change invalidate completed observations','passed':True})
 with (p/'lock').open('a') as first,(p/'lock').open('a') as second:
  fcntl.flock(first,fcntl.LOCK_EX|fcntl.LOCK_NB)
  rejected=False
  try:fcntl.flock(second,fcntl.LOCK_EX|fcntl.LOCK_NB)
  except BlockingIOError:rejected=True
  assert rejected
 checks.append({'name':'real nonblocking flock rejects duplicate worker admission','passed':True})
print(json.dumps({'success':True,'kind':'design model only; no product API or active configuration writes','checks':checks}))
