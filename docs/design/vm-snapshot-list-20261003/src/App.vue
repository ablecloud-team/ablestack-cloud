<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for details.
Licensed under the Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <a-config-provider :locale="koKR">
    <a-layout class="mold-preview" data-app-framework="Vue 3 + Ant Design Vue 3.2.20">
      <a-layout-sider :width="256" :collapsed="collapsed" :collapsed-width="80" class="mold-sider">
        <div class="brand"><img :src="dark ? 'assets/logo-dark.png' : 'assets/logo-light.png'" alt="ABLESTACK" /></div>
        <a-menu mode="inline" :selected-keys="['snapshots']" :open-keys="collapsed ? [] : ['compute']">
          <a-menu-item key="dashboard"><dashboard-outlined /><span>대시보드</span></a-menu-item>
          <a-sub-menu key="compute"><template #title><cloud-outlined /><span>컴퓨트</span></template>
            <a-menu-item key="vm"><desktop-outlined /><span>가상머신</span></a-menu-item>
            <a-menu-item key="snapshots"><camera-outlined /><span>VM 스냅샷</span></a-menu-item>
            <a-menu-item key="kubernetes"><appstore-outlined /><span>쿠버네티스</span></a-menu-item>
            <a-menu-item key="autoscale"><cluster-outlined /><span>오토스케일 VM 그룹</span></a-menu-item>
            <a-menu-item key="groups"><group-outlined /><span>가상머신 그룹</span></a-menu-item>
            <a-menu-item key="ssh"><key-outlined /><span>SSH 키 쌍</span></a-menu-item>
            <a-menu-item key="userdata"><file-text-outlined /><span>사용자 데이터 라이브러리</span></a-menu-item>
          </a-sub-menu>
          <a-menu-item key="storage"><database-outlined /><span>스토리지</span></a-menu-item>
          <a-menu-item key="network"><share-alt-outlined /><span>네트워크</span></a-menu-item>
          <a-menu-item key="image"><picture-outlined /><span>이미지</span></a-menu-item>
          <a-menu-item key="keys"><lock-outlined /><span>키 관리</span></a-menu-item>
          <a-menu-item key="events"><notification-outlined /><span>이벤트</span></a-menu-item>
          <a-menu-item key="infra"><hdd-outlined /><span>인프라스트럭처</span></a-menu-item>
          <a-menu-item key="config"><setting-outlined /><span>구성</span></a-menu-item>
        </a-menu>
      </a-layout-sider>
      <a-layout class="workspace">
        <a-layout-header class="mold-header">
          <a-button type="text" aria-label="메뉴 접기" @click="collapsed = !collapsed"><menu-fold-outlined /></a-button>
          <a-select value="default" class="project-select" aria-label="프로젝트 보기"><a-select-option value="default">기본 보기</a-select-option></a-select>
          <span class="header-spacer" />
          <a-button type="text" @click="notify('공통 생성 메뉴 시연입니다.')">생성 <down-outlined /></a-button>
          <a-button type="text" aria-label="언어"><translation-outlined /></a-button>
          <a-button type="text" aria-label="알림"><bell-outlined /></a-button>
          <a-avatar size="small"><user-outlined /></a-avatar><span>admin cloud</span>
        </a-layout-header>
        <a-layout-content class="mold-content">
          <!-- Same order and grid as AutogenView: breadcrumb controls | actions + SearchView pattern. -->
          <a-card class="breadcrumb-card">
            <a-row :gutter="12" align="middle">
              <a-col :xs="24" :lg="12" class="breadcrumb-left">
                <a-breadcrumb><a-breadcrumb-item><home-outlined /></a-breadcrumb-item><a-breadcrumb-item>VM 스냅샷</a-breadcrumb-item></a-breadcrumb>
                <a-tooltip title="VM의 디스크 또는 디스크와 메모리 상태를 저장한 복원 지점입니다."><question-circle-outlined /></a-tooltip>
                <tooltip-button tooltip="업데이트" icon="reload-outlined" size="small" aria-label="업데이트" @on-click="refresh" />
                <a-select v-model:value="filters.state" size="small" class="state-select" aria-label="스냅샷 상태" @change="resetPage">
                  <a-select-option value="">모두</a-select-option><a-select-option value="Ready">사용 가능</a-select-option><a-select-option value="Creating">생성 중</a-select-option><a-select-option value="Error">오류</a-select-option>
                </a-select>
              </a-col>
              <a-col :xs="24" :lg="12" class="search-actions">
                <a-button v-if="selected.length" danger shape="round" @click="openBulk"><delete-outlined /> 선택 삭제 ({{ selected.length }})</a-button>
                <a-button v-else type="primary" shape="round" @click="openModal('create')"><plus-outlined /> 스냅샷 생성</a-button>
                <a-input-search v-model:value="query" placeholder="검색" allow-clear aria-label="VM 및 스냅샷 검색" @search="applySearch">
                  <template #addonBefore>
                    <a-popover v-model:visible="filterVisible" placement="bottomRight" trigger="click" title="검색 조건">
                      <template #content><a-form layout="vertical" class="search-filter-form">
                        <a-form-item label="가상머신"><a-select v-model:value="filters.vm" show-search option-filter-prop="label" aria-label="가상머신 필터"><a-select-option value="" label="모든 VM">모든 VM</a-select-option><a-select-option v-for="vm in vms" :key="vm" :value="vm" :label="vm">{{ vm }}</a-select-option></a-select></a-form-item>
                        <a-form-item label="유형"><a-select v-model:value="filters.type" aria-label="유형 필터"><a-select-option value="">모두</a-select-option><a-select-option value="Disk">디스크</a-select-option><a-select-option value="DiskAndMemory">디스크 + 메모리</a-select-option></a-select></a-form-item>
                        <a-form-item label="현재 기준점"><a-select v-model:value="filters.current" aria-label="현재 기준점 필터"><a-select-option value="">모두</a-select-option><a-select-option value="true">현재 기준점</a-select-option><a-select-option value="false">이전 기준점</a-select-option></a-select></a-form-item>
                        <a-space><a-button size="small" @click="resetFilters">초기화</a-button><a-button size="small" type="primary" @click="applySearch">검색</a-button></a-space>
                      </a-form></template>
                      <a-button size="small" aria-label="검색 조건"><filter-outlined /></a-button>
                    </a-popover>
                  </template>
                </a-input-search>
              </a-col>
            </a-row>
            <div v-if="activeFilters.length" class="filter-chips"><a-tag v-for="tag in activeFilters" :key="tag">{{ tag }}</a-tag><a href="#reset" @click.prevent="resetFilters">초기화</a></div>
          </a-card>
          <a-alert v-if="scenario === 'error'" type="warning" show-icon class="list-notice" message="최신 목록을 불러오지 못했습니다. 마지막 조회 결과를 유지합니다." description="작업 전 최신 상태 확인이 필요합니다."><template #action><a-button size="small" @click="refresh">다시 시도</a-button></template></a-alert>
          <a-alert v-if="selected.length" type="info" show-icon class="list-notice"><template #message>{{ selected.length }}개 선택 <a href="#clear-selection" class="selection-clear" @click.prevent="selected = []">선택 해제</a></template></a-alert>
          <div class="list-view-table-wrapper">
            <a-table :columns="columns" :data-source="pageRows" :pagination="false" size="middle" row-key="id" :loading="scenario === 'loading'" :row-selection="{ selectedRowKeys: selected, onChange: keys => selected = keys, columnWidth: 30 }" :custom-row="rowEvents" :scroll="{ x: 1160 }" @change="onSort">
              <template #headerCell="{ column }"><template v-if="column.key === 'actions'">
                <a-popover placement="bottomRight" trigger="click" title="표시할 열"><template #content><a-checkbox-group v-model:value="visibleColumns" :options="columnOptions" class="column-options" /></template><a-button type="text" size="small" aria-label="열 설정"><filter-outlined /></a-button></a-popover>
              </template><template v-else>{{ column.title }}</template></template>
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'title'"><a href="#snapshot-info" class="snapshot-link" @click.prevent="openModal('detail', record)">{{ record.title }}</a></template>
                <template v-else-if="column.key === 'vm'"><a href="#vm-snapshots" @click.prevent="openModal('relations', record)">{{ record.vm }}</a></template>
                <template v-else-if="column.key === 'state'"><snapshot-status :text="record.state" display-text :show-tooltip="false" /></template>
                <template v-else-if="column.key === 'type'">{{ typeText(record.type) }}</template>
                <template v-else-if="column.key === 'current'"><a-tooltip title="Cloud 메타데이터상 현재 기준점입니다. 현재 VM의 모든 변경 데이터가 포함되었다는 뜻은 아닙니다.">{{ record.current ? '예' : '아니오' }}</a-tooltip></template>
                <template v-else-if="column.key === 'parent'">{{ parentTitle(record) }}</template>
                <template v-else-if="column.key === 'created'"><a-tooltip title="Asia/Seoul (UTC+09:00)">{{ date(record.created) }}</a-tooltip></template>
              </template>
              <template #emptyText><a-empty :description="hasFilters ? '검색 결과가 없습니다.' : '등록된 VM 스냅샷이 없습니다.'"><a-button @click="hasFilters ? resetFilters() : openModal('create')">{{ hasFilters ? '검색 조건 초기화' : '스냅샷 생성' }}</a-button></a-empty></template>
            </a-table>
          </div>
          <a-pagination class="list-pagination" size="small" :current="page" :page-size="pageSize" :total="filteredRows.length" :page-size-options="['20','50','100']" :show-total="showTotal" show-size-changer show-quick-jumper @change="changePage">
            <template #buildOptionText="props">{{ props.value }} / 쪽</template>
          </a-pagination>
        </a-layout-content>
        <a-layout-footer class="mold-footer">ABLESTACK Europa <span>31번 클러스터 기준 · 예시 데이터</span></a-layout-footer>
      </a-layout>
    </a-layout>
    <!-- Preview controls are outside the product layout and never call a Cloud API. -->
    <a-popover placement="topRight" trigger="click" title="목업 시연 도구">
      <template #content><a-form layout="vertical" class="preview-options"><a-form-item label="화면 상태"><a-select v-model:value="scenario" aria-label="목업 화면 상태" @change="resetPage"><a-select-option value="normal">정상 목록</a-select-option><a-select-option value="loading">최초 로딩</a-select-option><a-select-option value="empty">등록된 스냅샷 없음</a-select-option><a-select-option value="error">조회 실패 · 이전 결과 유지</a-select-option><a-select-option value="partial">관계 데이터 일부 조회</a-select-option></a-select></a-form-item><a-button block @click="dark = !dark">{{ dark ? '라이트 보기' : '다크 보기' }}</a-button><p class="mock-note">Vue 3 + Ant Design Vue 3.2.20<br />실제 VM 작업 없이 예시만 표시합니다.</p></a-form></template>
      <a-button class="preview-control" size="small"><experiment-outlined /> 목업</a-button>
    </a-popover>
    <div v-if="menuRecord" ref="contextMenuElement" class="resource-context-menu" :style="menuStyle" role="menu" @click.stop @contextmenu.stop.prevent>
      <resource-action-menu :entries="menuEntries" :title="menuRecord.title" show-resource-title @execute="onMenuAction" />
    </div>
    <a-modal :visible="!!modal" :title="modalTitle" :width="modal === 'bulk' || modal === 'relations' ? 920 : 760" centered wrap-class-name="snapshot-modal" :destroy-on-close="true" @cancel="closeModal">
      <template #footer>
        <a-button @click="closeModal">{{ ['detail','relations'].includes(modal) ? '닫기' : '취소' }}</a-button>
        <template v-if="modal === 'detail'"><a-button :disabled="!!eligibility('restore', target)" @click="openModal('restore', target)">이 스냅샷으로 복원</a-button></template>
        <a-button v-if="['restore','delete','bulk'].includes(modal)" type="primary" :danger="modal !== 'restore'" :disabled="!acknowledged || !!modalBlocked" @click="submitDemo">{{ modal === 'restore' ? '이 스냅샷으로 복원' : modal === 'bulk' ? '선택한 스냅샷 삭제' : '스냅샷 삭제' }}</a-button>
        <a-button v-if="modal === 'create'" type="primary" :disabled="!createVM" @click="submitDemo">가상머신에서 생성 계속</a-button>
      </template>
      <template v-if="['detail','restore','delete'].includes(modal) && target">
        <a-alert v-if="modalBlocked" type="warning" show-icon :message="modalBlocked" class="dialog-notice" />
        <a-alert v-if="modal === 'restore'" type="warning" show-icon message="선택한 복원 지점 이후의 변경 내용이 사라질 수 있습니다." :description="target.type === 'DiskAndMemory' ? 'VM의 디스크와 저장된 메모리 상태를 함께 복원합니다.' : '디스크 상태를 복원합니다. 디스크 전용 복원은 VM 정지 상태에서 수행해야 합니다.'" class="dialog-notice" />
        <a-alert v-if="modal === 'delete'" type="warning" show-icon message="삭제한 복원 지점은 다시 사용할 수 없습니다." description="현재 VM은 삭제되지 않습니다. 디스크 체인 병합과 실제 공간 회수는 스토리지 구현에 따라 처리됩니다." class="dialog-notice" />
        <a-descriptions bordered size="small" :column="1">
          <a-descriptions-item label="가상머신">{{ target.vm }} <span class="secondary-text">({{ target.instance }})</span></a-descriptions-item>
          <a-descriptions-item label="현재 VM 상태"><status :text="target.vmState" display-text :show-tooltip="false" /></a-descriptions-item>
          <a-descriptions-item label="스냅샷 이름">{{ target.title }}</a-descriptions-item>
          <a-descriptions-item label="UUID"><a-typography-text :copyable="{ text: target.uuid }">{{ target.uuid }}</a-typography-text></a-descriptions-item>
          <a-descriptions-item label="내부 이름">{{ target.name }}</a-descriptions-item>
          <a-descriptions-item label="설명">{{ target.description }}</a-descriptions-item>
          <a-descriptions-item label="생성일">{{ date(target.created) }} (UTC+09:00)</a-descriptions-item>
          <a-descriptions-item label="유형">{{ typeText(target.type) }}</a-descriptions-item>
          <a-descriptions-item label="스냅샷 상태"><snapshot-status :text="target.state" display-text :show-tooltip="false" /></a-descriptions-item>
          <a-descriptions-item label="현재 기준점">{{ target.current ? '예' : '아니오' }}</a-descriptions-item>
          <a-descriptions-item label="부모 스냅샷">{{ parentTitle(target) }}</a-descriptions-item>
          <a-descriptions-item label="계정 / 도메인">{{ target.account }} / {{ target.domain }}</a-descriptions-item>
        </a-descriptions>
        <p v-if="modal === 'detail'" class="secondary-text">현재 기준점 표시는 Cloud 메타데이터를 따릅니다. 개별 물리 사용량과 회수 가능 공간은 API 근거가 없어 표시하지 않습니다.</p>
        <template v-else>
          <a-alert type="info" show-icon message="제출 직전에 대상과 최신 상태를 다시 확인합니다." description="VM·스냅샷 상태, 권한, 백업 정책과 진행 중인 작업이 바뀌면 실행을 차단합니다. 작업 결과는 비동기 작업 상태로 확인합니다." class="submission-notice" />
          <a-checkbox v-model:checked="acknowledged" class="confirmation-check">대상 VM과 스냅샷, 작업의 영향을 확인했습니다.</a-checkbox>
        </template>
      </template>
      <template v-if="modal === 'bulk'">
        <a-alert :type="modalBlocked ? 'warning' : 'warning'" show-icon :message="modalBlocked || '선택한 모든 스냅샷을 삭제합니다.'" description="실행 불가 항목이 있으면 선택 묶음을 시작하지 않습니다. 같은 VM의 삭제는 직렬로 처리합니다." class="dialog-notice" />
        <a-table size="middle" row-key="id" :pagination="false" :data-source="bulkRows" :columns="bulkColumns">
          <template #bodyCell="{ column, record }"><template v-if="column.key === 'reason'"><span :class="eligibility('delete', record) ? 'blocked-text' : ''">{{ eligibility('delete', record) || '삭제 가능' }}</span></template></template>
        </a-table>
        <a-checkbox v-model:checked="acknowledged" class="confirmation-check">선택한 {{ bulkRows.length }}개 스냅샷과 삭제 영향을 확인했습니다.</a-checkbox>
      </template>
      <template v-if="modal === 'create'">
        <a-form layout="vertical"><a-form-item label="가상머신" required><a-select v-model:value="createVM" show-search option-filter-prop="label" placeholder="가상머신을 선택하세요" aria-label="생성 대상 가상머신"><a-select-option v-for="vm in vms" :key="vm" :value="vm" :label="vm">{{ vm }}</a-select-option></a-select></a-form-item></a-form>
        <a-alert type="info" show-icon message="선택 VM의 기존 스냅샷 생성 흐름으로 연결합니다." description="지원 하이퍼바이저·스토리지, VM 상태와 백업 정책은 기존 검증 계약을 따릅니다." />
      </template>
      <template v-if="modal === 'relations' && target">
        <a-alert :type="scenario === 'partial' ? 'warning' : 'info'" show-icon :message="scenario === 'partial' ? '관계 데이터가 일부만 조회되었습니다.' : target.vm + '의 전체 스냅샷 관계'" :description="scenario === 'partial' ? '추가 조회가 필요합니다. 현재 데이터로 완전한 연결 관계를 판단할 수 없습니다.' : '목록의 검색어·상태·유형 조건을 적용하지 않고 선택 VM의 전체 메타데이터를 조회합니다.'" class="dialog-notice" />
        <a-row :gutter="24"><a-col :xs="24" :sm="10"><a-tree :tree-data="relationTree" default-expand-all :selected-keys="[relationSelected]" @select="keys => relationSelected = keys[0] || relationSelected" /></a-col>
          <a-col :xs="24" :sm="14"><a-descriptions v-if="relationRecord" bordered size="small" :column="1"><a-descriptions-item label="이름">{{ relationRecord.title }}</a-descriptions-item><a-descriptions-item label="생성일">{{ date(relationRecord.created) }}</a-descriptions-item><a-descriptions-item label="유형">{{ typeText(relationRecord.type) }}</a-descriptions-item><a-descriptions-item label="상태"><snapshot-status :text="relationRecord.state" display-text :show-tooltip="false" /></a-descriptions-item><a-descriptions-item label="현재 기준점">{{ relationRecord.current ? '예' : '아니오' }}</a-descriptions-item><a-descriptions-item label="부모">{{ parentTitle(relationRecord) }}</a-descriptions-item></a-descriptions></a-col></a-row>
        <p class="secondary-text">연결선은 Cloud의 부모 메타데이터 관계입니다. 실제 저장 파일의 직접 종속 관계나 회수 가능한 용량을 의미하지 않습니다.</p>
      </template>
      <p class="mock-note">목업 시연 · 실제 API 호출이나 VM 작업은 수행하지 않습니다.</p>
    </a-modal>
  </a-config-provider>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'
