<template>
<div class="design-app">
 <div class="design-note"><strong>B · #1454 개선 UI 목업</strong><span>실제 Mold 공통 컴포넌트 · API 미연결 · 서비스 변경 없음</span><a-button size="small" @click="go('list')">목록</a-button><a-button size="small" @click="go('details')">상세</a-button><a-button size="small" @click="go('smb')">SMB</a-button><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트"/></div>
 <div class="app-shell"><aside class="side"><b>ABLESTACK</b><p>대시보드</p><p>컴퓨트　⌄</p><p>　가상머신</p><p>스토리지　⌄</p><div class="selected">공유 파일 시스템</div><p>네트워크</p><p>이미지</p></aside>
 <div class="workspace"><header class="topbar"><span>☰　 기본 보기</span><span>AC　admin cloud</span></header>
 <a-card class="breadcrumb-card"><a-row align="middle"><a-col :span="18"><Breadcrumb :resource="{name:'sharedfs-mask-design'}"><template #end><a-button size="small" shape="round"><ReloadOutlined/>업데이트</a-button></template></Breadcrumb></a-col><a-col :span="6" style="text-align:right"><a-button v-if="view!=='list'">작업 <DownOutlined/></a-button></a-col></a-row></a-card>
 <main>
 <template v-if="view==='list'"><div class="list-toolbar"><a-space><a-button type="primary" ><PlusOutlined/>공유 파일 시스템 생성</a-button><a-select value="all" style="width:160px"><a-select-option value="all">전체</a-select-option></a-select></a-space><a-input-search placeholder="이름 검색" style="width:260px"/></div><a-table :columns="listColumns" :data-source="[{id:'design',name:'sharedfs-mask-design',state:'사용 가능',storage:'Primary Storage',size:'10 GiB',account:'admin',zone:'13-Zone'}]" row-key="id" size="small" :pagination="{pageSize:20,showSizeChanger:true}"><template #bodyCell="{column,record}"><a v-if="column.key==='name'" @click="go('smb')">{{record.name}}</a><a-badge v-else-if="column.key==='state'" status="success" :text="record.state"/></template></a-table></template>
 <ResourceLayout v-else :wide="wide"><template #left><a-card class="resource-info"><FileTextOutlined class="resource-icon"/><h3>sharedfs-mask-design</h3><a-badge status="success" text="사용 가능 · 설계 예시"/><a-divider/><dl><dt>주 IP</dt><dd>10.10.13.220</dd><dt>백킹 볼륨</dt><dd>XFS · SPARSE · 10 GiB</dd><dt>활성 서비스</dt><dd>NFS · SMB</dd><dt>계정</dt><dd>admin</dd><dt>Zone</dt><dd>13-Zone</dd></dl></a-card></template>
 <template #right><a-card class="tab-card"><a-tabs v-model:active-key="activeTab" tab-position="left" :animated="false">
  <a-tab-pane key="details" tab="상세"><div class="detail-grid"><template v-for="(v,k) in details" :key="k"><strong>{{k}}</strong><span>{{v}}</span></template></div></a-tab-pane>
  <a-tab-pane key="directory" tab="공유 디렉터리"><a-descriptions bordered size="small" :column="1"><a-descriptions-item label="실제 부모">/export/team · UID 65534 · GID 65534 · mode 2770</a-descriptions-item><a-descriptions-item label="공통 POSIX 정책">새 객체 생성 mask와 구분해 관리합니다.</a-descriptions-item></a-descriptions></a-tab-pane>
  <a-tab-pane key="nfs" tab="NFS"><a-empty description="이 설계에서 변경하지 않는 탭"/></a-tab-pane>
  <a-tab-pane key="smb" tab="SMB"><div class="toolbar"><a-button type="primary" ><PlusOutlined/>SMB 공유 생성</a-button><a-button><PlusOutlined/>엔드포인트 추가</a-button><a-button><ReloadOutlined/>업데이트</a-button><a-button @click="wide=!wide"><FullscreenOutlined/>{{wide?'기본 보기':'넓게 보기'}}</a-button></div>
   <a-descriptions bordered size="small" :column="1"><a-descriptions-item label="접속 정보">\\10.10.13.220\team-smb</a-descriptions-item><a-descriptions-item label="인증 방식">기존 로컬 · AD 인증 유지</a-descriptions-item></a-descriptions>
   <a-table class="section" :columns="smbColumns" :data-source="[{name:'team-smb',endpoint:'10.10.13.220:445',masks:'0660 / 0770',owner:'부모 UID 상속',group:'활성 · setgid',state:'Ready'}]" row-key="name" size="small" :pagination="false" :scroll="{x:820}"><template #bodyCell="{column}"><a-space v-if="column.key==='action'"><a-button size="small"><EditOutlined/>편집</a-button><a-button size="small" @click="open('policy')"><SettingOutlined/>생성 권한 설정</a-button></a-space></template></a-table>
   <p class="secondary">새 객체 mask는 기존 디렉터리 권한과 별개입니다. 설정된 mask·force 값과 유효 설정은 생성 권한 설정에서 확인합니다.</p>
  </a-tab-pane>
  <a-tab-pane v-for="t in ['iSCSI','NVMe-oF','런타임','네트워크','메트릭','작업 내역','이벤트']" :key="t" :tab="t"><a-empty description="이 설계에서 변경하지 않는 탭"/></a-tab-pane>
 </a-tabs></a-card></template></ResourceLayout>
 </main></div></div>
 <MoldDialog v-if="dialog" :visible="true" title="SMB 생성 권한 설정" size="lg" form-actions @cancel="dialog=''">
  <a-form layout="vertical">

   <a-descriptions bordered size="small" :column="2"><a-descriptions-item label="SMB 공유">team-smb</a-descriptions-item><a-descriptions-item label="적용 범위">이 공유의 새 파일·디렉터리</a-descriptions-item><a-descriptions-item label="기존 데이터">소유자·권한·ACL·내용 유지</a-descriptions-item><a-descriptions-item label="변경 방식">연결 유지 · 설정 갱신</a-descriptions-item></a-descriptions>
   <p class="secondary">8진수 0000~0777을 입력합니다. force mode는 해당 mask 안의 비트만 지정할 수 있습니다.</p>
   <a-row :gutter="16" class="creation-fields">
    <a-col v-for="f in fields" :key="f.key" :xs="24" :sm="12"><a-form-item :label="f.label" :validate-status="fieldError(f.key)?'error':''" :help="fieldError(f.key)"><a-input v-model:value="form[f.key]" :aria-label="f.label" :disabled="phase==='preview'" :maxlength="5" spellcheck="false"/><div class="hint">현재 설정 {{defaults[f.key]}} · {{f.hint}}</div></a-form-item></a-col>
   </a-row>
   <a-alert v-if="error" type="error" show-icon :message="error"/>
   <a-alert v-else-if="executable" class="section" type="warning" show-icon message="새 파일의 실행 비트를 허용하거나 강제합니다." description="파일 mask의 실행 비트는 허용 범위이며 force create mode의 실행 비트는 강제 설정입니다. 사전 점검에서 영향을 확인하세요."/>
   <div class="section-heading">함께 적용되는 정책</div>
   <a-descriptions bordered size="small" :column="2"><a-descriptions-item label="부모 UID 상속">켜짐 · inherit owner</a-descriptions-item><a-descriptions-item label="부모 GID 상속">켜짐 · setgid</a-descriptions-item><a-descriptions-item label="부모 실제 mode">2770 · 기존 값 유지</a-descriptions-item><a-descriptions-item label="고정 UID/GID">없음</a-descriptions-item><a-descriptions-item label="권한 전체 상속">{{scenario==='inherited'?'켜짐 · 우선 적용':'꺼짐 · inherit permissions'}}</a-descriptions-item><a-descriptions-item label="부모 기본 ACL">있음 · 최종 mode에 영향</a-descriptions-item></a-descriptions>
   <p class="secondary">mask·force는 새 객체의 rwx를, inherit owner·setgid는 UID·GID를 결정합니다. 실제 mode는 클라이언트 요청과 부모 기본 ACL의 영향을 받습니다. 이 창에서 부모 권한을 변경하지 않습니다.</p>

   <a-alert v-if="scenario==='unsupported'" type="warning" show-icon message="현재 SystemVM은 이 생성 권한 정책의 호환성 보호를 지원하지 않습니다." description="기존 공유를 유지합니다. 지원되는 기준 구성으로 유지보수한 뒤 설정할 수 있습니다."/>
   <a-alert v-if="scenario==='inherited'" type="error" show-icon message="현재 inherit permissions가 mask·force보다 우선합니다." description="현재 유효 설정을 먼저 정리해야 합니다. 이 창은 기존 상속 설정을 자동으로 끄지 않습니다."/>
   <template v-if="phase==='preview'">
    <div class="section-heading">사전 점검 결과 · 설계 예시</div>
    <a-alert :type="blocked?'error':'success'" show-icon :message="blocked?'적용 전 차단':'연결을 유지하며 새 객체 생성 정책을 갱신합니다.'" :description="blocked?'기존 설정과 데이터는 변경되지 않습니다.':'현재 연결과 열린 파일은 유지합니다. 기존 파일의 권한을 소급 변경하지 않습니다.'"/>
    <a-table class="section" size="small" :pagination="false" :columns="previewColumns" :data-source="previewRows" row-key="key"/>
    <a-descriptions class="section" bordered size="small" :column="2"><a-descriptions-item label="유효 설정 확인">설정 파일 검증 · 각 수신 포트</a-descriptions-item><a-descriptions-item label="적용 확인">설정 갱신 전달 · 리스너 응답</a-descriptions-item><a-descriptions-item label="기존 PID·연결">유지</a-descriptions-item><a-descriptions-item label="파일 mode 예시">0775 요청도 부모 ACL에 따라 0770 가능</a-descriptions-item></a-descriptions>
    <a-checkbox v-model:checked="ack" :disabled="blocked" class="section">{{executable?'실행 비트 허용·강제와 새 객체 적용 범위를 확인했습니다.':'새 객체 적용 범위와 기존 연결 유지를 확인했습니다.'}}</a-checkbox>
   </template>
   <a-alert v-if="scenario==='rollback'" class="section" type="warning" show-icon message="설정 갱신 실패 · 이전 설정으로 원복 완료" description="연결과 열린 파일은 유지했습니다. 요청 식별자와 포트별 진단은 작업 내역에 보존합니다."/>
  </a-form>
  <MoldDialogActions><a-button @click="dialog=''">취소</a-button><a-button v-if="phase==='preview'" @click="phase='edit';ack=false">다시 편집</a-button><a-button type="primary" :disabled="blocked||!changed||(phase==='preview'&&!ack)" @click="submit">{{phase==='preview'?'변경 적용':'사전 점검'}}</a-button></MoldDialogActions>
 </MoldDialog>
