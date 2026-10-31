#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Opt-in SystemVM regression. Uses an existing SPARSE/FAT DATA mount; never formats.
Run with --volume /srv/ablestack-storage/volumes/<uuid> --provisioning SPARSE.
A private mount namespace, network namespace and disposable DATA subdirectory
exercise real bind aliases, two Ganesha listeners, Samba, and the real adapter.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import traceback
import uuid
from unittest.mock import patch


def command(args, **kwargs):
    result = subprocess.run(args, capture_output=True, text=True, timeout=kwargs.pop("timeout", 30), **kwargs)
    if result.returncode:
        raise RuntimeError(str(args[:3]) + ": " + result.stderr[-1800:])
    return result.stdout.strip()


def metadata(path):
    s = path.stat()
    result = {"uid": s.st_uid, "gid": s.st_gid, "mode": format(stat.S_IMODE(s.st_mode), "04o")}
    if path.is_file():
        result["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


CLIENT = r"""
import json,os,subprocess,sys
from pathlib import Path
mount,control,phase,port=Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3],sys.argv[4]
payload="epic1455-bind-"+phase+"-"+port
def run(args,**kw):
 r=subprocess.run(args,capture_output=True,text=True,timeout=30,**kw)
 if r.returncode:raise RuntimeError(str(args[:3])+": "+r.stderr[-1500:])
 return r.stdout
def uid():
 os.setgroups([65534]);os.setgid(65534);os.setuid(65534)
run(["mount","-t","nfs4","-o","vers=4.1,sec=sys,proto=tcp,port="+port+",soft,timeo=10,retrans=1,nosharecache,actimeo=0,lookupcache=none","127.0.0.1:/shared",str(mount)])
try:
 s=mount.stat()
 source=control/("input-"+port);source.write_text(payload)
 nfs="nfs-"+phase+"-"+port
 smb="smb-"+phase+"-"+port
 write="from pathlib import Path;import sys;Path(sys.argv[1]).write_text(sys.argv[2])"
 run(["python3","-c",write,str(mount/nfs),payload],preexec_fn=uid)
 target=control/("nfs-copy-"+port);seed=control/("seed-copy-"+port)
 run(["smbclient","//127.0.0.1/common","-p","23045","-U","epic1455guest%","-N","-c","put "+str(source)+" "+smb+"; get "+nfs+" "+str(target)+"; get seed.txt "+str(seed)])
 assert target.read_text()==payload and seed.read_bytes()==b"epic1455-bind-seed"
 read="from pathlib import Path;import sys;assert Path(sys.argv[1]).read_text()==sys.argv[2]"
 run(["python3","-c",read,str(mount/smb),payload],preexec_fn=uid)
 print(json.dumps({"port":int(port),"uid":s.st_uid,"gid":s.st_gid,"mode":format(s.st_mode&0o7777,"04o"),"nfsToSmb":True,"smbToNfs":True,"seedPreserved":True}))
finally:
 run(["umount",str(mount)])
"""


class Probe:
    def __init__(self, args):
        self.args = args
        self.volume = Path(args.volume).resolve()
        if self.volume.parent != Path("/srv/ablestack-storage/volumes") or self.volume.is_symlink():
            raise RuntimeError("Explicit managed DATA volume required")
        fs = command(["findmnt", "-n", "-M", str(self.volume), "-o", "FSTYPE,UUID"]).split()
        if len(fs) != 2 or fs[0] not in ("xfs", "ext4"):
            raise RuntimeError("Mounted XFS/EXT4 DATA with UUID required")
        self.nonce = uuid.uuid4().hex[:10]
        self.control = Path("/var/tmp/epic1455-bind-" + self.nonce)
        self.data = self.volume / (".epic1455-bind-" + self.nonce)
        self.ns = "epic1455-bind-" + self.nonce
        self.children, self.logs, self.bound = [], [], []
        self.processes, self.configs = {}, {}
        self.ns_created = False
        self.result = {"kind": "real SystemVM bind/two-port regression; not Mold UI acceptance",
                       "operatorProvisioning": args.provisioning, "checks": []}

    def record(self, name, **values):
        self.result["checks"].append({"name": name, "passed": True, **values})

    def protected(self):
        paths = [Path("/usr/local/bin/ablestack-storagectl"), Path("/etc/samba/smb.conf"), Path("/etc/fstab")]
        paths += list(Path("/etc/ganesha/ablestack-storage").glob("*.conf"))
        manifest = Path("/etc/ablestack-storage/runtime-default/manifest.json")
        if manifest.exists():
            for item in json.loads(manifest.read_text())["files"]:
                p = item["path"]
                paths.append(Path("/usr/local") / ("bin/" + p if "/" not in p else p))
        result = {"files": {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.is_file()},
                  "pids": {p.name: p.read_text() for p in Path("/run/ablestack-storage/ganesha").glob("*.pid")},
                  "network": json.loads(command(["ip", "-j", "addr", "show", "dev", "eth0"]))}
        if self.args.live_export:
            root = Path(self.args.live_export)
            result["data"] = {str(p.relative_to(root)): metadata(p) for p in root.rglob("*") if p.is_file()}
            result["root"] = metadata(root)
        return result

    def socket_ready(self, port):
        return subprocess.run(["ip", "netns", "exec", self.ns, "python3", "-c",
            'import socket;s=socket.create_connection(("127.0.0.1",' + str(port) + '),.3);s.close()'],
            capture_output=True, timeout=5).returncode == 0

    def wait(self, port):
        for _ in range(60):
            if self.socket_ready(port):
                return
            time.sleep(.1)
        raise RuntimeError("Private listener did not become ready: " + str(port))

    def start_nfs(self, port):
        out = (self.control / ("nfs-" + str(port) + ".log")).open("a")
        self.logs.append(out)
        args = ["ip", "netns", "exec", self.ns, "ganesha.nfsd", "-F", "-f", str(self.configs[port]),
                "-p", str(self.control / (str(port) + ".pid")), "-L", str(self.control / ("ganesha-" + str(port) + ".log"))]
        proc = subprocess.Popen(args, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
        self.children.append(proc)
        self.processes[port] = proc
        self.wait(port)

    def refresh(self, units):
        for unit in units:
            port = int(unit["port"])
            if port not in self.configs:
                raise RuntimeError("Unexpected restart scope")
            process = self.processes[port]
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=8)
            self.start_nfs(port)

    def check_protocols(self, value):
        for port in (23049, 23050, 23045):
            if not self.socket_ready(port):
                raise RuntimeError("Private protocol unavailable")

    def io(self, phase):
        results = []
        for port in (23049, 23050):
            mount = self.control / ("client-" + str(port))
            mount.mkdir(exist_ok=True)
            output = command(["ip", "netns", "exec", self.ns, "unshare", "--mount", "--propagation", "private",
                "python3", "-c", CLIENT, str(mount), str(self.control), phase, str(port)], timeout=90)
            results.append(json.loads(output))
        return results

    def setup(self):
        self.control.mkdir(mode=0o755)
        self.data.mkdir(mode=0o755)
        self.shared = self.data / "shared"
        self.shared.mkdir()
        os.chown(self.shared, 65534, 65534)
        self.shared.chmod(0o770)
        self.seed = self.shared / "seed.txt"
        self.seed.write_bytes(b"epic1455-bind-seed")
        os.chown(self.seed, 65534, 65534)
        self.seed.chmod(0o640)
        self.alias = self.control / "export-alias"
        self.alias.mkdir()
        command(["mount", "--bind", str(self.shared), str(self.alias)])
        self.bound.append(self.alias)
        other = self.data / "other"
        other.mkdir()
        outside = self.control / "outside"
        outside.mkdir()
        conf = self.control / "ganesha"
        conf.mkdir()
        for port in (23049, 23050):
            recovery = self.control / ("recovery-" + str(port))
            recovery.mkdir()
            cfg = conf / ("bind_" + str(port) + ".conf")
            cfg.write_text('NFS_Core_Param { NFS_Port = ' + str(port) + '; Bind_addr=127.0.0.1; Protocols=4; Enable_NLM=false; Enable_RQUOTA=false; Enable_UDP=false; }\n'
                'NFSV4 { Grace_Period=0; Graceless=true; RecoveryRoot=' + json.dumps(str(recovery)) + '; Allow_Numeric_Owners=true; Only_Numeric_Owners=true; }\n'
                'EXPORT_DEFAULTS { Access_Type=None; Protocols=4; Transports=TCP; SecType=sys; }\n'
                'EXPORT { Export_Id=1452;\n Path = ' + json.dumps(str(self.alias)) + '; Pseudo="/shared"; Protocols=4; SecType=sys; FSAL { Name=VFS; } CLIENT { Clients=127.0.0.1; Access_Type=RW; Squash=Root_Squash; } }\n')
            self.configs[port] = cfg
        for name, path in (("unrelated", other), ("foreign", outside)):
            (conf / (name + ".conf")).write_text('NFS_Core_Param { NFS_Port=23051; }\nEXPORT {\n Path = ' + json.dumps(str(path)) + ';\n}\n')
        command(["ip", "netns", "add", self.ns])
        self.ns_created = True
        command(["ip", "-n", self.ns, "link", "set", "lo", "up"])
        command(["ip", "-n", self.ns, "link", "add", "probe0", "type", "dummy"])
        command(["ip", "-n", self.ns, "addr", "add", "192.0.2.52/32", "dev", "probe0"])
        command(["ip", "-n", self.ns, "link", "set", "probe0", "up"])
        for port in (23049, 23050):
            self.start_nfs(port)
        smb = self.control / "smb.conf"
        settings = {"private dir": "private-smb", "state directory": "state", "lock directory": "lock",
                    "cache directory": "cache", "pid directory": "pid", "ncalrpc dir": "rpc"}
        lines = ["[global]", "server role=standalone server", "security=user", "map to guest=Bad User",
                 "guest account=nobody", "smb ports=23045", "interfaces=127.0.0.1", "bind interfaces only=yes",
                 "netbios name=EPIC1452BIND", "workgroup=WORKGROUP", "load printers=no", "printing=bsd", "disable spoolss=yes"]
        for key, name in settings.items():
            path = self.control / name
            path.mkdir()
            lines.append(key + "=" + str(path))
        lines += ["passdb backend=tdbsam:" + str(self.control / "private-smb/passdb.tdb"),
                  "log file=" + str(self.control / "smb.log"), "[common]", "path=" + str(self.shared),
                  "read only=no", "guest ok=yes", "create mask=0660", "directory mask=0770"]
        smb.write_text("\n".join(lines) + "\n")
        command(["testparm", "-s", str(smb)])
        out = (self.control / "smb-output.log").open("a")
        self.logs.append(out)
        self.children.append(subprocess.Popen(["ip", "netns", "exec", self.ns, "smbd", "--foreground", "--no-process-group",
            "--configfile", str(smb)], stdout=out, stderr=subprocess.STDOUT, start_new_session=True))
        self.wait(23045)
        return conf, other

    def exercise(self, conf, other):
        sys.path.insert(0, self.args.candidate)
        import configuration_transaction as transaction
        import shared_directory_policy as helper
        Engine = helper.DirectoryPolicies
        engine = Engine(registry=self.control / "private/policy.json", configs=conf, refresh=self.refresh, protocol_check=self.check_protocols)
        units = engine.units(str(self.shared))
        assert {int(u["port"]) for u in units} == {23049, 23050}
        assert all(u["paths"] == [str(self.shared)] for u in units)
        assert all(u["aliases"][0]["exportpath"] == str(self.alias) for u in units)
        self.record("real bind mount maps to exactly both listeners; unrelated directory and foreign filesystem excluded",
                    units=units, inode=self.shared.stat().st_ino)
        row = {"uuid": "private-nfs", "name": "private-nfs", "protocol": "NFS",
               "config": {"volumeMountPath": str(self.volume), "backingPath": str(self.shared)}}
        observed = engine.observe(row)
        policy = {k: observed[k] for k in ("volumeuuid", "canonicalpath", "filesystemuuid", "inode", "owneruid", "ownergid", "mode")}
        policy.update(uuid=str(uuid.uuid4()), revision=1)
        smb_rows = [{"state": "Ready", "config": row["config"],
                     "acls": [{"principalType": "LOCAL_USER", "principal": "nobody", "permission": "READ_WRITE", "state": "Ready"}]}]
        def value(action, rev, changes=None, fail=False):
            p = {**policy, "revision": rev}
            changes = changes or {}
            for source, target in (("ownerUid", "owneruid"), ("ownerGid", "ownergid"), ("mode", "mode")):
                if source in changes:
                    p[target] = changes[source]
            v = {"action": action, "policy": p, "changes": changes, "expectedObservation": engine.observe(row)["fingerprint"],
                 "units": engine.units(str(self.shared)), "smbShares": smb_rows}
            if action == "UPDATE":
                v["expectedPolicyRevision"] = rev - 1
            if fail:
                v["failPeer"] = True
            return v
        calls = []
        class Adapter(transaction.RuntimeAdapter):
            def apply(adapter, commands):
                calls.append(1)
                super().apply(commands)
            def verify(adapter, commands):
                super().verify(commands)
                if commands["directory policy apply"].get("failPeer"):
                    raise transaction.Rejected("TEST_BIND_PEER_FAILURE")
        identity = str(uuid.uuid4())
        def payload(v, base):
            return {"instanceUuid": identity, "operationid": str(uuid.uuid4()), "baseRevision": base,
                    "targetRevision": base + 1, "candidate": {"directory policy apply": v}, "before": {}}
        with patch.object(helper, "DirectoryPolicies", return_value=engine):
            tx = transaction.Transaction(self.control / "journal", Adapter)
            adopted = payload(value("ADOPT", 1), 0)
            before = metadata(self.shared)
            assert tx.execute("apply", adopted)["state"] == "VERIFIED"
            tx.execute("commit", adopted)
            assert metadata(self.shared) == before
            engine.apply_acl_batch(smb_rows, [str(self.shared)])
            initial = self.io("before")
            seed = metadata(self.seed)
            stale = value("UPDATE", 2, {"mode": "0777"})
            command(["umount", str(self.alias)])
            command(["mount", "--bind", str(other), str(self.alias)])
            try:
                engine.prepare(stale)
                raise AssertionError("Rebound alias was not rejected")
            except helper.PolicyError as error:
                assert error.code == "DIRECTORY_POLICY_STALE"
            finally:
                command(["umount", str(self.alias)])
                command(["mount", "--bind", str(self.shared), str(self.alias)])
            self.record("alias rebind after preview is rejected without modifying permissions")
            update = payload(value("UPDATE", 2, {"ownerUid": 1001, "ownerGid": 1001, "mode": "0777"}), 1)
            assert tx.execute("apply", update)["state"] == "VERIFIED"
            tx.execute("commit", update)
            after = self.io("after")
            assert all((r["uid"], r["gid"], r["mode"]) == (1001, 1001, "0777") for r in after)
            assert metadata(self.seed) == seed
            self.record("both bind-backed ports and SMB cross-read/write after common POSIX change", before=initial, after=after)
            reopened = transaction.Transaction(self.control / "journal", Adapter)
            count = len(calls)
            assert reopened.execute("resume", update)["state"] == "COMMITTED"
            assert reopened.execute("apply", update)["state"] == "COMMITTED" and len(calls) == count
            self.record("journal reopen and duplicate request do not replay mutation")
            policy.update(owneruid=1001, ownergid=1001, mode="0777", revision=2)
            failed = payload(value("UPDATE", 3, {"mode": "0775"}, True), 2)
            rolled = reopened.execute("apply", failed)
            assert rolled["state"] == "ROLLED_BACK" and rolled["rollback"]["state"] == "SUCCEEDED", rolled
            restored = self.io("rollback")
            assert all((r["uid"], r["gid"], r["mode"]) == (1001, 1001, "0777") for r in restored)
            assert metadata(self.seed) == seed
            self.record("real adapter restores POSIX, ACL and policy; both bind-backed listeners remain usable", operation=rolled)
        if self.args.live_export:
            live = Engine()
            current = live.units(self.args.live_export)
            assert {int(u["port"]) for u in current} == {2049, 2050}
            assert all(live.unit_ready(u) for u in current)
            self.record("actual deployed bind aliases and two process/socket identities match by read-only observation")

    def run(self):
        before = self.protected()
        try:
            conf, other = self.setup()
            self.exercise(conf, other)
            self.result["success"] = True
        except BaseException as error:
            self.result.update(success=False, error={"type": type(error).__name__, "message": str(error), "trace": traceback.format_exc()})
            self.result["logs"] = {p.name: p.read_text(errors="replace")[-2000:] for p in self.control.glob("*.log")}
        finally:
            print(json.dumps({"probeBeforeCleanup": self.result}), flush=True)
            for proc in reversed(self.children):
                if proc.poll() is None:
                    os.killpg(proc.pid, signal.SIGTERM)
                    try:
                        proc.wait(timeout=8)
                    except subprocess.TimeoutExpired:
                        os.killpg(proc.pid, signal.SIGKILL)
                        proc.wait(timeout=5)
            for output in self.logs:
                output.close()
            for alias in reversed(self.bound):
                if subprocess.run(["findmnt","-n","-M",str(alias)],capture_output=True).returncode==0:
                    command(["umount", str(alias)])
            if self.ns_created:
                command(["ip", "netns", "del", self.ns])
            assert self.data.parent == self.volume and self.data.name == ".epic1455-bind-" + self.nonce
            assert self.control == Path("/var/tmp/epic1455-bind-" + self.nonce)
            if self.data.exists():
                shutil.rmtree(self.data)
            if self.control.exists():
                shutil.rmtree(self.control)
            self.result["productionUnchanged"] = before == self.protected()
            self.result["temporaryResourcesRemoved"] = not self.data.exists() and not self.control.exists()
            self.result["productionFileCount"] = len(before.get("data", {}))
            self.result["success"] = bool(self.result.get("success") and self.result["productionUnchanged"] and self.result["temporaryResourcesRemoved"])
        return self.result




def normalized_modes(config):
    defaults={"createMask":"0660","directoryMask":"0770","forceCreateMode":"0000","forceDirectoryMode":"0000"}
    result={}
    for key,default in defaults.items():
        value=config.get(key,default)
        if not isinstance(value,str) or not re.fullmatch(r"0?[0-7]{3}",value):
            raise ValueError("SMB_CREATION_MODE_INVALID")
        result[key]=format(int(value,8),"04o")
    for force,mask in [("forceCreateMode","createMask"),("forceDirectoryMode","directoryMask")]:
        if int(result[force],8)&~int(result[mask],8):
            raise ValueError("SMB_FORCE_MODE_OUTSIDE_MASK")
    return result


import re,secrets,selectors,copy,ast
from types import SimpleNamespace

CLIENT_HOLD = r'''
import os,sys,json,subprocess
from pathlib import Path
mount,port=Path(sys.argv[1]),sys.argv[2]
mount.mkdir()
cred=os.memfd_create("epic1455-cifs-credential",0)
try:
 os.write(cred,("username=epic1453rw\npassword="+os.environ.pop("PASSWD")+"\ndomain=WORKGROUP\n").encode())
 os.lseek(cred,0,0)
 r=subprocess.run(["mount","-t","cifs","//127.0.0.1/common",str(mount),"-o","credentials=/proc/self/fd/"+str(cred)+",port="+port+",vers=3.1.1,nounix,noperm,cache=none,nosharesock,serverino"],pass_fds=(cred,),capture_output=True,text=True)
 assert r.returncode==0,r.stderr
finally:os.close(cred)
fd=None
try:
 fd=os.open(mount/"held.txt",os.O_CREAT|os.O_RDWR,0o660)
 def io(value):
  os.pwrite(fd,value.encode(),0);os.fsync(fd)
  assert os.pread(fd,len(value),0).decode()==value
  return {"fd":fd,"bytes":len(value),"sameOpenHandle":True}
 print(json.dumps({"ready":True,**io("initial-"+port)}),flush=True)
 for line in sys.stdin:
  q=json.loads(line)
  if q["action"]=="stop":break
  if q["action"]=="io":
   print(json.dumps(io(q["value"])),flush=True)
  if q["action"]=="create":
   p=mount/q["name"];p.write_text(q["name"]);print(json.dumps({"created":q["name"]}),flush=True)
finally:
 if fd is not None:os.close(fd)
 subprocess.run(["umount",str(mount)],check=True)
'''

class MaskProbe(Probe):
 def protected(self):
  v=super().protected()
  v["accounts"]={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in map(Path,["/etc/passwd","/etc/group","/etc/shadow","/etc/gshadow"])}
  v["desired"]={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in Path("/etc/ablestack-storage/desired-state").rglob("*") if p.is_file()}
  sys.path.insert(0,"/usr/local/lib/ablestack-storage")
  from configuration_transaction import SmbListenerProfiles
  profiles=SmbListenerProfiles();s=json.loads(profiles.manifest.read_text())
  v["managedPids"]={k:profiles.pid(row) for k,row in s["profiles"].items() if row.get("kind")!="covered"}
  for base in [Path("/export/common1453r5"),Path("/export/ad1453r5")]:
   for f in [base]+sorted(base.rglob("*")):
    t=f.stat();v.setdefault("objects",{})[str(f)]={"uid":t.st_uid,"gid":t.st_gid,"mode":t.st_mode&0o7777,"inode":t.st_ino,"device":t.st_dev,"acl":command(["getfacl","-cpn",str(f)]),**({"sha256":hashlib.sha256(f.read_bytes()).hexdigest()} if f.is_file() else {})}
  return v
 def setup(self):
  conf,other=super().setup()
  old=self.children[-1]
  os.killpg(old.pid,signal.SIGTERM);old.wait(timeout=8)
  self.secret=secrets.token_urlsafe(30)
  self.smbconf=self.control/"smb.conf"
  self.global_conf=self.smbconf.read_text().split("[common]",1)[0].replace("map to guest=Bad User","map to guest=Never").replace("EPIC1452BIND","EPIC1454MASK")
  self.smbconf.write_text(self.global_conf+"\n[common]\npath="+str(self.shared)+"\nguest ok=no\nread only=no\n")
  q=subprocess.run(["smbpasswd","-c",str(self.smbconf),"-a","-s","epic1453rw"],input=self.secret+"\n"+self.secret+"\n",capture_output=True,text=True,timeout=15)
  assert q.returncode==0,q.stderr
  os.chown(self.shared,65534,65534);command(["setfacl","-m","u:1001:rwx",str(self.shared)]);self.shared.chmod(0o2770)
  command(["setfacl","-d","-m","u:1001:rwx",str(self.shared)])
  self.source=self.control/"input.txt";self.source.write_bytes(b"epic1455-native-mask")
  src=Path("/usr/local/bin/ablestack-storagectl").read_text()
  body=src.split("apply_smb_shares() {",1)[1].split("<<'PY'\n",1)[1].split("\nPY\n",1)[0]
  tree=ast.parse(body)
  self.render=next(n for n in ast.walk(tree) if isinstance(n,ast.Expr) and isinstance(n.value,ast.Call) and isinstance(n.value.func,ast.Attribute) and n.value.func.attr=="extend" and "create mask = 0660" in ast.get_source_segment(body,n))
  self.sourceRendererHash=hashlib.sha256(ast.get_source_segment(body,self.render).encode()).hexdigest()
  self.profiles={}
  self.clients=[]
  self.render_config({})
  for port in [23045,23046]:
   settings=[]
   for key in ["pid directory","lock directory","state directory","cache directory","ncalrpc dir"]:
    d=self.control/(str(port)+"-"+key.replace(" ","-"));d.mkdir();settings.append(key+"="+str(d))
   overlay=self.control/("listener-"+str(port)+".conf")
   overlay.write_text("[global]\ninclude="+str(self.smbconf)+"\n[global]\nsmb ports="+str(port)+"\n"+"\n".join(settings)+"\n")
   log=(self.control/("smb-"+str(port)+".log")).open("a");self.logs.append(log)
   proc=subprocess.Popen(["ip","netns","exec",self.ns,"smbd","--foreground","--no-process-group","--configfile",str(overlay)],stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
   self.children.append(proc);self.profiles[port]=(overlay,proc)
   self.wait(port)
  return conf,other
 def render_config(self,cfg):
  modes=normalized_modes(cfg)
  scope={"lines":[],"name":"common","path":str(self.shared),"config":{"readOnly":False,"browseable":True},"guest_ok":False,"truth":lambda c,k,d:c.get(k,d)}
  exec(compile(ast.fix_missing_locations(ast.Module(body=[copy.deepcopy(self.render)],type_ignores=[])),"<installed-renderer-mask-injection>","exec"),scope)
  lines=scope["lines"]
  lines=[x for x in lines if not any(k in x for k in ["create mask =","directory mask ="])]
  lines+=["   "+key+" = "+modes[field] for key,field in [("create mask","createMask"),("directory mask","directoryMask"),("force create mode","forceCreateMode"),("force directory mode","forceDirectoryMode")]]
  lines+=["   inherit owner = "+("yes" if cfg.get("inheritOwner") else "no"),"   inherit permissions = "+("yes" if cfg.get("inheritPermissions") else "no"),'   valid users = "epic1453rw"','   write list = "epic1453rw"']
  data=self.global_conf+"\n"+"\n".join(lines)+"\n"
  candidate=self.control/"candidate.conf";candidate.write_text(data)
  command(["testparm","-s",str(candidate)])
  os.replace(candidate,self.smbconf)
 def reload(self,fail_second=False):
  for index,(port,(cfg,proc)) in enumerate(self.profiles.items()):
   pid=2147483647 if fail_second and index==1 else proc.pid
   command(["smbcontrol","-s",str(cfg),str(pid),"reload-config"])
 def smb(self,port,cmd):
  q=subprocess.run(["ip","netns","exec",self.ns,"smbclient","//127.0.0.1/common","-p",str(port),"-U","epic1453rw","-m","SMB3","-c",cmd],env={**os.environ,"PASSWD":self.secret},capture_output=True,text=True,timeout=20)
  assert q.returncode==0,q.stdout+q.stderr
 def state(self):
  out={}
  for port,(cfg,proc) in self.profiles.items():
   v=json.loads(command(["smbstatus","-s",str(cfg),"--json"]))
   out[str(port)]={"parentPid":proc.pid,"sessionIds":sorted(v.get("sessions",{})),"treeIds":sorted(v.get("tcons",{}))}
  return out
 def observe(self,p):
  s=p.stat();return {"uid":s.st_uid,"gid":s.st_gid,"mode":format(s.st_mode&0o7777,"04o"),"inode":s.st_ino}
 def request(self,client,value):
  client.stdin.write(json.dumps(value)+"\n");client.stdin.flush()
  with selectors.DefaultSelector() as selector:
   selector.register(client.stdout,selectors.EVENT_READ)
   assert selector.select(30),"CIFS client response timed out"
  line=client.stdout.readline();assert line, "CIFS client exited"
  return json.loads(line)
 def exercise(self,conf,other):
  held=[]
  try:
   for port in self.profiles:
    log=(self.control/("client-"+str(port)+".log")).open("a");self.logs.append(log)
    client=subprocess.Popen(["ip","netns","exec",self.ns,"unshare","--mount","--propagation","private","python3","-u","-c",CLIENT_HOLD,str(self.control/("cifs-"+str(port))),str(port)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,text=True,env={**os.environ,"PASSWD":self.secret},start_new_session=True)
    self.clients.append(client);self.children.append(client)
    with selectors.DefaultSelector() as sel:
     sel.register(client.stdout,selectors.EVENT_READ);assert sel.select(30),"CIFS mount readiness timeout"
    ready=json.loads(client.stdout.readline());assert ready["ready"];held.append(ready)
   stable=self.state()
   matrix=[]
   for name,cfg in [("baseline",{}),("restricted",{"createMask":"0640","directoryMask":"0750"}),("forced-execute",{"createMask":"0775","directoryMask":"0775","forceCreateMode":"0111","forceDirectoryMode":"0005"}),("inherit-owner",{"createMask":"0660","directoryMask":"0770","inheritOwner":True})]:
    before=self.smbconf.read_bytes()
    self.render_config(cfg);self.reload()
    for port,client in zip(self.profiles,self.clients):
     kept=self.request(client,{"action":"io","value":"kept-"+name+"-"+str(port)})
     self.request(client,{"action":"create","name":"same-tree-"+name+"-"+str(port)})
     filename=name+"-"+str(port)
     self.smb(port,"put "+str(self.source)+" "+filename+".txt; mkdir "+filename)
     matrix.append({"case":name,"port":port,"requested":normalized_modes(cfg),"file":self.observe(self.shared/(filename+".txt")),"directory":self.observe(self.shared/filename),"existingTreeNewFile":self.observe(self.shared/("same-tree-"+name+"-"+str(port))),"heldIo":kept})
    assert self.state()==stable,{"before":stable,"after":self.state()}
   for row in matrix:
    expected_file={"baseline":"0660","restricted":"0640","forced-execute":"0770","inherit-owner":"0660"}[row["case"]]
    expected_dir={"baseline":"2770","restricted":"2750","forced-execute":"2770","inherit-owner":"2770"}[row["case"]]
    assert row["file"]["mode"]==expected_file and row["directory"]["mode"]==expected_dir,row
    assert row["file"]["gid"]==65534 and row["file"]["uid"]==(65534 if row["case"]=="inherit-owner" else 1001),row
    assert row["existingTreeNewFile"]["mode"]==expected_file,row
   self.record("real native two-profile reload keeps daemon/session/tree/open handle",stable=stable,matrix=matrix,rendererAstSha256=self.sourceRendererHash)
   # Deliberately interrupted second profile reload: restore bytes and reload all; no restart fallback.
   old=self.smbconf.read_bytes()
   self.render_config({"createMask":"0600","directoryMask":"0700"})
   try:self.reload(True)
   except RuntimeError as e:assert "smbcontrol" in str(e)
   else:raise AssertionError("Planned second reload failure not injected")
   self.smbconf.write_bytes(old);self.reload()
   for c in self.clients:self.request(c,{"action":"io","value":"after-rollback"})
   assert self.state()==stable
   self.record("partial reload command failure restores prior bytes and reloads all without PID/session/handle loss",sameConfiguration=self.smbconf.read_bytes()==old,stable=stable)
   old=self.smbconf.read_bytes()
   rejected=[]
   for cfg in [{"createMask":"0888"},{"createMask":"1777"},{"createMask":"00660"},{"createMask":660},{"createMask":"0600","forceCreateMode":"0040"},{"directoryMask":"0700","forceDirectoryMode":"0001"}]:
    try:normalized_modes(cfg)
    except ValueError as e:rejected.append(str(e))
    else:raise AssertionError("Invalid creation mode accepted")
   assert self.smbconf.read_bytes()==old
   self.record("prototype strict octal and force-subset guard rejects before file/config mutation",codes=rejected)
   self.render_config({"createMask":"0600","directoryMask":"0700","inheritPermissions":True});self.reload()
   self.smb(23045,"put "+str(self.source)+" inherited-permissions.txt; mkdir inherited-permissions")
   self.record("native inherit permissions takes precedence; design must reject conflicting custom masks rather than silently ignore",file=self.observe(self.shared/"inherited-permissions.txt"),directory=self.observe(self.shared/"inherited-permissions"))
   self.render_config({});self.reload()
   # Existing protected NFS fixture at both ports reads private SMB object; no live export reload.
   cross=[]
   code=r"""
import os,json,sys,subprocess
from pathlib import Path
m=Path(sys.argv[1]);m.mkdir();port=sys.argv[2];f=sys.argv[3]
r=subprocess.run(["mount","-t","nfs4","-o","vers=4.1,sec=sys,port="+port+",soft,timeo=10,retrans=1,nosharecache","127.0.0.1:/shared",str(m)],capture_output=True,text=True);assert r.returncode==0,r.stderr
try:
 r=subprocess.run(["setpriv","--reuid","1001","--regid","1001","--groups","65534","python3","-c","from pathlib import Path;import sys;assert Path(sys.argv[1]).read_bytes()==b'epic1455-native-mask'",str(m/f)],capture_output=True,text=True);assert r.returncode==0,r.stderr
 print(json.dumps({"port":int(port),"smbDataReadableViaNfs":True}))
finally:subprocess.run(["umount",str(m)],check=True)
"""
   for port in [23049,23050]:
    cross.append(json.loads(command(["ip","netns","exec",self.ns,"unshare","--mount","--propagation","private","python3","-c",code,str(self.control/("nfs-client-"+str(port))),str(port),"baseline-23045.txt"],timeout=45)))
   self.record("NFS two-port cross-access and existing seed preserved",nfs=cross,seedSha256=hashlib.sha256(self.seed.read_bytes()).hexdigest())
  finally:
   for c in self.clients:
    if c.poll() is None:
     try:c.stdin.write('{"action":"stop"}\n');c.stdin.flush();c.wait(timeout=10)
     except Exception:pass

def main():
 assert Path("/sys/class/dmi/id/product_uuid").read_text().strip().lower()=="d9c423e9-ce89-3ba9-9cd8-f48cdb791da2"
 assert os.readlink('/proc/self/ns/mnt')!=os.readlink('/proc/1/ns/mnt')
 assert not any('shared:' in line.split(' - ',1)[0] for line in Path('/proc/self/mountinfo').read_text().splitlines())
 args=SimpleNamespace(volume="/srv/ablestack-storage/volumes/e070a0aa-dbf5-48c0-83e0-09f874446277",provisioning="SPARSE",candidate="/usr/local/lib/ablestack-storage",live_export="/export/common1453r5")
 result=MaskProbe(args).run();print(json.dumps(result));return 0 if result["success"] else 1

if __name__=="__main__":sys.exit(main())
