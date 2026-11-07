// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import NetworkIpVmLink from '@/components/widgets/NetworkIpVmLink'
import { networkIpVmReference } from '@/utils/networkIpVmReference'

const id = '4e6e8d47-779b-41f6-a4de-6f9914a92c69'
const named = { virtualmachineid: id, virtualmachinename: 'host-name', virtualmachinedisplayname: '검토 VM' }
const legacyShared = { ...named, forvirtualnetwork: false, issystem: false }
async function render (record) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/')
  await router.isReady()
  return { wrapper: mount(NetworkIpVmLink, { props: { record }, global: { plugins: [router] } }), router }
}

test.each([undefined, null, '', ' ', 'undefined', 'null', 'NaN', 'none', {}, [], 0, true, '../vm', 'a/b', 'a?x=1', '#bad', 'a b', '%2F'])('invalid identity %p produces no link, icon or focus target', async value => {
  const { wrapper } = await render({ ...named, virtualmachineid: value, virtualmachinetype: 'User' })
  expect(wrapper.text()).toBe('검토 VM')
  expect(wrapper.find('a, button, [tabindex], .anticon').exists()).toBe(false)
  wrapper.unmount()
})
test.each([['User', '/vm/'], ['DomainRouter', '/router/'], ['ConsoleProxy', '/systemvm/'], ['SecondaryStorageVm', '/systemvm/']])('%s navigates to its real resource route, including ID-only labels', async (type, path) => {
  const { wrapper, router } = await render({ virtualmachineid: id, virtualmachinetype: type })
  expect(wrapper.get('a').text()).toBe(id)
  expect(wrapper.get('a').attributes('href')).toBe(path + id)
  await wrapper.get('a').trigger('click', { button: 0 })
  await flushPromises()
  expect(router.currentRoute.value.path).toBe(path + id)
  wrapper.unmount()
})
test.each([undefined, null, '', 'Unknown', 'System', 'user', '__proto__', 'constructor'])('unconfirmed type %p never invents a VM route', type => {
  expect(networkIpVmReference({ ...named, virtualmachinetype: type }).path).toBe('')
})
test('legacy Shared user-NIC contract stays linked; system/router/incomplete payloads do not inherit it', () => {
  expect(networkIpVmReference(legacyShared)).toEqual({ label: '검토 VM', path: '/vm/' + id })
  for (const extra of [{ issystem: true }, { forrouter: true }, { virtualmachinedisplayname: '' }, { virtualmachinename: '' }, { forvirtualnetwork: true }, { issystem: undefined }, { virtualmachinetype: 'Unknown' }]) {
    expect(networkIpVmReference({ ...legacyShared, ...extra }).path).toBe('')
  }
  expect(networkIpVmReference({ virtualmachineid: id }).path).toBe('')
})
test('Source NAT has a visible dash; name-only is plain escaped text', async () => {
  const { wrapper } = await render({ issourcenat: true })
  expect(wrapper.text()).toBe('—')
  expect(wrapper.find('a').exists()).toBe(false)
  await wrapper.setProps({ record: { virtualmachinename: '<img src=x onerror=alert(1)>' } })
  expect(wrapper.find('img').exists()).toBe(false)
  expect(wrapper.text()).toBe('<img src=x onerror=alert(1)>')
  wrapper.unmount()
})
test('instant record changes remove old focus targets and recalculate type without lookups', async () => {
  const { wrapper } = await render({ ...named, virtualmachinetype: 'User' })
  expect(wrapper.get('a').text()).toBe('검토 VM')
  await wrapper.setProps({ record: {} })
  expect(wrapper.find('a').exists()).toBe(false)
  expect(wrapper.text()).toBe('—')
  await wrapper.setProps({ record: { ...named, virtualmachinetype: 'DomainRouter' } })
  expect(wrapper.get('a').attributes('href')).toBe('/router/' + id)
  await wrapper.setProps({ record: { ...named, virtualmachinetype: 'Unknown' } })
  expect(wrapper.find('a').exists()).toBe(false)
  wrapper.unmount()
})
