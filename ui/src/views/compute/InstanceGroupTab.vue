// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <a-spin :wrapper-class-name="viewportScroll ? 'workspace-fill' : undefined" :spinning="loading">
    <br />
    <a-tabs
      :class="{ 'workspace-tabset': viewportScroll }"
      v-scroll-activity="viewportScroll && { selector: tabScrollSelector, label: $t('label.details'), scope: currentTab }"
      :tabPosition="resourceTabPosition"
      :activeKey="currentTab"
      :animated="false"
      @change="handleChangeTab">
      <a-tab-pane key="details" :tab="$t('label.details')"><DetailsTab :resource="detailsResource" :loading="loading" /></a-tab-pane>
      <a-tab-pane key="members" :tab="$t('label.instances')"><InstanceGroupMembers :resource="resource" :active="currentTab === 'members'" /></a-tab-pane>
      <a-tab-pane key="comments" :tab="$t('label.annotations')" v-if="'listAnnotations' in $store.getters.apis"><AnnotationsTab :resource="resource" resourceType="VMInstanceGroup" /></a-tab-pane>
    </a-tabs>
  </a-spin>
</template>
<script>
import scrollActivity, { tabScrollSelector } from '@/utils/scrollActivity'
import { mixinDevice } from '@/utils/mixin'
import DetailsTab from '@/components/view/DetailsTab'
import AnnotationsTab from '@/components/view/AnnotationsTab'
import InstanceGroupMembers from './InstanceGroupMembers'
import { vmGroupMemberSummary } from '@/utils/vmGroupMembers'

export default {
  name: 'InstanceGroupTab',
  directives: { scrollActivity },
  components: { DetailsTab, AnnotationsTab, InstanceGroupMembers },
  mixins: [mixinDevice],
  props: { viewportScroll: Boolean, resource: { type: Object, required: true }, loading: { type: Boolean, default: false } },
  data () { return { currentTab: 'details' } },
  computed: {
    tabScrollSelector () { return tabScrollSelector },
    detailsResource () {
      const summary = vmGroupMemberSummary(this.resource)
      return {
        ...this.resource,
        virtualmachinecount: summary?.count ?? '—',
        virtualmachinestates: summary ? summary.states.map(([state, count]) => this.$t('label.' + state.toLowerCase()) + ' ' + count).join(' · ') || '—' : '—'
      }
    }
  },
  watch: { '$route.fullPath' () { this.setCurrentTab() } },
  created () { this.setCurrentTab(); window.addEventListener('popstate', this.setCurrentTab) },
  beforeUnmount () { window.removeEventListener('popstate', this.setCurrentTab) },
  methods: {
    setCurrentTab () {
      let tab = this.$route.query.tab
      if (window.location.hash.split('?')[0] === '#' + this.$route.path) tab = new URLSearchParams(window.location.hash.split('?')[1] || '').get('tab') || tab
      const allowed = ['details', 'members', ...('listAnnotations' in this.$store.getters.apis ? ['comments'] : [])]
      this.currentTab = allowed.includes(tab) ? tab : 'details'
    },
    handleChangeTab (tab) {
      this.currentTab = tab
      const query = { ...this.$route.query, tab }
      this.$route.query.tab = tab
      history.pushState({}, null, '#' + this.$route.path + '?' + new URLSearchParams(query))
    }
  }
}
</script>
