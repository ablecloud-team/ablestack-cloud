// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import FormSchedule from '@/views/storage/FormSchedule'
import { postAPI } from '@/api'
jest.mock('@/api', () => ({ postAPI: jest.fn(), getAPI: jest.fn() }))
jest.mock('@/utils/timezone', () => ({ timeZone: () => Promise.resolve([]) }))
let wrappers; let store; let route
function render () {
  const w = mount(FormSchedule, {
    props: { resource: { id: 'fixture-volume', zonename: 'Zone' }, dataSource: [] },
    attachTo: document.body,
    global: {
      mocks: {
        $store: store,
        $route: route,
        $t: key => key,
        $getApiParams: () => [],
        $notifyError: jest.fn(),
        $notification: { success: jest.fn() }
      },
      stubs: { ResourceIcon: true }
    }
  })
  wrappers.push(w); return w
}
const editor = w => w.find('[data-testid="snapshot-policy-tags"]')
async function draft (w, key, value) {
  await editor(w).find('button').trigger('click')
  await editor(w).find('input[aria-label="label.key"]').setValue(key)
  await editor(w).find('input[aria-label="label.value"]').setValue(value)
}
async function valid (w) {
  w.vm.form.time = 15; w.vm.form.maxsnaps = 1; w.vm.form.timezone = 'Asia/Seoul'; await flushPromises()
}
beforeEach(() => {
  wrappers = []; postAPI.mockReset(); postAPI.mockResolvedValue({ createsnapshotpolicyresponse: { snapshotpolicy: { id: 'policy' } } })
  store = reactive({ getters: { userInfo: { id: 'owner', roletype: 'User' }, project: {}, apis: { createSnapshotPolicy: {}, createTags: {}, deleteTags: {} } }, state: { user: { token: 'session' } } })
  route = reactive({ path: '/snapshotpolicy' })
})
afterEach(async () => { wrappers.forEach(w => w.unmount()); await flushPromises(); document.body.innerHTML = '' })

test('actual AntD close removes the model entry and only the retained tag is submitted', async () => {
  const w = render(); await valid(w)
  await draft(w, 'remove', 'yes'); await editor(w).find('input').trigger('keydown', { key: 'Enter' })
  await draft(w, 'keep', '한글'); await editor(w).find('button[aria-label="label.ok"]').trigger('click')
  await editor(w).find('.ant-tag-close-icon').trigger('click'); await flushPromises()
  expect(w.vm.tags).toEqual([{ key: 'keep', value: '한글' }]); expect(editor(w).findAll('.ant-tag')).toHaveLength(1)
  await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0]).toEqual(['createSnapshotPolicy', expect.objectContaining({ volumeid: 'fixture-volume', 'tags[0].key': 'keep', 'tags[0].value': '한글' }), { preserveOnFailure: true }])
  expect(JSON.stringify(postAPI.mock.calls)).not.toContain('remove'); expect(w.emitted('refresh')).toHaveLength(1)
})

test('blur keeps the draft, Enter commits once, and repeating confirm never appends an empty tag', async () => {
  const w = render(); await draft(w, 'key', 'value')
  await editor(w).find('input').trigger('blur'); expect(w.vm.tags).toEqual([])
  await editor(w).find('input').trigger('keydown', { key: 'Enter' }); w.vm.handleInputConfirm()
  expect(w.vm.tags).toEqual([{ key: 'key', value: 'value' }]); expect(w.vm.inputVisible).toBe(false)
  await flushPromises(); expect(document.activeElement).toBe(w.vm.$refs.tagAdd.$el)
})

test.each([[' ', 'value'], ['key', '  ']])('empty field %j/%j blocks tag and policy creation and preserves the draft', async (key, value) => {
  const w = render(); await valid(w); await draft(w, key, value); await w.vm.handleSubmit()
  expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.inputVisible).toBe(true); expect(editor(w).find('[role="alert"]').text()).toBe('message.tags.fields.required')
})

test('a duplicate trimmed key is rejected without replacing the original value', async () => {
  const w = render(); await draft(w, 'key', 'original'); w.vm.handleInputConfirm(); await flushPromises()
  await draft(w, ' key ', 'replacement'); w.vm.handleInputConfirm(); await flushPromises()
  expect(w.vm.tags).toEqual([{ key: 'key', value: 'original' }]); expect(w.vm.inputKey).toBe(' key ')
  expect(editor(w).text()).toContain('message.snapshot.policy.tag.duplicate')
})

