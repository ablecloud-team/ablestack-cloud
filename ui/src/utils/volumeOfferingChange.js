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

import { allocationIntent, allocationOfferingReason } from '@/utils/volumeAllocation'
const GiB = 1073741824
const blank = value => value == null || String(value).trim() === ''
function integer (value, positive = false) {
  const text = String(value).trim()
  const n = Number(text)
  if (!/^\d+$/.test(text) || !Number.isSafeInteger(n) || n < (positive ? 1 : 0)) throw new Error('message.volume.resize.integer')
  return n
}
export function offeringVolumeSize (volume, offering, values) {
  return offering?.iscustomized ? (blank(values.size) ? volume.size / GiB : Number(values.size)) : Number(offering?.disksize)
}
export function offeringShrinkBlocked (volume) {
  return volume.hypervisor === 'XenServer' || (volume.state !== 'Allocated' && String(volume.allocation?.actualformat || volume.format).toLowerCase() === 'qcow2')
}
export function volumeOfferingChangeParams (volume, offering, values, apiParams) {
  const reason = allocationOfferingReason(volume, offering)
  if (reason) throw new Error(reason)
  if (!['Ready', 'Allocated'].includes(volume.state) || volume.volumemutationblockedreason || volume.clonefastflattenstatus === 'running') throw new Error('message.volume.resize.unavailable')
  const params = { id: volume.id, diskofferingid: offering.id }
  const size = offeringVolumeSize(volume, offering, values)
  if (offering.iscustomized) {
    if (!apiParams.size) throw new Error('message.volume.resize.forbidden')
    params.size = integer(blank(values.size) ? volume.size / GiB : values.size, true)
  }
  if (!Number.isSafeInteger(size) || size < 1 || !Number.isSafeInteger(size * GiB)) throw new Error('message.volume.resize.integer')
  if (allocationIntent(volume) && size * GiB !== volume.size) throw new Error('message.allocation.lifecycle.unsupported')
  if (size * GiB < volume.size) {
    if (offeringShrinkBlocked(volume)) throw new Error('message.volume.resize.shrink')
    if (!apiParams.shrinkok || !values.shrinkok) throw new Error('message.volume.resize.shrink.confirm')
    params.shrinkok = true
  }
  if (offering.iscustomizediops) {
    const effective = {}
    for (const key of ['miniops', 'maxiops']) {
      effective[key] = blank(values[key]) ? volume[key] ?? null : integer(values[key])
      if (effective[key] != null) {
        if (!apiParams[key]) {
          if (effective[key] !== volume[key]) throw new Error('message.volume.resize.forbidden')
        } else params[key] = effective[key]
      }
    }
    if ((effective.miniops == null) !== (effective.maxiops == null) || (effective.miniops != null && effective.miniops > effective.maxiops)) throw new Error('message.volume.resize.iops')
  }
  if (!volume.kvdoenable && apiParams.automigrate) params.automigrate = !!values.automigrate
  return params
}
