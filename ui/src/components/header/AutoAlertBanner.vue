<!-- AutoAlertBanner.vue (미사용 제거/연결 누락 보완/ESLint 정리본) -->
<template>
  <teleport to="body">
    <div
      v-if="showBanner"
      class="auto-alert-banner-container"
      :class="{ 'has-banner': showBanner, 'mask-on': maskOn }"
    >
      <div class="banner-list" ref="listRef">
        <a-alert
          class="alert-summary"
          :type="'error'"
          :show-icon="false"
          :closable="false"
          :banner="true"
          :style="[{ border: '1px solid #ffccc7', background: '#ffffff', borderRadius: '10px', boxShadow: '0 2px 10px rgba(0, 0, 0, 0.08)' }]"
        >
          <template #message>
            <div class="summary-modern">
              <div class="summary-modern-left">
                <div class="summary-modern-icon">
                  <ExclamationCircleFilled />
                </div>

                <div class="summary-modern-text">
                  <div class="summary-modern-title">
                    <span class="summary-modern-title-text">
                      {{ tr('message.alerting.title', '주요 시스템 경보 감지') }}
                    </span>
                    <span class="summary-modern-count">
                      ({{ alertingCount }}{{ trCountUnit() }})
                    </span>
                  </div>

                  <div class="summary-modern-desc">
                    <span class="summary-bullet">-</span>
                    <span class="summary-desc-text">
                      {{ tr('message.alerting.desc', '주요 시스템에서 리소스 임계치 초과 또는 상태 변경이 감지되어 확인이 필요합니다.') }}
                    </span>
                  </div>
                </div>
              </div>

              <div class="summary-modern-actions">
                <a-space :size="8" align="center">
                  <a-button size="small" type="primary" danger @click.stop="drawerVisible = true">
                    {{ tr('label.take.action', '세부 경보 내용 확인') }}
                  </a-button>

                  <a-button size="small" @click.stop="goToAlertRulesMenu">
                    {{ tr('label.goto.the.alertRules', '경보 규칙 메뉴 이동') }}
                  </a-button>
                </a-space>
              </div>
            </div>
          </template>
        </a-alert>
      </div>
    </div>

    <!-- AlertListDrawer -->
    <a-drawer
      v-model:visible="drawerVisible"
      class="wall-alert-drawer"
      placement="right"
      :width="470"
      :bodyStyle="{ padding: '0px', overflow: 'hidden' }"
      :zIndex="2147483648"
      :getContainer="getPopupParent"
      :maskClosable="true"
      :closable="false"
      :closeIcon="null"
      :destroyOnClose="false"
    >
      <template #title>
        <div class="drawer-title">
          <div class="drawer-title-text">
            <div class="drawer-title-main">
              {{ tr('message.alerting.title', '조치가 필요한 경보') }}
              <span class="drawer-title-count">({{ alertingCount }}{{ trCountUnit() }})</span>
            </div>

            <div class="drawer-title-sub">
              <div>
                - {{ tr('message.alerting.desc.1', '각 항목에서 최대값·임계값을 확인하고 해결방안을 확인하세요.') }}
              </div>
              <div>
                - {{ tr('message.alerting.desc.2', '반복 알림은 사일런스(임시) 또는 일시 정지로 제어하세요.') }}
              </div>
            </div>
          </div>
        </div>
      </template>

      <div class="drawer-stack drawer-stack--modern">
        <div class="drawer-toolbar">
          <a-button size="small" type="link" @click.stop="markAllAsRead">
            {{ tr('label.mark.all.read', '모두 읽음 처리') }}
          </a-button>
          <a-space :size="10" align="center">
            <a-button size="small" type="link" @click.stop="drawerVisible = false">
              {{ tr('label.close', '닫기') }}
            </a-button>
          </a-space>
        </div>

        <div class="drawer-alert-viewport drawer-alert-viewport--modern">
          <!-- ★ soft-close 애니메이션이 Drawer에서도 동작하도록 ref 연결합니다 -->
          <div class="drawer-list-card" ref="drawerListRef">
            <a-alert
              v-for="it in visibleAlerts"
              :key="it.uid || it.id"
              :data-key="it.uid || it.id"
              class="drawer-item-alert"
              :type="'error'"
              :show-icon="false"
              :closable="false"
              :banner="false"
              @close="() => onAlertCloseStart(it)"
              @afterClose="() => onAlertClosed(it)"
            >
              <template #message>
                <div class="drawer-item">
                  <div class="drawer-item-icon">
                    <span class="drawer-item-icon-circle">
                      <ExclamationCircleFilled />
                    </span>
                  </div>

                  <div class="drawer-item-body">
                    <div class="drawer-item-header">
                      <div
                        class="drawer-item-title drawer-item-title--link"
                        role="button"
                        tabindex="0"
                        :title="`${tr('label.goto.the.alertRules', '경보 규칙으로 이동')}: ${(it && it.title) ? it.title : ''}`"
                        @click.prevent.stop="goToAlertRule(it)"
                        @keydown.enter.prevent.stop="goToAlertRule(it)"
                      >
                        {{ it && it.title ? it.title : tr('label.alert', '경보') }}
                      </div>

                      <div
                        v-if="drawerItemMetricLineText(it) || drawerItemAgeText(it)"
                        class="drawer-item-meta-row"
                      >
                        <div v-if="drawerItemMetricLineText(it)" class="drawer-item-meta-left">
                          <a-popover
                            trigger="hover"
                            placement="bottomLeft"
                            :overlayStyle="{ zIndex: 2147483650 }"
                            :getPopupContainer="getPopupParent"
                          >
                            <template #content>
                              <div class="target-more-popover">
                                <template v-if="breachedCountByKind(it, 'host') > 0">
                                  <div
                                    v-for="lnk in breachedLinksByKind(it, 'host')"
                                    :key="lnk.key"
                                    class="target-more-item"
                                  >
                                    <a
                                      class="target-more-link"
                                      href="#"
                                      @click.prevent.stop="openEntityLink(lnk)"
                                    >
                                      {{ lnk.label }}
                                    </a>
                                    <span v-if="lnk.valueText" class="target-value-metric">
                                      ({{ lnk.valueText }})
                                    </span>
                                  </div>
                                </template>

                                <template v-if="breachedCountByKind(it, 'vm') > 0">
                                  <div
                                    v-for="lnk in breachedLinksByKind(it, 'vm')"
                                    :key="lnk.key"
                                    class="target-more-item"
                                  >
                                    <a
                                      class="target-more-link"
                                      href="#"
                                      @click.prevent.stop="openEntityLink(lnk)"
                                    >
                                      {{ lnk.label }}
                                    </a>
                                    <span v-if="lnk.valueText" class="target-value-metric">
                                      ({{ lnk.valueText }})
                                    </span>
                                  </div>
                                </template>

                                <template v-if="breachedCountByKind(it, 'storage') > 0">
                                  <div
                                    v-for="lnk in breachedLinksByKind(it, 'storage')"
                                    :key="lnk.key"
                                    class="target-more-item"
                                  >
                                    <a
                                      class="target-more-link"
                                      href="#"
                                      @click.prevent.stop="openEntityLink(lnk)"
                                    >
                                      {{ lnk.label }}
                                    </a>
                                    <span v-if="lnk.valueText" class="target-value-metric">
                                      ({{ lnk.valueText }})
                                    </span>
                                  </div>
                                </template>

                                <template v-if="breachedCountByKind(it, 'cloud') > 0">
                                  <div
                                    v-for="lnk in breachedLinksByKind(it, 'cloud')"
                                    :key="lnk.key"
                                    class="target-more-item"
                                  >
                                    <a
                                      class="target-more-link"
                                      href="#"
                                      @click.prevent.stop="openEntityLink(lnk)"
                                    >
                                      {{ lnk.label }}
                                    </a>
                                    <span v-if="lnk.valueText" class="target-value-metric">
                                      ({{ lnk.valueText }})
                                    </span>
                                  </div>
                                </template>
                              </div>
                            </template>

                            <span class="drawer-item-metric-hover">
                              <template v-if="drawerItemMetricUi(it).kind === 'binary'">
                                <span class="metric-binary">
                                  {{ drawerItemMetricUi(it).text }}
                                </span>
                              </template>

                              <template v-else>
                                <span v-if="drawerItemMetricUi(it).hasCur" class="metric-kv">
                                  <span class="metric-k">
                                    {{ tr('label.max', '최대') }}
                                  </span>
                                  <span class="metric-v metric-current">
                                    {{ drawerItemMetricUi(it).curText }}
                                  </span>
                                </span>

                                <span
                                  v-if="drawerItemMetricUi(it).hasCur && drawerItemMetricUi(it).hasThr"
                                  class="metric-sep"
                                >·</span>

                                <span v-if="drawerItemMetricUi(it).hasThr" class="metric-kv">
                                  <span class="metric-k">
                                    {{ tr('label.threshold.value', '임계값') }}
                                  </span>
                                  <span class="metric-v metric-threshold">
                                    {{ drawerItemMetricUi(it).thrText }}
                                  </span>
                                </span>
                              </template>
                            </span>
                          </a-popover>
                        </div>
                        <div v-if="drawerItemAgeText(it)" class="drawer-item-meta-right">
                          {{ drawerItemAgeText(it) }}
                        </div>
                      </div>
                    </div>

                    <div class="drawer-item-divider" />

                    <!-- 대상 호스트 -->
                    <div v-if="hostLinkList(it).length" class="drawer-item-target-line">
                      <span class="target-label">
                        {{ tr('label.targets.hosts', '대상 호스트') }}:
                      </span>

                      <span class="target-values">
                        <template v-for="(lnk, idx) in hostLinkList(it)" :key="lnk.key">
                          <a
                            class="target-value-link"
                            href="#"
                            @click.prevent.stop="goToHost(lnk.keyword)"
                          >
                            {{ lnk.label }}
                          </a>
                          <span v-if="idx < hostLinkList(it).length - 1" class="target-sep"> | </span>
                        </template>

                        <a-popover
                          v-if="hostMoreCount(it) > 0"
                          trigger="hover"
                          placement="bottomRight"
                          :overlayStyle="{ zIndex: 2147483650 }"
                          :getPopupContainer="getPopupParent"
                        >
                          <template #content>
                            <div class="more-pop-list">
                              <div
                                v-for="lnk in hostRestList(it)"
                                :key="lnk.key"
                                class="more-pop-item"
                              >
                                <a
                                  class="target-value-link"
                                  href="#"
                                  @click.prevent.stop="goToHost(lnk.keyword)"
                                >
                                  {{ lnk.label }}
                                </a>
                              </div>
                            </div>
                          </template>

                          <a class="target-more" href="#" @click.prevent.stop>
                            +{{ hostMoreCount(it) }}
                          </a>
                        </a-popover>
                      </span>

                    </div>

                    <!-- 대상 VM -->
                    <div v-if="vmLinkList(it).length" class="drawer-item-target-line">
                      <span class="target-label">
                        {{ tr('label.targets.vms', '대상 VM') }}:
                      </span>

                      <span class="target-values">
                        <template v-for="(lnk, idx) in vmLinkList(it)" :key="lnk.key">
                          <a
                            class="target-value-link"
                            href="#"
                            :data-vmindex="vmIndexVersion"
                            @click.prevent.stop="goToVm(lnk.keyword)"
                          >
                            {{ lnk.label }}
                          </a>
                          <span v-if="idx < vmLinkList(it).length - 1" class="target-sep"> | </span>
                        </template>

                        <a-popover
                          v-if="vmMoreCount(it) > 0"
                          trigger="hover"
                          placement="bottomRight"
                          :overlayStyle="{ zIndex: 2147483650 }"
                          :getPopupContainer="getPopupParent"
                        >
                          <template #content>
                            <div class="more-pop-list">
                              <div
                                v-for="lnk in vmRestList(it)"
                                :key="lnk.key"
                                class="more-pop-item"
                              >
                                <a
                                  class="target-value-link"
                                  href="#"
                                  :data-vmindex="vmIndexVersion"
                                  @click.prevent.stop="goToVm(lnk.keyword)"
                                >
                                  {{ lnk.label }}
                                </a>
                              </div>
                            </div>
                          </template>

                          <a class="target-more" href="#" @click.prevent.stop>
                            +{{ vmMoreCount(it) }}
                          </a>
                        </a-popover>
                      </span>

                    </div>

                    <!-- 대상 스토리지 컨트롤러 -->
                    <div v-if="storageLinkList(it).length" class="drawer-item-target-line">
                      <span class="target-label">
                        {{ tr('label.targets.storage.controller', '대상 스토리지 컨트롤러') }}:
                      </span>

                      <span class="target-values">
                        <template v-for="(lnk, idx) in storageLinkList(it)" :key="lnk.key">
                          <a
                            class="target-value-link"
                            href="#"
                            @click.prevent.stop="openUrlBlank(lnk.url)"
                          >
                            {{ lnk.label }}
                          </a>
                          <span v-if="idx < storageLinkList(it).length - 1" class="target-sep"> | </span>
                        </template>

                        <a-popover
                          v-if="storageMoreCount(it) > 0"
                          trigger="hover"
                          placement="bottomRight"
                          :overlayStyle="{ zIndex: 2147483650 }"
                          :getPopupContainer="getPopupParent"
                        >
                          <template #content>
                            <div class="more-pop-list">
                              <div
                                v-for="lnk in storageRestList(it)"
                                :key="lnk.key"
                                class="more-pop-item"
                              >
                                <a
                                  class="target-value-link"
                                  href="#"
                                  @click.prevent.stop="openUrlBlank(lnk.url)"
                                >
                                  {{ lnk.label }}
                                </a>
                              </div>
                            </div>
                          </template>

                          <a class="target-more" href="#" @click.prevent.stop>
                            +{{ storageMoreCount(it) }}
                          </a>
                        </a-popover>
                      </span>

                    </div>

                    <!-- 대상 관리 서버 -->
                    <div v-if="cloudLinkList(it).length" class="drawer-item-target-line">
                      <span class="target-label">
                        {{ tr('label.targets.management', '대상 관리 서버') }}:
                      </span>

                      <span class="target-values">
                        <template v-for="(lnk, idx) in cloudLinkList(it)" :key="lnk.key">
                          <a
                            class="target-value-link"
                            href="#"
                            :title="`${tr('tooltip.goto.management', '관리 서버 상세로 이동')}: ${lnk.label}`"
                            @click.prevent.stop="goToManagement(lnk.keyword)"
                          >
                            {{ lnk.label }}
                          </a>
                          <span v-if="idx < cloudLinkList(it).length - 1" class="target-sep"> | </span>
                        </template>

                        <a-popover
                          v-if="cloudMoreCount(it) > 0"
                          trigger="hover"
                          placement="bottomRight"
                          :overlayStyle="{ zIndex: 2147483650 }"
                          :getPopupContainer="getPopupParent"
                        >
                          <template #content>
                            <div class="more-pop-list">
                              <div
                                v-for="lnk in cloudRestList(it)"
                                :key="lnk.key"
                                class="more-pop-item"
                              >
                                <a
                                  class="target-value-link"
                                  href="#"
                                  :title="`${tr('tooltip.goto.management', '관리 서버 상세로 이동')}: ${lnk.label}`"
                                  @click.prevent.stop="goToManagement(lnk.keyword)"
                                >
                                  {{ lnk.label }}
                                </a>
                              </div>
                            </div>
                          </template>

                          <a class="target-more" href="#" @click.prevent.stop>
                            +{{ cloudMoreCount(it) }}
                          </a>
                        </a-popover>
                      </span>

                    </div>

                    <div class="drawer-item-actions">
                      <a-space class="banner-actions" :size="10" align="center">
                        <!-- Solution -->
                        <a-popover
                          placement="topLeft"
                          trigger="hover"
                          overlayClassName="solution-popover"
                          :getPopupContainer="getPopupContainerBody"
                          :overlayStyle="getSolutionOverlayStyle()"
                          :visible="isSolutionPopoverVisible(it)"
                          @visible-change="onSolutionVisibleChange($event, it)"
                          :mouse-enter-delay="0"
                          :mouse-leave-delay="0.5"
                        >
                          <template #content>
                            <div class="solution-popover-unified">
                              <!-- Header -->
                              <div class="solution-popover-header" :class="solutionSeverityHeaderClass(it)">
                                <div class="sp-header-kicker">
                                  TROUBLESHOOTING
                                </div>

                                <div class="sp-header-row">
                                  <div class="sp-header-title">
                                    {{ solutionHeaderTitle(it) }}
                                  </div>
                                </div>
                              </div>

                              <!-- Body (scroll) -->
                              <div class="solution-popover-body">
                                <div class="sp-body-section">
                                  <div class="sp-body-label-chip">
                                    {{ tr('label.summary', '요약') }}
                                  </div>
                                  <div class="sp-body-box">
                                    {{ solutionSummaryText(it) }}
                                  </div>
                                </div>

                                <div class="sp-body-section">
                                  <div class="sp-body-label-chip">
                                    {{ tr('label.solution', '해결 방안') }}
                                  </div>
                                  <div class="sp-body-box sp-body-box--md">
                                    <div
                                      class="solution-popover-text"
                                      v-html="renderSolutionDescriptionHtml(it)"
                                    ></div>
                                  </div>
                                </div>
                              </div>
                            </div>
                          </template>

                          <a-button
                            size="small"
                            type="default"
                            class="solution-menu"
                            @click.stop="goSolutionTab(it)"
                          >
                            {{ tr('label.action.solution', '해결 방안') }}
                          </a-button>
                        </a-popover>

                        <!-- Silence -->
                        <a-button
                          size="small"
                          class="silence-menu"
                          :disabled="!it || !it.uid || isKeySilencedNow(it.uid)"
                          @click.stop="openSilence(it)"
                        >
                          <span class="icon-stack">
                            <SoundOutlined class="icon-sound" />
                          </span>
                          {{ isKeySilencedNow(it && it.uid) ? tr('label.silenced', '사일런스 중') : tr('label.action.silence', '사일런스') }}
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
                          {{ tr('label.alert.rule.pause', '일시 정지') }}
                        </a-button>
                      </a-space>
                    </div>
                  </div>
                </div>
              </template>
            </a-alert>

            <div v-if="!visibleAlerts.length" class="drawer-empty">
              {{ tr('message.no.alerts', '현재 표시할 경보가 없습니다.') }}
            </div>
          </div>
        </div>
      </div>
    </a-drawer>

    <!-- RuleSilenceModal -->
    <a-modal
      v-model:visible="silenceModal.visible"
      :title="$t('label.action.silence')"
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
      :title="$t('label.alert.rule.pause')"
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
import {
  ref,
  computed,
  onMounted,
  onBeforeUnmount,
  defineAsyncComponent,
  watch,
  nextTick,
  getCurrentInstance
} from 'vue'
import { ExclamationCircleFilled, SoundOutlined, PauseCircleOutlined, LinkOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { api } from '@/api'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

export default {
  name: 'AutoAlertBanner',
  components: {
    ExclamationCircleFilled,
    SoundOutlined,
    PauseCircleOutlined,
    LinkOutlined,
    RuleSilenceModal: defineAsyncComponent(() => import('@/views/infra/RuleSilenceModal.vue')),
    RulePauseModal: defineAsyncComponent(() => import('@/views/infra/RulePauseModal.vue'))
  },
  created () {
    this.md = new MarkdownIt({
      html: false,
      linkify: true,
      breaks: false
    })
  },
  data () {
    return {
      solutionPopoverUid: '',
      solutionPopoverOverlayStyle: { zIndex: 2147483650 }
    }
  },
  methods: {
    solutionHeaderTitle (it) {
      // 규칙명 우선, 없으면 uid/kind로 대체합니다.
      const name = (it && it.name) || (it && it.rule && it.rule.name) || ''
      if (name) {
        return name
      }

      const uid = (it && it.uid) || (it && it.rule && it.rule.uid) || ''
      const kind = (it && it.kind) || ''
      return uid || (kind || this.tr('label.solution', '해결 방안'))
    },

    solutionSeverityText (it) {
      const state = (it && it.state) || (it && it.rule && it.rule.state) || ''
      if (state === 'ALERTING' || state === 'FIRING') {
        return 'Critical'
      }
      if (state === 'PENDING') {
        return 'Warning'
      }
      if (state === 'NODATA') {
        return 'NoData'
      }
      return 'Info'
    },

    solutionSeverityHeaderClass (it) {
      const sev = this.solutionSeverityText(it)
      if (sev === 'Critical') {
        return 'sp-header--critical'
      }
      if (sev === 'Warning') {
        return 'sp-header--warning'
      }
      if (sev === 'NoData') {
        return 'sp-header--nodata'
      }
      return 'sp-header--info'
    },

    getPopupContainerBody () {
      return document.body
    },

    getItemUid (it) {
      // it 래퍼/원본 모두 대응합니다.
      return (it && it.uid) || (it && it.rule && it.rule.uid) || ''
    },

    isSolutionPopoverVisible (it) {
      const uid = this.getItemUid(it)
      return !!uid && this.solutionPopoverUid === uid
    },

    onSolutionVisibleChange (visible, it) {
      const uid = this.getItemUid(it)
      if (!uid) {
        return
      }

      this.solutionPopoverUid = visible ? uid : ''
    },

    getDrawerTopZIndex () {
      // drawer/mask 중 가장 높은 z-index를 찾아 그 위로 올립니다.
      const nodes = document.querySelectorAll('.ant-drawer, .ant-drawer-mask')
      let maxZ = 1000

      nodes.forEach((el) => {
        const z = parseInt(window.getComputedStyle(el).zIndex, 10)
        if (Number.isFinite(z) && z > maxZ) {
          maxZ = z
        }
      })

      return maxZ
    },

    getSolutionOverlayStyle () {
      return { zIndex: this.getDrawerTopZIndex() + 50 }
    },

    closeDrawerForNavigation () {
      // 기존 닫기 로직이 있으면 그 로직을 최우선으로 재사용합니다.
      if (typeof this.onDrawerClose === 'function') {
        this.onDrawerClose()
        return
      }

      if (typeof this.closeDrawer === 'function') {
        this.closeDrawer()
        return
      }

      // 파일마다 이름이 달라도 안전하게 닫히도록 흔한 상태값만 정리합니다.
      if (Object.prototype.hasOwnProperty.call(this, 'drawerVisible')) {
        this.drawerVisible = false
      }

      if (Object.prototype.hasOwnProperty.call(this, 'drawerOpen')) {
        this.drawerOpen = false
      }

      if (Object.prototype.hasOwnProperty.call(this, 'maskOn')) {
        this.maskOn = false
      }
    },
    renderMarkdownSafe (text) {
      const src = (text || '').trim()
      if (!src) {
        return '<div class="solution-empty">내용이 없습니다.</div>'
      }

      const html = this.md.render(src)
      return DOMPurify.sanitize(html)
    },
    renderSolutionDescriptionHtml (it) {
      return this.renderMarkdownSafe(this.solutionDescriptionText(it))
    },

    goSolutionTab (it) {
      const uid = this.getItemUid(it)
      if (!uid) {
        return
      }

      // 1) popover는 즉시 닫습니다.
      this.solutionPopoverUid = ''

      // 2) drawer도 즉시 닫습니다.
      this.closeDrawerForNavigation()

      // 3) 그 다음 탭 이동 라우팅을 수행합니다.
      const path = `/alertRules/${encodeURIComponent(uid)}`
      const query = { tab: 'solution' }

      if (this.$router && typeof this.$router.push === 'function') {
        this.$router.push({ path, query }).catch((e) => {
          if (e && e.name !== 'NavigationDuplicated') {
            console.log(e)
          }
        })
        return
      }

      window.location.hash = `#/alertRules/${encodeURIComponent(uid)}?tab=solution`
    }
  },
  setup () {
    const ORIGIN = typeof window !== 'undefined' ? window.location.origin : ''
    const HOST_BASE = ''
    const VM_BASE = '/client'

    // ===== i18n 안전 호출 =====
    const instance = getCurrentInstance()
    const tFn =
      (instance && instance.proxy && typeof instance.proxy.$t === 'function' ? instance.proxy.$t : null) ||
      (typeof window !== 'undefined' && typeof window.$t === 'function' ? window.$t : null)

    const tr = (key, fallback, params) => {
      try {
        const v = tFn ? tFn(key, params) : null
        if (!v || v === key) { return fallback }
        return v
      } catch (_) {
        return fallback
      }
    }

    const trCountUnit = () => {
      const v = tr('label.count.unit', '건')
      return v || '건'
    }

    // ===== 부팅 시 높이 복원 =====
    const LS_H_KEY = 'autoAlertBanner.lastHeight'
    try {
      const bootH = Number(localStorage.getItem(LS_H_KEY) || 0)
      if (!Number.isNaN(bootH) && bootH >= 0) {
        document.documentElement.style.setProperty('--autoBannerHeight', bootH + 'px')
      }
    } catch (_) {}

    // ===== 전역 이벤트 =====
    const emitClosing = () => {
      try {
        window.dispatchEvent(new CustomEvent('auto-alert-banner:closing'))
      } catch (_) {}
    }

    const emitClosed = () => {
      try {
        window.dispatchEvent(new CustomEvent('auto-alert-banner:closed'))
      } catch (_) {}
    }

    // ===== 모바일 판별 =====
    const isMobile = () => {
      try {
        return window.matchMedia && window.matchMedia('(max-width: 768px)').matches
      } catch (_) {
        return false
      }
    }

    // ===== 링크 유틸 =====
    const hrefManagementDetail = (id) => `${ORIGIN}/#/managementserver/${id}?tab=details`
    const hrefManagementList = (keyword) => `${ORIGIN}/#/managementserver${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`
    const hrefHostDetail = (id) => `${ORIGIN}${HOST_BASE}/#/host/${id}`
    const hrefHostList = (keyword) => `${ORIGIN}${HOST_BASE}/#/hosts${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`
    const hrefVmDetail = (id) => `${ORIGIN}${VM_BASE}/#/vm/${id}`
    const hrefVmList = (keyword) => `${ORIGIN}${VM_BASE}/#/vm${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`

    const alertRulesHref = `${ORIGIN}/#/alertRules`
    const goToAlertRulesMenu = () => {
      try {
        window.location.href = alertRulesHref
      } catch (_) {}
    }

    const hrefAlertRule = (item) => {
      const uid = item && (item.uid || ruleUid(item.rule) || item.id)
      return uid ? `${ORIGIN}/#/alertRules/${encodeURIComponent(uid)}` : `${ORIGIN}/#/alertRules`
    }

    const goToAlertRule = (item) => {
      const href = hrefAlertRule(item)
      if (!href) { return }
      try {
        window.location.href = href
      } catch (_) {}
    }

    const MAX_LINKS = 3

    // ===== 상태 =====
    const rules = ref([])
    const refreshInFlight = ref(false)
    const keepShowing = ref(false)

    const HIDE_GRACE_MS = 150
    let hideTimer = null

    const maskOn = ref(false)

    // ===== Drawer =====
    const drawerVisible = ref(false)

    // ===== 폴링 =====
    const POLL_MS = 60000
    const MIN_DELAY_MS = 5000
    let pollHandle = null
    let pollBusy = false

    function scheduleNextPoll () {
      if (pollHandle) {
        clearTimeout(pollHandle)
        pollHandle = null
      }
      let delay = POLL_MS - (Date.now() % POLL_MS)
      if (delay < MIN_DELAY_MS) {
        delay += POLL_MS
      }
      pollHandle = setTimeout(pollTick, delay)
    }

    async function pollTick () {
      if (pollBusy || refreshInFlight.value) {
        scheduleNextPoll()
        return
      }
      pollBusy = true
      try {
        await refresh()
      } catch (_) {
      } finally {
        pollBusy = false
        scheduleNextPoll()
      }
    }

    function startPoll () {
      stopPoll()
      let delay = POLL_MS - (Date.now() % POLL_MS)
      if (delay < MIN_DELAY_MS) {
        delay = MIN_DELAY_MS
      }
      pollHandle = setTimeout(pollTick, delay)
    }

    function stopPoll () {
      if (pollHandle) {
        clearTimeout(pollHandle)
        pollHandle = null
      }
    }

    function onVisibility () {
      if (document.hidden) {
        stopPoll()
      } else {
        pollTick()
        startPoll()
      }
    }

    function onFocus () {
      if (!document.hidden) {
        pollTick()
      }
    }

    // ===== 사일런스 캐시 =====
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
        if (now > exp) {
          closedUntil.value.delete(k)
        }
      }
    }

    const isClosedNow = (k) => {
      if (!k) { return false }
      const exp = closedUntil.value.get(k)
      return !!(exp && Date.now() <= exp)
    }

    // ===== 치수 측정 =====
    const listRef = ref(null)
    const drawerListRef = ref(null)
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
        try {
          document.documentElement.classList.remove('banner-measuring')
        } catch (_) {}
      } catch (_) {}
    }

    const scheduleMeasure = () => {
      if (rafId) {
        cancelAnimationFrame(rafId)
      }
      rafId = requestAnimationFrame(() => {
        rafId = 0
        measureAndNotifyHeight()
      })
    }

    // ===== 소프트 클로즈 =====
    const runCloseAnimation = (k) => {
      try {
        if (!k) { return }
        const roots = [drawerListRef.value, listRef.value].filter(Boolean)
        let el = null
        for (let i = 0; i < roots.length; i += 1) {
          const found = roots[i].querySelector(`[data-key="${k}"]`)
          if (found) { el = found; break }
        }
        if (!el) { return }

        const h = el.getBoundingClientRect().height
        el.style.height = h + 'px'
        el.style.opacity = '1'
        el.style.overflow = 'hidden'
        el.style.transition = 'height 150ms ease, opacity 150ms ease'

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

    // ===== Drawer UI 유틸 =====
    const pickTimeLikeMs = (obj) => {
      if (!obj || typeof obj !== 'object') { return 0 }
      const keys = [
        'activeAt',
        'startsAt',
        'startTime',
        'start',
        'createdAt',
        'firedAt',
        'lastEvaluation',
        'lastEvaluationTime',
        'updatedAt',
        'timestamp',
        'time'
      ]
      for (let i = 0; i < keys.length; i += 1) {
        const k = keys[i]
        const v = obj[k]
        if (typeof v === 'number' && Number.isFinite(v)) {
          return v > 10_000_000_000 ? v : v * 1000
        }
        if (typeof v === 'string' && v) {
          const ms = Date.parse(v)
          if (Number.isFinite(ms) && ms > 0) { return ms }
        }
      }
      return 0
    }

    const formatAgo = (ms) => {
      if (!ms) { return '' }
      const now = Date.now()
      const diff = Math.max(0, now - ms)
      const sec = Math.floor(diff / 1000)
      if (sec < 60) { return tr('label.just.now', '방금') }
      const min = Math.floor(sec / 60)
      if (min < 60) { return `${min}${tr('label.minute', '분')} ${tr('label.ago', '전')}` }
      const hour = Math.floor(min / 60)
      if (hour < 24) { return `${hour}${tr('label.hour', '시간')} ${tr('label.ago', '전')}` }
      const day = Math.floor(hour / 24)
      if (day < 7) { return `${day}${tr('label.day', '일')} ${tr('label.ago', '전')}` }
      try {
        const d = new Date(ms)
        const y = d.getFullYear()
        const mm = String(d.getMonth() + 1).padStart(2, '0')
        const dd = String(d.getDate()).padStart(2, '0')
        return `${y}-${mm}-${dd}`
      } catch (_) {
        return ''
      }
    }

    const drawerItemAgeText = (it) => {
      const inst = it && Array.isArray(it.alerts) && it.alerts.length ? it.alerts[0] : null
      const ms = pickTimeLikeMs(inst) || pickTimeLikeMs(it && it.rule) || 0
      return ms ? formatAgo(ms) : ''
    }

    const markAllAsRead = () => {
      const list = Array.isArray(visibleAlerts.value) ? visibleAlerts.value : []
      for (let i = 0; i < list.length; i += 1) {
        const it = list[i]
        const k = it && (it.uid || it.id)
        if (k) { softCloseByUid(k) }
      }
      drawerVisible.value = false
    }

    // ===== 파서/유틸 =====
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

    const solutionSummaryText = (it) => {
      // visibleAlerts 아이템은 래퍼이므로 실제 룰은 it.rule 입니다.
      const r = (it && it.rule) ? it.rule : it
      // 인스턴스(alerts[0]) 쪽에도 summary/message가 올 수 있어 폴백으로 같이 봅니다.
      const a = (it && Array.isArray(it.alerts) && it.alerts.length) ? it.alerts[0] : null

      const annR = (r && r.annotations) ? r.annotations : {}
      const annA = (a && a.annotations) ? a.annotations : {}

      const summary = (r && r.summary)
        ? r.summary
        : takeFirst(
          annR.summary,
          annR.__summary__,
          annR.message,
          annA.summary,
          annA.__summary__,
          annA.message,
          ''
        )

      return (summary && String(summary).trim()) ? String(summary).trim() : '-'
    }

    const solutionDescriptionText = (it) => {
      const r = (it && it.rule) ? it.rule : it
      const a = (it && Array.isArray(it.alerts) && it.alerts.length) ? it.alerts[0] : null

      const annR = (r && r.annotations) ? r.annotations : {}
      const annA = (a && a.annotations) ? a.annotations : {}

      const description = (r && r.description)
        ? r.description
        : takeFirst(
          annR.description,
          annR.__description__,
          annA.description,
          annA.__description__,
          ''
        )

      return (description && String(description).trim()) ? String(description).trim() : '-'
    }

    // ===== 배너 표시: 현재/임계(현재 값이 없으면 임계만 표시) =====
    function pickNumberLike (v) {
      if (v == null) { return null }

      if (typeof v === 'number' && Number.isFinite(v)) { return v }

      if (typeof v === 'string') {
        const s = v.trim()
        if (!s) { return null }
        const n = Number(s.replace(/%$/, ''))
        if (Number.isFinite(n)) { return n }
        return null
      }

      if (Array.isArray(v) && v.length > 0) {
        return pickNumberLike(v[0])
      }

      if (typeof v === 'object') {
        if (v.value != null) { return pickNumberLike(v.value) }
        if (v.values != null) { return pickNumberLike(v.values) }

        const keys = Object.keys(v)
        for (let i = 0; i < keys.length; i += 1) {
          const n = pickNumberLike(v[keys[i]])
          if (n != null) { return n }
        }
      }

      return null
    }

    function metricUnitOf (it) {
      const title = String((it && it.title) || '').trim()
      const r = it && it.rule ? it.rule : null

      const explicit = takeFirst(
        it && it.unit,
        r && r.unit,
        r && r.units,
        r && r.thresholdUnit,
        r && r.valueUnit
      )
      if (explicit) { return String(explicit) }

      // 온도 단위(°C/℃) 우선 처리합니다.
      if (title.includes('°C') || title.includes('℃') || title.includes('온도')) { return '°C' }

      // 퍼센트 단위입니다.
      if (title.includes('%') || title.includes('(%)') || title.includes('사용률')) { return '%' }

      // 제목 끝의 괄호 단위를 일반적으로 추출합니다. 예: "(MB/s)", "(ms)"
      const m = title.match(/\(([^)]+)\)\s*$/)
      if (m && m[1]) { return m[1].trim() }

      return ''
    }

    function resolveCurrentMetric (it) {
      const a = it && Array.isArray(it.alerts) && it.alerts.length ? it.alerts[0] : null
      const r = it && it.rule ? it.rule : null

      const n0 = pickNumberLike(it && (it.currentValue || it.current || it.value || it.lastValue))
      if (n0 != null) { return n0 }

      const n1 = pickNumberLike(r && (r.currentValue || r.current || r.value || r.lastValue))
      if (n1 != null) { return n1 }

      const n2 = pickNumberLike(a && (a.value || a.values || a.val || a.sample))
      if (n2 != null) { return n2 }

      const n3 = pickNumberLike(a && a.annotations && (a.annotations.current || a.annotations.value))
      if (n3 != null) { return n3 }

      return null
    }

    function resolveCurrentMaxMetric (it) {
      // Drawer의 '현재' 값은 대상별 currentTargets 중 최대값을 사용합니다.
      // - currentTargets 가 없으면 null 입니다.
      const rows = currentTargetsOf(it)
      if (!Array.isArray(rows) || rows.length === 0) { return null }

      let max = null
      for (let i = 0; i < rows.length; i += 1) {
        const row = rows[i] || {}
        const n = pickNumberLike(takeFirst(row.value, row.val, row.current, row.currentValue))
        if (typeof n !== 'number' || !Number.isFinite(n)) { continue }
        if (max == null || n > max) { max = n }
      }

      return max
    }

    function resolveThresholdMetric (it) {
      const r = it && it.rule ? it.rule : null

      const cand = takeFirst(
        it && (it.thresholdValue || it.threshold || it.thresholdvalue),
        r && (r.thresholdValue || r.threshold || r.thresholdvalue),
        r && r.thresholds,
        r && r.evaluatorParams,
        r && r.evaluator && r.evaluator.params,
        r && r.condition && r.condition.evaluator && r.condition.evaluator.params,
        r && r.conditions && r.conditions[0] && r.conditions[0].evaluator && r.conditions[0].evaluator.params
      )

      const n = pickNumberLike(cand)
      if (n != null) { return n }

      if (Array.isArray(cand) && cand.length > 0) {
        return pickNumberLike(cand[0])
      }

      return null
    }

    function formatMetric (n, unit) {
      if (n == null || !Number.isFinite(n)) { return '' }

      let v = n
      // 0~1 사이 비율(0.1533)을 %로 전달하는 케이스 보정합니다.
      if (unit === '%' && v >= 0 && v <= 1) { v = v * 100 }

      const abs = Math.abs(v)
      let s = ''

      if (unit && /sec\/sec/i.test(unit)) {
        const trimZeros = (x) => String(x).replace(/0+$/, '').replace(/\.$/, '')

        let decimals = 0
        if (abs >= 100) decimals = 0
        else if (abs >= 10) decimals = 1
        else if (abs >= 1) decimals = 2
        else if (abs >= 0.1) decimals = 3
        else if (abs >= 0.01) decimals = 4
        else if (abs >= 0.001) decimals = 5
        else decimals = 6 // 0.00014 같은 값이 0.00014로 보이게 됩니다.

        s = trimZeros(v.toFixed(decimals))
      } else {
        // 일반 수치: 화면 공간을 아끼되, 필요한 자릿수는 유지합니다.
        if (abs >= 100) {
          s = String(Math.round(v))
        } else if (abs >= 10) {
          s = String(Math.round(v * 10) / 10).replace(/\.0$/, '')
        } else {
          s = String(Math.round(v * 100) / 100).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')
        }
      }

      if (!unit) { return s }

      // 기호 단위(%, °C)는 붙여 쓰고, 문자열 단위(sec/sec 등)는 공백을 둡니다.
      const noSpaceUnits = { '%': true, '°C': true, '℃': true }
      const sep = (noSpaceUnits[unit] || unit.length <= 2) ? '' : ' '
      return `${s}${sep}${unit}`
    }

    const drawerItemMetricInlineText = (it) => {
      // 상태형(0/1) 규칙은 숫자 표시 대신 상태만 표시합니다.
      if (isBinaryTargetRule(it)) {
        const hasBad = breachedKeysOf(it).length > 0
        return hasBad ? tr('label.current.bad', '현재 상태 이상') : tr('label.current.ok', '현재 정상')
      }

      const unit = metricUnitOf(it)

      // 현재값: 대상별 값 중 최대값입니다.
      const curMax = resolveCurrentMaxMetric(it)

      // 평균값: 백엔드가 내려주는 currentValue(평균)입니다.
      const curAvg = resolveCurrentMetric(it)

      const thr = resolveThresholdMetric(it)

      const hasCurMax = typeof curMax === 'number' && Number.isFinite(curMax)
      const hasCurAvg = typeof curAvg === 'number' && Number.isFinite(curAvg)
      const hasThr = typeof thr === 'number' && Number.isFinite(thr)

      const maxText = hasCurMax ? formatMetric(curMax, unit) : ''
      const avgText = hasCurAvg ? formatMetric(curAvg, unit) : ''
      const curText = hasCurMax
        ? (hasCurAvg ? `${maxText} (평균 ${avgText})` : `${maxText}`)
        : ''

      if (hasCurMax && hasThr) {
        return `${tr('label.max', '최대')} ${curText} · ${tr('label.threshold.value', '임계값')} ${formatMetric(thr, unit)}`
      }

      if (hasThr) {
        return `${tr('label.threshold.value', '임계값')} ${formatMetric(thr, unit)}`
      }

      if (hasCurMax) {
        return `${tr('label.max', '최대')} ${curText}`
      }

      return ''
    }

    const drawerItemMetricInlineTitle = (it) => {
      const t = drawerItemMetricInlineText(it)
      return t || ''
    }

    const drawerItemMetricLineText = (it) => {
      if (isBinaryTargetRule(it)) {
        const hasBad = breachedKeysOf(it).length > 0
        return hasBad ? tr('label.current.state.bad', '현재 상태 이상') : tr('label.current.state.ok', '현재 상태 정상')
      }

      const unit = metricUnitOf(it)

      const curMax = resolveCurrentMaxMetric(it)
      const curAvg = resolveCurrentMetric(it)
      const thr = resolveThresholdMetric(it)

      const hasCurMax = typeof curMax === 'number' && Number.isFinite(curMax)
      const hasCurAvg = typeof curAvg === 'number' && Number.isFinite(curAvg)
      const hasThr = typeof thr === 'number' && Number.isFinite(thr)

      const maxText = hasCurMax ? formatMetric(curMax, unit) : ''
      const avgText = hasCurAvg ? formatMetric(curAvg, unit) : ''
      const curText = hasCurMax
        ? (hasCurAvg ? `${maxText} (평균 ${avgText})` : `${maxText}`)
        : ''

      if (hasCurMax && hasThr) {
        return `${tr('label.max', '최대')} ${curText} · ${tr('label.threshold.value', '임계값')} ${formatMetric(thr, unit)}`
      }

      if (hasThr) {
        return `${tr('label.threshold.value', '임계값')} ${formatMetric(thr, unit)}`
      }

      if (hasCurMax) {
        return `${tr('label.max', '최대')} ${curText}`
      }

      return ''
    }

    const drawerItemMetricUi = (it) => {
      // 템플릿에서 부분 스타일 적용이 가능하도록, 현재/임계값을 분리한 구조를 반환합니다.
      if (isBinaryTargetRule(it)) {
        const hasBad = breachedKeysOf(it).length > 0
        return {
          kind: 'binary',
          text: hasBad ? tr('label.current.state.bad', '현재 상태 이상') : tr('label.current.state.ok', '현재 상태 정상')
        }
      }

      const unit = metricUnitOf(it)

      // 현재값: 대상별 값 중 최대값입니다.
      const curMax = resolveCurrentMaxMetric(it)

      // 평균값: 백엔드가 내려주는 currentValue(평균)입니다.
      const curAvg = resolveCurrentMetric(it)

      const thr = resolveThresholdMetric(it)

      const hasCurMax = typeof curMax === 'number' && Number.isFinite(curMax)
      const hasCurAvg = typeof curAvg === 'number' && Number.isFinite(curAvg)
      const hasThr = typeof thr === 'number' && Number.isFinite(thr)

      const maxText = hasCurMax ? formatMetric(curMax, unit) : ''
      const avgText = hasCurAvg ? formatMetric(curAvg, unit) : ''
      const curText = hasCurMax
        ? (hasCurAvg ? `${maxText} (평균 ${avgText})` : `${maxText}`)
        : ''

      return {
        kind: 'metric',
        hasCur: hasCurMax,
        hasThr,
        curText,
        thrText: hasThr ? formatMetric(thr, unit) : ''
      }
    }

    const drawerItemMetaText = (it) => {
      const metric = drawerItemMetricLineText(it)
      const age = drawerItemAgeText(it)

      if (metric && age) { return `${metric} · ${age}` }
      if (metric) { return metric }
      if (age) { return age }

      return ''
    }

    // ===== 해결방안(런북) =====
    function solutionUrlOf (it) {
      const r = it && it.rule ? it.rule : null
      const a = it && Array.isArray(it.alerts) && it.alerts.length ? it.alerts[0] : null

      return String(takeFirst(
        it && (it.solutionUrl || it.solution_url || it.runbookUrl || it.runbook_url),
        r && (r.solutionUrl || r.solution_url || r.runbookUrl || r.runbook_url),
        r && r.annotations && (r.annotations.runbook_url || r.annotations.runbookUrl || r.annotations.solution_url || r.annotations.solutionUrl),
        a && a.annotations && (a.annotations.runbook_url || a.annotations.runbookUrl || a.annotations.solution_url || a.annotations.solutionUrl)
      ) || '').trim()
    }

    const hasSolutionUrl = (it) => {
      const url = solutionUrlOf(it)
      return !!url
    }

    const openSolution = (it) => {
      const url = solutionUrlOf(it)
      if (!url) { return }
      openUrlBlank(url)
    }

    // ===== CloudStack 인덱스 =====
    const extractHosts = (resp) => {
      const wrap =
        resp?.listhostsmetricsresponse ||
        resp?.listHostsMetricsResponse ||
        resp?.listhostsresponse ||
        resp?.listHostsResponse ||
        resp?.data ||
        resp ||
        {}

      let list =
        wrap.host ||
        wrap.hosts ||
        wrap.items ||
        wrap.list

      if (!Array.isArray(list)) {
        for (const k in wrap) {
          if (Object.prototype.hasOwnProperty.call(wrap, k) && Array.isArray(wrap[k])) {
            list = wrap[k]
            break
          }
        }
      }

      return Array.isArray(list) ? list : []
    }

    const fetchHosts = async (params) => {
      try {
        const resp1 = await api('listHostsMetrics', params)
        const rows1 = extractHosts(resp1)
        if (Array.isArray(rows1) && rows1.length > 0) {
          return rows1
        }
      } catch (_) {}

      try {
        const resp2 = await api('listHosts', params)
        const rows2 = extractHosts(resp2)
        if (Array.isArray(rows2) && rows2.length > 0) {
          return rows2
        }
      } catch (_) {}

      return []
    }

    const extractVMs = (resp) => {
      const wrap = resp?.listvirtualmachinesresponse || resp?.listVirtualMachinesResponse || resp?.data || resp || {}
      let list = wrap?.virtualmachine || wrap?.virtualMachine || wrap?.virtualmachines || wrap?.virtualMachines || wrap?.items || wrap?.list
      if (!Array.isArray(list)) {
        for (const k in wrap) {
          if (Array.isArray(wrap[k])) {
            list = wrap[k]
            break
          }
        }
      }
      return Array.isArray(list) ? list : []
    }

    const hostIndexCache = { until: 0, byIp: new Map(), byName: new Map() }
    const vmIndexCache = { until: 0, byIp: new Map(), byName: new Map(), byInstanceName: new Map() }
    const vmIndexReady = ref(false)

    const ensureHostIndex = async () => {
      const now = Date.now()
      if (hostIndexCache.until > now) { return }

      try {
        const params = { listAll: true, listall: true, page: 1, pageSize: 200, pagesize: 200 }
        const rows = await fetchHosts(params)

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
          if (!id) { continue }

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

        vmIndexCache.until = now + 5 * 60 * 1000
        vmIndexReady.value = true
      } catch (_) {
        vmIndexCache.until = now + 60 * 1000
        vmIndexReady.value = false
      }
    }

    // ===== 호스트 힌트 =====
    const hostHints = { byIpName: new Map() }
    const hintHostNameByIp = (ip) => {
      if (!ip) { return '' }
      const val = hostHints.byIpName.get(String(ip))
      return val ? String(val) : ''
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

    const labelBag = (a) => (a && (a.labels || a.metric || a.tags || a)) || {}

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

    // ===== 관리서버 이동 =====
    const resolveManagementId = async (keyword) => {
      try {
        const key = String(keyword).trim()

        let resp = await api('listManagementServers', { name: key, listAll: true, listall: true, page: 1, pageSize: 200, pagesize: 200 })
        let list = (resp?.listmanagementserversresponse?.managementserver) || (resp?.data?.items) || []
        if (!Array.isArray(list)) { list = list ? [list] : [] }
        for (let i = 0; i < list.length; i += 1) {
          const it = list[i] || {}
          const nm = takeFirst(it.name, it.hostname, it.hostName)
          if (nm === key) { return takeFirst(it.id, it.uuid) }
        }

        resp = await api('listManagementServers', { keyword: key, listAll: true, listall: true, page: 1, pageSize: 200, pagesize: 200 })
        list = (resp?.listmanagementserversresponse?.managementserver) || (resp?.data?.items) || []
        if (!Array.isArray(list)) { list = list ? [list] : [] }
        const ip = parseIp(key)
        for (let i = 0; i < list.length; i += 1) {
          const it = list[i] || {}
          const nm = takeFirst(it.name, it.hostname, it.hostName)
          const hip = takeFirst(it.ipaddress, it.ipAddress, it.managementip, it.managementIp)
          if (nm === key || (ip && hip === ip) || String(nm).toLowerCase().includes(key.toLowerCase())) {
            return takeFirst(it.id, it.uuid)
          }
        }

        return null
      } catch (_) {
        return null
      }
    }

    const goToManagement = async (keyword) => {
      const id = await resolveManagementId(keyword)
      const url = id ? hrefManagementDetail(id) : hrefManagementList(keyword)
      try {
        window.location.href = url
      } catch (_) {
        message.warning(tr('message.link.open.failed', '링크 열기에 실패했습니다. 콘솔 로그의 URL을 확인하세요.'))
      }
    }

    // ===== rules 파싱 =====
    const extractRules = (resp) => {
      const wrap = resp?.listwallalertrulesresponse || resp?.listWallAlertRulesResponse || resp?.data || resp || {}
      const inner = wrap.wallalertruleresponse || wrap.wallalertrules || wrap.wallAlertRules || wrap.rules || wrap.items || wrap.list || wrap
      if (Array.isArray(inner)) { return inner }
      let rows = inner?.wallalertrule || inner?.wallAlertRule || inner?.rule || inner?.rules || inner?.items || inner?.list || []
      if (!Array.isArray(rows) && inner && typeof inner === 'object') {
        for (const k of Object.keys(inner)) {
          if (Array.isArray(inner[k])) {
            rows = inner[k]
            break
          }
        }
      }
      if (!Array.isArray(rows)) { rows = rows ? [rows] : [] }
      return rows
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

    // ===== 로컬 사일런스 =====
    function loadLocalSilences () {
      try {
        const raw = localStorage.getItem(LS_KEY)
        const obj = raw ? JSON.parse(raw) : {}
        return typeof obj === 'object' && obj ? obj : {}
      } catch (_) {
        return {}
      }
    }

    function saveLocalSilences () {
      try {
        localStorage.setItem(LS_KEY, JSON.stringify(localSilenced.value || {}))
      } catch (_) {}
    }

    function cleanupLocalSilences () {
      const now = Date.now()
      const next = {}
      for (const k in localSilenced.value) {
        if (!Object.prototype.hasOwnProperty.call(localSilenced.value, k)) { continue }
        if (localSilenced.value[k] > now) { next[k] = localSilenced.value[k] }
      }
      localSilenced.value = next
      saveLocalSilences()
    }

    const extractSilences = (resp) => {
      const wrap = resp?.listwallalertsilencesresponse || resp?.listWallAlertSilencesResponse || resp?.data || resp || {}

      let list = wrap.silences || wrap.silence || wrap.items || wrap.list
      if (!Array.isArray(list)) {
        for (const k in wrap) {
          if (Object.prototype.hasOwnProperty.call(wrap, k) && Array.isArray(wrap[k])) {
            list = wrap[k]
            break
          }
        }
      }
      return Array.isArray(list) ? list : []
    }

    const UID_LABEL_KEY = '__alert_rule_uid__'

    const silenceUidFromLabels = (s) => {
      if (!s) { return null }
      const arr = Array.isArray(s.labels) ? s.labels : (Array.isArray(s.matchers) ? s.matchers : [])
      for (let i = 0; i < arr.length; i += 1) {
        const m = arr[i] || {}
        const key = m.key || m.name || m.label
        const val = m.value || m.val
        if (key === UID_LABEL_KEY && val) {
          return String(val)
        }
      }
      return null
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

      const mapFromCache = {}
      const needFetch = []

      for (let i = 0; i < uniq.length; i += 1) {
        const uid = uniq[i]
        const cached = silenceCache.get(uid)
        if (cached && cached.until > now) {
          if (cached.end > now) {
            mapFromCache[uid] = cached.end
          }
        } else {
          needFetch.push(uid)
        }
      }

      if (!needFetch.length) {
        remoteSilenced.value = mapFromCache
        remoteSilencedLoaded.value = true
        return
      }

      try {
        const paramsAll = { page: 1, pageSize: 1000, pagesize: 1000, states: 'active' }
        const respAll = await api('listWallAlertSilences', paramsAll)
        const listAll = extractSilences(respAll)

        const want = new Set(uniq)
        const perUidEnd = {}

        for (let i = 0; i < listAll.length; i += 1) {
          const s = listAll[i] || {}
          const uid = silenceUidFromLabels(s)
          if (!uid || !want.has(uid)) { continue }

          const state = String(s.state || '').toLowerCase()

          const startLike = s.startMs || s.startTime || s.startsAt || s.start || s.createdAt
          const endLike = s.endMs || s.endTime || s.endsAt || s.end || s.expiresAt

          const start = typeof startLike === 'number' ? startLike : (startLike ? Date.parse(startLike) : 0)
          const end = typeof endLike === 'number' ? endLike : (endLike ? Date.parse(endLike) : 0)

          const active = (state === 'active' || (start && start <= now)) && end > now
          if (!active) { continue }

          const prev = perUidEnd[uid] || 0
          perUidEnd[uid] = end > prev ? end : prev
        }

        for (let i = 0; i < uniq.length; i += 1) {
          const uid = uniq[i]
          const end = perUidEnd[uid] || 0
          silenceCache.set(uid, { end, until: Date.now() + SILENCE_TTL_MS })
          if (end > now) {
            mapFromCache[uid] = end
          }
        }
      } catch (_) {}

      remoteSilenced.value = mapFromCache
      remoteSilencedLoaded.value = true
    }

    // ===== 엔티티 링크 구성 =====
    const VM_NAME_RE = /^i-\d+-\d+-VM$/i
    const VM_NAME_FUZZY_RE = /-VM$/i

    const bestHostOfInstance = (a) => {
      const L = labelBag(a)

      const target = takeFirst(
        L && L.pingip,
        L && L.target,
        L && L.dst,
        L && L.destination,
        L && L.remote,
        L && L.remote_ip,
        L && L.peer
      )
      if (target) { return String(target) }

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
        if (label) { return { kind: 'cloud', label, keyword: label } }
      }

      const host = bestHostOfInstance(a)
      if (host) { return { kind: 'host', keyword: host } }
      return null
    }

    const entityLinksForAlert = (it) => {
      const parentKind = pickKindFromRule(it && it.rule ? it.rule : {})
      let arr = Array.isArray(it && it.alerts) ? it.alerts : []

      if ((!arr || arr.length === 0) && it && Array.isArray(it.instances)) {
        arr = it.instances
      }

      if ((!arr || arr.length === 0) && it && it.rule) {
        arr = ruleInstances(it.rule)
      }

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
          out.push({ key, kind: 'cloud', label: cls.label, keyword: cls.keyword })
        }
      }

      return out
    }

    const vmEntityLinksRaw = (it) => entityLinksForAlert(it).filter((x) => x.kind === 'vm')

    const filterKnownVmLinks = (it) => {
      const rawList = vmEntityLinksRaw(it)
      if (!Array.isArray(rawList) || rawList.length === 0) { return [] }
      if (!vmIndexReady.value) { return rawList }

      return rawList.filter((lnk) => {
        const label = String(lnk && (lnk.label || lnk.keyword || '')).trim()
        if (!label) { return false }
        if (!VM_NAME_RE.test(label)) { return true }
        const shown = displayVm(label)
        return shown !== label
      })
    }

    const hostEntityLinks = (it) => entityLinksForAlert(it).filter((x) => x.kind === 'host')
    const vmEntityLinks = (it) => filterKnownVmLinks(it)
    const storageEntityLinks = (it) => entityLinksForAlert(it).filter((x) => x.kind === 'storage')
    const cloudEntityLinks = (it) => entityLinksForAlert(it).filter((x) => x.kind === 'cloud')

    const hostLinkList = (it) => hostEntityLinks(it).slice(0, MAX_LINKS)
    const hostRestList = (it) => hostEntityLinks(it).slice(MAX_LINKS)

    const vmLinkList = (it) => vmEntityLinks(it).slice(0, MAX_LINKS)
    const vmRestList = (it) => vmEntityLinks(it).slice(MAX_LINKS)

    const storageLinkList = (it) => storageEntityLinks(it).slice(0, MAX_LINKS)
    const storageRestList = (it) => storageEntityLinks(it).slice(MAX_LINKS)

    const cloudLinkList = (it) => cloudEntityLinks(it).slice(0, MAX_LINKS)
    const cloudRestList = (it) => cloudEntityLinks(it).slice(MAX_LINKS)

    const hostMoreCount = (it) => Math.max(0, hostEntityLinks(it).length - MAX_LINKS)
    const vmMoreCount = (it) => Math.max(0, vmEntityLinks(it).length - MAX_LINKS)
    const storageMoreCount = (it) => Math.max(0, storageEntityLinks(it).length - MAX_LINKS)
    const cloudMoreCount = (it) => Math.max(0, cloudEntityLinks(it).length - MAX_LINKS)

    // ===== 이상 대상(임계 초과/실패) =====
    const normalizeTargetKey = (v) => String(v || '').trim().toLowerCase()

    const currentTargetsOf = (it) => {
      const r = it && it.rule ? it.rule : null
      const cand = takeFirst(
        r && r.currentTargets,
        r && r.currenttargets,
        it && it.currentTargets,
        it && it.currenttargets
      )
      return Array.isArray(cand) ? cand : []
    }

    const breachedTargetsOf = (it) => {
      const r = it && it.rule ? it.rule : null
      const cand = takeFirst(
        r && r.breachedTargets,
        r && r.breachedtargets,
        it && it.breachedTargets,
        it && it.breachedtargets
      )
      return Array.isArray(cand) ? cand : []
    }

    const breachedKeysOf = (it) => {
      const fromExplicit = breachedTargetsOf(it)
      if (Array.isArray(fromExplicit) && fromExplicit.length > 0) {
        return fromExplicit.map((x) => String(x || '').trim()).filter((x) => !!x)
      }

      const rows = currentTargetsOf(it)
      const out = []
      for (let i = 0; i < rows.length; i += 1) {
        const row = rows[i] || {}
        const k = takeFirst(row.key, row.target, row.name, row.label)
        if (!k) { continue }
        const breached = !!takeFirst(row.breached, row.isBreached, row.isbreached)
        if (breached) { out.push(String(k).trim()) }
      }
      return out
    }

    const isBinaryTargetRule = (it) => {
      const rows = currentTargetsOf(it)
      let seen = 0
      for (let i = 0; i < rows.length; i += 1) {
        const row = rows[i] || {}
        const n = pickNumberLike(takeFirst(row.value, row.val, row.current, row.currentValue))
        if (typeof n !== 'number' || !Number.isFinite(n)) { continue }
        seen += 1
        if (!(Math.abs(n - 0) < 1e-9 || Math.abs(n - 1) < 1e-9)) { return false }
      }
      return seen > 0
    }

    const breachedLabelText = (it) => {
      if (isBinaryTargetRule(it)) {
        return tr('label.targets.failed', '실패 대상')
      }
      return tr('label.targets.breached', '이상 대상')
    }

    const breachedEntityLinks = (it) => {
      const keys = breachedKeysOf(it)
      if (!Array.isArray(keys) || keys.length === 0) { return [] }

      const unit = metricUnitOf(it)
      const rows = currentTargetsOf(it)
      const valueMap = new Map()

      for (let i = 0; i < rows.length; i += 1) {
        const row = rows[i] || {}
        const k = takeFirst(row.key, row.target, row.name, row.label)
        if (!k) { continue }
        const n = pickNumberLike(takeFirst(row.value, row.val, row.current, row.currentValue))
        if (n == null) { continue }
        valueMap.set(normalizeTargetKey(k), n)
      }

      const binary = isBinaryTargetRule(it)
      const ents = entityLinksForAlert(it)
      const entMap = new Map()

      for (let i = 0; i < ents.length; i += 1) {
        const e = ents[i] || {}
        const k1 = e.keyword ? normalizeTargetKey(e.keyword) : ''
        const k2 = e.label ? normalizeTargetKey(e.label) : ''
        if (k1) { entMap.set(k1, e) }
        if (k2) { entMap.set(k2, e) }
      }

      const out = []
      for (let i = 0; i < keys.length; i += 1) {
        const raw = String(keys[i] || '').trim()
        if (!raw) { continue }

        const nk = normalizeTargetKey(raw)
        const matched = entMap.get(nk)
        const kind = matched && matched.kind ? matched.kind : 'host'
        const label = matched && matched.label ? matched.label : hostDisplayLabel(raw)
        const keyword = matched && matched.keyword ? matched.keyword : raw
        const url = matched && matched.url ? matched.url : ''

        let valueText = ''
        if (binary) {
          valueText = tr('label.bad.state', '상태 이상')
        } else {
          const n = valueMap.get(nk)
          if (typeof n === 'number' && Number.isFinite(n)) {
            valueText = formatMetric(n, unit)
          }
        }

        out.push({ key: `breach@${kind}@${nk}`, kind, label, keyword, url, valueText })
      }

      return out
    }

    const breachedLinkList = (it) => breachedEntityLinks(it).slice(0, MAX_LINKS)
    const breachedRestList = (it) => breachedEntityLinks(it).slice(MAX_LINKS)
    const breachedMoreCount = (it) => Math.max(0, breachedEntityLinks(it).length - MAX_LINKS)

    const breachedLinksByKind = (it, kind) => breachedEntityLinks(it).filter((x) => x.kind === kind)
    const breachedCountByKind = (it, kind) => breachedLinksByKind(it, kind).length

    const openEntityLink = (lnk) => {
      if (!lnk) { return }

      if (lnk.kind === 'vm') {
        goToVm(lnk.keyword)
        return
      }

      if (lnk.kind === 'storage') {
        if (lnk.url) { openUrlBlank(lnk.url) }
        return
      }

      if (lnk.kind === 'cloud') {
        goToManagement(lnk.keyword)
        return
      }

      goToHost(lnk.keyword)
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

        const on = inst.filter((a) => {
          const st = instanceState(a)
          return ['ALERTING', 'FIRING'].includes(UC(st)) && !isNoiseLike(st)
        })

        const rState = ruleState(r)
        const ruleIsAlerting = ['ALERTING', 'FIRING'].includes(UC(rState)) && !isNoiseLike(rState)

        if (!on.length && !ruleIsAlerting) { continue }

        const uid = ruleUid(r) || r.id || r.ruleId
        if (!uid || seen.has(uid)) { continue }
        seen.add(uid)

        out.push({
          id: r.id || r.ruleId || uid,
          uid,
          title: ruleTitle(r),
          rule: r,
          alerts: on,
          instances: inst
        })
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

        if (isClosedNow(uid) || isKeySilencedNow(uid) || isRulePaused(it.rule)) {
          seen.add(uid)
          continue
        }

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
      if (hideTimer) {
        clearTimeout(hideTimer)
        hideTimer = null
      }

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
        refreshInFlight.value = false
        hideTimer = setTimeout(() => { keepShowing.value = false }, HIDE_GRACE_MS)
        measureAndNotifyHeight()
      }
    }

    // ===== 모달 =====
    const silenceModal = ref({ visible: false, target: null })
    const openSilence = (it) => {
      const title = (it && it.title) || (it && it.rule && ruleTitle(it.rule)) || it?.name || ''
      const target = { ...it, name: title }
      silenceModal.value = { visible: true, target }
    }
    const closeSilence = () => { silenceModal.value = { visible: false, target: null } }

    const onSilenceRefresh = async (info) => {
      try {
        const uidFromModal = info && (info.uid || info.ruleUid)
        const uidFromTarget = (silenceModal.value && silenceModal.value.target && (silenceModal.value.target.uid || silenceModal.value.target.id)) || null
        const uid = uidFromModal || uidFromTarget

        if (uid) {
          onAlertCloseStart({ uid })

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

    const resolveHostId = async (keyword) => {
      try {
        const key = String(keyword || '').trim()
        if (!key) { return null }

        await ensureHostIndex()
        const info = resolveHostInfo(key)
        if (info && info.id) { return info.id }

        const ip = parseIp(key)

        const pickIdFromHosts = (rows, strict) => {
          for (let i = 0; i < rows.length; i += 1) {
            const h = rows[i] || {}
            const id = takeFirst(h.id, h.uuid)
            const name = takeFirst(h.name, h.hostname, h.hostName)
            const hip = takeFirst(h.ipaddress, h.ipAddress, h.hostip, h.hostIp, h.privateipaddress, h.privateIpAddress)
            if (!id) { continue }

            if (strict) {
              if (name === key || (ip && hip === ip)) { return id }
            } else {
              const lowerKey = key.toLowerCase()
              const lowerName = String(name || '').toLowerCase()
              if (name === key || (ip && hip === ip) || lowerName.includes(lowerKey)) {
                return id
              }
            }
          }
          return null
        }

        let rows = await fetchHosts({ name: key, listAll: true, listall: true, page: 1, pageSize: 50, pagesize: 50 })
        let found = pickIdFromHosts(rows, true)
        if (found) { return found }

        rows = await fetchHosts({ keyword: key, listAll: true, listall: true, page: 1, pageSize: 50, pagesize: 50 })
        found = pickIdFromHosts(rows, false)
        if (found) { return found }

        if (Array.isArray(rows) && rows.length === 1) {
          const h0 = rows[0] || {}
          return takeFirst(h0.id, h0.uuid) || null
        }

        return null
      } catch (_) {
        return null
      }
    }

    const goToHost = async (keyword) => {
      const id = await resolveHostId(keyword)
      const url = id ? hrefHostDetail(id) : hrefHostList(keyword)
      console.log(url)
      try {
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
      } catch (_) {
        return null
      }
    }

    const goToVm = async (keyword) => {
      const id = await resolveVmId(keyword)
      const url = id ? hrefVmDetail(id) : hrefVmList(keyword)
      if (!id) {
        message.warning(tr('message.vm.resolve.fallback', '정확한 VM ID를 찾지 못해 목록으로 이동합니다.'))
      }
      try {
        window.location.href = url
      } catch (_) {
        message.warning(tr('message.link.open.failed', '링크 열기에 실패했습니다. 콘솔 로그의 URL을 확인하세요.'))
      }
    }

    // ===== 공용 =====
    const keyOf = (it) => (it && (it.uid || it.id)) || null

    function getPopupParent (triggerNode) {
      try {
        const parent = triggerNode && triggerNode.ownerDocument && triggerNode.ownerDocument.body
        return parent || document.body
      } catch (_) {
        return typeof document !== 'undefined' ? document.body : undefined
      }
    }

    // ===== 닫힘 핸들러 =====
    const onAlertCloseStart = (it) => {
      const k = keyOf(it)
      if (!k) { return }

      if (isMobile()) {
        try {
          const root = listRef.value
          const el = root && root.querySelector(`[data-key="${k}"]`)
          if (el) {
            const h = Math.ceil(el.getBoundingClientRect().height || 0)
            if (h > 0 && lastHeight.value >= 0) {
              const nextH = Math.max(0, lastHeight.value - h)
              lastHeight.value = nextH
              if (typeof window !== 'undefined') {
                document.documentElement.style.setProperty('--autoBannerHeight', `${nextH}px`)
                try { localStorage.setItem(LS_H_KEY, String(nextH)) } catch (_) {}
                if (typeof navigator !== 'undefined' && /Mobi|Android/i.test(navigator.userAgent)) {
                  requestAnimationFrame(() => {
                    try { window.dispatchEvent(new Event('resize')) } catch (_) {}
                  })
                }
                window.dispatchEvent(new CustomEvent('auto-alert-banner:height', { detail: { height: nextH } }))
              }
              maskOn.value = nextH > 0
            }
          }
        } catch (_) {}
      }

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
      if (!v) { drawerVisible.value = false }
      if (!v && typeof window !== 'undefined') {
        document.documentElement.style.setProperty('--autoBannerHeight', '0px')
        try { localStorage.setItem(LS_H_KEY, '0') } catch (_) {}
        try { window.dispatchEvent(new Event('resize')) } catch (_) {}
      }
      if (!v) { maskOn.value = false }
      scheduleMeasure()
    })

    watch(visibleAlerts, () => scheduleMeasure(), { deep: true })

    // ===== 노출 =====
    return {
      drawerVisible,
      drawerListRef,
      listRef,
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
      hostRestList,
      hostMoreCount,
      vmLinkList,
      vmRestList,
      vmMoreCount,
      storageLinkList,
      storageRestList,
      storageMoreCount,
      cloudLinkList,
      cloudRestList,
      cloudMoreCount,
      breachedLinkList,
      breachedRestList,
      breachedMoreCount,
      breachedLinksByKind,
      breachedCountByKind,
      breachedLabelText,
      openEntityLink,
      goToAlertRulesMenu,
      goToAlertRule,
      openUrlBlank,
      goToHost,
      goToVm,
      getPopupParent,
      refresh,
      isKeySilencedNow,
      vmIndexVersion,
      displayVm,
      onAlertCloseStart,
      onAlertClosed,
      maskOn,
      goToManagement,
      tr,
      trCountUnit,
      drawerItemMetricInlineText,
      drawerItemMetricInlineTitle,
      drawerItemMetricLineText,
      drawerItemMetricUi,
      drawerItemMetaText,
      hasSolutionUrl,
      openSolution,
      drawerItemAgeText,
      markAllAsRead,
      solutionSummaryText,
      solutionDescriptionText
    }
  }
}
</script>

