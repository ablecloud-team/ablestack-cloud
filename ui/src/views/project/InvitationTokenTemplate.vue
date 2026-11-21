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
  <div @keydown.ctrl.enter.prevent.stop="!$event.repeat && submit()">
    <a-form layout="vertical">
      <a-alert v-if="membershipError" show-icon type="error" :message="membershipError" class="token-error" />
      <a-form-item :label="$t('label.projectid')" html-for="invite-token-project" required>
        <a-input id="invite-token-project" v-model:value="projectid" :disabled="membershipLocked" v-focus="true" />
      </a-form-item>
      <a-form-item :label="$t('label.token')" html-for="invite-token-code" required>
        <a-input-password id="invite-token-code" v-model:value="token" :disabled="membershipLocked" />
      </a-form-item>
    </a-form>
    <MoldDialogActions :busy="membershipBusy">
      <a-button :disabled="membershipBusy" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="membershipBusy" :disabled="disabled" @click="submit">
        {{ $t(membershipJobId ? 'label.job.check.result' : 'label.accept.project.invitation') }}
      </a-button>
    </MoldDialogActions>
  </div>
</template>

<script>
import eventBus from '@/config/eventBus'
import { projectMembershipJob } from '@/utils/projectMembership'
import MoldDialogActions from '@/components/view/MoldDialogActions'

export default {
  name: 'InvitationTokenTemplate',
  components: { MoldDialogActions },
  mixins: [projectMembershipJob],
  emits: ['close-action', 'refresh-data'],
  data: () => ({ projectid: '', token: '', disposed: false }),
  computed: {
    disabled () { return this.membershipBusy || (!this.membershipJobId && (this.membershipUnknown || !this.projectid.trim() || !this.token.trim())) }
  },
  beforeUnmount () { this.disposed = true },
  methods: {
    async submit () {
      if (this.disabled) return
      const projectid = this.projectid.trim()
      const params = { projectid, token: this.token.trim(), accept: true }
      if (!await this.runMembershipJob('updateProjectInvitation', params, 'label.accept.project.invitation') || this.disposed) return
      eventBus.emit('project-invitations-updated', projectid)
      this.$emit('refresh-data', { irefresh: true })
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped>
.token-error { margin-bottom: 12px; }
</style>
