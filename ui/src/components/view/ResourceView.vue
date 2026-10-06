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
  <resource-layout :wide="wideLayout">
    <template #left>
      <slot name="info-card">
        <info-card
          :resource="resource"
          :loading="loading"
          :actions="actions"
          @exec-action="$emit('exec-action', $event)" />
      </slot>
    </template>
    <template #right>
      <a-card
        class="spin-content resource-content-card"
        :loading="loading"
        :bordered="true"
        style="width:100%">
        <keep-alive v-if="tabs.length === 1">
          <component
            :is="tabs[0].component"
            :key="tabs[0].name"
            :resource="resource"
            :loading="loading"
            :tab="tabs[0].name"
            @wide-layout-change="onWideLayoutChange" />
        </keep-alive>
        <a-tabs
          v-else
          :style="{ width: '100%', marginTop: resourceTabPosition === 'top' ? '-12px' : '0' }"
          :tabPosition="resourceTabPosition"
          :animated="false"
          :activeKey="activeTab"
          @change="onTabChange" >
          <template v-for="tab in tabs" :key="tab.name">
            <a-tab-pane
              :key="tab.name"
              :tab="$t('label.' + tabName(tab))"
              v-if="showTab(tab)">
              <keep-alive>
                <component
                  :is="tab.component"
                  :key="tab.name"
                  :resource="resource"
                  :resourceType="tab.resourceType"
                  :loading="loading"
                  :tab="activeTab"
                  @change-resource="$emit('change-resource', $event)"
                  @wide-layout-change="onWideLayoutChange" />
              </keep-alive>
            </a-tab-pane>
          </template>
        </a-tabs>
      </a-card>
    </template>
  </resource-layout>
</template>

<script>
import DetailsTab from '@/components/view/DetailsTab'
import InfoCard from '@/components/view/InfoCard'
import ResourceLayout from '@/layouts/ResourceLayout'
import { getAPI } from '@/api'
import { mixinDevice } from '@/utils/mixin.js'

export default {
  name: 'ResourceView',
  components: {
    InfoCard,
    ResourceLayout
  },
  mixins: [mixinDevice],
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    },
    actions: {
      type: Array,
      default: function () {
        return []
      }
    },
    tabs: {
      type: Array,
      default: function () {
        return [{
          name: 'details',
          component: DetailsTab
        }]
      }
    },
    historyTab: {
      type: String,
      default: ''
    }
  },
  data () {
    return {
      activeTab: '',
      networkService: null,
      projectAccount: null,
      wideLayout: false
    }
  },
  watch: {
    resource: {
      deep: true,
      handler (newItem, oldItem) {
        if (newItem.id === oldItem.id) return

        this.wideLayout = false
        this.fetchData()
      }
    },
    '$route.fullPath': function () {
      this.setActiveTab()
    },
    tabs: {
      handler () {
        this.setActiveTab()
      }
    }
  },
  created () {
    this.setActiveTab()
    window.addEventListener('popstate', this.setActiveTab)
    this.fetchData()
  },
  beforeUnmount () {
    window.removeEventListener('popstate', this.setActiveTab)
  },
  methods: {
    fetchData () {
      if (this.resource.associatednetworkid) {
        getAPI('listNetworks', { id: this.resource.associatednetworkid, listall: true }).then(response => {
          if (response && response.listnetworksresponse && response.listnetworksresponse.network) {
            this.networkService = response.listnetworksresponse.network[0]
          } else {
            this.networkService = {}
          }
        })
      }
    },
    onTabChange (key) {
      this.activeTab = key
      this.wideLayout = false
      const query = Object.assign({}, this.$route.query)
      query.tab = key
      this.$route.query.tab = key
      history.pushState(
        {},
        null,
        '#' + this.$route.path + '?' + Object.keys(query).map(key => {
          return (
            encodeURIComponent(key) + '=' + encodeURIComponent(query[key])
          )
        }).join('&')
      )
      this.$emit('onTabChange', key)
    },
    onWideLayoutChange (enabled) {
      this.wideLayout = !!enabled
    },
    tabName (tab) {
      if (typeof tab.name === 'function') {
        return tab.name(this.resource)
      }
      return tab.name
    },
    showTab (tab) {
      if (this.networkService && this.networkService.service && tab.networkServiceFilter) {
        return tab.networkServiceFilter(this.networkService.service)
      } else if ('show' in tab) {
        return tab.show(this.resource, this.$route, this.$store.getters.userInfo)
      } else {
        return true
      }
    },
    setActiveTab () {
      const requestedTab = this.$route.query.tab || this.historyTab
      const selected = this.tabs.find(tab => tab.name === requestedTab) || this.tabs[0]
      this.activeTab = selected ? selected.name : ''
    }
  }
}
</script>

<style lang="less" scoped>
.resource-content-card {
  background: var(--ui-bg-surface);
}

:deep(.resource-content-card > .ant-card-body),
:deep(.resource-content-card .ant-spin-nested-loading),
:deep(.resource-content-card .ant-spin-container) {
  background: var(--ui-bg-surface);
}
</style>
