// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import { mount } from '@vue/test-utils'
import ImportOffering from '@/views/offering/ImportBackupOffering'
import CloneOffering from '@/views/offering/CloneBackupOffering'
import UpdateOffering from '@/views/offering/UpdateBackupOffering'
import offerings from '@/config/section/offering'
import { getAPI, postAPI } from '@/api'
import ko from '@public/locales/ko_KR.json'
import en from '@public/locales/en.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/role', () => ({ isAdmin: () => true }))

const inventory = rows => ({ listbackupofferingsresponse: { backupoffering: rows, count: rows.length } })
const external = ids => ({ listbackupproviderofferingsresponse: { backupoffering: ids.map(externalid => ({ id: 'provider-' + externalid, externalid, name: externalid })) } })
function context (component, provider = 'ablestack-nas', overrides = {}) {
  const ctx = {
    ...component.data(),
    selectedZoneId: 'zone-a',
    selectedProviderName: provider,
    resource: { id: 'source', provider, zoneid: 'zone-a' },
    form: { name: 'New offering', description: 'Description', zoneid: 'Zone A', providername: provider, ispublic: true, allowuserdrivenbackups: true },
    formRef: { value: { validate: jest.fn().mockResolvedValue(), scrollToField: jest.fn() } },
    $t: key => ko[key] || key,
    $notifyError: jest.fn(),
    $notification: { error: jest.fn() },
    $message: { success: jest.fn() },
    $pollJob: jest.fn(),
    $emit: jest.fn(),
    ...overrides
  }
  for (const [name, fn] of Object.entries(component.methods)) ctx[name] = fn.bind(ctx)
  for (const [name, fn] of Object.entries(component.computed || {})) Object.defineProperty(ctx, name, { get: () => fn.call(ctx) })
  return ctx
}
function mockLists (providerIds, registered = []) {
  getAPI.mockImplementation(command => Promise.resolve(command === 'listBackupOfferings' ? inventory(registered) : external(providerIds)))
}
beforeEach(() => { jest.clearAllMocks() })

test.each([ImportOffering, CloneOffering])('$name shows no options when both NAS repositories are registered', async component => {
  const ctx = context(component)
  mockLists(['repo-1', 'repo-2'], [
    { id: 'mold-1', externalid: 'repo-1', zoneid: 'zone-a', provider: 'ablestack-nas' },
    { id: 'mold-2', externalid: 'repo-2', zoneid: 'zone-a', provider: 'ablestack-nas' }
  ])
  ctx.form.externalid = 'repo-1'
  await ctx.fetchExternal('zone-a', 'ablestack-nas')
  expect(ctx.externals.opts).toEqual([])
  expect(ctx.form.externalid).toBeFalsy()
  expect(ctx.externalOptionsLoaded).toBe(true)
})

test.each([
  ['ablestack-netbackup', 'netbackup', 'NetBackup'],
  ['ablestack-veeam', 'veeam', 'ABLESTACK Veeam'],
  ['ablestack-commvault', 'plan-1', 'Commvault']
])('a late %s registration blocks submission with its own message', async (provider, externalid, label) => {
  const ctx = context(ImportOffering, provider)
  mockLists([externalid])
  await ctx.fetchExternal('zone-a', provider)
  ctx.form.externalid = externalid
  mockLists([externalid], [{ id: 'existing', externalid, provider, zoneid: 'zone-a' }])
  await ctx.handleSubmit()
  expect(postAPI).not.toHaveBeenCalled()
  const message = ctx.$notification.error.mock.calls[0][0].description
  expect(message).toContain(label)
  if (provider !== 'ablestack-commvault') expect(message).not.toContain('Commvault')
  expect(ctx.externals.opts).toEqual([])
  expect(ctx.loading).toBe(false)
})

test.each([ImportOffering, CloneOffering])('$name rechecks used IDs before submitting', async component => {
  const ctx = context(component)
  mockLists(['new-id'])
  await ctx.fetchExternal('zone-a', 'ablestack-nas')
  ctx.form.externalid = 'new-id'
  mockLists(['new-id'], [{ id: 'new-registration', externalid: 'new-id', zoneid: 'zone-a' }])
  await ctx.handleSubmit()
  expect(postAPI).not.toHaveBeenCalled()
  expect(ctx.$notification.error).toHaveBeenCalledWith(expect.objectContaining({ description: ko['message.backup.offering.externalid.unavailable'] }))
})

test.each([ImportOffering, CloneOffering])('$name blocks submission if registration lookup fails', async component => {
  const ctx = context(component)
  mockLists(['repo-1'])
  await ctx.fetchExternal('zone-a', 'ablestack-nas')
  ctx.form.externalid = 'repo-1'
  getAPI.mockRejectedValue(new Error('offline'))
  await ctx.handleSubmit()
  expect(postAPI).not.toHaveBeenCalled()
  expect(ctx.$notifyError).toHaveBeenCalledTimes(1)
  expect(ctx.externalLoadFailed).toBe(true)
  expect(ctx.externalOptionsLoaded).toBe(false)
  expect(ctx.loading).toBe(false)
})

