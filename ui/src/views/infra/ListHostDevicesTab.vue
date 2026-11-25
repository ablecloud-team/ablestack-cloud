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
  <div class="host-devices-content">
    <resource-table-toolbar
      class="host-device-tools"
      searchable
      :search="searchQuery"
      :search-label="$t('label.host.device.search')"
      :loading="deviceListLoading"
      @update:search="searchQuery = $event"
      @search="commitSearch"
      @refresh="refreshDevices">
      <a-select
        id="host-device-kind"
        class="host-device-kind"
        :aria-label="$t('label.host.device.type')"
        :value="activeKey"
        :options="deviceOptions"
        @change="selectDeviceType" />
    </resource-table-toolbar>
    <a-alert v-if="deviceListFailed" type="error" show-icon :message="$t('message.host.device.read.failed')" />
    <section v-if="activeKey === '1'" class="host-device-list">
        <a-table
          :columns="currentColumns"
          :dataSource="filteredOtherDevices"
          :loading="deviceListLoading && !dataItems.length"
          :pagination="false"
          size="small"
          :scroll="{ x: 720 }">
          <template #headerCell="{ column }">
            <template v-if="column.key === 'hostDevicesText'">
              {{ $t('label.details') }}
            </template>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'hostDevicesName'">
              {{ record.hostDevicesName }}
            </template>
            <template v-if="column.key === 'hostDevicesText'"><span v-html="formatHostDevicesText(record.hostDevicesText)" style="white-space: pre-line; line-height: 1.6; display: block;"></span></template>
            <template v-if="column.key === 'vmName'">
              <span>{{ record.vmName || (record.virtualmachineid ? record.virtualmachineid : '—') }}</span>
              <a-tag v-if="deviceOwnerNote(record)" color="warning">{{ deviceOwnerNote(record) }}</a-tag>
            </template>
            <template v-if="column.key === 'action'">
              <div style="display: flex; align-items: center; gap: 8px;">
                <a-button
                  :type="isDeviceAssigned(record) ? 'danger' : 'primary'"
                  size="middle"
                  shape="circle"
                  :aria-label="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                  :title="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                  :disabled="pciDetachPending || !deviceActionAvailable('updateHostDevices')"
                  @click="isDeviceAssigned(record) ? deallocatePciDevice(record) : openPciModal(record)"
                  :loading="actionLoading">
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
      </section>

      <section v-if="activeKey === '2'" class="host-device-list">
          <a-table
          :columns="currentColumns"
          :dataSource="filteredHbaDevices"
          :loading="deviceListLoading && !dataItems.length"
          :pagination="false"
          size="small"
          :scroll="{ x: 720 }">
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
                  <span v-html="formatHostDevicesText(record.hostDevicesText)" style="white-space: pre-line; line-height: 1.6; display: block;"></span>
                </template>
              </div>
            </template>
            <template v-if="column.key === 'vmName'">
              <template v-if="!record.isSummary">
                <span>{{ record.vmName || (record.virtualmachineid ? record.virtualmachineid : '—') }}</span>
              <a-tag v-if="deviceOwnerNote(record)" color="warning">{{ deviceOwnerNote(record) }}</a-tag>
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
                    :loading="actionLoading"
                    :disabled="actionLoading || !deviceActionAvailable('createVhbaDevice')"
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
                    :loading="actionLoading"
                    :disabled="actionLoading || !deviceActionAvailable('deleteVhbaDevice')"
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
                    :loading="actionLoading"
                    :disabled="actionLoading || !deviceActionAvailable('updateHostVhbaDevices')"
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
                    :loading="actionLoading"
                    :disabled="actionLoading || !deviceActionAvailable('updateHostHbaDevices')"
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
      </section>

      <section v-if="activeKey === '3'" class="host-device-list">
        <a-table
          :columns="currentColumns"
          :dataSource="filteredUsbDevices"
          :loading="deviceListLoading && !dataItems.length"
          :pagination="false"
          size="small"
          :scroll="{ x: 720 }">
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
              <span v-html="formatHostDevicesText(record.hostDevicesText)" style="white-space: pre-line; line-height: 1.6; display: block;"></span>
            </template>
            <template v-if="column.dataIndex === 'vmName'">
              <span>{{ record.vmName || (record.virtualmachineid ? record.virtualmachineid : '—') }}</span>
              <a-tag v-if="deviceOwnerNote(record)" color="warning">{{ deviceOwnerNote(record) }}</a-tag>
            </template>
            <template v-if="column.key === 'action'">
              <div style="display: flex; align-items: center; gap: 8px;">
                <a-button
                  :type="isDeviceAssigned(record) ? 'danger' : 'primary'"
                  size="middle"
                  shape="circle"
                  :tooltip="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                  @click="isDeviceAssigned(record) ? deallocateUsbDevice(record) : openUsbModal(record)"
                  :loading="actionLoading"
                  :disabled="actionLoading || !deviceActionAvailable('updateHostUsbDevices')"
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
      </section>

      <section v-if="activeKey === '4'" class="host-device-list">
        <a-table
          :columns="currentColumns"
          :dataSource="filteredLunDevices"
          :loading="deviceListLoading && !dataItems.length"
          :pagination="false"
          size="small"
          :scroll="{ x: 720 }">
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
              <div>
                <span v-html="formatLunHostDevicesText(record.hostDevicesText, record)" style="white-space: pre-line; line-height: 1.6; display: block;"></span>
                <a-alert
                  v-if="lunAllocationReason(record)"
                  type="warning"
                  show-icon
                  :message="lunAllocationReason(record)"
                  style="margin-top: 8px;" />
                <div v-if="record.multipathLlFull" style="margin-top: 8px;">
                  <a-popover
                    placement="leftTop"
                    trigger="click"
                    :overlay-style="{ maxWidth: 'min(720px, 92vw)' }">
                    <template #content>
                      <pre style="white-space: pre-wrap; margin: 0; max-height: 70vh; overflow: auto; font-size: 12px; line-height: 1.45;">{{ record.multipathLlFull }}</pre>
                    </template>
                    <a-button
                      type="default"
                      size="small"
                      shape="circle"
                      :title="$t('label.details') + ' (multipath -ll)'">
                      <template #icon><plus-outlined /></template>
                    </a-button>
                  </a-popover>
                </div>
              </div>
            </template>
            <template v-if="column.dataIndex === 'vmName'">
              <span>{{ record.vmName || (record.virtualmachineid ? record.virtualmachineid : '—') }}</span>
              <a-tag v-if="deviceOwnerNote(record)" color="warning">{{ deviceOwnerNote(record) }}</a-tag>
            </template>
            <template v-if="column.key === 'action'">
              <div style="display: flex; align-items: center; gap: 8px;">
                <template v-if="activeKey === '4'">
                  <a-button
                    v-if="shouldShowAllocationButton(record)"
                    :disabled="lunAllocationBlocked(record) || !deviceActionAvailable('updateHostLunDevices')"
                    :title="lunAllocationReason(record)"
                    :type="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? 'danger' : 'primary'"
                    size="middle"
                    shape="circle"
                    :tooltip="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? $t('label.remove') : $t('label.create')"
                    @click="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? deallocateLunDevice(record) : openLunModal(record)"
                    :loading="actionLoading">
                    <template #icon>
                      <delete-outlined v-if="(isDeviceAssigned(record) || record.allocatedInOtherTab)" />
                      <plus-outlined v-else />
                    </template>
                  </a-button>
                </template>
                <template v-else>
                  <a-button
                    v-if="shouldShowAllocationButton(record)"
                    :disabled="lunAllocationBlocked(record) || !deviceActionAvailable('updateHostLunDevices')"
                    :title="lunAllocationReason(record)"
                    :type="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? 'danger' : 'primary'"
                    size="middle"
                    shape="circle"
                    :tooltip="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? $t('label.remove') : $t('label.create')"
                    @click="(isDeviceAssigned(record) || record.allocatedInOtherTab) ? deallocateLunDevice(record) : openLunModal(record)"
                    :loading="actionLoading">
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
      </section>

      <section v-if="activeKey === '5'" class="host-device-list">
        <a-table
          :columns="currentColumns"
          :dataSource="filteredScsiDevices"
          :loading="deviceListLoading && !dataItems.length"
          :pagination="false"
          size="small"
          :scroll="{ x: 720 }">
          <template #headerCell="{ column }">
            <template v-if="column.key === 'hostDevicesText'">
              {{ $t('label.details') }}
            </template>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'hostDevicesName'">
              <div style="white-space: pre-line; line-height: 1.4; min-height: 40px;">{{ formatDeviceName(record.scsiDisplayName || record.hostDevicesName) }}</div>
            </template>
            <template v-if="column.key === 'hostDevicesText'">
              <span v-html="formatScsiHostDevicesText(record.hostDevicesText)" style="white-space: pre-line; line-height: 1.6; display: block;"></span>
            </template>
            <template v-if="column.dataIndex === 'vmName'">
              <span>{{ record.vmName || (record.virtualmachineid ? record.virtualmachineid : '—') }}</span>
              <a-tag v-if="deviceOwnerNote(record)" color="warning">{{ deviceOwnerNote(record) }}</a-tag>
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
                  :loading="actionLoading"
                  :disabled="actionLoading || !deviceActionAvailable('updateHostScsiDevices')"
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
      </section>
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
import ResourceTableToolbar from '@/components/view/ResourceTableToolbar'
import { mixinDevice } from '@/utils/mixin.js'
import { getAPI, postAPI } from '@/api'
import { generatePciDeviceXml, matchesPciDeviceXml } from '@/utils/util'
import { deviceBlocked } from '@/utils/vmDevices'
import { IdcardOutlined, PlusOutlined, DeleteOutlined, FormOutlined, MinusOutlined } from '@ant-design/icons-vue'
import HostDevicesTransfer from '@/views/storage/HostDevicesTransfer'
import HostUsbDevicesTransfer from '@/views/storage/HostUsbDevicesTransfer'
import HostLunDevicesTransfer from '@/views/storage/HostLunDevicesTransfer'
import HostHbaDevicesTransfer from '@/views/storage/HostHbaDevicesTransfer'
import HostScsiDevicesTransfer from '@/views/storage/HostScsiDevicesTransfer'
import HostVhbaDevicesTransfer from '@/views/storage/HostVhbaDevicesTransfer'

