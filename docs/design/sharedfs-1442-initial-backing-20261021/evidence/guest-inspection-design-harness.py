# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
import ast,base64,contextlib,importlib.util,io,json,pathlib,posixpath,types,copy
root=pathlib.Path('/root/work/ablestack-cloud');out=pathlib.Path('/root/work/epic1438-preparation/20261021/issue1442-design')
source=(root/'systemvm/debian/usr/local/bin/ablestack-storagectl').read_text()
block=source.split("volume_attach_inspect() {",1)[1].split("<<'PY'\n",1)[1].split("\nPY\n",1)[0]
tree=ast.parse(block)
# Only replace device/process/file operations in this isolated design harness.
blocked={'os','subprocess','sys','shutil','tempfile'}
tree.body=[n for n in tree.body if not (isinstance(n,ast.Import) and any(a.name in blocked for a in n.names)) and not (isinstance(n,ast.ImportFrom) and n.module=='volume_identity') and not (isinstance(n,ast.Expr) and isinstance(n.value,ast.Call) and ast.unparse(n.value.func)=='sys.path.insert')]
for i,n in enumerate(tree.body):
 if isinstance(n,ast.FunctionDef) and n.name=='persist_backing_mount':
  tree.body[i]=ast.parse("def persist_backing_mount(device,target_path,fs):\n    operations.append({'operation':'persist_mount_stub','device':device,'filesystem':fs})\n    return 'design-filesystem-uuid'").body[0]
code=compile(ast.fix_missing_locations(tree),'<verified-deployed-volume-inspect-with-io-stubs>','exec')
spec=importlib.util.spec_from_file_location('identity',root/'systemvm/debian/usr/local/lib/ablestack-storage/volume_identity.py');identity=importlib.util.module_from_spec(spec);spec.loader.exec_module(identity)
volume='3af8261e-6877-4479-8815-0b64a1b50910'
def run_case(fs,mode,expected='xfs',safe_initial=False):
 operations=[];devices=[{'name':'sda','path':'/dev/sda','type':'disk','fstype':None,'serial':'root00000000000000','size':5242880000,'mountpoint':None,'children':[{'name':'sda1','path':'/dev/sda1','type':'part','fstype':'ext4','size':4000000000,'mountpoint':'/'}]},{'name':'sdb','path':'/dev/sdb','type':'disk','fstype':fs,'serial':volume.replace('-','')[:20],'uuid':'design-filesystem-uuid' if fs else None,'size':21474836480,'mountpoint':None}]
 payload={'volumeUuid':volume,'volumeSizeBytes':21474836480,'importMode':mode,'filesystem':expected,'path':'' if safe_initial else '/export/design1442','config':{'createDirectory':not safe_initial}}
 def fake_run(args,**kwargs):
  operations.append({'operation':'command_stub','command':args})
  if args[0]=='lsblk':return types.SimpleNamespace(returncode=0,stdout=json.dumps({'blockdevices':copy.deepcopy(devices)}),stderr='')
  if args[0]=='findmnt':return types.SimpleNamespace(returncode=1,stdout='',stderr='')
  if args[0]=='blkid':return types.SimpleNamespace(returncode=0 if fs else 2,stdout='design-filesystem-uuid' if fs else '',stderr='')
  if args[0]=='mount' and not fs:return types.SimpleNamespace(returncode=32,stdout='',stderr='design fake: blank disk cannot mount')
  return types.SimpleNamespace(returncode=0,stdout='',stderr='')
 fakepath=types.SimpleNamespace(exists=lambda p:False,isdir=lambda p:True,realpath=lambda p:p,join=posixpath.join,dirname=posixpath.dirname)
 fakeos=types.SimpleNamespace(sep='/',path=fakepath,makedirs=lambda path,**kw:operations.append({'operation':'mkdir_stub','path':path}))
 env={'os':fakeos,'sys':types.SimpleNamespace(argv=['design','/design-payload']),'subprocess':types.SimpleNamespace(run=fake_run,PIPE=-1,DEVNULL=-3),'shutil':types.SimpleNamespace(which=lambda v:'/design/'+v),'tempfile':types.SimpleNamespace(),'VolumeIdentityError':identity.VolumeIdentityError,'resolve_volume_device':identity.resolve_volume_device,'operations':operations,'open':lambda p,*a,**kw:io.StringIO(json.dumps(payload))}
 stdout=io.StringIO();error=None;result=None
 try:
  with contextlib.redirect_stdout(stdout):exec(code,env)
  result=json.loads(stdout.getvalue())
 except BaseException as e:error=str(e)
 return {'fixtureFilesystem':fs,'mode':mode,'expected':expected,'result':result,'error':error,'operations':operations,'formatted':any(x.get('command',[''])[0].startswith('mkfs.') for x in operations)}
cases=[run_case('xfs','MOUNT_EXISTING'),run_case('ext4','MOUNT_EXISTING'),run_case(None,'INSPECT_ONLY'),run_case(None,'MOUNT_EXISTING'),run_case('xfs','INSPECT_ONLY'),run_case('xfs','INSPECT_ONLY','auto',True),run_case(None,'INSPECT_ONLY','auto',True)]
assert not any(x['formatted'] for x in cases)
assert cases[1]['result']['filesystem']=='ext4'
assert cases[2]['result']['filesystem']=='xfs','Characterization changed: inspect blank currently falls back to requested xfs'
assert cases[3]['error']=='design fake: blank disk cannot mount'
assert any(x['operation']=='mkdir_stub' for x in cases[4]['operations'])
assert not any(x['operation']=='mkdir_stub' for x in cases[5]['operations'])
assert not any(x['operation']=='mkdir_stub' for x in cases[6]['operations'])
assert cases[5]['result']['filesystemUuid'] and not cases[6]['result']['filesystemUuid']
result={'kind':'design-only characterization of deployed-identical code with all process/filesystem I/O stubbed; not real-volume import acceptance','sourceSha256':__import__('hashlib').sha256((root/'systemvm/debian/usr/local/bin/ablestack-storagectl').read_bytes()).hexdigest(),'cases':cases,'checksPassed':7,'changesToGuest':False}
(out/'guest-characterization.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps({'checksPassed':7,'noFormatterInExistingModes':True,'safeInitialInspectMakesNoDirectoryWrites':True,'blankSafeInspectIdentifiedByMissingFsUuid':True,'blankInspectCurrentlyReportsFallbackXfs':True,'inspectOnlyCurrentlyAttemptsDirectoryCreation':True,'realDeviceIO':False}))
