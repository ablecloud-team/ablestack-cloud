// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import { shallowMount } from '@vue/test-utils'
import TestWebhookDeliveryView from '@/components/view/TestWebhookDeliveryView.vue'
import { postAPI } from '@/api'

jest.mock('@/api', () => ({ postAPI: jest.fn() }))

const makeWrapper = (props = {}) => shallowMount(TestWebhookDeliveryView, {
  props,
  global: {
    mocks: { $t: key => key, $notifyError: jest.fn() },
    stubs: ['a-space', 'a-button', 'a-alert']
  }
})

describe('TestWebhookDeliveryView', () => {
  beforeEach(() => {
    postAPI.mockReset()
  })

  it('never sends while create settings are entered, changed or discarded', async () => {
    jest.useFakeTimers()
    try {
      const wrapper = makeWrapper({ showActions: true })
      await wrapper.setProps({ payloadUrl: 'https://webhook.example/test', sslVerification: true, secretKey: 'first' })
      jest.advanceTimersByTime(5000)
      await wrapper.setProps({ secretKey: 'second', sslVerification: false })
      wrapper.unmount()
      jest.runOnlyPendingTimers()
      expect(postAPI).not.toHaveBeenCalled()
    } finally {
      jest.useRealTimers()
    }
  })

  it('sends one request per explicit action and drops a response superseded by an edit', async () => {
    let respond
    postAPI.mockImplementation(() => new Promise(resolve => { respond = resolve }))
    const wrapper = makeWrapper({
      showActions: true,
      payloadUrl: 'https://webhook.example/first',
      sslVerification: true
    })
    const send = wrapper.vm.testWebhookDelivery()
    await wrapper.vm.testWebhookDelivery()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).toHaveBeenCalledWith('executeWebhookDelivery', {
      payloadurl: 'https://webhook.example/first',
      sslverification: true
    })
    await wrapper.setProps({ payloadUrl: 'https://webhook.example/second' })
    respond({ executewebhookdeliveryresponse: { webhookdelivery: { success: true } } })
    await send
    expect(wrapper.vm.delivery).toBeNull()
    expect(wrapper.vm.loading).toBe(false)
    wrapper.unmount()
  })

  it('distinguishes a delivery failure from a request rejection', async () => {
    const wrapper = makeWrapper({ resource: { id: 'fixture' }, payload: '{"test":true}' })
    postAPI.mockResolvedValueOnce({ executewebhookdeliveryresponse: { webhookdelivery: { success: false } } })
    await wrapper.vm.testWebhookDelivery()
    expect(postAPI).toHaveBeenCalledWith('executeWebhookDelivery', { webhookid: 'fixture', payload: '{"test":true}' })
    expect(wrapper.vm.delivery.success).toBe(false)
    expect(wrapper.vm.requestFailed).toBe(false)

    postAPI.mockRejectedValueOnce(new Error('request rejected'))
    await wrapper.vm.testWebhookDelivery()
    expect(wrapper.vm.delivery).toBeNull()
    expect(wrapper.vm.requestFailed).toBe(true)
    wrapper.unmount()
  })
})
