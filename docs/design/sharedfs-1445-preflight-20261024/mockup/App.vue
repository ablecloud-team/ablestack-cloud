<template>
  <div class="design1445">
    <header class="banner"><b>B. #1445 개선 설계 목업</b><span>실제 API 미연결 · 수치는 설계 fixture</span><a-switch v-model:checked="dark" checked-children="다크" un-checked-children="라이트" @change="theme" /></header>
    <main><h2>공유 파일 시스템 · 설계용</h2><p>상세는 정보 전용이며 기존 폼의 항목·순서는 유지합니다. 아래 폼은 선택 항목을 발췌했습니다.</p>
      <a-tabs tab-position="left" active-key="nfs"><a-tab-pane key="details" tab="상세"/><a-tab-pane key="nfs" tab="NFS"><div class="toolbar"><a-button type="primary" @click="visible=true">NFS 내보내기 생성</a-button><a-button><ReloadOutlined />업데이트</a-button><a-button>넓게 보기</a-button></div><a-alert message="설계 검토 화면 · 실제 서버와 연결되지 않습니다." type="info" show-icon /></a-tab-pane><a-tab-pane key="smb" tab="SMB"/><a-tab-pane key="iscsi" tab="iSCSI"/><a-tab-pane key="nvme" tab="NVMe-oF"/><a-tab-pane key="ops" tab="작업 내역"/></a-tabs>
    </main>
    <MoldDialog :visible="visible" size="lg" form-actions class="preflight1445" @cancel="visible=false">
      <template #title>NFS 내보내기 수정 <a-tag>설계 목업</a-tag></template>
      <a-alert v-if="running" type="info" show-icon message="설정 적용 중 · 실행 상태 확인" description="같은 작업을 조회하고 있습니다. 화면을 닫아도 서버 작업은 취소되지 않습니다."/>
      <a-alert v-else :type="blocked?'error':'warning'" show-icon :message="blocked?'사전 점검 차단: 메모리 여유 부족':'사전 점검 완료 · 일시 중단 영향 확인 필요'" :description="blocked?'서버 측 재점검 전에는 적용하지 않습니다. 현재 설정과 입력은 보존됩니다.':'현재 적용 엔진은 NFS 엔드포인트를 재시작합니다. 기존 연결이 잠시 끊길 수 있습니다.'"/>
      <a-descriptions bordered size="small" :column="2" class="summary">
        <a-descriptions-item label="점검 시각">방금 · 유효 30초</a-descriptions-item><a-descriptions-item label="설정 revision">2</a-descriptions-item>
        <a-descriptions-item label="사용 가능 메모리">{{blocked?'64 MiB':'3,602 MiB'}}</a-descriptions-item><a-descriptions-item label="제안 여유 기준">약 209 MiB</a-descriptions-item>
        <a-descriptions-item label="journal / staging 여유">2.43 GiB · 쓰기 가능</a-descriptions-item><a-descriptions-item label="부하 / CPU">0.11 / 2 CPU</a-descriptions-item>
        <a-descriptions-item label="백킹 볼륨">SPARSE · XFS · Ready</a-descriptions-item><a-descriptions-item label="다른 writer">없음</a-descriptions-item>
        <a-descriptions-item label="영향 범위">현재 NFS 엔드포인트의 공유 1개</a-descriptions-item><a-descriptions-item label="관측 연결">TCP 연결 1개 · 논리 세션 수와 구분</a-descriptions-item>
      </a-descriptions>
      <template v-if="running">
        <a-descriptions bordered size="small" :column="1" class="summary">
          <a-descriptions-item label="단계">적용 중 · 진행률 미제공</a-descriptions-item>
          <a-descriptions-item label="최근 heartbeat">3초 전 · worker 동작 확인</a-descriptions-item>
          <a-descriptions-item label="관찰 기준">일반 설정 300초 · 시간 초과는 취소가 아닙니다.</a-descriptions-item>
          <a-descriptions-item label="작업 식별자">design-operation · 같은 requestid 유지</a-descriptions-item>
          <a-descriptions-item label="취소">지원하지 않음 · 강제 종료하지 않습니다.</a-descriptions-item>
        </a-descriptions>
      </template>
      <template v-else>
        <a-checkbox v-model:checked="ack" :disabled="blocked" class="ack">현재 영향 범위의 일시 중단을 확인했습니다.</a-checkbox>
        <p class="help">삭제·백킹 교체 등 drain 필수 작업은 이 확인으로 차단을 우회할 수 없습니다. 입력 변경·점검 만료 시 다시 점검합니다.</p>
        <a-form layout="vertical" class="existing-form">
          <a-form-item label="이름"><a-input value="r1444n" readonly /></a-form-item>
          <a-form-item label="내부 백킹 경로"><a-input value="/export/r1444n" readonly /></a-form-item>
          <a-form-item label="기존 변경 항목"><a-checkbox checked>읽기 전용</a-checkbox></a-form-item>
        </a-form>
      </template>
      <MoldDialogActions>
        <a-button @click="visible=false">{{running?'닫기':'취소'}}</a-button>
        <a-button @click="reads++"><ReloadOutlined />{{running?'업데이트':'다시 점검'}}</a-button>
        <a-button v-if="!running" type="primary" :disabled="blocked||!ack" @click="mode='running'">변경 적용</a-button>
      </MoldDialogActions>
    </MoldDialog>
  </div>
</template>
<script>
import MoldDialog from '/root/work/ablestack-cloud/ui/src/components/view/MoldDialog.vue'
import MoldDialogActions from '/root/work/ablestack-cloud/ui/src/components/view/MoldDialogActions.vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
const q = new URLSearchParams(location.search)
export default {components:{MoldDialog,MoldDialogActions,ReloadOutlined},data(){return {dark:q.get('theme')!=='light',mode:q.get('mode')||'ready',ack:false,visible:true,reads:0}},computed:{blocked(){return this.mode==='blocked'},running(){return this.mode==='running'}},mounted(){this.theme()},methods:{theme(){document.body.classList.toggle('dark-mode',this.dark);document.documentElement.classList.toggle('dark-mode',this.dark)}}}
</script>
<style>
body{margin:0;background:var(--ui-bg-surface);color:var(--ui-text-primary);font-size:14px}.banner{padding:16px 24px;display:flex;gap:16px;align-items:center;background:var(--ui-bg-elevated);border-bottom:1px solid var(--ui-border)}.banner span,.help,main p{color:var(--ui-text-secondary)}main{padding:24px}main h2{color:var(--ui-text-primary)}.toolbar{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px}.summary{margin-top:16px}.ack{margin-top:20px}.help{font-size:13px;line-height:1.7;margin-top:12px}.existing-form{margin-top:24px}.existing-form .ant-form-item{margin-bottom:20px}.preflight1445 .ant-input[readonly]{color:var(--ui-text-primary)!important;background:var(--ui-bg-input)!important}.preflight1445 .ant-descriptions-item-label,.preflight1445 .ant-descriptions-item-content{color:var(--ui-text-primary)}.preflight1445 .ant-btn-primary:not(:disabled),.toolbar .ant-btn-primary{background:var(--ui-action-primary-bg)!important;border-color:var(--ui-action-primary-bg)!important;color:var(--ui-action-primary-text)!important}.preflight1445 code{overflow-wrap:anywhere}
</style>
