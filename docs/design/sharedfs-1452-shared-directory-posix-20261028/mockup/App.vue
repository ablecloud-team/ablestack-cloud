<template>
<div class="design-app">
 <div class="design-note"><b>B · #1452 개선 UI 목업</b><span>설계 예시 · 실제 API 미연결</span><a-button size="small" @click="go('list')">목록</a-button><a-button size="small" @click="go('details')">상세</a-button><a-button size="small" @click="go('directory')">공유 디렉터리</a-button><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트"/></div>
 <div class="app-shell"><aside class="side"><b>ABLESTACK</b><p>대시보드</p><p>컴퓨트　⌄</p><p>　가상머신</p><p>스토리지　⌄</p><div class="nav-selected">공유 파일 시스템</div><p>네트워크</p><p>이미지</p><p>인프라스트럭쳐</p></aside>
 <div class="workspace"><header class="topbar"><span>☰　 기본 보기</span><span>AC　admin cloud</span></header>
 <a-card class="breadcrumb-card"><a-row align="middle"><a-col :span="18"><Breadcrumb :resource="{name:'sharedfs-team-example'}"><template #end><a-button size="small" shape="round"><ReloadOutlined/>업데이트</a-button></template></Breadcrumb></a-col><a-col :span="6" class="breadcrumb-actions"><a-button v-if="view!=='list'">작업 <DownOutlined/></a-button></a-col></a-row></a-card>
 <main>
 <template v-if="view==='list'"><div class="list-toolbar"><a-space><a-button type="primary">공유 파일 시스템 생성</a-button><a-select value="all" style="width:160px"><a-select-option value="all">전체</a-select-option></a-select></a-space><a-input-search placeholder="이름 검색" style="width:280px"/></div><a-table :columns="listColumns" :data-source="services" row-key="id" size="small" :pagination="{pageSize:20,showSizeChanger:true,showTotal:()=> '전체 1 개 항목 중 1–1 표시'}"><template #bodyCell="{column,record}"><a v-if="column.key==='name'" @click="go('directory')">{{record.name}}</a><a-badge v-else-if="column.key==='state'" status="success" text="사용 가능"/></template></a-table></template>
 <ResourceLayout v-else :wide="wide">
  <template #left><a-card class="resource-info"><div class="resource-icon"><FileTextOutlined/></div><h3>sharedfs-team-example</h3><a-badge status="success" text="사용 가능 · 설계 예시"/><a-divider/><dl><dt>주 IP</dt><dd>10.10.13.193</dd><dt>컴퓨트 오퍼링</dt><dd>2 CPU / 4 GiB</dd><dt>네트워크</dt><dd>L2-Network</dd><dt>백킹 볼륨</dt><dd>SPARSE · XFS · 10 GiB</dd><dt>서비스 설정 리비전</dt><dd>#12</dd></dl></a-card></template>
  <template #right><a-card class="tab-card"><a-tabs v-model:active-key="tab" tab-position="left" :animated="false">
   <a-tab-pane key="details" tab="상세"><div class="detail-grid"><template v-for="(value,key) in details" :key="key"><strong>{{key}}</strong><span>{{value}}</span></template></div></a-tab-pane>
   <a-tab-pane key="directory" tab="공유 디렉터리">
    <div class="toolbar"><a-button type="primary" @click="open('adopt')"><PlusOutlined/>기존 디렉터리 등록</a-button><a-button @click="open('edit')"><EditOutlined/>권한 변경</a-button><a-button><ReloadOutlined/>업데이트</a-button></div>
    <a-alert type="info" show-icon message="같은 경로의 NFS·SMB는 하나의 POSIX 정책을 사용합니다." description="소유자·디렉터리 모드·하위 항목 적용을 여기에서 관리합니다. 프로토콜별 RO/RW·squash·인증은 각 탭에서 관리합니다."/>
    <a-table :columns="policyColumns" :data-source="policies" row-key="id" :pagination="false" size="small" :scroll="{x:750}"><template #bodyCell="{column,record}"><a v-if="column.key==='path'" @click="open(record.id==='legacy'?'adopt':'edit')">{{record.path}}</a><a-tag v-else-if="column.key==='status'" :color="record.id==='legacy'?'gold':'green'">{{record.id==='legacy'?'정책 등록 필요':'일치'}}</a-tag><a v-else-if="column.key==='action'" @click="open(record.id==='legacy'?'adopt':'edit')">{{record.id==='legacy'?'현재 값으로 등록':'편집'}}</a></template></a-table>
    <div class="section-heading">선택한 디렉터리 · /export/team</div>
    <a-descriptions :column="1" bordered size="small"><a-descriptions-item label="공통 정책">1001:1001 / 0770 · #3</a-descriptions-item><a-descriptions-item label="현재 실제 값">1001:1001 / 0770 · 일치</a-descriptions-item><a-descriptions-item label="백킹 볼륨">DATA · XFS · SPARSE</a-descriptions-item><a-descriptions-item label="마지막 적용">확인 완료 · 하위 항목 변경 없음</a-descriptions-item></a-descriptions>
    <div class="section-heading">참조하는 공유</div><a-table :columns="refColumns" :data-source="refs" row-key="name" :pagination="false" size="small"/>
    <p class="secondary">기존 설정은 자동 덮어쓰지 않습니다. 등록은 실제 디렉터리의 현재 값을 기준으로 하며, 충돌은 사전 점검에서 해결합니다.</p>
   </a-tab-pane>
   <a-tab-pane v-for="proto in ['NFS','SMB']" :key="proto.toLowerCase()" :tab="proto">
    <div class="toolbar"><a-button type="primary"><PlusOutlined/>{{proto==='NFS'?'NFS 내보내기 생성':'SMB 공유 생성'}}</a-button><a-button @click="dialog='reference'"><EditOutlined/>편집</a-button><a-button><ReloadOutlined/>업데이트</a-button><a-button @click="wide=!wide"><FullscreenOutlined/>{{wide?'기본 보기':'넓게 보기'}}</a-button></div>
    <a-table :columns="protocolColumns" :data-source="[{name:proto==='NFS'?'team-nfs':'team-smb',path:'/export/team',access:'읽기/쓰기',policy:'1001:1001 / 0770 · #3'}]" row-key="name" :pagination="false" size="small"><template #bodyCell="{column}"><a v-if="column.key==='link'" @click="tab='directory'">공유 디렉터리에서 관리</a></template></a-table>
    <p class="secondary">공통 정책을 참조한 공유는 POSIX 필드를 별도로 제출하지 않습니다. 연결되지 않은 기존 단독 공유의 편집 흐름은 유지합니다.</p>
   </a-tab-pane>
   <a-tab-pane v-for="t in ['iSCSI','NVMe-oF','런타임','네트워크','메트릭','작업 내역','이벤트']" :key="t" :tab="t"><a-empty description="이 설계의 변경 범위 밖"/></a-tab-pane>
  </a-tabs></a-card></template>
 </ResourceLayout>
 </main></div></div>
 <MoldDialog v-if="dialog==='edit'||dialog==='adopt'" :visible="true" :title="dialog==='adopt'?'공유 디렉터리 등록':'공유 디렉터리 권한 변경'" size="lg" form-actions @cancel="dialog=''">
  <a-form layout="vertical">
   <a-descriptions bordered :column="2" size="small"><a-descriptions-item label="경로">{{dialog==='adopt'?'/export/legacy':'/export/team'}}</a-descriptions-item><a-descriptions-item label="백킹 볼륨">DATA · XFS · SPARSE</a-descriptions-item><a-descriptions-item label="현재 실제 값">1001:1001 / 0770</a-descriptions-item><a-descriptions-item label="공통 정책">{{dialog==='adopt'?'미등록':'#3 · 서비스 #12'}}</a-descriptions-item></a-descriptions>
   <a-alert v-if="dialog==='adopt'" class="spaced" type="info" show-icon message="현재 실제 값으로 등록 · 파일·권한 변경 없음" description="기존 NFS·SMB 설정의 권장값을 자동 적용하지 않습니다. 서로 다른 설정값은 충돌 목록으로 확인하고, 등록 이후 별도 권한 변경으로 처리합니다."/>
   <div v-if="dialog==='adopt'" class="spaced"><a-descriptions bordered :column="1" size="small"><a-descriptions-item label="NFS 기존 저장값">1001:1001 / 0755</a-descriptions-item><a-descriptions-item label="SMB 기존 저장값">디렉터리 모드 0770</a-descriptions-item><a-descriptions-item label="등록할 공통 값">실제 관측 1001:1001 / 0770 · 파일 권한 변경 없음</a-descriptions-item></a-descriptions></div>
   <template v-else>
   <a-row :gutter="16" class="spaced"><a-col :span="8"><a-form-item label="소유자 UID"><a-input-number v-model:value="uid" :disabled="phase==='preview'" :min="0"/></a-form-item></a-col><a-col :span="8"><a-form-item label="소유자 GID"><a-input-number v-model:value="gid" :disabled="phase==='preview'" :min="0"/></a-form-item></a-col><a-col :span="8"><a-form-item label="디렉터리 모드"><a-input v-model:value="mode" :disabled="phase==='preview'" placeholder="0770"/></a-form-item></a-col></a-row>
   <a-form-item label="하위 항목까지 적용"><a-switch v-model:checked="recursive" :disabled="phase==='preview'"/><span class="switch-label">{{recursive?'기존 하위 항목도 변경 · 영향 점검 필요':'디렉터리만 변경 · 하위 파일 보존'}}</span></a-form-item>
   </template>
   <div class="section-heading">직접 영향을 받는 공유</div><a-table :columns="refColumns" :data-source="refs" row-key="name" :pagination="false" size="small"/>
   <div class="section-heading">변경 영향</div>
   <a-descriptions bordered :column="1" size="small"><a-descriptions-item label="변경 후 POSIX">{{dialog==='adopt'?'현재 실제 값 등록 · 권한 변경 없음':uid+':'+gid+' / '+mode}}</a-descriptions-item><a-descriptions-item label="하위 항목">{{recursive?'명시적 재귀 변경 · 사전 점검의 파일 수·스냅샷 상한 내에서만 적용':'UID/GID·모드·데이터 변경 없음'}}</a-descriptions-item><a-descriptions-item label="프로토콜 정책">NFS squash·RO/RW, SMB 인증·계정·생성 마스크 유지</a-descriptions-item><a-descriptions-item label="NFS 서비스">{{dialog==='adopt'?'등록만 수행 · 서비스 재시작 없음':'포트 2049·2050 서비스 갱신 · 기존 연결 일시 중단 가능'}}</a-descriptions-item><a-descriptions-item v-if="dialog!=='adopt'" label="같은 NFS 프로세스의 다른 공유">archive-nfs · 권한 유지 · 서비스 갱신 영향 있음</a-descriptions-item><a-descriptions-item label="실패 처리">두 프로토콜 확인 후 확정 · 실패 시 기존 소유자·모드·ACL·정책 원복</a-descriptions-item></a-descriptions>
   <template v-if="phase==='preview'">
    <a-alert class="spaced" :type="conflict?'error':'success'" show-icon :message="conflict?'적용 불가 · 공통 ACL 마스크 충돌':'사전 점검 완료 · 설계 예시'" :description="conflict?'모드 0750의 그룹 마스크는 r-x입니다. SMB 사용자 alice의 RW에는 rwx가 필요합니다. 권한을 자동 확대하지 않으며 정책 또는 ACL을 조정한 뒤 다시 점검하세요.':'같은 실제 경로 1개 · 직접 참조 2개 · NFS 갱신 영향 1개 · 새 경합·변경 감지 시 재점검이 필요합니다.'"/>
    <a-checkbox v-model:checked="ack" :disabled="conflict" class="spaced">{{dialog==='adopt'?'현재 실제 값과 기존 설정 차이를 확인했습니다.':'두 프로토콜의 영향·권한 변경·NFS 서비스 일시 중단을 확인했습니다.'}}</a-checkbox>
   </template>
  </a-form>
  <MoldDialogActions><a-button @click="dialog=''">취소</a-button><a-button v-if="phase==='preview'" @click="phase='edit';ack=false">다시 편집</a-button><a-button type="primary" :disabled="phase==='preview'?(!ack||conflict):(dialog==='edit'&&!dirty)" @click="preview">{{phase==='preview'?(dialog==='adopt'?'현재 값으로 등록':'변경 적용'):'사전 점검'}}</a-button></MoldDialogActions>
 </MoldDialog>
 <MoldDialog v-if="dialog==='reference'" :visible="true" :title="tab==='nfs'?'NFS 내보내기 수정':'SMB 공유 수정'" size="lg" form-actions @cancel="dialog=''">
  <a-form layout="vertical"><a-form-item label="이름"><a-input :value="tab==='nfs'?'team-nfs':'team-smb'"/></a-form-item><a-form-item label="공유 디렉터리"><a-select value="team"><a-select-option value="team">DATA /export/team · 1001:1001 / 0770</a-select-option></a-select></a-form-item><a-descriptions bordered :column="1" size="small"><a-descriptions-item label="상속한 POSIX">1001:1001 / 0770 · 정책 #3</a-descriptions-item><a-descriptions-item label="변경 위치"><a @click="dialog='';tab='directory'">공유 디렉터리에서 관리</a></a-descriptions-item></a-descriptions><a-form-item label="읽기 전용" class="spaced"><a-switch/></a-form-item><a-form-item v-if="tab==='nfs'" label="사용자 매핑"><a-select value="root"><a-select-option value="root">Root Squash</a-select-option></a-select></a-form-item><a-alert type="info" show-icon message="프로토콜 접근 설정만 변경합니다." description="공통 UID/GID·디렉터리 모드·재귀 적용 필드는 이 요청에서 제출하지 않습니다."/></a-form>
  <MoldDialogActions><a-button @click="dialog=''">취소</a-button><a-button type="primary" @click="dialog=''">사전 점검</a-button></MoldDialogActions>
 </MoldDialog>
