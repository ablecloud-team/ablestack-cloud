# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
"""Exact scoped endpoint metadata with real Generation/FD9 and no host network effects."""
import ast,base64,copy,fcntl,hashlib,json,os,stat,subprocess,sys,tempfile,unittest,uuid
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage"
CLI=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
sys.path.insert(0,str(LIB))
from config_generation import Generation,atomic_json,read_json
from network_endpoint_transaction import NetworkEndpointTransaction
from TestStorageLocalSamBootstrap import require_verified_private_sandbox


class StorageNetworkEndpointTransactionTest(unittest.TestCase):
    def setUp(self):
        require_verified_private_sandbox()
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.config=self.root/"config";self.config.mkdir(mode=0o700)
        self.g=Generation(self.root/"generation",self.config)
        self.instance=str(uuid.uuid4())
        self.original=json.dumps({"endpoints":[{"listenIp":"192.0.2.10","primaryIp":"192.0.2.10",
            "prefixlen":24,"port":3260,"legacyLabel":"keep exact"}]},indent=3).encode()+b"\n"
        self.network=self.config/"network-endpoints.json";self.network.write_bytes(self.original);self.network.chmod(0o640)
        self.nfs={"instanceUuid":self.instance,"enabled":True,"listenIp":"192.0.2.11","port":2049,"exports":[]}
        source={"instanceUuid":self.instance,"operationUuid":str(uuid.uuid4()),"revision":1}
        for action in ("begin","verify","commit","finish"):self.g.execute(action,source)
        frozen=self.g.frozen(source)
        self.request={"operationScope":{"instanceUuid":self.instance,"operationUuid":str(uuid.uuid4()),"revision":2}}
        self.g.execute("begin",{**self.request["operationScope"],"previousGeneration":frozen["generation"],
                               "configurationDesiredState":frozen["configurationDesiredState"]})
        self.source=frozen["configurationDesiredState"];self.before=self.g.digest()
        lock=self.root/"writer.lock";fd=os.open(lock,os.O_RDWR|os.O_CREAT,0o600)
        try:saved=os.dup(9)
        except OSError:saved=None
        if fd!=9:os.dup2(fd,9);os.close(fd)
        fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
        def close():
            if saved is None:os.close(9)
            else:os.dup2(saved,9);os.close(saved)
        self.addCleanup(close)
        self.env=patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9",
            "ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(lock),
            "ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR":str(self.root/"maintenance"),
            "ABLESTACK_STORAGE_RENDERED_GENERATIONS":str(self.root/"rendered")})
        self.env.start();self.addCleanup(self.env.stop)
        block=CLI.read_text();start=block.index("def protected_network_bytes(");end=block.index("\ndef validate_endpoint_primary(",start)
        ns={"os":os,"stat":stat};exec(compile(ast.parse(block[start:end]),"<protected-network-source>","exec"),ns)
        self.read_bytes=ns["protected_network_bytes"]
        self.receipt=self.root/"receipt.json";self.bindings=[{"listenIp":"192.0.2.11","primaryIp":"192.0.2.10",
                                                           "prefixlen":24,"macAddress":"02:00:00:00:00:01"}]
        self.observed=copy.deepcopy(self.bindings)
        def verify(bindings):
            if any(binding not in self.observed for binding in bindings):raise ValueError("Pinned address binding changed")
        def receipt_publish(network):
            atomic_json(self.receipt,{"networkSha256":hashlib.sha256(self.network.read_bytes()).hexdigest(),
                                      "bindings":self.observed})
        self.tx=NetworkEndpointTransaction(self.g,read_bytes=self.read_bytes,verify=verify,
                                           receipt_path=self.receipt,receipt_publish=receipt_publish,
                                           restore_addresses=lambda rows:self.observed.clear(),
                                           receipt_plan=lambda network:{"networkSha256":hashlib.sha256(
                                               json.dumps(network,sort_keys=True).encode()).hexdigest(),"bindings":self.observed})

    def prepared(self,add=True):
        self.tx.begin(self.request)
        candidate=copy.deepcopy(self.source["network-endpoints.json"])
        if add:candidate["endpoints"]+=copy.deepcopy(self.bindings)
        return self.tx.prepared(self.request,candidate,self.bindings,self.bindings if add else [])

    def publish(self):
        atomic_json(self.config/self.tx.NFS,self.nfs)
        return self.tx.publish({**self.request,"expectedNfsDesiredState":self.nfs})

    def test_new_ip_preparation_preserves_source_until_verified_nfs_publication(self):
        result=self.prepared()
        self.assertTrue(result["prepared"]);self.assertFalse(result["canonicalChanged"])
        self.assertEqual(self.original,self.network.read_bytes());self.assertEqual(self.before,self.g.digest())
        result=self.publish()
        self.assertTrue(result["published"]);self.assertTrue(result["canonicalChanged"])
        self.assertEqual(result["targetConfigurationSha256"],self.g.digest())
        rows=json.loads(self.network.read_text())["endpoints"]
        self.assertEqual(3260,rows[0]["port"]);self.assertEqual("keep exact",rows[0]["legacyLabel"])
        self.assertNotIn("port",rows[1]);self.assertTrue(self.receipt.is_file())

    def test_same_ip_protocol_port_change_does_not_publish_or_reformat_source_bytes(self):
        self.prepared(add=False);result=self.publish()
        self.assertFalse(result["canonicalChanged"]);self.assertEqual(self.original,self.network.read_bytes())
        self.assertEqual(0o640,stat.S_IMODE(self.network.stat().st_mode));self.assertFalse(self.receipt.exists())

    def test_address_restore_is_exact_and_does_not_overwrite_other_protocol_files(self):
        self.prepared();self.publish()
        result=self.tx.restore({**self.request,"previousGeneration":self.tx.pending["previous"],
                                "configurationDesiredState":self.source})
        self.assertTrue(result["sourceRestored"]);self.assertEqual(self.original,self.network.read_bytes())
        self.assertFalse(self.receipt.exists());self.assertEqual(self.nfs,read_json(self.config/self.tx.NFS))
        self.assertEqual(0o640,stat.S_IMODE(self.network.stat().st_mode))

    def test_foreign_configuration_or_wrong_nfs_target_cannot_be_published(self):
        self.prepared();atomic_json(self.config/self.tx.NFS,self.nfs)
        with self.assertRaises(ValueError):self.tx.publish({**self.request,"expectedNfsDesiredState":{**self.nfs,"port":2050}})
        atomic_json(self.config/"sharedfs-network.json",{"foreign":True})
        with self.assertRaises(ValueError):self.tx.publish({**self.request,"expectedNfsDesiredState":self.nfs})
        self.assertEqual(self.original,self.network.read_bytes());self.assertFalse(self.receipt.exists())

    def test_source_drift_is_not_rebased_or_admitted_as_planned_address_change(self):
        atomic_json(self.config/"sharedfs-network.json",{"foreign":True})
        with self.assertRaises(ValueError):self.tx.begin(self.request)
        self.assertFalse((self.g.root/("network-endpoint-"+self.request["operationScope"]["operationUuid"]+".json")).exists())

    def test_foreign_scope_boot_primary_mac_journal_and_restore_source_are_rejected(self):
        self.prepared()
        for change in (lambda value:value.update(bootId=str(uuid.uuid4())),
                       lambda value:value.update(scope={**value["scope"],"revision":99})):
            original=read_json(self.tx.path);bad=copy.deepcopy(original);change(bad);atomic_json(self.tx.path,bad)
            with self.assertRaises(ValueError):self.publish()
            atomic_json(self.tx.path,original)
        self.observed[0]["macAddress"]="02:00:00:00:00:02"
        with self.assertRaises(ValueError):self.publish()
        self.observed=copy.deepcopy(self.bindings)
        with self.assertRaises(ValueError):
            self.tx.restore({**self.request,"previousGeneration":{},"configurationDesiredState":self.source})
        self.assertEqual(self.original,self.network.read_bytes())

    def test_failure_windows_preserve_source_authority_and_publish_response_loss_is_idempotent(self):
        self.prepared()
        request={**self.request,"previousGeneration":self.tx.pending["previous"],"configurationDesiredState":self.source}
        # Address prepared, then NFS did not publish a canonical target.
        self.assertTrue(self.tx.restore(request)["sourceRestored"])
        self.assertEqual(self.original,self.network.read_bytes())

    def test_nfs_success_then_publish_not_called_restores_only_exact_planned_target(self):
        self.prepared();atomic_json(self.config/self.tx.NFS,self.nfs)
        request={**self.request,"previousGeneration":self.tx.pending["previous"],"configurationDesiredState":self.source,
                 "expectedNfsDesiredState":self.nfs}
        self.assertTrue(self.tx.restore(request)["sourceRestored"])
        self.assertEqual(self.nfs,read_json(self.config/self.tx.NFS))
        # The existing original writer restores the complete exact SOURCE next.
        self.g.execute("restore",{**self.request["operationScope"],"previousGeneration":self.tx.pending["previous"],
                                  "configurationDesiredState":self.source})
        self.assertEqual(self.before,self.g.digest())

    def test_publish_response_loss_replay_and_source_restore_are_exact(self):
        self.prepared();first=self.publish();raw=self.network.read_bytes()
        again=self.tx.publish({**self.request,"expectedNfsDesiredState":self.nfs})
        self.assertEqual(first["targetConfigurationSha256"],again["targetConfigurationSha256"])
        self.assertEqual(raw,self.network.read_bytes())
        changed=copy.deepcopy(self.nfs);changed["port"]=2050
        with self.assertRaises(ValueError):self.tx.publish({**self.request,"expectedNfsDesiredState":changed})
        self.assertEqual(raw,self.network.read_bytes())

    def test_receipt_written_before_ack_journal_can_resume_or_restore_only_its_durable_intent(self):
        self.prepared();atomic_json(self.config/self.tx.NFS,self.nfs)
        original=self.tx.receipt_publish
        def after_write(network):
            original(network)
            raise OSError("Synthetic response lost after exact receipt write")
        self.tx.receipt_publish=after_write
        with self.assertRaises(OSError):self.tx.publish({**self.request,"expectedNfsDesiredState":self.nfs})
        self.assertEqual("PUBLISHING",read_json(self.tx.path)["phase"])
        self.tx.receipt_publish=original
        self.assertTrue(self.tx.publish({**self.request,"expectedNfsDesiredState":self.nfs})["published"])
        self.tx.restore({**self.request,"previousGeneration":self.tx.pending["previous"],"configurationDesiredState":self.source})
        self.assertEqual(self.original,self.network.read_bytes())

    def test_receipt_written_before_ack_journal_can_restore_directly_without_republishing(self):
        self.prepared();atomic_json(self.config/self.tx.NFS,self.nfs)
        original=self.tx.receipt_publish
        def after_write(network):
            original(network)
            raise OSError("Synthetic interruption after receipt write")
        self.tx.receipt_publish=after_write
        with self.assertRaises(OSError):self.tx.publish({**self.request,"expectedNfsDesiredState":self.nfs})
        self.assertTrue(self.tx.restore({**self.request,"previousGeneration":self.tx.pending["previous"],
                                        "configurationDesiredState":self.source})["sourceRestored"])
        self.assertEqual(self.original,self.network.read_bytes())

    def test_publishing_durable_receipt_does_not_accept_unknown_receipt_drift(self):
        self.prepared();atomic_json(self.config/self.tx.NFS,self.nfs)
        original=self.tx.receipt_publish
        def after_write(network):
            original(network)
            raise OSError("Synthetic interruption")
        self.tx.receipt_publish=after_write
        with self.assertRaises(OSError):self.tx.publish({**self.request,"expectedNfsDesiredState":self.nfs})
        atomic_json(self.receipt,{"foreign":True})
        self.tx.receipt_publish=original
        with self.assertRaises(ValueError):self.tx.publish({**self.request,"expectedNfsDesiredState":self.nfs})
        with self.assertRaises(ValueError):self.tx.restore({**self.request,"previousGeneration":self.tx.pending["previous"],
                                                           "configurationDesiredState":self.source})

    def test_dynamic_checkpoint_target_hash_tracks_network_publish_and_original_source_restore(self):
        self.prepared();atomic_json(self.config/self.tx.NFS,self.nfs)
        before=self.g.digest()
        path=Path("/run/ablestack-storage/nfs-dynamic-sources")/self.request["operationScope"]["operationUuid"]/"checkpoint.json"
        atomic_json(path,{"schemaVersion":1,"sourceGeneration":self.tx.pending["previous"],"endpoints":{},
                          "scope":self.request["operationScope"],"bootId":self.tx.boot,"beforeSha256":self.before,
                          "phase":"TARGET_VERIFIED","targetDesiredSha256":before})
        self.addCleanup(lambda:path.unlink(missing_ok=True))
        self.publish();self.assertEqual(self.g.digest(),read_json(path)["targetDesiredSha256"])
        self.tx.restore({**self.request,"previousGeneration":self.tx.pending["previous"],"configurationDesiredState":self.source})
        self.assertEqual(before,read_json(path)["targetDesiredSha256"])
        self.assertEqual(self.original,self.network.read_bytes())

    def test_signed_network_module_and_generation_primitives_are_exact_copies(self):
        body=CLI.read_text()
        for marker,name,filter_imports in (("NETWORK CONFIG GENERATION","config_generation.py",()),
                ("NETWORK ENDPOINT TRANSACTION","network_endpoint_transaction.py",
                 ("from config_generation import ","from iscsi_auth import "))):
            actual=body.split("# BEGIN EMBEDDED "+marker+"\n",1)[1].split("# END EMBEDDED "+marker,1)[0].strip()
            expected="\n".join(line for line in (LIB/name).read_text().splitlines()if not line.startswith(filter_imports)).strip()
            self.assertEqual(expected,actual)