const deviceViews = [
  { key: '1', id: 'other', label: 'label.other.devices' },
  { key: '2', id: 'hba', label: 'label.hba.devices' },
  { key: '3', id: 'usb', label: 'label.usb.devices' },
  { key: '4', id: 'lun', label: 'label.lun.devices' },
  { key: '5', id: 'scsi', label: 'label.scsi.devices' }
]
const deviceView = value => deviceViews.find(view => view.id === value || view.key === value) || deviceViews[0]
const searchFromQuery = value => typeof value === 'string' ? value : ''

export default {
  mixins: [mixinDevice],
  name: 'ListHostDevicesTab',
  components: {
    ResourceTableToolbar,
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
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      dataItems: [],
      actionLoading: false,
      showAddModal: false,
      selectedResource: null,
      searchQuery: searchFromQuery(this.$route.query.deviceq),
      activeKey: deviceView(this.$route.query.device).key,
      selectedDevices: [],
      selectedPciDevices: [],
      virtualmachines: [],
      showPciDeleteModal: false,
      selectedPciDevice: null,
      pciConfigs: {},
      vmNames: {},
      deviceReadVersion: 0,
      deviceListLoading: false,
      deviceListFailed: false,
      pciDetachPending: false,
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
    deviceOptions () { return deviceViews.map(view => ({ value: view.key, label: this.$t(view.label) })) },
    tableSource () {
      return this.dataItems.map((item, index) => ({
        ...item,
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
      const query = this.searchQuery.toLowerCase()
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

      const query = this.searchQuery.toLowerCase()
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
      const query = this.searchQuery.toLowerCase()
      const items = this.dataItems || []
      return items.filter(item => {
        const deviceName = String(item.hostDevicesName || '')
        const deviceText = String(item.hostDevicesText || '')

        const isLun = deviceName.startsWith('/dev/') ||
                     deviceName.startsWith('wwn-') ||
                     deviceName.startsWith('scsi-') ||
                     deviceName.startsWith('dm-') ||
                     deviceName.startsWith('nvme-')
        if (!query) return isLun
        return isLun && (
          deviceName.toLowerCase().includes(query) ||
          deviceText.toLowerCase().includes(query)
        )
      })
    },

    filteredHbaDevices () {
      const query = this.searchQuery.toLowerCase()
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
      const query = this.searchQuery.toLowerCase()
      return (this.dataItems || []).filter(item => {
        const deviceName = String(item.hostDevicesName || '')
        const displayName = String(item.scsiDisplayName || '')
        const deviceText = String(item.hostDevicesText || '')
        if (!query) return true
        return deviceName.toLowerCase().includes(query) ||
               displayName.toLowerCase().includes(query) ||
               deviceText.toLowerCase().includes(query)
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
  watch: {
    'resource.id' (id, previous) {
      if (id !== previous) {
        this.resetDeviceList()
        this.refreshDevices()
      }
    },
    '$route.query.device' (value) {
      const key = deviceView(value).key
      if (key !== this.activeKey) {
        this.activeKey = key
        this.resetDeviceList()
        this.refreshDevices()
      }
    },
    '$route.query.deviceq' (value) {
      this.searchQuery = searchFromQuery(value)
    },
    loading (loading, previous) {
      if (previous && !loading) this.refreshDevices()
    }
  },
  created () {
    this.refreshDevices()
  },
  unmounted () {
    this.deviceReadVersion++
  },
  methods: {
    formatDeviceName (deviceName) {
      if (!deviceName) return ''
      const match = deviceName.match(/^(.+?)\s*\((.+?)\)$/)
      return match ? match[1].trim() + '\n(' + match[2].trim() + ')' : deviceName
    },
    resetDeviceList () {
      this.deviceReadVersion++
      this.dataItems = []
      this.vmNames = {}
      this.selectedDevices = []
      this.expandedVhbaDevices = {}
      this.vhbaDevicesData = {}
      this.vhbaLoading = {}
      this.deviceListFailed = false
      this.deviceListLoading = false
    },
    refreshDevices () {
      return [
        this.fetchData,
        this.fetchHbaDevices,
        this.fetchUsbDevices,
        this.fetchLunDevices,
        this.fetchScsiDevices
      ][Number(this.activeKey) - 1]?.()
    },
    async readDeviceList (key, fetchRows) {
      const id = this.resource.id
      if (!id || this.activeKey !== key) return
      const version = ++this.deviceReadVersion
      const isCurrent = () => this.resource.id === id && this.activeKey === key && this.deviceReadVersion === version
      this.deviceListLoading = true
      if (key === '2') this.vhbaLoading = {}
      this.deviceListFailed = false
      try {
        const rows = await fetchRows(id)
        if (!isCurrent()) return
        const resolved = await this.lookupDeviceOwners(rows)
        if (!isCurrent()) return
        this.dataItems = resolved
        this.selectedDevices = resolved.filter(row => row.virtualmachineid).map(row => row.hostDevicesName)
        this.vmNames = Object.fromEntries(resolved.filter(row => row.vmName).map(row => [row.hostDevicesName, row.vmName]))
      } catch (error) {
        if (!isCurrent()) return
        this.deviceListFailed = true
        if (!this.dataItems.length) this.$notifyError(error)
      } finally {
        if (isCurrent()) this.deviceListLoading = false
      }
    },
    async lookupDeviceOwners (rows) {
      const ids = [...new Set(rows.map(row => row.virtualmachineid).filter(Boolean))]
      const owners = {}
      await Promise.all(ids.map(async id => {
        try {
          const result = await getAPI('listVirtualMachines', { id, listall: true }, { preserveOnFailure: true })
          const vm = result.listvirtualmachinesresponse?.virtualmachine?.find(item => item.id === id)
          owners[id] = vm
            ? { vmName: vm.displayname || vm.name, vmLookup: vm.state === 'Expunging' ? 'cleanup' : 'found' }
            : { vmLookup: 'unknown' }
        } catch {
          owners[id] = { vmLookup: 'failed' }
        }
      }))
      return rows.map(row => ({ ...row, ...owners[row.virtualmachineid] }))
    },
    deviceActionAvailable (api) {
      return Boolean(this.$store.getters.apis?.[api]) && !this.deviceListFailed
    },
    deviceOwnerNote (record) {
      const labels = {
        cleanup: 'label.host.device.cleanup.required',
        unknown: 'label.host.device.owner.unknown',
        failed: 'label.host.device.owner.failed'
      }
      return labels[record.vmLookup] ? this.$t(labels[record.vmLookup]) : ''
    },
    fetchData () {
      return this.readDeviceList('1', id => this.readNamedDevices('listHostDevices', 'listhostdevices', id))
    },
    async readNamedDevices (api, key, id) {
      const response = await getAPI(api, { id }, { preserveOnFailure: true })
      const data = response[key + 'response']?.[key]?.[0]
      return (data?.hostdevicesname || []).map((name, index) => ({
        key: name,
        hostDevicesName: name,
        hostDevicesText: data.hostdevicestext?.[index] || '',
        virtualmachineid: data.vmallocations?.[name] || null,
        isAssigned: Boolean(data.vmallocations?.[name])
      }))
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
      this.selectedResource = null
      return this.refreshDevices()
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
          const response = await getAPI('listHostDevices', { id: this.resource.id })
          const devices = response.listhostdevicesresponse?.listhostdevices?.[0]
          const vmAllocations = devices?.vmallocations || {}
          const vmId = vmAllocations[record.hostDevicesName]

          if (vmId) {
            const vmResponse = await getAPI('listVirtualMachines', {
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
      return this.closeAction()
    },
    handleDeviceAllocated () {
      return this.closeAction()
    },
    selectDeviceType (key) {
      if (key === this.activeKey) return
      const query = { ...this.$route.query, tab: 'listhostdevices', device: deviceView(key).id }
      delete query.deviceq
      this.$router.push({ query })
    },
    commitSearch (value) {
      const query = { ...this.$route.query }
      const keyword = value?.trim()
      if (keyword) query.deviceq = keyword
      else delete query.deviceq
      this.searchQuery = keyword || ''
      this.$router.replace({ query })
    },
    fetchUsbDevices () {
      return this.readDeviceList('3', id => this.readNamedDevices('listHostUsbDevices', 'listhostusbdevices', id))
    },
    fetchLunDevices () {
      return this.readDeviceList('4', async id => {
        const [singleSettled, multiSettled] = await Promise.allSettled([
          getAPI('listHostLunDevices', {
            id,
            lunpathmode: 'single',
            lunPathMode: 'single'
          }, { preserveOnFailure: true }),
          getAPI('listHostLunDevices', {
            id,
            lunpathmode: 'multipath',
            lunPathMode: 'multipath'
          }, { preserveOnFailure: true })
        ])
        const singleRes = singleSettled.status === 'fulfilled' ? singleSettled.value : null
        const multiRes = multiSettled.status === 'fulfilled' ? multiSettled.value : null

        if (singleSettled.status === 'rejected' && multiSettled.status === 'rejected') {
          throw singleSettled.reason || multiSettled.reason
        }

        const lun0 = singleRes?.listhostlundevicesresponse?.listhostlundevices?.[0]
        const lun1 = multiRes?.listhostlundevicesresponse?.listhostlundevices?.[0]

        const multiHasRows = lun1 && Array.isArray(lun1.hostdevicesname) && lun1.hostdevicesname.length > 0

        if (!lun0 && !lun1) return []

        const vmAllocations = { ...((lun0?.vmallocations) || {}), ...((lun1?.vmallocations) || {}) }
        const mergedPartitions = { ...((lun0?.haspartitions) || {}), ...((lun1?.haspartitions) || {}) }
        const mergedUsage = { ...((lun0?.deviceusagestatus) || {}), ...((lun1?.deviceusagestatus) || {}) }
        const mergedSafety = { ...((lun0?.devicesafetydetails) || {}), ...((lun1?.devicesafetydetails) || {}) }

        const rowMap = new Map()
        const ingestLunBlock = (lunData) => {
          if (!lunData?.hostdevicesname) return
          for (let i = 0; i < lunData.hostdevicesname.length; i++) {
            const name = lunData.hostdevicesname[i]
            const rawText = lunData.hostdevicestext[i] || ''
            const mpParts = rawText.split(/\nMULTIPATH_LL:\n/)
            const tableText = (mpParts[0] || '').replace(/\n/g, '<br>')
            const multipathLlFull = mpParts.length > 1 ? mpParts.slice(1).join('\nMULTIPATH_LL:\n').trim() : null
            if (rowMap.has(name)) {
              const ex = rowMap.get(name)
              if (multipathLlFull && !ex.multipathLlFull) {
                ex.multipathLlFull = multipathLlFull
              }
            } else {
              rowMap.set(name, {
                hostDevicesName: name,
                hostDevicesText: tableText,
                multipathLlFull,
                virtualmachineid: vmAllocations[name] || null,
                isAssigned: Boolean(vmAllocations[name]),
                usage: mergedUsage[name] || 'unknown',
                safety: mergedSafety[name],
                hasPartitions: mergedSafety[name]?.haspartitions === true || mergedPartitions[name] === true || /HAS_PARTITIONS:\s*true/i.test(rawText)
              })
            }
          }
        }
        // 호스트에 multipath LUN이 하나라도 있으면(mpatha 등) 싱글 경로(sd*)는 목록에 넣지 않음
        if (multiHasRows) {
          ingestLunBlock(lun1)
        } else {
          ingestLunBlock(lun1)
          ingestLunBlock(lun0)
        }

        const lunDevices = Array.from(rowMap.values()).map((r, idx) => ({
          ...r,
          key: idx
        }))

        const baseFiltered = lunDevices.filter(device =>
          device.hostDevicesName &&
          (device.hostDevicesName.startsWith('/dev/') ||
           device.hostDevicesName.startsWith('wwn-') ||
           device.hostDevicesName.startsWith('scsi-') ||
           device.hostDevicesName.startsWith('dm-') ||
           device.hostDevicesName.startsWith('nvme-')) &&
          !device.hostDevicesName.startsWith('/dev/sg')
        )

        if (multiHasRows) {
          return baseFiltered.filter(device => {
            const name = device.hostDevicesName || ''
            const text = (device.hostDevicesText || '').replace(/<br\s*\/?>/gi, '\n')
            if (device.multipathLlFull) return true
            if (text.includes('TYPE: multipath')) return true
            if (text.includes('MULTIPATH_LL')) return true
            if (/\/dev\/mapper\//i.test(name) || /\/dev\/dm-\d+/i.test(name)) return true
            if (/\bmpath[a-z0-9]*/i.test(name)) return true
            return false
          })
        }
        return baseFiltered
      })
    },
    async handlePciDeviceDelete () {
      try {
        const response = await getAPI('listHostDevices', { id: this.resource.id })
        const devices = response.listhostdevicesresponse?.listhostdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[this.selectedPciDevice.hostDevicesName]

        const vmResponse = await getAPI('listVirtualMachines', {
          id: vmId,
          listall: true,
          details: 'all'
        })
        const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

        if (!vm || vm.state !== 'Stopped') {
          this.$notification.warning({
            message: this.$t('label.warning'),
            description: this.$t('label.vmdevice.stopPci')
          })
          this.showPciDeleteModal = false
          this.selectedPciDevice = null
          this.pciConfigs = {}
          return
        }

        const params = { id: vm.id }

        Object.entries(vm.details || {}).forEach(([key, value]) => {
          if (key.startsWith('extraconfig-') && value.includes('<hostdev')) {
            if (!matchesPciDeviceXml(value, this.selectedPciDevice.hostDevicesName)) {
              params[`details[0].${key}`] = value
            }
          } else if (!key.startsWith('extraconfig-')) {
            params[`details[0].${key}`] = value
          }
        })

        await postAPI('updateVirtualMachine', params)

        await postAPI('updateHostDevices', {
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
        getAPI('listVirtualMachines', {
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
    async deallocateUsbDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }

      this.actionLoading = true
      const hostDevicesName = record.hostDevicesName

      try {
        const response = await getAPI('listHostUsbDevices', {
          id: this.resource.id
        })
        const devices = response.listhostusbdevicesresponse?.listhostusbdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = String(vmAllocations[hostDevicesName]) // 문자열로 변환

        if (!vmId || vmId === 'null' || vmId === 'undefined') {
          throw new Error('No VM allocation found for this device')
        }

        const vmResponse = await getAPI('listVirtualMachines', {
          id: vmId,
          listall: true
        })
        const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

        if (vm && vm.state !== 'Running') {
          this.$notification.warning({
            message: this.$t('label.warning'),
            description: this.$t('message.cannot.remove.device.vm.stop')
          })
          this.actionLoading = false
          return
        }

        const vmName = this.vmNames[hostDevicesName] || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.usbdevice')}`,
          onOk: async () => {
            // try {
            //   const vmResponse = await getAPI('listVirtualMachines', {
            //     id: vmId,
            //     listall: true
            //   })
            //   const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

            //   if (!vm) {
            //   } else if (vm.state === 'Expunging') {
            //   } else if (vm.state !== 'Running' && vm.state !== 'Stopped') {
            //   }
            // } catch (vmError) {
            // }

            const xmlConfig = this.generateXmlUsbConfig(hostDevicesName)

            const detachResponse = await postAPI('updateHostUsbDevices', {
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
        this.actionLoading = false
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

      const targetDevFromPath = (basePath || '').replace('/dev/', '')
      const isValidScsiTarget = /^sd[a-z]{1,2}$/.test(targetDevFromPath)
      const scsiAddr = this.extractScsiAddressString(this.resource?.hostDevicesText || '')
      const addressTag = scsiAddr
        ? `<address type='drive' controller='${scsiAddr.split(':')[0]}' bus='${scsiAddr.split(':')[1]}' target='${scsiAddr.split(':')[2]}' unit='${scsiAddr.split(':')[3]}'/>`
        : ''
      // 멀티패스(mapper/mpatha1 등): target dev는 백엔드에서 VM XML 기준 sdX로 채움. dev 넣으면 할당 실패
      const targetTag = isValidScsiTarget
        ? `<target dev='${targetDevFromPath}' bus='scsi'/>`
        : `<target bus='scsi'/>`
      return `
        <disk type='block' device='lun'>
          <driver name='qemu' type='raw' io='native' cache='none'/>
          <source dev='${actualDevicePath}'/>
          ${targetTag}
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

      this.actionLoading = true
      const hostDevicesName = this.selectedUsbDevice.hostDevicesName

      try {
        const response = await getAPI('listHostUsbDevices', {
          id: this.resource.id
        })
        const devices = response.listhostusbdevicesresponse?.listhostusbdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = String(vmAllocations[hostDevicesName]) // 문자열로 변환

        if (!vmId || vmId === 'null' || vmId === 'undefined') {
          throw new Error('No VM allocation found for this device')
        }

        try {
          const vmResponse = await getAPI('listVirtualMachines', {
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

        const detachResponse = await postAPI('updateHostUsbDevices', {
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
        this.actionLoading = false
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
      this.actionLoading = true
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
              const scsiResponse = await getAPI('listHostScsiDevices', { id: this.resource.id })
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
          const [singleSettled, multiSettled] = await Promise.allSettled([
            getAPI('listHostLunDevices', { id: this.resource.id, lunpathmode: 'single', lunPathMode: 'single' }),
            getAPI('listHostLunDevices', { id: this.resource.id, lunpathmode: 'multipath', lunPathMode: 'multipath' })
          ])

          const singleLun = singleSettled.status === 'fulfilled'
            ? singleSettled.value?.listhostlundevicesresponse?.listhostlundevices?.[0]
            : null
          const multiLun = multiSettled.status === 'fulfilled'
            ? multiSettled.value?.listhostlundevicesresponse?.listhostlundevices?.[0]
            : null
          const mergedVmAllocations = { ...(singleLun?.vmallocations || {}), ...(multiLun?.vmallocations || {}) }
          vmId = mergedVmAllocations[hostDevicesName] || record.virtualmachineid || null
          vmName = this.vmNames[hostDevicesName] || 'Unknown VM'

          if (!vmId) {
            try {
              const scsiResponse = await getAPI('listHostScsiDevices', { id: this.resource.id })
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
                        const vmResponse = await getAPI('listVirtualMachines', { id: vmId, listall: true })
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
          throw new Error(`VM 할당 정보를 찾을 수 없습니다: ${hostDevicesName}`)
        }

        const vmResponse = await getAPI('listVirtualMachines', {
          id: vmId,
          listall: true
        })
        const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

        if (!vm || vm.state !== 'Running') {
          this.$notification.warning({
            message: this.$t('label.warning'),
            description: this.$t('label.vmdevice.runningRequired')
          })
          this.actionLoading = false
          return
        }

        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            try {
              if (isScsiAllocated) {
                const scsiResponse = await getAPI('listHostScsiDevices', { id: this.resource.id })
                const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

                if (!scsiData) {
                  throw new Error('Failed to fetch SCSI devices data')
                }

                let scsiDeviceFound = false
                let actualLunDeviceName = hostDevicesName

                if (hostDevicesName.startsWith('/dev/sg')) {
                  const lunResponse = await getAPI('listHostLunDevices', { id: this.resource.id })
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

                    const detachResponse = await postAPI('updateHostScsiDevices', {
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
                const detachResponse = await postAPI('updateHostLunDevices', {
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
        this.actionLoading = false
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
      this.actionLoading = true
      const hostDevicesName = this.selectedLunDevice.hostDevicesName
      try {
        const response = await postAPI('updateHostLunDevices', {
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
        this.actionLoading = false
      }
    },
    async openLunModal (record) {
      if (this.lunAllocationBlocked(record)) {
        this.$notification.error({
          message: this.$t('label.error'),
          description: this.lunAllocationReason(record)
        })
        return
      }
      const isAllocatedInScsi = await this.checkDeviceAllocationInScsi(record.hostDevicesName)
      if (isAllocatedInScsi) {
        try {
          const scsiResponse = await getAPI('listHostScsiDevices', { id: this.resource.id })
          const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]
          if (scsiData?.vmallocations) {
            for (let i = 0; i < scsiData.hostdevicesname.length; i++) {
              const scsiText = scsiData.hostdevicestext[i]
              const deviceMatch = scsiText.match(/Device:\s*(\/dev\/[^\s\n]+)/)
              if (deviceMatch && deviceMatch[1] === record.hostDevicesName) {
                const sgDevice = scsiData.hostdevicesname[i]
                const vmId = scsiData.vmallocations[sgDevice]
                if (vmId) {
                  const vmRes = await getAPI('listVirtualMachines', { id: vmId, listall: true })
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

      this.selectedResource = {
        ...this.resource,
        hostDevicesName: record.hostDevicesName,
        hostDevicesText: record.hostDevicesText || '',
        usage: record.usage,
        safety: record.safety,
        hasPartitions: record.hasPartitions === true || this.hasPartitionsFromText(record.hostDevicesText || '')
      }
      this.showAddModal = true
    },
    async deallocatePciDevice (record) {
      const hostId = this.resource.id
      if (!hostId || this.pciDetachPending || !this.deviceActionAvailable('updateHostDevices')) return
      this.pciDetachPending = true
      this.actionLoading = true
      let confirmOpened = false
      const name = record.hostDevicesName
      try {
        const response = await getAPI('listHostDevices', { id: hostId }, { preserveOnFailure: true })
        const vmId = response.listhostdevicesresponse?.listhostdevices?.[0]?.vmallocations?.[name]
        if (!vmId || vmId !== record.virtualmachineid) {
          this.$message.warning(this.$t('message.host.device.allocation.changed'))
          await this.fetchData()
          return
        }
        const result = await getAPI('listVirtualMachines', { id: vmId, listall: true }, { preserveOnFailure: true })
        const vm = result.listvirtualmachinesresponse?.virtualmachine?.find(item => item.id === vmId)
        if (vm?.state !== 'Stopped') {
          this.$notification.warning({
            message: this.$t('label.warning'),
            description: this.$t('label.vmdevice.stopPci')
          })
          return
        }
        if (hostId !== this.resource.id || this.activeKey !== '1') return
        const vmName = vm.displayname || vm.name || vmId
        let submitting = false
        confirmOpened = true
        this.$confirm({
          title: vmName + ' ' + this.$t('message.delete.device.allocation'),
          content: vmName + ' ' + this.$t('message.confirm.delete.device'),
          onOk: async () => {
            if (submitting) return
            if (hostId !== this.resource.id || this.activeKey !== '1' || !this.deviceActionAvailable('updateHostDevices')) {
              this.pciDetachPending = false
              return
            }
            submitting = true
            try {
              await postAPI('updateHostDevices', {
                hostid: hostId,
                hostdevicesname: name,
                currentvmid: vmId,
                isattach: false
              }, { preserveOnFailure: true })
              this.$message.success(this.$t('message.success.remove.allocation'))
              this.pciDetachPending = false
              await this.fetchData()
              this.$emit('device-allocated')
              this.$emit('allocation-completed')
              this.$emit('close-action')
            } catch (error) {
              this.$notifyError(error)
              submitting = false
              throw error
            }
          },
          onCancel: () => { this.pciDetachPending = false }
        })
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.actionLoading = false
        if (!confirmOpened) this.pciDetachPending = false
      }
    },
    closePciDeleteModal () {
      this.showPciDeleteModal = false
      this.selectedPciDevice = null
    },

    fetchHbaDevices () {
      return this.readDeviceList('2', async id => {
        const response = await getAPI('listHostHbaDevices', {
          id
        }, { preserveOnFailure: true })

        if (response.listhosthbadevicesresponse?.listhosthbadevices?.[0]) {
          const hbaData = response.listhosthbadevicesresponse.listhosthbadevices[0]
          const vmAllocations = hbaData.vmallocations || {}

          const deviceTypes = hbaData.devicetypes || []
          const parentHbaNames = hbaData.parenthbanames || []

          const hbaDevices = []
          const physicalHbaMap = new Map() // 물리 HBA 이름 -> 인덱스 매핑

          hbaData.hostdevicesname.forEach((name, index) => {
            let deviceText = hbaData.hostdevicestext?.[index] || ''
            const vmId = vmAllocations[name] || null
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

          return hbaDevices
        }
        return []
      })
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

    async refreshVhbaDeviceList () {
      this.vhbaDevicesData = {}
      this.vhbaLoading = {}
      await this.fetchHbaDevices()
      await Promise.all(this.dataItems
        .filter(row => this.expandedVhbaDevices[row.hostDevicesName])
        .map(row => this.fetchVhbaListForHba(row)))
    },

    async deallocateHbaDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.actionLoading = true
      try {
        const response = await getAPI('listHostHbaDevices', {
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
              this.actionLoading = false
            }
          })
        }

        if (!targetVmId) {
          this.actionLoading = false
          return
        }

        const vmName = this.vmNames[record.hostDevicesName] || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            const xmlConfig = this.generateHbaDeallocationXmlConfig(record.hostDevicesName)
            const detachResponse = await postAPI('updateHostHbaDevices', {
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
        this.actionLoading = false
      }
    },
    openHbaModal (record) {
      const hostDevicesName = record.hostDevicesName
      this.selectedResource = { ...this.resource, hostDevicesName: hostDevicesName }
      this.showAddModal = true
    },
    generateXmlHbaConfig (hostDeviceName) {
      return generatePciDeviceXml(hostDeviceName)
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
            this.actionLoading = true

            let wwnn = null
            if (record.hostDevicesText) {
              const wwnnMatch = record.hostDevicesText.match(/WWNN:\s*([0-9A-Fa-f]{16})/i)
              if (wwnnMatch) {
                wwnn = wwnnMatch[1]
              }
            }

            const params = {
              hostid: this.resource.id
            }

            if (wwnn) {
              params.wwnn = wwnn
            } else {
              params.hostdevicesname = record.hostDevicesName
            }

            const response = await getAPI('deleteVhbaDevice', params)

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
        this.actionLoading = false
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

        const response = await getAPI('createVhbaDevice', params)

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
        const response = await getAPI('listVhbaDevices', { hostid: this.resource.id })
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
        const response = await getAPI('listHostHbaDevices', {
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
      this.actionLoading = true
      try {
        const response = await getAPI('listVhbaDevices', {
          hostid: this.resource.id
        })
        const devices = response.listvhbadevicesresponse?.listvhbadevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[record.hostDevicesName]

        if (!vmId) {
          throw new Error('No VM allocation found for this vHBA device')
        }

        const vmResponse = await getAPI('listVirtualMachines', {
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

            const detachResponse = await postAPI('updateHostVhbaDevices', {
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
        this.actionLoading = false
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
        const response = await getAPI('listVhbaDevices', {
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
      const hostId = this.resource.id
      const version = this.deviceReadVersion
      const isCurrent = () => this.activeKey === '2' && this.resource.id === hostId && this.deviceReadVersion === version

      if (this.vhbaLoading[hbaName] || this.vhbaDevicesData[hbaName]) {
        return
      }

      this.vhbaLoading[hbaName] = true

      try {
        const response = await getAPI('listVhbaDevices', {
          hostid: hostId,
          keyword: hbaName
        }, { preserveOnFailure: true })
        if (!isCurrent()) return

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

            names.forEach((name, idx) => {
              const type = types[idx] || 'virtual'
              const parent = parents[idx] || ''
              if (type === 'virtual' && parent === hbaName) {
                const vmId = allocations[name] || null

                formattedVhbaDevices.push({
                  key: `vhba-${hbaName}-${idx}`,
                  hostDevicesName: name,
                  hostDevicesText: String(texts[idx] || '').replace(/\n/g, '<br>'),
                  virtualmachineid: vmId,
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

          const resolved = await this.lookupDeviceOwners(formattedVhbaDevices)
          if (!isCurrent()) return
          this.vhbaDevicesData[hbaName] = resolved

          this.$nextTick(() => {
            this.$forceUpdate()
          })
        } else {
          this.vhbaDevicesData[hbaName] = []
        }
      } catch (error) {
        if (!isCurrent()) return
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.fetch.vhba.devices')
        })
        this.vhbaDevicesData[hbaName] = []
      } finally {
        if (isCurrent()) this.vhbaLoading[hbaName] = false
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
    fetchScsiDevices () {
      return this.readDeviceList('5', async id => {
        const rows = await this.readNamedDevices('listHostScsiDevices', 'listhostscsidevices', id)
        return rows
          .filter(row => row.hostDevicesName?.startsWith('/dev/sg'))
          .map(row => ({ ...row, scsiDisplayName: row.hostDevicesText.match(/Device:\s*(\S+)/i)?.[1] || row.hostDevicesName }))
      })
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
      this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
      this.showAddModal = true
    },

    async deallocateScsiDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.actionLoading = true
      const hostDevicesName = record.hostDevicesName
      try {
        let vmId = null
        let vmName = 'Unknown VM'

        if (record.allocatedInOtherTab && record.allocatedInOtherTab.tabType === 'SCSI') {
          vmId = record.allocatedInOtherTab.vmId
          vmName = record.allocatedInOtherTab.vmName
        }
        if (!vmId) {
          const response = await getAPI('listHostScsiDevices', {
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

        const vmResponse = await getAPI('listVirtualMachines', {
          id: vmId,
          listall: true
        })
        const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

        if (!vm || vm.state !== 'Running') {
          this.$notification.warning({
            message: this.$t('label.warning'),
            description: this.$t('label.vmdevice.runningRequired')
          })
          this.actionLoading = false
          return
        }

        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            try {
              const scsiResponse = await getAPI('listHostScsiDevices', { id: this.resource.id })
              const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

              let actualDeviceText = record.hostDevicesText || ''
              if (scsiData && scsiData.hostdevicesname) {
                const deviceIndex = scsiData.hostdevicesname.indexOf(hostDevicesName)
                if (deviceIndex !== -1 && scsiData.hostdevicestext) {
                  actualDeviceText = scsiData.hostdevicestext[deviceIndex] || actualDeviceText
                }
              }

              const xmlConfig = this.generateScsiXmlFromText(hostDevicesName, actualDeviceText)

              const detachResponse = await postAPI('updateHostScsiDevices', {
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
        this.actionLoading = false
      }
    },

    async isDeviceAllocatedInOtherTab (record, currentTab) {
      try {
        // SCSI 탭에서는 ListHostLunDeviceCommand를 쓰지 않음 — LUN 탭(4) 등에서만 SCSI 할당 여부 조회
        const otherTabs = {
          5: 'listHostScsiDevices'
        }

        for (const [tabKey, apiMethod] of Object.entries(otherTabs)) {
          if (tabKey === currentTab) continue

          const response = await getAPI(apiMethod, { id: this.resource.id })
          const key = `list${apiMethod.replace('listHost', '').toLowerCase()}response`
          const res = response[key]
          const devices = res?.listhostdevices?.[0] ||
                         res?.listhostscsidevices?.[0] ||
                         res?.listhostlundevices?.[0]

          if (devices?.vmallocations) {
            const deviceName = record.hostDevicesName

            let allocatedVmId = devices.vmallocations[deviceName]

            if (!allocatedVmId && currentTab === '4' && tabKey === '5') {
              const scsiResponse = await getAPI('listHostScsiDevices', { id: this.resource.id })
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
              const vmResponse = await getAPI('listVirtualMachines', { id: allocatedVmId, listall: true })
              const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
              if (vm) {
                return {
                  isAllocated: true,
                  vmName: vm.displayname || vm.name,
                  vmId: allocatedVmId,
                  tabType: 'SCSI'
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

    lunAllocationBlocked (record) {
      if (this.isDeviceAssigned(record) || record.allocatedInOtherTab) return false
      return deviceBlocked({ type: 'lun', usage: record.usage || 'unknown', safety: record.safety, hasPartitions: record.hasPartitions === true || record.safety?.haspartitions === true })
    },

    lunAllocationReason (record) {
      if (!this.lunAllocationBlocked(record)) return ''
      const verified = record.safety?.verified === true && record.safety.status === record.usage
      const usage = verified && ['mounted', 'host-volume', 'vm-connected', 'filesystem'].includes(record.usage) ? record.usage : 'unknown'
      const mountpoints = verified && Array.isArray(record.safety.mountpoints) ? record.safety.mountpoints.join(', ') : ''
      return [this.$t('label.vmdevice.usage.' + usage), mountpoints].filter(Boolean).join(' · ')
    },

    shouldShowAllocationButton (record) {
      if (this.isDeviceAssigned(record) || record.allocatedInOtherTab) {
        return true
      }

      return true
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

    /** SCSI 상세: Device: 줄은 이름 컬럼에 쓰므로 상세에서 제거 후 포맷 */
    formatScsiHostDevicesText (text) {
      if (!text) return text
      let t = String(text)
      t = t.replace(/\s*Device:\s*\S+/gi, '')
      return this.formatHostDevicesText(t)
    },

    formatHostDevicesText (text) {
      if (!text) return text

      let formattedText = text

      formattedText = formattedText.replace(/HAS_PARTITIONS:\s*false/gi, this.$t('label.no.partitions'))
      formattedText = formattedText.replace(/HAS_PARTITIONS:\s*true/gi, `<span class="lun-partitioned-text">${this.$t('label.has.partitions')}</span>`)

      formattedText = formattedText.replace(/USAGE_STATUS:\s*사용안함/gi, '사용안함')
      formattedText = formattedText.replace(/USAGE_STATUS:\s*사용중/gi, '사용중')

      formattedText = formattedText.replace(/IN_USE:\s*false/gi, this.$t('label.not.in.use'))
      formattedText = formattedText.replace(/IN_USE:\s*true/gi, this.$t('label.in.use'))
      const hasExistingLineBreaks = /[\r\n]/.test(formattedText)

      formattedText = formattedText.replace(/(?:\r\n|\r|\n)/g, '<br/>')
      let changed = true
      let loopCount = 0
      while (changed && loopCount < 20) {
        const before = formattedText
        loopCount++
        formattedText = formattedText.replace(/Fabric(\s*<br\/?>\s*)+WWN/gi, 'Fabric WWN')
        formattedText = formattedText.replace(/SCSI(\s*<br\/?>\s*)+Address/gi, 'SCSI Address')

        changed = (before !== formattedText)
      }

      if (!hasExistingLineBreaks) {
        formattedText = formattedText.replace(/([^<])\s+(TYPE|SIZE|SCSI_ADDRESS|SCSI\s+Address|Vendor|Model|Revision|Device|BY_ID|TRANSPORT|WWNN|WWPN|Fabric\s+WWN|Max\s+vPorts)\s*:/gi, '$1<br/>$2:')
        formattedText = formattedText.replace(/([^<]):\s*([^:\n<]+?)\s+([A-Z][A-Za-z_]{1,}\s*:)/g, '$1: $2<br/>$3')
        formattedText = formattedText.replace(/([^<])\s+([A-Z][A-Za-z_]{2,}\s*:)/g, '$1<br/>$2')
      } else {
        formattedText = formattedText.replace(/([^<]):\s*([^:\n<]+?)(\s+)(TYPE|SIZE|SCSI_ADDRESS|SCSI\s+Address|Vendor|Model|Revision|Device|BY_ID|TRANSPORT|WWNN|WWPN|Fabric\s+WWN|Max\s+vPorts)\s*:/gi, '$1: $2<br/>$4:')
      }

      // SIZE 값 출력 직후 줄바꿈 (다음 필드는 다음 줄)
      formattedText = formattedText.replace(/(SIZE:\s+[^\s<]+)(?!\s*<br\/?>)(\s+)(?=\S)/gi, '$1<br/>$2')

      return formattedText
    },
    formatLunHostDevicesText (text, record) {
      let formatted = this.formatHostDevicesText(text)
      if (!formatted) return formatted
      if (this.isDeviceAssigned(record) || record.allocatedInOtherTab) {
        const hasPartitionsBlue = `<span class="lun-partitioned-text">${this.$t('label.has.partitions')}</span>`
        const noPartitionsText = this.$t('label.no.partitions')
        formatted = formatted.split(noPartitionsText).join(hasPartitionsBlue)
        formatted = formatted.replace(/HAS_PARTITIONS:\s*false/gi, `<span class="lun-partitioned-text">${this.$t('label.has.partitions')}</span>`)
      }
      return formatted
    },

    async checkDeviceAllocationInScsi (deviceName) {
      try {
        if (!this.resource?.id) return false
        const scsiResponse = await getAPI('listHostScsiDevices', { id: this.resource.id })
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

    async checkDeviceAllocationInLun (_deviceName) {
      // SCSI UI에서는 LUN API를 호출하지 않음
      return false
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
  .host-device-kind {
    width: 180px;
    max-width: 100%;
  }

  .host-devices-content,
  .host-device-list {
    min-width: 0;
    max-width: 100%;
  }

  .ant-table-wrapper {
    margin: 12px 0;
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

  :deep(.lun-partitioned-text) {
    color: #1890ff;
    font-weight: 600;
  }
</style>