</div>
</template>
<script>
import ResourceLayout from '@/layouts/ResourceLayout.vue'
import Breadcrumb from '@/components/widgets/Breadcrumb.vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import MoldDialogActions from '@/components/view/MoldDialogActions.vue'
import {PlusOutlined,ReloadOutlined,FullscreenOutlined,EditOutlined,FileTextOutlined,DownOutlined,SettingOutlined} from '@ant-design/icons-vue'
const q=new URLSearchParams(location.search)
const defaults={createMask:'0660',directoryMask:'0770',forceCreateMode:'0000',forceDirectoryMode:'0000'}
export default{
 components:{ResourceLayout,Breadcrumb,MoldDialog,MoldDialogActions,PlusOutlined,ReloadOutlined,FullscreenOutlined,EditOutlined,FileTextOutlined,DownOutlined,SettingOutlined},
 data(){const scenario=q.get('scenario')||'';return{view:q.get('view')||'smb',activeTab:q.get('view')||'smb',wide:q.get('wide')==='1',dark:q.get('theme')!=='light',dialog:q.get('dialog')||'',phase:q.get('phase')||'edit',scenario,ack:false,defaults,form:scenario==='unchanged'?{...defaults}:scenario==='conflict'?{...defaults,createMask:'0600',forceCreateMode:'0040'}:{...defaults,createMask:'0775',directoryMask:'0775',forceCreateMode:'0111',forceDirectoryMode:'0005'}}},
 watch:{dark:{immediate:true,handler(v){document.body.classList.toggle('dark-mode',v)}}},
 computed:{
  fields(){return[{key:'createMask',label:'파일 create mask',hint:'새 파일에 허용할 rwx 비트'},{key:'forceCreateMode',label:'파일 force create mode',hint:'새 파일에 반드시 설정할 비트'},{key:'directoryMask',label:'디렉터리 directory mask',hint:'새 디렉터리에 허용할 rwx 비트'},{key:'forceDirectoryMode',label:'디렉터리 force directory mode',hint:'새 디렉터리에 반드시 설정할 비트'}]},
  error(){for(const f of this.fields){if(this.fieldError(f.key))return this.fieldError(f.key)}return ''},
  blocked(){return !!this.error||this.scenario==='inherited'||this.scenario==='unsupported'},
  changed(){return Object.keys(defaults).some(k=>this.form[k]!==defaults[k])},
  executable(){return !this.error&&!!((parseInt(this.form.createMask,8)|parseInt(this.form.forceCreateMode,8))&0o111)},
  details(){return{'이름':'sharedfs-mask-design','상태':'사용 가능','주 IP':'10.10.13.220','활성 서비스':'NFS · SMB','백킹 볼륨':'SPARSE · XFS · 10 GiB','계정':'admin','Zone':'13-Zone'}},
  listColumns(){return[{title:'이름',dataIndex:'name',key:'name'},{title:'상태',dataIndex:'state',key:'state'},{title:'스토리지',dataIndex:'storage'},{title:'크기',dataIndex:'size'},{title:'계정',dataIndex:'account'},{title:'Zone',dataIndex:'zone'}]},
  smbColumns(){return[{title:'SMB 공유 이름',dataIndex:'name',width:140},{title:'IP / 포트',dataIndex:'endpoint',width:150},{title:'파일 / 디렉터리 mask',dataIndex:'masks',width:160},{title:'새 객체 UID',dataIndex:'owner',width:150},{title:'부모 GID 상속',dataIndex:'group',width:130},{title:'상태',dataIndex:'state',width:80},{title:'작업',key:'action',width:260}]},
  previewColumns(){return[{title:'항목',dataIndex:'label'},{title:'변경 전',dataIndex:'before'},{title:'변경 후',dataIndex:'after'}]},
  previewRows(){return this.fields.map(f=>({key:f.key,label:f.label,before:defaults[f.key],after:this.form[f.key]}))}
 },
 methods:{
  go(v){this.view=v;this.activeTab=v;this.dialog=''},
  open(kind){this.dialog=kind;this.phase='edit';this.ack=false},
  fieldError(k){if(!/^0?[0-7]{3}$/.test(this.form[k]))return '0000~0777 범위의 8진수를 입력하세요.';const pairs={forceCreateMode:'createMask',forceDirectoryMode:'directoryMask'};const mask=pairs[k];if(mask&&/^0?[0-7]{3}$/.test(this.form[mask])&&(parseInt(this.form[k],8)&~parseInt(this.form[mask],8)))return 'force mode는 해당 mask 안의 비트만 지정할 수 있습니다.';return ''},
  submit(){if(this.blocked)return;if(this.phase==='edit'){this.phase='preview';this.ack=false}else this.dialog=''}
 }
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
.section{margin-top:16px}.secondary,.hint{color:var(--ui-text-secondary);font-size:12px;line-height:1.7}.secondary{margin:12px 0}.hint{margin-top:4px}.section-heading{font-weight:600;color:var(--ui-text-primary);margin:16px 0 12px}
.creation-fields{margin-top:12px}.creation-fields .ant-form-item{margin-bottom:6px}.creation-fields .ant-input{font-variant-numeric:tabular-nums;font-family:monospace}
.detail-grid{display:grid;grid-template-columns:170px 1fr}.detail-grid>*{padding:12px;border-bottom:1px solid var(--ui-border)}.detail-grid>strong{background:var(--ui-bg-secondary)}
@media(max-width:900px){.side{display:none}main{padding:16px}.design-note>span{display:none}}
</style>
