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

import { pollJobPlugin } from '@/utils/plugins'
import { getAPI } from '@/api'
import { message, notification } from 'ant-design-vue'
import eventBus from '@/config/eventBus'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
jest.mock('@/locales', () => ({ i18n: { global: { t: x => x } } }))
jest.mock('@/store', () => ({ getters: { countNotify: 0, headerNotices: [] }, state: { user: {} }, watch: jest.fn(), dispatch: jest.fn(), commit: jest.fn() }))
jest.mock('@/config/eventBus', () => ({ emit: jest.fn() }))
jest.mock('ant-design-vue', () => ({ message: { destroy: jest.fn(), loading: jest.fn(), success: jest.fn(), error: jest.fn() }, notification: { error: jest.fn(), warning: jest.fn() } }))
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
function setup () {
  const app = { config: { globalProperties: {} } }
  pollJobPlugin.install(app)
  return app.config.globalProperties.$pollJob.bind({ $route: { fullPath: '/vm/a' }, $router: { currentRoute: { value: { path: '/vm/a' } } } })
}
describe('pollJob notification lifecycle', () => {
  beforeEach(() => {
    jest.useFakeTimers(); jest.clearAllMocks()
    for (const method of ['destroy', 'loading', 'success', 'error']) jest.spyOn(message, method).mockImplementation(() => {})
    jest.spyOn(notification, 'warning').mockImplementation(() => {})
    jest.spyOn(notification, 'close').mockImplementation(() => {})
  })
  afterEach(() => jest.useRealTimers())
  it('cleans the second job after interrupted polling while the first succeeds', async () => {
    const count = {}
    getAPI.mockImplementation((_, { jobId }) => {
      count[jobId] = (count[jobId] || 0) + 1
      if (count[jobId] === 1) return Promise.resolve({ queryasyncjobresultresponse: { jobstatus: 0 } })
      return jobId === 'a' ? Promise.resolve({ queryasyncjobresultresponse: { jobstatus: 1 } }) : Promise.reject(new Error('offline'))
    })
    const poll = setup(); const a = poll({ jobId: 'a' }); const b = poll({ jobId: 'b', batchKey: 'batch', action: { api: 'detachIso', isFetchData: false } })
    await flush(); for (let i = 0; i < 4; i++) { jest.runOnlyPendingTimers(); await flush() }
    expect((await a).jobstatus).toBe(1); expect((await b).trackingStatus).toBe('unknown')
    expect(message.destroy).toHaveBeenCalledWith('b'); expect(notification.warning).toHaveBeenCalled()
    expect(message.error).not.toHaveBeenCalled()
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1 } })
    eventBus.emit.mockClear()
    await poll({ jobId: 'b', retry: true })
    expect(eventBus.emit).toHaveBeenCalledWith('async-job-complete', { api: 'detachIso', isFetchData: false })
    expect(notification.close).toHaveBeenCalledWith('batch')
    expect(message.success).toHaveBeenCalled()
  })
  it('cleans loading and invokes completion even when refresh listeners throw', async () => {
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1 } })
    eventBus.emit.mockImplementation(() => { throw new Error('listener') })
    const log = jest.spyOn(console, 'error').mockImplementation(() => {})
    const successMethod = jest.fn(); await setup()({ jobId: 'a', successMethod })
    expect(message.destroy).toHaveBeenCalledWith('a'); expect(successMethod).toHaveBeenCalledTimes(1)
    log.mockRestore(); eventBus.emit.mockReset()
  })
  it.each([1, 2])('keeps caller-managed refresh disabled on retry terminal %s without suppressing job details', async jobstatus => {
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus } })
    const successMethod = jest.fn(); const errorMethod = jest.fn()
    const result = await setup()({ jobId: 'owned-form-job', retry: true, refreshOnComplete: false, action: { isFetchData: false }, successMethod, errorMethod })
    expect(result.jobstatus).toBe(jobstatus)
    expect(eventBus.emit).toHaveBeenCalledWith('update-job-details', { jobId: 'owned-form-job', resourceId: undefined })
    expect(eventBus.emit.mock.calls.some(call => call[0] === 'async-job-complete')).toBe(false)
    expect(successMethod).toHaveBeenCalledTimes(jobstatus === 1 ? 1 : 0)
    expect(errorMethod).toHaveBeenCalledTimes(jobstatus === 2 ? 1 : 0)
  })
})
