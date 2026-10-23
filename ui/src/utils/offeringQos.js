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

export const qosGroups = ['bytesreadrate', 'byteswriterate', 'iopsreadrate', 'iopswriterate'].map(api => ({
  api,
  form: 'disk' + api,
  label: 'label.disk' + api,
  unit: api.startsWith('bytes') ? 'B/s' : 'IOPS',
  response: 'disk' + api.charAt(0).toUpperCase() + api.slice(1).replace('readrate', 'ReadRate').replace('writerate', 'WriteRate')
}))
export const qosFields = qosGroups.flatMap(group => ['', 'max', 'maxlength'].map(suffix => ({
  api: group.api + suffix,
  form: group.form + suffix,
  response: group.response + (suffix === 'max' ? 'Max' : suffix === 'maxlength' ? 'MaxLength' : '')
})))
const present = value => value !== undefined && value !== null && value !== ''
export const trueValue = value => [true, 'true', 1, '1'].includes(value)
export function qosForm (resource) {
  const lower = Object.fromEntries(Object.entries(resource || {}).map(([key, value]) => [key.toLowerCase(), value]))
  return Object.fromEntries(qosFields.map(field => [field.form, present(lower[field.response.toLowerCase()]) ? String(lower[field.response.toLowerCase()]) : '']))
}
export function qosError (form, current = {}) {
  for (const field of qosFields) {
    const value = form[field.form]
    if (present(value) && (!/^\d+$/.test(String(value)) || !Number.isSafeInteger(Number(value)))) return 'message.offering.qos.integer'
  }
  const effective = field => Number((present(form[field]) ? form[field] : current?.[field]) || 0)
  for (const group of qosGroups) {
    const rate = effective(group.form)
    const max = effective(group.form + 'max')
    const length = effective(group.form + 'maxlength')
    if (max > 0 && (rate === 0 || max < rate)) return 'message.offering.qos.burst'
    if (length > 0 && max === 0) return 'message.offering.qos.length'
  }
  return ''
}
export function qosParams (form, supported) {
  return Object.fromEntries(qosFields.filter(field => present(form[field.form]) && (!supported || field.api in supported))
    .map(field => [field.api, String(form[field.form])]))
}
export function performanceParams (form, linked = false, clone = false) {
  if (linked) return {}
  const result = { cachemode: trueValue(form.shareable) ? 'none' : (form.writecachetype || form.cachemode) }
  if (!result.cachemode) delete result.cachemode
  if (clone) Object.assign(result, Object.fromEntries(qosFields.map(field => [field.api, '0'])))
  if (form.qostype === 'hypervisor') Object.assign(result, qosParams(form))
  if (form.qostype === 'storage') {
    result.customizediops = trueValue(form.iscustomizeddiskiops)
    for (const [key, value] of [['miniops', form.diskiopsmin], ['maxiops', form.diskiopsmax], ['hypervisorsnapshotreserve', form.hypervisorsnapshotreserve]]) {
      if (!result.customizediops && present(value)) result[key] = String(value)
    }
  }
  if (clone && form.qostype !== 'storage') Object.assign(result, { customizediops: false, miniops: '0', maxiops: '0', hypervisorsnapshotreserve: '0' })
  return result
}
export function performanceFormValues (form) {
  return Object.fromEntries(['qostype', 'writecachetype', 'cachemode', 'computeonly', 'shareable', ...qosFields.map(field => field.form)]
    .filter(key => key in form).map(key => [key, form[key]]))
}
