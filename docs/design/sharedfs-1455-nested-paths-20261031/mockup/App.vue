<template>
<div class="design-app">
 <div class="design-note"><strong>B · #1455 개선 UI 목업</strong><span>설계 예시 · API 미연결 · 실제 서비스 변경 없음</span><a-button size="small" @click="go('list')">목록</a-button><a-button size="small" @click="go('details')">상세</a-button><a-button size="small" @click="go('nfs')">NFS</a-button><a-button size="small" @click="go('smb')">SMB</a-button><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트"/></div>
 <div class="app-shell"><aside class="side"><b>ABLESTACK</b><p>대시보드</p><p>컴퓨트　⌄</p><p>　가상머신</p><p>스토리지　⌄</p><div class="selected">공유 파일 시스템</div><p>네트워크</p><p>이미지</p></aside>
 <div class="workspace"><header class="topbar"><span>☰　 기본 보기</span><span>AC　admin cloud</span></header>
 <a-card class="breadcrumb-card"><a-row align="middle"><a-col :span="18"><Breadcrumb :resource="{name:'sharedfs-path-design'}"><template #end><a-button size="small" shape="round"><ReloadOutlined/>업데이트</a-button></template></Breadcrumb></a-col><a-col :span="6" style="text-align:right"><a-button v-if="view!=='list'">작업 <DownOutlined/></a-button></a-col></a-row></a-card>
 <main>
 <template v-if="view==='list'"><div class="list-toolbar"><a-space><a-button type="primary"><PlusOutlined/>공유 파일 시스템 생성</a-button><a-select value="all" style="width:150px"><a-select-option value="all">전체</a-select-option></a-select></a-space><a-input-search placeholder="이름 검색" style="width:250px"/></div><a-table :columns="listColumns" :data-source="[{id:'design',name:'sharedfs-path-design',state:'사용 가능',storage:'Primary Storage',size:'10 GiB',account:'admin',zone:'13-Zone'}]" row-key="id" size="small"><template #bodyCell="{column,record}"><a v-if="column.key==='name'" @click="go('nfs')">{{record.name}}</a><a-badge v-else-if="column.key==='state'" status="success" :text="record.state"/></template></a-table></template>
 <ResourceLayout v-else :wide="wide"><template #left><a-card class="resource-info"><FileTextOutlined class="resource-icon"/><h3>sharedfs-path-design</h3><a-badge status="success" text="사용 가능"/><dl><template v-for="(value,key) in details" :key="key"><dt>{{key}}</dt><dd>{{value}}</dd></template></dl></a-card></template>
 <template #right><a-card class="tab-card"><a-tabs v-model:activeKey="activeTab" tab-position="left">
 <a-tab-pane key="details" tab="상세"><div class="detail-grid"><template v-for="(value,key) in details" :key="key"><strong>{{key}}</strong><span>{{value}}</span></template></div></a-tab-pane>
 <a-tab-pane key="directory" tab="공유 디렉터리"><a-alert type="info" show-icon message="같은 실제 디렉터리의 NFS·SMB는 하나의 POSIX 정책을 사용합니다."/><a-table class="section" :columns="directoryColumns" :data-source="rows" row-key="name" size="small" :pagination="false"/></a-tab-pane>
 <a-tab-pane v-for="proto in ['nfs','smb']" :key="proto" :tab="proto.toUpperCase()">
 <div class="toolbar"><a-button type="primary" @click="dialog='create';phase='edit'"><PlusOutlined/>{{proto==='nfs'?'NFS 내보내기 생성':'SMB 공유 생성'}}</a-button><a-button><PlusOutlined/>엔드포인트 추가</a-button><a-button><ReloadOutlined/>업데이트</a-button><a-button @click="wide=!wide"><FullscreenOutlined/>{{wide?'원래 보기':'넓게 보기'}}</a-button></div>
 <a-table :columns="columns" :data-source="rows" row-key="name" size="small" :pagination="false" :scroll="{x:940}"><template #bodyCell="{column,record}"><template v-if="column.key==='name'"><a @click="selected=record;dialog='relations'">{{record.name}}</a></template><code v-else-if="column.key==='relative'">{{record.relative}}</code><template v-else-if="column.key==='relations'"><a-tag v-if="record.parent">상위 projects</a-tag><a-tag v-else color="blue">하위 공유 4</a-tag><a-button type="link" size="small" @click="selected=record;dialog='relations'">관계 보기</a-button></template><a-badge v-else-if="column.key==='state'" status="success" text="사용 가능"/><a-space v-else-if="column.key==='actions'"><a-button size="small" @click="form.name=record.name;form.relative=record.relative;dialog='create';phase='edit'"><EditOutlined/>편집</a-button><a-button size="small" danger @click="selected=record;dialog=record.parent?'deleteleaf':'delete'">삭제</a-button></a-space></template></a-table>
 <div class="section-heading">백킹 볼륨</div><a-table :columns="volumeColumns" :data-source="[{id:'data',name:'sharedfs-DATA-195',size:'10 GiB',format:'SPARSE · XFS',references:'6개 공유 · 3개 실제 경로'}]" row-key="id" size="small" :pagination="false"><template #bodyCell="{column}"><a-button v-if="column.key==='action'" @click="dialog='detach'">연결 해제 사유 확인</a-button></template></a-table>
 </a-tab-pane><a-tab-pane key="iscsi" tab="iSCSI"/><a-tab-pane key="nvmeof" tab="NVMe-oF"/><a-tab-pane key="runtime" tab="런타임"/><a-tab-pane key="nics" tab="네트워크"/><a-tab-pane key="history" tab="작업 내역"/>
 </a-tabs></a-card></template></ResourceLayout>
 </main></div></div>

 <MoldDialog v-if="dialog==='create'" :visible="true" :title="activeTab==='smb'?'SMB 공유 생성':'NFS 내보내기 생성'" size="lg" form-actions @cancel="dialog=''">
 <a-form layout="vertical">
 <a-row :gutter="16"><a-col :span="12"><a-form-item label="이름"><a-input v-model:value="form.name" aria-label="이름"/></a-form-item></a-col><a-col :span="12"><a-form-item label="클라이언트 별칭 경로"><a-input :value="'/export/'+form.name" readonly aria-label="클라이언트 별칭 경로"/></a-form-item></a-col></a-row>
 <p class="hint">{{activeTab==='smb'?'SMB 접속':'NFS 접속'}}: <code>{{activeTab==='smb'?'\\\\10.10.13.220\\'+form.name:'10.10.13.220:/'+form.name}}</code></p>
 <a-form-item label="백킹 볼륨 선택"><a-radio-group v-model:value="form.volumeMode"><a-radio value="CURRENT">현재 백킹 볼륨</a-radio><a-radio value="EXISTING">기존 볼륨</a-radio><a-radio value="NEW">새 볼륨 생성</a-radio></a-radio-group></a-form-item>
 <a-form-item label="현재 백킹 볼륨"><a-select value="data" style="width:100%"><a-select-option value="data">sharedfs-DATA-195 · 10 GiB · XFS · SPARSE · 연결됨</a-select-option></a-select></a-form-item>
 <a-form-item label="볼륨 내 상대 경로" :validate-status="pathError?'error':''" :help="pathError"><a-input v-model:value="form.relative" aria-label="볼륨 내 상대 경로" placeholder="projects/team-a"/></a-form-item>
 <a-descriptions bordered size="small" :column="1"><a-descriptions-item label="실제 경로"><code>{{physical}}</code></a-descriptions-item><a-descriptions-item label="현재 공유">{{form.relative==='projects'?'하위 NFS·SMB 공유 4개':'상위 projects · NFS/SMB · 같은 경로의 공통 POSIX 정책'}}</a-descriptions-item></a-descriptions>
 <a-checkbox v-model:checked="form.create">경로가 없으면 생성</a-checkbox><p class="hint">선택한 볼륨 안에서만 생성합니다. 기존 디렉터리·파일의 소유권과 권한은 유지합니다.</p>
 <a-form-item label="공통 POSIX 정책 참조" class="section"><a-select value="policy" style="width:100%"><a-select-option value="policy">projects/team-a · 65534:65534 / 0775 · 현재 권한 유지</a-select-option></a-select></a-form-item>
 <a-alert type="info" show-icon message="같은 경로의 NFS·SMB는 공통 정책을 사용합니다. 권한 변경은 공유 디렉터리에서 별도로 관리합니다."/>
 <div class="options section"><template v-if="activeTab==='smb'"><a-checkbox>읽기 전용</a-checkbox><a-checkbox :checked="true">탐색 허용</a-checkbox><a-checkbox>게스트 접근</a-checkbox></template><template v-else><a-checkbox>읽기 전용</a-checkbox><a-checkbox :checked="true">동기화</a-checkbox><a-select value="root" style="width:170px"><a-select-option value="root">Root Squash</a-select-option></a-select></template></div>
 <template v-if="phase==='checked'"><a-alert class="section" type="success" show-icon message="경로 사전 점검 완료"/><a-descriptions class="section" bordered size="small" :column="2"><a-descriptions-item label="디렉터리">{{wirePreview.exists?'기존 경로 · 생성 없음':'신규 경로'}}</a-descriptions-item><a-descriptions-item label="POSIX">{{wirePreview.owneruid}}:{{wirePreview.ownergid}} / {{wirePreview.mode}} 유지</a-descriptions-item><a-descriptions-item label="파일시스템">XFS · UUID 확인</a-descriptions-item><a-descriptions-item label="신원">inode {{wirePreview.inode}} · mount 경계 확인</a-descriptions-item></a-descriptions>
 <a-alert class="section" type="warning" show-icon message="상위 공유를 통해 하위 경로에 접근할 수 있습니다." description="중첩 공유는 별도의 보안 경계가 아닙니다. 하위 공유의 ACL만으로 상위 공유를 통한 접근을 차단할 수 없습니다."/><a-checkbox v-model:checked="ack" class="section">중첩 경로와 기존 공유의 영향 범위를 확인했습니다.</a-checkbox>
 </template></a-form>
 <MoldDialogActions><a-button @click="dialog=''">취소</a-button><a-button type="primary" :disabled="!!pathError||(phase==='checked'&&!ack)" @click="phase==='checked'?dialog='':phase='checked'">{{phase==='checked'?'변경 적용':'사전 점검'}}</a-button></MoldDialogActions>
 </MoldDialog>

 <MoldDialog v-if="dialog==='relations'" :visible="true" title="공유 경로 관계" size="lg" form-actions @cancel="dialog=''"><a-descriptions bordered size="small" :column="1"><a-descriptions-item label="백킹 볼륨">sharedfs-DATA-195 · SPARSE · XFS</a-descriptions-item><a-descriptions-item label="선택 경로"><code>{{selected.relative}}</code></a-descriptions-item></a-descriptions><a-table class="section" :columns="referenceColumns" :data-source="references" row-key="id" size="small" :pagination="false"/><a-alert class="section" type="warning" show-icon message="경로가 중첩되면 상위 공유의 접근 권한도 함께 확인해야 합니다."/><MoldDialogActions><a-button @click="dialog=''">닫기</a-button></MoldDialogActions></MoldDialog>
 <MoldDialog v-if="dialog==='delete'||dialog==='deleteleaf'" :visible="true" title="공유 설정 삭제" size="lg" form-actions @cancel="dialog=''"><a-descriptions bordered size="small" :column="1"><a-descriptions-item label="선택 공유">{{selected.name}}</a-descriptions-item><a-descriptions-item label="볼륨 내 상대 경로">{{selected.relative}}</a-descriptions-item></a-descriptions><a-alert class="section" :type="dialog==='delete'?'error':'warning'" show-icon :message="dialog==='delete'?'하위 공유가 있어 부모 공유를 삭제할 수 없습니다.':'공유 설정만 삭제합니다. 디렉터리와 파일은 유지됩니다.'"/><a-table v-if="dialog==='delete'" class="section" :columns="referenceColumns" :data-source="references.filter(r=>r.relative!=='projects')" row-key="id" size="small" :pagination="false"/><a-form-item v-else label="확인을 위해 공유 이름 입력" class="section"><a-input v-model:value="confirmation" aria-label="공유 이름 확인"/></a-form-item><p class="hint">백킹 볼륨, 디렉터리, 파일과 다른 공유는 삭제하지 않습니다.</p><MoldDialogActions><a-button @click="dialog=''">닫기</a-button><a-button danger type="primary" :disabled="dialog==='delete'||confirmation!==selected.name" @click="dialog=''">공유 설정 삭제</a-button></MoldDialogActions></MoldDialog>
 <MoldDialog v-if="dialog==='detach'" :visible="true" title="백킹 볼륨 연결 해제" size="lg" form-actions @cancel="dialog=''"><a-alert type="error" show-icon message="공유가 참조하는 볼륨은 연결 해제할 수 없습니다."/><a-descriptions class="section" bordered size="small" :column="2"><a-descriptions-item label="볼륨">sharedfs-DATA-195</a-descriptions-item><a-descriptions-item label="참조">NFS·SMB 6개 공유</a-descriptions-item></a-descriptions><a-table class="section" :columns="referenceColumns" :data-source="references" row-key="id" size="small" :pagination="false"/><MoldDialogActions><a-button @click="dialog=''">닫기</a-button><a-button type="primary" disabled>연결 해제</a-button></MoldDialogActions></MoldDialog>
