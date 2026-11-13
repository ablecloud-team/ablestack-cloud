// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { shallowMount, flushPromises } from '@vue/test-utils'
import { h, reactive } from 'vue'
import { createStore } from 'vuex'
import Add from '@/views/offering/AddVpcOffering'
import Clone from '@/views/offering/CloneVpcOffering'
import { getAPI, postAPI } from '@/api'
import { clearVpcOfferingOperations } from '@/utils/vpcOfferingOperationMixin'
import '../../utils/cryptoRandom'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/role', () => ({ isAdmin: () => true }))
const pass = { render () { return h('div', this.$slots.default?.()) } }
const form = { methods: { validate: () => Promise.resolve(), scrollToField: jest.fn() }, render () { return h('form', this.$slots.default?.()) } }
const list = { props: ['dataSource'], render () { return h('ul', this.dataSource.map(item => h('li', this.$slots.renderItem({ item })))) } }
const source = { id: 'source', name: 'Source', state: 'Enabled', conservemode: true, internetprotocol: 'IPv4', service: [{ name: 'SourceNat', provider: [{ name: 'VpcVirtualRouter' }] }] }
const deferred = () => { let done; const promise = new Promise(resolve => { done = resolve }); return { promise, resolve: done } }
let wrappers, resolveJob, store, route, receipt, command, target
beforeEach(() => {
  clearVpcOfferingOperations(); jest.clearAllMocks(); wrappers = []
  store = createStore({ state: { user: { token: 'token' }, identity: { id: 'user', domainid: 'domain' }, project: { id: 'project' } }, getters: { userInfo: state => state.identity, project: state => state.project } })
  route = reactive({ fullPath: '/vpcoffering/source' })
  const pending = deferred(); resolveJob = pending.resolve
  getAPI.mockImplementation(async (api, params) => {
    if (api === 'queryAsyncJobResult') return pending.promise
    return { [api.toLowerCase() + 'response']: { domain: [], zone: [], serviceoffering: [], configuration: [{ value: 'false' }], networkservice: source.service, vpcoffering: params?.id ? [{ id: 'new-uuid', name: target }] : [] } }
  })
  postAPI.mockImplementation(async (api, params) => { command = api; target = params.name; receipt = params.customjobid; return { [api.toLowerCase() + 'response']: { id: '1', jobid: receipt } } })
})
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); clearVpcOfferingOperations() })

async function open (component) {
  const wrapper = shallowMount(component, {
    props: component === Clone ? { resource: source } : {},
    global: {
      mocks: { $store: store, $route: route, $t: key => key, $getApiParams: () => new Proxy({}, { get: () => ({ description: 'description' }) }), $message: { success: jest.fn() }, $notifyError: jest.fn() },
      stubs: { AForm: form, AFormItem: pass, ASpin: pass, AList: list, AListItem: pass, ARow: pass, ACol: pass }
    }
  })
  wrappers.push(wrapper); await flushPromises()
  wrapper.vm.form.name = 'UI draft'; wrapper.vm.form.enable = false
  if (component === Add) wrapper.vm.selectedServiceProviderMap = { SourceNat: 'VpcVirtualRouter' }
  return wrapper
}
function finish (status = 1) {
  resolveJob({ queryasyncjobresultresponse: { jobid: receipt, userid: 'user', cmd: command + 'Cmd', jobstatus: status, jobresult: status === 1 ? { vpcoffering: { id: 'new-uuid', name: target } } : { errorcode: 530, errortext: 'Actual job failure' } } })
}

