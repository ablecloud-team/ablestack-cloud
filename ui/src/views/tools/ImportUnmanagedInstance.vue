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
  <div>
    <a-spin :spinning="loading" v-ctrl-enter="handleSubmit">
      <a-row :gutter="12">
        <a-col :md="24" :lg="7" v-if="!isDiskImport">
          <info-card
            class="vm-info-card"
            :isStatic="true"
            :resource="resource"
            :title="$t('label.unmanaged.instance')" />
        </a-col>
        <a-col :md="24" :lg="17">
          <a-card class="import-form-card" :bordered="true">
            <a-form
              :ref="formRef"
              :model="form"
              :rules="rules"
              @finish="handleSubmit"
              layout="vertical">
              <div class="import-form-scroll">
                <section class="import-form-section">
                  <div class="import-form-section-header">
                    <profile-outlined class="import-form-section-icon" />
                    <span>{{ $t('label.details') }}</span>
                  </div>
                  <div class="import-form-section-body">
              <a-alert
                v-if="selectedVmwareVcenter && isVmRunning"
                class="import-form-alert"
                type="warning"
                :showIcon="true"
                :message="$t('message.import.running.instance.warning')"
              />
              <a-form-item name="displayname" ref="displayname">
                <template #label>
                  <tooltip-label :title="$t('label.displayname')" :tooltip="apiParams.displayname.description"/>
                </template>
                <a-input
                  v-model:value="form.displayname"
                  :placeholder="apiParams.displayname.description"
                  ref="displayname"
                  v-focus="true" />
              </a-form-item>
              <a-form-item name="hostname" ref="hostname">
                <template #label>
                  <tooltip-label :title="$t('label.hostnamelabel')" :tooltip="apiParams.hostname.description"/>
                </template>
                <a-input
                  v-model:value="form.hostname"
                  :placeholder="apiParams.hostname.description" />
              </a-form-item>
                  </div>
                </section>
                <section class="import-form-section">
                  <div class="import-form-section-header">
                    <team-outlined class="import-form-section-icon" />
                    <span>{{ $t('label.project') }}</span>
                  </div>
                  <div class="import-form-section-body">
              <a-form-item name="domainid" ref="domainid">
                <template #label>
                  <tooltip-label :title="$t('label.domainid')" :tooltip="apiParams.domainid.description"/>
                </template>
                <a-select
                  v-model:value="form.domainid"
                  showSearch
                  optionFilterProp="label"
                  :filterOption="(input, option) => {
                    return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                  }"
                  :loading="optionsLoading.domains"
                  :placeholder="apiParams.domainid.description"
                  @change="val => { this.selectedDomainId = val }">
                  <a-select-option v-for="dom in domainSelectOptions" :key="dom.value" :label="dom.label">
                    <span>
                      <resource-icon v-if="dom.icon" :image="dom.icon" size="1x" style="margin-right: 5px"/>
                      <block-outlined v-else-if="dom.value !== null" style="margin-right: 5px" />
                      {{ dom.label }}
                    </span>
                  </a-select-option>
                </a-select>
              </a-form-item>
              <a-form-item name="account" ref="account" v-if="selectedDomainId">
                <template #label>
                  <tooltip-label :title="$t('label.account')" :tooltip="apiParams.account.description"/>
                </template>
                <a-input
                  v-model:value="form.account"
                  :placeholder="apiParams.account.description"/>
              </a-form-item>
              <a-form-item name="projectid" ref="projectid">
                <template #label>
                  <tooltip-label :title="$t('label.project')" :tooltip="apiParams.projectid.description"/>
                </template>
                <a-select
                  v-model:value="form.projectid"
                  showSearch
                  optionFilterProp="label"
                  :filterOption="(input, option) => {
                    return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                  }"
                  :loading="optionsLoading.projects"
                  :placeholder="apiParams.projectid.description">
                  <a-select-option v-for="proj in projectSelectOptions" :key="proj.value" :label="proj.label">
                    <span>
                      <resource-icon v-if="proj.icon" :image="proj.icon" size="1x" style="margin-right: 5px"/>
                      <project-outlined  v-else-if="proj.value !== null" style="margin-right: 5px" />
                      {{ proj.label }}
                    </span>
                  </a-select-option>
                </a-select>
              </a-form-item>
                  </div>
                </section>
                <section class="import-form-section import-options-section">
                  <div class="import-form-section-header">
                    <cloud-server-outlined class="import-form-section-icon" />
                    <span>{{ $t('label.import.instance') }}</span>
                  </div>
                  <div class="import-form-section-body import-options-section-body">
              <a-form-item name="templateid" ref="templateid" v-if="cluster.hypervisortype === 'VMware' || (cluster.hypervisortype === 'KVM' && !selectedVmwareVcenter && !isDiskImport && !isExternalImport)">
                <template #label>
                  <tooltip-label :title="$t('label.templatename')" :tooltip="apiParams.templateid.description + '. ' + $t('message.template.import.vm.temporary')"/>
                </template>
                <a-radio-group
                  style="width:100%"
                  :value="templateType"
                  @change="changeTemplateType">
                  <a-row :gutter="12">
                    <a-col :md="24" :lg="12" v-if="cluster.hypervisortype === 'VMware' || (cluster.hypervisortype === 'KVM' && !selectedVmwareVcenter && !isDiskImport && !isExternalImport)">
                      <a-radio value="auto">
                        {{ $t('label.template.temporary.import') }}
                      </a-radio>
                    </a-col>
                    <a-col :md="24" :lg="12">
                      <a-radio value="custom">
                        {{ $t('label.template.select.existing') }}
                      </a-radio>
                      <a-select
                        :disabled="templateType === 'auto'"
                        style="margin-top:10px"
                        v-model:value="form.templateid"
                        showSearch
                        optionFilterProp="label"
                        :filterOption="(input, option) => {
                          return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                        }"
                        :loading="optionsLoading.templates"
                        :placeholder="apiParams.templateid.description">
                        <a-select-option v-for="temp in templateSelectOptions" :key="temp.value" :label="temp.label">
                          <span>
                            <resource-icon v-if="temp.icon" :image="temp.icon" size="1x" style="margin-right: 5px"/>
                            <os-logo v-else-if="temp.value !== null" :osId="temp.ostypeid" :osName="temp.ostypename" size="lg" style="margin-left: -1px" />
                            {{ temp.label }}
                          </span>
                        </a-select-option>
                      </a-select>
                    </a-col>
                  </a-row>
                </a-radio-group>
              </a-form-item>
              <a-form-item v-if="showAblestackV2KModeSelector" name="useablestackv2k" ref="useablestackv2k">
                <template #label>
                  <tooltip-label
                    :title="$t('label.ablestack.v2k.use')"
                    :tooltip="apiParams.useablestackv2k?.description || $t('message.select.ablestack.v2k.primary.storage.migration')"/>
                </template>
                <a-switch
                  v-model:checked="form.useablestackv2k"
                  @change="onAblestackV2KModeChange" />
              </a-form-item>
              <a-alert
                v-if="isAblestackN2KImport"
                class="ablestack-import-alert"
                type="info"
                :showIcon="true"
                :message="$t('label.ablestack.n2k.use')"
                :description="$t('message.select.ablestack.n2k.primary.storage.migration')" />
              <a-form-item v-if="isAblestackN2KImport" name="starttargetvm" ref="starttargetvm">
                <template #label>
                  <tooltip-label
                    :title="$t('label.n2k.start.target.vm')"
                    :tooltip="$t('message.n2k.start.target.vm')" />
                </template>
                <a-switch v-model:checked="form.starttargetvm" />
              </a-form-item>
              <a-form-item v-if="isAblestackN2KImport" name="n2kretentiondays" ref="n2kretentiondays">
                <template #label>
                  <tooltip-label
                    :title="$t('label.n2k.snapshot.retention.days')"
                    :tooltip="$t('message.n2k.snapshot.retention.days')" />
                </template>
                <a-input-number
                  v-model:value="form.n2kretentiondays"
                  :min="1"
                  :max="365"
                  :precision="0"
                  style="width: 100%" />
              </a-form-item>
              <a-form-item name="usevddk" ref="usevddk" v-if="showVmwareConversionOptions">
                <template #label>
                  <tooltip-label :title="$t('label.use.vddk')" :tooltip="apiParams.usevddk ? apiParams.usevddk.description : ''"/>
                </template>
                <a-switch v-model:checked="form.usevddk" @change="onUseVddkChange" />
              </a-form-item>
              <a-form-item name="forceconverttopool" ref="forceconverttopool" v-if="showVmwareConversionOptions">
                <template #label>
                  <tooltip-label :title="$t('label.force.convert.to.pool')" :tooltip="apiParams.forceconverttopool.description"/>
                </template>
                <a-switch v-model:checked="form.forceconverttopool" @change="onForceConvertToPoolChange" />
              </a-form-item>
              <a-form-item name="converthostid" ref="converthostid" v-if="showVmwareConversionOptions">
                <check-box-select-pair
                  layout="vertical"
                  :resourceKey="cluster.id"
                  :selectOptions="kvmHostsForConversion"
                  :checkBoxLabel="$t('message.select.kvm.host.instance.conversion')"
                  :defaultCheckBoxValue="false"
                  :reversed="false"
                  @handle-checkselectpair-change="updateSelectedKvmHostForConversion"
                />
              </a-form-item>
              <a-form-item name="ablestackconverthostid" ref="ablestackconverthostid" v-if="showAblestackCloudMigrationOptions">
                <check-box-select-pair
                  layout="vertical"
                  :resourceKey="cluster.id"
                  :selectOptions="kvmHostsForConversion"
                  :checkBoxLabel="$t('message.select.kvm.host.ablestack.import')"
                  :defaultCheckBoxValue="false"
                  :reversed="false"
                  @handle-checkselectpair-change="updateSelectedKvmHostForConversion"
                />
              </a-form-item>
              <a-form-item name="importhostid" ref="importhostid" v-if="!form.usevddk && showVmwareConversionOptions">
                <check-box-select-pair
                  layout="vertical"
                  :resourceKey="cluster.id"
                  :selectOptions="kvmHostsForImporting"
                  :checkBoxLabel="$t('message.select.kvm.host.instance.import')"
                  :defaultCheckBoxValue="false"
                  :reversed="false"
                  @handle-checkselectpair-change="updateSelectedKvmHostForImporting"
                />
              </a-form-item>
              <a-form-item name="convertstorageoption" ref="convertstorageoption" v-if="showVmwareConversionOptions">
                <check-box-select-pair
                  :key="`convertstorageoption-${form.usevddk ? 'vddk' : 'default'}-${switches.forceConvertToPool ? 'pool' : 'tmp'}`"
                  layout="vertical"
                  :resourceKey="cluster.id"
                  :selectOptions="storageOptionsForConversion"
                  :checkBoxLabel="switches.forceConvertToPool ? $t('message.select.destination.storage.instance.conversion') : $t('message.select.temporary.storage.instance.conversion')"
                  :defaultCheckBoxValue="switches.forceConvertToPool"
                  :reversed="false"
                  @handle-checkselectpair-change="updateSelectedStorageOptionForConversion"
                />
              </a-form-item>
              <a-form-item name="ablestacktargetstorageoption" ref="ablestacktargetstorageoption" v-if="showAblestackCloudMigrationOptions">
                <check-box-select-pair
                  layout="vertical"
                  :resourceKey="cluster.id"
                  :selectOptions="ablestackStorageOptionsForConversion"
                  :checkBoxLabel="$t('message.select.primary.storage.ablestack.import')"
                  :defaultCheckBoxValue="false"
                  :reversed="false"
                  @handle-checkselectpair-change="updateSelectedStorageOptionForConversion"
                />
              </a-form-item>
              <a-form-item
                v-if="showStoragePoolsForConversion && (showVmwareConversionOptions || showAblestackCloudMigrationOptions)"
                name="convertstoragepool"
                ref="convertstoragepool"
                :label="$t('label.storagepool')"
              >
                <a-select
                  v-model:value="form.convertstoragepoolid"
                  defaultActiveFirstOption
                  showSearch
                  optionFilterProp="label"
                  :filterOption="(input, option) => {
                    return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                  }"
                  @change="val => { selectedStoragePoolForConversion = val }">
                  <a-select-option v-for="(pool) in storagePoolsForConversion" :key="pool.id" :label="pool.name">
                    {{ pool.name }}
                  </a-select-option>
                </a-select>
              </a-form-item>
              <a-form-item v-if="showAblestackCloudMigrationOptions" name="ablestacktargetpreview" ref="ablestacktargetpreview">
                <a-space direction="vertical" style="width: 100%">
                  <a-space>
                    <a-button
                      v-if="isAblestackN2KImport"
                      size="small"
                      :loading="preflightLoading"
                      @click="runAblestackN2KPreflight">
                      {{ $t('label.preflight') }}
                    </a-button>
                    <a-tag v-if="storagePlanPreview.targetStorage" color="blue">
                      {{ storagePlanPreview.targetStorage }} / {{ storagePlanPreview.targetFormat }}
                    </a-tag>
                    <a-tag v-if="selectedTargetStoragePool">
                      {{ selectedTargetStoragePool.name }}
                    </a-tag>
                  </a-space>
                  <a-alert
                    v-if="preflightResult"
                    :type="preflightResult.success ? 'success' : 'warning'"
                    :showIcon="true"
                    :message="preflightResult.message"
                    :description="preflightResultDescription" />
                </a-space>
              </a-form-item>
              <a-form-item name="extraparams" ref="extraparams" v-if="showVmwareConversionOptions && (vmwareToKvmExtraParamsAllowed || vmwareToKvmExtraParamsSelected)">
                <a-checkbox
                  v-if="vmwareToKvmExtraParamsAllowed"
                  v-model:checked="vmwareToKvmExtraParamsSelected">
                  {{ $t('message.select.extra.parameters.for.instance.conversion') }}
                </a-checkbox>
                <a-input
                  v-if="vmwareToKvmExtraParamsSelected"
                  v-model:value="vmwareToKvmExtraParams"
                  :placeholder="$t('label.extra')"
                />
              </a-form-item>
              <a-form-item name="forcemstoimportvmfiles" ref="forcemstoimportvmfiles" v-if="showVmwareConversionOptions && !form.usevddk">
                <template #label>
                  <tooltip-label :title="$t('label.force.ms.to.import.vm.files')" :tooltip="apiParams.forcemstoimportvmfiles.description"/>
                </template>
                <a-switch v-model:checked="form.forcemstoimportvmfiles" @change="val => { switches.forceMsToImportVmFiles = val }" />
              </a-form-item>
              <a-form-item name="osid" ref="osid" v-if="selectedVmwareVcenter">
                <template #label>
                  <tooltip-label :title="$t('label.guest.os')" :tooltip="$t('label.select.guest.os.type')"/>
                </template>
                <a-spin v-if="loadingGuestOsMappings" />
                <template v-else>
                  <a-select
                    v-if="resource.guestOsMappings && resource.guestOsMappings.length > 0"
                    v-model:value="form.osid"
                    showSearch
                    optionFilterProp="label"
                    :filterOption="(input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }">
                    <a-select-option v-for="mapping in resource.guestOsMappings" :key="mapping.ostypeid" :label="mapping.osdisplayname">
                      <span>
                        {{ mapping.osdisplayname }}
                      </span>
                    </a-select-option>
                  </a-select>
                  <a-span v-else>{{ $t('label.no.matching.guest.os.vmware.import') }}</a-span>
                </template>
              </a-form-item>
                  </div>
                </section>
                <section class="import-form-section">
                  <div class="import-form-section-header">
                    <control-outlined class="import-form-section-icon" />
                    <span>{{ $t('label.compute') }}</span>
                  </div>
                  <div class="import-form-section-body">
              <a-form-item name="serviceofferingid" ref="serviceofferingid">
                <template #label>
                  <tooltip-label :title="$t('label.serviceofferingid')" :tooltip="apiParams.serviceofferingid.description"/>
                </template>
                <compute-offering-selection
                  :compute-items="computeOfferings"
                  :loading="computeOfferingLoading"
                  :rowCount="totalComputeOfferings"
                  :value="computeOffering ? computeOffering.id : ''"
                  :minimumCpunumber="isVmRunning ? resource.cpunumber : null"
                  :minimumCpuspeed="isVmRunning ? resource.cpuspeed : null"
                  :minimumMemory="isVmRunning ? resource.memory : null"
                  :allowAllOfferings="selectedVmwareVcenter ? true : false"
                  size="small"
                  @select-compute-item="($event) => updateComputeOffering($event)"
                  @handle-search-filter="($event) => fetchComputeOfferings($event)" />
                <compute-selection
                  class="row-element"
                  v-if="computeOffering && (computeOffering.iscustomized || computeOffering.iscustomizediops)"
                  :isCustomized="computeOffering.iscustomized"
                  :isCustomizedIOps="'iscustomizediops' in computeOffering && computeOffering.iscustomizediops"
                  :cpuNumberInputDecorator="cpuNumberKey"
                  :cpuSpeedInputDecorator="cpuSpeedKey"
                  :memoryInputDecorator="memoryKey"
                  :computeOfferingId="computeOffering.id"
                  :preFillContent="resource"
                  :isConstrained="isOfferingConstrained(computeOffering)"
                  :minCpu="getMinCpu()"
                  :maxCpu="getMaxCpu()"
                  :minMemory="getMinMemory()"
                  :maxMemory="getMaxMemory()"
                  :cpuSpeed="getCPUSpeed()"
                  @update-iops-value="updateFieldValue"
                  @update-compute-cpunumber="updateFieldValue"
                  @update-compute-cpuspeed="updateCpuSpeed"
                  @update-compute-memory="updateFieldValue" />
              </a-form-item>
                  </div>
                </section>
                <section class="import-form-section" v-if="resourceDisks.length > 1">
                  <div class="import-form-section-header">
                    <hdd-outlined class="import-form-section-icon" />
                    <span>{{ $t('label.disk.selection') }}</span>
                  </div>
                  <div class="import-form-section-body">
              <div v-if="resourceDisks.length > 1">
                <a-form-item name="selection" ref="selection">
                  <template #label>
                    <tooltip-label :title="$t('label.disk.selection')" :tooltip="apiParams.datadiskofferinglist.description"/>
                  </template>
                </a-form-item>
                <a-form-item name="rootdiskid" ref="rootdiskid" :label="$t('label.select.root.disk')">
                  <a-select
                    v-model:value="form.rootdiskid"
                    defaultActiveFirstOption
                    showSearch
                    optionFilterProp="label"
                    :filterOption="(input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }"
                    @change="onSelectRootDisk">
                    <a-select-option v-for="(opt, optIndex) in resourceDisks" :key="optIndex" :label="opt.label || opt.id">
                      {{ opt.label || opt.id }}
                    </a-select-option>
                  </a-select>
                  <a-table
                    :columns="selectedRootDiskColumns"
                    :dataSource="selectedRootDiskSources"
                    :pagination="false">
                    <template #bodyCell="{ column, record }">
                      <template v-if="column.key === 'name'">
                        <span>{{ record.displaytext || record.name }}</span>
                        <div v-if="record.meta">
                          <div v-for="meta in record.meta" :key="meta.key">
                            <a-tag style="margin-top: 5px" :key="meta.key">{{ meta.key + ': ' + meta.value }}</a-tag>
                          </div>
                        </div>
                      </template>
                    </template>
                  </a-table>
                </a-form-item>
                <multi-disk-selection
                  :items="dataDisks"
                  :zoneId="cluster.zoneid"
                  :selectionEnabled="false"
                  :customOfferingsAllowed="true"
                  :autoSelectCustomOffering="true"
                  :isKVMUnmanage="isKVMUnmanage"
                  :autoSelectLabel="$t('label.auto.assign.diskoffering.disk.size')"
                  @select-multi-disk-offering="updateMultiDiskOffering" />
              </div>
                  </div>
                </section>
                <section class="import-form-section" v-if="(resource.nic && resource.nic.length > 0) || isDiskImport || (!isExternalImport && !isDiskImport) || (!selectedVmwareVcenter && !isDiskImport)">
                  <div class="import-form-section-header">
                    <deployment-unit-outlined class="import-form-section-icon" />
                    <span>{{ $t('label.network.selection') }}</span>
                  </div>
                  <div class="import-form-section-body">
              <div v-if="resource.nic && resource.nic.length > 0">
                <a-form-item name="networkselection" ref="networkselection">
                  <template #label>
                    <tooltip-label :title="$t('label.network.selection')" :tooltip="apiParams.nicnetworklist.description"/>
                  </template>
                  <span>{{ $t('message.ip.address.changes.effect.after.vm.restart') }}</span>
                </a-form-item>
                <a-alert
                  v-if="isAblestackN2KImport && resource.nic && resource.nic.length > 0"
                  class="import-form-alert ablestack-import-alert"
                  type="info"
                  :showIcon="true"
                  :message="$t('message.ablestack.n2k.preserve.source.mac')" />
                <a-row v-if="selectedVmwareVcenter" :gutter="12" justify="end">
                  <a-col style="text-align: right">
                    <a-form-item name="forced" ref="forced">
                      <template #label>
                        <tooltip-label
                          :title="$t('label.allow.duplicate.macaddresses')"
                          :tooltip="apiParams.forced.description"/>
                      </template>
                      <a-switch v-model:checked="form.forced" @change="val => { switches.forced = val }" />
                    </a-form-item>
                  </a-col>
                </a-row>
                <multi-network-selection
                  :items="nics"
                  :zoneId="cluster.zoneid"
                  :domainid="form.domainid"
                  :account="form.account"
                  :selectionEnabled="false"
                  :filterUnimplementedNetworks="true"
                  :hypervisor="this.cluster.hypervisortype"
                  :filterMatchKey="isKVMUnmanage ? undefined : 'broadcasturi'"
                  @select-multi-network="updateMultiNetworkOffering" />
              </div>
              <a-row v-else style="margin: 12px 0" >
                <div v-if="!isExternalImport && !isDiskImport">
                  <a-alert type="warning">
                    <template #message>
                      <div v-html="$t('message.warn.importing.instance.without.nic')"></div>
                    </template>
                  </a-alert>
                </div>
              </a-row>
              <div v-if="isDiskImport">
                <a-form-item name="networkid" ref="networkid">
                  <template #label>
                    <tooltip-label :title="$t('label.network')"/>
                  </template>
                  <a-select
                    v-model:value="form.networkid"
                    showSearch
                    optionFilterProp="label"
                    :filterOption="(input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }"
                    :loading="optionsLoading.networks">
                    <a-select-option v-for="network in networkSelectOptions" :key="network.value" :label="network.label">
                      <span>
                        {{ network.label }}
                      </span>
                    </a-select-option>
                  </a-select>
                </a-form-item>
              </div>
              <a-row v-if="!selectedVmwareVcenter" :gutter="12">
                <a-col :md="24" :lg="12">
                  <a-form-item name="migrateallowed" ref="migrateallowed">
                    <template #label>
                      <tooltip-label :title="$t('label.migrate.allowed')" :tooltip="apiParams.migrateallowed.description"/>
                    </template>
                    <a-switch v-model:checked="form.migrateallowed" @change="val => { switches.migrateAllowed = val }" />
                  </a-form-item>
                </a-col>
                <a-col>
                  <a-form-item name="forced" ref="forced">
                    <template #label>
                      <tooltip-label
                        :title="$t('label.forced')"
                        :tooltip="apiParams.forced.description"/>
                    </template>
                    <a-switch v-model:checked="form.forced" @change="val => { switches.forced = val }" />
                  </a-form-item>
                </a-col>
              </a-row>
                  </div>
                </section>
              </div>
              <div :span="24" class="action-button import-form-actions">
                <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
                <a-button :loading="loading" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
              </div>
            </a-form>
          </a-card>
        </a-col>
      </a-row>
    </a-spin>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import _ from 'lodash'
