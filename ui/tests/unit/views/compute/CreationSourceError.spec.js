// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import { vmCreationSourceErrorMessage } from '@/utils/vmCreationSourceError'

const messages = {
  'message.creation.source.restore.failed': '스냅샷 복구 실패',
  'message.creation.source.reason.SOURCE_NOT_READY': '원본이 준비되지 않았습니다'
}
const translate = key => messages[key] || key

test('restore failures use localized recovery guidance', () => {
  expect(vmCreationSourceErrorMessage('Could not create volume from a snapshot', translate)).toBe('스냅샷 복구 실패')
})

test('known server rejection codes are localized', () => {
  expect(vmCreationSourceErrorMessage('SOURCE_NOT_READY: unavailable', translate)).toBe('원본이 준비되지 않았습니다')
})

test('unknown diagnostics remain available without inventing a reason', () => {
  expect(vmCreationSourceErrorMessage('UNKNOWN_CODE: diagnostic', translate)).toBe('UNKNOWN_CODE: diagnostic')
})

test('network failures show localized uncertainty while the original technical diagnostic remains available', () => {
  expect(vmCreationSourceErrorMessage('Network Error', key => key)).toBe('message.creation.source.job.unknown')
  expect(vmCreationSourceErrorMessage('timeout of 15000ms exceeded', key => key)).toBe('message.creation.source.job.unknown')
})
