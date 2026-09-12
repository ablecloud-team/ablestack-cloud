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
profiles = api("listHSMProfiles", listall="true").get("hsmprofile", [])
print("profiles", [(p["name"], p["protocol"]) for p in profiles])
zones = api("listZones").get("zone", [])
print("zones", [(z["id"], z["name"]) for z in zones])
profile = next(p for p in profiles if p["protocol"] == "database")
zone = zones[0]
api("updateHSMProfile", id=profile["id"], enabled="true")
key = api("createKMSKey", name="S5C-runtime", hsmprofileid=profile["id"], zoneid=zone["id"])["kmskey"]
assert key["name"] == "S5C-runtime"
Path("/tmp/epic996/api-key.json").write_text(json.dumps(key))
print("create/list", api("listKMSKeys", id=key["id"])["count"])
api("updateKMSKey", id=key["id"], enabled="false")
api("updateKMSKey", id=key["id"], enabled="true", name="S5C-updated")
print("rotate", list(api("rotateKMSKey", id=key["id"])))
