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

export function operationSnapshot (record, resource) {
  return { id: record.id || resource.id, name: resource.name, zoneid: record.zoneid, zonename: record.zonename, created: record.created, isready: record.isready, status: record.status, downloaddetails: JSON.parse(JSON.stringify(record.downloaddetails || [])), operationState: 'pending', jobid: '', error: '' }
}
export function preparePoolEligible (pool, zoneid) {
  return pool.zoneid === zoneid && pool.state === 'Up' && pool.hypervisor === 'KVM' && pool.managed !== true && pool.managed !== 'true' && ['NetworkFilesystem', 'SharedMountPoint'].includes(pool.type)
}
export function primaryPreparationState (rows, zoneid, poolid, templateid) {
  const targets = rows.filter(row => row.id === templateid && row.zoneid === zoneid).flatMap(row => row.downloaddetails || []).filter(row => row.datastoreRole === 'Primary' && row.datastoreId === poolid)
  if (targets.length && targets.every(row => row.downloadState === 'DOWNLOADED')) return 'success'
  if (!targets.some(row => row.downloadState === 'DOWNLOADED') && targets.some(row => ['DOWNLOAD_ERROR', 'UPLOAD_ERROR', 'ABANDONED', 'LIMIT_REACHED'].includes(row.downloadState))) return 'failed'
  return 'running'
}
