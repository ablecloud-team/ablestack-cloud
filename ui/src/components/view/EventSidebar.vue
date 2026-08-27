// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <section
    v-show="isVisible"
    class="bottom-activity-panel"
    :class="{ 'is-collapsed': collapsed, 'is-resizing': resizing }"
    :style="{ height: currentHeight + 'px' }"
    aria-label="Activity panel">
    <div
      class="bottom-activity-panel__resize-handle"
      role="separator"
      aria-orientation="horizontal"
      :aria-valuemin="minHeight"
      :aria-valuemax="maxHeight"
      :aria-valuenow="currentHeight"
      tabindex="0"
      @dblclick="resetHeight"
      @keydown="onResizeKeydown"
      @pointerdown="startResize">
      <span aria-hidden="true"></span>
    </div>

    <header class="bottom-activity-panel__header">
      <button
        v-if="canListEvents"
        type="button"
        class="bottom-activity-panel__tab"
        :class="{ 'is-active': activeTab === 'events' }"
        :aria-selected="activeTab === 'events'"
        @click="selectTab('events')">
        {{ $t('label.recent.events') }}
      </button>
      <button
        v-if="canListAlerts"
        type="button"
        class="bottom-activity-panel__tab"
        :class="{ 'is-active': activeTab === 'alerts' }"
        :aria-selected="activeTab === 'alerts'"
        @click="selectTab('alerts')">
        {{ $t('label.alerts') }}
      </button>

      <div class="bottom-activity-panel__header-actions">
        <a-button
          type="text"
          size="small"
          :aria-label="$t('label.close')"
          @click="toggleCollapsed">
          <up-outlined v-if="collapsed" />
          <down-outlined v-else />
        </a-button>
        <a-button
          type="text"
          size="small"
          :aria-label="$t('label.close')"
          @click="closeSidebar">
          <close-outlined />
        </a-button>
      </div>
    </header>

    <div v-show="!collapsed" class="bottom-activity-panel__body">
      <a-table
        v-if="activeTab === 'events'"
        :columns="eventColumns"
        :dataSource="events"
        rowKey="id"
        :bordered="false"
        :pagination="false"
        :scroll="{ x: 1160, y: tableBodyHeight }"
        size="small"
        class="bottom-activity-panel__table" />

      <a-table
        v-else
        :columns="alertColumns"
        :dataSource="alerts"
        rowKey="id"
        :bordered="false"
        :pagination="false"
        :scroll="{ x: 760, y: tableBodyHeight }"
        size="small"
        class="bottom-activity-panel__table" />
    </div>

    <footer v-show="!collapsed" class="bottom-activity-panel__footer">
      <span>{{ activeRows.length }}</span>
    </footer>
  </section>
</template>

<script>
import { DownOutlined, UpOutlined } from '@ant-design/icons-vue'
import { api } from '@/api'

const DEFAULT_HEIGHT = 220
const MIN_HEIGHT = 140
const COLLAPSED_HEIGHT = 36
const MAX_HEIGHT = 520
const HEIGHT_STORAGE_KEY = 'ablestack.bottomActivityPanel.height.v1'

