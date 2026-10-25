"""#1448 design-only injected probes; no live network command or product patch."""
import builtins,copy,hashlib,ipaddress,json,os,subprocess,sys,tempfile,types
from pathlib import Path
from contextlib import redirect_stdout
import io
LIVE_HELPER=Path('/usr/local/sbin/ablestack-sharedfs-network')
def current_helper_probe(root, gateway='192.0.2.1', prefix=24, duplicate=False):
    net=root/'net';net.mkdir()
    (net/'ens8').mkdir();(net/'ens8/address').write_text('02:0d:00:cc:00:52')
    if duplicate:
        (net/'ens9').mkdir();(net/'ens9/address').write_text('02:0d:00:cc:00:52')
    state={'macAddress':'02:0d:00:cc:00:52','ipAddress':'192.0.2.10','cidr':'192.0.2.10/'+str(prefix),'dns1':'203.0.113.53'}
    if gateway is not None:state['gateway']=gateway
    p=root/'policy.json';p.write_text(json.dumps(state));p.chmod(0o600)
    resolv=root/'resolv';resolv.write_text('search example.invalid\noptions timeout:1\nnameserver 203.0.113.53\n')
    addresses=[{'local':'192.0.2.10','prefixlen':prefix},{'local':'192.0.2.99','prefixlen':prefix}]
    routes=[{'dst':'default','gateway':'192.0.2.1','dev':'ens8'},{'dst':'default','gateway':'198.51.100.1','dev':'ens9'}]
    calls=[]
    def run(args,**kwargs):
        nonlocal addresses,routes
        args=list(args);calls.append(args)
        out=''
        if args[:4]==['ip','-4','addr','flush']:addresses=[]
        elif args[:3]==['ip','addr','replace']:
            address=ipaddress.ip_interface(args[3]);addresses=[a for a in addresses if a['local']!=str(address.ip)]+[{'local':str(address.ip),'prefixlen':address.network.prefixlen}]
        elif args[:4]==['ip','route','del','default']:routes=[x for x in routes if x['dev']!=args[-1]]
        elif args[:4]==['ip','route','replace','default']:routes=[x for x in routes if x['dev']!=args[-1]]+[{'dst':'default','gateway':args[5],'dev':args[-1]}]
        elif args[:5]==['ip','-j','-4','addr','show']:out=json.dumps([{'ifname':'ens8','addr_info':addresses}])
        elif args[:5]==['ip','-j','-4','route','show']:out=json.dumps(routes)
        return subprocess.CompletedProcess(args,0,out,'')
    def path(value):
        value=str(value)
        if value=='/sys/class/net':return net
        if value=='/etc/resolv.conf':return resolv
        if value.startswith(str(root)):return Path(value)
        raise AssertionError('unapproved fixture path: '+value)
    native_import=builtins.__import__
    def importer(name,*args,**kwargs):
        if name=='pathlib':return types.SimpleNamespace(Path=path)
        if name=='subprocess':return types.SimpleNamespace(run=run,PIPE=subprocess.PIPE,DEVNULL=subprocess.DEVNULL)
        return native_import(name,*args,**kwargs)
    argv=sys.argv;sys.argv=[str(LIVE_HELPER),str(p)]
    error=None
    try:
        with redirect_stdout(io.StringIO()):exec(compile(LIVE_HELPER.read_text(),str(LIVE_HELPER),'exec'),{'__builtins__':dict(vars(builtins),__import__=importer)})
    except (ValueError,RuntimeError) as e:error=str(e)
    finally:sys.argv=argv
    return {'error':error,'calls':calls,'aliasPreserved':any(a['local']=='192.0.2.99' for a in addresses),
            'otherNicDefaultPreserved':any(x['dev']=='ens9' for x in routes),
            'dnsSearchOptionsPreserved':'search example.invalid' in resolv.read_text() and 'options timeout:1' in resolv.read_text(),
            'resolvedInterface':next((a[-1] for a in calls if a[:3]==['ip','addr','replace']),None)}

