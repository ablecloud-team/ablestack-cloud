<!-- Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 See the NOTICE file for additional information. Licensed under the Apache License, Version 2.0.
 You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . -->
<template>
  <div class="runtime-upgrade">
    <a-space class="runtime-toolbar" wrap>
      <a-button type="primary" :disabled="loading || submitting || !capability.available || running" @click="openPreparation">
        <CloudUploadOutlined />{{ canObserve ? '기존 작업 계속' : '업그레이드 준비' }}
      </a-button>
      <a-button :loading="loading" :disabled="submitting" @click="fetchData"><ReloadOutlined />업데이트</a-button>
      <a-button :disabled="!canRollback" @click="rollbackDialog = true"><RollbackOutlined />이전 런타임 복구</a-button>
    </a-space>
    <a-alert
type="info"
show-icon
message="Storage Service 관리 코드만 갱신합니다."
      description="OS·커널·공용 라이브러리와 DATA 볼륨은 갱신 대상이 아닙니다. 닫기 또는 접속 유실은 실행 취소가 아닙니다." />
    <a-alert v-if="readError" type="error" show-icon :message="readError" />
    <a-alert
v-if="!capability.available && !loading"
type="warning"
show-icon
      message="현재 VM의 런타임을 조회할 수 없습니다."
:description="capability.details || 'VM 실행 상태와 QGA 연결을 확인한 뒤 업데이트하십시오.'" />
    <a-alert
v-else-if="bundles.length === 0 && !loading"
type="warning"
show-icon
      message="등록된 서명 번들이 없습니다."
description="업그레이드 준비에서 승인된 서명 번들 정보를 등록한 뒤 검증할 수 있습니다." />
    <a-alert
v-if="canObserve"
type="warning"
show-icon
message="같은 작업의 결과 확인 또는 복구가 필요합니다."
      description="현재 상태를 조회한 뒤 같은 작업의 확인·복구를 계속합니다. 새 활성화는 전송하지 않으며 설정 변경 보호를 유지합니다." />
    <a-descriptions bordered size="small" :column="1">
      <a-descriptions-item label="템플릿">{{ resource.templatename || '-' }}</a-descriptions-item>
      <a-descriptions-item label="현재 런타임">{{ capability.currentversion || '-' }}</a-descriptions-item>
      <a-descriptions-item label="이전 런타임">{{ capability.previousversion || '-' }}</a-descriptions-item>
      <a-descriptions-item label="지원 상태">{{ supportDescription }}</a-descriptions-item>
      <a-descriptions-item label="실행 파일 검증">{{ integrity.verified ? '일치' : (integrity.reason || '준비 단계에서 승인 기준 확인 필요') }}</a-descriptions-item>
      <a-descriptions-item label="ABI / 설정 스키마">{{ capability.runtimeabiversion || '-' }} / {{ capability.desiredstateschemaversion || '-' }}</a-descriptions-item>
    </a-descriptions>
    <a-collapse v-if="integrity.files && integrity.files.length">
      <a-collapse-panel key="files" header="실제 적용 파일 · SHA-256">
        <a-table :columns="fileColumns" :data-source="integrity.files" :pagination="false" :scroll="{ x: 800 }" size="small" row-key="path" />
      </a-collapse-panel>
    </a-collapse>
    <a-table :columns="historyColumns" :data-source="upgrades" :pagination="false" :scroll="{ x: 760 }" size="small" row-key="id">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'state'"><a-tag :color="stateColor(record.state)">{{ record.state }}</a-tag></template>
        <template v-else-if="column.key === 'view'"><a-button size="small" @click="detail = record">상세</a-button></template>
      </template>
    </a-table>
    <MoldDialog v-if="dialog" :visible="dialog" title="Storage Service 런타임 업그레이드" size="lg" form-actions @cancel="dialog = false">
      <a-alert type="info" show-icon message="전송·스테이징·서명 검증 후 명시적으로 적용합니다." />
      <a-button :disabled="submitting || !!prepared" @click="registrationOpen = !registrationOpen">
        서명 번들 정보 등록
      </a-button>
      <a-form v-if="registrationOpen" layout="vertical">
        <a-alert type="info" show-icon message="관리 서버가 접근 가능한 서명된 세 파일의 주소와 검증 메타데이터를 등록합니다." />
        <a-form-item v-for="field in registrationFields" :key="field.key" :label="field.label" required>
          <a-input-number v-if="field.key === 'artifactsize'" v-model:value="registration[field.key]" :min="1" :max="67108864" style="width:100%" />
          <a-input v-else v-model:value="registration[field.key]" :disabled="['runtimeabiversion','desiredstateschemaversion','serviceimpact'].includes(field.key)" />
        </a-form-item>
      </a-form>
      <a-form v-else layout="vertical">
        <a-form-item required label="대상 서명 번들">
          <a-select v-model:value="selectedBundleId" :disabled="submitting || !!prepared">
            <a-select-option v-for="bundle in bundles" :key="bundle.id" :value="bundle.id">{{ bundle.version }} · ABI {{ bundle.runtimeabiversion }}</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
      <a-descriptions bordered size="small" :column="1">
        <a-descriptions-item label="현재 버전">{{ capability.currentversion || '-' }}</a-descriptions-item>
        <a-descriptions-item label="대상 버전">{{ selectedBundle && selectedBundle.version }}</a-descriptions-item>
        <a-descriptions-item label="서비스 영향">{{ selectedBundle && selectedBundle.serviceimpact }}</a-descriptions-item>
        <a-descriptions-item label="서명키 ID">{{ selectedBundle && selectedBundle.signingkeyid }}</a-descriptions-item>
      </a-descriptions>
      <a-alert
