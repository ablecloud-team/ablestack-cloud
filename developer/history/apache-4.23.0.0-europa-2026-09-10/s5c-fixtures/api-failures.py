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


import pymysql, uuid, base64, secrets, subprocess, os

r = Path("/tmp/epic996")
c = pymysql.connect(
    host="epic996-mysql-ddl", user="root", password="epic996-fixture-dummy", database="cloud", autocommit=True
)
q = c.cursor()
suffix = uuid.uuid4().hex[:8]
off = json.loads((r / "api-offering.json").read_text())
vol = wait(api("createVolume", name="S5C-rollback-" + suffix, diskofferingid=off["id"], zoneid=zone["id"]))["volume"]
secret = base64.b64encode(secrets.token_bytes(48))
q.execute("INSERT INTO passphrase(passphrase) VALUES(%s)", (secret.decode(),))
passphrase = q.lastrowid
q.execute("UPDATE volumes SET passphrase_id=%s,encrypt_format='luks' WHERE uuid=%s", (passphrase, vol["id"]))
q.execute("SELECT id FROM volumes WHERE uuid=%s", (vol["id"],))
volume_id = q.fetchone()[0]
q.execute("SELECT COUNT(*) FROM kms_wrapped_key")
before_count = q.fetchone()[0]
q.execute(
    "CREATE TRIGGER s5c_reject_key BEFORE UPDATE ON volumes FOR EACH ROW BEGIN IF NEW.id="
    + str(volume_id)
    + " AND NEW.kms_wrapped_key_id IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='S5C injected reference write failure'; END IF; END"
)
try:
    try:
        wait(api("migrateVolumesToKMS", volumeids=vol["id"], kmskeyid=key["id"]))
        raise AssertionError("DB failure falsely reported success")
    except RuntimeError as e:
        assert "Failures: 1" in str(e), str(e)
    q.execute("SELECT passphrase_id,kms_key_id,kms_wrapped_key_id FROM volumes WHERE id=%s", (volume_id,))
    assert q.fetchone() == (passphrase, None, None)
    q.execute("SELECT COUNT(*) FROM kms_wrapped_key")
    assert q.fetchone()[0] == before_count
finally:
    q.execute("DROP TRIGGER IF EXISTS s5c_reject_key")
profile = api("listHSMProfiles", id=key["hsmprofileid"], listall="true")["hsmprofile"][0]
api("updateHSMProfile", id=profile["id"], enabled="false")
try:
    try:
        wait(api("migrateVolumesToKMS", volumeids=vol["id"], kmskeyid=key["id"]))
        raise AssertionError("Offline HSM falsely reported success")
    except RuntimeError as e:
        assert "not enabled" in str(e), str(e)
    q.execute("SELECT passphrase_id,kms_wrapped_key_id FROM volumes WHERE id=%s", (volume_id,))
    assert q.fetchone() == (passphrase, None)
finally:
    api("updateHSMProfile", id=profile["id"], enabled="true")
api("updateKMSKey", id=key["id"], enabled="false")
try:
    try:
        wait(api("createVolume", name="S5C-denied", diskofferingid=off["id"], zoneid=zone["id"], kmskeyid=key["id"]))
        raise AssertionError("Disabled KMS key accepted")
    except RuntimeError as e:
        assert any(x in str(e).lower() for x in ["not enabled", "permission", "disabled"]), str(e)
finally:
    api("updateKMSKey", id=key["id"], enabled="true")
(r / "payload.raw").write_bytes(bytes(range(256)) * 16384)
(r / "legacy-data-secret").write_bytes(secret)
(r / "legacy-data-secret").chmod(0o600)
env = dict(os.environ, LD_LIBRARY_PATH="/tmp/epic994/native/usr/lib64")
qemu = "/tmp/epic994/native/usr/bin/qemu-img"
subprocess.run(
    [
        qemu,
        "convert",
        "--object",
        "secret,id=sec0,file=" + str(r / "legacy-data-secret"),
        "-f",
        "raw",
        "-O",
        "qcow2",
        "-o",
        "encrypt.format=luks,encrypt.key-secret=sec0",
        str(r / "payload.raw"),
        str(r / "payload-encrypted.qcow2"),
    ],
    env=env,
    check=True,
)
assert wait(api("migrateVolumesToKMS", volumeids=vol["id"], kmskeyid=key["id"])) == {"success": True}
q.execute("SELECT passphrase FROM passphrase WHERE id=%s", (passphrase,))
assert q.fetchone()[0].encode() == secret
assert wait(api("migrateVolumesToKMS", volumeids=vol["id"], kmskeyid=key["id"])) == {"success": True}
# Both asynchronous rotation requests contend for the same key lock.
jobs = [api("rotateKMSKey", id=key["id"]), api("rotateKMSKey", id=key["id"])]
for job in jobs:
    wait(job)
for _ in range(80):
    q.execute(
        "SELECT v.kms_wrapped_key_id,w.kek_version_id,k.status FROM volumes v JOIN kms_wrapped_key w ON v.kms_wrapped_key_id=w.id JOIN kms_kek_versions k ON w.kek_version_id=k.id WHERE v.id=%s",
        (volume_id,),
    )
    values = q.fetchone()
    if values[2] == "Active":
        break
    time.sleep(0.5)
else:
    raise AssertionError("Background rewrap did not converge")
q.execute(
    'SELECT COUNT(*) FROM kms_kek_versions WHERE kms_key_id=(SELECT id FROM kms_keys WHERE uuid=%s) AND status="Active" AND removed IS NULL',
    (key["id"],),
)
assert q.fetchone()[0] == 1
cmd = json.loads((r / "secret-java-command.json").read_text())
cmd[-2] = vol["id"]
cmd[-1] = str(r / "rewrapped-secret")
with (r / "rewrapped-secret.log").open("w") as f:
    subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, check=True)
assert (r / "rewrapped-secret").read_bytes() == secret
subprocess.run(
    [
        qemu,
        "convert",
        "--object",
        "secret,id=sec0,file=" + str(r / "rewrapped-secret"),
        "--image-opts",
        "driver=qcow2,file.filename=" + str(r / "payload-encrypted.qcow2") + ",encrypt.key-secret=sec0",
        "-O",
        "raw",
        str(r / "payload-recovered.raw"),
    ],
    env=env,
    check=True,
)
assert (r / "payload-recovered.raw").read_bytes() == (r / "payload.raw").read_bytes()
(r / "api-data-volume.json").write_text(json.dumps(vol))
print(
    "PASS: DB write failure rolls back wrapped key and volume references; disabled HSM/key fail closed; repeated migration is safe; concurrent rotations and background rewrap preserve DEK; QEMU 10.1 decrypts all 4 MiB nonzero fixture data"
)