import InfoCard from '@/components/view/InfoCard'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import ComputeOfferingSelection from '@views/compute/wizard/ComputeOfferingSelection'
import ComputeSelection from '@views/compute/wizard/ComputeSelection'
import MultiDiskSelection from '@views/compute/wizard/MultiDiskSelection'
import MultiNetworkSelection from '@views/compute/wizard/MultiNetworkSelection'
import OsLogo from '@/components/widgets/OsLogo'
import ResourceIcon from '@/components/view/ResourceIcon'
import CheckBoxSelectPair from '@/components/CheckBoxSelectPair'

export default {
  name: 'ImportUnmanagedInstances',
  components: {
    InfoCard,
    TooltipLabel,
    ComputeOfferingSelection,
    ComputeSelection,
    MultiDiskSelection,
    MultiNetworkSelection,
    OsLogo,
    ResourceIcon,
    CheckBoxSelectPair
  },
  props: {
    wizard: { type: Boolean, default: false },
    sourceidentity: { type: String, default: undefined },
    cluster: {
      type: Object,
      required: true
    },
    host: {
      type: Object,
      required: true
    },
    pool: {
      type: Object,
      required: true
    },
    resource: {
      type: Object,
      required: true
    },
    isOpen: {
      type: Boolean,
      required: false
    },
    zoneid: {
      type: String,
      required: false
    },
    importsource: {
      type: String,
      required: false
    },
    hypervisor: {
      type: String,
      required: false
    },
    exthost: {
      type: String,
      required: false
    },
    username: {
      type: String,
      required: false
    },
    password: {
      type: String,
      required: false
    },
    tmppath: {
      type: String,
      required: false
    },
    diskpath: {
      type: String,
      required: false
    },
    selectedVmwareVcenter: {
      type: [Array, Object],
      required: false
    },
    sourceprovider: {
      type: String,
      required: false
    },
    sourceapi: {
      type: String,
      required: false,
      default: 'v3'
    },
    insecure: {
      type: Boolean,
      required: false,
      default: true
    },
    loadingGuestOsMappings: {
      type: Boolean,
      required: false
    }
  },
  data () {
    return {
      options: {
        domains: [],
        projects: [],
        networks: [],
        templates: []
      },
      rowCount: {},
      optionsLoading: {
        domains: false,
        projects: false,
        networks: false,
        templates: false
      },
      domains: [],
      domainLoading: false,
      selectedDomainId: null,
      templates: [],
      templateLoading: false,
      templateType: this.defaultTemplateType(),
      totalComputeOfferings: 0,
      computeOfferings: [],
      computeOfferingLoading: false,
      computeOffering: {},
      selectedRootDiskIndex: 0,
      dataDisksOfferingsMapping: {},
      nicsNetworksMapping: {},
      cpuNumberKey: 'cpuNumber',
      cpuSpeedKey: 'cpuSpeed',
      memoryKey: 'memory',
      minIopsKey: 'minIops',
      maxIopsKey: 'maxIops',
      switches: {},
      loading: false,
      kvmHostsForConversion: [],
      kvmHostsForImporting: [],
      selectedKvmHostForConversion: null,
      selectedKvmHostForImporting: null,
      storageOptionsForConversion: [
        {
          id: 'secondary',
          name: this.$t('label.secondary.storage')
        }, {
          id: 'primary',
          name: this.$t('label.primary.storage')
        }
      ],
      ablestackStorageOptionsForConversion: [
        {
          id: 'primary',
          name: this.$t('label.primary.storage')
        }
      ],
      storagePoolsForConversion: [],
      selectedStorageOptionForConversion: null,
      selectedStoragePoolForConversion: null,
      showStoragePoolsForConversion: false,
      selectedRootDiskColumns: [
        {
          key: 'name',
          dataIndex: 'name',
          title: this.$t('label.rootdisk')
        }
      ],
      selectedRootDiskSources: [],
      vmwareToKvmExtraParamsAllowed: false,
      vmwareToKvmExtraParamsSelected: false,
      vmwareToKvmExtraParams: '',
      userModifiedVddkSetting: false,
      preflightLoading: false,
      preflightResult: null
    }
  },
  beforeCreate () {
    this.apiConfig = this.$store.getters.apis.importUnmanagedInstance || {}
    this.apiParams = {}
    this.apiConfig.params.forEach(param => {
      this.apiParams[param.name] = param
    })
    this.apiConfig = this.$store.getters.apis.importVm || {}
    this.apiConfig.params.forEach(param => {
      if (!(param.name in this.apiParams)) {
        this.apiParams[param.name] = param
      }
    })
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  computed: {
    showAblestackV2KModeSelector () {
      return this.cluster.hypervisortype === 'KVM' && this.selectedVmwareVcenter
    },
    isAblestackN2KImport () {
      return this.cluster.hypervisortype === 'KVM' && this.sourceprovider === 'nutanix'
    },
    showAblestackCloudMigrationOptions () {
      return (this.cluster.hypervisortype === 'KVM' && this.selectedVmwareVcenter && this.form?.useablestackv2k) || this.isAblestackN2KImport
    },
    showVmwareConversionOptions () {
      return this.cluster.hypervisortype === 'KVM' && this.selectedVmwareVcenter && !this.form?.useablestackv2k
    },
    params () {
      return {
        domains: {
          list: 'listDomains',
          isLoad: true,
          field: 'domainid',
          options: {
            details: 'min',
            showicon: true
          }
        },
        projects: {
          list: 'listProjects',
          isLoad: true,
          field: 'projectid',
          options: {
            details: 'min',
            showicon: true
          }
        },
        networks: {
          list: 'listNetworks',
          isLoad: true,
          field: 'networkid',
          options: {
            zoneid: this.zoneid,
            details: 'min'
          }
        },
        templates: {
          list: 'listTemplates',
          isLoad: true,
          options: {
            templatefilter: 'all',
            isready: true,
            hypervisor: this.cluster.hypervisortype,
            showicon: true
          },
          field: 'templateid'
        }
      }
    },
    isVmRunning () {
      if (this.resource && this.resource.powerstate === 'PowerOn') {
        return true
      }
      return false
    },
    isDiskImport () {
      if (this.importsource === 'local' || this.importsource === 'shared') {
        return true
      }
      return false
    },
    isExternalImport () {
      if (this.importsource === 'external') {
        return true
      }
      return false
    },
    isNutanixImport () {
      return this.importsource === 'nutanix' || this.sourceprovider === 'nutanix'
    },
    isKVMUnmanage () {
      return this.hypervisor && this.hypervisor === 'kvm' && (this.importsource === 'unmanaged' || this.importsource === 'external' || this.isNutanixImport)
    },
    domainSelectOptions () {
      var domains = this.options.domains.map((domain) => {
        return {
          label: domain.path || domain.name,
          value: domain.id,
          icon: domain?.icon?.base64image || ''
        }
      })
      domains.unshift({
        label: '',
        value: null
      })
      return domains
    },
    projectSelectOptions () {
      var projects = this.options.projects.map((project) => {
        return {
          label: project.name,
          value: project.id,
          icon: project?.icon?.base64image || ''
        }
      })
      projects.unshift({
        label: '',
        value: null
      })
      return projects
    },
    networkSelectOptions () {
      var networks = this.options.networks.map((network) => {
        return {
          label: network.name + ' (' + network.displaytext + ')',
          value: network.id
        }
      })
      networks.unshift({
        label: '',
        value: null
      })
      return networks
    },
    templateSelectOptions () {
      const uniqueTemplates = []
      const seenTemplateIds = new Set()
      this.options.templates.forEach((template) => {
        const key = template.id || template.name
        if (seenTemplateIds.has(key)) {
          return
        }
        seenTemplateIds.add(key)
        uniqueTemplates.push(template)
      })
      return uniqueTemplates.map((template) => {
        return {
          label: template.name,
          value: template.id,
          icon: template?.icon?.base64image || '',
          ostypeid: template.ostypeid,
          ostypename: template.ostypename
        }
      })
    },
    resourceDisks () {
      if (Array.isArray(this.resource?.disk)) {
        return this.resource.disk
      }
      if (this.resource?.disk && typeof this.resource.disk === 'object') {
        return Object.values(this.resource.disk)
      }
      return []
    },
    dataDisks () {
      var disks = []
      if (this.resourceDisks.length > 1) {
        for (var index = 0; index < this.resourceDisks.length; ++index) {
          if (index !== this.selectedRootDiskIndex) {
            var disk = { ...this.resourceDisks[index] }
            disk.size = this.getDiskSizeGiB(disk.capacity)
            disk.name = disk.label
            disk.meta = this.getMeta(disk, { controller: 'controller', datastorename: 'datastore', position: 'position' })
            disks.push(disk)
          }
        }
      }
      return disks
    },
    nics () {
      var nics = []
      if (this.resource.nic && this.resource.nic.length > 0) {
        for (var nicEntry of this.resource.nic) {
          var nic = { ...nicEntry }
          nic.name = nic.name || nic.id
          nic.displaytext = nic.name
          if (this.isExternalImport && nic.vlanid === -1) {
            delete nic.vlanid
          }
          if (nic.vlanid) {
            nic.broadcasturi = 'vlan://' + nic.vlanid
            if (nic.isolatedpvlan) {
              nic.broadcasturi = 'pvlan://' + nic.vlanid + '-i' + nic.isolatedpvlan
            }
          }
          if (this.cluster.hypervisortype === 'VMware') {
            nic.meta = this.getMeta(nic, { macaddress: 'mac', vlanid: 'vlan', networkname: 'network' })
          } else {
            nic.meta = this.getMeta(nic, { macaddress: 'mac', vlanid: 'vlan' })
          }
          nics.push(nic)
        }
      }
      return nics
    },
    selectedTargetStoragePool () {
      const selectedPoolId = this.form.convertstoragepoolid || this.selectedStoragePoolForConversion
      if (!selectedPoolId || !Array.isArray(this.storagePoolsForConversion)) {
        return null
      }
      return this.storagePoolsForConversion.find(pool => pool.id === selectedPoolId)
    },
    storagePlanPreview () {
      const pool = this.selectedTargetStoragePool
      if (!pool) {
        return {
          targetStorage: 'auto',
          targetFormat: 'auto'
        }
      }
      const poolType = String(pool.type || pool.pooltype || pool.storagetype || '').toLowerCase()
      if (poolType === 'rbd') {
        return {
          targetStorage: 'rbd',
          targetFormat: 'raw'
        }
      }
      if (['sharedmountpoint', 'filesystem', 'networkfilesystem', 'nfs'].includes(poolType)) {
        return {
          targetStorage: 'file',
          targetFormat: 'qcow2'
        }
      }
      return {
        targetStorage: poolType || 'auto',
        targetFormat: this.$t('label.unsupported')
      }
    },
    preflightResultDescription () {
      if (!this.preflightResult) {
        return ''
      }
      const parts = []
      if (this.preflightResult.sourceapi) {
        parts.push(`${this.$t('label.source.api')}: ${this.preflightResult.sourceapi}`)
      }
      if (this.preflightResult.sourcevmcount !== undefined) {
        parts.push(`${this.$t('label.source.vm.count')}: ${this.preflightResult.sourcevmcount}`)
      }
      if (this.preflightResult.targetstorage || this.preflightResult.targetformat) {
        parts.push(`${this.$t('label.target.storage.plan')}: ${this.preflightResult.targetstorage || 'auto'} / ${this.preflightResult.targetformat || 'auto'}`)
      }
      return parts.join(' · ')
    }
  },
  watch: {
    isOpen (newValue) {
      if (newValue) {
        this.resetForm()
        this.$nextTick(() => {
          this.applyDefaultDisplayName(true)
          this.applyDefaultHostname(true)
          const displaynameRef = this.$refs.displayname
          if (displaynameRef && typeof displaynameRef.focus === 'function') {
            displaynameRef.focus()
          }
        })
        this.selectMatchingComputeOffering()
      }
    },
    resource: {
      deep: true,
      handler () {
        if (this.isOpen) {
          this.applyDefaultDisplayName(false)
          this.applyDefaultHostname(false)
        }
      }
    },
    'resource.guestOsMappings' (mappings) {
      if (mappings && mappings.length > 0) {
        this.form.osid = mappings[0].ostypeid
      }
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        rootdiskid: 0,
        usevddk: false,
        migrateallowed: this.switches.migrateAllowed,
        forced: this.switches.forced,
        forcemstoimportvmfiles: this.switches.forceMsToImportVmFiles,
        forceconverttopool: this.switches.forceConvertToPool,
        useablestackv2k: this.defaultUseAblestackV2K(),
        starttargetvm: true,
        n2kretentiondays: 14,
        domainid: null,
        account: null,
        osid: null
      })
      this.rules = reactive({
        displayname: [{ required: true, message: this.$t('message.error.input.value') }],
        templateid: [{ required: this.templateType !== 'auto', message: this.$t('message.error.input.value') }],
        rootdiskid: [{ required: this.templateType !== 'auto', message: this.$t('message.error.input.value') }],
        n2kretentiondays: [{ required: true, type: 'number', min: 1, message: this.$t('message.error.input.value') }]
      })
    },
    fetchData () {
      _.each(this.params, (param, name) => {
        if (param.isLoad) {
          this.fetchOptions(param, name)
        }
      })
      this.fetchComputeOfferings({
        keyword: '',
        pageSize: 10,
        page: 1
      })
      this.fetchKvmHostsForConversion()
      this.fetchKvmHostsForImporting()
      if (this.resourceDisks.length > 1) {
        this.updateSelectedRootDisk()
      }
      this.fetchVmwareToKVMExtraConfigsSetting()
      if (this.showAblestackCloudMigrationOptions) {
        this.resetStorageOptionsForConversion()
      }
    },
    fetchVmwareToKVMExtraConfigsSetting () {
      const params = {
        name: 'convert.vmware.instance.to.kvm.extra.params.allowed'
      }
      getAPI('listConfigurations', params).then(json => {
        if (json.listconfigurationsresponse.configuration !== null) {
          const config = json.listconfigurationsresponse.configuration[0]
          if (config && config.name === params.name) {
            this.vmwareToKvmExtraParamsAllowed = config.value === 'true'
          }
        }
      })
    },
    getMeta (obj, metaKeys) {
      var meta = []
      for (var key in metaKeys) {
        if (key in obj) {
          meta.push({ key: metaKeys[key], value: obj[key] })
        }
      }
      return meta
    },
    getMinCpu () {
      if (this.isVmRunning) {
        return this.resource.cpunumber
      }
      return 'serviceofferingdetails' in this.computeOffering ? this.computeOffering.serviceofferingdetails.mincpunumber * 1 : 1
    },
    getMinMemory () {
      if (this.isVmRunning) {
        return this.resource.memory
      }
      return 'serviceofferingdetails' in this.computeOffering ? this.computeOffering.serviceofferingdetails.minmemory * 1 : 32
    },
    getMaxCpu () {
      if (this.isVmRunning) {
        return this.resource.cpunumber
      }
      return 'serviceofferingdetails' in this.computeOffering ? this.computeOffering.serviceofferingdetails.maxcpunumber * 1 : Number.MAX_SAFE_INTEGER
    },
    getMaxMemory () {
      if (this.isVmRunning) {
        return this.resource.memory
      }
      return 'serviceofferingdetails' in this.computeOffering ? this.computeOffering.serviceofferingdetails.maxmemory * 1 : Number.MAX_SAFE_INTEGER
    },
    getCPUSpeed () {
      if (!this.computeOffering) {
        return 0
      }
      if (this.computeOffering.cpuspeed) {
        return this.computeOffering.cpuspeed * 1
      }
      return this.resource.cpuspeed * 1 || 0
    },
    fetchOptions (param, name, exclude) {
      if (exclude && exclude.length > 0) {
        if (exclude.includes(name)) {
          return
        }
      }
      this.optionsLoading[name] = true
      param.loading = true
      param.opts = []
      const options = param.options || {}
      if (!('listall' in options)) {
        options.listall = true
      }
      getAPI(param.list, options).then((response) => {
        param.loading = false
        _.map(response, (responseItem, responseKey) => {
          if (Object.keys(responseItem).length === 0) {
            this.rowCount[name] = 0
            this.options[name] = []
            return
          }
          if (!responseKey.includes('response')) {
            return
          }
          _.map(responseItem, (response, key) => {
            if (key === 'count') {
              this.rowCount[name] = response
              return
            }
            param.opts = response
            this.options[name] = response
          })
        })
      }).catch(function (error) {
        console.log(error.stack)
        param.loading = false
      }).finally(() => {
        this.optionsLoading[name] = false
      })
    },
    fetchComputeOfferings (options) {
      this.computeOfferingLoading = true
      this.totalComputeOfferings = 0
      this.computeOfferings = []
      this.offeringsMap = []
      getAPI('listServiceOfferings', {
        keyword: options.keyword,
        page: options.page,
        pageSize: options.pageSize,
        details: 'min',
        response: 'json'
      }).then(response => {
        this.totalComputeOfferings = response.listserviceofferingsresponse.count
        if (this.totalComputeOfferings === 0) {
          return
        }
        this.computeOfferings = response.listserviceofferingsresponse.serviceoffering
        this.computeOfferings.map(i => { this.offeringsMap[i.id] = i })
      }).finally(() => {
        this.computeOfferingLoading = false
        this.selectMatchingComputeOffering()
      })
    },
    updateCpuSpeed (name, value) {
      if (this.computeOffering.iscustomized) {
        if (this.computeOffering.serviceofferingdetails) {
          this.updateFieldValue(this.cpuSpeedKey, this.computeOffering.cpuspeed)
          console.log('111objethis.computeOffering.cpuspeedct :>> ', this.computeOffering.cpuspeed)
        } else {
          this.updateFieldValue(this.cpuSpeedKey, value)
          console.log('222value :>> ', value)
        }
      }
    },
    updateFieldValue (name, value) {
      this.form[name] = value
    },
    getDefaultDisplayName () {
      return this.resource?.displayname || this.resource?.name || this.resource?.id || ''
    },
    applyDefaultDisplayName (force = false) {
      const defaultDisplayName = this.getDefaultDisplayName()
      if (!defaultDisplayName) {
        return
      }
      if (!force && this.form.displayname) {
        return
      }
      this.updateFieldValue('displayname', defaultDisplayName)
      if (this.formRef?.value?.setFieldsValue) {
        this.formRef.value.setFieldsValue({ displayname: defaultDisplayName })
      }
    },
    getDefaultHostname () {
      return this.getDefaultDisplayName()
    },
    applyDefaultHostname (force = false) {
      const defaultHostname = this.getDefaultHostname()
      if (!defaultHostname) {
        return
      }
      if (!force && this.form.hostname) {
        return
      }
      this.updateFieldValue('hostname', defaultHostname)
      if (this.formRef?.value?.setFieldsValue) {
        this.formRef.value.setFieldsValue({ hostname: defaultHostname })
      }
    },
    updateComputeOffering (id) {
      this.updateFieldValue('computeofferingid', id)
      this.computeOffering = this.computeOfferings.filter(x => x.id === id)[0]
      if (this.computeOffering && !this.computeOffering.iscustomizediops) {
        this.updateFieldValue(this.minIopsKey, undefined)
        this.updateFieldValue(this.maxIopsKey, undefined)
      }
    },
    updateMultiDiskOffering (data) {
      this.dataDisksOfferingsMapping = data
    },
    updateMultiNetworkOffering (data) {
      this.nicsNetworksMapping = data
    },
    defaultTemplateType () {
      if (this.cluster.hypervisortype === 'VMware') {
        return 'auto'
      }
      if (this.cluster.hypervisortype === 'KVM' && !this.selectedVmwareVcenter && !this.isDiskImport && !this.isExternalImport) {
        return 'auto'
      }
      return 'custom'
    },
    defaultUseAblestackV2K () {
      return this.cluster.hypervisortype === 'KVM' && !!this.selectedVmwareVcenter
    },
    changeTemplateType (e) {
      this.templateType = e.target.value
      if (this.templateType === 'auto') {
        this.updateFieldValue('templateid', undefined)
      }
      this.rules = reactive({
        displayname: [{ required: true, message: this.$t('message.error.input.value') }],
        templateid: [{ required: this.templateType !== 'auto', message: this.$t('message.error.input.value') }],
        rootdiskid: [{ required: this.templateType !== 'auto', message: this.$t('message.error.input.value') }]
      })
    },
    selectMatchingComputeOffering () {
      var offerings = [...this.computeOfferings]
      offerings.sort(function (a, b) {
        return a.cpunumber - b.cpunumber
      })
      for (var offering of offerings) {
        var cpuNumberMatches = false
        var cpuSpeedMatches = false
        var memoryMatches = false
        if (!offering.iscustomized) {
          cpuNumberMatches = offering.cpunumber === this.resource.cpunumber
          cpuSpeedMatches = !this.resource.cpuspeed || offering.cpuspeed === this.resource.cpuspeed
          memoryMatches = offering.memory === this.resource.memory
        } else {
          cpuNumberMatches = cpuSpeedMatches = memoryMatches = true
          if (offering.serviceofferingdetails) {
            cpuNumberMatches = (this.resource.cpunumber >= offering.serviceofferingdetails.mincpunumber &&
              this.resource.cpunumber <= offering.serviceofferingdetails.maxcpunumber)
            memoryMatches = (this.resource.memory >= offering.serviceofferingdetails.minmemory &&
              this.resource.memory <= offering.serviceofferingdetails.maxmemory)
            cpuSpeedMatches = !this.resource.cpuspeed || offering.cpuspeed === this.resource.cpuspeed
          }
        }
        if (cpuNumberMatches && cpuSpeedMatches && memoryMatches) {
          setTimeout(() => {
            this.updateComputeOffering(offering.id)
          }, 250)
          break
        }
      }
    },
    fetchKvmHostsForConversion () {
      getAPI('listHosts', {
        zoneid: this.zoneid,
        hypervisor: this.cluster.hypervisortype,
        type: 'Routing',
        state: 'Up'
      }).then(json => {
        this.kvmHostsForConversion = json.listhostsresponse.host || []
        this.kvmHostsForConversion = this.kvmHostsForConversion.filter(host => ['Enabled', 'Disabled'].includes(host.resourcestate))
        // Check if any host has VDDK support
        let hasVddkSupport = false
        this.kvmHostsForConversion.map(host => {
          host.name = host.name + ' [Pod=' + host.podname + '] [Cluster=' + host.clustername + ']'
          if (host.instanceconversionsupported !== null && host.instanceconversionsupported !== undefined && host.instanceconversionsupported) {
            host.name = host.name + ' (' + this.$t('label.supported') + ')'
          } else {
            host.name = host.name + ' (' + this.$t('label.not.supported') + ')'
          }
          if (host.details['host.virtv2v.version']) {
            host.name = host.name + ' (virt-v2v=' + host.details['host.virtv2v.version'] + ')'
          }
          if (host.details['host.ovftool.version']) {
            host.name = host.name + ' (ovftool=' + host.details['host.ovftool.version'] + ')'
          }
          // Check for VDDK support
          if (host.details['host.vddk.support'] === 'true' || host.details['host.vddk.support'] === true) {
            hasVddkSupport = true
          }

          if (this.form.usevddk) {
            if (host.details['host.vddk.support'] === 'true' || host.details['host.vddk.support'] === true) {
              host.name = host.name + ' (VDDK=' + this.$t('label.supported') + ')'
            } else {
              host.name = host.name + ' (VDDK=' + this.$t('label.not.supported') + ')'
            }
            if (host.details['host.vddk.version']) {
              host.name = host.name + ' (vddk=' + host.details['host.vddk.version'] + ')'
            }
          }
        })

        // Enable usevddk by default if at least one host has VDDK support
        // Only auto-enable if user hasn't manually modified the setting
        if (hasVddkSupport && !this.form.usevddk && !this.userModifiedVddkSetting) {
          this.form.usevddk = true
          this.onUseVddkChange(true, false)
        }
      })
    },
    fetchKvmHostsForImporting () {
      getAPI('listHosts', {
        clusterid: this.cluster.id,
        hypervisor: this.cluster.hypervisortype,
        type: 'Routing',
        state: 'Up',
        resourcestate: 'Enabled'
      }).then(json => {
        this.kvmHostsForImporting = json.listhostsresponse.host || []
      })
    },
    fetchStoragePoolsForConversion () {
      if (this.selectedStorageOptionForConversion === 'primary') {
        const params = {
          clusterid: this.cluster.id,
          status: 'Up'
        }
        if (this.selectedKvmHostForConversion) {
          const kvmHost = this.kvmHostsForConversion.filter(x => x.id === this.selectedKvmHostForConversion)[0]
          if (kvmHost.clusterid !== this.cluster.id) {
            params.scope = 'ZONE'
          }
        }
        getAPI('listStoragePools', params).then(json => {
          this.storagePoolsForConversion = json.liststoragepoolsresponse.storagepool || []
          // Keep selected pool state aligned when the value is auto-populated by v-model.
          if (this.form.convertstoragepoolid) {
            const poolExists = this.storagePoolsForConversion.some(pool => pool.id === this.form.convertstoragepoolid)
            this.selectedStoragePoolForConversion = poolExists ? this.form.convertstoragepoolid : null
          }
          this.preflightResult = null
        })
      } else if (this.selectedStorageOptionForConversion === 'local') {
        const kvmHost = this.kvmHostsForConversion.filter(x => x.id === this.selectedKvmHostForConversion)[0]
        getAPI('listStoragePools', {
          scope: 'HOST',
          ipaddress: kvmHost.ipaddress,
          status: 'Up'
        }).then(json => {
          this.storagePoolsForConversion = json.liststoragepoolsresponse.storagepool || []
          if (this.form.convertstoragepoolid) {
            const poolExists = this.storagePoolsForConversion.some(pool => pool.id === this.form.convertstoragepoolid)
            this.selectedStoragePoolForConversion = poolExists ? this.form.convertstoragepoolid : null
          }
          this.preflightResult = null
        })
      }
    },
    updateSelectedKvmHostForImporting (clusterid, checked, value) {
      if (checked) {
        this.selectedKvmHostForImporting = value
      } else {
        this.selectedKvmHostForImporting = null
        this.resetStorageOptionsForConversion()
      }
    },
    updateSelectedKvmHostForConversion (clusterid, checked, value) {
      if (checked) {
        this.selectedKvmHostForConversion = value
        const kvmHost = this.kvmHostsForConversion.filter(x => x.id === this.selectedKvmHostForConversion)[0]
        if (kvmHost.islocalstorageactive) {
          this.storageOptionsForConversion.push({
            id: 'local',
            name: 'Host Local Storage'
          })
        } else {
          this.resetStorageOptionsForConversion()
        }
      } else {
        this.selectedKvmHostForConversion = null
        this.resetStorageOptionsForConversion()
      }
    },
    updateSelectedStorageOptionForConversion (clusterid, checked, value) {
      if (checked) {
        this.selectedStorageOptionForConversion = value
        this.fetchStoragePoolsForConversion()
        this.showStoragePoolsForConversion = value !== 'secondary'
      } else {
        this.showStoragePoolsForConversion = false
        this.selectedStoragePoolForConversion = null
        this.updateFieldValue('convertstoragepoolid', undefined)
      }
      this.preflightResult = null
    },
    resetStorageOptionsForConversion () {
      if (this.showAblestackCloudMigrationOptions) {
        this.storageOptionsForConversion = [...this.ablestackStorageOptionsForConversion]
        return
      }
      this.storageOptionsForConversion = this.switches.forceConvertToPool ? [] : [{
        id: 'secondary',
        name: this.$t('label.secondary.storage')
      }]
      this.storageOptionsForConversion.push({
        id: 'primary',
        name: this.$t('label.primary.storage')
      })
    },
    onSelectRootDisk (val) {
      this.selectedRootDiskIndex = val
      this.updateSelectedRootDisk()
    },
    onForceConvertToPoolChange (val) {
      this.switches.forceConvertToPool = val
      this.form.forceconverttopool = val
      this.selectedStorageOptionForConversion = null
      this.selectedStoragePoolForConversion = null
      this.showStoragePoolsForConversion = false
      this.resetStorageOptionsForConversion()
    },
    onUseVddkChange (val, isUserChange = true) {
      if (isUserChange) {
        this.userModifiedVddkSetting = true
      }
      if (val) {
        this.form.forceconverttopool = true
        this.form.forcemstoimportvmfiles = false
        this.switches.forceConvertToPool = true
        this.switches.forceMsToImportVmFiles = false
        // Reset import host selection when VDDK is enabled
        this.selectedKvmHostForImporting = null
        // Refresh host list to show VDDK support details
        this.fetchKvmHostsForConversion()
      } else {
        this.form.forceconverttopool = false
        this.switches.forceConvertToPool = false
        this.selectedStorageOptionForConversion = null
        this.selectedStoragePoolForConversion = null
        this.showStoragePoolsForConversion = false
        // Refresh host list to remove VDDK support details
        this.fetchKvmHostsForConversion()
      }
      this.resetStorageOptionsForConversion()
    },
    onAblestackV2KModeChange (e) {
      const useAblestackV2K = typeof e === 'boolean' ? e : (e?.target?.value ?? this.form.useablestackv2k)
      if (!useAblestackV2K) {
        if (this.form.usevddk) {
          this.onUseVddkChange(true, false)
        }
        return
      }
      this.updateFieldValue('forceconverttopool', false)
      this.updateFieldValue('forcemstoimportvmfiles', false)
      this.switches.forceConvertToPool = false
      this.switches.forceMsToImportVmFiles = false
      this.selectedKvmHostForConversion = null
      this.selectedKvmHostForImporting = null
      this.selectedStorageOptionForConversion = null
      this.selectedStoragePoolForConversion = null
      this.showStoragePoolsForConversion = false
      this.vmwareToKvmExtraParamsSelected = false
      this.vmwareToKvmExtraParams = ''
      this.updateFieldValue('convertstoragepoolid', undefined)
      this.preflightResult = null
      this.resetStorageOptionsForConversion()
    },
    runAblestackN2KPreflight () {
      if (!this.isAblestackN2KImport) {
        return
      }
      if (!this.exthost || !this.username || !this.password) {
        this.$notification.error({
          message: this.$t('message.request.failed'),
          description: this.$t('message.please.enter.valid.value') + ': ' + this.$t('label.nutanix.prism.endpoint')
        })
        return
      }
      const params = {
        zoneid: this.zoneid,
        clusterid: this.cluster.id,
        migrationtool: 'ablestack_n2k',
        sourceprovider: 'nutanix',
        host: this.exthost,
        username: this.username,
        password: this.password,
        sourceapi: this.sourceapi || 'v3',
        sourcevmname: this.resource.name,
        insecure: this.insecure !== false
      }
      if (this.computeOffering?.id || this.form.computeofferingid) {
        params.serviceofferingid = this.form.computeofferingid || this.computeOffering.id
      }
      if (this.selectedKvmHostForConversion) {
        params.convertinstancehostid = this.selectedKvmHostForConversion
      }
      const selectedPoolForConversion = this.form.convertstoragepoolid || this.selectedStoragePoolForConversion
      if (selectedPoolForConversion) {
        params.convertinstancepoolid = selectedPoolForConversion
      }
      this.preflightLoading = true
      getAPI('preflightAblestackVmImport', params).then(json => {
        this.preflightResult = json.preflightablestackvmimportresponse || json.ablestackvmimportpreflightresponse || {}
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.preflightLoading = false
      })
    },
    getDiskSizeGiB (capacity) {
      const bytes = Number(capacity)
      if (!Number.isFinite(bytes) || bytes <= 0) {
        return null
      }
      return bytes / (1024 * 1024 * 1024)
    },
    getDiskCapacityLabel (capacity) {
      const size = this.getDiskSizeGiB(capacity)
      if (size === null) {
        return '-'
      }
      return `${Number(size.toFixed(size >= 10 ? 0 : 1))} GB`
    },
    updateSelectedRootDisk () {
      var rootDisk = this.resourceDisks[this.selectedRootDiskIndex]
      rootDisk.size = this.getDiskSizeGiB(rootDisk.capacity)
      rootDisk.name = `${rootDisk.label} (${this.getDiskCapacityLabel(rootDisk.capacity)})`
      rootDisk.meta = this.getMeta(rootDisk, { controller: 'controller', datastorename: 'datastore', position: 'position' })
      this.selectedRootDiskSources = [rootDisk]
    },
    handleSubmit (e) {
      if (e?.preventDefault) e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        if (this.loading) return
        const values = toRaw(this.form)
        const params = {
          name: this.resource.name,
          clusterid: this.cluster.id,
          displayname: values.displayname,
          zoneid: this.zoneid,
          importsource: this.importsource,
          hypervisor: this.hypervisor,
          host: this.exthost,
          hostname: values.hostname,
          username: this.username,
          password: this.password,
          hostid: this.host.id,
          storageid: this.pool.id,
          diskpath: this.diskpath,
          temppath: this.tmppath
        }
        const useAblestackV2KWorkflow = !!this.form.useablestackv2k
        const useAblestackN2KWorkflow = this.isAblestackN2KImport
        var importapi = 'importUnmanagedInstance'
        if (this.isExternalImport || this.isDiskImport || this.selectedVmwareVcenter || useAblestackN2KWorkflow) {
          importapi = useAblestackN2KWorkflow ? 'importUnmanagedInstanceForAblestackN2K' : (useAblestackV2KWorkflow ? 'importUnmanagedInstanceForAblestackV2K' : 'importVm')
          if (this.isDiskImport) {
            if (!values.networkid) {
              this.$notification.error({
                message: this.$t('message.request.failed'),
                description: this.$t('message.please.enter.valid.value') + ': ' + this.$t('label.network')
              })
              return
            }
            params.name = values.displayname
            params.networkid = values.networkid
            if (this.sourceidentity) params.sourceidentity = this.sourceidentity
          }
        }
        if (!this.computeOffering || !this.computeOffering.id) {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('message.step.2.continue')
          })
          return
        }
        params.serviceofferingid = values.computeofferingid
        if (this.computeOffering.iscustomized) {
          var details = [this.cpuNumberKey, this.cpuSpeedKey, this.memoryKey]
          for (var detail of details) {
            if (!(values[detail] || this.computeOffering[detail])) {
              this.$notification.error({
                message: this.$t('message.request.failed'),
                description: this.$t('message.please.enter.valid.value') + ': ' + this.$t('label.' + detail.toLowerCase())
              })
              return
            }
            if (values[detail]) {
              params['details[0].' + detail] = values[detail]
            }
          }
        }
        if (this.computeOffering.iscustomizediops) {
          var iopsDetails = [this.minIopsKey, this.maxIopsKey]
          for (var iopsDetail of iopsDetails) {
            if (!values[iopsDetail] || values[iopsDetail] < 0) {
              this.$notification.error({
                message: this.$t('message.request.failed'),
                description: this.$t('message.please.enter.valid.value') + ': ' + this.$t('label.' + iopsDetail.toLowerCase())
              })
              return
            }
            params['details[0].' + iopsDetail] = values[iopsDetail]
          }
          if (values[this.minIopsKey] > values[this.maxIopsKey]) {
            this.$notification.error({
              message: this.$t('message.request.failed'),
              description: this.$t('error.form.message')
            })
          }
        }
        if (this.isDiskImport) {
          var storageType = this.computeOffering.storagetype
          if (this.importsource !== storageType) {
            this.$notification.error({
              message: this.$t('message.request.failed'),
              description: 'Incompatible Storage. Import Source is: ' + this.importsource + '. Storage Type in service offering is: ' + storageType
            })
            return
          }
        }
        if (this.selectedVmwareVcenter) {
          if (this.selectedVmwareVcenter.existingvcenterid) {
            params.existingvcenterid = this.selectedVmwareVcenter.existingvcenterid
          } else {
            params.vcenter = this.selectedVmwareVcenter.vcenter
            params.datacentername = this.selectedVmwareVcenter.datacentername
            params.username = this.selectedVmwareVcenter.username
            params.password = this.selectedVmwareVcenter.password
          }
          params.hostip = this.resource.hostname
          params.clustername = this.resource.clustername
          if (this.selectedKvmHostForConversion) {
            params.convertinstancehostid = this.selectedKvmHostForConversion
          }
          const selectedPoolForConversion = values.convertstoragepoolid || this.selectedStoragePoolForConversion
          if (selectedPoolForConversion) {
            params.convertinstancepoolid = selectedPoolForConversion
          }
          if (!useAblestackV2KWorkflow) {
            if (this.selectedKvmHostForImporting) {
              params.importinstancehostid = this.selectedKvmHostForImporting
            }
            if (this.vmwareToKvmExtraParams) {
              params.extraparams = this.vmwareToKvmExtraParams
            }
            if (values.usevddk) {
              params.usevddk = true
              params.forcemstoimportvmfiles = false
            } else {
              params.usevddk = false
              params.forcemstoimportvmfiles = values.forcemstoimportvmfiles
            }
            if (values.forceconverttopool !== undefined) {
              params.forceconverttopool = values.forceconverttopool
            }
          }
        }
        if (useAblestackN2KWorkflow) {
          params.importsource = 'nutanix'
          params.hypervisor = 'KVM'
          params.host = this.exthost
          params.username = this.username
          params.password = this.password
          params.sourceapi = this.sourceapi === 'auto' ? 'v3' : (this.sourceapi || 'v3')
          params.insecure = this.insecure !== false
          const retentionDays = Number(values.n2kretentiondays || this.form.n2kretentiondays || 14)
          if (!Number.isFinite(retentionDays) || retentionDays < 1) {
            this.$notification.error({
              message: this.$t('message.request.failed'),
              description: this.$t('message.error.input.value')
            })
            return
          }
          params.retentionseconds = Math.round(retentionDays * 24 * 60 * 60)
          params.starttargetvm = values.starttargetvm !== false
          if (this.selectedKvmHostForConversion) {
            params.convertinstancehostid = this.selectedKvmHostForConversion
          }
          const selectedPoolForConversion = values.convertstoragepoolid || this.selectedStoragePoolForConversion
          if (selectedPoolForConversion) {
            params.convertinstancepoolid = selectedPoolForConversion
          }
        }
        var keys = ['hostname', 'domainid', 'projectid', 'account', 'migrateallowed', 'forced', 'osid']
        if (this.templateType !== 'auto') {
          keys.push('templateid')
        }
        for (var key of keys) {
          if (values[key]) {
            params[key] = values[key]
          }
        }
        var diskOfferingIndex = 0
        for (const disk of this.dataDisks) {
          const diskId = disk.id
          if (!this.dataDisksOfferingsMapping[diskId]) {
            this.$notification.error({
              message: this.$t('message.request.failed'),
              description: this.$t('message.select.disk.offering') + ': ' + (disk.label || diskId)
            })
            return
          }
          params['datadiskofferinglist[' + diskOfferingIndex + '].disk'] = diskId
          params['datadiskofferinglist[' + diskOfferingIndex + '].diskOffering'] = this.dataDisksOfferingsMapping[diskId]
          diskOfferingIndex++
        }
        var nicNetworkIndex = 0
        var nicIpIndex = 0
        var networkcheck = new Set()
        for (var nicId in this.nicsNetworksMapping) {
          if (!this.nicsNetworksMapping[nicId].network) {
            this.$notification.error({
              message: this.$t('message.request.failed'),
              description: this.$t('message.select.nic.network') + ': ' + nicId
            })
            return
          }
          params['nicnetworklist[' + nicNetworkIndex + '].nic'] = nicId
          params['nicnetworklist[' + nicNetworkIndex + '].network'] = this.nicsNetworksMapping[nicId].network
          var netId = this.nicsNetworksMapping[nicId].network
          if (!networkcheck.has(netId)) {
            networkcheck.add(netId)
          } else {
            this.$notification.error({
              message: this.$t('message.request.failed'),
              description: 'Same network cannot be assigned to multiple Nics'
            })
            return
          }
          nicNetworkIndex++
          if ('ipAddress' in this.nicsNetworksMapping[nicId]) {
            if (!this.nicsNetworksMapping[nicId].ipAddress) {
              this.$notification.error({
                message: this.$t('message.request.failed'),
                description: this.$t('message.enter.valid.nic.ip') + ': ' + nicId
              })
              return
            }
            params['nicipaddresslist[' + nicIpIndex + '].nic'] = nicId
            params['nicipaddresslist[' + nicIpIndex + '].ip4Address'] = this.nicsNetworksMapping[nicId].ipAddress
            nicIpIndex++
          }
        }
        this.updateLoading(true)
        const name = params.name
        return new Promise((resolve, reject) => {
          postAPI(importapi, params).then(response => {
            var jobId
            if (importapi === 'importUnmanagedInstanceForAblestackV2K') {
              jobId = response.importunmanagedinstanceforablestackv2kresponse.jobid
            } else if (importapi === 'importUnmanagedInstanceForAblestackN2K') {
              jobId = response.importunmanagedinstanceforablestackn2kresponse.jobid
            } else if (this.isDiskImport || this.isExternalImport || this.selectedVmwareVcenter) {
              jobId = response.importvmresponse.jobid
            } else {
              jobId = response.importunmanagedinstanceresponse.jobid
            }
            if (this.wizard && (useAblestackV2KWorkflow || useAblestackN2KWorkflow)) {
              this.updateLoading(false)
              this.$emit('submitted', jobId)
            }
            let msgLoading = this.$t('label.import.instance') + ' ' + name + ' ' + this.$t('label.in.progress')
            if (this.selectedKvmHostForConversion) {
              const kvmHost = this.kvmHostsForConversion.filter(x => x.id === this.selectedKvmHostForConversion)[0]
              msgLoading += ' on host ' + kvmHost.name
            }
            if (importapi === 'importUnmanagedInstanceForAblestackN2K') {
              this.finishAblestackN2KBackgroundJob(jobId, this.$t('label.import.instance'), name)
              this.$emit('refresh-data')
              window.setTimeout(() => this.$emit('refresh-data'), 3000)
              resolve(response)
              return
            }
            this.$pollJob({
              jobId,
              title: this.$t('label.import.instance'),
              description: name,
              loadingMessage: msgLoading,
              catchMessage: this.$t('error.fetching.async.job.result'),
              successMessage: this.$t('message.success.import.instance') + ' ' + name,
              successMethod: result => {
                this.$emit('refresh-data')
                if (this.wizard && !useAblestackV2KWorkflow) {
                  this.updateLoading(false)
                  this.$emit('submitted', jobId)
                }
                resolve(result)
              },
              errorMethod: result => {
                this.updateLoading(false)
                reject(result.jobresult.errortext)
              },
              catchMethod: result => {
                this.updateLoading(false)
                reject(result)
              }
            })
          }).catch(error => {
            this.updateLoading(false)
            this.$notifyError(error)
          }).finally(() => {
            if (!this.wizard) {
              this.closeAction()
              this.updateLoading(false)
            }
          })
        })
      }).catch(() => {
        this.$emit('loading-changed', false)
      })
    },
    finishAblestackN2KBackgroundJob (jobId, title, description) {
      this.$message.success({
        content: this.$t('message.import.vm.task.submitted') + ' ' + description,
        key: jobId,
        duration: 2
      })
      this.$store.dispatch('AddHeaderNotice', {
        key: jobId,
        title,
        description,
        status: 'done',
        duration: 2,
        timestamp: new Date()
      })
    },
    updateLoading (value) {
      this.loading = value
      this.$emit('loading-changed', value)
    },
    resetForm () {
      var fields = ['displayname', 'hostname', 'domainid', 'account', 'projectid', 'computeofferingid']
      for (var field of fields) {
        this.updateFieldValue(field, undefined)
      }
      this.applyDefaultDisplayName(true)
      this.applyDefaultHostname(true)
      this.templateType = this.defaultTemplateType()
      this.updateComputeOffering(undefined)
      this.switches = {}
      this.form.usevddk = false
      this.form.forceconverttopool = false
      this.form.forcemstoimportvmfiles = false
      this.form.starttargetvm = true
      this.form.n2kretentiondays = 14
      this.userModifiedVddkSetting = false
      this.selectedKvmHostForConversion = null
      this.selectedKvmHostForImporting = null
      this.selectedStorageOptionForConversion = null
      this.selectedStoragePoolForConversion = null
      this.showStoragePoolsForConversion = false
      this.vmwareToKvmExtraParamsSelected = false
      this.vmwareToKvmExtraParams = ''
      this.updateFieldValue('convertstoragepoolid', undefined)
      this.preflightResult = null
      this.resetStorageOptionsForConversion()
      this.updateFieldValue('useablestackv2k', this.defaultUseAblestackV2K())
      this.onAblestackV2KModeChange(this.form.useablestackv2k)
    },
    closeAction () {
      this.$emit('close-action')
    },
    isOfferingConstrained (serviceOffering) {
      return 'serviceofferingdetails' in serviceOffering && 'mincpunumber' in serviceOffering.serviceofferingdetails &&
        'maxmemory' in serviceOffering.serviceofferingdetails && 'maxcpunumber' in serviceOffering.serviceofferingdetails &&
        'minmemory' in serviceOffering.serviceofferingdetails
    }
  }
}
</script>

