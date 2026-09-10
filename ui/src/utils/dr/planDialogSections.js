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

export const DR_PLAN_DIALOG_SECTIONS = Object.freeze({
  BASIC: 'basic',
  SITES: 'sites',
  WORKLOAD: 'workload',
  OBJECTIVES: 'objectives',
  TARGET: 'target',
  DISKS: 'disks',
  WORKERS: 'workers',
  POLICY: 'policy',
  ADVANCED: 'advanced'
})

export const DEFAULT_DR_PLAN_ACTIVE_SECTIONS = Object.freeze([
  DR_PLAN_DIALOG_SECTIONS.BASIC,
  DR_PLAN_DIALOG_SECTIONS.SITES,
  DR_PLAN_DIALOG_SECTIONS.WORKLOAD,
  DR_PLAN_DIALOG_SECTIONS.TARGET,
  DR_PLAN_DIALOG_SECTIONS.DISKS
])

export const DR_PLAN_VALIDATION_SECTION_MAP = Object.freeze({
  name: DR_PLAN_DIALOG_SECTIONS.BASIC,
  description: DR_PLAN_DIALOG_SECTIONS.BASIC,
  sourcesiteid: DR_PLAN_DIALOG_SECTIONS.SITES,
  targetsiteid: DR_PLAN_DIALOG_SECTIONS.SITES,
  direction: DR_PLAN_DIALOG_SECTIONS.SITES,
  sourcevmid: DR_PLAN_DIALOG_SECTIONS.WORKLOAD,
  sourceexternalref: DR_PLAN_DIALOG_SECTIONS.WORKLOAD,
  sourceworkloadvalue: DR_PLAN_DIALOG_SECTIONS.WORKLOAD,
  targetvmname: DR_PLAN_DIALOG_SECTIONS.WORKLOAD,
  rposeconds: DR_PLAN_DIALOG_SECTIONS.OBJECTIVES,
  rtoseconds: DR_PLAN_DIALOG_SECTIONS.OBJECTIVES,
  startsync: DR_PLAN_DIALOG_SECTIONS.OBJECTIVES,
  targetstorageref: DR_PLAN_DIALOG_SECTIONS.TARGET,
  targetcomputeref: DR_PLAN_DIALOG_SECTIONS.TARGET,
  targetnetworkref: DR_PLAN_DIALOG_SECTIONS.TARGET,
  targetfolderpath: DR_PLAN_DIALOG_SECTIONS.TARGET,
  diskmappingsjson: DR_PLAN_DIALOG_SECTIONS.DISKS,
  sourceDiskSize: DR_PLAN_DIALOG_SECTIONS.DISKS,
  coordinatorworkerhostid: DR_PLAN_DIALOG_SECTIONS.WORKERS,
  sourceworkerhostid: DR_PLAN_DIALOG_SECTIONS.WORKERS,
  targetworkerhostid: DR_PLAN_DIALOG_SECTIONS.WORKERS,
  consistencymode: DR_PLAN_DIALOG_SECTIONS.POLICY,
  syncintervalseconds: DR_PLAN_DIALOG_SECTIONS.POLICY,
  retentioncount: DR_PLAN_DIALOG_SECTIONS.POLICY,
  retrycount: DR_PLAN_DIALOG_SECTIONS.POLICY,
  testnetworkmode: DR_PLAN_DIALOG_SECTIONS.POLICY,
  failoverpoweron: DR_PLAN_DIALOG_SECTIONS.POLICY,
  enginetype: DR_PLAN_DIALOG_SECTIONS.ADVANCED,
  enginebindingtype: DR_PLAN_DIALOG_SECTIONS.ADVANCED,
  enginebindingid: DR_PLAN_DIALOG_SECTIONS.ADVANCED,
  mappingjson: DR_PLAN_DIALOG_SECTIONS.ADVANCED,
  schedulejson: DR_PLAN_DIALOG_SECTIONS.ADVANCED,
  policyjson: DR_PLAN_DIALOG_SECTIONS.ADVANCED,
  quiescepolicyjson: DR_PLAN_DIALOG_SECTIONS.ADVANCED
})

export const DR_PLAN_REASON_SECTION_MAP = Object.freeze({
  SOURCE_DISK_INVENTORY_REQUIRED: DR_PLAN_DIALOG_SECTIONS.WORKLOAD,
  SOURCE_DISK_SIZE_UNKNOWN: DR_PLAN_DIALOG_SECTIONS.DISKS,
  SOURCE_DISK_SIZE_UNRESOLVED: DR_PLAN_DIALOG_SECTIONS.DISKS,
  DR_TARGET_DISK_SIZE_UNRESOLVED: DR_PLAN_DIALOG_SECTIONS.DISKS,
  TARGET_DISK_OFFERING_REQUIRED: DR_PLAN_DIALOG_SECTIONS.DISKS,
  TARGET_DISK_FORMAT_INVALID: DR_PLAN_DIALOG_SECTIONS.DISKS,
  TARGET_STORAGE_REQUIRED: DR_PLAN_DIALOG_SECTIONS.DISKS,
  TARGET_STORAGE_UNRESOLVED: DR_PLAN_DIALOG_SECTIONS.DISKS,
  TARGET_STORAGE_INVALID: DR_PLAN_DIALOG_SECTIONS.DISKS,
  TARGET_NETWORK_REQUIRED: DR_PLAN_DIALOG_SECTIONS.TARGET,
  TARGET_SERVICE_OFFERING_REQUIRED: DR_PLAN_DIALOG_SECTIONS.TARGET,
  TARGET_COMPUTE_REQUIRED: DR_PLAN_DIALOG_SECTIONS.TARGET,
  TARGET_WORKER_REQUIRED: DR_PLAN_DIALOG_SECTIONS.WORKERS,
  SOURCE_WORKER_REQUIRED: DR_PLAN_DIALOG_SECTIONS.WORKERS,
  COORDINATOR_WORKER_REQUIRED: DR_PLAN_DIALOG_SECTIONS.WORKERS,
  TARGET_SITE_ZONE_REQUIRED: DR_PLAN_DIALOG_SECTIONS.SITES
})

export function drPlanSectionForValidation (fieldNameOrReason) {
  const key = String(fieldNameOrReason || '')
  const normalizedReason = key.split(':')[0]
  return DR_PLAN_VALIDATION_SECTION_MAP[key] || DR_PLAN_REASON_SECTION_MAP[normalizedReason] || ''
}
