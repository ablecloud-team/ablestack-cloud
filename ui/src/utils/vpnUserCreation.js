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

import { networkError } from '@/utils/networkLifecycle'

const credentials = {
  username: /^[a-zA-Z0-9][a-zA-Z0-9@._-]{2,63}$/,
  password: /^[a-zA-Z0-9][a-zA-Z0-9@+=._-]{2,31}$/
}

const supports = (getters, ...names) =>
  names.every(name => getters.apis?.addVpnUser?.params?.some(param => param.name === name))

export function canCreateVpnUser (getters) {
  return !!getters.apis?.queryAsyncJobResult && supports(getters, 'username', 'password') &&
    (!getters.project?.id || supports(getters, 'projectid'))
}

export function canChooseVpnUserAccount (getters) {
  return !getters.project?.id && ['Admin', 'DomainAdmin', 'ResourceAdmin'].includes(getters.userInfo?.roletype) &&
    !!getters.apis?.listDomains && !!getters.apis?.listAccounts && supports(getters, 'domainid', 'account')
}

export function vpnUserCredentialError (field, value) {
  if (!value) return 'message.vpn.user.add.' + field + '.required'
  // In JavaScript $ also matches immediately before a trailing newline.
  return credentials[field].exec(value)?.[0] === value ? '' : 'message.vpn.user.add.' + field + '.format'
}

export function vpnUserCreateParams (form, owner) {
  if (!owner || vpnUserCredentialError('username', form.username) || vpnUserCredentialError('password', form.password)) return null
  const { projectid, domainid, account, own } = owner
  if ((projectid || own) && (domainid || account)) return null
  if (projectid && own) return null
  if (!projectid && !own && !(domainid && account)) return null
  return {
    username: form.username,
    password: form.password,
    ignoreproject: true,
    ...(projectid ? { projectid } : own ? {} : { domainid, account })
  }
}

export function vpnUserCreateFailure (error, jobResult) {
  const detail = networkError(error)
  const status = Number(jobResult?.errorcode || detail.code || error?.response?.status)
  const reason = jobResult?.errortext || detail.text
  if (!jobResult && ![400, 401, 403, 409, 422, 429, 430, 431, 432, 531, 532].includes(status)) return 'unknown'
  // Only known localized messages may reach the form. Server errors can
  // contain submitted data, including secrets from upstream components.
  if (/already added/i.test(reason)) return 'duplicate'
  if (status === 532 || /cannot add more than/i.test(reason)) return 'limit'
  if (/another operation is active/i.test(reason)) return 'busy'
  if (/unable to find (domain|project|account)|no longer active/i.test(reason)) return 'owner'
  if ([401, 403, 531].includes(status)) return 'permission'
  return jobResult ? 'job' : 'rejected'
}
