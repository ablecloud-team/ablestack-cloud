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

const entries = 'td, th, .ant-list-item, .ant-tree-title'
const listeners = new WeakMap()

function isEllipsized (element) {
  if (element.clientWidth === 0 || element.scrollWidth <= element.clientWidth) return false
  const style = getComputedStyle(element)
  return style.textOverflow === 'ellipsis' && ['hidden', 'clip'].includes(style.overflowX)
}

export default {
  mounted (layout) {
    let active
    let text
    let previousTitle

    const clear = () => {
      if (active && active.getAttribute('title') === text) {
        if (previousTitle == null) active.removeAttribute('title')
        else active.setAttribute('title', previousTitle)
      }
      active = undefined
    }

    const show = event => {
      if (event.pointerType === 'touch' || !(event.target instanceof Element)) return
      if (active?.contains(event.target)) return
      clear()

      const cell = event.target.closest(entries)
      if (!cell || !layout.contains(cell)) return

      for (let element = event.target; element && cell.contains(element); element = element.parentElement) {
        if (!isEllipsized(element)) continue

        // TableOverflowText owns its tooltip. Prefer any explicit tooltip text
        // and use the rendered label for other cells, including masked fields.
        if (element.matches('.table-overflow-text') ||
          element.closest('[title]:not([title=""])')) return
        text = element.innerText?.replace(/\s+/g, ' ').trim()
        if (!text) return

        previousTitle = element.getAttribute('title')
        element.setAttribute('title', text)
        active = element
        return
      }
    }

    const hide = event => {
      if (!(event.relatedTarget instanceof Node) || !active?.contains(event.relatedTarget)) clear()
    }

    layout.addEventListener('pointerover', show)
    layout.addEventListener('pointerout', hide)
    listeners.set(layout, () => {
      layout.removeEventListener('pointerover', show)
      layout.removeEventListener('pointerout', hide)
      clear()
    })
  },
  beforeUnmount (layout) {
    const dispose = listeners.get(layout)
    if (dispose) dispose()
    listeners.delete(layout)
  }
}
