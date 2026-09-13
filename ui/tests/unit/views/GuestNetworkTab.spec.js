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

import GuestNetworkTab from '@/views/compute/GuestNetworkTab.vue'
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/vue-app', () => ({ vueProps: {} }))

const flush = async () => { for (let i = 0; i < 6; i++) await Promise.resolve() }
function context () {
  return {
    ...GuestNetworkTab.methods,
    resource: { id: 'vm-id', state: 'Running' },
    state: { observed: 'before', interfaces: [] },
    recollecting: false,
    $t: key => key,
    $message: { info: jest.fn() },
    $notifyError: jest.fn()
  }
}
describe('Guest network recollection', () => {
  beforeEach(() => { jest.clearAllMocks(); jest.useFakeTimers() })
  afterEach(() => { jest.clearAllTimers(); jest.useRealTimers() })
  it('requests recollection with POST and waits beyond a scheduler interval', async () => {
    postAPI.mockResolvedValue({ refreshvirtualmachineguestnetworkstateresponse: { guestnetworkrefresh: { accepted: true } } })
    const vm = context()
    vm.pollRecollection = jest.fn()
    const start = Date.now()
    vm.requestRecollection()
    await flush()
    expect(postAPI).toHaveBeenCalledWith('refreshVirtualMachineGuestNetworkState', { virtualmachineid: 'vm-id', sections: 'interfaces,routes,dns,readiness' })
    expect(getAPI).not.toHaveBeenCalled()
    expect(vm.pollRecollection.mock.calls[0][1]).toBeGreaterThanOrEqual(start + 120000)
  })
  it('reads persisted state with GET after collection', async () => {
    getAPI.mockResolvedValue({ getvirtualmachineguestnetworkstateresponse: { guestnetworkstate: { observed: 'after', interfaces: [{ name: 'Ethernet' }] } } })
    const vm = context()
    vm.recollecting = true
    vm.pollRecollection('before', Date.now() + 120000, 2000)
    jest.advanceTimersByTime(2000)
    await flush()
    expect(vm.state.interfaces[0].name).toBe('Ethernet')
    expect(vm.recollecting).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('reports pending collection at the deadline instead of claiming success', async () => {
    getAPI.mockResolvedValue({ getvirtualmachineguestnetworkstateresponse: { guestnetworkstate: { observed: 'before' } } })
    const vm = context()
    vm.pollRecollection('before', Date.now(), 1)
    jest.advanceTimersByTime(1)
    await flush()
    expect(vm.$message.info).toHaveBeenCalledWith('message.guest.network.recollect.pending')
    expect(vm.recollecting).toBe(false)
  })
  it('releases loading state on request failure', async () => {
    postAPI.mockRejectedValue(new Error('failed'))
    const vm = context()
    vm.requestRecollection()
    await flush()
    expect(vm.recollecting).toBe(false)
    expect(vm.$notifyError).toHaveBeenCalled()
  })
})
