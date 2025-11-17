<!-- AutoAlertBanner.vue (사일런스·일시정지 시 즉시 소프트클로즈 + 높이 반영 / ESLint 오류 수정본) -->
<template>
  <teleport to="body">
    <div
      v-if="showBanner"
      class="auto-alert-banner-container"
      :class="{ 'has-banner': showBanner, 'mask-on': maskOn }"
      ref="containerRef"
    >
      <div class="banner-list" ref="listRef">
        <a-alert
          v-for="it in visibleAlerts"
          :key="it.uid || it.id"
          :data-key="it.uid || it.id"
          :type="'error'"
          :show-icon="false"
          :closable="true"
          :banner="true"
          :style="[{ border: '1px solid #ffa39e', background: '#fff1f0' }]"
          @close="() => onAlertCloseStart(it)"
          @afterClose="() => onAlertClosed(it)"
        >
          <template #message>
            <div class="banner-content" style="display:flex; justify-content:flex-end; align-items:center; gap:12px; flex-wrap:wrap; text-align:right;">
              <span class="banner-text">
                <ExclamationCircleFilled class="banner-error-icon" />
                {{ $t('label.alert') || '경고' }} "{{ it && it.title ? it.title : ($t('label.alert') || '경고') }}" {{ $t('message.alerting') || '경고 발생 중입니다.' }}

                <!-- 문제 호스트 -->
                <span v-if="hostLinkList(it).length" class="banner-field banner-hosts">
                  <span class="field-key">{{ $t('label.targets.hosts') || '대상 호스트' }}</span>
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
                <span v-if="vmLinkList(it).length" class="banner-field banner-vms">
                  <span class="field-key">{{ $t('label.targets.vms') || '대상 VM' }}</span>
                  <span class="chip-wrap">
                    <a-tag
                      v-for="lnk in vmLinkList(it)"
                      :key="lnk.key"
                      class="tag-link"
                      :data-vmindex="vmIndexVersion"
                      @click.prevent.stop="goToVm(lnk.keyword)"
                      :title="`${$t('tooltip.goto.vm.detail') || 'VM 상세로 이동'}: ${displayVm(lnk.label)}`"
                    >
                      {{ displayVm(lnk.label) }}
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
                            :title="`${$t('tooltip.goto.vm.detail') || 'VM 상세로 이동'}: ${displayVm(lnk.label)}`"
                          >
                            {{ displayVm(lnk.label) }}
                          </a-tag>
                        </div>
                      </template>
                      <a-tag class="tag-more">+{{ vmMoreCount(it) }}</a-tag>
                    </a-popover>
                  </span>
                </span>

                <!-- 문제 스토리지 -->
                <span v-if="storageLinkList(it).length" class="banner-field banner-storage">
                  <span class="field-key">{{ $t('label.targets.storage.controller') || '대상 스토리지 컨트롤러' }}</span>
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
                <span v-if="cloudLinkList(it).length" class="banner-field banner-cloud">
                  <span class="field-key">{{ $t('label.targets.management') || '대상 관리 서버' }}</span>
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
                <a
                  :href="hrefAlertRule(it)"
                  class="router-link-button"
                  :title="`${$t('label.goto.the.alertRule') || '경고 규칙으로 이동'}: ${it?.title || ''}`"
                >
                  <a-button size="small" type="link">
                    <template #icon><LinkOutlined /></template>
                    {{ $t('label.goto.the.alertRules') || '경고 규칙으로 이동' }}
                  </a-button>
                </a>

                <!-- Silence -->
                <a-button
                  v-if="!isKeySilencedNow(it && it.uid)"
                  size="small"
                  class="silence-menu"
                  :disabled="!it || !it.uid"
                  @click.stop="openSilence(it)"
                >
                  <span class="icon-stack">
                    <SoundOutlined class="icon-sound" />
                  </span>
                  {{ $t('label.action.silence') || 'Silence' }}
                </a-button>

                <!-- Pause -->
                <a-button
                  size="small"
                  class="pause-btn pause-compact"
                  danger
                  :disabled="!it || !it.uid"
                  @click.stop="openPause(it)"
                >
                  <template #icon><PauseCircleOutlined /></template>
                  {{ $t('label.alert.rule.pause') || 'Pause' }}
                </a-button>
              </a-space>
            </div>
          </template>
        </a-alert>
      </div>
    </div>

    <!-- RuleSilenceModal -->
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

    <!-- RulePauseModal -->
    <a-modal
      v-model:visible="pauseModal.visible"
      :footer="null"
      :destroyOnClose="true"
      :maskClosable="false"
      :centered="true"
      :getContainer="getPopupParent"
      :zIndex="2147483652"
      width="480px"
      @afterClose="closePause"
    >
      <RulePauseModal
        v-if="pauseModal.visible"
        :resource="pauseModal.target"
        :selection="[]"
        :records="[]"
        @refresh-data="onPauseRefresh"
        @close-action="closePause"
      />
    </a-modal>
  </teleport>
</template>

