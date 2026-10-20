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
import { mount } from '@vue/test-utils'

const baseContext = customizedIops => ({
  isCustomizedDiskIOps: customizedIops,
  isStaticNetwork: false,
  owner: { domainid: 'domain-1', account: 'admin' },
  createSharedFsSize: () => undefined,
  hasFormValue: CreateSharedFS.methods.hasFormValue,
  cleanParams: CreateSharedFS.methods.cleanParams
})

const values = {
  name: 'sharedfs-test',
  description: '',
  zoneid: 'zone-1',
  serviceofferingid: 'service-offering-1',
  diskofferingid: 'disk-offering-1',
  networkid: 'network-1',
  filesystem: 'XFS',
  storageid: 'storage-1'
}

describe('CreateSharedFS request normalization', () => {
  it('retains the selected volume when the generic form filter omits an unreferenced field', () => {
    const context = { ...baseContext(false), form: { useexistingvolume: true, existingvolumeid: 'retained-volume' } }
    const request = CreateSharedFS.methods.buildCreateSharedFsRequest.call(context, values)
    expect(request.backingvolumemode).toBe('EXISTING')
    expect(request.existingvolumeid).toBe('retained-volume')
    expect(request.diskofferingid).toBeUndefined()
  })
  it('uses an existing volume without hidden offering, size, pool or formatting parameters', () => {
    const context = baseContext(true)
    const request = CreateSharedFS.methods.buildCreateSharedFsRequest.call(context, {
      ...values, useexistingvolume: true, existingvolumeid: 'preserved-volume', size: 20, miniops: 1000, maxiops: 2000
    })
    expect(request.backingvolumemode).toBe('EXISTING')
    expect(request.existingvolumeid).toBe('preserved-volume')
    for (const field of ['diskofferingid', 'storageid', 'size', 'miniops', 'maxiops', 'filesystem']) expect(request[field]).toBeUndefined()
  })
  it('omits IOPS for a non-customized IOPS offering', () => {
    const context = baseContext(false)
    const request = CreateSharedFS.methods.buildCreateSharedFsRequest.call(context, {
      ...values,
      miniops: undefined,
      maxiops: undefined
    })

    expect(request.miniops).toBeUndefined()
    expect(request.maxiops).toBeUndefined()
    expect(request.storageid).toBe('storage-1')
  })

  it('includes a complete customized IOPS pair as numbers', () => {
    const context = baseContext(true)
    const request = CreateSharedFS.methods.buildCreateSharedFsRequest.call(context, {
      ...values,
      miniops: '1000',
      maxiops: '2000'
    })

    expect(request.miniops).toBe(1000)
    expect(request.maxiops).toBe(2000)
  })
})

describe('CreateSharedFS L2 network mode', () => {
  it('detects an L2 network from the listNetworks type field', () => {
    expect(CreateSharedFS.computed.isSelectedNetworkL2.call({
      selectedNetwork: { type: 'L2' }
    })).toBe(true)
  })

  it('detects UserData support from the network service list', () => {
    expect(CreateSharedFS.computed.selectedNetworkSupportsUserData.call({
      selectedNetwork: { service: [{ name: 'Connectivity' }, { name: 'UserData' }] }
    })).toBe(true)
    expect(CreateSharedFS.computed.selectedNetworkSupportsUserData.call({
      selectedNetwork: { service: [{ name: 'Connectivity' }] }
    })).toBe(false)
  })

  it('sends one ipcidr value and optional network values for static mode', () => {
    const context = { ...baseContext(false), isStaticNetwork: true }
    const request = CreateSharedFS.methods.buildCreateSharedFsRequest.call(context, {
      ...values,
      ipcidr: '10.10.1.211/24',
      gateway: '10.10.1.1',
      dns1: '10.10.1.10',
      dns2: '8.8.8.8'
    })

    expect(request.ipcidr).toBe('10.10.1.211/24')
    expect(request.ipaddress).toBeUndefined()
    expect(request.cidr).toBeUndefined()
    expect(request.gateway).toBe('10.10.1.1')
    expect(request.dns1).toBe('10.10.1.10')
    expect(request.dns2).toBe('8.8.8.8')
  })

  it('validates IPv4/prefix notation', async () => {
    const validate = CreateSharedFS.methods.validateStaticIpCidr
    const context = { isStaticNetwork: true, $t: key => key }

    await expect(validate.call(context, null, '10.10.1.211/24')).resolves.toBeUndefined()
    await expect(validate.call(context, null, '10.10.1.999/24')).rejects.toBe('message.sharedfs.static.ip.cidr.invalid')
    await expect(validate.call(context, null, '10.10.1.211/33')).rejects.toBe('message.sharedfs.static.ip.cidr.invalid')
  })
})

