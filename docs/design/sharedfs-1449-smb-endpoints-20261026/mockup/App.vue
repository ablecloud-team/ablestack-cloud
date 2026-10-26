<template>
<div class="mock-root">
 <div class="design-banner"><strong>B. #1449 SMB 엔드포인트 개선 설계</strong><span>목업 · 예시 데이터 · 실제 API 미연결</span><a-select v-model:value="scenario" size="small" style="width:170px"><a-select-option value="normal">정상 목록</a-select-option><a-select-option value="failure">추가 실패 원복</a-select-option><a-select-option value="last">마지막 항목 보호</a-select-option></a-select><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트"/></div>
 <div class="shell"><aside class="nav"><b>ABLESTACK</b><p>◷ 대시보드</p><p>☁ 컴퓨트</p><p>▣ 스토리지</p><div class="selected">공유 파일 시스템</div><p>⚒ 도구</p></aside>
 <section class="workspace"><header>관리자 콘솔<span>AC admin cloud</span></header><main>
 <a-breadcrumb><a-breadcrumb-item>스토리지</a-breadcrumb-item><a-breadcrumb-item>공유 파일 시스템</a-breadcrumb-item><a-breadcrumb-item>sharedfs-smb-example</a-breadcrumb-item></a-breadcrumb>
 <ResourceLayout :wide="wide"><template #left><a-card class="info-card"><FileTextOutlined style="font-size:36px;color:#1890ff"/><h3>sharedfs-smb-example</h3><a-badge status="success" text="사용 가능 · 예시"/><dl><dt>주 IP</dt><dd>10.10.13.233</dd><dt>네트워크</dt><dd>L2-Network</dd><dt>디스크</dt><dd>ROOT / DATA · SPARSE</dd><dt>파일 시스템</dt><dd>XFS</dd><dt>인증 방식</dt><dd>로컬 계정</dd></dl></a-card></template>
 <template #right><a-card class="content-card"><a-tabs v-model:active-key="tab" tab-position="left" :animated="false">
 <a-tab-pane key="details" tab="상세"><a-descriptions bordered :column="1"><a-descriptions-item label="이름">sharedfs-smb-example</a-descriptions-item><a-descriptions-item label="상태">사용 가능 · 예시</a-descriptions-item></a-descriptions></a-tab-pane>
 <a-tab-pane key="nfs" tab="NFS"><a-empty description="목업 범위 밖"/></a-tab-pane>
 <a-tab-pane key="smb" tab="SMB">
 <div class="toolbar"><a-button type="primary" @click="open('add')"><PlusOutlined/>엔드포인트 추가</a-button><a-button @click="updated=true"><ReloadOutlined/>업데이트</a-button><a-button @click="wide=!wide"><FullscreenOutlined/>{{wide?'기본 보기':'넓게 보기'}}</a-button></div>
 <a-alert v-if="scenario==='failure'" type="error" show-icon message="새 엔드포인트를 활성화하지 못했습니다." description="10.10.13.238:445 수신 확인 실패 · 기존 엔드포인트와 공유 연결은 유지됐습니다. 작업 내역에서 진단을 확인하세요."/>
 <a-table class="endpoint-table" :columns="columns" :data-source="rows" row-key="id" :pagination="false" size="middle" :scroll="{x:610}">
  <template #bodyCell="{column,record}"><template v-if="column.key==='ip'"><span>{{record.ip}}</span><br><small class="muted">{{record.kind}}</small></template><template v-else-if="column.key==='status'"><a-badge status="success" text="정상"/><small class="muted">수신 확인 · 예시</small></template><template v-else-if="column.key==='shares'">2개</template><template v-else-if="column.key==='actions'"><a-dropdown :trigger="['click']"><a-button size="small">작업 ▾</a-button><template #overlay><a-menu @click="e=>open(e.key,record)"><a-menu-item key="edit">편집</a-menu-item><a-menu-item key="delete">삭제</a-menu-item></a-menu></template></a-dropdown></template></template>
 </a-table>
 <p class="observation">최근 관측: 예시 · 설정 IP/포트와 실제 수신을 구분해 표시합니다.<br>같은 IP·포트 요청은 기존 엔드포인트를 재사용합니다.</p>
 <a-divider orientation="left">SMB 공유</a-divider>
 <a-table :columns="shareColumns" :data-source="shares" row-key="name" size="small" :pagination="false"><template #bodyCell="{column}"><template v-if="column.key==='auth'">로컬 인증 · 접근 규칙 적용</template></template></a-table>
 <p class="muted">접속 주소와 포트를 선택해 기존 공유에 연결합니다. 비표준 포트는 지원되는 클라이언트에서 사용합니다.</p>
 </a-tab-pane>
 <a-tab-pane v-for="p in ['iSCSI','NVMe-oF','런타임','네트워크','메트릭','작업 내역','이벤트']" :key="p" :tab="p"><a-empty description="목업 범위 밖"/></a-tab-pane>
 </a-tabs></a-card></template></ResourceLayout>
 </main></section></div>
 <MoldDialog v-if="dialog" :visible="true" :title="title" size="md" form-actions @cancel="dialog=''">
  <template v-if="dialog==='delete-blocked'">
   <a-alert type="warning" show-icon message="마지막 SMB 엔드포인트는 삭제할 수 없습니다." description="이 엔드포인트를 사용하는 공유가 있습니다. 공유를 사용하는 접속 경로를 모두 제거하지 않도록 보호합니다."/>
   <a-descriptions bordered :column="1" size="small"><a-descriptions-item label="대상">{{selected.ip}}:{{selected.port}}</a-descriptions-item><a-descriptions-item label="남는 정상 엔드포인트">0개</a-descriptions-item><a-descriptions-item label="연결 공유">team-docs, project-data</a-descriptions-item></a-descriptions>
  </template>
  <template v-else-if="dialog==='delete'">
   <a-alert type="warning" show-icon message="선택한 엔드포인트의 접속이 끊어집니다." description="다른 SMB 엔드포인트와 NFS·iSCSI·NVMe-oF 설정은 유지합니다."/>
   <a-descriptions bordered :column="1" size="small"><a-descriptions-item label="삭제 대상">{{selected.ip}}:{{selected.port}}</a-descriptions-item><a-descriptions-item label="유지할 정상 엔드포인트">2개 · 예시</a-descriptions-item><a-descriptions-item label="주소 보존">다른 서비스가 사용하는 IP는 제거하지 않습니다.</a-descriptions-item></a-descriptions>
  </template>
  <template v-else>
   <a-alert type="info" show-icon :message="dialog==='edit'?'선택한 엔드포인트만 변경합니다.':'기존 엔드포인트에 새 접속 경로를 추가합니다.'" :description="dialog==='edit'?'변경될 접속 경로는 재접속이 필요합니다. 다른 엔드포인트의 연결과 공유는 유지합니다.':'현재 공유·인증·다른 프로토콜 설정을 유지합니다.'"/>
   <a-descriptions v-if="dialog==='edit'" bordered :column="1" size="small"><a-descriptions-item label="현재 주소">{{selected.ip}}:{{selected.port}}</a-descriptions-item></a-descriptions>
   <a-form layout="vertical">
    <a-form-item label="수신 IP 방식"><a-radio-group v-model:value="ipMode"><a-radio value="existing">기존 IP 사용</a-radio><a-radio value="new">신규 IP 추가</a-radio></a-radio-group></a-form-item>
    <a-form-item label="수신 IP" required><a-input v-if="ipMode==='new'" v-model:value="ip"/><a-select v-else v-model:value="ip"><a-select-option value="10.10.13.233">10.10.13.233 · 기본</a-select-option><a-select-option value="10.10.13.235">10.10.13.235 · 보조</a-select-option><a-select-option value="10.10.13.237">10.10.13.237 · 보조 · 예시</a-select-option></a-select></a-form-item>
    <a-form-item label="포트" required><a-input-number v-model:value="port" :min="1" :max="65535" style="width:100%"/></a-form-item>
   </a-form>
  </template>
  <template v-if="dialog!=='delete-blocked'">
   <a-divider orientation="left">변경 영향 · 사전 점검 예시</a-divider>
   <a-descriptions bordered :column="1" size="small"><a-descriptions-item label="추가 / 변경 대상">{{dialog==='delete'?selected.ip+':'+selected.port:ip+':'+port}}</a-descriptions-item><a-descriptions-item label="기존 SMB 접속">{{dialog==='edit'?'선택 항목 외 연결 유지':'기존 정상 연결 유지'}}</a-descriptions-item><a-descriptions-item label="공유·권한·인증">변경 없음</a-descriptions-item><a-descriptions-item label="다른 프로토콜">NFS / iSCSI / NVMe-oF 변경 없음</a-descriptions-item></a-descriptions>
   <div class="preflight"><a-button @click="checked=true">사전 점검</a-button><a-tag :color="checked?'green':'default'">{{checked?'점검 완료 · 예시':'점검 전'}}</a-tag></div>
   <a-alert v-if="checked" type="success" show-icon message="요청한 IP·포트와 기존 접속 경로를 확인했습니다." description="설계 목업의 점검 예시입니다. 실제 API를 호출하지 않습니다."/>
   <a-checkbox v-model:checked="ack">변경 영향을 확인했습니다.</a-checkbox>
  </template>
  <MoldDialogActions><a-button @click="dialog=''">취소</a-button><a-button type="primary" :disabled="dialog==='delete-blocked'||!checked||!ack" @click="dialog=''">확인</a-button></MoldDialogActions>
 </MoldDialog>
