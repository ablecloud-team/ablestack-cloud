// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.

import { shallowMount, flushPromises } from '@vue/test-utils'
import Targets from '@/views/storage/StorageSnapshotDialogTargets.vue'
import Template from '@/views/storage/CreateTemplate.vue'
import TakeSnapshot from '@/views/storage/TakeSnapshot.vue'
import Zones from '@/views/storage/SnapshotZones.vue'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/vue-app', () => ({ vueProps: {} }))
const row = { id: 's', name: 'fixed snapshot', account: 'owner', domain: 'example', state: 'BackedUp', volumeid: 'v', volumename: 'source volume', virtualsize: 1073741824, intervaltype: 'MANUAL', created: '2026-10-25T00:00:00Z' }
function targets (zones) { return zones.map(zoneId => ({ id: 's', zoneId, record: { ...row, zonename: 'Zone ' + zoneId } })) }
const mocks = { $t: key => ({ 'state.backedup': '백업 됨', 'label.snapshot.list.interval.manual': '수동', 'message.snapshot.action.all.zones': '모든 Zone' }[key] || key), $toLocaleDate: value => '한글 시간 ' + value }
test('single deletion summary preserves the fixed name, source, translated state and dynamic capacity', () => {
  const wrapper = shallowMount(Targets, { props: { targets: targets(['all']) }, global: { mocks, stubs: { ATable: { name: 'ATable', props: ['pagination', 'scroll'], template: '<table />' } } } })
  expect(wrapper.vm.rows[0]).toEqual(expect.objectContaining({ name: 'fixed snapshot', volume: 'source volume', account: 'owner · example', state: '백업 됨', virtualsize: '1.00 GiB', intervaltype: '수동', scope: '모든 Zone' }))
  expect(wrapper.vm.rows[0].created).toContain('한글 시간'); wrapper.unmount()
})
test('same snapshot in two Zone scopes retains distinct table keys and never changes target count', () => {
  const fixed = targets(['a', 'b']); const contexts = fixed.map(target => ({ target, record: row, source: {}, copies: [{ zoneid: target.zoneId, zonename: 'Zone ' + target.zoneId }] }))
  const wrapper = shallowMount(Targets, { props: { targets: fixed, contexts }, global: { mocks, stubs: { ATable: { name: 'ATable', props: ['pagination', 'scroll'], template: '<table />' } } } })
  expect(new Set(wrapper.vm.rows.map(row => row.key)).size).toBe(2)
  expect(wrapper.vm.rows.map(row => row.scope)).toEqual(['Zone a', 'Zone b'])
  expect(wrapper.findComponent({ name: 'ATable' }).props('pagination')).toBe(false)
  expect(wrapper.findComponent({ name: 'ATable' }).props('scroll')).toEqual({ x: 1500, y: 300 }); wrapper.unmount()
})
test('template lookup failure retains inputs and the existing Zone until a successful retry', async () => {
  const vm = { ...Template.methods, form: { name: 'my name', zoneid: 'z' }, resource: { id: 's', intervaltype: 'MANUAL' }, osTypes: { opts: [] }, architectureTypes: {}, querySequence: 0, queryLoading: false, loading: false, lookupError: '', dialogCurrent: () => true, $fetchCpuArchitectureTypes: () => [] }
  getAPI.mockImplementation(command => command === 'listOsTypes' ? Promise.reject(new Error('503')) : Promise.resolve(command === 'listSnapshots' ? { listsnapshotsresponse: { snapshot: [{ zoneid: 'z' }] } } : { listzonesresponse: { zone: [{ id: 'z' }] } }))
  await vm.fetchData(); expect(vm.lookupError).toBeTruthy(); expect(vm.form).toEqual({ name: 'my name', zoneid: 'z' })
  getAPI.mockImplementation(command => Promise.resolve(command === 'listOsTypes' ? { listostypesresponse: { ostype: [{ id: 'os' }] } } : command === 'listSnapshots' ? { listsnapshotsresponse: { snapshot: [{ zoneid: 'z' }] } } : { listzonesresponse: { zone: [{ id: 'z' }] } }))
  await vm.fetchData(); expect(vm.lookupError).toBe(''); expect(vm.queryLoading).toBe(false); expect(vm.form).toEqual({ name: 'my name', zoneid: 'z' })
})
test('an old template option response cannot overwrite the new resource scope', async () => {
  const vm = { dialogCurrent: () => false, form: { zoneid: 'new' }, zones: [{ id: 'new' }] }
  getAPI.mockResolvedValue({ listzonesresponse: { zone: [{ id: 'old' }] } }); await Template.methods.fetchZones.call(vm, ['old'])
  expect(vm.form.zoneid).toBe('new'); expect(vm.zones).toEqual([{ id: 'new' }])
})
test('snapshot tag entry ignores an empty key and keeps explicit removal in the real model', () => {
  const vm = { actionLoading: false, inputKey: ' ', inputValue: 'value', tags: [], inputVisible: true, focusTagAdd: jest.fn() }
  TakeSnapshot.methods.handleInputConfirm.call(vm); expect(vm.tags).toEqual([])
  vm.inputKey = 'key'; TakeSnapshot.methods.handleInputConfirm.call(vm); expect(vm.tags).toEqual([{ key: 'key', value: 'value' }]); expect(vm.inputVisible).toBe(false)
})

