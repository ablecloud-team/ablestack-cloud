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
  <section>
    <div class="project-tab-toolbar">
      <a-button v-if="apis.createProjectRole" type="primary" :disabled="locked" @click="openDialog('create')">
        <template #icon><plus-outlined /></template>
        {{ $t('label.create.project.role') }}
      </a-button>
      <a-button :loading="loading || listRefreshing > 0" :disabled="busy" @click="fetchData">
        <template #icon><reload-outlined /></template>
        {{ $t('label.refresh') }}
      </a-button>
    </div>
    <a-alert v-if="listRefreshFailed" type="warning" show-icon :message="$t('message.list.refresh.stale')" class="project-tab-notice" />
    <detail-tab-table
      class="project-tab-table"
      size="small"
      :loading="loading"
      :columns="columns"
      :data-source="roles"
      :scroll="{ x: 440 }"
      :pagination="{ pageSize: 10, hideOnSinglePage: true, size: 'small' }"
      :show-expand-column="!!apis.listProjectRolePermissions"
      :row-expandable="() => !!apis.listProjectRolePermissions"
      row-key="id">
      <template #expandedRowRender="{ record }">
        <ProjectRolePermissionTab :resource="resource" :role="record" />
      </template>
      <template #bodyCell="{ column, record }">
        <a-space v-if="column.key === 'actions'" wrap>
          <TooltipButton
            v-if="apis.updateProjectRole"
            :tooltip="$t('label.update.project.role')"
            icon="edit-outlined"
            size="small"
            :disabled="locked"
            @onClick="openDialog('edit', record)" />
          <TooltipButton
            v-if="apis.deleteProjectRole"
            :tooltip="$t('label.remove.project.role')"
            icon="delete-outlined"
            size="small"
            danger
            :disabled="locked"
            @onClick="openDialog('delete', record)" />
        </a-space>
      </template>
    </detail-tab-table>
    <MoldDialog
      :visible="!!operation"
      :title="$t(dialogLabel)"
      size="sm"
      form-actions
      focus-guard
      :after-close="restoreFocus"
      @cancel="closeDialog">
      <a-alert v-if="error" type="error" show-icon :message="error" class="project-tab-notice" />
      <p v-if="operation === 'delete'" class="project-role-delete-name">{{ selectedRole?.name }}</p>
      <a-form
        v-else-if="operation"
        id="project-role-form"
        ref="roleForm"
        :model="form"
        :rules="rules"
        layout="vertical"
        @keydown.ctrl.enter.prevent.stop="!$event.repeat && validateRole()"
        @finish="submit">
        <a-form-item name="name" :label="$t('label.name')" html-for="project-role-name">
          <a-input id="project-role-name" v-model:value="form.name" :disabled="busy" v-focus="true" />
        </a-form-item>
        <a-form-item name="description" :label="$t('label.description')" html-for="project-role-description">
          <a-input id="project-role-description" v-model:value="form.description" :disabled="busy" />
        </a-form-item>
      </a-form>
      <MoldDialogActions :busy="busy">
        <a-button :disabled="busy" @click="closeDialog">{{ $t('label.cancel') }}</a-button>
        <a-button v-if="operation === 'delete'" type="primary" danger :loading="busy" @click="submit">{{ $t(dialogLabel) }}</a-button>
        <a-button v-else type="primary" html-type="submit" form="project-role-form" :loading="busy">{{ $t(dialogLabel) }}</a-button>
      </MoldDialogActions>
    </MoldDialog>
  </section>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import TooltipButton from '@/components/widgets/TooltipButton'
import ProjectRolePermissionTab from '@/views/project/iam/ProjectRolePermissionTab'

const roleActions = {
  create: { api: 'createProjectRole', label: 'label.create.project.role' },
  edit: { api: 'updateProjectRole', label: 'label.edit.project.role' },
  delete: { api: 'deleteProjectRole', label: 'label.remove.project.role' }
}

