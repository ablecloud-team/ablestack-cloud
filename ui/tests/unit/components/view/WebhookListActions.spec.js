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

/** @jest-environment jsdom */

import WebhookDeliveriesTab from '@/components/view/WebhookDeliveriesTab.vue'
import WebhookFiltersTab from '@/components/view/WebhookFiltersTab.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

function contextFor (component, readiness, tab) {
  const context = {
    resource: { id: 'webhook-1', name: 'test webhook' },
    $route: { params: { id: 'webhook-1' }, query: { tab } },
    $store: { getters: { apis: { executeWebhookDelivery: {} } } },
    $t: key => key,
    $confirm: jest.fn(),
    canDelete: true,
    canDeleteDeliveries: true,
    canClearAllDeliveries: true,
    totalCount: 2,
    deleteFilters: jest.fn(),
    deleteDeliveries: jest.fn(),
    redeliverDelivery: jest.fn()
  }
  Object.defineProperty(context, readiness, { get: () => component.computed[readiness].call(context) })
  return context
}

describe('Webhook list actions', () => {
  it('blocks filter removal on failed reads and abandons an outdated all-filter count', () => {
    const context = contextFor(WebhookFiltersTab, 'filterActionsReady', 'filters')
    const confirm = WebhookFiltersTab.methods.confirmDelete.bind(context)
    context.listRefreshFailed = true
    confirm(null)
    expect(context.$confirm).not.toHaveBeenCalled()

    context.listRefreshFailed = false
    confirm(null)
    context.totalCount = 3
    context.$confirm.mock.calls[0][0].onOk()
    expect(context.deleteFilters).not.toHaveBeenCalled()
  })

  it('blocks history removal and redelivery when the latest list is unavailable', () => {
    const context = contextFor(WebhookDeliveriesTab, 'deliveryActionsReady', 'recent.deliveries')
    const delivery = { id: 'delivery-1', eventtype: 'VM.CREATE' }
    context.listRefreshFailed = true
    WebhookDeliveriesTab.methods.confirmDeleteDeliveries.call(context, null)
    WebhookDeliveriesTab.methods.redeliverDeliveryConfirmation.call(context, delivery)
    expect(context.$confirm).not.toHaveBeenCalled()
  })

  it('does not redeliver a previously confirmed row after navigation away', () => {
    const context = contextFor(WebhookDeliveriesTab, 'deliveryActionsReady', 'recent.deliveries')
    const delivery = { id: 'delivery-1', eventtype: 'VM.CREATE' }
    WebhookDeliveriesTab.methods.redeliverDeliveryConfirmation.call(context, delivery)
    context.$route.params.id = 'webhook-2'
    context.$confirm.mock.calls[0][0].onOk()
    expect(context.redeliverDelivery).not.toHaveBeenCalled()
  })
})
