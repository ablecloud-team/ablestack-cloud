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
  <div>
    <a-descriptions :column="1" size="small">
      <a-descriptions-item :label="$t('label.hostname')">{{ endpoint }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.scope')">{{ scope }}</a-descriptions-item>
    </a-descriptions>
    <a-alert show-icon type="warning" :message="$t('message.ldap.delete.confirm')" />
    <a-alert v-if="message" show-icon :type="unknown ? 'warning' : 'error'" :message="$t(message)" />
    <mold-dialog-actions :busy="busy">
      <a-button id="ldap-delete-cancel" :disabled="busy" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" danger :disabled="!ready" :loading="busy" @click="remove">{{ $t('label.remove.ldap') }}</a-button>
    </mold-dialog-actions>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { ldapEndpointLabel } from '@/utils/ldapConfiguration'
import { ldapEndpointExists } from '@/utils/ldapRegistration'

export default {
  name: 'DeleteLdapConfiguration',
  components: { MoldDialogActions },
  props: {
    currentAction: { type: Object, required: true },
    loading: { type: Boolean, default: false }
  },
  emits: ['close-action'],
  data () {
    return { message: '', busy: false, unknown: false }
  },
  computed: {
    target () { return this.currentAction.ldapTarget },
    endpoint () { return ldapEndpointLabel(this.target) },
    scope () {
      return this.target.domainid ? this.target.domainpath || this.target.domainid : this.$t('label.ldap.scope.global')
    },
    ready () { return !this.loading && this.currentAction.ldapCurrent() && 'deleteLdapConfiguration' in this.$store.getters.apis }
  },
  methods: {
    complete (key) {
      if (!this.currentAction.ldapCurrent()) return
      this.$message.success(this.$t(key))
      this.$store.dispatch('UpdateConfiguration')
      this.$emit('close-action', { ldapRemoved: this.target.id })
    },
    async reconcile () {
      try {
        const { hostname, port, domainid, id } = this.target
        const exists = await ldapEndpointExists(getAPI, { hostname, port, domainid }, id)
        this.unknown = false
        if (!exists) this.complete('message.ldap.delete.missing')
        else this.message = 'message.ldap.delete.exists'
        return exists
      } catch {
        this.unknown = true
        this.message = 'message.ldap.delete.unknown'
        return null
      }
    },
    async remove () {
      if (this.busy || !this.ready) return
      this.busy = true
      this.message = ''
      const id = this.target.id
      try {
        if (this.unknown && await this.reconcile() !== true) return
        if (!this.ready) return
        const response = await postAPI('deleteLdapConfiguration', { id }, { preserveOnFailure: true, timeout: 20000 })
        if (response.ldapconfigurationresponse?.LdapDeleteConfiguration?.id === id) {
          this.complete('message.ldap.delete.removed')
        } else {
          await this.reconcile()
        }
      } catch (error) {
        if (!error.response || error.response.status === 431) await this.reconcile()
        else this.message = 'message.ldap.delete.failed'
      } finally {
        this.busy = false
      }
    }
  }
}
</script>
