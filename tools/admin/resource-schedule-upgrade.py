#!/usr/bin/env python3
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

"""Explicit same-version ResourceSchedule migration; never starts management."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

OLD = {'vm_schedule', 'vm_scheduled_job'}
NEW = {'resource_schedule', 'resource_scheduled_job', 'resource_schedule_details'}


def classify(tables):
    present = set(tables) & (OLD | NEW)
    if present == OLD:
        return 'legacy'
    if present == NEW:
        return 'resource'
    raise ValueError('Incomplete or mixed schedule schema; manual recovery required')


def sql(database, query):
    result = subprocess.run(['mysql', '-uroot', '--batch', '--raw', '--skip-column-names', database],
                            input=query, text=True, capture_output=True, check=True)
    return result.stdout.strip()


def fingerprint(database, legacy):
    schedule = 'vm_schedule' if legacy else 'resource_schedule'
    job = 'vm_scheduled_job' if legacy else 'resource_scheduled_job'
    resource_id = 'vm_id' if legacy else 'resource_id'
    schedule_id = 'vm_schedule_id' if legacy else 'schedule_id'
    kind = "'VirtualMachine'" if legacy else 'resource_type'
    queries = {
        'schedules': f'SELECT JSON_ARRAY(id,{resource_id},uuid,{kind},description,schedule,timezone,action,enabled,start_date,end_date,created,removed) FROM {schedule} ORDER BY id',
        'jobs': f'SELECT JSON_ARRAY(id,{resource_id},{schedule_id},uuid,{kind},action,scheduled_timestamp,async_job_id) FROM {job} ORDER BY id',
        'events': "SELECT JSON_ARRAY(id,REPLACE(type,'VM.SCHEDULE.','SCHEDULE.')) FROM event WHERE type IN ('VM.SCHEDULE.CREATE','VM.SCHEDULE.UPDATE','VM.SCHEDULE.DELETE','SCHEDULE.CREATE','SCHEDULE.UPDATE','SCHEDULE.DELETE') ORDER BY id",
        'configuration': "SELECT JSON_ARRAY(value) FROM configuration WHERE name IN ('vmscheduler.jobs.expire.interval','scheduler.jobs.expire.interval') ORDER BY name",
    }
    return {name: hashlib.sha256(sql(database, query).encode()).hexdigest() for name, query in queries.items()}


def validate_resource(database):
    for table, columns in {
        'resource_schedule': {'id', 'resource_id', 'resource_type', 'schedule', 'enabled'},
        'resource_scheduled_job': {'id', 'resource_id', 'resource_type', 'schedule_id', 'scheduled_timestamp'},
        'resource_schedule_details': {'id', 'schedule_id', 'name', 'value', 'display'},
    }.items():
        found = {line.split('\t')[0] for line in sql(database, f'SHOW COLUMNS FROM {table}').splitlines()}
        if not columns <= found:
            raise ValueError('Incomplete columns: ' + table)
    expected = {'i_resource_schedule__resource', 'i_resource_schedule__enabled_end_date',
                'uc_resource_scheduled_job__schedule_timestamp', 'i_resource_scheduled_job__resource',
                'i_resource_scheduled_job__scheduled_timestamp', 'i_resource_schedule_details__schedule_id'}
    indexes = set(sql(database, "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('resource_schedule','resource_scheduled_job','resource_schedule_details')").splitlines())
    if not expected <= indexes:
        raise ValueError('Incomplete resource schedule indexes')
    fks = set(sql(database, "SELECT CONSTRAINT_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND DELETE_RULE='CASCADE' AND REFERENCED_TABLE_NAME='resource_schedule'").splitlines())
    if not {'fk_resource_scheduled_job__schedule_id', 'fk_resource_schedule_details__schedule_id'} <= fks:
        raise ValueError('Incomplete resource schedule foreign keys')
    if sql(database, "SELECT COUNT(*) FROM configuration WHERE name='vmscheduler.jobs.expire.interval'") != '0':
        raise ValueError('Legacy configuration still present')
    if sql(database, "SELECT COUNT(*) FROM configuration WHERE name='scheduler.jobs.expire.interval'") != '1':
        raise ValueError('Resource scheduler configuration missing')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', default='cloud')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup-dir', type=Path)
    parser.add_argument('--management-service', default='mold')
    parser.add_argument('--rehearsal', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*', args.database):
        parser.error('Invalid database name')
    if args.rehearsal and not args.database.startswith('resource_schedule_rehearsal_'):
        parser.error('Rehearsal requires an isolated resource_schedule_rehearsal_ database')
    state = classify(sql(args.database, 'SHOW TABLES').splitlines())
    print('SCHEMA_STATE', state, flush=True)
    if state == 'resource':
        validate_resource(args.database)
        print('ALREADY_MIGRATED: schema validated; no changes')
        return
    if not args.apply:
        print('Migration required; read-only inspection complete')
        return
    if args.backup_dir is None:
        parser.error('--apply requires a new --backup-dir')
    if not args.rehearsal:
        service = subprocess.check_output(['systemctl', 'show', args.management_service, '-p', 'ActiveState', '--value'], text=True).strip()
        if service != 'inactive':
            raise ValueError('Management must be stopped (inactive) before migration')
    if sql(args.database, "SELECT COUNT(*) FROM configuration WHERE name='vmscheduler.jobs.expire.interval'") != '1':
        raise ValueError('Expected exactly one legacy scheduler configuration')
    if sql(args.database, "SELECT COUNT(*) FROM configuration WHERE name='scheduler.jobs.expire.interval'") != '0':
        raise ValueError('Conflicting scheduler configurations')
    if sql(args.database, 'SELECT COUNT(*) FROM vm_scheduled_job j LEFT JOIN vm_schedule s ON s.id=j.vm_schedule_id WHERE s.id IS NULL') != '0':
        raise ValueError('Orphan scheduled jobs; migration refused')
    if sql(args.database, 'SELECT COUNT(*) FROM (SELECT vm_schedule_id,scheduled_timestamp FROM vm_scheduled_job GROUP BY vm_schedule_id,scheduled_timestamp HAVING COUNT(*)>1) duplicates') != '0':
        raise ValueError('Duplicate scheduled jobs; migration refused')
    args.backup_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    os.chmod(args.backup_dir, 0o700)
    # DDL auto-commits: preserve a restorable backup before the first ALTER.
    with (args.backup_dir / 'schedules-configuration.sql').open('wb') as backup:
        subprocess.run(['mysqldump', '-uroot', '--single-transaction', '--set-gtid-purged=OFF', args.database,
                        'vm_schedule', 'vm_scheduled_job', 'configuration'], stdout=backup, check=True)
    with (args.backup_dir / 'schedule-events.sql').open('wb') as backup:
        subprocess.run(['mysqldump', '-uroot', '--single-transaction', '--set-gtid-purged=OFF', '--no-create-info',
                        '--skip-add-locks', '--skip-disable-keys', "--where=type IN ('VM.SCHEDULE.CREATE','VM.SCHEDULE.UPDATE','VM.SCHEDULE.DELETE','SCHEDULE.CREATE','SCHEDULE.UPDATE','SCHEDULE.DELETE')",
                        args.database, 'event'], stdout=backup, check=True)
    before = fingerprint(args.database, True)
    (args.backup_dir / 'before.json').write_text(json.dumps(before, indent=2) + '\n')
    sql(args.database, Path(__file__).with_suffix('.sql').read_text())
    validate_resource(args.database)
    after = fingerprint(args.database, False)
    (args.backup_dir / 'after.json').write_text(json.dumps(after, indent=2) + '\n')
    if before != after:
        raise ValueError('Data fingerprint mismatch; keep management stopped and recover from backup')
    print('MIGRATION_PASS: all schedule/job/event/configuration fingerprints preserved')


if __name__ == '__main__':
    main()
