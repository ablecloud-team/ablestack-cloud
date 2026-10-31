from pathlib import Path
import subprocess,json,os,stat,hashlib
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='fdc01745-efea-3fb4-9959-2bf48694f57f'
def run(args):
 q=subprocess.run(args,capture_output=True,text=True,timeout=25);return {'exitcode':q.returncode,'stdout':q.stdout.strip()[-6000:],'stderr':q.stderr.strip()[-2000:]}
fd=os.memfd_create('ad-readonly-status',0);os.write(fd,json.dumps({'instanceUuid':'4cc8fd3c-19c5-4f05-960c-82d40abdc802'}).encode());os.lseek(fd,0,0)
query=subprocess.run(['/usr/local/bin/ablestack-storagectl','configuration','ad-domain','show','/proc/self/fd/'+str(fd)],pass_fds=(fd,),capture_output=True,text=True);os.close(fd)
domain_status={'exitcode':query.returncode,'stdout':query.stdout.strip(),'stderr':query.stderr.strip()}
result={'files':{},'services':{},'domainStatus':domain_status}
for name in ['/etc/resolv.conf','/etc/krb5.conf','/etc/samba/smb.conf']:
 p=Path(name)
 if not p.exists() and not p.is_symlink():
  result['files'][name]={'exists':False};continue
 s=p.lstat();row={'regular':stat.S_ISREG(s.st_mode),'symlink':p.is_symlink(),'mode':oct(s.st_mode&0o7777),'uid':s.st_uid,'gid':s.st_gid,'bytes':s.st_size,'inode':s.st_ino}
 if p.is_symlink():row['target']=os.readlink(p);row['resolved']=str(p.resolve())
 if p.exists():row['sha256']=hashlib.sha256(p.read_bytes()).hexdigest()
 result['files'][name]=row
for unit in ['smbd.service','nmbd.service','winbind.service']:
 result['services'][unit]=run(['systemctl','show',unit,'-p','ActiveState','-p','SubState','-p','MainPID','-p','ExecMainStartTimestamp'])
result['ss']=run(['ss','-lntp','sport = :445'])
result['membership']=run(['net','ads','testjoin'])
for f in Path('/var/lib/ablestack-storage/ad-domain-operations').glob('*.json'):
 result['marker']=json.loads(f.read_text())
result['journal']=run(['journalctl','--since','2026-10-30 06:18:00 UTC','-u','smbd.service','-u','nmbd.service','-u','winbind.service','--no-pager','-n','50'])
result['protectedFilesHash']={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in Path('/etc/ablestack-storage/desired-state').glob('*.json')}
print(json.dumps(result))
