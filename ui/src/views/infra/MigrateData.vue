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
  <div class="form-layout" v-ctrl-enter="submit">
    <a-alert v-if="readFailed" type="error" show-icon :message="$t('message.secondary.storage.list.failed')">
      <template #description>
        <a-button size="small" @click="fetchStores">{{ $t('label.retry') }}</a-button>
      </template>
    </a-alert>
    <a-alert
      v-else-if="!loading && !sources.length"
      type="info"
      show-icon
      :message="$t('message.secondary.storage.migration.no.sources')" />
    <a-alert
      v-else-if="!loading && !destinations.length"
      type="info"
      show-icon
      :message="$t('message.secondary.storage.migration.no.targets')"
      :description="$t('message.secondary.storage.migration.destination.rules')" />
    <a-alert v-if="migrationError" type="error" show-icon :message="migrationError" />
    <a-form layout="vertical" :model="form" @finish="submit">
      <a-form-item name="srcpool" html-for="secondary-migration-source" :label="$t('label.secondary.storage.migration.source')">
        <a-select
          id="secondary-migration-source"
          v-model:value="form.srcpool"
          :options="sources.map(storeOption)"
          :disabled="locked || readFailed || !sources.length"
          :loading="loading"
          option-filter-prop="label"
          show-search
          @change="changeSource" />
      </a-form-item>
      <a-form-item name="destpools" html-for="secondary-migration-destinations" :label="$t('label.secondary.storage.migration.destinations')">
        <a-select
          id="secondary-migration-destinations"
          v-model:value="form.destpools"
          :options="destinations.map(storeOption)"
          :disabled="locked || readFailed || !destinations.length"
          mode="multiple"
          option-filter-prop="label"
          show-search />
      </a-form-item>
      <a-form-item name="migrationtype" :label="$t('migrationPolicy')">
        <a-select v-model:value="form.migrationtype" :disabled="locked">
          <a-select-option value="Complete">{{ $t('label.complete') }}</a-select-option>
          <a-select-option value="Balance">{{ $t('label.balance') }}</a-select-option>
        </a-select>
      </a-form-item>
      <mold-dialog-actions :busy="migrating">
        <a-button :disabled="migrating" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" @click="submit" :loading="migrating" :disabled="!canSubmit">
          {{ $t(migrationJobId ? 'label.retry' : 'label.ok') }}
        </a-button>
      </mold-dialog-actions>
    </a-form>
  </div>
</template>

<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { isNfsImageStore, listMigrationStores, migrationDestinations, secondaryStorageMigrationJob } from '@/utils/secondaryStorageMigration'

export default {
  name: 'MigrateData',
  mixins: [secondaryStorageMigrationJob],
  components: { MoldDialogActions },
  emits: ['close-action', 'refresh-data'],
  data () {
    return {
      stores: [],
      form: { srcpool: undefined, destpools: [], migrationtype: 'Complete' },
      loading: false,
      readFailed: false
    }
  },
  computed: {
    sources () { return this.stores.filter(isNfsImageStore) },
    source () { return this.sources.find(row => row.id === this.form.srcpool) },
    destinations () { return migrationDestinations(this.source, this.stores) },
    locked () { return this.loading || this.migrating || !!this.migrationJobId },
    canSubmit () {
      return !this.migrating && (!!this.migrationJobId || (!this.loading && !this.readFailed &&
        this.form.destpools.length > 0 && this.form.destpools.every(id => this.destinations.some(row => row.id === id))))
    }
  },
  created () { this.fetchStores() },
  methods: {
    async fetchStores () {
      if (this.locked) return
      this.loading = true
      this.readFailed = false
      try {
        this.stores = await listMigrationStores()
        this.changeSource(this.source?.id || this.sources[0]?.id)
      } catch {
        this.readFailed = true
      } finally {
        this.loading = false
      }
    },
    storeOption (store) { return { value: store.id, label: store.name || store.url || store.id } },
    changeSource (id) {
      this.form.srcpool = id
      this.form.destpools = this.form.destpools.filter(id => this.destinations.some(row => row.id === id))
      this.migrationError = ''
    },
    async submit (event) {
      if (event?.preventDefault) event.preventDefault()
      if (!this.canSubmit) return
      const { srcpool, destpools, migrationtype } = this.form
      if (await this.runMigration('migrateSecondaryStorageData', { srcpool, destpools: destpools.join(','), migrationtype })) {
        this.$emit('refresh-data')
        this.$emit('close-action')
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.form-layout {
  .ant-alert { margin-bottom: 12px; }
}
</style>
