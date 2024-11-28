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

import store from '@/store'

export default {
  name: 'event',
  title: 'label.events',
  icon: 'ScheduleOutlined',
  docHelp: 'adminguide/events.html',
  permission: ['listEvents'],
  columns: () => {
    const fields = ['level', 'type', 'state', 'description', 'username', 'account', 'created']
    const securityFields = ['level', 'type', 'state', 'description', 'username',
      {
        account: (record) => {
          if (record.username === 'system') {
            return 'system'
          } else {
            return record.account
          }
        }
      }, 'created', 'clientip']
    if (store.getters.features.securityfeaturesenabled) {
      return securityFields
    } else {
      return fields
    }
  },
  details: ['username', 'id', 'description', 'resourcetype', 'resourceid', 'state', 'level', 'type', 'account', 'created'],
  searchFilters: () => {
    const filters = ['level', 'domainid', 'account', 'keyword', 'resourcetype']
    const securityFilters = ['level', 'domainid', 'keyword', 'resourcetype']
    if (store.getters.features.securityfeaturesenabled) {
      return securityFilters
    } else {
      return filters
    }
  },
  filters: () => {
    return ['active', 'archived']
  },
  actions: [
    {
      api: 'archiveEvents',
      icon: 'book-outlined',
      label: 'label.archive.events',
      message: 'message.confirm.archive.selected.events',
      docHelp: 'adminguide/events.html#deleting-and-archiving-events-and-alerts',
      dataView: true,
      successMessage: 'label.event.archived',
      groupAction: true,
      groupMap: (selection) => { return [{ ids: selection.join(',') }] },
      args: ['ids'],
      mapping: {
        ids: {
          value: (record) => { return record.id }
        }
      },
      show: (record) => {
        return !(record.archived)
      },
      groupShow: (selectedItems) => {
        return selectedItems.filter(x => { return !(x.archived) }).length > 0
      }
    },
    {
      api: 'downloadEvents',
      icon: 'download-outlined',
      label: 'label.download.events',
      message: 'message.confirm.archive.selected.events',
      docHelp: 'adminguide/events.html#deleting-and-archiving-events-and-alerts',
      dataView: true,
      successMessage: 'label.event.archived',
      groupAction: true,
      groupMap: (selection) => { return [{ ids: selection.join(',') }] },
      args: ['ids'],
      mapping: {
        ids: {
          value: (record) => { return record.id }
        }
      }
    },
    {
      api: 'deleteEvents',
      icon: 'delete-outlined',
      label: 'label.delete.events',
      message: 'message.confirm.remove.selected.events',
      docHelp: 'adminguide/events.html#deleting-and-archiving-events-and-alerts',
      dataView: true,
      successMessage: 'label.event.deleted',
      groupAction: false,
      show: () => { return (store.getters.features.eventdeleteenabled) },
      groupMap: (selection) => { return [{ ids: selection.join(',') }] },
      args: ['ids'],
      mapping: {
        ids: {
          value: (record) => { return record.id }
        }
      }
    }
  ]
}
