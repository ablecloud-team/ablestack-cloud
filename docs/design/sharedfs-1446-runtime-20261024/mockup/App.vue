<template>
  <div class="design-runtime">
    <div class="design-notice"><b>B. #1446 개선 설계 목업</b><span>설계 fixture · 실제 API/서명 번들 미연결</span><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트" @change="theme"/></div>
    <a-layout class="app-shell">
      <a-layout-sider width="208" class="sider"><div class="brand">ABLESTACK</div><a-menu mode="inline" :selected-keys="['sharedfs']">
        <a-menu-item key="dashboard"><DashboardOutlined/>대시보드</a-menu-item>
        <a-sub-menu key="compute"><template #title><CloudServerOutlined/> 컴퓨트</template><a-menu-item key="vm">가상머신</a-menu-item></a-sub-menu>
        <a-sub-menu key="storage"><template #title><DatabaseOutlined/> 스토리지</template><a-menu-item key="volume">볼륨</a-menu-item><a-menu-item key="sharedfs">공유 파일 시스템</a-menu-item></a-sub-menu>
      </a-menu></a-layout-sider>
      <a-layout><a-layout-header class="topbar"><span>컴퓨트 · 스토리지 관리</span><span>admin</span></a-layout-header>
      <a-layout-content class="page">
        <a-breadcrumb><a-breadcrumb-item>홈</a-breadcrumb-item><a-breadcrumb-item>공유 파일 시스템</a-breadcrumb-item><a-breadcrumb-item>epic1438-sharedfs</a-breadcrumb-item></a-breadcrumb>
        <ResourceLayout><template #left>
        <aside class="resource-card">
          <div><FileTextOutlined class="resource-icon"/><strong>epic1438-sharedfs</strong></div>
          <a-tag>Storage Service VM</a-tag><a-tag>SPARSE</a-tag>
          <div class="info-line"><label>상태</label><span><a-badge status="success"/>사용 가능</span></div>
          <div class="info-line"><label>아이디</label><span class="mono">bd9bf00f-579c-4a22-a7d7-40eed014872c</span></div>
          <div class="info-line"><label>IP</label><span>10.10.13.250</span></div>
          <div class="info-line"><label>파일 시스템</label><span>XFS · 20 GiB</span></div>
          <div class="info-line"><label>활성 서비스</label><span>NFS · SMB<br/>iSCSI · NVMe-oF</span></div>
          <div class="info-line"><label>VM</label><span>Storage Service VM · KVM</span></div>
        </aside>
        </template><template #right><a-card class="resource-content-card" :bordered="true">
        <a-tabs tab-position="left" active-key="runtime" class="resource-tabs">
          <a-tab-pane key="details" tab="상세"/><a-tab-pane key="nfs" tab="NFS"/><a-tab-pane key="smb" tab="SMB"/><a-tab-pane key="iscsi" tab="iSCSI"/><a-tab-pane key="nvme" tab="NVMe-oF"/>
          <a-tab-pane key="runtime" tab="런타임">
            <div class="toolbar">
              <a-button type="primary" :disabled="mode==='unknown'||mode==='current'" @click="dialog=true"><CloudUploadOutlined/>업그레이드 준비</a-button>
              <a-button @click="reads++"><ReloadOutlined/>업데이트</a-button>
              <a-button :disabled="mode==='current'||mode==='unknown'" @click="rollback=true"><RollbackOutlined/>이전 런타임 복구</a-button>
            </div>
            <a-alert v-if="mode==='current'" type="warning" show-icon message="실행 파일의 기준 해시와 현재 해시가 다릅니다."
              description="지원 여부와 기준을 확인하고 등록된 서명 번들을 선택해야 합니다. 기존 파일 공유 서비스는 계속 운영됩니다."/>
            <a-alert v-else-if="mode==='unknown'" type="warning" show-icon message="응답이 유실되어 같은 작업의 상태를 확인 중입니다."
              description="새 적용·원복을 전송하지 않습니다. 업데이트로 현재 작업을 다시 확인합니다."/>
            <a-alert v-else type="info" show-icon message="관리 코드만 업데이트합니다."
              description="OS·커널·공용 라이브러리와 데이터 볼륨은 갱신 범위에 포함하지 않습니다."/>
            <a-descriptions bordered size="small" :column="1" class="summary">
              <a-descriptions-item label="템플릿">Europa-SystemVM-1443-observation</a-descriptions-item><a-descriptions-item label="지원 상태"><a-tag :color="mode==='current'?'orange':'blue'">{{mode==='current'?'기준 확인 필요':mode==='unknown'?'확인 대기':'검증 지원 (설계 예시)'}}</a-tag></a-descriptions-item>
              <a-descriptions-item :label="mode==='unknown'?'최근 확정 런타임':'현재 런타임'"><span class="mono">{{mode==='current'?'bootstrap-f858358b0e1b8eba':'확정 기준 버전 (설계 예시)'}}</span></a-descriptions-item><a-descriptions-item label="이전 런타임">{{mode==='current'?'-':'이전 확정 버전 (예시)'}}</a-descriptions-item>
              <a-descriptions-item label="실행 파일 검증"><a-tag :color="mode==='current'?'orange':'green'">{{mode==='current'?'기록과 불일치':mode==='unknown'?'최근 값 · 현재 확인 대기':'일치 (설계 예시)'}}</a-tag></a-descriptions-item><a-descriptions-item label="코드 범위">{{mode==='current'?'진입점 3개 · 보조 모듈 별도':'진입점 3개 + 보조 모듈 6개'}}</a-descriptions-item>
            </a-descriptions>
            <section v-if="mode==='unknown'" class="operation">
              <a-descriptions bordered size="small" :column="2"><a-descriptions-item label="작업">runtime-design-001</a-descriptions-item><a-descriptions-item label="확인 상태">연결 복구 대기</a-descriptions-item><a-descriptions-item label="마지막 단계">활성화</a-descriptions-item><a-descriptions-item label="설정 변경 보호">유지 중</a-descriptions-item></a-descriptions>
              <a-steps size="small" :current="3" class="steps"><a-step title="전송"/><a-step title="검증"/><a-step title="준비"/><a-step title="활성화"/><a-step title="서비스 확인"/><a-step title="확정"/></a-steps>
            </section>
            <a-collapse :bordered="false" class="file-details"><a-collapse-panel key="files" header="실제 적용 파일 · 해시">
              <a-table size="small" :data-source="files" :columns="fileColumns" :pagination="false" row-key="path">
                <template #bodyCell="{column,record}"><template v-if="column.key==='hash'"><code>{{record.hash.slice(0,16)}}…</code></template><template v-else-if="column.key==='state'"><a-tag :color="mode==='unknown'||(mode==='current'&&record.path.startsWith('lib/'))?'default':record.drift?'orange':'green'">{{mode==='unknown'?'현재 확인 대기':mode==='current'&&record.path.startsWith('lib/')?'기준 미관리':record.drift?'기준과 불일치':'일치'}}</a-tag></template></template>
              </a-table>
            </a-collapse-panel></a-collapse>
            <div class="section-caption">작업 내역</div>
            <a-table size="small" :columns="historyColumns" :scroll="{x:700}" :data-source="history" :pagination="false" row-key="id">
              <template #bodyCell="{column,record}"><template v-if="column.key==='state'"><a-tag :color="mode==='unknown'?'orange':'green'">{{record.state}}</a-tag></template><template v-else-if="column.key==='action'"><a-button type="link" @click="diagnostic=true">상세</a-button></template></template>
              <template #emptyText><a-empty description="아직 런타임 갱신 작업이 없습니다."/></template>
            </a-table>
          </a-tab-pane><a-tab-pane key="ops" tab="작업 내역"/>
        </a-tabs>
        </a-card></template></ResourceLayout>
      </a-layout-content></a-layout>
    </a-layout>
    <MoldDialog :visible="dialog" size="lg" form-actions class="runtime-design-dialog" @cancel="dialog=false">
      <template #title>Storage Service 런타임 업그레이드</template>
      <a-alert type="info" show-icon message="다운로드·스테이징·검증 후 적용을 선택합니다."
        description="이 화면은 설계 예시입니다. 실제 번들을 등록하거나 적용하지 않습니다."/>
      <a-form layout="vertical" class="target-form"><a-form-item label="대상 번들" required>
        <a-select value="example"><a-select-option value="example">Europa-runtime-e88ef923fc9 · 설계 예시</a-select-option></a-select>
      </a-form-item></a-form>
      <a-descriptions bordered size="small" :column="2">
        <a-descriptions-item label="현재 버전">bootstrap-f858358b0e1b8eba</a-descriptions-item><a-descriptions-item label="대상 버전">Europa-runtime-e88ef923fc9 (예시)</a-descriptions-item>
        <a-descriptions-item label="서명 출처">승인된 서명키 (설계 예시)</a-descriptions-item><a-descriptions-item label="서비스 영향">프로토콜 재시작 없음 · 검증 필요</a-descriptions-item>
      </a-descriptions>
      <a-steps size="small" :current="prepared?2:0" class="steps"><a-step title="전송"/><a-step title="서명·호환성 검증"/><a-step title="적용 준비"/><a-step title="적용·확인"/></a-steps>
      <a-alert v-if="!prepared" type="warning" show-icon message="현재 기준 파일 확인이 필요합니다." description="현재 VM의 실제 파일과 신뢰할 수 있는 기준을 대조한 후 준비합니다."/>
      <a-alert v-else type="success" show-icon message="적용 전 검증 완료 (설계 예시)" description="출처·해시·경로·권한·호환성과 원복 대상을 확인했습니다."/>
      <div class="section-caption">검증 결과 · 설계 예시</div>
      <a-table size="small" :columns="checkColumns" :data-source="checks" :pagination="false" row-key="name">
        <template #bodyCell="{column,record}"><template v-if="column.key==='state'"><a-tag :color="prepared?'green':'default'">{{prepared?'통과':'확인 예정'}}</a-tag></template></template>
      </a-table>
      <div class="section-caption">적용 파일</div>
      <a-table size="small" :columns="fileColumns.filter(x=>x.key!=='state')" :data-source="files" :pagination="false" row-key="path">
        <template #bodyCell="{column,record}"><template v-if="column.key==='hash'"><code>{{record.hash.slice(0,20)}}…</code></template></template>
      </a-table>
      <a-checkbox v-if="prepared" v-model:checked="ack" class="ack">현재 버전과 검증 결과를 확인했으며 해당 코드 적용을 진행합니다.</a-checkbox>
      <MoldDialogActions><a-button @click="dialog=false">취소</a-button><a-button v-if="prepared" @click="reads++"><ReloadOutlined/>업데이트</a-button><a-button type="primary" :disabled="prepared&&!ack" @click="prepare">{{prepared?'적용':'준비'}}</a-button></MoldDialogActions>
    </MoldDialog>
    <MoldDialog :visible="diagnostic" size="lg" @cancel="diagnostic=false"><template #title>런타임 작업 상세</template>
      <a-alert :type="mode==='unknown'?'warning':'success'" show-icon :message="mode==='unknown'?'작업 관찰 대기 · 같은 식별자 유지':'확정 완료 (설계 예시)'"/>
      <a-descriptions bordered size="small" :column="1" class="summary"><a-descriptions-item label="작업 식별자">runtime-design-001</a-descriptions-item><a-descriptions-item label="현재 / 이전 버전">설계 예시 버전 · 실제 조회 아님</a-descriptions-item><a-descriptions-item label="최초 실패">{{mode==='unknown'?'응답 유실 · 실행 결과 미확정':'없음'}}</a-descriptions-item><a-descriptions-item label="원복 판정">{{mode==='unknown'?'현재 상태 확인 전 새 원복 요청 금지':'필요 없음'}}</a-descriptions-item></a-descriptions>
    </MoldDialog>
    <MoldDialog :visible="rollback" size="md" @cancel="rollback=false"><template #title>이전 런타임 복구</template><a-alert type="warning" show-icon message="이전 확정 코드로 복구합니다." description="현재 공유 설정과 데이터는 유지하며 호환성 검증을 통과한 대상만 선택합니다."/><a-descriptions bordered size="small" :column="1" class="summary"><a-descriptions-item label="복구 대상">이전 확정 버전 (설계 예시)</a-descriptions-item><a-descriptions-item label="공유 설정">현재 설정 유지 · 실패 자동 원복과 구분</a-descriptions-item></a-descriptions><MoldDialogActions><a-button @click="rollback=false">취소</a-button><a-button type="primary" @click="rollback=false">복구</a-button></MoldDialogActions></MoldDialog>
  </div>
