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
  <div class="api-docs">
    <resource-layout>
      <template #left>
        <a-card :bordered="false">
          <a-auto-complete
            v-model:value="query"
            :options="options.filter(value => value.value.toLowerCase().includes(query.toLowerCase()))"
            style="width: 100%"
            >
            <a-input-search
                size="default"
                :placeholder="$t('label.search')"
                v-model:value="query"
                allow-clear
                enter-button
                >
                <template #prefix><search-outlined /></template>
            </a-input-search>
          </a-auto-complete>
          <a-list style="margin-top: 12px; height:580px; overflow-y: scroll;" size="small" :data-source="Object.keys($store.getters.apis).sort()">
            <template #renderItem="{ item }">
              <a-list-item
                v-if="item.toLowerCase().includes(query.toLowerCase())"
                class="api-docs-command"
                :class="{ 'selected-item': selectedApi === item }"
                tabindex="0"
                :aria-current="selectedApi === item ? 'true' : undefined"
                @click="showApi(item)"
                @keydown.enter.prevent="showApi(item)"
                @keydown.space.prevent="showApi(item)">
                {{ item }} <a-tag v-if="$store.getters.apis[item].isasync" class="api-docs-tag">async</a-tag>
              </a-list-item>
            </template>
          </a-list>
          <a-divider style="margin-bottom: 12px" />
          <span>{{ Object.keys($store.getters.apis).length }} {{ $t('label.api.docs.count') }}</span>
        </a-card>
      </template>
      <template #right>
        <a-card
          class="spin-content"
          :bordered="true"
          style="width: 100%; overflow-x: auto">
          <span v-if="selectedApi && selectedApi in $store.getters.apis">
            <h2>{{ selectedApi }}
              <a-tag v-if="$store.getters.apis[selectedApi].isasync" class="api-docs-tag">Asynchronous API</a-tag>
              <a-tag v-if="$store.getters.apis[selectedApi].since" class="api-docs-tag">Since {{ $store.getters.apis[selectedApi].since }}</a-tag>
              <tooltip-button
                tooltipPlacement="right"
                :tooltip="$t('label.copy') + ' ' + selectedApi"
                icon="CopyOutlined"
                type="outlined"
                size="small"
                @onClick="$message.success($t('label.copied.clipboard'))"
                :copyResource="selectedApi" />
            </h2>
            <p>{{ $store.getters.apis[selectedApi].description }}</p>
            <h3>{{ $t('label.request') }} {{ $t('label.params') }}:</h3>
            <a-table
              class="api-docs-fields"
              :columns="[{title: $t('label.name'), dataIndex: 'name'}, {title: $t('label.required'), dataIndex: 'required'}, {title: $t('label.type'), dataIndex: 'type'}, {title: $t('label.description'), dataIndex: 'description'}]"
              :data-source="selectedParams"
              :pagination="false"
              size="small">
              <template #bodyCell="{text, column, record}">
                <a-tag v-if="record.since && column.dataIndex === 'description'" class="api-docs-tag">Since {{ record.since }}</a-tag>
                <span v-if="record.required === true"><strong>{{ text }}</strong></span>
                <span v-else>{{ text }}</span>
              </template>
            </a-table>
            <br/>
            <h3>{{ $t('label.response') }} {{ $t('label.params') }}:</h3>
            <a-alert v-if="responseError" type="error" :message="responseError" show-icon>
              <template #action>
                <a-button size="small" @click="showApi(selectedApi)">{{ $t('label.retry') }}</a-button>
              </template>
            </a-alert>
            <a-table
              class="api-docs-fields"
              :columns="[{title: $t('label.name'), dataIndex: 'name'}, {title: $t('label.type'), dataIndex: 'type'}, {title: $t('label.description'), dataIndex: 'description'}]"
              :data-source="selectedResponse"
              :loading="loadingResponse"
              row-key="name"
              :pagination="false"
              size="small" />
          </span>
          <span v-else>
            <a-alert
              :message="$t('label.api.docs')"
              type="info"
              show-icon
              banner>
              <template #description>
                <a href="https://docs.cloudstack.apache.org/en/latest/developersguide/dev.html" target="_blank">{{ $t('label.api.docs.description') }}</a>
              </template>
            </a-alert>
            <a-result
              status="success"
              :title="$t('label.download') + ' ' + $t('label.app.name') + ' MOLD CLI'"
              sub-title="For API automation and orchestration"
            >
              <template #extra>
                <a-button type="primary"><a href="https://images.ablecloud.io/mold/ablestack-mold-cli/" target="_blank">{{ $t('label.download') }} CLI</a></a-button>
                <a-button><a href="https://github.com/apache/cloudstack-cloudmonkey/wiki/Usage" target="_blank">{{ $t('label.open.documentation') }} (CLI)</a></a-button>
                <br/>
                <br/>
                <div v-if="showKeys">
                  <key-outlined />
                  <strong>
                    {{ $t('label.apikey') }}
                    <tooltip-button
                      tooltipPlacement="right"
                      :tooltip="$t('label.copy') + ' ' + $t('label.apikey')"
                      icon="CopyOutlined"
                      type="dashed"
                      size="small"
                      @onClick="$message.success($t('label.copied.clipboard'))"
                      :copyResource="userkeys.apikey" />
                  </strong>
                  <div>
                    {{ userkeys.apikey.substring(0, 20) }}...
                  </div>
                  <br/>
                  <lock-outlined />
                  <strong>
                    {{ $t('label.secretkey') }}
                    <tooltip-button
                      tooltipPlacement="right"
                      :tooltip="$t('label.copy') + ' ' + $t('label.secretkey')"
                      icon="CopyOutlined"
                      type="dashed"
                      size="small"
                      @onClick="$message.success($t('label.copied.clipboard'))"
                      :copyResource="userkeys.secretkey" />
                  </strong>
                  <div>
                    {{ userkeys.secretkey.substring(0, 20) }}...
                  </div>
                </div>
              </template>
            </a-result>
          </span>
        </a-card>
      </template>
    </resource-layout>
  </div>
