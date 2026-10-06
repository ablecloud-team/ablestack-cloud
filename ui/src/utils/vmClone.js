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

// Additional XML disks are not CloudStack-managed clone volumes.
export const hasExtraConfigDisk = (record) => {
  return Object.entries(record?.details || {}).some(([key, value]) => {
    if (!/^extraconfig(?:-\d+)?$/.test(key) || typeof value !== 'string' || !value.trim()) return false
    const xml = new DOMParser().parseFromString(`<devices>${value}</devices>`, 'application/xml')
    if (xml.getElementsByTagName('parsererror').length) {
      // Incomplete device XML must not allow a directly attached disk to be cloned.
      return /<disk\b|<hostdev\b[^>]*\btype\s*=\s*['"]scsi['"]/i.test(value)
    }
    return Array.from(xml.getElementsByTagName('disk')).some(disk =>
      ['disk', 'lun'].includes(disk.getAttribute('device') || 'disk')) ||
      Array.from(xml.getElementsByTagName('hostdev')).some(device => device.getAttribute('type') === 'scsi')
  })
}

export const isCloneBlockedByExtraConfigDisk = (record, selectedItems) => {
  return hasExtraConfigDisk(record) || (Array.isArray(selectedItems) && selectedItems.some(hasExtraConfigDisk))
}
