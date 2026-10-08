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

"""Pure protocol configuration rendering using fixed helpers from signed storagectl."""
import ast
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import tempfile
import zlib

NFS_HELPERS = {'assign_ganesha_export_ids','effective_nfs_export_config','endpoint_key','export_id','ganesha_access','ganesha_bind_addr','ganesha_client_list','ganesha_clients','ganesha_raw_literal','ganesha_squash','nested_export_filesystem_id','nfs_export_listener_ports','nfs_export_endpoint_ips','nfs_export_principal','nfs_protocols_for_mode','quote_ganesha','render_ganesha_export','safe_export_root_name','truth','write_ganesha_configs','int_config'}
SMB_HELPERS = {'local_or_ad_name','safe_netbios_name','safe_share_name','samba_principal','smb_conf_quote','smb_creation_lines','smb_creation_policy','smb_effective_read_only','smb_group','smb_hosts_allow_lines','smb_identity','smb_inheritance_lines','smb_inheritance_policy','smb_network_policy','smb_user','truth'}


def fixed_render_helpers(cli, marker, allowed):
    block = next(item for item in re.findall(r"<<'PY'\n(.*?)\nPY", cli.read_text(), re.S) if marker in item)
    definitions = [node for node in ast.parse(block).body if isinstance(node, ast.FunctionDef) and node.name in allowed]
    if {node.name for node in definitions} != allowed:
        raise ValueError('Signed renderer helpers differ from the fixed contract')
    namespace = {'os':os,'re':re,'ipaddress':ipaddress,'json':json,'hashlib':hashlib,'zlib':zlib}
    for node in ast.parse(block).body:
        if isinstance(node,ast.Assign) and len(node.targets)==1 and isinstance(node.targets[0],ast.Name) and node.targets[0].id in ('GANESHA_EXPORT_ID_MIN','GANESHA_EXPORT_ID_MAX'):
            namespace[node.targets[0].id]=ast.literal_eval(node.value)
    exec(compile(ast.Module(body=definitions,type_ignores=[]),str(cli),'exec'),namespace)
    return namespace