</div>
</template>
<script>
import ResourceLayout from '@/layouts/ResourceLayout.vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import MoldDialogActions from '@/components/view/MoldDialogActions.vue'
import {FileTextOutlined,PlusOutlined,ReloadOutlined,FullscreenOutlined} from '@ant-design/icons-vue'
const q=new URLSearchParams(location.search)
export default{
 components:{ResourceLayout,MoldDialog,MoldDialogActions,FileTextOutlined,PlusOutlined,ReloadOutlined,FullscreenOutlined},
 data(){return{dark:q.get('theme')!=='light',scenario:q.get('scenario')||(q.get('dialog')==='delete-blocked'?'last':'normal'),tab:'smb',dialog:q.get('dialog')||'',selected:{id:'example-02',ip:q.get('dialog')==='delete-blocked'?'10.10.13.233':'10.10.13.235',port:445},ip:q.get('dialog')==='edit'?'10.10.13.235':'10.10.13.237',port:445,ipMode:q.get('dialog')==='edit'?'existing':'new',checked:false,ack:false,wide:false,updated:false}},
 watch:{dark:{immediate:true,handler(v){document.body.classList.toggle('dark-mode',v)}}},
 computed:{
 title(){return this.dialog==='add'?'SMB 엔드포인트 추가':this.dialog==='edit'?'SMB 엔드포인트 편집':this.dialog==='delete-blocked'?'SMB 엔드포인트 삭제 보호':'SMB 엔드포인트 삭제'},
 rows(){const rows=[{id:'example-01',ip:'10.10.13.233',port:445,kind:'기본 IP'},{id:'example-02',ip:'10.10.13.235',port:445,kind:'보조 IP'},{id:'example-03',ip:'10.10.13.237',port:1445,kind:'보조 IP · 사용자 포트'}];return this.scenario==='last'?rows.slice(0,1):rows},
 columns(){return[{title:'수신 IP',key:'ip',width:160},{title:'포트',dataIndex:'port',width:70},{title:'실제 수신 상태',key:'status',width:160},{title:'공유 수',key:'shares',width:90},{title:'작업',key:'actions',width:100,fixed:'right'}]},
 shareColumns(){return[{title:'공유 이름',dataIndex:'name'},{title:'인증·접근',key:'auth'}]},shares(){return[{name:'team-docs'},{name:'project-data'}]}
 },
 methods:{open(kind,row){this.selected=row||this.selected;this.checked=false;this.ack=false;this.ipMode=kind==='edit'?'existing':'new';this.ip=kind==='edit'?this.selected.ip:'10.10.13.237';this.port=kind==='edit'?this.selected.port:445;this.dialog=kind==='delete'&&this.scenario==='last'?'delete-blocked':kind;}}
}
</script>
<style>
html,body,#app{margin:0;min-height:100%;font-family:Arial,"Malgun Gothic",sans-serif}.mock-root{min-height:100vh;color:var(--ui-text-primary);background:var(--ui-bg-page)}.design-banner{display:flex;align-items:center;gap:12px;padding:12px 18px;font-size:12px;border-bottom:1px solid var(--ui-border);background:var(--ui-bg-elevated)}.design-banner>span{flex:1}.shell{display:flex}.nav{width:170px;flex-shrink:0;background:var(--ui-bg-elevated);padding:24px 16px;border-right:1px solid var(--ui-border)}.nav b{font-size:24px}.nav p{margin:28px 8px}.selected{padding:12px 8px;color:#1890ff;border:1px solid var(--ui-border)}.workspace{flex:1;min-width:0}.workspace>header{height:54px;display:flex;align-items:center;justify-content:space-between;padding:0 24px;border-bottom:1px solid var(--ui-border)}main{padding:20px;min-width:0}.ant-breadcrumb{margin-bottom:20px}.mock-root .ant-breadcrumb-link,.mock-root .ant-breadcrumb-separator{color:var(--ui-text-secondary)}.info-card h3{color:var(--ui-text-primary);margin-top:16px;overflow-wrap:anywhere}.info-card dt{font-weight:600;margin:20px 0 6px}.info-card dd{margin:0}.content-card .ant-card-body{padding:20px}.content-card .ant-tabs-nav{min-width:90px}.content-card .ant-tabs-tabpane{padding-left:20px!important;min-width:0}.toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;position:static;min-height:32px;margin:0 0 16px!important}.muted,.observation{color:var(--ui-text-secondary);font-size:12px;line-height:1.8}.observation{margin:16px 0}.mock-root .ant-badge-status-text{color:var(--ui-text-primary)}.endpoint-table small{display:block;margin-top:4px}.mock-root .ant-alert,.mold-dialog .ant-alert,.mold-dialog .ant-descriptions{margin-bottom:16px}.preflight{display:flex;align-items:center;gap:12px;margin:16px 0}.mock-root .ant-table{margin-bottom:16px}.mock-root .ant-table-cell{vertical-align:middle}.mold-dialog .ant-checkbox-wrapper{margin-top:8px}
body.dark-mode .mock-root .ant-card,body.dark-mode .mock-root .ant-table,body.dark-mode .mock-root .ant-table-thead>tr>th,body.dark-mode .mock-root .ant-table-tbody>tr>td{background:var(--ui-bg-elevated);color:var(--ui-text-primary);border-color:var(--ui-border)}body.dark-mode .mold-dialog .ant-descriptions-item-label{background:var(--ui-bg-page);color:var(--ui-text-primary)}body.dark-mode .mold-dialog .ant-descriptions-item-content{background:var(--ui-bg-elevated);color:var(--ui-text-primary)}body.dark-mode .mock-root .ant-divider-inner-text,body.dark-mode .mold-dialog .ant-divider-inner-text{color:var(--ui-text-primary)}body.dark-mode .mock-root .ant-divider:before,body.dark-mode .mock-root .ant-divider:after,body.dark-mode .mold-dialog .ant-divider:before,body.dark-mode .mold-dialog .ant-divider:after{border-color:var(--ui-border)}
</style>
