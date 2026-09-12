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


import uuid

suffix = uuid.uuid4().hex[:8]
admin_op, admin_session = op, session
root = api("listDomains")["domain"][0]["id"]
profile = api(
    "createHSMProfile",
    name="S5C-private-" + suffix,
    protocol="database",
    **{"details[0].pin": "fixture-pin-never-return"}
)["hsmprofile"]
assert profile["details"]["pin"] == "*****"
assert "fixture-pin-never-return" not in json.dumps(profile)
public = api(
    "createHSMProfile",
    name="S5C-public-" + suffix,
    protocol="database",
    ispublic="true",
    **{"details[0].pin": "fixture-public-pin"}
)["hsmprofile"]
assert public["details"]["pin"] == "*****"
username = "s5c-user-" + suffix
acct = api(
    "createAccount",
    username=username,
    password="S5c!test2026",
    firstname="S5C",
    lastname="Fixture",
    email="s5c@example.invalid",
    accounttype=0,
    domainid=root,
    enable="false",
)["account"]
project = wait(api("createProject", name="S5C-project-" + suffix, displaytext="KMS ACL fixture"))["project"]
api("addAccountToProject", projectid=project["id"], account=acct["name"])
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
session = None
session = api("login", username=username, password="S5c!test2026", domain="/")["sessionkey"]
assert not api("listKMSKeys", id=key["id"]).get("kmskey")
for command in ["updateKMSKey", "deleteKMSKey", "rotateKMSKey"]:
    try:
        wait(api(command, id=key["id"], name="must-not-change"))
        raise AssertionError("Other-account key access allowed")
    except RuntimeError as error:
        assert any(word in str(error).lower() for word in ["access", "permission", "unable", "not found"]), str(error)
try:
    api("createHSMProfile", name="not-admin", protocol="database")
    raise AssertionError("Non-admin HSM create allowed")
except RuntimeError:
    pass
assert not api("listHSMProfiles", id=profile["id"]).get("hsmprofile")
visible = api("listHSMProfiles", ispublic="true", listall="true")["hsmprofile"]
assert all("details" not in item for item in visible)
user_key = api("createKMSKey", name="S5C-own-" + suffix, hsmprofileid=public["id"], zoneid=zone["id"])["kmskey"]
assert user_key["account"] == acct["name"]
assert "keklabel" not in user_key
project_key = api(
    "createKMSKey",
    name="S5C-project-key-" + suffix,
    hsmprofileid=public["id"],
    zoneid=zone["id"],
    projectid=project["id"],
)["kmskey"]
assert project_key["projectid"] == project["id"]
api("updateKMSKey", id=user_key["id"], name="S5C-own-updated-" + suffix)
wait(api("rotateKMSKey", id=user_key["id"]))
api("deleteKMSKey", id=user_key["id"])
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
session = None
session = api("login", username="admin", password="password", domain="/")["sessionkey"]
api("deleteKMSKey", id=project_key["id"])
api("deleteHSMProfile", id=profile["id"])
api("deleteHSMProfile", id=public["id"])
print(
    "PASS: admin HSM details masked; public details hidden; private profile denied; cross-account key list/update/delete/rotate denied; non-admin HSM create denied; user and project key create/rotate/delete"
)
Path("/tmp/epic996/acl-fixture.json").write_text(json.dumps({"account": acct["name"], "project": project["id"]}))
