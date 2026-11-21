<script>
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

import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import { affinityOwnerOptions } from '@/utils/affinityGroupCreation'
import { createJobTracker } from '@/utils/jobTracker'
import { canChooseVpnUserAccount, canCreateVpnUser, vpnUserCreateFailure, vpnUserCreateParams, vpnUserCredentialError } from '@/utils/vpnUserCreation'

const requestOptions = { preserveOnFailure: true, timeout: 15000 }

export default {
  name: 'VpnUserCreateDialog',
  components: { MoldDialog },
  props: { afterClose: { type: Function, default: undefined } },
  emits: ['close-action', 'refresh-data'],
  data () {
    return {
      form: { username: '', password: '' },
      errors: {},
      mode: 'self',
      domains: [],
      accounts: [],
      domainid: undefined,
      account: undefined,
      lookupVersion: 0,
      loading: '',
      lookupError: '',
      result: '',
      jobId: ''
    }
  },
  computed: {
    getters () { return this.$store.getters },
    project () { return this.getters.project || {} },
    canChooseAccount () { return canChooseVpnUserAccount(this.getters) },
    choosingAccount () { return this.mode === 'account' && this.canChooseAccount },
    scope () {
      const user = this.getters.userInfo
      return JSON.stringify([this.$store.state.user.token, user?.id, user?.account, user?.domainid, this.project.id, canCreateVpnUser(this.getters), this.canChooseAccount])
    },
    owner () {
      if (this.project.id) return { projectid: this.project.id }
      if (!this.choosingAccount) return { own: true }
      if (this.domains.some(row => row.id === this.domainid) && this.accounts.some(row => row.name === this.account)) {
        return { domainid: this.domainid, account: this.account }
      }
      return null
    },
    ownerLabel () {
      if (this.project.id) return this.$t('message.vpn.user.add.project.target', [this.project.name || this.project.displaytext || this.project.id])
      if (!this.owner) return this.$t('message.vpn.user.add.select.target')
      const user = this.getters.userInfo || {}
      const domain = this.domains.find(row => row.id === this.domainid)
      const name = this.owner.own ? user.account : this.account
      const path = this.owner.own ? user.domain : domain?.path || domain?.name
      return this.$t('message.vpn.user.add.account.target', [[path, name].filter(Boolean).join(' / ')])
    },
    running () { return ['submitting', 'pending'].includes(this.result) },
    locked () { return this.running || this.result === 'unknown' },
    resultType () { return this.running ? 'info' : this.result === 'unknown' ? 'warning' : 'error' }
  },
  watch: {
    scope: {
      flush: 'sync',
      handler () { this.$emit('close-action') }
    }
  },
  created () {
    this.activeScope = this.scope
    this.tracker = createJobTracker({
      query: async id => {
        const data = await getAPI('queryAsyncJobResult', { jobid: id, ignoreproject: true }, requestOptions)
        const job = data?.queryasyncjobresultresponse
        if (job?.jobid !== id) throw new Error('Unexpected VPN user add job')
        return job
      },
      onState: () => {},
      maxDuration: 120000
    })
  },
  beforeUnmount () {
    this.activeScope = null
    this.tracker.clear()
  },
  methods: {
    current () { return this.activeScope === this.scope },
    errorText (field) { return this.errors[field] ? this.$t(this.errors[field]) : undefined },
    validateCredential (field) { this.errors[field] = vpnUserCredentialError(field, this.form[field]) },
    updateCredential (field, value) {
      if (this.errors[field]) this.errors[field] = vpnUserCredentialError(field, value)
    },
    async loadOwners (kind) {
      const version = ++this.lookupVersion
      const domain = this.domainid
      const current = () => this.current() && this.choosingAccount && version === this.lookupVersion
      this.loading = kind
      this.lookupError = ''
      try {
        const rows = await affinityOwnerOptions(getAPI, kind, domain)
        if (!current()) return
        if (kind === 'domain') this.domains = rows
        else this.accounts = rows.filter(row => row.domainid === domain && Number(row.accounttype) !== 5)
      } catch (_) {
        if (current()) this.lookupError = kind
      } finally {
        if (current()) this.loading = ''
      }
    },
    changeMode (mode) {
      if (this.locked || !this.canChooseAccount) return
      this.lookupVersion++
      this.mode = mode
      this.domainid = undefined
      this.account = undefined
      this.domains = []
      this.accounts = []
      this.loading = ''
      this.lookupError = ''
      delete this.errors.domainid
      delete this.errors.account
      if (this.choosingAccount) this.loadOwners('domain')
    },
    changeDomain (domainid) {
      this.domainid = domainid
      this.accounts = []
      this.account = undefined
      delete this.errors.domainid
      delete this.errors.account
      this.loadOwners('account')
    },
    selectAccount (account) {
      this.account = account
      delete this.errors.account
    },
    retryOwners () { return this.loadOwners(this.lookupError) },
    async track (retry = false) {
      this.result = 'pending'
      const job = await this.tracker.track(this.jobId, {}, retry)
      if (!this.current()) return
      if (job.jobstatus === 1) {
        this.$message.success(this.$t('message.vpn.user.add.result.success'))
        this.$emit('refresh-data', { irefresh: true })
        this.$emit('close-action')
      } else {
        this.result = job.jobstatus === 2 ? vpnUserCreateFailure(null, job.jobresult || {}) : 'unknown'
      }
    },
    async submit () {
      if (!this.current() || this.locked || this.loading || this.lookupError || !canCreateVpnUser(this.getters)) return
      this.validateCredential('username')
      this.validateCredential('password')
      if (this.choosingAccount) {
        this.errors.domainid = this.domainid ? '' : 'message.vpn.user.add.domain.required'
        this.errors.account = this.account && this.owner ? '' : 'message.vpn.user.add.account.required'
      }
      const params = vpnUserCreateParams(this.form, this.owner)
      if (!params) return
      this.jobId = ''
      this.result = 'submitting'
      try {
        const response = await postAPI('addVpnUser', params, requestOptions)
        if (!this.current()) return
        this.jobId = response?.addvpnuserresponse?.jobid || ''
        if (this.jobId) await this.track()
        else this.result = 'unknown'
      } catch (error) {
        if (!this.current()) return
        this.result = vpnUserCreateFailure(error)
        if (this.result === 'duplicate') {
          this.errors.username = 'message.vpn.user.add.result.duplicate'
          this.$nextTick(() => this.$refs.usernameInput?.focus())
        }
      }
    },
    recheck () {
      if (this.current() && this.result === 'unknown' && this.jobId) return this.track(true)
    },
    close () {
      if (this.running) return
      if (this.jobId || this.result === 'unknown') this.$emit('refresh-data', { irefresh: true })
      this.$emit('close-action')
    }
  }
}
</script>