<style lang="less">
@import url('../../style/index');
.ant-table-selection-column {
  // Fix for the table header if the row selection use radio buttons instead of checkboxes
  > div:empty {
    width: 16px;
  }
}

.ant-collapse-borderless > .ant-collapse-item {
  border: 1px solid @border-color-split;
  border-radius: @border-radius-base !important;
  margin: 0 0 1.2rem;
}

.form-layout {
  width: 120vw;

  @media (min-width: 1000px) {
    width: 550px;
  }
}

.action-button {
  text-align: right;

  button {
    margin-right: 5px;
  }
}

.ablestack-import-alert {
  margin-bottom: 4px;
  padding: 10px 14px;

  .ant-alert-icon {
    align-items: center;
    display: inline-flex;
    font-size: 14px;
  }

  .ant-alert-message {
    font-size: 13px;
    font-weight: 600;
    line-height: 1.4;
  }

  .ant-alert-description {
    font-size: 12px;
    line-height: 1.55;
    margin-top: 3px;
  }
}

.importform,
.importform > div,
.importform .ant-spin-nested-loading,
.importform .ant-spin-container {
  min-height: 0;
}

.import-form-card {
  max-height: calc(100vh - 148px);
  overflow: hidden;

  > .ant-card-body {
    display: flex;
    flex-direction: column;
    max-height: calc(100vh - 148px);
    padding: 0;
  }

  form {
    display: flex;
    flex-direction: column;
    max-height: calc(100vh - 148px);
    min-height: 0;
  }
}

