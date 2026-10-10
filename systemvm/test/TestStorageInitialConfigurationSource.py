from pathlib import Path
import copy,json,os,subprocess,sys,tempfile,unittest,uuid
LIB=Path(__file__).resolve().parents[1]/'debian/usr/local/lib/ablestack-storage';sys.path.insert(0,str(LIB))
from config_generation import Generation,atomic_json,read_json
CLI=LIB.parents[1]/'bin/ablestack-storagectl'
class InitialConfigurationSourceTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
  self.g=Generation(self.root/'native',self.root/'config')
  self.scope={'instanceUuid':str(uuid.uuid4()),'operationUuid':str(uuid.uuid4()),'revision':1}
  self.source=self.g.files();self.g.execute('begin',self.scope)
  self.request={**self.scope,'configurationDesiredState':copy.deepcopy(self.source)}
 def tearDown(self):self.tmp.cleanup()
 def test_original_absence_restored_then_normal_rollback(self):
  original=read_json(self.g.pending)
  atomic_json(self.g.config/'desired-state/nfs-export-apply.json',{'enabled':False,'exports':[]})
  self.assertNotEqual(self.g.digest(),original['beforeSha256'])
  response=self.g.execute('frozen-initial',self.request)
  self.assertIs(response['initialSourceVerified'],True);self.assertEqual({},response['generation'])
  self.assertEqual(original,read_json(self.g.pending));self.assertFalse(self.g.current.exists())
  restored=self.g.execute('restore',{**self.request,'previousGeneration':{}})
  self.assertTrue(restored['canonicalRestored']);self.assertEqual(original['beforeSha256'],self.g.digest())
  self.assertFalse((self.g.config/'desired-state/nfs-export-apply.json').exists())
  self.assertEqual(original,read_json(self.g.pending));self.assertFalse(self.g.current.exists())
  self.g.execute('rollback',self.scope);self.assertFalse(self.g.pending.exists());self.assertEqual({},read_json(self.g.current))
 def test_wrong_scope_and_closed_request(self):
  for delta in ({'operationUuid':str(uuid.uuid4())},{'revision':True},{'initial':True},{'previousGeneration':{}}):
   with self.assertRaises(ValueError):self.g.execute('frozen-initial',{**self.request,**delta})
 def test_current_or_previous_generation_denied(self):
  atomic_json(self.g.current,dict(self.scope))
  with self.assertRaises(ValueError):self.g.execute('frozen-initial',self.request)
  self.g.current.unlink();pending=read_json(self.g.pending);pending['previous']=dict(self.scope);atomic_json(self.g.pending,pending)
  with self.assertRaises(ValueError):self.g.execute('restore',{**self.request,'previousGeneration':{}})
 def test_wrong_phase_missing_extra_seven_and_wrong_hash_denied(self):
  for kind in ('missing','extra','hash'):
   desired=copy.deepcopy(self.source)
   if kind=='missing':desired.pop(next(iter(desired)))
   elif kind=='extra':desired['foreign.json']=None
   else:desired['desired-state/nfs-export-apply.json']={'enabled':False}
   with self.assertRaises(ValueError):self.g.execute('frozen-initial',{**self.scope,'configurationDesiredState':desired})
  pending=read_json(self.g.pending);pending['phase']='VERIFIED';atomic_json(self.g.pending,pending)
  with self.assertRaises(ValueError):self.g.execute('frozen-initial',self.request)
 def test_production_cli_readonly_entry_does_not_acquire_writer(self):
  request=self.root/'request.json';request.write_text(json.dumps(self.request));request.chmod(0o600)
  lock=self.root/'absent-writer/lock'
  env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1',ABLESTACK_STORAGE_GENERATION_DIR=str(self.g.root),ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.g.config),ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(lock))
  result=subprocess.run(['bash',str(CLI),'operation','generation','frozen-initial',str(request)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env)
  self.assertEqual(0,result.returncode,result.stderr);self.assertIs(json.loads(result.stdout)['initialSourceVerified'],True)
  self.assertFalse(lock.parent.exists());self.assertFalse(self.g.current.exists())
