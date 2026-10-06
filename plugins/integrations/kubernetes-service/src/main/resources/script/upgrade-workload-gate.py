# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import argparse
import base64
import json
import pathlib
import subprocess
import time


class GateError(RuntimeError):
    pass


def selected(selector, labels):
    for key, value in selector.get("matchLabels", {}).items():
        if labels.get(key) != value:
            return False
    for expression in selector.get("matchExpressions", []):
        key = expression["key"]
        op = expression["operator"]
        values = expression.get("values", [])
        if op == "In" and labels.get(key) not in values:
            return False
        if op == "NotIn" and key in labels and labels[key] in values:
            return False
        if op == "Exists" and key not in labels:
            return False
        if op == "DoesNotExist" and key in labels:
            return False
        if op not in ("In", "NotIn", "Exists", "DoesNotExist"):
            raise GateError("Unsupported PDB selector")
    return True


def identity(item):
    meta = item["metadata"]
    return item["kind"], meta.get("namespace", ""), meta["name"]


def pod_ready(pod):
    return (not pod["metadata"].get("deletionTimestamp")
            and any(c["type"] == "Ready" and c["status"] == "True"
                    for c in pod.get("status", {}).get("conditions", [])))


def controller_ready(item):
    spec = item.get("spec", {})
    status = item.get("status", {})
    count = spec.get("replicas", 1)
    if count < 1 or status.get("observedGeneration", 0) < item["metadata"].get("generation", 1):
        return False
    if item["kind"] == "Deployment":
        return (status.get("readyReplicas", 0) >= count
                and status.get("updatedReplicas", 0) >= count
                and status.get("availableReplicas", 0) >= count)
    return (status.get("readyReplicas", 0) >= count
            and status.get("currentRevision") == status.get("updateRevision"))


def endpoint_count(service, items):
    ready = {p["metadata"]["uid"] for p in items if p["kind"] == "Pod" and pod_ready(p)}
    result = set()
    for item in items:
        if item["kind"] != "EndpointSlice" or item["metadata"].get("namespace") != service["metadata"].get("namespace"):
            continue
        if not any(o.get("uid") == service["metadata"]["uid"] and o.get("kind") == "Service"
                   for o in item["metadata"].get("ownerReferences", [])):
            continue
        for endpoint in item.get("endpoints", []):
            ref = endpoint.get("targetRef", {})
            conditions = endpoint.get("conditions", {})
            if (ref.get("kind") == "Pod" and ref.get("uid") in ready
                    and conditions.get("ready") is True and not conditions.get("terminating", False)):
                result.add(ref["uid"])
    return len(result)


def capture(items, target_nodes):
    pods = [p for p in items if p["kind"] == "Pod" and p.get("spec", {}).get("nodeName") in target_nodes
            and pod_ready(p) and not p["metadata"].get("annotations", {}).get("kubernetes.io/config.mirror")
            and not any(o.get("kind") == "DaemonSet" for o in p["metadata"].get("ownerReferences", []))]
    for budget in (p for p in items if p["kind"] == "PodDisruptionBudget"):
        if budget.get("status", {}).get("disruptionsAllowed", 0) > 0:
            continue
        if any(p["metadata"].get("namespace") == budget["metadata"].get("namespace")
               and selected(budget.get("spec", {}).get("selector", {}), p["metadata"].get("labels", {})) for p in pods):
            raise GateError("PDB blocks upgrade: " + budget["metadata"].get("namespace", "") + "/" + budget["metadata"]["name"])
    baseline = {"schemaVersion": 1, "controllers": [], "services": []}
    for item in items:
        if item["kind"] in ("Deployment", "StatefulSet") and controller_ready(item):
            baseline["controllers"].append({"identity": identity(item), "uid": item["metadata"]["uid"],
                                            "replicas": item.get("spec", {}).get("replicas", 1)})
        if item["kind"] == "Service" and item.get("spec", {}).get("selector"):
            count = endpoint_count(item, items)
            if count:
                baseline["services"].append({"identity": identity(item), "uid": item["metadata"]["uid"],
                                             "readyEndpoints": count,
                                             "ingress": item.get("status", {}).get("loadBalancer", {}).get("ingress", [])})
    return baseline


def recovered(baseline, items):
    current = {identity(i): i for i in items}
    for entry in baseline["controllers"] + baseline["services"]:
        item = current.get(tuple(entry["identity"]))
        if not item or item["metadata"]["uid"] != entry["uid"]:
            raise GateError("Baseline resource was replaced or removed")
        if item["kind"] in ("Deployment", "StatefulSet"):
            if item.get("spec", {}).get("replicas", 1) != entry["replicas"]:
                raise GateError("Baseline replica count changed during upgrade")
            if not controller_ready(item):
                return False
        else:
            if endpoint_count(item, items) < entry["readyEndpoints"]:
                return False
            if item.get("status", {}).get("loadBalancer", {}).get("ingress", []) != entry["ingress"]:
                return False
    return True


def inventory():
    command = ["/opt/bin/kubectl", "--request-timeout=15s", "get",
               "deployments,statefulsets,pods,services,endpointslices,poddisruptionbudgets", "-A", "-o", "json"]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=20, check=True)
        return json.loads(result.stdout)["items"]
    except (subprocess.SubprocessError, ValueError, KeyError):
        raise GateError("Unable to obtain upgrade readiness inventory") from None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--capture", action="store_true")
    parser.add_argument("--nodes-base64", default="W10=")
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    if not 30 <= args.timeout <= 180:
        raise GateError("Invalid workload recovery timeout")
    path = pathlib.Path(args.baseline)
    if args.capture:
        baseline = capture(inventory(), json.loads(base64.b64decode(args.nodes_base64).decode()))
        # Only metadata/readiness receipts are stored; no Pod env or credential values.
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        path.write_text(json.dumps(baseline))
        path.chmod(0o600)
        print("UPGRADE_WORKLOAD_BASELINE_READY", flush=True)
        return
    baseline = json.loads(path.read_text())
    if baseline.get("schemaVersion") != 1:
        raise GateError("Unsupported upgrade workload baseline")
    end = time.monotonic() + args.timeout
    while time.monotonic() < end:
        if recovered(baseline, inventory()):
            print("UPGRADE_WORKLOADS_AND_ENDPOINTS_READY", flush=True)
            return
        time.sleep(2)
    raise GateError("Workloads or Service endpoints did not recover before the next node")


if __name__ == "__main__":
    try:
        main()
    except (GateError, OSError, ValueError, KeyError) as error:
        raise SystemExit(str(error)) from None
