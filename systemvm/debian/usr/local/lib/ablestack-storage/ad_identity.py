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

"""Bounded AD input and principal mapping helpers; no implicit trust attestation."""
import fcntl
import hashlib
import json
from pathlib import Path
import stat
import uuid
import os
import ipaddress
import re
import subprocess
import signal
import time


def bounded_ad_run(arguments, capture_output=True, text=True, timeout=5, pass_fds=()):
    """Exact child termination; never wait without a timeout after a deadline."""
    if not callable(getattr(os, "pidfd_open", None)) or not callable(getattr(signal, "pidfd_send_signal", None)):
        raise ValueError("AD operations require exact pidfd child control")
    until = time.monotonic() + timeout
    inherited = (9,) if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") == "9" else ()
    child = subprocess.Popen(arguments, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             text=text, env=dict(os.environ,LC_ALL="C"), pass_fds=tuple(set((*inherited, *pass_fds))))
    descriptor = None
    try:
        try: descriptor = os.pidfd_open(child.pid, 0)
        except ProcessLookupError:
            if child.poll() is None: raise ValueError("AD child identity is unavailable")
        try:
            output, error = child.communicate(timeout=max(.001, until-time.monotonic()))
        except subprocess.TimeoutExpired:
            if descriptor is not None:
                try: signal.pidfd_send_signal(descriptor, signal.SIGTERM, None, 0)
                except ProcessLookupError: pass
            try: child.wait(timeout=.2)
            except subprocess.TimeoutExpired:
                if descriptor is not None:
                    try: signal.pidfd_send_signal(descriptor, signal.SIGKILL, None, 0)
                    except ProcessLookupError: pass
                try: child.wait(timeout=.2)
                except subprocess.TimeoutExpired: pass
            raise TimeoutError("AD operation deadline exceeded; trust remains unverified")
        if len(output) > 256*1024 or len(error) > 256*1024:
            raise ValueError("AD public metadata response is oversized")
        return subprocess.CompletedProcess(arguments, child.returncode, output, error)
    finally:
        if descriptor is not None: os.close(descriptor)
        for stream in (child.stdout, child.stderr):
            if stream is not None: stream.close()


def domain_name(value):
    if not isinstance(value,str) or len(value)>253 or value!=value.strip() or '.' not in value:
        raise ValueError("AD DNS domain is invalid")
    labels=value.split('.')
    if any(not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?",label) for label in labels):
        raise ValueError("AD DNS domain contains an invalid label")
    return value.lower()


def dns_addresses(value):
    if value in (None,'',[]):return []
    values=re.split(r"[,\s]+",value) if isinstance(value,str) else value
    if not isinstance(values,list) or len(values)>16:raise ValueError("AD DNS server list is invalid")
    result=[]
    for item in values:
        if not item:continue
        address=str(ipaddress.ip_address(item))
        if address not in result:result.append(address)
    return result


def ad_principal(value,domain,workgroup):
    domain=domain_name(domain)
    if not isinstance(value,str) or not value or len(value)>256 or any(ord(char)<32 for char in value):
        raise ValueError("AD principal is invalid")
    if chr(92) in value:
        pieces=value.split(chr(92))
        if len(pieces)!=2 or pieces[0].upper()!=workgroup.upper():raise ValueError("AD principal belongs to another domain")
        name=pieces[1]
    elif '@' in value:
        pieces=value.split('@')
        if len(pieces)!=2 or domain_name(pieces[1])!=domain:raise ValueError("AD UPN belongs to another realm")
        name=pieces[0]
    else:name=value
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_. -]{0,127}",name) or name!=name.strip():
        raise ValueError("AD account name is invalid")
    return workgroup.upper()+chr(92)+name


def sid(value):
    if not re.fullmatch(r"S-1-5(?:-[0-9]{1,10}){3,15}",str(value or '')):
        raise ValueError("AD SID is invalid")
    return value


