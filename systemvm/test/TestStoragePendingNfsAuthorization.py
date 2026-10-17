from pathlib import Path
import ast,copy,fcntl,hashlib,json,os,re,sys,tempfile,unittest,uuid
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
 def local_source_authority(self, phase="VERIFIED"):
  conf=self.root/"local-source-samba";conf.mkdir(mode=0o700)
  raw=b"[global]\nnetbios name=SOURCE\n";(conf/"smb.conf").write_bytes(raw)
  auth=PendingNfsAuthorization(self.g,self.rendered,self.maintenance,self.root/"local-source-auth",conf,protocol="SMB")
  pending=read_json(self.g.pending);pending["phase"]=phase;atomic_json(self.g.pending,pending)
  identity=str(uuid.UUID(bytes=hashlib.md5(("local-source-checkpoint:"+self.scope["operationUuid"]).encode()).digest(),version=3))
  local={**self.scope,"localCheckpointUuid":identity}
  common={**local,"sourceGeneration":{},"sourceConfigurationSha256":pending["beforeSha256"],
          "expectedBootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip()}
  record={"schemaVersion":1,"kind":"LOCAL_SOURCE_IDENTITY_CHECKPOINT","scope":local,"common":common,
          "sourceDesiredState":self.source,"sourceConfigurationFileSha256":hashlib.sha256(raw).hexdigest(),
          "capsule":{"fixture":"opaque-prior-import"}}
  digest=lambda value:hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()
  journal={"scope":local,"common":common,"phase":"RESUMING","imported":True,"importedFingerprints":{},
           "importedStoppedDatabases":{},"importedCheckpointSha256":digest(record),
           "prior":{"units":[{"unit":"smbd.service"}],"configurationSha256":record["sourceConfigurationFileSha256"]}}
  root=self.g.root.parent/"smb-local-source-checkpoints";root.mkdir(mode=0o700)
  cipher=root/(identity+"-cipher.json");header=root/(identity+"-journal.json")
  atomic_json(cipher,record);atomic_json(header,journal)
  return auth,cipher,header,digest

 def test_verified_only_original_local_resume_grant_reaches_same_closed_consumer(self):
  auth,cipher,header,digest=self.local_source_authority()
  with self.assertRaises(ValueError):
   with auth.grant(self.payload,"smbd.service"):self.fail("Forward VERIFIED grant")
  with auth.local_source_grant(self.scope,"smbd.service"):
   self.assertTrue(auth.authorized("smbd.service",self.maintenance.status(),self.rendered.status()))
   self.assertFalse(auth.authorized("nmbd.service",self.maintenance.status(),self.rendered.status()))
   proof=read_json(auth.path);self.assertEqual("NATIVE_PENDING_SMB_LOCAL_SOURCE_RESUME",proof["mode"])
  self.assertFalse(auth.path.exists())

 def test_local_resume_rejects_new_import_foreign_scope_boot_reference_and_committed(self):
  auth,cipher,header,digest=self.local_source_authority()
  original=read_json(header);original_pending=read_json(self.g.pending)
  for changes in ({"imported":False},{"phase":"STOPPED"},{"importedCheckpointSha256":"0"*64},
                  {"common":{**original["common"],"expectedBootId":str(uuid.uuid4())}},
                  {"scope":{**original["scope"],"operationUuid":str(uuid.uuid4())}}):
   atomic_json(header,{**original,**changes})
   with self.subTest(changes=changes),self.assertRaises(ValueError):
    with auth.local_source_grant(self.scope,"smbd.service"):self.fail("Foreign local grant")
   self.assertFalse(auth.path.exists())
  atomic_json(header,original);atomic_json(self.g.pending,{**original_pending,"phase":"COMMITTED"})
  with self.assertRaises(ValueError):
   with auth.local_source_grant(self.scope,"smbd.service"):self.fail("Committed local grant")

 def test_local_consumer_rechecks_checkpoint_reference_and_current_source(self):
  auth,cipher,header,digest=self.local_source_authority()
  with auth.local_source_grant(self.scope,"smbd.service"):
   saved=cipher.read_bytes();record=read_json(cipher);record["capsule"]={"fixture":"changed-opaque-record"}
   atomic_json(cipher,record)
   self.assertFalse(auth.authorized("smbd.service",self.maintenance.status(),self.rendered.status()))
   cipher.write_bytes(saved)
   self.assertTrue(auth.authorized("smbd.service",self.maintenance.status(),self.rendered.status()))
   atomic_json(self.g.config/"network-endpoints.json",{"foreign":True})
   self.assertFalse(auth.authorized("smbd.service",self.maintenance.status(),self.rendered.status()))

 def test_local_reader_preserves_service_sixteen_mib_bound_and_duplicate_rejection(self):
  from pending_nfs_authorization import pending_local_source_read
  path=self.root/"local-large.json";atomic_json(path,{"opaque":"x"*(8*1024*1024+1)})
  self.assertEqual(8*1024*1024+1,len(pending_local_source_read(path)["opaque"]))
  path.write_bytes(b'{"opaque":"'+b"x"*(16*1024*1024)+b'"}')
  with self.assertRaises(ValueError):pending_local_source_read(path)
  path.write_text('{"scope":1,"scope":2}')
  with self.assertRaises(ValueError):pending_local_source_read(path)

 def test_local_reader_bodies_match_reviewed_service_cipher_exactly(self):
  reviewed=ast.parse((LIB/"service_identity_cipher.py").read_text())
  actual=ast.parse((LIB/"pending_nfs_authorization.py").read_text())
  klass=next(n for n in reviewed.body if isinstance(n,ast.ClassDef)and n.name=="ServiceIdentityCipher")
  source=copy.deepcopy(next(n for n in klass.body if isinstance(n,ast.FunctionDef)and n.name=="read"))
  class Alias(ast.NodeTransformer):
   def visit_Name(self,node):
    if node.id=="service_cipher_json":node.id="pending_local_source_json"
    return node
  source=Alias().visit(source)
  target=next(n for n in actual.body if isinstance(n,ast.FunctionDef)and n.name=="pending_local_source_read")
  self.assertEqual(ast.dump(ast.Module(body=source.body,type_ignores=[]),include_attributes=False),
                   ast.dump(ast.Module(body=target.body,type_ignores=[]),include_attributes=False))
  source=next(n for n in reviewed.body if isinstance(n,ast.FunctionDef)and n.name=="service_cipher_json")
  target=next(n for n in actual.body if isinstance(n,ast.FunctionDef)and n.name=="pending_local_source_json")
  self.assertEqual(ast.dump(ast.Module(body=source.body,type_ignores=[]),include_attributes=False),
                   ast.dump(ast.Module(body=target.body,type_ignores=[]),include_attributes=False))

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
 def test_actual_nfs_main_export_uuid_then_embedded_grant_preserves_module_identity(self):
  cli=LIB.parents[1]/'bin/ablestack-storagectl';source=cli.read_text()
  program=next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",source,re.S) if 'def start_ganesha_endpoints(' in value)
  tree=ast.parse(program)
  parser=next(node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name=='safe_uuid')
  export_loop=next(node for node in tree.body if isinstance(node,ast.For) and isinstance(node.target,ast.Name) and node.target.id=='export' and isinstance(node.body[0],ast.Assign) and any(isinstance(item,ast.Name) and item.id=='uuid' for item in node.body[0].targets))
  readers=source.split('# BEGIN EMBEDDED PENDING NFS READERS\n',1)[1].split('\n# END EMBEDDED PENDING NFS READERS',1)[0]
  helper=source.split('# BEGIN EMBEDDED PENDING NFS AUTHORIZATION\n',1)[1].split('\n# END EMBEDDED PENDING NFS AUTHORIZATION',1)[0]
  export_uuid=str(uuid.uuid4());exports=self.root/'parsed-exports';exports.mkdir(mode=0o700)
  def namespace(body):
   ns={};exec('import copy,hashlib,json,os,stat\nfrom pathlib import Path\n'+body+'\n'+helper,ns)
   ns.update(payload={**self.payload,'exports':[{'uuid':export_uuid,'state':'Disabled'}]},exports_dir=str(exports),managed_files=set(),enabled=False)
   # Execute the actual whole legacy export loop, including its real private
   # export-file creation. Disabled rows avoid mounts, DATA or network effects.
   exec(compile(ast.Module(body=[parser,export_loop],type_ignores=[]),str(cli),'exec'),ns)
   self.assertEqual(export_uuid,ns['uuid']);return ns
  broken=namespace(readers.replace('import uuid as pending_nfs_uuid','import uuid').replace('pending_nfs_uuid.UUID','uuid.UUID'))
  broken_reader=broken['PendingNfsGeneration'](self.g.root,self.g.config)
  old=broken['PendingNfsAuthorization'](broken_reader,self.rendered,self.maintenance,self.root/'old-uuid-grant',self.conf)
  with self.assertRaisesRegex(AttributeError,'UUID'):
   with old.grant(broken['payload'],self.unit):self.fail('Prior UUID collision passed')
  self.assertFalse(old.path.exists())
  fixed=namespace(readers);reader=fixed['PendingNfsGeneration'](self.g.root,self.g.config)
  auth=fixed['PendingNfsAuthorization'](reader,self.rendered,self.maintenance,self.root/'fixed-uuid-grant',self.conf)
  pending=read_json(self.g.pending);digest=self.g.digest()
  with auth.grant(fixed['payload'],self.unit):
   self.assertTrue(auth.authorized(self.unit,self.maintenance.status(),self.rendered.status()))
   self.assertFalse(auth.authorized('smbd.service',self.maintenance.status(),self.rendered.status()))
   self.assertEqual(export_uuid,fixed['uuid'])
  self.assertFalse(auth.path.exists());self.assertEqual(pending,read_json(self.g.pending));self.assertEqual(digest,self.g.digest())
 def test_smb_owned_registry_unit_and_fixed_auxiliary_grants_are_protocol_closed(self):
  import hashlib
  registry=self.root/'smb-registry';registry.mkdir(mode=0o700)
  ip='10.10.13.243';port=445;key=hashlib.sha256((ip+':'+str(port)).encode()).hexdigest()[:24]
  atomic_json(registry/(key+'.json'),{'listenIp':ip,'port':port})
  configuration=self.root/'samba';configuration.mkdir(mode=0o700);(configuration/'smb.conf').write_text('[global]\nnetbios name = STOR1234567890\n')
  auth=PendingNfsAuthorization(self.g,self.rendered,self.maintenance,self.root/'smb-auth',configuration,protocol='SMB',registry=registry)
  unit='ablestack-storage-smb@'+key+'.service'
  source=self.g.files()
  for selected in (unit,'smbd.service','nmbd.service'):
   with auth.grant(self.payload,selected):
    self.assertTrue(auth.authorized(selected,self.maintenance.status(),self.rendered.status()))
    self.assertFalse(auth.authorized(self.unit,self.maintenance.status(),self.rendered.status()))
    self.assertFalse(auth.authorized('winbind.service',self.maintenance.status(),self.rendered.status()))
   self.assertFalse(auth.path.exists())
  self.assertEqual(source,self.g.files())
 def test_smb_foreign_registry_changed_config_and_unknown_protocol_deny(self):
  import hashlib
  registry=self.root/'registry';registry.mkdir(mode=0o700)
  key=hashlib.sha256(b'10.10.13.243:445').hexdigest()[:24]
  atomic_json(registry/(key+'.json'),{'listenIp':'10.10.13.244','port':445})
  conf=self.root/'samba';conf.mkdir(mode=0o700);(conf/'smb.conf').write_text('[global]\n')
  auth=PendingNfsAuthorization(self.g,self.rendered,self.maintenance,self.root/'auth-smb',conf,protocol='SMB',registry=registry)
  with self.assertRaises(ValueError):
   with auth.grant(self.payload,'ablestack-storage-smb@'+key+'.service'):self.fail('Foreign registry granted')
  self.assertFalse(auth.path.exists())
  with self.assertRaises(ValueError):PendingNfsAuthorization(protocol='ALL')
  atomic_json(registry/(key+'.json'),{'listenIp':'10.10.13.243','port':445})
  with auth.grant(self.payload,'ablestack-storage-smb@'+key+'.service'):
   (conf/'smb.conf').write_text('[global]\nchanged=true\n')
   self.assertFalse(auth.authorized('ablestack-storage-smb@'+key+'.service',self.maintenance.status(),self.rendered.status()))
 def test_actual_smb_main_fixed_start_uses_embedded_held_scope_and_cleans_grant(self):
  import hashlib
  cli=LIB.parents[1]/'bin/ablestack-storagectl';source=cli.read_text()
  program=next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",source,re.S) if 'def reconcile_smb_endpoint_units(' in value)
  readers=program.split('# BEGIN EMBEDDED PENDING SMB READERS\n',1)[1].split('\n# END EMBEDDED PENDING SMB READERS',1)[0]
  helper=program.split('# BEGIN EMBEDDED PENDING SMB AUTHORIZATION\n',1)[1].split('\n# END EMBEDDED PENDING SMB AUTHORIZATION',1)[0]
  start=next(node for node in ast.parse(program).body if isinstance(node,ast.FunctionDef) and node.name=='pending_smb_service_start')
  ns={};exec('import hashlib,json,os,stat,subprocess\nfrom pathlib import Path\n'+readers+'\n'+helper,ns)
  registry=self.root/'main-smb-registry';registry.mkdir(mode=0o700)
  key=hashlib.sha256(b'10.10.13.243:445').hexdigest()[:24]
  atomic_json(registry/(key+'.json'),{'listenIp':'10.10.13.243','port':445})
  conf=self.root/'main-samba';conf.mkdir(mode=0o700);(conf/'smb.conf').write_text('[global]\nnetbios name=STOR1234567890\n')
  reader=ns['PendingNfsGeneration'](self.g.root,self.g.config)
  auth=ns['PendingNfsAuthorization'](reader,self.rendered,self.maintenance,self.root/'main-smb-auth',conf,protocol='SMB',registry=registry)
  calls=[]
  def systemctl_only(args,**kwargs):
   self.assertEqual('systemctl',args[0]);unit=args[-1]
   self.assertTrue(auth.authorized(unit,self.maintenance.status(),self.rendered.status()))
   calls.append(args)
  ns.update(payload=self.payload,pending_smb=auth,run=systemctl_only)
  exec(compile(ast.Module(body=[start],type_ignores=[]),str(cli),'exec'),ns)
  before=self.g.files();pending=read_json(self.g.pending)
  unit='ablestack-storage-smb@'+key+'.service'
  ns['pending_smb_service_start'](unit)
  ns['pending_smb_service_start']('nmbd',enable=True)
  ns['pending_smb_service_start']('smbd.service',restart=True)
  self.assertEqual(['start','enable','restart'],[row[1] for row in calls])
  self.assertFalse(auth.path.exists());self.assertEqual(before,self.g.files());self.assertEqual(pending,read_json(self.g.pending))
  with self.assertRaises(ValueError):ns['pending_smb_service_start']('winbind.service')
  self.assertEqual(3,len(calls))
 def test_replaced_grant_preserved_and_rejected(self):
  with self.assertRaisesRegex(ValueError,'replaced'):
   with self.auth.grant(self.payload,self.unit):
    data=read_json(self.auth.path);self.auth.path.unlink();atomic_json(self.auth.path,data)
  self.assertTrue(self.auth.path.exists())
