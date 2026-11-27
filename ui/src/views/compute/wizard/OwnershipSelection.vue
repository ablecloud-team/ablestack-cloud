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
  <component :is="embedded ? 'div' : 'a-form'" :layout="embedded ? undefined : 'vertical'" :class="{ 'ownership-selection-embedded': embedded }">
    <a-alert v-if="safeScope && safeError" show-icon type="error" :message="$t('message.bucket.owner.options.failed')" class="owner-options-error">
      <template #description><a-button :loading="loading" @click="retrySafe">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <a-button v-if="safeScope" class="owner-options-reload" :disabled="disabled || loading" @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.bucket.owner.options.reload') }}</a-button>
    <a-form-item v-if="!accountOnly" :label="$t('label.owner.type')">
      <a-select
        @change="changeAccountTypeOrDomain"
        v-model:value="selectedAccountType"
        :aria-label="$t('label.owner.type')"
        defaultValue="account"
        :disabled="safeScope && (disabled || safePhase === 'domains' || !domains.length)"
        :autoFocus="!embedded"
        showSearch
        optionFilterProp="label"
        :filterOption="
          (input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }
        "
      >
        <a-select-option :value="'Account'">{{ $t('label.account') }}</a-select-option>
        <a-select-option :value="'Project'">{{ $t('label.project') }}</a-select-option>
      </a-select>
    </a-form-item>
    <a-form-item :label="$t('label.domain')" required>
      <a-select
        @change="changeAccountTypeOrDomain"
        v-model:value="selectedDomain"
        :aria-label="$t('label.domain')"
        :loading="safeScope && safePhase === 'domains'"
        :disabled="safeScope && (disabled || safePhase === 'domains' || (safeError && safeError.kind === 'domains'))"
        showSearch
        optionFilterProp="label"
        :filterOption="
          (input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }
        "
      >
        <a-select-option
          v-for="domain in domains"
          :key="domain.name"
          :value="domain.id"
          :label="domain.path || domain.name || domain.description"
        >
          <span>
            <resource-icon
              v-if="domain && domain.icon"
              :image="domain.icon.base64image"
              size="1x"
              style="margin-right: 5px"
            />
            <block-outlined v-else />
            {{ domain.path || domain.name || domain.description }}
          </span>
        </a-select-option>
      </a-select>
    </a-form-item>

    <template v-if="selectedAccountType === 'Account'">
      <a-form-item :label="$t('label.account')" required>
        <a-select
          @change="emitChangeEvent"
          v-model:value="selectedAccount"
        :aria-label="$t('label.account')"
          :loading="safeScope && loading"
          :disabled="safeScope && (disabled || loading || !!safeError || !selectedDomain)"
          showSearch
          optionFilterProp="label"
          :filterOption="
            (input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }
          "
        >
          <a-select-option v-for="account in accounts" :key="account.name" :value="account.name" :label="account.name">
            <span>
              <resource-icon
                v-if="account && account.icon"
                :image="account.icon.base64image"
                size="1x"
                style="margin-right: 5px"
              />
              <team-outlined v-else />
              {{ account.name }}
            </span>
          </a-select-option>
        </a-select>
      </a-form-item>
    </template>

    <template v-else>
      <a-form-item :label="$t('label.project')" required>
        <a-select
          @change="emitChangeEvent"
          v-model:value="selectedProject"
        :aria-label="$t('label.project')"
          :loading="safeScope && loading"
          :disabled="safeScope && (disabled || loading || !!safeError || !selectedDomain)"
          showSearch
          optionFilterProp="label"
          :filterOption="
            (input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }
          "
        >
          <a-select-option v-for="project in projects" :key="project.id" :value="project.id" :label="project.name">
            <span>
              <resource-icon
                v-if="project && project.icon"
                :image="project.icon.base64image"
                size="1x"
                style="margin-right: 5px"
              />
              <project-outlined v-else />
              {{ project.name }}
            </span>
          </a-select-option>
        </a-select>
      </a-form-item>
    </template>
  </component>
</template>

<script>
import { getAPI } from '@/api'
import ResourceIcon from '@/components/view/ResourceIcon.vue'
import { scopedOwnershipOptions } from '@/utils/scopedOwnership'

