# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
"""Scoped address preparation; protocol listeners never rewrite shared address identity."""
import base64
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
import uuid
from config_generation import Generation, read_json, atomic_json, redact
from iscsi_auth import iscsi_auth_writer as network_endpoint_writer


class NetworkEndpointTransaction:
    NETWORK = "network-endpoints.json"
    NFS = "desired-state/nfs-export-apply.json"

    def __init__(self, generation=None, writer=None, read_bytes=None, verify=None,
                 receipt_path=None, receipt_publish=None, restore_addresses=None, receipt_plan=None, dynamic_root=None):
        self.generation = generation or Generation()
        self.writer = writer or network_endpoint_writer
        self.read_bytes = read_bytes
        self.verify = verify
        self.receipt_path = Path(receipt_path or "/var/lib/ablestack-storage/network-endpoint-bindings.json")
        self.receipt_publish = receipt_publish
        self.restore_addresses = restore_addresses
        self.receipt_plan = receipt_plan
        self.dynamic_root=Path(dynamic_root or "/run/ablestack-storage/nfs-dynamic-sources")

    @staticmethod
    def digest(value):
        return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()

    def state(self, request, phases=("PREPARED",)):
        self.writer()
        scope = request.get("operationScope")
        if type(scope) is not dict or set(scope)!={"instanceUuid","operationUuid","revision"}:
            raise ValueError("Network address writer scope is not exact")
        normalized = self.generation.request(scope)
        if normalized!=scope:
            raise ValueError("Network address writer UUID is not canonical")
        pending = self.generation.scoped(scope)
        if stat.S_IMODE(self.generation.pending.lstat().st_mode)!=0o600 or pending.get("phase") not in phases:
            raise ValueError("Network address writer is not protected PREPARED")
        if (read_json(self.generation.current) or {})!=pending.get("previous",{}):
            raise ValueError("Network address SOURCE pointer changed")
        previous = pending.get("previous") or {}
        path=self.generation.root/("network-endpoint-"+scope["operationUuid"]+".json")
        existing=read_json(path)
        source = (self.generation.frozen(previous)["configurationDesiredState"] if previous
                  else existing.get("sourceDesiredState") if isinstance(existing,dict) else self.generation.files())
        if type(source)is not dict or set(source)!=set(self.generation.paths) or redact(source)!=source:
            raise ValueError("Network address SOURCE is not canonical")
        maintenance=Path(os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))
        rendered=Path(os.environ.get("ABLESTACK_STORAGE_RENDERED_GENERATIONS","/var/lib/ablestack-storage/rendered-generations"))
        if (read_json(maintenance/"template-maintenance.json") is not None
                or (rendered/"current").exists() or (rendered/"current").is_symlink()
                or read_json(rendered/"activation.json") is not None):
            raise ValueError("Network address writer cannot borrow ROOT/SERVICE/rendered authority")
        if self.digest(source)!=pending.get("beforeSha256"):
            raise ValueError("Network address frozen SOURCE changed")
        self.scope,self.pending,self.source=scope,pending,source
        self.boot=str(uuid.UUID(Path("/proc/sys/kernel/random/boot_id").read_text().strip()))
        self.path=self.generation.root/("network-endpoint-"+scope["operationUuid"]+".json")
        return source

    def snapshot(self, path):
        raw=self.read_bytes(str(path))
        if raw is None:return {"present":False,"bytes":None,"sha256":None,"mode":None}
        if len(raw)>1024*1024:raise ValueError("Network address source exceeds its bound")
        value=json.loads(raw)
        if redact(value)!=value:raise ValueError("Network address source contains private fields")
        return {"present":True,"bytes":base64.b64encode(raw).decode(),
                "sha256":hashlib.sha256(raw).hexdigest(),"mode":stat.S_IMODE(path.lstat().st_mode)}

    @staticmethod
    def contents(snapshot):
        if snapshot["present"] is False:return None
        raw=base64.b64decode(snapshot["bytes"],validate=True)
        if len(raw)>1024*1024 or hashlib.sha256(raw).hexdigest()!=snapshot["sha256"]:
            raise ValueError("Network address source bytes changed")
        return raw

    def record(self):
        value=read_json(self.path)
        fields={"schemaVersion","scope","bootId","beforeSha256","sourceGeneration","sourceDesiredState","sourceNetwork","sourceReceipt",
                "plannedNetwork","expectedBindings","createdAddresses","phase","expectedNfsDesiredState","targetConfigurationSha256","plannedReceipt","publishedReceipt"}
        if (type(value)is not dict or set(value)!=fields or type(value["schemaVersion"])is not int or value["schemaVersion"]!=1
                or value["scope"]!=self.scope or value["bootId"]!=self.boot
                or value["beforeSha256"]!=self.pending["beforeSha256"]
                or value["sourceGeneration"]!=self.pending.get("previous",{})
                or value["sourceDesiredState"]!=self.source
                or value["phase"] not in ("PREPARING","PREPARED","PUBLISHING","PUBLISHED","RESTORING","RESTORED")):
            raise ValueError("Network address preparation journal is foreign")
        if stat.S_IMODE(self.path.lstat().st_mode)!=0o600:
            raise ValueError("Network address preparation journal is not private")
        raw=self.contents(value["sourceNetwork"])
        if (None if raw is None else json.loads(raw))!=self.source[self.NETWORK]:
            raise ValueError("Network address journal differs from frozen SOURCE")
        self.contents(value["sourceReceipt"])
        if value["plannedReceipt"] is not None:self.contents(value["plannedReceipt"])
        old=[] if self.source[self.NETWORK] is None else self.source[self.NETWORK]["endpoints"]
        planned=[] if value["plannedNetwork"] is None else value["plannedNetwork"]["endpoints"]
        created=value["createdAddresses"]
        if (type(created)is not list or planned[:len(old)]!=old
                or any(type(row)is not dict or set(row)!={"listenIp","primaryIp","prefixlen","macAddress","priorPresent","changed"}
                       or row["priorPresent"] is not False or row["changed"] is not True for row in created)
                or [{key:item for key,item in row.items()if key not in ("priorPresent","changed")}for row in created]!=planned[len(old):]):
            raise ValueError("Address journal OS creation ownership changed")
        return value

    def begin(self, request):
        self.state(request)
        if self.generation.digest()!=self.pending["beforeSha256"]:
            raise ValueError("Network preparation cannot admit SOURCE drift")
        if self.path.exists() or self.path.is_symlink():
            value=self.record()
            if value["phase"] not in ("PREPARING","PREPARED"):
                raise ValueError("Network address preparation is already published")
            return value
        value={"schemaVersion":1,"scope":self.scope,"bootId":self.boot,"beforeSha256":self.pending["beforeSha256"],
               "sourceGeneration":self.pending.get("previous",{}),"sourceDesiredState":self.source,
               "sourceNetwork":self.snapshot(self.generation.config/self.NETWORK),
               "sourceReceipt":self.snapshot(self.receipt_path),
               "plannedNetwork":self.source[self.NETWORK],"expectedBindings":[],"createdAddresses":[],"phase":"PREPARING",
               "expectedNfsDesiredState":None,"targetConfigurationSha256":None,"plannedReceipt":None,"publishedReceipt":None}
        atomic_json(self.path,value)
        return value

    def prepared(self, request, network, bindings, created):
        self.state(request)
        value=self.record()
        if value["phase"] not in ("PREPARING","PREPARED") or self.generation.digest()!=value["beforeSha256"]:
            raise ValueError("Network address SOURCE changed during preparation")
        source=self.source[self.NETWORK]
        old=[] if source is None else source.get("endpoints")
        new=[] if network is None else network.get("endpoints")
        if type(old)is not list or type(new)is not list or new[:len(old)]!=old:
            raise ValueError("Network preparation changed a SOURCE address")
        seen={row.get("listenIp") for row in old}
        for row in new[len(old):]:
            if (type(row)is not dict or set(row)!={"listenIp","primaryIp","prefixlen","macAddress"}
                    or row["listenIp"] in seen):
                raise ValueError("New network address identity is not exact")
            seen.add(row["listenIp"])
        if created!=new[len(old):]:
            raise ValueError("New address metadata lacks exact OS creation proof")
        self.verify(bindings)
        if value["phase"]=="PREPARED" and value["expectedBindings"]!=bindings:
            raise ValueError("Network preparation replay changed its requested address bindings")
        value.update(plannedNetwork=network,expectedBindings=bindings,
                     createdAddresses=[{**row,"priorPresent":False,"changed":True}for row in created],phase="PREPARED")
        atomic_json(self.path,value)
        return self.response(value,"prepared",False)

    @staticmethod
    def encoded_snapshot(value,mode=0o600):
        raw=json.dumps(value,sort_keys=True).encode()
        return {"present":True,"bytes":base64.b64encode(raw).decode(),
                "sha256":hashlib.sha256(raw).hexdigest(),"mode":mode}

    def expected_target(self, request, value):
        nfs=redact(request.get("expectedNfsDesiredState"))
        if type(nfs)is not dict or nfs.get("instanceUuid")!=self.scope["instanceUuid"]:
            raise ValueError("Network publication lacks its exact NFS target")
        if "operationScope" in nfs:
            nfs={key:item for key,item in nfs.items() if key!="operationScope"}
        if value["expectedNfsDesiredState"] is not None and value["expectedNfsDesiredState"]!=nfs:
            raise ValueError("Network publication NFS target changed")
        return {**self.source,self.NFS:nfs}

    def sync_dynamic_checkpoint(self, before, after):
        path=self.dynamic_root/self.scope["operationUuid"]/"checkpoint.json"
        value=read_json(path)
        if value is None:return
        if (type(value.get("schemaVersion"))is not int or value["schemaVersion"]!=1
                or value.get("sourceGeneration")!=self.pending.get("previous",{})
                or type(value.get("endpoints"))is not dict or len(value["endpoints"])>256
                or value.get("scope")!=self.scope or value.get("bootId")!=self.boot
                or value.get("beforeSha256")!=self.pending["beforeSha256"]
                or value.get("phase")!="TARGET_VERIFIED" or value.get("targetDesiredSha256") not in (before,after)):
            raise ValueError("Network publication cannot change a foreign NFS checkpoint")
        if value["targetDesiredSha256"]!=after:
            atomic_json(path,{**value,"targetDesiredSha256":after})

    def publish(self, request):
        self.state(request)
        value=self.record()
        if value["phase"] not in ("PREPARED","PUBLISHING","PUBLISHED"):
            raise ValueError("Network address preparation is not publishable")
        expected=self.expected_target(request,value)
        target={**expected,self.NETWORK:value["plannedNetwork"]}
        current=self.generation.files()
        if current not in (expected,target):
            raise ValueError("Network publication detected unrelated configuration drift")
        self.verify(value["expectedBindings"])
        changed=value["plannedNetwork"]!=self.source[self.NETWORK]
        before,after=self.digest(expected),self.digest(target)
        network_snapshot=self.snapshot(self.generation.config/self.NETWORK)
        source_snapshot=value["sourceNetwork"]
        target_snapshot=self.encoded_snapshot(value["plannedNetwork"]) if changed else source_snapshot
        if network_snapshot not in (source_snapshot,target_snapshot):
            raise ValueError("Network publication detected raw address metadata drift")
        planned_receipt=value["plannedReceipt"]
        if changed and planned_receipt is None:
            if self.receipt_plan is None:raise ValueError("Network receipt intent adapter is unavailable")
            planned_receipt=self.encoded_snapshot(self.receipt_plan(value["plannedNetwork"]))
        receipt=self.snapshot(self.receipt_path)
        if receipt not in (value["sourceReceipt"],planned_receipt):
            raise ValueError("Network publication detected a foreign receipt")
        value.update(phase="PUBLISHING",expectedNfsDesiredState=expected[self.NFS],
                     targetConfigurationSha256=after,plannedReceipt=planned_receipt)
        atomic_json(self.path,value)
        if changed and current!=target:
            atomic_json(self.generation.config/self.NETWORK,value["plannedNetwork"])
        if changed:
            self.receipt_publish(value["plannedNetwork"])
            value["publishedReceipt"]=self.snapshot(self.receipt_path)
            if value["publishedReceipt"]!=planned_receipt:
                raise ValueError("Network receipt publication differs from its durable intent")
        self.sync_dynamic_checkpoint(before,after)
        if self.generation.files()!=target:
            raise ValueError("Network publication final readback differs")
        value["phase"]="PUBLISHED"
        atomic_json(self.path,value)
        return self.response(value,"published",changed)

    def restore_file(self, path, expected):
        if self.snapshot(path)==expected:return
        content=self.contents(expected)
        if content is None:
            if path.exists() or path.is_symlink():
                self.read_bytes(str(path))
                path.unlink()
            return
        descriptor,temporary=tempfile.mkstemp(prefix=".network-source-",dir=path.parent)
        try:
            os.fchmod(descriptor,expected["mode"])
            with os.fdopen(descriptor,"wb")as handle:handle.write(content);handle.flush();os.fsync(handle.fileno())
            os.replace(temporary,path)
            directory=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try:os.fsync(directory)
            finally:os.close(directory)
        finally:
            if os.path.exists(temporary):os.unlink(temporary)

    def restore(self, request):
        self.state(request,("PREPARED","VERIFIED"))
        value=self.record()
        if (request.get("previousGeneration")!=value["sourceGeneration"]
                or request.get("configurationDesiredState")!=self.source):
            raise ValueError("Network restore request differs from exact frozen SOURCE")
        expected_nfs=request.get("expectedNfsDesiredState")
        if expected_nfs is None:expected_nfs=value["expectedNfsDesiredState"]
        expected_target=self.source
        if expected_nfs is not None:
            expected_target=self.expected_target({"expectedNfsDesiredState":expected_nfs},value)
        observed=self.generation.files()
        address_restored={**observed,self.NETWORK:self.source[self.NETWORK]}
        if address_restored not in (self.source,expected_target):
            raise ValueError("Network restore refuses unrelated protocol configuration drift")
        current=read_json(self.generation.config/self.NETWORK)
        if current not in (self.source[self.NETWORK],value["plannedNetwork"]):
            raise ValueError("Network restore refuses foreign address state")
        planned_snapshot=(self.encoded_snapshot(value["plannedNetwork"])
                          if value["plannedNetwork"]!=self.source[self.NETWORK] else value["sourceNetwork"])
        if self.snapshot(self.generation.config/self.NETWORK) not in (value["sourceNetwork"],planned_snapshot):
            raise ValueError("Network restore refuses raw address metadata drift")
        if value["phase"]=="PREPARING":
            raise ValueError("Network preparation outcome has not been verified")
        created=[{key:item for key,item in row.items()if key not in ("priorPresent","changed")}
                 for row in value["createdAddresses"]]
        remaining=[binding for binding in value["expectedBindings"]
                   if binding["listenIp"] not in {row["listenIp"]for row in created}]
        self.verify(remaining)
        receipt=self.snapshot(self.receipt_path)
        if receipt not in (value["sourceReceipt"],value["publishedReceipt"],value["plannedReceipt"]):
            raise ValueError("Network restore refuses a foreign NIC binding receipt")
        changed=self.snapshot(self.generation.config/self.NETWORK)!=value["sourceNetwork"]
        value["phase"]="RESTORING"
        atomic_json(self.path,value)
        self.restore_file(self.generation.config/self.NETWORK,value["sourceNetwork"])
        if receipt!=value["sourceReceipt"]:
            self.restore_file(self.receipt_path,value["sourceReceipt"])
        if created:
            if self.restore_addresses is None:raise ValueError("Owned address removal adapter is unavailable")
            self.restore_addresses(created)
        if expected_nfs is not None and address_restored==expected_target:
            before=self.digest(expected_target)
            after=self.digest({**expected_target,self.NETWORK:value["plannedNetwork"]})
            self.sync_dynamic_checkpoint(after,before)
        value["phase"]="RESTORED"
        atomic_json(self.path,value)
        return self.response(value,"sourceRestored",changed)

    def response(self,value,flag,changed):
        return {"success":True,flag:True,"scope":self.scope,"bootId":self.boot,"canonicalChanged":changed,
                "sourceConfigurationSha256":value["beforeSha256"],
                "targetConfigurationSha256":value["targetConfigurationSha256"],
                "endpoints":[] if flag=="sourceRestored" else [{**row,"active":True} for row in value["expectedBindings"]]}

