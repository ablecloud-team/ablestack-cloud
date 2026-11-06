#!/usr/bin/env python3
# #1451 disposable-client verifier. No Cloud API calls, disk formatting or credentials.
import argparse,hashlib,json,os,subprocess,socket,struct,random,xdrlib,errno
from pathlib import Path
def checked(argv):
    return subprocess.run(argv,capture_output=True,text=True,timeout=45,check=True).stdout.strip()
def credentials(uid):
    os.setgroups([uid]);os.setgid(uid);os.setuid(uid)
def as_uid(uid,code,*args):
    value=subprocess.run(['python3','-c',code,*map(str,args)],capture_output=True,text=True,
                         timeout=20,preexec_fn=lambda:credentials(uid))
    return {'rc':value.returncode,'stdout':value.stdout.strip(),'stderr':value.stderr.strip()[-512:]}
P=xdrlib.Packer
def uint(v):return struct.pack('>I',v)
def opaque(v):
    p=P();p.pack_opaque(v.encode() if isinstance(v,str) else v);return p.get_buffer()
def wire_attrs(server,port,export,name):
    c=P();c.pack_uint(1);c.pack_string(b'epic1451');c.pack_uint(1);c.pack_uint(1);c.pack_array([1],c.pack_uint)
    ops=[uint(24),uint(15)+opaque(export),uint(15)+opaque(name),uint(9)+uint(2)+uint(0)+uint(0x32)]
    xid=random.randint(1,2**30);body=opaque('identity')+uint(0)+uint(len(ops))+b''.join(ops)
    msg=b''.join([uint(xid),uint(0),uint(2),uint(100003),uint(4),uint(1),uint(1),opaque(c.get_buffer()),uint(0),uint(0),body])
    def recv(sock,n):
        data=b''
        while len(data)<n:
            chunk=sock.recv(n-len(data))
            if not chunk:raise RuntimeError('RPC EOF')
            data+=chunk
        return data
    with socket.create_connection((server,port),timeout=8) as sock:
        sock.sendall(uint(0x80000000|len(msg))+msg);raw=b''
        while True:
            marker=struct.unpack('>I',recv(sock,4))[0];raw+=recv(sock,marker&0x7fffffff)
            if marker&0x80000000:break
    u=xdrlib.Unpacker(raw);assert u.unpack_uint()==xid
    assert u.unpack_uint()==1 and u.unpack_uint()==0
    u.unpack_uint();u.unpack_opaque();assert u.unpack_uint()==0
    status=u.unpack_uint();u.unpack_opaque();count=u.unpack_uint();assert status==0,status
    attrs={}
    for _ in range(count):
        op=u.unpack_uint();error=u.unpack_uint();assert error==0,(op,error)
        if op==9:
            u.unpack_array(u.unpack_uint);a=xdrlib.Unpacker(u.unpack_opaque())
            attrs={'mode':oct(a.unpack_uint()),'owner':a.unpack_string().decode(),'group':a.unpack_string().decode()}
    return attrs
parser=argparse.ArgumentParser();parser.add_argument('command',choices=['prepare','probe'])
parser.add_argument('--mapping',choices=['Y','N'],default='Y')
parser.add_argument('--server',default='10.10.13.196');parser.add_argument('--port',type=int,default=2049)
parser.add_argument('--export',default='e1451');parser.add_argument('--client',choices=['c1','c2'],default='c1')
parser.add_argument('--identity',choices=['NAME_DOMAIN','NUMERIC'],default='NAME_DOMAIN')
parser.add_argument('--squash',choices=['ROOT','NONE','ALL'],default='ROOT')
parser.add_argument('--access',choices=['RW','RO'],default='RW');parser.add_argument('--version',choices=['3','4'],default='4')
args=parser.parse_args();assert os.geteuid()==0
if args.command=='prepare':
    checked(['modprobe','nfs'])
    Path('/sys/module/nfs/parameters/nfs4_disable_idmapping').write_text(args.mapping)
    print(json.dumps({'prepared':True,'mapping':Path('/sys/module/nfs/parameters/nfs4_disable_idmapping').read_text().strip(),
                      'domain':checked(['nfsidmap','-d']),'nss':checked(['getent','passwd','1'])}));raise SystemExit()
