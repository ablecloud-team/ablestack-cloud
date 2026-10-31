<template>
<div class="mock-root">
 <div class="design-banner"><strong>B. #1448 네트워크 개선 설계</strong><span>설계 목업 · 실제 API 미연결 · 아래 값은 예시</span><a-select v-model:value="mode" size="small" style="width:230px"><a-select-option value="static">STATIC · 게이트웨이</a-select-option><a-select-option value="nogateway">STATIC · 게이트웨이 없음</a-select-option><a-select-option value="dhcp">DHCP</a-select-option><a-select-option value="failure">복원 실패 진단</a-select-option></a-select><a-switch :checked="dark" @change="dark=!dark" checked-children="다크" un-checked-children="라이트"/></div>
 <div class="shell"><aside class="nav"><b>ABLESTACK</b><p>◷ 대시보드</p><p>☁ 컴퓨트</p><p>▣ 스토리지</p><div class="selected">공유 파일 시스템</div><p>⚒ 도구</p></aside>
 <section class="workspace"><header>관리자 콘솔<span>AC admin cloud</span></header><main>
 <a-breadcrumb><a-breadcrumb-item>스토리지</a-breadcrumb-item><a-breadcrumb-item>공유 파일 시스템</a-breadcrumb-item><a-breadcrumb-item>sharedfs-network-example</a-breadcrumb-item></a-breadcrumb>
 <ResourceLayout><template #left><a-card class="info-card"><FileTextOutlined style="font-size:36px;color:#1890ff"/><h3>sharedfs-network-example</h3><a-tag color="green">사용 가능 · 예시</a-tag><dl><dt>주 IP</dt><dd>{{ ip }}</dd><dt>네트워크</dt><dd>L2-Network</dd><dt>VM</dt><dd class="break">동일 VM 유지 · 재배포 없음</dd><dt>디스크</dt><dd>ROOT / DATA · SPARSE</dd><dt>네트워크 모드</dt><dd>{{ mode==='dhcp'?'DHCP':'STATIC' }}</dd></dl></a-card></template>
 <template #right><a-card class="content-card"><a-tabs v-model:active-key="tab" tab-position="left" :animated="false">
 <a-tab-pane key="details" tab="상세"><a-descriptions bordered :column="1" size="small"><a-descriptions-item label="이름">sharedfs-network-example</a-descriptions-item><a-descriptions-item label="상태">사용 가능 · 예시</a-descriptions-item><a-descriptions-item label="네트워크 모드">{{mode==='dhcp'?'DHCP':'STATIC'}}</a-descriptions-item></a-descriptions></a-tab-pane>
 <a-tab-pane v-for="p in ['NFS','SMB','iSCSI','NVMe-oF','런타임']" :key="p" :tab="p"><a-alert message="이 이슈에서 프로토콜 탭의 기능과 배치는 변경하지 않습니다." type="info"/></a-tab-pane>
 <a-tab-pane key="network" tab="네트워크">
 <a-space class="toolbar" :size="12" wrap><a-button type="primary" @click="dialog='restart'"><ReloadOutlined/>재시작</a-button><a-button @click="reads++"><ReloadOutlined/>업데이트</a-button></a-space>
 <a-alert :type="mode==='failure'?'error':'success'" show-icon :message="mode==='failure'?'추가 주소 확인 실패 · 프로토콜 재적용 보류':'설정과 실제 네트워크가 일치합니다 · 설계 예시'" :description="mode==='failure'?'NETWORK_ALIASES 단계 · 마지막 정상 구성 유지 · 성공으로 표시하지 않습니다.':mode==='nogateway'?'게이트웨이 미지정은 정상 구성입니다. 기본 경로를 임의로 만들지 않습니다.':mode==='dhcp'?'DHCP 임대 설정을 사용합니다. 고정 IP 설정을 덮어쓰거나 임대를 해제하지 않습니다.':'고정 IP의 소유 NIC만 관리합니다. 추가 IP와 다른 NIC의 경로를 유지합니다.'"/>
 <a-table :columns="policyColumns" :data-source="policyRows" row-key="name" :pagination="false" size="small">
 <template #bodyCell="{column,record}"><a-tag v-if="column.key==='status'" :color="record.status==='실패'?'red':'green'">{{record.status}}</a-tag></template>
 </a-table>
 <div class="observation">관측: 2026-10-25 15:30:00 KST · 게스트 직접 관측 · 예시<br/>소유 NIC: ens8 · MAC 02:0d:00:00:01:52 · 이름·MAC 변경 없음</div>
 <a-divider orientation="left">추가 IP 및 프로토콜 연결</a-divider>
 <a-table :columns="aliasColumns" :data-source="aliasRows" row-key="protocol" size="small" :pagination="false" :scroll="{x:500}"/>
 <a-alert type="info" show-icon message="같은 IP의 프로토콜별 포트와 공유 구성은 별도로 유지합니다." description="추가 IP 예시는 목업 데이터입니다. 현재 실행 VM에 추가한 주소가 아닙니다."/>
 <a-divider orientation="left">Cloud NIC 정보</a-divider>
 <a-table :columns="nicColumns" :data-source="[{name:'L2-Network · 기본',deviceid:0,ip,mac:'02:0d:00:00:01:52',state:'활성화'}]" row-key="mac" size="small" :pagination="false" :scroll="{x:650}"><template #bodyCell="{column,record}"><template v-if="column.key==='address'">{{record.ip}}<br/><small>{{record.mac}}</small></template><template v-else-if="column.key==='actions'"><a-button size="small">작업 ▾</a-button></template></template></a-table>
 <div class="observation">기존 NIC 작업 유지 · VM 표준 컬럼·행 배치 적용 · Cloud 메타데이터와 게스트 관측 구분<br/>미관측·오래된 관측은 UNKNOWN과 관측 시각을 표시합니다.</div>
 </a-tab-pane></a-tabs></a-card></template></ResourceLayout>
 </main></section></div>
 <MoldDialog v-if="dialog" :visible="true" :title="dialog==='restart'?'공유 파일 시스템 재시작':'공유 파일 시스템 생성 · 네트워크 설정'" size="md" form-actions @cancel="dialog=''">
 <template v-if="dialog==='restart'">
 <a-alert type="warning" show-icon message="재시작 중 연결이 잠시 끊어집니다." description="기존 재시작 작업으로 같은 VM의 네트워크 정책을 다시 확인합니다. VM을 새로 배포하거나 디스크를 초기화하지 않습니다."/>
 <a-descriptions bordered :column="1" size="small"><a-descriptions-item label="대상">sharedfs-network-example</a-descriptions-item><a-descriptions-item label="네트워크 모드">{{mode==='dhcp'?'DHCP':'STATIC'}}</a-descriptions-item><a-descriptions-item label="주소">{{ip}}{{mode==='dhcp'?' · DHCP 임대':'/16'}}</a-descriptions-item><a-descriptions-item label="게이트웨이">{{gateway}}</a-descriptions-item><a-descriptions-item label="NIC / MAC">동일 NIC 유지 · 02:0d:00:00:01:52</a-descriptions-item><a-descriptions-item label="DATA / 공유">연결과 기존 구성 유지 · 재포맷 없음</a-descriptions-item></a-descriptions>
 <a-alert type="info" show-icon message="재배포 없이 재시작" description="네트워크가 준비된 뒤 추가 IP와 기존 프로토콜을 순서대로 확인합니다. 실패 단계와 원인은 네트워크 탭에 표시합니다."/>
 <a-checkbox v-model:checked="ack">일시적인 접속 중단을 확인했습니다.</a-checkbox>
 </template>
 <template v-else>
 <a-alert type="info" show-icon message="기존 생성 폼의 네트워크 항목" description="전체 생성 대화상자를 재설계하지 않고 주소와 게이트웨이의 교차 검증만 추가합니다."/>
 <a-form layout="vertical"><a-form-item label="네트워크 모드"><a-radio-group v-model:value="createMode"><a-radio value="STATIC">고정 IP (L2)</a-radio><a-radio value="DHCP">DHCP</a-radio></a-radio-group></a-form-item>
 <template v-if="createMode==='STATIC'"><a-form-item label="IP / 프리픽스" required><a-input value="10.10.13.250/16"/></a-form-item><a-form-item label="게이트웨이 · 선택" :validate-status="invalidGateway?'error':'success'" :help="invalidGateway?'게이트웨이는 자신의 IP와 같을 수 없습니다. 같은 서브넷의 유효한 주소를 입력하세요.':'비워 두면 기본 게이트웨이 없이 생성합니다.'"><a-input v-model:value="gatewayInput" placeholder="선택 사항"/></a-form-item><a-form-item label="DNS 1 · 선택"><a-input value="8.8.8.8"/></a-form-item><a-form-item label="DNS 2 · 선택"><a-input placeholder="선택 사항"/></a-form-item></template>
 <a-alert v-else type="info" message="기존 DHCP / UserData 지원 조건을 유지합니다."/>
 </a-form>
 </template>
 <MoldDialogActions><a-button @click="dialog=''">취소</a-button><a-button type="primary" :disabled="dialog==='restart'?!ack:invalidGateway" @click="dialog=''">확인</a-button></MoldDialogActions>
 </MoldDialog>
