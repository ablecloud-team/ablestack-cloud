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
    <a-alert
      v-if="disableSettings"
      banner
      :message="$t('message.action.settings.warning.vm.running')" />
    <div v-else>
      <div v-show="!showAddDetail">
        <a-button
          type="dashed"
          style="width: 100%"
          :disabled="!(isAdminOrOwner() && hasSettingUpdatePermission())"
          @click="onShowAddDetail">
          <template #icon><plus-outlined /></template>
          {{ $t('label.add.setting') }}
        </a-button>
      </div>
      <div v-show="showAddDetail">
        <a-input-group
          type="text"
          compact>
          <a-auto-complete
            class="detail-input"
            ref="keyElm"
            :filterOption="(input, option) => filterOption(input, option, 'key')"
            v-model:value="newKey"
            :options="detailKeys"
            :placeholder="$t('label.name')"
            @change="e => onAddInputChange(e, 'newKey')" />
          <a-input
            class="tag-disabled-input"
            style=" width: 30px; border-left: 0; pointer-events: none; text-align: center"
            placeholder="="
            disabled />
          <a-auto-complete
            class="detail-input"
            :filterOption="(input, option) => filterOption(input, option, 'value')"
            v-model:value="newValue"
            :options="detailValues"
            :placeholder="$t('label.value')"
            @change="e => onAddInputChange(e, 'newValue')" />
          <a-input
            v-if="newKey === 'video.hardware'"
            v-model:value="videoHardwareCount"
            type="number"
            :min="1"
            :max="4"
            :placeholder="$t('label.count')"
            style="width: 80px"
            @change="e => onVideoHardwareCountChange(e.target.value)" />
          <tooltip-button :tooltip="$t('label.add.setting')" :shape="null" icon="check-outlined" @onClick="addDetail" buttonClass="detail-button" />
          <tooltip-button :tooltip="$t('label.cancel')" :shape="null" icon="close-outlined" @onClick="closeDetail" buttonClass="detail-button" />
        </a-input-group>
        <p v-if="error" style="color: red"> {{ $t(error) }} </p>
      </div>
    </div>
    <a-list size="large">
      <a-list-item :key="item.name" v-for="item in displayedDetails">
        <a-list-item-meta>
          <template #title>
            {{ item.name }}
          </template>
          <template #description>
            <div v-if="item.edit" style="display: flex">
              <a-auto-complete
                style="width: 100%"
                v-model:value="item.value"
                :options="getDetailOptions(detailOptions[item.name])"
                @change="val => handleInputChange(val, item.name)"
                @pressEnter="e => updateDetail(item.name)" />
              <tooltip-button
                buttonClass="edit-button"
                :tooltip="$t('label.cancel')"
                @onClick="hideEditDetail(item.name)"
                v-if="item.edit"
                iconType="close-circle-two-tone"
                iconTwoToneColor="#f5222d" />
              <tooltip-button
                buttonClass="edit-button"
                :tooltip="$t('label.ok')"
                @onClick="updateDetail(item.name)"
                v-if="item.edit"
                iconType="check-circle-two-tone"
                iconTwoToneColor="#52c41a" />
            </div>
            <span v-else style="word-break: break-all">{{ item.value }}</span>
          </template>
        </a-list-item-meta>
        <template #actions>
          <div
            v-if="!disableSettings && isAdminOrOwner() && allowEditOfDetail(item.name) && hasSettingUpdatePermission()">
            <tooltip-button
              :tooltip="$t('label.edit')"
              icon="edit-outlined"
              :disabled="deployasistemplate === true"
              v-if="!item.edit"
              @onClick="showEditDetail(item.name)" />
          </div>
          <div
            v-if="!disableSettings && isAdminOrOwner() && allowEditOfDetail(item.name) && hasSettingUpdatePermission()">
            <a-popconfirm
              :title="`${$t('label.delete.setting')}?`"
              @confirm="deleteDetail(item.name)"
              :okText="$t('label.yes')"
              :cancelText="$t('label.no')"
              placement="left"
            >
              <tooltip-button :tooltip="$t('label.delete')" :disabled="deployasistemplate === true" type="primary" :danger="true" icon="delete-outlined" />
            </a-popconfirm>
          </div>
        </template>
      </a-list-item>
    </a-list>
  </a-spin>
</template>

<script>
import { api } from '@/api'
import TooltipButton from '@/components/widgets/TooltipButton'

