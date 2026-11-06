<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<!-- Design-only Vue3/AntD mockup. No product API calls or resource changes. -->
<template>
 <a-config-provider :locale="locale"><div class="design-app">
  <div class="design-note"><strong>B · #1458 개선 UI 목업</strong><span>설계 예시 · API 미연결 · 수치/자원은 예시</span><a-button size="small" @click="go('list')">목록</a-button><a-button size="small" @click="go('details')">상세</a-button><a-button size="small" @click="go('capacity')">용량·볼륨</a-button><a-select v-model:value="scenario" size="small" style="width:135px"><a-select-option value="partial">부분 관측</a-select-option><a-select-option value="current">전체 FS 관측</a-select-option><a-select-option value="stale">오래된 관측</a-select-option><a-select-option value="unknown">미관측</a-select-option><a-select-option value="transition">연결 전환 중</a-select-option></a-select><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트"/></div>
  <div class="app-shell">
   <aside class="side"><b>ABLESTACK</b><p>대시보드</p><p>컴퓨트　⌄</p><p>　가상머신</p><p>스토리지　⌄</p><div class="selected">공유 파일 시스템</div><p>네트워크</p><p>이미지</p></aside>
   <div class="workspace">
    <header class="topbar"><span>☰　 기본 보기</span><span>AC　admin cloud</span></header>
    <a-card class="breadcrumb-card"><a-row align="middle"><a-col :span="18"><Breadcrumb :resource="view==='list'?{}:{name:'sharedfs-capacity-design'}"><template #end><a-button size="small" shape="round" @click="updated=true"><ReloadOutlined/>업데이트</a-button></template></Breadcrumb></a-col><a-col :span="6" style="text-align:right"><a-button v-if="view!=='list'">작업 <DownOutlined/></a-button></a-col></a-row></a-card>
    <main>
     <template v-if="view==='list'">
      <div class="list-toolbar"><a-space><a-button type="primary"><PlusOutlined/>공유 파일 시스템 생성</a-button><a-select value="all" style="width:135px"><a-select-option value="all">전체</a-select-option></a-select></a-space><a-input-search placeholder="이름 검색" style="width:240px"/></div>
      <a-table :columns="listColumns" :data-source="listRows" row-key="id" size="small" :pagination="{total:3,pageSize:20}" :scroll="{x:1020}">
       <template #bodyCell="{column,record}">
        <a v-if="column.key==='name'" @click="go('capacity')">{{record.name}}</a>
        <a-badge v-else-if="column.key==='state'" status="success" :text="record.state"/>
        <div v-else-if="column.key==='capacity'" class="capacity-cell"><a class="capacity-primary" @click="go('capacity')">{{record.total}} <span class="small">(고유 DATA {{record.count}}개)</span></a><div class="secondary">{{record.usage}}</div><div class="small secondary">{{record.observed}} <a-tag :color="record.color">{{record.status}}</a-tag></div></div>
       </template>
      </a-table>
      <p class="hint">프로비저닝은 현재 연결된 고유 DATA 기준입니다. 파일시스템 관측이 없거나 일부만 지원되면 상태와 범위를 함께 표시합니다.</p>
     </template>
     <ResourceLayout v-else>
      <template #left><a-card class="resource-info"><FileTextOutlined class="resource-icon"/><h3>sharedfs-capacity-design</h3><a-badge status="success" text="사용 가능"/><dl><dt>주 IP</dt><dd>10.10.13.220 (예시)</dd><dt>총 프로비저닝</dt><dd class="capacity-primary">{{total}} · 고유 DATA {{volumes.length}}개</dd><dt>관측 FS 사용량</dt><dd>{{usage}} <span class="small secondary">· {{status}}</span></dd><dt>기본 볼륨 현재 크기</dt><dd>64 GiB</dd><dt>계정</dt><dd>admin</dd><dt>Zone</dt><dd>13-Zone</dd></dl></a-card></template>
      <template #right><a-card class="tab-card"><a-tabs v-model:activeKey="activeTab" tab-position="left">
       <a-tab-pane key="details" tab="상세">
        <a-list size="small" :data-source="detailRows"><template #renderItem="{item}"><a-list-item><div class="detail-row"><strong>{{item[0]}}</strong><span>{{item[1]}}</span></div></a-list-item></template></a-list>
        <p class="hint">상세는 정보만 표시합니다. 합산 근거는 ‘용량·볼륨’ 탭에서 확인합니다.</p>
       </a-tab-pane>
       <a-tab-pane key="capacity" tab="용량·볼륨">
        <div class="toolbar"><a-button @click="updated=true"><ReloadOutlined/>업데이트</a-button><a-button @click="dialog=true"><InfoCircleOutlined/>집계 기준</a-button><span class="toolbar-state secondary">마지막 관측 {{observed}}</span></div>
        <a-descriptions bordered size="small" :column="2" class="summary">
         <a-descriptions-item label="프로비저닝">{{total}}</a-descriptions-item><a-descriptions-item label="연결 DATA">{{volumes.length}}개</a-descriptions-item>
         <a-descriptions-item label="관측 FS">{{usage}}</a-descriptions-item><a-descriptions-item label="상태"><a-tag :color="color">{{status}}</a-tag></a-descriptions-item>
         <a-descriptions-item label="관측 범위">{{coverage}}</a-descriptions-item><a-descriptions-item label="기본 볼륨">64 GiB</a-descriptions-item>
        </a-descriptions>
        <a-alert class="spaced" :type="scenario==='stale'||scenario==='transition'?'warning':'info'" show-icon :message="message" :description="description"/>
        <div class="table-toolbar"><a-input-search v-model:value="search" placeholder="연결된 볼륨 이름 검색" style="width:245px"/><span class="small secondary">ROOT·분리된 보존 볼륨 제외</span></div>
        <a-table :columns="volumeColumns" :data-source="filteredVolumes" row-key="id" size="small" :pagination="false" :scroll="{x:1010}">
         <template #bodyCell="{column,record}">
          <div v-if="column.key==='name'"><a>{{record.name}}</a><div class="small secondary">{{record.hidden?'관리 숨김 DATA · 서비스 권한으로 표시':'서비스 연결 DATA'}}</div></div>
          <div v-else-if="column.key==='usage'"><span>{{scenario==='unknown'||scenario==='transition'?'—':record.usage}}</span><div class="small secondary">{{record.kind==='raw'?'raw block 사용량 미지원':scenario==='unknown'?'관측 없음':scenario==='transition'?'연결 집합 재확인 중':scenario==='stale'?'마지막 관측값':record.fs}}</div></div>
          <div v-else-if="column.key==='refs'">{{record.refs}}<div class="small secondary">{{record.refs==='없음'?'공유 참조가 없어도 연결 DATA는 합산':'동일 볼륨 참조 중복 합산 없음'}}</div></div>
          <a-tag v-else-if="column.key==='mapping'" :color="scenario==='transition'?'orange':'green'">{{scenario==='transition'?'전환 확인 중':'연결 확인'}}</a-tag>
         </template>
        </a-table>
        <p class="hint">같은 파일시스템의 NFS·SMB 참조와 bind 별칭은 한 번만 집계합니다. raw block 미지원·미관측을 0으로 바꾸지 않습니다.</p>
        <span v-if="updated" role="status" class="hint">관측 시각을 포함한 목록을 다시 조회했습니다. (목업 예시)</span>
       </a-tab-pane>
       <a-tab-pane key="directory" tab="공유 디렉터리"/><a-tab-pane key="nfs" tab="NFS"/><a-tab-pane key="smb" tab="SMB"/><a-tab-pane key="iscsi" tab="iSCSI"/><a-tab-pane key="nvmeof" tab="NVMe-oF"/><a-tab-pane key="runtime" tab="런타임"/><a-tab-pane key="network" tab="네트워크"/><a-tab-pane key="operations" tab="작업 내역"/>
      </a-tabs></a-card></template>
     </ResourceLayout>
    </main>
   </div>
  </div>
  <MoldDialog v-if="dialog" :visible="true" title="용량 집계 기준" size="md" @cancel="dialog=false">
   <a-alert type="info" show-icon message="서비스에 연결된 고유 DATA 기준" description="아래 수치와 자원은 설계 예시입니다. 기존 생성·연결·분리·권한 동작을 변경하지 않습니다."/>
   <a-list class="spaced" bordered :data-source="rules"><template #renderItem="{item}"><a-list-item>{{item}}</a-list-item></template></a-list>
   <a-descriptions class="spaced" bordered size="small" :column="1"><a-descriptions-item label="프로비저닝 예시">64 + 32 + 16 + 8 = 120 GiB</a-descriptions-item><a-descriptions-item label="관측 FS 사용 예시">18 + 9 + 0.5 = 27.5 GiB</a-descriptions-item><a-descriptions-item label="raw block 16 GiB">프로비저닝 합계에는 포함 · 파일시스템 사용량에는 미포함</a-descriptions-item><a-descriptions-item label="공유 참조 없는 DATA 8 GiB">현재 VM에 연결돼 있으므로 합산</a-descriptions-item></a-descriptions>
   <p class="hint spaced">같은 파일시스템의 여러 별칭은 사용량을 늘리지 않습니다. 볼륨이 분리되면 그 볼륨의 보존 여부와 관계없이 현재 서비스 합계에서 제외합니다.</p>
  </MoldDialog>
 </div></a-config-provider>
