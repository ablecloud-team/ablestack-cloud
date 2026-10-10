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

import Widget from '@/views/storage/SmbNetworkAccess'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const context = () => ({ instanceId: 'a', requestToken: 0, rules: [{ id: 'known' }], loading: false, readError: false })
describe('SMB source policy reads and summaries', () => {
  beforeEach(() => getAPI.mockReset())
  it('keeps known rules visible when a read fails', async () => {
    getAPI.mockRejectedValue(new Error('timeout')); const vm = context()
    await Widget.methods.refresh.call(vm)
    expect(vm.rules).toEqual([{ id: 'known' }]); expect(vm.readError).toBe(true); expect(vm.loading).toBe(false)
  })
  it('ignores a late response from a previously selected instance', async () => {
    let complete
    getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = context(); const pending = Widget.methods.refresh.call(vm)
    vm.instanceId = 'b'; vm.requestToken++; complete({ liststoragesmbnetworkaclsresponse: { storageaccessrule: [{ id: 'old' }] } })
    await pending; expect(vm.rules).toEqual([{ id: 'known' }])
  })
  it('warns specifically before removing the last rule of a share', () => {
    const vm = { deleteTarget: { resourceid: 'x' }, rules: [{ resourceid: 'x' }, { resourceid: 'y' }] }
    expect(Widget.computed.isLastRule.call(vm)).toBe(true)
    vm.rules.push({ resourceid: 'x' }); expect(Widget.computed.isLastRule.call(vm)).toBe(false)
  })
  it('reports desired/runtime differences instead of a false consistent state', () => {
    const vm = { shares: [{ id: 'x', name: 'share' }], rules: [{ resourceid: 'x', principal: '10.1.1.9' }], runtime: { x: { networkAccessMode: 'ALLOW_LIST', allowedSources: ['10.1.1.10'] } }, $t: value => value }
    expect(Widget.computed.summaries.call(vm)[0].status).toBe('DRIFT')
    vm.runtime = {}; expect(Widget.computed.summaries.call(vm)[0].status).toBe('UNOBSERVED')
  })
})
