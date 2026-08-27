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
