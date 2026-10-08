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

export function validPortRange (start, end) {
  const a = Number(start); const b = Number(end == null || end === '' ? start : end)
  return start !== null && start !== '' && Number.isInteger(a) && Number.isInteger(b) && a >= 1 && a <= b && b <= 65535
}
export function validForwardPorts (rule) {
  return validPortRange(rule.publicport, rule.publicendport) && validPortRange(rule.privateport, rule.privateendport) &&
    Number(rule.publicendport || rule.publicport) - Number(rule.publicport) === Number(rule.privateendport || rule.privateport) - Number(rule.privateport)
}
export function loadBalancerIsProtected (rule, owners = {}) {
  return !!owners[rule.id] || (rule.tags || []).some(tag => String(tag.key).startsWith('mold.k8s.'))
}
export function selectedBackendMap (selected, vpcConserveMode, networkId) {
  const params = {}; let index = 0
  for (const [id, selection] of Object.entries(selected)) {
    for (const ip of selection.ips || []) {
      params[`vmidipmap[${index}].vmid`] = id
      params[`vmidipmap[${index}].vmip`] = ip
      if (vpcConserveMode) params[`vmidipmap[${index}].vmnetworkid`] = networkId
      index++
    }
  }
  return params
}
