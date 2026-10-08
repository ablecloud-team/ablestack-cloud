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

import CreateSharedFS from '@/views/storage/CreateSharedFS'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const methods = CreateSharedFS.methods
const instance = () => ({ id: 'instance-a', virtualmachineid: 'vm-a' })
const capability = extra => ({ kernelTargetSupported: true, configfsHostSupported: true, dhChapSupported: true, dhChapCtrlSupported: true, ...extra })
const observation = (extra = {}, row = {}) => ({ liststorageserviceinventoryresponse: { storageserviceruntime: [{ id: 'instance-a', success: true, resultjson: JSON.stringify({ capabilities: { nvmeof: capability(extra) } }), ...row }] } })
const snapshot = extra => ({ services: ['NVME_OF'], nvmeengine: 'KERNEL_NVMET', nvmetransport: 'tcp', nvmesubsystemnqn: 'nqn.test:subsystem', nvmehostnqn: 'nqn.test:host', nvmedhchapenabled: true, nvmedhchapctrlenabled: false, nvmedhchapkey: 'synthetic-host-input', nvmedhchapctrlkey: 'synthetic-controller-input', ...extra })
const context = () => {
  const vm = {
    form: snapshot(),
    nvmeAuthReadToken: 0,
    $t: key => key,
    $store: { getters: { apis: { listStorageServiceInventory: {} } } },
    initialBackingVolumeId: () => 'data-a',
    isSetupServiceSelected: (setup, protocol) => setup.services.includes(protocol),
    toCapacityBytes: () => undefined,
    extractCreatedId: () => 'subsystem-a',
    runStorageServiceSetup: jest.fn().mockResolvedValue({ id: 'instance-a', success: true }),
    notifyStorageServiceSetup: jest.fn(),
    parentFetchData: jest.fn()
  }
  for (const name of ['initialNvmeAuthRequest', 'requireInitialNvmeAuthCapabilities', 'normalizeApiItems', 'parseRuntimeResultJson', 'clearInitialBlockSecrets', 'clearInitialNvmeAuth', 'parseStorageServiceItemConfig', 'isNvmeSubsystemItem']) vm[name] = methods[name]
  return vm
}
const create = (vm, setup) => methods.createInitialBlockServices.call(vm, instance(), {}, setup)

