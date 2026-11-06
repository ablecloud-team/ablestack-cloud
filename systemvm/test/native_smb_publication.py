#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Actual SystemVM Samba/testparm regression in private mount/network namespaces.
Only an owned directory on the explicitly identified SPARSE/FAT DATA is used.
"""
from pathlib import Path
import argparse
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid

def run(args):
    return subprocess.check_output(args,stderr=subprocess.STDOUT,text=True).strip()
def main():
    parser=argparse.ArgumentParser();parser.add_argument("--volume",required=True)
    parser.add_argument("--provisioning",choices=("SPARSE","FAT"),required=True)
    parser.add_argument("--helper",required=True);parser.add_argument("--inner",action="store_true")
    args=parser.parse_args()
    if not args.inner:
        q=subprocess.run(["unshare","--mount","--net","--propagation","private",sys.executable,__file__,*sys.argv[1:],"--inner"],capture_output=True,text=True,timeout=150)
        print(q.stdout);print(q.stderr,file=sys.stderr);return q.returncode
    sys.path.insert(0,args.helper)
    import shared_directory_policy as h
    volume=Path(args.volume);assert volume.parent==Path("/srv/ablestack-storage/volumes")
    fs=run(["findmnt","-n","-M",str(volume),"-o","UUID,FSTYPE"]).split()
    assert fs[1] in ("xfs","ext4")
    run(["ip","link","set","lo","up"])
    control=Path(tempfile.mkdtemp(prefix="epic1455-pub-native-",dir="/var/tmp"))
    data=volume/(".epic1455-pub-"+uuid.uuid4().hex);data.mkdir(mode=0o755)
    children=[];streams=[];sources=[];originals={};checks=[]
    def passed(name):checks.append({"name":name,"passed":True})
    def rejected(name,code,call):
        try:call()
        except h.PolicyError as error:assert error.code==code,(name,error.code);passed(name)
        else:raise AssertionError(name+" incorrectly accepted")
    try:
        for index,port in enumerate((23045,23046)):
            base=control/str(index);base.mkdir()
            lines=["[global]","server role=standalone server","security=user","map to guest=Bad User",
                   "smb ports="+str(port),"interfaces=127.0.0.1","bind interfaces only=yes","netbios name=EPICPUB"+str(index),
                   "load printers=no","printing=bsd","disable spoolss=yes"]
            for key,folder in [("private dir","private"),("state directory","state"),("lock directory","lock"),
                               ("cache directory","cache"),("pid directory","pid"),("ncalrpc dir","rpc")]:
                path=base/folder;path.mkdir();lines.append(key+"="+str(path))
            lines += ["passdb backend=tdbsam:"+str(base/"private/passdb.tdb"),"log file="+str(base/"smb.log"),
                      "[published]","path="+str(data),"read only=no","guest ok=yes"]
            cfg=base/"smb.conf";cfg.write_text("\n".join(lines)+"\n");originals[cfg]=cfg.read_text()
            stream=(base/"daemon.log").open("w");streams.append(stream)
            process=subprocess.Popen(["smbd","--foreground","--no-process-group","--configfile",str(cfg)],
                                     stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
            children.append(process);sources.append((cfg,process.pid))
            for _ in range(60):
                q=subprocess.run(["ss","-Hlnt","sport","=",str(port)],capture_output=True,text=True)
                if q.stdout.strip():break
                assert process.poll() is None
                time.sleep(.1)
            assert q.stdout.strip()
        inode=data.stat().st_ino
        row={"uuid":str(uuid.uuid4()),"name":"waiting","state":"Ready","acls":[],"config":{"pathBindingVersion":1,
            "volumeMountPath":str(volume),"backingPath":str(data),"relativeSharePath":data.name,"guestOk":False,
            "pathIdentity":{"filesystemUuid":fs[0],"volumeRootInode":volume.stat().st_ino,"inode":inode}}}
        desired=control/"desired.json";desired.write_text(json.dumps({"enabled":True,"shares":[row]}));desired.chmod(0o600)
        state=h.smb_publication_state(row,desired,sources)
        assert state["state"]=="UNPUBLISHED_WAITING_ACL" and state["listeners"]==2,state
        for values in state["runtime"]:h.inheritance_conflict(values,"2775")
        passed("two actual daemons verify deliberate absence and ownership defaults")
        published={**row,"name":"published","acls":[{"state":"Ready","principal":"user"}]}
        assert h.smb_publication_state(published,desired,sources)["state"]=="PUBLISHED"
        passed("published sections verified on both actual listeners")
        cfg=sources[1][0];cfg.write_text(originals[cfg].split("[published]",1)[0])
        rejected("missing published section remains blocked","SMB_PUBLISHED_SECTION_MISSING",
                 lambda:h.smb_publication_state(published,desired,sources))
        cfg.write_text(originals[cfg])
        cfg=sources[0][0];cfg.write_text(originals[cfg]+"\n[waiting]\npath="+str(data)+"\n")
        rejected("unexpected publication cannot use waiting fallback","SMB_PUBLICATION_STATE_MISMATCH",
                 lambda:h.smb_publication_state(row,desired,sources))
        cfg.write_text(originals[cfg])
        cfg.write_text(originals[cfg].replace("[global]","[global]\nforce group=nogroup",1))
        state=h.smb_publication_state(row,desired,sources)
        rejected("global force-group conflict remains blocked","SMB_PARENT_IDENTITY_CONFLICT",
                 lambda:[h.inheritance_conflict(v,"2775")for v in state["runtime"]])
        cfg.write_text(originals[cfg])
        assert data.stat().st_ino==inode and not list(data.iterdir())
        passed("data inode and empty directory preserved")
        print(json.dumps({"success":True,"provisioning":args.provisioning,"checks":checks}))
    finally:
        for child in reversed(children):
            if child.poll() is None:
                os.killpg(child.pid,signal.SIGTERM)
                try:child.wait(timeout=8)
                except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.wait()
        for stream in streams:stream.close()
        assert data.parent==volume and data.name.startswith(".epic1455-pub-")
        shutil.rmtree(data);shutil.rmtree(control)
if __name__=="__main__":sys.exit(main())
