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

import { shallowMount } from '@vue/test-utils'
import ComputeSelection from '@/views/compute/wizard/ComputeSelection.vue'

const mountSelection = (props = {}) => shallowMount(ComputeSelection, {
  props: {
    isCustomized: true,
    minCpu: 1,
    maxCpu: 16,
    minMemory: 1024,
    maxMemory: 16384,
    ...props
  },
  global: { mocks: { $t: key => key } }
})

describe('Compute offering selection values', () => {
  test('live scaling starts with current VM CPU and memory', () => {
    const wrapper = mountSelection({ initialCpuValue: 4, initialMemoryValue: 8192 })
    expect(wrapper.emitted('update-compute-cpunumber')[0][1]).toBe(4)
    expect(wrapper.emitted('update-compute-memory')[0][1]).toBe(8192)
    wrapper.unmount()
  })

  test('a constrained offering rejects the current value if it exceeds the new maximum', () => {
    const wrapper = mountSelection({ initialCpuValue: 8, maxCpu: 4, initialMemoryValue: 8192, maxMemory: 4096 })
    expect(wrapper.emitted('update-compute-cpunumber')).toBeUndefined()
    expect(wrapper.emitted('update-compute-memory')).toBeUndefined()
    expect(wrapper.vm.errors.cpu.status).toBe('error')
    expect(wrapper.vm.errors.memory.status).toBe('error')
    wrapper.unmount()
  })

  test('deployment defaults and clone prefill stay compatible', () => {
    const defaults = mountSelection({ minCpu: 0, minMemory: 0, cpuSpeed: 0 })
    expect(defaults.emitted('update-compute-cpunumber')[0][1]).toBe(1)
    expect(defaults.emitted('update-compute-memory')[0][1]).toBe(1024)
    expect(defaults.emitted('update-compute-cpuspeed')[0][1]).toBe(1000)
    defaults.unmount()
    const clone = mountSelection({ initialCpuValue: 4, initialMemoryValue: 4096, preFillContent: { cpunumber: 6, memory: 8192, cpuspeed: 2000 } })
    expect(clone.emitted('update-compute-cpunumber')[0][1]).toBe(6)
    expect(clone.emitted('update-compute-memory')[0][1]).toBe(8192)
    expect(clone.emitted('update-compute-cpuspeed')[0][1]).toBe(2000)
    clone.unmount()
  })
})
