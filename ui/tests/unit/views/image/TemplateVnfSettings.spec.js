// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for ownership information.
// Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
// Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { shallowMount, flushPromises } from '@vue/test-utils'
import { Alert, Button } from 'ant-design-vue'
import Settings from '@/views/image/TemplateVnfSettings.vue'
import { getAPI, postAPI } from '@/api'
import { vnfWriteAllowed, vnfDetailError, isVnfSecret, vnfNicError } from '@/utils/templateVnfSettings'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const original = { deviceid: 0, name: 'Management', required: true, management: true }
const optional = { deviceid: 1, name: 'Data', required: false, management: false, description: 'Test' }
const reply = nics => ({ updatetemplateresponse: { template: { vnfnics: nics } } })
function context () {
  const ctx = {
    ...Settings.data(),
    resource: { templatetype: 'VNF', id: 'template', name: 'Template', projectid: 'p', project: 'Project', vnfnics: [{ ...original }] },
    $store: { getters: { project: { id: 'p' }, userInfo: { id: 'admin', roletype: 'Admin' }, apis: { updateVnfTemplate: {}, listDetailOptions: {} } }, state: { user: { token: 'login' } } },
    $route: { fullPath: '/template/template?tab=vnfsettings' },
    $t: key => key,
    $notification: { error: jest.fn() },
    $notifyError: jest.fn(),
    $emit: jest.fn(),
    vnfNics: [{ ...original }],
    previousVnfNics: [{ ...original }]
  }
  for (const [k, method] of Object.entries(Settings.methods)) ctx[k] = method.bind(ctx)
  for (const [key, value] of Object.entries(Settings.computed)) Object.defineProperty(ctx, key, { get: () => value.call(ctx) })
  return ctx
}
beforeEach(() => { jest.clearAllMocks(); getAPI.mockResolvedValue({ listdetailoptionsresponse: { detailoptions: { details: { version: [], password: [], ssh_password: [], web_password: [], ssh_port: [], access_methods: ['ssh-password', 'https'] } } } }) })

test('add and edit share exactly one dialog instance and never open simultaneously', async () => {
  const w = shallowMount(Settings, {
    props: { resource: context().resource },
    global: {
      mocks: { $getApiParams: () => ({ vnfnics: {}, vnfdetails: { version: [], password: [], ssh_password: [], web_password: [], ssh_port: [], access_methods: ['ssh-password', 'https'] } }), $store: context().$store, $t: k => k },
      directives: { focus: {} },
      stubs: { MoldDialog: { props: ['visible'], template: '<section v-if="visible" role="dialog"><slot /><slot name="footer" /></section>' } }
    }
  })
  w.vm.onShowAddVnfNic(); w.vm.onShowEditVnfNic(optional)
  await w.vm.$nextTick()
  expect(w.findAll('[role="dialog"]')).toHaveLength(1)
  expect(w.vm.showEditVnfNic).toBe(false)
  w.vm.closeVnfNicModal(); w.vm.afterVnfNicClose(); await w.vm.$nextTick()
  expect(w.findAll('[role="dialog"]')).toHaveLength(0)
  w.unmount()
})

test('double submit sends one write and never mutates the displayed baseline while pending', async () => {
  const ctx = context(); let done
  postAPI.mockImplementation(() => new Promise(resolve => { done = resolve }))
  ctx.onShowAddVnfNic(); ctx.nicDraft = { ...optional }
  const pending = ctx.submitVnfNic(); ctx.submitVnfNic()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(ctx.updateBusy).toBe(true)
  expect(ctx.vnfNics).toEqual([original])
  expect(postAPI.mock.calls[0][1]['vnfnics[1].name']).toBe('Data')
  ctx.closeVnfNicModal(); expect(ctx.showAddVnfNic).toBe(true)
  done(reply([original, optional])); await pending
  expect(ctx.vnfNics).toEqual([original, optional]); expect(ctx.showAddVnfNic).toBe(false); expect(ctx.updateBusy).toBe(false)
})

