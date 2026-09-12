<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

<!-- Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE file. -->
<template>
  <div>
  <template v-if="'manageDrCheckpoints' in $store.getters.apis">
    <a-tooltip v-if="toolbar" title="미정리 자원">
      <a-button shape="circle" class="action-button-item" aria-label="미정리 자원" @click="open">
        <template #icon><FolderOpenOutlined /></template>
      </a-button>
    </a-tooltip>
    <a-button v-else size="small" @click="open">
      {{ planId ? '체크포인트 관리' : '미정리 자원' }}
    </a-button>
  </template>
  <a-modal v-model:visible="visible" :title="planId ? '체크포인트 보존 및 수동 정리' : '등록 해제 후 미정리 자원'" :width="1100" :footer="null">
    <a-alert type="info" show-icon :message="planId ? '최신·사용 중 복구 지점은 보존합니다. 아래 정책은 수동 정리 후보를 계산하며 자동 삭제하지 않습니다.' : '계획 등록은 해제됐지만 원격 자원 정리는 확인되지 않았습니다. VM과 볼륨은 보존됩니다.'" />
    <a-space v-if="planId" style="margin: 16px 0">
      <span>최근 보존 개수</span><a-input-number v-model:value="keepCount" :min="2" :max="10000" />
      <span>보존 기간(일)</span><a-input-number v-model:value="keepDays" :min="0" :max="36500" />
      <a-button :loading="loading" @click="fetch(false)">미리보기</a-button>
      <a-button :loading="loading" @click="fetch(true)">정책 저장</a-button>
      <a-button danger :disabled="!selected.length || loading" @click="confirm = true">선택한 세트 정리</a-button>
    </a-space>
    <a-alert v-if="error" type="error" show-icon :message="error" style="margin: 12px 0" />
    <a-table
v-if="planId"
size="small"
:loading="loading"
:columns="columns"
:dataSource="sets"
rowKey="checkpointRef"
:row-selection="rowSelection"
:pagination="{ pageSize: 10 }"
:scroll="{ x: 900 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'bytes'">{{ formatBytes(record.logicalBytes) }} / {{ record.allocatedBytes == null ? '측정 불가' : formatBytes(record.allocatedBytes) }}</template>
        <template v-else-if="column.key === 'reason'">{{ (record.protectedReasons || []).join(', ') || (record.state === 'DELETED' ? '정리 완료' : '정리 가능') }}</template>
        <template v-else-if="column.key === 'disks'"><div v-for="disk in record.disks" :key="disk.device">{{ disk.device }}: {{ disk.locator }}</div></template>
      </template>
    </a-table>
    <a-table v-else size="small" :loading="loading" :columns="recordColumns" :dataSource="records" rowKey="id" :pagination="{ pageSize: 10 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'disks'"><div v-for="disk in record.disks || []" :key="disk.device">{{ disk.device }}: {{ disk.canonicalLocator }}</div><span v-if="!record.disks">저장 위치 확인 필요</span></template>
      </template>
    </a-table>
    <p v-if="planId">할당량은 공유 블록을 포함할 수 있어 실제 회수 가능 용량과 다릅니다. RBD 물리 사용량은 추정하지 않습니다.</p>
  </a-modal>
  <a-modal v-model:visible="confirm" title="선택한 체크포인트 세트 정리" :confirmLoading="loading" :okButtonProps="{ danger: true, disabled: !ack || !reason.trim() }" @ok="remove">
    <p>선택한 {{ selected.length }}개 복구 지점의 불변 데이터만 삭제합니다. 원본 VM과 운영 볼륨은 삭제하지 않습니다.</p>
    <a-alert v-if="error" type="error" show-icon :message="error" style="margin-bottom: 12px" />
    <a-input v-model:value="reason" placeholder="정리 사유" :maxlength="1024" />
    <a-checkbox v-model:checked="ack" style="margin-top: 16px">선택한 복구 지점을 더 이상 사용하지 않음을 확인했습니다.</a-checkbox>
  </a-modal>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { FolderOpenOutlined } from '@ant-design/icons-vue'
export default {
  name: 'DrCheckpointManager',
  components: { FolderOpenOutlined },
  props: {
    planId: { type: String, default: '' },
    toolbar: { type: Boolean, default: false }
  },
  data () {
    return {
      visible: false,
      confirm: false,
      loading: false,
      ack: false,
      reason: '',
      error: '',
      keepCount: 5,
      keepDays: 0,
      sets: [],
      records: [],
      selected: [],
      columns: [
        { title: '체크포인트', dataIndex: 'sequence' },
        { title: '상태', dataIndex: 'state' },
        { title: '논리 / 할당 용량', key: 'bytes' },
        { title: '보존 사유', key: 'reason' },
        { title: '소유 자원', key: 'disks' }
      ],
      recordColumns: [
        { title: '계획 이름', dataIndex: 'planName' },
        { title: '계획 UUID', dataIndex: 'planUuid' },
        { title: '정리 상태', dataIndex: 'state' },
        { title: '보존 자원 위치', key: 'disks' }
      ]
    }
  },
  computed: {
    rowSelection () {
      return {
        selectedRowKeys: this.selected,
        onChange: keys => { this.selected = keys },
        getCheckboxProps: record => ({ disabled: record.eligible !== true || record.state === 'DELETED' })
      }
    }
  },
  methods: {
    open () { this.visible = true; this.fetch(false, true) },
    async fetch (savePolicy = false, initial = false) {
      this.loading = true
      this.error = ''
      this.selected = []
      try {
        const params = this.planId ? { planuuid: this.planId, ...(initial ? {} : { keepcount: this.keepCount, keepdays: this.keepDays }), savepolicy: savePolicy } : {}
        const response = await (savePolicy ? postAPI : getAPI)('manageDrCheckpoints', params)
        const details = JSON.parse(response.managedrcheckpointsresponse.checkpointmanagement.details)
        if (details.policy) { this.keepCount = details.policy.keepCount; this.keepDays = details.policy.keepDays }
        this.sets = details.sets || []
        this.records = details.records || []
      } catch (e) { this.error = e?.response?.data?.managedrcheckpointsresponse?.errortext || e?.response?.data?.errorresponse?.errortext || e.message || String(e) } finally { this.loading = false }
    },
    async remove () {
      this.loading = true
      this.error = ''
      try {
        const selection = this.sets.filter(item => this.selected.includes(item.checkpointRef))
          .map(item => ({ checkpointRef: item.checkpointRef, manifestSha256: item.manifestSha256 }))
        await postAPI('manageDrCheckpoints', { planuuid: this.planId, keepcount: this.keepCount, keepdays: this.keepDays, selection: JSON.stringify(selection), reason: this.reason })
        this.confirm = false
        this.ack = false
        this.reason = ''
        await this.fetch()
      } catch (e) { this.error = e?.response?.data?.managedrcheckpointsresponse?.errortext || e?.response?.data?.errorresponse?.errortext || e.message || String(e) } finally { this.loading = false }
    },
    formatBytes (value) {
      const gib = Number(value) / 1073741824
      return gib >= 1 ? gib.toFixed(2) + ' GiB' : (Number(value) / 1048576).toFixed(2) + ' MiB'
    }
  }
}
</script>