def resolve_ad_principal(kind,principal,domain,workgroup,run=None,deadline=None,expected_domain_sid=None):
    if kind not in ('AD_USER','AD_GROUP'):raise ValueError("Unknown AD POSIX principal kind")
    name=ad_principal(principal,domain,workgroup)
    until=deadline or time.monotonic()+10
    def command(args):
        remaining=until-time.monotonic()
        if remaining<=0:raise TimeoutError("AD principal lookup deadline expired")
        result=(run or bounded_ad_run)(args,capture_output=True,text=True,timeout=min(5,remaining))
        if result.returncode:raise ValueError("AD principal mapping is unavailable")
        return result.stdout.strip()
    output=command(['wbinfo','--name-to-sid',name])
    matched=re.fullmatch(r"(S-1-5(?:-[0-9]{1,10}){3,15})\s+([A-Z_]+)\s+\(([124])\)",output)
    allowed={('SID_USER','1')} if kind=='AD_USER' else {('SID_DOM_GRP','2'),('SID_ALIAS','4')}
    if not matched or (matched.group(2),matched.group(3)) not in allowed:
        raise ValueError("AD principal SID kind differs")
    identity=sid(matched.group(1))
    if expected_domain_sid is not None:
        expected_domain_sid=sid(expected_domain_sid)
        if not re.fullmatch(r"S-1-5-21(?:-[0-9]{1,10}){3}",expected_domain_sid) or not identity.startswith(expected_domain_sid+"-"):
            raise ValueError("AD principal SID differs from its verified joined domain")
    option='--sid-to-uid' if kind=='AD_USER' else '--sid-to-gid'
    numeric=command(['wbinfo',option,identity])
    if not numeric.isdigit() or not 1000<=int(numeric)<=2147483647 or int(numeric)==65534:
        raise ValueError("AD principal maps to a protected or invalid numeric identity")
    inverse='--uid-to-sid' if kind=='AD_USER' else '--gid-to-sid'
    if command(['wbinfo',inverse,numeric])!=identity:
        raise ValueError("AD SID/numeric identity reverse mapping differs")
    return {'principal':name,'sid':identity,'kind':'u' if kind=='AD_USER' else 'g','numericId':int(numeric),'sidType':int(matched.group(3)),'sidTypeName':matched.group(2),'mappingVerified':True}


def reject_local_ad_collision(kind, numeric, account_root=None):
    # NSS also contains winbind entries. Only the protected local account file
    # can establish a collision with an unrelated Unix managed identity.
    path=Path(account_root or "/etc")/("passwd" if kind=="AD_USER" else "group")
    info=path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022 or info.st_size>8*1024*1024:
        raise ValueError("Local account collision authority is unprotected")
    descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        opened=os.fstat(descriptor)
        if (opened.st_dev,opened.st_ino,opened.st_size)!=(info.st_dev,info.st_ino,info.st_size):raise ValueError("Local account authority changed")
        with os.fdopen(descriptor,"r",closefd=False) as handle:content=handle.read(8*1024*1024+1)
        after=os.fstat(descriptor);named=path.lstat()
        fields=("st_dev","st_ino","st_size","st_uid","st_gid","st_mode","st_mtime_ns","st_ctime_ns")
        if len(content)>8*1024*1024 or any(getattr(opened,key)!=getattr(after,key) or getattr(opened,key)!=getattr(named,key) for key in fields):
            raise ValueError("Local account authority changed while reading")
        for line in content.splitlines():
            fields=line.split(":")
            if len(fields)<3 or not fields[2].isdigit():raise ValueError("Local account authority is malformed")
            if int(fields[2])==numeric:raise ValueError("AD numeric mapping collides with a local managed account")
    finally:os.close(descriptor)


def machine_sids(output):
    """Read public SID metadata only; local machine SID and AD domain SID differ."""
    if not isinstance(output,str) or len(output)>65536:raise ValueError("AD SID metadata is oversized")
    local=re.findall(r"(?m)^SID for local machine [^:\n]+ is:\s*(S-[0-9-]+)\s*$",output)
    domain=re.findall(r"(?m)^SID for domain [^:\n]+ is:\s*(S-[0-9-]+)\s*$",output)
    if len(local)!=1 or len(domain)!=1:raise ValueError("AD machine/domain SID metadata is ambiguous")
    return {"machineSid":sid(local[0]),"domainSid":sid(domain[0])}


def service_principal(value,domain):
    if not isinstance(value,str) or len(value)>512 or value!=value.strip() or any(ord(char)<32 for char in value):
        raise ValueError("AD service principal is invalid")
    service,separator,hostname=value.partition('/')
    if not separator or service.lower() not in ("host","cifs") or '@' in hostname or ':' in hostname:
        raise ValueError("AD service principal has an unsupported service binding")
    hostname=domain_name(hostname);domain=domain_name(domain)
    if not hostname.endswith('.'+domain):raise ValueError("AD service principal hostname is outside its joined domain")
    return service.lower()+"/"+hostname


