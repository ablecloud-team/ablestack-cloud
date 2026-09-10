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
import DrRunProgress from '@/components/dr/DrRunProgress.vue'

const mountProgress = props => shallowMount(DrRunProgress, {
  props: {
    run: {},
    runtime: {},
    ...props
  },
  global: {
    mocks: {
      $t: (key, values) => values && values.reason ? `${key}:${values.reason}` : key,
      $te: () => false
    }
  }
})

describe('DrRunProgress transfer authority', () => {
  it('keeps a valid run sample when plan runtime is schema zero', () => {
    const wrapper = mountProgress({
      run: {
        transferprogressschemaversion: 2,
        transfercyclesequence: 8,
        transfersamplesequence: 3,
        transferbytestotal: 4096,
        transferbytesprocessed: 1024,
        transferpercent: 25
      },
      runtime: {
        transferprogressschemaversion: 0,
        transferbytestotal: 0,
        transferbytesprocessed: 0,
        transferpercent: 0
      }
    })

    expect(wrapper.vm.transferPercent).toBe(25)
    expect(wrapper.vm.transferBytesProcessed).toBe(1024)
  })

  it('separates accepted test failover from VM creation and final boot success', async () => {
    const wrapper = mountProgress({
      run: { runtype: 'TEST_FAILOVER', state: 'ACCEPTED', testsessionstate: 'PREPARING' }
    })
    expect(wrapper.vm.testLifecycleNotice).toBe('message.dr.test.failover.accepted')
    expect(wrapper.vm.testFailoverActive).toBe(false)

    await wrapper.setProps({
      run: { runtype: 'TEST_FAILOVER', state: 'RUNNING', testsessionstate: 'CLOUD_VM_CREATING' }
    })
    expect(wrapper.vm.testLifecycleNotice).toBe('message.dr.test.failover.vm.creating')

    await wrapper.setProps({
      run: { runtype: 'TEST_FAILOVER', state: 'SUCCEEDED', testsessionstate: 'ACTIVE' }
    })
    expect(wrapper.vm.testLifecycleNotice).toBe('message.dr.test.failover.active')
    expect(wrapper.vm.testFailoverActive).toBe(true)
  })

  it('selects the newer valid runtime sample and suppresses transient busy noise', () => {
    const wrapper = mountProgress({
      run: {
        retryable: true,
        errormessage: 'FTCTL engine is busy',
        transferprogressschemaversion: 2,
        transfercyclesequence: 8,
        transfersamplesequence: 3,
        transferbytestotal: 4096,
        transferbytesprocessed: 1024,
        transferpercent: 25
      },
      runtime: {
        transferprogressschemaversion: 2,
        transfercyclesequence: 8,
        transfersamplesequence: 4,
        transferactivitystate: 'COPYING',
        transferbytestotal: 4096,
        transferbytesprocessed: 2048,
        transferpercent: 50,
        transferprogressstale: false
      }
    })

    expect(wrapper.vm.transferPercent).toBe(50)
    expect(wrapper.vm.retryNotice).toBe('')
  })

  it('keeps whole-operation progress consistent with a live transfer sample', () => {
    const wrapper = mountProgress({
      run: {
        runtype: 'SYNC',
        state: 'RUNNING',
        progresspercent: 1,
        transferprogressschemaversion: 2,
        transfercyclesequence: 75,
        transfersamplesequence: 10,
        transferbytestotal: 1000,
        transferbytesprocessed: 220,
        transferpercent: 22
      }
    })

    expect(wrapper.vm.transferPercent).toBe(22)
    expect(wrapper.vm.progress).toBe(76)
  })

  it('does not let an older transfer sample reduce backend workflow progress', () => {
    const wrapper = mountProgress({
      run: {
        runtype: 'SYNC',
        state: 'RUNNING',
        progresspercent: 90,
        transferprogressschemaversion: 2,
        transferbytestotal: 1000,
        transferbytesprocessed: 250,
        transferpercent: 25
      }
    })

    expect(wrapper.vm.progress).toBe(90)
  })

  it('does not report a running failback as complete while data is still transferring', () => {
    const wrapper = mountProgress({
      run: {
        runtype: 'FAILBACK',
        state: 'RUNNING',
        currentstep: 'runtime-transfer',
        progresspercent: 100,
        transferprogressschemaversion: 2,
        transferbytestotal: 1000,
        transferbytesprocessed: 110,
        transferpercent: 11
      }
    })

    expect(wrapper.vm.hasTransferProgress).toBe(true)
    expect(wrapper.vm.transferPercent).toBe(11)
    expect(wrapper.vm.progress).toBe(73)
  })

  it('explains the final failback protection-resume gate and localizes its step', () => {
    const wrapper = mountProgress({
      run: {
        runtype: 'FAILBACK',
        state: 'RUNNING',
        currentstep: 'remote-source-protection-resume-pending',
        progresspercent: 95,
        transferprogressschemaversion: 2,
        transferbytestotal: 150,
        transferbytesprocessed: 150,
        transferpercent: 100
      }
    })

    expect(wrapper.vm.currentStepText).toBe('label.dr.failback.protection.resume.verifying')
    expect(wrapper.vm.failbackLifecycleNotice).toBe('message.dr.failback.protection.resume.verifying')
    expect(wrapper.vm.progress).toBe(95)
  })

  it('clamps a malformed historical disk index to the declared disk count', () => {
    const wrapper = mountProgress({
      run: {
        transferprogressschemaversion: 2,
        transferbytestotal: 150,
        transferbytesprocessed: 150,
        transfercurrentdiskindex: 2,
        transferdiskcount: 2
      }
    })

    expect(wrapper.vm.transferCurrentDisk).toBe(2)
    expect(wrapper.vm.transferDiskCount).toBe(2)
  })

  it('shows a localized guest preparation blocker for the run and step', () => {
    const wrapper = shallowMount(DrRunProgress, {
      props: {
        run: {
          state: 'FAILED',
          errorcode: 'DR_GUEST_PREP_V2K_RUNTIME_MISSING',
          errormessage: 'generic backend message'
        },
        runtime: {}
      },
      global: {
        mocks: {
          $te: key => key === 'message.dr.error.dr.guest.prep.v2k.runtime.missing',
          $t: key => `translated:${key}`
        }
      }
    })

    expect(wrapper.vm.failureText).toBe('translated:message.dr.error.dr.guest.prep.v2k.runtime.missing')
    expect(wrapper.vm.formatStepError({
      errorcode: 'DR_GUEST_PREP_V2K_RUNTIME_MISSING',
      errormessage: 'generic step message'
    })).toBe('translated:message.dr.error.dr.guest.prep.v2k.runtime.missing')
  })

  it('shows the exact test disk locator failure instead of the accepted state', () => {
    const wrapper = shallowMount(DrRunProgress, {
      props: {
        run: {
          runtype: 'TEST_FAILOVER',
          state: 'FAILED',
          errorcode: 'DR_TARGET_DISK_LOCATOR_INVALID',
          errormessage: 'unsupported test artifact type: qcow2-copy'
        },
        runtime: {}
      },
      global: {
        mocks: {
          $te: key => key === 'message.dr.error.dr.target.disk.locator.invalid',
          $t: key => `translated:${key}`
        }
      }
    })

    expect(wrapper.vm.failureText).toBe('translated:message.dr.error.dr.target.disk.locator.invalid')
    expect(wrapper.vm.testFailoverActive).toBe(false)
  })
})
