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

import base64
import ctypes
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
from samba_public_sid import PublicSidTdbData
from semantic_local_identity import SemanticRamTdb,semantic_samu_public,semantic_passdb_namespace,SemanticLocalIdentity,LOCAL_PASSDB


def synthetic_tdb(records):
    fd=os.memfd_create("synthetic-rid-tdb",os.MFD_CLOEXEC)
    lib=ctypes.CDLL("libtdb.so.1");lib.tdb_open.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_uint];lib.tdb_open.restype=ctypes.c_void_p
    lib.tdb_store.argtypes=[ctypes.c_void_p,PublicSidTdbData,PublicSidTdbData,ctypes.c_int];lib.tdb_close.argtypes=[ctypes.c_void_p]
    db=lib.tdb_open(("/proc/self/fd/"+str(fd)).encode(),0,0,os.O_CREAT|os.O_RDWR,0o600)
    if not db:raise ValueError("fixture tdb")
    try:
        for key,value in records.items():
            k=ctypes.create_string_buffer(key);v=ctypes.create_string_buffer(value)
            if lib.tdb_store(db,PublicSidTdbData(ctypes.cast(k,ctypes.c_void_p),len(key)),PublicSidTdbData(ctypes.cast(v,ctypes.c_void_p),len(value)),1):raise ValueError("fixture tdb store")
    finally:lib.tdb_close(db)
    try:os.lseek(fd,0,os.SEEK_SET);return os.read(fd,8*1024*1024)
    finally:os.close(fd)


def synthetic_samu(name,rid):
    strings=[name.encode()+bytes([0]),b"ORIGINAL"+bytes([0]),name.encode()+bytes([0])]+[bytes([0])]*9
    return bytes(28)+b"".join(struct.pack("<I",len(value))+value for value in strings)+struct.pack("<II",rid,513)+b"SYNTHETIC_PRIVATE_TAIL_NOT_LOGGED"


