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

import SharedFSTab from '@/views/storage/SharedFSTab'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

describe('SharedFS read transport ownership', () => {
  const context = { $store: { getters: { apis: {} } } }
  beforeEach(() => getAPI.mockReset())

  it('keeps transient reads in the view with a bounded transport deadline', async () => {
    getAPI.mockResolvedValue({ liststorageservicehealthresponse: { storageserviceruntime: [{ status: 'ok' }] } })
    const values = await SharedFSTab.methods.listApi.call(context, 'listStorageServiceHealth', { instanceid: 'instance' }, 'storageserviceruntime')
    expect(values).toEqual([{ status: 'ok' }])
    expect(getAPI).toHaveBeenCalledWith('listStorageServiceHealth', { instanceid: 'instance' }, { preserveOnFailure: true, timeout: 15000 })
  })

  it('propagates transport failure to section-level recovery', async () => {
    const error = new Error('network unavailable')
    getAPI.mockRejectedValue(error)
    await expect(SharedFSTab.methods.listApi.call(context, 'listStorageServiceHealth', { instanceid: 'instance' }, 'storageserviceruntime')).rejects.toBe(error)
  })
})
