<!-- AutoAlertBanner.vue (최소 변경: openSilence name 주입 + 배너 CSS 범위 한정) -->
<template>
  <teleport to="body">
    <div v-if="showBanner" class="auto-alert-banner-container">
      <div class="banner-list">
        <a-alert
          v-for="it in visibleAlerts"
          :key="it.uid || it.id"
          :type="'error'"
          :show-icon="false"
          :closable="true"
          :banner="true"
          :style="[{ border: '1px solid #ffa39e', background: '#fff1f0' }]"
        >
          <template #message>
            <div class="banner-content" style="display:flex; justify-content:flex-end; align-items:center; gap:12px; flex-wrap:wrap; text-align:right;">
              <span class="banner-text">
                <ExclamationCircleFilled class="banner-error-icon" />
                {{ $t('label.alert') || '경고' }} "{{ it && it.title ? it.title : ($t('label.alert') || '경고') }}" {{ $t('message.alerting') || '경고 발생 중입니다.' }}

                <!-- 문제 호스트 -->
                <span v-if="hostLinkList(it).length" class="banner-hosts">
                  {{ $t('label.targets.hosts') || '대상 호스트' }}:
                  <span class="chip-wrap">
                    <a-tag
                      v-for="lnk in hostLinkList(it)"
                      :key="lnk.key"
                      class="tag-link"
                      @click.prevent="goToHost(lnk.keyword)"
                    >
                      {{ lnk.label }}
                    </a-tag>
                    <a-tag
                      v-if="hostMoreCount(it) > 0"
                      class="tag-more"
                      @click="openUrl(hostMoreHref)"
                    >
                      +{{ hostMoreCount(it) }}
                    </a-tag>
                  </span>
                </span>

                <!-- 문제 VM -->
                <span v-if="vmLinkList(it).length" class="banner-vms">
                  대상 VM:
                  <span class="chip-wrap">
                    <a-tag
                      v-for="lnk in vmLinkList(it)"
                      :key="lnk.key"
                      class="tag-link"
                      @click.prevent.stop="goToVm(lnk.keyword)"
                      :title="`${$t('tooltip.goto.vm.detail') || 'VM 상세로 이동'}: ${lnk.label}`"
                    >
                      {{ lnk.label }}
                    </a-tag>
                    <a-popover
                      v-if="vmMoreCount(it) > 0"
                      trigger="hover"
                      placement="bottomRight"
                      :overlayStyle="{ zIndex: 2147483649 }"
                      :getPopupContainer="getPopupParent"
                    >
                      <template #content>
                        <div class="vm-more-pop">
                          <a-tag
                            v-for="lnk in vmRestList(it)"
                            :key="lnk.key"
                            class="tag-link"
                            @click.prevent.stop="goToVm(lnk.keyword)"
                            :title="`${$t('tooltip.goto.vm.detail') || 'VM 상세로 이동'}: ${lnk.label}`"
                          >
                            {{ lnk.label }}
                          </a-tag>
                        </div>
                      </template>

                      <a-tag class="tag-more">+{{ vmMoreCount(it) }}</a-tag>
                    </a-popover>
                  </span>
                </span>

                <!-- 문제 스토리지 -->
                <span v-if="storageLinkList(it).length" class="banner-storage">
                  {{ $t('label.targets.storage.controller') || '대상 스토리지 컨트롤러' }}:
                  <span class="chip-wrap">
                    <a-tag
                      v-for="lnk in storageLinkList(it)"
                      :key="lnk.key"
                      class="tag-link"
                      @click="openUrlBlank(lnk.url)"
                    >
                      {{ lnk.label }}
                    </a-tag>
                  </span>
                </span>

                <!-- 문제 클라우드 -->
                <span v-if="cloudLinkList(it).length" class="banner-cloud">
                  {{ $t('label.targets.management') || '대상 관리 서버' }}:
                  <span class="chip-wrap">
                    <a-tag
                      v-for="lnk in cloudLinkList(it)"
                      :key="lnk.key"
                      class="tag-link"
                      @click="openUrl(lnk.url)"
                      :title="$t('tooltip.goto.management') || '관리 서버로 이동'"
                    >
                      {{ lnk.label }}
                    </a-tag>
                  </span>
                </span>
              </span>

              <!-- 액션 영역 -->
              <a-space class="banner-actions" :size="8" align="center" wrap>
                <a :href="hrefAlertRule(it)" class="router-link-button" :title="`${$t('label.goto.the.alertRule') || '경고 규칙으로 이동'}: ${it?.title || ''}`">
                  <a-button size="small" type="link">
                    <template #icon><LinkOutlined /></template>
                    {{ $t('label.goto.the.alertRules') || '경고 규칙으로 이동' }}
                  </a-button>
                </a>

                <!-- Silence → RuleSilenceModal 모달 오픈 -->
                <a-button
                  v-if="!isKeySilencedNow(it && it.uid)"
                  size="small"
                  class="silence-menu"
                  :loading="isSilencing(it)"
                  :disabled="!it || !it.uid"
                  @click.stop="openSilence(it)"
                >
                  <span class="icon-stack">
                    <SoundOutlined class="icon-sound" />
                  </span>
                  {{ $t('label.action.silence') || 'Silence' }}
                </a-button>

                <a-popconfirm
                  :title="$t('message.confirm.pause.rule') || 'Pause this rule?'"
                  :ok-text="$t('label.ok') || 'OK'"
                  :cancel-text="$t('label.cancel') || 'Cancel'"
                  @confirm="() => pauseRule(it)"
                >
                  <a-button
                    size="small"
                    class="pause-btn pause-compact"
                    danger
                    :disabled="!it || !it.uid || isPausing(it)"
                    :loading="isPausing(it)"
                  >
                    <template #icon><PauseCircleOutlined /></template>
                    {{ $t('label.alert.rule.pause') || 'Pause' }}
                  </a-button>
                </a-popconfirm>
              </a-space>
            </div>
          </template>
        </a-alert>
      </div>
    </div>

    <!-- RuleSilenceModal을 Ant Modal로 감싸서 사용 (v-model:visible) -->
    <a-modal
      v-model:visible="silenceModal.visible"
      :footer="null"
      :destroyOnClose="true"
      :maskClosable="false"
      :centered="true"
      :getContainer="getPopupParent"
      :zIndex="2147483652"
      width="480px"
      @afterClose="closeSilence"
    >
      <RuleSilenceModal
        v-if="silenceModal.visible"
        :resource="silenceModal.target"
        @refresh-data="onSilenceRefresh"
        @close-action="closeSilence"
      />
    </a-modal>
  </teleport>
