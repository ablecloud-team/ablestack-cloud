<template>
<div class="design-app">
 <div class="design-note"><b>B · #1453 개선 UI 목업</b><span>설계 예시 · 실제 API 미연결 · 서비스 변경 없음</span><a-button size="small" @click="go('list')">목록</a-button><a-button size="small" @click="go('details')">상세</a-button><a-button size="small" @click="go('smb')">SMB</a-button><a-button size="small" @click="go('directory')">공유 디렉터리</a-button><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트"/></div>
 <div class="app-shell"><aside class="side"><b>ABLESTACK</b><p>대시보드</p><p>컴퓨트　⌄</p><p>　가상머신</p><p>스토리지　⌄</p><div class="nav-selected">공유 파일 시스템</div><p>네트워크</p><p>이미지</p><p>인프라스트럭쳐</p></aside>
 <div class="workspace"><header class="topbar"><span>☰　 기본 보기</span><span>AC　admin cloud</span></header>
 <a-card class="breadcrumb-card"><a-row align="middle"><a-col :span="18"><Breadcrumb :resource="{name:'sharedfs-parent-example'}"><template #end><a-button size="small" shape="round"><ReloadOutlined/>업데이트</a-button></template></Breadcrumb></a-col><a-col :span="6" class="breadcrumb-actions"><a-button v-if="view!=='list'">작업 <DownOutlined/></a-button></a-col></a-row></a-card>
 <main>
 <template v-if="view==='list'"><div class="list-toolbar"><a-space><a-button type="primary">공유 파일 시스템 생성</a-button><a-select value="all" style="width:160px"><a-select-option value="all">전체</a-select-option></a-select></a-space><a-input-search placeholder="이름 검색" style="width:260px"/></div><a-table :columns="listColumns" :data-source="services" row-key="id" size="small" :pagination="{pageSize:20,showSizeChanger:true}"><template #bodyCell="{column,record}"><a v-if="column.key==='name'" @click="go('smb')">{{record.name}}</a><a-badge v-else-if="column.key==='state'" status="success" text="사용 가능"/></template></a-table></template>
 <ResourceLayout v-else :wide="wide"><template #left><a-card class="resource-info"><div class="resource-icon"><FileTextOutlined/></div><h3>sharedfs-parent-example</h3><a-badge status="success" text="사용 가능 · 설계 예시"/><a-divider/><dl><dt>주 IP</dt><dd>10.10.13.241</dd><dt>컴퓨트 오퍼링</dt><dd>2 CPU / 4 GiB</dd><dt>네트워크</dt><dd>L2-Network</dd><dt>백킹 볼륨</dt><dd>SPARSE · XFS · 10 GiB</dd><dt>서비스 설정 리비전</dt><dd>#20</dd></dl></a-card></template>
 <template #right><a-card class="tab-card"><a-tabs v-model:active-key="tab" tab-position="left" :animated="false">
 <a-tab-pane key="details" tab="상세"><div class="detail-grid"><template v-for="(value,key) in details" :key="key"><strong>{{key}}</strong><span>{{value}}</span></template></div></a-tab-pane>
 <a-tab-pane key="directory" tab="공유 디렉터리">
  <div class="toolbar"><a-button type="primary"><PlusOutlined/>기존 디렉터리 등록</a-button><a-button><ReloadOutlined/>업데이트</a-button></div>
  <a-alert type="info" show-icon message="setgid는 같은 실제 디렉터리를 참조하는 NFS·SMB가 공유하는 속성입니다." description="현재 소유자와 inode를 유지하고 setgid 비트만 변경합니다. 기존 하위 디렉터리·파일의 권한은 자동 변경하지 않습니다."/>
  <a-table :columns="directoryColumns" :data-source="[{path:'/export/team',parent:'1001:2000 / '+baseMode,group:baseSetgid?'활성':'미사용',refs:'NFS · team-nfs / SMB · team-smb',revision:4}]" row-key="path" :pagination="false" size="small" :scroll="{x:760}"><template #bodyCell="{column}"><a v-if="column.key==='action'" @click="open('setgid')">부모 GID 상속 설정</a></template></a-table>
 </a-tab-pane>
 <a-tab-pane key="nfs" tab="NFS"><div class="toolbar"><a-button type="primary"><PlusOutlined/>NFS 내보내기 생성</a-button><a-button><ReloadOutlined/>업데이트</a-button><a-button @click="wide=!wide"><FullscreenOutlined/>넓게 보기</a-button></div><a-alert type="info" show-icon message="NFS의 생성자 UID·squash 정책은 그대로입니다." description="setgid 디렉터리에서 새 객체의 GID만 부모를 따릅니다. SMB inherit owner를 NFS UID 변경으로 적용하지 않습니다."/><a-table :columns="nfsColumns" :data-source="[{name:'team-nfs',endpoint:'2049 · 2050',access:'RW · Root Squash',parent:'1001:2000 / 2770'}]" row-key="name" :pagination="false" size="small"/></a-tab-pane>
 <a-tab-pane key="smb" tab="SMB">
  <div class="toolbar"><a-button type="primary" @click="open('createSmb')"><PlusOutlined/>SMB 공유 생성</a-button><a-button><PlusOutlined/>엔드포인트 추가</a-button><a-button><ReloadOutlined/>업데이트</a-button><a-button @click="wide=!wide"><FullscreenOutlined/>{{wide?'기본 보기':'넓게 보기'}}</a-button></div>
  <a-descriptions :column="1" bordered size="small"><a-descriptions-item label="접속 정보">\\10.10.13.241\team-smb</a-descriptions-item><a-descriptions-item label="인증 방식">로컬 계정 · AD는 기존 가입/매핑 기능을 사용</a-descriptions-item></a-descriptions>
  <div class="section-heading">SMB 공유 목록</div><a-table :columns="smbColumns" :data-source="[{name:'team-smb',access:'읽기/쓰기',endpoint:'10.10.13.241:445',owner:'생성자 UID 유지',group:baseSetgid?'부모 GID 상속 · setgid':'생성자 GID 유지',parent:'1001:2000 / '+baseMode}]" row-key="name" :pagination="false" size="small" :scroll="{x:900}"><template #bodyCell="{column}"><a v-if="column.key==='action'" @click="open('smb')">편집</a><a v-else-if="column.key==='policy'" @click="go('directory')">공유 디렉터리에서 관리</a></template></a-table>
  <p class="secondary">새 객체의 인증 주체와 숫자 UID/GID를 구분합니다. 상속 설정은 기존 파일 소유자·내용·공유 ACL을 바꾸지 않습니다.</p>
  <div class="section-heading">SMB 접근 및 계정</div><a-table :columns="aclColumns" :data-source="[{name:'team-smb',principal:'alice',type:'로컬 사용자',access:'읽기/쓰기'}]" row-key="principal" :pagination="false" size="small"/>
 </a-tab-pane>
 <a-tab-pane v-for="t in ['iSCSI','NVMe-oF','런타임','네트워크','메트릭','작업 내역','이벤트']" :key="t" :tab="t"><a-empty description="이 설계에서 변경하지 않는 탭"/></a-tab-pane>
 </a-tabs></a-card></template></ResourceLayout>
 </main></div></div>
 <MoldDialog v-if="dialog==='smb'||dialog==='createSmb'" :visible="true" :title="dialog==='createSmb'?'SMB 공유 생성':'SMB 공유 수정'" size="lg" form-actions @cancel="dialog=''">
  <a-form layout="vertical">
   <a-form-item label="공통 POSIX 정책 참조"><a-select value="team"><a-select-option value="team">/export/team · 1001:2000 / 2770 · DATA</a-select-option></a-select></a-form-item>
   <a-alert type="info" show-icon message="공통 POSIX 권한을 상속합니다." description="이 폼은 기존 UID/GID·mode 필드를 독립적으로 제출하지 않습니다."/>
   <a-form-item label="이름"><a-input :value="dialog==='createSmb'?'team-smb-new':'team-smb'"/></a-form-item><a-form-item label="클라이언트 별칭 경로"><a-input :value="dialog==='createSmb'?'/export/team-smb-new':'/export/team-smb'"/></a-form-item>
   <div class="section-heading">백킹 볼륨</div><a-form-item label="백킹 볼륨 선택"><a-radio-group value="current" disabled><a-radio value="current">현재 백킹 볼륨</a-radio><a-radio value="existing">기존 볼륨</a-radio></a-radio-group></a-form-item><a-form-item label="현재 백킹 볼륨"><a-select value="data" disabled><a-select-option value="data">DATA · XFS · SPARSE · 10 GiB</a-select-option></a-select></a-form-item><a-checkbox :checked="false" disabled>디렉터리가 없으면 생성</a-checkbox>
   <a-form-item label="SMB 공유 용량 제한" class="spaced"><a-input-number/><span class="switch-label">GiB</span></a-form-item><a-form-item label="NFS와 같은 경로 공유"><a-switch :checked="true"/></a-form-item><a-form-item label="디렉터리 권한"><a-input :value="baseMode" readonly/></a-form-item>
   <div class="old-checks"><a-checkbox>읽기 전용</a-checkbox><a-checkbox :checked="true">탐색 허용</a-checkbox><a-checkbox>게스트 접근</a-checkbox></div>
   <div class="section-heading">새 객체의 소유권</div>
   <a-form-item label="부모 UID 상속 (inherit owner)"><a-switch v-model:checked="inheritOwner" :disabled="phase==='preview'"/><span class="switch-label">{{inheritOwner?'부모 디렉터리의 UID':'생성자 UID 유지'}}</span></a-form-item>
   <p class="secondary">로컬·AD 로그인과 공유 ACL은 그대로입니다. 켜면 새 객체의 UNIX UID와 Windows owner가 부모를 따릅니다. 기존 파일·부모 owner를 chown하지 않습니다.</p>
   <a-descriptions :column="1" bordered size="small"><a-descriptions-item label="현재 실제 부모">UID 1001 · GID 2000 · mode {{baseMode}} · inode 8389001</a-descriptions-item><a-descriptions-item label="부모 GID 상속">{{baseSetgid?"setgid 활성":"setgid 미사용"}} · <a @click="dialog='';go('directory');open('setgid')">공유 디렉터리에서 관리</a></a-descriptions-item><a-descriptions-item label="생성자 예시">alice · 숫자 UID 1101 / GID 1101</a-descriptions-item><a-descriptions-item label="새 파일 예상">UID {{inheritOwner?1001:1101}} · GID {{baseSetgid?2000:1101}} · mode 0660</a-descriptions-item><a-descriptions-item label="새 디렉터리 예상">UID {{inheritOwner?1001:1101}} · GID {{baseSetgid?2000:1101}} · mode {{baseSetgid?"2770":"0770"}}</a-descriptions-item><a-descriptions-item label="고정 identity 정책">{{conflict?'force user = builduser · 상속과 충돌':'없음'}}</a-descriptions-item></a-descriptions>
   <p class="secondary">표시한 모드는 현재 mask와 부모 정책의 예시입니다. 실제 값은 클라이언트 요청과 부모 ACL의 영향을 받습니다. 기존 하위 부모의 setgid 비트는 바꾸지 않습니다.</p>
   <template v-if="phase==='preview'"><a-alert class="spaced" :type="blocked?'error':'success'" show-icon :message="blocked?'사전 조건 차단':'사전 점검 완료 · 설계 예시'" :description="blocked?(unsupported?'현재 SystemVM 기준 구성은 상속 기능의 호환성 보호를 지원하지 않습니다. 일반 번들 업데이트만으로 해결되지 않습니다. 기존 공유를 유지하며 지원되는 기준 구성이 필요합니다.':'부모 상속과 고정 UID/GID 정책을 동시에 적용할 수 없습니다. 자동 우회하거나 권한을 확대하지 않습니다.'):'새 객체에만 적용합니다. 기존 하위 파일 변경 없음. SMB 엔드포인트 설정 갱신으로 연결이 잠시 끊길 수 있습니다. NFS 설정과 UID 정책은 유지합니다.'"/><a-checkbox v-model:checked="ack" :disabled="blocked" class="spaced">상속 범위·인증과 숫자 소유자 구분·SMB 일시 중단을 확인했습니다.</a-checkbox></template>
  </a-form><MoldDialogActions><a-button @click="dialog=''">취소</a-button><a-button v-if="phase==='preview'" @click="phase='edit';ack=false">다시 편집</a-button><a-button type="primary" :disabled="phase==='preview'?(!ack||blocked):(dialog==='smb'&&!inheritOwner)" @click="preview">{{phase==='preview'?'변경 적용':'사전 점검'}}</a-button></MoldDialogActions>
 </MoldDialog>
 <MoldDialog v-if="dialog==='setgid'" :visible="true" title="부모 GID 상속 설정" size="lg" form-actions @cancel="dialog=''">
  <a-form layout="vertical"><a-descriptions :column="1" bordered size="small"><a-descriptions-item label="볼륨 내 경로">/export/team · SPARSE DATA</a-descriptions-item><a-descriptions-item label="현재 실제 부모">UID 1001 · GID 2000 · mode {{baseMode}} · inode 8389001</a-descriptions-item><a-descriptions-item label="정책">공통 POSIX · revision 4</a-descriptions-item></a-descriptions>
   <a-form-item label="부모 GID 상속 (setgid)" class="spaced"><a-switch v-model:checked="setgid" :disabled="phase==='preview'"/><span class="switch-label">{{setgid?'새 객체가 부모 GID를 상속':'setgid 비트 해제'}}</span></a-form-item>
   <a-descriptions :column="1" bordered size="small"><a-descriptions-item label="변경 후 부모">UID 1001 · GID 2000 · mode {{setgid?'2770':'0770'}} · inode 유지</a-descriptions-item><a-descriptions-item label="기존 파일·하위 디렉터리">소유자·mode·ACL·데이터 변경 없음 · 재귀 적용 안 함</a-descriptions-item><a-descriptions-item label="새 객체">부모의 현재 setgid 상태에 따라 GID 결정 · SMB UID 상속은 별도</a-descriptions-item><a-descriptions-item label="NFS 영향">2049 · 2050 속성 갱신 · 연결 일시 중단 가능</a-descriptions-item></a-descriptions>
   <div class="section-heading">같은 실제 경로를 참조하는 공유</div><a-table :columns="refsColumns" :data-source="[{name:'team-nfs',protocol:'NFS',port:'2049 · 2050'},{name:'team-smb',protocol:'SMB',port:'445'}]" row-key="name" :pagination="false" size="small"/>
   <a-alert class="spaced" type="info" show-icon message="부모 mode의 setgid 비트만 변경합니다." description="소유자·기본 rwx·그 외 특수 비트는 유지합니다. 해제해도 이미 만들어진 하위 디렉터리의 setgid 비트는 그대로입니다."/>
   <template v-if="phase==='preview'"><a-alert :type="blocked?'error':'success'" show-icon :message="blocked?'사전 조건 차단 · 고정 identity 정책 충돌':'사전 점검 완료 · 디렉터리 1개만 변경'"/><a-checkbox v-model:checked="ack" :disabled="blocked" class="spaced">NFS·SMB 공통 영향과 NFS 서비스 갱신을 확인했습니다.</a-checkbox></template>
  </a-form><MoldDialogActions><a-button @click="dialog=''">취소</a-button><a-button v-if="phase==='preview'" @click="phase='edit';ack=false">다시 편집</a-button><a-button type="primary" :disabled="phase==='preview'?(!ack||blocked):(setgid===baseSetgid)" @click="preview">{{phase==='preview'?'변경 적용':'사전 점검'}}</a-button></MoldDialogActions>
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
 data(){return{view:q.get('view')||'smb',tab:q.get('view')||'smb',dark:q.get('theme')!=='light',wide:false,dialog:q.get('dialog')||'',phase:q.get('scenario')?'preview':'edit',conflict:q.get('scenario')==='conflict',unsupported:q.get('scenario')==='unsupported',ack:false,inheritOwner:q.get('changed')==='1'||!!q.get('scenario'),setgid:(q.get('changed')==='1'||!!q.get('scenario'))?(q.get('parent')==='plain'):(q.get('parent')!=='plain')}},
 watch:{dark:{immediate:true,handler(v){document.body.classList.toggle('dark-mode',v)}}},
 computed:{
  blocked(){return this.conflict||this.unsupported},
  baseSetgid(){return q.get('parent')!=='plain'},
  baseMode(){return this.baseSetgid?'2770':'0770'},
  details(){return{'이름':'sharedfs-parent-example','상태':'사용 가능','주 IP':'10.10.13.241','활성 서비스':'NFS · SMB','백킹 볼륨':'SPARSE · XFS · 10 GiB','계정':'admin','Zone':'13-Zone'}},
  services(){return[{id:'example',name:'sharedfs-parent-example',state:'Ready',storage:'Primary Storage',account:'admin',zone:'13-Zone'}]},
  listColumns(){return[{title:'이름',dataIndex:'name',key:'name'},{title:'상태',key:'state'},{title:'스토리지',dataIndex:'storage'},{title:'계정',dataIndex:'account'},{title:'Zone',dataIndex:'zone'}]},
  smbColumns(){return[{title:'이름',dataIndex:'name',width:130},{title:'IP / 포트',dataIndex:'endpoint',width:140},{title:'권한',dataIndex:'access',width:100},{title:'새 객체 UID',dataIndex:'owner',width:140},{title:'새 객체 GID',dataIndex:'group',width:180},{title:'현재 실제 부모',dataIndex:'parent',width:160},{title:'공통 정책',key:'policy',width:160},{title:'작업',key:'action',width:80}]},
  directoryColumns(){return[{title:'볼륨 내 경로',dataIndex:'path',width:140},{title:'공통 POSIX 정책',dataIndex:'parent',width:150},{title:'부모 GID 상속',dataIndex:'group',width:110},{title:'참조하는 공유',dataIndex:'refs',width:180},{title:'리비전',dataIndex:'revision',width:70},{title:'작업',key:'action',width:160}]},
  aclColumns(){return[{title:'SMB 공유 이름',dataIndex:'name'},{title:'대상 주체',dataIndex:'principal'},{title:'유형',dataIndex:'type'},{title:'권한',dataIndex:'access'}]},
  refsColumns(){return[{title:'이름',dataIndex:'name'},{title:'프로토콜',dataIndex:'protocol'},{title:'포트',dataIndex:'port'}]},
  nfsColumns(){return[{title:'이름',dataIndex:'name'},{title:'포트',dataIndex:'endpoint'},{title:'권한',dataIndex:'access'},{title:'현재 실제 부모',dataIndex:'parent'}]}
 },
 methods:{go(v){this.view=v;this.tab=v},open(kind){this.dialog=kind;this.phase='edit';this.ack=false;if(kind==='createSmb')this.inheritOwner=false},preview(){if(this.phase==='preview'){this.dialog='';return}this.phase='preview';this.ack=false}}
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

.old-checks{display:flex;flex-wrap:wrap;gap:16px;margin-bottom:16px}
.mold-dialog .ant-alert-description,.mold-dialog .secondary{line-height:1.7}
</style>