export default {
  name: 'OwnershipSelection',
  components: { ResourceIcon },
  data () {
    return {
      initialized: false,
      safePhase: '',
      safeError: null,
      safeWanted: {},
      disposed: false,
      domains: [],
      accounts: [],
      projects: [],
      selectedAccountType: !this.accountOnly && this.$store.getters.project?.id ? 'Project' : 'Account',
      selectedDomain: null,
      selectedAccount: null,
      selectedProject: null,
      loading: false,
      requestToken: 0
    }
  },
  props: {
    accountOnly: { type: Boolean, default: false },
    embedded: { type: Boolean, default: false },
    safeScope: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    override: {
      type: Object
    },
    accountState: {
      type: String,
      default: 'Enabled'
    }
  },
  computed: {
    safeContext () { return JSON.stringify([this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roleid, this.$store.getters.userInfo?.roletype, this.$store.state?.user?.token, this.$store.getters.project?.id]) },
    safeReady () {
      return !this.loading && !this.safeError && this.domains.some(row => row.id === this.selectedDomain) &&
        (this.selectedAccountType === 'Account' ? this.accounts.some(row => row.name === this.selectedAccount && row.domainid === this.selectedDomain) : this.projects.some(row => row.id === this.selectedProject && row.domainid === this.selectedDomain))
    }
  },
  watch: {
    safeContext (_, previous) {
      if (this.safeScope && previous) {
        ++this.requestToken; this.selectedAccount = null; this.selectedProject = null; this.safeError = null; this.safeWanted = {}
        this.emitChangeEvent(); this.fetchData()
      }
    }
  },
  beforeUnmount () { this.disposed = true; ++this.requestToken },
  created () {
    this.fetchData()
  },
  methods: {
    retrySafe () { return this.fetchScoped(this.safeError?.kind || 'domains', this.safeWanted) },
    async fetchScoped (kind, wanted = {}) {
      const token = ++this.requestToken; const context = this.safeContext; const domain = this.selectedDomain
      const current = () => !this.disposed && token === this.requestToken && context === this.safeContext
      const permitted = ['Admin', 'DomainAdmin'].includes(this.$store.getters.userInfo?.roletype) && !this.$store.getters.project?.id
      this.safeWanted = { account: wanted.account || this.selectedAccount, project: wanted.project || this.selectedProject }
      this.selectedAccount = null; this.selectedProject = null; this.safeError = null; this.safePhase = kind; this.loading = true
      this.accounts = []; this.projects = []; if (kind === 'domains') this.domains = []
      this.emitChangeEvent()
      if (!permitted) { this.loading = false; this.safePhase = ''; this.emitChangeEvent(); return }
      try {
        const params = kind === 'domains' ? { isrecursive: true, showicon: true, details: 'min' } : { domainid: domain, isrecursive: false, state: kind === 'accounts' ? 'Enabled' : 'Active', showicon: true, details: 'min' }
        const rows = await scopedOwnershipOptions(getAPI, kind, params)
        if (!current()) return
        this[kind] = rows
        if (kind === 'domains') {
          const preferred = domain || this.$store.getters.userInfo.domainid
          this.selectedDomain = rows.some(row => row.id === preferred) ? preferred : undefined
          if (this.selectedDomain) return this.fetchScoped(this.selectedAccountType === 'Account' ? 'accounts' : 'projects', this.safeWanted)
        } else if (kind === 'accounts') {
          const preferred = this.safeWanted.account || (!this.initialized && domain === this.$store.getters.userInfo.domainid ? this.$store.getters.userInfo.account : null)
          this.selectedAccount = rows.some(row => row.name === preferred) ? preferred : undefined
        } else {
          this.selectedProject = rows.some(row => row.id === this.safeWanted.project) ? this.safeWanted.project : undefined
        }
      } catch (_) { if (current()) this.safeError = { kind } } finally {
        if (current()) { this.loading = false; this.safePhase = ''; this.initialized = true; this.emitChangeEvent() }
      }
    },
    fetchData () {
      if (this.safeScope) return this.fetchScoped('domains')
      this.loading = true
      getAPI('listDomains', {
        response: 'json',
        listAll: true,
        showicon: true,
        details: 'min'
      })
        .then((response) => {
          this.domains = response.listdomainsresponse.domain
          if (this.override) {
            this.domains = this.domains.filter(item => this.override.domains.has(item.id))
          }
          if (this.domains.length === 0) {
            this.selectedDomain = null
            this.selectedProject = null
            this.selectedAccount = null
            return
          }
          const domainIds = this.domains?.map(domain => domain.id)
          const ownerDomainId = this.$store.getters.project?.domainid || this.$store.getters.userInfo.domainid
          this.selectedDomain = domainIds?.includes(ownerDomainId) ? ownerDomainId : this.domains?.[0]?.id
          this.fetchOwnerData()
        })
        .catch((error) => {
          this.$notifyError(error)
        })
        .finally(() => {
          this.loading = false
        })
    },
    incrementAndGetRequestToken () {
      this.requestToken += 1
      return this.requestToken
    },
    fetchAccounts () {
      if (this.safeScope) return this.fetchScoped('accounts')
      this.loading = true
      const currentToken = this.incrementAndGetRequestToken()
      getAPI('listAccounts', {
        response: 'json',
        domainId: this.selectedDomain,
        showicon: true,
        state: this.accountState,
        isrecursive: false
      })
        .then((response) => {
          if (currentToken !== this.requestToken) {
            return
          }
          this.accounts = response.listaccountsresponse.account || []
          if (this.override?.accounts && this.accounts) {
            this.accounts = this.accounts.filter(item => this.override.accounts.has(item.name))
          }
          const accountNames = this.accounts.map(account => account.name)
          if (this.selectedDomain === this.$store.getters.userInfo.domainid && accountNames.includes(this.$store.getters.userInfo.account)) {
            this.selectedAccount = this.$store.getters.userInfo.account
          } else {
            this.selectedAccount = this.accounts?.[0]?.name
          }
          this.selectedProject = null
          this.emitChangeEvent()
        })
        .catch((error) => {
          this.$notifyError(error)
        })
        .finally(() => {
          this.loading = false
          this.initialized = true
        })
    },
    fetchProjects () {
      if (this.safeScope) return this.fetchScoped('projects')
      this.loading = true
      const currentToken = this.incrementAndGetRequestToken()
      getAPI('listProjects', {
        response: 'json',
        domainId: this.selectedDomain,
        state: 'Active',
        showicon: true,
        details: 'min',
        isrecursive: false
      })
        .then((response) => {
          if (currentToken !== this.requestToken) {
            return
          }
          this.projects = response.listprojectsresponse.project
          if (this.override?.projects && this.projects) {
            this.projects = this.projects.filter(item => this.override.projects.has(item.id))
          }
          this.selectedProject = this.projects?.map(project => project.id)?.includes(this.$store.getters.project?.id) ? this.$store.getters.project?.id : this.projects?.[0]?.id
          this.selectedAccount = null
          this.emitChangeEvent()
        })
        .catch((error) => {
          this.$notifyError(error)
        })
        .finally(() => {
          this.loading = false
          this.initialized = true
        })
    },
    changeAccountTypeOrDomain () {
      if (this.safeScope) { this.selectedAccount = null; this.selectedProject = null; this.safeWanted = {}; this.safeError = null; this.emitChangeEvent() }
      this.initialized = true
      this.fetchOwnerData()
    },
    fetchOwnerData () {
      if (this.selectedAccountType === 'Account') {
        this.fetchAccounts()
      } else {
        this.fetchProjects()
      }
    },
    emitChangeEvent () {
      if (!this.safeScope) { this.$emit('fetch-owner', this); return }
      this.$emit('fetch-owner', {
        initialized: this.initialized,
        ready: this.safeReady,
        loading: this.loading,
        failed: !!this.safeError,
        selectedAccountType: this.selectedAccountType,
        selectedDomain: this.selectedDomain,
        selectedAccount: this.selectedAccount,
        selectedProject: this.selectedProject,
        domainLabel: this.domains.find(row => row.id === this.selectedDomain)?.path || this.domains.find(row => row.id === this.selectedDomain)?.name,
        projectLabel: this.projects.find(row => row.id === this.selectedProject)?.name
      })
    }
  }
}
</script>

<style scoped>
.owner-options-reload { margin-bottom: 16px; }
.owner-options-error { margin-bottom: 16px; overflow-wrap: anywhere; }
</style>
