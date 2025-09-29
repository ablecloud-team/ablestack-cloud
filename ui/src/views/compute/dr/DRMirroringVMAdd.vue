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
  <a-spin :spinning="loading">
    <a-form
      class="form"
      layout="vertical"
      :ref="formRef"
      :model="form"
      :rules="rules"
      @finish="handleSubmit"
      v-ctrl-enter="handleSubmit"
    >
      <a-alert style="margin-bottom: 5px" type="warning" show-icon>
        <template #message>
          <span v-html="$t('message.add.dr.mirroring.vm')" />
        </template>
      </a-alert>
      <a-form-item
        ref="drCluster"
        name="drCluster"
        :label="$t('label.cluster')">
        <a-select
          v-model:value="form.drCluster"
          :loading="loading"
          @change="updateSelectedId"
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }"
          :placeholder="$t('placeholder.dr.cluster.cluster.selection')">
          <a-select-option v-for="(opt) in this.drCluster" :key="opt.id" :value="opt.name">
            {{ opt.name }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item
        ref="secDrClusterOfferings"
        name="secDrClusterOfferings"
        :label="$t('label.compute.offerings')">
        <a-select
          v-model:value="form.secDrClusterOfferings"
          :loading="loading"
          @change="updateSelectedOfferingId"
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }"
          :placeholder="$t('placeholder.dr.cluster.compute.offering.selection')">
          <a-select-option v-for="(opt) in this.secDrClusterOfferings" :key="opt.id" :value="opt.name">
            {{ opt.name }}
          </a-select-option>
        </a-select>
        <a-card v-if="this.isCustomized">
          <a-col>
            <a-row>
              <a-col :md="colContraned" :lg="colContraned" v-if="this.isCustomized">
                <a-form-item
                  :label="$t('label.cpunumber')"
                  :validate-status="errors.cpu.status"
                  :help="errors.cpu.message">
                  <a-select
                    v-model:value="cpuNumberInputValue"
                    show-search
                    style="width: 100px"
                    :options="cpuOptions"
                    @change="($event) => updateComputeCpuNumber($event) "
                  ></a-select>
                </a-form-item>
              </a-col>
              <a-col :md="colContraned" :lg="colContraned" v-if="this.isCustomized">
                <a-form-item
                  :label="$t('label.memory.gb')"
                  :validate-status="errors.memory.status"
                  :help="errors.memory.message">
                  <a-select
                    v-model:value="memoryInputValue"
                    show-search
                    style="width: 100px"
                    :options="memOptions"
                    @change="($event) => updateComputeMemory($event)"
                  ></a-select>
                </a-form-item>
              </a-col>
            </a-row>
          </a-col>
        </a-card>
      </a-form-item>
      <a-form-item
        ref="secDrClusterNetworkList"
        name="secDrClusterNetworkList"
        :label="$t('label.network.name')">
        <a-select
          v-model:value="form.secDrClusterNetworkList"
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }"
          :placeholder="$t('placeholder.dr.cluster.network.selection')">
          <a-select-option v-for="(opt) in this.secDrClusterNetworkList" :key="opt.id" :value="opt.name">
            {{ opt.name }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <div :span="24" class="action-button">
        <a-button @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-form>
  </a-spin>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI } from '@/api'