export default {
  name: 'EventSidebar',
  components: {
    DownOutlined,
    UpOutlined
  },
  props: {
    isVisible: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      activeTab: 'events',
      alerts: [],
      collapsed: false,
      events: [],
      eventListBarMinutes: 5 * 60 * 1000,
      expandedHeight: DEFAULT_HEIGHT,
      refreshInterval: null,
      resizing: false,
      resizeStartHeight: DEFAULT_HEIGHT,
      resizeStartY: 0,
      eventColumns: [
        { title: this.$t('label.level'), dataIndex: 'level', key: 'level', width: 80, ellipsis: true },
        { title: this.$t('label.type'), dataIndex: 'type', key: 'type', width: 150, ellipsis: true },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 100, ellipsis: true },
        { title: this.$t('label.description'), dataIndex: 'description', key: 'description', width: 360, ellipsis: true },
        { title: this.$t('label.resource'), dataIndex: 'resourcename', key: 'resourcename', width: 160, ellipsis: true },
        { title: this.$t('label.username'), dataIndex: 'username', key: 'username', width: 120, ellipsis: true },
        { title: this.$t('label.account'), dataIndex: 'account', key: 'account', width: 120, ellipsis: true },
        { title: this.$t('label.domain'), dataIndex: 'domain', key: 'domain', width: 120, ellipsis: true },
        {
          title: this.$t('label.created'),
          dataIndex: 'created',
          key: 'created',
          width: 170,
          ellipsis: true,
          customRender: ({ text }) => this.formatDate(text)
        }
      ],
      alertColumns: [
        { title: this.$t('label.name'), dataIndex: 'name', key: 'name', width: 180, ellipsis: true },
        { title: this.$t('label.description'), dataIndex: 'description', key: 'description', width: 420, ellipsis: true },
        { title: this.$t('label.type'), dataIndex: 'type', key: 'type', width: 120, ellipsis: true },
        {
          title: this.$t('label.sent'),
          dataIndex: 'sent',
          key: 'sent',
          width: 170,
          ellipsis: true,
          customRender: ({ text }) => this.formatDate(text)
        }
      ]
    }
  },
  computed: {
    canListEvents () {
      return 'listEvents' in this.$store.getters.apis
    },
    canListAlerts () {
      return 'listAlerts' in this.$store.getters.apis
    },
    currentHeight () {
      return this.collapsed ? COLLAPSED_HEIGHT : this.expandedHeight
    },
    minHeight () {
      return this.collapsed ? COLLAPSED_HEIGHT : MIN_HEIGHT
    },
    maxHeight () {
      return Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, Math.floor(window.innerHeight * 0.5)))
    },
    tableBodyHeight () {
      return Math.max(54, this.expandedHeight - 94)
    },
    activeRows () {
      return this.activeTab === 'events' ? this.events : this.alerts
    }
  },
  watch: {
    isVisible (visible) {
      if (visible) {
        this.openSidebar()
      } else {
        this.stopRefresh()
      }
    }
  },
  mounted () {
    const storedHeight = Number(window.localStorage.getItem(HEIGHT_STORAGE_KEY))
    if (Number.isFinite(storedHeight) && storedHeight > 0) {
      this.expandedHeight = this.clampHeight(storedHeight)
    }
    if (!this.canListEvents && this.canListAlerts) {
      this.activeTab = 'alerts'
    }
    if (this.isVisible) {
      this.openSidebar()
    }
  },
  beforeUnmount () {
    this.stopRefresh()
    this.stopResize()
  },
  methods: {
    async openSidebar () {
      this.collapsed = false
      await this.loadRecentMinutes()
      await this.fetchActiveTab()
      this.startRefresh()
    },
    openSiderBar () {
      return this.openSidebar()
    },
    closeSidebar () {
      this.stopRefresh()
      this.$emit('update:isVisible', false)
    },
    toggleCollapsed () {
      this.collapsed = !this.collapsed
      if (this.collapsed) {
        this.stopRefresh()
      } else {
        this.fetchActiveTab()
        this.startRefresh()
      }
    },
    selectTab (tab) {
      this.activeTab = tab
      if (this.collapsed) {
        this.collapsed = false
      }
      this.fetchActiveTab()
      this.startRefresh()
    },
    startRefresh () {
      this.stopRefresh()
      if (!this.isVisible || this.collapsed) return
      this.refreshInterval = window.setInterval(this.fetchActiveTab, 5000)
    },
    stopRefresh () {
      if (this.refreshInterval) {
        window.clearInterval(this.refreshInterval)
        this.refreshInterval = null
      }
    },
    async loadRecentMinutes () {
      if (this.$store.getters.userInfo.roletype !== 'Admin') return
      try {
        const response = await api('listConfigurations', { name: 'event.recent.minutes' })
        const minutes = Number(response?.listconfigurationsresponse?.configuration?.[0]?.value || 5)
        this.eventListBarMinutes = minutes * 60 * 1000
      } catch (error) {
        console.error('Error getting recent event configuration:', error)
      }
    },
    fetchActiveTab () {
      return this.activeTab === 'alerts' ? this.fetchAlerts() : this.fetchEvents()
    },
    async fetchEvents () {
      if (!this.canListEvents) return
      try {
        const response = await api('listEvents', { page: 1, pagesize: 20, listall: true })
        const events = response?.listeventsresponse?.event || []
        this.events = this.filterRecentEvents(events, this.eventListBarMinutes)
      } catch (error) {
        console.error('Error getting event list:', error)
      }
    },
    async fetchAlerts () {
      if (!this.canListAlerts) return
      try {
        const response = await api('listAlerts', { page: 1, pagesize: 20, listall: true })
        this.alerts = response?.listalertsresponse?.alert || []
      } catch (error) {
        console.error('Error getting alert list:', error)
      }
    },
    filterRecentEvents (events, timeRange = 5 * 60 * 1000) {
      const timeThreshold = Date.now() - timeRange
      return events.filter(event => new Date(event.created).getTime() >= timeThreshold).slice(0, 20)
    },
    formatDate (dateString) {
      if (!dateString) return '-'
      const date = new Date(dateString)
      if (Number.isNaN(date.getTime())) return dateString
      const locale = String(this.$i18n.locale || 'en-GB').replace('_', '-')
      return new Intl.DateTimeFormat(locale, {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false
      }).format(date)
    },
    startResize (event) {
      if (this.collapsed || event.button !== 0) return
      this.resizing = true
      this.resizeStartY = event.clientY
      this.resizeStartHeight = this.expandedHeight
      if (event.currentTarget.setPointerCapture) {
        event.currentTarget.setPointerCapture(event.pointerId)
      }
      window.addEventListener('pointermove', this.resizePanel)
      window.addEventListener('pointerup', this.stopResize, { once: true })
      document.body.classList.add('activity-panel-resizing')
    },
    resizePanel (event) {
      if (!this.resizing) return
      this.expandedHeight = this.clampHeight(this.resizeStartHeight + this.resizeStartY - event.clientY)
    },
    stopResize () {
      if (!this.resizing) return
      this.resizing = false
      window.removeEventListener('pointermove', this.resizePanel)
      document.body.classList.remove('activity-panel-resizing')
      window.localStorage.setItem(HEIGHT_STORAGE_KEY, String(this.expandedHeight))
    },
    onResizeKeydown (event) {
      if (!['ArrowUp', 'ArrowDown'].includes(event.key)) return
      event.preventDefault()
      if (this.collapsed) this.collapsed = false
      const step = event.shiftKey ? 48 : 16
      const direction = event.key === 'ArrowUp' ? 1 : -1
      this.expandedHeight = this.clampHeight(this.expandedHeight + step * direction)
      window.localStorage.setItem(HEIGHT_STORAGE_KEY, String(this.expandedHeight))
    },
    resetHeight () {
      this.collapsed = false
      this.expandedHeight = this.clampHeight(DEFAULT_HEIGHT)
      window.localStorage.setItem(HEIGHT_STORAGE_KEY, String(this.expandedHeight))
    },
    clampHeight (height) {
      return Math.min(this.maxHeight, Math.max(MIN_HEIGHT, Math.round(height)))
    }
  }
}
</script>

