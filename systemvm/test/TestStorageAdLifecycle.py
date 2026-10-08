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

from pathlib import Path
import json,os,sys,subprocess,tempfile,unittest,uuid,fcntl
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from ad_lifecycle import AdDomainLifecycle

class StorageAdLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.config=self.root/"etc/ablestack-storage";self.config.mkdir(parents=True,mode=0o700);self.gen=self.root/"generation";self.gen.mkdir(mode=0o700)
        self.request={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":2,"domainName":"ablestack.local","workgroup":"ABLESTACK","netbiosName":"SERVER",
                      "dnsServers":["10.10.13.100"],"dnsAliases":[{"hostname":"server.ablestack.local","addresses":["10.10.13.240"]}],"servicePrincipals":["host/server.ablestack.local","cifs/server.ablestack.local"],
                      "identityMode":"JOIN_EXISTING","username":"Administrator","password":"SYNTHETIC_PASSWORD_ONLY"}
        self.request["maintenanceUuid"]=self.request["operationUuid"]
        (self.gen/"current.json").write_text(json.dumps({key:self.request[key] for key in ("instanceUuid","operationUuid","revision")}));(self.gen/"current.json").chmod(0o600)
        samba=self.root/"etc/samba";samba.mkdir();self.smb=samba/"smb.conf";self.smb.write_text("[global]\n private dir = /var/lib/samba/private\n[existing]\n path = /srv/existing\n valid users = olduser\n")
        self.before=self.smb.read_bytes();self.calls=[];self.command_failure=False;self.quiescent=True
        outer=self
        class Daemon:
            def scope(self,request):return {key:request[key] for key in ("instanceUuid","operationUuid","revision")}
            def marker(self,request):return {**self.scope(request),"maintenanceUuid":request["maintenanceUuid"]}
            def start(self,request):outer.calls.append(["OWNED_DAEMON_START"]);return {"success":True}
            def stop(self,request):outer.calls.append(["OWNED_DAEMON_STOP"]);return {"success":True}
        def quiet():
            if not outer.quiescent:raise ValueError("live holder")
        self.lifecycle=AdDomainLifecycle("fixture",self.config,self.run_command,Daemon(),quiet,self.root,source_provider=self.source_proof,sid_reader=lambda name:"S-1-5-21-1-2-3")
        self.joined=False;self.remote_sid="S-1-5-21-4-5-6-1001";self.foreign_dns=False;self.foreign_ipv6=False;self.disabled_present=False;self.invalid_machine_configuration=False
        self.env=patch.dict(os.environ,{"ABLESTACK_STORAGE_GENERATION_DIR":str(self.gen)});self.env.start();self.addCleanup(self.env.stop)
        self.public_sid=patch("ad_identity.samba_public_sid",side_effect=lambda name:"S-1-5-21-1-2-3");self.public_sid.start();self.addCleanup(self.public_sid.stop)
    def source_proof(self,scope,fresh):
        state=json.loads(self.lifecycle.state.read_text()) if self.lifecycle.state.exists() else {}
        identity=None
        if state.get("state")=="JOINED":
            identity={"domain":state["domainName"],**{key:state[key] for key in ("realm","workgroup","netbiosName","dnsAliases","servicePrincipals","idmapPolicy")},
                      **state["identityReceipt"],"trustVerified":True,"machineConfigurationSha256":state["machineConfigurationSha256"]}
        return {"scope":scope,"serviceSourceStoppedVerified":True,"bootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
                "publicLocalMachineSid":"S-1-5-21-1-2-3","sourceConfigurationSha256":"a"*64,"adIdentity":identity}

    def run_command(self,args,**kwargs):
        self.assertFalse(any(self.request["password"] in arg for arg in args));self.calls.append(args)
        plain=[arg for arg in args if not arg.startswith(("--configfile=","--authentication-file=","--use-kerberos=","--use-krb5-ccache="))]
        if self.command_failure and plain[:3]==["net","ads","join"]:return subprocess.CompletedProcess(args,1,"","private diagnostic must stay omitted")
        if plain[:3]==["net","ads","search"]:
            output="Got 1 replies\nobjectSid: "+self.remote_sid+"\n" if self.joined else "Got 0 replies\n\n"
            return subprocess.CompletedProcess(args,0,output,"")
        if plain[:3]==["net","ads","join"]:self.joined=True
        if plain[:3]==["net","ads","leave"]:self.joined=self.disabled_present
        if plain[:4]==["net","ads","keytab","create"]:
            (self.root/"etc/krb5.keytab").write_text("SYNTHETIC_KEYTAB_BYTES");(self.root/"etc/krb5.keytab").chmod(0o600)
        if plain[:2]==["net","getdomainsid"]:out="SID for local machine SERVER is: S-1-5-21-1-2-3\nSID for domain ABLESTACK is: S-1-5-21-4-5-6\n"
        elif plain[0]=="klist":out="2 cifs/server.ablestack.local@ABLESTACK.LOCAL\n2 host/server.ablestack.local@ABLESTACK.LOCAL\n2 SERVER$@ABLESTACK.LOCAL\n"
        elif plain[:3]==["net","ads","setspn"]:out="cifs/server.ablestack.local\nhost/server.ablestack.local\n"
        elif plain[0]=="dig":
            out=("2001:db8::1\n" if self.foreign_ipv6 else "") if "AAAA" in plain else (("10.10.13.240\n10.10.13.242\n" if self.foreign_dns else "10.10.13.240\n") if self.joined else "")
        elif plain[0]=="wbinfo":out="S-1-5-21-4-5-6-1001 SID_USER (1)"
        elif plain[0]=="testparm":
            if self.invalid_machine_configuration and not any("parameter-name=" in arg for arg in plain):return subprocess.CompletedProcess(args,1,"","private diagnostic omitted")
            name=plain[-1].split("=",1)[-1]
            out=self.request["netbiosName"] if name=="netbios name" else "tdb" if name.endswith("backend") and "*" in name else "rid" if name.endswith("backend") else "10000-60000" if "*" in name else "1000000-1999999" if name.endswith("range") else "0" if name.endswith("base_rid") else "valid"
        else:out="Join is OK"
        return subprocess.CompletedProcess(args,0,out,"")
    def test_join_uses_sealed_credentials_preserves_source_shares_and_persists_only_verified_public_receipt(self):
        result=self.lifecycle.join(self.request)
        self.assertTrue(result["joined"]);self.assertTrue(result["identity"]["trustVerified"]);self.assertFalse(result["canonicalDesiredStateChanged"])
        self.assertEqual(self.before,self.smb.read_bytes());self.assertIn("[existing]",self.lifecycle.machine.read_text())
        state=json.loads(self.lifecycle.state.read_text());self.assertEqual("S-1-5-21-4-5-6-1001",state["identityReceipt"]["machineAccountSid"])
        self.assertEqual("COMPLETE",json.loads(self.lifecycle.journal.read_text())["phase"])
        for path in self.root.rglob("*"):
            if path.is_file():self.assertNotIn(self.request["password"].encode(),path.read_bytes())
        self.assertTrue(any(arg.startswith("--authentication-file=/proc/self/fd/") for call in self.calls for arg in call))
    def test_join_failure_retains_recovery_receipt_stops_owned_daemon_and_never_claims_joined(self):
        self.command_failure=True
        with self.assertRaises(ValueError):self.lifecycle.join(self.request)
        self.assertEqual("RECOVERY_REQUIRED",json.loads(self.lifecycle.journal.read_text())["phase"])
        self.assertFalse(self.lifecycle.state.exists());self.assertIn(["OWNED_DAEMON_STOP"],self.calls);self.assertEqual(self.before,self.smb.read_bytes())
    def test_invalid_secret_transport_or_live_identity_holder_blocks_before_public_file_writes(self):
        for invalid in ("", "bad\npassword"):
            with self.assertRaises(ValueError):self.lifecycle.join({**self.request,"password":invalid})
        self.quiescent=False
        with self.assertRaises(ValueError):self.lifecycle.join(self.request)
        self.assertFalse(self.lifecycle.machine.exists());self.assertFalse(self.lifecycle.journal.exists());self.assertFalse(any(row[0]!="testparm" for row in self.calls))

    def test_samevm_verified_identity_is_retained_without_remote_rejoin_or_sid_mutation(self):
        joined=self.lifecycle.join(self.request);self.calls.clear()
        result=self.lifecycle.join(self.request)
        self.assertTrue(result["identityPreserved"]);self.assertFalse(result["rejoined"])
        self.assertFalse(any("join" in call or "setlocalsid" in call for call in self.calls))
        expected={key:joined["identity"][key] for key in ("domain","realm","workgroup","netbiosName","machineSid","domainSid","machineAccountSid","servicePrincipals","idmapPolicy","dnsAliases")}
        expected["machineConfigurationSha256"]=json.loads(self.lifecycle.state.read_text())["machineConfigurationSha256"]
        restored=self.lifecycle.retain(self.request,expected)
        self.assertTrue(restored["identityPreserved"]);self.assertFalse(restored["rejoined"])
        self.lifecycle.machine.write_text("tampered private config")
        before=len(self.calls)
        with self.assertRaises(ValueError):self.lifecycle.retain(self.request,expected)
        self.assertFalse(any(call==["OWNED_DAEMON_START"] for call in self.calls[before:]))
    def test_new_clone_name_is_new_instance_bound_and_source_identity_copy_is_rejected_before_effects(self):
        request={**self.request,"identityMode":"NEW_INSTANCE"}
        with self.assertRaises(ValueError):self.lifecycle.join(request)
        self.assertFalse(self.lifecycle.machine.exists());self.assertFalse(any(row[0]!="testparm" for row in self.calls))
        request["netbiosName"]="STOR"+request["instanceUuid"].replace("-","")[:10].upper()
        request["sourceIdentity"]={"netbiosName":request["netbiosName"],"machineSid":"S-1-5-21-1-2-3","machineAccountSid":"S-1-5-21-4-5-6-1001"}
        with self.assertRaises(ValueError):self.lifecycle.join(request)
        self.assertFalse(self.lifecycle.machine.exists());self.assertFalse(self.calls)

    def clone_request(self):
        netbios="STOR"+self.request["instanceUuid"].replace("-","")[:10].upper()
        hostname=netbios.lower()+".ablestack.local"
        return {**self.request,"identityMode":"NEW_INSTANCE","netbiosName":netbios,
                "dnsAliases":[{"hostname":hostname,"addresses":["10.10.13.240"]}],
                "servicePrincipals":["host/"+hostname,"cifs/"+hostname],
                "sourceIdentity":{"netbiosName":"SERVER","machineSid":"S-1-5-21-1-2-3","machineAccountSid":"S-1-5-21-4-5-6-1001",
                                  "dnsAliases":self.request["dnsAliases"],"servicePrincipals":self.request["servicePrincipals"]}}

    def test_new_instance_plain_source_identity_has_no_mutation_authority(self):
        request=self.clone_request()
        with self.assertRaisesRegex(ValueError,"authenticated original authority"):self.lifecycle.join(request)
        self.assertFalse(self.lifecycle.machine.exists());self.assertFalse(self.lifecycle.journal.exists());self.assertEqual([],self.calls)

    def test_remote_computer_spn_dns_collision_or_unproven_absence_blocks_before_any_public_write(self):
        request=self.request;queries=[];output=["Got 1 replies\n\n"]
        def run(args,**kwargs):
            queries.append(args)
            if args[0]=="testparm":return subprocess.CompletedProcess(args,0,self.request["netbiosName"],"")
            if args[:3]==["net","ads","search"]:return subprocess.CompletedProcess(args,0,output[0],"")
            if args[0]=="dig":return subprocess.CompletedProcess(args,0,"10.10.13.240\n","")
            raise AssertionError("mutation reached "+repr(args))
        self.lifecycle.run=run
        for value in ("Got 1 replies\n\n","","Got 0 replies\nunexpected record","Got 0 replies\n\n"):
            with self.subTest(reply=value.splitlines()[:1]):
                output[0]=value
                with self.assertRaises(ValueError):self.lifecycle.join(request)
                self.assertFalse(self.lifecycle.machine.exists());self.assertFalse(self.lifecycle.journal.exists())
                self.assertFalse(any("OWNED_DAEMON" in " ".join(call) for call in self.calls))
        # Source aliases are rejected before even sending a directory query.
        queries.clear()
        with self.assertRaisesRegex(ValueError,"authenticated original authority"):self.lifecycle.join({**self.clone_request(),"dnsAliases":self.request["dnsAliases"],"servicePrincipals":self.request["servicePrincipals"]})
        self.assertEqual([],queries)

    def test_leave_incomplete_nullable_or_invalid_credentials_reject_before_daemon_stop_and_public_effects(self):
        self.lifecycle.join(self.request);self.calls.clear()
        state=self.lifecycle.state.read_bytes();journal=self.lifecycle.journal.read_bytes()
        for change in ({"username":None},{"password":None},{"username":""},{"password":""},{"password":"bad\nsecret"},{"username":True}):
            with self.subTest(change=tuple(change)):
                with self.assertRaisesRegex(ValueError,"complete and valid"):self.lifecycle.leave({**self.request,**change})
                self.assertEqual([],self.calls);self.assertEqual(state,self.lifecycle.state.read_bytes());self.assertEqual(journal,self.lifecycle.journal.read_bytes())

    def test_ordinary_leave_proves_remote_absence_and_closed_artifacts_restores_public_config_and_preserves_sam(self):
        (self.root/"etc/krb5.conf").write_text("[libdefaults]\n dns_lookup_kdc = true\n");(self.root/"etc/krb5.conf").chmod(0o644)
        (self.root/"etc/resolv.conf").write_text("nameserver 10.10.13.1\n");(self.root/"etc/resolv.conf").chmod(0o644)
        before={name:(self.root/"etc"/name).read_bytes() for name in ("krb5.conf","resolv.conf")}
        self.lifecycle.join(self.request);result=self.lifecycle.leave(self.request)
        self.assertTrue(result["left"]);self.assertTrue(result["localMachineSidPreserved"]);self.assertTrue(result["adOwnedArtifactsRemoved"])
        self.assertEqual([{"name":name,"absent":True} for name in ("ad-machine.conf","krb5.keytab","winbindd_idmap.tdb")],result["ownedArtifactCleanup"])
        self.assertEqual("NOT_JOINED",result["identity"]["joinState"]);self.assertEqual("S-1-5-21-1-2-3",result["identity"]["machineSid"])
        self.assertEqual(before,{name:(self.root/"etc"/name).read_bytes() for name in before});self.assertEqual(self.before,self.smb.read_bytes())
        self.assertEqual("COMPLETE_LEFT",json.loads(self.lifecycle.journal.read_text())["phase"])

    def test_ordinary_source_wrong_scope_boot_or_missing_cipher_proof_blocks_join_before_any_remote_or_file_effect(self):
        original=self.lifecycle.source_provider
        for change in ({"serviceSourceStoppedVerified":False},{"serviceSourceStoppedVerified":"true"},{"bootId":str(uuid.uuid4())},{"scope":{**self.request,"maintenanceUuid":str(uuid.uuid4())}}):
            self.lifecycle.source_provider=lambda scope,fresh:{**original(scope,fresh),**change}
            with self.assertRaises(ValueError):self.lifecycle.join(self.request)
            self.assertEqual([],self.calls);self.assertFalse(self.lifecycle.machine.exists());self.assertFalse(self.lifecycle.journal.exists())

    def test_leave_missing_keytab_foreign_artifact_or_configuration_drift_blocks_before_remote_effect(self):
        self.lifecycle.join(self.request);self.calls.clear()
        keytab=self.root/"etc/krb5.keytab";raw=keytab.read_bytes();keytab.unlink()
        with self.assertRaises(ValueError):self.lifecycle.leave(self.request)
        self.assertEqual([],self.calls)
        keytab.write_bytes(raw);keytab.chmod(0o644)
        with self.assertRaises(ValueError):self.lifecycle.leave(self.request)
        self.assertEqual([],self.calls);keytab.chmod(0o600)
        (self.root/"etc/resolv.conf").write_text("foreign replacement")
        with self.assertRaises(ValueError):self.lifecycle.leave(self.request)
        self.assertEqual([],self.calls);self.assertEqual("JOINED",json.loads(self.lifecycle.state.read_text())["state"])

    def test_replaced_remote_computer_or_foreign_dns_address_blocks_before_any_delete(self):
        self.lifecycle.join(self.request);self.calls.clear();before=self.lifecycle.state.read_bytes()
        self.remote_sid="S-1-5-21-4-5-6-9999"
        with self.assertRaises(ValueError):self.lifecycle.leave(self.request)
        self.assertFalse(any("delete" in call or "leave" in call or "unregister" in call for call in self.calls));self.assertEqual(before,self.lifecycle.state.read_bytes())
        self.calls.clear();self.remote_sid="S-1-5-21-4-5-6-1001";self.foreign_dns=True
        with self.assertRaises(ValueError):self.lifecycle.leave(self.request)
        self.assertFalse(any("delete" in call or "leave" in call or "unregister" in call for call in self.calls));self.assertEqual(before,self.lifecycle.state.read_bytes())

    def test_foreign_ipv6_alias_and_private_config_validation_failure_never_claim_success(self):
        self.invalid_machine_configuration=True
        with self.assertRaises(ValueError):self.lifecycle.join(self.request)
        self.assertEqual("RECOVERY_REQUIRED",json.loads(self.lifecycle.journal.read_text())["phase"])
        self.assertFalse(any("join" in row or "setlocalsid" in row for row in self.calls))
        self.invalid_machine_configuration=False;self.lifecycle.machine.unlink()
        for name in ("krb5.conf","resolv.conf"):(self.root/"etc"/name).unlink()
        self.lifecycle.join(self.request);self.calls.clear();before=self.lifecycle.state.read_bytes();self.foreign_ipv6=True
        with self.assertRaises(ValueError):self.lifecycle.leave(self.request)
        self.assertFalse(any("delete" in row or "leave" in row or "unregister" in row for row in self.calls))
        self.assertEqual(before,self.lifecycle.state.read_bytes())

    def test_remote_leave_disabled_but_present_is_recovery_and_never_claims_cleanup(self):
        self.lifecycle.join(self.request);self.disabled_present=True
        with self.assertRaises(ValueError):self.lifecycle.leave(self.request)
        self.assertEqual("RECOVERY_REQUIRED",json.loads(self.lifecycle.journal.read_text())["phase"])
        self.assertEqual("JOINED",json.loads(self.lifecycle.state.read_text())["state"]);self.assertTrue(self.lifecycle.machine.exists())
        self.assertTrue((self.root/"etc/krb5.keytab").exists())

    def test_actual_cli_ordinary_leave_rejects_unheld_root_foreign_service_and_missing_source_cipher_before_mutation(self):
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        state=self.root/"native-state";state.mkdir(mode=0o700);marker=state/"template-maintenance.json"
        bins=self.root/"bin";bins.mkdir();mutation=self.root/"mutation-observed"
        for name in ("net","systemctl"):
            binary=bins/name;binary.write_text("#!/bin/sh\ntouch '"+str(mutation)+"'\nexit 99\n");binary.chmod(0o755)
        payload=self.root/"actual-leave.json";payload.write_text(json.dumps(self.request))
        env=dict(os.environ,PATH=str(bins)+":"+os.environ["PATH"],ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(state),
                 ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.config),ABLESTACK_STORAGE_GENERATION_DIR=str(self.gen),
                 ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.root/"native-writer"),ABLESTACK_STORAGE_LOG_FILE="/dev/null")
        service={key:self.request[key] for key in ("instanceUuid","maintenanceUuid","operationUuid","revision")}
        variants=(None,{"kind":"ROOT","scope":{**{key:service[key] for key in ("instanceUuid","operationUuid","revision")},"templateUpgradeUuid":str(uuid.uuid4())}},
                  {"kind":"SERVICE","bootHeld":True,"scope":{**service,"operationUuid":str(uuid.uuid4()),"maintenanceUuid":str(uuid.uuid4())}},
                  {"kind":"SERVICE","bootHeld":True,"scope":service})
        before={str(path):path.read_bytes() for path in (self.config,self.gen) for path in path.rglob("*") if path.is_file()}
        for value in variants:
            if value is None:
                if marker.exists():marker.unlink()
            else:marker.write_text(json.dumps(value));marker.chmod(0o600)
            result=subprocess.run(["bash",str(cli),"identity","domain","leave",str(payload)],env=env,capture_output=True,text=True,timeout=15)
            self.assertNotEqual(0,result.returncode,result.stderr);self.assertEqual("AD_LIFECYCLE_REJECTED",json.loads(result.stdout)["errorCode"])
            self.assertFalse(mutation.exists());self.assertFalse(self.lifecycle.journal.exists())
            self.assertEqual(before,{str(path):path.read_bytes() for path in (self.config,self.gen) for path in path.rglob("*") if path.is_file()})

    def test_new_instance_real_authenticated_original_decoder_preserves_distinct_target_sam_and_new_computer_sid(self):
        from TestStorageAdSemanticSource import synthetic_original_source
        original,payload,_=synthetic_original_source()
        request=self.clone_request();request.pop("sourceIdentity");request.update(original)
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        self.lifecycle.cli=str(cli);self.request=request;netbios=request["netbiosName"];hostname=request["dnsAliases"][0]["hostname"]
        frozen=self.source_proof(self.lifecycle.daemon.marker(request),True);self.lifecycle.source_provider=lambda scope,fresh:dict(frozen)
        oldrun=self.lifecycle.run
        def run(args,**kwargs):
            if args[:4]==[str(cli),"identity","capsule","semantic-original"]:
                fd=kwargs["pass_fds"][0]
                self.assertTrue(fcntl.fcntl(fd,fcntl.F_GET_SEALS)&fcntl.F_SEAL_WRITE)
                self.assertNotIn(original["originalSourceCredentialPrivateKey"]," ".join(args))
                return subprocess.run(args,**kwargs)
            result=oldrun(args,**kwargs)
            return subprocess.CompletedProcess(args,result.returncode,result.stdout.replace("server.ablestack.local",hostname).replace("SERVER",netbios),result.stderr)
        self.lifecycle.run=run
        result=self.lifecycle.join(request)
        self.assertEqual(frozen["publicLocalMachineSid"],result["identity"]["machineSid"]);self.assertNotEqual(payload["adIdentity"]["machineSid"],result["identity"]["machineSid"])
        self.assertNotEqual(payload["adIdentity"]["machineAccountSid"],result["identity"]["machineAccountSid"])
        state=json.loads(self.lifecycle.state.read_text());self.assertEqual(original["originalSourceAuthority"],state["semanticOriginalSourceAuthority"])
        self.assertEqual(self.before,self.smb.read_bytes());self.assertFalse(any("setlocalsid" in row for row in self.calls))
        for path in self.root.rglob("*"):
            if path.is_file():self.assertNotIn(original["originalSourceCredentialPrivateKey"].encode(),path.read_bytes())
        self.calls.clear();retry=self.lifecycle.join(request);self.assertTrue(retry["identityPreserved"]);self.assertFalse(any("join" in row for row in self.calls))

    def test_join_foreign_configured_name_or_missing_exact_sam_key_rejects_before_directory_or_file_effects(self):
        oldrun=self.lifecycle.run
        def run(args,**kwargs):
            if args==["testparm","-s","--parameter-name=netbios name"]:return subprocess.CompletedProcess(args,0,"FOREIGN","")
            return oldrun(args,**kwargs)
        self.lifecycle.run=run
        with self.assertRaisesRegex(ValueError,"actual captured target configuration"):self.lifecycle.join(self.request)
        self.assertFalse(self.lifecycle.machine.exists());self.assertFalse(self.lifecycle.journal.exists());self.assertEqual([],self.calls)
        self.lifecycle.run=oldrun;self.lifecycle.sid_reader=lambda name:(_ for _ in ()).throw(ValueError("missing exact target key"))
        with self.assertRaises(ValueError):self.lifecycle.join(self.request)
        self.assertFalse(self.lifecycle.machine.exists());self.assertFalse(self.lifecycle.journal.exists())
        self.assertFalse(any(row[0]!="testparm" for row in self.calls))

    def test_semantic_local_guard_requires_actual_fd9_named_flock_joined_authority_and_owned_restore_phase(self):
        joined=self.lifecycle.join(self.request);state=json.loads(self.lifecycle.state.read_text())
        identity={"schemaVersion":1,**{key:joined["identity"][key] for key in ("domain","realm","workgroup","netbiosName","machineSid","domainSid","machineAccountSid","dnsAliases","servicePrincipals","idmapPolicy")},"machineConfigurationSha256":state["machineConfigurationSha256"],"trustVerified":True}
        descriptor={"sourceInstanceUuid":self.request["instanceUuid"]}
        request={**self.request,"originalSourceAuthority":descriptor,"originalSourceCapsule":{},"originalSourceCredentialPrivateKey":"SYNTHETIC"}
        self.lifecycle.original_provider=lambda scoped:{"success":True,"scope":self.lifecycle.daemon.scope(request),"originalSourceAuthority":descriptor,"originalIdentity":identity}
        path=self.root/"actual-writer";fd=os.open(path,os.O_RDWR|os.O_CREAT|os.O_EXCL,0o600);saved=None
        try:
            try:saved=os.dup(9)
            except OSError:pass
            os.dup2(fd,9);fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9","ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(path)}):
                with self.assertRaisesRegex(ValueError,"owned restore journal"):self.lifecycle.semantic_local_guard(request)
                self.lifecycle.snapshot(request,"LOCAL_RESTORING",{})
                with patch("ad_lifecycle.ServiceIdentityCipher") as cipher:
                    cipher.return_value.read.return_value={"syntheticOpaqueCipher":True}
                    result=self.lifecycle.semantic_local_guard(request)
                self.assertEqual(self.lifecycle.daemon.scope(request),result["scope"]);self.assertEqual("S-1-5-21-1-2-3",result["targetLocalMachineSid"])
                self.quiescent=False
                with self.assertRaises(ValueError):self.lifecycle.semantic_local_guard(request)
                self.quiescent=True;fcntl.flock(9,fcntl.LOCK_UN)
                with self.assertRaisesRegex(ValueError,"exclusive"):self.lifecycle.semantic_local_guard(request)
        finally:
            os.close(fd)
            if saved is None:os.close(9)
            else:os.dup2(saved,9);os.close(saved)

    def test_actual_signed_semantic_local_guard_rejects_absent_native_writer_before_any_private_import(self):
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        payload=self.root/"guard.json";payload.write_text(json.dumps(self.request));before={str(p):p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        result=subprocess.run(["bash",str(cli),"identity","domain","semantic-local-guard",str(payload)],capture_output=True,text=True,timeout=15,
                              env={key:value for key,value in os.environ.items() if key!="ABLESTACK_STORAGE_WRITER_LOCK_FD"})
        self.assertNotEqual(0,result.returncode);self.assertEqual("AD_LIFECYCLE_REJECTED",json.loads(result.stdout)["errorCode"])
        self.assertEqual(before,{str(p):p.read_bytes() for p in self.root.rglob("*") if p.is_file()})

    def test_samevm_retained_identity_binds_the_machine_config_digest_inside_real_aead_before_start(self):
        from identity_capsule import encrypt,decrypt,validate_payload
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        result=self.lifecycle.join(self.request);state=json.loads(self.lifecycle.state.read_text())
        identity={"schemaVersion":1,**{key:result["identity"][key] for key in ("domain","realm","workgroup","netbiosName","machineSid","domainSid","machineAccountSid","servicePrincipals","trustVerified","idmapPolicy","dnsAliases")},
                  "machineConfigurationSha256":state["machineConfigurationSha256"]}
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        private=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        aad=self.request["instanceUuid"]+":"+self.request["operationUuid"]
        capsule=encrypt({"schemaVersion":1,"files":{},"accounts":{},"adIdentity":identity},public,aad)
        restored=decrypt(capsule,private,aad);validate_payload(restored);self.calls.clear()
        self.assertTrue(self.lifecycle.retain(self.request,restored["adIdentity"])["identityPreserved"])
        self.calls.clear()
        with self.assertRaises(ValueError):self.lifecycle.retain(self.request,{**restored["adIdentity"],"machineConfigurationSha256":"f"*64})
        self.assertFalse(any(call==["OWNED_DAEMON_START"] for call in self.calls));self.assertNotIn(private,str(capsule))

if __name__=="__main__":unittest.main()
