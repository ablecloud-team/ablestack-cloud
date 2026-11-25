// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"). You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

import { webhookDeliveryQuery, webhookDeliverySearch, webhookDetailParams } from '@/utils/webhookDeliveryQuery'

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
})
