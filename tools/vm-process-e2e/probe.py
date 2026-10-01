# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
"""Bounded Cloud process acceptance probes; never replay an uncertain mutation."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

RPCS = {"guest-exec", "guest-exec-status", "guest-file-open", "guest-file-close",
        "guest-file-read", "guest-file-write", "guest-file-seek", "guest-file-flush"}
ACTIONS = {"process.terminate": "terminateVirtualMachineProcess",
           "process.kill": "killVirtualMachineProcess",
           "service.restart": "restartVirtualMachineService"}


class ProbeError(Exception):
    pass


class Client:
    def __init__(self, url, username, password):
        self.url = url
        self.session = None
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        result = self.call("login", username=username, password=password)
        self.session = result["sessionkey"]

    def call(self, command, **params):
        data = dict(command=command, response="json", **params)
        if self.session:
            data["sessionkey"] = self.session
        request = urllib.request.Request(self.url,
                    data=urllib.parse.urlencode(data).encode(), method="POST")
        try:
            with self.opener.open(request, timeout=120) as response:
                value = json.load(response)
        except urllib.error.HTTPError as error:
            # Do not expose request URLs, cookies, sessions or error bodies in receipts.
            raise ProbeError("API " + command + " returned HTTP " + str(error.code)) from None
        except (OSError, ValueError):
            raise ProbeError("API " + command + " response unavailable") from None
        payload = next(iter(value.values()))
        if "errorcode" in payload:
            raise ProbeError("API " + command + " rejected request")
        return payload

    def job(self, jobid):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            value = self.call("queryAsyncJobResult", jobid=jobid)
            if value["jobstatus"] == 1:
                return value["jobresult"]
            if value["jobstatus"] == 2:
                raise ProbeError("Async job failed")
            time.sleep(0.5)
        raise ProbeError("Async result unavailable; do not replay the operation")


def test_vm(client, vmid):
    if str(uuid.UUID(vmid)) != vmid:
        raise ProbeError("Canonical VM UUID required")
    rows = client.call("listVirtualMachines", id=vmid).get("virtualmachine", [])
    if len(rows) != 1 or not rows[0]["name"].endswith("Process"):
        raise ProbeError("Only explicitly listed Process test VMs are permitted")
    return rows[0]


def snapshot(client, vmid):
    result = client.call("refreshVirtualMachineProcesses", virtualmachineid=vmid)
    first = client.job(result["jobid"])["processsnapshot"]
    state = first["processstate"]
    if state.get("kind") != "snapshot" or state["status"] != "OK":
        raise ProbeError("Snapshot was not successful")
    identifier = state["snapshotId"]
    rows = []
    for page in range(1, 52):
        value = client.call("listVirtualMachineProcesses", virtualmachineid=vmid,
                            snapshotid=identifier, page=page, pagesize=200)["processsnapshot"]
        if value["processstate"].get("snapshotId") != identifier or value["stale"]:
            raise ProbeError("Mixed snapshot pages")
        rows.extend(value["processstate"].get("processes", []))
        if len(rows) == value["count"]:
            break
        if len(rows) > value["count"]:
            raise ProbeError("Inconsistent process count")
    else:
        raise ProbeError("Process pagination exceeded the configured bound")
    if len({row["identity"]["pid"] for row in rows}) != len(rows):
        raise ProbeError("Duplicate process identity across pages")
    return state, rows


def verify(client, vmid):
    vm = test_vm(client, vmid)
    capability = client.call("getVirtualMachineProcessCapabilities",
                            virtualmachineid=vmid)["processcapability"]["processstate"]
    if capability["readiness"] != "READY":
        raise ProbeError("VM readiness: " + capability["readiness"])
    if set(capability["rpcs"]) != RPCS or any(
            value != "ENABLED" for value in capability["rpcs"].values()):
        raise ProbeError("Required RPCs are not enabled")
    state, rows = snapshot(client, vmid)
    if not rows or not any(isinstance(row.get("cpuPercent"), (float, int)) for row in rows):
        raise ProbeError("No process rows or numeric CPU sample")
    return {"vmUuid": vmid, "name": vm["name"], "readiness": "READY",
            "snapshotId": state["snapshotId"], "processCount": len(rows),
            "numericCpuRows": sum(isinstance(row.get("cpuPercent"), (float, int)) for row in rows),
            "rpcCount": len(RPCS)}


def fixed_action(client, vmid, target):
    test_vm(client, vmid)
    action = target["action"]
    if action not in ACTIONS or int(target["pid"]) < 2:
        raise ProbeError("Invalid disposable action target")
    service = target.get("serviceName")
    if action == "service.restart" and not (
            isinstance(service, str) and service.startswith(("process-c7-", "AbleC7"))):
        raise ProbeError("Only dedicated C7 fixture services may be restarted")
    request_id = str(uuid.uuid4())
    args = dict(virtualmachineid=vmid, snapshotid=target["snapshotId"],
                pid=int(target["pid"]), requestid=request_id)
    if service:
        args["servicename"] = service
    # The request ID is saved before sending. Loss of the response never submits again.
    receipt = {"vmUuid": vmid, "requestId": request_id, "state": "UNKNOWN"}
    path = Path(target["receipt"])
    path.write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    try:
        response = client.call(ACTIONS[action], **args)
        if "jobid" in response:
            client.job(response["jobid"])
    except ProbeError:
        pass
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            result = client.call("getVirtualMachineProcessOperation",
                                 virtualmachineid=vmid, requestid=request_id)["processoperation"]["processstate"]
        except ProbeError:
            time.sleep(1)
            continue
        receipt.update({key: result.get(key) for key in
                        ("operationId", "state", "effect", "postcondition")})
        path.write_text(json.dumps(receipt, indent=2), encoding="utf-8")
        if result["state"] != "UNKNOWN":
            expected = "SERVICE_RESTART_VERIFIED" if action == "service.restart" else "TARGET_EXITED"
            if (result["state"] != "SUCCEEDED" or result["effect"] != "VERIFIED"
                    or result["postcondition"] != expected):
                raise ProbeError("Fixture action failed; inspect its saved request ID")
            return receipt
        time.sleep(1)
    raise ProbeError("Result UNKNOWN; keep the receipt and use read-only reconciliation")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", required=True)
    parser.add_argument("--vm", action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fixture", type=Path,
                        help="Explicit fixed disposable target; exactly one VM required")
    args = parser.parse_args()
    client = Client(args.api, os.environ["CLOUD_USERNAME"], os.environ["CLOUD_PASSWORD"])
    if args.fixture:
        if len(args.vm) != 1:
            parser.error("An action requires exactly one explicit VM")
        results = [fixed_action(client, args.vm[0], json.loads(args.fixture.read_text()))]
    else:
        results = [verify(client, vmid) for vmid in args.vm]
    args.output.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print("PASS", len(results), "explicit test VMs; guest postconditions require independent proof")


if __name__ == "__main__":
    try:
        main()
    except (ProbeError, KeyError, ValueError) as error:
        raise SystemExit("FAIL: " + str(error)) from None
