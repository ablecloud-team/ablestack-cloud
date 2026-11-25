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
  <a-form class="oauth-credential-form" layout="vertical" :model="form">
    <a-form-item
      v-if="registering"
      html-for="oauth-form-provider"
      :validate-status="errors.provider ? 'error' : undefined"
      :help="fieldError('provider')"
      required>
      <template #label><tooltip-label :title="$t('label.provider')" :tooltip="fieldHelp('provider')" focusable /></template>
      <a-select id="oauth-form-provider" v-model:value="form.provider" :options="providers" :disabled="busy" />
    </a-form-item>
    <a-form-item
      v-for="field in fields"
      :key="field"
      :html-for="'oauth-form-' + field"
      :required="registering"
      :validate-status="errors[field] ? 'error' : undefined"
      :help="fieldError(field)">
      <template #label><tooltip-label :title="$t('label.' + field)" :tooltip="fieldHelp(field)" focusable /></template>
      <a-input-password
        v-if="field === 'secretkey'"
        id="oauth-form-secretkey"
        v-model:value="form.secretkey"
        autocomplete="new-password"
        :disabled="busy"
        :placeholder="registering ? undefined : $t('message.oauth.secret.replace')"
        @keydown.enter.prevent="submit" />
      <a-input
        v-else
        :id="'oauth-form-' + field"
        v-model:value="form[field]"
        :disabled="busy"
        @keydown.enter.prevent="submit" />
    </a-form-item>
    <a-form-item html-for="oauth-form-scope">
      <template #label><tooltip-label :title="$t('label.scope')" :tooltip="fieldHelp('scope')" focusable /></template>
      <a-select id="oauth-form-scope" v-model:value="form.scope" :disabled="busy" :options="scopes" />
    </a-form-item>
    <a-form-item
      v-if="form.scope === 'domain'"
      :label="$t('label.domain')"
      html-for="oauth-form-domain"
      :validate-status="domainError ? 'error' : undefined"
      :help="domainError ? $t(domainError) : undefined"
      required>
      <a-select
        id="oauth-form-domain"
        v-model:value="form.domainid"
        :options="domains"
        :loading="domainsPending"
        :disabled="busy"
        :filter-option="false"
        :placeholder="$t('label.oauth.domain.select')"
        show-search
        @search="loadDomains" />
    </a-form-item>
    <a-alert v-if="registering && policy === 'disabled'" type="warning" show-icon :message="$t('message.oauth.scope.disabled')" />
    <a-alert v-if="registering && policy === 'error'" type="error" show-icon :message="$t('message.oauth.scope.read.failed')">
      <template #description>
        <a-button v-if="canReadPolicy" size="small" @click="loadScopePolicy">{{ $t('label.retry') }}</a-button>
      </template>
    </a-alert>
    <a-alert v-if="message" type="error" role="alert" show-icon :message="$t(message)" />
    <mold-dialog-actions :busy="busy">
      <a-button @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="busy" :disabled="!canSubmit" @click="submit">{{ $t('label.ok') }}</a-button>
    </mold-dialog-actions>
  </a-form>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import TooltipLabel from '@/components/widgets/TooltipLabel'

const readOptions = { preserveOnFailure: true, timeout: 10000 }