.import-form-scroll {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  max-height: calc(100vh - 226px);
  overflow-x: hidden;
  overflow-y: auto;
  padding: 24px;
  scrollbar-color: fade(@text-color-secondary, 42%) fade(@border-color-base, 36%);
  scrollbar-width: thin;
}

.import-form-scroll::-webkit-scrollbar {
  height: 8px;
  width: 8px;
}

.import-form-scroll::-webkit-scrollbar-track {
  background: fade(@border-color-base, 28%);
  border-radius: 8px;
}

.import-form-scroll::-webkit-scrollbar-thumb {
  background: fade(@text-color-secondary, 40%);
  border-radius: 8px;
}

.import-form-scroll::-webkit-scrollbar-thumb:hover {
  background: fade(@text-color-secondary, 58%);
}

.import-form-section {
  background: @component-background;
  border: 1px solid @border-color-split;
  border-radius: 6px;
  overflow: visible;
}

.import-form-section-header {
  align-items: center;
  background: fade(@primary-color, 5%);
  border-bottom: 1px solid @border-color-split;
  color: @heading-color;
  display: flex;
  font-weight: 600;
  gap: 8px;
  line-height: 1.35;
  min-height: 40px;
  padding: 10px 14px;
}

.import-form-section-icon {
  align-items: center;
  color: @primary-color;
  display: inline-flex;
  font-size: 15px;
  justify-content: center;
}

