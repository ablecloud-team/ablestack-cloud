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

// Full administrator responses always include the details map. An absent key
// in that confirmed map is the server's false default; an absent map is unknown.
export function networkIpUsageValue (network) {
  const details = network?.details
  if (!details || Array.isArray(details) || typeof details !== 'object') return null
  if (!Object.prototype.hasOwnProperty.call(details, 'hideIpAddressUsage')) return false
  const value = details.hideIpAddressUsage
  if (typeof value === 'boolean') return value
  if (typeof value === 'string' && /^(true|false)$/i.test(value.trim())) return value.trim().toLowerCase() === 'true'
  return null
}

export function networkIpUsageLabel (network) {
  const value = networkIpUsageValue(network)
  return value === null ? 'label.unknown' : value ? 'label.network.ip.usage.excluded' : 'label.network.ip.usage.included'
}