assert args.server in ('10.10.13.196','10.10.13.195') and args.port in (2049,2050)
assert args.export=='e1451'
mount=Path('/mnt/epic1451')/str(args.port);mount.mkdir(parents=True,exist_ok=True)
if subprocess.run(['mountpoint','-q',str(mount)]).returncode==0:checked(['umount',str(mount)])
checked(['mount','-t','nfs','-o',f'vers={args.version},sec=sys,proto=tcp,port={args.port},timeo=30,retrans=1,nosharecache,lookupcache=none,actimeo=0',f'{args.server}:/{args.export}',str(mount)])
result={'server':args.server,'port':args.port,'version':args.version,'client':args.client,'identity':args.identity,
        'squash':args.squash,'access':args.access,'mapping':Path('/sys/module/nfs/parameters/nfs4_disable_idmapping').read_text().strip(),'cases':[]}
seed=mount/'.e1451-seed.txt';expected=b'epic1451-shared-data-preserved'
if not seed.exists():
    assert args.access=='RW','seed must be created during explicit initial RW phase'
    seed.write_bytes(expected);seed.chmod(0o644)
assert seed.read_bytes()==expected
result['seedSha256']=hashlib.sha256(seed.read_bytes()).hexdigest()
for uid in (0,1,1001,65534):
    name=f'.e1451-{args.client}-{args.identity.lower()}-{args.squash.lower()}-{args.port}-{uid}.txt'
    path=mount/name
    read=as_uid(uid,"import pathlib,sys;assert pathlib.Path(sys.argv[1]).read_bytes()==b'epic1451-shared-data-preserved'",seed)
    assert read['rc']==0,('seed-read',uid,read)
    payload=f'epic1451-{args.client}-{uid}-{args.squash}'
    write=as_uid(uid,"import os,sys;f=os.open(sys.argv[1],os.O_WRONLY|os.O_CREAT|os.O_TRUNC|os.O_NOFOLLOW,0o640);os.write(f,sys.argv[2].encode());os.close(f)",path,payload)
    if args.access=='RO':
        assert write['rc']!=0 and 'Read-only file system' in write['stderr'],('RO-write',uid,write)
        result['cases'].append({'uid':uid,'read':True,'write':'EXPECTED_EROFS'});continue
    assert write['rc']==0,('RW-write',uid,write)
    own=as_uid(uid,"import pathlib,sys;assert pathlib.Path(sys.argv[1]).read_text()==sys.argv[2]",path,payload)
    assert own['rc']==0,('owner-read',uid,own)
    st=path.stat();attrs=wire_attrs(args.server,args.port,args.export,name)
    expected_uid=65534 if args.squash=='ALL' or args.squash=='ROOT' and uid==0 else uid
    if args.identity=='NUMERIC':
        assert attrs['owner']==str(expected_uid),(uid,attrs,expected_uid)
    if uid==1:
        other=as_uid(1001,"import pathlib,sys;pathlib.Path(sys.argv[1]).read_bytes()",path)
        assert (other['rc']==0)==(args.squash=='ALL'),('POSIX',args.squash,other)
    result['cases'].append({'uid':uid,'read':True,'write':True,'expectedServerUid':expected_uid,
                            'clientUid':st.st_uid,'clientGid':st.st_gid,'clientMode':oct(st.st_mode&0o7777),'wire':attrs,
                            'sha256':hashlib.sha256(payload.encode()).hexdigest()})
assert seed.read_bytes()==expected
checked(['umount',str(mount)])
out=Path('/tmp')/f'epic1451-{args.client}-{args.identity.lower()}-{args.squash.lower()}-{args.access.lower()}-{args.version}-{args.port}.json'
out.write_text(json.dumps(result,indent=2));print(json.dumps({'PASS':True,'result':str(out),**result},sort_keys=True))
