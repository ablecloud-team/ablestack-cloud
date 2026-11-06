<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<!-- Design-only mockup; uses actual Mold layouts and Vue3/AntD components. No API calls. -->
<template>
<div class="design-app">
 <div class="design-note"><strong>B · #1457 개선 UI 목업</strong><span>설계 예시 · API 미연결 · 실제 정책 변경 없음</span><a-button size="small" @click="go('list')">목록</a-button><a-button size="small" @click="go('details')">상세</a-button><a-button size="small" @click="go('smb')">SMB</a-button><a-select v-model:value="scenario" size="small" style="width:160px"><a-select-option value="ready">허용 목록 변경</a-select-option><a-select-option value="last">마지막 규칙 삭제</a-select-option><a-select-option value="invalid">입력 오류</a-select-option><a-select-option value="unknown">조회 불가</a-select-option><a-select-option value="rollback">실패 원복</a-select-option></a-select><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트"/></div>
 <div class="app-shell"><aside class="side"><b>ABLESTACK</b><p>대시보드</p><p>컴퓨트　⌄</p><p>　가상머신</p><p>스토리지　⌄</p><div class="selected">공유 파일 시스템</div><p>네트워크</p><p>이미지</p></aside>
 <div class="workspace"><header class="topbar"><span>☰　 기본 보기</span><span>AC　admin cloud</span></header>
 <a-card class="breadcrumb-card"><a-row align="middle"><a-col :span="18"><Breadcrumb :resource="{name:'sharedfs-client-policy-design'}"><template #end><a-button size="small" shape="round"><ReloadOutlined/>업데이트</a-button></template></Breadcrumb></a-col><a-col :span="6" style="text-align:right"><a-button v-if="view!=='list'">작업 <DownOutlined/></a-button></a-col></a-row></a-card>
 <main>
 <template v-if="view==='list'"><div class="list-toolbar"><a-space><a-button type="primary"><PlusOutlined/>공유 파일 시스템 생성</a-button><a-select value="all" style="width:150px"><a-select-option value="all">전체</a-select-option></a-select></a-space><a-input-search placeholder="이름 검색" style="width:250px"/></div><a-table :columns="listColumns" :data-source="[{id:'design',name:'sharedfs-client-policy-design',state:'사용 가능',storage:'Primary Storage',size:'10 GiB',account:'admin',zone:'13-Zone'}]" row-key="id" size="small"><template #bodyCell="{column,record}"><a v-if="column.key==='name'" @click="go('smb')">{{record.name}}</a><a-badge v-else-if="column.key==='state'" status="success" :text="record.state"/></template></a-table></template>
 <ResourceLayout v-else :wide="wide"><template #left><a-card class="resource-info"><FileTextOutlined class="resource-icon"/><h3>sharedfs-client-policy-design</h3><a-badge status="success" text="사용 가능"/><dl><template v-for="(value,key) in details" :key="key"><dt>{{key}}</dt><dd>{{value}}</dd></template></dl></a-card></template>
 <template #right><a-card class="tab-card"><a-tabs v-model:activeKey="activeTab" tab-position="left">
 <a-tab-pane key="details" tab="상세"><div class="detail-grid"><template v-for="(value,key) in details" :key="key"><strong>{{key}}</strong><span>{{value}}</span></template></div></a-tab-pane>
 <a-tab-pane key="directory" tab="공유 디렉터리"/><a-tab-pane key="nfs" tab="NFS"/>
 <a-tab-pane key="smb" tab="SMB">
 <div class="toolbar"><a-button type="primary" @click="dialog='create';mode='ANY_SOURCE';confirmed=false;checked=false"><PlusOutlined/>SMB 공유 생성</a-button><a-button><PlusOutlined/>엔드포인트 추가</a-button><a-button><SafetyCertificateOutlined/>AD 상태 확인</a-button><a-button><ReloadOutlined/>업데이트</a-button><a-button @click="wide=!wide"><FullscreenOutlined/>{{wide?'원래 보기':'넓게 보기'}}</a-button></div>
 <a-alert type="info" show-icon message="클라이언트 원본과 계정·POSIX 권한은 각각 검사합니다." description="원본이 허용돼도 기존 로컬/AD 계정 ACL과 파일 권한을 통과해야 합니다. 기존 공유는 원본 제한 없음(ANY_SOURCE)을 유지합니다."/>
 <a-table class="section" :columns="smbColumns" :data-source="shares" row-key="name" size="small" :pagination="false" :scroll="{x:1020}"><template #bodyCell="{column,record}"><a v-if="column.key==='name'">{{record.name}}</a><template v-else-if="column.key==='policy'"><a-tag :color="record.name==='team-a'?'blue':undefined">{{record.policy}}</a-tag><div class="hint">{{record.name==='team-a'?'10.10.254.224/32 외 1개':'계정 인증·POSIX 검사 유지'}}</div></template><template v-else-if="column.key==='observed'"><a-badge status="success" text="445 · 1445 일치"/><div class="hint">설계 예시 · 새 연결부터 적용</div></template><a-space v-else-if="column.key==='actions'"><a-button size="small" @click="dialog='policy';checked=false"><SafetyCertificateOutlined/>원본 접근 정책</a-button></a-space></template></a-table>
 <div class="section-heading">SMB 계정 접근</div><a-table :columns="aclColumns" :data-source="[{id:1,type:'로컬 사용자',name:'local-user',permission:'읽기/쓰기'},{id:2,type:'AD 사용자',name:'ABLESTACK\\team-user',permission:'읽기/쓰기'}]" row-key="id" :pagination="false" size="small"/>
 <div class="section-heading">세션</div><p class="hint">원본 정책은 새 SMB 연결에 적용됩니다. 기존 세션은 자동 종료하지 않습니다. 즉시 철회가 필요하면 해당 세션을 확인해 명시적으로 종료하세요.</p>
 </a-tab-pane>
 <a-tab-pane key="iscsi" tab="iSCSI"/><a-tab-pane key="nvmeof" tab="NVMe-oF"/><a-tab-pane key="runtime" tab="런타임"/><a-tab-pane key="nics" tab="네트워크"/><a-tab-pane key="operations" tab="작업 내역"><a-table :columns="operationColumns" :data-source="[{id:'design-only',state:'원복 완료',phase:'적용',result:'최초 오류·원복 진단 보존 (예시)'}]" :pagination="false" size="small"/></a-tab-pane>
 </a-tabs></a-card></template></ResourceLayout>
 </main></div></div>
 <MoldDialog v-if="dialog" :visible="true" :title="dialog==='create'?'SMB 공유 생성':'SMB 원본 접근 정책'" size="lg" form-actions @cancel="dialog=''">
 <a-form layout="vertical">
  <a-alert v-if="dialog==='create'" type="info" show-icon message="기존 생성 폼에 추가하는 원본 정책 영역" description="기존 이름·별칭·볼륨·경로·쿼터·인증/권한 항목과 배치는 유지합니다. 아래는 추가 영역을 검토하기 위한 설계 예시입니다." class="section"/><a-row v-if="dialog==='create'" :gutter="16"><a-col :span="12"><a-form-item label="SMB 공유 이름"><a-input value="team-a"/></a-form-item></a-col><a-col :span="12"><a-form-item label="볼륨 내 상대 경로"><a-input value="projects/team-a"/></a-form-item></a-col></a-row>
  <a-descriptions bordered size="small" :column="2"><a-descriptions-item label="공유">team-a</a-descriptions-item><a-descriptions-item label="적용 엔드포인트">10.10.13.220 · 445 / 1445</a-descriptions-item><a-descriptions-item label="현재 저장 정책">{{dialog==='create'?'신규 · 미설정':'지정 원본 2개'}}</a-descriptions-item><a-descriptions-item label="현재 적용 관측">{{dialog==='create'?'생성 전 · 관측 없음':'445·1445 일치 (설계 예시)'}}</a-descriptions-item></a-descriptions>
  <a-form-item label="클라이언트 원본" class="section"><a-radio-group v-model:value="mode" @change="checked=false;confirmed=false"><a-radio value="RESTRICTED">지정 IP/CIDR만 허용</a-radio><a-radio value="ANY_SOURCE">제한 없음 (ANY_SOURCE)</a-radio></a-radio-group></a-form-item>
  <a-form-item v-if="mode==='RESTRICTED'" label="허용 IPv4 주소 또는 CIDR" :validate-status="error?'error':''" :help="error"><a-textarea v-model:value="input" :rows="3" placeholder="예: 10.10.254.224 또는 10.10.254.0/24 (한 줄에 하나)" @change="checked=false"/><div class="hint">최대32개 · 호스트 주소는 /32 · 네트워크 주소로 정규화 · 중복·hostname·wildcard·/0 거부</div></a-form-item>
  <a-alert type="info" show-icon message="서버가 실제로 관찰한 주소를 판정합니다." description="NAT 환경에서는 변환 이후 원본 IP를 입력하세요. 현재 제품 엔드포인트의 IPv6 원본 정책은 지원하지 않으며 입력 단계에서 거부합니다."/>
  <a-alert v-if="mode==='ANY_SOURCE' && dialog!=='create'" class="section" type="warning" show-icon message="마지막 규칙을 제거하면 모든 원본에서 접속을 시도할 수 있습니다." description="로컬/AD 인증과 공유 ACL·POSIX 권한 검사는 계속 유지됩니다. 이 전환은 별도 확인과 사전 점검이 필요합니다."/>
  <a-checkbox v-if="mode==='ANY_SOURCE' && dialog!=='create'" v-model:checked="confirmed" class="section">원본 제한 해제와 ANY_SOURCE 전환을 확인했습니다.</a-checkbox>
  <a-descriptions class="section" bordered size="small" :column="2"><a-descriptions-item label="변경 후 원본">{{mode==='ANY_SOURCE'?'제한 없음':normalized.join(', ')||'입력 필요'}}</a-descriptions-item><a-descriptions-item label="자동 포함">{{mode==='RESTRICTED'?'필요한 loopback 127.0.0.1/32':'없음'}}</a-descriptions-item><a-descriptions-item label="계정·파일 권한">기존 로컬/AD ACL·POSIX·고정 UID/GID 유지</a-descriptions-item><a-descriptions-item label="다른 공유·엔드포인트">정책·주소·포트 변경 없음</a-descriptions-item></a-descriptions>
  <a-alert class="section" type="warning" show-icon message="새 연결부터 적용 · 기존 세션 자동 종료 없음" description="허용 목록에서 원본을 제거해도 기존 연결의 열린 파일 접근은 유지될 수 있습니다. 세션 종료는 별도 명시적 작업입니다."/>
  <a-alert v-if="scenario==='unknown'" class="section" type="error" show-icon message="적용 상태를 조회할 수 없습니다." description="기존 정책을 유지합니다. 관측 불가를 적용 성공으로 표시하지 않으며 새 제출을 차단합니다."/>
  <a-alert v-if="scenario==='rollback'" class="section" type="warning" show-icon message="이전 정책 원복 완료 (설계 예시)" description="두 포트의 기존 정책을 복원했습니다. 최초 오류와 원복 결과는 작업 내역에서 확인할 수 있습니다."/>
  <a-descriptions v-if="checked" class="section" bordered size="small" :column="2"><a-descriptions-item label="점검 결과">준비 완료 (설계 예시)</a-descriptions-item><a-descriptions-item label="적용 방식">해당 공유 섹션 갱신 · 두 소유 리스너 reload</a-descriptions-item><a-descriptions-item label="영향 확인">새 연결에만 적용</a-descriptions-item><a-descriptions-item label="현재 revision">47 (예시)</a-descriptions-item></a-descriptions>
 </a-form>
 <MoldDialogActions><a-button @click="dialog=''">취소</a-button><a-button type="primary" :disabled="!!error || (mode==='ANY_SOURCE'&&!confirmed&&dialog!=='create') || scenario==='unknown'" @click="checked=true">{{checked?'변경 적용 (목업)':'사전 점검'}}</a-button></MoldDialogActions>
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
 data(){const last=q.get('scenario')==='last';return {view:q.get('view')||'smb',activeTab:q.get('view')||'smb',dialog:q.get('dialog')||'',dark:q.get('theme')!=='light',scenario:q.get('scenario')||'ready',mode:last||q.get('dialog')==='create'?'ANY_SOURCE':'RESTRICTED',input:q.get('scenario')==='invalid'?'*.example.local':'10.10.254.224',checked:false,confirmed:false,wide:false}},
 watch:{dark:{immediate:true,handler(v){document.body.classList.toggle('dark-mode',v)}},scenario(v){this.checked=false;this.confirmed=false;this.mode=v==='last'?'ANY_SOURCE':'RESTRICTED';this.input=v==='invalid'?'*.example.local':'10.10.254.224'}},
 computed:{
  details(){return {'이름':'sharedfs-client-policy-design','주 IP':'10.10.13.220 (예시)','볼륨':'10 GiB · SPARSE · XFS','계정':'admin','Zone':'13-Zone'}},
  shares(){return [{name:'team-a',policy:'지정 원본 2개',identity:'고정 7001:7002',path:'projects/team-a'},{name:'common',policy:'ANY_SOURCE',identity:'인증 사용자 유지',path:'export/common'}]},
  normalized(){return this.input.split(/[\n,]+/).map(x=>x.trim()).filter(Boolean).map(x=>{const [ip,raw]=x.split('/');const octets=ip.split('.');const prefix=raw===undefined?32:Number(raw);if(x.split('/').length>2||octets.length!==4||octets.some(o=>!(/^(0|[1-9][0-9]{0,2})$/).test(o)||Number(o)>255)||!Number.isInteger(prefix)||prefix<1||prefix>32)return x;const value=octets.reduce((v,o)=>((v<<8)|Number(o))>>>0,0);const mask=(0xffffffff<<(32-prefix))>>>0;const net=(value&mask)>>>0;return [24,16,8,0].map(n=>(net>>>n)&255).join('.')+'/'+prefix})},
  error(){if(this.mode==='ANY_SOURCE')return '';const a=this.normalized;if(a.length>32)return '허용 원본은 최대32개입니다.';if(!a.length)return '허용 원본을 입력하세요. 제한 해제는 ANY_SOURCE를 선택해야 합니다.';if(a.some(x=>!/^([0-9]{1,3}\.){3}[0-9]{1,3}\/(?:[1-9]|[12][0-9]|3[0-2])$/.test(x)))return 'IPv4 주소 또는 CIDR만 입력하세요. hostname·wildcard·IPv6·/0은 사용할 수 없습니다.';if(a.some(x=>x.split('/')[0].split('.').some(o=>Number(o)>255)))return 'IPv4 주소의 각 값은 0부터255여야 합니다.';if(new Set(a).size!==a.length)return '정규화 후 중복되는 원본은 제거하세요.';return ''},
  smbColumns(){return [{title:'SMB 공유 이름',dataIndex:'name',key:'name',width:130},{title:'클라이언트 원본',key:'policy',width:210},{title:'실제 적용 관측',key:'observed',width:190},{title:'파일 작업 identity',dataIndex:'identity',width:160},{title:'볼륨 내 상대 경로',dataIndex:'path',width:180},{title:'작업',key:'actions',width:170}]},
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
.section{margin-top:16px}.hint{color:var(--ui-text-secondary);font-size:12px;line-height:1.7;margin:8px 0 12px}.section-heading{font-weight:600;color:var(--ui-text-primary);margin:16px 0 12px}
.detail-grid{display:grid;grid-template-columns:170px 1fr}.detail-grid>*{padding:12px;border-bottom:1px solid var(--ui-border)}.detail-grid>strong{background:var(--ui-bg-secondary)}
code{overflow-wrap:anywhere}
@media(max-width:900px){.side{display:none}main{padding:16px}.design-note>span{display:none}}
</style>
