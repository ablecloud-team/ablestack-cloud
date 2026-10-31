#!/usr/bin/python3
"""Design-only: extracted deployed NFS functions on disposable directories.
Does not call storagectl apply, alter exports, or restart a live daemon.
RO assertions cover the renderer contract; they are not live NFS mount tests.
"""
import ast, contextlib, hashlib, importlib.util, json, os, pathlib, re, stat, subprocess, sys, tempfile

ctl = pathlib.Path("/usr/local/bin/ablestack-storagectl").resolve()
source = ctl.read_text()
chunk = source[source.index("apply_nfs_exports() {"):]
chunk = chunk[chunk.index("<<'PY'")+6:]; chunk = chunk[:chunk.index("\nPY")]
names = {"truth", "int_config", "mode_config", "effective_nfs_export_config",
         "apply_posix_permissions", "ganesha_squash", "ganesha_access"}
tree = ast.parse(chunk)
ns = {"os": os, "re": re}
exec(compile(ast.Module(body=[n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names],
                       type_ignores=[]), str(ctl), "exec"), ns)

def observation(path):
    s = path.stat()
    return {"uid":s.st_uid, "gid":s.st_gid, "mode":format(stat.S_IMODE(s.st_mode),"04o"),
            "dev":s.st_dev, "ino":s.st_ino,
            **({"sha256":hashlib.sha256(path.read_bytes()).hexdigest()} if path.is_file() else {})}

def protected():
    selected = [ctl, pathlib.Path("/etc/ablestack-storage/desired-state/nfs-export-apply.json")]
    selected += list(pathlib.Path("/etc/ganesha").rglob("*.conf"))
    selected += list(pathlib.Path("/etc/exports.d").glob("*"))
    for x in ("r1444n", "r1444s"):
        selected.append(pathlib.Path("/srv/ablestack-storage/volumes/6b2a9fb6-fcbe-4f4d-aff8-4c531603a3ae/export")/x/"ui-preserve.txt")
    return {str(p):observation(p) for p in selected if p.is_file()}
before = protected()
pid_before = subprocess.check_output(["pgrep","-x","ganesha.nfsd"],text=True).split()

# Candidate policy only; this is not imported into the deployed runtime.
def recommend(policy, readonly=False, uid=65534, gid=65534):
    anonymous = policy in ("ROOT", "ALL")
    return {"ownerUid":uid if anonymous and not readonly else 0,
            "ownerGid":gid if anonymous and not readonly else 0,
            "mode":"0775" if anonymous and not readonly else "0755",
            "recursivePermission":False}

def candidate_apply(root, actual, draft, requested, acknowledged=False):
    # No NFS policy/anon-mapping change implicitly requests chown/chmod.
    delta = {k:draft[k] for k in requested if k in ("ownerUid","ownerGid","mode") and k in draft}
    if not delta:
        return {"action":"PRESERVE","fields":[]}
    if not acknowledged:
        raise ValueError("POSIX_CHANGE_ACK_REQUIRED")
    now = observation(root)
    if any(now[k] != actual[k] for k in ("uid","gid","mode","dev","ino")):
        raise ValueError("POSIX_OBSERVATION_CHANGED")
    ns["apply_posix_permissions"](str(root), delta)
    return {"action":"APPLY_ROOT_ONLY","fields":sorted(delta)}

def effective_io(root, uid, gid, write):
    pid = os.fork()
    if pid == 0:
        try:
            os.setgroups([]);os.setgid(gid);os.setuid(uid)
            p = root/("write-"+str(uid))
            if write:
                with p.open("wb") as out: out.write(b"design-fixture-only")
            else:
                (root/"read.txt").read_bytes()
            os._exit(0)
        except PermissionError: os._exit(13)
        except Exception: os._exit(99)
    _, status = os.waitpid(pid,0)
    return os.waitstatus_to_exitcode(status)