import ResourceIcon from '@/components/view/ResourceIcon.vue'
import TooltipLabel from '@/components/widgets/TooltipLabel.vue'
export default {
  name: 'DRMirroringVMAdd',
  components: {
    TooltipLabel,
    ResourceIcon
  },
  props: {
    resource: {
      type: Object,
      default: () => {}
    }
  },
  inject: ['parentFetchData'],
  data () {
    return {
      loading: false,
      isCustomized: false,
      params: [],
      drCluster: [],
      drVm: [],
      selectedDrCluster: [],
      secDrClusterOfferings: [],
      secDrClusterNetworkList: [],
      cpuNumberInputValue: 0,
      memoryInputValue: 0,
      errors: {
        cpu: {
          status: '',
          message: ''
        },
        memory: {
          status: '',
          message: ''
        }
      },
      cpuOpt: [
        { value: '1', label: '1' },
        { value: '2', label: '2' },
        { value: '4', label: '4' },
        { value: '8', label: '8' },
        { value: '16', label: '16' },
        { value: '32', label: '32' },
        { value: '64', label: '64' },
        { value: '128', label: '128' },
        { value: '256', label: '256' },
        { value: '512', label: '512' },
        { value: '1024', label: '1024' },
        { value: '2048', label: '2048' },
        { value: '4096', label: '4096' },
        { value: '8192', label: '8192' }
      ],
      memOpt: [
        { value: '512', label: '0.5' },
        { value: '1024', label: '1' },
        { value: '2048', label: '2' },
        { value: '4096', label: '4' },
        { value: '8192', label: '8' },
        { value: '16384', label: '16' },
        { value: '32768', label: '32' },
        { value: '65536', label: '64' },
        { value: '131072', label: '128' },
        { value: '262144', label: '256' },
        { value: '524288', label: '512' },
        { value: '1048576', label: '1024' },
        { value: '2097152', label: '2048' },
        { value: '4194304', label: '4096' },
        { value: '8388608', label: '8192' }
      ]
    }
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  mounted () {
    if (this.isCustomized) {
      this.fillValue()
    }
  },
  computed: {
    colContraned () {
      if (this.maxCpu && !isNaN(this.maxCpu)) {
        return 12
      }
      return 8
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        drCluster: [{ required: true, message: this.$t('label.required') }],
        secDrClusterOfferings: [{ required: true, message: this.$t('label.required') }],
        secDrClusterNetworkList: [{ required: true, message: this.$t('label.required') }]
      })
    },
    fetchData () {
      this.fetchDRClusterList()
    },
    fetchDRClusterList () {
      this.loading = true
      var drName = ''
      getAPI('getDisasterRecoveryClusterList', { drclustertype: 'secondary' }).then(json => {
        this.drCluster = json.getdisasterrecoveryclusterlistresponse.disasterrecoverycluster
        for (const dr of this.drCluster) {
          for (const vm of dr.drclustervmmap) {
            if (vm.drclustervmid === this.resource.id) {
              drName = vm.drclustername
            }
          }
        }
        if (drName !== '') {
          this.drCluster = this.drCluster.filter(entry => {
            return !entry.name.includes(drName)
          })
        }
        this.drCluster = this.drCluster.filter(dr => {
          return dr.drclusterstatus !== 'Disabled'
        })
        if (this.drCluster.length > 0) {
          this.form.drCluster = this.drCluster[0].name
        } else {
          this.form.drCluster = null
        }
      }).finally(() => {
        this.loading = false
        this.fetchSecDRClusterInfoList()
      })
    },
    // 재해복구용 가상머신 생성 모달에서 DR Secondary 클러스터를 선택했을 때 컴퓨트 오퍼링과 네트워크 목록을 불러오는 함수
    fetchSecDRClusterInfoList () {
      this.loading = true
      if (this.selectedClusterId === undefined) {
        if (this.form.drCluster != null) {
          this.secDrClusterOfferings = this.drCluster[0].serviceofferingdetails || []
          this.secDrClusterOfferings = this.secDrClusterOfferings.filter(off => {
            // kvdo 오퍼링 필터링 임시 주석처리
            // return off.kvdoenable === this.resource.kvdoenable
            return off.kvdoenable === false
          })
          this.form.secDrClusterOfferings = this.secDrClusterOfferings.length > 0 ? this.secDrClusterOfferings[0].name : null
          if (this.secDrClusterOfferings[0].serviceofferingdetails && this.secDrClusterOfferings[0].iscustomized === true) {
            this.isCustomized = true
            this.minCpu = this.secDrClusterOfferings[0].serviceofferingdetails.mincpunumber * 1
            this.maxCpu = this.secDrClusterOfferings[0].serviceofferingdetails.maxcpunumber * 1
            this.minMemory = this.secDrClusterOfferings[0].serviceofferingdetails.minmemory * 1
            this.maxMemory = this.secDrClusterOfferings[0].serviceofferingdetails.maxmemory * 1
            this.fillValue()
          }
          this.secDrClusterNetworkList = this.drCluster[0].network || []
          this.form.secDrClusterNetworkList = this.secDrClusterNetworkList.length > 0 ? this.secDrClusterNetworkList[0].name : null
        } else {
          this.form.secDrClusterOfferings = null
          this.form.secDrClusterNetworkList = null
        }
        this.loading = false
      } else {
        this.SecDRClusterInfoList(this.selectedClusterId)
      }
    },
    SecDRClusterInfoList (selectId) {
      getAPI('getDisasterRecoveryClusterList', { id: selectId, drclustertype: 'secondary' }).then(json => {
        const response = json.getdisasterrecoveryclusterlistresponse
        const clusters = response ? response.disasterrecoverycluster : null
        if (clusters && clusters.length > 0) {
          const cluster = clusters[0]
          this.secDrClusterOfferings = cluster.serviceofferingdetails || []
          this.secDrClusterOfferings = this.secDrClusterOfferings.filter(off => {
            // kvdo 오퍼링 필터링 임시 주석처리
            // return off.kvdoenable === this.resource.kvdoenable
            return off.kvdoenable === false
          })
          this.form.secDrClusterOfferings = this.secDrClusterOfferings.length > 0 ? this.secDrClusterOfferings[0].name : ''
          if (this.secDrClusterOfferings[0].serviceofferingdetails && this.secDrClusterOfferings[0].iscustomized === true) {
            this.isCustomized = true
            this.minCpu = this.secDrClusterOfferings[0].serviceofferingdetails.mincpunumber * 1
            this.maxCpu = this.secDrClusterOfferings[0].serviceofferingdetails.maxcpunumber * 1
            this.minMemory = this.secDrClusterOfferings[0].serviceofferingdetails.minmemory * 1
            this.maxMemory = this.secDrClusterOfferings[0].serviceofferingdetails.maxmemory * 1
            this.fillValue()
          }
          this.secDrClusterNetworkList = cluster.network || []
          this.form.secDrClusterNetworkList = this.secDrClusterNetworkList.length > 0 ? this.secDrClusterNetworkList[0].name : ''

          if (this.secDrClusterOfferings.length === 0 && this.secDrClusterNetworkList.length === 0) {
            this.resetSelection() // Reset selections in case of missing data
          }
        } else {
          this.resetSelection() // Reset selections in case of missing clusters
        }
      }).catch(() => {
        this.resetSelection() // 에러 발생 후 선택된 값 초기화
        this.loading = false // 에러 처리 후 로딩 종료
      }).finally(() => {
        this.loading = false
      })
    },
    fillValue () {
      if (this.maxCpu && !isNaN(this.maxCpu)) {
        this.cpuOptions = this.cpuOpt.filter(x => x.value >= this.minCpu && x.value <= this.maxCpu)
        this.memOptions = this.memOpt.filter(x => x.value >= this.minMemory && x.value <= this.maxMemory)
      } else {
        this.cpuOptions = this.cpuOpt
        this.memOptions = this.memOpt
      }
      this.cpuNumberInputValue = this.cpuOptions[0].value
      this.memoryInputValue = this.memOptions[0].value

      this.updateComputeCpuNumber(this.cpuNumberInputValue)
      this.updateComputeMemory(this.memoryInputValue)
    },
    updateComputeCpuNumber (value) {
      if (!value) this.cpuNumberInputValue = 0
      if (!this.validateInput('cpu', value)) {
        return
      }
      this.$emit('update-compute-cpunumber', 'cpunumber', value)
    },
    updateComputeMemory (value) {
      if (!value) this.memoryInputValue = 0
      if (!this.validateInput('memory', value)) {
        return
      }
      this.$emit('update-compute-memory', 'memory', value)
    },
    validateInput (input, value) {
      this.errors[input].status = ''
      this.errors[input].message = ''

      if (value === null || value === undefined || value.length === 0) {
        this.errors[input].status = 'error'
        this.errors[input].message = this.$t('message.error.required.input')
        return false
      }

      let min
      let max

      switch (input) {
        case 'cpu':
          min = this.minCpu
          max = this.maxCpu
          break
        case 'memory':
          min = this.memOptions[0].value / 1024
          max = this.memOptions[this.memOptions.length - 1].value / 1024
          value = value / 1024
          break
      }
      if (!this.checkValidRange(value, min, max)) {
        this.errors[input].status = 'error'
        this.errors[input].message = `${this.$t('message.please.enter.value')} (${min} ~ ${max})`
        return false
      }

      return true
    },
    checkValidRange (value, min, max) {
      if (value < min || value > max) {
        return false
      }

      return true
    },
    updateSelectedId (value) {
      this.resetSelection()
      this.selectedClusterId = value
      this.fetchSecDRClusterInfoList()
    },
    updateSelectedOfferingId (selectOff) {
      this.loading = true
      this.isCustomized = false
      getAPI('getDisasterRecoveryClusterList', { id: this.selectedClusterId, drclustertype: 'secondary' }).then(json => {
        const response = json.getdisasterrecoveryclusterlistresponse
        const clusters = response ? response.disasterrecoverycluster : null
        if (clusters && clusters.length > 0) {
          const cluster = clusters[0]
          this.secDrClusterOfferings = cluster.serviceofferingdetails || []
          this.secDrClusterOfferings = this.secDrClusterOfferings.filter(off => {
            // kvdo 오퍼링 필터링 임시 주석처리
            // return off.kvdoenable === this.resource.kvdoenable
            return off.kvdoenable === false
          })
          for (const offering of this.secDrClusterOfferings) {
            if (offering.name === selectOff && offering.serviceofferingdetails && offering.iscustomized === true) {
              this.isCustomized = true
              this.minCpu = offering.serviceofferingdetails.mincpunumber * 1
              this.maxCpu = offering.serviceofferingdetails.maxcpunumber * 1
              this.minMemory = offering.serviceofferingdetails.minmemory * 1
              this.maxMemory = offering.serviceofferingdetails.maxmemory * 1
              this.fillValue()
            }
          }
        }
      }).catch(() => {
        this.resetSelection() // 에러 발생 후 선택된 값 초기화
        this.loading = false // 에러 처리 후 로딩 종료
      }).finally(() => {
        this.loading = false
      })
    },
    resetSelection () {
      this.form.secDrClusterOfferings = null
      this.form.secDrClusterNetworkList = null
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        if (this.resource.kvdoenable) {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('message.error.confirm.create.dr.mirroring.vm')
          })
          return
        }
        this.loading = true
        const params = {
          virtualmachineid: this.resource.id,
          drclustername: values.drCluster,
          serviceofferingname: values.secDrClusterOfferings,
          networkname: values.secDrClusterNetworkList
        }
        if (this.isCustomized) {
          params.cpunumber = this.cpuNumberInputValue
          params.memory = this.memoryInputValue
        }
        getAPI('createDisasterRecoveryClusterVm', params).then(response => {
          this.$pollJob({
            jobId: response.createdisasterrecoveryclustervmresponse.jobid,
            title: this.$t('message.success.create.disaster.recovery.cluster.vm'),
            description: this.resource.id,
            successMessage: this.$t('message.success.create.disaster.recovery.cluster.vm'),
            successMethod: () => {
              this.loading = false
              this.parentFetchData()
            },
            errorMessage: this.$t('message.error.create.disaster.recovery.cluster.vm'),
            errorMethod: () => {
              this.loading = false
              this.parentFetchData()
            },
            loadingMessage: this.$t('message.create.disaster.recovery.cluster.vm.processing'),
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              this.loading = false
              this.parentFetchData()
            }
          })
          this.closeModal()
        }).catch(error => {
          this.$notifyError(error)
          this.loading = false
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    closeModal () {
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped lang="scss">
.required {
  color: #ff0000;
  &-label {
    display: none;
    &--visible {
      display: block;
    }
  }
}
.ant-alert-warning {
  border: 1px solid #ffe58f;
  background-color: #fffbe6;
}
</style>
