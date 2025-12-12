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
    <a-card class="ant-form-text card-launch-description">
      {{ $t(description.launching) }}
    </a-card>
    <a-card class="ant-form-text card-launch-content">
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
              <div><strong>{{ $t('label.error') }}:</strong></div>
              <div>{{ messageError }}</div>
            </a-card>
          </template>
        </a-step>
      </a-steps>
    </a-card>
  </div>
  <div class="form-action">
    <a-button
      v-if="processStatus==='error'"
      class="button-next"
      type="primary"
      :loading="loading"
      @click="closeModal">{{ $t('label.cancel') }}
    </a-button>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
const STATUS_PROCESS = 'process'
const STATUS_FINISH = 'finish'
const STATUS_FAILED = 'error'
export default {
  props: {
    launchData: {
      type: Object,
      default () {
        return {}
      }
    },
    launchMock: {
      type: Boolean,
      default: false
    },
    stepChild: {
      type: String,
      default: ''
    },
    resource: {
      type: Object,
      default: () => {}
    }
  },
  data: () => ({
    formModel: {},
    processStatus: null,
    description: {
      waiting: 'message.launch.dr.simulation.test',
      launching: 'message.waiting.dr.simulation.test',
      finish: 'message.finish.dr.simulation.test'
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
    },
    clusters: [],
    vmMap: []
  }),
  created () {
    this.initForm()
  },
  mounted () {
    if (this.launchMock) {
      this.processStatus = STATUS_PROCESS
      this.stepData = this.launchData
    }
    this.handleSubmit()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
      })
      this.rules = reactive({
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
    async handleSubmit () {
      this.processStatus = STATUS_PROCESS
      if (!this.stepData.stepMove) {
        this.stepData.stepMove = []
      }
      await this.stepOneTestDr()
    },
    async stepOneTestDr () {
      this.addStep('message.dr.simulation.test.step1', 'stepOneTestDr')
      try {
        if (!this.stepData.stepMove.includes('stepOneTestDr')) {
          await this.drInfo()
          await this.stopVm()
          await this.stopDrVm()
          this.stepData.stepMove.push('stepOneTestDr')
        }
        await this.stepTwoTestDr()
        await this.stepThreeTestDr()
        await this.stepFourTestDr()
        await this.stepFiveTestDr()
        await this.stepSixTestDr()
        await this.stepSevenTestDr()
        await this.stepEightTestDr()
        this.$emit('nextPressed')
      } catch (e) {
        this.messageError = e
        this.processStatus = STATUS_FAILED
        this.setStepStatus(STATUS_FAILED)
      }
    },
    async stepTwoTestDr () {
      if (this.stepData.stepMove.includes('stepTwoTestDr')) {
        return
      }
      this.setStepStatus(STATUS_FINISH)
      this.currentStep++
      this.addStep('message.dr.simulation.test.step2', 'stepTwoTestDr')
      await this.snapVm()
      this.stepData.stepMove.push('stepTwoTestDr')
    },
    async stepThreeTestDr () {
      if (this.stepData.stepMove.includes('stepThreeTestDr')) {
        return
      }
      this.setStepStatus(STATUS_FINISH)
      this.currentStep++
      this.addStep('message.dr.simulation.test.step3', 'stepThreeTestDr')
      await this.demoteImage()
      this.stepData.stepMove.push('stepThreeTestDr')
    },
    async stepFourTestDr () {
      if (this.stepData.stepMove.includes('stepFourTestDr')) {
        return
      }
      this.setStepStatus(STATUS_FINISH)
      this.currentStep++
      this.addStep('message.dr.simulation.test.step4', 'stepFourTestDr')
      await this.startDrVm()
      this.stepData.stepMove.push('stepFourTestDr')
    },
    async stepFiveTestDr () {
      if (this.stepData.stepMove.includes('stepFiveTestDr')) {
        return
      }
      this.setStepStatus(STATUS_FINISH)
      this.currentStep++
      this.addStep('message.dr.simulation.test.step5', 'stepFiveTestDr')
      await this.statusVm()
      this.stepData.stepMove.push('stepFiveTestDr')
    },
    async stepSixTestDr () {
      if (this.stepData.stepMove.includes('stepSixTestDr')) {
        return
      }
      this.setStepStatus(STATUS_FINISH)
      this.currentStep++
      this.addStep('message.dr.simulation.test.step6', 'stepSixTestDr')
      await this.stopDrVm()
      this.stepData.stepMove.push('stepSixTestDr')
    },
    async stepSevenTestDr () {
      if (this.stepData.stepMove.includes('stepSevenTestDr')) {
        return
      }
      this.setStepStatus(STATUS_FINISH)
      this.currentStep++
      this.addStep('message.dr.simulation.test.step7', 'stepSevenTestDr')
      await this.promoteDrImage()
      this.stepData.stepMove.push('stepSevenTestDr')
    },
    async stepEightTestDr () {
      if (this.stepData.stepMove.includes('stepEightTestDr')) {
        return
      }
      this.setStepStatus(STATUS_FINISH)
      this.currentStep++
      this.addStep('message.dr.simulation.test.step8', 'stepEightTestDr')
      await this.startVm()
      this.stepData.stepMove.push('stepEightTestDr')
    },
    async pollJob (jobId) {
      return new Promise(resolve => {
        const asyncJobInterval = setInterval(() => {
          getAPI('queryAsyncJobResult', { jobId }).then(async json => {
            const result = json.queryasyncjobresultresponse
            if (result.jobstatus === 0) {
              return
            }
            clearInterval(asyncJobInterval)
            resolve(result)
          })
        }, 1000)
      })
    },
    async drJob () {
      return new Promise(resolve => {
        const drJobInterval = setInterval(() => {
          getAPI('getDisasterRecoveryClusterList', { drclustertype: 'secondary' }).then(async json => {
            this.drClusterList = json.getdisasterrecoveryclusterlistresponse.disasterrecoverycluster || []
            for (const cluster of this.drClusterList) {
              const vmList = cluster.drclustervmmap
              if (vmList.some(vm => vm.drclustervmid === this.resource.id)) {
                this.clusters = cluster
                var map = cluster.drclustervmmap
                this.vmMap = map.filter(it => it.drclustervmid === this.resource.id)
                if (this.vmMap[0].drclustervmvolstatus !== 'SYNCING') {
                  return
                }
              }
            }
            clearInterval(drJobInterval)
            resolve()
          })
        }, 60000)
      })
    },
    drInfo () {
      return new Promise((resolve, reject) => {
        getAPI('getDisasterRecoveryClusterList', {
          drclustertype: 'secondary'
        }).then(json => {
          this.drClusterList = json.getdisasterrecoveryclusterlistresponse.disasterrecoverycluster || []
          for (const cluster of this.drClusterList) {
            const vmList = cluster.drclustervmmap
            if (vmList.some(vm => vm.drclustervmid === this.resource.id)) {
              this.clusters = cluster
              var map = cluster.drclustervmmap
              this.vmMap = map.filter(it => it.drclustervmid === this.resource.id)
              break
            }
          }
          resolve(this.vmMap)
        }).catch(error => {
          reject(error)
        })
      })
    },
    async stopVm () {
      return new Promise((resolve, reject) => {
        let message = ''
        const params = {}
        params.id = this.resource.id
        params.forced = true
        postAPI('stopVirtualMachine', params).then(async json => {
          const jobId = json.stopvirtualmachineresponse.jobid
          if (jobId) {
            const result = await this.pollJob(jobId)
            if (result.jobstatus === 2) {
              message = `stopVirtualMachine ${this.$t('label.failed').toLowerCase()}. ${this.$t('label.error')}: ` + result.jobresult.errortext
              reject(message)
              return
            }
            resolve(result)
          }
        }).catch(error => {
          message = error.response.headers['x-description']
          reject(message)
        })
      })
    },
    stopDrVm () {
      return new Promise((resolve, reject) => {
        let message = ''
        const params = {}
        params.virtualmachineid = this.resource.id
        params.drclustername = this.clusters.name
        postAPI('stopDisasterRecoveryClusterVm', params).then(json => {
          resolve()
        }).catch(error => {
          message = error.response.headers['x-description']
          reject(message)
        })
      })
    },
    snapVm () {
      return new Promise((resolve, reject) => {
        let message = ''
        const params = {}
        params.virtualmachineid = this.resource.id
        postAPI('takeSnapshotDisasterRecoveryClusterVm', params).then(json => {
          resolve()
        }).catch(error => {
          message = error.response.headers['x-description']
          reject(message)
        })
      })
    },
    async demoteImage () {
      return new Promise((resolve, reject) => {
        let message = ''
        const params = {}
        params.virtualmachineid = this.resource.id
        params.drclustername = this.clusters.name
        postAPI('demoteDisasterRecoveryClusterVm', params).then(async json => {
          await this.drJob()
          resolve()
        }).catch(error => {
          message = error.response.headers['x-description']
          reject(message)
        })
      })
    },
    startDrVm () {
      return new Promise((resolve, reject) => {
        let message = ''
        const params = {}
        params.virtualmachineid = this.resource.id
        params.drclustername = this.clusters.name
        postAPI('startDisasterRecoveryClusterVm', params).then(json => {
          resolve()
        }).catch(error => {
          message = error.response.headers['x-description']
          reject(message)
        })
      })
    },
    statusVm () {
      return new Promise((resolve, reject) => {
        setTimeout(() => {
          // 1 min status check
          resolve()
        }, 60000)
      })
    },
    promoteDrImage () {
      return new Promise((resolve, reject) => {
        let message = ''
        const params = {}
        params.virtualmachineid = this.resource.id
        params.drclustername = this.clusters.name
        postAPI('promoteDisasterRecoveryClusterVm', params).then(json => {
          setTimeout(() => {
            // 3 min status check
            resolve()
          }, 180000)
        }).catch(error => {
          message = error.response.headers['x-description']
          reject(message)
        })
      })
    },
    async startVm () {
      return new Promise((resolve, reject) => {
        let message = ''
        const params = {}
        params.id = this.resource.id
        params.considerlasthost = true
        postAPI('startVirtualMachine', params).then(async json => {
          const jobId = json.startvirtualmachineresponse.jobid
          if (jobId) {
            const result = await this.pollJob(jobId)
            if (result.jobstatus === 2) {
              message = `startVirtualMachine ${this.$t('label.failed').toLowerCase()}. ${this.$t('label.error')}: ` + result.jobresult.errortext
              reject(message)
              return
            }
            resolve(result)
          }
        }).catch(error => {
          message = error.response.headers['x-description']
          reject(message)
        })
      })
    },
    closeModal () {
      this.loading = false
      this.steps = []
      this.$emit('closeAction')
      this.$emit('refresh-data')
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
