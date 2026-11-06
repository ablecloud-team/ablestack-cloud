// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { TextEncoder } from 'util'
import { shallowMount } from '@vue/test-utils'
import Fields from '@/components/storage/FileSharePathFields'
import Panel from '@/components/storage/StorageConfigurationPreflightPanel'
import SharedFS from '@/views/storage/SharedFSTab'
import { configurationModalIntent } from '@/utils/storageConfigPreflight'
import { fileSharePathParameters, validRelativePath, childShares } from '@/utils/fileSharePaths'
import { smbSimpleOptionPatch } from '@/utils/smbCreationPolicy'
global.TextEncoder = TextEncoder
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
describe('nested file share paths', () => {
  const form = () => ({ name: 'team', path: '/export/team', volumeid: 'data', volumemode: 'CURRENT', nestedpath: true, createdirectory: false, relativepath: 'projects/team' })
  test.each(['nfsExport', 'smbShare'])('same %s preflight and submission path, explicit creation only', kind => {
    const draft = form()
    const request = fileSharePathParameters(draft)
    const intent = configurationModalIntent(kind, { [kind]: draft }, {})
    expect(request).toEqual({ relativepath: 'projects/team', createdirectory: false })
    expect(intent).toMatchObject(request)
    expect(fileSharePathParameters({ ...draft, volumemode: 'NEW' })).toEqual({})
    expect(fileSharePathParameters({ ...draft, volumemode: 'EXISTING' })).toEqual({})
  })
  test('existing bound edit omits unchanged path and preserves simple SMB option reload', () => {
    const baseline = { ...form(), nestedpathbaseline: true, relativepathbaseline: 'projects/team', readonly: false }
    const current = { ...baseline, readonly: true }
    expect(fileSharePathParameters(current, true)).toEqual({})
    expect(smbSimpleOptionPatch(baseline, current)).toEqual({ readonly: true })
    expect(fileSharePathParameters({ ...current, relativepath: 'projects/other' }, true)).toEqual({ relativepath: 'projects/other', createdirectory: false })
  })
  test('invalid paths are rejected; siblings are not descendants', () => {
    for (const path of ['', '/root', 'a/../b', 'a//b', 'a\\b', 'a/', 'x'.repeat(256)]) expect(validRelativePath(path)).toBe(false)
    const row = { id: '1', volumeid: 'data', config: JSON.stringify({ relativeSharePath: 'projects' }) }
    const rows = [{ id: '2', volumeid: 'data', config: JSON.stringify({ relativeSharePath: 'projects2' }) }, { id: '3', volumeid: 'data', config: JSON.stringify({ relativeSharePath: 'projects/team' }) }]
    expect(childShares(row, rows).map(r => r.id)).toEqual(['3'])
  })
  test('actual component shows physical path and public alias separately with a guarded input', () => {
    const wrapper = shallowMount(Fields, { props: { form: form(), root: '/srv/volumes/data', guarded: true }, global: { mocks: { $t: x => x }, stubs: { 'a-form-item': { template: '<div><slot /></div>' }, 'a-descriptions': { template: '<div><slot /></div>' }, 'a-descriptions-item': { template: '<div><slot /></div>' } } } })
    expect(wrapper.text()).toContain('/srv/volumes/data/projects/team')
    expect(wrapper.text()).toContain('/export/team')
    expect(wrapper.find('a-input-stub').attributes('disabled')).toBe('true')
    wrapper.unmount()
  })
  test('actual preflight component retains relations and parent access warning', () => {
    const report = { status: 'READY', checks: [], pathpreview: { physicalpath: '/srv/volumes/data/projects', parentaccesswarning: true, resources: [] } }
    const wrapper = shallowMount(Panel, { props: { report }, global: { mocks: { $t: x => x } } })
    expect(wrapper.find('.path-preview').exists()).toBe(true)
    expect(wrapper.findAll('a-alert-stub').some(x => x.attributes('message') === 'message.storage.path.parent.access')).toBe(true)
    wrapper.unmount()
  })
  test('existing target omits POSIX fields even if recommendation was selected', () => {
    const vm = { forms: { nfsExport: { ...form(), owneruid: 65534, mode: '0775' } }, configurationPreflight: { pathpreview: { exists: true } }, nfsPosixEdit: true }
    expect(SharedFS.computed.nfsPosixParameters.call(vm)).toEqual({})
  })
})
