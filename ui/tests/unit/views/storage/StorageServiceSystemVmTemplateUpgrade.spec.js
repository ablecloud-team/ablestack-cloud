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

import Widget from '@/views/storage/StorageServiceSystemVmTemplateUpgrade'
import History from '@/views/storage/StorageServiceTemplateUpgradeHistory'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

describe('ROOT template maintenance UI boundaries', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it('does not enable unverified, incompatible or current templates', () => {
    const vm = { targetTemplate: 'target', templates: [{ templateUuid: 'target', compatible: false }], plan: null }
    expect(Widget.computed.canSubmit.call(vm)).toBe(false)
    vm.templates[0].compatible = true
    expect(Widget.computed.canSubmit.call(vm)).toBe(true)
    vm.templates[0].current = true
    expect(Widget.computed.canSubmit.call(vm)).toBe(false)
  })
  it('requires verified preflight, maintenance approval and exact service name', () => {
    const vm = { targetTemplate: 'target', plan: { id: 'planned' }, preflight: { compatible: true }, blockers: [], maintenanceWindow: true, confirmation: 'service', resource: { name: 'service' } }
    expect(Widget.computed.canSubmit.call(vm)).toBe(true)
    vm.preflight.compatible = false
    expect(Widget.computed.canSubmit.call(vm)).toBe(false)
    vm.preflight.compatible = true; vm.maintenanceWindow = false
    expect(Widget.computed.canSubmit.call(vm)).toBe(false)
    vm.maintenanceWindow = true; vm.confirmation = 'other'
    expect(Widget.computed.canSubmit.call(vm)).toBe(false)
  })
  it('preserves known template rows after a transient read failure', async () => {
    getAPI.mockRejectedValue(new Error('offline'))
    const vm = { resource: { id: 'a' }, scope: 0, templates: [{ templateUuid: 'known' }], unwrap: Widget.methods.unwrap }
    await Widget.methods.refresh.call(vm)
    expect(vm.templates).toEqual([{ templateUuid: 'known' }]); expect(vm.readFailed).toBe(true)
  })
  it('does not publish a previous resource template plan after navigation', async () => {
    let complete
    postAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = { canSubmit: true, plan: null, resource: { id: 'a' }, targetTemplate: 'target', scope: 0, unwrap: Widget.methods.unwrap }
    const pending = Widget.methods.preflightPlan.call(vm)
    vm.resource = { id: 'b' }; vm.scope++
    complete({ result: JSON.stringify({ upgrade: { id: 'old' }, preflight: { compatible: true } }) })
    await pending
    expect(vm.plan).toBeNull()
  })
  it('keeps preflight evidence attached to the planned upgrade', async () => {
    postAPI.mockResolvedValue({ result: JSON.stringify({ upgrade: { id: 'plan', revision: 9 }, preflight: { compatible: true, activeSessions: 3 } }) })
    const vm = { canSubmit: true, plan: null, resource: { id: 'a' }, targetTemplate: 'target', scope: 0, unwrap: Widget.methods.unwrap }
    await Widget.methods.preflightPlan.call(vm)
    expect(vm.plan).toEqual({ id: 'plan', revision: 9, preflight: { compatible: true, activeSessions: 3 } })
  })
  it('closes the review before tracking a background upgrade and passes the frozen revision', async () => {
    postAPI.mockResolvedValue({ upgradestorageservicesystemvmtemplateresponse: { jobid: 'job' } })
    const events = []
    const vm = {
      canSubmit: true,
      plan: { id: 'plan', revision: 9 },
      targetTemplate: 'target',
      resource: { id: 'a', name: 'service' },
      scope: 0,
      pollJob: true,
      $t: key => key,
      $emit: name => events.push(name),
      $pollJob: jest.fn(() => { expect(events).toEqual(['accepted', 'close-action']) })
    }
    await Widget.methods.upgrade.call(vm)
    expect(postAPI.mock.calls[0][1]).toEqual({ sharedfilesystemid: 'a', upgradeid: 'plan', templateid: 'target', expectedrevision: 9, maintenancewindow: true, confirmation: 'service' })
    expect(vm.$pollJob).toHaveBeenCalledTimes(1)
  })
  it('does not submit an unconfirmed ROOT operation', async () => {
    const vm = { canSubmit: false, plan: { id: 'plan' } }
    await Widget.methods.upgrade.call(vm); expect(postAPI).not.toHaveBeenCalled()
  })
  it('history preserves rows and does not clear known ROOT bindings on read failure', async () => {
    getAPI.mockRejectedValue(new Error('offline'))
    const vm = { resource: { id: 'a' }, scope: 0, rows: [{ previousRootVolumeUuid: 'preserved' }], busy: false, timer: null, unwrap: History.methods.unwrap }
    await History.methods.refresh.call(vm)
    expect(vm.rows).toEqual([{ previousRootVolumeUuid: 'preserved' }]); expect(vm.readFailed).toBe(true)
  })
  it('does not accept a late job callback after history unmount', () => {
    let options
    const vm = { resource: { id: 'a' }, alive: true, $t: key => key, refresh: jest.fn(), $emit: jest.fn(), $pollJob: value => { options = value } }
    History.methods.accepted.call(vm, { jobid: 'job' })
    vm.alive = false; vm.refresh.mockClear()
    options.successMethod()
    expect(vm.refresh).not.toHaveBeenCalled(); expect(vm.$emit).not.toHaveBeenCalled()
  })
})