.import-form-section-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px 24px 18px;

  > .ant-form-item,
  > div > .ant-form-item,
  > .ant-row {
    margin-bottom: 0;
  }

  .ant-form-item {
    margin-bottom: 0;
  }

  .ant-form-item-label {
    padding-bottom: 5px;
  }

  .ant-alert {
    margin-bottom: 2px;
  }

  .ant-table-wrapper,
  .ant-table,
  .ant-table-content {
    max-width: 100%;
  }
}

.import-options-section-body {
  gap: 10px;

  .ant-form-item-label {
    padding-bottom: 3px;
  }

  .ant-form-item-control-input {
    min-height: 28px;
  }

  .ant-row {
    row-gap: 6px;
  }

  .ant-checkbox-wrapper {
    line-height: 1.45;
  }

  .ant-select {
    margin-top: 4px;
  }

  .ant-tag {
    margin-top: 0;
  }
}

.import-form-alert {
  margin-bottom: 2px;
}

.import-form-actions {
  background: @component-background;
  border-top: 1px solid @border-color-split;
  flex: 0 0 auto;
  padding: 14px 24px;

  button {
    min-width: 82px;
  }
}

.vm-info-card {
  max-height: calc(100vh - 148px);
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-color: fade(@text-color-secondary, 42%) fade(@border-color-base, 36%);
  scrollbar-width: thin;
}

