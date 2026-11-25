<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <a-form layout="vertical" :model="form">
    <a-form-item
      html-for="ldap-server-hostname"
      required
      :validate-status="errors.hostname ? 'error' : undefined"
      :help="errors.hostname ? $t(errors.hostname) : undefined">
      <template #label><tooltip-label :title="$t('label.hostname')" :tooltip="$t('message.ldap.host.help')" focusable /></template>
      <a-input
        id="ldap-server-hostname"
        v-model:value="form.hostname"
        :disabled="busy"
        :placeholder="$t('label.ldap.host.placeholder')"
        @keydown.enter.prevent="submit" />
    </a-form-item>
    <a-form-item
      html-for="ldap-server-port"
      required
      :validate-status="errors.port ? 'error' : undefined"
      :help="errors.port ? $t(errors.port) : undefined">
      <template #label><tooltip-label :title="$t('label.port')" :tooltip="$t('message.ldap.port.help')" focusable /></template>
      <a-input id="ldap-server-port" v-model:value="form.port" inputmode="numeric" :disabled="busy" @keydown.enter.prevent="submit" />
    </a-form-item>
    <a-form-item html-for="ldap-server-scope" required>
      <template #label><tooltip-label :title="$t('label.scope')" :tooltip="$t('message.ldap.scope.help')" focusable /></template>
      <a-select id="ldap-server-scope" v-model:value="form.scope" :disabled="busy" :options="scopes" />
    </a-form-item>
    <a-form-item
      v-if="form.scope === 'domain'"
      :label="$t('label.domain')"
      html-for="ldap-server-domain"
      required
      :validate-status="errors.domainid ? 'error' : undefined"
      :help="errors.domainid ? $t(errors.domainid) : undefined">
      <a-select
        id="ldap-server-domain"
        v-model:value="form.domainid"
        :options="domains"
        :loading="domainsPending"
        :disabled="busy"
        show-search
        :filter-option="false"
        :placeholder="$t('label.ldap.domain.placeholder')"
        @search="loadDomains" />
    </a-form-item>
    <a-alert v-if="message" :type="uncertain ? 'warning' : 'error'" show-icon :message="$t(message)" />
    <mold-dialog-actions :busy="busy">
      <a-button :disabled="busy" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :disabled="!canAdd" :loading="busy" @click="submit">{{ $t('label.ok') }}</a-button>
    </mold-dialog-actions>
  </a-form>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import { ldapEndpointExists, ldapRegistration, ldapRegistrationFailure } from '@/utils/ldapRegistration'

const readOptions = { preserveOnFailure: true, timeout: 10000 }

export default {
  name: 'AddLdapConfiguration',
  components: { MoldDialogActions, TooltipLabel },
  emits: ['close-action', 'refresh-data'],
  data () {
    const domainid = 'listDomains' in this.$store.getters.apis ? this.$route.query.domainid : undefined
    return {
      form: { hostname: '', port: '389', scope: domainid ? 'domain' : 'global', domainid },
      errors: {},
      domains: [],
      domainsPending: false,
      domainVersion: 0,
      busy: false,
      message: '',
      uncertain: null
    }
  },
  computed: {
    canAdd () { return 'addLdapConfiguration' in this.$store.getters.apis },
    scopes () {
      return [
        { value: 'global', label: this.$t('label.ldap.scope.global') },
        { value: 'domain', label: this.$t('label.domain'), disabled: !('listDomains' in this.$store.getters.apis) }
      ]
    }
  },
  watch: {
    'form.scope' (scope) {
      this.errors = {}
      if (scope === 'domain') this.loadDomains()
    }
  },
  mounted () {
    if (this.form.scope === 'domain') this.loadDomains()
  },
  beforeUnmount () { this.domainVersion++ },
  methods: {
    async loadDomains (keyword) {
      const version = ++this.domainVersion
      this.domainsPending = true
      try {
        const response = await getAPI('listDomains', { listall: true, details: 'min', page: 1, pagesize: 20, keyword: keyword || undefined }, readOptions)
        const domains = response.listdomainsresponse?.domain || []
        if (this.form.domainid && !domains.some(row => row.id === this.form.domainid)) {
          const selected = await getAPI('listDomains', { id: this.form.domainid, details: 'min' }, readOptions)
          domains.unshift(...(selected.listdomainsresponse?.domain || []))
        }
        if (version === this.domainVersion) this.domains = domains.map(row => ({ value: row.id, label: row.path || row.name || row.id }))
      } catch {
        if (version === this.domainVersion) {
          this.domains = []
          this.message = 'message.ldap.domain.failed'
        }
      } finally {
        if (version === this.domainVersion) this.domainsPending = false
      }
    },
    complete () {
      this.$message.success(this.$t('message.ldap.add.created'))
      this.$store.dispatch('UpdateConfiguration')
      this.$emit('refresh-data', { irefresh: true })
      this.$emit('close-action')
    },
    async reconcile () {
      try {
        const exists = await ldapEndpointExists(getAPI, this.uncertain)
        this.uncertain = null
        if (exists) this.complete()
        else this.message = 'message.ldap.add.not.saved'
        return exists
      } catch {
        this.message = 'message.ldap.add.unknown'
        return null
      }
    },
    async submit (event) {
      if (event?.repeat || this.busy || !this.canAdd) return
      const { params, errors } = ldapRegistration(this.form, this.domains.some(domain => domain.value === this.form.domainid))
      this.errors = errors
      if (Object.keys(errors).length) return
      this.message = ''
      this.busy = true
      try {
        if (this.uncertain && await this.reconcile() !== false) return
        await postAPI('addLdapConfiguration', params, { preserveOnFailure: true, timeout: 25000 })
        this.complete()
      } catch (error) {
        this.message = ldapRegistrationFailure(error)
        if (this.message === 'message.ldap.add.unknown') {
          this.uncertain = params
          await this.reconcile()
        }
      } finally {
        this.busy = false
      }
    }
  }
}
</script>
