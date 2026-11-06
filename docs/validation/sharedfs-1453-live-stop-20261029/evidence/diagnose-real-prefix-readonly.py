from pathlib import Path
import ast,builtins,hashlib,io,json,os,stat,subprocess,sys,traceback
from unittest.mock import patch
sys.dont_write_bytecode=True
runtime=Path("/opt/ablestack/storage-runtime/current")
ctl=runtime/"ablestack-storagectl"
text=ctl.read_text()
function=text.split("apply_smb_shares() {",1)[1]
body=function.split("<<'PY'\n",1)[1].split("\nPY\n",1)[0]
start=text.index(body)
tree=ast.parse(body)
guard=next(i for i,n in enumerate(tree.body) if isinstance(n,ast.For) and any(isinstance(c,ast.Attribute) and c.attr=="validate_inheritance_share" for c in ast.walk(n)))
request=json.loads(Path("/var/lib/ablestack-storage/configuration-operations/0c0e570b-3793-41de-9d2d-2129276e2f08/1aae5145-4a9e-4403-b7c3-0ab63d0b6d17/request.json").read_text())
candidate=request["candidate"]["smb share apply"]
target=Path("/srv/ablestack-storage/volumes/da246f5b-90fe-46b6-b9dd-70e4f7ea88bf/export/common1453")
def before():
 s=target.stat()
 return {"parent":[s.st_uid,s.st_gid,stat.S_IMODE(s.st_mode),s.st_ino],"code":hashlib.sha256(ctl.read_bytes()).hexdigest()}
initial=before()
real_open=builtins.open
sentinel="/__in_memory_candidate_only__"
def read_only_open(path,mode="r",*args,**kwargs):
 if str(path)==sentinel:return io.StringIO(json.dumps(candidate))
 if any(x in mode for x in "wax+"):raise AssertionError("Write forbidden in read-only diagnostic")
 return real_open(path,mode,*args,**kwargs)
os.environ["ABLESTACK_STORAGE_HELPER_ROOT"]=str(runtime/"lib/ablestack-storage")
sys.argv=["read-only-prefix",sentinel]
result={"readOnly":True,"fullOperationReplayed":False,"externalCommandsExecuted":False}
try:
 with patch.object(builtins,"open",side_effect=read_only_open),patch.object(subprocess,"run",side_effect=AssertionError("External command forbidden in read-only diagnostic")):
  exec(compile(ast.fix_missing_locations(ast.Module(body=tree.body[:guard+1],type_ignores=[])),"<installed-real-prefix>","exec"),{})
except Exception as error:
 frames=traceback.extract_tb(error.__traceback__)
 frame=next((f for f in reversed(frames) if f.filename=="<installed-real-prefix>"),None)
 result.update(errorType=type(error).__name__,error=str(error),bodyLine=frame.lineno if frame else None,
               storagectlLine=text[:start].count("\n")+(frame.lineno if frame else 0))
result["configurationUnchanged"]=initial==before()
print(json.dumps(result))