test('failed save keeps the draft and existing definitions; retry adds the draft once', async () => {
  const ctx = context(); ctx.onShowAddVnfNic(); ctx.nicDraft = { ...optional }
  postAPI.mockRejectedValueOnce(new Error('503')); await ctx.submitVnfNic()
  expect(ctx.vnfNics).toEqual([original]); expect(ctx.nicDraft).toEqual(optional)
  expect(ctx.showAddVnfNic).toBe(true); expect(ctx.nicError).toBeTruthy()
  postAPI.mockResolvedValueOnce(reply([original, optional])); await ctx.submitVnfNic()
  expect(postAPI).toHaveBeenCalledTimes(2); expect(ctx.vnfNics).toHaveLength(2)
})

test.each([null, '', -1, 1.2, 'not-a-number', 0])('rejects invalid or duplicate device ID %s without a write', id => {
  const ctx = context(); ctx.onShowAddVnfNic(); ctx.nicDraft = { ...optional, deviceid: id }
  ctx.submitVnfNic(); expect(postAPI).not.toHaveBeenCalled(); expect(ctx.vnfNics).toEqual([original])
})

test('device zero is valid for an empty template and whitespace name is rejected', async () => {
  const ctx = context(); ctx.vnfNics = []; ctx.onShowAddVnfNic(); ctx.nicDraft = { ...original, name: '  ' }
  ctx.submitVnfNic(); expect(postAPI).not.toHaveBeenCalled()
  ctx.nicDraft.name = 'Management'; postAPI.mockResolvedValueOnce(reply([original])); await ctx.submitVnfNic()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(ctx.vnfNics).toEqual([original])
})

test('cancel/reopen resets values and returns focus once to the originating control', () => {
  const ctx = context(); const trigger = { isConnected: true, focus: jest.fn() }
  ctx.onShowAddVnfNic({ currentTarget: trigger }); ctx.nicDraft = { ...optional }
  ctx.closeVnfNicModal(); ctx.afterVnfNicClose(); ctx.afterVnfNicClose(); ctx.onShowAddVnfNic()
  expect(trigger.focus).toHaveBeenCalledTimes(1); expect(ctx.nicDraft.deviceid).toBe(1); expect(ctx.nicDraft.name).toBe('')
})

test.each(['project', 'resource', 'login', 'route', 'unmount'])('late save response cannot overwrite a new %s context', async kind => {
  const ctx = context(); let done
  postAPI.mockImplementation(() => new Promise(resolve => { done = resolve }))
  ctx.onShowAddVnfNic(); ctx.nicDraft = { ...optional }; const pending = ctx.submitVnfNic()
  if (kind === 'project') ctx.$store.getters.project.id = 'other'
  if (kind === 'resource') ctx.resource.id = 'other'
  if (kind === 'login') ctx.$store.state.user.token = 'other'
  if (kind === 'route') ctx.$route.fullPath = '/dashboard'
  if (kind === 'unmount') Settings.beforeUnmount.call(ctx)
  else Settings.watch.nicScope.call(ctx)
  done(reply([original, optional])); await pending
  expect(ctx.vnfNics).toEqual([original]); expect(ctx.showAddVnfNic).toBe(false)
  expect(ctx.nicTrigger).toBeNull()
})

test('edit is a detached draft; failed edit cannot alter an existing definition', async () => {
  const ctx = context(); ctx.onShowEditVnfNic(original); ctx.nicDraft.name = 'changed'
  expect(ctx.vnfNics).toEqual([original]); postAPI.mockRejectedValueOnce(new Error('503')); await ctx.submitVnfNic()
  expect(ctx.vnfNics).toEqual([original]); expect(ctx.nicDraft.name).toBe('changed')
})

test('reordering then deleting an optional definition preserves the management definition', async () => {
  const ctx = context(); const spare = { ...optional, deviceid: 2, name: 'Spare' }; ctx.vnfNics = [original, optional, spare]
  postAPI.mockImplementation((api, p) => Promise.resolve(reply([0, 1, 2].map(i => ({ deviceid: p['vnfnics[' + i + '].deviceid'], name: p['vnfnics[' + i + '].name'], required: p['vnfnics[' + i + '].required'], management: p['vnfnics[' + i + '].management'], description: p['vnfnics[' + i + '].description'] })).filter(n => n.deviceid !== undefined))))
  await ctx.moveVnfNicUp(spare); expect(ctx.vnfNics.find(n => n.name === 'Spare').deviceid).toBe(1)
  await ctx.moveVnfNicDown(ctx.vnfNics.find(n => n.name === 'Spare'))
  await ctx.deleteVnfNic(ctx.vnfNics.find(n => n.name === 'Spare'))
  expect(ctx.vnfNics).toEqual([original, optional]); expect(postAPI).toHaveBeenCalledTimes(3)
})

