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
  <section class="scoped-settings">
    <resource-table-toolbar
      v-model:search="search"
      searchable
      :loading="loading"
      @refresh="refresh" />
    <a-alert v-if="loadFailed" type="error" show-icon :message="$t('message.error.loading.setting')" />
    <a-alert
      v-if="scope.id && scope.type === 'account'"
      type="info"
      show-icon
      :message="$t('message.account.settings.scope', { account: resource.name, domain: resource.domainpath || resource.domain || resource.domainid })" />
    <a-alert
      v-if="scope.id && scope.type === 'domain'"
      type="info"
      show-icon
      :message="$t('message.domain.settings.scope', { path: resource.path || resource.name })"
      :description="$t('message.domain.settings.inheritance')" />
    <configuration-hierarchy
      :config="pageRows"
      :korean-catalog="koreanCatalog"
      :loading="loading && !items.length"
      :count="filteredRows.length"
      :page="page"
      :pagesize="pageSize"
      @change-page="changePage"
      :row-actions="settingActions"
      @show-setting="open('details', $event)">
      <template #actions="{ record }">
        <a-space size="small">
          <a-button
            v-if="canUpdate"
            type="link"
            size="small"
            :disabled="loading || loadFailed"
            @click="open('edit', record)">
            {{ $t('label.edit') }}
          </a-button>
          <a-button
            v-if="canReset"
            shape="circle"
            size="small"
            :aria-label="$t('label.reset.config.value')"
            :disabled="loading || loadFailed"
            @click="open('reset', record)">
            <template #icon><reload-outlined /></template>
          </a-button>
        </a-space>
      </template>
    </configuration-hierarchy>
    <mold-dialog
      v-if="dialog"
      size="md"
      :form-actions="dialog.mode !== 'details'"
      focus-guard
      :title="$t(dialogTitle)"
      @cancel="dialog = null">
      <a-descriptions size="small" layout="vertical" :column="{ xs: 1, sm: 3 }">
        <a-descriptions-item :label="$t('label.global.setting.key')">
          {{ localizedText(dialog.record, 'displaytext') }}<br />{{ dialog.record.name }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.global.setting.current')">
          {{ formatConfigurationValue(dialog.record) }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.global.setting.default')">
          {{ formatConfigurationValue(dialog.record, 'defaultvalue') }}
        </a-descriptions-item>
      </a-descriptions>
      <p v-if="localizedText(dialog.record, 'description')" class="configuration-description">
        {{ localizedText(dialog.record, 'description') }}
      </p>
      <a-alert v-if="dialog.mode === 'reset'" type="warning" show-icon :message="$t('message.scoped.setting.reset')" />
      <configuration-value
        v-if="dialog.mode !== 'details'"
        :scope="dialog.scope"
        :configrecord="dialog.record"
        :dialog-mode="dialog.mode"
        @saved="handleSaved"
        @cancel="dialog = null" />
    </mold-dialog>
  </section>
</template>

<script>
import { ReloadOutlined } from '@ant-design/icons-vue'
import { getAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import ResourceTableToolbar from '@/components/view/ResourceTableToolbar'
import ConfigurationHierarchy from './ConfigurationHierarchy.vue'
import ConfigurationValue from './ConfigurationValue'
import { configurationText, loadKoreanConfigurationCatalog } from './configurationLocale'
import { formatConfigurationValue, isSensitiveConfiguration } from './configurationPresentation'
import { configurationScopeParams } from './configurationScope'

export default {
  name: 'ScopedSettingsTab',
  components: { ConfigurationHierarchy, ConfigurationValue, ReloadOutlined, MoldDialog, ResourceTableToolbar },
  props: { resource: { type: Object, required: true } },
  data () {
    return {
      scopeType: this.$route.meta.name,
      items: [],
      koreanCatalog: null,
      search: '',
      page: 1,
      pageSize: Number(this.$store.getters.defaultListViewPageSize) || 20,
      loading: false,
      loadFailed: false,
      revision: 0,
      dialog: null
    }
  },
  computed: {
    scope () {
      const { id } = this.resource
      const type = this.scopeType
      if (type !== this.$route.meta.name) return { type, id: null }
      const routeId = this.$route.params?.id
      // On the domain tree, the selected record identifies the scope.
      const isDomainTree = type === 'domain' && !routeId
      return { type, id: id && (id === routeId || isDomainTree) ? id : null }
    },
    canUpdate () {
      return 'updateConfiguration' in this.$store.getters.apis
    },
    canReset () {
      return 'resetConfiguration' in this.$store.getters.apis
    },
    filteredRows () {
      const keyword = this.search.trim().toLowerCase()
      if (!keyword) return this.items
      return this.items.filter(record => [
        record.name,
        record.displaytext,
        record.description,
        record.category,
        record.component,
        record.instance,
        isSensitiveConfiguration(record) ? '' : record.value,
        this.localizedText(record, 'displaytext'),
        this.localizedText(record, 'description')
      ].some(value => String(value ?? '').toLowerCase().includes(keyword)))
    },
    pageRows () {
      return this.filteredRows.slice((this.page - 1) * this.pageSize, this.page * this.pageSize)
    },
    dialogTitle () {
      return {
        details: 'label.details',
        edit: 'label.global.setting.edit',
        reset: 'label.reset.config.value'
      }[this.dialog?.mode]
    }
  },
  watch: {
    scope: {
      immediate: true,
      handler (scope, previousScope) {
        if (scope.type === previousScope?.type && scope.id === previousScope?.id) return
        this.items = []
        this.search = ''
        this.page = 1
        this.dialog = null
        this.refresh()
      }
    },
    search () {
      this.page = 1
    },
    '$i18n.locale': {
      immediate: true,
      async handler (locale) {
        if (locale === 'ko_KR') this.koreanCatalog = await loadKoreanConfigurationCatalog()
        this.page = 1
      }
    }
  },
  beforeUnmount () {
    this.revision++
  },
  methods: {
    settingActions (record) {
      const disabled = this.loading || this.loadFailed
      return [
        ...(this.canUpdate ? [{ key: 'edit', label: 'label.edit', icon: 'edit-outlined', disabled, handler: () => this.open('edit', record) }] : []),
        ...(this.canReset ? [{ key: 'reset', label: 'label.reset.config.value', icon: 'reload-outlined', disabled, handler: () => this.open('reset', record) }] : [])
      ]
    },
    formatConfigurationValue,
    localizedText (record, field) {
      return configurationText(record, field, this.$i18n.locale, this.koreanCatalog)
    },
    changePage (page, pageSize) {
      this.page = this.pageSize === pageSize ? page : 1
      this.pageSize = pageSize
    },
    async refresh () {
      const revision = ++this.revision
      const { type, id } = this.scope
      const params = type !== 'globalsetting' ? configurationScopeParams(type, id, 'listConfigurations') : null
      this.loadFailed = false
      this.loading = params !== null
      if (!params) return
      try {
        const response = await getAPI('listConfigurations', { ...params, pagesize: -1 }, { preserveOnFailure: true })
        if (revision !== this.revision || this.scope.id !== id || this.scope.type !== type) return
        const list = response.listconfigurationsresponse
        if (!list) throw new Error('Missing configuration response')
        const rows = list.configuration || []
        const hideSecuritySetting = this.$store.getters.features.securityfeaturesenabled
        this.items = hideSecuritySetting ? rows.filter(row => row.name !== 'api.allowed.source.cidr') : rows
        this.page = Math.min(this.page, Math.max(1, Math.ceil(this.filteredRows.length / this.pageSize)))
      } catch {
        if (revision === this.revision) this.loadFailed = true
      } finally {
        if (revision === this.revision) this.loading = false
      }
    },
    open (mode, record) {
      if (!this.scope.id || (mode !== 'details' && (this.loading || this.loadFailed))) return
      this.dialog = { mode, record, scope: this.scope }
    },
    handleSaved () {
      this.dialog = null
      this.refresh()
    }
  }
}
</script>

<style scoped>
.scoped-settings { min-width: 0; }
.scoped-settings > .ant-alert { margin-bottom: 12px; }
.scoped-settings :deep(.ant-table-thead > tr > th.ant-table-cell) { background: var(--ui-bg-elevated); }
.configuration-description { margin: 12px 0; color: var(--ui-text-secondary); overflow-wrap: anywhere; }
</style>