import koKR from 'ant-design-vue/es/locale/ko_KR'
import { DashboardOutlined, CloudOutlined, DesktopOutlined, CameraOutlined, AppstoreOutlined, ClusterOutlined, GroupOutlined, KeyOutlined, FileTextOutlined, DatabaseOutlined, ShareAltOutlined, PictureOutlined, LockOutlined, NotificationOutlined, HddOutlined, SettingOutlined, MenuFoldOutlined, DownOutlined, TranslationOutlined, BellOutlined, UserOutlined, HomeOutlined, QuestionCircleOutlined, PlusOutlined, DeleteOutlined, FilterOutlined, ExperimentOutlined, InfoCircleOutlined, BranchesOutlined, RollbackOutlined } from '@ant-design/icons-vue'
import Status from '@/components/widgets/Status.vue'
import TooltipButton from '@/components/widgets/TooltipButton.vue'
import ResourceActionMenu from '@/components/view/ResourceActionMenu.vue'
import { data, eligibility, typeText, statusText, date } from './mock-data'

// Extend the shared status renderer only for snapshot states missing Korean text.
const SnapshotStatus = { ...Status, methods: { ...Status.methods, getText () { return this.displayText && statusText(this.text) !== this.text ? statusText(this.text) : Status.methods.getText.call(this) } } }
const dark = ref(true), collapsed = ref(false), query = ref(''), search = ref(''), page = ref(1), pageSize = ref(20), scenario = ref('normal'), selected = ref([]), filterVisible = ref(false)
watch(dark, value => { document.body.classList.toggle('dark-mode', value); document.documentElement.classList.toggle('dark-mode', value) }, { immediate: true })
const filters = reactive({ vm: '', state: '', type: '', current: '' })
const vms = [...new Set(data.map(x => x.vm))]
const sort = reactive({ key: 'created', order: 'descend' })
const visibleColumns = ref(['title','vm','state','type','current','parent','created','account','domain'])
const definitions = [
  { key:'title', title:'이름', dataIndex:'title', width:205, sorter:true }, { key:'vm', title:'가상머신', dataIndex:'vm', width:205, sorter:true },
  { key:'state', title:'상태', dataIndex:'state', width:110 }, { key:'type', title:'유형', dataIndex:'type', width:145 },
  { key:'current', title:'현재', dataIndex:'current', width:75 }, { key:'parent', title:'부모 스냅샷', width:155 },
  { key:'created', title:'생성일', dataIndex:'created', width:185, sorter:true }, { key:'account', title:'계정', dataIndex:'account', width:90 }, { key:'domain', title:'도메인', dataIndex:'domain', width:90 }
]
const columnOptions = definitions.map(x => ({ label:x.title, value:x.key, disabled:x.key === 'title' }))
const columns = computed(() => [...definitions.filter(x => visibleColumns.value.includes(x.key)).map(x => ({ ...x, sortOrder:x.key === sort.key ? sort.order : null })), { key:'actions', width:35 }])
const activeFilters = computed(() => [search.value && '검색: ' + search.value, filters.vm && 'VM: ' + filters.vm, filters.type && typeText(filters.type), filters.current && (filters.current === 'true' ? '현재 기준점' : '이전 기준점')].filter(Boolean))
const hasFilters = computed(() => !!(search.value || filters.vm || filters.state || filters.type || filters.current))
const filteredRows = computed(() => {
  if (scenario.value === 'empty') return []
  const q = search.value.toLowerCase()
  return data.filter(x => (!q || [x.title,x.description,x.vm,x.instance,x.name,x.uuid].some(v => v.toLowerCase().includes(q))) && (!filters.vm || x.vm === filters.vm) && (!filters.state || x.state === filters.state) && (!filters.type || x.type === filters.type) && (!filters.current || String(x.current) === filters.current)).sort((a,b) => (String(a[sort.key]).localeCompare(String(b[sort.key]), 'ko', { numeric:true }) || a.id.localeCompare(b.id)) * (sort.order === 'ascend' ? 1 : -1))
})
const pageRows = computed(() => filteredRows.value.slice((page.value-1)*pageSize.value, page.value*pageSize.value))
const showTotal = total => `전체 ${total}개 항목 중 ${total ? (page.value-1)*pageSize.value+1 : 0}-${Math.min(page.value*pageSize.value,total)} 표시`
function resetPage () { page.value = 1; menuRecord.value = null }
function applySearch () { search.value = query.value.trim(); filterVisible.value = false; resetPage() }
function resetFilters () { Object.assign(filters, { vm:'',state:'',type:'',current:'' }); query.value = ''; search.value = ''; applySearch() }
function changePage (p,s) { page.value = p; pageSize.value = s; menuRecord.value = null }
function onSort (_, __, sorter) { sort.key = sorter.order ? sorter.columnKey : 'created'; sort.order = sorter.order || 'descend'; resetPage() }
const notify = text => message.info(text)
function refresh () { scenario.value = 'normal'; notify('예시 목록을 갱신했습니다. 기존 검색·정렬·선택을 유지합니다.') }
const parentTitle = row => data.find(x => x.id === row.parent)?.title || '없음'