class NetworkEndpointCliFixture:
    """Actual signed CLI endpoint commands; only NIC inspection is a private model."""
    def __init__(self, packet):
        require_verified_private_sandbox()
        self.packet=packet;self.requests=packet["requests"]
        prepare=next(row["payload"]for row in self.requests if row["operation"]=="network endpoint apply")
        self.scope=prepare["operationScope"]
        self.temporary=tempfile.TemporaryDirectory();self.root=Path(self.temporary.name)
        self.backups=[];self.paths=[]
        for path in (Path("/etc/ablestack-storage"),Path("/var/lib/ablestack-storage/network-endpoint-bindings.json")):
            path.parent.mkdir(parents=True,exist_ok=True)
            if path.exists()or path.is_symlink():
                if path.is_symlink():raise ValueError("Foreign fixture state path")
                backup=path.with_name(path.name+".endpoint-fixture-"+uuid.uuid4().hex)
                path.rename(backup);self.backups.append((path,backup))
            self.paths.append(path)
        self.config=Path("/etc/ablestack-storage");self.config.mkdir(mode=0o700)
        self.g=Generation(self.root/"generation",self.config)
        restore=next((row["payload"]for row in self.requests if row["operation"]=="network endpoints source-restore"),None)
        primary=prepare["primaryIp"];mac=prepare["macAddress"];prefix=prepare["prefixlen"]
        if restore:
            source=restore["configurationDesiredState"];previous=restore["previousGeneration"]
            if NetworkEndpointTransaction.digest(source)!=previous["configurationSha256"]:
                raise ValueError("Manager fixture SOURCE SHA is not canonical")
            for name,value in source.items():
                if value is not None:atomic_json(self.config/name,value)
            atomic_json(self.g.current,previous)
            atomic_json(self.g.root/(previous["operationUuid"]+".json"),{**previous,"phase":"VERIFIED","desired":source})
        else:
            source={"instanceUuid":self.scope["instanceUuid"],"operationUuid":str(uuid.uuid4()),
                    "revision":max(1,self.scope["revision"]-1)}
            if not packet.get("sourceCacheAbsent"):
                atomic_json(self.config/"network-endpoints.json",{"endpoints":[{
                    "listenIp":primary,"primaryIp":primary,"prefixlen":prefix,"port":3260}]})
            for action in ("begin","verify","commit","finish"):self.g.execute(action,source)
        self.source=self.g.files();self.original=self.g.digest()
        self.original_network=self.config/"network-endpoints.json"
        self.raw=self.original_network.read_bytes()if self.original_network.exists()else None
        addresses=[{"local":primary,"prefixlen":prefix}]
        for entry in ((self.source.get("network-endpoints.json")or{}).get("endpoints")or[]):
            if entry["listenIp"]!=primary:
                addresses.append({"local":entry["listenIp"],"prefixlen":entry["prefixlen"],"secondary":True})
        for ip in packet.get("initialSecondary",[]):
            if ip!=primary:addresses.append({"local":ip,"prefixlen":prefix,"secondary":True})
        self.model=self.root/"model.json"
        self.initial_addresses={row["local"]for row in addresses}
        self.model.write_text(json.dumps({"ifname":"eth0","mac":mac,"addresses":addresses}))
        binary=self.root/"bin";binary.mkdir()
        ip=binary/"ip"
        ip.write_text("""#!/usr/bin/python3
import json,os,sys
from pathlib import Path
p=Path(os.environ["NFS_ENDPOINT_MODEL_FILE"]);m=json.loads(p.read_text());args=sys.argv[1:]
if args==["-j","-4","addr","show","scope","global"]:
 print(json.dumps([{"ifname":m["ifname"],"addr_info":m["addresses"]}]))
elif args==["-j","link","show"]:
 print(json.dumps([{"ifname":m["ifname"],"address":m["mac"],"link_type":"ether"}]))
elif args[:2]==["link","set"]:pass
elif args[:2]in(["addr","add"],["addr","del"]):
 address,prefix=args[2].split("/");rows=[r for r in m["addresses"]if r["local"]==address]
 if args[1]=="add":
  assert not rows
  m["addresses"].append({"local":address,"prefixlen":int(prefix),"secondary":True})
 else:
  assert len(rows)==1 and rows[0]["prefixlen"]==int(prefix)
  m["addresses"]=[r for r in m["addresses"]if r["local"]!=address]
 p.write_text(json.dumps(m))
else:raise AssertionError("Unexpected private network inspection command")
""");ip.chmod(0o755)
        self.environment=dict(os.environ,PATH=str(binary)+":"+os.environ["PATH"],
            NFS_ENDPOINT_MODEL_FILE=str(self.model),ABLESTACK_STORAGE_GENERATION_DIR=str(self.g.root),
            ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.config),
            ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.root/"writer.lock"),
            ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(self.root/"maintenance"),
            ABLESTACK_STORAGE_RENDERED_GENERATIONS=str(self.root/"rendered"))
        self.environment.pop("ABLESTACK_STORAGE_WRITER_LOCK_FD",None)
        self.g.execute("begin",self.scope)
        self.counter=0

    def close(self):
        import shutil
        for path in reversed(self.paths):
            if path.is_symlink():raise ValueError("Fixture state path became linked")
            if path.is_dir():shutil.rmtree(path)
            elif path.exists():path.unlink()
        for path,backup in reversed(self.backups):backup.rename(path)
        self.temporary.cleanup()

    def invoke(self, operation, payload):
        self.counter+=1;path=self.root/("request-"+str(self.counter)+".json")
        path.write_text(json.dumps(payload));path.chmod(0o600)
        result=subprocess.run([str(CLI),*operation.split(),str(path)],env=self.environment,
                              capture_output=True,text=True,timeout=30)
        value=json.loads(result.stdout)
        if result.returncode or value.get("success")is not True:
            raise AssertionError("Actual signed endpoint CLI rejected the producer request: "+json.dumps(value))
        if result.stderr:raise AssertionError("Actual signed endpoint CLI emitted unexpected stderr")
        return value

    def execute(self):
        capability=self.invoke("operation generation status",{})
        if capability.get("endpointPreparationSupported")is not True:
            raise AssertionError("Signed CLI lacks the safe endpoint preparation capability")
        receipts=[];source_unchanged=True;seeded=False;restored=False
        for request in self.requests:
            operation,payload=request["operation"],request["payload"]
            if operation=="nfs export apply":
                # Real submitted canonical payload is used; daemons are deliberately outside this endpoint fixture.
                atomic_json(self.config/NetworkEndpointTransaction.NFS,
                            {key:value for key,value in payload.items()if key!="operationScope"})
                seeded=True;continue
            if operation=="network endpoint publish"and not seeded:
                atomic_json(self.config/NetworkEndpointTransaction.NFS,payload["expectedNfsDesiredState"]);seeded=True
            if operation not in ("network endpoint apply","network endpoint publish",
                                  "network endpoints source-restore","operation generation restore"):
                raise ValueError("Manager fixture operation is outside its fixed contract")
            receipt=self.invoke(operation,payload);receipts.append(receipt)
            if operation=="network endpoint apply":
                source_unchanged=source_unchanged and self.g.digest()==self.original
            if operation=="network endpoints source-restore":restored=True
        network=read_json(self.config/"network-endpoints.json")
        rows=[]if network is None else network["endpoints"]
        result={"success":True,"signedCliOnly":True,"nfsRuntimeNotExercised":True,
                "sourceUnchangedBeforeNfs":source_unchanged,"sourceRestored":restored,
                "canonicalExistingPortPreserved":all(row.get("port")==3260 for row in rows if row["listenIp"]==
                    next(row["payload"]["primaryIp"]for row in self.requests if row["operation"]=="network endpoint apply")),
                "receipts":receipts,"canonicalNetwork":network,
                "activeAddresses":[row["local"]for row in json.loads(self.model.read_text())["addresses"]]}
        if not source_unchanged or not result["canonicalExistingPortPreserved"]:
            raise AssertionError("Actual endpoint CLI did not preserve its SOURCE before NFS")
        if restored:
            current=self.original_network.read_bytes()if self.original_network.exists()else None
            result["sourceBytesRestored"]=current==self.raw
            if not result["sourceBytesRestored"] or set(result["activeAddresses"])!=self.initial_addresses:
                raise AssertionError("Actual endpoint CLI did not restore exact SOURCE metadata/NIC addresses")
        return result


