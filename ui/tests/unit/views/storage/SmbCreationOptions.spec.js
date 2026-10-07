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

import Widget from '@/views/storage/SmbCreationOptions'

describe('SMB creation permission options', () => {
  it('selecting executable preset clears confirmation and emits a patch without mutating props', () => {
    const form = { createmask: '0660', forcecreatemode: '0000', directorymask: '0770', forcedirectorymode: '0000', confirmfileexecute: true }
    const vm = { form, fields: ['createmask', 'forcecreatemode', 'directorymask', 'forcedirectorymode'], patch: jest.fn() }
    Widget.methods.applyPreset.call(vm, 'EXACT_0775')
    expect(vm.patch).toHaveBeenCalledWith({ createmask: '0775', forcecreatemode: '0775', directorymask: '0775', forcedirectorymode: '0775', inheritpermissions: false, confirmfileexecute: false })
    expect(form.createmask).toBe('0660')
  })
  it('parent group inheritance adds setgid through a parent patch without changing owner', () => {
    const vm = { form: { directorymode: '0775', posixpolicyid: undefined }, patch: jest.fn() }
    Widget.methods.setParentGroup.call(vm, true)
    expect(vm.patch).toHaveBeenCalledWith({ inheritgroup: true, directorymode: '2775' })
    expect(vm.form.directorymode).toBe('0775')
  })
  it('common directories require changing setgid in their common policy', () => {
    const vm = { form: { directorymode: '0775', posixpolicyid: 'common' }, patch: jest.fn() }
    Widget.methods.setParentGroup.call(vm, true)
    expect(vm.patch).toHaveBeenCalledWith({ inheritgroup: true })
  })
  it('only forced file execute bits trigger warning', () => {
    expect(Widget.computed.hasExecute.call({ form: { forcecreatemode: '0775' } })).toBe(true)
    expect(Widget.computed.hasExecute.call({ form: { forcecreatemode: '0660' } })).toBe(false)
    expect(Widget.computed.hasExecute.call({ form: { forcecreatemode: 'invalid' } })).toBe(false)
  })
})