export default {
  name: 'OAuthProviderForm',
  components: { MoldDialogActions, TooltipLabel },
  props: { currentAction: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data () {
    const target = this.currentAction.oauthTarget
    const registering = this.currentAction.api === 'registerOauthProvider'
    const domainid = registering && 'listDomains' in this.$store.getters.apis ? this.$route.query.domainid : target.domainid
    return {
      form: {
        provider: target.provider,
        description: target.description || '',
        clientid: target.clientid || '',
        redirecturi: target.redirecturi || '',
        secretkey: '',
        authorizeurl: target.authorizeurl || '',
        tokenurl: target.tokenurl || '',
        scope: registering ? domainid ? 'domain' : 'global' : 'keep',
        domainid
      },
      providers: [
        { value: 'google', label: 'Google' },
        { value: 'github', label: 'GitHub' },
        { value: 'keycloak', label: 'Keycloak' }
      ],
      errors: {},
      policy: '',
      policyVersion: 0,
      domains: [],
      domainsPending: false,
      domainVersion: 0,
      domainError: '',
      message: '',
      busy: false
    }
  },
  computed: {
    registering () { return this.currentAction.api === 'registerOauthProvider' },
    fields () {
      const fields = ['description', 'clientid', 'redirecturi', 'secretkey']
      if (this.form.provider === 'keycloak') fields.push('authorizeurl', 'tokenurl')
      return fields
    },
    canReadDomains () { return 'listDomains' in this.$store.getters.apis },
    canReadPolicy () { return 'listConfigurations' in this.$store.getters.apis },
    canSubmit () {
      return this.currentAction.api in this.$store.getters.apis && (!this.registering || this.policy === 'enabled')
    },
    policyScope () {
      if (!this.registering) return undefined
      return this.form.scope === 'global' ? 'global' : this.form.domainid || ''
    },
    scopes () {
      const options = [
        { value: 'global', label: this.$t('label.oauth.scope.global'), disabled: !this.registering && !this.canReadDomains },
        { value: 'domain', label: this.$t('label.domain'), disabled: !this.canReadDomains }
      ]
      if (!this.registering) options.unshift({ value: 'keep', label: this.$t('label.oauth.scope.keep') })
      return options
    }
  },
  watch: {
    policyScope: { handler: 'loadScopePolicy', immediate: true },
    'form.provider' (provider) {
      this.errors = {}
      this.message = ''
      if (provider !== 'keycloak') {
        this.form.authorizeurl = ''
        this.form.tokenurl = ''
      }
    },
    'form.scope' (scope) {
      this.domainError = ''
      this.message = ''
      if (scope === 'domain') this.loadDomains()
      else {
        this.domainVersion++
        this.domainsPending = false
      }
    },
    'form.domainid' () { this.domainError = ''; this.message = '' }
  },
  mounted () {
    if (this.form.scope === 'domain') this.loadDomains()
  },
  beforeUnmount () { this.domainVersion++; this.policyVersion++ },
  methods: {
    fieldHelp (field) {
      if (field === 'secretkey' && !this.registering) return this.$t('message.oauth.secret.replace')
      return this.$t('message.oauth.field.help.' + field)
    },
    fieldError (field) {
      return this.errors[field] ? this.$t(this.errors[field], { field: this.$t('label.' + field) }) : undefined
    },
    async loadScopePolicy () {
      const scope = this.policyScope
      const version = ++this.policyVersion
      this.policy = ''
      if (!scope) return
      if (!this.canReadPolicy) { this.policy = 'error'; return }
      this.policy = 'pending'
      try {
        const result = await getAPI('listConfigurations', { name: 'oauth2.enabled', ...(scope === 'global' ? {} : { domainid: scope }) }, readOptions)
        const setting = result.listconfigurationsresponse?.configuration?.find(row => row.name === 'oauth2.enabled')
        if (version !== this.policyVersion) return
        this.policy = setting ? (String(setting.value).toLowerCase() === 'true' ? 'enabled' : 'disabled') : 'error'
      } catch {
        if (version === this.policyVersion) this.policy = 'error'
      }
    },
    checkInputs () {
      const errors = {}
      for (const field of this.registering ? ['provider', ...this.fields] : this.fields) {
        const value = this.form[field]?.trim()
        if (!value && this.registering) errors[field] = 'message.oauth.field.required'
        if (this.registering && value && ['redirecturi', 'authorizeurl', 'tokenurl'].includes(field)) {
          try {
            const url = new URL(value)
            if (!['http:', 'https:'].includes(url.protocol)) errors[field] = 'message.oauth.url.invalid'
          } catch {
            errors[field] = 'message.oauth.url.invalid'
          }
        }
      }
      this.errors = errors
      return Object.keys(errors).length === 0
    },
    async loadDomains (keyword) {
      const version = ++this.domainVersion
      this.domainsPending = true
      try {
        const result = await getAPI('listDomains', { listall: true, details: 'min', keyword: keyword || undefined, page: 1, pagesize: 20 }, readOptions)
        const domains = (result.listdomainsresponse?.domain || []).filter(row => row.name !== 'ROOT')
        if (this.form.domainid && !domains.some(row => row.id === this.form.domainid) && !keyword) {
          const selected = await getAPI('listDomains', { id: this.form.domainid, details: 'min' }, readOptions)
          domains.unshift(...(selected.listdomainsresponse?.domain || []))
        }
        if (version === this.domainVersion) this.domains = domains.map(row => ({ value: row.id, label: row.path || row.name || row.id }))
      } catch {
        if (version === this.domainVersion) this.message = 'message.oauth.domain.read.failed'
      } finally {
        if (version === this.domainVersion) this.domainsPending = false
      }
    },
    async submit (event) {
      const { api, oauthTarget, oauthCurrent } = this.currentAction
      if (event?.repeat || this.busy || !this.canSubmit || !oauthCurrent()) return
      const { scope, domainid } = this.form
      if (scope === 'domain' && !domainid) {
        this.domainError = 'message.oauth.domain.required'
        return
      }
      if (!this.checkInputs()) return
      this.message = ''
      this.busy = true
      try {
        const params = this.registering ? { provider: this.form.provider } : { id: oauthTarget.id }
        for (const field of this.fields) {
          const value = this.form[field].trim()
          if (value) params[field] = value
        }
        if (scope === 'domain') params.domainid = domainid
        if (scope === 'global' && oauthTarget.domainid) {
          const result = await getAPI('listDomains', { name: 'ROOT', details: 'min' }, readOptions)
          const root = result.listdomainsresponse?.domain?.find(row => row.name === 'ROOT')
          if (!root?.id) {
            if (oauthCurrent()) this.message = 'message.oauth.domain.read.failed'
            return
          }
          params.domainid = root.id
        }
        if (!oauthCurrent() || !this.canSubmit) return
        await postAPI(api, params, { preserveOnFailure: true, timeout: 25000 })
        if (!oauthCurrent()) return
        this.$message.success(this.$t(this.registering ? 'message.oauth.registered' : 'message.oauth.saved'))
        this.$emit('close-action')
        this.$emit('refresh-data', { irefresh: true })
      } catch (error) {
        if (!oauthCurrent()) return
        const result = Object.values(error.response?.data || {}).find(row => row?.errorcode || row?.errortext)
        const status = Number(error.response?.status || result?.errorcode)
        const message = result?.errortext || ''
        if ([401, 403].includes(status)) this.message = 'message.oauth.action.denied'
        else if (/already registered/i.test(message)) this.message = 'message.oauth.scope.duplicate'
        else if (this.registering && /oauth.*not enabled/i.test(message)) this.policy = 'disabled'
        else this.message = 'message.oauth.save.failed'
      } finally {
        this.busy = false
      }
    }
  }
}
</script>

<style scoped>
.oauth-credential-form :deep(.ant-input-password:not(.ant-input-affix-wrapper-disabled)) {
  background: var(--ui-bg-input);
  border-color: var(--ui-border-strong);
}
.oauth-credential-form :deep(.ant-input-password:not(.ant-input-affix-wrapper-disabled):hover),
.oauth-credential-form :deep(.ant-input-password:focus-within) {
  border-color: var(--ui-focus);
}
.oauth-credential-form :deep(.ant-input-password-icon) {
  color: var(--ui-text-secondary);
}
.oauth-credential-form :deep(.ant-input-password-icon:hover) {
  color: var(--ui-link);
}
</style>
