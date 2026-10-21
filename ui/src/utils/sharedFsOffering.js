// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

export const computeFields = ['cpunumber', 'memory', 'cpuspeed']
const present = v => v !== undefined && v !== null && v !== ''

// These are server-provided constraints; CPU/HA/ROOT policies are not duplicated here.
export function computeInputErrors (eligibility, values = {}) {
  if (!eligibility?.selectable) return [{ code: 'SELECTION_REQUIRED', field: 'serviceofferingid' }]
  return computeFields.flatMap(field => {
    const constraint = eligibility.constraints?.[field]
    if (!constraint?.custom) return []
    const value = values[field]
    if (!present(value)) return [{ code: 'REQUIRED', field }]
    if (!Number.isInteger(Number(value)) || Number(value) < constraint.min || Number(value) > constraint.max) {
      return [{ code: 'OUT_OF_RANGE', field, params: { min: constraint.min, max: constraint.max } }]
    }
    return []
  })
}

export function customComputeParams (eligibility, values = {}) {
  return Object.fromEntries(computeFields
    .filter(field => eligibility?.constraints?.[field]?.custom && present(values[field]))
    .map(field => [field, Number(values[field])]))
}

export function computeReasonText (translate, reason = {}) {
  const known = ['HA_REQUIRED', 'OFFERING_INACTIVE', 'ROOT_POLICY_UNKNOWN', 'ROOT_POLICY_THIN', 'ROOT_POLICY_UNAVAILABLE',
    'SYSTEMVM_ARCH_UNAVAILABLE', 'RANGE_INVALID', 'RANGE_EMPTY', 'OUT_OF_RANGE', 'REQUIRED', 'FIXED_OVERRIDE',
    'CUSTOM_VALUES_INVALID', 'PARAMETER_UNSUPPORTED', 'SELECTION_REQUIRED']
  const code = known.includes(reason.code) ? reason.code.toLowerCase() : 'unknown'
  const labels = { cpunumber: 'label.cpunumber', memory: 'label.memory.mb', cpuspeed: 'label.cpuspeed' }
  return translate('message.sharedfs.offering.reason.' + code, {
    ...(reason.params || {}),
    field: translate(labels[reason.field] || 'label.compute.offering.for.sharedfs.instance')
  })
}

export function offeringFailureText (translate, error) {
  const data = error?.response?.data || error?.data || {}
  const candidates = [error?.errortext, error?.message, ...Object.values(data).map(x => x?.errortext),
    data.errortext, typeof error === 'string' ? error : null]
  for (const message of candidates) {
    if (typeof message !== 'string' || !message.startsWith('SHAREDFS_OFFERING_INELIGIBLE:')) continue
    try {
      const reasons = JSON.parse(message.slice('SHAREDFS_OFFERING_INELIGIBLE:'.length))
      if (Array.isArray(reasons)) return reasons.map(reason => computeReasonText(translate, reason)).join('; ')
    } catch (e) {
      return ''
    }
  }
  return ''
}
