#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Opt-in SystemVM path-binding regression using a mounted SPARSE DATA filesystem.
Private mount namespace only; no production service files, ACLs or disks are changed.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import uuid

def run(*args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, text=True).strip()
def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--volume", required=True)
    parser.add_argument("--helper", required=True)
    parser.add_argument("--provisioning", choices=("SPARSE","FAT"), required=True)
    args=parser.parse_args()
    assert os.geteuid()==0
    volume=Path(args.volume)
    assert volume.parent==Path("/srv/ablestack-storage/volumes")
    assert run("findmnt","-n","-M",str(volume),"-o","FSTYPE") in ("xfs","ext4")
    run("mount","--make-rprivate","/")
    sys.path.insert(0,args.helper)
    from shared_directory_policy import FileSharePathBindings, PolicyError
    control=Path(tempfile.mkdtemp(prefix="epic1455-native-",dir="/var/tmp"))
    nonce=".epic1455-"+uuid.uuid4().hex
    owned=volume/nonce
    checks=[]
    engine=FileSharePathBindings(root=control/"bindings",journal=control/"private"/"paths.json")
    row={"uuid":str(uuid.uuid4()),"state":"Ready","config":{"pathBindingVersion":1,
        "volumeMountPath":str(volume),"relativeSharePath":nonce+"/projects/team",
        "backingPath":str(owned/"projects/team"),"createDirectory":True}}
    payload={"instanceUuid":str(uuid.uuid4()),"operationid":str(uuid.uuid4()),
             "candidate":{"nfs export apply":{"exports":[row]}}}
    mounts=[]
    def passed(name):checks.append({"name":name,"passed":True})
    def blocked(name,call,expected):
        try:call();raise AssertionError(name+" unexpectedly allowed")
        except PolicyError as error:assert error.code==expected,(name,error.code);passed(name)
    try:
        engine.begin(payload)
        binding=engine.ensure(row,"NFS");mounts.append(Path(binding["anchor"]))
        physical=Path(row["config"]["backingPath"])
        assert binding["created"] and physical.stat().st_ino==Path(binding["anchor"]).stat().st_ino
        (physical/"seed").write_bytes(b"epic1455-native-data")
        before=physical.stat()
        passed("real findmnt UUID, FD walk, mkdir and bind anchor")
        engine.verify(payload["candidate"])
        assert row["config"]["pathIdentity"]==engine.journal_value()["bindings"][row["uuid"]]["pathIdentity"]
        passed("stable identity receipt and verification")
        # Parent writer replaces the path after validation; the anchor must keep the original inode.
        original=physical.with_name("original");physical.rename(original)
        outside=owned/"outside";outside.mkdir();(outside/"seed").write_bytes(b"outside")
        physical.symlink_to(outside)
        assert (Path(binding["anchor"])/"seed").read_bytes()==b"epic1455-native-data"
        blocked("parent rename/symlink cannot redirect service root",lambda:engine.inspect(row),"DIRECTORY_PATH_SYMLINK")
        physical.unlink();original.rename(physical)
        # Same-device bind crosses a mount ID while st_dev remains equal.
        boundary=physical/"boundary";boundary.mkdir()
        run("mount","--bind",str(outside),str(boundary));mounts.append(boundary)
        probe=copy.deepcopy(row);probe["config"]["relativeSharePath"]+="/boundary"
        probe["config"]["backingPath"]+="/boundary";probe["config"].pop("pathIdentity")
        blocked("bind mount boundary",lambda:engine.inspect(probe),"DIRECTORY_MOUNT_BOUNDARY")
        run("umount",str(boundary));mounts.remove(boundary);boundary.rmdir()
        # Simulate a cleared /run mount: boot must recreate only from durable exact identity.
        run("umount",binding["anchor"]);mounts.remove(Path(binding["anchor"]))
        boot=FileSharePathBindings(root=control/"bindings")
        recreated=boot.ensure(row,"NFS");mounts.append(Path(recreated["anchor"]))
        assert not recreated["created"]
        assert before.st_ino==Path(recreated["anchor"]).stat().st_ino
        assert (Path(recreated["anchor"])/"seed").read_bytes()==b"epic1455-native-data"
        passed("boot rebind preserves inode, data and mode without mkdir")
        # Expected failure compensation retains a nonempty newly created target.
        retained=engine.rollback();mounts.clear()
        assert str(physical) in retained and (physical/"seed").read_bytes()==b"epic1455-native-data"
        passed("rollback retains client data and cleans only owned bind")
        assert physical.stat().st_uid==before.st_uid and physical.stat().st_mode==before.st_mode
        passed("owner and permission preservation")
        # The real adapter must create and initialize a deferred NFS root in one operation.
        import configuration_transaction as tx
        class Deferred(tx.RuntimeAdapter):
            def path_bindings(self, restoring=False):
                return FileSharePathBindings(root=control/"tx-bindings", journal=self.private/"pathbindings.json", restoring=restoring)
            def run(self, *args): raise AssertionError("deferred creation published a service")
        deferred=copy.deepcopy(row);deferred["uuid"]=str(uuid.uuid4())
        deferred["config"].pop("pathIdentity")
        deferred["config"]["relativeSharePath"]=nonce+"/deferred"
        deferred["config"]["backingPath"]=str(owned/"deferred")
        op=str(uuid.uuid4())
        value={"instanceUuid":payload["instanceUuid"],"posixOnly":True,"configurationOperationId":op,"exports":[deferred],
               "posixIntent":{"version":1,"targetUuid":deferred["uuid"],"create":True,"fields":{}}}
        request={"instanceUuid":payload["instanceUuid"],"operationid":op,"baseRevision":0,"targetRevision":1,
                 "candidate":{"nfs export apply":value},"before":{}}
        transaction=tx.Transaction(control/"transactions",Deferred)
        result=transaction.execute("apply",request)
        assert result["state"]=="VERIFIED",result
        assert (owned/"deferred").stat().st_uid==65534 and (owned/"deferred").stat().st_mode&0o777==0o775
        assert result["pathbindings"]["bindings"][0]["pathIdentity"]["inode"]==(owned/"deferred").stat().st_ino
        assert transaction.show(request)["pathbindings"]==result["pathbindings"]
        assert transaction.execute("commit",request)["state"]=="COMMITTED"
        passed("real deferred transaction -> verified receipt -> commit without publishing")
        for entry in FileSharePathBindings(root=control/"tx-bindings").registered()["records"].values():
            run("umount",entry["anchor"])
        print(json.dumps({"success":True,"kind":"SystemVM injection, not Mold UI acceptance","provisioning":args.provisioning,"checks":checks}))
    finally:
        for path in reversed(mounts):
            subprocess.run(["umount",str(path)],capture_output=True)
        if owned.exists():shutil.rmtree(owned)
        shutil.rmtree(control)
if __name__=="__main__":main()
