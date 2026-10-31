"""Design-only extension of the installed compatibility check. No installation."""
from pathlib import Path
import ast,sys,json,tempfile,shutil,hashlib
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='d9c423e9-ce89-3ba9-9cd8-f48cdb791da2'
sys.path.insert(0,'/usr/local/lib/ablestack-storage')
from runtime_updater import RuntimeUpdater
u=RuntimeUpdater()
release=Path('/usr/local/bin/ablestack-storagectl').resolve().parent
helper=release/'lib/ablestack-storage/shared_directory_policy.py'
before=hashlib.sha256(helper.read_bytes()).hexdigest()
baseline=Path('/usr/local/lib/ablestack-storage/runtime_updater.py')
tree=ast.parse(baseline.read_text())
version=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='UPDATER_VERSION' for t in n.targets))
defaults={'createMask':'0660','directoryMask':'0770','forceCreateMode':'0000','forceDirectoryMode':'0000'}
def proposed_check(target,payload):
 u.require_directory_compatibility(target) # Real current common POSIX / inherit-owner guard.
 active=any(row.get('state') not in ('Disabled','Destroyed','Error') and any((row.get('config') or {}).get(k,v)!=v for k,v in defaults.items()) for row in payload.get('shares',[]))
 if not active:return
 tree=ast.parse((target/'lib/ablestack-storage/shared_directory_policy.py').read_text())
 supported=next((ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='SMB_CREATION_POLICY_VERSION' for t in n.targets)),0)
 if type(supported) is not int or supported!=1:raise ValueError('SMB_CREATION_POLICY_CAPABILITY_REQUIRED')
root=Path(tempfile.mkdtemp(prefix='epic1454-compatibility-',dir='/var/tmp'))
try:
 (root/'lib/ablestack-storage').mkdir(parents=True)
 candidate=root/'lib/ablestack-storage/shared_directory_policy.py'
 candidate.write_text(helper.read_text()+'\nSMB_CREATION_POLICY_VERSION=1\nraise RuntimeError("candidate top-level must never execute")\n')
 defaults_payload={'shares':[{'state':'Ready','config':dict(defaults)}]}
 custom={'shares':[{'state':'Ready','config':{'createMask':'0640'}}]}
 proposed_check(release,defaults_payload)
 try:proposed_check(release,custom)
 except ValueError as e:assert str(e)=='SMB_CREATION_POLICY_CAPABILITY_REQUIRED'
 else:raise AssertionError('Old helper accepted custom policy')
 proposed_check(root,custom)
 disabled={'shares':[{'state':'Disabled','config':{'createMask':'0640'}}]}
 proposed_check(release,disabled)
 assert before==hashlib.sha256(helper.read_bytes()).hexdigest()
 print(json.dumps({'success':True,'installedProtectedUpdaterVersion':version,'installedGuardReused':True,'defaultCompatibilityPreserved':True,'activeCustomOldHelperRejected':True,'candidateFeatureAstAcceptedWithoutExecutingCode':True,'disabledReferenceSkipped':True,'productionHelperUnchanged':True}))
finally:shutil.rmtree(root)