</template>
<script>
import ResourceLayout from '/root/work/ablestack-cloud/ui/src/layouts/ResourceLayout.vue'
import MoldDialog from '/root/work/ablestack-cloud/ui/src/components/view/MoldDialog.vue'
import MoldDialogActions from '/root/work/ablestack-cloud/ui/src/components/view/MoldDialogActions.vue'
import { ReloadOutlined, RollbackOutlined, CloudUploadOutlined, CloudServerOutlined, DatabaseOutlined, DashboardOutlined, FileTextOutlined } from '@ant-design/icons-vue'
import current from './current.json'
const q=new URLSearchParams(location.search)
export default {
 components:{ResourceLayout,MoldDialog,MoldDialogActions,ReloadOutlined,RollbackOutlined,CloudUploadOutlined,CloudServerOutlined,DatabaseOutlined,DashboardOutlined,FileTextOutlined},
 data(){return{dark:q.get('theme')!=='light',mode:q.get('mode')||'current',dialog:q.get('view')==='prepare',prepared:q.get('state')==='prepared',ack:false,diagnostic:false,rollback:false,reads:0,
 files:current.files.map((f,i)=>({...f,drift:i===0&&(!q.has('mode')||q.get('mode')==='current')})),
 fileColumns:[{title:'파일',dataIndex:'path',key:'path'},{title:'실제 SHA-256',key:'hash',width:210},{title:'판정',key:'state',width:125}],
 checkColumns:[{title:'검증 항목',dataIndex:'name',key:'name'},{title:'판정',key:'state',width:130}],
 checks:['서명·신뢰키','압축 파일·manifest 해시','허용 경로·소유자·권한','관리 서버·agent·updater 호환성','ABI·desired schema','기존 실행 코드·이전 버전 무결성','설정/준비 작업과의 충돌','네 프로토콜 검증 계획'].map(name=>({name}))}},
 computed:{historyColumns(){return[{title:'시작 시각',dataIndex:'started',key:'started',width:165},{title:'대상 버전',dataIndex:'version',key:'version',width:220,ellipsis:true},{title:'단계',dataIndex:'phase',key:'phase',width:120,ellipsis:true},{title:'상태',dataIndex:'state',key:'state',width:120},{title:'작업',key:'action',width:75}]},history(){return this.mode==='current'?[]:[{id:'runtime-design-001',started:'설계용 시각',version:'Europa-runtime-e88ef923fc9',phase:this.mode==='unknown'?'활성화 · 관찰 대기':'확정',state:this.mode==='unknown'?'확인 필요':'완료 (예시)'}]}},
 mounted(){this.theme()},methods:{theme(){document.body.classList.toggle('dark-mode',this.dark);document.documentElement.classList.toggle('dark-mode',this.dark)},prepare(){if(!this.prepared){this.prepared=true}else if(this.ack){this.dialog=false;this.mode='unknown'}}}
}
</script>
<style>
body{margin:0;background:var(--ui-bg-surface);color:var(--ui-text-primary);font-size:14px}.design-notice{padding:10px 20px;display:flex;align-items:center;gap:18px;border-bottom:1px solid var(--ui-border);background:var(--ui-bg-elevated)}.design-notice span{color:var(--ui-text-secondary);flex:1}.app-shell{min-height:calc(100vh - 45px)}.sider,.topbar{background:var(--ui-bg-elevated)!important;border-right:1px solid var(--ui-border)}.brand{height:64px;display:flex;align-items:center;padding:0 24px;font-size:23px;letter-spacing:1px;color:var(--ui-text-primary);font-weight:700}.sider .ant-menu{background:transparent!important;color:var(--ui-text-primary)}.topbar{height:54px!important;line-height:54px!important;display:flex;justify-content:space-between;padding:0 24px!important;color:var(--ui-text-secondary);border-bottom:1px solid var(--ui-border)}.page{padding:20px 24px;background:var(--ui-bg-surface)}.resource-card{margin-top:0;margin-bottom:0;padding:20px 20px;background:var(--ui-bg-elevated);border:1px solid var(--ui-border);border-radius:4px}.resource-card>div:first-child{display:flex;gap:12px;align-items:center;margin-bottom:14px}.resource-card strong{font-size:19px;font-weight:600;color:var(--ui-text-primary)}.resource-icon{font-size:23px;color:var(--ui-action-primary-bg)}.toolbar{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:16px}.summary{margin-top:20px}.operation{margin-top:24px}.steps{margin:24px 0}.section-caption{margin-top:24px;margin-bottom:12px;font-weight:600;color:var(--ui-text-primary)}.file-details{margin-top:20px}.target-form{margin-top:20px}.ack{margin-top:24px;margin-bottom:8px}.mono,code{font-family:monospace;overflow-wrap:anywhere}.design-runtime .ant-descriptions-item-label,.design-runtime .ant-descriptions-item-content,.runtime-design-dialog .ant-descriptions-item-label,.runtime-design-dialog .ant-descriptions-item-content{color:var(--ui-text-primary)}.design-runtime .ant-breadcrumb,.design-runtime .ant-breadcrumb a,.design-runtime .ant-breadcrumb>span:last-child{color:var(--ui-text-secondary)}.toolbar .ant-btn-primary:not(:disabled),.runtime-design-dialog .ant-btn-primary:not(:disabled){background:var(--ui-action-primary-bg)!important;border-color:var(--ui-action-primary-bg)!important;color:var(--ui-action-primary-text)!important}
body.dark-mode .runtime-design-dialog .ant-table,body.dark-mode .runtime-design-dialog .ant-table-thead>tr>th,body.dark-mode .runtime-design-dialog .ant-table-tbody>tr>td{background:var(--ui-bg-elevated)!important;color:var(--ui-text-primary)!important;border-color:var(--ui-border)!important}body.dark-mode .runtime-design-dialog .ant-table-tbody>tr:hover>td{background:var(--ui-bg-surface)!important}body.dark-mode .runtime-design-dialog .ant-empty-description{color:var(--ui-text-secondary)}
.page>.ant-breadcrumb{margin-bottom:20px}.info-line{margin-top:24px;display:flex;flex-direction:column;gap:8px}.info-line label{font-weight:600;color:var(--ui-text-secondary)}.info-line span{overflow-wrap:anywhere}.resource-card strong{overflow-wrap:anywhere}.resource-content-card{background:var(--ui-bg-elevated);border-color:var(--ui-border)}
</style>
