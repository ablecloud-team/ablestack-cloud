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

// Per-banner session gate; only the lightweight status request runs while unavailable.
export function createWallGate (request, current) {
  let status = 'Unknown'
  let pending
  let expires = 0
  return {
    get state () { return status },
    fail () { status = 'Unavailable'; expires = 0 },
    async check () {
      if (!current()) return false
      if (Date.now() < expires) return status === 'Ready'
      if (!pending) {
        pending = (async () => {
          try {
            const response = await request('getWallAlertAvailability')
            if (!current()) return false
            const value = response?.getwallalertavailabilityresponse?.wallavailability
            status = value?.available === true && value?.state === 'Ready' ? 'Ready' : (value?.state || 'Unknown')
          } catch (_) {
            if (current()) status = 'Unavailable'
          }
          expires = Date.now() + 30000
          return current() && status === 'Ready'
        })().finally(() => { pending = null })
      }
      return pending
    }
  }
}
