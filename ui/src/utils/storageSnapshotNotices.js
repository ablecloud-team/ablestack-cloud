// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import { storageSnapshotOperations, operationScopeKey } from '@/utils/storageSnapshotOperations'
import { snapshotOperationResponse } from '@/utils/storageSnapshotContext'

export const snapshotOperationLabels = {
  createSnapshot: 'label.action.take.snapshot',
  createVolume: 'label.action.create.volume',
  createTemplate: 'label.create.template',
  revertSnapshot: 'label.action.revert.snapshot',
  archiveSnapshot: 'label.archive.snapshot',
  copySnapshot: 'label.copy.snapshot',
  deleteSnapshot: 'label.action.delete.snapshot',
  extractSnapshot: 'label.action.download.snapshot'
}
export function scopedSnapshotOperations (scope) {
  const hash = operationScopeKey(scope)
  return Object.values(storageSnapshotOperations).filter(e => e.scope === hash).sort((a, b) => b.created.localeCompare(a.created))
}
export function snapshotResultPath (entry) {
  const result = entry.result?.jobresult
  const body = snapshotOperationResponse(entry.api, result) || result
  const volume = body?.volume; const template = body?.template?.[0] || body?.template; const snapshot = body?.snapshot?.[0] || body?.snapshot
  if (volume?.id) return '/volume/' + volume.id
  if (template?.id) return '/template/' + template.id
  // A deleted snapshot's success payload must never offer a broken detail link.
  if (snapshot?.id && !['deleteSnapshot', 'extractSnapshot'].includes(entry.api)) return '/snapshot/' + snapshot.id
  return ''
}
export function snapshotOperationAffectsRoute (entry, route) {
  const kind = route.meta?.name || route.name
  if (!['snapshot', 'volume'].includes(kind)) return false
  if (route.params?.id) return entry.keys.includes(kind + ':' + route.params.id) || snapshotResultPath(entry) === route.path
  return kind === 'snapshot' ? !['createVolume', 'createTemplate', 'attachVolume'].includes(entry.api) : ['createVolume', 'attachVolume', 'revertSnapshot'].includes(entry.api)
}
