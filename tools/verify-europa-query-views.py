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
"""Read-only deployment check. Credentials belong in a protected MySQL option file."""
import argparse
import json
import re
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--defaults-extra-file', required=True)
    args = parser.parse_args()
    result = subprocess.run([
        'mysql', '--defaults-extra-file=' + args.defaults_extra_file,
        '--batch', '--skip-column-names', '-e',
        "SELECT table_name,column_name FROM information_schema.columns "
        "WHERE table_schema='cloud' AND table_name LIKE '%view'"
    ], check=True, capture_output=True, text=True)
    actual = {}
    for line in result.stdout.splitlines():
        table, column = line.split('\t')
        actual.setdefault(table, set()).add(column)
    root = Path(__file__).resolve().parents[1]
    sources = list((root / 'server/src/main/java/com/cloud/api/query/vo').glob('*.java'))
    if not sources:
        raise RuntimeError('No query VO sources found; run from a complete source checkout')
    missing = {}
    checked = 0
    for source in sources:
        text = source.read_text()
        table = re.search(r'@Table\(name\s*=\s*"([^"]+)"', text)
        if not table:
            continue
        checked += 1
        expected = set(re.findall(r'@Column\(name\s*=\s*"([^"]+)"', text))
        absent = sorted(expected - actual.get(table[1], set()))
        if absent:
            missing[table[1]] = absent
    print(json.dumps({'checked_views': checked, 'missing_columns': missing}, indent=2))
    return 1 if missing else 0


if __name__ == '__main__':
    raise SystemExit(main())
