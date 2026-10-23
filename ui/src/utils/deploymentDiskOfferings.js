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
import { readAllocationList } from '@/utils/volumeAllocation'

export function deploymentDiskOfferingScope (options = {}, store) {
  return JSON.stringify([store?.getters.userInfo?.id || '', options.zoneid || '', options.domainid || '', options.account || '', options.projectid || ''])
}

// Fetch every API page once for this owner/zone. ROOT/DATA selectors can then
// filter and page independently without replacing each other's selected objects.
export async function fetchDeploymentDiskOfferings (vm, param) {
  const token = Symbol('deployment disk offerings')
  vm.diskOfferingLookupToken = token
  const query = Object.fromEntries(Object.entries(param.options || {}).filter(([key]) => !['page', 'pageSize', 'pagesize', 'keyword'].includes(key)))
  query.listall = true
  const scope = deploymentDiskOfferingScope(query, vm.$store)
  if (scope !== vm.diskOfferingLookupScope) {
    vm.options.diskOfferings = []
    vm.rowCount.diskOfferings = 0
    vm.diskOffering = null; vm.overrideDiskOffering = null; vm.diskSelected = {}; vm.rootDiskSelected = {}
    delete vm.form.diskofferingid; delete vm.form.overridediskofferingid
  }
  vm.diskOfferingLookupScope = scope
  const current = () => vm.diskOfferingLookupToken === token && !vm.$?.isUnmounted &&
    scope === deploymentDiskOfferingScope(vm.params.diskOfferings.options, vm.$store)
  vm.loading.diskOfferings = true; param.loading = true; vm.diskOfferingsError = ''
  try {
    const rows = await readAllocationList(getAPI, 'listDiskOfferings', 'diskoffering', query, current)
    if (!current() || !rows) return
    vm.options.diskOfferings = rows; vm.rowCount.diskOfferings = rows.length; param.opts = rows
    return { listdiskofferingsresponse: { count: rows.length, diskoffering: rows } }
  } catch (error) {
    if (current()) vm.diskOfferingsError = error.response?.data?.listdiskofferingsresponse?.errortext || error.message || vm.$t('message.disk.offering.lookup.failed')
  } finally {
    if (current()) { vm.loading.diskOfferings = false; param.loading = false }
  }
}
