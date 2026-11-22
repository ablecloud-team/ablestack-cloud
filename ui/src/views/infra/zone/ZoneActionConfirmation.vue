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
  <div v-ctrl-enter="submit">
    <dl class="zone-action-summary">
      <dt>{{ $t('label.name') }}</dt>
      <dd>{{ resource.name }}</dd>
      <dt>{{ $t('label.allocationstate') }}</dt>
      <dd><status :text="resource.allocationstate" display-text /></dd>
    </dl>
    <p v-if="!deleting">{{ $t(currentAction.message) }}</p>
    <template v-else>
      <p v-if="checking" role="status">{{ $t('label.loading') }}</p>
      <a-alert v-else-if="!impactReady" type="error" show-icon :message="$t('message.zone.delete.impact.failed')">
        <template #description><a-button size="small" @click="loadImpact">{{ $t('label.retry') }}</a-button></template>
      </a-alert>
      <a-alert v-else show-icon :type="hasDependencies ? 'warning' : 'info'" :message="$t(hasDependencies ? 'message.zone.delete.blocked' : 'message.zone.delete.impact.clear')" />
      <dl v-if="impact.length" class="zone-action-summary">
        <template v-for="item in impact" :key="item.label">
          <dt>{{ $t(item.label) }}</dt>
          <dd>{{ item.count ?? '—' }}</dd>
        </template>
      </dl>
    </template>
    <a-alert v-if="submitError" type="error" show-icon :message="$t('message.zone.operation.failed')" :description="submitError" />
    <mold-dialog-actions :busy="submitting">
      <a-button :disabled="submitting" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :danger="deleting" :loading="submitting" :disabled="!canSubmit" @click="submit">
        {{ $t(currentAction.label) }}
      </a-button>
    </mold-dialog-actions>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import Status from '@/components/widgets/Status'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { zoneListQuery } from '@/utils/zone'

const dependencies = [
  ['listHosts', 'host', 'label.hosts'],
  ['listPods', 'pod', 'label.pods'],
  ['listVirtualMachines', 'virtualmachine', 'label.virtual.machines'],
  ['listSystemVms', 'systemvm', 'label.system.vms'],
  ['listVolumes', 'volume', 'label.volumes'],
  ['listPhysicalNetworks', 'physicalnetwork', 'label.physical.network'],
  ['listImageStores', 'imagestore', 'label.secondary.storage']
]

export default {
  name: 'ZoneActionConfirmation',
  components: { Status, MoldDialogActions },
  props: {
    resource: { type: Object, required: true },
    currentAction: { type: Object, required: true }
  },
  emits: ['close-action', 'refresh-data'],
  data () { return { checking: false, submitting: false, impact: [], submitError: '' } },
  computed: {
    deleting () { return this.currentAction.api === 'deleteZone' },
    impactReady () { return this.impact.length === dependencies.length && this.impact.every(item => item.count !== null) },
    hasDependencies () { return this.impact.some(item => item.count > 0) },
    canSubmit () {
      return !!this.resource.id && this.currentAction.api in this.$store.getters.apis && !this.submitting &&
        (!this.deleting || (!this.checking && this.impactReady && !this.hasDependencies))
    }
  },
  created () { if (this.deleting) this.loadImpact() },
  methods: {
    async loadImpact () {
      this.checking = true
      this.impact = await Promise.all(dependencies.map(async ([command, key, label]) => {
        try {
          if (!(command in this.$store.getters.apis)) return { label, count: null }
          const params = { zoneid: this.resource.id, page: 1, pagesize: 1 }
          if (['listVirtualMachines', 'listVolumes'].includes(command)) params.listall = true
          const response = (await getAPI(command, params, { preserveOnFailure: true, timeout: 12000 }))[command.toLowerCase() + 'response']
          return { label, count: response && !response.errorcode ? Number(response.count ?? response[key]?.length ?? 0) : null }
        } catch (_) {
          return { label, count: null }
        }
      }))
      this.checking = false
    },
    async submit () {
      if (!this.canSubmit) return
      this.submitting = true
      this.submitError = ''
      const { api, defaultArgs } = this.currentAction
      const params = { id: this.resource.id, ...defaultArgs }
      try {
        const response = (await postAPI(api, params, { preserveOnFailure: true, timeout: 15000 }))[api.toLowerCase() + 'response']
        if (this.deleting && response?.success !== true) throw new Error(this.$t('message.job.result.unknown'))
        if (!this.deleting && response?.zone?.allocationstate !== params.allocationstate) {
          const result = await getAPI('listZones', { id: params.id }, { preserveOnFailure: true })
          if (result.listzonesresponse.zone?.[0]?.allocationstate !== params.allocationstate) throw new Error(this.$t('message.job.result.unknown'))
        }
        this.$message.success(this.$t('label.success'))
        this.$emit('close-action')
        if (this.deleting) await this.$router.push({ path: '/zone', query: { ...zoneListQuery(this.$route.query), page: '1' } })
        else this.$emit('refresh-data')
      } catch (error) {
        this.submitError = Object.values(error.response?.data || {}).find(result => result?.errortext)?.errortext || error.message
        if (this.deleting) await this.loadImpact()
      } finally {
        this.submitting = false
      }
    }
  }
}
</script>

<style scoped>
.zone-action-summary {
  display: grid;
  grid-template-columns: minmax(7rem, auto) 1fr;
  gap: 8px 12px;
  margin: 12px 0;
}
.zone-action-summary dd { margin: 0; overflow-wrap: anywhere; }
</style>
