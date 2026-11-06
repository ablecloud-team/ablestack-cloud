<template>
<div class="design-app">
 <div class="design-note"><b>B · #1451 개선 UI 목업</b><span>설계 예시 · 실제 API 미연결</span><a-button size="small" @click="go('list')">목록</a-button><a-button size="small" @click="go('details')">상세</a-button><a-button size="small" @click="go('nfs')">NFS</a-button><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트"/></div>
 <div class="app-shell"><aside class="side"><b>ABLESTACK</b><p>대시보드</p><p>컴퓨트　⌄</p><p>　가상머신</p><p>스토리지　⌄</p><div class="nav-selected">공유 파일 시스템</div><p>네트워크</p><p>이미지</p><p>인프라스트럭쳐</p></aside>
 <div class="workspace"><header class="topbar"><span>☰　 기본 보기</span><span>AC　admin cloud</span></header>
 <a-card class="breadcrumb-card"><a-row align="middle"><a-col :span="18"><Breadcrumb :resource="{name:'sharedfs-idmap-example'}"><template #end><a-button size="small" shape="round"><ReloadOutlined/>업데이트</a-button></template></Breadcrumb></a-col><a-col :span="6" class="breadcrumb-actions"><a-button v-if="view==='list'" type="primary">공유 파일 시스템 생성</a-button><a-button v-else>작업 <DownOutlined/></a-button></a-col></a-row></a-card>
 <main>
 <template v-if="view==='list'"><div class="list-toolbar"><a-select value="all" style="width:160px"><a-select-option value="all">전체</a-select-option></a-select><a-input-search placeholder="이름 검색" style="width:280px"/></div><a-table :columns="listColumns" :data-source="services" row-key="id" size="small" :pagination="{pageSize:20,showSizeChanger:true,showTotal:()=> '전체 1 개 항목 중 1–1 표시'}"><template #bodyCell="{column,record}"><a v-if="column.key==='name'" @click="go('nfs')">{{record.name}}</a><a-badge v-else-if="column.key==='state'" status="success" text="사용 가능"/></template></a-table></template>
 <ResourceLayout v-else :wide="wide">
  <template #left><a-card class="resource-info"><div class="resource-icon"><FileTextOutlined/></div><h3>sharedfs-idmap-example</h3><a-badge status="success" text="사용 가능 · 설계 예시"/><a-divider/><dl><dt>주 IP</dt><dd>10.10.13.197</dd><dt>컴퓨트 오퍼링</dt><dd>2 CPU / 4 GiB</dd><dt>네트워크</dt><dd>L2-Network</dd><dt>백킹 볼륨</dt><dd>SPARSE · XFS · 10 GiB</dd><dt>서비스 설정 Revision</dt><dd>#12</dd></dl></a-card></template>
  <template #right><a-card class="tab-card"><a-tabs v-model:active-key="tab" tab-position="left" :animated="false">
   <a-tab-pane key="details" tab="상세"><a-descriptions :column="1" bordered size="small"><a-descriptions-item label="이름">sharedfs-idmap-example</a-descriptions-item><a-descriptions-item label="상태">사용 가능</a-descriptions-item><a-descriptions-item label="활성 프로토콜">NFS</a-descriptions-item><a-descriptions-item label="주 IP">10.10.13.197</a-descriptions-item><a-descriptions-item label="백킹 볼륨">SPARSE · XFS · 10 GiB</a-descriptions-item></a-descriptions></a-tab-pane>
   <a-tab-pane key="nfs" tab="NFS">
    <div class="toolbar"><a-button type="primary"><PlusOutlined/>NFS 내보내기 생성</a-button><a-button>프로토콜 활성화</a-button><a-button @click="dialog='settings';phase='edit'">ID 매핑 설정</a-button><a-button><DeleteOutlined/>엔드포인트 제거</a-button><a-button><ReloadOutlined/>업데이트</a-button><a-button @click="wide=!wide"><FullscreenOutlined/>{{wide?'기본 보기':'넓게 보기'}}</a-button></div>
    <div class="mapping-panel"><div class="section-heading first">NFSv4 ID 매핑 <a-tag>{{legacy?'기존 호환 설정':'정책 Revision #7'}}</a-tag></div>
     <a-descriptions :column="1" bordered size="small"><a-descriptions-item label="설정 모드">이름/도메인 (기존 호환)</a-descriptions-item><a-descriptions-item label="현재 적용"><a-badge :status="legacy?'warning':'success'" :text="legacy?'확인되지 않음':'이름/도메인 · 모든 포트 일치'"/></a-descriptions-item><a-descriptions-item label="적용 범위">서비스 전체 · 포트 2049, 2050 · AUTH_SYS</a-descriptions-item><a-descriptions-item label="이름 매핑 도메인">localdomain <span class="secondary">· 서버 기존 설정</span></a-descriptions-item></a-descriptions>
     <p class="secondary">{{legacy?'실행 설정 증거가 없는 기존 서비스입니다. 모드 적용 후 포트별 실행 상태를 확인합니다.':'포트별 실행 프로세스와 적용 정책이 확인된 관측 예시입니다.'}}</p>
     <a-button size="small" @click="dialog='client'">클라이언트 점검 안내</a-button>
    </div>
    <div class="section-heading">수신 포트 그룹</div><a-table :columns="endpointColumns" :data-source="endpoints" row-key="port" :pagination="false" size="small"><template #bodyCell="{column}"><a-badge v-if="column.key==='result'" :status="legacy?'warning':'success'" :text="legacy?'확인되지 않음':'일치'"/></template></a-table>
    <div class="section-heading">NFS 내보내기 목록</div><a-table :columns="exportColumns" :data-source="exports" row-key="id" :pagination="false" size="small" :scroll="{x:570}"><template #bodyCell="{column}"><a-button v-if="column.key==='action'" size="small"><EditOutlined/>편집</a-button></template></a-table>
    <p class="secondary">ID 매핑은 소유자 속성의 표현 방식입니다. 현재 squash·실제 UID/GID·POSIX 권한은 유지합니다.</p>
   </a-tab-pane>
   <a-tab-pane v-for="t in ['SMB','iSCSI','NVMe-oF','런타임','네트워크','메트릭','작업 내역','이벤트']" :key="t" :tab="t"><a-empty description="이 설계의 변경 범위 밖"/></a-tab-pane>
  </a-tabs></a-card></template>
 </ResourceLayout>
 </main></div></div>
 <MoldDialog v-if="dialog==='settings'" :visible="true" title="NFSv4 ID 매핑 설정" size="lg" form-actions @cancel="dialog=''">
  <a-form layout="vertical">
   <a-descriptions bordered :column="2" size="small"><a-descriptions-item label="서비스">sharedfs-idmap-example</a-descriptions-item><a-descriptions-item label="현재 설정">이름/도메인 · 정책 {{legacy?'#0':'#7'}}</a-descriptions-item><a-descriptions-item label="현재 적용">{{legacy?'확인되지 않음':'이름/도메인 · 포트별 일치'}}</a-descriptions-item><a-descriptions-item label="수신 포트">2049, 2050</a-descriptions-item></a-descriptions>
   <a-form-item label="적용할 모드" class="spaced"><a-select v-model:value="selected" :disabled="phase==='preview'" @change="phase='edit';ack=false"><a-select-option value="NAME_DOMAIN">이름/도메인 (기존 호환)</a-select-option><a-select-option value="NUMERIC">숫자 UID/GID (AUTH_SYS)</a-select-option></a-select></a-form-item>
   <a-alert type="info" show-icon :message="selected==='NUMERIC'?'숫자 UID/GID로 소유자 속성을 교환합니다.':'서버 기존 이름/도메인 매핑을 사용합니다.'" :description="selected==='NUMERIC'?'Linux 클라이언트의 nfs4_disable_idmapping=Y와 sec=sys를 확인하세요. 서버와 클라이언트의 실제 숫자 UID/GID가 맞아야 합니다.':'기존 idmap 도메인과 NSS 사용자 조회를 유지합니다. 서버·클라이언트의 도메인과 사용자 조회 설정을 확인하세요. 숫자 ID fallback은 기존처럼 허용합니다.'"/>
   <div class="section-heading">클라이언트 확인 사항</div>
   <a-descriptions bordered :column="1" size="small"><a-descriptions-item label="인증 방식">AUTH_SYS (sec=sys)</a-descriptions-item><a-descriptions-item v-if="selected==='NUMERIC'" label="Linux 확인 명령"><code>cat /sys/module/nfs/parameters/nfs4_disable_idmapping</code></a-descriptions-item><a-descriptions-item v-if="selected==='NUMERIC'" label="NUMERIC 기대값">Y · 클라이언트에서 확인</a-descriptions-item><a-descriptions-item v-if="selected==='NAME_DOMAIN'" label="클라이언트 점검">서버·클라이언트 도메인/NSS 대조 · RPC 숫자 UID/GID 일치 확인</a-descriptions-item><a-descriptions-item label="현재 서버 도메인">localdomain · 기존 설정 유지</a-descriptions-item></a-descriptions>
   <p class="secondary">클라이언트 설정은 해당 시스템에서 확인합니다. 이 대화상자는 서버의 서비스 정책을 변경합니다.</p>
   <div class="section-heading">변경 영향</div>
   <a-descriptions bordered :column="1" size="small"><a-descriptions-item label="적용 범위">이 서비스의 모든 NFS 포트 그룹 · 2049, 2050</a-descriptions-item><a-descriptions-item label="실행 영향">NFS 프로세스 재시작 · 기존 연결 일시 중단·재접속 필요</a-descriptions-item><a-descriptions-item label="권한·데이터">현재 squash·파일 UID/GID·mode·데이터 유지</a-descriptions-item><a-descriptions-item label="정책 Revision">{{phase==='preview'?'#7 → #13 (점검 예시)':'적용 시 새 정책 Revision으로 함께 확정'}}</a-descriptions-item></a-descriptions>
   <template v-if="phase==='preview'"><a-alert class="spaced" type="warning" show-icon message="사전 점검 완료 · 목업 예시" description="모든 포트의 변경 후 정책, 일시 중단 영향과 클라이언트 요구조건을 확인하세요. 실제 API와 연결되지 않은 설계 화면입니다."/><a-table class="spaced" :columns="previewColumns" :data-source="previewRows" row-key="port" :pagination="false" size="small"/><a-checkbox v-model:checked="ack" class="spaced">모든 NFS 포트의 일시 중단과 클라이언트 요구조건을 확인했습니다.</a-checkbox></template>
  </a-form>
  <MoldDialogActions><a-button @click="dialog=''">취소</a-button><a-button type="primary" :disabled="phase==='preview'&&!ack" @click="phase==='preview'?dialog='':phase='preview'">{{phase==='preview'?'변경 적용':'사전 점검'}}</a-button></MoldDialogActions>
 </MoldDialog>
 <MoldDialog v-if="dialog==='client'" :visible="true" title="NFSv4 클라이언트 점검 안내" size="lg" form-actions @cancel="dialog=''">
  <a-alert type="info" show-icon message="표시와 실제 접근 권한을 구분합니다." description="NUMERIC은 ID 속성의 이름 변환을 줄입니다. AUTH_SYS의 실제 UID/GID·squash·POSIX 접근 권한은 별도로 확인합니다."/>
  <div class="section-heading">Linux NUMERIC 점검</div><a-descriptions bordered :column="1" size="small"><a-descriptions-item label="확인 명령"><code>cat /sys/module/nfs/parameters/nfs4_disable_idmapping</code></a-descriptions-item><a-descriptions-item label="기대값">Y</a-descriptions-item><a-descriptions-item label="마운트 옵션">sec=sys · NFSv4</a-descriptions-item><a-descriptions-item label="숫자 소유권 확인"><code>ls -ln /마운트/경로</code></a-descriptions-item></a-descriptions>
  <div class="section-heading">결과 해석</div><a-table :columns="diagnosticColumns" :data-source="diagnostics" row-key="id" :pagination="false" size="small"/>
  <p class="secondary">클라이언트 전역 커널 설정 변경은 다른 NFS 마운트에도 영향을 줄 수 있습니다. 각 클라이언트에서 적용 범위와 재접속을 검토합니다.</p>
  <MoldDialogActions><a-button type="primary" @click="dialog=''">닫기</a-button></MoldDialogActions>
 </MoldDialog>
