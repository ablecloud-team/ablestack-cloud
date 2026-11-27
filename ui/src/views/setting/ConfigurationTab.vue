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
  <a-row :gutter="12" class="mold-workspace">
    <a-col :span="24" class="content-breadcrumb">
      <a-card class="breadcrumb-card breadcrumb-toolbar">
        <div class="breadcrumb-toolbar-row">
          <div class="breadcrumb-toolbar-path">
            <breadcrumb>
              <template #end>
                <a-button
                  :loading="loading || configLoading || listRefreshing > 0"
                  size="small"
                  shape="round"
                  @click="fetchConfigurationData()" >
                  <template #icon><ReloadOutlined /></template>
                  {{ $t('label.refresh') }}
                </a-button>
              </template>
            </breadcrumb>
          </div>
          <div class="breadcrumb-toolbar-actions">
            <a-input-search
              class="breadcrumb-toolbar-search"
              :placeholder="$t('label.search')"
              :aria-label="$t('label.search')"
              v-model:value="filterDraft"
              allow-clear
              @search="setConfigurationQuery({ filter: $event, page: 1 })">
              <template #addonBefore>
                <a-popover v-model:visible="filtersOpen" trigger="click" placement="bottomRight">
                  <template #content>
                    <form class="configuration-filters" @submit.prevent="applyFilters">
                      <label for="configuration-name-filter">{{ $t('label.global.setting.key') }}</label>
                      <a-input id="configuration-name-filter" v-model:value="nameDraft" allow-clear />
                      <label for="configuration-category-filter">{{ $t('label.category') }}</label>
                      <a-input id="configuration-category-filter" v-model:value="categoryDraft" allow-clear />
                      <a-button type="primary" html-type="submit">{{ $t('label.apply') }}</a-button>
                    </form>
                  </template>
                  <a-button class="filter-button" size="small" :aria-label="$t('label.advanced.search')" :aria-expanded="filtersOpen">
                    <FilterOutlined />
                  </a-button>
                </a-popover>
              </template>
            </a-input-search>
          </div>
        </div>
      </a-card>
    </a-col>
    <a-col :span="24" class="workspace-fill">
      <a-card class="configuration-content workspace-fill">
        <div v-if="activeFilters.length" class="configuration-active-filters">
          <a-tag
            v-for="condition in activeFilters"
            :key="condition.key"
            closable
            @close.prevent="setConfigurationQuery({ [condition.key]: '', page: 1 })">
            {{ $t(condition.label) }} {{ condition.value }}
          </a-tag>
        </div>
        <a-card v-if="selectedSetting" class="configuration-detail-card workspace-layout-body" :title="$t('label.global.setting.details')" size="small">
          <template #extra>
            <a-space>
              <a-button v-if="canEditSettings" size="small" @click="openSettingDialog(selectedSetting, 'edit')">{{ $t('label.edit') }}</a-button>
              <a-button size="small" @click="selectedName = ''">{{ $t('label.close') }}</a-button>
            </a-space>
          </template>
          <a-list class="resource-details-list vm-details-list configuration-detail-list" size="small" row-key="label" :data-source="selectedSettingDetails">
            <template #renderItem="{ item }">
              <a-list-item>
                <div class="configuration-detail-entry">
                  <strong>{{ $t(item.label) }}</strong>
                  <div>{{ item.value }}</div>
                  <div v-if="item.settingKey">{{ item.settingKey }}</div>
                </div>
              </a-list-item>
            </template>
          </a-list>
        </a-card>
        <a-spin :spinning="configLoading" wrapper-class-name="workspace-fill">
          <a-alert v-if="listRefreshFailed" type="error" show-icon :message="$t('message.error.loading.setting')" />
          <a-tabs
            class="workspace-tabset workspace-tabset-with-list"
            v-if="!listRefreshFailed || config.length > 0"
            :tab-position="isMobile() ? 'top' : 'left'"
            :animated="false"
            :activeKey="this.group || ''"
            @change="changeGroupTab" >
            <a-tab-pane
              key=''
              :tab="localizedSection('All Settings')" >
              <ConfigurationHierarchy
                v-if="!group"
                viewport-scroll
                :viewport-scope="$route.fullPath"
                :show-selection="false"
                :config="config"
                :korean-catalog="koreanCatalog"
                :count="count"
                :page="page"
                :pagesize="pagesize"
                @change-page="changePage"
                @show-setting="selectedName = $event.name"
                @edit-setting="openSettingDialog($event, 'edit')"
                @reset-setting="openSettingDialog($event, 'reset')" />
            </a-tab-pane>
            <a-tab-pane
              v-for="(group) in groups"
              :key="group.name"
              :tab="localizedSection(group.name)" >
              <a-tabs
                class="workspace-tabset workspace-tabset-with-list"
                :activeKey="this.subgroup || ''"
                :animated="false"
                @change="changeSubgroupTab" >
                <a-tab-pane
                  v-for="(subgroup) in group.subgroup"
                  :key="subgroup.name"
                  :tab="localizedSection(subgroup.name)" >
                  <ConfigurationHierarchy
                    v-if="group.name === this.group && subgroup.name === this.subgroup"
                    grouped
                    viewport-scroll
                    :viewport-scope="$route.fullPath"
                    :show-selection="false"
                    :config="hierarchy"
                    :korean-catalog="koreanCatalog"
                    :expanded-row-keys="expandedRows[sectionKey] || []"
                    @update:expanded-row-keys="expandedRows[sectionKey] = $event"
                    @open-parent="openParent"
                    @show-setting="selectedName = $event.name"
                    @edit-setting="openSettingDialog($event, 'edit')"
                    @reset-setting="openSettingDialog($event, 'reset')" />
                </a-tab-pane>
              </a-tabs>
            </a-tab-pane>
          </a-tabs>
        </a-spin>
      </a-card>
    </a-col>
    <mold-dialog
      v-if="settingDialog"
      size="md"
      form-actions
      focus-guard
      :title="$t(settingDialog.mode === 'reset' ? 'label.reset.config.value' : 'label.global.setting.edit')"
      @cancel="settingDialog = null">
      <a-descriptions size="small" layout="vertical" :column="{ xs: 1, sm: 3 }">
        <a-descriptions-item :label="$t('label.global.setting.key')">
          {{ localizedText(dialogRecord, 'displaytext') }}<br />{{ dialogRecord.name }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.global.setting.current')">{{ formatConfigurationValue(dialogRecord) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.global.setting.default')">{{ formatConfigurationValue(dialogRecord, 'defaultvalue') }}</a-descriptions-item>
      </a-descriptions>
      <p v-if="localizedText(dialogRecord, 'description')">{{ localizedText(dialogRecord, 'description') }}</p>
      <configuration-value
        :configrecord="dialogRecord"
        :dialog-mode="settingDialog.mode"
        @refresh="handleConfigRefresh"
        @saved="settingDialog = null"
        @cancel="settingDialog = null" />
    </mold-dialog>
  </a-row>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI } from '@/api'
