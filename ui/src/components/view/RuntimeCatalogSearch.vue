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
  <span class="runtime-catalog-search">
    <a-input-search
      v-model:value="keyword"
      class="input-search"
      :aria-label="$t('label.runtime.catalog.keyword')"
      :placeholder="$t('label.runtime.catalog.keyword')"
      allow-clear
      @search="searchKeyword">
      <template #addonBefore>
        <a-popover placement="bottomRight" trigger="click" :visible="visible" @visibleChange="$event ? open() : close()">
          <template #content>
            <a-form
              v-if="visible"
              class="storage-standard-search-form runtime-catalog-search-form"
              :model="form"
              layout="vertical"
              @finish="apply"
              @keydown.esc.stop.prevent="close(true)">
              <a-form-item :label="$t('label.runtime.catalog.keyword')" html-for="runtime-filter-keyword">
                <a-input id="runtime-filter-keyword" ref="formKeyword" v-model:value="form.keyword" />
              </a-form-item>
              <a-form-item :label="$t('label.runtime.catalog.version.exact')" html-for="runtime-filter-version">
                <a-input id="runtime-filter-version" v-model:value="form.version" />
              </a-form-item>
              <a-form-item v-for="(values, field) in choices" :key="field" :label="$t('label.' + field)" :html-for="'runtime-filter-' + field">
                <a-select
                  :id="'runtime-filter-' + field"
                  v-model:value="form[field]"
                  :aria-label="$t('label.' + field)"
                  :placeholder="$t('label.all')"
                  :options="values.map(value => ({ value, label: $t('label.runtime.catalog.' + field + '.' + value) }))"
                  allow-clear />
              </a-form-item>
              <div class="filter-group-button">
                <a-button class="filter-group-button-clear" size="small" @click="reset">{{ $t('label.reset') }}</a-button>
                <a-button class="filter-group-button-search" size="small" type="primary" html-type="submit">{{ $t('label.search') }}</a-button>
              </div>
            </a-form>
          </template>
          <a-button ref="trigger" class="filter-button" size="small" :aria-label="$t('label.advanced.search')" :aria-expanded="visible">
            <filter-two-tone v-if="filtered" />
            <filter-outlined v-else />
          </a-button>
        </a-popover>
      </template>
    </a-input-search>
  </span>
</template>

<script>
import { runtimeCatalogChoices, runtimeCatalogFilters } from '@/utils/runtimeCatalog'

export default {
  name: 'RuntimeCatalogSearch',
  emits: ['search'],
  data () { return { keyword: '', visible: false, form: {} } },
  computed: {
    applied () { return runtimeCatalogFilters(this.$route.query) },
    choices () {
      return this.applied.history === 'deleted' ? { validationstatus: runtimeCatalogChoices.validationstatus } : runtimeCatalogChoices
    },
    filtered () { return Object.keys(this.applied).some(field => !['keyword', 'history'].includes(field)) }
  },
  watch: {
    applied: {
      immediate: true,
      handler (filters) { this.keyword = filters.keyword || '' }
    }
  },
  methods: {
    open () {
      this.form = { ...this.applied }
      this.visible = true
      this.$nextTick(() => this.$refs.formKeyword?.focus())
    },
    close (focus = false) {
      this.visible = false
      if (focus) this.$nextTick(() => this.$refs.trigger?.$el?.focus())
    },
    apply () {
      this.close(true)
      this.$emit('search', runtimeCatalogFilters(this.form))
    },
    reset () {
      this.keyword = ''
      this.close(true)
      this.$emit('search', this.applied.history ? { history: this.applied.history } : {})
    },
    searchKeyword (keyword) {
      this.$emit('search', runtimeCatalogFilters({ ...this.applied, keyword }))
    }
  }
}
</script>
<style scoped lang="less">
@import '~@/style/components/view/search-view.less';
.runtime-catalog-search { display: table-cell; width: 100%; }
</style>