def proposed_plan(policy, observed):
    """Decision sketch only. Does not install a helper or execute an apply plan."""
    matches=[n for n in observed['links'] if n['mac'].lower()==policy['macAddress'].lower()]
    if len(matches)!=1:raise ValueError('NIC_IDENTITY_UNKNOWN')
    iface=matches[0]['name']
    if policy['mode']=='DHCP':return {'interface':iface,'commands':[],'addresses':copy.deepcopy(observed['addresses']),'ports':copy.deepcopy(observed['ports'])}
    net=ipaddress.ip_network(policy['cidr'],strict=False);ip=ipaddress.ip_address(policy['ipAddress'])
    # Match the existing server IPv4/prefix0..32 contract; host prefixes need consistent endpoint semantics.
    if net.version!=4 or ip.version!=4 or ip not in net or (net.prefixlen<=30 and ip in (net.network_address,net.broadcast_address)):raise ValueError('INVALID_STATIC_HOST')
    gateway=policy.get('gateway')
    if gateway:
        gw=ipaddress.ip_address(gateway)
        if gw.version!=4 or gw not in net or gw==ip or (net.prefixlen<=30 and gw in (net.network_address,net.broadcast_address)) or gw.is_multicast or gw.is_unspecified:raise ValueError('INVALID_GATEWAY')
    for key in ['dns1','dns2']:
        if policy.get(key) and ipaddress.ip_address(policy[key]).version!=4:raise ValueError('INVALID_DNS')
    commands=[]
    existing=[a for a in observed['addresses'] if a['ip']==str(ip) and a['interface']==iface and a['prefix']==net.prefixlen]
    if not existing:commands.append(['ip','addr','replace',str(ip)+'/'+str(net.prefixlen),'dev',iface])
    # Preserve all aliases, all non-default routes and other-NIC defaults.
    defaults=[a for a in observed['routes'] if a['dev']==iface and a['dst']=='default']
    if gateway and not any(a.get('gateway')==gateway for a in defaults):commands.append(['ip','route','replace','default','via',gateway,'dev',iface])
    if not gateway:
        for item in defaults:
            if item.get('owner')!='SHAREDFS':raise ValueError('DEFAULT_ROUTE_OWNER_UNKNOWN')
            commands.append(['ip','route','del','default','via',item['gateway'],'dev',iface])
    return {'interface':iface,'commands':commands,'addresses':copy.deepcopy(observed['addresses']),'ports':copy.deepcopy(observed['ports'])}

def plan_matrix():
    policy={'mode':'STATIC','macAddress':'02:0d:00:cc:00:52','ipAddress':'192.0.2.10','cidr':'192.0.2.0/24','gateway':'192.0.2.1'}
    data={'links':[{'name':'ens8','mac':policy['macAddress']}],'addresses':[{'interface':'ens8','ip':'192.0.2.10','prefix':24},{'interface':'ens8','ip':'192.0.2.99','prefix':24}],
          'routes':[{'dst':'default','gateway':'192.0.2.1','dev':'ens8','owner':'SHAREDFS'},{'dst':'default','gateway':'198.51.100.1','dev':'ens9','owner':'OTHER'},{'dst':'198.18.0.0/15','dev':'ens8','owner':'OTHER'}],
          'ports':{'NFS':2049,'SMB':445,'ISCSI':3260,'NVME_OF':4420}}
    out=[]
    p=proposed_plan(policy,data);assert p['commands']==[] and p['addresses']==data['addresses'] and p['ports']==data['ports'];out.append('STATIC_NOOP_PRESERVES_ALIASES_AND_PORTS')
    p=proposed_plan(dict(policy,gateway=None),data);assert len(p['commands'])==1 and '192.0.2.1' in p['commands'][0];out.append('NO_GATEWAY_REMOVES_OWN_DEFAULT_ONLY')
    p=proposed_plan(dict(policy,mode='DHCP'),data);assert p['commands']==[];out.append('DHCP_UNCHANGED')
    assert p['interface']=='ens8';out.append('NON_ETH0_RESOLVED_BY_MAC_WITHOUT_RENAME')
    for gw in ['192.0.2.10','192.0.2.0','192.0.2.255','198.51.100.1','::1']:
        try:proposed_plan(dict(policy,gateway=gw),data);raise AssertionError('invalid gateway accepted')
        except ValueError:out.append('REJECT_GATEWAY_'+gw)
    bad=copy.deepcopy(data);bad['links'].append({'name':'ens9','mac':policy['macAddress']})
    try:proposed_plan(policy,bad);raise AssertionError('ambiguous MAC accepted')
    except ValueError:out.append('AMBIGUOUS_MAC_BLOCKED')
    peer=dict(policy,ipAddress='192.0.2.11',cidr='192.0.2.10/31',gateway='192.0.2.10')
    proposed_plan(peer,data);out.append('PREFIX31_PEER_MATCHES_EXISTING_SERVER_CONTRACT')
    proposed_plan(dict(policy,cidr='192.0.2.10/32',gateway=None),data);out.append('PREFIX32_NO_GATEWAY_MATCHES_EXISTING_SERVER_CONTRACT')
    try:proposed_plan(dict(policy,cidr='192.0.2.10/33'),data);raise AssertionError('prefix33 accepted')
    except ValueError:out.append('PREFIX33_BLOCKED_BEFORE_MUTATION')
    return out