const menuRecord = ref(null), contextMenuElement = ref(null), menuPoint = reactive({ x:0,y:0 })
const menuStyle = computed(() => ({ left: menuPoint.x + 'px', top:menuPoint.y + 'px' }))
const menuEntries = computed(() => [
  { key:'relations', label:'VM별 관계 보기', icon:'BranchesOutlined', group:'STORAGE' },
  { key:'restore', label:'이 스냅샷으로 복원', icon:'RollbackOutlined', group:'STORAGE', disabled:!!eligibility('restore',menuRecord.value), tooltip:eligibility('restore',menuRecord.value) },
  { key:'detail', label:'상세 정보', icon:'InfoCircleOutlined', group:'GENERAL' },
  { key:'delete', label:'스냅샷 삭제', icon:'DeleteOutlined', group:'DANGER', danger:true, disabled:!!eligibility('delete',menuRecord.value), tooltip:eligibility('delete',menuRecord.value) }
])
// Keep ListView's right-click entry and ResourceContextMenu's viewport/dismiss contract.
async function showMenu (row,event) {
  event.preventDefault(); event.stopPropagation()
  menuRecord.value = Object.freeze({ ...row })
  menuPoint.x = event.clientX; menuPoint.y = event.clientY
  await nextTick()
  if (menuRecord.value?.id !== row.id || !contextMenuElement.value) return
  const rect = contextMenuElement.value.getBoundingClientRect()
  // Exclude the page scrollbar from the visible area when clamping.
  menuPoint.x = Math.max(8,Math.min(event.clientX,document.documentElement.clientWidth-rect.width-8))
  menuPoint.y = Math.max(8,Math.min(event.clientY,document.documentElement.clientHeight-rect.height-8))
}
const rowEvents = row => ({ onContextmenu: event => showMenu(row,event) })
function onMenuAction ({ key }) { const record = menuRecord.value; openModal(key,record) }
const closeMenu = () => { menuRecord.value = null }
const dismissMenu = event => { if (event.key === 'Escape') menuRecord.value = null }
const dismissOutside = event => { if (menuRecord.value && !contextMenuElement.value?.contains(event.target)) closeMenu() }
onMounted(() => {
  document.addEventListener('pointerdown', dismissOutside, true)
  document.addEventListener('keydown', dismissMenu)
  window.addEventListener('resize', closeMenu)
  window.addEventListener('scroll', dismissOutside, true)
})
onUnmounted(() => {
  document.removeEventListener('pointerdown', dismissOutside, true)
  document.removeEventListener('keydown', dismissMenu)
  window.removeEventListener('resize', closeMenu)
  window.removeEventListener('scroll', dismissOutside, true)
})

