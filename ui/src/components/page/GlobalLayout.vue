<template>
  <div>
    <announcement-banner ref="announceRef" />
    <AutoAlertBanner ref="autoRef" />

    <a-affix v-if="isShutdown" :offsetTop="0">
      <a-alert
        :message="$t('message.shutdown.triggered')"
        type="error"
        banner
        :showIcon="false"
        class="shutdownHeader"
        ref="shutdownRef"
      />
    </a-affix>

    <div class="banner-spacer" :style="{ height: combinedBannerHeight + 'px' }" aria-hidden="true"></div>

    <a-layout class="layout" :class="[device]">
      <div class="sticky-sidebar">
        <template v-if="isSideMenu()">
          <a-drawer
            v-if="isMobile()"
            :wrapClassName="'drawer-sider ' + navTheme"
            :closable="false"
            :visible="collapsed"
            placement="left"
            @close="() => (collapsed = false)"
          >
            <side-menu
              :menus="menus"
              :theme="navTheme"
              :collapsed="false"
              :collapsible="true"
              mode="inline"
              :style="{ paddingBottom: isSidebarVisible ? '300px' : '0' }"
              @menuSelect="menuSelect"
            />
          </a-drawer>

          <side-menu
            v-else
            mode="inline"
            :menus="menus"
            :theme="navTheme"
            :collapsed="collapsed"
            :collapsible="true"
            :style="{ paddingBottom: isSidebarVisible ? '300px' : '0' }"
          />
        </template>

        <template v-else>
          <a-drawer
            v-if="isMobile()"
            :wrapClassName="'drawer-sider ' + navTheme"
            placement="left"
            @close="() => (collapsed = false)"
            :closable="false"
            :visible="collapsed"
          >
            <side-menu
              :menus="menus"
              :theme="navTheme"
              :collapsed="false"
              :collapsible="true"
              mode="inline"
              :style="{ paddingBottom: isSidebarVisible ? '300px' : '0' }"
              @menuSelect="menuSelect"
            />
          </a-drawer>
        </template>

        <drawer
          :visible="showSetting"
          placement="right"
          v-if="isAdmin && (isDevelopmentMode || allowSettingTheme)"
        >
          <template #handler>
            <a-button type="primary" size="large">
              <close-outlined v-if="showSetting" />
              <setting-outlined v-else />
            </a-button>
          </template>
          <template #drawer>
            <setting :visible="showSetting" />
          </template>
        </drawer>
      </div>

      <div style="position: fixed; bottom: 45px; right: 0; z-index: 100;">
        <a-button
          type="primary"
          @click="toggleSidebar"
          style="width: 40px; height: 40px; padding: 0; background: #aaa; border: none; color: #fff;"
        >
          <ScheduleOutlined />
        </a-button>
      </div>

      <event-sidebar
        :isVisible="isSidebarVisible"
        ref="eventSidebar"
        @update:isVisible="isSidebarVisible = $event"
      />

      <a-layout
        :class="[layoutMode, `content-width-${contentWidth}`]"
        :style="{ paddingLeft: contentPaddingLeft, minHeight: '100vh', paddingBottom: isSidebarVisible ? '300px' : '0' }"
      >
        <div class="sticky-header">
          <global-header
            :mode="layoutMode"
            :menus="menus"
            :theme="navTheme"
            :collapsed="collapsed"
            :device="device"
            @toggle="toggle"
          />
        </div>

        <a-button
          v-if="showClear"
          type="default"
          size="small"
          class="button-clear-notification"
          @click="onClearNotification"
        >
          {{ $t('label.clear.notification') }}
        </a-button>

        <a-layout-content
          class="layout-content"
          :class="{ 'is-header-fixed': fixedHeader }"
          :style="{ paddingBottom: isSidebarVisible ? '300px' : '0' }"
        >
          <slot />
        </a-layout-content>

        <a-layout-footer
          style="padding: 0; transition: padding-bottom 0.3s;"
          :style="{ paddingBottom: isSidebarVisible ? '300px' : '0' }"
        >
          <global-footer />
        </a-layout-footer>
      </a-layout>
    </a-layout>
  </div>
