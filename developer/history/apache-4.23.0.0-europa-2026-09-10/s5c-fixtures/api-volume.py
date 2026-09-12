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

import urllib.request, urllib.parse, json, http.cookiejar
from pathlib import Path

op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
session = None


def api(cmd, **kw):
    if session:
        kw["sessionkey"] = session
    try:
        r = json.load(
            op.open(
                "http://127.0.0.1:18996/client/api",
                data=urllib.parse.urlencode(dict(command=cmd, response="json", **kw)).encode(),
                timeout=60,
            )
        )
        return next(iter(r.values()))
    except urllib.error.HTTPError as e:
        r = json.load(e)
        raise RuntimeError(cmd + ": " + str(next(iter(r.values())).get("errortext")))


session = api("login", username="admin", password="password", domain="/")["sessionkey"]
import time

key = json.loads(Path("/tmp/epic996/api-key.json").read_text())
zone = api("listZones")["zone"][0]


def wait(result):
    if "jobid" not in result:
        return result
    for _ in range(120):
        j = api("queryAsyncJobResult", jobid=result["jobid"])
        if j.get("jobstatus") == 1:
            return j.get("jobresult", {})
        if j.get("jobstatus") == 2:
            raise RuntimeError("job failed: " + str(j.get("jobresult")))
        time.sleep(0.5)
    raise RuntimeError("job timed out")


print("rotation completed", wait(api("rotateKMSKey", id=key["id"])))
off = api(
    "createDiskOffering",
    name="S5C-encrypted",
    displaytext="S5C encryption metadata fixture",
    disksize=1,
    encrypt="true",
)["diskoffering"]
Path("/tmp/epic996/api-offering.json").write_text(json.dumps(off))
vol = wait(api("createVolume", name="S5C-legacy", diskofferingid=off["id"], zoneid=zone["id"]))["volume"]
Path("/tmp/epic996/api-legacy-volume.json").write_text(json.dumps(vol))
print("legacy volume created", vol["state"])
kmsvol = wait(api("createVolume", name="S5C-kms", diskofferingid=off["id"], zoneid=zone["id"], kmskeyid=key["id"]))[
    "volume"
]
Path("/tmp/epic996/api-kms-volume.json").write_text(json.dumps(kmsvol))
print("kms volume created", kmsvol["state"], kmsvol.get("kmskeyid"))
