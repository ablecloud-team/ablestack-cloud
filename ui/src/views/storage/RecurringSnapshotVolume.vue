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
  <div class="snapshot-layout">
    <a-alert v-if="volumesError || policyError" type="error" :message="volumesError || policyError" show-icon />
    <a-button v-if="volumesError" @click="fetchVolumes">{{ $t('label.retry') }}</a-button>
    <a-button v-if="policyError" @click="fetchData">{{ $t('label.retry') }}</a-button>
    <div v-if="isVolumeResource" class="volume-operation-target">
      <hdd-outlined aria-hidden="true" /><span>{{ currentVolumeResource.name }}</span><span class="operation-secondary">{{ currentVolumeResource.type }}</span>
    </div>
    <FormSchedule
      :key="currentVolumeResource?.id || 'unselected'"
      :loading="loading"
      :disabled="!currentVolumeResource?.id || loading || !!policyError || (!isVolumeResource && (volumesLoading || !!volumesError))"
      :resource="currentVolumeResource || {}"
      :dataSource="dataSource"
      resourceType="Volume"
      @busy="busy = $event"
      @close-action="closeAction"
      @refresh="handleRefresh">
      <template v-if="!isVolumeResource" #resource-selection>
        <a-form-item :label="$t('label.volume')" html-for="policy-create-volume" required>
          <a-select
            id="policy-create-volume"
            v-model:value="selectedVolumeId"
            :placeholder="$t('label.select.volume')"
            :loading="volumesLoading"
            :disabled="busy || volumesLoading || !!volumesError"
            :options="volumeOptions"
            option-label-prop="label"
            dropdown-class-name="snapshot-policy-volume-dropdown"
            show-search
            :filter-option="filterOption"
            @change="onVolumeChange"
          >
            <template #option="{ label, description }">
              <div class="snapshot-policy-volume-option">
                <div>{{ label }}</div>
                <div class="operation-secondary">{{ description }}</div>
              </div>
            </template>
          </a-select>
          <template v-if="selectedVolume" #extra><p class="selected-volume-description">{{ volumeDescription(selectedVolume) }}</p></template>
        </a-form-item>
      </template>
    </FormSchedule>
  </div>
</template>

<script>
import { getAPI } from '@/utils/storageApi'
import { readAllocationList } from '@/utils/volumeAllocation'
import FormSchedule from '@/views/storage/FormSchedule'

