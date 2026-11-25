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

import { getAPI, postAPI } from '@/api'

export function isNfsImageStore (store) {
  return !!store?.id && String(store.providername || store.provider || store.protocol).toUpperCase() === 'NFS'
}

export function migrationDestinations (source, stores) {
  if (!isNfsImageStore(source)) return []
  return stores.filter(store => isNfsImageStore(store) && store.id !== source.id &&
    String(store.readonly) !== 'true' && (!source.zoneid || !store.zoneid || source.zoneid === store.zoneid))
}

export async function listMigrationStores () {
  const json = await getAPI('listImageStores', { pagesize: -1 }, { preserveOnFailure: true })
  return json.listimagestoresresponse.imagestore || []
}

export const secondaryStorageMigrationJob = {
  data () {
    return { migrationJobId: null, migrating: false, migrationError: '' }
  },
  methods: {
    async runMigration (command, params) {
      if (this.migrating || !this.$store.getters.apis[command]) return false
      this.migrating = true
      this.migrationError = ''
      const retry = !!this.migrationJobId
      try {
        if (!this.migrationJobId) {
          const response = await postAPI(command, params, { preserveOnFailure: true })
          this.migrationJobId = response[command.toLowerCase() + 'response']?.jobid
          if (!this.migrationJobId) {
            this.migrationError = this.$t('message.secondary.storage.migration.jobid')
            return false
          }
        }
        const job = await this.$pollJob({
          jobId: this.migrationJobId,
          retry,
          title: this.$t('message.data.migration'),
          showLoading: false,
          showSuccessMessage: false,
          action: { suppressErrorNotification: true }
        })
        if (![1, 2].includes(job.jobstatus)) {
          this.migrationError = this.$t('message.secondary.storage.migration.unknown')
          return false
        }
        this.migrationJobId = null
        const result = job.jobresult?.imagestore
        if (job.jobstatus === 1 && result?.success) {
          this.$notification.success({ message: this.$t('message.success.migration'), description: result.message })
          return true
        }
        this.migrationError = result?.message || job.jobresult?.errortext || this.$t('message.migrating.failed')
      } catch {
        this.migrationError = this.$t(this.migrationJobId ? 'message.secondary.storage.migration.unknown' : 'message.migrating.failed')
      } finally {
        this.migrating = false
      }
      return false
    }
  }
}
