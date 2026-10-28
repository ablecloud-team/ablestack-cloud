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
payload="epic1452-bind-"+phase+"-"+port
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
 run(["smbclient","//127.0.0.1/common","-p","23045","-U","epic1452guest%","-N","-c","put "+str(source)+" "+smb+"; get "+nfs+" "+str(target)+"; get seed.txt "+str(seed)])
 assert target.read_text()==payload and seed.read_bytes()==b"epic1452-bind-seed"
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
        self.control = Path("/var/tmp/epic1452-bind-" + self.nonce)
        self.data = self.volume / (".epic1452-bind-" + self.nonce)
        self.ns = "epic1452-bind-" + self.nonce
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
        self.seed.write_bytes(b"epic1452-bind-seed")
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
                if os.path.ismount(alias):
                    command(["umount", str(alias)])
            if self.ns_created:
                command(["ip", "netns", "del", self.ns])
            assert self.data.parent == self.volume and self.data.name == ".epic1452-bind-" + self.nonce
            assert self.control == Path("/var/tmp/epic1452-bind-" + self.nonce)
            if self.data.exists():
                shutil.rmtree(self.data)
            if self.control.exists():
                shutil.rmtree(self.control)
            self.result["productionUnchanged"] = before == self.protected()
            self.result["temporaryResourcesRemoved"] = not self.data.exists() and not self.control.exists()
            self.result["productionFileCount"] = len(before.get("data", {}))
            self.result["success"] = bool(self.result.get("success") and self.result["productionUnchanged"] and self.result["temporaryResourcesRemoved"])
        return self.result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--volume", required=True)
    parser.add_argument("--provisioning", required=True, choices=["SPARSE", "FAT"])
    parser.add_argument("--candidate", default="/usr/local/lib/ablestack-storage")
    parser.add_argument("--live-export")
    parser.add_argument("--inner", action="store_true")
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit("Root is required for isolated native regression")
    if not args.inner:
        result = subprocess.run(["unshare", "--mount", "--propagation", "private", sys.executable, __file__,
                                 *sys.argv[1:], "--inner"], capture_output=True, text=True, timeout=240)
        print(result.stdout.strip())
        if result.stderr:
            print(result.stderr[-1600:], file=sys.stderr)
        return result.returncode
    result = Probe(args).run()
    print(json.dumps(result))
    return 0 if result["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