</div>
</template>
<script>
import ResourceLayout from '@/layouts/ResourceLayout.vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import MoldDialogActions from '@/components/view/MoldDialogActions.vue'
import {ReloadOutlined,FileTextOutlined} from '@ant-design/icons-vue'
export default{components:{ResourceLayout,MoldDialog,MoldDialogActions,ReloadOutlined,FileTextOutlined},
data(){let q=new URLSearchParams(location.search);return{dark:q.get('theme')!=='light',mode:q.get('mode')||'static',tab:'network',dialog:q.get('dialog')||'',ack:false,reads:0,createMode:'STATIC',gatewayInput:'10.10.13.250'}},
watch:{dark:{immediate:true,handler(v){document.body.classList.toggle('dark-mode',v)}}},
computed:{
ip(){return this.mode==='dhcp'?'10.10.13.249':'10.10.13.250'},gateway(){return this.mode==='nogateway'?'미지정 · 의도한 없음':'10.10.13.1'},
invalidGateway(){return this.createMode==='STATIC'&&this.gatewayInput==='10.10.13.250'},
policyColumns(){return[{title:'항목',dataIndex:'name',width:105},{title:'설정',dataIndex:'desired'},{title:'실제 관측',dataIndex:'actual'},{title:'판정',key:'status',width:70}]},
policyRows(){return[{name:'모드',desired:this.mode==='dhcp'?'DHCP':'STATIC',actual:this.mode==='dhcp'?'DHCP 임대':'고정 정책',status:'일치'},{name:'주 IP / CIDR',desired:this.mode==='dhcp'?'자동 할당':'10.10.13.250/16',actual:this.ip+'/16',status:'일치'},{name:'게이트웨이',desired:this.gateway,actual:this.gateway,status:'일치'},{name:'DNS',desired:this.mode==='dhcp'?'임대 제공':'8.8.8.8',actual:'8.8.8.8',status:'일치'},{name:'추가 IP',desired:'10.10.13.251/16',actual:this.mode==='failure'?'미확인':'10.10.13.251/16',status:this.mode==='failure'?'실패':'일치'}]},
aliasColumns(){return[{title:'IP',dataIndex:'ip',width:135},{title:'프로토콜',dataIndex:'protocol',width:100},{title:'포트',dataIndex:'port',width:70},{title:'관측',dataIndex:'status',width:130}]},
aliasRows(){return['NFS','SMB','iSCSI','NVMe-oF'].map((protocol,i)=>({protocol,ip:'10.10.13.251',port:[2049,445,3260,4420][i],status:this.mode==='failure'?'재적용 보류':'준비됨 · 예시'}))},
nicColumns(){return[{title:'네트워크',dataIndex:'name',width:150},{title:'장치 ID',dataIndex:'deviceid',width:80},{title:'IP / MAC 주소',key:'address',width:180},{title:'상태',dataIndex:'state',width:100},{title:'작업',key:'actions',width:100}]}
}}
</script>
<style>
html,body,#app{margin:0;min-height:100%;font-family:Arial,"Malgun Gothic",sans-serif}.mock-root{background:var(--ui-bg-page);color:var(--ui-text-primary);min-height:100vh}.design-banner{display:flex;align-items:center;gap:12px;padding:10px 16px;border-bottom:1px solid var(--ui-border);font-size:12px;background:var(--ui-bg-elevated)}.design-banner>span{flex:1}.shell{display:flex;min-height:calc(100vh - 48px)}.nav{width:190px;flex-shrink:0;border-right:1px solid var(--ui-border);padding:24px 16px;background:var(--ui-bg-elevated)}.nav b{font-size:24px}.nav p{margin:24px 8px}.selected{color:#1890ff;padding:12px 8px;border:1px solid var(--ui-border)}.workspace{min-width:0;flex:1}.workspace>header{height:52px;display:flex;align-items:center;justify-content:space-between;padding:0 24px;border-bottom:1px solid var(--ui-border)}main{padding:20px;min-width:0}.ant-breadcrumb{margin-bottom:20px}.mock-root .ant-breadcrumb-link,.mock-root .ant-breadcrumb-separator{color:var(--ui-text-secondary,var(--ui-text-primary))}.info-card h3{margin-top:16px;color:var(--ui-text-primary);overflow-wrap:anywhere}.info-card dt{margin:20px 0 8px;font-weight:600}.info-card dd{margin:0}.break{overflow-wrap:anywhere}.content-card .ant-card-body{padding:20px}.content-card .ant-tabs-tabpane{padding-left:20px!important;min-width:0}.content-card .ant-tabs-nav{min-width:92px}.toolbar{display:flex!important;justify-content:flex-start;margin-bottom:16px!important;gap:12px!important}.toolbar .ant-space-item{margin-right:0!important}.mock-root .ant-alert,.mock-root .ant-descriptions,.mold-dialog .ant-alert,.mold-dialog .ant-descriptions{margin-bottom:16px}.observation{font-size:12px;line-height:1.8;margin:16px 0;color:var(--ui-text-secondary)}.mock-root .ant-table{margin-bottom:16px}
body.dark-mode .mock-root .ant-card,body.dark-mode .mock-root .ant-table,body.dark-mode .mock-root .ant-table-thead>tr>th,body.dark-mode .mock-root .ant-table-tbody>tr>td{background:var(--ui-bg-page);color:var(--ui-text-primary);border-color:var(--ui-border)}body.dark-mode .mock-root .ant-descriptions-item-label,body.dark-mode .mold-dialog .ant-descriptions-item-label{background:var(--ui-bg-page);color:var(--ui-text-primary)}body.dark-mode .mock-root .ant-descriptions-item-content,body.dark-mode .mold-dialog .ant-descriptions-item-content{background:var(--ui-bg-page);color:var(--ui-text-primary)}body.dark-mode .mock-root .ant-card,body.dark-mode .mock-root .ant-descriptions-view,body.dark-mode .mock-root .ant-descriptions-row>th,body.dark-mode .mock-root .ant-descriptions-row>td{border-color:var(--ui-border)}body:not(.dark-mode) .design-banner,body:not(.dark-mode) .nav{background:white}
body.dark-mode .mock-root .ant-divider-inner-text{color:var(--ui-text-primary)}body.dark-mode .mock-root .ant-divider:before,body.dark-mode .mock-root .ant-divider:after{border-color:var(--ui-border)}
</style>