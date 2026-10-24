// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import { freezeSnapshotTarget, freshStorageSnapshotContext, snapshotSecurityScope, snapshotActionParams, snapshotResourceKeys, copyTarget } from '@/utils/storageSnapshotContext'
import { submitSnapshotOperation, snapshotOperationBusy } from '@/utils/storageSnapshotOperations'
import StorageSnapshotSafety from '@/components/view/StorageSnapshotSafety.vue'

export function storageSnapshotSafety (api, kind = 'snapshot', enabled = () => true) {
  return {
    components: { StorageSnapshotSafety },
    data () { return { snapshotSafetyTarget: null, snapshotSafetyContext: null, snapshotSafetyLoading: false, snapshotSafetyError: '', snapshotSafetyFingerprint: '', snapshotSafetySequence: 0 } },
    created () {
      if (!enabled(this)) return
      this.snapshotSafetyTarget = freezeSnapshotTarget(this.resource, { kind })
      this.refreshSnapshotSafety()
    },
    computed: {
      snapshotSafetyPending () {
        return !!this.snapshotSafetyTarget && snapshotOperationBusy(snapshotSecurityScope(this.$store), snapshotResourceKeys(this.snapshotSafetyTarget, this.snapshotSafetyContext))
      },
      snapshotSafetyBlocked () {
        if (!this.snapshotSafetyTarget) return false
        return this.snapshotSafetyLoading || !this.snapshotSafetyContext || !!this.snapshotSafetyContext.reason || !!this.snapshotSafetyError || !this.dialogCurrent() || this.snapshotSafetyPending
      }
    },
    methods: {
      snapshotSafetyMessage (error) { return this.$t(Object.values(error?.response?.data || {}).find(value => value?.errortext)?.errortext || error?.message || error || 'message.snapshot.action.validation.failed') },
      async refreshSnapshotSafety () {
        if (!this.snapshotSafetyTarget || !this.dialogCurrent()) return
        const sequence = ++this.snapshotSafetySequence
        this.snapshotSafetyLoading = true; this.snapshotSafetyError = ''
        try {
          const context = await freshStorageSnapshotContext(this.snapshotSafetyTarget, api, () => this.dialogCurrent() && sequence === this.snapshotSafetySequence)
          if (!this.dialogCurrent() || sequence !== this.snapshotSafetySequence) return
          this.snapshotSafetyContext = context; this.snapshotSafetyFingerprint = context.fingerprint
        } catch (error) { if (this.dialogCurrent()) this.snapshotSafetyError = this.snapshotSafetyMessage(error) } finally { if (sequence === this.snapshotSafetySequence) this.snapshotSafetyLoading = false }
      },
      async submitSnapshotSafely (values, beforeSubmit = async () => {}) {
        const target = this.snapshotSafetyTarget; const initial = this.snapshotSafetyFingerprint; const store = this.$store
        if (!target || this.snapshotSafetyBlocked) throw new Error(this.$t('message.snapshot.action.busy'))
        const scope = snapshotSecurityScope(store); const captured = copyTarget(values)
        const current = () => this.dialogCurrent() && snapshotSecurityScope(store) === scope
        try {
          const entry = await submitSnapshotOperation({
            api,
            target: { id: target.id, name: target.record.name, volumeid: this.snapshotSafetyContext.record.volumeid, kind: target.kind, zoneid: target.zoneId },
            scope,
            keys: snapshotResourceKeys(target, this.snapshotSafetyContext),
            current,
            securityCurrent: () => snapshotSecurityScope(store) === scope,
            validate: async () => {
              const latest = await freshStorageSnapshotContext(target, api, current, captured)
              if (latest.reason) throw new Error('message.snapshot.action.reason.' + latest.reason)
              if (latest.fingerprint !== initial) throw new Error('message.snapshot.action.changed')
              await beforeSubmit()
              return snapshotActionParams(target, api, captured, latest)
            }
          })
          if (current() && ['unknown', 'failed'].includes(entry.status)) this.snapshotSafetyError = this.$t(entry.error)
          if (entry.completion) entry.completion.then(result => { if (this.dialogCurrent() && result.jobstatus === 1 && entry.status === 'complete') this.$emit('refresh-data') })
          return entry
        } catch (error) {
          if (this.dialogCurrent()) this.snapshotSafetyError = this.snapshotSafetyMessage(error)
          throw new Error(this.snapshotSafetyMessage(error))
        }
      }
    }
  }
}
