// Licensed under the Apache License, Version 2.0. See NOTICE.
import { mount, flushPromises } from '@vue/test-utils'
import Tab from '@/views/storage/SharedFSBootRecoveryTab.vue'
import Summary from '@/views/storage/SharedFSBootRecoverySummary.vue'
import SharedFS from '@/views/storage/SharedFSTab.vue'
import { bootRecoveryObservation, bootResumeRequest } from '@/utils/sharedfsBootRecovery'
import wire from '../../../fixtures/sharedfs-1459-boot-wire.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const { postAPI } = require('@/api')
const real = () => JSON.parse(JSON.stringify(wire.resumestorageservicebootrecoveryresponse.storageserviceruntime))
const rows = () => [real()]
const instance = () => ({ id: real().id, configurationprotection: false })
const box = { template: '<div><slot /><slot name="footer" /></div>' }
const button = { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' }
const stubs = {
  ASpace: box,
  AButton: button,
  AAlert: true,
  ADescriptions: box,
  ADescriptionsItem: box,
  ATable: true,
  AInputSearch: true,
  MoldDialog: { name: 'MoldDialog', props: { formActions: Boolean }, template: '<section role="dialog"><header><slot name="title" /></header><article><slot /></article><footer><slot name="footer" /></footer></section>' },
  MoldDialogActions: box,
  RouterLink: { name: 'RouterLink', props: ['to'], template: '<a><slot /></a>' }
}
let wrapper
let job
const mocks = { $t: x => x, $pollJob: value => { job = value } }
function create (props = {}) {
  wrapper = mount(Tab, { props: { instance: instance(), inventory: rows(), ...props }, global: { mocks, stubs } })
  return wrapper
}
afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null; jest.clearAllMocks(); job = null })
test('actual API string result preserves first failure and independent SMB readiness', () => {
  const value = bootRecoveryObservation(rows())
  expect(value.unknown).toBe(false)
  expect(value.value.firstFailure.code).toBe('NFS_PROBE_TIMEOUT')
  create()
  expect(wrapper.vm.state).toBe('PAUSED')
  expect(wrapper.vm.protocols.find(x => x.protocol === 'SMB').state).toBe('READY')
  expect(wrapper.findAll('button')[0].attributes('disabled')).toBeUndefined()
})
test('protected configuration, UNKNOWN, wrong epoch and arbitrary protocol cannot submit', async () => {
  create({ guarded: true })
  expect(wrapper.findAll('button')[0].attributes('disabled')).toBeDefined()
  await wrapper.vm.resume()
  expect(postAPI).not.toHaveBeenCalled()
  expect(bootResumeRequest({ ...instance(), configurationprotection: true }, bootRecoveryObservation(rows()).value)).toBeNull()
  const bad = { ...bootRecoveryObservation(rows()).value, bootId: 'invalid' }
  expect(bootResumeRequest(instance(), bad)).toBeNull()
})
test('real serializer nested inherited job ID is observed and not treated as boot completion', async () => {
  create()
  postAPI.mockResolvedValue(JSON.parse(JSON.stringify(wire)))
  const pending = wrapper.vm.resume()
  await flushPromises()
  expect(wrapper.vm.busy).toBe(true)
  expect(job.jobId).toBe('job-1459')
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][0]).toBe('resumeStorageServiceBootRecovery')
  expect(postAPI.mock.calls[0][1]).toEqual(bootResumeRequest(instance(), bootRecoveryObservation(rows()).value))
  await wrapper.vm.resume()
  expect(postAPI).toHaveBeenCalledTimes(1)
  job.successMethod()
  await pending
  expect(wrapper.emitted('refresh')).toHaveLength(1)
  expect(wrapper.vm.value.state).toBe('PAUSED')
})
test('a lost read keeps previous diagnostics but disables a new mutation', async () => {
  create()
  await wrapper.setProps({ unavailable: true, inventory: [] })
  expect(wrapper.vm.state).toBe('UNKNOWN')
  expect(wrapper.vm.value.firstFailure.code).toBe('NFS_PROBE_TIMEOUT')
  expect(wrapper.findAll('button')[0].attributes('disabled')).toBeDefined()
  await wrapper.vm.resume()
  expect(postAPI).not.toHaveBeenCalled()
})
test('server error stays visible in the standard confirmation dialog', async () => {
  create()
  wrapper.vm.dialog = 'resume'
  postAPI.mockRejectedValue({ response: { data: { errorresponse: { errortext: 'BOOT_OBSERVATION_CHANGED' } } } })
  await wrapper.vm.resume()
  expect(wrapper.vm.error).toBe('BOOT_OBSERVATION_CHANGED')
  expect(wrapper.vm.dialog).toBe('resume')
  expect(wrapper.findComponent({ name: 'MoldDialog' }).props('formActions')).toBe(true)
})
test('old native missing field and malformed API result remain unknown', () => {
  expect(bootRecoveryObservation([{ success: true, resultjson: '{}' }]).unknown).toBe(true)
  expect(bootRecoveryObservation([{ success: true, resultjson: '{broken' }]).unknown).toBe(true)
  expect(bootResumeRequest(instance(), null)).toBeNull()
})
test('read-only list summary routes to recovery and does not invent ready from stale cache', async () => {
  const resource = { id: real().id, bootrecovery: { state: 'READY', stale: true } }
  wrapper = mount(Summary, { props: { resource, mode: 'list' }, global: { mocks, stubs } })
  expect(wrapper.text()).toContain('label.storage.boot.state.unknown')
  expect(wrapper.findComponent({ name: 'RouterLink' }).props('to').query.tab).toBe('bootrecovery')
})
test('actual SharedFSTab ignores a late inventory result after resource scope changes', async () => {
  let finish
  const context = {
    storageService: { instance: { id: 'old' }, inventory: [] },
    storageReadScope: 'old',
    storageReadErrors: {},
    listRefreshDisposed: false,
    listApi: jest.fn(() => new Promise(resolve => { finish = resolve }))
  }
  const pending = SharedFS.methods.refreshBootRecovery.call(context)
  context.storageReadScope = 'new'
  context.storageService.instance.id = 'new'
  finish(rows())
  await pending
  expect(context.storageService.inventory).toEqual([])
})
