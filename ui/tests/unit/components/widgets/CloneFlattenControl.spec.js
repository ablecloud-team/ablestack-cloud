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

import { flushPromises, shallowMount } from '@vue/test-utils'
import { getAPI, postAPI } from '@/api'
import CloneFlattenControl from '@/components/widgets/CloneFlattenControl'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

describe('SharedMountPoint clone flatten bandwidth control', () => {
  let wrappers
  let pollJob
  let notifyError
  const record = {
    id: 'clone-id',
    state: 'Running',
    clonefastphase: 'clone_ready',
    clonefaststatus: 'running',
    clonefastflattenbandwidth: 100,
    clonefastflattenbandwidthstatus: 'applied'
  }
  const response = vm => ({ listvirtualmachinesresponse: { virtualmachine: [vm] } })
  const factory = (overrides = {}, apis = { updateVmCloneFlattenBandwidth: {} }) => {
    const wrapper = shallowMount(CloneFlattenControl, {
      props: { record: { ...record, ...overrides } },
      global: {
        mocks: {
          $t: key => key,
          $store: { getters: { apis } },
          $pollJob: pollJob,
          $notifyError: notifyError
        },
        stubs: {
          'a-popover': true,
          'a-tooltip': true,
          'a-button': true,
          'a-tag': true,
          'a-progress': true,
          'a-input-number': true
        }
      }
    })
    wrappers.push(wrapper)
    return wrapper
  }

  beforeEach(() => {
    jest.resetAllMocks()
    wrappers = []
    pollJob = jest.fn()
    notifyError = jest.fn()
    getAPI.mockResolvedValue(response(record))
  })

  afterEach(() => wrappers.forEach(wrapper => wrapper.unmount()))

  test.each([{}, { clonefastphase: 'source_ready' }])('hides flatten controls for an inactive record: %s', overrides => {
    const wrapper = factory(Object.keys(overrides).length ? overrides : {
      clonefastphase: undefined, clonefaststatus: undefined
    })
    expect(wrapper.vm.active).toBe(false)
    expect(wrapper.find('.clone-flatten-control').exists()).toBe(false)
  })

  test.each([
    ['clone_ready', 'Running', true],
    ['clone_paused', 'Stopped', true],
    ['clone_failed', 'Running', false],
    ['clone_preparing', 'Stopped', false],
    ['clone_transitioning', 'Stopping', false]
  ])('bandwidth edit permission in %s/%s is %s', (phase, state, allowed) => {
    expect(factory({ clonefastphase: phase, state }).vm.canEdit).toBe(allowed)
  })

  test('requires bandwidth metadata and API permission', () => {
    expect(factory({ clonefastflattenbandwidth: undefined }).vm.canEdit).toBe(false)
    expect(factory({}, {}).vm.bandwidthDisabledReason).toBe('message.clone.flatten.bandwidth.api.unavailable')
    expect(factory({ clonefastflattenbandwidthstatus: 'applying' }).vm.canEdit).toBe(false)
  })

  test('refresh keeps an edited bandwidth draft and updates progress', async () => {
    getAPI.mockResolvedValue(response({ ...record, clonefastflattenbandwidth: 70, clonefastflattenprogress: '45.25' }))
    const wrapper = factory()
    await wrapper.setData({ draft: 200, dirty: true })
    await wrapper.vm.refresh()
    expect(getAPI).toHaveBeenCalledWith('listVirtualMachines', { id: record.id, details: 'min' })
    expect(wrapper.vm.draft).toBe(200)
    expect(wrapper.vm.progress).toBe(45.25)
  })

  test('ignores an old refresh after switching the VM', async () => {
    let resolveRefresh
    getAPI.mockReturnValue(new Promise(resolve => { resolveRefresh = resolve }))
    const wrapper = factory()
    const pending = wrapper.vm.refresh()
    await wrapper.setProps({ record: { ...record, id: 'another-clone', clonefastflattenbandwidth: 80 } })
    resolveRefresh(response(record))
    await pending
    expect(wrapper.vm.current.id).toBe('another-clone')
    expect(wrapper.vm.draft).toBe(80)
  })

  test('submits the bandwidth and refreshes after the async job succeeds', async () => {
    postAPI.mockResolvedValue({ updatevmcloneflattenbandwidthresponse: { jobid: 'job-id' } })
    const wrapper = factory()
    await wrapper.setData({ draft: 250, dirty: true })
    await wrapper.vm.apply()
    expect(postAPI).toHaveBeenCalledWith('updateVmCloneFlattenBandwidth', { id: record.id, bandwidth: 250 })
    expect(wrapper.vm.busy).toBe(true)
    pollJob.mock.calls[0][0].successMethod()
    await flushPromises()
    expect(wrapper.vm.busy).toBe(false)
    expect(wrapper.vm.dirty).toBe(false)
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  test('preserves the draft when the bandwidth job fails', async () => {
    postAPI.mockResolvedValue({ updatevmcloneflattenbandwidthresponse: { jobid: 'job-id' } })
    const wrapper = factory()
    await wrapper.setData({ draft: 250, dirty: true })
    await wrapper.vm.apply()
    pollJob.mock.calls[0][0].errorMethod()
    await flushPromises()
    expect(wrapper.vm.busy).toBe(false)
    expect(wrapper.vm.dirty).toBe(true)
    expect(wrapper.vm.draft).toBe(250)
    expect(wrapper.vm.error).toBe('message.clone.flatten.bandwidth.failed')
  })

  test.each([-1, 1.5, 2147483648, null])('does not submit invalid bandwidth: %s', async bandwidth => {
    const wrapper = factory()
    await wrapper.setData({ draft: bandwidth })
    await wrapper.vm.apply()
    expect(postAPI).not.toHaveBeenCalled()
  })
})
