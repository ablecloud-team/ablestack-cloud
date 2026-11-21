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
  <MoldDialog
    size="sm"
    :title="$t('label.project.member.add')"
    form-actions
    focus-guard
    @cancel="close">
    <a-form layout="vertical" v-ctrl-enter="submit">
      <a-alert v-if="membershipError" type="error" show-icon :message="membershipError" class="member-feedback" />
      <a-alert v-if="candidateError" type="warning" show-icon :message="candidateError" class="member-feedback">
        <template #action><a-button size="small" @click="loadCandidates()">{{ $t('label.retry') }}</a-button></template>
      </a-alert>
      <a-alert v-if="resource.state !== 'Active'" type="warning" show-icon :message="$t('message.project.member.inactive')" class="member-feedback" />
      <a-form-item :label="$t('label.project')">
        <span>{{ resource.name }} · {{ resource.domain }}</span>
      </a-form-item>
      <a-form-item v-if="modes.length > 1">
        <a-radio-group :value="kind" :disabled="membershipLocked" @change="changeKind">
          <a-radio value="account">{{ $t('label.account') }}</a-radio>
          <a-radio value="user">{{ $t('label.user') }}</a-radio>
        </a-radio-group>
      </a-form-item>
      <a-form-item :label="$t(kind === 'account' ? 'label.account' : 'label.username')" html-for="project-member-name">
        <a-select
          v-if="canDiscover"
          id="project-member-name"
          v-model:value="memberName"
          :placeholder="$t('label.select')"
          :options="candidates"
          :disabled="membershipLocked || resource.state !== 'Active'"
          :loading="loadingCandidates"
          :filter-option="false"
          allow-clear
          show-search
          @search="searchCandidates" />
        <a-input v-else id="project-member-name" v-model:value="memberName" :disabled="membershipLocked" />
      </a-form-item>
      <a-form-item v-if="invitationRequired && supports('email')" :label="$t('label.email')" html-for="project-member-email">
        <a-input id="project-member-email" v-model:value="email" type="email" :disabled="membershipLocked" />
      </a-form-item>
      <a-form-item v-if="supports('roletype')" :label="$t('label.roletype')" html-for="project-member-type">
        <a-select
          id="project-member-type"
          v-model:value="roleType"
          :options="[{ value: 'Regular', label: 'Regular' }, { value: 'Admin', label: 'Admin' }]"
          :disabled="membershipLocked" />
      </a-form-item>
      <a-form-item v-if="supports('projectroleid') && apis.listProjectRoles" :label="$t('label.project.role')" html-for="project-member-role">
        <a-select
          id="project-member-role"
          v-model:value="projectRoleId"
          :options="projectRoles"
          :placeholder="$t('label.none')"
          :disabled="membershipLocked"
          allow-clear />
      </a-form-item>
    </a-form>
    <MoldDialogActions :busy="membershipBusy">
      <a-button :disabled="membershipBusy" @click="close">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="membershipBusy" :disabled="submitDisabled" @click="submit">
        {{ $t(membershipJobId ? 'label.job.check.result' : 'label.project.member.add') }}
      </a-button>
    </MoldDialogActions>
  </MoldDialog>
</template>

<script>
import { getAPI } from '@/api'
import eventBus from '@/config/eventBus'
import { eligibleProjectCandidates, isProjectAdministrator, projectMembershipJob } from '@/utils/projectMembership'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'

const commands = { account: 'addAccountToProject', user: 'addUserToProject' }

