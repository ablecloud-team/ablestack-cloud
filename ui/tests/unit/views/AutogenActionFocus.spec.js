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
import AutogenView from '@/views/AutogenView'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))

afterEach(() => { document.body.innerHTML = '' })

function view () {
  return { currentAction: {}, showAction: false, actionTrigger: null, $nextTick: callback => Promise.resolve().then(callback) }
}

test('keyboard trigger regains focus after its action modal is unmounted', async () => {
  const trigger = document.createElement('button')
  const input = document.createElement('input')
  document.body.append(trigger, input)
  trigger.focus()
  const context = view()
  AutogenView.methods.execAction.call(context, { api: 'createVMSnapshot', snapshotMode: 'create', resource: { id: 'vm' } }, false)
  expect(context.actionTrigger).toBe(trigger)
  input.focus()
  input.remove()
  AutogenView.methods.closeAction.call(context)
  await Promise.resolve()
  expect(document.activeElement).toBe(trigger)
})

test('removed triggers do not steal focus from another page control', async () => {
  const removed = document.createElement('button')
  const current = document.createElement('button')
  document.body.appendChild(current)
  current.focus()
  const context = { ...view(), actionTrigger: removed }
  AutogenView.methods.closeAction.call(context)
  await Promise.resolve()
  expect(document.activeElement).toBe(current)
})

test('a newly opened modal retains focus when previous restoration is queued', async () => {
  const trigger = document.createElement('button')
  const nextInput = document.createElement('input')
  document.body.append(trigger, nextInput)
  const context = { ...view(), actionTrigger: trigger }
  AutogenView.methods.closeAction.call(context)
  context.showAction = true
  nextInput.focus()
  await Promise.resolve()
  expect(document.activeElement).toBe(nextInput)
})

test('detail menu actions return to the persistent actions button', () => {
  const trigger = document.createElement('button')
  const context = { ...view(), $refs: { detailActionsTrigger: { $el: trigger } }, execAction: jest.fn() }
  const action = { api: 'scaleKubernetesCluster' }
  AutogenView.methods.handleDataViewAction.call(context, action)
  expect(context.execAction).toHaveBeenCalledWith(action, false)
  expect(context.actionTrigger).toBe(trigger)
})