<style scoped>
/* 컨테이너 */
.auto-alert-banner-container {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 2147483647;
  width: 100%;
  isolation: isolate;
  font-size: 0.7em;
}

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

.auto-alert-banner-container > * {
  position: relative;
  z-index: 1;
}

/* 리스트 */
.banner-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 2px 8px 4px;
}
.banner-list:empty {
  padding: 0;
}

/* ===== 요약(상단) 배너 ===== */

.summary-modern {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: nowrap;
}

.summary-modern-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1 1 auto;
}

.summary-modern-icon {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #ff4d4f;
  color: #ffffff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  font-size: 18px;
}

.summary-modern-text {
  min-width: 0;
  flex: 1 1 auto;
}

.summary-modern-title {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.summary-modern-title-text {
  font-size: 18px;
  font-weight: 800;
  line-height: 20px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-modern-count {
  font-size: 18px;
  font-weight: 700;
  color: #cf1322;
  flex: 0 0 auto;
}

.summary-modern-desc {
  margin-top: 2px;
  font-size: 12px;
  line-height: 18px;
  color: rgba(0, 0, 0, 0.65);
  display: flex;
  gap: 6px;
  min-width: 0;
}

.summary-desc-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-modern-actions {
  flex: 0 0 auto;
  white-space: nowrap;
}

/* ===== Drawer Theme ===== */

.drawer-title {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}

.drawer-title-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.drawer-title-main {
  font-size: 16px;
  font-weight: 700;
  color: rgba(0, 0, 0, 0.88);
  line-height: 1.2;
  min-width: 0;
}

.drawer-title-count {
  margin-left: 6px;
  color: #cf1322;
  font-weight: 700;
}

.drawer-title-sub {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.55);
  line-height: 1.35;
}

.drawer-stack {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.drawer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: #fafafa;
  border-bottom: 1px solid #f0f0f0;
}

.drawer-alert-viewport--modern {
  padding: 12px 16px;
  overflow: auto;
}

.drawer-list-card {
  border: none;
  background: transparent;
  overflow: visible;
  box-shadow: none;
}

.drawer-empty {
  padding: 18px 12px;
  text-align: center;
  color: rgba(0, 0, 0, 0.55);
  font-size: 13px;
}

/* ===== Drawer Item Card ===== */
.drawer-item-alert {
  position: relative !important;
  background: #fff !important;
  border: 1px solid #f0f0f0 !important;
  border-radius: 14px !important;
  margin-bottom: 4px !important;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.08);
  padding: 0 !important;
  overflow: hidden;
}

