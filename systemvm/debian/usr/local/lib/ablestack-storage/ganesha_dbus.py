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

"""Owned Ganesha DBus export updates with exact inventory readback."""
import ipaddress
import hashlib
import json
import re
import time

EXPORT_PATH="/org/ganesha/nfsd/ExportMgr"
EXPORT_INTERFACE="org.ganesha.nfsd.exportmgr"


class GaneshaDbus:
    def __init__(self,bus=None,deadline=None):
        if bus is None:
            import dbus
            bus=dbus.SystemBus()
        self.bus=bus;self.deadline=deadline or time.monotonic()+30

    def budget(self):
        remaining=self.deadline-time.monotonic()
        if remaining<=0:raise TimeoutError("Ganesha DBus total deadline exceeded")
        return min(5,remaining)

    @staticmethod
    def name(listen_ip,port):
        key=hashlib.sha256((listen_ip+":"+str(port)).encode()).hexdigest()[:24]
        return "ablestack_storage_ganesha_e"+key+".org.ganesha.nfsd"

    def owned_manager(self,name,pid):
        if not re.fullmatch(r"(?:org\.ganesha\.nfsd|ablestack_storage_ganesha_e[0-9a-f]{24}\.org\.ganesha\.nfsd)",name):
            raise ValueError("Ganesha bus name is outside its managed namespace")
        owner=self.bus.get_object('org.freedesktop.DBus','/org/freedesktop/DBus').get_dbus_method('GetConnectionUnixProcessID','org.freedesktop.DBus')
        if int(owner(name,timeout=self.budget()))!=pid:raise ValueError("Ganesha DBus owner differs from the owned endpoint process")
        return self.bus.get_object(name,EXPORT_PATH)

    def owned_endpoint_manager(self,listen_ip,port,pid):
        name=self.name(listen_ip,port)
        try:return name,self.owned_manager(name,pid)
        except Exception as current_error:
            # An old daemon can retain its one legacy name until its next boot.
            # A foreign managed owner or unknown lookup failure is not absence.
            missing=getattr(current_error,"get_dbus_name",None)
            if not callable(missing) or missing()!="org.freedesktop.DBus.Error.NameHasNoOwner":raise
            try:return "org.ganesha.nfsd",self.owned_manager("org.ganesha.nfsd",pid)
            except Exception:raise ValueError("No managed or legacy Ganesha bus belongs to the owned endpoint PID") from current_error

    def call(self,manager,method,*arguments):
        if method not in ('ShowExports','DisplayExport','UpdateExport','RemoveExport'):
            raise ValueError("Unknown fixed Ganesha export method")
        return manager.get_dbus_method(method,EXPORT_INTERFACE)(*arguments,timeout=self.budget())

    def inventory(self,manager):
        response=self.call(manager,'ShowExports')
        if not isinstance(response,(list,tuple)) or len(response)!=2:raise ValueError("Ganesha export inventory has an unknown schema")
        result={}
        for row in response[1]:
            if len(row)<2:raise ValueError("Ganesha export row is malformed")
            identifier=int(row[0]);path=str(row[1])
            if identifier in result:raise ValueError("Ganesha export inventory duplicates an ID")
            if identifier!=0:result[identifier]=path
        return result

    @staticmethod
    def client_identifier(value):
        value=str(value).strip()
        return "*" if value=="*" else str(ipaddress.ip_network(value,strict=False))

    def verify_clients(self,displayed,wanted):
        observed={}
        for client in displayed:
            if len(client)!=10:raise ValueError("Ganesha client permission readback has an unknown schema")
            name=self.client_identifier(client[0])
            if name in observed:raise ValueError("Ganesha client permission binding duplicates")
            observed[name]=client
        if set(observed)!={row["client"] for row in wanted}:raise ValueError("Ganesha client access set differs")
        for row in wanted:
            actual=observed[row["client"]];options=int(actual[8]);configured=int(actual[9])
            if configured&0x1e7!=0x1e7 or options&0x1e7!=row["options"]:
                raise ValueError("Ganesha client access/squash policy differs")
            for key,position,mask in (("anonUid",5,8),("anonGid",6,16)):
                if row[key] is not None and (configured&mask!=mask or int(actual[position])!=row[key]):
                    raise ValueError("Ganesha client anonymous identity differs")

    def verify(self,manager,expected):
        wanted={int(row['exportId']):row['pseudo'] if row.get('mountPathPseudo') else row['path'] for row in expected}
        if len(wanted)!=len(expected) or self.inventory(manager)!=wanted:
            raise ValueError("Ganesha export inventory differs from the exact declared generation")
        for row in expected:
            displayed=self.call(manager,'DisplayExport',int(row['exportId']))
            # Ganesha 4.3 DisplayExport returns ID/fullpath/displaypath/tag/clients.
            # displaypath follows mount_path_pseudo; NFSv4 pseudo visibility
            # must also be confirmed by the native mount/RPC probe.
            if len(displayed)!=5 or int(displayed[0])!=int(row['exportId']) or str(displayed[1])!=row['path'] or str(displayed[2])!=(row['pseudo'] if row.get('mountPathPseudo') else row['path']):
                raise ValueError("Ganesha export path/pseudo binding differs")
            if "clients" in row:self.verify_clients(displayed[4],row["clients"])
        return True

    @staticmethod
    def configuration(content):
        # Parse only the fixed serializer's complete EXPORT blocks. Braces inside
        # quoted paths do not terminate a block, and duplicate bindings reject.
        chunks=[];exports=[];cursor=0
        for match in re.finditer(r"(?m)^EXPORT\s*\{",content):
            if match.start()<cursor:continue
            start=match.start();position=match.end();depth=1;quoted=False;escaped=False
            while position<len(content) and depth:
                char=content[position]
                if escaped:escaped=False
                elif quoted and char==chr(92):escaped=True
                elif char=='"':quoted=not quoted
                elif not quoted and char=='{':depth+=1
                elif not quoted and char=='}':depth-=1
                position+=1
            if depth or quoted:raise ValueError("Fixed Ganesha EXPORT block is truncated")
            block=content[start:position];chunks.append(content[cursor:start]);cursor=position
            def field(name,pattern):
                rows=re.findall(r"(?m)^\s*"+name+r"\s*=\s*("+pattern+r")\s*;",block)
                if len(rows)!=1:raise ValueError("Fixed Ganesha export binding is ambiguous")
                return rows[0]
            identifier=int(field('Export_Id',r'[0-9]+'))
            path=json.loads(field('Path',r'"(?:[^"\\]|\\.)*"'))
            pseudo=json.loads(field('Pseudo',r'"(?:[^"\\]|\\.)*"'))
            fsal=field('Name',r'[A-Za-z0-9_]+')
            if fsal!='VFS':raise ValueError("Fixed Ganesha export FSAL is unsupported")
            clients=[]
            for client_block in re.findall(r"(?m)^[ \t]*CLIENT\s*\{([^{}]*)\}",block,re.S):
                def client_field(name,default=None):
                    values=re.findall(r"(?m)^\s*"+name+r"\s*=\s*([^;]+);",client_block)
                    if len(values)>1:raise ValueError("Ganesha client policy is ambiguous")
                    return values[0].strip() if values else default
                access=client_field("Access_Type");squash=client_field("Squash")
                access_bits={"RW":0x1e0,"RO":0xa0,"None":0};squash_bits={"Root_Squash":2,"All_Squash":4,"No_Root_Squash":0}
                if access not in access_bits or squash not in squash_bits:raise ValueError("Ganesha client policy is outside its fixed serializer")
                uid=client_field("Anonymous_uid");gid=client_field("Anonymous_gid")
                for item in client_field("Clients","").split(","):
                    clients.append({"client":GaneshaDbus.client_identifier(item),"options":access_bits[access]|squash_bits[squash],"anonUid":int(uid) if uid is not None else None,"anonGid":int(gid) if gid is not None else None})
            # The serializer fixes every export-level property outside CLIENT.
            # Only client access/squash/anonymous policy is a dynamic change.
            structure=re.sub(r"(?m)^[ \t]*CLIENT\s*\{[^{}]*\}[ \t]*\n?", "", block, flags=re.S)
            exports.append({'exportId':identifier,'path':path,'pseudo':pseudo,'fsal':fsal,'clients':clients,
                            'structureSha256':hashlib.sha256(structure.strip().encode()).hexdigest(),
                            'mountPathPseudo':bool(re.search(r'(?i)mount_path_pseudo\s*=\s*true\s*;',content))})
        chunks.append(content[cursor:])
        # The managed DBus name is claimed at daemon startup. Updating only this
        # prefix does not alter NFS binding/global behavior of a running daemon;
        # the old owned bus is used until its next boot, then the new name.
        # The locked Ganesha consumer accepts one alnum/underscore identifier.
        # Its former dotted prefix is accepted here only to parse old configs.
        global_content=''.join(chunks)
        prefixes=re.findall(r'(?m)^[ \t]*Dbus_Name_Prefix[ \t]*=[ \t]*"([^"\n]+)"[ \t]*;[ \t]*$',global_content)
        if len(prefixes)>1 or any(not re.fullmatch(r'(?:org\.ablestack\.storage\.ganesha\.e|ablestack_storage_ganesha_e)[0-9a-f]{24}',prefix) for prefix in prefixes):
            raise ValueError("Ganesha DBus prefix is outside the fixed endpoint namespace")
        global_content=re.sub(r'(?m)^[ \t]*Dbus_Name_Prefix[ \t]*=[ \t]*"[^"\n]+"[ \t]*;[ \t]*\n?', '', global_content)
        if len({row['exportId'] for row in exports})!=len(exports):raise ValueError("Fixed Ganesha export IDs duplicate")
        return {'exports':exports,'globalSha256':hashlib.sha256(global_content.strip().encode()).hexdigest()}

    def transition(self,name,pid,configuration,previous_content,target_content,source_configuration=None):
        before=self.configuration(previous_content);after=self.configuration(target_content)
        if before['globalSha256']!=after['globalSha256']:
            raise ValueError("NFS global/listener change requires explicit drain/recreate")
        source=None
        if source_configuration is not None:
            raw=self.source_bytes(source_configuration)
            if raw.decode()!=previous_content:raise ValueError("Ganesha immutable SOURCE configuration differs")
            source=(str(source_configuration),hashlib.sha256(raw).hexdigest())
        return self.update(name,pid,configuration,before['exports'],after['exports'],source)

    def update(self,name,pid,configuration,previous,target,source_configuration=None):
        old={int(row['exportId']):row for row in previous};wanted={int(row['exportId']):row for row in target}
        if len(old)!=len(previous) or len(wanted)!=len(target):raise ValueError("Ganesha generation duplicates an export ID")
        for identifier in old.keys() & wanted.keys():
            if any(old[identifier].get(field)!=wanted[identifier].get(field) for field in ('path','pseudo','fsal','structureSha256')):
                raise ValueError("NFS immutable export binding change requires explicit drain/recreate")
        # Add/remove are structural maintenance, never an ACL fast path.
        if old.keys()!=wanted.keys():raise ValueError("NFS export set change requires the explicit native drain adapter")
        manager=self.owned_manager(name,pid);self.verify(manager,previous)
        changed=[identifier for identifier in sorted(wanted) if old[identifier]!=wanted[identifier]]
        attempted=[]
        try:
            for identifier in changed:
                # An error response is ambiguous about whether mutation started.
                # Include that exact ID in SOURCE compensation as well.
                attempted.append(identifier)
                self.update_one(manager,configuration,identifier)
            self.verify(manager,target)
            self.owned_manager(name,pid)
        except Exception as original:
            if source_configuration is not None and attempted:
                try:
                    self.owned_manager(name,pid)
                    for identifier in reversed(attempted):
                        if hashlib.sha256(self.source_bytes(source_configuration[0])).hexdigest()!=source_configuration[1]:
                            raise ValueError("Ganesha immutable SOURCE fingerprint changed")
                        self.update_one(manager,source_configuration[0],identifier)
                    self.verify(manager,previous)
                    self.owned_manager(name,pid)
                except Exception as recovery:
                    raise RuntimeError("DBUS_DYNAMIC_UPDATE_FAILED_SOURCE_ROLLBACK_UNVERIFIED") from original
                raise RuntimeError("DBUS_DYNAMIC_UPDATE_FAILED_SOURCE_ROLLBACK_VERIFIED") from original
            raise
        return {'success':True,'activationStrategy':'DBUS_DYNAMIC_UPDATE_VERIFIED','processPid':pid,
                'exportsVerified':len(wanted),'exportsUpdated':len(changed),'restartPerformed':False}

    @staticmethod
    def source_bytes(path):
        import os,stat
        from pathlib import Path
        path=Path(path);before=path.lstat();parent=path.parent.lstat()
        if (not stat.S_ISREG(before.st_mode) or before.st_uid!=os.geteuid() or before.st_mode&0o022 or
                before.st_nlink!=1 or before.st_size>8*1024*1024 or not stat.S_ISDIR(parent.st_mode) or
                parent.st_uid!=os.geteuid() or parent.st_mode&0o022):
            raise ValueError("Ganesha immutable SOURCE file is not protected")
        def identity(info):
            return tuple(getattr(info,key) for key in ("st_dev","st_ino","st_uid","st_gid","st_mode","st_nlink","st_size","st_mtime_ns","st_ctime_ns"))
        descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_CLOEXEC)
        try:
            if identity(before)!=identity(os.fstat(descriptor)):raise ValueError("Ganesha SOURCE changed while opening")
            data=os.read(descriptor,8*1024*1024+1)
            if len(data)!=before.st_size or identity(before)!=identity(os.fstat(descriptor)) or identity(before)!=identity(path.lstat()):
                raise ValueError("Ganesha SOURCE changed while reading")
            return data
        finally:os.close(descriptor)

    def update_one(self,manager,configuration,identifier):
        acknowledgement=self.call(manager,'UpdateExport',str(configuration),'EXPORT(Export_Id='+str(identifier)+')')
        if isinstance(acknowledgement,(list,tuple)) and len(acknowledgement)==1:
            acknowledgement=acknowledgement[0]
        if not isinstance(acknowledgement,str) or not re.fullmatch(r"1 exports? updated",acknowledgement.strip(),re.I):
            raise ValueError("Ganesha export update did not acknowledge exactly one export")



