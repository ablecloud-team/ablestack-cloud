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

import { allocationIntent, allocationForm, allocationParams, allocationOfferingReason, poolAcceptsIntent, readAllocationList, readAllocationCapability } from '@/utils/volumeAllocation'
const explicit = (policy = 'full', format = 'raw') => ({ provisioningtype: 'fat', allocation: { contractversion: '1', requestedpolicy: policy, requestedformat: format } })
test.each(['thin', 'sparse', 'fat'])('legacy %s clone never sends explicit fields', provisioningtype => {
  expect(allocationParams(allocationForm({ provisioningtype }))).toEqual({ provisioningtype })
})
test.each([['metadata', 'qcow2'], ['falloc', 'qcow2'], ['full', 'qcow2'], ['off', 'raw'], ['falloc', 'raw'], ['full', 'raw']])('%s/%s clone retains literal contract without provisioningtype', (p, f) => {
  expect(allocationParams(allocationForm(explicit(p, f)))).toEqual({ allocationpolicy: p, allocationformat: f })
})
test('linked root disk suppresses competing allocation and legacy parameters', () => {
  expect(allocationParams(allocationForm(explicit()), true)).toEqual({})
  expect(allocationParams({ provisioningtype: 'fat' }, true)).toEqual({})
})
test('RBD accepts only raw off/full while GFS mount still needs capability verification', () => {
  for (const i of [['metadata', 'qcow2'], ['falloc', 'raw'], ['falloc', 'qcow2'], ['full', 'qcow2']]) expect(poolAcceptsIntent({ type: 'RBD' }, explicit(...i))).toBe(false)
  for (const p of ['off', 'full']) expect(poolAcceptsIntent({ type: 'RBD' }, explicit(p))).toBe(true)
})
test('offering swaps preserve policy, format and legacy semantics', () => {
  expect(allocationOfferingReason(explicit(), explicit())).toBe('')
  expect(allocationOfferingReason(explicit(), explicit('off'))).toContain('unsupported')
  expect(allocationOfferingReason(explicit(), explicit('full', 'qcow2'))).toContain('unsupported')
  expect(allocationOfferingReason({ provisioningtype: 'fat' }, { provisioningtype: 'thin' })).toContain('unsupported')
})
test('multi-page reads fail closed on repeated pages and keep stale results unpublished', async () => {
  const api = jest.fn().mockResolvedValue({ listdiskofferingsresponse: { count: 2, diskoffering: [{ id: 'a' }] } })
  await expect(readAllocationList(api, 'listDiskOfferings', 'diskoffering')).rejects.toThrow('Incomplete')
  expect(await readAllocationList(api, 'listDiskOfferings', 'diskoffering', {}, () => false)).toBeNull()
})
const pool = { id: 'p', zoneid: 'z', clusterid: 'c', type: 'RBD' }
function capabilityApi (extra = {}) {
  return jest.fn((cmd, args) => Promise.resolve(cmd === 'listHosts'
    ? { listhostsresponse: { count: 1, host: [{ id: 'h', state: 'Up', hypervisor: 'KVM' }] } }
    : { getvolumeallocationcapabilitiesresponse: { volumeallocationcapability: { contractversion: 1, storageid: 'p', hostid: 'h', allocationformat: args.allocationformat, operation: args.operation, backend: 'RBD', supported: true, agentpolicies: { off: 'rbd-thin', full: 'rbd-thick-create' }, ...extra } } }))
}
test('policy choices are obtained from scoped server capability', async () => {
  const api = capabilityApi()
  expect((await readAllocationCapability(api, pool, 'raw')).policies).toEqual({ off: 'rbd-thin', full: 'rbd-thick-create' })
  expect(api).toHaveBeenCalledWith('getVolumeAllocationCapabilities', expect.objectContaining({ storageid: 'p', hostid: 'h', allocationformat: 'raw', operation: 'create' }), { preserveOnFailure: true })
})
test.each([{ supported: false }, { storageid: 'other' }, { contractversion: 2 }, { backend: 'GFS2' }, { operation: 'resize' }, { allocationformat: 'qcow2' }])('unverified response %p never enables submit', async extra => {
  await expect(readAllocationCapability(capabilityApi(extra), pool, 'raw')).rejects.toThrow('failed')
})
test('RBD malformed policy declarations cannot expose falloc/metadata', async () => {
  const cap = await readAllocationCapability(capabilityApi({ agentpolicies: { off: 'rbd-thin', falloc: 'invented', metadata: 'invented' } }), pool, 'raw')
  expect(Object.keys(cap.policies)).toEqual(['off'])
})
test('permission/transport failures do not masquerade as empty successful capabilities', async () => {
  const api = capabilityApi(); const base = api.getMockImplementation()
  api.mockImplementation((c, a) => c === 'listHosts' ? base(c, a) : Promise.reject(new Error('denied')))
  await expect(readAllocationCapability(api, pool, 'raw')).rejects.toThrow('failed')
})
test('missing contract is not inferred from legacy fat or offering name', () => {
  expect(allocationIntent({ name: 'FULL', provisioningtype: 'fat' })).toBeNull()
})