const modal = ref(''), target = ref(null), acknowledged = ref(false), bulkRows = ref([]), createVM = ref(undefined), relationSelected = ref('')
const modalTitle = computed(() => ({ detail:'VM 스냅샷 상세 정보', restore:'VM 스냅샷 복원', delete:'VM 스냅샷 삭제', bulk:`선택한 VM 스냅샷 삭제 (${bulkRows.value.length}개)`, create:'VM 스냅샷 생성', relations:'VM별 스냅샷 관계' })[modal.value] || '')
const modalBlocked = computed(() => modal.value === 'bulk' ? (bulkRows.value.some(x => eligibility('delete',x)) ? '삭제할 수 없는 항목이 포함되어 있습니다. 목록에서 선택을 수정해 주세요.' : '') : ['restore','delete'].includes(modal.value) ? eligibility(modal.value,target.value) : '')
function openModal (kind,row) { menuRecord.value = null; modal.value = kind; target.value = row ? Object.freeze({ ...row }) : null; acknowledged.value = false; relationSelected.value = row?.id || ''; createVM.value = undefined }
function openBulk () { bulkRows.value = selected.value.map(id => data.find(x => x.id === id)).filter(Boolean).map(x => Object.freeze({ ...x })); openModal('bulk') }
function closeModal () { modal.value = ''; target.value = null }
function submitDemo () { const label = modalTitle.value; closeModal(); notify(label + ' 시연입니다. 실제 작업은 실행하지 않았습니다.') }
const bulkColumns = [{ key:'vm',title:'가상머신',dataIndex:'vm',width:205 },{ key:'title',title:'스냅샷 이름',dataIndex:'title',width:190 },{ key:'reason',title:'검증 결과' }]
const relationRows = computed(() => data.filter(x => x.vm === target.value?.vm))
const relationRecord = computed(() => relationRows.value.find(x => x.id === relationSelected.value))
const relationTree = computed(() => {
  const rows = scenario.value === 'partial' ? relationRows.value.slice(0,1) : relationRows.value
  const nodes = new Map(rows.map(x => [x.id,{ key:x.id,title:x.title + (x.current ? ' · 현재' : ''),children:[] }]))
  const roots = []
  rows.forEach(x => { if (x.parent && nodes.has(x.parent)) nodes.get(x.parent).children.push(nodes.get(x.id)); else roots.push(nodes.get(x.id)) })
  return roots
})
</script>

