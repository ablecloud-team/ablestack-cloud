#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Harmless wrapper safety tests. Never imports a product Native module."""
import argparse
import builtins
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
import case_filesystem_guard as guard_module
from case_filesystem_guard import CasePathGuard,CasePathDenied

ACCOUNT_FILES={"/etc/passwd","/etc/group","/etc/shadow","/etc/gshadow"}

def need(value,code):
    if not value:raise AssertionError(code)

def snapshot(root):
    result={}
    for parent,dirs,files in os.walk(root,followlinks=False):
        for name in sorted(dirs+files):
            path=Path(parent)/name;info=path.lstat()
            row=[info.st_dev,info.st_ino,info.st_mode,info.st_uid,info.st_gid,info.st_nlink,info.st_size]
            if stat.S_ISLNK(info.st_mode):row.append(os.readlink(path))
            elif stat.S_ISREG(info.st_mode):row.append(hashlib.sha256(path.read_bytes()).hexdigest())
            result[str(path.relative_to(root))]=row
    return result

def protected_stat(path):
    info=path.lstat()
    # Read-side atime is excluded; mutation-relevant metadata remains exact.
    return [info.st_dev,info.st_ino,info.st_mode,info.st_uid,info.st_gid,
            info.st_nlink,info.st_size,info.st_mtime_ns,info.st_ctime_ns]