</div>
</template>
<script>
import ResourceLayout from '@/layouts/ResourceLayout.vue'
import Breadcrumb from '@/components/widgets/Breadcrumb.vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import MoldDialogActions from '@/components/view/MoldDialogActions.vue'
import {PlusOutlined,ReloadOutlined,FullscreenOutlined,EditOutlined,FileTextOutlined,DownOutlined} from '@ant-design/icons-vue'
import wire from './path-preview-wire.json'
const q=new URLSearchParams(location.search)
export default{
 components:{ResourceLayout,Breadcrumb,MoldDialog,MoldDialogActions,PlusOutlined,ReloadOutlined,FullscreenOutlined,EditOutlined,FileTextOutlined,DownOutlined},
 data(){return{view:q.get('view')||'nfs',activeTab:q.get('view')||'nfs',dark:q.get('theme')!=='light',wide:q.get('wide')==='1',dialog:q.get('dialog')||'',phase:q.get('phase')||'edit',ack:false,confirmation:'',selected:{name:'projects',relative:'projects'},form:{name:'team-a',relative:q.get('scenario')==='invalid'?'projects/../private':'projects/team-a',volumeMode:'CURRENT',create:false},rows:[{name:'projects',relative:'projects',parent:false},{name:'team-a',relative:'projects/team-a',parent:true},{name:'team-b',relative:'projects/team-b',parent:true}]}},
 watch:{dark:{immediate:true,handler(value){document.body.classList.toggle('dark-mode',value)}},'form.relative'(){this.phase='edit';this.ack=false}},
 computed:{
 wirePreview(){return wire.preflightstorageserviceconfigurationresponse.configurationpreflight.pathpreview},
 details(){return{'이름':'sharedfs-path-design','주 IP':'10.10.13.220','볼륨':'10 GiB · SPARSE · XFS','계정':'admin','Zone':'13-Zone'}},
 physical(){return '/srv/ablestack-storage/volumes/e070a0aa-dbf5-48c0-83e0-09f874446277/'+this.form.relative},
 pathError(){const s=this.form.relative;if(!s||s.startsWith('/')||s.includes('\\')||s.split('/').some(p=>!p||p==='.'||p==='..'))return '볼륨 내 상대 경로를 입력하세요. 절대 경로·빈 경로·상위 이동·symlink는 허용하지 않습니다.';return ''},
 columns(){return[{title:this.activeTab==='smb'?'SMB 공유 이름':'NFS 내보내기 이름',key:'name',dataIndex:'name',width:140},{title:'볼륨 내 상대 경로',key:'relative',dataIndex:'relative',width:220},{title:'중첩 공유',key:'relations',width:210},{title:'상태',key:'state',width:100},{title:'작업',key:'actions',width:210}]},
 listColumns(){return[{title:'이름',key:'name',dataIndex:'name'},{title:'상태',key:'state',dataIndex:'state'},{title:'스토리지',dataIndex:'storage'},{title:'크기',dataIndex:'size'},{title:'계정',dataIndex:'account'},{title:'Zone',dataIndex:'zone'}]},
 directoryColumns(){return[{title:'실제 상대 경로',dataIndex:'relative'},{title:'참조',customRender:()=>'NFS · SMB'},{title:'POSIX 정책',customRender:()=>'현재 권한 유지'}]},
 volumeColumns(){return[{title:'볼륨',dataIndex:'name'},{title:'크기',dataIndex:'size'},{title:'형식',dataIndex:'format'},{title:'참조',dataIndex:'references'},{title:'작업',key:'action'}]},
 references(){return this.rows.flatMap(r=>['NFS','SMB'].map(protocol=>({id:protocol+r.name,protocol,name:r.name,relative:r.relative})))},
 referenceColumns(){return[{title:'프로토콜',dataIndex:'protocol',width:110},{title:'공유 이름',dataIndex:'name'},{title:'볼륨 내 상대 경로',dataIndex:'relative'}]}
 },
 methods:{go(view){this.view=view;this.activeTab=view;this.dialog='';this.$router.push(view==='list'?'/sharedfs':'/sharedfs/design-example')}}
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