</div>
</template>
<script>
import ResourceLayout from '@/layouts/ResourceLayout.vue'
import Breadcrumb from '@/components/widgets/Breadcrumb.vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import MoldDialogActions from '@/components/view/MoldDialogActions.vue'
import {PlusOutlined,ReloadOutlined,FullscreenOutlined,EditOutlined,FileTextOutlined,DownOutlined} from '@ant-design/icons-vue'
const q=new URLSearchParams(location.search)
export default {
 components:{ResourceLayout,Breadcrumb,MoldDialog,MoldDialogActions,PlusOutlined,ReloadOutlined,FullscreenOutlined,EditOutlined,FileTextOutlined,DownOutlined},
 data(){return{view:q.get('view')||'directory',tab:q.get('view')||'directory',dark:q.get('theme')!=='light',wide:false,dialog:q.get('dialog')||'',phase:q.get('scenario')?'preview':'edit',conflict:q.get('scenario')==='conflict',ack:false,uid:1001,gid:1001,mode:q.get('scenario')==='conflict'?'0750':(q.get('scenario')?'0775':'0770'),recursive:false}},
 watch:{dark:{immediate:true,handler(v){document.body.classList.toggle('dark-mode',v)}}},
 computed:{
 dirty(){return this.uid!==1001||this.gid!==1001||this.mode!=='0770'||this.recursive},
 details(){return{'이름':'sharedfs-team-example','상태':'사용 가능','주 IP':'10.10.13.193','활성 프로토콜':'NFS · SMB','백킹 볼륨':'SPARSE · XFS · 10 GiB','계정':'admin','Zone':'13-Zone'}},
 services(){return[{id:'example',name:'sharedfs-team-example',state:'Ready',storage:'Primary Storage',account:'admin',zone:'13-Zone'}]},
 listColumns(){return[{title:'이름',dataIndex:'name',key:'name'},{title:'상태',key:'state'},{title:'스토리지',dataIndex:'storage'},{title:'계정',dataIndex:'account'},{title:'Zone',dataIndex:'zone'}]},
 policies(){return[{id:'team',path:'/export/team',volume:'DATA · XFS',value:'1001:1001 / 0770',revision:'#3',refs:'NFS 1 · SMB 1'},{id:'legacy',path:'/export/legacy',volume:'DATA · XFS',value:'1001:1001 / 0770',revision:'—',refs:'NFS 1 · SMB 1'}]},
 policyColumns(){return[{title:'경로',key:'path',width:140},{title:'백킹 볼륨',dataIndex:'volume',width:100},{title:'현재 실제 POSIX',dataIndex:'value',width:145},{title:'정책',dataIndex:'revision',width:60},{title:'참조',dataIndex:'refs',width:110},{title:'관측',key:'status',width:110},{title:'작업',key:'action',width:120}]},
 refs(){const prefix=this.dialog==='adopt'?'legacy':'team';return[{name:prefix+'-nfs',protocol:'NFS',access:'RW · Root Squash',endpoint:'2049 · 2050'},{name:prefix+'-smb',protocol:'SMB',access:'RW · 로컬 인증',endpoint:'445'}]},
 refColumns(){return[{title:'이름',dataIndex:'name'},{title:'프로토콜',dataIndex:'protocol'},{title:'접근 정책',dataIndex:'access'},{title:'포트',dataIndex:'endpoint'}]},
 protocolColumns(){return[{title:'이름',dataIndex:'name'},{title:'경로',dataIndex:'path'},{title:'권한',dataIndex:'access'},{title:'공통 POSIX',dataIndex:'policy'},{title:'관리',key:'link'}]}
 },
 methods:{go(view){location.href='?view='+view+'&theme='+(this.dark?'dark':'light')},open(kind){this.uid=1001;this.gid=1001;this.mode='0770';this.recursive=false;this.dialog=kind;this.phase='edit';this.conflict=false;this.ack=false},preview(){if(this.phase==='preview'){this.dialog='';return}this.conflict=this.mode==='0750'&&this.dialog!=='adopt';this.phase='preview';this.ack=false}}
}
</script>
<style>
html,body,#app{margin:0;min-height:100%;font-family:Arial,"Malgun Gothic",sans-serif}
.design-app{min-height:100vh;color:var(--ui-text-primary);background:var(--ui-bg-page)}
.design-note{display:flex;align-items:center;gap:12px;padding:10px 16px;background:var(--ui-bg-elevated);border-bottom:1px solid var(--ui-border);font-size:12px}
.design-note>span{flex:1;color:var(--ui-text-secondary)}.app-shell{display:flex;min-height:calc(100vh - 48px)}
.side{width:200px;flex-shrink:0;padding:20px 16px;background:var(--ui-bg-elevated);border-right:1px solid var(--ui-border)}.side>b{font-size:26px}.side>p{margin:28px 8px}.nav-selected{padding:12px;color:var(--ui-primary);background:var(--ui-bg-secondary);border-radius:4px}
.workspace{flex:1;min-width:0}.topbar{height:64px;display:flex;align-items:center;justify-content:space-between;padding:0 24px;background:var(--ui-bg-elevated);border-bottom:1px solid var(--ui-border)}
.breadcrumb-card{position:sticky;top:0;z-index:10;border-radius:0}.breadcrumb-card>.ant-card-body{padding:16px 24px}.breadcrumb-actions{text-align:right}
main{padding:16px 24px}.list-toolbar{display:flex;justify-content:space-between;gap:16px;margin:0 0 16px}.resource-info h3{overflow-wrap:anywhere;color:var(--ui-text-primary);margin-top:16px}.resource-icon{font-size:36px;color:var(--ui-primary)}
dl>dt{font-weight:600;margin:16px 0 4px}dl>dd{margin:0;color:var(--ui-text-secondary)}
.tab-card>.ant-card-body{padding:16px}.tab-card .ant-tabs-nav{min-width:104px}.tab-card .ant-tabs-content-holder{min-width:0}
.toolbar{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:0 0 16px}.secondary{color:var(--ui-text-secondary);font-size:12px;margin:8px 0;line-height:1.7}
.section-heading{font-weight:600;font-size:14px;color:var(--ui-text-primary);margin:16px 0 12px}
.detail-grid{display:grid;grid-template-columns:170px 1fr;gap:0}.detail-grid>*{padding:12px;border-bottom:1px solid var(--ui-border)}.detail-grid>strong{background:var(--ui-bg-secondary)}
.ant-form-item{margin-bottom:16px}.ant-input-number{width:100%}.switch-label{margin-left:12px}.ant-alert{margin:0 0 16px}.mold-dialog .ant-descriptions-item-label{white-space:nowrap}.mold-dialog .ant-form{color:var(--ui-text-primary)}.spaced{margin-top:16px}
.design-app .ant-btn-primary,.mold-dialog .ant-btn-primary{background:#096dd9;border-color:#096dd9;color:#fff}
@media(max-width:900px){.side{display:none}main{padding:16px}.design-note>span{display:none}.breadcrumb-card>.ant-card-body{padding:16px}.tab-card .ant-tabs-nav{min-width:80px}}
</style>
