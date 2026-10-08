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
import SharedFSTab from '@/views/storage/SharedFSTab'
import { getAPI, postAPI } from '@/api'
import { requireAdServiceApproval, requireJoinedAdReceipt, readJoinedAdReceipt, supportsAdMaintenanceApi } from '@/utils/storageAdIdentity'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const id = '11111111-1111-4111-8111-111111111111'
const readId = '33333333-3333-4333-8333-333333333333'
const instance = () => ({ id, name: 'owned-service', virtualmachineid: 'vm-a' })
const params = command => command === 'listStorageServiceDomainStatus' ? { fresh: {} } : { maintenancewindow: {}, confirmation: {}, identitymode: {} }
const receipt = extra => ({ success: true, sideEffects: false, joinState: 'JOINED', domain: 'example.local', realm: 'EXAMPLE.LOCAL', scope: { instanceUuid: id, operationUuid: readId, revision: 4 }, bootId: '22222222-2222-4222-8222-222222222222', generatedEpoch: Date.now() / 1000, trustVerified: true, identityVerified: true, dnsAliasesVerified: true, adSpnsVerified: true, adIdentity: true, ...extra })
const response = (proof = receipt(), extra = {}) => ({ liststorageservicedomainstatusresponse: { storageidentitydomain: [{ instanceid: id, domainname: 'example.local', joinstate: 'JOINED', healthstate: 'OK', identityreceipt: proof, ...extra }] } })
const setup = extra => ({ services: ['SMB'], name: 'owned-service', smbidentitymode: 'AD', smbguestok: false, smbadprincipaltype: 'AD_USER', smbadprincipal: 'EXAMPLE\\test', smbadpermission: 'READ_WRITE', smbadmaintenancewindow: true, smbadconfirmation: 'owned-service', smbadpassword: 'synthetic-input', smbadusername: 'synthetic-user', smbaddomain: 'example.local', filesystem: 'XFS', ...extra })
const createContext = () => {
  const vm = {
    form: { smbadpassword: 'synthetic-input' },
    $t: key => key,
    $getApiParams: params,
    initialBackingVolumeId: () => 'data-a',
    isSetupServiceSelected: (snapshot, protocol) => snapshot.services.includes(protocol),
    deriveAdWorkgroup: () => 'EXAMPLE',
    runStorageServiceSetup: jest.fn().mockResolvedValue({}),
    extractCreatedId: () => 'share-a',
    toCapacityBytes: () => undefined
  }
  for (const name of ['requireInitialAdApi', 'joinInitialAdDomain', 'initialSmbAclParams']) vm[name] = CreateSharedFS.methods[name]
  return vm
}
const detailContext = () => {
  const vm = {
    storageService: { instance: instance() },
    actionLoading: {},
    currentTab: 'SMB',
    protocolWideLayout: false,
    $t: key => key,
    $getApiParams: params,
    $route: { path: '/sharedfs/owned' },
    $message: { error: jest.fn() },
    $pollJob: jest.fn().mockResolvedValue({ jobstatus: 1 }),
    refreshAfterStorageAction: jest.fn().mockResolvedValue(),
    cleanParams: CreateSharedFS.methods.cleanParams
  }
  vm.runAdDomainAction = SharedFSTab.methods.runAdDomainAction
  return vm
}
const form = extra => ({ maintenancewindow: true, confirmation: 'owned-service', domainname: 'example.local', username: 'synthetic-user', password: 'synthetic-input', ...extra })

