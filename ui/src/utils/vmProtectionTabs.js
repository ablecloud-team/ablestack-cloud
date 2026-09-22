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

import { getAPI } from '@/api'
import { getDrVmProtectionView } from '@/api/dr'

export const isFtMachineCompatible = details =>
  String(details?.['kvm.guest.os.machine.type'] || '').trim().toLowerCase().startsWith('pc-i440fx-')

export const hasDrRelationship = view => Array.isArray(view?.association) && view.association.length > 0

// Own the eligibility queries in the VM parent, not in an already visible tab.
export default {
  data () {
    return {
      protectionVmId: null,
      protectionRequest: 0,
      protectionDisposed: false,
      ftEligibility: 'pending',
      ftCompatible: false,
      drEligibility: 'pending',
      drView: null
    }
  },
  computed: {
    canReadFt () { return 'getFtctlProtection' in this.$store.getters.apis },
    canReadDr () { return 'getDrVmProtectionView' in this.$store.getters.apis },
    showFtTab () {
      return this.protectionVmId === this.resource.id && this.canReadFt && this.ftEligibility === 'ready' && this.ftCompatible
    },
    showDrTab () {
      return this.protectionVmId === this.resource.id && this.canReadDr && hasDrRelationship(this.drView)
    },
    protectionLookupFailed () { return this.ftEligibility === 'error' || this.drEligibility === 'error' },
    protectionLookupPending () { return this.ftEligibility === 'pending' || this.drEligibility === 'pending' },
    visibleCurrentTab () {
      if (this.currentTab === 'ftctl' && !this.showFtTab) return 'details'
      if (this.currentTab === 'drplans' && !this.showDrTab) return 'details'
      return this.currentTab
    }
  },
  created () { this.refreshProtectionTabs() },
  beforeUnmount () {
    this.protectionDisposed = true
    this.protectionRequest++
  },
  watch: {
    resource: { deep: true, handler () { this.refreshProtectionTabs() } },
    canReadFt () { this.refreshProtectionTabs() },
    canReadDr () { this.refreshProtectionTabs() }
  },
  methods: {
    async refreshProtectionTabs () {
      const id = this.resource?.id
      const request = ++this.protectionRequest
      if (id !== this.protectionVmId) {
        this.ftCompatible = false
        this.drView = null
      }
      this.protectionVmId = id
      this.ftEligibility = id && this.canReadFt ? 'pending' : 'unavailable'
      this.drEligibility = id && this.canReadDr ? 'pending' : 'unavailable'
      if (!this.canReadDr) this.drView = null
      const current = () => !this.protectionDisposed && request === this.protectionRequest && id === this.resource?.id
      const ft = async () => {
        if (this.ftEligibility !== 'pending') return
        try {
          let details = this.resource.details
          if (!details || typeof details !== 'object') {
            const json = await getAPI('listVirtualMachines', { id, details: 'all', listall: true })
            const vm = json?.listvirtualmachinesresponse?.virtualmachine?.find(vm => vm.id === id)
            if (!vm) throw new Error('VM details unavailable')
            details = vm.details || {}
          }
          if (!current()) return
          this.ftCompatible = isFtMachineCompatible(details)
          this.ftEligibility = 'ready'
        } catch (e) {
          if (current()) { this.ftCompatible = false; this.ftEligibility = 'error' }
        }
        if (current()) this.normalizeProtectionTab()
      }
      const dr = async () => {
        if (this.drEligibility !== 'pending') return
        try {
          const view = await getDrVmProtectionView(id)
          // Do not mistake a malformed response or a different VM for an empty relation.
          if (!view || view.virtualmachineid !== id || ![true, false].includes(view.configured)) throw new Error('Invalid DR view')
          if (view.configured && !hasDrRelationship(view)) throw new Error('Missing DR relationship')
          if (!current()) return
          this.drView = view
          this.drEligibility = 'ready'
        } catch (e) {
          if (current()) this.drEligibility = 'error'
        }
        if (current()) this.normalizeProtectionTab()
      }
      this.normalizeProtectionTab()
      await Promise.all([ft(), dr()])
    },
    normalizeProtectionTab () {
      if (this.$route?.path !== '/vm/' + this.resource?.id) return
      const tab = this.resolveCurrentTabFromRoute()
      const hidden = (tab === 'ftctl' && (!this.canReadFt || (this.ftEligibility === 'ready' && !this.ftCompatible))) ||
        (tab === 'drplans' && (!this.canReadDr || (this.drEligibility === 'ready' && !hasDrRelationship(this.drView))))
      if (!hidden) return
      this.currentTab = 'details'
      const hash = window.location.hash
      const query = new URLSearchParams(hash.split('?')[1] || '')
      query.set('tab', 'details')
      // Preserve other query fields and avoid adding a redundant history entry.
      this.$router.replace({ path: this.$route.path, query: Object.fromEntries(query) })
    }
  }
}
