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

"""Staged NFS visibility probes with one deadline and exact successful checkpoints."""
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import tempfile
import time


class NfsProbeRunner:
    concurrency = 1

    def __init__(self, payload, root, deadline, select_host, report=None):
        self.payload, self.root, self.deadline = payload, str(root), deadline
        self.select_host, self.report = select_host, report
        self.boot_id = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        self.cache_root = Path("/run/ablestack-storage/nfs-probe-checkpoints")
        self.cache_enabled = bool(os.environ.get("ABLESTACK_STORAGE_BOOT_DEADLINE_MONOTONIC"))
        self.last_failure = None
        self.success_keys = []
        self.skipped_acl = 0
        self.completed = 0
        self.total = 0
        self.blocked = False

    @staticmethod
    def identity(info):
        return tuple(getattr(info, field) for field in
                     ("st_dev", "st_ino", "st_uid", "st_gid", "st_mode", "st_size", "st_mtime_ns", "st_ctime_ns"))

    def protected_read(self, path):
        parent = path.parent.lstat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or parent.st_mode & 0o077:
            raise ValueError("NFS probe checkpoint directory is not protected")
        info = path.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
                or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > 128 * 1024):
            raise ValueError("NFS probe checkpoint is not protected")
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            opened = os.fstat(fd)
            if self.identity(info) != self.identity(opened):
                raise ValueError("NFS probe checkpoint changed while opening")
            data = os.read(fd, 128 * 1024 + 1)
            if self.identity(opened) != self.identity(os.fstat(fd)) or self.identity(opened) != self.identity(path.lstat()):
                raise ValueError("NFS probe checkpoint changed while reading")
            return json.loads(data)
        finally:
            os.close(fd)

    def write(self, path, record):
        path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
        parent = path.parent.lstat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or parent.st_mode & 0o077:
            raise ValueError("NFS probe checkpoint directory is not protected")
        if path.exists() or path.is_symlink():
            self.protected_read(path)
        fd, temporary = tempfile.mkstemp(prefix=".nfs-probe-", dir=path.parent)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w") as handle:
                json.dump(record, handle, sort_keys=True, allow_nan=False)
                handle.flush()
                os.fsync(handle.fileno())
            if self.identity(parent)[:5] != self.identity(path.parent.lstat())[:5]:
                raise ValueError("NFS probe checkpoint parent changed")
            os.replace(temporary, path)
            directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def checkpoint_key(self, endpoint, export, mode):
        if not self.cache_enabled or endpoint.get("exportInventoryReady") is not True:
            return None
        binding = {key: endpoint.get(key) for key in ("configurationSha256", "pid", "startTicks")}
        if (not isinstance(binding["configurationSha256"], str) or len(binding["configurationSha256"]) != 64
                or type(binding["pid"]) is not int or binding["pid"] <= 0 or not binding["startTicks"]):
            raise ValueError("NFS boot probe lacks its exact endpoint identity")
        value = {"bootId": self.boot_id, **binding, "exportId": export.get("id"),
                 "exportUuid": export.get("uuid"), "mode": mode,
                 "bindingSha256": hashlib.sha256(json.dumps(export, sort_keys=True, allow_nan=False).encode()).hexdigest()}
        return value

    def cached(self, key):
        if key is None:
            return False
        name = hashlib.sha256(json.dumps(key, sort_keys=True).encode()).hexdigest() + ".json"
        path = self.cache_root / name
        if not path.exists() and not path.is_symlink():
            return False
        value = self.protected_read(path)
        return json.dumps(value,sort_keys=True) == json.dumps({"phase":"VERIFIED","identity":key,"cleanupComplete":True},sort_keys=True)

    def save(self, key):
        if key is not None:
            name = hashlib.sha256(json.dumps(key, sort_keys=True).encode()).hexdigest() + ".json"
            self.write(self.cache_root / name, {"phase": "VERIFIED", "identity": key, "cleanupComplete": True})

    def failure(self, code, endpoint=None, export=None, mode=None, cleanup=False, active=False, retryable=True):
        self.last_failure = {"protocol":"NFS","phase":"PROBE_EXPORTS",
                             "endpointIp":(endpoint or {}).get("listenIp"),"endpointPort":(endpoint or {}).get("port"),
                             "exportUuid":(export or {}).get("uuid"),"exportName":(export or {}).get("name"),
                             "protocolMode":{"NFSv4":"NFS4","NFSv3":"NFS3"}.get(mode),
                             "code":code,"timedOut":code in ("PROBE_TIMEOUT","PROBE_DEADLINE"),
                             "retryable":bool(retryable),
                             "cleanup":{"complete":not cleanup,"childActive":bool(active),"mountPresent":bool(cleanup)}}
        return self.last_failure

    def notify(self, phase):
        try:
            if self.report:
                self.report(phase,{"completedProbeCount":self.completed,"totalProbeCount":self.total,
                                  "skippedAclCount":self.skipped_acl,"detailVerificationKind":"UNVERIFIED",
                                  "lastFailure":self.last_failure})
            return True
        except (OSError,ValueError,TypeError,KeyError):
            # Progress publication is not permission to discard an unresolved
            # cleanup failure or to begin another probe.
            if self.last_failure is None or self.last_failure["cleanup"]["complete"]:
                self.failure("PROBE_PROGRESS_UNAVAILABLE",retryable=False)
            self.blocked=True
            return False

    def run_command(self, arguments, maximum, context=None):
        remaining = self.deadline - time.monotonic()
        if remaining <= .05:
            raise subprocess.TimeoutExpired(arguments, 0)
        if not callable(getattr(os, "pidfd_open", None)) or not callable(getattr(signal, "pidfd_send_signal", None)):
            raise ValueError("NFS probes require exact pidfd child control")
        inherited = (9,) if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") == "9" else ()
        child = subprocess.Popen(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, pass_fds=inherited)
        descriptor = None
        def checkpoint(phase):
            if not inherited:
                return
            ticks = None
            try:
                ticks = Path("/proc/" + str(child.pid) + "/stat").read_text().rpartition(")")[2].split()[19]
            except FileNotFoundError:
                pass
            path = Path(os.environ.get("ABLESTACK_STORAGE_NFS_PROBE_JOURNAL", "/run/ablestack-storage/nfs-probe-child/current.json"))
            self.write(path, {"phase": phase, "childPid": child.pid, "startTicks": ticks, "bootId": self.boot_id,
                              "command": Path(arguments[0]).name, "childActive": child.poll() is None,
                              "updatedEpoch": time.time(), "probe": context})
        try:
            try:
                descriptor = os.pidfd_open(child.pid, 0)
            except ProcessLookupError:
                if child.poll() is None:
                    raise ValueError("NFS probe child identity is unavailable")
            checkpoint("RUNNING")
            try:
                output, error = child.communicate(timeout=min(maximum, max(.01, remaining - 2)))
                checkpoint("COMPLETE" if child.returncode == 0 else "FAILED")
                return subprocess.CompletedProcess(arguments, child.returncode, output, error)
            except subprocess.TimeoutExpired:
                checkpoint("TIMED_OUT_PENDING_RECONCILE")
                settled=False
                for sig in (signal.SIGTERM, signal.SIGKILL):
                    if descriptor is not None:
                        try:
                            signal.pidfd_send_signal(descriptor, sig, None, 0)
                        except OSError:
                            pass
                    try:
                        child.communicate(timeout=max(.01, min(.5, self.deadline - time.monotonic())))
                    except subprocess.TimeoutExpired:
                        continue
                    settled=True
                    break
                # A helper can still own stdout/FD9 after its launcher exits.
                active = child.poll() is None or not settled
                checkpoint("RECOVERY_REQUIRED" if active else "TIMED_OUT")
                failure = subprocess.TimeoutExpired(arguments, maximum)
                failure.childActive, failure.childPid = active, child.pid
                raise failure
        except (OSError,ValueError,TypeError,KeyError) as failure:
            # Even an exited child may have mounted successfully before a
            # journal/readback failure. Only the probe cleanup can prove absence.
            if child.poll() is None and descriptor is not None:
                for sig in (signal.SIGTERM,signal.SIGKILL):
                    try:signal.pidfd_send_signal(descriptor,sig,None,0)
                    except OSError:pass
                    try:child.communicate(timeout=.1)
                    except subprocess.TimeoutExpired:continue
                    break
            error=NfsReadinessError("PROBE_CHILD_UNOBSERVABLE")
            error.public["cleanup"]={"complete":False,"childActive":child.poll() is None,"mountPresent":False}
            if context:
                for key in ("protocol","phase","endpointIp","endpointPort","exportUuid","exportName","protocolMode"):
                    error.public[key]=context.get(key)
            error.public["retryable"]=False
            raise error from failure
        finally:
            if descriptor is not None:
                os.close(descriptor)

    @staticmethod
    def mounted(path):
        escaped = str(path).replace("\\", "\\134").replace(" ", "\\040").replace("\t", "\\011").replace("\n", "\\012")
        return any(len(line.split()) > 4 and line.split()[4] == escaped
                   for line in Path("/proc/self/mountinfo").read_text().splitlines())

    def probe(self, endpoint, export, spec, result):
        key = self.checkpoint_key(endpoint, export, spec["mode"])
        if self.cached(key):
            result["probes"].append({**spec,"success":True,"cached":True,"cleanupComplete":True})
            self.success_keys.append(key)
            self.completed += 1
            self.notify("PROBE_EXPORTS")
            return
        if self.blocked or self.deadline-time.monotonic()<=2:
            result.update(success=None,status="PROBE_PENDING")
            if self.last_failure is None:self.failure("PROBE_DEADLINE",endpoint,export,spec["mode"])
            return
        root_info=Path(self.root).lstat()
        if not stat.S_ISDIR(root_info.st_mode) or root_info.st_uid!=os.geteuid() or root_info.st_mode&0o022:
            raise ValueError("NFS probe mount directory is not protected")
        directory=tempfile.mkdtemp(prefix="nfs-probe-",dir=self.root)
        started=time.monotonic()
        info={**spec,"success":False,"cached":False}
        active=False;mounted=None;cleanup_complete=False;failure=None;mount_success=False
        try:
            command=["mount","-t",spec["mountType"],"-o",spec["options"],spec["target"],directory]
            child=self.run_command(command,15,self.failure("PROBING",endpoint,export,spec["mode"]))
            if child.returncode:
                text=(child.stderr or child.stdout or "").lower()
                denied=any(token in text for token in ("access denied","permission denied","not permitted"))
                transient=any(token in text for token in ("timed out","connection refused","temporarily unavailable"))
                code="PROBE_PERMISSION_DENIED" if denied else "PROBE_NOT_READY" if transient else "MOUNT_FAILED"
                failure=self.failure(code,endpoint,export,spec["mode"],retryable=transient and not denied)
                info["exitCode"]=child.returncode
            else:mount_success=True
        except NfsReadinessError as error:
            failure=error.public
            active=failure["cleanup"]["childActive"]
            self.blocked=True
        except subprocess.TimeoutExpired as error:
            active=bool(getattr(error,"childActive",False))
            result.update(probeChildActive=active,probeChildPid=getattr(error,"childPid",None))
            failure=self.failure("PROBE_TIMEOUT",endpoint,export,spec["mode"],cleanup=True,active=active)
        except (OSError,ValueError,TypeError,KeyError):
            failure=self.failure("PROBE_OBSERVATION_FAILED",endpoint,export,spec["mode"],cleanup=True,retryable=False)
            self.blocked=True
        finally:
            try:
                if not active:
                    mounted=self.mounted(directory)
                    if type(mounted) is not bool:raise ValueError("NFS mount readback is unknown")
                    if mounted:
                        cleanup=self.run_command(["umount",directory],10)
                        mounted=self.mounted(directory)
                        if type(mounted) is not bool:raise ValueError("NFS cleanup mount readback is unknown")
                        if cleanup.returncode!=0 or mounted:raise ValueError("NFS cleanup is incomplete")
                    elif mount_success:
                        # The mount command's success is not sufficient proof of
                        # a still-mounted path, but an explicit umount receipt is
                        # retained for the normal synchronous mount path.
                        cleanup=self.run_command(["umount",directory],10)
                        if cleanup.returncode!=0:raise ValueError("NFS cleanup command failed")
                    os.rmdir(directory)
                    cleanup_complete=True
            except NfsReadinessError as error:
                info["cleanupFailure"]=error.public
                active=error.public["cleanup"]["childActive"]
            except subprocess.TimeoutExpired as error:
                active=bool(getattr(error,"childActive",False))
                result.update(probeChildActive=active,probeChildPid=getattr(error,"childPid",None))
                info["cleanupFailure"]=self.failure("CLEANUP_TIMEOUT",endpoint,export,spec["mode"],cleanup=True,active=active)
            except (OSError,ValueError,TypeError,KeyError):
                info["cleanupFailure"]=self.failure("CLEANUP_OBSERVATION_FAILED",endpoint,export,spec["mode"],cleanup=True,active=active,retryable=False)
            cleanup={"complete":cleanup_complete,"childActive":active,"mountPresent":mounted is True}
            if not cleanup_complete:
                self.blocked=True
                result.update(success=None,status="PROBE_PENDING",cleanupPending=True,probeChildActive=active)
                failure=failure or info.get("cleanupFailure") or self.failure("CLEANUP_PENDING",endpoint,export,spec["mode"],cleanup=True,active=active)
            elif failure:
                result.update(success=None if failure["timedOut"] else False,status="PROBE_PENDING" if failure["timedOut"] else "FAILED")
            else:
                try:self.save(key)
                except (OSError,ValueError,TypeError,KeyError):
                    failure=self.failure("PROBE_CHECKPOINT_FAILED",endpoint,export,spec["mode"],retryable=False)
                    self.blocked=True;result.update(success=False,status="FAILED")
                if failure is None:
                    info.update(success=True,cleanupComplete=True)
                    self.success_keys.append(key);self.completed+=1;self.last_failure=None
            if failure:
                failure={**failure,"cleanup":cleanup}
                info["failure"]=failure;self.last_failure=failure
            if info.get("cleanupFailure"):
                info["cleanupFailure"]={**info["cleanupFailure"],"cleanup":cleanup}
            info["elapsedSeconds"]=round(max(0,time.monotonic()-started),3)
            info["remainingSeconds"]=round(max(0,self.deadline-time.monotonic()),3)
            result["probes"].append(info)
            if not self.notify("PROBE_EXPORTS"):
                result.update(success=None,status="PROBE_PENDING")

    def run(self, runtime, endpoint_exports):
        local = []
        try:
            output = self.run_command(["ip", "-4", "-o", "addr", "show", "scope", "global"], 10)
            if output.returncode:
                raise ValueError("NFS probe local address observation failed")
            for line in output.stdout.splitlines():
                parts = line.split()
                if "inet" in parts:
                    local.append(str(ipaddress.ip_interface(parts[parts.index("inet") + 1]).ip))
        except subprocess.TimeoutExpired:
            self.blocked = True
            self.failure("PROBE_DEADLINE")
        representatives, remaining = [], []
        seen = set()
        for endpoint in runtime:
            endpoint.update(probeSuccess=True, exportsVisible=True, probeResults=[])
            for export in endpoint_exports.get((str(endpoint["listenIp"]), int(endpoint["port"])), []):
                result = {"exportUuid": export.get("uuid"), "exportName": export.get("name"),
                          "protocolMode": export.get("protocolMode", "V4_ONLY"), "targetPath": export.get("path"),
                          "pseudoPath": export.get("pseudo"), "success": True, "probes": []}
                endpoint["probeResults"].append(result)
                host, error = self.select_host(endpoint["listenIp"], export, local)
                if error:
                    self.skipped_acl+=1
                    result.update(success=None, status="SKIPPED_ACL", error=error)
                    endpoint["exportsVisible"] = None
                    continue
                modes = ("NFSv4", "NFSv3") if export.get("protocolMode") == "V3V4_DUAL" else ("NFSv4",)
                for mode in modes:
                    options = ("vers=4.1" if mode == "NFSv4" else "vers=3") + ",proto=tcp,port=" + str(endpoint["port"])
                    options += (",nolock" if mode == "NFSv3" else "") + ",ro,soft,timeo=1,retrans=1"
                    spec = {"mode": mode, "mountType": "nfs4" if mode == "NFSv4" else "nfs",
                            "target": host + ":" + export["pseudo"], "options": options}
                    task = endpoint, export, spec, result
                    group = endpoint["listenIp"], endpoint["port"], mode
                    (remaining if group in seen else representatives).append(task)
                    seen.add(group)
                    self.total += 1
        self.notify("PROBE_EXPORTS")
        for tasks,phase in ((representatives,"PROBE_ENDPOINTS"),(remaining,"PROBE_EXPORTS")):
            self.notify(phase)
            for task in tasks:
                if task[3].get("success") is not False:
                    try:self.probe(*task)
                    except (OSError,ValueError,TypeError,KeyError):
                        if self.last_failure is None or self.last_failure["cleanup"]["complete"]:
                            self.failure("PROBE_OBSERVATION_FAILED",task[0],task[1],task[2]["mode"],cleanup=True,retryable=False)
                        self.blocked=True
                        task[3].update(success=None,status="PROBE_PENDING",cleanupPending=True)

        failures = []
        for endpoint in runtime:
            pending = any(row.get("status") == "PROBE_PENDING" for row in endpoint["probeResults"])
            failed = any(row.get("success") is False for row in endpoint["probeResults"])
            endpoint["probeSuccess"] = not pending and not failed
            if endpoint["exportsVisible"] is not None:
                endpoint["exportsVisible"] = not pending and not failed
            if pending or failed:
                failures.append(endpoint)
            endpoint["completedProbeCount"] = sum(probe.get("success") is True for row in endpoint["probeResults"] for probe in row["probes"])
            endpoint["totalProbeCount"] = sum(2 if row["protocolMode"] == "V3V4_DUAL" else 1
                                               for row in endpoint["probeResults"] if row.get("status") != "SKIPPED_ACL")
            endpoint["lastFailure"] = next((probe["failure"] for row in endpoint["probeResults"] for probe in row["probes"] if probe.get("failure")), self.last_failure) if pending or failed else None
        try:
            if self.cache_enabled:
                self.write(self.cache_root/"summary.json",{
                    "bootId":self.boot_id,"desiredHash":os.environ.get("ABLESTACK_STORAGE_BOOT_DESIRED_HASH"),
                    "endpoints":[self.endpoint_binding(row) for row in runtime],
                    "successKeys":self.success_keys,"success":not failures,"completedProbeCount":self.completed,
                    "totalProbeCount":self.total,"skippedAclCount":self.skipped_acl,
                    "detailVerificationKind":"UNVERIFIED" if failures else "NOT_REQUIRED" if not runtime else
                    "EXACT_INVENTORY_AND_ELIGIBLE_MOUNTS" if self.skipped_acl else "MOUNT_PROBES_COMPLETE"})
        except (OSError,ValueError,TypeError,KeyError):
            failure=self.last_failure
            if failure is None:
                failure=self.failure("PROBE_CHECKPOINT_FAILED",retryable=False)
            for endpoint in runtime:
                endpoint.update(probeSuccess=False,lastFailure=failure)
                if endpoint not in failures:failures.append(endpoint)
        return failures

    @staticmethod
    def endpoint_binding(row):
        return {key:row.get(key) for key in ("listenIp","port","pid","startTicks","configurationSha256")}

    def verified_summary(self, runtime):
        value=self.protected_read(self.cache_root/"summary.json")
        expected={"bootId","desiredHash","endpoints","successKeys","success","completedProbeCount","totalProbeCount","skippedAclCount","detailVerificationKind"}
        if (set(value)!=expected or value["bootId"]!=self.boot_id
                or value["desiredHash"]!=os.environ.get("ABLESTACK_STORAGE_BOOT_DESIRED_HASH")
                or json.dumps(value["endpoints"],sort_keys=True)!=json.dumps([self.endpoint_binding(row) for row in runtime],sort_keys=True)
                or value["success"] is not True
                or type(value["skippedAclCount"]) is not int or value["skippedAclCount"]<0
                or value["detailVerificationKind"]!=("NOT_REQUIRED" if not runtime else
                    "EXACT_INVENTORY_AND_ELIGIBLE_MOUNTS" if value["skippedAclCount"] else "MOUNT_PROBES_COMPLETE")
                or type(value["totalProbeCount"]) is not int
                or type(value["completedProbeCount"]) is not int
                or value["totalProbeCount"]!=value["completedProbeCount"]
                or len(value["successKeys"])!=value["totalProbeCount"]
                or not all(key is not None and self.cached(key) for key in value["successKeys"])):
            raise ValueError("NFS probe summary does not match the fresh exact runtime")
        return {"success":True,"runtimeReady":True,"detailVerified":True,"probePending":0,
                "completedProbeCount":value["completedProbeCount"],"totalProbeCount":value["totalProbeCount"],
                "skippedAclCount":value["skippedAclCount"],"detailVerificationKind":value["detailVerificationKind"],"lastFailure":None}