/* 닫기(X) 아이콘 */

/* Drawer Item Layout */
.drawer-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  padding-right: 25px;
}

.drawer-item-icon-circle {
  width: 32px;
  height: 32px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #fff1f0;
  border: 1px solid #ffccc7;
  color: #ff4d4f;
}

.drawer-item-body {
  flex: 1 1 auto;
  min-width: 0;
}

.drawer-item-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.drawer-item-meta-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
  line-height: 18px;
  color: rgba(0, 0, 0, 0.55);
}

.drawer-item-meta-left {
  min-width: 0;
  flex: 1 1 auto;
  white-space: normal;
  overflow: visible;
  text-overflow: clip;
  word-break: break-word;
}

.drawer-item-metric-hover {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  color: inherit;
  cursor: pointer;
}

.drawer-item-metric-hover .metric-k {
  font-size: 12px;
  font-weight: 700;
  color: rgba(0, 0, 0, 0.60);
  margin-right: 4px;
}

.drawer-item-metric-hover .metric-sep {
  margin: 0 4px;
  color: rgba(0, 0, 0, 0.35);
}

.drawer-item-metric-hover .metric-binary {
  font-weight: 700;
  color: rgba(0, 0, 0, 0.88);
}

.drawer-item-meta-right {
  flex: 0 0 auto;
  white-space: nowrap;
}

