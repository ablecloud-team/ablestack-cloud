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

import { mount } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'

describe('SharedFS history listener lifecycle', () => {
  const wrappers = []
  const create = () => {
    const setCurrentTab = jest.fn()
    const cancelAdMutation = jest.fn()
    const wrapper = mount({
      template: '<div />',
      data: () => ({ resource: { id: 'sharedfs' } }),
      created: SharedFSTab.created,
      beforeUnmount: SharedFSTab.beforeUnmount,
      methods: {
        initStorageDefaults: jest.fn(),
        fetchData: jest.fn(),
        setCurrentTab,
        cancelAdMutation
      }
    })
    wrappers.push(wrapper)
    return { wrapper, setCurrentTab, cancelAdMutation }
  }
  afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

  it('stops history callbacks as soon as the detail view unmounts', () => {
    const { wrapper, setCurrentTab, cancelAdMutation } = create()
    window.dispatchEvent(new PopStateEvent('popstate'))
    expect(setCurrentTab).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    window.dispatchEvent(new PopStateEvent('popstate'))
    expect(setCurrentTab).toHaveBeenCalledTimes(1)
    expect(cancelAdMutation).toHaveBeenCalledTimes(1)
  })

  it('keeps only the current detail view subscribed after navigation', () => {
    const previous = create()
    previous.wrapper.unmount()
    const current = create()
    window.dispatchEvent(new PopStateEvent('popstate'))
    expect(previous.setCurrentTab).not.toHaveBeenCalled()
    expect(current.setCurrentTab).toHaveBeenCalledTimes(1)
  })
})
