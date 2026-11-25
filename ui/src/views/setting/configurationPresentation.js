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

const writableTypes = new Set(['Boolean', 'Number', 'Decimal', 'Range', 'Select', 'Order'])

export function isSensitiveConfiguration (record) {
  if (record.type === 'Password' || record.isencrypted === true) return true
  return !writableTypes.has(record.type) &&
    /(?:^|[._-])(?:password|passwd|passphrase|secret|token|private[._-]?key|api[._-]?key|credential)(?:$|[._-])/i.test(record.name)
}

export function formatConfigurationValue (record, property = 'value') {
  const value = record[property]
  if (value == null || value === '') return '—'
  return isSensitiveConfiguration(record) ? '••••••••' : String(value)
}

export function configurationApplyLabel (record) {
  if (record.isdynamic === true) return 'label.global.setting.apply.dynamic'
  if (record.isdynamic === false) return 'label.global.setting.apply.restart'
  return ''
}