v-if="latestUpgrade"
:type="latestUpgrade.state === 'FAILED' ? 'error' : 'info'"
show-icon
        :message="latestUpgrade.phase"
:description="latestUpgrade.errormessage || latestUpgrade.transactionid" />
      <a-descriptions v-if="prepared" bordered size="small" :column="1">
        <a-descriptions-item v-for="(value, key) in preflightDetails" :key="key" :label="key">{{ value }}</a-descriptions-item>
      </a-descriptions>
      <a-table v-if="preparedFiles.length" :columns="fileColumns" :data-source="preparedFiles" :pagination="false" :scroll="{ x: 800 }" size="small" row-key="path" />
      <a-checkbox v-if="prepared" v-model:checked="acknowledged">현재 버전과 검증 결과를 확인했으며 해당 코드 적용을 진행합니다.</a-checkbox>
      <MoldDialogActions :busy="submitting">
        <a-button @click="dialog = false">취소</a-button>
        <a-button :disabled="submitting || loading" @click="fetchData"><ReloadOutlined />업데이트</a-button>
        <a-button v-if="registrationOpen" type="primary" :disabled="!registrationValid" :loading="submitting" @click="registerBundle">등록</a-button>
        <a-button v-else-if="prepared" type="primary" :disabled="!acknowledged" :loading="submitting" @click="runUpgrade">적용</a-button>
        <a-button v-else type="primary" :disabled="!canPreflight" :loading="submitting" @click="runPreflight">다운로드·검증</a-button>
      </MoldDialogActions>
    </MoldDialog>
    <MoldDialog v-if="rollbackDialog" :visible="true" title="이전 런타임 복구" size="md" form-actions @cancel="rollbackDialog = false">
      <a-alert
type="warning"
show-icon
message="검증된 이전 코드로 복구합니다."
        description="현재 공유 설정과 DATA를 유지합니다. 이후 적용된 작업이 있으면 과거 작업의 원복은 거부됩니다." />
      <p>{{ capability.currentversion }} → {{ capability.previousversion }}</p>
      <MoldDialogActions :busy="submitting">
        <a-button @click="rollbackDialog = false">취소</a-button>
        <a-button type="primary" :disabled="!canRollback" :loading="submitting" @click="runRollback">복구</a-button>
      </MoldDialogActions>
    </MoldDialog>
    <MoldDialog v-if="detail" :visible="true" title="런타임 작업 상세" size="lg" @cancel="detail = null">
      <a-descriptions bordered size="small" :column="1">
        <a-descriptions-item v-for="key in ['transactionid','bundleversion','state','phase','errorcode','errormessage']" :key="key" :label="key">{{ detail[key] || '-' }}</a-descriptions-item>
      </a-descriptions>
      <pre>{{ detail.preflightjson }}</pre><pre>{{ detail.verificationjson }}</pre><pre>{{ detail.rollbackresultjson }}</pre>
    </MoldDialog>
  </div>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { creationRequestId } from '@/utils/sharedFsBacking'
import { CloudUploadOutlined, ReloadOutlined, RollbackOutlined } from '@ant-design/icons-vue'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'

