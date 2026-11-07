import ast,base64,hashlib,json,os,pathlib,shutil,subprocess,sys,time,zipfile
payload=json.load(sys.stdin)
sha=lambda b:hashlib.sha256(b).hexdigest()
def run(*args):return subprocess.check_output(args,text=True,timeout=40)
def snapshot():
 links=json.loads(run('ip','-j','-d','link','show'))
 domains=run('virsh','list','--all','--name').split()
 return {'domains':{n:sha(run('virsh','dumpxml',n).encode())for n in domains},'links':{i['ifname']:{k:i.get(k)for k in ['ifindex','address','master','mtu']}for i in links},'routes':json.loads(run('ip','-j','route','show'))}
prefix='com/cloud/hypervisor/kvm/resource/'
matches=[p for p in pathlib.Path('/usr/share/cloudstack-agent/lib').glob('*.jar')if prefix+'BridgeVifDriver.class' in zipfile.ZipFile(p).namelist()]
assert len(matches)==1,matches
jar=matches[0];script=pathlib.Path('/usr/share/cloudstack-common/scripts/vm/network/vnet/modifyvlan.sh')
backup=pathlib.Path('/root/qa-gn1580-backup-'+time.strftime('%Y%m%d-%H%M%S'));backup.mkdir()
hook=pathlib.Path('/etc/libvirt/hooks/qemu');shutil.copy2(hook,backup/'qemu');oldhook=hook.read_text()
before=snapshot()
old=jar.read_bytes();oldscript=script.read_bytes()
shutil.copy2(jar,backup/jar.name);shutil.copy2(script,backup/script.name)
newclasses={k:base64.b64decode(v)for k,v in payload['classes'].items()}
assert set(newclasses)=={prefix+n+'.class'for n in ['BridgeVifDriver','IvsVifDriver','VlanBridgeName','LibvirtComputingResource']}
tmp=jar.with_suffix('.gn1580-tmp')
with zipfile.ZipFile(jar)as src,zipfile.ZipFile(tmp,'w',zipfile.ZIP_DEFLATED)as dst:
 assert not any(n.endswith(('.SF','.RSA','.DSA'))for n in src.namelist())
 for info in src.infolist():dst.writestr(info,newclasses.get(info.filename,src.read(info.filename)))
 for name,data in newclasses.items():
  if name not in src.namelist():dst.writestr(name,data)
with zipfile.ZipFile(jar)as a,zipfile.ZipFile(tmp)as b:
 preserved={n:sha(a.read(n))for n in a.namelist()if n not in newclasses}
 assert all(sha(b.read(n))==v for n,v in preserved.items())
 assert all(b.read(n)==v for n,v in newclasses.items())
stat=jar.stat();os.chmod(tmp,stat.st_mode);os.chown(tmp,stat.st_uid,stat.st_gid)
newscript=base64.b64decode(payload['script']);tempScript=script.with_suffix('.gn1580-tmp');tempScript.write_bytes(newscript)
run('bash','-n',str(tempScript));st=script.stat();os.chmod(tempScript,st.st_mode);os.chown(tempScript,st.st_uid,st.st_gid)
newhook=base64.b64decode(payload['hook']).decode()
oldnodes={n.name:n for n in ast.parse(oldhook).body if isinstance(n,ast.FunctionDef)}
newnodes={n.name:n for n in ast.parse(newhook).body if isinstance(n,ast.FunctionDef)}
lines=oldhook.splitlines(keepends=True); source=newhook.splitlines(keepends=True)
for name in sorted(['isNewStyleBridge','handleMigrateBegin'],key=lambda n:oldnodes[n].lineno,reverse=True):
 oldnode=oldnodes[name];newnode=newnodes[name];replacement=''.join(source[newnode.lineno-1:newnode.end_lineno])
 if name=='handleMigrateBegin':
  helpers=[]
  for helper in ['vlanBridgeName','preparedVlanParent']:
   if helper not in oldnodes:
    n=newnodes[helper];helpers.append(''.join(source[n.lineno-1:n.end_lineno]))
  replacement='\n\n'.join(helpers+[replacement])
 lines[oldnode.lineno-1:oldnode.end_lineno]=[replacement]
updatedhook=''.join(lines);compile(updatedhook,str(hook),'exec')
# Existing FTCTL, disk unmap and custom hooks remain exactly the same AST.
updatednodes={n.name:n for n in ast.parse(updatedhook).body if isinstance(n,ast.FunctionDef)}
preserved_hook_functions=[n for n in oldnodes if n not in ['isNewStyleBridge','handleMigrateBegin']]
assert all(ast.dump(oldnodes[n])==ast.dump(updatednodes[n])for n in preserved_hook_functions)
tmphook=hook.with_suffix('.gn1580-tmp');tmphook.write_text(updatedhook);hs=hook.stat();os.chmod(tmphook,hs.st_mode);os.chown(tmphook,hs.st_uid,hs.st_gid)
os.replace(tmp,jar);os.replace(tempScript,script);os.replace(tmphook,hook)
run('systemctl','restart','mold-agent')
assert run('systemctl','is-active','mold-agent').strip()=='active'
after=snapshot();(backup/'state-comparison.json').write_text(json.dumps({'before':before,'after':after},indent=2));assert before==after, 'State differs; see '+str(backup/'state-comparison.json')
result={'backup':str(backup),'jar':str(jar),'before_jar_sha256':sha(old),'after_jar_sha256':sha(jar.read_bytes()),'before_script_sha256':sha(oldscript),'after_script_sha256':sha(newscript),'classes':{k:sha(v)for k,v in newclasses.items()},'unchanged_other_entries':len(preserved),'before':before,'after':after,'service':run('systemctl','show','mold-agent','-p','MainPID','-p','ActiveState'),'hook_before_sha256':sha(oldhook.encode()),'hook_after_sha256':sha(updatedhook.encode()),'preserved_hook_functions':preserved_hook_functions,'source':payload['source']}
(backup/'receipt.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result))