test('unavailable API or non-owner cannot write', () => {
  const ctx = context(); ctx.onShowAddVnfNic(); ctx.nicDraft = optional
  ctx.$store.getters.apis = {}; ctx.submitVnfNic(); expect(postAPI).not.toHaveBeenCalled()
  ctx.$store.getters.apis = { updateVnfTemplate: {} }; ctx.$store.getters.userInfo = { roletype: 'User', account: 'outside', domainid: 'outside' }; ctx.$store.getters.project = {}; ctx.submitVnfNic()
  expect(postAPI).not.toHaveBeenCalled()
})

function detailContext (entries = [['version', '1.0']]) {
  const ctx = context(); ctx.resource.vnfdetails = Object.fromEntries(entries); ctx.vnfDetails = entries.map(([name, value]) => ({ name, value })); ctx.previousVnfDetails = ctx.vnfDetails.map(d => ({ ...d })); ctx.optionsReady = true; ctx.vnfDetailOptions = { version: [], password: [], ssh_password: [], web_password: [], ssh_port: [], access_methods: ['ssh-password', 'https'] }; return ctx
}
const detailReply = (ctx, details) => ({ updatetemplateresponse: { template: { id: 'template', vnfnics: ctx.vnfNics, vnfdetails: details } } })
test('unsupported or duplicate keys cannot submit and uppercase supported keys normalize', async () => {
  const c = detailContext(); c.onShowAddVnfDetail(); c.detailDraft = { key: 'qaAudit', value: 'bad', values: [] }; await c.saveVnfDetail(); expect(postAPI).not.toHaveBeenCalled(); expect(c.detailError).toBeTruthy()
  c.detailDraft.key = 'VERSION'; await c.saveVnfDetail(); expect(postAPI).not.toHaveBeenCalled(); expect(c.vnfDetails[0].value).toBe('1.0')
})
test('failed detail edit preserves baseline and draft; retry commits once and requests parent refresh', async () => {
  const c = detailContext(); c.showEditVnfDetail('version'); c.detailDraft.value = '2.0'; postAPI.mockRejectedValueOnce({ response: { status: 431 }, message: 'private content' }); await c.saveVnfDetail()
  expect(c.vnfDetails).toEqual([{ name: 'version', value: '1.0' }]); expect(c.detailDraft.value).toBe('2.0'); expect(c.showEditDetail).toBe(true); expect(c.detailError).not.toContain('private content'); expect(c.$notifyError).not.toHaveBeenCalled()
  postAPI.mockResolvedValueOnce(detailReply(c, { version: '2.0' })); await c.saveVnfDetail(); expect(c.vnfDetails[0].value).toBe('2.0'); expect(c.showEditDetail).toBe(false); expect(c.$emit).toHaveBeenCalledWith('refresh')
})
test('failed add/delete cannot mutate existing details; last delete uses explicit cleanup', async () => {
  const c = detailContext([]); c.onShowAddVnfDetail(); c.detailDraft = { key: 'version', value: '1.0', values: [] }; postAPI.mockRejectedValueOnce(new Error('431')); await c.saveVnfDetail(); expect(c.vnfDetails).toEqual([]); expect(c.detailDraft.value).toBe('1.0')
  postAPI.mockResolvedValueOnce(detailReply(c, { version: '1.0' })); await c.saveVnfDetail(); postAPI.mockRejectedValueOnce(new Error('431')); await c.deleteVnfDetail('version'); expect(c.vnfDetails).toEqual([{ name: 'version', value: '1.0' }]); expect(postAPI.mock.calls[2][1]).toEqual({ id: 'template', cleanupvnfdetails: true })
})
test.each(['PASSWORD', 'SSH_PASSWORD', 'WEB_PASSWORD', 'password'])('secret %s is masked, edit starts empty, unchanged never writes, other edits preserve it', async name => {
  const c = detailContext([[name.toLowerCase(), 'unit-test-value'], ['version', '1.0']]); expect(isVnfSecret(name)).toBe(true); expect(c.getDisplayValue(name, 'unit-test-value')).toBe('********'); c.showEditVnfDetail(name.toLowerCase()); expect(c.detailDraft.value).toBe(''); await c.saveVnfDetail(); expect(postAPI).not.toHaveBeenCalled()
  c.showEditVnfDetail('version'); c.detailDraft.value = '2.0'; postAPI.mockResolvedValueOnce(detailReply(c, { [name.toLowerCase()]: 'unit-test-value', version: '2.0' })); await c.saveVnfDetail(); expect(postAPI.mock.calls[0][1]['vnfdetails[0].' + name.toLowerCase()]).toBe('unit-test-value')
})
test('explicit secret replacement rejects empty value and only sends replacement after opting in', async () => {
  const c = detailContext([['password', 'old-test-value']]); c.showEditVnfDetail('password'); c.detailDraft.replaceSecret = true; await c.saveVnfDetail(); expect(postAPI).not.toHaveBeenCalled(); expect(c.detailError).toBeTruthy(); c.detailDraft.value = 'new-test-value'; postAPI.mockResolvedValueOnce(detailReply(c, { password: 'new-test-value' })); await c.saveVnfDetail(); expect(postAPI.mock.calls[0][1]['vnfdetails[0].password']).toBe('new-test-value'); expect(c.detailDraft.value).toBe('')
})
test.each(['0', '-1', '65536', '1.5', 'abc', ''])('invalid port %s cannot submit', value => { expect(vnfDetailError('SSH_PORT', value, { ssh_port: [] })).toBeTruthy() })
test('valid access methods and port normalize against discovered keys', () => { expect(vnfDetailError('SSH_PORT', '22', { ssh_port: [] })).toBe(''); expect(vnfDetailError('ACCESS_METHODS', 'SSH-PASSWORD,https', { access_methods: ['ssh-password', 'https'] })).toBe(''); expect(vnfDetailError('version', '2', { vendor: [] })).toBeTruthy() })
test('NIC nonconsecutive, required ordering and boundary moves cannot mutate; last removal cleans up', async () => {
  const c = context(); c.vnfNics = [original, optional]; expect(vnfNicError([original, { ...optional, deviceid: 2 }])).toBeTruthy(); expect(vnfNicError([{ ...original, required: false }, { ...optional, required: true }])).toBeTruthy(); await c.moveVnfNicUp(original); await c.moveVnfNicDown(optional); expect(postAPI).not.toHaveBeenCalled(); c.vnfNics = [{ ...original }]; postAPI.mockResolvedValueOnce(reply([])); await c.deleteVnfNic(original); expect(postAPI.mock.calls[0][1]).toEqual({ id: 'template', cleanupvnfnics: true })
})
test('removing a middle NIC renumbers following definitions and retains flags', async () => { const c = context(); c.vnfNics = [original, optional, { ...optional, deviceid: 2, name: 'Third' }]; postAPI.mockResolvedValueOnce(reply([original, { ...optional, deviceid: 1, name: 'Third' }])); await c.deleteVnfNic(optional); expect(postAPI.mock.calls[0][1]['vnfnics[1].deviceid']).toBe(1); expect(postAPI.mock.calls[0][1]['vnfnics[1].name']).toBe('Third') })
test('permission matrix distinguishes owner, read-only DomainAdmin, project and outside scope', () => {
  const resource = { id: 't', templatetype: 'VNF', account: 'owner', domainid: 'child', domainpath: 'ROOT/Parent/Child/' }; const apis = { updateVnfTemplate: {} }; const user = { id: 'u', roletype: 'User', account: 'owner', domainid: 'child' }
  expect(vnfWriteAllowed(resource, user, {}, apis)).toBe(true); expect(vnfWriteAllowed(resource, { ...user, account: 'other' }, {}, apis)).toBe(false)
  expect(vnfWriteAllowed(resource, { ...user, roletype: 'DomainAdmin', account: 'admin', domainid: 'parent', domainpath: 'ROOT/Parent/' }, {}, apis, { parent: 'ROOT/Parent/', child: 'ROOT/Parent/Child/' })).toBe(false)
  expect(vnfWriteAllowed(resource, { ...user, roletype: 'DomainAdmin', domainid: 'outside', domainpath: 'ROOT/Else/' }, {}, apis)).toBe(false)
  expect(vnfWriteAllowed({ ...resource, projectid: 'p' }, user, { id: 'p' }, apis)).toBe(true); expect(vnfWriteAllowed({ ...resource, projectid: 'p' }, user, {}, apis)).toBe(false)
  expect(vnfWriteAllowed(resource, user, { id: 'p' }, apis)).toBe(false); expect(vnfWriteAllowed(resource, user, {}, {})).toBe(false)
})
test('late detail option discovery and write response cannot leak across owner or permission scope', async () => {
  const c = detailContext(); let done; getAPI.mockImplementationOnce(() => new Promise(resolve => { done = resolve })); const pending = c.loadOptions(); c.resource.account = 'other'; Settings.watch.nicScope.call(c); done({ listdetailoptionsresponse: { detailoptions: { details: { version: [] } } } }); await pending; expect(c.showEditDetail).toBe(false)
  c.optionsReady = true; c.showEditVnfDetail('version'); c.detailDraft.value = '2'; let saved; postAPI.mockImplementationOnce(() => new Promise(resolve => { saved = resolve })); const write = c.saveVnfDetail(); c.$store.getters.apis = {}; Settings.watch.nicScope.call(c); saved(detailReply(c, { version: '2' })); await write; expect(c.vnfDetails[0].value).toBe('1.0'); expect(c.detailDraft.value).toBe('')
})

