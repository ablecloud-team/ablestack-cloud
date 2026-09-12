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

import { resolveDrActionAvailability } from '@/utils/dr/actionAvailability'

const runtimePlanActions = [
  {
    key: 'sync',
    group: 'REPLICATION',
    order: 30,
    api: 'startDrSync',
    command: 'startDrSync',
    icon: 'sync-outlined',
    label: 'label.dr.action.full.resync',
    modal: true
  },
  {
    key: 'recoversync',
    group: 'REPLICATION',
    order: 31,
    api: 'recoverDrSync',
    command: 'recoverDrSync',
    icon: 'reload-outlined',
    label: 'label.dr.action.recover.sync'
  },
  {
    key: 'pausesync',
    group: 'REPLICATION',
    order: 32,
    api: 'pauseDrSync',
    command: 'pauseDrSync',
    icon: 'pause-circle-outlined',
    label: 'label.dr.action.pause.sync'
  },
  {
    key: 'resumesync',
    group: 'REPLICATION',
    order: 33,
    api: 'resumeDrSync',
    command: 'resumeDrSync',
    icon: 'play-circle-outlined',
    label: 'label.dr.action.resume.sync'
  },
  {
    key: 'testfailover',
    group: 'TEST',
    order: 40,
    api: 'startDrTestFailover',
    command: 'startDrTestFailover',
    icon: 'experiment-outlined',
    label: 'label.dr.action.test.failover'
  },
  {
    key: 'stoptestfailover',
    group: 'TEST',
    order: 41,
    api: 'stopDrTestFailover',
    command: 'stopDrTestFailover',
    icon: 'stop-outlined',
    label: 'label.dr.action.test.cleanup',
    modal: true,
    danger: true
  },
  {
    key: 'failover',
    group: 'TRANSITION',
    order: 50,
    api: 'startDrFailover',
    command: 'startDrFailover',
    icon: 'thunderbolt-outlined',
    label: 'label.dr.action.failover',
    modal: true,
    confirmMessage: 'message.dr.confirm.failover',
    danger: true
  },
  {
    key: 'failback',
    group: 'TRANSITION',
    order: 52,
    api: 'startDrFailback',
    command: 'startDrFailback',
    icon: 'undo-outlined',
    label: 'label.dr.action.failback',
    modal: true,
    confirmMessage: 'message.dr.confirm.failback',
    danger: true
  },
  {
    key: 'reprotect',
    group: 'TRANSITION',
    order: 53,
    api: 'startDrReprotect',
    command: 'startDrReprotect',
    icon: 'retweet-outlined',
    label: 'label.dr.action.reprotect',
    modal: true
  },
  {
    key: 'adoptreplica',
    group: 'ADVANCED',
    order: 60,
    api: 'adoptDrReplica',
    command: 'adoptDrReplica',
    icon: 'safety-certificate-outlined',
    label: 'label.dr.action.adopt.replica',
    modal: true,
    confirmMessage: 'message.dr.confirm.adopt.replica',
    danger: true
  },
  {
    key: 'releaseprotection',
    group: 'PROTECTION_END',
    order: 70,
    api: 'releaseDrProtection',
    command: 'releaseDrProtection',
    icon: 'delete-outlined',
    label: 'label.dr.action.release.protection',
    modal: true,
    confirmMessage: 'message.dr.confirm.release.protection',
    danger: true
  },
  {
    key: 'cancelrun',
    group: 'CURRENT',
    order: 10,
    api: 'cancelDrRun',
    command: 'cancelDrRun',
    icon: 'close-circle-outlined',
    label: 'label.dr.action.cancel.run',
    modal: true,
    danger: true
  }
]

const actionContracts = {
  startDrSync: 'SYNC',
  recoverDrSync: 'RECOVER_SYNC',
  pauseDrSync: 'PAUSE_SYNC',
  resumeDrSync: 'RESUME_SYNC',
  startDrTestFailover: 'TEST_FAILOVER',
  stopDrTestFailover: 'TEST_CLEANUP',
  startDrFailover: 'FAILOVER',
  startDrFailback: 'FAILBACK',
  startDrReprotect: 'REPROTECT',
  adoptDrReplica: 'ADOPT',
  releaseDrProtection: 'RELEASE'
}

function commonMenuAction (action) {
  return Object.assign({
    dataView: true,
    listView: true
  }, action)
}

export function buildDrSiteActions () {
  return [
    commonMenuAction({
      api: 'checkDrSite',
      icon: 'api-outlined',
      label: 'label.dr.site.check'
    }),
    commonMenuAction({
      api: 'updateDrSite',
      icon: 'edit-outlined',
      label: 'label.dr.site.edit'
    }),
    commonMenuAction({
      api: 'deleteDrSite',
      icon: 'delete-outlined',
      label: 'label.dr.site.delete',
      disabled: resource => Number(resource?.activeplancount || resource?.activePlanCount || 0) > 0
    })
  ]
}

export function buildDrPlanActions (currentRun = {}) {
  const runtimeActions = runtimePlanActions.map(action => commonMenuAction(Object.assign({}, action, {
    intent: actionContracts[action.command],
    expectedRunType: actionContracts[action.command],
    show: resource => resolveDrActionAvailability(action, resource, currentRun).applicable,
    disabled: resource => !resolveDrActionAvailability(action, resource, currentRun).enabled,
    currentRun
  })))

  return [
    commonMenuAction({
      key: 'update',
      group: 'PLAN',
      order: 20,
      api: 'updateDrPlan',
      icon: 'edit-outlined',
      label: 'label.dr.plan.edit',
      show: resource => resolveDrActionAvailability({ key: 'update' }, resource, currentRun).applicable,
      disabled: resource => !resolveDrActionAvailability({ key: 'update' }, resource, currentRun).enabled,
      currentRun
    }),
    commonMenuAction({
      key: 'delete',
      group: 'PLAN',
      order: 21,
      api: 'deleteDrPlan',
      icon: 'delete-outlined',
      label: 'label.dr.plan.delete',
      show: () => true,
      disabled: () => false,
      currentRun
    }),
    ...runtimeActions
  ].sort((left, right) => (left.order || 999) - (right.order || 999))
}