results=[]
with tempfile.TemporaryDirectory(prefix="epic1450-design-",dir="/var/tmp") as td:
    temp = pathlib.Path(td); os.chmod(temp,0o755)
    root = temp/"existing";root.mkdir();os.chown(root,1234,2345);os.chmod(root,0o750)
    f = root/"read.txt";f.write_bytes(b"immutable-child-data");os.chown(f,1234,2345);os.chmod(f,0o640)
    original = observation(root);child = observation(f)
    legacy = ns["effective_nfs_export_config"]({"rootSquash":True,"readOnly":False})
    ns["apply_posix_permissions"](str(root),legacy)
    assert (observation(root)["uid"],observation(root)["mode"]) == (65534,"0775")
    results.append({"case":"current_missing_posix_is_auto_initialized","reproduced":True,
                    "before":original,"after":observation(root)})
    os.chown(root,1234,2345);os.chmod(root,0o750)
    assert candidate_apply(root,original,{},[])["action"] == "PRESERVE"
    assert observation(root) == original and observation(f) == child
    results.append({"case":"candidate_policy_only_edit_preserves_root_and_child","pass":True})
    desired = {"ownerUid":1666,"ownerGid":2777,"mode":"0770"}
    try: candidate_apply(root,original,desired,list(desired))
    except ValueError as ex: assert str(ex)=="POSIX_CHANGE_ACK_REQUIRED"
    else: raise AssertionError("missing acknowledgment accepted")
    assert observation(root)==original
    results.append({"case":"candidate_permission_change_requires_ack","pass":True})
    candidate_apply(root,original,desired,list(desired),True)
    assert observation(root)["uid"]==1666 and observation(f)==child
    try: candidate_apply(root,original,desired,list(desired),True)
    except ValueError as ex: assert str(ex)=="POSIX_OBSERVATION_CHANGED"
    else: raise AssertionError("stale preview accepted")
    results.append({"case":"candidate_stale_observation_rejected_child_preserved","pass":True})

    # Actual deployed root-metadata restore branch. Non-metadata phases are
    # substituted with no-ops; no production files or service calls are reachable.
    helper = next(ctl.parent.rglob("configuration_transaction.py"))
    spec=importlib.util.spec_from_file_location("design1450_txn",helper)
    txn=importlib.util.module_from_spec(spec);sys.modules[spec.name]=txn;spec.loader.exec_module(txn)
    adapter=object.__new__(txn.RuntimeAdapter)
    adapter.private=temp/"private";adapter.private.mkdir()
    adapter.is_running=lambda:False
    adapter.snapshot_path=adapter.private/"before.json";adapter.payload_path=adapter.private/"request.json"
    os.chown(root,original["uid"],original["gid"]);os.chmod(root,int(original["mode"],8))
    acl=subprocess.check_output(["getfacl","-p",str(root)],text=True)
    adapter.snapshot_path.write_text(json.dumps({"metadata":{str(root):{"uid":1234,"gid":2345,"mode":0o750,"acl":acl}},"kernel":{}}))
    adapter.payload_path.write_text(json.dumps({"candidate":{},"before":{}}))
    for name in ("restore_files","restore_accounts","capture_new_ports_for_restore",
                 "restore_kernel","restore_lio_credentials","apply","cleanup_new_backstores",
                 "restore_alias_mounts","restore_network"):
        setattr(adapter,name,lambda *a:None)
    os.chown(root,1666,2777);os.chmod(root,0o770)
    adapter.restore()
    assert observation(root)==original and observation(f)==child
    results.append({"case":"actual_deployed_root_metadata_rollback_branch","pass":True,
                    "scope":"real chown/chmod/setfacl, other phases stubbed"})

    matrix=[]
    for policy in ("ROOT","NONE","ALL"):
        for ro in (False,True):
            p=temp/(policy+("-RO" if ro else "-RW"));p.mkdir()
            config=recommend(policy,ro)
            ns["apply_posix_permissions"](str(p),config)
            marker=p/"read.txt";marker.write_bytes(b"policy-matrix");os.chmod(marker,0o644)
            uid,gid=(65534,65534) if policy in ("ROOT","ALL") else (0,0)
            assert effective_io(p,uid,gid,False)==0
            access=ns["ganesha_access"]("READ_WRITE",ro)
            squash=ns["ganesha_squash"](policy=="ALL",policy!="NONE")
            if not ro: assert effective_io(p,uid,gid,True)==0
            else: assert access=="RO" # NFS server enforcement is a later live acceptance test.
            matrix.append({"policy":policy,"readOnly":ro,"recommendation":config,
                           "renderedSquash":squash,"renderedAccess":access,"effectiveUid":uid,
                           "kernelRead":True,"kernelWrite":not ro,
                           "roValidation":"renderer_contract_only" if ro else None})
    # Root_Squash does not make every numeric user a writer.
    assert effective_io(temp/"ROOT-RW",1001,1001,True)==13
    p=temp/"custom-anon";p.mkdir()
    ns["apply_posix_permissions"](str(p),recommend("ALL",False,1234,2345))
    assert effective_io(p,1234,2345,True)==0
    assert ns["ganesha_squash"](True,False)=="All_Squash"
    assert "ownerUid" not in ns["effective_nfs_export_config"]({"allSquash":True,"rootSquash":False})
    results.append({"case":"six_policy_readonly_matrix_and_custom_numeric_identity","pass":True,
                    "matrix":matrix,"rootSquashNonRootWriteDenied":True,
                    "customAnonymous1234Write":True,"current_all_true_root_false_missing_defaults":True})
    invalid=[]
    for c in ({"ownerUid":-1},{"ownerUid":65536},{"mode":"0788"}):
        try:ns["apply_posix_permissions"](str(root),c)
        except ValueError:invalid.append(c)
        else:raise AssertionError("invalid permission accepted")
    assert len(invalid)==3
    results.append({"case":"existing_uid_range_mode_validation_preserved","pass":True})
after=protected()
assert before==after
assert subprocess.check_output(["pgrep","-x","ganesha.nfsd"],text=True).split()==pid_before
print(json.dumps({"designOnly":True,"sourceCtl":str(ctl),
 "sourceCtlSha256":hashlib.sha256(ctl.read_bytes()).hexdigest(),"helperSha256":hashlib.sha256(helper.read_bytes()).hexdigest(),
 "results":results,"protectedFilesUnchanged":True,"productionGaneshaPidUnchanged":True,
 "newDisks":0,"liveExportsChanged":0,"temporaryDirectoryRemoved":not pathlib.Path(td).exists()},
 ensure_ascii=False,indent=2))
