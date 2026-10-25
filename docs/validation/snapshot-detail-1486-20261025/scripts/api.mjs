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
import fs from 'node:fs'
const cluster = process.argv[2]
const phase = process.argv[3]
const root = process.env.EVIDENCE_ROOT || '/home/ablecloud/work/snapshot-detail-1486-20261025'
const registryFile = `${root}/${cluster}-fixtures.json`
const prefix = `SNAPDETAIL1486-${cluster}`
let cookies = '', session = '', deletionContractChecked = false, archiveContractChecked = false, forceContractChecked = false
const clean = x => JSON.parse(JSON.stringify(x, (k, v) => /password|passphrase|session|secret|api.?key|cookie|token/i.test(k) ? '[REDACTED]' : v))
async function api (command, args = {}) {
  if (command === 'deleteSnapshot' && args.force && !forceContractChecked) {
    const contract = await api('listApis', { name: 'deleteSnapshot' })
    if (!JSON.stringify(contract).includes('"name":"force"')) throw new Error('Refusing forced test: deployed API has no force contract')
    forceContractChecked = true
  }
  if (command === 'archiveSnapshot' && !archiveContractChecked) {
    const contract = await api('listApis', { name: 'archiveSnapshot' })
    if (!JSON.stringify(contract).includes('"name":"dryrun"')) throw new Error('Refusing archive test: deployed API has no dryrun contract')
    archiveContractChecked = true
  }
  if (command === 'deleteSnapshot' && !deletionContractChecked) {
    const contract = await api('listApis', { name: 'deleteSnapshot' })
    if (!JSON.stringify(contract).includes('"name":"dryrun"')) throw new Error('Refusing deletion test: deployed API has no dryrun contract')
    deletionContractChecked = true
  }
  const body = new URLSearchParams({ command, response: 'json', ...args, ...(session ? { sessionkey: session } : {}) })
  const response = await fetch(`http://10.10.${cluster}.10:8080/client/api`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', ...(cookies ? { Cookie: cookies } : {}) }, body, signal: AbortSignal.timeout(60000) })
  const set = response.headers.getSetCookie()
  if (set.length) cookies = set.map(v => v.split(';')[0]).join('; ')
  const responseText = await response.text()
  let json
  try { json = JSON.parse(responseText) } catch (_) {
    fs.writeFileSync(`${root}/${cluster}-unexpected-response.txt`, session ? responseText.split(session).join('[REDACTED]') : responseText)
    throw new Error(`Unexpected API response: ${command}, HTTP ${response.status}; saved local diagnostic`)
  }
  const data = Object.values(json)[0]
  fs.appendFileSync(`${root}/${cluster}-api.jsonl`, JSON.stringify(clean({ at: new Date().toISOString(), command, args, status: response.status, data })) + '\n')
  if (!response.ok || data.errorcode) throw new Error(JSON.stringify(clean(data)))
  return data
}
async function job (command, args) {
  const response = await api(command, args)
  if (!response.jobid) return response
  console.log(cluster, command, response.jobid)
  for (let i = 0; i < 300; i++) {
    const result = await api('queryAsyncJobResult', { jobid: response.jobid })
    if (result.jobstatus === 1) return result.jobresult?.[command.toLowerCase() + 'response'] || result.jobresult
    if (result.jobstatus === 2) throw new Error(JSON.stringify(result))
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  throw new Error(`Job result unknown: ${response.jobid}`)
}
export { api, job, clean, root, prefix, cluster, registryFile }
export async function login () {
 const result = await api('login', { username: process.env.MOLD_TEST_USER || 'admin', password: process.env.MOLD_TEST_PASSWORD, domain: process.env.MOLD_TEST_DOMAIN || '/' })
 session = result.sessionkey
 return result
}
export async function logout () { await api('logout') }
if (phase === 'inventory') {
 await login()
 const inventory = {}
 for (const command of ['listVolumes', 'listSnapshots', 'listVirtualMachines', 'listZones', 'listStoragePools', 'listDiskOfferings', 'listServiceOfferings', 'listNetworks', 'listTemplates', 'listOsTypes', 'listHosts', 'listImageStores', 'listProjects']) inventory[command] = await api(command, { listall: true, page: 1, pagesize: 500, ...(command === 'listSnapshots' ? { showunique: false } : {}), ...(command === 'listTemplates' ? { templatefilter: 'executable' } : {}) })
 fs.writeFileSync(`${root}/${cluster}-existing-before.json`, JSON.stringify(inventory, null, 2))
 console.log(cluster, 'inventory saved')
 await logout()
}
if (phase === 'call') {
 await login()
 const args = process.argv[5] ? JSON.parse(fs.readFileSync(process.argv[5])) : {}
 console.log(JSON.stringify(await job(process.argv[4], args), null, 2))
 await logout()
}