export default {
  name: 'ProjectRoleTab',
  components: { MoldDialog, MoldDialogActions, ProjectRolePermissionTab, TooltipButton },
  mixins: [listRefreshMixin(['fetchData'], { active: vm => !vm.busy && !vm.operation })],
  props: { resource: { type: Object, required: true } },
  data () {
    return {
      roles: [],
      loading: false,
      busy: false,
      operation: '',
      selectedRole: null,
      returnFocus: null,
      error: '',
      form: { name: '', description: '' }
    }
  },
  computed: {
    apis () { return this.$store.getters.apis },
    locked () { return this.loading || this.busy || this.listRefreshFailed },
    dialogLabel () { return roleActions[this.operation]?.label || 'label.project.role' },
    rules () { return { name: [{ required: true, whitespace: true, message: this.$t('message.error.required.input') }] } },
    columns () {
      return [
        { key: 'name', dataIndex: 'name', title: this.$t('label.name'), width: 126 },
        { key: 'description', dataIndex: 'description', title: this.$t('label.description') },
        ...(this.apis.updateProjectRole || this.apis.deleteProjectRole ? [{ key: 'actions', title: this.$t('label.actions'), width: 100 }] : [])
      ]
    }
  },
  watch: {
    'resource.id' () {
      this.roles = []
      this.operation = ''
      this.returnFocus = null
      this.fetchData()
    }
  },
  mounted () { this.fetchData() },
  methods: {
    async fetchData () {
      if (!this.resource.id) return
      const request = this.listRequestToken('fetchData')
      this.loading = !request.loaded
      try {
        const reply = await getAPI('listProjectRoles', { projectid: this.resource.id, ignoreproject: true }, { preserveOnFailure: true, timeout: 15000 })
        if (this.isListRequestCurrent('fetchData', request)) this.roles = reply.listprojectrolesresponse?.projectrole || []
      } catch {
        if (this.isListRequestCurrent('fetchData', request)) {
          request.failed = true
          this.listRefreshFailed = true
        }
      } finally {
        if (this.isListRequestCurrent('fetchData', request)) this.loading = false
      }
    },
    openDialog (operation, role = null) {
      if (this.locked || !this.apis[roleActions[operation].api]) return
      this.returnFocus = document.activeElement
      this.selectedRole = role
      this.error = ''
      this.form = { name: role?.name || '', description: role?.description || '' }
      this.operation = operation
    },
    async validateRole () {
      if (this.busy || !this.$refs.roleForm) return
      try {
        await this.$refs.roleForm.validate()
      } catch (error) {
        if (error.errorFields?.length) this.$refs.roleForm.scrollToField(error.errorFields[0].name)
        return
      }
      await this.submit()
    },
    closeDialog () {
      if (!this.busy) this.operation = ''
    },
    restoreFocus () {
      const trigger = this.returnFocus
      this.returnFocus = null
      if (!this.operation && trigger?.isConnected) trigger.focus({ preventScroll: true })
    },
    async submit () {
      const operation = this.operation
      const api = roleActions[operation]?.api
      if (this.busy || !this.apis[api]) return
      const projectid = this.resource.id
      const params = { projectid, ignoreproject: true }
      if (this.selectedRole) params.id = this.selectedRole.id
      if (operation !== 'delete') Object.assign(params, { name: this.form.name.trim(), description: this.form.description })
      this.busy = true
      this.error = ''
      try {
        await postAPI(api, params, { preserveOnFailure: true, timeout: 15000 })
        if (projectid !== this.resource.id || operation !== this.operation) return
        this.operation = ''
        await this.fetchData()
      } catch (error) {
        if (projectid === this.resource.id && operation === this.operation) {
          this.error = Object.values(error.response?.data || {}).find(value => value?.errortext)?.errortext || this.$t('message.request.failed')
        }
      } finally {
        this.busy = false
      }
    }
  }
}
</script>

<style scoped>
.project-role-delete-name { overflow-wrap: anywhere; }
</style>
