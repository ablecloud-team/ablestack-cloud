// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import { snapshotDownloadResult } from '@/utils/snapshotDownload'
const good = { url: 'http://ssvm/userdata/token/backup.qcow2', filename: 'backup.qcow2', format: 'QCOW2', size: 1073741824, physicalsize: 2490368 }
test('verified image metadata and safe download URL are accepted', () => { expect(snapshotDownloadResult({ snapshot: good })).toEqual(good) })
test.each([
  { url: 'javascript:alert(1)' }, { url: 'data:text/html,test' }, { url: 'http://user:secret@ssvm/backup.qcow2' },
  { filename: '<img>.qcow2' }, { format: 'RAW' }, { size: 0 }, { physicalsize: null }, { url: good.url + '?token=x' }
])('invalid completion is rejected: %s', change => { expect(snapshotDownloadResult({ snapshot: { ...good, ...change } })).toBeNull() })
test('nested async completion is supported', () => { expect(snapshotDownloadResult({ extractsnapshotresponse: { snapshot: good } })).toEqual(good) })
test('a URL alone is not a verified completion', () => { expect(snapshotDownloadResult({ snapshot: { url: good.url } })).toBeNull() })
