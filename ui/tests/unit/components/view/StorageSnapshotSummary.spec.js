// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for ownership information.
// Licensed under the Apache License, Version 2.0. http://www.apache.org/licenses/LICENSE-2.0
import { mount } from '@vue/test-utils'
import Summary from '@/views/storage/StorageSnapshotSummary'
const id = '11111111-1111-4111-8111-111111111111'; const project = '22222222-2222-4222-8222-222222222222'
const RouterLink = { props: ['to'], computed: { href () { return typeof this.to === 'string' ? this.to : this.to.path + (this.to.query ? '?' + new URLSearchParams(this.to.query) : '') } }, template: '<a :href="href"><slot/></a>' }
const data = { id, projectid: project, physicalsize: 0, virtualsize: null, volumename: 'historical source', volumeid: id, snapshotdetail: { volume: { id, name: 'source' }, vm: { id, displayname: 'current VM' }, copies: [{ id, zoneid: id, zonename: 'zone', copy: { role: 'Image', state: 'Ready' } }] } }
function view (resource = data, apis = { listVolumes: {}, listVirtualMachines: {}, listZones: {} }, matched = [{}]) {
  return mount(Summary, { props: { resource }, global: { mocks: { $t: key => key, $store: { getters: { apis } }, $router: { resolve: () => ({ matched }) } }, stubs: { RouterLink, HddOutlined: true, DesktopOutlined: true, GlobalOutlined: true } } })
}
test('current related identities link with exact project scope and zero differs from unknown', () => {
  const w = view(); expect(w.find('a[href="/volume/' + id + '?projectid=' + project + '"]').exists()).toBe(true)
  expect(w.find('a[href="/vm/' + id + '?projectid=' + project + '"]').text()).toBe('current VM')
  expect(w.text()).toContain('0 B'); expect(w.text()).toContain('label.snapshot.list.unknown'); w.unmount()
})
test('permissions and missing UI routes suppress related links', () => {
  for (const [apis, matched] of [[{}, [{}]], [{ listVolumes: {}, listVirtualMachines: {}, listZones: {} }, [{ redirect: '/exception/404' }]]]) {
    const w = view(data, apis, matched); expect(w.findAll('a')).toHaveLength(0); w.unmount()
  }
})
test('removed source keeps historical identity and recovery explanation without a dead link', () => {
  const w = view({ ...data, snapshotdetail: { copies: [] } }); expect(w.text()).toContain('historical source'); expect(w.text()).toContain('message.snapshot.detail.source.unavailable'); expect(w.findAll('a')).toHaveLength(0); w.unmount()
})

test.each([{ copies: [] }, { copies: [{ copy: { role: 'Primary', state: 'Ready' } }] }, { copies: [{ copy: { role: 'Image', state: 'Destroyed' } }] }])('a missing usable Secondary never displays the Primary or stale backup size', ({ copies }) => {
  const w = view({ ...data, physicalsize: 1073741824, snapshotdetail: { copies } })
  const backup = w.findAll('.resource-detail-item')[1]
  expect(backup.text()).toContain('label.snapshot.list.unknown'); expect(backup.text()).not.toContain('1.00 GiB'); w.unmount()
})
