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
    <div class="sharedfs-create-dialog">
    <a-collapse
      v-if="!isNormalUserOrProject"
      class="sharedfs-create-owner"
      :bordered="false"
      :defaultActiveKey="[]">
      <a-collapse-panel key="owner" :header="ownerPanelHeader">
        <ownership-selection @fetch-owner="fetchOwnerOptions" />
      </a-collapse-panel>
    </a-collapse>
    <a-form
      class="form"
      :ref="formRef"
      :model="form"
      :rules="rules"
      layout="vertical"
      @finish="handleSubmit"
      v-ctrl-enter="handleSubmit"
     >
      <a-alert
        class="section-alert"
        type="info"
        show-icon
        :message="$t('message.storage.service.create.dialog.summary')" />

      <div class="sharedfs-create-layout">
        <aside class="sharedfs-create-summary">
          <section class="summary-panel">
            <div class="summary-panel__title">{{ $t('label.storage.service.section.review') }}</div>
            <div class="summary-panel__services">
              <a-tag v-for="service in selectedServices" :key="service.value" color="blue">
                {{ service.label }}
              </a-tag>
              <span v-if="selectedServices.length === 0" class="summary-panel__empty">{{ $t('label.none') }}</span>
            </div>
            <dl class="review-list">
              <dt>{{ $t('label.storage.service.sharedfs.name') }}</dt>
              <dd>{{ form.name || '-' }}</dd>
              <dt>{{ $t('label.zoneid') }}</dt>
              <dd>{{ selectedZoneName }}</dd>
              <dt>{{ $t('label.networkid') }}</dt>
              <dd>{{ selectedNetworkName }}</dd>
              <dt>{{ $t('label.filesystem') }}</dt>
              <dd>{{ form.filesystem || '-' }}</dd>
              <dt>{{ $t('label.storage.service.smb.identity.mode') }}</dt>
              <dd>{{ isServiceSelected('SMB') ? smbIdentityModeLabel : '-' }}</dd>
              <dt>{{ $t('label.storage.service.existing.volume') }}</dt>
              <dd>{{ form.useexistingvolume ? $t('label.yes') : $t('label.no') }}</dd>
              <dt>{{ $t('label.storage.service.existing.volume.select') }}</dt>
              <dd>{{ form.useexistingvolume ? selectedExistingVolumeLabel : '-' }}</dd>
              <dt>{{ $t('label.storage.service.primary.storage') }}</dt>
              <dd>{{ form.useexistingvolume ? '-' : selectedStoragePoolLabel }}</dd>
              <dt>{{ $t('label.storage.service.import.mode') }}</dt>
              <dd>{{ form.useexistingvolume ? importModeLabel : '-' }}</dd>
              <dt>{{ $t('label.storage.service.backing.capacity') }}</dt>
              <dd>{{ backingCapacityLabel }}</dd>
              <dt>{{ $t('label.storage.service.data.disk.resize.allowed') }}</dt>
              <dd>{{ form.resizeallowed ? $t('label.yes') : $t('label.no') }}</dd>
            </dl>
            <div v-if="isServiceSelected('NFS')" class="review-service">
              <div class="review-service__title">NFS</div>
              <dl class="review-list">
                <dt>{{ $t('label.storage.service.nfs.export.name') }}</dt>
                <dd>{{ effectiveNfsName }}</dd>
                  <dt>{{ $t('label.storage.service.internal.path') }}</dt>
                  <dd>{{ form.nfspath || '-' }}</dd>
                  <dt>{{ $t('label.storage.service.nfs.protocol.mode') }}</dt>
                  <dd>{{ nfsProtocolModeLabel(form.nfsprotocolmode) }}</dd>
                <dt>{{ $t('label.storage.service.allowed.cidr') }}</dt>
                <dd>{{ form.nfsprincipal || $t('label.storage.service.nfs.acl.implicit.open') }}</dd>
                <dt>{{ $t('label.storage.service.permission') }}</dt>
                <dd>{{ storagePermissionLabel(form.nfspermission) }}</dd>
                <dt>{{ $t('label.storage.service.nfs.export.capacity.limit') }}</dt>
                <dd>{{ nfsQuotaLabel }}</dd>
              </dl>
            </div>
            <div v-if="isServiceSelected('SMB')" class="review-service">
              <div class="review-service__title">SMB</div>
              <dl class="review-list">
                <dt>{{ $t('label.storage.service.smb.share.name') }}</dt>
                <dd>{{ effectiveSmbName }}</dd>
                <dt>{{ $t('label.storage.service.internal.path') }}</dt>
                <dd>{{ form.smbpath || '-' }}</dd>
                <dt>{{ $t('label.storage.service.smb.identity.mode') }}</dt>
                <dd>{{ smbIdentityModeLabel }}</dd>
                <dt>{{ $t('label.storage.service.local.user') }}</dt>
                <dd>{{ form.smbidentitymode === 'LOCAL' ? (form.smblocalusername || '-') : '-' }}</dd>
                <dt>{{ $t('label.storage.service.permission') }}</dt>
                <dd>{{ form.smbidentitymode === 'LOCAL' ? storagePermissionLabel(form.smblocalpermission) : '-' }}</dd>
                <dt>{{ $t('label.storage.service.ad.domain') }}</dt>
                <dd>{{ form.smbidentitymode === 'AD' ? (form.smbaddomain || '-') : '-' }}</dd>
                <dt>{{ $t('label.storage.service.initial.smb.acl.type') }}</dt>
                <dd>{{ smbInitialAclTypeLabel }}</dd>
                <dt>{{ $t('label.storage.service.initial.smb.acl.principal') }}</dt>
                <dd>{{ smbInitialAclPrincipalLabel }}</dd>
                <dt>{{ $t('label.storage.service.smb.share.capacity.limit') }}</dt>
                <dd>{{ smbQuotaLabel }}</dd>
              </dl>
            </div>
            <div v-if="isServiceSelected('ISCSI')" class="review-service">
              <div class="review-service__title">iSCSI</div>
              <dl class="review-list">
                <dt>{{ $t('label.storage.service.target.iqn') }}</dt>
                <dd>{{ form.iscsitargetname || '-' }}</dd>
                <dt>{{ $t('label.storage.service.lun') }}</dt>
                <dd>{{ form.iscsilun || '0' }}</dd>
                <dt>{{ $t('label.storage.service.allowed.initiator.iqn') }}</dt>
                <dd>{{ form.iscsiinitiator || '-' }}</dd>
                <dt>{{ $t('label.storage.service.permission') }}</dt>
                <dd>{{ storagePermissionLabel(form.iscsipermission) }}</dd>
                <dt>{{ $t('label.storage.service.lun.size') }}</dt>
                <dd>{{ iscsiLunSizeLabel }}</dd>
                <dt>{{ $t('label.storage.service.chap.enabled') }}</dt>
                <dd>{{ form.iscsichapenabled ? $t('label.yes') : $t('label.no') }}</dd>
                <dt>{{ $t('label.storage.service.mutual.chap.enabled') }}</dt>
                <dd>{{ form.iscsichapenabled && form.iscsimutualchapenabled ? $t('label.yes') : $t('label.no') }}</dd>
              </dl>
            </div>
            <div v-if="isServiceSelected('NVME_OF')" class="review-service">
              <div class="review-service__title">NVMe-oF</div>
              <dl class="review-list">
                <dt>{{ $t('label.storage.service.engine') }}</dt>
                <dd>{{ nvmeEngineLabel }}</dd>
                <dt>{{ $t('label.storage.service.transport') }}</dt>
                <dd>{{ nvmeTransportLabel }}</dd>
                <dt>{{ $t('label.storage.service.protocol.port') }}</dt>
                <dd>{{ form.nvmeport || 4420 }}</dd>
                <dt>{{ $t('label.storage.service.subsystem.nqn') }}</dt>
                <dd>{{ form.nvmesubsystemnqn || '-' }}</dd>
                <dt>{{ $t('label.storage.service.namespace.size') }}</dt>
                <dd>{{ nvmeNamespaceSizeLabel }}</dd>
                <dt>{{ $t('label.storage.service.allowed.host.nqn') }}</dt>
                <dd>{{ form.nvmehostnqn || '-' }}</dd>
                <dt>{{ $t('label.storage.service.dhchap.enabled') }}</dt>
                <dd>{{ nvmeDhChapCreateSupported ? (form.nvmedhchapenabled ? $t('label.yes') : $t('label.no')) : $t('label.unsupported') }}</dd>
                <dt>{{ $t('label.storage.service.dhchap.controller.enabled') }}</dt>
                <dd>{{ nvmeDhChapCreateSupported ? (form.nvmedhchapenabled && form.nvmedhchapctrlenabled ? $t('label.yes') : $t('label.no')) : $t('label.unsupported') }}</dd>
              </dl>
            </div>
          </section>
        </aside>

        <main class="sharedfs-create-config">
      <a-collapse
        class="sharedfs-create-sections"
        :defaultActiveKey="['basic', 'volume', 'services']">
        <a-collapse-panel key="basic" :header="$t('label.storage.service.section.basic')">
          <a-row :gutter="16">
            <a-col :xs="24" :md="12">
              <a-form-item name="name" ref="name" required>
                <template #label>
                  <tooltip-label :title="$t('label.storage.service.sharedfs.name')" :tooltip="$t('message.storage.service.sharedfs.name.help')" />
                </template>
                <a-input v-model:value="form.name" v-focus="true" />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="12">
              <a-form-item name="description" ref="description">
                <template #label>
                  <tooltip-label :title="$t('label.description')" :tooltip="$t('message.storage.service.description.help')" />
                </template>
                <a-input v-model:value="form.description" />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="12">
              <a-form-item ref="zoneid" name="zoneid" required>
                <template #label>
                  <tooltip-label :title="$t('label.zoneid')" :tooltip="apiParams.zoneid.description"/>
                </template>
                <a-select
                  v-model:value="form.zoneid"
                  :loading="zoneLoading"
                  @change="handleZoneChange"
                  :placeholder="apiParams.zoneid.description"
                  showSearch
                  optionFilterProp="label"
                  :filterOption="filterOption" >
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
            </a-col>
            <a-col :xs="24" :md="12">
              <a-form-item ref="networkid" name="networkid" required>
                <template #label>
                  <tooltip-label :title="$t('label.networkid')" :tooltip="apiParams.networkid.description || $t('label.networkid')"/>
                </template>
                <a-select
                  v-model:value="form.networkid"
                  :loading="networkLoading"
                  :placeholder="apiParams.networkid.description || $t('label.networkid')"
                  showSearch
                  optionFilterProp="label"
                  :filterOption="filterOption" >
                  <a-select-option
                    v-for="(network, index) in networks"
                    :value="network.id"
                    :key="index"
                    :label="network.name"> {{ network.name }}
                  </a-select-option>
                </a-select>
              </a-form-item>
            </a-col>
            <a-col v-if="isSelectedNetworkL2" :span="24">
              <section class="sharedfs-network-settings">
                <a-row :gutter="[16, 0]">
                  <a-col :span="24">
                    <a-form-item ref="networkmode" name="networkmode" required>
                      <template #label>
                        <tooltip-label :title="$t('label.sharedfs.network.mode')" :tooltip="$t('message.sharedfs.network.mode')"/>
                      </template>
                      <a-radio-group v-model:value="form.networkmode" class="sharedfs-network-mode">
                        <a-radio value="DHCP" :disabled="!selectedNetworkSupportsUserData">{{ $t('label.dhcp') }}</a-radio>
                        <a-radio value="STATIC">{{ $t('label.static.ip') }}</a-radio>
                      </a-radio-group>
                    </a-form-item>
                  </a-col>
                  <a-col v-if="!selectedNetworkSupportsUserData" :span="24">
                    <a-alert
                      class="sharedfs-network-alert"
                      type="info"
                      show-icon
                      :message="$t('message.sharedfs.static.required.no.userdata')" />
                  </a-col>
                  <a-col v-if="isStaticNetwork" :span="24">
                    <a-form-item ref="ipcidr" name="ipcidr" required>
                      <template #label><tooltip-label :title="$t('label.sharedfs.ip.cidr')" :tooltip="$t('message.sharedfs.static.ip.cidr')"/></template>
                      <a-input v-model:value="form.ipcidr" placeholder="10.10.1.211/24" />
                    </a-form-item>
                  </a-col>
                  <a-col v-if="isStaticNetwork" :xs="24" :md="12">
                    <a-form-item ref="gateway" name="gateway">
                      <template #label><tooltip-label :title="$t('label.gateway')" :tooltip="$t('message.sharedfs.static.gateway')"/></template>
                      <a-input v-model:value="form.gateway" placeholder="10.10.1.1" />
                    </a-form-item>
                  </a-col>
                  <a-col v-if="isStaticNetwork" :xs="24" :md="12">
                    <a-form-item ref="dns1" name="dns1">
                      <template #label><tooltip-label :title="$t('label.dns1')" :tooltip="$t('message.sharedfs.static.dns')"/></template>
                      <a-input v-model:value="form.dns1" placeholder="10.10.1.10" />
                    </a-form-item>
                  </a-col>
                  <a-col v-if="isStaticNetwork" :xs="24" :md="12">
                    <a-form-item ref="dns2" name="dns2">
                      <template #label><tooltip-label :title="$t('label.dns2')" :tooltip="$t('message.sharedfs.static.dns.optional')"/></template>
                      <a-input v-model:value="form.dns2" placeholder="8.8.8.8" />
                    </a-form-item>
                  </a-col>
                </a-row>
              </section>
            </a-col>
            <a-col :xs="24" :md="12">
              <a-form-item ref="filesystem" name="filesystem">
                <template #label>
                  <tooltip-label :title="$t('label.filesystem')" :tooltip="apiParams.filesystem.description"/>
                </template>
                <a-select v-model:value="form.filesystem" showSearch optionFilterProp="label" :filterOption="filterOption">
                  <a-select-option value="XFS" label="XFS">XFS</a-select-option>
                  <a-select-option value="EXT4" label="EXT4">EXT4</a-select-option>
                </a-select>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="12">
              <a-form-item ref="serviceofferingid" name="serviceofferingid" required>
                <template #label>
                  <tooltip-label :title="$t('label.compute.offering.for.sharedfs.instance')" :tooltip="apiParams.serviceofferingid.description || $t('label.serviceofferingid')"/>
                </template>
                <a-select
                  v-model:value="form.serviceofferingid"
                  :loading="serviceofferingLoading"
                  :placeholder="$t('label.serviceofferingid')"
                  showSearch
                  optionFilterProp="label"
                  :filterOption="filterOption" >
                  <a-select-option
                    v-for="(serviceoffering, index) in serviceofferings"
                    :value="serviceoffering.id"
                    :key="index"
                    :label="serviceoffering.displaytext || serviceoffering.name">
                    {{ serviceoffering.displaytext || serviceoffering.name }}
                  </a-select-option>
                </a-select>
              </a-form-item>
            </a-col>
          </a-row>
        </a-collapse-panel>

        <a-collapse-panel key="volume" :header="$t('label.storage.service.section.volume.backing')">
          <a-row :gutter="16">
            <a-col :xs="24" :lg="12">
              <a-form-item name="useexistingvolume">
                <template #label>
                  <tooltip-label :title="$t('label.storage.service.volume.mode')" :tooltip="$t('message.storage.service.volume.mode.help')" />
                </template>
                <a-switch v-model:checked="form.useexistingvolume" @change="handleExistingVolumeToggle" />
                <div class="field-hint">{{ form.useexistingvolume ? $t('message.storage.service.volume.mode.existing') : $t('message.storage.service.volume.mode.new') }}</div>
              </a-form-item>
              <template v-if="form.useexistingvolume">
                <a-form-item name="existingvolumeid" required>
                  <template #label>
                    <tooltip-label :title="$t('label.storage.service.existing.volume.select')" :tooltip="$t('message.storage.service.existing.volume.select.help')" />
                  </template>
                  <a-select
                    v-model:value="form.existingvolumeid"
                    :loading="volumeLoading"
                    :placeholder="$t('message.storage.service.existing.volume.select')"
                    showSearch
                    optionFilterProp="label"
                    :filterOption="filterOption">
                    <a-select-option
                      v-for="volume in availableVolumes"
                      :key="volume.id"
                      :value="volume.id"
                      :label="formatVolumeOption(volume)">
                      {{ formatVolumeOption(volume) }}
                    </a-select-option>
                  </a-select>
                  <div class="field-hint">{{ $t('message.storage.service.existing.volume.only.unattached') }}</div>
                </a-form-item>
                <a-form-item :label="$t('label.storage.service.selected.volume.size')">
                  <a-input :value="selectedExistingVolumeSizeLabel" disabled />
                </a-form-item>
                <a-form-item name="importmode" required>
                  <template #label>
                    <tooltip-label :title="$t('label.storage.service.import.mode')" :tooltip="$t('message.storage.service.import.mode.help')" />
                  </template>
                  <a-select v-model:value="form.importmode">
                    <a-select-option value="INSPECT_ONLY">{{ $t('label.storage.service.import.inspect') }}</a-select-option>
                    <a-select-option value="MOUNT_EXISTING">{{ $t('label.storage.service.import.mount') }}</a-select-option>
                    <a-select-option value="FORMAT_NEW">{{ $t('label.storage.service.import.format') }}</a-select-option>
                  </a-select>
                </a-form-item>
                <a-alert
                  v-if="form.importmode === 'FORMAT_NEW'"
                  type="warning"
                  show-icon
                  :message="$t('message.storage.service.destructive.confirmation')" />
              </template>
            </a-col>
            <a-col :xs="24" :lg="12">
              <template v-if="!form.useexistingvolume">
                <a-form-item ref="diskofferingid" name="diskofferingid" required>
                  <template #label>
                    <tooltip-label :title="$t('label.diskofferingid')" :tooltip="apiParams.diskofferingid.description || $t('label.diskofferingid')"/>
                  </template>
                  <a-select
                    v-model:value="form.diskofferingid"
                    :loading="diskofferingLoading"
                    @change="handleDiskOfferingChange"
                    :placeholder="apiParams.diskofferingid.description || $t('label.diskofferingid')"
                    showSearch
                    optionFilterProp="label"
                    :filterOption="filterOption" >
                    <a-select-option
                      v-for="(diskoffering, index) in diskofferings"
                      :value="diskoffering.id"
                      :key="index"
                      :label="diskoffering.displaytext || diskoffering.name">
                      {{ diskoffering.displaytext || diskoffering.name }}
                    </a-select-option>
                  </a-select>
                </a-form-item>
                <a-form-item ref="storageid" name="storageid" required>
                  <template #label>
                    <tooltip-label :title="$t('label.storage.service.primary.storage')" :tooltip="$t('message.storage.service.primary.storage.help')"/>
                  </template>
                  <a-select
                    v-model:value="form.storageid"
                    :loading="storagePoolLoading"
                    :placeholder="$t('message.storage.service.primary.storage.select')"
                    showSearch
                    optionFilterProp="label"
                    :filterOption="filterOption" >
                    <a-select-option
                      v-for="pool in filteredStoragePools"
                      :value="pool.id"
                      :key="pool.id"
                      :label="formatStoragePoolOption(pool)">
                      {{ formatStoragePoolOption(pool) }}
                    </a-select-option>
                  </a-select>
                  <div v-if="selectedDiskOfferingTags.length" class="field-hint">
                    {{ $t('message.storage.service.primary.storage.tag.filtered', { tags: selectedDiskOfferingTags.join(', ') }) }}
                  </div>
                </a-form-item>
                <a-form-item v-if="customDiskOffering" ref="size" name="size" required>
                  <template #label>
                    <tooltip-label :title="$t('label.storage.service.data.disk.size.gib')" :tooltip="apiParams.size.description"/>
                  </template>
                  <a-input-number v-model:value="form.size" class="full-width-input" :min="1" :placeholder="apiParams.size.description"/>
                </a-form-item>
                <a-row v-if="isCustomizedDiskIOps" :gutter="12">
                  <a-col :xs="24" :md="12">
                    <a-form-item ref="miniops" name="miniops">
                      <template #label>
                        <tooltip-label :title="$t('label.miniops')" :tooltip="apiParams.miniops.description"/>
                      </template>
                      <a-input v-model:value="form.miniops" :placeholder="apiParams.miniops.description"/>
                    </a-form-item>
                  </a-col>
                  <a-col :xs="24" :md="12">
                    <a-form-item ref="maxiops" name="maxiops">
                      <template #label>
                        <tooltip-label :title="$t('label.maxiops')" :tooltip="apiParams.maxiops.description"/>
                      </template>
                      <a-input v-model:value="form.maxiops" :placeholder="apiParams.maxiops.description"/>
                    </a-form-item>
                  </a-col>
                </a-row>
              </template>
              <a-form-item name="resizeallowed">
                <template #label>
                  <tooltip-label :title="$t('label.storage.service.data.disk.resize.allowed')" :tooltip="$t('message.storage.service.data.disk.resize.allowed.help')" />
                </template>
                <a-switch v-model:checked="form.resizeallowed" />
                <div class="field-hint">{{ $t('message.storage.service.data.disk.resize.allowed.help') }}</div>
              </a-form-item>
            </a-col>
          </a-row>
        </a-collapse-panel>

        <a-collapse-panel key="services" :header="$t('label.storage.service.section.services')">
          <a-form-item name="services" required>
            <template #label>
              <tooltip-label :title="$t('label.storage.service.initial.services')" :tooltip="$t('message.storage.service.initial.services.help')" />
            </template>
            <a-checkbox-group v-model:value="form.services" class="service-selector">
              <a-checkbox v-for="service in serviceOptions" :key="service.value" :value="service.value">
                <span class="service-selector__title">{{ service.label }}</span>
                <span class="service-selector__description">{{ service.description }}</span>
              </a-checkbox>
            </a-checkbox-group>
          </a-form-item>
          <a-alert
            v-if="!hasStorageServiceApi"
            type="warning"
            show-icon
            :message="$t('message.storage.service.api.unavailable')" />
        </a-collapse-panel>

        <a-collapse-panel v-if="isServiceSelected('NFS')" key="nfs" header="NFS">
          <section class="service-section">
            <h4>{{ $t('label.storage.service.nfs.initial.export') }}</h4>
            <a-form-item name="nfsname">
              <template #label>
                <tooltip-label :title="$t('label.storage.service.nfs.export.name')" :tooltip="$t('message.storage.service.nfs.name.autogenerated')" />
              </template>
              <a-input v-model:value="form.nfsname" />
              <div class="field-hint">{{ $t('message.storage.service.nfs.name.autogenerated') }}</div>
            </a-form-item>
            <a-form-item name="nfspath" required>
              <template #label>
                <tooltip-label :title="$t('label.storage.service.internal.path')" :tooltip="$t('message.storage.service.nfs.internal.path.help')" />
              </template>
              <a-input v-model:value="form.nfspath" placeholder="/export/&lt;share-name&gt;" />
              <div class="field-hint">{{ $t('message.storage.service.nfs.internal.path.help') }}</div>
            </a-form-item>
            <a-form-item name="nfsquotaamount">
              <template #label>
                <tooltip-label :title="$t('label.storage.service.nfs.export.capacity.limit')" :tooltip="$t('message.storage.service.nfs.quota.help')" />
              </template>
              <div class="capacity-input-group">
                <a-input-number
                  v-model:value="form.nfsquotaamount"
                  :min="0"
                  :placeholder="$t('message.storage.service.capacity.limit.placeholder')" />
                <a-select v-model:value="form.nfsquotaunit">
                  <a-select-option v-for="unit in capacityUnits" :key="unit.value" :value="unit.value">{{ unit.label }}</a-select-option>
                </a-select>
              </div>
              <div class="field-hint">{{ $t('message.storage.service.nfs.quota.help') }}</div>
            </a-form-item>
            <a-form-item name="nfsprotocolmode">
              <template #label>
                <tooltip-label :title="$t('label.storage.service.nfs.protocol.mode')" :tooltip="$t('message.storage.service.nfs.protocol.mode.help')" />
              </template>
              <a-radio-group
                v-model:value="form.nfsprotocolmode"
                class="nfs-protocol-mode-radio"
                @change="form.nfsport = form.nfsprotocolmode === 'V3V4_DUAL' ? 2049 : form.nfsport">
                <a-radio value="V4_ONLY">{{ $t('label.storage.service.nfs.protocol.mode.v4only') }}</a-radio>
                <a-radio value="V3V4_DUAL">{{ $t('label.storage.service.nfs.protocol.mode.dual') }}</a-radio>
              </a-radio-group>
            </a-form-item>
            <a-alert
              v-if="form.nfsprotocolmode === 'V3V4_DUAL'"
              type="info"
              show-icon
              class="sharedfs-inline-alert"
              :message="$t('message.storage.service.nfs.dual.mode.export.exposure')" />
            <a-form-item name="nfsport" required>
              <template #label>
                <tooltip-label :title="$t('label.storage.service.protocol.port')" :tooltip="$t('message.storage.service.nfs.port.help')" />
              </template>
              <a-input-number v-model:value="form.nfsport" :min="1" :max="65535" class="full-width-input sharedfs-fixed-value" :disabled="form.nfsprotocolmode === 'V3V4_DUAL'" />
            </a-form-item>
            <a-row :gutter="12">
              <a-col :xs="24" :md="12">
                <a-form-item name="nfsprincipal">
                  <template #label>
                    <tooltip-label :title="$t('label.storage.service.allowed.cidr')" :tooltip="$t('message.storage.service.allowed.cidr.help')" />
                  </template>
                  <a-input v-model:value="form.nfsprincipal" :placeholder="$t('message.storage.service.nfs.acl.optional.placeholder')" />
                </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
                <a-form-item name="nfspermission" required>
                  <template #label>
                    <tooltip-label :title="$t('label.storage.service.permission')" :tooltip="$t('message.storage.service.permission.help')" />
                  </template>
                  <a-select v-model:value="form.nfspermission">
                    <a-select-option value="READ_WRITE">{{ $t('label.storage.service.permission.readwrite') }}</a-select-option>
                    <a-select-option value="READ_ONLY">{{ $t('label.storage.service.permission.readonly') }}</a-select-option>
                  </a-select>
                </a-form-item>
              </a-col>
            </a-row>
            <a-space wrap>
              <a-checkbox v-model:checked="form.nfsrootsquash">{{ $t('label.storage.service.root.squash') }}</a-checkbox>
              <a-checkbox v-model:checked="form.nfssync">{{ $t('label.storage.service.sync') }}</a-checkbox>
              <a-checkbox v-model:checked="form.nfssecure">{{ $t('label.storage.service.secure') }}</a-checkbox>
            </a-space>
          </section>
        </a-collapse-panel>

        <a-collapse-panel v-if="isServiceSelected('SMB')" key="smb" header="SMB">
          <section class="service-section">
            <h4>{{ $t('label.storage.service.smb.initial.share') }}</h4>
            <a-form-item name="smbname">
              <template #label>
                <tooltip-label :title="$t('label.storage.service.smb.share.name')" :tooltip="$t('message.storage.service.smb.name.autogenerated')" />
              </template>
              <a-input v-model:value="form.smbname" />
              <div class="field-hint">{{ $t('message.storage.service.smb.name.autogenerated') }}</div>
            </a-form-item>
            <a-form-item name="smbpath" required>
              <template #label>
                <tooltip-label :title="$t('label.storage.service.internal.path')" :tooltip="$t('message.storage.service.smb.internal.path.help')" />
              </template>
              <a-input v-model:value="form.smbpath" placeholder="/export/smb01" />
              <div class="field-hint">{{ $t('message.storage.service.smb.internal.path.help') }}</div>
            </a-form-item>
            <a-form-item name="smbquotaamount">
              <template #label>
                <tooltip-label :title="$t('label.storage.service.smb.share.capacity.limit')" :tooltip="$t('message.storage.service.smb.quota.help')" />
              </template>
              <div class="capacity-input-group">
                <a-input-number
                  v-model:value="form.smbquotaamount"
                  :min="0"
                  :placeholder="$t('message.storage.service.capacity.limit.placeholder')" />
                <a-select v-model:value="form.smbquotaunit">
                  <a-select-option v-for="unit in capacityUnits" :key="unit.value" :value="unit.value">{{ unit.label }}</a-select-option>
                </a-select>
              </div>
              <div class="field-hint">{{ $t('message.storage.service.smb.quota.help') }}</div>
            </a-form-item>
            <a-space wrap>
              <a-checkbox v-model:checked="form.smbbrowseable">{{ $t('label.storage.service.browseable') }}</a-checkbox>
              <a-checkbox v-model:checked="form.smbguestok">{{ $t('label.storage.service.guest.access') }}</a-checkbox>
              <a-checkbox v-model:checked="form.smbreadonly">{{ $t('label.storage.service.permission.readonly') }}</a-checkbox>
            </a-space>

            <div class="service-subsection">
              <h4>{{ $t('label.storage.service.section.smb.identity') }}</h4>
              <a-form-item name="smbidentitymode" required>
                <template #label>
                  <tooltip-label :title="$t('label.storage.service.smb.identity.mode')" :tooltip="$t('message.storage.service.smb.identity.mode.help')" />
                </template>
                <a-radio-group class="smb-identity-radio" v-model:value="form.smbidentitymode">
                  <a-radio value="LOCAL">{{ $t('label.storage.service.smb.identity.local') }}</a-radio>
                  <a-radio value="AD">{{ $t('label.storage.service.smb.identity.ad') }}</a-radio>
                </a-radio-group>
              </a-form-item>
              <a-alert
                class="section-alert"
                type="info"
                show-icon
                :message="$t('message.storage.service.smb.password.sensitive')" />
              <a-row v-if="form.smbidentitymode === 'LOCAL'" :gutter="16">
                <a-col :xs="24" :md="12">
                  <a-form-item name="smblocalusername" :required="form.smbidentitymode === 'LOCAL'">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.local.user')" :tooltip="$t('message.storage.service.smb.local.user.help')" />
                    </template>
                    <a-input v-model:value="form.smblocalusername" autocomplete="off" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="smblocalpermission" :required="form.smbidentitymode === 'LOCAL'">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.permission')" :tooltip="$t('message.storage.service.permission.help')" />
                    </template>
                    <a-select v-model:value="form.smblocalpermission">
                      <a-select-option value="READ_ONLY">{{ $t('label.storage.service.permission.readonly') }}</a-select-option>
                      <a-select-option value="READ_WRITE">{{ $t('label.storage.service.permission.readwrite') }}</a-select-option>
                      <a-select-option value="ADMIN">{{ $t('label.admin') }}</a-select-option>
                    </a-select>
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="smblocalpassword" :required="form.smbidentitymode === 'LOCAL'">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.local.user.password')" :tooltip="$t('message.storage.service.smb.local.password.help')" />
                    </template>
                    <a-input-password v-model:value="form.smblocalpassword" autocomplete="new-password" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="smblocalpasswordconfirm" :required="form.smbidentitymode === 'LOCAL'">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.local.user.password.confirm')" :tooltip="$t('message.storage.service.smb.local.password.confirm.help')" />
                    </template>
                    <a-input-password v-model:value="form.smblocalpasswordconfirm" autocomplete="new-password" />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-row v-if="form.smbidentitymode === 'AD'" :gutter="16">
                <a-col :xs="24" :md="12">
                  <a-form-item name="smbaddomain" :required="form.smbidentitymode === 'AD'">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.ad.domain')" :tooltip="$t('message.storage.service.ad.domain.help')" />
                    </template>
                    <a-input v-model:value="form.smbaddomain" placeholder="example.local" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="smbadusername" :required="form.smbidentitymode === 'AD'">
                    <template #label>
                      <tooltip-label :title="$t('label.username')" :tooltip="$t('message.storage.service.ad.username.help')" />
                    </template>
                    <a-input v-model:value="form.smbadusername" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="smbadpassword" :required="form.smbidentitymode === 'AD'">
                    <template #label>
                      <tooltip-label :title="$t('label.password')" :tooltip="$t('message.storage.service.ad.password.help')" />
                    </template>
                    <a-input-password v-model:value="form.smbadpassword" autocomplete="new-password" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="smbaddns">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.dns.servers')" :tooltip="$t('message.storage.service.dns.servers.help')" />
                    </template>
                    <a-input v-model:value="form.smbaddns" placeholder="10.0.0.10,10.0.0.11" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="smbadou">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.organizational.unit')" :tooltip="$t('message.storage.service.organizational.unit.help')" />
                    </template>
                    <a-input v-model:value="form.smbadou" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="smbadworkgroup">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.workgroup')" :tooltip="$t('message.storage.service.workgroup.help')" />
                    </template>
                    <a-input v-model:value="form.smbadworkgroup" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="smbadprincipaltype">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.initial.smb.acl.type')" :tooltip="$t('message.storage.service.initial.smb.acl.help')" />
                    </template>
                    <a-select v-model:value="form.smbadprincipaltype">
                      <a-select-option value="AD_USER">{{ $t('label.storage.service.ad.user') }}</a-select-option>
                      <a-select-option value="AD_GROUP">{{ $t('label.storage.service.ad.group') }}</a-select-option>
                    </a-select>
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="smbadprincipal">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.initial.smb.acl.principal')" :tooltip="$t('message.storage.service.initial.smb.acl.help')" />
                    </template>
                    <a-input v-model:value="form.smbadprincipal" :placeholder="form.smbadprincipaltype === 'AD_GROUP' ? 'Domain Users' : 'ablecloud'" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="smbadpermission">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.permission')" :tooltip="$t('message.storage.service.permission.help')" />
                    </template>
                    <a-select v-model:value="form.smbadpermission">
                      <a-select-option value="READ_ONLY">{{ $t('label.storage.service.permission.readonly') }}</a-select-option>
                      <a-select-option value="READ_WRITE">{{ $t('label.storage.service.permission.readwrite') }}</a-select-option>
                      <a-select-option value="ADMIN">{{ $t('label.admin') }}</a-select-option>
                    </a-select>
                  </a-form-item>
                </a-col>
              </a-row>
            </div>
          </section>
        </a-collapse-panel>

        <a-collapse-panel v-if="isServiceSelected('ISCSI')" key="iscsi" header="iSCSI">
          <section class="service-section">
            <h4>{{ $t('label.storage.service.iscsi.initial.target') }}</h4>
            <a-form-item name="iscsitargetname" required>
              <template #label>
                <tooltip-label :title="$t('label.storage.service.target.iqn')" :tooltip="$t('message.storage.service.target.iqn.help')" />
              </template>
              <a-input v-model:value="form.iscsitargetname" placeholder="iqn.2026-05.local.storage:target01" />
            </a-form-item>
            <a-row :gutter="12">
              <a-col :xs="24" :md="12">
                <a-form-item name="iscsilun" required>
                  <template #label>
                    <tooltip-label :title="$t('label.storage.service.lun')" :tooltip="$t('message.storage.service.lun.help')" />
                  </template>
                  <a-input v-model:value="form.iscsilun" placeholder="0" />
                </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
                <a-form-item name="iscsipermission" required>
                  <template #label>
                    <tooltip-label :title="$t('label.storage.service.permission')" :tooltip="$t('message.storage.service.permission.help')" />
                  </template>
                  <a-select v-model:value="form.iscsipermission">
                    <a-select-option value="READ_WRITE">{{ $t('label.storage.service.permission.readwrite') }}</a-select-option>
                    <a-select-option value="READ_ONLY">{{ $t('label.storage.service.permission.readonly') }}</a-select-option>
                  </a-select>
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item name="iscsilunsizeamount">
              <template #label>
                <tooltip-label :title="$t('label.storage.service.lun.size')" :tooltip="$t('message.storage.service.lun.size.help')" />
              </template>
              <div class="capacity-input-group">
                <a-input-number
                  v-model:value="form.iscsilunsizeamount"
                  :min="0"
                  :placeholder="$t('message.storage.service.lun.size.placeholder')" />
                <a-select v-model:value="form.iscsilunsizeunit">
                  <a-select-option v-for="unit in capacityUnits" :key="unit.value" :value="unit.value">{{ unit.label }}</a-select-option>
                </a-select>
              </div>
              <div class="field-hint">{{ $t('message.storage.service.lun.size.help') }}</div>
            </a-form-item>
            <a-form-item name="iscsiinitiator">
              <template #label>
                <tooltip-label :title="$t('label.storage.service.allowed.initiator.iqn')" :tooltip="$t('message.storage.service.allowed.initiator.iqn.help')" />
              </template>
              <a-input v-model:value="form.iscsiinitiator" />
              <div class="field-hint">{{ $t('message.storage.service.iscsi.initiator.optional') }}</div>
            </a-form-item>
            <div class="auth-subsection">
              <h4>{{ $t('label.storage.service.block.auth') }}</h4>
              <a-row :gutter="12">
                <a-col :xs="24" :md="12">
                  <a-form-item name="iscsichapenabled">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.chap.enabled')" :tooltip="$t('message.storage.service.chap.enabled.help')" />
                    </template>
                    <a-switch v-model:checked="form.iscsichapenabled" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="iscsimutualchapenabled">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.mutual.chap.enabled')" :tooltip="$t('message.storage.service.mutual.chap.enabled.help')" />
                    </template>
                    <a-switch v-model:checked="form.iscsimutualchapenabled" :disabled="!form.iscsichapenabled" />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-row v-if="form.iscsichapenabled" :gutter="12">
                <a-col :xs="24" :md="12">
                  <a-form-item name="iscsichapusername" :required="form.iscsichapenabled">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.chap.username')" :tooltip="$t('message.storage.service.chap.username.help')" />
                    </template>
                    <a-input v-model:value="form.iscsichapusername" autocomplete="off" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="iscsichapsecret" :required="form.iscsichapenabled">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.chap.secret')" :tooltip="$t('message.storage.service.chap.secret.help')" />
                    </template>
                    <a-input-password v-model:value="form.iscsichapsecret" autocomplete="new-password" />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-row v-if="form.iscsichapenabled && form.iscsimutualchapenabled" :gutter="12">
                <a-col :xs="24" :md="12">
                  <a-form-item name="iscsimutualchapusername" :required="form.iscsichapenabled && form.iscsimutualchapenabled">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.mutual.chap.username')" :tooltip="$t('message.storage.service.mutual.chap.username.help')" />
                    </template>
                    <a-input v-model:value="form.iscsimutualchapusername" autocomplete="off" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="iscsimutualchapsecret" :required="form.iscsichapenabled && form.iscsimutualchapenabled">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.mutual.chap.secret')" :tooltip="$t('message.storage.service.mutual.chap.secret.help')" />
                    </template>
                    <a-input-password v-model:value="form.iscsimutualchapsecret" autocomplete="new-password" />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-alert
                class="section-alert"
                type="info"
                show-icon
                :message="$t('message.storage.service.block.auth.secret.sensitive')" />
            </div>
          </section>
        </a-collapse-panel>

        <a-collapse-panel v-if="isServiceSelected('NVME_OF')" key="nvme-of" header="NVMe-oF">
          <section class="service-section">
            <h4>{{ $t('label.storage.service.nvme.initial.subsystem') }}</h4>
            <a-alert
              class="section-alert"
              type="warning"
              show-icon
              :message="$t('message.storage.service.spdk.planned')" />
            <a-form-item name="nvmeengine" required>
              <template #label>
                <tooltip-label :title="$t('label.storage.service.engine')" :tooltip="$t('message.storage.service.nvme.engine.help')" />
              </template>
              <a-select v-model:value="form.nvmeengine">
                <a-select-option value="KERNEL_NVMET">{{ $t('label.storage.service.kernel.nvmet') }}</a-select-option>
                <a-select-option value="SPDK" disabled>SPDK</a-select-option>
              </a-select>
            </a-form-item>
            <a-row :gutter="12">
              <a-col :xs="24" :md="12">
                <a-form-item name="nvmetransport" required>
                  <template #label>
                    <tooltip-label :title="$t('label.storage.service.transport')" :tooltip="$t('message.storage.service.nvme.transport.help')" />
                  </template>
                  <a-select v-model:value="form.nvmetransport">
                    <a-select-option value="tcp">TCP</a-select-option>
                  </a-select>
                </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
                <a-form-item name="nvmeport" required>
                  <template #label>
                    <tooltip-label :title="$t('label.storage.service.protocol.port')" :tooltip="$t('message.storage.service.protocol.port.help')" />
                  </template>
                  <a-input-number v-model:value="form.nvmeport" :min="1" :max="65535" class="full-width-input" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item name="nvmesubsystemnqn" required>
              <template #label>
                <tooltip-label :title="$t('label.storage.service.subsystem.nqn')" :tooltip="$t('message.storage.service.subsystem.nqn.help')" />
              </template>
              <a-input v-model:value="form.nvmesubsystemnqn" placeholder="nqn.2026-05.local.storage:subsystem01" />
            </a-form-item>
            <a-row :gutter="12">
              <a-col :xs="24" :md="8">
                <a-form-item name="nvmenamespaceid" required>
                  <template #label>
                    <tooltip-label :title="$t('label.storage.service.namespace.id')" :tooltip="$t('message.storage.service.namespace.id.help')" />
                  </template>
                  <a-input v-model:value="form.nvmenamespaceid" placeholder="1" />
                </a-form-item>
              </a-col>
              <a-col :xs="24" :md="16">
                <a-form-item name="nvmenamespacesizeamount">
                  <template #label>
                    <tooltip-label :title="$t('label.storage.service.namespace.size')" :tooltip="$t('message.storage.service.namespace.size.help')" />
                  </template>
                  <div class="capacity-input-group">
                    <a-input-number
                      v-model:value="form.nvmenamespacesizeamount"
                      :min="0"
                      :placeholder="$t('message.storage.service.namespace.size.placeholder')" />
                    <a-select v-model:value="form.nvmenamespacesizeunit">
                      <a-select-option v-for="unit in capacityUnits" :key="unit.value" :value="unit.value">{{ unit.label }}</a-select-option>
                    </a-select>
                  </div>
                  <div class="field-hint">{{ $t('message.storage.service.namespace.size.help') }}</div>
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item name="nvmehostnqn">
              <template #label>
                <tooltip-label :title="$t('label.storage.service.allowed.host.nqn')" :tooltip="$t('message.storage.service.allowed.host.nqn.help')" />
              </template>
              <a-input v-model:value="form.nvmehostnqn" />
              <div class="field-hint">{{ $t('message.storage.service.nvme.host.optional') }}</div>
            </a-form-item>
            <div class="auth-subsection">
              <h4>{{ $t('label.storage.service.block.auth') }}</h4>
              <a-alert
                class="section-alert"
                type="warning"
                show-icon
                :message="$t('message.storage.service.nvme.dhchap.unsupported.current.template')" />
              <a-alert
                v-if="!form.nvmehostnqn"
                class="section-alert"
                type="info"
                show-icon
                :message="$t('message.storage.service.nvme.auth.requires.host')" />
              <a-row :gutter="12">
                <a-col :xs="24" :md="12">
                  <a-form-item name="nvmedhchapenabled">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.dhchap.enabled')" :tooltip="$t('message.storage.service.dhchap.enabled.help')" />
                    </template>
                    <a-switch v-model:checked="form.nvmedhchapenabled" :disabled="!form.nvmehostnqn || !nvmeDhChapCreateSupported" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12">
                  <a-form-item name="nvmedhchapctrlenabled">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.dhchap.controller.enabled')" :tooltip="$t('message.storage.service.dhchap.controller.enabled.help')" />
                    </template>
                    <a-switch v-model:checked="form.nvmedhchapctrlenabled" :disabled="!form.nvmehostnqn || !form.nvmedhchapenabled || !nvmeDhChapCreateSupported" />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-row v-if="form.nvmehostnqn && form.nvmedhchapenabled" :gutter="12">
                <a-col :xs="24" :md="12">
                  <a-form-item name="nvmedhchapkey" :required="form.nvmedhchapenabled">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.dhchap.key')" :tooltip="$t('message.storage.service.dhchap.key.help')" />
                    </template>
                    <a-input-password v-model:value="form.nvmedhchapkey" autocomplete="new-password" />
                  </a-form-item>
                </a-col>
                <a-col :xs="24" :md="12" v-if="form.nvmedhchapctrlenabled">
                  <a-form-item name="nvmedhchapctrlkey" :required="form.nvmedhchapenabled && form.nvmedhchapctrlenabled">
                    <template #label>
                      <tooltip-label :title="$t('label.storage.service.dhchap.controller.key')" :tooltip="$t('message.storage.service.dhchap.controller.key.help')" />
                    </template>
                    <a-input-password v-model:value="form.nvmedhchapctrlkey" autocomplete="new-password" />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-alert
                class="section-alert"
                type="info"
                show-icon
                :message="$t('message.storage.service.block.auth.secret.sensitive')" />
            </div>
            <a-alert
              class="section-alert"
              type="info"
              show-icon
              :message="$t('message.storage.service.nvme.namespace.intent')" />
          </section>
        </a-collapse-panel>

      </a-collapse>
        </main>
      </div>

      <div :span="24" class="action-button">
        <a-button @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-form>
    </div>
  </a-spin>
