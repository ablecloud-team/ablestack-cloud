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
  <div ref="deployLayout" class="deploy-vm-layout" :class="{ 'deploy-vm-layout--bounded': deployViewportHeight }" :style="{ '--deploy-viewport-height': deployViewportHeight }">
    <a-row :gutter="12">
      <a-col :md="24" :lg="17" class="deploy-vm-form-column">
        <a-card :bordered="true" :title="$t('label.newinstance')">
          <a-form
            class="deploy-vm-form-scroll"
            :disabled="loading.deploy"
            tabindex="0"
            :aria-label="$t('label.newinstance')"
            v-ctrl-enter="handleSubmit"
            :ref="formRef"
            :model="form"
            :rules="rules"
            @finish="handleSubmit"
            layout="vertical"
          >
            <div v-for="operation in isoDataOperations" :key="operation.deployJobId" style="margin-bottom: 16px">
              <div style="display: flex; align-items: center; gap: 10px">
                <strong>{{ $t('label.vm.disk.progress') }} · {{ operation.vmId || operation.deployJobId }}</strong>
                <a-button v-if="operation.status === 'failed'" :loading="loading.deploy" @click="retryIsoDisks(operation)">
                  <template #icon><ReloadOutlined /></template>{{ $t('label.retry') }}
                </a-button>
              </div>
              <p>{{ operation.disks.filter(disk => disk.status === 'attached').length }} / {{ operation.disks.length }} · {{ $t('label.vm.disk.status.' + operation.status) }}</p>
              <p v-if="operation.status === 'complete' && !operation.started">{{ $t('message.vm.disk.logical.complete') }}</p>
              <a-table :data-source="operation.disks" :pagination="false" row-key="index" size="small" style="margin: 8px 0">
                <a-table-column :title="$t('label.deviceid')" data-index="deviceId" />
                <a-table-column :title="$t('label.id')" data-index="volumeId" />
                <a-table-column :title="$t('label.status')">
                  <template #default="{ record }">{{ $t('label.vm.disk.item.' + record.status) }}</template>
                </a-table-column>
              </a-table>
              <a-alert v-if="operation.error"  type="error" show-icon :message="operation.error" />
            </div>
            <creation-source-operations v-model:operations="sourceOperations" :storage-key="sourceOperationsKey" />
            <a-steps direction="vertical" size="small">
              <a-step
                v-if="!isNormalUserOrProject"
                :title="this.$t('label.assign.instance.another')">
                <template #description>
                  <div style="margin-top: 15px">
                    {{ $t('label.assigning.vms') }}
                    <ownership-selection
                      @fetch-owner="fetchOwnerOptions"/>
                  </div>
                </template>
              </a-step>
              <a-step :title="$t('label.select.deployment.infrastructure')" status="process">
                <template #description>
                  <div style="margin-top: 15px">
                    <span>{{ $t('message.select.a.zone') }}</span><br/>
                    <a-form-item :label="$t('label.zoneid')" name="zoneid" ref="zoneid">
                      <zone-block-radio-group-select
                        :items="zones"
                        :selectedValue="form.zoneid"
                        @change="onSelectZoneId" />
                    </a-form-item>
                    <a-form-item
                      v-if="!isNormalAndDomainUser"
                      :label="$t('label.podid')"
                      name="podid"
                      ref="podid">
                      <a-select
                        v-model:value="form.podid"
                        showSearch
                        optionFilterProp="label"
                        :filterOption="filterOption"
                        :options="podSelectOptions"
                        :loading="loading.pods"
                        @change="onSelectPodId"
                      ></a-select>
                    </a-form-item>
                    <a-form-item
                      v-if="!isNormalAndDomainUser"
                      :label="$t('label.clusterid')"
                      name="clusterid"
                      ref="clusterid">
                      <a-select
                        v-model:value="form.clusterid"
                        :disabled="imageType === 'volumeid' && selectedCreationSource?.storage?.scope === 'CLUSTER'"
                        showSearch
                        optionFilterProp="label"
                        :filterOption="filterOption"
                        :options="clusterSelectOptions"
                        :loading="loading.clusters"
                        @change="onSelectClusterId"
                      ></a-select>
                    </a-form-item>
                    <a-form-item
                      v-if="!isNormalAndDomainUser"
                      :label="$t('label.hostid')"
                      name="hostid"
                      ref="hostid">
                      <a-select
                        v-model:value="form.hostid"
                        showSearch
                        optionFilterProp="label"
                        :filterOption="filterOption"
                        :options="hostSelectOptions"
                        :loading="loading.hosts"
                        @change="onSelectHostId"
                      ></a-select>
                    </a-form-item>
                  </div>
                </template>
              </a-step>
              <a-step
                v-if="!zoneSelected || isZoneSelectedMultiArch"
                :title="$t('label.arch')"
                :status="zoneSelected ? 'process' : 'wait'">
                <template #description>
                  <div v-if="zoneSelected" style="margin-top: 15px">
                    {{ $t('message.instance.architecture') }}
                    <block-radio-group-select
                      style="margin-top: 5px;"
                      :items="architectureTypes.opts"
                      :selectedValue="selectedArchitecture"
                      @change="changeArchitecture">
                        <template #radio-option="{ item }">
                          <span>{{ item.name || item.description }}</span>
                        </template>
                        <template #select-option="{ item }">
                          <span>{{ item.name || item.description }}</span>
                        </template>
                    </block-radio-group-select>
                  </div>
                </template>
              </a-step>
              <a-step
                :title="$t('label.image')"
                :status="zoneSelected ? 'process' : 'wait'">
                <template #description>
                  <div v-if="zoneSelected" style="margin-top: 15px">
                    <template v-if="isCreationSource">
                      <creation-source-selection
                        ref="creationSourceSelection"
                        :key="imageType"
                        :image-type="imageType"
                        :query="creationSourceQuery"
                        :selected="selectedCreationSource"
                        :preselected-id="imageType === 'volumeid' ? queryVolumeId : querySnapshotId"
                        @select="selectCreationSource"
                        @loading="sourceLoading = $event"
                        @change-image-type="changeImageType" />
                      <creation-source-summary :source="selectedCreationSource" :target-storage="rootStorageSelection" />
                      <a-alert type="info" show-icon :message="$t('message.creation.source.fixed.boot')" />
                    </template>
                    <os-based-image-selection
                      ref="imageSelection"
                      v-else-if="isModernImageSelection"
                      :selectedImageType="imageType"
                      :imagePreSelected="!!this.queryTemplateId || !!this.queryIsoId"
                      :guestOsCategoriesSelectionDisallowed="guestOsCategoriesSelectionDisallowed"
                      :guestOsCategories="options.guestOsCategories"
                      :guestOsCategoriesLoading="loading.guestOsCategories"
                      :selectedGuestOsCategoryId="form.guestoscategoryid"
                      :imageItems="imageType === 'isoid' ? options.isos : imageType === 'volumeid' ? options.volumes : imageType === 'snapshotid' ? options.snapshots : options.templates"
                      :imagesLoading="imageType === 'isoid' ? loading.isos : imageType === 'volumeid' ? loading.volumes : imageType === 'snapshotid' ? loading.snapshots : loading.templates"
                      :diskSizeSelectionAllowed="false"
                      :diskSizeSelectionDeployAsIsMessageVisible="template && template.deployasis"
                      :rootDiskOverrideDisabled="rootDiskSizeFixed > 0 || (template && template.deployasis) || showOverrideDiskOfferingOption"
                      :rootDiskOverrideChecked="form.rootdisksizeitem"
                      :isoHypervisor="form.hypervisor"
                      :isoHypervisorItems="hypervisorSelectOptions"
                      :filterOption="filterOption"
                      :preFillContent="dataPreFill"
                      @change-image-type="changeImageType"
                      @change-guest-os-category="onSelectGuestOsCategory"
                      @handle-image-search-filter="filters => fetchImages(filters)"
                      @update-image="updateFieldValue"
                      @update-disk-size="updateFieldValue"
                      @change-iso-hypervisor="value => form.hypervisor = value" />
                    <a-card
                      ref="legacyImageSelection"
                      v-else
                      :tabList="imageTypeList"
                      :activeTabKey="imageType"
                      @tabChange="key => changeImageType(key, true)">
                      <div v-if="imageType === 'templateid'">
                        {{ $t('message.template.desc') }}
                        <template-iso-selection
                          input-decorator="templateid"
                          :items="options.templates"
                          :selected="imageType"
                          :loading="loading.templates"
                          :preFillContent="dataPreFill"
                          :key="templateKey"
                          @handle-search-filter="filters => fetchAllTemplates(filters)"
                          @update-template-iso="updateFieldValue" />
                      </div>
                      <div v-else>
                        {{ $t('message.iso.desc') }}
                        <template-iso-selection
                          input-decorator="isoid"
                          :items="options.isos"
                          :selected="imageType"
                          :loading="loading.isos"
                          :preFillContent="dataPreFill"
                          @handle-search-filter="filters => fetchAllIsos(filters)"
                          @update-template-iso="updateFieldValue" />
                        <a-form-item :label="$t('label.hypervisor')">
                          <a-select
                            v-model:value="form.hypervisor"
                            :preFillContent="dataPreFill"
                            :options="hypervisorSelectOptions"
                            @change="value => hypervisor = value"
                            showSearch
                            optionFilterProp="label"
                            :filterOption="filterOption" />
                        </a-form-item>
                      </div>
                    </a-card>
                    <additional-iso-selection
                      v-if="imageType === 'isoid'"
                      :zone-id="form.zoneid"
                      :primary-id="form.isoid"
                      :owner="owner"
                      :project-id="$store.getters.project?.id"
                      :supported="!!apiParams?.additionalisoids && form.hypervisor === 'KVM'"
                      @change="additionalIsoSelection = $event" />
                    <a-form-item class="form-item-hidden">
                      <a-input v-model:value="form.templateid" />
                    </a-form-item>
                    <a-form-item class="form-item-hidden">
                      <a-input v-model:value="form.isoid" />
                    </a-form-item>
                    <a-form-item class="form-item-hidden">
                      <a-input v-model:value="form.volumeid" />
                    </a-form-item>
                    <a-form-item class="form-item-hidden">
                      <a-input v-model:value="form.snapshotid" />
                    </a-form-item>
                    <a-form-item class="form-item-hidden">
                      <a-input v-model:value="form.rootdisksize" />
                    </a-form-item>
                    <a-form-item class="form-item-hidden">
                      <a-input v-model:value="form.templateKvdoEnable" />
                    </a-form-item>
                  </div>
                </template>
              </a-step>
              <a-step
                :title="$t('label.serviceofferingid')"
                :status="zoneSelected ? 'process' : 'wait'">
                <template #description>
                  <div v-if="zoneSelected">
                    <a-form-item v-if="zoneSelected && templateConfigurationExists" name="templateConfiguration" ref="templateConfiguration">
                      <template #label>
                        <tooltip-label :title="$t('label.configuration')" :tooltip="$t('message.ovf.configurations')"/>
                      </template>
                      <a-select
                        showSearch
                        optionFilterProp="label"
                        v-model:value="form.templateConfiguration"
                        defaultActiveFirstOption
                        :placeholder="$t('message.ovf.configurations')"
                        :filterOption="(input, option) => {
                          return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                        }"
                        @change="onSelectTemplateConfigurationId"
                      >
                        <a-select-option v-for="opt in templateConfigurations" :key="opt.id" :label="opt.name || opt.description">
                          {{ opt.name || opt.description }}
                        </a-select-option>
                      </a-select>
                      <span v-if="selectedTemplateConfiguration && selectedTemplateConfiguration.description">{{ selectedTemplateConfiguration.description }}</span>
                    </a-form-item>
                    <compute-offering-selection
                      :compute-items="options.serviceOfferings"
                      :selected-template="template ? template : {}"
                      :row-count="rowCount.serviceOfferings"
                      :zoneId="zoneId"
                      :value="serviceOffering ? serviceOffering.id : ''"
                      :loading="loading.serviceOfferings"
                      :preFillContent="dataPreFill"
                      :show-gpu-filter="zone.gputotal && zone.gputotal > 0"
                      :minimum-cpunumber="templateConfigurationExists && selectedTemplateConfiguration && selectedTemplateConfiguration.cpunumber ? selectedTemplateConfiguration.cpunumber : 0"
                      :minimum-cpuspeed="templateConfigurationExists && selectedTemplateConfiguration && selectedTemplateConfiguration.cpuspeed ? selectedTemplateConfiguration.cpuspeed : 0"
                      :minimum-memory="templateConfigurationExists && selectedTemplateConfiguration && selectedTemplateConfiguration.memory ? selectedTemplateConfiguration.memory : 0"
                      @select-compute-item="($event, $kvdoEnable) => updateComputeOffering($event, $kvdoEnable)"
                      @handle-search-filter="($event) => handleSearchFilter('serviceOfferings', $event)"
                    ></compute-offering-selection>
                    <compute-selection
                      v-if="serviceOffering && (serviceOffering.iscustomized || serviceOffering.iscustomizediops)"
                      cpuNumberInputDecorator="cpunumber"
                      cpuSpeedInputDecorator="cpuspeed"
                      memoryInputDecorator="memory"
                      :preFillContent="dataPreFill"
                      :computeOfferingId="instanceConfig.computeofferingid"
                      :computeOfferingKvdoEnable="serviceOffering.kvdoenable"
                      :isConstrained="isOfferingConstrained(serviceOffering)"
                      :minCpu="'serviceofferingdetails' in serviceOffering ? serviceOffering.serviceofferingdetails.mincpunumber*1 : 1"
                      :maxCpu="'serviceofferingdetails' in serviceOffering ? serviceOffering.serviceofferingdetails.maxcpunumber*1 : Number.MAX_SAFE_INTEGER"
                      :minMemory="'serviceofferingdetails' in serviceOffering ? serviceOffering.serviceofferingdetails.minmemory*1 : 1024"
                      :maxMemory="'serviceofferingdetails' in serviceOffering ? serviceOffering.serviceofferingdetails.maxmemory*1 : Number.MAX_SAFE_INTEGER"
                      :isCustomized="serviceOffering.iscustomized"
                      :isCustomizedIOps="'iscustomizediops' in serviceOffering && serviceOffering.iscustomizediops"
                      @handler-error="handlerError"
                      @update-iops-value="updateIOPSValue"
                      @update-compute-cpunumber="updateFieldValue"
                      @update-compute-cpuspeed="updateFieldValue"
                      @update-compute-memory="updateFieldValue" />
                    <span v-if="serviceOffering && serviceOffering.iscustomized">
                      <a-form-item name="cpunumber" ref="cpunumber" class="form-item-hidden">
                        <a-input v-model:value="form.cpunumber"/>
                      </a-form-item>
                      <a-form-item
                        class="form-item-hidden"
                        v-if="(serviceOffering && !(serviceOffering.cpuspeed > 0))"
                        name="cpuspeed"
                        ref="cpuspeed">
                        <a-input v-model:value="form.cpuspeed"/>
                      </a-form-item>
                      <a-form-item class="form-item-hidden" name="memory" ref="memory">
                        <a-input v-model:value="form.memory"/>
                      </a-form-item>
                    </span>
                    <span v-if="!['templateid', 'isoid'].includes(imageType) && serviceOffering && !serviceOffering.diskofferingstrictness">
                      <a-step
                        :status="zoneSelected ? 'process' : 'wait'"
                        v-if="template && !template.deployasis && template.childtemplates && template.childtemplates.length > 0" >
                        <template #description>
                          <div v-if="zoneSelected">
                            <multi-disk-selection
                              :items="template.childtemplates"
                              :diskOfferings="options.diskOfferings"
                              :zoneId="zoneId"
                              @select-multi-disk-offering="updateMultiDiskOffering($event)" />
                          </div>
                        </template>
                      </a-step>
                      <div v-else-if="zoneSelected" class="root-offering-selection">
                            <disk-offering-selection
                              v-if="showOverrideDiskOfferingOption"
                              :items="options.diskOfferings"
                              :row-count="rowCount.diskOfferings"
                              :zoneId="zoneId"
                              :value="overrideDiskOffering ? overrideDiskOffering.id : ''"
                              :loading="loading.diskOfferings"
                              :preFillContent="dataPreFill"
                              :isIsoSelected="imageType==='isoid'"
                              :isRootDiskOffering="true"
                              @on-selected-root-disk-size="onSelectRootDiskSize"
                              @select-disk-offering-item="($event) => updateOverrideDiskOffering($event)"
                              @handle-search-filter="($event) => handleSearchFilter('diskOfferings', $event)"
                            ></disk-offering-selection>
                            <disk-size-selection
                              v-if="(overrideDiskOffering && (overrideDiskOffering.iscustomized || overrideDiskOffering.iscustomizediops || overrideDiskOffering.encrypt)) || (serviceOffering && serviceOffering.encryptroot)"
                              input-decorator="rootdisksize"
                              :value="form.rootdisksize"
                              :preFillContent="dataPreFill"
                              :minDiskSize="dataPreFill.minrootdisksize"
                              :rootDiskSelected="overrideDiskOffering"
                              :isCustomized="overrideDiskOffering && overrideDiskOffering.iscustomized"
                              :kmsKeys="options.kmsKeys"
                              :loadingKmsKeys="loading.kmsKeys"
                              :computeOfferingEncryptRoot="serviceOffering && serviceOffering.encryptroot"
                              @handler-error="handlerError"
                              @update-disk-size="updateFieldValue"
                              @update-root-disk-iops-value="updateIOPSValue"
                              @update-root-kms-key="updateRootKmsKey"/>
                            <a-form-item class="form-item-hidden">
                              <a-input v-model:value="form.rootdisksize"/>
                            </a-form-item>
                            <a-form-item class="form-item-hidden">
                              <a-input v-model:value="form.offeringKvdoEnable" />
                            </a-form-item>
                      </div>
                    </span>
                  </div>
                </template>
              </a-step>
              <a-step v-if="imageType === 'snapshotid' && storageSelectionEnabled" :title="$t('label.vm.storage.root')" status="process">
                <template #description>
                  <deployment-storage-selection :title="$t('label.vm.storage.root')" v-model:value="rootStorageSelection" :query="rootStorageQuery" />
                </template>
              </a-step>
              <a-step
                v-if="['templateid', 'isoid'].includes(imageType) && !isTemplateHypervisorExternal"
                :title="$t('label.rootdisk')"
                :status="serviceOffering?.id ? 'process' : 'wait'">
                <template #description>
                  <div v-if="zoneSelected && serviceOffering?.id" class="root-disk-configuration">
                    <template v-if="imageType === 'templateid'">
                      <section class="root-configuration-section root-offering-section">
                        <h4 class="root-section-heading"><span class="root-section-number">1</span>{{ $t('label.vm.root.offering') }}</h4>
                        <a-form-item :label="$t('label.override.root.diskoffering')">
                          <a-switch
                            v-model:checked="showOverrideDiskOfferingOption"
                            :disabled="serviceOffering?.diskofferingstrictness || (template && template.deployasis)"
                            @change="updateOverrideRootDiskShowParam" />
                          <p class="option-help">{{ $t('message.vm.root.offering.override') }}</p>
                        </a-form-item>
                        <div v-if="!showOverrideDiskOfferingOption" class="root-default-value">
                          <span>{{ defaultRootOfferingName }}</span>
                          <a-tag v-if="defaultRootOfferingName !== $t('label.vm.root.offering.default')">{{ $t('label.vm.root.offering.default') }}</a-tag>
                        </div>
                        <p v-if="serviceOffering?.diskofferingstrictness" class="option-help">{{ $t('message.vm.root.offering.required') }}</p>
                        <div v-if="template && template.deployasis" class="option-help">{{ $t('message.deployasis') }}</div>
                        <template v-if="serviceOffering && !serviceOffering.diskofferingstrictness">
                          <div
                            v-if="template && !template.deployasis && template.childtemplates && template.childtemplates.length > 0" >
                            <template>
                              <div v-if="zoneSelected">
                                <multi-disk-selection
                                  :items="template.childtemplates"
                                  :diskOfferings="options.diskOfferings"
                                  :zoneId="zoneId"
                                  @select-multi-disk-offering="updateMultiDiskOffering($event)" />
                              </div>
                            </template>
                          </div>

                          <template v-else>
                            <disk-offering-selection
                              v-if="showOverrideDiskOfferingOption"
                              :items="options.diskOfferings"
                              :row-count="rowCount.diskOfferings"
                              :zoneId="zoneId"
                              :value="overrideDiskOffering ? overrideDiskOffering.id : ''"
                              :loading="loading.diskOfferings"
                              :preFillContent="dataPreFill"
                              :isIsoSelected="imageType==='isoid'"
                              :isRootDiskOffering="true"
                              @on-selected-root-disk-size="onSelectRootDiskSize"
                              @select-disk-offering-item="($event) => updateOverrideDiskOffering($event)"
                              @handle-search-filter="($event) => handleSearchFilter('diskOfferings', $event)"
                            ></disk-offering-selection>
                          </template>
                        </template>
                      </section>
                      <section class="root-configuration-section root-size-section">
                        <h4 class="root-section-heading"><span class="root-section-number">2</span>{{ $t('label.vm.root.capacity') }}</h4>
                        <template v-if="!showOverrideDiskOfferingOption">
                          <div v-if="selectedRootDiskSize && !showRootDiskSizeChanger" class="root-default-value">
                            <span>{{ selectedRootDiskSize }} GB</span>
                            <a-tag v-if="serviceOffering?.diskofferingstrictness && !templateStrictCustomRoot">{{ $t('label.vm.disk.fixed') }}</a-tag>
                          </div>
                          <a-form-item :label="$t('label.override.rootdisk.size')">
                            <a-switch
                              v-model:checked="form.rootdisksizeitem"
                              :disabled="templateStrictCustomRoot || serviceOffering?.diskofferingstrictness || (template && template.deployasis)"
                              @change="changeRootDiskSizeOverride" />
                            <p class="option-help">{{ $t('message.vm.root.size.override') }}</p>
                          </a-form-item>
                          <disk-size-selection
                            v-if="showRootDiskSizeChanger"
                            input-decorator="rootdisksize"
                            :hide-size-label="true"
                            :value="form.rootdisksize"
                            :preFillContent="dataPreFill"
                            :isCustomized="true"
                            :minDiskSize="dataPreFill.minrootdisksize"
                            @update-disk-size="updateFieldValue" />
                        </template>
                        <a-form-item v-if="showOverrideDiskOfferingOption && overrideDiskOffering?.id && !overrideDiskOffering.iscustomized">
                          <a-input-number :value="overrideDiskOffering.disksize" disabled />
                          <span class="root-size-unit">GB</span>
                          <a-tag class="root-fixed-size-tag">{{ $t('label.vm.disk.fixed') }}</a-tag>
                        </a-form-item>
                            <disk-size-selection
                              v-if="!serviceOffering.diskofferingstrictness && !(template?.childtemplates?.length > 0) && ((overrideDiskOffering && (overrideDiskOffering.iscustomized || overrideDiskOffering.iscustomizediops || overrideDiskOffering.encrypt)) || (serviceOffering && serviceOffering.encryptroot))"
                              input-decorator="rootdisksize"
                              :value="form.rootdisksize"
                              :hide-size-label="true"
                              :show-size="showOverrideDiskOfferingOption"
                              :preFillContent="dataPreFill"
                              :minDiskSize="dataPreFill.minrootdisksize"
                              :rootDiskSelected="overrideDiskOffering"
                              :isCustomized="overrideDiskOffering && overrideDiskOffering.iscustomized"
                              :kmsKeys="options.kmsKeys"
                              :loadingKmsKeys="loading.kmsKeys"
                              :computeOfferingEncryptRoot="serviceOffering && serviceOffering.encryptroot"
                              @handler-error="handlerError"
                              @update-disk-size="updateFieldValue"
                              @update-root-disk-iops-value="updateIOPSValue"
                              @update-root-kms-key="updateRootKmsKey"/>

                        <a-form-item class="form-item-hidden"><a-input v-model:value="form.rootdisksize"/></a-form-item>
                        <a-form-item class="form-item-hidden"><a-input v-model:value="form.offeringKvdoEnable"/></a-form-item>
                      </section>
                    </template>
                    <template v-else>
                      <section class="root-configuration-section root-offering-section">
                        <h4 class="root-section-heading"><span class="root-section-number">1</span>{{ $t('label.vm.root.offering') }}</h4>
                        <a-alert v-if="isoRootOfferingStrict" type="info" :message="mappedRootOffering?.displaytext || serviceOffering.diskofferingname" style="margin: 12px 0" />
                        <disk-offering-selection
                          v-else
                          :items="options.diskOfferings"
                          :row-count="rowCount.diskOfferings"
                          :zoneId="zoneId"
                          :value="diskOffering ? diskOffering.id : ''"
                          :loading="loading.diskOfferings"
                          :preFillContent="dataPreFill"
                          :isIsoSelected="true"
                          @on-selected-disk-size="onSelectDiskSize"
                          @select-disk-offering-item="($event) => updateDiskOffering($event)"
                          @handle-search-filter="($event) => handleSearchFilter('diskOfferings', $event)" />
                      </section>
                      <section v-if="diskOffering?.id" class="root-configuration-section root-size-section">
                        <h4 class="root-section-heading"><span class="root-section-number">2</span>{{ $t('label.vm.root.capacity') }}</h4>
                        <a-form-item v-if="!diskOffering.iscustomized">
                          <a-input-number :value="diskOffering.disksize" disabled />
                          <span class="root-size-unit">GB</span>
                          <a-tag class="root-fixed-size-tag">{{ $t('label.vm.disk.fixed') }}</a-tag>
                        </a-form-item>
                        <disk-size-selection
                          v-if="diskOffering.iscustomized || diskOffering.iscustomizediops || diskOffering.encrypt"
                          input-decorator="size"
                          :hide-size-label="true"
                          :show-size="true"
                          :preFillContent="dataPreFill"
                          :diskSelected="diskSelected"
                          :isCustomized="diskOffering.iscustomized"
                          :kmsKeys="options.kmsKeys"
                          :loadingKmsKeys="loading.kmsKeys"
                          @handler-error="handlerError"
                          @update-disk-size="updateFieldValue"
                          @update-iops-value="updateIOPSValue"
                          @update-data-kms-key="updateDataKmsKey" />
                        <a-form-item class="form-item-hidden"><a-input v-model:value="form.size"/></a-form-item>
                      </section>
                    </template>
                    <deployment-storage-selection
                      v-if="storageSelectionEnabled && (imageType === 'templateid' || diskOffering?.id)"
                      class="root-storage-section"
                      v-model:value="rootStorageSelection"
                      :title="$t('label.vm.storage.root')"
                      :query="rootStorageQuery" />
                  </div>
                </template>
              </a-step>
              <a-step
                :title="$t('label.data.disk')"
                :status="zoneSelected ? 'process' : 'wait'"
                v-if="template && !template.deployasis && template.childtemplates && template.childtemplates.length > 0" >
                <template #description>
                  <div v-if="zoneSelected">
                    <multi-disk-selection
                      :items="template.childtemplates"
                      :diskOfferings="options.diskOfferings"
                      :zoneId="zoneId"
                      @select-multi-disk-offering="updateMultiDiskOffering($event)" />
                  </div>
                </template>
              </a-step>
              <a-step
                v-else-if="imageType !== 'isoid'"
                :title="$t('label.data.disk')"
                :disabled="isTemplateHypervisorExternal ? true : false"
                :status="zoneSelected ? 'process' : 'wait'">
                <template #description>
                  <div v-if="zoneSelected && !isTemplateHypervisorExternal">
                    <disk-offering-selection
                      :items="options.diskOfferings"
                      :row-count="rowCount.diskOfferings"
                      :zoneId="zoneId"
                      :value="diskOffering ? diskOffering.id : ''"
                      :loading="loading.diskOfferings"
                      :preFillContent="dataPreFill"
                      :isIsoSelected="imageType==='isoid'"
                      @on-selected-disk-size="onSelectDiskSize"
                      @select-disk-offering-item="($event) => updateDiskOffering($event)"
                      @handle-search-filter="($event) => handleSearchFilter('diskOfferings', $event)"
                    ></disk-offering-selection>
                    <disk-size-selection
                      v-if="diskOffering && (diskOffering.iscustomizediops || diskOffering.encrypt)"
                      input-decorator="size"
                      :show-size="false"
                      :preFillContent="dataPreFill"
                      :diskSelected="diskSelected"
                      :isCustomized="diskOffering.iscustomized"
                      :kmsKeys="options.kmsKeys"
                      :loadingKmsKeys="loading.kmsKeys"
                      @handler-error="handlerError"
                      @update-disk-size="updateFieldValue"
                      @update-iops-value="updateIOPSValue"
                      @update-data-kms-key="updateDataKmsKey"/>
                    <a-form-item class="form-item-hidden"><a-input v-model:value="form.size"/></a-form-item>
                    <template v-if="imageType === 'templateid' && diskOffering?.id && diskOffering.id !== '0'">
                      <disk-quantity-selection
                        :offering="diskOffering"
                        v-model:size="form.size"
                        v-model:count="form.datadiskcount" />
                      <deployment-storage-selection
                        v-if="storageSelectionEnabled"
                        v-model:value="dataStorageSelection"
                        :title="$t('label.vm.storage.data')"
                        :query="dataStorageQuery" />
                    </template>
                  </div>
                  <div v-else-if="isTemplateHypervisorExternal" style="margin-bottom: 20px; margin-top: 7px">
                    {{ $t('message.host.external.datadisk') }}
                  </div>
                </template>
              </a-step>
              <a-step v-if="imageType === 'isoid' && zoneSelected" :title="$t('label.data.disk')" :status="'process'">
                <template #description>
                  <iso-additional-data-disks
                    v-model:value="isoDataDiskSelection"
                    :items="options.diskOfferings"
                    :zone-id="zoneId"
                    :loading="loading.diskOfferings"
                    :kms-keys="options.kmsKeys"
                    :loading-kms-keys="loading.kmsKeys"
                    @search="handleSearchFilter('diskOfferings', $event)">
                    <deployment-storage-selection
                      v-if="storageSelectionEnabled"
                        v-model:value="dataStorageSelection"
                      :title="$t('label.vm.storage.data')"
                      :query="dataStorageQuery" />
                  </iso-additional-data-disks>
                </template>
              </a-step>
              <a-step
                :title="$t('label.networks')"
                :status="zoneSelected ? 'process' : 'wait'"
                v-if="zone && zone.networktype !== 'Basic'">
                <template #description>
                  <div v-if="zoneSelected" style="margin-top: 5px">
                    <div style="margin-bottom: 10px">
                      {{ $t('message.network.selection') + ('createNetwork' in $store.getters.apis ? ' ' + $t('message.network.selection.new.network') : '') }}
                    </div>
                    <div v-if="vm.templateid && templateNics && templateNics.length > 0">
                      <instance-nics-network-select-list-view
                        :nics="templateNics"
                        :zoneid="selectedZone"
                        @select="handleNicsNetworkSelection" />
                    </div>
                    <div v-show="!(vm.templateid && templateNics && templateNics.length > 0)" >
                      <network-selection
                        :items="options.networks"
                        :row-count="rowCount.networks"
                        :value="networkOfferingIds"
                        :loading="loading.networks"
                        :zoneId="zoneId"
                        :preFillContent="dataPreFill"
                        @select-network-item="($event) => updateNetworks($event)"
                        @handle-search-filter="($event) => handleSearchFilter('networks', $event)"
                      ></network-selection>
                      <network-configuration
                        v-if="networks.length > 0"
                        :items="networks"
                        :preFillContent="dataPreFill"
                        @update-network-config="($event) => updateNetworkConfig($event)"
                        @handler-error="($event) => hasError = $event"
                        @select-default-network-item="($event) => updateDefaultNetworks($event)"
                      ></network-configuration>
                    </div>
                  </div>
                </template>
              </a-step>
              <a-step
                v-if="showSecurityGroupSection"
                :title="$t('label.security.groups')"
                :status="zoneSelected ? 'process' : 'wait'">
                <template #description>
                  <security-group-selection
                    :zoneId="zoneId"
                    :value="securitygroupids"
                    :loading="loading.networks"
                    :preFillContent="dataPreFill"
                    :domainId="owner.domainid"
                    :account="owner.account"
                    :projectId="owner.projectid"
                    @select-security-group-item="($event) => updateSecurityGroups($event)"></security-group-selection>
                </template>
              </a-step>
              <a-step
                v-if="isUserAllowedToListSshKeys && !isCreationSource"
                :title="$t('label.sshkeypairs')"
                :status="zoneSelected ? 'process' : 'wait'">
                <template #description>
                  <div v-if="zoneSelected">
                    <ssh-key-pair-selection
                      :items="options.sshKeyPairs"
                      :row-count="rowCount.sshKeyPairs"
                      :zoneId="zoneId"
                      :value="sshKeyPairs"
                      :loading="loading.sshKeyPairs"
                      :preFillContent="dataPreFill"
                      @select-ssh-key-pair-item="($event) => updateSshKeyPairs($event)"
                      @handle-search-filter="($event) => handleSearchFilter('sshKeyPairs', $event)"
                    />
                  </div>
                </template>
              </a-step>
              <a-step
                :title="$t('label.ovf.properties')"
                :status="zoneSelected ? 'process' : 'wait'"
                v-if="vm.templateid && templateProperties && Object.keys(templateProperties).length > 0">
                <template #description>
                  <div v-for="(props, category) in templateProperties" :key="category">
                    <a-alert :message="'Category: ' + category + ' (' + props.length + ' properties)'" type="info" />
                    <div style="margin-left: 15px; margin-top: 10px">
                      <a-form-item
                        v-for="(property, propertyIndex) in props"
                        :key="propertyIndex"
                        :v-bind="property.key"
                        :name="'properties.' + escapePropertyKey(property.key)"
                        :ref="'properties.' + escapePropertyKey(property.key)">
                        <tooltip-label style="text-transform: capitalize" :title="property.label" :tooltip="property.description"/>

                        <span v-if="property.type && property.type==='boolean'">
                          <a-switch
                            v-model:checked="form.properties[escapePropertyKey(property.key)]"
                            :placeholder="property.description"
                          />
                        </span>
                        <span v-else-if="property.type && (property.type==='int' || property.type==='real')">
                          <a-input-number
                            v-model:value="form.properties[escapePropertyKey(property.key)]"
                            :placeholder="property.description"
                            :min="getPropertyQualifiers(property.qualifiers, 'number-select').min"
                            :max="getPropertyQualifiers(property.qualifiers, 'number-select').max" />
                        </span>
                        <span v-else-if="property.type && property.type==='string' && property.qualifiers && property.qualifiers.startsWith('ValueMap')">
                          <a-select
                            showSearch
                            optionFilterProp="label"
                            v-model:value="form.properties[escapePropertyKey(property.key)]"
                            :placeholder="property.description"
                            :filterOption="(input, option) => {
                              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                            }"
                          >
                            <a-select-option v-for="opt in getPropertyQualifiers(property.qualifiers, 'select')" :key="opt">
                              {{ opt }}
                            </a-select-option>
                          </a-select>
                        </span>
                        <span v-else-if="property.type && property.type==='string' && property.password">
                          <a-input-password
                            v-model:value="form.properties[escapePropertyKey(property.key)]"
                            :placeholder="property.description" />
                        </span>
                        <span v-else>
                          <a-input
                            v-model:value="form.properties[escapePropertyKey(property.key)]"
                            :placeholder="property.description" />
                        </span>
                      </a-form-item>
                    </div>
                  </div>
                </template>
              </a-step>
              <a-step
                v-if="zoneAllowsBackupOperations"
                :title="$t('label.backup')"
                :status="zoneSelected ? 'process' : 'wait'">
                <template #description>
                  <div v-if="zoneSelected" style="margin-top: 15px">
                    <deploy-instance-backup-selection
                      :zoneId="zoneId"
                      v-model:backupOfferingId="form.backupofferingid"
                      :backupSchedules="backupSchedules"
                      @change-backup-offering="onChangeBackupOffering"
                      @add-backup-schedule="onAddBackupSchedule"
                      @delete-backup-schedule="backupSchedules = backupSchedules.filter(schedule => schedule.id !== $event.id)" />
                    <a-form-item class="form-item-hidden">
                      <a-input v-model:value="form.backupofferingid" />
                    </a-form-item>
                  </div>
                </template>
              </a-step>
              <a-step
                :title="$t('label.advanced.mode')"
                :status="zoneSelected ? 'process' : 'wait'">
                <template #description v-if="zoneSelected">
                  <span>
                    {{ $t('label.isadvanced') }}
                    <a-switch v-model:checked="showDetails" style="margin-left: 10px"/>
                  </span>
                  <div style="margin-top: 15px" v-if="showDetails">
                    <div
                      v-if="['KVM', 'VMware', 'XenServer'].includes(hypervisor) && ((vm.templateid && template && !template.deployasis) || vm.isoid)">
                      <a-form-item :label="$t('label.boottype')" name="boottype" ref="boottype">
                        <a-select
                          v-model:value="form.boottype"
                          @change="onBootTypeChange"
                          showSearch
                          optionFilterProp="label"
                          :filterOption="filterOption">
                          <a-select-option v-for="bootType in options.bootTypes" :key="bootType.id" :label="bootType.description">
                            {{ bootType.description }}
                          </a-select-option>
                        </a-select>
                      </a-form-item>
                      <a-form-item :label="$t('label.bootmode')" name="bootmode" ref="bootmode">
                        <a-select
                          v-model:value="form.bootmode"
                          showSearch
                          optionFilterProp="label"
                          :filterOption="filterOption">
                          <a-select-option v-for="bootMode in options.bootModes" :key="bootMode.id" :label="bootMode.description">
                            {{ bootMode.description }}
                          </a-select-option>
                        </a-select>
                      </a-form-item>
                    </div>
                    <a-form-item v-if="hypervisor === 'KVM' && !isCreationSource" name="machinecompatibility" ref="machinecompatibility">
                      <template #label>
                        <tooltip-label :title="$t('label.ft.machine.compatibility')" :tooltip="$t('message.ft.machine.compatibility.desc')"/>
                      </template>
                      <a-select v-model:value="form.machinecompatibility">
                        <a-select-option value="standard">
                          {{ $t('label.ft.machine.compatibility.standard') }}
                        </a-select-option>
                        <a-select-option value="ft-compatible">
                          {{ $t('label.ft.machine.compatibility.ft') }}
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                    <a-form-item v-if="hypervisor === 'KVM' && !isCreationSource" :label="$t('label.tpm')" name="tpmversion" ref="tpmversion">
                      <a-select
                        v-model:value="form.tpmversion"
                        @change="form.tpmmodel = 'tpm-tis'"
                        showSearch
                        optionFilterProp="label"
                        :filterOption="filterOption">
                        <a-select-option v-for="tpmversion in options.tpmversion" :key="tpmversion.id">
                          {{ tpmversion.description }}
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                    <a-form-item v-if="hypervisor === 'KVM' && ['V1_2', 'V2_0'].includes(form.tpmversion)" :label="$t('label.tpm.model')" name="tpmmodel">
                      <a-select v-model:value="form.tpmmodel" :options="form.tpmversion === 'V1_2' ? [{ value: 'tpm-tis', label: 'TIS' }] : [{ value: 'tpm-tis', label: 'TIS' }, { value: 'tpm-crb', label: 'CRB' }]" />
                    </a-form-item>
                    <a-form-item
                      :label="$t('label.bootintosetup')"
                      v-if="zoneSelected && ((imageType === 'isoid' && hypervisor === 'VMware') || (imageType === 'templateid' && template && template.hypervisor === 'VMware'))"
                      name="bootintosetup"
                      ref="bootintosetup">
                      <a-switch v-model:checked="form.bootintosetup" />
                    </a-form-item>
                    <a-form-item name="dynamicscalingenabled" ref="dynamicscalingenabled" v-if="!!template && !isTemplateHypervisorExternal">
                      <template #label>
                        <tooltip-label :title="$t('label.dynamicscalingenabled')" :tooltip="$t('label.dynamicscalingenabled.tooltip')"/>
                      </template>
                      <a-form-item name="dynamicscalingenabled" ref="dynamicscalingenabled">
                        <a-switch
                          v-model:checked="form.dynamicscalingenabled"
                          :checked="isDynamicallyScalable() && dynamicscalingenabled"
                          :disabled="!isDynamicallyScalable()"
                          @change="val => { dynamicscalingenabled = val }"/>
                      </a-form-item>
                    </a-form-item>
                    <a-form-item name="showLeaseOptions" ref="showLeaseOptions" v-if="isLeaseFeatureEnabled">
                      <template #label>
                        <tooltip-label :title="$t('label.lease.enable')" :tooltip="$t('label.lease.enable.tooltip')"/>
                      </template>
                      <a-switch v-model:checked="showLeaseOptions" @change="onToggleLeaseData"/>
                    </a-form-item>
                    <a-row :gutter="12" v-if="isLeaseFeatureEnabled && showLeaseOptions">
                      <a-col :md="12" :lg="12">
                        <a-form-item name="leaseduration" ref="leaseduration">
                          <template #label>
                            <tooltip-label :title="$t('label.leaseduration')" />
                          </template>
                          <a-input
                            v-model:value="form.leaseduration"
                            :placeholder="$t('label.instance.lease.placeholder')"/>
                        </a-form-item>
                      </a-col>
                      <a-col :md="12" :lg="12">
                        <a-form-item name="leaseexpiryaction" ref="leaseexpiryaction">
                          <template #label>
                            <tooltip-label :title="$t('label.leaseexpiryaction')"  />
                          </template>
                          <a-select v-model:value="form.leaseexpiryaction" :defaultValue="leaseexpiryaction">
                            <a-select-option v-for="action in expiryActions" :key="action" :label="action" />
                          </a-select>
                        </a-form-item>
                      </a-col>
                    </a-row>
                    <a-form-item v-if="!isCreationSource" :label="$t('label.user.data')">
                      <a-card>
                        <div v-if="this.template && this.template.userdataid">
                          <a-typography-text>
                            Userdata "{{ $t(this.template.userdataname) }}" is linked with template "{{ $t(this.template.name) }}" with override policy "{{ $t(this.template.userdatapolicy) }}"
                          </a-typography-text><br/><br/>
                          <div v-if="templateUserDataParams.length > 0 && !doUserdataOverride">
                            <a-typography-text v-if="this.template && this.template.userdataid && templateUserDataParams.length > 0">
                              Enter the values for the variables in userdata
                            </a-typography-text>
                            <a-input-group>
                              <a-table
                                size="small"
                                style="overflow-y: auto"
                                :columns="userDataParamCols"
                                :dataSource="templateUserDataParams"
                                :pagination="false"
                                :rowKey="record => record.key">
                                <template #bodyCell="{ column, record }">
                                  <template v-if="column.key === 'value'">
                                    <a-input v-model:value="templateUserDataValues[record.key]" />
                                  </template>
                                </template>
                              </a-table>
                            </a-input-group>
                          </div>
                        </div>
                        <div v-if="this.iso && this.iso.userdataid">
                          <a-typography-text>
                            Userdata "{{ $t(this.iso.userdataname) }}" is linked with ISO "{{ $t(this.iso.name) }}" with override policy "{{ $t(this.iso.userdatapolicy) }}"
                          </a-typography-text><br/><br/>
                          <div v-if="templateUserDataParams.length > 0 && !doUserdataOverride">
                            <a-typography-text v-if="this.iso && this.iso.userdataid && templateUserDataParams.length > 0">
                              Enter the values for the variables in userdata
                            </a-typography-text>
                            <a-input-group>
                              <a-table
                                size="small"
                                style="overflow-y: auto"
                                :columns="userDataParamCols"
                                :dataSource="templateUserDataParams"
                                :pagination="false"
                                :rowKey="record => record.key">
                                <template #bodyCell="{ column, record }">
                                  <template v-if="column.key === 'value'">
                                    <a-input v-model:value="templateUserDataValues[record.key]" />
                                  </template>
                                </template>
                              </a-table>
                            </a-input-group>
                          </div>
                        </div><br/><br/>
                        <div v-if="userdataDefaultOverridePolicy === 'ALLOWOVERRIDE' || userdataDefaultOverridePolicy === 'APPEND' || !userdataDefaultOverridePolicy">
                          <span v-if="userdataDefaultOverridePolicy === 'ALLOWOVERRIDE'" >
                            {{ $t('label.user.data.do.override') }}
                            <a-switch v-model:checked="doUserdataOverride" style="margin-left: 10px"/>
                          </span>
                          <span v-if="userdataDefaultOverridePolicy === 'APPEND'">
                            {{ $t('label.user.data.do.append') }}
                            <a-switch v-model:checked="doUserdataAppend" style="margin-left: 10px"/>
                          </span>
                          <a-step
                            :status="zoneSelected ? 'process' : 'wait'">
                            <template #description>
                              <div v-if="doUserdataOverride || doUserdataAppend || !userdataDefaultOverridePolicy" style="margin-top: 15px">
                                <a-card
                                  :tabList="userdataTabList"
                                  :activeTabKey="userdataTabKey"
                                  @tabChange="key => onUserdataTabChange(key, 'userdataTabKey')">
                                  <div v-if="userdataTabKey === 'userdataregistered'">
                                    <a-step
                                      v-if="isUserAllowedToListUserDatas"
                                      :status="zoneSelected ? 'process' : 'wait'">
                                      <template #description>
                                        <div v-if="zoneSelected">
                                          <user-data-selection
                                            :items="options.userDatas"
                                            :row-count="rowCount.userDatas"
                                            :zoneId="zoneId"
                                            :disabled="template && template.userdatapolicy === 'DENYOVERRIDE'"
                                            :loading="loading.userDatas"
                                            :preFillContent="dataPreFill"
                                            @select-user-data-item="($event) => updateUserData($event)"
                                            @handle-search-filter="($event) => handleSearchFilter('userData', $event)"
                                          />
                                          <div v-if="userDataParams.length > 0">
                                            <a-input-group>
                                              <a-table
                                                size="small"
                                                style="overflow-y: auto"
                                                :columns="userDataParamCols"
                                                :dataSource="userDataParams"
                                                :pagination="false"
                                                :rowKey="record => record.key">
                                                <template #bodyCell="{ column, record }">
                                                  <template v-if="column.key === 'value'">
                                                    <a-input v-model:value="userDataValues[record.key]" />
                                                  </template>
                                                </template>
                                              </a-table>
                                            </a-input-group>
                                          </div>
                                        </div>
                                      </template>
                                    </a-step>
                                  </div>
                                  <div v-else>
                                    <a-form-item name="userdata" ref="userdata" >
                                      <a-textarea
                                        placeholder="Userdata"
                                        v-model:value="form.userdata">
                                      </a-textarea>
                                    </a-form-item>
                                  </div>
                                </a-card>
                              </div>
                            </template>
                          </a-step>
                        </div>
                      </a-card>
                    </a-form-item>
                    <a-form-item v-if="extraConfigEnabled" name="extraconfig" ref="extraconfig">
                      <template #label>
                        <tooltip-label :title="$t('label.extraconfig')" :tooltip="$t('label.extraconfig.tooltip')"/>
                      </template>
                      <a-textarea v-model:value="form.extraconfig"/>
                    </a-form-item>
                    <a-form-item :label="$t('label.affinity.groups')">
                      <affinity-group-selection
                        :items="options.affinityGroups"
                        :row-count="rowCount.affinityGroups"
                        :zoneId="zoneId"
                        :value="affinityGroupIds"
                        :loading="loading.affinityGroups"
                        :preFillContent="dataPreFill"
                        @select-affinity-group-item="($event) => updateAffinityGroups($event)"
                        @handle-search-filter="($event) => handleSearchFilter('affinityGroups', $event)"/>
                    </a-form-item>
                    <a-form-item name="nicmultiqueuenumber" ref="nicmultiqueuenumber" v-if="vm.templateid && ['KVM'].includes(hypervisor)">
                      <template #label>
                        <tooltip-label :title="$t('label.nicmultiqueuenumber')" :tooltip="$t('label.nicmultiqueuenumber.tooltip')"/>
                      </template>
                      <a-input-number
                        style="width: 100%;"
                        v-model:value="form.nicmultiqueuenumber" />
                    </a-form-item>
                    <a-form-item name="nicpackedvirtqueuesenabled" ref="nicpackedvirtqueuesenabled" v-if="vm.templateid && ['KVM'].includes(hypervisor)">
                      <template #label>
                        <tooltip-label :title="$t('label.nicpackedvirtqueuesenabled')" :tooltip="$t('label.nicpackedvirtqueuesenabled.tooltip')"/>
                      </template>
                      <a-switch
                        v-model:checked="form.nicpackedvirtqueuesenabled"
                        :checked="nicpackedvirtqueuesenabled"
                        @change="val => { nicpackedvirtqueuesenabled = val }"/>
                    </a-form-item>
                    <a-form-item name="iothreadsenabled" ref="iothreadsenabled" v-if="vm.templateid && ['KVM'].includes(hypervisor)">
                      <template #label>
                        <tooltip-label :title="$t('label.iothreadsenabled')" :tooltip="$t('label.iothreadsenabled.tooltip')"/>
                      </template>
                      <a-form-item name="iothreadsenabled" ref="iothreadsenabled">
                        <a-switch
                          v-model:checked="form.iothreadsenabled"
                          :checked="iothreadsenabled"
                          @change="val => { iothreadsenabled = val }"/>
                      </a-form-item>
                    </a-form-item>
                    <a-form-item name="iodriverpolicy" ref="iodriverpolicy" v-if="vm.templateid && ['KVM'].includes(hypervisor)">
                      <template #label>
                        <tooltip-label :title="$t('label.iodriverpolicy')" :tooltip="$t('label.iodriverpolicy.tooltip')"/>
                      </template>
                      <a-select
                        v-model:value="form.iodriverpolicy"
                        optionFilterProp="label"
                        :filterOption="filterOption">
                        <a-select-option v-for="iodriverpolicy in options.ioPolicyTypes" :key="iodriverpolicy.id" :label="iodriverpolicy.description">
                          {{ iodriverpolicy.description }}
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                  </div>
                </template>
              </a-step>
              <a-step
                :title="$t('label.details')"
                :status="zoneSelected ? 'process' : 'wait'">
                <template #description v-if="zoneSelected">
                  <div style="margin-top: 15px">
                    {{ $t('message.vm.review.launch') }}
                    <a-form-item :label="$t('label.name.optional')" name="name" ref="name">
                      <a-input v-model:value="form.name" />
                    </a-form-item>
                    <a-form-item :label="$t('label.group.optional')" name="group" ref="group">
                      <a-auto-complete
                        v-model:value="form.group"
                        :filterOption="filterOption"
                        :options="options.instanceGroups" />
                    </a-form-item>
                    <a-form-item :label="$t('label.keyboard')" name="keyboard" ref="keyboard">
                      <a-select
                        v-model:value="form.keyboard"
                        :options="keyboardSelectOptions"
                        showSearch
                        optionFilterProp="label"
                        :filterOption="filterOption"
                      ></a-select>
                    </a-form-item>
                    <a-form-item :label="$t('label.deploy.vm.number')" name="vmNumber" ref="vmNumber">
                      <a-input-number :min=1 :max="isCreationSource ? 1 : 50" :disabled="isCreationSource" :maxlength="2" v-model:value="form.vmNumber" />
                    </a-form-item>
                    <a-form-item name="externaldetails" ref="externaldetails" v-if="imageType === 'templateid' && isTemplateHypervisorExternal">
                      <template #label>
                        <tooltip-label :title="$t('label.externaldetails')" :tooltip="apiParams.externaldetails.description" />
                      </template>
                      <a-switch v-model:checked="externalDetailsEnabled" @change="onExternalDetailsEnabledChange"/>
                      <a-card v-if="externalDetailsEnabled" style="margin-top: 10px">
                        <div style="margin-bottom: 10px">{{ $t('message.add.orchestrator.resource.details') }}</div>
                        <details-input
                          v-model:value="form.externaldetails" />
                      </a-card>
                    </a-form-item>
                    <a-form-item :label="$t('label.vm.start.after.creation')" name="startvm" ref="startvm">
                      <a-switch v-model:checked="form.startvm" />
                      <p style="margin-top: 8px">{{ $t(form.startvm ? 'message.vm.start.enabled' : 'message.vm.start.disabled') }}</p>
                    </a-form-item>
                  </div>
                </template>
              </a-step>
              <a-step
                :title="$t('label.license.agreements')"
                :status="zoneSelected ? 'process' : 'wait'"
                v-if="vm.templateid && templateLicenses && templateLicenses.length > 0">
                <template #description>
                  <div style="margin-top: 10px">
                    {{ $t('message.read.accept.license.agreements') }}
                    <a-form-item
                      style="margin-top: 10px"
                      v-for="(license, licenseIndex) in templateLicenses"
                      :key="licenseIndex"
                      :v-bind="license.id">
                      <template #label>
                        <tooltip-label style="text-transform: capitalize" :title="$t('label.agreement' + ' ' + (licenseIndex+1) + ': ' + license.name)"/>
                      </template>
                      <a-textarea
                        v-model:value="license.text"
                        :auto-size="{ minRows: 3, maxRows: 8 }"
                        readOnly />
                      <a-checkbox
                        style="margin-top: 10px"
                        v-model:checked="form.licensesaccepted">
                        {{ $t('label.i.accept.all.license.agreements') }}
                      </a-checkbox>
                    </a-form-item>
                  </div>
                </template>
              </a-step>
            </a-steps>
            <div class="card-footer" v-if="isMobile()">
              <deploy-buttons
                :loading="loading.deploy"
                :disabled="diskPlanIncomplete"
                :deployButtonText="form.startvm ? $t('label.launch.vm') : $t('label.create.vm')"
                :deployButtonMenuOptions="deployMenuOptions"
                @handle-cancel="() => $router.back()"
                @handle-deploy="handleSubmit"
                @handle-deploy-menu="(index, e) => handleSubmitAndStay(e)" />
            </div>
          </a-form>
        </a-card>
      </a-col>
      <a-col :md="24" :lg="7" v-if="!isMobile()" class="deploy-vm-summary-column">
        <div class="vm-info-card">
          <info-card :footerVisible="true" :resource="vmSummary" :title="$t('label.yourinstance')" @change-resource="(data) => resource = data">
            <template #details>
              <creation-source-summary v-if="isCreationSource" :source="selectedCreationSource" :target-storage="rootStorageSelection" />
              <div v-if="serviceOffering?.id && !isTemplateHypervisorExternal && !isCreationSource" class="vm-storage-summary">
                <div><strong>{{ $t('label.vm.storage.root') }}</strong><br>{{ rootStorageSelection.name || $t('label.vm.storage.auto') }} · {{ selectedRootDiskSize || '—' }} GB</div>
                <div v-if="selectedDataDiskOffering?.id"><strong>{{ $t('label.vm.storage.data') }}</strong><br>
                  {{ dataStorageSelection.name || $t('label.vm.storage.auto') }} · {{ selectedDataDiskSize }} GB × {{ selectedDataDiskCount }} · {{ selectedDataDiskSize * selectedDataDiskCount }} GB
                </div>
              </div>
            </template>
            <template #footer-content>
              <deploy-buttons
                :loading="loading.deploy"
                :disabled="diskPlanIncomplete"
                :deployButtonText="form.startvm ? $t('label.launch.vm') : $t('label.create.vm')"
                :deployButtonMenuOptions="deployMenuOptions"
                @handle-cancel="() => $router.back()"
                @handle-deploy="handleSubmit"
                @handle-deploy-menu="(index, e) => handleSubmitAndStay(e)" />
            </template>
          </info-card>
        </div>
      </a-col>
    </a-row>
    <mold-dialog v-if="sourceConfirmVisible" :title="$t('label.creation.source.confirm')" @cancel="sourceConfirmVisible = false">
      <p><strong>{{ form.name || $t('label.name.optional') }}</strong> · {{ $t(form.startvm ? 'label.launch.vm' : 'label.create.vm') }}</p>
      <creation-source-summary :source="selectedCreationSource" :target-storage="rootStorageSelection" />
      <p v-if="imageType === 'volumeid'">{{ $t('message.creation.source.delete.policy') }}</p>
      <a-checkbox v-if="imageType === 'volumeid'" v-model:checked="sourceAcknowledged">{{ $t('label.creation.source.ack') }}</a-checkbox>
      <template #footer>
        <a-button @click="sourceConfirmVisible = false">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :disabled="imageType === 'volumeid' && !sourceAcknowledged" :loading="loading.deploy" @click="confirmCreationSource">{{ $t(form.startvm ? 'label.launch.vm' : 'label.create.vm') }}</a-button>
      </template>
    </mold-dialog>
  </div>
