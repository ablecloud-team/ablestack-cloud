#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with this
# work for additional information regarding copyright ownership. The ASF
# licenses this file to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations.
"""Audit/recover the legacy policy ID 8 collision; never infer types from names.

Run on the management server using its local Cloud DB. Default: read-only plan.
Apply requires that exact plan's SHA256, locks/revalidates every eligible row,
and changes only snapshot_type/type_description plus one hidden audit detail.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re

COMMAND = 'org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotCmd'
TYPES = {0: 'HOURLY', 1: 'DAILY', 2: 'WEEKLY', 3: 'MONTHLY'}
AUDIT_KEY = 'snapshot.type.recovery.1493'
SNAPSHOT_FIELDS = ('id,uuid,volume_id,account_id,domain_id,snapshot_type,'
                   'type_description,status,created,removed')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), default=str)


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def assess(snapshot, volume, policies, jobs, details, archives):
    result = {'snapshot': snapshot, 'action': 'skip'}

    def skip(reason):
        return {**result, 'reason': reason}

    if snapshot['snapshot_type'] != 8 or snapshot['type_description'] != 'INTERNAL':
        return skip('not-legacy-INTERNAL')
    if details.get('snapshot.creation.intent') in ('INTERNAL', 'MANUAL'):
        return skip('explicit-non-policy-intent')
    if snapshot['status'] not in ('BackedUp', 'Destroyed'):
        return skip('not-terminal')
    if not volume or any(snapshot[k] != volume[k] for k in ('account_id', 'domain_id')):
        return skip('missing-or-mismatched-volume-owner')
    proofs = []
    for job in jobs:
        params = job['parameters']
        if (job['command'] != COMMAND or job['status'] != 1 or job['instance_type'] != 'Snapshot'
                or job['instance_id'] != snapshot['id'] or str(params.get('id')) != str(snapshot['id'])
                or str(params.get('ctxAccountId')) != str(job['account_id'])
                or str(params.get('volumeid')) not in (str(volume['id']), volume['uuid'])):
            continue
        for policy in policies + archives:
            if policy['id'] != 8 or policy['interval'] not in TYPES:
                continue
            if (policy['volume_id'] != volume['id'] or policy['account_id'] != snapshot['account_id']
                    or policy['domain_id'] != snapshot['domain_id']):
                continue
            identifier = str(params.get('policyid'))
            if identifier not in ('8', policy['uuid']):
                continue
            archived = policy in archives
            if archived:
                if (policy.get('volume_uuid') != volume['uuid']
                        or not re.fullmatch('[0-9a-f]{64}', policy.get('evidence_sha256', ''))):
                    continue
                # A numeric scheduler parameter cannot identify a deleted/reused policy alone.
                if identifier == '8' and job['uuid'] not in policy.get('creation_jobs', []):
                    continue
            interval = TYPES[policy['interval']]
            if details.get('snapshot.policy.interval', interval) != interval:
                continue
            if details.get('snapshot.policy.uuid', policy['uuid']) != policy['uuid']:
                continue
            proofs.append({'job': job, 'policy': policy, 'archived': archived,
                           'target_type': policy['interval'] + 3, 'target_description': interval})
    if not proofs:
        return skip('insufficient-policy-and-successful-creation-job-evidence')
    if len({(p['target_type'], p['policy']['uuid']) for p in proofs}) != 1:
        return skip('ambiguous-policy-evidence')
    proof = sorted(proofs, key=lambda p: (p['archived'], p['job']['id']))[0]
    return {**result, 'action': 'repair', 'volume': volume, 'details': details, **proof}


def fetch(cursor, snapshot_uuid, archives, lock=False):
    suffix = ' FOR UPDATE' if lock else ''
    cursor.execute('SELECT ' + SNAPSHOT_FIELDS + ' FROM snapshots WHERE uuid=%s' + suffix, (snapshot_uuid,))
    snapshot = cursor.fetchone()
    if not snapshot:
        raise ValueError('Snapshot no longer exists: ' + snapshot_uuid)
    cursor.execute('SELECT id,uuid,account_id,domain_id FROM volumes WHERE id=%s' + suffix, (snapshot['volume_id'],))
    volume = cursor.fetchone()
    cursor.execute('SELECT name,value FROM snapshot_details WHERE snapshot_id=%s ORDER BY id' + suffix, (snapshot['id'],))
    rows = cursor.fetchall()
    details = {r['name']: r['value'] for r in rows}
    if len(details) != len(rows):
        raise ValueError('Duplicate snapshot detail keys require manual review: ' + snapshot_uuid)
    cursor.execute('SELECT id,uuid,volume_id,account_id,domain_id,`interval` FROM snapshot_policy WHERE id=8' + suffix)
    policies = list(cursor.fetchall())
    cursor.execute('SELECT id,uuid,account_id,instance_id,instance_type,job_cmd,job_status,job_cmd_info '
                   'FROM async_job WHERE instance_id=%s AND job_cmd=%s ORDER BY id' + suffix, (snapshot['id'], COMMAND))
    jobs = []
    for row in cursor.fetchall():
        try:
            params = json.loads(row['job_cmd_info'])
            if not isinstance(params, dict):
                continue
        except (ValueError, TypeError):
            continue
        jobs.append({'id': row['id'], 'uuid': row['uuid'], 'account_id': row['account_id'],
                     'instance_id': row['instance_id'], 'instance_type': row['instance_type'],
                     'command': row['job_cmd'], 'status': row['job_status'],
                     'parameters': {k: params[k] for k in ('id', 'volumeid', 'policyid', 'ctxAccountId') if k in params},
                     'command_sha256': hashlib.sha256(row['job_cmd_info'].encode()).hexdigest()})
    return snapshot, volume, policies, jobs, details


def audit(connection, archives, snapshot_uuids=None):
    with connection.cursor() as cursor:
        if snapshot_uuids is None:
            cursor.execute('SELECT uuid FROM snapshots WHERE snapshot_type=8 ORDER BY id')
            snapshot_uuids = [r['uuid'] for r in cursor.fetchall()]
        entries = []
        for uuid in snapshot_uuids:
            snapshot, volume, policies, jobs, details = fetch(cursor, uuid, archives)
            entries.append(assess(snapshot, volume, policies, jobs, details, archives))
    connection.rollback()  # A read-only audit never commits anything.
    return {'version': 1, 'archive_evidence': archives, 'entries': entries}


def apply(connection, plan, plan_sha):
    if digest(plan) != plan_sha or plan.get('version') != 1:
        raise ValueError('Reviewed plan SHA256/version does not match')
    changed, already = [], []
    try:
        connection.begin()
        with connection.cursor() as cursor:
            pending = []
            for entry in sorted(plan['entries'], key=lambda e: e['snapshot']['id']):
                if entry['action'] != 'repair':
                    continue
                snapshot, volume, policies, jobs, details = fetch(cursor, entry['snapshot']['uuid'], plan['archive_evidence'], True)
                previous = json.loads(details.get(AUDIT_KEY, '{}'))
                if (snapshot['snapshot_type'] == entry['target_type'] and snapshot['type_description'] == entry['target_description']
                        and previous.get('plan') == plan_sha):
                    already.append(snapshot['uuid'])
                    continue
                current = assess(snapshot, volume, policies, jobs, details, plan['archive_evidence'])
                if canonical(current) != canonical(entry):
                    raise ValueError('Evidence changed since review: ' + snapshot['uuid'])
                pending.append(entry)
            for entry in pending:
                snapshot = entry['snapshot']
                cursor.execute('UPDATE snapshots SET snapshot_type=%s,type_description=%s WHERE id=%s AND snapshot_type=8 AND type_description=%s',
                               (entry['target_type'], entry['target_description'], snapshot['id'], 'INTERNAL'))
                if cursor.rowcount != 1:
                    raise ValueError('Snapshot changed during apply: ' + snapshot['uuid'])
                value = canonical({'plan': plan_sha, 'job': entry['job']['id'], 'policy': entry['policy']['uuid'],
                                   'from': 8, 'to': entry['target_type']})
                cursor.execute('INSERT INTO snapshot_details (snapshot_id,name,value,display) VALUES (%s,%s,%s,0)',
                               (snapshot['id'], AUDIT_KEY, value))
                changed.append(snapshot['uuid'])
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    return {'changed': changed, 'already_applied': already, 'plan_sha256': plan_sha}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--socket', default='/var/lib/mysql/mysql.sock')
    parser.add_argument('--user', default='root')
    parser.add_argument('--database', default='cloud')
    parser.add_argument('--archive-policy-evidence', type=Path)
    parser.add_argument('--snapshot', action='append', help='Explicit snapshot UUID; repeat to audit several')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--apply', type=Path, help='Apply a reviewed JSON plan')
    parser.add_argument('--sha256', help='The canonical plan SHA256 printed by the read-only audit')
    args = parser.parse_args()
    if args.apply and (not args.sha256 or args.archive_policy_evidence or args.snapshot):
        parser.error('--apply requires --sha256; archive/snapshot inputs are taken only from the reviewed plan')
    import pymysql
    connection = pymysql.connect(user=args.user, password=os.environ.get('MYSQL_PWD'), unix_socket=args.socket,
                                 database=args.database, charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)
    try:
        if args.apply:
            plan = json.loads(args.apply.read_text(encoding='utf8'))
            result = apply(connection, plan, args.sha256)
        else:
            archives = json.loads(args.archive_policy_evidence.read_text(encoding='utf8')) if args.archive_policy_evidence else []
            plan = audit(connection, archives, args.snapshot)
            result = {'plan_sha256': digest(plan), 'plan': plan}
        output = json.dumps(result, ensure_ascii=False, indent=2, default=str) + '\n'
        if args.output:
            # Audit output wrapper is convenient for reading; --apply consumes the inner plan.
            args.output.write_text(json.dumps(result.get('plan', result), ensure_ascii=False, indent=2, default=str) + '\n', encoding='utf8')
            args.output.chmod(0o600)
        print(output)
    finally:
        connection.close()


if __name__ == '__main__':
    main()
