<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
<div class="design-app">
 <div class="design-note"><strong>B · #1456 개선 UI 목업</strong><span>설계 예시 · API 미연결 · 실제 서비스 변경 없음</span><a-button size="small" @click="go('list')">목록</a-button><a-button size="small" @click="go('details')">상세</a-button><a-button size="small" @click="go('smb')">SMB</a-button><a-select v-model:value="scenario" size="small" style="width:150px"><a-select-option value="ready">정상 점검 예시</a-select-option><a-select-option value="setgid">setgid 충돌</a-select-option><a-select-option value="collision">UID 충돌</a-select-option><a-select-option value="inherit">상속 충돌</a-select-option><a-select-option value="guest">게스트 충돌</a-select-option><a-select-option value="audit">감사 모듈 미준비</a-select-option></a-select><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트"/></div>
 <div class="app-shell"><aside class="side"><b>ABLESTACK</b><p>대시보드</p><p>컴퓨트　⌄</p><p>　가상머신</p><p>스토리지　⌄</p><div class="selected">공유 파일 시스템</div><p>네트워크</p><p>이미지</p></aside>
 <div class="workspace"><header class="topbar"><span>☰　 기본 보기</span><span>AC　admin cloud</span></header>
 <a-card class="breadcrumb-card"><a-row align="middle"><a-col :span="18"><Breadcrumb :resource="{name:'sharedfs-identity-design'}"><template #end><a-button size="small" shape="round"><ReloadOutlined/>업데이트</a-button></template></Breadcrumb></a-col><a-col :span="6" style="text-align:right"><a-button v-if="view!=='list'">작업 <DownOutlined/></a-button></a-col></a-row></a-card>
 <main>
 <template v-if="view==='list'"><div class="list-toolbar"><a-space><a-button type="primary"><PlusOutlined/>공유 파일 시스템 생성</a-button><a-select value="all" style="width:150px"><a-select-option value="all">전체</a-select-option></a-select></a-space><a-input-search placeholder="이름 검색" style="width:250px"/></div><a-table :columns="listColumns" :data-source="[{id:'design',name:'sharedfs-identity-design',state:'사용 가능',storage:'Primary Storage',size:'10 GiB',account:'admin',zone:'13-Zone'}]" row-key="id" size="small"><template #bodyCell="{column,record}"><a v-if="column.key==='name'" @click="go('smb')">{{record.name}}</a><a-badge v-else-if="column.key==='state'" status="success" :text="record.state"/></template></a-table></template>
 <ResourceLayout v-else :wide="wide"><template #left><a-card class="resource-info"><FileTextOutlined class="resource-icon"/><h3>sharedfs-identity-design</h3><a-badge status="success" text="사용 가능"/><dl><template v-for="(value,key) in details" :key="key"><dt>{{key}}</dt><dd>{{value}}</dd></template></dl></a-card></template>
 <template #right><a-card class="tab-card"><a-tabs v-model:activeKey="activeTab" tab-position="left">
 <a-tab-pane key="details" tab="상세"><div class="detail-grid"><template v-for="(value,key) in details" :key="key"><strong>{{key}}</strong><span>{{value}}</span></template></div></a-tab-pane>
 <a-tab-pane key="directory" tab="공유 디렉터리"><a-alert type="info" show-icon message="실제 POSIX 권한은 이 정책에서 명시적으로 관리합니다. 고정 UID/GID 설정이 기존 권한을 바꾸지 않습니다."/><a-descriptions class="section" bordered :column="1" size="small"><a-descriptions-item label="실제 경로">projects/team-a</a-descriptions-item><a-descriptions-item label="실제 UID:GID / mode">{{actualDirectory}}</a-descriptions-item><a-descriptions-item label="참조">NFS · SMB</a-descriptions-item></a-descriptions></a-tab-pane>
 <a-tab-pane key="nfs" tab="NFS"><a-alert type="info" show-icon message="SMB 고정 identity는 NFS 인증·squash·기존 파일 소유자를 변경하지 않습니다."/></a-tab-pane>
 <a-tab-pane key="smb" tab="SMB">
 <div class="toolbar"><a-button type="primary" @click="dialog='create';checked=false"><PlusOutlined/>SMB 공유 생성</a-button><a-button><PlusOutlined/>엔드포인트 추가</a-button><a-button><SafetyCertificateOutlined/>AD 상태 확인</a-button><a-button><ReloadOutlined/>업데이트</a-button><a-button @click="wide=!wide"><FullscreenOutlined/>{{wide?'원래 보기':'넓게 보기'}}</a-button></div>
 <a-alert type="info" show-icon message="인증·공유 ACL과 파일 작업 UID/GID를 분리합니다. 고정 사용자는 로그인할 수 없으며 기존 데이터 소유자는 그대로 유지됩니다."/>
 <a-table class="section" :columns="smbColumns" :data-source="[{name:'team-a',path:'projects/team-a'}]" row-key="name" size="small" :pagination="false" :scroll="{x:920}"><template #bodyCell="{column,record}"><a v-if="column.key==='name'">{{record.name}}</a><template v-else-if="column.key==='identity'"><div>고정 UID/GID <a-tag color="blue">7001:7002</a-tag></div><span class="hint">설정값 · 관측값과 구분</span></template><template v-else-if="column.key==='observed'"><div>7001:7002</div><span class="hint">445 · 1445 일치 (예시)</span></template><a-space v-else-if="column.key==='actions'"><a-button size="small" @click="dialog='policy';checked=false"><SafetyCertificateOutlined/>생성 권한 설정</a-button></a-space></template></a-table>
 <div class="section-heading">접근 허용 목록</div><a-table :columns="aclColumns" :data-source="[{id:1,type:'로컬 사용자',name:'local-reader',permission:'읽기/쓰기'},{id:2,type:'AD 사용자',name:'ABLESTACK\\team-user',permission:'ADMIN'}]" row-key="id" :pagination="false" size="small"/>
 <p class="hint">고정 모드에서도 ADMIN은 파일시스템 권한 검사를 통과해야 합니다. 감사에는 원래 인증 사용자와 고정 작업 identity를 함께 남깁니다.</p>
 </a-tab-pane>
 <a-tab-pane key="iscsi" tab="iSCSI"/><a-tab-pane key="nvmeof" tab="NVMe-oF"/><a-tab-pane key="runtime" tab="런타임"/><a-tab-pane key="nics" tab="네트워크"/><a-tab-pane key="operations" tab="작업 내역"><a-table :columns="operationColumns" :data-source="[{id:'design-only',state:'설계 예시',phase:'사전 점검',result:'실행하지 않음'}]" :pagination="false" size="small"/></a-tab-pane>
 </a-tabs></a-card></template></ResourceLayout>
 </main></div></div>
 <MoldDialog v-if="dialog" :visible="true" :title="dialog==='create'?'SMB 공유 생성':'생성 권한 설정'" size="lg" form-actions @cancel="dialog=''">
 <a-form layout="vertical">
  <a-descriptions bordered size="small" :column="2"><a-descriptions-item label="공유 이름">team-a</a-descriptions-item><a-descriptions-item label="기존 데이터">소유자·권한·ACL·내용 유지</a-descriptions-item><a-descriptions-item label="인증·접근">기존 로컬/AD 계정과 공유 ACL</a-descriptions-item><a-descriptions-item label="변경 방식">identity 변경 시 연결 확인·서비스 적용</a-descriptions-item></a-descriptions>
  <template v-if="dialog==='create'"><a-row :gutter="16" class="section"><a-col :span="12"><a-form-item label="SMB 공유 이름"><a-input value="team-a"/></a-form-item></a-col><a-col :span="12"><a-form-item label="볼륨 내 상대 경로"><a-input value="projects/team-a"/></a-form-item></a-col></a-row></template>
  <div class="section-heading">파일 작업 사용자</div>
  <a-form-item label="파일 작업 identity"><a-radio-group v-model:value="mode" @change="checked=false"><a-radio value="AUTHENTICATED">인증 사용자 유지 (기본)</a-radio><a-radio value="FIXED">고정 UID/GID</a-radio></a-radio-group></a-form-item>
  <p class="hint">공유에 접속하는 사용자와 실제 파일을 생성·수정하는 사용자를 분리합니다. 인증과 공유 ACL은 계속 검사합니다.</p>
  <a-row v-if="mode==='FIXED'" :gutter="16"><a-col :xs="24" :sm="12"><a-form-item label="고정 UID"><a-input-number v-model:value="uid" :min="1000" :max="65533" style="width:100%" aria-label="고정 UID" @change="checked=false"/><span class="hint">저장값 7001 · 기존 계정 UID는 사용할 수 없습니다.</span></a-form-item></a-col><a-col :xs="24" :sm="12"><a-form-item label="고정 GID"><a-input-number v-model:value="gid" :min="1000" :max="65533" style="width:100%" aria-label="고정 GID" @change="checked=false"/><span class="hint">저장값 7002 · 보호된 ID와 AD idmap 범위를 제외합니다.</span></a-form-item></a-col></a-row>
  <a-alert v-if="mode==='FIXED'" type="info" show-icon message="관리 identity: 비밀번호 로그인 불가 · 홈 생성 안 함 · Samba passdb 미가입" class="section"/>
  <a-descriptions class="section" bordered size="small" :column="2"><a-descriptions-item label="현재 실제 디렉터리">{{actualDirectory}}</a-descriptions-item><a-descriptions-item label="변경 후 파일 작업">{{mode==='FIXED'?uid+':'+gid:'원래 인증 사용자'}}</a-descriptions-item><a-descriptions-item label="기존 파일·디렉터리">자동 chown/chmod 없음</a-descriptions-item><a-descriptions-item label="감사">인증 사용자 + 작업 UID/GID</a-descriptions-item></a-descriptions>
  <a-alert v-if="blocked" class="section" type="error" show-icon :message="blockTitle" :description="blockText"/>
  <a-button v-if="scenario==='setgid'" class="section" @click="dialog='';go('directory')">공유 디렉터리 정책 확인</a-button>
  <div class="section-heading">파일·디렉터리 생성 권한</div>
  <a-row :gutter="16"><a-col v-for="(value,label) in masks" :key="label" :xs="24" :sm="12"><a-form-item :label="label"><a-input :value="value"/><span class="hint">현재 설정 {{value}}</span></a-form-item></a-col></a-row>
  <a-alert type="info" show-icon message="실제 mode는 클라이언트 요청과 부모 기본 ACL의 영향을 받습니다. UID/GID 설정은 POSIX 권한을 자동으로 넓히지 않습니다."/>
  <a-descriptions v-if="checked" class="section" bordered size="small" :column="2"><a-descriptions-item label="점검 결과">{{blocked?'차단':'준비 완료 (설계 예시)'}}</a-descriptions-item><a-descriptions-item label="공유 ACL">기존 사용자·그룹 유지</a-descriptions-item><a-descriptions-item label="identity 충돌">{{blocked?'해결 필요':'없음 (예시)'}}</a-descriptions-item><a-descriptions-item label="리소스 변경">예시 · 실행하지 않음</a-descriptions-item></a-descriptions>
 </a-form>
 <MoldDialogActions><a-button @click="dialog=''">취소</a-button><a-button type="primary" :disabled="blocked" @click="checked=true">{{checked?'변경 적용 (목업)':'사전 점검'}}</a-button></MoldDialogActions>
 </MoldDialog>
