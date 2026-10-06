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
<template>
  <a-config-provider :locale="koKR">
    <a-layout class="mold-detail-preview">
      <a-layout-sider :width="256" :collapsed="device === 'tablet'" :collapsed-width="80" class="mold-sider" v-if="device !== 'mobile'">
        <div class="brand"><img :src="dark ? 'assets/logo-dark.png' : 'assets/logo-light.png'" alt="ABLESTACK" /></div>
        <a-menu mode="inline" :selected-keys="['snapshots']" :open-keys="device === 'desktop' ? ['compute'] : []">
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
          <a-menu-item key="network"><wifi-outlined /><span>네트워크</span></a-menu-item>
          <a-menu-item key="image"><picture-outlined /><span>이미지</span></a-menu-item>
          <a-menu-item key="keys"><lock-outlined /><span>키 관리</span></a-menu-item>
          <a-menu-item key="events"><schedule-outlined /><span>이벤트</span></a-menu-item>
          <a-menu-item key="project"><project-outlined /><span>프로젝트</span></a-menu-item>
          <a-menu-item key="account"><team-outlined /><span>계정</span></a-menu-item>
          <a-menu-item key="domain"><block-outlined /><span>도메인</span></a-menu-item>
          <a-menu-item key="infra"><bank-outlined /><span>인프라스트럭쳐</span></a-menu-item>
          <a-menu-item key="config"><setting-outlined /><span>구성</span></a-menu-item>
        </a-menu>
      </a-layout-sider>
      <a-layout class="workspace">
        <a-layout-header class="mold-header">
          <menu-fold-outlined />
          <a-select value="default" class="project-select" aria-label="프로젝트 보기"><a-select-option value="default"><project-outlined /> 기본 보기</a-select-option></a-select>
          <span class="header-spacer" />
          <a-button type="primary">생성 <down-outlined /></a-button>
          <translation-outlined /><bell-outlined /><a-avatar size="small">AC</a-avatar><span class="account-label">admin cloud</span>
        </a-layout-header>
        <a-layout-content class="mold-content">
          <a-card class="breadcrumb-card">
            <a-row align="middle" justify="space-between" :gutter="12">
              <a-col class="breadcrumb-left"><a-breadcrumb><a-breadcrumb-item><a href="#" @click.prevent><home-outlined /></a></a-breadcrumb-item><a-breadcrumb-item><a href="#" @click.prevent>VM 스냅샷</a></a-breadcrumb-item><a-breadcrumb-item>{{ snapshot.displayname }}</a-breadcrumb-item></a-breadcrumb><question-circle-outlined /><a-button size="small" shape="round" @click="refresh"><reload-outlined /> 업데이트</a-button></a-col>
              <a-col><a-dropdown :trigger="['click']"><a-button type="primary"><down-outlined /> 작업</a-button><template #overlay><a-menu @click="showActionNotice"><a-menu-item key="relation"><branches-outlined /> 스냅샷 관계</a-menu-item><a-menu-item key="restore"><rollback-outlined /> 복원</a-menu-item><a-menu-item key="delete" danger><delete-outlined /> 삭제</a-menu-item></a-menu></template></a-dropdown></a-col>
            </a-row>
          </a-card>
          <ResourceLayout>
            <template #left>
              <a-card class="snapshot-info-card">
                <div class="snapshot-name"><camera-outlined /><h4>{{ snapshot.displayname }}</h4></div>
                <a-tag>{{ snapshot.type }}</a-tag><a-tag>KVM</a-tag><a-divider />
                <div class="resource-detail-item"><strong>상태</strong><div><Status text="Ready" :display-text="true" :show-tooltip="false" /></div></div>
                <div class="resource-detail-item"><strong>아이디</strong><div><barcode-outlined /> <a href="#" @click.prevent>{{ snapshot.id }}</a></div></div>
                <div class="resource-detail-item"><strong>VM 이름</strong><div><desktop-outlined /> <a href="#" @click.prevent>{{ snapshot.virtualmachinename }}</a></div></div>
                <div class="resource-detail-item"><strong>Zone</strong><div><global-outlined /> <a href="#" @click.prevent>Zone</a></div></div>
                <div class="resource-detail-item"><strong>계정</strong><div><user-outlined /> <a href="#" @click.prevent>admin</a></div></div>
                <div class="resource-detail-item"><strong>도메인</strong><div><block-outlined /> <a href="#" @click.prevent>ROOT</a></div></div>
                <div class="resource-detail-item"><strong>생성일</strong><div><calendar-outlined /> {{ snapshot.created }}</div></div>
                <a-divider /><strong>태그</strong><div class="tag-placeholder"><plus-outlined /> 새 태그</div>
              </a-card>
            </template>
            <template #right>
              <a-card class="spin-content resource-content-card" :bordered="true">
                <a-tabs :active-key="activeTab" :tab-position="tabPosition" :animated="false" @change="changeTab">
                  <a-tab-pane key="details" tab="상세">
                    <a-list class="resource-details-list" size="small" :data-source="fields"><template #renderItem="{ item }"><a-list-item><div class="detail-field"><strong>{{ item.label }}</strong><br /><span>{{ item.value }}</span></div></a-list-item></template></a-list>
                  </a-tab-pane>
                  <a-tab-pane v-if="eventsAllowed" key="events" tab="이벤트">
                    <a-table :columns="eventColumns" :data-source="events" size="middle" row-key="id" :pagination="false" :scroll="{ x: 650 }"><template #bodyCell="{ column, record }"><a v-if="column.key === 'type'" href="#" @click.prevent>{{ record.type }}</a><span v-else>{{ record[column.dataIndex] }}</span></template></a-table>
                    <div class="list-pagination"><a-pagination :total="1" :page-size="20" :show-total="total => `총 ${total} 개 항목`" /></div>
                  </a-tab-pane>
                  <a-tab-pane key="comments" tab="코멘트">
                    <a-form layout="vertical"><a-form-item label="코멘트"><a-textarea v-model:value="comment" placeholder="코멘트 입력" :rows="3" /></a-form-item><a-button type="primary" :disabled="!comment.trim()" @click="addComment"><plus-outlined /> 코멘트 추가</a-button></a-form>
                    <a-list class="comment-list" :data-source="comments"><template #renderItem="{ item }"><a-list-item><a-list-item-meta :title="item.author" :description="item.text" /></a-list-item></template></a-list>
                  </a-tab-pane>
                </a-tabs>
              </a-card>
            </template>
          </ResourceLayout>
        </a-layout-content>
        <a-layout-footer class="mockup-tools" aria-label="목업 시연 도구">
          <span>설계 목업 · 예시 데이터</span>
          <a-space wrap><a-radio-group v-model:value="layout" size="small" @change="syncUrl"><a-radio-button value="vertical">개선: 세로 탭</a-radio-button><a-radio-button value="horizontal">현재: 가로 탭</a-radio-button></a-radio-group><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트" @change="setTheme" /><a-checkbox v-model:checked="eventsAllowed" @change="permissionsChanged">이벤트 탭 표시</a-checkbox></a-space>
        </a-layout-footer>
      </a-layout>
    </a-layout>
  </a-config-provider>
