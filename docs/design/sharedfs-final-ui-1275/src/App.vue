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
<a-config-provider :locale="koKR"><a-layout class="mold-detail-preview">
<a-layout-sider :width="220" class="mold-sider" v-if="device !== 'mobile'">
<div class="brand"><img :src="dark ? 'assets/logo-dark.png' : 'assets/logo-light.png'" alt="ABLESTACK" /></div>
<a-menu mode="inline" :selected-keys="['sharedfs']" :open-keys="['storage']">
<a-menu-item key="dashboard"><dashboard-outlined /> 대시보드</a-menu-item>
<a-menu-item key="compute"><cloud-outlined /> 컴퓨트</a-menu-item>
<a-sub-menu key="storage"><template #title><database-outlined /> 스토리지</template><a-menu-item key="volumes">볼륨</a-menu-item><a-menu-item key="snapshots">스냅샷</a-menu-item><a-menu-item key="sharedfs">공유 파일 시스템</a-menu-item></a-sub-menu>
<a-menu-item key="network"><wifi-outlined /> 네트워크</a-menu-item><a-menu-item key="images"><picture-outlined /> 이미지</a-menu-item><a-menu-item key="infra"><bank-outlined /> 인프라스트럭쳐</a-menu-item><a-menu-item key="tools"><tool-outlined /> 도구</a-menu-item>
</a-menu></a-layout-sider>
<a-layout class="workspace"><a-layout-header class="mold-header"><menu-fold-outlined /><a-select value="default" class="project-select"><a-select-option value="default"><project-outlined /> 기본 보기</a-select-option></a-select><span class="header-spacer" /><translation-outlined /><bell-outlined /><a-avatar size="small">AC</a-avatar><span class="account-label">admin cloud</span></a-layout-header>
<a-layout-content class="mold-content"><a-card class="breadcrumb-card"><a-row justify="space-between" align="middle"><a-col class="breadcrumb-left"><a-breadcrumb><a-breadcrumb-item><home-outlined /></a-breadcrumb-item><a-breadcrumb-item>공유 파일 시스템</a-breadcrumb-item><a-breadcrumb-item>nfs-test</a-breadcrumb-item></a-breadcrumb><a-button size="small" shape="round" @click="notice"><reload-outlined /> 업데이트</a-button></a-col><a-col><a-button type="primary" @click="openDialog('confirm')"><down-outlined /> 작업</a-button></a-col></a-row></a-card>
<ResourceLayout><template #left><a-card class="resource-info-card"><div class="resource-name"><file-text-outlined /><h3>nfs-test</h3></div><div class="resource-detail-item"><strong>상태</strong><Status text="Ready" :display-text="true" :show-tooltip="false" /></div><div v-for="field in summary" :key="field.label" class="resource-detail-item"><strong>{{ field.label }}</strong><div>{{ field.value }}</div></div><a-divider /><strong>태그</strong><div class="tag-placeholder"><a-tag>Storage Service</a-tag><a-tag>NFS</a-tag></div></a-card></template>
<template #right><a-card class="resource-content-card"><a-tabs v-model:active-key="activeTab" tab-position="left" :animated="false" @change="syncUrl">
<a-tab-pane v-for="tab in tabs" :key="tab.key" :tab="tab.label">
<template v-if="tab.key === 'details'"><a-list class="resource-details-list" size="small" :data-source="details"><template #renderItem="{item}"><a-list-item><div class="detail-field"><strong>{{ item.label }}</strong><br /><span>{{ item.value }}</span></div></a-list-item></template></a-list><section class="tab-section"><h4>스토리지 서비스 개요</h4><a-descriptions :column="1" size="small"><a-descriptions-item label="활성 서비스">NFS · SMB · iSCSI · NVMe-oF</a-descriptions-item><a-descriptions-item label="조회 상태"><a-tag color="green">조회 완료</a-tag></a-descriptions-item></a-descriptions></section></template>
<template v-else-if="tab.key === 'metrics'"><div class="tab-heading"><h3>메트릭</h3><a-space><a-select value="1h" style="width:110px"><a-select-option value="1h">최근 1시간</a-select-option><a-select-option value="24h">최근 24시간</a-select-option></a-select><a-button @click="notice"><reload-outlined /> 업데이트</a-button></a-space></div><a-row :gutter="16"><a-col :xs="24" :md="12" v-for="(metric,i) in metrics" :key="metric.name"><a-card class="metric-card" :title="metric.name" size="small"><div class="metric-value">{{ metric.value }}</div><svg viewBox="0 0 420 145" role="img" :aria-label="metric.name+' 예시 차트'"><path d="M0 120H420M0 75H420M0 30H420" stroke="var(--ui-border)" fill="none" /><polyline :points="i%2 ? '0,100 35,96 70,80 105,93 140,72 175,65 210,83 245,68 280,66 315,54 350,70 385,50 420,62' : '0,110 35,102 70,105 105,98 140,103 175,75 210,86 245,65 280,70 315,55 350,63 385,50 420,55'" fill="none" stroke="#1890ff" stroke-width="3" /></svg><div class="chart-ticks"><span>09:00</span><span>09:30</span><span>10:00</span></div></a-card></a-col></a-row></template>
<template v-else><div class="tab-heading"><h3>{{ tab.label }}</h3><a-space wrap><a-button v-if="isProtocol(tab.key)" @click="openDialog('protocol')"><poweroff-outlined /> 프로토콜 활성화</a-button><a-button type="primary" v-if="isProtocol(tab.key)" @click="openDialog(tab.key)"><plus-outlined /> {{ creationLabel(tab.key) }}</a-button><a-button @click="notice"><reload-outlined /> 업데이트</a-button></a-space></div>
<div class="list-toolbar" v-if="tab.key==='events'"><a-input-search placeholder="이벤트 검색" style="max-width:280px" /><a-select value="all" style="width:130px"><a-select-option value="all">모든 상태</a-select-option></a-select></div>
<template v-if="isProtocol(tab.key)"><section class="tab-section connection"><h4>접속 정보</h4><p>{{ connectionHelp(tab.key) }}</p><code>{{ connectionCommand(tab.key) }}</code></section><section class="tab-section"><h4>상태 요약</h4><a-descriptions :column="2" size="small"><a-descriptions-item label="엔드포인트">10.1.1.9:{{ ports[tab.key] }}</a-descriptions-item><a-descriptions-item label="모니터링 캐시"><a-tag color="green">정상</a-tag></a-descriptions-item><a-descriptions-item label="조회 상태">조회 완료</a-descriptions-item><a-descriptions-item label="마지막 갱신">2026-10-06 10:00</a-descriptions-item></a-descriptions></section></template>
<section class="tab-section" v-for="section in sections[tab.key]" :key="section.title"><div class="section-title"><div><h4>{{ section.title }}</h4><p>{{ section.description }}</p></div><a-button v-if="section.title.includes('접근') || section.title.includes('ACL')" size="small" @click="openDialog('acl')"><plus-outlined /> ACL 생성</a-button></div><a-table :columns="columns(section)" :data-source="rows(section)" size="middle" row-key="id" :pagination="false" :scroll="{x: section.columns.length > 7 ? 1450 : 800}"><template #bodyCell="{column,record}"><a-space v-if="column.key==='action'" wrap><template v-if="section.title==='백킹 볼륨'"><a-button size="small" type="link" @click="openDialog('capacity')">볼륨 확장</a-button><a-button size="small" type="link" disabled>연결 해제</a-button></template><template v-else><a-button size="small" type="link" @click="openDialog(tab.key)">편집</a-button><a-button size="small" type="link" danger @click="openDialog('confirm')">삭제</a-button></template></a-space><a-tag v-else-if="['사용 가능','활성','완료','연결됨'].includes(record[column.dataIndex])" color="green">{{ record[column.dataIndex] }}</a-tag><span v-else>{{record[column.dataIndex]}}</span></template></a-table><div class="list-pagination" v-if="tab.key==='events'"><a-pagination :total="2" :page-size="20" show-size-changer :show-total="total=>`총 ${total}개 항목`" /></div></section></template>
</a-tab-pane></a-tabs></a-card></template></ResourceLayout></a-layout-content>
<a-layout-footer class="mockup-tools"><span>최종 UI 설계 목업 · 예시 데이터 · 기능 완료 후 적용</span><a-space wrap><a-select v-model:value="dialogType" style="width:190px"><a-select-option v-for="option in dialogOptions" :key="option.key" :value="option.key">{{option.label}}</a-select-option></a-select><a-button @click="openDialog(dialogType)">대화상자 시연</a-button><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트" @change="setTheme" /></a-space></a-layout-footer>
</a-layout></a-layout>
<MoldDialog :visible="dialogVisible" :title="dialogTitle" :width="760" @cancel="closeDialog"><a-form layout="vertical" class="preserved-form">
<template v-if="dialogType==='confirm'"><a-alert type="warning" show-icon message="삭제 대상과 데이터 보존 정책을 확인하십시오." /><a-descriptions :column="1" class="mold-dialog-summary"><a-descriptions-item label="공유 이름">nfs-test-nfs</a-descriptions-item><a-descriptions-item label="데이터 볼륨">보존</a-descriptions-item></a-descriptions><a-form-item label="확인할 이름" required><a-input v-model:value="form.confirmation" placeholder="nfs-test-nfs" /></a-form-item></template>
<template v-else-if="dialogType==='capacity'"><a-descriptions :column="1" class="mold-dialog-summary"><a-descriptions-item label="백킹 볼륨">sharedfs-DATA-39</a-descriptions-item><a-descriptions-item label="현재 용량">100 GiB</a-descriptions-item></a-descriptions><a-form-item label="확장 후 용량 (GiB)" required><a-input-number v-model:value="form.size" :min="101" style="width:100%" /></a-form-item><a-alert show-icon type="info" message="기존 데이터와 공유 설정을 유지합니다." /></template>
<template v-else-if="dialogType==='acl'"><a-form-item label="NFS 내보내기" required><a-select value="nfs-test-nfs"><a-select-option value="nfs-test-nfs">nfs-test-nfs</a-select-option></a-select></a-form-item><a-form-item label="대상 주체 (IP/CIDR)" required><a-input value="10.1.1.0/24" /></a-form-item><h4>권한 및 squash 정책</h4><a-checkbox>읽기 전용</a-checkbox><a-checkbox :checked="true">Root Squash</a-checkbox><a-checkbox>All Squash</a-checkbox><h4 class="mold-dialog-section">익명 사용자 매핑</h4><a-row :gutter="12"><a-col :span="12"><a-form-item label="익명 UID"><a-input-number :value="65534" style="width:100%" /></a-form-item></a-col><a-col :span="12"><a-form-item label="익명 GID"><a-input-number :value="65534" style="width:100%" /></a-form-item></a-col></a-row></template>
<template v-else-if="['iscsi','nvmeof'].includes(dialogType)"><a-form-item label="이름" required><a-input :value="dialogType==='iscsi'?'block-store':'nvme-store'" /></a-form-item><a-form-item :label="dialogType==='iscsi'?'대상 IQN':'서브시스템 NQN'" required><a-input :value="dialogType==='iscsi'?'iqn.2026-10.example:storage.block-store':'nqn.2026-10.example:storage.nvme-store'" /></a-form-item><a-form-item label="백엔드"><a-select value="kernel"><a-select-option value="kernel">커널 대상</a-select-option></a-select></a-form-item><a-form-item label="인증 방식"><a-select value="auth"><a-select-option value="auth">{{dialogType==='iscsi'?'CHAP':'DH-HMAC-CHAP'}}</a-select-option></a-select></a-form-item><a-form-item label="수신 포트 그룹"><a-select :value="'port'"><a-select-option value="port">10.1.1.9:{{ports[dialogType]}}</a-select-option></a-select></a-form-item></template>
<template v-else-if="dialogType==='protocol'"><a-form-item label="프로토콜"><a-select value="nfs"><a-select-option value="nfs">NFS</a-select-option></a-select></a-form-item><a-form-item label="수신 IP 선택"><a-radio-group value="EXISTING"><a-radio value="EXISTING">기존 IP</a-radio><a-radio value="NEW">새 IP</a-radio></a-radio-group></a-form-item><a-form-item label="수신 IP"><a-select value="primary"><a-select-option value="primary">10.1.1.9</a-select-option></a-select></a-form-item><a-form-item label="포트"><a-input-number :value="2049" style="width:100%" /></a-form-item><a-form-item label="프로토콜 모드"><a-input value="NFSv4 전용" readonly /></a-form-item></template>
<template v-else><a-form-item :label="dialogType==='smb'?'공유 이름':'이름'" required><a-input v-model:value="form.name" /></a-form-item><a-form-item label="내부 백킹 경로" required><a-input v-model:value="form.path" /></a-form-item><h4>백킹 볼륨</h4><a-form-item label="볼륨 모드" required><a-radio-group v-model:value="form.volumeMode"><a-radio value="CURRENT">현재 볼륨</a-radio><a-radio value="EXISTING">기존 볼륨 선택</a-radio><a-radio value="NEW">새 볼륨</a-radio></a-radio-group></a-form-item><a-form-item label="현재 백킹 볼륨" required><a-select value="data39"><a-select-option value="data39">sharedfs-DATA-39 · 100 GiB · XFS</a-select-option></a-select></a-form-item><a-descriptions :column="1" size="small" class="volume-summary"><a-descriptions-item label="마운트 경로">/srv/ablestack-storage/volumes/data39</a-descriptions-item><a-descriptions-item label="연결된 내보내기">nfs-test-nfs</a-descriptions-item></a-descriptions><a-form-item><a-checkbox :checked="true">없으면 디렉터리 생성</a-checkbox></a-form-item>
<template v-if="dialogType==='smb'"><a-form-item label="교차 프로토콜 공유"><a-select value="none"><a-select-option value="none">기존 NFS 내보내기와 별도 경로</a-select-option><a-select-option value="nfs">nfs-test-nfs</a-select-option></a-select></a-form-item><a-form-item label="디렉터리 모드"><a-input value="0775" /></a-form-item><div class="option-grid"><a-checkbox>읽기 전용</a-checkbox><a-checkbox :checked="true">탐색 허용</a-checkbox><a-checkbox>게스트 접근</a-checkbox></div></template><template v-else><h4 class="mold-dialog-section">NFS 내보내기 엔드포인트</h4><a-form-item :label="dialogType==='smb'?'접속 포트':'프로토콜 모드'" required><a-select :value="dialogType==='smb'?'445':'V4_ONLY'"><a-select-option value="V4_ONLY">NFSv4 전용</a-select-option><a-select-option value="445">445</a-select-option></a-select></a-form-item><a-form-item label="수신 포트 그룹" required><a-select mode="multiple" :value="['primary']"><a-select-option value="primary">10.1.1.9:{{dialogType==='smb'?'445':'2049'}}</a-select-option></a-select></a-form-item>
<h4 class="mold-dialog-section">NFS 내보내기 옵션</h4><div class="option-grid"><a-checkbox>읽기 전용</a-checkbox><a-checkbox :checked="true">{{dialogType==='smb'?'탐색 허용':'Root Squash'}}</a-checkbox><a-checkbox>{{dialogType==='smb'?'게스트 접근':'All Squash'}}</a-checkbox><a-checkbox :checked="true">동기화</a-checkbox><a-checkbox>특권 포트 요구</a-checkbox></div>
<h4 class="mold-dialog-section">POSIX 권한</h4><a-row :gutter="12"><a-col :xs="24" :md="12" v-for="field in posixFields" :key="field.label"><a-form-item :label="field.label"><a-input-number v-if="/UID|GID/.test(field.label)" :value="Number(field.value)" :min="0" :max="65535" style="width:100%" /><a-input v-else :value="field.value" /></a-form-item></a-col><a-col :xs="24" :md="12"><a-form-item label="기존 하위 항목에 재귀 적용"><a-switch /></a-form-item></a-col></a-row>
</template></template></a-form><template #footer><a-button @click="closeDialog">취소</a-button><a-button type="primary" @click="confirmDialog">확인</a-button></template></MoldDialog>
</a-config-provider></template>
<script>
import { computed, ref, onMounted } from 'vue'
import { useStore } from 'vuex'
import { message } from 'ant-design-vue'
import koKR from 'ant-design-vue/es/locale/ko_KR'
import * as icons from '@ant-design/icons-vue'
import ResourceLayout from '@/layouts/ResourceLayout.vue'
import Status from '@/components/widgets/Status.vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import { sections } from './data'
export default { components:{...icons,ResourceLayout,Status,MoldDialog}, setup(){
const query=new URLSearchParams(location.search), store=useStore(),device=computed(()=>store.state.app.device)
const tabs=[{key:'details',label:'상세'},{key:'nfs',label:'NFS'},{key:'smb',label:'SMB'},{key:'iscsi',label:'iSCSI'},{key:'nvmeof',label:'NVMe-oF'},{key:'network',label:'네트워크'},{key:'metrics',label:'메트릭'},{key:'events',label:'이벤트'}]
const activeTab=ref(tabs.some(t=>t.key===query.get('tab'))?query.get('tab'):'details'),dark=ref(query.get('theme')!=='light'),dialogType=ref(query.get('dialog')||'nfs'),dialogVisible=ref(Boolean(query.get('dialog')))
const summary=[{label:'아이디',value:'487a3d3b-b583-4499-be02-c40b45a7e6b1'},{label:'디스크 크기',value:'100 GiB'},{label:'서비스 IP',value:'10.1.1.9'},{label:'VM 이름',value:'sharedfs-nfs-test'},{label:'볼륨',value:'sharedfs-DATA-39'},{label:'컴퓨트 오퍼링',value:'2C4GB'},{label:'Zone',value:'13-Zone'},{label:'계정 / 도메인',value:'admin / ROOT'}]
const details=[{label:'이름',value:'nfs-test'},{label:'아이디',value:summary[0].value},{label:'상태',value:'사용 가능'},{label:'파일 시스템',value:'XFS'},{label:'디스크 오퍼링',value:'SharedFS 데이터'},{label:'크기',value:'100 GiB'},{label:'프로비저닝 유형',value:'thin'},{label:'계정',value:'admin'},{label:'도메인',value:'ROOT'}]
const ports={nfs:2049,smb:445,iscsi:3260,nvmeof:4420},metrics=[{name:'CPU 사용량',value:'8.2%'},{name:'메모리 사용량',value:'1.4 / 4 GiB'},{name:'네트워크 처리량',value:'42 MiB/s'},{name:'스토리지 사용량',value:'1.5 / 100 GiB'}]
const dialogOptions=[{key:'nfs',label:'NFS 내보내기 생성'},{key:'smb',label:'SMB 공유 생성'},{key:'iscsi',label:'iSCSI 대상 생성'},{key:'nvmeof',label:'NVMe-oF 서브시스템 생성'},{key:'acl',label:'NFS ACL 생성'},{key:'capacity',label:'볼륨 용량 확장'},{key:'confirm',label:'삭제 확인'}]
const dialogTitle=computed(()=>dialogOptions.find(o=>o.key===dialogType.value)?.label||'프로토콜 설정'),form=ref({name:dialogType.value==='smb'?'team-share':'nfs-test-nfs',path:dialogType.value==='smb'?'/export/team-share':'/export/nfs-test-nfs',volumeMode:'CURRENT',size:200,confirmation:''}),posixFields=[{label:'소유자 UID',value:'65534'},{label:'소유자 GID',value:'65534'},{label:'익명 UID',value:'65534'},{label:'익명 GID',value:'65534'},{label:'디렉터리 모드',value:'0775'}]
function syncUrl(){const q=new URLSearchParams();q.set('tab',activeTab.value);q.set('theme',dark.value?'dark':'light');if(dialogVisible.value)q.set('dialog',dialogType.value);history.replaceState({},'',location.pathname+'?'+q)}
function setTheme(){document.documentElement.classList.toggle('dark-mode',dark.value);document.body.classList.toggle('dark-mode',dark.value);syncUrl()}
function openDialog(type){dialogType.value=type;if(['nfs','smb'].includes(type)){form.value.name=type==='smb'?'team-share':'nfs-test-nfs';form.value.path='/export/'+form.value.name}dialogVisible.value=true;syncUrl()}
function closeDialog(){dialogVisible.value=false;syncUrl()}
function confirmDialog(){message.success('목업의 입력 내용을 확인했습니다.');closeDialog()}
function notice(){message.info('예시 데이터 화면입니다.')}
function isProtocol(key){return ['nfs','smb','iscsi','nvmeof'].includes(key)}
function creationLabel(key){return ({nfs:'NFS 내보내기 생성',smb:'SMB 공유 생성',iscsi:'iSCSI 대상 생성',nvmeof:'서브시스템 생성'})[key]}
function connectionHelp(key){return ({nfs:'서비스 IP와 내보내기 이름을 함께 사용합니다.',smb:'서비스 IP와 SMB 공유 이름을 함께 사용합니다.',iscsi:'기존 대상 IQN과 포트로 접속합니다.',nvmeof:'기존 서브시스템 NQN과 TCP 엔드포인트를 사용합니다.'})[key]}
function connectionCommand(key){return ({nfs:'mount -t nfs4 -o vers=4.1,proto=tcp,port=2049 10.1.1.9:/nfs-test-nfs <로컬 경로>',smb:'\\\\10.1.1.9\\team-share',iscsi:'iscsiadm -m discovery -t sendtargets -p 10.1.1.9:3260',nvmeof:'nvme discover -t tcp -a 10.1.1.9 -s 4420'})[key]}
function columns(section){const base=section.columns.map((title,i)=>({title,dataIndex:'c'+i,key:'c'+i,width:Math.max(120,title.length*14)}));return ['이벤트 목록','세션','SMB 인증'].includes(section.title)?base:[...base,{title:'작업',key:'action',width:125,fixed:'right'}]}
function rows(section){return section.rows.map((row,i)=>Object.fromEntries([['id',section.title+'-'+i],...row.map((value,j)=>['c'+j,value])]))}
onMounted(setTheme)
return {koKR,device,tabs,activeTab,dark,summary,details,ports,metrics,sections,dialogType,dialogVisible,dialogOptions,dialogTitle,form,posixFields,syncUrl,setTheme,openDialog,closeDialog,confirmDialog,notice,isProtocol,creationLabel,connectionHelp,connectionCommand,columns,rows}
}}
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
.resource-info-card .ant-card-body { padding: 30px; }
.resource-name { display: flex; align-items: center; gap: 22px; margin-bottom: 16px; }
.resource-name .anticon { font-size: 36px; }
.resource-name h4 { margin: 0; overflow-wrap: anywhere; }
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
@media(max-width:765px) { .mold-header { padding: 0 12px; gap: 12px; } .account-label,.mold-header > .anticon { display: none; } .mold-content { padding: 0 12px 12px; } .breadcrumb-card .ant-card-body { padding: 16px 12px; } .breadcrumb-card .ant-col { max-width: 100%; } .breadcrumb-card .ant-breadcrumb { overflow-wrap: anywhere; } .resource-info-card .ant-card-body { padding: 24px; } .resource-content-card .ant-card-body { padding: 20px; } .resource-content-card .ant-tabs { margin-top: 0; } .mockup-tools { padding: 16px 12px; } }

