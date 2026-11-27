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
    <mold-dialog
      v-if="showTimeFilterModal"
      :visible="true"
      :title="$t('label.select.period')"
      size="md"
      form-actions
      @cancel="closeTimeFilterAction">
      <a-alert v-if="dateRangeError" type="error" show-icon :message="$t('message.webhook.delivery.period.invalid')" />
      <date-time-filter
        :startDateProp="startDate"
        :endDateProp="endDate"
        :showAllDataOption="false"
        standard-dialog
        @closeAction="closeTimeFilterAction"
        @onSubmit="handleSubmitDateTimeFilter"/>
    </mold-dialog>
    <mold-dialog v-if="selectedDelivery" :visible="true" :title="$t('label.webhook.delivery.details')" size="lg" form-actions @cancel="selectedDelivery = null">
      <a-descriptions :column="1" size="small">
        <a-descriptions-item :label="$t('label.event')">{{ selectedDelivery.eventtype || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.payloadurl')">{{ resource.payloadurl || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.startdate')">{{ formatDeliveryDate(selectedDelivery.startdate) || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.enddate')">{{ formatDeliveryDate(selectedDelivery.enddate) || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.status')">{{ $t(selectedDelivery.success ? 'label.success' : 'label.failed') }}</a-descriptions-item>
      </a-descriptions>
      <details v-for="field in deliveryDetailFields" :key="field" class="webhook-delivery-section">
        <summary>{{ $t('label.' + field) }}</summary>
        <pre>{{ formatDeliveryContent(selectedDelivery[field]) }}</pre>
      </details>
      <mold-dialog-actions>
        <a-button @click="selectedDelivery = null">{{ $t('label.close') }}</a-button>
      </mold-dialog-actions>
    </mold-dialog>
    <div class="filter-row">
      <a-row>
        <a-col :xs="24" :md="12">
          <a-space direction="vertical">
            <div>
              <a-radio-group
                :value="durationSelectorValue"
                buttonStyle="solid"
                @change="handleTimeFilterChange">
                <a-radio-button value="day">
                  {{ $t('label.duration.24hours') }}
                </a-radio-button>
                <a-radio-button value="all">
                  {{ $t('label.all') }}
                </a-radio-button>
                <a-radio-button value="custom">
                  {{ $t('label.webhook.delivery.custom.period') }}
                </a-radio-button>
              </a-radio-group>
              <InfoCircleOutlined class="info-icon" :title="$t('message.webhook.deliveries.time.filter')"/>
            </div>
            <a-space v-if="durationSelectorValue === 'custom'" wrap>
              <a-button @click="openTimeFilter">{{ $t('label.select.period') }}</a-button>
              <span>{{ formattedPeriod }}</span>
            </a-space>
          </a-space>
        </a-col>
        <a-col :xs="24" :md="12" style="text-align: right; padding-right: 10px;">
          <span>
            <search-view
              :searchFilters="searchFilters"
              :searchParams="searchParams"
              :apiName="'listWebhookDeliveries'"
              @search="searchDeliveries"
            />
          </span>
        </a-col>
      </a-row>
    </div>
    <a-space v-if="canDeleteDeliveries" class="delivery-actions" wrap>
      <a-button v-if="selectedDeliveries.length" danger :disabled="!deliveryActionsReady" @click="confirmDeleteDeliveries(selectedDeliveries)">
        {{ $t('label.webhook.filter.delete.selected', { count: selectedDeliveries.length }) }}
      </a-button>
      <a-button v-if="canClearAllDeliveries" danger :disabled="!deliveryActionsReady" @click="confirmDeleteDeliveries(null)">
        {{ $t('label.webhook.delivery.clear.all') }}
      </a-button>
    </a-space>
    <a-alert v-if="operationNotice" class="delivery-actions" :type="operationNotice.type" :message="operationNotice.title" :description="operationNotice.detail" show-icon />
    <list-view
detail-tab
      :loading="tabLoading"
      :columns="columns"
      :items="deliveries"
      :actions="actions"
      :columnKeys="columnKeys"
      :explicitlyAllowRowSelection="canDeleteDeliveries"
      :selectedColumns="selectedColumnKeys"
      ref="listview"
      @update-selected-columns="updateSelectedColumns"
      inline-webhook-delivery
      @open-webhook-delivery="selectedDelivery = $event"
      @refresh="this.fetchData"
      @selection-change="updateSelectedRows"/>
    <detail-tab-pagination
      class="row-element"
      style="margin-top: 10px"
      size="small"
      :current="page"
      :pageSize="pageSize"
      :total="totalCount"
      :showTotal="total => this.$localStorage.get('LOCALE') == 'ko_KR' ?
        `${$t('label.total')} ${total} ${$t('label.items')} ${$t('label.of')} ${Math.min(total, 1+((page-1)*pageSize))}-${Math.min(page*pageSize, total)} ${$t('label.showing')}` :
        `${$t('label.showing')} ${Math.min(total, 1+((page-1)*pageSize))}-${Math.min(page*pageSize, total)} ${$t('label.of')} ${total} ${$t('label.items')}`"
      :pageSizeOptions="pageSizeOptions"
      @change="changePage"
      showSizeChanger
      showQuickJumper>
      <template #buildOptionText="props">
        <span>{{ props.value }} / {{ $t('label.page') }}</span>
      </template>
    </detail-tab-pagination>
  </div>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI, postAPI } from '@/api'
import { isAdmin } from '@/role'
import { genericCompare } from '@/utils/sort.js'
import moment from 'moment'
import DateTimeFilter from './DateTimeFilter'
import SearchView from '@/components/view/SearchView'
import ListView from '@/components/view/ListView'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { webhookDeliveryQuery, webhookDeliverySearch } from '@/utils/webhookDeliveryQuery'

export default {
  mixins: [listRefreshMixin(['fetchDeliveries'])],
  name: 'WebhookDeliveriesTab',
  components: {
    DateTimeFilter,
    SearchView,
    ListView,
    MoldDialog,
    MoldDialogActions
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      required: true
    }
  },
  data () {
    return {
      tabLoading: false,
      operationBusy: false,
      operationNotice: null,
      columnKeys: ['payload', 'eventtype', 'success', 'response', 'startdate', 'duration'],
      selectedColumnKeys: ['payload', 'eventtype', 'success', 'duration'],
      selectedRowKeys: [],
      columns: [],
      cols: [],
      deliveries: [],
      actions: [
        {
          api: 'executeWebhookDelivery',
          icon: 'retweet-outlined',
          label: 'label.redeliver',
          message: 'message.redeliver.webhook.delivery',
          dataView: true,
          popup: true
        },
        {
          api: 'deleteWebhookDelivery',
          icon: 'delete-outlined',
          label: 'label.delete.webhook.delivery',
          message: 'message.delete.webhook.delivery',
          dataView: true,
          popup: true
        }
      ],
      page: 1,
      pageSize: 20,
      totalCount: 0,
      durationSelectorValue: 'day',
      showTimeFilterModal: false,
      dateRangeError: false,
      selectedDelivery: null,
      deliveryDetailFields: ['response', 'headers', 'payload'],
      endDate: null,
      startDate: this.get24hrStartDate(),
      searchFilters: [
        'eventtype'
      ],
      searchParams: {}
    }
  },
  computed: {
    canDeleteDeliveries () { return 'deleteWebhookDelivery' in this.$store.getters.apis },
    deliveryActionsReady () {
      return !!this.resource.id && this.$route.params.id === this.resource.id &&
        this.$route.query.tab === 'recent.deliveries' && !this.tabLoading && !this.listRefreshFailed && !this.operationBusy
    },
    canClearAllDeliveries () { return this.durationSelectorValue === 'all' && this.searchParamsIsEmpty && this.totalCount > 0 },
    selectedDeliveries () { return this.deliveries.filter(item => this.selectedRowKeys.includes(item.id)) },
    formattedPeriod () {
      const startDate = this.formatDeliveryDate(this.startDate)
      const endDate = this.formatDeliveryDate(this.endDate)
      if (startDate && endDate) return this.$t('label.datetime.filter.period', { startDate, endDate })
      if (startDate) return this.$t('label.datetime.filter.starting', { startDate })
      if (endDate) return this.$t('label.datetime.filter.up.to', { endDate })
      return this.$t('label.all.available.data')
    },
    pageSizeOptions () {
      var sizes = [20, 50, 100, 200, this.$store.getters.defaultListViewPageSize]
      if (this.device !== 'desktop') {
        sizes.unshift(10)
      }
      return [...new Set(sizes)].sort(function (a, b) {
        return a - b
      }).map(String)
    },
    searchParamsIsEmpty () {
      if (!this.searchParams) {
        return true
      }
      return Object.keys(this.searchParams).length === 0
    }
  },
  created () {
    if (isAdmin()) {
      this.columnKeys.splice(2, 0, 'managementservername')
      this.selectedColumnKeys.splice(2, 0, 'managementservername')
      this.searchFilters.push('managementserverid')
    }
    this.updateColumns()
    this.applyDeliveryQuery()
  },
  watch: {
    'resource.id' () {
      this.deliveries = []
      this.totalCount = 0
      this.selectedRowKeys = []
      this.selectedDelivery = null
      this.operationNotice = null
      this.closeTimeFilterAction()
      this.applyDeliveryQuery()
    },
    '$route.query': 'applyDeliveryQuery'
  },
  methods: {
    applyDeliveryQuery () {
      const { params, query } = this.$route
      if (!this.resource.id || params.id !== this.resource.id || query.tab !== 'recent.deliveries') return
      this.searchParams = webhookDeliverySearch(query)
      if (!this.searchFilters.includes('managementserverid')) delete this.searchParams.managementserverid
      const page = Number(query.deliveryPage)
      this.page = Number.isSafeInteger(page) && page > 0 ? page : 1
      this.pageSize = this.pageSizeOptions.includes(query.deliveryPageSize) ? Number(query.deliveryPageSize) : Number(this.pageSizeOptions[0])
      this.durationSelectorValue = ['all', 'custom'].includes(query.deliveryPeriod) ? query.deliveryPeriod : 'day'
      const date = value => typeof value === 'string' && Number.isFinite(Date.parse(value)) ? new Date(value) : null
      if (this.durationSelectorValue === 'custom') {
        this.startDate = date(query.deliveryStart)
        this.endDate = date(query.deliveryEnd)
      } else {
        this.startDate = this.durationSelectorValue === 'day' ? this.get24hrStartDate() : null
        this.endDate = null
      }
      this.fetchData()
    },
    setDeliveryQuery ({
      search = this.searchParams,
      period = this.durationSelectorValue,
      start = this.startDate,
      end = this.endDate,
      page = 1,
      pageSize = this.pageSize
    } = {}) {
      const query = webhookDeliveryQuery(this.$route.query, search, period, start, end, page, pageSize)
      this.$router.push({ path: this.$route.path, query })
    },
    fetchData () {
      if ('listview' in this.$refs && this.$refs.listview) {
        this.$refs.listview.resetSelection()
      }
      this.fetchDeliveries()
    },
    fetchDeliveries () {
      const listRequest = this.listRequestToken('fetchDeliveries')

      if (!this.resource.id) {
        return
      }
      const params = {
        page: this.page,
        pagesize: this.pageSize,
        webhookid: this.resource.id,
        listall: true
      }
      if (this.startDate) {
        params.startDate = moment(this.startDate).format()
      }
      if (this.endDate) {
        params.endDate = moment(this.endDate).format()
      }
      if (this.searchParams?.searchQuery) {
        params.keyword = this.searchParams.searchQuery
      }
      if (this.searchParams?.managementserverid) {
        params.managementserverid = this.searchParams.managementserverid
      }
      if (this.searchParams?.eventtype) {
        params.eventtype = this.searchParams.eventtype
      }
      this.tabLoading = !listRequest.loaded
      return getAPI('listWebhookDeliveries', params).then(json => {
        if (!this.isListRequestCurrent('fetchDeliveries', listRequest)) return
        this.totalCount = json?.listwebhookdeliveriesresponse?.count || 0
        const lastPage = Math.max(1, Math.ceil(this.totalCount / this.pageSize))
        this.deliveries = json?.listwebhookdeliveriesresponse?.webhookdelivery || []
        this.tabLoading = false
        if (this.page > lastPage && this.operationNotice) this.setDeliveryQuery({ page: lastPage })
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchDeliveries', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (!listRequest.loaded) this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchDeliveries', listRequest)) return
        this.tabLoading = false
      })
    },
    changePage (page, pageSize) {
      this.setDeliveryQuery({ page: pageSize === this.pageSize ? page : 1, pageSize })
    },
    updateSelectedColumns (key) {
      if (this.selectedColumnKeys.includes(key)) {
        this.selectedColumnKeys = this.selectedColumnKeys.filter(x => x !== key)
      } else {
        this.selectedColumnKeys.push(key)
      }
      this.updateColumns()
    },
    updateColumns () {
      this.columns = []
      for (var columnKey of this.columnKeys) {
        const key = columnKey
        if (!this.selectedColumnKeys.includes(key)) continue
        var title = this.$t('label.' + String(key).toLowerCase())
        if (key === 'eventtype') {
          title = this.$t('label.event')
        }
        this.columns.push({
          key: key,
          title: title,
          dataIndex: key,
          sorter: (a, b) => { return genericCompare(a[key] || '', b[key] || '') }
        })
      }
      if (this.columns.length > 0) {
        this.columns[this.columns.length - 1].customFilterDropdown = true
      }
    },
    updateSelectedRows (keys) {
      this.selectedRowKeys = keys
    },
    confirmDeleteDeliveries (items) {
      const all = items === null
      if (!this.canDeleteDeliveries || !this.deliveryActionsReady || (all ? !this.canClearAllDeliveries : !items.length)) return
      const webhookid = this.resource.id
      const count = all ? this.totalCount : items.length
      this.$confirm({
        title: this.$t(all ? 'label.webhook.delivery.clear.all' : 'label.action.delete.webhook.deliveries'),
        content: this.$t(all ? 'message.webhook.delivery.delete.all' : 'message.webhook.delivery.delete.selected', {
          name: this.resource.name,
          count
        }),
        okText: this.$t('label.remove'),
        okType: 'danger',
        cancelText: this.$t('label.cancel'),
        onOk: () => this.deliveryActionsReady && this.resource.id === webhookid &&
          (!all || (this.canClearAllDeliveries && this.totalCount === count)) && this.deleteDeliveries(webhookid, items)
      })
    },
    async deleteDeliveries (webhookid, items) {
      if (this.operationBusy || !this.canDeleteDeliveries) return
      this.operationBusy = true
      this.operationNotice = null
      try {
        const targets = items === null ? [{ webhookid }] : items.map(({ id }) => ({ id, webhookid }))
        const results = await Promise.allSettled(targets.map(params => postAPI('deleteWebhookDelivery', params)))
        if (this.resource.id !== webhookid) return
        const failed = results.filter(row => row.status === 'rejected' || row.value?.deletewebhookdeliveryresponse?.success !== true).length
        const title = failed
          ? this.$t(items === null ? 'message.webhook.delivery.delete.failed' : 'message.webhook.delivery.delete.partial', { count: targets.length - failed, failed })
          : this.$t(items === null ? 'message.success.clear.webhook.deliveries' : 'message.success.delete')
        this.operationNotice = { type: failed ? 'error' : 'success', title }
      } finally {
        this.operationBusy = false
        if (this.resource.id === webhookid) this.fetchData()
      }
    },
    redeliverDeliveryConfirmation (item) {
      if (!this.deliveryActionsReady || !('executeWebhookDelivery' in this.$store.getters.apis)) return
      const webhookid = this.resource.id
      this.$confirm({
        title: this.$t('label.redeliver') + ' ' + item.eventtype,
        content: this.resource.payloadurl,
        okText: this.$t('label.redeliver'),
        cancelText: this.$t('label.cancel'),
        onOk: () => this.deliveryActionsReady && this.resource.id === webhookid && this.redeliverDelivery(webhookid, item.id)
      })
    },
    async redeliverDelivery (webhookid, id) {
      if (this.operationBusy) return
      this.operationBusy = true
      this.operationNotice = null
      try {
        const response = await postAPI('executeWebhookDelivery', { id })
        if (this.resource.id !== webhookid) return
        const delivery = response?.executewebhookdeliveryresponse?.webhookdelivery
        if (typeof delivery?.success !== 'boolean') throw new Error('Missing delivery result')
        this.operationNotice = {
          type: delivery.success ? 'success' : 'warning',
          title: this.$t(delivery.success ? 'message.success.redeliver.webhook.delivery' : 'message.webhook.delivery.receiver.failed'),
          detail: delivery.response
        }
      } catch {
        if (this.resource.id === webhookid) this.operationNotice = { type: 'error', title: this.$t('message.webhook.delivery.request.failed') }
      } finally {
        this.operationBusy = false
        if (this.resource.id === webhookid) this.fetchData()
      }
    },
    execAction (action) {
      if (action.api === 'executeWebhookDelivery') this.redeliverDeliveryConfirmation(action.resource)
      if (action.api === 'deleteWebhookDelivery') this.confirmDeleteDeliveries([action.resource])
    },
    get24hrStartDate () {
      const start = new Date()
      start.setDate(start.getDate() - 1)
      return start
    },
    handleTimeFilterChange (event) {
      const period = event.target.value
      if (period === 'custom') this.openTimeFilter()
      else this.setDeliveryQuery({ period })
    },
    openTimeFilter () {
      this.dateRangeError = false
      this.showTimeFilterModal = true
    },
    formatDeliveryDate (value) {
      if (!value || !Number.isFinite(Date.parse(value))) return ''
      const locale = this.$i18n.locale.replace('_', '-')
      return new Intl.DateTimeFormat(locale, {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        timeZoneName: 'short'
      }).format(new Date(value))
    },
    formatDeliveryContent (value) {
      if (value === null || value === undefined || value === '') return '—'
      if (typeof value !== 'string') return JSON.stringify(value, null, 2)
      try {
        return JSON.stringify(JSON.parse(value), null, 2)
      } catch {
        return value
      }
    },
    handleSubmitDateTimeFilter ({ startDate, endDate }) {
      if ((!startDate && !endDate) || (startDate && !Number.isFinite(startDate.getTime())) || (endDate && !Number.isFinite(endDate.getTime())) || (startDate && endDate && startDate > endDate)) {
        this.dateRangeError = true
        return
      }
      this.closeTimeFilterAction()
      this.setDeliveryQuery({ period: 'custom', start: startDate, end: endDate })
    },
    closeTimeFilterAction () {
      this.showTimeFilterModal = false
      this.dateRangeError = false
    },
    searchDeliveries (params) {
      const search = {}
      if ('searchQuery' in params) {
        Object.assign(search, this.searchParams, { searchQuery: params.searchQuery?.trim() })
      } else {
        for (const field of this.searchFilters) {
          if (params[field]) search[field] = params[field]
        }
        if (Object.keys(search).length && this.searchParams.searchQuery) search.searchQuery = this.searchParams.searchQuery
      }
      this.setDeliveryQuery({ search })
    }
  }
}
</script>

<style lang="scss" scoped>
.ant-tag {
  padding: 0 7px 0 0;
}
.ant-select {
  margin-left: 10px;
}
.info-icon {
  margin: 0 10px 0 5px;
}
.delivery-actions { margin-bottom: 12px; }
.filter-row {
  margin-bottom: 2.5%;
}
.webhook-delivery-section {
  margin-top: 12px;
}
.webhook-delivery-section pre {
  max-height: 20rem;
  overflow: auto;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
</style>