</template>

<script>
import { strictTemplateCustomRoot } from '@/utils/templateRootDisk'
import { deploymentTpmParams } from '@/utils/tpm'
import AdditionalIsoSelection from './AdditionalIsoSelection.vue'
import CreationSourceSelection from './wizard/CreationSourceSelection.vue'
import CreationSourceSummary from './wizard/CreationSourceSummary.vue'
import CreationSourceOperations from './wizard/CreationSourceOperations.vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import { ref, reactive, toRaw, nextTick, h } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { Button, message } from 'ant-design-vue'
import { getAPI, postAPI } from '@/api'
import { isAdmin } from '@/role'
import _ from 'lodash'
import { mixin, mixinDevice } from '@/utils/mixin.js'
import store from '@/store'
import eventBus from '@/config/eventBus'

import OwnershipSelection from '@views/compute/wizard/OwnershipSelection'
import InfoCard from '@/components/view/InfoCard'
import DeployButtons from '@views/compute/wizard/DeployButtons'
import ResourceIcon from '@/components/view/ResourceIcon'
import ZoneBlockRadioGroupSelect from '@views/compute/wizard/ZoneBlockRadioGroupSelect'
import BlockRadioGroupSelect from '@/components/widgets/BlockRadioGroupSelect'
import ComputeOfferingSelection from '@views/compute/wizard/ComputeOfferingSelection'
import ComputeSelection from '@views/compute/wizard/ComputeSelection'
import DiskOfferingSelection from '@views/compute/wizard/DiskOfferingSelection'
import DeploymentStorageSelection from '@views/compute/wizard/DeploymentStorageSelection'
import IsoAdditionalDataDisks from '@views/compute/wizard/IsoAdditionalDataDisks'
import DiskQuantitySelection from './wizard/DiskQuantitySelection'
import { deploymentStorageQuery, dataDiskRequest, dataDiskDeviceIds, completeIsoDiskDeployment } from '@/utils/vmDiskDeployment'
import DiskSizeSelection from '@views/compute/wizard/DiskSizeSelection'
import MultiDiskSelection from '@views/compute/wizard/MultiDiskSelection'
import TemplateIsoSelection from '@views/compute/wizard/TemplateIsoSelection'
import OsBasedImageSelection from '@views/compute/wizard/OsBasedImageSelection'
import AffinityGroupSelection from '@views/compute/wizard/AffinityGroupSelection'
import NetworkSelection from '@views/compute/wizard/NetworkSelection'
import NetworkConfiguration from '@views/compute/wizard/NetworkConfiguration'
import SshKeyPairSelection from '@views/compute/wizard/SshKeyPairSelection'
import UserDataSelection from '@views/compute/wizard/UserDataSelection'
import SecurityGroupSelection from '@views/compute/wizard/SecurityGroupSelection'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import InstanceNicsNetworkSelectListView from '@/components/view/InstanceNicsNetworkSelectListView'
import DetailsInput from '@/components/widgets/DetailsInput'
import DeployInstanceBackupSelection from '@views/compute/wizard/DeployInstanceBackupSelection'