export default {
  name: 'RecurringSnapshotVolume',
  components: {
    FormSchedule
  },
  props: {
    resource: {
      type: Object,
      required: false,
      default: () => null
    }
  },
  inject: ['parentFetchData'],
  data () {
    return {
      loading: false,
      busy: false,
      dataSource: [],
      volumes: [],
      volumesLoading: false,
      selectedVolumeId: null,
      selectedVolume: null,
      volumesError: '',
      policyError: '',
      volumeVersion: 0,
      policyVersion: 0,
      disposed: false
    }
  },
  computed: {
    ownerContext () { return JSON.stringify([this.$store.getters.userInfo?.id, this.$store.state?.user?.token, this.$store.getters.project?.id]) },
    volumeOptions () {
      return this.volumes.map(volume => {
        const description = this.volumeDescription(volume)
        const size = volume.size == null ? NaN : Number(volume.size)
        const label = `${volume.name || volume.id} (${Number.isFinite(size) && size >= 0 ? (size / 1024 / 1024 / 1024).toFixed(2) + ' GiB' : '—'})`
        return {
          value: volume.id,
          label,
          description,
          searchText: [label, description, volume.vmname, volume.vmdisplayname].filter(value => value != null).join(' ')
        }
      })
    },
    resourceType () {
      if (!this.resource) return 'none'
      if (this.resource.type === 'ROOT' || this.resource.type === 'DATADISK' ||
          this.resource.state === 'Ready' || this.resource.state === 'Allocated' ||
          this.resource.sizegb !== undefined) {
        return 'volume'
      }
      if (this.resource.intervaltype !== undefined || this.resource.schedule !== undefined) {
        return 'snapshotpolicy'
      }

      return 'unknown'
    },

    isVolumeResource () {
      return this.resourceType === 'volume'
    },

    currentVolumeResource () {
      if (this.isVolumeResource) {
        return this.resource
      } else {
        return this.selectedVolume
      }
    }
  },
  created () {
    if (this.isVolumeResource) {
      this.fetchData()
    } else {
      this.fetchVolumes()
    }
  },
  watch: {
    ownerContext () {
      this.volumeVersion++; this.policyVersion++; this.dataSource = []; this.volumes = []; this.selectedVolumeId = null; this.selectedVolume = null
      this.loading = false; this.volumesLoading = false; this.policyError = ''; this.volumesError = ''
      if (this.isVolumeResource) this.fetchData(); else this.fetchVolumes()
    },
    'resource.id' () { this.policyVersion++; this.dataSource = []; this.policyError = ''; this.loading = false; this.fetchData() }
  },
  beforeUnmount () { this.disposed = true; this.volumeVersion++; this.policyVersion++ },
  methods: {
    async fetchVolumes () {
      const version = ++this.volumeVersion; const context = this.ownerContext
      const current = () => !this.disposed && version === this.volumeVersion && context === this.ownerContext
      this.volumesLoading = true
      this.volumesError = ''
      try {
        const volumes = await readAllocationList(getAPI, 'listVolumes', 'volume', { listall: true }, current)
        if (!current()) return
        this.volumes = volumes.filter(volume => {
          return volume.state === 'Ready' &&
                (volume.hypervisor !== 'KVM' ||
                  (['Stopped', 'Destroyed'].includes(volume.vmstate)) ||
                  (this.$store.getters.features.kvmsnapshotenabled))
        })
      } catch (error) {
        if (current()) this.volumesError = this.failureText(error)
      } finally {
        if (current()) this.volumesLoading = false
      }
    },
    onVolumeChange (volumeId) {
      if (this.busy) return
      const volume = this.volumes.find(v => v.id === volumeId)
      this.policyVersion++
      this.selectedVolume = volume || null
      this.selectedVolumeId = volume?.id || null
      this.dataSource = []
      this.policyError = ''
      this.loading = false
      if (volume) this.fetchData()
    },
    failureText (error) { return Object.values(error?.response?.data || {}).find(value => value?.errortext)?.errortext || error?.message || this.$t('message.request.failed') },
    async fetchData () {
      const volumeResource = this.currentVolumeResource
      if (!volumeResource || !volumeResource.id) {
        return
      }

      const version = ++this.policyVersion; const context = this.ownerContext
      const current = () => !this.disposed && version === this.policyVersion && context === this.ownerContext && this.currentVolumeResource?.id === volumeResource.id
      const params = {
        volumeid: volumeResource.id,
        listall: true
      }

      this.loading = true
      this.policyError = ''
      try {
        const rows = await readAllocationList(getAPI, 'listSnapshotPolicies', 'snapshotpolicy', params, current)
        if (current()) this.dataSource = rows
      } catch (error) { if (current()) this.policyError = this.failureText(error) } finally { if (current()) this.loading = false }
    },
    handleRefresh () {
      this.fetchData()
      this.parentFetchData()
    },
    closeAction () {
      this.fetchData()
      this.$emit('close-action')
    },
    filterOption (input, option) {
      return String(option?.searchText ?? option?.label ?? '').toLowerCase().includes(String(input ?? '').trim().toLowerCase())
    },
    volumeDescription (volume) {
      const vm = volume.vmdisplayname || volume.vmname || volume.virtualmachineid
      const project = volume.project || volume.projectid
      const owner = project || volume.account
      const domain = volume.domain || volume.domainid
      const zone = volume.zonename || volume.zoneid
      return [
        vm && `${this.$t('label.virtualmachine')}: ${vm}`,
        owner && `${this.$t(project ? 'label.project' : 'label.account')}: ${owner}`,
        domain && `${this.$t('label.domain')}: ${domain}`,
        zone && `${this.$t('label.zone')}: ${zone}`,
        `${this.$t('label.id')}: ${volume.id}`
      ].filter(Boolean).join(' · ')
    }
  }
}
</script>

<style lang="scss" scoped>
@import '@/style/volume-operation-dialog';
.snapshot-layout {
  width: 100%;
  min-width: 0;
  max-width: 100%;

  .selected-volume-description {
    overflow-wrap: anywhere;
    margin: 0;
    color: var(--ui-text-secondary);
  }
}
</style>

<style lang="scss">
.snapshot-policy-volume-dropdown .snapshot-policy-volume-option {
  white-space: normal;
  overflow-wrap: anywhere;

  .operation-secondary {
    font-size: 12px;
    color: var(--ui-text-secondary);
  }
}
</style>