</template>

<script>
import { ref, computed, onMounted, onBeforeUnmount, defineAsyncComponent } from 'vue'
import { LinkOutlined, ExclamationCircleFilled, SoundOutlined, PauseCircleOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { api } from '@/api'

export default {
  name: 'AutoAlertBanner',
  components: {
    LinkOutlined,
    ExclamationCircleFilled,
    SoundOutlined,
    PauseCircleOutlined,
    RuleSilenceModal: defineAsyncComponent(() => import('@/views/infra/RuleSilenceModal.vue'))
  },
  setup () {
    // ===== 베이스 URL =====
    const ORIGIN = typeof window !== 'undefined' ? window.location.origin : ''
    const HOST_BASE = ''
    const VM_BASE = '/client'

    // ===== URL 생성기 =====
    const hrefHostDetail = (id) => `${ORIGIN}${HOST_BASE}/#/host/${id}`
    const hrefHostList = (keyword) => `${ORIGIN}${HOST_BASE}/#/hosts${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`
    const hrefVmDetail = (id) => `${ORIGIN}${VM_BASE}/#/vm/${id}?tab=details`
    const hrefVmList = (keyword) => `${ORIGIN}${VM_BASE}/#/vm${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`
    const hrefCloudPage = () => `${ORIGIN}/#/managementserver`
    const alertRulesHref = `${ORIGIN}/#/alertRules`
    const hrefAlertRule = (item) => {
      const uid = item && (item.uid || ruleUid(item.rule) || item.id)
      return uid
        ? `${ORIGIN}/#/alertRules/${encodeURIComponent(uid)}`
        : `${ORIGIN}/#/alertRules`
    }
    const hostMoreHref = hrefHostList('')
    const vmMoreHref = hrefVmList('')
    const MAX_LINKS = 3

    // ---------- 상태 ----------
    const loading = ref(false)
    const silencingSet = ref(new Set())
    const pausingSet = ref(new Set())
    const rules = ref([])
    let timer = null
    const refreshInFlight = ref(false)

    // 로컬/서버 사일런스 캐시
    const LS_KEY = 'autoAlertBanner.silencedByUid'
    const localSilenced = ref(loadLocalSilences())
    const remoteSilenced = ref({})
    const remoteSilencedLoaded = ref(false)
    const SILENCE_TTL_MS = 30 * 1000
    const silenceCache = new Map()

    // ---------- 유틸 ----------
    const UC = (v) => (v == null ? '' : String(v).toUpperCase())
    const pickState = (obj) => {
      if (!obj) return ''
      if (typeof obj === 'string') return obj
      if (typeof obj !== 'object') return ''
      if (typeof obj.state === 'string') return obj.state
      if (typeof obj.currentState === 'string') return obj.currentState
      if (typeof obj.evaluationState === 'string') return obj.evaluationState
      if (typeof obj.health === 'string') return obj.health
      if (typeof obj.status === 'string') return obj.status
      if (obj.state && typeof obj.state === 'object' && typeof obj.state.state === 'string') return obj.state.state
      if (obj.status && typeof obj.status === 'object' && typeof obj.status.state === 'string') return obj.status.state
      if (obj.grafana_alert && typeof obj.grafana_alert.state === 'string') return obj.grafana_alert.state
      if (obj.grafana_alert && typeof obj.grafana_alert.state === 'object' && typeof obj.grafana_alert.state.state === 'string') return obj.grafana_alert.state.state
      if (obj.alert && typeof obj.alert.state === 'string') return obj.alert.state
      if (obj.alert && typeof obj.alert.state === 'object' && typeof obj.alert.state.state === 'string') return obj.alert.state.state
      return ''
    }
    const normState = (v) => UC(pickState(v)).replace(/\s+/g, '')
    const isNoiseLike = (v) => {
      const s = normState(v)
      return s === 'NODATA' || s === 'UNKNOWN' || s === 'PENDING' || s === 'OK' || s === 'NORMAL' || s === 'INACTIVE'
    }
    const parseIp = (s) => {
      if (!s) return ''
      const m = String(s).match(/(\d{1,3}(?:\.\d{1,3}){3})/)
      return m ? m[1] : ''
    }
    function takeFirst (...args) {
      for (let i = 0; i < args.length; i += 1) {
        const v = args[i]
        if (v != null && v !== '') return v
      }
      return ''
    }

    // ====== 호스트/VM 목록 파서 & 인덱싱 ======
    const extractHosts = (resp) => {
      const wrap =
        resp?.listhostsresponse ||
        resp?.listHostsResponse ||
        resp?.data ||
        resp ||
        {}
      let list =
        wrap?.host ||
        wrap?.hosts ||
        wrap?.items ||
        wrap?.list
      if (!Array.isArray(list)) {
        for (const k in wrap) {
          if (Array.isArray(wrap[k])) { list = wrap[k]; break }
        }
      }
      return Array.isArray(list) ? list : []
    }

    const extractVMs = (resp) => {
      const wrap =
        resp?.listvirtualmachinesresponse ||
        resp?.listVirtualMachinesResponse ||
        resp?.data ||
        resp ||
        {}
      let list =
        wrap?.virtualmachine ||
        wrap?.virtualMachine ||
        wrap?.virtualmachines ||
        wrap?.virtualMachines ||
        wrap?.items ||
        wrap?.list
      if (!Array.isArray(list)) {
        for (const k in wrap) {
          if (Array.isArray(wrap[k])) { list = wrap[k]; break }
        }
      }
      return Array.isArray(list) ? list : []
    }

    const hostIndexCache = { until: 0, byIp: new Map(), byName: new Map() }
    const vmIndexCache = { until: 0, byIp: new Map(), byName: new Map() }

    const ensureHostIndex = async () => {
      const now = Date.now()
      if (hostIndexCache.until > now) return
      try {
        const params = { listAll: true, listall: true, page: 1, pageSize: 200, pagesize: 200 }
        const resp = await api('listHosts', params)
        const rows = extractHosts(resp)
        hostIndexCache.byIp = new Map()
        hostIndexCache.byName = new Map()
        for (let i = 0; i < rows.length; i += 1) {
          const h = rows[i] || {}
          const id = takeFirst(h.id, h.uuid)
          const name = takeFirst(h.name, h.hostname, h.hostName)
          const ip = takeFirst(
            h.ipaddress, h.ipAddress, h.hostip, h.hostIp,
            h.privateipaddress, h.privateIpAddress
          )
          if (!id) continue
          const info = { id: String(id), name: name ? String(name) : '' }
          if (name) hostIndexCache.byName.set(String(name), info)
          if (ip) hostIndexCache.byIp.set(String(ip), info)
        }
        hostIndexCache.until = now + 5 * 60 * 1000
      } catch (_) {
        hostIndexCache.until = now + 60 * 1000
      }
    }

    const ensureVmIndex = async () => {
      const now = Date.now()
      if (vmIndexCache.until > now) return
      try {
        const params = { listAll: true, listall: true, page: 1, pageSize: 200, pagesize: 200 }
        const resp = await api('listVirtualMachines', params)
        const rows = extractVMs(resp)
        vmIndexCache.byIp = new Map()
        vmIndexCache.byName = new Map()
        for (let i = 0; i < rows.length; i += 1) {
          const v = rows[i] || {}
          const id = takeFirst(v.id, v.uuid)
          const name = takeFirst(v.name, v.displayname, v.displayName)
          if (id) {
            if (name) vmIndexCache.byName.set(String(name), String(id))
            const nics = Array.isArray(v.nic) ? v.nic : (Array.isArray(v.nics) ? v.nics : [])
            for (let j = 0; j < nics.length; j += 1) {
              const ip = takeFirst(nics[j]?.ipaddress, nics[j]?.ipAddress, nics[j]?.ip)
              if (ip) vmIndexCache.byIp.set(String(ip), String(id))
            }
          }
        }
        vmIndexCache.until = now + 5 * 60 * 1000
      } catch (_) {
        vmIndexCache.until = now + 60 * 1000
      }
    }

    // 룰에서 수확한 IP→호스트명 힌트
    const hostHints = { byIpName: new Map() }
    const hintHostNameByIp = (ip) => {
      if (!ip) return ''
      const val = hostHints.byIpName.get(String(ip))
      return val ? String(val) : ''
    }

    // 규칙 응답 파싱
    const extractRules = (resp) => {
      const wrap =
        resp?.listwallalertrulesresponse ||
        resp?.listWallAlertRulesResponse ||
        resp?.data ||
        resp ||
        {}
      const inner =
        wrap.wallalertruleresponse ||
        wrap.wallalertrules ||
        wrap.wallAlertRules ||
        wrap.rules ||
        wrap.items ||
        wrap.list ||
        wrap
      if (Array.isArray(inner)) return inner
      let rows =
        inner?.wallalertrule ||
        inner?.wallAlertRule ||
        inner?.rule ||
        inner?.rules ||
        inner?.items ||
        inner?.list ||
        []
      if (!Array.isArray(rows) && inner && typeof inner === 'object') {
        for (const k of Object.keys(inner)) {
          if (Array.isArray(inner[k])) { rows = inner[k]; break }
        }
      }
      if (!Array.isArray(rows)) rows = rows ? [rows] : []
      return rows
    }

    const ruleInstances = (r) => {
      if (!r) return []
      const st = r.status && typeof r.status === 'object' ? r.status : null
      if (st && Array.isArray(st.alerts)) return st.alerts
      if (st && Array.isArray(st.instances)) return st.instances
      if (Array.isArray(r.alerts)) return r.alerts
      if (Array.isArray(r.instances)) return r.instances
      return []
    }
    const instanceState = (a) => normState(
      pickState(a) || pickState(a && a.state) || pickState(a && a.evaluationState) ||
      pickState(a && a.health) || pickState(a && a.status)
    )
    const ruleState = (r) => normState(
      pickState(r && r.state) || pickState(r && r.status) ||
      pickState(r && r.evaluationState) || pickState(r && r.health) || pickState(r)
    )
    const ruleUid = (r) => {
      return takeFirst(
        r && r.uid,
        r && r.ruleUid,
        r && r.alertUid,
        r && r.grafana_alert && r.grafana_alert.uid,
        r && r.alert && r.alert.uid,
        r && r.metadata && (r.metadata.rule_uid || r.metadata.uid),
        r && r.annotations && (r.annotations.__alert_rule_uid__ || r.annotations.uid),
        r && r.labels && (r.labels.__alert_rule_uid__ || r.labels.uid)
      ) || null
    }
    const ruleTitle = (r) => (r && (r.name || r.title || (r.metadata && r.metadata.rule_title) || r.ruleName)) || 'Alert'

    // 로컬 사일런스
    function loadLocalSilences () {
      try {
        const raw = localStorage.getItem(LS_KEY)
        const obj = raw ? JSON.parse(raw) : {}
        return typeof obj === 'object' && obj ? obj : {}
      } catch (_) { return {} }
    }
    function saveLocalSilences () {
      try { localStorage.setItem(LS_KEY, JSON.stringify(localSilenced.value || {})) } catch (_) {}
    }
    function cleanupLocalSilences () {
      const now = Date.now()
      const next = {}
      for (const k in localSilenced.value) {
        if (Object.prototype.hasOwnProperty.call(localSilenced.value, k)) {
          if (localSilenced.value[k] > now) next[k] = localSilenced.value[k]
        }
      }
      localSilenced.value = next
      saveLocalSilences()
    }

    // 사일런스 파싱 + 서버 동기화
    const extractSilences = (resp) => {
      const wrap =
        resp?.listwallalertsilencesresponse ||
        resp?.listWallAlertSilencesResponse ||
        resp?.data ||
        resp ||
        {}
      let list =
        wrap?.silences ||
        wrap?.silence ||
        wrap?.items ||
        wrap?.list
      if (!Array.isArray(list)) {
        for (const k in wrap) {
          if (Array.isArray(wrap[k])) { list = wrap[k]; break }
        }
      }
      return Array.isArray(list) ? list : []
    }

    const syncRemoteSilencesByUidList = async (uids) => {
      remoteSilencedLoaded.value = false
      if (!Array.isArray(uids) || !uids.length) {
        remoteSilenced.value = {}
        remoteSilencedLoaded.value = true
        return
      }
      const uniq = Array.from(new Set(uids.map(String).filter(Boolean)))
      const now = Date.now()
      const needFetch = []
      const mapFromCache = {}
      for (let i = 0; i < uniq.length; i += 1) {
        const uid = uniq[i]
        const cached = silenceCache.get(uid)
        if (cached && cached.until > now) {
          if (cached.end > now) mapFromCache[uid] = cached.end
        } else {
          needFetch.push(uid)
        }
      }
      const CONC = 4
      const chunks = []
      for (let i = 0; i < needFetch.length; i += CONC) chunks.push(needFetch.slice(i, i + CONC))
      for (let c = 0; c < chunks.length; c += 1) {
        const group = chunks[c]
        await Promise.all(group.map(async (uid) => {
          try {
            const params = {
              page: 1,
              pageSize: 200,
              'labels[0].key': '__alert_rule_uid__',
              'labels[0].value': uid
            }
            const resp = await api('listWallAlertSilences', params)
            const list = extractSilences(resp)
            let activeEnd = 0
            for (let i2 = 0; i2 < list.length; i2 += 1) {
              const s = list[i2] || {}
              const state = String(s.state || '').toLowerCase()
              const startLike = s.startMs || s.startTime || s.startsAt || s.start || s.createdAt
              const endLike = s.endMs || s.endTime || s.endsAt || s.end || s.expiresAt
              const start = typeof startLike === 'number' ? startLike : (startLike ? Date.parse(startLike) : 0)
              const end = typeof endLike === 'number' ? endLike : (endLike ? Date.parse(endLike) : 0)
              if ((state === 'active' || (start && start <= now)) && end > now) {
                activeEnd = Math.max(activeEnd, end)
              }
            }
            silenceCache.set(uid, { end: activeEnd, until: Date.now() + SILENCE_TTL_MS })
            if (activeEnd > now) mapFromCache[uid] = activeEnd
          } catch (_) {
            silenceCache.set(uid, { end: 0, until: Date.now() + 10 * 1000 })
          }
        }))
      }
      remoteSilenced.value = mapFromCache
      remoteSilencedLoaded.value = true
    }

    // ========== 라벨/링크 유틸 ==========
    const VM_NAME_RE = /^i-\d+-\d+-VM$/i
    const VM_NAME_FUZZY_RE = /-VM$/i
    const labelBag = (a) => (a && (a.labels || a.metric || a.tags || a)) || {}

    const bestHostOfInstance = (a) => {
      const L = labelBag(a)
      const prefers = ['nodename', 'host', 'hostname', 'node', 'machine', 'server']
      for (let i = 0; i < prefers.length; i += 1) {
        const k = prefers[i]
        if (L && L[k]) return String(L[k])
      }
      if (L && L.instance) return String(L.instance).replace(/:\d+$/, '')
      return ''
    }

    const bestVmOfInstance = (a) => {
      const L = labelBag(a)
      const keys = ['vm', 'vmname', 'vm_name', 'displayname', 'display_name', 'guest', 'domain']
      for (let i = 0; i < keys.length; i += 1) {
        const k = keys[i]
        if (!L || !L[k]) continue
        const v = String(L[k])
        if (VM_NAME_RE.test(v) || VM_NAME_FUZZY_RE.test(v)) return v
      }
      return ''
    }

    // kind 정규화
    const normalizeKind = (v) => String(v == null ? '' : v).replace(/\s+/g, '').toLowerCase()
    const isVmKind = (v) => {
      const k = normalizeKind(v)
      return k === '사용자vm' || k === 'virtualmachine' || k === 'vm' || k.includes('uservm') || k.includes('guest')
    }
    const isStorageKind = (v) => {
      const k = normalizeKind(v)
      return k === '스토리지' || k === 'storage' || k.includes('scvm')
    }
    const isCloudKind = (v) => {
      const k = normalizeKind(v)
      return k === '클라우드' || k === 'cloud' || k === 'management' || k === 'managementserver'
    }

    // 룰에서 수확한 IP→호스트명 힌트
    const harvestHostHintsFromRules = (arrRules) => {
      for (let i = 0; i < arrRules.length; i += 1) {
        const insts = ruleInstances(arrRules[i])
        for (let j = 0; j < insts.length; j += 1) {
          const a = insts[j] || {}
          const L = labelBag(a)
          const name = takeFirst(L.nodename, L.host, L.hostname, L.node, L.machine, L.server)
          const ip = parseIp(takeFirst(L.instance, L.ip, L.address))
          if (name && ip) hostHints.byIpName.set(String(ip), String(name))
        }
      }
    }

    // 호스트/VM/스토리지/클라우드 링크 목록
    const resolveHostInfo = (keyword) => {
      if (!keyword) return null
      const key = String(keyword)
      const ip = parseIp(key)
      if (ip && hostIndexCache.byIp.has(ip)) return hostIndexCache.byIp.get(ip)
      const hintName = ip ? hintHostNameByIp(ip) : ''
      if (hintName && hostIndexCache.byName.has(hintName)) return hostIndexCache.byName.get(hintName)
      if (hostIndexCache.byName.has(key)) return hostIndexCache.byName.get(key)
      return null
    }
    const hostDisplayLabel = (keyword) => {
      const info = resolveHostInfo(keyword)
      if (info && info.name) return info.name
      const ip = parseIp(String(keyword))
      const hinted = hintHostNameByIp(ip)
      if (hinted) return hinted
      return ip || String(keyword)
    }
    const hostDedupKey = (keyword) => {
      const info = resolveHostInfo(keyword)
      if (info && info.id) return 'host#' + info.id
      const label = hostDisplayLabel(keyword)
      return 'host@' + String(label).toLowerCase()
    }

    const storageLabel = (a) => {
      const L = labelBag(a)
      const name = takeFirst(L.nodename, L.host, L.hostname, L.node, L.machine, L.server)
      const ip = parseIp(takeFirst(L.instance, L.ip, L.address))
      return name || ip || ''
    }
    const storageUrlByInstance = (a) => {
      const ip = parseIp(takeFirst(labelBag(a).instance, labelBag(a).ip, labelBag(a).address))
      return ip ? `https://${ip}:9090/` : ''
    }

    const cloudLabel = (a) => {
      const L = labelBag(a)
      const name = takeFirst(L.nodename, L.host, L.hostname, L.node, L.machine, L.server)
      const ip = parseIp(takeFirst(L.instance, L.ip, L.address))
      return name || ip || 'management'
    }
    const cloudUrl = () => hrefCloudPage()

    const pickKindFromRule = (r) => {
      const k1 = r && r.kind
      const k2 = r && r.labels && (r.labels.kind || r.labels.KIND)
      const k3 = r && r.metadata && (r.metadata.kind || r.metadata.KIND)
      return takeFirst(k1, k2, k3)
    }

    const classifyInstance = (a, parentKind) => {
      const L = labelBag(a)
      const ownKind = L.kind || L.KIND
      const finalKind = takeFirst(ownKind, parentKind)

      // VM 우선
      const domainName = L.domain ? String(L.domain) : ''
      if (isVmKind(finalKind) || (domainName && (VM_NAME_RE.test(domainName) || VM_NAME_FUZZY_RE.test(domainName)))) {
        if (domainName) return { kind: 'vm', keyword: domainName }
        const vmByOther = bestVmOfInstance(a)
        if (vmByOther) return { kind: 'vm', keyword: vmByOther }
      }

      // 스토리지
      if (isStorageKind(finalKind)) {
        const label = storageLabel(a)
        const url = storageUrlByInstance(a)
        if (label && url) return { kind: 'storage', label, url }
      }

      // 클라우드(관리 서버)
      if (isCloudKind(finalKind)) {
        const label = cloudLabel(a)
        const url = cloudUrl()
        if (label && url) return { kind: 'cloud', label, url }
      }

      // 호스트
      const host = bestHostOfInstance(a)
      if (host) return { kind: 'host', keyword: host }
      return null
    }

    const entityLinksForAlert = (it) => {
      const parentKind = pickKindFromRule(it.rule || {})
      const arr = Array.isArray(it?.alerts) ? it.alerts : []
      const seen = new Set()
      const out = []

      for (let i = 0; i < arr.length; i += 1) {
        const cls = classifyInstance(arr[i], parentKind)
        if (!cls) continue

        if (cls.kind === 'host') {
          const key = hostDedupKey(cls.keyword)
          if (seen.has(key)) continue
          seen.add(key)
          out.push({
            key,
            kind: 'host',
            label: hostDisplayLabel(cls.keyword),
            keyword: cls.keyword
          })
          continue
        }

        if (cls.kind === 'vm') {
          const key = 'vm@' + String(cls.keyword).toLowerCase()
          if (seen.has(key)) continue
          seen.add(key)
          out.push({
            key,
            kind: 'vm',
            label: String(cls.keyword),
            keyword: cls.keyword
          })
          continue
        }

        if (cls.kind === 'storage') {
          const key = 'storage@' + String(cls.label).toLowerCase()
          if (seen.has(key)) continue
          seen.add(key)
          out.push({
            key,
            kind: 'storage',
            label: cls.label,
            url: cls.url
          })
          continue
        }

        if (cls.kind === 'cloud') {
          const key = 'cloud@' + String(cls.label).toLowerCase()
          if (seen.has(key)) continue
          seen.add(key)
          out.push({
            key,
            kind: 'cloud',
            label: cls.label,
            url: cls.url
          })
        }
      }
      return out
    }

    const hostEntityLinks = (it) => entityLinksForAlert(it).filter((x) => x.kind === 'host')
    const vmEntityLinks = (it) => entityLinksForAlert(it).filter((x) => x.kind === 'vm')
    const storageEntityLinks = (it) => entityLinksForAlert(it).filter((x) => x.kind === 'storage')
    const cloudEntityLinks = (it) => entityLinksForAlert(it).filter((x) => x.kind === 'cloud')
    const hostLinkList = (it) => hostEntityLinks(it).slice(0, MAX_LINKS)
    const vmLinkList = (it) => vmEntityLinks(it).slice(0, MAX_LINKS)
    const storageLinkList = (it) => storageEntityLinks(it)
    const cloudLinkList = (it) => cloudEntityLinks(it)
    const hostMoreCount = (it) => Math.max(0, hostEntityLinks(it).length - MAX_LINKS)
    const vmMoreCount = (it) => Math.max(0, vmEntityLinks(it).length - MAX_LINKS)
    const vmRestList = (it) => vmEntityLinks(it).slice(MAX_LINKS)

    // ===== i18n helper (no imports) =====
    const t = (k, params) => {
      try {
        const fn = (typeof window !== 'undefined' && typeof window.$t === 'function') ? window.$t : null
        return fn ? fn(k, params) : null
      } catch (_) {
        return null
      }
    }

    const keyOf = (it) => (it && (it.uid || it.id)) || null
    const isSilencing = (it) => silencingSet.value.has(keyOf(it))
    const isPausing = (it) => pausingSet.value.has(keyOf(it))
    const isKeySilencedNow = (k) => {
      if (!k) return false
      const now = Date.now()
      if (remoteSilencedLoaded.value) {
        return !!(remoteSilenced.value && remoteSilenced.value[k] > now)
      }
      return !!(
        (localSilenced.value && localSilenced.value[k] > now) ||
        (remoteSilenced.value && remoteSilenced.value[k] > now)
      )
    }
    const markPausing = (it, on) => {
      const k = keyOf(it); if (!k) return
      const s = new Set(pausingSet.value); on ? s.add(k) : s.delete(k); pausingSet.value = s
    }

    const pauseRule = async (it) => {
      if (!it) return
      markPausing(it, true)
      try {
        const rid = it && it.uid ? String(it.uid) : null
        if (!rid) { message.error(t('error.pause.missingUid') || 'UID가 없어 정지할 수 없습니다.'); return }
        await api('pauseWallAlertRule', { id: rid, paused: true })
        message.success(`${t('message.pause.success') || '정지 완료'}: ${it?.title || rid}`)
        await refresh()
      } catch (e) {
        message.error(t('message.pause.failed') || '정지에 실패했습니다. 잠시 후 다시 시도해 주세요.')
      } finally {
        markPausing(it, false)
      }
    }

    // ---------- 계산 ----------
    const alerting = computed(() => {
      const out = []
      const seen = new Set()
      const rs = Array.isArray(rules.value) ? rules.value : []
      for (let i = 0; i < rs.length; i += 1) {
        const r = rs[i] || {}
        const inst = ruleInstances(r)
        const on = inst.filter((a) => ['ALERTING', 'FIRING'].includes(UC(instanceState(a))) && !isNoiseLike(instanceState(a)))
        const rState = ruleState(r)
        const ruleIsAlerting = ['ALERTING', 'FIRING'].includes(UC(rState)) && !isNoiseLike(rState)
        if (!on.length && !ruleIsAlerting) continue
        const uid = ruleUid(r) || r.id || r.ruleId
        if (!uid || seen.has(uid)) continue
        seen.add(uid)
        out.push({ id: r.id || r.ruleId || uid, uid, title: ruleTitle(r), rule: r, alerts: on })
      }
      return out
    })

    const visibleAlerts = computed(() => {
      const list = Array.isArray(alerting.value) ? alerting.value : []
      const seen = new Set()
      const out = []
      for (let i = 0; i < list.length; i += 1) {
        const it = list[i]
        const uid = it && (it.uid || it.id)
        if (!uid || seen.has(uid)) continue
        if (isKeySilencedNow(uid)) { seen.add(uid); continue }
        out.push(it)
        seen.add(uid)
      }
      return out
    })

    const showBanner = computed(() => {
      return remoteSilencedLoaded.value && Array.isArray(visibleAlerts.value) && visibleAlerts.value.length > 0
    })

    // ---------- 동작 ----------
    const refresh = async () => {
      if (refreshInFlight.value) return
      refreshInFlight.value = true
      loading.value = true
      try {
        const params = {
          includeStatus: true,
          includestatus: true,
          listAll: true,
          listall: true,
          state: '',
          kind: '',
          name: '',
          page: 1,
          pageSize: 200,
          pagesize: 200
        }
        const resp = await api('listWallAlertRules', params)
        rules.value = extractRules(resp)

        await Promise.all([ensureHostIndex(), ensureVmIndex()])

        harvestHostHintsFromRules(Array.isArray(rules.value) ? rules.value : [])

        const uids = (Array.isArray(rules.value) ? rules.value : [])
          .map((r) => ruleUid(r) || r.id || r.ruleId)
        await syncRemoteSilencesByUidList(uids)

        cleanupLocalSilences()
      } finally {
        loading.value = false
        refreshInFlight.value = false
      }
    }

    // ---------- Silence 모달 상태 ----------
    const silenceModal = ref({ visible: false, target: null })
    const openSilence = (it) => {
      // ✅ 최소 변경: 모달 상단 “규칙:”에 제목이 보이도록 name 주입
      const title = (it && it.title) || (it && it.rule && ruleTitle(it.rule)) || it?.name || ''
      const target = { ...it, name: title }
      // eslint-disable-next-line no-console
      console.log('[AutoAlertBanner] openSilence target ->', target)
      silenceModal.value = { visible: true, target }
    }
    const closeSilence = () => { silenceModal.value = { visible: false, target: null } }
    const onSilenceRefresh = async () => {
      closeSilence()
      await refresh()
      message.success(window.$t?.('message.silence.applied') || '사일런스 적용')
    }

    // ========== 네비게이션/헬퍼 ==========
    function openUrl (url) {
      try { window.location.href = String(url) } catch (_) {}
    }
    function openUrlBlank (url) {
      try {
        window.open(String(url), '_blank', 'noopener,noreferrer')
      } catch (_) {
        try {
          const a = document.createElement('a')
          a.href = String(url)
          a.target = '_blank'
          a.rel = 'noopener noreferrer'
          document.body.appendChild(a)
          a.click()
          document.body.removeChild(a)
        } catch (e) {
          window.location.href = String(url)
        }
      }
    }

    const goToHost = async (keyword) => {
      try {
        await ensureHostIndex()
        let id = null
        const info = resolveHostInfo(keyword)
        if (info && info.id) id = info.id
        if (!id) {
          const ip = parseIp(String(keyword))
          const hintName = ip ? hintHostNameByIp(ip) : ''
          const terms = [String(keyword)]
          if (hintName) terms.push(hintName)
          for (let tIdx = 0; tIdx < terms.length && !id; tIdx += 1) {
            const resp = await api('listHosts', {
              keyword: terms[tIdx],
              listAll: true,
              listall: true,
              page: 1,
              pageSize: 50,
              pagesize: 50
            })
            const rows = extractHosts(resp)
            for (let i = 0; i < rows.length; i += 1) {
              const h = rows[i] || {}
              const name = takeFirst(h.name, h.hostname, h.hostName)
              const hip = takeFirst(
                h.ipaddress, h.ipAddress, h.hostip, h.hostIp,
                h.privateipaddress, h.privateIpAddress
              )
              if (name === terms[tIdx] || hip === terms[tIdx] || (ip && hip === ip)) {
                id = takeFirst(h.id, h.uuid)
                if (id) break
              }
            }
          }
        }
        const url = id ? hrefHostDetail(id) : hrefHostList(keyword)
        window.location.href = url
      } catch (_) {
        window.location.href = hrefHostList(keyword)
      }
    }

    const resolveVmId = async (keyword) => {
      try {
        await ensureVmIndex()
        const key = String(keyword).trim()
        if (vmIndexCache.byName.has(key)) return vmIndexCache.byName.get(key)
        const ip = parseIp(key)
        if (ip && vmIndexCache.byIp.has(ip)) return vmIndexCache.byIp.get(ip)
        let resp = await api('listVirtualMachines', {
          name: key,
          listAll: true,
          listall: true,
          page: 1,
          pageSize: 200,
          pagesize: 200
        })
        let rows = extractVMs(resp)
        for (let i = 0; i < rows.length; i += 1) {
          const v = rows[i] || {}
          const nm = takeFirst(v.name, v.displayname, v.displayName)
          if (nm === key) return takeFirst(v.id, v.uuid)
        }
        resp = await api('listVirtualMachines', {
          keyword: key,
          listAll: true,
          listall: true,
          page: 1,
          pageSize: 200,
          pagesize: 200
        })
        rows = extractVMs(resp)
        for (let i = 0; i < rows.length; i += 1) {
          const v = rows[i] || {}
          const nm = takeFirst(v.name, v.displayname, v.displayName)
          const iname = String(v.instancename || v.instanceName || '')
          if (iname === key || nm === key) return takeFirst(v.id, v.uuid)
        }
        for (let i = 0; i < rows.length; i += 1) {
          const v = rows[i] || {}
          const nm = takeFirst(v.name, v.displayname, v.displayName, v.instancename, v.instanceName)
          if (String(nm).toLowerCase().includes(key.toLowerCase())) {
            return takeFirst(v.id, v.uuid)
          }
        }
        return null
      } catch (_) {
        return null
      }
    }

    const goToVm = async (keyword) => {
      const id = await resolveVmId(keyword)
      const url = id ? hrefVmDetail(id) : hrefVmList(keyword)
      if (!id) message.warning(t('message.vm.resolve.fallback') || '정확한 VM ID를 찾지 못해 목록으로 이동합니다.')
      try {
        window.location.href = url
      } catch (e) {
        message.warning(t('message.link.open.failed') || '링크 열기에 실패했습니다. 콘솔 로그의 URL을 확인하세요.')
      }
    }

    function getPopupParent (triggerNode) {
      try {
        return (triggerNode && triggerNode.ownerDocument && triggerNode.ownerDocument.body) || document.body
      } catch (e) {
        return typeof document !== 'undefined' ? document.body : undefined
      }
    }

    function onVisibility () {
      if (document.hidden) {
        if (timer) { clearInterval(timer); timer = null }
      } else {
        if (!timer) {
          refresh()
          timer = setInterval(refresh, 60000)
        }
      }
    }

    onMounted(async () => {
      await refresh()
      timer = setInterval(refresh, 60000)
      document.addEventListener('visibilitychange', onVisibility)
    })
    onBeforeUnmount(() => {
      if (timer) clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibility)
    })

    return {
      // state
      loading,

      // Silence Modal
      silenceModal,
      openSilence,
      closeSilence,
      onSilenceRefresh,

      // computed
      showBanner,
      alertingCount: computed(() => (Array.isArray(visibleAlerts.value) ? visibleAlerts.value.length : 0)),
      visibleAlerts,

      // 링크 렌더 데이터
      hostLinkList,
      hostMoreCount,
      vmLinkList,
      vmRestList,
      vmMoreCount,
      storageLinkList,
      cloudLinkList,

      // URL 바인딩
      hrefHostList,
      hrefVmList,
      hostMoreHref,
      vmMoreHref,
      alertRulesHref,
      hrefAlertRule,

      // 네비게이션/헬퍼
      openUrl,
      openUrlBlank,
      goToHost,
      goToVm,

      // 일시정지/사일런스 상태
      pauseRule,
      isSilencing,
      isPausing,

      // 기타
      getPopupParent,
      refresh,
      isKeySilencedNow
    }
  }
}
</script>