</template>

<script>
import { getAPI } from '@/api'

import ResourceLayout from '@/layouts/ResourceLayout'
import TooltipButton from '@/components/widgets/TooltipButton'

const documentedFields = fields => Array.isArray(fields) ? fields.filter(field => field?.name) : []

export default {
  name: 'ApiDocsPlugin',
  components: {
    ResourceLayout,
    TooltipButton
  },
  data () {
    return {
      query: '',
      selectedApi: '',
      selectedParams: [],
      selectedResponse: [],
      loadingResponse: false,
      responseError: '',
      responseRequestId: 0,
      responseCache: {},
      showKeys: false,
      userkeys: {},
      options: [
        { value: 'VirtualMachine', label: 'Instance' },
        { value: 'Kubernetes', label: 'Kubernetes' },
        { value: 'Volume', label: 'Volume' },
        { value: 'Snapshot', label: 'Snapshot' },
        { value: 'Backup', label: 'Backup' },
        { value: 'Network', label: 'Network' },
        { value: 'IpAddress', label: 'IP Address' },
        { value: 'VPN', label: 'VPN' },
        { value: 'VPC', label: 'VPC' },
        { value: 'NetworkACL', label: 'Network ACL' },
        { value: 'SecurityGroup', label: 'Security Group' },
        { value: 'Template', label: 'Template' },
        { value: 'ISO', label: 'ISO' },
        { value: 'SSH', label: 'SSH' },
        { value: 'Project', label: 'Project' },
        { value: 'Account', label: 'Account' },
        { value: 'User', label: 'User' },
        { value: 'Event', label: 'Event' },
        { value: 'Offering', label: 'Offering' },
        { value: 'Zone', label: 'Zone' }
      ]
    }
  },
  computed: {
    documentationContext () {
      return JSON.stringify([this.$store.state?.user?.token, this.$store.state?.user?.discoveryGeneration,
        this.$store.getters.userInfo?.id, this.$store.getters.project?.id])
    }
  },
  watch: {
    documentationContext () {
      this.responseRequestId += 1
      this.selectedApi = ''
      this.selectedParams = []
      this.selectedResponse = []
      this.loadingResponse = false
      this.responseError = ''
      this.responseCache = {}
    }
  },
  beforeUnmount () {
    this.responseRequestId += 1
  },
  created () {
    if (!('getUserKeys' in this.$store.getters.apis)) {
      return
    }
    getAPI('getUserKeys', { id: this.$store.getters.userInfo.id }).then(json => {
      this.userkeys = json.getuserkeysresponse.userkeys
      if (this.userkeys && this.userkeys.secretkey) {
        this.showKeys = true
      }
    })
  },
  methods: {
    isCurrentResponse (api, requestId, context) {
      return requestId === this.responseRequestId && context === this.documentationContext &&
        this.selectedApi === api && Object.prototype.hasOwnProperty.call(this.$store.getters.apis, api)
    },
    async showApi (api) {
      const requestId = ++this.responseRequestId
      const context = this.documentationContext
      const metadata = this.$store.getters.apis[api]
      this.selectedApi = api
      this.selectedParams = []
      this.selectedResponse = []
      this.loadingResponse = false
      this.responseError = ''
      if (!metadata) return
      this.selectedParams = documentedFields(metadata.params).slice()
        .sort((a, b) => Number(b.required === true) - Number(a.required === true) || a.name.localeCompare(b.name))
      const cachedResponse = Array.isArray(metadata.response) ? metadata.response : this.responseCache[api]
      if (Array.isArray(cachedResponse)) {
        this.selectedResponse = documentedFields(cachedResponse)
        return
      }
      // Login stores request metadata only; load response documentation on selection.
      this.loadingResponse = true
      try {
        const json = await getAPI('listApis', { name: api }, { preserveOnFailure: true, timeout: 15000 })
        if (!this.isCurrentResponse(api, requestId, context)) return
        const definition = json.listapisresponse?.api?.find(item => item.name === api)
        if (!definition) throw new Error(this.$t('error.fetching.data'))
        const fields = documentedFields(definition.response)
        this.responseCache[api] = fields
        this.selectedResponse = fields
      } catch (error) {
        if (!this.isCurrentResponse(api, requestId, context)) return
        this.responseError = error.response?.data?.listapisresponse?.errortext || error.message || this.$t('error.fetching.data')
      } finally {
        if (this.isCurrentResponse(api, requestId, context)) this.loadingResponse = false
      }
    }
  }
}
</script>

