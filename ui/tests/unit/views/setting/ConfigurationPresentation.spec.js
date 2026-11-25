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

import { formatConfigurationValue, configurationApplyLabel } from '@/views/setting/configurationPresentation'

test('masks credential values in both current and default fields', () => {
  const credential = { name: 'alert.smtp.password', type: 'String', value: 'test-value', defaultvalue: 'test-default' }
  expect(formatConfigurationValue(credential)).toBe('••••••••')
  expect(formatConfigurationValue(credential, 'defaultvalue')).toBe('••••••••')
  expect(formatConfigurationValue({ name: 'password.policy.minimum.length', type: 'Number', value: '0' })).toBe('0')
})

test('does not treat explicit zero or false as missing', () => {
  expect(formatConfigurationValue({ name: 'sample.enabled', value: false })).toBe('false')
  expect(formatConfigurationValue({ name: 'sample.limit', value: 0 })).toBe('0')
  expect(formatConfigurationValue({ name: 'sample.limit', value: null })).toBe('—')
})

test('distinguishes restart requirements from unknown metadata', () => {
  expect(configurationApplyLabel({ isdynamic: true })).toBe('label.global.setting.apply.dynamic')
  expect(configurationApplyLabel({ isdynamic: false })).toBe('label.global.setting.apply.restart')
  expect(configurationApplyLabel({})).toBe('')
})
