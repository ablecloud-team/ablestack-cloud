// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { getAPI, postAPI } from '@/api'
import { createVpcOfferingOperation, vpcOfferingBusy, vpcOfferingBlocked } from './vpcOfferingOperations'

const controllers = new Map()
const watchedStores = new WeakSet()
const prefix = 'vpc.offering.operation.v1:'
const clone = value => JSON.parse(JSON.stringify(value))
const identity = store => {
  const user = store?.getters?.userInfo || {}
  return [user.id, user.domainid, store?.getters?.project?.id || '']
}
const security = store => JSON.stringify([...identity(store), store?.state?.user?.token, store?.getters?.userInfo?.roleid])
export function clearVpcOfferingOperations (clearStored = true) {
  for (const controller of controllers.values()) controller.invalidate(clearStored)
  controllers.clear()
  try {
    if (!clearStored) return
    Object.keys(sessionStorage).filter(key => key.startsWith(prefix)).forEach(key => sessionStorage.removeItem(key))
  } catch (_) {}
}
const draftFields = ['selectedServiceProviderMap', 'serviceProviderMap', 'supportedServices', 'domains', 'zones', 'serviceOfferings', 'provider', 'networkmode', 'internetProtocolValue', 'isPublic', 'sourceNatServiceChecked', 'connectivityServiceChecked', 'isVpcVirtualRouterForAtLeastOneService']

export const vpcOfferingOperationMixin = {
  data: () => ({ offeringOperation: null, offeringController: null, offeringUnsubscribe: null, offeringDraftVersion: 0, offeringContextVersion: 0, offeringDisposed: false }),
  computed: {
    offeringScope () { return security(this.$store) },
    offeringSlot () { return this.$options.name === 'CloneVpcOffering' ? 'clone:' + this.resource?.id : 'create' },
    offeringContext () { return JSON.stringify([this.offeringScope, this.offeringSlot, this.$route?.fullPath]) },
    offeringBusy () { return vpcOfferingBusy(this.offeringOperation) },
    offeringBlocked () { return vpcOfferingBlocked(this.offeringOperation) }
  },
  watch: {
    offeringContext (next, previous) {
      this.offeringContextVersion++
      if (this.offeringUnsubscribe) this.offeringUnsubscribe()
      this.offeringUnsubscribe = null
      this.offeringOperation = null; this.loading = false
      if (previous && JSON.parse(next)[0] !== JSON.parse(previous)[0]) {
        this.selectedServiceProviderMap = {}
        this.initForm()
      }
      const context = this.offeringContext
      this.$nextTick(() => { if (!this.offeringDisposed && context === this.offeringContext) this.attachOfferingOperation() })
    }
  },
  mounted () {
    if (this.$store?.watch && !watchedStores.has(this.$store)) {
      const store = this.$store
      watchedStores.add(store)
      store.watch(() => security(store), () => clearVpcOfferingOperations(false))
    }
    this.attachOfferingOperation()
  },
  beforeUnmount () {
    this.offeringDisposed = true; this.offeringContextVersion++
    if (this.offeringUnsubscribe) this.offeringUnsubscribe()
  },
  methods: {
    attachOfferingOperation () {
      if (this.offeringUnsubscribe) this.offeringUnsubscribe()
      const key = JSON.stringify([this.offeringScope, this.offeringSlot])
      const store = this.$store
      if (!controllers.has(key)) {
        controllers.set(key, createVpcOfferingOperation({
          get: getAPI,
          post: postAPI,
          storage: sessionStorage,
          storageKey: prefix + JSON.stringify([...identity(this.$store), this.offeringSlot]),
          scope: this.offeringScope,
          getScope: () => security(store),
          userId: store?.getters?.userInfo?.id
        }))
      }
      this.offeringController = controllers.get(key)
      const saved = this.offeringController.snapshot()
      if (saved.draft.form) {
        Object.assign(this.form, clone(saved.draft.form))
        for (const field of draftFields) if (field in saved.draft) this[field] = clone(saved.draft[field])
        this.offeringDraftVersion++
      }
      const context = this.offeringContext
      this.offeringUnsubscribe = this.offeringController.subscribe(state => {
        const previous = this.offeringOperation?.status
        this.offeringOperation = state
        if (state.status !== 'idle' && state.status !== previous) {
          this.$nextTick(() => {
            const status = this.$el?.querySelector?.('.vpc-offering-operation')
            if (!this.offeringDisposed && context === this.offeringContext && status?.scrollIntoView) status.scrollIntoView({ block: 'start' })
          })
        }
      })
    },
    offeringDraft () {
      return clone({ form: this.form, ...Object.fromEntries(draftFields.filter(field => this[field] !== undefined).map(field => [field, this[field]])) })
    },
    async submitOfferingOperation (command, params) {
      const context = this.offeringContext
      const version = this.offeringContextVersion
      const controller = this.offeringController
      const result = await controller.run(command, params, this.offeringDraft())
      if (this.offeringDisposed || context !== this.offeringContext || version !== this.offeringContextVersion) return
      this.loading = false
      if (result.status === 'succeeded') this.finishOfferingOperation()
    },
    async recheckOfferingOperation () {
      const controller = this.offeringController
      const context = this.offeringContext
      const version = this.offeringContextVersion
      const result = await controller.recheck()
      if (!this.offeringDisposed && context === this.offeringContext && version === this.offeringContextVersion && result.status === 'succeeded') this.finishOfferingOperation()
    },
    finishOfferingOperation () {
      if (this.offeringDisposed || this.offeringOperation?.status !== 'succeeded') return
      const state = this.offeringOperation
      this.$message.success(this.$t(state.command === 'cloneVPCOffering' ? 'message.success.clone.vpc.offering' : 'message.create.vpc.offering') + ': ' + state.params.name)
      this.$emit('refresh-data')
      this.offeringController.acknowledge()
      this.$emit('close-action')
    }
  }
}
