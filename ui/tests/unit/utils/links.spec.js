// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { validateLinksAsync } from '@/utils/links'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))
const router = { resolve: jest.fn(() => ({ matched: [{ redirect: undefined }] })) }

describe('VM image link validation', () => {
  beforeEach(() => jest.clearAllMocks())
  it.each([
    ['ISO', 'listIsos', 'isofilter', 'listisosresponse', 'iso', '/iso/image-id'],
    ['QCOW2', 'listTemplates', 'templatefilter', 'listtemplatesresponse', 'template', '/template/image-id']
  ])('uses the matching API for %s images', async (format, api, filter, response, item, path) => {
    getAPI.mockResolvedValue({ [response]: { [item]: [{ id: 'image-id' }] } })
    const links = await validateLinksAsync(router, false, { templateid: 'image-id', templateformat: format })
    expect(links.template).toBe(true)
    expect(router.resolve).toHaveBeenCalledWith(path)
    expect(getAPI).toHaveBeenCalledWith(api, { [filter]: 'executable', listAll: true, id: 'image-id' })
  })
  it('validates an attached ISO separately', async () => {
    getAPI.mockResolvedValue({ listisosresponse: { iso: [{ id: 'attached' }] } })
    expect((await validateLinksAsync(router, false, { isoid: 'attached' })).iso).toBe(true)
    expect(getAPI).toHaveBeenCalledWith('listIsos', { isofilter: 'executable', listAll: true, id: 'attached' })
  })
  it('keeps unavailable images unlinked', async () => {
    getAPI.mockRejectedValue(new Error('not accessible'))
    expect((await validateLinksAsync(router, false, { templateid: 'missing' })).template).toBe(false)
  })
  it('does not fetch links for a static resource', async () => {
    await validateLinksAsync(router, true, { templateid: 'image-id' })
    expect(getAPI).not.toHaveBeenCalled()
  })
})
