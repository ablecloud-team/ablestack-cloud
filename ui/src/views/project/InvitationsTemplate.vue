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
  <section>
    <div class="project-tab-toolbar invitation-toolbar">
      <a-button v-if="inviteAction" type="primary" :disabled="busy" @click="parentExecuteAction({ ...inviteAction, resource })">
        <template #icon><plus-outlined /></template>
        {{ $t('label.project.member.invite') }}
      </a-button>
      <a-button ref="refreshButton" :loading="loading || listRefreshing > 0" :disabled="busy" @click="fetchData">
        <template #icon><reload-outlined /></template>
        {{ $t('label.refresh') }}
      </a-button>
      <a-select
        :aria-label="$t('label.state')"
        :value="state"
        :options="stateOptions"
        :disabled="busy"
        class="invitation-state-filter"
        @change="changeState" />
      <a-checkbox :checked="activeOnly" :disabled="busy" @change="setActiveOnly($event.target.checked)">
        {{ $t('label.project.invitation.active.only') }}
      </a-checkbox>
      <a-input-search
        v-model:value="draftKeyword"
        :aria-label="$t('label.project.invitation.search')"
        :placeholder="$t('label.project.invitation.search')"
        :disabled="busy"
        allow-clear
        @search="applyKeyword" />
    </div>
    <a-alert v-if="listRefreshFailed" show-icon type="warning" :message="$t('message.list.refresh.stale')" class="project-tab-notice invitation-notice" />
    <detail-tab-table
      class="project-tab-table"
      :loading="loading"
      :columns="columns"
      :data-source="rows"
      :scroll="{ x: outgoing ? 520 : 620 }"
      :pagination="false"
      size="small"
      row-key="id">
      <template #bodyCell="{ column, record, text }">
        <Status
          v-if="column.key === 'state'"
          :text="displayState(record)"
          :display-label="$t('state.' + displayState(record).toLowerCase())"
          display-text />
        <span v-else-if="column.key === 'recipient'">{{ recipient(record) }}</span>
        <TableOverflowText v-else-if="column.key === 'project'" :text="text || record.projectid" />
        <a-space v-else-if="column.key === 'actions'" wrap>
          <template v-if="record.active && !listRefreshFailed">
            <TooltipButton
              v-for="operation in operations"
              :key="operation"
              :tooltip="$t(actionLabels[operation])"
              :icon="actionIcons[operation]"
              :danger="operation !== 'accept'"
              size="small"
              @onClick="open(operation, record)" />
          </template>
        </a-space>
      </template>
    </detail-tab-table>
    <detail-tab-pagination
      v-if="!listRefreshFailed || total > 0"
      class="invitation-pager"
      size="small"
      :current="page"
      :page-size="pageSize"
      :total="total"
      :show-total="count => $t('label.total') + ' ' + count + ' ' + $t('label.items')"
      :page-size-options="['10', '20', '40', '80', '100']"
      show-size-changer
      @change="changePage" />
    <MoldDialogActions v-if="!outgoing">
      <a-button :disabled="busy" @click="$emit('close-action')">{{ $t('label.close') }}</a-button>
    </MoldDialogActions>

    <MoldDialog
      :visible="!!selected"
      :title="$t(actionLabels[selected?.operation] || 'label.project.invitation')"
      size="sm"
      form-actions
      focus-guard
      :after-close="restoreFocus"
      @cancel="closeConfirmation">
      <template v-if="selected">
        <a-alert v-if="membershipError" show-icon type="error" :message="membershipError" class="invitation-notice" />
        <a-descriptions :column="1" size="small" bordered class="invitation-summary">
          <a-descriptions-item :label="$t('label.project')">{{ selected.row.project || resource.name }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.project.invitation.recipient')">{{ recipient(selected.row) }}</a-descriptions-item>
        </a-descriptions>
        <p>{{ $t(actionMessages[selected.operation]) }}</p>
      </template>
      <MoldDialogActions :busy="busy">
        <a-button :disabled="busy" @click="closeConfirmation">{{ $t('label.cancel') }}</a-button>
        <a-button
          v-if="selected"
          type="primary"
          :danger="selected.operation !== 'accept'"
          :loading="busy"
          :disabled="staleTarget || (membershipUnknown && !membershipJobId)"
          @click="submit">
          {{ $t(membershipJobId ? 'label.job.check.result' : actionLabels[selected.operation]) }}
        </a-button>
      </MoldDialogActions>
    </MoldDialog>
  </section>
</template>

<script>
import { getAPI } from '@/api'
import eventBus from '@/config/eventBus'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { isProjectAdministrator, projectMembershipJob } from '@/utils/projectMembership'
import { projectListScope } from '@/utils/projectSearch'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import TableOverflowText from '@/components/view/TableOverflowText'
import Status from '@/components/widgets/Status'
import TooltipButton from '@/components/widgets/TooltipButton'

const actionLabels = {
  accept: 'label.accept.project.invitation',
  decline: 'label.decline.invitation',
  revoke: 'label.project.invitation.revoke'
}
const actionIcons = { accept: 'check-outlined', decline: 'close-outlined', revoke: 'undo-outlined' }
const actionMessages = {
  accept: 'label.confirmacceptinvitation',
  decline: 'label.confirmdeclineinvitation',
  revoke: 'message.project.invitation.revoke'
}

export default {
  name: 'InvitationsTemplate',
  components: { MoldDialog, MoldDialogActions, TableOverflowText, Status, TooltipButton },
  mixins: [listRefreshMixin(['fetchData'], {
    active: vm => !vm.selected,
    scopeKey: vm => [vm.scope, vm.page, vm.pageSize, vm.state, vm.activeOnly, vm.keyword]
  }), projectMembershipJob],
  inject: { parentExecuteAction: { default: null } },
  props: {
    resource: { type: Object, default: () => ({}) },
    currentAction: { type: Object, default: null }
  },
  emits: ['refresh-data', 'close-action'],
  data () {
    return {
      rows: [],
      page: 1,
      pageSize: 10,
      total: 0,
      state: '',
      activeOnly: true,
      draftKeyword: '',
      keyword: '',
      loading: false,
      checking: false,
      staleTarget: false,
      selected: null,
      focusTarget: null,
      actionLabels,
      actionIcons,
      actionMessages
    }
  },
  computed: {
    outgoing () { return !this.currentAction },
    projectid () { return this.outgoing ? this.resource.id : undefined },
    scope () {
      return JSON.stringify([this.outgoing, this.projectid, projectListScope(this.$store.getters, this.$store.state?.user?.token)])
    },
    busy () { return this.checking || this.membershipBusy },
    apis () { return this.$store.getters.apis },
    operations () {
      if (this.outgoing) return this.apis.deleteProjectInvitation && isProjectAdministrator(this.resource, this.$store.getters.userInfo) ? ['revoke'] : []
      return this.apis.updateProjectInvitation ? ['accept', 'decline'] : []
    },
    inviteAction () {
      if (!this.outgoing || !this.parentExecuteAction || this.resource.state !== 'Active') return null
      return this.$route.meta.actions?.find(action => ['addAccountToProject', 'addUserToProject'].includes(action.api) &&
        this.apis[action.api] && action.show(this.resource, this.$store.getters))
    },
    stateOptions () {
      return [{ value: '', label: this.$t('label.all') }, ...['Pending', 'Completed', 'Declined', 'Expired']
        .map(value => ({ value, label: this.$t('state.' + value.toLowerCase()) }))]
    },
    columns () {
      return [
        ...(!this.outgoing ? [{ key: 'project', dataIndex: 'project', title: this.$t('label.project') }] : []),
        { key: 'recipient', title: this.$t('label.project.invitation.recipient') },
        { key: 'state', dataIndex: 'state', title: this.$t('label.state'), width: 108 },
        ...(this.operations.length ? [{ key: 'actions', title: this.$t('label.actions'), width: 104 }] : [])
      ]
    }
  },
  watch: {
    scope () {
      this.rows = []
      this.total = 0
      this.page = 1
      this.selected = null
      this.resetMembershipJob()
      this.fetchData()
    }
  },
  created () { this.fetchData() },
  mounted () { eventBus.on('project-invitations-updated', this.invitationsUpdated) },
  beforeUnmount () { eventBus.off('project-invitations-updated', this.invitationsUpdated) },
  methods: {
    displayState (row) { return row.state === 'Pending' && !row.active ? 'Expired' : row.state },
    recipient (row) {
      if (row.userid) {
        const user = row.username || row.userid
        const account = row.account && row.account !== user ? ' (' + row.account + ')' : ''
        return this.$t('label.user') + ' · ' + user + account
      }
      if (row.account) return this.$t('label.account') + ' · ' + row.account
      return row.email || '—'
    },
    invites (params) {
      return getAPI('listProjectInvitations', {
        direction: this.outgoing ? 'outgoing' : 'incoming',
        projectid: this.projectid,
        ignoreproject: true,
        ...params
      }, { preserveOnFailure: true, timeout: 15000 }).then(reply => {
        const result = reply.listprojectinvitationsresponse
        return { rows: result.projectinvitation || [], count: result.count || 0 }
      })
    },
    async fetchData () {
      if (this.outgoing && !this.projectid) return
      const request = this.listRequestToken('fetchData')
      this.loading = !request.loaded
      try {
        const result = await this.invites({
          page: this.page,
          pagesize: this.pageSize,
          state: this.state || undefined,
          activeonly: this.activeOnly,
          keyword: this.keyword || undefined
        })
        if (!this.isListRequestCurrent('fetchData', request)) return
        const lastPage = Math.max(1, Math.ceil(result.count / this.pageSize))
        if (this.page > lastPage) {
          this.page = lastPage
          await this.fetchData()
          return
        }
        this.rows = result.rows
        this.total = result.count
      } catch {
        if (this.isListRequestCurrent('fetchData', request)) {
          request.failed = true
          this.listRefreshFailed = true
        }
      } finally {
        if (this.isListRequestCurrent('fetchData', request)) this.loading = false
      }
    },
    invitationsUpdated (projectid) {
      if (!this.outgoing || projectid === this.projectid) this.fetchData()
    },
    applyKeyword (value) { this.keyword = value.trim(); this.page = 1; this.fetchData() },
    changeState (state) {
      this.state = state
      if (state !== 'Pending') this.activeOnly = false
      this.page = 1
      this.fetchData()
    },
    setActiveOnly (value) {
      this.activeOnly = value
      if (value) this.state = ''
      this.page = 1
      this.fetchData()
    },
    changePage (page, size) {
      this.page = this.pageSize === size ? page : 1
      this.pageSize = size
      this.fetchData()
    },
    open (operation, row) {
      if (this.busy || this.listRefreshFailed || !row.active || !this.operations.includes(operation)) return
      this.focusTarget = document.activeElement
      this.staleTarget = false
      this.resetMembershipJob()
      this.selected = { operation, row, scope: this.scope }
    },
    closeConfirmation () {
      if (!this.busy) this.selected = null
    },
    restoreFocus () {
      const target = this.focusTarget?.isConnected ? this.focusTarget : this.$refs.refreshButton?.$el
      this.focusTarget = null
      if (target) target.focus({ preventScroll: true })
    },
    async submit () {
      if (!this.selected || this.busy || this.staleTarget) return
      const { row, operation, scope } = this.selected
      if (scope !== this.scope || !this.operations.includes(operation)) return
      if (!this.membershipJobId) {
        this.checking = true
        try {
          const result = await this.invites({ id: row.id, activeonly: true })
          if (scope !== this.scope) return
          if (!result.rows.some(item => item.id === row.id && item.projectid === row.projectid && item.active)) {
            this.staleTarget = true
            this.membershipError = this.$t('message.project.invitation.no.longer.active')
            return
          }
        } catch {
          this.membershipError = this.$t('message.project.invitation.verify.failed')
          return
        } finally {
          this.checking = false
        }
      }
      const revoke = operation === 'revoke'
      const params = revoke ? { id: row.id } : {
        projectid: row.projectid,
        ...(row.userid ? { userid: row.userid } : { account: row.account }),
        accept: operation === 'accept'
      }
      const ok = await this.runMembershipJob(revoke ? 'deleteProjectInvitation' : 'updateProjectInvitation', params, actionLabels[operation])
      if (scope !== this.scope || this.listRefreshDisposed) return
      if (ok) {
        this.closeConfirmation()
        eventBus.emit('project-invitations-updated', row.projectid)
        if (!this.outgoing) this.$emit('refresh-data', { irefresh: true })
      } else if (!this.membershipJobId && !this.membershipUnknown) {
        this.fetchData()
      }
    }
  }
}
</script>

<style scoped>
.invitation-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 16px; }
.invitation-toolbar :deep(.ant-input-search) { width: 250px; max-width: 100%; }
.invitation-state-filter { min-width: 104px; }
.invitation-notice, .invitation-summary { margin-bottom: 12px; }
.invitation-summary { overflow-wrap: anywhere; }
.invitation-pager { display: flex; flex-wrap: wrap; justify-content: flex-end; margin-top: 16px; }
</style>
