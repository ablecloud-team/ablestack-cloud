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
  <div class="backup-layout backup-schedule-wizard">
    <a-tabs :key="`tabs-${resource?.id}-${innerRenderKey}`" defaultActiveKey="1" :animated="false">
      <a-tab-pane :tab="$t('label.schedule')" key="1">
        <FormSchedule
          :key="`form-${resource?.id}-${innerRenderKey}`"
          :loading="loading"
          :resource="resource"
          :dataSource="dataSource"
          @close-action="closeAction"
          @refresh="handleRefresh"/>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.scheduled.backups')" key="2">
        <BackupSchedule
          :key="`backup-${resource?.id}-${innerRenderKey}`"
          :loading="loading"
          :resource="resource"
          :dataSource="dataSource"
          @refresh="handleRefresh"
          @close-action="closeAction" />
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import FormSchedule from '@views/compute/backup/FormSchedule'
import BackupSchedule from '@views/compute/backup/BackupSchedule'

export default {
  name: 'BackupScheduleWizard',
  components: {
    FormSchedule,
    BackupSchedule
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      loading: false,
      dataSource: [],
      innerRenderKey: 0
    }
  },
  provide () {
    return {
      refreshSchedule: () => {
        this.fetchData()
      },
      closeSchedule: this.closeAction
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    'resource.id': {
      immediate: false,
      handler () {
        this.dataSource = []
        this.innerRenderKey++
        this.fetchData()
      }
    }
  },
  methods: {
    fetchData () {
      const params = {}
      this.dataSource = []
      this.loading = true
      params.virtualmachineid = this.resource.id || this.resource.virtualmachineid

      if (!params.virtualmachineid) {
        console.error('No VM ID found in resource:', this.resource)
        this.loading = false
        return
      }

      getAPI('listBackupSchedule', params).then(json => {
        this.dataSource = json.listbackupscheduleresponse.backupschedule || []
      }).finally(() => {
        this.loading = false
      })
    },
    handleRefresh () {
      this.fetchData()
      this.$emit('refresh')
    },
    closeAction () {
      this.$emit('refresh')
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped lang="less">
  .backup-layout {
    width: 80vw;
    @media (min-width: 800px) {
      width: 600px;
    }
  }
</style>

<style lang="less">
// Both the VM action menu and the backup tab host this component in a modal.
.ant-modal-wrap:has(.backup-schedule-wizard) {
  display: flex;
  align-items: center;
  justify-content: center;
  .ant-modal {
    top: 0 !important;
    width: 680px !important;
    margin: 0;
    padding-bottom: 0;
    max-width: calc(100vw - 32px);
  }
  .ant-modal-body {
    height: ~"min(650px, calc(100dvh - 130px))";
    overflow: hidden;
  }
  .backup-dialog,
  .backup-schedule-wizard,
  .backup-schedule-wizard > .ant-tabs {
    width: 100%;
    height: 100%;
    min-height: 0;
  }
  .backup-schedule-wizard {
    .ant-tabs-content-holder { min-height: 0; overflow: hidden; }
    .ant-tabs-content, .ant-tabs-tabpane-active,
    .ant-spin-nested-loading, .ant-spin-container {
      height: 100%;
      min-height: 0;
    }
    .list-schedule { max-height: 100%; overflow: auto; }
    .form-layout, .form, form {
      display: flex;
      flex-direction: column;
      flex: 1;
      min-height: 0;
      width: 100%;
    }
    .form-layout { height: 100%; }
    .form-layout > label, .ant-alert { flex-shrink: 0; }
    form > .ant-row { min-height: 0; overflow-y: auto; flex: 1; }
    .action-button {
      flex-shrink: 0;
      display: flex;
      justify-content: flex-end;
      gap: 8px;
      padding-top: 16px;
      margin: 0;
    }
  }
}
</style>