<template>
  <MoldDialog
    class="vpn-user-create-dialog"
    :title="$t('label.add.vpn.user')"
    :after-close="afterClose"
    :focus-trigger-after-close="false"
    :closable="!running"
    :keyboard="!running"
    size="md"
    focus-guard
    @cancel="close">
    <a-alert v-if="result" class="vpn-user-create-result" :type="resultType" show-icon :message="$t('message.vpn.user.add.result.' + result)">
      <template v-if="jobId" #description>
        <div>{{ $t('label.job.id') }} <code>{{ jobId }}</code></div>
        <a-button v-if="result === 'unknown'" size="small" @click="recheck">{{ $t('message.vpn.user.delete.recheck') }}</a-button>
      </template>
    </a-alert>

    <a-form :model="form" layout="vertical">
      <a-form-item v-if="canChooseAccount" :label="$t('label.vpn.user.add.owner.type')">
        <a-radio-group :value="mode" :disabled="locked" :aria-label="$t('label.vpn.user.add.owner.type')" @change="changeMode($event.target.value)">
          <a-radio value="self">{{ $t('label.vpn.user.add.own.account') }}</a-radio>
          <a-radio value="account">{{ $t('label.vpn.user.add.other.account') }}</a-radio>
        </a-radio-group>
      </a-form-item>
      <template v-if="choosingAccount">
        <a-alert v-if="lookupError" class="vpn-user-create-result" type="error" show-icon :message="$t('message.vpn.user.add.lookup.failed')">
          <template #description><a-button size="small" :disabled="locked" @click="retryOwners">{{ $t('label.retry') }}</a-button></template>
        </a-alert>
        <a-form-item :label="$t('label.domain')" :validate-status="errors.domainid ? 'error' : undefined" :help="errorText('domainid')" html-for="vpn-user-add-domain" required>
          <a-select
            id="vpn-user-add-domain"
            :value="domainid"
            :loading="loading === 'domain'"
            :disabled="locked || loading === 'domain' || lookupError === 'domain'"
            :placeholder="$t('message.vpn.user.add.domain.required')"
            show-search
            option-filter-prop="label"
            @change="changeDomain">
            <a-select-option v-for="row in domains" :key="row.id" :value="row.id" :label="row.path || row.name">{{ row.path || row.name }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item :label="$t('label.account')" :validate-status="errors.account ? 'error' : undefined" :help="errorText('account')" html-for="vpn-user-add-account" required>
          <a-select
            id="vpn-user-add-account"
            :value="account"
            :loading="loading === 'account'"
            :disabled="locked || !domainid || !!loading || !!lookupError"
            :placeholder="$t(domainid ? 'message.vpn.user.add.account.required' : 'message.vpn.user.search.account.domain')"
            show-search
            option-filter-prop="label"
            @change="selectAccount">
            <a-select-option v-for="row in accounts" :key="row.id" :value="row.name" :label="row.name">{{ row.name }}</a-select-option>
          </a-select>
        </a-form-item>
      </template>
      <p class="vpn-user-registration-owner" aria-live="polite">
        <strong>{{ $t('label.vpn.user.add.target') }}</strong>
        {{ ownerLabel }}
      </p>

      <a-form-item :label="$t('label.username')" :validate-status="errors.username ? 'error' : undefined" :help="errorText('username')" :extra="errors.username ? undefined : $t('message.vpn.user.add.username.format')" html-for="vpn-user-add-username" required>
        <a-input
          id="vpn-user-add-username"
          ref="usernameInput"
          v-focus="true"
          v-model:value="form.username"
          :disabled="locked"
          :placeholder="$t('message.vpn.user.add.username.required')"
          :aria-invalid="!!errors.username"
          autocomplete="off"
          @blur="validateCredential('username')"
          @input="updateCredential('username', $event.target.value)"
          @keydown.enter.prevent="submit" />
      </a-form-item>
      <a-form-item :label="$t('label.password')" :validate-status="errors.password ? 'error' : undefined" :help="errorText('password')" :extra="errors.password ? undefined : $t('message.vpn.user.add.password.format')" html-for="vpn-user-add-password" required>
        <a-input
          id="vpn-user-add-password"
          v-model:value="form.password"
          type="password"
          :disabled="locked"
          :placeholder="$t('message.vpn.user.add.password.required')"
          :aria-invalid="!!errors.password"
          autocomplete="new-password"
          @blur="validateCredential('password')"
          @input="updateCredential('password', $event.target.value)"
          @keydown.enter.prevent="submit" />
      </a-form-item>
    </a-form>
    <template #footer>
      <a-button :disabled="running" @click="close">{{ $t(result === 'unknown' ? 'label.close' : 'label.cancel') }}</a-button>
      <a-button type="primary" :loading="running" :disabled="locked || !!loading || !!lookupError" @click="submit">{{ $t('label.add') }}</a-button>
    </template>
  </MoldDialog>
</template>

<style scoped>
.vpn-user-create-result {
  margin-block-end: 16px;
}

.vpn-user-registration-owner {
  margin-block-end: 16px;
  overflow-wrap: anywhere;
}

.vpn-user-registration-owner strong {
  margin-inline-end: 8px;
}
</style>
