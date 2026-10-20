#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Harmless sandbox-only write-open probe; never imports a Native producer."""
import argparse
import errno
import json
import os
from pathlib import Path
import sys

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--owned-test-root',type=Path,required=True)
    parser.add_argument('--repository-root',type=Path,required=True)
    parser.add_argument('--checked-postconditions',required=True)
    args=parser.parse_args()
    checked=json.loads(args.checked_postconditions)
    if not isinstance(checked,list) or not all(isinstance(label,str) for label in checked):raise ValueError('GUARD_CHECK_LABELS_REQUIRED')
    request=json.loads(sys.stdin.buffer.read(4097))
    if type(request) is not dict or set(request)!={'outsideSentinel'}:
        raise ValueError('EXACT_SAFETY_PROBE_REQUEST_REQUIRED')
    outside=Path(request['outsideSentinel'])
    if not outside.is_absolute() or outside.resolve(strict=True)!=outside or not outside.is_file():
        raise ValueError('REAL_OUTSIDE_SENTINEL_REQUIRED')
    paths=[Path('/etc')/name for name in ('passwd','group','shadow','gshadow')]
    paths += [outside,args.repository_root/'pom.xml']
    observed=[]
    for path in paths:
        blocked=False;number=None
        try:
            fd=os.open(path,os.O_WRONLY|os.O_NOFOLLOW|os.O_CLOEXEC)
        except OSError as failure:
            blocked=failure.errno in (errno.EROFS,errno.EACCES,errno.EPERM)
            number=failure.errno
        else:
            os.close(fd)  # No truncation, write or unlink even if the safety guard is broken.
        observed.append({'path':str(path),'writeOpenBlocked':blocked,'errno':number})
    writes=[]
    for path in (args.owned_test_root/'sandbox-owned-root-write.txt',args.owned_test_root/'native/sandbox-owned-native-write.txt'):
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW|os.O_CLOEXEC,0o600)
        try:
            os.write(fd,b'owned sandbox write only\n');os.fsync(fd)
        finally:os.close(fd)
        writes.append({'path':str(path),'writeSucceeded':True})
    namespaces={name:os.readlink('/proc/self/ns/'+name) for name in ('mnt','pid','net','user')}
    success=all(row['writeOpenBlocked'] for row in observed)
    print(json.dumps({'success':success,'sandboxSafetyProbe':True,'protectedWriteOpens':observed,
                      'ownedCaseWrites':writes,'namespaces':namespaces,'checkedPostconditions':checked,'nativeProductImported':False,
                      'uid':os.geteuid(),'gid':os.getegid(),'ownedRootUid':args.owned_test_root.stat().st_uid,
                      'ownedRootGid':args.owned_test_root.stat().st_gid,
                      'operatorEnvironmentAbsent':not any(name.startswith('ISSUE909_') for name in os.environ)}))
    return 0 if success else 1

if __name__=='__main__':
    sys.exit(main())
