from pathlib import Path
import importlib.util,json,os,sys,copy,uuid,subprocess,time,tracemalloc
stage=Path('/var/tmp/epic1445-design')
def module(path,name):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
runtime=module('/usr/local/lib/ablestack-storage/configuration_transaction.py','installed_transaction')
sketch=module(stage/'resource_sketch.py','resource_sketch')
actual=json.loads(Path('/etc/ablestack-storage/desired-state/nfs-export-apply.json').read_text())
payload={'instanceUuid':actual['instanceUuid'],'operationid':str(uuid.uuid4()),'vmUuid':Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower(),'baseRevision':0,'targetRevision':1,'before':{'nfs export apply':actual},'candidate':{'nfs export apply':actual}}
private=stage/'measured-snapshot';private.mkdir(mode=0o700,exist_ok=True)
adapter=runtime.RuntimeAdapter(private)
adapter.preflight(payload)
tracemalloc.start();start=time.monotonic()
adapter.snapshot(payload)
_,peak=tracemalloc.get_traced_memory();tracemalloc.stop()
snapshot_info={'bytes':adapter.snapshot_path.stat().st_size,'pythonAllocationPeakBytes':peak,'elapsedSeconds':time.monotonic()-start}
mem=dict(line.split(':',1) for line in Path('/proc/meminfo').read_text().splitlines())
filesystems=[]
for path in ['/var/lib/ablestack-storage/configuration-operations','/etc/ablestack-storage','/tmp']:
 s=os.statvfs(path);device=Path(path).stat().st_dev
 if any(v['device']==device for v in filesystems):continue
 filesystems.append({'device':device,'path':path,'freeBytes':s.f_bavail*s.f_frsize,'freeInodes':s.f_favail,'readOnly':bool(s.f_flag&os.ST_RDONLY)})
connections=sum(1 for line in subprocess.check_output(['ss','-H','-tn','state','established'],text=True).splitlines() if len(line.split())>3 and line.split()[2].endswith(':2049'))
sample={'observedAt':time.time(),'memoryAvailable':int(mem['MemAvailable'].split()[0])*1024,'load1':os.getloadavg()[0],'cpuCount':os.cpu_count(),'filesystems':filesystems,'affectedConnections':connections,'busyVolumes':[]}
intent={'revision':2,'publicIntent':{'api':'updateStorageNfsExport','readonly':True},'impact':'TEMPORARY_INTERRUPTION','affectedResources':['r1444n'],'requestBytes':len(json.dumps(payload).encode()),'snapshotBytes':snapshot_info['bytes']}
cases=sketch.matrix(sample,intent)
blocked_checks=[]
for name,patch,expected in [('memory',{'memoryAvailable':64*sketch.MIB},'MEMORY_LOW'),('disk',{'filesystems':[{'freeBytes':1,'freeInodes':128,'readOnly':False}]},'SPACE_LOW'),('busy-volume',{'busyVolumes':['owned-volume']},'VOLUME_PREPARATION_ACTIVE')]:
 counts={'snapshot':0,'apply':0,'restore':0}
 bad={**sample,**patch}
 class Gated(runtime.RuntimeAdapter):
  def preflight(self,value):
   super().preflight(value)
   outcome=sketch.evaluate(bad,intent)
   if outcome['blockers']:raise runtime.Rejected(outcome['blockers'][0])
  def snapshot(self,value):counts['snapshot']+=1;raise AssertionError('snapshot must not start')
  def apply(self,value):counts['apply']+=1;raise AssertionError('apply must not start')
  def restore(self):counts['restore']+=1;raise AssertionError('restore must not start')
 local=copy.deepcopy(payload);local['operationid']=str(uuid.uuid4())
 result=runtime.Transaction(stage/('gate-'+name),Gated).execute('apply',local)
 assert result['state']=='ROLLED_BACK' and result['rollback']['state']=='NOT_NEEDED'
 assert result['originalfailure']['code']==expected and counts=={'snapshot':0,'apply':0,'restore':0}
 blocked_checks.append({'case':name,'state':result['state'],'code':expected,'calls':counts})
