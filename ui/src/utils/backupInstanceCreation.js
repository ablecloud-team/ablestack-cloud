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

import { h } from 'vue'
import { notification } from 'ant-design-vue'
import { getAPI } from '@/api'
import { i18n } from '@/locales'
import store from '@/store'
import eventBus from '@/config/eventBus'

const tasks = new Map()
const providers = ['ablestack-nas', 'ablestack-commvault', 'ablestack-netbackup', 'ablestack-veeam']
const terminalStates = ['COMPLETED', 'FAILED', 'INTERRUPTED', 'CANCELED', 'CANCELLED']
const scope = () => [store.state.user.token, store.getters.userInfo?.id, store.getters.project?.id].join('|')

export const isAblestackInstanceCreation = provider => providers.includes(String(provider || '').toLowerCase())

export function clearBackupInstanceCreations () {
  for (const task of tasks.values()) {
    window.clearTimeout(task.timer)
    notification.close(task.key)
  }
  tasks.clear()
}

export function trackBackupInstanceCreation ({ backupId, jobId, vm, router }) {
  if (!backupId || !jobId || tasks.has(jobId) || !('getBackupRestoreJobStatus' in store.getters.apis)) return
  const task = { key: jobId, scope: scope(), vm, timer: null, dismissed: false, last: '' }
  tasks.set(jobId, task)
  const current = () => tasks.get(jobId) === task && task.scope === scope()
  const publish = response => {
    const state = String(response.state || 'STARTING').toUpperCase()
    const step = response.step || 'REQUESTED'
    const finished = terminalStates.includes(state)
    const vmId = response.restoretargetvmid || vm?.id
    const name = response.restoretargetvmname || vm?.displayname || vm?.name || vmId || ''
    const vmState = response.restoretargetvmstate || vm?.state || ''
    const signature = JSON.stringify([state, step, vmId, vmState, response.details])
    if (task.last === signature) return
    task.last = signature
    const title = i18n.global.t('label.create.instance.from.backup')
    store.dispatch('AddHeaderNotice', {
      key: jobId,
      title,
      description: [name, step].filter(Boolean).join(' · '),
      path: vmId ? '/vm/' + vmId : '/backup',
      status: state === 'UNKNOWN' ? 'unknown' : finished ? state === 'COMPLETED' ? 'done' : 'failed' : 'progress',
      timestamp: new Date()
    })
    if (!task.dismissed || finished) {
      const notify = state === 'UNKNOWN' ? notification.warning : finished ? state === 'COMPLETED' ? notification.success : notification.error : notification.info
      notify({
        key: jobId,
        top: '65px',
        duration: 0,
        message: title,
        description: () => h('div', [
          h('div', `${i18n.global.t('label.state')}: ${state}`),
          h('div', `${i18n.global.t('label.step')}: ${step}`),
          vmId ? h('a', {
            href: router.resolve('/vm/' + vmId).href,
            onClick: event => { event.preventDefault(); router.push('/vm/' + vmId) }
          }, `${name}${vmState ? ' · ' + vmState : ''}`) : null,
          (finished || state === 'UNKNOWN') && state !== 'COMPLETED' && response.details ? h('div', response.details) : null
        ]),
        onClose: () => { task.dismissed = true }
      })
    }
    if (finished) {
      tasks.delete(jobId)
      eventBus.emit('backup-restore-updated', { backupId, vmId, state })
    }
  }
  const stopUnknown = () => {
    task.dismissed = false
    publish({ state: 'UNKNOWN', step: 'STATUS_UNAVAILABLE', details: i18n.global.t('message.job.result.unknown') })
    tasks.delete(jobId)
  }
  const poll = async () => {
    if (!current()) return
    try {
      const json = await getAPI('getBackupRestoreJobStatus', { id: backupId, limit: 1 }, { timeout: 15000, backgroundJob: true })
      if (!current()) return
      const raw = json?.getbackuprestorejobstatusresponse || {}
      const response = raw.state ? raw : Object.values(raw).find(value => value && typeof value === 'object' && value.state)
      // Match this creation, rather than a later restore using the same backup.
      if (response?.restoreoperationtype === 'CREATE_INSTANCE') {
        if (response.restoretargetvmid && response.restoretargetvmid !== vm?.id) stopUnknown()
        else publish(response)
      } else if (response) {
        stopUnknown()
      }
    } catch (error) {
      // A status query failure does not establish that the restore or VM startup failed.
      if (current() && [401, 403, 404].includes(error.response?.status)) stopUnknown()
    } finally {
      if (current()) task.timer = window.setTimeout(poll, 5000)
    }
  }
  publish({ state: 'STARTING', step: 'REQUESTED' })
  poll()
}
