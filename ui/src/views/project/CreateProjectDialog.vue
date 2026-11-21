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
    :visible="!closed"
    :title="$t('label.new.project')"
    doc-help="adminguide/projects.html#creating-a-new-project"
    size="md"
    form-actions
    focus-guard
    :after-close="finishClose"
    @cancel="close">
    <a-form layout="vertical" @finish="submit" v-ctrl-enter="submit">
      <a-alert v-if="error" type="error" show-icon :message="error" class="project-create-error" />
      <a-form-item :label="$t('label.name')" html-for="new-project-name" required>
        <a-input id="new-project-name" v-model:value="name" :disabled="fieldsLocked" v-focus="true" />
      </a-form-item>
      <a-form-item :label="$t('label.description')" html-for="new-project-description">
        <a-textarea id="new-project-description" v-model:value="description" :rows="2" :disabled="fieldsLocked" />
      </a-form-item>
      <a-form-item v-if="canDelegate" :label="$t('label.project.owner')">
        <a-radio-group :value="delegated" :disabled="fieldsLocked" @change="changeOwnerMode">
          <a-radio :value="false">{{ $t('label.project.create.owner.current') }}</a-radio>
          <a-radio :value="true">{{ $t('label.project.create.owner.other') }}</a-radio>
        </a-radio-group>
      </a-form-item>
      <template v-if="delegated && canDelegate">
        <a-form-item :label="$t('label.domain')" html-for="new-project-domain" required>
          <InfiniteScrollSelect
            id="new-project-domain"
            :value="domain?.id"
            api="listDomains"
            :api-params="domainsParams"
            resource-type="domain"
            option-label-key="path"
            default-icon="block-outlined"
            :disabled="fieldsLocked"
            @change-option="selectDomain" />
        </a-form-item>
        <a-form-item :label="$t('label.owner.account')" html-for="new-project-account" required>
          <InfiniteScrollSelect
            v-if="domain"
            id="new-project-account"
            :key="domain.id"
            :value="account?.id"
            api="listAccounts"
            :api-params="accountsParams"
            resource-type="account"
            default-icon="team-outlined"
            :disabled="fieldsLocked"
            @change-option="selectAccount" />
          <a-select v-else id="new-project-account" disabled :placeholder="$t('label.select')" />
        </a-form-item>
        <a-form-item v-if="canSelectUser" :label="$t('label.user')" html-for="new-project-user">
          <InfiniteScrollSelect
            v-if="account"
            id="new-project-user"
            :key="account.id"
            :value="user?.id"
            api="listUsers"
            :api-params="usersParams"
            resource-type="user"
            option-label-key="username"
            default-icon="user-outlined"
            :show-icon="false"
            allow-clear
            :disabled="fieldsLocked"
            @change-option="selectUser" />
          <a-select v-else id="new-project-user" disabled :placeholder="$t('label.select')" />
        </a-form-item>
      </template>
      <a-descriptions
        v-if="ownerAccount"
        :title="$t('label.project.create.owner.summary')"
        :column="1"
        size="small"
        bordered>
        <a-descriptions-item :label="$t('label.domain')">{{ ownerDomain }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.owner.account')">{{ ownerAccount }}</a-descriptions-item>
        <a-descriptions-item v-if="delegated && user" :label="$t('label.user')">{{ user.username }}</a-descriptions-item>
      </a-descriptions>
    </a-form>
    <MoldDialogActions :busy="busy">
      <a-button :disabled="busy" @click="close">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="busy" :disabled="submitDisabled" @click="submit">
        {{ $t(jobId ? 'label.job.check.result' : 'label.create.project') }}
      </a-button>
    </MoldDialogActions>
  </MoldDialog>
</template>

<script>
import { postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import InfiniteScrollSelect from '@/components/widgets/InfiniteScrollSelect'
import eventBus from '@/config/eventBus'

export default {
  name: 'CreateProjectDialog',
  components: { InfiniteScrollSelect, MoldDialog, MoldDialogActions },
  emits: ['close-action', 'refresh-data'],
  data () {
    return {
      name: '',
      description: '',
      delegated: false,
      domain: null,
      account: null,
      user: null,
      busy: false,
      jobId: '',
      unknown: false,
      error: '',
      closed: false,
      disposed: false,
      domainsParams: { listall: true, ignoreproject: true }
    }
  },
  computed: {
    caller () { return this.$store.getters.userInfo || {} },
    apis () { return this.$store.getters.apis },
    createParams () { return this.apis.createProject?.params?.map(param => param.name) || [] },
    canDelegate () {
      return ['Admin', 'DomainAdmin'].includes(this.caller.roletype) &&
        this.createParams.includes('accountid') && 'listDomains' in this.apis && 'listAccounts' in this.apis
    },
    canSelectUser () {
      return this.createParams.includes('userid') && 'listUsers' in this.apis
    },
    ownerAccount () { return this.delegated ? this.account?.name : this.caller.account },
    ownerDomain () { return this.delegated ? this.domain?.path || this.domain?.name : this.caller.domain || this.caller.domainid },
    accountsParams () {
      return { domainid: this.domain?.id, state: 'enabled', listall: true, ignoreproject: true }
    },
    usersParams () { return { ...this.accountsParams, account: this.account?.name } },
    fieldsLocked () { return this.busy || !!this.jobId || this.unknown },
    submitDisabled () {
      if (this.busy || this.closed || !this.apis.createProject) return true
      if (this.jobId) return false
      return this.unknown || !this.name.trim() || (this.delegated && (!this.canDelegate || !this.domain || !this.account))
    }
  },
  beforeUnmount () { this.disposed = true },
  methods: {
    changeOwnerMode (event) {
      this.delegated = event.target.value
      this.selectDomain(null)
    },
    selectDomain (domain) {
      this.domain = domain
      this.selectAccount(null)
    },
    selectAccount (account) {
      this.account = account?.domainid === this.domain?.id ? account : null
      this.user = null
    },
    selectUser (user) {
      this.user = user?.accountid === this.account?.id ? user : null
    },
    close () {
      if (!this.busy) this.closed = true
    },
    finishClose () {
      if (this.closed) this.$emit('close-action')
    },
    async submit () {
      if (this.submitDisabled) return
      const retry = !!this.jobId
      this.busy = true
      this.error = ''
      try {
        if (!retry) {
          const params = { name: this.name.trim(), displaytext: this.description, ignoreproject: true }
          if (this.delegated) {
            params.domainid = this.domain.id
            params.accountid = this.account.id
            if (this.canSelectUser && this.user) params.userid = this.user.id
          }
          const response = await postAPI('createProject', params, { preserveOnFailure: true, timeout: 30000 })
          if (this.disposed) return
          this.jobId = response?.createprojectresponse?.jobid || ''
          if (!this.jobId) {
            this.unknown = true
            this.error = this.$t('message.project.create.result.unknown')
            return
          }
        }
        const result = await this.$pollJob({
          jobId: this.jobId,
          retry,
          title: this.$t('label.create.project'),
          description: this.name.trim(),
          action: { api: 'createProject', label: 'label.create.project', isFetchData: false, suppressErrorNotification: true }
        })
        if (this.disposed) return
        if (result.jobstatus === 1) {
          eventBus.emit('projects-updated', { action: 'createProject', project: result.jobresult?.project })
          this.$emit('refresh-data', { irefresh: true })
          this.closed = true
        } else if (result.jobstatus === 2) {
          this.jobId = ''
          this.error = result.jobresult?.errortext || this.$t('message.request.failed')
        } else {
          this.error = this.$t('message.project.create.result.unknown')
        }
      } catch (error) {
        if (this.disposed) return
        const apiError = Object.values(error.response?.data || {}).find(value => value?.errortext)?.errortext
        this.unknown = !this.jobId && !apiError && !(error.response?.status >= 400 && error.response?.status < 500)
        this.error = this.unknown || this.jobId
          ? this.$t('message.project.create.result.unknown')
          : apiError || this.$t('message.request.failed')
      } finally {
        this.busy = false
      }
    }
  }
}
</script>

<style scoped>
.project-create-error { margin-bottom: 16px; }
</style>