class StorageNetworkEndpointCliTest(unittest.TestCase):
    def packet(self,listen="192.0.2.10",source_absent=False,secondary=False):
        scope={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":2}
        prepare={"operationScope":scope,"deferCanonical":True,"listenIp":listen,"primaryIp":"192.0.2.10",
                 "macAddress":"02:00:00:00:00:01","prefixlen":24,"port":2049}
        nfs={"instanceUuid":scope["instanceUuid"],"enabled":True,"listenIp":listen,"port":2049,"exports":[]}
        return {"sourceCacheAbsent":source_absent,"initialSecondary":[listen]if secondary else[],
            "requests":[{"operation":"network endpoint apply","payload":prepare},
                        {"operation":"nfs export apply","payload":nfs},
                        {"operation":"network endpoint publish","payload":{"operationScope":scope,"expectedNfsDesiredState":nfs}}]}

    def test_actual_cli_existing_primary_or_secondary_without_cache_never_claims_ip_creation(self):
        for listen,secondary in (("192.0.2.10",False),("192.0.2.11",True)):
            with self.subTest(listen=listen):
                fixture=NetworkEndpointCliFixture(self.packet(listen,True,secondary))
                try:
                    fixture.requests.append({"operation":"network endpoints source-restore","payload":{
                        "operationScope":fixture.scope,"previousGeneration":read_json(fixture.g.current),
                        "configurationDesiredState":fixture.source,"expectedNfsDesiredState":fixture.packet["requests"][1]["payload"]}})
                    result=fixture.execute()
                    self.assertTrue(result["sourceBytesRestored"])
                    journal=read_json(fixture.g.root/("network-endpoint-"+fixture.scope["operationUuid"]+".json"))
                    self.assertEqual([],journal["createdAddresses"])
                    self.assertIsNone(result["canonicalNetwork"])
                    self.assertFalse(result["receipts"][0]["canonicalChanged"])
                    self.assertTrue(result["sourceUnchangedBeforeNfs"])
                finally:fixture.close()

    def test_explicit_prepare_response_loss_replay_keeps_original_ip_creation_ownership(self):
        packet=self.packet("192.0.2.11");packet["requests"].insert(1,copy.deepcopy(packet["requests"][0]))
        fixture=NetworkEndpointCliFixture(packet)
        try:
            result=fixture.execute()
            self.assertTrue(result["sourceUnchangedBeforeNfs"])
            journal=read_json(fixture.g.root/("network-endpoint-"+fixture.scope["operationUuid"]+".json"))
            self.assertEqual(1,len(journal["createdAddresses"]))
            self.assertIs(journal["createdAddresses"][0]["priorPresent"],False)
            self.assertIs(journal["createdAddresses"][0]["changed"],True)
        finally:fixture.close()

    def test_actual_cli_shared_ip_retains_iscsi_port_and_new_alias_has_address_only_identity(self):
        for listen in ("192.0.2.10","192.0.2.11"):
            with self.subTest(listen=listen):
                fixture=NetworkEndpointCliFixture(self.packet(listen))
                try:
                    result=fixture.execute()
                    self.assertTrue(result["sourceUnchangedBeforeNfs"])
                    self.assertTrue(result["canonicalExistingPortPreserved"])
                    for row in result["canonicalNetwork"]["endpoints"]:
                        if row["listenIp"]=="192.0.2.11":self.assertNotIn("port",row)
                finally:fixture.close()


def manager_endpoint_fixture():
    packet=json.load(sys.stdin)
    fixture=NetworkEndpointCliFixture(packet)
    try:print(json.dumps(fixture.execute(),separators=(",",":")))
    finally:fixture.close()


if __name__=="__main__":
    if "--manager-endpoint" in sys.argv:manager_endpoint_fixture()
    else:unittest.main()


