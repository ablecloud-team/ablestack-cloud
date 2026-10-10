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

export function deploymentStorageQuery ({ form = {}, imageType, hypervisor, template }) {
  return {
    zoneid: form.zoneid,
    serviceofferingid: form.computeofferingid,
    templateid: imageType === 'isoid' ? form.isoid : form.templateid,
    hypervisor: form.hypervisor || hypervisor || template?.hypervisor,
    podid: form.podid,
    clusterid: form.clusterid,
    hostid: form.hostid,
    vmcount: Number(form.vmNumber) || 1
  }
}

export function additionalIsoConfigDriveConflict ({ imageType, additionalIsoSelection = {}, networks = [], networkIds = [] }) {
  return imageType === 'isoid' && additionalIsoSelection.enabled && additionalIsoSelection.ids?.length > 0 &&
    networks.some(network => networkIds.includes(network.id) && network.service?.some(service =>
      service.provider?.some(provider => provider.name === 'ConfigDrive')))
}

export function dataDiskDeviceIds (count) {
  if (!Number.isSafeInteger(count) || count < 1) throw new Error('Invalid disk count')
  return Array.from({ length: count }, (_, index) => index < 2 ? index + 1 : index + 2)
}

export function dataDiskRequest (offering, size, count, extra = {}) {
  const params = {}
  dataDiskDeviceIds(count).forEach((deviceId, index) => {
    const values = { diskofferingid: offering.id, deviceid: deviceId, ...extra }
    if (offering.iscustomized) values.size = size
    Object.entries(values).forEach(([key, value]) => {
      if (value != null && value !== '') params['datadisksdetails[' + index + '].' + key] = value
    })
  })
  return params
}

export async function waitDiskJob (call, jobId, delay = () => new Promise(resolve => setTimeout(resolve, 1500))) {
  for (let attempt = 0; attempt < 1200; attempt++) {
    const result = await call('queryAsyncJobResult', { jobid: jobId })
    if (result.jobstatus === 1) return result.jobresult
    if (result.jobstatus === 2) {
      const error = new Error(result.jobresult?.errortext || 'Asynchronous job failed')
      error.terminal = true
      throw error
    }
    await delay()
  }
  throw new Error('Job is still pending; retry resumes the existing job')
}

// The mutable record preserves IDs across partial failure and retry; pending jobs are resumed.
export async function completeIsoDiskDeployment (call, record, delay) {
  async function phase (entry, phase, command, params) {
    const key = phase + 'jobid'
    if (!entry[key]) {
      if (phase === 'create') entry.createUncertain = true
      let response
      try {
        response = await call(command, params)
      } catch (error) {
        // A definitive API rejection is retryable. A lost response may have created a volume.
        if (error.response) entry.createUncertain = false
        throw error
      }
      if (phase === 'create') entry.createUncertain = false
      entry[key] = response.jobid
      if (phase === 'create' && response.id) entry.volumeId = response.id
      if (!entry[key]) throw new Error('Missing asynchronous job ID')
    }
    try {
      const result = await waitDiskJob(call, entry[key], delay)
      entry[key] = null
      return result
    } catch (error) {
      if (error.terminal) entry[key] = null
      throw error
    }
  }
  record.status = 'running'
  record.error = null
  try {
    if (!record.vmId) {
      const result = await waitDiskJob(call, record.deployJobId, delay)
      record.vmId = result.virtualmachine.id
    }
    for (const entry of record.disks) {
      if (entry.status === 'attached') continue
      entry.error = null
      if (entry.volumeId && !entry.createjobid) {
        const response = await call('listVolumes', { id: entry.volumeId })
        const volume = response.volume?.[0]
        if (volume?.virtualmachineid === record.vmId) { entry.status = 'attached'; continue }
        if (!volume || ['Destroy', 'Destroyed', 'Expunged'].includes(volume.state)) {
          entry.previousVolumeIds = [...(entry.previousVolumeIds || []), entry.volumeId]
          entry.volumeId = null
        } else if (volume.state !== 'Ready' && volume.state !== 'Allocated') throw new Error('Existing volume is not ready; retry after its current operation completes')
      }
      if (!entry.volumeId && entry.createUncertain) {
        const response = await call('listVolumes', { ...record.volumeBaseParams, name: record.volumeNamePrefix + (entry.index + 1), listall: true })
        const matches = (response.volume || []).filter(volume => volume.name === record.volumeNamePrefix + (entry.index + 1) &&
          volume.diskofferingid === record.volumeBaseParams.diskofferingid && !['Destroy', 'Destroyed', 'Expunged'].includes(volume.state))
        if (matches.length !== 1) throw new Error('Volume submission result is unknown; confirm its volume or job ID before retrying')
        entry.volumeId = matches[0].id
        entry.createUncertain = false
      }
      if (!entry.volumeId || entry.createjobid) {
        entry.status = 'creating'
        const result = await phase(entry, 'create', 'createVolume', { ...record.volumeBaseParams, name: record.volumeNamePrefix + (entry.index + 1) })
        entry.volumeId = result.volume.id
      }
      entry.status = 'attaching'
      await phase(entry, 'attach', 'attachVolume', { id: entry.volumeId, virtualmachineid: record.vmId, deviceid: entry.deviceId })
      entry.status = 'attached'
    }
    if (record.startAfterCreation && !record.started) {
      await phase(record, 'start', 'startVirtualMachine', { id: record.vmId })
      record.started = true
    }
    record.status = 'complete'
  } catch (error) {
    record.status = 'failed'
    record.error = error.message
    const failed = record.disks.find(entry => entry.status !== 'attached')
    if (failed) failed.error = error.message
    throw error
  }
  return record
}