test('two template clicks that validate together acquire only one submission', async () => {
  let release
  const vm = { loading: false, queryLoading: false, lookupError: '', snapshotSafetyBlocked: false, dialogCurrent: () => true, formRef: { value: { validate: () => Promise.resolve() } }, form: { name: 'new' }, handleRemoveFields: value => value, resource: { id: 'snapshot', intervaltype: 'MANUAL' }, snapshotSafetyTarget: {}, submitSnapshotSafely: jest.fn(() => new Promise(resolve => { release = resolve })), closeModal: jest.fn(), $notifyError: jest.fn() }
  Template.methods.handleSubmit.call(vm); Template.methods.handleSubmit.call(vm); await flushPromises()
  expect(vm.submitSnapshotSafely).toHaveBeenCalledTimes(1); expect(vm.loading).toBe(true)
  release({ jobid: 'job' }); await flushPromises(); expect(vm.loading).toBe(false); expect(vm.closeModal).toHaveBeenCalledTimes(1)
})
test('a previous template lookup generation cannot replace newer choices in the same scope', async () => {
  let release; getAPI.mockReturnValue(new Promise(resolve => { release = resolve }))
  const vm = { dialogCurrent: () => true, querySequence: 1, form: { zoneid: 'new' }, zones: [{ id: 'new' }] }
  const pending = Template.methods.fetchZones.call(vm, ['old'], 1); vm.querySequence = 2; release({ listzonesresponse: { zone: [{ id: 'old' }] } }); await pending
  expect(vm.zones).toEqual([{ id: 'new' }]); expect(vm.form.zoneid).toBe('new')
})
test('closing a Zone dialog restores a live action but never focuses an older scope', () => {
  const trigger = document.createElement('button'); document.body.appendChild(trigger)
  const vm = { snapshotDialogTrigger: trigger, snapshotDialogScope: 'current', copySecurityScope: 'current', copyRecord: null, safetyDeleteAction: null, $nextTick: callback => callback(), $refs: {} }
  Zones.methods.restoreDialogFocus.call(vm); expect(document.activeElement).toBe(trigger)
  trigger.blur(); vm.copySecurityScope = 'new'; Zones.methods.restoreDialogFocus.call(vm); expect(document.activeElement).not.toBe(trigger); trigger.remove()
})

test('tag editor returns focus to its own add button only in the original dialog scope', () => {
  const button = document.createElement('button'); document.body.appendChild(button)
  const vm = { $nextTick: callback => callback(), dialogCurrent: () => true, $refs: { tagAdd: { $el: button } } }
  TakeSnapshot.methods.focusTagAdd.call(vm); expect(document.activeElement).toBe(button)
  button.blur(); vm.dialogCurrent = () => false; TakeSnapshot.methods.focusTagAdd.call(vm); expect(document.activeElement).not.toBe(button); button.remove()
})

test.each([
  [{ sourceavailable: true, virtualmachinename: 'attached VM', virtualmachinestate: 'Stopped' }, 'attached VM · state.stopped'],
  [{ sourceavailable: true }, 'label.none'],
  [{ sourceavailable: false }, 'label.snapshot.list.unknown']
])('deletion target preserves VM identity and distinguishes absent from unavailable source %j', (source, expected) => {
  const fixed = targets(['all'])
  const wrapper = shallowMount(Targets, { props: { targets: fixed, contexts: [{ target: fixed[0], record: row, source, copies: [] }] }, global: { mocks } })
  expect(wrapper.vm.rows[0].virtualmachine).toBe(expected); wrapper.unmount()
})