.vm-info-card::-webkit-scrollbar {
  height: 8px;
  width: 8px;
}

.vm-info-card::-webkit-scrollbar-track {
  background: fade(@border-color-base, 28%);
  border-radius: 8px;
}

.vm-info-card::-webkit-scrollbar-thumb {
  background: fade(@text-color-secondary, 40%);
  border-radius: 8px;
}

body.dark-mode {
  .import-form-card,
  .import-form-section,
  .import-form-actions {
    background: #22282f;
    color: rgba(255, 255, 255, 0.85);
  }

  .import-form-section {
    border-color: #3e444c;
  }

  .import-form-section-header {
    background: #1b2733;
    border-bottom-color: #3e444c;
    color: rgba(255, 255, 255, 0.85);
  }

  .import-form-section-body {
    background: #22282f;
    color: rgba(255, 255, 255, 0.65);
  }

  .import-form-section-body .ant-form-item-label > label,
  .import-form-section-body .ant-radio-wrapper,
  .import-form-section-body .ant-checkbox-wrapper,
  .import-form-section-body .ant-descriptions-item-label,
  .import-form-section-body .ant-descriptions-item-content,
  .import-form-section-body .ant-table,
  .import-form-section-body .ant-table-thead > tr > th,
  .import-form-section-body .ant-table-tbody > tr > td {
    color: rgba(255, 255, 255, 0.65);
  }

  .import-form-section-body .ant-input,
  .import-form-section-body .ant-input-number,
  .import-form-section-body .ant-select-selector,
  .import-form-section-body .ant-table,
  .import-form-section-body .ant-table-thead > tr > th,
  .import-form-section-body .ant-table-tbody > tr > td {
    background: #161b22;
    border-color: #434343;
  }

  .import-form-section-body .ant-table-thead > tr > th,
  .import-form-section-body .ant-table-tbody > tr > td {
    border-bottom-color: #3e444c;
  }

  .import-form-section-body .ant-input,
  .import-form-section-body .ant-input-number-input,
  .import-form-section-body .ant-select-selection-item,
  .import-form-section-body .ant-select-selection-placeholder {
    color: rgba(255, 255, 255, 0.85);
  }

  .import-form-section-body .ant-input::placeholder {
    color: rgba(255, 255, 255, 0.35);
  }

  .import-form-section-icon {
    color: @primary-color;
  }

  .ablestack-import-alert.ant-alert-info {
    background: #102538;
    border-color: #27445f;
  }

  .ablestack-import-alert .ant-alert-icon,
  .ablestack-import-alert .ant-alert-message {
    color: #d6e4ff;
  }

  .ablestack-import-alert .ant-alert-description {
    color: #b7c9e6;
  }

  .import-form-scroll,
  .vm-info-card {
    scrollbar-color: rgba(143, 179, 217, 0.52) rgba(54, 80, 106, 0.35);
  }

  .import-form-scroll::-webkit-scrollbar-track,
  .vm-info-card::-webkit-scrollbar-track {
    background: rgba(54, 80, 106, 0.34);
  }

  .import-form-scroll::-webkit-scrollbar-thumb,
  .vm-info-card::-webkit-scrollbar-thumb {
    background: rgba(143, 179, 217, 0.52);
  }

  .import-form-scroll::-webkit-scrollbar-thumb:hover,
  .vm-info-card::-webkit-scrollbar-thumb:hover {
    background: rgba(143, 179, 217, 0.72);
  }

  .import-form-actions {
    border-top-color: #36506a;
  }
}
</style>
