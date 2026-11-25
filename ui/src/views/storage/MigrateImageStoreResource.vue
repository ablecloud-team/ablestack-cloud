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
  <div v-ctrl-enter="submitForm">
    <a-alert type="error">
      <template #message>
        <span v-html="$t('message.migrate.resource.to.ss')" />
      </template>
    </a-alert>
    <image-store-selector
      :source-image-store="sourceImageStore"
      :selected-id="selection"
      :disabled="migrating || !!migrationJobId"
      @candidates="updateCandidates"
      @select="selection = $event" />
    <a-alert v-if="migrationError" type="error" show-icon :message="migrationError" />
    <a-divider />

    <div class="actions">
      <a-button :disabled="migrating" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="migrating" :disabled="!canSubmit" @click="submitForm">
        {{ $t(migrationJobId ? 'label.retry' : 'label.ok') }}
      </a-button>
    </div>
  </div>
</template>

<script>
import ImageStoreSelector from '@/components/view/ImageStoreSelectView'
import { secondaryStorageMigrationJob } from '@/utils/secondaryStorageMigration'

export default {
  name: 'MigrateImageStoreResource',
  mixins: [secondaryStorageMigrationJob],
  components: { ImageStoreSelector },
  props: {
    snapshotIdsToMigrate: { type: Array, default: () => [] },
    templateIdsToMigrate: { type: Array, default: () => [] },
    sourceImageStore: { type: Object, required: true }
  },
  emits: ['close-action', 'refresh-data'],
  data () { return { selection: null, candidates: [] } },
  computed: {
    canSubmit () {
      return !this.migrating && (!!this.migrationJobId || (this.candidates.includes(this.selection) &&
        (this.snapshotIdsToMigrate.length > 0 || this.templateIdsToMigrate.length > 0)))
    }
  },
  methods: {
    updateCandidates (value) {
      this.candidates = value
      if (!value.includes(this.selection)) this.selection = null
    },
    async submitForm (event) {
      if (event?.preventDefault) event.preventDefault()
      if (!this.canSubmit) return
      const params = { srcpool: this.sourceImageStore.id, destpool: this.selection }
      if (this.templateIdsToMigrate.length) params.templates = this.templateIdsToMigrate.join(',')
      if (this.snapshotIdsToMigrate.length) params.snapshots = this.snapshotIdsToMigrate.join(',')
      if (await this.runMigration('migrateResourceToAnotherSecondaryStorage', params)) {
        this.$emit('refresh-data')
        this.$emit('close-action')
      }
    }
  }
}
</script>

<style scoped lang="less">
  .form-layout {
    width: 80vw;

    @media (min-width: 900px) {
      width: 850px;
    }
  }

  .top-spaced {
    margin-top: 20px;
  }

  .radio-style {
    display: block;
    margin-left: 10px;
    height: 40px;
    line-height: 40px;
  }

  .actions {
    display: flex;
    justify-content: flex-end;
    margin-top: 20px;

    button {
      &:not(:last-child) {
        margin-right: 10px;
      }
    }
  }
</style>
