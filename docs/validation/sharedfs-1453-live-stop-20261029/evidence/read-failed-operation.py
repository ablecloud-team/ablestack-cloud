from pathlib import Path
import json
p=Path("/var/lib/ablestack-storage/configuration-operations/0c0e570b-3793-41de-9d2d-2129276e2f08/1aae5145-4a9e-4403-b7c3-0ab63d0b6d17")
assert p.is_dir()
result={"operation":"1aae5145-4a9e-4403-b7c3-0ab63d0b6d17","readOnly":True}
status=json.loads((p/"status.json").read_text())
result["status"]={k:status[k] for k in ["state","phase","originalfailure","rollback","baseRevision","targetRevision","revision","operationid"] if k in status}
runner=json.loads((p/"runner-result.json").read_text())
result["latestRunnerResult"]={k:runner[k] for k in ["operation","returncode","outputTruncated","stdoutBytes","stderrBytes","stdout","stderr"] if k in runner}
request=json.loads((p/"request.json").read_text())
result["requestKeys"]=list(request)
commands=request.get("candidate",{})
result["candidateCommands"]=list(commands)
smb=commands.get("smb share apply",{})
result["shares"]=[{"name":s.get("name"),"uuid":s.get("uuid"),"state":s.get("state"),
 "configKeys":list((s.get("config") or {}).keys()),"inheritOwnerPresent":"inheritOwner" in (s.get("config") or {}),
 "directoryPolicyUuid":(s.get("config") or {}).get("directoryPolicyUuid")} for s in smb.get("shares",[])]
print(json.dumps(result))
