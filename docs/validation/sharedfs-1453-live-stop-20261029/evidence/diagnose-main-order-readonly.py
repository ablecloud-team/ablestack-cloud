from pathlib import Path
import ast,json
root=Path("/opt/ablestack/storage-runtime/current/lib/ablestack-storage")
p=root/"shared_directory_policy.py"
s=p.read_text();tree=ast.parse(s)
guard=next((i,n) for i,n in enumerate(tree.body) if isinstance(n,ast.If) and "__name__" in ast.unparse(n.test))
namespace={"__name__":"_read_only_main_prefix"}
exec(compile(ast.fix_missing_locations(ast.Module(body=tree.body[:guard[0]],type_ignores=[])),"<installed-cli-before-main>","exec"),namespace)
result={"readOnly":True,"mainGuardLine":guard[1].lineno,"helpers":[{"name":n.name,"line":n.lineno} for n in tree.body if isinstance(n,ast.FunctionDef) and n.name in ["inheritance_conflict","smb_runtime_settings"]],"namesAvailableWhenMainRuns":{name:name in namespace for name in ["DirectoryPolicies","inheritance_conflict","smb_runtime_settings"]}}
try:eval("inheritance_conflict({}, '2775')",namespace)
except NameError as e:result["lookupError"]=str(e)
print(json.dumps(result))
