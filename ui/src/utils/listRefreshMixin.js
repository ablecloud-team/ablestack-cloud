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

import { createListRefresh, canRefreshList, listRowKey } from './listRefresh'

// A detail tab chooses presentation, not the rows requested by shared tabs.
export function detailTabRouteKey (route) {
  const query = { ...route?.query }
  delete query.tab
  return JSON.stringify([route?.path, query])
}

function scope (vm, routeKey) {
  return JSON.stringify([routeKey(vm), vm.$store?.getters.project?.id,
    vm.$store?.getters.userInfo?.id, vm.resource?.id, vm.record?.id, vm.record?.uid, vm.detailId, vm.activeTab, vm.currentTab, vm.sorter, vm.sortOrder, vm.planId, vm.zoneId,
    vm.page, vm.pageSize, vm.pagesize, vm.pagination?.current, vm.pagination?.pageSize, vm.filter, Array.isArray(vm.filters) ? null : vm.filters,
    vm.filterValue, vm.searchQuery, vm.keyword, vm.searchParams, vm.vm?.id, vm.startDate, vm.endDate, typeof vm.state === 'object' ? undefined : vm.state, vm.domainid, vm.projectid, vm.group, vm.subgroup, vm.poolId, vm.browserPath, vm.searchPrefix, vm.browserPageSize, vm.resourceType, vm.client ? vm.resource?.name : undefined, vm.network?.id],
  (key, value) => ['command', 'response', 'sessionkey', 'total', 'count'].includes(key) ? undefined : value)
}

// Explicit adapters only: never discover/invoke APIs from a table or API metadata.
export function listRefreshMixin (methods, { interval = 10000, active = () => true, select = () => methods, reuseArgs = false, routeKey = vm => vm.$route?.fullPath } = {}) {
  return {
    data: () => ({ listRefreshing: 0, listRefreshFailed: false, listLastUpdated: null }),
    created () {
      this.listRefreshRequests = new Map()
      this.listRefreshDisposed = false
      methods.forEach(name => {
        const original = this[name]
        this[name] = (...args) => {
          const key = scope(this, routeKey)
          const previous = this.listRefreshRequests.get(name)
          if (previous?.pending && previous.key === key) return previous.promise
          const request = { key, args, pending: true, loaded: previous?.loaded && previous.key === key }
          this.listRefreshRequests.set(name, request)
          if (request.loaded) this.listRefreshing += 1
          let result
          try { result = original(...args) } catch (error) { result = Promise.reject(error) }
          request.promise = Promise.resolve(result).then(value => {
            if (this.isListRequestCurrent(name, request) && !request.failed && result?.then) {
              request.loaded = true
              this.listLastUpdated = Date.now()
              this.listRefreshFailed = Array.from(this.listRefreshRequests.values()).some(item => item.failed && item.key === scope(this, routeKey))
            }
            return value
          }).finally(() => {
            request.pending = false
            if (request.loaded && previous?.loaded && previous.key === key) this.listRefreshing -= 1
          })
          return request.promise
        }
      })
    },
    mounted () {
      this.listRefreshController = createListRefresh({
        interval,
        active: () => !this.listRefreshDisposed && active(this) && canRefreshList(this.$el),
        refresh: async () => {
          await Promise.all(select(this).map(name => this[name](...(reuseArgs ? this.listRefreshRequests.get(name)?.args || [] : []))))
          if (this.listRefreshFailed) throw new Error('List refresh failed')
        }
      })
    },
    beforeUnmount () {
      this.listRefreshDisposed = true
      if (this.listRefreshController) this.listRefreshController.stop()
    },
    methods: {
      listRowKey,
      listRefreshScope () { return scope(this, routeKey) },
      listRequestToken (name) {
        const request = this.listRefreshRequests.get(name)
        request.key = scope(this, routeKey)
        return request
      },
      isListRequestCurrent (name, token) {
        return !this.listRefreshDisposed && token === this.listRefreshRequests.get(name) && token.key === scope(this, routeKey)
      }
    }
  }
}