def keytab_principals(output,domain,required_spns,machine_name=None):
    """klist -k metadata only; never use -K or return key material."""
    if not isinstance(output,str) or len(output)>256*1024:raise ValueError("AD keytab metadata is oversized")
    realm=domain_name(domain).upper();wanted={service_principal(value,domain) for value in required_spns};seen=set()
    owned_short = {item.split("/", 1)[1].split(".", 1)[0].upper() for item in wanted}
    if machine_name is not None:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,14}", machine_name): raise ValueError("AD keytab machine name is invalid")
        account_names = {machine_name.upper()}
    else: account_names = owned_short
    for line in output.splitlines():
        matched=re.fullmatch(r"\s*([0-9]+)\s+([^\s]+)\s*",line)
        if not matched:continue
        principal=matched[2];name,separator,actual_realm=principal.rpartition('@')
        if not separator or actual_realm!=realm:raise ValueError("AD keytab contains a foreign realm")
        if name.endswith('$'):
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,15}\$",name) or name[:-1].upper() not in account_names:raise ValueError("AD keytab machine account is invalid")
            continue
        if "/" in name and name.split("/",1)[0].lower()=="restrictedkrbhost":
            host=name.split("/",1)[1]
            if "." in host:
                if "host/"+host.lower() not in wanted:raise ValueError("AD restricted HOST principal belongs to another machine")
            elif host.upper() not in owned_short:
                raise ValueError("AD restricted short HOST principal belongs to another machine")
            continue
        if "/" in name and "." not in name.split("/", 1)[1]:
            service, host = name.split("/", 1)
            if service.lower() not in ("host", "cifs") or host.upper() not in owned_short:
                raise ValueError("AD short service principal belongs to another machine")
            continue
        seen.add(service_principal(name,domain))
    if not wanted<=seen:raise ValueError("AD keytab lacks an exact required CIFS/HOST principal")
    return {"realm":realm,"servicePrincipals":sorted(seen),"requiredServicePrincipalsVerified":True}


class AdIdentityProbe:
    def __init__(self,run=None,deadline=None):
        self.run=run or bounded_ad_run;self.deadline=deadline or time.monotonic()+20

    def command(self,arguments):
        remaining=self.deadline-time.monotonic()
        if remaining<=0:raise TimeoutError("AD identity probe deadline expired")
        result=self.run(arguments,capture_output=True,text=True,timeout=min(5,remaining))
        if result.returncode:raise ValueError("AD identity trust or public metadata probe failed")
        return result.stdout

    def verify(self,domain,required_spns,expected_sids=None,machine_name=None):
        domain=domain_name(domain)
        self.command(["net","ads","testjoin","--machine-pass"])
        observed=machine_sids(self.command(["net","getdomainsid"]))
        if expected_sids is not None and observed!=expected_sids:raise ValueError("AD machine/domain SID differs from the protected source identity")
        keytab=keytab_principals(self.command(["klist","-k","/etc/krb5.keytab"]),domain,required_spns,machine_name)
        return {**observed,**keytab,"domain":domain,"trustVerified":True,"identityVerified":True}


def ad_idmap_configuration(workgroup,value=None):
    if not isinstance(workgroup,str) or not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{0,14}",workgroup):
        raise ValueError("AD idmap workgroup is invalid")
    if value is None:
        value={"default":{"backend":"tdb","range":[10000,60000]},"domain":{"backend":"rid","range":[1000000,1999999],"baseRid":0}}
    if not isinstance(value,dict) or set(value)!={"default","domain"}:raise ValueError("AD idmap configuration requires exact default/domain policies")
    ranges=[];lines=[]
    for key,name in (("default","*"),("domain",workgroup)):
        row=value[key]
        if (not isinstance(row,dict) or row.get("backend") not in ("tdb","rid")
                or set(row)!=({"backend","range","baseRid"} if row["backend"]=="rid" else {"backend","range"})):
            raise ValueError("AD idmap backend fields are unsupported")
        limits=row["range"]
        if (not isinstance(limits,list) or len(limits)!=2 or any(type(item) is not int for item in limits)
                or not 1000<=limits[0]<=limits[1]<=2147483647 or limits[0]<=65534<=limits[1]):
            raise ValueError("AD idmap configuration includes a protected or invalid identity range")
        if row["backend"]=="rid" and (type(row["baseRid"]) is not int or not 0<=row["baseRid"]<=2147483647):
            raise ValueError("AD idmap RID base is invalid")
        ranges.append(limits);lines.extend(["   idmap config "+name+" : backend = "+row["backend"],
                                          "   idmap config "+name+" : range = "+str(limits[0])+"-"+str(limits[1])])
        if row["backend"]=="rid":lines.append("   idmap config "+name+" : base_rid = "+str(row["baseRid"]))
    if max(row[0] for row in ranges)<=min(row[1] for row in ranges):raise ValueError("AD default/domain idmap configurations overlap")
    return {"policy":value,"lines":lines}