<style>
/* AutogenView/ListView geometry, using current theme tokens rather than a new layout. */
.mold-preview { min-height:100vh; font-size:14px; }
.workspace { min-width:0; overflow-x:clip; }
.mold-sider { position:sticky; top:0; height:100vh; overflow:auto; overflow-x:hidden; z-index:10; background:var(--ui-bg-surface) !important; border-right:1px solid var(--ui-border); }
.brand { height:64px; padding:12px; display:flex; align-items:center; }
.brand img { width:100%; max-width:230px; max-height:42px; object-fit:contain; }
.mold-sider .ant-menu { background:var(--ui-bg-surface); border-right:0; }
.mold-sider .ant-menu-item { margin-top:0; margin-bottom:0; height:40px; line-height:40px; }
.mold-sider .ant-menu-inline .ant-menu-item:not(:last-child) { margin-bottom:0; }
.mold-sider .ant-menu-item-selected { background:var(--ui-bg-selected) !important; color:var(--ui-link); }
.mold-sider .ant-menu-submenu-title { margin:0; }
.mold-header { position:sticky; top:0; z-index:30; height:64px; line-height:64px; padding:0 24px 0 12px; display:flex; align-items:center; gap:12px; background:var(--ui-bg-surface) !important; border-bottom:1px solid var(--ui-border); }
.project-select { width:32%; min-width:150px; }
.header-spacer { flex:1; }
.mold-content { padding:16px 12px; }
.breadcrumb-card { position:sticky; top:64px; z-index:20; margin:-16px -24px 12px; border-radius:0; }
.breadcrumb-card .ant-card-body { padding:24px; }
.breadcrumb-left { display:flex; align-items:center; gap:10px; padding-left:12px !important; min-height:38px; }
.breadcrumb-left .ant-breadcrumb { display:inline; vertical-align:text-bottom; }
.state-select { width:100px; }
.search-actions { display:flex; align-items:center; justify-content:flex-end; gap:10px; }
.search-actions .ant-input-search { flex:1; width:auto; }
.search-actions .ant-btn-round { white-space:nowrap; }
.ant-input-search .ant-input-group-addon:first-child { padding:0; background:var(--ui-bg-input); border-color:var(--ui-border); }
.ant-input-search .ant-input-group-addon:first-child .ant-btn { height:30px; border:0; width:30px; }
.search-filter-form { width:290px; }
.ant-popover-title { color:var(--ui-text-secondary); border-color:var(--ui-border); }
.search-filter-form .ant-form-item { margin-bottom:16px; }
.filter-chips { padding-top:12px; padding-left:12px; }
.list-notice { margin-bottom:10px; }
.selection-clear { margin-left:18px; }
.list-view-table-wrapper .ant-table-thead > tr > th { background:var(--ui-bg-surface); }
.list-view-table-wrapper .ant-table-thead .ant-btn { height:22px; line-height:20px; }
.list-view-table-wrapper .ant-table-cell { white-space:nowrap; }
.list-view-table-wrapper .ant-table-middle .ant-table-tbody > tr > td,.list-view-table-wrapper .ant-table-middle .ant-table-thead > tr > th { padding:12px 8px; }
.list-view-table-wrapper .ant-table-tbody > tr.ant-table-row-selected > td { background:var(--ui-bg-selected); }
.list-pagination { margin-top:10px; margin-bottom:10px; text-align:right; }
.column-options { display:flex; flex-direction:column; gap:10px; }
.mold-footer { padding:12px 24px; font-size:12px; color:var(--ui-text-muted) !important; }
.mold-footer span { margin-left:12px; }
.preview-control { position:fixed; bottom:8px; right:16px; z-index:20; }
.preview-options { width:270px; }
.mock-note { color:var(--ui-text-muted); font-size:12px; margin:16px 0 0; }
.mold-preview .ant-btn-dangerous:not([disabled]) { color:var(--ui-error-icon); border-color:var(--ui-error-border); }
.secondary-text { color:var(--ui-text-muted); }
.dialog-notice { margin-bottom:16px; }
.submission-notice { margin-top:16px; }
.blocked-text { color:var(--ui-error-icon); }
.confirmation-check { margin-top:20px; }
/* AntD centered modal: fixed title/footer, only its content body scrolls. */
.snapshot-modal .ant-modal { padding-bottom:0; top:0; max-width:calc(100vw - 32px); }
.snapshot-modal .ant-modal-content { display:flex; flex-direction:column; max-height:calc(100vh - 48px); max-height:calc(100dvh - 48px); }
.snapshot-modal .ant-modal-header,.snapshot-modal .ant-modal-footer { flex:0 0 auto; }
.snapshot-modal .ant-modal-body { overflow-y:auto; min-height:0; overscroll-behavior:contain; }
.snapshot-modal .ant-descriptions-item-label { width:148px; }
.snapshot-modal .ant-descriptions-bordered .ant-descriptions-item-label { background:var(--ui-bg-input); color:var(--ui-text-secondary); }
.snapshot-modal .ant-descriptions-bordered .ant-descriptions-view,.snapshot-modal .ant-descriptions-bordered .ant-descriptions-row,.snapshot-modal .ant-descriptions-bordered .ant-descriptions-item-label,.snapshot-modal .ant-descriptions-bordered .ant-descriptions-item-content { border-color:var(--ui-border); }
.snapshot-modal .ant-descriptions-item-content { overflow-wrap:anywhere; }
.snapshot-modal .ant-modal-footer { padding:12px 16px; }
@media(max-width:991px) { .search-actions { margin-top:12px; } .mold-header { gap:6px; } .mold-header > span:last-child { display:none; } }
@media(max-width:600px) { .mold-sider { display:none; } .mold-content { padding:16px 12px; } .breadcrumb-card { margin-left:-12px; margin-right:-12px; } .breadcrumb-card .ant-card-body { padding:16px 12px; } .project-select { min-width:120px; width:140px; } .mold-header { padding:0 8px; } .search-actions { gap:6px; } .snapshot-modal .ant-modal-body { padding:16px; } }
</style>
