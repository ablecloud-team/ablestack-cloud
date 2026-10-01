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

import StatsTab from '@/components/view/StatsTab.vue'
import AnnotationsTab from '@/components/view/AnnotationsTab.vue'
import EventsTab from '@/components/view/EventsTab.vue'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/vue-app', () => ({ vueProps: {} }))

function statsContext (resourceType = 'VirtualMachine') {
  const vm = {
    ...StatsTab.methods,
    ...StatsTab.data.call({ getStartDate: () => null, getEndDate: () => null, $t: key => key }),
    resource: { id: 'resource-1' },
    resourceType,
    startDate: null,
    endDate: null,
    loaded: true,
    statsRequestId: 0,
    statsResourceId: 'resource-1',
    statsRefreshFailed: false,
    chartLabels: ['previous'],
    formatPeriod: jest.fn(),
    handleStatsResponse: jest.fn(),
    showResourceInfoModal: true,
    $notifyError: jest.fn()
  }
  vm.resourceStatsApi = StatsTab.computed.resourceStatsApi.call(vm)
  return vm
}
function listContext (component) {
  return {
    ...component.methods,
    resource: { id: 'resource-1' },
    resourceType: 'Volume',
    annotationType: 'VOLUME',
    page: 2,
    pageSize: 10,
    notes: [{ id: 'old', annotation: 'previous' }],
    events: [{ id: 'old' }],
    annotation: 'unsent draft',
    itemCount: 21,
    listRequestToken: jest.fn(() => ({ loaded: true })),
    isListRequestCurrent: jest.fn(() => true),
    $store: { getters: { apis: { listAnnotations: {} } } },
    $notifyError: jest.fn()
  }
}

describe('shared detail refresh behavior', () => {
  beforeEach(() => jest.clearAllMocks())
  it.each(['VirtualMachine', 'Volume', 'SystemVm'])('keeps charts and open help visible during %s refresh', async type => {
    let complete
    getAPI.mockReturnValue(new Promise(resolve => { complete = resolve }))
    const vm = statsContext(type)
    const pending = vm.fetchData()
    expect(vm.loaded).toBe(true)
    expect(vm.chartLabels).toEqual(['previous'])
    expect(vm.showResourceInfoModal).toBe(true)
    expect(getAPI).toHaveBeenCalledWith(vm.resourceStatsApi, { id: 'resource-1' })
    complete({ response: 'current' })
    await pending
    expect(vm.handleStatsResponse).toHaveBeenCalledWith({ response: 'current' })
  })
  it('preserves a custom period during automatic refresh', () => {
    const vm = { durationSelectorValue: 'custom', fetchData: jest.fn(), getStartDate: jest.fn() }
    StatsTab.methods.updateVirtualMachineStats.call(vm)
    expect(vm.fetchData).toHaveBeenCalled()
    expect(vm.getStartDate).not.toHaveBeenCalled()
  })
  it('ignores the late response for an older period', async () => {
    const responses = []
    getAPI.mockImplementation(() => new Promise(resolve => responses.push(resolve)))
    const vm = statsContext()
    const first = vm.fetchData()
    const second = vm.fetchData()
    responses[1]({ period: 'new' }); await second
    responses[0]({ period: 'old' }); await first
    expect(vm.handleStatsResponse).toHaveBeenCalledTimes(1)
    expect(vm.handleStatsResponse).toHaveBeenCalledWith({ period: 'new' })
  })
  it('retains charts and reports stale data after a refresh failure', async () => {
    getAPI.mockRejectedValue(new Error('offline'))
    const vm = statsContext()
    await vm.fetchData()
    expect(vm.chartLabels).toEqual(['previous'])
    expect(vm.loaded).toBe(true)
    expect(vm.statsRefreshFailed).toBe(true)
    expect(vm.$notifyError).not.toHaveBeenCalled()
  })
  it('shows initial loading again only for a different resource', async () => {
    getAPI.mockResolvedValue({})
    const vm = statsContext()
    vm.resource.id = 'resource-2'
    const pending = vm.fetchData()
    expect(vm.loaded).toBe(false)
    await pending
  })
  it('does not display the previous resource charts when a new resource fails', async () => {
    getAPI.mockRejectedValue(new Error('new resource unavailable'))
    const vm = statsContext()
    vm.resourceUsageHistory.cpu = [{ data: [71] }]
    vm.resource.id = 'resource-2'
    await vm.fetchData()
    expect(vm.chartLabels).toEqual([])
    expect(vm.resourceUsageHistory.cpu).toEqual([])
    expect(vm.loaded).toBe(true)
    expect(vm.statsRefreshFailed).toBe(true)
    expect(vm.$notifyError).toHaveBeenCalledTimes(1)
  })
  it('handles a valid empty statistics result without an endless spinner', () => {
    const vm = { resetData: jest.fn(), loaded: false, resourceStatsApi: 'listVolumesUsageHistory', resourceStatsApiResponseObject: 'volume' }
    StatsTab.methods.handleStatsResponse.call(vm, { listvolumesusagehistoryresponse: {} })
    expect(vm.loaded).toBe(true)
  })
  it('retains comment draft and page while updating a non-VM resource', async () => {
    let complete
    getAPI.mockReturnValue(new Promise(resolve => { complete = resolve }))
    const vm = listContext(AnnotationsTab)
    const pending = vm.getAnnotations()
    expect(vm.loadingAnnotations).toBe(false)
    expect(vm.notes[0].id).toBe('old')
    expect(vm.annotation).toBe('unsent draft')
    expect(getAPI).toHaveBeenCalledWith('listAnnotations', expect.objectContaining({ entitytype: 'VOLUME', page: 2 }))
    complete({ listannotationsresponse: {} }); await pending
    expect(vm.itemCount).toBe(0)
    expect(vm.notes).toEqual([])
    expect(vm.page).toBe(2)
    expect(vm.annotation).toBe('unsent draft')
  })
  it('retains events and the selected page after a refresh failure', async () => {
    getAPI.mockRejectedValue(new Error('offline'))
    const vm = listContext(EventsTab)
    await vm.fetchEvents()
    expect(vm.events).toEqual([{ id: 'old' }])
    expect(vm.page).toBe(2)
    expect(vm.tabLoading).toBe(false)
    expect(vm.listRefreshFailed).toBe(true)
  })
})