export default {
  name: 'StorageServiceRuntimeUpgrade',
  components: { MoldDialog, MoldDialogActions, CloudUploadOutlined, ReloadOutlined, RollbackOutlined },
  props: { resource: { type: Object, required: true } },
  data () {
    return {
      loading: false,
      submitting: false,
      capability: {},
      bundles: [],
      upgrades: [],
      selectedBundleId: undefined,
      dialog: false,
      rollbackDialog: false,
      detail: null,
      registrationOpen: false,
      registration: {
        version: '',
        runtimeabiversion: '1',
        desiredstateschemaversion: '1',
        serviceimpact: 'NONE',
        artifacturl: '',
        manifesturl: '',
        signatureurl: '',
        artifactsize: undefined,
        sha256: '',
        manifestsha256: '',
        signingkeyid: ''
      },
      acknowledged: false,
      requestid: null,
      epoch: 0,
      readError: ''
    }
  },
  computed: {
    registrationFields () {
      return [
        { key: 'version', label: '번들 버전' }, { key: 'signingkeyid', label: '서명키 ID' },
        { key: 'runtimeabiversion', label: '런타임 ABI' }, { key: 'desiredstateschemaversion', label: '설정 스키마' },
        { key: 'serviceimpact', label: '서비스 영향' }, { key: 'artifacturl', label: '번들 URL' },
        { key: 'manifesturl', label: 'Manifest URL' }, { key: 'signatureurl', label: '서명 URL' },
        { key: 'artifactsize', label: '번들 크기(bytes)' }, { key: 'sha256', label: '번들 SHA-256' },
        { key: 'manifestsha256', label: 'Manifest SHA-256' }
      ]
    },
    registrationValid () {
      return this.registrationFields.every(field => !!this.registration[field.key]) &&
        ['sha256', 'manifestsha256'].every(key => /^[0-9a-f]{64}$/.test(this.registration[key])) &&
        ['artifacturl', 'manifesturl', 'signatureurl'].every(key => /^https?:\/\//.test(this.registration[key]))
    },
    preparedFiles () { return this.parse(this.prepared?.preflightjson).verifiedFiles || [] },
    latestUpgrade () { return this.upgrades[0] || null },
    prepared () { return this.latestUpgrade?.state === 'PREFLIGHT_READY' ? this.latestUpgrade : null },
    selectedBundle () { return this.bundles.find(x => x.id === this.selectedBundleId) },
    integrity () { return this.parse(this.capability.integrityjson) },
    preflightDetails () {
      const result = this.parse(this.prepared?.preflightjson)
      return {
        서명·해시·경로·소유자·권한·호환성: result.phase === 'PREFLIGHT_OK' ? '검증 완료' : '확인 필요',
        '원복 버전': result.previousVersion || '-',
        '필요 공간(bytes)': result.requiredBytes || '-',
        '가용 공간(bytes)': result.availableBytes || '-'
      }
    },
    running () { return this.latestUpgrade?.state === 'RUNNING' && !this.canObserve },
    canObserve () { return this.latestUpgrade?.state === 'OBSERVATION_PENDING' || (this.latestUpgrade?.state === 'RUNNING' && ['ACTIVATING', 'VERIFYING', 'ROLLING_BACK'].includes(this.latestUpgrade.phase)) },
    canPreflight () { return !this.registrationOpen && !this.loading && !this.submitting && !!this.selectedBundleId && this.selectedBundle?.version !== this.capability.currentversion && !this.prepared && !this.running && !this.canObserve },
    canRollback () { return !this.loading && !this.submitting && this.latestUpgrade?.state === 'COMPLETE' && !!this.capability.previousversion },
    supportDescription () {
      if (!this.capability.available) return '조회 불가'
      if ((this.capability.hosttransportversion || 1) < 2) return '안전한 전송을 위해 호스트 모듈 갱신 필요'
      return this.integrity.verified ? '검증 지원' : '준비 단계에서 승인된 기준 코드 대조 필요'
    },
    historyColumns () {
      return [
        { title: '시작 시각', dataIndex: 'started', width: 180 },
        { title: '대상 버전', dataIndex: 'bundleversion', width: 180 },
        { title: '단계', dataIndex: 'phase', width: 160 },
        { title: '상태', key: 'state', width: 160 }, { title: '작업', key: 'view', width: 70 }
      ]
    },
    fileColumns () {
      return [{ title: '파일', dataIndex: 'path', width: 310 }, { title: '실제 SHA-256', dataIndex: 'sha256', width: 490 }]
    }
  },
  watch: {
    'resource.id' () {
      this.epoch++; this.loading = false; this.submitting = false
      this.capability = {}; this.upgrades = []; this.bundles = []
      this.dialog = false; this.rollbackDialog = false; this.requestid = null
      this.selectedBundleId = undefined; this.fetchData()
    }
  },
  created () { this.fetchData() },
  beforeUnmount () { this.epoch++ },
  methods: {
    parse (value) { try { return JSON.parse(value || '{}') } catch (_) { return {} } },
    async fetchData () {
      if (this.loading) return
      const epoch = this.epoch
      this.loading = true
      this.readError = ''
      try {
        const responses = await Promise.all([
          getAPI('getStorageServiceRuntimeUpgradeCapabilities', { sharedfilesystemid: this.resource.id }),
          getAPI('listStorageServiceRuntimeBundles', { listall: true }),
          getAPI('listStorageServiceRuntimeUpgrades', { sharedfilesystemid: this.resource.id, listall: true })
        ])
        if (this.epoch !== epoch) return
        this.capability = responses[0].getstorageserviceruntimeupgradecapabilitiesresponse?.storageserviceruntimecapability || {}
        this.bundles = responses[1].liststorageserviceruntimebundlesresponse?.storageserviceruntimebundle || []
        this.upgrades = (responses[2].liststorageserviceruntimeupgradesresponse?.storageserviceruntimeupgrade || [])
          .sort((a, b) => String(b.started || '').localeCompare(String(a.started || '')))
        if (['COMPLETE', 'ROLLED_BACK', 'FAILED'].includes(this.latestUpgrade?.state)) this.requestid = null
        if (['COMPLETE', 'ROLLED_BACK'].includes(this.latestUpgrade?.state)) { this.dialog = false; this.rollbackDialog = false }
        if (this.prepared) this.selectedBundleId = this.prepared.bundleid
        else if (!this.selectedBundleId && this.bundles.length) this.selectedBundleId = this.bundles[0].id
      } catch (error) {
        if (this.epoch === epoch) { this.readError = '런타임 조회에 실패했습니다. 업데이트로 다시 확인하십시오.'; this.$notifyError(error) }
      } finally { if (this.epoch === epoch) this.loading = false }
    },
    async registerBundle () {
      if (!this.registrationValid || this.submitting) return
      this.submitting = true
      const epoch = this.epoch
      try {
        const wire = await postAPI('registerStorageServiceRuntimeBundle', { ...this.registration })
        if (this.epoch !== epoch) return
        const bundle = wire.registerstorageserviceruntimebundleresponse?.storageserviceruntimebundle
        if (!bundle?.id) throw new Error('Runtime bundle registration response is missing its identity')
        await this.fetchData()
        this.selectedBundleId = bundle.id
        this.requestid = null
        this.registrationOpen = false
      } catch (error) {
        if (this.epoch === epoch) this.$notifyError(error)
      } finally { if (this.epoch === epoch) this.submitting = false }
    },
    openPreparation () {
      if (this.canObserve) { this.runUpgrade(); return }
      this.acknowledged = false; this.dialog = true
    },
    runPreflight () {
      if (!this.canPreflight) return
      if (!this.requestid) this.requestid = 'runtime-' + creationRequestId()
      this.startAsync('preflightStorageServiceRuntimeUpgrade', {
        sharedfilesystemid: this.resource.id, bundleid: this.selectedBundleId, requestid: this.requestid
      })
    },
    runUpgrade () {
      if ((!this.prepared || !this.acknowledged) && !this.canObserve) return
      this.startAsync('upgradeStorageServiceRuntime', { upgradeid: this.latestUpgrade.id })
    },
    runRollback () {
      if (this.canRollback) this.startAsync('rollbackStorageServiceRuntimeUpgrade', { upgradeid: this.latestUpgrade.id })
    },
    async startAsync (api, params) {
      if (this.submitting) return
      this.submitting = true
      const epoch = this.epoch
      const finish = () => {
        if (this.epoch !== epoch) return
        this.submitting = false
        this.acknowledged = false
        this.fetchData()
      }
      try {
        const wire = await postAPI(api, params, { preserveOnFailure: true })
        if (this.epoch !== epoch) return
        const root = wire[api.toLowerCase() + 'response'] || {}
        if (!root.jobid) { finish(); return }
        this.$pollJob({
          jobId: root.jobid,
          title: 'Storage Service 런타임',
          description: this.resource.name,
          showLoading: false,
          resourceId: this.resource.id,
          successMethod: finish,
          errorMethod: finish,
          catchMethod: finish
        })
      } catch (error) {
        if (this.epoch === epoch) { this.$notifyError(error); finish() }
      }
    },
    stateColor (state) {
      if (state === 'COMPLETE') return 'green'
      if (['FAILED', 'MANUAL_RECOVERY'].includes(state)) return 'red'
      if (['OBSERVATION_PENDING', 'ROLLED_BACK'].includes(state)) return 'orange'
      return 'blue'
    }
  }
}
</script>
<style scoped>
.runtime-upgrade { display: flex; flex-direction: column; gap: 16px; min-width: 0; color: var(--ui-text); }
.runtime-toolbar { justify-content: flex-start; margin-bottom: 0; }
.runtime-upgrade :deep(.ant-descriptions-item-content) { overflow-wrap: anywhere; }
.runtime-upgrade pre { white-space: pre-wrap; overflow-wrap: anywhere; background: var(--ui-bg); border: 1px solid var(--ui-border); padding: 16px; color: var(--ui-text); }
</style>