.drawer-item-divider {
  width: 100%;
  height: 1px;
  background: rgba(0, 0, 0, 0.08);
  margin: 10px 0;
}

.drawer-item-target-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
  font-size: 13px;
  line-height: 20px;
}

.target-label {
  font-weight: 700;
  color: rgba(0, 0, 0, 0.88);
  white-space: nowrap;
}

.target-values {
  min-width: 0;
  color: rgba(0, 0, 0, 0.88);
}

.target-more-popover {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 2px 0;
  min-width: 180px;
}

.target-more-item {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.target-more-link {
  color: rgba(0, 0, 0, 0.88);
  text-decoration: none;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.target-value-link {
  color: rgba(0, 0, 0, 0.88);
  text-decoration: none;
}

.target-value-link:hover {
  text-decoration: underline;
}

.target-value-metric {
  margin-left: 4px;
  color: rgba(0, 0, 0, 0.65);
  font-size: 12px;
  white-space: nowrap;
}

.target-sep {
  color: rgba(0, 0, 0, 0.4);
}

.target-more {
  margin-left: 6px;
  color: rgba(0, 0, 0, 0.6);
  text-decoration: none;
  font-weight: 700;
}

.target-more:hover {
  text-decoration: underline;
}

.more-pop-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 2px 0;
}

.more-pop-item {
  line-height: 20px;
}

.drawer-item-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(0, 0, 0, 0.88);
  padding-right: 0;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-item-title--link {
  cursor: pointer;
}
.drawer-item-title--link:hover {
  text-decoration: underline;
}

/* Targets */

/* '대상 호스트/대상 VM'은 태그 형태를 제거하고 라벨 텍스트로만 표시합니다 */

/* 실제 대상(호스트/VM)은 클릭 가능한 '칩'으로 표시합니다 */

/* Drawer Actions */
.drawer-item-actions {
  margin-top: 14px;
  display: flex;
  justify-content: flex-start;
  align-items: center;
}

/* 버튼 줄바꿈 방지 */

/* 아이콘/버튼 */
.icon-stack {
  position: relative;
  display: inline-flex;
  width: 16px;
  height: 16px;
  margin-right: 4px;
  vertical-align: -2px;
}

.icon-stack .icon-sound {
  font-size: 15px;
  line-height: 16px;
}

/* banner-actions 중복 제거: 이 정의 하나만 사용 */
.banner-actions {
  margin-left: 0;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  transform: none;
  font-size: 11px;
  flex-wrap: nowrap;
  white-space: nowrap;
}

/* 버튼 크기/정렬을 균일하게 유지합니다 */
.solution-menu,
.silence-menu,
.pause-btn {
  min-width: 108px;
  justify-content: center;
}
/* Popover */

/* 반응형 */
@media (max-width: 768px) {

  .banner-actions {
    max-width: 100%;
    overflow-x: auto;
  }

  .ant-layout.layout.mobile .sticky-sidebar > div {
    height: 0 !important;
    min-height: 0 !important;
  }
}

/* 요약/설명 내용을 박스로 감싸서 “구분”을 만듭니다 */

.solution-popover-box .solution-popover-text {
  white-space: normal;
  word-break: break-word;
  line-height: 1.5;
}
/* =========================================================
 * Wall Alerts (AutoAlertBanner + Drawer + Popover) Dark Mode
 * - body.dark-mode 기반
 * ========================================================= */

/* -------------------------
 * 1) 상단 메인 배너(요약)
 * ------------------------- */

/* --------------------------------
 * 2) Drawer 헤더(전체 ant-drawer)
 * - “상단이 안 보임” 해결을 위해 전역으로 강제
 * -------------------------------- */

/* --------------------------------
 * 3) AutoAlertBanner Drawer 본문 (wall-alert-drawer 범위)
 * -------------------------------- */

/* Drawer 상단 툴바(“모두 읽음 처리/닫기” 영역이 있으면) */
body.dark-mode .wall-alert-drawer .drawer-toolbar {
  background: #141414 !important;
  border-bottom: 1px solid #303030 !important;
}

body.dark-mode .wall-alert-drawer .drawer-toolbar,
body.dark-mode .wall-alert-drawer .drawer-toolbar * {
  color: rgba(255, 255, 255, 0.85) !important;
}

/* 카드(각 경보 항목) */

/* 카드 타이틀 */
body.dark-mode .wall-alert-drawer .drawer-item-title,
body.dark-mode .wall-alert-drawer .drawer-item-title * {
  color: rgba(255, 255, 255, 0.92) !important;
}

/* 메타 텍스트(최대/평균/임계값/시간 등) */
body.dark-mode .wall-alert-drawer .drawer-item-meta-row,
body.dark-mode .wall-alert-drawer .drawer-item-meta-left,
body.dark-mode .wall-alert-drawer .drawer-item-meta-right,
body.dark-mode .wall-alert-drawer .drawer-item-meta-row * {
  color: rgba(255, 255, 255, 0.74) !important;
}

/* 메트릭 값은 조금 더 선명하게 */

/* 카드 내부 구분선 */
body.dark-mode .wall-alert-drawer .drawer-item-divider {
  background: rgba(255, 255, 255, 0.12) !important;
}

/* 대상 라인(라벨/값 링크) */
body.dark-mode .wall-alert-drawer .drawer-item-target-line,
body.dark-mode .wall-alert-drawer .drawer-item-target-line * {
  color: rgba(255, 255, 255, 0.84) !important;
}

body.dark-mode .wall-alert-drawer .target-label {
  color: rgba(255, 255, 255, 0.72) !important;
}

body.dark-mode .wall-alert-drawer .target-value-link {
  color: rgba(255, 255, 255, 0.9) !important;
  text-decoration-color: rgba(255, 255, 255, 0.35) !important;
}

/* Drawer 버튼(해결방안/사일런스/일시정지) */

/* -------------------------
 * 4) Popover(해결방안/대상/메트릭)
 * ------------------------- */

body.dark-mode .target-more-popover,
body.dark-mode .target-more-popover * {
  color: rgba(255, 255, 255, 0.88) !important;
}

.solution-popover-text {
  white-space: pre-wrap;
  word-break: break-word;
}

/* 헤더: 과한 컬러 제거, 기존 템플릿 톤(연한 배경/보더)로 통일 */
:deep(.solution-popover-header) {
  padding: 12px 14px;
  background: #fafafa;
  border-bottom: 1px solid #f0f0f0;
  color: rgba(0, 0, 0, 0.88);
}

/* 상태별 포인트는 “왼쪽 얇은 라인”으로만 줍니다 */
:deep(.solution-popover-header.sp-header--critical) {
  border-left: 3px solid #ff4d4f;
}
:deep(.solution-popover-header.sp-header--warning) {
  border-left: 3px solid #faad14;
}
:deep(.solution-popover-header.sp-header--nodata) {
  border-left: 3px solid #8c8c8c;
}
:deep(.solution-popover-header.sp-header--info) {
  border-left: 3px solid #1677ff;
}

:deep(.sp-header-kicker) {
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.06em;
  color: rgba(0, 0, 0, 0.45);
  margin-bottom: 6px;
}

:deep(.sp-header-row) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

:deep(.sp-header-title) {
  font-size: 16px;
  font-weight: 800;
  line-height: 1.25;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.sp-header-sub) {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.35;
  color: rgba(0, 0, 0, 0.60);
}

/* 배지: Ant 팔레트처럼 “은은한 배경+보더” */
:deep(.sp-severity) {
  flex: 0 0 auto;
  height: 22px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
  line-height: 22px;
  border: 1px solid transparent;
}

:deep(.sp-severity--critical) {
  background: #fff1f0;
  border-color: #ffccc7;
  color: #cf1322;
}

:deep(.sp-severity--warning) {
  background: #fff7e6;
  border-color: #ffd591;
  color: #d48806;
}

:deep(.sp-severity--nodata) {
  background: #f5f5f5;
  border-color: #d9d9d9;
  color: #595959;
}

:deep(.sp-severity--info) {
  background: #e6f4ff;
  border-color: #91caff;
  color: #1677ff;
}

/* 다크모드: 헤더도 과하지 않게 */
:deep(body.dark-mode .solution-popover-header) {
  background: #141414;
  border-bottom-color: #303030;
  color: rgba(255, 255, 255, 0.88);
}

:deep(body.dark-mode .sp-header-kicker) {
  color: rgba(255, 255, 255, 0.55);
}

:deep(body.dark-mode .sp-header-sub) {
  color: rgba(255, 255, 255, 0.70);
}

:deep(body.dark-mode .sp-severity--critical) {
  background: rgba(255, 77, 79, 0.12);
  border-color: rgba(255, 77, 79, 0.30);
  color: rgba(255, 255, 255, 0.88);
}

:deep(body.dark-mode .sp-severity--warning) {
  background: rgba(250, 173, 20, 0.12);
  border-color: rgba(250, 173, 20, 0.30);
  color: rgba(255, 255, 255, 0.88);
}

:deep(body.dark-mode .sp-severity--nodata) {
  background: rgba(140, 140, 140, 0.18);
  border-color: rgba(140, 140, 140, 0.30);
  color: rgba(255, 255, 255, 0.88);
}

:deep(body.dark-mode .sp-severity--info) {
  background: rgba(22, 119, 255, 0.12);
  border-color: rgba(22, 119, 255, 0.30);
  color: rgba(255, 255, 255, 0.88);
}

:deep(.solution-popover .ant-popover-inner-content) {
  max-width: calc(100vw - 48px);
  max-height: 560px;
  overflow: auto;
  padding: 0;
}

/* unified 래퍼는 더 이상 높이/스크롤을 가지지 않습니다 */
:deep(.solution-popover-unified) {
  width: 700px;
  max-width: calc(100vw - 48px);
}

:deep(.solution-popover-body) {
  flex: 1 1 auto;
  min-height: 0;        /* flex 내부 overflow 필수 */
  overflow: auto;
  max-height: none;     /* 기존 max-height 있으면 제거 */
}

:deep(.solution-popover-header) {
  position: sticky;
  top: 0;
  z-index: 2;
}
</style>

<style>
:root { --autoBannerHeight: 0px; }

body.dark-mode .drawer-title-main,
body.dark-mode .drawer-title-sub {
  color: rgba(255, 255, 255, 0.88) !important;
}

/* 팝오버 외곽 폭 제한 */
.solution-popover.ant-popover {
  width: 700px;
  max-width: calc(100vw - 48px);
}

/* 스크롤 컨테이너는 inner-content가 담당 */
.solution-popover.ant-popover .ant-popover-inner-content {
  box-sizing: border-box;
  padding: 12px;
  max-height: 560px;
  overflow: auto;
}

/* 카드 래퍼 */
.solution-popover .solution-popover-unified {
  width: 100%;
  max-width: 100%;
  border-radius: 12px;
  overflow: hidden;
  background: #ffffff;
}

/* 헤더: 스크롤 중에도 상단 고정 */
.solution-popover .solution-popover-header {
  position: sticky;
  top: 0;
  z-index: 2;
  background: inherit;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  padding: 12px 14px;
}

/* body는 스크롤을 만들지 않음(스크롤은 inner-content가 담당) */
.solution-popover .solution-popover-body {
  overflow: visible;
  max-height: none;
  padding: 12px 14px;
}

/* 라벨 칩 */
.solution-popover .sp-body-label-chip {
  display: flex;
  align-items: center;
  height: 32px;
  padding: 0 12px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 800;
  line-height: 32px;
  color: rgba(0, 0, 0, 0.88);
  background: rgba(0, 0, 0, 0.02);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-left: 3px solid rgba(0, 0, 0, 0.18);
  margin: 0 0 8px;
}

/* 요약/해결방안 박스 */
.solution-popover .sp-body-box {
  border: 1px solid #f0f0f0;
  border-radius: 10px;
  padding: 10px 12px;
  background: #fafafa;
  color: rgba(0, 0, 0, 0.88);
  line-height: 1.45;
}

/* 섹션 간격 */
.solution-popover .sp-body-section {
  margin-bottom: 12px;
}
.solution-popover .sp-body-section:last-child {
  margin-bottom: 0;
}

/* 라벨 대비 내용만 살짝 안쪽으로(요약/해결방안 공통) */
.solution-popover .sp-body-section .sp-body-box {
  margin-left: 12px;
}

/* 들여쓰기로 인해 너무 답답하면 폭을 살짝 보정 */
.solution-popover .sp-body-section .sp-body-box {
  width: calc(100% - 12px);
}

.solution-popover .sp-body-label-chip {
  margin-bottom: 6px;
}

/* 다크 모드 */
body.dark-mode .solution-popover .solution-popover-unified {
  background: #0f0f0f;
}

body.dark-mode .solution-popover .solution-popover-header {
  border-bottom-color: rgba(255, 255, 255, 0.12);
}

body.dark-mode .solution-popover .sp-body-label-chip {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.12);
  border-left-color: rgba(255, 255, 255, 0.25);
  color: rgba(255, 255, 255, 0.88);
}

