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

import { v4 as uuid } from 'uuid'

export const batchStorageKey = (actor, source) => 'vm-clone-batch:' + actor + ':' + source
export const newCloneRequest = params => ({ id: uuid(), attempt: null, params: { ...params } })
export const cloneAttemptParams = request => ({ ...request.params, customjobid: request.attempt?.id || request.id, ...(request.attempt ? { batchid: request.id, retryindices: request.attempt.indices.join(',') } : {}) })
export const retryableTargets = result => (result?.targets || []).filter(t => ['CREATE', 'RESUME', 'START'].includes(t.retryaction))
export const cloneBatchBusy = result => ['QUEUED', 'RUNNING'].includes(result?.status) || result?.jobstatus === 0
export const retryCloneRequest = (request, result) => ({ ...request, attempt: { id: uuid(), indices: retryableTargets(result).map(t => t.index) } })
export function readCloneRequest (storage, key, source) {
  try {
    const value = JSON.parse(storage.getItem(key))
    if (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value?.id) || value?.params?.virtualmachineid !== source) return null
    if (!Number.isInteger(value.params.count) || value.params.count < 1 || value.params.count > 50 || typeof value.params.name !== 'string') return null
    if (value.attempt && (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value.attempt.id) || !Array.isArray(value.attempt.indices) || !value.attempt.indices.length || value.attempt.indices.some(i => !Number.isInteger(i) || i < 1 || i > value.params.count))) return null
    return value
  } catch (error) { return null }
}
