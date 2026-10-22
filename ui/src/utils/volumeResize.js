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

// Resize requests retain the current offering and omit unchanged parameters.

import { allocationIntent } from '@/utils/volumeAllocation'
const GiB = 1073741824
const fail = key => { throw new Error('message.volume.resize.' + key) }
const equal = (a, b) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null)
const blank = value => value == null || String(value).trim() === ''
function integer (value, positive = false) {
  const text = String(value).trim()
  const n = Number(text)
  if (!/^\d+$/.test(text) || !Number.isSafeInteger(n) || n < (positive ? 1 : 0)) fail('integer')
  return n
}
function exact (response, command, key, id) {
  const rows = response?.[command.toLowerCase() + 'response']?.[key]
  return Array.isArray(rows) && rows.length === 1 && rows[0].id === id ? rows[0] : null
}
export async function readVolumeResizeContract (api, volume, current = () => true) {
  const read = async (command, key, id, args = {}) => {
    const response = await api(command, { id, listall: true, ...args }, { preserveOnFailure: true })
    if (!current()) fail('context')
    return exact(response, command, key, id)
  }
  if (volume.diskofferingid) {
    const offering = await read('listDiskOfferings', 'diskoffering', volume.diskofferingid, { state: 'all' })
    if (!offering) fail('offering')
    return { kind: 'disk', offering }
  }
  // compute-only disks are deliberately omitted by listDiskOfferings and represented
  // by a numeric serviceofferingid on listVolumes. Resolve the VM's UUID instead.
  if (volume.type !== 'ROOT' || !volume.virtualmachineid) fail('offering')
  const vm = await read('listVirtualMachines', 'virtualmachine', volume.virtualmachineid, volume.projectid ? { projectid: volume.projectid } : {})
  if (!vm?.serviceofferingid) fail('offering')
  const offering = await read('listServiceOfferings', 'serviceoffering', vm.serviceofferingid, { state: 'all' })
  if (!offering?.diskofferingid || offering.diskofferingcomputeonly === false ||
      (volume.serviceofferingname && volume.serviceofferingname !== offering.name)) fail('offering')
  return { kind: 'compute', offering, vmid: vm.id, serviceofferingid: vm.serviceofferingid }
}
export function volumeResizeSizeReason (volume, contract) {
  if (allocationIntent(volume)) return 'message.allocation.lifecycle.unsupported'
  if (contract?.offering.disksizestrictness) return 'message.volume.resize.strict'
  return ''
}
export function volumeResizeIops (volume, contract) {
  // resizeVolume explicitly permits ROOT IOPS overrides, even for fixed offerings.
  return !!contract && (volume.type === 'ROOT' || contract.offering.iscustomizediops === true)
}
export function volumeResizeParams (volume, form, contract, apiParams) {
  if (!contract || !['Ready', 'Allocated'].includes(volume.state)) fail('offering')
  if (volume.clonefastflattenstatus === 'running' || volume.volumemutationblockedreason) fail('unavailable')
  const values = { id: volume.id }
  if (!blank(form.size) && String(form.size).trim() !== String(volume.size / GiB)) {
    if (!apiParams.size) fail('forbidden')
    if (volumeResizeSizeReason(volume, contract)) throw new Error(volumeResizeSizeReason(volume, contract))
    const size = integer(form.size, true)
    if (!Number.isSafeInteger(size * GiB)) fail('integer')
    if (size * GiB < volume.size) {
      if (volume.hypervisor === 'XenServer' || (volume.state !== 'Allocated' && String(volume.allocation?.actualformat || volume.format).toLowerCase() === 'qcow2')) fail('shrink')
      if (!apiParams.shrinkok || !form.shrinkok) fail('shrink.confirm')
      values.shrinkok = true
    }
    values.size = size
    if (apiParams.automigrate && form.automigrate) values.automigrate = true
  }
  const effective = {}
  for (const key of ['miniops', 'maxiops']) {
    effective[key] = blank(form[key]) ? volume[key] ?? null : integer(form[key])
    if (!equal(effective[key], volume[key] ?? null)) {
      if (!volumeResizeIops(volume, contract) || !apiParams[key]) fail('forbidden')
      values[key] = effective[key]
    }
  }
  if ((effective.miniops == null) !== (effective.maxiops == null) ||
      (effective.miniops != null && effective.miniops > effective.maxiops)) fail('iops')
  return values
}
export function validateVolumeResizeSnapshot (original, fresh, contract, freshContract) {
  if (!fresh || fresh.id !== original.id) fail('changed')
  for (const key of ['diskofferingid', 'serviceofferingid', 'size', 'miniops', 'maxiops', 'state', 'path', 'chaininfo', 'storageid', 'virtualmachineid', 'vmstate', 'account', 'domainid', 'projectid', 'allocation', 'clonefastflattenstatus', 'volumemutationblockedreason']) {
    if (!equal(original[key], fresh[key])) fail('changed')
  }
  if (!equal(contract, freshContract)) fail('changed')
}
