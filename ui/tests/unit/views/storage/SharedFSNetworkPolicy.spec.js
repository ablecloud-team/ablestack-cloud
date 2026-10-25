// Licensed under the Apache License, Version 2.0. See NOTICE.
import { shallowMount, flushPromises } from '@vue/test-utils'
import Network from '@/views/storage/SharedFSNetworkStatus'
import Create from '@/views/storage/CreateSharedFS'
import NicsTable from '@/views/network/NicsTable'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { postAPI } from '@/api'
import { staticNetworkError, networkPolicyObservation } from '@/utils/sharedfsNetwork'
import wire from '../../../fixtures/storage-1448-network-wire.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(async () => ({})), postAPI: jest.fn() }))
const clone = x => JSON.parse(JSON.stringify(x))
describe('SharedFS network actual component contracts', () => {
  let wrapper, poll, notify, health
  const render = async (props = {}) => {
    wrapper = shallowMount(Network, {
      props: { resource: { ...wire.resource }, health, ...props },
      global: { mocks: { $t: k => k, $store: { getters: { apis: { restartSharedFileSystem: {} } } }, $pollJob: poll, $notifyError: notify } }
    })
    await flushPromises()
    return wrapper.vm
  }
  beforeEach(() => {
    jest.spyOn(Date, 'now').mockReturnValue(wire.testNowEpoch * 1000)
    health = clone(wire.legacyHealth)
    poll = jest.fn()
    notify = jest.fn()
    postAPI.mockReset()
    postAPI.mockResolvedValue({ restartsharedfilesystemresponse: { jobid: 'restart-original-job' } })
  })
  afterEach(() => { if (wrapper) wrapper.unmount(); jest.restoreAllMocks(); jest.useRealTimers() })
  it('uses real listStorageServiceHealth resultjson and does not invent an observation for legacy guests', async () => {
    const vm = await render()
    expect(vm.state).toBe('UNKNOWN')
    expect(vm.rows.find(r => r.key === 'gateway').desired).toBe('10.10.13.1')
    expect(vm.rows.find(r => r.key === 'gateway').actual).toBe('label.sharedfs.network.unknown')
    expect(postAPI).not.toHaveBeenCalled()
    wrapper.vm.$emit('refresh')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('distinguishes a valid no-gateway policy, stale and exact failure phase', async () => {
    health = clone(wire.policyHealth)
    const vm = await render({ resource: { ...wire.resource, gateway: null } })
    expect(vm.state).toBe('READY')
    expect(vm.rows.find(r => r.key === 'gateway').desired).toBe('label.sharedfs.network.no.gateway')
    const root = JSON.parse(health[0].resultjson)
    root.network.policy.status = 'FAILED'; root.network.policy.phase = 'NETWORK_ALIASES'; root.network.policy.error = 'NIC_OWNER_UNKNOWN'
    await wrapper.setProps({ health: [{ ...health[0], resultjson: JSON.stringify(root) }] })
    expect(vm.state).toBe('FAILED'); expect(vm.diagnostic).toContain('NETWORK_ALIASES'); expect(vm.diagnostic).toContain('NIC_OWNER_UNKNOWN')
    root.generatedEpoch -= 60; root.network.policy.status = 'READY'
    await wrapper.setProps({ health: [{ ...health[0], resultjson: JSON.stringify(root) }] })
    expect(vm.state).toBe('UNKNOWN')
  })
  it('expires an unchanged successful observation and marks failed reads unknown', async () => {
    jest.useFakeTimers('legacy')
    health = clone(wire.policyHealth)
    wrapper = shallowMount(Network, { props: { resource: wire.resource, health }, global: { mocks: { $t: k => k, $store: { getters: { apis: {} } } } } })
    expect(wrapper.vm.state).toBe('READY')
    Date.now.mockReturnValue((wire.testNowEpoch + 60) * 1000)
    jest.advanceTimersByTime(5000)
    await wrapper.vm.$nextTick()
    expect(wrapper.vm.state).toBe('UNKNOWN')
    Date.now.mockReturnValue(wire.testNowEpoch * 1000)
    jest.advanceTimersByTime(5000)
    await wrapper.setProps({ unavailable: true })
    expect(wrapper.vm.state).toBe('UNKNOWN')
  })
  it('discards a health response from a previous SharedFS scope', async () => {
    let finish
    const pending = new Promise(resolve => { finish = resolve })
    const context = {
      storageService: { instance: { id: 'one' }, refreshing: false, health: [] },
      storageReadScope: 'scope-one',
      storageReadErrors: {},
      listApi: jest.fn(() => pending),
      fetchInstances: jest.fn(async () => {}),
      $t: k => k,
      $notifyError: jest.fn()
    }
    const action = SharedFSTab.methods.refreshNetworkPolicy.call(context)
    context.storageReadScope = 'scope-two'; context.storageService.instance = { id: 'two' }
    finish(wire.policyHealth)
    await action
    expect(context.storageService.health).toEqual([])
    expect(context.listApi).toHaveBeenCalledWith('listStorageServiceHealth', { instanceid: 'one' }, 'storageserviceruntime')
  })
  it('keeps duplicate submissions locked and restarts the same VM without cleanup', async () => {
    const vm = await render()
    vm.openRestart()
    await vm.restart()
    expect(postAPI).not.toHaveBeenCalled()
    vm.ack = true
    vm.restart(); vm.restart()
    await flushPromises()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).toHaveBeenCalledWith('restartSharedFileSystem', { id: wire.resource.id, cleanup: false })
    expect(poll.mock.calls[0][0].jobId).toBe('restart-original-job')
    expect(vm.submitting).toBe(true)
    poll.mock.calls[0][0].successMethod()
    expect(vm.submitting).toBe(false)
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })
  it('preserves configuration protection and ignores completion after changing resource', async () => {
    const vm = await render({ protected: true })
    vm.ack = true; await vm.restart(); expect(postAPI).not.toHaveBeenCalled()
    await wrapper.setProps({ protected: false })
    vm.openRestart(); vm.ack = true; await vm.restart()
    await wrapper.setProps({ resource: { ...wire.resource, id: 'another-service' } })
    poll.mock.calls[0][0].successMethod()
    expect(wrapper.emitted('restarted')).toBeUndefined()
  })
  it('reports missing async job identity and never retries a network mutation on its own', async () => {
    postAPI.mockResolvedValue({ restartsharedfilesystemresponse: {} })
    const vm = await render(); vm.openRestart(); vm.ack = true; await vm.restart()
    expect(notify).toHaveBeenCalledTimes(1); expect(poll).not.toHaveBeenCalled()
    expect(postAPI).toHaveBeenCalledTimes(1)
  })
  it('uses VM NIC column presentation only when opted in', () => {
    wrapper = shallowMount(NicsTable, { props: { resource: { nic: [] } }, global: { provide: { parentFetchData: jest.fn() }, mocks: { $t: k => k } } })
    expect(wrapper.vm.standard).toBe(false)
    expect(wrapper.vm.nicColumns.some(c => c.dataIndex === 'gateway')).toBe(true)
    expect(wrapper.vm.standardColumns.map(c => c.key)).toEqual(['networkname', 'deviceid', 'address', 'enabled', 'actions'])
  })
  it('keeps DHCP, /31 peer and /32 no-gateway; rejects invalid STATIC before submission', async () => {
    const form = { networkmode: 'STATIC', ipcidr: '192.0.2.10/24', gateway: '192.0.2.1' }
    for (const gateway of ['192.0.2.10', '192.0.2.0', '192.0.2.255', '198.51.100.1', '224.0.0.1']) expect(staticNetworkError({ ...form, gateway }, 'gateway')).toBeTruthy()
    expect(staticNetworkError({ ...form, gateway: '' }, 'gateway')).toBe('')
    expect(staticNetworkError({ ...form, ipcidr: '192.0.2.10/31', gateway: '192.0.2.11' }, 'gateway')).toBe('')
    expect(staticNetworkError({ ...form, ipcidr: '192.0.2.10/32', gateway: '' }, 'gateway')).toBe('')
    expect(staticNetworkError({ ...form, networkmode: 'DHCP', ipcidr: '', gateway: 'bad' }, 'gateway')).toBe('')
    expect(staticNetworkError({ ...form, dns1: '255.255.255.255' }, 'dns1')).toBeTruthy()
    await expect(Create.methods.validateStaticIpCidr.call({ isStaticNetwork: true, form, $t: k => k }, {}, '192.0.2.10/33')).rejects.toBeTruthy()
  })
  it('does not treat malformed or unsuccessful runtime responses as healthy', () => {
    expect(networkPolicyObservation([{ success: false, resultjson: '{}' }]).stale).toBe(true)
    expect(networkPolicyObservation([{ success: true, resultjson: '{' }]).policy).toBe(null)
  })
})
