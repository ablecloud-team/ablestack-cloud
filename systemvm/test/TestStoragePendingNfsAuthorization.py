from pathlib import Path
import copy,fcntl,json,os,sys,tempfile,unittest,uuid
LIB=Path(__file__).resolve().parents[1]/'debian/usr/local/lib/ablestack-storage'
sys.path.insert(0,str(LIB))
from config_generation import Generation,atomic_json,read_json
from rendered_generation import RenderedGeneration
from template_maintenance import Maintenance
from pending_nfs_authorization import PendingNfsAuthorization
class PendingNfsAuthorizationTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.env=dict(os.environ)
  self.saved=None
  try:self.saved=fcntl.fcntl(9,fcntl.F_DUPFD_CLOEXEC,16)
  except OSError:pass
  self.lockpath=self.root/'writer.lock';fd=os.open(self.lockpath,os.O_RDWR|os.O_CREAT,0o600)
  if fd!=9:os.dup2(fd,9);os.close(fd)
  fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
  os.environ['ABLESTACK_STORAGE_WRITER_LOCK_FILE']=str(self.lockpath);os.environ['ABLESTACK_STORAGE_WRITER_LOCK_FD']='9'
  self.g=Generation(self.root/'native',self.root/'config')
  self.scope={'instanceUuid':str(uuid.uuid4()),'operationUuid':str(uuid.uuid4()),'revision':1}
  self.source=self.g.files();self.g.execute('begin',self.scope)
  self.rendered=RenderedGeneration(self.root/'rendered');self.maintenance=Maintenance(self.root/'maintenance')
  self.conf=self.root/'nfs';self.conf.mkdir(mode=0o700)
  self.unit='ablestack-storage-ganesha@0.0.0.0_2049.service'
  (self.conf/'0.0.0.0_2049.conf').write_text('NFS_CORE_PARAM { NFS_Port=2049; }')
  self.auth=PendingNfsAuthorization(self.g,self.rendered,self.maintenance,self.root/'authorization',self.conf)
  self.payload={'instanceUuid':self.scope['instanceUuid'],'operationScope':dict(self.scope)}
 def tearDown(self):
  os.close(9)
  if self.saved is not None:os.dup2(self.saved,9);os.close(self.saved)
  os.environ.clear();os.environ.update(self.env);self.tmp.cleanup()
 def authorized(self,unit=None):return self.auth.authorized(unit or self.unit,self.maintenance.status(),self.rendered.status())
 def test_real_held_fd9_own_pending_unit_and_finally_cleanup(self):
  before=read_json(self.g.pending);digest=self.g.digest()
  with self.auth.grant(self.payload,self.unit):
   self.assertTrue(self.authorized());self.assertFalse(self.authorized('smbd.service'))
  self.assertFalse(self.auth.path.exists());self.assertEqual(before,read_json(self.g.pending));self.assertEqual(digest,self.g.digest())
 def test_unheld_named_fd9_is_not_acquired(self):
  fcntl.flock(9,fcntl.LOCK_UN)
  with self.assertRaisesRegex(ValueError,'actual FLOCK'):
   with self.auth.grant(self.payload,self.unit):self.fail('Unheld FD9 granted')
  self.assertFalse(self.auth.path.exists())
 def test_current_digest_may_differ_from_original_before_digest(self):
  atomic_json(self.g.config/'desired-state/nfs-export-apply.json',{'enabled':False,'exports':[]})
  self.assertNotEqual(self.g.digest(),read_json(self.g.pending)['beforeSha256'])
  with self.auth.grant(self.payload,self.unit):self.assertTrue(self.authorized())
 def test_scope_invalid_before_grant(self):
  for delta in ({'revision':True},{'operationUuid':str(uuid.uuid4())},{'instanceUuid':str(uuid.uuid4())},{'extra':False}):
   payload=copy.deepcopy(self.payload);payload['operationScope'].update(delta)
   with self.assertRaises(ValueError):
    with self.auth.grant(payload,self.unit):self.fail('Foreign scope granted')
   self.assertFalse(self.auth.path.exists())
 def test_no_scope_pending_denied(self):
  with self.assertRaises(ValueError):self.auth.validate_payload({'instanceUuid':self.scope['instanceUuid']})
 def test_root_service_and_rendered_holds_denied(self):
  for kind in ('ROOT','SERVICE'):
   marker={'kind':kind,'scope':{**self.scope,('templateUpgradeUuid' if kind=='ROOT' else 'maintenanceUuid'):str(uuid.uuid4()) if kind=='ROOT' else self.scope['operationUuid']}}
   self.maintenance.write(self.maintenance.marker,marker)
   with self.assertRaises(ValueError):self.auth.validate_payload(self.payload)
   self.maintenance.marker.unlink()
  self.rendered.read_journal=lambda:{'phase':'ACTIVATING','scope':self.scope}
  with self.assertRaises(ValueError):self.auth.validate_payload(self.payload)
 def test_wrong_phase_denied(self):
  pending=read_json(self.g.pending);pending['phase']='VERIFIED';atomic_json(self.g.pending,pending)
  with self.assertRaises(ValueError):self.auth.validate_payload(self.payload)
 def test_proof_tampering_boot_pid_ticks_scope_unknown_flag_denied(self):
  with self.auth.grant(self.payload,self.unit):
   original=read_json(self.auth.path)
   for delta in ({'bootId':str(uuid.uuid4())},{'pid':os.getpid()+1000000},{'startTicks':'0'},{'extra':True},{'configurationSha256':'0'*64},{'mode':'SERVICE_SOURCE_RESTORE'}):
    changed={**original,**delta};self.auth.path.write_text(json.dumps(changed));self.assertFalse(self.authorized())
   self.auth.path.write_text(json.dumps(original))
 def test_config_changed_then_restored(self):
  p=self.conf/'0.0.0.0_2049.conf';before=p.read_bytes()
  with self.auth.grant(self.payload,self.unit):
   p.write_bytes(before+b'changed');self.assertFalse(self.authorized());p.write_bytes(before);self.assertFalse(self.authorized())
 def test_failure_cleanup_preserves_native_source_and_pending(self):
  before=read_json(self.g.pending);source=self.g.files()
  with self.assertRaisesRegex(RuntimeError,'unit start failed'):
   with self.auth.grant(self.payload,self.unit):raise RuntimeError('unit start failed')
  self.assertFalse(self.auth.path.exists());self.assertEqual(before,read_json(self.g.pending));self.assertEqual(source,self.g.files())
 def test_replaced_grant_preserved_and_rejected(self):
  with self.assertRaisesRegex(ValueError,'replaced'):
   with self.auth.grant(self.payload,self.unit):
    data=read_json(self.auth.path);self.auth.path.unlink();atomic_json(self.auth.path,data)
  self.assertTrue(self.auth.path.exists())
