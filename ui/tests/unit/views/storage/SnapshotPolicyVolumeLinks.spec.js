// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for ownership information.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import Details from '@/views/storage/SnapshotPolicyDetails'
import { policyVolumePath } from '@/utils/snapshotPolicyDetail'
const record = { id: 'policy', volumeid: 'volume', volumename: 'project volume', projectid: 'project', intervaltype: 0, schedule: '5', timezone: 'UTC', maxsnaps: 1 }
test('API-only volume read permission cannot advertise the unavailable volume UI route', () => {
  expect(policyVolumePath(record, { listVolumes: {} })).toBeNull()
  expect(policyVolumePath({}, { listVolumesMetrics: {} })).toBeNull()
  expect(policyVolumePath(record)).toBeNull()
})
test('allowed volume navigation retains the policy project and personal navigation has no invented scope', () => {
  expect(policyVolumePath(record, { listVolumesMetrics: {} })).toEqual({ path: '/volume/volume', query: { projectid: 'project' } })
  expect(policyVolumePath({ volumeid: 'volume' }, { listVolumesMetrics: {} })).toEqual({ path: '/volume/volume' })
})
test('the rendered details field becomes plain text immediately on permission revocation', async () => {
  const store = reactive({ getters: { apis: { listVolumes: {} } } })
  const wrapper = mount(Details, { props: { resource: record }, global: { mocks: { $store: store, $t: k => k }, stubs: { RouterLink: { name: 'RouterLink', props: ['to'], template: '<a :href="to.path"><slot /></a>' } } } })
  expect(wrapper.find('a[href="/volume/volume"]').exists()).toBe(false)
  expect(wrapper.text()).toContain('project volume')
  store.getters.apis.listVolumesMetrics = {}; await flushPromises()
  expect(wrapper.findComponent({ name: 'RouterLink' }).props('to')).toEqual({ path: '/volume/volume', query: { projectid: 'project' } })
  delete store.getters.apis.listVolumesMetrics; await flushPromises()
  expect(wrapper.find('a[href="/volume/volume"]').exists()).toBe(false)
  expect(wrapper.text()).toContain('project volume'); wrapper.unmount()
})
