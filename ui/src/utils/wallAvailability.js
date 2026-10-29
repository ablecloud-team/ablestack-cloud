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

import md5 from 'md5'

const DISMISSAL_KEY = 'autoAlertBanner.wallDismissal'

export function resetWallBannerDismissal () {
  try { sessionStorage.removeItem(DISMISSAL_KEY) } catch (_) {}
}

// Bind to the authenticated session, including cookie-based SSO, without storing credentials.
export function createWallBannerDismissal (sessionKey) {
  const session = sessionKey ? md5(sessionKey) : ''
  return {
    get dismissed () {
      try {
        const saved = JSON.parse(sessionStorage.getItem(DISMISSAL_KEY))
        return !!session && saved?.session === session && saved?.dismissed === true
      } catch (_) { return false }
    },
    dismiss () {
      if (!session) return
      try { sessionStorage.setItem(DISMISSAL_KEY, JSON.stringify({ session, dismissed: true })) } catch (_) {}
    },
    reset: resetWallBannerDismissal
  }
}

// Per-banner session gate; only the lightweight status request runs while unavailable.
export function createWallGate (request, current) {
  let status = 'Unknown'
  let ready = false
  let pending
  let expires = 0
  return {
    get state () { return status },
    fail () { status = 'Degraded'; ready = false; expires = 0 },
    async check () {
      if (!current()) return false
      if (Date.now() < expires) return ready
      if (!pending) {
        pending = (async () => {
          try {
            const response = await request('getWallAlertAvailability')
            if (!current()) return false
            const value = response?.getwallalertavailabilityresponse?.wallavailability
            ready = value?.available === true && value?.state === 'Ready'
            if (ready) {
              status = 'Ready'
            } else if (value?.state && value.state !== 'Ready' && value.state !== 'Unknown') {
              status = value.state
            } else {
              throw new Error('Wall status not confirmed')
            }
          } catch (_) {
            if (!current()) return false
            ready = false
            // A failed Mold status request cannot prove a Wall outage or recovery.
            // Keep a known fault stable across transient probe failures.
            if (status === 'Unknown' || status === 'Ready') status = 'CheckFailed'
          }
          expires = Date.now() + 30000
          return current() && ready
        })().finally(() => { pending = null })
      }
      return pending
    }
  }
}
