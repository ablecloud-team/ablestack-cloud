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

import { postAPI } from '@/api'

export function isProjectAdministrator (project, user) {
  if (!project?.id || !user) return false
  if (['Admin', 'DomainAdmin'].includes(user.roletype)) return true
  if (!user.account || !user.domainid || project.domainid !== user.domainid) return false
  if (!Array.isArray(project.owner)) return project.account === user.account
  return project.owner.some(owner => owner.userid ? owner.userid === user.id : owner.account === user.account)
}

export function projectMemberActions (project, member, user, apis) {
  if (!isProjectAdministrator(project, user)) return []
  const actions = []
  const updateFields = apis.updateProject?.params?.map(field => field.name) || []
  const canTarget = updateFields.includes(member.userid ? 'userid' : 'account')
  const roleActions = canTarget && updateFields.includes('swapowner') && updateFields.includes('roletype')
  const soleOwner = project.owner?.length === 1
  const hasAnotherOwner = project.owner?.length > 1
  const isAdmin = member.role === 'Admin'
  if (!isAdmin && roleActions) {
    actions.push('promote')
    if (soleOwner) actions.push('transfer')
  }
  if (isAdmin && roleActions && hasAnotherOwner) actions.push('demote')
  if (canTarget && updateFields.includes('projectroleid') && updateFields.includes('clearprojectrole') && apis.listProjectRoles) {
    actions.push('customRole')
  }
  if ((!isAdmin || hasAnotherOwner) && apis[member.userid ? 'deleteUserFromProject' : 'deleteAccountFromProject']) {
    actions.push('remove')
  }
  return actions
}

export function memberOperation (projectId, member, action, projectRoleId) {
  const target = member.userid ? { userid: member.userid } : { account: member.account }
  if (action === 'remove') return [member.userid ? 'deleteUserFromProject' : 'deleteAccountFromProject', { ...target, projectid: projectId }]
  const params = { ...target, id: projectId }
  if (action === 'customRole') {
    Object.assign(params, projectRoleId ? { projectroleid: projectRoleId } : { clearprojectrole: true })
  } else {
    params.swapowner = action === 'transfer'
    if (!params.swapowner) params.roletype = action === 'demote' ? 'Regular' : 'Admin'
  }
  return ['updateProject', params]
}

export function eligibleProjectCandidates (kind, records, members, domainId) {
  const accountIds = new Set(members.filter(member => !member.userid).map(member => member.accountid))
  const userIds = new Set(members.map(member => member.userid).filter(Boolean))
  return records.filter(record => record.domainid === domainId && record.state === 'enabled' &&
    (kind === 'user' ? !userIds.has(record.id) && !accountIds.has(record.accountid) : !accountIds.has(record.id)))
}

export const projectMembershipJob = {
  data: () => ({ membershipJobId: '', membershipBusy: false, membershipUnknown: false, membershipError: '' }),
  computed: {
    membershipLocked () { return this.membershipBusy || !!this.membershipJobId || this.membershipUnknown }
  },
  methods: {
    resetMembershipJob () {
      this.membershipJobId = ''
      this.membershipUnknown = false
      this.membershipError = ''
    },
    async runMembershipJob (command, params, label) {
      if (this.membershipBusy || (this.membershipUnknown && !this.membershipJobId)) return false
      this.membershipBusy = true
      this.membershipError = ''
      const retry = !!this.membershipJobId
      try {
        if (!retry) {
          const response = await postAPI(command, { ...params, ignoreproject: true }, { preserveOnFailure: true, timeout: 30000 })
          this.membershipJobId = response?.[command.toLowerCase() + 'response']?.jobid || ''
          if (!this.membershipJobId) {
            this.membershipUnknown = true
            this.membershipError = this.$t('message.project.member.result.unknown')
            return false
          }
        }
        const result = await this.$pollJob({
          jobId: this.membershipJobId,
          retry,
          title: this.$t(label),
          action: { api: command, isFetchData: false, suppressErrorNotification: true }
        })
        if (result.jobstatus === 1) {
          this.resetMembershipJob()
          return true
        }
        if (result.jobstatus === 2) {
          this.membershipJobId = ''
          this.membershipError = result.jobresult?.errortext || this.$t('message.request.failed')
        } else {
          this.membershipError = this.$t('message.project.member.result.unknown')
        }
      } catch (error) {
        const detail = Object.values(error.response?.data || {}).find(value => value?.errortext)?.errortext
        this.membershipUnknown = !this.membershipJobId && !detail && !error.response
        this.membershipError = this.membershipUnknown || this.membershipJobId
          ? this.$t('message.project.member.result.unknown')
          : detail || this.$t('message.request.failed')
      } finally {
        this.membershipBusy = false
      }
      return false
    }
  }
}
