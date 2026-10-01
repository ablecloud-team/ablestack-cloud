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

import { reactive } from 'vue'
import { getAPI, postAPI } from '@/api'

export const vbmcOperations = reactive({})
export const allocated = vm => !!vm?.vbmcport && vm.vbmcport !== 'None'
export function allocationReason (vm) {
  if (vm?.hypervisor !== 'KVM' || vm?.state !== 'Running' || vm?.vmtype === 'sharedfsvm') return 'unsupported'
  if (vm.haenable) return 'ha'
  if (!vm.hostid || ['Offline', 'Maintenance'].includes(vm.hostcontrolstate)) return 'host'
  return ''
}
export function validCidr (value) {
  const parts = value.split('/')
  return parts.length === 2 && /^(0|[1-9]\d?)$/.test(parts[1]) && Number(parts[1]) <= 32 && parts[0].split('.').length === 4 && parts[0].split('.').every(v => /^(0|[1-9]\d{0,2})$/.test(v) && Number(v) <= 255)
}
export const validPassword = value => /^[\x21-\x7e]{8,20}$/.test(value) && !value.includes('%')
// Keep only operation metadata across dialog closes, never credentials or raw errors.
export async function runVbmc (key, api, params) {
  if (['pending', 'unknown'].includes(vbmcOperations[key]?.state)) return
  const op = reactive({ state: 'pending', api, jobId: null })
  vbmcOperations[key] = op
  try {
    const response = await postAPI(api, params)
    const result = response[api.toLowerCase() + 'response']
    op.jobId = result?.jobid
    if (op.jobId) await pollVbmc(key)
    else op.state = 'unknown'
  } catch (e) {
    op.state = e?.response?.data && Object.values(e.response.data).some(v => v?.errorcode) ? 'failed' : 'unknown'
  }
}
export async function pollVbmc (key) {
  const op = vbmcOperations[key]
  if (!op?.jobId || op.polling) return
  op.polling = true
  try {
    for (let i = 0; i < 90; i++) {
      const response = await getAPI('queryAsyncJobResult', { jobid: op.jobId })
      const result = response.queryasyncjobresultresponse
      if (result.jobstatus === 1 || result.jobstatus === 2) {
        op.state = result.jobstatus === 1 ? 'success' : 'failed'
        op.finished = Date.now()
        return
      }
      await new Promise(resolve => setTimeout(resolve, 2000))
    }
    op.state = 'unknown'
  } catch (_) { op.state = 'unknown' } finally { op.polling = false }
}
