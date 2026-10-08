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
import fcntl,os
import sys,subprocess,unittest,time
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import ad_identity as module

class StorageAdIdentityTest(unittest.TestCase):
    def test_domain_dns_and_principal_are_bounded_and_cannot_inject_configs(self):
        self.assertEqual('ablestack.local',module.domain_name('ABLESTACK.local'))
        self.assertEqual(['10.10.13.100','10.10.13.101'],module.dns_addresses('10.10.13.100 10.10.13.101,10.10.13.100'))
        self.assertEqual('ABLESTACK'+chr(92)+'User',module.ad_principal('User@ablestack.local','ablestack.local','ABLESTACK'))
        for value in ('ablestack.local\nsearch foreign','-bad.local','a..local',' localhost','a/'+'.local'):
            with self.assertRaises(ValueError):module.domain_name(value)
        for value in ('FOREIGN'+chr(92)+'User','User@foreign.local','User\nroot'):
            with self.assertRaises(ValueError):module.ad_principal(value,'ablestack.local','ABLESTACK')

    def test_ad_numeric_mapping_requires_exact_type_and_reverse_sid(self):
        identifier='S-1-5-21-1-2-3-1001';calls=[]
        def run(args,**kwargs):
            calls.append(args)
            output=identifier+' SID_USER (1)' if args[1]=='--name-to-sid' else '10001' if args[1]=='--sid-to-uid' else identifier
            return subprocess.CompletedProcess(args,0,output,'')
        result=module.resolve_ad_principal('AD_USER','User','ablestack.local','ABLESTACK',run)
        self.assertEqual(10001,result['numericId']);self.assertEqual(identifier,result['sid']);self.assertEqual(3,len(calls))
        def wrong(args,**kwargs):return subprocess.CompletedProcess(args,0,identifier+' SID_DOM_GRP (2)' if args[1]=='--name-to-sid' else '0','')
        with self.assertRaises(ValueError):module.resolve_ad_principal('AD_USER','User','ablestack.local','ABLESTACK',wrong)

    def test_machine_sid_keytab_and_service_principals_are_scoped_public_metadata(self):
        identities=module.machine_sids('SID for local machine SERVER is: S-1-5-21-1-2-3\nSID for domain ABLESTACK is: S-1-5-21-4-5-6\n')
        self.assertNotEqual(identities['machineSid'],identities['domainSid'])
        expected=['cifs/server.ablestack.local','host/server.ablestack.local']
        output='Keytab name: FILE:/etc/krb5.keytab\n KVNO Principal\n---- ---------\n  2 cifs/server.ablestack.local@ABLESTACK.LOCAL\n  2 host/server.ablestack.local@ABLESTACK.LOCAL\n  2 SERVER$@ABLESTACK.LOCAL\n'
        self.assertTrue(module.keytab_principals(output,'ablestack.local',expected)['requiredServicePrincipalsVerified'])
        with self.assertRaises(ValueError):module.keytab_principals(output.replace('@ABLESTACK.LOCAL','@FOREIGN.LOCAL'),'ablestack.local',expected)
        with self.assertRaises(ValueError):module.service_principal('cifs/foreign.example','ablestack.local')
        with self.assertRaises(ValueError):module.service_principal('cifs/server.ablestack.local\nroot','ablestack.local')

    def test_actual_fixed_probe_uses_machine_trust_and_never_keytab_secret_dump_or_password_argv(self):
        calls=[]
        def run(args,**kwargs):
            calls.append(args)
            output='Join is OK' if args[1]=='ads' else 'SID for local machine SERVER is: S-1-5-21-1-2-3\nSID for domain ABLESTACK is: S-1-5-21-4-5-6\n' if args[1]=='getdomainsid' else '2 cifs/server.ablestack.local@ABLESTACK.LOCAL\n'
            return subprocess.CompletedProcess(args,0,output,'')
        result=module.AdIdentityProbe(run).verify('ablestack.local',['cifs/server.ablestack.local'])
        self.assertTrue(result['trustVerified']);self.assertIn('--machine-pass',calls[0])
        self.assertEqual(['klist','-k','/etc/krb5.keytab'],calls[-1]);self.assertFalse(any('-K' in row or '--password' in row for row in calls))

    def test_keytab_accepts_only_own_short_names_and_own_machine_account(self):
        wanted=["cifs/server.ablestack.local","host/server.ablestack.local"]
        output="2 cifs/server.ablestack.local@ABLESTACK.LOCAL\n2 host/server.ablestack.local@ABLESTACK.LOCAL\n2 host/SERVER@ABLESTACK.LOCAL\n2 SERVER$@ABLESTACK.LOCAL\n2 RestrictedKrbHost/SERVER@ABLESTACK.LOCAL\n2 RestrictedKrbHost/server.ablestack.local@ABLESTACK.LOCAL\n"
        self.assertTrue(module.keytab_principals(output,"ablestack.local",wanted,"SERVER")["requiredServicePrincipalsVerified"])
        for invalid in ("FOREIGN$", "host/FOREIGN"):
            with self.assertRaises(ValueError): module.keytab_principals(output+"2 "+invalid+"@ABLESTACK.LOCAL\n","ablestack.local",wanted,"SERVER")

    def test_real_ad_child_timeout_is_bounded_and_private_output_is_not_exposed(self):
        started=time.monotonic()
        with self.assertRaises(TimeoutError):
            module.bounded_ad_run([sys.executable,"-c","import time;time.sleep(10)"],timeout=.05)
        self.assertLess(time.monotonic()-started,1.5)
        result=module.bounded_ad_run([sys.executable,"-c","print('public metadata')"],timeout=2)
        self.assertEqual("public metadata\n",result.stdout);self.assertEqual(0,result.returncode)

    def test_joined_idmap_ranges_and_domain_sid_mapping_are_exact_and_cannot_fallback(self):
        def run(args,**kwargs):
            name=args[-1].split("=",1)[1]
            output="tdb" if name.endswith("backend") and "*" in name else "rid" if name.endswith("backend") else "10000-60000" if "*" in name else "1000000-1999999" if name.endswith("range") else "0"
            return subprocess.CompletedProcess(args,0,output+"\n","")
        result=module.idmap_policy(run,"ABLESTACK")
        self.assertEqual({"backend":"rid","range":[1000000,1999999],"baseRid":0},result["domain"])
        identifier="S-1-5-21-1-2-3-1001"
        def foreign(args,**kwargs):
            return subprocess.CompletedProcess(args,0,identifier+" SID_USER (1)","")
        with self.assertRaises(ValueError):module.resolve_ad_principal("AD_USER","User","ablestack.local","ABLESTACK",foreign,expected_domain_sid="S-1-5-21-4-5-6")
        def overlap(args,**kwargs):
            return subprocess.CompletedProcess(args,0,"tdb" if args[-1].endswith("backend") else "10000-999999","")
        with self.assertRaises(ValueError):module.idmap_policy(overlap,"ABLESTACK")

    def test_ad_configuration_preserves_existing_shares_and_private_database_binding(self):
        source='[global]\n passdb backend = tdbsam\n private dir = /var/lib/samba/private\n security = user\n[existing]\n path = /srv/volume/existing\n valid users = olduser\n'
        result=module.ad_configuration('ablestack.local','ABLESTACK','STORAGEVM',source,['10.10.13.100'])
        self.assertIn('[existing]\n path = /srv/volume/existing\n valid users = olduser',result['smbConfiguration'])
        self.assertIn('private dir = /var/lib/samba/private',result['smbConfiguration'])
        self.assertIn('security = ADS',result['smbConfiguration']);self.assertNotIn('security = user',result['smbConfiguration'])
        with self.assertRaises(ValueError):module.ad_configuration('ablestack.local','ABLESTACK','SERVER\nroot',source,['10.10.13.100'])

    def test_ad_secret_transport_is_sealed_memfd_and_never_argv_or_named_file(self):
        observed=[]
        def run(args,**kwargs):
            self.assertFalse(any('SYNTHETIC_SECRET' in argument for argument in args))
            descriptor=kwargs['pass_fds'][-1];self.assertTrue(fcntl.fcntl(descriptor,fcntl.F_GET_SEALS)&fcntl.F_SEAL_WRITE)
            content=os.read(descriptor,8192).decode();self.assertIn('password = SYNTHETIC_SECRET',content)
            observed.append(descriptor)
            return subprocess.CompletedProcess(args,0,'','')
        result=module.credential_command(['net','ads','join'],'Administrator','SYNTHETIC_SECRET','ablestack.local',run)
        self.assertTrue(result['protectedCredentialTransport'])
        with self.assertRaises(OSError):os.fstat(observed[0])
        with self.assertRaises(ValueError):module.credential_command(['net','ads','join'],'Administrator','bad\ncredential','ablestack.local',run)