import { markRaw } from 'vue'
import { FilterOutlined } from '@ant-design/icons-vue'
import { mixin, mixinDevice } from '@/utils/mixin.js'
import Breadcrumb from '@/components/widgets/Breadcrumb'
import ConfigurationHierarchy from './ConfigurationHierarchy.vue'
import ConfigurationValue from './ConfigurationValue'
import MoldDialog from '@/components/view/MoldDialog'
import { formatConfigurationValue, configurationApplyLabel } from './configurationPresentation'
import { configurationText, loadKoreanConfigurationCatalog } from './configurationLocale'
import { buildConfigurationHierarchy } from './configurationHierarchy'
import { readConfigurationQuery, writeConfigurationQuery, listConfigurationParams } from './configurationQuery'

const securitySettings = new Set([
  'api.source.cidr.checks.enabled',
  'password.policy.allowUseOfLastUsedPassword',
  'password.policy.allowContinuousLettersAndNumbersInputOnKeyboard',
  'password.policy.allowConsecutiveRepetitionsOfSameLettersAndNumbers',
  'password.policy.allowPasswordToContainUsername',
  'password.policy.minimum.digits',
  'password.policy.minimum.lowercase.letters',
  'password.policy.minimum.special.characters',
  'password.policy.minimum.uppercase.letters',
  'password.policy.minimum.length',
  'password.policy.maximum.length',
  'incorrect.login.attempts.allowed',
  'incorrect.login.enable.time',
  'block.exist.connection',
  'api.allowed.source.cidr',
  'concurrent.connect.enabled',
  'event.delete.enabled',
  'event.purge.delay',
  'security.check.interval',
  'integrity.verification.interval',
  'license.check.interval'
])