<style scoped>
.auto-alert-banner-container {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 2147483647;
  width: 100%;
}
.banner-list { display: grid; }
.banner-list > * { grid-area: 1 / 1; }

/* ▼ 배너 전용 스타일: 모달에는 적용 안 되도록 범위 한정 ▼ */
.auto-alert-banner-container :deep(.ant-alert),
.auto-alert-banner-container ::v-deep(.ant-alert) {
  display: flex !important;
  align-items: center !important;
  justify-content: flex-start !important;
  gap: 8px !important;
  width: 100%;
}
.auto-alert-banner-container :deep(.ant-alert-with-icon),
.auto-alert-banner-container ::v-deep(.ant-alert-with-icon) { padding-left: 0 !important; }
.auto-alert-banner-container :deep(.ant-alert-icon),
.auto-alert-banner-container ::v-deep(.ant-alert-icon) {
  position: static !important;
  float: none !important;
  margin: 0 8px 0 0 !important;
}
.auto-alert-banner-container :deep(.ant-alert-content),
.auto-alert-banner-container ::v-deep(.ant-alert-content) {
  margin-left: auto !important;
  display: flex !important;
  justify-content: flex-end !important;
  align-items: center !important;
  padding-right: 0 !important;
}
.auto-alert-banner-container :deep(.ant-alert-close-icon),
.auto-alert-banner-container ::v-deep(.ant-alert-close-icon) {
  position: static !important;
  float: none !important;
  margin-left: 8px !important;
  cursor: pointer;
}
/* ▲ 범위 한정 끝 ▲ */

