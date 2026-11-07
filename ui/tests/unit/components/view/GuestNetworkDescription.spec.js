// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.
import { mount } from '@vue/test-utils'
import GuestNetworkDescription from '@/components/view/GuestNetworkDescription.vue'

test.each([undefined, null, '', ' \n '])('missing or blank descriptions use the same placeholder: %p', value => {
  const w = mount(GuestNetworkDescription, { props: { value } })
  expect(w.text()).toBe('—')
  w.unmount()
})

test('summary and detail retain multiline Korean and HTML-like strings as plain text', async () => {
  const value = '설명 첫 줄 & 특수문자\n<script>window.qa1558 = true</script>\n<img src=x onerror=alert(1)> ' + '긴설명'.repeat(30)
  const w = mount(GuestNetworkDescription, { props: { value, summary: true }, global: { mocks: { $t: () => '설명' } } })
  expect(w.get('.guest-network-description__text').element.textContent).toBe(value)
  expect(w.find('script').exists()).toBe(false)
  expect(w.find('img').exists()).toBe(false)
  expect(w.text()).toContain('설명')
  await w.setProps({ value: '서버에서 다시 조회한 설명', summary: false })
  expect(w.text()).toBe('서버에서 다시 조회한 설명')
  w.unmount()
})
