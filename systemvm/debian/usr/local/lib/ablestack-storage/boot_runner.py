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

"""Bounded boot children share one monotonic budget and exact pidfd termination."""
import json
import os
from pathlib import Path
import stat
import tempfile
import signal
import subprocess
import time


class BootRunner:
    def __init__(self,deadline):
        self.deadline=float(deadline)
        self.journal=Path(os.environ.get("ABLESTACK_STORAGE_BOOT_CHILD_JOURNAL","/run/ablestack-storage/boot-execution/current.json"))

    def checkpoint(self,phase,child,arguments):
        self.journal.parent.mkdir(parents=True,mode=0o700,exist_ok=True)
        info=self.journal.parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o077:raise ValueError("Boot execution journal is not protected")
        ticks=None
        try:ticks=Path("/proc/"+str(child.pid)+"/stat").read_text().rpartition(")")[2].split()[19]
        except FileNotFoundError:pass
        record={"phase":phase,"childPid":child.pid,"startTicks":ticks,"command":Path(arguments[0]).name,"childActive":child.poll() is None,
                "bootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),"updatedEpoch":time.time()}
        descriptor,temporary=tempfile.mkstemp(prefix=".boot-child-",dir=self.journal.parent)
        try:
            os.fchmod(descriptor,0o600)
            with os.fdopen(descriptor,"w") as handle:json.dump(record,handle);handle.flush();os.fsync(handle.fileno())
            os.replace(temporary,self.journal)
            directory=os.open(self.journal.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try:os.fsync(directory)
            finally:os.close(directory)
        finally:
            if os.path.exists(temporary):os.unlink(temporary)

    def run(self,arguments,stdin_fd=None,heartbeat=None,capture=False):
        remaining=self.deadline-time.monotonic()
        if remaining<=0:raise TimeoutError("Storage boot total deadline exceeded before another command")
        if not callable(getattr(os,"pidfd_open",None)) or not callable(getattr(signal,"pidfd_send_signal",None)):
            raise ValueError("Storage boot requires exact pidfd child control")
        inherited=(9,) if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")=="9" else ()
        if stdin_fd is not None:inherited=(*inherited,stdin_fd)
        output=os.memfd_create("storage-boot-public-output",os.MFD_CLOEXEC) if capture else None
        child=subprocess.Popen(arguments,stdin=stdin_fd if stdin_fd is not None else subprocess.DEVNULL,
                               stdout=output if capture else None,stderr=subprocess.DEVNULL if capture else None,
                               pass_fds=inherited)
        descriptor=None
        def terminate():
            self.checkpoint("TIMED_OUT_PENDING_RECONCILE",child,arguments)
            if descriptor is not None:
                try:signal.pidfd_send_signal(descriptor,signal.SIGTERM,None,0)
                except ProcessLookupError:pass
            try:child.wait(timeout=max(.001,min(.5,self.deadline-time.monotonic())))
            except subprocess.TimeoutExpired:
                if descriptor is not None:
                    try:signal.pidfd_send_signal(descriptor,signal.SIGKILL,None,0)
                    except ProcessLookupError:pass
                try:child.wait(timeout=max(.001,min(.5,self.deadline-time.monotonic())))
                except subprocess.TimeoutExpired:pass
            self.checkpoint("RECOVERY_REQUIRED" if child.poll() is None else "TIMED_OUT",child,arguments)
        try:
            try:descriptor=os.pidfd_open(child.pid,0)
            except ProcessLookupError:
                if child.poll() is None:raise ValueError("Storage boot child identity is unavailable")
            self.checkpoint("RUNNING",child,arguments)
            while True:
                if heartbeat is not None:heartbeat()
                if capture and os.fstat(output).st_size>16*1024*1024:
                    raise ValueError("Storage boot output exceeds its bounded public result")
                remaining=self.deadline-time.monotonic()-1
                if remaining<=0:raise subprocess.TimeoutExpired(arguments,0)
                try:
                    code=child.wait(timeout=max(.001,min(1,remaining)))
                    break
                except subprocess.TimeoutExpired:
                    if time.monotonic()>=self.deadline-1:raise
            self.checkpoint("COMPLETE" if code==0 else "FAILED",child,arguments)
            result={"success":code==0,"exitCode":code,"childActive":False}
            if capture:
                if os.fstat(output).st_size>16*1024*1024:raise ValueError("Storage boot output exceeds its limit")
                os.lseek(output,0,os.SEEK_SET);data=bytearray()
                while len(data)<=16*1024*1024:
                    piece=os.read(output,min(65536,16*1024*1024+1-len(data)))
                    if not piece:break
                    data.extend(piece)
                result["output"]=data.decode("utf-8")
            return result
        except subprocess.TimeoutExpired:
            terminate()
            return {"success":False,"exitCode":child.poll(),"childActive":child.poll() is None,"deadlineExceeded":True,
                    "recoveryRequired":True,"childPid":child.pid}
        except BaseException:
            if child.poll() is None:terminate()
            raise
        finally:
            if descriptor is not None:os.close(descriptor)
            if output is not None:os.close(output)