def ad_configuration(domain,workgroup,netbios,existing_smb,dns_servers,idmap_configuration=None):
    """Pure validated domain config; existing shares and private passdb settings remain."""
    domain=domain_name(domain)
    if not isinstance(workgroup,str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,14}",workgroup):raise ValueError("AD workgroup is invalid")
    if not isinstance(netbios,str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,14}",netbios):raise ValueError("AD NetBIOS name is invalid")
    dns=dns_addresses(dns_servers)
    if not dns:raise ValueError("AD join requires explicit validated DNS server addresses")
    if not isinstance(existing_smb,str) or len(existing_smb)>8*1024*1024:raise ValueError("AD source Samba config is invalid")
    sections=[];global_lines=[];current=None
    owned={'workgroup','realm','security','kerberos method','netbios name','winbind use default domain','winbind enum users','winbind enum groups','idmap config * : backend','idmap config * : range'}
    for line in existing_smb.splitlines():
        matched=re.fullmatch(r"\s*\[([^\]]+)\]\s*",line)
        if matched:current=matched[1].lower()
        if current=='global':
            if matched:continue
            if '=' in line and (line.split('=',1)[0].strip().lower() in owned or line.split('=',1)[0].strip().lower().startswith('idmap config '+workgroup.lower()+' :')):continue
            global_lines.append(line)
        elif current is not None:sections.append(line)
        elif line.strip() and not line.lstrip().startswith(('#',';')):raise ValueError("AD Samba source has content before a section")
    mapping=ad_idmap_configuration(workgroup.upper(),idmap_configuration)
    lines=['[global]',*global_lines,'   workgroup = '+workgroup.upper(),'   realm = '+domain.upper(),'   security = ADS',
           '   kerberos method = secrets and keytab','   netbios name = '+netbios.upper(),'   winbind use default domain = no',
           '   winbind enum users = no','   winbind enum groups = no',*mapping['lines']]
    krb='[libdefaults]\n default_realm = '+domain.upper()+'\n dns_lookup_realm = false\n dns_lookup_kdc = true\n rdns = false\n'
    resolver='search '+domain+'\n'+''.join('nameserver '+address+'\n' for address in dns)
    return {'smbConfiguration':'\n'.join([*lines,*sections])+'\n','kerberosConfiguration':krb,'resolverConfiguration':resolver,
            'domain':domain,'realm':domain.upper(),'workgroup':workgroup.upper(),'netbiosName':netbios.upper(),'dnsServers':dns,'idmapPolicy':mapping['policy']}

def credential_command(arguments,username,password,domain,run=None,deadline=None):
    """Samba auth file is a sealed memfd; no credential argv/disk/cache file."""
    domain=domain_name(domain)
    if not isinstance(username,str) or not re.fullmatch(r"[A-Za-z0-9_.@\\-]{1,256}",username) or any(ord(char)<32 for char in username):
        raise ValueError("AD join credential username is invalid")
    if not isinstance(password,str) or not password or len(password)>4096 or any(char in password for char in ("\n","\r","\0")):
        raise ValueError("AD join credential password cannot be encoded in its protected transport")
    until=deadline or time.monotonic()+120
    remaining=until-time.monotonic()
    if remaining<=0:raise TimeoutError("AD credential operation deadline expired")
    descriptor=os.memfd_create("storage-ad-credentials",os.MFD_CLOEXEC|os.MFD_ALLOW_SEALING)
    inherited=(9,) if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")=="9" else ()
    try:
        os.fchmod(descriptor,0o600)
        encoded=("username = "+username+"\npassword = "+password+"\ndomain = "+domain+"\n").encode()
        os.write(descriptor,encoded);os.lseek(descriptor,0,os.SEEK_SET)
        fcntl.fcntl(descriptor,fcntl.F_ADD_SEALS,fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL)
        command=[*arguments,"--authentication-file=/proc/self/fd/"+str(descriptor),"--use-kerberos=required","--use-krb5-ccache=MEMORY:ablestack-ad"]
        result=(run or bounded_ad_run)(command,capture_output=True,text=True,timeout=min(120,remaining),pass_fds=(*inherited,descriptor))
        if result.returncode:raise ValueError("Protected AD credential operation failed; private diagnostic omitted")
        return {"success":True,"protectedCredentialTransport":True}
    finally:os.close(descriptor)

