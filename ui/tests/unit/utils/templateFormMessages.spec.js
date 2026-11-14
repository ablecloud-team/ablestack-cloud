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

import { createI18n } from 'vue-i18n'
import en from '../../../public/locales/en.json'
import ko from '../../../public/locales/ko_KR.json'
test.each([['en', en], ['ko', ko]])('%s template labels and checksum examples compile as literal text', (locale, messages) => {
  const i18n = createI18n({ locale, fallbackLocale: locale, messages: { [locale]: messages } })
  expect(i18n.global.t('label.sshkeyenabled')).not.toBe('label.sshkeyenabled')
  for (const key of ['message.template.checksum.help', 'message.template.checksum.placeholder', 'message.template.checksum.invalid']) {
    const text = i18n.global.t(key); expect(text).toContain('{SHA-256}'); expect(text).not.toContain("{'{")
  }
})
