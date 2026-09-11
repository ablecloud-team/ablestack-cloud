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

import axios from 'axios'
import { axios as service } from '@/utils/request'
import router from '@/router'
import store from '@/store'
import notification from 'ant-design-vue/es/notification'

jest.mock('@/router', () => ({ currentRoute: { value: { path: '/volume', fullPath: '/volume' } }, push: jest.fn() }))
jest.mock('@/store', () => ({ getters: { countNotify: 0 }, commit: jest.fn(), dispatch: jest.fn(() => Promise.resolve()) }))
jest.mock('ant-design-vue/es/notification', () => ({ error: jest.fn() }))
jest.mock('@/locales', () => ({ i18n: { global: { t: key => key } } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { get: () => undefined } } }))

const rejectResponse = service.interceptors.response.handlers[0].rejected

describe('request failure session handling', () => {
  beforeEach(() => jest.clearAllMocks())

  it('keeps the session when route navigation cancels an Axios request', async () => {
    const source = axios.CancelToken.source()
    source.cancel('route changed')
    const error = source.token.reason
    expect(error.isAxiosError).toBe(true)
    await expect(rejectResponse(error)).rejects.toBe(error)
    expect(notification.error).not.toHaveBeenCalled()
    expect(store.dispatch).not.toHaveBeenCalled()
    expect(router.push).not.toHaveBeenCalled()
  })

  it('retains existing network failure notification and logout behavior', async () => {
    const error = new axios.AxiosError('Network Error', 'ERR_NETWORK')
    await expect(rejectResponse(error)).rejects.toBe(error)
    expect(notification.error).toHaveBeenCalledWith(expect.objectContaining({ key: 'network-error' }))
    expect(store.dispatch).toHaveBeenCalledWith('Logout')
    expect(router.push).toHaveBeenCalledWith({ path: '/user/login', query: { redirect: '/volume' } })
  })
})
