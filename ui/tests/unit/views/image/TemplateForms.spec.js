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
import Template from '@/views/image/UpdateTemplate.vue'
import Register from '@/views/image/RegisterOrUploadTemplate.vue'
import { validTemplateChecksum, templateOptionMatches } from '@/utils/templateForm'
import { getAPI, postAPI } from '@/api'
import { axios } from '@/utils/request'
import { probeSsvmCert } from '@/utils/ssvmProbe'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/utils/request', () => ({ axios: { post: jest.fn() } }))
jest.mock('@/utils/ssvmProbe', () => ({ probeSsvmCert: jest.fn() }))
const metadata = Object.fromEntries(['arch', 'checksum', 'sshkeyenabled', 'requireshvm', 'forcks', 'ispublic'].map(key => [key, { description: key }]))
const context = (C, extra = {}) => {
  const c = { ...C.data.call({ action: { currentAction: { icon: 'plus-outlined' } } }), apiParams: metadata, resource: { id: 'image', name: 'original', arch: 'x86_64', hypervisor: 'KVM', passwordenabled: false, sshkeyenabled: false, requireshvm: true, forcks: false }, $store: { getters: { userInfo: { id: 'admin', roletype: 'Admin', account: 'admin', domainid: 'root' }, project: {}, apis: { registerTemplate: {}, getUploadParamsForTemplate: {}, updateTemplate: {}, getSharedFileSystemComputeStatus: {} } } }, $t: key => key, $emit: jest.fn(), $refs: {}, $nextTick: fn => fn(), $message: { success: jest.fn(), warning: jest.fn() }, $notification: { info: jest.fn() }, $notifyError: jest.fn(), handleRemoveFields: form => ({ ...form }), ...extra }
  Object.entries(C.methods).forEach(([key, fn]) => { c[key] = fn.bind(c) })
  for (const name of ['typeContext', 'allowedTemplateTypes', 'isSharedFsRegistration', 'permittedTemplateTypes']) if (C.computed[name]) Object.defineProperty(c, name, { get: () => C.computed[name].call(c) })
  c.initForm()
  c.formRef.value = { validate: jest.fn().mockResolvedValue(), scrollToField: jest.fn() }
  return c
}
beforeEach(() => { jest.clearAllMocks(); postAPI.mockReset(); getAPI.mockReset(); probeSsvmCert.mockResolvedValue(true) })
test.each(['x86_64', 'aarch64', 's390x'])('edit initial %s and false flags survive unrelated save', async arch => {
  const c = context(Template); c.resource.arch = arch; c.initForm(); c.formRef.value = { validate: jest.fn().mockResolvedValue() }
  expect(c.form.arch).toBe(arch); expect(c.form.sshkeyenabled).toBe(false); expect(c.form.passwordenabled).toBe(false)
  c.form.displaytext = 'description only'; postAPI.mockResolvedValue({}); await c.handleSubmit()
  expect(postAPI).toHaveBeenCalledWith('updateTemplate', expect.objectContaining({ arch, sshkeyenabled: false, requireshvm: true, forcks: false, displaytext: 'description only' }), { preserveOnFailure: true })
})
test('edit explicit false and empty tag are sent; unsupported/missing fields are not initialized', async () => {
  const c = context(Template, { isAdmin: true }); c.form.requireshvm = false; c.form.templatetag = ''; c.form.arch = 'aarch64'; postAPI.mockResolvedValue({}); await c.handleSubmit()
  expect(postAPI.mock.calls[0][1]).toMatchObject({ requireshvm: false, arch: 'aarch64', templatetag: '' })
  const unavailable = context(Template, { apiParams: {} }); expect(unavailable.form).not.toHaveProperty('arch'); expect(unavailable.form).not.toHaveProperty('sshkeyenabled')
})
test('cancel sends no mutation and preserves source resource', () => {
  const c = context(Template); c.form.arch = 's390x'; c.closeAction(); expect(c.resource.arch).toBe('x86_64'); expect(postAPI).not.toHaveBeenCalled(); expect(c.$emit).toHaveBeenCalledWith('close-action')
})
test('metadata saved before link failure is not repeated on retry; a new edit is saved', async () => {
  const c = context(Template, { canLinkUserData: true, userDataSelectionReady: true }); c.userdataid = 'userdata'; c.userdatapolicy = 'APPEND'
  postAPI.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('link failed')); await c.handleSubmit(); expect(c.submitError).toContain('message.userdata.image.partial')
  postAPI.mockResolvedValue({}); await c.handleSubmit(); expect(postAPI.mock.calls.map(c => c[0])).toEqual(['updateTemplate', 'linkUserDataToTemplate', 'linkUserDataToTemplate'])
  c.form.name = 'new name'; await c.handleSubmit(); expect(postAPI.mock.calls[3][0]).toBe('updateTemplate')
})
test.each(['Create', 'Upload'])('%s maps checksum, CKS and SSH with explicit private/HVM false', currentForm => {
  const c = context(Register, { currentForm }); c.form = { zoneid: 'zone', zoneids: ['zone'], hypervisor: 0, checksum: '{SHA-256}' + 'a'.repeat(64), groupenabled: ['forcks', 'sshkeyenabled'] }; c.hyperVisor.opts = [{ name: 'KVM' }]
  const params = c.registrationParams(); expect(params).toMatchObject({ checksum: c.form.checksum, forcks: true, sshkeyenabled: true, ispublic: false, requireshvm: false, hypervisor: 'KVM' }); expect(params).not.toHaveProperty(currentForm === 'Create' ? 'zoneid' : 'zoneids')
  expect(c.form.zoneid).toBe('zone'); expect(c.form.zoneids).toEqual(['zone'])
})
test('unsupported optional fields are neither submitted nor fabricated', () => {
  const c = context(Register, { apiParams: {} }); c.form.groupenabled = ['forcks', 'sshkeyenabled']; c.form.checksum = 'a'.repeat(32); const params = c.registrationParams(); expect(params).not.toHaveProperty('checksum'); expect(params).not.toHaveProperty('forcks'); expect(params).not.toHaveProperty('sshkeyenabled'); expect(params).not.toHaveProperty('requireshvm')
})
test('validation starts under one lock before a second submission', async () => {
  const c = context(Register); let release; c.formRef.value.validate.mockImplementation(() => new Promise(resolve => { release = resolve })); Object.defineProperty(c, 'blocksNavigation', { get: () => c.submitting || c.uploading || c.loading })
  postAPI.mockResolvedValue({ registertemplateresponse: { template: [{ id: 'created' }] } }); const first = c.handleSubmit(); await c.handleSubmit(); expect(c.formRef.value.validate).toHaveBeenCalledTimes(1); release(); await first; expect(postAPI).toHaveBeenCalledTimes(1)
})
test('URL metadata/link partial failure retries only link, retaining ID and policy', async () => {
  const c = context(Register); c.userdataid = 'userdata'; c.userdatapolicy = 'APPEND'; postAPI.mockResolvedValueOnce({ registertemplateresponse: { template: [{ id: 'created' }] } }).mockRejectedValueOnce(new Error('link failed'))
  await c.handleSubmit(); expect(c.linkFailed).toBe(true); expect(c.acceptedTemplateId).toBe('created'); expect(c.$emit).not.toHaveBeenCalledWith('close-action')
  postAPI.mockResolvedValue({}); await c.retryUserdataLink(); expect(postAPI.mock.calls.map(c => c[0])).toEqual(['registerTemplate', 'linkUserDataToTemplate', 'linkUserDataToTemplate']); expect(postAPI.mock.calls[2][1]).toEqual({ templateid: 'created', userdataid: 'userdata', userdatapolicy: 'APPEND' })
})
test('local certificate recovery uses accepted upload ID and never silently links wrong response shape', async () => {
  const c = context(Register, { currentForm: 'Upload' }); c.handleUpload = jest.fn(); getAPI.mockResolvedValue({ postuploadtemplateresponse: { getuploadparams: { id: 'upload-image', postURL: 'https://ssvm.example/upload' } } }); probeSsvmCert.mockResolvedValueOnce(false)
  await c.handleSubmit(); expect(c.ssvmCertUntrusted).toBe(true); expect(c.acceptedTemplateId).toBe('upload-image'); expect(c.handleUpload).not.toHaveBeenCalled(); await c.retryUpload(); expect(getAPI).toHaveBeenCalledTimes(1); expect(c.handleUpload).toHaveBeenCalledTimes(1)
})
test('API failure keeps draft and permits corrected retry', async () => {
  const c = context(Register); c.form.name = 'retained'; postAPI.mockRejectedValue(new Error('503')); await c.handleSubmit(); expect(c.form.name).toBe('retained'); expect(c.acceptedTemplateId).toBe(''); expect(c.submitError).toBe('503'); expect(c.submitting).toBe(false)
})
test.each(['', 'a'.repeat(32), '{MD5}' + 'b'.repeat(32), '{SHA-256}' + 'c'.repeat(64), '{SHA-512}' + 'D'.repeat(128)])('checksum accepts documented wire format %s', value => expect(validTemplateChecksum(value)).toBe(true))
test.each(['bad', '{SHA-256}' + 'a'.repeat(32), '{sha-256}' + 'a'.repeat(64), '{SHA-512}' + 'z'.repeat(128), ' a'.repeat(32), '{SHA-256}'])('checksum rejects malformed digest %s', value => expect(validTemplateChecksum(value)).toBe(false))

