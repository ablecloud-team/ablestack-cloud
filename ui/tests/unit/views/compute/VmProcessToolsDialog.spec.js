// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { shallowMount } from '@vue/test-utils'
import { getAPI, postAPI } from '@/api'
import Dialog from '@/views/compute/VmProcessToolsDialog.vue'
import { requiredRpcs } from '@/views/compute/vmProcessDisplay'
import { clearIsoOperations } from '@/utils/vmIsoActions'
import { createI18n } from 'vue-i18n'
import ko from '@public/locales/ko_KR.json'
import en from '@public/locales/en.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const response = (cmd, value) => ({ [cmd.toLowerCase() + 'response']: value })
const flush = async () => { for (let i = 0; i < 50; i++) await Promise.resolve() }
const id = 'vm-1173'
const catalog = { status: 'MATCHED', isoId: 'tools', zoneId: 'zone', name: 'Tools', isoFamily: 'ubuntu', arch: 'x86_64' }
let vm, cap, selected, media, snapshot, poll
const wrappers = []
function mount (props = {}, apis = { attachIso: {} }) {
  const wrapper = shallowMount(Dialog, {
    props: { visible: true, resource: { id, name: 'Test VM' }, catalog: selected, ...props },
    global: { mocks: { $t: key => key, $route: { path: '/vm/' + id }, $pollJob: (...args) => poll(...args), $store: { getters: { apis, userInfo: { id: 'admin' }, project: {} }, state: { user: { token: 'token' } } } } }
  })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  jest.clearAllMocks(); clearIsoOperations()
  vm = { id, state: 'Running', zoneid: 'zone', hypervisor: 'KVM', isomaxcount: 2, isos: [{ id: 'original', deviceseq: 3 }] }
  selected = { ...catalog }; media = { id: 'tools', name: 'Tools', isready: true, arch: 'x86_64' }
  cap = { kind: 'capability', schemaVersion: '1.0', readiness: 'READY', authority: { vmUuid: id }, allowedActions: ['process.list'], rpcs: Object.fromEntries(requiredRpcs.map(rpc => [rpc, 'ENABLED'])) }
  snapshot = { schemaVersion: '1.0', kind: 'snapshot', snapshotId: 'new', bootId: 'boot', status: 'OK', authority: { vmUuid: id }, processes: [], observedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 10000).toISOString() }
  poll = jest.fn(async () => { vm.isos.push({ id: 'tools', deviceseq: 4 }); return { jobstatus: 1 } })
  getAPI.mockImplementation(cmd => Promise.resolve(response(cmd, cmd === 'getVirtualMachineProcessCapabilities'
    ? { processcapability: { processstate: cap, toolsiso: selected } }
    : cmd === 'listVirtualMachines' ? { virtualmachine: [vm] }
      : cmd === 'listIsos' ? { iso: media ? [media] : [] }
        : { jobstatus: 1, jobresult: { processsnapshot: { processstate: snapshot } } })))
  postAPI.mockImplementation(cmd => Promise.resolve(response(cmd, { jobid: cmd + '-job' })))
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); jest.useRealTimers() })

test('reuses attachIso jobs, preserves the existing ISO and waits for guest installation', async () => {
  const wrapper = mount(); await wrapper.vm.open(); await wrapper.vm.attach()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI).toHaveBeenCalledWith('attachIso', { virtualmachineid: id, id: 'tools' })
  expect(vm.isos.map(iso => iso.id)).toEqual(['original', 'tools'])
  expect(wrapper.vm.stage).toBe('INSTALL_PENDING')
  expect(wrapper.emitted('verified')).toBeUndefined()
})

test.each([
  ['missing', () => { media = null }, 'message.vmprocess.tools.missing'],
  ['not ready', () => { media.isready = false }, 'message.vmprocess.tools.notready'],
  ['wrong architecture', () => { media.arch = 'aarch64' }, 'message.vmprocess.tools.arch'],
  ['wrong zone', () => { vm.zoneid = 'other' }, 'message.vmprocess.tools.zone'],
  ['full slot', () => { vm.isos.push({ id: 'other', deviceseq: 4 }) }, 'message.vmiso.full'],
  ['observed OS mismatch', () => { selected = { status: 'OS_MISMATCH' } }, 'message.vmprocess.tools.catalog.os_mismatch']
])('blocks %s before any attach mutation', async (_, change, error) => {
  const wrapper = mount(); change(); await wrapper.vm.open()
  expect(wrapper.vm.stage).toBe('FAILED'); expect(wrapper.vm.error).toBe(error)
  expect(postAPI).not.toHaveBeenCalled()
})

test('denies missing API permission before media access', async () => {
  const wrapper = mount({}, {}); await wrapper.vm.open(); await wrapper.vm.attach()
  expect(wrapper.vm.error).toBe('message.vmprocess.tools.permission')
  expect(getAPI).not.toHaveBeenCalled(); expect(postAPI).not.toHaveBeenCalled()
})

test('requires actual OS confirmation for a registered Windows installation hint', async () => {
  selected = { ...catalog, isoFamily: 'windows', selectionSource: 'REGISTERED_OS', registeredOsName: 'Windows Server 2022' }
  const wrapper = mount(); await wrapper.vm.open(); await wrapper.vm.attach()
  expect(postAPI).not.toHaveBeenCalled()
  expect(wrapper.vm.installCommand).toBe('message.vmprocess.tools.command.windows')
  wrapper.vm.osConfirmed = true; await wrapper.vm.attach()
  expect(wrapper.vm.stage).toBe('INSTALL_PENDING')
})

