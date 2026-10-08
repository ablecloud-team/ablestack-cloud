#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Fresh ROOT primary-network attestation; writes one canonical row, moves no network."""
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import uuid


class RootNetworkBootstrap:
    def __init__(self,configuration=None,run=None):
        self.configuration=Path(configuration or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"))
        self.run=run or subprocess.run

    def observe(self,arguments):
        result=self.run(arguments,capture_output=True,text=True,timeout=5)
        if result.returncode or len(result.stdout)>1024*1024:raise ValueError("ROOT primary network observation is unavailable")
        return json.loads(result.stdout)

    def apply(self,request,maintenance,generation,rendered):
        keys={"instanceUuid","templateUpgradeUuid","operationUuid","revision"}
        if not isinstance(request,dict) or set(request)!=keys|{"sharedfsNetwork","primaryBinding"}:raise ValueError("ROOT network bootstrap requires its exact public fields")
        scope={key:str(uuid.UUID(request[key])) for key in keys-{"revision"}}
        if type(request.get("revision")) is not int or request["revision"]<1:raise ValueError("ROOT network bootstrap revision is invalid")
        scope["revision"]=request["revision"]
        if (maintenance.get("maintenanceKind") not in (None,"ROOT") or maintenance.get("bootHeld") is not True or maintenance.get("scope")!=scope
                or generation.get("generation") or generation.get("pendingOperationUuid") or rendered.get("current") is not None or rendered.get("bootHeld") is True):
            raise ValueError("ROOT primary bootstrap is only available on its protected fresh target")
        desired=generation.get("configurationDesiredState")
        if not isinstance(desired,dict) or set(desired)!={"desired-state/nfs-export-apply.json","desired-state/smb-share-apply.json","iscsi-targets.json","nvmeof-subsystems.json","posix-directory-policies.json","network-endpoints.json","sharedfs-network.json"} or any(value is not None for name,value in desired.items() if name!="sharedfs-network.json"):
            raise ValueError("ROOT primary bootstrap cannot seed protocols, aliases or POSIX state")
        declaration=request.get("sharedfsNetwork");binding=request.get("primaryBinding")
        if not isinstance(binding,dict) or set(binding)!={"macAddress","primaryIp","prefixlen","gateway"}:raise ValueError("ROOT bootstrap primary binding is incomplete")
        mac=str(binding["macAddress"]).lower();primary=str(ipaddress.IPv4Address(binding["primaryIp"]));prefix=binding["prefixlen"]
        if not re.fullmatch(r"[0-9a-f]{2}(?::[0-9a-f]{2}){5}",mac) or type(prefix) is not int or not 1<=prefix<=32:raise ValueError("ROOT primary binding MAC/prefix is invalid")
        gateway=str(ipaddress.IPv4Address(binding["gateway"])) if binding["gateway"] is not None else None
        if declaration is not None:
            if not isinstance(declaration,dict) or set(declaration)-{"macAddress","ipAddress","cidr","gateway","dns1","dns2"} or not {"macAddress","ipAddress","cidr"}<=set(declaration):
                raise ValueError("ROOT frozen primary declaration has unsupported fields")
            network=ipaddress.IPv4Network(declaration["cidr"],strict=False)
            if (str(declaration["macAddress"]).lower()!=mac or str(ipaddress.IPv4Address(declaration["ipAddress"]))!=primary or network.prefixlen!=prefix
                    or ipaddress.IPv4Address(primary) not in network or declaration.get("gateway")!=gateway):raise ValueError("ROOT source declaration differs from its exact NIC binding")
            if gateway is not None and ipaddress.IPv4Address(gateway) not in network:raise ValueError("ROOT gateway is outside its source subnet")
        links=self.observe(["ip","-j","link","show"]);matches=[item for item in links if str(item.get("address","")).lower()==mac and item.get("ifname")!="lo"]
        if len(matches)!=1:raise ValueError("ROOT primary MAC has no unique current interface")
        interface=matches[0]["ifname"]
        addresses=self.observe(["ip","-j","-4","addr","show","dev",interface,"scope","global"])
        actual={(item.get("local"),item.get("prefixlen")) for row in addresses for item in row.get("addr_info",[]) if item.get("family")=="inet" and item.get("scope")=="global"}
        if actual!={(primary,prefix)}:raise ValueError("ROOT target primary address/prefix differs; explicit VM bootstrap is required")
        routes=self.observe(["ip","-j","-4","route","show","default"])
        actual_routes=[item for item in routes if item.get("dev")==interface]
        if (gateway is None and actual_routes) or (gateway is not None and (len(actual_routes)!=1 or actual_routes[0].get("gateway")!=gateway)):
            raise ValueError("ROOT target default route differs from its exact source binding")
        if declaration is not None:
            dns=[str(ipaddress.IPv4Address(declaration[key])) for key in ("dns1","dns2") if declaration.get(key)]
            if dns:
                actual_dns=[]
                for line in Path("/etc/resolv.conf").read_text().splitlines():
                    fields=line.split()
                    if len(fields)==2 and fields[0]=="nameserver":actual_dns.append(str(ipaddress.ip_address(fields[1])))
                if actual_dns!=dns:raise ValueError("ROOT resolver differs from its frozen source declaration")
        path=self.configuration/"sharedfs-network.json";written=False
        if declaration is not None:
            self.configuration.mkdir(parents=True,mode=0o700,exist_ok=True);parent=self.configuration.lstat()
            if not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o022:raise ValueError("ROOT bootstrap canonical directory is not protected")
            existing=None
            if path.exists() or path.is_symlink():
                info=path.lstat()
                if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600:raise ValueError("ROOT bootstrap current primary row is not protected")
                descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
                try:
                    opened=os.fstat(descriptor)
                    if (opened.st_dev,opened.st_ino,opened.st_size)!=(info.st_dev,info.st_ino,info.st_size):raise ValueError("ROOT primary row changed while opening")
                    existing=json.loads(os.read(descriptor,1024*1024))
                finally:os.close(descriptor)
            if existing!=declaration:
                descriptor,temporary=tempfile.mkstemp(prefix=".root-primary-",dir=self.configuration)
                try:
                    os.fchmod(descriptor,0o600)
                    with os.fdopen(descriptor,"w") as handle:json.dump(declaration,handle,sort_keys=True,allow_nan=False);handle.flush();os.fsync(handle.fileno())
                    os.replace(temporary,path);directory=os.open(self.configuration,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
                    try:os.fsync(directory)
                    finally:os.close(directory)
                    written=True
                finally:
                    if os.path.exists(temporary):os.unlink(temporary)
        elif desired.get("sharedfs-network.json") is not None:raise ValueError("ROOT DHCP source cannot remove an existing static declaration")
        return {"success":True,"scope":scope,"primaryBindingVerified":True,"sharedfsNetwork":declaration,"canonicalPrimaryWritten":written,
                "networkChanged":False,"protocolDesiredStateChanged":False,"posixDesiredStateChanged":False,"interface":interface}
