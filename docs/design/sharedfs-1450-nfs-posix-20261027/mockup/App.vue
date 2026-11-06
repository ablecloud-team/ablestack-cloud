<template>
<div class="design-app">
 <div class="design-note"><b>B · #1450 개선 UI 설계</b><span>설계 예시 · 실제 API 미연결</span><a-button size="small" @click="go('list')">목록</a-button><a-button size="small" @click="go('details')">상세</a-button><a-button size="small" @click="go('nfs')">NFS</a-button><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트"/></div>
 <div class="app-shell"><aside class="side"><b>ABLESTACK</b><p>대시보드</p><p>컴퓨트　⌄</p><p>　가상머신</p><p>스토리지　⌄</p><div class="nav-selected">공유 파일 시스템</div><p>네트워크</p><p>이미지</p><p>인프라스트럭쳐</p></aside>
 <div class="workspace"><header class="topbar"><span>☰　 기본 보기</span><span>AC　admin cloud</span></header>
 <a-card class="breadcrumb-card">
  <a-row align="middle"><a-col :span="17"><Breadcrumb :resource="{name:'sharedfs-nfs-example'}"><template #end><a-button size="small" shape="round"><ReloadOutlined/>업데이트</a-button></template></Breadcrumb></a-col><a-col :span="7" class="breadcrumb-actions"><a-button v-if="view==='list'" type="primary" @click="go('nfs')"><PlusOutlined/>공유 파일 시스템 생성</a-button><a-button v-else type="primary">작업 <DownOutlined/></a-button></a-col></a-row>
 </a-card>
 <main>
 <template v-if="view==='list'">
  <div class="list-toolbar"><a-select value="all" style="width:160px"><a-select-option value="all">전체</a-select-option></a-select><a-input-search placeholder="이름 검색" style="width:280px"/></div>
  <a-table class="resource-list" :columns="serviceColumns" :data-source="services" row-key="id" size="small" :pagination="{pageSize:20,showSizeChanger:true,showTotal:()=> '전체 1 개 항목 중 1–1 표시'}">
   <template #bodyCell="{column,record}"><template v-if="column.key==='name'"><a @click="go('nfs')">{{record.name}}</a></template><template v-else-if="column.key==='state'"><a-badge status="success" text="사용 가능"/></template></template>
  </a-table>
 </template>
 <ResourceLayout v-else :wide="wide">
  <template #left><a-card class="resource-info"><div class="resource-icon"><FileTextOutlined/></div><h3>sharedfs-nfs-example</h3><a-badge status="success" text="사용 가능 · 설계 예시"/><a-divider/><dl><dt>주 IP</dt><dd>10.10.13.250</dd><dt>컴퓨트 오퍼링</dt><dd>2 CPU / 4 GiB</dd><dt>네트워크</dt><dd>L2-Network</dd><dt>볼륨</dt><dd>SPARSE · XFS · 20 GiB</dd></dl></a-card></template>
  <template #right><a-card class="tab-card"><a-tabs v-model:active-key="tab" tab-position="left" :animated="false">
   <a-tab-pane key="details" tab="상세"><a-descriptions :column="1" bordered size="small"><a-descriptions-item label="이름">sharedfs-nfs-example</a-descriptions-item><a-descriptions-item label="상태"><a-badge status="success" text="사용 가능"/></a-descriptions-item><a-descriptions-item label="프로토콜">NFSv4 전용</a-descriptions-item><a-descriptions-item label="주 IP">10.10.13.250</a-descriptions-item><a-descriptions-item label="데이터 볼륨">SPARSE · XFS · 20 GiB</a-descriptions-item></a-descriptions></a-tab-pane>
   <a-tab-pane key="nfs" tab="NFS">
    <div class="toolbar"><a-button type="primary" @click="open('create')"><PlusOutlined/>NFS 내보내기 생성</a-button><a-button>프로토콜 활성화</a-button><a-button><ReloadOutlined/>업데이트</a-button><a-button @click="wide=!wide"><FullscreenOutlined/>{{wide?'기본 보기':'넓게 보기'}}</a-button></div>
    <a-alert type="info" show-icon message="접근 정책과 디렉터리 권한은 함께 확인합니다." description="RW 허용만으로 모든 사용자가 쓸 수 있는 것은 아닙니다. Squash로 결정된 UID/GID와 실제 POSIX 권한이 모두 맞아야 합니다."/>
    <a-table :columns="exportColumns" :data-source="exports" row-key="id" :pagination="false" size="small" :scroll="{x:740}">
     <template #bodyCell="{column,record}"><template v-if="column.key==='name'"><b>{{record.name}}</b><div class="secondary">10.10.13.250:/{{record.name}}</div></template><template v-else-if="column.key==='posix'"><code>1234:2345 / 0750</code><div class="secondary">관측 완료 · 예시</div></template><template v-else-if="column.key==='actions'"><a-button size="small" @click="open('edit')"><EditOutlined/>편집</a-button></template></template>
    </a-table>
    <div class="section-heading">접근 허용 목록</div><div class="toolbar"><a-button type="primary" @click="open('acl')"><PlusOutlined/>NFS ACL 생성</a-button></div>
    <a-table :columns="aclColumns" :data-source="acls" row-key="id" :pagination="false" size="small"><template #bodyCell="{column}"><template v-if="column.key==='actions'"><a-button size="small" @click="open('acl')"><EditOutlined/>편집</a-button></template></template></a-table>
    <p class="secondary">권장값은 설정값을 바꾸지 않습니다. 현재 실제 권한은 별도 관측으로 표시하며 조회할 수 없으면 ‘조회되지 않음’으로 표시합니다.</p>
   </a-tab-pane>
   <a-tab-pane v-for="t in ['SMB','iSCSI','NVMe-oF','런타임','네트워크','메트릭','작업 내역','이벤트']" :key="t" :tab="t"><a-empty description="이 설계의 변경 범위 밖"/></a-tab-pane>
  </a-tabs></a-card></template>
 </ResourceLayout>
 </main></div></div>
 <MoldDialog v-if="dialog" :visible="true" :title="dialog==='acl'?'NFS 접근 규칙 편집':dialog==='create'?'NFS 내보내기 생성':'NFS 내보내기 편집'" size="lg" form-actions @cancel="dialog=''">
 <a-form layout="vertical">
  <a-row :gutter="16"><a-col :span="12"><a-form-item :label="dialog==='acl'?'내보내기 이름':'이름'"><a-input value="team-nfs" :disabled="dialog==='acl'"/></a-form-item></a-col><a-col :span="12"><a-form-item :label="dialog==='acl'?'허용할 클라이언트':'내부 백킹 경로'"><a-input :value="dialog==='acl'?'10.10.0.0/16':'/export/team-nfs'"/></a-form-item></a-col></a-row>
  <div v-if="dialog!=='acl'" class="fixed-context"><b>백킹 볼륨</b><span>현재 백킹 볼륨 · sharedfs-DATA-127 · SPARSE / XFS / 20 GiB</span><span>용량 제한 없음 · 수신 포트 그룹 2049 · 기존 볼륨/신규 볼륨 선택은 유지</span></div>
  <div class="section-heading">{{dialog==='acl'?'접근 옵션':'NFS 내보내기 옵션'}}</div>
  <a-row :gutter="16"><a-col :span="12"><a-form-item label="접근 권한"><a-radio-group v-model:value="readonly" @change="invalidate"><a-radio :value="false">읽기/쓰기 (RW)</a-radio><a-radio :value="true">읽기 전용 (RO)</a-radio></a-radio-group></a-form-item></a-col><a-col :span="12"><a-form-item label="사용자 매핑"><a-select v-model:value="policy" @change="invalidate"><a-select-option v-if="dialog==='acl'" value="INHERIT">내보내기 정책 상속</a-select-option><a-select-option value="ROOT">Root Squash</a-select-option><a-select-option value="NONE">No Root Squash</a-select-option><a-select-option value="ALL">All Squash</a-select-option></a-select></a-form-item></a-col></a-row>
  <p class="policy-help">{{policyHelp}}</p>
  <a-row v-if="policy!=='NONE'" :gutter="16"><a-col :span="12"><a-form-item label="익명 UID"><a-input-number v-model:value="anonuid" :min="0" :max="65535" @change="invalidate"/></a-form-item></a-col><a-col :span="12"><a-form-item label="익명 GID"><a-input-number v-model:value="anongid" :min="0" :max="65535" @change="invalidate"/></a-form-item></a-col></a-row>
  <a-alert v-if="anonMismatch" type="warning" show-icon message="익명 사용자의 쓰기 권한을 확인하세요." :description="'현재 1234:2345 / 0750에서 익명 '+anonuid+':'+anongid+'의 쓰기가 허용되지 않습니다. 접근 규칙이나 권한을 확인하되 자동으로 소유권을 바꾸지 않습니다.'"/><div class="section-heading">현재 실제 POSIX 권한</div>
  <a-descriptions bordered size="small" :column="3"><a-descriptions-item label="소유자 UID">1234</a-descriptions-item><a-descriptions-item label="소유자 GID">2345</a-descriptions-item><a-descriptions-item label="디렉터리 권한">0750</a-descriptions-item></a-descriptions>
  <p class="secondary">관측 완료 · 설계 예시 / 저장 설정 1234:2345 / 0750 · 하위 파일의 권한은 별도입니다.</p>
  <a-alert v-if="dialog==='acl'" type="info" show-icon message="접근 규칙만 변경합니다." description="익명 UID/GID와 RW/RO는 NFS 접근 정책입니다. 이 대화상자에서 디렉터리 소유권·mode·하위 파일을 변경하지 않습니다."/>
  <template v-else>
   <div class="section-heading">현재 정책의 권장값 <a-tag>참고값</a-tag></div>
   <div class="recommendation"><code>{{recommended.owneruid}}:{{recommended.ownergid}} / {{recommended.mode}}</code><a-button @click="useRecommended">권장값 사용</a-button><p>{{readonly?'RO는 NFS에서 쓰기를 거부합니다. 기존 디렉터리 권한을 자동 변경하지 않습니다.':'신규 디렉터리 기준의 권장값입니다. 기존 디렉터리에 사용하려면 변경 영향을 확인해야 합니다.'}}</p></div>
   <a-form-item label="디렉터리 권한 변경"><a-switch v-model:checked="editPermissions" @change="invalidate"/><span class="switch-label">{{editPermissions?'입력한 변경만 적용':'현재 권한 유지'}}</span></a-form-item>
   <a-row :gutter="16"><a-col :span="8"><a-form-item label="변경할 소유자 UID"><a-input-number v-model:value="draft.owneruid" :disabled="!editPermissions" :min="0" :max="65535" @change="invalidate"/></a-form-item></a-col><a-col :span="8"><a-form-item label="변경할 소유자 GID"><a-input-number v-model:value="draft.ownergid" :disabled="!editPermissions" :min="0" :max="65535" @change="invalidate"/></a-form-item></a-col><a-col :span="8"><a-form-item label="변경할 디렉터리 권한"><a-input v-model:value="draft.mode" :disabled="!editPermissions" @change="invalidate"/></a-form-item></a-col></a-row>
   <a-checkbox v-model:checked="draft.recursivepermission" :disabled="!editPermissions" @change="invalidate">하위 항목까지 변경 (기본 꺼짐)</a-checkbox>
  </template>
  <div class="section-heading">변경 영향 · {{ready?'사전 점검 예시 완료':'점검 전'}}</div>
  <a-descriptions bordered size="small" :column="1"><a-descriptions-item label="접근 정책">{{readonly?'RO':'RW'}} / {{policyName}}</a-descriptions-item><a-descriptions-item label="디렉터리 권한">{{hasChanges?'1234:2345 / 0750 → '+draft.owneruid+':'+draft.ownergid+' / '+draft.mode:'변경 없음 · 현재 권한 유지'}}</a-descriptions-item><a-descriptions-item label="적용 범위">{{hasChanges?(draft.recursivepermission?'하위 항목 포함 · 별도 명시 확인 필요':'선택한 내보내기 디렉터리만'):'접근 정책만 · 하위 파일 변경 없음'}}</a-descriptions-item></a-descriptions>
  <a-alert v-if="ready" type="info" show-icon message="목업의 사전 점검 예시입니다. 서버에 적용하지 않습니다." :description="hasChanges?'적용 직전 실제 권한·경로를 다시 확인합니다. 관측이 달라졌으면 다시 점검합니다.':'변경하지 않은 POSIX 항목은 요청에 포함하지 않습니다.'"/>
  <a-checkbox v-if="hasChanges" v-model:checked="ack">현재 → 변경 후 값과 적용 범위를 확인했습니다.</a-checkbox>
 </a-form>
 <MoldDialogActions><a-button @click="dialog=''">취소</a-button><a-button type="primary" :disabled="ready&&hasChanges&&!ack" @click="ready?dialog='':ready=true">{{ready?'확인':'사전 점검'}}</a-button></MoldDialogActions>
 </MoldDialog>