test.each([['ko', ko], ['en', en]])('%s installation commands survive the real locale formatter', (locale, messages) => {
  const i18n = createI18n({ locale, messages: { [locale]: messages } })
  expect(i18n.global.t('message.vmprocess.tools.command.windows')).toContain('X:\\install.bat')
  expect(i18n.global.t('message.vmprocess.tools.command.windows')).not.toContain('-Mode Apply')
  expect(i18n.global.t('message.vmprocess.tools.command.linux')).toContain('bash /mnt/ablestack-tools/install-linux.sh')
  expect(i18n.global.t('message.vmprocess.tools.command.linux')).not.toContain('--mode')
})

test('shows connecting while the ISO job is pending', async () => {
  let finish
  poll = jest.fn(() => new Promise(resolve => { finish = resolve }))
  const wrapper = mount(); await wrapper.vm.open(); const pending = wrapper.vm.attach(); await flush()
  expect(wrapper.vm.stage).toBe('CONNECTING')
  vm.isos.push({ id: 'tools' }); finish({ jobstatus: 1 }); await pending
  expect(wrapper.vm.stage).toBe('INSTALL_PENDING')
})

test('rechecks an unknown accepted attach job without a second attach', async () => {
  poll = jest.fn().mockResolvedValueOnce({ trackingStatus: 'unknown' }).mockImplementation(async () => { vm.isos.push({ id: 'tools' }); return { jobstatus: 1 } })
  const wrapper = mount(); await wrapper.vm.open(); await wrapper.vm.attach()
  expect(wrapper.vm.stage).toBe('FAILED')
  await wrapper.vm.checkOperation()
  expect(wrapper.vm.stage).toBe('INSTALL_PENDING')
  expect(postAPI).toHaveBeenCalledTimes(1)
})

test('an earlier successful attach is not evidence that the ISO is still connected', async () => {
  const wrapper = mount(); await wrapper.vm.open(); await wrapper.vm.attach()
  vm.isos = [{ id: 'original' }]; await wrapper.vm.open()
  expect(wrapper.vm.stage).toBe('SELECT'); expect(wrapper.vm.canVerify).toBe(false)
})

test('RPCs alone cannot mark Tools ready when the execution probe failed', async () => {
  cap.readiness = 'TOOLS_REQUIRED'; const wrapper = mount(); await wrapper.vm.verify()
  expect(wrapper.vm.error).toBe('message.vmprocess.tools.adapter')
  expect(postAPI).not.toHaveBeenCalled(); expect(wrapper.emitted('verified')).toBeUndefined()
})

test.each(['failure', 'expired', 'wrong VM', 'missing boot', 'invalid status'])('rejects %s process evidence', async problem => {
  if (problem === 'failure') snapshot.kind = 'failure'
  if (problem === 'expired') snapshot.expiresAt = new Date(Date.now() - 1).toISOString()
  if (problem === 'wrong VM') snapshot.authority.vmUuid = 'other'
  if (problem === 'missing boot') delete snapshot.bootId
  if (problem === 'invalid status') snapshot.status = 'FAILED'
  const wrapper = mount(); await wrapper.vm.verify()
  expect(wrapper.vm.stage).toBe('FAILED'); expect(wrapper.emitted('verified')).toBeUndefined()
})

test('requires a READY capability and a fresh valid process snapshot', async () => {
  const wrapper = mount(); await wrapper.vm.verify()
  expect(wrapper.vm.stage).toBe('READY'); expect(wrapper.emitted('verified')).toHaveLength(1)
})

test('removes the original QGA-unreachable installation hint after readiness is verified', async () => {
  selected = { ...catalog, selectionSource: 'REGISTERED_OS', registeredOsName: 'Ubuntu 24.04' }
  const wrapper = mount(); await wrapper.vm.verify()
  expect(wrapper.vm.stage).toBe('READY')
  expect(wrapper.html()).not.toContain('message.vmprocess.tools.declared')
})

test('discards late capabilities after changing VM or closing the dialog', async () => {
  for (const props of [{ resource: { id: 'other' } }, { visible: false }]) {
    let finish
    getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(); const pending = wrapper.vm.verify()
    await wrapper.setProps(props)
    finish(response('getVirtualMachineProcessCapabilities', { processcapability: { processstate: cap } })); await pending
    expect(postAPI).not.toHaveBeenCalled(); expect(wrapper.emitted('verified')).toBeUndefined()
    expect(wrapper.vm.busy).toBe(false)
  }
})

test('retains a timed-out read job for explicit recheck without another submission', async () => {
  jest.useFakeTimers()
  getAPI.mockImplementation(cmd => Promise.resolve(response(cmd, cmd === 'getVirtualMachineProcessCapabilities'
    ? { processcapability: { processstate: cap } } : { jobstatus: 0 })))
  const wrapper = mount(); const pending = wrapper.vm.verify(); await flush()
  for (let i = 0; i < 60; i++) { jest.advanceTimersByTime(400); await flush() }
  await pending; expect(wrapper.vm.error).toBe('message.vmprocess.tools.timeout')
  getAPI.mockImplementation(cmd => Promise.resolve(response(cmd, cmd === 'getVirtualMachineProcessCapabilities'
    ? { processcapability: { processstate: cap } } : { jobstatus: 1, jobresult: { processsnapshot: { processstate: { ...snapshot, observedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 10000).toISOString() } } } })))
  await wrapper.vm.verify()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(wrapper.vm.stage).toBe('READY')
})
