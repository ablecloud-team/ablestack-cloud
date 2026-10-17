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
import SharedFSTab from '@/views/storage/SharedFSTab'

const Input = {
  props: ['value'],
  emits: ['update:value'],
  template: '<input :value="value" @input="$emit(\'update:value\', $event.target.value)" />'
}
const Password = { ...Input, template: '<input type="password" :value="value" @input="$emit(\'update:value\', $event.target.value)" />' }
const Switch = {
  props: ['checked'],
  emits: ['update:checked'],
  template: '<input type="checkbox" :checked="checked" @change="$emit(\'update:checked\', $event.target.checked)" />'
}
const Widget = {
  ...SharedFSTab,
  mixins: [],
  // Suppress unrelated inventory lifecycle calls; use production render/data/methods.
  created () {},
  mounted () {}
}

function field (wrapper, label) {
  const item = wrapper.findAll('.form-item').find(item => item.find('label').text() === label)
  return item ? item.find('input') : null
}

async function dialog (type = 'iscsiAcl') {
  const runStorageAction = jest.fn().mockResolvedValue(undefined)
  const error = jest.fn()
  const wrapper = shallowMount(Widget, {
    props: { resource: { id: 'fs-own', name: 'service-own' } },
    global: {
      provide: { parentFetchData: jest.fn() },
      mocks: {
        $t: key => key,
        $message: { error },
        $route: { path: '/sharedfs/fs-own', query: {}, params: {} },
        $store: { getters: { apis: {}, resourceTabPosition: 'top' } }
      },
      stubs: {
        'a-spin': { template: '<div><slot /></div>' },
        'a-tabs': true,
        'a-modal': { props: ['visible'], template: '<div v-if="visible" class="acl-dialog"><slot /></div>' },
        'a-form': { template: '<div><slot /></div>' },
        'a-form-item': { template: '<div class="form-item"><label><slot name="label" /></label><slot /></div>' },
        TooltipLabel: { props: ['title'], template: '<span>{{ title }}</span>' },
        'a-input': Input,
        'a-input-password': Password,
        'a-switch': Switch
      }
    }
  })
  wrapper.vm.runStorageAction = runStorageAction
  await wrapper.setData({
    actionModal: { visible: true, type, context: {}, loading: false },
    storageService: { ...wrapper.vm.storageService, iscsiTargets: [{ id: 'target-own', targetname: 'iqn.2026-05.local.storage:own' }] }
  })
  wrapper.vm.resetIscsiAclForm()
  await wrapper.vm.$nextTick()
  return { wrapper, runStorageAction, error }
}

async function oneWay (wrapper) {
  await field(wrapper, 'label.storage.service.chap.enabled').setValue(true)
  await field(wrapper, 'label.storage.service.chap.username').setValue('synthetic-host')
  await field(wrapper, 'label.storage.service.chap.secret').setValue('synthetic-host-secret')
}

async function mutual (wrapper) {
  await oneWay(wrapper)
  await field(wrapper, 'label.storage.service.mutual.chap.enabled').setValue(true)
  await field(wrapper, 'label.storage.service.mutual.chap.username').setValue('synthetic-target')
  await field(wrapper, 'label.storage.service.mutual.chap.secret').setValue('synthetic-target-secret')
}