</div>
</template>
<script>
import ResourceLayout from '@/layouts/ResourceLayout.vue'
import Breadcrumb from '@/components/widgets/Breadcrumb.vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import MoldDialogActions from '@/components/view/MoldDialogActions.vue'
import {PlusOutlined,ReloadOutlined,FullscreenOutlined,EditOutlined,FileTextOutlined,DownOutlined,DeleteOutlined} from '@ant-design/icons-vue'
const q=new URLSearchParams(location.search)
export default {
 components:{ResourceLayout,Breadcrumb,MoldDialog,MoldDialogActions,PlusOutlined,ReloadOutlined,FullscreenOutlined,EditOutlined,FileTextOutlined,DownOutlined,DeleteOutlined},
 data(){return{view:q.get('view')||'nfs',tab:q.get('view')==='details'?'details':'nfs',dark:q.get('theme')!=='light',wide:false,legacy:q.get('status')==='legacy',dialog:q.get('dialog')||'',selected:q.get('mode')||'NUMERIC',phase:q.get('scenario')==='preview'?'preview':'edit',ack:false}},
 watch:{dark:{immediate:true,handler(v){document.body.classList.toggle('dark-mode',v)}}},
 computed:{
 services(){return[{id:'example',name:'sharedfs-idmap-example',state:'Ready',storage:'Primary Storage',account:'admin',zone:'13-Zone'}]},
 listColumns(){return[{title:'이름',dataIndex:'name',key:'name'},{title:'상태',key:'state'},{title:'스토리지',dataIndex:'storage'},{title:'계정',dataIndex:'account'},{title:'Zone',dataIndex:'zone'}]},
 endpoints(){return[2049,2050].map(port=>({port,mode:this.legacy?'확인되지 않음':'이름/도메인',revision:this.legacy?'—':'#7'}))},
 endpointColumns(){return[{title:'포트',dataIndex:'port'},{title:'적용 모드',dataIndex:'mode'},{title:'정책 Revision',dataIndex:'revision'},{title:'관측',key:'result'}]},
 exports(){return[{id:'example',name:'team-nfs',access:'읽기/쓰기',squash:'Root Squash',posix:'65534:65534 / 0775'}]},
 exportColumns(){return[{title:'내보내기 이름',dataIndex:'name',width:140},{title:'권한',dataIndex:'access',width:90},{title:'사용자 매핑',dataIndex:'squash',width:110},{title:'실제 POSIX 권한',dataIndex:'posix',width:160},{title:'작업',key:'action',width:70}]},
 previewRows(){return[2049,2050].map(port=>({port,before:'이름/도메인 · '+(this.legacy?'#0':'#7'),after:(this.selected==='NUMERIC'?'숫자 UID/GID':'이름/도메인')+' · #13',state:'점검 예시'}))},
 previewColumns(){return[{title:'포트',dataIndex:'port'},{title:'현재 정책',dataIndex:'before'},{title:'변경 후 정책',dataIndex:'after'},{title:'상태',dataIndex:'state'}]},
 diagnosticColumns(){return[{title:'관측 예시',dataIndex:'case'},{title:'해석·확인할 항목',dataIndex:'meaning'}]},
 diagnostics(){return[{id:1,case:'서버 UID1001 / 클라이언트65534',meaning:'ID 매핑·도메인·캐시 대조 필요. 실제 접근 결과도 확인.'},{id:2,case:'서버·클라이언트 모두65534',meaning:'Root/All Squash의 익명 UID 정책과 대조.'},{id:3,case:'표시가 같지만 쓰기 거부',meaning:'RPC 숫자 UID/GID·그룹·ACL·POSIX·RO 정책 확인.'}]}
 },
 methods:{go(view){location.href='?view='+view+'&theme='+(this.dark?'dark':'light')}}
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
.spaced{margin-top:16px}.section-heading.first{margin-top:0}.mapping-panel{margin-bottom:16px}
@media(max-width:900px){.side{display:none}main{padding:16px}.design-note>span{display:none}.breadcrumb-card>.ant-card-body{padding:16px}.tab-card .ant-tabs-nav{min-width:72px}}
</style>