</template>
<script>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import koKR from 'ant-design-vue/es/locale/ko_KR'
import { message } from 'ant-design-vue'
import * as icons from '@ant-design/icons-vue'
import ResourceLayout from '@/layouts/ResourceLayout.vue'
import Status from '@/components/widgets/Status.vue'
export default {
  components: { ...icons, ResourceLayout, Status },
  setup () {
    const params = new URLSearchParams(location.search)
    const store = useStore()
    const device = computed(() => store.state.app.device)
    const dark = ref(params.get('theme') !== 'light')
    const layout = ref(params.get('layout') === 'horizontal' ? 'horizontal' : 'vertical')
    const eventsAllowed = ref(params.get('events') !== '0')
    const validTabs = () => eventsAllowed.value ? ['details', 'events', 'comments'] : ['details', 'comments']
    const activeTab = ref(validTabs().includes(params.get('tab')) ? params.get('tab') : 'details')
    const tabPosition = computed(() => layout.value === 'vertical' && device.value !== 'mobile' ? 'left' : 'top')
    const snapshot = { id: '302b2913-0000-4000-8000-000000000001', displayname: 'Windows2025-업데이트-전', name: 'i-2-13-VM_VS_20261003140930', type: 'DiskAndMemory', virtualmachinename: 'Windows2025-Example', created: '2026. 10. 3. 오후 11:09:30', parentName: 'Windows2025-설치-완료' }
    const fields = [{ label: '이름', value: snapshot.name }, { label: '아이디', value: snapshot.id }, { label: '표시 이름', value: snapshot.displayname }, { label: '유형', value: snapshot.type }, { label: '현재', value: 'true' }, { label: '상위', value: snapshot.parentName }, { label: 'VM ID', value: '36d5333d-0000-4000-8000-000000000002' }, { label: 'VM 이름', value: snapshot.virtualmachinename }, { label: '계정', value: 'admin' }, { label: '도메인', value: 'ROOT' }, { label: '생성일', value: snapshot.created }]
    const eventColumns = [{ title: '유형', dataIndex: 'type', key: 'type' }, { title: '상태', dataIndex: 'state' }, { title: '설명', dataIndex: 'description' }, { title: '생성일', dataIndex: 'created' }]
    const events = [{ id: 'example-event', type: 'VM.SNAPSHOT.CREATE', state: 'Completed', description: 'VM 스냅샷 생성', created: snapshot.created }]
    const comment = ref('')
    const comments = ref([{ author: 'admin', text: '업데이트 전 복원 지점입니다.' }])
    function syncUrl () { const q = new URLSearchParams(location.search); q.set('theme', dark.value ? 'dark' : 'light'); q.set('layout', layout.value); q.set('tab', activeTab.value); q.set('events', eventsAllowed.value ? '1' : '0'); history.replaceState({}, '', `${location.pathname}?${q}`) }
    function setTheme () { document.documentElement.classList.toggle('dark-mode', dark.value); document.body.classList.toggle('dark-mode', dark.value); syncUrl() }
    function changeTab (key) { activeTab.value = key; syncUrl() }
    function permissionsChanged () { if (!validTabs().includes(activeTab.value)) activeTab.value = 'details'; syncUrl() }
    function popstate () { const q = new URLSearchParams(location.search); activeTab.value = validTabs().includes(q.get('tab')) ? q.get('tab') : 'details' }
    function refresh () { message.success('예시 데이터로 새로고침했습니다.') }
    function showActionNotice () { message.info('이 목업은 탭 배치만 시연하며 실제 VM 작업을 실행하지 않습니다.') }
    function addComment () { comments.value.push({ author: 'admin', text: comment.value }); comment.value = '' }
    onMounted(() => { setTheme(); window.addEventListener('popstate', popstate) })
    onBeforeUnmount(() => window.removeEventListener('popstate', popstate))
    return { koKR, device, dark, layout, eventsAllowed, activeTab, tabPosition, snapshot, fields, eventColumns, events, comment, comments, syncUrl, setTheme, changeTab, permissionsChanged, refresh, showActionNotice, addComment }
  }
}
</script>
<style>
.mold-detail-preview { min-height: 100vh; font-size: 14px; }
.workspace { min-width: 0; }
.mold-sider { background: var(--ui-bg-surface) !important; border-right: 1px solid var(--ui-border); }
.brand { height: 64px; padding: 12px; display: flex; align-items: center; }
.brand img { width: 100%; max-height: 42px; object-fit: contain; }
.mold-sider .ant-menu { background: var(--ui-bg-surface); border-right: 0; }
.mold-sider .ant-menu-item { margin: 0; height: 40px; line-height: 40px; }
.mold-sider .ant-menu-item-selected { background: var(--ui-bg-selected) !important; }
.mold-sider .ant-menu-submenu-title { margin: 0; }
.mold-header { color: var(--ui-text-secondary); height: 64px; line-height: 64px; padding: 0 24px 0 20px; display: flex; align-items: center; gap: 16px; background: var(--ui-bg-surface) !important; border-bottom: 1px solid var(--ui-border); }
.project-select { width: 32%; min-width: 140px; }
.header-spacer { flex: 1; }
.mold-content { padding: 0 12px 12px; }
.breadcrumb-card { margin: 12px -12px 12px; border-radius: 0; }
.breadcrumb-card .ant-card-body { padding: 24px; }
.breadcrumb-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.snapshot-info-card .ant-card-body { padding: 30px; }
.snapshot-name { display: flex; align-items: center; gap: 22px; margin-bottom: 16px; }
.snapshot-name .anticon { font-size: 36px; }
.snapshot-name h4 { margin: 0; overflow-wrap: anywhere; }
.resource-detail-item { margin-top: 22px; }
.resource-detail-item strong { display: block; margin-bottom: 4px; }
.resource-detail-item > div { overflow-wrap: anywhere; }
.resource-detail-item .anticon { margin-right: 4px; }
.tag-placeholder { margin-top: 12px; color: var(--ui-text-muted); }
.resource-content-card .ant-card-body { padding: 24px; }
.resource-content-card .ant-tabs { margin-top: 16px; }
.resource-details-list .ant-list-item { padding: 12px 8px; border-color: var(--ui-border); }
.resource-details-list strong { color: var(--ui-text-primary); }
.detail-field { width: 100%; overflow-wrap: anywhere; }
.list-pagination { margin-top: 12px; text-align: right; }
.comment-list { margin-top: 24px; }
.mockup-tools { display: flex; justify-content: space-between; gap: 16px; padding: 16px 24px; flex-wrap: wrap; border-top: 1px solid var(--ui-border); font-size: 12px; }
@media(max-width:765px) { .mold-header { padding: 0 12px; gap: 12px; } .account-label,.mold-header > .anticon { display: none; } .mold-content { padding: 0 12px 12px; } .breadcrumb-card .ant-card-body { padding: 16px 12px; } .breadcrumb-card .ant-col { max-width: 100%; } .breadcrumb-card .ant-breadcrumb { overflow-wrap: anywhere; } .snapshot-info-card .ant-card-body { padding: 24px; } .resource-content-card .ant-card-body { padding: 20px; } .resource-content-card .ant-tabs { margin-top: 0; } .mockup-tools { padding: 16px 12px; } }
</style>
