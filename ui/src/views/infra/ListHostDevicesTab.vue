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
            <template v-if="column.key === 'hostDevicesText'"><span v-html="formatHostDevicesText(record.hostDevicesText)"></span></template>
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
                  <div style="white-space: pre-line; line-height: 1.4; min-height: 40px;">{{ formatDeviceName(record.hostDevicesName) }}</div>
                </template>
              </div>
            </template>
            <template v-if="column.key === 'hostDevicesText'">
              <div :style="{ paddingLeft: record.indent ? '20px' : '0px' }">
                <template v-if="!record.isSummary">
                  <span v-html="formatHostDevicesText(record.hostDevicesText)"></span>
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
              <span v-html="formatHostDevicesText(record.hostDevicesText)"></span>
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
            <template v-if="column.key === 'hostDevicesName'">
              <div style="white-space: pre-line; line-height: 1.4; min-height: 40px;">{{ formatDeviceName(record.hostDevicesName) }}</div>
            </template>
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
                    v-if="!hasPartitionsFromText(record.hostDevicesText) && shouldShowAllocationButton(record)"
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
              <div style="white-space: pre-line; line-height: 1.4; min-height: 40px;">{{ formatDeviceName(record.hostDevicesName) }}</div>
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
        @device-allocated="handleVhbaDeviceAllocated"
        @refresh-device-list="refreshVhbaDeviceList" />
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

    currentColumns () {
      if (this.activeKey === '2') {
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

        if (deviceName.toUpperCase().includes('LUN') || deviceName.toLowerCase().includes('dm')) {
          return false
        }

        const isLun = deviceName.startsWith('/dev/') ||
                     deviceName.startsWith('wwn-') ||
                     deviceName.startsWith('scsi-') ||
                     deviceName.startsWith('nvme-')
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
    formatDeviceName (deviceName) {
      if (!deviceName) return ''

      const match = deviceName.match(/^(.+?)\s*\((.+?)\)$/)
      if (match) {
        const [, mainName, bracketContent] = match
        const result = `${mainName.trim()}\n(${bracketContent})`
        return result
      }

      return deviceName
    },
    setupVMEventListeners () {
      const eventTypes = ['DestroyVM', 'ExpungeVM', 'StopVM', 'RebootVM', 'StartVM']

      eventTypes.forEach(eventType => {
        eventBus.emit('register-event', {
          eventType: eventType,
          callback: async (event) => {
            try {
              const vmId = event.id

              if (eventType === 'DestroyVM' || eventType === 'ExpungeVM') {
                await Promise.all([
                  this.deallocateDevicesOnVmDelete(vmId, 'listHostDevices', 'updateHostDevices'),
                  this.deallocateDevicesOnVmDelete(vmId, 'listHostHbaDevices', 'updateHostHbaDevices'),
                  this.deallocateDevicesOnVmDelete(vmId, 'listHostUsbDevices', 'updateHostUsbDevices'),
                  this.deallocateDevicesOnVmDelete(vmId, 'listHostLunDevices', 'updateHostLunDevices'),
                  this.deallocateDevicesOnVmDelete(vmId, 'listHostScsiDevices', 'updateHostScsiDevices')
                ])

                await this.refreshDataAfterVmDelete()
              } else if (eventType === 'StartVM') {
                await this.restoreDevicesFromExtraConfig(vmId)
              } else {
                if (this.activeKey === '1') {
                  await this.fetchData()
                } else if (this.activeKey === '2') {
                  await this.fetchHbaDevices()
                } else if (this.activeKey === '3') {
                  await this.fetchUsbDevices()
                } else if (this.activeKey === '4') {
                  await this.fetchLunDevices()
                } else if (this.activeKey === '5') {
                  await this.fetchScsiDevices()
                }
              }
            } catch (error) {
            }
          }
        })
      })
    },

    async deallocateDevicesOnVmDelete (vmId, listApiMethod, updateApiMethod) {
      try {
        const response = await api(listApiMethod, { id: this.resource.id })
        const devices = this.extractDevicesFromResponse(response)

        if (devices?.vmallocations) {
          const allocatedDevices = Object.entries(devices.vmallocations)
            .filter(([_, allocatedVmId]) => allocatedVmId === vmId)
            .map(([deviceName]) => deviceName)

          for (const deviceName of allocatedDevices) {
            try {
              let updateResponse
              if (listApiMethod === 'listHostDevices') {
                updateResponse = await api('updateHostDevices', {
                  hostid: this.resource.id,
                  hostdevicesname: deviceName,
                  virtualmachineid: null
                })
              } else if (listApiMethod === 'listHostHbaDevices') {
                const xmlConfig = this.generateHbaDeallocationXmlConfig(deviceName)
                updateResponse = await api('updateHostHbaDevices', {
                  hostid: this.resource.id,
                  hostdevicesname: deviceName,
                  virtualmachineid: null,
                  xmlconfig: xmlConfig
                })
              } else if (listApiMethod === 'listHostUsbDevices') {
                const xmlConfig = this.generateXmlUsbConfig(deviceName)
                updateResponse = await api('updateHostUsbDevices', {
                  hostid: this.resource.id,
                  hostdevicesname: deviceName,
                  virtualmachineid: null,
                  currentvmid: vmId,
                  xmlconfig: xmlConfig
                })
              } else if (listApiMethod === 'listHostLunDevices') {
                const xmlConfig = this.generateXmlLunConfig(deviceName)
                updateResponse = await api('updateHostLunDevices', {
                  hostid: this.resource.id,
                  hostdevicesname: deviceName,
                  virtualmachineid: null,
                  xmlconfig: xmlConfig
                })
              } else if (listApiMethod === 'listHostScsiDevices') {
                const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
                const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

                let actualDeviceText = ''
                if (scsiData && scsiData.hostdevicesname) {
                  const deviceIndex = scsiData.hostdevicesname.indexOf(deviceName)
                  if (deviceIndex !== -1 && scsiData.hostdevicestext) {
                    actualDeviceText = scsiData.hostdevicestext[deviceIndex] || ''
                  }
                }

                const xmlConfig = this.generateScsiXmlFromText(deviceName, actualDeviceText)
                updateResponse = await api('updateHostScsiDevices', {
                  hostid: this.resource.id,
                  hostdevicesname: deviceName,
                  virtualmachineid: null,
                  currentvmid: vmId,
                  xmlconfig: xmlConfig
                })
              } else if (listApiMethod === 'listVhbaDevices') {
                const xmlConfig = this.generateVhbaDeallocationXmlConfig(deviceName)
                updateResponse = await api('updateHostVhbaDevices', {
                  hostid: this.resource.id,
                  hostdevicesname: deviceName,
                  virtualmachineid: null,
                  currentvmid: vmId,
                  xmlconfig: xmlConfig
                })
              }

              if (!updateResponse || updateResponse.error) {
                throw new Error(updateResponse?.error?.errortext || 'Failed to update host device')
              }
            } catch (error) {
              this.$notification.error({
                message: this.$t('label.error'),
                description: error.message || this.$t('message.update.host.device.failed')
              })
            }
          }

          return allocatedDevices.length > 0
        }
        return false
      } catch (error) {
        return false
      }
    },

    extractDevicesFromResponse (response) {
      if (response.listhostdevicesresponse?.listhostdevices?.[0]) {
        return response.listhostdevicesresponse.listhostdevices[0]
      } else if (response.listhosthbadevicesresponse?.listhosthbadevices?.[0]) {
        return response.listhosthbadevicesresponse.listhosthbadevices[0]
      } else if (response.listhostusbdevicesresponse?.listhostusbdevices?.[0]) {
        return response.listhostusbdevicesresponse.listhostusbdevices[0]
      } else if (response.listhostlundevicesresponse?.listhostlundevices?.[0]) {
        return response.listhostlundevicesresponse.listhostlundevices[0]
      } else if (response.listhostscsidevicesresponse?.listhostscsidevices?.[0]) {
        return response.listhostscsidevicesresponse.listhostscsidevices[0]
      }
      return null
    },

    async refreshDataAfterVmDelete () {
      try {
        await Promise.all([
          this.fetchData(),
          this.fetchHbaDevices(),
          this.fetchUsbDevices(),
          this.fetchLunDevices(),
          this.fetchScsiDevices()
        ])

        this.vmNames = {}

        if (this.activeKey === '1') {
          await this.updateVmNames()
        } else if (this.activeKey === '2') {
          await this.updateHbaVmNames()
        } else if (this.activeKey === '3') {
          await this.updateUsbVmNames()
        }

        this.$nextTick(() => {
          this.$forceUpdate()
          setTimeout(() => {
            this.$forceUpdate()
          }, 100)
        })

        this.$message.info(this.$t('message.device.allocation.removed.vm.deleted'))
      } catch (error) {
      }
    },

    async restoreDevicesFromExtraConfig (vmId) {
      try {
        const vmResponse = await api('listVirtualMachines', { id: vmId })
        const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

        if (!vm || !vm.details) {
          return
        }

        const deviceConfigs = []
        Object.entries(vm.details).forEach(([key, value]) => {
          if (key.startsWith('extraconfig-') && value.includes('<hostdev')) {
            deviceConfigs.push({ key, value })
          }
        })

        if (deviceConfigs.length === 0) {
          return
        }

        for (const config of deviceConfigs) {
          try {
            await this.restoreDeviceFromConfig(vmId, config.value)
          } catch (error) {
          }
        }

        await this.refreshDataAfterVmStart()

        this.$message.success(this.$t('message.device.restored.from.extraconfig'))
      } catch (error) {
        this.$message.error(this.$t('message.device.restore.failed'))
      }
    },

    async restoreDeviceFromConfig (vmId, xmlConfig) {
      const deviceInfo = this.parseDeviceXmlConfig(xmlConfig)

      if (!deviceInfo) {
        return
      }

      const params = {
        hostid: this.resource.id,
        hostdevicesname: deviceInfo.deviceName,
        virtualmachineid: vmId,
        xmlconfig: xmlConfig
      }

      let apiMethod
      switch (deviceInfo.type) {
        case 'usb':
          apiMethod = 'updateHostUsbDevices'
          break
        case 'hba':
          apiMethod = 'updateHostHbaDevices'
          break
        case 'lun':
          apiMethod = 'updateHostLunDevices'
          break
        case 'scsi':
          apiMethod = 'updateHostScsiDevices'
          break
        case 'vhba':
          apiMethod = 'updateHostVhbaDevices'
          break
        default:
          return
      }

      await api(apiMethod, params)
    },

    parseDeviceXmlConfig (xmlConfig) {
      try {
        if (xmlConfig.includes("type='usb'")) {
          const busMatch = xmlConfig.match(/bus='0x([^']+)'/)
          const deviceMatch = xmlConfig.match(/device='0x([^']+)'/)
          if (busMatch && deviceMatch) {
            const bus = busMatch[1].padStart(3, '0')
            const device = deviceMatch[1].padStart(3, '0')
            return {
              type: 'usb',
              deviceName: `${bus} Device ${device}`,
              bus: bus,
              device: device
            }
          }
        }

        if (xmlConfig.includes("type='scsi_host'")) {
          const adapterMatch = xmlConfig.match(/name='([^']+)'/)
          if (adapterMatch) {
            return {
              type: 'hba',
              deviceName: adapterMatch[1],
              adapterName: adapterMatch[1]
            }
          }
        }

        if (xmlConfig.includes("device='lun'")) {
          const devMatch = xmlConfig.match(/dev='([^']+)'/)
          if (devMatch) {
            const devicePath = devMatch[1]
            const deviceName = devicePath.split('/').pop()
            return {
              type: 'lun',
              deviceName: deviceName,
              devicePath: devicePath
            }
          }
        }

        if (xmlConfig.includes("type='scsi'")) {
          const adapterMatch = xmlConfig.match(/name='([^']+)'/)
          if (adapterMatch) {
            return {
              type: 'scsi',
              deviceName: adapterMatch[1],
              adapterName: adapterMatch[1]
            }
          }
        }

        if (xmlConfig.includes("type='fc_host'")) {
          const parentMatch = xmlConfig.match(/<parent>([^<]+)<\/parent>/)
          if (parentMatch) {
            return {
              type: 'vhba',
              deviceName: parentMatch[1],
              parentHba: parentMatch[1]
            }
          }
        }

        return null
      } catch (error) {
        return null
      }
    },

    async refreshDataAfterVmStart () {
      try {
        await Promise.all([
          this.fetchData(),
          this.fetchHbaDevices(),
          this.fetchUsbDevices(),
          this.fetchLunDevices(),
          this.fetchScsiDevices()
        ])

        this.vmNames = {}

        if (this.activeKey === '1') {
          await this.updateVmNames()
        } else if (this.activeKey === '2') {
          await this.updateHbaVmNames()
        } else if (this.activeKey === '3') {
          await this.updateUsbVmNames()
        }

        this.$nextTick(() => {
          this.$forceUpdate()
          setTimeout(() => {
            this.$forceUpdate()
          }, 100)
        })
      } catch (error) {
      }
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
        this.dataItems = []
        this.selectedDevices = []
      } finally {
        this.loading = false
      }
      await this.updateVmNames()
    },
    isDeviceAssigned (record) {
      const deviceName = this.activeKey === '4' ? record.hostDevicesName : record.hostDevicesName

      if (record.vmName && record.vmName.trim() !== '') {
        return true
      }

      if (record.allocatedInOtherTab && record.allocatedInOtherTab.isAllocated) {
        return true
      }

      if (this.vmNames[record.hostDevicesName]) {
        return true
      }

      if (record.hostDevicesText && this.isInUseFromText(record.hostDevicesText)) {
        return true
      }

      return record.virtualmachineid != null ||
             record.isAssigned ||
             this.selectedDevices.includes(deviceName) ||
             (this.activeKey === '4' && this.vmNames[record.hostDevicesName])
    },

    isVhbaDeviceAssigned (record) {
      if (!this.isVhbaDevice(record)) {
        return false
      }

      if (record.virtualmachineid != null || record.isAssigned) {
        return true
      }

      if (this.vmNames[record.hostDevicesName]) {
        return true
      }

      if (record.hostDevicesText && this.isInUseFromText(record.hostDevicesText)) {
        return true
      }

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

    isParentHbaAssigned (record) {
      if (!this.isVhbaDevice(record) || !record.parentHba) {
        return false
      }

      const parentHbaRecord = this.dataItems.find(item =>
        item.hostDevicesName === record.parentHba && !this.isVhbaDevice(item)
      )

      if (!parentHbaRecord) {
        return false
      }

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
      this.loading = true
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
    },
    onLunSearch () {
    },
    onOtherSearch () {
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
          this.$notifyError(error)
        }
      }
    },
    handleAllocationCompleted () {
      const currentActiveKey = this.activeKey
      this.showAddModal = false
      this.loading = true

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

      this.updateDataWithVmNames()

      this.$nextTick(() => {
        this.activeKey = currentActiveKey
      })
    },
    handleDeviceAllocated () {
      const currentActiveKey = this.activeKey
      this.loading = true

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
          const vmNameMap = {}
          for (const name in vmAllocations) {
            const vmId = vmAllocations[name]
            if (vmId) {
              try {
                const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm && vm.state !== 'Expunging') {
                  vmNameMap[name] = vm.displayname || vm.name
                } else {
                  try {
                    const xmlConfig = this.generateXmlLunConfig(name)
                    const updateResponse = await api('updateHostLunDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: name,
                      virtualmachineid: null,
                      xmlconfig: xmlConfig
                    })

                    if (!updateResponse || updateResponse.error) {
                    }
                  } catch (error) {
                  }
                }
              } catch (error) {
                try {
                  const xmlConfig = this.generateXmlLunConfig(name)
                  await api('updateHostLunDevices', {
                    hostid: this.resource.id,
                    hostdevicesname: name,
                    virtualmachineid: null,
                    xmlconfig: xmlConfig
                  })
                } catch (detachError) {
                }
              }
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
          const scsiAddrToVmId = {}
          try {
            const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
            const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]
            if (scsiData && Array.isArray(scsiData.hostdevicesname)) {
              for (let i = 0; i < scsiData.hostdevicesname.length; i++) {
                const stext = scsiData.hostdevicestext[i] || ''
                const saddr = this.extractScsiAddressString(stext)
                if (saddr) {
                  const vmId = scsiData.vmallocations?.[scsiData.hostdevicesname[i]] || null
                  if (vmId) scsiAddrToVmId[saddr] = vmId
                }
              }
            }
          } catch (e) {}

          for (const device of lunDevices) {
            const laddr = this.extractScsiAddressString(device.hostDevicesText)
            const mappedVmId = laddr ? scsiAddrToVmId[laddr] : null

            let vmIdByPath = null
            try {
              const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
              const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]
              if (scsiData?.vmallocations) {
                for (let i = 0; i < scsiData.hostdevicesname.length; i++) {
                  const scsiText = scsiData.hostdevicestext[i]
                  const deviceMatch = scsiText.match(/Device:\s*(\/dev\/[^\s\n]+)/)
                  if (deviceMatch && deviceMatch[1] === device.hostDevicesName) {
                    const sgDevice = scsiData.hostdevicesname[i]
                    vmIdByPath = scsiData.vmallocations[sgDevice] || null
                    break
                  }
                }
              }
            } catch (e) {}

            const finalVmId = mappedVmId || vmIdByPath
            if (finalVmId) {
              const vmResponse = await api('listVirtualMachines', { id: finalVmId, listall: true })
              const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
              if (vm && vm.state !== 'Expunging') {
                device.allocatedInOtherTab = {
                  isAllocated: true,
                  vmName: vm.displayname || vm.name,
                  vmId: finalVmId,
                  tabType: 'SCSI'
                }
                device.vmName = vm.displayname || vm.name
                device.virtualmachineid = finalVmId
                device.isAssigned = true
              }
            }
          }

          this.dataItems = lunDevices.filter(device =>
            device.hostDevicesName &&
            (device.hostDevicesName.startsWith('/dev/') ||
             device.hostDevicesName.startsWith('wwn-') ||
             device.hostDevicesName.startsWith('scsi-') ||
             device.hostDevicesName.startsWith('dm-') ||
             device.hostDevicesName.startsWith('nvme-')) &&
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

        await api('updateVirtualMachine', params)

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
                if (vm && vm.state !== 'Expunging') {
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

                    this.selectedDevices = this.selectedDevices.filter(device => device !== deviceName)
                  } catch (error) {
                    this.$notification.error({
                      message: this.$t('label.error'),
                      description: error.message || this.$t('message.update.host.device.failed')
                    })
                  }
                }
              } catch (error) {
                vmNamesMap[deviceName] = this.$t(' ')
              }
            }
          }
          this.vmNames = vmNamesMap

          this.dataItems = this.dataItems.map(item => {
            if (vmNamesMap[item.hostDevicesName]) {
              return {
                ...item,
                isAssigned: true,
                vmName: vmNamesMap[item.hostDevicesName]
              }
            } else {
              return {
                ...item,
                isAssigned: false,
                vmName: null,
                virtualmachineid: null
              }
            }
          })

          this.selectedDevices = this.selectedDevices.filter(deviceName =>
            vmNamesMap[deviceName] !== undefined
          )

          if (processedDevices.size > 0) {
            await this.fetchData()
          }
        }
      } catch (error) {
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
          const processedDevices = new Set()

          for (const [deviceName, vmId] of entries) {
            if (vmId && !processedDevices.has(deviceName)) {
              try {
                const vmResponse = await api('listVirtualMachines', {
                  id: vmId,
                  listall: true
                })

                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm && vm.state !== 'Expunging') {
                  vmNamesMap[deviceName] = vm.name || vm.displayname
                } else {
                  try {
                    const xmlConfig = this.generateXmlUsbConfig(deviceName)
                    const updateResponse = await api('updateHostUsbDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: deviceName,
                      virtualmachineid: null,
                      currentvmid: vmId,
                      xmlconfig: xmlConfig
                    })

                    if (!updateResponse || updateResponse.error) {
                      throw new Error(updateResponse?.error?.errortext || 'Failed to update USB device')
                    }

                    vmNamesMap[deviceName] = this.$t(' ')
                    processedDevices.add(deviceName)

                    this.dataItems = this.dataItems.map(item => {
                      if (item.hostDevicesName === deviceName) {
                        return {
                          ...item,
                          virtualmachineid: null,
                          vmName: null,
                          isAssigned: false
                        }
                      }
                      return item
                    })

                    this.selectedDevices = this.selectedDevices.filter(device => device !== deviceName)
                  } catch (error) {
                    this.$notification.error({
                      message: this.$t('label.error'),
                      description: error.message || this.$t('message.update.usb.device.failed')
                    })
                  }
                }
              } catch (error) {
                vmNamesMap[deviceName] = this.$t(' ')
              }
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNamesMap }

          this.dataItems = this.dataItems.map(item => {
            if (vmNamesMap[item.hostDevicesName]) {
              return {
                ...item,
                isAssigned: true,
                vmName: vmNamesMap[item.hostDevicesName]
              }
            } else if (vmNamesMap[item.hostDevicesName] === '' || vmNamesMap[item.hostDevicesName] === undefined) {
              return {
                ...item,
                isAssigned: false,
                vmName: null,
                virtualmachineid: null
              }
            }
            return item
          })

          this.selectedDevices = this.selectedDevices.filter(deviceName =>
            vmNamesMap[deviceName] !== undefined && vmNamesMap[deviceName] !== ''
          )

          if (processedDevices.size > 0) {
            await this.fetchUsbDevices()
          }
        }
      } catch (error) {
      } finally {
        this.vmNameLoading = false
      }
    },
    beforeDestroy () {
      const eventTypes = ['DestroyVM', 'ExpungeVM', 'StopVM', 'RebootVM']
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
        const response = await api('listHostUsbDevices', {
          id: this.resource.id
        })
        const devices = response.listhostusbdevicesresponse?.listhostusbdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = String(vmAllocations[hostDevicesName]) // 문자열로 변환

        if (!vmId || vmId === 'null' || vmId === 'undefined') {
          throw new Error('No VM allocation found for this device')
        }

        const vmName = this.vmNames[hostDevicesName] || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            try {
              const vmResponse = await api('listVirtualMachines', {
                id: vmId,
                listall: true
              })
              const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

              if (!vm) {
              } else if (vm.state === 'Expunging') {
              } else if (vm.state !== 'Running' && vm.state !== 'Stopped') {
              }
            } catch (vmError) {
            }

            const xmlConfig = this.generateXmlUsbConfig(hostDevicesName)

            const detachResponse = await api('updateHostUsbDevices', {
              hostid: this.resource.id,
              hostdevicesname: hostDevicesName,
              virtualmachineid: null,
              currentvmid: vmId,
              xmlconfig: xmlConfig
            })

            if (!detachResponse || detachResponse.error) {
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
        this.$notifyError(error.message || 'Failed to deallocate USB device')
      } finally {
        this.loading = false
      }
    },

    generateXmlUsbConfig (hostDeviceName) {
      try {
        let bus = '0x001'
        let device = '0x01'

        let match = hostDeviceName.match(/(\d+)\s+Device\s+(\d+)/i)
        if (match && match.length >= 3) {
          const busNum = parseInt(match[1], 10)
          const deviceNum = parseInt(match[2], 10)

          if (!isNaN(busNum) && !isNaN(deviceNum)) {
            bus = '0x' + busNum.toString(16).padStart(3, '0')
            device = '0x' + deviceNum.toString(16).padStart(2, '0')
          }
        } else {
          match = hostDeviceName.match(/(\d+):(\d+)/)
          if (match && match.length >= 3) {
            const busNum = parseInt(match[1], 10)
            const deviceNum = parseInt(match[2], 10)

            if (!isNaN(busNum) && !isNaN(deviceNum)) {
              bus = '0x' + busNum.toString(16)
              device = '0x' + deviceNum.toString(16)
            }
          } else {
            match = hostDeviceName.match(/(\d+)\.(\d+)/)
            if (match && match.length >= 3) {
              const busNum = parseInt(match[1], 10)
              const deviceNum = parseInt(match[2], 10)

              if (!isNaN(busNum) && !isNaN(deviceNum)) {
                bus = '0x' + busNum.toString(16)
                device = '0x' + deviceNum.toString(16)
              }
            } else {
              match = hostDeviceName.match(/(\d+)\D+(\d+)/)
              if (match && match.length >= 3) {
                const busNum = parseInt(match[1], 10)
                const deviceNum = parseInt(match[2], 10)

                if (!isNaN(busNum) && !isNaN(deviceNum)) {
                  bus = '0x' + busNum.toString(16)
                  device = '0x' + deviceNum.toString(16)
                }
              }
            }
          }
        }

        return `
        <hostdev mode='subsystem' type='usb'>
          <source>
            <address type='usb' bus='${bus}' device='${device}' />
          </source>
        </hostdev>
        `.trim()
      } catch (error) {
        return `
        <hostdev mode='subsystem' type='usb'>
          <source>
            <address type='usb' bus='0x1' device='0x1' />
          </source>
        </hostdev>
        `.trim()
      }
    },
    generateXmlLunConfig (hostDeviceName) {
      const basePath = (hostDeviceName || '').split(' (')[0]
      const byIdMatch = (hostDeviceName || '').match(/\(([^)]+)\)/)

      let actualDevicePath = ''
      if (byIdMatch && byIdMatch[1]) {
        actualDevicePath = `/dev/disk/by-id/${byIdMatch[1]}`
      } else if (basePath && basePath.startsWith('/dev/disk/by-id/')) {
        actualDevicePath = basePath
      } else {
        actualDevicePath = basePath
      }

      const targetDev = (basePath || '').replace('/dev/', '')
      const scsiAddr = this.extractScsiAddressString(this.resource?.hostDevicesText || '')
      const addressTag = scsiAddr
        ? `<address type='drive' controller='${scsiAddr.split(':')[0]}' bus='${scsiAddr.split(':')[1]}' target='${scsiAddr.split(':')[2]}' unit='${scsiAddr.split(':')[3]}'/>`
        : ''
      return `
        <disk type='block' device='lun'>
          <driver name='qemu' type='raw' io='native' cache='none'/>
          <source dev='${actualDevicePath}'/>
          <target dev='${targetDev}' bus='scsi'/>
          ${addressTag}
          <!-- Fallback device path: ${basePath} -->
        </disk>
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
        const vmId = String(vmAllocations[hostDevicesName]) // 문자열로 변환

        if (!vmId || vmId === 'null' || vmId === 'undefined') {
          throw new Error('No VM allocation found for this device')
        }

        try {
          const vmResponse = await api('listVirtualMachines', {
            id: vmId,
            listall: true
          })
          const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

          if (!vm) {
          } else if (vm.state === 'Expunging') {
          } else if (vm.state !== 'Running' && vm.state !== 'Stopped') {
          }
        } catch (vmError) {
        }

        const xmlConfig = this.generateXmlUsbConfig(hostDevicesName)

        const detachResponse = await api('updateHostUsbDevices', {
          hostid: this.resource.id,
          hostdevicesname: hostDevicesName,
          virtualmachineid: null,
          currentvmid: vmId,
          xmlconfig: xmlConfig
        })

        if (!detachResponse || detachResponse.error) {
          throw new Error(detachResponse?.error?.errortext || 'Failed to detach USB device')
        }

        this.$message.success(this.$t('message.success.remove.allocation'))
        this.$emit('device-allocated')
        this.$emit('allocation-completed')
        this.$emit('close-action')
      } catch (error) {
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

        if (record.allocatedInOtherTab) {
          vmId = record.allocatedInOtherTab.vmId
          vmName = record.allocatedInOtherTab.vmName
          isScsiAllocated = record.allocatedInOtherTab.tabType === 'SCSI'
          if (!vmId) {
            if (isScsiAllocated) {
              const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
              const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

              if (scsiData?.vmallocations) {
                const laddr = this.extractScsiAddressString(record.hostDevicesText)
                for (let i = 0; i < scsiData.hostdevicesname.length; i++) {
                  const scsiText = scsiData.hostdevicestext[i]
                  const saddr = this.extractScsiAddressString(scsiText)
                  if (laddr && saddr && laddr === saddr) {
                    const sgDevice = scsiData.hostdevicesname[i]
                    vmId = scsiData.vmallocations[sgDevice]
                    break
                  }
                }
              }
            }
          }
        } else {
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
                const laddr = this.extractScsiAddressString(record.hostDevicesText)
                for (let i = 0; i < scsiData.hostdevicesname.length; i++) {
                  const scsiText = scsiData.hostdevicestext[i]
                  const saddr = this.extractScsiAddressString(scsiText)
                  if (laddr && saddr && laddr === saddr) {
                    const sgDevice = scsiData.hostdevicesname[i]
                    vmId = scsiData.vmallocations[sgDevice]
                    if (vmId) {
                      isScsiAllocated = true
                      try {
                        const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
                        const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                        if (vm) vmName = vm.name || vm.displayname
                      } catch (vmError) {}
                      break
                    }
                  }
                }
              }
            } catch (scsiError) {
            }
          }
        }

        if (!vmId) {
          let errorMessage = `VM 할당 정보를 찾을 수 없습니다: ${hostDevicesName}`
          if (record.allocatedInOtherTab) {
            errorMessage += ` (다른 탭에서 할당됨: ${record.allocatedInOtherTab.tabType})`
          }
          throw new Error(errorMessage)
        }

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

        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            try {
              if (isScsiAllocated) {
                const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
                const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

                if (!scsiData) {
                  throw new Error('Failed to fetch SCSI devices data')
                }

                let scsiDeviceFound = false
                let actualLunDeviceName = hostDevicesName

                if (hostDevicesName.startsWith('/dev/sg')) {
                  const lunResponse = await api('listHostLunDevices', { id: this.resource.id })
                  const lunData = lunResponse.listhostlundevicesresponse?.listhostlundevices?.[0]

                  if (lunData && Array.isArray(lunData.hostdevicesname)) {
                    const scsiAddrFromName = this.extractScsiAddressString(record.hostDevicesText)
                    for (let j = 0; j < lunData.hostdevicesname.length; j++) {
                      const lunText = lunData.hostdevicestext[j]
                      const lunScsiAddr = this.extractScsiAddressString(lunText)
                      if (scsiAddrFromName && lunScsiAddr && scsiAddrFromName === lunScsiAddr) {
                        actualLunDeviceName = lunData.hostdevicesname[j]
                        break
                      }
                    }
                  }
                }

                const lunAddr = this.extractScsiAddressString(record.hostDevicesText)

                for (let i = 0; i < scsiData.hostdevicesname.length; i++) {
                  const scsiText = scsiData.hostdevicestext[i]
                  const scsiAddr = this.extractScsiAddressString(scsiText)
                  const sgDevice = scsiData.hostdevicesname[i]

                  if (lunAddr && scsiAddr && lunAddr === scsiAddr) {
                    const xmlConfig = this.generateXmlLunConfig(actualLunDeviceName)

                    const detachResponse = await api('updateHostScsiDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: sgDevice,
                      virtualmachineid: null,
                      currentvmid: vmId,
                      xmlconfig: xmlConfig,
                      isattach: false
                    })

                    if (!detachResponse || detachResponse.error) {
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
                  throw new Error(detachResponse?.error?.errortext || 'Failed to detach LUN device')
                }
              }

              this.dataItems = this.dataItems.map(item =>
                item.hostDevicesName === hostDevicesName
                  ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false, allocatedInOtherTab: null }
                  : item
              )

              this.$message.success(this.$t('message.success.remove.allocation'))

              await this.fetchLunDevices()

              this.vmNames = {}
              this.$emit('device-allocated')
              this.$emit('close-action')
            } catch (error) {
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
        this.$emit('close-action')
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate LUN device')
      } finally {
        this.loading = false
      }
    },
    async openLunModal (record) {
      const isAllocatedInScsi = await this.checkDeviceAllocationInScsi(record.hostDevicesName)
      if (isAllocatedInScsi) {
        try {
          const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
          const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]
          if (scsiData?.vmallocations) {
            for (let i = 0; i < scsiData.hostdevicesname.length; i++) {
              const scsiText = scsiData.hostdevicestext[i]
              const deviceMatch = scsiText.match(/Device:\s*(\/dev\/[^\s\n]+)/)
              if (deviceMatch && deviceMatch[1] === record.hostDevicesName) {
                const sgDevice = scsiData.hostdevicesname[i]
                const vmId = scsiData.vmallocations[sgDevice]
                if (vmId) {
                  const vmRes = await api('listVirtualMachines', { id: vmId, listall: true })
                  const vm = vmRes.listvirtualmachinesresponse?.virtualmachine?.[0]
                  const vmName = vm ? (vm.displayname || vm.name) : ''
                  record.allocatedInOtherTab = { isAllocated: true, vmName, vmId, tabType: 'SCSI' }
                  record.vmName = vmName
                  record.virtualmachineid = vmId
                  record.isAssigned = true
                  await this.deallocateLunDevice(record)
                  this.$forceUpdate()
                }
              }
            }
          }
        } catch (e) {}
        return
      }

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
        const response = await api('listHostDevices', {
          id: this.resource.id
        })
        const devices = response.listhostdevicesresponse?.listhostdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[hostDevicesName]
        if (!vmId) {
          throw new Error('No VM allocation found for this device')
        }

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

        const vmName = this.vmNames[hostDevicesName] || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            await api('updateHostDevices', {
              hostid: this.resource.id,
              hostdevicesname: hostDevicesName,
              virtualmachineid: null,
              currentvmid: vmId
            })
            this.dataItems = this.dataItems.map(item =>
              item.hostDevicesName === hostDevicesName
                ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false }
                : item
            )
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

    },

    onScsiSearch () {

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

          const vmNameMap = {}

          for (const name in vmAllocations) {
            const vmId = vmAllocations[name]
            if (vmId) {
              try {
                const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm && vm.state !== 'Expunging') {
                  vmNameMap[name] = vm.displayname || vm.name
                } else {
                  try {
                    const xmlConfig = this.generateHbaDeallocationXmlConfig(name)
                    const updateResponse = await api('updateHostHbaDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: name,
                      virtualmachineid: null,
                      currentvmid: vmId,
                      xmlconfig: xmlConfig,
                      isattach: false
                    })

                    if (!updateResponse || updateResponse.error) {
                    }
                  } catch (error) {
                  }
                }
              } catch (error) {
                try {
                  const xmlConfig = this.generateHbaDeallocationXmlConfig(name)
                  await api('updateHostHbaDevices', {
                    hostid: this.resource.id,
                    hostdevicesname: name,
                    virtualmachineid: null,
                    currentvmid: vmId,
                    xmlconfig: xmlConfig,
                    isattach: false
                  })
                } catch (detachError) {
                }
              }
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNameMap }

          const vhbaAllocations = {}
          for (const name in vmAllocations) {
            const vmId = vmAllocations[name]
            if (vmId) {
              vhbaAllocations[name] = vmId
            }
          }

          const deviceTypes = hbaData.devicetypes || []
          const parentHbaNames = hbaData.parenthbanames || []

          const hbaDevices = []
          const physicalHbaMap = new Map() // 물리 HBA 이름 -> 인덱스 매핑

          hbaData.hostdevicesname.forEach((name, index) => {
            let deviceText = hbaData.hostdevicestext[index]
            const vmId = vmAllocations[name] || null
            const vmName = vmNameMap[name] || ''
            const isAssigned = Boolean(vmAllocations[name])
            const deviceType = deviceTypes[index] || 'physical'
            const parentHbaName = parentHbaNames[index] || ''

            deviceText = deviceText.replace(/Virtual HBA:\s*[^\s\n]+/g, '').trim()
            deviceText = deviceText.replace(/Virtual HBA Device/g, '').trim()

            deviceText = deviceText.replace(/scsi_host\d+/g, '').trim()

            deviceText = deviceText.replace(/\n/g, '<br>')

            const isRaidController = deviceText.toLowerCase().includes('raid') ||
                                   deviceText.toLowerCase().includes('sas') ||
                                   deviceText.toLowerCase().includes('broadcom') ||
                                   deviceText.toLowerCase().includes('lsi')

            if (isRaidController) {
              return
            }

            if (deviceType === 'physical') {
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

              if (parentHbaName && physicalHbaMap.has(parentHbaName)) {
                const parentIndex = physicalHbaMap.get(parentHbaName)
                hbaDevices[parentIndex].hasChildren = true
              }
            }
          })

          this.dataItems = hbaDevices
          this.updateHbaVmNames()

          this.$nextTick(() => {
            this.$forceUpdate()
            setTimeout(() => {
              this.$forceUpdate()
            }, 100)
          })
        } else {
          this.dataItems = []
        }
      } catch (error) {
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
      const vhbaDevices = []
      const vmAllocations = hbaData.vmallocations || {}

      hbaData.hostdevicesname.forEach((name, index) => {
        const deviceText = hbaData.hostdevicestext[index]

        if (this.isVhbaDevice({ hostDevicesName: name, hostDevicesText: deviceText })) {
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
          const processedDevices = new Set()

          for (const [deviceName, vmId] of entries) {
            if (vmId && !processedDevices.has(deviceName)) {
              try {
                const vmResponse = await api('listVirtualMachines', {
                  id: vmId,
                  listall: true
                })

                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm && vm.state !== 'Expunging') {
                  vmNamesMap[deviceName] = vm.name || vm.displayname
                } else {
                  try {
                    const xmlConfig = this.generateHbaDeallocationXmlConfig(deviceName)
                    const updateResponse = await api('updateHostHbaDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: deviceName,
                      virtualmachineid: null,
                      xmlconfig: xmlConfig,
                      isattach: false
                    })

                    if (!updateResponse || updateResponse.error) {
                      throw new Error(updateResponse?.error?.errortext || 'Failed to update HBA device')
                    }

                    vmNamesMap[deviceName] = this.$t(' ')
                    processedDevices.add(deviceName)

                    this.dataItems = this.dataItems.map(item => {
                      if (item.hostDevicesName === deviceName) {
                        return {
                          ...item,
                          virtualmachineid: null,
                          vmName: null,
                          isAssigned: false
                        }
                      }
                      return item
                    })
                    this.selectedDevices = this.selectedDevices.filter(device => device !== deviceName)
                  } catch (error) {
                    this.$notification.error({
                      message: this.$t('label.error'),
                      description: error.message || this.$t('message.update.hba.device.failed')
                    })
                  }
                }
              } catch (error) {
                vmNamesMap[deviceName] = this.$t(' ')
              }
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNamesMap }

          if (processedDevices.size > 0) {
            await this.fetchHbaDevices()
          }

          await this.updateVhbaVmNames()
        }
      } catch (error) {
      } finally {
        this.vmNameLoading = false
      }
    },

    async updateVhbaVmNames () {
      if (this.vmNameLoading) return

      this.vmNameLoading = true
      try {
        const response = await api('listVhbaDevices', {
          hostid: this.resource.id
        })

        if (response.listvhbadevicesresponse?.listvhbadevices?.[0]) {
          const vhbaData = response.listvhbadevicesresponse.listvhbadevices[0]
          const vmAllocations = vhbaData.vmallocations || {}

          const vmNamesMap = {}
          const processedDevices = new Set()

          for (const [deviceName, vmId] of Object.entries(vmAllocations)) {
            if (vmId && !processedDevices.has(deviceName)) {
              try {
                const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

                if (vm && vm.state !== 'Expunging') {
                  vmNamesMap[deviceName] = vm.name || vm.displayname
                } else {
                  try {
                    const xmlConfig = this.generateVhbaDeallocationXmlConfig(deviceName)
                    const updateResponse = await api('updateHostVhbaDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: deviceName,
                      virtualmachineid: null,
                      currentvmid: vmId,
                      xmlconfig: xmlConfig
                    })

                    if (!updateResponse || updateResponse.error) {
                      throw new Error(updateResponse?.error?.errortext || 'Failed to update vHBA device')
                    }

                    vmNamesMap[deviceName] = this.$t(' ')
                    processedDevices.add(deviceName)

                    this.dataItems = this.dataItems.map(item => {
                      if (item.hostDevicesName === deviceName) {
                        return {
                          ...item,
                          virtualmachineid: null,
                          vmName: null,
                          isAssigned: false
                        }
                      }
                      return item
                    })
                    this.selectedDevices = this.selectedDevices.filter(device => device !== deviceName)
                  } catch (error) {
                    this.$notification.error({
                      message: this.$t('label.error'),
                      description: error.message || this.$t('message.update.vhba.device.failed')
                    })
                  }
                }
              } catch (error) {
                vmNamesMap[deviceName] = this.$t(' ')
              }
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNamesMap }

          if (processedDevices.size > 0) {
            await this.fetchHbaDevices()
          }
        }
      } catch (error) {
      } finally {
        this.vmNameLoading = false
      }
    },

    async refreshVhbaDeviceList () {
      try {
        this.vhbaDevicesData = {}
        this.vhbaLoading = {}

        this.vmNames = {}

        await this.fetchHbaDevices()

        await this.updateVhbaVmNames()

        this.$nextTick(() => {
          this.$forceUpdate()
        })
      } catch (error) {
      }
    },

    async deallocateHbaDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.loading = true
      try {
        const response = await api('listHostHbaDevices', {
          id: this.resource.id
        })
        const devices = response.listhosthbadevicesresponse?.listhosthbadevices?.[0]
        const vmAllocations = devices?.vmallocations || {}

        const allocations = []
        for (const [deviceName, vmId] of Object.entries(vmAllocations)) {
          if (deviceName === record.hostDevicesName) {
            allocations.push({ deviceName, vmId })
          }
        }

        if (allocations.length === 0) {
          throw new Error('No VM allocation found for this device')
        }

        let targetVmId = null

        if (allocations.length === 1) {
          targetVmId = allocations[0].vmId
        } else {
          this.$confirm({
            title: '할당 해제할 VM 선택',
            content: '이 HBA 디바이스는 여러 VM에 할당되어 있습니다. 해제할 VM을 선택해주세요.',
            onOk: () => {
              targetVmId = allocations[0].vmId
            },
            onCancel: () => {
              this.loading = false
            }
          })
        }

        if (!targetVmId) {
          this.loading = false
          return
        }

        const vmName = this.vmNames[record.hostDevicesName] || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            const xmlConfig = this.generateHbaDeallocationXmlConfig(record.hostDevicesName)
            const detachResponse = await api('updateHostHbaDevices', {
              hostid: this.resource.id,
              hostdevicesname: record.hostDevicesName,
              virtualmachineid: null,
              currentvmid: targetVmId,
              xmlconfig: xmlConfig
            })
            if (!detachResponse || detachResponse.error) {
              throw new Error(detachResponse?.error?.errortext || 'Failed to detach HBA device')
            }

            this.dataItems = this.dataItems.map(item =>
              item.hostDevicesName === record.hostDevicesName && item.virtualmachineid === targetVmId
                ? { ...item, virtualmachineid: null, vmName: null, isAssigned: false }
                : item
            )

            this.$message.success(this.$t('message.success.remove.allocation'))

            if (record.parentHba) {
              delete this.vhbaDevicesData[record.parentHba]
              const parentHbaRecord = this.dataItems.find(item =>
                item.hostDevicesName === record.parentHba
              )
              if (parentHbaRecord) {
                await this.fetchVhbaListForHba(parentHbaRecord)
              }
            }

            this.vmNames = {}
            await this.fetchHbaDevices()
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
      if (record.deviceType === 'virtual') {
        return true
      }
      if (record.deviceType === 'physical') {
        return false
      }

      if (record.isVhba === true) {
        return true
      }
      if (record.isVhba === false) {
        return false
      }

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

            let wwnn = null
            if (record.hostDevicesText) {
              const wwnnMatch = record.hostDevicesText.match(/WWNN:\s*([0-9A-Fa-f]{16})/i)
              if (wwnnMatch) {
                wwnn = wwnnMatch[1]
              }
            }

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

              if (record.parentHba) {
                delete this.vhbaDevicesData[record.parentHba]
                const parentHbaRecord = this.dataItems.find(item =>
                  item.hostDevicesName === record.parentHba
                )
                if (parentHbaRecord) {
                  await this.fetchVhbaListForHba(parentHbaRecord)
                }
              }

              await this.fetchHbaDevices()
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

        const parentHbaName = record.hostDevicesName
        const vhbaName = `vhba_${parentHbaName.replace(/[^a-zA-Z0-9]/g, '_')}`

        this.selectedHbaDevice = record
        this.vhbaForm = {
          hbaDevice: parentHbaName,
          vhbaName: vhbaName
        }
        this.showVhbaCreateModal = true
      } catch (error) {
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

        let wwnn = ''
        let wwpn = ''

        if (this.selectedHbaDevice && this.selectedHbaDevice.hostDevicesText) {
          const hbaText = this.selectedHbaDevice.hostDevicesText

          const wwnnMatch = hbaText.match(/WWNN:\s*([0-9A-Fa-f]{16})/i)
          if (wwnnMatch) {
            wwnn = wwnnMatch[1]
          }

          const wwpnMatch = hbaText.match(/WWPN:\s*([0-9A-Fa-f]{16})/i)
          if (wwpnMatch) {
            wwpn = wwpnMatch[1]
          }

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

        const xmlContent = this.generateBasicVhbaXml(
          this.selectedHbaDevice.hostDevicesName
        )

        const params = {
          hostid: this.resource.id,
          parenthbaname: this.selectedHbaDevice.hostDevicesName,
          vhbaname: `vhba_${this.selectedHbaDevice.hostDevicesName.replace(/[^a-zA-Z0-9]/g, '_')}`,
          wwnn: wwnn,
          wwpn: wwpn,
          xmlconfig: xmlContent
        }

        const response = await api('createVhbaDevice', params)

        if (response && !response.error) {
          const realVhbaName = response.result || response.createdDeviceName || response.data || response.vhbaName
          if (realVhbaName) {
            this.$message.success(`${this.$t('message.success.create.vhba')} (${realVhbaName})`)
          } else {
            this.$message.success(this.$t('message.success.create.vhba'))
          }
          this.closeVhbaCreateModal()

          if (this.selectedHbaDevice) {
            const parentHbaName = this.selectedHbaDevice.hostDevicesName

            if (!this.vhbaDevicesData[parentHbaName]) {
              this.vhbaDevicesData[parentHbaName] = []
            }

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

            if (!this.expandedVhbaDevices[parentHbaName]) {
              this.expandedVhbaDevices[parentHbaName] = true
            }

            this.$nextTick(() => {
              this.$forceUpdate()
              setTimeout(() => {
                this.$forceUpdate()
              }, 50)
            })
          }

          setTimeout(async () => {
            await this.fetchHbaDevices()
          }, 500)
        } else {
          throw new Error(response?.error?.errortext || this.$t('message.error.create.vhba'))
        }
      } catch (error) {
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.create.vhba')
        })
      } finally {
        this.vhbaCreating = false
      }
    },

    async fetchVhbaDevices () {
      try {
        const response = await api('listVhbaDevices', { hostid: this.resource.id })
        if (response && response.listvhbadevicesresponse && response.listvhbadevicesresponse.vhbadevices) {
        }
      } catch (error) {
      }
    },

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

    async getHbaDeviceInfo (hbaDeviceName) {
      try {
        const response = await api('listHostHbaDevices', {
          id: this.resource.id
        })

        if (response.listhosthbadevicesresponse?.listhosthbadevices?.[0]) {
          const hbaData = response.listhosthbadevicesresponse.listhosthbadevices[0]

          const deviceIndex = hbaData.hostdevicesname.indexOf(hbaDeviceName)

          if (deviceIndex !== -1) {
            const deviceText = hbaData.hostdevicestext[deviceIndex]

            let wwpn = null
            let wwnn = null

            const wwpnMatch1 = deviceText.match(/wwpn[:\s]*([0-9A-Fa-f]{16})/i)
            const wwnnMatch1 = deviceText.match(/wwnn[:\s]*([0-9A-Fa-f]{16})/i)

            if (wwpnMatch1) wwpn = wwpnMatch1[1]
            if (wwnnMatch1) wwnn = wwnnMatch1[1]

            if (!wwpn) {
              const portNameMatch = deviceText.match(/port_name[:\s]*([0-9A-Fa-f]{16})/i)
              if (portNameMatch) wwpn = portNameMatch[1]
            }

            if (!wwnn) {
              const nodeNameMatch = deviceText.match(/node_name[:\s]*([0-9A-Fa-f]{16})/i)
              if (nodeNameMatch) wwnn = nodeNameMatch[1]
            }

            if (!wwpn || !wwnn) {
              const hexMatches = deviceText.match(/([0-9A-Fa-f]{16})/g)
              if (hexMatches && hexMatches.length > 0) {
                if (!wwpn) {
                  wwpn = hexMatches[0]
                }
                if (!wwnn && hexMatches.length > 1) {
                  wwnn = hexMatches[1]
                }
              }
            }

            if (!wwpn || !wwnn) {
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

        return {
          name: hbaDeviceName,
          description: '',
          wwpn: null,
          wwnn: null,
          pciAddress: hbaDeviceName
        }
      } catch (error) {
        return {
          name: hbaDeviceName,
          description: '',
          wwpn: null,
          wwnn: null,
          pciAddress: hbaDeviceName
        }
      }
    },

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
      if (this.selectedVhbaDevice && this.selectedVhbaDevice.parentHba) {
        const parentHbaRecord = this.dataItems.find(item =>
          item.hostDevicesName === this.selectedVhbaDevice.parentHba
        )
        if (parentHbaRecord) {
          delete this.vhbaDevicesData[this.selectedVhbaDevice.parentHba]
          this.fetchVhbaListForHba(parentHbaRecord)
        }
      }
      this.fetchHbaDevices()

      this.updateVhbaVmNames()

      this.$nextTick(() => {
        this.$forceUpdate()
      })
    },
    handleVhbaDeviceAllocated () {
      if (this.selectedVhbaDevice && this.selectedVhbaDevice.parentHba) {
        const parentHbaRecord = this.dataItems.find(item =>
          item.hostDevicesName === this.selectedVhbaDevice.parentHba
        )
        if (parentHbaRecord) {
          delete this.vhbaDevicesData[this.selectedVhbaDevice.parentHba]
          this.fetchVhbaListForHba(parentHbaRecord)
        }
      }
      this.fetchHbaDevices()
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
        const response = await api('listVhbaDevices', {
          hostid: this.resource.id
        })
        const devices = response.listvhbadevicesresponse?.listvhbadevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[record.hostDevicesName]

        if (!vmId) {
          throw new Error('No VM allocation found for this vHBA device')
        }

        const vmResponse = await api('listVirtualMachines', {
          id: vmId,
          listall: true
        })
        const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

        if (!vm) {
          throw new Error('VM not found')
        }

        const vmName = vm.name || vm.displayname || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            const xmlConfig = this.generateVhbaDeallocationXmlConfig(record.hostDevicesName)

            const detachResponse = await api('updateHostVhbaDevices', {
              hostid: this.resource.id,
              hostdevicesname: record.hostDevicesName,
              virtualmachineid: null,
              currentvmid: vmId,
              xmlconfig: xmlConfig
            })

            if (!detachResponse || detachResponse.error) {
              throw new Error(detachResponse?.error?.errortext || 'Failed to detach vHBA device')
            }

            this.dataItems = this.dataItems.map(item =>
              item.hostDevicesName === record.hostDevicesName
                ? { ...item, virtualmachineid: null, vmName: null, isAssigned: false }
                : item
            )

            this.$message.success(this.$t('message.success.remove.allocation'))

            if (record.parentHba) {
              delete this.vhbaDevicesData[record.parentHba]
              const parentHbaRecord = this.dataItems.find(item =>
                item.hostDevicesName === record.parentHba
              )
              if (parentHbaRecord) {
                await this.fetchVhbaListForHba(parentHbaRecord)
              }
            }

            this.vmNames = {}
            await this.fetchHbaDevices()
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

    generateVhbaDeallocationXmlConfig (vhbaDeviceName) {
      return `
        <hostdev mode='subsystem' type='scsi'>
          <source>
            <adapter name='${vhbaDeviceName}'/>
            <address bus='0' target='0' unit='0'/>
          </source>
        </hostdev>
      `.trim()
    },

    generateHbaDeallocationXmlConfig (hostDeviceName) {
      return `
        <hostdev mode='subsystem' type='scsi'>
          <source>
            <adapter name='${hostDeviceName}'/>
            <address bus='0' target='0' unit='0'/>
          </source>
        </hostdev>
      `.trim()
    },

    async getVhbaDeviceInfo (vhbaDeviceName) {
      try {
        const response = await api('listVhbaDevices', {
          hostid: this.resource.id
        })

        if (response.listvhbadevicesresponse?.listvhbadevices?.[0]) {
          const vhbaData = response.listvhbadevicesresponse.listvhbadevices[0]
          const deviceIndex = vhbaData.hostdevicesname?.indexOf(vhbaDeviceName)

          if (deviceIndex !== -1) {
            const deviceText = vhbaData.hostdevicestext?.[deviceIndex] || ''
            const wwpn = vhbaData.wwpns?.[deviceIndex] || ''
            const wwnn = vhbaData.wwnns?.[deviceIndex] || ''

            return {
              name: vhbaDeviceName,
              description: deviceText,
              wwpn: wwpn || null,
              wwnn: wwnn || null
            }
          }
        }

        return {
          name: vhbaDeviceName,
          description: '',
          wwpn: null,
          wwnn: null
        }
      } catch (error) {
        return {
          name: vhbaDeviceName,
          description: '',
          wwpn: null,
          wwnn: null
        }
      }
    },
    generateVhbaXmlConfig (vhbaName, wwpn, wwnn) {
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

    generateVhbaWwpn (parentWwpn, timestamp) {
      if (!parentWwpn || parentWwpn.length !== 16) {
        throw new Error('Parent HBA WWPN is required and must be 16 characters')
      }

      const prefix = parentWwpn.substring(0, 8)
      const suffix = timestamp.toString().slice(-8)
      return prefix + suffix
    },

    generateVhbaWwnn (parentWwnn, timestamp) {
      if (!parentWwnn || parentWwnn.length !== 16) {
        throw new Error('Parent HBA WWNN is required and must be 16 characters')
      }

      const prefix = parentWwnn.substring(0, 8)
      const suffix = timestamp.toString().slice(-8)
      return prefix + suffix
    },
    toggleVhbaList (record) {
      const isExpanded = this.expandedVhbaDevices[record.hostDevicesName]

      if (!isExpanded) {
        this.fetchVhbaListForHba(record)
      }

      this.expandedVhbaDevices[record.hostDevicesName] = !isExpanded
    },

    async fetchVhbaListForHba (physicalHbaRecord) {
      const hbaName = physicalHbaRecord.hostDevicesName

      if (this.vhbaLoading[hbaName] || this.vhbaDevicesData[hbaName]) {
        return
      }

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
          let formattedVhbaDevices = []

          const first = vhbaDevices[0]
          const isAggregate = first && Array.isArray(first.hostdevicesname) && Array.isArray(first.parenthbanames)

          if (isAggregate) {
            const names = first.hostdevicesname || []
            const texts = first.hostdevicestext || []
            const types = first.devicetypes || []
            const parents = first.parenthbanames || []
            const allocations = first.vmallocations || {}

            const allocatedVmIds = new Set()
            names.forEach((name, idx) => {
              const type = types[idx] || 'virtual'
              const parent = parents[idx] || ''
              if (type === 'virtual' && parent === hbaName) {
                const vmId = allocations[name] || null
                if (vmId) {
                  allocatedVmIds.add(vmId)
                }
              }
            })

            const vmNamesMap = {}
            if (allocatedVmIds.size > 0) {
              const vmIdArray = Array.from(allocatedVmIds)
              for (const vmId of vmIdArray) {
                try {
                  const vmResponse = await api('listVirtualMachines', {
                    id: vmId,
                    listall: true
                  })
                  const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                  if (vm && vm.state !== 'Expunging') {
                    vmNamesMap[vmId] = vm.name || vm.displayname
                  }
                } catch (error) {
                }
              }

              names.forEach((name, idx) => {
                const type = types[idx] || 'virtual'
                const parent = parents[idx] || ''
                if (type === 'virtual' && parent === hbaName) {
                  const vmId = allocations[name] || null
                  if (vmId && vmNamesMap[vmId]) {
                    this.vmNames[name] = vmNamesMap[vmId]
                  }
                }
              })
            }

            names.forEach((name, idx) => {
              const type = types[idx] || 'virtual'
              const parent = parents[idx] || ''
              if (type === 'virtual' && parent === hbaName) {
                const vmId = allocations[name] || null

                let vmName = ''
                if (vmId) {
                  vmName = vmNamesMap[vmId] || ''
                }

                formattedVhbaDevices.push({
                  key: `vhba-${hbaName}-${idx}`,
                  hostDevicesName: name,
                  hostDevicesText: String(texts[idx] || '').replace(/\n/g, '<br>'),
                  virtualmachineid: vmId,
                  vmName: vmName,
                  isAssigned: Boolean(vmId),
                  isPhysicalHba: false,
                  isVhba: true,
                  deviceType: 'virtual',
                  parentHba: hbaName,
                  indent: true,
                  isVhbaDevice: true,
                  wwnn: '',
                  wwpn: '',
                  status: 'Active'
                })
              }
            })
          } else {
            const filteredVhbaDevices = vhbaDevices.filter(vhba => {
              const parentHbaName = vhba.parenthbaname || vhba.parentHbaName
              return parentHbaName === hbaName
            })

            formattedVhbaDevices = filteredVhbaDevices.map((vhba, index) => ({
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
          }

          this.vhbaDevicesData[hbaName] = formattedVhbaDevices

          this.$nextTick(() => {
            this.$forceUpdate()
          })
        } else {
          this.vhbaDevicesData[hbaName] = []
        }
      } catch (error) {
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.fetch.vhba.devices')
        })
        this.vhbaDevicesData[hbaName] = []
      } finally {
        this.vhbaLoading[hbaName] = false
      }
    },

    getHbaDevicesWithVhba () {
      const result = []

      this.dataItems.forEach(item => {
        result.push(item)

        if (!this.isVhbaDevice(item) && this.expandedVhbaDevices[item.hostDevicesName]) {
          const vhbaDevices = this.vhbaDevicesData[item.hostDevicesName] || []
          result.push(...vhbaDevices)
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
          const vmNameMap = {}
          for (const name in vmAllocations) {
            const vmId = vmAllocations[name]
            if (vmId) {
              try {
                const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm && vm.state !== 'Expunging') {
                  vmNameMap[name] = vm.displayname || vm.name
                } else {
                  try {
                    const deviceIndex = scsiData.hostdevicesname.indexOf(name)
                    const actualDeviceText = deviceIndex !== -1 ? scsiData.hostdevicestext[deviceIndex] : ''
                    const xmlConfig = this.generateScsiXmlFromText(name, actualDeviceText)
                    const updateResponse = await api('updateHostScsiDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: name,
                      virtualmachineid: null,
                      currentvmid: vmId,
                      xmlconfig: xmlConfig,
                      isattach: false
                    })

                    if (!updateResponse || updateResponse.error) {
                    }
                  } catch (error) {
                  }
                }
              } catch (error) {
                try {
                  const deviceIndex = scsiData.hostdevicesname.indexOf(name)
                  const actualDeviceText = deviceIndex !== -1 ? scsiData.hostdevicestext[deviceIndex] : ''
                  const xmlConfig = this.generateScsiXmlFromText(name, actualDeviceText)
                  await api('updateHostScsiDevices', {
                    hostid: this.resource.id,
                    hostdevicesname: name,
                    virtualmachineid: null,
                    currentvmid: vmId,
                    xmlconfig: xmlConfig,
                    isattach: false
                  })
                } catch (detachError) {
                }
              }
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

          const lunAddrToVmId = {}
          try {
            const lunResponse = await api('listHostLunDevices', { id: this.resource.id })
            const lunData = lunResponse.listhostlundevicesresponse?.listhostlundevices?.[0]
            if (lunData && Array.isArray(lunData.hostdevicesname)) {
              for (let i = 0; i < lunData.hostdevicesname.length; i++) {
                const ltxt = lunData.hostdevicestext[i] || ''
                const addr = this.extractScsiAddressString(ltxt)
                if (addr) {
                  const vmId = lunData.vmallocations?.[lunData.hostdevicesname[i]] || null
                  if (vmId) lunAddrToVmId[addr] = vmId
                }
              }
            }
          } catch (e) {}

          for (const device of scsiDevices) {
            const scsiAddr = this.extractScsiAddressString(device.hostDevicesText)
            const mappedVmId = scsiAddr ? lunAddrToVmId[scsiAddr] : null

            let lunDevice = null
            const deviceMatch = device.hostDevicesText.match(/Device:\s*(\/dev\/[^\s\n]+)/)
            if (deviceMatch) {
              lunDevice = deviceMatch[1]
            }

            let vmIdByPath = null
            if (lunDevice) {
              try {
                const lunResponse = await api('listHostLunDevices', { id: this.resource.id })
                const lunData = lunResponse.listhostlundevicesresponse?.listhostlundevices?.[0]
                vmIdByPath = lunData?.vmallocations?.[lunDevice] || null
              } catch (e) {}
            }

            const finalVmId = mappedVmId || vmIdByPath
            if (finalVmId) {
              const vmResponse = await api('listVirtualMachines', { id: finalVmId, listall: true })
              const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
              if (vm && vm.state !== 'Expunging') {
                device.allocatedInOtherTab = {
                  isAllocated: true,
                  vmName: vm.displayname || vm.name,
                  vmId: finalVmId,
                  tabType: 'LUN'
                }
                device.vmName = vm.displayname || vm.name
                device.virtualmachineid = finalVmId
                device.isAssigned = true
              }
            }
          }

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
        this.dataItems = []
      } finally {
        this.loading = false
      }
    },

    extractScsiAddressString (text) {
      if (!text) return null
      let m = text.match(/SCSI_ADDRESS:\s*(\d+:\d+:\d+:\d+)/)
      if (m && m[1]) return m[1]
      m = text.match(/\[(\d+):(\d+):(\d+):(\d+)\]/)
      if (m) return `${m[1]}:${m[2]}:${m[3]}:${m[4]}`
      return null
    },

    async openScsiModal (record) {
      const isAllocatedInLun = await this.checkDeviceAllocationInLun(record.hostDevicesName)
      if (isAllocatedInLun) {
        try {
          const lunResp = await api('listHostLunDevices', { id: this.resource.id })
          const lun = lunResp?.listhostlundevicesresponse?.listhostlundevices?.[0]
          if (lun && lun.vmallocations && Array.isArray(lun.hostdevicesname)) {
            const deviceMatch = record.hostDevicesText && record.hostDevicesText.match(/Device:\s*(\/dev\/[^\s\n]+)/)
            const lunDevice = deviceMatch ? deviceMatch[1] : null
            if (lunDevice) {
              const vmId = lun.vmallocations[lunDevice]
              if (vmId) {
                const vmRes = await api('listVirtualMachines', { id: vmId, listall: true })
                const vm = vmRes.listvirtualmachinesresponse?.virtualmachine?.[0]
                const vmName = vm ? (vm.displayname || vm.name) : ''
                record.allocatedInOtherTab = { isAllocated: true, vmName, vmId, tabType: 'LUN' }
                record.vmName = vmName
                record.virtualmachineid = vmId
                record.isAssigned = true
                await this.deallocateScsiDevice(record)
                this.$forceUpdate()
              }
            }
          }
        } catch (e) {}
        return
      }

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

        if (record.allocatedInOtherTab) {
          vmId = record.allocatedInOtherTab.vmId
          vmName = record.allocatedInOtherTab.vmName
          isLunAllocated = record.allocatedInOtherTab.tabType === 'LUN'

          if (!vmId) {
            if (isLunAllocated) {
              const lunResponse = await api('listHostLunDevices', { id: this.resource.id })
              const lunData = lunResponse.listhostlundevicesresponse?.listhostlundevices?.[0]

              if (lunData?.vmallocations) {
                const deviceMatch = record.hostDevicesText.match(/Device:\s*(\/dev\/[^\s\n]+)/)
                if (deviceMatch) {
                  const lunDevice = deviceMatch[1]
                  vmId = lunData.vmallocations[lunDevice]
                }
              }
            }
          }
        } else {
          const response = await api('listHostScsiDevices', {
            id: this.resource.id
          })
          const devices = response.listhostscsidevicesresponse?.listhostscsidevices?.[0]
          const vmAllocations = devices?.vmallocations || {}
          vmId = vmAllocations[hostDevicesName]
          vmName = this.vmNames[hostDevicesName] || 'Unknown VM'
        }

        if (!vmId) {
          throw new Error('No VM allocation found for this device')
        }

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

        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            try {
              if (isLunAllocated) {
                const deviceMatch = record.hostDevicesText.match(/Device:\s*(\/dev\/[^\s\n]+)/)

                if (!deviceMatch) {
                  throw new Error(`LUN device mapping not found in SCSI device text: ${record.hostDevicesText}`)
                }

                const baseDevicePath = deviceMatch[1]

                const lunResponse = await api('listHostLunDevices', { id: this.resource.id })
                const lunData = lunResponse.listhostlundevicesresponse?.listhostlundevices?.[0]

                if (!lunData || !Array.isArray(lunData.hostdevicesname)) {
                  throw new Error('Failed to get LUN device list')
                }

                const scsiAddr = this.extractScsiAddressString(record.hostDevicesText)

                let foundLunDeviceName = null
                for (let i = 0; i < lunData.hostdevicesname.length; i++) {
                  const lunName = lunData.hostdevicesname[i]
                  const lunText = lunData.hostdevicestext[i] || ''
                  const lunAddr = this.extractScsiAddressString(lunText)

                  if (scsiAddr && lunAddr && scsiAddr === lunAddr) {
                    foundLunDeviceName = lunName
                    break
                  }

                  const lunBasePath = lunName.split(' (')[0]
                  if (lunBasePath === baseDevicePath) {
                    foundLunDeviceName = lunName
                    break
                  }
                }

                if (!foundLunDeviceName) {
                  throw new Error(`LUN device not found for path: ${baseDevicePath}`)
                }

                lunDeviceName = foundLunDeviceName

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
                  throw new Error(detachResponse?.error?.errortext || 'Failed to detach LUN device')
                }
              } else {
                const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
                const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

                let actualDeviceText = record.hostDevicesText || ''
                if (scsiData && scsiData.hostdevicesname) {
                  const deviceIndex = scsiData.hostdevicesname.indexOf(hostDevicesName)
                  if (deviceIndex !== -1 && scsiData.hostdevicestext) {
                    actualDeviceText = scsiData.hostdevicestext[deviceIndex] || actualDeviceText
                  }
                }

                const xmlConfig = this.generateScsiXmlFromText(hostDevicesName, actualDeviceText)

                const detachResponse = await api('updateHostScsiDevices', {
                  hostid: this.resource.id,
                  hostdevicesname: hostDevicesName,
                  virtualmachineid: null,
                  currentvmid: vmId,
                  xmlconfig: xmlConfig,
                  isattach: false
                })

                if (!detachResponse || detachResponse.error) {
                  throw new Error(detachResponse?.error?.errortext || 'Failed to detach SCSI device')
                }
              }

              this.dataItems = this.dataItems.map(item =>
                item.hostDevicesName === hostDevicesName
                  ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false, allocatedInOtherTab: null }
                  : item
              )

              this.$message.success(this.$t('message.success.remove.allocation'))

              await this.fetchScsiDevices()

              this.vmNames = {}
              this.$emit('device-allocated')
              this.$emit('close-action')
            } catch (error) {
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
        this.$notifyError(error.message || 'Failed to deallocate SCSI device')
      } finally {
        this.loading = false
      }
    },

    async isDeviceAllocatedInOtherTab (record, currentTab) {
      try {
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
            const deviceName = record.hostDevicesName

            let allocatedVmId = devices.vmallocations[deviceName]

            if (!allocatedVmId && currentTab === '5' && tabKey === '3') {
              const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
              const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]
              if (scsiData) {
                const scsiIndex = scsiData.hostdevicesname.indexOf(deviceName)
                if (scsiIndex !== -1) {
                  const scsiText = scsiData.hostdevicestext[scsiIndex]
                  const deviceMatch = scsiText.match(/Device:\s*(\/dev\/[^\s\n]+)/)
                  if (deviceMatch) {
                    const blockDevice = deviceMatch[1]
                    allocatedVmId = devices.vmallocations[blockDevice]
                  }
                }
              }
            }

            if (!allocatedVmId && currentTab === '3' && tabKey === '5') {
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
        return { isAllocated: false }
      }
    },

    shouldShowAllocationButton (record) {
      if (this.activeKey === '4') {
        const deviceName = String(record.hostDevicesName || '')
        if (deviceName.toUpperCase().includes('LUN') || deviceName.toLowerCase().includes('dm')) {
          return false
        }
      }

      if (this.isDeviceAssigned(record) || record.allocatedInOtherTab) {
        return true
      }

      return true
    },
    async fetchHostInfo () {
      const hostResponse = await api('listHosts', { id: this.resource.id })
      const host = hostResponse.listhostsresponse?.host?.[0]
      this.numericHostId = host.id
    },
    hasPartitionsFromText (hostDevicesText) {
      const hasPartitionsMatch = hostDevicesText.match(/HAS_PARTITIONS:\s*(true|false)/i)
      if (hasPartitionsMatch) {
        return hasPartitionsMatch[1].toLowerCase() === 'true'
      }

      return /has[\s_]?partitions\s*:\s*true/i.test(hostDevicesText)
    },

    isInUseFromText (hostDevicesText) {
      const usageStatusMatch = hostDevicesText.match(/USAGE_STATUS:\s*(사용중|사용안함)/i)
      if (usageStatusMatch) {
        return usageStatusMatch[1] === '사용중'
      }

      const inUseMatch = hostDevicesText.match(/IN_USE:\s*(true|false)/i)
      if (inUseMatch) {
        return inUseMatch[1].toLowerCase() === 'true'
      }

      return /in[\s_]?use\s*:\s*true/i.test(hostDevicesText)
    },

    isCephLvmVolume (deviceName) {
      return deviceName && deviceName.includes('ceph--') && deviceName.includes('--osd--block--')
    },

    formatHostDevicesText (text) {
      if (!text) return text

      let formattedText = text

      formattedText = formattedText.replace(/HAS_PARTITIONS:\s*false/gi, this.$t('label.no.partitions'))
      formattedText = formattedText.replace(/HAS_PARTITIONS:\s*true/gi, this.$t('label.has.partitions'))

      formattedText = formattedText.replace(/USAGE_STATUS:\s*사용안함/gi, '사용안함')
      formattedText = formattedText.replace(/USAGE_STATUS:\s*사용중/gi, '사용중')

      formattedText = formattedText.replace(/IN_USE:\s*false/gi, this.$t('label.not.in.use'))
      formattedText = formattedText.replace(/IN_USE:\s*true/gi, this.$t('label.in.use'))

      formattedText = formattedText.replace(/(?:\r\n|\r|\n)/g, '<br/>')

      return formattedText
    },

    async checkDeviceAllocationInScsi (deviceName) {
      try {
        if (!this.resource?.id) return false
        const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
        const scsiDevices = scsiResponse?.listhostscsidevicesresponse?.listhostscsidevices?.[0]
        if (scsiDevices && scsiDevices.vmallocations) {
          for (const [scsiDeviceName, vmId] of Object.entries(scsiDevices.vmallocations)) {
            if (vmId && this.isSamePhysicalDevice(deviceName, scsiDeviceName)) {
              return true
            }
          }
        }
        return false
      } catch (error) {
        return false
      }
    },

    async checkDeviceAllocationInLun (deviceName) {
      try {
        if (!this.resource?.id) return false
        const lunResponse = await api('listHostLunDevices', { id: this.resource.id })
        const lunDevices = lunResponse?.listhostlundevicesresponse?.listhostlundevices?.[0]
        if (lunDevices && lunDevices.vmallocations) {
          for (const [lunDeviceName, vmId] of Object.entries(lunDevices.vmallocations)) {
            if (vmId && this.isSamePhysicalDevice(deviceName, lunDeviceName)) {
              return true
            }
          }
        }
        return false
      } catch (error) {
        return false
      }
    },

    isSamePhysicalDevice (device1, device2) {
      const base1 = this.extractDeviceBase(device1)
      const base2 = this.extractDeviceBase(device2)

      return base1 === base2 || this.areRelatedDevices(base1, base2)
    },

    extractDeviceBase (deviceName) {
      if (deviceName.includes('(')) {
        const match = deviceName.match(/\(([^)]+)\)/)
        if (match) {
          const byIdName = match[1]
          if (byIdName.startsWith('scsi-')) {
            return byIdName
          }
        }
      }

      if (deviceName.startsWith('/dev/')) {
        return deviceName
      }

      return deviceName
    },

    areRelatedDevices (device1, device2) {
      return device1 === device2
    },

    generateScsiXmlFromText (hostDeviceName, hostDevicesText) {
      if (!hostDevicesText) {
        throw new Error(`Cannot generate SCSI XML: no device text provided for ${hostDeviceName}`)
      }

      const match = hostDevicesText.match(/\[(\d+):(\d+):(\d+):(\d+)\]/)
      if (!match) {
        throw new Error(`Cannot extract SCSI address from device text for ${hostDeviceName}: ${hostDevicesText}`)
      }

      const host = match[1]
      const bus = match[2]
      const target = match[3]
      const unit = match[4]

      const adapterName = `scsi_host${host}`

      return `
        <hostdev mode='subsystem' type='scsi'>
          <source>
            <adapter name='${adapterName}'/>
            <address bus='${bus}' target='${target}' unit='${unit}'/>
          </source>
        </hostdev>
      `.trim()
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