body.dark-mode .solution-popover .sp-body-box {
  background: #141414;
  border-color: #303030;
  color: rgba(255, 255, 255, 0.88);
}
/* 1) 상단 메인 배너(요약)도 어둡게 강제 */
body.dark-mode .auto-alert-banner-container .ant-alert.alert-summary {
  background: #141414 !important;
  border-color: #303030 !important;
}

body.dark-mode .auto-alert-banner-container .summary-modern-title-text,
body.dark-mode .auto-alert-banner-container .summary-modern-desc,
body.dark-mode .auto-alert-banner-container .summary-modern-count,
body.dark-mode .auto-alert-banner-container .summary-modern-actions .ant-btn,
body.dark-mode .auto-alert-banner-container .summary-modern-actions .ant-btn > span,
body.dark-mode .auto-alert-banner-container .summary-modern-actions .ant-btn .anticon {
  color: rgba(255, 255, 255, 0.90) !important;
}

body.dark-mode .auto-alert-banner-container .summary-modern-actions .ant-btn:not(.ant-btn-primary):not(.ant-btn-dangerous) {
  background: rgba(255, 255, 255, 0.06) !important;
  border-color: rgba(255, 255, 255, 0.18) !important;
}

/* 2) Drawer 바탕 */
body.dark-mode .wall-alert-drawer .ant-drawer-body {
  background: #0f0f0f !important;
}