describe('AD maintenance approval and typed fresh receipt', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  afterEach(() => jest.useRealTimers())

  it('labels default template authentication as unknown rather than observed unsupported', () => {
    const context = { form: { templateid: '' }, systemTemplates: [], $t: key => key }
    expect(CreateSharedFS.computed.nvmeAuthPreviewUnavailableLabel.call(context)).toBe('label.unknown')
    context.form.templateid = 'template-a'
    context.systemTemplates = [{ id: 'template-a', details: { 'storage.service.nvme.target.auth': 'false' } }]
    expect(CreateSharedFS.computed.nvmeAuthPreviewUnavailableLabel.call(context)).toBe('label.unsupported')
  })

  it('requires literal user approval and the exact service name rather than a domain name', () => {
    expect(requireAdServiceApproval(instance(), form())).toEqual({ maintenancewindow: true, confirmation: 'owned-service' })
    for (const extra of [{ maintenancewindow: false }, { maintenancewindow: 'true' }, { confirmation: 'example.local' }, { confirmation: 'owned-service ' }, { confirmation: 'OWNED-SERVICE' }]) expect(() => requireAdServiceApproval(instance(), form(extra))).toThrow('AD_SERVICE_APPROVAL_REQUIRED')
  })

  it('fails closed when deployed API parameters lack protected approval or fresh observation', () => {
    expect(supportsAdMaintenanceApi(params, 'joinStorageServiceToAdDomain', true)).toBe(true)
    expect(supportsAdMaintenanceApi(() => ({}), 'joinStorageServiceToAdDomain', true)).toBe(false)
    expect(supportsAdMaintenanceApi(command => command === 'listStorageServiceDomainStatus' ? {} : params(command), 'joinStorageServiceToAdDomain', true)).toBe(false)
  })

  it('accepts a fresh read observation UUID independently of the prior join job UUID', () => {
    const proof = receipt(); expect(requireJoinedAdReceipt(response(proof), instance(), 'Example.Local')).toBe(proof)
    expect(proof.scope.operationUuid).toBe(readId)
  })

  it('rejects missing typed receipt instead of using old config receipt', () => {
    expect(() => requireJoinedAdReceipt(response(undefined, { identityreceipt: undefined, config: JSON.stringify({ identityReceipt: receipt() }) }), instance(), 'example.local')).toThrow('AD_IDENTITY_RECEIPT_UNVERIFIED')
  })

  it('rejects foreign instance, domain, scope, boot and unhealthy domain records', () => {
    for (const extra of [{ instanceid: 'foreign' }, { domainname: 'foreign.local' }, { joinstate: 'JOINING' }, { healthstate: 'ERROR' }]) expect(() => requireJoinedAdReceipt(response(receipt(), extra), instance(), 'example.local')).toThrow('AD_IDENTITY_RECEIPT_UNVERIFIED')
    for (const extra of [{ scope: { instanceUuid: 'foreign', operationUuid: readId, revision: 4 } }, { bootId: '' }, { realm: 'FOREIGN.LOCAL' }, { scope: { instanceUuid: id, operationUuid: readId, revision: '4' } }]) expect(() => requireJoinedAdReceipt(response(receipt(extra)), instance(), 'example.local')).toThrow('AD_IDENTITY_RECEIPT_UNVERIFIED')
  })

  it('rejects stale/future receipts and string booleans', () => {
    const now = Date.now() / 1000
    for (const extra of [{ generatedEpoch: now - 61 }, { generatedEpoch: now + 6 }, { generatedEpoch: String(now) }, { trustVerified: 'true' }, { sideEffects: true }, { adSpnsVerified: false }]) expect(() => requireJoinedAdReceipt(response(receipt(extra)), instance(), 'example.local', now)).toThrow('AD_IDENTITY_RECEIPT_UNVERIFIED')
  })

  it('sends fresh:true only for the exact instance and bounds the actual read at 60 seconds', async () => {
    getAPI.mockResolvedValue(response())
    await readJoinedAdReceipt(instance(), 'example.local')
    expect(getAPI).toHaveBeenCalledWith('listStorageServiceDomainStatus', { instanceid: id, fresh: true }, { timeout: 60000, preserveOnFailure: true })
  })

  it('bounds a hung read and ignores its late response', async () => {
    jest.useFakeTimers(); let finish
    getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const pending = readJoinedAdReceipt(instance(), 'example.local')
    const rejected = expect(pending).rejects.toThrow('Storage service read deadline exceeded')
    jest.advanceTimersByTime(60000); await rejected
    finish(response()); await Promise.resolve()
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('rejects an instance scope changed while the read was pending', async () => {
    let finish; getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const target = instance(); const pending = readJoinedAdReceipt(target, 'example.local')
    const rejected = expect(pending).rejects.toThrow('AD_IDENTITY_RECEIPT_UNVERIFIED')
    target.id = 'foreign'; finish(response()); await rejected
  })
})

describe('SharedFS initial AD join precedes AD principal effects', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })

  it('joins, validates fresh receipt and only then creates the share with its AD ACL', async () => {
    const vm = createContext(); const order = []; const captured = setup()
    vm.runStorageServiceSetup.mockImplementation(async (api, request) => { order.push(api); if (api === 'joinStorageServiceToAdDomain') expect(request).toMatchObject({ instanceid: id, identitymode: 'JOIN_EXISTING', maintenancewindow: true, confirmation: 'owned-service' }); return {} })
    getAPI.mockImplementation(async () => { order.push('fresh'); return response() })
    await CreateSharedFS.methods.createInitialFileServices.call(vm, instance(), {}, captured)
    expect(order).toEqual(['joinStorageServiceToAdDomain', 'fresh', 'createStorageSmbShare'])
    const share = vm.runStorageServiceSetup.mock.calls.find(call => call[0] === 'createStorageSmbShare')[1]
    expect(share.aclprincipaltype).toBe('AD_USER')
    expect(share.aclprincipal).toBe('EXAMPLE\\test')
    expect(captured.smbadpassword).toBe('')
    expect(vm.form.smbadpassword).toBe('')
  })

  it('blocks missing approval or wrong name before join and clears captured credentials', async () => {
    for (const extra of [{ smbadmaintenancewindow: false }, { smbadconfirmation: 'example.local' }]) {
      const vm = createContext(); const captured = setup(extra)
      await expect(CreateSharedFS.methods.createInitialFileServices.call(vm, instance(), {}, captured)).rejects.toThrow('message.storage.service.ad.maintenance.required')
      expect(vm.runStorageServiceSetup).not.toHaveBeenCalled()
      expect(captured.smbadpassword).toBe('')
    }
  })

  it('blocks legacy API support before joining or creating an AD ACL', async () => {
    const vm = createContext(); vm.$getApiParams = () => ({})
    await expect(CreateSharedFS.methods.createInitialFileServices.call(vm, instance(), {}, setup())).rejects.toThrow('message.storage.service.ad.receipt.unverified')
    expect(vm.runStorageServiceSetup).not.toHaveBeenCalled()
  })

  it('preserves resources and creates no AD ACL after join failure', async () => {
    const vm = createContext(); const captured = setup()
    vm.runStorageServiceSetup.mockRejectedValue(new Error('controlled failure'))
    await expect(CreateSharedFS.methods.createInitialFileServices.call(vm, instance(), {}, captured)).rejects.toThrow('message.storage.service.ad.receipt.unverified')
    expect(vm.runStorageServiceSetup).toHaveBeenCalledTimes(1)
    expect(getAPI).not.toHaveBeenCalled()
    expect(captured.smbadpassword).toBe('')
  })

  it('stops after missing or stale fresh receipt without share/ACL create, retry or delete', async () => {
    for (const proof of [null, receipt({ generatedEpoch: Date.now() / 1000 - 61 })]) {
      getAPI.mockResolvedValue(response(proof)); const vm = createContext()
      await expect(CreateSharedFS.methods.createInitialFileServices.call(vm, instance(), {}, setup())).rejects.toThrow('message.storage.service.ad.receipt.unverified')
      expect(vm.runStorageServiceSetup.mock.calls.map(call => call[0])).toEqual(['joinStorageServiceToAdDomain'])
    }
  })

  it('keeps local user creation independent of AD approval/receipt APIs', async () => {
    const vm = createContext(); vm.$getApiParams = () => ({})
    await CreateSharedFS.methods.createInitialFileServices.call(vm, instance(), {}, setup({ smbidentitymode: 'LOCAL', smblocalusername: 'local-user', smblocalpassword: 'synthetic-local' }))
    expect(getAPI).not.toHaveBeenCalled()
    expect(vm.runStorageServiceSetup.mock.calls.map(call => call[0])).toEqual(['createStorageSmbShare'])
  })
})

