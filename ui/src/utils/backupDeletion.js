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

import { isAblestackBackupProvider } from './ablestackBackupPolicy'

export function validBackupDeletionPlan (plan, record, forced) {
  return !!plan && plan.id === record.id && plan.provider === record.provider && plan.forced === forced &&
    typeof plan.deletionplan === 'string' && !!plan.deletionplan &&
    ['READY', 'WAITING_DEPENDENCIES'].includes(plan.state) &&
    Array.isArray(plan.backup) && plan.backup.some(point => point?.id === record.id) &&
    (forced || plan.backup.length === 1) && Array.isArray(plan.dependentbackup) &&
    [...plan.backup, ...plan.dependentbackup].every(point => typeof point?.id === 'string' && !!point.id)
}

// Re-reading may shrink an approved scope, but must never silently add recovery points.
export function withinBackupDeletionPlan (confirmed, current) {
  const approved = new Set(confirmed.backup.map(point => point.id))
  const dependents = new Set(confirmed.dependentbackup.map(point => point.id))
  return current.backup.every(point => approved.has(point.id)) && current.dependentbackup.every(point => dependents.has(point.id))
}

export function orderBackupDeletions (records, plans, forced) {
  const byId = new Map(plans.map(plan => [plan.id, plan]))
  let remaining = [...new Map(records.map(record => [record.id, { record: { ...record }, plan: byId.get(record.id) }])).values()]
  if (remaining.some(({ record, plan }) => isAblestackBackupProvider(record) && !validBackupDeletionPlan(plan, record, forced))) return null
  if (forced) {
    // A selected ancestor already submits the dependent backup as part of its one job.
    remaining = remaining.filter(item => !item.plan || !remaining.some(other => other !== item && other.plan?.backup.some(point => point.id === item.record.id)))
    if (!remaining.length && records.length) return null
  }
  const ordered = []
  while (remaining.length) {
    const ids = new Set(remaining.map(item => item.record.id))
    const index = remaining.findIndex(item => !item.plan?.dependentbackup.some(point => ids.has(point.id)))
    if (index < 0) return null
    ordered.push(...remaining.splice(index, 1))
  }
  return ordered
}