# Existing Transaction/RuntimeAdapter process control, but a harmless Python child replaces storagectl.
fake_program="""from pathlib import Path
import json,time
s=Path('/var/tmp/epic1445-design')
p=s/'safe-child-count';p.write_text(str(int(p.read_text())+1 if p.exists() else 1))
for seq in range(16):
 t=s/'heartbeat-prototype.tmp';t.write_text(json.dumps({'sequence':seq,'heartbeatAt':time.time()}));t.replace(s/'heartbeat-prototype.json');time.sleep(.05)
print(json.dumps({'success':True,'runtimeReady':True}))
"""
source=Path('/usr/local/lib/ablestack-storage/configuration_transaction.py').read_text()
old='argv = ["/usr/local/bin/ablestack-storagectl"] + operation.split() + [f"/proc/self/fd/{descriptor}"]'
assert source.count(old)==1
source=source.replace(old,'argv = [sys.executable, "-c", '+repr(fake_program)+']')
(stage/'probe_copy.py').write_text(source)
safe_payload={**payload,'operationid':str(uuid.uuid4()),'before':{},'candidate':{'nfs export apply':{'enabled':False}}}
(stage/'safe-payload.json').write_text(json.dumps(safe_payload))
parent_code="""from pathlib import Path
import json
import probe_copy as c
s=Path('/var/tmp/epic1445-design')
class Safe(c.RuntimeAdapter):
 def snapshot(self,p):c.atomic_json(self.snapshot_path,{'testOnly':True})
 def restore(self):raise AssertionError('unexpected rollback')
print(json.dumps(c.Transaction(s/'control',Safe).execute('apply',json.loads((s/'safe-payload.json').read_text()))))
"""
(stage/'safe-parent.py').write_text(parent_code)
assert not(stage/'safe-child-count').exists()
parent=subprocess.Popen([sys.executable,str(stage/'safe-parent.py')],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
transport_timeout=False
try:parent.wait(timeout=.15)
except subprocess.TimeoutExpired:transport_timeout=True
control=module(stage/'probe_copy.py','copy_for_observation')
tx=control.Transaction(stage/'control')
running=None
for _ in range(60):
 running=tx.show(safe_payload)
 if running.get('workerActive'):break
 time.sleep(.02)
assert running.get('workerActive') and running['phase']=='APPLYING'
_,_,status_path=tx.paths(safe_payload);before_status=status_path.read_bytes()
for _ in range(10):assert tx.show(safe_payload).get('workerActive')
assert status_path.read_bytes()==before_status
other={**safe_payload,'operationid':str(uuid.uuid4())}
try:tx.execute('apply',other);raise AssertionError('concurrent writer accepted')
except BlockingIOError:pass
out,err=parent.communicate(timeout=5)
assert parent.returncode==0,(out,err)
finished=json.loads(out);assert finished['state']=='VERIFIED'
assert tx.execute('resume',safe_payload)['state']=='VERIFIED'
assert tx.execute('commit',safe_payload)['state']=='COMMITTED'
assert (stage/'safe-child-count').read_text()=='1'
result={'actualSnapshotMeasurement':snapshot_info,'realSample':sample,'resourceMatrix':cases,'gateBeforeMutation':blocked_checks,'existingControlProof':{'callerWaitExpiredWithoutKill':transport_timeout,'running':running,'getterMutations':0,'concurrentWriterBlocked':True,'applyCount':1,'resumeDidNotReplay':True,'finalState':'COMMITTED','currentStatusHasHeartbeat':('heartbeatAt' in running),'prototypeHeartbeat':json.loads((stage/'heartbeat-prototype.json').read_text())},'actualServiceApplyCount':0,'productFilesModified':False}
(stage/'design-validation.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result))