/* 3) 카드(각 경보 항목) - 흰 배경 !important를 다크에서 강제 덮어씀 */
body.dark-mode .wall-alert-drawer .drawer-item-alert,
body.dark-mode .wall-alert-drawer .ant-alert.drawer-item-alert {
  background: #141414 !important;
  border-color: #303030 !important;
}

/* 4) 카드 텍스트/구분선/링크 */
body.dark-mode .wall-alert-drawer .drawer-item-title,
body.dark-mode .wall-alert-drawer .drawer-item-title * {
  color: rgba(255, 255, 255, 0.92) !important;
}

body.dark-mode .wall-alert-drawer .drawer-item-meta-row,
body.dark-mode .wall-alert-drawer .drawer-item-meta-left,
body.dark-mode .wall-alert-drawer .drawer-item-meta-right,
body.dark-mode .wall-alert-drawer .drawer-item-meta-row * {
  color: rgba(255, 255, 255, 0.74) !important;
}

body.dark-mode .wall-alert-drawer .drawer-item-divider {
  background: rgba(255, 255, 255, 0.12) !important;
}

body.dark-mode .wall-alert-drawer .drawer-item-target-line,
body.dark-mode .wall-alert-drawer .drawer-item-target-line * {
  color: rgba(255, 255, 255, 0.84) !important;
}

