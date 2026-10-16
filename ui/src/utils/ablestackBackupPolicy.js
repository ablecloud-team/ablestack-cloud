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

export const isAblestackBackupProvider = record =>
  ['ablestack-nas', 'ablestack-commvault', 'ablestack-netbackup', 'ablestack-veeam'].includes(String(record?.backupprovider || record?.provider || '').toLowerCase())

export const getAblestackBackupOfferingRemovalMessage = record =>
  isAblestackBackupProvider(record) ? 'message.backup.offering.remove.preserve' : 'message.backup.offering.remove'

export const getAblestackBackupOfferingDeletionMessage = record =>
  String(record?.provider || '').toLowerCase() === 'ablestack-commvault'
    ? 'message.backup.offering.delete.commvault' : 'message.action.delete.backup.offering'

export const getAblestackBackupOfferingVisibleDetails = record => {
  const details = record?.backupofferingdetails || {}
  if (String(record?.provider || '').toLowerCase() !== 'ablestack-commvault') return details
  return Object.fromEntries(Object.entries(details).filter(([key]) => !key.startsWith('commvault.managed.backupset.')))
}

export const isAblestackOriginalVmUnavailable = record => {
  return isAblestackBackupProvider(record) &&
    (record.vmrestoreavailable === false || record.isbackupvmexpunged === true)
}
