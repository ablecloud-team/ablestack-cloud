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

// Session memory only: keep accepted jobs across dialog closure, never persist them.
export const sharedfsRemovalIntents = reactive({})
export function clearSharedfsRemovalIntents () { Object.keys(sharedfsRemovalIntents).forEach(key => delete sharedfsRemovalIntents[key]) }

export function removalActor (vm) {
  return JSON.stringify([vm.$store?.getters?.userInfo?.id || '', vm.$store?.getters?.project?.id || '', vm.$store?.state?.user?.token || ''])
}

export function validRemovalPlan (plan, resource, policy) {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
  const positive = value => Number.isSafeInteger(value) && value > 0
  if (!plan || plan.schemaVersion !== 1 || (plan.vmId !== null && !positive(plan.vmId)) || plan.sharedfsUuid !== resource.id || plan.policy !== policy || !/^[0-9a-f]{64}$/i.test(plan.planHash) ||
      !positive(plan.sharedfsId) || !['accountId', 'domainId', 'zoneId'].every(key => positive(plan[key])) ||
      !Array.isArray(plan.volumes)) return false
  const seen = new Set()
  const ids = new Set()
  return plan.volumes.every(row => {
    if (!row || !uuid.test(row.uuid) || !positive(row.id) || row.type !== 'DATADISK' || seen.has(row.uuid) || ids.has(row.id) ||
        !['accountId', 'domainId', 'zoneId'].every(key => row[key] === plan[key]) ||
        !Number.isSafeInteger(row.sizeBytes) || row.sizeBytes < 0 ||
        (row.poolId !== null && !positive(row.poolId)) || (row.attachedVmId !== null && row.attachedVmId !== plan.vmId) ||
        row.action !== (policy === 'PRESERVE_VOLUMES' ? 'DETACH_AND_PRESERVE' : 'DETACH_AND_DELETE')) return false
    seen.add(row.uuid); ids.add(row.id)
    return true
  })
}

export function removalImpacts (plan) {
  const roots = Array.isArray(plan?.rootVolumes) ? plan.rootVolumes.filter(row => row?.type === 'ROOT' && row.uuid && row.action === 'DELETE_WITH_VM') : []
  return [
    ...(roots.length ? roots.map(row => ({ ...row, role: 'ROOT', protocols: [], rowKey: 'root-' + row.uuid })) : [{ role: 'ROOT_UNKNOWN', rowKey: 'root-unknown', protocols: [] }]),
    ...(plan?.volumes || []).map(row => ({ ...row, rowKey: 'data-' + row.uuid }))
  ]
}
