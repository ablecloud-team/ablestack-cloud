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
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <a-button
      :disabled="!('applyTungstenFabricTag' in $store.getters.apis)"
      type="dashed"
      style="width: 100%; margin-bottom: 15px"
      @click="onShowAction">
      <template #icon><plus-outlined /></template>
      {{ $t('label.apply.tungsten.tag') }}
    </a-button>
    <detail-tab-table
      size="small"
      :loading="fetchLoading"
      :columns="columns"
      :dataSource="dataSource"
      :rowKey="listRowKey"
      :pagination="false">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'policy'">
        <span v-if="record.policy.length > 0">{{ record.policy[0].name }}</span>
        </template>
        <template v-if="column.key === 'actions'">
          <a-popconfirm
            v-if="'removeTungstenFabricTag' in $store.getters.apis"
            placement="topRight"
            :title="$t('message.delete.tungsten.tag')"
            :ok-text="$t('label.yes')"
            :cancel-text="$t('label.no')"
            :loading="deleteLoading"
            @confirm="deleteRule(record)"
          >
            <tooltip-button
              :tooltip="$t('label.delete.tag')"
              danger
              type="primary"
              icon="delete-outlined" />
          </a-popconfirm>
        </template>
      </template>
    </detail-tab-table>

    <div style="display: block; text-align: right; margin-top: 10px;">
      <detail-tab-pagination
        size="small"
        :current="page"
        :pageSize="pageSize"
        :total="itemCount"
        :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
        :pageSizeOptions="pageSizeOptions"
        @change="onChangePage"
        @showSizeChange="onChangePageSize"
        showSizeChanger>
        <template #buildOptionText="props">
          <span>{{ props.value }} / {{ $t('label.page') }}</span>
        </template>
      </detail-tab-pagination>
    </div>

    <a-modal
      v-if="showAction"
      :visible="showAction"
      :title="$t('label.apply.tungsten.tag')"
      :maskClosable="false"
      :footer="null"
      @cancel="showAction = false">
      <div v-ctrl-enter="handleSubmit">
        <a-form :ref="formRef" :model="form" :rules="rules" layout="vertical">
          <a-form-item name="taguuid" ref="taguuid">
            <template #label>
              <tooltip-label :title="$t('label.taguuid')" :tooltip="apiParams.taguuid.description"/>
            </template>
            <a-select
              v-focus="true"
              :loading="tags.loading"
              v-model:value="form.taguuid"
              :placeholder="apiParams.taguuid.description">
              <a-select-option v-for="item in tags.opts" :key="item.uuid">{{ item.name }}</a-select-option>
            </a-select>
          </a-form-item>
        </a-form>

        <div :span="24" class="action-button">
          <a-button :loading="actionLoading" @click="() => { showAction = false }">{{ this.$t('label.cancel') }}</a-button>
          <a-button :loading="actionLoading" type="primary" ref="submit" @click="handleSubmit">{{ this.$t('label.ok') }}</a-button>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import { mixinDevice } from '@/utils/mixin.js'
import TooltipButton from '@/components/widgets/TooltipButton'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'TungstenFabricPolicyTag',
  components: {
    TooltipButton,
    TooltipLabel
  },
  mixins: [listRefreshMixin(['fetchData']), mixinDevice],
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      showAction: false,
      tagSelected: null,
      tags: {
        loading: false,
        opts: []
      },
      zoneId: null,
      fetchLoading: false,
      actionLoading: false,
      deleteLoading: false,
      dataSource: [],
      itemCount: 0,
      page: 1,
      pageSize: this.$store.getters.defaultListViewPageSize,
      columns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'name',
          key: 'name'
        },
        {
          title: this.$t('label.policy'),
          dataIndex: 'policy',
          key: 'policy'
        },
        {
          title: this.$t('label.actions'),
          dataIndex: 'actions',
          key: 'actions',
          width: 70
        }
      ]
    }
  },
  computed: {
    pageSizeOptions () {
      const sizes = [20, 50, 100, 200, this.$store.getters.defaultListViewPageSize]
      if (this.device !== 'desktop') {
        sizes.unshift(10)
      }
      return [...new Set(sizes)].sort(function (a, b) {
        return a - b
      }).map(String)
    }
  },
  watch: {
    resource () {
      this.fetchData()
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('applyTungstenFabricTag')
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
        taguuid: [{ required: true, message: this.$t('message.error.select') }]
      })
    },
    fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      if (!this.resource.uuid || !('zoneid' in this.$route.query)) {
        return
      }
      this.zoneId = this.$route.query.zoneid

      const params = {}
      params.zoneid = this.zoneId
      params.policyuuid = this.resource.uuid

      this.fetchLoading = !listRequest.loaded
      return getAPI('listTungstenFabricTag', params).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.dataSource = json?.listtungstenfabrictagresponse?.tag || []
        this.itemCount = json?.listtungstenfabrictagresponse?.count || 0
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.fetchLoading = false
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.fetchLoading = false
      })
    },
    onShowAction () {
      this.showAction = true

      this.tags.loading = true
      this.tags.opts = []
      getAPI('listTungstenFabricTag', { zoneid: this.zoneId }).then(json => {
        const listTags = json?.listtungstenfabrictagresponse?.tag || []

        this.tags.opts = listTags.filter(item => {
          const tagExist = this.dataSource.findIndex(tag => tag.uuid === item.uuid)
          return tagExist === -1
        })

        this.tagSelected = this.tags.opts[0]?.uuid || null
      }).finally(() => { this.tags.loading = false })
    },
    handleSubmit () {
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)

        const params = {}
        params.zoneid = this.zoneId
        params.policyuuid = this.resource.uuid
        params.taguuid = values.taguuid

        const tag = this.tags.opts.filter(item => item.uuid === values.taguuid)
        const description = tag.length > 0 ? tag[0].name : values.taguuid

        this.actionLoading = true
        postAPI('applyTungstenFabricTag', params).then(json => {
          this.$pollJob({
            jobId: json.applytungstenfabrictagresponse.jobid,
            title: this.$t('label.apply.tungsten.tag'),
            description: description,
            successMessage: `${this.$t('message.success.apply.tungsten.tag')}`,
            successMethod: () => {
              this.fetchData()
            },
            errorMessage: this.$t('message.error.apply.tungsten.tag'),
            loadingMessage: this.$t('message.loading.apply.tungsten.tag'),
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              this.fetchData()
            },
            action: {
              isFetchData: false
            }
          })
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.showAction = false
          this.actionLoading = false
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      }).finally(() => {
        this.actionLoading = false
      })
    },
    deleteRule (record) {
      const params = {}
      params.zoneid = this.zoneId
      params.policyuuid = this.resource.uuid
      params.taguuid = record.uuid

      this.deleteLoading = true
      postAPI('removeTungstenFabricTag', params).then(json => {
        this.$pollJob({
          jobId: json.removetungstenfabrictagresponse.jobid,
          title: this.$t('label.delete.rule'),
          description: record.name || record.uuid,
          successMessage: `${this.$t('message.success.delete.tungsten.tag')} ${record.name || record.uuid}`,
          successMethod: () => {
            this.fetchData()
          },
          errorMessage: this.$t('message.error.delete.tungsten.tag'),
          loadingMessage: this.$t('message.loading.delete.tungsten.tag'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.fetchData()
          },
          action: {
            isFetchData: false
          }
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => { this.deleteLoading = false })
    },
    onChangePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    },
    onChangePageSize (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    }
  }
}
</script>

<style scoped>
</style>
