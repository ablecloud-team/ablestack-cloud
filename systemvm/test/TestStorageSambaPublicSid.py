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

import ctypes
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
import samba_public_sid as module


class StorageSambaPublicSidTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.root.chmod(0o700);self.path=self.root/"secrets.tdb"
        self.lib=ctypes.CDLL("libtdb.so.1")
        self.lib.tdb_open.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_uint];self.lib.tdb_open.restype=ctypes.c_void_p
        self.lib.tdb_store.argtypes=[ctypes.c_void_p,module.PublicSidTdbData,module.PublicSidTdbData,ctypes.c_int];self.lib.tdb_store.restype=ctypes.c_int
        self.lib.tdb_close.argtypes=[ctypes.c_void_p];self.lib.tdb_close.restype=ctypes.c_int
        self.sid=bytes([1,4,0,0,0,0,0,5])+struct.pack("<15I",21,0x01020304,0x11223344,4294967295,*([0]*11))
        self.put({b"SECRETS/SID/SERVER":self.sid,b"SECRETS/SID/FOREIGN":bytes([1,4,0,0,0,0,0,5])+struct.pack("<15I",21,9,9,9,*([0]*11)),
                  b"SECRETS/MACHINE_PASSWORD/ABLESTACK":b"SYNTHETIC_PRIVATE_VALUE_DO_NOT_READ"})
    def put(self,rows):
        database=self.lib.tdb_open(os.fsencode(self.path),0,0,os.O_RDWR|os.O_CREAT,0o600)
        self.assertTrue(database)
        try:
            for key,data in rows.items():
                k=ctypes.create_string_buffer(key);v=ctypes.create_string_buffer(data)
                result=self.lib.tdb_store(database,module.PublicSidTdbData(ctypes.cast(k,ctypes.c_void_p),len(key)),
                                          module.PublicSidTdbData(ctypes.cast(v,ctypes.c_void_p),len(data)),1)
                self.assertEqual(0,result)
        finally:self.lib.tdb_close(database)
        self.path.chmod(0o600)
    def test_real_libtdb_reads_only_existing_exact_public_sid_and_preserves_all_database_bytes(self):
        before=self.path.read_bytes();metadata=self.path.stat();calls=[]
        class Function:
            def __init__(self,target,check=None):self.target=target;self.check=check
            def __call__(self,*args):
                if self.check:self.check(args)
                return self.target(*args)
        class Library:pass
        library=Library()
        library.tdb_open=Function(self.lib.tdb_open,lambda args:self.assertEqual((0,0,os.O_RDONLY,0),args[1:]))
        self.lib.tdb_fetch.argtypes=[ctypes.c_void_p,module.PublicSidTdbData];self.lib.tdb_fetch.restype=module.PublicSidTdbData
        library.tdb_fetch=Function(self.lib.tdb_fetch,lambda args:calls.append(ctypes.string_at(args[1].dptr,args[1].dsize)))
        library.tdb_close=Function(self.lib.tdb_close)
        result=module.samba_public_sid("SERVER",self.path,library)
        self.assertEqual("S-1-5-21-16909060-287454020-4294967295",result)
        self.assertEqual([b"SECRETS/SID/SERVER"],calls);self.assertEqual(before,self.path.read_bytes());self.assertEqual(metadata.st_mtime_ns,self.path.stat().st_mtime_ns)
    def test_absent_database_or_missing_exact_machine_key_cannot_create_sid_or_files(self):
        before=self.path.read_bytes()
        with self.assertRaises(ValueError):module.samba_public_sid("MISSING",self.path)
        self.assertEqual(before,self.path.read_bytes())
        absent=self.root/"absent.tdb"
        with self.assertRaises(FileNotFoundError):module.samba_public_sid("SERVER",absent)
        self.assertFalse(absent.exists());self.assertEqual(before,self.path.read_bytes())
    def test_malformed_binary_revision_authority_rid_shape_or_endianness_is_rejected(self):
        malformed=(b"",self.sid[:-1],bytes([2])+self.sid[1:],self.sid[:1]+bytes([3])+self.sid[2:],
                   self.sid[:7]+bytes([7])+self.sid[8:],self.sid[:8]+struct.pack(">15I",21,1,2,3,*([0]*11)))
        for row in malformed:
            self.put({b"SECRETS/SID/SERVER":row})
            with self.assertRaises(ValueError):module.samba_public_sid("SERVER",self.path)
    def test_unprotected_symlink_or_foreign_machine_name_is_rejected_before_library_open(self):
        for name in ("server","FOREIGN/NAME","SERVER\nOTHER",True,None):
            with self.assertRaises(ValueError):module.samba_public_sid(name,self.path)
        self.path.chmod(0o644)
        with self.assertRaises(ValueError):module.samba_public_sid("SERVER",self.path)
        self.path.chmod(0o600);alias=self.root/"alias.tdb";alias.symlink_to(self.path)
        with self.assertRaises(ValueError):module.samba_public_sid("SERVER",alias)
    def test_named_database_replacement_during_exact_fetch_is_rejected(self):
        original=self.lib.tdb_fetch;original.argtypes=[ctypes.c_void_p,module.PublicSidTdbData];original.restype=module.PublicSidTdbData
        class Function:
            def __init__(self,target):self.target=target
            def __call__(self,*args):return self.target(*args)
        class Library:pass
        library=Library();library.tdb_open=Function(self.lib.tdb_open);library.tdb_close=Function(self.lib.tdb_close)
        def replaced(*args):
            result=original(*args);replacement=self.root/"replacement.tdb";replacement.write_bytes(self.path.read_bytes());replacement.chmod(0o600);os.replace(replacement,self.path);return result
        library.tdb_fetch=Function(replaced)
        with self.assertRaises(ValueError):module.samba_public_sid("SERVER",self.path,library)


if __name__=="__main__":unittest.main()