export default {
  components: { TooltipButton },
  name: 'DetailSettings',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      details: [],
      detailOptions: {},
      showAddDetail: false,
      disableSettings: false,
      newKey: '',
      newValue: '',
      loading: false,
      resourceType: 'UserVm',
      deployasistemplate: false,
      error: false,
      videoHardwareCount: '1'
    }
  },
  watch: {
    resource: {
      deep: true,
      handler (newItem) {
        this.updateResource(newItem)
      }
    }
  },
  computed: {
    detailKeys () {
      // video.hardware만 표시, _2, _3, _4는 모달에서 처리
      const keys = Object.keys(this.detailOptions).map(key => {
        return { value: key }
      })

      return keys
    },
    detailValues () {
      if (!this.newKey) {
        return []
      }

      if (!Array.isArray(this.detailOptions[this.newKey])) {
        if (this.detailOptions[this.newKey]) {
          return { value: this.detailOptions[this.newKey] }
        } else {
          return []
        }
      }
      return this.detailOptions[this.newKey].map(value => {
        return { value: value }
      })
    },
    displayedDetails () {
      // 모든 details 표시 (이미 updateResource에서 정렬됨)
      console.log('=== DISPLAYED DETAILS ===')
      this.details.forEach((d, idx) => {
        console.log(`  Display[${idx}] ${d.name} = ${d.value}`)
      })
      return this.details
    },
    videoHardwareOptions () {
      if (this.detailOptions && this.detailOptions['video.hardware']) {
        return this.detailOptions['video.hardware']
      }
      return ['cirrus', 'vga', 'qxl', 'virtio']
    }
  },
  created () {
    this.updateResource(this.resource)
  },
  methods: {
    filterOption (input, option, filterType) {
      if ((filterType === 'key' && !this.newKey) ||
        (filterType === 'value' && !this.newValue)) {
        return true
      }

      return (
        option.value.toUpperCase().indexOf(input.toUpperCase()) >= 0
      )
    },
    updateResource (resource) {
      this.details = []
      if (!resource) {
        return
      }
      this.resourceType = this.$route.meta.resourceType
      if (resource.details) {
        // video.hardware와 video.ram을 올바른 순서로 정렬
        const detailKeys = Object.keys(resource.details)

        const getVideoNumber = (key) => {
          if (key === 'video.hardware' || key === 'video.ram') {
            return 0 // 숫자 없는 기본값
          }
          const match = key.match(/video\.(hardware|ram)(\d+)/)
          return match ? parseInt(match[2]) : 0
        }

        const videoHardwareKeys = detailKeys
          .filter(k => k.startsWith('video.hardware'))
          .sort((a, b) => getVideoNumber(a) - getVideoNumber(b))

        const videoRamKeys = detailKeys
          .filter(k => k.startsWith('video.ram'))
          .sort((a, b) => getVideoNumber(a) - getVideoNumber(b))

        const otherKeys = detailKeys.filter(k => !k.startsWith('video.'))

        // video.hardware, video.ram, 기타 순서로 배열 생성
        const orderedKeys = [...videoHardwareKeys, ...videoRamKeys, ...otherKeys]

        console.log('Ordered keys:', orderedKeys)

        this.details = orderedKeys.map(k => {
          return { name: k, value: resource.details[k], edit: false }
        })
      }
      api('listDetailOptions', { resourcetype: this.resourceType, resourceid: resource.id }).then(json => {
        this.detailOptions = json.listdetailoptionsresponse.detailoptions.details
      })
      this.disableSettings = (this.$route.meta.name === 'vm' && resource.state !== 'Stopped')
      api('listTemplates', { templatefilter: 'all', id: resource.templateid }).then(json => {
        this.deployasistemplate = json.listtemplatesresponse.template[0].deployasis
      })
    },
    allowEditOfDetail (name) {
      if (this.resource.readonlydetails) {
        if (this.resource.readonlydetails.split(',').map(item => item.trim()).includes(name)) {
          return false
        }
      }
      return true
    },
    showEditDetail (name) {
      const item = this.details.find(d => d.name === name)
      if (item) {
        item.edit = true
        item.originalValue = item.value
      }
    },
    hideEditDetail (name) {
      const item = this.details.find(d => d.name === name)
      if (item) {
        item.edit = false
        item.value = item.originalValue
      }
    },
    handleInputChange (val, name) {
      const item = this.details.find(d => d.name === name)
      if (item) {
        item.value = val
      }
    },
    getDetailOptions (values) {
      if (!values) {
        return
      }
      var data = values.map(value => { return { value: value } })
      return data
    },
    onAddInputChange (val, obj) {
      this.error = false
      this[obj] = val

      // video.hardware 선택 시 개수 초기화
      if (obj === 'newKey' && val === 'video.hardware') {
        const existingCount = this.details.filter(item => item.name.startsWith('video.hardware')).length
        this.videoHardwareCount = existingCount > 0 ? existingCount.toString() : '1'
      }
    },
    onVideoHardwareCountChange (val) {
      this.videoHardwareCount = val
    },
    isAdminOrOwner () {
      return ['Admin'].includes(this.$store.getters.userInfo.roletype) ||
        (this.resource.domainid === this.$store.getters.userInfo.domainid && this.resource.account === this.$store.getters.userInfo.account) ||
        this.resource.project && this.resource.projectid === this.$store.getters.project.id
    },
    getDetailsParam (details) {
      var params = {}
      var filteredDetails = details
      if (this.resource.readonlydetails && filteredDetails) {
        filteredDetails = []
        var readOnlyDetailNames = this.resource.readonlydetails.split(',').map(item => item.trim())
        for (var detail of this.details) {
          if (!readOnlyDetailNames.includes(detail.name)) {
            filteredDetails.push(detail)
          }
        }
      }
      if (filteredDetails.length === 0) {
        params.cleanupdetails = true
      } else {
        filteredDetails.forEach(function (item, index) {
          params['details[0].' + item.name] = item.value
        })
      }
      return params
    },
    runApi () {
      var apiName = ''
      if (this.resourceType === 'UserVm') {
        apiName = 'updateVirtualMachine'
      } else if (this.resourceType === 'Template') {
        apiName = 'updateTemplate'
      } else if (this.resourceType === 'DisasterRecoveryCluster') {
        apiName = 'updateDisasterRecoveryCluster'
      }
      if (!(apiName in this.$store.getters.apis)) {
        this.$notification.error({
          message: this.$t('error.execute.api.failed') + ' ' + apiName,
          description: this.$t('message.user.not.permitted.api')
        })
        return
      }

      var params = { id: this.resource.id, drclusterstatus: this.resource.drclusterstatus, mirroringagentstatus: this.resource.mirroringagentstatus }
      params = Object.assign(params, this.getDetailsParam(this.details))
      this.loading = true
      api(apiName, params).then(json => {
        var details = {}
        if (this.resourceType === 'UserVm' && json.updatevirtualmachineresponse.virtualmachine.details) {
          details = json.updatevirtualmachineresponse.virtualmachine.details
        } else if (this.resourceType === 'Template' && json.updatetemplateresponse.template.details) {
          details = json.updatetemplateresponse.template.details
        } else if (this.resourceType === 'DisasterRecoveryCluster' && json.updatedisasterrecoveryclusterresponse.disasterrecoverycluster.details) {
          details = json.updatedisasterrecoveryclusterresponse.disasterrecoverycluster.details
        }

        // 서버 응답을 올바른 순서로 정렬
        const detailKeys = Object.keys(details)

        // 번호 추출 함수 (숫자 없으면 0으로 간주)
        const getVideoNumber = (key) => {
          if (key === 'video.hardware' || key === 'video.ram') {
            return 0 // 숫자 없는 기본값
          }
          const match = key.match(/video\.(hardware|ram)(\d+)/)
          return match ? parseInt(match[2]) : 0
        }

        const videoHardwareKeys = detailKeys
          .filter(k => k.startsWith('video.hardware'))
          .sort((a, b) => getVideoNumber(a) - getVideoNumber(b))

        const videoRamKeys = detailKeys
          .filter(k => k.startsWith('video.ram'))
          .sort((a, b) => getVideoNumber(a) - getVideoNumber(b))

        const otherKeys = detailKeys.filter(k => !k.startsWith('video.'))

        // video.hardware, video.ram, 기타 순서로 배열 생성
        const orderedKeys = [...videoHardwareKeys, ...videoRamKeys, ...otherKeys]

        console.log('API Response - Ordered keys:', orderedKeys)

        this.details = orderedKeys.map(k => {
          return { name: k, value: details[k], edit: false }
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(f => {
        this.loading = false
        this.showAddDetail = false
        this.newKey = ''
        this.newValue = ''
      })
    },
    addDetail () {
      if (this.newKey === '') {
        this.error = this.$t('message.error.provide.setting')
        return
      }

      // video.hardware 처리
      if (this.newKey === 'video.hardware') {
        if (this.newValue === '') {
          this.error = this.$t('message.error.provide.setting')
          return
        }

        // 개수 확인
        const count = parseInt(this.videoHardwareCount)
        if (isNaN(count) || count < 1 || count > 4) {
          this.error = this.$t('message.video.hardware.count.error')
          return
        }

        // 기존 video.hardware 및 video.ram 관련 항목 모두 제거
        this.details = this.details.filter(item =>
          !item.name.startsWith('video.hardware') && !item.name.startsWith('video.ram')
        )

        // 개수만큼 video.hardware 항목 추가
        for (let i = 1; i <= count; i++) {
          const key = i === 1 ? 'video.hardware' : `video.hardware${i}`
          this.details.push({
            name: key,
            value: this.newValue,
            edit: false
          })
        }

        // 개수만큼 video.ram 항목 추가 (각 디바이스당 16384)
        for (let i = 1; i <= count; i++) {
          const ramKey = i === 1 ? 'video.ram' : `video.ram${i}`
          this.details.push({
            name: ramKey,
            value: '16384',
            edit: false
          })
        }
      } else {
        // 일반 설정은 값이 필요함
        if (this.newValue === '') {
          this.error = this.$t('message.error.provide.setting')
          return
        }

        if (!this.allowEditOfDetail(this.newKey)) {
          this.error = this.$t('error.unable.to.proceed')
          return
        }

        this.details.push({ name: this.newKey, value: this.newValue })
      }

      this.error = false
      this.runApi()
    },
    updateDetail (name) {
      const item = this.details.find(d => d.name === name)
      if (item) {
        item.edit = false
      }
      this.runApi()
    },
    deleteDetail (name) {
      console.log('=== DELETE DETAIL DEBUG ===')
      console.log('Trying to delete:', name)
      console.log('Current details array:')
      this.details.forEach((d, idx) => {
        console.log(`  [${idx}] ${d.name} = ${d.value}`)
      })

      const index = this.details.findIndex(d => d.name === name)
      console.log('Found at index:', index)

      if (index !== -1) {
        const deletedItem = this.details[index]
        console.log('Deleting item:', deletedItem.name, '=', deletedItem.value)
        this.details.splice(index, 1)

        console.log('After deletion:')
        this.details.forEach((d, idx) => {
          console.log(`  [${idx}] ${d.name} = ${d.value}`)
        })
      } else {
        console.error('ERROR: Could not find item with name:', name)
      }

      this.runApi()
    },
    updateVideoRam () {
      // video.hardware 개수를 세어서 video.ram 자동 설정
      const videoHardwareCount = this.details.filter(item =>
        item.name.startsWith('video.hardware')
      ).length

      // 기존 video.ram 관련 항목 모두 제거
      this.details = this.details.filter(item =>
        !item.name.startsWith('video.ram')
      )

      // video.hardware가 있으면 개수만큼 video.ram 자동 추가
      if (videoHardwareCount > 0) {
        for (let i = 1; i <= videoHardwareCount; i++) {
          const ramKey = i === 1 ? 'video.ram' : `video.ram${i}`
          this.details.push({
            name: ramKey,
            value: '16384',
            edit: false
          })
        }
      }
    },
    onShowAddDetail () {
      this.showAddDetail = true
      setTimeout(() => {
        this.$refs.keyElm.focus()
      })
    },
    closeDetail () {
      this.newKey = ''
      this.newValue = ''
      this.error = false
      this.showAddDetail = false
      this.videoHardwareCount = '1'
    },
    hasSettingUpdatePermission () {
      return (
        (this.resourceType === 'Template' && 'updateTemplate' in this.$store.getters.apis) ||
        (this.resourceType === 'UserVm' && 'updateVirtualMachine' in this.$store.getters.apis) ||
        (this.resourceType === 'DisasterRecoveryCluster' && 'updateDisasterRecoveryCluster' in this.$store.getters.apis)
      )
    }
  }
}
</script>

<style scoped lang="less">
.detail-input {
  width: calc(calc(100% / 2) - 45px);
}

.detail-button {
  width: 30px;
}
</style>
