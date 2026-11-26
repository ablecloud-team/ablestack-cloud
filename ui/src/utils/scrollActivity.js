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

const bindings = new WeakMap()
const idleDelay = 650

function detach (root) {
  const binding = bindings.get(root)
  if (!binding) return
  const { scroller, listener, original } = binding
  clearTimeout(binding.timer)
  scroller.removeEventListener('scroll', listener)
  scroller.classList.remove('workspace-scroll-region', 'is-scrolling')
  for (const [attribute, value] of original) {
    if (value === null) scroller.removeAttribute(attribute)
    else scroller.setAttribute(attribute, value)
  }
  bindings.delete(root)
}

function update (root, { value }) {
  const scroller = value && (value.selector ? root.querySelector(value.selector) : root)
  const previous = bindings.get(root)
  if (previous?.scroller !== scroller) {
    detach(root)
    if (!scroller) return

    const original = ['tabindex', 'role', 'aria-label'].map(attribute => [attribute, scroller.getAttribute(attribute)])
    const binding = { scroller, original, timer: null, listener: null }
    binding.listener = () => {
      clearTimeout(binding.timer)
      scroller.classList.add('is-scrolling')
      binding.timer = setTimeout(() => scroller.classList.remove('is-scrolling'), idleDelay)
    }
    bindings.set(root, binding)
    scroller.classList.add('workspace-scroll-region')
    scroller.setAttribute('tabindex', '0')
    scroller.setAttribute('role', 'region')
    scroller.addEventListener('scroll', binding.listener, { passive: true })
  }

  if (value?.label && scroller) scroller.setAttribute('aria-label', value.label)
}

export default { mounted: update, updated: update, beforeUnmount: detach }
