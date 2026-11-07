// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

export const networkTabRoute = route => ['guestnetwork', 'publicip'].includes(route?.meta?.name)
const contextValue = value => !!String(value ?? '').trim() && !['false', 'null', 'undefined'].includes(String(value).trim().toLowerCase())
export const hasVpc = (record = {}) => contextValue(record.vpcid) || contextValue(record.vpcname)

// Labels may depend on a record; tab identity and URLs must always be strings.
export function uniqueTabs (tabs) {
  const seen = new Set()
  return tabs.filter(tab => {
    if (typeof tab.name !== 'string' || seen.has(tab.name)) return false
    seen.add(tab.name)
    return true
  })
}

export function selectedNetworkTab (tabs, requested) {
  // Old Shared bookmarks used the display label. Function-body bookmarks are
  // deliberately treated as invalid input, never parsed or executed.
  const key = requested === 'ip.addresses' ? 'public.ip.addresses' : requested
  return tabs.find(tab => tab.name === key) || tabs[0]
}

export function networkTabRouteKey (route) {
  return JSON.stringify([route.path, Object.entries(route.query || {}).filter(([key]) => key !== 'tab').sort(([a], [b]) => a.localeCompare(b))])
}
