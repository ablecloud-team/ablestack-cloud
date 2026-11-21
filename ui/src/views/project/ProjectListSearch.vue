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
  <a-input-search
    v-model:value="keyword"
    allow-clear
    class="project-keyword-search"
    :aria-label="$t('label.project.search.keyword')"
    :placeholder="$t('label.project.search.keyword')"
    @search="searchKeyword">
    <template #addonBefore>
      <a-popover v-model:visible="open" placement="bottomRight" trigger="click">
        <template v-if="open" #content>
          <a-form :model="draft" class="project-search-form" layout="vertical" @finish="submit" v-ctrl-enter="submit">
            <a-form-item :label="$t('label.project.search.name.exact')" html-for="project-filter-name">
              <a-input id="project-filter-name" v-model:value="draft.name" allow-clear />
            </a-form-item>
            <a-form-item :label="$t('label.project.search.description.exact')" html-for="project-filter-description">
              <a-input id="project-filter-description" v-model:value="draft.displaytext" allow-clear />
            </a-form-item>
            <a-form-item :label="$t('label.state')" html-for="project-filter-state">
              <a-select id="project-filter-state" v-model:value="draft.state" :options="stateOptions" allow-clear />
            </a-form-item>
            <a-form-item :label="$t('label.project.search.uuid')" html-for="project-filter-id">
              <a-input id="project-filter-id" v-model:value="draft.id" allow-clear />
            </a-form-item>
            <a-form-item v-if="canFilterScope" :label="$t('label.domain')" html-for="project-filter-domain">
              <InfiniteScrollSelect
                id="project-filter-domain"
                :value="draft.domainid"
                api="listDomains"
                :api-params="domainParams"
                resource-type="domain"
                option-label-key="path"
                :show-icon="false"
                allow-clear
                @change-option-value="changeDomain" />
            </a-form-item>
            <a-form-item v-if="canFilterScope && apis.listAccounts" :label="$t('label.account')" html-for="project-filter-account">
              <InfiniteScrollSelect
                v-if="draft.domainid"
                id="project-filter-account"
                :key="draft.domainid"
                :value="draft.account"
                api="listAccounts"
                :api-params="accountParams"
                resource-type="account"
                option-value-key="name"
                :show-icon="false"
                allow-clear
                @change-option-value="changeAccount" />
              <a-select v-else id="project-filter-account" disabled :placeholder="$t('message.project.search.select.domain')" />
            </a-form-item>
            <a-form-item v-if="canFilterScope && apis.listUsers" :label="$t('label.username')" html-for="project-filter-user">
              <InfiniteScrollSelect
                v-if="draft.domainid"
                id="project-filter-user"
                :key="draft.domainid + ':' + (draft.account || '')"
                :value="draft.username"
                api="listUsers"
                :api-params="userParams"
                resource-type="user"
                option-value-key="username"
                option-label-key="username"
                :show-icon="false"
                allow-clear
                @change-option-value="draft.username = $event" />
              <a-select v-else id="project-filter-user" disabled :placeholder="$t('message.project.search.select.domain')" />
            </a-form-item>
            <a-form-item :label="$t('label.project.search.tag.key')" html-for="project-filter-tag-key">
              <a-input id="project-filter-tag-key" v-model:value="draft['tags[0].key']" allow-clear />
            </a-form-item>
            <a-form-item :label="$t('label.project.search.tag.value')" html-for="project-filter-tag-value">
              <a-input id="project-filter-tag-value" v-model:value="draft['tags[0].value']" allow-clear />
            </a-form-item>
            <a-alert v-if="error" type="error" show-icon :message="$t(error)" class="project-search-wide" />
            <a-space class="project-search-wide project-search-actions">
              <a-button @click="reset">{{ $t('label.reset') }}</a-button>
              <a-button type="primary" @click="submit">{{ $t('label.search') }}</a-button>
            </a-space>
          </a-form>
        </template>
        <a-button type="text" :aria-label="$t('label.advanced.search')" :aria-expanded="open" size="small">
          <filter-outlined />
        </a-button>
      </a-popover>
    </template>
  </a-input-search>
</template>

<script>
import InfiniteScrollSelect from '@/components/widgets/InfiniteScrollSelect'
import { projectScopeFiltersAvailable, projectSearchError, projectSearchQuery, projectStates } from '@/utils/projectSearch'

export default {
  name: 'ProjectListSearch',
  components: { InfiniteScrollSelect },
  emits: ['search'],
  data () {
    return { open: false, draft: {}, keyword: '', error: '', domainParams: { listall: true, ignoreproject: true } }
  },
  computed: {
    getters () { return this.$store.getters },
    apis () { return this.getters.apis },
    canFilterScope () { return projectScopeFiltersAvailable(this.getters) },
    accountParams () { return { ...this.domainParams, domainid: this.draft.domainid, isrecursive: false } },
    userParams () {
      return { ...this.accountParams, ...(this.draft.account ? { account: this.draft.account } : {}) }
    },
    stateOptions () { return projectStates.map(state => ({ label: this.$t('state.' + state.toLowerCase()), value: state })) }
  },
  watch: {
    '$route.query': { immediate: true, handler () { this.restore() } },
    open (value) { if (value) this.restore() }
  },
  methods: {
    restore () {
      const query = projectSearchQuery(this.$route.query, this.getters)
      this.keyword = query.q || ''
      for (const key of ['q', 'page', 'pagesize', 'sortkey', 'sortorder']) delete query[key]
      this.draft = query
      this.error = ''
    },
    changeDomain (domain) {
      this.draft.domainid = domain
      this.changeAccount(undefined)
    },
    changeAccount (account) {
      this.draft.account = account
      this.draft.username = undefined
    },
    searchKeyword (keyword) { this.$emit('search', { ...projectSearchQuery(this.$route.query, this.getters), q: keyword }) },
    submit () {
      this.error = projectSearchError(this.draft, this.$isValidUuid)
      if (this.error) return
      this.open = false
      this.$emit('search', { ...this.draft, q: this.keyword })
    },
    reset () {
      this.open = false
      this.$emit('search', {})
    }
  }
}
</script>

<style scoped>
.project-keyword-search { display: inline-block; width: 228px; max-width: 100%; vertical-align: middle; }
.project-search-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; width: min(440px, 76vw); }
.project-search-form :deep(.ant-form-item) { margin-bottom: 12px; }
.project-search-wide { grid-column: 1 / -1; }
.project-search-actions { justify-content: flex-end; margin-top: 8px; }
</style>