body.dark-mode .wall-alert-drawer .target-value-link {
  color: rgba(255, 255, 255, 0.90) !important;
  text-decoration-color: rgba(255, 255, 255, 0.35) !important;
}

/* 5) 카드 하단 버튼(해결방안/사일런스/일시정지) */
body.dark-mode .wall-alert-drawer .drawer-item-actions .ant-btn {
  background: rgba(255, 255, 255, 0.06) !important;
  border-color: rgba(255, 255, 255, 0.18) !important;
  color: rgba(255, 255, 255, 0.90) !important;
}

body.dark-mode .wall-alert-drawer .drawer-item-actions .ant-btn > span,
body.dark-mode .wall-alert-drawer .drawer-item-actions .ant-btn .anticon {
  color: rgba(255, 255, 255, 0.90) !important;
}

/* 6) 해결방안 팝오버 헤더 텍스트 */
body.dark-mode .solution-popover .sp-header-kicker {
  color: rgba(255, 255, 255, 0.55) !important;
}

body.dark-mode .solution-popover .sp-header-title {
  color: rgba(255, 255, 255, 0.92) !important;
}

/* Ant Drawer 헤더/타이틀 영역을 전역으로 강제 */
body.dark-mode .ant-drawer-header,
body.dark-mode .ant-drawer-title,
body.dark-mode .ant-drawer-close,
body.dark-mode .ant-drawer-close .anticon {
  background: #141414 !important;
  border-bottom-color: #303030 !important;
  color: rgba(255, 255, 255, 0.90) !important;
}