<style lang="less">
.bottom-activity-panel {
  position: relative;
  flex: 0 0 auto;
  min-width: 0;
  color: var(--ui-text-secondary);
  background: var(--ui-bg-surface);
  border-top: 1px solid var(--ui-border);
  overflow: hidden;

  &.is-resizing { user-select: none; }

  &__resize-handle {
    position: absolute;
    z-index: 2;
    top: 0;
    right: 0;
    left: 0;
    height: 6px;
    cursor: ns-resize;
    display: flex;
    justify-content: center;
    align-items: center;

    span {
      width: 42px;
      height: 2px;
      border-radius: 1px;
      background: var(--ui-border);
      opacity: 0;
      transition: opacity 0.15s ease;
    }

    &:hover span,
    &:focus span {
      opacity: 1;
      background: var(--ui-focus);
    }
  }

  &__header {
    height: 36px;
    padding: 4px 8px 0;
    display: flex;
    align-items: flex-end;
    gap: 4px;
    background: var(--ui-bg-elevated);
    border-bottom: 1px solid var(--ui-border);
  }

  &__tab {
    height: 32px;
    padding: 0 12px;
    color: var(--ui-text-secondary);
    background: transparent;
    border: 0;
    border-bottom: 2px solid transparent;
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;

    &:hover,
    &:focus-visible { color: var(--ui-text-primary); }

    &.is-active {
      color: var(--ui-focus);
      border-bottom-color: var(--ui-focus);
    }
  }

  &__header-actions {
    margin-left: auto;
    height: 32px;
    display: flex;
    align-items: center;

    .ant-btn {
      width: 30px;
      height: 30px;
      padding: 0;
      color: var(--ui-text-muted);

      &:hover,
      &:focus-visible {
        color: var(--ui-text-primary);
      }

      .anticon {
        display: inline-flex;
        font-size: 14px;
      }
    }
  }

  &__body {
    height: calc(100% - 58px);
    min-height: 0;
    overflow: hidden;
  }

  &__table {
    height: 100%;

    .ant-table { font-size: 12px; }

    .ant-table-thead > tr > th,
    .ant-table-tbody > tr > td {
      height: 30px;
      padding: 4px 10px;
      white-space: nowrap;
    }

    .ant-table-thead > tr > th {
      position: sticky;
      top: 0;
      z-index: 1;
      font-size: 12px;
      font-weight: 600;
    }

    .ant-table-body,
    .ant-table-content {
      scrollbar-width: thin;
      scrollbar-color: var(--ui-scroll-thumb) transparent;
    }

    .ant-table-body::-webkit-scrollbar,
    .ant-table-content::-webkit-scrollbar {
      width: 6px;
      height: 6px;
    }

    .ant-table-body::-webkit-scrollbar-thumb,
    .ant-table-content::-webkit-scrollbar-thumb {
      background: var(--ui-scroll-thumb);
      border-radius: 3px;
    }
  }

  &__footer {
    height: 22px;
    padding: 0 10px;
    display: flex;
    justify-content: flex-end;
    align-items: center;
    color: var(--ui-text-muted);
    background: var(--ui-bg-elevated);
    border-top: 1px solid var(--ui-border);
    font-size: 11px;
  }

  &.is-collapsed {
    .bottom-activity-panel__resize-handle { cursor: default; }
    .bottom-activity-panel__header { border-bottom: 0; }
  }
}

body.activity-panel-resizing {
  cursor: ns-resize !important;
  user-select: none !important;
}

@media (max-width: 767px) {
  .bottom-activity-panel__tab {
    padding: 0 8px;
    font-size: 12px;
  }
}
</style>
