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
  <a-row :gutter="12" v-if="isPageAllowed">
    <a-col :span="24" class="content-breadcrumb">
      <a-card class="breadcrumb-card breadcrumb-toolbar">
      <a-row class="breadcrumb-toolbar-row">
        <a-col class="breadcrumb-toolbar-path">
          <breadcrumb :resource="resource">
            <template #end>
              <a-tooltip placement="bottom">
                <template #title>{{ $t('label.refresh') }}</template>
                <a-button
                  :loading="viewLoading"
                  shape="round"
                  size="small"
                  @click="fetchData()"
                >
                  <template #icon>
                    <ReloadOutlined />
                  </template>
                  {{ $t('label.refresh') }}
                </a-button>
              </a-tooltip>
            </template>
          </breadcrumb>
        </a-col>
      </a-row>
    </a-card>
    </a-col>
    <a-col
      :md="24">
      <div>
        <a-card>
          <a-alert
            class="manage-instances-alert"
            type="info"
            :showIcon="true"
            :message="wizardTitle"
          >
            <template #description>
              <span v-html="wizardDescription" />
            </template>
          </a-alert>
          <br />
          <a-row :gutter="12">
            <a-card class="source-dest-card">
              <a-col :md="24" :lg="48">
                <a-form
                  style="min-width: 170px"
                  :ref="formRef"
                  :model="form"
                  :rules="rules"
                  layout="vertical"
                >
                  <a-col :md="24" :lg="24">
                    <a-form-item name="sourcehypervisor" ref="sourcehypervisor" :label="$t('label.source')">
                      <a-radio-group
                        style="text-align: center; width: 100%"
                        v-model:value="form.sourceHypervisor"
                        @change="selected => { onSelectHypervisor(selected.target.value) }"
                        buttonStyle="solid">
                        <a-radio-button value="vmware" class="source-hypervisor-button">
                          {{ $t('label.vmware') }}
                        </a-radio-button>
                        <a-radio-button value="nutanix" class="source-hypervisor-button">
                          {{ $t('label.nutanix') }}
                        </a-radio-button>
                        <a-radio-button value="kvm" class="source-hypervisor-button">
                          {{ $t('label.app.name') }}
                        </a-radio-button>
                      </a-radio-group>
                    </a-form-item>
                    <a-form-item name="sourceaction" ref="sourceaction" :label="$t('label.action')" v-if="sourceActions">
                      <a-select
                        v-model:value="form.sourceAction"
                        showSearch
                        optionFilterProp="label"
                        :filterOption="(input, option) => {
                          return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                        }"
                        @change="value => onSelectSourceAction(value)"
                        :loading="optionLoading.sourcehypervisor"
                        v-focus="true"
                      >
                        <a-select-option v-for="opt in sourceActions" :key="opt.name" :label="opt.label">
                          <span>
                            {{ opt.label }}
                          </span>
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                  </a-col>
                    <a-col v-if="isExternal" :md="24" :lg="12">
                        <a-form-item
                                name="hostname"
                                ref="hostname">
                          <template #label>
                            <tooltip-label
                              :title="sourceEndpointLabel"
                              :tooltip="sourceEndpointTooltip"/>
                          </template>
                            <a-input
                                    v-model:value="form.hostname"
                            ></a-input>
                        </a-form-item>
                    </a-col>
                    <a-col v-if="isExternal" :md="24" :lg="12">
                        <a-form-item
                                name="username"
                                ref="username">
                          <template #label>
                            <tooltip-label
                              :title="$t('label.username')"
                              :tooltip="$t('label.username.tooltip')"/>
                          </template>
                            <a-input
                                    v-model:value="form.username"
                            ></a-input>
                        </a-form-item>
                    </a-col>
                    <a-col v-if="isExternal" :md="24" :lg="12">
                        <a-form-item
                                name="password"
                                ref="password">
                          <template #label>
                            <tooltip-label
                              :title="$t('label.password')"
                              :tooltip="$t('label.password.tooltip')"/>
                          </template>
                            <a-input-password
                                    v-model:value="form.password"
                            ></a-input-password>
                        </a-form-item>
                    </a-col>
                    <a-col v-if="showExtTmpPath" :md="24" :lg="12">
                        <a-form-item
                                name="tmppath"
                                ref="tmppath">
                          <template #label>
                            <tooltip-label
                              :title="$t('label.tmppath')"
                              :tooltip="$t('label.tmppath.tooltip')"/>
                          </template>
                            <a-input
                                    v-model:value="form.tmppath"
                            ></a-input>
                        </a-form-item>
                    </a-col>
                </a-form>
              </a-col>
            </a-card>
            <!-- ------------ -->
            <!-- RIGHT COLUMN -->
            <!-- ------------ -->
            <a-card class="source-dest-card">
              <template #title>
                {{ $t('label.destination') }}
              </template>
              <a-col :md="24" :lg="48">
                <a-form
                  style="min-width: 170px"
                  :ref="formRef"
                  :model="form"
                  :rules="rules"
                  layout="vertical"
                >
                <a-form-item v-if="showPool" name="scope" ref="scope">
                  <template #label>
                    <tooltip-label :title="$t('label.scope')" :tooltip="$t('label.scope.tooltip')"/>
                  </template>
                  <a-select
                    v-model:value="this.poolscope"
                    @change="onSelectPoolScope"
                    showSearch
                    optionFilterProp="label"
                    :filterOption="(input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }" >
                    <a-select-option :value="'cluster'" :label="$t('label.clusterid')"> {{ $t('label.clusterid') }} </a-select-option>
                    <a-select-option :value="'zone'" :label="$t('label.zoneid')"> {{ $t('label.zoneid') }} </a-select-option>
                  </a-select>
                </a-form-item>
                  <a-form-item
                    name="zoneid"
                    ref="zoneid"
                    :label="isMigrateFromVmware ? $t('label.destination.zone') : $t('label.zoneid')"
                  >
                    <a-select
                      v-model:value="form.zoneid"
                      showSearch
                      optionFilterProp="label"
                      :filterOption="(input, option) => {
                        return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                      }"
                      @change="value => onSelectZoneId(value)"
                      :loading="optionLoading.zones"
                    >
                      <a-select-option v-for="zoneitem in zoneSelectOptions" :key="zoneitem.value" :label="zoneitem.label">
                        <span>
                          <resource-icon v-if="zoneitem.icon" :image="zoneitem.icon" size="1x" style="margin-right: 5px"/>
                          <global-outlined v-else style="margin-right: 5px" />
                          {{ zoneitem.label }}
                        </span>
                      </a-select-option>
                    </a-select>
                  </a-form-item>
                  <a-form-item
                    v-if="showPod"
                    name="podid"
                    ref="podid"
                    :label="isMigrateFromVmware ? $t('label.destination.pod') : $t('label.podid')">
                    <a-select
                      v-model:value="form.podid"
                      showSearch
                      optionFilterProp="label"
                      :filterOption="filterOption"
                      :options="podSelectOptions"
                      :loading="optionLoading.pods"
                      @change="value => onSelectPodId(value)"
                    ></a-select>
                  </a-form-item>
                  <a-form-item
                    v-if="showCluster"
                    name="clusterid"
                    ref="clusterid"
                    :label="isMigrateFromVmware ? $t('label.destination.cluster') : $t('label.clusterid')">
                    <a-select
                      v-model:value="form.clusterid"
                      showSearch
                      optionFilterProp="label"
                      :filterOption="filterOption"
                      :options="clusterSelectOptions"
                      :loading="optionLoading.clusters"
                      @change="value => onSelectClusterId(value)"
                    ></a-select>
                  </a-form-item>
                  <a-form-item v-if="isDestinationKVM && isMigrateFromVmware && clusterId != undefined">
                    <SelectVmwareVcenter
                      :zoneid="zoneId"
                      :clusterid="clusterId"
                      :useAblestackV2KInventory="true"
                      @onVcenterTypeChanged="updateVmwareVcenterType"
                      @loadingVmwareUnmanagedInstances="() => this.unmanagedInstancesLoading = true"
                      @listedVmwareUnmanagedInstances="($e) => onListUnmanagedInstancesFromVmware($e)"
                    />
                  </a-form-item>
                  <template v-if="isDestinationKVM && isMigrateFromNutanix && clusterId != undefined">
                    <a-form-item
                      name="hostname"
                      ref="hostname">
                      <template #label>
                        <tooltip-label
                          :title="sourceEndpointLabel"
                          :tooltip="sourceEndpointTooltip"/>
                      </template>
                      <a-input v-model:value="form.hostname" />
                    </a-form-item>
                    <a-form-item
                      name="username"
                      ref="username">
                      <template #label>
                        <tooltip-label
                          :title="$t('label.username')"
                          :tooltip="$t('label.username.tooltip')"/>
                      </template>
                      <a-input v-model:value="form.username" />
                    </a-form-item>
                    <a-form-item
                      name="password"
                      ref="password">
                      <template #label>
                        <tooltip-label
                          :title="$t('label.password')"
                          :tooltip="$t('label.password.tooltip')"/>
                      </template>
                      <a-input-password v-model:value="form.password" />
                    </a-form-item>
                    <a-form-item name="sourceapi" ref="sourceapi">
                      <template #label>
                        <tooltip-label
                          :title="$t('label.source.api')"
                          :tooltip="$t('message.nutanix.source.api.tooltip')"/>
                      </template>
                      <a-select v-model:value="form.sourceapi">
                        <a-select-option value="v3" :label="$t('label.nutanix.api.v3')">
                          {{ $t('label.nutanix.api.v3') }}
                        </a-select-option>
                        <a-select-option value="auto" :label="$t('label.auto')">
                          {{ $t('label.auto') }}
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                    <a-form-item name="insecure" ref="insecure">
                      <template #label>
                        <tooltip-label
                          :title="$t('label.skip.tls.verify')"
                          :tooltip="$t('message.skip.tls.verify.nutanix')"/>
                      </template>
                      <a-switch v-model:checked="form.insecure" />
                    </a-form-item>
                    <div class="card-footer">
                      <a-button
                        :loading="unmanagedInstancesLoading"
                        type="primary"
                        @click="() => { fetchUnmanagedInstances() }">
                        {{ sourceFetchButtonLabel }}
                      </a-button>
                    </div>
                  </template>
                  <a-form-item
                    v-if="showHost"
                    name="hostid"
                    ref="hostid">
                    <template #label>
                      <tooltip-label
                        :title="$t('label.hostname')"
                        :tooltip="$t('label.hostname.tooltip')"/>
                    </template>
                      <a-select
                        v-model:value="form.hostid"
                        showSearch
                        optionFilterProp="label"
                        :filterOption="filterOption"
                        :options="hostSelectOptions"
                        :loading="optionLoading.hosts"
                        @change="value => onSelectHostId(value)"
                      ></a-select>
                  </a-form-item>
                  <a-form-item
                    v-if="isDiskImport"
                    name="poolid"
                    ref="poolid">
                    <template #label>
                      <tooltip-label
                        :title="$t('label.storagepool')"
                        :tooltip="$t('label.storagepool.tooltip')"/>
                    </template>
                      <a-select
                        v-model:value="form.poolid"
                        showSearch
                        optionFilterProp="label"
                        :filterOption="filterOption"
                        :options="poolSelectOptions"
                        :loading="optionLoading.pools"
                        @change="onSelectPoolId"
                      ></a-select>
                  </a-form-item>
                  <a-form-item
                    v-if="showDiskPath"
                    name="diskpath"
                    ref="diskpath">
                    <template #label>
                          <tooltip-label
                            :title="$t('label.disk')"
                            :tooltip="$t('label.disk.tooltip')"/>
                    </template>
                    <a-input
                      v-model:value="form.diskpath"
                    ></a-input>
                  </a-form-item>
                  <a-col v-if="showDiskPath" :md="24" :lg="8">
                    <a-button
                        type="primary"
                        @click="onImportInstanceAction">
                      <template #icon><import-outlined /></template>
                      {{ $t('label.import.instance') }}
                    </a-button>
                  </a-col>
                </a-form>
              </a-col>
            </a-card>
          </a-row>
          <a-row v-if="isExternal">
            <a-col class="fetch-instances-column">
              <div>
                <a-button
                  shape="round"
                  type="primary"
                  @click="() => { fetchUnmanagedInstances() }">
                  {{ sourceFetchButtonLabel }}
                </a-button>
              </div>
            </a-col>
          </a-row>
          <a-divider />

          <a-tabs v-model:activeKey="activeTabKey" @change="onTabChange">
            <a-tab-pane :key=1 :tab="$t('label.instances.listing')">
              <a-row :gutter="12">
                <a-col v-if="!isDiskImport" :md="24" :lg="(!isMigrateFromVmware && showManagedInstances) ? 12 : 24">
                  <a-card class="instances-card">
                    <template #title>
                      {{ (isMigrateFromVmware && vmwareVcenterType === 'existing') ? $t('label.instances') : $t('label.unmanaged.instances') }}
                      <a-tooltip :title="(isMigrateFromVmware && vmwareVcenterType === 'existing') ? $t('message.instances.migrate.vmware') : $t('message.instances.unmanaged')">
                        <info-circle-outlined />
                      </a-tooltip>
                      <a-button
                        style="margin-left: 12px; margin-top: 4px"
                        :loading="unmanagedInstancesLoading"
                        size="small"
                        shape="round"
                        @click="fetchUnmanagedInstances()" >
                        <template #icon><reload-outlined /></template>
                      </a-button>
                      <span style="float: right; width: 50%">
                        <search-view
                          :searchFilters="searchFilters.unmanaged"
                          :searchParams="searchParams.unmanaged"
                          :apiName="listInstancesApi.unmanaged"
                          @search="searchUnmanagedInstances"
                        />
                      </span>
                    </template>
                    <a-table
                      v-if="!isExternal"
                      class="instances-card-table"
                      :loading="unmanagedInstancesLoading"
                      :custom-row="unmanagedRow"
                      :rowKey="(record, index) => index"
                      :columns="unmanagedInstancesColumns"
                      :data-source="unmanagedInstances"
                      :pagination="false"
                      size="middle"
                      :rowClassName="getRowClassName"
                    >
                      <template #bodyCell="{ column, text, record }">
                        <template v-if="column.key === 'state'">
                          <status :text="text ? text : ''" displayText />
                        </template>
                        <template v-else-if="['hostname', 'clustername', 'osdisplayname'].includes(column.key)">
                          {{ getUnmanagedInstanceCellText(record, column.key, text) }}
                        </template>
                      </template>
                    </a-table>
                    <a-table
                      v-if="isExternal"
                      class="instances-card-table"
                      :loading="unmanagedInstancesLoading"
                      :custom-row="unmanagedRow"
                      :rowKey="(record, index) => index"
                      :columns="externalInstancesColumns"
                      :data-source="unmanagedInstances"
                      :pagination="false"
                      size="middle"
                      :rowClassName="getRowClassName"
                    >
                      <template #bodyCell="{ column, text }">
                          <template v-if="column.key === 'state'">
                              <status :text="text ? text : ''" displayText />
                          </template>
                      </template>
                    </a-table>
                    <div class="instances-card-footer">
                      <a-pagination
                        class="row-element"
                        size="small"
                        :current="page.unmanaged"
                        :pageSize="pageSize.unmanaged"
                        :total="itemCount.unmanaged"
                        :showTotal="total => `${$t('label.showing')} ${Math.min(total, 1+((page.unmanaged-1)*pageSize.unmanaged))}-${Math.min(page.unmanaged*pageSize.unmanaged, total)} ${$t('label.of')} ${total} ${$t('label.items')}`"
                        @change="fetchUnmanagedInstances"
                        showQuickJumper>
                        <template #buildOptionText="props">
                          <span>{{ props.value }} / {{ $t('label.page') }}</span>
                        </template>
                      </a-pagination>
                      <div :span="24" class="action-button-right">
                        <a-button
                          :loading="importUnmanagedInstanceLoading"
                          :disabled="!(('importUnmanagedInstance' in $store.getters.apis) && unmanagedInstancesSelectedRowKeys.length > 0)"
                          type="primary"
                          @click="onManageInstanceAction">
                          <template #icon><import-outlined /></template>
                          {{ $t('label.import.instance') }}
                        </a-button>
                      </div>
                    </div>
                  </a-card>
                </a-col>
                <a-col :md="24" :lg="12" v-if="!isMigrateFromVmware && showManagedInstances">
                  <a-card class="instances-card">
                    <template #title>
                      {{ $t('label.managed.instances') }}
                      <a-tooltip :title="$t('message.instances.managed')">
                        <info-circle-outlined />
                      </a-tooltip>
                      <a-button
                        style="margin-left: 12px; margin-top: 4px"
                        :loading="managedInstancesLoading"
                        size="small"
                        shape="round"
                        @click="fetchManagedInstances()" >
                        <template #icon><reload-outlined /></template>
                      </a-button>
                      <span style="float: right; width: 50%">
                        <search-view
                          :searchFilters="searchFilters.managed"
                          :searchParams="searchParams.managed"
                          :apiName="listInstancesApi.managed"
                          @search="searchManagedInstances"
                        />
                      </span>
                    </template>
                    <a-table
                      class="instances-card-table"
                      :loading="managedInstancesLoading"
                      :custom-row="managedRow"
                      :rowKey="(record, index) => index"
                      :columns="managedInstancesColumns"
                      :data-source="managedInstances"
                      :pagination="false"
                      size="middle"
                      :rowClassName="getRowClassName"
                    >
                      <template #bodyCell="{ column, text, record }">
                        <template v-if="column.key === 'name'">
                          <router-link :to="{ path: '/vm/' + record.id }">{{ text }}</router-link>
                        </template>
                        <template v-if="column.key === 'state'">
                          <status :text="text ? text : ''" displayText />
                        </template>
                      </template>
                    </a-table>
                    <div class="instances-card-footer">
                      <a-pagination
                        class="row-element"
                        size="small"
                        :current="page.managed"
                        :pageSize="pageSize.managed"
                        :total="itemCount.managed"
                        :showTotal="total => `${$t('label.showing')} ${Math.min(total, 1+((page.managed-1)*pageSize.managed))}-${Math.min(page.managed*pageSize.managed, total)} ${$t('label.of')} ${total} ${$t('label.items')}`"
                        @change="fetchManagedInstances"
                        showQuickJumper>
                        <template #buildOptionText="props">
                          <span>{{ props.value }} / {{ $t('label.page') }}</span>
                        </template>
                      </a-pagination>
                      <div :span="24" class="action-button-right">
                        <a-button

                          :disabled="!(('unmanageVirtualMachine' in $store.getters.apis) && managedInstancesSelectedRowKeys.length > 0)"
                          type="primary"
                          @click="onUnmanageInstanceAction">
                          <template #icon><disconnect-outlined /></template>
                          {{ managedInstancesSelectedRowKeys.length > 1 ? $t('label.action.unmanage.instances') : $t('label.action.unmanage.instance') }}
                        </a-button>
                      </div>
                    </div>
                  </a-card>
                </a-col>
              </a-row>
            </a-tab-pane>
            <a-tab-pane :key=2 :tab="$t('label.import.vm.tasks')" v-if="isAblestackCloudMigration">
              <ImportVmTasks
                :tasks="importVmTasks"
                :loading="loadingImportVmTasks"
                :filter="importVmTasksFilter"
                :total="itemCount.tasks || 0"
                :page="page.tasks"
                :pageSize="pageSize.tasks"
                @fetch-import-vm-tasks="fetchImportVmTasks"
                @change-pagination="onChangeImportTasksPagination"
                @change-filter="onChangeImportTasksFilter"
                @start-phase2="onStartPhase2"
              />
            </a-tab-pane>
          </a-tabs>

        </a-card>

        <a-modal
          v-if="showUnmanageForm"
          :visible="showUnmanageForm"
          :title="$t('label.import.instance')"
          :closable="true"
          :maskClosable="false"
          :footer="null"
          :cancelText="$t('label.cancel')"
          @cancel="showUnmanageForm = false"
          centered
          ref="importModal"
          width="auto">
          <import-unmanaged-instances
            class="importform"
            :resource="selectedUnmanagedInstance"
            :cluster="selectedCluster"
            :host="selectedHost"
            :pool="selectedPool"
            :importsource="selectedSourceAction"
            :zoneid="this.zoneId"
            :hypervisor="this.destinationHypervisor"
            :exthost="this.values?.hostname || ''"
            :username="this.values?.username || ''"
            :password="this.values?.password || ''"
            :tmppath="this.values?.tmppath || ''"
            :diskpath="this.values?.diskpath || ''"
            :isOpen="showUnmanageForm"
            :loadingGuestOsMappings="loadingGuestOsMappings"
            :selectedVmwareVcenter="selectedVmwareVcenter"
            :sourceprovider="selectedSourceProvider"
            :sourceapi="values?.sourceapi || form.sourceapi"
            :insecure="values?.insecure ?? form.insecure"
            @refresh-data="fetchInstances"
            @close-action="closeImportUnmanagedInstanceForm"
            @loading-changed="updateManageInstanceActionLoading"
            @track-import-jobid="trackImportJobId"
          />
        </a-modal>
      </div>
    </a-col>
  </a-row>
