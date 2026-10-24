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
  <span class="header-notice">
    <a-popover
      v-model:visible="visible"
      trigger="click"
      placement="bottom"
      :autoAdjustOverflow="true"
      :arrowPointAtCenter="true"
      overlayClassName="header-notice-popover">
      <template #content>
        <a-spin :spinning="loading">
          <a-list style="min-width: 200px; max-width: 300px; max-height: 70vh; overflow-y: auto">
            <a-list-item>
              <a-list-item-meta :title="$t('label.notifications')">
                <template #avatar>
                  <a-avatar :style="{ backgroundColor: '#6887d0', verticalAlign: 'middle' }">
                    <template #icon><notification-outlined /></template>
                  </a-avatar>
                </template>
                <template #description><a-button size="small" @click="clearJobs">{{ $t('label.clear.list') }}</a-button></template>
              </a-list-item-meta>
            </a-list-item>
            <a-list-item v-for="(notice, index) in notices" :key="notice.operationId || notice.key || index">
              <template #title>{{ notice.path }} </template>
              <a-list-item-meta :title="notice.title">
                <template #avatar>
                  <a-avatar :style="(notificationAvatar[notice.status] || notificationAvatar.unknown).style">
                    <template #icon>
                      <render-icon :icon="(notificationAvatar[notice.status] || notificationAvatar.unknown).icon" />
                    </template>
                  </a-avatar>
                </template>
                <template #description>
                  <template v-if="notice.operationId">
                    <a-button type="link" class="snapshot-notice-target" @click="openOperation(notice.operationId)">{{ notice.targetName }}</a-button>
                    <div>{{ $t('label.snapshot.action.status.' + notice.operationStatus) }}</div>
                    <time :datetime="notice.created">{{ formatTime(notice.created) }}</time>
                  </template>
                  <span v-else-if="getResourceName(notice.description, 'name') && notice.path && !['VPC_RESTART_REQUIRED', 'NETWORK_RESTART_REQUIRED'].includes(notice.key)">
                    <router-link :to="{ path: notice.path}">{{ getResourceName(notice.description, "name") + ' - ' }}</router-link>
                    {{ getResourceName(notice.description, "msg") }}</span>
                  <span v-else-if="notice.path && ['VPC_RESTART_REQUIRED', 'NETWORK_RESTART_REQUIRED'].includes(notice.key)">
                    <router-link :to="{ path: notice.path, query: notice.query }">{{ notice.description }}</router-link>
                  </span>
                  <span v-else>{{ notice.description }}</span>
                  <a-button v-if="!notice.operationId && notice.status === 'unknown'" type="link" size="small" @click="$pollJob({ jobId: notice.key, title: notice.title, description: notice.description, originalPage: notice.path, retry: true })">
                    {{ $t('label.job.check.result') }}
                  </a-button>
                </template>
              </a-list-item-meta>
            </a-list-item>
            <a-list-item v-if="canOpenActivityPanel" class="header-notice-activity-link">
              <a-button type="link" block @click="openActivityPanel">
                <template #icon><calendar-outlined /></template>
                {{ $t('label.recent.events') }} / {{ $t('label.alerts') }}
              </a-button>
            </a-list-item>
          </a-list>
        </a-spin>
      </template>
      <a-button ref="noticeOpener" type="text" :aria-label="$t('label.notifications')" class="header-notice-opener">
        <a-badge :count="notices.length">
          <bell-outlined class="header-notice-icon" />
        </a-badge>
      </a-button>
    </a-popover>
    <StorageSnapshotJobDialog v-if="selectedOperation" :entry-id="selectedOperation" @close="closeOperation" />
  </span>
</template>

<script>
import StorageSnapshotJobDialog from '@/components/view/StorageSnapshotJobDialog.vue'
import { dismissSnapshotNotifications, snapshotOperationActive } from '@/utils/storageSnapshotOperations'
import { scopedSnapshotOperations, snapshotOperationLabels } from '@/utils/storageSnapshotNotices'
import { snapshotSecurityScope } from '@/utils/storageSnapshotContext'