test('ambiguous transfer failure never reuses a one-time URL or creates a duplicate', async () => {
  const c = context(Register, { currentForm: 'Upload' }); c.uploadParams = { id: 'accepted', postURL: 'https://ssvm.example/upload', signature: 'test', expires: 'test', metadata: 'test' }; c.acceptedTemplateId = 'accepted'; c.fileList = []; c.finishRegistration = jest.fn().mockResolvedValue()
  axios.post.mockRejectedValueOnce(new Error('transfer reset')); await c.handleUpload(); expect(c.uploadFailed).toBe(true); expect(c.uploading).toBe(false); await c.retryUpload(); expect(axios.post).toHaveBeenCalledTimes(1); expect(getAPI).not.toHaveBeenCalled(); expect(c.finishRegistration).not.toHaveBeenCalled()
  c.$route = { query: { page: '2' } }; c.$store = { getters: {} }; c.$router = { push: jest.fn() }; c.viewAcceptedTemplate(); expect(c.$router.push).toHaveBeenCalledWith(expect.objectContaining({ path: '/template/accepted' })); expect(c.$emit).toHaveBeenCalledWith('close-action')
})
test.each([['plus-outlined', 'Create', 'registerTemplate'], ['cloud-upload-outlined', 'Upload', 'getUploadParamsForTemplate']])('self-managed %s renders with currentAction prop', (icon, mode, api) => {
  const wrapper = shallowMount({ ...Register, methods: { ...Register.methods, fetchData () {} } }, {
    props: { resource: {}, currentAction: { icon } },
    global: { mocks: { $getApiParams: jest.fn(() => metadata), $store: { getters: { userInfo: { roletype: 'Admin' }, apis: {} } }, $t: key => key }, stubs: { MoldDialog: { template: '<div><slot /></div>' }, 'a-spin': { template: '<div><slot /></div>' }, 'a-form': { template: '<form><slot /></form>' } } }
  })
  expect(wrapper.vm.currentForm).toBe(mode); expect(wrapper.vm.apiParams).toBe(metadata); expect(wrapper.vm.$getApiParams).toHaveBeenCalledWith(api); wrapper.unmount()
})

