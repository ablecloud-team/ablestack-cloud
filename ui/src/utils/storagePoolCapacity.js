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

const physicalPoolTypes = new Set(['SharedMountPoint', 'Filesystem', 'NetworkFilesystem', 'NFS', 'RBD', 'CLVM', 'CLVM_NG', 'IscsiLUN', 'VMFS', 'Gluster', 'SMB', 'PreSetup'])

function capacityNumber (value) {
  if (typeof value !== 'number' && typeof value !== 'string') return null
  if (typeof value === 'string' && !value.trim()) return null
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? number : null
}

export function storagePoolCapacities (pool = {}) {
  const total = capacityNumber(pool?.capacitybytes) ?? capacityNumber(pool?.disksizetotal)
  const allocated = capacityNumber(pool?.disksizeallocated)
  const used = capacityNumber(pool?.disksizeused)
  // Allocated capacity includes reservations and can exceed the physical total.
  // Managed/unknown providers do not necessarily report physical capacity here.
  const physical = pool?.managed === false && physicalPoolTypes.has(pool?.type)
  const available = physical && total != null && used != null ? Math.max(0, total - used) : null
  return { total, allocated, available }
}

export function formatStorageCapacity (value) {
  const number = capacityNumber(value)
  if (number == null) return '—'
  const divisor = number >= 1024 ** 4 ? 1024 ** 4 : 1024 ** 3
  const unit = divisor === 1024 ** 4 ? 'TiB' : 'GiB'
  const size = number / divisor
  if (size > 0 && size < 0.01) return '< 0.01 GiB'
  return size.toLocaleString(undefined, { maximumFractionDigits: 2 }) + ' ' + unit
}