</template>

<script>
import { selectableImportRow } from '@/utils/moldListSelection'
import { restoreImportOptions } from '@/utils/importOptionNavigation'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { ref, reactive, toRaw } from 'vue'
import { postAPI, getAPI } from '@/api'
import _ from 'lodash'
import Breadcrumb from '@/components/widgets/Breadcrumb'
import Status from '@/components/widgets/Status'
import SearchView from '@/components/view/SearchView'
import ImportUnmanagedInstances from '@/views/tools/ImportUnmanagedInstance'
import ResourceIcon from '@/components/view/ResourceIcon'
import SelectVmwareVcenter from '@/views/tools/SelectVmwareVcenter'
import TooltipLabel from '@/components/widgets/TooltipLabel.vue'
import ImportVmTasks from '@/views/tools/ImportVmTasks.vue'

export default {
  mixins: [listRefreshMixin(['fetchImportVmTasks'], { active: vm => String(vm.activeTabKey) === '2' && !!vm.zoneId && vm.isAblestackCloudMigration })],
  components: {
    TooltipLabel,
    Breadcrumb,
    Status,
    SearchView,
    ImportUnmanagedInstances,
    ResourceIcon,
    SelectVmwareVcenter,
    ImportVmTasks
  },
  name: 'ManageVms',
  data () {
    const AllSourceActions = [
      {
        name: 'unmanaged',
        label: this.$t('label.manage.unmanage.instances'),
        sourceDestHypervisors: {
          vmware: 'vmware',
          kvm: 'kvm'
        },
        wizardTitle: this.$t('label.desc.importexportinstancewizard'),
        wizardDescription: this.$t('message.desc.importexportinstancewizard')
      },
      {
        name: 'vmware',
        label: this.$t('label.desc.importmigratefromvmwarewizard'),
        sourceDestHypervisors: {
          vmware: 'kvm'
        },
        wizardTitle: this.$t('label.desc.importmigratefromvmwarewizard'),
        wizardDescription: this.$t('message.desc.importmigratefromvmwarewizard')
      },
      {
        name: 'nutanix',
        label: this.$t('label.desc.importmigratefromnutanixwizard'),
        sourceDestHypervisors: {
          nutanix: 'kvm'
        },
        wizardTitle: this.$t('label.desc.importmigratefromnutanixwizard'),
        wizardDescription: this.$t('message.desc.importmigratefromnutanixwizard')
      },
      {
        name: 'external',
        label: this.$t('label.desc.import.ext.kvm.wizard'),
        sourceDestHypervisors: {
          kvm: 'kvm'
        },
        wizardTitle: this.$t('label.desc.import.ext.kvm.wizard'),
        wizardDescription: this.$t('message.desc.import.ext.kvm.wizard')
      },
      {
        name: 'local',
        label: this.$t('label.desc.import.local.kvm.wizard'),
        sourceDestHypervisors: {
          kvm: 'kvm'
        },
        wizardTitle: this.$t('label.desc.import.local.kvm.wizard'),
        wizardDescription: this.$t('message.desc.import.local.kvm.wizard')
      },
      {
        name: 'shared',
        label: this.$t('label.desc.import.shared.kvm.wizard'),
        sourceDestHypervisors: {
          kvm: 'kvm'
        },
        wizardTitle: this.$t('label.desc.import.shared.kvm.wizard'),
        wizardDescription: this.$t('message.desc.import.shared.kvm.wizard')
      }
    ]
    const unmanagedInstancesColumns = [
      {
        title: this.$t('label.name'),
        dataIndex: 'name',
        width: 100
      },
      {
        key: 'state',
        title: this.$t('label.state'),
        dataIndex: 'powerstate'
      },
      {
        key: 'hostname',
        title: this.$t('label.hostname'),
        dataIndex: 'hostname'
      },
      {
        key: 'clustername',
        title: this.$t('label.clustername'),
        dataIndex: 'clustername'
      },
      {
        key: 'osdisplayname',
        title: this.$t('label.ostypename'),
        dataIndex: 'osdisplayname'
      }
    ]
    const externalInstancesColumns = [
      {
        title: this.$t('label.name'),
        dataIndex: 'name',
        width: 200
      },
      {
        key: 'state',
        title: this.$t('label.state'),
        dataIndex: 'powerstate'
      }
    ]
    const managedInstancesColumns = [
      {
        key: 'name',
        title: this.$t('label.name'),
        dataIndex: 'name',
        width: 100
      },
      {
        title: this.$t('label.instancename'),
        dataIndex: 'instancename'
      },
      {
        key: 'state',
        title: this.$t('label.state'),
        dataIndex: 'state'
      },
      {
        title: this.$t('label.hostname'),
        dataIndex: 'hostname'
      },
      {
        title: this.$t('label.templatename'),
        dataIndex: 'templatedisplaytext'
      }
    ]
    return {
      options: {
        hypervisors: [],
        zones: [],
        pods: [],
        clusters: [],
        hosts: [],
        pools: []
      },
      rowCount: {},
      optionLoading: {
        sourceaction: false,
        hypervisors: false,
        zones: false,
        pods: false,
        clusters: false,
        hosts: false,
        pools: false
      },
      page: {
        unmanaged: 1,
        managed: 1,
        tasks: 1
      },
      pageSize: {
        unmanaged: 10,
        managed: 10,
        tasks: 10
      },
      searchFilters: {
        unmanaged: [],
        managed: []
      },
      searchParams: {
        unmanaged: {},
        managed: {}
      },
      itemCount: {},
      hypervisors: [],
      sourceHypervisor: 'vmware',
      destinationHypervisor: 'kvm',
      sourceActions: undefined,
      selectedSourceAction: 'vmware',
      wizardTitle: this.$t('label.desc.importmigratefromvmwarewizard'),
      wizardDescription: this.$t('message.desc.importmigratefromvmwarewizard'),
      zone: {},
      pod: {},
      cluster: {},
      values: undefined,
      zoneId: undefined,
      podId: undefined,
      clusterId: undefined,
      hostname: undefined,
      username: undefined,
      password: undefined,
      hostId: undefined,
      poolId: undefined,
      diskpath: undefined,
      tmppath: undefined,
      poolscope: 'cluster',
      listInstancesApi: {
        unmanaged: 'listUnmanagedInstances',
        managed: 'listVirtualMachines',
        migratefromvmware: 'listVmsForImport',
        external: 'listVmsForImport'
      },
      unmanagedInstancesColumns,
      externalInstancesColumns,
      AllSourceActions,
      unmanagedInstancesLoading: false,
      unmanagedInstances: [],
      unmanagedInstancesSelectedRowKeys: [],
      importUnmanagedInstanceLoading: false,
      managedInstancesColumns,
      managedInstancesLoading: false,
      managedInstances: [],
      managedInstancesSelectedRowKeys: [],
      showUnmanageForm: false,
      selectedUnmanagedInstance: {},
      initialOptionsPending: false,
      query: {},
      vmwareVcenterType: undefined,
      selectedVmwareVcenter: undefined,
      activeTabKey: 1,
      loadingImportVmTasks: false,
      importVmTasks: [],
      selectedImportVmTask: null,
      importVmTasksFilter: 'all',
      importVmTasksAutoRefreshTimer: null,
      loadingGuestOsMappings: false
    }
  },
  created () {
    this.page.unmanaged = parseInt(this.$route.query.unmanagedpage || 1)
    this.page.managed = parseInt(this.$route.query.managedpage || 1)
    this.page.tasks = parseInt(this.$route.query.tasks || 1)
    this.initForm()
    this.fetchData()
  },
  beforeUnmount () {
    this.stopImportVmTasksAutoRefresh()
  },
  computed: {
    isPageAllowed () {
      if (this.$route.meta.permission) {
        for (var apiName of this.$route.meta.permission) {
          if (!(apiName in this.$store.getters.apis)) {
            return false
          }
        }
      }
      return true
    },
    isUnmanaged () {
      return this.selectedSourceAction === 'unmanaged'
    },
    isExternal () {
      return this.selectedSourceAction === 'external'
    },
    isMigrateFromVmware () {
      return this.selectedSourceAction === 'vmware'
    },
    isMigrateFromNutanix () {
      return this.selectedSourceAction === 'nutanix'
    },
    isAblestackCloudMigration () {
      return this.isMigrateFromVmware || this.isMigrateFromNutanix
    },
    selectedSourceProvider () {
      if (this.isMigrateFromVmware) {
        return 'vmware'
      }
      if (this.isMigrateFromNutanix) {
        return 'nutanix'
      }
      return this.selectedSourceAction
    },
    sourceEndpointLabel () {
      return this.isMigrateFromNutanix ? this.$t('label.nutanix.prism.endpoint') : this.$t('label.hostname')
    },
    sourceEndpointTooltip () {
      return this.isMigrateFromNutanix ? this.$t('message.nutanix.prism.endpoint.tooltip') : this.$t('label.ext.hostname.tooltip')
    },
    sourceFetchButtonLabel () {
      return this.isMigrateFromNutanix ? this.$t('label.fetch.source.vms') : this.$t('label.fetch.instances')
    },
    isDestinationKVM () {
      return this.destinationHypervisor === 'kvm'
    },
    showPod () {
      if (this.selectedSourceAction === 'shared') {
        return this.poolscope !== 'zone'
      }
      return (this.selectedSourceAction !== 'external')
    },
    showCluster () {
      if (this.selectedSourceAction === 'shared') {
        return this.poolscope !== 'zone'
      }
      return (this.selectedSourceAction !== 'external')
    },
    showHost () {
      return (this.selectedSourceAction === 'local')
    },
    showPool () {
      return (this.selectedSourceAction === 'shared')
    },
    showExtHost () {
      return (this.selectedSourceAction === 'external' || this.isMigrateFromNutanix)
    },
    showExtTmpPath () {
      return this.selectedSourceAction === 'external'
    },
    showDiskPath () {
      return ((this.selectedSourceAction === 'local') || (this.selectedSourceAction === 'shared'))
    },
    showManagedInstances () {
      return !this.isAblestackCloudMigration &&
        ((this.selectedSourceAction !== 'local') && (this.selectedSourceAction !== 'shared') && (this.selectedSourceAction !== 'external'))
    },
    isDiskImport () {
      return ((this.selectedSourceAction === 'local') || (this.selectedSourceAction === 'shared'))
    },
    getPoolScope () {
      if (this.selectedSourceAction === 'local') {
        return 'host'
      } else {
        return this.poolscope
      }
    },
    params () {
      return {
        zones: {
          list: 'listZones',
          field: 'zoneid',
          options: {
            showicon: true
          }
        },
        pods: {
          list: 'listPods',
          options: {
            zoneid: _.get(this.zone, 'id')
          },
          field: 'podid'
        },
        clusters: {
          list: 'listClusters',
          options: {
            zoneid: _.get(this.zone, 'id'),
            podid: this.podId,
            hypervisor: this.destinationHypervisor,
            allocationstate: 'Enabled'
          },
          field: 'clusterid'
        },
        hosts: {
          list: 'listHosts',
          options: {
            zoneid: _.get(this.zone, 'id'),
            podid: this.podId,
            clusterid: this.clusterId
          },
          field: 'hostid'
        },
        pools: {
          list: 'listStoragePools',
          options: {
            zoneid: _.get(this.zone, 'id'),
            podid: this.podId,
            clusterid: this.clusterId,
            hostid: this.hostId,
            scope: this.getPoolScope
          },
          field: 'poolid'
        }
      }
    },
    viewLoading () {
      for (var key in this.optionLoading) {
        if (this.optionLoading[key]) {
          return true
        }
      }
      return this.unmanagedInstancesLoading || this.managedInstancesLoading
    },
    zoneSelectOptions () {
      return this.options.zones.map((zone) => {
        return {
          label: zone.name,
          value: zone.id,
          icon: zone?.icon?.base64image || ''
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
      return options
    },
    clusterSelectOptions () {
      const options = this.options.clusters.map((cluster) => {
        return {
          label: cluster.name,
          value: cluster.id
        }
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
      return options
    },
    poolSelectOptions () {
      const options = this.options.pools.map((pool) => {
        return {
          label: pool.name,
          value: pool.id
        }
      })
      return options
    },
    unmanagedInstanceSelection () {
      return {
        type: 'radio',
        selectedRowKeys: this.unmanagedInstancesSelectedRowKeys || [],
        onChange: this.onUnmanagedInstanceSelectRow
      }
    },
    managedInstanceSelection () {
      return {
        type: 'checkbox',
        selectedRowKeys: this.managedInstancesSelectedRowKeys || [],
        onChange: this.onManagedInstanceSelectRow
      }
    },
    selectedCluster () {
      if (this.options.clusters &&
        this.options.clusters.length > 0 &&
        this.clusterId) {
        return _.find(this.options.clusters, (option) => option.id === this.clusterId)
      }
      return {}
    },
    selectedHost () {
      if (this.options.hosts &&
          this.options.hosts.length > 0 &&
          this.hostId) {
        return _.find(this.options.hosts, (option) => option.id === this.hostId)
      }
      return {}
    },
    selectedPool () {
      if (this.options.pools &&
          this.options.pools.length > 0 &&
          this.poolId) {
        return _.find(this.options.pools, (option) => option.id === this.poolId)
      }
      return {}
    }
  },
  methods: {
    unmanagedRow (record, index) {
      return selectableImportRow(this.unmanagedInstanceSelection, this.unmanagedInstances, index)
    },
    managedRow (record, index) {
      return selectableImportRow(this.managedInstanceSelection, this.managedInstances, index)
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        sourceHypervisor: this.sourceHypervisor,
        sourceapi: 'v3',
        insecure: true
      })
      this.rules = reactive({
        hostname: [{ required: true, message: this.$t('message.error.input.value') }],
        username: [{ required: true, message: this.$t('message.error.input.value') }],
        password: [{ required: true, message: this.$t('message.error.input.value') }]
      })
    },
    async fetchData () {
      if (this.initialOptionsPending) return
      this.initialOptionsPending = true
      this.unmanagedInstances = []
      this.managedInstances = []
      try {
        await restoreImportOptions(this.$router, initial => this.onSelectHypervisor(this.sourceHypervisor, initial))
      } finally {
        this.initialOptionsPending = false
      }
    },
    filterOption (input, option) {
      return (
        option.label.toUpperCase().indexOf(input.toUpperCase()) >= 0
      )
    },
    getUnmanagedInstanceCellText (record, key, text) {
      if (text) {
        return text
      }
      if (key === 'osdisplayname') {
        return record?.operatingsystem || record?.ostypename || record?.guestosname || '-'
      }
      if (key === 'clustername') {
        return record?.cluster || record?.clusterid || '-'
      }
      if (key === 'hostname') {
        return record?.host || record?.hostid || '-'
      }
      return '-'
    },
    fetchOptions (param, name, exclude, initial) {
      if (exclude?.includes(name)) return
      this.optionLoading[name] = true
      const params = { ...param.options }
      return getAPI(param.list, params).then(response => {
        if (initial && this.$router.currentRoute.value !== initial.route) return
        const body = Object.entries(response).find(([key]) => key.includes('response'))?.[1]
        const options = Object.values(body || {}).find(Array.isArray) || []
        this.rowCount[name] = body?.count ?? options.length
        this.options[name] = options
        return this.handleFetchOptionsSuccess(name, param, initial)
      }).catch(error => {
        console.log(error.stack)
      }).finally(() => {
        this.optionLoading[name] = false
      })
    },
    getRowClassName (record, index) {
      if (index % 2 === 0) {
        return 'light-row'
      }
      return 'dark-row'
    },
    handleFetchOptionsSuccess (name, param, initial) {
      if (['zones', 'pods', 'clusters', 'hosts', 'pools'].includes(name)) {
        let paramid = ''
        const query = initial?.query || { ...this.$route.query }
        if (query[param.field] && _.find(this.options[name], (option) => option.id === query[param.field])) {
          paramid = query[param.field]
        }
        if (!paramid && this.options[name].length > 0) {
          paramid = (this.options[name])[0].id
        }
        if (paramid) {
          this.form[param.field] = paramid
          if (name === 'zones') {
            return this.onSelectZoneId(paramid, initial)
          } else if (name === 'pods') {
            this.form.podid = paramid
            return this.onSelectPodId(paramid, initial)
          } else if (name === 'clusters') {
            this.form.clusterid = paramid
            return this.onSelectClusterId(paramid, initial)
          } else if (name === 'hosts') {
            this.form.hostid = paramid
            return this.onSelectHostId(paramid, initial)
          } else if (name === 'pools') {
            this.form.poolid = paramid
            return this.onSelectPoolId(paramid, initial)
          }
        }
      }
    },
    updateQuery (field, value, initial) {
      const query = initial?.query || { ...this.$route.query }
      if (query[field] === value + '') {
        return
      }
      query[field] = value
      if (['zoneid', 'podid', 'clusterid'].includes(field)) {
        query.managedpage = 1
        query.unmanagedpage = 1
      }
      if (!initial) this.$router.push({ query })
    },
    resetLists () {
      this.page.unmanaged = 1
      this.unmanagedInstances = []
      this.unmanagedInstancesSelectedRowKeys = []
      this.page.managed = 1
      this.managedInstances = []
      this.managedInstancesSelectedRowKeys = []
      this.page.tasks = 1
      this.activeTabKey = 1
      this.stopImportVmTasksAutoRefresh()
    },
    getSourceActionsForHypervisor (hypervisor) {
      return this.AllSourceActions.filter((action) => {
        if (!action.sourceDestHypervisors[hypervisor]) {
          return false
        }
        return true
      })
    },
    getDefaultSourceActionForHypervisor (hypervisor, sourceActions) {
      const preferredActionByHypervisor = {
        vmware: 'vmware'
      }
      const preferredAction = preferredActionByHypervisor[hypervisor]
      if (preferredAction && _.find(sourceActions, (action) => action.name === preferredAction)) {
        return preferredAction
      }
      return sourceActions[0]?.name || ''
    },
    onSelectHypervisor (value, initial) {
      this.sourceHypervisor = value
      this.sourceActions = this.getSourceActionsForHypervisor(value)
      this.form.sourceAction = this.getDefaultSourceActionForHypervisor(value, this.sourceActions)
      this.selectedVmwareVcenter = undefined
      if (this.form.sourceAction) {
        return this.onSelectSourceAction(this.form.sourceAction, initial)
      } else {
        this.resetLists()
      }
    },
    onSelectSourceAction (value, initial) {
      this.selectedSourceAction = value
      const selectedAction = _.find(this.AllSourceActions, (option) => option.name === value)
      if (!selectedAction) {
        this.resetLists()
        return
      }
      this.destinationHypervisor = selectedAction.sourceDestHypervisors[this.sourceHypervisor]
      this.wizardTitle = selectedAction.wizardTitle
      this.wizardDescription = selectedAction.wizardDescription
      this.form.zoneid = undefined
      this.form.podid = undefined
      this.form.clusterid = undefined
      if (this.isMigrateFromNutanix) {
        this.form.sourceapi = this.form.sourceapi || 'v3'
        this.form.insecure = this.form.insecure !== false
      }
      this.resetLists()
      return this.fetchOptions(this.params.zones, 'zones', undefined, initial)
    },
    onSelectZoneId (value, initial) {
      this.zoneId = value
      this.podId = null
      this.clusterId = null
      this.hostId = null
      this.poolId = null
      this.zone = _.find(this.options.zones, (option) => option.id === value)
      this.resetLists()
      this.form.clusterid = undefined
      this.form.podid = undefined
      this.form.poolid = undefined
      this.updateQuery('zoneid', value, initial)
      return this.fetchOptions(this.params.pods, 'pods', undefined, initial)
    },
    onSelectPodId (value, initial) {
      this.podId = value
      this.pod = _.find(this.options.pods, (option) => option.id === value)
      this.resetLists()
      this.clusterId = null
      this.form.clusterid = undefined
      this.updateQuery('podid', value, initial)
      return this.fetchOptions(this.params.clusters, 'clusters', value, initial)
    },
    onSelectClusterId (value, initial) {
      this.clusterId = value
      this.cluster = _.find(this.options.clusters, (option) => option.id === value)
      this.resetLists()
      this.updateQuery('clusterid', value, initial)
      if (this.isUnmanaged) {
        if (initial) initial.read = () => this.fetchInstances()
        else this.fetchInstances()
      } else if (this.showHost) {
        return this.fetchOptions(this.params.hosts, 'hosts', value, initial)
      } else if (this.showPool) {
        return this.fetchOptions(this.params.pools, 'pools', value, initial)
      }
    },
    onSelectHostId (value, initial) {
      this.hostId = value
      this.updateQuery('scope', 'local', initial)
      return this.fetchOptions(this.params.pools, 'pools', value, initial)
    },
    onSelectPoolId (value) {
      this.poolId = value
    },
    onSelectPoolScope (value) {
      this.poolscope = value
      this.poolId = null
      this.updateQuery('scope', value)
      this.fetchOptions(this.params.pools, 'pools', value)
    },
    onTabChange (e) {
      if (String(e) === '2') {
        this.fetchImportVmTasks()
        this.startImportVmTasksAutoRefresh()
      } else {
        this.stopImportVmTasksAutoRefresh()
      }
    },
    startImportVmTasksAutoRefresh () {
      if (this.listRefreshController) this.listRefreshController.schedule()
    },
    stopImportVmTasksAutoRefresh () {
      if (this.importVmTasksAutoRefreshTimer) {
        window.clearInterval(this.importVmTasksAutoRefreshTimer)
        this.importVmTasksAutoRefreshTimer = null
      }
    },
    onChangeImportTasksPagination (page, pagesize) {
      this.page.tasks = page
      this.pageSize.tasks = pagesize
      this.fetchImportVmTasks()
    },
    onChangeImportTasksFilter (filter) {
      this.importVmTasksFilter = filter
      this.fetchImportVmTasks()
    },
    onStartPhase2 (task) {
      const taskRecord = task?.task || task
      const credential = task?.credential || {}
      this.selectedImportVmTask = taskRecord
      const isN2KTask = taskRecord.migrationtool === 'ablestack_n2k' || taskRecord.sourceprovider === 'nutanix'
      const importApi = isN2KTask ? 'importUnmanagedInstanceForAblestackN2K' : 'importUnmanagedInstanceForAblestackV2K'
      const responseKey = isN2KTask ? 'importunmanagedinstanceforablestackn2kresponse' : 'importunmanagedinstanceforablestackv2kresponse'
      const params = {
        importvmtaskid: taskRecord.id,
        split: 'phase2',
        zoneid: taskRecord.zoneid,
        clusterid: taskRecord.clusterid,
        serviceofferingid: taskRecord.serviceofferingid,
        name: taskRecord.sourcevmname,
        importsource: isN2KTask ? 'nutanix' : 'VMWARE',
        hypervisor: 'KVM'
      }
      if (credential.endpoint) {
        params.host = credential.endpoint
      }
      if (credential.username) {
        params.username = credential.username
      }
      if (credential.password) {
        params.password = credential.password
      }
      if (credential.sourceapi) {
        params.sourceapi = credential.sourceapi
      }
      if (credential.insecure !== undefined) {
        params.insecure = credential.insecure
      }
      if (credential.retentionseconds) {
        params.retentionseconds = credential.retentionseconds
      }
      if (credential.starttargetvm !== undefined) {
        params.starttargetvm = credential.starttargetvm
      }
      if (!isN2KTask && this.selectedVmwareVcenter) {
        if (this.selectedVmwareVcenter.existingvcenterid) {
          params.existingvcenterid = this.selectedVmwareVcenter.existingvcenterid
        } else {
          params.vcenter = this.selectedVmwareVcenter.vcenter
          params.datacentername = this.selectedVmwareVcenter.datacentername
          params.username = this.selectedVmwareVcenter.username
          params.password = this.selectedVmwareVcenter.password
        }
      }
      postAPI(importApi, params).then(response => {
        const jobId = response[responseKey].jobid
        if (isN2KTask) {
          this.finishN2KImportTaskBackgroundJob(jobId, this.$t('label.phase2.execute'), taskRecord.displayname || taskRecord.sourcevmname)
          this.fetchImportVmTasks()
          window.setTimeout(() => this.fetchImportVmTasks(), 3000)
          return
        }
        this.$pollJob({
          jobId,
          title: this.$t('label.phase2.execute'),
          description: taskRecord.displayname || taskRecord.sourcevmname,
          loadingMessage: `${taskRecord.displayname || taskRecord.sourcevmname} ${this.$t('label.in.progress')}`,
          catchMessage: this.$t('error.fetching.async.job.result'),
          successMessage: this.$t('label.phase2.execute'),
          successMethod: () => {
            this.fetchImportVmTasks()
          },
          errorMethod: (result) => {
            this.$notifyError(result?.jobresult?.errortext || result)
          }
        })
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    finishN2KImportTaskBackgroundJob (jobId, title, description) {
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
    fetchImportVmTasks () {
      const listRequest = this.listRequestToken('fetchImportVmTasks')
      this.loadingImportVmTasks = !listRequest.loaded
      const params = {
        zoneid: this.zoneId,
        page: this.page.tasks,
        pagesize: this.pageSize.tasks,
        tasksfilter: this.importVmTasksFilter,
        targetprovider: 'cloud'
      }
      if (this.isMigrateFromVmware) {
        params.sourceprovider = 'vmware'
        params.migrationtool = 'ablestack_v2k'
      } else if (this.isMigrateFromNutanix) {
        params.sourceprovider = 'nutanix'
        params.migrationtool = 'ablestack_n2k'
      }
      return getAPI('listImportVmTasks', params).then(response => {
        if (!this.isListRequestCurrent('fetchImportVmTasks', listRequest)) return

        this.itemCount.tasks = response.listimportvmtasksresponse.count
        this.importVmTasks = response.listimportvmtasksresponse.importvmtask || []
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchImportVmTasks', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchImportVmTasks', listRequest)) return

        this.loadingImportVmTasks = false
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchImportVmTasks', listRequest)) return
        this.loadingImportVmTasks = false
      })
    },
    fetchInstances () {
      this.fetchUnmanagedInstances()
      if (this.isUnmanaged) {
        this.fetchManagedInstances()
      } else if (this.kvmOption === 'external') {
        this.fetchExternalInstances()
      }
    },
    fetchUnmanagedInstances (page, pageSize) {
      if (this.isExternal) {
        this.fetchExtKVMInstances(page, pageSize)
        return
      }
      if (this.isMigrateFromNutanix) {
        this.fetchNutanixInstances(page, pageSize)
        return
      }
      const params = {
        clusterid: this.clusterId
      }
      const query = Object.assign({}, this.$route.query)
      this.page.unmanaged = page || parseInt(query.unmanagedpage) || this.page.unmanaged
      this.updateQuery('unmanagedpage', this.page.unmanaged)
      params.page = this.page.unmanaged
      this.pageSize.unmanaged = pageSize || this.pageSize.unmanaged
      params.pagesize = this.pageSize.unmanaged
      this.unmanagedInstances = []
      this.unmanagedInstancesSelectedRowKeys = []
      if (this.searchParams.unmanaged.keyword) {
        params.keyword = this.searchParams.unmanaged.keyword
      }
      if (!this.clusterId) {
        return
      }
      this.unmanagedInstancesLoading = true
      this.searchParams.unmanaged = params

      let apiName = this.listInstancesApi.unmanaged
      if (this.isMigrateFromVmware && this.selectedVmwareVcenter) {
        apiName = this.listInstancesApi.migratefromvmware
        params.zoneid = this.zoneId
        params.clusterid = this.clusterId
        params.hypervisor = 'VMware'
        params.sourceprovider = 'vmware'
        if (this.selectedVmwareVcenter.vcenter) {
          params.datacentername = this.selectedVmwareVcenter.datacentername
          params.vcenter = this.selectedVmwareVcenter.vcenter
          params.host = this.selectedVmwareVcenter.vcenter
          params.username = this.selectedVmwareVcenter.username
          params.password = this.selectedVmwareVcenter.password
        } else {
          params.existingvcenterid = this.selectedVmwareVcenter.existingvcenterid
        }
      }

      getAPI(apiName, params).then(json => {
        const response = this.isMigrateFromVmware ? json.listvmsforimportresponse : json.listunmanagedinstancesresponse
        const listUnmanagedInstances = response.unmanagedinstance
        if (this.arrayHasItems(listUnmanagedInstances)) {
          this.unmanagedInstances = this.unmanagedInstances.concat(listUnmanagedInstances)
        }
        this.itemCount.unmanaged = response.count || 0
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.unmanagedInstancesLoading = false
      })
    },
    fetchExtKVMInstances (page, pageSize) {
      const params = {
        zoneid: this.zoneid
      }
      const query = Object.assign({}, this.$route.query)
      this.page.unmanaged = page || parseInt(query.unmanagedpage) || this.page.unmanaged
      this.updateQuery('unmanagedpage', this.page.unmanaged)
      params.page = this.page.unmanaged
      this.pageSize.unmanaged = pageSize || this.pageSize.unmanaged
      params.pagesize = this.pageSize.unmanaged
      this.unmanagedInstances = []
      this.unmanagedInstancesSelectedRowKeys = []
      if (this.searchParams.unmanaged.keyword) {
        params.keyword = this.searchParams.unmanaged.keyword
      }
      this.values = toRaw(this.form)
      this.unmanagedInstancesLoading = true
      params.zoneid = this.zoneId
      params.host = this.values.hostname
      params.username = this.values.username
      params.password = this.values.password
      params.hypervisor = this.destinationHypervisor
      var details = ['host', 'username', 'password']
      for (var detail of details) {
        if (!params[detail]) {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('message.please.enter.valid.value') + ': ' + this.$t('label.' + detail.toLowerCase())
          })
          return
        }
      }
      this.searchParams.unmanaged = params
      getAPI(this.listInstancesApi.external, params).then(json => {
        const listUnmanagedInstances = json.listvmsforimportresponse.unmanagedinstance
        if (this.arrayHasItems(listUnmanagedInstances)) {
          this.unmanagedInstances = this.unmanagedInstances.concat(listUnmanagedInstances)
        }
        this.itemCount.unmanaged = json.listvmsforimportresponse.count
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.unmanagedInstancesLoading = false
      })
    },
    fetchNutanixInstances (page, pageSize) {
      const params = {
        zoneid: this.zoneId,
        host: this.form.hostname,
        username: this.form.username,
        password: this.form.password,
        hypervisor: 'Nutanix',
        sourceprovider: 'nutanix',
        sourceapi: this.form.sourceapi || 'v3',
        insecure: this.form.insecure !== false
      }
      const query = Object.assign({}, this.$route.query)
      this.page.unmanaged = page || parseInt(query.unmanagedpage) || this.page.unmanaged
      this.updateQuery('unmanagedpage', this.page.unmanaged)
      params.page = this.page.unmanaged
      this.pageSize.unmanaged = pageSize || this.pageSize.unmanaged
      params.pagesize = this.pageSize.unmanaged
      this.unmanagedInstances = []
      this.unmanagedInstancesSelectedRowKeys = []
      if (this.searchParams.unmanaged.keyword) {
        params.keyword = this.searchParams.unmanaged.keyword
      }
      var details = ['host', 'username', 'password']
      for (var detail of details) {
        if (!params[detail]) {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('message.please.enter.valid.value') + ': ' + this.$t('label.' + detail.toLowerCase())
          })
          return
        }
      }
      this.values = toRaw(this.form)
      this.unmanagedInstancesLoading = true
      this.searchParams.unmanaged = params
      getAPI(this.listInstancesApi.external, params).then(json => {
        const response = json.listvmsforimportresponse
        const listUnmanagedInstances = response.unmanagedinstance
        if (this.arrayHasItems(listUnmanagedInstances)) {
          this.unmanagedInstances = this.unmanagedInstances.concat(listUnmanagedInstances)
        }
        this.itemCount.unmanaged = response.count
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.unmanagedInstancesLoading = false
      })
    },
    searchUnmanagedInstances (params) {
      this.searchParams.unmanaged.keyword = params.searchQuery
      this.fetchUnmanagedInstances()
    },
    fetchManagedInstances (page, pageSize) {
      const params = {
        listall: true,
        clusterid: this.clusterId
      }
      const query = Object.assign({}, this.$route.query)
      this.page.managed = page || parseInt(query.managedpage) || this.page.managed
      this.updateQuery('managedpage', this.page.managed)
      params.page = this.page.managed
      this.pageSize.managed = pageSize || this.pageSize.managed
      params.pagesize = this.pageSize.managed
      this.managedInstances = []
      this.managedInstancesSelectedRowKeys = []
      if (this.searchParams.managed.keyword) {
        params.keyword = this.searchParams.managed.keyword
      }
      if (!this.clusterId) {
        return
      }
      this.managedInstancesLoading = true
      this.searchParams.managed = params
      getAPI(this.listInstancesApi.managed, params).then(json => {
        const listManagedInstances = json.listvirtualmachinesresponse.virtualmachine
        if (this.arrayHasItems(listManagedInstances)) {
          this.managedInstances = this.managedInstances.concat(listManagedInstances)
        }
        this.itemCount.managed = json.listvirtualmachinesresponse.count
      }).finally(() => {
        this.managedInstancesLoading = false
      })
    },
    searchManagedInstances (params) {
      this.searchParams.managed.keyword = params.searchQuery
      this.fetchManagedInstances()
    },
    onUnmanagedInstanceSelectRow (value) {
      this.unmanagedInstancesSelectedRowKeys = value
    },
    onManagedInstanceSelectRow (value) {
      this.managedInstancesSelectedRowKeys = value
    },
    isValidValueForKey (obj, key) {
      return key in obj && obj[key] != null
    },
    arrayHasItems (array) {
      return array !== null && array !== undefined && Array.isArray(array) && array.length > 0
    },
    isObjectEmpty (obj) {
      return !(obj !== null && obj !== undefined && Object.keys(obj).length > 0 && obj.constructor === Object)
    },
    updateManageInstanceActionLoading (value) {
      this.importUnmanagedInstanceLoading = value
      if (!value) {
        this.fetchInstances()
      }
    },
    async fetchGuestOsMappings (osIdentifier, hypervisorVersion) {
      const params = {}
      params.hypervisor = 'VMware'
      params.hypervisorversion = hypervisorVersion
      params.osnameforhypervisor = osIdentifier
      return await getAPI('listGuestOsMapping', params).then(json => {
        return json.listguestosmappingresponse?.guestosmapping || []
      }).catch(error => {
        this.$notifyError(error)
        return []
      })
    },
    fetchVmwareInstanceForKVMMigration (vmname, hostname) {
      const params = {}
      this.loadingGuestOsMappings = true
      if (this.isMigrateFromVmware && this.selectedVmwareVcenter) {
        if (this.selectedVmwareVcenter.vcenter) {
          params.datacentername = this.selectedVmwareVcenter.datacentername
          params.vcenter = this.selectedVmwareVcenter.vcenter
          params.host = this.selectedVmwareVcenter.vcenter
          params.username = this.selectedVmwareVcenter.username
          params.password = this.selectedVmwareVcenter.password
        } else {
          params.existingvcenterid = this.selectedVmwareVcenter.existingvcenterid
        }
        params.instancename = vmname
        params.zoneid = this.zoneId
        params.clusterid = this.clusterId
        params.hypervisor = 'VMware'
        params.sourceprovider = 'vmware'
      }
      getAPI('listVmsForImport', params).then(async json => {
        const response = json.listvmsforimportresponse
        this.selectedUnmanagedInstance = response.unmanagedinstance[0]
        this.selectedUnmanagedInstance.ostypename = this.selectedUnmanagedInstance.osdisplayname
        this.selectedUnmanagedInstance.state = this.selectedUnmanagedInstance.powerstate
        if (this.selectedUnmanagedInstance.osid && this.selectedUnmanagedInstance.hypervisorversion) {
          this.selectedUnmanagedInstance.guestOsMappings = await this.fetchGuestOsMappings(this.selectedUnmanagedInstance.osid, this.selectedUnmanagedInstance.hypervisorversion)
        } else {
          this.selectedUnmanagedInstance.guestOsMappings = []
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
        this.loadingGuestOsMappings = false
      })
    },
    onManageInstanceAction () {
      this.selectedUnmanagedInstance = {}
      if (this.unmanagedInstances.length > 0 &&
        this.unmanagedInstancesSelectedRowKeys.length > 0) {
        this.selectedUnmanagedInstance = this.unmanagedInstances[this.unmanagedInstancesSelectedRowKeys[0]]
        this.selectedUnmanagedInstance.ostypename = this.selectedUnmanagedInstance.osdisplayname
        this.selectedUnmanagedInstance.state = this.selectedUnmanagedInstance.powerstate
      }
      if (this.isMigrateFromVmware) {
        this.fetchVmwareInstanceForKVMMigration(this.selectedUnmanagedInstance.name, this.selectedUnmanagedInstance.hostname)
        this.showUnmanageForm = true
      } else {
        this.showUnmanageForm = true
      }
    },
    onImportInstanceAction () {
      this.selectedUnmanagedInstance = {}
      this.values = toRaw(this.form)
      if (!this.values.diskpath) {
        this.$notification.error({
          message: this.$t('message.request.failed'),
          description: this.$t('message.please.enter.valid.value') + ': ' + this.$t('label.disk.path')
        })
        return
      }
      if (this.showPool && !this.values.poolid) {
        this.$notification.error({
          message: this.$t('message.request.failed'),
          description: this.$t('message.please.enter.valid.value') + ': ' + this.$t('label.storagepool')
        })
        return
      }
      this.showUnmanageForm = true
    },
    closeImportUnmanagedInstanceForm () {
      this.selectedUnmanagedInstance = {}
      this.showUnmanageForm = false
    },
    onUnmanageInstanceAction () {
      const self = this
      const title = this.managedInstancesSelectedRowKeys.length > 1
        ? this.$t('message.action.unmanage.instances')
        : this.$t('message.action.unmanage.instance')
      var vmNames = []
      for (var index of this.managedInstancesSelectedRowKeys) {
        vmNames.push(this.managedInstances[index].name)
      }
      const content = vmNames.join(', ')
      this.$confirm({
        title: title,
        okText: this.$t('label.ok'),
        okType: 'danger',
        content: content,
        cancelText: this.$t('label.cancel'),
        onOk () {
          self.unmanageInstances()
        }
      })
    },
    trackImportJobId (details) {
      const jobId = details[0]
      const name = details[1]
      this.$pollJob({
        jobId,
        title: this.$t('label.import.instance'),
        description: this.$t('label.import.instance'),
        loadingMessage: `${this.$t('label.import.instance')} ${name} ${this.$t('label.in.progress')}`,
        catchMessage: this.$t('error.fetching.async.job.result'),
        successMessage: this.$t('message.success.import.instance') + ' ' + name,
        successMethod: (result) => {
          this.fetchInstances()
        }
      })
    },
    unmanageInstances () {
      for (var index of this.managedInstancesSelectedRowKeys) {
        const vm = this.managedInstances[index]
        var params = { id: vm.id }
        postAPI('unmanageVirtualMachine', params).then(json => {
          const jobId = json.unmanagevirtualmachineresponse.jobid
          this.$pollJob({
            jobId,
            title: this.$t('label.unmanage.instance'),
            description: vm.name,
            loadingMessage: `${this.$t('label.unmanage.instance')} ${vm.name} ${this.$t('label.in.progress')}`,
            catchMessage: this.$t('error.fetching.async.job.result'),
            successMessage: this.$t('message.success.unmanage.instance') + ' ' + vm.name,
            successMethod: result => {
              if (index === this.managedInstancesSelectedRowKeys[this.managedInstancesSelectedRowKeys.length - 1]) {
                this.fetchInstances()
              }
            }
          })
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }
    },
    onListUnmanagedInstancesFromVmware (obj) {
      this.selectedVmwareVcenter = obj.params
      this.page.unmanaged = 1
      this.updateQuery('unmanagedpage', this.page.unmanaged)
      this.unmanagedInstances = obj.response.unmanagedinstance || []
      this.itemCount.unmanaged = obj.response.count || 0
      this.unmanagedInstancesLoading = false
    },
    updateVmwareVcenterType (type) {
      this.vmwareVcenterType = type
    }
  }
}
</script>

<style scoped lang="less">
:deep(.ant-table-small) > .ant-table-content > .ant-table-body {
  margin: 0;
}

.importform {
  width: 80vw;
}
.instances-card {
  height: 100%;
}
.source-dest-card {
  width: 50%;
  height: 100%;
}
.source-hypervisor-button {
  width: 33.333%;
  text-align: center;
}
.instances-card-table {
  overflow-y: auto;
  margin-bottom: 100px;
}
.instances-card-footer {
  height: 100px;
  position: absolute;
  bottom: 0;
  left: 0;
  margin-left: 10px;
  right: 0;
  margin-right: 10px;
}
.card-footer {
  text-align: right;
}
.card-footer button {
  width: 50%;
  text-align: center;
}
.row-element {
  margin-top: 10px;
  margin-bottom: 10px;
}
.action-button-left {
  text-align: left;
}
.action-button-right {
  text-align: right;
}
.fetch-instances-column {
  width: 50%;
  margin-left: 50%;
  padding-left: 24px;
}

.manage-instances-alert {
  margin-bottom: 16px;

  :deep(.ant-alert-icon) {
    align-items: center;
    display: inline-flex;
  }

  :deep(.ant-alert-message) {
    font-weight: 600;
    line-height: 1.45;
  }

  :deep(.ant-alert-description) {
    line-height: 1.6;
  }
}

:global(body.dark-mode .manage-instances-alert.ant-alert-info) {
  background: #102538;
  border-color: #27445f;
}

:global(body.dark-mode .manage-instances-alert .ant-alert-icon),
:global(body.dark-mode .manage-instances-alert .ant-alert-message) {
  color: #d6e4ff;
}

:global(body.dark-mode .manage-instances-alert .ant-alert-description) {
  color: #b7c9e6;
}

.breadcrumb-card {
  margin-left: -24px;
  margin-right: -24px;
  margin-top: -16px;
  margin-bottom: 12px;
}

.ant-breadcrumb {
  vertical-align: text-bottom;
}

:deep(.ant-table-tbody > .import-row-selected > td) {
  background-color: var(--ui-bg-selected);
}

:deep(.ant-table-tbody > tr:focus-visible) {
  outline: 2px solid var(--ui-focus);
  outline-offset: -2px;
}
</style>