def idmap_policy(run=None,workgroup=None,deadline=None):
    if not isinstance(workgroup,str) or not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{0,14}",workgroup):
        raise ValueError("AD idmap workgroup is invalid")
    until=deadline or time.monotonic()+10
    def parameter(name):
        remaining=until-time.monotonic()
        if remaining<=0:raise TimeoutError("AD idmap probe deadline expired")
        result=(run or bounded_ad_run)(["testparm","-s","--parameter-name="+name],capture_output=True,text=True,timeout=min(3,remaining))
        if result.returncode:raise ValueError("AD idmap parameter is unavailable")
        return result.stdout.strip()
    result={}
    for label,scope in (("default","*"),("domain",workgroup)):
        backend=parameter("idmap config "+scope+" : backend")
        if backend not in ("tdb","rid"):raise ValueError("AD idmap backend cannot be preserved by this runtime")
        value=parameter("idmap config "+scope+" : range")
        match=re.fullmatch(r"([0-9]+)\s*-\s*([0-9]+)",value)
        if not match or not 1000<=int(match[1])<=int(match[2])<=2147483647 or int(match[1])<=65534<=int(match[2]):
            raise ValueError("AD idmap range overlaps a protected numeric identity")
        result[label]={"backend":backend,"range":[int(match[1]),int(match[2])]}
        if backend=="rid":
            base=parameter("idmap config "+scope+" : base_rid") or "0"
            if not base.isdigit() or int(base)>2147483647:raise ValueError("AD RID base is invalid")
            result[label]["baseRid"]=int(base)
    low,high=result["default"]["range"];other_low,other_high=result["domain"]["range"]
    if max(low,other_low)<=min(high,other_high):raise ValueError("AD domain/default idmap ranges overlap")
    return result