describe('#974 partial initial configuration recovery notification', () => {
  const setup = { name: 'large-created-service', services: ['NFS'] }
  function background (resolved) {
    const vm = {
      form: {},
      initialAdInteractive: false,
      initialAdDisposed: false,
      $t: key => key,
      notifyStorageServiceSetup: jest.fn(),
      clearInitialBlockSecrets: jest.fn(),
      clearInitialAdCredentials: jest.fn(),
      parentFetchData: jest.fn(),
      assertInitialSetupActive: jest.fn(),
      waitForInitialPreparation: jest.fn().mockResolvedValue({ ready: true }),
      clearInitialCredentials: jest.fn(),
      assertStorageServiceSetupApis: jest.fn(),
      pollStorageServiceSetupJob: resolved ? jest.fn().mockResolvedValue({}) : jest.fn().mockRejectedValue(new Error('Traceback: raw internal failure')),
      resolveCreatedSharedFileSystem: jest.fn().mockResolvedValue({ id: 'created-fs', volumeid: 'created-data' }),
      findStorageServiceInstance: jest.fn().mockResolvedValue({ id: 'created-instance' }),
      isSetupServiceSelected: () => false,
      enableSelectedProtocols: jest.fn().mockResolvedValue({}),
      createInitialFileServices: jest.fn().mockRejectedValue(new Error('Traceback: mkfs.xfs timed out')),
      createInitialBlockServices: jest.fn(),
      verifyInitialStorageServiceSetup: jest.fn()
    }
    vm.configureInitialStorageServices = CreateSharedFS.methods.configureInitialStorageServices.bind(vm)
    if (CreateSharedFS.methods.initialSetupFailureDescription) vm.initialSetupFailureDescription = CreateSharedFS.methods.initialSetupFailureDescription.bind(vm)
    return vm
  }
  it('links only the already resolved server resource to its volume preparation tab and replaces raw traceback', async () => {
    const vm = background(true)
    await CreateSharedFS.methods.runInitialStorageServiceSetup.call(vm, 'original-job', setup, 'notification')
    const notification = vm.notifyStorageServiceSetup.mock.calls.find(([, type]) => type === 'error')
    const content = notification[3]
    const wrapper = mount({ render: () => content })
    expect(wrapper.text()).not.toContain('Traceback')
    expect(wrapper.text()).toContain('message.storage.volume.setup.failed.summary')
    expect(wrapper.get('a').attributes('href')).toBe('#/sharedfs/created-fs?tab=operations&volumeid=created-data')
    expect(vm.createInitialFileServices).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it('does not guess a resource link or leak raw errors when creation has no confirmed resource ID', async () => {
    const vm = background(false)
    await CreateSharedFS.methods.runInitialStorageServiceSetup.call(vm, 'unobserved-job', setup, 'notification')
    const content = vm.notifyStorageServiceSetup.mock.calls.find(([, type]) => type === 'error')[3]
    const wrapper = mount({ render: () => content })
    expect(wrapper.text()).not.toContain('Traceback')
    expect(wrapper.find('a').exists()).toBe(false)
    expect(vm.resolveCreatedSharedFileSystem).not.toHaveBeenCalled()
    expect(vm.createInitialFileServices).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
