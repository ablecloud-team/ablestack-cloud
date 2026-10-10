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

"""Owned Ganesha 4.x DBus export updates with exact inventory readback."""
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

    def name(self,listen_ip,port):
        key=hashlib.sha256((listen_ip+":"+str(port)).encode()).hexdigest()[:24]
        return "org.ablestack.storage.ganesha.e"+key+".org.ganesha.nfsd"

    def owned_manager(self,name,pid):
        if not re.fullmatch(r"(?:org\.ganesha\.nfsd|org\.ablestack\.storage\.ganesha\.e[0-9a-f]{24}\.org\.ganesha\.nfsd)",name):
            raise ValueError("Ganesha bus name is outside its managed namespace")
        owner=self.bus.get_object('org.freedesktop.DBus','/org/freedesktop/DBus').get_dbus_method('GetConnectionUnixProcessID','org.freedesktop.DBus')
        if int(owner(name,timeout=self.budget()))!=pid:raise ValueError("Ganesha DBus owner differs from the owned endpoint process")
        return self.bus.get_object(name,EXPORT_PATH)

    def owned_endpoint_manager(self,listen_ip,port,pid):
        name=self.name(listen_ip,port)
        try:return name,self.owned_manager(name,pid)
        except Exception as current_error:
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
            for client_block in re.findall(r"CLIENT\s*\{([^{}]*)\}",block,re.S):
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
            exports.append({'exportId':identifier,'path':path,'pseudo':pseudo,'fsal':fsal,'clients':clients,
                            'mountPathPseudo':bool(re.search(r'(?i)mount_path_pseudo\s*=\s*true\s*;',content))})
        chunks.append(content[cursor:])
        # The managed DBus name is claimed at daemon startup. Updating only this
        # prefix does not alter NFS binding/global behavior of a running daemon;
        # the old owned bus is used until its next boot, then the new name.
        global_content=''.join(chunks)
        prefixes=re.findall(r'(?m)^[ \t]*Dbus_Name_Prefix[ \t]*=[ \t]*"([^"\n]+)"[ \t]*;[ \t]*$',global_content)
        if len(prefixes)>1 or any(not re.fullmatch(r'org\.ablestack\.storage\.ganesha\.e[0-9a-f]{24}',prefix) for prefix in prefixes):
            raise ValueError("Ganesha DBus prefix is outside the fixed endpoint namespace")
        global_content=re.sub(r'(?m)^[ \t]*Dbus_Name_Prefix[ \t]*=[ \t]*"[^"\n]+"[ \t]*;[ \t]*\n?', '', global_content)
        if len({row['exportId'] for row in exports})!=len(exports):raise ValueError("Fixed Ganesha export IDs duplicate")
        return {'exports':exports,'globalSha256':hashlib.sha256(global_content.strip().encode()).hexdigest()}

    def transition(self,name,pid,configuration,previous_content,target_content):
        before=self.configuration(previous_content);after=self.configuration(target_content)
        if before['globalSha256']!=after['globalSha256']:
            raise ValueError("NFS global/listener change requires explicit drain/recreate")
        return self.update(name,pid,configuration,before['exports'],after['exports'])

    def update(self,name,pid,configuration,previous,target):
        old={int(row['exportId']):row for row in previous};wanted={int(row['exportId']):row for row in target}
        if len(old)!=len(previous) or len(wanted)!=len(target):raise ValueError("Ganesha generation duplicates an export ID")
        for identifier in old.keys() & wanted.keys():
            if any(old[identifier].get(field)!=wanted[identifier].get(field) for field in ('path','pseudo','fsal')):
                raise ValueError("NFS immutable export binding change requires explicit drain/recreate")
        # Removal is a separate maintenance/drain operation until exact open
        # state is independently proved. Never silently tear down an export.
        if old.keys()-wanted.keys():raise ValueError("NFS export removal requires the explicit native drain adapter")
        manager=self.owned_manager(name,pid);self.verify(manager,previous)
        for identifier in sorted(wanted):
            self.call(manager,'UpdateExport',str(configuration),'EXPORT(Export_Id='+str(identifier)+')')
        self.verify(manager,target)
        # Owner PID must remain identical after all operations and readback.
        self.owned_manager(name,pid)
        return {'success':True,'activationStrategy':'DBUS_DYNAMIC_UPDATE_VERIFIED','processPid':pid,'exportsVerified':len(wanted),'restartPerformed':False}
