import ast,copy,hashlib,json,os,pathlib,shutil,subprocess,tempfile,time
SOURCE=pathlib.Path('/usr/local/bin/ablestack-storagectl').read_text()
assert hashlib.sha256(SOURCE.encode()).hexdigest()=='db4c648179c23f12b746d550e6b7ccf49a9be063c81b06c5117b601a948d61cb'
def body(name):
 s=SOURCE.split(name+'() {',1)[1]
 return s.split("<<'PY'\n",1)[1].split('\nPY\n',1)[0]
def defs(name):
 tree=ast.parse(body(name)); ns={}
 exec(compile(ast.Module(body=[x for x in tree.body if isinstance(x,(ast.Import,ast.ImportFrom,ast.FunctionDef))],type_ignores=[]),'<deployed-definitions>','exec'),ns)
 return tree,ns
def digest(p):
 return {str(f.relative_to(p)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(p.rglob('*')) if f.is_file()}
def snapshot(p):
 return {str(f.relative_to(p)):(f.read_bytes(),f.stat().st_mode&0o777) for f in p.rglob('*') if f.is_file()}
def restore(p,s):
 for f in p.rglob('*'):
  if f.is_file() and str(f.relative_to(p)) not in s:f.unlink()
 for name,(data,mode) in s.items():
  f=p/name;f.parent.mkdir(parents=True,exist_ok=True);tmp=f.with_suffix('.restore');tmp.write_bytes(data);tmp.chmod(mode);os.replace(tmp,f)
def transaction(p,action,rollback_failure=False):
 old=snapshot(p);state={'operationId':'design-only-fixture','phase':'APPLYING','firstFailure':None,'rollback':None}
 try:action()
 except BaseException as ex:
  state['firstFailure']={'type':type(ex).__name__,'stage':'APPLYING','code':'INJECTED_FAILURE'}
  try:
   if rollback_failure:raise PermissionError('INJECTED_ROLLBACK_FAILURE')
   restore(p,old);assert snapshot(p)==old
   state['phase']='ROLLED_BACK';state['rollback']={'success':True}
  except BaseException as rb:
   state['phase']='RECOVERY_REQUIRED';state['rollback']={'success':False,'type':type(rb).__name__}
 return state
results=[]
def record(name,**kw):results.append(dict(case=name,**kw))
with tempfile.TemporaryDirectory(prefix='issue1444-design-') as td:
 root=pathlib.Path(td)
 # Actual deployed NFS writer removes old managed files before a later rendering error.
 _,ns=defs('apply_nfs_exports');n=root/'nfs';n.mkdir();old=n/'old.conf';old.write_text('fixture original NFS config');ns['ganesha_conf_dir']=str(n)
 def bad_nfs():ns['write_ganesha_configs']({('127.0.0.1','INVALID_PORT'):[{'protocolMode':'V4_ONLY'}]},2049)
 try:bad_nfs()
 except BaseException as ex:record('NFS-current-render-failure',oldFileLost=not old.exists(),failure=type(ex).__name__)
 assert not old.exists();old.write_text('fixture original NFS config');before=digest(n);st=transaction(n,bad_nfs);assert st['phase']=='ROLLED_BACK' and digest(n)==before
 record('NFS-design-compensation',**st)
 # Actual deployed SMB publication statements and real testparm on an isolated explicit config.
 tree,smbns=defs('apply_smb_shares');m=root/'smb';m.mkdir();conf=m/'smb.conf';conf.write_text('[global]\nworkgroup = ORIGINAL\n')
 chosen=[]
 for node in tree.body:
  text=ast.get_source_segment(body('apply_smb_shares'),node) or ''
  if isinstance(node,ast.With) and ('smb.conf' in text or 'smb-quotas.json' in text or 'smb-access.json' in text):chosen.append(node)
  if isinstance(node,ast.Expr) and text.startswith('run(["testparm"'):chosen.append(node)
 class Paths(ast.NodeTransformer):
  def visit_Constant(self,node):
   if node.value=='/etc/samba/smb.conf':return ast.copy_location(ast.Constant(str(conf)),node)
   return node
 isolated=ast.fix_missing_locations(Paths().visit(ast.Module(body=chosen,type_ignores=[])))
 def validate(args,**kwargs):
  assert args==['testparm','-s']
  return subprocess.run(['/usr/bin/testparm','-s',str(conf)],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 smbns.update(lines=['[global]','security = invalid_design_fixture'],state_dir=str(m),quota_state={},access_state={},run=validate)
 def bad_smb():exec(compile(isolated,'<deployed-smb-publication>','exec'),smbns)
 try:bad_smb()
 except subprocess.CalledProcessError as ex:record('SMB-current-validator-failure',oldFileOverwritten='invalid_design_fixture' in conf.read_text(),validatorExit=ex.returncode)
 assert 'invalid_design_fixture' in conf.read_text();conf.write_text('[global]\nworkgroup = ORIGINAL\n');before=digest(m);st=transaction(m,bad_smb);assert st['phase']=='ROLLED_BACK' and digest(m)==before
 record('SMB-design-compensation',**st)
 candidate=m/'candidate.conf';candidate.write_text('[global]\nsecurity = invalid_design_fixture\n');q=subprocess.run(['/usr/bin/testparm','-s',str(candidate)],capture_output=True);assert q.returncode!=0 and conf.read_text()=='[global]\nworkgroup = ORIGINAL\n'
 record('SMB-real-preflight-rejects-before-publish',validatorExit=q.returncode,oldFilePreserved=True);candidate.unlink()
 # Actual deployed iSCSI mutation branch with a modeled executor, no real targetcli/configfs.
 tree,ins=defs('apply_iscsi_targets');kernel={'target':'original target and ACL','backstore':'original backing reference'}
 outer=next(x for x in tree.body if isinstance(x,ast.If) and 'command_exists("targetcli")' in (ast.get_source_segment(body('apply_iscsi_targets'),x.test) or ''))
 def run_targetcli(*args):
  if args[:2]==('/iscsi','delete'):kernel.pop('target',None)
  if args[:2]==('/backstores/block','delete'):kernel.pop('backstore',None)
 def require_targetcli(*args):raise RuntimeError('INJECTED_CREATE_AFTER_DELETE')
 ins.update(enabled=True,targets=[{'uuid':'fixture','targetName':'iqn.design.fixture','lunOrNamespace':'0'}],listeners=[],skipped=0,applied=0,required_ports=set(),runtime_targets=[],command_exists=lambda x:True,cleanup_stale_managed_targets=lambda x:None,previous_acl_auth_map=lambda x:{},run_targetcli=run_targetcli,require_targetcli=require_targetcli)
 try:exec(compile(ast.Module(body=[outer],type_ignores=[]),'<deployed-iscsi-branch>','exec'),ins)
 except RuntimeError:pass
 assert not kernel
 record('ISCSI-current-create-after-delete-failure',previousTargetAndBackstoreLost=True,executor='modeled; real kernel untouched')
 previous={'target':'original target and ACL','backstore':'original backing reference'};kernel.update(copy.deepcopy(previous))
 first=None
 try:exec(compile(ast.Module(body=[outer],type_ignores=[]),'<deployed-iscsi-branch>','exec'),ins)
 except RuntimeError as ex:first=type(ex).__name__;kernel.clear();kernel.update(copy.deepcopy(previous))
 assert kernel==previous
 record('ISCSI-design-before-image-compensation',previousGraphRestored=True,firstFailure=first,executor='modeled; real kernel untouched')
 # Actual deployed configfs attribute helpers on regular files, not kernel configfs.
 _,vns=defs('apply_nvmeof_subsystems');v=root/'nvme';v.mkdir();attr=v/'dhchap_key';attr.write_text('fixture-original-value')
 def bad_nvme():
  vns['write_required_configfs'](str(attr),'fixture-new-value','fixture host authentication')
  vns['write_required_configfs'](str(v/'missing_ctrl_key'),'fixture','fixture controller authentication')
 try:bad_nvme()
 except SystemExit as ex:record('NVME-current-second-attribute-failure',firstAttributeChanged=attr.read_text()!='fixture-original-value',failure=type(ex).__name__)
 attr.write_text('fixture-original-value');before=digest(v);st=transaction(v,bad_nvme);assert st['phase']=='ROLLED_BACK' and digest(v)==before
 record('NVME-design-attribute-compensation',**st)
 st=transaction(v,bad_nvme,True);assert st['phase']=='RECOVERY_REQUIRED' and st['firstFailure']['type']=='SystemExit' and st['rollback']['type']=='PermissionError'
 record('design-dual-failure-retains-first',**st)
 journal=root/'read-only-status.json';journal.write_text(json.dumps(st));again=json.loads(journal.read_text());assert again==st
 record('design-diagnostic-read-after-reload',firstFailurePreserved=True,rollbackFailureSeparate=True)
 # Bounded request/revision sketch prevents any mutation before a conflict is resolved.
 calls=[];seen={}
 def request(key,digest_value,expected,current):
  if key in seen:
   if seen[key]['hash']!=digest_value:raise ValueError('REQUEST_ID_CONFLICT')
   return seen[key]['result']
  if expected!=current:raise ValueError('REVISION_CONFLICT')
  calls.append(key);seen[key]={'hash':digest_value,'result':'COMMITTED'};return 'COMMITTED'
 assert request('r1','hash1',1,1)==request('r1','hash1',1,1) and len(calls)==1
 for args in [('r1','hash2',1,1),('r2','hash1',0,1)]:
  try:request(*args);raise AssertionError('conflict missing')
  except ValueError:pass
 assert len(calls)==1
 record('design-idempotency-and-revision-fence',applyCount=1,changedRequestBlocked=True,staleRevisionBlocked=True)
 # No production apply/service/account/volume commands are executed by this probe.
 record('scope',newDiskCount=0,liveServiceReloadCount=0,liveConfigfsWrites=0,productionFileWrites=0,temporaryDirectoryRemovedOnExit=True)
print(json.dumps({'sourceSha256':hashlib.sha256(SOURCE.encode()).hexdigest(),'pythonVersion':__import__('sys').version,'designOnly':True,'results':results},indent=2))