export default {
  name: 'Wizard',
  components: {
    ReloadOutlined,
    AdditionalIsoSelection,
    CreationSourceSelection,
    CreationSourceSummary,
    CreationSourceOperations,
    MoldDialog,
    OwnershipSelection,
    InfoCard,
    DeployButtons,
    ResourceIcon,
    ZoneBlockRadioGroupSelect,
    BlockRadioGroupSelect,
    SshKeyPairSelection,
    UserDataSelection,
    NetworkConfiguration,
    NetworkSelection,
    AffinityGroupSelection,
    TemplateIsoSelection,
    OsBasedImageSelection,
    DiskSizeSelection,
    DeploymentStorageSelection,
    IsoAdditionalDataDisks,
    DiskQuantitySelection,
    MultiDiskSelection,
    DiskOfferingSelection,
    ComputeOfferingSelection,
    ComputeSelection,
    SecurityGroupSelection,
    TooltipLabel,
    InstanceNicsNetworkSelectListView,
    DetailsInput,
    DeployInstanceBackupSelection
  },
  props: {
    visible: {
      type: Boolean
    },
    preFillContent: {
      type: Object,
      default: () => {}
    }
  },
  mixins: [mixin, mixinDevice],
  data () {
    return {
      deployViewportHeight: '',
      zoneId: '',
      podId: null,
      clusterId: null,
      zoneSelected: false,
      isZoneSelectedMultiArch: false,
      dynamicscalingenabled: true,
      imageType: 'templateid',
      additionalIsoSelection: { enabled: false, ids: [], valid: true },
      rootStorageSelection: { valid: true },
      dataStorageSelection: { valid: true },
      isoDataDiskSelection: { count: 1 },
      isoDataOperations: [],
      selectedCreationSource: null,
      sourceLoading: false,
      sourceConfirmVisible: false,
      sourceConfirmed: false,
      sourceAcknowledged: false,
      sourceOperations: [],
      currentSourceOperation: null,

      imageSearchFilters: null,
      templateKey: 0,
      showRegisteredUserdata: true,
      doUserdataOverride: false,
      doUserdataAppend: false,
      userdataDefaultOverridePolicy: 'ALLOWOVERRIDE',
      vm: {
        name: null,
        zoneid: null,
        zonename: null,
        hypervisor: null,
        templateid: null,
        templatename: null,
        keyboard: null,
        keypairs: [],
        group: null,
        affinitygroupids: [],
        affinitygroup: [],
        serviceofferingid: null,
        serviceofferingname: null,
        ostypeid: null,
        ostypename: null,
        rootdisksize: null,
        disksize: null
      },
      options: {
        guestOsCategories: [],
        volumes: {},
        snapshots: {},
        templates: {},
        isos: {},
        hypervisors: [],
        serviceOfferings: [],
        diskOfferings: [],
        zones: [],
        affinityGroups: [],
        networks: [],
        sshKeyPairs: [],
        UserDatas: [],
        pods: [],
        clusters: [],
        hosts: [],
        groups: [],
        keyboards: [],
        bootTypes: [],
        bootModes: [],
        tpmversion: [],
        ioPolicyTypes: [],
        dynamicScalingVmConfig: false,
        storagePoolObjects: [],
        kmsKeys: []
      },
      rowCount: {},
      loading: {
        deploy: false,
        guestOsCategories: false,
        volumes: false,
        snapshots: false,
        templates: false,
        isos: false,
        hypervisors: false,
        serviceOfferings: false,
        diskOfferings: false,
        affinityGroups: false,
        networks: false,
        sshKeyPairs: false,
        userDatas: false,
        zones: false,
        pods: false,
        clusters: false,
        hosts: false,
        groups: false,
        kmsKeys: false
      },
      owner: {
        projectid: store.getters.project?.id,
        domainid: store.getters.project?.id ? null : store.getters.userInfo.domainid,
        account: store.getters.project?.id ? null : store.getters.userInfo.account
      },
      instanceConfig: {},
      template: {},
      defaultBootType: '',
      defaultBootMode: '',
      defaultTPM: '',
      templateConfigurations: [],
      templateNics: [],
      templateLicenses: [],
      templateProperties: {},
      selectedTemplateConfiguration: {},
      iso: {},
      hypervisor: '',
      serviceOffering: {},
      diskOffering: {},
      mappedRootOffering: null,
      affinityGroups: [],
      networks: [],
      networksAdd: [],
      zone: {},
      sshKeyPairs: [],
      sshKeyPair: {},
      userData: {},
      userDataParams: [],
      userDataParamCols: [
        {
          title: this.$t('label.key'),
          dataIndex: 'key'
        },
        {
          title: this.$t('label.value'),
          dataIndex: 'value',
          key: 'value'
        }
      ],
      userDataValues: {},
      templateUserDataCols: [
        {
          title: this.$t('label.user.data'),
          dataIndex: 'userdata'
        },
        {
          title: this.$t('label.user.data.policy'),
          dataIndex: 'userdataoverridepolicy'
        }
      ],
      templateUserDataParams: [],
      templateUserDataValues: {},
      overrideDiskOffering: {},
      initDataConfig: {},
      defaultnetworkid: '',
      networkConfig: [],
      dataNetworkCreated: [],
      userdataTabKey: 'userdataregistered',
      dataPreFill: {},
      showDetails: false,
      showRootDiskSizeChanger: false,
      showOverrideDiskOfferingOption: false,
      securitygroupids: [],
      rootDiskSizeFixed: 0,
      hasError: false,
      error: false,
      diskSelected: {},
      rootDiskSelected: {},
      diskIOpsMin: 0,
      diskIOpsMax: 0,
      minIops: 0,
      maxIops: 0,
      zones: [],
      selectedZone: '',
      form: {},
      formModel: {},
      nicToNetworkSelection: [],
      selectedArchitecture: null,
      isLeaseFeatureEnabled: this.$store.getters.features.instanceleaseenabled,
      showLeaseOptions: false,
      leaseduration: -1,
      leaseexpiryaction: undefined,
      expiryActions: ['STOP', 'DESTROY'],
      defaultLeaseDuration: 90,
      defaultLeaseExpiryAction: 'STOP',
      naturalNumberRule: {
        type: 'number',
        validator: this.validateNumber
      },
      architectureTypes: {
        opts: []
      },
      externalDetailsEnabled: false,
      selectedExtensionId: null,
      zoneAllowsBackupOperations: false,
      selectedBackupOffering: null,
      backupSchedules: []
    }
  },
  computed: {
    isCreationSource () { return ['volumeid', 'snapshotid'].includes(this.imageType) },
    sourceOperationsKey () { return 'vm-creation-source-' + this.$store.getters.userInfo.id + '-' + (this.$store.getters.project?.id || '') },
    creationSourceQuery () {
      return {
        sourcekind: this.imageType === 'volumeid' ? 'volume' : 'snapshot',
        zoneid: this.form.zoneid,
        account: store.getters.project?.id ? undefined : this.owner.account,
        domainid: store.getters.project?.id ? undefined : this.owner.domainid,
        projectid: store.getters.project?.id || this.owner.projectid,
        arch: this.isZoneSelectedMultiArch ? this.selectedArchitecture : undefined
      }
    },
    sourceOperationPending () { return this.sourceOperations.some(op => op.sourceid === this.selectedCreationSource?.id && ['pending', 'submitting', 'unknown'].includes(op.status)) },
    vmSummary () {
      // ISO data inputs are separate from formModel; always derive their summary from current selections.
      return {
        ...this.vm,
        ...(this.isCreationSource ? {
          ostypeid: this.selectedCreationSource?.bootprofile?.ostypeid,
          ostypename: this.selectedCreationSource?.bootprofile?.osname,
          templateformat: undefined,
          templateid: undefined,
          isoid: undefined
        } : {}),
        disksizetotalgb: this.diskSize || null,
        rootdiskofferingid: this.rootDiskOffering?.id,
        rootdiskofferingdisplaytext: this.rootDiskOffering?.displayText,
        datadiskofferingid: this.dataDiskOffering?.id,
        datadiskofferingdisplaytext: this.dataDiskOffering?.displayText,
        templateformat: this.isCreationSource ? undefined : this.imageType === 'isoid' ? 'ISO' : this.template?.format,
        isoname: this.iso?.name,
        isodisplaytext: this.iso?.displaytext
      }
    },
    templateStrictCustomRoot () {
      return strictTemplateCustomRoot(this.imageType, this.serviceOffering, this.options.diskOfferings, this.template)
    },
    isoRootOfferingStrict () { return this.imageType === 'isoid' && this.serviceOffering?.diskofferingstrictness === true },
    mappedRootOfferingQueryKey () {
      return JSON.stringify({
        strict: this.isoRootOfferingStrict,
        zoneid: this.storageQuery.zoneid,
        serviceofferingid: this.storageQuery.serviceofferingid,
        templateid: this.storageQuery.templateid,
        hypervisor: this.storageQuery.hypervisor
      })
    },
    isoOperationsKey () {
      return 'vm-iso-disks-' + this.$store.getters.userInfo.id + '-' + (this.$store.getters.project.id || '')
    },
    storageSelectionEnabled () {
      return isAdmin() && 'listDeploymentStoragePools' in this.$store.getters.apis && !this.isTemplateHypervisorExternal &&
        ['templateid', 'isoid', 'snapshotid'].includes(this.imageType) && !!this.serviceOffering?.id && !!(this.template?.id || this.iso?.id || this.selectedCreationSource?.id)
    },
    selectedDataDiskOffering () {
      return this.imageType === 'isoid'
        ? this.options.diskOfferings.find(offering => offering.id === this.isoDataDiskSelection.offeringid) || this.isoDataDiskSelection.offering
        : this.diskOffering?.id && this.diskOffering.id !== '0' ? this.diskOffering : null
    },
    selectedDataDiskSize () {
      const offering = this.selectedDataDiskOffering
      return offering?.iscustomized ? Number(this.imageType === 'isoid' ? this.isoDataDiskSelection.size : this.form.size) || 0 : offering?.disksize || 0
    },
    selectedDataDiskCount () {
      const count = this.imageType === 'isoid' ? this.isoDataDiskSelection.count : this.form.datadiskcount
      return count === undefined ? 1 : Number(count)
    },
    diskPlanIncomplete () {
      if (this.isCreationSource) {
        return !this.selectedCreationSource?.allowed || this.sourceLoading || this.sourceOperationPending ||
        (!!this.rootStorageSelection.id && !this.rootStorageSelection.valid)
      }
      if (!['templateid', 'isoid'].includes(this.imageType) || this.template?.deployasis) return false
      if (this.imageType === 'isoid' && (!this.diskOffering?.id || !(this.selectedRootDiskSize > 0))) return true
      if (this.imageType === 'templateid' && this.showOverrideDiskOfferingOption &&
        (!this.overrideDiskOffering?.id || this.overrideDiskOffering.iscustomized && !(this.selectedRootDiskSize > 0))) return true
      if (this.showRootDiskSizeChanger && !(Number(this.form.rootdisksize) > 0)) return true
      if (this.selectedDataDiskOffering?.id && (!(this.selectedDataDiskSize > 0) || !Number.isSafeInteger(this.selectedDataDiskCount) ||
        this.selectedDataDiskCount < 1 || this.isoDataDiskSelection.invalid)) return true
      return this.storageSelectionEnabled && (!!this.rootStorageSelection.id && !this.rootStorageSelection.valid ||
        !!this.selectedDataDiskOffering?.id && !!this.dataStorageSelection.id && !this.dataStorageSelection.valid)
    },
    defaultRootOfferingName () {
      const offering = this.options.diskOfferings.find(item => item.id === this.serviceOffering?.diskofferingid)
      return this.serviceOffering?.diskofferingname || offering?.displaytext || this.$t('label.vm.root.offering.default')
    },
    selectedRootDiskSize () {
      if (this.isCreationSource) return this.selectedCreationSource ? Math.ceil(this.selectedCreationSource.sizebytes / 1024 ** 3) : undefined
      if (this.imageType === 'isoid') return this.diskOffering?.iscustomized ? Number(this.form.size) || undefined : this.diskOffering?.disksize
      if (this.showOverrideDiskOfferingOption && this.overrideDiskOffering?.id) {
        return this.overrideDiskOffering.iscustomized ? Number(this.form.rootdisksize) || undefined : this.overrideDiskOffering.disksize
      }
      if (this.showRootDiskSizeChanger && Number(this.form.rootdisksize) > 0) return Number(this.form.rootdisksize)
      return this.serviceOffering?.rootdisksize || Math.ceil((this.template?.size || 0) / 1024 ** 3) || undefined
    },
    storageQuery () {
      return deploymentStorageQuery({
        form: this.form,
        imageType: this.imageType,
        hypervisor: this.hypervisor,
        template: this.template
      })
    },
    rootStorageQuery () {
      if (this.imageType === 'snapshotid') {
        return {
          zoneid: this.form.zoneid,
          snapshotid: this.selectedCreationSource?.id,
          serviceofferingid: this.form.computeofferingid,
          hypervisor: 'KVM',
          rootdisk: true,
          diskcount: 1,
          vmcount: 1,
          hostid: this.form.hostid,
          clusterid: this.form.clusterid,
          podid: this.form.podid
        }
      }
      return {
        ...this.storageQuery,
        rootdisk: true,
        diskcount: 1,
        size: this.selectedRootDiskSize,
        diskofferingid: this.imageType === 'isoid' ? this.diskOffering?.id : this.showOverrideDiskOfferingOption ? this.overrideDiskOffering?.id : undefined,
        miniops: this.imageType === 'isoid' ? this.diskIOpsMin || undefined : this.minIops || undefined,
        otherstorageid: this.selectedDataDiskOffering?.id ? this.dataStorageSelection.id : undefined,
        otherrequirediops: (this.imageType === 'isoid' ? this.isoDataDiskSelection.diskIOpsMin || this.selectedDataDiskOffering?.miniops || 0 : this.diskIOpsMin || this.selectedDataDiskOffering?.miniops || 0) * this.selectedDataDiskCount * (Number(this.form.vmNumber) || 1),
        otherrequiredbytes: this.selectedDataDiskSize * this.selectedDataDiskCount * (Number(this.form.vmNumber) || 1) * 1024 ** 3
      }
    },
    dataStorageQuery () {
      return {
        ...this.storageQuery,
        rootdisk: false,
        diskcount: this.selectedDataDiskCount,
        diskofferingid: this.selectedDataDiskOffering?.id,
        size: this.selectedDataDiskSize || undefined,
        miniops: this.imageType === 'isoid' ? this.isoDataDiskSelection.diskIOpsMin : this.diskIOpsMin || undefined,
        otherstorageid: this.rootStorageSelection.id,
        otherrequirediops: (this.imageType === 'isoid' ? this.diskIOpsMin || this.diskOffering?.miniops || 0 : this.minIops || this.serviceOffering?.miniops || 0) * (Number(this.form.vmNumber) || 1),
        otherrequiredbytes: (this.selectedRootDiskSize || 0) * (Number(this.form.vmNumber) || 1) * 1024 ** 3
      }
    },
    rootDiskSize () {
      return this.showRootDiskSizeChanger && this.rootDiskSizeFixed > 0
    },
    isNormalAndDomainUser () {
      return ['DomainAdmin', 'User'].includes(this.$store.getters.userInfo.roletype)
    },
    isNormalUserOrProject () {
      return ['User'].includes(this.$store.getters.userInfo.roletype) || store.getters.project.id
    },
    diskSize () {
      if (this.isCreationSource) {
        const root = this.selectedCreationSource?.sizebytes
        const data = this.selectedDataDiskSize
        return [root ? (root / 1024 ** 3).toLocaleString() + ' GiB (Root)' : '', data ? `${data} GiB × ${this.selectedDataDiskCount} (Data)` : ''].filter(Boolean).join(' | ')
      }
      const customRootDiskSize = _.get(this.instanceConfig, 'rootdisksize', null)
      const customDataDiskSize = _.get(this.instanceConfig, 'size', null)
      let computeOfferingDiskSize = _.get(this.serviceOffering, 'rootdisksize', null)
      computeOfferingDiskSize = computeOfferingDiskSize > 0 ? computeOfferingDiskSize : null
      const diskOfferingDiskSize = _.get(this.diskOffering, 'disksize', null)
      const overrideDiskOfferingDiskSize = _.get(this.overrideDiskOffering, 'disksize', null)

      let rootDiskSize
      let dataDiskSize
      if (this.vm.isoid != null) {
        rootDiskSize = this.diskOffering?.iscustomized ? customDataDiskSize : diskOfferingDiskSize
        dataDiskSize = this.selectedDataDiskSize
      } else {
        rootDiskSize = this.overrideDiskOffering?.iscustomized ? customRootDiskSize : overrideDiskOfferingDiskSize || computeOfferingDiskSize || this.dataPreFill.minrootdisksize
        dataDiskSize = this.diskOffering?.iscustomized ? customDataDiskSize : diskOfferingDiskSize
      }

      const size = []
      if (rootDiskSize) {
        size.push(`${rootDiskSize} GB (Root)`)
      }
      if (dataDiskSize) {
        size.push(`${dataDiskSize} GB × ${this.selectedDataDiskCount} (Data)`)
      }
      return size.join(' | ')
    },
    rootDiskOffering () {
      const rootDiskOffering = this.vm.isoid != null ? this.diskOffering : this.overrideDiskOffering

      const id = _.get(rootDiskOffering, 'id', null)
      const displayText = _.get(rootDiskOffering, 'displaytext', null)

      return {
        id: id,
        displayText: `${displayText} (Root)`
      }
    },
    dataDiskOffering () {
      if (this.vm.isoid != null) {
        const offering = this.selectedDataDiskOffering
        return offering ? { id: offering.id, displayText: offering.displaytext + ' (Data)' } : null
      }

      const id = _.get(this.diskOffering, 'id', null)
      const displayText = _.get(this.diskOffering, 'displaytext', null)

      return {
        id: id,
        displayText: `${displayText} (Data)`
      }
    },
    affinityGroupIds () {
      return _.map(this.affinityGroups, 'id')
    },
    params () {
      const listAll = isAdmin()
      return {
        serviceOfferings: {
          list: 'listServiceOfferings',
          options: {
            zoneid: _.get(this.zone, 'id'),
            projectid: this.owner.projectid,
            domainid: this.owner.domainid,
            account: this.owner.account,
            issystem: false,
            page: 1,
            pageSize: 10,
            keyword: undefined
          }
        },
        diskOfferings: {
          list: 'listDiskOfferings',
          options: {
            zoneid: _.get(this.zone, 'id'),
            projectid: this.owner.projectid,
            domainid: this.owner.domainid,
            account: this.owner.account,
            page: 1,
            pageSize: 10,
            keyword: undefined
          }
        },
        zones: {
          list: 'listZones',
          isLoad: true,
          field: 'zoneid'
        },
        hypervisors: {
          list: 'listHypervisors',
          options: {
            zoneid: _.get(this.zone, 'id')
          },
          field: 'hypervisor'
        },
        affinityGroups: {
          list: 'listAffinityGroups',
          options: {
            page: 1,
            pageSize: 10,
            account: this.owner.account,
            domainid: this.owner.domainid,
            projectid: this.owner.projectid,
            keyword: undefined,
            listall: listAll
          }
        },
        sshKeyPairs: {
          list: 'listSSHKeyPairs',
          options: {
            page: 1,
            pageSize: 10,
            account: this.owner.account,
            domainid: this.owner.domainid,
            projectid: this.owner.projectid,
            keyword: undefined,
            listall: listAll
          }
        },
        userDatas: {
          list: 'listUserData',
          options: {
            page: 1,
            pageSize: 10,
            account: this.owner.account,
            domainid: this.owner.domainid,
            projectid: this.owner.projectid,
            keyword: undefined,
            listall: listAll
          }
        },
        networks: {
          list: 'listNetworks',
          options: {
            zoneid: _.get(this.zone, 'id'),
            canusefordeploy: true,
            projectid: store.getters.project.id || this.owner.projectid,
            domainid: store.getters.project.id ? null : this.owner.domainid,
            account: store.getters.project.id ? null : this.owner.account,
            page: 1,
            pageSize: 10,
            keyword: undefined,
            showIcon: true
          }
        },
        pods: {
          list: 'listPods',
          isLoad: !this.isNormalAndDomainUser,
          options: {
            zoneid: _.get(this.zone, 'id')
          },
          field: 'podid'
        },
        clusters: {
          list: 'listClusters',
          isLoad: !this.isNormalAndDomainUser,
          options: {
            zoneid: _.get(this.zone, 'id'),
            podid: this.podId
          },
          field: 'clusterid'
        },
        hosts: {
          list: 'listHosts',
          isLoad: !this.isNormalAndDomainUser,
          options: {
            zoneid: _.get(this.zone, 'id'),
            podid: this.podId,
            clusterid: this.clusterId,
            state: 'Up',
            type: 'Routing'
          },
          field: 'hostid'
        },
        dynamicScalingVmConfig: {
          list: 'listConfigurations',
          options: {
            zoneid: _.get(this.zone, 'id'),
            name: 'enable.dynamic.scale.vm'
          }
        },
        guestOsCategories: {
          list: 'listOsCategories',
          options: {
            zoneid: _.get(this.zone, 'id'),
            isfeatured: true,
            isiso: _.get(this, 'imageType') === 'isoid',
            arch: this.selectedArchitecture,
            isvnf: false,
            showicon: true
          },
          field: 'guestoscategoryid'
        }
      }
    },
    networkOfferingIds () {
      return _.map(this.networks, 'id')
    },
    zoneSelectOptions () {
      return this.options.zones.map((zone) => {
        return {
          label: zone.name,
          value: zone.id
        }
      })
    },
    hypervisorSelectOptions () {
      return this.options.hypervisors.map((hypervisor) => {
        return {
          label: hypervisor.name,
          value: hypervisor.name
        }
      })
    },
    podSelectOptions () {
      const options = this.options.pods.map((pod) => {
        return {
          label: pod.name,
          value: pod.id
        }
      })
      options.unshift({
        label: this.$t('label.default'),
        value: null
      })
      return options
    },
    clusterSelectOptions () {
      const options = this.options.clusters.map((cluster) => {
        return {
          label: cluster.name,
          value: cluster.id
        }
      })
      options.unshift({
        label: this.$t('label.default'),
        value: null
      })
      return options
    },
    hostSelectOptions () {
      const options = this.options.hosts.map((host) => {
        return {
          label: host.name,
          value: host.id
        }
      })
      options.unshift({
        label: this.$t('label.default'),
        value: null
      })
      return options
    },
    keyboardSelectOptions () {
      const keyboardOpts = this.$config.keyboardOptions || {}
      return Object.keys(keyboardOpts).map((keyboard) => {
        return {
          label: this.$t(keyboardOpts[keyboard]),
          value: keyboard
        }
      })
    },
    templateConfigurationExists () {
      return this.vm.templateid && this.templateConfigurations && this.templateConfigurations.length > 0
    },
    queryZoneId () {
      return this.$route.query.zoneid || null
    },
    queryArchId () {
      return this.$route.query.arch || null
    },
    querySnapshotId () {
      return this.$route.query.snapshotid || null
    },
    queryVolumeId () {
      return this.$route.query.volumeid || null
    },
    queryTemplateId () {
      return this.$route.query.templateid || null
    },
    queryIsoId () {
      return this.$route.query.isoid || null
    },
    queryNetworkId () {
      return this.$route.query.networkid || null
    },
    queryGuestOsCategoryId () {
      return this.$route.query.oscategoryid || null
    },
    imageTypeList () {
      if (this.queryTemplateId) {
        return [{
          key: 'templateid',
          tab: this.$t('label.templates')
        }]
      } else if (this.queryIsoId) {
        return [{
          key: 'isoid',
          tab: this.$t('label.isos')
        }]
      }
      return [{
        key: 'templateid',
        tab: this.$t('label.templates')
      },
      {
        key: 'isoid',
        tab: this.$t('label.isos')
      }]
    },
    userdataTabList () {
      let tabList = []
      tabList = [{
        key: 'userdataregistered',
        tab: this.$t('label.user.data.registered')
      },
      {
        key: 'userdatatext',
        tab: this.$t('label.user.data.text')
      }]

      return tabList
    },
    showSecurityGroupSection () {
      if (this.zone && this.zone.networktype === 'Basic') {
        return true
      }
      if (this.networks.length < 1) {
        return false
      }
      for (const network of this.options.networks) {
        if (this.form.networkids && this.form.networkids.includes(network.id)) {
          for (const service of network.service) {
            if (service.name === 'SecurityGroup') {
              return true
            }
          }
        }
      }
      return false
    },
    isUserAllowedToListSshKeys () {
      return Boolean('listSSHKeyPairs' in this.$store.getters.apis)
    },
    isUserAllowedToListUserDatas () {
      return Boolean('listUserData' in this.$store.getters.apis)
    },
    dynamicScalingVmConfigValue () {
      return this.$store.getters.features.dynamicscalingenabled
    },
    extraConfigEnabled () {
      return this.$store.getters.features.additionalconfigenabled
    },
    isCustomizedDiskIOPS () {
      return this.diskSelected?.iscustomizediops || false
    },
    isCustomizedIOPS () {
      return this.rootDiskSelected?.iscustomizediops || this.serviceOffering?.iscustomizediops || false
    },
    deployMenuOptions () {
      return [this.form.startvm ? this.$t('label.launch.vm.and.stay') : this.$t('label.create.vm.and.stay')]
    },
    isModernImageSelection () {
      return this.$config.imageSelectionInterface === undefined || this.$config.imageSelectionInterface === 'modern'
    },
    imageSelection () {
      return this.isModernImageSelection ? 'modern' : 'legacy'
    },
    showUserCategoryForModernImageSelection () {
      return this.$config.showUserCategoryForModernImageSelection === undefined || this.$config.showUserCategoryForModernImageSelection
    },
    showAllCategoryForModernImageSelection () {
      return this.$config.showAllCategoryForModernImageSelection
    },
    guestOsCategoriesSelectionDisallowed () {
      if (this.imageType === 'volumeid' || this.imageType === 'snapshotid') {
        return true
      }
      return (!this.queryGuestOsCategoryId || this.options.guestOsCategories.length === 0) && (!!this.queryTemplateId || !!this.queryIsoId)
    },
    isTemplateHypervisorExternal () {
      return !!this.template && this.template.hypervisor === 'External'
    }
  },
  watch: {
    templateStrictCustomRoot (required) {
      if (!required) return
      this.showRootDiskSizeChanger = true
      this.form.rootdisksizeitem = true
      if (!(Number(this.form.rootdisksize) > 0)) this.form.rootdisksize = this.dataPreFill.minrootdisksize
    },
    mappedRootOfferingQueryKey: {
      async handler (queryKey) {
        this.mappedRootOffering = null
        if (!this.isoRootOfferingStrict || !this.serviceOffering.diskofferingid) return
        if (!isAdmin()) {
          const offering = this.serviceOffering
          this.mappedRootOffering = {
            id: offering.diskofferingid,
            name: offering.diskofferingname,
            displaytext: offering.diskofferingname,
            disksize: offering.rootdisksize,
            iscustomized: !offering.rootdisksize,
            iscustomizediops: offering.iscustomizediops,
            miniops: offering.miniops,
            maxiops: offering.maxiops,
            encrypt: offering.encryptroot
          }
          if (this.isoRootOfferingStrict) { this.form.diskofferingid = offering.diskofferingid; this.diskOffering = this.mappedRootOffering; this.diskSelected = this.mappedRootOffering }
          return
        }
        const query = this.storageQuery
        if (!query.zoneid || !query.serviceofferingid || !query.templateid || !query.hypervisor) return
        try {
          const result = await getAPI('listDeploymentStoragePools', { ...query, rootdisk: true })
          if (queryKey !== this.mappedRootOfferingQueryKey) return
          this.mappedRootOffering = result.listdeploymentstoragepoolsresponse.deploymentstoragepool?.[0]?.diskoffering
          if (this.isoRootOfferingStrict && this.mappedRootOffering) {
            this.form.diskofferingid = this.mappedRootOffering.id
            this.diskOffering = this.mappedRootOffering
            this.diskSelected = this.mappedRootOffering
            this.form.size = this.mappedRootOffering.iscustomized ? undefined : this.mappedRootOffering.disksize
          }
        } catch (error) { if (queryKey === this.mappedRootOfferingQueryKey) this.$notifyError(error) }
      }
    },
    isoDataOperations: {
      deep: true,
      handler (records) { sessionStorage.setItem(this.isoOperationsKey, JSON.stringify(records.filter(record => record.status !== 'complete'))) }
    },
    'form.vmNumber' () {
      if (this.form.name && this.formRef.value) {
        this.formRef.value.validateFields(['name']).catch(() => {})
      }
    },
    '$route' (to, from) {
      if (to.name === 'deployVirtualMachine') {
        this.resetData()
      }
    },
    formModel: {
      deep: true,
      handler (instanceConfig) {
        this.instanceConfig = toRaw(instanceConfig)
        Object.keys(instanceConfig).forEach(field => {
          this.vm[field] = this.instanceConfig[field]
        })
        this.template = null
        for (const key in this.options.templates) {
          var template = _.find(_.get(this.options.templates[key], 'template', []), (option) => option.id === instanceConfig.templateid)
          if (template) {
            this.template = template
            break
          }
        }

        this.iso = null
        for (const key in this.options.isos) {
          var iso = _.find(_.get(this.options.isos[key], 'iso', []), (option) => option.id === instanceConfig.isoid)
          if (iso) {
            this.iso = iso
            break
          }
        }

        if (instanceConfig.hypervisor) {
          var hypervisorItem = _.find(this.options.hypervisors, (option) => option.name === instanceConfig.hypervisor)
          this.hypervisor = hypervisorItem ? hypervisorItem.name : null
        }

        this.serviceOffering = _.find(this.options.serviceOfferings, (option) => option.id === instanceConfig.computeofferingid)
        if (this.imageType === 'templateid' &&
            (this.serviceOffering?.diskofferingstrictness || this.template?.deployasis) &&
            (this.showOverrideDiskOfferingOption || (this.showRootDiskSizeChanger && !this.templateStrictCustomRoot))) {
          this.updateOverrideRootDiskShowParam(false)
        }

        instanceConfig.overridediskofferingid = this.rootDiskSelected?.id || this.serviceOffering?.diskofferingid
        if (instanceConfig.overridediskofferingid) {
          this.overrideDiskOffering = _.find(this.options.diskOfferings, (option) => option.id === instanceConfig.overridediskofferingid)
        } else {
          this.overrideDiskOffering = null
        }

        if (this.iso && this.serviceOffering?.diskofferingid) {
          this.diskOffering = _.find(this.options.diskOfferings, (option) => option.id === this.serviceOffering.diskofferingid)
        } else if (!iso && this.diskSelected) {
          this.diskOffering = this.isoRootOfferingStrict && this.mappedRootOffering ? this.mappedRootOffering : _.find(this.options.diskOfferings, (option) => option.id === instanceConfig.diskofferingid)
        }

        this.zone = _.find(this.options.zones, (option) => option.id === this.instanceConfig.zoneid)
        this.affinityGroups = _.filter(this.options.affinityGroups, (option) => _.includes(instanceConfig.affinitygroupids, option.id))
        // this.networks = this.getSelectedNetworksWithExistingConfig(_.filter(this.options.networks, (option) => _.includes(instanceConfig.networkids, option.id)))
        this.networks = _.filter(this.options.networks, (option) => _.includes(instanceConfig.networkids, option.id))

        this.diskOffering = this.isoRootOfferingStrict && this.mappedRootOffering ? this.mappedRootOffering : _.find(this.options.diskOfferings, (option) => option.id === instanceConfig.diskofferingid)
        this.sshKeyPair = _.find(this.options.sshKeyPairs, (option) => option.name === instanceConfig.keypair)

        if (this.zone) {
          this.vm.zoneid = this.zone.id
          this.vm.zonename = this.zone.name
        }

        const pod = _.find(this.options.pods, (option) => option.id === instanceConfig.podid)
        if (pod) {
          this.vm.podid = pod.id
          this.vm.podname = pod.name
        }

        const cluster = _.find(this.options.clusters, (option) => option.id === instanceConfig.clusterid)
        if (cluster) {
          this.vm.clusterid = cluster.id
          this.vm.clustername = cluster.name
        }

        const host = _.find(this.options.hosts, (option) => option.id === instanceConfig.hostid)
        if (host) {
          this.vm.hostid = host.id
          this.vm.hostname = host.name
        }

        if (this.diskSize) {
          this.vm.disksizetotalgb = this.diskSize
        } else {
          this.vm.disksizetotalgb = null
        }

        if (this.networks) {
          this.vm.networks = this.networks
          this.vm.defaultnetworkid = this.defaultnetworkid
        }

        if (this.template) {
          this.vm.templateid = this.template.id
          this.vm.templatename = this.template.displaytext
          this.vm.ostypeid = this.template.ostypeid
          this.vm.ostypename = this.template.ostypename
        }

        if (this.iso) {
          this.vm.isoid = this.iso.id
          this.vm.templateid = this.iso.id
          this.vm.templatename = this.iso.displaytext
          this.vm.ostypeid = this.iso.ostypeid
          this.vm.ostypename = this.iso.ostypename
          if (this.hypervisor) {
            this.vm.hypervisor = this.hypervisor
          }
        }

        if (this.serviceOffering) {
          this.vm.serviceofferingid = this.serviceOffering.id
          this.vm.serviceofferingname = this.serviceOffering.displaytext
          if (this.serviceOffering.cpunumber) {
            this.vm.cpunumber = this.serviceOffering.cpunumber
          }
          if (this.serviceOffering.cpuspeed) {
            this.vm.cpuspeed = this.serviceOffering.cpuspeed
          }
          if (this.serviceOffering.memory) {
            this.vm.memory = this.serviceOffering.memory
          }
          this.vm.gpucardid = this.serviceOffering.gpucardid ? this.serviceOffering.gpucardid : ''
          this.vm.gpucardname = this.serviceOffering.gpucardname ? this.serviceOffering.gpucardname : ''
          this.vm.gpucount = this.serviceOffering.gpucount ? this.serviceOffering.gpucount : 0
          this.vm.vgpuprofileid = this.serviceOffering.vgpuprofileid ? this.serviceOffering.vgpuprofileid : ''
          this.vm.vgpuprofilename = this.serviceOffering.vgpuprofilename ? this.serviceOffering.vgpuprofilename : ''
        }

        if (this.template && !this.template.deployasis && this.template.childtemplates && this.template.childtemplates.length > 0) {
          this.vm.diskofferingid = ''
          this.vm.diskofferingname = ''
          this.vm.diskofferingsize = ''
        } else if (this.diskOffering) {
          this.vm.diskofferingid = this.diskOffering.id
          this.vm.diskofferingname = this.diskOffering.displaytext
          this.vm.diskofferingsize = this.diskOffering.disksize
        }

        this.vm.rootdiskofferingid = this.rootDiskOffering?.id
        this.vm.rootdiskofferingdisplaytext = this.rootDiskOffering?.displayText
        this.vm.datadiskofferingid = this.dataDiskOffering?.id
        this.vm.datadiskofferingdisplaytext = this.dataDiskOffering?.displayText

        if (this.affinityGroups) {
          this.vm.affinitygroup = this.affinityGroups
        }

        if (this.sshKeyPairs && this.sshKeyPairs.length > 0) {
          this.vm.keypairs = this.sshKeyPairs
        }

        if (this.leaseduration < 1) {
          this.vm.leaseduration = undefined
        }

        delete this.vm.backupofferingid
        delete this.vm.backupofferingname
        if (this.form.backupofferingid && this.selectedBackupOffering) {
          this.vm.backupofferingid = this.selectedBackupOffering.id
          this.vm.backupofferingname = this.selectedBackupOffering.name
        }
      }
    }
  },
  serviceOffering (oldValue, newValue) {
    if (oldValue && newValue && oldValue.id !== newValue.id) {
      this.dynamicscalingenabled = this.isDynamicallyScalable()
      // Fetch KMS keys if encryption is enabled
      if (newValue && newValue.encryptroot && this.zoneId) {
        this.fetchKmsKeys()
      }
    }
  },
  diskOffering (newValue) {
    // Fetch KMS keys if encryption is enabled
    if (newValue && newValue.encrypt && this.zoneId) {
      this.fetchKmsKeys()
    }
  },
  overrideDiskOffering (newValue) {
    // Fetch KMS keys if encryption is enabled
    if (newValue && newValue.encrypt && this.zoneId) {
      this.fetchKmsKeys()
    }
  },
  template (oldValue, newValue) {
    if (oldValue && newValue && oldValue.id !== newValue.id) {
      this.dynamicscalingenabled = this.isDynamicallyScalable()
      this.doUserdataOverride = false
      this.doUserdataAppend = false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('deployVirtualMachine')
  },
  created () {
    try {
      this.isoDataOperations = JSON.parse(sessionStorage.getItem(this.isoOperationsKey) || '[]')
      this.isoDataOperations.forEach(record => { if (record.status === 'running') record.status = 'failed' })
    } catch (error) { sessionStorage.removeItem(this.isoOperationsKey) }
    this.initForm()
    this.dataPreFill = this.preFillContent && Object.keys(this.preFillContent).length > 0 ? this.preFillContent : {}
    this.fetchData()
  },
  provide () {
    return {
      vmFetchTemplates: this.fetchAllTemplates,
      vmFetchIsos: this.fetchAllIsos,
      vmFetchNetworks: this.fetchNetwork
    }
  },
  mounted () {
    this.deployResizeObserver = new ResizeObserver(this.updateDeployViewportHeight)
    const layout = this.$refs.deployLayout
    const content = layout.closest('.layout-content')
    if (content) {
      this.deployResizeObserver.observe(content)
      this.deployResizeObserver.observe(layout)
    }
    this.updateDeployViewportHeight()
  },
  beforeUnmount () {
    if (this.deployResizeObserver) this.deployResizeObserver.disconnect()
  },
  methods: {
    updateDeployViewportHeight () {
      const layout = this.$refs.deployLayout
      const content = layout?.closest('.layout-content')
      if (!content || !layout.getClientRects().length) return
      // Measure the actual workspace, including header/banner and page padding.
      // Adding scrollTop keeps this offset stable when an outer scroll is restored.
      const top = layout.getBoundingClientRect().top - content.getBoundingClientRect().top + content.scrollTop - content.clientTop
      const bottom = parseFloat(getComputedStyle(content).paddingBottom) || 0
      const height = Math.max(0, Math.floor(content.clientHeight - top - bottom))
      this.deployViewportHeight = height ? `${height}px` : ''
    },
    updateTemplateKey () {
      this.templateKey += 1
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.zoneid = null
      this.rules = reactive({
        zoneid: [{ required: true, message: `${this.$t('message.error.select')}` }],
        hypervisor: [{ required: true, message: `${this.$t('message.error.select')}` }],
        name: [{ validator: this.validateVmName }]
      })

      this.form.startvm = false
      this.form.datadiskcount = 1
      this.rootStorageSelection = { valid: true }
      this.dataStorageSelection = { valid: true }
      this.isoDataDiskSelection = { count: 1 }
      this.form.vmNumber = 1
      this.form.machinecompatibility = 'standard'

      if (this.zone && this.zone.networktype !== 'Basic') {
        if (this.zoneSelected && this.vm.templateid && this.templateNics && this.templateNics.length > 0) {
          this.templateNics.forEach((nic, nicIndex) => {
            this.form['networkMap.nic-' + nic.InstanceID.toString()] = this.options.networks && this.options.networks.length > 0
              ? this.options.networks[Math.min(nicIndex, this.options.networks.length - 1)].id
              : null
          })
        }

        this.updateFormProperties()

        if (this.vm.templateid && this.templateLicenses && this.templateLicenses.length > 0) {
          this.rules.licensesaccepted = [{
            validator: async (rule, value) => {
              if (!value) {
                return Promise.reject(this.$t('message.license.agreements.not.accepted'))
              }
              return Promise.resolve()
            }
          }]
        }
      }
    },
    async validateVmName (rule, value) {
      if (!value) return
      if (!/^[a-zA-Z]/.test(value) || /[^a-zA-Z0-9-]/.test(value) || value.endsWith('-')) {
        throw new Error(this.$t('message.vm.name.invalid'))
      }
      const count = Number(this.form.vmNumber)
      const suffix = Number.isInteger(count) && count > 1 ? `-${count}` : ''
      if (value.length + suffix.length > 63) {
        throw new Error(this.$t('message.vm.name.length', { max: 63 - suffix.length }))
      }
    },
    getImageFilters (params, forReset) {
      if (this.isModernImageSelection) {
        if (this.form.guestoscategoryid === '0') {
          return ['self']
        }
        if (this.isModernImageSelection && params && !forReset) {
          if (params.featured) {
            return ['featured']
          } else if (params.public) {
            return ['community']
          }
        }
        return this.isNormalAndDomainUser ? ['executable'] : ['all']
      }
      return [
        'featured',
        'community',
        'selfexecutable',
        'sharedexecutable'
      ]
    },
    getPropertyQualifiers (qualifiers, type) {
      var result = ''
      switch (type) {
        case 'select':
          result = []
          if (qualifiers && qualifiers.includes('ValueMap')) {
            result = qualifiers.replace('ValueMap', '').substr(1).slice(0, -1).split(',')
            for (var i = 0; i < result.length; i++) {
              result[i] = result[i].replace(/"/g, '')
            }
          }
          break
        case 'number-select':
          var min = 0
          var max = Number.MAX_SAFE_INTEGER
          if (qualifiers) {
            var match = qualifiers.match(/MinLen\((\d+)\)/)
            if (match) {
              min = parseInt(match[1])
            }
            match = qualifiers.match(/MaxLen\((\d+)\)/)
            if (match) {
              max = parseInt(match[1])
            }
          }
          result = { min: min, max: max }
          break
        default:
      }
      return result
    },
    fillValue (field) {
      this.form[field] = this.dataPreFill[field]
    },
    fetchZoneByQuery () {
      return new Promise(resolve => {
        let zones = []
        let apiName = ''
        const params = {}
        if (this.queryZoneId) {
          zones.push(this.queryZoneId)
          if (this.queryTemplateId) {
            this.dataPreFill.templateid = this.queryTemplateId
          } else if (this.queryIsoId) {
            this.dataPreFill.isoid = this.queryIsoId
          }
          return resolve(zones)
        } else if (this.queryTemplateId) {
          apiName = 'listTemplates'
          params.listall = true
          params.templatefilter = this.isNormalAndDomainUser ? 'executable' : 'all'
          params.isready = true
          params.id = this.queryTemplateId
          this.dataPreFill.templateid = this.queryTemplateId
        } else if (this.queryIsoId) {
          apiName = 'listIsos'
          params.listall = true
          params.isofilter = this.isNormalAndDomainUser ? 'executable' : 'all'
          params.isready = true
          params.id = this.queryIsoId
          this.dataPreFill.isoid = this.queryIsoId
        } else if (this.queryNetworkId) {
          apiName = 'listNetworks'
          params.listall = true
          params.id = this.queryNetworkId
        }
        if (!apiName) return resolve(zones)

        getAPI(apiName, params).then(json => {
          let objectName
          const responseName = [apiName.toLowerCase(), 'response'].join('')
          for (const key in json[responseName]) {
            if (key === 'count') {
              continue
            }
            objectName = key
            break
          }
          const data = json?.[responseName]?.[objectName] || []
          zones = data.map(item => item.zoneid)
          return resolve(zones)
        }).catch(() => {
          return resolve(zones)
        })
      })
    },
    async fetchData () {
      this.architectureTypes.opts = this.$fetchCpuArchitectureTypes()
      if (this.queryArchId) {
        this.architectureTypes.opts = this.architectureTypes.opts.filter(o => o.id === this.queryArchId)
      }
      const zones = await this.fetchZoneByQuery()
      if (zones && zones.length === 1) {
        this.selectedZone = zones[0]
        this.dataPreFill.zoneid = zones[0]
      }
      if (this.dataPreFill.zoneid) {
        this.fetchDataByZone(this.dataPreFill.zoneid)
      } else {
        this.fetchZones(null, zones)
        _.each(this.params, (param, name) => {
          if (param.isLoad) {
            this.fetchOptions(param, name)
          }
        })
      }
      this.fetchBootTypes()
      this.fetchBootModes()
      this.fetchTpm()
      this.fetchInstaceGroups()
      this.fetchIoPolicyTypes()
      nextTick().then(() => {
        ['name', 'keyboard', 'boottype', 'bootmode', 'userdata', 'tpmversion', 'iothreadsenabled', 'iodriverpolicy', 'nicmultiqueuenumber', 'nicpackedvirtqueues'].forEach(this.fillValue)
        this.form.boottype = this.defaultBootType ? this.defaultBootType : this.options.bootTypes && this.options.bootTypes.length > 0 ? this.options.bootTypes[0].id : undefined
        this.form.bootmode = this.defaultBootMode ? this.defaultBootMode : this.options.bootModes && this.options.bootModes.length > 0 ? this.options.bootModes[0].id : undefined
        this.form.tpmmodel = 'tpm-tis'
        this.form.tpmversion = this.defaultTPM ? this.defaultTPM : this.options.tpmversion && this.options.tpmversion.length > 0 ? this.options.tpmversion[0].id : undefined
        this.form.machinecompatibility = this.form.machinecompatibility || 'standard'
        this.instanceConfig = toRaw(this.form)
      })
    },
    isDynamicallyScalable () {
      return this.serviceOffering && this.serviceOffering.dynamicscalingenabled && this.template && this.template.isdynamicallyscalable && this.dynamicScalingVmConfigValue
    },
    isOfferingConstrained (serviceOffering) {
      return 'serviceofferingdetails' in serviceOffering && 'mincpunumber' in serviceOffering.serviceofferingdetails &&
        'maxmemory' in serviceOffering.serviceofferingdetails && 'maxcpunumber' in serviceOffering.serviceofferingdetails &&
        'minmemory' in serviceOffering.serviceofferingdetails
    },
    updateOverrideRootDiskShowParam (val) {
      this.form.rootdisksizeitem = false
      if (val) {
        this.showRootDiskSizeChanger = false
      } else {
        this.rootDiskSelected = null
        this.form.overridediskofferingid = undefined
        this.changeRootDiskSizeOverride(false)
      }
      this.showOverrideDiskOfferingOption = val
    },
    async fetchDataByZone (zoneId) {
      this.fillValue('zoneid')
      this.options.zones = await this.fetchZones(zoneId)
      this.onSelectZoneId(zoneId)
    },
    fetchBootTypes () {
      this.options.bootTypes = [
        { id: 'BIOS', description: 'BIOS' },
        { id: 'UEFI', description: 'UEFI' }
      ]
    },
    fetchBootModes (bootType) {
      const bootModes = [
        { id: 'LEGACY', description: 'LEGACY' }
      ]
      if (bootType === 'UEFI') {
        bootModes.unshift(
          { id: 'SECURE', description: 'SECURE' }
        )
      }
      this.options.bootModes = bootModes
    },
    fetchTpm () {
      this.options.tpmversion = [
        { id: 'INHERIT', description: this.$t('label.tpm.inherit') },
        { id: 'NONE', description: this.$t('label.disabled') },
        { id: 'V1_2', description: 'TPM Version 1.2' },
        { id: 'V2_0', description: 'TPM Version 2.0' }
      ]
    },
    fetchIoPolicyTypes () {
      this.options.ioPolicyTypes = [
        { id: 'native', description: 'native' },
        { id: 'threads', description: 'threads' },
        { id: 'io_uring', description: 'io_uring' },
        { id: 'storage_specific', description: 'storage_specific' }
      ]
    },
    fetchInstaceGroups () {
      this.options.instanceGroups = []
      getAPI('listInstanceGroups', {
        account: this.$store.getters.project?.id ? null : this.$store.getters.userInfo.account,
        domainid: this.$store.getters.project?.id ? null : this.$store.getters.userInfo.domainid,
        listall: true
      }).then(response => {
        const groups = response.listinstancegroupsresponse.instancegroup || []
        groups.forEach(x => {
          this.options.instanceGroups.push({ label: x.name, value: x.name })
        })
      })
    },
    fetchNetwork () {
      const param = this.params.networks
      this.fetchOptions(param, 'networks')
    },
    fetchKmsKeys () {
      if (!this.zoneId) {
        return
      }
      this.loading.kmsKeys = true
      this.options.kmsKeys = []
      getAPI('listKMSKeys', {
        zoneid: this.zoneId,
        account: this.owner.account,
        domainid: this.owner.domainid,
        projectid: this.owner.projectid,
        purpose: 'volume'
      }).then(response => {
        const kmskeyMap = response.listkmskeysresponse.kmskey || []
        if (kmskeyMap.length > 0) {
          this.options.kmsKeys = kmskeyMap
        } else {
          this.options.kmsKeys = null
        }
      }).catch(() => {
        this.options.kmsKeys = null
      }).finally(() => {
        this.loading.kmsKeys = false
      })
    },
    resetData () {
      this.vm = {
        name: null,
        zoneid: null,
        zonename: null,
        hypervisor: null,
        templateid: null,
        templatename: null,
        keyboard: null,
        keypair: null,
        group: null,
        affinitygroupids: [],
        affinitygroup: [],
        serviceofferingid: null,
        serviceofferingname: null,
        ostypeid: null,
        ostypename: null,
        rootdisksize: null,
        disksize: null
      }
      this.zoneSelected = false
      this.formRef.value.resetFields()
      this.fetchData()
    },
    updateRootKmsKey (value) {
      this.form.rootkmskeyid = value
    },
    updateDataKmsKey (value) {
      this.form.datakmskeyid = value
    },
    updateFieldValue (name, value) {
      if (name === 'templateid') {
        this.imageType = 'templateid'
        this.form.templateid = value
        this.form.isoid = null
        this.form.volumeid = null
        this.form.snapshotid = null
        this.resetFromTemplateConfiguration()
        let template = ''
        for (const entry of Object.values(this.options.templates)) {
          template = entry?.template.find(option => option.id === value) || null
          if (template) {
            this.template = template
            break
          }
        }
        this.resetFromTemplateConfiguration()
        if (template) {
          this.resetTemplateAssociatedResources()
          this.updateTemplateParameters()
          var size = template.size / (1024 * 1024 * 1024) || 0 // bytes to GB
          this.dataPreFill.minrootdisksize = Math.ceil(size)
          this.dataPreFill.kvdoenable = template.kvdoenable
          this.updateTemplateLinkedUserData(template.userdataid)
          this.userdataDefaultOverridePolicy = template.userdatapolicy
          this.form.dynamicscalingenabled = template.isdynamicallyscalable
          this.defaultBootType = template.details?.UEFI ? 'UEFI' : 'BIOS'
          this.form.boottype = this.defaultBootType
          this.fetchBootModes(this.form.boottype)
          this.defaultBootMode = template.details?.UEFI || this.options.bootModes?.[0]?.id || undefined
          this.form.bootmode = this.defaultBootMode
          this.form.iothreadsenabled = template.details && Object.prototype.hasOwnProperty.call(template.details, 'iothreads')
          this.form.iodriverpolicy = template.details?.['io.policy']
          this.form.keyboard = template.details?.keyboard
          this.form.templateKvdoEnable = template.kvdoenable
          this.showOverrideDiskOfferingOption = false
          this.updateOverrideRootDiskShowParam(false)
          if (template.details['vmware-to-kvm-mac-addresses']) {
            this.dataPreFill.macAddressArray = JSON.parse(template.details['vmware-to-kvm-mac-addresses'])
          }
          this.dataPreFill.hypervisorType = template.hypervisor
        }
      } else if (name === 'isoid') {
        this.imageType = 'isoid'
        this.resetTemplateAssociatedResources()
        this.resetFromTemplateConfiguration()
        this.form.isoid = value
        this.form.templateid = null
        this.form.volumeid = null
        this.form.snapshotid = null
        let iso = null
        this.defaultBootType = 'UEFI'
        this.form.boottype = this.defaultBootType
        this.fetchBootModes(this.form.boottype)
        this.defaultBootMode = this.options.bootModes?.[1]?.id || undefined
        this.form.bootmode = this.defaultBootMode
        for (const entry of Object.values(this.options.isos)) {
          iso = entry?.iso.find(option => option.id === value)
          if (iso) {
            this.iso = iso
            break
          }
        }
        if (iso) {
          this.updateTemplateLinkedUserData(this.iso.userdataid)
          this.userdataDefaultOverridePolicy = this.iso.userdatapolicy
        }
      } else if (name === 'volumeid') {
        this.updateFieldValueForVolume(value)
      } else if (name === 'snapshotid') {
        this.updateFieldValueForSnapshot(value)
      } else if (['cpuspeed', 'cpunumber', 'memory'].includes(name)) {
        this.vm[name] = value
        this.form[name] = value
      } else {
        this.form[name] = value
      }
    },
    updateFieldValueForVolume (value) {
      this.imageType = 'volumeid'
      this.resetTemplateAssociatedResources()
      this.resetFromTemplateConfiguration()
      this.form.templateid = null
      this.form.isoid = null
      this.form.volumeid = value
      this.form.snapshotid = null
      let volume = null
      for (const entry of Object.values(this.options.volumes)) {
        volume = entry?.volume.find(option => option.id === value)
        if (volume) {
          this.volume = volume
          break
        }
      }
      if (volume) {
        this.updateTemplateLinkedUserData(this.volume.userdataid)
        this.userdataDefaultOverridePolicy = this.volume.userdatapolicy
      }
    },
    updateFieldValueForSnapshot (value) {
      this.imageType = 'snapshotid'
      this.resetTemplateAssociatedResources()
      this.resetFromTemplateConfiguration()
      this.form.templateid = null
      this.form.isoid = null
      this.form.volumeid = null
      this.form.snapshotid = value
      let snapshot = null
      for (const entry of Object.values(this.options.snapshots)) {
        snapshot = entry?.snapshot.find(option => option.id === value)
        if (snapshot) {
          this.snapshot = snapshot
          break
        }
      }
      if (snapshot) {
        this.updateTemplateLinkedUserData(this.snapshot.userdataid)
        this.userdataDefaultOverridePolicy = this.snapshot.userdatapolicy
      }
    },
    changeRootDiskSizeOverride (value) {
      this.showRootDiskSizeChanger = value || this.templateStrictCustomRoot
      if (this.templateStrictCustomRoot) this.form.rootdisksizeitem = true
      if (!value) this.form.rootdisksize = this.serviceOffering?.rootdisksize || this.dataPreFill.minrootdisksize
    },
    updateComputeOffering (id, kvdoEnable) {
      this.form.computeofferingid = id
      this.form.computeOfferingKvdoEnable = kvdoEnable
      setTimeout(() => {
        this.updateTemplateConfigurationOfferingDetails(id)
      }, 500)
    },
    updateDiskOffering (id) {
      if (id === '0') {
        this.form.diskofferingid = undefined
        return
      }
      this.form.diskofferingid = id
    },
    updateOverrideDiskOffering (id) {
      if (id === '0') {
        this.form.overridediskofferingid = undefined
        return
      }
      this.form.overridediskofferingid = id
    },
    updateMultiDiskOffering (value) {
      this.form.multidiskoffering = value
    },
    updateAffinityGroups (ids) {
      this.form.affinitygroupids = ids
    },
    updateNetworks (ids) {
      this.form.networkids = ids
    },
    updateDefaultNetworks (id) {
      this.defaultnetworkid = id
      this.form.defaultnetworkid = id
    },
    updateNetworkConfig (networks) {
      console.log('networks :>> ', networks)
      this.networkConfig = networks
      if (this.networks.length > 0) {
        this.networks.forEach((el, idx) => {
          console.log('::선택된 net::>> ', el.id)
          networks.forEach(el2 => {
            if (el.id === el2.key) {
              console.log('::값바꾼 net::>> ', el2.key)
              this.networks[idx].enabled = el2.enabled === undefined ? true : el2.enabled
              this.networks[idx].ipAddress = el2.ipAddress ? el2.ipAddress : ''
              this.networks[idx].macAddress = el2.macAddress ? el2.macAddress : ''
            }
          })
        })
        // this.networks[0].enabled = true
        // console.log(this.networks[0].enabled)
      }
    },
    updateSshKeyPairs (names) {
      this.form.keypairs = names
      this.sshKeyPairs = names.map((sshKeyPair) => { return sshKeyPair.name })
    },
    updateUserData (id) {
      if (id === '0') {
        this.form.userdataid = undefined
        return
      }

      this.form.userdataid = id
      this.userDataParams = []
      getAPI('listUserData', { id: id }).then(json => {
        const resp = json?.listuserdataresponse?.userdata || []
        if (resp[0]) {
          const params = resp[0].params
          const dataParams = params ? params.split(',') : []
          dataParams.forEach((val, index) => {
            this.userDataParams.push({
              id: index,
              key: val
            })
          })
        }
      })
    },
    updateTemplateLinkedUserData (id) {
      if (id === '0') {
        return
      }
      this.templateUserDataParams = []

      getAPI('listUserData', { id: id }).then(json => {
        const resp = json.listuserdataresponse.userdata || []
        if (resp.length > 0) {
          var params = resp[0].params
          if (params) {
            var dataParams = params.split(',')
          }
          var that = this
          that.templateUserDataParams = []
          if (dataParams) {
            dataParams.forEach(function (val, index) {
              that.templateUserDataParams.push({
                id: index,
                key: val
              })
            })
          }
        }
      })
    },
    escapePropertyKey (key) {
      return key.split('.').join('\\002E')
    },
    updateSecurityGroups (securitygroupids) {
      this.securitygroupids = securitygroupids || []
    },
    getText (option) {
      return _.get(option, 'displaytext', _.get(option, 'name'))
    },
    fetchGuestOsCategories (skipFetchImages) {
      const key = 'guestOsCategories'
      const params = this.params[key]
      if (this.queryGuestOsCategoryId) {
        params.options.id = this.queryGuestOsCategoryId
      } else if (this.queryTemplateId || this.queryIsoId) {
        this.fetchImages()
        return Promise.resolve()
      }
      return this.fetchOptions(params, key, ['zones'])
        .then((res) => {
          if (!this.options.guestOsCategories) {
            this.options.guestOsCategories = []
          }
          if (!this.queryGuestOsCategoryId) {
            if (this.showUserCategoryForModernImageSelection) {
              const userCategory = {
                id: '0',
                name: this.$t('label.user'),
                disableimagefilters: true
              }
              if (this.$store.getters.avatar) {
                userCategory.icon = {
                  base64image: this.$store.getters.avatar
                }
              }
              this.options.guestOsCategories.push(userCategory)
            }
            if (this.showAllCategoryForModernImageSelection) {
              this.options.guestOsCategories.push({
                id: '-1',
                name: this.$t('label.all')
              })
            }
          }
          if (this.options.guestOsCategories.length > 0) {
            this.form.guestoscategoryid = this.options.guestOsCategories[0].id
          }
          if (skipFetchImages) {
            return
          }
          this.fetchImages()
        })
        .catch((e) => {
          console.error('Error fetching guestOsCategories:', e)
        })
    },
    changeArchitecture (arch) {
      this.selectedArchitecture = arch
      this.updateImages()
    },
    clearCreationSource () {
      this.selectedCreationSource = null; this.sourceConfirmed = false; this.sourceConfirmVisible = false; this.sourceAcknowledged = false
      this.template = null; this.iso = null; this.volume = null; this.snapshot = null
      for (const key of ['templateid', 'isoid', 'volumeid', 'snapshotid', 'boottype', 'bootmode', 'rootdisksize', 'rootkmskeyid', 'datakmskeyid', 'userdata', 'userdataid', 'overridediskofferingid']) this.form[key] = undefined
      for (const key of ['templateid', 'templatename', 'templatedisplaytext', 'isoid', 'isoname', 'isodisplaytext', 'guestosname', 'ostypeid', 'ostypename']) this.vm[key] = undefined
      this.sshKeyPairs = []; this.userDataValues = {}; this.templateUserDataValues = {}; this.userdataDefaultOverridePolicy = 'ALLOWOVERRIDE'
      this.rootStorageSelection = { valid: true }; this.dataStorageSelection = { valid: true }; this.diskOffering = null; this.overrideDiskOffering = null
      this.showRootDiskSizeChanger = false; this.additionalIsoSelection = { enabled: false, ids: [], valid: true }
    },
    selectCreationSource (source) {
      this.clearCreationSource()
      if (!source?.allowed) return
      this.selectedCreationSource = source
      this.form[this.imageType] = source.id; this.form.hypervisor = 'KVM'; this.form.vmNumber = 1
      this.form.boottype = source.bootprofile.boottype; this.form.bootmode = source.bootprofile.bootmode
      this.vm.hypervisor = 'KVM'; this.vm.guestosname = source.bootprofile.osname; this.vm.ostypeid = source.bootprofile.ostypeid; this.vm.ostypename = source.bootprofile.osname
      if (source.sourcekind === 'volume') {
        this.volume = source
        if (source.storage?.scope === 'CLUSTER' && source.storage.clusterid) {
          this.form.clusterid = source.storage.clusterid; this.form.hostid = undefined; this.onSelectClusterId(source.storage.clusterid)
        }
      } else this.snapshot = source
    },
    confirmCreationSource () {
      this.sourceConfirmed = true; this.sourceConfirmVisible = false; this.handleSubmit()
    },
    async validateCreationSource () {
      if (!this.selectedCreationSource?.allowed || this.sourceLoading || this.sourceOperationPending) throw new Error(this.$t('message.creation.source.required'))
      const args = Object.fromEntries(Object.entries({
        ...this.creationSourceQuery,
        id: this.selectedCreationSource.id,
        sourcerevision: this.selectedCreationSource.revision,
        serviceofferingid: this.form.computeofferingid,
        clusterid: this.form.clusterid,
        hostid: this.form.hostid,
        rootstorageid: this.rootStorageSelection.id
      }).filter(([, value]) => value != null && value !== ''))
      const result = (await getAPI('validateVirtualMachineCreation', args)).validatevirtualmachinecreationresponse
      if (!result?.allowed) throw new Error((result?.reasoncodes || []).map(reason => this.$t('message.creation.source.reason.' + reason)).join(' ') || this.$t('message.creation.source.required'))
      return result
    },
    changeImageType (imageType, restoreSourceFocus = false) {
      this.clearCreationSource()
      if (['volumeid', 'snapshotid'].includes(imageType)) this.form.vmNumber = 1
      this.additionalIsoSelection = { enabled: false, ids: [], valid: true }
      this.imageType = imageType
      this.updateImages()
      if (restoreSourceFocus) {
        this.$nextTick(() => {
          const selection = this.$refs.creationSourceSelection || this.$refs.imageSelection || this.$refs.legacyImageSelection
          const input = selection?.$el.querySelector('.ant-radio-button-wrapper-checked input, .ant-tabs-tab-active [role=tab]')
          if (input) input.focus()
        })
      }
    },
    handleSubmitAndStay (e) {
      this.form.stayonpage = true
      this.handleSubmit(e.domEvent)
    },
    handleSubmit (e) {
      console.log('wizard submit')
      if (this.loading.deploy) return
      this.formRef.value.validate().then(async () => {
        const values = toRaw(this.form)
        if (this.isCreationSource) {
          try {
            await this.validateCreationSource()
            if (!this.sourceConfirmed) { this.sourceAcknowledged = false; this.sourceConfirmVisible = true; return }
            this.sourceConfirmed = false
          } catch (error) { this.$notification.error({ message: this.$t('message.request.failed'), description: error.message }); return }
          values.vmNumber = 1
          values.stayonpage = true
        }
        if (this.imageType === 'isoid' && this.additionalIsoSelection.enabled && !this.additionalIsoSelection.valid) {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('message.additional.iso.required')
          })
          return
        }
        if (!values.templateid && !values.isoid && !values.volumeid && !values.snapshotid) {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('message.template.iso')
          })
          return
        } else if (values.isoid && (!values.diskofferingid || values.diskofferingid === '0')) {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('message.step.3.continue')
          })
          return
        }
        if (!values.computeofferingid) {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('message.step.2.continue')
          })
          return
        }
        if (this.error) {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('error.form.message')
          })
          return
        }

        if (this.storageSelectionEnabled && ((this.rootStorageSelection.id && !this.rootStorageSelection.valid) ||
          (this.selectedDataDiskOffering?.id && this.dataStorageSelection.id && !this.dataStorageSelection.valid))) {
          this.$notification.error({ message: this.$t('message.vm.storage.reselect') })
          return
        }
        if (this.isoDataDiskSelection.invalid) {
          this.$notification.error({ message: this.$t('message.vm.disk.invalid') })
          return
        }
        if (this.selectedDataDiskOffering?.id && (!Number.isSafeInteger(this.selectedDataDiskCount) ||
          this.selectedDataDiskCount < 1 || this.selectedDataDiskSize <= 0)) {
          this.$notification.error({ message: this.$t('message.vm.disk.invalid') })
          return
        }

        if (this.selectedDataDiskOffering?.id && 'listHypervisorCapabilities' in this.$store.getters.apis) {
          try {
            const response = await getAPI('listHypervisorCapabilities', { hypervisor: this.form.hypervisor || this.hypervisor || this.template?.hypervisor, page: 1, pagesize: 500 })
            const capabilities = response.listhypervisorcapabilitiesresponse.hypervisorCapabilities || []
            const limits = capabilities.map(item => Number(item.maxdatavolumeslimit)).filter(limit => limit > 0)
            const limit = limits.length ? Math.min(...limits) : 6
            if (this.selectedDataDiskCount > limit) {
              this.$notification.error({ message: this.$t('message.vm.disk.limit', { limit }) })
              return
            }
          } catch (error) { this.$notifyError(error); return }
        }
        this.loading.deploy = true

        let networkIds = []

        let deployVmData = {}
        // step 1 : select zone
        deployVmData.zoneid = values.zoneid
        deployVmData.podid = values.podid
        deployVmData.clusterid = values.clusterid
        deployVmData.hostid = values.hostid
        deployVmData.keyboard = values.keyboard
        if (!this.template?.deployasis) {
          deployVmData.boottype = values.boottype
          deployVmData.bootmode = values.bootmode
        }
        Object.assign(deployVmData, deploymentTpmParams(this.hypervisor, values))
        deployVmData.dynamicscalingenabled = values.dynamicscalingenabled
        deployVmData.iothreadsenabled = values.iothreadsenabled
        deployVmData.iodriverpolicy = values.iodriverpolicy
        deployVmData.nicmultiqueuenumber = values.nicmultiqueuenumber
        deployVmData.nicpackedvirtqueuesenabled = values.nicpackedvirtqueuesenabled
        const isUserdataAllowed = !this.userdataDefaultOverridePolicy || (this.userdataDefaultOverridePolicy === 'ALLOWOVERRIDE' && this.doUserdataOverride) || (this.userdataDefaultOverridePolicy === 'APPEND' && this.doUserdataAppend)
        if (isUserdataAllowed && values.userdata && values.userdata.length > 0) {
          deployVmData.userdata = this.$toBase64AndURIEncoded(values.userdata)
        }
        if (values.extraconfig && values.extraconfig.length > 0) {
          deployVmData.extraconfig = encodeURIComponent(values.extraconfig)
        }
        // step 2: select template/iso
        if (this.imageType === 'templateid') {
          deployVmData.templateid = values.templateid
          values.hypervisor = null
          if (values.templateKvdoEnable !== values.computeOfferingKvdoEnable) {
            this.$notification.error({
              message: this.$t('message.request.failed'),
              description: this.$t('message.step.3.kvdo')
            })
            this.loading.deploy = false
            return
          }
        } else if (this.imageType === 'volumeid') {
          deployVmData.volumeid = values.volumeid
        } else if (this.imageType === 'snapshotid') {
          deployVmData.snapshotid = values.snapshotid
        } else {
          deployVmData.templateid = values.isoid
          if (this.additionalIsoSelection.enabled) {
            deployVmData.additionalisoids = this.additionalIsoSelection.ids.join(',')
          }
        }

        if (this.showRootDiskSizeChanger && values.rootdisksize && values.rootdisksize > 0) {
          deployVmData.rootdisksize = values.rootdisksize
        } else if (this.rootDiskSizeFixed > 0 && !this.template?.deployasis) {
          deployVmData.rootdisksize = this.rootDiskSizeFixed
        }

        if (values.hypervisor && values.hypervisor.length > 0) {
          deployVmData.hypervisor = values.hypervisor
        }

        deployVmData.startvm = values.startvm === true
        if (this.isCreationSource) deployVmData.sourcerevision = this.selectedCreationSource.revision
        if (this.rootStorageSelection.id && this.storageSelectionEnabled) deployVmData.rootstorageid = this.rootStorageSelection.id

        // step 3: select service offering
        deployVmData.serviceofferingid = values.computeofferingid
        if (this.serviceOffering && this.serviceOffering.iscustomized) {
          if (values.cpunumber && (this.serviceOffering.cpunumber == null || this.serviceOffering.cpunumber === undefined)) {
            deployVmData['details[0].cpuNumber'] = values.cpunumber
          }
          if (values.cpuspeed && (this.serviceOffering.cpuspeed == null || this.serviceOffering.cpuspeed === undefined)) {
            deployVmData['details[0].cpuSpeed'] = values.cpuspeed
          }
          if (values.memory && (this.serviceOffering.memory == null || this.serviceOffering.memory === undefined)) {
            deployVmData['details[0].memory'] = values.memory
          }
        }
        // Add root disk KMS key if selected (optional - falls back to legacy passphrase if not provided)
        if (values.rootkmskeyid) {
          deployVmData.rootdiskkmskeyid = values.rootkmskeyid
        }
        if (this.selectedTemplateConfiguration) {
          deployVmData['details[0].configurationId'] = this.selectedTemplateConfiguration.id
        }
        if (!this.serviceOffering.diskofferingstrictness && values.overridediskofferingid && !values.isoid) {
          deployVmData.overridediskofferingid = values.overridediskofferingid
          if (values.rootdisksize && values.rootdisksize > 0) {
            deployVmData.rootdisksize = values.rootdisksize
          }
        }
        if (this.isCustomizedIOPS) {
          deployVmData['details[0].minIops'] = this.minIops
          deployVmData['details[0].maxIops'] = this.maxIops
        }
        if (values.machinecompatibility === 'ft-compatible') {
          deployVmData['details[0].kvm.guest.os.machine.type'] = 'pc-i440fx-9.2'
        }
        // step 4: select disk offering
        if (this.template && !this.template.deployasis && this.template.childtemplates && this.template.childtemplates.length > 0) {
          if (values.multidiskoffering) {
            let i = 0
            Object.entries(values.multidiskoffering).forEach(([disk, offering]) => {
              const diskKey = `datadiskofferinglist[${i}].datadisktemplateid`
              const offeringKey = `datadiskofferinglist[${i}].diskofferingid`
              deployVmData[diskKey] = disk
              deployVmData[offeringKey] = offering
              i++
            })
          }
        } else {
          if (this.imageType === 'isoid') {
            deployVmData.diskofferingid = values.diskofferingid
            if (this.diskOffering?.iscustomized) deployVmData.size = values.size
            // This existing size control and KMS selector describe ISO ROOT, never DATA.
            if (values.datakmskeyid) deployVmData.rootdiskkmskeyid = values.datakmskeyid
            if (this.isCustomizedDiskIOPS) {
              deployVmData['details[0].minIops'] = this.diskIOpsMin
              deployVmData['details[0].maxIops'] = this.diskIOpsMax
            }
            delete deployVmData.rootdisksize
            if (this.selectedDataDiskOffering?.id) deployVmData.startvm = false
          } else if (this.selectedDataDiskOffering?.id) {
            Object.assign(deployVmData, dataDiskRequest(this.diskOffering, values.size, this.selectedDataDiskCount, {
              kmskeyid: values.datakmskeyid,
              storageid: this.storageSelectionEnabled ? this.dataStorageSelection.id : undefined,
              miniops: this.isCustomizedDiskIOPS ? this.diskIOpsMin : undefined,
              maxiops: this.isCustomizedDiskIOPS ? this.diskIOpsMax : undefined
            }))
          }
        }
        // step 5: select an affinity group
        deployVmData.affinitygroupids = (values.affinitygroupids || []).join(',')
        // step 6: select network
        if (this.zone.networktype !== 'Basic') {
          if (this.nicToNetworkSelection && this.nicToNetworkSelection.length > 0) {
            for (var j in this.nicToNetworkSelection) {
              var nicNetwork = this.nicToNetworkSelection[j]
              deployVmData['nicnetworklist[' + j + '].nic'] = nicNetwork.nic
              deployVmData['nicnetworklist[' + j + '].network'] = nicNetwork.network
            }
          } else {
            const arrNetwork = []
            networkIds = values.networkids
            if (networkIds.length > 0) {
              for (let i = 0; i < networkIds.length; i++) {
                if (networkIds[i] === this.defaultnetworkid) {
                  const ipToNetwork = {
                    networkid: this.defaultnetworkid
                  }
                  arrNetwork.unshift(ipToNetwork)
                } else {
                  const ipToNetwork = {
                    networkid: networkIds[i]
                  }
                  arrNetwork.push(ipToNetwork)
                }
              }
            } else {
              this.$notification.error({
                message: this.$t('message.request.failed'),
                description: this.$t('message.step.4.continue')
              })
              this.loading.deploy = false
              return
            }
            for (let j = 0; j < arrNetwork.length; j++) {
              deployVmData['iptonetworklist[' + j + '].networkid'] = arrNetwork[j].networkid
              if (this.networkConfig.length > 0) {
                const networkConfig = this.networkConfig.filter((item) => item.key === arrNetwork[j].networkid)
                if (networkConfig && networkConfig.length > 0) {
                  deployVmData['iptonetworklist[' + j + '].ip'] = networkConfig[0].ipAddress ? networkConfig[0].ipAddress : undefined
                  deployVmData['iptonetworklist[' + j + '].mac'] = networkConfig[0].macAddress ? networkConfig[0].macAddress : undefined
                  deployVmData['iptonetworklist[' + j + '].enabled'] = networkConfig[0].enabled === undefined ? true : networkConfig[0].enabled
                }
              }
            }
          }
        }
        if (this.securitygroupids.length > 0) {
          deployVmData.securitygroupids = this.securitygroupids.join(',')
        }
        // step 7: select ssh key pair
        deployVmData.keypairs = this.sshKeyPairs.join(',')
        if (isUserdataAllowed) {
          deployVmData.userdataid = values.userdataid
        }

        if (values.name) {
          deployVmData.name = values.name
          deployVmData.displayname = values.name
        }
        if (values.group) {
          deployVmData.group = values.group
        }
        if (values.leaseduration) {
          deployVmData.leaseduration = values.leaseduration
        }
        if (values.leaseexpiryaction) {
          deployVmData.leaseexpiryaction = values.leaseexpiryaction
        }
        // step 8: enter setup
        if ('properties' in values) {
          const keys = Object.keys(values.properties)
          for (var i = 0; i < keys.length; ++i) {
            const propKey = keys[i].split('\\002E').join('.')
            deployVmData['properties[' + i + '].key'] = propKey
            deployVmData['properties[' + i + '].value'] = values.properties[keys[i]]
          }
        }
        if ('bootintosetup' in values) {
          deployVmData.bootintosetup = values.bootintosetup
        }

        if (this.owner.account) {
          deployVmData.account = this.owner.account
          deployVmData.domainid = this.owner.domainid
        } else if (this.owner.projectid) {
          deployVmData.domainid = this.owner.domainid
          deployVmData.projectid = this.owner.projectid
        }

        if (this.imageType === 'templateid' && this.template && this.template.hypervisor === 'External' && values.externaldetails) {
          Object.entries(values.externaldetails).forEach(([key, value]) => {
            deployVmData['externaldetails[0].' + key] = value
          })
        }

        const title = this.$t(values.startvm ? 'label.launch.vm' : 'label.create.vm')
        const description = values.name || ''
        const password = this.$t('label.password')

        deployVmData = Object.fromEntries(
          Object.entries(deployVmData).filter(([key, value]) => value !== undefined))

        var idx = 0
        if (this.templateUserDataValues) {
          for (const [key, value] of Object.entries(this.templateUserDataValues)) {
            deployVmData['userdatadetails[' + idx + '].' + `${key}`] = value
            idx++
          }
        }
        if (isUserdataAllowed && this.userDataValues) {
          for (const [key, value] of Object.entries(this.userDataValues)) {
            deployVmData['userdatadetails[' + idx + '].' + `${key}`] = value
            idx++
          }
        }

        if (this.isCreationSource) {
          for (const key of ['templateid', 'additionalisoids', 'userdata', 'userdataid', 'keypairs', 'rootdisksize', 'rootdiskkmskeyid', 'overridediskofferingid', 'diskofferingid', 'size', 'bootintosetup', 'machinecompatibility', 'tpmversion', 'tpmmodel', 'extraconfig', 'details[0].rootdisksize']) delete deployVmData[key]
          for (const key of Object.keys(deployVmData)) if (/^(userdatadetails|datadiskofferinglist|datadiskofferings|properties)|^details\[\d+\]\.(UEFI|tpm|kvm.guest.os.machine.type)/i.test(key)) delete deployVmData[key]
        }
        const httpMethod = this.isCreationSource || deployVmData.userdata ? 'POST' : 'GET'

        if (values.vmNumber) {
          let anySuccess = false
          for (var num = 1; num <= Number(values.vmNumber); num++) {
            let args = ''
            let data = ''
            if (values.name) {
              if (values.vmNumber === 1) {
                deployVmData.name = values.name
                deployVmData.displayname = values.name
              } else {
                if (deployVmData['iptonetworklist[0].ip'] != null || deployVmData['iptonetworklist[0].mac'] != null) {
                  this.$notification.error({
                    message: this.$t('message.request.failed'),
                    description: this.$t('message.deploy.vm.number')
                  })
                  this.loading.deploy = false
                  return
                }
                deployVmData.name = values.name + '-' + num
                deployVmData.displayname = values.name + '-' + num
              }
            }
            args = httpMethod === 'POST' ? {} : deployVmData
            data = httpMethod === 'POST' ? deployVmData : {}
            try {
              if (this.isCreationSource) {
                const operation = {
                  sourceid: this.selectedCreationSource.id,
                  sourcekind: this.selectedCreationSource.sourcekind,
                  name: deployVmData.name,
                  status: 'submitting',
                  startvm: deployVmData.startvm,
                  created: new Date().toISOString()
                }
                this.sourceOperations.push(operation); this.currentSourceOperation = this.sourceOperations[this.sourceOperations.length - 1]
                sessionStorage.setItem(this.sourceOperationsKey, JSON.stringify(this.sourceOperations))
              }
              let jobId
              if (values.volumeId) {
                jobId = await this.deployVirtualMachineForVolume(args, httpMethod, data)
              } else {
                jobId = await this.deployVM(args, httpMethod, data)
              }
              if (this.imageType === 'isoid' && this.selectedDataDiskOffering?.id) {
                const offering = { ...this.selectedDataDiskOffering }
                const selection = { ...this.isoDataDiskSelection }
                const storageId = this.storageSelectionEnabled ? this.dataStorageSelection.id : undefined
                const count = this.selectedDataDiskCount
                const record = {
                  deployJobId: jobId,
                  vmId: null,
                  startAfterCreation: values.startvm === true,
                  started: false,
                  status: 'running',
                  disks: dataDiskDeviceIds(count).map((deviceId, index) => ({ index, deviceId, status: 'pending' })),
                  volumeNamePrefix: (deployVmData.name || 'VM-' + jobId) + '-Data-',
                  volumeBaseParams: Object.fromEntries(Object.entries({
                    zoneid: deployVmData.zoneid,
                    diskofferingid: offering.id,
                    size: offering.iscustomized ? selection.size : undefined,
                    storageid: storageId,
                    miniops: selection.diskIOpsMin,
                    maxiops: selection.diskIOpsMax,
                    kmskeyid: selection.kmskeyid,
                    account: deployVmData.account,
                    domainid: deployVmData.domainid,
                    projectid: deployVmData.projectid
                  }).filter(([, value]) => value != null))
                }
                this.isoDataOperations.push(record)
                await completeIsoDiskDeployment(this.diskApi, this.isoDataOperations[this.isoDataOperations.length - 1])
              }
              if (this.isCreationSource && this.currentSourceOperation) {
                this.currentSourceOperation.jobid = jobId; this.currentSourceOperation.status = 'pending'
                sessionStorage.setItem(this.sourceOperationsKey, JSON.stringify(this.sourceOperations))
              }
              anySuccess = true
              if (num === 1 && !this.isCreationSource && !(this.imageType === 'isoid' && this.selectedDataDiskOffering?.id)) {
                this.$pollJob({
                  jobId,
                  title,
                  description,
                  successMethod: result => {
                    const vm = result.jobresult.virtualmachine
                    const name = vm.displayname || vm.name || vm.id
                    if (vm.password) {
                      this.$notification.success({
                        message: password + ` ${this.$t('label.for')} ` + name,
                        description: vm.password,
                        btn: () => h(
                          Button,
                          {
                            type: 'primary',
                            size: 'small',
                            onClick: () => this.copyToClipboard(vm.password)
                          },
                          () => [this.$t('label.copy.password')]
                        ),
                        duration: 0
                      })
                    }
                    this.performPostDeployBackupActions(vm)
                    if (!values.stayonpage) {
                      // eventBus.emit('vm-refresh-data')
                    }
                  },
                  loadingMessage: `${title} ${this.$t('label.in.progress')}`,
                  catchMessage: this.$t('error.fetching.async.job.result'),
                  action: {
                    isFetchData: false
                  }
                })
              }
            } catch (error) {
              if (this.isCreationSource && this.currentSourceOperation) {
                this.currentSourceOperation.status = error.response?.data?.errorresponse ? 'failed' : 'unknown'
                this.currentSourceOperation.error = error.response?.data?.errorresponse?.errortext || error.message
                sessionStorage.setItem(this.sourceOperationsKey, JSON.stringify(this.sourceOperations))
              }
              if (error.message !== undefined) {
                await this.$notifyError(error)
              }
              this.loading.deploy = false
              break
            }
            if (values.templateKvdoEnable && values.vmNumber > 1) {
              await new Promise(resolve => setTimeout(resolve, 6000)).then(() => {})
            }
          }
          if (anySuccess) {
            await new Promise(resolve => setTimeout(resolve, 3000)).then(() => {
              eventBus.emit('vm-refresh-data')
            })
            if (!values.stayonpage && !this.isoDataOperations.some(operation => operation.status === 'failed')) {
              await this.$router.back()
            }
          }
          this.form.stayonpage = false
          this.loading.deploy = false
        }
      }).catch(err => {
        this.formRef.value.scrollToField(err.errorFields[0].name, { block: 'center' })
        if (err) {
          if (err.licensesaccepted) {
            this.$notification.error({
              message: this.$t('message.license.agreements.not.accepted'),
              description: this.$t('message.step.license.agreements.continue')
            })
            return
          }
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('error.form.message')
          })
        }
      })
    },
    async diskApi (command, params) {
      const response = await (command.startsWith('list') || command === 'queryAsyncJobResult' ? getAPI : postAPI)(command, params)
      return response[command.toLowerCase() + 'response']
    },
    async retryIsoDisks (operation) {
      this.loading.deploy = true
      operation.error = null
      try {
        await completeIsoDiskDeployment(this.diskApi, operation)
        eventBus.emit('vm-refresh-data')
      } catch (error) {
        this.$notifyError(error)
      } finally { this.loading.deploy = false }
    },
    deployVM (args, httpMethod, data) {
      return new Promise((resolve, reject) => {
        postAPI('deployVirtualMachine', args, httpMethod, data).then(json => {
          const jobId = json.deployvirtualmachineresponse.jobid
          if (this.currentSourceOperation?.status === 'submitting') this.currentSourceOperation.vmid = json.deployvirtualmachineresponse.id
          return resolve(jobId)
        }).catch(error => {
          return reject(error)
        })
      })
    },
    deployVirtualMachineForVolume (args, httpMethod, data) {
      return new Promise((resolve, reject) => {
        postAPI('deployVirtualMachineForVolume', args, httpMethod, data).then(json => {
          const jobId = json.deployvirtualmachineforvolumeresponse.jobid
          return resolve(jobId)
        }).catch(error => {
          return reject(error)
        })
      })
    },
    fetchOwnerOptions (OwnerOptions) {
      this.owner = {
        projectid: null,
        domainid: store.getters.userInfo.domainid,
        account: store.getters.userInfo.account
      }
      if (OwnerOptions.selectedAccountType === 'Account') {
        if (!OwnerOptions.selectedAccount) {
          return
        }
        this.owner.account = OwnerOptions.selectedAccount
        this.owner.domainid = OwnerOptions.selectedDomain
        this.owner.projectid = null
      } else if (OwnerOptions.selectedAccountType === 'Project') {
        if (!OwnerOptions.selectedProject) {
          return
        }
        this.owner.account = null
        this.owner.domainid = null
        this.owner.projectid = OwnerOptions.selectedProject
      }
      if (OwnerOptions.initialized) {
        this.resetData()
      }
    },
    fetchZones (zoneId, listZoneAllow) {
      this.zones = []
      return new Promise((resolve) => {
        this.loading.zones = true
        const param = this.params.zones
        const args = { showicon: true }
        if (zoneId) args.id = zoneId
        getAPI(param.list, args).then(json => {
          const zoneResponse = json.listzonesresponse.zone || []
          if (listZoneAllow && listZoneAllow.length > 0) {
            zoneResponse.map(zone => {
              if (listZoneAllow.includes(zone.id)) {
                this.zones.push(zone)
              }
            })
          } else {
            this.zones = zoneResponse
          }

          resolve(this.zones)
        }).catch(function (error) {
          console.log(error.stack)
        }).finally(() => {
          this.loading.zones = false
        })
      })
    },
    fetchOptions (param, name, exclude) {
      return new Promise((resolve, reject) => {
        if (exclude && exclude.length > 0 && exclude.includes(name)) {
          return resolve(null)
        }
        this.loading[name] = true
        param.loading = true
        param.opts = []
        const options = param.options || {}
        if (!('listall' in options) && !['zones', 'pods', 'clusters', 'hosts', 'hypervisors'].includes(name)) {
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
              if (!responseKey.includes('response')) {
                return resolve(null)
              }
              _.map(responseItem, (response, key) => {
                if (key === 'count') {
                  this.rowCount[name] = response
                  return
                }
                param.opts = response
                this.options[name] = response

                if (name === 'hypervisors') {
                  const hypervisorFromResponse = response[0] && response[0].name ? response[0].name : null
                  this.dataPreFill.hypervisor = hypervisorFromResponse
                  this.form.hypervisor = hypervisorFromResponse
                }

                if (param.field) {
                  this.fillValue(param.field)
                }
              })

              if (name === 'zones') {
                let zoneid = ''
                if (this.$route.query.zoneid) {
                  zoneid = this.$route.query.zoneid
                } else if (this.options.zones.length === 1) {
                  zoneid = this.options.zones[0].id
                }
                if (zoneid) {
                  this.form.zoneid = zoneid
                  this.onSelectZoneId(zoneid)
                }
              }
            })
          })
          resolve(response)
        }).catch(function (error) {
          console.log(error.stack)
          param.loading = false
          reject(error)
        }).finally(() => {
          this.loading[name] = false
        })
      })
    },
    async fetchUnattachedVolumes (volumeFilter, params = {}) {
      const result = (await getAPI('listVirtualMachineCreationSources', { ...this.creationSourceQuery, sourcekind: 'volume', id: this.queryVolumeId, page: params.page || 1, pagesize: params.pageSize || 10 })).listvirtualmachinecreationsourcesresponse || {}
      return { listvolumesresponse: { count: result.count || 0, volume: result.creationsource || [] } }
    },
    async fetchRootSnapshots (snapshotFilter, params = {}) {
      const result = (await getAPI('listVirtualMachineCreationSources', { ...this.creationSourceQuery, sourcekind: 'snapshot', id: this.querySnapshotId, page: params.page || 1, pagesize: params.pageSize || 10 })).listvirtualmachinecreationsourcesresponse || {}
      return { listsnapshotsresponse: { count: result.count || 0, snapshot: result.creationsource || [] } }
    },
    fetchTemplates (templateFilter, params) {
      const args = Object.assign({}, params)
      if (this.isModernImageSelection && this.form.guestoscategoryid && !['-1', '0'].includes(this.form.guestoscategoryid)) {
        args.oscategoryid = this.form.guestoscategoryid
      }
      if (!args.page || args.keyword || (args.category && args.category !== templateFilter)) {
        args.page = 1
      }
      args.pageSize = args.pageSize || 10
      args.zoneid = _.get(this.zone, 'id')
      if (this.isZoneSelectedMultiArch) {
        args.arch = this.selectedArchitecture
      }
      if (this.selectedExtensionId) {
        args.extensionid = this.selectedExtensionId
      }
      args.account = store.getters.project?.id ? null : this.owner.account
      args.domainid = store.getters.project?.id ? null : this.owner.domainid
      args.projectid = store.getters.project?.id || this.owner.projectid
      args.templatefilter = templateFilter
      args.isready = true
      args.details = 'all'
      args.showicon = 'true'
      args.id = this.queryTemplateId
      args.isvnf = false

      delete args.category
      delete args.public
      delete args.featured

      return new Promise((resolve, reject) => {
        getAPI('listTemplates', args).then((response) => {
          resolve(response)
        }).catch((reason) => {
          // ToDo: Handle errors
          reject(reason)
        })
      })
    },
    fetchIsos (isoFilter, params) {
      const args = Object.assign({}, params)
      if (this.isModernImageSelection && this.form.guestoscategoryid) {
        args.oscategoryid = this.form.guestoscategoryid
      }
      if (!args.page || args.keyword || (args.category && args.category !== isoFilter)) {
        args.page = 1
      }
      args.pageSize = args.pageSize || 10
      args.zoneid = _.get(this.zone, 'id')
      if (this.isZoneSelectedMultiArch) {
        args.arch = this.selectedArchitecture
      }
      args.account = store.getters.project?.id ? null : this.owner.account
      args.domainid = store.getters.project?.id ? null : this.owner.domainid
      args.projectid = store.getters.project?.id || this.owner.projectid
      args.isoFilter = isoFilter
      args.isready = true
      args.bootable = true
      args.showicon = 'true'
      args.id = this.queryIsoId

      delete args.category
      delete args.public
      delete args.featured

      return new Promise((resolve, reject) => {
        getAPI('listIsos', args).then((response) => {
          resolve(response)
        }).catch((reason) => {
          // ToDo: Handle errors
          reject(reason)
        })
      })
    },
    fetchImages (params) {
      if (this.isCreationSource) return
      if (this.imageType === 'isoid') {
        this.fetchAllIsos(params)
        return
      }
      if (this.imageType === 'volumeid') {
        this.fetchAllVolumes(params)
        return
      }
      if (this.imageType === 'snapshotid') {
        this.fetchAllSnapshots(params)
        return
      }
      this.fetchAllTemplates(params)
    },
    fetchAllTemplates (params) {
      const promises = []
      const templates = {}
      this.loading.templates = true
      this.imageSearchFilters = params
      const templateFilters = this.getImageFilters(params)
      templateFilters.forEach((filter) => {
        templates[filter] = { count: 0, template: [] }
        promises.push(this.fetchTemplates(filter, params))
      })
      this.options.templates = templates
      Promise.all(promises).then((response) => {
        response.forEach((resItem, idx) => {
          templates[templateFilters[idx]] = _.isEmpty(resItem.listtemplatesresponse) ? { count: 0, template: [] } : resItem.listtemplatesresponse
          this.options.templates = { ...templates }
        })
      }).catch((reason) => {
        console.log(reason)
      }).finally(() => {
        this.loading.templates = false
      })
    },
    fetchAllIsos (params) {
      const promises = []
      const isos = {}
      this.loading.isos = true
      this.imageSearchFilters = params
      const isoFilters = this.getImageFilters(params)
      isoFilters.forEach((filter) => {
        isos[filter] = { count: 0, iso: [] }
        promises.push(this.fetchIsos(filter, params))
      })
      this.options.isos = isos
      Promise.all(promises).then((response) => {
        response.forEach((resItem, idx) => {
          isos[isoFilters[idx]] = _.isEmpty(resItem.listisosresponse) ? { count: 0, iso: [] } : resItem.listisosresponse
          this.options.isos = { ...isos }
        })
      }).catch((reason) => {
        console.log(reason)
      }).finally(() => {
        this.loading.isos = false
      })
    },
    fetchAllVolumes (params) {
      const promises = []
      const volumes = {}
      this.loading.volumes = true
      this.imageSearchFilters = params
      const volumeFilters = this.getImageFilters(params)
      volumeFilters.forEach((filter) => {
        volumes[filter] = { count: 0, volume: [] }
        promises.push(this.fetchUnattachedVolumes(filter, params))
      })
      this.options.volumes = volumes
      Promise.all(promises).then((response) => {
        response.forEach((resItem, idx) => {
          volumes[volumeFilters[idx]] = _.isEmpty(resItem.listvolumesresponse) ? { count: 0, volume: [] } : resItem.listvolumesresponse
          this.options.volumes = { ...volumes }
        })
      }).catch((reason) => {
        console.log(reason)
      }).finally(() => {
        this.loading.volumes = false
      })
    },
    fetchAllSnapshots (params) {
      const promises = []
      const snapshots = {}
      this.loading.snapshots = true
      this.imageSearchFilters = params
      const snapshotFilters = this.getImageFilters(params)
      snapshotFilters.forEach((filter) => {
        snapshots[filter] = { count: 0, snapshot: [] }
        promises.push(this.fetchRootSnapshots(filter, params))
      })
      this.options.snapshots = snapshots
      Promise.all(promises).then((response) => {
        response.forEach((resItem, idx) => {
          snapshots[snapshotFilters[idx]] = _.isEmpty(resItem.listsnapshotsresponse) ? { count: 0, snapshot: [] } : resItem.listsnapshotsresponse
          this.options.snapshots = { ...snapshots }
        })
      }).catch((reason) => {
        console.log(reason)
      }).finally(() => {
        this.loading.snapshots = false
      })
    },
    filterOption (input, option) {
      return option.label.toUpperCase().indexOf(input.toUpperCase()) >= 0
    },
    resetTemplatesList () {
      const templates = {}
      const templateFilters = this.getImageFilters(null, true)
      templateFilters.forEach((filter) => {
        templates[filter] = { count: 0, template: [] }
      })
      this.options.templates = templates
    },
    resetTemplateAssociatedResources () {
      this.templateConfigurations = []
      this.selectedTemplateConfiguration = {}
      this.templateNics = []
      this.templateLicenses = []
      this.templateProperties = {}
    },
    resetIsosList () {
      const isos = {}
      const isoFilters = this.getImageFilters(null, true)
      isoFilters.forEach((filter) => {
        isos[filter] = { count: 0, iso: [] }
      })
      this.options.isos = isos
    },
    async fetchZoneOptions () {
      let guestOsFetch = null
      for (const [name, param] of Object.entries(this.params)) {
        if (this.queryNetworkId && name === 'networks') {
          param.options = { id: this.queryNetworkId }
        }
        const shouldLoad = !('isLoad' in param) || param.isLoad
        if (!shouldLoad) continue
        if (this.isModernImageSelection && name === 'guestOsCategories') {
          guestOsFetch = this.fetchGuestOsCategories(true)
        } else {
          this.fetchOptions(param, name, ['zones'])
        }
      }

      if (this.isModernImageSelection && guestOsFetch) {
        await guestOsFetch
      }
      this.fetchImages()
      this.updateTemplateKey()
      this.formModel = toRaw(this.form)
    },
    async updateZoneAllowsBackupOperations () {
      this.zoneAllowsBackupOperations = false
      if (!this.zoneId) {
        return
      }
      if (!('listBackupOfferings' in this.$store.getters.apis) ||
        !('assignVirtualMachineToBackupOffering' in this.$store.getters.apis)) {
        return
      }
      const params = {
        zoneid: this.zoneId,
        issystem: false,
        listall: true,
        page: 1,
        pageSize: 1
      }
      try {
        const response = await getAPI('listBackupOfferings', params)
        const backupOfferings = response.listbackupofferingsresponse.backupoffering || []
        this.zoneAllowsBackupOperations = backupOfferings.length > 0
      } catch (error) {
        console.error('Error fetching backup offerings:', error)
        this.zoneAllowsBackupOperations = false
      }
    },
    onSelectZoneId (value) {
      this.clearCreationSource()
      if (this.dataPreFill.zoneid !== value) {
        this.dataPreFill = {}
      }
      this.zoneId = value
      this.podId = null
      this.clusterId = null
      this.zone = _.find(this.options.zones, (option) => option.id === value)
      this.zoneSelected = true
      this.isZoneSelectedMultiArch = this.zone.ismultiarch
      if (this.isZoneSelectedMultiArch) {
        this.selectedArchitecture = this.architectureTypes.opts[0].id
      }
      this.form.startvm = false
      this.selectedZone = this.zoneId
      this.form.zoneid = this.zoneId
      this.form.clusterid = undefined
      this.form.podid = undefined
      this.form.hostid = undefined
      this.form.guestoscategoryid = undefined
      this.form.templateid = undefined
      this.form.isoid = undefined
      this.resetTemplatesList()
      this.resetIsosList()
      this.imageType = this.queryVolumeId ? 'volumeid' : this.querySnapshotId ? 'snapshotid' : this.queryIsoId ? 'isoid' : 'templateid'
      this.form.backupofferingid = undefined
      this.selectedBackupOffering = null
      this.fetchZoneOptions()
      this.updateZoneAllowsBackupOperations()
      this.fetchKmsKeys()
    },
    onSelectPodId (value) {
      this.podId = value
      if (this.podId === null) {
        this.form.podid = undefined
      }

      this.fetchOptions(this.params.clusters, 'clusters')
      this.fetchOptions(this.params.hosts, 'hosts')
    },
    onSelectClusterId (value) {
      this.clusterId = value
      if (this.clusterId === null) {
        this.form.clusterid = undefined
      }
      this.fetchOptions(this.params.hosts, 'hosts')
      if (this.clusterId && Array.isArray(this.options.clusters)) {
        const cluster = this.options.clusters.find(c => c.id === this.clusterId)
        this.handleComputeResourceSelected(cluster)
      }
    },
    onSelectHostId (value) {
      this.hostId = value
      if (this.hostId === null) {
        this.form.hostid = undefined
      }
      if (this.hostId && Array.isArray(this.options.hosts)) {
        const host = this.options.hosts.find(h => h.id === this.hostId)
        this.handleComputeResourceSelected(host)
      }
    },
    updateImages () {
      if (this.isModernImageSelection && this.imageType !== 'snapshotid' && this.imageType !== 'volumeid') {
        this.fetchGuestOsCategories()
        return
      }
      this.fetchImages()
    },
    handleComputeResourceSelected (computeResource) {
      if (!computeResource) {
        this.selectedExtensionId = null
        return
      }
      const resourceArch = computeResource.arch
      const needArchChange = resourceArch &&
        this.isZoneSelectedMultiArch &&
        this.selectedArchitecture !== resourceArch
      const resourceHypervisor = computeResource.hypervisor || computeResource.hypervisortype
      const resourceExtensionId = resourceHypervisor === 'External' ? computeResource.extensionid : null
      const needExtensionIdChange = this.selectedExtensionId !== resourceExtensionId
      if (!needArchChange && !needExtensionIdChange) {
        return
      }
      if (needArchChange && !needExtensionIdChange) {
        this.changeArchitecture(resourceArch, this.imageType === 'templateid')
        return
      }
      this.selectedExtensionId = resourceExtensionId
      if (needArchChange) {
        this.selectedArchitecture = resourceArch
      }
      this.updateImages()
    },
    onSelectGuestOsCategory (value) {
      this.form.guestoscategoryid = value
      this.fetchImages(this.imageSearchFilters)
    },
    handleSearchFilter (name, options) {
      if (name === 'serviceOfferings') {
        this.params[name].options.kvdoenable = this.template?.kvdoenable
      }
      this.params[name].options = { ...this.params[name].options, ...options }
      this.fetchOptions(this.params[name], name)
    },
    onTabChange (key, type) {
      this[type] = key
      if (key === 'isoid') {
        this.fetchAllIsos()
      } else if (key === 'volumeId') {
        this.fetchAllRbdImage()
      }
    },
    onUserdataTabChange (key, type) {
      this[type] = key
      this.userDataParams = []
    },
    fetchTemplateNics (template) {
      var nics = []
      this.nicToNetworkSelection = []
      if (template && template.deployasisdetails && Object.keys(template.deployasisdetails).length > 0) {
        var keys = Object.keys(template.deployasisdetails)
        keys = keys.filter(key => key.startsWith('network-'))
        for (var key of keys) {
          var propertyMap = JSON.parse(template.deployasisdetails[key])
          nics.push(propertyMap)
        }
        nics.sort(function (a, b) {
          return a.InstanceID - b.InstanceID
        })
        if (this.options.networks && this.options.networks.length > 0) {
          for (var i = 0; i < nics.length; ++i) {
            var nic = nics[i]
            nic.id = nic.InstanceID
            var network = this.options.networks[Math.min(i, this.options.networks.length - 1)]
            nic.selectednetworkid = network.id
            nic.selectednetworkname = network.name
            this.nicToNetworkSelection.push({ nic: nic.id, network: network.id })
          }
        }
      }
      return nics
    },
    groupBy (array, key) {
      const result = {}
      array.forEach(item => {
        if (!result[item[key]]) {
          result[item[key]] = []
        }
        result[item[key]].push(item)
      })
      return result
    },
    fetchTemplateProperties (template) {
      var properties = []
      if (template && template.deployasisdetails && Object.keys(template.deployasisdetails).length > 0) {
        var keys = Object.keys(template.deployasisdetails)
        keys = keys.filter(key => key.startsWith('property-'))
        for (var key of keys) {
          var propertyMap = JSON.parse(template.deployasisdetails[key])
          properties.push(propertyMap)
        }
        properties.sort(function (a, b) {
          return a.index - b.index
        })
      }
      return this.groupBy(properties, 'category')
    },
    fetchTemplateConfigurations (template) {
      var configurations = []
      if (template && template.deployasisdetails && Object.keys(template.deployasisdetails).length > 0) {
        var keys = Object.keys(template.deployasisdetails)
        keys = keys.filter(key => key.startsWith('configuration-'))
        for (var key of keys) {
          var configuration = JSON.parse(template.deployasisdetails[key])
          configuration.name = configuration.label
          configuration.displaytext = configuration.label
          configuration.iscustomized = true
          configuration.cpunumber = 0
          configuration.cpuspeed = 0
          configuration.memory = 0
          for (var harwareItem of configuration.hardwareItems) {
            if (harwareItem.resourceType === 'Processor') {
              configuration.cpunumber = harwareItem.virtualQuantity
              configuration.cpuspeed = harwareItem.reservation
            } else if (harwareItem.resourceType === 'Memory') {
              configuration.memory = harwareItem.virtualQuantity
            }
          }
          configurations.push(configuration)
        }
        configurations.sort(function (a, b) {
          return a.index - b.index
        })
      }
      return configurations
    },
    fetchTemplateLicenses (template) {
      var licenses = []
      if (template && template.deployasisdetails && Object.keys(template.deployasisdetails).length > 0) {
        var keys = Object.keys(template.deployasisdetails)
        const prefix = /eula-\d-/
        keys = keys.filter(key => key.startsWith('eula-')).sort()
        for (var key of keys) {
          var license = {
            id: this.escapePropertyKey(key.replace(' ', '-')),
            name: key.replace(prefix, ''),
            text: template.deployasisdetails[key]
          }
          licenses.push(license)
        }
      }
      return licenses
    },
    deleteFrom (options, values) {
      for (const value of values) {
        delete options[value]
      }
    },
    resetFromTemplateConfiguration () {
      this.deleteFrom(this.form, ['disksize', 'rootdisksize'])
      this.deleteFrom(this.params.serviceOfferings.options, ['templateid', 'cpuspeed', 'cpunumber', 'memory'])
      this.deleteFrom(this.dataPreFill, ['cpuspeed', 'cpunumber', 'memory'])
      this.handleSearchFilter('serviceOfferings', {
        page: 1,
        pageSize: 10
      })
    },
    handleTemplateConfiguration () {
      if (!this.selectedTemplateConfiguration && !this.template.templatetag) {
        return
      }
      let params = {
        page: 1,
        pageSize: 10
      }
      if (this.template.templatetag) {
        params.templateid = this.template.id
      }
      if (this.selectedTemplateConfiguration && Object.keys(this.selectedTemplateConfiguration).length > 0) {
        params = {
          ...params,
          cpunumber: this.selectedTemplateConfiguration.cpunumber,
          cpuspeed: this.selectedTemplateConfiguration.cpuspeed,
          memory: this.selectedTemplateConfiguration.memory
        }
        this.dataPreFill.cpunumber = params.cpunumber
        this.dataPreFill.cpuspeed = params.cpuspeed
        this.dataPreFill.memory = params.memory
      }
      this.handleSearchFilter('serviceOfferings', params)
    },
    updateFormProperties () {
      if (this.vm.templateid && this.templateProperties && Object.keys(this.templateProperties).length > 0) {
        this.form.properties = {}
        Object.keys(this.templateProperties).forEach((category, categoryIndex) => {
          this.templateProperties[category].forEach((property, _) => {
            if (property.type && property.type === 'boolean') {
              this.form.properties[this.escapePropertyKey(property.key)] = property.value === 'TRUE'
            } else if (property.type && (property.type === 'int' || property.type === 'real')) {
              this.form.properties[this.escapePropertyKey(property.key)] = property.value
            } else if (property.type && property.type === 'string' && property.qualifiers && property.qualifiers.startsWith('ValueMap')) {
              this.form.properties[this.escapePropertyKey(property.key)] = property.value && property.value.length > 0
                ? property.value
                : this.getPropertyQualifiers(property.qualifiers, 'select')[0]
            } else if (property.type && property.type === 'string' && property.password) {
              this.form.properties[this.escapePropertyKey(property.key)] = property.value
              this.rules['properties.' + this.escapePropertyKey(property.key)] = [{
                validator: async (rule, value) => {
                  if (!property.qualifiers) {
                    return Promise.resolve()
                  }
                  var minlength = this.getPropertyQualifiers(property.qualifiers, 'number-select').min
                  var maxlength = this.getPropertyQualifiers(property.qualifiers, 'number-select').max
                  var errorMessage = ''
                  var isPasswordInvalidLength = function () {
                    return false
                  }
                  if (minlength) {
                    errorMessage = this.$t('message.validate.minlength').replace('{0}', minlength)
                    isPasswordInvalidLength = function () {
                      return !value || value.length < minlength
                    }
                  }
                  if (maxlength !== Number.MAX_SAFE_INTEGER) {
                    if (minlength) {
                      errorMessage = this.$t('message.validate.range.length').replace('{0}', minlength).replace('{1}', maxlength)
                      isPasswordInvalidLength = function () {
                        return !value || (maxlength < value.length || value.length < minlength)
                      }
                    } else {
                      errorMessage = this.$t('message.validate.maxlength').replace('{0}', maxlength)
                      isPasswordInvalidLength = function () {
                        return !value || value.length > maxlength
                      }
                    }
                  }
                  if (isPasswordInvalidLength()) {
                    return Promise.reject(errorMessage)
                  }
                  return Promise.resolve()
                }
              }]
            } else {
              this.form.properties[this.escapePropertyKey(property.key)] = property.value
            }
          })
        })
      }
    },
    updateTemplateParameters () {
      if (this.template) {
        this.templateNics = this.fetchTemplateNics(this.template)
        this.templateConfigurations = this.fetchTemplateConfigurations(this.template)
        this.templateLicenses = this.fetchTemplateLicenses(this.template)
        this.templateProperties = this.fetchTemplateProperties(this.template)
        this.selectedTemplateConfiguration = {}
        setTimeout(() => {
          if (this.templateConfigurationExists || this.template.templatetag) {
            this.selectedTemplateConfiguration = this.templateConfigurationExists ? this.templateConfigurations[0] : {}
            this.handleTemplateConfiguration()
            if (this.selectedTemplateConfiguration) {
              this.updateFieldValue('templateConfiguration', this.selectedTemplateConfiguration.id)
            }
            this.updateComputeOffering(null, this.template.kvdoEnable) // reset as existing selection may be incompatible
          }
        }, 500)
        this.updateFormProperties()
      }
    },
    onSelectTemplateConfigurationId (value) {
      this.selectedTemplateConfiguration = _.find(this.templateConfigurations, (option) => option.id === value)
      this.handleTemplateConfiguration()
      this.updateComputeOffering(null, null)
    },
    updateTemplateConfigurationOfferingDetails (offeringId) {
      this.rootDiskSizeFixed = 0
      var offering = this.serviceOffering
      if (!offering || offering.id !== offeringId) {
        offering = _.find(this.options.serviceOfferings, (option) => option.id === offeringId)
      }
      if (offering && offering.iscustomized && this.templateConfigurationExists && this.selectedTemplateConfiguration) {
        if ('cpunumber' in this.form.fieldsStore.fieldsMeta) {
          this.updateFieldValue('cpunumber', this.selectedTemplateConfiguration.cpunumber)
        }
        if ((offering.cpuspeed == null || offering.cpuspeed === undefined) && 'cpuspeed' in this.form.fieldsStore.fieldsMeta) {
          this.updateFieldValue('cpuspeed', this.selectedTemplateConfiguration.cpuspeed)
        }
        if ('memory' in this.form.fieldsStore.fieldsMeta) {
          this.updateFieldValue('memory', this.selectedTemplateConfiguration.memory)
        }
      }
      if (offering && offering.rootdisksize > 0) {
        this.rootDiskSizeFixed = offering.rootdisksize
        this.showRootDiskSizeChanger = false
      }

      if (this.isLeaseFeatureEnabled) {
        if (offering && offering.leaseduration > 0) {
          this.showLeaseOptions = true
        } else {
          this.showLeaseOptions = false
        }
        this.onToggleLeaseData()
      }

      if (this.templateStrictCustomRoot) {
        this.showRootDiskSizeChanger = true
        if (!(Number(this.form.rootdisksize) > 0)) this.form.rootdisksize = this.dataPreFill.minrootdisksize
      }
      this.form.rootdisksizeitem = this.templateStrictCustomRoot || this.showRootDiskSizeChanger && this.rootDiskSizeFixed > 0
      this.formModel = toRaw(this.form)
    },
    handlerError (error) {
      this.error = error
    },
    onSelectDiskSize (rowSelected) {
      this.diskSelected = rowSelected
    },
    onSelectRbdSize (rowSelected) {
      this.rbdSelected = rowSelected
    },
    onSelectRootDiskSize (rowSelected) {
      this.rootDiskSelected = rowSelected
    },
    updateIOPSValue (input, value) {
      this[input] = value
    },
    onBootTypeChange (value) {
      this.fetchBootModes(value)
      this.defaultBootMode = this.options.bootModes?.[0]?.id || undefined
      this.updateFieldValue('bootmode', this.defaultBootMode)
    },
    handleNicsNetworkSelection (nicToNetworkSelection) {
      this.nicToNetworkSelection = nicToNetworkSelection
    },
    getSelectedNetworksWithExistingConfig (networks) {
      for (var i in this.networks) {
        var n = this.networks[i]
        for (var c of this.networkConfig) {
          if (n.id === c.key) {
            n = { ...n, ...c }
            networks[i] = n
            break
          }
        }
      }
      return networks
    },
    copyToClipboard (txt) {
      const parent = this
      this.$copyText(txt, document.body, function (err) {
        if (!err) {
          parent.$message.success(parent.$t('label.copied.clipboard'))
        }
      })
    },
    onToggleLeaseData () {
      if (this.showLeaseOptions === false) {
        this.leaseduration = -1
        this.leaseexpiryaction = undefined
      } else {
        this.leaseduration = this.serviceOffering.leaseduration ? this.serviceOffering.leaseduration : this.defaultLeaseDuration
        this.leaseexpiryaction = this.serviceOffering.leaseexpiryaction ? this.serviceOffering.leaseexpiryaction : this.defaultLeaseExpiryAction
      }
      this.form.leaseduration = this.leaseduration
      this.form.leaseexpiryaction = this.leaseexpiryaction
    },
    async validateNumber (rule, value) {
      if (value && (isNaN(value) || value <= 0)) {
        return Promise.reject(this.$t('message.error.number'))
      }
      return Promise.resolve()
    },
    onExternalDetailsEnabledChange (val) {
      if (val || !this.form.externaldetails) {
        return
      }
      this.form.externaldetails = undefined
    },
    onChangeBackupOffering (val) {
      if (!val || !val.id) {
        this.selectedBackupOffering = null
        this.backupSchedules = []
        return
      }
      this.selectedBackupOffering = val
      if (this.backupSchedules && this.backupSchedules.length > 0 && !this.$isBackupProviderSupportsQuiesceVm(val.provider)) {
        this.backupSchedules = this.backupSchedules.filter(item => !item.quiescevm)
      }
    },
    onAddBackupSchedule (schedule) {
      if (!schedule) {
        return
      }
      // This is in accordance with the API behavior that only one schedule per intervaltype is allowed
      const existingIndex = this.backupSchedules.findIndex(item => item.intervaltype === schedule.intervaltype)
      if (existingIndex !== -1) {
        message.warning({
          content: this.$t('message.backup.update.existing.schedule') + ' ' + schedule.intervaltype,
          duration: 2
        })
        this.backupSchedules.splice(existingIndex, 1, schedule)
        return
      }
      this.backupSchedules.push(schedule)
    },
    async performPostDeployBackupActions (vm) {
      if (!this.zoneAllowsBackupOperations) {
        return
      }
      const assigned = await this.assignVirtualMachineToBackupOfferingIfNeeded(vm)
      if (assigned) {
        await this.createVirtualMachineBackupSchedulesIfNeeded(vm)
      }
    },
    assignVirtualMachineToBackupOfferingIfNeeded (vm) {
      if (!this.form.backupofferingid || !vm || !vm.id) {
        return Promise.resolve(false)
      }
      const params = {
        virtualmachineid: vm.id,
        backupofferingid: this.form.backupofferingid
      }
      return new Promise((resolve, reject) => {
        postAPI('assignVirtualMachineToBackupOffering', params).then(json => {
          const jobId = json.assignvirtualmachinetobackupofferingresponse?.jobid
          if (!jobId) {
            resolve(false)
            return
          }
          this.$pollJob({
            jobId,
            loadingMessage: `${this.$t('label.backup.offering.assign')} ${this.$t('label.in.progress')}`,
            successMethod: () => {
              resolve(true)
            },
            errorMethod: (result) => {
              this.$notification.error({
                message: this.$t('label.backup.offering.assign.failed'),
                description: result?.jobresult?.errortext || this.$t('error.fetching.async.job.result')
              })
              resolve(false)
            },
            catchMessage: this.$t('error.fetching.async.job.result')
          })
        }).catch(error => {
          this.$notification.error({
            message: this.$t('label.backup.offering.assign.failed'),
            description: error.message || error
          })
          resolve(false)
        })
      })
    },
    createVirtualMachineBackupSchedulesIfNeeded (vm) {
      if (!vm || !vm.id || !this.backupSchedules) {
        return Promise.resolve()
      }
      const promises = (this.backupSchedules || []).map(item =>
        this.createVirtualMachineBackupSchedule(vm, item)
      )
      return Promise.all(promises)
    },
    createVirtualMachineBackupSchedule (vm, item) {
      const params = {
        virtualmachineid: vm.id,
        intervaltype: item.intervaltype,
        maxbackups: item.maxbackups,
        timezone: item.timezone,
        schedule: item.schedule
      }
      if (item.quiescevm) {
        params.quiescevm = item.quiescevm
      }
      return new Promise((resolve, reject) => {
        postAPI('createBackupSchedule', params).then(response => {
          resolve(response)
        }).catch(error => {
          this.$notification.error({
            message: this.$t('label.backup.schedule.create.failed'),
            description: error.message || error
          })
          reject(error)
        })
      })
    }
  }
}
</script>

