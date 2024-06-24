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
      :ref="formRef"
      :model="form"
      :rules="rules"
      layout="vertical"
      class="form"
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
            @change="updateSelectedId"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :placeholder="$t('placeholder.dr.cluster.cluster.selection')"
            >
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
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :placeholder="$t('placeholder.dr.cluster.compute.offering.selection')"
        >
          <a-select-option v-for="(opt) in this.secDrClusterOfferings" :key="opt.id" :value="opt.name">
            {{ opt.name }}
          </a-select-option>
        </a-select>
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
            :placeholder="$t('placeholder.dr.cluster.network.selection')"
        >
          <a-select-option v-for="(opt) in this.secDrClusterNetworkList" :key="opt.id" :value="opt.name">
            {{ opt.name }}
          </a-select-option>
        </a-select>
      </a-form-item>

      <div :span="24" class="action-button">
        <a-button @click="cancel">{{ $t('label.cancel') }}</a-button>
        <a-button @click="handleSubmit" ref="submit" type="primary">{{ $t('label.ok') }}</a-button>
      </div>

    </a-form>
  </a-spin>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { api } from '@/api'
import DedicateDomain from '@comp/view/DedicateDomain.vue'
import ResourceIcon from '@/components/view/ResourceIcon.vue'
import TooltipLabel from '@/components/widgets/TooltipLabel.vue'
export default {
  name: 'DRMirroringVMAdd',
  components: {
    TooltipLabel,
    DedicateDomain,
    ResourceIcon
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  inject: ['parentFetchData'],
  setup () {
    const spinning = ref(false)
    const changeSpinning = () => {
      spinning.value = !spinning.value
    }
    return {
      spinning,
      changeSpinning
    }
  },
  data () {
    return {
      loading: false,
      params: [],
      drCluster: [],
      selectedDrCluster: [],
      selectedId: '',
      secDrClusterOfferings: [],
      secDrClusterNetworkList: []
    }
  },
  created () {
    this.initForm()
    this.fetchData()
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
      this.fetchSecDRClusterInfoList()
    },
    fetchDRClusterList () {
      this.loading = true
      api('getDisasterRecoveryClusterList', { drclustertype: 'secondary' }).then(json => {
        this.drCluster = json.getdisasterrecoveryclusterlistresponse.disasterrecoverycluster || []
        this.form.drCluster = this.drCluster[0].name || ''
      }).catch(error => {
        console.error('Error fetching DR cluster list:', error)
        this.loading = false
      })
    },
    // 재해복구용 가상머신 생성 모달에서 DR Secondary 클러스터를 선택했을 때 컴퓨트 오퍼링과 네트워크 목록을 불러오는 함수
    fetchSecDRClusterInfoList () {
      this.loading = true
      api('getDisasterRecoveryClusterList', { id: this.selectedClusterId, drclustertype: 'secondary' }).then(json => {
        const response = json.getdisasterrecoveryclusterlistresponse
        const clusters = response ? response.disasterrecoverycluster : null

        if (clusters && clusters.length > 0) {
          const cluster = clusters[0]

          this.secDrClusterOfferings = cluster.serviceofferingdetails || []
          this.form.secDrClusterOfferings = this.secDrClusterOfferings.length > 0 ? this.secDrClusterOfferings[0].name : ''

          this.secDrClusterNetworkList = cluster.network || []
          this.form.secDrClusterNetworkList = this.secDrClusterNetworkList.length > 0 ? this.secDrClusterNetworkList[0].name : ''

          if (this.secDrClusterOfferings.length === 0 && this.secDrClusterNetworkList.length === 0) {
            console.error('No service offering or network details available')
            this.resetSelection() // Reset selections in case of missing data
          }
        } else {
          console.error('No disaster recovery clusters found')
          this.resetSelection() // Reset selections in case of missing clusters
        }
      }).catch(error => {
        console.error('API 호출 실패:', error)
        this.resetSelection() // 에러 발생 후 선택된 값 초기화
        this.loading = false // 에러 처리 후 로딩 종료
      }).finally(() => {
        this.loading = false
      })
    },
    updateSelectedId (value) {
      this.resetSelection()
      this.selectedClusterId = value
      this.fetchSecDRClusterInfoList()
    },
    resetSelection () {
      this.form.secDrClusterOfferings = null
      this.form.secDrClusterNetworkList = null
    },
    cancel () {
      this.$emit('cancel')
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        this.loading = true
        const params = {
          virtualmachineid: this.resource.id,
          drclustername: values.drCluster,
          serviceofferingname: values.secDrClusterOfferings,
          networkname: values.secDrClusterNetworkList
        }
        api('createDisasterRecoveryClusterVm', params).then(json => {
          this.$notification.success({
            message: this.$t('label.add.disaster.recovery.cluster.vm'),
            description: this.$t('label.add.disaster.recovery.cluster.vm')
          })
          this.closeModal()
          this.parentFetchData()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
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
