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
  <a-drawer
    :visible="isVisible"
    :mask="false"
    :maskClosable="false"
    placement="bottom"
    :height="300"
    @close="closeSidebar"
    :closable="false"
  >
    <div class="sidebar-header">
      <span class="sidebar-title">{{ $t('label.recent.events') }}</span>
      <a-button
        v-if="isVisible"
        type="primary"
        class="close-btn"
        @click="closeSidebar"
        style="width: 40px; height: 40px; padding: 0;"
      >
        <close-outlined />
      </a-button>
    </div>

    <a-col :xs="{ span: 24 }" :lg="{ span: 24 }">
      <chart-card :loading="loading" class="dashboard-card dashboard-event">
        <a-divider style="margin: 0px 0px; border-width: 0px" />
        <a-table
          :loading="loading"
          :columns="columns"
          :dataSource="events"
          rowKey="id"
          :bordered="false"
          :pagination="false"
          size="small"
          class="event-table no-border-table"
        >
        </a-table>
      </chart-card>
    </a-col>
  </a-drawer>
</template>

<script>
import { api } from '@/api'

export default {
  name: 'EventSidebar',
  props: {
    isVisible: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      events: [],
      loading: false,
      refreshInterval: null,
      columns: [
        {
          title: this.$t('label.level'),
          dataIndex: 'level',
          key: 'level',
          width: 40,
          ellipsis: true
        },
        {
          title: this.$t('label.type'),
          dataIndex: 'type',
          key: 'type',
          width: 120
        },
        {
          title: this.$t('label.state'),
          dataIndex: 'state',
          key: 'state',
          width: 50,
          ellipsis: true
        },
        {
          title: this.$t('label.description'),
          dataIndex: 'description',
          key: 'description',
          width: 300
        },
        {
          title: this.$t('label.resource'),
          dataIndex: 'resourcename',
          key: 'resourcename',
          width: 80
        },
        {
          title: this.$t('label.username'),
          dataIndex: 'username',
          key: 'username',
          width: 40
        },
        {
          title: this.$t('label.account'),
          dataIndex: 'account',
          key: 'account',
          width: 40
        },
        {
          title: this.$t('label.domain'),
          dataIndex: 'domain',
          key: 'domain',
          width: 40,
          ellipsis: true
        },
        {
          title: this.$t('label.created'),
          dataIndex: 'created',
          key: 'created',
          width: 100,
          ellipsis: true,
          customRender: ({ text }) => {
            return this.formatDate(text)
          }
        }
      ],
      columnKeys: ['level', 'type', 'state', 'description', 'resourcename', 'username', 'account', 'domain', 'created']
    }
  },
  created () {
    this.fetchEvents()
    this.refreshInterval = setInterval(this.fetchEvents, 5000)
    // console.log('EventsListBar:', this.$store.getters.globalSettings.EventsListBar)
  },
  unmounted () {
    clearInterval(this.refreshInterval)
  },
  methods: {
    closeSidebar () {
      clearInterval(this.refreshInterval)
      this.$emit('update:isVisible', false)
    },
    async fetchEvents () {
      if (!('listEvents' in this.$store.getters.apis)) {
        return
      }
      this.loading = true
      const params = {
        page: 1,
        pagesize: 20,
        listall: true
      }
      try {
        const settingsResponse = await api('listConfigurations', { name: 'event.recent.minutes' })
        const eventListBarSetting = settingsResponse?.listconfigurationsresponse?.configuration[0]?.value || 5
        this.eventListBarMinutes = parseInt(eventListBarSetting, 10) * 60 * 1000
        const response = await api('listEvents', params)
        if (response && response.listeventsresponse) {
          const events = response.listeventsresponse.event || []
          const recentEvents = this.filterRecentEvents(events, this.eventListBarMinutes)
          this.events = recentEvents
        } else {
          console.error('No events found in the response')
        }
      } catch (error) {
        console.error('Error getting event list:', error)
      } finally {
        this.loading = false
      }
    },
    filterRecentEvents (events, timeRange = 5 * 60 * 1000) {
      const timeThreshold = Date.now() - timeRange
      return events.filter(event => {
        const eventTime = new Date(event.created).getTime()
        return eventTime >= timeThreshold
      }).slice(0, 20)
    },
    formatDate (dateString) {
      const date = new Date(dateString)
      return new Intl.DateTimeFormat('en-GB', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false
      }).format(date)
    },
    getEventColour (event) {
      return 'blue'
    }
  }
}
</script>

<style scoped>
.sidebar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
}
.sidebar-title {
  font-size: 16px;
  font-weight: bold;
  margin-right: auto;
}

.close-btn {
  position: fixed;
  top: -40px;
  right: 0px;
  z-index: 1000;
  background: #aaa;
  border: none;
  color: #666;
}

.no-border-table .ant-table-cell,
.no-border-table .ant-table-thead > tr > th {
  border: none;
}

a-table .ant-table-content {
  overflow: auto;
}

a-table .ant-table-cell {
  font-size: 10px;
  line-height: 1.2;
}

a-table .ant-table-content .ant-table-row {
  max-height: 200px;
  overflow: auto;
}
</style>
