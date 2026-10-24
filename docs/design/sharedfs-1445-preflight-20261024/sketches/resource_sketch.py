"""#1445 design experiment only; never imported by the installed runtime."""
import copy
import hashlib
import json
import time

MIB = 1024 * 1024

def evaluate(sample, intent):
    # Proposed conservative defaults, not a proven OOM guarantee or a volume-size RAM estimate.
    request_bytes = intent.get("requestBytes", 4096)
    snapshot_bytes = intent.get("snapshotBytes", 65536)
    memory_need = 192*MIB + 8*(request_bytes+snapshot_bytes) + 16*MIB
    disk_need = 80*MIB + 2*(request_bytes+snapshot_bytes)
    blockers, warnings = [], []
    if sample.get("memoryAvailable") is None:
        blockers.append("RESOURCE_OBSERVATION_UNAVAILABLE")
    elif sample["memoryAvailable"] < memory_need:
        blockers.append("MEMORY_LOW")
    for fs in sample["filesystems"]:
        if fs.get("readOnly"):
            blockers.append("FILESYSTEM_READONLY")
        if fs.get("freeBytes") is None or fs.get("freeInodes") is None:
            blockers.append("RESOURCE_OBSERVATION_UNAVAILABLE")
        elif fs["freeBytes"] < disk_need or fs["freeInodes"] < 64:
            blockers.append("SPACE_LOW")
    if sample.get("busyVolumes"):
        blockers.append("VOLUME_PREPARATION_ACTIVE")
    if intent.get("destructive") and sample.get("affectedConnections", 0):
        blockers.append("DRAIN_REQUIRED")
    if sample["load1"] / max(sample["cpuCount"], 1) > 2:
        warnings.append("LOAD_HIGH")
    material = {"revision": intent["revision"], "intent": intent["publicIntent"],
                "impact": intent.get("impact", "ONLINE"),
                "affected": intent.get("affectedResources", [])}
    fingerprint = hashlib.sha256(json.dumps(material, sort_keys=True).encode()).hexdigest()
    return {"ready": not blockers, "blockers": sorted(set(blockers)), "warnings": warnings,
            "requiredMemoryBytes": memory_need, "requiredDiskBytes": disk_need,
            "fingerprint": fingerprint, "expiresAt": sample["observedAt"] + 30,
            "cancellationSupported": False}

def admission(plan, now, ack, current_fingerprint):
    if not plan["ready"]:
        return False
    if now > plan["expiresAt"] or plan["fingerprint"] != current_fingerprint:
        return False
    return bool(ack)

def matrix(real_sample, intent):
    cases=[]
    def case(name, patch, expected, changes=None):
        sample=copy.deepcopy(real_sample)
        sample.update(patch)
        plan=evaluate(sample, {**intent, **(changes or {})})
        assert (expected in plan["blockers"]) if expected else plan["ready"]
        cases.append({"case":name,"result":plan})
    case("actual-measurement-pass", {}, None)
    case("injected-low-memory", {"memoryAvailable":64*MIB}, "MEMORY_LOW")
    case("injected-low-space", {"filesystems":[{"freeBytes":1,"freeInodes":100,"readOnly":False}]}, "SPACE_LOW")
    case("injected-readonly-filesystem", {"filesystems":[{"freeBytes":1024*MIB,"freeInodes":100,"readOnly":True}]}, "FILESYSTEM_READONLY")
    case("injected-running-formatter", {"busyVolumes":["owned-volume"]}, "VOLUME_PREPARATION_ACTIVE")
    case("actual-connections-destructive-intent", {}, "DRAIN_REQUIRED", {"destructive":True})
    case("injected-unknown-memory", {"memoryAvailable":None}, "RESOURCE_OBSERVATION_UNAVAILABLE")
    sample=copy.deepcopy(real_sample);sample["load1"]=20*sample["cpuCount"]
    plan=evaluate(sample,intent);assert plan["ready"] and plan["warnings"]==["LOAD_HIGH"]
    cases.append({"case":"load-only-warning","result":plan})
    plan=evaluate(real_sample,intent)
    assert not admission(plan,plan["expiresAt"]+1,True,plan["fingerprint"])
    changed={**intent,"publicIntent":{**intent["publicIntent"],"readonly":False}}
    new=evaluate(real_sample,changed)
    assert not admission(plan,real_sample["observedAt"],True,new["fingerprint"])
    assert not admission(plan,real_sample["observedAt"],False,plan["fingerprint"])
    assert admission(plan,real_sample["observedAt"],True,plan["fingerprint"])
    denied=evaluate(real_sample,{**intent,"destructive":True})
    assert not admission(denied,real_sample["observedAt"],True,denied["fingerprint"])
    cases.append({"case":"stale-edit-noack-and-drain-ack-contract","passed":True})
    return cases
