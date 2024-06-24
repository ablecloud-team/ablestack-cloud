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

<template>
  <div>
    <div>
      <a-card class="ant-form-text card-launch-description">
        {{ $t(description.waiting) }}
      </a-card>
      <a-card
          id="launch-content"
          class="ant-form-text card-launch-content">
        <a-steps
            size="small"
            direction="vertical"
            :current="currentStep"
        >
          <a-step
              v-for="(step, index) in steps"
              :key="index"
              :title="$t(step.title)"
              :status="step.status">
            <template #icon>
              <LoadingOutlined v-if="step.status===status.PROCESS" />
              <CloseCircleOutlined v-else-if="step.status===status.FAILED" />
            </template>
            <template #description>
              <a-card
                  class="step-error"
                  v-if="step.status===status.FAILED"
              >
                <div><strong>{{ $t('label.error.something.went.wrong.please.correct.the.following') }}:</strong></div>
                <div>{{ messageError }}</div>
              </a-card>
            </template>
          </a-step>
        </a-steps>
      </a-card>
      <div class="form-action">
        <a-button
            v-if="processStatus==='finish'"
            class="button-next"
            type="primary"
            :loading="loading"
            @click="enableZoneAction"
        >
          <template #icon><play-circle-outlined /></template>
          {{ $t('label.action.enable.zone') }}
        </a-button>
        <a-button
            v-if="processStatus==='error'"
            class="button-next"
            type="primary"
            @click="handleFixesError"
        >{{ $t('label.fix.errors') }}</a-button>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
const STATUS_PROCESS = 'process'
const STATUS_FINISH = 'finish'
const STATUS_FAILED = 'error'
export default {
  props: {
    prefillContent: {
      type: Object,
      default: function () {
        return {}
      }
    },
    isFixError: {
      type: Boolean,
      default: false
    }
  },
  data: () => ({
    formItemLayout: {
      labelCol: { span: 6 },
      wrapperCol: { span: 14 }
    },
    zoneDescription: {
      Basic: 'message.desc.basic.zone',
      Advanced: 'message.desc.advanced.zone',
      SecurityGroups: 'message.advanced.security.group'
    },
    formModel: {},
    isLaunchTest: false,
    processStatus: null,
    description: {
      waiting: 'message.waiting.dr.simulation.test',
      launching: 'message.please.wait.while.zone.is.being.created'
    },
    loading: false,
    messageError: '',
    currentStep: 0,
    steps: [],
    stepData: {},
    status: {
      PROCESS: STATUS_PROCESS,
      FAILED: STATUS_FAILED,
      FINISH: STATUS_FINISH
    }
  }),
  created () {
    this.initForm()
  },
  watch: {
    formModel: {
      deep: true,
      handler (changedFields) {
        const fieldsChanged = toRaw(changedFields)
        this.$emit('fieldsChanged', fieldsChanged)
      }
    }
  },
  updated () {
    const launchElm = this.$el.querySelector('#launch-content')
    if (launchElm) {
      launchElm.scrollTop = launchElm.scrollHeight
    }
  },
  computed: {
    isAdvancedZone () {
      return this.zoneType === 'Advanced'
    },
    zoneType () {
      return this.prefillContent.zoneType ? this.prefillContent.zoneType : 'Advanced'
    },
    securityGroupsEnabled () {
      return this.isAdvancedZone && (this.prefillContent?.securityGroupsEnabled || false)
    }
  },
  mounted () {
    if (this.launchZone) {
      this.processStatus = STATUS_PROCESS
      this.stepData = this.launchData
    }
    this.handleSubmit()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        zoneType: this.zoneType,
        securityGroupsEnabled: this.securityGroupsEnabled
      })
      this.rules = reactive({
        zoneType: [{ required: true, message: this.$t('message.error.zone.type') }]
      })
      this.formModel = toRaw(this.form)
    },
    addStep (title, step) {
      this.steps.push({
        index: this.currentStep,
        title,
        step,
        status: STATUS_PROCESS
      })
      this.setStepStatus(STATUS_PROCESS)
    },
    setStepStatus (status) {
      const index = this.steps.findIndex(step => step.index === this.currentStep)
      this.steps[index].status = status
      this.nsx = false
    },
    handleBack () {
      this.$emit('backPressed')
    },
    async handleSubmit () {
      this.processStatus = STATUS_PROCESS
      if (!this.stepData.stepMove) {
        this.stepData.stepMove = []
      }
      await this.stepTestDr()
    },
    async stepTestDr () {
      this.addStep('message.dr.simulation.test.step1', 'stepTestDr')
      try {
        if (!this.stepData.stepMove.includes('createZone')) {
          this.stepData.stepMove.push('createZone')
        }
        await this.stepOneDedicateTestDr()
        await this.stepTwoDedicateTestDr()
      } catch (e) {
        this.messageError = e
        this.processStatus = STATUS_FAILED
        this.setStepStatus(STATUS_FAILED)
      }
    },
    async stepOneDedicateTestDr () {
      if (!this.isDedicated || this.stepData.stepMove.includes('dedicateTestDr')) {
        return
      }
      this.setStepStatus(STATUS_FINISH)
      this.currentStep++
      this.addStep('message.dr.simulation.test.step1', 'dedicateTestDr')
      try {
        this.stepData.stepMove.push('dedicateTestDr')
      } catch (e) {
        this.messageError = e
        this.processStatus = STATUS_FAILED
        this.setStepStatus(STATUS_FAILED)
      }
    },
    async stepTwoDedicateTestDr () {
      this.setStepStatus(STATUS_FINISH)
      this.currentStep++
      this.addStep('message.dr.simulation.test.step2', 'physicalNetwork')
      try {
        this.stepData.stepMove.push('physicalNetwork')
        await this.stepThreeDedicateTestDr()
      } catch (e) {
        this.messageError = e
        this.processStatus = STATUS_FAILED
        this.setStepStatus(STATUS_FAILED)
      }
    },
    async stepThreeDedicateTestDr () {
      this.setStepStatus(STATUS_FINISH)
      this.currentStep++
      this.addStep('message.dr.simulation.test.step3', 'physicalNetwork')
      try {
        this.stepData.stepMove.push('createPhysicalNetwork')
      } catch (e) {
        this.messageError = e
        this.processStatus = STATUS_FAILED
        this.setStepStatus(STATUS_FAILED)
      }
    }
  }
}
</script>
<style scoped lang="less">
  .card-waiting-launch {
    text-align: center;
    margin: 10px 0;
    width: 100%;
    padding: 20px;
    font-size: 20px;
  }
  .card-item {
    margin-top: 10px;
    .card-form-item {
      float: left;
    }
    .checkbox-advance {
      margin-top: 10px;
    }
    .zone-support {
      text-align: justify;
    }
  }
  .ant-form-text {
    text-align: justify;
    margin: 10px 0;
    padding: 24px;
    width: 100%;
  }
</style>