</template>
<script>
import koKR from 'ant-design-vue/es/locale/ko_KR'
import Breadcrumb from '@/components/widgets/Breadcrumb.vue'
import ResourceLayout from '@/layouts/ResourceLayout.vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import {ReloadOutlined,PlusOutlined,DownOutlined,FileTextOutlined,InfoCircleOutlined} from '@ant-design/icons-vue'
const q=new URLSearchParams(location.search)
export default {
 components:{Breadcrumb,ResourceLayout,MoldDialog,ReloadOutlined,PlusOutlined,DownOutlined,FileTextOutlined,InfoCircleOutlined},
 data(){return {locale:koKR,view:q.get('view')||'capacity',activeTab:q.get('view')==='details'?'details':'capacity',scenario:q.get('scenario')||'partial',dark:q.get('theme')!=='light',dialog:q.get('dialog')==='rules',search:'',updated:false,
 listColumns:[{title:'이름',key:'name',dataIndex:'name',width:290},{title:'상태',key:'state',dataIndex:'state',width:100},{title:'스토리지 용량',key:'capacity',width:360},{title:'계정',key:'account',dataIndex:'account',width:100},{title:'Zone',key:'zone',dataIndex:'zone',width:110}],
 volumeColumns:[{title:'볼륨 이름',key:'name',width:230},{title:'프로비저닝',key:'size',dataIndex:'size',width:120},{title:'관측 FS 사용량',key:'usage',width:180},{title:'현재 연결',key:'mapping',width:130},{title:'공유 참조',key:'refs',width:240},{title:'관측 시각',key:'time',dataIndex:'time',width:150}],
 rules:['현재 서비스 VM에 연결된 고유 DATA 볼륨의 크기를 더합니다. ROOT와 분리된 보존 볼륨은 제외합니다.','NFS·SMB가 같은 볼륨을 참조해도 프로비저닝은 한 번만 더합니다.','같은 파일시스템의 bind 별칭과 여러 마운트는 사용량을 중복 합산하지 않습니다.','프로비저닝 크기와 파일시스템 크기·사용량은 서로 다릅니다.','raw block의 쓰인 영역은 파일시스템 사용량으로 추정하지 않습니다. 미지원으로 표시합니다.','미관측은 0이 아닙니다. 오래된 관측은 시각과 상태를 함께 표시합니다.','공유 참조가 없어도 VM에 연결된 숨김 DATA는 합계에 포함합니다.']}},
 watch:{dark:{immediate:true,handler(v){document.body.classList.toggle('dark-mode',v)}},scenario(){this.updated=false}},
 computed:{
  total(){return this.scenario==='current'?'104 GiB':'120 GiB'},
  status(){return {current:'관측 완료',partial:'부분 관측',stale:'오래된 관측',unknown:'미관측',transition:'연결 전환 중'}[this.scenario]},
  color(){return {current:'green',partial:'blue',stale:'orange',unknown:'default',transition:'orange'}[this.scenario]},
  observed(){return this.scenario==='unknown'?'—':this.scenario==='stale'?'예시 13:00:00':'예시 14:30:00'},
  usage(){return this.scenario==='unknown'||this.scenario==='transition'?'—':(this.scenario==='stale'?'마지막 ':'')+'27.5 GiB'},
  coverage(){return this.scenario==='unknown'?'미관측':this.scenario==='transition'?'연결 집합 확인 중':this.scenario==='current'?'고유 FS 3개 · DATA 3/3 관측':'고유 FS 3개 · DATA 3/4 관측 · raw 1개 미지원'},
  message(){return {current:'관측된 파일시스템의 고유 사용량입니다.',partial:'파일시스템 사용량을 지원하는 범위만 표시합니다.',stale:'현재 상태를 확인하지 못한 과거 관측값입니다.',unknown:'파일시스템 사용량이 아직 관측되지 않았습니다.',transition:'연결 상태가 바뀌어 사용량을 다시 확인하고 있습니다.'}[this.scenario]},
  description(){return this.scenario==='partial'?'raw block 1개는 프로비저닝 합계에 포함되며 사용량은 미지원입니다.':this.scenario==='current'?'동일 파일시스템의 bind 별칭과 공동 참조는 한 번만 합산합니다.':'프로비저닝은 현재 Cloud 연결 정보이며, 미관측 사용량을 0으로 표시하지 않습니다.'},
  volumes(){let rows=[{id:'base',name:'기본 DATA',size:'64 GiB',usage:'18 GiB',fs:'XFS · 고유 파일시스템',kind:'fs',refs:'NFS 4 · SMB 2',hidden:false,time:this.observed},{id:'file',name:'추가 파일 DATA',size:'32 GiB',usage:'9 GiB',fs:'EXT4 · 고유 파일시스템',kind:'fs',refs:'NFS 1 · SMB 1',hidden:false,time:this.observed},{id:'raw',name:'iSCSI raw DATA',size:'16 GiB',usage:'미지원',fs:'',kind:'raw',refs:'iSCSI LUN 1',hidden:false,time:this.observed},{id:'unref',name:'보관용 연결 DATA',size:'8 GiB',usage:'512 MiB',fs:'XFS · 고유 파일시스템',kind:'fs',refs:'없음',hidden:true,time:this.observed}];return this.scenario==='current'?rows.filter(x=>x.kind!=='raw'):rows},
  filteredVolumes(){return this.volumes.filter(x=>x.name.includes(this.search))},
  listRows(){return [{id:'design',name:'sharedfs-capacity-design',state:'사용 가능',total:this.total,count:this.volumes.length,usage:'관측 FS 사용 '+this.usage+' · '+this.coverage,observed:this.observed,status:this.status,color:this.color,account:'admin',zone:'13-Zone'},{id:'file-only',name:'sharedfs-file-only',state:'사용 가능',total:'80 GiB',count:2,usage:'관측 FS 사용 12 GiB · DATA 2/2 관측',observed:'예시 14:30:00',status:'관측 완료',color:'green',account:'admin',zone:'13-Zone'},{id:'not-seen',name:'sharedfs-not-observed',state:'사용 가능',total:'32 GiB',count:1,usage:'관측 FS 사용 —',observed:'관측 시각 없음',status:'미관측',color:'default',account:'admin',zone:'13-Zone'}]},
  detailRows(){return [['아이디','설계 예시 · design-capacity'],['이름','sharedfs-capacity-design'],['상태','Ready'],['총 프로비저닝',this.total],['고유 연결 DATA',this.volumes.length+'개'],['관측 FS 사용량',this.usage],['관측 범위',this.coverage],['관측 시각',this.observed],['기본 볼륨 현재 크기','64 GiB'],['프로비저닝 유형','SPARSE'],['주 IP','10.10.13.220'],['계정','admin'],['Zone','13-Zone']]},
 },
 methods:{go(view){this.view=view;if(view!=='list')this.activeTab=view==='details'?'details':'capacity';this.$router.push(view==='list'?'/sharedfs':'/sharedfs/design-capacity')}}
}
</script>
<style>
html,body,#app{margin:0;min-height:100%;font-family:Arial,"Malgun Gothic",sans-serif}
.design-app{min-height:100vh;color:var(--ui-text-primary);background:var(--ui-bg-page)}
.design-note{display:flex;align-items:center;gap:10px;padding:10px 16px;background:var(--ui-bg-elevated);border-bottom:1px solid var(--ui-border);font-size:12px}.design-note>span{flex:1;color:var(--ui-text-secondary)}
.app-shell{display:flex;min-height:calc(100vh - 48px)}.side{width:200px;flex-shrink:0;padding:20px 16px;background:var(--ui-bg-elevated);border-right:1px solid var(--ui-border)}.side>b{font-size:26px}.side>p{margin:28px 8px}.selected{padding:12px;color:var(--ui-link);background:var(--ui-bg-secondary);border-radius:4px}
.workspace{flex:1;min-width:0}.topbar{height:64px;display:flex;align-items:center;justify-content:space-between;padding:0 24px;background:var(--ui-bg-elevated);border-bottom:1px solid var(--ui-border)}
.breadcrumb-card{position:sticky;top:0;z-index:10;border-radius:0}.breadcrumb-card>.ant-card-body{padding:16px 24px}
main{padding:16px 24px}.list-toolbar,.table-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:16px}.table-toolbar{margin-top:16px}
.resource-info h3{overflow-wrap:anywhere;color:var(--ui-text-primary);margin-top:16px}.resource-icon{font-size:36px;color:var(--ui-link)}dl>dt{font-weight:600;margin:16px 0 4px}dl>dd{margin:0;color:var(--ui-text-primary)}
.tab-card>.ant-card-body{padding:16px}.tab-card .ant-tabs-nav{min-width:112px}.tab-card .ant-tabs-content-holder{min-width:0}
.toolbar{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin-bottom:16px}.toolbar-state{font-size:12px;margin-left:8px}.spaced{margin-top:16px}.summary .ant-descriptions-item-label{white-space:nowrap}
.secondary,.hint{color:var(--ui-text-secondary)}.hint{font-size:12px;line-height:1.8;margin:12px 0}.small{font-size:12px;line-height:1.8}.capacity-primary{font-size:14px;font-weight:600;color:var(--ui-text-primary)}a.capacity-primary{color:var(--ui-link)}
.capacity-cell>div{margin-top:4px}.capacity-cell .ant-tag{margin-left:8px}.detail-row{display:grid;grid-template-columns:160px minmax(0,1fr);gap:16px;width:100%;padding:4px 0}.detail-row strong{color:var(--ui-text-secondary)}
code{overflow-wrap:anywhere}
@media(max-width:1000px){.side{display:none}main{padding:16px}.design-note>span{display:none}.detail-row{grid-template-columns:120px 1fr}}
</style>
