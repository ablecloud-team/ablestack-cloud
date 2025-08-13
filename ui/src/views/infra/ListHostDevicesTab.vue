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
    <a-tabs v-model:activeKey="activeKey" tab-position="top" @change="handleTabChange">
      <a-tab-pane key="1" :tab="$t('label.other.devices')">
        <a-input-search
          v-model:value="otherSearchQuery"
          :placeholder="$t('label.search')"
          style="width: 500px; margin-bottom: 15px; float: right;"
          @search="onOtherSearch"
          @change="onOtherSearch"
        />
        <a-table
          :columns="currentColumns"
          :dataSource="filteredOtherDevices"
          :pagination="false"
          size="small"
          :scroll="{ y: 1000 }">
          <template #headerCell="{ column }">
            <template v-if="column.key === 'hostDevicesText'">
              {{ $t('label.details') }}
            </template>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'hostDevicesName'">
              {{ record.hostDevicesName }}
            </template>
            <template v-if="column.key === 'hostDevicesText'"><span v-html="record.hostDevicesText"></span></template>
            <template v-if="column.key === 'vmName'">
              <a-spin v-if="vmNameLoading" size="small" />
              <span v-else>{{ vmNames[record.hostDevicesName] || $t(' ') }}</span>
            </template>
            <template v-if="column.key === 'action'">
              <div style="display: flex; align-items: center; gap: 8px;">
                <a-button
                  :type="isDeviceAssigned(record) ? 'danger' : 'primary'"
                  size="middle"
                  shape="circle"
                  :tooltip="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                  @click="isDeviceAssigned(record) ? deallocatePciDevice(record) : openPciModal(record)"
                  :loading="loading">
                  <template #icon>
                    <delete-outlined v-if="isDeviceAssigned(record)" />
                    <plus-outlined v-else />
                  </template>
                </a-button>
              </div>
              <span style="display: none;">{{ record.virtualmachineid }}</span>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="2" :tab="$t('label.hba.devices')">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px;">
          <div style="display: flex; align-items: center; gap: 10px;">
          </div>
          <a-input-search
            v-model:value="hbaSearchQuery"
            :placeholder="$t('label.search')"
            style="width: 500px;"
            @search="onHbaSearch"
            @change="onHbaSearch"
          />
        </div>
        <a-table
          :columns="currentColumns"
          :dataSource="filteredHbaDevices"
          :loading="loading"
          :pagination="false"
          size="small"
          :scroll="{ y: 1000 }">
          <template #headerCell="{ column }">
            <template v-if="column.key === 'hostDevicesText'">
              {{ $t('label.details') }}
            </template>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'hostDevicesName'">
              <div :style="{ paddingLeft: record.indent ? '20px' : '0px' }">
                <!-- 요약 행인 경우 vHBA 개수 표시 -->
                <template v-if="record.isSummary">
                  <div style="text-align: center; font-weight: bold; color: #1890ff; padding: 8px 0;">
                    {{ $t('label.total.vhba.count') }}: {{ record.vhbaCount }}
                  </div>
                </template>
                <!-- 일반 행인 경우 기존 로직 -->
                <template v-else>
                  <a-button
                    v-if="!record.isVhba"
                    type="text"
                    size="middle"
                    style="margin-right: 4px; padding: 0; width: 16px; height: 16px;"
                    @click.stop="toggleVhbaList(record)"
                    :loading="vhbaLoading[record.hostDevicesName]">
                    <template #icon>
                      <plus-outlined v-if="!expandedVhbaDevices[record.hostDevicesName]" />
                      <minus-outlined v-else />
                    </template>
                  </a-button>
                  <span v-if="record.isVhba" style="color: #1890ff;"> </span>
                  {{ record.hostDevicesName }}
                </template>
              </div>
            </template>
            <template v-if="column.key === 'hostDevicesText'">
              <div :style="{ paddingLeft: record.indent ? '20px' : '0px' }">
                <template v-if="!record.isSummary">
                  <span v-html="record.hostDevicesText"></span>
                </template>
              </div>
            </template>
            <template v-if="column.key === 'vmName'">
              <template v-if="!record.isSummary">
                <a-spin v-if="vmNameLoading" size="small" />
                <span v-else>{{ vmNames[record.hostDevicesName] || $t('') }}</span>
              </template>
            </template>
            <template v-if="column.key === 'vhba'">
              <template v-if="!record.isSummary">
                <div style="display: flex; align-items: center; gap: 8px;">
                  <!-- vHBA 생성 버튼 (물리 HBA에만 표시) -->
                  <a-button
                    v-if="!isVhbaDevice(record)"
                    type="primary"
                    size="middle"
                    shape="circle"
                    :tooltip="$t('label.create.vhba')"
                    @click="openCreateVhbaModal(record)"
                    :loading="loading"
                    :disabled="loading"
                    style="z-index: 1; position: relative;">
                    <template #icon>
                      <FormOutlined />
                    </template>
                  </a-button>
                  <!-- vHBA 삭제 버튼 (할당되지 않은 vHBA만) -->
                  <a-button
                    v-if="isVhbaDevice(record) && !isVhbaDeviceAssigned(record)"
                    type="danger"
                    size="middle"
                    shape="circle"
                    :tooltip="$t('label.delete.vhba')"
                    @click="deleteVhbaDevice(record)"
                    :loading="loading"
                    :disabled="loading"
                    style="z-index: 1; position: relative;">
                    <template #icon>
                      <delete-outlined />
                    </template>
                  </a-button>
                </div>
              </template>
            </template>
            <template v-if="column.key === 'action'">
              <template v-if="!record.isSummary">
                <div style="display: flex; align-items: center; gap: 8px;">
                  <!-- vHBA 할당/해제 버튼 (물리 HBA가 할당되지 않은 경우에만 표시) -->
                  <a-button
                    v-if="isVhbaDevice(record) && !isParentHbaAssigned(record)"
                    :type="isVhbaDeviceAssigned(record) ? 'danger' : 'primary'"
                    size="middle"
                    shape="circle"
                    :tooltip="isVhbaDeviceAssigned(record) ? $t('label.remove') : $t('label.allocate')"
                    @click="isVhbaDeviceAssigned(record) ? deallocateVhbaDevice(record) : openVhbaAllocationModal(record)"
                    :loading="loading"
                    :disabled="loading"
                    style="z-index: 1; position: relative;">
                    <template #icon>
                      <delete-outlined v-if="isVhbaDeviceAssigned(record)" />
                      <plus-outlined v-else />
                    </template>
                  </a-button>
                  <!-- 일반 HBA 할당/해제 버튼 -->
                  <a-button
                    v-if="!isVhbaDevice(record)"
                    :type="isDeviceAssigned(record) ? 'danger' : 'primary'"
                    size="middle"
                    shape="circle"
                    :tooltip="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                    @click="isDeviceAssigned(record) ? deallocateHbaDevice(record) : openHbaModal(record)"
                    :loading="loading"
                    :disabled="loading"
                    style="z-index: 1; position: relative;">
                    <template #icon>
                      <delete-outlined v-if="isDeviceAssigned(record)" />
                      <plus-outlined v-else />
                    </template>
                  </a-button>

                </div>
                <span style="display: none;">{{ record.virtualmachineid }}</span>
              </template>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="3" :tab="$t('label.usb.devices')">
        <a-input-search
          v-model:value="usbSearchQuery"
          :placeholder="$t('label.search')"
          style="width: 500px; margin-bottom: 15px; float: right;"
          @search="onUsbSearch"
          @change="onUsbSearch"
        />
        <a-table
          :columns="currentColumns"
          :dataSource="filteredUsbDevices"
          :loading="loading"
          :pagination="false"
          size="small"
          :scroll="{ y: 1000 }">
          <template #headerCell="{ column }">
            <template v-if="column.key === 'hostDevicesText'">
              {{ $t('label.details') }}
            </template>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'hostDevicesName'">
              {{ record.hostDevicesName }}
            </template>
            <template v-if="column.key === 'hostDevicesText'">
              <span v-html="record.hostDevicesText"></span>
            </template>
            <template v-if="column.dataIndex === 'vmName'">
              <a-spin v-if="vmNameLoading" size="small" />
              <span v-else>{{ vmNames[record.hostDevicesName] || '' }}</span>
            </template>
            <template v-if="column.key === 'action'">
              <div style="display: flex; align-items: center; gap: 8px;">
                <a-button
                  :type="isDeviceAssigned(record) ? 'danger' : 'primary'"
                  size="middle"
                  shape="circle"
                  :tooltip="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                  @click="isDeviceAssigned(record) ? deallocateUsbDevice(record) : openUsbModal(record)"
                  :loading="loading"
                  :disabled="loading"
                  style="z-index: 1; position: relative;">
                  <template #icon>
                    <delete-outlined v-if="isDeviceAssigned(record)" />
                    <plus-outlined v-else />
                  </template>
                </a-button>
              </div>
              <span style="display: none;">{{ record.virtualmachineid }}</span>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="4" :tab="$t('label.lun.devices')">
        <a-input-search
          v-model:value="lunSearchQuery"
          :placeholder="$t('label.search')"
          style="width: 500px; margin-bottom: 15px; float: right;"
          @search="onLunSearch"
          @change="onLunSearch"
        />
        <a-table
          :columns="currentColumns"
          :dataSource="filteredLunDevices"
          :loading="loading"
          :pagination="false"
          size="small"
          :scroll="{ y: 1000 }">
          <template #headerCell="{ column }">
            <template v-if="column.key === 'hostDevicesText'">
              {{ $t('label.details') }}
            </template>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'hostDevicesName'">{{ record.hostDevicesName }}</template>
            <template v-if="column.key === 'hostDevicesText'"><span v-html="formatHostDevicesText(record.hostDevicesText)"></span></template>
            <template v-if="column.dataIndex === 'vmName'">
              <a-spin v-if="vmNameLoading" size="small" />
              <span v-else>
                <template v-if="record.allocatedInOtherTab">
                  {{ record.vmName }}
                </template>
                <template v-else>
                  {{ record.vmName || vmNames[record.hostDevicesName] || '' }}
                </template>
              </span>
            </template>
            <template v-if="column.key === 'action'">
              <div style="display: flex; align-items: center; gap: 8px;">
                <template v-if="activeKey === '4'">
                  <a-button
                    v-if="!hasPartitionsFromText(record.hostDevicesText) && !isInUseFromText(record.hostDevicesText) && shouldShowAllocationButton(record)"
                    :type="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? 'danger' : 'primary'"
                    size="middle"
                    shape="circle"
                    :tooltip="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? $t('label.remove') : $t('label.create')"
                    @click="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? deallocateLunDevice(record) : openLunModal(record)"
                    :loading="loading">
                    <template #icon>
                      <delete-outlined v-if="(isDeviceAssigned(record) || record.allocatedInOtherTab)" />
                      <plus-outlined v-else />
                    </template>
                  </a-button>
                </template>
                <template v-else>
                  <a-button
                    v-if="shouldShowAllocationButton(record)"
                    :type="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? 'danger' : 'primary'"
                    size="middle"
                    shape="circle"
                    :tooltip="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? $t('label.remove') : $t('label.create')"
                    @click="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? deallocateLunDevice(record) : openLunModal(record)"
                    :loading="loading">
                    <template #icon>
                      <delete-outlined v-if="(isDeviceAssigned(record) || record.allocatedInOtherTab)" />
                      <plus-outlined v-else />
                    </template>
                  </a-button>
                </template>
                <!-- 다른 탭에서 할당된 경우 정보 표시 -->
                <span v-if="record.allocatedInOtherTab" style="color: #1890ff; font-size: 12px;">
                </span>
              </div>
              <span style="display: none;">{{ record.virtualmachineid }}</span>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="5" :tab="$t('label.scsi.devices')">
        <a-input-search
          v-model:value="scsiSearchQuery"
          :placeholder="$t('label.search')"
          style="width: 500px; margin-bottom: 15px; float: right;"
          @search="onScsiSearch"
          @change="onScsiSearch"
        />
        <a-table
          :columns="currentColumns"
          :dataSource="filteredScsiDevices"
          :loading="loading"
          :pagination="false"
          size="small"
          :scroll="{ y: 1000 }">
          <template #headerCell="{ column }">
            <template v-if="column.key === 'hostDevicesText'">
              {{ $t('label.details') }}
            </template>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'hostDevicesName'">
              {{ record.hostDevicesName }}
            </template>
            <template v-if="column.key === 'hostDevicesText'">
              <div style="white-space: pre-line;">{{ record.hostDevicesText }}</div>
            </template>
            <template v-if="column.dataIndex === 'vmName'">
              <a-spin v-if="vmNameLoading" size="small" />
              <span v-else>
                <template v-if="record.allocatedInOtherTab">
                  {{ record.vmName }}
                </template>
                <template v-else>
                  {{ record.vmName || vmNames[record.hostDevicesName] || '' }}
                </template>
              </span>
            </template>
            <template v-if="column.key === 'action'">
              <div style="display: flex; align-items: center; gap: 8px;">
                <a-button
                  v-if="shouldShowAllocationButton(record)"
                  :type="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? 'danger' : 'primary'"
                  size="middle"
                  shape="circle"
                  :tooltip="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? $t('label.remove') : $t('label.create')"
                  @click="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? deallocateScsiDevice(record) : openScsiModal(record)"
                  :loading="loading"
                  :disabled="loading"
                  style="z-index: 1; position: relative;">
                  <template #icon>
                    <delete-outlined v-if="(isDeviceAssigned(record) || record.allocatedInOtherTab)" />
                    <plus-outlined v-else />
                  </template>
                </a-button>
                <!-- 다른 탭에서 할당된 경우 정보 표시 -->
                <span v-if="record.allocatedInOtherTab" style="color: #1890ff; font-size: 12px;">
                </span>
              </div>
              <span style="display: none;">{{ record.virtualmachineid }}</span>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
    </a-tabs>
    <a-modal
      :visible="showAddModal"
      :title="$t('label.create.host.devices')"
      :v-html="$t('message.restart.vm.host.update.settings')"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      @cancel="closeAction">
      <HostDevicesTransfer
        v-if="activeKey === '1' && selectedResource"
        ref="hostDevicesTransfer"
        :resource="selectedResource"
        @close-action="closeAction"
        @allocation-completed="onAllocationCompleted"
        @device-allocated="handleDeviceAllocated" />
      <HostUsbDevicesTransfer
        v-else-if="activeKey === '3' && selectedResource"
        ref="hostUsbDevicesTransfer"
        :resource="selectedResource"
        @close-action="closeAction"
        @allocation-completed="onAllocationCompleted"
        @device-allocated="handleDeviceAllocated" />
      <HostLunDevicesTransfer
        v-else-if="activeKey === '4' && selectedResource"
        ref="hostLunDevicesTransfer"
        :resource="selectedResource"
        @close-action="closeAction"
        @allocation-completed="onAllocationCompleted"
        @device-allocated="handleDeviceAllocated" />
      <HostHbaDevicesTransfer
        v-else-if="activeKey === '2' && selectedResource"
        ref="hostHbaDevicesTransfer"
        :resource="selectedResource"
        @close-action="closeAction"
        @allocation-completed="onAllocationCompleted"
        @device-allocated="handleDeviceAllocated" />
      <HostScsiDevicesTransfer
        v-else-if="activeKey === '5' && selectedResource"
        ref="hostScsiDevicesTransfer"
        :resource="selectedResource"
        @close-action="closeAction"
        @allocation-completed="onAllocationCompleted"
        @device-allocated="handleDeviceAllocated" />
    </a-modal>

    <a-modal
      v-model:visible="showUsbDeleteModal"
      :title="`${vmNames[selectedUsbDevice?.hostDevicesName] || ''} ${$t('message.delete.device.allocation')}`"
      @ok="handleUsbDeviceDelete"
      @cancel="closeUsbDeleteModal"
    >
      <div>
        <p>{{ $t('message.confirm.delete.device') }}</p>
      </div>
    </a-modal>

    <a-modal
      v-model:visible="showPciDeleteModal"
      :title="`${selectedPciDevice?.vmName || ''} ${$t('message.delete.device.allocation1')}`"
      @ok="handlePciDeviceDelete"
      @cancel="closePciDeleteModal"
    >
      <div>
        <p>{{ $t('message.confirm.delete.device') }}</p>
      </div>
    </a-modal>

    <a-modal
      v-model:visible="showLunDeleteModal"
      :title="`${selectedLunDevice?.vmName || ''} ${$t('message.delete.device.allocation')}`"
      @ok="handleLunDeviceDelete"
      @cancel="closeLunDeleteModal"
    >
      <div>
        <p>{{ $t('message.confirm.delete.device') }}</p>
      </div>
    </a-modal>

    <!-- vHBA 생성 모달 -->
    <a-modal
      v-model:visible="showVhbaCreateModal"
      :title="$t('label.create.vhba')"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      @cancel="closeVhbaCreateModal">
      <div v-if="selectedHbaDevice">
        <p>
          {{ $t('message.confirm.create.vhba', { hba: selectedHbaDevice.hostDevicesName }) }}
        </p>
        <div style="text-align: right; margin-top: 20px;">
          <a-button @click="closeVhbaCreateModal" style="margin-right: 8px;">
            {{ $t('label.cancel') }}
          </a-button>
          <a-button
            type="primary"
            :loading="vhbaCreating"
            @click="createVhba">
            {{ $t('label.create') }}
          </a-button>
        </div>
      </div>
    </a-modal>

    <a-modal
      :visible="showVhbaAllocationModal"
      :title="$t('label.create.host.devices')"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      @cancel="closeVhbaAllocationModal">
      <HostVhbaDevicesTransfer
        v-if="selectedVhbaDevice"
        ref="hostVhbaDevicesTransfer"
        :resource="selectedVhbaDevice"
        @close-action="closeVhbaAllocationModal"
        @allocation-completed="onVhbaAllocationCompleted"
        @device-allocated="handleVhbaDeviceAllocated" />
    </a-modal>
  </div>
