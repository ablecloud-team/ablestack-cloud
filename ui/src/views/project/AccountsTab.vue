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
    <div class="project-tab-toolbar">
      <a-button v-if="addAction" type="primary" :disabled="membershipLocked || listRefreshFailed" @click="parentExecuteAction({ ...addAction, resource })">
        <template #icon><plus-outlined /></template>
        {{ $t('label.project.member.add') }}
      </a-button>
      <a-button :loading="loading" :disabled="membershipLocked" @click="refreshMembers">
        <template #icon><reload-outlined /></template>
        {{ $t('label.refresh') }}
      </a-button>
      <a-input-search
        v-model:value="keyword"
        :aria-label="$t('label.project.members.search.keyword')"
        :placeholder="$t('label.project.members.search.keyword')"
        :disabled="membershipLocked"
        allow-clear
        @search="searchMemberKeyword" />
      <a-popover v-model:visible="filtersOpen" placement="bottomLeft" trigger="click">
        <template v-if="filtersOpen" #content>
          <a-form :model="draftFilters" layout="vertical" class="member-filter-form" @finish="submitMemberFilters" v-ctrl-enter="submitMemberFilters">
            <a-form-item :label="$t('label.project.members.search.account')" html-for="project-member-filter-account">
              <a-input id="project-member-filter-account" v-model:value="draftFilters.account" allow-clear />
            </a-form-item>
            <a-form-item :label="$t('label.project.members.search.username')" html-for="project-member-filter-username">
              <a-input id="project-member-filter-username" v-model:value="draftFilters.username" allow-clear />
            </a-form-item>
            <a-form-item :label="$t('label.roletype')" html-for="project-member-filter-role">
              <a-select id="project-member-filter-role" v-model:value="draftFilters.role" :options="memberRoleOptions" allow-clear />
            </a-form-item>
            <a-form-item v-if="apis.listProjectRoles" :label="$t('label.project.role')" html-for="project-member-filter-projectrole">
              <a-select id="project-member-filter-projectrole" v-model:value="draftFilters.projectroleid" :options="customRoleOptions" allow-clear />
            </a-form-item>
            <a-space>
              <a-button @click="applyMemberFilters({})">{{ $t('label.reset') }}</a-button>
              <a-button type="primary" @click="submitMemberFilters">{{ $t('label.search') }}</a-button>
            </a-space>
          </a-form>
        </template>
        <a-button :aria-expanded="filtersOpen" :disabled="membershipLocked" @click="draftFilters = { ...memberFilters }">
          <filter-outlined /> {{ $t('label.advanced.search') }}
          <span v-if="advancedFilterCount">({{ advancedFilterCount }})</span>
        </a-button>
      </a-popover>
    </div>
    <a-alert v-if="listRefreshFailed" type="warning" show-icon :message="$t('message.list.refresh.stale')" class="project-tab-notice" />
    <detail-tab-table
      class="project-tab-table"
      size="small"
      :scroll="{ x: apis.listProjectRoles ? 630 : 510 }"
      :loading="loading"
      :columns="columns"
      :data-source="members"
      :pagination="false"
      :row-key="member => member.userid || member.accountid">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'projectrole'">
          {{ roleName(record.projectroleid) }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-space wrap>
            <tooltip-button
              v-for="action in actionsFor(record)"
              :key="action"
              :tooltip="$t(actionDetails[action].label)"
              :icon="actionDetails[action].icon"
              :danger="action === 'remove'"
              :disabled="loading || listRefreshFailed"
              size="small"
              @onClick="openAction(action, record)" />
          </a-space>
        </template>
      </template>
    </detail-tab-table>
    <detail-tab-pagination
      v-if="!listRefreshFailed || itemCount > 0"
      class="member-pagination"
      size="small"
      :current="page"
      :page-size="pageSize"
      :total="itemCount"
      :show-total="total => $t('label.total') + ' ' + total + ' ' + $t('label.items')"
      :page-size-options="['10', '20', '40', '80', '100']"
      show-size-changer
      @change="changePage" />
    <MoldDialog
      v-if="selected"
      size="sm"
      :title="$t(actionDetails[selected.action].label)"
      form-actions
      focus-guard
      @cancel="closeAction">
      <a-alert v-if="membershipError" show-icon type="error" :message="membershipError" class="member-action-status" />
      <a-descriptions :column="1" size="small" bordered>
        <a-descriptions-item :label="$t('label.project')">{{ resource.name }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.account')">{{ selected.member.account }}</a-descriptions-item>
        <a-descriptions-item v-if="selected.member.userid" :label="$t('label.user')">{{ selected.member.username }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.roletype')">{{ selected.member.role }}</a-descriptions-item>
      </a-descriptions>
      <p class="member-action-status">{{ $t(actionDetails[selected.action].effect) }}</p>
      <a-select
        v-if="selected.action === 'customRole'"
        v-model:value="selectedRole"
        :aria-label="$t('label.project.role')"
        :options="roleOptions"
        :disabled="membershipLocked"
        style="width: 100%" />
      <MoldDialogActions :busy="membershipBusy">
        <a-button :disabled="membershipBusy" @click="closeAction">{{ $t('label.cancel') }}</a-button>
        <a-button
          type="primary"
          :danger="selected.action === 'remove'"
          :loading="membershipBusy"
          :disabled="confirmDisabled"
          @click="submitAction">
          {{ $t(membershipJobId ? 'label.job.check.result' : actionDetails[selected.action].label) }}
        </a-button>
      </MoldDialogActions>
    </MoldDialog>
  </div>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { projectListScope, projectMemberFilters } from '@/utils/projectSearch'
import { getAPI } from '@/api'
import eventBus from '@/config/eventBus'
import { isProjectAdministrator, memberOperation, projectMemberActions, projectMembershipJob } from '@/utils/projectMembership'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import TooltipButton from '@/components/widgets/TooltipButton'

const actions = {
  promote: { label: 'label.project.member.promote', effect: 'message.project.member.promote', icon: 'arrow-up-outlined' },
  demote: { label: 'label.project.member.demote', effect: 'message.project.member.demote', icon: 'arrow-down-outlined' },
  transfer: { label: 'label.project.member.transfer', effect: 'message.project.member.transfer', icon: 'swap-outlined' },
  customRole: { label: 'label.project.member.custom.role', effect: 'message.project.member.custom.role', icon: 'edit-outlined' },
  remove: { label: 'label.project.member.remove', effect: 'message.project.member.remove', icon: 'delete-outlined' }
}

export default {
  name: 'AccountsTab',
  components: { MoldDialog, MoldDialogActions, TooltipButton },
  mixins: [listRefreshMixin(['fetchProjectAccounts'], {
    scopeKey: vm => [vm.resource.id, vm.page, vm.pageSize, vm.memberFilters, vm.memberSearchScope]
  }), projectMembershipJob],
  inject: ['parentFetchData', 'parentExecuteAction'],
  props: {
    resource: { type: Object, required: true }
  },
  data () {
    return {
      members: [],
      roles: [],
      memberFilters: {},
      draftFilters: {},
      keyword: '',
      filtersOpen: false,
      loading: false,
      page: 1,
      pageSize: 10,
      itemCount: 0,
      selected: null,
      selectedRole: '',
      actionDetails: actions
    }
  },
  computed: {
    apis () { return this.$store.getters.apis },
    addAction () {
      if (!this.parentExecuteAction || this.resource.state !== 'Active' || !isProjectAdministrator(this.resource, this.$store.getters.userInfo)) return null
      return this.$route.meta.actions?.find(action => ['addAccountToProject', 'addUserToProject'].includes(action.api) &&
        this.apis[action.api] && action.show(this.resource, this.$store.getters))
    },
    columns () {
      return [
        { key: 'account', dataIndex: 'account', title: this.$t('label.account') },
        { key: 'user', dataIndex: 'username', title: this.$t('label.user') },
        { key: 'role', dataIndex: 'role', title: this.$t('label.roletype') },
        ...(this.apis.listProjectRoles ? [{ key: 'projectrole', title: this.$t('label.project.role') }] : []),
        { key: 'actions', title: this.$t('label.actions') }
      ]
    },
    memberSearchScope () { return projectListScope(this.$store.getters, this.$store.state?.user?.token) },
    memberRoleOptions () { return ['Admin', 'Regular'].map(value => ({ value, label: value })) },
    customRoleOptions () { return this.roles.map(role => ({ value: role.id, label: role.name })) },
    roleOptions () { return [{ value: '', label: this.$t('label.none') }, ...this.customRoleOptions] },
    advancedFilterCount () { return Object.keys(this.memberFilters).filter(key => key !== 'keyword').length },
    confirmDisabled () {
      if (!this.selected || this.membershipBusy) return true
      if (this.membershipJobId) return false
      if (this.membershipUnknown || this.listRefreshFailed || !this.actionsFor(this.selected.member).includes(this.selected.action)) return true
      return this.selected.action === 'customRole' && this.selectedRole === (this.selected.member.projectroleid || '')
    }
  },
  watch: {
    'resource.id': 'resetProject',
    memberSearchScope: 'resetProject'
  },
  created () { this.resetProject() },
  mounted () { eventBus.on('project-members-updated', this.projectMembersUpdated) },
  beforeUnmount () {
    eventBus.off('project-members-updated', this.projectMembersUpdated)
    this.disposed = true
  },
  methods: {
    resetProject () {
      this.page = 1
      this.itemCount = 0
      this.members = []
      this.roles = []
      this.memberFilters = {}
      this.draftFilters = {}
      this.keyword = ''
      this.filtersOpen = false
      this.selected = null
      this.resetMembershipJob()
      this.fetchProjectAccounts()
      this.fetchProjectRoles()
    },
    refreshMembers () { return Promise.all([this.fetchProjectAccounts(), this.fetchProjectRoles()]) },
    projectMembersUpdated (projectId) {
      if (this.resource.id === projectId) this.fetchProjectAccounts()
    },
    actionsFor (member) { return projectMemberActions(this.resource, member, this.$store.getters.userInfo, this.apis) },
    roleName (id) { return id ? this.roles.find(role => role.id === id)?.name || id : '' },
    searchMemberKeyword (keyword) { this.applyMemberFilters({ ...this.memberFilters, keyword }) },
    submitMemberFilters () { this.applyMemberFilters({ ...this.draftFilters, keyword: this.keyword }) },
    applyMemberFilters (draft) {
      if (this.membershipLocked) return
      this.filtersOpen = false
      const filters = projectMemberFilters(draft)
      this.keyword = filters.keyword || ''
      if (JSON.stringify(filters) === JSON.stringify(this.memberFilters) && this.page === 1 && !this.listRefreshFailed) return
      this.memberFilters = filters
      this.members = []
      this.itemCount = 0
      this.page = 1
      this.fetchProjectAccounts()
    },
    changePage (page, size) {
      const next = size === this.pageSize ? page : 1
      if (next === this.page && size === this.pageSize) return
      this.page = next
      this.pageSize = size
      this.fetchProjectAccounts()
    },
    async fetchProjectRoles () {
      const projectid = this.resource.id
      if (!projectid || !this.apis.listProjectRoles) return
      try {
        const json = await getAPI('listProjectRoles', { projectid, ignoreproject: true }, { preserveOnFailure: true })
        if (this.resource.id === projectid) this.roles = json.listprojectrolesresponse.projectrole || []
      } catch (error) {
        if (this.resource.id === projectid) this.$notifyError(error)
      }
    },
    async fetchProjectAccounts () {
      const projectid = this.resource.id
      if (!projectid) return
      const request = this.listRequestToken('fetchProjectAccounts')
      this.loading = !request.loaded
      try {
        const json = await getAPI('listProjectAccounts', {
          projectid, page: this.page, pagesize: this.pageSize, ignoreproject: true, ...this.memberFilters
        }, { preserveOnFailure: true })
        if (!this.isListRequestCurrent('fetchProjectAccounts', request)) return
        const { count, projectaccount } = json.listprojectaccountsresponse
        this.members = projectaccount || []
        this.itemCount = count || 0
        this.listRefreshFailed = false
        const lastPage = Math.max(1, Math.ceil(this.itemCount / this.pageSize))
        if (lastPage < this.page) {
          this.page = lastPage
          this.fetchProjectAccounts()
        }
      } catch (error) {
        if (!this.isListRequestCurrent('fetchProjectAccounts', request)) return
        request.failed = true
        this.listRefreshFailed = true
        if (!request.loaded) this.$notifyError(error)
      } finally {
        if (this.isListRequestCurrent('fetchProjectAccounts', request)) this.loading = false
      }
    },
    openAction (action, member) {
      if (this.listRefreshFailed || !this.actionsFor(member).includes(action)) return
      this.resetMembershipJob()
      this.selectedRole = member.projectroleid || ''
      this.selected = { projectid: this.resource.id, action, member: { ...member } }
    },
    closeAction () {
      if (!this.membershipBusy) this.selected = null
    },
    async submitAction () {
      if (this.confirmDisabled) return
      const target = this.selected
      const [command, params] = memberOperation(target.projectid, target.member, target.action, this.selectedRole)
      if (!await this.runMembershipJob(command, params, this.actionDetails[target.action].label) || this.selected !== target) return
      this.selected = null
      await this.parentFetchData({ irefresh: true })
      if (!this.disposed && this.resource.id === target.projectid && isProjectAdministrator(this.resource, this.$store.getters.userInfo)) {
        this.fetchProjectAccounts()
      }
    }
  }
}
</script>

<style scoped>
.member-filter-form { width: min(330px, 72vw); }
.member-filter-form :deep(.ant-form-item) { margin-bottom: 12px; }
.member-pagination { margin: 16px 0 0; display: flex; flex-wrap: wrap; justify-content: flex-end; }
.member-action-status { margin: 12px 0; }
</style>