test.each([Add, Clone])('$name accepts a receipt without success/refresh/close and blocks duplicate submit paths', async component => {
  const wrapper = await open(component)
  wrapper.vm.handleSubmit(); wrapper.vm.handleSubmit(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(wrapper.vm.offeringOperation.status).toBe('running')
  expect(wrapper.vm.$message.success).not.toHaveBeenCalled()
  expect(wrapper.emitted('refresh-data')).toBeUndefined()
  expect(wrapper.emitted('close-action')).toBeUndefined()
  finish(); await flushPromises()
  expect(wrapper.vm.$message.success).toHaveBeenCalledTimes(1)
  expect(wrapper.emitted('refresh-data')).toHaveLength(1)
  expect(wrapper.emitted('close-action')).toHaveLength(1)
})

test.each([Add, Clone])('$name keeps submitted input/source/job and server error on real async failure', async component => {
  const wrapper = await open(component)
  wrapper.vm.handleSubmit(); await flushPromises(); finish(2); await flushPromises()
  expect(wrapper.vm.offeringOperation).toMatchObject({ status: 'failed', error: 'Actual job failure', jobId: receipt, params: { name: 'UI draft' } })
  if (component === Clone) expect(wrapper.vm.offeringOperation.params.enable).toBe(false)
  expect(wrapper.vm.form).toMatchObject({ name: 'UI draft', enable: false })
  expect(wrapper.vm.$message.success).not.toHaveBeenCalled()
  expect(wrapper.emitted('close-action')).toBeUndefined()
})

test.each(['route', 'project', 'login', 'source'])('late completion after %s replacement never closes or refreshes the new context', async change => {
  const wrapper = await open(Clone)
  wrapper.vm.handleSubmit(); await flushPromises()
  if (change === 'route') route.fullPath = '/another-page'
  if (change === 'project') store.state.project = { id: 'other-project' }
  if (change === 'login') store.state.user.token = 'other-session'
  if (change === 'source') await wrapper.setProps({ resource: { ...source, id: 'other-source', name: 'Other' } })
  await flushPromises(); finish(); await flushPromises()
  expect(wrapper.vm.$message.success).not.toHaveBeenCalled()
  expect(wrapper.emitted('refresh-data')).toBeUndefined()
  expect(wrapper.emitted('close-action')).toBeUndefined()
})

test('reopening an unresolved clone restores the exact form and blocks another POST', async () => {
  const first = await open(Clone)
  first.vm.handleSubmit(); await flushPromises()
  first.unmount()
  const reopened = await open(Clone)
  expect(reopened.vm.form.name).toBe('UI draft')
  reopened.vm.handleSubmit(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  finish(); await flushPromises()
  expect(reopened.emitted('close-action')).toBeUndefined()
  expect(reopened.vm.offeringOperation.status).toBe('succeeded')
  reopened.vm.finishOfferingOperation()
  expect(reopened.emitted('refresh-data')).toHaveLength(1)
})

test.each(['project', 'login'])('returning to the same %s context requires explicit recheck and never repeats the POST', async change => {
  const wrapper = await open(Clone)
  wrapper.vm.handleSubmit(); await flushPromises()
  if (change === 'project') store.state.project = { id: 'other-project' }
  else store.state.user.token = 'another-token'
  await flushPromises()
  if (change === 'project') store.state.project = { id: 'project' }
  else store.state.user.token = 'token'
  await flushPromises(); finish(); await flushPromises()
  expect(wrapper.vm.$message.success).not.toHaveBeenCalled()
  expect(wrapper.emitted('close-action')).toBeUndefined()
  expect(wrapper.vm.offeringOperation.status).toBe('unknown')
  await wrapper.vm.recheckOfferingOperation(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(wrapper.emitted('close-action')).toHaveLength(1)
})

test('reopening Add restores service selections and false input alongside the outstanding receipt', async () => {
  const first = await open(Add)
  first.vm.handleSubmit(); await flushPromises(); first.unmount()
  const reopened = await open(Add)
  expect(reopened.vm.form).toMatchObject({ name: 'UI draft', enable: false })
  expect(reopened.vm.selectedServiceProviderMap).toEqual({ SourceNat: 'VpcVirtualRouter' })
  const pair = reopened.findComponent({ name: 'CheckBoxSelectPair' })
  expect(pair.props('defaultCheckBoxValue')).toBe(true)
  expect(pair.props('defaultSelectValue')).toBe('VpcVirtualRouter')
  reopened.vm.handleSubmit(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
})
