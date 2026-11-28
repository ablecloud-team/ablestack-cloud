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
    <a-col v-if="!embedded" :span="24" class="content-breadcrumb">
      <a-card class="breadcrumb-card breadcrumb-toolbar">
        <div class="breadcrumb-toolbar-row">
          <div class="breadcrumb-toolbar-path">
            <breadcrumb>
              <template #end>
                <a-button
                  :loading="viewLoading"
                  size="small"
                  shape="round"
                  @click="fetchData()" >
                  <template #icon><reload-outlined /></template>
                  {{ $t('label.refresh') }}
                </a-button>
              </template>
            </breadcrumb>
          </div>
        </div>
      </a-card>
    </a-col>
    <a-col :md="24">
      <div>
        <a-card>
          <a-alert
            type="info"
            :showIcon="true"
            :message="$t('label.desc.import.unmanage.volume')"
          >
            <template #description>
              <span v-html="$t('message.desc.import.unmanage.volume')" />
            </template>
          </a-alert>
          <br />
          <a-row :gutter="12">
            <a-col :span="24">
              <a-card class="volume-storage-pool">
                <template #title>
                  {{ $t('label.storagepool') }}
                </template>
                <a-form
                  class="volume-scope-form"
                  :ref="formRef"
                  :model="form"
                  :rules="rules"
                  layout="vertical"
                >
                <a-form-item name="scope" ref="scope">
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
                    <a-select-option :value="'zone'" :label="$t('label.zoneid')"> {{ $t('label.zoneid') }} </a-select-option>
                    <a-select-option :value="'cluster'" :label="$t('label.clusterid')"> {{ $t('label.clusterid') }} </a-select-option>
                    <a-select-option :value="'host'" :label="$t('label.hostid')"> {{ $t('label.hostid') }} </a-select-option>
                  </a-select>
                </a-form-item>
                  <a-form-item
                    name="zoneid"
                    ref="zoneid"
                    :label="$t('label.zoneid')"
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
                    :label="$t('label.podid')">
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
                    :label="$t('label.clusterid')">
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
                        @change="value => onSelectPoolId(value)"
                      ></a-select>
                  </a-form-item>
                </a-form>
              </a-card>
            </a-col>
          </a-row>

          <a-radio-group v-model:value="volumeList" class="volume-list-panels" button-style="solid" @change="fetchVolumes">
            <a-radio-button v-if="canListUnmanaged" value="unmanaged">{{ $t('label.unmanaged.volumes') }}</a-radio-button>
            <a-radio-button v-if="canListManaged" value="managed">{{ $t('label.managed.volumes') }}</a-radio-button>
          </a-radio-group>
          <a-row :gutter="[12, 16]" class="volume-list-panels">
            <a-col v-if="canListUnmanaged" v-show="volumeList === 'unmanaged'" :span="24">
              <a-card class="volumes-card unmanaged-volumes-card">
                <template #title>
                  {{ $t('label.unmanaged.volumes') }}
                  <a-tooltip :title="$t('message.volumes.unmanaged')">
                    <info-circle-outlined />
                  </a-tooltip>
                  <a-button
                    style="margin-left: 12px; margin-top: 4px"
                    :loading="unmanagedVolumesLoading"
                    size="small"
                    shape="round"
                    @click="fetchUnmanagedVolumes()" >
                    <template #icon><reload-outlined /></template>
                  </a-button>
                  <span style="float: right; width: 50%">
                    <search-view
                      :searchFilters="searchFilters.unmanaged"
                      :searchParams="searchParams.unmanaged"
                      :apiName="listVolumesApi.unmanaged"
                      @search="searchUnmanagedVolumes"
                    />
                  </span>
                </template>
                <a-alert v-if="unmanagedListError" type="error" show-icon :message="$t('message.request.failed')" />
                <a-table
                  class="workspace-table workspace-import-table volumes-card-table"
                  v-scroll-activity="{ selector: tableScrollSelector, label: $t('label.storagepool'), scope: JSON.stringify([page, pageSize]) }"
                  :loading="unmanagedVolumesLoading"
                  :custom-row="unmanagedRow"
                  :rowKey="(record, index) => index"
                  :columns="unmanagedVolumesColumns"
                  :data-source="unmanagedVolumes"
                  :pagination="false"
                  size="middle"
                  :rowClassName="getRowClassName"
                >
                  <template #bodyCell="{ column, text, record }">
                    <template v-if="column.key === 'format'">{{ readableVolumeFormat(record) }}</template>
                    <template v-else-if="column.key === 'allocated'">{{ isRbd(record) ? '—' : readableSize(text) }}</template>
                    <template v-else-if="column.key === 'virtual'">{{ readableSize(text) }}</template>
                  </template>
                </a-table>
                <div class="volumes-card-footer">
                  <a-pagination
                    class="row-element"
                    size="small"
                    :current="page.unmanaged"
                    :pageSize="pageSize.unmanaged"
                    :total="itemCount.unmanaged"
                    :showTotal="total => this.$localStorage.get('LOCALE') == 'ko_KR' ?
                      `${$t('label.total')} ${total} ${$t('label.items')} ${$t('label.of')} ${Math.min(total, 1+((page.unmanaged-1)*pageSize.unmanaged))}-${Math.min(page.unmanaged*pageSize.unmanaged, total)} ${$t('label.showing')}` :
                      `${$t('label.showing')} ${Math.min(total, 1+((page.unmanaged-1)*pageSize.unmanaged))}-${Math.min(page.unmanaged*pageSize.unmanaged, total)} ${$t('label.of')} ${total} ${$t('label.items')}`"
                    @change="fetchUnmanagedVolumes"
                    showQuickJumper>
                    <template #buildOptionText="props">
                      <span>{{ props.value }} / {{ $t('label.page') }}</span>
                    </template>
                  </a-pagination>
                  <div :span="24" class="action-button-right">
                    <a-button
                      :loading="importUnmanagedVolumeLoading"
                      :disabled="!(('importVolume' in $store.getters.apis) && selectedImportVolume)"
                      type="primary"
                      @click="onImportVolumeAction">
                      <template #icon><import-outlined /></template>
                      {{ $t('label.import.volume') }}
                    </a-button>
                  </div>
                </div>
              </a-card>
            </a-col>
            <a-col v-if="canListManaged" v-show="volumeList === 'managed'" :span="24">
              <a-card class="volumes-card">
                <template #title>
                  {{ $t('label.managed.volumes') }}
                  <a-tooltip :title="$t('message.volumes.managed')">
                    <info-circle-outlined />
                  </a-tooltip>
                  <a-button
                    style="margin-left: 12px; margin-top: 4px"
                    :loading="managedVolumesLoading"
                    size="small"
                    shape="round"
                    @click="fetchManagedVolumes()" >
                    <template #icon><reload-outlined /></template>
                  </a-button>
                  <span style="float: right; width: 50%">
                    <search-view
                      :searchFilters="searchFilters.managed"
                      :searchParams="searchParams.managed"
                      :apiName="listVolumesApi.managed"
                      @search="searchManagedVolumes"
                    />
                  </span>
                </template>
                <a-alert v-if="managedListError" type="error" show-icon :message="$t('message.request.failed')" />
                <a-table
                  class="workspace-table workspace-import-table volumes-card-table"
                  v-scroll-activity="{ selector: tableScrollSelector, label: $t('label.storagepool'), scope: JSON.stringify([page, pageSize]) }"
                  :loading="managedVolumesLoading"
                  :custom-row="managedRow"
                  :rowKey="(record, index) => index"
                  :columns="managedVolumesColumns"
                  :data-source="managedVolumes"
                  :pagination="false"
                  size="middle"
                  :rowClassName="getRowClassName"
                >
                  <template #bodyCell="{ column, text, record }">
                    <template v-if="column.key === 'name'">
                      <router-link :to="{ path: '/volume/' + record.id }">{{ text }}</router-link>
                    </template>
                    <template v-if="column.key === 'state'">
                      <status :text="text ? text : ''" displayText />
                    </template>
                  </template>
                </a-table>
                <div class="volumes-card-footer">
                  <a-pagination
                    class="row-element"
                    size="small"
                    :current="page.managed"
                    :pageSize="pageSize.managed"
                    :total="itemCount.managed"
                    :showTotal="total => this.$localStorage.get('LOCALE') == 'ko_KR' ?
                      `${$t('label.total')} ${total} ${$t('label.items')} ${$t('label.of')} ${Math.min(total, 1+((page.managed-1)*pageSize.managed))}-${Math.min(page.managed*pageSize.managed, total)} ${$t('label.showing')}` :
                      `${$t('label.showing')} ${Math.min(total, 1+((page.managed-1)*pageSize.managed))}-${Math.min(page.managed*pageSize.managed, total)} ${$t('label.of')} ${total} ${$t('label.items')}`"
                    @change="fetchManagedVolumes"
                    showQuickJumper>
                    <template #buildOptionText="props">
                      <span>{{ props.value }} / {{ $t('label.page') }}</span>
                    </template>
                  </a-pagination>
                  <div :span="24" class="action-button-right">
                    <a-button
                      :loading="unmanagePending"
                      :disabled="!(('unmanageVolume' in $store.getters.apis) && releasableVolumes.length > 0)"
                      type="primary"
                      @click="onUnmanageVolumeAction">
                      <template #icon><disconnect-outlined /></template>
                      {{ managedVolumesSelectedRowKeys.length > 1 ? $t('label.action.unmanage.volumes') : $t('label.action.unmanage.volume') }}
                    </a-button>
                  </div>
                </div>
              </a-card>
            </a-col>
          </a-row>
        </a-card>

        <a-modal
          v-if="showImportForm"
          :visible="showImportForm"
          :title="$t('label.import.volume')"
          :closable="!importUnmanagedVolumeLoading"
          :keyboard="!importUnmanagedVolumeLoading"
          :maskClosable="false"
          :footer="null"
          :cancelText="$t('label.cancel')"
          @cancel="onCloseImportVolumeForm"
          centered
          width="auto">
          <a-alert type="warning" style="margin-bottom: 20px">
            <template #message>
              <label v-html="$t('message.import.volume')"></label>
            </template>
          </a-alert>
          <a-form
            :ref="importFormRef"
            :model="importForm"
            @finish="handleSubmitImportVolumeForm"
            v-ctrl-enter="handleSubmitImportVolumeForm"
            class="import-form"
          >
            <a-descriptions v-if="selectedUnmanagedVolume" bordered size="small" :column="1">
              <a-descriptions-item :label="$t('label.path')">{{ selectedUnmanagedVolume.path }}</a-descriptions-item>
              <a-descriptions-item :label="$t('label.format')">{{ readableVolumeFormat(selectedUnmanagedVolume) }}</a-descriptions-item>
            </a-descriptions>
            <a-form-item
              name="name"
              ref="name"
              :label="$t('label.name')">
              <a-input v-model:value="importForm.name" />
            </a-form-item>

            <a-form-item
              name="accounttype"
              ref="accounttype"
              :label="$t('label.accounttype')">
              <a-select
                @change="changeAccountType"
                v-model:value="importForm.selectedAccountType"
                v-focus="true"
                showSearch
                optionFilterProp="label">
                <a-select-option value="Current">{{ $t('label.current.account') }}</a-select-option>
                <a-select-option value="Account" :label="$t('label.account')">{{ $t('label.account') }}</a-select-option>
                <a-select-option value="Project" :label="$t('label.project')">{{ $t('label.project') }}</a-select-option>
              </a-select>
            </a-form-item>

            <a-form-item
              v-if="importForm.selectedAccountType === 'Account' || importForm.selectedAccountType === 'Project'"
              name="domain"
              ref="domain"
              :label="$t('label.domain')">
              <infinite-scroll-select
                v-model:value="importForm.selectedDomain"
                api="listDomains"
                :apiParams="domainsApiParams"
                resourceType="domain"
                optionValueKey="id"
                optionLabelKey="path"
                defaultIcon="block-outlined"
                allowClear="true"
                @change-option-value="changeDomain" />
            </a-form-item>

            <a-form-item
              v-if="importForm.selectedAccountType === 'Account'"
              name="account"
              ref="account"
              :label="$t('label.account')">
              <infinite-scroll-select
                v-model:value="importForm.selectedAccount"
                api="listAccounts"
                :apiParams="accountsApiParams"
                resourceType="account"
                optionValueKey="name"
                optionLabelKey="name"
                defaultIcon="team-outlined"
                allowClear="true"
                @change-option-value="changeAccount" />
              <span v-if="importForm.accountError" class="required">{{ $t('label.required') }}</span>
            </a-form-item>

            <a-form-item
              v-if="importForm.selectedAccountType === 'Project'"
              name="project"
              ref="project"
              :label="$t('label.project')">
              <infinite-scroll-select
                v-model:value="importForm.selectedProject"
                api="listProjects"
                :apiParams="projectsApiParams"
                resourceType="project"
                optionValueKey="id"
                optionLabelKey="name"
                defaultIcon="project-outlined"
                allowClear="true"
                @change-option-value="changeProject" />
              <span v-if="importForm.projectError" class="required">{{ $t('label.required') }}</span>
            </a-form-item>

            <a-form-item
              name="diskoffering"
              ref="diskoffering"
              :label="$t('label.diskoffering')">
              <a-select
                v-model:value="importForm.selectedDiskoffering"
                :placeholder="$t('label.diskofferingid')"
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }" >
                <a-select-option
                  v-for="(offering, index) in diskOfferings"
                  :value="offering.id"
                  :key="index"
                  :label="offering.displaytext || offering.name">
                  {{ offering.displaytext || offering.name }}
                </a-select-option>
              </a-select>
            </a-form-item>

            <div :span="24" class="action-button">
              <a-button :disabled="importUnmanagedVolumeLoading" @click="onCloseImportVolumeForm">{{ $t('label.cancel') }}</a-button>
              <a-button type="primary" ref="submit" :loading="importUnmanagedVolumeLoading" :disabled="!canSubmitVolume" @click="handleSubmitImportVolumeForm">{{ $t('label.ok') }}</a-button>
            </div>
          </a-form>
        </a-modal>
      </div>
    </a-col>
  </a-row>
