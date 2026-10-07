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

import copy
import json
import os
import subprocess
import sys

TARGETS = {
    "CCM": ("cloud-controller-manager", "cloud-controller-manager",
            ("ghcr.io/dhslove/ablestack-kubernetes-provider@sha256:",
             "ghcr.io/ablecloud-team/ablestack-kubernetes-provider@sha256:")),
    "HEADLAMP": ("headlamp", "headlamp", ("ghcr.io/headlamp-k8s/headlamp@sha256:",)),
}
CONTROL_TAINTS = ("node-role.kubernetes.io/control-plane", "node-role.kubernetes.io/master")


def normalize(document, component):
    """Normalize only the Deployment in a verified Mold ISO, before API apply."""
    if component not in TARGETS:
        raise ValueError("unsupported component")
    result = copy.deepcopy(document)
    items = result.get("items") if result.get("kind") == "List" else [result]
    if not isinstance(items, list):
        raise ValueError("invalid resource list")
    name, container_name, prefixes = TARGETS[component]
    matches = [x for x in items if x.get("kind") == "Deployment"
               and x.get("metadata", {}).get("name") == name
               and x.get("metadata", {}).get("namespace") == "kube-system"]
    if len(matches) != 1 or matches[0].get("apiVersion") != "apps/v1":
        raise ValueError("managed Deployment identity mismatch")
    spec = matches[0]["spec"]["template"]["spec"]
    containers = [x for x in spec.get("containers", []) if x.get("name") == container_name]
    if len(containers) != 1 or not any(containers[0].get("image", "").startswith(p) for p in prefixes):
        raise ValueError("managed image identity mismatch")
    if component == "CCM" and spec.get("serviceAccountName") != "cloud-controller-manager":
        raise ValueError("managed service account identity mismatch")
    tolerations = spec.setdefault("tolerations", [])
    if not isinstance(tolerations, list):
        raise ValueError("invalid tolerations")
    for key in CONTROL_TAINTS:
        covered = any(t.get("key", "") in (key, "")
                      and t.get("operator", "Equal") == "Exists"
                      and t.get("effect", "") in ("", "NoSchedule") for t in tolerations)
        if not covered:
            tolerations.append({"key": key, "operator": "Exists", "effect": "NoSchedule"})
    return result


def cli(manifest, component):
    kubectl = [os.environ.get("MOLD_KUBECTL", "/opt/bin/kubectl"),
               "--kubeconfig=/etc/kubernetes/admin.conf", "--request-timeout=20s"]
    try:
        # The immutable source file is not edited. Secret/config content stays in memory.
        dry = subprocess.run(kubectl + ["create", "--dry-run=client", "-f", manifest, "-o", "json"],
                             capture_output=True, text=True, timeout=30)
        if dry.returncode:
            raise subprocess.CalledProcessError(dry.returncode, kubectl, stderr=dry.stderr)
        normalized = normalize(json.loads(dry.stdout), component)
        applied = subprocess.run(kubectl + ["apply", "-f", "-"], input=json.dumps(normalized),
                                 capture_output=True, text=True, timeout=30)
        if applied.returncode:
            raise subprocess.CalledProcessError(applied.returncode, kubectl, stderr=applied.stderr)
    except subprocess.CalledProcessError as error:
        raw = error.stderr or ""
        transient = ("Unable to connect to the server:", "ServiceUnavailable", "TooManyRequests",
                     "context deadline exceeded", "TLS handshake timeout", "connection reset by peer", "EOF")
        reason = "Unable to connect to the server: managed addon transport" if any(x in raw for x in transient) else "(Invalid): managed addon apply failed"
        if any(x in raw for x in ("(Forbidden)", "(Unauthorized)", "You must be logged in")):
            reason = "(Forbidden): managed addon apply denied"
        print(reason, file=sys.stderr)
        return 1
    except subprocess.TimeoutExpired:
        print("Unable to connect to the server: managed addon timeout", file=sys.stderr)
        return 1
    except (ValueError, KeyError, TypeError, AttributeError, OSError):
        print("(Invalid): managed addon identity or manifest rejected", file=sys.stderr)
        return 1
    print("MOLD_MANAGED_ADDON_APPLIED component=" + component)
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(2)
    sys.exit(cli(sys.argv[1], sys.argv[2]))