test('moving cannot silently exchange required flags across the required/optional boundary', async () => {
  const c = context(); c.vnfNics = [{ ...original }, { ...optional }]
  expect(c.canMove(original, 1)).toBe(false); expect(c.canMove(optional, -1)).toBe(false)
  await c.moveVnfNicDown(original); await c.moveVnfNicUp(optional)
  expect(postAPI).not.toHaveBeenCalled(); expect(c.vnfNics).toEqual([original, optional])
})

test('cancelling an invalid NIC draft does not leave a resource-level error', async () => {
  const c = context(); c.vnfNics = [original, optional, { ...optional, deviceid: 2, name: 'Third' }]
  c.onShowEditVnfNic(c.vnfNics[2]); c.nicDraft.required = true; await c.submitVnfNic()
  expect(c.nicError).toBe('message.vnf.nic.required.order'); expect(c.saveError).toBe(''); expect(postAPI).not.toHaveBeenCalled()
  c.closeVnfNicModal(); c.afterVnfNicClose(); expect(c.nicError).toBe(''); expect(c.saveError).toBe('')
})

test.each(['same', 'parent', 'outside'])('DomainAdmin cannot modify a different owner in %s domain', domainid => {
  const resource = { id: 't', templatetype: 'VNF', domainid: 'same', account: 'owner' }
  const user = { id: 'u', roletype: 'DomainAdmin', domainid, account: 'manager' }
  expect(vnfWriteAllowed(resource, user, {}, { updateVnfTemplate: {} }, { parent: 'ROOT/', same: 'ROOT/A/' })).toBe(false)
  expect(vnfWriteAllowed(resource, { ...user, domainid: 'same', account: 'owner' }, {}, { updateVnfTemplate: {} })).toBe(true)
})

test.each([['optionsError', 'loadOptions']])('real Ant Design alert renders the %s retry control', async (field, method) => {
  const c = context(); const w = shallowMount(Settings, { props: { resource: c.resource }, global: { mocks: { $getApiParams: () => ({ vnfnics: {}, vnfdetails: {} }), $store: c.$store, $route: c.$route, $t: k => k }, components: { 'a-alert': Alert, 'a-button': Button }, stubs: { AAlert: false, AButton: false, Wave: false }, directives: { focus: {} } } })
  await flushPromises(); const retry = jest.spyOn(w.vm, method).mockResolvedValue(); await w.setData({ [field]: true })
  const button = w.get('.ant-alert-description button'); expect(button.text()).toBe('label.retry'); await button.trigger('click'); expect(retry).toHaveBeenCalledTimes(1); w.unmount()
})
