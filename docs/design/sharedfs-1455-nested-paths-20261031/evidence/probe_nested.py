#!/usr/bin/env python3
# Design-only SystemVM injection. No Mold API/DB writes and no installed code replacement.
import sys,os,json,subprocess,signal,hashlib,stat,uuid,traceback
from pathlib import Path
from types import SimpleNamespace
sys.path.insert(0,str(Path(__file__).parent))
from probe_base import Probe,command,metadata

def parts(value):
    if not isinstance(value,str) or not value or len(value)>255 or value.startswith('/') or '\\' in value:
        raise ValueError('RELATIVE_PATH_INVALID')
    result=value.split('/')
    if any(p in ('','.','..') or any(ord(c)<32 or ord(c)==127 for c in p) or len(p.encode())>255 for p in result):
        raise ValueError('RELATIVE_PATH_INVALID')
    return result

def mount_id(fd):
    return int(next(line.split(':')[1] for line in Path('/proc/self/fdinfo/'+str(fd)).read_text().splitlines() if line.startswith('mnt_id:')))

def opened(root,relative,expected=None):
    path=parts(relative);fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    device=os.fstat(fd).st_dev;mount=mount_id(fd)
    try:
        for name in path:
            child=os.open(name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
            os.close(fd);fd=child
            if os.fstat(fd).st_dev!=device or mount_id(fd)!=mount:
                raise ValueError('MOUNT_BOUNDARY')
        if expected is not None and os.fstat(fd).st_ino!=expected:
            raise ValueError('PATH_IDENTITY_CHANGED')
        return fd
    except BaseException:
        os.close(fd);raise

def descendants(parent,rows):
    return [r for r in rows if r['volume']==parent['volume'] and len(r['parts'])>len(parent['parts'])
            and r['parts'][:len(parent['parts'])]==parent['parts']]


def create_chain(root,relative):
    fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    device=os.fstat(fd).st_dev;mid=mount_id(fd);created=[];walk=[]
    try:
        for name in parts(relative):
            walk.append(name)
            try:child=os.open(name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
            except FileNotFoundError:
                os.mkdir(name,0o755,dir_fd=fd)
                child=os.open(name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
                created.append({'relative':'/'.join(walk),'inode':os.fstat(child).st_ino})
            os.close(fd);fd=child
            if os.fstat(fd).st_dev!=device or mount_id(fd)!=mid:raise ValueError('MOUNT_BOUNDARY')
        return created
    finally:os.close(fd)

def rollback_created(root,created):
    retained=[]
    for row in reversed(created):
        path=parts(row['relative']);parent='/'.join(path[:-1])
        fd=opened(root,parent) if parent else os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:
            if os.stat(path[-1],dir_fd=fd,follow_symlinks=False).st_ino!=row['inode']:raise ValueError('PATH_IDENTITY_CHANGED')
            try:os.rmdir(path[-1],dir_fd=fd)
            except OSError as e:
                import errno
                if e.errno not in (errno.ENOTEMPTY,errno.EEXIST):raise
                retained.append(row['relative'])
        finally:os.close(fd)
    return retained

CLIENT=r'''
import subprocess,sys,json,hashlib,os
from pathlib import Path
mount,port,export,operation=Path(sys.argv[1]),sys.argv[2],sys.argv[3],sys.argv[4]
mount.mkdir(exist_ok=True)
subprocess.run(['mount','--make-rprivate','/'],check=True)
subprocess.run(['mount','-t','nfs4','-o','vers=4.1,sec=sys,port='+port+',soft,timeo=5,retrans=2','127.0.0.1:/'+export,str(mount)],check=True,capture_output=True,timeout=20)
try:
 os.setgroups([65534]);os.setegid(65534);os.seteuid(65534)
 value=(mount/'marker.txt').read_text()
 if operation=='write':(mount/'nfs-created.txt').write_text('native-nfs-write')
 print(json.dumps({'export':export,'port':port,'marker':value,'write':operation=='write'}))
finally:
 os.seteuid(0);os.setegid(0)
 subprocess.run(['umount',str(mount)],check=True,capture_output=True,timeout=10)
'''

class NestedProbe(Probe):
    def protected(self):
        value=super().protected()
        for p in [Path('/etc/passwd'),Path('/etc/group'),Path('/etc/shadow'),Path('/etc/gshadow')]:
            value.setdefault('accounts',{})[str(p)]=hashlib.sha256(p.read_bytes()).hexdigest()
        sys.path.insert(0,str(Path('/usr/local/bin/ablestack-storagectl').resolve().parent/'lib/ablestack-storage'))
        from configuration_transaction import SmbListenerProfiles
        listener=SmbListenerProfiles(); manifest=json.loads(listener.manifest.read_text())
        value['managedSmbPids']={k:listener.pid(v) for k,v in manifest['profiles'].items() if v.get('kind')!='covered'}
        value['desired']={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in Path('/etc/ablestack-storage/desired-state').rglob('*') if p.is_file()}
        for base in [Path('/export/common1453r5'),Path('/export/ad1453r5')]:
            for p in [base,*sorted(base.rglob('*'))]:
                s=p.stat();value.setdefault('objects',{})[str(p)]={'uid':s.st_uid,'gid':s.st_gid,'mode':s.st_mode&0o7777,'inode':s.st_ino,'device':s.st_dev,
                    'acl':command(['getfacl','-cpn',str(p)]),**({'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} if p.is_file() else {})}
        return value

    def stop_private(self):
        for process in self.children:
            if process.poll() is None:
                os.killpg(process.pid,signal.SIGTERM);process.wait(timeout=8)

    def setup(self):
        conf,other=super().setup()
        self.stop_private()
        self.child=self.shared/'team-a';self.sibling=self.shared/'team-b';self.outside=self.data/'outside-parent'
        for path,marker in [(self.shared,'parent-original'),(self.child,'child-original'),(self.sibling,'sibling-original'),(self.outside,'outside-probe')]:
            path.mkdir(exist_ok=True);os.chown(path,65534,65534);path.chmod(0o770)
            f=path/'marker.txt';f.write_text(marker);os.chown(f,65534,65534);f.chmod(0o660)
        self.child_inode=self.child.stat().st_ino
        self.anchors={'parent':self.alias}
        for name,source in [('child',self.child),('sibling',self.sibling)]:
            alias=self.control/(name+'-anchor');alias.mkdir()
            fd=opened(self.volume,str(source.relative_to(self.volume)))
            try:subprocess.run(['mount','--bind','/proc/self/fd/'+str(fd),str(alias)],pass_fds=(fd,),check=True)
            finally:os.close(fd)
            self.bound.append(alias);self.anchors[name]=alias
        self.render_nfs()
        smb=self.control/'smb.conf';global_part=smb.read_text().split('[common]',1)[0]
        self.global_part=global_part
        self.render_smb()
        self.start_private()
        return conf,other

    def render_nfs(self,child=True):
        for port,cfg in self.configs.items():
            head=cfg.read_text().split('EXPORT {',1)[0]
            entries=[]
            for index,(name,path) in enumerate(self.anchors.items()):
                if name=='child' and not child:continue
                entries.append('EXPORT { Export_Id='+str(1451+index)+'; Path='+json.dumps(str(path))+'; Pseudo="/'+name+'"; Protocols=4; SecType=sys; FSAL { Name=VFS; } CLIENT { Clients=127.0.0.1; Access_Type=RW; Squash=Root_Squash; } }')
            cfg.write_text(head+'\n'.join(entries)+'\n')

    def render_smb(self,child=True):
        entries=[]
        for name,path in self.anchors.items():
            if name=='child' and not child:continue
            entries.append('['+name+']\npath='+str(path)+'\nread only=no\nguest ok=yes\ncreate mask=0660\ndirectory mask=0770\n')
        entries.append('[rawchild]\npath='+str(self.child)+'\nread only=no\nguest ok=yes\n')
        (self.control/'smb.conf').write_text(self.global_part+'\n'+'\n'.join(entries))
        command(['testparm','-s',str(self.control/'smb.conf')])

    def start_private(self):
        for port in self.configs:self.start_nfs(port)
        out=(self.control/'nested-smb.log').open('a');self.logs.append(out)
        self.smb_process=subprocess.Popen(['ip','netns','exec',self.ns,'smbd','--foreground','--no-process-group','--configfile',str(self.control/'smb.conf')],stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
        self.children.append(self.smb_process);self.wait(23045)

    def smb(self,share,action):
        return subprocess.run(['ip','netns','exec',self.ns,'smbclient','//127.0.0.1/'+share,'-p','23045','-N','-c',action],
                              capture_output=True,text=True,timeout=20)

    def smb_marker(self,share):
        output=self.control/('read-'+uuid.uuid4().hex)
        q=self.smb(share,'get marker.txt '+str(output))
        return {'returncode':q.returncode,'marker':output.read_text() if output.exists() else None}

    def nfs(self,name,port=23049,write=False):
        out=command(['ip','netns','exec',self.ns,'unshare','--mount','--propagation','private','python3','-c',CLIENT,
                     str(self.control/('client-'+name)),str(port),name,'write' if write else 'read'],timeout=45)
        return json.loads(out)

    def reject(self,name,operation):
        try:operation()
        except (ValueError,OSError) as error:self.record(name,code=str(error).split(':')[0]);return
        raise AssertionError('Expected path guard rejection: '+name)

    def exercise(self,conf,other):
        self.record('mounted SPARSE DATA identity',volumeUuid=self.volume.name,filesystem=command(['findmnt','-n','-M',str(self.volume),'-o','UUID,FSTYPE']).strip())
        for bad in ['/absolute','../outside','a/../b','a//b','a/./b','a\\b','', '.', 'a\ncontrol']:
            self.reject('invalid path '+repr(bad),lambda v=bad:opened(self.volume,v))
        missing=self.shared/'missing'
        self.reject('missing path does not create by default',lambda:opened(self.volume,str(missing.relative_to(self.volume))))
        assert not missing.exists()
        symlink=self.shared/'link';symlink.symlink_to(self.outside,target_is_directory=True)
        self.reject('symlink outside parent',lambda:opened(self.volume,str(symlink.relative_to(self.volume))))
        bind=self.shared/'same-fs-bind';bind.mkdir()
        command(['mount','--bind',str(self.outside),str(bind)]);self.bound.append(bind)
        same_device=bind.stat().st_dev==self.volume.stat().st_dev
        self.reject('same filesystem bind mount boundary',lambda:opened(self.volume,str(bind.relative_to(self.volume))))
        assert same_device;self.record('mount ID guard catches what st_dev alone cannot',sameDevice=same_device)
        foreign=self.shared/'foreign';foreign.mkdir();command(['mount','-t','tmpfs','tmpfs',str(foreign)]);self.bound.append(foreign)
        self.reject('foreign filesystem mount boundary',lambda:opened(self.volume,str(foreign.relative_to(self.volume))))
        for name,expected in [('parent','parent-original'),('child','child-original'),('sibling','sibling-original')]:
            assert self.smb_marker(name)['marker']==expected
            for port in (23049,23050):assert self.nfs(name,port)['marker']==expected
        self.nfs('child',write=True)
        assert (self.child/'nfs-created.txt').read_text()=='native-nfs-write'
        input_file=self.control/'smb-input';input_file.write_text('native-smb-write')
        q=self.smb('child','put '+str(input_file)+' smb-created.txt');assert q.returncode==0,q.stderr
        assert (self.child/'smb-created.txt').read_text()=='native-smb-write'
        self.record('parent child sibling NFS two ports and SMB read/write',childInode=self.child_inode)
        rows=[{'volume':self.volume.name,'parts':['projects'],'id':'parent'},
              {'volume':self.volume.name,'parts':['projects','team-a'],'id':'nfs-child'},
              {'volume':self.volume.name,'parts':['projects','team-a'],'id':'smb-child'},
              {'volume':self.volume.name,'parts':['projects2'],'id':'other'},
              {'volume':'other-volume','parts':['projects','team-b'],'id':'foreign'}]
        children=descendants(rows[0],rows)
        assert [r['id'] for r in children]==['nfs-child','smb-child']
        assert len([r for r in rows if r['volume']==self.volume.name])==4
        self.record('parent delete strict descendant guard and existing per-volume detach references',blockedChildren=[r['id'] for r in children],prefixBoundary=True)
        # Preserve the declared inode even if a parent-share writer later replaces its pathname.
        raw_before=self.smb_marker('rawchild');assert raw_before['marker']=='child-original'
        renamed=self.shared/'old-team-a';self.child.rename(renamed);self.child.symlink_to(self.outside,target_is_directory=True)
        raw_after=self.smb_marker('rawchild')
        assert self.smb_marker('child')['marker']=='child-original'
        assert self.nfs('child')['marker']=='child-original'
        self.reject('replaced pathname is rejected',lambda:opened(self.volume,str(self.child.relative_to(self.volume)),self.child_inode))
        self.record('pinned child does not follow post-activation pathname replacement',rawPhysicalPath=raw_after,pinnedMarker='child-original',samePinnedInode=self.anchors['child'].stat().st_ino==self.child_inode)
        self.child.unlink();renamed.rename(self.child)
        replacement=self.shared/'replacement';replacement.mkdir()
        self.reject('expected inode mismatch',lambda:opened(self.volume,str(replacement.relative_to(self.volume)),self.child_inode))
        # Planned configuration publication failure: restore prior files before private restart.
        before={str(cfg):cfg.read_bytes() for cfg in self.configs.values()}
        first=next(iter(self.configs.values()));first.write_text('DESIGN_INJECTED_INVALID_CANDIDATE')
        for name,data in before.items():Path(name).write_bytes(data)
        self.stop_private();self.start_private()
        assert self.smb_marker('child')['marker']=='child-original'
        assert self.nfs('sibling')['marker']=='sibling-original'
        self.record('private service restart and planned config rollback preserve non-target data',restoredConfigs=all(Path(n).read_bytes()==v for n,v in before.items()))
        before_children={str(p):metadata(p) for p in self.child.rglob('*') if p.is_file()}
        self.stop_private();self.render_nfs(child=False);self.render_smb(child=False);self.start_private()
        assert self.smb_marker('parent')['marker']=='parent-original'
        assert self.nfs('sibling')['marker']=='sibling-original'
        assert before_children=={str(p):metadata(p) for p in self.child.rglob('*') if p.is_file()}
        self.record('removing child publication preserves child files and sibling publication',filesPreserved=True)
        for path in (foreign,bind):
            command(['umount',str(path)]);self.bound.remove(path)
        # New directories are operation-owned; rollback only removes matching empty directories.
        created=self.shared/'new-leaf';fd=opened(self.volume,str(self.shared.relative_to(self.volume)))
        try:os.mkdir('new-leaf',mode=0o770,dir_fd=fd)
        finally:os.close(fd)
        inode=created.stat().st_ino;assert created.is_dir()
        created.rmdir();assert not created.exists()
        self.record('explicit creation and empty-only cleanup primitive',createdInode=inode)
        parent_before=metadata(self.shared)
        relative=str(self.shared.relative_to(self.volume))+'/created-parent/created-leaf'
        made=create_chain(self.volume,relative);assert len(made)==2
        assert rollback_created(self.volume,made)==[]
        assert not (self.volume/relative).exists()
        made=create_chain(self.volume,relative);file=self.volume/relative/'client-file.txt';file.write_text('preserve-client-data')
        retained=rollback_created(self.volume,made)
        assert len(retained)==2 and file.read_text()=='preserve-client-data'
        assert parent_before==metadata(self.shared)
        self.record('explicit missing-parent chain and empty-only rollback preserves new client data',createdCount=2,retainedNonempty=len(retained),existingParentMetadataUnchanged=True)

def main():
    assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='d9c423e9-ce89-3ba9-9cd8-f48cdb791da2'
    assert os.readlink('/proc/self/ns/mnt')!=os.readlink('/proc/1/ns/mnt')
    command(['mount','--make-rprivate','/'])
    args=SimpleNamespace(volume='/srv/ablestack-storage/volumes/e070a0aa-dbf5-48c0-83e0-09f874446277',provisioning='SPARSE',live_export='/export/common1453r5',candidate='')
    result=NestedProbe(args).run();print(json.dumps(result))
    return 0 if result['success'] else 1
if __name__=='__main__':sys.exit(main())
