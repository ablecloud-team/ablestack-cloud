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
  <span class="header-notice-opener">
    <label v-if="!isDisabled" :for="projectSelectorId" class="project-accessible-label">{{ $t('label.project') }}</label>
    <infinite-scroll-select
      v-if="!isDisabled"
      v-model:value="selectedProjectId"
      :disabled="loading"
      :aria-busy="loading"
      :id="projectSelectorId"
      class="project-select"
      :dropdownMatchSelectWidth="false"
      :dropdownStyle="{ width: 'min(420px, calc(100vw - 24px))' }"
      api="listProjects"
      :apiParams="projectsApiParams"
      resourceType="project"
      :defaultOption="defaultOption"
      defaultIcon="project-outlined"
      :pageSize="100"
      @change-option="changeProject" />
  </span>
</template>

<script>
import InfiniteScrollSelect from '@/components/widgets/InfiniteScrollSelect'
import eventBus from '@/config/eventBus'
import { sourceToken } from '@/utils/request'

let projectMenuSequence = 0
export default {
  name: 'ProjectMenu',
  components: {
    InfiniteScrollSelect
  },
  data () {
    return {
      projectSelectorId: 'project-selector-' + (++projectMenuSequence),
      selectedProjectId: null,
      loading: false,
      timestamp: new Date().getTime()
    }
  },
  created () {
    this.selectedProjectId = this.$store.getters?.project?.id || this.defaultOption.id
    this.$store.dispatch('ToggleTheme', this.selectedProjectId ? 'dark' : 'light')
  },
  computed: {
    isDisabled () {
      return !('listProjects' in this.$store.getters.apis)
    },
    defaultOption () {
      return { id: 0, name: this.$t('label.default.view') }
    },
    projectsApiParams () {
      return {
        details: 'min',
        listall: true,
        timestamp: this.timestamp
      }
    }
  },
  mounted () {
    this.unwatchProject = this.$store.watch(
      (state, getters) => getters.project?.id,
      (newId) => {
        this.selectedProjectId = newId || this.defaultOption.id
      }
    )
    eventBus.on('projects-updated', this.onProjectsUpdated)
  },
  beforeUnmount () {
    eventBus.off('projects-updated', this.onProjectsUpdated)
    if (this.unwatchProject) {
      this.unwatchProject()
    }
  },
  methods: {
    onProjectsUpdated () { this.timestamp = new Date().getTime() },
    async changeProject (project) {
      if (this.loading || !project) return
      const previous = this.$store.getters.project || this.defaultOption
      const previousApis = this.$store.getters.apis
      const token = this.$store.getters.token
      const target = project.id && project.id !== '0' ? project : {}
      let scopeApplied = false
      this.loading = true
      eventBus.emit('project-switch-start')
      try {
        // Finish capability lookup before AutogenView's route guard cancels
        // outstanding reads. Keep project selection, API metadata and routing
        // in one awaited sequence instead of an unhandled fire-and-forget job.
        await this.$store.dispatch('ProjectView', target.id)
        await this.$store.dispatch('SetProject', target)
        scopeApplied = true
        await this.$store.dispatch('ToggleTheme', target.id ? 'dark' : 'light')
        if (this.$route.name !== 'dashboard') await this.$router.push({ name: 'dashboard' })
        this.$message.success(`${this.$t('message.switch.to')} "${project.displaytext || project.name}"`)
      } catch (error) {
        // Lookup/storage failures keep the previous scope. A later navigation
        // failure must not display the old project with the new permissions.
        if (!scopeApplied && this.$store.getters.token === token && this.$store.getters.apis !== previousApis) {
          this.$store.commit('SET_APIS', previousApis)
          await this.$store.dispatch('GenerateRoutes', { apis: previousApis })
        }
        const active = this.$store.getters.project || previous
        this.selectedProjectId = active.id || this.defaultOption.id
        if (!sourceToken.isCancel(error)) this.$notifyError(error)
      } finally {
        this.loading = false
        eventBus.emit('project-switch-end')
      }
    }
  }
}
</script>

<style lang="less" scoped>
.project {
  &-select {
    width: 27vw;
  }

  &-icon {
    font-size: 20px;
    line-height: 1;
    padding-top: 5px;
    padding-right: 5px;
  }
}

.custom-suffix-icon {
  font-size: 20px;
  position: absolute;
  top: 0;
  right: 1px;
  margin-top: -5px;
}
</style>

<style scoped>
.project-accessible-label { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip:rect(0,0,0,0); white-space:nowrap; border:0; }
</style>