<style lang="less" scoped>
.root-disk-configuration { padding-top: 8px; }
.root-configuration-section { margin-bottom: 20px; }
.root-section-heading { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; color: var(--ui-text-primary); margin: 0 0 12px; }
.root-section-number { display: inline-flex; align-items: center; justify-content: center; width: 21px; height: 21px; flex-shrink: 0; border: 1px solid var(--ui-border); border-radius: 50%; font-size: 12px; font-weight: normal; color: var(--ui-text-secondary); }
.root-default-value { display: flex; align-items: center; gap: 12px; padding: 10px 12px; margin-bottom: 12px; background: var(--ui-bg-elevated); border: 1px solid var(--ui-border); border-radius: 3px; }
.root-default-value .ant-tag { margin: 0; }
.root-offering-selection { margin-top: 12px; }
.root-size-unit { margin-left: 8px; }
.root-fixed-size-tag { margin-left: 8px; }
.root-storage-section :deep(.storage-toolbar strong) { display: flex; align-items: center; gap: 8px; }
.root-storage-section :deep(.storage-toolbar strong)::before { content: '3'; display: inline-flex; align-items: center; justify-content: center; width: 21px; height: 21px; flex-shrink: 0; border: 1px solid var(--ui-border); border-radius: 50%; font-size: 12px; font-weight: normal; color: var(--ui-text-secondary); }
.option-help { margin: 8px 0 0; opacity: .75; font-size: 12px; line-height: 1.6; }

  @media (min-width: 992px) {
    .deploy-vm-layout--bounded {
      height: var(--deploy-viewport-height);
      min-height: 0;

      > .ant-row,
      .deploy-vm-form-column,
      .deploy-vm-summary-column,
      .vm-info-card {
        height: 100%;
        min-height: 0;
      }

      .deploy-vm-form-column > .ant-card,
      .vm-info-card :deep(.spin-content) {
        display: flex;
        flex-direction: column;
        height: 100%;
        min-height: 0;
      }

      .deploy-vm-form-column > .ant-card > :deep(.ant-card-head),
      .vm-info-card :deep(.spin-content > .ant-card-head) {
        flex-shrink: 0;
      }

      .deploy-vm-form-column > .ant-card > :deep(.ant-card-body),
      .vm-info-card :deep(.spin-content > .ant-card-body) {
        flex: 1;
        min-height: 0;
      }

      .deploy-vm-form-column > .ant-card > :deep(.ant-card-body) {
        display: flex;
        flex-direction: column;
      }

      .deploy-vm-form-scroll {
        flex: 1;
        min-height: 0;
        overflow-y: auto;
        overscroll-behavior-y: contain;
        scrollbar-width: none;

        &::-webkit-scrollbar {
          display: none;
        }
      }

      .vm-info-card {
        > :deep(.ant-spin-nested-loading),
        > :deep(.ant-spin-nested-loading > .ant-spin-container) {
          height: 100%;
          min-height: 0;
        }

        :deep(.ant-card-body) {
          overflow: hidden;
        }

        :deep(.card-content) {
          flex: 1;
          min-height: 0;
          overflow-y: auto;
          overscroll-behavior-y: contain;
        }

        :deep(.card-footer) {
          flex-shrink: 0;
        }
      }
    }
  }

  .card-footer {
    text-align: right;
    margin-top: 2rem;
  }

  .ant-list-item-meta-avatar {
    font-size: 1rem;
  }

  .ant-collapse {
    margin: 2rem 0;
  }
</style>

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

  .vm-info-card {
    .resource-detail-item__label {
      font-weight: normal;
    }

    .resource-detail-item__details, .resource-detail-item {
      a {
        color: rgba(0, 0, 0, 0.65);
        cursor: default;
        pointer-events: none;
      }
    }
  }

  // Keep invalid input text on the same dark surface as valid inputs.
  .dark-mode .deploy-vm-layout .ant-form-item-has-error {
    .ant-input,
    .ant-input:hover,
    .ant-input-affix-wrapper,
    .ant-input-affix-wrapper:hover,
    .ant-select:not(.ant-select-customize-input) .ant-select-selector {
      // Override the shared dark theme error background.
      background-color: transparent !important;
    }
  }

  .form-item-hidden {
    display: none;
  }
</style>
