import ast,pathlib,tempfile,os,json
ctl=pathlib.Path("/usr/local/bin/ablestack-storagectl").resolve()
s=ctl.read_text();s=s[s.index("volume_attach_inspect() {"):];s=s[s.index("<<'PY'")+6:];s=s[:s.index("\nPY")]
tree=ast.parse(s);n=[x for x in tree.body if isinstance(x,ast.FunctionDef) and x.name=="resolve_backing_path"]
ns={"os":os};exec(compile(ast.Module(body=n,type_ignores=[]),"<deployed resolve_backing_path>","exec"),ns)
out=[]
with tempfile.TemporaryDirectory(prefix="epic1450-path-",dir="/var/tmp") as td:
 root=pathlib.Path(td);(root/"export/team").mkdir(parents=True)
 for rel in ("export/team","/export/team"):
  assert ns["resolve_backing_path"](td,rel,False)==str(root/"export/team");out.append({"path":rel,"result":"existing_supported"})
 for rel in ("../escape","export/team/nested","export"):
  try:ns["resolve_backing_path"](td,rel,False)
  except SystemExit:out.append({"path":rel,"result":"existing_guard_rejected"})
  else:raise AssertionError(rel)
 for rel in ("export/team","export/newteam"):
  existed=(root/rel).exists()
  target=ns["resolve_backing_path"](td,rel,True)
  created=not existed and pathlib.Path(target).is_dir()
  assert created==(rel=="export/newteam")
  out.append({"path":rel,"directoryExistedBefore":existed,"directoryCreated":created,
              "note":"prototype receipt computed around actual resolver; no product receipt added"})
 assert not (root/"escape").exists()
print(json.dumps({"designOnly":True,"pathContractUnchanged":True,"checks":out,"newDisks":0,"liveExportsChanged":0,"temporaryDirectoryRemoved":not pathlib.Path(td).exists()}))
