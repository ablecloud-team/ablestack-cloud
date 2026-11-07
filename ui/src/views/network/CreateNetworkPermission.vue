// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for ownership information.
// Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
// Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.

<template>
  <MoldDialog
    size="md"
    focus-guard
    :title="$t('label.add.network.permission')"
    :closable="!busy"
    :keyboard="!busy"
    @cancel="$emit('close-action')">
    <p class="permission-context">{{ resource.name }} · {{ resource.domain }} · {{ resource.account }}</p>
    <p>{{ $t('message.network.permission.same.domain') }}</p>
    <a-alert v-if="error || lookupError" type="error" show-icon :message="errorMessage" />
    <a-form layout="vertical" class="permission-fields">
      <a-form-item :label="$t('label.account')">
        <a-select
          v-if="accountPicker"
          v-model:value="draft.accountids"
          :options="accountOptions"
          :aria-label="$t('label.account')"
          :loading="loading"
          :disabled="busy || loading || !!lookupError"
          mode="multiple"
          option-filter-prop="label"
          :placeholder="$t('label.select')" />
        <a-input
          v-else
          v-model:value="draft.accounts"
          :aria-label="$t('label.accounts')"
          :placeholder="$t('message.network.permission.account.names')"
          :disabled="busy"
          @keydown.ctrl.enter.prevent="submit" />
      </a-form-item>
      <a-form-item v-if="projectPicker" :label="$t('label.project')">
        <a-select
          v-model:value="draft.projectids"
          :options="projectOptions"
          :aria-label="$t('label.project')"
          :loading="loading"
          :disabled="busy || loading || !!lookupError"
          mode="multiple"
          option-filter-prop="label"
          :placeholder="$t('label.select')" />
      </a-form-item>
    </a-form>
    <template #footer>
      <a-button v-if="lookupError" :disabled="busy" :loading="loading" @click="fetchCandidates">{{ $t('label.retry') }}</a-button>
      <a-button v-if="error && !ready" :disabled="busy" @click="$emit('refresh')">{{ $t('label.refresh') }}</a-button>
      <a-button :disabled="busy" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :disabled="!canSubmit" :loading="busy" @click="submit">{{ $t('label.ok') }}</a-button>
    </template>
  </MoldDialog>
</template>

<script>
import { getAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import { scopedOwnershipOptions } from '@/utils/scopedOwnership'
import { permissionGrant } from '@/utils/networkPermissions'

export default {
  name: 'CreateNetworkPermission',
  components: { MoldDialog },
  props: {
    resource: { type: Object, required: true },
    permissions: { type: Array, required: true },
    busy: { type: Boolean, default: false },
    ready: { type: Boolean, default: false },
    error: { type: String, default: '' }
  },
  emits: ['submit', 'refresh', 'close-action'],
  data () {
    return { draft: { accountids: [], projectids: [], accounts: '' }, accounts: [], projects: [], loading: false, lookupError: '', disposed: false }
  },
  computed: {
    errorMessage () {
      const message = this.error || this.lookupError
      return message.startsWith('message.') ? this.$t(message) : message
    },
    accountPicker () {
      const { apis, userInfo } = this.$store.getters
      return ['Admin', 'DomainAdmin'].includes(userInfo.roletype) && 'listAccounts' in apis
    },
    projectPicker () { return 'listProjects' in this.$store.getters.apis },
    accountOptions () {
      return this.accounts.filter(row => !this.permissions.some(permission => permission.accountid === row.id))
        .map(row => ({ value: row.id, label: row.name }))
    },
    projectOptions () {
      return this.projects.filter(row => !this.permissions.some(permission => permission.projectid === row.id))
        .map(row => ({ value: row.id, label: row.name }))
    },
    targets () {
      return permissionGrant({
        accounts: this.accountPicker ? '' : this.draft.accounts,
        accountids: this.draft.accountids.filter(id => this.accountOptions.some(row => row.value === id)),
        projectids: this.draft.projectids.filter(id => this.projectOptions.some(row => row.value === id))
      }, this.resource, this.permissions)
    },
    canSubmit () { return this.ready && !this.loading && !this.lookupError && Object.keys(this.targets).length > 0 }
  },
  watch: {
    permissions () {
      this.draft.accountids = this.draft.accountids.filter(id => this.accountOptions.some(row => row.value === id))
      this.draft.projectids = this.draft.projectids.filter(id => this.projectOptions.some(row => row.value === id))
    }
  },
  created () { this.fetchCandidates() },
  beforeUnmount () { this.disposed = true },
  methods: {
    async fetchCandidates () {
      if (this.loading) return
      this.loading = true
      this.lookupError = ''
      const params = { domainid: this.resource.domainid, listall: true, ignoreproject: true }
      try {
        const [accounts, projects] = await Promise.all([
          this.accountPicker ? scopedOwnershipOptions(getAPI, 'accounts', { ...params, isrecursive: false, state: 'Enabled' }) : [],
          this.projectPicker ? scopedOwnershipOptions(getAPI, 'projects', { ...params, isrecursive: false, state: 'Active' }) : []
        ])
        if (this.disposed) return
        this.accounts = accounts.filter(row => row.name !== this.resource.account)
        this.projects = projects
      } catch (error) {
        if (!this.disposed) this.lookupError = 'message.network.permission.candidates.failed'
      } finally {
        if (!this.disposed) this.loading = false
      }
    },
    submit () {
      if (!this.canSubmit) return
      this.$emit('submit', {
        accounts: this.targets.accounts,
        accountids: this.targets.accountids?.split(','),
        projectids: this.targets.projectids?.split(',')
      })
    }
  }
}
</script>

<style scoped>
.permission-context { font-weight: 600; overflow-wrap: anywhere; }
.permission-fields { margin-top: 16px; }
</style>