export default {
  name: 'AddAccountOrUserToProject',
  components: { MoldDialog, MoldDialogActions },
  mixins: [projectMembershipJob],
  props: { resource: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data () {
    return {
      kind: '',
      memberName: undefined,
      email: '',
      roleType: 'Regular',
      projectRoleId: undefined,
      projectRoles: [],
      members: null,
      candidates: [],
      candidateError: '',
      loadingCandidates: false,
      searchTimer: null,
      searchVersion: 0,
      disposed: false
    }
  },
  computed: {
    apis () { return this.$store.getters.apis },
    caller () { return this.$store.getters.userInfo },
    modes () { return Object.keys(commands).filter(kind => this.apis[commands[kind]]) },
    command () { return commands[this.kind] },
    discoveryCommand () { return this.kind === 'user' ? 'listUsers' : 'listAccounts' },
    canDiscover () {
      return ['Admin', 'DomainAdmin'].includes(this.caller?.roletype) && !!this.apis[this.discoveryCommand] && !!this.apis.listProjectAccounts
    },
    invitationRequired () { return !!this.$store.getters.features?.projectinviterequired },
    submitDisabled () {
      if (this.membershipBusy) return true
      if (this.membershipJobId) return false
      if (this.membershipUnknown || !this.apis[this.command] || this.resource.state !== 'Active' || !isProjectAdministrator(this.resource, this.caller)) return true
      const hasTarget = !!this.memberName?.trim() ||
        (this.kind === 'account' && this.invitationRequired && this.supports('email') && !!this.email.trim())
      return !hasTarget || (this.canDiscover && !!this.memberName && !this.members)
    }
  },
  created () {
    this.kind = this.modes[0] || 'account'
    this.loadCandidates()
    this.loadRoles()
  },
  beforeUnmount () {
    this.disposed = true
    clearTimeout(this.searchTimer)
  },
  methods: {
    supports (field) { return !!this.apis[this.command]?.params?.some(param => param.name === field) },
    changeKind (event) {
      clearTimeout(this.searchTimer)
      this.searchVersion++
      this.kind = event.target.value
      this.memberName = undefined
      this.email = ''
      this.roleType = 'Regular'
      this.projectRoleId = undefined
      this.resetMembershipJob()
      this.candidates = []
      this.candidateError = ''
      this.loadCandidates()
    },
    async loadMembers () {
      const members = []
      let count = 0
      let page = 1
      do {
        const json = await getAPI('listProjectAccounts', {
          projectid: this.resource.id,
          page: page++,
          pagesize: 100,
          ignoreproject: true
        }, { preserveOnFailure: true })
        const result = json.listprojectaccountsresponse
        const rows = result.projectaccount || []
        members.push(...rows)
        count = result.count ?? rows.length
        if (members.length < count && !rows.length) throw new Error('Incomplete project membership response')
      } while (members.length < count && !this.disposed)
      return members
    },
    searchCandidates (keyword) {
      clearTimeout(this.searchTimer)
      this.searchVersion++
      this.searchTimer = setTimeout(() => this.loadCandidates(keyword), 250)
    },
    async loadCandidates (keyword = '') {
      const version = ++this.searchVersion
      if (!this.canDiscover || this.resource.state !== 'Active') return
      const domainid = this.resource.domainid
      const command = this.discoveryCommand
      const kind = this.kind
      this.loadingCandidates = true
      this.candidateError = ''
      try {
        const members = this.members || await this.loadMembers()
        if (this.disposed || version !== this.searchVersion) return
        this.members = members
        const field = kind === 'user' ? 'username' : 'name'
        const params = { domainid, state: 'enabled', listall: true, page: 1, pagesize: 100, ignoreproject: true }
        if (keyword.trim()) params.keyword = keyword.trim()
        const json = await getAPI(command, params, { preserveOnFailure: true })
        if (this.disposed || version !== this.searchVersion) return
        const rows = json[command.toLowerCase() + 'response'][kind] || []
        this.candidates = eligibleProjectCandidates(kind, rows, members, domainid)
          .map(record => ({ label: record[field], value: record[field] }))
      } catch (error) {
        if (this.disposed || version !== this.searchVersion) return
        this.candidates = []
        this.candidateError = this.$t('message.project.member.candidates.failed')
      } finally {
        if (!this.disposed && version === this.searchVersion) this.loadingCandidates = false
      }
    },
    async loadRoles () {
      if (!this.apis.listProjectRoles) return
      try {
        const json = await getAPI('listProjectRoles', { projectid: this.resource.id, ignoreproject: true }, { preserveOnFailure: true })
        if (!this.disposed) this.projectRoles = (json.listprojectrolesresponse.projectrole || []).map(role => ({ label: role.name, value: role.id }))
      } catch (error) {
        if (!this.disposed) this.$notifyError(error)
      }
    },
    close () {
      if (!this.membershipBusy) this.$emit('close-action')
    },
    async submit () {
      if (this.submitDisabled) return
      const params = { projectid: this.resource.id }
      if (this.memberName?.trim()) params[this.kind === 'user' ? 'username' : 'account'] = this.memberName.trim()
      if (this.invitationRequired && this.email.trim() && this.supports('email')) params.email = this.email.trim()
      if (this.supports('roletype')) params.roletype = this.roleType
      if (this.projectRoleId && this.supports('projectroleid')) params.projectroleid = this.projectRoleId
      if (!await this.runMembershipJob(this.command, params, 'label.project.member.add') || this.disposed) return
      eventBus.emit('project-members-updated', this.resource.id)
      this.$emit('refresh-data', { irefresh: true })
      this.close()
    }
  }
}
</script>

<style scoped>
.member-feedback { margin-bottom: 12px; }
</style>
