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
// Validate the read-only server plan before any destination can be selected.
export function readSnapshotCopyTargets (json, target) {
  const plan = json?.listsnapshotcopytargetsresponse?.snapshotcopytargets
  const fail = () => { throw new Error('message.snapshot.action.incomplete') }
  if (!plan || plan.snapshotid !== target.id || plan.sourcezoneid !== target.zoneId || typeof plan.eligible !== 'boolean' || typeof plan.reason !== 'string' || !/^[a-f0-9]{64}$/.test(plan.scopehash) || !Array.isArray(plan.destinations)) fail()
  if ((!plan.eligible && !plan.reason) || (plan.eligible && plan.reason)) fail()
  const ids = new Set()
  for (const row of plan.destinations) {
    if (!row.zoneid || row.zoneid === target.zoneId || ids.has(row.zoneid) || typeof row.name !== 'string' || typeof row.eligible !== 'boolean' || typeof row.reason !== 'string') fail()
    if (row.eligible && (!['', 'RETRY'].includes(row.reason) || row.method !== 'SecondaryStorageVM' || row.protocol !== 'nfs')) fail()
    if (!row.eligible && !row.reason) fail()
    ids.add(row.zoneid)
  }
  return plan
}
export function snapshotTransferFailure (error) {
  if ([401, 403].includes(error?.response?.status) || error?.message === 'message.snapshot.action.permission') return 'message.snapshot.action.permission'
  return error?.message?.startsWith('message.snapshot.') ? error.message : 'message.snapshot.transfer.lookup.failed'
}