.tab-heading,.section-title { display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap; }
.tab-heading h3,.tab-section h4 {margin:0;color:var(--ui-text-primary);}
.tab-section {margin-top:24px;} .section-title {margin-bottom:12px;} .section-title p,.connection p {margin:6px 0 0;color:var(--ui-text-muted);font-size:13px;}
.connection {padding:16px;background:var(--ui-bg-navigation);border:1px solid var(--ui-border);border-radius:4px;}
.connection code {display:block;overflow:auto;margin-top:12px;font-size:12px;white-space:nowrap;}
.resource-content-card .ant-tabs-nav {min-width:108px;} .resource-content-card .ant-tabs-tab {padding:12px 14px!important;}
.resource-content-card .ant-tabs-content-holder {min-width:0;} .resource-content-card .ant-tabs-tabpane {min-width:0;padding-left:20px!important;}
.resource-content-card .ant-table-cell {vertical-align:top;} .resource-info-card .ant-card-body {padding:24px;} .resource-name h3 {font-size:20px;}
.resource-detail-item {margin-top:18px;} .resource-detail-item div {color:var(--ui-text-secondary);font-size:13px;}
.metric-card {margin-bottom:16px;} .metric-value {font-size:22px;font-weight:600;margin-bottom:8px;} .chart-ticks {display:flex;justify-content:space-between;font-size:12px;color:var(--ui-text-muted);}
.list-toolbar {display:flex;gap:12px;margin-top:16px;} .preserved-form h4 {border-bottom:1px solid var(--ui-border);padding-bottom:10px;color:var(--ui-text-primary);}
.volume-summary {background:var(--ui-bg-navigation);padding:12px;margin-bottom:16px;} .option-grid {display:grid;grid-template-columns:1fr 1fr;gap:12px;}
.option-grid .ant-checkbox-wrapper {margin-left:0;} .mold-dialog .ant-form-item:last-child {margin-bottom:0;}
@media(max-width:765px){.resource-content-card .ant-card-body{padding:14px}.resource-content-card .ant-tabs-nav{min-width:84px}.resource-content-card .ant-tabs-tab{padding:10px 8px!important}.resource-content-card .ant-tabs-tabpane{padding-left:12px!important}.tab-heading h3{font-size:18px}.mockup-tools{position:static}.mold-dialog{max-width:calc(100vw - 16px)}}
</style>
