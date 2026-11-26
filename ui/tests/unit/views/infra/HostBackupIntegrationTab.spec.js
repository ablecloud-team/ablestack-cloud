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

import { mount } from '@vue/test-utils'
import HostBackupIntegrationTab from '@/views/infra/HostBackupIntegrationTab.vue'
import ko from '../../../../public/locales/ko_KR.json'

const opened = jest.fn()
const configure = jest.fn()
const NetBackupStub = {
  props: ['resource'],
  data () { return { policy: '' } },
  mounted () { opened(this.resource.id) },
  methods: { apply () { configure(this.resource.id, this.policy) } },
  template: '<section data-provider="netbackup"><input v-model="policy" /><button @click="apply">Apply NetBackup</button></section>'
}
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve() }
let wrapper
async function open (resource = {}, apis = { getNetBackupHostConfiguration: {} }) {
  wrapper = mount(HostBackupIntegrationTab, {
    props: { resource: { id: 'host-1', zoneid: 'zone-1', clusterid: 'cluster-1', hypervisor: 'KVM', type: 'Routing', backupintegrationproviders: ['ablestack-netbackup', 'ablestack-veeam'], ...resource } },
    global: {
      stubs: { NetBackupHostConfiguration: NetBackupStub },
      mocks: { $t: key => ko[key] || key, $store: { getters: { apis } } }
    }
  })
  await flush()
  return wrapper
}
async function select (label) {
  await wrapper.findAll('[role="tab"]').find(tab => tab.text() === label).trigger('click')
  await flush()
}
beforeEach(() => jest.clearAllMocks())
afterEach(() => wrapper?.unmount())

test('the same Host offers independent NetBackup and Veeam tabs with NetBackup selected initially', async () => {
  const w = await open()
  expect(w.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['NetBackup', 'Veeam'])
  expect(w.find('[data-provider="netbackup"]').isVisible()).toBe(true)
  expect(opened).toHaveBeenCalledWith('host-1')
  expect(configure).not.toHaveBeenCalled()
})

test('selecting Veeam shows its support status without invoking NetBackup configuration', async () => {
  const w = await open()
  await select('Veeam')
  expect(w.text()).toContain(ko['message.backup.integration.veeam.unavailable'])
  expect(w.find('[data-provider="netbackup"]').isVisible()).toBe(false)
  expect(opened).toHaveBeenCalledTimes(1)
  expect(configure).not.toHaveBeenCalled()
})

test('returning from Veeam preserves unsaved NetBackup input for the same Host', async () => {
  const w = await open()
  await w.find('input').setValue('host-one-policy')
  await select('Veeam')
  await select('NetBackup')
  expect(w.find('input').element.value).toBe('host-one-policy')
  await w.find('[data-provider="netbackup"] button').trigger('click')
  expect(configure).toHaveBeenCalledWith('host-1', 'host-one-policy')
  expect(opened).toHaveBeenCalledTimes(1)
})

test('changing Hosts discards the previous Host form and binds actions to the new Host', async () => {
  const w = await open()
  await w.find('input').setValue('host-one-policy')
  await w.setProps({ resource: { ...w.props('resource'), id: 'host-2', zoneid: 'zone-2', clusterid: 'cluster-2' } })
  await flush()
  expect(w.find('input').element.value).toBe('')
  expect(opened).toHaveBeenLastCalledWith('host-2')
  await w.find('[data-provider="netbackup"] button').trigger('click')
  expect(configure).toHaveBeenCalledWith('host-2', '')
})

test('a Veeam-only Zone opens Veeam directly without loading NetBackup', async () => {
  const w = await open({ backupintegrationproviders: ['ablestack-veeam'] }, {})
  expect(w.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['Veeam'])
  expect(w.vm.provider).toBe('veeam')
  expect(opened).not.toHaveBeenCalled()
})

test('a NetBackup-only Zone offers no Veeam pane', async () => {
  const w = await open({ backupintegrationproviders: ['ablestack-netbackup'] })
  expect(w.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['NetBackup'])
})

test.each([[], undefined, ['veeam'], ['other-ablestack-netbackup']])('disabled, unknown or unrelated provider observations %p never mount integration forms', async providers => {
  const w = await open({ backupintegrationproviders: providers })
  expect(w.findAll('[role="tab"]')).toHaveLength(0)
  expect(opened).not.toHaveBeenCalled()
})

test('removing the active provider selects the remaining provider', async () => {
  const w = await open()
  await w.setProps({ resource: { ...w.props('resource'), backupintegrationproviders: ['ablestack-veeam'] } })
  await flush()
  expect(w.vm.provider).toBe('veeam')
  expect(w.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['Veeam'])
})
