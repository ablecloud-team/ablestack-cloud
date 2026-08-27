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

import ResourceContextMenu from '@/components/view/ResourceContextMenu'

describe('Components > View > ResourceContextMenu.vue', () => {
  describe('handleWindowScroll()', () => {
    it('keeps the menu open while its own scroll container scrolls', () => {
      const close = jest.fn()
      const menu = { contains: jest.fn(() => true) }
      const vm = { $refs: { menu }, close }
      const event = { target: document.createElement('div') }

      ResourceContextMenu.methods.handleWindowScroll.call(vm, event)

      expect(menu.contains).toHaveBeenCalledWith(event.target)
      expect(close).not.toHaveBeenCalled()
    })

    it('closes the menu when an ancestor scrolls', () => {
      const close = jest.fn()
      const menu = { contains: jest.fn(() => false) }
      const vm = { $refs: { menu }, close }
      const event = { target: document.createElement('div') }

      ResourceContextMenu.methods.handleWindowScroll.call(vm, event)

      expect(menu.contains).toHaveBeenCalledWith(event.target)
      expect(close).toHaveBeenCalledTimes(1)
    })
  })
})
