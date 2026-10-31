// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and limitations under the License.

import { mount } from '@vue/test-utils'
import { defineComponent, reactive } from 'vue'
import { Form, InputNumber, Row, Col } from 'ant-design-vue'
import { getAPI } from '@/api'
import CreateSharedFS from '@/views/storage/CreateSharedFS'
import Inputs from '@/views/storage/SharedFSOfferingInputs'
import { customComputeParams, computeInputErrors, computeReasonText, offeringFailureText } from '@/utils/sharedFsOffering'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const eligibility = (fields = ['cpunumber', 'memory', 'cpuspeed']) => ({
  selectable: true,
  inputrequired: fields.length > 0,
  mode: 'free',
  constraints: Object.fromEntries(['cpunumber', 'memory', 'cpuspeed'].map(field => [field, {
    custom: fields.includes(field),
    min: field === 'memory' ? 1024 : 1,
    max: field === 'memory' ? 16384 : field === 'cpunumber' ? 8 : 10000,
    value: field === 'cpuspeed' ? 2000 : 4
  }]))
})
const t = (key, args) => key + (args ? JSON.stringify(args) : '')

describe('SharedFS creation compute contract', () => {
  beforeEach(() => getAPI.mockReset())

  it.each([
    [eligibility([]), { cpunumber: 999, memory: 999, cpuspeed: 999 }, {}],
    [eligibility(), { cpunumber: '4', memory: '8192', cpuspeed: '2000' }, { cpunumber: 4, memory: 8192, cpuspeed: 2000 }],
    [eligibility(['cpunumber', 'memory']), { cpunumber: 8, memory: 16384, cpuspeed: 999 }, { cpunumber: 8, memory: 16384 }],
    [eligibility(['memory']), { cpunumber: 4, memory: 8192, cpuspeed: 2000 }, { memory: 8192 }]
  ])('only submits custom fields', (contract, values, expected) => {
    expect(customComputeParams(contract, values)).toEqual(expected)
  })

  it.each([null, undefined, '', 2.5, 0, 9, 'NaN'])('rejects missing, fractional and out-of-range CPU %p', value => {
    expect(computeInputErrors(eligibility(['cpunumber']), { cpunumber: value })).toHaveLength(1)
  })

  it('blocks non-selectable/unknown offerings rather than inferring a policy', () => {
    expect(computeInputErrors(undefined, {})).toEqual([{ code: 'SELECTION_REQUIRED', field: 'serviceofferingid' }])
    expect(computeInputErrors({ selectable: false }, {})).toHaveLength(1)
  })

  it('does not lose custom fields through the parent form-ref filter', () => {
    const values = { name: 'new', serviceofferingid: 'id' }
    const context = {
      selectedComputeEligibility: eligibility(['memory']),
      form: { memory: 8192, cpunumber: 999, cpuspeed: 999 },
      owner: { domainid: 'domain', account: 'admin' },
      createSharedFsSize: () => 20,
      cleanParams: x => x,
      isStaticNetwork: false
    }
    const request = CreateSharedFS.methods.buildCreateSharedFsRequest.call(context, values)
    expect(request.memory).toBe(8192)
    expect(request).not.toHaveProperty('cpunumber')
    expect(request).not.toHaveProperty('cpuspeed')
  })

  it('uses the current catalog generation after out-of-order owner/zone reads', async () => {
    let firstResolve
    getAPI.mockImplementationOnce(() => new Promise(resolve => { firstResolve = resolve }))
    getAPI.mockResolvedValueOnce({
      listsharedfilesystemserviceofferingsresponse: {
        count: 1,
        sharedfilesystemserviceoffering: [{ offering: { id: 'new' }, eligibility: eligibility([]) }]
      }
    })
    const context = {
      serviceofferingGeneration: 0,
      form: { serviceofferingid: 'old' },
      selectedZone: { id: 'zone' },
      owner: { account: 'admin' },
      $store: { getters: { apis: { listSharedFileSystemServiceOfferings: {} } } },
      $t: t,
      handleComputeOfferingChange: jest.fn()
    }
    const old = CreateSharedFS.methods.fetchServiceOfferings.call(context)
    const fresh = CreateSharedFS.methods.fetchServiceOfferings.call(context)
    await fresh
    firstResolve({
      listsharedfilesystemserviceofferingsresponse: {
        count: 1,
        sharedfilesystemserviceoffering: [{ offering: { id: 'stale' }, eligibility: eligibility([]) }]
      }
    })
    await old
    expect(context.form.serviceofferingid).toBe('new')
    expect(context.serviceofferings.map(x => x.id)).toEqual(['new'])
    expect(context.serviceofferingLoading).toBe(false)
  })

  it('clears the choice on empty, failed or unavailable catalogs', async () => {
    for (const mode of ['empty', 'error', 'version']) {
      getAPI.mockReset()
      if (mode === 'error') getAPI.mockRejectedValue(new Error('network'))
      else getAPI.mockResolvedValue({ listsharedfilesystemserviceofferingsresponse: { count: 0 } })
      const context = {
        serviceofferingGeneration: 0,
        form: { serviceofferingid: 'thin' },
        selectedZone: { id: 'zone' },
        owner: {},
        $t: t,
        handleComputeOfferingChange: jest.fn(),
        $store: { getters: { apis: mode === 'version' ? {} : { listSharedFileSystemServiceOfferings: {} } } }
      }
      await CreateSharedFS.methods.fetchServiceOfferings.call(context)
      expect(context.form.serviceofferingid).toBe('')
      expect(context.serviceofferings).toEqual([])
      if (mode !== 'empty') expect(context.serviceofferingError).toBeTruthy()
    }
  })

  it('translates only structured known reasons and preserves unknown errors', () => {
    const message = offeringFailureText(t, { errortext: 'SHAREDFS_OFFERING_INELIGIBLE:[{"code":"ROOT_POLICY_THIN","field":"serviceofferingid"}]' })
    expect(message).toContain('message.sharedfs.offering.reason.root_policy_thin')
    expect(computeReasonText(t, { code: '<unsafe>' })).toContain('message.sharedfs.offering.reason.unknown')
    expect(offeringFailureText(t, { message: 'other error' })).toBe('')
  })

  it('renders actual AntD inputs with partial fixed fields read-only', async () => {
    const contract = eligibility(['memory'])
    const values = reactive({ memory: 8192 })
    const Host = defineComponent({
      components: { Inputs, AForm: Form },
      setup: () => ({ values, contract }),
      template: '<a-form :model="values"><Inputs :eligibility="contract" :values="values" @update="(k,v) => values[k]=v" /></a-form>'
    })
    const wrapper = mount(Host, {
      global: {
        components: { AFormItem: Form.Item, AInputNumber: InputNumber, ARow: Row, ACol: Col },
        mocks: { $t: t }
      }
    })
    const inputs = wrapper.findAll('input')
    expect(inputs).toHaveLength(3)
    expect(inputs[0].attributes('disabled')).toBeDefined()
    expect(inputs[1].attributes('disabled')).toBeUndefined()
    expect(inputs[2].attributes('disabled')).toBeDefined()
    await inputs[1].setValue('4096')
    expect(values.memory).toBe(4096)
    // Real AntD typing must reach our model even when invalid, without clamping on blur.
    await inputs[1].setValue('999999')
    await inputs[1].trigger('blur')
    expect(values.memory).toBe(999999)
    expect(computeInputErrors(contract, values)).toEqual([expect.objectContaining({ code: 'OUT_OF_RANGE', field: 'memory' })])
    await inputs[1].setValue('4096.5')
    await inputs[1].trigger('blur')
    expect(values.memory).toBe(4096.5)
    expect(computeInputErrors(contract, values)).toHaveLength(1)
    wrapper.unmount()
  })
})
