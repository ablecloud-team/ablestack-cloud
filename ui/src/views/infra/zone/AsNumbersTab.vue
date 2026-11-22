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
  <div class="asn-range-tab">
    <router-link v-if="returnLocation" class="asn-return-link" :to="returnLocation"><arrow-left-outlined /> {{ $t('label.asn.return') }}</router-link>
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <resource-table-toolbar :loading="fetchLoading || listRefreshing > 0" @refresh="fetchData()">
      <a-button
        ref="createButton"
        type="primary"
        :disabled="!canCreate || fetchLoading || listRefreshFailed || loading || !!deleteTarget"
        @click="showCreateASRange">
        <template #icon><plus-outlined /></template> {{ $t('label.create.asnrange') }}
      </a-button>
    </resource-table-toolbar>
    <a-table
      :loading="fetchLoading"
      :locale="{ emptyText: $t(listRefreshFailed ? 'message.asn.range.lookup.failed' : 'label.no.data') }"
      rowKey="id"
      size="middle"
      :scroll="{ x: 800 }"
      :dataSource="asnRanges"
      :columns="columns"
      :pagination="false">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'range'">{{ record.startasn }} - {{ record.endasn }}</template>
        <template v-if="column.key === 'count'">{{ Number(record.endasn) - Number(record.startasn) + 1 }}</template>
        <template v-if="column.key === 'created'">{{ record.created ? $toLocaleDate(record.created) : '—' }}</template>
        <span v-if="column.key === 'actions' && canDelete" :ref="el => { if (el) deleteButtons[record.id] = el }">
          <tooltip-button
          :tooltip="$t('label.delete.asnrange')"
          type="primary"
          :danger="true"
          :disabled="loading || submitting || !!operation || !!deleteTarget"
          icon="delete-outlined"
          @onClick="onDelete(record)" />
        </span>
      </template>
    </a-table>
    <asn-range-delete-dialog v-if="deleteTarget" :key="creationContext + deleteTarget.id" :target="deleteTarget" :context="creationContext" @cancel="closeDelete" @complete="completeDelete" />
    <mold-dialog
      v-if="addASRangeModal"
      :visible="addASRangeModal"
      size="md"
      formActions
      focusGuard
      :title="$t('label.create.asnrange')"
      @cancel="closeAction">
      <a-form
        id="asn-range-create-form"
        name="asn-range-create"
        ref="createForm"
        class="form-layout asn-range-create"
        layout="vertical"
        :model="form"
        :rules="rules"
        @keydown.ctrl.enter.prevent="!$event.repeat && handleSubmit()"
        @finishFailed="focusFirstError"
        @finish="handleSubmit">
        <a-descriptions class="mold-dialog-summary" :column="1" size="small">
          <a-descriptions-item :label="$t('label.zone')">{{ resource.name || resource.id }}</a-descriptions-item>
          <a-descriptions-item v-if="preview" :label="$t('label.asn.range.preview')">{{ preview.startasn }}–{{ preview.endasn }} ({{ $t('message.asn.range.count', { count: preview.count }) }})</a-descriptions-item>
        </a-descriptions>
        <a-alert class="asn-range-notice" type="info" show-icon :message="$t('message.asn.range.policy')" />
        <a-alert v-if="error" class="asn-range-notice" type="error" show-icon :message="error" />
        <a-alert
          v-if="operation"
          class="asn-range-notice"
          type="warning"
          show-icon
          :message="$t(operation.status === 'unverified' ? 'message.asn.range.unverified' : 'message.asn.range.unknown')">
          <template #description>
            <a-button :loading="checking" :disabled="submitting || checking" @click="recheck">{{ $t('label.asn.range.recheck') }}</a-button>
            <template v-if="operation.absent">
              <a-checkbox v-model:checked="acknowledged" :disabled="checking">{{ $t('message.asn.range.acknowledge') }}</a-checkbox>
              <a-button :disabled="!acknowledged || checking" @click="endTracking">{{ $t('label.asn.range.endtracking') }}</a-button>
            </template>
          </template>
        </a-alert>
        <a-form-item name="startasn" :label="$t('label.asn.range.start')">
          <a-input ref="startasnInput" v-model:value="form.startasn" inputmode="numeric" :disabled="locked" v-focus="true" />
        </a-form-item>
        <a-form-item name="endasn" :label="$t('label.asn.range.end')">
          <a-input ref="endasnInput" v-model:value="form.endasn" inputmode="numeric" :disabled="locked" />
        </a-form-item>
        <mold-dialog-actions :busy="submitting || checking">
          <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" html-type="submit" form="asn-range-create-form" :loading="submitting" :disabled="locked || !canCreate">{{ $t('label.asn.range.register') }}</a-button>
        </mold-dialog-actions>
      </a-form>
    </mold-dialog>
  </div>
