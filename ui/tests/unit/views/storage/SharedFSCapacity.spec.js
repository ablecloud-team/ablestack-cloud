// Licensed under the Apache License, Version 2.0. See NOTICE.
import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import Summary from '@/views/storage/SharedFSCapacitySummary.vue'
import Capacity from '@/views/storage/SharedFSCapacityTab.vue'
import wire from '../../../fixtures/sharedfs-1458-capacity-wire.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const { getAPI, postAPI } = require('@/api')
const row = () => JSON.parse(JSON.stringify(wire.listsharedfilesystemsresponse.sharedfs[0]))
const bytes = value => value + ' bytes'
const container = { template: '<div><slot /><slot name="footer" /></div>' }
const mocks = { $t: x => x, $bytesToHumanReadableSize: bytes }
const stubs = {
  RouterLink: { props: ['to'], template: '<a><slot /></a>' },
  ATag: container,
  ASpace: container,
  AAlert: true,
  ATable: { props: ['dataSource'], template: '<div data-test="inventory">{{ dataSource.map(x=>x.name).join(",") }}</div>' },
  AButton: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
  AInputSearch: { props: ['value'], emits: ['update:value'], template: '<input :value="value" @input="$emit(\'update:value\', $event.target.value)" />' },
  AModal: { props: ['visible'], template: '<section role="dialog" v-if="visible"><header><slot name="title" /></header><article><slot /></article><footer><slot name="footer" /></footer></section>' }
}
let wrapper
afterEach(() => { if (wrapper) wrapper.unmount(); jest.clearAllMocks() })
test('real API wire separates initial/provisioned bytes and observed subset in the list', () => {
  wrapper = mount(Summary, { props: { resource: row(), mode: 'list' }, global: { mocks, stubs } })
  expect(wrapper.text()).toContain(22 * 1024 ** 3 + ' bytes')
  expect(wrapper.text()).toContain('DATA 3')
  expect(wrapper.text()).toContain('108703744 bytes')
  expect(wrapper.text()).toContain('label.storage.capacity.status.partial')
  expect(wrapper.text()).not.toContain('[object Object]')
})
test('zero is displayed as measured and unavailable is never fabricated zero', async () => {
  const resource = row()
  resource.capacity.filesystemusedbytes = 0
  wrapper = mount(Summary, { props: { resource, mode: 'list' }, global: { mocks, stubs } })
  expect(wrapper.text()).toContain('0 bytes')
  delete resource.capacity.filesystemusedbytes
  resource.capacity.usagestatus = 'UNAVAILABLE'
  await wrapper.setProps({ resource: { ...resource } })
  expect(wrapper.text()).toContain('label.storage.capacity.observed.usage —')
  expect(wrapper.text()).not.toContain('0 bytes')
})
test('old API fallback explicitly labels the initial volume rather than guessing a total', () => {
  const resource = row()
  delete resource.capacity
  wrapper = mount(Summary, { props: { resource, mode: 'list' }, global: { mocks, stubs } })
  expect(wrapper.text()).toContain('10.00 GiB')
  expect(wrapper.text()).toContain('label.storage.capacity.initial')
  expect(wrapper.text()).not.toContain('DATA 3')
})
test('inventory search does not change totals and update emits one parent refresh with no per-volume APIs', async () => {
  wrapper = mount(Capacity, { props: { resource: row() }, global: { mocks, stubs } })
  await wrapper.find('input').setValue('data-3')
  expect(wrapper.find('[data-test="inventory"]').text()).toBe('data-3')
  expect(wrapper.text()).toContain(22 * 1024 ** 3 + ' bytes')
  await wrapper.findAll('button').find(x => x.text() === 'label.refresh').trigger('click')
  expect(wrapper.emitted('refresh')).toHaveLength(1)
  expect(getAPI).not.toHaveBeenCalled()
  expect(postAPI).not.toHaveBeenCalled()
})
test('read-only rules use the real MoldDialog fixed header/body/footer structure', async () => {
  wrapper = mount(Capacity, { props: { resource: row() }, global: { mocks, stubs } })
  await wrapper.findAll('button').find(x => x.text() === 'label.storage.capacity.rules').trigger('click')
  await flushPromises()
  const dialog = wrapper.findComponent({ name: 'MoldDialog' })
  expect(dialog.props('size')).toBe('md')
  expect(wrapper.find('[role="dialog"] header').text()).toBe('label.storage.capacity.rules')
  expect(wrapper.find('[role="dialog"] footer').text()).toBe('label.close')
  expect(wrapper.find('[role="dialog"] article').text()).toContain('message.storage.capacity.aliases')
  expect(wrapper.find('[role="dialog"] article button').exists()).toBe(false)
})
test.each(['STALE', 'TRANSITION', 'UNSUPPORTED'])('read-only summary preserves %s status and observation time', status => {
  const resource = row()
  resource.capacity.usagestatus = status
  if (status === 'TRANSITION' || status === 'UNSUPPORTED') delete resource.capacity.filesystemusedbytes
  wrapper = shallowMount(Summary, { props: { resource, mode: 'list' }, global: { mocks, stubs } })
  expect(wrapper.text()).toContain('label.storage.capacity.status.' + status.toLowerCase())
  expect(wrapper.text()).toContain(resource.capacity.observedat)
})