class StorageSemanticLocalIdentityTest(unittest.TestCase):
    def setUp(self):
        self.identity={"machineSid":"S-1-5-21-9-8-7","netbiosName":"ORIGINAL","workgroup":"ABLESTACK"}
        self.target="S-1-5-21-1-2-3";self.name="STOR1234567890";self.passwd="example:x:12000:12001::/nonexistent:/usr/sbin/nologin";self.group="storage:x:12001:"
        self.records={b"INFO/version"+bytes([0]):struct.pack("<I",4),b"INFO/minor_version"+bytes([0]):struct.pack("<I",0),b"NEXT_RID"+bytes([0]):struct.pack("<I",2002),
                      b"USER_example"+bytes([0]):synthetic_samu("example",2001),b"RID_000007d1"+bytes([0]):b"example"+bytes([0])}
        provenance=json.dumps({"schemaVersion":1,"accounts":{"/etc/passwd":[self.passwd],"/etc/group":[self.group]}}).encode()
        def item(data):return {"data":base64.b64encode(data).decode(),"uid":0,"gid":0,"mode":0o600}
        self.payload={"schemaVersion":1,"files":{LOCAL_PASSDB:item(synthetic_tdb(self.records)),"/etc/ablestack-storage/smb-local-account-provenance.json":item(provenance),
                      "/var/lib/samba/private/secrets.tdb":item(b"SOURCE_SAM_MUST_NOT_IMPORT"),"/etc/krb5.keytab":item(b"SOURCE_KEYTAB_MUST_NOT_IMPORT")},
                      "accounts":{"/etc/passwd":[self.passwd],"/etc/group":[self.group]}}
        self.applied=[];self.current={"/etc/passwd":b"root:x:0:0:root:/root:/bin/bash\n","/etc/group":b"root:x:0:\n"}
        def read(path):
            if path not in self.current:raise FileNotFoundError(path)
            return self.current[path],SimpleNamespace()
        def apply(payload):
            self.applied.append(payload)
            for path,rows in payload["accounts"].items():self.current[path]+=("\n".join(rows)+"\n").encode()
        def run(args,**kwargs):return subprocess.CompletedProcess(args,0,"---------------\nUnix username: example\nUser SID: "+self.target+"-2001\nPrimary Group SID: S-1-22-2-12001\n","")
        self.consumer=SemanticLocalIdentity(run,read,apply,lambda name:self.target)
    def test_real_tdbsam4_namespace_preserves_rid_and_private_tail_and_does_not_import_source_sam_or_ad_files(self):
        source=self.records[b"USER_example"+bytes([0])]
        result=self.consumer.restore_local({"payload":self.payload,"identity":self.identity},self.target,self.name)
        self.assertTrue(result["targetSamPreserved"]);self.assertEqual(12000,result["publicMappings"][0]["uid"]);self.assertEqual(12001,result["publicMappings"][0]["gid"])
        self.assertEqual(self.target+"-2001",result["publicMappings"][0]["userSid"])
        installed=self.applied[0]["files"];self.assertNotIn("/var/lib/samba/private/secrets.tdb",installed);self.assertNotIn("/etc/krb5.keytab",installed)
        db=SemanticRamTdb(base64.b64decode(installed[LOCAL_PASSDB]["data"]))
        try:
            restored=db.fetch(b"USER_example"+bytes([0]))
            self.assertTrue(restored.endswith(b"SYNTHETIC_PRIVATE_TAIL_NOT_LOGGED"));self.assertIn(self.name.encode()+bytes([0]),restored)
            self.assertEqual(source[-len(b"SYNTHETIC_PRIVATE_TAIL_NOT_LOGGED"):],restored[-len(b"SYNTHETIC_PRIVATE_TAIL_NOT_LOGGED"):])
        finally:db.close()
    def test_unowned_or_duplicate_rid_reverse_union_bad_version_and_counter_are_rejected_before_apply(self):
        variants=[{**self.records,b"INFO/version"+bytes([0]):struct.pack("<I",3)},
                  {**self.records,b"RID_000007d1"+bytes([0]):b"foreign"+bytes([0])},
                  {**self.records,b"NEXT_RID"+bytes([0]):struct.pack("<I",2001)},
                  {**self.records,b"USER_foreign"+bytes([0]):synthetic_samu("foreign",2001)}]
        for records in variants:
            self.payload["files"][LOCAL_PASSDB]["data"]=base64.b64encode(synthetic_tdb(records)).decode()
            with self.subTest(records=len(records)),self.assertRaises(ValueError):self.consumer.restore_local({"payload":self.payload,"identity":self.identity},self.target,self.name)
            self.assertEqual([],self.applied)
    def test_numeric_uid_or_gid_union_collision_and_foreign_target_sam_reject_before_apply(self):
        for path,line in (("/etc/passwd",b"foreign:x:12000:12001::/x:/bin/false\n"),("/etc/group",b"foreign:x:12001:\n")):
            old=self.current[path];self.current[path]+=line
            with self.assertRaises(ValueError):self.consumer.restore_local({"payload":self.payload,"identity":self.identity},self.target,self.name)
            self.current[path]=old;self.assertEqual([],self.applied)
        self.consumer.sid_reader=lambda name:"S-1-5-21-99-99-99"
        with self.assertRaises(ValueError):self.consumer.restore_local({"payload":self.payload,"identity":self.identity},self.target,self.name)
        self.assertEqual([],self.applied)
    def test_public_serializer_rejects_truncated_foreign_username_domain_and_rid_without_parsing_private_tail(self):
        original=synthetic_samu("example",2001)
        for value in (original[:30],synthetic_samu("foreign",2001),synthetic_samu("example",500)):
            with self.assertRaises(ValueError):semantic_samu_public(value,"example",self.identity,self.name)
        bad_identity={**self.identity,"netbiosName":"FOREIGN","workgroup":"FOREIGN"}
        with self.assertRaises(ValueError):semantic_samu_public(original,"example",bad_identity,self.name)

if __name__=="__main__":unittest.main()
