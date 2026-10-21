# SPDX-License-Identifier: Apache-2.0
import pathlib,hashlib,ast,copy,json,subprocess,time,base64,importlib.util,types,os
script=pathlib.Path('/usr/local/bin/ablestack-storagectl')
identity=pathlib.Path('/usr/local/lib/ablestack-storage/volume_identity.py')
data=pathlib.Path('/srv/ablestack-storage/volumes/c8aef987-45a4-47aa-a1b9-37ab54c5d629/export/p1442ext4')
def snapshot():
 return {'helperSha256':hashlib.sha256(script.read_bytes()).hexdigest(),'identitySha256':hashlib.sha256(identity.read_bytes()).hexdigest(),'fstabSha256':hashlib.sha256(pathlib.Path('/etc/fstab').read_bytes()).hexdigest(),'dataSha256':hashlib.sha256((data/'preserve.txt').read_bytes()).hexdigest(),'directory':{'uid':data.stat().st_uid,'gid':data.stat().st_gid,'mode':oct(data.stat().st_mode&0o777)},'file':{'uid':(data/'preserve.txt').stat().st_uid,'gid':(data/'preserve.txt').stat().st_gid,'mode':oct((data/'preserve.txt').stat().st_mode&0o777)}}
before=snapshot()
section=script.read_text().split('volume_attach_inspect() {',1)[1].split("<<'PY'\n",1)[1].split('\nPY\n}',1)[0]
tree=ast.parse(section);defs={n.name:n for n in tree.body if isinstance(n,ast.FunctionDef)}
def function(name,bindings):
 exec(compile(ast.Module(body=[copy.deepcopy(defs[name])],type_ignores=[]),'deployed:'+name,'exec'),bindings)
 return bindings[name]
run=function('run',{'subprocess':subprocess})
start=time.monotonic()
try:
 run(['/bin/sleep','21'])
 raise AssertionError('Expected deployed 20-second cutoff')
except subprocess.TimeoutExpired as e:
 actual={'kind':'real-deployed-run-function','actualTimeoutSeconds':e.timeout,'elapsedSeconds':round(time.monotonic()-start,2),'command':'/bin/sleep 21','observedProductionStorageCommandSettingSeconds':300, 'productionHostCommandDispatched':False, 'harnessTotalWaitBudgetSeconds':100}
# isolated signature sketch; not installed or called by production
node=copy.deepcopy(defs['run']);node.args.args.append(ast.arg(arg='timeout'));node.args.defaults.append(ast.Constant(20))
node.body[0].value.keywords[-1].value=ast.Name(id='timeout',ctx=ast.Load())
sandbox={'subprocess':subprocess};exec(compile(ast.fix_missing_locations(ast.Module(body=[node],type_ignores=[])),'design-only:run-timeout-parameter','exec'),sandbox)
start=time.monotonic();result=sandbox['run'](['/bin/sleep','21'],timeout=30)
assert result.returncode==0
candidate={'kind':'isolated-timeout-signature-sketch','timeoutSeconds':30,'elapsedSeconds':round(time.monotonic()-start,2),'returncode':result.returncode,'productionHelperChanged':False}
format_cases=[]
for fs in ['xfs','ext4']:
 calls=[]
 def fake_run(args):
  calls.append(args);return subprocess.CompletedProcess(args,0,'','')
 b={'requested_filesystem':fs,'device':'/dev/issue1443-NEVER-OPENED','run':fake_run,'shutil':types.SimpleNamespace(which=lambda _:True)}
 assert function('format_empty_device',b)()==fs
 format_cases.append({'fs':fs,'virtualSizeFixtureGiB':10240,'capturedOnlyCommands':calls,'realMkfsExecuted':False})
b={'requested_filesystem':'xfs','device':'/dev/issue1443-NEVER-OPENED','shutil':types.SimpleNamespace(which=lambda _:True)}
def timeout_run(args):raise subprocess.TimeoutExpired(args,20)
b['run']=timeout_run
try:function('format_empty_device',b)();raise AssertionError()
except subprocess.TimeoutExpired:unhandled=True
spec=importlib.util.spec_from_file_location('identity_probe',str(identity));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.glob=types.SimpleNamespace(glob=lambda _:[])
uid='11111111-2222-4333-8444-555555555555'
root={'name':'sda','path':'/dev/sda','type':'disk','size':5242880000,'serial':'root','fstype':'ext4','mountpoint':'/'}
def disk(name='sdb',serial=None,fs=None,fsuuid=None,mount=None,size=2199023255552):
 return {'name':name,'path':'/dev/'+name,'type':'disk','size':size,'serial':serial,'fstype':fs,'uuid':fsuuid,'mountpoint':mount}
identity_cases=[]
for name,devices,kwargs in [
 ('exact_serial',[root,disk(serial=uid.replace('-','')[:20])],dict(allow_blank_size_fallback=False)),
 ('wrong_uuid_same_size_legacy_fallback',[root,disk(serial='other-volume')],dict(allow_blank_size_fallback=True)),
 ('wrong_uuid_strict_blocks',[root,disk(serial='other-volume')],dict(allow_blank_size_fallback=False)),
 ('ambiguous_serial',[root,disk(serial=uid),disk('sdc',uid)],dict(allow_blank_size_fallback=False)),
 ('root_identity_blocks',[dict(root,serial=uid)],dict(allow_blank_size_fallback=False))]:
 try:result=m.resolve_volume_device(devices,volume_uuid=uid,expected_size=2199023255552,**kwargs);identity_cases.append({'case':name,'result':result})
 except m.VolumeIdentityError as e:identity_cases.append({'case':name,'blocked':True,'reason':str(e)})
assert identity_cases[1]['result']['matchedBy']=='UNIQUE_BLANK_SIZE'
assert all(c.get('blocked') for c in identity_cases[2:])
after=snapshot();assert before==after
print(json.dumps({'before':before,'after':after,'productionStateUnchanged':True,'deployedRun':actual,'isolatedSketch':candidate,'formatFunctionCases':format_cases,'formatTimeoutEscapesUnstructured':unhandled,'identityFixtures':identity_cases,'sourceSectionBase64':base64.b64encode(section.encode()).decode(),'identitySourceBase64':base64.b64encode(identity.read_bytes()).decode()}))