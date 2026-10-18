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

"""LOCAL Gate C adapter: real rendered producer; explicit OS boundary only."""
from pathlib import Path
import configparser
import contextlib
import fcntl
import hashlib
import importlib
import json
import os
import stat
import shutil
import copy
import io
import socket
import sys
import time
import types
import traceback
import builtins
import tempfile
from case_filesystem_guard import CasePathGuard,is_write_mode,is_write_flags,denied
import re
from unittest.mock import patch

def require(value, code):
    if not value:raise ValueError(code)

class RenderedGuestFixture:
    def __init__(self, repository, case, generation):
        self.repository,self.case,self.generation=Path(repository),Path(case),generation
        require(self.case.name=="native","EXACT_NATIVE_CASE_CHILD_REQUIRED")
        self.path_guard=CasePathGuard(self.case.parent)
        self.lib=self.repository/"systemvm/debian/usr/local/lib/ablestack-storage"
        if str(self.lib) not in sys.path:sys.path.insert(0,str(self.lib))
        self.driver_module=importlib.import_module("rendered_driver")
        self.runtime_module=importlib.import_module("native_render_runtime")
        self.store_module=importlib.import_module("rendered_generation")
        self.credential_module=importlib.import_module("nvme_credentials")
        self.nvme_cleanup_module=importlib.import_module("nvme_cleanup")
        self.nvme_identity_module=importlib.import_module("nvme_runtime_identity")
        self.cli=self.repository/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        self.osroot=self.case/"rendered-os-fixture"
        for name in ("etc/systemd/system","etc/samba","etc/ganesha/ablestack-storage",
                     "etc/ablestack-storage/secrets","sys/kernel/config/target/iscsi",
                     "sys/kernel/config/nvmet/subsystems","sys/kernel/config/nvmet/hosts",
                     "sys/kernel/config/nvmet/ports","proc","boot"):
            (self.osroot/name).mkdir(parents=True,exist_ok=True,mode=0o700)
        self.store=self.store_module.RenderedGeneration(self.case/"rendered-generations")
        self.driver=self.driver_module.RenderedDriver(self.cli,self.store)
        self.driver.nfs_config_root=self.osroot/"etc/ganesha/ablestack-storage"
        self.driver.runtime.nfs_config_root=self.driver.nfs_config_root
        self.driver.runtime.iscsi_root=self.osroot/"sys/kernel/config/target/iscsi"
        self.driver.runtime.nvme_root=self.osroot/"sys/kernel/config/nvmet"
        self.driver.runtime.iscsi_credentials_path=self.generation.config/"secrets/iscsi-acl-secrets.json"
        self.driver.runtime.nvme_credentials=self.credential_module.NvmeCredentialStore(
            self.generation.config/"secrets/nvmeof-acl-secrets.json")
        self.driver.runtime.command=self.command
        self.effects=[]
        self.original_path=Path
        self.os_state_path=self.case/"rendered-service-os-state.json"
        self.network_module=importlib.import_module("rendered_network")
        self.original_which=shutil.which
        self.ganesha_module=importlib.import_module("ganesha_dbus")
        kernel=self.osroot/("boot/config-"+os.uname().release)
        if not kernel.exists():
            kernel.write_text("CONFIG_TARGET_CORE=y\nCONFIG_ISCSI_TARGET=y\nCONFIG_NVME_TARGET=y\nCONFIG_NVME_TARGET_TCP=y\n")
            kernel.chmod(0o600)
        self.source_module=importlib.import_module("smb_source_checkpoint")
        self.identity_module=importlib.import_module("smb_identity")
        self.capsule_module=importlib.import_module("identity_capsule")
        self.maintenance_module=importlib.import_module("template_maintenance")
        self.service_module=importlib.import_module("service_maintenance")
        self.maintenance=self.maintenance_module.Maintenance(self.case/"maintenance",self.generation.status)
        self.service=self.service_module.ServiceMaintenance(self.maintenance,self.cli,
            run=self.service_os_run,command=self.command)
        self.initialize_local_os()
        self.handler=self.identity_module.SmbIdentity(self.cli)
        self.handler.configuration=self.generation.config
        self.handler.generations=self.generation.root
        self.handler.process_root=self.osroot/"proc"
        self.handler.run=self.smb_os_run
        self.handler.unit_path=self.osroot/"etc/systemd/system/ablestack-storage-smb@.service"
        self.local=self.source_module.SmbSourceCheckpoint(self.cli,self.handler,self.command,
                                                         self.case/"smb-local-source-checkpoints")
        self.raw_regular_file=self.capsule_module.regular_file
        self.local.reader=self.local_reader
        self.local.unit_authority.reader=self.local_reader
    def mapped_path(self, value="."):
        value=os.fsdecode(value)
        if value in("/etc/passwd","/etc/group","/etc/shadow","/etc/gshadow"):
            return self.osroot/value.lstrip("/")
        if value=="/etc":return self.osroot/"etc"
        if value in("/usr","/usr/share")or value=="/usr/share/samba"or value.startswith("/usr/share/samba/"):
            return self.osroot/value.lstrip("/")
        if value in ("/proc/self/stat","/proc/sys/kernel/random/boot_id") or value.startswith("/proc/"+str(os.getpid())+"/"):
            return self.original_path(value)
        if value=="/etc/ablestack-storage"or value.startswith("/etc/ablestack-storage/"):
            return self.generation.config/value.removeprefix("/etc/ablestack-storage").lstrip("/")
        for prefix in ("/etc/systemd/system","/etc/samba","/etc/ganesha","/proc","/boot","/run/ablestack-storage",
                       "/var/lib/samba","/var/lib/dpkg/info","/lib/systemd/system","/usr/sbin"):
            if value==prefix or value.startswith(prefix+"/"):
                return self.osroot/value.lstrip("/")
        return self.original_path(value)
    def initialize_local_os(self):
        self.generation.config.mkdir(parents=True,exist_ok=True,mode=0o700)
        (self.generation.config/"desired-state").mkdir(exist_ok=True,mode=0o700)
        def put(name,value,mode=0o600):
            path=self.mapped_path(name);path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
            if not path.exists():path.write_text(value);path.chmod(mode)
        put("/etc/samba/smb.conf","[global]\nsecurity = user\n")
        source_config=self.mapped_path("/etc/samba/smb.conf")
        put("/usr/share/samba/smb.conf",source_config.read_text(),stat.S_IMODE(source_config.stat().st_mode))
        for name in ("/etc/passwd","/etc/group","/etc/shadow","/etc/gshadow"):
            path=self.osroot/name.lstrip("/")
            path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
            if not path.exists():path.write_text("\n");path.chmod(0o600)
        put("/proc/sys/kernel/random/boot_id",Path("/proc/sys/kernel/random/boot_id").read_text())
        checksums=[]
        for unit in ("smbd.service","nmbd.service"):
            name="/lib/systemd/system/"+unit
            put(name,"[Service]\nExecStart=/usr/sbin/"+unit[:-8]+" -F\n",0o644)
            checksums.append(hashlib.md5(self.mapped_path(name).read_bytes()).hexdigest()+"  "+name.lstrip("/"))
            name="/usr/sbin/"+unit[:-8]
            put(name,"synthetic OS package fixture, never executable in this test\n",0o755)
            checksums.append(hashlib.md5(self.mapped_path(name).read_bytes()).hexdigest()+"  "+name.lstrip("/"))
        put("/var/lib/dpkg/info/samba.md5sums","\n".join(checksums)+"\n")
    def local_reader(self,name,maximum=8*1024*1024):
        name=str(name)
        mapped=self.osroot/name.lstrip("/") if name in self.capsule_module.ACCOUNT_FILES else self.mapped_path(name)
        return self.raw_regular_file(str(mapped),maximum)
    def smb_os_run(self,arguments):
        args=list(arguments);state=self.os_state();smb=state["domains"].get("SMB",{})
        if len(args)==3 and args[:2]==["testparm","-s"]:
            path=self.original_path(args[2])
            require(path.parent==self.osroot/"etc/samba"and path.name.startswith(".local-source-smb-"),
                    "LOCAL_TESTPARM_CANDIDATE_SCOPE")
            return self.os_run(args).stdout
        if args==["smbstatus","--json"] or args==["smbstatus","--json","--locks"]:
            require(not state["connections"],"OS_SMB_SESSIONS_NOT_EMPTY")
            return json.dumps({"sessions":{},"tcons":{},"open_files":{}})
        if args[:1]==["net"] and args[1:]==["getlocalsid"]:
            accounts=self.samba_account_state()
            return "SID for domain "+accounts["netbiosName"]+" is: "+accounts["machineSid"]+"\n"
        if args[:1]==["pdbedit"] and args[1:]==["-L"]:
            accounts=self.samba_account_state()
            return "".join(row["principal"]+":"+str(row["uid"])+":Fixture SMB account\n"for row in accounts["users"])
        if args[:2]==["systemctl","show"] and len(args)==5 and args[-1]=="--value":
            unit=args[2];field=args[3]
            owned=next((row for row in smb.get("endpoints",[])if row.get("unit")==unit),None)
            if owned:
                values={"--property=MainPID":str(owned["pid"])+"\n",
                    "--property=FragmentPath":str(self.handler.unit_path)+"\n","--property=DropInPaths":"",
                    "--property=ControlGroup":"/system.slice/"+unit+"\n","--property=Slice":"system.slice\n"}
                require(field in values,"OS_SMB_UNIT_PROPERTY_UNKNOWN");return values[field]
        if args==["ss","-H","-ltnp"]:
            return "".join("LISTEN 0 50 "+row["listenIp"]+":"+str(row["port"])+" 0.0.0.0:* users:((\"smbd\",pid="+str(row["pid"])+",fd=50))\n"
                for row in smb.get("endpoints",[]))
        if args==["ss","-H","-ntp"]:
            require(not state["connections"],"OS_SMB_CONNECTIONS_NOT_EMPTY");return ""
        if args[:2]==["systemctl","show"] and len(args)==5 and args[-1]=="--value":
            unit=args[2];field=args[3]
            require(unit in ("smbd.service","nmbd.service","winbind.service"),"LOCAL_OS_UNKNOWN_UNIT")
            if field=="--property=MainPID":return "0\n"
            if field=="--property=FragmentPath":return "/lib/systemd/system/"+unit+"\n"
            if field=="--property=DropInPaths":
                return "/etc/systemd/system/"+unit+".d/ablestack-storage-gate.conf\n"
        if args[:1]==["ss"] and args[1:]in (["-H","-ltnp"],["-H","-ntp"]):return ""
        raise ValueError("LOCAL_OS_COMMAND_NOT_IMPLEMENTED")
    def samba_account_state(self):
        accounts=self.os_state().get("sambaAccounts")
        require(accounts is not None,"OS_NO_LOCAL_SAM")
        for name in("passdb.tdb","secrets.tdb"):
            path=self.osroot/"var/lib/samba/private"/name
            expected=("SYNTHETIC_OS_SAM_V1\n"+json.dumps({**accounts,"database":name},sort_keys=True)+"\n").encode()
            require(path.is_file()and not path.is_symlink()and stat.S_IMODE(path.stat().st_mode)==0o600
                and path.read_bytes()==expected,"OS_SAM_DATABASE_AND_PUBLIC_IDENTITY_DIFFER")
        return accounts
    def apply_samba_accounts(self,payload,manifest):
        # This is a stateful OS account/Samba boundary, not a TDB implementation.
        # No SID, database or account exists before a real signed runtime request
        # supplies and binds its LOCAL credential after baseline/SOURCE capture.
        require(payload.get("instanceUuid")==manifest["scope"]["instanceUuid"],"OS_SAM_INSTANCE_CHANGED")
        users={}
        for share in payload.get("shares",[]):
            for acl in share.get("acls",[]):
                if not (acl.get("config")or{}).get("localAccount"):continue
                principal=acl.get("principal");password=acl.get("password")
                require(isinstance(principal,str)and re.fullmatch("[a-z_][a-z0-9_-]{0,31}",principal)
                    and principal not in("root","nobody")and isinstance(password,str)and bool(password),"OS_LOCAL_ACCOUNT_CREDENTIAL_BINDING")
                digest=hashlib.sha256(password.encode()).hexdigest()
                row={"principal":principal,"credentialDigest":digest,"resourceUuid":acl["uuid"],
                     "uid":10000+int(hashlib.sha256(principal.encode()).hexdigest()[:4],16)}
                require(principal not in users or users[principal]==row,"OS_LOCAL_ACCOUNT_CONFLICT");users[principal]=row
        if not users:return
        state=self.os_state();old=state.get("sambaAccounts")
        seed=hashlib.sha256(manifest["scope"]["instanceUuid"].encode()).hexdigest()
        sid="S-1-5-21-"+"-".join(str(int(seed[n:n+8],16))for n in(0,8,16))
        account={"kind":"SYNTHETIC_OS_SAM_V1","scope":manifest["scope"],"machineSid":sid,
            "netbiosName":payload.get("netbiosName")or"FIXTURE","users":list(users.values())}
        require(old is None or old==account,"OS_SAM_ALREADY_OWNED_BY_DIFFERENT_APPLY")
        private=self.osroot/"var/lib/samba/private";private.mkdir(parents=True,exist_ok=True,mode=0o700)
        for name in("passdb.tdb","secrets.tdb"):
            path=private/name
            raw=("SYNTHETIC_OS_SAM_V1\n"+json.dumps({**account,"database":name},sort_keys=True)+"\n").encode()
            require(not path.exists()or path.read_bytes()==raw,"OS_FOREIGN_TDB_WOULD_BE_REPLACED")
            path.write_bytes(raw);path.chmod(0o600)
        for name in("passwd","group","shadow","gshadow"):
            path=self.osroot/"etc"/name
            rows=[]
            for row in users.values():
                user=row["principal"];uid=str(row["uid"])
                rows.append(user+":"+("x:"+uid+":"+uid+":Fixture SMB:/nonexistent:/usr/sbin/nologin"if name=="passwd"
                    else"x:"+uid+":"if name=="group"else"!:0:0:99999:7:::"if name=="shadow"else"!::"))
            raw="\n".join(rows)+"\n"
            require(path.read_text()in("","\n",raw),"OS_FOREIGN_POSIX_ACCOUNT_WOULD_BE_REPLACED")
            path.write_text(raw);path.chmod(0o600)
        managed=self.generation.config/"smb-managed-identities.json"
        managed.write_text("{}\n");managed.chmod(0o600)
        provenance={"schemaVersion":1,"accounts":{}}
        for name in("passwd","group"):
            provenance["accounts"]["/etc/"+name]=(self.osroot/"etc"/name).read_text().splitlines()
        path=self.generation.config/"smb-local-account-provenance.json"
        path.write_text(json.dumps(provenance,sort_keys=True)+"\n");path.chmod(0o600)
        state["sambaAccounts"]=account;self.write_state(state)
        self.effects.append({"kind":"STATEFUL_OS_SAM_CREATED_AFTER_REAL_LOCAL_APPLY","scope":manifest["scope"],
            "principals":sorted(users),"privateDatabaseCount":2,"plaintextCredentialPersisted":False})
    def samba_processes(self,record):
        self.handler.unit_path.write_text(self.handler.managed_unit_definition());self.handler.unit_path.chmod(0o644)
        registry=self.generation.config/"smb-endpoint-listeners";registry.mkdir(mode=0o700,exist_ok=True)
        for index,row in enumerate(record["endpoints"]):
            key=hashlib.sha256((row["listenIp"]+":"+str(row["port"])).encode()).hexdigest()[:24]
            unit="ablestack-storage-smb@"+key+".service";pid=900100+index
            process=self.osroot/"proc"/str(pid);process.mkdir(mode=0o700,exist_ok=True)
            (process/"fd").mkdir(mode=0o700,exist_ok=True)
            (process/"comm").write_text("smbd\n")
            (process/"cgroup").write_text("0::/system.slice/"+unit+"\n")
            (process/"stat").write_text(str(pid)+" (smbd) "+" ".join(["S"]+["0"]*18+["10000"])+"\n")
            (process/"status").write_text("Uid:\t0\t0\t0\t0\n")
            exe=process/"exe"
            if not exe.exists():exe.symlink_to(self.osroot/"usr/sbin/smbd")
            argv=["/usr/sbin/smbd","--foreground","--no-process-group","-s","/etc/samba/smb.conf",
                "--option=smb ports="+str(row["port"]),"--option=pid directory=/run/ablestack-storage/smb/"+key]
            argv.extend(["--option=bind interfaces only=no"]if row["listenIp"]in("0.0.0.0","::")else
                ["--option=interfaces="+row["listenIp"],"--option=bind interfaces only=yes"])
            (process/"cmdline").write_bytes(("\0".join(argv)+"\0").encode())
            entry=registry/(key+".json");entry.write_text(json.dumps({"listenIp":row["listenIp"],"port":row["port"]}));entry.chmod(0o600)
            row.update(pid=pid,unit=unit)
    @contextlib.contextmanager
    def actual_writer_lock(self):
        path=self.case/"desired-writer.lock"
        existed=path.exists()
        fd=os.open(path,os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
        require(stat.S_IMODE(os.fstat(fd).st_mode)==0o600,"TEST_WRITER_LOCK_NOT_PRIVATE")
        saved=None
        try:
            try:saved=os.dup(9)
            except OSError:pass
            if fd!=9:os.dup2(fd,9);os.close(fd)
            fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
            os.environ["ABLESTACK_STORAGE_WRITER_LOCK_FD"]="9"
            os.environ["ABLESTACK_STORAGE_WRITER_LOCK_FILE"]=str(path)
            yield
        finally:
            os.close(9)
            if saved is not None:os.dup2(saved,9);os.close(saved)
    @contextlib.contextmanager
    def local_os_boundary(self):
        original_exists=os.path.exists;original_lexists=os.path.lexists;original_realpath=os.path.realpath
        original_readlink=os.readlink;original_open=os.open;original_unlink=os.unlink;original_replace=os.replace
        original_makedirs=os.makedirs;original_mkstemp=tempfile.mkstemp;original_builtin_open=builtins.open
        original_io_open=io.open;guard=self.path_guard;guard.check_root()
        originals={name:getattr(os,name)for name in("chmod","chown","mkdir","rmdir","rename","symlink","link","truncate",
            "utime","fchmod","fchown","ftruncate","write","writev","pwrite","fdopen")if hasattr(os,name)}
        names=self.capsule_module.FILES|self.capsule_module.AD_FILES|self.capsule_module.ACCOUNT_FILES
        parents={str(self.original_path(name).parent)for name in names}
        def translated(path):
            return self.mapped_path(path)if isinstance(path,(str,bytes,os.PathLike))and os.fsdecode(path)in names|parents else path
        def checked(path,dir_fd=None,root_ok=False):
            value=translated(path);guard.path(value,dir_fd,root_ok);return value
        def open_file(function,path,mode="r",*args,**kwargs):
            value=translated(path)
            if is_write_mode(mode):guard.path(value)
            opener=kwargs.get("opener")
            if opener is not None:
                known=getattr(self.original_path,"_opener",None)
                owner=getattr(opener,"__self__",None)
                if (known is None or getattr(opener,"__func__",None)is not known
                        or not isinstance(owner,self.original_path)
                        or os.fsdecode(translated(owner))!=os.fsdecode(value)):
                    denied("CASE_CUSTOM_OPENER")
                # pathlib's captured OS accessor must never bypass remapping.
                kwargs.pop("opener")
            return function(value,mode,*args,**kwargs)
        def open_fd(path,flags,*args,**kwargs):
            value=translated(path)
            if is_write_flags(flags):guard.path(value,kwargs.get("dir_fd"))
            return original_open(value,flags,*args,**kwargs)
        def temporary(*args,**kwargs):
            values=list(args);directory=kwargs.get("dir",values[2]if len(values)>2 else None)
            if directory is None:denied("CASE_EXPLICIT_TEMP_DIRECTORY_REQUIRED")
            directory=checked(directory)
            if len(values)>2:values[2]=directory
            else:kwargs["dir"]=directory
            return original_mkstemp(*values,**kwargs)
        def symlink(target,destination,*args,**kwargs):
            target=translated(target);destination=checked(destination,kwargs.get("dir_fd"))
            guard.symlink(target,destination,kwargs.get("dir_fd"))
            return originals["symlink"](target,destination,*args,**kwargs)
        publication_pairs={}
        original_lstat=os.lstat
        def operand(path,dir_fd=None):
            value=os.fsdecode(translated(path))
            if not os.path.isabs(value):
                value=os.path.join(guard.fd(dir_fd,directory=True)if dir_fd is not None else os.getcwd(),value)
            return os.path.abspath(value)
        def publication_inode(path,key,count):
            info=original_lstat(path)
            if (not stat.S_ISREG(info.st_mode)or info.st_uid!=os.geteuid()
                    or (info.st_dev,info.st_ino)!=key or info.st_nlink!=count):
                denied("CASE_PUBLICATION_CHANGED")
        def hardlink(source,destination,*,src_dir_fd=None,dst_dir_fd=None,follow_symlinks=True):
            source=operand(source,src_dir_fd);destination=operand(destination,dst_dir_fd)
            actual=guard.path(source);final=guard.path(destination)
            info=original_lstat(source)
            if not stat.S_ISREG(info.st_mode)or info.st_uid!=os.geteuid()or info.st_nlink!=1:
                denied("CASE_HARDLINK_SOURCE")
            key=(info.st_dev,info.st_ino)
            originals["link"](source,destination,follow_symlinks=False)
            publication_inode(actual,key,2);publication_inode(final,key,2)
            publication_pairs[actual]=(key,final)
        def unlink(path,*,dir_fd=None):
            named=operand(path,dir_fd);actual=original_realpath(named)
            pair=publication_pairs.get(actual)
            if pair is None:return original_unlink(checked(path,dir_fd),dir_fd=dir_fd)
            key,final=pair
            guard.path(self.original_path(actual).parent,root_ok=True)
            guard.path(self.original_path(final).parent,root_ok=True)
            publication_inode(named,key,2)  # Raw leaf lstat never follows a symlink.
            publication_inode(actual,key,2);publication_inode(final,key,2)
            original_unlink(actual)
            publication_inode(final,key,1);del publication_pairs[actual]
        def descriptor(name,fd,*args,**kwargs):
            guard.fd(fd,stdio=name in("write","writev"))
            return originals[name](fd,*args,**kwargs)
        def fdopen(fd,mode="r",*args,**kwargs):
            if is_write_mode(mode):guard.fd(fd,stdio=True)
            return originals["fdopen"](fd,mode,*args,**kwargs)
        def mapped_exists(path):return original_exists(translated(path))
        def mapped_lexists(path):return original_lexists(translated(path))
        def mapped_realpath(path,*args,**kwargs):
            if isinstance(path,(str,bytes,os.PathLike))and os.fsdecode(path)in parents:return os.fsdecode(path)
            actual=original_realpath(path,*args,**kwargs)
            for name in("/usr/sbin/smbd","/usr/bin/ganesha.nfsd"):
                if actual==str(self.osroot/name.lstrip("/")):return name
            return actual
        def mapped_readlink(path,*args,**kwargs):
            value=original_readlink(path,*args,**kwargs)
            for name in("/usr/sbin/smbd","/usr/bin/ganesha.nfsd"):
                if value==str(self.osroot/name.lstrip("/")):return name
            return value
        def source_constructor(instance,*args,**kwargs):instance.__dict__.update(self.local.__dict__.copy())
        with contextlib.ExitStack()as more:
            bindings=[
                (self.identity_module,"Path",self.mapped_path),
                (self.capsule_module,"Path",self.mapped_path),
                (self.capsule_module,"regular_file",self.local_reader),
                (self.source_module,"Path",self.mapped_path),
                (self.source_module,"LOCAL_SOURCE_SMB_CONFIG",self.osroot/"etc/samba/smb.conf"),
                (self.source_module,"LOCAL_SOURCE_NVME_ROOT",self.driver.runtime.nvme_root),
                (self.source_module.SmbSourceCheckpoint,"__init__",source_constructor),
                (self.service_module,"Path",self.service_path),
                (os.path,"exists",mapped_exists),(os.path,"lexists",mapped_lexists),
                (os.path,"realpath",mapped_realpath),(os,"readlink",mapped_readlink),
                (os,"open",open_fd),
                (os,"unlink",unlink),(os,"remove",unlink),
                (os,"replace",lambda old,new,*args,**kwargs:original_replace(
                    checked(old,kwargs.get("src_dir_fd")),checked(new,kwargs.get("dst_dir_fd")),*args,**kwargs)),
                (os,"makedirs",lambda path,*args,**kwargs:original_makedirs(
                    checked(path,root_ok=True),*args,**kwargs)),
                (tempfile,"mkstemp",temporary),
                (builtins,"open",lambda path,mode="r",*args,**kwargs:open_file(
                    original_builtin_open,path,mode,*args,**kwargs)),
                (io,"open",lambda path,mode="r",*args,**kwargs:open_file(
                    original_io_open,path,mode,*args,**kwargs))]
            for module,name,value in bindings:more.enter_context(patch.object(module,name,value))
            for name in("chmod","chown","mkdir","rmdir","truncate","utime"):
                def call(path,*args,_name=name,**kwargs):
                    return originals[_name](checked(path,kwargs.get("dir_fd"),root_ok=_name=="mkdir"),*args,**kwargs)
                more.enter_context(patch.object(os,name,call))
            more.enter_context(patch.object(os,"rename",lambda old,new,*args,**kwargs:originals["rename"](
                checked(old,kwargs.get("src_dir_fd")),checked(new,kwargs.get("dst_dir_fd")),*args,**kwargs)))
            more.enter_context(patch.object(os,"symlink",symlink))
            more.enter_context(patch.object(os,"link",hardlink))
            for name in("fchmod","fchown","ftruncate","write","writev","pwrite"):
                if name in originals:
                    more.enter_context(patch.object(os,name,lambda fd,*args,_name=name,**kwargs:descriptor(_name,fd,*args,**kwargs)))
            more.enter_context(patch.object(os,"fdopen",fdopen))
            # Python 3.9 pathlib captures OS functions in a singleton accessor.
            # Reuse the same checked callables; ExitStack restores every original.
            accessor=getattr(self.original_path("."),"_accessor",None)
            if accessor is not None:
                for name in("open","chmod","mkdir","unlink","rmdir","rename","replace","symlink","utime"):
                    if hasattr(accessor,name):more.enter_context(patch.object(accessor,name,getattr(os,name)))
                if hasattr(accessor,"link_to"):more.enter_context(patch.object(accessor,"link_to",hardlink))
            yield
    def service_path(self,value="."):
        fixture=self
        class View:
            def __init__(self,path):self.path=path
            def __truediv__(self,name):return View(self.path/name)
            def __fspath__(self):return str(self.path)
            def __str__(self):return str(self.path)
            def __getattr__(self,name):return getattr(self.path,name)
            def read_bytes(self):
                raw=self.path.read_bytes()
                if self.path.name=="cmdline"and self.path.parent.parent==fixture.osroot/"proc":
                    for row in fixture.os_state()["domains"].get("NFS",{}).get("endpoints",[]):
                        if self.path.parent.name==str(row["pid"]):
                            raw=raw.replace(str(fixture.driver.nfs_config_root/row["file"]).encode(),
                                ("/etc/ganesha/ablestack-storage/"+row["file"]).encode())
                return raw
        return View(self.mapped_path(value))
    def service_os_run(self,arguments,timeout=15,pass_fds=()):
        args=list(arguments);state=self.os_state()
        owners=[]
        for domain,prefix in(("NFS","ablestack-storage-ganesha@"),("SMB","ablestack-storage-smb@")):
            for row in state["domains"].get(domain,{}).get("endpoints",[]):
                unit=row.get("unit")or(prefix+row["file"].removesuffix(".conf")+".service")
                owners.append((domain,unit,row))
        if args[:2]==["systemctl","list-units"]:
            require(args[2:7]==["--state=active","--type=service","--no-legend","--plain",
                "ablestack-storage-ganesha@*.service"],"SERVICE_OS_UNIT_QUERY_SCHEMA")
            return "".join(unit+" loaded active running Fixture\n"for _,unit,_ in owners)
        if args[:2]==["systemctl","show"]and args[-2:]==["--property=MainPID,ActiveState,SubState","--no-pager"]:
            row=next((row for _,unit,row in owners if unit==args[2]),None)
            return "MainPID="+str(row["pid"]if row else 0)+"\nActiveState="+("active"if row else"inactive")+"\nSubState="+("running"if row else"dead")+"\n"
        if args[:2]==["systemctl","stop"]and len(args)==3:
            matches=[(domain,unit,row)for domain,unit,row in owners if unit==args[2]]
            require(len(matches)==1,"SERVICE_OS_STOP_UNKNOWN_UNIT")
            domain,unit,row=matches[0];proc=self.osroot/"proc"/str(row["pid"])
            require(proc.parent==self.osroot/"proc"and(proc/"cgroup").read_text().strip()=="0::/system.slice/"+unit,
                "SERVICE_OS_STOP_CHANGED_PROCESS")
            state["domains"][domain]["endpoints"]=[x for x in state["domains"][domain]["endpoints"]if x!=row]
            shutil.rmtree(proc);self.write_state(state)
            self.effects.append({"kind":"STATEFUL_OS_SERVICE_STOP","unit":unit,"pid":row["pid"],"privateFilesTouched":False})
            return ""
        if args in (["ss","-H","-ltnp"],["ss","-H","-lunp"]):
            if args[-1]=="-lunp":return ""
            return "".join("LISTEN 0 50 "+row["listenIp"]+":"+str(row["port"])+" 0.0.0.0:* users:((\"fixture\",pid="+str(row["pid"])+",fd=50))\n"
                for _,_,row in owners)
        raise ValueError("SERVICE_OS_COMMAND_NOT_IMPLEMENTED")
    def root_data(self,request):
        spec=json.loads((self.case/"witness-os-fixture.json").read_text())
        require(spec["bootId"]==self.generation.status()["bootId"],"ROOT_DATA_OS_BOOT_CHANGED")
        data={item["volumeUuid"]:(index,item) for index,item in enumerate(spec["dataVolumes"])}
        result=[]
        for wanted in request["volumes"]:
            require(wanted["volumeUuid"]in data,"ROOT_DATA_OS_FOREIGN_VOLUME")
            index,actual=data[wanted["volumeUuid"]]
            require(wanted["sizeBytes"]==actual["sizeBytes"],"ROOT_DATA_OS_SIZE_CHANGED")
            result.append({"volumeUuid":wanted["volumeUuid"],"sizeBytes":actual["sizeBytes"],
                "kind":wanted["kind"],"mappingStatus":"EXACT","matchedBy":"VOLUME_SERIAL",
                "serial":wanted["volumeUuid"].replace("-","")[:20],"observedDevicePath":"/dev/sd"+chr(ord("b")+index),
                "filesystem":actual["filesystem"],"filesystemUuid":actual["filesystemUuid"],
                "mounts":actual["mounts"],"signatureObservationAvailable":True,
                "blank":actual["filesystem"]is None})
        return {"success":True,"bootId":spec["bootId"],"generatedEpoch":time.time(),"volumes":result}
    def network_inspect(self,payload):
        require(type(payload)is dict and type(payload.get("expectedBindings"))is list
                and type(payload.get("endpoints"))is list,"RENDERED_OS_NETWORK_SCHEMA")
        observed=[]
        for binding in payload["expectedBindings"]:
            require(binding=={"listenIp":"10.10.13.226","macAddress":"02:00:00:00:00:80",
                              "primaryIp":"10.10.13.226","prefixlen":16},"RENDERED_OS_FOREIGN_NIC_BINDING")
            observed.append({**binding,"active":True,"interface":"eth0"})
        for endpoint in payload["endpoints"]:
            require(endpoint["listenIp"]=="10.10.13.226"and endpoint["primaryIp"]=="10.10.13.226"
                    and endpoint["prefixlen"]==16 and endpoint["macAddress"]=="02:00:00:00:00:80",
                    "RENDERED_OS_FOREIGN_ENDPOINT")
        return {"success":True,"sideEffects":False,"bindingsValidated":True,
                "endpoints":observed,"expectedBindings":payload["expectedBindings"],"fixtureOnly":True}
    def save_os_ledger(self):
        path=self.case/"rendered-os-fixture-events.jsonl"
        with path.open("a")as out:
            for row in self.effects:out.write(json.dumps(row,sort_keys=True)+"\n")
        os.chmod(path,0o600)
        self.effects.clear()
    def os_state(self):
        return json.loads(self.os_state_path.read_text()) if self.os_state_path.exists() else {"domains":{},"connections":[]}
    def write_state(self,state):
        self.os_state_path.write_text(json.dumps(state,sort_keys=True)+"\n");self.os_state_path.chmod(0o600)
    def health(self):
        state=self.os_state();domains=state["domains"]
        nfs=domains.get("NFS",{});smb=domains.get("SMB",{})
        endpoints=[]
        for row in nfs.get("endpoints",[]):
            process=self.osroot/"proc"/str(row["pid"])
            endpoints.append({"file":row["file"],"pid":row["pid"],"processRunning":process.is_dir()})
        smb_endpoints=[]
        for row in smb.get("endpoints",[]):
            actual=self.osroot/"etc/samba/smb.conf"
            valid=actual.is_file()and hashlib.sha256(actual.read_bytes()).hexdigest()==smb["configurationSha256"]
            smb_endpoints.append({"listenIp":row["listenIp"],"port":row["port"],"listenerOwned":valid,"tcpReady":valid})
        return {"success":True,"status":"ok","nfsGanesha":{"active":len(endpoints),
            "listening":bool(endpoints),"endpoints":endpoints},"smbRuntime":{"runtimeEndpoints":smb_endpoints},
            "listenPorts":{"nfs":[r["port"]for r in nfs.get("endpoints",[])]}}
    def tcp_fixture(self,address,timeout=None,*args,**kwargs):
        host,port=address
        known={"10.10.13.226","127.0.0.1","::1"}
        matches=[row for domain in self.os_state()["domains"].values()for row in domain.get("endpoints",[])
                 if row["port"]==int(port)and (row["listenIp"]==host or row["listenIp"]in("0.0.0.0","::")and host in known)]
        require(matches,"OS_TCP_ENDPOINT_NOT_LISTENING")
        class Connection:
            def __enter__(self):return self
            def __exit__(self,*args):return False
        return Connection()
    def dbus_adapter(self,deadline=None):
        actual=self.ganesha_module.GaneshaDbus;state=self.os_state()
        endpoints=state["domains"].get("NFS",{}).get("endpoints",[])
        class Manager:
            def __init__(self,endpoint):self.endpoint=endpoint
            def get_dbus_method(self,name,interface):
                exports=self.endpoint["exports"]
                if name=="ShowExports":
                    return lambda **kw:[0,[[row["exportId"],row["pseudo"]if row.get("mountPathPseudo")else row["path"]]for row in exports]]
                if name=="DisplayExport":
                    def display(identifier,**kw):
                        rows=[r for r in exports if r["exportId"]==int(identifier)]
                        require(len(rows)==1,"OS_DBUS_UNKNOWN_EXPORT")
                        row=rows[0]
                        clients=[[c["client"],0,0,0,0,c["anonUid"]if c["anonUid"]is not None else 65534,
                                  c["anonGid"]if c["anonGid"]is not None else 65534,0,c["options"],0x1ff]
                                 for c in row.get("clients",[])]
                        return [row["exportId"],row["path"],row["pseudo"]if row.get("mountPathPseudo")else row["path"],"",clients]
                    return display
                raise ValueError("OS_DBUS_METHOD_NOT_IMPLEMENTED")
        class Bus:
            def get_object(self,name,path):
                if name=="org.freedesktop.DBus":
                    class Owner:
                        def get_dbus_method(self,method,interface):
                            require(method=="GetConnectionUnixProcessID","OS_DBUS_OWNER_METHOD")
                            def pid(bus_name,**kw):
                                rows=[r for r in endpoints if actual.name(r["listenIp"],r["port"])==bus_name]
                                require(len(rows)==1,"OS_DBUS_OWNER_UNKNOWN")
                                return rows[0]["pid"]
                            return pid
                    return Owner()
                rows=[r for r in endpoints if actual.name(r["listenIp"],r["port"])==name]
                require(len(rows)==1,"OS_DBUS_MANAGER_UNKNOWN")
                return Manager(rows[0])
        return actual(bus=Bus(),deadline=deadline)
    def native_writer_idle(self):
        source=self.cli.read_text();begin="<<'PYWRITERSTATUS'\n";end="\nPYWRITERSTATUS\n"
        require(source.count(begin)==1 and source.count(end)==1,"EXACT_REAL_WRITER_IDLE_PRODUCER")
        body=source.split(begin,1)[1].split(end,1)[0]
        out=io.StringIO()
        with contextlib.redirect_stdout(out):
            exec(compile(body,"actual_cli_writer_idle_producer","exec"),{"__name__":"actual_writer_idle_branch"})
        return json.loads(out.getvalue())
    def verified_health(self):
        path=self.store.pointer()
        if path is not None:
            domains=self.driver.verify(path)
            require(set(domains)=={"NFS","SMB","ISCSI","NVMEOF"}and all(v is True for v in domains.values()),
                    "OS_STATE_FAILED_REAL_RUNTIME_VERIFY")
        else:
            require(not self.os_state()["domains"],"OS_RUNTIME_WITHOUT_RENDERED_POINTER")
            domains={}
        return {**self.health(),"bootId":self.generation.status()["bootId"],
                "generatedEpoch":time.time(),"realRenderedProtocolVerification":domains}
    def inventory(self):
        health=self.verified_health();state=self.os_state()
        spec=json.loads((self.case/"witness-os-fixture.json").read_text())
        volumes=self.root_data({"volumes":[{"volumeUuid":x["volumeUuid"],"sizeBytes":x["sizeBytes"],"kind":"FILE_DATA"}
                                          for x in spec["dataVolumes"]if x["filesystem"]is not None]})["volumes"]
        result={**health,"fileShareVolumes":volumes,"posixDirectoryPolicies":{},
                "nfsGaneshaExports":[],"smbAccess":{},"smbShares":[],"iscsiTargets":{"targets":[]},
                "nvmeofSubsystems":{"subsystems":[]},"nvmeofRuntime":{"listening":False}}
        for endpoint in state["domains"].get("NFS",{}).get("endpoints",[]):
            content=(self.driver.nfs_config_root/endpoint["file"]).read_text()
            actual=self.ganesha_module.GaneshaDbus.configuration(content)
            entries=[]
            for row in actual["exports"]:
                clients=[]
                for client in row["clients"]:
                    bits=client["options"];access={0x1e0:"RW",0xa0:"RO"}[bits&0x1e0]
                    squash={0:"No_Root_Squash",2:"Root_Squash",4:"All_Squash"}[bits&7]
                    clients.append({"clients":client["client"],"access":access,"squash":squash,
                                    "anonUid":client["anonUid"],"anonGid":client["anonGid"]})
                entries.append({"exportId":row["exportId"],"path":row["path"],"pseudo":row["pseudo"],"clients":clients})
            result["nfsGaneshaExports"].append({"file":endpoint["file"],"status":"active",
                "listening":True,"listenIp":endpoint["listenIp"],"port":endpoint["port"],"entries":entries})
        current=self.store.pointer()
        if current is None:return result
        smb=json.loads((current/"smb/manifest.json").read_text())
        parsed=configparser.ConfigParser(interpolation=None,strict=True)
        parsed.read_string((self.osroot/"etc/samba/smb.conf").read_text())
        for share in smb["shares"]:
            section=parsed[share["shareName"]]
            creation={key:section[field]for key,field in (("createMask","create mask"),("forceCreateMode","force create mode"),
                      ("directoryMask","directory mask"),("forceDirectoryMode","force directory mode"))}
            creation["inheritPermissions"]=section.get("inherit permissions","no").lower()=="yes"
            require(creation==share["creationPolicy"],"OS_SMB_CREATION_POLICY_DRIFT")
            sources=set(section.get("hosts allow","").split())
            expected=set(share["networkPolicy"]["allowedSources"])
            require(sources==expected|{"127.0.0.1","::1"}if expected else not sources,"OS_SMB_NETWORK_POLICY_DRIFT")
            forced=share.get("forcedIdentity")
            result["smbAccess"][share["uuid"]]={
                "ownershipInheritance":"INHERIT_PARENT_OWNER"if section.get("inherit owner","no").lower()=="yes"else"AUTHENTICATED_USER",
                "posixOwnershipMode":"FORCED_UID_GID"if forced else"AUTHENTICATED_USER",
                "creationPolicy":creation,"allowedSources":sorted(expected),
                **({"managedUser":section.get("force user"),"managedGroup":section.get("force group")}if forced else{})}
            result["smbShares"].append({"name":share["shareName"],
                **{key:section.get(field,"")for key,field in (("valid_users","valid users"),("write_list","write list"),
                ("admin_users","admin users"),("read_only","read only"),("guest_ok","guest ok"))}})
        all_data=self.root_data({"volumes":[{"volumeUuid":x["volumeUuid"],"sizeBytes":x["sizeBytes"],"kind":"BLOCK_RAW"}
                                            for x in spec["dataVolumes"]]})["volumes"]
        devices={row["observedDevicePath"]:row for row in all_data}
        for target in sorted(self.driver.runtime.iscsi_root.iterdir()):
            require(target.is_dir(),"OS_ISCSI_NAMESPACE_ENTRY")
            root=target/"tpgt_1";acls=sorted(x.name for x in(root/"acls").iterdir())
            for lun in sorted((root/"lun").iterdir()):
                links=list(lun.iterdir());require(len(links)==1 and links[0].is_symlink(),"OS_ISCSI_LUN_LINK")
                device=(links[0].resolve()/"udev_path").read_text().strip()
                require(device in devices and not devices[device]["mounts"]and devices[device]["filesystem"]is None,"OS_ISCSI_RAW_BINDING")
                row=devices[device]
                result["iscsiTargets"]["targets"].append({"targetName":target.name,"lunOrNamespace":lun.name.removeprefix("lun_"),
                    "volumeUuid":row["volumeUuid"],"effectiveSizeBytes":row["sizeBytes"],
                    "runtime":{"acls":acls,"configfsPresent":True,"backingPath":device}})
        for subsystem in sorted((self.driver.runtime.nvme_root/"subsystems").iterdir()):
            require(subsystem.is_dir(),"OS_NVME_NAMESPACE_ENTRY")
            allowed=sorted(p.name for p in(subsystem/"allowed_hosts").iterdir())
            namespaces=[]
            for ns in sorted((subsystem/"namespaces").iterdir()):
                device=(ns/"device_path").read_text().strip()
                require(device in devices and not devices[device]["mounts"]and devices[device]["filesystem"]is None,"OS_NVME_RAW_BINDING")
                row=devices[device]
                namespaces.append({"namespaceId":ns.name,"configfsPresent":ns.is_dir(),"enabled":(ns/"enable").read_text().strip()=="1",
                    "mappingStatus":row["mappingStatus"],"runtimeVolumeUuid":row["volumeUuid"],
                    "actualSizeBytes":row["sizeBytes"],"backingPath":device})
            auth={}
            for name in allowed:
                host=self.driver.runtime.nvme_root/"hosts"/name
                require((subsystem/"allowed_hosts"/name).resolve()==host.resolve(),"OS_NVME_HOST_LINK")
                auth[name]={"dhChapConfigured":(host/"dhchap_key").exists()and bool((host/"dhchap_key").read_text().strip()),
                            "dhChapCtrlConfigured":(host/"dhchap_ctrl_key").exists()and bool((host/"dhchap_ctrl_key").read_text().strip())}
            result["nvmeofSubsystems"]["subsystems"].append({"targetName":subsystem.name,"runtime":{
                "configfsPresent":subsystem.is_dir(),"allowAnyHost":(subsystem/"attr_allow_any_host").read_text().strip()=="1",
                "allowedHosts":allowed,"hostAuthentication":auth,"namespaces":namespaces}})
        endpoints=state["domains"].get("NVMEOF",{}).get("endpoints",[])
        result["nvmeofRuntime"]["listening"]=bool(endpoints)and all(self.tcp_fixture((x["listenIp"]if x["listenIp"]!="0.0.0.0"else"127.0.0.1",x["port"]))for x in endpoints)
        return result
    def empty_source_nvme_products(self,payload):
        # Only this fixture's verified-empty SOURCE is modeled. Both public
        # identity capture and cleanup ownership remain the real Native code.
        activation=self.store.read_journal()
        source=self.store.generations/activation["previousOperationUuid"]
        self.store.inspect(source)
        require(json.loads((source/"desired-state.json").read_text())["nvmeof-subsystems.json"]is None,
                "OS_ONLY_EMPTY_NVME_SOURCE_SUPPORTED")
        def probe(binding,device):
            row=self.root_data({"volumes":[{"volumeUuid":binding["volumeUuid"],
                "sizeBytes":binding["sizeBytes"],"kind":"RAW_DATA"}]})["volumes"][0]
            require(row["observedDevicePath"]==device and row["filesystem"]is None
                    and row["filesystemUuid"]is None and row["mounts"]==[],
                    "OS_NVME_EXACT_RAW_VOLUME_REQUIRED")
            return {"devicePath":row["observedDevicePath"],"serial":row["serial"],"sizeBytes":row["sizeBytes"]}
        def ownership(cleanup,value):
            return self.nvme_identity_module.NvmeRuntimeIdentity.cleanup_authority(cleanup,value,
                generation_root=self.generation.root,probe=probe,boot=lambda:self.generation.status()["bootId"])
        cleanup=self.nvme_cleanup_module.NvmeManagedCleanup(self.driver.runtime.nvme_root,
            self.generation.config/"nvmeof-subsystems.json",payload,lambda:self.command(("sessions",)),
            rmdir=self.configfs_rmdir,ownership=ownership)
        identity=self.nvme_identity_module.NvmeRuntimeIdentity(cleanup,payload,
            generation_root=self.generation.root,probe=probe,boot=lambda:self.generation.status()["bootId"])
        return cleanup,identity

    def configfs_rmdir(self,path):
        # Real configfs removes kernel-owned fixed attributes when an object is
        # rmdir'ed. A regular-directory OS fixture must model that one effect.
        root=self.driver.runtime.nvme_root;path=self.original_path(path)
        self.path_guard.path(path);require(not path.is_symlink(),"OS_CONFIGFS_RMDIR_ALIAS")
        relative=path.relative_to(root).parts
        files=set();directories=set()
        if len(relative)==4 and relative[0]=="subsystems"and relative[2]=="namespaces":
            require(relative[3].isdigit(),"OS_CONFIGFS_NAMESPACE_ID")
            files={"enable","device_path","device_uuid","device_nguid","device_eui64"}
            require((path/"enable").read_text().strip()=="0","OS_CONFIGFS_NAMESPACE_STILL_ENABLED")
        elif len(relative)==2 and relative[0]=="subsystems":
            files={"attr_allow_any_host","attr_serial","attr_model"};directories={"allowed_hosts","namespaces"}
        elif len(relative)==2 and relative[0]=="ports":
            require(relative[1].isdigit()and 1<=int(relative[1])<=4095,"OS_CONFIGFS_PORT_ID")
            files={"addr_trtype","addr_adrfam","addr_traddr","addr_trsvcid"};directories={"subsystems"}
        elif len(relative)==2 and relative[0]=="hosts":
            files={"dhchap_key","dhchap_ctrl_key"}
        else:raise ValueError("OS_CONFIGFS_RMDIR_OUTSIDE_FIXED_OBJECTS")
        require({p.name for p in path.iterdir()}<=files|directories,"OS_CONFIGFS_UNKNOWN_ATTRIBUTE")
        for child in path.iterdir():
            require(not child.is_symlink(),"OS_CONFIGFS_ATTRIBUTE_ALIAS")
            if child.name in directories:
                require(child.is_dir()and not any(child.iterdir()),"OS_CONFIGFS_REFERENCE_NOT_EMPTY")
            else:
                info=child.lstat()
                require(stat.S_ISREG(info.st_mode)and info.st_uid==os.geteuid()
                        and info.st_nlink==1 and not info.st_mode&0o022,"OS_CONFIGFS_ATTRIBUTE_NOT_OWNED")
        for child in list(path.iterdir()):
            child.rmdir()if child.name in directories else child.unlink()
        path.rmdir()

    def cleanup_missing_replay(self,payload):
        # replay_auth's normal disabled-empty CLI request has no rendered kwargs.
        # Its real LOCAL producer already authenticated the capsule and calls the
        # real NVMe adapter. Never mint the missing pending identity receipt here.
        activation=self.store.read_journal();scope=activation["scope"]
        source=self.store.generations/activation["previousOperationUuid"]
        require(activation["phase"]=="ROLLING_BACK"and self.store.pointer()==source
                and payload=={"instanceUuid":scope["instanceUuid"],"enabled":False,"subsystems":[]},
                "OS_DIRECT_NVME_CLEANUP_SCOPE")
        require("nvmeRuntimeIdentity"in json.loads(self.generation.pending.read_text()),
                "OS_DIRECT_NVME_CLEANUP_HAS_NO_ORIGINAL_RECEIPT")
        cleanup,identity=self.empty_source_nvme_products(payload)
        cleanup.finish({})
        require(all(not any((self.driver.runtime.nvme_root/name).iterdir())
                    for name in("subsystems","ports","hosts")),"OS_NVME_SOURCE_NOT_EMPTY_AFTER_REAL_CLEANUP")
        self.driver.persist_one("nvmeof-subsystems.json",payload)
        state=self.os_state();state["domains"]["NVMEOF"]={"manifestSha256":activation["previousSha256"],"endpoints":[]}
        self.write_state(state)
        self.effects.append({"kind":"REAL_NVME_IDENTITY_CLEANUP_FINISH","sourceBindingsEmpty":True,
                             "pendingReceiptSeeded":False})
        return {"success":True,"fixtureServiceEffect":True,"runtimeGenerationWritten":False}

    def cleanup_empty_rendered_source(self,domain,payload,replay,replay_from):
        activation=self.store.read_journal();target=self.store.inspect(replay_from)
        require(activation["phase"]=="ROLLING_BACK"
                and target["manifestSha256"]==activation["targetSha256"]
                and json.loads((replay/"desired-state.json").read_text())[
                    self.runtime_module.PROTOCOL_FILES[domain]]is None,"OS_EMPTY_SOURCE_CLEANUP_SCOPE")
        if domain=="SMB":raise ValueError("OS_EMPTY_SMB_REPLAY_MUST_USE_VERIFIED_LOCAL_SOURCE")
        if domain=="NVMEOF":
            cleanup,identity=self.empty_source_nvme_products(payload);cleanup.finish({})
            require(all(not any((self.driver.runtime.nvme_root/name).iterdir())
                for name in("subsystems","ports","hosts")),"OS_NVME_EMPTY_REPLAY_RETAINS_OBJECTS")
            self.driver.persist_one("nvmeof-subsystems.json",payload)
        elif domain=="ISCSI":
            plan=json.loads((replay_from/"block/iscsi-plan.json").read_text())
            resolved=self.driver.runtime.reobserve_block(replay_from,plan)
            self.driver.runtime.verify_block(plan,instance_uuid=target["scope"]["instanceUuid"],resolved=resolved)
            root=self.driver.runtime.iscsi_root;core=root.parent/"core/iblock_0"
            require({p.name for p in root.iterdir()}=={t["targetName"]for t in plan["targets"]},
                    "OS_FOREIGN_ISCSI_TARGET_DURING_EMPTY_REPLAY")
            owned={"ablestack-"+t["uuid"]for t in plan["targets"]}
            require({p.name for p in core.iterdir()}==owned,"OS_FOREIGN_ISCSI_BACKSTORE_DURING_EMPTY_REPLAY")
            for row in plan["targets"]:
                target_root=root/row["targetName"]
                expected={"tpgt_1","tpgt_1/acls","tpgt_1/np","tpgt_1/lun","tpgt_1/attrib","tpgt_1/attrib/authentication"}
                for endpoint in row["listeners"]:expected.add("tpgt_1/np/"+endpoint["listenIp"]+":"+str(endpoint["port"]))
                for acl in row["acls"]:
                    prefix="tpgt_1/acls/"+acl["principal"];expected.update((prefix,prefix+"/auth"))
                    expected.update(prefix+"/auth/"+field for field in("userid","userid_mutual","password","password_mutual"))
                for backing in row["backstores"]:
                    prefix="tpgt_1/lun/lun_"+str(backing["number"]);expected.update((prefix,prefix+"/backstore"))
                require({str(x.relative_to(target_root))for x in target_root.rglob("*")}==expected,
                        "OS_ISCSI_UNKNOWN_OWNED_TARGET_CHILD")
                for backing in row["backstores"]:
                    link=root/row["targetName"]/"tpgt_1/lun"/("lun_"+str(backing["number"]))/"backstore"
                    require(link.is_symlink()and link.resolve()==core/("ablestack-"+row["uuid"]),
                            "OS_ISCSI_EMPTY_CLEANUP_BACKSTORE_NOT_OWNED")
                shutil.rmtree(root/row["targetName"])
                storage=core/("ablestack-"+row["uuid"])
                require({p.name for p in storage.iterdir()}=={"udev_path"}and not(storage/"udev_path").is_symlink(),
                        "OS_ISCSI_UNKNOWN_BACKSTORE_ATTRIBUTE")
                (storage/"udev_path").unlink();storage.rmdir()
            self.driver.persist_one("iscsi-targets.json",payload)
        elif domain=="NFS":
            self.driver.runtime.verify(replay_from,("NFS",))
            endpoints=json.loads((replay_from/"nfs/manifest.json").read_text())["endpoints"]
            require({p.name for p in self.driver.nfs_config_root.iterdir()}==
                    {row["legacyUnitKey"]+".conf"for row in endpoints},"OS_FOREIGN_NFS_EMPTY_REPLAY_CONFIG")
            for row in endpoints:
                self.service_os_run(["systemctl","stop","ablestack-storage-ganesha@"+row["legacyUnitKey"]+".service"])
                (self.driver.nfs_config_root/(row["legacyUnitKey"]+".conf")).unlink()
        else:raise ValueError("OS_UNKNOWN_EMPTY_SOURCE_DOMAIN")
        state=self.os_state();state["domains"][domain]={"manifestSha256":activation["previousSha256"],"endpoints":[]}
        self.write_state(state)
        self.effects.append({"kind":"STATEFUL_EMPTY_SOURCE_OS_REPLAY","domain":domain,
                             "manifestSha256":activation["previousSha256"],"nativeGenerationPromoted":False})
        return {"success":True,"fixtureServiceEffect":True,"runtimeGenerationWritten":False}

    def kernel_apply(self,domain,payload,replay,replay_from):
        require(replay is not None and replay_from is not None,"OS_REPLAY_IMMUTABLE_PATHS_REQUIRED")
        replay=Path(replay);replay_from=Path(replay_from)
        manifest=self.store.inspect(replay);from_manifest=self.store.inspect(replay_from)
        journal=self.store.read_journal()
        require(journal and journal["phase"]in("ACTIVATING","ROLLING_BACK"),
                "OS_REPLAY_NOT_OWNED_ACTIVATION")
        rollback=journal["phase"]=="ROLLING_BACK"
        target,source=(from_manifest,manifest)if rollback else(manifest,from_manifest)
        target_path,source_path=(replay_from,replay)if rollback else(replay,replay_from)
        require(target["scope"]==journal["scope"]
                and target["manifestSha256"]==journal["targetSha256"]
                and source["manifestSha256"]==journal["previousSha256"]
                and source["scope"]["operationUuid"]==journal["previousOperationUuid"]
                and source["scope"]["instanceUuid"]==target["scope"]["instanceUuid"]
                and type(source["scope"]["revision"])is int and type(target["scope"]["revision"])is int
                and source["scope"]["revision"]<target["scope"]["revision"]
                and target["previousRenderedSha256"]==source["manifestSha256"]
                and target_path==self.store.generations/target["scope"]["operationUuid"]
                and source_path==self.store.generations/source["scope"]["operationUuid"]
                and self.store.pointer()==replay,"OS_REPLAY_NOT_OWNED_ACTIVATION")
        expected=self.driver.runtime.payload(replay,domain,rollback)
        require(payload==expected,"OS_REPLAY_REQUEST_DIFFERS_FROM_REAL_IMMUTABLE_PAYLOAD")
        if rollback and json.loads((replay/"desired-state.json").read_text())[
                self.runtime_module.PROTOCOL_FILES[domain]]is None:
            return self.cleanup_empty_rendered_source(domain,payload,replay,replay_from)
        state=self.os_state();record={"manifestSha256":manifest["manifestSha256"],"endpoints":[]}
        if domain=="NVMEOF":cleanup,identity=self.empty_source_nvme_products(payload)
        if domain=="NFS":
            data=json.loads((replay/"nfs/manifest.json").read_text())
            old=state["domains"].get(domain,{})
            owned={r["file"]for r in old.get("endpoints",[])}
            require({p.name for p in self.driver.nfs_config_root.iterdir()}<=owned,"OS_FOREIGN_NFS_CONFIG")
            for name in owned:(self.driver.nfs_config_root/name).unlink(missing_ok=True)
            for index,endpoint in enumerate(data["endpoints"]):
                source=replay/endpoint["configurationPath"]
                name=endpoint["legacyUnitKey"]+".conf"
                target=self.driver.nfs_config_root/name;target.write_bytes(source.read_bytes());target.chmod(0o600)
                policy=self.ganesha_module.GaneshaDbus.configuration(target.read_text())
                pid=900001+index;proc=self.osroot/"proc"/str(pid);proc.mkdir(exist_ok=True,mode=0o700)
                (proc/"comm").write_text("ganesha.nfsd\n")
                (proc/"stat").write_text(str(pid)+" (ganesha.nfsd) "+" ".join(["S"]+["0"]*18+["1234"])+"\n")
                (proc/"cmdline").write_bytes(("/usr/bin/ganesha.nfsd\0-F\0-f\0"+str(target)+"\0").encode())
                unit="ablestack-storage-ganesha@"+endpoint["legacyUnitKey"]+".service"
                (proc/"cgroup").write_text("0::/system.slice/"+unit+"\n")
                binary=self.osroot/"usr/bin/ganesha.nfsd";binary.parent.mkdir(parents=True,exist_ok=True)
                if not binary.exists():binary.write_bytes(b"FIXTURE_GANESHA_EXECUTABLE");binary.chmod(0o755)
                if not(proc/"exe").exists():(proc/"exe").symlink_to(binary)
                record["endpoints"].append({"listenIp":endpoint["listenIp"],"port":endpoint["port"],
                    "file":name,"pid":pid,"exports":policy["exports"]})
        elif domain=="SMB":
            self.apply_samba_accounts(payload,manifest)
            data=json.loads((replay/"smb/manifest.json").read_text())
            source=replay/"smb/smb.conf";target=self.osroot/"etc/samba/smb.conf"
            target.write_bytes(source.read_bytes());target.chmod(0o600)
            record["configurationSha256"]=hashlib.sha256(target.read_bytes()).hexdigest()
            record["endpoints"]=copy.deepcopy(data.get("listeners",[]))
            self.samba_processes(record)
            clean=copy.deepcopy(payload);clean.pop("operationScope",None);clean.pop("password",None)
            for share in clean.get("shares",[]):
                for acl in share.get("acls",[]):acl.pop("password",None)
            self.driver.persist_one("desired-state/smb-share-apply.json",clean)
        else:
            data=json.loads((replay/("block/iscsi-plan.json"if domain=="ISCSI"else"block/nvmeof-plan.json")).read_text())
            root=self.driver.runtime.iscsi_root if domain=="ISCSI"else self.driver.runtime.nvme_root/"subsystems"
            require(not any(root.iterdir()),"OS_BLOCK_REPLAY_EXPECTS_INITIAL_EMPTY_NAMESPACE")
            for target in data["targets"]:
                base=root/target["targetName"];base.mkdir(mode=0o700)
                if domain=="ISCSI":
                    require(all(not a["config"].get("chapEnabled")and not a["config"].get("mutualChapEnabled")for a in target["acls"]),
                            "OS_AUTHENTICATED_ISCSI_NOT_IMPLEMENTED")
                    tpgt=base/"tpgt_1"
                    for part in("acls","np","lun","attrib"):(tpgt/part).mkdir(parents=True,mode=0o700)
                    (tpgt/"attrib/authentication").write_text("0\n")
                    for endpoint in target["listeners"]:(tpgt/"np"/(endpoint["listenIp"]+":"+str(endpoint["port"]))).mkdir(mode=0o700)
                    for acl in target["acls"]:
                        auth=tpgt/"acls"/acl["principal"]/"auth";auth.mkdir(parents=True,mode=0o700)
                        for name in("userid","userid_mutual","password","password_mutual"):(auth/name).write_text("")
                    for backing in target["backstores"]:
                        lun=tpgt/"lun"/("lun_"+str(backing["number"]));lun.mkdir(mode=0o700)
                        storage=self.osroot/"sys/kernel/config/target/core/iblock_0"/("ablestack-"+target["uuid"])
                        storage.mkdir(parents=True,mode=0o700);(storage/"udev_path").write_text(backing["devicePath"])
                        (lun/"backstore").symlink_to(storage)
                else:
                    require(all(not a["config"].get("dhChapEnabled")and not a["config"].get("dhChapCtrlEnabled")for a in target["acls"]),
                            "OS_AUTHENTICATED_NVME_NOT_IMPLEMENTED")
                    (base/"attr_serial").write_text(target["uuid"].replace("-","")[:20])
                    (base/"attr_model").write_text("SYNTHETIC_OS_NVMET")
                    identity.restore_subsystem(next(row for row in payload["subsystems"]if row["targetName"]==target["targetName"]))
                    (base/"attr_allow_any_host").write_text("1\n"if target["allowAnyHost"]else"0\n")
                    for part in("allowed_hosts","namespaces"):(base/part).mkdir(mode=0o700)
                    for acl in target["acls"]:
                        host=self.driver.runtime.nvme_root/"hosts"/acl["principal"];created=not host.exists()
                        host.mkdir(exist_ok=True,mode=0o700)
                        if created:cleanup.created_host(acl["principal"])
                        (base/"allowed_hosts"/acl["principal"]).symlink_to(host)
                    for backing in target["backstores"]:
                        namespace=base/"namespaces"/str(backing["number"]);namespace.mkdir(mode=0o700)
                        (namespace/"enable").write_text("0\n");(namespace/"device_path").write_text(backing["devicePath"])
                        (namespace/"device_uuid").write_text(backing["volumeUuid"])
                        (namespace/"device_nguid").write_text(backing["volumeUuid"])
                        (namespace/"device_eui64").write_text("0"*16)
                        sub=next(row for row in payload["subsystems"]if row["targetName"]==target["targetName"])
                        ns=next(row for row in sub["namespaces"]if int(row.get("lunOrNamespace",1))==backing["number"])
                        identity.restore_namespace(sub,ns,backing["devicePath"])
                        (namespace/"enable").write_text("1\n")
                    for endpoint in target["listeners"]:
                        ports=self.driver.runtime.nvme_root/"ports"
                        matched=[p for p in ports.iterdir()if cleanup.port_tuple(p)==(endpoint["listenIp"],endpoint["port"])]
                        if matched:require(len(matched)==1,"OS_NVME_DUPLICATE_PORT");port=matched[0]
                        else:
                            key=next(str(n)for n in range(1,4096)if not(ports/str(n)).exists())
                            port=ports/key;port.mkdir(mode=0o700);(port/"subsystems").mkdir(mode=0o700)
                            for field,value in (("addr_trtype","tcp"),("addr_adrfam","ipv4"),
                                    ("addr_traddr",endpoint["listenIp"]),("addr_trsvcid",str(endpoint["port"]))):
                                (port/field).write_text(value)
                            cleanup.created_port(key,(endpoint["listenIp"],endpoint["port"]))
                        (port/"subsystems"/target["targetName"]).symlink_to(base)
                for endpoint in target["listeners"]:
                    if endpoint not in record["endpoints"]:record["endpoints"].append(copy.deepcopy(endpoint))
        if domain=="NVMEOF":
            cleanup.finish({target["targetName"]:{p.name for p in (self.driver.runtime.nvme_root/"ports").iterdir()
                if (p/"subsystems"/target["targetName"]).exists()}for target in data["targets"]})
            self.driver.persist_one("nvmeof-subsystems.json",self.nvme_identity_module.nvme_identity_redact(payload))
        state={**self.os_state(),"domains":{**state["domains"],domain:record}};self.write_state(state)
        self.effects.append({"kind":"STATEFUL_OS_SERVICE_APPLY","domain":domain,"manifestSha256":manifest["manifestSha256"],
                             "listenerCount":len(record["endpoints"]),"nativeGenerationPromoted":False})
        return {"success":True,"fixtureServiceEffect":True,"runtimeGenerationWritten":False}
    def command(self, arguments,payload=None,**kwargs):
        command=tuple(arguments)
        self.effects.append({"kind":"EXPLICIT_OS_TRANSPORT_BOUNDARY","command":list(command),
                             "requestKeys":sorted((payload or {}).keys())})
        if command[:2]==("operation","generation"):
            action=command[2]
            if action.startswith("render-"):return self.driver.execute(action,payload)
            return self.generation.execute(action,payload)
        if command==("identity","capsule","export"):
            require(type(payload)is dict and set(payload)=={"instanceUuid","operationUuid","revision","publicKey","names","nvmeHosts"}
                    and not kwargs,"EXACT_INTERNAL_GENERIC_EXPORT_REQUEST_REQUIRED")
            scope=self.generation.request({key:payload[key]for key in("instanceUuid","operationUuid","revision")})
            require(all(scope[key]==payload[key]for key in scope),"INTERNAL_GENERIC_EXPORT_SCOPE_NOT_CANONICAL")
            self.generation.scoped(scope)
            with self.local_os_boundary():
                return self.export_generic_identity({key:value for key,value in payload.items()if key!="revision"})
        if command==("smb","identity","local-source-status"):
            with self.local_os_boundary():return self.local.status(payload)
        if command in (("identity","capsule","import-local-source"),("identity","capsule","replay-local-source-auth")):
            with self.local_os_boundary():
                result=self.local.import_source(payload) if command[-1]=="import-local-source" else self.local.replay_auth(payload)
                if command[-1]=="import-local-source":
                    # The real producer has restored the files. Reconcile only
                    # the synthetic OS account cache with their actual absence.
                    private=self.osroot/"var/lib/samba/private"
                    absent=all(not(private/name).exists()for name in("passdb.tdb","secrets.tdb"))
                    absent=absent and all(not(self.generation.config/name).exists()
                        for name in("smb-managed-identities.json","smb-local-account-provenance.json"))
                    if absent:
                        require(all(not any((self.osroot/"etc"/name).read_text().splitlines())for name in("passwd","group","shadow","gshadow")),
                                "OS_ABSENT_SAM_RETAINS_POSIX_ACCOUNT")
                        state=self.os_state();state.pop("sambaAccounts",None);self.write_state(state)
                return result
        if command==("operation","quiesce"):return self.service.quiesce(payload)
        if command==("operation","verify"):return self.health()
        if command==("operation","maintenance","status"):
            return self.maintenance.status()
        if command==("operation","root-data","inspect"):return self.root_data(payload)
        if command==("sessions",):
            connections=self.os_state()["connections"]
            return {"success":True,"status":"ok","sessionSchemaVersion":2,"sessions":connections,
                "observedIscsiTcpCount":sum(r.get("protocol")=="ISCSI"for r in connections),
                "observedNvmeofTcpCount":sum(r.get("protocol")=="NVME_OF"for r in connections),"warnings":[]}
        applies={("nfs","export","apply"):"NFS",("smb","share","apply"):"SMB",
                 ("iscsi","target","apply"):"ISCSI",("nvmeof","subsystem","apply"):"NVMEOF"}
        if command==("nvmeof","subsystem","apply","/dev/stdin"):
            require(not kwargs,"OS_DIRECT_NVME_CLEANUP_HAS_RENDERED_KWARGS")
            return self.cleanup_missing_replay(payload)
        if command in applies:return self.kernel_apply(applies[command],payload,kwargs.get("replay"),kwargs.get("replay_from"))
        if command==("network","endpoints","reconcile"):
            canonical=self.generation.files()["network-endpoints.json"]or{"endpoints":[]}
            proof=self.network_inspect({**canonical,"expectedBindings":payload["expectedBindings"]})
            checksum=hashlib.sha256(json.dumps(canonical,sort_keys=True,separators=(",",":")).encode()).hexdigest()
            receipt={"desiredStateSha256":checksum,"expectedBindings":copy.deepcopy(payload["expectedBindings"])}
            path=self.osroot/"var/lib/ablestack-storage/network-endpoint-bindings.json"
            path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
            path.write_text(json.dumps(receipt,sort_keys=True)+"\n");path.chmod(0o600)
            require(json.loads(path.read_text())==receipt,"OS_NETWORK_RECEIPT_READBACK")
            return {**proof,"bindingReceiptVerified":True,"desiredStateSha256":checksum,
                    "bindingReceiptDesiredStateSha256":checksum}
        if command==("network","endpoints","inspect"):return self.network_inspect(payload)
        raise ValueError("RENDERED_OS_COMMAND_NOT_IMPLEMENTED")
    def os_run(self, args, **kwargs):
        require(type(args)in(list,tuple),"EXACT_OS_ARGV_REQUIRED")
        args=list(args)
        if len(args)==3 and args[:2]==["testparm","-s"]:
            named=self.original_path(args[2]);path=named.resolve(strict=True)
            local=(named.parent==self.osroot/"etc/samba"and named.name.startswith(".local-source-smb-"))
            if local:
                self.path_guard.path(named);info=named.lstat()
                require(named==path and stat.S_ISREG(info.st_mode)and info.st_uid==os.geteuid()
                        and info.st_nlink==1 and not info.st_mode&0o022,"LOCAL_TESTPARM_CANDIDATE_NOT_PROTECTED")
            require(local or self.store.root in path.parents,"TESTPARM_CANDIDATE_OUTSIDE_CASE")
            parser=configparser.ConfigParser(interpolation=None,strict=True)
            parser.read_string(path.read_text())
            require("global"in parser and parser["global"].get("security")=="user",
                    "SYNTHETIC_OS_TESTPARM_CONFIG_REJECTED")
            require(not parser.has_option("global","smb ports") or bool(parser["global"]["smb ports"].strip()),
                    "RENDERED_SMB_EMPTY_PORTS")
            self.effects.append({"kind":"OS_SYNTAX_FIXTURE_ONLY","tool":"testparm",
                                 "candidateSha256":hashlib.sha256(path.read_bytes()).hexdigest()})
            return types.SimpleNamespace(returncode=0,stdout="\n".join("["+x+"]"for x in parser.sections())+"\n",stderr="")
        if args[:1]==["unshare"]:
            path=self.original_path(args[-1]).resolve(strict=True)
            require(self.store.root in path.parents,"NFS_PARSER_CANDIDATE_OUTSIDE_CASE")
            manifest=json.loads((path/"nfs/manifest.json").read_text())
            parsed=[]
            for endpoint in manifest["endpoints"]:
                config=path/endpoint["configurationPath"]
                require(config.is_file()and path in config.parents,"NFS_PARSER_CONFIGURATION_OUTSIDE_CASE")
                policy=self.ganesha_module.GaneshaDbus.configuration(config.read_text())
                require(type(policy.get("exports"))is list,"NFS_PARSER_EXPORT_SCHEMA")
                parsed.extend(policy["exports"])
            self.effects.append({"kind":"OS_EXTERNAL_NFS_PARSER_FIXTURE_ONLY",
                "candidateSha256":hashlib.sha256((path/"nfs/manifest.json").read_bytes()).hexdigest(),
                "actualProductGaneshaParserUsed":True,"exportCount":len(parsed)})
            return types.SimpleNamespace(returncode=0,stdout=json.dumps({"success":True,"nfsParserVerified":True}),stderr="")
        if args==["systemctl","daemon-reload"]:
            content="[Service]\nExecCondition=/usr/local/bin/ablestack-storagectl operation generation render-boot-gate %n\n"
            for unit in ("smbd.service","nmbd.service","winbind.service","nfs-server.service",
                         "nfs-kernel-server.service","nfs-ganesha.service","target.service","rtslib-fb-targetctl.service"):
                target=self.osroot/"etc/systemd/system"/(unit+".d")/"ablestack-storage-gate.conf"
                require(target.read_text()==content,"OS_BOOT_GUARD_FILE_NOT_MATERIALIZED")
            self.effects.append({"kind":"OS_SYSTEMD_RELOAD_FIXTURE_ONLY","guardFiles":8})
            return types.SimpleNamespace(returncode=0,stdout="",stderr="")
        raise ValueError("RENDERED_OS_SUBPROCESS_NOT_IMPLEMENTED")
    def export_generic_identity(self,request):
        # The existing CLI generic export branch, restricted to this exact
        # non-AD, no-managed-principals SOURCE request. No LOCAL/SERVICE receipt
        # is invented and no machine SID/SAM is initialized for collection.
        require(type(request)is dict and set(request)=={"instanceUuid","operationUuid","publicKey","names","nvmeHosts"}
                and request["names"]==[]and request["nvmeHosts"]==[],"EXACT_EMPTY_GENERIC_EXPORT_REQUEST_REQUIRED")
        scope=str(request.get("instanceUuid")or"")+":"+str(request.get("operationUuid")or"")
        require(re.fullmatch(r"[0-9a-fA-F-]{36}:[0-9a-fA-F-]{36}",scope),"Identity capsule request scope is invalid")
        domain_path=self.mapped_path("/etc/ablestack-storage/smb-domain.json")
        domain=json.loads(domain_path.read_text())if domain_path.exists()else{}
        require(str(domain.get("joinState")or domain.get("state")or"").upper()!="JOINED",
                "Joined AD RAW export requires typed SERVICE or ROOT AFTERSTOP source authority")
        payload=self.capsule_module.collect(request["names"],request["nvmeHosts"])
        self.capsule_module.validate_payload(payload)
        capsule=self.capsule_module.encrypt(payload,request["publicKey"],scope)
        return {"success":True,"capsule":capsule,"rootIdentityReference":None,"serviceIdentityCheckpoint":None}

    def execute(self, action, request=None):
        require(action in ("render-status","render-import","render-stage","export-identity","export-local-source","local-source-status",
                                    "service-enter","service-status","service-release","reservation-status","render-activate","render-finalize","render-rollback","render-maintenance-resume-source","render-maintenance-verify","observe","inventory","verify","health","writer-idle"),
                "RENDERED_ACTION_NOT_IMPLEMENTED")
        previous=dict(os.environ)
        os.environ["ABLESTACK_STORAGE_GENERATION_DIR"]=str(self.generation.root)
        os.environ["ABLESTACK_STORAGE_CONFIGURATION_ROOT"]=str(self.generation.config)
        os.environ["ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR"]=str(self.maintenance.root)
        os.environ["ABLESTACK_STORAGE_VOLUME_OPERATIONS"]=str(self.case/"volume-operations")
        os.environ["ABLESTACK_STORAGE_RESERVATION_DIR"]=str(self.case/"resource-reservation")
        os.environ["ABLESTACK_STORAGE_WRITER_LOCK_FILE"]=str(self.case/"desired-writer.lock")
        os.environ["ABLESTACK_STORAGE_BOOT_CHILD_JOURNAL"]=str(self.case/"boot-execution/current.json")
        try:
            with patch.object(self.driver_module,"Path",self.mapped_path), \
                 patch.object(self.runtime_module,"Path",self.mapped_path), \
                 patch.object(self.driver_module.subprocess,"run",self.os_run), \
                 patch.object(self.network_module,"Path",self.mapped_path), \
                 patch.object(self.runtime_module,"GaneshaDbus",self.dbus_adapter), \
                 patch.object(socket,"create_connection",self.tcp_fixture), \
                 patch.object(shutil,"which",lambda name: "/fixture/targetcli" if name=="targetcli" else self.original_which(name)):
                if action=="writer-idle":return self.native_writer_idle()
                if action in ("observe","inventory"):return self.inventory()
                if action in ("verify","health"):return self.verified_health()
                if action=="service-status":return self.service.status(request)
                if action=="reservation-status":
                    module=importlib.import_module("resource_reservation")
                    return module.ResourceReservation(self.case/"resource-reservation",self.generation.root,
                        boot_id=self.generation.status()["bootId"],maintenance=self.maintenance.marker).status()
                if action in ("service-enter","service-release"):
                    with self.actual_writer_lock():
                        return self.service.enter(request) if action=="service-enter" else self.service.release(request)
                if action=="export-identity":
                    with self.local_os_boundary():return self.export_generic_identity(request)
                if action in ("export-local-source","local-source-status"):
                    with self.local_os_boundary():
                        if action=="local-source-status":return self.local.status(request)
                        with self.actual_writer_lock():return self.local.export(request)
                if action=="render-rollback":
                    with self.local_os_boundary(),self.actual_writer_lock():return self.driver.execute(action,request)
                if action in ("render-stage","render-activate","render-finalize","render-maintenance-resume-source"):
                    with self.local_os_boundary(),self.actual_writer_lock():return self.driver.execute(action,request)
                return self.driver.execute(action,request)
        except Exception as error:
            self.effects.append({"kind":"SAFE_REAL_ENGINE_EXCEPTION","exceptionClass":type(error).__name__,
                "safeLocations":[{"file":self.original_path(frame.filename).name,"function":frame.name,"line":frame.lineno}
                    for frame in traceback.extract_tb(error.__traceback__)]})
            raise
        finally:
            os.environ.clear();os.environ.update(previous)
            self.save_os_ledger()