describe('SharedFSTab mutual CHAP dialog', () => {
  let wrapper
  afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null })

  it('renders the three conditional controls and binds real user input', async () => {
    ({ wrapper } = await dialog())
    expect(field(wrapper, 'label.storage.service.mutual.chap.enabled')).toBeNull()
    await oneWay(wrapper)
    expect(field(wrapper, 'label.storage.service.mutual.chap.enabled')).not.toBeNull()
    expect(field(wrapper, 'label.storage.service.mutual.chap.username')).toBeNull()
    await field(wrapper, 'label.storage.service.mutual.chap.enabled').setValue(true)
    await field(wrapper, 'label.storage.service.mutual.chap.username').setValue('synthetic-target')
    await field(wrapper, 'label.storage.service.mutual.chap.secret').setValue('synthetic-target-secret')
    expect(wrapper.vm.forms.iscsiAcl.mutualchapusername).toBe('synthetic-target')
    expect(wrapper.vm.forms.iscsiAcl.mutualchapsecret).toBe('synthetic-target-secret')
    expect(field(wrapper, 'label.storage.service.mutual.chap.secret').attributes('type')).toBe('password')
  })

  it('passes entered mutual credentials to the existing create API and clears form secrets', async () => {
    const context = await dialog(); wrapper = context.wrapper
    await mutual(wrapper)
    await field(wrapper, 'label.storage.service.allowed.initiator.iqn').setValue('iqn.2026-05.local.client:own')
    await wrapper.vm.createIscsiAcl()
    expect(context.runStorageAction).toHaveBeenCalledWith('iscsiAcl', 'createStorageIscsiAcl', {
      targetid: 'target-own',
      initiatoriqn: 'iqn.2026-05.local.client:own',
      permission: 'READ_WRITE',
      chapenabled: true,
      chapusername: 'synthetic-host',
      chapsecret: 'synthetic-host-secret',
      mutualchapenabled: true,
      mutualchapusername: 'synthetic-target',
      mutualchapsecret: 'synthetic-target-secret'
    }, 'label.storage.service.create.iscsi.acl')
    expect(wrapper.vm.forms.iscsiAcl.chapsecret).toBe('')
    expect(wrapper.vm.forms.iscsiAcl.mutualchapsecret).toBe('')
  })

  it.each(['username', 'secret'])('blocks the API when mutual %s is missing without echoing credentials', async missing => {
    const context = await dialog(); wrapper = context.wrapper
    await mutual(wrapper)
    await field(wrapper, 'label.storage.service.mutual.chap.' + missing).setValue('')
    await wrapper.vm.createIscsiAcl()
    expect(context.runStorageAction).not.toHaveBeenCalled()
    expect(context.error).toHaveBeenCalledWith('message.storage.service.iscsi.mutual.chap.credential.required')
    expect(JSON.stringify(context.error.mock.calls)).not.toMatch(/synthetic-host|synthetic-target/)
    expect(wrapper.vm.forms.iscsiAcl.chapsecret).toBe('synthetic-host-secret')
  })

  it('populates existing mutual ACL without secrets and updates the same ACL only after reentry', async () => {
    const context = await dialog('editIscsiAcl'); wrapper = context.wrapper
    wrapper.vm.populateIscsiAclForm({
      id: 'acl-own',
      resourceid: 'target-own',
      principal: 'iqn.2026-05.local.client:own',
      permission: 'READ_ONLY',
      chapenabled: true,
      chapusername: 'existing-host',
      mutualchapenabled: true,
      mutualchapusername: 'existing-target'
    })
    await wrapper.vm.$nextTick()
    expect(field(wrapper, 'label.storage.service.chap.secret').element.value).toBe('')
    expect(field(wrapper, 'label.storage.service.mutual.chap.secret').element.value).toBe('')
    await wrapper.vm.updateIscsiAcl()
    expect(context.runStorageAction).not.toHaveBeenCalled()
    await field(wrapper, 'label.storage.service.chap.secret').setValue('synthetic-host-secret')
    await field(wrapper, 'label.storage.service.mutual.chap.secret').setValue('synthetic-target-secret')
    await wrapper.vm.updateIscsiAcl()
    expect(context.runStorageAction).toHaveBeenCalledWith('editIscsiAcl', 'updateStorageIscsiAcl', {
      id: 'acl-own',
      initiatoriqn: 'iqn.2026-05.local.client:own',
      permission: 'READ_ONLY',
      chapenabled: true,
      chapusername: 'existing-host',
      chapsecret: 'synthetic-host-secret',
      mutualchapenabled: true,
      mutualchapusername: 'existing-target',
      mutualchapsecret: 'synthetic-target-secret'
    }, 'label.storage.service.update.iscsi.acl')
    expect(wrapper.vm.forms.iscsiAcl.chapsecret).toBe('')
    expect(wrapper.vm.forms.iscsiAcl.mutualchapsecret).toBe('')
  })

  it('sends disabled mutual authentication only after the user switches it off', async () => {
    const context = await dialog(); wrapper = context.wrapper
    await mutual(wrapper)
    await field(wrapper, 'label.storage.service.mutual.chap.enabled').setValue(false)
    expect(field(wrapper, 'label.storage.service.mutual.chap.secret')).toBeNull()
    await wrapper.vm.createIscsiAcl()
    expect(context.runStorageAction.mock.calls[0][2]).toMatchObject({ chapenabled: true, mutualchapenabled: false, mutualchapusername: '', mutualchapsecret: '' })
  })

  it('preserves the existing no-auth request when the user switches CHAP off', async () => {
    const context = await dialog(); wrapper = context.wrapper
    await mutual(wrapper)
    await field(wrapper, 'label.storage.service.chap.enabled').setValue(false)
    expect(field(wrapper, 'label.storage.service.mutual.chap.enabled')).toBeNull()
    await wrapper.vm.createIscsiAcl()
    expect(context.runStorageAction.mock.calls[0][2]).toMatchObject({ chapenabled: false, chapusername: '', chapsecret: '', mutualchapenabled: false, mutualchapusername: '', mutualchapsecret: '' })
  })
})