<script>
import { ref, computed, onMounted, onBeforeUnmount, defineAsyncComponent, watch, nextTick } from 'vue'
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
    RuleSilenceModal: defineAsyncComponent(() => import('@/views/infra/RuleSilenceModal.vue')),
    RulePauseModal: defineAsyncComponent(() => import('@/views/infra/RulePauseModal.vue'))
  },
  setup () {
    const ORIGIN = typeof window !== 'undefined' ? window.location.origin : ''
    const HOST_BASE = ''
    const VM_BASE = '/client'

    // ===== 전역 알림 이벤트(글로벌 레이아웃이 듣습니다) =====
    const emitClosing = () => {
      try { window.dispatchEvent(new CustomEvent('auto-alert-banner:closing')) } catch (_) {}
    }
    const emitClosed = () => {
      try { window.dispatchEvent(new CustomEvent('auto-alert-banner:closed')) } catch (_) {}
    }

    // ===== 링크 유틸 =====
    const hrefHostDetail = (id) => `${ORIGIN}${HOST_BASE}/#/host/${id}`
    const hrefHostList = (keyword) => `${ORIGIN}${HOST_BASE}/#/hosts${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`
    const hrefVmDetail = (id) => `${ORIGIN}${VM_BASE}/#/vm/${id}`
    const hrefVmList = (keyword) => `${ORIGIN}${VM_BASE}/#/vm${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`
    const hrefCloudPage = () => `${ORIGIN}/#/managementserver`
    const alertRulesHref = `${ORIGIN}/#/alertRules`
    const hrefAlertRule = (item) => {
      const uid = item && (item.uid || ruleUid(item.rule) || item.id)
      return uid ? `${ORIGIN}/#/alertRules/${encodeURIComponent(uid)}` : `${ORIGIN}/#/alertRules`
    }
    const hostMoreHref = hrefHostList('')
    const vmMoreHref = hrefVmList('')
    const MAX_LINKS = 3

    // ===== 상태 =====
    const loading = ref(false)
    const rules = ref([])
    const refreshInFlight = ref(false)

    const keepShowing = ref(false)
    const HIDE_GRACE_MS = 150
    let hideTimer = null

    const maskOn = ref(false)

    // ===== 폴링 =====
    const POLL_MS = 60000
    const MIN_DELAY_MS = 5000
    let pollHandle = null
    let pollBusy = false

    function scheduleNextPoll () {
      if (pollHandle) { clearTimeout(pollHandle); pollHandle = null }
      let delay = POLL_MS - (Date.now() % POLL_MS)
      if (delay < MIN_DELAY_MS) { delay += POLL_MS }
      pollHandle = setTimeout(pollTick, delay)
    }

    async function pollTick () {
      if (pollBusy || refreshInFlight.value) { scheduleNextPoll(); return }
      pollBusy = true
      try { await refresh() } catch (_) {} finally { pollBusy = false; scheduleNextPoll() }
    }

    function startPoll () {
      stopPoll()
      let delay = POLL_MS - (Date.now() % POLL_MS)
      if (delay < MIN_DELAY_MS) { delay = MIN_DELAY_MS }
      pollHandle = setTimeout(pollTick, delay)
    }
    function stopPoll () { if (pollHandle) { clearTimeout(pollHandle); pollHandle = null } }

    function onVisibility () { if (document.hidden) { stopPoll() } else { pollTick(); startPoll() } }
    function onFocus () { if (!document.hidden) { pollTick() } }

    // ===== 사일런스 캐시(로컬/원격) =====
    const LS_KEY = 'autoAlertBanner.silencedByUid'
    const localSilenced = ref(loadLocalSilences())
    const remoteSilenced = ref({})
    const remoteSilencedLoaded = ref(false)
    const SILENCE_TTL_MS = 30 * 1000
    const silenceCache = new Map()

    const CLOSE_TTL_MS = 60 * 1000
    const closedUntil = ref(new Map())

    const pruneClosed = () => {
      if (!closedUntil.value || closedUntil.value.size === 0) { return }
      const now = Date.now()
      for (const [k, exp] of closedUntil.value.entries()) {
        if (now > exp) { closedUntil.value.delete(k) }
      }
    }
    const isClosedNow = (k) => {
      if (!k) { return false }
      const exp = closedUntil.value.get(k)
      return !!(exp && Date.now() <= exp)
    }

    // ===== 치수 측정 =====
    const containerRef = ref(null)
    const listRef = ref(null)
    let ro = null
    let rafId = 0
    const lastHeight = ref(-1)

    const measureAndNotifyHeight = async () => {
      await nextTick()
      try {
        const el = listRef.value
        const h = showBanner.value ? Math.ceil((el?.getBoundingClientRect().height || 0)) : 0
        if (h === lastHeight.value) {
          maskOn.value = h > 0
          return
        }
        lastHeight.value = h
        if (typeof window !== 'undefined') {
          document.documentElement.style.setProperty('--autoBannerHeight', `${h}px`)
          window.dispatchEvent(new CustomEvent('auto-alert-banner:height', { detail: { height: h } }))
        }
        maskOn.value = h > 0
        try { document.documentElement.classList.remove('banner-measuring') } catch (_) {}
      } catch (_) {}
    }

    const scheduleMeasure = () => {
      if (rafId) { cancelAnimationFrame(rafId) }
      rafId = requestAnimationFrame(() => { rafId = 0; measureAndNotifyHeight() })
    }

    // ===== 개별 배너 소프트 클로즈 =====
    const runCloseAnimation = (k) => {
      try {
        const root = listRef.value
        if (!root || !k) { return }
        const el = root.querySelector(`[data-key="${k}"]`)
        if (!el) { return }

        const h = el.getBoundingClientRect().height
        el.style.height = h + 'px'
        el.style.opacity = '1'
        el.style.overflow = 'hidden'
        el.style.transition = 'height 150ms ease, opacity 150ms ease'

        // 강제 리플로우
        el.getBoundingClientRect()

        el.style.height = '0px'
        el.style.opacity = '0'

        setTimeout(scheduleMeasure, 16)
        setTimeout(scheduleMeasure, 180)
      } catch (_) {}
    }

    const softCloseByUid = (uid) => {
      if (!uid) { return }
      runCloseAnimation(uid)
      closedUntil.value.set(uid, Date.now() + CLOSE_TTL_MS)
      scheduleMeasure()
    }

    // ===== 각종 파서 =====
    const UC = (v) => (v == null ? '' : String(v).toUpperCase())
    const pickState = (obj) => {
      if (!obj) { return '' }
      if (typeof obj === 'string') { return obj }
      if (typeof obj !== 'object') { return '' }
      if (typeof obj.state === 'string') { return obj.state }
      if (typeof obj.currentState === 'string') { return obj.currentState }
      if (typeof obj.evaluationState === 'string') { return obj.evaluationState }
      if (typeof obj.health === 'string') { return obj.health }
      if (typeof obj.status === 'string') { return obj.status }
      if (obj.state && typeof obj.state === 'object' && typeof obj.state.state === 'string') { return obj.state.state }
      if (obj.status && typeof obj.status === 'object' && typeof obj.status.state === 'string') { return obj.status.state }
      if (obj.grafana_alert && typeof obj.grafana_alert.state === 'string') { return obj.grafana_alert.state }
      if (obj.grafana_alert && typeof obj.grafana_alert.state === 'object' && typeof obj.grafana_alert.state.state === 'string') { return obj.grafana_alert.state.state }
      if (obj.alert && typeof obj.alert.state === 'string') { return obj.alert.state }
      if (obj.alert && typeof obj.alert.state === 'object' && typeof obj.alert.state.state === 'string') { return obj.alert.state.state }
      return ''
    }
    const normState = (v) => UC(pickState(v)).replace(/\s+/g, '')
    const isNoiseLike = (v) => {
      const s = normState(v)
      return s === 'NODATA' || s === 'UNKNOWN' || s === 'PENDING' || s === 'OK' || s === 'NORMAL' || s === 'INACTIVE'
    }
    const parseIp = (s) => {
      if (!s) { return '' }
      const m = String(s).match(/(\d{1,3}(?:\.\d{1,3}){3})/)
      return m ? m[1] : ''
    }
    function takeFirst (...args) {
      for (let i = 0; i < args.length; i += 1) {
        const v = args[i]
        if (v != null && v !== '') { return v }
      }
      return ''
    }

    // ===== CloudStack 인덱스(호스트/VM) =====
    const extractHosts = (resp) => {
      const wrap = resp?.listhostsresponse || resp?.listHostsResponse || resp?.data || resp || {}
      let list = wrap?.host || wrap?.hosts || wrap?.items || wrap?.list
      if (!Array.isArray(list)) {
        for (const k in wrap) { if (Array.isArray(wrap[k])) { list = wrap[k]; break } }
      }
      return Array.isArray(list) ? list : []
    }
    const extractVMs = (resp) => {
      const wrap = resp?.listvirtualmachinesresponse || resp?.listVirtualMachinesResponse || resp?.data || resp || {}
      let list = wrap?.virtualmachine || wrap?.virtualMachine || wrap?.virtualmachines || wrap?.virtualMachines || wrap?.items || wrap?.list
      if (!Array.isArray(list)) {
        for (const k in wrap) { if (Array.isArray(wrap[k])) { list = wrap[k]; break } }
      }
      return Array.isArray(list) ? list : []
    }

    const hostIndexCache = { until: 0, byIp: new Map(), byName: new Map() }
    const vmIndexCache = { until: 0, byIp: new Map(), byName: new Map(), byInstanceName: new Map() }

    const ensureHostIndex = async () => {
      const now = Date.now()
      if (hostIndexCache.until > now) { return }
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
          const ip = takeFirst(h.ipaddress, h.ipAddress, h.hostip, h.hostIp, h.privateipaddress, h.privateIpAddress)
          if (!id) { continue }
          const info = { id: String(id), name: name ? String(name) : '' }
          if (name) { hostIndexCache.byName.set(String(name), info) }
          if (ip) { hostIndexCache.byIp.set(String(ip), info) }
        }
        hostIndexCache.until = now + 5 * 60 * 1000
      } catch (_) {
        hostIndexCache.until = now + 60 * 1000
      }
    }

    const ensureVmIndex = async () => {
      const now = Date.now()
      if (vmIndexCache.until > now) { return }
      try {
        const params = { listAll: true, listall: true, details: 'all', page: 1, pageSize: 200, pagesize: 200 }
        const resp = await api('listVirtualMachines', params)
        const rows = extractVMs(resp)

        vmIndexCache.byIp = new Map()
        vmIndexCache.byName = new Map()
        vmIndexCache.byInstanceName = new Map()

        for (let i = 0; i < rows.length; i += 1) {
          const v = rows[i] || {}
          const id = takeFirst(v.id, v.uuid)
          const friendly = takeFirst(v.displayname, v.displayName, v.name)
          const inst = takeFirst(v.instancename, v.instanceName)
          if (id) {
            if (friendly) { vmIndexCache.byName.set(String(friendly), String(id)) }
            if (inst && friendly) {
              const k1 = String(inst)
              const k2 = k1.toLowerCase()
              vmIndexCache.byInstanceName.set(k1, String(friendly))
              vmIndexCache.byInstanceName.set(k2, String(friendly))
            }
            const nics = Array.isArray(v.nic) ? v.nic : (Array.isArray(v.nics) ? v.nics : [])
            for (let j = 0; j < nics.length; j += 1) {
              const ip = takeFirst(nics[j]?.ipaddress, nics[j]?.ipAddress, nics[j]?.ip)
              if (ip) { vmIndexCache.byIp.set(String(ip), String(id)) }
            }
          }
        }
        vmIndexCache.until = now + 5 * 60 * 1000
      } catch (_) {
        vmIndexCache.until = now + 60 * 1000
      }
    }

    // ===== 호스트 힌트(이름<->IP) =====
    const hostHints = { byIpName: new Map() }
    const hintHostNameByIp = (ip) => {
      if (!ip) { return '' }
      const val = hostHints.byIpName.get(String(ip))
      return val ? String(val) : ''
    }
    const harvestHostHintsFromRules = (arrRules) => {
      for (let i = 0; i < arrRules.length; i += 1) {
        const insts = ruleInstances(arrRules[i])
        for (let j = 0; j < insts.length; j += 1) {
          const a = insts[j] || {}
          const L = labelBag(a)
          const name = takeFirst(L.nodename, L.host, L.hostname, L.node, L.machine, L.server)
          const ip = parseIp(takeFirst(L.instance, L.ip, L.address))
          if (name && ip) { hostHints.byIpName.set(String(ip), String(name)) }
        }
      }
    }

    // ===== 응답 파서 =====
    const extractRules = (resp) => {
      const wrap = resp?.listwallalertrulesresponse || resp?.listWallAlertRulesResponse || resp?.data || resp || {}
      const inner = wrap.wallalertruleresponse || wrap.wallalertrules || wrap.wallAlertRules || wrap.rules || wrap.items || wrap.list || wrap
      if (Array.isArray(inner)) { return inner }
      let rows = inner?.wallalertrule || inner?.wallAlertRule || inner?.rule || inner?.rules || inner?.items || inner?.list || []
      if (!Array.isArray(rows) && inner && typeof inner === 'object') {
        for (const k of Object.keys(inner)) { if (Array.isArray(inner[k])) { rows = inner[k]; break } }
      }
      if (!Array.isArray(rows)) { rows = rows ? [rows] : [] }
      return rows
    }

    const ruleInstances = (r) => {
      if (!r) { return [] }
      const st = r.status && typeof r.status === 'object' ? r.status : null
      if (st && Array.isArray(st.alerts)) { return st.alerts }
      if (st && Array.isArray(st.instances)) { return st.instances }
      if (Array.isArray(r.alerts)) { return r.alerts }
      if (Array.isArray(r.instances)) { return r.instances }
      return []
    }
    const instanceState = (a) => normState(pickState(a) || pickState(a && a.evaluationState) || pickState(a && a.health) || pickState(a && a.status))
    const ruleState = (r) => normState(pickState(r && r.state) || pickState(r && r.status) || pickState(r && r.evaluationState) || pickState(r && r.health) || pickState(r))
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

    // ===== 로컬 사일런스 저장/정리 =====
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
          if (localSilenced.value[k] > now) { next[k] = localSilenced.value[k] }
        }
      }
      localSilenced.value = next
      saveLocalSilences()
    }

    const extractSilences = (resp) => {
      const wrap = resp?.listwallalertsilencesresponse || resp?.listWallAlertSilencesResponse || resp?.data || resp || {}
      let list = wrap?.silences || wrap?.silence || wrap?.items || wrap?.list
      if (!Array.isArray(list)) {
        for (const k in wrap) { if (Array.isArray(wrap[k])) { list = wrap[k]; break } }
      }
      return Array.isArray(list) ? list : []
    }

    const syncRemoteSilencesByUidList = async (uids) => {
      remoteSilencedLoaded.value = false
      if (!Array.isArray(uids) || !uids.length) { remoteSilenced.value = {}; remoteSilencedLoaded.value = true; return }
      const uniq = Array.from(new Set(uids.map(String).filter(Boolean)))
      const now = Date.now()
      const needFetch = []
      const mapFromCache = {}
      for (let i = 0; i < uniq.length; i += 1) {
        const uid = uniq[i]
        const cached = silenceCache.get(uid)
        if (cached && cached.until > now) {
          if (cached.end > now) { mapFromCache[uid] = cached.end }
        } else {
          needFetch.push(uid)
        }
      }
      const CONC = 4
      const chunks = []
      for (let i = 0; i < needFetch.length; i += CONC) { chunks.push(needFetch.slice(i, i + CONC)) }
      for (let c = 0; c < chunks.length; c += 1) {
        const group = chunks[c]
        await Promise.all(group.map(async (u) => {
          try {
            const params = { page: 1, pageSize: 200, 'labels[0].key': '__alert_rule_uid__', 'labels[0].value': u }
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
              if ((state === 'active' || (start && start <= now)) && end > now) { activeEnd = Math.max(activeEnd, end) }
            }
            silenceCache.set(u, { end: activeEnd, until: Date.now() + SILENCE_TTL_MS })
            if (activeEnd > now) { mapFromCache[u] = activeEnd }
          } catch (_) {
            silenceCache.set(u, { end: 0, until: Date.now() + 10 * 1000 })
          }
        }))
      }
      remoteSilenced.value = mapFromCache
      remoteSilencedLoaded.value = true
    }

    // ===== 엔티티 링크 구성 =====
    const VM_NAME_RE = /^i-\d+-\d+-VM$/i
    const VM_NAME_FUZZY_RE = /-VM$/i
    const labelBag = (a) => (a && (a.labels || a.metric || a.tags || a)) || {}

    const bestHostOfInstance = (a) => {
      const L = labelBag(a)
      const prefers = ['nodename', 'host', 'hostname', 'node', 'machine', 'server']
      for (let i = 0; i < prefers.length; i += 1) {
        const k = prefers[i]
        if (L && L[k]) { return String(L[k]) }
      }
      if (L && L.instance) { return String(L.instance).replace(/:\d+$/, '') }
      return ''
    }

    const bestVmOfInstance = (a) => {
      const L = labelBag(a)
      const keys = ['vm', 'vmname', 'vm_name', 'displayname', 'display_name', 'guest', 'domain']
      for (let i = 0; i < keys.length; i += 1) {
        const k = keys[i]
        if (!L || !L[k]) { continue }
        const v = String(L[k])
        if (VM_NAME_RE.test(v) || VM_NAME_FUZZY_RE.test(v)) { return v }
      }
      return ''
    }

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

    const labelBag2 = (a) => (a && (a.labels || a.metric || a.tags || a)) || {}
    const resolveHostInfo = (keyword) => {
      if (!keyword) { return null }
      const key = String(keyword)
      const ip = parseIp(key)
      if (ip && hostIndexCache.byIp.has(ip)) { return hostIndexCache.byIp.get(ip) }
      const hintName = ip ? hintHostNameByIp(ip) : ''
      if (hintName && hostIndexCache.byName.has(hintName)) { return hostIndexCache.byName.get(hintName) }
      if (hostIndexCache.byName.has(key)) { return hostIndexCache.byName.get(key) }
      return null
    }
    const hostDisplayLabel = (keyword) => {
      const info = resolveHostInfo(keyword)
      if (info && info.name) { return info.name }
      const ip = parseIp(String(keyword))
      const hinted = hintHostNameByIp(ip)
      if (hinted) { return hinted }
      return ip || String(keyword)
    }
    const hostDedupKey = (keyword) => {
      const info = resolveHostInfo(keyword)
      if (info && info.id) { return 'host#' + info.id }
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
      const L = labelBag2(a)
      const ownKind = L.kind || L.KIND
      const finalKind = takeFirst(ownKind, parentKind)

      const domainName = L.domain ? String(L.domain) : ''
      if (isVmKind(finalKind) || (domainName && /-VM$/i.test(domainName))) {
        if (domainName) { return { kind: 'vm', keyword: domainName } }
        const vmByOther = bestVmOfInstance(a)
        if (vmByOther) { return { kind: 'vm', keyword: vmByOther } }
      }

      if (isStorageKind(finalKind)) {
        const label = storageLabel(a)
        const url = storageUrlByInstance(a)
        if (label && url) { return { kind: 'storage', label, url } }
      }

      if (isCloudKind(finalKind)) {
        const label = cloudLabel(a)
        const url = cloudUrl()
        if (label && url) { return { kind: 'cloud', label, url } }
      }

      const host = bestHostOfInstance(a)
      if (host) { return { kind: 'host', keyword: host } }
      return null
    }

    const entityLinksForAlert = (it) => {
      const parentKind = pickKindFromRule(it.rule || {})
      const arr = Array.isArray(it?.alerts) ? it.alerts : []
      const seen = new Set()
      const out = []

      for (let i = 0; i < arr.length; i += 1) {
        const cls = classifyInstance(arr[i], parentKind)
        if (!cls) { continue }

        if (cls.kind === 'host') {
          const key = hostDedupKey(cls.keyword)
          if (seen.has(key)) { continue }
          seen.add(key)
          out.push({ key, kind: 'host', label: hostDisplayLabel(cls.keyword), keyword: cls.keyword })
          continue
        }

        if (cls.kind === 'vm') {
          const key = 'vm@' + String(cls.keyword).toLowerCase()
          if (seen.has(key)) { continue }
          seen.add(key)
          out.push({ key, kind: 'vm', label: String(cls.keyword), keyword: cls.keyword })
          continue
        }

        if (cls.kind === 'storage') {
          const key = 'storage@' + String(cls.label).toLowerCase()
          if (seen.has(key)) { continue }
          seen.add(key)
          out.push({ key, kind: 'storage', label: cls.label, url: cls.url })
          continue
        }

        if (cls.kind === 'cloud') {
          const key = 'cloud@' + String(cls.label).toLowerCase()
          if (seen.has(key)) { continue }
          seen.add(key)
          out.push({ key, kind: 'cloud', label: cls.label, url: cls.url })
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

    const t = (k, params) => {
      try {
        const fn = (typeof window !== 'undefined' && typeof window.$t === 'function') ? window.$t : null
        return fn ? fn(k, params) : null
      } catch (_) { return null }
    }

    const isRulePaused = (r) => {
      if (!r) { return false }
      const v =
        r.isPaused ||
        r.paused ||
        (r.grafana_alert && r.grafana_alert.isPaused) ||
        (r.alert && r.alert.isPaused) ||
        (r.status && r.status.isPaused)
      return !!v
    }

    const isKeySilencedNow = (k) => {
      if (!k) { return false }
      const now = Date.now()
      if (remoteSilencedLoaded.value) {
        return !!(remoteSilenced.value && remoteSilenced.value[k] > now)
      }
      return !!(
        (localSilenced.value && localSilenced.value[k] > now) ||
        (remoteSilenced.value && remoteSilenced.value[k] > now)
      )
    }

    // ===== 렌더 목록 =====
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
        if (!on.length && !ruleIsAlerting) { continue }
        const uid = ruleUid(r) || r.id || r.ruleId
        if (!uid || seen.has(uid)) { continue }
        seen.add(uid)
        out.push({ id: r.id || r.ruleId || uid, uid, title: ruleTitle(r), rule: r, alerts: on })
      }
      return out
    })

    const visibleAlerts = computed(() => {
      pruneClosed()
      const list = Array.isArray(alerting.value) ? alerting.value : []
      const seen = new Set()
      const out = []
      for (let i = 0; i < list.length; i += 1) {
        const it = list[i]
        const uid = it && (it.uid || it.id)
        if (!uid || seen.has(uid)) { continue }
        if (isClosedNow(uid)) { seen.add(uid); continue }
        if (isKeySilencedNow(uid)) { seen.add(uid); continue }
        if (isRulePaused(it.rule)) { seen.add(uid); continue }
        out.push(it)
        seen.add(uid)
      }
      return out
    })

    const showBanner = computed(() => {
      const hasList = Array.isArray(visibleAlerts.value) && visibleAlerts.value.length > 0
      return (keepShowing.value || remoteSilencedLoaded.value) && hasList
    })

    const vmIndexVersion = ref(0)

    const displayVm = (raw) => {
      if (!raw) { return '' }
      const trimBasics = (s) => {
        let t = String(s).trim()
        t = t.replace(/^[a-z]+:\/\//i, '')
        t = t.replace(/:\d+$/, '')
        t = t.split('/')[0]
        t = t.split('@').pop()
        t = t.split(/\s+/)[0]
        t = t.split('.')[0]
        return t
      }
      const k0 = String(raw)
      const k1 = trimBasics(k0)
      const k2 = k1.toLowerCase()
      const k3 = k0.toLowerCase()
      const hit =
        vmIndexCache.byInstanceName.get(k0) ||
        vmIndexCache.byInstanceName.get(k1) ||
        vmIndexCache.byInstanceName.get(k2) ||
        vmIndexCache.byInstanceName.get(k3)
      if (hit) { return hit }
      return String(raw)
    }

    // ===== 데이터 갱신 =====
    const refresh = async () => {
      if (refreshInFlight.value) { return }
      refreshInFlight.value = true

      if (Array.isArray(visibleAlerts.value) && visibleAlerts.value.length > 0) {
        keepShowing.value = true
      }
      if (hideTimer) { clearTimeout(hideTimer); hideTimer = null }

      loading.value = true
      try {
        const params = { includeStatus: true, includestatus: true, listAll: true, listall: true, state: '', kind: '', name: '', page: 1, pageSize: 200, pagesize: 200 }
        const resp = await api('listWallAlertRules', params)
        rules.value = extractRules(resp)

        await Promise.all([ensureHostIndex(), ensureVmIndex()])
        vmIndexVersion.value += 1

        harvestHostHintsFromRules(Array.isArray(rules.value) ? rules.value : [])

        const uids = (Array.isArray(rules.value) ? rules.value : []).map((r) => ruleUid(r) || r.id || r.ruleId)
        await syncRemoteSilencesByUidList(uids)

        cleanupLocalSilences()
        pruneClosed()
      } finally {
        loading.value = false
        refreshInFlight.value = false
        hideTimer = setTimeout(() => { keepShowing.value = false }, HIDE_GRACE_MS)
        measureAndNotifyHeight()
      }
    }

    // ===== 모달 열고 닫기 =====
    const silenceModal = ref({ visible: false, target: null })
    const openSilence = (it) => {
      const title = (it && it.title) || (it && it.rule && ruleTitle(it.rule)) || it?.name || ''
      const target = { ...it, name: title }
      silenceModal.value = { visible: true, target }
    }
    const closeSilence = () => { silenceModal.value = { visible: false, target: null } }

    // ✅ 사일런스 적용 직후: 해당 UID 소프트클로즈 + 로컬 캐시 반영 + 새로고침
    const onSilenceRefresh = async (info) => {
      try {
        const uidFromModal = info && (info.uid || info.ruleUid)
        const uidFromTarget = (silenceModal.value && silenceModal.value.target && (silenceModal.value.target.uid || silenceModal.value.target.id)) || null
        const uid = uidFromModal || uidFromTarget

        if (uid) {
          // 즉시 소프트 클로즈
          onAlertCloseStart({ uid })

          // 로컬 사일런스 캐시(정보가 없으면 3분 보호)
          const now = Date.now()
          let endMs = 0
          if (info && typeof info.endsAt === 'string') { endMs = Date.parse(info.endsAt) || 0 }
          if (!endMs && info && typeof info.endMs === 'number') { endMs = info.endMs }
          if (!endMs && info && typeof info.durationMinutes === 'number') { endMs = now + Math.max(1, info.durationMinutes) * 60 * 1000 }
          if (!endMs) { endMs = now + 3 * 60 * 1000 }

          localSilenced.value[uid] = endMs
          saveLocalSilences()
        }

        closeSilence()
        await refresh()
        message.success(window.$t?.('message.silence.applied') || '사일런스 적용')
      } catch (_) {
        closeSilence()
      }
    }

    const pauseModal = ref({ visible: false, target: null })
    const openPause = (it) => {
      const title = (it && it.title) || (it && it.rule && ruleTitle(it.rule)) || it?.name || ''
      const target = { ...it, name: title }
      console.log('[AutoAlertBanner] openPause target ->', target)
      pauseModal.value = { visible: true, target }
    }
    const closePause = () => { pauseModal.value = { visible: false, target: null } }
    const onPauseRefresh = async () => {
      const uid = keyOf(pauseModal.value && pauseModal.value.target)
      if (uid) { onAlertCloseStart({ uid }) }
      closePause()
      await refresh()
      message.success(window.$t?.('message.pause.applied') || '일시정지 적용')
    }

    // ===== 내비게이션 =====
    function openUrl (url) { try { window.location.href = String(url) } catch (_) {} }
    function openUrlBlank (url) {
      try { window.open(String(url), '_blank', 'noopener,noreferrer') } catch (_) {
        try {
          const a = document.createElement('a')
          a.href = String(url)
          a.target = '_blank'
          a.rel = 'noopener noreferrer'
          document.body.appendChild(a)
          a.click()
          document.body.removeChild(a)
        } catch (e) { window.location.href = String(url) }
      }
    }

    const goToHost = async (keyword) => {
      try {
        await ensureHostIndex()
        let id = null
        const info = resolveHostInfo(keyword)
        if (info && info.id) { id = info.id }
        if (!id) {
          const ip = parseIp(String(keyword))
          const hintName = ip ? hintHostNameByIp(ip) : ''
          const terms = [String(keyword)]
          if (hintName) { terms.push(hintName) }
          for (let tIdx = 0; tIdx < terms.length && !id; tIdx += 1) {
            const resp = await api('listHosts', { keyword: terms[tIdx], listAll: true, listall: true, page: 1, pageSize: 50, pagesize: 50 })
            const rows = extractHosts(resp)
            for (let i = 0; i < rows.length; i += 1) {
              const h = rows[i] || {}
              const name = takeFirst(h.name, h.hostname, h.hostName)
              const hip = takeFirst(h.ipaddress, h.ipAddress, h.hostip, h.hostIp, h.privateipaddress, h.privateIpAddress)
              if (name === terms[tIdx] || hip === terms[tIdx] || (ip && hip === ip)) {
                id = takeFirst(h.id, h.uuid)
                if (id) { break }
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
        if (vmIndexCache.byName.has(key)) { return vmIndexCache.byName.get(key) }
        const ip = parseIp(key)
        if (ip && vmIndexCache.byIp.has(ip)) { return vmIndexCache.byIp.get(ip) }
        let resp = await api('listVirtualMachines', { name: key, listAll: true, listall: true, page: 1, pageSize: 200, pagesize: 200 })
        let rows = extractVMs(resp)
        for (let i = 0; i < rows.length; i += 1) {
          const v = rows[i] || {}
          const nm = takeFirst(v.name, v.displayname, v.displayName)
          if (nm === key) { return takeFirst(v.id, v.uuid) }
        }
        resp = await api('listVirtualMachines', { keyword: key, listAll: true, listall: true, page: 1, pageSize: 200, pagesize: 200 })
        rows = extractVMs(resp)
        for (let i = 0; i < rows.length; i += 1) {
          const v = rows[i] || {}
          const nm = takeFirst(v.name, v.displayname, v.displayName)
          const iname = String(v.instancename || v.instanceName || '')
          if (iname === key || nm === key) { return takeFirst(v.id, v.uuid) }
        }
        for (let i = 0; i < rows.length; i += 1) {
          const v = rows[i] || {}
          const nm = takeFirst(v.name, v.displayname, v.displayName, v.instancename, v.instanceName)
          if (String(nm).toLowerCase().includes(key.toLowerCase())) { return takeFirst(v.id, v.uuid) }
        }
        return null
      } catch (_) { return null }
    }

    const goToVm = async (keyword) => {
      const id = await resolveVmId(keyword)
      const url = id ? hrefVmDetail(id) : hrefVmList(keyword)
      if (!id) { message.warning(t('message.vm.resolve.fallback') || '정확한 VM ID를 찾지 못해 목록으로 이동합니다.') }
      try { window.location.href = url } catch (e) {
        message.warning(t('message.link.open.failed') || '링크 열기에 실패했습니다. 콘솔 로그의 URL을 확인하세요.')
      }
    }

    // ===== 공용 =====
    const keyOf = (it) => (it && (it.uid || it.id)) || null
    function getPopupParent (triggerNode) {
      try {
        const parent = triggerNode && triggerNode.ownerDocument && triggerNode.ownerDocument.body
        return parent || document.body
      } catch (e) {
        return typeof document !== 'undefined' ? document.body : undefined
      }
    }

    // ===== 배너 닫힘 핸들러(누락으로 인한 ESLint 오류 해결) =====
    const onAlertCloseStart = (it) => {
      const k = keyOf(it)
      if (!k) { return }
      emitClosing()
      softCloseByUid(k)
    }
    const onAlertClosed = () => {
      emitClosed()
      scheduleMeasure()
    }

    // ===== 라이프사이클 =====
    onMounted(async () => {
      try { document.documentElement.style.setProperty('--autoBannerHeight', '0px') } catch (_) {}
      try { document.documentElement.classList.add('banner-measuring') } catch (_) {}

      const killMeasure = setTimeout(() => {
        try { document.documentElement.classList.remove('banner-measuring') } catch (_) {}
      }, 300)

      await refresh()
      startPoll()
      document.addEventListener('visibilitychange', onVisibility)
      window.addEventListener('focus', onFocus)

      try {
        if (typeof window !== 'undefined' && 'ResizeObserver' in window) {
          ro = new ResizeObserver(() => scheduleMeasure())
          if (listRef.value) { ro.observe(listRef.value) }
        }
      } catch (_) {}

      scheduleMeasure()

      try { window.__bannerPoll = { startPoll, stopPoll, pollTick } } catch (_) {}

      setTimeout(() => {
        try { clearTimeout(killMeasure) } catch (_) {}
      }, 400)
    })
    onBeforeUnmount(() => {
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('focus', onFocus)
      stopPoll()
      try { if (ro) { ro.disconnect() } } catch (_) {}
      if (rafId) { cancelAnimationFrame(rafId) }
      if (hideTimer) { clearTimeout(hideTimer); hideTimer = null }
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('auto-alert-banner:height', { detail: { height: 0 } }))
        document.documentElement.style.setProperty('--autoBannerHeight', '0px')
      }
      maskOn.value = false
      try { document.documentElement.classList.remove('banner-measuring') } catch (_) {}
    })

    // ===== 반응 =====
    watch(showBanner, (v) => {
      if (!v && typeof window !== 'undefined') {
        document.documentElement.style.setProperty('--autoBannerHeight', '0px')
      }
      if (!v) { maskOn.value = false }
      scheduleMeasure()
    })
    watch(visibleAlerts, () => scheduleMeasure(), { deep: true })

    // ===== 노출 =====
    return {
      loading,
      silenceModal,
      openSilence,
      closeSilence,
      onSilenceRefresh,
      pauseModal,
      openPause,
      closePause,
      onPauseRefresh,
      showBanner,
      alertingCount: computed(() => (Array.isArray(visibleAlerts.value) ? visibleAlerts.value.length : 0)),
      visibleAlerts,
      hostLinkList,
      hostMoreCount,
      vmLinkList,
      vmRestList,
      vmMoreCount,
      storageLinkList,
      cloudLinkList,
      hrefHostList,
      hrefVmList,
      hostMoreHref,
      vmMoreHref,
      alertRulesHref,
      hrefAlertRule,
      openUrl,
      openUrlBlank,
      goToHost,
      goToVm,
      getPopupParent,
      refresh,
      isKeySilencedNow,
      vmIndexVersion,
      displayVm,
      containerRef,
      listRef,
      onAlertCloseStart,
      onAlertClosed,
      maskOn
    }
  }
}
</script>

<style scoped>
/* 컨테이너/토큰 */
.auto-alert-banner-container {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 2147483647;
  width: 100%;
  isolation: isolate;
  --banner-radius: 6px;
  --field-radius: 6px;
  --chip-radius: 5px;
}

/* 측정 완료(.mask-on)일 때만 마스크 적용 */
.auto-alert-banner-container.mask-on::before {
  content: "";
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: var(--autoBannerHeight, 0px);
  background: var(--layout-bg, #fff);
  box-shadow: 0 1px 0 rgba(0, 0, 0, 0.06) inset;
  pointer-events: none;
  z-index: 0;
  transition: height 180ms ease;
}

.auto-alert-banner-container > * { position: relative; z-index: 1; }

/* 리스트 */
.banner-list { display: flex; flex-direction: column; gap: 8px; padding: 4px 8px 6px; }
.banner-list:empty { padding: 0; }

/* Ant Alert 오버라이드 */
.auto-alert-banner-container :deep(.ant-alert) {
  display: flex !important;
  align-items: center !important;
  justify-content: flex-start !important;
  gap: 8px !important;
  width: 100%;
  position: relative;
  padding-right: 44px !important;
  min-height: 35px;
  border-radius: var(--banner-radius);
  overflow: hidden;
}
.auto-alert-banner-container :deep(.ant-alert-with-icon) { padding-left: 0 !important; }
.auto-alert-banner-container :deep(.ant-alert-icon) {
  position: static !important;
  float: none !important;
  margin: 0 8px 0 0 !important;
}
.auto-alert-banner-container :deep(.ant-alert-content) {
  margin-left: auto !important;
  display: flex !important;
  justify-content: flex-end !important;
  align-items: center !important;
  padding-top: 4px !important;
  padding-bottom: 6px !important;
}
.auto-alert-banner-container :deep(.ant-alert-close-icon) {
  position: absolute !important;
  top: 6px;
  right: 8px;
  margin-left: 0 !important;
  cursor: pointer;
}

/* 콘텐츠 레이아웃 */
.banner-content {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-start;
  align-items: flex-start;
  gap: 10px;
  line-height: 1.6;
  text-align: left;
}
.banner-text {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  text-align: left;
  pointer-events: auto;
}

/* 필드 캡슐 */
.auto-alert-banner-container .banner-field {
  display: inline-flex;
  flex: 0 0 auto;
  width: auto;
  max-width: 100%;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-left: 8px;
  padding: 2px 8px;
  background: rgba(0, 0, 0, 0.035);
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: var(--field-radius);
  line-height: 1.5;
  padding-right: 0px;
}
.auto-alert-banner-container .banner-field .chip-wrap {
  display: inline-flex;
  flex: 0 0 auto;
  min-width: auto;
  max-width: 100%;
  flex-wrap: wrap;
  gap: 4px;
  margin-left: 0;
}

/* 칩 */
.auto-alert-banner-container :deep(.ant-tag.tag-link),
.auto-alert-banner-container :deep(.ant-tag.tag-more) {
  cursor: pointer;
  user-select: none;
  border-radius: var(--chip-radius);
}
.auto-alert-banner-container :deep(.ant-tag.tag-more) { border-style: dashed; opacity: 0.9; }

/* 아이콘/버튼 */
.banner-error-icon { font-size: 16px; color: #ff4d4f; flex: 0 0 auto; }
.icon-stack { position: relative; display: inline-flex; width: 16px; height: 16px; margin-right: 4px; vertical-align: -2px; }
.icon-stack .icon-sound { font-size: 16px; line-height: 16px; }

/* Pause 버튼(컴팩트) */
:deep(.pause-btn.pause-compact.ant-btn) { height: 22px; padding: 0 6px; font-size: 14px; line-height: 18px; }

/* 액션 우측 정렬 */
.banner-actions { margin-left: auto; display: flex; flex-wrap: wrap; gap: 10px; }

/* 다크 모드 */
@media (prefers-color-scheme: dark) {
  .auto-alert-banner-container.mask-on::before { background: var(--layout-bg, #0b0b0b); }
}

/* 반응형 */
@media (max-width: 1100px) {
  .banner-actions { width: 100%; justify-content: flex-end; }
}
@media (max-width: 768px) {
  .banner-content { justify-content: flex-start; }
  .banner-actions { width: 100%; display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 6px; }
}
</style>

<style>
:root { --autoBannerHeight: 0px; }

/* 모달/드로어 상단 오프셋 */
.ant-modal-wrap,
.ant-modal-mask {
  top: var(--autoBannerHeight, 0px) !important;
}

.ant-image-preview-wrap,
.ant-drawer,
.ant-drawer-mask {
  top: var(--autoBannerHeight, 0px) !important;
}

/* Notification/Message 상단 오프셋 */
.ant-notification-top,
.ant-notification-topRight,
.ant-notification-topLeft {
  top: calc(24px + var(--autoBannerHeight, 0px)) !important;
}
</style>