class NfsReadinessError(RuntimeError):
    def __init__(self, code, endpoint=None):
        self.public = {"protocol":"NFS","phase":"WAIT_RUNTIME_READY",
                       "endpointIp":(endpoint or {}).get("listenIp"),"endpointPort":(endpoint or {}).get("port"),
                       "exportUuid":None,"exportName":None,"protocolMode":None,"code":code,
                       "timedOut":False,"retryable":True,
                       "cleanup":{"complete":True,"childActive":False,"mountPresent":False}}
        super().__init__(code)


class NfsEndpointReadiness:
    def __init__(self, deadline, adapter_type, command, config_root=None):
        self.deadline, self.adapter_type, self.command = deadline, adapter_type, command
        self.config_root = Path(config_root or "/etc/ganesha/ablestack-storage")
        self.records = NfsProbeRunner({}, "/run/ablestack-storage/nfs-probe", deadline, None)
        self.records.cache_root = Path("/run/ablestack-storage/nfs-endpoint-checkpoints")

    def snapshot(self, configuration, pid, rendered=False):
        path = Path(configuration)
        if path.parent != self.config_root or path.suffix != ".conf" or type(pid) is not int or pid <= 0:
            raise ValueError("NFS endpoint configuration or process is outside its fixed scope")
        parent, named = path.parent.lstat(), path.lstat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or parent.st_mode & 0o022:
            raise ValueError("NFS endpoint configuration parent is not protected")
        if stat.S_ISLNK(named.st_mode):
            target = os.readlink(path)
            if not rendered or not target.startswith("/var/lib/ablestack-storage/rendered-generations/current/nfs/ganesha/"):
                raise ValueError("NFS endpoint configuration link is not its verified rendered current")
            info = path.stat()
        else:
            info = named
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022 or info.st_size > 8 * 1024 * 1024:
            raise ValueError("NFS endpoint configuration is not protected")
        fd = os.open(path, os.O_RDONLY | (0 if stat.S_ISLNK(named.st_mode) else os.O_NOFOLLOW))
        try:
            opened = os.fstat(fd)
            if self.records.identity(opened) != self.records.identity(info):
                raise ValueError("NFS endpoint configuration changed while opening")
            data = os.read(fd, 8 * 1024 * 1024 + 1)
            if (self.records.identity(opened) != self.records.identity(os.fstat(fd))
                    or self.records.identity(info) != self.records.identity(path.stat())
                    or self.records.identity(named) != self.records.identity(path.lstat())):
                raise ValueError("NFS endpoint configuration changed while reading")
        finally:
            os.close(fd)
        process = Path("/proc") / str(pid)
        ticks = (process / "stat").read_text().rpartition(")")[2].split()[19]
        args = (process / "cmdline").read_bytes().split(b"\0")
        if (not args or not args[0].endswith(b"ganesha.nfsd") or args.count(b"-f") != 1
                or args[args.index(b"-f") + 1].decode() != str(path)
                or ticks != (process / "stat").read_text().rpartition(")")[2].split()[19]):
            raise ValueError("NFS endpoint process has a foreign binding")
        return {"bootId": self.records.boot_id, "configurationSha256": hashlib.sha256(data).hexdigest(),
                "pid": pid, "startTicks": ticks}, data.decode()

    def listener_deadline(self, configuration, pid, rendered=False):
        identity, content = self.snapshot(configuration, pid, rendered)
        # Ganesha initializes CLIENT policies before opening its listener. Use
        # the exact protected serializer's clients (expanded principals are a
        # conservative upper bound on CLIENT blocks), not only export count.
        clients = sum(len(row["clients"]) for row in self.adapter_type.configuration(content)["exports"])
        # Share the caller's total deadline; keep five seconds for its existing
        # stop/rollback cleanup. Waiting does not restart a healthy initializer.
        return min(self.deadline - 5, time.monotonic() + 15 + 2 * clients), identity

    def path(self, key):
        if not key or any(char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-" for char in key):
            raise ValueError("NFS endpoint unit key is invalid")
        return self.records.cache_root / (key + ".json")

    def remember(self, key, configuration, pid, rendered=False):
        identity, _ = self.snapshot(configuration, pid, rendered)
        if self.records.cache_enabled:
            self.records.write(self.path(key), identity)
        return identity

    def reuse(self, key, configuration, rendered=False):
        if not self.records.cache_enabled:
            return None
        path = self.path(key)
        if not path.exists() and not path.is_symlink():
            return None
        saved = self.records.protected_read(path)
        if saved.get("bootId") != self.records.boot_id:
            return None
        process = self.command(["systemctl", "show", "-p", "MainPID", "--value",
                                "ablestack-storage-ganesha@" + key + ".service"], 5)
        try:
            pid = int(process.stdout.strip())
            actual, _ = self.snapshot(configuration, pid, rendered)
        except (ValueError, FileNotFoundError, ProcessLookupError):
            return None
        return actual if process.returncode==0 and json.dumps(actual,sort_keys=True)==json.dumps(saved,sort_keys=True) else None

    def wait(self, endpoint, configuration, rendered=False):
        before, content = self.snapshot(configuration, endpoint["pid"], rendered)
        # Inventory is only readiness: it does not replace the endpoint/protocol
        # and export visibility mounts, or grant UpdateExport/RemoveExport.
        deadline = min(self.deadline, time.monotonic() + 20)
        while time.monotonic() < deadline:
            try:
                adapter = self.adapter_type(deadline=deadline)
                _, manager = adapter.owned_endpoint_manager(endpoint["listenIp"], endpoint["port"], endpoint["pid"])
                adapter.verify(manager, adapter.configuration(content)["exports"])
                if endpoint["protocolMode"] == "V3V4_DUAL":
                    host = "127.0.0.1" if endpoint["listenIp"] == "0.0.0.0" else endpoint["listenIp"]
                    result = self.command(["rpcinfo", "-T", "tcp", host, "100003", "3"],
                                          max(.01, min(3, deadline - time.monotonic())))
                    if result.returncode != 0:
                        raise ValueError("NFSv3 RPC registration is not ready")
                after, _ = self.snapshot(configuration, endpoint["pid"], rendered)
                if before != after:
                    raise NfsReadinessError("ENDPOINT_IDENTITY_CHANGED", endpoint)
                endpoint.update(after, exportInventoryReady=True, exportBindings=[{key: row[key] for key in ("exportId","path","pseudo")} for row in adapter.configuration(content)["exports"]])
                return endpoint
            except NfsReadinessError:
                raise
            except Exception:
                if self.deadline <= time.monotonic():
                    break
                time.sleep(min(.2, max(0, deadline - time.monotonic())))
        raise NfsReadinessError("EXPORT_INVENTORY_NOT_READY", endpoint)


def nfs_export_endpoint_ips(export_config, default_ip):
    mode = str(export_config.get("endpointMode") or export_config.get("endpointmode") or "").strip().upper()
    raw_ips = export_config.get("listenIps") if "listenIps" in export_config else export_config.get("listenips")
    if isinstance(raw_ips, list):
        ips = [str(item).strip() for item in raw_ips if str(item).strip()]
    elif raw_ips:
        ips = [str(item).strip() for item in str(raw_ips).split(",") if str(item).strip()]
    else:
        ips = []
    if mode == "SELECTED":
        if not ips:
            return [default_ip or "0.0.0.0"]
        return ips
    if not mode and ips:
        return ips
    return [default_ip or "0.0.0.0"]

def nfs_export_listener_ports(export_config, default_port, protocol_mode):
    if str(protocol_mode or "V4_ONLY").strip().upper() == "V3V4_DUAL":
        return [2049]
    raw_ports = None
    for key in ("listenerGroupPorts", "listenergroupports", "listenerPorts", "listenerports"):
        if key in export_config:
            raw_ports = export_config.get(key)
            break
    if isinstance(raw_ports, list):
        values = raw_ports
    elif raw_ports is None or str(raw_ports).strip() == "":
        values = []
    else:
        values = str(raw_ports).split(",")
    ports = []
    seen = set()
    for value in values:
        try:
            port = int(str(value).strip())
        except Exception:
            continue
        if 1 <= port <= 65535 and port not in seen:
            seen.add(port)
            ports.append(port)
    if not ports:
        try:
            fallback = int(default_port or 2049)
        except Exception:
            fallback = 2049
        ports.append(fallback)
    return ports

def nfs_boot_workload(payload):
    if type(payload) is not dict:raise ValueError("NFS boot workload requires its saved payload")
    if payload.get("enabled") is False:return {"probeTasks":0,"endpointModeGroups":0,"concurrency":1}
    mode=str(payload.get("protocolMode") or payload.get("protocolmode") or "V4_ONLY").strip().upper()
    mode=mode if mode in ("V4_ONLY","V3V4_DUAL") else "V4_ONLY"
    default_ip=str(payload.get("listenIp") or "0.0.0.0").strip() or "0.0.0.0"
    default_port=int(payload.get("port") or 2049)
    endpoints={};active=[]
    for index,export in enumerate(payload.get("exports",[])):
        if export.get("state","Ready") in ("Disabled","Destroyed","Error"):continue
        active.append(export.get("uuid") or export.get("name") or export.get("path") or index)
        config=export.get("config") or {}
        for ip in nfs_export_endpoint_ips(config,default_ip):
            ip=str(ipaddress.IPv4Address(ip))
            for port in nfs_export_listener_ports(config,default_port,mode):
                endpoints[(ip,port)]=endpoints.get((ip,port),0)+1
    if mode=="V3V4_DUAL":
        count=len(set(active))
        return {"probeTasks":2*count,"endpointModeGroups":2 if count else 0,"concurrency":1}
    return {"probeTasks":sum(endpoints.values()),"endpointModeGroups":len(endpoints),"concurrency":1}


def verify_nfs_boot_payload(payload_path, record, adapter_type, command, deadline):
    """Read-only verification of a legacy boot's protected exact successful probes."""
    path=Path(payload_path)
    parent,info=path.parent.lstat(),path.lstat()
    if (not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o022
            or not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600
            or info.st_size>8*1024*1024):
        raise ValueError("Saved boot NFS payload is not protected")
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        opened=os.fstat(fd)
        if NfsProbeRunner.identity(opened)!=NfsProbeRunner.identity(info):raise ValueError("Saved NFS payload changed")
        data=os.read(fd,8*1024*1024+1)
        if (NfsProbeRunner.identity(opened)!=NfsProbeRunner.identity(os.fstat(fd))
                or NfsProbeRunner.identity(opened)!=NfsProbeRunner.identity(path.lstat())):
            raise ValueError("Saved NFS payload changed while reading")
    finally:os.close(fd)
    boot_id=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    if (record["bootId"]!=boot_id or record["desiredHash"]!=os.environ.get("ABLESTACK_STORAGE_BOOT_DESIRED_HASH")
            or hashlib.sha256(data).hexdigest()!=record["_internal"]["payloadDigests"].get("NFS")):
        raise ValueError("Saved NFS payload differs from the boot transaction")
    payload=json.loads(data);expected=set()
    mode=str(payload.get("protocolMode") or payload.get("protocolmode") or "V4_ONLY").strip().upper()
    if payload.get("enabled") is not False:
        for export in payload.get("exports",[]):
            if export.get("state","Ready") in ("Disabled","Destroyed","Error"):continue
            if mode=="V3V4_DUAL":expected.add(("0.0.0.0",2049));continue
            config=export.get("config") or {}
            for ip in nfs_export_endpoint_ips(config,payload.get("listenIp") or "0.0.0.0"):
                for port in nfs_export_listener_ports(config,payload.get("port") or 2049,mode):
                    expected.add((str(ipaddress.IPv4Address(ip)),port))
    probe=NfsProbeRunner({},"/run/ablestack-storage/nfs-probe",deadline,None)
    summary=probe.protected_read(probe.cache_root/"summary.json")
    rows=summary.get("endpoints",[])
    if {(row["listenIp"],row["port"]) for row in rows}!=expected or len(rows)!=len(expected):
        raise ValueError("NFS boot endpoint set differs from its saved payload")
    readiness=NfsEndpointReadiness(deadline,adapter_type,command)
    runtime=[]
    for row in rows:
        key=str(row["listenIp"]).replace(":","_")+"_"+str(row["port"])
        configuration=readiness.config_root/(key+".conf")
        endpoint={**row,"protocolMode":"V3V4_DUAL" if mode=="V3V4_DUAL" else "V4_ONLY"}
        readiness.wait(endpoint,configuration)
        host="127.0.0.1" if row["listenIp"]=="0.0.0.0" else row["listenIp"]
        import socket
        with socket.create_connection((host,row["port"]),timeout=max(.01,min(1,deadline-time.monotonic()))):pass
        runtime.append(endpoint)
    result=probe.verified_summary(runtime)
    if NfsProbeRunner.identity(info)!=NfsProbeRunner.identity(path.lstat()):
        raise ValueError("Saved NFS payload changed during runtime readback")
    return result