class StorageAdIdentityRpcTest(unittest.TestCase):
    def setUp(self):
        import json,tempfile,uuid
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.base=Path(self.temp.name);self.config=self.base/"config";self.config.mkdir(mode=0o700);self.gen=self.base/"generation";self.gen.mkdir(mode=0o700)
        self.scope={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":2}
        self.sids={"machineSid":"S-1-5-21-1-2-3","domainSid":"S-1-5-21-4-5-6","machineAccountSid":"S-1-5-21-4-5-6-1001"}
        self.mapping={"default":{"backend":"tdb","range":[10000,60000]},"domain":{"backend":"rid","range":[1000000,1999999],"baseRid":0}}
        self.state={"state":"JOINED","domainName":"ablestack.local","workgroup":"ABLESTACK","netbiosName":"SERVER","identityReceipt":self.sids,
                    "servicePrincipals":["cifs/server.ablestack.local","host/server.ablestack.local"],"idmapPolicy":self.mapping,
                    "dnsServers":["10.10.13.100"],"dnsAliases":[{"hostname":"server.ablestack.local","addresses":["10.10.13.240","10.10.13.241"]}]}
        self.calls=[];self.foreign_dns=False;self.foreign_sid=False
        self.write(self.gen/"current.json",{**self.scope,"revision":1});self.write(self.config/"smb-domain.json",self.state)
        self.rpc=module.AdIdentityRpc(self.run_command,self.config,self.gen)
    def write(self,path,value):
        import json
        path.write_text(json.dumps(value));path.chmod(0o600)
    def run_command(self,args,**kwargs):
        self.calls.append(args)
        if args[:3]==["net","ads","testjoin"]:output="Join is OK"
        elif args[:2]==["net","getdomainsid"]:output="SID for local machine SERVER is: "+self.sids["machineSid"]+"\nSID for domain ABLESTACK is: "+self.sids["domainSid"]+"\n"
        elif args[0]=="klist":output="2 cifs/server.ablestack.local@ABLESTACK.LOCAL\n2 host/server.ablestack.local@ABLESTACK.LOCAL\n2 SERVER$@ABLESTACK.LOCAL\n"
        elif args[:3]==["net","ads","setspn"]:output="Registered SPNs:\n  cifs/server.ablestack.local\n  host/server.ablestack.local\n"
        elif args[0]=="dig":output="10.10.13.240\n" if self.foreign_dns else "10.10.13.240\n10.10.13.241\n"
        elif args[0]=="testparm":
            name=args[-1].split("=",1)[1];output="tdb" if name.endswith("backend") and "*" in name else "rid" if name.endswith("backend") else "10000-60000" if "*" in name else "1000000-1999999" if name.endswith("range") else "0"
        elif args[0]=="wbinfo":
            if args[1]=="--name-to-sid":output=(self.sids["machineAccountSid"] if args[-1].endswith("$") else self.sids["domainSid"]+"-2001")+" SID_USER (1)"
            elif args[1]=="--sid-to-uid":output="1001001"
            elif args[1]=="--uid-to-sid":output=("S-1-5-21-9-9-9-2001" if self.foreign_sid else self.sids["domainSid"]+"-2001")
            else:raise AssertionError(args)
        else:raise AssertionError(args)
        return subprocess.CompletedProcess(args,0,output,"")
    def test_joined_attestation_includes_fresh_trust_computer_sid_dns_spn_idmap_and_creates_no_files(self):
        before={str(path):path.read_bytes() for path in self.base.rglob("*.json")}
        result=self.rpc.inspect(self.scope)
        self.assertTrue(result["identityVerified"]);self.assertTrue(result["trustVerified"]);self.assertTrue(result["dnsAliasesVerified"])
        self.assertEqual(self.sids["machineAccountSid"],result["machineAccountSid"]);self.assertEqual(self.mapping,result["idmapPolicy"])
        self.assertFalse(result["sideEffects"]);self.assertEqual(before,{str(path):path.read_bytes() for path in self.base.rglob("*.json")})
    def test_ad_user_resolution_requires_exact_pinned_domain_and_reverse_mapping_without_fallback(self):
        request={**self.scope,"principalType":"AD_USER","principal":"User","expectedRealm":"ABLESTACK.LOCAL","expectedDomainSid":self.sids["domainSid"]}
        result=self.rpc.resolve(request);self.assertEqual("ABLESTACK"+chr(92)+"User",result["qualifiedName"])
        self.assertEqual(1001001,result["numericId"]);self.assertTrue(result["reverseVerified"]);self.assertEqual(1,result["sidType"]);self.assertEqual("SID_USER",result["sidTypeName"]);self.assertEqual("AD_USER",result["principalType"])
        self.foreign_sid=True
        with self.assertRaises(ValueError):self.rpc.resolve(request)
        with self.assertRaises(ValueError):self.rpc.resolve({**request,"expectedRealm":"FOREIGN.LOCAL"})
    def test_missing_joined_receipt_foreign_writer_or_dns_alias_change_never_claims_joined(self):
        self.foreign_dns=True
        with self.assertRaises(ValueError):self.rpc.inspect(self.scope)
        self.foreign_dns=False;self.state.pop("identityReceipt");self.write(self.config/"smb-domain.json",self.state)
        with self.assertRaises(ValueError):self.rpc.inspect(self.scope)
        (self.config/"smb-domain.json").unlink();result=self.rpc.inspect(self.scope)
        self.assertEqual("NOT_JOINED",result["joinState"]);self.assertFalse(result["identityVerified"])
        import uuid
        self.write(self.gen/"pending.json",{**self.scope,"operationUuid":str(uuid.uuid4())})
        with self.assertRaises(ValueError):self.rpc.inspect(self.scope)
    def test_actual_signed_rpc_not_joined_is_readonly_and_foreign_pending_fails(self):
        import json,uuid
        (self.config/"smb-domain.json").unlink()
        cli=LIB.parent.parent/"bin/ablestack-storagectl"
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        environment=dict(os.environ,ABLESTACK_STORAGE_GENERATION_DIR=str(self.gen),ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.config))
        def native():return subprocess.run([str(cli),"identity","domain","inspect","/dev/stdin"],input=json.dumps(self.scope),env=environment,capture_output=True,text=True,timeout=10)
        before=list(self.base.rglob("*"));result=native();self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual("NOT_JOINED",json.loads(result.stdout)["joinState"]);self.assertEqual(before,list(self.base.rglob("*")))
        self.write(self.gen/"pending.json",{**self.scope,"operationUuid":str(uuid.uuid4())});result=native()
        self.assertNotEqual(0,result.returncode);self.assertEqual("AD_IDENTITY_ATTESTATION_REJECTED",json.loads(result.stdout)["errorCode"])

if __name__=='__main__':unittest.main()
