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
import { mount } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import ResourceTags from '@/components/view/ResourceTags.vue'
import InfoCard from '@/components/view/InfoCard.vue'
import { getAPI, postAPI } from '@/api'
import { clearResourceTagOperations } from '@/utils/resourceTags'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); await nextTick() }
const deferred = () => { let finish; const promise = new Promise(resolve => { finish = resolve }); return { promise, resolve: finish } }
const id = '11111111-1111-4111-8111-111111111111'
const otherId = '22222222-2222-4222-8222-222222222222'
const resource = { id, account: 'owner', domainid: 'domain' }
const tag = (key = 'existing', value = 'value', resourceid = id, resourcetype = 'Snapshot') => ({ resourceid, resourcetype, key, value })
let wrapper, store, route, rows
function render (props = {}) {
  wrapper = mount(ResourceTags, {
    props: { resource: { ...resource }, resourceType: 'Snapshot', canEdit: true, ...props },
    global: {
      mocks: { $store: store, $route: route, $t: key => key },
      stubs: {
        ASpin: { template: '<div><slot /></div>' },
        AAlert: { props: ['message', 'description'], template: '<div role="alert">{{ message }}{{ description }}<slot name="message"/><slot name="description"/></div>' },
        ADivider: true,
        AButton: { template: '<button v-bind="$attrs"><slot/></button>' },
        AInput: { props: ['value'], emits: ['update:value'], methods: { focus () { this.$el.focus() } }, template: '<input :value="value" v-bind="$attrs" @input="$emit(\'update:value\', $event.target.value)" />' },
        ATag: {
          props: ['closable'],
          emits: ['close'],
          data: () => ({ shown: true }),
          template: '<span v-if="shown" data-tag><slot/><button v-if="closable" data-delete @click="close">x</button></span>',
          methods: { close (event) { this.$emit('close', event); if (!event.defaultPrevented) this.shown = false } }
        }
      }
    }
  })
  return wrapper
}
beforeEach(() => {
  wrapper = null; jest.useFakeTimers(); clearResourceTagOperations(sessionStorage); sessionStorage.clear()
  rows = [tag()]
  store = reactive({ state: { user: { token: 'session' } }, getters: { userInfo: { id: 'admin', roletype: 'Admin', account: 'admin', domainid: 'domain' }, project: {}, apis: { listTags: {}, createTags: {}, deleteTags: {} } } })
  route = reactive({ fullPath: '/snapshot/' + id, params: { id }, meta: { resourceType: 'Snapshot', name: 'snapshot' } })
  getAPI.mockReset().mockImplementation(async command => command === 'listTags' ? { listtagsresponse: { count: rows.length, tag: rows } } : { queryasyncjobresultresponse: { jobstatus: 1 } })
  postAPI.mockReset().mockResolvedValue({ createtagsresponse: { jobid: 'job' } })
})
afterEach(() => { if (wrapper) wrapper.unmount(); clearResourceTagOperations(sessionStorage); jest.useRealTimers() })

test('InfoCard uses the same tag component for snapshot and VM, without its former eager writes', () => {
  expect(InfoCard.components.ResourceTags).toBe(ResourceTags)
  expect(InfoCard.methods.handleInputConfirm).toBeUndefined()
  expect(InfoCard.computed.tagsSupportingResourceTypes()).toEqual(expect.arrayContaining(['Snapshot', 'UserVm']))
})

test('creation waits for terminal job and then refreshes automatically; blur and duplicate enter do not submit', async () => {
  render(); await flush(); const gate = deferred()
  getAPI.mockImplementation(command => command === 'listTags' ? Promise.resolve({ listtagsresponse: { count: rows.length, tag: rows } }) : gate.promise)
  await wrapper.vm.showInput(); await flush()
  await wrapper.find('input[aria-label="label.key"]').setValue('new'); await wrapper.find('input[aria-label="label.key"]').trigger('blur')
  expect(postAPI).not.toHaveBeenCalled()
  await wrapper.find('input[aria-label="label.value"]').setValue('new-value')
  wrapper.vm.submitTag(); wrapper.vm.submitTag(); await flush()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(wrapper.vm.operationState.status).toBe('pending')
  expect(wrapper.findAll('[data-tag]')).toHaveLength(1)
  const reads = getAPI.mock.calls.filter(c => c[0] === 'listTags').length
  rows = [...rows, tag('new', 'new-value')]; gate.resolve({ queryasyncjobresultresponse: { jobstatus: 1 } }); await flush()
  expect(wrapper.findAll('[data-tag]')).toHaveLength(2); expect(wrapper.vm.inputVisible).toBe(false)
  expect(getAPI.mock.calls.filter(c => c[0] === 'listTags')).toHaveLength(reads + 1)
})

test('delete keeps its visible tag until completion and never optimistically closes it', async () => {
  render(); await flush(); const gate = deferred()
  postAPI.mockResolvedValue({ deletetagsresponse: { jobid: 'delete-job' } })
  getAPI.mockImplementation(command => command === 'listTags' ? Promise.resolve({ listtagsresponse: { count: rows.length, tag: rows } }) : gate.promise)
  await wrapper.find('[data-delete]').trigger('click'); await flush()
  expect(wrapper.findAll('[data-tag]')).toHaveLength(1); expect(wrapper.vm.operationState.status).toBe('pending')
  rows = []; gate.resolve({ queryasyncjobresultresponse: { jobstatus: 1 } }); await flush()
  expect(wrapper.findAll('[data-tag]')).toHaveLength(0)
})