<style scoped>
.api-docs :deep(.ant-card),
.api-docs :deep(h2),
.api-docs :deep(h3),
.api-docs :deep(.ant-result-title) {
  color: var(--ui-text-primary);
}

.api-docs :deep(.ant-result-subtitle),
.api-docs p {
  color: var(--ui-text-secondary);
}

.api-docs :deep(.ant-card) {
  background: var(--ui-bg-surface);
}

.api-docs-command {
  padding-inline-start: 12px;
  color: var(--ui-text-primary);
  cursor: pointer;
}

.api-docs-command:hover {
  background: var(--ui-bg-hover);
}

.api-docs .api-docs-command.selected-item {
  background: var(--ui-bg-selected);
}

.api-docs-command:focus-visible {
  outline: 2px solid var(--ui-focus);
  outline-offset: -2px;
}

.api-docs :deep(.api-docs-fields .ant-table-thead > tr > th) {
  color: var(--ui-text-primary);
  background: var(--ui-bg-elevated);
  border-color: var(--ui-border);
}

.api-docs :deep(.api-docs-fields .ant-table-tbody > tr > td) {
  color: var(--ui-text-primary);
  background: var(--ui-bg-surface);
  border-color: var(--ui-border);
}

.api-docs :deep(.api-docs-fields .ant-table-tbody > tr:hover > td) {
  background: var(--ui-bg-hover);
}

.api-docs-tag {
  color: var(--ui-info-text);
  background: var(--ui-info-bg);
  border-color: var(--ui-info-border);
}
</style>
