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
  <a-spin :spinning="loading">
    <StorageSnapshotSafety v-if="snapshotSafetyTarget" compact :context="snapshotSafetyContext" :loading="snapshotSafetyLoading" :error="snapshotSafetyError" :pending="snapshotSafetyPending" @refresh="refreshSnapshotSafety" />
    <div v-if="!snapshotSafetyTarget && !isNormalUserOrProject && !submitHandler">
      <ownership-selection @fetch-owner="fetchOwnerOptions" />
    </div>
    <a-form
      class="form"
      :class="{ 'snapshot-volume-form': snapshotSafetyTarget }"
      layout="vertical"
      :ref="formRef"
      :model="form"
      :rules="rules"
      @finish="handleSubmit"
      v-ctrl-enter="handleSubmit"
     >
      <a-form-item ref="name" name="name">
        <template #label>
          <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
        </template>
        <a-input
          v-focus="true"
          v-model:value="form.name"
          :placeholder="snapshotSafetyTarget ? $t('label.snapshot.volume.name.placeholder') : apiParams.name.description" />
      </a-form-item>
      <a-form-item ref="zoneid" name="zoneid" v-if="!createVolumeFromVM">
        <template #label>
          <tooltip-label :title="$t('label.zoneid')" :tooltip="apiParams.zoneid.description"/>
        </template>
        <a-select
          v-model:value="form.zoneid"
          :loading="loading"
          @change="zone => fetchDiskOfferings(zone)"
          :placeholder="apiParams.zoneid.description"
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }" >
          <a-select-option
            v-for="(zone, index) in zones"
            :value="zone.id"
            :key="index"
            :label="zone.name">
            <span>
              <resource-icon v-if="zone.icon" :image="zone.icon.base64image" size="1x" style="margin-right: 5px"/>
              <global-outlined v-else style="margin-right: 5px"/>
              {{ zone.name }}
            </span>
          </a-select-option>
        </a-select>
      </a-form-item>
      <div v-if="snapshotSafetyTarget && !isNormalUserOrProject && !submitHandler" class="snapshot-volume-owner">
        <ownership-selection embedded @fetch-owner="fetchOwnerOptions" />
      </div>
      <a-form-item ref="diskofferingid" name="diskofferingid" v-if="!createVolumeFromSnapshot">
        <template #label>
          <tooltip-label :title="$t('label.diskofferingid')" :tooltip="apiParams.diskofferingid.description || 'Disk Offering'"/>
        </template>
        <a-select
          v-model:value="form.diskofferingid"
          :loading="loading"
          @change="id => onChangeDiskOffering(id)"
          :placeholder="apiParams.diskofferingid.description || $t('label.diskofferingid')"
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }" >
          <a-select-option
            v-for="(offering, index) in offerings"
            :value="offering.id"
            :key="index"
            :label="offering.displaytext || offering.name">
            {{ offering.displaytext || offering.name }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <div v-if="createVolumeFromSnapshot" class="snapshot-restore-contract" data-testid="snapshot-restore-contract" aria-live="polite">
        <template v-if="snapshotRestore">
          <dl>
            <div><dt>{{ $t('label.size') }}</dt><dd>{{ snapshotRestore.sizeGiB }} GiB</dd></div>
            <div><dt>{{ $t('label.diskofferingid') }}</dt><dd>{{ snapshotRestore.diskofferingname }}</dd></div>
          </dl>
          <p>{{ $t('message.snapshot.volume.inheritance') }}</p>
        </template>
        <a-alert v-else-if="!snapshotSafetyLoading" type="error" show-icon :message="$t('message.snapshot.volume.inheritance.unavailable')">
          <template #description><a-button type="link" size="small" @click="refreshSnapshotSafety">{{ $t('label.retry') }}</a-button></template>
        </a-alert>
      </div>
      <AllocationSummary v-if="selectedDiskOffering" :resource="selectedDiskOffering" compact />
      <p v-if="explicitAllocation" class="volume-storage-error">{{ $t('message.allocation.cost.' + explicitAllocation.policy) }}</p>
      <p v-if="explicitAllocation && !createOnStorage" class="volume-storage-error">{{ $t('message.allocation.target.pending') }}</p>
      <a-alert v-if="allocationError" type="error" show-icon :message="$t('message.allocation.capability.failed')" />
      <a-button v-if="allocationError" size="small" @click="queryAllocation">{{ $t('label.retry') }}</a-button>
      <a-alert v-if="createVolumeFromSnapshot && allocationBlocked" type="error" show-icon :message="$t('message.allocation.snapshot.unsupported')" />
      <span v-if="customDiskOffering">
        <a-form-item ref="size" name="size">
          <template #label>
            <tooltip-label :title="$t('label.sizegb')" :tooltip="apiParams.size.description"/>
          </template>
          <a-input
            v-model:value="form.size"
            :placeholder="apiParams.size.description"/>
        </a-form-item>
      </span>
      <span v-if="isCustomizedDiskIOps">
        <a-form-item ref="miniops" name="miniops">
          <template #label>
            <tooltip-label :title="$t('label.miniops')" :tooltip="apiParams.miniops.description"/>
          </template>
          <a-input
            v-model:value="form.miniops"
            :placeholder="apiParams.miniops.description"/>
        </a-form-item>
        <a-form-item ref="maxiops" name="maxiops">
          <template #label>
            <tooltip-label :title="$t('label.maxiops')" :tooltip="apiParams.maxiops.description"/>
          </template>
          <a-input
            v-model:value="form.maxiops"
            :placeholder="apiParams.maxiops.description"/>
        </a-form-item>
      </span>
      <span v-if="diskOfferingSupportsEncryption && kmsKeys !== null">
        <a-form-item ref="kmskeyid" name="kmskeyid">
          <template #label>
            <tooltip-label :title="$t('label.kms.key')" :tooltip="apiParams.kmskeyid.description"/>
          </template>
          <a-select
            v-model:value="form.kmskeyid"
            :loading="loadingKmsKeys"
            :placeholder="$t('label.select.kms.key.optional')"
            showSearch
            optionFilterProp="label"
            allowClear>
            <a-select-option
              v-for="key in kmsKeys"
              :key="key.id"
              :value="key.id"
              :label="key.name">
              {{ key.name }}
            </a-select-option>
          </a-select>
          <p style="color: gray; font-size: 12px; margin-top: 5px">
            {{ $t('message.kms.key.optional') }}
          </p>
        </a-form-item>
      </span>
      <a-form-item name="createOnStorage" ref="createOnStorage" v-if="showStoragePoolSelect">
        <template #label>
          <tooltip-label :title="$t('label.create.on.storage')" :tooltip="$t('label.create.volume.on.primary.storage')" />
        </template>
        <a-switch
          v-model:checked="form.createOnStorage"
          :checked="createOnStorage"
          @change="onChangeCreateOnStorage" />
      </a-form-item>
      <span v-if="showStoragePoolSelect && createOnStorage">
        <a-form-item ref="storageid" name="storageid">
          <template #label>
            <tooltip-label :title="$t('label.storageid')" />
          </template>
          <a-select
            v-model:value="form.storageid"
            :loading="storageLoading"
            :options="storagePoolOptions"
            :disabled="storageLoading || storageFetchError || !selectedDiskOffering"
            :virtual="false"
            dropdown-class-name="volume-storage-pool-dropdown"
            option-label-prop="label"
            showSearch
            optionFilterProp="label"
            :not-found-content="$t(storageLoading ? 'message.volume.storage.loading' : storageFetchError ? 'message.volume.storage.failed' : 'message.volume.storage.empty')"
            :filterOption="(input, option) => option.label.toLowerCase().includes(input.toLowerCase())">
            <template #option="{ pool }">
              <div class="volume-storage-option">
                <span class="volume-storage-name" :title="pool.name">
                  <resource-icon v-if="pool.icon" :image="pool.icon.base64image" size="1x" />
                  <hdd-outlined v-else />
                  <span>{{ pool.name }}</span>
                </span>
                <storage-pool-capacity :pool="pool" />
              </div>
            </template>
            <template #optionLabel="{ pool, label }">
              <span v-if="pool" class="volume-storage-name" :title="pool.name">
                <resource-icon v-if="pool.icon" :image="pool.icon.base64image" size="1x" />
                <hdd-outlined v-else />
                <span>{{ pool.name }}</span>
              </span>
              <span v-else>{{ label }}</span>
            </template>
          </a-select>
          <storage-pool-capacity v-if="selectedStoragePool" :pool="selectedStoragePool" summary />
          <p v-if="hasRuleStoragePools" class="volume-storage-error">{{ $t('message.volume.storage.rule.automatic') }}</p>
          <p v-if="storageFetchError" class="volume-storage-error">{{ $t('message.volume.storage.failed') }}</p>
        </a-form-item>
      </span>
      <a-form-item name="attachVolume" ref="attachVolume" v-if="!createVolumeFromVM">
        <template v-if="!snapshotSafetyTarget" #label>
          <tooltip-label :title="$t('label.action.attach.to.instance')" :tooltip="$t('label.attach.vol.to.instance')" />
        </template>
        <label v-if="snapshotSafetyTarget" class="snapshot-volume-attach">
          <a-switch v-model:checked="form.attachVolume" :checked="attachVolume" :aria-label="$t('label.snapshot.volume.attach')" @change="zone => onChangeAttachToVM(zone.id)" />
          <span>{{ $t('label.snapshot.volume.attach') }}</span>
        </label>
        <a-switch v-else v-model:checked="form.attachVolume" :checked="attachVolume" @change="zone => onChangeAttachToVM(zone.id)" />
      </a-form-item>
      <span v-if="attachVolume">
        <a-form-item :label="$t(snapshotSafetyTarget ? 'label.virtualmachine' : 'label.virtualmachineid')" name="virtualmachineid" ref="virtualmachineid">
          <a-select
            v-focus="true"
            v-model:value="form.virtualmachineid"
            :placeholder="snapshotSafetyTarget ? $t('label.snapshot.volume.select.vm') : attachVolumeApiParams.virtualmachineid.description"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option v-for="vm in virtualmachines" :key="vm.id" :label="vm.name || vm.displayname">
              {{ vm.name || vm.displayname }}
            </a-select-option>
          </a-select>
        </a-form-item >
        <a-form-item ref="deviceid" name="deviceid" :label="$t('label.deviceid')" :extra="$t('message.vmvolume.device.auto')" :rules="[{ validator: validateDeviceId }]">
          <div v-if="!snapshotSafetyTarget" style="margin-bottom: 10px">
            <a-collapse>
              <a-collapse-panel header="More information about deviceID">
                <a-alert type="warning">
                  <template #message>
                    <span v-html="attachVolumeApiParams.deviceid.description" />
                  </template>
                </a-alert>
              </a-collapse-panel>
            </a-collapse>
          </div>
          <a-input-number
            v-model:value="form.deviceid"
            style="width: 100%;"
            :min="1"
            :precision="0"
            :placeholder="$t('label.vmvolume.device.auto')"
          />
        </a-form-item>
      </span>
      <a-form-item v-if="submitHandler" class="volume-device-hint" ref="deviceid" name="deviceid" :label="$t('label.vmvolume.deviceid')" :extra="$t('message.vmvolume.device.auto')" :rules="[{ validator: validateDeviceId }]">
        <a-input-number v-model:value="form.deviceid" :min="1" :precision="0" :placeholder="$t('label.vmvolume.device.auto')" style="width: 100%" />
      </a-form-item>
      <MoldDialogActions v-if="!hideActions" :span="24" class="" :busy="loading" @keyup.ctrl.enter="snapshotSafetyTarget && handleSubmit($event)">
        <a-button :disabled="loading" @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" ref="submit" :loading="loading" :disabled="snapshotSafetyBlocked || snapshotRestoreBlocked || allocationBlocked || (createOnStorage && (storageLoading || storageFetchError || !selectedStoragePool))" @click="handleSubmit">{{ $t(snapshotSafetyTarget ? 'label.create' : 'label.ok') }}</a-button>
      </MoldDialogActions>
    </a-form>
  </a-spin>
</template>

<script>
import { submitSnapshotOperation } from '@/utils/storageSnapshotOperations'
import { snapshotSecurityScope, copyTarget } from '@/utils/storageSnapshotContext'
import { storageSnapshotSafety } from '@/utils/storageSnapshotSafety'
import storageDialogContext from '@/utils/storageDialogContext'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { ref, reactive, toRaw } from 'vue'
import { postAPI } from '@/api'
import { getAPI } from '@/utils/storageApi'
import { volumeDeviceIdReason } from '@/utils/vmVolumeActions'
import { offeringStoragePools, readStoragePools, storageTags } from '@/utils/volumeStoragePools'
import { mixinForm } from '@/utils/mixin'
import { isAdmin } from '@/role'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import OwnershipSelection from '@/views/compute/wizard/OwnershipSelection.vue'
import StoragePoolCapacity from '@/views/storage/StoragePoolCapacity.vue'
import store from '@/store'
import AllocationSummary from '@/components/view/AllocationSummary'
import { snapshotRestoreInheritance, pureSnapshotRestoreParams } from '@/utils/snapshotRestore'
import { allocationIntent, poolAcceptsIntent, readAllocationCapability, readAllocationList } from '@/utils/volumeAllocation'

export default {
  name: 'CreateVolume',
  mixins: [storageDialogContext, storageSnapshotSafety('createVolume', 'snapshot', vm => !!vm.resource?.intervaltype), mixinForm],
  components: {
    MoldDialogActions,
    AllocationSummary,
    OwnershipSelection,
    ResourceIcon,
    TooltipLabel,
    StoragePoolCapacity
  },
  props: {
    hideActions: { type: Boolean, default: false },
    submitHandler: { type: Function, default: null },
    resource: {
      type: Object,
      default: () => {}
    }
  },
  data () {
    return {
      form: {},
      owner: {
        projectid: store.getters.project?.id,
        domainid: store.getters.project?.id ? null : store.getters.userInfo.domainid,
        account: store.getters.project?.id ? null : store.getters.userInfo.account
      },
      snapshotZoneIds: [],
      allocationLoading: false,
      allocationError: false,
      allocationCap: null,
      allocationRequestId: 0,
      snapshotAllocation: null,
      snapshotAllocationError: false,
      snapshotAllocationLoading: false,
      zones: [],
      offerings: [],
      customDiskOffering: false,
      loading: false,
      isCustomizedDiskIOps: false,
      virtualmachines: [],
      createOnStorage: false,
      storagePools: [],
      storageLoading: false,
      storageFetchError: false,
      storageRequestId: 0,
      offeringRequestId: 0,
      attachVolume: false,
      vmidtoattach: null,
      kmsKeys: [],
      loadingKmsKeys: false,
      kmsKeysZoneId: null
    }
  },
  computed: {
    snapshotRestore () { return snapshotRestoreInheritance(this.snapshotSafetyContext) },
    snapshotRestoreBlocked () { return this.createVolumeFromSnapshot && !this.snapshotRestore },
    explicitAllocation () { return allocationIntent(this.selectedDiskOffering) },
    allocationBlocked () {
      return (this.createOnStorage && !!this.explicitAllocation && (this.allocationLoading || this.allocationError || !this.allocationCap?.policies[this.explicitAllocation.policy])) ||
        (this.createVolumeFromSnapshot && (this.snapshotAllocationLoading || (this.snapshotAllocationError && this.snapshotSafetyContext?.source.sourceavailable !== false) || !!this.explicitAllocation || !!allocationIntent(this.snapshotAllocation)))
    },
    eligibleStoragePools () { return offeringStoragePools(this.storagePools, this.selectedDiskOffering).filter(pool => poolAcceptsIntent(pool, this.selectedDiskOffering)) },
    hasRuleStoragePools () { return storageTags(this.selectedDiskOffering?.tags).length > 0 && this.storagePools.some(pool => pool.state === 'Up' && pool.istagarule) },
    storagePoolOptions () {
      return this.eligibleStoragePools.map(pool => ({ value: pool.id, label: pool.name, title: pool.name, pool }))
    },
    selectedStoragePool () {
      return this.eligibleStoragePools.find(pool => pool.id === this.form.storageid)
    },
    selectedDiskOffering () {
      if (!this.form.diskofferingid || !this.offerings.length) return null
      return this.offerings.find(o => o.id === this.form.diskofferingid) || null
    },
    diskOfferingSupportsEncryption () {
      return this.selectedDiskOffering?.encrypt === true
    },
    showStoragePoolSelect () {
      return isAdmin() && !this.createVolumeFromSnapshot
    },
    createVolumeFromVM () {
      return this.$route.path.startsWith('/vm/')
    },
    isNormalUserOrProject () {
      return ['User'].includes(this.$store.getters.userInfo.roletype) || store.getters.project?.id
    },
    createVolumeFromSnapshot () {
      return this.$route.path.startsWith('/snapshot')
    }
  },
  watch: {
    'form.diskofferingid' () { this.reconcileStoragePool(); this.queryAllocation() },
    'form.storageid' () { this.queryAllocation() },
    'form.zoneid' () {
      this.invalidateStoragePools()
      if (this.createOnStorage) this.fetchStoragePools(this.form.zoneid)
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createVolume')
  },
  created () {
    if (this.submitHandler) this.owner = { account: this.resource.account, domainid: this.resource.domainid, projectid: this.resource.projectid }
    this.initForm()
    this.fetchData()
  },
  beforeUnmount () { this.storageRequestId++; this.offeringRequestId++; this.allocationRequestId++ },
  methods: {
    async queryAllocation () {
      const id = ++this.allocationRequestId
      this.allocationCap = null; this.allocationError = false; this.allocationLoading = false
      if (!this.createOnStorage || !this.explicitAllocation || !this.selectedStoragePool) return
      this.allocationLoading = true
      const current = () => id === this.allocationRequestId
      try {
        const cap = await readAllocationCapability(getAPI, this.selectedStoragePool, this.explicitAllocation.format, 'create', current)
        if (!current()) return
        this.allocationCap = cap
        if (!cap?.policies[this.explicitAllocation.policy]) this.allocationError = true
      } catch (e) { if (current()) this.allocationError = true } finally { if (current()) this.allocationLoading = false }
    },
    validateDeviceId (rule, value) {
      const reason = volumeDeviceIdReason(value)
      return reason ? Promise.reject(new Error(this.$t(reason))) : Promise.resolve()
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        zoneid: [{ required: true, message: this.$t('message.error.zone') }],
        size: [{ required: true, message: this.$t('message.error.custom.disk.size') }],
        miniops: [{
          validator: async (rule, value) => {
            if (value && (isNaN(value) || value <= 0)) {
              return Promise.reject(this.$t('message.error.number'))
            }
            return Promise.resolve()
          }
        }],
        maxiops: [{
          validator: async (rule, value) => {
            if (value && (isNaN(value) || value <= 0)) {
              return Promise.reject(this.$t('message.error.number'))
            }
            return Promise.resolve()
          }
        }]
      })
      if (this.attachVolume) {
        this.rules.virtualmachineid = [{ required: true, message: this.$t('message.error.select') }]
        this.rules.deviceid = [{ required: true, message: this.$t('message.error.select') }]
      }
      if (!this.createVolumeFromSnapshot) {
        this.rules.name = [{ required: true, message: this.$t('message.error.volume.name') }]
        this.rules.diskofferingid = [{ required: true, message: this.$t('message.error.select') }]
      }
    },
    fetchOwnerOptions (OwnerOptions) {
      this.owner = {}
      if (OwnerOptions.selectedAccountType === 'Account') {
        if (!OwnerOptions.selectedAccount) {
          return
        }
        this.owner.account = OwnerOptions.selectedAccount
        this.owner.domainid = OwnerOptions.selectedDomain
      } else if (OwnerOptions.selectedAccountType === 'Project') {
        if (!OwnerOptions.selectedProject) {
          return
        }
        this.owner.projectid = OwnerOptions.selectedProject
      }
      if (OwnerOptions.initialized) {
        this.fetchData()
      }
    },
    fetchData () {
      if (this.createVolumeFromSnapshot) {
        this.fetchSnapshotZones()
        return
      }
      let zoneId = null
      if (this.createVolumeFromVM) {
        zoneId = this.resource.zoneid
      }
      this.fetchZones(zoneId)
    },
    fetchZones (id) {
      this.loading = true
      const params = { showicon: true }
      if (Array.isArray(id)) {
        params.ids = id.join()
      } else if (id !== null) {
        params.id = id
      }
      getAPI('listZones', params, { preserveOnFailure: true }).then(json => {
        this.zones = json.listzonesresponse.zone || []
        this.form.zoneid = this.zones[0]?.id || ''
        this.fetchDiskOfferings(this.form.zoneid)
        if (this.createOnStorage) {
          // The zone watcher owns storage requests.
        }
        if (this.attachVolume) {
          this.fetchVirtualMachines(this.form.zoneid)
        }
      }).finally(() => {
        this.loading = false
      })
    },
    fetchSnapshotZones () {
      this.snapshotAllocationLoading = true
      getAPI('listVolumes', { id: this.resource.volumeid, listall: true }, { preserveOnFailure: true }).then(json => {
        this.snapshotAllocation = json.listvolumesresponse?.volume?.[0] || null
        this.snapshotAllocationError = !this.snapshotAllocation
      }).catch(() => { this.snapshotAllocationError = true }).finally(() => { this.snapshotAllocationLoading = false })
      this.loading = true
      this.snapshotZoneIds = []
      const params = {
        showunique: false,
        id: this.resource.id
      }
      getAPI('listSnapshots', params, { preserveOnFailure: true }).then(json => {
        const snapshots = json.listsnapshotsresponse.snapshot || []
        for (const snapshot of snapshots) {
          if (!this.snapshotZoneIds.includes(snapshot.zoneid)) {
            this.snapshotZoneIds.push(snapshot.zoneid)
          }
        }
      }).finally(() => {
        if (this.snapshotZoneIds && this.snapshotZoneIds.length > 0) {
          this.fetchZones(this.snapshotZoneIds)
        }
      })
    },
    fetchDiskOfferings (zoneId) {
      const sequence = ++this.offeringRequestId
      if (this.createVolumeFromSnapshot) {
        this.offerings = []
        this.customDiskOffering = false
        this.isCustomizedDiskIOps = false
        for (const key of ['diskofferingid', 'size', 'miniops', 'maxiops', 'kmskeyid']) delete this.form[key]
        this.loading = false
        return Promise.resolve()
      }
      this.offerings = []
      this.form.diskofferingid = undefined
      this.form.storageid = undefined
      if (zoneId !== this.kmsKeysZoneId) {
        this.kmsKeys = []
        this.kmsKeysZoneId = null
        this.form.kmskeyid = undefined
      }
      this.loading = true
      var params = {
        zoneid: zoneId,
        listall: true,
        domainid: this.owner.domainid
      }
      if (this.createVolumeFromVM) {
        params.virtualmachineid = this.resource.id
      }
      if (this.owner.projectid) {
        params.projectid = this.owner.projectid
      } else {
        params.account = this.owner.account
      }
      return readAllocationList(getAPI, 'listDiskOfferings', 'diskoffering', params, () => sequence === this.offeringRequestId && zoneId === this.form.zoneid).then(rows => {
        if (sequence !== this.offeringRequestId || zoneId !== this.form.zoneid) return
        this.offerings = rows || []
        if (this.createVolumeFromVM) {
          this.offerings = this.offerings.filter(x => x.suitableforvirtualmachine)
        }
        if (!this.createVolumeFromSnapshot) {
          this.form.diskofferingid = this.offerings[0]?.id || ''
        }
        this.customDiskOffering = !this.createVolumeFromSnapshot && (this.offerings[0]?.iscustomized || false)
        this.isCustomizedDiskIOps = !this.createVolumeFromSnapshot && (this.offerings[0]?.iscustomizediops || false)
        if (this.offerings[0]?.encrypt) {
          this.fetchKmsKeys()
        } else {
          this.form.kmskeyid = undefined
          this.kmsKeys = []
        }
      }).catch(error => {
        if (sequence === this.offeringRequestId) this.$notifyError(error)
      }).finally(() => {
        if (sequence === this.offeringRequestId) this.loading = false
      })
    },
    invalidateStoragePools () {
      this.allocationRequestId++
      this.allocationCap = null; this.allocationError = false; this.allocationLoading = false
      this.storageRequestId++
      this.storagePools = []
      this.form.storageid = undefined
      this.storageLoading = false
      this.storageFetchError = false
    },
    reconcileStoragePool () {
      if (!this.selectedStoragePool) this.form.storageid = undefined
    },
    async fetchStoragePools (zoneId) {
      const sequence = ++this.storageRequestId
      this.storagePools = []
      this.form.storageid = undefined
      this.storageFetchError = false
      this.storageLoading = !!zoneId
      if (!zoneId) return
      try {
        const pools = await readStoragePools(getAPI, zoneId, () => sequence === this.storageRequestId && this.form.zoneid === zoneId && this.createOnStorage)
        if (pools === null) return
        this.storagePools = pools
        this.reconcileStoragePool()
      } catch (error) {
        if (sequence !== this.storageRequestId) return
        this.storageFetchError = true
        this.$notifyError(error)
      } finally {
        if (sequence === this.storageRequestId) this.storageLoading = false
      }
    },
    fetchVirtualMachines (zoneId) {
      var params = {
        zoneid: zoneId,
        details: 'min'
      }
      if (this.owner.projectid) {
        params.projectid = this.owner.projectid
      } else {
        params.account = this.owner.account
        params.domainid = this.owner.domainid
      }

      this.loading = true
      var vmStates = ['Running', 'Stopped']
      vmStates.forEach((state) => {
        params.state = state
        getAPI('listVirtualMachines', params, { preserveOnFailure: true }).then(response => {
          this.virtualmachines = this.virtualmachines.concat(response.listvirtualmachinesresponse.virtualmachine || [])
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      })
    },
    handleSubmit (e) {
      if (this.loading || this.snapshotSafetyBlocked || this.snapshotRestoreBlocked || this.allocationBlocked || (this.createOnStorage && this.storageLoading)) return
      this.formRef.value.validate().then(async () => {
        if (this.loading || (this.dialogCurrent && !this.dialogCurrent())) return
        if (this.createOnStorage && (this.storageFetchError || this.storageLoading || !this.selectedStoragePool)) {
          throw new Error(this.$t(this.storageFetchError ? 'message.volume.storage.failed' : 'message.volume.storage.select.matching'))
        }
        if (this.allocationBlocked) throw new Error(this.$t('message.allocation.capability.failed'))
        const formRaw = toRaw(this.form)
        let values = this.handleRemoveFields(formRaw)
        if (this.createVolumeFromVM) {
          values.account = this.resource.account
          values.domainid = this.resource.domainid
          values.virtualmachineid = this.resource.id
          values.zoneid = this.resource.zoneid
        }
        if (this.customDiskOffering) {
          values.size = values.size.trim()
        }
        delete values.createOnStorage
        if (!this.createOnStorage) delete values.storageid
        if (this.createVolumeFromSnapshot) {
          values = pureSnapshotRestoreParams(values, this.resource.id)
        }
        if (this.attachVolume) {
          this.vmidtoattach = values.virtualmachineid
          values.virtualmachineid = null
        }
        if (!this.diskOfferingSupportsEncryption && 'kmskeyid' in values) {
          delete values.kmskeyid
        }
        values.domainid = this.owner.domainid
        if (this.owner.projectid) {
          values.projectid = this.owner.projectid
        } else {
          values.account = this.owner.account
        }
        if (this.submitHandler) return this.submitHandler(values)
        const attachment = (this.createVolumeFromVM || this.attachVolume)
          ? {
            virtualmachineid: this.createVolumeFromVM ? this.resource.id : this.vmidtoattach,
            projectid: values.projectid || '-1',
            ...(!this.createVolumeFromVM ? { deviceid: values.deviceid } : {})
          }
          : null
        delete values.deviceid
        this.loading = true
        if (this.snapshotSafetyTarget) {
          try {
            const capturedAttachment = attachment ? copyTarget(attachment) : null
            const store = this.$store; const security = snapshotSecurityScope(store)
            const current = () => this.dialogCurrent() && snapshotSecurityScope(store) === security
            const validateAttachment = async () => {
              if (!capturedAttachment) return
              const allowed = (await getAPI('listApis', { name: 'attachVolume' }, { preserveOnFailure: true })).listapisresponse?.api || []
              const machines = (await getAPI('listVirtualMachines', { id: capturedAttachment.virtualmachineid, listall: true }, { preserveOnFailure: true })).listvirtualmachinesresponse?.virtualmachine || []
              const vm = machines[0]
              if (!current() || !allowed.some(api => api.name === 'attachVolume') || machines.length !== 1 || vm.id !== capturedAttachment.virtualmachineid || !['Running', 'Stopped'].includes(vm.state) || vm.zoneid !== values.zoneid || (vm.projectid || '') !== (values.projectid || '') || (!values.projectid && (vm.account !== values.account || vm.domainid !== values.domainid))) throw new Error(this.$t('message.snapshot.action.changed'))
            }
            const operation = await this.submitSnapshotSafely(values, validateAttachment)
            let result
            if (operation.jobid) {
              result = await operation.completion
              if (!current()) return
              if (operation.status !== 'complete') {
                this.snapshotSafetyError = this.$t(operation.error || 'message.snapshot.action.failed')
                return
              }
            }
            if (capturedAttachment && operation.jobid) {
              const body = result.jobresult?.createvolumeresponse || result.jobresult
              const created = body.volume
              await submitSnapshotOperation({
                api: 'attachVolume',
                scope: security,
                target: { id: created.id, name: created.name },
                keys: ['volume:' + created.id, 'vm:' + capturedAttachment.virtualmachineid],
                current,
                securityCurrent: () => snapshotSecurityScope(store) === security,
                validate: async () => { await validateAttachment(); return { ...capturedAttachment, id: created.id } }
              })
            }
            if (operation.jobid && current()) this.closeModal()
          } finally { this.loading = false }
          return
        }
        postAPI('createVolume', values).then(response => {
          this.$pollJob({
            jobId: response.createvolumeresponse.jobid,
            title: this.$t('message.success.create.volume'),
            description: values.name,
            successMessage: this.$t('message.success.create.volume'),
            successMethod: (result) => {
              if (attachment) {
                postAPI('attachVolume', { ...attachment, id: result.jobresult.volume.id }).then(response => {
                  this.$pollJob({
                    jobId: response.attachvolumeresponse.jobid,
                    title: this.$t('message.attach.volume.success'),
                    description: values.name,
                    successMessage: this.$t('message.attach.volume.success'),
                    errorMessage: this.$t('message.attach.volume.failed'),
                    loadingMessage: this.$t('message.attach.volume.progress'),
                    catchMessage: this.$t('error.fetching.async.job.result')
                  })
                })
              }
            },
            errorMessage: this.$t('message.create.volume.failed'),
            loadingMessage: this.$t('message.create.volume.processing'),
            catchMessage: this.$t('error.fetching.async.job.result')
          })
          this.closeModal()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }).catch((error) => {
        if (error.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
        else this.$notifyError(error)
      })
    },
    closeModal () {
      if (this.dialogCurrent && !this.dialogCurrent()) return
      this.$emit('close-action')
    },
    onChangeDiskOffering (id) {
      this.form.diskofferingid = id
      this.reconcileStoragePool()
      const offering = this.offerings.filter(x => x.id === id)
      this.customDiskOffering = offering[0]?.iscustomized || false
      this.isCustomizedDiskIOps = offering[0]?.iscustomizediops || false
      if (offering[0]?.encrypt) {
        this.fetchKmsKeys()
      } else {
        this.form.kmskeyid = undefined
      }
    },
    fetchKmsKeys () {
      const zoneId = this.form.zoneid || (this.createVolumeFromVM && this.resource?.zoneid)
      if (!zoneId) return
      if (zoneId === this.kmsKeysZoneId) return
      this.kmsKeysZoneId = zoneId
      this.loadingKmsKeys = true
      this.kmsKeys = []
      const params = {
        zoneid: zoneId,
        account: this.owner.account,
        domainid: this.owner.domainid,
        projectid: this.owner.projectid,
        purpose: 'volume'
      }
      getAPI('listKMSKeys', params, { preserveOnFailure: true }).then(response => {
        const kmskeyMap = response.listkmskeysresponse.kmskey || []
        if (kmskeyMap.length > 0) {
          this.kmsKeys = kmskeyMap
        } else {
          this.kmsKeys = null
        }
      }).catch(() => {
        this.kmsKeys = null
      }).finally(() => {
        this.loadingKmsKeys = false
      })
    },
    onChangeAttachToVM (zone) {
      this.attachVolume = !this.attachVolume
      this.virtualmachines = []
      if (this.attachVolume) {
        this.rules.virtualmachineid = [{ required: true, message: this.$t('message.error.select') }]
        this.rules.deviceid = [{ validator: this.validateDeviceId }]
        this.attachVolumeApiParams = this.$getApiParams('attachVolume')
        this.fetchVirtualMachines(this.form.zoneid)
      } else {
        delete this.rules.virtualmachineid
        delete this.rules.deviceid
        if (this.formRef?.value?.clearValidate) this.formRef.value.clearValidate(['virtualmachineid', 'deviceid'])
      }
    },
    onChangeCreateOnStorage (checked) {
      this.createOnStorage = checked
      this.form.createOnStorage = checked
      this.invalidateStoragePools()
      if (checked) this.fetchStoragePools(this.form.zoneid)
    }
  }
}
</script>

<style lang="scss" scoped>
.volume-storage-name {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  max-width: 100%;
  min-width: 0;
  vertical-align: middle;
}
.volume-storage-name > span:last-child {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.volume-storage-name > .anticon { flex-shrink: 0; }
.volume-storage-option .volume-storage-name { margin-bottom: 7px; }
.volume-storage-error {
  margin: 8px 0 0;
  color: var(--ui-warning-text);
  font-size: 12px;
}
:global(.volume-storage-pool-dropdown .ant-select-item-option-content) {
  white-space: normal;
}
:global(.volume-storage-pool-dropdown .ant-select-item-option) {
  align-items: flex-start;
  padding: 9px 12px;
}

.volume-device-hint :deep(.ant-form-item-extra) {
  margin-top: 8px;
  color: var(--ui-text-secondary);
}
.form {
  width: 80vw;

  @media (min-width: 500px) {
    min-width: 400px;
    width: 100%;
  }
}
.form.snapshot-volume-form { width: 100%; min-width: 0; }
.snapshot-volume-form > :deep(.ant-form-item),
.snapshot-volume-form > span > :deep(.ant-form-item) { margin-bottom: 24px; }
.snapshot-volume-attach { display: flex; align-items: center; gap: 8px; }
.snapshot-volume-attach :deep(.ant-switch) { flex-shrink: 0; }
.snapshot-restore-contract { margin-bottom: 24px; color: var(--ui-text-primary); }
.snapshot-restore-contract dl { margin: 0; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 2fr); gap: 16px; }
.snapshot-restore-contract dt { color: var(--ui-text-secondary); margin-bottom: 8px; }
.snapshot-restore-contract dd { margin: 0; overflow-wrap: anywhere; }
.snapshot-restore-contract p { margin: 12px 0 0; color: var(--ui-text-secondary); }
.snapshot-volume-owner { margin-bottom: 24px; }
.snapshot-volume-owner :deep(.ownership-selection-embedded) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 24px;
}
.snapshot-volume-owner :deep(.ant-form-item) { min-width: 0; margin: 0; }
.snapshot-volume-owner :deep(.ant-form-item:first-child) { grid-column: 1 / -1; }
.snapshot-volume-form :deep(.ant-form-item-label) { padding-bottom: 8px; }
@media (max-width: 600px) {
  .snapshot-volume-owner :deep(.ownership-selection-embedded) { grid-template-columns: minmax(0, 1fr); }
}
</style>