test.each([ImportOffering, CloneOffering])('$name ignores a stale external response after changing Zone', async component => {
  const ctx = context(component)
  let resolveOld
  getAPI.mockImplementation(command => command === 'listBackupOfferings'
    ? Promise.resolve(inventory([])) : new Promise(resolve => { resolveOld = resolve }))
  const old = ctx.fetchExternal('zone-a', 'ablestack-nas')
  ctx.selectedZoneId = 'zone-b'
  mockLists(['new-zone-id'])
  await ctx.fetchExternal('zone-b', 'ablestack-nas')
  resolveOld(external(['old-zone-id']))
  await old
  expect(ctx.externals.opts.map(option => option.externalid)).toEqual(['new-zone-id'])
  expect(ctx.externals.loading).toBe(false)
})

test('a stale provider response cannot reset a newly selected provider', async () => {
  const ctx = context(ImportOffering)
  let resolveOld
  getAPI.mockImplementation(() => new Promise(resolve => { resolveOld = resolve }))
  const old = ctx.fetchProvider('zone-a')
  ctx.selectedZoneId = 'zone-b'
  getAPI.mockResolvedValue({ listbackupprovidersforzoneresponse: { providers: [{ name: 'new-provider' }] } })
  await ctx.fetchProvider('zone-b')
  resolveOld({ listbackupprovidersforzoneresponse: { providers: [{ name: 'old-provider' }] } })
  await old
  expect(ctx.providers.opts).toEqual([{ name: 'new-provider' }])
})

test.each(['ablestack-netbackup', 'ablestack-veeam'])('%s imports its external ID rather than the response object ID and fixes user-driven backups to false', async provider => {
  const externalid = provider === 'ablestack-netbackup' ? 'netbackup' : 'veeam'
  const ctx = context(ImportOffering, provider)
  mockLists([externalid])
  await ctx.fetchExternal('zone-a', provider)
  ctx.form.externalid = externalid
  postAPI.mockResolvedValue({ importbackupofferingresponse: { jobid: 'job' } })
  await ctx.handleSubmit()
  expect(postAPI).toHaveBeenCalledWith('importBackupOffering', expect.objectContaining({ provider, zoneid: 'zone-a', externalid, allowuserdrivenbackups: false }))
})

test('NetBackup can be cloned to an unregistered destination Zone with the same fixed ID', async () => {
  const ctx = context(CloneOffering, 'ablestack-netbackup', { selectedZoneId: 'zone-b' })
  ctx.zones.opts = [{ name: 'Zone B', id: 'zone-b' }]
  ctx.form.zoneid = 'Zone B'
  mockLists(['netbackup'])
  await ctx.fetchExternal('zone-b', 'ablestack-netbackup')
  ctx.form.externalid = 'netbackup'
  postAPI.mockResolvedValue({ clonebackupofferingresponse: { jobid: 'job' } })
  await ctx.handleSubmit()
  expect(postAPI).toHaveBeenCalledWith('cloneBackupOffering', expect.objectContaining({ sourceofferingid: 'source', zoneid: 'zone-b', externalid: 'netbackup', allowuserdrivenbackups: false }))
})

test('the NetBackup clone action is visible while Commvault cloning stays unavailable', () => {
  const action = offerings.children.find(section => section.name === 'backupoffering').actions.find(action => action.api === 'cloneBackupOffering')
  expect(action.show({ provider: 'ablestack-netbackup' })).toBe(true)
  expect(action.show({ provider: 'ablestack-nas' })).toBe(true)
  expect(action.show({ provider: 'ablestack-commvault' })).toBe(false)
})

test.each(['ablestack-veeam', 'ablestack-netbackup', 'ablestack-commvault', 'veeam', 'ablestack-nas'])('edit visibility of user-driven backups matches %s without conflating native Veeam', provider => {
  const wrapper = mount(UpdateOffering, {
    props: { resource: { id: 'offering', provider, name: 'Offering', description: 'Description', allowuserdrivenbackups: false } },
    global: { mocks: { $t: key => key, $getApiParams: () => new Proxy({}, { get: () => ({ description: '' }) }) }, stubs: { TooltipLabel: true } }
  })
  expect(wrapper.find('button[role="switch"]').exists()).toBe(['veeam', 'ablestack-nas'].includes(provider))
  wrapper.unmount()
})

test('every new message has English and Korean text and Commvault messages stay provider-specific', () => {
  const keys = Object.keys(en).filter(key => key.startsWith('message.backup.offering.externalid.') || key.startsWith('message.backup.offering.exists.'))
  for (const key of keys) {
    expect(en[key]).toBeTruthy()
    expect(ko[key]).toBeTruthy()
  }
  expect(ko['message.error.import.backup.offering']).toContain('Commvault')
  expect(context(ImportOffering, 'ablestack-nas').existingOfferingMessage).not.toContain('Commvault')
})
