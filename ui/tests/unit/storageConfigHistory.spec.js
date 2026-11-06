// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import { getAPI } from '@/api'
import Panel from '@/components/storage/StorageConfigurationOperationPanel.vue'
import serializerWire from '../fixtures/storage-configuration-operation-wire.json'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const original = serializerWire.getstorageserviceconfigurationoperationresponse.configurationoperation
const listWire = { liststorageserviceconfigurationoperationsresponse: { configurationoperation: [original] } }
const render = () => shallowMount(Panel, {
  props: { history: true, instanceid: 'instance-a' },
  global: {
    mocks: { $t: key => key },
    stubs: { 'a-button': { template: '<button><slot /></button>' } }
  }
})

describe('configuration history component request contract', () => {
  beforeEach(() => getAPI.mockReset())

  test('initial history read supplies the paired pagination parameters required by BaseListCmd', async () => {
    getAPI.mockImplementation(async (command, params) => {
      // The deployed BaseListCmd rejects pagesize without page before DAO execution.
      if (params.pagesize !== undefined && params.page === undefined) {
        throw new Error('"page" parameter is required when "pagesize" is specified')
      }
      return listWire
    })
    const wrapper = render()
    await flushPromises()
    expect(getAPI).toHaveBeenCalledWith('listStorageServiceConfigurationOperations', {
      instanceid: 'instance-a', page: 1, pagesize: 200
    })
    expect(wrapper.vm.readFailed).toBe(false)
    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.rows).toEqual([original])
    expect(wrapper.vm.rows[0].originalfailure).toEqual(original.originalfailure)
    wrapper.unmount()
  })

  test('the visible update button repeats only the valid GET and clears a previous read failure', async () => {
    getAPI.mockRejectedValueOnce(new Error('temporary read failure')).mockResolvedValue(listWire)
    const wrapper = render()
    await flushPromises()
    expect(wrapper.vm.readFailed).toBe(true)
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(2)
    for (const call of getAPI.mock.calls) {
      expect(call).toEqual(['listStorageServiceConfigurationOperations', {
        instanceid: 'instance-a', page: 1, pagesize: 200
      }])
    }
    expect(wrapper.vm.readFailed).toBe(false)
    expect(wrapper.vm.rows).toEqual([original])
    expect(wrapper.emitted('resume')).toBeUndefined()
    wrapper.unmount()
  })

  test('an old instance response cannot overwrite the new instance history', async () => {
    let resolveOld
    getAPI.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
      .mockResolvedValue({ liststorageserviceconfigurationoperationsresponse: {} })
    const wrapper = render()
    await wrapper.setProps({ instanceid: 'instance-b' })
    await flushPromises()
    resolveOld(listWire)
    await flushPromises()
    expect(getAPI).toHaveBeenLastCalledWith('listStorageServiceConfigurationOperations', {
      instanceid: 'instance-b', page: 1, pagesize: 200
    })
    expect(wrapper.vm.rows).toEqual([])
    expect(wrapper.vm.readFailed).toBe(false)
    expect(wrapper.vm.loading).toBe(false)
    wrapper.unmount()
  })
})