def render_nfs_candidate(payload, cli):
    namespace=fixed_render_helpers(cli,'def write_ganesha_configs(',NFS_HELPERS)
    namespace['id_mapping_mode']=str(payload.get('idMappingMode') or 'NAME_DOMAIN').upper()
    if namespace['id_mapping_mode'] not in ('NAME_DOMAIN','NUMERIC'):
        raise ValueError('Unsupported NFS owner mapping mode')
    service_mode=str(payload.get('protocolMode') or 'V4_ONLY').upper()
    if service_mode not in ('V4_ONLY','V3V4_DUAL'):raise ValueError('Unsupported NFS protocol mode')
    default_port=int(payload.get('port') or 2049)
    endpoint_exports={};aliases={};files={};enabled=bool(payload.get('enabled',True))
    for export in payload.get('exports') or []:
        export_uuid=str(export['uuid'])
        if not re.fullmatch('[0-9a-f-]{36}',export_uuid):raise ValueError('NFS render needs a canonical export UUID')
        files['nfs/exports/'+export_uuid+'.exports']=''
        if not enabled or export.get('state','Ready') in ('Disabled','Destroyed','Error'):continue
        config=namespace['effective_nfs_export_config'](export.get('config') or {})
        exposed=str(export.get('path') or '').rstrip('/')
        root_name=namespace['safe_export_root_name'](export.get('exportPath') or export.get('name'),exposed)
        if exposed!='/export/'+root_name:raise ValueError('NFS render alias differs from its fixed export path')
        backing=str(config.get('backingPath') or '').rstrip('/');volume=str(config.get('volumeMountPath') or '').rstrip('/')
        if not backing or not volume or not (backing==volume or backing.startswith(volume+'/')):
            raise ValueError('NFS render backing path escapes its selected volume')
        aliases[export_uuid]={'aliasPath':exposed,'backingPath':backing,'volumeMountPath':volume}
        clients=[];seen=set()
        for acl in export.get('acls') or []:
            if acl.get('state','Ready') in ('Disabled','Destroyed','Error') or not acl.get('principal'):continue
            principal=namespace['nfs_export_principal'](acl['principal'])
            if principal in seen:continue
            seen.add(principal);entry=acl.get('config') or {};all_squash=bool(entry.get('allSquash',config.get('allSquash',False)))
            clients.append({'clients':namespace['ganesha_clients'](acl['principal']),
                            'access':namespace['ganesha_access'](acl.get('permission','READ_ONLY'),bool(config.get('readOnly',False))),
                            'squash':namespace['ganesha_squash'](all_squash,bool(entry.get('rootSquash',config.get('rootSquash',True)))),
                            'anonUid':namespace['int_config'](entry,'anonUid') if entry.get('anonUid') is not None else namespace['int_config'](config,'anonUid'),
                            'anonGid':namespace['int_config'](entry,'anonGid') if entry.get('anonGid') is not None else namespace['int_config'](config,'anonGid')})
        if not clients:
            clients=[{'clients':'*','access':namespace['ganesha_access']('READ_WRITE',bool(config.get('readOnly',False))),
                      'squash':namespace['ganesha_squash'](bool(config.get('allSquash',False)),bool(config.get('rootSquash',True))),
                      'anonUid':namespace['int_config'](config,'anonUid'),'anonGid':namespace['int_config'](config,'anonGid'),'implicit':True}]
        rendered={'uuid':export_uuid,'name':export.get('name'),'path':exposed,'pseudo':'/'+root_name,
                  'filesystemId':namespace['nested_export_filesystem_id'](export_uuid) if config.get('relativeSharePath') else None,
                  'protocolMode':service_mode,'anonUid':namespace['int_config'](config,'anonUid'),'anonGid':namespace['int_config'](config,'anonGid'),'clients':clients}
        for ip in namespace['nfs_export_endpoint_ips'](config,str(payload.get('listenIp') or '0.0.0.0')):
            ip=str(ipaddress.IPv4Address(ip))
            for port in namespace['nfs_export_listener_ports'](config,default_port,service_mode):
                endpoint_exports.setdefault((ip,int(port)),[]).append(dict(rendered))
    if service_mode=='V3V4_DUAL':
        merged={export['uuid']:export for rows in endpoint_exports.values() for export in rows}
        endpoint_exports={('0.0.0.0',2049):list(merged.values())} if merged else {}
    for ip,port in endpoint_exports:
        if ip=='0.0.0.0' and any(other!='0.0.0.0' and other_port==port for other,other_port in endpoint_exports):
            raise ValueError('NFS wildcard and selected endpoint sockets overlap')
    # Existing configuration text serializers run only inside this private scratch
    # directory. No alias, mount, filesystem metadata, network or service changes.
    with tempfile.TemporaryDirectory(prefix='storage-render-nfs-') as directory:
        namespace['ganesha_conf_dir']=directory
        configs=namespace['write_ganesha_configs'](endpoint_exports,default_port)
        for key,path,ip,port,mode in configs:
            stable=hashlib.sha256((ip+':'+str(port)).encode()).hexdigest()[:24]
            files['nfs/ganesha/'+stable+'.conf']=Path(path).read_text()
    files['nfs/manifest.json']=json.dumps({'enabled':enabled,'aliases':aliases,'endpoints':[{'listenIp':ip,'port':port,'mode':mode,'legacyUnitKey':key,'configurationPath':'nfs/ganesha/'+hashlib.sha256((ip+':'+str(port)).encode()).hexdigest()[:24]+'.conf'} for key,path,ip,port,mode in configs]},sort_keys=True)
    return files

