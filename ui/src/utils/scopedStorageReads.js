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

import { storageReadDeadline } from './storageRead'

// Reference reads follow the resource/session, independent of selected protocol tabs.
export function createScopedStorageReads ({ scope, active, onState }) {
  const requests = new Map()
  const current = (key, request) => active() && requests.get(key) === request && request.scope === scope()
  const run = (key, read, apply, retries = 0) => {
    const previous = requests.get(key)
    if (previous?.pending && previous.scope === scope()) return previous.promise
    const request = { scope: scope(), pending: true }
    requests.set(key, request)
    onState(key, { pending: true })
    request.promise = (async () => {
      let failed = false
      let retry = false
      try {
        const value = await storageReadDeadline(Promise.resolve().then(read))
        if (current(key, request)) apply(value)
      } catch (_) {
        failed = true
      } finally {
        request.pending = false
        if (active() && requests.get(key) === request) {
          if (request.scope !== scope() && retries < 2) {
            retry = true
          }
          if (!retry) onState(key, { pending: false, failed: failed || request.scope !== scope() })
        }
      }
      if (retry) return run(key, read, apply, retries + 1)
    })()
    return request.promise
  }
  return { run }
}
