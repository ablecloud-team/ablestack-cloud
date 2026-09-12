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

"""Exercise S3 authentication migrations on the explicitly named disposable MySQL fixture.

Requires the compiled schema module, mysql client, Java 17 and a Maven dependency
classpath file. It never loads the repository's development DB configuration.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

DIRECTORY = Path(__file__).resolve().parent
REPOSITORY = DIRECTORY.parents[3]
MARKER = "epic991-disposable"


def sql(statement):
    environment = dict(os.environ, MYSQL_PWD="epic991-disposable-fixture")
    return subprocess.check_output([
        "mysql", "--no-defaults", "--host=epic991-auth-db", "--port=3306",
        "--user=root", "--batch", "--skip-column-names", "--execute", statement,
    ], env=environment, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", required=True, type=Path)
    args = parser.parse_args()
    classpath = str(REPOSITORY / "engine/schema/target/classes") + ":" + args.classpath_file.read_text().strip()
    with tempfile.TemporaryDirectory(prefix="epic991-schema-") as output:
        subprocess.run(["javac", "-cp", classpath, "-d", output,
                        str(REPOSITORY / "tools/build/EuropaSecuritySchemaSmoke.java")], check=True)
        for mode in ("baseline", "fresh", "partial"):
            exists = sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='cloud'")
            if exists == "1":
                if sql("SELECT marker FROM cloud.epic991_fixture_guard") != MARKER:
                    raise RuntimeError("Refusing to reset a database without the disposable fixture marker")
                sql("DROP DATABASE cloud")
            sql((DIRECTORY / "auth-schema-fixture.sql").read_text())
            if mode == "fresh":
                sql("""ALTER TABLE cloud.api_keypair MODIFY description VARCHAR(1024) DEFAULT NULL;
                    ALTER TABLE cloud.oauth_provider ADD authorize_url VARCHAR(255),
                        ADD token_url VARCHAR(255), ADD domain_id BIGINT UNSIGNED DEFAULT NULL,
                        ADD INDEX i_oauth_provider__domain_id(domain_id),
                        ADD UNIQUE INDEX uk_oauth_provider__provider_domain(provider,domain_id),
                        ADD CONSTRAINT fk_oauth_provider__domain_id FOREIGN KEY(domain_id) REFERENCES cloud.domain(id);""")
            subprocess.run(["java", "-cp", output + ":" + classpath,
                            "EuropaSecuritySchemaSmoke", mode], check=True)


if __name__ == "__main__":
    main()