/* 헤더 내부 텍스트(커스텀 title 슬롯 포함) */
body.dark-mode .ant-drawer-header * {
  color: rgba(255, 255, 255, 0.88) !important;
}

/* wall-alert-drawer 범위의 drawer-title 텍스트는 더 선명하게 */
body.dark-mode .wall-alert-drawer .drawer-title-main,
body.dark-mode .wall-alert-drawer .drawer-title-main * {
  color: rgba(255, 255, 255, 0.92) !important;
}

body.dark-mode .wall-alert-drawer .drawer-title-sub,
body.dark-mode .wall-alert-drawer .drawer-title-sub * {
  color: rgba(255, 255, 255, 0.78) !important;
}

/* 상단 툴바(모두 읽음/닫기 줄)도 배경/보더 통일 */
body.dark-mode .wall-alert-drawer .drawer-toolbar {
  background: #141414 !important;
  border-bottom: 1px solid #303030 !important;
}

body.dark-mode .wall-alert-drawer .drawer-toolbar .ant-btn-link,
body.dark-mode .wall-alert-drawer .drawer-toolbar .ant-btn-link > span,
body.dark-mode .wall-alert-drawer .drawer-toolbar .ant-btn-link .anticon {
  color: rgba(255, 255, 255, 0.88) !important;
}
</style>