export default {
  name: 'ConfigurationTab',
  components: {
    Breadcrumb,
    FilterOutlined,
    ConfigurationHierarchy,
    ConfigurationValue,
    MoldDialog
  },
  mixins: [listRefreshMixin(['fetchConfigurationData'], { active: vm => vm.groupsLoaded && !vm.settingDialog }), mixin, mixinDevice],
  props: {
    loading: {
      type: Boolean,
      default: false
    },
    actions: {
      type: Array,
      default: () => []
    }
  },
  data () {
    return {
      groups: [],
      koreanCatalog: null,
      groupsLoaded: false,
      config: [],
      expandedRows: {},
      parentLookup: 0,
      configLoading: true,
      filterDraft: '',
      nameDraft: '',
      categoryDraft: '',
      filtersOpen: false,
      count: 0,
      selectedName: '',
      settingDialog: null
    }
  },
  computed: {
    selectedSetting () {
      return this.config.find(record => record.name === this.selectedName)
    },
    selectedSettingDetails () {
      const record = this.selectedSetting
      if (!record) return []

      const applyLabel = configurationApplyLabel(record)
      return [
        { label: 'label.global.setting.key', value: this.localizedText(record, 'displaytext'), settingKey: record.name },
        { label: 'label.global.setting.current', value: formatConfigurationValue(record) },
        { label: 'label.global.setting.default', value: formatConfigurationValue(record, 'defaultvalue') },
        { label: 'label.global.setting.apply', value: applyLabel ? this.$t(applyLabel) : '—' },
        { label: 'label.description', value: this.localizedText(record, 'description') || '—' }
      ]
    },
    dialogRecord () {
      return this.config.find(record => record.name === this.settingDialog?.record.name) || this.settingDialog?.record
    },
    canEditSettings () {
      return 'updateConfiguration' in this.$store.getters.apis
    },
    defaultPageSize () {
      return Number(this.$store.getters.defaultListViewPageSize) || 20
    },
    queryState () {
      return readConfigurationQuery(this.$route.query, this.groups, this.defaultPageSize)
    },
    group () {
      return this.queryState.group
    },
    subgroup () {
      return this.queryState.subgroup
    },
    page () {
      return this.queryState.page
    },
    pagesize () {
      return this.queryState.pagesize
    },
    activeFilters () {
      return [
        { key: 'filter', label: 'label.search', value: this.queryState.filter },
        { key: 'name', label: 'label.global.setting.key', value: this.queryState.name },
        { key: 'category', label: 'label.category', value: this.queryState.category }
      ].filter(condition => condition.value)
    },
    sectionKey () {
      return JSON.stringify([this.group, this.subgroup])
    },
    hierarchy () {
      return buildConfigurationHierarchy(this.config)
    }
  },
  watch: {
    '$i18n.locale' () {
      this.ensureKoreanCatalog()
    },
    '$route.fullPath' () {
      if (this.groupsLoaded && this.$route.meta.name === 'globalsetting') {
        this.applyConfigurationRoute()
      }
    }
  },
  created () {
    this.fetchConfigurationGroups()
    this.ensureKoreanCatalog()
  },
  methods: {
    formatConfigurationValue,
    async ensureKoreanCatalog () {
      if (this.$i18n.locale !== 'ko_KR' || this.koreanCatalog) return
      const catalog = await loadKoreanConfigurationCatalog()
      if (catalog && !this.listRefreshDisposed) {
        this.koreanCatalog = markRaw(catalog)
      }
    },
    localizedSection (name) {
      const locale = this.$i18n.locale
      if (locale !== 'ko_KR' && locale !== 'en') return name
      const key = 'label.global.setting.section.' + name
      const label = this.$t(key)
      return label === key ? name : label
    },
    localizedText (record, field) {
      return configurationText(record, field, this.$i18n.locale, this.koreanCatalog)
    },
    openSettingDialog (record, mode) {
      this.settingDialog = { record, mode }
    },
    applyConfigurationRoute () {
      this.selectedName = ''
      this.settingDialog = null
      this.filterDraft = this.queryState.filter
      this.nameDraft = this.queryState.name
      this.categoryDraft = this.queryState.category
      this.filtersOpen = false
      return this.fetchConfigurationData()
    },
    fetchConfigurationGroups () {
      this.configLoading = true
      const params = {
        pagesize: -1
      }
      getAPI('listConfigurationGroups', params).then(response => {
        this.groups = response.listconfigurationgroupsresponse.configurationgroup || []
      }).catch(error => {
        console.error(error)
        this.$message.error(this.$t('message.error.loading.setting'))
      }).finally(() => {
        if (!this.listRefreshDisposed) {
          this.groupsLoaded = true
          this.applyConfigurationRoute()
        }
      })
    },
    fetchConfigurationData () {
      const listRequest = this.listRequestToken('fetchConfigurationData')
      this.configLoading = !listRequest.loaded
      const params = listConfigurationParams(this.queryState)

      return getAPI('listConfigurations', params).then(response => {
        if (!this.isListRequestCurrent('fetchConfigurationData', listRequest)) return

        const config = response.listconfigurationsresponse.configuration || []
        this.count = response.listconfigurationsresponse.count || 0
        this.config = this.visibleConfigurations(config)
        if (!listRequest.loaded) window.scrollTo(0, 0)
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchConfigurationData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        console.error(error)
        this.$message.error(this.$t('message.error.loading.setting'))
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchConfigurationData', listRequest)) return
        this.configLoading = false
      })
    },
    visibleConfigurations (configs) {
      if (!this.$store.getters.features.securityfeaturesenabled) return configs
      return configs.filter(record => !securitySettings.has(record.name))
    },
    async openParent (record) {
      const request = ++this.parentLookup
      const path = this.$route.fullPath
      const isCurrent = () => !this.listRefreshDisposed && request === this.parentLookup && path === this.$route.fullPath
      try {
        const response = await getAPI('listConfigurations', { name: record.parent, pagesize: -1 })
        if (!isCurrent()) return

        const parent = this.visibleConfigurations(response.listconfigurationsresponse.configuration || [])
          .find(item => item.name === record.parent)
        if (!parent) {
          this.$message.info(this.$t('message.global.setting.parent.unavailable'))
          return
        }
        const group = this.groups.find(item => item.name === parent.group)
        const subgroup = group?.subgroup?.find(item => item.name === parent.subgroup)
        await this.setConfigurationQuery({
          group: subgroup ? group.name : '',
          subgroup: subgroup?.name || '',
          filter: parent.name,
          name: '',
          category: '',
          page: 1
        })
      } catch (error) {
        if (isCurrent()) {
          this.$message.error(this.$t('message.error.loading.setting'))
        }
      }
    },
    changePage (page, pagesize) {
      const pageChanged = pagesize === this.pagesize
      return this.setConfigurationQuery({ page: pageChanged ? page : 1, pagesize })
    },
    setConfigurationQuery (changes) {
      const state = { ...this.queryState, ...changes }
      const query = { ...this.$route.query, ...writeConfigurationQuery(state, this.defaultPageSize) }
      if (this.$router.resolve({ query }).fullPath === this.$route.fullPath) {
        return this.fetchConfigurationData()
      }
      return this.$router.push({ query })
    },
    applyFilters () {
      this.filtersOpen = false
      return this.setConfigurationQuery({ name: this.nameDraft, category: this.categoryDraft, page: 1 })
    },
    changeGroupTab (name) {
      const group = this.groups.find(entry => entry.name === name)
      return this.setConfigurationQuery({ group: group?.name || '', subgroup: group?.subgroup?.[0]?.name || '', page: 1 })
    },
    changeSubgroupTab (name) {
      return this.setConfigurationQuery({ subgroup: name, page: 1 })
    },
    handleConfigRefresh (name, updatedRecord) {
      if (!name || !updatedRecord) return
      const index = this.config.findIndex(item => item.name === name)
      if (index !== -1) {
        this.config.splice(index, 1, { ...this.config[index], ...updatedRecord })
      }
    }
  }
}

</script>

<style scoped src="@/style/components/view/resource-details-list.css" />

<style scoped lang="scss">
  .breadcrumb-card {
    margin: -16px -24px 12px;
  }

  .configuration-filters { width: min(260px, 75vw); display: grid; gap: 8px; }
  .configuration-active-filters { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
  .configuration-content,
  .configuration-content :deep(.ant-tabs-content-holder) { min-width: 0; }
  .configuration-detail-card { margin-bottom: 16px; overflow-wrap: anywhere; }
  .configuration-detail-list :deep(.ant-list-item) {
    padding: 12px 0;
    color: var(--ui-text-secondary);
    border-color: var(--ui-border);
  }
  .configuration-detail-entry strong { color: var(--ui-text-primary); }

</style>