test.each([['x86_64', { label: 'Intel/AMD 64 bits (x86_64)' }], ['aarch64', { label: 'ARM 64 bits (aarch64)' }], ['s390x', { label: 'IBM Z 64 bits (s390x)' }], ['user', { label: 'USER' }], ['x86_64', { value: 'x86_64' }]])('option search %s uses current Vue option properties', (input, option) => expect(templateOptionMatches(input, option)).toBe(true))
test('option search safely rejects missing properties without crashing the selector', () => { expect(templateOptionMatches('x86_64', undefined)).toBe(false); expect(templateOptionMatches('x86_64', { children: [] })).toBe(false) })

test('normal owner edit reads details without the admin-only filter', async () => {
  const c = context(Template, { $store: { getters: { userInfo: { roletype: 'User' }, project: {} } } })
  getAPI.mockResolvedValue({ listtemplatesresponse: { template: [{ id: 'image', details: { rootDiskController: 'virtio' } }] } })
  await c.fetchDetails(); await Promise.resolve()
  expect(getAPI.mock.calls[0][1]).toEqual({ id: 'image', templatefilter: 'executable' })
})

test('local upload cannot request unsupported SHAREDFS upload parameters even if the model is forced', async () => {
  const c = context(Register, { currentForm: 'Upload' }); c.form.templatetype = 'SHAREDFS'; await c.handleSubmit(); expect(getAPI).not.toHaveBeenCalled(); expect(postAPI).not.toHaveBeenCalled(); expect(c.submitError).toContain('sharedfs.upload')
})
test('edit cannot convert a USER template or change an existing SHAREDFS profile', async () => {
  const c = context(Template, { isAdmin: true }); c.resource.templatetype = 'USER'; c.form.templatetype = 'SHAREDFS'; await c.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(c.submitError).toContain('new.registration')
  c.resource.templatetype = 'SHAREDFS'; c.form.templatetype = 'USER'; await c.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(c.submitError).toContain('immutable')
  c.form.templatetype = 'SHAREDFS'; c.form.arch = 'aarch64'; await c.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(c.submitError).toContain('immutable')
})
test('selecting supported SHAREDFS visibly pins the required downloaded private image profile', () => {
  const c = context(Register); c.form.templatetype = 'SHAREDFS'; c.form.groupenabled = ['ispublic', 'isfeatured', 'isextractable', 'sshkeyenabled']; c.form.directdownload = true; c.onTemplateTypeChange(); expect(c.form).toMatchObject({ arch: 'x86_64', format: 'QCOW2', directdownload: false }); expect(c.form.groupenabled).toEqual(['sshkeyenabled', 'isdynamicallyscalable'])
})

test('a SHAREDFS Zone refresh restores pinned KVM fields instead of leaving disabled empty controls', async () => {
  const c = context(Register); c.form.templatetype = 'SHAREDFS'; c.rootDisk.opts = []; c.fetchFormat = jest.fn(); c.fetchRootDisk = jest.fn(); c.fetchNicAdapterTypes = jest.fn(); c.fetchKeyboardType = jest.fn(); getAPI.mockResolvedValue({ listhypervisorsresponse: { hypervisor: [{ name: 'KVM' }] } }); c.fetchHyperVisor({ zoneid: 'zone' }); await new Promise(resolve => setTimeout(resolve, 0)); expect(c.form.hypervisor).toBe(0); expect(c.form.templatetype).toBe('SHAREDFS'); expect(c.form.format).toBe('QCOW2'); expect(c.form.arch).toBe('x86_64')
})
