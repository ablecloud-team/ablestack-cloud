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

import { webhookDeliveryQuery, webhookDeliverySearch, webhookDetailParams, webhookListDetailQuery, webhookBackQuery } from '@/utils/webhookDeliveryQuery'

describe('Webhook delivery route', () => {
  it('keeps child filters out of the parent UUID request', () => {
    expect(webhookDetailParams({
      id: 'query-id',
      projectid: 'project',
      listall: true,
      keyword: 'not-the-webhook',
      eventtype: 'VM.CREATE',
      deliveryKeyword: 'filtered',
      page: 3
    }, 'webhook-id')).toEqual({ id: 'webhook-id', projectid: 'project', listall: true })
  })

  it('reads legacy links and writes separate history-only query fields', () => {
    const legacy = { tab: 'recent.deliveries', keyword: 'legacy', eventtype: 'VM.CREATE', projectid: 'project' }
    const search = webhookDeliverySearch(legacy)
    expect(search).toEqual({ searchQuery: 'legacy', eventtype: 'VM.CREATE' })
    expect(webhookDeliverySearch({ ...legacy, tab: 'details' })).toEqual({})
    const query = webhookDeliveryQuery(legacy, search, 'custom', '2026-11-24T21:40:00+09:00', null, 2, 20)
    expect(query).toEqual({
      tab: 'recent.deliveries',
      projectid: 'project',
      deliveryKeyword: 'legacy',
      deliveryEventType: 'VM.CREATE',
      deliveryPeriod: 'custom',
      deliveryStart: '2026-11-24T12:40:00.000Z',
      deliveryPage: '2',
      deliveryPageSize: '20'
    })
    expect(webhookDeliverySearch(query)).toEqual(search)
  })

  it('removes previous filters and dates when the search is reset to all time', () => {
    const query = webhookDeliveryQuery({
      tab: 'recent.deliveries', deliveryKeyword: 'older', deliveryStart: '2026-11-24', deliveryPage: '4'
    }, {}, 'all', null, null, 1, 10)
    expect(query).toEqual({ tab: 'recent.deliveries', deliveryPeriod: 'all', deliveryPageSize: '10' })
  })

  it('round trips list filters without injecting them into the delivery tab', () => {
    const list = { keyword: 'browse', state: 'Disabled', projectid: 'project', page: '2', pagesize: '20' }
    const detail = webhookListDetailQuery(list)
    expect(webhookDeliverySearch({ ...detail, tab: 'recent.deliveries' })).toEqual({})
    expect(webhookBackQuery(detail)).toEqual(list)
    expect(webhookBackQuery({ webhookList: 'invalid', projectid: 'project' })).toEqual({ projectid: 'project' })
  })
})