</template>

<script>
import { api } from '@/api'
import eventBus from '@/config/eventBus'
import { IdcardOutlined, PlusOutlined, DeleteOutlined, FormOutlined, MinusOutlined } from '@ant-design/icons-vue'
import HostDevicesTransfer from '@/views/storage/HostDevicesTransfer'
import HostUsbDevicesTransfer from '@/views/storage/HostUsbDevicesTransfer'
import HostLunDevicesTransfer from '@/views/storage/HostLunDevicesTransfer'
import HostHbaDevicesTransfer from '@/views/storage/HostHbaDevicesTransfer'
import HostScsiDevicesTransfer from '@/views/storage/HostScsiDevicesTransfer'
import HostVhbaDevicesTransfer from '@/views/storage/HostVhbaDevicesTransfer'

export default {
  name: 'ListHostDevicesTab',
  components: {
    IdcardOutlined,
    PlusOutlined,
    DeleteOutlined,
    FormOutlined,
    MinusOutlined,
    HostDevicesTransfer,
    HostUsbDevicesTransfer,
    HostLunDevicesTransfer,
    HostHbaDevicesTransfer,
    HostScsiDevicesTransfer,
    HostVhbaDevicesTransfer
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      dataItems: [],
      loading: false,
      showAddModal: false,
      selectedResource: null,
      searchQuery: '',
      activeKey: '1',
      usbSearchQuery: '',
      lunSearchQuery: '',
      otherSearchQuery: '',
      hbaSearchQuery: '',
      scsiSearchQuery: '',
      selectedDevices: [],
      selectedPciDevices: [],
      virtualmachines: [],
      showPciDeleteModal: false,
      selectedPciDevice: null,
      pciConfigs: {},
      vmNames: {},
      vmNameLoading: false,
      showUsbDeleteModal: false,
      selectedUsbDevice: null,
      showLunDeleteModal: false,
      selectedLunDevice: null,
      showVhbaCreateModal: false,
      selectedHbaDevice: null,
      vhbaForm: {
        hbaDevice: '',
        vhbaName: ''
      },
      vhbaCreating: false,
      showVhbaAllocationModal: false,
      selectedVhbaDevice: null,
      vhbaLoading: {},
      expandedVhbaDevices: {},
      vhbaDevicesData: {}
    }
  },
  computed: {
    tableSource () {
      return this.dataItems.map((item, index) => ({
        key: index,
        hostDevicesName: Array.isArray(item.hostDevicesNames) ? item.hostDevicesNames[0] : item.hostDevicesName,
        hostDevicesText: Array.isArray(item.hostDevicesTexts) ? item.hostDevicesTexts[0] : item.hostDevicesText,
        value: item.value,
        virtualmachineid: item.virtualmachineid,
        isAssigned: item.isAssigned,
        vmName: item.vmName || (this.vmNames[item.hostDevicesName] || '')
      }))
    },

    // 탭별 컬럼 설정
    currentColumns () {
      if (this.activeKey === '2') {
        // HBA 탭: vhba 컬럼 포함
        return [
          {
            key: 'hostDevicesName',
            dataIndex: 'hostDevicesName',
            title: this.$t('label.name'),
            width: '25%'
          },
          {
            key: 'hostDevicesText',
            dataIndex: 'hostDevicesText',
            title: this.$t('label.text'),
            width: '35%'
          },
          {
            key: 'vmName',
            dataIndex: 'vmName',
            title: this.$t('label.vmname'),
            width: '20%'
          },
          {
            key: 'action',
            dataIndex: 'action',
            title: this.$t('label.action'),
            width: '10%'
          },
          {
            key: 'vhba',
            dataIndex: 'vhba',
            title: this.$t('label.create.vhba'),
            width: '10%'
          }
        ]
      } else {
        // 다른 탭들: 기본 컬럼 + action 컬럼
        return [
          {
            key: 'hostDevicesName',
            dataIndex: 'hostDevicesName',
            title: this.$t('label.name'),
            width: '30%'
          },
          {
            key: 'hostDevicesText',
            dataIndex: 'hostDevicesText',
            title: this.$t('label.text'),
            width: '40%'
          },
          {
            key: 'vmName',
            dataIndex: 'vmName',
            title: this.$t('label.vmname'),
            width: '20%'
          },
          {
            key: 'action',
            dataIndex: 'action',
            title: this.$t('label.action'),
            width: '10%'
          }
        ]
      }
    },

    filteredOtherDevices () {
      const query = this.otherSearchQuery.toLowerCase()
      return this.tableSource.filter(item => {
        const deviceName = String(item.hostDevicesName || '')
        const deviceText = String(item.hostDevicesText || '')
        const isOther = !deviceName.toLowerCase().includes('usb') &&
                       !deviceName.toLowerCase().includes('lun')
        if (!query) return isOther
        return isOther && (
          deviceName.toLowerCase().includes(query) ||
          deviceText.toLowerCase().includes(query)
        )
      })
    },

    filteredUsbDevices () {
      if (!this.dataItems) return []

      const query = this.usbSearchQuery.toLowerCase()
      return this.dataItems.filter(item => {
        if (item.hostDevicesText && item.hostDevicesText.toLowerCase().includes('hub')) {
          return false
        }

        if (!query) return true
        return (
          (item.hostDevicesName && item.hostDevicesName.toLowerCase().includes(query)) ||
          (item.hostDevicesText && item.hostDevicesText.toLowerCase().includes(query))
        )
      })
    },

    filteredLunDevices () {
      const query = this.lunSearchQuery.toLowerCase()
      return this.tableSource.filter(item => {
        const deviceName = String(item.hostDevicesName || '')
        const deviceText = String(item.hostDevicesText || '')
        const isLun = deviceName.startsWith('/dev/')
        if (!query) return isLun
        return isLun && (
          deviceName.toLowerCase().includes(query) ||
          deviceText.toLowerCase().includes(query)
        )
      })
    },

    filteredHbaDevices () {
      const query = this.hbaSearchQuery.toLowerCase()
      const allHbaDevices = this.getHbaDevicesWithVhba()

      return allHbaDevices.filter(item => {
        if (!query) return true
        return (
          (item.hostDevicesName && item.hostDevicesName.toLowerCase().includes(query)) ||
          (item.hostDevicesText && item.hostDevicesText.toLowerCase().includes(query))
        )
      })
    },

    filteredScsiDevices () {
      const query = this.scsiSearchQuery.toLowerCase()
      return this.dataItems.filter(item => {
        const deviceName = String(item.hostDevicesName || '')
        const deviceText = String(item.hostDevicesText || '')
        const isScsi = deviceName.startsWith('/dev/sg')
        if (!query) return isScsi
        return isScsi && (
          deviceName.toLowerCase().includes(query) ||
          deviceText.toLowerCase().includes(query)
        )
      })
    },
    vhbaFormRules () {
      return {
        hbaDevice: [
          { required: true, message: this.$t('message.required.hba.device') }
        ],
        vhbaName: [
          { required: true, message: this.$t('message.required.vhba.name') },
          { min: 1, max: 50, message: this.$t('message.vhba.name.length') }
        ]
      }
    },

    totalVhbaCount () {
      // 현재 표시된 HBA 디바이스 목록에서 vHBA 개수 계산
      const allHbaDevices = this.getHbaDevicesWithVhba()
      const vhbaCount = allHbaDevices.filter(device => this.isVhbaDevice(device)).length

      return vhbaCount
    }
  },
  created () {
    this.fetchData()
    this.setupVMEventListeners()
    this.fetchHostInfo()
  },
  methods: {
    setupVMEventListeners () {
      const eventTypes = ['DestroyVM', 'ExpungeVM']

      eventTypes.forEach(eventType => {
        eventBus.emit('register-event', {
          eventType: eventType,
          callback: async (event) => {
            try {
              const vmId = event.id

              // 현재 호스트의 디바이스 할당 정보 조회
              const response = await api('listHostDevices', { id: this.resource.id })
              const devices = response.listhostdevicesresponse?.listhostdevices?.[0]

              if (devices?.vmallocations) {
                // 삭제된 VM에 할당된 디바이스 찾기
                const allocatedDevices = Object.entries(devices.vmallocations)
                  .filter(([_, allocatedVmId]) => {
                    return allocatedVmId === vmId
                  })
                  .map(([deviceName]) => deviceName)

                // 각 디바이스의 할당 해제
                for (const deviceName of allocatedDevices) {
                  try {
                    const response = await api('updateHostDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: deviceName,
                      virtualmachineid: null
                    })

                    if (!response || response.error) {
                      throw new Error(response?.error?.errortext || 'Failed to update host device')
                    }
                  } catch (error) {
                    console.error('Failed to update host device:', deviceName, error)
                    this.$notification.error({
                      message: this.$t('label.error'),
                      description: error.message || this.$t('message.update.host.device.failed')
                    })
                  }
                }

                if (allocatedDevices.length > 0) {
                  this.$message.info(this.$t('message.device.allocation.removed.vm.deleted'))
                  await this.fetchData()
                  await this.updateVmNames()
                }
              }
            } catch (error) {
              console.error('Error handling VM deletion event:', error)
            }
          }
        })
      })
    },
    async fetchData () {
      this.loading = true
      this.selectedDevices = []

      try {
        const response = await api('listHostDevices', { id: this.resource.id })
        const data = response.listhostdevicesresponse?.listhostdevices?.[0]

        if (data) {
          const vmAllocations = data.vmallocations || {}

          const vmIds = Object.values(vmAllocations)
            .filter(id => id)
            .map(id => id.toString())

          const vmNameMap = {}
          if (vmIds.length > 0) {
            for (const vmId of vmIds) {
              try {
                const vmResponse = await api('listVirtualMachines', {
                  id: vmId,
                  listall: true
                })
                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm) {
                  vmNameMap[vmId] = vm.displayname || vm.name
                }
              } catch (error) {
              }
            }
          }

          this.vmNames = vmNameMap

          this.dataItems = data.hostdevicesname.map((name, index) => {
            const vmId = vmAllocations[name] || null
            const vmName = vmId ? this.vmNames[vmId] : null

            return {
              hostDevicesName: name,
              hostDevicesText: data.hostdevicestext[index] || '',
              virtualmachineid: vmId,
              vmName: vmName,
              isAssigned: Boolean(vmId)
            }
          })

          this.selectedDevices = Object.keys(vmAllocations).filter(key => vmAllocations[key])
        } else {
          this.dataItems = []
          this.selectedDevices = []
        }
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.loading = false
      }
      await this.updateVmNames()
    },
    isDeviceAssigned (record) {
      // HBA 디바이스의 경우 고유한 디바이스명 사용
      const deviceName = this.activeKey === '4' ? record.hostDevicesName : record.hostDevicesName
      return record.virtualmachineid != null ||
             record.isAssigned ||
             this.selectedDevices.includes(deviceName) ||
             (this.activeKey === '4' && this.vmNames[record.hostDevicesName])
    },

    isVhbaDeviceAssigned (record) {
      // vHBA 디바이스의 할당 상태 확인
      if (!this.isVhbaDevice(record)) {
        return false
      }

      // 1. record의 직접적인 할당 상태 확인
      if (record.virtualmachineid != null || record.isAssigned) {
        return true
      }

      // 2. vmNames에서 할당 상태 확인
      if (this.vmNames[record.hostDevicesName]) {
        return true
      }

      // 3. vHBA 디바이스 데이터에서 할당 상태 확인
      if (record.parentHba && this.vhbaDevicesData[record.parentHba]) {
        const vhbaDevice = this.vhbaDevicesData[record.parentHba].find(
          vhba => vhba.hostDevicesName === record.hostDevicesName
        )
        if (vhbaDevice && (vhbaDevice.virtualmachineid != null || vhbaDevice.isAssigned)) {
          return true
        }
      }

      return false
    },

    // 물리 HBA가 할당되었는지 확인하는 메서드
    isParentHbaAssigned (record) {
      // vHBA 디바이스가 아니면 false 반환
      if (!this.isVhbaDevice(record) || !record.parentHba) {
        return false
      }

      // 부모 HBA 디바이스 찾기
      const parentHbaRecord = this.dataItems.find(item =>
        item.hostDevicesName === record.parentHba && !this.isVhbaDevice(item)
      )

      if (!parentHbaRecord) {
        return false
      }

      // 부모 HBA의 할당 상태 확인
      return this.isDeviceAssigned(parentHbaRecord)
    },
    openPciModal (record) {
      this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
      this.showAddModal = true
    },
    openUsbModal (record) {
      this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
      this.showAddModal = true
    },
    closeAction () {
      this.showAddModal = false
      this.selectedResource = null
      if (this.activeKey === '2') {
        this.fetchHbaDevices()
      } else if (this.activeKey === '3') {
        this.fetchUsbDevices()
      } else if (this.activeKey === '4') {
        this.fetchLunDevices()
      } else if (this.activeKey === '5') {
        this.fetchScsiDevices()
      } else {
        this.fetchData()
      }
    },
    onUsbSearch (e) {
      // USB 디바이스 검색 처리
    },
    onLunSearch () {
      // LUN 디바이스 검색 처리
    },
    onOtherSearch () {
      // 기타 디바이스 검색 처리
    },
    async handleDelete (record) {
      if (this.activeKey === '2') {
        this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
        this.showAddModal = true
      } else if (this.activeKey === '3') {
        this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
        this.showAddModal = true
      } else {
        try {
          const response = await api('listHostDevices', { id: this.resource.id })
          const devices = response.listhostdevicesresponse?.listhostdevices?.[0]
          const vmAllocations = devices?.vmallocations || {}
          const vmId = vmAllocations[record.hostDevicesName]

          if (vmId) {
            const vmResponse = await api('listVirtualMachines', {
              id: vmId,
              listall: true
            })
            const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

            // VM이 실행 중인 경우 할당 해제 불가
            if (vm && vm.state === 'Running') {
              this.$notification.warning({
                message: this.$t('label.warning'),
                description: this.$t('message.cannot.remove.device.vm.running')
              })
              return
            }

            record.vmName = vm?.name || vm?.displayname
          }

          this.selectedPciDevice = record
          this.showPciDeleteModal = true
          this.fetchPciConfigs(record)
        } catch (error) {
          console.error('Error fetching VM details:', error)
          this.$notifyError(error)
        }
      }
    },
    handleAllocationCompleted () {
      // 현재 활성 탭을 저장
      const currentActiveKey = this.activeKey

      if (this.activeKey === '2') {
        this.fetchHbaDevices()
      } else if (this.activeKey === '3') {
        setTimeout(() => {
          this.fetchUsbDevices()
        }, 700)
      } else if (this.activeKey === '4') {
        setTimeout(() => {
          this.fetchLunDevices()
        }, 700)
      } else if (this.activeKey === '5') {
        setTimeout(() => {
          this.fetchScsiDevices()
        }, 700)
        setTimeout(() => {
          this.fetchLunDevices()
        }, 1000)
      } else {
        this.fetchData()
      }

      this.showAddModal = false
      this.updateDataWithVmNames()

      // 현재 탭 유지
      this.$nextTick(() => {
        this.activeKey = currentActiveKey
      })
    },
    handleDeviceAllocated () {
      // 현재 활성 탭을 저장
      const currentActiveKey = this.activeKey

      if (this.activeKey === '2') {
        this.fetchHbaDevices()
      } else if (this.activeKey === '3') {
        this.fetchUsbDevices()
      } else if (this.activeKey === '4') {
        this.fetchLunDevices()
      } else if (this.activeKey === '5') {
        this.fetchScsiDevices()
        setTimeout(() => {
          this.fetchLunDevices()
        }, 500)
      } else {
        this.fetchData()
      }

      // 현재 탭 유지
      this.$nextTick(() => {
        this.activeKey = currentActiveKey
      })
    },

    async handleTabChange (activeKey) {
      this.activeKey = activeKey
      if (activeKey === '1') {
        await this.fetchData()
        await this.updateVmNames()
      } else if (activeKey === '2') {
        this.fetchHbaDevices() // HBA 탭 선택 시 호출
      } else if (activeKey === '3') {
        this.fetchUsbDevices() // USB 탭 선택 시 호출
      } else if (activeKey === '4') {
        this.fetchLunDevices() // LUN 탭 선택 시 호출
      } else if (activeKey === '5') {
        this.fetchScsiDevices() // SCSI 탭 선택 시 호출
      }
    },
    fetchUsbDevices () {
      this.loading = true
      api('listHostUsbDevices', {
        id: this.resource.id
      }).then(response => {
        if (response.listhostusbdevicesresponse?.listhostusbdevices?.[0]) {
          const usbData = response.listhostusbdevicesresponse.listhostusbdevices[0]

          const usbDevices = usbData.hostdevicesname.map((name, index) => ({
            key: index,
            hostDevicesName: name,
            hostDevicesText: usbData.hostdevicestext[index],
            virtualmachineid: (usbData.vmallocations && usbData.vmallocations[name]) || null,
            isAssigned: Boolean(usbData.vmallocations && usbData.vmallocations[name])
          }))

          this.dataItems = usbDevices
          this.updateUsbVmNames()
        } else {
          this.dataItems = []
        }
      }).catch(error => {
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.fetch.usb.devices')
        })
      }).finally(() => {
        this.loading = false
      })
    },
    fetchLunDevices () {
      this.loading = true
      api('listHostLunDevices', {
        id: this.resource.id
      }).then(async response => {
        if (response.listhostlundevicesresponse?.listhostlundevices?.[0]) {
          const lunData = response.listhostlundevicesresponse.listhostlundevices[0]
          const vmAllocations = lunData.vmallocations || {}
          // VM 이름 매핑
          const vmNameMap = {}
          for (const name in vmAllocations) {
            const vmId = vmAllocations[name]
            if (vmId) {
              const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
              const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
              if (vm) vmNameMap[name] = vm.displayname || vm.name
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNameMap }
          const lunDevices = lunData.hostdevicesname.map((name, index) => ({
            key: index,
            hostDevicesName: name,
            hostDevicesText: lunData.hostdevicestext[index].replace(/\n/g, '<br>'),
            virtualmachineid: (lunData.vmallocations && lunData.vmallocations[name]) || null,
            vmName: vmNameMap[name] || '',
            isAssigned: Boolean(lunData.vmallocations && lunData.vmallocations[name]),
            hasPartitions: (lunData.haspartitions && typeof lunData.haspartitions[name] !== 'undefined') ? lunData.haspartitions[name] : false
          }))
          for (const device of lunDevices) {
            // SCSI 탭에서 할당된 디바이스 확인
            const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
            const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]
            if (scsiData?.vmallocations) {
              for (let i = 0; i < scsiData.hostdevicesname.length; i++) {
                const scsiText = scsiData.hostdevicestext[i]
                const deviceMatch = scsiText.match(/Device:\s*(\/dev\/[^\s\n]+)/)

                if (deviceMatch && deviceMatch[1] === device.hostDevicesName) {
                  const sgDevice = scsiData.hostdevicesname[i]

                  const allocatedVmId = scsiData.vmallocations[sgDevice]
                  if (allocatedVmId) {
                    // VM 정보 가져오기
                    const vmResponse = await api('listVirtualMachines', { id: allocatedVmId, listall: true })
                    const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

                    if (vm) {
                      device.allocatedInOtherTab = {
                        isAllocated: true,
                        vmName: vm.displayname || vm.name,
                        vmId: allocatedVmId,
                        tabType: 'SCSI'
                      }
                      device.vmName = vm.displayname || vm.name
                      device.virtualmachineid = allocatedVmId
                      device.isAssigned = true
                    }
                    break
                  } else {
                  }
                }
              }
            } else {
            }

            if (!device.isAssigned && !device.allocatedInOtherTab) {
            }
          }

          // LUN 디바이스만 필터링하여 설정
          this.dataItems = lunDevices.filter(device =>
            device.hostDevicesName && device.hostDevicesName.startsWith('/dev/') &&
            !device.hostDevicesName.startsWith('/dev/sg')
          )
        } else {
          this.dataItems = []
        }
      }).catch(error => {
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.fetch.lun.devices')
        })
      }).finally(() => {
        this.loading = false
      })
      this.updateDataWithVmNames()
    },
    async handlePciDeviceDelete () {
      try {
        const response = await api('listHostDevices', { id: this.resource.id })
        const devices = response.listhostdevicesresponse?.listhostdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[this.selectedPciDevice.hostDevicesName]

        const vmResponse = await api('listVirtualMachines', {
          id: vmId,
          listall: true,
          details: 'all'
        })
        const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

        // VM이 실행 중인 경우 할당 해제 불가
        if (vm && vm.state === 'Running') {
          this.$notification.warning({
            message: this.$t('label.warning'),
            description: this.$t('message.cannot.remove.device.vm.running')
          })
          this.showPciDeleteModal = false
          this.selectedPciDevice = null
          this.pciConfigs = {}
          return
        }

        const params = { id: vm.id }

        // XML 설정 찾아서 제거
        Object.entries(vm.details || {}).forEach(([key, value]) => {
          if (key.startsWith('extraconfig-') && value.includes('<hostdev')) {
            const [pciAddress] = this.selectedPciDevice.hostDevicesName.split(' ')
            const [bus, slotFunction] = pciAddress.split(':')
            const [slot, func] = slotFunction.split('.')
            const pattern = `bus='0x${bus}' slot='0x${slot}' function='0x${func}'`

            if (!value.includes(pattern)) {
              params[`details[0].${key}`] = value
            }
          } else if (!key.startsWith('extraconfig-')) {
            params[`details[0].${key}`] = value
          }
        })

        // 먼저 VM의 extraconfig를 업데이트
        await api('updateVirtualMachine', params)

        // 그 다음 호스트 디바이스 할당 해제
        await api('updateHostDevices', {
          hostid: this.resource.id,
          hostdevicesname: this.selectedPciDevice.hostDevicesName,
          virtualmachineid: null
        })

        this.$message.success(this.$t('message.success.remove.allocation'))
        this.showPciDeleteModal = false
        this.selectedPciDevice = null
        this.pciConfigs = {}
        this.fetchData()

        // eventBus 대신 emit 사용
        this.$emit('refresh-vm-list')
      } catch (error) {
        this.$notifyError(error)
      }
    },
    showConfirmModal (device) {
      if (device.virtualmachineid) {
        api('listVirtualMachines', {
          id: device.virtualmachineid,
          listall: true
        }).then(response => {
          const vm = response?.listvirtualmachinesresponse?.virtualmachine?.[0]
          if (vm && vm.state === 'Running') {
            this.$notification.warning({
              message: this.$t('label.warning'),
              description: this.$t('message.cannot.remove.device.vm.running')
            })
            return
          }
          this.selectedPciDevice = device
          this.showPciDeleteModal = true
        }).catch(error => {
          this.$notifyError(error)
        })
      } else {
        this.selectedPciDevice = device
        this.showPciDeleteModal = true
      }
    },
    async updateDataWithVmNames () {
      try {
        const response = await api('listHostDevices', { id: this.resource.id })
        const data = response.listhostdevicesresponse?.listhostdevices?.[0]

        if (data && data.vmallocations) {
          const vmIds = [...new Set(Object.values(data.vmallocations))].filter(Boolean)
          const vmNameMap = await this.getVmNames(vmIds)
          this.dataItems = this.dataItems.map(item => ({
            ...item,
            vmName: item.virtualmachineid ? vmNameMap[item.virtualmachineid] : ''
          }))
        }
      } catch (error) {
      }
    },
    async updateVmNames () {
      this.vmNameLoading = true
      try {
        const response = await api('listHostDevices', { id: this.resource.id })
        const devices = response.listhostdevicesresponse?.listhostdevices?.[0]

        if (devices?.vmallocations) {
          const vmNamesMap = {}
          const entries = Object.entries(devices.vmallocations)
          const processedDevices = new Set()

          for (const [deviceName, vmId] of entries) {
            if (vmId && !processedDevices.has(deviceName)) {
              try {
                const vmResponse = await api('listVirtualMachines', {
                  id: vmId,
                  listall: true
                })

                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm) {
                  vmNamesMap[deviceName] = vm.name || vm.displayname
                } else {
                  try {
                    const updateResponse = await api('updateHostDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: deviceName,
                      virtualmachineid: null,
                      isattach: false
                    })

                    if (!updateResponse || updateResponse.error) {
                      throw new Error(updateResponse?.error?.errortext || 'Failed to update host device')
                    }

                    vmNamesMap[deviceName] = this.$t(' ')
                    processedDevices.add(deviceName)

                    // UI 업데이트 (모달 없이)
                    this.dataItems = this.dataItems.map(item => {
                      if (item.hostDevicesName === deviceName) {
                        return {
                          ...item,
                          virtualmachineid: null,
                          isAssigned: false
                        }
                      }
                      return item
                    })
                  } catch (error) {
                    console.error('Failed to update host device:', deviceName, error)
                    this.$notification.error({
                      message: this.$t('label.error'),
                      description: error.message || this.$t('message.update.host.device.failed')
                    })
                  }
                }
              } catch (error) {
                console.error('Error processing device:', deviceName, error)
                vmNamesMap[deviceName] = this.$t(' ')
              }
            }
          }
          this.vmNames = vmNamesMap
          if (processedDevices.size > 0) {
            await this.fetchData()
          }
        }
      } catch (error) {
        console.error('Error in updateVmNames:', error)
      } finally {
        this.vmNameLoading = false
      }
    },
    async updateUsbVmNames () {
      this.vmNameLoading = true
      try {
        const response = await api('listHostUsbDevices', { id: this.resource.id })
        const devices = response.listhostusbdevicesresponse?.listhostusbdevices?.[0]

        if (devices?.vmallocations) {
          const vmNamesMap = {}
          const entries = Object.entries(devices.vmallocations)

          for (const [deviceName, vmId] of entries) {
            if (vmId) {
              try {
                const vmResponse = await api('listVirtualMachines', {
                  id: vmId,
                  listall: true
                })

                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm) {
                  vmNamesMap[deviceName] = vm.name || vm.displayname
                } else {
                  vmNamesMap[deviceName] = this.$t(' ')
                }
              } catch (error) {
                console.error('Error fetching VM name for USB device:', deviceName, error)
                vmNamesMap[deviceName] = this.$t(' ')
              }
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNamesMap }
        }
      } catch (error) {
        console.error('Error in updateUsbVmNames:', error)
      } finally {
        this.vmNameLoading = false
      }
    },
    // 컴포넌트가 제거될 때 이벤트 리스너도 제거
    beforeDestroy () {
      const eventTypes = ['DestroyVM', 'ExpungeVM']
      eventTypes.forEach(eventType => {
        eventBus.emit('unregister-event', {
          eventType: eventType
        })
      })
    },
    async deallocateUsbDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }

      this.loading = true
      const hostDevicesName = record.hostDevicesName

      try {
        // 1. USB 디바이스의 현재 할당 상태 확인
        const response = await api('listHostUsbDevices', {
          id: this.resource.id
        })
        const devices = response.listhostusbdevicesresponse?.listhostusbdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[hostDevicesName]

        if (!vmId) {
          console.error('No VM allocation found for device:', hostDevicesName)
          throw new Error('No VM allocation found for this device')
        }

        // 2. 할당 해제 확인 및 실행
        const vmName = this.vmNames[hostDevicesName] || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            const xmlConfig = this.generateXmlUsbConfig(hostDevicesName)
            const detachResponse = await api('updateHostUsbDevices', {
              hostid: this.resource.id,
              hostdevicesname: hostDevicesName,
              virtualmachineid: null,
              currentvmid: vmId,
              xmlconfig: xmlConfig,
              isattach: false
            })

            if (!detachResponse || detachResponse.error) {
              console.error('Failed to detach USB device:', detachResponse?.error?.errortext)
              throw new Error(detachResponse?.error?.errortext || 'Failed to detach USB device')
            }

            this.dataItems = this.dataItems.map(item =>
              item.hostDevicesName === hostDevicesName
                ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false }
                : item
            )

            this.$message.success(this.$t('message.success.remove.allocation'))
            await this.fetchUsbDevices()
            this.vmNames = {}
            this.$emit('device-allocated')
            this.$emit('allocation-completed')
            this.$emit('close-action')
          },
          onCancel () {
          }
        })
      } catch (error) {
        console.error('Error during USB device deallocation:', error)
        this.$notifyError(error.message || 'Failed to deallocate USB device')
      } finally {
        this.loading = false
      }
    },

    generateXmlUsbConfig (hostDeviceName) {
      const match = hostDeviceName.match(/(\d+)\D+(\d+)/)
      let bus = '0x001'
      let device = '0x01'
      if (match) {
        bus = '0x' + parseInt(match[1], 10).toString(16).padStart(3, '0')
        device = '0x' + parseInt(match[2], 10).toString(16).padStart(2, '0')
      }
      console.log(bus, device)
      return `
        <hostdev mode='subsystem' type='usb'>
          <source>
            <address type='usb' bus='${bus}' device='${device}' />
          </source>
        </hostdev>
      `.trim()
    },
    generateXmlLunConfig (hostDeviceName) {
      let targetDev = 'sdc'
      const match = hostDeviceName.match(/\/dev\/([a-z]+[a-z0-9]*)$/)
      if (match) {
        targetDev = match[1]
      } else {
        // 기본값
        targetDev = 'sdc'
      }
      return `
        <disk type='block' device='lun'>
          <driver name='qemu' type='raw'/>
          <source dev='${hostDeviceName}'/>
          <target dev='${targetDev}' bus='scsi'/>
          <address type='drive' controller='0' bus='0' target='1' unit='0'/>
        </disk>
      `.trim()
    },

    generateScsiXmlConfig (hostDeviceName) {
      // SCSI 디바이스 이름에서 SCSI 주소 추출 (예: /dev/sg33 -> [host:bus:target:unit])
      const scsiAddressMatch = hostDeviceName.match(/\/dev\/sg(\d+)/)
      if (!scsiAddressMatch) {
        throw new Error(`Invalid SCSI device name: ${hostDeviceName}`)
      }

      const sgNumber = parseInt(scsiAddressMatch[1])

      // SCSI 디바이스 정보에서 실제 SCSI 주소 가져오기
      // lsscsi -g 명령 결과에서 해당 sg 디바이스의 SCSI 주소를 찾아야 함
      // 여기서는 기본값을 사용하거나, 실제 구현에서는 백엔드에서 제공하는 정보를 사용

      // 기본 SCSI 주소 (실제로는 백엔드에서 제공해야 함)
      const host = 0
      const bus = 0
      const target = sgNumber % 256
      const unit = 0

      return `
        <hostdev mode='subsystem' type='scsi' rawio='yes'>
          <source>
            <adapter name='scsi_host${host}'/>
            <address bus='${bus}' target='${target}' unit='${unit}'/>
          </source>
        </hostdev>
      `.trim()
    },
    async handleUsbDeviceDelete () {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }

      this.loading = true
      const hostDevicesName = this.selectedUsbDevice.hostDevicesName

      try {
        const response = await api('listHostUsbDevices', {
          id: this.resource.id
        })
        const devices = response.listhostusbdevicesresponse?.listhostusbdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[hostDevicesName]

        if (!vmId) {
          console.error('No VM allocation found for device:', hostDevicesName)
          throw new Error('No VM allocation found for this device')
        }

        const xmlConfig = this.generateXmlUsbConfig(hostDevicesName)
        const detachResponse = await api('updateHostUsbDevices', {
          hostid: this.resource.id,
          hostdevicesname: hostDevicesName,
          virtualmachineid: vmId,
          xmlconfig: xmlConfig,
          isattach: false
        })

        if (!detachResponse || detachResponse.error) {
          // console.error('Failed to detach USB device:', detachResponse?.error?.errortext)
          throw new Error(detachResponse?.error?.errortext || 'Failed to detach USB device')
        }

        this.$message.success(this.$t('message.success.remove.allocation'))
        this.$emit('device-allocated')
        this.$emit('allocation-completed')
        this.$emit('close-action')
      } catch (error) {
        console.error('Error during USB device deallocation:', error)
        this.$notifyError(error.message || 'Failed to deallocate USB device')
      } finally {
        this.loading = false
      }
    },
    closeUsbDeleteModal () {
      this.showUsbDeleteModal = false
      this.selectedUsbDevice = null
    },
    async deallocateLunDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.loading = true
      const hostDevicesName = record.hostDevicesName
      try {
        let vmId = null
        let vmName = 'Unknown VM'
        let isScsiAllocated = false

        // 다른 탭에서 할당된 경우
        if (record.allocatedInOtherTab) {
          vmId = record.allocatedInOtherTab.vmId
          vmName = record.allocatedInOtherTab.vmName
          isScsiAllocated = record.allocatedInOtherTab.tabType === 'SCSI'
          // allocatedInOtherTab에 vmId가 없는 경우, 실제 API에서 확인
          if (!vmId) {
            if (isScsiAllocated) {
              // SCSI 탭에서 할당 정보 확인
              const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
              const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

              if (scsiData?.vmallocations) {
                // SCSI 디바이스 중에서 현재 LUN 디바이스와 매핑되는 것 찾기
                for (let i = 0; i < scsiData.hostdevicesname.length; i++) {
                  const scsiText = scsiData.hostdevicestext[i]
                  const deviceMatch = scsiText.match(/Device:\s*(\/dev\/[^\s\n]+)/)

                  if (deviceMatch && deviceMatch[1] === hostDevicesName) {
                    const sgDevice = scsiData.hostdevicesname[i]
                    vmId = scsiData.vmallocations[sgDevice]
                    break
                  }
                }
              }
            }
          }
        } else {
          // 현재 탭에서 할당된 경우
          const response = await api('listHostLunDevices', {
            id: this.resource.id
          })
          const devices = response.listhostlundevicesresponse?.listhostlundevices?.[0]
          const vmAllocations = devices?.vmallocations || {}
          vmId = vmAllocations[hostDevicesName]
          vmName = this.vmNames[hostDevicesName] || 'Unknown VM'

          if (!vmId) {
            try {
              const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
              const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

              if (scsiData?.vmallocations) {
                // SCSI 디바이스 중에서 현재 LUN 디바이스와 매핑되는 것 찾기
                for (let i = 0; i < scsiData.hostdevicesname.length; i++) {
                  const scsiText = scsiData.hostdevicestext[i]
                  const deviceMatch = scsiText.match(/Device:\s*(\/dev\/[^\s\n]+)/)

                  if (deviceMatch && deviceMatch[1] === hostDevicesName) {
                    const sgDevice = scsiData.hostdevicesname[i]
                    vmId = scsiData.vmallocations[sgDevice]
                    if (vmId) {
                      isScsiAllocated = true
                      // VM 이름도 가져오기
                      try {
                        const vmResponse = await api('listVirtualMachines', {
                          id: vmId,
                          listall: true
                        })
                        const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                        if (vm) {
                          vmName = vm.name || vm.displayname
                        }
                      } catch (vmError) {
                        // VM 이름 조회 실패
                      }
                      break
                    }
                  }
                }
              }
            } catch (scsiError) {
              // SCSI 디바이스 조회 실패
            }
          }
          if (!vmId) {
            // 더 자세한 에러 메시지 제공
            let errorMessage = `VM 할당 정보를 찾을 수 없습니다: ${hostDevicesName}`
            if (record.allocatedInOtherTab) {
              errorMessage += ` (다른 탭에서 할당됨: ${record.allocatedInOtherTab.tabType})`
            }
            throw new Error(errorMessage)
          }

          // 2. 할당 해제 확인 및 실행
          this.$confirm({
            title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
            content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
            onOk: async () => {
              try {
                // 다른 탭에서 할당된 경우 SCSI 디바이스 해제
                if (isScsiAllocated) {
                  // SCSI 디바이스에서 해당 LUN 디바이스 찾기
                  const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
                  const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

                  if (!scsiData) {
                    throw new Error('Failed to fetch SCSI devices data')
                  }

                  let scsiDeviceFound = false
                  for (let i = 0; i < scsiData.hostdevicesname.length; i++) {
                    const scsiText = scsiData.hostdevicestext[i]
                    const deviceMatch = scsiText.match(/Device:\s*(\/dev\/[^\s\n]+)/)

                    if (deviceMatch && deviceMatch[1] === hostDevicesName) {
                      const sgDevice = scsiData.hostdevicesname[i]

                      // SCSI 디바이스 해제
                      const xmlConfig = this.generateScsiXmlConfig(sgDevice)

                      const detachResponse = await api('updateHostScsiDevices', {
                        hostid: this.resource.id,
                        hostdevicesname: sgDevice,
                        virtualmachineid: null,
                        currentvmid: vmId,
                        xmlconfig: xmlConfig,
                        isattach: false
                      })

                      if (!detachResponse || detachResponse.error) {
                        console.error('SCSI 디바이스 해제 실패:', detachResponse?.error)
                        throw new Error(detachResponse?.error?.errortext || 'Failed to detach SCSI device')
                      }
                      scsiDeviceFound = true
                      break
                    }
                  }

                  if (!scsiDeviceFound) {
                    throw new Error(`SCSI device mapping not found for LUN device: ${hostDevicesName}`)
                  }
                } else {
                  // 현재 탭에서 할당된 경우 LUN 디바이스 해제
                  const xmlConfig = this.generateXmlLunConfig(hostDevicesName)
                  const detachResponse = await api('updateHostLunDevices', {
                    hostid: this.resource.id,
                    hostdevicesname: hostDevicesName,
                    virtualmachineid: null,
                    currentvmid: vmId,
                    xmlconfig: xmlConfig,
                    isattach: false
                  })

                  if (!detachResponse || detachResponse.error) {
                    console.error('LUN 디바이스 해제 실패:', detachResponse?.error)
                    throw new Error(detachResponse?.error?.errortext || 'Failed to detach LUN device')
                  }
                }

                // UI 상태 업데이트
                this.dataItems = this.dataItems.map(item =>
                  item.hostDevicesName === hostDevicesName
                    ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false, allocatedInOtherTab: null }
                    : item
                )

                this.$message.success(this.$t('message.success.remove.allocation'))

                // 현재 탭만 새로고침 (LUN 탭에서 해제한 경우 LUN 탭만)
                await this.fetchLunDevices()

                this.vmNames = {}
                this.$emit('device-allocated')
                // allocation-completed 이벤트 제거 (탭 전환 방지)
                this.$emit('close-action')
              } catch (error) {
                console.error('디바이스 해제 중 오류:', error)
                this.$notification.error({
                  message: this.$t('label.error'),
                  description: error.message || 'Failed to deallocate device'
                })
              }
            },
            onCancel () {
            }
          })
        }
      } catch (error) {
        console.error('deallocateLunDevice 오류:', error)
        this.$notifyError(error.message || 'Failed to deallocate LUN device')
      } finally {
        this.loading = false
      }
    },
    closeLunDeleteModal () {
      this.showLunDeleteModal = false
      this.selectedLunDevice = null
    },
    async handleLunDeviceDelete () {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.loading = true
      const hostDevicesName = this.selectedLunDevice.hostDevicesName
      try {
        const response = await api('updateHostLunDevices', {
          hostid: this.resource.id,
          hostdevicesname: hostDevicesName,
          virtualmachineid: null
        })
        if (!response || response.error) {
          throw new Error(response?.error?.errortext || 'Failed to detach LUN device')
        }
        this.$message.success(this.$t('message.success.remove.allocation'))
        this.showLunDeleteModal = false
        this.selectedLunDevice = null
        await this.fetchLunDevices()
        this.$emit('device-allocated')
        // allocation-completed 이벤트 제거 (탭 전환 방지)
        this.$emit('close-action')
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate LUN device')
      } finally {
        this.loading = false
      }
    },
    openLunModal (record) {
      this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
      this.showAddModal = true
    },
    async deallocatePciDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.loading = true
      const hostDevicesName = record.hostDevicesName
      try {
        // 1. PCI 디바이스의 현재 할당 상태 확인
        const response = await api('listHostDevices', {
          id: this.resource.id
        })
        const devices = response.listhostdevicesresponse?.listhostdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[hostDevicesName]
        if (!vmId) {
          throw new Error('No VM allocation found for this device')
        }

        // 2. VM 상태 확인 - 실행 중인 경우 할당 해제 불가
        const vmResponse = await api('listVirtualMachines', {
          id: vmId,
          listall: true
        })
        const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

        if (vm && vm.state === 'Running') {
          this.$notification.warning({
            message: this.$t('label.warning'),
            description: this.$t('message.cannot.remove.device.vm.running')
          })
          this.loading = false
          return
        }

        // 3. 할당 해제 확인 및 실행
        const vmName = this.vmNames[hostDevicesName] || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            // VM extraconfig에서 PCI 관련 설정 제거
            const vmDetailResponse = await api('listVirtualMachines', {
              id: vmId,
              listall: true,
              details: 'all'
            })
            const vmDetails = vmDetailResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]
            const params = { id: vmDetails.id }
            Object.entries(vmDetails.details || {}).forEach(([key, value]) => {
              if (key.startsWith('extraconfig-') && value.includes('<hostdev')) {
                const [pciAddress] = hostDevicesName.split(' ')
                const [bus, slotFunction] = pciAddress.split(':')
                const [slot, func] = slotFunction.split('.')
                const pattern = `bus='0x${bus}' slot='0x${slot}' function='0x${func}'`
                if (!value.includes(pattern)) {
                  params[`details[0].${key}`] = value
                }
              } else if (!key.startsWith('extraconfig-')) {
                params[`details[0].${key}`] = value
              }
            })
            await api('updateVirtualMachine', params)
            // PCI 디바이스 할당 해제
            await api('updateHostDevices', {
              hostid: this.resource.id,
              hostdevicesname: hostDevicesName,
              virtualmachineid: null
            })
            this.dataItems = this.dataItems.map(item =>
              item.hostDevicesName === hostDevicesName
                ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false }
                : item
            )
            // 해당 디바이스만 vmNames에서 삭제
            if (this.vmNames[hostDevicesName]) {
              delete this.vmNames[hostDevicesName]
            }
            this.$message.success(this.$t('message.success.remove.allocation'))
            await this.fetchData()
            this.$emit('device-allocated')
            this.$emit('allocation-completed')
            this.$emit('close-action')
          },
          onCancel () {}
        })
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate PCI device')
      } finally {
        this.loading = false
      }
    },
    closePciDeleteModal () {
      this.showPciDeleteModal = false
      this.selectedPciDevice = null
    },
    onHbaSearch () {
      // HBA 디바이스 검색은 computed 속성에서 자동으로 처리됨
    },

    onScsiSearch () {
      // SCSI 디바이스 검색은 computed 속성에서 자동으로 처리됨
    },
    async fetchHbaDevices () {
      this.loading = true
      try {
        const response = await api('listHostHbaDevices', {
          id: this.resource.id
        })

        if (response.listhosthbadevicesresponse?.listhosthbadevices?.[0]) {
          const hbaData = response.listhosthbadevicesresponse.listhosthbadevices[0]
          const vmAllocations = hbaData.vmallocations || {}

          // VM 이름 매핑
          const vmNameMap = {}
          for (const name in vmAllocations) {
            const vmId = vmAllocations[name]
            if (vmId) {
              const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
              const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
              if (vm) vmNameMap[name] = vm.displayname || vm.name
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNameMap }

          // vHBA 디바이스의 할당 상태도 확인
          const vhbaAllocations = {}
          for (const name in vmAllocations) {
            const vmId = vmAllocations[name]
            if (vmId) {
              vhbaAllocations[name] = vmId
            }
          }

          // 백엔드에서 받은 deviceTypes와 parentHbaNames 정보 활용
          const deviceTypes = hbaData.devicetypes || []
          const parentHbaNames = hbaData.parenthbanames || []

          // 물리 HBA와 vHBA를 계층 구조로 구성
          const hbaDevices = []
          const physicalHbaMap = new Map() // 물리 HBA 이름 -> 인덱스 매핑

          hbaData.hostdevicesname.forEach((name, index) => {
            let deviceText = hbaData.hostdevicestext[index]
            const vmId = vmAllocations[name] || null
            const vmName = vmNameMap[name] || ''
            const isAssigned = Boolean(vmAllocations[name])
            const deviceType = deviceTypes[index] || 'physical'
            const parentHbaName = parentHbaNames[index] || ''

            // "Virtual HBA: scsi_host49" 및 "Virtual HBA Device" 정보 제거
            deviceText = deviceText.replace(/Virtual HBA:\s*[^\s\n]+/g, '').trim()
            deviceText = deviceText.replace(/Virtual HBA Device/g, '').trim()

            // scsi_host 숫자 패턴 제거 (예: scsi_host13, scsi_host14 등)
            deviceText = deviceText.replace(/scsi_host\d+/g, '').trim()

            // 줄바꿈 문자를 HTML <br> 태그로 변환
            deviceText = deviceText.replace(/\n/g, '<br>')

            // RAID 컨트롤러 필터링
            const isRaidController = deviceText.toLowerCase().includes('raid') ||
                                   deviceText.toLowerCase().includes('sas') ||
                                   deviceText.toLowerCase().includes('broadcom') ||
                                   deviceText.toLowerCase().includes('lsi')

            if (isRaidController) {
              return // 이 디바이스는 건너뛰기
            }

            if (deviceType === 'physical') {
              // 물리 HBA 디바이스 추가
              const hbaDevice = {
                key: `hba-${index}`,
                hostDevicesName: name,
                hostDevicesText: deviceText,
                virtualmachineid: vmId,
                vmName: vmName,
                isAssigned: isAssigned,
                isPhysicalHba: true,
                isVhba: false,
                hasChildren: false,
                deviceType: 'physical',
                parentHba: null
              }

              hbaDevices.push(hbaDevice)
              physicalHbaMap.set(name, hbaDevices.length - 1)
            } else if (deviceType === 'virtual') {
              // vHBA 디바이스 추가
              const vhbaDevice = {
                key: `vhba-${index}`,
                hostDevicesName: name,
                hostDevicesText: deviceText,
                virtualmachineid: vmId,
                vmName: vmName,
                isAssigned: isAssigned,
                isPhysicalHba: false,
                isVhba: true,
                deviceType: 'virtual',
                parentHba: parentHbaName,
                indent: true
              }

              hbaDevices.push(vhbaDevice)

              // 부모 HBA가 있으면 hasChildren 플래그 설정
              if (parentHbaName && physicalHbaMap.has(parentHbaName)) {
                const parentIndex = physicalHbaMap.get(parentHbaName)
                hbaDevices[parentIndex].hasChildren = true
              }
            }
          })

          this.dataItems = hbaDevices
          // 가상머신 이름을 업데이트합니다
          this.updateHbaVmNames()

          // vHBA 개수 업데이트를 위해 강제로 computed 속성 재계산
          this.$nextTick(() => {
            this.$forceUpdate()
            // 추가로 setTimeout을 사용하여 DOM 업데이트 보장
            setTimeout(() => {
              this.$forceUpdate()
            }, 100)
          })
        } else {
          this.dataItems = []
        }
      } catch (error) {
        console.error('Error fetching HBA devices:', error)
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.fetch.hba.devices')
        })
        this.dataItems = []
      } finally {
        this.loading = false
      }
    },

    findVhbaDevicesForHba (physicalHbaName, hbaData) {
      // 물리 HBA에서 생성된 vHBA 디바이스들을 찾는 로직
      const vhbaDevices = []
      const vmAllocations = hbaData.vmallocations || {}

      hbaData.hostdevicesname.forEach((name, index) => {
        const deviceText = hbaData.hostdevicestext[index]

        // vHBA 디바이스인지 확인
        if (this.isVhbaDevice({ hostDevicesName: name, hostDevicesText: deviceText })) {
          // 물리 HBA와 vHBA의 관계를 확인하는 로직
          // 여기서는 간단히 이름 패턴으로 판단 (실제로는 더 정확한 로직 필요)
          if (name.includes(physicalHbaName) || this.isVhbaRelatedToHba(name, physicalHbaName)) {
            const vmId = vmAllocations[name] || null
            const vmName = this.vmNames[name] || ''

            vhbaDevices.push({
              hostDevicesName: name,
              hostDevicesText: deviceText,
              virtualmachineid: vmId,
              vmName: vmName,
              isAssigned: Boolean(vmId)
            })
          }
        }
      })

      return vhbaDevices
    },

    isVhbaRelatedToHba (vhbaName, hbaName) {
      return vhbaName.toLowerCase().includes('vhba') &&
             (vhbaName.includes(hbaName) || vhbaName.includes(hbaName.replace(/[^0-9]/g, '')))
    },

    async updateHbaVmNames () {
      this.vmNameLoading = true
      try {
        const response = await api('listHostHbaDevices', { id: this.resource.id })
        const devices = response.listhosthbadevicesresponse?.listhosthbadevices?.[0]

        if (devices?.vmallocations) {
          const vmNamesMap = {}
          const entries = Object.entries(devices.vmallocations)

          for (const [deviceName, vmId] of entries) {
            if (vmId) {
              try {
                const vmResponse = await api('listVirtualMachines', {
                  id: vmId,
                  listall: true
                })

                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm) {
                  vmNamesMap[deviceName] = vm.name || vm.displayname
                } else {
                  vmNamesMap[deviceName] = this.$t(' ')
                }
              } catch (error) {
                console.error('Error fetching VM name for HBA device:', deviceName, error)
                vmNamesMap[deviceName] = this.$t(' ')
              }
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNamesMap }
        }
      } catch (error) {
        console.error('Error in updateHbaVmNames:', error)
      } finally {
        this.vmNameLoading = false
      }
    },
    async deallocateHbaDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.loading = true
      try {
        // 1. HBA 디바이스의 현재 할당 상태 확인
        const response = await api('listHostHbaDevices', {
          id: this.resource.id
        })
        const devices = response.listhosthbadevicesresponse?.listhosthbadevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[record.hostDevicesName]

        if (!vmId) {
          throw new Error('No VM allocation found for this device')
        }

        // 2. 할당 해제 확인 및 실행
        const vmName = this.vmNames[record.hostDevicesName] || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            // 물리 HBA 할당 해제용 XML 생성 (libvirt hostdev 형식)
            const xmlConfig = this.generateHbaDeallocationXmlConfig(record.hostDevicesName)
            const detachResponse = await api('updateHostHbaDevices', {
              hostid: this.resource.id,
              hostdevicesname: record.hostDevicesName,
              virtualmachineid: null,
              currentVmId: vmId,
              xmlconfig: xmlConfig
            })
            if (!detachResponse || detachResponse.error) {
              throw new Error(detachResponse?.error?.errortext || 'Failed to detach HBA device')
            }

            // 현재 디바이스만 업데이트
            this.dataItems = this.dataItems.map(item =>
              item.hostDevicesName === record.hostDevicesName
                ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false }
                : item
            )

            this.$message.success(this.$t('message.success.remove.allocation'))

            // 해당 물리 HBA의 vHBA 리스트 새로고침
            if (record.parentHba) {
              // 캐시된 데이터 삭제
              delete this.vhbaDevicesData[record.parentHba]
              // vHBA 리스트 다시 조회
              const parentHbaRecord = this.dataItems.find(item =>
                item.hostDevicesName === record.parentHba
              )
              if (parentHbaRecord) {
                await this.fetchVhbaListForHba(parentHbaRecord)
              }
            }

            this.vmNames = {}
            // 전체 HBA 디바이스 목록 새로고침
            await this.fetchHbaDevices()
            // vHBA 카운터 업데이트를 위해 강제로 computed 속성 재계산
            this.$nextTick(() => {
              this.$forceUpdate()
            })
            this.$emit('device-allocated')
            this.$emit('allocation-completed')
            this.$emit('close-action')
          },
          onCancel () {}
        })
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate HBA device')
      } finally {
        this.loading = false
      }
    },
    openHbaModal (record) {
      // 원본 디바이스명 사용 (API 호출용)
      const hostDevicesName = record.hostDevicesName
      this.selectedResource = { ...this.resource, hostDevicesName: hostDevicesName }
      this.showAddModal = true
    },
    generateXmlHbaConfig (hostDeviceName) {
      const match = hostDeviceName.match(/(\d+):(\d+)\.(\d+)/)
      let bus = '0x0000'
      let slot = '0x00'
      let func = '0x0'

      if (match) {
        bus = '0x' + parseInt(match[1], 10).toString(16).padStart(4, '0')
        slot = '0x' + parseInt(match[2], 10).toString(16).padStart(2, '0')
        func = '0x' + parseInt(match[3], 10).toString(16)
      }

      return `
        <hostdev mode='subsystem' type='pci' managed='yes'>
          <source>
            <address domain='0x0000' bus='${bus}' slot='${slot}' function='${func}'/>
          </source>
        </hostdev>
      `.trim()
    },
    isVhbaDevice (record) {
      // deviceType 속성을 우선적으로 확인
      if (record.deviceType === 'virtual') {
        return true
      }
      if (record.deviceType === 'physical') {
        return false
      }

      // isVhba 플래그 확인
      if (record.isVhba === true) {
        return true
      }
      if (record.isVhba === false) {
        return false
      }

      // 이름이나 텍스트에서 vHBA 관련 키워드 확인 (마지막 수단)
      const deviceName = record.hostDevicesName || ''
      const deviceText = record.hostDevicesText || ''

      return deviceName.toLowerCase().includes('vhba') ||
             deviceText.toLowerCase().includes('vhba') ||
             deviceName.toLowerCase().includes('virtual') ||
             deviceText.toLowerCase().includes('virtual')
    },
    async deleteVhbaDevice (record) {
      try {
        this.$confirm({
          title: this.$t('label.delete.vhba'),
          content: this.$t('message.confirm.delete.vhba'),
          onOk: async () => {
            this.loading = true

            // WWNN 값 추출
            let wwnn = null
            if (record.hostDevicesText) {
              const wwnnMatch = record.hostDevicesText.match(/WWNN:\s*([0-9A-Fa-f]{16})/i)
              if (wwnnMatch) {
                wwnn = wwnnMatch[1]
              }
            }

            // WWNN 기반으로 삭제 API 호출
            const params = {
              hostid: this.numericHostId
            }

            if (wwnn) {
              params.wwnn = wwnn
            } else {
              params.hostdevicesname = record.hostDevicesName
            }

            const response = await api('deleteVhbaDevice', params)

            if (response && !response.error) {
              this.$message.success(this.$t('message.success.delete.vhba'))

              // 해당 물리 HBA의 vHBA 리스트 새로고침
              if (record.parentHba) {
                // 캐시된 데이터 삭제
                delete this.vhbaDevicesData[record.parentHba]
                // vHBA 리스트 다시 조회
                const parentHbaRecord = this.dataItems.find(item =>
                  item.hostDevicesName === record.parentHba
                )
                if (parentHbaRecord) {
                  await this.fetchVhbaListForHba(parentHbaRecord)
                }
              }

              // 전체 HBA 디바이스 목록 새로고침
              await this.fetchHbaDevices()
              // vHBA 카운터 업데이트를 위해 강제로 computed 속성 재계산
              this.$nextTick(() => {
                this.$forceUpdate()
              })
            } else {
              throw new Error(response?.error?.errortext || this.$t('message.error.delete.vhba'))
            }
          },
          onCancel () {}
        })
      } catch (error) {
        console.error('Error deleting vHBA:', error)
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.delete.vhba')
        })
      } finally {
        this.loading = false
      }
    },
    async openCreateVhbaModal (record) {
      try {
        this.vhbaCreating = true

        // vHBA 이름 생성 (virsh nodedev-create 명령에서 사용할 XML 파일명)
        // 예: vhba_host3 (parenthbaname이 scsi_host3인 경우)
        const parentHbaName = record.hostDevicesName
        const vhbaName = `vhba_${parentHbaName.replace(/[^a-zA-Z0-9]/g, '_')}`

        this.selectedHbaDevice = record
        this.vhbaForm = {
          hbaDevice: parentHbaName,
          vhbaName: vhbaName
        }
        this.showVhbaCreateModal = true
      } catch (error) {
        console.error('Error preparing vHBA info:', error)
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.prepare.vhba.info')
        })
      } finally {
        this.vhbaCreating = false
      }
    },
    closeVhbaCreateModal () {
      this.showVhbaCreateModal = false
      this.selectedHbaDevice = null
      this.vhbaForm = {
        hbaDevice: '',
        vhbaName: ''
      }
      if (this.$refs.vhbaFormRef) {
        this.$refs.vhbaFormRef.resetFields()
      }
    },
    async createVhba () {
      try {
        this.vhbaCreating = true

        // 물리 HBA에서 WWNN/WWPN 값 추출
        let wwnn = ''
        let wwpn = ''

        if (this.selectedHbaDevice && this.selectedHbaDevice.hostDevicesText) {
          const hbaText = this.selectedHbaDevice.hostDevicesText

          // WWNN 추출 (16자리 16진수)
          const wwnnMatch = hbaText.match(/WWNN:\s*([0-9A-Fa-f]{16})/i)
          if (wwnnMatch) {
            wwnn = wwnnMatch[1]
          }

          // WWPN 추출 (16자리 16진수)
          const wwpnMatch = hbaText.match(/WWPN:\s*([0-9A-Fa-f]{16})/i)
          if (wwpnMatch) {
            wwpn = wwpnMatch[1]
          }

          // WWNN/WWPN이 추출되지 않으면 다른 패턴 시도
          if (!wwnn) {
            const wwnnMatch2 = hbaText.match(/([0-9A-Fa-f]{16})/g)
            if (wwnnMatch2 && wwnnMatch2.length > 0) {
              wwnn = wwnnMatch2[0]
            }
          }

          if (!wwpn) {
            const wwpnMatch2 = hbaText.match(/([0-9A-Fa-f]{16})/g)
            if (wwpnMatch2 && wwpnMatch2.length > 1) {
              wwpn = wwpnMatch2[1]
            }
          }
        }

        // 기본 XML 생성 (백엔드에서 dumpxml에서 WWNN 추출)
        const xmlContent = this.generateBasicVhbaXml(
          this.selectedHbaDevice.hostDevicesName
        )

        // API 호출 파라미터 준비
        const params = {
          hostid: this.resource.id,
          parenthbaname: this.selectedHbaDevice.hostDevicesName,
          vhbaname: `vhba_${this.selectedHbaDevice.hostDevicesName.replace(/[^a-zA-Z0-9]/g, '_')}`,
          wwnn: wwnn, // 추출된 WWNN 값 (참고용)
          wwpn: wwpn, // 추출된 WWPN 값 (참고용)
          xmlconfig: xmlContent
        }

        // createVhbaDevice API 호출
        const response = await api('createVhbaDevice', params)

        if (response && !response.error) {
          // 실제 생성된 vHBA 이름(예: scsi_host5) 추출
          const realVhbaName = response.result || response.createdDeviceName || response.data || response.vhbaName
          if (realVhbaName) {
            this.$message.success(`${this.$t('message.success.create.vhba')} (${realVhbaName})`)
          } else {
            this.$message.success(this.$t('message.success.create.vhba'))
          }
          this.closeVhbaCreateModal()

          // 즉시 vHBA 개수 업데이트를 위해 현재 데이터에 새 vHBA 추가
          if (this.selectedHbaDevice) {
            const parentHbaName = this.selectedHbaDevice.hostDevicesName

            // 기존 vHBA 데이터가 없으면 초기화
            if (!this.vhbaDevicesData[parentHbaName]) {
              this.vhbaDevicesData[parentHbaName] = []
            }

            // 새로 생성된 vHBA를 데이터에 추가
            const newVhbaDevice = {
              key: `vhba-${parentHbaName}-${Date.now()}`,
              hostDevicesName: realVhbaName || `vhba_${parentHbaName.replace(/[^a-zA-Z0-9]/g, '_')}`,
              hostDevicesText: `Virtual HBA: ${realVhbaName || 'New vHBA'}`,
              virtualmachineid: null,
              vmName: '',
              isAssigned: false,
              isPhysicalHba: false,
              isVhba: true,
              deviceType: 'virtual',
              parentHba: parentHbaName,
              indent: true,
              isVhbaDevice: true,
              wwnn: '',
              wwpn: '',
              status: 'Active'
            }

            this.vhbaDevicesData[parentHbaName].push(newVhbaDevice)

            // 해당 물리 HBA가 펼쳐져 있지 않으면 펼치기
            if (!this.expandedVhbaDevices[parentHbaName]) {
              this.expandedVhbaDevices[parentHbaName] = true
            }

            // 즉시 UI 업데이트를 위한 강제 리렌더링
            this.$nextTick(() => {
              this.$forceUpdate()
              // 추가로 한 번 더 강제 업데이트
              setTimeout(() => {
                this.$forceUpdate()
              }, 50)
            })
          }

          // 백그라운드에서 전체 데이터 새로고침 (사용자 경험을 위해 지연)
          setTimeout(async () => {
            await this.fetchHbaDevices()
          }, 500)
        } else {
          console.error('API 응답에 에러 있음:', response?.error)
          throw new Error(response?.error?.errortext || this.$t('message.error.create.vhba'))
        }
      } catch (error) {
        console.error('createVhba 메서드에서 에러 발생:', error)
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.create.vhba')
        })
      } finally {
        this.vhbaCreating = false
      }
    },

    // vHBA 디바이스 목록 조회 (listVhbaDevices API)
    async fetchVhbaDevices () {
      try {
        const response = await api('listVhbaDevices', { hostid: this.resource.id })
        if (response && response.listvhbadevicesresponse && response.listvhbadevicesresponse.vhbadevices) {
        }
      } catch (error) {
        console.error('listVhbaDevices API 호출 에러:', error)
      }
    },

    // 기본 vHBA XML 생성 메서드 (백엔드에서 dumpxml에서 WWNN 추출)
    generateBasicVhbaXml (parentHbaName) {
      const xml = `<device>
  <parent>${parentHbaName}</parent>
  <capability type='scsi_host'>
    <capability type='fc_host'>
    </capability>
  </capability>
</device>`

      return xml
    },

    // 물리 HBA 디바이스 정보 가져오기 (개선된 버전)
    async getHbaDeviceInfo (hbaDeviceName) {
      try {
        // 물리 HBA의 상세 정보를 가져오기 위해 시스템 명령어 실행
        const response = await api('listHostHbaDevices', {
          id: this.resource.id
        })

        if (response.listhosthbadevicesresponse?.listhosthbadevices?.[0]) {
          const hbaData = response.listhosthbadevicesresponse.listhosthbadevices[0]

          const deviceIndex = hbaData.hostdevicesname.indexOf(hbaDeviceName)

          if (deviceIndex !== -1) {
            const deviceText = hbaData.hostdevicestext[deviceIndex]

            // 다양한 패턴으로 WWPN/WWNN 추출 시도
            let wwpn = null
            let wwnn = null

            // 1. 직접적인 WWPN/WWNN 패턴 (개선된 정규식)
            const wwpnMatch1 = deviceText.match(/wwpn[:\s]*([0-9A-Fa-f]{16})/i)
            const wwnnMatch1 = deviceText.match(/wwnn[:\s]*([0-9A-Fa-f]{16})/i)

            if (wwpnMatch1) wwpn = wwpnMatch1[1]
            if (wwnnMatch1) wwnn = wwnnMatch1[1]

            // 2. port_name/node_name 패턴
            if (!wwpn) {
              const portNameMatch = deviceText.match(/port_name[:\s]*([0-9A-Fa-f]{16})/i)
              if (portNameMatch) wwpn = portNameMatch[1]
            }

            if (!wwnn) {
              const nodeNameMatch = deviceText.match(/node_name[:\s]*([0-9A-Fa-f]{16})/i)
              if (nodeNameMatch) wwnn = nodeNameMatch[1]
            }

            // 3. 일반적인 16자리 16진수 패턴 (마지막 수단)
            if (!wwpn || !wwnn) {
              const hexMatches = deviceText.match(/([0-9A-Fa-f]{16})/g)
              if (hexMatches && hexMatches.length > 0) {
                if (!wwpn) {
                  // 첫 번째 16자리 16진수를 WWPN으로 사용
                  wwpn = hexMatches[0]
                }
                if (!wwnn && hexMatches.length > 1) {
                  // 두 번째 16자리 16진수를 WWNN으로 사용
                  wwnn = hexMatches[1]
                }
              }
            }

            // 4. 만약 여전히 찾지 못했다면, 기본값 사용 (테스트용)
            if (!wwpn || !wwnn) {
              // 실제 환경에서는 이 값들이 백엔드에서 제공되어야 함
              // 여기서는 테스트를 위해 기본값 사용
              if (!wwpn) wwpn = '10000000c9848140'
              if (!wwnn) wwnn = '20000000c9848140'
            }

            return {
              name: hbaDeviceName,
              description: deviceText,
              wwpn: wwpn,
              wwnn: wwnn,
              pciAddress: hbaDeviceName
            }
          }
        }

        // 기본 정보 반환
        return {
          name: hbaDeviceName,
          description: '',
          wwpn: null,
          wwnn: null,
          pciAddress: hbaDeviceName
        }
      } catch (error) {
        console.error('Error getting HBA device info:', error)
        return {
          name: hbaDeviceName,
          description: '',
          wwpn: null,
          wwnn: null,
          pciAddress: hbaDeviceName
        }
      }
    },

    // HBA XML 정보 가져오기 (시뮬레이션)
    async getHbaXmlInfo (hbaDeviceName) {
      return {
        wwpn: '10000000c9848140',
        wwnn: '20000000c9848140'
      }
    },
    validateWwpn (wwpn) {
      const wwpnRegex = /^[0-9A-Fa-f]{16}$/
      return wwpnRegex.test(wwpn)
    },
    validateWwnn (wwnn) {
      const wwnnRegex = /^[0-9A-Fa-f]{16}$/
      return wwnnRegex.test(wwnn)
    },
    openVhbaAllocationModal (record) {
      this.selectedVhbaDevice = { ...this.resource, hostDevicesName: record.hostDevicesName }
      this.showVhbaAllocationModal = true
    },
    closeVhbaAllocationModal () {
      this.showVhbaAllocationModal = false
      this.selectedVhbaDevice = null
    },
    onVhbaAllocationCompleted () {
      this.closeVhbaAllocationModal()
      // vHBA 할당 완료 후 해당 물리 HBA의 vHBA 리스트 새로고침
      if (this.selectedVhbaDevice && this.selectedVhbaDevice.parentHba) {
        const parentHbaRecord = this.dataItems.find(item =>
          item.hostDevicesName === this.selectedVhbaDevice.parentHba
        )
        if (parentHbaRecord) {
          delete this.vhbaDevicesData[this.selectedVhbaDevice.parentHba]
          this.fetchVhbaListForHba(parentHbaRecord)
        }
      }
      // 전체 HBA 디바이스 목록 새로고침
      this.fetchHbaDevices()
      // vHBA 카운터 업데이트를 위해 강제로 computed 속성 재계산
      this.$nextTick(() => {
        this.$forceUpdate()
      })
    },
    handleVhbaDeviceAllocated () {
      // vHBA 할당 완료 후 해당 물리 HBA의 vHBA 리스트 새로고침
      if (this.selectedVhbaDevice && this.selectedVhbaDevice.parentHba) {
        const parentHbaRecord = this.dataItems.find(item =>
          item.hostDevicesName === this.selectedVhbaDevice.parentHba
        )
        if (parentHbaRecord) {
          delete this.vhbaDevicesData[this.selectedVhbaDevice.parentHba]
          this.fetchVhbaListForHba(parentHbaRecord)
        }
      }
      // 전체 HBA 디바이스 목록 새로고침
      this.fetchHbaDevices()
      // vHBA 카운터 업데이트를 위해 강제로 computed 속성 재계산
      this.$nextTick(() => {
        this.$forceUpdate()
      })
    },
    async deallocateVhbaDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.loading = true
      try {
        // 1. vHBA 디바이스의 현재 할당 상태 확인
        const response = await api('listHostHbaDevices', {
          id: this.resource.id
        })
        const devices = response.listhosthbadevicesresponse?.listhosthbadevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[record.hostDevicesName]

        if (!vmId) {
          throw new Error('No VM allocation found for this vHBA device')
        }

        // 2. VM 정보 가져오기
        const vmResponse = await api('listVirtualMachines', {
          id: vmId,
          listall: true
        })
        const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

        if (!vm) {
          throw new Error('VM not found')
        }

        // 3. 할당 해제 확인 및 실행
        const vmName = vm.name || vm.displayname || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            // vHBA 할당 해제용 XML 생성 (libvirt hostdev 형식)
            const xmlConfig = this.generateVhbaDeallocationXmlConfig(record.hostDevicesName)

            // updateHostHbaDevices API 사용 (vHBA도 동일한 API 사용)
            const detachResponse = await api('updateHostHbaDevices', {
              hostid: this.resource.id,
              hostdevicesname: record.hostDevicesName,
              virtualmachineid: null,
              currentvmid: vmId,
              xmlconfig: xmlConfig,
              isattach: false
            })

            if (!detachResponse || detachResponse.error) {
              throw new Error(detachResponse?.error?.errortext || 'Failed to detach vHBA device')
            }

            // 현재 디바이스만 업데이트
            this.dataItems = this.dataItems.map(item =>
              item.hostDevicesName === record.hostDevicesName
                ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false }
                : item
            )

            this.$message.success(this.$t('message.success.remove.allocation'))

            // 해당 물리 HBA의 vHBA 리스트 새로고침
            if (record.parentHba) {
              // 캐시된 데이터 삭제
              delete this.vhbaDevicesData[record.parentHba]
              // vHBA 리스트 다시 조회
              const parentHbaRecord = this.dataItems.find(item =>
                item.hostDevicesName === record.parentHba
              )
              if (parentHbaRecord) {
                await this.fetchVhbaListForHba(parentHbaRecord)
              }
            }

            this.vmNames = {}
            // 전체 HBA 디바이스 목록 새로고침
            await this.fetchHbaDevices()
            // vHBA 카운터 업데이트를 위해 강제로 computed 속성 재계산
            this.$nextTick(() => {
              this.$forceUpdate()
            })
            this.$emit('device-allocated')
            this.$emit('allocation-completed')
            this.$emit('close-action')
          },
          onCancel () {}
        })
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate vHBA device')
      } finally {
        this.loading = false
      }
    },

    // vHBA 해제용 XML 설정 생성
    generateVhbaDeallocationXmlConfig (vhbaDeviceName) {
      // vHBA 해제용 XML 생성 (VM에서 분리할 때 사용)
      // vHBA는 물리 HBA와 동일한 형식의 hostdev XML 사용
      return `
        <hostdev mode='subsystem' type='scsi' rawio='yes'>
          <source>
            <adapter name='${vhbaDeviceName}'/>
          </source>
        </hostdev>
      `.trim()
    },

    // 물리 HBA 할당 해제용 XML 설정 생성
    generateHbaDeallocationXmlConfig (hostDeviceName) {
      // 물리 HBA 할당 해제용 XML 생성 (libvirt hostdev 형식)
      // SCSI host adapter를 VM에서 분리할 때 사용
      return `
        <hostdev mode='subsystem' type='scsi' rawio='yes'>
          <source>
            <adapter name='${hostDeviceName}'/>
          </source>
        </hostdev>
      `.trim()
    },

    // vHBA 디바이스 정보 가져오기 (vHBA용)
    async getVhbaDeviceInfo (vhbaDeviceName) {
      try {
        // vHBA 디바이스의 상세 정보를 가져오기 위해 시스템 명령어 실행
        const response = await api('listHostHbaDevices', {
          id: this.resource.id
        })

        if (response.listhosthbadevicesresponse?.listhosthbadevices?.[0]) {
          const hbaData = response.listhosthbadevicesresponse.listhosthbadevices[0]
          const deviceIndex = hbaData.hostdevicesname.indexOf(vhbaDeviceName)

          if (deviceIndex !== -1) {
            const deviceText = hbaData.hostdevicestext[deviceIndex]

            // vHBA 디바이스 텍스트에서 WWPN/WWNN 정보 추출 (개선된 정규식)
            const wwpnMatch = deviceText.match(/wwpn[:\s]*([0-9A-Fa-f]{16})/i) ||
                             deviceText.match(/port_name[:\s]*([0-9A-Fa-f]{16})/i) ||
                             deviceText.match(/([0-9A-Fa-f]{16})/i)

            const wwnnMatch = deviceText.match(/wwnn[:\s]*([0-9A-Fa-f]{16})/i) ||
                             deviceText.match(/node_name[:\s]*([0-9A-Fa-f]{16})/i)

            return {
              name: vhbaDeviceName,
              description: deviceText,
              wwpn: wwpnMatch ? wwpnMatch[1] : null,
              wwnn: wwnnMatch ? wwnnMatch[1] : null
            }
          }
        }

        // 기본 정보 반환
        return {
          name: vhbaDeviceName,
          description: '',
          wwpn: null,
          wwnn: null
        }
      } catch (error) {
        console.error('Error getting vHBA device info:', error)
        return {
          name: vhbaDeviceName,
          description: '',
          wwpn: null,
          wwnn: null
        }
      }
    },
    generateVhbaXmlConfig (vhbaName, wwpn, wwnn, parentHbaDevice) {
      // vHBA XML 형식은 다음과 같아야 합니다:
      // <device>
      //   <name>vhba</name>
      //   <parent wwnn='20000000c9848140' wwpn='10000000c9848140'/>
      //   <capability type='scsi_host'>
      //     <capability type='fc_host'>
      //     </capability>
      //   </capability>
      // </device>

      // 물리 HBA의 WWPN/WWNN이 필요합니다
      if (!wwpn || !wwnn) {
        throw new Error('vHBA device must have WWPN and WWNN values')
      }

      return `
        <device>
          <name>${vhbaName}</name>
          <parent wwnn='${wwnn}' wwpn='${wwpn}'/>
          <capability type='scsi_host'>
            <capability type='fc_host'>
            </capability>
          </capability>
        </device>
      `.trim()
    },

    // 물리 HBA WWPN을 기반으로 vHBA WWPN 생성
    generateVhbaWwpn (parentWwpn, timestamp) {
      if (!parentWwpn || parentWwpn.length !== 16) {
        throw new Error('Parent HBA WWPN is required and must be 16 characters')
      }

      // 물리 HBA WWPN의 앞 8자리를 유지하고, 뒤 8자리를 변경
      const prefix = parentWwpn.substring(0, 8)
      const suffix = timestamp.toString().slice(-8)
      return prefix + suffix
    },

    // 물리 HBA WWNN을 기반으로 vHBA WWNN 생성
    generateVhbaWwnn (parentWwnn, timestamp) {
      if (!parentWwnn || parentWwnn.length !== 16) {
        throw new Error('Parent HBA WWNN is required and must be 16 characters')
      }

      // 물리 HBA WWNN의 앞 8자리를 유지하고, 뒤 8자리를 변경
      const prefix = parentWwnn.substring(0, 8)
      const suffix = timestamp.toString().slice(-8)
      return prefix + suffix
    },
    toggleVhbaList (record) {
      const isExpanded = this.expandedVhbaDevices[record.hostDevicesName]

      if (!isExpanded) {
        // 접혀있던 상태에서 펼칠 때만 vHBA 리스트 조회
        this.fetchVhbaListForHba(record)
      }

      this.expandedVhbaDevices[record.hostDevicesName] = !isExpanded
    },

    async fetchVhbaListForHba (physicalHbaRecord) {
      const hbaName = physicalHbaRecord.hostDevicesName

      // 이미 로딩 중이거나 데이터가 있으면 중복 호출 방지
      if (this.vhbaLoading[hbaName] || this.vhbaDevicesData[hbaName]) {
        return
      }

      // Vue 3에서는 직접 할당 사용
      this.vhbaLoading[hbaName] = true

      try {
        const response = await api('listVhbaDevices', {
          hostid: this.resource.id,
          keyword: hbaName
        })

        let vhbaDevices = []
        if (response && response.listvhbadevicesresponse) {
          if (response.listvhbadevicesresponse.null) {
            vhbaDevices = response.listvhbadevicesresponse.null
          } else if (response.listvhbadevicesresponse.listvhbadevices) {
            vhbaDevices = response.listvhbadevicesresponse.listvhbadevices
          } else if (Array.isArray(response.listvhbadevicesresponse)) {
            vhbaDevices = response.listvhbadevicesresponse
          }
        }

        if (vhbaDevices.length > 0) {
          // 해당 물리 HBA에 속한 vHBA만 필터링
          const filteredVhbaDevices = vhbaDevices.filter(vhba => {
            const parentHbaName = vhba.parenthbaname || vhba.parentHbaName
            return parentHbaName === hbaName
          })

          // vHBA 디바이스들을 기존 데이터 형식에 맞게 변환
          const formattedVhbaDevices = filteredVhbaDevices.map((vhba, index) => ({
            key: `vhba-${hbaName}-${index}`,
            hostDevicesName: vhba.vhbaname || vhba.name || vhba.hostdevicesname,
            hostDevicesText: (vhba.description || vhba.hostdevicestext || `Virtual HBA: ${vhba.vhbaname || vhba.name}`).replace(/\n/g, '<br>'),
            virtualmachineid: vhba.virtualmachineid || null,
            vmName: vhba.vmname || '',
            isAssigned: Boolean(vhba.virtualmachineid),
            isPhysicalHba: false,
            isVhba: true,
            deviceType: 'virtual',
            parentHba: hbaName,
            indent: true,
            isVhbaDevice: true,
            wwnn: vhba.wwnn || '',
            wwpn: vhba.wwpn || '',
            status: vhba.status || 'Active'
          }))
          this.vhbaDevicesData[hbaName] = formattedVhbaDevices
        } else {
          this.vhbaDevicesData[hbaName] = []
        }
      } catch (error) {
        console.error('Error fetching vHBA devices for HBA:', hbaName, error)
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.fetch.vhba.devices')
        })
        this.vhbaDevicesData[hbaName] = []
      } finally {
        this.vhbaLoading[hbaName] = false
      }
    },

    // HBA 디바이스 목록을 가져올 때 vHBA 데이터도 함께 처리
    getHbaDevicesWithVhba () {
      const result = []

      this.dataItems.forEach(item => {
        // 물리 HBA 추가
        result.push(item)

        // 해당 물리 HBA의 vHBA들이 펼쳐져 있으면 추가
        if (!this.isVhbaDevice(item) && this.expandedVhbaDevices[item.hostDevicesName]) {
          const vhbaDevices = this.vhbaDevicesData[item.hostDevicesName] || []
          result.push(...vhbaDevices)
          // vHBA 개수 표시를 위한 요약 행 추가
          if (vhbaDevices.length > 0) {
            result.push({
              key: `summary-${item.hostDevicesName}`,
              hostDevicesName: '',
              hostDevicesText: '',
              vmName: '',
              isSummary: true,
              vhbaCount: vhbaDevices.length,
              parentHbaName: item.hostDevicesName
            })
          }
        }
      })

      return result
    },
    async fetchScsiDevices () {
      this.loading = true
      try {
        const response = await api('listHostScsiDevices', { id: this.resource.id })
        if (response.listhostscsidevicesresponse?.listhostscsidevices?.[0]) {
          const scsiData = response.listhostscsidevicesresponse.listhostscsidevices[0]
          const vmAllocations = scsiData.vmallocations || {}
          // VM 이름 매핑
          const vmNameMap = {}
          for (const name in vmAllocations) {
            const vmId = vmAllocations[name]
            if (vmId) {
              const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
              const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
              if (vm) vmNameMap[name] = vm.displayname || vm.name
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNameMap }
          const scsiDevices = scsiData.hostdevicesname.map((name, index) => ({
            key: index,
            hostDevicesName: name,
            hostDevicesText: scsiData.hostdevicestext[index],
            virtualmachineid: (scsiData.vmallocations && scsiData.vmallocations[name]) || null,
            vmName: vmNameMap[name] || '',
            isAssigned: Boolean(scsiData.vmallocations && scsiData.vmallocations[name])
          }))

          // 다른 탭에서 할당된 디바이스 확인 (LUN 탭과의 중복 할당 방지)
          for (const device of scsiDevices) {
            // SCSI 디바이스 텍스트에서 LUN 디바이스 찾기
            const deviceMatch = device.hostDevicesText.match(/Device:\s*(\/dev\/[^\s\n]+)/)

            if (deviceMatch) {
              const lunDevice = deviceMatch[1]

              // LUN 탭에서 할당된 디바이스 확인
              const lunResponse = await api('listHostLunDevices', { id: this.resource.id })
              const lunData = lunResponse.listhostlundevicesresponse?.listhostlundevices?.[0]

              if (lunData?.vmallocations) {
                const allocatedVmId = lunData.vmallocations[lunDevice]

                if (allocatedVmId) {
                  // VM 정보 가져오기
                  const vmResponse = await api('listVirtualMachines', { id: allocatedVmId, listall: true })
                  const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

                  if (vm) {
                    device.allocatedInOtherTab = {
                      isAllocated: true,
                      vmName: vm.displayname || vm.name,
                      vmId: allocatedVmId,
                      tabType: 'LUN'
                    }
                    device.vmName = vm.displayname || vm.name
                    device.virtualmachineid = allocatedVmId
                    device.isAssigned = true
                  }
                } else {
                  console.log(`LUN 디바이스 ${lunDevice}는 할당되지 않음`)
                }
              } else {
                console.log(`LUN 디바이스 할당 정보가 없음`)
              }
            } else {
              console.log(`SCSI 디바이스 ${device.hostDevicesName}에서 LUN 매핑을 찾을 수 없음`)
            }
          }

          // SCSI 디바이스만 필터링하여 설정
          this.dataItems = scsiDevices.filter(device =>
            device.hostDevicesName && device.hostDevicesName.startsWith('/dev/sg')
          )
        } else {
          this.dataItems = []
        }
      } catch (error) {
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.fetch.scsi.devices')
        })
      } finally {
        this.loading = false
      }
    },

    openScsiModal (record) {
      this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
      this.showAddModal = true
    },

    async deallocateScsiDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.loading = true
      const hostDevicesName = record.hostDevicesName
      try {
        let vmId = null
        let vmName = 'Unknown VM'
        let isLunAllocated = false
        let lunDeviceName = null

        // 다른 탭에서 할당된 경우
        if (record.allocatedInOtherTab) {
          vmId = record.allocatedInOtherTab.vmId
          vmName = record.allocatedInOtherTab.vmName
          isLunAllocated = record.allocatedInOtherTab.tabType === 'LUN'

          // allocatedInOtherTab에 vmId가 없는 경우, 실제 API에서 확인
          if (!vmId) {
            if (isLunAllocated) {
              // LUN 탭에서 할당 정보 확인
              const lunResponse = await api('listHostLunDevices', { id: this.resource.id })
              const lunData = lunResponse.listhostlundevicesresponse?.listhostlundevices?.[0]

              if (lunData?.vmallocations) {
                // SCSI 디바이스 텍스트에서 LUN 디바이스 찾기
                const deviceMatch = record.hostDevicesText.match(/Device:\s*(\/dev\/[^\s\n]+)/)
                if (deviceMatch) {
                  const lunDevice = deviceMatch[1]
                  vmId = lunData.vmallocations[lunDevice]
                }
              }
            }
          }
        } else {
          // 현재 탭에서 할당된 경우
          const response = await api('listHostScsiDevices', {
            id: this.resource.id
          })
          const devices = response.listhostscsidevicesresponse?.listhostscsidevices?.[0]
          const vmAllocations = devices?.vmallocations || {}
          vmId = vmAllocations[hostDevicesName]
          vmName = this.vmNames[hostDevicesName] || 'Unknown VM'
        }

        if (!vmId) {
          console.error(`VM 할당 정보를 찾을 수 없음: ${hostDevicesName}`)
          console.error(`record:`, record)
          throw new Error('No VM allocation found for this device')
        }

        // 2. 할당 해제 확인 및 실행
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            try {
              // 다른 탭에서 할당된 경우 LUN 디바이스 해제
              if (isLunAllocated) {
                // SCSI 디바이스 텍스트에서 LUN 디바이스 찾기
                const deviceMatch = record.hostDevicesText.match(/Device:\s*(\/dev\/[^\s\n]+)/)

                if (!deviceMatch) {
                  throw new Error(`LUN device mapping not found in SCSI device text: ${record.hostDevicesText}`)
                }

                lunDeviceName = deviceMatch[1]

                // LUN 디바이스 해제
                const xmlConfig = this.generateXmlLunConfig(lunDeviceName)

                const detachResponse = await api('updateHostLunDevices', {
                  hostid: this.resource.id,
                  hostdevicesname: lunDeviceName,
                  virtualmachineid: null,
                  currentvmid: vmId,
                  xmlconfig: xmlConfig,
                  isattach: false
                })

                if (!detachResponse || detachResponse.error) {
                  console.error('LUN 디바이스 해제 실패:', detachResponse?.error)
                  throw new Error(detachResponse?.error?.errortext || 'Failed to detach LUN device')
                }
              } else {
                // 현재 탭에서 할당된 경우 SCSI 디바이스 해제
                const xmlConfig = this.generateScsiXmlConfig(hostDevicesName)

                const detachResponse = await api('updateHostScsiDevices', {
                  hostid: this.resource.id,
                  hostdevicesname: hostDevicesName,
                  virtualmachineid: null,
                  currentvmid: vmId,
                  xmlconfig: xmlConfig,
                  isattach: false
                })

                if (!detachResponse || detachResponse.error) {
                  console.error('SCSI 디바이스 해제 실패:', detachResponse?.error)
                  throw new Error(detachResponse?.error?.errortext || 'Failed to detach SCSI device')
                }
              }

              // UI 상태 업데이트
              this.dataItems = this.dataItems.map(item =>
                item.hostDevicesName === hostDevicesName
                  ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false, allocatedInOtherTab: null }
                  : item
              )

              this.$message.success(this.$t('message.success.remove.allocation'))

              // 현재 탭만 새로고침 (SCSI 탭에서 해제한 경우 SCSI 탭만)
              await this.fetchScsiDevices()

              this.vmNames = {}
              this.$emit('device-allocated')
              // allocation-completed 이벤트 제거 (탭 전환 방지)
              this.$emit('close-action')
            } catch (error) {
              console.error('디바이스 해제 중 오류:', error)
              this.$notification.error({
                message: this.$t('label.error'),
                description: error.message || 'Failed to deallocate device'
              })
            }
          },
          onCancel () {
          }
        })
      } catch (error) {
        console.error('deallocateScsiDevice 오류:', error)
        this.$notifyError(error.message || 'Failed to deallocate SCSI device')
      } finally {
        this.loading = false
      }
    },

    // 디바이스가 다른 탭에서 할당되었는지 확인하는 메서드
    async isDeviceAllocatedInOtherTab (record, currentTab) {
      try {
        // 현재 탭이 아닌 다른 탭들의 할당 상태 확인
        const otherTabs = {
          3: 'listHostLunDevices', // LUN 탭
          5: 'listHostScsiDevices' // SCSI 탭
        }

        for (const [tabKey, apiMethod] of Object.entries(otherTabs)) {
          if (tabKey === currentTab) continue

          const response = await api(apiMethod, { id: this.resource.id })
          const key = `list${apiMethod.replace('listHost', '').toLowerCase()}response`
          const res = response[key]
          const devices = res?.listhostdevices?.[0] ||
                         res?.listhostscsidevices?.[0] ||
                         res?.listhostlundevices?.[0]

          if (devices?.vmallocations) {
            // 디바이스 이름 매핑 확인
            const deviceName = record.hostDevicesName

            // 직접 매칭 확인
            let allocatedVmId = devices.vmallocations[deviceName]

            if (!allocatedVmId && currentTab === '5' && tabKey === '3') {
              // SCSI 디바이스의 실제 블록 디바이스 찾기
              const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
              const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]
              if (scsiData) {
                const scsiIndex = scsiData.hostdevicesname.indexOf(deviceName)
                if (scsiIndex !== -1) {
                  const scsiText = scsiData.hostdevicestext[scsiIndex]
                  // Device: /dev/sdah 추출
                  const deviceMatch = scsiText.match(/Device:\s*(\/dev\/[^\s\n]+)/)
                  if (deviceMatch) {
                    const blockDevice = deviceMatch[1]
                    allocatedVmId = devices.vmallocations[blockDevice]
                  }
                }
              }
            }

            if (!allocatedVmId && currentTab === '3' && tabKey === '5') {
              // LUN 디바이스에 해당하는 SCSI 디바이스 찾기
              const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
              const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]
              if (scsiData) {
                for (let i = 0; i < scsiData.hostdevicesname.length; i++) {
                  const scsiText = scsiData.hostdevicestext[i]
                  const deviceMatch = scsiText.match(/Device:\s*(\/dev\/[^\s\n]+)/)
                  if (deviceMatch && deviceMatch[1] === deviceName) {
                    const sgDevice = scsiData.hostdevicesname[i]
                    allocatedVmId = devices.vmallocations[sgDevice]
                    break
                  }
                }
              }
            }

            if (allocatedVmId) {
              // 할당된 VM 정보 가져오기
              const vmResponse = await api('listVirtualMachines', { id: allocatedVmId, listall: true })
              const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
              if (vm) {
                return {
                  isAllocated: true,
                  vmName: vm.displayname || vm.name,
                  vmId: allocatedVmId,
                  tabType: tabKey === '3' ? 'LUN' : 'SCSI'
                }
              }
            }
          }
        }

        return { isAllocated: false }
      } catch (error) {
        console.error('Error checking device allocation in other tabs:', error)
        return { isAllocated: false }
      }
    },

    // 디바이스 할당 버튼 표시 여부 결정
    shouldShowAllocationButton (record) {
      // 할당된 경우 (현재 탭이든 다른 탭이든) 해제 버튼 표시
      if (this.isDeviceAssigned(record) || record.allocatedInOtherTab) {
        return true
      }

      return true
    },
    async fetchHostInfo () {
      const hostResponse = await api('listHosts', { id: this.resource.id })
      const host = hostResponse.listhostsresponse?.host?.[0]
      this.numericHostId = host.id // 숫자 ID 저장
    },
    hasPartitionsFromText (hostDevicesText) {
      // HAS_PARTITIONS: true/false 패턴 매칭
      const hasPartitionsMatch = hostDevicesText.match(/HAS_PARTITIONS:\s*(true|false)/i)
      if (hasPartitionsMatch) {
        return hasPartitionsMatch[1].toLowerCase() === 'true'
      }

      // 기존 패턴도 지원
      return /has[\s_]?partitions\s*:\s*true/i.test(hostDevicesText)
    },

    isInUseFromText (hostDevicesText) {
      // IN_USE: true/false 패턴 매칭
      const inUseMatch = hostDevicesText.match(/IN_USE:\s*(true|false)/i)
      if (inUseMatch) {
        return inUseMatch[1].toLowerCase() === 'true'
      }

      // 기존 패턴도 지원
      return /in[\s_]?use\s*:\s*true/i.test(hostDevicesText)
    },

    isCephLvmVolume (deviceName) {
      // Ceph OSD 블록 디바이스인지 확인
      return deviceName && deviceName.includes('ceph--') && deviceName.includes('--osd--block--')
    },

    // HAS_PARTITIONS와 IN_USE를 라벨로 변경하는 함수
    formatHostDevicesText (text) {
      if (!text) return text

      let formattedText = text

      formattedText = formattedText.replace(/HAS_PARTITIONS:\s*false/gi, this.$t('label.no.partitions'))
      formattedText = formattedText.replace(/HAS_PARTITIONS:\s*true/gi, this.$t('label.has.partitions'))

      formattedText = formattedText.replace(/IN_USE:\s*false/gi, this.$t('label.not.in.use'))
      formattedText = formattedText.replace(/IN_USE:\s*true/gi, this.$t('label.in.use'))

      return formattedText
    }
  }
}
</script>

<style lang="less" scoped>
  .ant-table-wrapper {
    margin: 2rem 0;
  }

  @media (max-width: 600px) {
    position: relative;
    width: 100%;
    top: 0;
    right: 0;
  }

  :deep(.ant-table-tbody) > tr > td {
    cursor: pointer;
  }

  .pci-device-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    width: 100%;
  }
</style>
