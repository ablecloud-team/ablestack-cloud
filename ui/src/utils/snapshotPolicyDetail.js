// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for ownership information.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
export const policyDetailRoute = route => (route?.meta?.name || route?.name) === 'snapshotpolicy' && !!route?.params?.id
// The volume route requires listVolumesMetrics, independently of policy/volume API read access.
export const policyVolumePath = (record, apis = {}) => record?.volumeid && 'listVolumesMetrics' in apis
  ? { path: '/volume/' + record.volumeid, ...(record.projectid ? { query: { projectid: record.projectid } } : {}) } : null
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(value)
const fail = (message = 'message.snapshot.policy.detail.incomplete', status) => { const error = new Error(message); if (status) error.response = { status }; throw error }
export const policyDetailPermissions = getters => ['listSnapshotPolicies', 'listSnapshots', 'listVolumes', 'listZones', 'listStoragePools', 'listAccounts', 'listDomains', 'listProjects'].map(api => api in (getters.apis || {}))
export async function readPolicyDetail (request, route, project, current = () => true) {
  if (!uuid(route.params.id)) fail('message.snapshot.policy.detail.missing', 404)
  const projectid = route.query?.projectid || project?.id
  if (projectid && !['0', '-1'].includes(projectid) && !uuid(projectid)) fail()
  let json
  try {
    json = await request('listSnapshotPolicies', { id: route.params.id, listall: true, page: 1, pagesize: 1, includepolicycontext: true, ...(projectid && projectid !== '0' ? { projectid } : {}), ...(projectid === '0' ? { ignoreproject: true } : {}) }, { preserveOnFailure: true, timeout: 20000 })
  } catch (error) {
    const code = Number(Object.values(error?.response?.data || {}).find(data => data?.errorcode)?.errorcode || error?.response?.status)
    if ([401, 403, 404, 431, 432, 531].includes(code)) fail('message.snapshot.policy.detail.missing', code)
    throw error
  }
  if (!current()) fail('message.snapshot.policy.context.changed')
  const data = json?.listsnapshotpoliciesresponse; const rows = data?.snapshotpolicy || []
  if (!data || !Array.isArray(rows) || Number(data.count ?? rows.length) !== rows.length || rows.length > 1) fail()
  if (!rows.length) fail('message.snapshot.policy.detail.missing', 404)
  const p = rows[0]; const c = p.policycontext
  if (!uuid(p.id) || p.id.toLowerCase() !== route.params.id.toLowerCase() || !uuid(p.volumeid) || typeof p.schedulingenabled !== 'boolean' || !(p.account || p.projectid) || !uuid(p.domainid) || !p.timezone || typeof p.schedule !== 'string' || !Array.isArray(p.zone) || !Array.isArray(p.storage) || !Array.isArray(p.tags)) fail()
  if (projectid && !['0', '-1'].includes(projectid) && p.projectid !== projectid) fail()
  if (projectid === '0' && p.projectid) fail()
  if (c?.version !== 1 || c.volumeid !== p.volumeid || c.schedulingenabled !== p.schedulingenabled || !Number.isFinite(Date.parse(c.observedat)) || !['PAUSED', 'UNKNOWN', 'ASSIGNED', 'SCHEDULED'].includes(c.schedulestate) || c.historysource !== 'RETAINED_SCHEDULER_JOBS' || c.historylimit !== 50 || !(c.pollintervalseconds > 0) || typeof c.currentjobknown !== 'boolean') fail()
  if (c.nextscheduled && (!Number.isFinite(Date.parse(c.nextscheduled)) || c.schedulestate !== 'SCHEDULED')) fail()
  const job = c.latestjob
  if (job && (!uuid(job.id) || !['SUCCEEDED', 'FAILED', 'IN_PROGRESS', 'UNKNOWN'].includes(job.status) || !Number.isFinite(Date.parse(job.created)) || typeof job.snapshotavailable !== 'boolean' || (job.snapshotavailable && !uuid(job.snapshotid)))) fail()
  return json
}
