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
import { storageReadDeadline } from '@/utils/storageRead'

export function requireAdServiceApproval (instance, approval) {
  if (!instance?.id || !instance.name || approval?.maintenancewindow !== true || approval.confirmation !== instance.name) {
    throw new Error('AD_SERVICE_APPROVAL_REQUIRED')
  }
  return { maintenancewindow: true, confirmation: approval.confirmation }
}

export function requireJoinedAdReceipt (response, instance, domain, now = Date.now() / 1000) {
  const body = response?.liststorageservicedomainstatusresponse
  const rows = Array.isArray(body?.storageidentitydomain) ? body.storageidentitydomain : body?.storageidentitydomain ? [body.storageidentitydomain] : []
  const row = rows.length === 1 ? rows[0] : null
  const receipt = row?.identityreceipt
  const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
  const domainName = typeof domain === 'string' ? domain.trim().toLowerCase() : ''
  if (!domainName || !uuid.test(instance?.id || '') || row?.instanceid !== instance.id || typeof row?.domainname !== 'string' || row.domainname.toLowerCase() !== domainName || row.joinstate !== 'JOINED' || row.healthstate !== 'OK' ||
    !receipt || typeof receipt !== 'object' || Array.isArray(receipt) || receipt.success !== true || receipt.sideEffects !== false || receipt.joinState !== 'JOINED' ||
    receipt.domain !== domainName || receipt.realm !== domainName.toUpperCase() || receipt.scope?.instanceUuid !== instance.id ||
    !uuid.test(receipt.scope?.operationUuid || '') || !Number.isSafeInteger(receipt.scope?.revision) || receipt.scope.revision < 0 || !uuid.test(receipt.bootId || '') ||
    typeof receipt.generatedEpoch !== 'number' || !Number.isFinite(receipt.generatedEpoch) || !Number.isFinite(now) || now - receipt.generatedEpoch > 60 || receipt.generatedEpoch - now > 5 ||
    ['trustVerified', 'identityVerified', 'dnsAliasesVerified', 'adSpnsVerified', 'adIdentity'].some(field => receipt[field] !== true)) {
    throw new Error('AD_IDENTITY_RECEIPT_UNVERIFIED')
  }
  return receipt
}

export async function readJoinedAdReceipt (instance, domain) {
  const id = instance.id
  const response = await storageReadDeadline(getAPI('listStorageServiceDomainStatus', { instanceid: id, fresh: true }, { timeout: 60000, preserveOnFailure: true }), 60000)
  if (instance.id !== id) throw new Error('AD_IDENTITY_RECEIPT_UNVERIFIED')
  return requireJoinedAdReceipt(response, instance, domain)
}

export function supportsAdMaintenanceApi (getParams, command, needsFresh = false) {
  const params = getParams?.(command)
  return !!params?.maintenancewindow && !!params?.confirmation && (command !== 'joinStorageServiceToAdDomain' || !!params?.identitymode) &&
    (!needsFresh || !!getParams?.('listStorageServiceDomainStatus')?.fresh)
}
