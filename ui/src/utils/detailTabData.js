// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for ownership information.
// Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
// Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { detailTabRouteKey } from './listRefreshMixin'

export function detailTabContext (vm, commands = []) {
  const getters = vm.$store?.getters || {}
  return JSON.stringify([detailTabRouteKey(vm.$route), vm.resource?.id,
    vm.resourceType || vm.$route?.meta?.resourceType, getters.project?.id,
    getters.userInfo?.id, getters.userInfo?.roleid, getters.userInfo?.roletype,
    vm.$store?.state?.user?.token, commands.map(command => command in (getters.apis || {}))])
}

export function detailTabDenied (error) {
  return [401, 403, 404, 431, 432, 531].includes(Number(error?.response?.data?.errorresponse?.errorcode || error?.response?.status))
}

export function detailTabError (error) {
  return Object.values(error?.response?.data || {}).find(value => value?.errortext)?.errortext ||
    (error?.message?.startsWith('message.') ? error.message : 'message.detail.tab.read.failed')
}

// Reject malformed or mixed-resource pages instead of rendering a false empty result.
export function readDetailTabPage (json, response, key, { page, pageSize, resourceId, entityType }) {
  const fail = () => { throw new Error('message.detail.tab.incomplete') }
  const data = json?.[response]
  if (!data || (data[key] !== undefined && !Array.isArray(data[key]))) fail()
  const rows = data[key] || []; const count = data.count ?? (rows.length ? NaN : 0)
  if (!Number.isSafeInteger(count) || count < 0 || rows.length !== Math.min(pageSize, Math.max(0, count - (page - 1) * pageSize))) fail()
  const ids = new Set()
  for (const row of rows) {
    if (typeof row.id !== 'string' || !row.id || ids.has(row.id) ||
      (row.resourceid && row.resourceid !== resourceId) ||
      (row.entityid && row.entityid !== resourceId) ||
      (entityType && row.entitytype && row.entitytype !== entityType)) fail()
    ids.add(row.id)
  }
  return { rows, count, lastPage: Math.max(1, Math.ceil(count / pageSize)) }
}

export function translatedSnapshotDownload (row) {
  const value = row.copy?.downloadstate
  return {
    DOWNLOADED: 'label.snapshot.copy.completed',
    NOT_DOWNLOADED: 'label.snapshot.copy.not.started',
    DOWNLOAD_IN_PROGRESS: 'label.snapshot.copy.downloading',
    DOWNLOAD_ERROR: 'label.snapshot.copy.download.failed',
    BYPASSED: 'label.snapshot.copy.not.required',
    ABANDONED: 'label.snapshot.copy.abandoned',
    UNKNOWN: 'label.snapshot.copy.unknown'
  }[value] || 'label.snapshot.copy.unknown'
}
