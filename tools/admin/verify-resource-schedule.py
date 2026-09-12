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

"""Verify new schedule API discovery and both new/legacy read paths."""
import argparse
import http.cookiejar
import json
import os
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8080/client/api')
    parser.add_argument('--user', default='admin')
    parser.add_argument('--resource-id', required=True)
    parser.add_argument('--disabled-crud-test', action='store_true',
                        help='Explicitly create/update/delete one disabled schedule starting in 2099')
    parser.add_argument('--cleanup-test-id', help='Remove only a specified disabled verification fixture')
    args = parser.parse_args()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    session = None

    def api(command, **params):
        params.update(command=command, response='json')
        if session:
            params['sessionkey'] = session
        request = urllib.request.Request(args.url, urllib.parse.urlencode(params).encode())
        try:
            with opener.open(request, timeout=60) as response:
                payload = json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f'{command}: HTTP {error.code}') from None
        result = payload.get(command.lower() + 'response')
        if result is None or 'errorcode' in result:
            raise RuntimeError(command + ': API rejected request')
        return result

    schedule_id = None
    try:
        session = api('login', username=args.user, password=os.environ['CLOUD_API_PASSWORD'])['sessionkey']
        names = {item['name'] for item in api('listApis').get('api', [])}
        required = {action + kind + 'Schedule' for action in ('create', 'list', 'update', 'delete') for kind in ('Resource', 'VM')}
        missing = required - names
        if missing:
            raise RuntimeError('Missing APIs: ' + ', '.join(sorted(missing)))
        print('SCHEDULE_APIS', ','.join(sorted(required)), flush=True)
        scope = {'resourcetype': 'VirtualMachine', 'resourceid': args.resource_id}
        before = api('listResourceSchedule', **scope)
        legacy = api('listVMSchedule', virtualmachineid=args.resource_id)
        print('NEW_LIST', json.dumps(before), flush=True)
        print('LEGACY_LIST', json.dumps(legacy), flush=True)
        if args.cleanup_test_id:
            matches = [item for item in before.get('resourceschedule', []) if item['id'] == args.cleanup_test_id]
            if len(matches) != 1 or matches[0]['enabled'] is not False or not matches[0].get('description', '').startswith('ResourceSchedule deployment verification'):
                raise RuntimeError('Cleanup target is not a disabled verification fixture')
            api('deleteResourceSchedule', **scope, id=args.cleanup_test_id)
            print('TEST_FIXTURE_CLEANED', args.cleanup_test_id, flush=True)
        if args.disabled_crud_test:
            created = api('createResourceSchedule', **scope, description='ResourceSchedule deployment verification (disabled)',
                          schedule='0 3 * * *', timezone='Asia/Seoul', action='START',
                          enabled='false', startdate='2099-01-01 00:00:00')
            schedule_id = created['resourceschedule']['id']
            if created['resourceschedule']['enabled'] is not False:
                raise RuntimeError('Test schedule unexpectedly enabled')
            listed = api('listResourceSchedule', **scope, id=schedule_id)
            if not any(item['id'] == schedule_id and item['enabled'] is False for item in listed.get('resourceschedule', [])):
                raise RuntimeError('Created disabled schedule not readable')
            updated = api('updateResourceSchedule', id=schedule_id, description='ResourceSchedule deployment verification updated (disabled)', enabled='false')
            if updated['resourceschedule']['enabled'] is not False:
                raise RuntimeError('Updated test schedule unexpectedly enabled')
            api('deleteResourceSchedule', **scope, id=schedule_id)
            if api('listResourceSchedule', **scope, id=schedule_id).get('resourceschedule'):
                raise RuntimeError('Test schedule not removed')
            schedule_id = None
            print('DISABLED_CRUD_PASS: create/list/update/delete; no enabled schedule submitted', flush=True)
        print('RESOURCE_SCHEDULE_API_PASS', flush=True)
    finally:
        try:
            if schedule_id and session:
                api('deleteResourceSchedule', resourcetype='VirtualMachine', resourceid=args.resource_id, id=schedule_id)
        finally:
            if session:
                api('logout')


if __name__ == '__main__':
    main()
