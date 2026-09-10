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

import en from '../../../public/locales/en.json'
import ko from '../../../public/locales/ko_KR.json'

const expectedLabels = {
  'label.dr.operation.progress': {
    en: 'Overall operation progress',
    ko: '전체 작업 진행률'
  },
  'label.dr.transfer.progress': {
    en: 'Data transfer progress',
    ko: '데이터 전송 진행률'
  },
  'label.dr.cbt.pending.activation': {
    en: 'CBT configured, activation pending',
    ko: 'CBT 설정 완료, 활성 검증 대기'
  }
}

describe('DR locale labels', () => {
  for (const [key, expected] of Object.entries(expectedLabels)) {
    it(`defines ${key} in English and Korean`, () => {
      expect(en[key]).toBe(expected.en)
      expect(ko[key]).toBe(expected.ko)
    })
  }
})
