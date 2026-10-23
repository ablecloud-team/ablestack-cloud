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
    <div v-if="!isVolumeResource" class="volume-selection">
      <a-form layout="vertical">
        <a-form-item :label="$t('label.volume')" required>
          <a-select
            v-model:value="selectedVolumeId"
            :placeholder="$t('label.select.volume')"
            :loading="volumesLoading"
            show-search
            :filter-option="filterOption"
            @change="onVolumeChange"
          >
            <a-select-option
              v-for="volume in volumes"
              :key="volume.id"
              :value="volume.id"
            >
              {{ volume.name }} ({{ (volume.size / 1024 / 1024 / 1024).toFixed(2) }} GB)
            </a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </div>
    <div v-if="currentVolumeResource?.id" class="volume-operation-target">
      <hdd-outlined aria-hidden="true" /><span>{{ currentVolumeResource.name }}</span><span class="operation-secondary">{{ currentVolumeResource.type }}</span>
    </div>
    <FormSchedule
      v-if="currentVolumeResource?.id"
      :key="currentVolumeResource.id"
      :loading="loading"
      :disabled="loading || !!policyError"
      :resource="currentVolumeResource"
      :dataSource="dataSource"
      resourceType="Volume"
      @close-action="closeAction"
      @refresh="handleRefresh" />
    <MoldDialogActions v-else><a-button @click="closeAction">{{ $t('label.close') }}</a-button></MoldDialogActions>
    <div v-if="!currentVolumeResource || !currentVolumeResource.id" class="no-volume-selected">
      <div class="empty-state">
        <p>{{ $t('message.select.volume.to.continue') }}</p>
      </div>
    </div>
  </div>
</template>

<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { getAPI } from '@/utils/storageApi'
import { readAllocationList } from '@/utils/volumeAllocation'
import FormSchedule from '@/views/storage/FormSchedule'

export default {
  name: 'RecurringSnapshotVolume',
  components: {
    MoldDialogActions,
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
      const volume = this.volumes.find(v => v.id === volumeId)
      if (volume) {
        this.selectedVolume = volume
        this.selectedVolumeId = volumeId
        this.dataSource = []
        this.fetchData()
      }
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
      return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
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

  .volume-selection {
    margin-bottom: 20px;
    padding: 16px;
    border: 1px solid var(--ui-border);
    border-radius: 6px;
    background: var(--ui-bg-surface);
    .ant-form-item {
      margin-bottom: 0;
      .ant-select {
        width: 100%;
        min-width: 0;
      }
    }
  }

  .current-volume-info {
    margin-bottom: 16px;
  }

  .no-volume-selected {
    text-align: center;
    padding: 40px 20px;
  }
}
</style>