test('failed job shows the real cause and preserves the entered tag until an explicit retry', async () => {
  render(); await flush(); wrapper.vm.inputVisible = true; wrapper.vm.inputKey = 'new'; wrapper.vm.inputValue = 'value'
  getAPI.mockImplementation(async command => command === 'listTags' ? { listtagsresponse: { count: rows.length, tag: rows } } : { queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'Duplicate tag' } } })
  await wrapper.vm.submitTag(); await flush()
  expect(wrapper.text()).toContain('Duplicate tag'); expect(wrapper.vm.inputKey).toBe('new'); expect(postAPI).toHaveBeenCalledTimes(1)
  await wrapper.vm.submitTag(); expect(postAPI).toHaveBeenCalledTimes(1)
  getAPI.mockImplementation(async command => command === 'listTags' ? { listtagsresponse: { count: rows.length, tag: rows } } : { queryasyncjobresultresponse: { jobstatus: 1 } })
  await wrapper.vm.retryMutation(); await flush(); expect(postAPI).toHaveBeenCalledTimes(2)
})

test('refresh failure preserves tags and job success; retry is read-only', async () => {
  render(); await flush(); postAPI.mockImplementation(async () => {
    getAPI.mockImplementation(async command => { if (command === 'listTags') throw Object.assign(new Error('read failed'), { response: { status: 503 } }); return { queryasyncjobresultresponse: { jobstatus: 1 } } })
    return { createtagsresponse: { jobid: 'job' } }
  })
  wrapper.vm.inputKey = 'new'; wrapper.vm.inputValue = 'value'; await wrapper.vm.submitTag(); await flush()
  expect(wrapper.vm.operationState.status).toBe('succeeded'); expect(wrapper.findAll('[data-tag]')).toHaveLength(1); expect(wrapper.vm.canChange).toBe(false)
  getAPI.mockResolvedValue({ listtagsresponse: { count: 2, tag: [tag(), tag('new')] } }); await wrapper.vm.refreshTags(); await flush()
  expect(wrapper.findAll('[data-tag]')).toHaveLength(2); expect(postAPI).toHaveBeenCalledTimes(1)
})

test('permission read failure clears prior data and editing, then explicit refresh recovers', async () => {
  render(); await flush(); getAPI.mockRejectedValueOnce({ response: { status: 403 } }); await wrapper.vm.refreshTags(); await flush()
  expect(wrapper.findAll('[data-tag]')).toHaveLength(0); expect(wrapper.vm.canChange).toBe(false)
  await wrapper.vm.refreshTags(); await flush(); expect(wrapper.findAll('[data-tag]')).toHaveLength(1)
})

test('same-scope reload preserves tags while waiting; late read from an old target is ignored', async () => {
  render(); await flush(); const late = deferred(); getAPI.mockReturnValueOnce(late.promise); const old = wrapper.vm.refreshTags(); await flush()
  expect(wrapper.findAll('[data-tag]')).toHaveLength(1)
  rows = [tag('other', 'different', otherId)]; route.fullPath = '/snapshot/' + otherId; route.params.id = otherId
  expect(wrapper.vm.visibleTags).toEqual([])
  await wrapper.setProps({ resource: { ...resource, id: otherId, account: 'other' } }); await flush()
  late.resolve({ listtagsresponse: { count: 1, tag: [tag('secret-old')] } }); await old; await flush()
  expect(wrapper.text()).toContain('other = different'); expect(wrapper.text()).not.toContain('secret-old')
})

test('project, API permission and login changes cannot display old tags or submit with old scope', async () => {
  render(); await flush(); store.getters.project = { id: 'project' }
  expect(wrapper.vm.visibleTags).toEqual([]); expect(wrapper.vm.canChange).toBe(false)
  wrapper.vm.submitTag(); expect(postAPI).not.toHaveBeenCalled()
  store.getters.project = {}; await flush(); expect(wrapper.vm.visibleTags).toHaveLength(1)
  delete store.getters.apis.listTags; expect(wrapper.vm.visibleTags).toEqual([])
  getAPI.mockRejectedValue({ response: { status: 403 } }); store.state.user.token = 'other-session'; store.getters.userInfo = { id: 'other' }; store.getters.apis.listTags = {}; await flush()
  expect(wrapper.vm.visibleTags).toEqual([])
})

test('empty inputs are rejected and read-only resources expose no write controls', async () => {
  render(); await flush(); wrapper.vm.inputKey = 'key'; wrapper.vm.inputValue = ''; wrapper.vm.submitTag()
  expect(wrapper.vm.fieldError).toBe('message.tags.fields.required'); expect(postAPI).not.toHaveBeenCalled()
  await wrapper.setProps({ canEdit: false }); await flush(); expect(wrapper.find('[data-delete]').exists()).toBe(false)
  wrapper.vm.deleteTag(tag()); expect(postAPI).not.toHaveBeenCalled()
})

test('VM tags use UserVm and its exact owner project; failed preflight never posts', async () => {
  route.meta = { name: 'vm', resourceType: 'UserVm' }; route.fullPath = '/vm/' + id; store.getters.project = { id: 'project' }
  rows = [tag('vm', 'value', id, 'UserVm')]; render({ resourceType: 'UserVm', resource: { ...resource, projectid: 'project' } }); await flush()
  expect(getAPI.mock.calls[0][1]).toMatchObject({ resourcetype: 'UserVm', resourceid: id, projectid: 'project' })
  getAPI.mockRejectedValueOnce({ response: { status: 503 } }); wrapper.vm.inputKey = 'key'; wrapper.vm.inputValue = 'value'; await wrapper.vm.submitTag(); await flush()
  expect(postAPI).not.toHaveBeenCalled(); expect(wrapper.vm.operationState.status).toBe('failed')
})
