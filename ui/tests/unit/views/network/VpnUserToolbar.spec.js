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

import AutogenView from '@/views/AutogenView'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))

const create = { api: 'addVpnUser', listView: true }
const remove = { api: 'removeVpnUser', groupAction: true, toolbarLabel: 'label.vpn.user.delete.selected' }

test('offers both creation and explicit deletion for the selected VPN users', () => {
  const ctx = {
    isVpnUser: true,
    $route: { name: 'vpnuser', meta: { name: 'vpnuser' } },
    $store: { getters: { apis: { addVpnUser: {}, removeVpnUser: {} } } },
    actions: [create, remove],
    storageActionReady: true,
    selectedRowKeys: []
  }
  const actions = () => AutogenView.computed.visibleListActions.call(ctx)
  expect(actions()).toEqual([create])
  ctx.selectedRowKeys = ['selected']
  expect(actions()).toEqual([create, remove])
  ctx.storageActionReady = false
  expect(actions()[1].disabled()).toBe(true)
  delete ctx.$store.getters.apis.removeVpnUser
  expect(actions()).toEqual([create])
})

test('returns focus to the VPN list region if the selection button was removed', async () => {
  const root = document.createElement('div')
  root.innerHTML = '<div class="list-view-table-wrapper" tabindex="0"></div>'
  document.body.appendChild(root)
  try {
    const ctx = {
      currentAction: { vpnUserDeleteTargets: [{}] },
      actionTrigger: document.createElement('button'),
      actionOriginPath: '/vpnuser',
      $route: { path: '/vpnuser', meta: { name: 'vpnuser' } },
      $el: root,
      $nextTick: callback => Promise.resolve().then(callback)
    }
    AutogenView.methods.closeAction.call(ctx)
    await Promise.resolve()
    expect(document.activeElement).toBe(root.firstChild)
  } finally { root.remove() }
})