def render_smb_candidate(payload, cli, credential_refs=None):
    namespace=fixed_render_helpers(cli,'def samba_principal(',SMB_HELPERS | {'derive_ad_workgroup'})
    identity=payload.get('identityDomain') or {};identity_config=identity.get('config') or {}
    ad_joined=bool(identity.get('domainName') and identity.get('joinState')=='JOINED')
    workgroup=namespace['derive_ad_workgroup'](identity.get('domainName'),identity_config.get('workgroup'))
    netbios=namespace['safe_netbios_name'](payload.get('netbiosName') or identity_config.get('netbiosName'),payload.get('instanceUuid'))
    listeners=[] if payload.get('enabled') is False else (payload.get('listeners') or [{'listenIp':payload.get('listenIp') or '0.0.0.0','port':payload.get('port') or 445}])
    rows=[]
    for item in listeners:
        ip=str(ipaddress.ip_address(item.get('listenIp') or '0.0.0.0'));port=int(item.get('port') or 445)
        if not 1<=port<=65535:raise ValueError('SMB render port is invalid')
        if (ip,port) not in rows:rows.append((ip,port))
    if any(ip in ('0.0.0.0','::') and any(other_ip!=ip and other_port==port for other_ip,other_port in rows) for ip,port in rows):
        raise ValueError('SMB wildcard/specific acceptor overlap requires maintenance')
    ports=sorted({port for ip,port in rows})
    lines=['[global]','   server string = ABLESTACK Storage Service','   netbios name = '+netbios,
           '   smb ports = '+' '.join(str(port) for port in ports),'   load printers = no','   printing = bsd',
           '   disable spoolss = yes','   map to guest = Bad User','   passdb backend = tdbsam']
    if rows and not any(ip in ('0.0.0.0','::') for ip,port in rows):
        lines+=['   interfaces = lo '+' '.join(dict.fromkeys(ip for ip,port in rows)),'   bind interfaces only = yes']
    lines+=['   workgroup = '+workgroup]
    if ad_joined:
        realm=str(identity['domainName']).upper()
        if not re.fullmatch('[A-Z0-9.-]+',realm):raise ValueError('AD rendered realm is invalid')
        lines+=['   realm = '+realm,'   security = ADS','   kerberos method = secrets and keytab','   winbind use default domain = yes','   winbind enum users = yes','   winbind enum groups = yes','   idmap config * : backend = tdb','   idmap config * : range = 10000-999999']
    else:
        lines+=['   security = user']
    files={};manifest_shares=[]
    for share in payload.get('shares') or []:
        if payload.get('enabled') is False or share.get('state','Ready') in ('Disabled','Destroyed','Error'):continue
        name=namespace['safe_share_name'](share.get('name') or share['uuid']);config=share.get('config') or {}
        path=str(share.get('path') or config.get('backingPath') or '')
        if path.startswith('/export/') and config.get('backingPath'):path=str(config['backingPath'])
        if not path or not path.startswith('/srv/ablestack-storage/volumes/'):
            raise ValueError('SMB rendered path needs its explicit managed backing volume')
        active=[acl for acl in share.get('acls') or [] if acl.get('principal') and acl.get('state','Ready') not in ('Disabled','Destroyed','Error')]
        if any(acl.get('principalType') not in ('LOCAL_USER','LOCAL_GROUP','AD_USER','AD_GROUP') for acl in active):
            raise ValueError('Rendered account ACL principal type is unsupported')
        if not ad_joined and any(acl.get('principalType') in ('AD_USER','AD_GROUP') for acl in active):
            raise ValueError('AD rendered account ACL requires a verified joined identity')
        principals=[namespace['samba_principal'](acl['principalType'],acl['principal'],workgroup,netbios) for acl in active]
        writable=[namespace['samba_principal'](acl['principalType'],acl['principal'],workgroup,netbios) for acl in active if acl.get('permission') in ('READ_WRITE','ADMIN') and not config.get('readOnly',False)]
        administrators=[namespace['samba_principal'](acl['principalType'],acl['principal'],workgroup,netbios) for acl in active if acl.get('permission')=='ADMIN']
        creation=namespace['smb_creation_policy'](config);inheritance=namespace['smb_inheritance_policy'](config,active)
        lines+=['','['+name+']','   path = '+path,
                '   read only = '+('yes' if namespace['smb_effective_read_only'](config,len(active),bool(config.get('guestOk',False))) else 'no'),
                '   browseable = '+('yes' if config.get('browseable',True) else 'no'),
                '   guest ok = '+('yes' if config.get('guestOk',False) else 'no')]
        lines+=namespace['smb_creation_lines'](creation)+namespace['smb_inheritance_lines'](inheritance)
        forced=None
        if str(config.get('posixOwnershipMode') or 'AUTHENTICATED_USER').upper()=='FORCED_UID_GID':
            uid,gid=config.get('ownerUid'),config.get('ownerGid');token=re.sub('[^a-fA-F0-9]','',str(share['uuid']))[:20].lower()
            if (isinstance(uid,bool) or isinstance(gid,bool) or not isinstance(uid,int) or not isinstance(gid,int)
                    or uid<10000 or gid<10000 or uid==65534 or gid==65534 or uid>2147483647 or gid>2147483647
                    or len(token)!=20 or not config.get('posixPolicyUuid') or config.get('guestOk') or administrators):
                raise ValueError('SMB forced identity is not bound to its explicit common POSIX policy')
            forced={'managedUser':'sf_u_'+token,'managedGroup':'sf_g_'+token,'ownerUid':uid,'ownerGid':gid}
            lines+=['   force user = '+forced['managedUser'],'   force group = '+forced['managedGroup']]
        policy=namespace['smb_network_policy'](share.get('networkAcls') or []);lines+=namespace['smb_hosts_allow_lines'](policy)
        if principals:lines+=['   valid users = '+' '.join(principals)]
        if writable:lines+=['   write list = '+' '.join(writable)]
        if administrators:lines+=['   admin users = '+' '.join(administrators)]
        manifest_shares.append({'uuid':share['uuid'],'shareName':name,'path':path,'forcedIdentity':forced,
                                'creationPolicy':creation,'inheritance':inheritance,'networkPolicy':policy,
                                'accountAcls':[{key:acl.get(key) for key in ('uuid','principalType','principal','permission')} for acl in active],
                                'credentialRefs':(credential_refs or {}).get(share['uuid'],{})})
    for ip,port in rows:
        key=hashlib.sha256((ip+':'+str(port)).encode()).hexdigest()[:24]
        files['smb/listeners/'+key+'.json']=json.dumps({'listenIp':ip,'port':port},sort_keys=True)
    files['smb/smb.conf']='\n'.join(lines)+'\n'
    files['smb/manifest.json']=json.dumps({'enabled':bool(payload.get('enabled',True)),'netbiosName':netbios,'shares':manifest_shares,'listeners':[{'listenIp':ip,'port':port} for ip,port in rows]},sort_keys=True)
    return files