export default {
  name: 'HeaderNotice',
  components: { StorageSnapshotJobDialog },
  data () {
    return {
      loading: false,
      visible: false,
      selectedOperation: '',
      notificationAvatar: {
        unknown: { icon: 'question-circle-outlined', style: { backgroundColor: '#8c8c8c' } },
        done: { icon: 'check-circle-outlined', style: { backgroundColor: '#87d068' } },
        progress: { icon: 'loading-outlined', style: { backgroundColor: '#ffbf00' } },
        failed: { icon: 'close-circle-outlined', style: { backgroundColor: '#f56a00' } }
      }
    }
  },
  computed: {
    scope () { return snapshotSecurityScope(this.$store) },
    operations () { return scopedSnapshotOperations(this.scope) },
    notices () {
      const jobs = this.operations.filter(e => !e.dismissed || snapshotOperationActive(e.status)).map(e => ({
        operationId: e.id,
        targetName: e.target.name || e.target.id,
        operationStatus: e.status,
        created: e.created,
        title: this.$t(snapshotOperationLabels[e.api] || 'label.snapshot.action.operations'),
        status: ['checking', 'submitting', 'accepted'].includes(e.status) ? 'progress' : e.status === 'complete' ? 'done' : ['failed', 'rejected'].includes(e.status) ? 'failed' : 'unknown'
      }))
      const ownedIds = new Set(this.operations.flatMap(e => [e.jobid, e.requestid]).filter(Boolean))
      return [...jobs, ...(this.$store.getters.headerNotices || []).filter(e => !ownedIds.has(e.key) && !ownedIds.has(e.jobid)).slice().reverse()]
    },
    canOpenActivityPanel () {
      return 'listEvents' in this.$store.getters.apis || 'listAlerts' in this.$store.getters.apis
    }
  },
  watch: {
    scope () { this.selectedOperation = ''; this.visible = false }
  },
  methods: {
    formatTime (value) { return new Date(value).toLocaleString(this.$i18n.locale.replace('_', '-')) },
    openOperation (id) { this.visible = false; this.selectedOperation = id },
    closeOperation () { this.selectedOperation = ''; this.$nextTick(() => this.$refs.noticeOpener?.$el?.focus()) },
    clearJobs () {
      dismissSnapshotNotifications(this.scope)
      this.$store.commit('SET_HEADER_NOTICES', (this.$store.getters.headerNotices || []).filter(x => ['progress', 'unknown'].includes(x.status)))
    },
    openActivityPanel () {
      this.visible = false
      this.$emit('open-activity-panel')
    },
    getResourceName (description, data) {
      if (description) {
        if (data === 'name') {
          const name = description.match(/\(([^)]+)\)/)
          return name ? name[1] : null
        }
        const msg = description.substring(description.indexOf(')') + 1)
        return msg
      }
    }
  }
}
</script>

<style lang="less" scoped>
  .header-notice {
    display: inline-block;
    transition: all 0.3s;

    &-popover {
      top: 50px !important;
      width: 300px;
      top: 50px;
    }

    &-opener {
      padding: 0;
      height: auto;
      display: inline-block;
      transition: all 0.3s;
      vertical-align: initial;
    }

    &-icon {
      font-size: 18px;
      padding: 4px;
    }
  }

  .snapshot-notice-target { padding: 0; height: auto; white-space: normal; text-align: left; overflow-wrap: anywhere; }
  time { font-size: 12px; opacity: .75; }
  .header-notice-activity-link {
    padding: 4px 8px 0;

    .ant-btn {
      display: flex;
      align-items: center;
      justify-content: flex-start;
      gap: 8px;
      padding: 0 8px;
      text-align: left;
    }
  }
</style>
