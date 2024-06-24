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
  <a-alert v-if="drTestSteps[currentStep].name === 'start'" style="margin-bottom: 5px" type="warning" show-icon>
    <template #message>
      <span v-html="$t('message.warning.dr.mirroring.test.vm.step.start')" />
    </template>
  </a-alert>
  <div class="form">
    <a-steps
      ref="zoneStep"
      labelPlacement="vertical"
      size="small"
      :current="currentStep">
      <a-step
        v-for="(item, index) in drTestSteps"
        :key="item.title"
        :title="$t(item.title)"
        :ref="`step${index}`">
      </a-step>
    </a-steps>
    <div>
      <dr-wizard-start-step
          v-if="drTestSteps[currentStep].name === 'start'"
          @nextPressed="nextPressed"
          @fieldsChanged="onFieldsChanged"
          :prefillContent="zoneConfig"
      />
      <dr-wizard-running-step
        v-else-if="drTestSteps[currentStep].name === 'coreType'"
        @nextPressed="nextPressed"
        @backPressed="backPressed"
        @fieldsChanged="onFieldsChanged"
        :isFixError="stepFixError"
        :prefillContent="zoneConfig"
      />
      <dr-wizard-launch
        v-else
        @closeAction="onCloseAction"
        @refresh-data="onRefreshData"
        @stepError="onStepError"
        :launchZone="launchZone"
        :stepChild="stepChild"
        :launchData="launchData"
        :isFixError="stepFixError"
        :prefillContent="zoneConfig"
      />
    </div>
  </div>
</template>
<script>
import { mixinDevice } from '@/utils/mixin.js'
import DrWizardLaunch from '@/views/compute/dr/DrWizardLaunch.vue'
import DrWizardStartStep from '@/views/compute/dr/DrWizardStartStep.vue'
import DrWizardRunningStep from '@/views/compute/dr/DrWizardRunningStep.vue'
export default {
  components: {
    DrWizardStartStep,
    DrWizardLaunch,
    DrWizardRunningStep
  },
  mixins: [mixinDevice],
  data () {
    return {
      currentStep: 0,
      stepFixError: false,
      launchZone: false,
      launchData: {},
      stepChild: '',
      coreTypeStep: {
        name: 'coreType',
        title: 'label.running',
        step: [],
        description: this.$t('message.select.zone.description'),
        hint: this.$t('message.select.zone.hint')
      },
      steps: [
        {
          name: 'start',
          title: 'label.start',
          step: [],
          description: this.$t('message.select.zone.description'),
          hint: this.$t('message.select.zone.hint')
        },
        {
          name: 'launch',
          title: 'label.end',
          step: ['launchZone'],
          description: this.$t('message.launch.zone.description'),
          hint: this.$t('message.launch.zone.hint')
        }
      ],
      zoneConfig: {}
    }
  },
  computed: {
    drTestSteps () {
      var steps = [...this.steps]
      steps.splice(1, 0, this.coreTypeStep)
      return steps
    }
  },
  methods: {
    nextPressed () {
      this.currentStep++
      this.scrollToStepActive()
    },
    backPressed (data) {
      this.currentStep--
      this.scrollToStepActive()
    },
    scrollToStepActive () {
      if (!this.isMobile()) {
        return
      }
      this.$nextTick(() => {
        if (!this.$refs.zoneStep) {
          return
        }
        if (this.currentStep === 0) {
          this.$refs.zoneStep.$el.scrollLeft = 0
          return
        }
        this.$refs.zoneStep.$el.scrollLeft = this.$refs['step' + (this.currentStep - 1)][0].$el.offsetLeft
      })
    },
    onFieldsChanged (data) {
      if (data.zoneType &&
        this.zoneConfig.zoneType &&
        data.zoneType !== this.zoneConfig.zoneType) {
        this.zoneConfig.physicalNetworks = null
      }
      this.zoneConfig = { ...this.zoneConfig, ...data }
    },
    onCloseAction () {
      this.$emit('close-action')
    },
    onRefreshData () {
      this.$message.success(this.$t('message.processing.complete'))
      this.$emit('refresh-data')
      this.onCloseAction()
    },
    onStepError (step, launchData) {
      this.currentStep = this.drTestSteps.findIndex(item => item.step.includes(step))
      this.stepChild = step
      this.launchData = launchData
      this.launchZone = false
      this.stepFixError = true
    },
    onLaunchZone () {
      this.stepFixError = false
      this.launchZone = true
      this.currentStep = this.drTestSteps.findIndex(item => item.step.includes('launchZone'))
    }
  }
}
</script>

<style scoped lang="scss">
  .form {
    width: 100%;
    @media (min-width: 1000px) {
      width: 800px;
    }
    :deep(.form-action) {
      position: relative;
      margin-top: 16px;
      height: 35px;
    }
    :deep(.button-next) {
      position: absolute;
      right: 0;
    }
    :deep(.button-next).ant-btn-loading:not(.ant-btn-circle):not(.ant-btn-circle-outline):not(.ant-btn-icon-only) {
      position: absolute;
      right: 0;
    }
    :deep(.ant-steps) {
      overflow-x: auto;
      padding: 10px 0;
    }
    :deep(.submit-btn) {
      display: none;
    }
  }
  :deep(.ant-form-text) {
    width: 100%;
  }
  .steps-content {
    border: 1px dashed #e9e9e9;
    border-radius: 6px;
    background-color: #fafafa;
    min-height: 200px;
    text-align: center;
    vertical-align: center;
    padding: 8px;
    padding-top: 16px;
  }
  .ant-alert-warning {
    border: 1px solid #ffe58f;
    background-color: #fffbe6;
  }
  .steps-action {
    margin-top: 24px;
  }
</style>
