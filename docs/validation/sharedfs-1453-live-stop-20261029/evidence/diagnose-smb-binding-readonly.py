from pathlib import Path
import ast,hashlib,importlib.util,json,os,stat,subprocess,sys
operation="1aae5145-4a9e-4403-b7c3-0ab63d0b6d17"
runtime=Path("/opt/ablestack/storage-runtime/current")
ctl=runtime/"ablestack-storagectl"
if not ctl.is_file():ctl=Path("/usr/local/bin/ablestack-storagectl")
text=ctl.read_text()
assert "apply_smb_shares() {" in text
helper=runtime/"lib/ablestack-storage"
if not helper.is_dir():helper=Path("/usr/local/lib/ablestack-storage")
sys.path.insert(0,str(helper))
import shared_directory_policy as module
body=text.split("apply_smb_shares() {",1)[1].split("<<'PY'\n",1)[1].split("\nPY\n",1)[0]
tree=ast.parse(body)
nodes=[]
for n in tree.body:
 if isinstance(n,ast.ImportFrom) and n.module=="shared_directory_policy" and any(v.name=="DirectoryPolicies" for v in n.names):nodes.append(n)
 if isinstance(n,ast.Assign) and any(isinstance(v,ast.Name) and v.id=="directory_policies" for v in n.targets):nodes.append(n)
assert len(nodes)==2
namespace={}
exec(compile(ast.fix_missing_locations(ast.Module(body=nodes,type_ignores=[])),"<installed-binding-only>","exec"),namespace)
instance=namespace["directory_policies"]
lookups={}
for name in ["validate_inheritance_share","inheritance_enabled"]:
 try:lookups[name]={"instanceCallable":callable(getattr(instance,name))}
 except AttributeError as e:lookups[name]={"instanceCallable":False,"lookupError":str(e)}
 lookups[name]["moduleCallable"]=callable(getattr(module,name,None))
journals=[]
for q in Path("/var/lib/ablestack-storage/configuration-operations").rglob(operation):
 if not q.is_dir():continue
 entry={"path":str(q),"files":[{"name":f.name,"bytes":f.stat().st_size} for f in q.iterdir() if f.is_file()]}
 for filename in ["state.json","public.json"]:
  f=q/filename
  if not f.is_file():continue
  value=json.loads(f.read_text())
  entry[filename]={k:value[k] for k in ["state","phase","originalfailure","rollback","operationid","baseRevision","targetRevision","revision"] if k in value}
 journals.append(entry)
parent=Path("/srv/ablestack-storage/volumes/da246f5b-90fe-46b6-b9dd-70e4f7ea88bf/export/common1453")
s=parent.stat()
result={"readOnly":True,"operation":operation,"storagectl":str(ctl),"storagectlSha256":hashlib.sha256(ctl.read_bytes()).hexdigest(),
 "helperSha256":hashlib.sha256((helper/"shared_directory_policy.py").read_bytes()).hexdigest(),"actualBindingType":type(instance).__name__,
 "contracts":lookups,"journals":journals,"parent":{"uid":s.st_uid,"gid":s.st_gid,"mode":format(stat.S_IMODE(s.st_mode),"04o"),"inode":s.st_ino},
 "nfsUnits":subprocess.run(["systemctl","list-units","--type=service","--state=running","--no-legend","--plain"],capture_output=True,text=True).stdout.splitlines()}
result["nfsUnits"]=[x for x in result["nfsUnits"] if "ganesha" in x or "storage-nfs" in x]
print(json.dumps(result))