def collect_joined_ad_identity(domain_state,run=None,deadline=None):
    if not isinstance(domain_state,dict) or str(domain_state.get("joinState") or domain_state.get("state") or "").upper()!="JOINED":
        raise ValueError("AD source is not a protected joined identity")
    config=domain_state.get("config") or {};domain=domain_name(domain_state.get("domainName"))
    workgroup=str(domain_state.get("workgroup") or config.get("workgroup") or "").upper()
    netbios=str(domain_state.get("netbiosName") or config.get("netbiosName") or "").upper()
    if any(not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{0,14}",item) for item in (workgroup,netbios)):
        raise ValueError("AD joined source lacks its exact machine/workgroup identity")
    required=domain_state.get("servicePrincipals")
    if not isinstance(required,list) or not required or len(required)>128:
        raise ValueError("AD source lacks its verified explicit CIFS/HOST aliases")
    until=deadline or time.monotonic()+30
    probe=AdIdentityProbe(run,until).verify(domain,required)
    mapping=idmap_policy(run,workgroup,until)
    return {"schemaVersion":1,"domain":domain,"realm":domain.upper(),"workgroup":workgroup,"netbiosName":netbios,
            "machineSid":probe["machineSid"],"domainSid":probe["domainSid"],"servicePrincipals":probe["servicePrincipals"],
            "trustVerified":True,"idmapPolicy":mapping}


def ad_protected_json(path,optional=False):
    path=Path(path)
    if optional and not path.exists() and not path.is_symlink():return None
    for parent in (path.parent,path):
        info=parent.lstat()
        if info.st_uid!=os.geteuid() or info.st_mode&0o022:raise ValueError("AD metadata is not protected")
        if parent==path and (not stat.S_ISREG(info.st_mode) or info.st_size>1024*1024):raise ValueError("AD metadata is not a bounded regular file")
        if parent!=path and not stat.S_ISDIR(info.st_mode):raise ValueError("AD metadata directory is not regular")
    descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        opened=os.fstat(descriptor)
        if (opened.st_dev,opened.st_ino,opened.st_size)!=(info.st_dev,info.st_ino,info.st_size):raise ValueError("AD metadata changed while opening")
        return json.loads(os.read(descriptor,1024*1024+1))
    finally:os.close(descriptor)


def machine_account_sid(output,domain_sid):
    match=re.fullmatch(r"(S-1-5(?:-[0-9]{1,10}){3,15})\s+SID_USER\s+\(1\)",output.strip())
    if not match or not match[1].startswith(sid(domain_sid)+"-"):raise ValueError("AD computer account SID belongs to another domain/type")
    return sid(match[1])


def dns_aliases(value,domain):
    if not isinstance(value,list) or not value or len(value)>64:raise ValueError("AD identity lacks its exact DNS aliases")
    result=[];seen=set()
    for row in value:
        if not isinstance(row,dict) or set(row)!={"hostname","addresses"}:raise ValueError("AD DNS alias has unknown fields")
        hostname=domain_name(row["hostname"])
        if not hostname.endswith("."+domain_name(domain)) or hostname in seen:raise ValueError("AD DNS alias is foreign or duplicated")
        addresses=dns_addresses(row["addresses"])
        if not addresses or any(ipaddress.ip_address(item).version!=4 for item in addresses):raise ValueError("AD DNS alias lacks exact supported IPv4 endpoints")
        result.append({"hostname":hostname,"addresses":sorted(addresses)});seen.add(hostname)
    return sorted(result,key=lambda row:row["hostname"])


class AdIdentityRpc:
    def __init__(self,run=None,configuration=None,generation=None,clock=None,cli=None,account_root=None):
        self.run=run or bounded_ad_run;self.deadline=time.monotonic()+45
        self.configuration=Path(configuration or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"))
        self.generations=Path(generation or os.environ.get("ABLESTACK_STORAGE_GENERATION_DIR","/var/lib/ablestack-storage/config-generations"))
        self.account_root=account_root;self.clock=clock or time.time;self.cli=str(cli or "/usr/local/bin/ablestack-storagectl");self.config_override=None

    def scope(self,request):
        scope={key:str(uuid.UUID(request[key])) for key in ("instanceUuid","operationUuid")}
        if type(request.get("revision")) is not int or request["revision"]<1:raise ValueError("AD RPC revision is invalid")
        scope["revision"]=request["revision"]
        current=ad_protected_json(self.generations/"current.json")
        pending=ad_protected_json(self.generations/"pending.json",True)
        if current.get("instanceUuid")!=scope["instanceUuid"] or type(current.get("revision")) is not int or current["revision"]>scope["revision"]:
            raise ValueError("AD RPC native instance/revision differs")
        if pending and any(pending.get(key)!=value for key,value in scope.items()):raise ValueError("Foreign pending generation blocks AD attestation")
        return scope

    def configured_run(self,args,**kwargs):
        if self.config_override and args[0] in ("net","testparm"):
            args=[args[0],"--configfile="+self.config_override,*args[1:]]
        return self.run(args,**kwargs)

    def command(self,args):
        remaining=self.deadline-time.monotonic()
        if remaining<=0:raise TimeoutError("AD RPC total deadline expired")
        result=self.configured_run(args,capture_output=True,text=True,timeout=min(5,remaining))
        if result.returncode:raise ValueError("AD public identity attestation is unavailable")
        return result.stdout.strip()

    def inspect(self,request):
        scope=self.scope(request);state=ad_protected_json(self.configuration/"smb-domain.json",True)
        if state is None or str(state.get("joinState") or state.get("state") or "").upper()!="JOINED":
            return {"success":True,"scope":scope,"sideEffects":False,"joinState":"NOT_JOINED","trustVerified":False,"identityVerified":False,"adIdentity":False}
        if state.get("instanceUuid")!=scope["instanceUuid"]:raise ValueError("AD joined receipt belongs to another native instance")
        config=state.get("config") or {};domain=domain_name(state.get("domainName"));realm=domain.upper()
        machine_config=self.configuration/"ad-machine.conf"
        if state.get("machineConfigurationSha256") is not None:
            info=machine_config.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600 or info.st_size>8*1024*1024:
                raise ValueError("AD private machine configuration is not protected")
            descriptor=os.open(machine_config,os.O_RDONLY|os.O_NOFOLLOW)
            try:
                opened=os.fstat(descriptor)
                if (opened.st_dev,opened.st_ino,opened.st_size)!=(info.st_dev,info.st_ino,info.st_size):raise ValueError("AD machine configuration changed")
                content=os.read(descriptor,8*1024*1024+1)
            finally:os.close(descriptor)
            if hashlib.sha256(content).hexdigest()!=state["machineConfigurationSha256"]:raise ValueError("AD machine configuration differs from its joined receipt")
            self.config_override=str(machine_config)

        workgroup=str(state.get("workgroup") or config.get("workgroup") or "").upper()
        netbios=str(state.get("netbiosName") or config.get("netbiosName") or "").upper()
        if any(not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{0,14}",item) for item in (workgroup,netbios)):raise ValueError("AD joined state lacks its exact workgroup/machine name")
        required=state.get("servicePrincipals")
        if not isinstance(required,list) or not required:raise ValueError("AD joined state lacks its explicit service principals")
        aliases=dns_aliases(state.get("dnsAliases"),domain)
        expected=state.get("identityReceipt")
        if not isinstance(expected,dict) or set(expected)!={"machineSid","domainSid","machineAccountSid"}:raise ValueError("AD joined state lacks its protected SID receipt")
        observed=AdIdentityProbe(self.configured_run,self.deadline).verify(domain,required,{key:expected[key] for key in ("machineSid","domainSid")},netbios)
        account=machine_account_sid(self.command(["wbinfo","--name-to-sid",workgroup+chr(92)+netbios+"$"]),observed["domainSid"])
        if account!=expected["machineAccountSid"]:raise ValueError("AD computer account differs from the protected joined receipt")
        listed=self.command(["net","ads","setspn","list","--machine-pass"])
        spns={line.strip().lower() for line in listed.splitlines() if line.strip().lower().startswith(("cifs/","host/"))}
        if not {service_principal(item,domain) for item in required}<=spns:raise ValueError("AD computer account lacks its required exact SPNs")
        servers=dns_addresses(state.get("dnsServers") or config.get("dnsServers"))
        if not servers:raise ValueError("AD joined state lacks its validated DNS servers")
        for alias in aliases:
            for server in servers:
                output=self.command(["dig","+short","+time=2","+tries=1","A",alias["hostname"],"@"+server])
                actual=sorted({str(ipaddress.IPv4Address(line)) for line in output.splitlines() if line.strip()})
                if actual!=alias["addresses"]:raise ValueError("AD DNS alias resolution differs from its exact endpoints")
        mapping=idmap_policy(self.configured_run,workgroup,self.deadline)
        if state.get("idmapPolicy")!=mapping:raise ValueError("AD idmap policy differs from its protected joined receipt")
        if request.get("expectedRealm") not in (None,realm) or request.get("expectedDomainSid") not in (None,observed["domainSid"]):raise ValueError("AD caller realm/domain SID differs")
        return {"success":True,"scope":scope,"sideEffects":False,"joinState":"JOINED","domain":domain,"realm":realm,"workgroup":workgroup,"netbiosName":netbios,
                **observed,"machineAccountSid":account,"dnsAliases":aliases,"dnsAliasesVerified":True,"adSpnsVerified":True,"idmapPolicy":mapping,
                "bootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),"generatedEpoch":self.clock(),"adIdentity":True}

    def resolve(self,request):
        if not request.get("expectedRealm") or not request.get("expectedDomainSid"):raise ValueError("AD mapping requires pinned realm and domain SID")
        joined=self.inspect(request)
        if joined.get("joinState")!="JOINED" or joined.get("identityVerified") is not True:raise ValueError("AD mapping requires a freshly verified joined identity")
        kind=request.get("principalType");resolved=resolve_ad_principal(kind,request.get("principal"),joined["domain"],joined["workgroup"],self.configured_run,self.deadline,joined["domainSid"])
        limits=joined["idmapPolicy"]["domain"]["range"]
        if not limits[0]<=resolved["numericId"]<=limits[1]:raise ValueError("AD principal numeric identity differs from its joined domain idmap range")
        reject_local_ad_collision(kind,resolved["numericId"],self.account_root)
        return {"success":True,"scope":joined["scope"],"sideEffects":False,"qualifiedName":resolved["principal"],"sid":resolved["sid"],
                "principalType":kind,"sidType":resolved["sidType"],"sidTypeName":resolved["sidTypeName"],"numericId":resolved["numericId"],"kind":resolved["kind"],"mappingVerified":True,"reverseVerified":True,
                **{key:joined[key] for key in ("domainSid","realm","workgroup","bootId","generatedEpoch")}}
