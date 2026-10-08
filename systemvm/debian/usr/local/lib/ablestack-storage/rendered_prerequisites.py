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

"""Fixed prerequisite snapshots for reversible network and directory policy apply."""
import json
import uuid
import pwd
import grp
from rendered_generation import rendered_read
from rendered_network import RenderedNetwork


class RenderedPrerequisites:
    def __init__(self, runtime,store=None):
        self.runtime=runtime;self.network=RenderedNetwork(runtime,store) if store else None

    def snapshot(self, source, target, expected_bindings,root_transfer=None):
        if source["sharedfs-network.json"] != target["sharedfs-network.json"]:
            raise ValueError("Primary network transitions require their explicit maintenance plan")
        before = source["posix-directory-policies.json"] or {}
        after = target["posix-directory-policies.json"] or {}
        snapshots = {};plans = {}
        for key in sorted(set(before) | set(after)):
            if before.get(key) == after.get(key): continue
            if root_transfer is not None and root_transfer.get("retainedRootAuthorization") and key not in after:
                continue # Drop historical canonical policy without DATA/receipt effects.
            row = after.get(key) or before[key]
            request = row.get("request")
            if not isinstance(request, dict) or str(uuid.UUID(request["uuid"])) != key:
                raise ValueError("Rendered common policy does not have its exact request UUID")
            inspected = self.runtime.command(("posix", "directory", "inspect"), request)
            expected = request.get("expectedDirectoryIdentity")
            if key in after:
                if root_transfer is not None:
                    plan=self.runtime.command(("posix","directory","attest-plan"),{"canonicalPolicyRow":row,**{name:root_transfer[name] for name in ("rootScope","sourceConfigurationSha256")}})
                    if plan.get("transferOnly") is not True:raise ValueError("ROOT POSIX stage lacks its actual receipt-only transfer plan")
                else:
                    if expected != inspected["directoryIdentity"]:raise ValueError("Directory changed since the explicit permission preview")
                    plan = self.runtime.command(("posix","directory","plan"),request)
                if (plan.get("sideEffects") is not False or plan.get("postApplyReceiptRequired") is not True
                        or plan.get("beforeDirectoryIdentity") != inspected["directoryIdentity"]
                        or plan.get("configurationDesiredRow") != row):
                    raise ValueError("Rendered directory proposal differs from its pure physical policy plan")
                plans[key] = plan
            elif (row.get("effective") or {}).get("directoryIdentity") != inspected["directoryIdentity"]:
                raise ValueError("Removing a common policy requires its unchanged committed physical inode")
            configured_fs = request.get("expectedFilesystemUuid") or (row.get("effective") or {}).get("filesystemUuid")
            if not configured_fs or configured_fs != inspected["filesystemUuid"]:
                raise ValueError("Rendered policy lacks its exact previously inspected filesystem")
            snapshots[key] = inspected
        endpoints = target["network-endpoints.json"] or {"endpoints": []}
        if not isinstance(expected_bindings, list): raise ValueError("Rendered endpoint changes require expected MAC bindings")
        proof = self.runtime.command(("network", "endpoints", "inspect"), {**endpoints, "expectedBindings": expected_bindings})
        if proof.get("sideEffects") is not False or proof.get("bindingsValidated") is not True:
            raise ValueError("Pure network prerequisite proof is unavailable")
        return {"schemaVersion": 1, "directories": snapshots, "directoryPlans":plans, "expectedBindings": expected_bindings, "networkProof": proof}

    def apply(self, path, previous, rollback=False):
        target = json.loads(rendered_read(path / "desired-state.json"))
        source = json.loads(rendered_read(previous / "desired-state.json"))
        receipt = json.loads(rendered_read(path / "prerequisites.json"))
        if target["network-endpoints.json"] != source["network-endpoints.json"]:
            if self.network is None:raise ValueError("Root reversible network adapter is unavailable")
            self.network.apply(path,previous,rollback)
        after = target["posix-directory-policies.json"] or {}
        for key in sorted(receipt["directories"]):
            snapshot = receipt["directories"][key]
            staged=(receipt.get("directoryPlans") or {}).get(key)
            if staged and staged.get("transferOnly") is True:
                if not rollback:
                    row=after[key]
                    fresh=self.runtime.command(("posix","directory","attest-plan"),{"canonicalPolicyRow":row,"rootScope":staged["rootScope"],"sourceConfigurationSha256":staged["sourceConfigurationSha256"]})
                    if fresh!=staged:raise ValueError("ROOT POSIX physical identity changed before transferred activation")
                # Receipt transfer changed no inode metadata; rollback has no DATA inverse.
                continue
            if rollback:
                self.runtime.command(("posix", "directory", "restore"), snapshot)
            elif key not in after:
                self.runtime.command(("posix", "directory", "forget"), snapshot)
            else:
                request = after[key]["request"]
                staged = (receipt.get("directoryPlans") or {}).get(key)
                fresh = self.runtime.command(("posix","directory","plan"),request)
                if not staged or fresh != staged:
                    raise ValueError("Directory physical plan changed before rendered activation")
                applied = self.runtime.command(("posix", "directory", "apply"), request)
                if (applied.get("postApplyReceiptVerified") is not True
                        or applied.get("directoryIdentity") != staged["predictedDirectoryIdentity"]):
                    raise ValueError("Rendered directory result differs from its staged physical plan")
        return True

    def verify(self,path):
        desired=json.loads(rendered_read(path / "desired-state.json"))
        for key,row in (desired["posix-directory-policies.json"] or {}).items():
            request=row["request"];config=row.get("config") or request.get("config") or {}
            observed=self.runtime.command(("posix","directory","inspect"),request)
            expected_fs=request.get("expectedFilesystemUuid") or (row.get("effective") or {}).get("filesystemUuid")
            if observed.get("postApplyReceiptVerified") is not True:raise ValueError("Rendered common policy lacks its fresh exact post-apply attestation")
            if observed["filesystemUuid"]!=expected_fs or observed["effectiveMode"]!=format(int(str(config.get("directoryMode") or "0770"),8),"04o"):
                raise ValueError("Rendered common policy filesystem/mode readback differs")
            if config.get("applyOwner") and (observed["effectiveUid"],observed["effectiveGid"])!=(config["ownerUid"],config["ownerGid"]):
                raise ValueError("Rendered common policy owner readback differs")
            for field,default in (("accessEntries",False),("defaultEntries",True)):
                for entry in config.get(field) or []:
                    kind=entry["principalType"];principal=entry["principal"]
                    if kind=="NUMERIC_UID":name="user";number=int(principal)
                    elif kind=="NUMERIC_GID":name="group";number=int(principal)
                    elif kind=="LOCAL_USER":name="user";number=pwd.getpwnam(principal).pw_uid
                    elif kind=="LOCAL_GROUP":name="group";number=grp.getgrnam(principal).gr_gid
                    else:raise ValueError("Rendered principal mapping needs its implemented identity adapter")
                    permission="r-x" if entry["permission"]=="READ_ONLY" else "rwx"
                    expected=("default:" if default else "")+name+":"+str(number)+":"+permission
                    if expected not in observed["acl"]:raise ValueError("Rendered common POSIX ACL readback differs")
        return True