class GaneshaDbusPolicy:
    """Explicit activation-only policy preparation; construction never writes.

    Root's prerequisite deployment may call prepare() without replacing CODE3
    or taking FD9. It only publishes this policy and reloads the bus; starting
    an owned daemon remains the normal activation caller's separate decision.
    """
    MAX_NAMES = 256
    MAX_POLICY = 128 * 1024
    NAME = r"ablestack_storage_ganesha_e[0-9a-f]{24}\.org\.ganesha\.nfsd"
    HEADER = ('<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN" '
              '"http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">\n'
              '<!-- Managed exact endpoint names; no wildcard or prefix grants. -->\n')

    def __init__(self, config_root=None, policy_path=None, bus=None, deadline=None, rendered_source=None):
        from pathlib import Path
        self.config_root = Path(config_root or "/etc/ganesha/ablestack-storage")
        self.policy_path = Path(policy_path or "/etc/dbus-1/system.d/ablestack-storage-ganesha-endpoints.conf")
        self.bus = bus
        self.rendered_source = rendered_source
        self.deadline = deadline if deadline is not None else time.monotonic() + 10

    @staticmethod
    def signature(value):
        return tuple(getattr(value, name) for name in
                     ("st_dev", "st_ino", "st_uid", "st_gid", "st_mode",
                      "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns"))

    @staticmethod
    def directory(path):
        import os, stat
        info = path.lstat()
        if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid()
                or info.st_mode & 0o022):
            raise ValueError("Ganesha policy directory is not protected")
        return info.st_dev, info.st_ino, info.st_uid, info.st_gid, info.st_mode

    @classmethod
    def read(cls, path, maximum):
        import os, stat
        before = path.lstat()
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid()
                or before.st_mode & 0o022 or before.st_nlink != 1
                or before.st_size > maximum):
            raise ValueError("Ganesha policy input is not protected")
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | getattr(os, "O_NOATIME", 0))
        try:
            if cls.signature(before) != cls.signature(os.fstat(descriptor)):
                raise ValueError("Ganesha policy input changed while opening")
            with os.fdopen(descriptor, "rb", closefd=False) as handle:
                raw = handle.read(maximum + 1)
            if (len(raw) != before.st_size or len(raw) > maximum
                    or cls.signature(before) != cls.signature(os.fstat(descriptor))
                    or cls.signature(before) != cls.signature(path.lstat())):
                raise ValueError("Ganesha policy input changed while reading")
            return raw, cls.signature(before)
        finally:
            os.close(descriptor)

    @classmethod
    def render(cls, names):
        names = set(names)
        if len(names) > cls.MAX_NAMES or any(type(name) is not str or
                                            not re.fullmatch(cls.NAME, name) for name in names):
            raise ValueError("Ganesha policy names exceed the exact managed scope")
        lines = [cls.HEADER.rstrip(), "<busconfig>", '  <policy context="default">']
        for name in sorted(names):
            lines.extend(('    <deny own="' + name + '"/>',
                          '    <deny send_destination="' + name + '"/>'))
        lines.extend(("  </policy>", '  <policy user="root">'))
        for name in sorted(names):
            lines.extend(('    <allow own="' + name + '"/>',
                          '    <allow send_destination="' + name + '"/>'))
        lines.extend(("  </policy>", "</busconfig>"))
        raw = ("\n".join(lines) + "\n").encode()
        if len(raw) > cls.MAX_POLICY:
            raise ValueError("Ganesha policy exceeds its bounded size")
        return raw

    @classmethod
    def parse(cls, raw):
        # Only our complete canonical document is replaceable. In particular,
        # partial rules, foreign XML, entities and additional privileges reject.
        names = set(re.findall(rb'<allow own="([^"]+)"/>', raw))
        try:
            names = {name.decode("ascii") for name in names}
        except UnicodeDecodeError:
            raise ValueError("Ganesha policy namespace is not ASCII") from None
        if raw != cls.render(names):
            raise ValueError("Existing Ganesha policy is foreign")
        return names

    @staticmethod
    def endpoint_name(endpoint):
        if not isinstance(endpoint, (list, tuple)) or len(endpoint) != 2:
            raise ValueError("Ganesha policy endpoint is malformed")
        ip, port = endpoint
        if type(ip) is not str or type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("Ganesha policy endpoint has invalid types")
        ip = str(ipaddress.ip_address(ip))
        return GaneshaDbus.name(ip, port)

    def rollback_source_configuration(self, named, link):
        """Resolve ownership against the pinned TARGET, never the switched current."""
        from pathlib import Path
        root = Path("/var/lib/ablestack-storage/rendered-generations")
        source = Path(self.rendered_source)
        if not re.fullmatch(re.escape(str(root / "generations")) + r"/[0-9a-f-]{36}", str(source)):
            raise ValueError("Ganesha rollback source is outside its immutable store")
        for directory in (root, root / "generations", source):
            self.directory(directory)
        raw, _ = self.read(source / "generation.json", 8 * 1024 * 1024)
        manifest = json.loads(raw)
        canonical = {key: value for key, value in manifest.items() if key != "manifestSha256"}
        digest = hashlib.sha256(json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        if digest != manifest.get("manifestSha256"):
            raise ValueError("Ganesha rollback source manifest changed")
        raw, _ = self.read(root / "activation.json", 128 * 1024)
        journal = json.loads(raw)
        current = root / "current"
        if (journal.get("phase") != "ROLLING_BACK" or journal.get("targetSha256") != digest
                or journal.get("scope") != manifest.get("scope")
                or source.name != journal.get("scope", {}).get("operationUuid")
                or not current.is_symlink()
                or current.readlink().as_posix() != "generations/" + str(journal.get("previousOperationUuid"))):
            raise ValueError("Ganesha rollback source lacks its exact inverse boundary")
        previous = root / current.readlink()
        self.directory(previous)
        previous_raw, _ = self.read(previous / "generation.json", 8 * 1024 * 1024)
        previous_manifest = json.loads(previous_raw)
        previous_canonical = {key: value for key, value in previous_manifest.items() if key != "manifestSha256"}
        previous_digest = hashlib.sha256(json.dumps(previous_canonical, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        if (previous_digest != journal.get("previousSha256")
                or previous_manifest.get("manifestSha256") != previous_digest
                or manifest.get("previousRenderedSha256") != previous_digest):
            raise ValueError("Ganesha rollback previous/source pins changed")
        relative = str(Path(link).relative_to(root / "current"))
        raw, _ = self.read(source / "nfs/manifest.json", 8 * 1024 * 1024)
        if hashlib.sha256(raw).hexdigest() != manifest.get("files", {}).get("nfs/manifest.json"):
            raise ValueError("Ganesha rollback endpoint manifest changed")
        rows = json.loads(raw).get("endpoints", [])
        matching = [row for row in rows if isinstance(row.get("legacyUnitKey"), str) and row["legacyUnitKey"] + ".conf" == named.name
                    and row.get("configurationPath") == relative]
        if len(matching) != 1:
            raise ValueError("Ganesha rollback configuration is outside its exact TARGET")
        target = source / relative
        raw, _ = self.read(target, 8 * 1024 * 1024)
        if hashlib.sha256(raw).hexdigest() != manifest.get("files", {}).get(relative):
            raise ValueError("Ganesha rollback configuration changed")
        return target

    def source_names(self):
        import os, stat
        from pathlib import Path
        if not self.config_root.exists() and not self.config_root.is_symlink():
            return set(), []
        self.directory(self.config_root)
        files = sorted(path for path in self.config_root.iterdir() if path.suffix == ".conf")
        if len(files) > self.MAX_NAMES:
            raise ValueError("Ganesha source endpoint count exceeds its bound")
        result, observed, total = set(), [], 0
        for path in files:
            named = self.signature(path.lstat())
            target = path
            if path.is_symlink():
                rendered = Path("/var/lib/ablestack-storage/rendered-generations")
                link = os.readlink(path)
                if (path.lstat().st_uid != os.geteuid() or
                        not re.fullmatch(re.escape(str(rendered / "current/nfs/ganesha")) +
                                         r"/[0-9a-f]{24}\.conf", link)):
                    raise ValueError("Ganesha source configuration link is foreign")
                target = (self.rollback_source_configuration(path, link) if self.rendered_source is not None
                          else path.resolve(strict=True))
                if not re.fullmatch(re.escape(str(rendered / "generations")) +
                                    r"/[0-9a-f-]{36}/nfs/ganesha/[0-9a-f]{24}\.conf", str(target)):
                    raise ValueError("Ganesha rendered configuration escapes its store")
                for parent in (target.parent, target.parent.parent,
                               target.parent.parent.parent, rendered, rendered / "generations"):
                    self.directory(parent)
            raw, identity = self.read(target, 8 * 1024 * 1024)
            total += len(raw)
            if total > 16 * 1024 * 1024:
                raise ValueError("Ganesha source configurations exceed their total bound")
            text = raw.decode()
            GaneshaDbus.configuration(text)
            ports = re.findall(r"(?m)^\s*NFS_Port\s*=\s*([0-9]+)\s*;", text)
            binds = re.findall(r"(?m)^\s*Bind_addr\s*=\s*([^;]+)\s*;", text)
            if len(ports) != 1 or len(binds) != 1:
                raise ValueError("Ganesha source endpoint binding is ambiguous")
            ip = binds[0].strip()
            ip = json.loads(ip) if ip.startswith('"') else ip
            ip = str(ipaddress.ip_address(ip)); port = int(ports[0])
            name = self.endpoint_name((ip, port))
            if path.name != ip.replace(":", "_") + "_" + str(port) + ".conf":
                raise ValueError("Ganesha source endpoint filename differs")
            prefixes = re.findall(r'(?m)^\s*Dbus_Name_Prefix\s*=\s*"([^"]+)"\s*;', text)
            old = "org.ablestack.storage.ganesha.e" + hashlib.sha256((ip + ":" + str(port)).encode()).hexdigest()[:24]
            if prefixes and prefixes not in ([old], [name.removesuffix(".org.ganesha.nfsd")]):
                raise ValueError("Ganesha source prefix differs from its endpoint")
            if named != self.signature(path.lstat()):
                raise ValueError("Ganesha source configuration changed")
            result.add(name); observed.append((path, named, target, identity))
        return result, observed

    def check(self, expected):
        import os
        if expected is None:
            if os.path.lexists(self.policy_path):
                raise ValueError("Ganesha policy appeared before publication")
        elif self.signature(self.policy_path.lstat()) != expected:
            raise ValueError("Ganesha policy changed before publication")

    def sync_directory(self):
        import os
        descriptor = os.open(self.policy_path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try: os.fsync(descriptor)
        finally: os.close(descriptor)

    def publish(self, raw, expected, mode=0o644):
        import os, tempfile
        parent = self.directory(self.policy_path.parent)
        descriptor, temporary = tempfile.mkstemp(prefix=".ganesha-policy-", dir=self.policy_path.parent)
        try:
            os.fchmod(descriptor, mode)
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(raw); handle.flush(); os.fsync(handle.fileno())
            if self.directory(self.policy_path.parent) != parent:
                raise ValueError("Ganesha policy parent changed")
            self.check(expected)
            if expected is None:
                os.link(temporary, self.policy_path, follow_symlinks=False)
                os.unlink(temporary)
            else:
                os.replace(temporary, self.policy_path)
            self.sync_directory()
            actual, identity = self.read(self.policy_path, self.MAX_POLICY)
            if actual != raw: raise ValueError("Published Ganesha policy differs")
            return identity
        finally:
            if os.path.lexists(temporary): os.unlink(temporary)

    def reload(self, cleanup=False):
        if self.bus is None:
            import dbus
            self.bus = dbus.SystemBus()
        remaining = 2 if cleanup else min(3, self.deadline - time.monotonic())
        if remaining <= 0: raise TimeoutError("Ganesha policy deadline exceeded")
        method = self.bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus").get_dbus_method(
            "ReloadConfig", "org.freedesktop.DBus")
        if method(timeout=remaining) is not None:
            raise ValueError("Ganesha bus reload returned an unknown result")

    @staticmethod
    def require_root():
        import os
        if os.geteuid() != 0:
            raise ValueError("Ganesha policy preparation requires root")

    def prepare(self, endpoints=()):
        import os, stat, fcntl
        self.require_root()
        if time.monotonic() >= self.deadline:
            raise TimeoutError("Ganesha policy deadline exceeded")
        targets = set()
        for index, endpoint in enumerate(endpoints):
            if index >= self.MAX_NAMES:
                raise ValueError("Ganesha target endpoint count exceeds its bound")
            targets.add(self.endpoint_name(endpoint))
            if len(targets) > self.MAX_NAMES:
                raise ValueError("Ganesha target endpoint count exceeds its bound")
        self.directory(self.policy_path.parent)
        lock = self.policy_path.with_name("." + self.policy_path.name + ".lock")
        descriptor = os.open(lock, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600)
        try:
            opened = os.fstat(descriptor)
            if (not stat.S_ISREG(opened.st_mode) or opened.st_uid != os.geteuid()
                    or stat.S_IMODE(opened.st_mode) != 0o600 or opened.st_nlink != 1
                    or (opened.st_dev, opened.st_ino) != (lock.lstat().st_dev, lock.lstat().st_ino)):
                raise ValueError("Ganesha policy lock is not protected")
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            old, previous = (None, None)
            if os.path.lexists(self.policy_path):
                old, previous = self.read(self.policy_path, self.MAX_POLICY)
            source, observed = self.source_names()
            names = targets | source | (self.parse(old) if old is not None else set())
            raw = self.render(names)
            if not names and old is None:
                return {"configurationChanged": False, "reloaded": False, "endpointNameCount": 0}
            for path, named, target, identity in observed:
                if self.signature(path.lstat()) != named or self.signature(target.lstat()) != identity:
                    raise ValueError("Ganesha source changed before policy publication")
            if time.monotonic() >= self.deadline:
                raise TimeoutError("Ganesha policy deadline exceeded")
            changed = old != raw
            installed = self.publish(raw, previous) if changed else previous
            try:
                self.reload()
            except Exception as failure:
                try:
                    self.check(installed)
                    if changed:
                        if old is None:
                            os.unlink(self.policy_path); self.sync_directory()
                        else:
                            self.publish(old, installed, previous[4] & 0o777)
                    self.reload(cleanup=True)
                except Exception:
                    raise RuntimeError("Ganesha policy recovery required; no NFS activation permitted") from failure
                raise RuntimeError("Ganesha policy reload failed; no NFS activation permitted") from failure
            return {"configurationChanged": changed, "reloaded": True, "endpointNameCount": len(names),
                    "policySha256": hashlib.sha256(raw).hexdigest(), "policyPath": str(self.policy_path)}
        finally:
            os.close(descriptor)
