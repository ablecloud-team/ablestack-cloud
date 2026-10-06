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
import copy
import datetime
import json
import subprocess
import time


DNS_TERM = {
    "labelSelector": {"matchLabels": {"k8s-app": "kube-dns"}},
    "topologyKey": "kubernetes.io/hostname",
}


def rebalance_patch(deployment, prefer_workers=False):
    metadata = deployment["metadata"]
    if not metadata.get("uid") or not metadata.get("resourceVersion"):
        raise ValueError("CoreDNS identity is unavailable")
    template = deployment["spec"]["template"]
    if template["metadata"].get("labels", {}).get("k8s-app") != "kube-dns":
        raise ValueError("Unsupported CoreDNS selector")
    if int(deployment["spec"].get("replicas", 1)) < 2:
        raise ValueError("HA requires at least two CoreDNS replicas")
    affinity = copy.deepcopy(template["spec"].get("affinity", {}))
    anti = affinity.setdefault("podAntiAffinity", {})
    terms = anti.setdefault("requiredDuringSchedulingIgnoredDuringExecution", [])
    if DNS_TERM not in terms:
        terms.append(copy.deepcopy(DNS_TERM))
    if prefer_workers:
        preferred = affinity.setdefault("nodeAffinity", {}).setdefault(
            "preferredDuringSchedulingIgnoredDuringExecution", [])
        worker_preference = {"weight": 100, "preference": {"matchExpressions": [
            {"key": "node-role.kubernetes.io/control-plane", "operator": "DoesNotExist"}]}}
        if worker_preference not in preferred:
            preferred.append(worker_preference)
    annotations = dict(template["metadata"].get("annotations", {}))
    annotations["mold.ablecloud.io/dns-rebalanced-at"] = datetime.datetime.now(
        datetime.timezone.utc).isoformat()
    return [
        {"op": "test", "path": "/metadata/uid", "value": metadata["uid"]},
        {"op": "test", "path": "/metadata/resourceVersion", "value": metadata["resourceVersion"]},
        {"op": "add", "path": "/spec/template/spec/affinity", "value": affinity},
        {"op": "add", "path": "/spec/template/metadata/annotations", "value": annotations},
    ]


def owned_ready_nodes(deployment, replicasets, pods):
    uid = deployment["metadata"]["uid"]
    revision = deployment["metadata"].get("annotations", {}).get("deployment.kubernetes.io/revision")
    owned_sets = {r["metadata"]["uid"] for r in replicasets["items"]
                  if (not revision or r["metadata"].get("annotations", {}).get("deployment.kubernetes.io/revision") == revision)
                  and any(o.get("kind") == "Deployment" and o.get("uid") == uid
                         and o.get("controller") is True
                         for o in r["metadata"].get("ownerReferences", []))}
    nodes = []
    for pod in pods["items"]:
        if pod["metadata"].get("deletionTimestamp"):
            continue
        if not any(o.get("kind") == "ReplicaSet" and o.get("uid") in owned_sets
                   and o.get("controller") is True
                   for o in pod["metadata"].get("ownerReferences", [])):
            continue
        if pod.get("spec", {}).get("nodeName") and any(
                c.get("type") == "Ready" and c.get("status") == "True"
                for c in pod.get("status", {}).get("conditions", [])):
            nodes.append(pod["spec"]["nodeName"])
    return nodes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--kubectl", default="/opt/bin/kubectl")
    parser.add_argument("--kubeconfig", default="/etc/kubernetes/admin.conf")
    parser.add_argument("--prefer-workers", action="store_true")
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    if not 30 <= args.timeout <= 120:
        raise ValueError("CoreDNS wait must be between 30 and 120 seconds")
    command = [args.kubectl, "--kubeconfig", args.kubeconfig, "-n", "kube-system"]

    def run(arguments):
        result = subprocess.run(command + arguments, capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise RuntimeError("CoreDNS command failed with exit code " + str(result.returncode))
        return result.stdout

    deployment = json.loads(run(["get", "deploy/coredns", "-o", "json"]))
    uid = deployment["metadata"]["uid"]
    if not args.check_only:
        run(["patch", "deploy/coredns", "--type=json", "-p", json.dumps(rebalance_patch(deployment, args.prefer_workers))])
    deadline = time.monotonic() + args.timeout
    last = None
    while time.monotonic() < deadline:
        current = json.loads(run(["get", "deploy/coredns", "-o", "json"]))
        if current["metadata"]["uid"] != uid:
            raise RuntimeError("CoreDNS Deployment was replaced; preserving the replacement")
        replicasets = json.loads(run(["get", "rs", "-l", "k8s-app=kube-dns", "-o", "json"]))
        pods = json.loads(run(["get", "pods", "-l", "k8s-app=kube-dns", "-o", "json"]))
        nodes = owned_ready_nodes(current, replicasets, pods)
        status = current.get("status", {})
        replicas = int(current["spec"].get("replicas", 1))
        updated = int(status.get("updatedReplicas", 0))
        observed = int(status.get("observedGeneration", 0)) >= int(current["metadata"]["generation"])
        signature = (len(nodes), len(set(nodes)), updated, observed)
        if signature != last:
            print(json.dumps({"phase": "ha-dns-rebalance", "readyPods": len(nodes),
                              "distinctNodes": len(set(nodes)), "updatedReplicas": updated,
                              "observedGeneration": observed}), flush=True)
            last = signature
        if observed and updated == replicas and len(nodes) >= replicas and len(set(nodes)) >= 2:
            print("HA_COREDNS_READY_ON_DISTINCT_NODES", flush=True)
            return
        time.sleep(2)
    raise RuntimeError("CoreDNS did not become Ready on distinct nodes within the bounded wait")


if __name__ == "__main__":
    main()
