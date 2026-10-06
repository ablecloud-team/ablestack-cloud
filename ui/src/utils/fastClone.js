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

export const isFastClonePowerOperationBlocked = (record, selectedItems, starting = false) => {
  const isBlocked = (item) => {
    const status = String(item?.clonefaststatus || item?.details?.['clone.fast.status'] || '').toLowerCase()
    if (getFastClonePhase(item)) {
      return item?.clonefastpowerallowed !== true
    }
    const active = starting ? status === 'running' : ['pending', 'running'].includes(status)
    return active && item?.clonefastsourcepowerallowed !== true
  }
  return isBlocked(record) || (Array.isArray(selectedItems) && selectedItems.some(isBlocked))
}

export const getFastClonePhase = record => String(record?.clonefastphase || '').toLowerCase()

export const isFastCloneFlattenStatusVisible = record => {
  const phase = getFastClonePhase(record)
  if (phase === 'source_ready') return false
  if (phase) return true
  const status = String(record?.clonefaststatus || record?.details?.['clone.fast.status'] || '').toLowerCase()
  const hasVolumeInfo = [record?.clonefastflattenvolumetype, record?.clonefastflattenvolumename, record?.clonefastflattendeviceid]
    .some(value => value !== undefined && value !== null && value !== '')
  return ['pending', 'running'].includes(status) && hasVolumeInfo
}

export const getFastClonePhaseLabel = record => {
  const phase = getFastClonePhase(record)
  if (!phase || ['clone_ready', 'source_ready'].includes(phase)) return ''
  const known = ['source_preparing', 'source_prepared', 'source_committing', 'source_failed',
    'clone_preparing', 'clone_checking', 'clone_pausing', 'clone_transitioning', 'clone_paused', 'clone_failed']
  return known.includes(phase) ? 'label.clone.phase.' + phase : 'label.clone.phase.clone_failed'
}

export const getFastClonePhaseDescription = record => {
  const phase = getFastClonePhase(record)
  if (!phase) return ''
  if (phase.endsWith('_failed')) return 'message.clone.phase.recovery'
  if (record?.clonefastpowerallowed === true) {
    return phase === 'source_ready' ? 'message.clone.phase.source.ready' : 'message.clone.phase.clone.ready'
  }
  return getFastClonePhaseLabel(record) || 'message.clone.phase.power.blocked'
}

export const getFastClonePhaseColor = record => {
  const phase = getFastClonePhase(record)
  if (phase.endsWith('_failed')) return 'error'
  if (['clone_paused', 'clone_pausing', 'clone_transitioning'].includes(phase)) return 'warning'
  return 'processing'
}

export const getFastClonePowerBlockedLabel = (record, selectedItems, starting = false) => {
  const blocked = [record, ...(Array.isArray(selectedItems) ? selectedItems : [])]
    .find(item => isFastClonePowerOperationBlocked(item, [], starting))
  return getFastClonePhaseDescription(blocked) || 'message.sharedmountpoint.clone.flatten.in.progress'
}
