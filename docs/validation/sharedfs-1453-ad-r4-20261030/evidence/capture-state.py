from pathlib import Path
import subprocess,json,os,hashlib
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='fdc01745-efea-3fb4-9959-2bf48694f57f'
def run(args):
 q=subprocess.run(args,text=True,capture_output=True,timeout=20);return {'exitcode':q.returncode,'stdout':q.stdout.strip(),'stderr':q.stderr.strip()[-1000:]}
fd=os.memfd_create('ad-readonly-status',0);os.write(fd,json.dumps({'instanceUuid':'4cc8fd3c-19c5-4f05-960c-82d40abdc802'}).encode());os.lseek(fd,0,0)
query=subprocess.run(['/usr/local/bin/ablestack-storagectl','configuration','ad-domain','show','/proc/self/fd/'+str(fd)],pass_fds=(fd,),capture_output=True,text=True);os.close(fd)
domain_status={'exitcode':query.returncode,'stdout':query.stdout.strip(),'stderr':query.stderr.strip()}
result={'legacySmbd':run(['systemctl','is-active','smbd.service']),'listeners':run(['ss','-lntp','sport = :445']),'nss':{}}
for user in ['epic1453rw','epic1453ro','ABLESTACK\\epic1453ad','ABLESTACK\\epic1453adro']:
 result['nss'][user]=run(['getent','passwd',user])
files={}
parent=Path('/export/common1453r4')
if parent.exists():
 for f in [parent]+sorted(parent.rglob('*')):
  if f.is_symlink():continue
  st=f.stat();row={'uid':st.st_uid,'gid':st.st_gid,'mode':oct(st.st_mode&0o7777),'inode':st.st_ino,'device':st.st_dev}
  if f.is_file() and st.st_size<65536:row['sha256']=hashlib.sha256(f.read_bytes()).hexdigest()
  files[str(f)]=row
result['objects']=files
result['filesystem']=run(['findmnt','-T',str(parent),'-n','-o','UUID,FSTYPE,SOURCE,FSROOT'])
result['domainOperation']=domain_status
print(json.dumps(result))
