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

export function vmCreationSourceErrorMessage (error, translate) {
  const text = typeof error === 'string' ? error : error?.message || ''
  if (/^(Network Error|timeout of [0-9]+ms exceeded)$/.test(text)) return translate('message.creation.source.job.unknown')
  if (/^Could not create volume from a snapshot/.test(text)) {
    return translate('message.creation.source.restore.failed')
  }
  const code = text.match(/^([A-Z][A-Z0-9_]+):/)?.[1]
  if (code) {
    const key = 'message.creation.source.reason.' + code
    const message = translate(key)
    if (message !== key) return message
  }
  return text
}