</template>
<script>

import { ref, reactive, toRaw } from 'vue'
import { api, getAPI, postAPI } from '@/api'
import { mixinForm } from '@/utils/mixin'
import ResourceIcon from '@/components/view/ResourceIcon'
import OwnershipSelection from '@/views/compute/wizard/OwnershipSelection.vue'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import store from '@/store'

export default {
  name: 'CreateSharedFS',
  mixins: [mixinForm],
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  components: {
    OwnershipSelection,
    ResourceIcon,
    TooltipLabel
  },
  inject: ['parentFetchData'],
  data () {
    return {
      owner: {
        projectid: store.getters.project?.id,
        domainid: store.getters.project?.id ? null : store.getters.userInfo.domainid,
        account: store.getters.project?.id ? null : store.getters.userInfo.account
      },
      ownerSelection: {
        accountType: store.getters.project?.id ? 'Project' : 'Account',
        domainid: store.getters.project?.domainid || store.getters.userInfo.domainid,
        domainName: store.getters.project?.domain || store.getters.userInfo.domain || store.getters.userInfo.domainname || store.getters.userInfo.domainpath || '',
        account: store.getters.project?.id ? '' : store.getters.userInfo.account,
        projectid: store.getters.project?.id,
        projectName: store.getters.project?.name
      },
      loading: false,
      zones: [],
      zoneLoading: false,
      configLoading: false,
      networks: [],
      networkLoading: false,
      availableVolumes: [],
      volumeLoading: false,
      storagePools: [],
      storagePoolLoading: false,
      serviceofferings: [],
      serviceofferingLoading: false,
      diskofferings: [],
      diskofferingLoading: false,
      customDiskOffering: false,
      isCustomizedDiskIOps: false,
      serviceOptions: [
        {
          value: 'NFS',
          label: 'NFS',
          description: this.$t('message.storage.service.nfs.description')
        },
        {
          value: 'SMB',
          label: 'SMB',
          description: this.$t('message.storage.service.smb.description')
        },
        {
          value: 'ISCSI',
          label: 'iSCSI',
          description: this.$t('message.storage.service.iscsi.description')
        },
        {
          value: 'NVME_OF',
          label: 'NVMe-oF',
          description: this.$t('message.storage.service.nvme.description')
        }
      ],
      capacityUnits: [
        { value: 'B', label: 'B', multiplier: 1 },
        { value: 'MiB', label: 'MiB', multiplier: 1024 * 1024 },
        { value: 'GiB', label: 'GiB', multiplier: 1024 * 1024 * 1024 },
        { value: 'TiB', label: 'TiB', multiplier: 1024 * 1024 * 1024 * 1024 }
      ]
    }
  },
  computed: {
    isNormalUserOrProject () {
      return ['User'].includes(this.$store.getters.userInfo.roletype) || store.getters.project?.id
    },
    hasStorageServiceApi () {
      return 'listStorageServiceInstances' in this.$store.getters.apis
    },
    hasFileService () {
      return this.isServiceSelected('NFS') || this.isServiceSelected('SMB')
    },
    hasBlockService () {
      return this.isServiceSelected('ISCSI') || this.isServiceSelected('NVME_OF')
    },
    selectedServiceLabels () {
      return this.serviceOptions
        .filter(service => this.form.services?.includes(service.value))
        .map(service => service.label)
        .join(', ') || this.$t('label.none')
    },
    selectedServices () {
      return this.serviceOptions.filter(service => this.form.services?.includes(service.value))
    },
    ownerPanelHeader () {
      return `${this.$t('label.owner.type')} (${this.ownerSummary})`
    },
    ownerSummary () {
      const type = this.ownerSelection.accountType === 'Project' ? this.$t('label.project') : this.$t('label.account')
      const domain = this.ownerSelection.domainName || this.ownerSelection.domainid || '-'
      const owner = this.ownerSelection.accountType === 'Project'
        ? (this.ownerSelection.projectName || this.ownerSelection.projectid || '-')
        : (this.ownerSelection.account || '-')
      return [type, domain, owner].join(' / ')
    },
    selectedZoneName () {
      return this.zones.find(zone => zone.id === this.form.zoneid)?.name || '-'
    },
    selectedNetworkName () {
      return this.networks.find(network => network.id === this.form.networkid)?.name || '-'
    },
    selectedNetwork () {
      return this.networks.find(network => network.id === this.form.networkid)
    },
    isSelectedNetworkL2 () {
      return String(this.selectedNetwork?.type || '').toUpperCase() === 'L2'
    },
    selectedNetworkSupportsUserData () {
      const services = this.selectedNetwork?.service
      const serviceList = Array.isArray(services) ? services : services ? [services] : []
      return serviceList.some(service => String(service?.name || '').toLowerCase() === 'userdata')
    },
    isStaticNetwork () {
      return this.isSelectedNetworkL2 && this.form.networkmode === 'STATIC'
    },
    selectedExistingVolume () {
      return this.availableVolumes.find(volume => volume.id === this.form.existingvolumeid)
    },
    selectedExistingVolumeLabel () {
      return this.selectedExistingVolume ? this.formatVolumeOption(this.selectedExistingVolume) : '-'
    },
    selectedDiskOffering () {
      return this.diskofferings.find(offering => offering.id === this.form.diskofferingid)
    },
    selectedDiskOfferingTags () {
      return this.extractStorageTags(this.selectedDiskOffering)
    },
    filteredStoragePools () {
      const requiredTags = this.selectedDiskOfferingTags.map(tag => tag.toLowerCase())
      if (!requiredTags.length) {
        return this.storagePools
      }
      return this.storagePools.filter(pool => {
        const poolTags = this.extractStorageTags(pool).map(tag => tag.toLowerCase())
        return requiredTags.every(tag => poolTags.includes(tag))
      })
    },
    selectedStoragePool () {
      return this.filteredStoragePools.find(pool => pool.id === this.form.storageid)
    },
    selectedStoragePoolLabel () {
      return this.selectedStoragePool ? this.formatStoragePoolOption(this.selectedStoragePool) : '-'
    },
    selectedExistingVolumeSizeLabel () {
      return this.selectedExistingVolume?.size ? this.formatBytes(this.selectedExistingVolume.size) : '-'
    },
    backingCapacityLabel () {
      if (this.form.useexistingvolume) {
        return this.selectedExistingVolumeSizeLabel
      }
      if (this.customDiskOffering) {
        return this.form.size ? `${this.form.size} GiB` : '-'
      }
      const diskOffering = this.diskofferings.find(offering => offering.id === this.form.diskofferingid)
      return diskOffering?.disksize ? `${diskOffering.disksize} GiB` : '-'
    },
    nfsQuotaLabel () {
      return this.formatCapacityInput(this.form.nfsquotaamount, this.form.nfsquotaunit)
    },
    smbQuotaLabel () {
      return this.formatCapacityInput(this.form.smbquotaamount, this.form.smbquotaunit)
    },
    iscsiLunSizeLabel () {
      return this.formatCapacityInput(this.form.iscsilunsizeamount, this.form.iscsilunsizeunit)
    },
    nvmeNamespaceSizeLabel () {
      return this.formatCapacityInput(this.form.nvmenamespacesizeamount, this.form.nvmenamespacesizeunit)
    },
    smbIdentityModeLabel () {
      return this.form.smbidentitymode === 'AD'
        ? this.$t('label.storage.service.smb.identity.ad')
        : this.$t('label.storage.service.smb.identity.local')
    },
    smbInitialAclTypeLabel () {
      if (this.form.smbguestok) {
        return this.$t('label.storage.service.guest.access')
      }
      if (this.form.smbidentitymode === 'LOCAL') {
        return this.$t('label.storage.service.local.user')
      }
      return this.principalTypeLabel(this.form.smbadprincipaltype || 'AD_USER')
    },
    smbInitialAclPrincipalLabel () {
      if (this.form.smbguestok) {
        return this.$t('label.storage.service.guest.access')
      }
      if (this.form.smbidentitymode === 'LOCAL') {
        return this.form.smblocalusername || '-'
      }
      return this.form.smbadprincipal || '-'
    },
    effectiveNfsName () {
      return this.form.nfsname || (this.form.name ? this.form.name + '-nfs' : '-')
    },
    effectiveSmbName () {
      return this.form.smbname || (this.form.name ? this.form.name + '-smb' : '-')
    },
    nvmeEngineLabel () {
      return this.form.nvmeengine === 'SPDK' ? 'SPDK' : this.$t('label.storage.service.kernel.nvmet')
    },
    nvmeTransportLabel () {
      return (this.form.nvmetransport || 'tcp').toUpperCase()
    },
    principalTypeLabel () {
      return value => {
        const type = String(value || '').toUpperCase()
        const labels = {
          LOCAL_USER: 'label.storage.service.local.user',
          LOCAL_GROUP: 'label.storage.service.local.group',
          AD_USER: 'label.storage.service.ad.user',
          AD_GROUP: 'label.storage.service.ad.group'
        }
        return labels[type] ? this.$t(labels[type]) : (value || '-')
      }
    },
    nvmeDhChapCreateSupported () {
      return false
    },
    nfsProtocolModeLabel () {
      const labels = {
        V4_ONLY: this.$t('label.storage.service.nfs.protocol.mode.v4only'),
        V3V4_DUAL: this.$t('label.storage.service.nfs.protocol.mode.dual')
      }
      return value => labels[String(value || 'V4_ONLY').toUpperCase()] || String(value || '-')
    },
    importModeLabel () {
      const labels = {
        INSPECT_ONLY: this.$t('label.storage.service.import.inspect'),
        MOUNT_EXISTING: this.$t('label.storage.service.import.mount'),
        FORMAT_NEW: this.$t('label.storage.service.import.format')
      }
      return labels[this.form.importmode] || '-'
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createSharedFileSystem')
  },
  created () {
    this.initForm()
    this.fetchData()
    this.form.filesystem = 'XFS'
  },
  watch: {
    'form.name' (name, previousName) {
      this.syncInitialNfsPath(previousName ? `${previousName}-nfs` : '')
    },
    'form.nfsname' (name, previousName) {
      this.syncInitialNfsPath(previousName)
    },
    'form.networkid' () {
      Object.assign(this.form, {
        networkmode: this.isSelectedNetworkL2 && !this.selectedNetworkSupportsUserData ? 'STATIC' : 'DHCP',
        ipcidr: '',
        gateway: '',
        dns1: '',
        dns2: ''
      })
    },
    'form.smbaddomain' (domainName, previousDomainName) {
      const previousDerived = this.deriveAdWorkgroup(previousDomainName)
      if (this.form?.smbidentitymode === 'AD' && (!this.form.smbadworkgroup || this.form.smbadworkgroup === previousDerived)) {
        this.form.smbadworkgroup = this.deriveAdWorkgroup(domainName)
      }
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        networkmode: 'DHCP',
        ipcidr: '',
        gateway: '',
        dns1: '',
        dns2: '',
        services: ['NFS'],
        nfsname: '',
        nfspath: '',
        nfsprotocolmode: 'V4_ONLY',
        nfsport: 2049,
        nfsprincipal: '',
        nfspermission: 'READ_WRITE',
        nfsrootsquash: true,
        nfssync: true,
        nfssecure: false,
        nfsquotaamount: null,
        nfsquotaunit: 'GiB',
        smbname: '',
        smbpath: '/export/smb01',
        smbbrowseable: true,
        smbguestok: false,
        smbreadonly: false,
        smbquotaamount: null,
        smbquotaunit: 'GiB',
        smbidentitymode: 'LOCAL',
        smblocalusername: '',
        smblocalpassword: '',
        smblocalpasswordconfirm: '',
        smblocalpermission: 'READ_WRITE',
        smbaddomain: '',
        smbadusername: '',
        smbadpassword: '',
        smbaddns: '',
        smbadou: '',
        smbadworkgroup: '',
        smbadprincipaltype: 'AD_USER',
        smbadprincipal: '',
        smbadpermission: 'READ_WRITE',
        iscsitargetname: '',
        iscsilun: '0',
        iscsilunsizeamount: null,
        iscsilunsizeunit: 'GiB',
        iscsiinitiator: '',
        iscsipermission: 'READ_WRITE',
        iscsichapenabled: false,
        iscsichapusername: '',
        iscsichapsecret: '',
        iscsimutualchapenabled: false,
        iscsimutualchapusername: '',
        iscsimutualchapsecret: '',
        nvmeengine: 'KERNEL_NVMET',
        nvmetransport: 'tcp',
        nvmeport: 4420,
        nvmesubsystemnqn: '',
        nvmenamespaceid: '1',
        nvmenamespacesizeamount: null,
        nvmenamespacesizeunit: 'GiB',
        nvmehostnqn: '',
        nvmedhchapenabled: false,
        nvmedhchapkey: '',
        nvmedhchapctrlenabled: false,
        nvmedhchapctrlkey: '',
        useexistingvolume: false,
        storageid: '',
        existingvolumeid: '',
        miniops: null,
        maxiops: null,
        importmode: 'INSPECT_ONLY',
        resizeallowed: true
      })
      this.rules = reactive({
        zoneid: [{ required: true, message: this.$t('message.error.zone') }],
        name: [{ required: true, message: this.$t('label.required') }],
        networkid: [{ required: true, message: this.$t('label.required') }],
        networkmode: [{ required: true, message: this.$t('label.required') }],
        ipcidr: [{ validator: this.validateStaticIpCidr }],
        serviceofferingid: [{ required: true, message: this.$t('label.required') }],
        diskofferingid: [{ required: true, message: this.$t('label.required') }],
        storageid: [{
          validator: async (rule, value) => {
            if (!this.form.useexistingvolume && !value) {
              return Promise.reject(this.$t('message.error.select'))
            }
            return Promise.resolve()
          }
        }],
        size: [{ required: true, message: this.$t('message.error.custom.disk.size') }],
        existingvolumeid: [{
          validator: async (rule, value) => {
            if (this.form.useexistingvolume && !value) {
              return Promise.reject(this.$t('message.error.select'))
            }
            return Promise.resolve()
          }
        }],
        services: [{
          validator: async (rule, value) => {
            if (!value || value.length === 0) {
              return Promise.reject(this.$t('message.storage.service.select.one'))
            }
            return Promise.resolve()
          }
        }],
        nfspath: [{
          validator: async (rule, value) => {
            if (!this.isServiceSelected('NFS')) {
              return Promise.resolve()
            }
            return this.validateNfsExportPath(value, this.effectiveNfsName)
          }
        }],
        nfsprincipal: [{
          validator: async (rule, value) => {
            return Promise.resolve()
          }
        }],
        smbpath: [{
          validator: async (rule, value) => {
            if (!this.isServiceSelected('SMB')) {
              return Promise.resolve()
            }
            return this.validateFileSharePath(value)
          }
        }],
        smblocalusername: [{
          validator: async (rule, value) => {
            if (this.isServiceSelected('SMB') && this.form.smbidentitymode === 'LOCAL' && !value) {
              return Promise.reject(this.$t('message.storage.service.smb.local.user.required'))
            }
            return Promise.resolve()
          }
        }],
        smblocalpassword: [{
          validator: async (rule, value) => {
            if (this.isServiceSelected('SMB') && this.form.smbidentitymode === 'LOCAL' && !value) {
              return Promise.reject(this.$t('message.storage.service.smb.local.password.required'))
            }
            return Promise.resolve()
          }
        }],
        smblocalpasswordconfirm: [{
          validator: async (rule, value) => {
            if (!this.isServiceSelected('SMB') || this.form.smbidentitymode !== 'LOCAL') {
              return Promise.resolve()
            }
            if (!value) {
              return Promise.reject(this.$t('message.storage.service.smb.local.password.confirm.required'))
            }
            if (value !== this.form.smblocalpassword) {
              return Promise.reject(this.$t('message.storage.service.smb.local.password.mismatch'))
            }
            return Promise.resolve()
          }
        }],
        smbadprincipal: [{
          validator: async (rule, value) => {
            if (!this.isServiceSelected('SMB') || this.form.smbidentitymode !== 'AD' || this.form.smbguestok) {
              return Promise.resolve()
            }
            if (!String(value || '').trim()) {
              return Promise.reject(this.$t('message.storage.service.smb.ad.principal.required'))
            }
            return Promise.resolve()
          }
        }],
        miniops: [{ validator: this.validateCustomizedIops }],
        maxiops: [{ validator: this.validateCustomizedIops }]
      })
    },
    filterOption (input, option) {
      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
    },
    isServiceSelected (service) {
      return (this.form.services || []).includes(service)
    },
    isSetupServiceSelected (setup, service) {
      return (setup?.services || []).includes(service)
    },
    validateFileSharePath (value) {
      if (!value) {
        return Promise.reject(this.$t('label.required'))
      }
      const trimmed = String(value).trim()
      const normalized = trimmed.replace(/\/+$/, '')
      if (!trimmed.startsWith('/') || trimmed.includes('/../') || trimmed.endsWith('/..') || trimmed.includes('//')) {
        return Promise.reject(this.$t('message.storage.service.path.invalid'))
      }
      if (normalized === '' || normalized === '/' || normalized === '/export') {
        return Promise.reject(this.$t('message.storage.service.path.root.not.allowed'))
      }
      return Promise.resolve()
    },
    isValidNfsExportName (name) {
      const value = String(name || '').trim()
      return !!value && value !== '.' && value !== '..' && /^[A-Za-z0-9._-]+$/.test(value)
    },
    validateNfsExportPath (value, name) {
      const exportName = String(name || '').trim()
      if (!this.isValidNfsExportName(exportName)) {
        return Promise.reject(this.$t('message.storage.service.nfs.name.invalid'))
      }
      return this.validateFileSharePath(value).then(() => {
        const expectedPath = `/export/${exportName}`
        const normalized = String(value || '').trim().replace(/\/+$/, '')
        if (normalized !== expectedPath) {
          return Promise.reject(this.$t('message.storage.service.nfs.path.must.match.name', { path: expectedPath }))
        }
        return Promise.resolve()
      })
    },
    syncInitialNfsPath (previousName) {
      if (!this.form) {
        return
      }
      const exportName = this.effectiveNfsName
      this.form.nfspath = exportName && exportName !== '-' ? `/export/${exportName}` : ''
    },
    deriveAdWorkgroup (domainName) {
      const firstLabel = String(domainName || '').trim().split('.')[0] || ''
      return firstLabel.replace(/[^A-Za-z0-9]/g, '').slice(0, 15).toUpperCase()
    },
    arrayHasItems (array) {
      return array !== null && array !== undefined && Array.isArray(array) && array.length > 0
    },
    fetchOwnerOptions (OwnerOptions) {
      this.owner = {}
      const selectedDomain = OwnerOptions.domains?.find(domain => domain.id === OwnerOptions.selectedDomain)
      const selectedProject = OwnerOptions.projects?.find(project => project.id === OwnerOptions.selectedProject)
      this.ownerSelection = {
        accountType: OwnerOptions.selectedAccountType,
        domainid: OwnerOptions.selectedDomain,
        domainName: selectedDomain?.path || selectedDomain?.name || selectedDomain?.description || '',
        account: OwnerOptions.selectedAccount,
        projectid: OwnerOptions.selectedProject,
        projectName: selectedProject?.name
      }
      if (OwnerOptions.selectedAccountType === 'Account') {
        if (!OwnerOptions.selectedAccount) {
          return
        }
        console.log('fetched account')
        this.owner.account = OwnerOptions.selectedAccount
        this.owner.domainid = OwnerOptions.selectedDomain
      } else if (OwnerOptions.selectedAccountType === 'Project') {
        if (!OwnerOptions.selectedProject) {
          return
        }
        console.log('fetched project')
        this.owner.projectid = OwnerOptions.selectedProject
      }
      console.log('fetched owner')
      this.fetchData()
    },
    fetchData () {
      this.minCpu = store.getters.features.sharedfsvmmincpucount
      this.minMemory = store.getters.features.sharedfsvmminramsize
      this.fetchZones()
    },
    fetchZones () {
      this.zoneLoading = true
      const params = { showicon: true }
      api('listZones', params).then(json => {
        var listZones = json.listzonesresponse.zone
        if (listZones) {
          this.zones = []
          listZones = listZones.filter(x => (x.allocationstate === 'Enabled' && x.networktype === 'Advanced' && x.securitygroupsenabled === false))
          this.zones = this.zones.concat(listZones)
        }
      }).finally(() => {
        this.zoneLoading = false
        if (this.arrayHasItems(this.zones)) {
          this.form.zoneid = this.zones[0].id
          this.handleZoneChange(this.zones[0])
        }
      })
    },
    handleZoneChange (zone) {
      this.selectedZone = typeof zone === 'string' ? this.zones.find(item => item.id === zone) : zone
      if (!this.selectedZone) {
        return
      }
      this.fetchServiceOfferings()
      this.fetchDiskOfferings()
      this.fetchStoragePools()
      this.fetchNetworks()
      this.fetchAvailableVolumes()
    },
    fetchServiceOfferings () {
      this.serviceofferingLoading = true
      this.serviceofferings = []
      var params = {
        zoneid: this.selectedZone.id,
        listall: true,
        domainid: this.owner.domainid
      }
      if (this.owner.projectid) {
        params.projectid = this.owner.projectid
      } else {
        params.account = this.owner.account
      }
      api('listServiceOfferings', params).then(json => {
        var items = json.listserviceofferingsresponse.serviceoffering || []
        if (items != null) {
          for (var i = 0; i < items.length; i++) {
            if (items[i].iscustomized === false && items[i].offerha === true &&
                items[i].cpunumber >= this.minCpu && items[i].memory >= this.minMemory) {
              this.serviceofferings.push(items[i])
            }
          }
        }
        this.form.serviceofferingid = this.serviceofferings[0]?.id || ''
      }).finally(() => {
        this.serviceofferingLoading = false
      })
    },
    fetchDiskOfferings () {
      this.diskofferingLoading = true
      this.form.diskofferingid = null
      var params = {
        zoneid: this.selectedZone.id,
        listall: true,
        domainid: this.owner.domainid
      }
      if (this.owner.projectid) {
        params.projectid = this.owner.projectid
      } else {
        params.account = this.owner.account
      }
      api('listDiskOfferings', params).then(json => {
        this.diskofferings = json.listdiskofferingsresponse.diskoffering || []
        this.form.diskofferingid = this.diskofferings[0].id || ''
        this.customDiskOffering = this.diskofferings[0].iscustomized || false
        this.isCustomizedDiskIOps = this.diskofferings[0]?.iscustomizediops || false
        this.reconcileSelectedStoragePool()
      }).finally(() => {
        this.diskofferingLoading = false
      })
    },
    fetchStoragePools () {
      this.storagePools = []
      this.form.storageid = ''
      if (!('listStoragePools' in this.$store.getters.apis) || !this.selectedZone) {
        return
      }
      this.storagePoolLoading = true
      const params = {
        zoneid: this.selectedZone.id,
        listall: true
      }
      getAPI('listStoragePools', params).then(json => {
        const pools = json.liststoragepoolsresponse?.storagepool || []
        this.storagePools = pools.filter(pool => {
          const state = String(pool.state || pool.status || '').toLowerCase()
          return !state || ['up', 'enabled', 'available'].includes(state)
        })
        this.reconcileSelectedStoragePool()
      }).finally(() => {
        this.storagePoolLoading = false
      })
    },
    fetchNetworks () {
      this.networkLoading = true
      this.form.networkid = null
      var params = {
        zoneid: this.selectedZone.id,
        canusefordeploy: true,
        domainid: this.owner.domainid
      }
      if (this.owner.projectid) {
        params.projectid = this.owner.projectid
      } else {
        params.account = this.owner.account
      }
      api('listNetworks', params).then(json => {
        this.networks = json.listnetworksresponse.network || []
        this.form.networkid = this.networks[0].id || ''
      }).finally(() => {
        this.networkLoading = false
      })
    },
    handleExistingVolumeToggle (enabled) {
      if (enabled) {
        this.fetchAvailableVolumes()
      }
    },
    fetchAvailableVolumes () {
      if (!('listVolumes' in this.$store.getters.apis) || !this.selectedZone) {
        this.availableVolumes = []
        this.form.existingvolumeid = ''
        return
      }
      this.volumeLoading = true
      const params = {
        zoneid: this.selectedZone.id,
        listall: true,
        type: 'DATADISK',
        domainid: this.owner.domainid
      }
      if (this.owner.projectid) {
        params.projectid = this.owner.projectid
      } else {
        params.account = this.owner.account
      }
      getAPI('listVolumes', params).then(json => {
        const volumes = json.listvolumesresponse.volume || []
        this.availableVolumes = volumes.filter(volume => {
          return volume.type === 'DATADISK' &&
            volume.state === 'Ready' &&
            !volume.virtualmachineid &&
            !volume.vmname
        })
        if (!this.availableVolumes.some(volume => volume.id === this.form.existingvolumeid)) {
          this.form.existingvolumeid = ''
        }
      }).finally(() => {
        this.volumeLoading = false
      })
    },
    closeModal () {
      this.$emit('close-action')
    },
    formatVolumeOption (volume) {
      const name = volume.name || volume.displayname || volume.id
      const size = volume.size ? this.formatBytes(volume.size) : '-'
      const zone = volume.zonename || this.selectedZoneName
      return `${name} / ${size} / ${zone}`
    },
    formatStoragePoolOption (pool) {
      const name = pool.name || pool.id
      const type = pool.type || pool.storagetype || '-'
      const scope = pool.scope || '-'
      const state = pool.state || pool.status || '-'
      const tags = this.extractStorageTags(pool)
      const tagLabel = tags.length ? ` / ${tags.join(',')}` : ''
      return `${name} / ${type} / ${scope} / ${state}${tagLabel}`
    },
    extractStorageTags (item) {
      if (!item) {
        return []
      }
      const raw = item.tags ?? item.storageTags ?? item.storagetags ?? item.storagepooltags ?? item.storagePoolTags
      if (Array.isArray(raw)) {
        return raw.map(value => String(value).trim()).filter(Boolean)
      }
      if (raw && typeof raw === 'object') {
        return Object.values(raw).map(value => String(value).trim()).filter(Boolean)
      }
      return String(raw || '')
        .split(',')
        .map(value => value.trim())
        .filter(Boolean)
    },
    reconcileSelectedStoragePool () {
      if (!this.form.useexistingvolume && !this.filteredStoragePools.some(pool => pool.id === this.form.storageid)) {
        this.form.storageid = this.filteredStoragePools[0]?.id || ''
      }
    },
    formatBytes (bytes) {
      const value = Number(bytes)
      if (!value || value < 0) {
        return '-'
      }
      const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
      let size = value
      let unitIndex = 0
      while (size >= 1024 && unitIndex < units.length - 1) {
        size = size / 1024
        unitIndex += 1
      }
      return `${size.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`
    },
    capacityMultiplier (unit) {
      return this.capacityUnits.find(item => item.value === unit)?.multiplier || 1
    },
    toCapacityBytes (amount, unit) {
      if (amount === null || amount === undefined || amount === '') {
        return null
      }
      const numericAmount = Number(amount)
      if (Number.isNaN(numericAmount) || numericAmount < 0) {
        return null
      }
      return Math.round(numericAmount * this.capacityMultiplier(unit))
    },
    formatCapacityInput (amount, unit) {
      const bytes = this.toCapacityBytes(amount, unit)
      if (!bytes) {
        return '-'
      }
      return `${amount} ${unit || 'B'} (${bytes} B)`
    },
    handleDiskOfferingChange (id) {
      const diskoffering = this.diskofferings.filter(x => x.id === id)
      this.customDiskOffering = diskoffering[0]?.iscustomized || false
      this.isCustomizedDiskIOps = diskoffering[0]?.iscustomizediops || false
      if (!this.isCustomizedDiskIOps) {
        this.form.miniops = null
        this.form.maxiops = null
      }
      this.reconcileSelectedStoragePool()
    },
    hasFormValue (value) {
      return value !== undefined && value !== null && value !== ''
    },
    validateCustomizedIops () {
      if (!this.isCustomizedDiskIOps) {
        return Promise.resolve()
      }
      const hasMin = this.hasFormValue(this.form.miniops)
      const hasMax = this.hasFormValue(this.form.maxiops)
      if (hasMin !== hasMax) {
        return Promise.reject(this.$t('label.required'))
      }
      if (!hasMin) {
        return Promise.resolve()
      }
      const minIops = Number(this.form.miniops)
      const maxIops = Number(this.form.maxiops)
      if (!Number.isInteger(minIops) || !Number.isInteger(maxIops) || minIops <= 0 || maxIops <= 0 || minIops > maxIops) {
        return Promise.reject(this.$t('message.error.number'))
      }
      return Promise.resolve()
    },
    validateStaticIpCidr (rule, value) {
      if (!this.isStaticNetwork) {
        return Promise.resolve()
      }
      if (!value) {
        return Promise.reject(this.$t('label.required'))
      }
      const parts = String(value).trim().split('/')
      const octets = parts[0]?.split('.') || []
      const prefix = Number(parts[1])
      const validAddress = octets.length === 4 && octets.every(octet => /^\d+$/.test(octet) && Number(octet) >= 0 && Number(octet) <= 255)
      const validPrefix = parts.length === 2 && /^\d+$/.test(parts[1]) && prefix >= 0 && prefix <= 32
      if (!validAddress || !validPrefix) {
        return Promise.reject(this.$t('message.sharedfs.static.ip.cidr.invalid'))
      }
      return Promise.resolve()
    },
    buildCreateSharedFsRequest (values) {
      const data = {
        name: values.name,
        description: values.description,
        zoneid: values.zoneid,
        serviceofferingid: values.serviceofferingid,
        diskofferingid: values.diskofferingid,
        networkid: values.networkid,
        size: this.createSharedFsSize(values),
        filesystem: values.filesystem,
        domainid: this.owner.domainid
      }
      if (this.isCustomizedDiskIOps && this.hasFormValue(values.miniops) && this.hasFormValue(values.maxiops)) {
        data.miniops = Number(values.miniops)
        data.maxiops = Number(values.maxiops)
      }
      if (this.owner.projectid) {
        data.projectid = this.owner.projectid
      } else {
        data.account = this.owner.account
      }
      if (values.storageid) {
        data.storageid = values.storageid
      }
      data.networkmode = this.isStaticNetwork ? 'STATIC' : 'DHCP'
      if (this.isStaticNetwork) {
        Object.assign(data, {
          ipcidr: values.ipcidr,
          gateway: values.gateway,
          dns1: values.dns1
        })
        if (values.dns2) data.dns2 = values.dns2
      }
      return this.cleanParams(data)
    },
    handleSubmit (e) {
      if (e && e.preventDefault) {
        e.preventDefault()
      }
      if (this.loading) return
      this.syncInitialNfsPath()
      this.formRef.value.validate().then(async () => {
        const formRaw = toRaw(this.form)
        const values = this.handleRemoveFields(formRaw)

        const data = this.buildCreateSharedFsRequest(values)
        const missingApis = this.missingStorageServiceSetupApis()
        if (missingApis.length > 0) {
          this.$notification.error({
            message: this.$t('message.storage.service.setup.api.missing'),
            description: missingApis.join(', ')
          })
          return
        }
        const setupSnapshot = this.buildStorageServiceSetupSnapshot(values)
        this.loading = true
        postAPI('createSharedFileSystem', data).then(response => {
          const jobId = response.createsharedfilesystemresponse?.jobid
          if (!jobId) {
            throw new Error(this.$t('message.create.sharedfs.failed'))
          }
          const notificationKey = `storage-service-setup-${jobId}`
          this.notifyStorageServiceSetup(notificationKey, 'info', 'message.storage.service.setup.accepted', setupSnapshot.name, 0)
          this.loading = false
          this.closeModal()
          this.runInitialStorageServiceSetup(jobId, setupSnapshot, notificationKey)
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }).catch((error) => {
        if (error?.errorFields?.[0]?.name) {
          this.formRef.value.scrollToField(error.errorFields[0].name)
        }
      })
    },
    async runInitialStorageServiceSetup (jobId, setup, notificationKey) {
      try {
        this.notifyStorageServiceSetup(notificationKey, 'info', 'message.storage.service.setup.sharedfs.running', setup.name, 0)
        const result = await this.pollStorageServiceSetupJob(jobId, 'createSharedFileSystem', 240)
        await this.configureInitialStorageServices(result, setup, notificationKey)
      } catch (error) {
        this.notifyStorageServiceSetup(
          notificationKey,
          'error',
          'message.storage.service.setup.partial.failed',
          error?.message || setup.name,
          0
        )
      }
    },
    notifyStorageServiceSetup (key, type, messageKey, description, duration = 4.5) {
      const notifier = this.$notification[type] || this.$notification.info
      notifier({
        key,
        message: this.$t(messageKey),
        description,
        duration
      })
    },
    async configureInitialStorageServices (result, setup, notificationKey) {
      if (!setup?.services || setup.services.length === 0) {
        return
      }
      try {
        this.assertStorageServiceSetupApis(setup)
        this.notifyStorageServiceSetup(notificationKey, 'info', 'message.storage.service.setup.resolve.running', setup.name, 0)
        const sharedfs = await this.resolveCreatedSharedFileSystem(result)
        const instance = await this.findStorageServiceInstance(sharedfs, setup)
        if (!instance) {
          throw new Error(this.$t('message.storage.service.setup.instance.not.found'))
        }
        this.notifyStorageServiceSetup(notificationKey, 'info', 'message.storage.service.setup.protocol.running', setup.name, 0)
        await this.enableSelectedProtocols(instance, setup)
        this.notifyStorageServiceSetup(notificationKey, 'info', 'message.storage.service.setup.resources.running', setup.name, 0)
        const fileSetup = await this.createInitialFileServices(instance, sharedfs, setup)
        const blockSetup = await this.createInitialBlockServices(instance, sharedfs, setup)
        this.notifyStorageServiceSetup(notificationKey, 'info', 'message.storage.service.setup.verify.running', setup.name, 0)
        await this.verifyInitialStorageServiceSetup(instance, { ...fileSetup, ...blockSetup }, setup)
        this.notifyStorageServiceSetup(notificationKey, 'success', 'message.storage.service.setup.success', setup.name)
      } finally {
        this.form.smbadpassword = ''
        this.form.smblocalpassword = ''
        this.form.smblocalpasswordconfirm = ''
        this.parentFetchData()
      }
    },
    buildStorageServiceSetupSnapshot (values) {
      const rawForm = toRaw(this.form)
      const snapshot = {
        ...values,
        ...rawForm
      }
      snapshot.services = [...(snapshot.services || [])]
      snapshot.useexistingvolume = !!snapshot.useexistingvolume
      return snapshot
    },
    normalizeApiItems (rawItems) {
      if (!rawItems) {
        return []
      }
      return Array.isArray(rawItems) ? rawItems.filter(Boolean) : [rawItems]
    },
    firstListValue (response, keys = []) {
      for (const key of keys) {
        if (response?.[key]) {
          return response[key]
        }
      }
      return Object.values(response || {}).find(value => Array.isArray(value)) || null
    },
    extractSharedFileSystemFromResult (result) {
      const jobResult = result?.queryasyncjobresultresponse?.jobresult || result?.jobresult || result || {}
      const sharedfs = jobResult.sharedfilesystem ||
        jobResult.sharedfs ||
        jobResult.sharedfilesystems ||
        jobResult.sharedfilesystemresponse ||
        jobResult.SharedFS ||
        result?.sharedfilesystem ||
        result?.sharedfs ||
        result?.sharedfilesystems ||
        result?.sharedfilesystemresponse ||
        result?.SharedFS ||
        {}
      return this.normalizeApiItems(sharedfs)[0] || {}
    },
    async resolveCreatedSharedFileSystem (result) {
      const created = this.extractSharedFileSystemFromResult(result)
      const sharedFsId = created?.id || result?.id
      if (!sharedFsId) {
        throw new Error(this.$t('message.storage.service.setup.sharedfs.not.found'))
      }
      try {
        const json = await getAPI('listSharedFileSystems', { id: sharedFsId, listall: true })
        const response = json.listsharedfilesystemsresponse || json.listSharedFileSystemsResponse || {}
        const rawItems = this.firstListValue(response, ['sharedfs', 'sharedfilesystem', 'sharedfilesystems'])
        const items = this.normalizeApiItems(rawItems)
        const sharedfs = items.find(item => item.id === sharedFsId) || items[0]
        if (sharedfs) {
          return {
            ...created,
            ...sharedfs
          }
        }
      } catch (e) {
        // The completed create job is enough to continue initial setup. The
        // follow-up list call is only a freshness pass for UI-facing metadata.
      }
      if (!created?.virtualmachineid && !created?.virtualMachineId) {
        throw new Error(this.$t('message.storage.service.setup.sharedfs.not.found'))
      }
      return created
    },
    async findStorageServiceInstance (sharedfs, setup = this.form) {
      const vmId = sharedfs.virtualmachineid || sharedfs.virtualMachineId
      const params = {
        zoneid: setup.zoneid
      }
      for (let attempt = 0; attempt < 40; attempt++) {
        const json = await getAPI('listStorageServiceInstances', params)
        const response = json.liststorageserviceinstancesresponse || {}
        const rawItems = this.firstListValue(response, ['storageserviceinstance', 'storageserviceinstances'])
        const items = this.normalizeApiItems(rawItems)
        const instance = items.find(item => vmId && item.virtualmachineid === vmId) || items.find(item => item.name === setup.name)
        if (instance) {
          return instance
        }
        await this.delay(3000)
      }
      return null
    },
    initialSmbAclParams (snapshot = this.form) {
      if (snapshot.smbguestok) {
        return {}
      }
      if (snapshot.smbidentitymode === 'LOCAL') {
        const principal = String(snapshot.smblocalusername || '').trim()
        return principal ? {
          aclprincipaltype: 'LOCAL_USER',
          aclprincipal: principal,
          aclpermission: snapshot.smblocalpermission || 'READ_WRITE',
          aclpassword: snapshot.smblocalpassword
        } : {}
      }
      if (snapshot.smbidentitymode === 'AD') {
        const principal = String(snapshot.smbadprincipal || '').trim()
        return principal ? {
          aclprincipaltype: snapshot.smbadprincipaltype || 'AD_USER',
          aclprincipal: principal,
          aclpermission: snapshot.smbadpermission || 'READ_WRITE'
        } : {}
      }
      return {}
    },
    async enableSelectedProtocols (instance, setup = this.form) {
      const listenIp = ''
      for (const service of setup.services) {
        await this.runStorageServiceSetup('enableStorageServiceProtocol', {
          instanceid: instance.id,
          protocol: service,
          listenip: listenIp,
          port: this.defaultProtocolPort(service, setup),
          protocolmode: service === 'NFS' ? (setup.nfsprotocolmode || 'V4_ONLY') : undefined
        })
      }
    },
    async createInitialFileServices (instance, sharedfs, snapshot = this.form) {
      const backingVolumeId = this.initialBackingVolumeId(sharedfs, snapshot)
      const setup = {}
      if (this.isSetupServiceSelected(snapshot, 'NFS')) {
        const nfsName = snapshot.nfsname || snapshot.name + '-nfs'
        const nfsPath = snapshot.nfspath || `/export/${nfsName}`
        const exportResponse = await this.runStorageServiceSetup('createStorageNfsExport', {
          instanceid: instance.id,
          name: nfsName,
          path: nfsPath,
          volumeid: backingVolumeId,
          filesystem: (snapshot.filesystem || 'XFS').toLowerCase(),
          importmode: 'FORMAT_IF_EMPTY',
          createdirectory: true,
          quotabytes: this.toCapacityBytes(snapshot.nfsquotaamount, snapshot.nfsquotaunit),
          protocolmode: snapshot.nfsprotocolmode || 'V4_ONLY',
          readonly: snapshot.nfspermission === 'READ_ONLY',
          rootsquash: snapshot.nfsrootsquash,
          anonuid: snapshot.nfspermission === 'READ_ONLY' || !snapshot.nfsrootsquash ? null : 65534,
          anongid: snapshot.nfspermission === 'READ_ONLY' || !snapshot.nfsrootsquash ? null : 65534,
          owneruid: snapshot.nfspermission === 'READ_ONLY' || !snapshot.nfsrootsquash ? null : 65534,
          ownergid: snapshot.nfspermission === 'READ_ONLY' || !snapshot.nfsrootsquash ? null : 65534,
          mode: snapshot.nfspermission === 'READ_ONLY' || !snapshot.nfsrootsquash ? null : '0775',
          recursivepermission: false,
          sync: snapshot.nfssync,
          secure: snapshot.nfssecure,
          deferapply: !!snapshot.nfsprincipal
        })
        const exportId = this.extractCreatedId(exportResponse, 'storagenfsexport')
        setup.nfsExportId = exportId
        if (!exportId) {
          throw new Error(this.$t('message.storage.service.setup.verify.nfs.missing'))
        }
        if (snapshot.nfsprincipal) {
          const aclResponse = await this.runStorageServiceSetup('createStorageNfsAcl', {
            exportid: exportId,
            principaltype: 'CIDR',
            principal: snapshot.nfsprincipal,
            permission: snapshot.nfspermission,
            rootsquash: snapshot.nfsrootsquash,
            sync: snapshot.nfssync,
            secure: snapshot.nfssecure
          })
          setup.nfsAclId = this.extractCreatedId(aclResponse, 'storageaccessrule')
        }
        if (snapshot.useexistingvolume && snapshot.existingvolumeid) {
          await this.attachInitialVolume(exportResponse, snapshot)
        }
      }
      if (this.isSetupServiceSelected(snapshot, 'SMB')) {
        const initialSmbAcl = this.initialSmbAclParams(snapshot)
        const shareResponse = await this.runStorageServiceSetup('createStorageSmbShare', {
          instanceid: instance.id,
          name: snapshot.smbname || snapshot.name + '-smb',
          path: snapshot.smbpath,
          volumeid: backingVolumeId,
          filesystem: (snapshot.filesystem || 'XFS').toLowerCase(),
          quotabytes: this.toCapacityBytes(snapshot.smbquotaamount, snapshot.smbquotaunit),
          readonly: snapshot.smbreadonly,
          browseable: snapshot.smbbrowseable,
          guestok: snapshot.smbguestok,
          ...initialSmbAcl
        })
        setup.smbShareId = this.extractCreatedId(shareResponse, 'storagesmbshare')
        if (!setup.smbShareId) {
          throw new Error(this.$t('message.storage.service.setup.verify.smb.missing'))
        }
        if (snapshot.smbidentitymode === 'AD') {
          await this.runStorageServiceSetup('joinStorageServiceToAdDomain', {
            instanceid: instance.id,
            domainname: snapshot.smbaddomain,
            username: snapshot.smbadusername,
            password: snapshot.smbadpassword,
            dnsservers: snapshot.smbaddns,
            organizationalunit: snapshot.smbadou,
            workgroup: snapshot.smbadworkgroup || this.deriveAdWorkgroup(snapshot.smbaddomain)
          })
        }
        if (snapshot.useexistingvolume && snapshot.existingvolumeid) {
          await this.attachInitialVolume(shareResponse, snapshot)
        }
      }
      return setup
    },
    async createInitialBlockServices (instance, sharedfs, snapshot = this.form) {
      const backingVolumeId = this.initialBackingVolumeId(sharedfs, snapshot)
      const setup = {}
      if (this.isSetupServiceSelected(snapshot, 'ISCSI') && snapshot.iscsitargetname) {
        const targetResponse = await this.runStorageServiceSetup('createStorageIscsiTarget', {
          instanceid: instance.id,
          targetname: snapshot.iscsitargetname,
          volumeid: backingVolumeId,
          lun: snapshot.iscsilun || '0',
          lunsizebytes: this.toCapacityBytes(snapshot.iscsilunsizeamount, snapshot.iscsilunsizeunit)
        })
        const targetId = this.extractCreatedId(targetResponse, 'storageiscsitarget')
        setup.iscsiTargetId = targetId
        if (targetId && snapshot.iscsiinitiator) {
          await this.runStorageServiceSetup('createStorageIscsiAcl', {
            targetid: targetId,
            initiatoriqn: snapshot.iscsiinitiator,
            permission: snapshot.iscsipermission || 'READ_WRITE',
            chapenabled: snapshot.iscsichapenabled,
            chapusername: snapshot.iscsichapenabled ? snapshot.iscsichapusername : '',
            chapsecret: snapshot.iscsichapenabled ? snapshot.iscsichapsecret : '',
            mutualchapenabled: snapshot.iscsichapenabled && snapshot.iscsimutualchapenabled,
            mutualchapusername: snapshot.iscsichapenabled && snapshot.iscsimutualchapenabled ? snapshot.iscsimutualchapusername : '',
            mutualchapsecret: snapshot.iscsichapenabled && snapshot.iscsimutualchapenabled ? snapshot.iscsimutualchapsecret : ''
          })
        }
      }
      if (this.isSetupServiceSelected(snapshot, 'NVME_OF')) {
        await this.runStorageServiceSetup('prepareStorageServiceNvmeOfVm', {
          instanceid: instance.id,
          engine: snapshot.nvmeengine,
          transport: snapshot.nvmetransport || 'tcp',
          validateonly: true
        })
        if (snapshot.nvmesubsystemnqn) {
          const subsystemResponse = await this.runStorageServiceSetup('createStorageNvmeOfSubsystem', {
            instanceid: instance.id,
            subsystemnqn: snapshot.nvmesubsystemnqn,
            allowanyhost: false,
            engine: snapshot.nvmeengine,
            transport: snapshot.nvmetransport || 'tcp'
          })
          const subsystemId = this.extractCreatedId(subsystemResponse, 'storagenvmeofsubsystem')
          setup.nvmeSubsystemId = subsystemId
          if (subsystemId && backingVolumeId) {
            await this.runStorageServiceSetup('createStorageNvmeOfNamespace', {
              subsystemid: subsystemId,
              namespaceid: snapshot.nvmenamespaceid || '1',
              volumeid: backingVolumeId,
              namespacesizebytes: this.toCapacityBytes(snapshot.nvmenamespacesizeamount, snapshot.nvmenamespacesizeunit)
            })
          }
          if (subsystemId && snapshot.nvmehostnqn) {
            const dhChapEnabled = this.nvmeDhChapCreateSupported && snapshot.nvmedhchapenabled
            const dhChapCtrlEnabled = dhChapEnabled && snapshot.nvmedhchapctrlenabled
            await this.runStorageServiceSetup('createStorageNvmeOfHostAcl', {
              subsystemid: subsystemId,
              hostnqn: snapshot.nvmehostnqn,
              dhchapenabled: dhChapEnabled,
              dhchapkey: dhChapEnabled ? snapshot.nvmedhchapkey : '',
              dhchapctrlenabled: dhChapCtrlEnabled,
              dhchapctrlkey: dhChapCtrlEnabled ? snapshot.nvmedhchapctrlkey : ''
            })
          }
        }
      }
      this.form.iscsichapsecret = ''
      this.form.iscsimutualchapsecret = ''
      this.form.nvmedhchapkey = ''
      this.form.nvmedhchapctrlkey = ''
      return setup
    },
    async attachInitialVolume (shareResponse, snapshot = this.form) {
      const shareId = this.extractCreatedId(shareResponse, 'storagesmbshare') || this.extractCreatedId(shareResponse, 'storagenfsexport')
      if (!shareId) {
        return
      }
      await this.runStorageServiceSetup('attachStorageVolumeToFileShare', {
        id: shareId,
        volumeid: snapshot.existingvolumeid,
        filesystem: (snapshot.filesystem || 'XFS').toLowerCase(),
        importmode: snapshot.importmode
      })
    },
    async runStorageServiceSetup (api, params) {
      if (!(api in this.$store.getters.apis)) {
        throw new Error(this.$t('message.storage.service.setup.api.missing.with.name', { api }))
      }
      const clean = this.cleanParams(params)
      const response = await postAPI(api, clean)
      const setupResponse = response[api.toLowerCase() + 'response'] || response
      if (setupResponse.jobid) {
        return this.pollStorageServiceSetupJob(setupResponse.jobid, api)
      }
      return setupResponse
    },
    async pollStorageServiceSetupJob (jobId, api, maxAttempts = 120) {
      for (let attempt = 0; attempt < maxAttempts; attempt++) {
        const json = await getAPI('queryAsyncJobResult', { jobId })
        const result = json.queryasyncjobresultresponse
        if (result?.jobstatus === 1) {
          return result.jobresult || result
        }
        if (result?.jobstatus === 2) {
          throw new Error(result.jobresult?.errortext || this.$t('message.storage.service.setup.job.failed', { api }))
        }
        await this.delay(2000)
      }
      throw new Error(this.$t('message.storage.service.setup.job.timeout', { api }))
    },
    delay (milliseconds) {
      return new Promise(resolve => window.setTimeout(resolve, milliseconds))
    },
    missingStorageServiceSetupApis (setup = this.form) {
      const required = new Set(['listSharedFileSystems', 'listStorageServiceInstances', 'enableStorageServiceProtocol'])
      if (this.isSetupServiceSelected(setup, 'NFS')) {
        required.add('createStorageNfsExport')
        required.add('listStorageNfsExports')
        if (setup.nfsprincipal) {
          required.add('createStorageNfsAcl')
          required.add('listStorageNfsAcls')
        }
      }
      if (this.isSetupServiceSelected(setup, 'SMB')) {
        required.add('createStorageSmbShare')
        required.add('listStorageSmbShares')
        if (!setup.smbguestok) {
          required.add('createStorageSmbAcl')
          required.add('listStorageSmbAcls')
        }
        if (setup.smbidentitymode === 'AD') {
          required.add('joinStorageServiceToAdDomain')
        }
      }
      if (this.isSetupServiceSelected(setup, 'ISCSI')) {
        required.add('createStorageIscsiTarget')
        required.add('listStorageIscsiTargets')
        if (setup.iscsiinitiator) {
          required.add('createStorageIscsiAcl')
          required.add('listStorageIscsiAcls')
        }
      }
      if (this.isSetupServiceSelected(setup, 'NVME_OF')) {
        required.add('prepareStorageServiceNvmeOfVm')
        required.add('createStorageNvmeOfSubsystem')
        required.add('listStorageNvmeOfSubsystems')
        if (setup.nvmesubsystemnqn) {
          required.add('createStorageNvmeOfNamespace')
        }
        if (setup.nvmehostnqn) {
          required.add('createStorageNvmeOfHostAcl')
          required.add('listStorageNvmeOfHostAcls')
        }
      }
      if (setup.useexistingvolume && setup.existingvolumeid && (this.isSetupServiceSelected(setup, 'NFS') || this.isSetupServiceSelected(setup, 'SMB'))) {
        required.add('attachStorageVolumeToFileShare')
      }
      return Array.from(required).filter(api => !(api in this.$store.getters.apis))
    },
    assertStorageServiceSetupApis (setup = this.form) {
      const missingApis = this.missingStorageServiceSetupApis(setup)
      if (missingApis.length > 0) {
        throw new Error(this.$t('message.storage.service.setup.api.missing.with.name', { api: missingApis.join(', ') }))
      }
    },
    async verifyInitialStorageServiceSetup (instance, setup = {}, snapshot = this.form) {
      if (this.isSetupServiceSelected(snapshot, 'NFS')) {
        const exports = await this.fetchStorageServiceItems('listStorageNfsExports', 'storagenfsexport', { id: setup.nfsExportId })
        if (exports.length === 0) {
          throw new Error(this.$t('message.storage.service.setup.verify.nfs.missing'))
        }
        if (snapshot.nfsprincipal) {
          const acls = await this.fetchStorageServiceItems('listStorageNfsAcls', 'storageaccessrule', { exportid: setup.nfsExportId })
          if (acls.length === 0) {
            throw new Error(this.$t('message.storage.service.setup.verify.nfs.acl.missing'))
          }
        }
        const runtimeReady = await this.verifyNfsRuntimeInventory(instance, exports[0], snapshot)
        if (!runtimeReady) {
          console.warn('NFS runtime inventory cache is not fresh yet after successful initial setup.', exports[0])
        }
      }
      if (this.isSetupServiceSelected(snapshot, 'SMB')) {
        const shares = await this.fetchStorageServiceItems('listStorageSmbShares', 'storagesmbshare', { instanceid: instance.id })
        if (shares.length === 0) {
          throw new Error(this.$t('message.storage.service.setup.verify.smb.missing'))
        }
        if (!snapshot.smbguestok) {
          const acls = await this.fetchStorageServiceItems('listStorageSmbAcls', 'storageaccessrule', { shareid: setup.smbShareId || shares[0].id })
          if (acls.length === 0) {
            throw new Error(this.$t('message.storage.service.setup.verify.smb.acl.missing'))
          }
        }
      }
      if (this.isSetupServiceSelected(snapshot, 'ISCSI')) {
        const targets = await this.fetchStorageServiceItems('listStorageIscsiTargets', 'storageiscsitarget', { instanceid: instance.id })
        if (targets.length === 0) {
          throw new Error(this.$t('message.storage.service.setup.verify.iscsi.missing'))
        }
        if (snapshot.iscsiinitiator) {
          const acls = await this.fetchStorageServiceItems('listStorageIscsiAcls', 'storageaccessrule', { targetid: targets[0].id })
          if (acls.length === 0) {
            throw new Error(this.$t('message.storage.service.setup.verify.iscsi.acl.missing'))
          }
        }
      }
      if (this.isSetupServiceSelected(snapshot, 'NVME_OF')) {
        const subsystemParams = setup.nvmeSubsystemId
          ? { id: setup.nvmeSubsystemId }
          : { instanceid: instance.id, subsystemnqn: snapshot.nvmesubsystemnqn }
        const subsystems = await this.fetchStorageServiceItems('listStorageNvmeOfSubsystems', 'storagenvmeofsubsystem', subsystemParams)
        const subsystem = subsystems.find(item => this.isNvmeSubsystemItem(item) && (!snapshot.nvmesubsystemnqn || (item.targetname || item.targetName) === snapshot.nvmesubsystemnqn)) ||
          subsystems.find(item => this.isNvmeSubsystemItem(item))
        if (!subsystem) {
          throw new Error(this.$t('message.storage.service.setup.verify.nvme.missing'))
        }
        if (snapshot.nvmehostnqn) {
          const acls = await this.fetchStorageServiceItems('listStorageNvmeOfHostAcls', 'storageaccessrule', { subsystemid: subsystem.id })
          const matchingAcl = acls.find(acl => (acl.principal || acl.hostnqn || acl.hostNqn) === snapshot.nvmehostnqn)
          if (!matchingAcl) {
            throw new Error(this.$t('message.storage.service.setup.verify.nvme.acl.missing'))
          }
        }
      }
    },
    parseStorageServiceItemConfig (item) {
      try {
        return JSON.parse(item?.config || item?.configjson || item?.configJson || '{}')
      } catch (e) {
        return {}
      }
    },
    isNvmeSubsystemItem (item) {
      const config = this.parseStorageServiceItemConfig(item)
      return String(config.type || '').toLowerCase() === 'subsystem' || !(item?.volumeid || item?.volumeId || item?.lunornamespace || item?.lunOrNamespace)
    },
    async fetchStorageServiceItems (api, itemName, params) {
      if (!(api in this.$store.getters.apis)) {
        throw new Error(this.$t('message.storage.service.setup.api.missing.with.name', { api }))
      }
      const response = await getAPI(api, this.cleanParams(params))
      const apiResponse = response[api.toLowerCase() + 'response'] || {}
      const rawItems = this.firstListValue(apiResponse, [itemName, `${itemName}s`])
      return this.normalizeApiItems(rawItems)
    },
    async verifyNfsRuntimeInventory (instance, nfsExport, snapshot = this.form) {
      const fileName = `ablestack-${nfsExport.id}.exports`
      const expectedPath = this.clientVisibleNfsExportPath(nfsExport)
      const expectedPrincipal = this.nfsRuntimePrincipal(snapshot.nfsprincipal)
      for (let attempt = 0; attempt < 10; attempt++) {
        const runtime = await this.fetchStorageServiceItems('listStorageServiceInventory', 'storageserviceruntime', { instanceid: instance.id })
        const inventory = this.parseRuntimeResultJson(runtime[0])
        const exports = inventory.nfsExports || []
        const matched = exports.find(item => item.file === fileName)
        const entries = matched?.entries || []
        if (matched && entries.some(entry => this.isExpectedNfsRuntimeEntry(entry, expectedPath, expectedPrincipal))) {
          return true
        }
        await this.delay(2000)
      }
      return false
    },
    clientVisibleNfsExportPath (nfsExport) {
      const source = nfsExport?.name || this.basename(nfsExport?.path)
      return '/' + this.safeNfsRootName(source)
    },
    basename (path) {
      const value = String(path || '').replace(/\/+$/, '')
      const parts = value.split('/')
      return parts[parts.length - 1] || ''
    },
    safeNfsRootName (value) {
      const sanitized = String(value || '')
        .trim()
        .replace(/^\/+|\/+$/g, '')
        .replace(/[^A-Za-z0-9_.-]+/g, '-')
        .replace(/^[.-]+|[.-]+$/g, '')
      return sanitized || 'export'
    },
    nfsRuntimePrincipal (principal) {
      const value = String(principal || '').trim()
      if (!value) {
        return '*'
      }
      return value === '0.0.0.0/0' || value === '::/0' ? '*' : value
    },
    isExpectedNfsRuntimeEntry (entry, expectedPath, expectedPrincipal) {
      const value = String(entry || '').trim()
      if (!value) {
        return false
      }
      const parts = value.split(/\s+/)
      if (parts[0] !== expectedPath) {
        return false
      }
      return parts.slice(1).some(part => part === expectedPrincipal || part.startsWith(expectedPrincipal + '('))
    },
    parseRuntimeResultJson (runtime) {
      if (!runtime?.resultjson) {
        return {}
      }
      try {
        return JSON.parse(runtime.resultjson)
      } catch (e) {
        return {}
      }
    },
    cleanParams (params) {
      const clean = {}
      Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null && value !== '') {
          clean[key] = value
        }
      })
      return clean
    },
    createSharedFsSize (values) {
      if (!this.form.useexistingvolume) {
        return values.size
      }
      const selectedSize = Number(this.selectedExistingVolume?.size || 0)
      return selectedSize > 0 ? Math.ceil(selectedSize / (1024 * 1024 * 1024)) : values.size
    },
    initialBackingVolumeId (sharedfs, snapshot = this.form) {
      if (snapshot.useexistingvolume && snapshot.existingvolumeid) {
        return snapshot.existingvolumeid
      }
      const volumeId = sharedfs?.volumeid || sharedfs?.volumeId || ''
      if (!volumeId) {
        throw new Error(this.$t('message.storage.service.setup.backing.volume.missing'))
      }
      return volumeId
    },
    extractCreatedId (response, objectName) {
      if (!response) {
        return null
      }
      const item = response[objectName] || response.jobresult?.[objectName]
      return item?.id || response.id || null
    },
    defaultProtocolPort (protocol, snapshot = this.form) {
      const ports = {
        NFS: Number(snapshot.nfsport) || 2049,
        SMB: 445,
        ISCSI: 3260,
        NVME_OF: Number(snapshot.nvmeport) || 4420
      }
      return ports[protocol] || null
    },
    storagePermissionLabel (permission) {
      const labels = {
        READ_WRITE: this.$t('label.storage.service.permission.readwrite'),
        READ_ONLY: this.$t('label.storage.service.permission.readonly'),
        ADMIN: this.$t('label.admin')
      }
      return labels[permission] || permission || '-'
    }
  }
}
</script>

