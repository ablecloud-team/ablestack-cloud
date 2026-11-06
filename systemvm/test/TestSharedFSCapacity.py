#!/usr/bin/env python3
# Licensed under the Apache License, Version 2.0. See NOTICE.
import contextlib
import datetime
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import types
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "debian/usr/local/bin/ablestack-storagectl"
MONITOR = ROOT / "debian/usr/local/bin/ablestack-storage-monitor"
DATA = "e070a0aa-dbf5-48c0-83e0-09f874446277"
FS = "9c8ddfe9-3e23-46f0-9476-5c794b004841"
GIB = 1024 ** 3

def python_body(source, function):
    return source.read_text().split(function + "() {", 1)[1].split("<<'PY'", 1)[1].split("\n", 1)[1].split("\nPY\n}", 1)[0]

class SharedFSCapacityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="sharedfs-capacity-")
        self.root = Path(self.temp.name)
        self.request = self.root / "request.json"
        self.cache = {
            "schemaVersion": 1, "capacitySnapshotVersion": 1, "generatedEpoch": time.time(),
            "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "staleAfterSeconds": 30,
            "bootId": Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
            "blockDevices": [
                {"name":"sda","path":"/dev/sda","type":"disk","serial":"root-volume","size":5*GIB,"children":[{"name":"sda1","path":"/dev/sda1","type":"part","fstype":"ext4","uuid":"root-fs","mountpoint":"/","maj:min":"8:1"}]},
                {"name":"sdb","path":"/dev/sdb","type":"disk","serial":"e070a0aadbf548c083e0","size":10*GIB,"fstype":"xfs","uuid":FS,"maj:min":"8:16","mountpoint":"/srv/ablestack-storage/volumes/"+DATA},
                {"name":"sdc","path":"/dev/sdc","type":"disk","serial":"raw-volume","size":4*GIB,"fstype":None,"uuid":None,"maj:min":"8:32","mountpoint":None}],
            "fileSystems":[{"identity":FS+":2064","uuid":FS,"device":2064,"majorMinor":"8:16","devicePath":"/dev/sdb","filesystem":"xfs","totalBytes":10*GIB,"usedBytes":108703744,"availableBytes":10*GIB-108703744,"mounts":[{"target":"/srv/ablestack-storage/volumes/"+DATA,"fsroot":"/","inode":128}]}]}
        self.payload=[{"uuid":DATA,"size":10*GIB}]
    def tearDown(self):
        self.temp.cleanup()
    def run_inventory(self,payload=None):
        (self.root/"capacity.json").write_text(json.dumps(self.cache))
        self.request.write_text(json.dumps({"capacityOnly":True,"volumes":self.payload if payload is None else payload}))
        result=subprocess.run(["python3","-c",python_body(CLI,"runtime_capacity_inventory"),str(self.request),str(self.root)],
            capture_output=True,text=True,check=True,env=dict(os.environ,ABLESTACK_STORAGE_HELPER_ROOT=str(ROOT/"debian/usr/local/lib/ablestack-storage")))
        return json.loads(result.stdout)
    def test_actual_mounted_input_retains_identity_and_usage(self):
        result=self.run_inventory()
        self.assertEqual("EXACT",result["observations"][0]["mappingStatus"])
        self.assertEqual(108703744,result["observations"][0]["filesystems"][0]["usedBytes"])
    def test_wrong_uuid_root_and_size_do_not_bypass_protection(self):
        for uuid in ["wrong-volume","root-volume"]:
            self.assertEqual("UNMAPPED",self.run_inventory([{"uuid":uuid,"size":5*GIB}])["observations"][0]["mappingStatus"])
        self.assertEqual("TRANSITION",self.run_inventory([{"uuid":DATA,"size":GIB}])["status"])
    def test_mount_source_needs_complete_filesystem_identity(self):
        for key,value in [("uuid","wrong"),("device",999),("majorMinor","8:17"),("identity","wrong")]:
            with self.subTest(field=key):
                original=self.cache["fileSystems"][0][key]
                self.cache["fileSystems"][0][key]=value
                self.assertEqual("UNMAPPED",self.run_inventory()["observations"][0]["mappingStatus"])
                self.cache["fileSystems"][0][key]=original
    def test_raw_stale_zero_and_old_runtime_are_distinct(self):
        raw=self.run_inventory([{"uuid":"raw-volume","size":4*GIB}])["observations"][0]
        self.assertEqual("UNSUPPORTED",raw["status"]);self.assertEqual([],raw["filesystems"])
        self.cache["generatedEpoch"]=time.time()-1000
        self.assertEqual("STALE",self.run_inventory()["status"])
        self.cache["generatedEpoch"]=time.time();self.cache["fileSystems"][0]["usedBytes"]=0
        self.assertEqual(0,self.run_inventory()["observations"][0]["filesystems"][0]["usedBytes"])
        self.cache.pop("capacitySnapshotVersion")
        self.assertEqual("UNSUPPORTED",self.run_inventory()["status"])
    def test_wrong_boot_future_time_and_empty_cache_are_unavailable(self):
        self.cache["bootId"]="wrong"
        self.assertEqual("UNAVAILABLE",self.run_inventory()["status"])
        self.cache["bootId"]=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        self.cache["generatedEpoch"]=time.time()+1000
        self.assertEqual("UNAVAILABLE",self.run_inventory()["status"])
        self.cache={}
        self.assertEqual("UNSUPPORTED",self.run_inventory()["status"])
    def test_monitor_uses_one_device_and_mount_snapshot_and_one_statvfs_per_fs(self):
        mounts=[{"target":"/srv/ablestack-storage/volumes/"+DATA,"source":"/dev/sdb","fstype":"xfs","uuid":FS,"fsroot":"/","maj:min":"8:16"},
                {"target":"/export/alias","source":"/dev/sdb[/nested]","fstype":"xfs","uuid":FS,"fsroot":"/nested","maj:min":"8:16"}]
        def command(args,**kwargs):
            value={"blockdevices":self.cache["blockDevices"]} if args[0]=="lsblk" else {"filesystems":mounts}
            return types.SimpleNamespace(stdout=json.dumps(value))
        output=io.StringIO()
        fs=types.SimpleNamespace(f_blocks=1000,f_bfree=900,f_bavail=890,f_frsize=4096)
        with mock.patch("subprocess.run",side_effect=command) as run, mock.patch("os.stat",return_value=types.SimpleNamespace(st_dev=2064,st_ino=128)), mock.patch("os.statvfs",return_value=fs) as statvfs, contextlib.redirect_stdout(output):
            exec(compile(python_body(MONITOR,"collect_capacity"),str(MONITOR),"exec"),{})
        result=json.loads(output.getvalue())
        self.assertEqual(2,run.call_count);self.assertEqual(1,statvfs.call_count)
        self.assertEqual(2,len(result["capacity"]));self.assertEqual(1,len(result["fileSystems"]))
        self.assertEqual(2,len(result["fileSystems"][0]["mounts"]))

if __name__ == "__main__":
    unittest.main()
