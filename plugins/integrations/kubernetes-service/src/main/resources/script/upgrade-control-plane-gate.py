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
import json
import pathlib
import re
import subprocess
import time


def ready(pod):
    return not pod.get("metadata", {}).get("deletionTimestamp") and any(
        c.get("type") == "Ready" and c.get("status") == "True"
        for c in pod.get("status", {}).get("conditions", []))


def verify_local_pods(pods, host, manifests):
    for component, image in manifests.items():
        selected = [p for p in pods if p.get("metadata", {}).get("name") == component + "-" + host]
        if len(selected) != 1 or not ready(selected[0]):
            return False
        if selected[0].get("spec", {}).get("containers", [{}])[0].get("image") != image:
            return False
    return True


def verify_etcd_health(health, expected):
    return len(health) == expected and len({x.get("endpoint") for x in health}) == expected and all(
        x.get("health") is True and not x.get("error") for x in health)


def run_gate(host, control_count, timeout):
    root = pathlib.Path("/etc/kubernetes/manifests")
    manifests = {}
    for component in ["kube-apiserver", "kube-controller-manager", "kube-scheduler", "etcd"]:
        path = root / (component + ".yaml")
        if not path.exists():
            if component == "etcd":
                continue  # External etcd is covered by the authenticated API readyz etcd check.
            raise RuntimeError("Missing control-plane manifest")
        images = re.findall(r"^\s*image:\s*(\S+)\s*$", path.read_text(), re.M)
        if len(images) != 1:
            raise RuntimeError("Cannot verify control-plane image")
        manifests[component] = images[0]
    kubectl = ["/opt/bin/kubectl", "--kubeconfig=/etc/kubernetes/admin.conf", "--request-timeout=10s"]
    deadline = time.monotonic() + timeout
    stable = 0
    while time.monotonic() < deadline:
        try:
            response = subprocess.check_output(kubectl + ["get", "--raw=/readyz"], timeout=15, stderr=subprocess.DEVNULL)
            pods = json.loads(subprocess.check_output(kubectl + ["-n", "kube-system", "get", "pods", "-o", "json"], timeout=15, stderr=subprocess.DEVNULL))["items"]
            good = response.strip() == b"ok" and verify_local_pods(pods, host, manifests)
            api_pods = [p for p in pods if p.get("metadata", {}).get("name", "").startswith("kube-apiserver-")]
            good = good and len(api_pods) == control_count and all(ready(p) for p in api_pods)
            if good and "etcd" in manifests:
                command = kubectl + ["-n", "kube-system", "exec", "etcd-" + host, "--", "etcdctl",
                    "--cacert=/etc/kubernetes/pki/etcd/ca.crt", "--cert=/etc/kubernetes/pki/etcd/healthcheck-client.crt",
                    "--key=/etc/kubernetes/pki/etcd/healthcheck-client.key", "--endpoints=https://127.0.0.1:2379",
                    "endpoint", "health", "--cluster", "--write-out=json"]
                health = json.loads(subprocess.check_output(command, timeout=15, stderr=subprocess.DEVNULL))
                good = verify_etcd_health(health, control_count)
            stable = stable + 1 if good else 0
            if stable >= 3:
                print("UPGRADE_CONTROL_PLANE_AND_ETCD_READY")
                return
        except (subprocess.SubprocessError, ValueError, KeyError):
            stable = 0
        time.sleep(3)
    raise RuntimeError("Control-plane or etcd recovery deadline expired")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--control-count", required=True, type=int)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9.-]+", args.host) or args.control_count < 1 or not 0 < args.timeout <= 180:
        raise SystemExit("Invalid readiness gate arguments")
    run_gate(args.host, args.control_count, args.timeout)