<style lang="scss" scoped>
.sharedfs-create-dialog {
  width: min(92vw, 1120px);
  max-height: calc(100vh - 96px);
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;

  @media (min-width: 760px) {
    width: min(92vw, 1120px);
  }
}

.sharedfs-create-owner {
  flex: 0 0 auto;
  margin-bottom: 12px;
  color: inherit;
  border: 1px solid rgba(127, 127, 127, 0.24);
  border-radius: 6px;
  background: transparent;

  :deep(.ant-collapse-item) {
    border-bottom: 0;
  }

  :deep(.ant-collapse-header) {
    color: inherit;
    font-weight: 600;
    background: rgba(127, 127, 127, 0.06);
  }

  :deep(.ant-collapse-content) {
    color: inherit;
    border-top-color: rgba(127, 127, 127, 0.24);
    background: rgba(127, 127, 127, 0.025);
  }

  :deep(.ant-collapse-content-box) {
    padding: 12px 16px 16px;
  }

  :deep(.ant-form-item-label > label),
  :deep(.ant-select),
  :deep(.ant-select-selection-item),
  :deep(.ant-select-arrow) {
    color: inherit;
  }
}

.form {
  width: 100%;
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;

  :deep(.tooltip-icon) {
    margin-left: 4px;
    color: #409eff;
  }
}

