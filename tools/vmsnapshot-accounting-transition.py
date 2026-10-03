# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
"""Audit and transition legacy nominal internal COW counters on the management host.

dry-run is read-only. apply/rollback require Mold stopped, and a saved audit file.
Credentials belong in a protected MySQL defaults file, never in the audit output.
"""
import argparse
import datetime
import json
import pathlib
import subprocess
import uuid

VERSION = "kvm-internal-cow-v1"
KEY = "vmsnapshot.accounting.version"
LEGACY = "vmsnapshot.accounting.legacy-chain-bytes"
AUDIT = "vmsnapshot.accounting.audit-id"

QUERY = """
SELECT v.id,v.uuid,v.pool_id,v.instance_id,v.size,COALESCE(v.vm_snapshot_chain_size,0),
 COALESCE(d.value,''),COALESCE(a.value,''),COALESCE(l.value,''),
 COALESCE((SELECT SHA2(GROUP_CONCAT(CONCAT_WS(':',s.id,s.state,s.vm_snapshot_type,
  COALESCE(s.parent,0),s.current,COALESCE(s.removed,'')) ORDER BY s.id),256)
  FROM vm_snapshots s WHERE s.vm_id=v.instance_id),'none')
FROM volumes v JOIN storage_pool p ON p.id=v.pool_id
 JOIN vm_instance vm ON vm.id=v.instance_id
 LEFT JOIN volume_details d ON d.volume_id=v.id AND d.name='vmsnapshot.accounting.version'
 LEFT JOIN volume_details a ON a.volume_id=v.id AND a.name='vmsnapshot.accounting.audit-id'
 LEFT JOIN volume_details l ON l.volume_id=v.id AND l.name='vmsnapshot.accounting.legacy-chain-bytes'
WHERE v.removed IS NULL AND v.state<>'Destroy' AND p.removed IS NULL
 AND p.pool_type='SharedMountPoint' AND p.managed=0
 AND vm.hypervisor_type='KVM' AND v.format='QCOW2'
ORDER BY v.id;
"""


def sql_text(value):
    return "CONVERT(0x" + str(value).encode("utf-8").hex() + " USING utf8mb4)"


def mysql(defaults, sql):
    result = subprocess.run(["mysql", "--defaults-extra-file=" + str(defaults),
                             "--batch", "--raw", "--skip-column-names", "cloud"],
                            input="SET SESSION group_concat_max_len=16777216;\n" + sql,
                            text=True, capture_output=True, check=True)
    return result.stdout


def inventory(defaults):
    rows = []
    for line in mysql(defaults, QUERY).splitlines():
        values = line.split("\t")
        rows.append(dict(zip(["id", "uuid", "pool_id", "vm_id", "size", "chain_bytes",
                              "version", "audit_id", "legacy_bytes", "snapshot_fingerprint"], values)))
    for row in rows:
        for field in ["id", "pool_id", "vm_id", "size", "chain_bytes"]:
            row[field] = int(row[field])
    return rows


def plan(rows):
    candidates = [row for row in rows if row["chain_bytes"] > 0 and not row["version"]]
    if any(row.get("legacy_bytes") or row.get("audit_id") for row in candidates):
        raise ValueError("Unversioned accounting provenance exists; reconcile it before planning")
    pools = {}
    for row in candidates:
        key = str(row["pool_id"])
        pools[key] = pools.get(key, 0) + row["chain_bytes"]
    return {"policy": VERSION, "audit_id": str(uuid.uuid4()),
            "created": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "excluded_nominal_bytes_by_pool": pools, "volumes": candidates}


def verify_rows(audit, current, mode):
    by_id = {row["id"]: row for row in current}
    changed = []
    for before in audit["volumes"]:
        after = by_id.get(before["id"])
        if not after or any(after[key] != before[key] for key in
                            ["uuid", "pool_id", "vm_id", "size", "snapshot_fingerprint"]):
            raise ValueError("Volume identity, pool, size or snapshot metadata changed: " + str(before["id"]))
        already = after["chain_bytes"] == 0 and after["version"] == VERSION and after["audit_id"] == audit["audit_id"]
        if mode == "apply" and already:
            continue
        if mode == "apply" and (after["chain_bytes"] != before["chain_bytes"] or after["version"]):
            raise ValueError("Counter or accounting version changed: " + str(before["id"]))
        if mode == "rollback" and not already:
            # A repeated rollback is a harmless no-op only for the exact original row.
            if after["chain_bytes"] == before["chain_bytes"] and not after["version"] and not after["audit_id"]:
                continue
            raise ValueError("Rollback would overwrite later accounting: " + str(before["id"]))
        changed.append(before)
    return changed