test.each(['button', 'Escape'])('draft cancel by %s discards values, restores focus and never submits', async how => {
  const w = render(); await draft(w, 'cancelled', 'never-save')
  if (how === 'button') await editor(w).find('button[aria-label="label.cancel"]').trigger('click')
  else await editor(w).find('input').trigger('keydown', { key: 'Escape' })
  await flushPromises(); expect(w.vm.tags).toEqual([]); expect(w.vm.inputKey).toBe(''); expect(w.vm.inputValue).toBe('')
  expect(w.vm.inputVisible).toBe(false); expect(postAPI).not.toHaveBeenCalled(); expect(document.activeElement).toBe(w.vm.$refs.tagAdd.$el)
})

test('policy confirm commits a valid open draft exactly once before the create request', async () => {
  const w = render(); await valid(w); await draft(w, 'key', ' value '); await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]).toMatchObject({ 'tags[0].key': 'key', 'tags[0].value': ' value ' })
  expect(Object.keys(postAPI.mock.calls[0][1]).filter(k => k.startsWith('tags['))).toHaveLength(2)
})

test('API failure retains schedule and confirmed tags, and retry sends the same payload', async () => {
  const w = render(); await valid(w); await draft(w, 'keep', 'value'); postAPI.mockRejectedValueOnce(new Error('431'))
  await w.vm.handleSubmit(); expect(w.vm.form.time).toBe(15); expect(w.vm.tags).toEqual([{ key: 'keep', value: 'value' }]); expect(w.vm.actionLoading).toBe(false)
  expect(w.vm.$notifyError).toHaveBeenCalledTimes(1); await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]).toEqual(postAPI.mock.calls[1][1]); expect(w.vm.tags).toEqual([])
})

test('concurrent validation only sends one request and busy tag actions do not alter its payload', async () => {
  const w = render(); await valid(w); await draft(w, 'keep', 'value'); w.vm.handleInputConfirm(); await flushPromises()
  let release; postAPI.mockReturnValue(new Promise(resolve => { release = resolve }))
  const first = w.vm.handleSubmit(); const second = w.vm.handleSubmit(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1); w.vm.handleDeleteTag(w.vm.tags[0]); w.vm.showInput(); expect(w.vm.tags).toHaveLength(1); expect(w.vm.inputVisible).toBe(false)
  release({}); await Promise.all([first, second])
})

test('createTags permits draft removal without deleteTags, and no persisted tag API is called', async () => {
  delete store.getters.apis.deleteTags
  const w = render(); await draft(w, 'key', 'value'); w.vm.handleInputConfirm(); await flushPromises()
  expect(editor(w).find('.ant-tag-close-icon').exists()).toBe(true); await editor(w).find('.ant-tag-close-icon').trigger('click')
  expect(w.vm.tags).toEqual([]); expect(postAPI).not.toHaveBeenCalled()
})

test('without createTags the editor is hidden, and removing permission clears all drafts before submit', async () => {
  const w = render(); await valid(w); await draft(w, 'private', 'value'); w.vm.handleInputConfirm(); await flushPromises()
  delete store.getters.apis.createTags; await flushPromises(); expect(editor(w).find('button').exists()).toBe(false); expect(w.vm.tags).toEqual([])
  w.vm.showInput(); expect(w.vm.inputVisible).toBe(false); await w.vm.handleSubmit()
  expect(Object.keys(postAPI.mock.calls[0][1]).some(k => k.startsWith('tags['))).toBe(false)
})

test.each(['login', 'project', 'resource', 'permission', 'unmount'])('creation cannot continue after %s changes during validation', async change => {
  const w = render(); await valid(w); let release
  w.vm.formRef.value.validate = () => new Promise(resolve => { release = resolve })
  const pending = w.vm.handleSubmit()
  if (change === 'login') store.state.user.token = 'new-session'
  if (change === 'project') store.getters.project = { id: 'other' }
  if (change === 'resource') await w.setProps({ resource: { id: 'other' } })
  if (change === 'permission') delete store.getters.apis.createSnapshotPolicy
  if (change === 'unmount') w.unmount()
  release(); await pending; expect(postAPI).not.toHaveBeenCalled()
})

test('an IME composing Enter does not commit or submit', async () => {
  const w = render(); await draft(w, '한글', '값'); await editor(w).find('input').trigger('keydown', { key: 'Enter', isComposing: true })
  expect(w.vm.tags).toEqual([]); expect(w.vm.inputVisible).toBe(true); expect(postAPI).not.toHaveBeenCalled()
})