def render_block_candidate(payload, protocol, resolve_device, credential_refs=None):
    if protocol not in ('ISCSI','NVMEOF'):raise ValueError('Unknown block protocol renderer')
    collection='targets' if protocol=='ISCSI' else 'subsystems';default_port=3260 if protocol=='ISCSI' else 4420
    listeners=payload.get('listeners') or [{'listenIp':payload.get('listenIp') or '0.0.0.0','port':payload.get('port') or default_port}]
    normalized=[]
    for item in listeners:
        ip=str(ipaddress.ip_address(item.get('listenIp') or '0.0.0.0'));port=int(item.get('port') or default_port)
        if not 1<=port<=65535:raise ValueError('Block renderer port is invalid')
        if {'listenIp':ip,'port':port} not in normalized:normalized.append({'listenIp':ip,'port':port})
    plans=[];resources=[] if payload.get('enabled') is False else payload.get(collection) or []
    for resource in resources:
        if resource.get('state','Ready') in ('Disabled','Destroyed','Error'):continue
        target=str(resource.get('targetName') or '')
        prefix='iqn' if protocol=='ISCSI' else 'nqn'
        if not re.fullmatch(prefix+r'\.[0-9]{4}-[0-9]{2}\.[A-Za-z0-9.-]+:[A-Za-z0-9_.:-]+',target) or '.local.storage:' not in target:
            raise ValueError('Block rendered target name is not a bounded IQN/NQN')
        items=[resource] if protocol=='ISCSI' else resource.get('namespaces') or []
        rendered=[]
        for item in items:
            if item.get('state','Ready') in ('Disabled','Destroyed','Error'):continue
            observed=resolve_device(item)
            if (observed.get('matchedBy')!='VOLUME_SERIAL' or not observed.get('serial') or observed.get('rootDevice')
                    or observed.get('mounted') or int(observed.get('sizeBytes') or 0)!=int(item.get('volumeSizeBytes') or 0)):
                raise ValueError('Rendered block mapping is not its exact unmounted DATA volume')
            number=int(item.get('lunOrNamespace') or (0 if protocol=='ISCSI' else 1))
            if number<0 or number>65535 or (protocol=='NVMEOF' and number==0):raise ValueError('Rendered LUN/NSID is invalid')
            rendered.append({'uuid':item['uuid'],'volumeUuid':item['volumeUuid'],'number':number,
                             'devicePath':observed['devicePath'],'serial':observed['serial'],'sizeBytes':observed['sizeBytes']})
        acls=[]
        for acl in resource.get('acls' if protocol=='ISCSI' else 'hosts') or []:
            if acl.get('state','Ready') in ('Disabled','Destroyed','Error'):continue
            principal=str(acl.get('principal') or '')
            if not re.fullmatch(prefix+r'\.[0-9]{4}-[0-9]{2}\.[A-Za-z0-9.-]+:[A-Za-z0-9_.:-]+',principal):
                raise ValueError('Rendered initiator identity is invalid')
            config=acl.get('config') or {}
            fields=('chapEnabled','mutualChapEnabled','chapUsername','mutualChapUsername') if protocol=='ISCSI' else ('dhChapEnabled','dhChapCtrlEnabled')
            refs=(credential_refs or {}).get(target+'|'+principal)
            if any(config.get(name) for name in ('chapEnabled','mutualChapEnabled','dhChapEnabled','dhChapCtrlEnabled')) and not refs:
                raise ValueError('Authenticated block plan lacks its durable credential reference')
            acls.append({'uuid':acl.get('uuid'),'principal':principal,'config':{key:config[key] for key in fields if key in config},'credentialRef':refs})
        requested=set()
        for item in items:
            config=item.get('config') or {}
            selected=item.get('listenerPorts') or config.get('listenerGroupPorts') or config.get('listenerPorts')
            if selected is not None:
                if not isinstance(selected,list) or any(isinstance(port,bool) or not isinstance(port,int) for port in selected):
                    raise ValueError('Block listener group is invalid')
                requested.update(selected)
        selected_listeners=[item for item in normalized if not requested or item['port'] in requested]
        if not selected_listeners or requested-set(item['port'] for item in normalized):
            raise ValueError('Block listener group refers to an unavailable endpoint')
        allow_any=bool((resource.get('config') or {}).get('allowAnyHost'))
        if allow_any and acls:raise ValueError('Unrestricted block host access conflicts with explicit authenticated host bindings')
        existing=next((item for item in plans if item['targetName']==target),None)
        if existing:
            if existing['allowAnyHost']!=allow_any:raise ValueError('Merged block target host policy differs')
            if {item['number'] for item in existing['backstores']} & {item['number'] for item in rendered}:
                raise ValueError('Merged target duplicates a LUN/namespace number')
            existing['backstores'].extend(rendered)
            for acl in acls:
                old=next((item for item in existing['acls'] if item['principal']==acl['principal']),None)
                if old and old!=acl:raise ValueError('Merged target has conflicting credential/access bindings')
                if not old:existing['acls'].append(acl)
            for endpoint in selected_listeners:
                if endpoint not in existing['listeners']:existing['listeners'].append(endpoint)
        else:
            plans.append({'uuid':resource['uuid'],'targetName':target,'backstores':rendered,'acls':acls,
                          'allowAnyHost':allow_any,'listeners':selected_listeners})
    path='block/iscsi-plan.json' if protocol=='ISCSI' else 'block/nvmeof-plan.json'
    return {path:json.dumps({'schemaVersion':1,'enabled':bool(payload.get('enabled',True)),'protocol':protocol,'targets':plans},sort_keys=True)}