describe('SharedFS initial NVMe authentication capability boundary', () => {
  beforeEach(() => getAPI.mockReset())
  afterEach(() => jest.useRealTimers())

  it('uses explicit eligible metadata only as a prospective input condition', () => {
    const vm = { form: { templateid: 'template-a', nvmeengine: 'KERNEL_NVMET', nvmetransport: 'tcp' }, selectedZone: { id: 'zone-a' }, systemTemplates: [{ id: 'template-a', templatetype: 'SYSTEM', isready: true, isdynamicallyscalable: true, hypervisor: 'KVM', zoneid: 'zone-a', details: { 'storage.service.nvme.target.auth': 'true' } }], isSelectableSystemTemplate: methods.isSelectableSystemTemplate }
    expect(CreateSharedFS.computed.nvmeDhChapCreateSupported.call(vm)).toBe(true)
    for (const extra of [{ templateLoading: true }, { templateReadError: true }, { form: { ...vm.form, templateid: '' } }, { form: { ...vm.form, nvmeengine: 'SPDK' } }]) expect(CreateSharedFS.computed.nvmeDhChapCreateSupported.call({ ...vm, ...extra })).toBe(false)
    vm.systemTemplates[0].details = {}
    expect(CreateSharedFS.computed.nvmeDhChapCreateSupported.call(vm)).toBe(false)
  })

  it('invalidates old credentials when template, engine, transport or host context changes', () => {
    for (const field of ['form.templateid', 'form.nvmeengine', 'form.nvmetransport', 'form.nvmehostnqn']) {
      const vm = context()
      CreateSharedFS.watch[field].call(vm)
      expect(vm.form.nvmedhchapenabled).toBe(false)
      expect(vm.form.nvmedhchapctrlenabled).toBe(false)
      expect(vm.form.nvmedhchapkey).toBe('')
      expect(vm.form.nvmedhchapctrlkey).toBe('')
    }
  })

  it('requires fresh capability before subsystem, namespace and ACL creation and preserves explicit authentication', async () => {
    const vm = context(); const order = []
    vm.nvmeDhChapCreateSupported = false // Post-create decisions must use actual capability, not a mutable preview.
    vm.runStorageServiceSetup.mockImplementation(async api => { order.push(api); return { id: 'instance-a', success: true } })
    getAPI.mockImplementation(async (api, params, options) => {
      order.push(api)
      expect(params).toEqual({ instanceid: 'instance-a' })
      expect(options).toEqual({ timeout: 15000, preserveOnFailure: true })
      return observation()
    })
    const setup = snapshot({ nvmedhchapctrlenabled: true })
    await create(vm, setup)
    expect(order).toEqual(['prepareStorageServiceNvmeOfVm', 'listStorageServiceInventory', 'createStorageNvmeOfSubsystem', 'createStorageNvmeOfNamespace', 'createStorageNvmeOfHostAcl'])
    const acl = vm.runStorageServiceSetup.mock.calls.find(call => call[0] === 'createStorageNvmeOfHostAcl')[1]
    expect(acl.dhchapenabled).toBe(true)
    expect(acl.dhchapctrlenabled).toBe(true)
    expect(acl.dhchapkey).toBe('synthetic-host-input')
    expect(acl.dhchapctrlkey).toBe('synthetic-controller-input')
    expect(setup.nvmedhchapkey).toBe('')
    expect(vm.form.nvmedhchapkey).toBe('')
  })

  it('allows one-way authentication without falsely requiring controller support', async () => {
    getAPI.mockResolvedValue(observation({ dhChapCtrlSupported: false }))
    const vm = context(); await create(vm, snapshot())
    const acl = vm.runStorageServiceSetup.mock.calls.find(call => call[0] === 'createStorageNvmeOfHostAcl')[1]
    expect(acl.dhchapenabled).toBe(true)
    expect(acl.dhchapctrlenabled).toBe(false)
    expect(acl.dhchapctrlkey).toBe('')
  })

  it('blocks unsupported mutual authentication before every block resource and never downgrades it', async () => {
    getAPI.mockResolvedValue(observation({ dhChapCtrlSupported: false }))
    const vm = context(); const setup = snapshot({ nvmedhchapctrlenabled: true, services: ['ISCSI', 'NVME_OF'], iscsitargetname: 'iscsi-a' })
    await expect(create(vm, setup)).rejects.toThrow('message.storage.service.nvme.dhchap.unsupported')
    expect(vm.runStorageServiceSetup.mock.calls.map(call => call[0])).toEqual(['prepareStorageServiceNvmeOfVm'])
    expect(setup.nvmedhchapctrlkey).toBe('')
  })

  it('rejects string booleans and missing kernel/configfs/auth attributes before target effects', async () => {
    for (const extra of [{ dhChapSupported: 'true' }, { dhChapSupported: false }, { kernelTargetSupported: false }, { configfsHostSupported: undefined }]) {
      getAPI.mockResolvedValue(observation(extra)); const vm = context()
      await expect(create(vm, snapshot())).rejects.toThrow('message.storage.service.nvme.dhchap.unsupported')
      expect(vm.runStorageServiceSetup).toHaveBeenCalledTimes(1)
    }
  })

  it('rejects wrong-instance, unsuccessful, duplicate and malformed observations', async () => {
    const duplicate = observation(); duplicate.liststorageserviceinventoryresponse.storageserviceruntime.push({ ...duplicate.liststorageserviceinventoryresponse.storageserviceruntime[0] })
    for (const response of [observation({}, { id: 'foreign' }), observation({}, { success: 'true' }), observation({}, { success: false }), observation({}, { resultjson: '{' }), duplicate]) {
      getAPI.mockResolvedValue(response); const vm = context()
      await expect(create(vm, snapshot())).rejects.toThrow('message.storage.service.nvme.dhchap.unsupported')
      expect(vm.runStorageServiceSetup).toHaveBeenCalledTimes(1)
    }
  })

  it('bounds a hung read, ignores its late response and clears secrets without target creation', async () => {
    jest.useFakeTimers(); let finish
    getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const vm = context(); const setup = snapshot(); const pending = create(vm, setup)
    const rejected = expect(pending).rejects.toThrow('message.storage.service.authentication.unknown.help')
    for (let step = 0; step < 10; step++) {
      await Promise.resolve()
      if (finish) break
    }
    expect(finish).toBeDefined()
    jest.advanceTimersByTime(15000)
    await rejected
    finish(observation()); await Promise.resolve()
    expect(vm.runStorageServiceSetup).toHaveBeenCalledTimes(1)
    expect(vm.form.nvmedhchapkey).toBe('')
    expect(setup.nvmedhchapkey).toBe('')
  })

  it('rejects an older capability request that completes after a newer request', async () => {
    let finish
    getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockResolvedValueOnce(observation())
    const vm = context(); const old = vm.requireInitialNvmeAuthCapabilities(instance(), { host: true, controller: false })
    const rejected = expect(old).rejects.toThrow('message.storage.service.authentication.unknown.help')
    await vm.requireInitialNvmeAuthCapabilities(instance(), { host: true, controller: false })
    finish(observation()); await rejected
  })

  it('rejects a scope object changed while the capability request is pending', async () => {
    let finish; getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const vm = context(); const target = instance(); const pending = vm.requireInitialNvmeAuthCapabilities(target, { host: true })
    const rejected = expect(pending).rejects.toThrow('message.storage.service.authentication.unknown.help')
    target.virtualmachineid = 'foreign-vm'; finish(observation()); await rejected
  })

  it('keeps a read failure safe and performs no target effects', async () => {
    getAPI.mockRejectedValue(new Error('untrusted response body'))
    const vm = context()
    await expect(create(vm, snapshot())).rejects.toThrow('message.storage.service.authentication.unknown.help')
    expect(vm.runStorageServiceSetup).toHaveBeenCalledTimes(1)
    expect(vm.form.nvmedhchapctrlkey).toBe('')
  })

  it('rejects a structured unsuccessful or foreign preparation result before the capability read', async () => {
    for (const result of [{ id: 'instance-a', success: false }, { id: 'instance-a', success: 'true' }, { id: 'foreign', success: true }]) {
      const vm = context(); vm.runStorageServiceSetup.mockResolvedValue(result)
      await expect(create(vm, snapshot())).rejects.toThrow('message.storage.service.authentication.unknown.help')
      expect(getAPI).not.toHaveBeenCalled()
      expect(vm.runStorageServiceSetup).toHaveBeenCalledTimes(1)
    }
  })

  it('leaves authentication-free creation independent of capability APIs', async () => {
    const vm = context(); vm.$store.getters.apis = {}
    await create(vm, snapshot({ nvmedhchapenabled: false, nvmedhchapctrlenabled: false }))
    expect(getAPI).not.toHaveBeenCalled()
    const acl = vm.runStorageServiceSetup.mock.calls.find(call => call[0] === 'createStorageNvmeOfHostAcl')[1]
    expect(acl.dhchapenabled).toBe(false)
    expect(acl.dhchapkey).toBe('')
  })

  it('rejects contradictory flags, missing credentials, SPDK and foreign transport before prepare effects', async () => {
    for (const extra of [{ nvmedhchapenabled: false, nvmedhchapctrlenabled: true }, { nvmedhchapenabled: 'true' }, { nvmehostnqn: '' }, { nvmedhchapkey: '' }, { nvmeengine: 'SPDK' }, { nvmetransport: 'rdma' }]) {
      const vm = context()
      await expect(create(vm, snapshot(extra))).rejects.toThrow('message.storage.service.authentication.unknown.help')
      expect(vm.runStorageServiceSetup).not.toHaveBeenCalled()
    }
  })

  it('clears form and snapshot secrets after prepare or ACL failure without automatic cleanup or retry', async () => {
    for (const failedApi of ['prepareStorageServiceNvmeOfVm', 'createStorageNvmeOfHostAcl']) {
      getAPI.mockResolvedValue(observation()); const vm = context(); const setup = snapshot()
      vm.runStorageServiceSetup.mockImplementation(async api => { if (api === failedApi) throw new Error('controlled stage failure'); return { id: 'instance-a', success: true } })
      await expect(create(vm, setup)).rejects.toThrow('controlled stage failure')
      expect(vm.form.nvmedhchapkey).toBe('')
      expect(setup.nvmedhchapkey).toBe('')
      expect(vm.runStorageServiceSetup.mock.calls.filter(call => call[0] === failedApi)).toHaveLength(1)
      expect(vm.runStorageServiceSetup.mock.calls.some(call => call[0].startsWith('delete'))).toBe(false)
    }
  })

  it('rejects a created-VM binding mismatch before any protocol or resource setup', async () => {
    const vm = context(); const setup = snapshot()
    vm.assertStorageServiceSetupApis = jest.fn()
    vm.resolveCreatedSharedFileSystem = jest.fn().mockResolvedValue({ virtualmachineid: 'vm-a' })
    vm.findStorageServiceInstance = jest.fn().mockResolvedValue({ id: 'foreign', virtualmachineid: 'foreign-vm' })
    vm.enableSelectedProtocols = jest.fn()
    await expect(methods.configureInitialStorageServices.call(vm, {}, setup, 'notification-a')).rejects.toThrow('message.storage.service.authentication.unknown.help')
    expect(vm.enableSelectedProtocols).not.toHaveBeenCalled()
    expect(vm.runStorageServiceSetup).not.toHaveBeenCalled()
    expect(setup.nvmedhchapkey).toBe('')
    expect(vm.parentFetchData).toHaveBeenCalledTimes(1)
  })

  it('does not select a same-name foreign VM when the created VM ID is known', async () => {
    getAPI.mockResolvedValue({ liststorageserviceinstancesresponse: { storageserviceinstance: [{ id: 'foreign', name: 'same-name', virtualmachineid: 'foreign-vm' }] } })
    const vm = context(); vm.firstListValue = methods.firstListValue; vm.delay = jest.fn().mockResolvedValue()
    await expect(methods.findStorageServiceInstance.call(vm, { virtualmachineid: 'vm-a' }, { zoneid: 'zone-a', name: 'same-name' })).resolves.toBeNull()
    expect(getAPI).toHaveBeenCalledTimes(40)
  })

  it('verifies requested flags from metadata after secrets have been erased and rejects an unauthenticated ACL', async () => {
    for (const enabled of [true, false]) {
      const vm = context(); vm.fetchStorageServiceItems = jest.fn().mockImplementation(async api => api === 'listStorageNvmeOfSubsystems'
        ? [{ id: 'subsystem-a', targetname: 'nqn.test:subsystem', config: '{"type":"subsystem"}' }]
        : [{ principal: 'nqn.test:host', config: JSON.stringify({ dhChapEnabled: enabled, dhChapCtrlEnabled: true }) }])
      const pending = methods.verifyInitialStorageServiceSetup.call(vm, instance(), { nvmeSubsystemId: 'subsystem-a' }, snapshot({ nvmedhchapctrlenabled: true, nvmedhchapkey: '', nvmedhchapctrlkey: '' }))
      if (enabled) await expect(pending).resolves.toBeUndefined()
      else await expect(pending).rejects.toThrow('message.storage.service.authentication.unknown.help')
    }
  })

  it('discards captured credentials when the create job fails before instance setup', async () => {
    const vm = context(); const setup = snapshot()
    vm.pollStorageServiceSetupJob = jest.fn().mockRejectedValue(new Error('create failed'))
    await methods.runInitialStorageServiceSetup.call(vm, 'job-a', setup, 'notification-a')
    expect(setup.nvmedhchapkey).toBe('')
    expect(vm.notifyStorageServiceSetup.mock.calls.some(call => call[1] === 'success')).toBe(false)
  })
})