</div>
</template>
<script>
import ResourceLayout from '@/layouts/ResourceLayout.vue'
import Breadcrumb from '@/components/widgets/Breadcrumb.vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import MoldDialogActions from '@/components/view/MoldDialogActions.vue'
import {PlusOutlined,ReloadOutlined,FullscreenOutlined,FileTextOutlined,DownOutlined,SafetyCertificateOutlined} from '@ant-design/icons-vue'
const q=new URLSearchParams(location.search)
export default {
 components:{ResourceLayout,Breadcrumb,MoldDialog,MoldDialogActions,PlusOutlined,ReloadOutlined,FullscreenOutlined,FileTextOutlined,DownOutlined,SafetyCertificateOutlined},
 data(){return {view:q.get('view')||'smb',activeTab:q.get('view')||'smb',dialog:q.get('dialog')||'',dark:q.get('theme')!=='light',scenario:q.get('scenario')||'ready',mode:'FIXED',uid:7001,gid:7002,checked:false,wide:false}},
 watch:{dark:{immediate:true,handler(v){document.body.classList.toggle('dark-mode',v)}},scenario(){this.checked=false}},
 computed:{
  details(){return {'이름':'sharedfs-identity-design','주 IP':'10.10.13.220 (예시)','볼륨':'10 GiB · SPARSE · XFS','계정':'admin','Zone':'13-Zone'}},
  actualDirectory(){return this.scenario==='setgid'?'65534:65534 / 2775':'7001:7002 / 0770 (설계 예시)'},
  blocked(){return this.mode==='FIXED'&&this.scenario!=='ready'},
  blockTitle(){return {setgid:'상속 충돌: 부모 setgid가 켜져 있습니다.',collision:'UID 충돌: 선택한 UID는 기존 계정이 사용 중입니다.',inherit:'상속 충돌: 상위 소유자 상속과 함께 사용할 수 없습니다.',guest:'게스트 접근을 켠 상태에서는 고정 identity를 사용할 수 없습니다.',audit:'고정 identity 감사 모듈이 준비되지 않았습니다.'}[this.scenario]},
  blockText(){return {setgid:'부모 그룹이 생성 GID를 바꿀 수 있습니다. 공통 POSIX 정책을 확인한 뒤 명시적으로 변경하고 다시 점검하세요. 여기서 권한을 자동 변경하지 않습니다.',collision:'기존 계정을 변경하거나 같은 UID 계정을 추가하지 않습니다. 사용하지 않는 UID를 지정하세요.',inherit:'SMB 공유 편집에서 상위 소유자 상속을 명시적으로 해제한 후 다시 점검하세요.',guest:'공유 편집에서 게스트 접근을 해제하고, 허용할 로컬/AD 계정을 ACL에 등록하세요.',audit:'관리 런타임과 감사 모듈 준비 상태를 확인하세요. 준비 전에는 적용하지 않습니다.'}[this.scenario]},
  masks(){return {'파일 create mask':'0660','파일 force create mode':'0000','디렉터리 directory mask':'0770','디렉터리 force directory mode':'0000'}},
  smbColumns(){return [{title:'SMB 공유 이름',dataIndex:'name',key:'name',width:130},{title:'파일 작업 identity',key:'identity',width:190},{title:'실제 작업 UID/GID',key:'observed',width:170},{title:'볼륨 내 상대 경로',dataIndex:'path',width:180},{title:'작업',key:'actions',width:190}]},
  listColumns(){return [{title:'이름',key:'name',dataIndex:'name'},{title:'상태',key:'state',dataIndex:'state'},{title:'스토리지',dataIndex:'storage'},{title:'크기',dataIndex:'size'},{title:'계정',dataIndex:'account'},{title:'Zone',dataIndex:'zone'}]},
  aclColumns(){return [{title:'대상 유형',dataIndex:'type'},{title:'인증 사용자',dataIndex:'name'},{title:'공유 권한',dataIndex:'permission'}]},
  operationColumns(){return [{title:'상태',dataIndex:'state'},{title:'단계',dataIndex:'phase'},{title:'결과',dataIndex:'result'}]}
 },
 methods:{go(v){this.view=v;this.activeTab=v;this.dialog='';this.$router.push(v==='list'?'/sharedfs':'/sharedfs/design-example')}}
}
</script>
<style>
html,body,#app{margin:0;min-height:100%;font-family:Arial,"Malgun Gothic",sans-serif}
.design-app{min-height:100vh;color:var(--ui-text-primary);background:var(--ui-bg-page)}
.design-note{display:flex;align-items:center;gap:12px;padding:10px 16px;background:var(--ui-bg-elevated);border-bottom:1px solid var(--ui-border);font-size:12px}.design-note>span{flex:1;color:var(--ui-text-secondary)}
.app-shell{display:flex;min-height:calc(100vh - 48px)}.side{width:200px;flex-shrink:0;padding:20px 16px;background:var(--ui-bg-elevated);border-right:1px solid var(--ui-border)}.side>b{font-size:26px}.side>p{margin:28px 8px}.selected{padding:12px;color:var(--ui-link);background:var(--ui-bg-secondary);border-radius:4px}
.workspace{flex:1;min-width:0}.topbar{height:64px;display:flex;align-items:center;justify-content:space-between;padding:0 24px;background:var(--ui-bg-elevated);border-bottom:1px solid var(--ui-border)}
.breadcrumb-card{position:sticky;top:0;z-index:10;border-radius:0}.breadcrumb-card>.ant-card-body{padding:16px 24px}
main{padding:16px 24px}.list-toolbar{display:flex;justify-content:space-between;gap:16px;margin-bottom:16px}.resource-info h3{overflow-wrap:anywhere;color:var(--ui-text-primary);margin-top:16px}.resource-icon{font-size:36px;color:var(--ui-link)}
dl>dt{font-weight:600;margin:16px 0 4px}dl>dd{margin:0;color:var(--ui-text-secondary)}
.tab-card>.ant-card-body{padding:16px}.tab-card .ant-tabs-nav{min-width:104px}.tab-card .ant-tabs-content-holder{min-width:0}.toolbar{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:0 0 16px}
.section{margin-top:16px}.hint{color:var(--ui-text-secondary);font-size:12px;line-height:1.7;margin:8px 0 12px}.section-heading{font-weight:600;color:var(--ui-text-primary);margin:16px 0 12px}.options{display:flex;gap:16px;align-items:center}
.detail-grid{display:grid;grid-template-columns:170px 1fr}.detail-grid>*{padding:12px;border-bottom:1px solid var(--ui-border)}.detail-grid>strong{background:var(--ui-bg-secondary)}
code{overflow-wrap:anywhere}
@media(max-width:900px){.side{display:none}main{padding:16px}.design-note>span{display:none}}
</style>