.section-alert {
  flex: 0 0 auto;
  margin-bottom: 16px;
  color: inherit;
  border-color: rgba(127, 127, 127, 0.28);
  background: rgba(64, 158, 255, 0.12);

  :deep(.ant-alert-message),
  :deep(.ant-alert-description) {
    color: inherit;
  }

  :deep(.ant-alert-icon) {
    color: #409eff;
  }
}

:deep(.ant-alert-warning) {
  color: inherit;
  border-color: rgba(250, 173, 20, 0.46);
  background: rgba(250, 173, 20, 0.12);
}

:deep(.ant-alert-warning .ant-alert-icon) {
  color: #faad14;
}

.sharedfs-create-layout {
  flex: 1 1 auto;
  display: grid;
  grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
  gap: 16px;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.sharedfs-create-summary,
.sharedfs-create-config {
  min-height: 0;
  height: 100%;
  overflow: auto;
}

.sharedfs-create-config {
  padding-right: 4px;
}

.summary-panel,
.service-section {
  color: inherit;
  border: 1px solid rgba(127, 127, 127, 0.24);
  border-radius: 6px;
  background: rgba(127, 127, 127, 0.06);
}

.summary-panel {
  position: sticky;
  top: 0;
  padding: 16px;
}

.summary-panel__title {
  margin-bottom: 12px;
  font-weight: 600;
  color: inherit;
}

.summary-panel__services {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 16px;
}

.summary-panel__empty {
  color: rgba(127, 127, 127, 0.95);
}

.sharedfs-create-sections {
  color: inherit;
  border-color: rgba(127, 127, 127, 0.24);
  background: transparent;

  :deep(.ant-collapse-item) {
    border-color: rgba(127, 127, 127, 0.24);
  }

  :deep(.ant-collapse-header) {
    color: inherit;
    background: rgba(127, 127, 127, 0.06);
  }

  :deep(.ant-collapse-content) {
    color: inherit;
    border-color: rgba(127, 127, 127, 0.24);
    background: rgba(127, 127, 127, 0.025);
  }

  :deep(.ant-collapse-content-box) {
    padding-bottom: 8px;
  }

  :deep(.ant-form-item-label > label),
  :deep(.ant-checkbox-wrapper),
  :deep(.ant-radio-wrapper),
  :deep(.ant-switch + span) {
    color: inherit;
  }

  :deep(.ant-radio-wrapper span),
  :deep(.ant-radio + span) {
    color: inherit;
  }

  :deep(.ant-radio-wrapper-disabled),
  :deep(.ant-radio-wrapper-disabled span),
  :deep(.ant-radio-disabled + span) {
    color: rgba(127, 127, 127, 0.95);
  }
}

.sharedfs-network-settings {
  margin-bottom: 16px;
  padding: 14px 16px 2px;
  color: inherit;
  border: 1px solid rgba(127, 127, 127, 0.24);
  border-radius: 6px;
  background: rgba(127, 127, 127, 0.045);

  :deep(.ant-form-item) {
    margin-bottom: 14px;
  }
}

.sharedfs-network-alert {
  margin-bottom: 16px;
  color: inherit;
  border-color: rgba(64, 158, 255, 0.34);
  background: rgba(64, 158, 255, 0.1);

  :deep(.ant-alert-message) {
    color: inherit;
    line-height: 1.55;
  }
}

.sharedfs-network-mode {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;

  :deep(.ant-radio-wrapper),
  :deep(.ant-radio-wrapper span),
  :deep(.ant-radio + span) {
    margin-right: 0;
    color: rgba(0, 0, 0, 0.85);
  }

  :deep(.ant-radio-wrapper-disabled),
  :deep(.ant-radio-wrapper-disabled span),
  :deep(.ant-radio-disabled + span) {
    color: rgba(0, 0, 0, 0.42);
  }
}

:global(.dark-mode .sharedfs-create-dialog .sharedfs-network-settings) {
  border-color: rgba(255, 255, 255, 0.16);
  background: rgba(255, 255, 255, 0.035);
}

:global(.dark-mode .sharedfs-create-dialog .sharedfs-network-mode .ant-radio-wrapper),
:global(.dark-mode .sharedfs-create-dialog .sharedfs-network-mode .ant-radio-wrapper span),
:global(.dark-mode .sharedfs-create-dialog .sharedfs-network-mode .ant-radio + span) {
  color: rgba(255, 255, 255, 0.88) !important;
}

:global(.dark-mode .sharedfs-create-dialog .sharedfs-network-mode .ant-radio-wrapper-disabled),
:global(.dark-mode .sharedfs-create-dialog .sharedfs-network-mode .ant-radio-wrapper-disabled span),
:global(.dark-mode .sharedfs-create-dialog .sharedfs-network-mode .ant-radio-disabled + span) {
  color: rgba(255, 255, 255, 0.48) !important;
}

:global(.dark-mode .sharedfs-create-dialog .sharedfs-network-alert) {
  color: rgba(220, 236, 252, 0.94);
  border-color: rgba(64, 169, 255, 0.35);
  background: rgba(24, 144, 255, 0.12);
}

:global(.dark-mode .sharedfs-create-dialog .sharedfs-network-alert .ant-alert-message),
:global(.dark-mode .sharedfs-create-dialog .sharedfs-network-alert .ant-alert-icon) {
  color: rgba(220, 236, 252, 0.94) !important;
}

.smb-identity-radio,
.nfs-protocol-mode-radio {
  :deep(.ant-radio-wrapper),
  :deep(.ant-radio-wrapper span),
  :deep(.ant-radio + span) {
    color: rgba(0, 0, 0, 0.65);
  }
}

:global(.dark-mode .sharedfs-create-dialog .smb-identity-radio .ant-radio-wrapper),
:global(.dark-mode .sharedfs-create-dialog .smb-identity-radio .ant-radio-wrapper span),
:global(.dark-mode .sharedfs-create-dialog .smb-identity-radio .ant-radio + span),
:global(.dark-mode .sharedfs-create-dialog .nfs-protocol-mode-radio .ant-radio-wrapper),
:global(.dark-mode .sharedfs-create-dialog .nfs-protocol-mode-radio .ant-radio-wrapper span),
:global(.dark-mode .sharedfs-create-dialog .nfs-protocol-mode-radio .ant-radio + span) {
  color: rgba(255, 255, 255, 0.84) !important;
}

:global(.dark-mode .sharedfs-create-dialog .smb-identity-radio .ant-radio-wrapper-disabled),
:global(.dark-mode .sharedfs-create-dialog .smb-identity-radio .ant-radio-wrapper-disabled span),
:global(.dark-mode .sharedfs-create-dialog .smb-identity-radio .ant-radio-disabled + span),
:global(.dark-mode .sharedfs-create-dialog .nfs-protocol-mode-radio .ant-radio-wrapper-disabled),
:global(.dark-mode .sharedfs-create-dialog .nfs-protocol-mode-radio .ant-radio-wrapper-disabled span),
:global(.dark-mode .sharedfs-create-dialog .nfs-protocol-mode-radio .ant-radio-disabled + span) {
  color: rgba(255, 255, 255, 0.68) !important;
}

.service-selector {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  width: 100%;
  margin-bottom: 0;
  color: inherit;

  :deep(.ant-checkbox-wrapper) {
    min-height: 72px;
    margin: 0;
    padding: 12px 14px;
    color: inherit;
    border: 1px solid rgba(127, 127, 127, 0.24);
    border-radius: 6px;
    background: rgba(127, 127, 127, 0.045);
  }

  :deep(.ant-checkbox + span) {
    display: flex;
    flex-direction: column;
    gap: 3px;
    color: inherit;
  }
}

.service-selector__title {
  font-weight: 600;
  color: inherit;
}

.service-selector__description {
  font-size: 12px;
  color: rgba(127, 127, 127, 0.95);
}

.service-section {
  padding: 16px;

  h4 {
    margin-bottom: 16px;
    font-weight: 600;
    color: inherit;
  }
}

.service-subsection {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid rgba(127, 127, 127, 0.2);
}

.auth-subsection {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid rgba(127, 127, 127, 0.2);
}

.sharedfs-inline-alert {
  margin-bottom: 16px;
}

:global(.dark-mode .sharedfs-create-dialog .sharedfs-inline-alert) {
  color: rgba(214, 234, 255, 0.94);
  background: rgba(24, 144, 255, 0.12);
  border-color: rgba(64, 169, 255, 0.35);
}

:global(.dark-mode .sharedfs-create-dialog .sharedfs-inline-alert .ant-alert-message),
:global(.dark-mode .sharedfs-create-dialog .sharedfs-inline-alert .ant-alert-icon) {
  color: rgba(214, 234, 255, 0.94);
}

:global(.dark-mode .sharedfs-create-dialog .sharedfs-fixed-value.ant-input-number-disabled),
:global(.dark-mode .sharedfs-create-dialog .sharedfs-fixed-value .ant-input-number-input[disabled]) {
  color: rgba(229, 236, 246, 0.88) !important;
  background: rgba(255, 255, 255, 0.045) !important;
  border-color: rgba(255, 255, 255, 0.16) !important;
}

.field-hint {
  margin-top: 4px;
  color: rgba(127, 127, 127, 0.95);
  font-size: 12px;
  line-height: 1.5;
}

.full-width-input {
  width: 100%;
}

.capacity-input-group {
  display: flex;
  width: 100%;
  align-items: stretch;
  gap: 0;

  :deep(.ant-input-number) {
    flex: 1 1 0;
    width: 100%;
    min-width: 0;
  }

  :deep(.ant-select) {
    flex: 0 0 96px;
  }
}

.review-list {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 3px;
  margin: 0;

  dt {
    min-width: 0;
    margin-top: 10px;
    color: rgba(127, 127, 127, 0.95);
    font-size: 12px;
    line-height: 1.35;
    overflow-wrap: anywhere;

    &:first-child {
      margin-top: 0;
    }
  }

  dd {
    min-width: 0;
    margin: 0 0 2px;
    color: inherit;
    font-weight: 500;
    line-height: 1.45;
    overflow-wrap: anywhere;
    word-break: break-word;
  }
}

.review-service {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid rgba(127, 127, 127, 0.2);
}

.review-service__title {
  margin-bottom: 8px;
  font-weight: 600;
  color: inherit;
}

.action-button {
  flex: 0 0 auto;
  z-index: 2;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
  padding: 12px 0 0;
  border-top: 1px solid rgba(127, 127, 127, 0.24);
  background: inherit;
}

@media (max-width: 900px) {
  .sharedfs-create-layout {
    grid-template-columns: 1fr;
    overflow: auto;
  }

  .sharedfs-create-summary,
  .sharedfs-create-config {
    max-height: none;
    overflow: visible;
  }

  .service-selector {
    grid-template-columns: 1fr;
  }
}
</style>
