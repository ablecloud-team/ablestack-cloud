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
  <div class="vm-acl-standard themed-list-toolbar secondary-storage-selectors">
    <div class="content-breadcrumb">
      <a-card class="breadcrumb-card" style="z-index: 10">
        <a-row>
          <a-col :span="mobile ? 24 : 12" class="vm-acl-breadcrumb-layout">
            <breadcrumb :trail="breadcrumbTrail" standard-layout>
              <template #end>
                <a-button :loading="loading || loadingZones" shape="round" size="small" @click="refresh">
                  <template #icon><reload-outlined /></template>
                  {{ $t('label.refresh') }}
                </a-button>
                <a-select
                  v-if="!isDetail"
                  id="secondary-selector-zone"
                  :aria-label="$t('label.zone')"
                  :placeholder="$t('label.zone')"
                  :options="zoneOptions"
                  :value="zoneId || undefined"
                  :loading="loadingZones"
                  size="small"
                  class="selector-zone"
                  @change="setFilter({ zoneid: $event, page: '1' })" />
              </template>
            </breadcrumb>
          </a-col>
          <a-col :span="mobile ? 24 : 12" class="autogen-toolbar-actions">
            <a-dropdown v-if="isDetail && activeRule && !activeRule.removed && (canEdit || canRemove)" :trigger="['click']" placement="bottomRight">
              <a-button ref="detailActions" type="primary" class="autogen-action-dropdown__button">
                <template #icon><down-outlined /></template>
                {{ $t('label.actions') }}
              </a-button>
              <template #overlay>
                <a-menu>
                  <a-menu-item
                    v-if="canEdit"
                    key="edit"
                    :disabled="jsEnabled === false || checkingJs || !rowsReady"
                    @click="openEdit(activeRule, $refs.detailActions?.$el)">
                    <edit-outlined /> {{ $t('label.edit') }}
                  </a-menu-item>
                  <a-menu-item
                    v-if="canRemove"
                    key="remove"
                    :disabled="!rowsReady"
                    @click="openRemoval(activeRule, $refs.detailActions?.$el)">
                    <delete-outlined /> {{ $t('label.delete') }}
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
            <template v-else-if="!isDetail">
              <a-button
                v-if="canCreate"
                ref="addButton"
                type="primary"
                shape="round"
                :disabled="!zoneId || jsEnabled === false || checkingJs || readFailed || loading"
                :loading="opening"
                @click="openCreate">
                <template #icon><plus-outlined /></template>
                {{ $t('label.secondary.selector.add') }}
              </a-button>
              <div class="vm-acl-toolbar-search filter-group">
                <a-input-search
                  v-model:value="keywordDraft"
                  class="input-search"
                  :aria-label="$t('label.search')"
                  :placeholder="$t('label.search')"
                  allow-clear
                  @search="setFilter({ keyword: $event, page: '1' })">
                  <template #addonBefore>
                    <a-popover v-model:visible="filtersVisible" trigger="click" placement="bottomRight">
                      <template #content>
                        <a-form class="selector-filters" layout="vertical" @submit.prevent="applyFilters">
                          <a-form-item html-for="secondary-selector-list-type" :label="$t('label.type')">
                            <a-select id="secondary-selector-list-type" v-model:value="typeDraft" :options="typeOptions" />
                          </a-form-item>
                          <a-checkbox v-model:checked="removedDraft">{{ $t('label.secondary.selector.show.removed') }}</a-checkbox>
                          <div class="selector-filter-actions">
                            <a-button @click="resetFilters">{{ $t('label.reset') }}</a-button>
                            <a-button type="primary" html-type="submit">{{ $t('label.apply') }}</a-button>
                          </div>
                        </a-form>
                      </template>
                      <a-button class="filter-button" size="small" :aria-label="$t('label.advanced.search')">
                        <filter-two-tone v-if="selectorType || showRemoved" />
                        <filter-outlined v-else />
                      </a-button>
                    </a-popover>
                  </template>
                </a-input-search>
              </div>
            </template>
          </a-col>
        </a-row>
      </a-card>
    </div>

    <a-alert v-if="zonesFailed" type="error" show-icon :message="$t('message.secondary.selector.zones.failed')" />
    <a-alert v-else-if="jsEnabled === false && (canCreate || canEdit)" type="info" show-icon :message="$t('message.secondary.selector.js.disabled')" />
    <a-alert
      v-if="readFailed"
      type="error"
      show-icon
      :message="$t(rows.length ? 'message.list.refresh.stale' : 'message.secondary.selector.list.failed')" />

    <template v-if="isDetail">
      <resource-view
        v-if="activeRule"
        :resource="detailResource"
        :tabs="detailTabs"
        :ready-for-actions="rowsReady"
        :read-pending="loading"
        :read-failed="readFailed"
        quiet-refresh />
      <Empty
        v-else-if="!zonesFailed && !loadingZones && !loading && !readFailed"
        :description="$t(zoneId ? 'message.secondary.selector.rule.missing' : 'message.secondary.selector.detail.zone')" />
    </template>
    <template v-else>
      <list-view
        v-if="zoneId && (!readFailed || rows.length)"
        :columns="columns"
        :items="pageRows"
        :loading="loading"
        :empty-text="$t('message.secondary.selector.no.rules')"
        :table-scroll="{ x: 620 }"
        :resource-path="selectorPath"
        :resource-link="detailLocation" />
      <a-pagination
        v-if="zoneId && filteredRows.length"
        class="row-element"
        size="small"
        :current="page"
        :page-size="pageSize"
        :page-size-options="pageSizeOptions"
        :total="filteredRows.length"
        show-size-changer
        show-quick-jumper
        @change="changePage" />
      <Empty v-else-if="!zoneId && !zonesFailed && !loadingZones" :description="$t('message.secondary.selector.choose.zone')" />
    </template>

    <secondary-storage-selector-dialog
      v-if="dialog"
      :dialog="dialog"
      :visible="showRule"
      :zone-name="zoneName"
      :stores="targetStores"
      :stores-loading="loadingStores"
      :stores-failed="storesFailed"
      :busy="saving"
      @save="saveRule"
      @cancel="closeRule"
      @after-close="ruleClosed" />

    <mold-dialog
      :visible="!!removal"
      :title="$t('label.secondary.selector.remove')"
      size="sm"
      form-actions
      focus-guard
      initial-focus="#secondary-selector-cancel-removal"
      :after-close="restoreFocus"
      @cancel="closeRemoval">
      <dl v-if="removal" class="acl-action-context">
        <dt>{{ $t('label.name') }}</dt><dd>{{ removal.name }}</dd>
        <dt>{{ $t('label.zone') }}</dt><dd>{{ zoneName }}</dd>
        <dt>{{ $t('label.type') }}</dt><dd>{{ removal.type }}</dd>
      </dl>
      <p>{{ $t('message.secondary.selector.remove.effect') }}</p>
      <mold-dialog-actions :busy="saving">
        <a-button id="secondary-selector-cancel-removal" @click="closeRemoval">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" danger :loading="saving" @click="removeRule">{{ $t('label.delete') }}</a-button>
      </mold-dialog-actions>
    </mold-dialog>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { Empty } from 'ant-design-vue'
