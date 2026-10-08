// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
import ListView from '@/components/view/ListView'

describe('Kubernetes shared row keyboard context menu', () => {
  test.each(['vmsnapshot', 'kubernetes', 'kubernetesiso'])('%s exposes focusable rows and opens the shared menu with Shift+F10', name => {
    const context = { $route: { name }, handleGlobalContextMenu: jest.fn() }
    const events = ListView.methods.snapshotRowEvents.call(context, { id: 'row' })
    const row = document.createElement('tr')
    row.setAttribute('data-row-key', 'row')
    const event = { key: 'F10', shiftKey: true, currentTarget: row, preventDefault: jest.fn(), stopPropagation: jest.fn() }
    expect(events.tabindex).toBe(0)
    events.onKeydown(event)
    const invocation = context.handleGlobalContextMenu.mock.calls[0][0]
    expect(invocation.target).toBe(row)
    invocation.preventDefault()
    invocation.stopPropagation()
    expect(event.preventDefault).toHaveBeenCalledTimes(1)
    expect(event.stopPropagation).toHaveBeenCalledTimes(1)
  })

  test.each(['kubernetes', 'kubernetesiso'])('%s also supports the ContextMenu key without intercepting plain F10', name => {
    const context = { $route: { name }, handleGlobalContextMenu: jest.fn() }
    const events = ListView.methods.snapshotRowEvents.call(context, { id: 'row' })
    const row = document.createElement('tr')
    events.onKeydown({ key: 'F10', shiftKey: false, currentTarget: row })
    expect(context.handleGlobalContextMenu).not.toHaveBeenCalled()
    events.onKeydown({ key: 'ContextMenu', currentTarget: row })
    expect(context.handleGlobalContextMenu).toHaveBeenCalledTimes(1)
    expect(context.handleGlobalContextMenu.mock.calls[0][0].target).toBe(row)
  })

  test('unrelated resource rows retain their existing keyboard contract', () => {
    expect(ListView.methods.snapshotRowEvents.call({ $route: { name: 'volume' } }, {})).toEqual({})
  })
})