describe('Detailed AD join/rejoin/leave protected payloads', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset(); postAPI.mockResolvedValue({ ['joinStorageServiceToAdDomain'.toLowerCase() + 'response']: { jobid: 'join-job-a' } }) })

  it('requires user approval and exact instance name before posting', async () => {
    for (const extra of [{ maintenancewindow: false }, { confirmation: 'example.local' }]) {
      const vm = detailContext(); const captured = form(extra)
      await vm.runAdDomainAction('adJoin', 'joinStorageServiceToAdDomain', captured, 'join', true)
      expect(postAPI).not.toHaveBeenCalled()
      expect(vm.$message.error).toHaveBeenCalledWith('message.storage.service.ad.maintenance.required')
      expect(captured.password).toBe('')
    }
  })

  it('waits for the join async job before querying its fresh typed receipt', async () => {
    const order = []; const vm = detailContext()
    postAPI.mockImplementation(async () => { order.push('join'); return { ['joinStorageServiceToAdDomain'.toLowerCase() + 'response']: { jobid: 'join-job-a' } } })
    vm.$pollJob.mockImplementation(async () => { order.push('job-success'); return { jobstatus: 1 } })
    getAPI.mockImplementation(async () => { order.push('fresh'); return response() })
    await vm.runAdDomainAction('adJoin', 'joinStorageServiceToAdDomain', form(), 'join', true)
    expect(order).toEqual(['join', 'job-success', 'fresh'])
    expect(postAPI.mock.calls[0][1]).toMatchObject({ instanceid: id, maintenancewindow: true, confirmation: 'owned-service', identitymode: 'JOIN_EXISTING' })
    expect(vm.refreshAfterStorageAction).toHaveBeenCalledTimes(1)
  })

  it('includes actual leave confirmation and maintenance approval without exposing NEW_INSTANCE', async () => {
    postAPI.mockResolvedValue({ ['leaveStorageServiceFromAdDomain'.toLowerCase() + 'response']: { jobid: 'leave-job-a' } })
    const vm = detailContext(); const captured = form()
    await vm.runAdDomainAction('adLeave', 'leaveStorageServiceFromAdDomain', captured, 'leave', false)
    expect(postAPI.mock.calls[0][1]).toMatchObject({ instanceid: id, maintenancewindow: true, confirmation: 'owned-service' })
    expect(postAPI.mock.calls[0][1].identitymode).toBeUndefined()
    expect(getAPI).not.toHaveBeenCalled()
    expect(captured.password).toBe('')
  })

  it('rejects missing legacy approval fields without posting', async () => {
    const vm = detailContext(); vm.$getApiParams = () => ({})
    await vm.runAdDomainAction('adJoin', 'joinStorageServiceToAdDomain', form(), 'join', true)
    expect(postAPI).not.toHaveBeenCalled()
    expect(vm.$message.error).toHaveBeenCalledWith('message.storage.service.ad.maintenance.unsupported')
  })

  it('does not refresh as success after failed/unknown async join and discards secrets', async () => {
    postAPI.mockResolvedValue({ ['joinStorageServiceToAdDomain'.toLowerCase() + 'response']: { jobid: 'join-job-a' } })
    for (const result of [{ jobstatus: 2 }, { trackingStatus: 'unknown' }]) {
      const vm = detailContext(); const captured = form(); vm.$pollJob.mockResolvedValue(result)
      await vm.runAdDomainAction('adJoin', 'joinStorageServiceToAdDomain', captured, 'join', true)
      expect(getAPI).not.toHaveBeenCalled()
      expect(vm.refreshAfterStorageAction).not.toHaveBeenCalled()
      expect(captured.password).toBe('')
    }
  })

  it('clears the actual request object and displays no raw transport error after a failed post', async () => {
    const vm = detailContext(); const captured = form()
    postAPI.mockRejectedValue({ config: { data: 'untrusted transport details' } })
    await vm.runAdDomainAction('adJoin', 'joinStorageServiceToAdDomain', captured, 'join', true)
    expect(postAPI.mock.calls[0][1].password).toBe('')
    expect(captured.password).toBe('')
    expect(vm.$message.error).toHaveBeenCalledWith('message.storage.service.ad.receipt.unverified')
    expect(getAPI).not.toHaveBeenCalled()
  })

  it('ignores a changed active instance after the job completes', async () => {
    postAPI.mockResolvedValue({ ['joinStorageServiceToAdDomain'.toLowerCase() + 'response']: { jobid: 'join-job-a' } })
    const vm = detailContext(); vm.$pollJob.mockImplementation(async () => { vm.storageService.instance = { id: 'foreign', name: 'foreign' }; return { jobstatus: 1 } })
    await vm.runAdDomainAction('adJoin', 'joinStorageServiceToAdDomain', form(), 'join', true)
    expect(getAPI).not.toHaveBeenCalled()
    expect(vm.refreshAfterStorageAction).not.toHaveBeenCalled()
  })
})