import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import Breadcrumb from '@/components/widgets/Breadcrumb'
import ListView from '@/components/view/ListView'
import ResourceView from '@/components/view/ResourceView'
import SecondaryStorageSelectorDetails from '@/views/infra/SecondaryStorageSelectorDetails'
import SecondaryStorageSelectorDialog from '@/views/infra/SecondaryStorageSelectorDialog'
import { secondaryStorageListQuery } from '@/utils/secondaryStorageSearch'
import {
  fixedSelectorId, isSelectorUuid, readSecondaryStorageSelectors, readSelectorStores,
  selectorApi, selectorConfigKey, selectorListQuery, selectorPage, selectorPageSizes, selectorTypes
} from '@/utils/secondaryStorageSelectors'

export default {
  name: 'SecondaryStorageSelectors',
  components: { Empty, Breadcrumb, ListView, ResourceView, MoldDialog, MoldDialogActions, SecondaryStorageSelectorDialog },
  data () {
    return {
      zones: [],
      rows: [],
      targetStores: [],
      keywordDraft: selectorListQuery(this.$route.query).keyword || '',
      filtersVisible: false,
      typeDraft: '',
      removedDraft: false,
      loading: false,
      loadingZones: false,
      loadingStores: false,
      zonesFailed: false,
      readFailed: false,
      storesFailed: false,
      storesZone: '',
      checkingJs: false,
      jsEnabled: null,
      rowsKey: '',
      listVersion: 0,
      storeVersion: 0,
      zoneVersion: 0,
      dialog: null,
      showRule: false,
      removal: null,
      opening: false,
      saving: false,
      focusReturn: null
    }
  },
  computed: {
    apis () { return this.$store.getters.apis || {} },
    mobile () { return this.$store.getters.device === 'mobile' },
    selectorPath () { return '/' + selectorPage },
    query () { return selectorListQuery(this.$route.query) },
    originLocation () {
      const history = this.$router.options.history.state?.secondaryStorageSelectorOrigin
      return { name: 'imagestore', query: secondaryStorageListQuery(history || {}) }
    },
    originState () { return { secondaryStorageSelectorOrigin: this.originLocation.query } },
    listLocation () { return { name: selectorPage, query: this.query, state: this.originState } },
    breadcrumbTrail () {
      const items = [
        this.$route.matched[0],
        { name: 'infra', path: '/infra', meta: { title: 'label.infrastructure' } },
        { name: 'imagestore', location: this.originLocation, meta: { title: 'label.secondary.storage' } }
      ]
      if (this.isDetail) {
        items.push({ name: selectorPage, location: this.listLocation, meta: { title: 'label.secondary.selector.rules' } })
        items.push({ current: true, text: this.activeRule?.name || this.ruleId, meta: { title: 'label.details', docHelp: this.$route.meta.docHelp } })
      } else {
        items.push({ current: true, meta: { title: 'label.secondary.selector.rules', docHelp: this.$route.meta.docHelp } })
      }
      return items
    },
    requestedZone () { return this.query.zoneid || '' },
    zoneId () { return this.zones.some(zone => zone.id === this.requestedZone) ? this.requestedZone : '' },
    zoneName () { return this.zones.find(zone => zone.id === this.zoneId)?.name || this.zoneId },
    selectorType () { return this.query.type || '' },
    showRemoved () { return this.query.showremoved === 'true' },
    isDetail () { return !!this.$route.params.id },
    ruleId () { return isSelectorUuid(this.$route.params.id) ? this.$route.params.id : '' },
    zoneOptions () { return this.zones.map(zone => ({ value: zone.id, label: zone.name || zone.id })) },
    typeOptions () {
      return [{ value: '', label: this.$t('label.all') }, ...selectorTypes.map(type => ({ value: type, label: type }))]
    },
    canList () { return !!this.apis[selectorApi.list] },
    canCreate () { return !!this.apis[selectorApi.create] },
    canEdit () { return !!this.apis[selectorApi.update] },
    canRemove () { return !!this.apis[selectorApi.remove] },
    readKey () {
      const scope = this.isDetail ? [this.ruleId] : [this.selectorType, this.showRemoved]
      return JSON.stringify([this.zoneId, this.isDetail, scope, this.canList, this.$store.state.user?.token])
    },
    rowsReady () { return this.rowsKey === this.readKey && !this.loading && !this.readFailed },
    activeRule () {
      if (!this.isDetail || !this.ruleId || this.rowsKey !== this.readKey) return null
      return this.rows.find(rule => rule.id === this.ruleId && rule.zoneid === this.zoneId) || null
    },
    detailResource () {
      const rule = this.activeRule
      if (!rule) return {}
      return {
        ...rule,
        zonename: this.zoneName,
        selectorTarget: this.targetLabel(rule),
        state: this.$t(rule.removed ? 'label.removed' : 'label.active')
      }
    },
    detailTabs () { return [{ name: 'details', component: SecondaryStorageSelectorDetails }] },
    filteredRows () {
      const term = (this.query.keyword || '').toLowerCase()
      return this.rows.filter(row => String(row.name || '').toLowerCase().includes(term))
    },
    pageSizeOptions () { return selectorPageSizes },
    pageSize () {
      const configured = String(this.$store.getters.defaultListViewPageSize)
      return Number(this.query.pagesize || (selectorPageSizes.includes(configured) ? configured : '20'))
    },
    page () {
      return Math.min(Number(this.query.page || 1), Math.max(1, Math.ceil(this.filteredRows.length / this.pageSize)))
    },
    pageRows () {
      const start = (this.page - 1) * this.pageSize
      return this.filteredRows.slice(start, start + this.pageSize).map(row => ({
        ...row, selectorTarget: this.targetLabel(row), selectorStatus: this.$t(row.removed ? 'label.removed' : 'label.active')
      }))
    },
    columns () {
      return [
        { key: 'name', dataIndex: 'name', title: this.$t('label.name'), width: 200 },
        { key: 'type', dataIndex: 'type', title: this.$t('label.type'), width: 110 },
        { key: 'selectorTarget', dataIndex: 'selectorTarget', title: this.$t('label.secondary.selector.target'), width: 210, ellipsis: true },
        { key: 'selectorStatus', dataIndex: 'selectorStatus', title: this.$t('label.state'), width: 100 }
      ]
    }
  },
  watch: {
    readKey () { this.readRows() },
    '$route.query' () { this.keywordDraft = this.query.keyword || '' },
    zoneId () { this.readTargets() },
    filtersVisible (open) {
      if (!open) return
      this.typeDraft = this.selectorType
      this.removedDraft = this.showRemoved
    }
  },
  created () {
    this.readZones()
    this.readJsPolicy()
  },
  beforeUnmount () {
    this.listVersion++
    this.storeVersion++
    this.zoneVersion++
  },
  methods: {
    setFilter (changes) {
      return this.$router.replace({ query: selectorListQuery({ ...this.query, ...changes }), state: this.originState })
    },
    detailLocation (rule) {
      return { path: this.selectorPath + '/' + encodeURIComponent(rule.id), query: this.query, state: this.originState }
    },
    changePage (page, size) {
      return this.setFilter({ page: size === this.pageSize ? page : 1, pagesize: size })
    },
    applyFilters () {
      this.filtersVisible = false
      return this.setFilter({ type: this.typeDraft, showremoved: this.removedDraft ? 'true' : undefined, page: '1' })
    },
    resetFilters () {
      this.typeDraft = ''
      this.removedDraft = false
      return this.applyFilters()
    },
    async readZones () {
      if (!this.canList || this.loadingZones) return
      const request = ++this.zoneVersion
      this.loadingZones = true
      this.zonesFailed = false
      try {
        let zones
        if (this.apis.listZones) {
          const data = await getAPI('listZones', {}, { preserveOnFailure: true })
          zones = (data.listzonesresponse.zone || []).filter(zone => isSelectorUuid(zone.id))
        } else if (this.requestedZone) {
          zones = [{ id: this.requestedZone }]
        } else {
          throw new Error()
        }
        if (request !== this.zoneVersion) return
        this.zones = zones
        if (!this.isDetail) {
          const next = this.zoneId || (zones.length === 1 ? zones[0].id : undefined)
          if (next !== this.$route.query.zoneid) await this.setFilter({ zoneid: next })
        }
      } catch {
        if (request === this.zoneVersion) this.zonesFailed = true
      } finally {
        if (request === this.zoneVersion) this.loadingZones = false
      }
    },
    async readJsPolicy () {
      if (!this.apis.listConfigurations) return
      this.checkingJs = true
      try {
        const data = await getAPI('listConfigurations', { name: selectorConfigKey }, { preserveOnFailure: true })
        const row = data.listconfigurationsresponse.configuration?.find(setting => setting.name === selectorConfigKey)
        this.jsEnabled = row ? String(row.value) === 'true' : null
      } catch {
        this.jsEnabled = null
      } finally {
        this.checkingJs = false
      }
    },
    async readRows () {
      const version = ++this.listVersion
      const key = this.readKey
      this.readFailed = false
      if (this.rowsKey !== key) this.rows = []
      if (!this.zoneId || !this.canList || (this.isDetail && !this.ruleId)) {
        this.loading = false
        this.rowsKey = ''
        return
      }
      this.loading = true
      try {
        const scope = this.isDetail ? { showRemoved: true } : { type: this.selectorType, showRemoved: this.showRemoved }
        const rows = await readSecondaryStorageSelectors(this.zoneId, scope)
        if (version !== this.listVersion || key !== this.readKey) return
        this.rows = rows.filter(row => row.zoneid === this.zoneId && isSelectorUuid(row.id) && (!this.isDetail || row.id === this.ruleId))
        this.rowsKey = key
      } catch (error) {
        if (version !== this.listVersion || key !== this.readKey) return
        if ([401, 403].includes(error?.response?.status)) {
          this.rows = []
          this.rowsKey = ''
        }
        this.readFailed = true
      } finally {
        if (version === this.listVersion) this.loading = false
      }
    },
    async readTargets () {
      const version = ++this.storeVersion
      const zoneId = this.zoneId
      if (this.storesZone !== zoneId || !this.apis.listImageStores) this.targetStores = []
      this.storesFailed = !!zoneId && !this.apis.listImageStores
      this.loadingStores = false
      if (!zoneId || !this.apis.listImageStores) return
      this.loadingStores = true
      try {
        const stores = await readSelectorStores(zoneId)
        if (version === this.storeVersion && zoneId === this.zoneId) {
          this.targetStores = stores
          this.storesZone = zoneId
        }
      } catch {
        if (version === this.storeVersion) this.storesFailed = true
      } finally {
        if (version === this.storeVersion) this.loadingStores = false
      }
    },
    refresh () {
      if (this.zonesFailed || !this.zones.length) this.readZones()
      if (!this.checkingJs) this.readJsPolicy()
      this.readTargets()
      if (!this.loading) this.readRows()
    },
    targetLabel (record) {
      const id = fixedSelectorId(record.heuristicrule)
      if (!id) return this.$t('label.secondary.selector.script')
      return this.targetStores.find(store => store.id.toLowerCase() === id)?.name || id
    },
    currentRule (record) {
      return this.rowsReady && !record.removed && record.zoneid === this.zoneId &&
        isSelectorUuid(record.id) && this.rows.some(row => row.id === record.id && !row.removed)
    },
    async openCreate (event) {
      if (!this.canCreate || this.jsEnabled === false || this.checkingJs || !this.rowsReady || this.opening) return
      const key = this.readKey
      const trigger = event.currentTarget
      this.opening = true
      try {
        const active = await readSecondaryStorageSelectors(this.zoneId)
        if (key !== this.readKey || !this.canCreate || this.jsEnabled === false) return
        this.focusReturn = markRaw(trigger)
        this.dialog = { mode: 'add', zoneid: this.zoneId, type: this.selectorType, usedTypes: active.filter(rule => !rule.removed).map(rule => rule.type) }
        this.showRule = true
      } catch (error) {
        if (key === this.readKey) this.$notifyError(error)
      } finally {
        this.opening = false
      }
    },
    openEdit (record, trigger) {
      if (!this.canList || !this.canEdit || this.jsEnabled === false || !this.currentRule(record)) return
      this.focusReturn = trigger ? markRaw(trigger) : null
      this.dialog = { mode: 'edit', zoneid: this.zoneId, record: { ...record } }
      this.showRule = true
    },
    closeRule () {
      if (!this.saving) this.showRule = false
    },
    ruleClosed () {
      this.dialog = null
      this.restoreFocus()
    },
    openRemoval (record, trigger) {
      if (!this.canRemove || !this.currentRule(record)) return
      this.focusReturn = trigger ? markRaw(trigger) : null
      this.removal = { ...record }
    },
    closeRemoval () {
      if (!this.saving) this.removal = null
    },
    restoreFocus () {
      const target = this.focusReturn?.isConnected ? this.focusReturn : this.$refs.addButton?.$el
      this.focusReturn = null
      if (target && !target.disabled) target.focus({ preventScroll: true })
    },
    saveRule (values) {
      const dialog = this.dialog
      if (!dialog || dialog.zoneid !== this.zoneId || this.jsEnabled === false || !this.rowsReady) return
      if (dialog.mode === 'edit' && this.canEdit && this.currentRule(dialog.record)) {
        return this.updateRule(selectorApi.update, { id: dialog.record.id, heuristicrule: values.heuristicrule }, this.closeRule)
      }
      if (dialog.mode === 'add' && this.canCreate) {
        return this.updateRule(selectorApi.create, { ...values, zoneid: dialog.zoneid }, this.closeRule)
      }
    },
    removeRule () {
      if (!this.removal || !this.canRemove || !this.currentRule(this.removal)) return
      return this.updateRule(selectorApi.remove, { id: this.removal.id }, this.closeRemoval)
    },
    async updateRule (command, params, close) {
      if (this.saving) return
      this.saving = true
      const key = this.readKey
      try {
        await postAPI(command, params, { preserveOnFailure: true })
      } catch (error) {
        this.$notifyError(error)
        return
      } finally {
        this.saving = false
      }
      if (key !== this.readKey) return
      close()
      this.$message.success(this.$t('label.success'))
      if (this.isDetail && command === selectorApi.remove) await this.$router.push(this.listLocation)
      else await this.readRows()
    }
  }
}
</script>

<style scoped src="@/style/components/view/list-toolbar.css" />
<style scoped src="@/style/components/view/vm-acl-standard.css" />
<style scoped lang="less" src="@/style/components/view/search-view.less" />

<style scoped>
.secondary-storage-selectors { min-width: 0; }
.secondary-storage-selectors .breadcrumb-card { margin-bottom: 16px; }
.secondary-storage-selectors .ant-alert { margin-bottom: 12px; }
.secondary-storage-selectors .selector-zone { min-width: 140px; }
.secondary-storage-selectors :deep(.vm-acl-breadcrumb-layout .breadcrumb) {
  flex-wrap: wrap;
}
.selector-filters { min-width: 180px; }
.selector-filter-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }
</style>