</div>
</template>
<script>
import ResourceLayout from '@/layouts/ResourceLayout.vue'
import Breadcrumb from '@/components/widgets/Breadcrumb.vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import MoldDialogActions from '@/components/view/MoldDialogActions.vue'
import {PlusOutlined,ReloadOutlined,FullscreenOutlined,EditOutlined,FileTextOutlined,DownOutlined} from '@ant-design/icons-vue'
import {recommendation,permissionDelta} from './policy.mjs'
const q=new URLSearchParams(location.search)
export default {
 components:{ResourceLayout,Breadcrumb,MoldDialog,MoldDialogActions,PlusOutlined,ReloadOutlined,FullscreenOutlined,EditOutlined,FileTextOutlined,DownOutlined},
 data(){return {view:q.get('view')||'nfs',tab:q.get('view')==='details'?'details':'nfs',dark:q.get('theme')!=='light',wide:false,dialog:q.get('dialog')||'',policy:q.get('policy')||'ROOT',readonly:q.get('access')==='ro',anonuid:65534,anongid:65534,editPermissions:q.get('scenario')==='change',draft:{owneruid:q.get('scenario')==='change'?0:1234,ownergid:q.get('scenario')==='change'?0:2345,mode:q.get('scenario')==='change'?'0755':'0750',recursivepermission:false},ready:q.get('scenario')==='change',ack:false}},
 watch:{dark:{immediate:true,handler(v){document.body.classList.toggle('dark-mode',v)}}},
 computed:{
  anonMismatch(){return !this.readonly && this.policy!=='NONE' && !(Number(this.anonuid)===0 || Number(this.anonuid)===1234)},
  policyName(){return {INHERIT:'내보내기 정책 상속',ROOT:'Root Squash',NONE:'No Root Squash',ALL:'All Squash'}[this.policy]},
  policyHelp(){return {INHERIT:'현재 내보내기의 Root Squash와 익명 UID/GID를 상속합니다. 변경하지 않은 매핑은 별도 제출하지 않습니다.',ROOT:'클라이언트 root를 익명 UID/GID로 매핑합니다. 그 외 사용자는 숫자 UID/GID를 유지하므로 실제 디렉터리 권한도 맞아야 합니다.',NONE:'클라이언트 UID/GID를 유지합니다. root는 0:0입니다. 신규 디렉터리는 0:0 / 0755를 권장하며 기존 권한은 자동 변경하지 않습니다.',ALL:'모든 클라이언트 사용자를 익명 UID/GID로 매핑합니다. RW 사용 시 익명 identity와 디렉터리 권한을 함께 확인합니다.'}[this.policy]},
  recommended(){return recommendation(this.policy,this.readonly,this.anonuid,this.anongid)},
  delta(){return permissionDelta({owneruid:1234,ownergid:2345,mode:'0750'},this.draft,this.editPermissions)},
  hasChanges(){return this.dialog!=='acl'&&Object.keys(this.delta).length>0},
  services(){return [{id:'example',name:'sharedfs-nfs-example',state:'Ready',protocol:'NFSv4',ip:'10.10.13.250',volume:'20 GiB / SPARSE',zone:'13-Zone'}]},
  serviceColumns(){return [{title:'이름',dataIndex:'name',key:'name'},{title:'상태',dataIndex:'state',key:'state'},{title:'프로토콜',dataIndex:'protocol'},{title:'주 IP',dataIndex:'ip'},{title:'백킹 볼륨',dataIndex:'volume'},{title:'Zone',dataIndex:'zone'}]},
  exports(){return [{id:'example',name:'team-nfs',access:'읽기/쓰기',policy:'Root Squash'}]},
  exportColumns(){return [{title:'내보내기 이름',key:'name',width:210},{title:'권한',dataIndex:'access',width:95},{title:'사용자 매핑',dataIndex:'policy',width:125},{title:'실제 POSIX 권한',key:'posix',width:210},{title:'작업',key:'actions',width:100}]},
  acls(){return [{id:'acl',name:'team-nfs',principal:'10.10.0.0/16',access:'읽기/쓰기',mapping:'내보내기 정책 상속'}]},
  aclColumns(){return [{title:'내보내기',dataIndex:'name'},{title:'허용 클라이언트',dataIndex:'principal'},{title:'권한',dataIndex:'access'},{title:'사용자 매핑',dataIndex:'mapping'},{title:'작업',key:'actions'}]}
 },
 methods:{go(view){location.href='?view='+view+'&theme='+(this.dark?'dark':'light')},open(kind){this.dialog=kind;this.policy=kind==='acl'?'INHERIT':'ROOT';this.ready=false;this.ack=false;this.editPermissions=false;this.draft={owneruid:1234,ownergid:2345,mode:'0750',recursivepermission:false}},invalidate(){this.ready=false;this.ack=false},useRecommended(){this.editPermissions=true;this.draft={...this.recommended};this.invalidate()}}
}
</script>
<style>
html,body,#app{margin:0;min-height:100%;font-family:Arial,"Malgun Gothic",sans-serif}
.design-app{min-height:100vh;color:var(--ui-text-primary);background:var(--ui-bg-page)}
.design-note{display:flex;align-items:center;gap:12px;padding:10px 16px;background:var(--ui-bg-elevated);border-bottom:1px solid var(--ui-border);font-size:12px}
.design-note>span{flex:1;color:var(--ui-text-secondary)}.app-shell{display:flex;min-height:calc(100vh - 48px)}
.side{width:200px;flex-shrink:0;padding:20px 16px;background:var(--ui-bg-elevated);border-right:1px solid var(--ui-border)}
.side>b{font-size:26px}.side>p{margin:28px 8px}.nav-selected{padding:12px;color:var(--ui-primary);background:var(--ui-bg-secondary);border-radius:4px}
.workspace{flex:1;min-width:0}.topbar{height:64px;display:flex;align-items:center;justify-content:space-between;padding:0 24px;background:var(--ui-bg-elevated);border-bottom:1px solid var(--ui-border)}
.breadcrumb-card{position:sticky;top:0;z-index:10;border-radius:0}.breadcrumb-card>.ant-card-body{padding:16px 24px}.breadcrumb-actions{text-align:right}
main{padding:16px 24px}.list-toolbar{display:flex;justify-content:space-between;gap:16px;margin:0 0 16px}.resource-list{background:var(--ui-bg-elevated)}
.resource-info h3{overflow-wrap:anywhere;color:var(--ui-text-primary);margin-top:16px}.resource-icon{font-size:36px;color:var(--ui-primary)}
dl>dt{font-weight:600;margin:16px 0 4px}dl>dd{margin:0;color:var(--ui-text-secondary)}
.tab-card>.ant-card-body{padding:16px}.tab-card .ant-tabs-nav{min-width:96px}.tab-card .ant-tabs-content-holder{min-width:0}
.toolbar{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:0 0 16px}
.secondary{color:var(--ui-text-secondary);font-size:12px;margin:8px 0;line-height:1.6}
.section-heading{font-weight:600;font-size:14px;color:var(--ui-text-primary);margin:16px 0 12px;display:flex;align-items:center;gap:8px}
.fixed-context{display:grid;gap:8px;background:var(--ui-bg-secondary);border:1px solid var(--ui-border);border-radius:6px;padding:16px;margin-bottom:16px}
.fixed-context>span{color:var(--ui-text-secondary)}.policy-help{color:var(--ui-text-secondary);line-height:1.7;padding:12px 16px;background:var(--ui-bg-secondary);border-radius:4px}
.recommendation{border:1px solid var(--ui-border);border-radius:6px;padding:16px;margin-bottom:16px;display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap}
.recommendation>p{flex-basis:100%;margin:0;color:var(--ui-text-secondary);font-size:12px}
.ant-form-item{margin-bottom:16px}.ant-input-number{width:100%}.switch-label{margin-left:12px}.ant-alert{margin:0 0 16px}
.mold-dialog .ant-descriptions-item-label{white-space:nowrap}.mold-dialog .ant-form{color:var(--ui-text-primary)}
@media(max-width:900px){.side{display:none}main{padding:16px}.design-note>span{display:none}.breadcrumb-card>.ant-card-body{padding:16px}.tab-card .ant-tabs-nav{min-width:72px}}
</style>