</template>

<script>
import ResourceTableToolbar from '@/components/view/ResourceTableToolbar'
import { getAPI, postAPI } from '@/api'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { asnCreationContext, asnOperation, trackAsnOperation, clearAsnOperation, asnRangeErrors, asnRangeValues, asnRangeMatches, confirmAsnRange } from '@/utils/asnRange'
import { asnReturnLocation } from '@/utils/asnOperations'
import { confirmedList, deletionTarget } from '@/utils/asnRangeDeletion'
import AsnRangeDeleteDialog from './AsnRangeDeleteDialog'
import TooltipButton from '@/components/widgets/TooltipButton'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'

export default {
  components: { ResourceTableToolbar, AsnRangeDeleteDialog, TooltipButton, MoldDialog, MoldDialogActions },
  mixins: [listRefreshMixin(['fetchData'], { scopeKey: vm => vm.creationContext })],
  props: { resource: { type: Object, required: true } },
  emits: ['refresh-data', 'close-action'],
  data () {
    return {
      asnRanges: [],
      deleteTarget: null,
      deleteButtons: {},
      fetchLoading: false,
      loading: false,
      addASRangeModal: false,
      form: { startasn: '', endasn: '' },
      submitting: false,
      checking: false,
      error: '',
      operation: null,
      acknowledged: false,
      disposed: false,
      columns: [
        { key: 'range', title: this.$t('label.asnrange'), width: 270 },
        { key: 'count', title: this.$t('label.asn.count'), width: 90 },
        { key: 'created', title: this.$t('label.created'), width: 180 },
        { key: 'actions', title: this.$t('label.delete.asnrange'), width: 70 }
      ]
    }
  },
  computed: {
    returnLocation () { return asnReturnLocation(this.$route.query?.asnreturn, this.$store.getters) },
    creationContext () { return asnCreationContext(this.$store.getters, this.$store.state?.user?.token, this.resource.id) },
    canCreate () { return !!this.resource.id && this.resource.routedmodeenabled === true && 'createASNRange' in this.$store.getters.apis },
    canDelete () { return !!this.resource.id && 'deleteASNRange' in this.$store.getters.apis },
    locked () { return this.submitting || this.checking || !!this.operation },
    preview () { return asnRangeValues(this.form) },
    rules () {
      return Object.fromEntries(['startasn', 'endasn'].map(key => [key, [{
        validator: async () => {
          const reason = asnRangeErrors(this.form)[key]
          if (reason) throw new Error(this.$t('message.asn.range.' + reason))
        }
      }]]))
    }
  },
  watch: {
    creationContext () {
      this.addASRangeModal = false; this.submitting = false; this.checking = false; this.loading = false
      this.operation = null; this.error = ''; this.asnRanges = []; this.deleteTarget = null; this.deleteButtons = {}
      if (this.resource.id) this.fetchData()
    }
  },
  created () { this.fetchData() },
  beforeUnmount () { this.disposed = true },
  methods: {
    current (context) { return !this.disposed && context === this.creationContext },
    async fetchData () {
      const request = this.listRequestToken('fetchData')
      this.fetchLoading = !request.loaded
      try {
        const data = await getAPI('listASNRanges', { zoneid: this.resource.id }, { preserveOnFailure: true })
        if (!data?.listasnrangesresponse || data.listasnrangesresponse.errorcode) throw new Error('ASN ranges unavailable')
        if (!this.isListRequestCurrent('fetchData', request)) return false
        const { rows: ranges, count } = confirmedList(data, 'listasnrangesresponse', 'asnumberrange')
        if (count !== ranges.length || ranges.some(r => r.zoneid !== this.resource.id || !r.id)) throw Error('ASN range response scope')
        this.asnRanges = ranges
        return true
      } catch (error) {
        if (this.isListRequestCurrent('fetchData', request)) {
          request.failed = true; this.listRefreshFailed = true
          if (!request.loaded) this.$notifyError(error)
        }
        return false
      } finally {
        if (this.isListRequestCurrent('fetchData', request)) this.fetchLoading = false
      }
    },
    showCreateASRange () {
      if (!this.canCreate || this.loading || this.submitting || this.deleteTarget) return
      this.operation = asnOperation(this.creationContext) || null
      this.form = this.operation ? { startasn: this.operation.expected.startasn, endasn: this.operation.expected.endasn } : { startasn: '', endasn: '' }
      this.acknowledged = false; this.error = ''; this.addASRangeModal = true
    },
    closeAction () {
      if (this.submitting || this.checking) return
      this.addASRangeModal = false; this.$emit('close-action')
      this.$nextTick(() => this.$refs.createButton?.$el?.focus())
    },
    focusFirstError (error) {
      const field = error?.errorFields?.[0]?.name?.[0]
      if (!['startasn', 'endasn'].includes(field)) return
      if (this.$refs.createForm) this.$refs.createForm.scrollToField(field)
      this.$nextTick(() => this.$refs[field + 'Input']?.focus())
    },
    async handleSubmit () {
      if (!this.addASRangeModal || this.locked || !this.canCreate || this.loading) return
      const context = this.creationContext
      this.submitting = true; this.error = ''
      let operation
      try {
        await this.$refs.createForm.validate()
        const values = asnRangeValues(this.form)
        if (!values || !this.current(context) || !this.canCreate) return
        const expected = { ...values, zoneid: this.resource.id }
        const zone = (await getAPI('listZones', { id: expected.zoneid }))?.listzonesresponse?.zone?.find(row => row.id === expected.zoneid)
        if (!this.current(context) || !this.canCreate) return
        if (!zone?.routedmodeenabled) { this.error = this.$t('message.asn.range.context'); return }
        operation = { expected, status: 'pending', absent: false }
        trackAsnOperation(context, operation)
        const json = await postAPI('createASNRange', { zoneid: expected.zoneid, startasn: expected.startasn, endasn: expected.endasn }, { preserveOnFailure: true })
        const receipt = json?.createasnrangeresponse?.asnumberrange || json?.createasnrangeresponse
        operation.status = 'unverified'
        if (!asnRangeMatches(receipt, expected)) throw new Error('ASN receipt mismatch')
        operation.receiptId = receipt.id
        if (!this.current(context)) return
        const observed = await confirmAsnRange(getAPI, expected, receipt.id)
        if (!observed.range) throw new Error('ASN range not yet visible')
        if (this.current(context)) this.complete(context, observed.ranges)
      } catch (error) {
        if (operation) {
          const code = error?.response?.data?.createasnrangeresponse?.errorcode
          if ([401, 431, 432, 435, 436].includes(code)) {
            clearAsnOperation(context)
            if (this.current(context)) this.error = error.response.data.createasnrangeresponse.errortext || this.$t('message.asn.range.rejected')
          } else {
            operation.status = operation.status === 'unverified' ? 'unverified' : 'unknown'
            if (this.current(context)) this.operation = operation
          }
        } else if (this.current(context) && !error?.errorFields) this.error = this.$t('message.asn.range.context')
        if (this.current(context) && error?.errorFields?.length) this.focusFirstError(error)
      } finally {
        if (operation?.status === 'pending') operation.status = 'unknown'
        if (this.current(context)) {
          this.submitting = false
          if (!asnOperation(context)) this.operation = null
        }
      }
    },
    complete (context, ranges) {
      clearAsnOperation(context); this.operation = null; this.asnRanges = ranges; this.error = ''
      this.addASRangeModal = false; this.$emit('refresh-data')
      this.$notification.success({ message: this.$t('message.success.create.asnrange') })
      this.$nextTick(() => this.$refs.createButton?.$el?.focus())
    },
    async recheck () {
      if (this.submitting || this.checking || !this.operation || !this.canCreate) return
      const context = this.creationContext; const operation = this.operation
      this.checking = true; this.acknowledged = false; this.error = ''; operation.absent = false
      try {
        const observed = await confirmAsnRange(getAPI, operation.expected, operation.receiptId)
        if (!this.current(context)) return
        if (observed.range) this.complete(context, observed.ranges)
        else operation.absent = true
      } catch (_) {
        if (this.current(context)) this.error = this.$t('message.asn.range.lookup.failed')
      } finally { if (this.current(context)) this.checking = false }
    },
    endTracking () {
      if (this.submitting || this.checking || !this.operation?.absent || !this.acknowledged) return
      clearAsnOperation(this.creationContext); this.operation = null; this.acknowledged = false; this.error = ''
    },
    onDelete (record) {
      if (!this.canDelete || this.loading || this.submitting || this.deleteTarget || asnOperation(this.creationContext)) return
      try { this.deleteTarget = deletionTarget(this.resource, record) } catch (_) { this.$notifyError(new Error(this.$t('message.asn.delete.lookup'))) }
    },
    closeDelete () {
      const id = this.deleteTarget?.id
      this.deleteTarget = null
      this.$nextTick(() => {
        const source = this.deleteButtons[id]?.querySelector('button')
        if (source?.isConnected) source.focus()
        else if (this.$refs.createButton?.$el) this.$refs.createButton.$el.focus()
      })
    },
    completeDelete ({ ranges, accepted }) {
      this.asnRanges = ranges; this.closeDelete(); this.$emit('refresh-data')
      this.$notification[accepted ? 'success' : 'info']({ message: this.$t(accepted ? 'message.success.delete.asnrange' : 'message.asn.delete.synchronized') })
    }
  }
}
</script>

<style scoped>
.asn-return-link { display: inline-block; margin-bottom: 12px; }
.asn-range-tab { color: var(--ui-text-primary); }
.asn-range-tab :deep(.ant-table) { color: var(--ui-text-secondary); }
.asn-range-tab :deep(.ant-table-container),
.asn-range-tab :deep(.ant-table-content),
.asn-range-tab :deep(th),
.asn-range-tab :deep(td) { border-color: var(--ui-border) !important; }
.asn-range-tab :deep(.ant-table-thead > tr > th) { color: var(--ui-text-secondary); background: var(--ui-bg-elevated) !important; }
.asn-range-tab :deep(.ant-table-thead > tr > th::before) { background: var(--ui-border) !important; }
.asn-range-tab :deep(.ant-table-tbody > tr > td) { color: var(--ui-text-secondary); background: var(--ui-bg-surface); }
.asn-range-tab :deep(.ant-table-tbody > tr:hover > td) { background: var(--ui-bg-hover) !important; }
.asn-range-create :deep(.ant-form-item-extra) { color: var(--ui-text-secondary); }
.asn-range-notice { margin-bottom: 16px; }
.asn-range-create :deep(.ant-checkbox-wrapper) { display: block; margin: 12px 0; }
</style>