def dhcp_script_probe(root):
    # The actual installed script is copied only to redirect hooks/files into this private fixture.
    raw=Path('/sbin/dhclient-script').read_text()
    hook=root/'enter';hook.mkdir()
    script=root/'dhclient-script'
    raw=raw.replace('/etc/dhcp/dhclient-enter-hooks.d',str(hook)).replace('/etc/dhcp/dhclient-enter-hooks',str(root/'none-enter'))
    raw=raw.replace('/etc/dhcp/dhclient-exit-hooks.d',str(root/'none-exit-dir')).replace('/etc/dhcp/dhclient-exit-hooks',str(root/'none-exit'))
    raw=raw.replace('/etc/resolv.conf',str(root/'resolv'))
    raw=raw.replace('case \"$reason\" in', 'printf ORIGINAL_HANDLER_REACHED >> \"$TRACE\"; exit 0\ncase \"$reason\" in',1)
    script.write_text(raw)
    # An enter hook must exit the script, not merely return a nonzero status that the caller ignores.
    (hook/'ablestack-static-fixture').write_text('if [ "$STATIC_FIXTURE" = yes ] && [ "$interface" = ens8 ]; then exit 0; fi\n')
    helper=root/'bin';helper.mkdir()
    for cmd in ['ip','hostname','logger','run-parts']:
        p=helper/cmd
        if cmd=='run-parts':p.write_text('#!/bin/sh\n[ "$1" = --list ] && find "$2" -maxdepth 1 -type f 2>/dev/null\nexit 0\n')
        else:p.write_text('#!/bin/sh\nprintf "%s %s\\n" "'+cmd+'" "$*" >> "$TRACE"\n')
        p.chmod(0o755)
    results=[]
    for reason in ['PREINIT','BOUND','RENEW','REBIND','REBOOT','EXPIRE','FAIL','STOP']:
        trace=root/('trace-'+reason);env=dict(os.environ,PATH=str(helper)+':/usr/bin:/bin',TRACE=str(trace),reason=reason,interface='ens8',STATIC_FIXTURE='yes',
            new_ip_address='192.0.2.20',old_ip_address='192.0.2.10',new_subnet_mask='255.255.255.0')
        run=subprocess.run(['bash',str(script)],env=env,capture_output=True,text=True)
        assert run.returncode==0,(reason,run.stderr)
        calls=trace.read_text() if trace.exists() else ''
        assert 'ip ' not in calls,(reason,calls)
        results.append({'mode':'STATIC','reason':reason,'networkCommandCount':calls.count('ip ')})
    trace=root/'dhcp-normal';env=dict(os.environ,PATH=str(helper)+':/usr/bin:/bin',TRACE=str(trace),reason='EXPIRE',interface='ens8',STATIC_FIXTURE='no',old_ip_address='192.0.2.10',old_subnet_mask='255.255.255.0')
    run=subprocess.run(['bash',str(script)],env=env,capture_output=True,text=True)
    calls=trace.read_text() if trace.exists() else ''
    assert 'ORIGINAL_HANDLER_REACHED' in calls,('DHCP normal event did not proceed',run.stderr,calls)
    results.append({'mode':'DHCP','reason':'EXPIRE','originalHandlerReached':True,'handlerExecutionStoppedByFixture':True})
    return results