.banner-content { display: flex; justify-content: flex-end; align-items: center; gap: 12px; flex-wrap: wrap; line-height: 1.7; text-align: right; }
:deep(.ant-alert) { position: relative; }

/* 가운데 텍스트는 포인터 막고, 칩 컨테이너는 허용 */
.banner-text {
  position: absolute; left: 50%; transform: translateX(-50%);
  display: inline-flex; align-items: center; gap: 6px; text-align: center;
  line-height: 1.7; max-width: calc(100% - 160px); padding: 0 4px;
  pointer-events: none;
}
.banner-hosts, .banner-vms, .banner-storage, .banner-cloud { margin-left: 8px; font-weight: 500; pointer-events: auto; }

/* 태그 래퍼 */
.chip-wrap { display: inline-flex; gap: 2px; margin-left: 4px; vertical-align: middle; }

/* 태그 공통 인터랙션 */
:deep(.ant-tag.tag-link),
:deep(.ant-tag.tag-more) {
  cursor: pointer;
  user-select: none;
}

:deep(.ant-tag.tag-more) {
  border-style: dashed;
  opacity: 0.9;
}

.banner-error-icon { font-size: 16px; color: #ff4d4f; flex: 0 0 auto; }

/* 배너 높이 살짝 키우기 */
:deep(.ant-alert) { min-height: 45px; }
:deep(.ant-alert-content) { padding-top: 8px !important; padding-bottom: 8px !important; }

/* 태그 줄바꿈 시 간격 조금 확보 */
.banner-content { row-gap: 8px; }

.icon-stack {
  position: relative;
  display: inline-flex;
  width: 16px;
  height: 16px;
  margin-right: 4px;
  vertical-align: -2px;
}
.icon-stack .icon-sound { font-size: 16px; line-height: 16px; }

/* Pause 버튼만 더 컴팩트하게 */
:deep(.pause-btn.pause-compact.ant-btn) {
  height: 22px;
  padding: 0 6px;
  font-size: 14px;
  line-height: 18px;
}
</style>

<!-- 전역: message를 배너보다 위로 -->
<style>
.auto-message-host {
  position: fixed;
  top: 0; left: 0; width: 100%;
  z-index: 2147483650;
}
.auto-message-host .ant-message,
.auto-message-host .ant-notification {
  z-index: 2147483651 !important;
}
</style>