def mutation_sql(audit, rows, mode):
    statements = ["START TRANSACTION;"]
    for row in rows:
        vid = int(row["id"])
        statements.append("SELECT id FROM volumes WHERE id=%d FOR UPDATE;" % vid)
        if mode == "apply":
            for name, value in [(LEGACY, row["chain_bytes"]), (KEY, VERSION), (AUDIT, audit["audit_id"])]:
                statements.append("INSERT INTO volume_details(volume_id,name,value,display) "
                                  "SELECT v.id,%s,%s,0 FROM volumes v WHERE v.id=%d AND v.vm_snapshot_chain_size=%d "
                                  "AND NOT EXISTS(SELECT 1 FROM volume_details d WHERE d.volume_id=v.id AND d.name=%s) "
                                  "AND NOT EXISTS(SELECT 1 FROM volume_details a WHERE a.volume_id=v.id AND a.name=%s);" %
                                  (sql_text(name), sql_text(value), vid, row["chain_bytes"], sql_text(name), sql_text(AUDIT)))
            statements.append("UPDATE volumes v SET vm_snapshot_chain_size=0 WHERE v.id=%d AND vm_snapshot_chain_size=%d "
                              "AND EXISTS(SELECT 1 FROM volume_details a WHERE a.volume_id=v.id AND a.name=%s AND a.value=%s);" %
                              (vid, row["chain_bytes"], sql_text(AUDIT), sql_text(audit["audit_id"])))
        else:
            statements.append("UPDATE volumes v SET vm_snapshot_chain_size=%d WHERE v.id=%d AND vm_snapshot_chain_size=0 "
                              "AND EXISTS(SELECT 1 FROM volume_details a WHERE a.volume_id=v.id AND a.name=%s AND a.value=%s);" %
                              (row["chain_bytes"], vid, sql_text(AUDIT), sql_text(audit["audit_id"])))
            statements.append("DELETE d FROM volume_details d JOIN volume_details a ON a.volume_id=d.volume_id "
                              "WHERE d.volume_id=%d AND d.name IN(%s,%s,%s) AND a.name=%s AND a.value=%s;" %
                              (vid, sql_text(KEY), sql_text(LEGACY), sql_text(AUDIT), sql_text(AUDIT), sql_text(audit["audit_id"])))
    statements.append("COMMIT;")
    return "\n".join(statements)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["dry-run", "apply", "rollback"])
    parser.add_argument("--mysql-defaults-file", type=pathlib.Path, required=True)
    parser.add_argument("--audit", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.mysql_defaults_file.stat().st_mode & 0o077:
        parser.error("MySQL defaults file must be private (chmod 600)")
    if args.mode == "dry-run":
        if args.audit.exists():
            parser.error("Choose a new audit path; existing provenance will not be overwritten")
        audit = plan(inventory(args.mysql_defaults_file))
        args.audit.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"mode": args.mode, "volumes": len(audit["volumes"]),
                          "excluded_nominal_bytes_by_pool": audit["excluded_nominal_bytes_by_pool"]}))
        return
    state = subprocess.run(["systemctl", "is-active", "mold"], capture_output=True, text=True)
    if state.stdout.strip() != "inactive":
        parser.error("Stop Mold before a counter transition or rollback")
    audit = json.loads(args.audit.read_text(encoding="utf-8"))
    if audit.get("policy") != VERSION:
        parser.error("Unknown accounting policy")
    # Fail closed when another snapshot operation is still unfinished.
    vm_ids = sorted({int(row["vm_id"]) for row in audit["volumes"]})
    if vm_ids:
        pending = mysql(args.mysql_defaults_file, "SELECT COUNT(*) FROM vm_snapshots WHERE removed IS NULL "
                        "AND state IN('Allocated','Creating','Reverting','Expunging') AND vm_id IN(%s);" %
                        ",".join(map(str, vm_ids)))
        if int(pending.strip()):
            parser.error("Unfinished snapshot operations exist; preserve them and retry after reconciliation")
    rows = verify_rows(audit, inventory(args.mysql_defaults_file), args.mode)
    if rows:
        mysql(args.mysql_defaults_file, mutation_sql(audit, rows, args.mode))
    # Verification also makes an interrupted apply/rollback safely repeatable.
    if verify_rows(audit, inventory(args.mysql_defaults_file), args.mode):
        raise RuntimeError("Accounting transition did not reach the expected state")
    print(json.dumps({"mode": args.mode, "audit_id": audit["audit_id"], "changed_volumes": len(rows)}))


if __name__ == "__main__":
    main()
