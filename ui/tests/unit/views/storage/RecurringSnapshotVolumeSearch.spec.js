// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import Recurring from '@/views/storage/RecurringSnapshotVolume.vue'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

let wrapper
let store
const row = (id, extra = {}) => ({ id, name: 'QA-한글-Volume', size: 1073741824, state: 'Ready', hypervisor: 'KVM', vmstate: 'Stopped', account: 'owner', domain: 'ROOT', zonename: 'zone-a', vmname: 'i-2-100-VM', vmdisplayname: '표시 VM', ...extra })
const volumes = (rows, count = rows.length) => ({ listvolumesresponse: { count, volume: rows } })
const policies = rows => ({ listsnapshotpoliciesresponse: { count: rows.length, snapshotpolicy: rows } })
const scheduleStub = { name: 'FormSchedule', props: ['resource', 'disabled', 'dataSource'], template: '<section data-testid="schedule"><slot name="resource-selection" />{{ resource.id }}</section>' }
function render (resource) {
  wrapper = mount(Recurring, { ...(resource ? { props: { resource } } : {}), attachTo: document.body, global: { provide: { parentFetchData: jest.fn() }, mocks: { $t: key => key, $store: store }, stubs: { FormSchedule: scheduleStub } } })
  return wrapper
}
async function search (text) {
  await wrapper.find('.ant-select-selector').trigger('mousedown')
  await wrapper.find('input[role="combobox"]').setValue(text)
  await flushPromises()
  return Array.from(document.querySelectorAll('.ant-select-item-option-content')).map(node => node.textContent)
}
beforeEach(() => {
  getAPI.mockReset()
  store = reactive({ getters: { userInfo: { id: 'owner' }, project: {}, features: { kvmsnapshotenabled: true } }, state: { user: { token: 'session' } } })
  getAPI.mockImplementation(command => Promise.resolve(command === 'listVolumes' ? volumes([row('first'), row('second', { account: 'other', zonename: 'zone-b' })]) : policies([])))
})
afterEach(async () => { if (wrapper) wrapper.unmount(); wrapper = null; await flushPromises(); document.body.innerHTML = '' })

test.each(['QA-한글-Volume', 'qa-한글-volume', '한글', '  VoLuMe  ', '표시 VM', 'i-2-100-VM', 'zone-b', 'second'])('real AntD option filtering supports %s without vnode assumptions', async input => {
  render(); await flushPromises()
  const choices = await search(input)
  expect(choices.length).toBe(input === 'zone-b' || input === 'second' ? 1 : 2)
  expect(choices.join(' ')).toContain('QA-한글-Volume')
})

test('searching an absent name gives an empty result and clearing it recovers the choices', async () => {
  render(); await flushPromises()
  expect(await search('absent-volume')).toEqual([])
  await wrapper.find('input[role="combobox"]').setValue('')
  await flushPromises()
  expect(document.querySelectorAll('.ant-select-item-option')).toHaveLength(2)
})

test('a name on a later API page can be found and selected by its original UUID', async () => {
  const first = Array.from({ length: 100 }, (_, i) => row('old-' + i, { name: 'older-' + i }))
  getAPI.mockResolvedValueOnce(volumes(first, 101)).mockResolvedValueOnce(volumes([row('page-two')], 101)).mockResolvedValueOnce(policies([]))
  render(); await flushPromises()
  expect(await search('page-two')).toHaveLength(1)
  document.querySelector('.ant-select-item-option').click(); await flushPromises()
  expect(wrapper.findComponent(scheduleStub).props('resource').id).toBe('page-two')
  expect(getAPI.mock.calls[1]).toEqual(['listVolumes', { listall: true, page: 2, pagesize: 100 }, { preserveOnFailure: true }])
  expect(getAPI.mock.calls[2][1].volumeid).toBe('page-two')
})

test('duplicate names show their owner, VM, zone and UUID and keep that target above the schedule', async () => {
  render(); await flushPromises(); await search('QA-한글')
  const second = Array.from(document.querySelectorAll('.ant-select-item-option')).find(node => node.textContent.includes('second'))
  expect(second.textContent).toContain('label.account: other')
  expect(second.textContent).toContain('label.virtualmachine: 표시 VM')
  expect(second.textContent).toContain('label.zone: zone-b')
  second.click(); await flushPromises()
  expect(wrapper.findComponent(scheduleStub).props('resource').id).toBe('second')
  expect(wrapper.find('.selected-volume-description').text()).toContain('label.id: second')
  expect(wrapper.find('.selected-volume-description').text()).toContain('label.account: other')
})