def boot_order_probe(root):
    text=Path('/usr/local/bin/ablestack-storage-boot-reconcile').read_text()
    for prefix in ['/run/lock','/run/ablestack-storage','/run/ganesha','/etc/ganesha']:
        text=text.replace(prefix,str(root/prefix.lstrip('/')))
    (root/'run/lock').mkdir(parents=True)
    script=root/'boot';script.write_text(text)
    bin=root/'bin';bin.mkdir()
    for name in ['mount','systemctl']:
        x=bin/name;x.write_text('\n'.join(['#!/bin/sh','exit 0','']));x.chmod(0o755)
    ctl=root/'storagectl'
    ctl.write_text('\n'.join(['#!/bin/sh','echo "$*" >> "$TRACE"','case "$1 $2 $3" in "network endpoints reconcile") exit 1;; esac','exit 0','']))
    ctl.chmod(0o755)
    nfs=root/'nfs.json';nfs.write_text('{}')
    env=dict(os.environ,PATH=str(bin)+':/usr/bin:/bin',TRACE=str(root/'calls'),
             ABLESTACK_STORAGECTL=str(ctl),ABLESTACK_STORAGE_MONITOR=str(root/'no-monitor'),
             ABLESTACK_STORAGE_DESIRED_STATE_DIR=str(root/'desired'),ABLESTACK_STORAGE_NFS_DESIRED_STATE_FILE=str(nfs),
             ABLESTACK_STORAGE_SMB_DESIRED_STATE_FILE=str(root/'no-smb'),ABLESTACK_STORAGE_ISCSI_DESIRED_STATE_FILE=str(root/'no-iscsi'),
             ABLESTACK_STORAGE_NVMEOF_DESIRED_STATE_FILE=str(root/'no-nvme'),ABLESTACK_STORAGE_BOOT_RECONCILE_LOG=str(root/'log'),
             ABLESTACK_STORAGE_BOOT_RECONCILE_ATTEMPTS='1',ABLESTACK_STORAGE_BOOT_RECONCILE_SLEEP='0')
    run=subprocess.run(['bash',str(script)],env=env,capture_output=True,text=True)
    calls=(root/'calls').read_text().splitlines()
    assert any(x.startswith('network endpoints reconcile') for x in calls)
    assert any(x.startswith('nfs export apply') for x in calls),calls
    gate=root/'gate'
    gate.write_text('\n'.join(['#!/bin/bash','set -e','"$ABLESTACK_STORAGECTL" network endpoints reconcile','"$ABLESTACK_STORAGECTL" nfs export apply "$ABLESTACK_STORAGE_NFS_DESIRED_STATE_FILE"','']))
    (root/'calls').write_text('')
    blocked=subprocess.run(['bash',str(gate)],env=env,capture_output=True,text=True)
    after=(root/'calls').read_text().splitlines()
    assert blocked.returncode!=0 and not any(x.startswith('nfs export apply') for x in after)
    return {'currentExitCode':run.returncode,'currentCallsAfterNetworkFailure':calls,
            'gateSketchExitCode':blocked.returncode,'gateSketchProtocolCalls':0,
            'liveBootOrServiceExecuted':False}

with tempfile.TemporaryDirectory(prefix='epic1448-private-') as temp:
    root=Path(temp);result={'kind':'design-only actual-SystemVM isolated injection','liveNetworkCommandsExecuted':False,'productionFilesPatched':False,'liveMacChanged':False,
        'helperSha256':hashlib.sha256(LIVE_HELPER.read_bytes()).hexdigest(),'currentHelper':{},'candidateSketchIsProduct':False}
    for name,args in [('same-static',{}),('no-gateway',{'gateway':None}),('self-gateway',{'gateway':'192.0.2.10'}),('network-gateway',{'gateway':'192.0.2.0'}),('broadcast-gateway',{'gateway':'192.0.2.255'}),('ambiguous-mac',{'duplicate':True}),('prefix-31',{'prefix':31,'gateway':None}),('prefix-32',{'prefix':32,'gateway':None})]:
        p=root/name;p.mkdir();result['currentHelper'][name]=current_helper_probe(p,**args)
    result['candidatePlanCases']=plan_matrix()
    p=root/'dhcp';p.mkdir();result['actualDhclientHookSketch']=dhcp_script_probe(p)
    p=root/'boot-order';p.mkdir();result['bootOrdering']=boot_order_probe(p)
    result['privateFixtureRemovedOnExit']=True
    print(json.dumps(result))
