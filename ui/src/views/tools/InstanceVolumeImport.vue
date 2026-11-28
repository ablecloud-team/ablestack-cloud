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
  <div v-if="!legacyUrl" class="instance-volume-import">
    <a-card class="breadcrumb-card breadcrumb-toolbar">
      <div class="breadcrumb-toolbar-row">
        <div class="breadcrumb-toolbar-path">
          <breadcrumb>
            <template #end>
              <a-tooltip :title="$t('label.refresh')">
                <a-button shape="circle" :aria-label="$t('label.refresh')" @click="refresh">
                  <template #icon><ReloadOutlined /></template>
                </a-button>
              </a-tooltip>
            </template>
          </breadcrumb>
        </div>
      </div>
    </a-card>

    <a-card v-if="activeTab" class="import-workspace">
      <a-tabs
        :activeKey="activeTab"
        class="import-workspace-tabs"
        @change="selectTab">
        <a-tab-pane v-for="tab in tabs" :key="tab.key" :tab="$t(tab.label)" />
      </a-tabs>
      <ManageVolumes v-if="activeTab === 'volumes'" ref="workspace" embedded />
      <ManageInstances v-else :key="activeTab" ref="workspace" :mode="activeTab" />
    </a-card>
    <a-result v-else status="403" :title="$t('message.you.are.not.authorized')" />
  </div>
</template>

<script>
import Breadcrumb from '@/components/widgets/Breadcrumb'
import ManageInstances from './ManageInstances.vue'
import ManageVolumes from './ManageVolumes.vue'

const workspaces = [
  { key: 'instances', label: 'label.import.instance', any: ['importVm', 'importUnmanagedInstance', 'importUnmanagedInstanceForAblestackV2K', 'importUnmanagedInstanceForAblestackN2K'] },
  { key: 'volumes', label: 'label.action.import.unmanage.volumes', any: ['listVolumesForImport'] },
  { key: 'release', label: 'label.instances.unmanage.tab', any: ['unmanageVirtualMachine'] },
  { key: 'tasks', label: 'label.import.vm.tasks', any: ['listImportVmTasks'] }
]

export default {
  name: 'InstanceVolumeImport',
  components: { Breadcrumb, ManageInstances, ManageVolumes },
  computed: {
    legacyUrl () {
      return this.$route.path === '/managevolumes' || this.$route.path.startsWith('/managevolumes/')
    },
    tabs () {
      const apis = this.$store.getters.apis
      return workspaces.filter(workspace => workspace.any.some(api => api in apis))
    },
    activeTab () {
      const tab = this.$route.query.tab
      return this.tabs.find(workspace => workspace.key === tab)?.key || this.tabs[0]?.key
    }
  },
  created () {
    if (this.legacyUrl) {
      this.$router.replace({ path: '/manageinstances', query: { ...this.$route.query, tab: 'volumes' } })
    }
  },
  methods: {
    selectTab (tab) {
      if (tab !== this.activeTab && this.tabs.some(workspace => workspace.key === tab)) {
        this.$router.push({ query: { tab } })
      }
    },
    refresh () {
      if (this.$refs.workspace) this.$refs.workspace.fetchData()
    }
  }
}
</script>

<style scoped>
.import-workspace {
  margin-top: 12px;
}

.import-workspace > :deep(.ant-card-body) {
  padding: 0 12px 12px;
}

.import-workspace-tabs > :deep(.ant-tabs-content-holder) {
  display: none;
}
</style>
