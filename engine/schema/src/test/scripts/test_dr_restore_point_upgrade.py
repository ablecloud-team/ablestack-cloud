#!/usr/bin/env python3
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
"""Execute the DR migration section on an isolated MySQL schema.

Requires mysql CLI, CREATE DATABASE/ROUTINE privileges and credentials supplied
through its normal option file or MYSQL_PWD. Run on a disposable test server:
  python3 engine/schema/src/test/scripts/test_dr_restore_point_upgrade.py --user root
No existing database is modified; a random scratch database is removed on exit.
This focused data regression complements full upgrade testing from a DB backup.
"""
import argparse
import pathlib
import re
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user", default="root")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", default="3306")
    args = parser.parse_args()
    resources = pathlib.Path(__file__).resolve().parents[2] / "main/resources/META-INF/db"
    schema = (resources / "schema-42200to42210.sql").read_text()
    # Use the actual ordered migration, including DDL, legacy columns and backfill.
    migration = schema[schema.index("CREATE TABLE IF NOT EXISTS `cloud`.`dr_site`"):
                       schema.index("CREATE TABLE IF NOT EXISTS `cloud`.`dr_event`")]
    database = "test_dr_upgrade_" + uuid.uuid4().hex
    command = ["mysql", "--user=" + args.user, "--host=" + args.host,
               "--port=" + args.port, "--batch", "--skip-column-names"]

    def execute(sql, use_database=True):
        sql = sql.replace("`cloud`.", "`" + database + "`.").replace("'cloud.", "'" + database + ".")
        assert not re.search(r"[`']cloud[`. ]", sql), "Unmapped source schema"
        result = subprocess.run(command + ([database] if use_database else []),
                                input=sql, text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError(result.stderr)
        return result.stdout.strip()

    execute("CREATE DATABASE `" + database + "`;", False)
    try:
        for name in ("data_center", "vm_instance", "user", "async_job", "storage_pool", "volumes"):
            execute("CREATE TABLE `" + name + "` (`id` bigint unsigned PRIMARY KEY) ENGINE=InnoDB;")
        for procedure in ("column", "key", "unique_key"):
            execute((resources / ("procedures/cloud.idempotent_add_" + procedure + ".sql")).read_text())
        execute(migration)
        assert execute("SELECT COUNT(*) FROM dr_restore_point;") == "0"
        execute(migration)
        print("PASS: absent DR tables and repeated empty migration")

        execute("""
INSERT INTO dr_site (id,uuid,name,site_type,hypervisor_type,created)
VALUES (1,'site1','source','LOCAL','KVM',NOW()),(2,'site2','target','REMOTE','KVM',NOW());
INSERT INTO dr_plan (id,uuid,name,source_site_id,target_site_id,direction,created)
VALUES (1,'plan1','legacy plan',1,2,'FORWARD',NOW());
INSERT INTO dr_run (id,uuid,plan_id,run_type,created,removed)
VALUES (10,'run10',1,'SYNC',NOW(),NULL),(20,'run20',1,'SYNC',NOW(),NULL),
       (30,'run30',1,'SYNC',NOW(),NOW());
ALTER TABLE dr_restore_point DROP INDEX uk_dr_restore_point__plan_checkpoint_hash,
DROP COLUMN run_id, DROP COLUMN checkpoint_sequence, DROP COLUMN checkpoint_cycle_type,
DROP COLUMN checkpoint_ref_hash;
INSERT INTO dr_restore_point (id,uuid,plan_id,source_snapshot_ref,created,removed)
VALUES (1,'rp1',1,'ftctl:legacy:1',NOW(),NULL),(2,'rp2',1,'ftctl:legacy:1',NOW(),NULL),
       (3,'rp3',1,'ftctl:legacy:2',NOW(),NULL),(4,'rp4',1,NULL,NOW(),NULL),
       (5,'rp5',1,'ftctl:removed:1',NOW(),NOW());
""")
        execute(migration)
        expected = "2\t20\t1\tfull-seed\t1\n3\t20\t2\tincremental\t1\n4\t20\tNULL\tNULL\t0"
        query = """SELECT id,run_id,checkpoint_sequence,checkpoint_cycle_type,
checkpoint_ref_hash IS NOT NULL FROM dr_restore_point WHERE removed IS NULL ORDER BY id;"""
        assert execute(query) == expected
        assert execute("SELECT COUNT(*) FROM dr_restore_point;") == "5"
        assert execute("SELECT removed IS NOT NULL AND checkpoint_ref_hash IS NULL FROM dr_restore_point WHERE id=1;") == "1"
        assert execute("SELECT run_id IS NULL FROM dr_restore_point WHERE id=5;") == "1"
        print("PASS: legacy missing columns, latest active run, checkpoints and duplicate soft deletion")
        execute("UPDATE dr_restore_point SET run_id=10 WHERE id=3;")
        expected = expected.replace("3\t20", "3\t10")
        execute(migration)
        assert execute(query) == expected
        assert execute("SELECT COUNT(*) FROM dr_restore_point;") == "5"
        print("PASS: retry preserves existing run association and all records")
    finally:
        execute("DROP DATABASE `" + database + "`;", False)


if __name__ == "__main__":
    main()
