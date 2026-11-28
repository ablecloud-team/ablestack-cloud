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
  <div class="form-layout" v-ctrl-enter="submit">
    <a-alert v-if="error" type="error" show-icon :message="error" role="alert" />
    <a-form layout="vertical" @finish="submit">
      <a-form-item :label="$t('label.name')" required>
        <a-input v-model:value="name" :aria-label="$t('label.name')" :disabled="submitting" />
      </a-form-item>
      <a-form-item :label="$t('label.description')">
        <a-input v-model:value="description" :aria-label="$t('label.description')" :disabled="submitting" />
      </a-form-item>
      <a-form-item v-if="projectId" :label="$t('label.project')">
        {{ $store.getters.project.name || projectId }}
      </a-form-item>
      <a-form-item v-else :label="$t('label.owner.account')">
        <a-radio-group v-if="canSelectOwner" :value="ownerMode" :disabled="submitting" @change="changeOwnerMode">
          <a-radio value="current">{{ $t('label.security.group.owner.current') }} ({{ currentAccount }})</a-radio>
          <a-radio value="other">{{ $t('label.security.group.owner.select') }}</a-radio>
        </a-radio-group>
        <span v-else>{{ currentAccount }}</span>
      </a-form-item>
      <OwnershipSelection
        v-if="canSelectOwner && ownerMode === 'other'"
        embedded
        safe-scope
        account-only
        :disabled="submitting"
        @fetch-owner="setOwner" />
      <MoldDialogActions :busy="submitting">
        <a-button :disabled="submitting" @click="close">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">{{ $t('label.ok') }}</a-button>
      </MoldDialogActions>
    </a-form>
  </div>
</template>

<script>
import { postAPI } from '@/api'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import eventBus from '@/config/eventBus'
import { isAdminOrDomainAdmin } from '@/role'
import OwnershipSelection from '@/views/compute/wizard/OwnershipSelection.vue'

export default {
  name: 'CreateSecurityGroup',
  inheritAttrs: false,
  components: { MoldDialogActions, OwnershipSelection },
  emits: ['close-action', 'refresh-data'],
  data () {
    return {
      name: '',
      description: '',
      ownerMode: 'current',
      owner: null,
      submitting: false,
      error: '',
      closed: false
    }
  },
  computed: {
    projectId () {
      return this.$store.getters.project?.id || ''
    },
    currentAccount () {
      return this.$store.getters.userInfo?.account
    },
    permitted () {
      return !!this.$store.getters.apis?.createSecurityGroup
    },
    canSelectOwner () {
      const apis = this.$store.getters.apis
      return !this.projectId && isAdminOrDomainAdmin() && !!apis.listDomains && !!apis.listAccounts
    },
    scope () {
      const { userInfo, apis } = this.$store.getters
      return JSON.stringify([this.$route.fullPath, userInfo?.id, userInfo?.domainid, userInfo?.roleid,
        this.projectId, this.permitted, this.canSelectOwner, !!apis?.listSecurityGroups])
    },
    canSubmit () {
      return !this.closed && !this.submitting && this.permitted && !!this.name.trim() &&
        (this.ownerMode !== 'other' || (this.canSelectOwner && !!this.owner))
    }
  },
  watch: {
    scope: { flush: 'sync', handler () { this.close() } }
  },
  created () {
    eventBus.on('project-switch-start', this.close)
  },
  beforeUnmount () {
    this.closed = true
    eventBus.off('project-switch-start', this.close)
  },
  methods: {
    changeOwnerMode (event) {
      this.owner = null
      this.error = ''
      this.ownerMode = event.target.value
    },
    setOwner (selection) {
      this.owner = selection.ready
        ? { account: selection.selectedAccount, domainid: selection.selectedDomain }
        : null
    },
    close () {
      if (this.closed) return
      this.closed = true
      this.$emit('close-action')
    },
    async submit () {
      if (!this.canSubmit) return
      const target = this.projectId ? { projectid: this.projectId } : this.owner || {}
      this.submitting = true
      this.error = ''
      try {
        await postAPI('createSecurityGroup', {
          name: this.name.trim(),
          description: this.description,
          ignoreproject: true,
          ...target
        }, { preserveOnFailure: true })
        if (this.closed) return
        this.$notification.success({ message: this.$t('message.success.create.security.group') })
        this.$emit('refresh-data')
        this.close()
      } catch (error) {
        if (this.closed) return
        const response = error?.response?.data
        const result = Object.values(response || {}).find(value => value?.errortext)
        this.error = result?.errortext || this.$t('message.request.failed')
      } finally {
        this.submitting = false
      }
    }
  }
}
</script>