test('project ownership and nullable or numeric metadata remain searchable text', async () => {
  getAPI.mockResolvedValueOnce(volumes([row('project-volume', { name: 123, size: null, project: '한글 프로젝트', vmname: null, vmdisplayname: 42, zonename: null, zoneid: 'zone-id' })]))
  render(); await flushPromises()
  const result = await search('42')
  expect(result[0]).toContain('123 (—)')
  expect(result[0]).toContain('label.project: 한글 프로젝트')
  expect(result[0]).toContain('label.zone: zone-id')
})

test.each([true, false])('the existing Ready/KVM eligibility filter is preserved when live snapshots are %s', async enabled => {
  store.getters.features.kvmsnapshotenabled = enabled
  getAPI.mockResolvedValueOnce(volumes([row('stopped'), row('running', { vmstate: 'Running' }), row('allocated', { state: 'Allocated' }), row('other-hypervisor', { hypervisor: 'VMware', vmstate: 'Running' })]))
  render(); await flushPromises()
  expect(wrapper.vm.volumes.map(item => item.id)).toEqual(enabled ? ['stopped', 'running', 'other-hypervisor'] : ['stopped', 'other-hypervisor'])
})

test('loading and failed volume reads disable selection, expose retry and recover without losing the login', async () => {
  let fail
  getAPI.mockReturnValueOnce(new Promise((resolve, reject) => { fail = reject }))
  render(); await flushPromises()
  expect(wrapper.find('.ant-select-disabled').exists()).toBe(true)
  fail(new Error('read failed')); await flushPromises()
  expect(wrapper.text()).toContain('read failed')
  expect(wrapper.find('.ant-select-disabled').exists()).toBe(true)
  getAPI.mockResolvedValueOnce(volumes([row('recovered')]))
  await wrapper.findAll('button').find(button => button.text() === 'label.retry').trigger('click'); await flushPromises()
  expect(await search('recovered')).toHaveLength(1)
  expect(store.state.user.token).toBe('session')
})

test.each(['project', 'login'])('late volume replies cannot restore another %s or an unmounted dialog', async scope => {
  let release
  getAPI.mockReturnValueOnce(new Promise(resolve => { release = resolve })).mockResolvedValueOnce(volumes([row('new-project')]))
  render(); await flushPromises()
  if (scope === 'project') store.getters.project = { id: 'new-project' }
  else { store.getters.userInfo = { id: 'new-user' }; store.state.user.token = 'new-session' }
  await flushPromises()
  release(volumes([row('old-private')])); await flushPromises()
  expect(wrapper.vm.volumes.map(item => item.id)).toEqual(['new-project'])
  expect(wrapper.vm.selectedVolumeId).toBe(null)
  let releaseUnmounted
  getAPI.mockReturnValueOnce(new Promise(resolve => { releaseUnmounted = resolve }))
  const vm = wrapper.vm; const pending = vm.fetchVolumes(); wrapper.unmount(); wrapper = null
  releaseUnmounted(volumes([row('unmounted-private')])); await pending
  expect(vm.volumes.map(item => item.id)).toEqual(['new-project'])
})

test('switching or clearing a selection invalidates pending policies and removes the previous target', async () => {
  render(); await flushPromises(); let release
  getAPI.mockReturnValueOnce(new Promise(resolve => { release = resolve })).mockResolvedValueOnce(policies([{ id: 'second-policy' }]))
  wrapper.vm.onVolumeChange('first'); wrapper.vm.onVolumeChange('second'); await flushPromises()
  release(policies([{ id: 'private-first-policy' }])); await flushPromises()
  expect(wrapper.findComponent(scheduleStub).props('resource').id).toBe('second')
  expect(wrapper.vm.dataSource).toEqual([{ id: 'second-policy' }])
  wrapper.vm.onVolumeChange('missing'); await flushPromises()
  expect(wrapper.findComponent(scheduleStub).props('disabled')).toBe(true)
  expect(wrapper.findComponent(scheduleStub).props('resource')).toEqual({})
  expect(wrapper.vm.selectedVolumeId).toBe(null)
  expect(wrapper.vm.dataSource).toEqual([])
})

test('volume detail creation still uses the supplied volume and never fetches global options', async () => {
  render(row('detail-volume')); await flushPromises()
  expect(getAPI.mock.calls.map(call => call[0])).toEqual(['listSnapshotPolicies'])
  expect(wrapper.findComponent(scheduleStub).props('resource').id).toBe('detail-volume')
  expect(wrapper.find('input[role="combobox"]').exists()).toBe(false)
})