</template>

<script>
import SideMenu from '@/components/menu/SideMenu'
import GlobalHeader from '@/components/page/GlobalHeader'
import GlobalFooter from '@/components/page/GlobalFooter'
import { triggerWindowResizeEvent } from '@/utils/util'
import { mapState, mapActions } from 'vuex'
import { mixin, mixinDevice } from '@/utils/mixin.js'
import { isAdmin } from '@/role'
import { api } from '@/api'
import Drawer from '@/components/widgets/Drawer'
import Setting from '@/components/view/Setting.vue'
import EventSidebar from '@/components/view/EventSidebar.vue'
import AnnouncementBanner from '@/components/header/AnnouncementBanner.vue'
import AutoAlertBanner from '@/components/header/AutoAlertBanner.vue'

const HEADER_FIXED_PX = 78

export default {
  name: 'GlobalLayout',
  components: {
    SideMenu,
    GlobalHeader,
    GlobalFooter,
    Drawer,
    Setting,
    EventSidebar,
    AnnouncementBanner,
    AutoAlertBanner
  },
  mixins: [mixin, mixinDevice],
  data () {
    return {
      collapsed: false,
      menus: [],
      showSetting: false,
      showClear: false,
      isSidebarVisible: false,
      announceHeight: 0,
      autoBannerHeight: 0,
      shutdownHeight: 0,
      combinedBannerHeight: 0,
      recalcTimer: null,
      lastAffixHeaderPx: -1,
      lastAffixContentPx: -1,
      roAnnounce: null
    }
  },
  computed: {
    ...mapState({
      mainMenu: state => state.permission.addRouters
    }),
    isAdmin () { return isAdmin() },
    isDevelopmentMode () { return process.env.NODE_ENV === 'development' },
    allowSettingTheme () { return this.$config.allowSettingTheme },
    contentPaddingLeft () {
      if (!this.fixSidebar || this.isMobile()) return '0'
      if (this.sidebarOpened) return '256px'
      return '80px'
    },
    isShutdown () { return this.$store.getters.shutdownTriggered },
    headerHeight () { return this.fixedHeader ? HEADER_FIXED_PX : 0 }
  },
  watch: {
    sidebarOpened (val) { this.collapsed = !val },
    mainMenu (newMenu) { this.menus = newMenu.find(item => item.path === '/').children },
    '$store.getters.countNotify' (n) { this.showClear = !!(n && n > 0) },
    isShutdown () { this.measureShutdown() }
  },
  provide () { return { parentToggleSetting: this.toggleSetting } },
  created () {
    this.menus = this.mainMenu.find(item => item.path === '/').children
    this.collapsed = !this.sidebarOpened
    if ('readyForShutdown' in this.$store.getters.apis) {
      const job = setInterval(this.checkShutdown, 5000)
      this.$store.commit('SET_READY_FOR_SHUTDOWN_POLLING_JOB', job)
    }
  },
  mounted () {
    try {
      const bootH = Number(localStorage.getItem('autoAlertBanner.lastHeight') || 0)
      if (!Number.isNaN(bootH) && bootH >= 0) {
        this.autoBannerHeight = bootH
        document.documentElement.style.setProperty('--autoBannerHeight', bootH + 'px')
        // ✅ 추가: 첫 프레임부터 --affixTopHeader를 맞춰 메뉴/헤더 정렬
        this.updateAffixTopVars() // ← 이 한 줄을 debouncedRecalc() 이전에 호출
        this.debouncedRecalc && this.debouncedRecalc()
      }
    } catch (_) {}
    window.addEventListener('auto-alert-banner:height', this.onAutoBannerHeight)
    window.addEventListener('resize', this.onResize)

    // 닫힘 이벤트는 참조만 하되, 높이 반영은 height 이벤트로 즉시 처리합니다.
    window.addEventListener('auto-alert-banner:closing', this.onAutoBannerClosing)
    window.addEventListener('auto-alert-banner:closed', this.onAutoBannerClosed)

    try {
      if ('ResizeObserver' in window) {
        const el = this.$refs?.announceRef?.$el
        if (el) {
          this.roAnnounce = new ResizeObserver(() => {
            const h = el?.offsetHeight || 0
            if (h !== this.announceHeight) {
              this.announceHeight = h
              this.debouncedRecalc()
            }
          })
          this.roAnnounce.observe(el)
        }
      }
    } catch (_) {}

    this.$nextTick(() => {
      this.measureAnnouncement()
      this.measureShutdown(true)
      this.recalcCombined()
    })

    const n = this.$store.getters.countNotify
    this.showClear = !!(n && n > 0)
  },
  beforeUnmount () {
    window.removeEventListener('auto-alert-banner:height', this.onAutoBannerHeight)
    window.removeEventListener('resize', this.onResize)
    window.removeEventListener('auto-alert-banner:closing', this.onAutoBannerClosing)
    window.removeEventListener('auto-alert-banner:closed', this.onAutoBannerClosed)
    try { this.roAnnounce && this.roAnnounce.disconnect() } catch (_) {}
    document.body.classList.remove('dark')
    if (this.recalcTimer) clearTimeout(this.recalcTimer)
    try { localStorage.setItem('autoAlertBanner.lastHeight', '0') } catch (_) {}
  },
  methods: {
    onResize () {
      const newAnnounceHeight = this.$refs.announceRef?.$el?.offsetHeight || 0
      if (newAnnounceHeight !== this.announceHeight) {
        this.announceHeight = newAnnounceHeight
        this.debouncedRecalc()
      }
    },
    // 높이 이벤트를 항상 신뢰하여 즉시 반영합니다(감소도 포함).
    onAutoBannerHeight (evt) {
      const h = Math.max(0, Number(evt && evt.detail && evt.detail.height) || 0)
      if (h !== this.autoBannerHeight) {
        this.autoBannerHeight = h
        // 선택(권장): 캐시 최신화
        try { localStorage.setItem('autoAlertBanner.lastHeight', String(h)) } catch (_) {}
        // 지연 없이 즉시 반영
        this.recalcCombined()
      }
    },
    // 참고용 훅: 필요 시 지연 재계산만 수행합니다.
    onAutoBannerClosing () {
      // 닫힘 시작 시 별도 락을 걸지 않습니다.
    },
    onAutoBannerClosed () {
      // 닫힘 완료 후 한 번 더 재계산하여 최종값을 맞춥니다.
      this.debouncedRecalc()
    },
    measureAnnouncement () {
      this.announceHeight = this.$refs.announceRef?.$el?.offsetHeight || 0
    },
    measureShutdown (runImmediately = false) {
      const newShutdownHeight = this.isShutdown ? 25 : 0
      if (newShutdownHeight !== this.shutdownHeight) {
        this.shutdownHeight = newShutdownHeight
        if (runImmediately) this.recalcCombined()
        else this.debouncedRecalc()
      }
    },
    debouncedRecalc () {
      if (this.recalcTimer) clearTimeout(this.recalcTimer)
      this.recalcTimer = setTimeout(() => {
        this.recalcCombined()
      }, 80)
    },
    recalcCombined () {
      const next = this.announceHeight + this.autoBannerHeight + this.shutdownHeight
      if (next === this.combinedBannerHeight) return
      this.combinedBannerHeight = next
      this.updateAffixTopVars()
    },
    updateAffixTopVars () {
      const root = document.documentElement
      const totalBannerHeight = this.combinedBannerHeight
      const headerHeight = this.headerHeight
      const contentAffixTop = totalBannerHeight + headerHeight

      if (this.lastAffixHeaderPx !== totalBannerHeight) {
        root.style.setProperty('--affixTopHeader', `${totalBannerHeight}px`)
        this.lastAffixHeaderPx = totalBannerHeight
      }
      if (this.lastAffixContentPx !== contentAffixTop) {
        root.style.setProperty('--affixTopContent', `${contentAffixTop}px`)
        this.lastAffixContentPx = contentAffixTop
      }
    },

    toggleSidebar () {
      this.isSidebarVisible = true
      this.$refs.eventSidebar.openSiderBar()
    },
    ...mapActions(['setSidebar']),
    toggle () {
      this.collapsed = !this.collapsed
      this.setSidebar(!this.collapsed)
      triggerWindowResizeEvent()
    },
    paddingCalc () {
      let left = ''
      if (this.sidebarOpened) {
        left = this.isDesktop() ? '256px' : '80px'
      } else {
        left = this.isMobile() ? '0' : (this.fixSidebar ? '80px' : '0')
      }
      return left
    },
    menuSelect () { if (!this.isDesktop()) this.collapsed = false },
    toggleSetting (showSetting) { this.showSetting = showSetting },
    onClearNotification () {
      this.$notification.destroy()
      this.$store.commit('SET_COUNT_NOTIFY', 0)
    },
    checkShutdown () {
      if (!this.$store.getters.features.securityfeaturesenabled) {
        api('readyForShutdown', {}).then(json => {
          this.$store.dispatch(
            'SetShutdownTriggered',
            json.readyforshutdownresponse.readyforshutdown.shutdowntriggered || false
          )
        })
      }
    }
  }
}
</script>