def run(root,outside):
    guard=CasePathGuard(root)
    need(root.name.startswith("storage-config-baseline-flow-"),"FIXED_SAFETY_ROOT_PREFIX")
    need(outside==HERE/"outside-sentinel-public.txt","FIXED_PUBLIC_SENTINEL")
    need(outside.is_file() and outside.stat().st_nlink==1,"PUBLIC_SENTINEL_SINGLE_FILE")
    outside_before=(protected_stat(outside),hashlib.sha256(outside.read_bytes()).hexdigest())
    spec=importlib.util.spec_from_file_location("v13_safety_fixture",HERE/"rendered-local-os-fixture.py")
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    fixture=module.RenderedGuestFixture.__new__(module.RenderedGuestFixture)
    fixture.case=root/"native";fixture.osroot=fixture.case/"rendered-os-fixture"
    fixture.original_path=Path;fixture.path_guard=guard
    fixture.generation=SimpleNamespace(config=fixture.case/"configuration")
    fixture.local=SimpleNamespace()
    fixture.driver=SimpleNamespace(runtime=SimpleNamespace(nvme_root=fixture.case/"fake-nvmet"))
    class DummySource:pass
    fixture.identity_module=SimpleNamespace(Path=Path)
    fixture.capsule_module=SimpleNamespace(Path=Path,FILES={"/etc/samba/smb.conf"},
        AD_FILES=set(),ACCOUNT_FILES=ACCOUNT_FILES,regular_file=lambda *args:None)
    fixture.source_module=SimpleNamespace(Path=Path,SmbSourceCheckpoint=DummySource,
        LOCAL_SOURCE_SMB_CONFIG=Path("/etc/samba/smb.conf"),LOCAL_SOURCE_NVME_ROOT=Path("/fake/nvmet"))
    fixture.service_module=SimpleNamespace(Path=Path)
    for path in (fixture.osroot/"etc/samba",fixture.generation.config):
        guard.path(path);path.mkdir(mode=0o700,parents=True,exist_ok=True)
    safe=fixture.case/"safety";guard.path(safe);safe.mkdir(mode=0o700)
    original_link=os.link;original_symlink=os.symlink;original_unlink=os.unlink;original_replace=os.replace
    linked=safe/"hardlink-source";linked.write_text("owned public fixture\n")
    original_link(linked,safe/"hardlink-alias")
    original_symlink(outside,safe/"outside-link")
    original_symlink(outside.parent,safe/"outside-directory")
    outside_fd=os.open(outside,os.O_RDONLY|os.O_NOFOLLOW)
    outside_dir_fd=os.open(outside.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    inside_dir_fd=os.open(safe,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    passed=[];denials=[]
    def positive(name,action):
        action();passed.append(name)
    def negative(name,action):
        before=snapshot(fixture.case)
        try:action()
        except CasePathDenied as failure:denials.append({"name":name,"code":failure.code})
        else:raise AssertionError("MUTATION_WAS_NOT_REJECTED:"+name)
        need(snapshot(fixture.case)==before,"NEGATIVE_CHANGED_CASE:"+name)
        need(hashlib.sha256(outside.read_bytes()).hexdigest()==outside_before[1],"OUTSIDE_CHANGED:"+name)
    try:
        with fixture.local_os_boundary():
            for name in sorted(ACCOUNT_FILES):
                need(fixture.mapped_path(name)==fixture.osroot/name.lstrip("/"),"EXACT_ACCOUNT_MAP")
                positive("mapped-open:"+name,lambda name=name:Path(name).write_text("synthetic public "+Path(name).name+"\n"))
                need((fixture.osroot/name.lstrip("/")).is_file(),"ACCOUNT_WRITTEN_IN_CASE")
                need(Path(name).read_text()=="synthetic public "+Path(name).name+"\n","ACCOUNT_READ_FROM_CASE")
            need(fixture.mapped_path("/etc")==fixture.osroot/"etc","EXACT_ACCOUNT_PARENT_MAP")
            fd,name=tempfile.mkstemp(prefix=".identity-",dir="/etc")
            need(Path(name).parent==fixture.osroot/"etc","TEMP_PARENT_MAPPED")
            os.write(fd,b"synthetic account replacement\n");os.fchmod(fd,0o600);os.close(fd)
            os.replace(name,"/etc/passwd")
            passed.append("mapped-parent-temp-write-replace-account")
            p=safe/"builtins"
            with builtins.open(p,"w")as stream:stream.write("public\n")
            with io.open(safe/"io-open","w")as stream:stream.write("public\n")
            passed.extend(["builtins-open-inside","io-open-inside"])
            p=safe/"descriptor"
            fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            os.write(fd,b"public");os.fchmod(fd,0o600);os.fchown(fd,-1,-1);os.ftruncate(fd,3)
            if hasattr(os,"pwrite"):os.pwrite(fd,b"x",0)
            os.close(fd)
            with os.fdopen(os.open(p,os.O_WRONLY),"w")as stream:stream.write("public\n")
            passed.append("inside-fd-write-fchmod-fchown-ftruncate-fdopen")
            os.mkdir(safe/"mkdir",0o700);os.makedirs(safe/"nested/child",mode=0o700)
            os.chmod(p,0o600);os.chown(p,-1,-1);os.truncate(p,2);os.utime(p,None)
            os.symlink(p,safe/"inside-link");os.unlink(safe/"inside-link")
            (safe/"rename-source").write_text("public")
            os.rename(safe/"rename-source",safe/"rename-target")
            os.replace(safe/"rename-target",safe/"replace-target")
            os.remove(safe/"replace-target");os.rmdir(safe/"mkdir")
            passed.append("inside-path-create-metadata-symlink-rename-remove")
            parent=safe/"pathlib-parent";parent.mkdir(mode=0o700)
            child=parent/"source";child.write_text("public pathlib\n");child.chmod(0o600)
            child=child.rename(parent/"renamed");child=child.replace(parent/"replaced")
            link=parent/"link";link.symlink_to(child);link.unlink();child.unlink();parent.rmdir()
            passed.append("inside-pathlib-captured-accessor-mutations")
            fd=os.open("relative",os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600,dir_fd=inside_dir_fd)
            os.write(fd,b"public");os.close(fd)
            os.unlink("relative",dir_fd=inside_dir_fd);passed.append("inside-dirfd")
            temp=safe/"publication-temp";final=safe/"publication-final"
            temp.write_bytes(b"public immutable publication");temp.chmod(0o600)
            os.link(temp,final,follow_symlinks=False)
            need(temp.lstat().st_nlink==final.lstat().st_nlink==2,"PUBLICATION_TWO_OWNED_NAMES")
            negative("registered-publication-write",lambda:temp.write_bytes(b"forbidden"))
            negative("registered-publication-final-unlink",lambda:os.unlink(final))
            negative("registered-publication-rename",lambda:os.rename(temp,safe/"forbidden-rename"))
            alias=safe/"publication-temp-symlink";os.symlink(temp,alias)
            negative("registered-publication-symlink-unlink",lambda:os.unlink(alias))
            original_unlink(alias)  # Controlled adversarial fixture setup only, inside owned case.
            extra=safe/"publication-unknown-alias";original_link(temp,extra,follow_symlinks=False)
            negative("registered-publication-third-alias-unlink",lambda:os.unlink(temp))
            negative("registered-publication-unknown-alias-write",lambda:extra.write_bytes(b"forbidden"))
            original_unlink(extra)
            replacement=safe/"publication-replacement";replacement.write_bytes(b"public replacement")
            original_replace(replacement,final)
            negative("registered-publication-replaced-final-unlink",lambda:os.unlink(temp))
            original_unlink(final);original_link(temp,final,follow_symlinks=False)
            os.unlink(temp)
            need(not temp.exists()and final.lstat().st_nlink==1
                 and final.read_bytes()==b"public immutable publication","IMMUTABLE_PUBLICATION_FINAL_EXACT")
            passed.append("owned-immutable-publication-temp-unlink-final-single-link")
            before=snapshot(fixture.case)
            try:os.link(p,final,follow_symlinks=False)
            except FileExistsError:pass
            else:raise AssertionError("IMMUTABLE_EXISTING_TARGET_WAS_OVERWRITTEN")
            need(snapshot(fixture.case)==before,"EEXIST_CHANGED_OWNED_FILES")
            passed.append("owned-immutable-publication-atomic-no-overwrite")
            inside_source_link=safe/"inside-source-symlink";os.symlink(p,inside_source_link)
            negative("hardlink-source-symlink",lambda:os.link(inside_source_link,safe/"forbidden-link-source"))
            os.unlink(inside_source_link)
            binary=fixture.osroot/"usr/sbin/smbd";binary.parent.mkdir(parents=True,exist_ok=True)
            binary.write_bytes(b"public non-executable OS fixture")
            proc=fixture.osroot/"proc/4242";proc.mkdir(parents=True)
            exe=proc/"exe";exe.symlink_to(binary)
            logical_readlink=os.readlink
            need(os.readlink(exe)=="/usr/sbin/smbd","LOGICAL_OBSERVATION_SEAM_ACTIVE")
            need(guard.path(exe)==str(binary),"GUARD_RESOLVES_PHYSICAL_CASE_TARGET")
            need(os.readlink is logical_readlink and os.readlink(exe)=="/usr/sbin/smbd","LOGICAL_SEAM_RESTORED")
            passed.append("physical-guard-resolver-preserves-logical-daemon-observation")
            raw_resolve=guard_module._REALPATH;entered=[]
            def nested_resolve(path,*,strict=False):
                if not entered:
                    entered.append(True)
                    return guard_module._physical_realpath(path,strict=strict)
                return raw_resolve(path,strict=strict)
            with patch.object(guard_module,"_REALPATH",side_effect=nested_resolve):
                need(guard.path(exe)==str(binary),"NESTED_RAW_RESOLVER")
            need(os.readlink is logical_readlink,"NESTED_LOGICAL_SEAM_RESTORED")
            passed.append("nested-physical-resolver-restores-immediate-caller")
            with patch.object(guard_module.threading,"active_count",return_value=2):
                negative("raw-resolver-multithread-denied",lambda:guard.path(exe))
            need(os.readlink is logical_readlink,"THREAD_REJECTION_PRESERVES_SEAM")
            with patch.object(guard_module,"_REALPATH",side_effect=OSError("synthetic resolver failure")):
                try:guard_module._physical_realpath(exe)
                except OSError:pass
                else:raise AssertionError("RAW_RESOLVER_FAILURE_NOT_PROPAGATED")
            need(os.readlink is logical_readlink,"FAILED_RAW_RESOLVER_RESTORES_SEAM")
            passed.append("physical-resolver-failure-restores-logical-seam")
            negative("physical-resolver-outside-symlink-denied",lambda:guard.path(safe/"outside-link"))
            need(os.readlink(exe)=="/usr/sbin/smbd","OUTSIDE_REJECTION_PRESERVES_SEAM")
            __import__("shutil").rmtree(proc)
            need(not proc.exists()and binary.read_bytes()==b"public non-executable OS fixture","OWNED_PROC_RMTREE_EXACT")
            need(os.readlink is logical_readlink,"PROC_RMTREE_PRESERVES_LOGICAL_SEAM")
            passed.append("owned-proc-exe-rmtree-uses-physical-dirfd-containment")
            fixture.os_state=lambda:{"domains":{"SMB":{}}}
            fixture.effects=[];fixture.store=SimpleNamespace(root=fixture.case/"rendered-generations")
            candidate=fixture.osroot/"etc/samba/.local-source-smb-public"
            candidate.write_text("[global]\nsecurity = user\n");candidate.chmod(0o600)
            need("[global]"in fixture.smb_os_run(["testparm","-s",str(candidate)]),"LOCAL_TESTPARM_PARSED_ACTUAL_CANDIDATE")
            need(fixture.effects[-1]["candidateSha256"]==hashlib.sha256(candidate.read_bytes()).hexdigest(),"LOCAL_PARSER_FINGERPRINT")
            staged=fixture.store.root/"scope/smb/smb.conf";staged.parent.mkdir(parents=True)
            staged.write_text("[global]\nsecurity = user\n")
            need(fixture.os_run(["testparm","-s",str(staged)]).returncode==0,"EXISTING_RENDERED_TESTPARM_UNCHANGED")
            passed.append("local-and-rendered-candidates-share-actual-configparser")
            def parser_reject(name,path,through_smb=True):
                before=snapshot(fixture.case);events=len(fixture.effects)
                try:(fixture.smb_os_run if through_smb else fixture.os_run)(["testparm","-s",str(path)])
                except (ValueError,CasePathDenied,module.configparser.Error)as failure:
                    denials.append({"name":name,"code":type(failure).__name__})
                else:raise AssertionError("INVALID_TESTPARM_CANDIDATE_ACCEPTED:"+name)
                need(snapshot(fixture.case)==before and len(fixture.effects)==events,"REJECTED_PARSER_SIDE_EFFECT:"+name)
            parser_reject("local-testparm-outside",outside)
            wrong=candidate.with_name("wrong-prefix");wrong.write_text(candidate.read_text())
            parser_reject("local-testparm-wrong-prefix",wrong)
            symbolic=candidate.with_name(".local-source-smb-symlink");symbolic.symlink_to(candidate)
            parser_reject("local-testparm-symlink",symbolic);os.unlink(symbolic)
            linked_candidate=candidate.with_name(".local-source-smb-linked");linked_candidate.write_text(candidate.read_text())
            alias=candidate.with_name(".local-source-smb-alias");os.link(linked_candidate,alias)
            parser_reject("local-testparm-hardlink",linked_candidate);os.unlink(linked_candidate);os.unlink(alias)
            candidate.chmod(0o666);parser_reject("local-testparm-writable",candidate);candidate.chmod(0o600)
            candidate.write_text("not valid INI syntax")
            parser_reject("local-testparm-malformed-syntax",candidate)
            candidate.write_text("[global]\nsecurity = ads\n")
            parser_reject("local-testparm-nonlocal-security",candidate)
            candidate.write_text("[global]\nsecurity = user\n")
            negative("outside-open",lambda:open(outside,"w"))
            negative("outside-path-write",lambda:outside.write_text("forbidden"))
            negative("outside-os-open",lambda:os.open(outside,os.O_WRONLY))
            negative("unmapped-outside-create",lambda:open(outside.parent/"must-not-exist","w"))
            negative("outside-proc-root-alias",lambda:open("/proc/self/root"+str(outside),"w"))
            negative("outside-symlink-follow",lambda:(safe/"outside-link").write_text("forbidden"))
            negative("outside-directory-symlink",lambda:open(safe/"outside-directory/escape","w"))
            negative("outside-dirfd",lambda:os.open("must-not-exist",os.O_CREAT|os.O_WRONLY,0o600,dir_fd=outside_dir_fd))
            negative("outside-fd-write",lambda:os.write(outside_fd,b"forbidden"))
            negative("outside-fd-fchmod",lambda:os.fchmod(outside_fd,0o600))
            negative("outside-fd-fchown",lambda:os.fchown(outside_fd,-1,-1))
            negative("outside-fd-truncate",lambda:os.ftruncate(outside_fd,0))
            negative("outside-fd-open",lambda:os.fdopen(outside_fd,"w",closefd=False))
            negative("outside-unlink",lambda:os.unlink(outside))
            negative("outside-remove",lambda:os.remove(outside))
            negative("outside-chmod",lambda:os.chmod(outside,0o600))
            negative("outside-chown",lambda:os.chown(outside,-1,-1))
            negative("outside-mkdir",lambda:os.mkdir(outside.parent/"forbidden-directory"))
            negative("outside-rmdir",lambda:os.rmdir(outside.parent))
            negative("outside-truncate",lambda:os.truncate(outside,0))
            negative("outside-utime",lambda:os.utime(outside,None))
            negative("outside-temp-dir",lambda:tempfile.mkstemp(dir=outside.parent))
            negative("unspecified-temp-dir",lambda:tempfile.mkstemp())
            negative("outside-rename-source",lambda:os.rename(outside,safe/"forbidden-renamed"))
            negative("outside-rename-destination",lambda:os.rename(p,outside))
            negative("outside-replace-source",lambda:os.replace(outside,safe/"forbidden-replaced"))
            negative("outside-replace-destination",lambda:os.replace(p,outside))
            negative("outside-symlink-target",lambda:os.symlink(outside,safe/"forbidden-link"))
            negative("outside-symlink-destination",lambda:os.symlink(p,outside.parent/"forbidden-link"))
            negative("outside-hardlink-source",lambda:os.link(outside,safe/"forbidden-hardlink"))
            negative("outside-hardlink-destination",lambda:os.link(p,outside.parent/"forbidden-hardlink"))
            negative("existing-hardlink-write",lambda:linked.write_text("forbidden"))
            negative("existing-hardlink-unlink",lambda:os.unlink(linked))
            negative("root-mode-change",lambda:os.chmod(root,0o755))
            negative("custom-open-opener",lambda:open(p,"w",opener=lambda *args:outside_fd))
            negative("custom-read-opener",lambda:open(p,"r",opener=lambda *args:outside_fd))
            negative("outside-pathlib-unlink",lambda:outside.unlink())
            negative("outside-pathlib-mkdir",lambda:(outside.parent/"forbidden-pathlib-dir").mkdir())
            negative("outside-pathlib-chmod",lambda:outside.chmod(0o600))
            negative("outside-pathlib-rmdir",lambda:outside.parent.rmdir())
            negative("outside-pathlib-rename-source",lambda:outside.rename(safe/"forbidden-pathlib"))
            negative("outside-pathlib-rename-destination",lambda:p.rename(outside))
            negative("outside-pathlib-replace-source",lambda:outside.replace(safe/"forbidden-pathlib"))
            negative("outside-pathlib-replace-destination",lambda:p.replace(outside))
            negative("outside-pathlib-symlink-target",lambda:(safe/"forbidden-pathlib-link").symlink_to(outside))
            negative("outside-pathlib-symlink-destination",lambda:(outside.parent/"forbidden-pathlib-link").symlink_to(p))
            negative("outside-pathlib-touch",lambda:outside.touch())
            if hasattr(p,"link_to"):
                negative("pathlib-preexisting-hardlink-source",lambda:linked.link_to(safe/"forbidden-pathlib-hardlink"))
        need(protected_stat(outside)==outside_before[0],"PUBLIC_SENTINEL_METADATA_CHANGED")
        need(hashlib.sha256(outside.read_bytes()).hexdigest()==outside_before[1],"PUBLIC_SENTINEL_HASH_CHANGED")
        need(not any(name in sys.modules for name in ("identity_capsule","smb_source_checkpoint",
            "rendered_generation","rendered_driver","config_generation")),"PRODUCT_NATIVE_MODULE_IMPORTED")
        return {"success":True,"kind":"V13_FILESYSTEM_WRAPPER_SAFETY_ONLY","positiveCount":len(passed),
            "negativeCount":len(denials),"positiveChecks":passed,"deniedChecks":denials,
            "outsideSentinelUnchanged":True,"accountMutationsMappedToOwnedCase":True,
            "productNativeImported":False,"restoreInvoked":False,"functionalRestoreClaim":False}
    finally:
        for fd in (outside_fd,outside_dir_fd,inside_dir_fd):os.close(fd)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--owned-test-root",type=Path,required=True)
    parser.add_argument("--repository-root",type=Path)
    parser.add_argument("--checked-postconditions")
    args=parser.parse_args()
    try:result=run(args.owned_test_root,HERE/"outside-sentinel-public.txt")
    except BaseException as failure:
        result={"success":False,"kind":"V13_FILESYSTEM_WRAPPER_SAFETY_ONLY",
            "exceptionClass":type(failure).__name__,"code":getattr(failure,"code",None),
            "safeLocations":[{"file":Path(row.filename).name,"function":row.name,"line":row.lineno}
                for row in __import__("traceback").extract_tb(failure.__traceback__)]}
    if args.checked_postconditions is not None:
        result["launcherPostconditions"]=json.loads(args.checked_postconditions)
    print(json.dumps(result,separators=(",",":")),flush=True)
    return 0 if result["success"]else 1

if __name__=="__main__":sys.exit(main())