</template>

<script>
import scrollActivity, { tableScrollSelector } from '@/utils/scrollActivity'
import { selectableImportRow } from '@/utils/moldListSelection'
import { restoreImportOptions } from '@/utils/importOptionNavigation'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import _ from 'lodash'
import Breadcrumb from '@/components/widgets/Breadcrumb'
import Status from '@/components/widgets/Status'
import SearchView from '@/components/view/SearchView'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel.vue'
import InfiniteScrollSelect from '@/components/widgets/InfiniteScrollSelect.vue'

export default {
  mixins: [listRefreshMixin(['fetchUnmanagedVolumes', 'fetchManagedVolumes'], { active: vm => !!vm.poolId })],
  components: {
    TooltipLabel,
    Breadcrumb,
    Status,
    SearchView,
    ResourceIcon,
    InfiniteScrollSelect
  },
  name: 'ManageVolumes',
  props: {
    embedded: { type: Boolean, default: false }
  },
  directives: { scrollActivity },
  data () {
    const unmanagedVolumesColumns = [
      {
        title: this.$t('label.filename'),
        dataIndex: 'name',
        width: 250
      },
      {
        key: 'format',
        title: this.$t('label.format'),
        dataIndex: 'format'
      },
      {
        key: 'allocated',
        title: this.$t('label.size'),
        dataIndex: 'size'
      },
      {
        key: 'virtual',
        title: this.$t('label.virtualsize'),
        dataIndex: 'virtualsize'
      }
    ]
    const managedVolumesColumns = [
      {
        key: 'name',
        title: this.$t('label.name'),
        dataIndex: 'name',
        width: 100
      },
      {
        key: 'state',
        title: this.$t('label.state'),
        dataIndex: 'state'
      },
      {
        title: this.$t('label.type'),
        dataIndex: 'type'
      },
      {
        title: this.$t('label.account'),
        dataIndex: 'account'
      },
      {
        title: this.$t('label.vmname'),
        dataIndex: 'vmname'
      }
    ]
    return {
      domains: [],
      accounts: [],
      projects: [],
      diskOfferings: [],
      options: {
        zones: [],
        pods: [],
        clusters: [],
        hosts: [],
        pools: []
      },
      rowCount: {},
      optionLoading: {
        zones: false,
        pods: false,
        clusters: false,
        hosts: false,
        pools: false
      },
      page: {
        unmanaged: 1,
        managed: 1
      },
      pageSize: {
        unmanaged: 10,
        managed: 10
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
      zone: {},
      pod: {},
      cluster: {},
      values: undefined,
      zoneId: undefined,
      podId: undefined,
      clusterId: undefined,
      hostId: undefined,
      poolId: undefined,
      diskpath: undefined,
      poolscope: 'zone',
      listVolumesApi: {
        unmanaged: 'listVolumesForImport',
        managed: 'listVolumes'
      },
      unmanagedVolumesColumns,
      unmanagedVolumesLoading: false,
      unmanagedVolumes: [],
      unmanagedVolumesSelectedRowKeys: [],
      importUnmanagedVolumeLoading: false,
      managedVolumesColumns,
      managedVolumesLoading: false,
      managedVolumes: [],
      managedVolumesSelectedRowKeys: [],
      showImportForm: false,
      selectedUnmanagedVolume: null,
      initialOptionsPending: false,
      volumeList: 'unmanaged',
      unmanagedListError: false,
      managedListError: false,
      unmanagePending: false,
      optionRequestIds: {},
      diskOfferingRequest: null,
      query: {}
    }
  },
  created () {
    this.page.unmanaged = parseInt(this.$route.query.unmanagedpage || 1)
    this.page.managed = parseInt(this.$route.query.managedpage || 1)
    if (!this.canListUnmanaged) this.volumeList = 'managed'
    this.initForm()
    this.fetchData()
  },
  computed: {
    tableScrollSelector () { return tableScrollSelector },
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
    showPod () {
      return this.poolscope !== 'zone'
    },
    showCluster () {
      return this.poolscope !== 'zone'
    },
    domainsApiParams () {
      return {
        listall: true,
        details: 'min',
        showicon: true
      }
    },
    accountsApiParams () {
      if (!this.importForm.selectedDomain) {
        return null
      }
      return {
        domainid: this.importForm.selectedDomain,
        showicon: true,
        state: 'Enabled',
        isrecursive: false
      }
    },
    projectsApiParams () {
      if (!this.importForm.selectedDomain) {
        return null
      }
      return {
        domainid: this.importForm.selectedDomain,
        state: 'Active',
        showicon: true,
        details: 'min',
        isrecursive: false
      }
    },
    canListUnmanaged () {
      return 'listVolumesForImport' in this.$store.getters.apis
    },
    canListManaged () {
      return 'listVolumes' in this.$store.getters.apis
    },
    selectedImportVolume () {
      return this.unmanagedVolumes[this.unmanagedVolumesSelectedRowKeys[0]]
    },
    releasableVolumes () {
      return this.managedVolumesSelectedRowKeys.map(key => this.managedVolumes[key]).filter(volume =>
        volume && volume.state === 'Ready' && volume.type === 'DATADISK' && !volume.virtualmachineid && !volume.vmname)
    },
    canSubmitVolume () {
      return this.importForm.name?.trim() && this.importForm.selectedDiskoffering && !this.loading
    },
    showHost () {
      return this.poolscope === 'host'
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
            podid: this.podId
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
            scope: this.poolscope
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
      return this.unmanagedVolumesLoading || this.managedVolumesLoading
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
    unmanagedVolumeSelection () {
      return {
        type: 'radio',
        selectedRowKeys: this.unmanagedVolumesSelectedRowKeys || [],
        onChange: this.onUnmanagedVolumeSelectRow
      }
    },
    managedVolumeSelection () {
      return {
        type: 'checkbox',
        selectedRowKeys: this.managedVolumesSelectedRowKeys || [],
        onChange: this.onManagedVolumeSelectRow,
        getCheckboxProps: (record) => {
          return {
            disabled: record.virtualmachineid !== undefined || record.state !== 'Ready' || record.hypervisor !== 'KVM'
          }
        }
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
      return selectableImportRow(this.unmanagedVolumeSelection, this.unmanagedVolumes, index)
    },
    managedRow (record, index) {
      return selectableImportRow(this.managedVolumeSelection, this.managedVolumes, index)
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({
      })
      this.rules = reactive({
      })
      this.importFormRef = ref()
      this.importForm = reactive({
      })
    },
    async fetchData () {
      if (this.initialOptionsPending) return
      this.initialOptionsPending = true
      this.unmanagedVolumes = []
      this.managedVolumes = []
      try {
        await restoreImportOptions(this.$router, initial => this.fetchOptions(this.params.zones, 'zones', undefined, initial))
      } finally {
        this.initialOptionsPending = false
      }
    },
    filterOption (input, option) {
      return (
        option.label.toUpperCase().indexOf(input.toUpperCase()) >= 0
      )
    },
    fetchOptions (param, name, exclude, initial) {
      if (exclude?.includes(name)) return
      if (!(param.list in this.$store.getters.apis)) return
      const request = Symbol(name)
      this.optionRequestIds[name] = request
      this.optionLoading[name] = true
      const params = { ...param.options }
      return getAPI(param.list, params).then(response => {
        if (this.optionRequestIds[name] !== request || (initial && this.$router.currentRoute.value !== initial.route)) return
        const body = Object.entries(response).find(([key]) => key.includes('response'))?.[1]
        let options = Object.values(body || {}).find(Array.isArray) || []
        if (name === 'clusters') options = options.filter(cluster => cluster.hypervisortype === 'KVM')
        this.rowCount[name] = body?.count ?? options.length
        this.options[name] = options
        return this.handleFetchOptionsSuccess(name, param, initial)
      }).catch(error => {
        console.log(error.stack)
      }).finally(() => {
        if (this.optionRequestIds[name] === request) this.optionLoading[name] = false
      })
    },
    isRbd (volume) {
      return volume.storagetype?.toUpperCase() === 'RBD'
    },
    readableVolumeFormat (volume) {
      return this.isRbd(volume) ? 'RBD' : volume.format || '—'
    },
    readableSize (size) {
      return size == null ? '—' : this.$bytesToHumanReadableSize(size)
    },
    clearPoolOptions (fields) {
      for (const field of fields) {
        this.options[field] = []
        this.optionLoading[field] = false
        this.optionRequestIds[field] = undefined
      }
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
      if (['zoneid', 'podid', 'clusterid', 'hostid', 'poolid'].includes(field)) {
        query.managedpage = 1
        query.unmanagedpage = 1
        this.searchParams.managed.keyword = null
        this.searchParams.unmanaged.keyword = null
      }
      if (!initial) this.$router.push({ query })
    },
    resetLists () {
      this.unmanagedListError = false
      this.managedListError = false
      this.unmanagedVolumesLoading = false
      this.managedVolumesLoading = false
      this.itemCount = {}
      this.page.unmanaged = 1
      this.unmanagedVolumes = []
      this.unmanagedVolumesSelectedRowKeys = []
      this.page.managed = 1
      this.managedVolumes = []
      this.managedVolumesSelectedRowKeys = []
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
      this.form.hostid = undefined
      this.form.poolid = undefined
      this.clearPoolOptions(['pods', 'clusters', 'hosts', 'pools'])
      this.updateQuery('zoneid', value, initial)
      return this.poolscope === 'zone'
        ? this.fetchOptions(this.params.pools, 'pools', undefined, initial)
        : this.fetchOptions(this.params.pods, 'pods', undefined, initial)
    },
    onSelectPodId (value, initial) {
      this.podId = value
      this.pod = _.find(this.options.pods, (option) => option.id === value)
      this.resetLists()
      this.clusterId = null
      this.form.clusterid = undefined
      this.hostId = null
      this.poolId = null
      this.form.hostid = undefined
      this.form.poolid = undefined
      this.clearPoolOptions(['clusters', 'hosts', 'pools'])
      this.updateQuery('podid', value, initial)
      return this.fetchOptions(this.params.clusters, 'clusters', value, initial)
    },
    onSelectClusterId (value, initial) {
      this.clusterId = value
      this.cluster = _.find(this.options.clusters, (option) => option.id === value)
      this.resetLists()
      this.hostId = null
      this.poolId = null
      this.form.hostid = undefined
      this.form.poolid = undefined
      this.clearPoolOptions(['hosts', 'pools'])
      this.updateQuery('clusterid', value, initial)
      if (this.showHost) {
        return this.fetchOptions(this.params.hosts, 'hosts', value, initial)
      } else {
        return this.fetchOptions(this.params.pools, 'pools', value, initial)
      }
    },
    onSelectHostId (value, initial) {
      this.hostId = value
      this.poolId = null
      this.form.poolid = undefined
      this.clearPoolOptions(['pools'])
      this.resetLists()
      this.updateQuery('hostid', value, initial)
      return this.fetchOptions(this.params.pools, 'pools', value, initial)
    },
    onSelectPoolId (value, initial) {
      this.poolId = value
      this.updateQuery('poolid', value, initial)
      if (initial) initial.read = () => this.fetchVolumes()
      else this.fetchVolumes()
    },
    onSelectPoolScope (value) {
      this.poolscope = value
      this.zoneId = this.podId = this.clusterId = this.hostId = this.poolId = null
      this.clearPoolOptions(['pods', 'clusters', 'hosts', 'pools'])
      this.resetLists()
      this.updateQuery('scope', value)
      this.fetchOptions(this.params.zones, 'zones', value)
    },
    changeAccountType () {
      this.importForm.selectedDomain = null
      this.importForm.selectedAccount = null
      this.importForm.selectedProject = null
      this.importForm.selectedDiskoffering = null
      this.diskOfferings = []
      this.diskOfferingRequest = null
      this.loading = false
      if (this.importForm.selectedAccountType === 'Current') this.fetchDiskOfferings()
    },
    changeDomain () {
      this.importForm.selectedAccount = null
      this.importForm.selectedProject = null
      this.importForm.selectedDiskoffering = null
      this.diskOfferings = []
      this.diskOfferingRequest = null
      this.loading = false
      // InfiniteScrollSelect will auto-reload when apiParams changes
    },
    changeAccount () {
      this.importForm.selectedProject = null
      this.importForm.selectedDiskoffering = null
      this.diskOfferings = {}
      this.fetchDiskOfferings()
    },
    changeProject () {
      this.importForm.selectedAccount = null
      this.importForm.selectedDiskoffering = null
      this.diskOfferings = {}
      this.fetchDiskOfferings()
    },
    fetchDiskOfferings () {
      const pool = this.options.pools.find(option => option.id === this.poolId)
      if (!pool) return
      const request = Symbol('diskOfferings')
      this.diskOfferingRequest = request
      this.loading = true
      const storagetype = pool.scope === 'HOST' ? 'local' : 'shared'
      var params = {
        zoneid: this.zoneId,
        storageid: this.poolId,
        storagetype: storagetype,
        encrypt: false,
        listall: true
      }
      if (this.importForm.selectedAccountType === 'Account') {
        params.domainid = this.importForm.selectedDomain
        params.account = this.importForm.selectedAccount
      } else if (this.importForm.selectedAccountType === 'Project') {
        params.domainid = this.importForm.selectedDomain
        params.projectid = this.importForm.selectedProject
      }

      getAPI('listDiskOfferings', params).then(json => {
        if (this.diskOfferingRequest === request) this.diskOfferings = json.listdiskofferingsresponse.diskoffering || []
      }).catch(error => {
        if (this.diskOfferingRequest === request) this.$notifyError(error)
      }).finally(() => {
        if (this.diskOfferingRequest === request) this.loading = false
      })
    },
    fetchVolumes () {
      if (this.volumeList === 'unmanaged' && this.canListUnmanaged) return this.fetchUnmanagedVolumes()
      if (this.volumeList === 'managed' && this.canListManaged) return this.fetchManagedVolumes()
    },
    fetchUnmanagedVolumes (page, pageSize) {
      const params = {
        storageid: this.poolId
      }
      const query = Object.assign({}, this.$route.query)
      this.page.unmanaged = page || parseInt(query.unmanagedpage) || this.page.unmanaged
      this.updateQuery('unmanagedpage', this.page.unmanaged)
      params.page = this.page.unmanaged
      this.pageSize.unmanaged = pageSize || this.pageSize.unmanaged
      params.pagesize = this.pageSize.unmanaged
      if (this.searchParams.unmanaged.keyword) {
        params.keyword = this.searchParams.unmanaged.keyword
      }
      if (!this.poolId) {
        return
      }
      this.unmanagedListError = false
      const listRequest = this.listRequestToken('fetchUnmanagedVolumes')
      this.unmanagedVolumesLoading = !listRequest.loaded
      this.searchParams.unmanaged = params

      const apiName = this.listVolumesApi.unmanaged

      listRequest.key = this.listRefreshScope()
      return getAPI(apiName, params).then(json => {
        if (!this.isListRequestCurrent('fetchUnmanagedVolumes', listRequest)) return
        const selectedIds = new Set(this.unmanagedVolumesSelectedRowKeys.map(index => this.unmanagedVolumes[index]).filter(Boolean).map(row => row.id || row.path))
        const response = json.listvolumesforimportresponse
        const start = (this.page.unmanaged - 1) * this.pageSize.unmanaged
        this.unmanagedVolumes = (response.volumeforimport || []).slice(start, start + this.pageSize.unmanaged)
        this.unmanagedVolumesSelectedRowKeys = this.unmanagedVolumes.map((row, index) => selectedIds.has(row.id || row.path) ? index : -1).filter(index => index >= 0)
        this.itemCount.unmanaged = response.count
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchUnmanagedVolumes', listRequest)) return
        this.unmanagedListError = true
        listRequest.failed = true
        this.listRefreshFailed = true
        if (!listRequest.loaded) this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchUnmanagedVolumes', listRequest)) return
        this.unmanagedVolumesLoading = false
      })
    },
    searchUnmanagedVolumes (params) {
      this.searchParams.unmanaged.keyword = params.searchQuery
      this.fetchUnmanagedVolumes()
    },
    fetchManagedVolumes (page, pageSize) {
      const params = {
        listall: true,
        projectId: -1,
        storageid: this.poolId
      }
      const query = Object.assign({}, this.$route.query)
      this.page.managed = page || parseInt(query.managedpage) || this.page.managed
      this.updateQuery('managedpage', this.page.managed)
      params.page = this.page.managed
      this.pageSize.managed = pageSize || this.pageSize.managed
      params.pagesize = this.pageSize.managed
      if (this.searchParams.managed.keyword) {
        params.keyword = this.searchParams.managed.keyword
      }
      if (!this.poolId) {
        return
      }
      this.managedListError = false
      const listRequest = this.listRequestToken('fetchManagedVolumes')
      this.managedVolumesLoading = !listRequest.loaded
      this.searchParams.managed = params
      listRequest.key = this.listRefreshScope()
      return getAPI(this.listVolumesApi.managed, params).then(json => {
        if (!this.isListRequestCurrent('fetchManagedVolumes', listRequest)) return
        const selectedIds = new Set(this.managedVolumesSelectedRowKeys.map(index => this.managedVolumes[index]).filter(Boolean).map(row => row.id || row.path))
        this.managedVolumes = []
        const response = json.listvolumesresponse
        const listManagedVolumes = response.volume
        if (this.arrayHasItems(listManagedVolumes)) {
          this.managedVolumes = this.managedVolumes.concat(listManagedVolumes)
        }
        this.managedVolumesSelectedRowKeys = this.managedVolumes.map((row, index) => selectedIds.has(row.id || row.path) ? index : -1).filter(index => index >= 0)
        this.itemCount.managed = response.count
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchManagedVolumes', listRequest)) return
        this.managedListError = true
        listRequest.failed = true
        this.listRefreshFailed = true
        if (!listRequest.loaded) this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchManagedVolumes', listRequest)) return
        this.managedVolumesLoading = false
      })
    },
    searchManagedVolumes (params) {
      this.searchParams.managed.keyword = params.searchQuery
      this.fetchManagedVolumes()
    },
    onUnmanagedVolumeSelectRow (value) {
      this.unmanagedVolumesSelectedRowKeys = value
    },
    onManagedVolumeSelectRow (value) {
      this.managedVolumesSelectedRowKeys = value
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
    updateManageVolumeActionLoading (value) {
      this.importUnmanagedVolumeLoading = value
      if (!value) {
        this.fetchVolumes()
      }
    },
    onImportVolumeAction () {
      if (!this.selectedImportVolume) return
      this.selectedUnmanagedVolume = this.selectedImportVolume
      this.importForm.name = this.selectedUnmanagedVolume.name
      this.importForm.selectedAccountType = 'Current'
      this.importForm.selectedDiskoffering = null
      this.fetchDiskOfferings()
      this.showImportForm = true
    },
    async handleSubmitImportVolumeForm () {
      const selected = this.selectedUnmanagedVolume
      if (!selected || this.importUnmanagedVolumeLoading || !this.canSubmitVolume) return
      const { selectedAccountType, selectedAccount, selectedProject, selectedDomain, selectedDiskoffering, name } = toRaw(this.importForm)
      const params = {
        diskofferingid: selectedDiskoffering,
        storageid: this.poolId,
        path: selected.path,
        name: name.trim()
      }
      if (selectedAccountType === 'Account') {
        this.importForm.accountError = !selectedDomain || !selectedAccount
        if (this.importForm.accountError) return
        Object.assign(params, { account: selectedAccount, domainid: selectedDomain })
      } else if (selectedAccountType === 'Project') {
        this.importForm.projectError = !selectedDomain || !selectedProject
        if (this.importForm.projectError) return
        Object.assign(params, { projectid: selectedProject, domainid: selectedDomain })
      }

      this.importUnmanagedVolumeLoading = true
      const complete = () => { this.importUnmanagedVolumeLoading = false }
      try {
        const result = await getAPI('listVolumesForImport', { storageid: params.storageid, path: params.path })
        const source = result.listvolumesforimportresponse?.volumeforimport?.[0]
        const sourceIdentity = selected.details?.SOURCE_IDENTITY
        if (!source || (sourceIdentity && source.details?.SOURCE_IDENTITY !== sourceIdentity)) {
          complete()
          this.$notification.error({ message: this.$t('message.request.failed'), description: this.$t('message.please.enter.valid.value') })
          return
        }
        if (source.details?.SOURCE_IDENTITY) params.sourceidentity = source.details.SOURCE_IDENTITY
        const response = await postAPI('importVolume', params)
        this.$pollJob({
          jobId: response.importvolumeresponse.jobid,
          title: this.$t('label.import.volume'),
          description: params.name,
          loadingMessage: this.$t('label.in.progress'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          successMessage: this.$t('message.success.import.volume') + ' ' + params.name,
          errorMethod: complete,
          catchMethod: complete,
          successMethod: () => {
            complete()
            this.onCloseImportVolumeForm()
            if (params.storageid === this.poolId) {
              this.volumeList = this.canListManaged ? 'managed' : 'unmanaged'
              this.fetchVolumes()
            }
          }
        })
      } catch (error) {
        complete()
        this.$notifyError(error)
      }
    },
    onCloseImportVolumeForm () {
      if (this.importUnmanagedVolumeLoading) return
      this.selectedUnmanagedVolume = null
      this.showImportForm = false
      this.importForm.selectedAccountType = null
      this.importForm.selectedDomain = null
      this.importForm.selectedAccount = null
      this.importForm.selectedProject = null
    },
    onUnmanageVolumeAction () {
      const volumes = this.releasableVolumes
      if (!volumes.length || this.unmanagePending || !('unmanageVolume' in this.$store.getters.apis)) return
      this.$confirm({
        title: this.$t(volumes.length > 1 ? 'message.action.unmanage.volumes' : 'message.action.unmanage.volume'),
        okText: this.$t('label.ok'),
        okType: 'danger',
        content: volumes.map(volume => volume.name).join(', '),
        cancelText: this.$t('label.cancel'),
        onOk: () => this.unmanageVolumes(volumes)
      })
    },
    async unmanageVolumes (volumes) {
      this.unmanagePending = true
      const pool = this.poolId
      try {
        const results = await Promise.allSettled(volumes.map(async volume => {
          const response = await postAPI('unmanageVolume', { id: volume.id })
          return new Promise(resolve => {
            this.$pollJob({
              jobId: response.unmanagevolumeresponse.jobid,
              title: this.$t('label.unmanage.volume'),
              description: volume.name,
              loadingMessage: this.$t('label.in.progress'),
              catchMessage: this.$t('error.fetching.async.job.result'),
              successMessage: this.$t('message.success.unmanage.volume') + ' ' + volume.name,
              successMethod: () => resolve(true),
              errorMethod: () => resolve(false),
              catchMethod: () => resolve(false)
            })
          })
        }))
        results.filter(result => result.status === 'rejected').forEach(result => this.$notifyError(result.reason))
        if (this.poolId === pool) {
          if (this.canListUnmanaged && results.every(result => result.status === 'fulfilled' && result.value)) this.volumeList = 'unmanaged'
          await this.fetchVolumes()
        }
      } finally {
        this.unmanagePending = false
      }
    }
  }
}
</script>

<style scoped lang="less">
:deep(.ant-table-small) > .ant-table-content > .ant-table-body {
  margin: 0;
}

.import-form {
  width: 85vw;

  @media (min-width: 760px) {
    width: 500px;
  }

  display: flex;
  flex-direction: column;

  &__item {
    display: flex;
    flex-direction: column;
    width: 100%;
    margin-bottom: 10px;
  }

  &__label {
    display: flex;
    font-weight: bold;
    margin-bottom: 5px;
  }

  .required {
    margin-right: 2px;
    color: red;
    font-weight: bold;
  }
}
.volumes-card {
  height: 100%;
}
.volume-scope-form {
  display: grid;
  grid-template-columns: ~"repeat(auto-fit, minmax(min(100%, 180px), 1fr))";
  gap: 0 12px;
}

.volume-list-panels {
  margin-block-start: 16px;
}
.volumes-card-table {
  flex: none;
  min-block-size: 7.5rem;
}

.volumes-card-table :deep(.ant-table-container > :is(.ant-table-content, .ant-table-body)) {
  flex: 0 1 auto;
  min-block-size: 7.5rem;
  max-block-size: 45vh;
}
.volumes-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
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
.fetch-volumes-column {
  width: 50%;
  margin-left: 50%;
  padding-left: 24px;
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