<style lang="less">
/* 배너 영역만큼 컨텐츠를 밀어내는 스페이서 */
.banner-spacer {
  width: 100%;
  transition: height 0.18s ease;
  will-change: height;
}

/* 고정 헤더 사용 시 컨텐츠 상단 여백 */
.layout-content {
  &.is-header-fixed {
    margin: 78px 12px 0;
    transition: padding-bottom 0.3s ease;
  }
}

/* 사이드 드로어(모바일/좁은 화면) 스킨 */
.ant-drawer.drawer-sider {
  .sider { box-shadow: none; }

  &.dark {
    .ant-drawer-content { background-color: rgb(0, 21, 41); max-width: 256px; }
    .ant-drawer-content-wrapper { width: 256px !important; }
  }

  &.light {
    box-shadow: none;
    .ant-drawer-content { background-color: #fff; max-width: 256px; }
    .ant-drawer-content-wrapper { width: 256px !important; }
  }

  .ant-drawer-body { padding: 0; }
}

/* 셧다운 알림 배너 */
.shutdownHeader {
  font-weight: bold;
  height: 25px;
  text-align: center;
  padding: 0;
  margin: 0;
  width: 100vw;
  position: absolute;
}

/* 고정 헤더 위치 보정 */
.layout.ant-layout .sidemenu .ant-header-fixedHeader { top: auto !important; }

/* 전역 오프셋 변수(스크립트에서 갱신) */
:root {
  --affixTopHeader: 0px;
  --affixTopContent: 0px;
}

/* 상단 글로벌 헤더를 배너 아래에 고정 */
.sticky-header {
  position: sticky;
  top: var(--affixTopHeader);
  z-index: 100;
}

/* 페이지 내부에서 사용하는 <a-affix> (툴바 등) */
.layout .ant-layout-content .ant-affix {
  top: var(--affixTopContent) !important;
  z-index: 95 !important;
}

/* Sticky 사이드바 레이아웃 */
.sticky-sidebar {
  position: sticky;
  top: var(--affixTopHeader);
  z-index: 200;
  height: calc(100vh - var(--affixTopHeader));
  max-height: calc(100vh - var(--affixTopHeader));
  overflow: visible;
}
.sticky-sidebar > * {
  height: 100%;
  min-height: 0;
}
.sticky-sidebar :deep(.ant-layout-sider) {
  height: 100%;
  max-height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.sticky-sidebar :deep(.ant-layout-sider-children) {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.sticky-sidebar :deep(.ant-menu),
.sticky-sidebar :deep(.ant-menu-root) {
  max-height: 100%;
  overflow-y: auto;
}
</style>
